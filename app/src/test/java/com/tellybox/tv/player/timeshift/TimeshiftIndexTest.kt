package com.tellybox.tv.player.timeshift

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeshiftIndexTest {
    /** One entry per second: offset 1000 * s, content 1000 * s ms, arriving at 1000 * s. */
    private fun filled(seconds: Int) = TimeshiftIndex().apply {
        for (s in 0..seconds) add(s * 1000L, s * 1000L, s * 1000L)
    }

    @Test
    fun `entries closer than the spacing are skipped`() {
        val index = TimeshiftIndex(spacingMs = 500)
        index.add(0, 0, 0)
        index.add(100, 0, 100)
        index.add(200, 0, 600)
        assertEquals(2, index.size)
    }

    @Test
    fun `rewinding maps to the entry at or just before the target`() {
        val index = filled(60)
        assertEquals(30_000L, index.offsetFor(30_000))
        assertEquals(30_000L, index.offsetFor(30_999))
        // Asked for something older than the buffer holds: the oldest there is.
        assertEquals(0L, index.offsetFor(-5_000))
        assertEquals(60_000L, index.offsetFor(99_000))
    }

    @Test
    fun `content at an offset is the next entry from there`() {
        val index = filled(10)
        assertEquals(3_000L, index.contentAt(2_500))
        assertEquals(3_000L, index.contentAt(3_000))
        assertEquals(10_000L, index.contentAt(50_000))
    }

    @Test
    fun `overwritten entries are dropped and the rest still found`() {
        val index = filled(5_000)
        index.dropBefore(4_000_000)
        assertEquals(4_000_000L, index.firstOffset)
        assertEquals(4_000_000L, index.offsetFor(0))
        for (s in 5_001..6_000) index.add(s * 1000L, s * 1000L, s * 1000L)
        assertEquals(5_500_000L, index.offsetFor(5_500_000))
        assertEquals(2_001, index.size)
    }

    @Test
    fun `byte rate comes from recent arrivals`() {
        val index = filled(60)
        // 1000 bytes a second, measured over the last 30 seconds.
        assertEquals(1000L, index.byteRate(headOffset = 60_000, nowMs = 60_000))
        assertEquals(0L, TimeshiftIndex().byteRate(0, 0))
    }
}
