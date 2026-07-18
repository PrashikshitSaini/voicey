package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFormattingGuideTest {

    @Test
    fun recognizesCommonEmailApps() {
        assertEquals(
            AppFormattingGuide.Category.EMAIL,
            AppFormattingGuide.categoryFor("com.google.android.gm"),
        )
        assertEquals(
            AppFormattingGuide.Category.EMAIL,
            AppFormattingGuide.categoryFor("com.microsoft.office.outlook"),
        )
    }

    @Test
    fun recognizesSamsungNotesAndMessagingApps() {
        assertEquals(
            AppFormattingGuide.Category.NOTES,
            AppFormattingGuide.categoryFor("com.samsung.android.app.notes"),
        )
        assertEquals(
            AppFormattingGuide.Category.MESSAGING,
            AppFormattingGuide.categoryFor("com.whatsapp"),
        )
    }

    @Test
    fun emailPolicyUsesListsOnlyWhenContentCallsForThem() {
        val policy = AppFormattingGuide.instructionFor("com.google.android.gm")

        assertTrue(policy.contains("multiple unordered points"))
        assertTrue(policy.contains("explicit sequence"))
        assertTrue(policy.contains("Keep short prose as prose"))
    }

    @Test
    fun unknownAppGetsConservativeGeneralFormatting() {
        assertEquals(
            AppFormattingGuide.Category.GENERAL,
            AppFormattingGuide.categoryFor("com.example.editor"),
        )
    }
}
