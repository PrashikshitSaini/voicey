package dev.prashikshit.voicey.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts text into the currently focused field via the most reliable path Android offers
 * to a non-IME service: clipboard + ACTION_PASTE on the focused accessibility node, with
 * a fallback to ACTION_SET_TEXT on the existing field contents.
 *
 * The previous clipboard contents are saved and restored after a short delay so the user
 * doesn't lose what they had copied. This is the same approach Wispr Flow uses.
 */
class TextInjector(context: Context) {

    private val clipboard = context.applicationContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insert(node: AccessibilityNodeInfo?, text: String): InsertionResult {
        if (text.isEmpty()) return InsertionResult.SKIPPED_EMPTY
        if (node == null) return InsertionResult.NO_FOCUSED_NODE

        val previousClip: ClipData? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.primaryClip
        } else {
            // Older devices: don't risk reading the clip because doing so triggers user-visible
            // "App pasted from your clipboard" toasts. Skip restore on these.
            null
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("voicey", text))

        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            restoreClipboard(previousClip)
            return InsertionResult.PASTED
        }

        // Fallback: splice the new text at the cursor position. Appending blindly to
        // the field end (the naive approach) breaks mid-sentence dictation.
        val current = (node.text ?: "").toString()
        val rawCursor = node.textSelectionStart
        val cursor = if (rawCursor in 0..current.length) rawCursor else current.length
        val spliced = current.substring(0, cursor) + text + current.substring(cursor)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, spliced)
        }
        val setText = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setText) {
            // Move the cursor to the end of the inserted text so subsequent typing
            // continues naturally instead of jumping back to the original position.
            val newCursor = cursor + text.length
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        }
        restoreClipboard(previousClip)
        return if (setText) InsertionResult.SET_TEXT else InsertionResult.FAILED
    }

    private fun restoreClipboard(previous: ClipData?) {
        if (previous == null) return
        mainHandler.postDelayed({
            try {
                clipboard.setPrimaryClip(previous)
            } catch (_: SecurityException) {
                // Some OEMs throw if the foreground app changes during the delay; ignore.
            }
        }, CLIPBOARD_RESTORE_DELAY_MS)
    }

    enum class InsertionResult {
        PASTED,
        SET_TEXT,
        NO_FOCUSED_NODE,
        SKIPPED_EMPTY,
        FAILED,
    }

    private companion object {
        const val CLIPBOARD_RESTORE_DELAY_MS = 500L
    }
}
