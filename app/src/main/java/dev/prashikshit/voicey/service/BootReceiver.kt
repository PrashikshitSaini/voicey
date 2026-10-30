package dev.prashikshit.voicey.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        AutoStart.restoreIfWanted(context)
    }

    private companion object {
        /** Some OEMs (HTC/Xiaomi) send this instead of or alongside BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
