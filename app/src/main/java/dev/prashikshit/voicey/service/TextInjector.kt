package dev.prashikshit.voicey.service

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes the transcribed text into the currently focused field via ACTION_SET_TEXT.
 *
 * **Replaces the field's existing content.** This is deliberate. The previous splice-
 * at-cursor approach was unreliable because many Android apps (Compose-based inputs,
 * custom EditText subclasses, several builds of WhatsApp / search bars) return their
 * placeholder/hint string as `node.text` even when the field is "empty," and don't
 * set `isShowingHintText()` correctly. Splicing into that produced output like
 * `"Type a message" + your speech`, which is the bug users actually hit.
 *
 * The trade-off: dictating into a field that already has user-typed content overwrites
 * the existing text. We accept this because:
 *   - the much more common case (dictate into an empty field) now works reliably across
 *     all apps, including those that mis-report hint state;
 *   - voice-driven mid-sentence editing is a rare workflow on a touch device;
 *   - the user can still type with the keyboard for hybrid use.
 */
class TextInjector {

    fun insert(node: AccessibilityNodeInfo?, text: String): InsertionResult {
        if (text.isEmpty()) return InsertionResult.SKIPPED_EMPTY
        if (node == null) return InsertionResult.NO_FOCUSED_NODE

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val wrote = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!wrote) return InsertionResult.FAILED

        // Move the caret to the end of the inserted text so subsequent typing continues
        // naturally. Best-effort — some fields ignore ACTION_SET_SELECTION after a
        // SET_TEXT and leave the cursor at the start. Cosmetic only, not fatal.
        val cursorArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
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
