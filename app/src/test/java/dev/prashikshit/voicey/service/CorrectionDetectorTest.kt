package dev.prashikshit.voicey.service

import dev.prashikshit.voicey.data.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionDetectorTest {

    @Test
    fun detectsAnyExplicitWordReplacementFromTheDictation() {
        assertEquals(
            listOf(Correction("Cloud", "Claude")),
            CorrectionDetector.detect(
                original = "Open Cloud Code",
                final = "Open Claude Code",
                learnableTokens = setOf("open", "cloud", "code"),
            ),
        )
    }

    @Test
    fun noLongerRejectsAReplacementBecauseSpellingIsDissimilar() {
        assertEquals(
            listOf(Correction("meeting", "call")),
            CorrectionDetector.detect(
                original = "Schedule the meeting tomorrow",
                final = "Schedule the call tomorrow",
                learnableTokens = setOf("schedule", "the", "meeting", "tomorrow"),
            ),
        )
    }

    @Test
    fun detectsOneToMultipleWordCorrection() {
        assertEquals(
            listOf(Correction("VHISPERFLOW", "Wispr Flow")),
            CorrectionDetector.detect(
                original = "Use VHISPERFLOW today",
                final = "Use Wispr Flow today",
                learnableTokens = setOf("use", "vhisperflow", "today"),
            ),
        )
    }

    @Test
    fun ignoresChangesToTextThatVoiceyDidNotInsert() {
        assertTrue(
            CorrectionDetector.detect(
                original = "Existing text plus Voicey",
                final = "Edited text plus Voicey",
                learnableTokens = setOf("voicey"),
            ).isEmpty()
        )
    }

    @Test
    fun ignoresPureTypingAndDeletion() {
        assertTrue(
            CorrectionDetector.detect(
                original = "Hello",
                final = "Hello there",
                learnableTokens = setOf("hello"),
            ).isEmpty()
        )
        assertTrue(
            CorrectionDetector.detect(
                original = "Hello there",
                final = "Hello",
                learnableTokens = setOf("hello", "there"),
            ).isEmpty()
        )
    }
}
