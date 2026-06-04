package dev.prashikshit.voicey.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Tracks the currently focused input node and the package name of the foreground app
 * so the bubble service knows where dictated text will land and what context the
 * cleanup model should see.
 *
 * Also watches the window list for the IME (keyboard) window so the bubble can appear
 * only while the user is actually typing, positioned just above the keyboard — and
 * watches focus events for password fields so the bubble never shows over one.
 *
 * Exposes static accessors because the FloatingBubbleService needs synchronous access
 * at the moment the user releases the mic button — and an AccessibilityService instance
 * is a singleton anyway.
 */
class FocusAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshKeyboardState()
    }

    override fun onDestroy() {
        instance = null
        // The detection signal is gone; report "no keyboard" so a keyboard-aware bubble
        // doesn't stay stranded on screen (its service falls back to always-visible
        // mode on its own when it sees isEnabled() == false).
        updateKeyboardState(visible = false, top = NO_KEYBOARD_TOP)
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Required override; no-op — we don't queue feedback.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty()) lastPackageName = pkg

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshKeyboardState()
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source ?: return
                passwordFieldFocused = source.isPassword
                @Suppress("DEPRECATION")
                source.recycle()
                notifyListener()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // App/window switch — any remembered password-field focus is stale.
                if (passwordFieldFocused) {
                    passwordFieldFocused = false
                    notifyListener()
                }
            }
        }
    }

    /**
     * Scans the interactive window list for the IME window. A zero-height window is
     * treated as "no keyboard" — some IMEs briefly report an empty frame mid-animation.
     */
    private fun refreshKeyboardState() {
        val bounds = Rect()
        var visible = false
        var top = NO_KEYBOARD_TOP
        for (window in windows ?: emptyList()) {
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            window.getBoundsInScreen(bounds)
            if (bounds.height() > 0) {
                visible = true
                top = bounds.top
            }
            break
        }
        updateKeyboardState(visible, top)
    }

    companion object {
        @Volatile
        private var instance: FocusAccessibilityService? = null

        @Volatile
        private var lastPackageName: String = ""

        /** Screen-Y of the keyboard's top edge, or [NO_KEYBOARD_TOP] when closed/unknown. */
        const val NO_KEYBOARD_TOP = -1

        @Volatile
        private var keyboardVisible: Boolean = false

        @Volatile
        private var keyboardTop: Int = NO_KEYBOARD_TOP

        @Volatile
        private var passwordFieldFocused: Boolean = false

        /**
         * Notified with (shouldShowBubble, keyboardTop) whenever keyboard visibility,
         * keyboard bounds, or password-field focus changes. shouldShowBubble is false
         * over password fields even while the keyboard is open. Invoked on the main
         * thread (accessibility callbacks and the bubble service share it).
         */
        @Volatile
        private var keyboardListener: ((Boolean, Int) -> Unit)? = null

        private var lastNotifiedShow: Boolean? = null
        private var lastNotifiedTop: Int = NO_KEYBOARD_TOP

        /** Registers [listener] and immediately delivers the current state to it. */
        fun setKeyboardListener(listener: ((Boolean, Int) -> Unit)?) {
            keyboardListener = listener
            lastNotifiedShow = null
            lastNotifiedTop = NO_KEYBOARD_TOP
            if (listener != null) {
                val show = shouldShowBubble()
                val top = keyboardTop
                lastNotifiedShow = show
                lastNotifiedTop = top
                listener(show, top)
            }
        }

        private fun shouldShowBubble(): Boolean = keyboardVisible && !passwordFieldFocused

        private fun updateKeyboardState(visible: Boolean, top: Int) {
            keyboardVisible = visible
            keyboardTop = top
            notifyListener()
        }

        private fun notifyListener() {
            val listener = keyboardListener ?: return
            val show = shouldShowBubble()
            val top = keyboardTop
            if (show == lastNotifiedShow && top == lastNotifiedTop) return
            lastNotifiedShow = show
            lastNotifiedTop = top
            listener(show, top)
        }

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
