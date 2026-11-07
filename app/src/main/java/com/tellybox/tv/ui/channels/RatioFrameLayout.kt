package com.tellybox.tv.ui.channels

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A frame whose height follows its width: 16:9 by default (the preview window), or any
 * [ratio] of height to width (2:3 posters use 1.5). Sizing here, not in layout params,
 * keeps it right however the screen is scaled.
 */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var ratio: Float = 9f / 16f
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = kotlin.math.round(width * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
