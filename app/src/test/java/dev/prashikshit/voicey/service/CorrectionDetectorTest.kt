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
    fun rewordingIsNotLearnedAsATranscriptionMistake() {
        assertTrue(
            CorrectionDetector.detect(
                original = "Schedule the meeting tomorrow",
                final = "Schedule the call tomorrow",
                learnableTokens = setOf("schedule", "the", "meeting", "tomorrow"),
            ).isEmpty()
        )
        assertTrue(
            CorrectionDetector.detect(
                original = "See you tomorrow",
                final = "See you today",
                learnableTokens = setOf("see", "you", "tomorrow"),
            ).isEmpty()
        )
    }

    @Test
    fun everydayWordSwapsAreNotLearned() {
        // Correct in one sentence, wrong in the next: replaying it globally corrupts dictation.
        assertTrue(
            CorrectionDetector.detect(
                original = "I left there car outside",
                final = "I left their car outside",
                learnableTokens = setOf("i", "left", "there", "car", "outside"),
            ).isEmpty()
        )
    }

    @Test
    fun learnsMisheardNamesCasingAndClippedNames() {
        assertTrue(CorrectionDetector.looksLikeMisrecognition(listOf("Voice", "e"), listOf("Voicey")))
        assertTrue(CorrectionDetector.looksLikeMisrecognition(listOf("iphone"), listOf("iPhone")))
        assertTrue(CorrectionDetector.looksLikeMisrecognition(listOf("Prash"), listOf("Prashikshit")))
        assertTrue(CorrectionDetector.looksLikeMisrecognition(listOf("Grok"), listOf("Groq")))
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
