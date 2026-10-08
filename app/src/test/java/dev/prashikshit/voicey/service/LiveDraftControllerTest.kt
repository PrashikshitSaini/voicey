package dev.prashikshit.voicey.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDraftControllerTest {

    @Test
    fun rollingSnapshotsMatchTrailingAudioAcrossWrapsAndOversizedChunks() {
        val controller = LiveDraftController(requestIntervalMs = 0, minAudioBytes = 1, maxAudioBytes = 16)
        controller.start()
        var expected = ByteArray(0)
        val random = java.util.Random(42)
        repeat(200) {
            val chunk = ByteArray(1 + random.nextInt(48)).also(random::nextBytes)
            val length = 1 + random.nextInt(chunk.size)
            expected = (expected + chunk.copyOf(length)).takeLast(16).toByteArray()
            val snapshot = controller.offerPcm(chunk, length)!!.audio
            assertArrayEquals(expected, snapshot)
            // The recorder reuses its chunk, and requests must own independent snapshots.
            chunk.fill(0)
            assertArrayEquals(expected, snapshot)
            controller.complete("draft")
        }
        controller.stop()
        controller.start()
        assertArrayEquals(byteArrayOf(9), controller.offerPcm(byteArrayOf(9))!!.audio)
    }

    @Test
    fun inFlightSnapshotIsUnchangedWhileRollingBufferOverwritesOldAudio() {
        val controller = LiveDraftController(requestIntervalMs = 0, minAudioBytes = 1, maxAudioBytes = 4)
        controller.start()
        val first = controller.offerPcm(byteArrayOf(1, 2, 3, 4))!!.audio
        assertNull(controller.offerPcm(byteArrayOf(5, 6, 7, 8)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
        controller.complete("draft")
        assertArrayEquals(byteArrayOf(6, 7, 8, 9), controller.offerPcm(byteArrayOf(9))!!.audio)
    }

    @Test
    fun throttlesRequestsAndNeverQueuesASecondRequestWhileOneIsInFlight() {
        var now = 0L
        val controller = LiveDraftController(
            nowMs = { now },
            requestIntervalMs = 1_500,
            minAudioBytes = 4,
            maxAudioBytes = 16,
        )
        controller.start()

        assertNotNull(controller.offerPcm(byteArrayOf(1, 2, 3, 4)))
        now = 1_000
        assertNull(controller.offerPcm(byteArrayOf(5, 6, 7, 8)))

        controller.complete("hello")
        assertNull(controller.offerPcm(byteArrayOf(9)))
        now = 3_500
        assertNotNull(controller.offerPcm(byteArrayOf(10)))
    }

    @Test
    fun previewFailureFallsBackAndErasesAllEphemeralData() {
        val controller = LiveDraftController(
            minAudioBytes = 1,
            maxAudioBytes = 16,
        )
        controller.start()
        controller.offerPcm(byteArrayOf(1, 2, 3))
        controller.complete("temporary words")

        assertTrue(controller.fail())
        assertFalse(controller.isActive())
        assertEquals(0, controller.retainedAudioBytesForTest())
        assertTrue(controller.retainedDraftForTest().isEmpty)
    }

    @Test
    fun stopClearsAudioAndDraftText() {
        val controller = LiveDraftController(minAudioBytes = 1, maxAudioBytes = 16)
        controller.start()
        controller.offerPcm(byteArrayOf(1, 2, 3))
        controller.complete("never persisted")

        controller.stop()

        assertFalse(controller.isActive())
        assertEquals(0, controller.retainedAudioBytesForTest())
        assertTrue(controller.retainedDraftForTest().isEmpty)
    }

    @Test
    fun reconcilesOverlapByPromotingOnlyConfirmedPrefix() {
        val controller = LiveDraftController(minAudioBytes = 1, maxAudioBytes = 16)
        controller.start()

        controller.complete("hello there")
        val reconciled = controller.complete("there friend")

        assertEquals("hello", reconciled?.stable)
        assertEquals("there friend", reconciled?.tentative)
    }

    @Test
    fun passwordFocusRequiresPreviewToBeClearedOnlyWhenActive() {
        assertTrue(LiveDraftPrivacyPolicy.mustClearPreview(true, true))
        assertFalse(LiveDraftPrivacyPolicy.mustClearPreview(true, false))
        assertFalse(LiveDraftPrivacyPolicy.mustClearPreview(false, true))
    }
}
