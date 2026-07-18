package dev.prashikshit.voicey.service

import kotlin.math.max

/** Pure token-diff logic, split from CorrectionLearner so CI can test it without Android. */
internal object CorrectionDiff {

    /**
     * Standard LCS dynamic program over tokens, then a walk extracting hunks where both
     * sides changed. Equality is case-sensitive so capitalization fixes are visible.
     */
    fun replacementHunks(
        oldTokens: List<String>,
        newTokens: List<String>,
    ): List<Pair<List<String>, List<String>>> {
        val n = oldTokens.size
        val m = newTokens.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (oldTokens[i] == newTokens[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    max(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val hunks = mutableListOf<Pair<List<String>, List<String>>>()
        val oldHunk = mutableListOf<String>()
        val newHunk = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && oldTokens[i] == newTokens[j]) {
                addReplacement(hunks, oldHunk, newHunk)
                oldHunk.clear()
                newHunk.clear()
                i++
                j++
            } else if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) {
                oldHunk += oldTokens[i++]
            } else {
                newHunk += newTokens[j++]
            }
        }
        addReplacement(hunks, oldHunk, newHunk)
        return hunks
    }

    private fun addReplacement(
        hunks: MutableList<Pair<List<String>, List<String>>>,
        oldHunk: List<String>,
        newHunk: List<String>,
    ) {
        // Copy because the walk reuses and clears its mutable accumulators.
        if (oldHunk.isNotEmpty() && newHunk.isNotEmpty()) {
            hunks += oldHunk.toList() to newHunk.toList()
        }
    }
}
