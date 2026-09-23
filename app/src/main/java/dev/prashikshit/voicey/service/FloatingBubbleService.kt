package dev.prashikshit.voicey.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleService
import dev.prashikshit.voicey.R
import dev.prashikshit.voicey.SettingsActivity
import dev.prashikshit.voicey.VoiceyApp
import dev.prashikshit.voicey.data.Correction
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.ui.SpectrumView
import kotlin.math.abs

/**
 * Foreground service that draws the dictation pill on top of every app. Tap to toggle
 * recording; long-press (when "hold to talk" is on) to record while held.
 *
 * When "show only while typing" is enabled and the accessibility service is connected,
 * the pill appears centered above the keyboard the moment it opens and hides the moment
 * it closes (never mid-dictation — recording/processing pin it visible). If keyboard
 * detection is unavailable (accessibility off) the pill falls back to always-visible,
 * so a detection failure can never strand the user with an invisible control.
 *
 * Runs as a foreground service with a persistent low-importance notification because
 * SYSTEM_ALERT_WINDOW overlays drawn by background services get torn down on modern
 * Android. The notification is the price of admission.
 */
class FloatingBubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var bubbleLabel: TextView
    private lateinit var bubbleIcon: ImageView
    private lateinit var bubbleProgress: ProgressBar
    private lateinit var bubbleSpectrum: SpectrumView
    private lateinit var bubbleDiscard: ImageButton
    private lateinit var liveDraftContent: View
    private lateinit var liveDraftStatus: TextView
    private lateinit var liveDraftText: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var injector: TextInjector
    private lateinit var pipeline: Pipeline
    private val mainHandler = Handler(Looper.getMainLooper())
    private var learningCardView: View? = null
    private val dismissLearningCardRunnable = Runnable { removeLearningCard() }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isLongPressing = false
    private val touchSlopPx by lazy { (resources.displayMetrics.density * 8).toInt() }
    private val compactTouchSlopPx by lazy { (resources.displayMetrics.density * 12).toInt() }
    private val longPressMs = 350L
    private val longPressRunnable = Runnable { startHoldToTalk() }

    private val density: Float get() = resources.displayMetrics.density
    private val pillWidthPx: Int get() = (PILL_WIDTH_DP * density).toInt()
    private val pillHeightPx: Int get() = (PILL_HEIGHT_DP * density).toInt()
    private val compactSizePx: Int get() = (COMPACT_SIZE_DP * density).toInt()
    private val compactIconSizePx: Int get() = (COMPACT_ICON_SIZE_DP * density).toInt()
    private val compactSpectrumWidthPx: Int get() = (COMPACT_SPECTRUM_WIDTH_DP * density).toInt()
    private val compactSpectrumHeightPx: Int get() = (COMPACT_SPECTRUM_HEIGHT_DP * density).toInt()
    private val draftHeightPx: Int get() = (DRAFT_HEIGHT_DP * density).toInt()
    private val draftWidthPx: Int get() = minOf(
        (DRAFT_WIDTH_DP * density).toInt(),
        resources.displayMetrics.widthPixels - (32 * density).toInt(),
    )

    // Cached on service start so we don't decrypt EncryptedSharedPreferences on every
    // ACTION_DOWN event. Setting changes apply on bubble restart.
    @Volatile
    private var holdToTalkEnabled: Boolean = true
    private var showOnlyWhileTyping: Boolean = true
    private var compactBubbleEnabled: Boolean = false
    private var compactBubbleEdgeRight: Boolean = false
    private var compactBubbleVerticalFraction: Float = 0.5f

    /** Null when the user has disabled sound feedback in settings. */
    private var soundFeedback: SoundFeedback? = null

    /** Last keyboard state delivered by the accessibility service. Main thread only. */
    private var keyboardWantsBubble = false
    private var lastKeyboardTop = FocusAccessibilityService.NO_KEYBOARD_TOP

    override fun onCreate() {
        super.onCreate()
        // Start with the microphone type *off*. We only claim it during actual recording
        // so the system's mic-in-use indicator (green dot, "App is using your microphone"
        // banner) is not shown while the bubble is idle, and the OS doesn't keep the
        // mic radio warm — better for both privacy UX and battery.
        startInForeground(includeMicrophone = false)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        injector = TextInjector(this)
        pipeline = Pipeline(
            context = this,
            injector = injector,
            onStateChanged = ::renderState,
            onMessage = ::showTransientMessage,
            onAudioLevel = ::onAudioLevel,
            onLiveDraftChanged = ::renderLiveDraft,
        )
        val settings = Settings.load(this)
        holdToTalkEnabled = settings.holdToTalk
        showOnlyWhileTyping = settings.showOnlyWhileTyping
        compactBubbleEnabled = settings.compactBubble
        compactBubbleEdgeRight = settings.compactBubbleEdgeRight
        compactBubbleVerticalFraction = settings.compactBubbleVerticalFraction
        if (settings.soundFeedback) soundFeedback = SoundFeedback(this)
        addBubble()
        CorrectionLearner.setFeedbackListener(::showLearningFeedback)
        // Delivers the current keyboard state immediately, so the pill starts hidden
        // when no keyboard is open (and positioned correctly when one already is).
        FocusAccessibilityService.setKeyboardListener(::onKeyboardStateChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubbleView.isInitialized && compactBubbleEnabled) {
            if (keyboardWantsBubble && lastKeyboardTop != FocusAccessibilityService.NO_KEYBOARD_TOP) {
                positionAboveKeyboard(lastKeyboardTop)
            } else {
                reapplyCompactPosition()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        CorrectionLearner.setFeedbackListener(null)
        FocusAccessibilityService.setKeyboardListener(null)
        mainHandler.removeCallbacksAndMessages(null)
        removeLearningCard()
        soundFeedback?.release()
        soundFeedback = null
        pipeline.shutdown()
        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            windowManager.removeView(bubbleView)
        }
        super.onDestroy()
    }

    /**
     * Called when the user swipes the Voicey launcher activity off the recents stack.
     *
     * Even though we're a foreground service, several OEM Android skins (notably Xiaomi,
     * OPPO, Realme, and Samsung's older "memory saver") kill foreground services anyway
     * when their app's task is removed. The recommended workaround is to schedule a
     * one-shot inexact alarm to restart the service a beat later. The restart is allowed
     * because we were foreground at the moment we scheduled it.
     *
     * If the user explicitly stops the bubble via the notification action, [stopSelf] is
     * called in onStartCommand and onDestroy fires before onTaskRemoved would — so this
     * path only runs when the kill was OEM-initiated, not user-initiated.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, FloatingBubbleService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fireAt = System.currentTimeMillis() + RESTART_DELAY_MS

        // Android 12+ blocks foreground services from being started while the app is in
        // the background unless the start is delivered by an *exact* alarm. An inexact
        // setAndAllowWhileIdle does NOT grant the same exemption, so we must use
        // setExactAndAllowWhileIdle here — otherwise the restart silently fails on the
        // majority of installs. We fall back to inexact only if the user has revoked
        // SCHEDULE_EXACT_ALARM (rare; better than crashing).
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAt,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireAt,
                pendingIntent,
            )
        }
        // Intentionally NOT calling super — the default behavior on some OEMs stops the
        // service immediately, which races our restart alarm. We let the alarm be the
        // single source of truth for whether we come back.
    }

    /**
     * (Re-)enters the foreground state with the requested service-type set.
     *
     * Safe to call repeatedly while the service is alive; subsequent calls update the
     * service's claimed foreground types without restarting the service or re-posting
     * the notification visibly to the user. We toggle [includeMicrophone] on/off so the
     * OS only marks us as actively using the mic during real recording sessions.
     */
    private fun startInForeground(includeMicrophone: Boolean) {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // Android 14+ requires the microphone FGS type whenever AudioRecord runs
                // from a foreground service. We OR it in only when we're actively recording.
                val type = if (includeMicrophone) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(VoiceyApp.NOTIFICATION_ID, notification, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeMicrophone -> {
                // Android 10–13: declaring the microphone type during the recording
                // window keeps the mic-in-use indicator scoped to that window.
                startForeground(
                    VoiceyApp.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
            else -> {
                // Android 8–9: no foregroundServiceType concept; just keep us foreground.
                startForeground(VoiceyApp.NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        return NotificationCompat.Builder(this, VoiceyApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.stop_bubble), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun addBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null) as FrameLayout
        bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
        bubbleProgress = bubbleView.findViewById(R.id.bubble_progress)
        bubbleLabel = bubbleView.findViewById(R.id.bubble_label)
        bubbleSpectrum = bubbleView.findViewById(R.id.bubble_spectrum)
        bubbleDiscard = bubbleView.findViewById(R.id.bubble_discard)
        liveDraftContent = bubbleView.findViewById(R.id.live_draft_content)
        liveDraftStatus = bubbleView.findViewById(R.id.live_draft_status)
        liveDraftText = bubbleView.findViewById(R.id.live_draft_text)
        if (compactBubbleEnabled) configureCompactLayout()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Explicit pixel dimensions, NOT WRAP_CONTENT: a layout inflated with a null
        // parent has its root layout_width/height ignored, so a WRAP_CONTENT window
        // collapses to the icon's size — the old bubble's touch target was 28dp for
        // exactly this reason. Fixed size guarantees the full pill is tappable.
        layoutParams = WindowManager.LayoutParams(
            if (compactBubbleEnabled) compactSizePx else pillWidthPx,
            if (compactBubbleEnabled) compactSizePx else pillHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (compactBubbleEnabled) compactX() else (resources.displayMetrics.widthPixels - pillWidthPx) / 2
            y = if (compactBubbleEnabled) compactY() else {
                resources.displayMetrics.heightPixels - pillHeightPx - (FALLBACK_BOTTOM_MARGIN_DP * density).toInt()
            }
        }

        bubbleDiscard.setOnClickListener(::discardCurrentDictation)
        bubbleView.setOnTouchListener(::handleTouch)
        // Start invisible when keyboard-aware mode will decide visibility; the listener
        // registration in onCreate immediately delivers the real state.
        if (keyboardAwareActive()) {
            bubbleView.visibility = View.GONE
            bubbleView.alpha = 0f
        }
        windowManager.addView(bubbleView, layoutParams)
    }

    /**
     * Keyboard-aware mode needs both the user preference and a live accessibility
     * connection. Without the connection there is no keyboard signal, so we degrade
     * to the always-visible bubble rather than hiding a control we can't restore.
     */
    private fun keyboardAwareActive(): Boolean =
        showOnlyWhileTyping && FocusAccessibilityService.isEnabled()

    /** Called on the main thread by FocusAccessibilityService for keyboard/focus changes. */
    private fun onKeyboardStateChanged(shouldShow: Boolean, keyboardTop: Int) {
        val keyboardChanged = keyboardWantsBubble != shouldShow || lastKeyboardTop != keyboardTop
        keyboardWantsBubble = shouldShow
        lastKeyboardTop = keyboardTop
        if (keyboardChanged) {
            if (shouldShow && keyboardTop != FocusAccessibilityService.NO_KEYBOARD_TOP) {
                positionAboveKeyboard(keyboardTop)
            } else if (!shouldShow && compactBubbleEnabled) {
                reapplyCompactPosition()
            }
        }
        applyBubbleVisibility()
    }

    private fun positionAboveKeyboard(keyboardTop: Int) {
        if (compactBubbleEnabled) {
            layoutParams.x = compactX()
            layoutParams.y = minOf(
                compactY(),
                (keyboardTop - compactSizePx - (PILL_KEYBOARD_MARGIN_DP * density).toInt())
                    .coerceAtLeast(0),
            )
            if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
                windowManager.updateViewLayout(bubbleView, layoutParams)
            }
            return
        }
        layoutParams.x = (resources.displayMetrics.widthPixels - pillWidthPx) / 2
        layoutParams.y = (keyboardTop - pillHeightPx - (PILL_KEYBOARD_MARGIN_DP * density).toInt())
            .coerceAtLeast(0)
        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            windowManager.updateViewLayout(bubbleView, layoutParams)
        }
    }

    private fun desiredVisible(): Boolean {
        if (!keyboardAwareActive()) return true
        // Never yank the pill mid-dictation: recording and processing pin it visible.
        if (currentState == Pipeline.State.RECORDING || currentState == Pipeline.State.PROCESSING) {
            return true
        }
        if (!compactBubbleEnabled) return keyboardWantsBubble
        return keyboardWantsBubble && when (FocusAccessibilityService.activeEditorStatus()) {
            FocusAccessibilityService.ActiveEditorStatus.ACTIVE,
            FocusAccessibilityService.ActiveEditorStatus.UNKNOWN -> true
            FocusAccessibilityService.ActiveEditorStatus.INACTIVE -> false
        }
    }

    private fun applyBubbleVisibility() {
        if (!::bubbleView.isInitialized) return
        if (desiredVisible()) {
            // Skip if already fully shown; otherwise cancel any in-flight hide-fade —
            // without the cancel, a fade ending after this call would strand the pill
            // at alpha 0 while still VISIBLE (invisible but swallowing touches).
            if (bubbleView.visibility == View.VISIBLE && bubbleView.alpha == 1f) return
            bubbleView.animate().cancel()
            bubbleView.visibility = View.VISIBLE
            bubbleView.animate().alpha(1f).setDuration(FADE_MS).start()
        } else if (bubbleView.visibility == View.VISIBLE) {
            bubbleView.animate().cancel()
            bubbleView.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction {
                    // Re-check: state may have flipped back to visible during the fade.
                    if (!desiredVisible()) bubbleView.visibility = View.GONE
                }
                .start()
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                isLongPressing = false
                if (holdToTalkEnabled) {
                    mainHandler.postDelayed(longPressRunnable, longPressMs)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val dragSlop = if (compactBubbleEnabled) compactTouchSlopPx else touchSlopPx
                if (!isDragging && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                    isDragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    layoutParams.x = if (compactBubbleEnabled) {
                        (initialX + dx).toInt().coerceIn(compactMinX(), compactMaxX())
                    } else {
                        (initialX + dx).toInt()
                    }
                    layoutParams.y = if (compactBubbleEnabled) {
                        (initialY + dy).toInt().coerceIn(0, compactMaxY())
                    } else {
                        (initialY + dy).toInt()
                    }
                    windowManager.updateViewLayout(bubbleView, layoutParams)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                when {
                    isLongPressing -> {
                        // Hold-to-talk: release stops recording.
                        pipeline.stopAndProcess()
                        if (isDragging && compactBubbleEnabled) snapCompactToEdge()
                        isLongPressing = false
                    }
                    // The compact control always docks to an edge. The full pill keeps
                    // its existing free-placement behavior.
                    isDragging && compactBubbleEnabled -> snapCompactToEdge()
                    isDragging -> Unit
                    else -> toggleRecording()
                }
                isDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (isLongPressing) pipeline.stopAndProcess()
                if (isDragging && compactBubbleEnabled) snapCompactToEdge()
                isLongPressing = false
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun toggleRecording() {
        if (pipeline_isRecording()) {
            pipeline.stopAndProcess()
        } else {
            pipeline.startRecording()
        }
    }

    /** Stops and permanently drops the active recording before any transcription starts. */
    private fun discardCurrentDictation(view: View) {
        if (currentState != Pipeline.State.RECORDING) return
        pipeline.cancel()
        view.announceForAccessibility(getString(R.string.dictation_discarded))
        showTransientMessage(getString(R.string.dictation_discarded))
    }

    private fun startHoldToTalk() {
        isLongPressing = true
        pipeline.startRecording()
    }

    private fun pipeline_isRecording(): Boolean = currentState == Pipeline.State.RECORDING

    @Volatile
    private var currentState: Pipeline.State = Pipeline.State.IDLE

    private fun renderState(state: Pipeline.State) {
        val previousState = currentState
        currentState = state

        // Only claim the microphone foreground-service type while we're actually
        // capturing audio. PROCESSING is HTTP-only and IDLE/ERROR don't touch the mic,
        // so dropping the claim immediately releases the OS mic-in-use indicator and
        // lets the platform put the mic radio back to sleep.
        startInForeground(includeMicrophone = state == Pipeline.State.RECORDING)

        mainHandler.post {
            if (state == Pipeline.State.RECORDING && previousState != Pipeline.State.RECORDING) {
                soundFeedback?.playStart()
            } else if (state != Pipeline.State.RECORDING && previousState == Pipeline.State.RECORDING) {
                soundFeedback?.playStop()
            }

            val colorRes = when (state) {
                Pipeline.State.IDLE -> R.color.bubble_idle
                Pipeline.State.RECORDING -> R.color.bubble_recording
                Pipeline.State.PROCESSING -> R.color.bubble_processing
                Pipeline.State.ERROR -> R.color.bubble_recording
            }
            val background = ContextCompat.getDrawable(this, R.drawable.bubble_background)?.mutate()
            background?.setTint(ContextCompat.getColor(this, colorRes))
            bubbleView.background = background

            if (compactBubbleEnabled) {
                bubbleDiscard.visibility = View.GONE
                bubbleLabel.visibility = View.GONE
                if (state == Pipeline.State.RECORDING) {
                    bubbleIcon.visibility = View.GONE
                    bubbleProgress.visibility = View.GONE
                    bubbleSpectrum.visibility = View.VISIBLE
                    bubbleSpectrum.startAnimating()
                } else if (state == Pipeline.State.PROCESSING) {
                    bubbleIcon.visibility = View.GONE
                    bubbleSpectrum.stopAnimating()
                    bubbleSpectrum.visibility = View.GONE
                    bubbleProgress.visibility = View.VISIBLE
                } else {
                    hideLiveDraft()
                    bubbleSpectrum.stopAnimating()
                    bubbleSpectrum.visibility = View.GONE
                    bubbleProgress.visibility = View.GONE
                    bubbleIcon.visibility = View.VISIBLE
                }
                val description = getString(compactDescription(state))
                bubbleView.contentDescription = description
                bubbleIcon.contentDescription = description
                bubbleProgress.contentDescription = description
                if (state != previousState) bubbleView.announceForAccessibility(description)
            } else if (state == Pipeline.State.RECORDING) {
                bubbleLabel.visibility = View.GONE
                bubbleSpectrum.visibility = View.VISIBLE
                bubbleDiscard.visibility = View.VISIBLE
                bubbleSpectrum.startAnimating()
            } else {
                hideLiveDraft()
                bubbleSpectrum.stopAnimating()
                bubbleSpectrum.visibility = View.GONE
                bubbleDiscard.visibility = View.GONE
                bubbleLabel.visibility = View.VISIBLE
                bubbleLabel.setText(
                    when (state) {
                        Pipeline.State.PROCESSING -> R.string.pill_processing
                        Pipeline.State.ERROR -> R.string.pill_error
                        else -> R.string.pill_tap_to_speak
                    }
                )
            }

            // A state change can flip visibility: e.g. the keyboard closed mid-recording
            // (pill stayed pinned) and the pipeline just returned to IDLE — hide now.
            applyBubbleVisibility()
        }
    }

    /** Forwards the mic level to the spectrum. Called on the recorder's capture thread. */
    private fun onAudioLevel(level: Float) {
        if (::bubbleSpectrum.isInitialized) bubbleSpectrum.setLevel(level)
    }

    /** Renders an in-memory preview only; it never interacts with the focused editor. */
    private fun renderLiveDraft(draft: Pipeline.LiveDraftUi) {
        if (compactBubbleEnabled) return
        mainHandler.post {
            if (!::liveDraftContent.isInitialized) return@post
            when (draft.mode) {
                Pipeline.LiveDraftUi.Mode.OFF -> hideLiveDraft()
                Pipeline.LiveDraftUi.Mode.LISTENING -> {
                    liveDraftStatus.setText(R.string.live_draft_listening)
                    liveDraftText.text = ""
                    showLiveDraft()
                }
                Pipeline.LiveDraftUi.Mode.DRAFT -> {
                    liveDraftStatus.setText(R.string.live_draft_may_change)
                    liveDraftText.text = buildDraftText(draft)
                    showLiveDraft()
                }
            }
        }
    }

    private fun showLiveDraft() {
        if (compactBubbleEnabled || currentState != Pipeline.State.RECORDING) return
        liveDraftContent.visibility = View.VISIBLE
        resizeBubble(expanded = true)
    }

    private fun buildDraftText(draft: Pipeline.LiveDraftUi): CharSequence {
        val text = SpannableStringBuilder()
        if (draft.stable.isNotBlank()) text.append(draft.stable)
        if (draft.tentative.isNotBlank()) {
            if (text.isNotEmpty()) text.append(' ')
            val tentativeStart = text.length
            text.append(draft.tentative)
            text.setSpan(
                ForegroundColorSpan(
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(this, R.color.bubble_icon),
                        TENTATIVE_TEXT_ALPHA,
                    )
                ),
                tentativeStart,
                text.length,
                0,
            )
        }
        return text
    }

    private fun hideLiveDraft() {
        if (!::liveDraftContent.isInitialized) return
        liveDraftContent.visibility = View.GONE
        liveDraftText.text = ""
        resizeBubble(expanded = false)
    }

    private fun resizeBubble(expanded: Boolean) {
        if (!::bubbleView.isInitialized || !bubbleView.isAttachedToWindow) return
        if (compactBubbleEnabled) return
        val width = if (expanded) draftWidthPx else pillWidthPx
        val height = if (expanded) draftHeightPx else pillHeightPx
        if (layoutParams.width == width && layoutParams.height == height) return
        val oldHeight = layoutParams.height
        layoutParams.width = width
        layoutParams.height = height
        layoutParams.x = (resources.displayMetrics.widthPixels - width) / 2
        // Preserve the bottom edge when the keyboard position is temporarily unknown.
        layoutParams.y = (layoutParams.y - (height - oldHeight)).coerceAtLeast(0)
        windowManager.updateViewLayout(bubbleView, layoutParams)
    }

    private fun showTransientMessage(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows an immediate, undoable receipt whenever the learner stores an edit. */
    private fun showLearningFeedback(corrections: List<Correction>) {
        if (corrections.isEmpty()) return
        mainHandler.post {
            removeLearningCard()
            val card = LayoutInflater.from(this).inflate(R.layout.learning_overlay, null)
            val message = card.findViewById<TextView>(R.id.learning_message)
            message.text = if (corrections.size == 1) {
                getString(
                    R.string.learned_correction_message,
                    corrections.single().wrong,
                    corrections.single().right,
                )
            } else {
                getString(R.string.learned_corrections_count, corrections.size)
            }
            card.findViewById<View>(R.id.btn_undo_learning).setOnClickListener {
                CorrectionLearner.forget(this, corrections)
                removeLearningCard()
            }

            val width = minOf(
                (LEARNING_CARD_WIDTH_DP * density).toInt(),
                resources.displayMetrics.widthPixels - (32 * density).toInt(),
            )
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (layoutParams.y - (LEARNING_CARD_OFFSET_DP * density).toInt())
                    .coerceAtLeast((24 * density).toInt())
            }
            try {
                windowManager.addView(card, params)
                learningCardView = card
                mainHandler.postDelayed(dismissLearningCardRunnable, LEARNING_CARD_DURATION_MS)
            } catch (_: RuntimeException) {
                // If Android revokes or temporarily rejects overlay access, learning has
                // already succeeded. Preserve feedback without crashing the bubble.
                Toast.makeText(this, message.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun removeLearningCard() {
        mainHandler.removeCallbacks(dismissLearningCardRunnable)
        val card = learningCardView ?: return
        learningCardView = null
        if (card.isAttachedToWindow) {
            try {
                windowManager.removeView(card)
            } catch (_: IllegalArgumentException) {
                // Window was already removed while the service was shutting down.
            }
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun configureCompactLayout() {
        bubbleView.findViewById<LinearLayout>(R.id.pill_content).setPadding(0, 0, 0, 0)
        bubbleLabel.visibility = View.GONE
        bubbleDiscard.visibility = View.GONE
        bubbleSpectrum.visibility = View.GONE
        bubbleIcon.visibility = View.VISIBLE
        bubbleProgress.visibility = View.GONE
        bubbleIcon.contentDescription = getString(R.string.compact_bubble_idle)
        bubbleIcon.layoutParams = bubbleIcon.layoutParams.apply {
            width = compactIconSizePx
            height = compactIconSizePx
        }
        bubbleSpectrum.layoutParams = LinearLayout.LayoutParams(
            compactSpectrumWidthPx,
            compactSpectrumHeightPx,
        )
        liveDraftContent.visibility = View.GONE
    }

    private fun compactDescription(state: Pipeline.State): Int = when (state) {
        Pipeline.State.IDLE -> R.string.compact_bubble_idle
        Pipeline.State.RECORDING -> R.string.compact_bubble_recording
        Pipeline.State.PROCESSING -> R.string.compact_bubble_processing
        Pipeline.State.ERROR -> R.string.compact_bubble_error
    }

    private fun compactMaxY(): Int =
        (resources.displayMetrics.heightPixels - compactSizePx).coerceAtLeast(0)

    private fun compactMinX(): Int =
        (COMPACT_EDGE_INSET_DP * density).toInt().coerceAtMost(compactMaxX())

    private fun compactMaxX(): Int =
        (resources.displayMetrics.widthPixels - compactSizePx -
            (COMPACT_EDGE_INSET_DP * density).toInt()).coerceAtLeast(0)

    private fun compactX(): Int =
        if (compactBubbleEdgeRight) {
            compactMaxX()
        } else compactMinX()

    private fun compactY(): Int =
        (compactMaxY() * compactBubbleVerticalFraction.coerceIn(0f, 1f)).toInt()

    private fun reapplyCompactPosition() {
        if (!::layoutParams.isInitialized || !bubbleView.isAttachedToWindow) return
        layoutParams.width = compactSizePx
        layoutParams.height = compactSizePx
        layoutParams.x = compactX()
        layoutParams.y = compactY()
        windowManager.updateViewLayout(bubbleView, layoutParams)
    }

    private fun snapCompactToEdge() {
        compactBubbleEdgeRight = layoutParams.x + compactSizePx / 2 >= resources.displayMetrics.widthPixels / 2
        layoutParams.x = compactX()
        layoutParams.y = layoutParams.y.coerceIn(0, compactMaxY())
        compactBubbleVerticalFraction = if (compactMaxY() == 0) {
            0f
        } else {
            layoutParams.y.toFloat() / compactMaxY()
        }
        windowManager.updateViewLayout(bubbleView, layoutParams)
        Settings.save(
            this,
            Settings.load(this).copy(
                compactBubbleEdgeRight = compactBubbleEdgeRight,
                compactBubbleVerticalFraction = compactBubbleVerticalFraction,
            ),
        )
    }

    companion object {
        private const val ACTION_STOP = "dev.prashikshit.voicey.STOP_BUBBLE"
        private const val RESTART_REQUEST_CODE = 200
        private const val RESTART_DELAY_MS = 1_000L
        private const val PILL_WIDTH_DP = 160
        private const val PILL_HEIGHT_DP = 48
        private const val COMPACT_SIZE_DP = 48
        private const val COMPACT_EDGE_INSET_DP = 8
        private const val COMPACT_ICON_SIZE_DP = 24
        private const val COMPACT_SPECTRUM_WIDTH_DP = 32
        private const val COMPACT_SPECTRUM_HEIGHT_DP = 40
        private const val DRAFT_WIDTH_DP = 320
        private const val DRAFT_HEIGHT_DP = 92
        private const val TENTATIVE_TEXT_ALPHA = 165
        private const val PILL_KEYBOARD_MARGIN_DP = 12
        private const val FALLBACK_BOTTOM_MARGIN_DP = 120
        private const val FADE_MS = 150L
        private const val LEARNING_CARD_WIDTH_DP = 320
        private const val LEARNING_CARD_OFFSET_DP = 84
        private const val LEARNING_CARD_DURATION_MS = 7_000L

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
