package dev.prashikshit.voicey.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSpliceTest {

    @Test
    fun appendsAtEnd() {
        assertEquals(
            TextSplice.Result("Hello world", 11),
            TextSplice.atSelection("Hello ", 6, 6, "world"),
        )
    }

    @Test
    fun insertsAtCursorWithoutLosingSurroundingText() {
        assertEquals(
            TextSplice.Result("Hello beautiful world", 16),
            TextSplice.atSelection("Hello world", 6, 6, "beautiful "),
        )
    }

    @Test
    fun replacesForwardOrReversedSelection() {
        val expected = TextSplice.Result("Hello Voicey", 12)
        assertEquals(expected, TextSplice.atSelection("Hello world", 6, 11, "Voicey"))
        assertEquals(expected, TextSplice.atSelection("Hello world", 11, 6, "Voicey"))
    }

    @Test
    fun rejectsSelectionOutsideExposedText() {
        assertNull(TextSplice.atSelection("Hello", -1, -1, "Voicey"))
        assertNull(TextSplice.atSelection("Hello", 0, 6, "Voicey"))
    }
}
