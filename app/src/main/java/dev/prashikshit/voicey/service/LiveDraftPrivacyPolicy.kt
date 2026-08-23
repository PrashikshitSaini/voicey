package dev.prashikshit.voicey.service

/** Small, testable privacy boundary for the overlay-only draft channel. */
object LiveDraftPrivacyPolicy {
    fun mustClearPreview(isPreviewActive: Boolean, isPasswordFieldFocused: Boolean): Boolean =
        isPreviewActive && isPasswordFieldFocused
}
