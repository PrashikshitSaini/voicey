package dev.prashikshit.voicey.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tracks the currently focused input node and the package name of the foreground app
 * so the bubble service knows where dictated text will land and what context the
 * cleanup model should see.
 *
 * Exposes static accessors because the FloatingBubbleService needs synchronous access
 * at the moment the user releases the mic button — and an AccessibilityService instance
 * is a singleton anyway.
 */
class FocusAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Required override; no-op — we don't queue feedback.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty()) lastPackageName = pkg
    }

    companion object {
        @Volatile
        private var instance: FocusAccessibilityService? = null

        @Volatile
        private var lastPackageName: String = ""

        fun isEnabled(): Boolean = instance != null

        /**
         * Returns the currently focused text-input node, or null. Caller must recycle.
         *
         * Resolution order:
         *
         *   1. `findFocus(FOCUS_INPUT)` — the accessibility framework's own notion of
         *      input focus. Reliable for native Android EditText fields.
         *
         *   2. Walk the active window's accessibility tree and pick the first focused
         *      descendant that looks like a text input. This is the WebView/Chrome
         *      fallback: HTML `<input>` and `<textarea>` virtual nodes don't always
         *      propagate FOCUS_INPUT to the system, and they typically don't set
         *      `isEditable`. We accept any focused node that exposes a text-related
         *      action (SET_TEXT or PASTE) or has a textfield-shaped class name.
         *
         * The `isEditable` flag is *not* used as a filter at any tier — Chrome's web
         * content virtual nodes don't fill it in, which was the v0.1.4 reason
         * dictation silently failed on every web form field.
         */
        fun findFocusedEditable(): AccessibilityNodeInfo? {
            val svc = instance ?: return null

            svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                if (looksLikeTextInput(node)) return node
                node.recycle()
            }

            return findFocusedInputInWindows(svc)
        }

        fun currentPackageName(): String = lastPackageName

        private fun findFocusedInputInWindows(svc: AccessibilityService): AccessibilityNodeInfo? {
            val windows = svc.windows ?: return null
            for (window in windows) {
                if (!window.isActive) continue
                val root = window.root ?: continue
                val match = findFocusedTextInputDescendant(root)
                if (match != null) {
                    if (match !== root) root.recycle()
                    return match
                }
                root.recycle()
            }
            return null
        }

        private fun findFocusedTextInputDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isFocused && looksLikeTextInput(node)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val match = findFocusedTextInputDescendant(child)
                if (match != null) {
                    if (match !== child) child.recycle()
                    return match
                }
                child.recycle()
            }
            return null
        }

        private fun looksLikeTextInput(node: AccessibilityNodeInfo): Boolean {
            // Cheapest checks first.
            if (node.isEditable) return true

            val className = node.className?.toString().orEmpty()
            if (className.endsWith("EditText") ||
                className.contains("TextField") ||
                className.contains("TextInput")
            ) {
                return true
            }

            // Final and most reliable signal for WebView-hosted form fields: the node
            // actually advertises a text-mutation action. Chrome's virtual nodes for
            // <input> / <textarea> consistently expose ACTION_SET_TEXT or ACTION_PASTE
            // (or both) even when neither isEditable nor a recognizable className is set.
            return node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                    action.id == AccessibilityNodeInfo.ACTION_PASTE
            }
        }
    }
}
