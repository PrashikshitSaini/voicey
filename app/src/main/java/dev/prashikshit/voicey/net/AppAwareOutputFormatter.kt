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
        val category = AppFormattingGuide.categoryFor(context.app)
        if (category == AppFormattingGuide.Category.EMAIL &&
            !context.fieldKind.isNonBodyField() &&
            !isSingleLineEmailField(context.fieldHint)
        ) {
            result = applyEmailStructure(result)
        }
        // Body-shaped surfaces get one deterministic rescue when the model returns
        // a spoken enumeration as a single paragraph.
        if (category != AppFormattingGuide.Category.MESSAGING &&
            category != AppFormattingGuide.Category.SOCIAL &&
            (context.fieldKind == FieldKind.MULTI_LINE || context.fieldKind == FieldKind.UNKNOWN)
        ) {
            result = listifyCountedRun(result)
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

    /**
     * Turns a spoken enumeration ("First, X. Second, Y. Finally, Z.") into a real
     * bullet list. Only a lead ordinal plus at least one follow-up ordinal counts,
     * so ordinary prose that happens to start with "First" stays untouched.
     */
    private fun listifyCountedRun(text: String): String {
        if (HAS_LIST_LINE.containsMatchIn(text)) return text
        return text.split(PARAGRAPH_BREAK).joinToString("\n\n") { listifyParagraph(it) }
    }

    private fun listifyParagraph(paragraph: String): String {
        val sentences = SENTENCE_BREAK.split(paragraph)
        val start = sentences.indexOfFirst { FIRST_ORDINAL.containsMatchIn(it) }
        if (start < 0) return paragraph

        var end = start
        for (index in start + 1 until sentences.size) {
            val sentence = sentences[index]
            if (!CONTINUATION.containsMatchIn(sentence)) break
            end = index
            if (FINAL_ORDINAL.containsMatchIn(sentence)) break
        }
        if (end == start) return paragraph

        // A two-sentence run is usually narrative prose ("First we tried it. Then it worked.").
        val run = sentences.subList(start, end + 1)
        if (run.size < 3 && run.none { STRONG_CONTINUATION.containsMatchIn(it) }) return paragraph

        // The sentence introducing the run carries the list's colon.
        val lead = sentences.take(start).joinToString(" ")
            .let { if (it.endsWith(".")) it.dropLast(1) + ":" else it }
        val items = run.map { item(it) }
        val rest = sentences.drop(end + 1).joinToString(" ")

        return buildString {
            if (lead.isNotEmpty()) append(lead).append("\n\n")
            items.forEachIndexed { index, line ->
                if (index > 0) append("\n")
                append("- ").append(line)
            }
            if (rest.isNotEmpty()) append("\n\n").append(rest)
        }
    }

    private fun item(sentence: String): String {
        val stripped = sentence.replaceFirst(ORDINAL_PREFIX, "").trim()
        val capitalised = stripped.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        return if (capitalised.endsWith(".")) capitalised.dropLast(1) else capitalised
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
    private val HAS_LIST_LINE = Regex("""(?m)^[ \t]*(?:[-•*]|\d+\.)\s""")
    private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
    private val SENTENCE_BREAK = Regex("""(?<=[.!?])\s+""")
    private val FIRST_ORDINAL = Regex("""(?i)^(?:firstly\b|first(?: of all)?[, ]|number one\b|one,)""")
    private val CONTINUATION = Regex(
        """(?i)^(?:(?:second|third|fourth|fifth)(?:ly)?[, ]|next[, ]|then[, ]|also[, ]|after that\b|finally\b|lastly\b|number (?:two|three|four|five)\b)"""
    )
    private val FINAL_ORDINAL = Regex("""(?i)^(?:finally|lastly)\b""")
    private val STRONG_CONTINUATION = Regex("""(?i)^(?:second(?:ly)?\b|finally\b|lastly\b|number two\b)""")
    private val ORDINAL_PREFIX = Regex(
        """(?i)^(?:(?:first|second|third|fourth|fifth)(?:ly)?|number (?:one|two|three|four|five)|one|next|then|also|after that|finally|lastly)(?: of all)?\s*,?\s*"""
    )
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
