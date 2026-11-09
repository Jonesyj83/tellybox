package com.tellybox.tv.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tellybox.tv.R

/**
 * A thin line under the channel banner. The whole width is what the buffer can hold; the
 * filled part, which ends at the right edge (live), is what has been buffered so far; the
 * marker is the picture on screen. It is the only sign of how far back the viewer can go.
 */
/**
 * Where the play head sits along the bar, as a fraction of the buffered window.
 *
 * The window is what the buffer actually holds, not what it could hold: scaling against the
 * capacity made a two-minute buffer occupy a sliver of the bar, so a thirty-second skip moved
 * the marker a few pixels and every arriving chunk twitched the buffered edge.
 */
internal fun markerFraction(windowMs: Long, behindMs: Long): Float {
    if (windowMs <= 0L) return 1f
    val held = windowMs - behindMs.coerceIn(0L, windowMs)
    return (held.toDouble() / windowMs.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * The span the bar is drawn against, in whole steps.
 *
 * Scaling to the exact buffer meant the scale changed on every tick, so the head sped up while
 * the buffer filled and slowed again later — which reads as the bar jumping about even when the
 * picture isn't. Snapping to a step keeps pixels-per-second constant for minutes at a time.
 */
internal fun scaleWindow(spanMs: Long): Long {
    val steps = longArrayOf(60_000, 120_000, 300_000, 600_000, 900_000, 1_200_000, 1_800_000)
    for (step in steps) if (spanMs <= step) return step
    return steps.last()
}

/**
 * Eases the drawn position towards the real one.
 *
 * The buffer's live edge advances when a flush lands, not every frame, so drawing the raw
 * number makes the head hop: it sits still, then jumps a second, then sits again. Easing it
 * removes the hop without adding lag anyone can see.
 */
internal fun settleShown(currentMs: Long, targetMs: Long, factor: Double = 0.35): Long {
    if (targetMs <= 0L) return 0L
    if (currentMs <= 0L) return targetMs
    val diff = targetMs - currentMs
    // Small differences are jitter and get eased; a real seek or a pause snaps, because easing
    // a jump just replays it slowly.
    if (kotlin.math.abs(diff) <= 250L) return targetMs
    if (kotlin.math.abs(diff) > 2_000L) return targetMs
    return currentMs + (diff * factor).toLong()
}

class TimeshiftBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.accent)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }
    private val track = Paint().apply { color = 0x33FFFFFF }
    private val buffered = Paint().apply { color = ContextCompat.getColor(context, R.color.accent_dim) }
    private val played = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val marker = Paint().apply { color = ContextCompat.getColor(context, R.color.text_primary) }

    private var totalMs = 0L
    private var bufferedMs = 0L
    private var behindMs = 0L
    /** True while the viewer is scrubbing: the marker is a hollow target, not the play head. */
    private var scrubbing = false

    /**
     * [totalMs]: how much the buffer can hold, [bufferedMs]: how much it holds now,
     * [behindMs]: how far the picture is behind live.
     */
    fun set(totalMs: Long, bufferedMs: Long, behindMs: Long, scrubbing: Boolean = false) {
        val total = maxOf(totalMs, bufferedMs, 1L)
        if (total == this.totalMs && bufferedMs == this.bufferedMs && behindMs == this.behindMs &&
            scrubbing == this.scrubbing) return
        this.totalMs = total
        this.bufferedMs = bufferedMs.coerceIn(0, total)
        this.behindMs = behindMs.coerceIn(0, this.bufferedMs)
        this.scrubbing = scrubbing
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, track)
        if (totalMs <= 0) return
        val start = w * (1f - bufferedMs.toFloat() / totalMs)
        // Keep the head inside the view, or at the live edge it is drawn off the right side
        // and disappears exactly when it matters.
        val edge = resources.displayMetrics.density * 2
        val at = (w * markerFraction(totalMs, behindMs)).coerceIn(edge, w - edge)
        canvas.drawRect(start, 0f, w, h, buffered)
        canvas.drawRect(start, 0f, at, h, played)
        val half = resources.displayMetrics.density * 2
        if (scrubbing) {
            // Where the picture will be once the seek lands: an outline, not a filled head.
            canvas.drawRoundRect((at - half).coerceAtLeast(0f), 0f, (at + half).coerceAtMost(w), h, 2f, 2f, focusRing)
        } else {
            canvas.drawRect((at - half).coerceAtLeast(0f), 0f, (at + half).coerceAtMost(w), h, marker)
        }
        if (isFocused) {
            val inset = focusRing.strokeWidth / 2f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 3f, 3f, focusRing)
        }
    }
}
