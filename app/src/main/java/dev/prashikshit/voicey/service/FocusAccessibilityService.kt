package dev.prashikshit.voicey.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tracks the currently focused editable text node and the package name of the foreground
 * app so the bubble service knows where dictated text will land and what context the
 * cleanup model should see.
 *
 * Exposes static accessors because the FloatingBubbleService needs synchronous access at
 * the moment the user releases the mic button — and an AccessibilityService instance is a
 * singleton anyway. This is a common Android pattern; not the prettiest, but correct.
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

        // Cache the focused-editable node when focus changes. We intentionally don't
        // recycle it here — we re-fetch via findFocus() at injection time to ensure freshness.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source ?: return
            if (source.isEditable) {
                // No-op; we look up findFocus() on demand. This branch exists for
                // future expansion (e.g., showing/hiding the bubble on focus changes).
            }
            source.recycle()
        }
    }

    companion object {
        @Volatile
        private var instance: FocusAccessibilityService? = null

        @Volatile
        private var lastPackageName: String = ""

        fun isEnabled(): Boolean = instance != null

        /** Returns the currently focused editable node, or null. Caller must recycle. */
        fun findFocusedEditable(): AccessibilityNodeInfo? {
            val svc = instance ?: return null
            val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return if (focused.isEditable) focused else {
                focused.recycle()
                null
            }
        }

        fun currentPackageName(): String = lastPackageName
    }
}
