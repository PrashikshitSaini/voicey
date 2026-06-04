package dev.prashikshit.voicey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.prashikshit.voicey.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Five-bar live waveform shown in the pill while recording. Port of FreeFlow's
 * CompactWaveformView (MIT): center-weighted bars driven by the normalized mic level,
 * plus a traveling pulse and a subtle shimmer so the bars feel alive even between
 * level updates.
 *
 * Drive it with [setLevel] (thread-safe) and gate the animation loop with
 * [startAnimating] / [stopAnimating] so no frames are burned while idle.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile
    private var level: Float = 0f

    private var animating = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bubble_icon)
        style = Paint.Style.FILL
    }
    private val barRect = RectF()
    private val density = resources.displayMetrics.density

    private val frameTick = object : Runnable {
        override fun run() {
            if (!animating) return
            invalidate()
            postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /** Safe to call from any thread (the recorder's capture thread included). */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
    }

    fun startAnimating() {
        if (animating) return
        animating = true
        post(frameTick)
    }

    fun stopAnimating() {
        animating = false
        removeCallbacks(frameTick)
        level = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // The window can be torn away while animating (keyboard closes mid-recording and
        // the service detaches the pill); stop the frame loop so it can't leak.
        animating = false
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barWidth = BAR_WIDTH_DP * density
        val gap = BAR_GAP_DP * density
        val totalWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap
        val startX = (width - totalWidth) / 2f
        val centerY = height / 2f
        val minHeight = MIN_BAR_HEIGHT_DP * density
        val maxHeight = (height.toFloat() - 2f * density).coerceAtLeast(minHeight)
        val radius = barWidth / 2f

        val time = if (animating) SystemClock.uptimeMillis() / 1000.0 else null

        for (index in 0 until BAR_COUNT) {
            val amplitude = amplitude(index, time)
            val barHeight = minHeight + (maxHeight - minHeight) * amplitude
            val left = startX + index * (barWidth + gap)
            barRect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f)
            canvas.drawRoundRect(barRect, radius, radius, barPaint)
        }
    }

    /**
     * FreeFlow's amplitude curve: the mic level shaped by per-bar multipliers, with a
     * traveling sine pulse plus a slower shimmer layered on top. The "saturation
     * relief" term keeps loud bars from pegging; the "quiet pulse" keeps silent bars
     * gently moving so the UI never looks frozen mid-recording.
     */
    private fun amplitude(index: Int, time: Double?): Float {
        val base = min(level * MULTIPLIERS[index], 1f)
        if (time == null) return base
        val traveling = (0.5 + 0.5 * sin(time * 6.2 - index * 0.78)).toFloat()
        val shimmer = (0.5 + 0.5 * sin(time * 3.1 + index * 0.5)).toFloat()
        val pulse = traveling * 0.22f + shimmer * 0.06f
        val saturationRelief = base * (0.74f + pulse)
        val quietPulse = (1f - base) * (0.04f + pulse * 0.28f)
        return min(saturationRelief + quietPulse, 1f)
    }

    private companion object {
        const val BAR_COUNT = 5
        val MULTIPLIERS = floatArrayOf(0.5f, 0.75f, 1f, 0.75f, 0.5f)
        const val BAR_WIDTH_DP = 3f
        const val BAR_GAP_DP = 3f
        const val MIN_BAR_HEIGHT_DP = 3f
        const val FRAME_INTERVAL_MS = 33L // ~30 fps, matching FreeFlow's TimelineView.
    }
}
