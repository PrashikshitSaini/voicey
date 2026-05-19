package dev.prashikshit.voicey.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import dev.prashikshit.voicey.R
import dev.prashikshit.voicey.SettingsActivity
import dev.prashikshit.voicey.VoiceyApp
import dev.prashikshit.voicey.data.Settings
import kotlin.math.abs

/**
 * Foreground service that draws a draggable mic bubble on top of every app. Tap to
 * toggle recording; long-press (when "hold to talk" is on) to record while held.
 *
 * Runs as a foreground service with a persistent low-importance notification because
 * SYSTEM_ALERT_WINDOW overlays drawn by background services get torn down on modern
 * Android. The notification is the price of admission.
 */
class FloatingBubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var injector: TextInjector
    private lateinit var pipeline: Pipeline
    private val mainHandler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isLongPressing = false
    private val touchSlopPx by lazy { (resources.displayMetrics.density * 8).toInt() }
    private val longPressMs = 350L
    private val longPressRunnable = Runnable { startHoldToTalk() }

    // Cached on service start so we don't decrypt EncryptedSharedPreferences on every
    // ACTION_DOWN event. Setting changes apply on bubble restart.
    @Volatile
    private var holdToTalkEnabled: Boolean = true

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
        )
        holdToTalkEnabled = Settings.load(this).holdToTalk
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
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

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = resources.displayMetrics.heightPixels / 3
        }

        bubbleView.setOnTouchListener(::handleTouch)
        windowManager.addView(bubbleView, layoutParams)
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
                if (!isDragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                    isDragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    layoutParams.x = (initialX + dx).toInt()
                    layoutParams.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(bubbleView, layoutParams)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                when {
                    isLongPressing -> {
                        // Hold-to-talk: release stops recording.
                        pipeline.stopAndProcess()
                        isLongPressing = false
                    }
                    isDragging -> dockToNearestEdge()
                    else -> toggleRecording()
                }
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

    private fun startHoldToTalk() {
        isLongPressing = true
        pipeline.startRecording()
    }

    private fun pipeline_isRecording(): Boolean = currentState == Pipeline.State.RECORDING

    @Volatile
    private var currentState: Pipeline.State = Pipeline.State.IDLE

    private fun renderState(state: Pipeline.State) {
        currentState = state

        // Only claim the microphone foreground-service type while we're actually
        // capturing audio. PROCESSING is HTTP-only and IDLE/ERROR don't touch the mic,
        // so dropping the claim immediately releases the OS mic-in-use indicator and
        // lets the platform put the mic radio back to sleep.
        startInForeground(includeMicrophone = state == Pipeline.State.RECORDING)

        mainHandler.post {
            val colorRes = when (state) {
                Pipeline.State.IDLE -> R.color.bubble_idle
                Pipeline.State.RECORDING -> R.color.bubble_recording
                Pipeline.State.PROCESSING -> R.color.bubble_processing
                Pipeline.State.ERROR -> R.color.bubble_recording
            }
            val background = ContextCompat.getDrawable(this, R.drawable.bubble_background)?.mutate()
            background?.setTint(ContextCompat.getColor(this, colorRes))
            bubbleView.background = background
        }
    }

    private fun showTransientMessage(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dockToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = bubbleView.width.takeIf { it > 0 } ?: (resources.displayMetrics.density * 56).toInt()
        layoutParams.x = if (layoutParams.x + bubbleWidth / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - bubbleWidth
        }
        windowManager.updateViewLayout(bubbleView, layoutParams)
    }

    companion object {
        private const val ACTION_STOP = "dev.prashikshit.voicey.STOP_BUBBLE"
        private const val RESTART_REQUEST_CODE = 200
        private const val RESTART_DELAY_MS = 1_000L

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
