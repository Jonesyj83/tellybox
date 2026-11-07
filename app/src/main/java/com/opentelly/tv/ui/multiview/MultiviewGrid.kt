package com.opentelly.tv.ui.multiview

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Holds the multiview tiles and places them from [MultiviewLayout.tiles] in pixels. It
 * ignores the children's layout params on purpose: screen scaling rewrites those for every
 * new view, which is what used to push the second tile past the right edge.
 */
class MultiviewGrid(context: Context) : ViewGroup(context) {

    var enlarged: Int? = null
        set(value) {
            field = value
            requestLayout()
        }
    var margin = 0
    var gap = 0

    /** Where each tile went in the last layout, for picking label sizes. */
    var rects: List<TileRect> = emptyList()
        private set

    private fun tiles(): List<View> = (0 until childCount).map { getChildAt(it) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val children = tiles()
        rects = if (children.isEmpty()) emptyList()
        else MultiviewLayout.tiles(children.size, w, h, margin, gap, enlarged?.takeIf { it < children.size })
        children.forEachIndexed { i, child ->
            val r = rects[i]
            child.measure(
                MeasureSpec.makeMeasureSpec(r.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        tiles().forEachIndexed { i, child ->
            val rect = rects.getOrNull(i) ?: return@forEachIndexed
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }
}
