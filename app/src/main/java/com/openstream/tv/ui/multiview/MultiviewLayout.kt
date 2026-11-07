package com.openstream.tv.ui.multiview

import kotlin.math.min
import kotlin.math.roundToInt

/** A tile's place in pixels inside the multiview area; right and bottom are exclusive. */
data class TileRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Where each multiview tile goes inside a [width] x [height] area. Every tile is 16:9, so the
 * picture fills it and the focus border hugs the picture; the group of tiles is centred, so
 * the outer margins are equal (never less than [margin]) and tiles sit [gap] apart.
 *
 * - 1: one tile
 * - 2: two equal tiles side by side
 * - 3: two on the top row, one centred below
 * - 4: 2 x 2
 * - with [enlarged]: that tile large on the left, the others stacked on the right
 *
 * Pure arithmetic on pixels so it can be tested, and so nothing else (layout params, screen
 * scaling) can move a tile off the edge.
 */
object MultiviewLayout {
    const val MAX_TILES = 4
    val COUNTS = listOf(2, 3, 4)

    fun tiles(count: Int, width: Int, height: Int, margin: Int, gap: Int, enlarged: Int? = null): List<TileRect> {
        require(count in 1..MAX_TILES)
        val innerW = (width - 2 * margin).coerceAtLeast(0)
        val innerH = (height - 2 * margin).coerceAtLeast(0)
        if (innerW == 0 || innerH == 0) return List(count) { TileRect(0, 0, 0, 0) }
        if (count > 1 && enlarged != null && enlarged in 0 until count) {
            return withLarge(count, enlarged, width, height, innerW, innerH, gap)
        }
        val columns = if (count == 1) 1 else 2
        val rows = if (count <= 2) 1 else 2
        val tileW = fit16x9(
            (innerW - (columns - 1) * gap) / columns,
            (innerH - (rows - 1) * gap) / rows,
        )
        val tileH = heightOf(tileW)
        val blockW = columns * tileW + (columns - 1) * gap
        val blockH = rows * tileH + (rows - 1) * gap
        val x0 = (width - blockW) / 2
        val y0 = (height - blockH) / 2
        return (0 until count).map { i ->
            val row = i / columns
            val inRow = if (row == rows - 1) count - row * columns else columns
            // A short last row (three screens) is centred under the full one.
            val rowX = if (inRow < columns) (width - (inRow * tileW + (inRow - 1) * gap)) / 2 else x0
            val left = rowX + (i % columns) * (tileW + gap)
            val top = y0 + row * (tileH + gap)
            TileRect(left, top, left + tileW, top + tileH)
        }
    }

    /** One large tile, the rest in a column on its right, the column as tall as the large tile. */
    private fun withLarge(count: Int, large: Int, width: Int, height: Int, innerW: Int, innerH: Int, gap: Int): List<TileRect> {
        // Two screens still get a small tile: half the column, centred.
        val slots = maxOf(count - 1, 2)
        // big = slots * small + (slots - 1) gaps (in height), and big + gap + small = innerW.
        val gapAsWidth = (slots - 1) * gap * 16f / 9f
        var smallW = (innerW - gap - gapAsWidth) / (slots + 1)
        var bigW = slots * smallW + gapAsWidth
        if (heightOf(bigW.roundToInt()) > innerH) {
            bigW = innerH * 16f / 9f
            smallW = (bigW - gapAsWidth) / slots
        }
        val big = bigW.toInt()
        val bigH = heightOf(big)
        val small = smallW.toInt()
        val smallH = heightOf(small)
        val blockW = big + gap + small
        val x0 = (width - blockW) / 2
        val y0 = (height - bigH) / 2
        val others = count - 1
        val columnH = others * smallH + (others - 1) * gap
        var top = y0 + (bigH - columnH) / 2
        return (0 until count).map { i ->
            if (i == large) {
                TileRect(x0, y0, x0 + big, y0 + bigH)
            } else {
                val left = x0 + big + gap
                TileRect(left, top, left + small, top + smallH).also { top += smallH + gap }
            }
        }
    }

    private fun heightOf(width: Int) = (width * 9f / 16f).toInt()

    /** The widest 16:9 width that fits a cell. */
    private fun fit16x9(cellW: Int, cellH: Int): Int = min(cellW, (cellH * 16f / 9f).toInt()).coerceAtLeast(0)
}

/**
 * Which channel goes on each screen while multiview is being set up. Slots fill in order;
 * a screen can be refilled to change its channel.
 */
class SlotPlan(count: Int, first: Long? = null) {
    private val slots: MutableList<Long?> = MutableList(count) { null }

    init {
        require(count in 1..MultiviewLayout.MAX_TILES)
        if (first != null) slots[0] = first
    }

    val count get() = slots.size
    val channels: List<Long?> get() = slots.toList()
    val complete get() = slots.none { it == null }
    fun firstEmpty(): Int? = slots.indexOfFirst { it == null }.takeIf { it >= 0 }
    fun emptySlots(): List<Int> = slots.indices.filter { slots[it] == null }
    fun fill(slot: Int, channelId: Long) { slots[slot] = channelId }
    fun clear(slot: Int) { slots[slot] = null }
    operator fun get(slot: Int) = slots[slot]
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
