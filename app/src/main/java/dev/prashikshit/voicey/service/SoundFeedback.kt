package dev.prashikshit.voicey.service

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.prashikshit.voicey.R

/**
 * Plays the gentle "dot" cues for recording start/stop. The WAVs in res/raw are
 * synthesized sine blips (soft attack, exponential decay, one warm harmonic) — see
 * scripts/generate_sounds.py for the generator.
 *
 * Uses USAGE_ASSISTANCE_SONIFICATION so the cues route like system UI feedback:
 * they respect the notification/system volume rather than media volume, and don't
 * pause or duck the user's music.
 */
class SoundFeedback(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool loads asynchronously; a play() before loading completes is a silent
    // no-op, which is acceptable for the very first dictation after service start.
    private val startSoundId = soundPool.load(context, R.raw.record_start, 1)
    private val stopSoundId = soundPool.load(context, R.raw.record_stop, 1)

    fun playStart() {
        soundPool.play(startSoundId, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun playStop() {
        soundPool.play(stopSoundId, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }

    private companion object {
        /** Deliberately soft — a cue, not an alert. */
        const val VOLUME = 0.55f
    }
}
