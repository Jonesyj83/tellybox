package com.openstream.tv.ui.multiview

/** A tile's place on screen, as fractions of the screen's width and height. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where each multiview tile goes. Tiles never overlap, since video surfaces don't layer
 * reliably on TV hardware. The screen is assumed to be 16:9, so a tile that keeps the screen's
 * proportions keeps the video's too.
 */
object MultiviewLayout {
    const val MAX_TILES = 4
    private const val LARGE = 0.75f

    fun boxes(count: Int, enlarged: Int? = null): List<Box> {
        require(count in 1..MAX_TILES)
        if (count == 1) return listOf(Box(0f, 0f, 1f, 1f))
        if (enlarged != null && enlarged in 0 until count) return withLarge(count, enlarged)
        if (count == 2) return listOf(Box(0f, 0.25f, 0.5f, 0.75f), Box(0.5f, 0.25f, 1f, 0.75f))
        val quarters = listOf(
            Box(0f, 0f, 0.5f, 0.5f), Box(0.5f, 0f, 1f, 0.5f),
            Box(0f, 0.5f, 0.5f, 1f), Box(0.5f, 0.5f, 1f, 1f),
        )
        return quarters.take(count)
    }

    /** One big tile on the left, the rest stacked in a column on the right. */
    private fun withLarge(count: Int, large: Int): List<Box> {
        val small = 1f - LARGE
        val others = count - 1
        var top = (1f - others * small) / 2f
        return (0 until count).map { i ->
            if (i == large) {
                Box(0f, (1f - LARGE) / 2f, LARGE, (1f + LARGE) / 2f)
            } else {
                Box(LARGE, top, 1f, top + small).also { top += small }
            }
        }
    }
}

/** Whether opening one more stream from a provider would go past what it allows. */
object ConnectionBudget {
    /**
     * [inUse] counts streams this app already has open on the same playlist (tiles and running
     * recordings). Returns the provider's limit when one more would exceed it, otherwise null.
     * Providers that don't publish a limit are not second-guessed.
     */
    fun exceededLimit(maxConnections: Int?, inUse: Int): Int? =
        maxConnections?.takeIf { it > 0 && inUse + 1 > it }
}
