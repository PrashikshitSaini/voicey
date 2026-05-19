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
 * Writes the transcribed text into the currently focused field.
 *
 * Two-tier strategy:
 *
 *   1. **ACTION_SET_TEXT** — the clean path. Replaces the field's content directly,
 *      never touches the clipboard. Works on every native Android text field. Replaces
 *      (rather than splices) for the reasons documented in the v0.1.3 commit: many
 *      Android apps return placeholder/hint as `node.text` without setting
 *      `isShowingHintText`, and splicing into that hint produced "Type a message Hello"
 *      output. Always-replace removed that whole class of bug.
 *
 *   2. **Clipboard + ACTION_PASTE** — the fallback. HTML `<input>` / `<textarea>`
 *      fields rendered inside Chrome (and other WebView-based apps) reject
 *      ACTION_SET_TEXT because the text is owned by the JS/DOM model, not Android's
 *      view tree. ACTION_PASTE goes through Chrome's internal paste handler, which
 *      bridges to the JS input event. The previous clipboard contents are saved
 *      before our write and restored ~500ms later. The clipboard is only touched
 *      when ACTION_SET_TEXT fails, so native-app dictations stay clipboard-free.
 */
class TextInjector(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insert(node: AccessibilityNodeInfo?, text: String): InsertionResult {
        if (text.isEmpty()) return InsertionResult.SKIPPED_EMPTY
        if (node == null) return InsertionResult.NO_FOCUSED_NODE

        // Tier 1: direct write. The fast, clipboard-free path.
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            moveCaretToEnd(node, text.length)
            return InsertionResult.WROTE
        }

        // Tier 2: clipboard + paste. Required for HTML form fields inside Chrome and
        // other WebView-hosted inputs. Save and restore the user's clipboard around
        // the paste so we don't permanently clobber what they had copied.
        return pasteViaClipboard(node, text)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): InsertionResult {
        val previousClip: ClipData? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.primaryClip
        } else {
            // Pre-P, reading the primary clip from a background context surfaces an
            // OS-level "App pasted from your clipboard" toast that we can't suppress.
            // Skip the read on these older devices; the cost is a non-restored clip.
            null
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("voicey", text))

        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        restoreClipboardLater(previousClip)
        return if (pasted) InsertionResult.WROTE else InsertionResult.FAILED
    }

    private fun moveCaretToEnd(node: AccessibilityNodeInfo, position: Int) {
        // Best-effort: some fields ignore ACTION_SET_SELECTION after a SET_TEXT and
        // leave the cursor at the start. Cosmetic only — text is already inserted.
        val cursorArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
    }

    private fun restoreClipboardLater(previous: ClipData?) {
        if (previous == null) return
        mainHandler.postDelayed({
            try {
                clipboard.setPrimaryClip(previous)
            } catch (_: SecurityException) {
                // Some OEMs throw if the foreground app changes during the delay window
                // (Android 10+ restricts clipboard writes from background contexts).
                // Best-effort restore; failure here means the user's previous clipboard
                // content is lost, which is the documented trade-off.
            }
        }, CLIPBOARD_RESTORE_DELAY_MS)
    }

    enum class InsertionResult {
        WROTE,
        NO_FOCUSED_NODE,
        SKIPPED_EMPTY,
        FAILED,
    }

    private companion object {
        const val CLIPBOARD_RESTORE_DELAY_MS = 500L
    }
}
