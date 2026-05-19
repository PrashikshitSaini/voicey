package dev.prashikshit.voicey.service

import android.view.accessibility.AccessibilityNodeInfo
import dev.prashikshit.voicey.net.CleanupContext

/**
 * Reads contextual hints from the currently focused accessibility node so the cleanup
 * model can correctly spell names and avoid duplicating text near the cursor.
 *
 * Best-effort by design. If a node refuses to expose its text (most password fields,
 * most WebViews), this falls back to empty context.
 */
object ContextReader {

    private const val MAX_CONTEXT_CHARS = 1000

    fun read(node: AccessibilityNodeInfo?, packageName: String?): CleanupContext {
        if (node == null) return CleanupContext.EMPTY
        // Reject hint/placeholder text — sending it to the cleanup LLM as "field context"
        // both wastes tokens and risks the model treating the hint as user-typed content
        // it should preserve. Same heuristic as TextInjector.effectiveCurrentText().
        val text = userEnteredText(node)
        if (text.isEmpty()) {
            return CleanupContext(
                app = packageName.orEmpty(),
                textBefore = "",
                textAfter = "",
            )
        }
        val selectionStart = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        val safeStart = selectionStart.coerceIn(0, text.length)
        val before = text.substring(0, safeStart).takeLast(MAX_CONTEXT_CHARS)
        val after = text.substring(safeStart).take(MAX_CONTEXT_CHARS)
        return CleanupContext(
            app = packageName.orEmpty(),
            textBefore = before,
            textAfter = after,
        )
    }

    private fun userEnteredText(node: AccessibilityNodeInfo): String {
        val text = (node.text ?: "").toString()
        if (text.isEmpty()) return ""
        if (node.isShowingHintText) return ""
        val hint = (node.hintText ?: "").toString()
        if (hint.isNotEmpty() && hint == text) return ""
        return text
    }
}
