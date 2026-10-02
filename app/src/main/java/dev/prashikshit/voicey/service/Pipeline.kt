package dev.prashikshit.voicey.service

import android.content.Context
import dev.prashikshit.voicey.audio.Recorder
import dev.prashikshit.voicey.data.LearnedCorrections
import dev.prashikshit.voicey.data.Settings
import dev.prashikshit.voicey.net.PostProcessException
import dev.prashikshit.voicey.net.PostProcessor
import dev.prashikshit.voicey.net.TranscriptionException
import dev.prashikshit.voicey.net.WhisperClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one recording session from hardware start through final file cleanup. */
class Pipeline(
    private val context: Context,
    private val injector: TextInjector,
    private val onStateChanged: (State) -> Unit,
    private val onMessage: (String) -> Unit,
    onAudioLevel: ((Float) -> Unit)? = null,
    private val onLiveDraftChanged: ((LiveDraftUi) -> Unit)? = null,
    private val onRecordingStarting: (() -> Unit)? = null,
    private val onRecordingStopped: (() -> Unit)? = null,
) {
    enum class State { IDLE, RECORDING, PROCESSING, ERROR }

    data class LiveDraftUi(
        val mode: Mode,
        val stable: String = "",
        val tentative: String = "",
    ) {
        enum class Mode { OFF, LISTENING, DRAFT }
    }

    private val recorder = Recorder(context).apply {
        levelListener = onAudioLevel
        audioChunkListener = ::onAudioChunk
        captureFailureListener = ::onRecorderFailure
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Separate ownership scope: service shutdown cancels UI/network work but never strands
    // a recorder that is still starting or stopping.
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionJob: Job? = null
    private var errorClearJob: Job? = null
    private var liveDraftJob: Job? = null
    private var previewRequestJob: Job? = null
    @Volatile
    private var state: State = State.IDLE
    private var recordingGeneration = 0L
    private val lifecycleGate = PipelineLifecycleGate()
    private val liveDraft = LiveDraftController()
    @Volatile
    private var liveDraftSettings: Settings? = null
    @Volatile
    private var activeSession: Session? = null
    @Volatile
    private var closed = false

    fun startRecording() {
        if (closed) return
        errorClearJob?.cancel()
        errorClearJob = null

        val lease = lifecycleGate.reserveRecording() ?: return
        val session = Session(++recordingGeneration, lease)
        activeSession = session
        updateState(State.RECORDING)
        try {
            // This callback is deliberately synchronous on the service's main thread so the
            // foreground microphone claim precedes any hardware access on IO.
            onRecordingStarting?.invoke()
        } catch (e: Exception) {
            activeSession = null
            lifecycleGate.release(lease)
            notifyRecordingStopped()
            fail("Could not prepare microphone: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // Establish cleanup ownership before the caller can cancel a queued startup.
        sessionJob = sessionScope.launchOwnedSession { runSession(session) }
    }

    /** Requests finalization without doing any blocking recorder work on the caller. */
    fun stopAndProcess() {
        val session = activeSession ?: return
        if (state != State.RECORDING || !lifecycleGate.reserveProcessing(session.lease)) return
        stopLiveDraft()
        updateState(State.PROCESSING)
        session.stopSignal.complete(StopReason.PROCESS)
    }

    /** Discards asynchronously; recorder ownership remains busy until its worker is gone. */
    fun cancel() {
        recordingGeneration++
        errorClearJob?.cancel()
        errorClearJob = null
        stopLiveDraft()
        val session = activeSession
        if (session != null) {
            session.cancelRequested = true
            session.stopSignal.complete(StopReason.DISCARD)
            // Do not cancel a queued startup: it must still enter its owned IO block and
            // release the hardware. Once capture exists, cancellation only skips processing.
            if (session.captureStarted || state != State.RECORDING) sessionJob?.cancel()
        }
        if (state != State.IDLE) updateState(State.IDLE)
    }

    fun shutdown() {
        if (closed) return
        closed = true
        cancel()
        scope.cancel()
        // sessionScope is released by the session's finally block after recorder cleanup.
    }

    private suspend fun runSession(session: Session) {
        var captureStopped = false
        var reason: StopReason? = null
        try {
            // Settings decryption and all AudioRecord construction/startup are blocking.
            withContext(NonCancellable + Dispatchers.IO) {
                val settings = Settings.load(context)
                if (session.cancelRequested) return@withContext
                recorder.start(settings.microphoneDeviceId)
                session.captureStarted = true
            }
            if (!session.captureStarted) return
            // Gate state is main-thread ownership. Recorder startup is IO-only; publishing
            // RECORDING here avoids a phase mutation racing reserve/stop callbacks.
            lifecycleGate.markRecording(session.lease)
            beginLiveDraftIfEnabled()
            reason = session.stopSignal.await()
            when (val result = stopCapture(session).result) {
                is Recorder.StopResult.Completed -> {
                    captureStopped = true
                }
                is Recorder.StopResult.Failed -> {
                    if (!result.hardwareReleased) return
                    captureStopped = true
                    throw result.error
                }
                is Recorder.StopResult.TimedOut -> return
            }

            when (reason) {
                StopReason.DISCARD -> return
                StopReason.CAPTURE_FAILURE -> {
                    failIfCurrent(
                        session.generation,
                        "Recording failed: ${session.failure?.message ?: "microphone read failed"}",
                    )
                    return
                }
                StopReason.PROCESS -> Unit
                null -> return
            }

            val file = session.audioFile ?: run {
                failIfCurrent(session.generation, "Recording produced no audio file")
                return
            }
            if (file.length() < MIN_AUDIO_BYTES) {
                updateStateIfCurrent(session.generation, State.IDLE)
                onMessageIfCurrent(session.generation, "Too short")
                return
            }

            processAudio(session.generation, file)
        } catch (_: CancellationException) {
            // cancel() has already invalidated this generation; finally still owns stop/delete.
        } catch (e: TranscriptionException) {
            failIfCurrent(session.generation, "Transcription failed: ${e.message}")
        } catch (e: PostProcessException) {
            failIfCurrent(session.generation, "Cleanup failed: ${e.message}")
        } catch (e: Exception) {
            val prefix = if (!captureStopped) "Recording failed" else "Unexpected error"
            failIfCurrent(session.generation, "$prefix: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // This is also the shutdown path. It is never allowed to be cancelled before the
            // capture thread has stopped and its file is no longer being written.
            if (!captureStopped && session.captureStarted) {
                if (!session.cleanupPending) {
                    try {
                        when (val result = stopCapture(session).result) {
                            is Recorder.StopResult.Completed -> Unit
                            is Recorder.StopResult.Failed -> Unit
                            is Recorder.StopResult.TimedOut -> Unit
                        }
                    } catch (_: Exception) {
                        // The recorder owns any driver cleanup that is still pending.
                    }
                }
            }
            if (!session.cleanupPending && !session.stoppedNotified) {
                session.stoppedNotified = true
                notifyRecordingStopped()
            }
            stopLiveDraft()
            session.audioFile?.let { deleteRecording(it, session.generation) }
            if (isCurrent(session.generation) && state == State.PROCESSING) updateState(State.IDLE)
            session.runFinished = true
            if (activeSession === session && !session.cleanupPending) {
                activeSession = null
                sessionJob = null
                lifecycleGate.release(session.lease)
                if (closed) sessionScope.cancel()
            } else if (activeSession === session) {
                sessionJob = null
            }
        }
    }

    private suspend fun stopCapture(session: Session): Recorder.StopOutcome {
        session.cleanupPending = true
        lifecycleGate.markCleanupPending(session.lease)
        val outcome = withContext(NonCancellable + Dispatchers.IO) {
            recorder.stop(STOP_TIMEOUT_MS)
        } ?: run {
            val result = Recorder.StopResult.Failed(
                error = Recorder.RecorderException("Recording session is unavailable"),
                hardwareReleased = false,
            )
            Recorder.StopOutcome(result, CompletableDeferred())
        }

        // A completed stop transfers the file to this pipeline. A timeout transfers no file;
        // its late deferred result is deleted by the observer below.
        val completed = outcome.result as? Recorder.StopResult.Completed
        session.pipelineOwnsFile = completed != null
        session.audioFile = completed?.file

        // This is the ownership handoff used for both an already-finished stop and a late
        // cleanup. A deferred retains completion, so ordering with the 1s observer is safe.
        PipelineCleanupHandoff(
            completion = outcome.cleanupComplete,
            onComplete = { result -> onRecorderCleanupComplete(session, result) },
        ).observe(sessionScope)

        when (outcome.result) {
            is Recorder.StopResult.TimedOut -> {
                // Keep the lease and microphone foreground claim until the independent
                // recorder cleanup task reports that the driver really stopped.
                if (!closed && activeSession === session) {
                    updateState(State.ERROR)
                    onMessage("Microphone did not stop; retry after it recovers or restart Voicey")
                }
            }
            is Recorder.StopResult.Completed -> Unit
            is Recorder.StopResult.Failed -> if (!outcome.result.hardwareReleased) {
                if (!closed && activeSession === session) {
                    updateState(State.ERROR)
                    onMessage("Microphone cleanup failed; restart Voicey before retrying")
                }
            }
        }
        return outcome
    }

    private suspend fun deleteRecording(file: File, generation: Long) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (file.exists() && !file.delete() && isCurrent(generation)) {
                    onMessage("Could not clear recording cache")
                }
            } catch (_: Exception) {
                if (isCurrent(generation)) onMessage("Could not clear recording cache")
            }
        }
    }

    private fun notifyRecordingStopped() {
        try {
            onRecordingStopped?.invoke()
        } catch (_: RuntimeException) {
            // Teardown notification must not hide recorder cleanup or strand ownership.
        }
    }

    private suspend fun processAudio(generation: Long, file: File) {
        val (settings, corrections) = withContext(Dispatchers.IO) {
            Settings.load(context) to LearnedCorrections(context).all()
        }
        if (!settings.isReady()) {
            failIfCurrent(generation, "Set API key in Voicey settings")
            return
        }

        val raw = WhisperClient(settings).transcribe(file)
        if (raw.isBlank()) {
            updateStateIfCurrent(generation, State.IDLE)
            onMessageIfCurrent(generation, "Heard nothing")
            return
        }

        val focusedNode = FocusAccessibilityService.findFocusedEditable()
        val nodePackage = focusedNode?.packageName?.toString().orEmpty()
        val editorPackage = FocusAccessibilityService.currentEditorPackageName()
        val targetPackage = editorPackage
            .takeIf { it.isNotBlank() }
            ?: nodePackage.takeIf { it.isNotBlank() && it != context.packageName }
            ?: FocusAccessibilityService.currentPackageName()
        val ctx = ContextReader.read(focusedNode, targetPackage)
        val cleaned = PostProcessor(settings, corrections).clean(raw, ctx)
        val toInsert = cleaned.ifBlank { raw }

        val result = injector.insert(
            node = focusedNode,
            text = toInsert,
            neverUseClipboard = settings.neverUseClipboard,
        )
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
                updateStateIfCurrent(generation, State.IDLE)
            }
            TextInjector.InsertionResult.NO_FOCUSED_NODE -> {
                updateStateIfCurrent(generation, State.IDLE)
                onMessageIfCurrent(generation, "Tap a text field first")
            }
            TextInjector.InsertionResult.FAILED -> failIfCurrent(generation, "This app blocks accessibility writes")
            TextInjector.InsertionResult.CLIPBOARD_REQUIRED -> {
                failIfCurrent(generation, "This editor needs clipboard fallback; disable strict clipboard-free mode")
            }
            TextInjector.InsertionResult.SKIPPED_EMPTY -> updateStateIfCurrent(generation, State.IDLE)
        }
    }

    private fun onRecorderFailure(error: Throwable) {
        val session = activeSession ?: return
        session.failure = error
        session.stopSignal.complete(StopReason.CAPTURE_FAILURE)
    }

    private suspend fun onRecorderCleanupComplete(session: Session, result: Recorder.StopResult) {
        if (result is Recorder.StopResult.Completed && !session.pipelineOwnsFile) {
            // A timed-out stop has no local file variable to clean up. The durable result is
            // the ownership handoff for this late path.
            deleteRecording(result.file, session.generation)
        }
        session.cleanupPending = false
        session.captureStarted = false
        if (!session.stoppedNotified) {
            session.stoppedNotified = true
            notifyRecordingStopped()
        }
        // Normal cleanup is observed before processing and the gate remains owned until
        // runSession's finally. Late cleanup can release only after that finally has run.
        if (!session.runFinished || activeSession !== session) return

        activeSession = null
        sessionJob = null
        lifecycleGate.release(session.lease)
        if (!closed && isCurrent(session.generation)) {
            if (result is Recorder.StopResult.Failed) {
                updateState(State.ERROR)
                onMessage("Recording cleanup failed: ${result.error.message ?: "restart Voicey before retrying"}")
            } else if (state == State.ERROR) {
                updateState(State.IDLE)
            }
        }
        if (closed) sessionScope.cancel()
    }

    private fun beginLiveDraftIfEnabled() {
        liveDraftJob?.cancel()
        liveDraftJob = scope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { Settings.load(context) }
                if (state != State.RECORDING || !recorder.isRecording() ||
                    settings.compactBubble || !settings.liveDraftPreview ||
                    FocusAccessibilityService.isPasswordFieldFocused()
                ) return@launch
                liveDraftSettings = settings
                liveDraft.start()
                onLiveDraftChanged?.invoke(LiveDraftUi(LiveDraftUi.Mode.LISTENING))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Preview is optional; settings/cache failures must not kill the service.
            }
        }
    }

    /** Called on Recorder's capture thread. It never alters the final recording flow. */
    private fun onAudioChunk(chunk: ByteArray, length: Int) {
        if (!liveDraft.isActive()) return
        if (LiveDraftPrivacyPolicy.mustClearPreview(
                isPreviewActive = true,
                isPasswordFieldFocused = FocusAccessibilityService.isPasswordFieldFocused(),
            )
        ) {
            scope.launch { stopLiveDraft() }
            return
        }
        val request = liveDraft.offerPcm(chunk, length) ?: return
        val settings = liveDraftSettings ?: return
        previewRequestJob = scope.launch {
            try {
                val text = WhisperClient(settings).transcribePreview(request.audio)
                val draft = liveDraft.complete(text) ?: return@launch
                onLiveDraftChanged?.invoke(
                    LiveDraftUi(
                        mode = if (draft.isEmpty) LiveDraftUi.Mode.LISTENING else LiveDraftUi.Mode.DRAFT,
                        stable = draft.stable,
                        tentative = draft.tentative,
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                if (liveDraft.fail()) onLiveDraftChanged?.invoke(LiveDraftUi(LiveDraftUi.Mode.OFF))
            }
        }
    }

    private fun stopLiveDraft() {
        liveDraftJob?.cancel()
        liveDraftJob = null
        previewRequestJob?.cancel()
        previewRequestJob = null
        liveDraft.stop()
        liveDraftSettings = null
        onLiveDraftChanged?.invoke(LiveDraftUi(LiveDraftUi.Mode.OFF))
    }

    private fun fail(message: String) {
        updateState(State.ERROR)
        onMessage(message)
        errorClearJob?.cancel()
        errorClearJob = scope.launch {
            delay(ERROR_AUTO_CLEAR_MS)
            updateState(State.IDLE)
        }
    }

    private fun updateState(next: State) {
        state = next
        onStateChanged(next)
    }

    private fun isCurrent(generation: Long): Boolean = generation == recordingGeneration
    private fun updateStateIfCurrent(generation: Long, next: State) {
        if (isCurrent(generation)) updateState(next)
    }
    private fun onMessageIfCurrent(generation: Long, message: String) {
        if (isCurrent(generation)) onMessage(message)
    }
    private fun failIfCurrent(generation: Long, message: String) {
        if (isCurrent(generation)) fail(message)
    }

    private enum class StopReason { PROCESS, DISCARD, CAPTURE_FAILURE }

    private class Session(
        val generation: Long,
        val lease: PipelineLifecycleGate.Lease,
        val stopSignal: CompletableDeferred<StopReason> = CompletableDeferred(),
    ) {
        @Volatile var captureStarted = false
        @Volatile var cancelRequested = false
        @Volatile var cleanupPending = false
        @Volatile var pipelineOwnsFile = false
        @Volatile var audioFile: File? = null
        @Volatile var failure: Throwable? = null
        @Volatile var stoppedNotified = false
        @Volatile var runFinished = false
    }

    private companion object {
        const val MIN_AUDIO_BYTES = 4_096L
        const val ERROR_AUTO_CLEAR_MS = 2_000L
        const val STOP_TIMEOUT_MS = 1_000L
    }
}

/** UNDISPATCHED is the ownership boundary: cancellation cannot strand the session before its
 * try/finally has started. The actual blocking work still immediately hops to IO. */
internal fun CoroutineScope.launchOwnedSession(block: suspend CoroutineScope.() -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED, block = block)

/** Per-session completion handoff; unlike a listener it cannot lose an early completion. */
internal class PipelineCleanupHandoff<T>(
    private val completion: Deferred<T>,
    private val onComplete: suspend (T) -> Unit,
) {
    fun observe(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) { onComplete(completion.await()) }
    }
}

/** Main-thread lifecycle ownership. A stale completion cannot release a newer session. */
internal class PipelineLifecycleGate {
    enum class Phase { IDLE, STARTING, RECORDING, PROCESSING, CLEANUP_PENDING }

    data class Lease internal constructor(val id: Long)

    private var phase = Phase.IDLE
    private var ownerId = 0L
    private var nextId = 0L

    fun reserveRecording(): Lease? {
        if (phase != Phase.IDLE) return null
        phase = Phase.STARTING
        val lease = Lease(++nextId)
        ownerId = lease.id
        return lease
    }

    fun reserveProcessing(lease: Lease): Boolean {
        if ((phase != Phase.STARTING && phase != Phase.RECORDING) || ownerId != lease.id) return false
        phase = Phase.PROCESSING
        return true
    }

    fun markRecording(lease: Lease): Boolean {
        if (ownerId != lease.id) return false
        if (phase == Phase.STARTING) phase = Phase.RECORDING
        return phase == Phase.RECORDING || phase == Phase.PROCESSING
    }

    fun markCleanupPending(lease: Lease): Boolean {
        if (ownerId != lease.id || phase == Phase.IDLE) return false
        phase = Phase.CLEANUP_PENDING
        return true
    }

    fun release(lease: Lease): Boolean {
        if (ownerId != lease.id) return false
        phase = Phase.IDLE
        ownerId = 0L
        return true
    }
}
