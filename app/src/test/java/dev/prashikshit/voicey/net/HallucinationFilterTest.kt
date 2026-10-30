package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {

    @Test
    fun koreanSilenceHallucinationIsUnreliable() {
        assertTrue(isUnreliableRetry("안녕하세요.", 0.0))
    }

    @Test
    fun russianSilenceHallucinationIsUnreliable() {
        assertTrue(isUnreliableRetry("Продолжение следует...", 0.0))
    }

    @Test
    fun realEnglishSentenceIsReliable() {
        assertFalse(isUnreliableRetry("Let's meet at five tomorrow", 0.05))
    }

    @Test
    fun realHindiSentenceIsReliable() {
        assertFalse(isUnreliableRetry("मैं कल ऑफिस जाऊँगा", 0.05))
    }

    @Test
    fun highNoSpeechProbabilityIsUnreliable() {
        assertTrue(isUnreliableRetry("Let's meet at five tomorrow", 0.35))
    }

    @Test
    fun knownPhrasesAreStoredInNormalisedForm() {
        for (phrase in KNOWN_HALLUCINATION_PHRASES) {
            assertEquals(phrase, normalizeHallucinationText(phrase))
        }
    }
}
