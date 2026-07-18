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
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // This event fires on every keystroke in every app. Gate on an active
                // learning session BEFORE touching event.source — fetching the node is
                // a cross-process call we must not pay system-wide for no reason.
                if (!CorrectionLearner.wantsTextFrom(pkg)) return
                // Never observe text the user types into password fields.
                val source = event.source ?: return
                val isPassword = source.isPassword
                val text = if (isPassword) null else source.text?.toString()
                @Suppress("DEPRECATION")
                source.recycle()
                if (text != null) {
                    CorrectionLearner.onTextChanged(pkg, text)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Deliberately NOT a learner session boundary: the keyboard and our
                // own pill fire window-state events from their packages mid-editing,
                // which killed sessions the moment the user started correcting.
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
            val wasVisible = keyboardVisible
            keyboardVisible = visible
            keyboardTop = top
            // Keyboard dismissal ends the user's editing pass — let the learner settle.
            if (wasVisible && !visible) CorrectionLearner.onKeyboardClosed()
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

            // Tier 2: a *focused* descendant that looks like a text input.
            findFocusedInputInWindows(svc)?.let { return it }

            // Tier 3: last resort for editors that never report input focus. Rich-text
            // surfaces (e.g. Samsung Notes) expose an editable node in the accessibility
            // tree but leave isFocused() false on it — focus sits on a container — so
            // tiers 1 and 2 miss it and dictation fails with "Tap a text field first".
            // This drops the isFocused() requirement and takes the first text input in
            // the active app window instead. Reached ONLY when the focus-based tiers found
            // nothing, so apps that already work return above and are unaffected by it.
            return findTextInputInApplicationWindows(svc)
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
            return hasTextMutationAction(node)
        }

        private fun hasTextMutationAction(node: AccessibilityNodeInfo): Boolean =
            node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
                    action.id == AccessibilityNodeInfo.ACTION_PASTE
            }

        /**
         * Walks application accessibility windows (active first) and returns the best node
         * that [looksLikeTextInput], WITHOUT requiring isFocused(). The IME and Voicey's
         * own overlay are skipped so we never target keyboard or pill views.
         *
         * Tier 3 fallback for [findFocusedEditable]: rich-text editors such as Samsung
         * Notes keep their editable node in the tree but never mark it input-focused, so
         * the focus-based tiers return null. Taking the first text input in the active
         * window recovers dictation for those apps. Only invoked after the focus tiers
         * fail, so it can't change behavior for apps that already resolve a focused node.
         */
        private fun findTextInputInApplicationWindows(svc: AccessibilityService): AccessibilityNodeInfo? {
            val windows = svc.windows
                ?.filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.sortedByDescending { it.isActive }
                ?: return null
            for (window in windows) {
                val root = window.root ?: continue
                // The pill is a non-focusable overlay, but some Samsung builds still
                // report its window ahead of the editor. Never select our own tree.
                if (root.packageName?.toString() == svc.packageName) {
                    root.recycle()
                    continue
                }

                // Prefer a node that explicitly advertises PASTE/SET_TEXT. Samsung
                // Notes sometimes marks a rich-text container editable before its
                // actionable composer child; returning the container makes insertion
                // fail even though a usable child exists deeper in the same tree.
                val match = findActionableTextInputDescendant(root)
                    ?: findTextInputDescendant(root)
                if (match != null) {
                    if (match !== root) root.recycle()
                    return match
                }
                root.recycle()
            }
            return null
        }

        private fun findActionableTextInputDescendant(
            node: AccessibilityNodeInfo,
        ): AccessibilityNodeInfo? {
            if (hasTextMutationAction(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val match = findActionableTextInputDescendant(child)
                if (match != null) {
                    if (match !== child) child.recycle()
                    return match
                }
                child.recycle()
            }
            return null
        }

        /** Like [findFocusedTextInputDescendant] but without the isFocused() gate. */
        private fun findTextInputDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (looksLikeTextInput(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val match = findTextInputDescendant(child)
                if (match != null) {
                    if (match !== child) child.recycle()
                    return match
                }
                child.recycle()
            }
            return null
        }
    }
}
