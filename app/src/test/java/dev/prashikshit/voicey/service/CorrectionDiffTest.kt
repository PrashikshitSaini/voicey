package dev.prashikshit.voicey.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionDiffTest {

    @Test
    fun learnsStandaloneOneToTwoTokenCorrection() {
        val hunks = CorrectionDiff.replacementHunks(
            listOf("VHISPERFLOW"),
            listOf("Wispr", "Flow"),
        )

        assertEquals(listOf("VHISPERFLOW") to listOf("Wispr", "Flow"), hunks.single())
    }

    @Test
    fun learnsCorrectionAtEndOfSentence() {
        val hunks = CorrectionDiff.replacementHunks(
            listOf("hello", "wispr"),
            listOf("hello", "Wispr"),
        )

        assertEquals(listOf("wispr") to listOf("Wispr"), hunks.single())
    }

    @Test
    fun stillLearnsCorrectionInMiddleOfSentence() {
        val hunks = CorrectionDiff.replacementHunks(
            listOf("use", "VHISPERFLOW", "today"),
            listOf("use", "Wispr", "Flow", "today"),
        )

        assertEquals(listOf("VHISPERFLOW") to listOf("Wispr", "Flow"), hunks.single())
    }

    @Test
    fun ignoresPureInsertionsAndDeletions() {
        assertTrue(
            CorrectionDiff.replacementHunks(listOf("hello"), listOf("hello", "again")).isEmpty()
        )
        assertTrue(
            CorrectionDiff.replacementHunks(listOf("hello", "again"), listOf("hello")).isEmpty()
        )
    }
}
