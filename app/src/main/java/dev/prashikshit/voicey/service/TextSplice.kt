package dev.prashikshit.voicey.service

import kotlin.math.max
import kotlin.math.min

/** Pure cursor/selection reconstruction used by strict clipboard-free insertion. */
internal object TextSplice {

    data class Result(val text: String, val caret: Int)

    fun atSelection(
        current: String,
        rawStart: Int,
        rawEnd: Int,
        insertion: String,
    ): Result? {
        if (rawStart !in 0..current.length || rawEnd !in 0..current.length) return null
        val selectionStart = min(rawStart, rawEnd)
        val selectionEnd = max(rawStart, rawEnd)
        return Result(
            text = current.replaceRange(selectionStart, selectionEnd, insertion),
            caret = selectionStart + insertion.length,
        )
    }
}
