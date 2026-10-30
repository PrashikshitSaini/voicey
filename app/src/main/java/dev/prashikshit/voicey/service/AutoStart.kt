package dev.prashikshit.voicey.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import dev.prashikshit.voicey.data.Settings

/**
 * Restores the bubble if the user left it on and every prerequisite still holds.
 *
 * Shared by [BootReceiver] and [FocusAccessibilityService]: some OEMs (Samsung/One UI)
 * skip MY_PACKAGE_REPLACED when the process is first started to bind the accessibility
 * service, but the accessibility service is always reconnected after a reboot or update.
 *
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are exempt from the Android 12+ background
 * foreground-service start restrictions, and an accessibility service bound by the
 * system is exempt as well.
 */
internal object AutoStart {

    /** Restores the bubble if the user left it on and every prerequisite still holds. Never throws. */
    fun restoreIfWanted(context: Context) {
        try {
            val app = context.applicationContext
            if (!Settings.load(app).startAutomatically) return
            if (!Settings.isBubbleWanted(app)) return
            if (!AndroidSettings.canDrawOverlays(app)) return
            val micGranted = ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!micGranted) return
            FloatingBubbleService.start(app)
        } catch (_: Exception) {
            // A receiver or accessibility callback must never crash the app.
        }
    }
}
