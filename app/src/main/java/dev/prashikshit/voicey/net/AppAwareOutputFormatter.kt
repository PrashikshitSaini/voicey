package dev.prashikshit.voicey.net

/** Small deterministic guarantees layered after the model's app-aware formatting. */
internal object AppAwareOutputFormatter {

    fun format(
        text: String,
        packageName: String,
        fieldHint: String,
        enabled: Boolean,
    ): String {
        if (!enabled || AppFormattingGuide.categoryFor(packageName) != AppFormattingGuide.Category.EMAIL) {
            return text
        }
        if (isSingleLineEmailField(fieldHint)) return text

        var result = text.trim()

        // Greeting spoken as part of the first sentence → conventional email opening.
        GREETING.find(result)?.let { match ->
            result = result.replaceRange(
                match.range,
                match.groupValues[1] + "\n\n",
            )
        }

        // A natural spoken closing at the end should not remain glued to the body.
        CLOSING.find(result)?.let { match ->
            result = result.replaceRange(
                match.range,
                match.groupValues[1] + "\n\n" + match.groupValues[2],
            )
        }

        return result
    }

    private fun isSingleLineEmailField(fieldHint: String): Boolean {
        val normalized = fieldHint.trim().lowercase()
        return SINGLE_LINE_HINTS.any { normalized.contains(it) }
    }

    private val GREETING = Regex(
        """(?i)^((?:hey|hi|hello|dear)\b[^,\n]{0,60},)[ \t]+"""
    )

    private val CLOSING = Regex(
        """(?i)([.!?][\"']?)[ \t]+((?:alright,[ \t]*)?(?:see ya|see you(?: soon)?|thanks|thank you|best|regards)[.!?]?)$"""
    )

    private val SINGLE_LINE_HINTS = listOf(
        "subject",
        "recipient",
        "email address",
        "search",
        "to address",
    )
}
