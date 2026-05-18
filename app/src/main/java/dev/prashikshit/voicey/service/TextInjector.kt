package dev.prashikshit.voicey.service

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts text into the currently focused field via ACTION_SET_TEXT only — no clipboard.
 *
 * Android does not expose an "insert text at cursor" accessibility action, so we read the
 * field's current contents, splice the new text in at the cursor position, write the full
 * spliced string back via ACTION_SET_TEXT, and then move the cursor to the end of the
 * inserted text so subsequent typing continues naturally.
 *
 * This path does not touch the system clipboard. The trade-off is that fields which don't
 * expose their text to accessibility services (some WebView-based editors, Google Docs'
 * canvas-rendered editor, certain hardened banking inputs) will reject the write — those
 * cases surface as [InsertionResult.FAILED] and are reported to the user.
 */
class TextInjector {

    fun insert(node: AccessibilityNodeInfo?, text: String): InsertionResult {
        if (text.isEmpty()) return InsertionResult.SKIPPED_EMPTY
        if (node == null) return InsertionResult.NO_FOCUSED_NODE

        val current = (node.text ?: "").toString()
        val rawCursor = node.textSelectionStart
        val cursor = if (rawCursor in 0..current.length) rawCursor else current.length
        val spliced = current.substring(0, cursor) + text + current.substring(cursor)

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, spliced)
        }
        val wrote = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!wrote) return InsertionResult.FAILED

        // Move the caret to the end of the inserted text. Best-effort: some fields ignore
        // ACTION_SET_SELECTION after a SET_TEXT, leaving the cursor at the start. That's a
        // cosmetic issue, not a data-loss issue, so we don't treat its failure as fatal.
        val newCursor = cursor + text.length
        val cursorArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)

        return InsertionResult.WROTE
    }

    enum class InsertionResult {
        WROTE,
        NO_FOCUSED_NODE,
        SKIPPED_EMPTY,
        FAILED,
    }
}
