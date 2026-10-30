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
    fun preservesNormalCleanedTranscript() {
        val cleaned = "This is the polished transcription."

        assertEquals(cleaned, CleanupOutputGuard.safeText(cleaned, "raw words"))
        assertFalse(CleanupOutputGuard.containsInternalScaffolding(cleaned))
    }

    @Test
    fun rawTranscriptWithMarkerRemainsRawInsteadOfLeakingOtherMetadata() {
        val raw = "Say the phrase foreground app"
        val leaked = "$raw\nFOREGROUND_APP: com.example"

        assertEquals(raw, CleanupOutputGuard.safeText(leaked, raw))
    }
}
