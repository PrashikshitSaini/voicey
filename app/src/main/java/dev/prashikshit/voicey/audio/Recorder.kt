package dev.prashikshit.voicey.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records 16 kHz / mono / 16-bit PCM audio (Whisper's native rate) and writes it to a WAV
 * file in the app's cache directory. The PCM stream is consumed on a background thread and
 * appended in real time so memory stays bounded for long recordings.
 *
 * Caller is responsible for holding the RECORD_AUDIO runtime permission before calling [start].
 * Returned [File] from [stop] is owned by the caller — delete it after use.
 */
class Recorder(private val context: Context) {

    private var record: AudioRecord? = null
    private var outputFile: File? = null
    private var pcmBytesWritten: Long = 0L
    private var captureThread: Thread? = null
    private val levelNormalizer = AudioLevelNormalizer()

    @Volatile
    private var running: Boolean = false

    /**
     * Receives a smoothed 0..1 microphone level roughly every 50 ms while recording.
     * Invoked on the capture thread — consumers must hop to their own thread.
     */
    @Volatile
    var levelListener: ((Float) -> Unit)? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        check(record == null) { "Recorder already started" }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("AudioRecord buffer size unavailable on this device")
        }
        val bufferSize = maxOf(minBuffer * 2, MIN_BUFFER_BYTES)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        val out = File.createTempFile("voicey-", ".wav", context.cacheDir).apply {
            // Reserve space for the 44-byte WAV header; rewritten in stop().
            FileOutputStream(this).use { fos -> fos.write(ByteArray(WAV_HEADER_BYTES)) }
        }

        // Start recording BEFORE touching any of the recorder's state fields, so that
        // a failure here leaves the recorder in a clean "not started" state and can be
        // retried. Verifying recordingState catches the case where another app holds
        // the mic — startRecording() doesn't throw in that case, it just stays STOPPED.
        try {
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone busy (another app is using it)")
            }
        } catch (t: Throwable) {
            ar.release()
            out.delete()
            throw t
        }

        outputFile = out
        pcmBytesWritten = 0L
        record = ar
        running = true
        levelNormalizer.reset()

        captureThread = thread(name = "voicey-recorder", isDaemon = true) {
            // Read in ~50 ms chunks (rather than the full internal buffer) so the level
            // listener gets ~20 updates/sec for a responsive waveform. The AudioRecord's
            // internal buffer keeps the larger [bufferSize], so no audio is dropped —
            // this only changes how often we drain it. File content is byte-identical.
            val buf = ByteArray(LEVEL_CHUNK_BYTES)
            FileOutputStream(out, true).use { fos ->
                while (running) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read > 0) {
                        fos.write(buf, 0, read)
                        pcmBytesWritten += read
                        publishLevel(buf, read)
                    } else if (read < 0) {
                        // Read error — surface as silent abort; caller observes empty file.
                        break
                    }
                }
            }
        }
    }

    /** Computes chunk RMS and forwards the normalized level. Skips work when unobserved. */
    private fun publishLevel(buf: ByteArray, read: Int) {
        val listener = levelListener ?: return
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < read) {
            // 16-bit little-endian PCM.
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return
        val rms = kotlin.math.sqrt(sumSquares / sampleCount).toFloat()
        listener(levelNormalizer.normalizedLevel(rms))
    }

    /**
     * Stops the capture loop, rewrites the WAV header with the final byte counts,
     * and returns the file. Returns null if [start] was never called.
     */
    fun stop(): File? {
        val ar = record ?: return null
        val out = outputFile ?: return null

        running = false
        captureThread?.join(STOP_JOIN_TIMEOUT_MS)
        captureThread = null

        try {
            ar.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord already stopped; ignore.
        }
        ar.release()
        record = null

        writeWavHeader(out, pcmBytesWritten)
        outputFile = null
        return out
    }

    fun isRecording(): Boolean = running

    private fun writeWavHeader(file: File, pcmDataLength: Long) {
        val totalDataLen = pcmDataLength + WAV_HEADER_BYTES - 8
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM format
            putShort(NUM_CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * NUM_CHANNELS * BYTES_PER_SAMPLE) // byte rate
            putShort((NUM_CHANNELS * BYTES_PER_SAMPLE).toShort()) // block align
            putShort((BYTES_PER_SAMPLE * 8).toShort()) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmDataLength.toInt())
        }.array()

        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        } catch (e: IOException) {
            // Header write failed — file may still be playable up to data chunk; leave for caller to handle.
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NUM_CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_BYTES = 44
        const val MIN_BUFFER_BYTES = 8_192
        const val STOP_JOIN_TIMEOUT_MS = 500L

        /** 50 ms of 16 kHz / 16-bit / mono PCM — the level-update cadence. */
        const val LEVEL_CHUNK_BYTES = 1_600
    }
}
