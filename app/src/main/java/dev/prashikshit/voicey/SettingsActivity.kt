package dev.prashikshit.voicey

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.databinding.ActivitySettingsBinding
import dev.prashikshit.voicey.service.FloatingBubbleService
import dev.prashikshit.voicey.service.FocusAccessibilityService

/**
 * One-screen settings + permission grant UI. Loads/saves Settings via the encrypted
 * KeyStore and provides quick links to the three system permission screens we need.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val requestRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Microphone enabled") else toast("Microphone permission required")
        refreshPermissionStates()
    }

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("Notifications enabled") else toast("Notification permission required for the bubble")
        refreshPermissionStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindFields(Settings.load(this))
        wirePermissionButtons()
        wireBubbleToggle()
        wireResetButton()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
        refreshBubbleButton()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun bindFields(settings: Settings) = with(binding) {
        inputApiBase.setText(settings.apiBase)
        inputApiKey.setText(settings.apiKey)
        inputTranscriptionModel.setText(settings.transcriptionModel)
        inputCleanupModel.setText(settings.cleanupModel)
        inputLanguage.setText(settings.language)
        inputVocabulary.setText(settings.vocabulary.joinToString("\n"))
        inputPrompt.setText(settings.systemPrompt)
        switchHoldToTalk.isChecked = settings.holdToTalk
    }

    private fun save() {
        val current = Settings(
            apiBase = binding.inputApiBase.text?.toString().orEmpty(),
            apiKey = binding.inputApiKey.text?.toString().orEmpty(),
            transcriptionModel = binding.inputTranscriptionModel.text?.toString().orEmpty(),
            cleanupModel = binding.inputCleanupModel.text?.toString().orEmpty(),
            vocabulary = binding.inputVocabulary.text?.toString().orEmpty()
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
            systemPrompt = binding.inputPrompt.text?.toString().orEmpty()
                .ifBlank { Settings.DEFAULT_SYSTEM_PROMPT },
            holdToTalk = binding.switchHoldToTalk.isChecked,
            language = binding.inputLanguage.text?.toString().orEmpty()
                .ifBlank { Settings.DEFAULT_LANGUAGE },
        )
        Settings.save(this, current)
    }

    private fun wirePermissionButtons() = with(binding) {
        btnPermissionMic.setOnClickListener {
            requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
        btnPermissionNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                toast("Not required on this Android version")
                refreshPermissionStates()
            }
        }
        btnPermissionOverlay.setOnClickListener {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        btnPermissionAccessibility.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable \"${getString(R.string.accessibility_label)}\" in the list")
        }
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
        toast(getString(R.string.reset_complete))
    }

    private fun wireBubbleToggle() {
        binding.btnToggleBubble.setOnClickListener {
            save()
            if (isBubbleRunning()) {
                FloatingBubbleService.stop(this)
            } else {
                if (!canRunBubble()) {
                    toast("Grant all permissions above first")
                    return@setOnClickListener
                }
                FloatingBubbleService.start(this)
            }
            // Slight delay so the service state has time to flip before re-render.
            binding.btnToggleBubble.postDelayed({ refreshBubbleButton() }, 250)
        }
    }

    private fun refreshPermissionStates() {
        binding.btnPermissionMic.setIconResource(
            if (hasMicPermission()) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        binding.btnPermissionNotification.setIconResource(
            if (hasNotificationPermission()) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        binding.btnPermissionOverlay.setIconResource(
            if (canDrawOverlays()) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        binding.btnPermissionAccessibility.setIconResource(
            if (FocusAccessibilityService.isEnabled()) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
    }

    private fun refreshBubbleButton() {
        binding.btnToggleBubble.setText(
            if (isBubbleRunning()) R.string.stop_bubble else R.string.start_bubble
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

    private fun canRunBubble(): Boolean =
        hasMicPermission() && hasNotificationPermission() &&
            canDrawOverlays() && FocusAccessibilityService.isEnabled()

    @Suppress("DEPRECATION")
    private fun isBubbleRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FloatingBubbleService::class.java.name }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
