package dev.prashikshit.voicey.net

/** Prevents cleanup-model prompt echoes from ever reaching the focused text field. */
internal object CleanupOutputGuard {

    /**
     * A cleanup model occasionally echoes Voicey's structured request instead of
     * returning only the transcript. If any private scaffolding label survives in the
     * response, the only safe recovery is the original Whisper transcript: trying to
     * extract a suffix or prefix could still paste vocabulary, context, or instructions.
     */
    fun safeText(candidate: String, rawTranscript: String): String {
        val cleaned = candidate.trim()
        return if (containsInternalScaffolding(cleaned)) rawTranscript.trim() else cleaned
    }

    fun containsInternalScaffolding(text: String): Boolean =
        INTERNAL_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) }

    private val INTERNAL_MARKERS = listOf(
        "RAW_TRANSCRIPTION:",
        "FOREGROUND_APP:",
        "FOCUSED_FIELD_HINT:",
        "APP_CATEGORY:",
        "FIELD_CONTEXT_BEFORE_CURSOR:",
        "FIELD_CONTEXT_AFTER_CURSOR:",
        "CUSTOM_VOCABULARY",
        "KNOWN_CORRECTIONS",
        "spellings to preserve if mentioned",
        "the user previously fixed these transcription mistakes",
        "VOICEY_APP_FORMATTING_POLICY",
        "Detected surface:",
        "This is an email surface.",
        "This is a messaging surface.",
        "This is a notes surface.",
        "This is a document editor.",
    )
}
