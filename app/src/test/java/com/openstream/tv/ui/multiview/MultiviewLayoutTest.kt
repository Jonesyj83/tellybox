package com.openstream.tv.ui.multiview

import com.openstream.tv.player.PipAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MultiviewLayoutTest {

    private val margin = 16
    private val gap = 8
    /** Content areas to the right of the rail: Shield 1080p, 720p boxes, odd sizes. */
    private val areas = listOf(1803 to 1080, 1854 to 1080, 1194 to 720, 1237 to 702, 901 to 540, 1000 to 1000, 1600 to 500)

    private fun overlaps(a: TileRect, b: TileRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun gapBetween(a: TileRect, b: TileRect): Int {
        val dx = maxOf(b.left - a.right, a.left - b.right)
        val dy = maxOf(b.top - a.bottom, a.top - b.bottom)
        return maxOf(dx, dy)
    }

    private fun assertSixteenByNine(t: TileRect) {
        // Integer pixels: within one pixel of an exact 16:9.
        assertTrue("$t is not 16:9", abs(t.width * 9 - t.height * 16) <= 16)
    }

    private fun checkLayout(count: Int, w: Int, h: Int, enlarged: Int?) {
        val tiles = MultiviewLayout.tiles(count, w, h, margin, gap, enlarged)
        val where = "count=$count area=${w}x$h enlarged=$enlarged"
        assertEquals(where, count, tiles.size)
        tiles.forEach { t ->
            assertTrue("$where: $t past the left/top edge", t.left >= margin && t.top >= margin)
            assertTrue("$where: $t past the right edge", t.right <= w - margin)
            assertTrue("$where: $t past the bottom edge", t.bottom <= h - margin)
            assertTrue("$where: $t is empty", t.width > 0 && t.height > 0)
            assertSixteenByNine(t)
        }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            assertFalse("$where: tiles $i and $j overlap", overlaps(tiles[i], tiles[j]))
        }
        // The group is centred: equal outer margins left/right and top/bottom.
        val left = tiles.minOf { it.left }
        val right = w - tiles.maxOf { it.right }
        val top = tiles.minOf { it.top }
        val bottom = h - tiles.maxOf { it.bottom }
        assertTrue("$where: left $left vs right $right", abs(left - right) <= 1)
        assertTrue("$where: top $top vs bottom $bottom", abs(top - bottom) <= 1)
    }

    @Test fun everyCountStaysInsideTheAreaWithEqualMargins() {
        for ((w, h) in areas) for (count in 1..MultiviewLayout.MAX_TILES) {
            checkLayout(count, w, h, null)
            for (enlarged in 0 until count) checkLayout(count, w, h, enlarged)
        }
    }

    @Test fun twoScreensAreEqualColumnsOneGapApart() {
        for ((w, h) in areas) {
            val (a, b) = MultiviewLayout.tiles(2, w, h, margin, gap)
            assertEquals(a.width, b.width)
            assertEquals(a.top, b.top)
            assertEquals(gap, b.left - a.right)
        }
        // On a wide area the pair spans the width: no stray gap, nothing past the edge.
        val (a, b) = MultiviewLayout.tiles(2, 1803, 1080, margin, gap)
        assertEquals(margin, a.left)
        assertTrue(1803 - margin - b.right <= 1)
    }

    @Test fun threeScreensAreTwoAboveAndOneCentredBelow() {
        for ((w, h) in areas) {
            val (a, b, c) = MultiviewLayout.tiles(3, w, h, margin, gap)
            assertEquals(a.top, b.top)
            assertEquals(gap, b.left - a.right)
            assertEquals(gap, c.top - a.bottom)
            assertEquals(a.width, c.width)
            val rowCentre = (a.left + b.right) / 2f
            val centre = (c.left + c.right) / 2f
            assertTrue("bottom tile not centred in ${w}x$h", abs(rowCentre - centre) <= 1f)
        }
    }

    @Test fun fourScreensAreTwoByTwo() {
        for ((w, h) in areas) {
            val t = MultiviewLayout.tiles(4, w, h, margin, gap)
            assertEquals(t[0].top, t[1].top)
            assertEquals(t[2].top, t[3].top)
            assertEquals(t[0].left, t[2].left)
            assertEquals(t[1].left, t[3].left)
            assertEquals(gap, t[1].left - t[0].right)
            assertEquals(gap, t[2].top - t[0].bottom)
            assertEquals(1, t.map { it.width }.distinct().size)
        }
    }

    @Test fun neighboursAreAtLeastOneGapApart() {
        for ((w, h) in areas) for (count in 2..MultiviewLayout.MAX_TILES) {
            for (enlarged in listOf<Int?>(null) + (0 until count)) {
                val tiles = MultiviewLayout.tiles(count, w, h, margin, gap, enlarged)
                for (i in tiles.indices) for (j in i + 1 until tiles.size) {
                    assertTrue("count=$count enlarged=$enlarged", gapBetween(tiles[i], tiles[j]) >= gap)
                }
            }
        }
    }

    @Test fun enlargedTileIsTheBiggest() {
        for (count in 2..MultiviewLayout.MAX_TILES) for (enlarged in 0 until count) {
            val tiles = MultiviewLayout.tiles(count, 1803, 1080, margin, gap, enlarged)
            val areas = tiles.map { it.width * it.height }
            assertEquals(enlarged, areas.indexOf(areas.max()))
        }
    }

    @Test fun anEmptyAreaGivesEmptyTilesRatherThanCrashing() {
        assertEquals(List(3) { TileRect(0, 0, 0, 0) }, MultiviewLayout.tiles(3, 0, 0, margin, gap))
    }

    @Test fun slotsFillInOrderAndReportWhatIsLeft() {
        val plan = SlotPlan(3, first = 10L)
        assertEquals(1, plan.firstEmpty())
        assertEquals(listOf(1, 2), plan.emptySlots())
        plan.fill(1, 11L)
        plan.fill(2, 12L)
        assertTrue(plan.complete)
        assertNull(plan.firstEmpty())
        plan.fill(1, 20L)
        assertEquals(listOf<Long?>(10L, 20L, 12L), plan.channels)
        plan.clear(2)
        assertEquals(2, plan.firstEmpty())
    }

    @Test fun connectionBudgetWarnsOnlyPastAKnownLimit() {
        assertNull(ConnectionBudget.exceededLimit(null, 3))
        assertNull(ConnectionBudget.exceededLimit(2, 1))
        assertEquals(2, ConnectionBudget.exceededLimit(2, 2))
        assertEquals(1, ConnectionBudget.exceededLimit(1, 1))
    }

    @Test fun pipAspectFollowsVideoWithinSystemLimits() {
        assertEquals(16 to 9, PipAspect.of(0, 0))
        val (w, h) = PipAspect.of(1920, 1080)
        assertEquals(16f / 9f, w.toFloat() / h, 0.01f)
        val (cw, ch) = PipAspect.of(4000, 1000)
        assert(cw.toFloat() / ch <= 2.39f)
        // Anamorphic 720x576 with wide pixels is 16:9 on screen.
        val (aw, ah) = PipAspect.of(720, 576, 64f / 45f)
        assertEquals(16f / 9f, aw.toFloat() / ah, 0.01f)
    }
}
