package dev.prashikshit.voicey.service

import android.content.Context
import dev.prashikshit.voicey.audio.Recorder
import dev.prashikshit.voicey.data.LearnedCorrections
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.net.PostProcessException
import dev.prashikshit.voicey.net.PostProcessor
import dev.prashikshit.voicey.net.TranscriptionException
import dev.prashikshit.voicey.net.WhisperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the end-to-end dictation flow: start recording → stop → transcribe →
 * clean up → inject. State changes flow through [onStateChanged] so the bubble can
 * update its color and animation.
 *
 * One Pipeline instance lives for the lifetime of the FloatingBubbleService. It owns
 * its own coroutine scope and the underlying Recorder.
 */
class Pipeline(
    private val context: Context,
    private val injector: TextInjector,
    private val onStateChanged: (State) -> Unit,
    private val onMessage: (String) -> Unit,
    /** Smoothed 0..1 mic level, ~20 Hz while recording. Called on the capture thread. */
    onAudioLevel: ((Float) -> Unit)? = null,
) {

    enum class State { IDLE, RECORDING, PROCESSING, ERROR }

    private val recorder = Recorder(context).apply { levelListener = onAudioLevel }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var processingJob: Job? = null
    private var errorClearJob: Job? = null

    fun startRecording() {
        // Cancel any pending auto-clear from a recent error so it can't stomp
        // the new RECORDING state mid-capture.
        errorClearJob?.cancel()
        errorClearJob = null

        if (recorder.isRecording()) return
        try {
            recorder.start()
            updateState(State.RECORDING)
        } catch (e: SecurityException) {
            fail("Microphone permission denied")
        } catch (e: IllegalStateException) {
            fail("Microphone busy: ${e.message ?: "unknown"}")
        } catch (e: IllegalArgumentException) {
            fail("Microphone not supported on this device")
        }
    }

    /**
     * Stops recording and kicks off transcription + cleanup + injection on a coroutine.
     * Safe to call when not recording (no-ops).
     */
    fun stopAndProcess() {
        if (!recorder.isRecording()) return
        // recorder.stop() joins a thread for up to 500ms — push it off the main thread.
        processingJob = scope.launch {
            val audioFile = withContext(Dispatchers.IO) { recorder.stop() } ?: run {
                fail("Recording produced no audio file")
                return@launch
            }
            if (audioFile.length() < MIN_AUDIO_BYTES) {
                withContext(Dispatchers.IO) { audioFile.delete() }
                updateState(State.IDLE)
                onMessage("Too short")
                return@launch
            }

            updateState(State.PROCESSING)
            try {
                val (settings, corrections) = withContext(Dispatchers.IO) {
                    Settings.load(context) to LearnedCorrections(context).all()
                }
                if (!settings.isReady()) {
                    fail("Set API key in Voicey settings")
                    return@launch
                }

                val whisper = WhisperClient(settings)
                val postProcessor = PostProcessor(settings, corrections)

                val raw = whisper.transcribe(audioFile)
                if (raw.isBlank()) {
                    updateState(State.IDLE)
                    onMessage("Heard nothing")
                    return@launch
                }

                val focusedNode = FocusAccessibilityService.findFocusedEditable()
                // The node's own package is ground truth for "which app are we typing
                // into". currentPackageName() is event-stream noise — the last event
                // before insertion is usually from Voicey's own pill re-rendering or
                // the keyboard, which broke correction learning and context labeling.
                val targetPackage = focusedNode?.packageName?.toString()
                    ?: FocusAccessibilityService.currentPackageName()
                val ctx = ContextReader.read(focusedNode, targetPackage)

                val cleaned = postProcessor.clean(raw, ctx)
                val toInsert = cleaned.ifBlank { raw }

                val result = injector.insert(focusedNode, toInsert)
                focusedNode?.recycle()

                when (result) {
                    TextInjector.InsertionResult.WROTE -> {
                        if (settings.learnCorrections) {
                            CorrectionLearner.onTextInserted(
                                context = context,
                                inserted = toInsert,
                                packageName = targetPackage,
                                textBefore = ctx.textBefore,
                                textAfter = ctx.textAfter,
                            )
                        }
                        updateState(State.IDLE)
                    }
                    TextInjector.InsertionResult.NO_FOCUSED_NODE -> {
                        updateState(State.IDLE)
                        onMessage("Tap a text field first")
                    }
                    TextInjector.InsertionResult.FAILED -> fail("This app blocks accessibility writes")
                    TextInjector.InsertionResult.SKIPPED_EMPTY -> updateState(State.IDLE)
                }
            } catch (ce: CancellationException) {
                // Don't convert cancellation into an error toast or a state thrash.
                throw ce
            } catch (e: TranscriptionException) {
                fail("Transcription failed: ${e.message}")
            } catch (e: PostProcessException) {
                fail("Cleanup failed: ${e.message}")
            } catch (e: Exception) {
                fail("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(Dispatchers.IO) { audioFile.delete() }
            }
        }
    }

    fun cancel() {
        processingJob?.cancel()
        processingJob = null
        if (recorder.isRecording()) {
            recorder.stop()?.delete()
        }
        updateState(State.IDLE)
    }

    fun shutdown() {
        cancel()
        scope.cancel()
    }

    private fun fail(message: String) {
        updateState(State.ERROR)
        onMessage(message)
        // Auto-clear error after a beat so the user can try again. Tracked so that
        // a new startRecording() can cancel a pending clear and not have it stomp
        // the RECORDING state mid-capture.
        errorClearJob?.cancel()
        errorClearJob = scope.launch {
            delay(ERROR_AUTO_CLEAR_MS)
            updateState(State.IDLE)
        }
    }

    private fun updateState(state: State) {
        onStateChanged(state)
    }

    private companion object {
        const val MIN_AUDIO_BYTES = 4_096L
        const val ERROR_AUTO_CLEAR_MS = 2_000L
    }
}
