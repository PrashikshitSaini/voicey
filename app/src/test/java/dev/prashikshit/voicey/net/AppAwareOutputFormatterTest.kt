package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAwareOutputFormatterTest {

    private fun context(
        app: String = "",
        hint: String = "",
        kind: FieldKind = FieldKind.UNKNOWN,
    ) = CleanupContext(app = app, textBefore = "", textAfter = "", fieldHint = hint, fieldKind = kind)

    private fun format(text: String, context: CleanupContext, enabled: Boolean = true) =
        AppAwareOutputFormatter.format(text = text, context = context, enabled = enabled)

    @Test
    fun formatsTheReportedGmailShapeIntoEmailSections() {
        val input = "Hey Jesse, I can do 5:30pm and let's chat then. Alright, see ya."

        assertEquals(
            "Hey Jesse,\n\nI can do 5:30pm and let's chat then.\n\nAlright, see ya.",
            format(input, context("com.google.android.gm", "Message body")),
        )
    }

    @Test
    fun leavesGmailSubjectSingleLine() {
        val input = "Hello Jesse, project update."

        assertEquals(input, format(input, context("com.google.android.gm", "Subject")))
    }

    @Test
    fun leavesMessagingAndDisabledFormattingUntouched() {
        val input = "Hey Jesse, see you soon."
        assertEquals(input, format(input, context("com.whatsapp", "Message")))
        assertEquals(input, format(input, context("com.google.android.gm", "Body"), enabled = false))
    }

    @Test
    fun spokenSignOffWithNameBecomesAClosingBlock() {
        val input = "I can make the call at noon, see you there. Best regards, Prashikshit"

        assertEquals(
            "I can make the call at noon, see you there.\n\nBest regards,\nPrashikshit",
            format(input, context("com.google.android.gm", "Message body")),
        )
    }

    @Test
    fun greetingVariantsBecomeAnEmailOpening() {
        val input = "Good morning Jesse, the report is ready."

        assertEquals(
            "Good morning Jesse,\n\nthe report is ready.",
            format(input, context("com.google.android.gm", "Message body")),
        )
    }

    @Test
    fun thanksInsideSentenceIsNotASignOff() {
        val input = "Thanks for the update on the project."

        assertEquals(input, format(input, context("com.google.android.gm", "Message body")))
    }

    @Test
    fun listifiesASpokenEnumerationInAnEmailBody() {
        val input = "Hi Priya,\n\nI need a few things for Friday. First, confirm the venue. " +
            "Second, send the agenda. Finally, book a cab.\n\nThanks,"

        assertEquals(
            "Hi Priya,\n\nI need a few things for Friday:\n\n- Confirm the venue\n" +
                "- Send the agenda\n- Book a cab\n\nThanks,",
            format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)),
        )
    }

    @Test
    fun leavesEnumerationAloneInMessaging() {
        val input = "First, confirm the venue. Second, send the agenda."

        assertEquals(input, format(input, context("com.whatsapp", "Message", FieldKind.MULTI_LINE)))
    }

    @Test
    fun leavesAnExistingListAlone() {
        val input = "Hi Priya,\n\n- Confirm the venue\n- Send the agenda\n\nThanks,"

        assertEquals(input, format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)))
    }

    @Test
    fun doesNotListifyProseThatMerelyStartsWithFirst() {
        val input = "First of all, thanks for coming."

        assertEquals(input, format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)))
    }

    @Test
    fun keepsASentenceAfterAMidParagraphListAsItsOwnParagraph() {
        val input = "Hi team,\n\nI want to cover two things. First, book the room. " +
            "Second, send the invite. We can review the rest later.\n\nThanks,"

        assertEquals(
            "Hi team,\n\nI want to cover two things:\n\n- Book the room\n- Send the invite\n\n" +
                "We can review the rest later.\n\nThanks,",
            format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)),
        )
    }

    @Test
    fun leavesNarrativeFirstThenProseUnchanged() {
        val input = "First we tried the old router. Then it worked."

        assertEquals(input, format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)))
    }

    @Test
    fun listifiesATwoSentenceRunWithASecondOrdinal() {
        val input = "First, call Sam. Second, email Joe."

        assertEquals(
            "- Call Sam\n- Email Joe",
            format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)),
        )
    }

    @Test
    fun listifiesAThreeSentenceRunOfLooseContinuations() {
        val input = "First, call Sam. Then email Joe. Also book the room."

        assertEquals(
            "- Call Sam\n- Email Joe\n- Book the room",
            format(input, context("com.google.android.gm", "Message body", FieldKind.MULTI_LINE)),
        )
    }

    @Test
    fun flattensSearchFieldAndDropsOneTrailingPeriod() {
        val input = "- first result\n- second result."

        assertEquals(
            "first result second result",
            format(input, context("com.google.android.googlequicksearchbox", "Search", FieldKind.SEARCH)),
        )
    }

    @Test
    fun leavesSingleLineFieldsUnflattenedInAnyApp() {
        val input = "1. Book the room\n2. Send the invite\n\n3. Confirm"

        assertEquals(input, format(input, context("com.whatsapp", "", FieldKind.SINGLE_LINE)))
    }

    @Test
    fun singleLineFieldKeepsAParagraphBreak() {
        val input = "First paragraph.\n\nSecond paragraph."

        assertEquals(input, format(input, context("com.openai.chatgpt", "", FieldKind.SINGLE_LINE)))
    }

    @Test
    fun compactsSpacedEmailAddress() {
        assertEquals(
            "prashikshit@gmail.com",
            format("prashikshit @ gmail . com", context("com.google.android.gm", "To", FieldKind.EMAIL_ADDRESS)),
        )
    }

    @Test
    fun leavesNonEmailAddressTextAlone() {
        val input = "ask Prashikshit about it"

        assertEquals(input, format(input, context("com.google.android.gm", "To", FieldKind.EMAIL_ADDRESS)))
    }
}
