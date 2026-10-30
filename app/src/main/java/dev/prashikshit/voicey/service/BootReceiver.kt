package dev.prashikshit.voicey.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import dev.prashikshit.voicey.data.Settings

/**
 * Restores the bubble after a reboot or an app update, but only when the user left it
 * running and every prerequisite is still in place. Never throws: a receiver crash would
 * take down the app on boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON -> Unit
            else -> return
        }
        try {
            if (!Settings.load(context).startAutomatically) return
            if (!Settings.isBubbleWanted(context)) return
            if (!AndroidSettings.canDrawOverlays(context)) return
            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!micGranted) return
            // BOOT_COMPLETED and MY_PACKAGE_REPLACED are exempt from the Android 12+
            // background foreground-service start restrictions.
            FloatingBubbleService.start(context)
        } catch (_: Exception) {
            // A receiver must never crash the app.
        }
    }

    private companion object {
        /** Some OEMs (HTC/Xiaomi) send this instead of or alongside BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
