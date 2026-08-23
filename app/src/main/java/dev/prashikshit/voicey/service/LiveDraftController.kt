package dev.prashikshit.voicey.service

/**
 * Keeps the live-draft path deliberately ephemeral. PCM and draft words are retained
 * only for the current recording; callers send [PreviewRequest.audio] directly to the
 * configured transcription endpoint and must never write it to disk.
 */
class LiveDraftController(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val requestIntervalMs: Long = REQUEST_INTERVAL_MS,
    private val minAudioBytes: Int = MIN_AUDIO_BYTES,
    private val maxAudioBytes: Int = MAX_AUDIO_BYTES,
) {
    data class PreviewRequest(val audio: ByteArray)

    data class Draft(val stable: String, val tentative: String) {
        val isEmpty: Boolean get() = stable.isBlank() && tentative.isBlank()
    }

    private val lock = Any()
    private var active = false
    private var inFlight = false
    private var lastRequestAt = Long.MIN_VALUE
    private var rollingAudio = ByteArray(0)
    private var stableTokens = emptyList<String>()
    private var tentativeTokens = emptyList<String>()

    fun start() = synchronized(lock) {
        clearLocked()
        active = true
    }

    /** Accepts a copied PCM chunk and returns a request only when the throttle permits it. */
    fun offerPcm(chunk: ByteArray, length: Int = chunk.size): PreviewRequest? = synchronized(lock) {
        if (!active || length <= 0) return null
        appendAudioLocked(chunk, length.coerceAtMost(chunk.size))
        val now = nowMs()
        if (inFlight || rollingAudio.size < minAudioBytes ||
            (lastRequestAt != Long.MIN_VALUE && now - lastRequestAt < requestIntervalMs)
        ) {
            return null
        }
        inFlight = true
        lastRequestAt = now
        PreviewRequest(rollingAudio.copyOf())
    }

    /** Reconciles overlapping snapshot text without ever persisting it. */
    fun complete(transcript: String): Draft? = synchronized(lock) {
        if (!active) return null
        inFlight = false
        val next = tokenize(transcript)
        if (next.isEmpty()) return currentDraftLocked()

        val overlap = longestSuffixPrefixOverlap(tentativeTokens, next)
        if (overlap > 0) {
            stableTokens = stableTokens + tentativeTokens.dropLast(overlap)
        }
        tentativeTokens = next
        currentDraftLocked()
    }

    /** A preview failure is non-fatal: disable it for this recording and erase its data. */
    fun fail(): Boolean = synchronized(lock) {
        val wasActive = active
        clearLocked()
        wasActive
    }

    /** Erases the rolling audio and all draft text for stop, cancel, password, or shutdown. */
    fun stop() = synchronized(lock) { clearLocked() }

    fun isActive(): Boolean = synchronized(lock) { active }

    internal fun retainedAudioBytesForTest(): Int = synchronized(lock) { rollingAudio.size }
    internal fun retainedDraftForTest(): Draft = synchronized(lock) { currentDraftLocked() }

    private fun appendAudioLocked(chunk: ByteArray, length: Int) {
        val combined = ByteArray(rollingAudio.size + length)
        rollingAudio.copyInto(combined)
        chunk.copyInto(combined, destinationOffset = rollingAudio.size, endIndex = length)
        rollingAudio = if (combined.size <= maxAudioBytes) combined else combined.copyOfRange(
            combined.size - maxAudioBytes,
            combined.size,
        )
    }

    private fun clearLocked() {
        rollingAudio.fill(0)
        rollingAudio = ByteArray(0)
        stableTokens = emptyList()
        tentativeTokens = emptyList()
        active = false
        inFlight = false
        lastRequestAt = Long.MIN_VALUE
    }

    private fun currentDraftLocked() = Draft(
        stable = stableTokens.joinToString(" "),
        tentative = tentativeTokens.joinToString(" "),
    )

    private fun tokenize(text: String): List<String> = text.trim()
        .split(WHITESPACE)
        .filter(String::isNotBlank)

    private fun longestSuffixPrefixOverlap(previous: List<String>, next: List<String>): Int {
        for (size in minOf(previous.size, next.size) downTo 1) {
            if (previous.takeLast(size) == next.take(size)) return size
        }
        return 0
    }

    private companion object {
        const val REQUEST_INTERVAL_MS = 1_500L
        // One second of 16 kHz / mono / 16-bit PCM before asking for a first draft.
        const val MIN_AUDIO_BYTES = 32_000
        // Five seconds of the same format, kept only in RAM.
        const val MAX_AUDIO_BYTES = 160_000
        val WHITESPACE = Regex("\\s+")
    }
}
