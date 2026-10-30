package dev.prashikshit.voicey.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyEchoTest {

    private val prompt = "Prashikshit Saini, Voicey, Groq"

    @Test
    fun fullVocabularyEchoIsDetected() {
        assertTrue(isVocabularyEcho("Prashikshit Saini.", prompt))
    }

    @Test
    fun singleVocabularyTokenIsDetected() {
        assertTrue(isVocabularyEcho("prashikshit", prompt))
    }

    @Test
    fun realSentenceIsNotAnEcho() {
        assertFalse(isVocabularyEcho("Call Prashikshit Saini tomorrow", prompt))
    }

    @Test
    fun emptyTextIsNotAnEcho() {
        assertFalse(isVocabularyEcho("", prompt))
    }

    @Test
    fun emptyPromptIsNotAnEcho() {
        assertFalse(isVocabularyEcho("Prashikshit Saini", ""))
    }

    @Test
    fun otherLanguageIsNotAnEcho() {
        assertFalse(isVocabularyEcho("मुझे कल दिल्ली जाना है", prompt))
    }
}
