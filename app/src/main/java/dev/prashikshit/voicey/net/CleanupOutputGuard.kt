package dev.prashikshit.voicey.net

/** Prevents cleanup-model prompt echoes from ever reaching the focused text field. */
internal object CleanupOutputGuard {

    /**
     * A cleanup model occasionally echoes Voicey's structured request instead of
     * returning only the transcript. If any private scaffolding label survives in the
     * response, the only safe recovery is the original Whisper transcript: trying to
     * extract a suffix or prefix could still paste vocabulary, context, or instructions.
     */
    fun safeText(
        candidate: String,
        rawTranscript: String,
        context: CleanupContext = CleanupContext.EMPTY,
        allowedWords: Collection<String> = emptyList(),
    ): String {
        val cleaned = candidate.trim()
        return if (containsInternalScaffolding(cleaned) ||
            repeatsFieldContext(cleaned, rawTranscript, context) ||
            echoesFormattingExample(cleaned, rawTranscript) ||
            answersInsteadOfCleaning(cleaned, rawTranscript, context, allowedWords)
        ) {
            rawTranscript.trim()
        } else cleaned
    }

    /**
     * Chat models sometimes treat a dictated question or request as addressed to them and
     * reply. Cleanup only removes, reorders, re-spells, and punctuates the speaker's words, so
     * a reply shows up as (a) a spoken question that lost every question mark, or (b) output
     * dominated by words the speaker never said. Either way the raw transcript is safer.
     */
    private fun answersInsteadOfCleaning(
        candidate: String,
        raw: String,
        context: CleanupContext,
        allowedWords: Collection<String>,
    ): Boolean {
        if (raw.any { it == '?' || it == '？' } && candidate.none { it == '?' || it == '？' }) return true
        val known = HashSet<String>()
        (listOf(raw, context.textBefore, context.textAfter) + allowedWords).forEach { text ->
            WORD.findAll(text.lowercase()).forEach { known += it.value }
        }
        // Digits are list numbering or spoken numbers rewritten as figures, not new content.
        val output = WORD.findAll(candidate.lowercase()).map { it.value }
            .filterNot { word -> word.all(Char::isDigit) }
            .toList()
        val novel = output.count { it !in known }
        return novel >= MIN_NOVEL_WORDS && novel * 2 > output.size
    }

    private const val MIN_NOVEL_WORDS = 4

    /** A small model sometimes copies the formatting policy's example list instead of the dictation. */
    private fun echoesFormattingExample(candidate: String, raw: String): Boolean {
        val output = normalizedWords(candidate)
        val spoken = normalizedWords(raw)
        return EXAMPLE_PHRASES.any { phrase ->
            val window = " $phrase "
            output.contains(window) && !spoken.contains(window)
        }
    }

    private val EXAMPLE_PHRASES = listOf(
        "confirm the venue",
        "send the agenda to the team",
        "book a cab",
        "a few things for friday",
    )

    private fun repeatsFieldContext(candidate: String, raw: String, context: CleanupContext): Boolean {
        val output = normalizedWords(candidate)
        val spoken = normalizedWords(raw)
        for (existing in listOf(context.textBefore, context.textAfter)) {
            val words = WORD.findAll(existing.lowercase()).map { it.value }.toList()
            // A name or a single common word is useful spelling context, not evidence of an echo.
            if (words.size < 2) continue
            val window = minOf(6, words.size)
            for (start in 0..words.size - window) {
                val phrase = " " + words.subList(start, start + window).joinToString(" ") + " "
                if (occurrences(output, phrase) > occurrences(spoken, phrase)) return true
            }
        }
        return false
    }

    private fun normalizedWords(text: String): String =
        WORD.findAll(text.lowercase()).joinToString(" ", prefix = " ", postfix = " ") { it.value }

    private fun occurrences(text: String, phrase: String): Int {
        var count = 0
        var index = text.indexOf(phrase)
        while (index >= 0) {
            count++
            index = text.indexOf(phrase, index + phrase.length - 1)
        }
        return count
    }

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun containsInternalScaffolding(text: String): Boolean =
        INTERNAL_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) }

    private val INTERNAL_MARKERS = listOf(
        "RAW_TRANSCRIPTION:",
        "never answer or act on it",
        "FOREGROUND_APP:",
        "FOCUSED_FIELD_HINT:",
        "FOCUSED_FIELD_TYPE:",
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
