package com.tellybox.tv.ui.epg

import com.tellybox.tv.data.db.EpgProgramEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideBlocksTest {
    private val min = 60_000L
    private val base = 1_700_000_000_000L - Math.floorMod(1_700_000_000_000L, GuideBlocks.SLOT)

    private fun program(startMin: Int, stopMin: Int, title: String) =
        EpgProgramEntity(sourceId = 1, channelXmltvId = "a", start = base + startMin * min, stop = base + stopMin * min, title = title)

    @Test
    fun `channel with no programmes gets one no-data strip`() {
        val blocks = GuideBlocks.build(emptyList(), base, base + 120 * min)
        assertEquals(1, blocks.size)
        val strip = blocks.single()
        assertEquals(GuideBlock.Strip.NO_DATA, strip.strip)
        assertNull(strip.program)
        assertTrue(strip.start < base && strip.stop > base + 120 * min)
    }

    @Test
    fun `not loaded yet is a blank strip, not no-data`() {
        assertEquals(GuideBlock.Strip.LOADING, GuideBlocks.build(null, base, base + 60 * min).single().strip)
    }

    @Test
    fun `strip bounds survive pixel maths without overflow`() {
        val s = GuideBlocks.build(emptyList(), base, base + 60 * min).single()
        // The grid clamps to the view and subtracts the view start; neither may wrap.
        assertTrue(maxOf(s.start, base) - base == 0L)
        assertTrue(minOf(s.stop, base + 60 * min) - base == 60 * min)
        assertTrue(s.start - 12 * 3_600_000L < s.start)
        assertTrue(s.stop + 30 * min > s.stop)
    }

    @Test
    fun `holes between programmes are filled with half-hour placeholders`() {
        val blocks = GuideBlocks.build(listOf(program(0, 30, "A"), program(75, 120, "B")), base, base + 120 * min)
        assertEquals(listOf("A", null, null, "B"), blocks.map { it.program?.title })
        assertEquals(base + 30 * min, blocks[1].start)
        assertEquals(base + 60 * min, blocks[1].stop)
        assertEquals(base + 60 * min, blocks[2].start)
        assertEquals(base + 75 * min, blocks[2].stop)
        assertTrue(blocks.all { it.strip == null })
    }

    @Test
    fun `range past the last programme is padded to the end`() {
        val blocks = GuideBlocks.build(listOf(program(0, 30, "A")), base, base + 90 * min)
        assertEquals(3, blocks.size)
        assertEquals(base + 90 * min, blocks.last().stop)
        assertNull(blocks.last().program)
    }

    @Test
    fun `programmes outside the range are skipped`() {
        val blocks = GuideBlocks.build(
            listOf(program(-120, -60, "old"), program(0, 60, "now"), program(300, 360, "later")),
            base, base + 60 * min,
        )
        assertEquals(listOf("now"), blocks.map { it.program?.title })
    }
}
