package dev.prashikshit.voicey.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Converts raw microphone RMS values into a smoothed 0..1 level suitable for driving
 * a waveform animation. Port of FreeFlow's LiveAudioLevelNormalizer (MIT).
 *
 * The interesting part: instead of a fixed dB range, it continuously adapts a noise
 * floor and a peak ceiling to the current environment, so the bars sit at zero in a
 * quiet room, don't peg at max in a loud one, and respond with fast-attack /
 * slow-release smoothing that reads as natural speech motion.
 *
 * Not thread-safe — call from a single thread (the recorder's capture thread).
 */
class AudioLevelNormalizer {

    private var noiseFloorDb = INITIAL_NOISE_FLOOR_DB
    private var peakCeilingDb = INITIAL_PEAK_CEILING_DB
    private var displayLevel = 0f

    fun reset() {
        noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        peakCeilingDb = INITIAL_PEAK_CEILING_DB
        displayLevel = 0f
    }

    /** @param rms root-mean-square of a PCM chunk, normalized to 0..1 (sample / 32768). */
    fun normalizedLevel(rms: Float): Float {
        val levelDb = 20f * log10(max(rms, MINIMUM_RMS))

        updateNoiseFloor(levelDb)
        updatePeakCeiling(levelDb)

        val displayCeilingDb = peakCeilingDb + PEAK_HEADROOM_DB
        val dynamicSpan = max(displayCeilingDb - noiseFloorDb, MIN_SPAN_DB + PEAK_HEADROOM_DB)
        var normalized = ((levelDb - noiseFloorDb) / dynamicSpan).coerceIn(0f, 1f)
        val isActiveSpeech = levelDb >= noiseFloorDb + SPEECH_GATE_MARGIN_DB

        if (normalized < NOISE_GATE_NORMALIZED_THRESHOLD &&
            levelDb <= noiseFloorDb + SPEECH_GATE_MARGIN_DB
        ) {
            normalized = 0f
        } else if (isActiveSpeech) {
            normalized = max(normalized, MINIMUM_VISIBLE_ACTIVE_LEVEL)
        }

        val blend = if (normalized > displayLevel) DISPLAY_ATTACK_BLEND else DISPLAY_RELEASE_BLEND
        displayLevel = mix(displayLevel, normalized, blend)
        return displayLevel
    }

    private fun updateNoiseFloor(levelDb: Float) {
        val ceilingLimitedLevel = min(levelDb, peakCeilingDb - MIN_SPAN_DB)

        if (ceilingLimitedLevel <= noiseFloorDb) {
            noiseFloorDb = mix(noiseFloorDb, ceilingLimitedLevel, FLOOR_FALL_BLEND)
        } else if (ceilingLimitedLevel <= noiseFloorDb + FLOOR_RISE_WINDOW_DB) {
            noiseFloorDb = mix(noiseFloorDb, ceilingLimitedLevel, FLOOR_RISE_BLEND)
        }
    }

    private fun updatePeakCeiling(levelDb: Float) {
        val minimumCeiling = noiseFloorDb + MIN_SPAN_DB

        peakCeilingDb = if (levelDb >= peakCeilingDb) {
            mix(peakCeilingDb, levelDb, PEAK_ATTACK_BLEND)
        } else {
            mix(peakCeilingDb, max(levelDb, minimumCeiling), PEAK_RELEASE_BLEND)
        }

        peakCeilingDb = max(peakCeilingDb, minimumCeiling)
    }

    private fun mix(current: Float, target: Float, blend: Float): Float =
        current + (target - current) * blend

    private companion object {
        const val MINIMUM_RMS = 0.00001f
        const val MIN_SPAN_DB = 18f
        const val PEAK_HEADROOM_DB = 8f
        const val SPEECH_GATE_MARGIN_DB = 3f
        const val MINIMUM_VISIBLE_ACTIVE_LEVEL = 0.12f
        const val NOISE_GATE_NORMALIZED_THRESHOLD = 0.06f
        const val FLOOR_RISE_WINDOW_DB = 4f
        const val FLOOR_FALL_BLEND = 0.12f
        const val FLOOR_RISE_BLEND = 0.02f
        const val PEAK_ATTACK_BLEND = 0.55f
        const val PEAK_RELEASE_BLEND = 0.04f
        const val DISPLAY_ATTACK_BLEND = 0.45f
        const val DISPLAY_RELEASE_BLEND = 0.12f
        const val INITIAL_NOISE_FLOOR_DB = -55f
        const val INITIAL_PEAK_CEILING_DB = -37f
    }
}
