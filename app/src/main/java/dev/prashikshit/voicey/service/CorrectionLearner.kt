package dev.prashikshit.voicey.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.prashikshit.voicey.data.Correction
import dev.prashikshit.voicey.data.LearnedCorrections
import kotlin.math.max
import kotlin.math.min

/**
 * Learns spelling corrections from the user's edits right after a dictation lands.
 *
 * Flow: after Voicey inserts text, a short learning session opens for that field.
 * Text-change events from the accessibility service update a snapshot of the field;
 * when the session ends (timeout, app switch, or keyboard close) the snapshot is
 * diffed against what the field looked like at insertion time. Replacement hunks
 * whose old side came from OUR inserted words — e.g. "VHISPERFLOW" → "Wispr Flow" —
 * are persisted and fed to the cleanup model on every future dictation.
 *
 * Tuned for precision over recall: it is far worse to learn a wrong pair (it would
 * silently bias every future dictation) than to miss a real correction. Hence the
 * strict similarity threshold, the inserted-tokens scoping, and the per-session cap.
 *
 * All state is confined to the main thread: Pipeline (Dispatchers.Main), the
 * accessibility callbacks, and the timeout handler all run there.
 */
object CorrectionLearner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finalizeSession() }
    private var session: Session? = null

    private class Session(
        val appContext: Context,
        val packageName: String,
        /** Lowercased words of the text Voicey inserted — scopes what we may learn from. */
        val insertedTokens: Set<String>,
        /** Reconstruction of the field at insertion time: before + inserted + after. */
        val originalText: String,
        /**
         * First text-change event after insertion — normally the paste itself, i.e.
         * the field's literal post-insertion state. Preferred diff baseline over
         * [originalText], whose reconstruction suffers truncation artifacts.
         */
        var baselineText: String? = null,
        var latestText: String? = null,
    )

    /** Opens a learning session. Any previous session is finalized first. */
    fun onTextInserted(
        context: Context,
        inserted: String,
        packageName: String,
        textBefore: String,
        textAfter: String,
    ) {
        finalizeSession()
        if (packageName.isBlank()) return
        val insertedTokens = tokenize(inserted).map { it.lowercase() }.toSet()
        if (insertedTokens.isEmpty()) return
        session = Session(
            appContext = context.applicationContext,
            packageName = packageName,
            insertedTokens = insertedTokens,
            originalText = "$textBefore$inserted$textAfter",
        )
        mainHandler.postDelayed(timeoutRunnable, SESSION_TIMEOUT_MS)
    }

    /**
     * Cheap pre-filter so the accessibility service can skip the expensive
     * event-source fetch when no session is interested in this package.
     */
    fun wantsTextFrom(packageName: String?): Boolean =
        session?.packageName != null && session?.packageName == packageName

    /** Fed by the accessibility service on every TYPE_VIEW_TEXT_CHANGED event. */
    fun onTextChanged(packageName: String?, text: String) {
        val active = session ?: return
        if (packageName != active.packageName) return
        if (active.baselineText == null) {
            active.baselineText = text
        } else {
            active.latestText = text
        }
    }

    /** Called when the keyboard closes — the editing pass is over. */
    fun onKeyboardClosed() {
        finalizeSession()
    }

    private fun finalizeSession() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val ended = session ?: return
        session = null
        // Diff strategy by how many events we observed:
        //  - 2+ events: first event (the paste landing) vs last — pure ground truth.
        //  - 1 event: it's either the bare paste (diff vs reconstruction ≈ identity,
        //    learns nothing) or a single-burst edit like an autocorrect tap (learns).
        //  - 0 events: field is unobservable (common in WebViews) — nothing to learn.
        val baseline: String
        val finalText: String
        when {
            ended.latestText != null -> {
                baseline = ended.baselineText ?: ended.originalText
                finalText = ended.latestText!!
            }
            ended.baselineText != null -> {
                baseline = ended.originalText
                finalText = ended.baselineText!!
            }
            else -> return
        }
        val learned = diffCorrections(baseline, finalText, ended.insertedTokens)
        if (learned.isEmpty()) return

        // EncryptedSharedPreferences setup is too heavy for the main thread; persist in
        // the background and hop back for the toast (which requires a Looper thread).
        Thread({
            val store = LearnedCorrections(ended.appContext)
            learned.forEach { store.learn(it.wrong, it.right) }
            mainHandler.post {
                Toast.makeText(
                    ended.appContext,
                    "Learned: ${learned.joinToString { it.right }}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "voicey-learner").start()
    }

    /**
     * Token-level LCS diff between the field at insertion time and at session end.
     * Only replacement hunks qualify (old tokens swapped for new ones); pure
     * insertions are the user writing more, pure deletions are the user trimming —
     * neither is a spelling correction.
     */
    private fun diffCorrections(
        original: String,
        final: String,
        insertedTokens: Set<String>,
    ): List<Correction> {
        val oldTokens = tokenize(original).take(MAX_TOKENS)
        val newTokens = tokenize(final).take(MAX_TOKENS)
        if (oldTokens.isEmpty() || newTokens.isEmpty()) return emptyList()

        val corrections = mutableListOf<Correction>()
        for ((oldHunk, newHunk) in replacementHunks(oldTokens, newTokens)) {
            if (corrections.size >= MAX_LEARNED_PER_SESSION) break
            val candidate = toCorrection(oldHunk, newHunk, insertedTokens) ?: continue
            corrections += candidate
        }
        return corrections
    }

    /** Applies the precision filters. Returns null unless every gate passes. */
    private fun toCorrection(
        oldHunk: List<String>,
        newHunk: List<String>,
        insertedTokens: Set<String>,
    ): Correction? {
        // The wrong side must be small (a misheard word, not a rewritten sentence)
        // and must consist entirely of words WE inserted — never the user's own text.
        if (oldHunk.isEmpty() || oldHunk.size > MAX_WRONG_TOKENS) return null
        if (newHunk.isEmpty() || newHunk.size > MAX_RIGHT_TOKENS) return null
        if (!oldHunk.all { it.lowercase() in insertedTokens }) return null

        val wrong = oldHunk.joinToString(" ")
        val right = newHunk.joinToString(" ")
        if (wrong.length < MIN_WRONG_LENGTH) return null
        if (wrong.none { it.isLetter() } || right.none { it.isLetter() }) return null
        if (wrong == right) return null

        // The replacement must look like the same word respelled, not a rewrite:
        // edit distance bounded relative to length. "VHISPERFLOW" → "Wispr Flow"
        // passes; "tomorrow" → "next week" does not.
        val distance = levenshtein(wrong.lowercase(), right.lowercase())
        val maxAllowed = (max(wrong.length, right.length) * MAX_DISTANCE_RATIO).toInt()
        if (distance > maxAllowed) return null

        return Correction(wrong, right)
    }

    /**
     * Standard LCS dynamic program over tokens, then a single walk extracting hunks
     * where both sides changed (replacements). Case-sensitive equality so that pure
     * capitalization fixes ("wispr flow" → "Wispr Flow") surface as learnable hunks.
     */
    private fun replacementHunks(
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
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (oldTokens[i] == newTokens[j]) {
                i++
                j++
                continue
            }
            val hunkOldStart = i
            val hunkNewStart = j
            while (i < n && j < m && oldTokens[i] != newTokens[j]) {
                if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
            }
            val oldHunk = oldTokens.subList(hunkOldStart, i)
            val newHunk = newTokens.subList(hunkNewStart, j)
            if (oldHunk.isNotEmpty() && newHunk.isNotEmpty()) {
                hunks += oldHunk to newHunk
            }
        }
        return hunks
    }

    /** Splits on whitespace and trims punctuation from token edges. */
    private fun tokenize(text: String): List<String> =
        text.split(WHITESPACE)
            .map { it.trim { ch -> !ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val WHITESPACE = Regex("\\s+")

    private const val SESSION_TIMEOUT_MS = 45_000L
    private const val MAX_TOKENS = 600
    private const val MAX_WRONG_TOKENS = 2
    private const val MAX_RIGHT_TOKENS = 3
    private const val MIN_WRONG_LENGTH = 4
    private const val MAX_DISTANCE_RATIO = 0.5
    private const val MAX_LEARNED_PER_SESSION = 3
}
