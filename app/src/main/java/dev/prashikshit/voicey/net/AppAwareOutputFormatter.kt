package dev.prashikshit.voicey.net

/** Small deterministic guarantees layered after the model's app-aware formatting. */
internal object AppAwareOutputFormatter {

    fun format(
        text: String,
        context: CleanupContext,
        enabled: Boolean,
    ): String {
        if (!enabled) return text

        var result = text
        if (AppFormattingGuide.categoryFor(context.app) == AppFormattingGuide.Category.EMAIL &&
            !context.fieldKind.isNonBodyField() &&
            !isSingleLineEmailField(context.fieldHint)
        ) {
            result = applyEmailStructure(result)
        }
        if (!context.fieldKind.isSingleLineField()) return result

        result = flatten(result)
        if (context.fieldKind == FieldKind.SEARCH || context.fieldKind == FieldKind.URL) {
            result = result.removeSuffix(".")
        }
        if (context.fieldKind == FieldKind.EMAIL_ADDRESS) {
            val compact = result.replace(" ", "")
            if (EMAIL_ADDRESS.matches(compact)) result = compact
        }
        return result
    }

    private fun applyEmailStructure(text: String): String {
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
            val punctuation = match.groupValues[1]
            val closing = match.groupValues[2].trim()
            val name = match.groupValues[3].trim()
            result = result.replaceRange(
                match.range,
                if (name.isEmpty()) {
                    punctuation + "\n\n" + closing
                } else {
                    punctuation + "\n\n" + closing.trimEnd('.', '!', ',', '?') + ",\n" + name
                },
            )
        }

        return result
    }

    /** Fields that are definitely never an email body, even inside an email app. */
    private fun FieldKind.isNonBodyField(): Boolean = when (this) {
        FieldKind.SEARCH,
        FieldKind.URL,
        FieldKind.EMAIL_ADDRESS,
        FieldKind.SUBJECT,
        FieldKind.SHORT -> true
        FieldKind.SINGLE_LINE,
        FieldKind.MULTI_LINE,
        FieldKind.UNKNOWN -> false
    }

    /** One-line field shapes: line breaks and list markers would corrupt them. */
    private fun flatten(text: String): String = text.trim()
        .lines()
        .joinToString(" ") { it.trim().replaceFirst(LIST_MARKER, "") }
        .replace(WHITESPACE, " ")
        .trim()

    private fun FieldKind.isSingleLineField(): Boolean = when (this) {
        FieldKind.SEARCH,
        FieldKind.URL,
        FieldKind.EMAIL_ADDRESS,
        FieldKind.SUBJECT,
        FieldKind.SHORT -> true
        FieldKind.SINGLE_LINE,
        FieldKind.MULTI_LINE,
        FieldKind.UNKNOWN -> false
    }

    private fun isSingleLineEmailField(fieldHint: String): Boolean {
        val normalized = fieldHint.trim().lowercase()
        return SINGLE_LINE_HINTS.any { normalized.contains(it) }
    }

    private val GREETING = Regex(
        """(?i)^((?:hey|hi|hello|dear|good morning|good afternoon|good evening|greetings)\b[^,\n]{0,60},)[ \t]+"""
    )

    private val CLOSING = Regex(
        """([.!?][\"']?)[ \t]+((?:[Aa]lright,[ \t]*)?(?:(?i:thanks so much|thank you|many thanks|thanks|cheers|best regards|kind regards|warm regards|regards|best|sincerely|talk soon|see you soon|see you|see ya))[.!?,]?)(?:(?:[ \t]+([A-Z][a-z]{0,20}(?:[ \t]+[A-Z][a-z]{0,20}){0,2}))[.!]?)?$"""
    )

    private val LIST_MARKER = Regex("""^(?:[-•*]|\d+[.)])\s+""")
    private val WHITESPACE = Regex("""\s+""")
    private val EMAIL_ADDRESS = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    private val SINGLE_LINE_HINTS = listOf(
        "subject",
        "recipient",
        "email address",
        "search",
        "to address",
    )
}
