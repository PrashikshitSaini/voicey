package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupOutputGuardTest {
    @Test
    fun consecutiveDictationsDoNotReinsertPreviousFieldText() {
        val previous = "Please send the report tomorrow."
        val raw = "also include the sales figures"
        val context = CleanupContext("chat", previous, "", "")
        assertEquals(raw, CleanupOutputGuard.safeText("$previous Also include the sales figures.", raw, context))
        assertEquals("Also include the sales figures.", CleanupOutputGuard.safeText("Also include the sales figures.", raw, context))
    }

    @Test
    fun rejectsContextAfterCursorAndPartialLongContextDespiteFormattingChanges() {
        val context = CleanupContext("", "Older paragraph. Please send the report to our manager tomorrow.", "See you soon!", "")
        assertEquals("Thank you", CleanupOutputGuard.safeText("Thank you. SEE YOU SOON.", "Thank you", context))
        assertEquals("Thank you", CleanupOutputGuard.safeText("Please send the report to our manager. Thank you.", "Thank you", context))
    }

    @Test
    fun preservesIntentionalSpokenRepetitionButRejectsExtraCopies() {
        val context = CleanupContext("", "See you soon.", "", "")
        assertEquals("See you soon!", CleanupOutputGuard.safeText("See you soon!", "see you soon", context))
        assertEquals("see you soon", CleanupOutputGuard.safeText("See you soon. See you soon.", "see you soon", context))
    }

    @Test
    fun singleNameContextCanStillCorrectSpelling() {
        val context = CleanupContext("", "Prashikshit", "", "")
        assertEquals("Ask Prashikshit.", CleanupOutputGuard.safeText("Ask Prashikshit.", "ask Prash", context))
    }

    @Test
    fun detectsNonEnglishContextWithoutRemovingNewSpeech() {
        val context = CleanupContext("", "À demain!", "", "")
        assertEquals("Merci", CleanupOutputGuard.safeText("À demain. Merci!", "Merci", context))
    }

    @Test
    fun replacesExactReportedPromptLeakWithRawTranscript() {
        val leaked = """Cloud Code

FOREGROUND_APP: dev.prashikshit.voicey

CUSTOM_VOCABULARY (spellings to preserve if mentioned):
- Prashikshit
- Saini

KNOWN_CORRECTIONS (the user previously fixed these transcription mistakes)"""

        assertEquals(
            "Cloud Code",
            CleanupOutputGuard.safeText(leaked, "Cloud Code"),
        )
    }

    @Test
    fun markerDetectionIsCaseInsensitive() {
        assertTrue(CleanupOutputGuard.containsInternalScaffolding("foreground_app: Voicey"))
        assertTrue(CleanupOutputGuard.containsInternalScaffolding("focused_field_hint: Message"))
        assertTrue(CleanupOutputGuard.containsInternalScaffolding("focused_field_type: subject"))
        assertTrue(CleanupOutputGuard.containsInternalScaffolding("voicey_app_formatting_policy"))
    }

    @Test
    fun rejectsTheFormattingExamplesWordsWhenTheyWereNeverSpoken() {
        val raw = "hi priya can we meet on friday"
        val leaked = "Hi Priya,\n\nA few things for Friday:\n\n- Confirm the venue\n" +
            "- Send the agenda to the team\n- Book a cab\n\nThanks,"

        assertEquals(raw, CleanupOutputGuard.safeText(leaked, raw))
    }

    @Test
    fun keepsExampleWordsThatWereActuallySpoken() {
        val raw = "hi priya a few things for friday first confirm the venue second send " +
            "the agenda to the team and finally book a cab thanks"
        val cleaned = "Hi Priya,\n\nA few things for Friday:\n\n- Confirm the venue\n" +
            "- Send the agenda to the team\n- Book a cab\n\nThanks,"

        assertEquals(cleaned, CleanupOutputGuard.safeText(cleaned, raw))
    }

    @Test
    fun preservesNormalCleanedTranscript() {
        val cleaned = "This is the polished transcription."

        assertEquals(cleaned, CleanupOutputGuard.safeText(cleaned, "um this is uh the polished transcription"))
        assertFalse(CleanupOutputGuard.containsInternalScaffolding(cleaned))
    }

    @Test
    fun dictatedQuestionIsKeptInsteadOfAnswered() {
        val raw = "What's the capital of France?"
        assertEquals(raw, CleanupOutputGuard.safeText("The capital of France is Paris.", raw))
        assertEquals("What's the capital of France?", CleanupOutputGuard.safeText("What's the capital of France?", raw))
    }

    @Test
    fun dictatedRequestIsKeptInsteadOfCarriedOut() {
        val raw = "write an email to Sam about lunch on Friday"
        val reply = "Hi Sam,\n\nWould you like to grab lunch together this Friday? Let me know what works.\n\nBest,"
        assertEquals(raw, CleanupOutputGuard.safeText(reply, raw))
    }

    @Test
    fun normalCleanupWithListsNumbersAndVocabularyIsKept() {
        val raw = "um so first call grok second uh send the deck at five thirty"
        val cleaned = "1. Call Groq\n2. Send the deck at 5:30"
        assertEquals(cleaned, CleanupOutputGuard.safeText(cleaned, raw, allowedWords = listOf("Groq")))
    }

    @Test
    fun rawTranscriptWithMarkerRemainsRawInsteadOfLeakingOtherMetadata() {
        val raw = "Say the phrase foreground app"
        val leaked = "$raw\nFOREGROUND_APP: com.example"

        assertEquals(raw, CleanupOutputGuard.safeText(leaked, raw))
    }
}
