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

    /**
     * [totalMs]: how much the buffer can hold, [bufferedMs]: how much it holds now,
     * [behindMs]: how far the picture is behind live.
     */
    fun set(totalMs: Long, bufferedMs: Long, behindMs: Long) {
        val total = maxOf(totalMs, bufferedMs, 1L)
        if (total == this.totalMs && bufferedMs == this.bufferedMs && behindMs == this.behindMs) return
        this.totalMs = total
        this.bufferedMs = bufferedMs.coerceIn(0, total)
        this.behindMs = behindMs.coerceIn(0, this.bufferedMs)
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
        val at = w * (1f - behindMs.toFloat() / totalMs)
        canvas.drawRect(start, 0f, w, h, buffered)
        canvas.drawRect(start, 0f, at, h, played)
        val half = resources.displayMetrics.density * 2
        canvas.drawRect((at - half).coerceAtLeast(0f), 0f, (at + half).coerceAtMost(w), h, marker)
        if (isFocused) {
            val inset = focusRing.strokeWidth / 2f
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, 3f, 3f, focusRing)
        }
    }
}
