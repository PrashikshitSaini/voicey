package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupOutputGuardTest {

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
