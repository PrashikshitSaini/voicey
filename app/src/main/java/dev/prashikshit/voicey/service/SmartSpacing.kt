package dev.prashikshit.voicey.service

/** Adds the single separating space that cursor insertion otherwise loses. */
internal fun spaceForInsertion(insertion: String, textBefore: String, textAfter: String): String {
    if (insertion.isEmpty()) return insertion

    val leading = if (
        textBefore.isNotEmpty() &&
        !textBefore.last().isWhitespace() &&
        textBefore.last() !in OPENING_CHARS &&
        insertion.first().isLetterOrDigit()
    ) " " else ""

    val trailing = if (
        textAfter.isNotEmpty() &&
        textAfter.first().isLetterOrDigit() &&
        !insertion.last().isWhitespace()
    ) " " else ""

    return leading + insertion + trailing
}

private const val OPENING_CHARS = "([{\"'“‘@#/"
