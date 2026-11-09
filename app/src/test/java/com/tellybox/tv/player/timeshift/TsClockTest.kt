package com.tellybox.tv.player.timeshift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsClockTest {
    private fun feedAll(clock: TsClock, data: ByteArray, chunk: Int, wall: (Long) -> Long = { 0L }): List<Pair<Long, Long>> {
        val seen = mutableListOf<Pair<Long, Long>>()
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            clock.feed(data, i, n, i.toLong(), wall(i.toLong())) { off, content -> seen += off to content }
            i += n
        }
        return seen
    }

    @Test
    fun `content time follows the pcr from the start of the session`() {
        val data = TsPackets.stream(seconds = 3, startPcrMs = 5_000_000)
        val seen = feedAll(TsClock(), data, data.size)
        assertEquals(0L, seen.first().second)
        assertEquals(2_900L, seen.last().second)
        // Every reported offset is the start of a packet.
        assertTrue(seen.all { it.first % TsClock.PACKET == 0L && data[it.first.toInt()] == TsClock.SYNC })
    }

    @Test
    fun `packets split across reads are still found`() {
        val data = TsPackets.stream(seconds = 2)
        val whole = feedAll(TsClock(), data, data.size)
        val split = feedAll(TsClock(), data, 100)
        assertEquals(whole, split)
    }

    @Test
    fun `resyncs after junk in the stream`() {
        val data = byteArrayOf(1, 2, 3, 0x47, 9) + TsPackets.stream(seconds = 1)
        val seen = feedAll(TsClock(), data, 4096)
        assertEquals(10, seen.size)
        assertEquals(5L, seen.first().first)
    }

    @Test
    fun `pcr wrap does not jump the timeline`() {
        val wrapMs = (1L shl 33) / 90
        val data = TsPackets.stream(seconds = 2, startPcrMs = wrapMs - 1_000)
        val seen = feedAll(TsClock(), data, data.size)
        val deltas = seen.zipWithNext { a, b -> b.second - a.second }
        assertTrue(deltas.toString(), deltas.all { it in 99L..101L })
    }

    @Test
    fun `an encoder restart falls back to wall-clock time`() {
        val clock = TsClock()
        val first = TsPackets.stream(seconds = 1, startPcrMs = 1_000_000)
        val second = TsPackets.stream(seconds = 1, startPcrMs = 10)
        var last = 0L
        clock.feed(first, 0, first.size, 0, 1_000) { _, c -> last = c }
        assertEquals(900L, last)
        // The second stream arrives two seconds later by the wall clock, with its PCR reset.
        clock.feed(second, 0, TsClock.PACKET, first.size.toLong(), 3_000) { _, c -> last = c }
        assertEquals(2_900L, last)
    }

    @Test
    fun `recognises ts and rejects a playlist`() {
        val ts = TsPackets.stream(seconds = 1)
        assertTrue(TsClock.looksLikeTs(ts, ts.size))
        val shifted = byteArrayOf(0, 0, 0) + ts
        assertTrue(TsClock.looksLikeTs(shifted, shifted.size))
        val m3u = "#EXTM3U\n#EXT-X-VERSION:3\n".repeat(40).toByteArray()
        assertFalse(TsClock.looksLikeTs(m3u, m3u.size))
    }
}
