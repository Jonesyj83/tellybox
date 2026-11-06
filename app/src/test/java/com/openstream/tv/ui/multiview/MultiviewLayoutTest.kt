package com.openstream.tv.ui.multiview

import com.openstream.tv.player.PipAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MultiviewLayoutTest {

    private fun overlaps(a: Box, b: Box) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun assertNoOverlap(boxes: List<Box>) {
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            assertFalse("tiles $i and $j overlap", overlaps(boxes[i], boxes[j]))
        }
    }

    private fun Box.aspect() = (right - left) * 16f / ((bottom - top) * 9f)

    @Test fun twoTilesSitSideBySideAtVideoProportions() {
        val boxes = MultiviewLayout.boxes(2)
        assertEquals(2, boxes.size)
        assertNoOverlap(boxes)
        boxes.forEach { assertEquals(16f / 9f, it.aspect(), 0.001f) }
    }

    @Test fun everyCountAndEnlargedChoiceStaysOnScreenWithoutOverlap() {
        for (count in 1..MultiviewLayout.MAX_TILES) {
            for (enlarged in listOf<Int?>(null) + (0 until count)) {
                val boxes = MultiviewLayout.boxes(count, enlarged)
                assertEquals(count, boxes.size)
                assertNoOverlap(boxes)
                boxes.forEach {
                    assert(it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f)
                    assertEquals(16f / 9f, it.aspect(), 0.001f)
                }
            }
        }
    }

    @Test fun enlargedTileIsTheBiggest() {
        val boxes = MultiviewLayout.boxes(3, enlarged = 2)
        val areas = boxes.map { (it.right - it.left) * (it.bottom - it.top) }
        assertEquals(2, areas.indexOf(areas.max()))
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
