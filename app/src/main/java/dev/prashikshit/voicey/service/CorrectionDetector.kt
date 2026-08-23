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

        // The edit itself is explicit user intent, so do not second-guess it with the
        // old Levenshtein similarity gate. The visible Undo card is the safety valve.
        return Correction(wrong, right)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_TOKENS = 600
    private const val MAX_SIDE_TOKENS = 4
    private const val MAX_LEARNED_PER_EDIT = 4
}
