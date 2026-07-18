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
 * Clipboard-free mode is explicit rather than pretending every Android editor exposes
 * an insert-at-cursor API. Empty native fields always use direct ACTION_SET_TEXT. When
 * strict mode is enabled, non-empty accessible fields are reconstructed around their
 * reported selection and written directly; opaque/custom editors fail without touching
 * the clipboard. With strict mode disabled, ACTION_PASTE remains the compatibility path.
 *
 *   0. **Direct ACTION_SET_TEXT for confidently empty native fields** — no clipboard
 *      transit at all. Covers the most common dictation case; see [isConfidentlyEmpty]
 *      for why this can never destroy content or resurrect the placeholder bug.
 *
 *   1. **Cursor-aware ACTION_SET_TEXT in strict mode** — reconstructs accessible text
 *      around the selection and writes it back without clipboard transit.
 *
 *   2. **Clipboard + ACTION_PASTE** — the compatibility path. ACTION_PASTE is the platform's
 *      native "insert at cursor" action: it preserves existing typed content, naturally
 *      appends dictation, and bypasses the placeholder-as-`node.text` bug that splice-
 *      based SET_TEXT injection kept hitting on Compose-based inputs (WhatsApp, search
 *      bars, etc.). It also works on WebView-hosted HTML form fields, which is the only
 *      reliable Android-level way to inject text into them. The user's previous
 *      clipboard contents are saved before our paste and restored ~500 ms later;
 *      when there was no previous clip, the clipboard is cleared instead so the
 *      dictation never lingers there.
 *
 *   3. **ACTION_SET_TEXT** — fallback only. Used when ACTION_PASTE is unsupported on
 *      the focused node (rare; usually custom views that expose SET_TEXT but not PASTE).
 *      Replaces the field's content because there's no safe way to splice without
 *      re-introducing the placeholder bug.
 *
 * Compatibility mode may therefore touch the clipboard for non-empty or opaque fields.
 * Strict mode never does; its explicit tradeoff is that some editors cannot be written.
 */
class TextInjector(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insert(
        node: AccessibilityNodeInfo?,
        text: String,
        neverUseClipboard: Boolean = false,
    ): InsertionResult {
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

        // Strict mode guarantees that this method never reads or writes the clipboard.
        // If an editor hides its value/selection or rejects SET_TEXT, report that fact
        // instead of weakening the user's privacy choice behind their back.
        if (neverUseClipboard) {
            return if (insertAtSelectionDirectly(node, text)) {
                InsertionResult.WROTE
            } else {
                InsertionResult.CLIPBOARD_REQUIRED
            }
        }

        // Compatibility mode: clipboard + paste appends at cursor and preserves text.
        if (pasteViaClipboard(node, text)) return InsertionResult.WROTE

        // Tier 3: SET_TEXT fallback. Replaces content. Rare path — only triggered
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
     * fail these checks and fall through to paste in compatibility mode. Strict mode's
     * cursor-aware tier separately rejects the common zero-cursor placeholder shape.
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

    /**
     * Reconstructs a native editor's full value around its reported selection. This is
     * the only clipboard-free approximation of an IME-style insert-at-cursor operation
     * exposed to an accessibility service.
     *
     * A collapsed selection at position zero in an allegedly non-empty field is
     * deliberately rejected. Several Compose/custom inputs expose placeholder text as
     * node.text with a zero cursor and no hint metadata; accepting it recreates the old
     * "Type a messageHello" corruption bug. A real field with its cursor manually moved
     * to the beginning therefore reports unsupported in strict mode—a safe tradeoff.
     */
    private fun insertAtSelectionDirectly(
        node: AccessibilityNodeInfo,
        insertion: String,
    ): Boolean {
        if (!node.isEditable || node.isShowingHintText) return false

        val current = node.text?.toString() ?: return false
        val hint = node.hintText?.toString().orEmpty()
        if (hint.isNotEmpty() && hint == current) return false

        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        if (rawStart !in 0..current.length || rawEnd !in 0..current.length) return false
        if (current.isNotEmpty() && rawStart == 0 && rawEnd == 0) return false

        val splice = TextSplice.atSelection(current, rawStart, rawEnd, insertion)
            ?: return false

        val setArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                splice.text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        moveCaretToEnd(node, splice.caret)
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
        CLIPBOARD_REQUIRED,
        FAILED,
    }

    private companion object {
        const val CLIPBOARD_RESTORE_DELAY_MS = 500L
    }
}
