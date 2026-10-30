package dev.prashikshit.voicey.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSpacingTest {

    @Test
    fun addsSpaceAfterFinishedSentence() {
        assertEquals(" World", spaceForInsertion("World", "Hello.", ""))
    }

    @Test
    fun doesNotAddSpaceAfterOpeningBracket() {
        assertEquals("note", spaceForInsertion("note", "(", ")"))
    }

    @Test
    fun leavesStartOfEmptyFieldAlone() {
        assertEquals("Hello", spaceForInsertion("Hello", "", ""))
    }

    @Test
    fun addsTrailingSpaceBeforeExistingWord() {
        assertEquals("Hello ", spaceForInsertion("Hello", "", "world"))
    }

    @Test
    fun neverChangesEmptyInsertion() {
        assertEquals("", spaceForInsertion("", "Hello.", "world"))
    }

    @Test
    fun doesNotAddSpaceAfterAtSign() {
        assertEquals("alex", spaceForInsertion("alex", "ping @", ""))
    }

    @Test
    fun doesNotDoubleSpaceExistingWhitespace() {
        assertEquals("World", spaceForInsertion("World", "Hello ", ""))
    }
}
