package dev.prashikshit.voicey.service

import dev.prashikshit.voicey.audio.Recorder
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class PipelineLifecycleGateTest {
    @Test
    fun cancellationDuringIoStopStillTransfersResultAndReleasesOwnership() = runBlocking {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        val stopping = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var handedOff = false
        val job = launchOwnedSession {
            finishOwnedCleanup {
                val result = withContext(Dispatchers.IO) {
                    stopping.complete(Unit)
                    stopped.await()
                    "stopped"
                }
                assertEquals("stopped", result)
                handedOff = true
                gate.release(lease)
            }
        }
        stopping.await()
        job.cancel()
        assertFalse(gate.reserveRecording() != null)
        stopped.complete(Unit)
        job.join()
        assertTrue(handedOff)
        assertNotNull(gate.reserveRecording())
    }

    @Test
    fun rapidStopCannotReserveASecondProcessingJob() {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()
        assertNotNull(lease)
        assertTrue(gate.reserveProcessing(lease!!))
        assertFalse(gate.reserveProcessing(lease))
        assertFalse(gate.reserveRecording() != null)
    }

    @Test
    fun cancelBeforeIoCleanupKeepsOwnershipUntilRelease() {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        assertTrue(gate.reserveProcessing(lease))

        // A cancellation request does not release the gate; the owned IO stop must do that.
        assertFalse(gate.reserveRecording() != null)
        assertTrue(gate.release(lease))
        assertTrue(gate.reserveRecording() != null)
    }

    @Test
    fun cancelBeforeDispatchStillRunsOwnedCleanup() = runBlocking {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        var cleaned = false
        val job: Job = CoroutineScope(coroutineContext + Job()).launchOwnedSession {
            try {
                awaitCancellation()
            } finally {
                cleaned = true
                gate.release(lease)
            }
        }

        job.cancelAndJoin()
        assertTrue(cleaned)
        assertNotNull(gate.reserveRecording())
    }

    @Test
    fun timeoutQuarantinesUntilLateCleanupCompletes() {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        assertTrue(gate.markCleanupPending(lease))
        assertFalse(gate.reserveRecording() != null)

        runBlocking {
            val lateCleanup = CompletableDeferred<Unit>()
            val cleanupJob = PipelineCleanupHandoff(lateCleanup) { gate.release(lease) }
                .observe(this)
            assertFalse(gate.reserveRecording() != null)
            lateCleanup.complete(Unit)
            cleanupJob.join()
        }
        assertNotNull(gate.reserveRecording())
    }

    @Test
    fun staleCompletionCannotReleaseTheNextSession() = runBlocking {
        val gate = PipelineLifecycleGate()
        val oldLease = gate.reserveRecording()!!
        val oldCleanup = CompletableDeferred<Unit>().also { it.complete(Unit) }
        PipelineCleanupHandoff(oldCleanup) { gate.release(oldLease) }.observe(this).join()
        val newLease = gate.reserveRecording()!!

        val staleCleanup = CompletableDeferred<Unit>().also { it.complete(Unit) }
        PipelineCleanupHandoff(staleCleanup) { gate.release(oldLease) }.observe(this).join()
        assertFalse(gate.reserveRecording() != null)
        assertTrue(gate.release(newLease))
    }

    @Test
    fun completionBeforeObserverIsStillDelivered() = runBlocking {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        val cleanup = CompletableDeferred<Unit>()
        cleanup.complete(Unit)

        val job = PipelineCleanupHandoff(cleanup) { gate.release(lease) }.observe(this)
        job.join()
        assertNotNull(gate.reserveRecording())
    }

    @Test
    fun normalCompletionKeepsOwnershipUntilHandoffFinishes() = runBlocking {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        val cleanup = CompletableDeferred<Unit>()
        val job = PipelineCleanupHandoff(cleanup) { gate.release(lease) }.observe(this)

        assertFalse(gate.reserveRecording() != null)
        cleanup.complete(Unit)
        job.join()
        assertNotNull(gate.reserveRecording())
    }

    @Test
    fun lateRecorderResultDeliversItsFileToThePipelineOwner() = runBlocking {
        val file = File.createTempFile("voicey-test-", ".wav")
        val completion = CompletableDeferred<Recorder.StopResult>()
        val job = PipelineCleanupHandoff(completion) { result ->
            val completed = result as Recorder.StopResult.Completed
            assertEquals(file, completed.file)
            assertTrue(completed.file.delete())
        }.observe(this)

        completion.complete(Recorder.StopResult.Completed(file))
        job.join()
        assertFalse(file.exists())
    }

    @Test
    fun startingSessionCanBeStoppedBeforeHardwareIsMarkedRecording() {
        val gate = PipelineLifecycleGate()
        val lease = gate.reserveRecording()!!
        assertTrue(gate.reserveProcessing(lease))
        assertTrue(gate.markCleanupPending(lease))
        assertFalse(gate.reserveRecording() != null)
        assertTrue(gate.release(lease))
    }
}
