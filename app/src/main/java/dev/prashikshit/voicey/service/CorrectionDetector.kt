package dev.prashikshit.voicey.service

import dev.prashikshit.voicey.data.Correction

/** Pure replacement detection, separated so GitHub Actions can regression-test it. */
internal object CorrectionDetector {

    fun detect(
        original: String,
        final: String,
        learnableTokens: Set<String>,
    ): List<Correction> {
        val oldTokens = tokenize(original).take(MAX_TOKENS)
        val newTokens = tokenize(final).take(MAX_TOKENS)
        if (oldTokens.isEmpty() || newTokens.isEmpty()) return emptyList()

        return CorrectionDiff.replacementHunks(oldTokens, newTokens)
            .asSequence()
            .mapNotNull { (oldHunk, newHunk) ->
                toCorrection(oldHunk, newHunk, learnableTokens)
            }
            .take(MAX_LEARNED_PER_EDIT)
            .toList()
    }

    fun tokenize(text: String): List<String> =
        text.split(WHITESPACE)
            .map { it.trim { ch -> !ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

    private fun toCorrection(
        oldHunk: List<String>,
        newHunk: List<String>,
        learnableTokens: Set<String>,
    ): Correction? {
        if (oldHunk.isEmpty() || oldHunk.size > MAX_SIDE_TOKENS) return null
        if (newHunk.isEmpty() || newHunk.size > MAX_SIDE_TOKENS) return null
        if (!oldHunk.all { it.lowercase() in learnableTokens }) return null

        val wrong = oldHunk.joinToString(" ")
        val right = newHunk.joinToString(" ")
        if (wrong.equals(right, ignoreCase = false)) return null
        if (wrong.none { it.isLetterOrDigit() } || right.none { it.isLetterOrDigit() }) return null

        if (!looksLikeMisrecognition(oldHunk, newHunk)) return null
        return Correction(wrong, right)
    }

    /**
     * A learned correction is replayed on every future dictation, so only edits that look
     * like a misheard or misspelled term qualify ("Cloud" → "Claude", "VHISPERFLOW" →
     * "Wispr Flow"). Rewording ("meeting" → "call") and swaps between everyday words
     * ("there" → "their") depend on the sentence and would corrupt later dictations.
     * Arbitrary mappings can still be added deliberately in the Dictionary screen.
     */
    internal fun looksLikeMisrecognition(oldHunk: List<String>, newHunk: List<String>): Boolean {
        val words = (oldHunk + newHunk).map { it.lowercase() }
        if (words.all { it in COMMON_WORDS }) return false
        val a = oldHunk.joinToString("").lowercase().filter(Char::isLetterOrDigit)
        val b = newHunk.joinToString("").lowercase().filter(Char::isLetterOrDigit)
        if (a == b) return true // capitalization or spacing fix, e.g. "iphone" → "iPhone"
        // A clipped name, e.g. "Prash" → "Prashikshit".
        if (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        val longer = maxOf(a.length, b.length)
        return editDistance(a, b) <= maxOf(2, longer / 2)
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    // ponytail: small English list; other languages rely on the similarity rule alone.
    private val COMMON_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "so", "to", "too", "two", "of", "in", "on",
        "at", "by", "for", "from", "with", "as", "is", "are", "was", "were", "be", "been", "am",
        "it", "its", "it's", "this", "that", "these", "those", "there", "their", "they're",
        "they", "them", "then", "than", "we", "us", "our", "you", "your", "you're", "he", "him",
        "his", "she", "her", "i", "me", "my", "mine", "do", "does", "did", "done", "have", "has",
        "had", "will", "would", "can", "could", "should", "shall", "may", "might", "must", "not",
        "no", "yes", "know", "now", "new", "knew", "here", "hear", "where", "wear",
        "what", "when", "who", "whose", "who's", "which", "why", "how", "all", "any", "some",
        "one", "won", "four", "fore", "right", "write", "rite", "see", "sea",
        "buy", "bye", "wood", "hour", "weather", "whether",
        "accept", "except", "affect", "effect", "lose", "loose", "off", "up",
        "out", "get", "got", "go", "going", "gone", "come", "came", "make", "made", "take",
        "took", "say", "said", "just", "very", "really", "also", "only", "more", "most", "less",
        "much", "many", "good", "great", "well", "ok", "okay", "like", "time", "day", "today",
        "tomorrow", "yesterday", "week", "year", "thing", "things", "way", "want", "need",
    )

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_TOKENS = 600
    private const val MAX_SIDE_TOKENS = 4
    private const val MAX_LEARNED_PER_EDIT = 4
}
