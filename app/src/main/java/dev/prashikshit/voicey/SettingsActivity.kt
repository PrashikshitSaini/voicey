package dev.prashikshit.voicey

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import dev.prashikshit.voicey.data.LanguageCatalog
import dev.prashikshit.voicey.data.LearnedCorrections
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.databinding.ActivitySettingsBinding
import dev.prashikshit.voicey.audio.MICROPHONE_AUTO
import dev.prashikshit.voicey.audio.MICROPHONE_PHONE
import dev.prashikshit.voicey.audio.MicrophoneDevices
import dev.prashikshit.voicey.service.FloatingBubbleService
import dev.prashikshit.voicey.service.FocusAccessibilityService

/**
 * One-screen settings + guided setup UI. Loads/saves Settings via the encrypted
 * KeyStore and walks the user through each permission or key it needs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var revertingLiveDraftToggle = false
    private var liveDraftDisclosurePending = false
    private var setupWasComplete: Boolean? = null
    private var microphoneOptions = emptyList<MicrophoneOption>()
    private var selectedMicrophone = MICROPHONE_AUTO
    private val microphoneDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
            runOnUiThread { refreshMicrophoneDevices() }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            runOnUiThread { refreshMicrophoneDevices() }
    }

    private val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppInfo()
            toast(getString(R.string.setup_permanently_denied))
        }
        refreshSetup()
    }

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppInfo()
            toast(getString(R.string.setup_permanently_denied))
        }
        refreshSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindFields(Settings.load(this))
        wireModelDropdowns()
        wireMicrophoneDropdown()
        refreshMicrophoneDevices()
        wireModelStatus()
        wireLiveDraftPreview()
        wireSetup()
        wireAdvancedHeader()
        wirePromptScrolling()
        wireBubbleToggle()
        wireResetButton()
        wireDictionaryButton()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshMicrophoneDevices()
        refreshBubbleButton()
        refreshDictionarySummary()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(microphoneDeviceCallback, null)
    }

    override fun onStop() {
        getSystemService(AudioManager::class.java)
            .unregisterAudioDeviceCallback(microphoneDeviceCallback)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // The switch is already reverted before a disclosure is shown. Clear the
        // pending marker here too, so navigating away can never turn it on later.
        liveDraftDisclosurePending = false
        save()
    }

    private fun bindFields(settings: Settings) = with(binding) {
        inputApiBase.setText(settings.apiBase)
        inputApiKey.setText(settings.apiKey)
        // Second arg `false` skips the autocomplete filter so binding a saved value
        // doesn't pop the suggestion list open underneath it.
        inputTranscriptionModel.setText(settings.transcriptionModel, false)
        inputCleanupModel.setText(settings.cleanupModel, false)
        inputLanguage.setText(LanguageCatalog.labelForCode(settings.language), false)
        inputPrompt.setText(settings.systemPrompt)
        switchHoldToTalk.isChecked = settings.holdToTalk
        switchShowOnlyWhileTyping.isChecked = settings.showOnlyWhileTyping
        switchSoundFeedback.isChecked = settings.soundFeedback
        switchLiveDraftPreview.isChecked = settings.liveDraftPreview
        switchLearnCorrections.isChecked = settings.learnCorrections
        switchNeverUseClipboard.isChecked = settings.neverUseClipboard
        switchSmartFormatting.isChecked = settings.smartFormatting
        switchCompactBubble.isChecked = settings.compactBubble
        selectedMicrophone = settings.microphoneSelection
    }

    /**
     * Model fields are editable autocompletes: pick a Groq suggestion from the dropdown
     * or type any model id for other providers. The adapter never filters, so tapping
     * the field (or its arrow) always offers the full list even when text is present.
     */
    private fun wireModelDropdowns() {
        binding.inputTranscriptionModel.setAdapter(
            NoFilterArrayAdapter(this, Settings.TRANSCRIPTION_MODEL_SUGGESTIONS)
        )
        binding.inputCleanupModel.setAdapter(
            NoFilterArrayAdapter(this, Settings.CLEANUP_MODEL_SUGGESTIONS)
        )
        binding.inputLanguage.setAdapter(
            NoFilterArrayAdapter(this, LanguageCatalog.labels)
        )
    }

    private fun wireMicrophoneDropdown() {
        binding.inputMicrophone.setOnItemClickListener { _, _, position, _ ->
            selectedMicrophone = microphoneOptions.getOrNull(position)?.key ?: MICROPHONE_AUTO
        }
    }

    private fun refreshMicrophoneDevices() {
        val audioManager = getSystemService(AudioManager::class.java)
        microphoneOptions = buildList {
            add(MicrophoneOption(MICROPHONE_AUTO, getString(R.string.microphone_automatic)))
            add(MicrophoneOption(MICROPHONE_PHONE, getString(R.string.microphone_phone)))
            val keys = mutableSetOf(MICROPHONE_AUTO, MICROPHONE_PHONE)
            MicrophoneDevices.connectedExternalInputs(audioManager)
                .forEach { device ->
                    val key = MicrophoneDevices.stableKey(device)
                    if (keys.add(key)) add(MicrophoneOption(key, microphoneLabel(device)))
                }
            // A pinned device that is away stays selected until the user picks something else.
            if (keys.add(selectedMicrophone)) {
                add(MicrophoneOption(selectedMicrophone, getString(R.string.microphone_saved_disconnected)))
            }
        }

        binding.inputMicrophone.setAdapter(
            NoFilterArrayAdapter(this, microphoneOptions.map(MicrophoneOption::label))
        )
        binding.inputMicrophone.setText(
            microphoneOptions.first { it.key == selectedMicrophone }.label,
            false,
        )
    }

    private fun microphoneLabel(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.trim().orEmpty()
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
            else -> "External microphone"
        }
        return if (name.isBlank() || name.equals(type, ignoreCase = true)) type else "$name · $type"
    }

    private fun wireModelStatus() {
        binding.inputCleanupModel.doAfterTextChanged { refreshModelStatus() }
        refreshModelStatus()
    }

    private fun wireLiveDraftPreview() {
        binding.switchLiveDraftPreview.setOnCheckedChangeListener { _, enabled ->
            if (!enabled || revertingLiveDraftToggle) return@setOnCheckedChangeListener
            if (Settings.load(this).liveDraftPreviewDisclosureAccepted) {
                return@setOnCheckedChangeListener
            }
            // A toggle is not consent. Revert it before showing the disclosure so
            // Back, tapping outside, or leaving this screen always retains "off".
            revertingLiveDraftToggle = true
            binding.switchLiveDraftPreview.isChecked = false
            revertingLiveDraftToggle = false
            liveDraftDisclosurePending = true
            AlertDialog.Builder(this)
                .setTitle(R.string.live_draft_preview_confirm_title)
                .setMessage(R.string.live_draft_preview_confirm_message)
                .setPositiveButton(R.string.live_draft_preview_turn_on) { _, _ ->
                    if (!liveDraftDisclosurePending) return@setPositiveButton
                    liveDraftDisclosurePending = false
                    Settings.save(
                        this,
                        Settings.load(this).copy(liveDraftPreviewDisclosureAccepted = true),
                    )
                    revertingLiveDraftToggle = true
                    binding.switchLiveDraftPreview.isChecked = true
                    revertingLiveDraftToggle = false
                }
                .setNegativeButton(R.string.live_draft_preview_not_now, null)
                .setOnDismissListener { liveDraftDisclosurePending = false }
                .show()
        }
    }

    private fun refreshModelStatus() {
        val model = binding.inputCleanupModel.text?.toString()?.trim().orEmpty()
        binding.layoutCleanupModel.helperText = Settings.DEPRECATED_GROQ_CLEANUP_MODELS[model]
    }

    private fun save() {
        // Custom vocabulary is managed on its dedicated screen. Reload it here so
        // leaving Settings can never overwrite dictionary edits with a stale copy.
        val stored = Settings.load(this)
        val current = Settings(
            apiBase = binding.inputApiBase.text?.toString().orEmpty(),
            apiKey = binding.inputApiKey.text?.toString().orEmpty(),
            transcriptionModel = binding.inputTranscriptionModel.text?.toString().orEmpty(),
            cleanupModel = binding.inputCleanupModel.text?.toString().orEmpty(),
            vocabulary = stored.vocabulary,
            systemPrompt = binding.inputPrompt.text?.toString().orEmpty()
                .ifBlank { Settings.DEFAULT_SYSTEM_PROMPT },
            holdToTalk = binding.switchHoldToTalk.isChecked,
            language = LanguageCatalog.codeForLabel(
                binding.inputLanguage.text?.toString().orEmpty()
            ),
            showOnlyWhileTyping = binding.switchShowOnlyWhileTyping.isChecked,
            soundFeedback = binding.switchSoundFeedback.isChecked,
            learnCorrections = binding.switchLearnCorrections.isChecked,
            neverUseClipboard = binding.switchNeverUseClipboard.isChecked,
            smartFormatting = binding.switchSmartFormatting.isChecked,
            liveDraftPreview = binding.switchLiveDraftPreview.isChecked,
            liveDraftPreviewDisclosureAccepted = stored.liveDraftPreviewDisclosureAccepted,
            microphoneSelection = selectedMicrophone,
            compactBubble = binding.switchCompactBubble.isChecked,
            compactBubbleEdgeRight = stored.compactBubbleEdgeRight,
            compactBubbleVerticalFraction = stored.compactBubbleVerticalFraction,
        )
        Settings.save(this, current)
    }

    private fun wireDictionaryButton() {
        binding.btnOpenDictionary.setOnClickListener {
            save()
            startActivity(Intent(this, DictionaryActivity::class.java))
        }
    }

    private fun refreshDictionarySummary() {
        val customCount = Settings.load(this).vocabulary.size
        val learnedCount = LearnedCorrections(this).count()
        binding.textDictionarySummary.text = getString(
            R.string.dictionary_summary,
            customCount,
            learnedCount,
        )
    }

    private fun wireSetup() = with(binding) {
        val notificationsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        rowSetupNotifications.visibility = if (notificationsApplicable) View.VISIBLE else View.GONE

        inputApiKey.doAfterTextChanged { refreshSetup() }

        btnSetupApiKey.setOnClickListener { focusApiKey() }
        btnGetGroqKey.setOnClickListener { openUrl("https://console.groq.com/keys") }
        btnSetupMic.setOnClickListener {
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
        btnSetupNotifications.setOnClickListener {
            if (notificationsApplicable) {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                refreshSetup()
            }
        }
        btnSetupOverlay.setOnClickListener {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        btnSetupAccessibility.setOnClickListener { openAccessibilitySettings() }
        // Android 13+ sideloads grey out the Accessibility switch until "Allow restricted
        // settings" is granted from the App Info ⋮ menu; there is no public Intent for it.
        btnRestrictedSettings.setOnClickListener {
            openAppInfo()
            longToast(getString(R.string.setup_restricted_toast))
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("No browser found")
        }
    }

    private fun focusApiKey() {
        binding.scrollRoot.smoothScrollTo(0, binding.cardAi.top)
        binding.inputApiKey.requestFocus()
        binding.inputApiKey.postDelayed({
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(binding.inputApiKey, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun wireAdvancedHeader() {
        val startExpanded = binding.layoutCleanupModel.helperText != null
        binding.containerAdvanced.visibility = if (startExpanded) View.VISIBLE else View.GONE
        binding.iconAdvancedChevron.rotation = if (startExpanded) 180f else 0f
        binding.rowAdvancedHeader.setOnClickListener { toggleAdvanced() }
    }

    private fun toggleAdvanced() {
        val expanded = binding.containerAdvanced.visibility == View.VISIBLE
        binding.containerAdvanced.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconAdvancedChevron.rotation = if (expanded) 0f else 180f
    }

    private fun wirePromptScrolling() {
        // Let the prompt scroll internally instead of the page scrolling under it.
        binding.inputPrompt.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun openAppInfo() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val key = ComponentName(this, FocusAccessibilityService::class.java).flattenToString()
        val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.putExtra(":settings:fragment_args_key", key)
        intent.putExtra(
            ":settings:show_fragment_args",
            Bundle().apply { putString(":settings:fragment_args_key", key) },
        )
        startActivity(intent)
        longToast(getString(R.string.setup_accessibility_toast))
    }

    /**
     * Restores the fields that ship with the app (API base, models, language, system
     * prompt) to their defaults. User-specific data — the API key, custom vocabulary,
     * and hold-to-talk preference — is intentionally preserved, because losing those
     * to an accidental tap would be a worse user experience than a few leftover
     * customizations.
     */
    private fun wireResetButton() {
        binding.btnResetDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.reset_dialog_title)
                .setMessage(R.string.reset_dialog_message)
                .setPositiveButton(R.string.reset_confirm) { _, _ -> performReset() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun performReset() {
        val current = Settings.load(this)
        val restored = current.copy(
            apiBase = Settings.DEFAULT_API_BASE,
            transcriptionModel = Settings.DEFAULT_TRANSCRIPTION_MODEL,
            cleanupModel = Settings.DEFAULT_CLEANUP_MODEL,
            systemPrompt = Settings.DEFAULT_SYSTEM_PROMPT,
            language = Settings.DEFAULT_LANGUAGE,
        )
        Settings.save(this, restored)
        bindFields(restored)
        refreshModelStatus()
        toast(getString(R.string.reset_complete))
    }

    private fun wireBubbleToggle() {
        binding.btnToggleBubble.setOnClickListener {
            save()
            if (isBubbleRunning()) {
                FloatingBubbleService.stop(this)
            } else {
                val pending = firstPendingStep()
                if (pending != null) {
                    toast(getString(R.string.setup_finish_first, pending))
                    return@setOnClickListener
                }
                FloatingBubbleService.start(this)
            }
            // Slight delay so the service state has time to flip before re-render.
            binding.btnToggleBubble.postDelayed({ refreshBubbleButton() }, 250)
        }
    }

    private fun refreshSetup() {
        val apiKeyDone = binding.inputApiKey.text?.isNotBlank() == true
        val micDone = hasMicPermission()
        val notificationsDone = hasNotificationPermission()
        val overlayDone = canDrawOverlays()
        val accessibilityDone = isAccessibilityEnabled()
        val steps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(apiKeyDone, micDone, notificationsDone, overlayDone, accessibilityDone)
        } else {
            listOf(apiKeyDone, micDone, overlayDone, accessibilityDone)
        }
        val doneCount = steps.count { it }

        setStepIcon(binding.iconSetupApiKey, apiKeyDone)
        setStepIcon(binding.iconSetupMic, micDone)
        setStepIcon(binding.iconSetupNotifications, notificationsDone)
        setStepIcon(binding.iconSetupOverlay, overlayDone)
        setStepIcon(binding.iconSetupAccessibility, accessibilityDone)
        binding.btnSetupApiKey.visibility = stepVisibility(apiKeyDone)
        binding.btnSetupMic.visibility = stepVisibility(micDone)
        binding.btnSetupNotifications.visibility = stepVisibility(notificationsDone)
        binding.btnSetupOverlay.visibility = stepVisibility(overlayDone)
        binding.btnSetupAccessibility.visibility = stepVisibility(accessibilityDone)
        binding.btnGetGroqKey.visibility = stepVisibility(apiKeyDone)
        val restrictedApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        binding.btnRestrictedSettings.visibility =
            if (restrictedApplicable && !accessibilityDone) View.VISIBLE else View.GONE

        binding.textSetupProgress.text = getString(R.string.setup_progress, doneCount, steps.size)
        binding.progressSetup.max = steps.size
        binding.progressSetup.setProgressCompat(doneCount, true)

        val complete = doneCount == steps.size
        binding.cardSetup.visibility = if (complete) View.GONE else View.VISIBLE
        val wasComplete = setupWasComplete
        setupWasComplete = complete
        // Only auto-start on a transition inside this screen, never on a plain launch.
        if (wasComplete == false && complete) {
            save()
            if (!isBubbleRunning()) FloatingBubbleService.start(this)
            binding.btnToggleBubble.postDelayed({ refreshBubbleButton() }, 250)
            toast(getString(R.string.setup_complete_toast))
        }
    }

    private fun setStepIcon(icon: ImageView, done: Boolean) {
        icon.setImageResource(
            if (done) R.drawable.ic_setup_check_circle else R.drawable.ic_setup_circle
        )
        val attr = if (done) {
            androidx.appcompat.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOutline
        }
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, attr))
    }

    private fun stepVisibility(done: Boolean): Int =
        if (done) View.GONE else View.VISIBLE

    private fun firstPendingStep(): String? {
        if (binding.inputApiKey.text.isNullOrBlank()) {
            return getString(R.string.setup_step_api_key_title)
        }
        if (!hasMicPermission()) return getString(R.string.setup_step_mic_title)
        if (!hasNotificationPermission()) return getString(R.string.setup_step_notifications_title)
        if (!canDrawOverlays()) return getString(R.string.setup_step_overlay_title)
        if (!isAccessibilityEnabled()) return getString(R.string.setup_step_accessibility_title)
        return null
    }

    private fun refreshBubbleButton() {
        val running = isBubbleRunning()
        binding.btnToggleBubble.setText(
            if (running) R.string.turn_off_voicey else R.string.turn_on_voicey
        )
        binding.textBubbleStatus.setText(
            if (running) R.string.bubble_status_on else R.string.bubble_status_off
        )
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun canDrawOverlays(): Boolean = AndroidSettings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        if (FocusAccessibilityService.isEnabled()) return true
        val component = ComponentName(this, FocusAccessibilityService::class.java)
        val enabled = AndroidSettings.Secure.getString(
            contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    @Suppress("DEPRECATION")
    private fun isBubbleRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FloatingBubbleService::class.java.name }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun longToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * An ArrayAdapter whose filter always returns every item. AutoCompleteTextView
     * normally filters suggestions by the current text, which would hide all options
     * once a saved model id fills the field — for a pick-or-type dropdown we always
     * want the full list.
     */
    private class NoFilterArrayAdapter(
        context: Context,
        items: List<String>,
    ) : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {

        private val allItems = items.toList()

        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults =
                FilterResults().apply {
                    values = allItems
                    count = allItems.size
                }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = noFilter
    }

    private data class MicrophoneOption(val key: String, val label: String)
}
