package dev.prashikshit.voicey.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-dictation stage timings, so slowness can be traced to the mic, the network, transcription,
 * or cleanup from normal use. Records durations and sizes only — never audio or text.
 * Kept in app-private storage (last [MAX_LINES] dictations) and mirrored to logcat.
 */
internal class DictationTimings(private val context: Context, stoppedAtMs: Long) {
    private val fields = linkedMapOf<String, Any>()
    private val startMs = if (stoppedAtMs > 0) stoppedAtMs else SystemClock.elapsedRealtime()
    private var mark = startMs

    /** Milliseconds since the previous mark (or since the user pressed stop, for the first). */
    fun stage(name: String) {
        val now = SystemClock.elapsedRealtime()
        fields["${name}_ms"] = now - mark
        mark = now
    }

    fun note(name: String, value: Any) {
        fields[name] = value
    }

    fun finish(outcome: String) {
        fields["outcome"] = outcome
        fields["total_ms"] = SystemClock.elapsedRealtime() - startMs
        fields["net"] = networkType()
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " +
            fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i(TAG, line)
        try {
            synchronized(LOCK) {
                val file = File(context.filesDir, FILE_NAME)
                val lines = (if (file.exists()) file.readLines() else emptyList()) + line
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
            // Diagnostics must never affect dictation.
        }
    }

    private fun networkType(): String = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (_: Exception) {
        "unknown"
    }

    private companion object {
        const val TAG = "VoiceyTiming"
        const val FILE_NAME = "dictation_timings.log"
        const val MAX_LINES = 200
        val LOCK = Any()
    }
}
