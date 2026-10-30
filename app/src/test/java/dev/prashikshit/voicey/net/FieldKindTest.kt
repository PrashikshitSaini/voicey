package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldKindTest {

    @Test
    fun searchHintWins() {
        assertEquals(FieldKind.SEARCH, classifyField(0, false, "Search messages"))
        assertEquals(FieldKind.SEARCH, classifyField(TYPE_TEXT, false, "SEARCH"))
    }

    @Test
    fun urlVariationAndHints() {
        assertEquals(FieldKind.URL, classifyField(TYPE_TEXT_VARIATION_URI, false, ""))
        assertEquals(FieldKind.URL, classifyField(0, false, "Enter URL"))
        assertEquals(FieldKind.URL, classifyField(0, false, "Web address"))
    }

    @Test
    fun emailAddressVariationsAndHints() {
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false, ""))
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, false, ""))
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(0, false, "Email address"))
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(0, false, "Recipient"))
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(0, false, "to"))
    }

    @Test
    fun subjectVariationAndHints() {
        assertEquals(FieldKind.SUBJECT, classifyField(TYPE_TEXT_VARIATION_EMAIL_SUBJECT, false, ""))
        assertEquals(FieldKind.SUBJECT, classifyField(0, false, "Subject"))
        assertEquals(FieldKind.SUBJECT, classifyField(0, false, "Notion title"))
    }

    @Test
    fun shortFields() {
        assertEquals(FieldKind.SHORT, classifyField(TYPE_TEXT_VARIATION_PERSON_NAME, false, ""))
        assertEquals(FieldKind.SHORT, classifyField(TYPE_CLASS_NUMBER, false, ""))
        assertEquals(FieldKind.SHORT, classifyField(TYPE_CLASS_PHONE, false, ""))
    }

    @Test
    fun multiLineFromFlagOrNode() {
        assertEquals(FieldKind.MULTI_LINE, classifyField(TYPE_TEXT_FLAG_MULTI_LINE, false, ""))
        assertEquals(FieldKind.MULTI_LINE, classifyField(TYPE_TEXT, true, ""))
    }

    @Test
    fun plainTextFieldIsSingleLine() {
        assertEquals(FieldKind.SINGLE_LINE, classifyField(TYPE_TEXT, false, ""))
    }

    @Test
    fun unknownWhenTheNodeReportsNothing() {
        assertEquals(FieldKind.UNKNOWN, classifyField(0, false, ""))
    }

    private companion object {
        const val TYPE_TEXT = 1
        const val TYPE_CLASS_NUMBER = 2
        const val TYPE_CLASS_PHONE = 3
        const val TYPE_TEXT_VARIATION_URI = 0x10
        const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
        const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x30
        const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x60
        const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
        const val TYPE_TEXT_FLAG_MULTI_LINE = 0x20000
    }
}
