package dev.prashikshit.voicey.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import dev.prashikshit.voicey.R
import dev.prashikshit.voicey.data.Correction
import dev.prashikshit.voicey.data.LearnedCorrections

/**
 * Learns replacements the user makes shortly after Voicey inserts a dictation.
 *
 * Every quiet edit burst is compared with the previous field snapshot immediately;
 * qualifying replacements are persisted and surfaced through [setFeedbackListener].
 * This avoids the old, unreliable requirement to close the keyboard or wait 45 seconds.
 */
object CorrectionLearner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceLock = Any()
    private val reviewRunnable = Runnable { processPendingEdits() }
    private val deletionGraceRunnable = Runnable { settlePendingDeletion() }
    private val timeoutRunnable = Runnable { finalizeSession() }
    private var session: Session? = null

    @Volatile
    private var feedbackListener: ((List<Correction>) -> Unit)? = null

    private class Session(
        val appContext: Context,
        val packageName: String,
        val startedAtMs: Long,
        val learnableTokens: MutableSet<String>,
        var referenceText: String,
        var latestText: String? = null,
        var pendingDeletionText: String? = null,
        var insertionBaselineObserved: Boolean = false,
        val emittedWrong: MutableSet<String> = mutableSetOf(),
    )

    fun setFeedbackListener(listener: ((List<Correction>) -> Unit)?) {
        feedbackListener = listener
    }

    /** Opens a learning window. Any pending edits from the previous dictation settle first. */
    fun onTextInserted(
        context: Context,
        inserted: String,
        packageName: String,
        textBefore: String,
        textAfter: String,
    ) {
        finalizeSession()
        if (packageName.isBlank()) return
        val insertedTokens = CorrectionDetector.tokenize(inserted)
            .mapTo(mutableSetOf()) { it.lowercase() }
        if (insertedTokens.isEmpty()) return
        session = Session(
            appContext = context.applicationContext,
            packageName = packageName,
            startedAtMs = SystemClock.elapsedRealtime(),
            learnableTokens = insertedTokens,
            referenceText = "$textBefore$inserted$textAfter",
        )
        scheduleTimeout()
    }

    /** Lets the accessibility service avoid fetching event.source outside an active session. */
    fun wantsTextFrom(packageName: String?): Boolean =
        session?.packageName != null && session?.packageName == packageName

    /** Called for every observable text change in the field Voicey just wrote. */
    fun onTextChanged(packageName: String?, text: String) {
        val active = session ?: return
        if (packageName != active.packageName) return

        // Some keyboards report a replacement as two separate bursts: first delete the
        // old word, then insert the new one. Keep the pre-deletion snapshot alive when
        // the second half arrives instead of treating them as unrelated edits.
        mainHandler.removeCallbacks(deletionGraceRunnable)
        active.pendingDeletionText = null

        // The insertion itself often arrives asynchronously after onTextInserted(). Use
        // that early event as exact ground truth instead of mistaking reconstruction
        // differences for a correction. A very fast genuine replacement is still
        // reviewed, so correcting immediately after dictation cannot be swallowed.
        val elapsed = SystemClock.elapsedRealtime() - active.startedAtMs
        if (!active.insertionBaselineObserved && elapsed <= INSERTION_SETTLE_MS) {
            val referenceTokenCount = CorrectionDetector.tokenize(active.referenceText).size
            val observedTokenCount = CorrectionDetector.tokenize(text).size
            val containsReplacement = CorrectionDetector.detect(
                original = active.referenceText,
                final = text,
                learnableTokens = active.learnableTokens,
            ).isNotEmpty()
            val looksLikeUserDeletion = observedTokenCount < referenceTokenCount
            if (!containsReplacement && !looksLikeUserDeletion) {
                active.referenceText = text
                active.insertionBaselineObserved = true
                active.latestText = null
                scheduleTimeout()
                return
            }
        }
        active.insertionBaselineObserved = true
        active.latestText = text

        mainHandler.removeCallbacks(reviewRunnable)
        mainHandler.postDelayed(reviewRunnable, EDIT_DEBOUNCE_MS)
        scheduleTimeout()
    }

    /** Keyboard dismissal flushes the final edit immediately instead of losing it. */
    fun onKeyboardClosed() {
        finalizeSession()
    }

    /** Removes a just-learned batch after the user taps Undo on the feedback card. */
    fun forget(context: Context, corrections: List<Correction>) {
        if (corrections.isEmpty()) return
        Thread({
            synchronized(persistenceLock) {
                val store = LearnedCorrections(context.applicationContext)
                corrections.forEach { store.delete(it.wrong) }
            }
            mainHandler.post {
                Toast.makeText(
                    context.applicationContext,
                    R.string.correction_forgotten,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "voicey-learner-undo").start()
    }

    private fun scheduleTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, SESSION_TIMEOUT_MS)
    }

    private fun finalizeSession() {
        mainHandler.removeCallbacks(reviewRunnable)
        mainHandler.removeCallbacks(deletionGraceRunnable)
        mainHandler.removeCallbacks(timeoutRunnable)
        processPendingEdits()
        mainHandler.removeCallbacks(deletionGraceRunnable)
        session = null
    }

    private fun processPendingEdits() {
        val active = session ?: return
        val finalText = active.latestText ?: return
        active.latestText = null
        if (finalText == active.referenceText) return

        val allDetected = CorrectionDetector.detect(
            original = active.referenceText,
            final = finalText,
            learnableTokens = active.learnableTokens,
        )

        // Gboard, Samsung Keyboard, and others can pause between their delete and insert
        // events. A deletion-only snapshot is therefore held briefly. If typing follows,
        // the combined replacement is compared against the original text and learned.
        if (
            allDetected.isEmpty() &&
            CorrectionDetector.tokenize(finalText).size <
                CorrectionDetector.tokenize(active.referenceText).size
        ) {
            active.pendingDeletionText = finalText
            mainHandler.postDelayed(deletionGraceRunnable, DELETION_GRACE_MS)
            return
        }

        active.pendingDeletionText = null
        val detected = allDetected.filter { correction ->
            active.emittedWrong.add(correction.wrong.lowercase())
        }

        // Every quiet edit becomes the next baseline. This makes a later correction
        // independent of unrelated typing the user did earlier in the same session.
        active.referenceText = finalText
        if (detected.isEmpty()) return

        detected.forEach { correction ->
            CorrectionDetector.tokenize(correction.right)
                .mapTo(active.learnableTokens) { it.lowercase() }
        }

        Thread({
            synchronized(persistenceLock) {
                val store = LearnedCorrections(active.appContext)
                detected.forEach { store.learn(it.wrong, it.right) }
            }
            mainHandler.post {
                val listener = feedbackListener
                if (listener != null) {
                    listener(detected)
                } else {
                    Toast.makeText(
                        active.appContext,
                        "Learned: ${detected.joinToString { "${it.wrong} → ${it.right}" }}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, "voicey-learner-save").start()
    }

    private fun settlePendingDeletion() {
        val active = session ?: return
        val deletedText = active.pendingDeletionText ?: return
        active.pendingDeletionText = null
        active.referenceText = deletedText
    }

    private const val INSERTION_SETTLE_MS = 1_500L
    private const val EDIT_DEBOUNCE_MS = 1_200L
    private const val DELETION_GRACE_MS = 3_000L
    private const val SESSION_TIMEOUT_MS = 60_000L
}
