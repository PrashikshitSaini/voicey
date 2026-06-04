package dev.prashikshit.voicey.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes the transcribed text into the currently focused field.
 *
 * Three-tier strategy:
 *
 *   0. **Direct ACTION_SET_TEXT for confidently empty native fields** — no clipboard
 *      transit at all. Covers the most common dictation case; see [isConfidentlyEmpty]
 *      for why this can never destroy content or resurrect the placeholder bug.
 *
 *   1. **Clipboard + ACTION_PASTE** — the primary path. ACTION_PASTE is the platform's
 *      native "insert at cursor" action: it preserves existing typed content, naturally
 *      appends dictation, and bypasses the placeholder-as-`node.text` bug that splice-
 *      based SET_TEXT injection kept hitting on Compose-based inputs (WhatsApp, search
 *      bars, etc.). It also works on WebView-hosted HTML form fields, which is the only
 *      reliable Android-level way to inject text into them. The user's previous
 *      clipboard contents are saved before our paste and restored ~500 ms later;
 *      when there was no previous clip, the clipboard is cleared instead so the
 *      dictation never lingers there.
 *
 *   2. **ACTION_SET_TEXT** — fallback only. Used when ACTION_PASTE is unsupported on
 *      the focused node (rare; usually custom views that expose SET_TEXT but not PASTE).
 *      Replaces the field's content because there's no safe way to splice without
 *      re-introducing the placeholder bug.
 *
 * Trade-off vs. v0.1.4/v0.1.5: the clipboard is touched on every dictation now, not
 * only on WebView fallbacks. Clipboard-history tools (Gboard, etc.) will briefly see
 * dictated text. The win: append behavior works everywhere, and the placeholder-prepend
 * bug class is structurally eliminated rather than papered over with heuristics.
 */
class TextInjector(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insert(node: AccessibilityNodeInfo?, text: String): InsertionResult {
        if (text.isEmpty()) return InsertionResult.SKIPPED_EMPTY
        if (node == null) return InsertionResult.NO_FOCUSED_NODE

        // Tier 0: confidently empty native field → direct SET_TEXT, NO clipboard.
        // This is the common dictation case (empty reply box) and keeps the text out
        // of Samsung/Gboard clipboard panels entirely, which snapshot every clip
        // change regardless of the sensitive flag. Paste's append semantics are only
        // needed when there is existing content to preserve — an empty field has none.
        if (isConfidentlyEmpty(node) && setTextDirectly(node, text)) {
            return InsertionResult.WROTE
        }

        // Tier 1: clipboard + paste. Appends at cursor, preserves existing text.
        if (pasteViaClipboard(node, text)) return InsertionResult.WROTE

        // Tier 2: SET_TEXT fallback. Replaces content. Rare path — only triggered
        // when the focused node has no working paste handler.
        if (setTextDirectly(node, text)) return InsertionResult.WROTE

        return InsertionResult.FAILED
    }

    /**
     * True only when we can TRUST that the field is empty. Gated on [AccessibilityNodeInfo.isEditable]
     * because WebView virtual nodes typically leave it false AND often refuse to expose
     * their text — an opaque web field that *looks* empty may hold real content that a
     * SET_TEXT would destroy, so those always take the append-safe paste path. For
     * native fields, empty/hint-showing text genuinely means empty (mirrors
     * ContextReader.userEnteredText). Compose placeholders that masquerade as content
     * fail these checks and fall through to paste — the placeholder-prepend bug class
     * stays structurally impossible because no tier ever concatenates node.text.
     */
    private fun isConfidentlyEmpty(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) return false
        val text = node.text?.toString().orEmpty()
        if (text.isEmpty()) return true
        if (node.isShowingHintText) return true
        val hint = node.hintText?.toString().orEmpty()
        return hint.isNotEmpty() && hint == text
    }

    private fun setTextDirectly(node: AccessibilityNodeInfo, text: String): Boolean {
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        moveCaretToEnd(node, text.length)
        return true
    }

    /** Returns true if the paste succeeded. Always schedules a clipboard restore. */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val previousClip: ClipData? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.primaryClip
        } else {
            // Pre-P, reading the primary clip from a background context surfaces an
            // OS-level "App pasted from your clipboard" toast that we can't suppress.
            // Skip the read on these older devices; the cost is a non-restored clip.
            null
        }

        clipboard.setPrimaryClip(buildDictationClip(text))

        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        restoreClipboardLater(previousClip)
        return pasted
    }

    /**
     * Builds the [ClipData] we write before pasting. On Android 13+ we mark the clip
     * as sensitive via [ClipDescription.EXTRA_IS_SENSITIVE], which tells the system:
     *
     *  - Don't show the "App pasted from your clipboard" preview popup.
     *  - Don't save this entry to clipboard history. Gboard's clipboard panel and
     *    other clipboard managers respect this flag.
     *
     * This is the same affordance password managers and OTP-autofill apps use so their
     * transient writes don't pollute the user's clipboard history. On Android 12 and
     * below the flag is silently ignored — the clipboard still shows the dictation,
     * which is unavoidable until those devices get an API equivalent.
     */
    private fun buildDictationClip(text: String): ClipData {
        val clip = ClipData.newPlainText("voicey", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return clip
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

    /**
     * Leaves the clipboard exactly as we found it: the previous clip is restored, and
     * when there was none (or we couldn't read it, pre-P) the dictation clip is cleared
     * rather than left behind. Consequence: the last dictation can't be manually
     * re-pasted from the clipboard — by design, dictations leave no clipboard residue.
     */
    private fun restoreClipboardLater(previous: ClipData?) {
        mainHandler.postDelayed({
            try {
                when {
                    previous != null -> clipboard.setPrimaryClip(previous)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> clipboard.clearPrimaryClip()
                    // Pre-P there is no clearPrimaryClip(); overwriting with an empty
                    // clip would still leave an entry, so the dictation stays — same
                    // trade-off as the unreadable-previous-clip path on those devices.
                    else -> Unit
                }
            } catch (_: SecurityException) {
                // Some OEMs throw if the foreground app changes during the delay window
                // (Android 10+ restricts clipboard writes from background contexts).
                // Best-effort; failure means either the previous clip isn't restored or
                // the dictation lingers on the clipboard — the documented trade-off.
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
