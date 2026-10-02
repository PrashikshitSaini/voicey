package dev.prashikshit.voicey.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records 16 kHz / mono / 16-bit PCM audio (Whisper's native rate) and writes it to a WAV
 * file in the app's cache directory. The PCM stream is consumed on a background thread and
 * appended in real time so memory stays bounded for long recordings.
 *
 * Caller is responsible for holding the RECORD_AUDIO runtime permission before calling [start].
 * A completed file from [stop] is owned by the caller — delete it after use.
 */
class Recorder(private val context: Context) {

    @Volatile
    private var record: AudioRecord? = null
    private var outputFile: File? = null
    @Volatile
    private var captureThread: Thread? = null
    private var activeSession: CaptureSession? = null
    private val levelNormalizer = AudioLevelNormalizer()

    /**
     * Receives a smoothed 0..1 microphone level roughly every 50 ms while recording.
     * Invoked on the capture thread — consumers must hop to their own thread.
     */
    @Volatile
    var levelListener: ((Float) -> Unit)? = null

    /**
     * Receives each PCM capture chunk on the recorder thread. Consumers must copy any
     * bytes they retain: the recorder reuses its buffer for the next read.
     */
    @Volatile
    var audioChunkListener: ((ByteArray, Int) -> Unit)? = null

    /** Called from the capture thread when a read or listener fails. */
    @Volatile
    var captureFailureListener: ((Throwable) -> Unit)? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(microphoneDeviceId: Int = 0) {
        check(record == null && captureThread == null) { "Recorder already started" }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("AudioRecord buffer size unavailable on this device")
        }
        val bufferSize = maxOf(minBuffer * 2, MIN_BUFFER_BYTES)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val selectedDevice = MicrophoneDevices.findConnectedExternalInput(audioManager, microphoneDeviceId)
        // A disconnected/invalid saved ID is simply the normal system route. If a live
        // external route is rejected, retry with a fresh default-routed AudioRecord.
        val ar = try {
            createAndStart(bufferSize, selectedDevice)
        } catch (selectedFailure: Exception) {
            if (microphoneDeviceId == 0 || selectedDevice == null) throw selectedFailure
            createAndStart(bufferSize, null)
        }

        val out = try {
            File.createTempFile("voicey-", ".wav", context.cacheDir).also { file ->
                // Reserve space for the 44-byte WAV header; rewritten in stop().
                try {
                    FileOutputStream(file).use { fos -> fos.write(ByteArray(WAV_HEADER_BYTES)) }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
        } catch (e: Exception) {
            ar.release()
            throw e
        }

        outputFile = out
        record = ar
        levelNormalizer.reset()

        val session = CaptureSession(ar, out)
        activeSession = session
        captureThread = thread(name = "voicey-recorder", isDaemon = true) {
            try {
                // Read in ~50 ms chunks (rather than the full internal buffer) so the level
                // listener gets ~20 updates/sec for a responsive waveform. The AudioRecord's
                // internal buffer keeps the larger [bufferSize], so no audio is dropped — this
                // only changes how often we drain it. File content is byte-identical.
                val buf = ByteArray(LEVEL_CHUNK_BYTES)
                FileOutputStream(session.outputFile, true).use { fos ->
                    while (session.running) {
                        val read = session.record.read(buf, 0, buf.size)
                        if (read > 0) {
                            fos.write(buf, 0, read)
                            session.pcmBytesWritten += read
                            publishLevel(buf, read)
                            audioChunkListener?.invoke(buf, read)
                        } else if (read < 0) {
                            if (session.stopping) break
                            throw IOException("AudioRecord read failed: $read")
                        }
                    }
                }
            } catch (e: Exception) {
                session.failure = e
                session.running = false
                try {
                    captureFailureListener?.invoke(e)
                } catch (_: Exception) {
                    // A listener must never escape the capture worker.
                }
            } finally {
                session.completion.countDown()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAndStart(bufferSize: Int, device: AudioDeviceInfo?): AudioRecord {
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        try {
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }
            if (device != null && !ar.setPreferredDevice(device)) {
                throw IllegalStateException("Selected microphone was rejected")
            }
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone busy (another app is using it)")
            }
            return ar
        } catch (e: Exception) {
            ar.release()
            throw e
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
     * Requests stop and waits only [timeoutMs] for the owned cleanup task. A timeout leaves this
     * recorder quarantined; its cleanup task still owns the driver and file until it finishes.
     * Returns null if [start] was never called.
     */
    suspend fun stop(timeoutMs: Long = STOP_TIMEOUT_MS): StopOutcome? {
        val cleanupComplete = synchronized(activeSession ?: return null) {
            val session = activeSession ?: return null
            session.stopping = true
            session.running = false
            if (!session.cleanupStarted) {
                session.cleanupStarted = true
                thread(name = "voicey-recorder-cleanup", isDaemon = true) {
                    finishCleanup(session)
                }
            }
            session.cleanupComplete
        }

        // Timeout only cancels this wait; the cleanup task and its durable result continue.
        val result = withTimeoutOrNull(timeoutMs) { cleanupComplete.await() }
        return StopOutcome(result ?: StopResult.TimedOut(timeoutMs), cleanupComplete)
    }

    fun isRecording(): Boolean = record != null

    private fun finishCleanup(session: CaptureSession) {
        var stopFailure: Exception? = null
        val result = try {
            // This task owns stop, worker completion, release, and file finalization. The
            // caller only waits for a bounded hand-off; it never releases a live driver.
            try {
                if (session.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    session.record.stop()
                }
            } catch (e: Exception) {
                stopFailure = e
            }
            awaitUninterruptibly(session.completion)

            var hardwareReleased = false
            try {
                session.record.release()
                hardwareReleased = true
                if (stopFailure != null) {
                    throw RecorderException("Could not stop microphone", stopFailure)
                }
                session.failure?.let { throw RecorderException("Recording capture failed", it) }
                writeWavHeader(session.outputFile, session.pcmBytesWritten)
                StopResult.Completed(session.outputFile)
            } catch (e: Exception) {
                StopResult.Failed(
                    error = if (e is RecorderException) e else RecorderException("Could not finalize recording", e),
                    hardwareReleased = hardwareReleased,
                )
            }
        } catch (e: Exception) {
            StopResult.Failed(
                RecorderException("Could not clean up microphone", e),
                hardwareReleased = false,
            )
        }

        // A failed release leaves ownership quarantined. There is no safe completion signal
        // until the driver can actually be released.
        if (result is StopResult.Failed && !result.hardwareReleased) {
            try {
                session.outputFile.delete()
            } catch (_: Exception) {
                // A failed hardware release is already quarantined; never strand the worker.
            }
            return
        }

        if (result is StopResult.Failed) {
            try {
                session.outputFile.delete()
            } catch (_: Exception) {
                // There is no usable file for the pipeline to own after a failed finalization.
            }
        }
        // Clear recorder fields before publishing the durable result. A caller that timed out
        // still cannot start another capture until this task has reached this point.
        synchronized(this) {
            if (activeSession === session) {
                record = null
                captureThread = null
                outputFile = null
                activeSession = null
            }
        }
        session.cleanupComplete.complete(result)
    }

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

        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private class CaptureSession(
        val record: AudioRecord,
        val outputFile: File,
        @Volatile var running: Boolean = true,
        @Volatile var stopping: Boolean = false,
        var pcmBytesWritten: Long = 0L,
        @Volatile var failure: Throwable? = null,
        val completion: CountDownLatch = CountDownLatch(1),
        val cleanupComplete: CompletableDeferred<StopResult> = CompletableDeferred(),
        var cleanupStarted: Boolean = false,
    )

    class RecorderException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class StopOutcome(
        val result: StopResult,
        /** Completes only after the worker, driver, file ownership, and recorder fields settle. */
        val cleanupComplete: CompletableDeferred<StopResult>,
    )

    sealed class StopResult {
        data class Completed(val file: File) : StopResult()
        data class Failed(
            val error: RecorderException,
            val hardwareReleased: Boolean,
        ) : StopResult()
        data class TimedOut(val timeoutMs: Long) : StopResult()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NUM_CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_BYTES = 44
        const val MIN_BUFFER_BYTES = 8_192
        const val STOP_TIMEOUT_MS = 1_000L

        /** 50 ms of 16 kHz / 16-bit / mono PCM — the level-update cadence. */
        const val LEVEL_CHUNK_BYTES = 1_600
    }
}
