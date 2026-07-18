package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAwareOutputFormatterTest {

    @Test
    fun formatsTheReportedGmailShapeIntoEmailSections() {
        val input = "Hey Jesse, I can do 5:30pm and let's chat then. Alright, see ya."

        assertEquals(
            "Hey Jesse,\n\nI can do 5:30pm and let's chat then.\n\nAlright, see ya.",
            AppAwareOutputFormatter.format(
                text = input,
                packageName = "com.google.android.gm",
                fieldHint = "Message body",
                enabled = true,
            ),
        )
    }

    @Test
    fun leavesGmailSubjectSingleLine() {
        val input = "Hello Jesse, project update."

        assertEquals(
            input,
            AppAwareOutputFormatter.format(
                text = input,
                packageName = "com.google.android.gm",
                fieldHint = "Subject",
                enabled = true,
            ),
        )
    }

    @Test
    fun leavesMessagingAndDisabledFormattingUntouched() {
        val input = "Hey Jesse, see you soon."
        assertEquals(
            input,
            AppAwareOutputFormatter.format(input, "com.whatsapp", "Message", true),
        )
        assertEquals(
            input,
            AppAwareOutputFormatter.format(input, "com.google.android.gm", "Body", false),
        )
    }
}
