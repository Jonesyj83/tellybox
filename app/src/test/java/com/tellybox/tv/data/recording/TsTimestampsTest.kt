package com.tellybox.tv.data.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class TsTimestampsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val mask = (1L shl 33) - 1
    private val frame = 3_600L // 40 ms at 90 kHz
    private val pcrLead = 45_000L // PCR runs half a second ahead of the PTS

    // --- building synthetic TS --------------------------------------------------------------

    /** A video packet: adaptation field carrying a PCR, then a PES header with PTS and DTS. */
    private fun videoPacket(pts: Long, cc: Int): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = 0x41 // PUSI, PID 0x100
        p[2] = 0x00
        p[3] = (0x30 or (cc and 0x0F)).toByte() // adaptation field + payload
        p[4] = 7 // adaptation field length
        p[5] = 0x10 // PCR flag
        writePcr(p, 6, (pts - pcrLead) and mask, ext = 123)
        val pes = 12
        p[pes] = 0; p[pes + 1] = 0; p[pes + 2] = 1; p[pes + 3] = 0xE0.toByte()
        p[pes + 4] = 0; p[pes + 5] = 0
        p[pes + 6] = 0x80.toByte()
        p[pes + 7] = 0xC0.toByte() // PTS and DTS
        p[pes + 8] = 10
        writeTs(p, pes + 9, 0x3, pts and mask)
        writeTs(p, pes + 14, 0x1, (pts - frame) and mask)
        return p
    }

    /** An audio packet: no adaptation field, PES header with a PTS only. */
    private fun audioPacket(pts: Long, cc: Int): ByteArray {
        val p = ByteArray(188) { 0x55 }
        p[0] = 0x47
        p[1] = 0x41 // PUSI, PID 0x101
        p[2] = 0x01
        p[3] = (0x10 or (cc and 0x0F)).toByte()
        p[4] = 0; p[5] = 0; p[6] = 1; p[7] = 0xC0.toByte()
        p[8] = 0; p[9] = 0
        p[10] = 0x80.toByte()
        p[11] = 0x80.toByte() // PTS only
        p[12] = 5
        writeTs(p, 13, 0x2, pts and mask)
        return p
    }

    private fun writeTs(b: ByteArray, at: Int, prefix: Int, v: Long) {
        b[at] = ((prefix shl 4) or ((v shr 30 and 7).toInt() shl 1) or 1).toByte()
        b[at + 1] = (v shr 22 and 0xFF).toByte()
        b[at + 2] = (((v shr 15 and 0x7F).toInt() shl 1) or 1).toByte()
        b[at + 3] = (v shr 7 and 0xFF).toByte()
        b[at + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun writePcr(b: ByteArray, at: Int, base: Long, ext: Int) {
        b[at] = (base shr 25 and 0xFF).toByte()
        b[at + 1] = (base shr 17 and 0xFF).toByte()
        b[at + 2] = (base shr 9 and 0xFF).toByte()
        b[at + 3] = (base shr 1 and 0xFF).toByte()
        b[at + 4] = (((base and 1).toInt() shl 7) or 0x7E or (ext shr 8 and 1)).toByte()
        b[at + 5] = (ext and 0xFF).toByte()
    }

    /** Video and audio frames at the given input PTS values, interleaved. */
    private fun stream(ptsValues: List<Long>): ByteArray {
        val out = ByteArrayOutputStream()
        ptsValues.forEachIndexed { i, pts ->
            out.write(videoPacket(pts, i))
            out.write(audioPacket(pts + 900, i))
        }
        return out.toByteArray()
    }

    private fun frames(start: Long, count: Int) = List(count) { start + it * frame }

    private fun file(bytes: ByteArray) = tmp.newFile("rec.ts").apply { writeBytes(bytes) }

    // --- reading it back --------------------------------------------------------------------

    private data class Stamps(val videoPts: List<Long>, val dts: List<Long>, val audioPts: List<Long>, val pcr: List<Long>)

    private fun readTs(b: ByteArray, at: Int): Long =
        ((b[at].toLong() shr 1 and 7) shl 30) or ((b[at + 1].toLong() and 0xFF) shl 22) or
            ((b[at + 2].toLong() shr 1 and 0x7F) shl 15) or ((b[at + 3].toLong() and 0xFF) shl 7) or
            (b[at + 4].toLong() shr 1 and 0x7F)

    private fun stamps(bytes: ByteArray): Stamps {
        val v = ArrayList<Long>(); val d = ArrayList<Long>(); val a = ArrayList<Long>(); val c = ArrayList<Long>()
        var p = 0
        while (p + 188 <= bytes.size) {
            assertEquals(0x47.toByte(), bytes[p])
            if (bytes[p + 2] == 0x00.toByte()) {
                c += ((bytes[p + 6].toLong() and 0xFF) shl 25) or ((bytes[p + 7].toLong() and 0xFF) shl 17) or
                    ((bytes[p + 8].toLong() and 0xFF) shl 9) or ((bytes[p + 9].toLong() and 0xFF) shl 1) or
                    (bytes[p + 10].toLong() shr 7 and 1)
                // Marker bits, the PTS/DTS prefixes and the PCR extension are left as they were.
                assertEquals(0x3, bytes[p + 21].toInt() shr 4 and 0xF)
                assertEquals(0x1, bytes[p + 26].toInt() shr 4 and 0xF)
                assertEquals(1, bytes[p + 21].toInt() and 1)
                assertEquals(1, bytes[p + 23].toInt() and 1)
                assertEquals(1, bytes[p + 25].toInt() and 1)
                assertEquals(123, ((bytes[p + 10].toInt() and 1) shl 8) or (bytes[p + 11].toInt() and 0xFF))
                assertEquals(0x7E, bytes[p + 10].toInt() and 0x7E)
                v += readTs(bytes, p + 21)
                d += readTs(bytes, p + 26)
            } else {
                assertEquals(0x2, bytes[p + 13].toInt() shr 4 and 0xF)
                a += readTs(bytes, p + 13)
            }
            p += 188
        }
        return Stamps(v, d, a, c)
    }

    /** Signed step from [b] to [a] on the 33 bit clock. */
    private fun step(a: Long, b: Long): Long {
        val d = (a - b) and mask
        return if (d >= (1L shl 32)) d - (1L shl 33) else d
    }

    /** Every step is forward and no bigger than a normal frame plus the 40 ms gap. */
    private fun assertContinuous(values: List<Long>) {
        for (i in 1 until values.size) {
            val s = step(values[i], values[i - 1])
            assertTrue("step $s at $i", s in 1..frame + TsTimestamps.GAP_TICKS)
        }
    }

    private fun span(values: List<Long>) = values.zipWithNext().sumOf { (x, y) -> step(y, x) }

    // --- tests ------------------------------------------------------------------------------

    @Test
    fun `a ten second jump comes out continuous with the span of the content`() {
        val first = frames(13_260L * 90_000, 125)
        val second = frames(first.last() + frame + 10 * 90_000, 125)
        val f = file(stream(first + second))

        assertTrue(TsTimestamps.fix(f))
        assertFalse(File(f.path + ".tmp").exists())

        val s = stamps(f.readBytes())
        listOf(s.videoPts, s.dts, s.audioPts, s.pcr).forEach(::assertContinuous)
        // The shifted frame lands exactly one frame after the last, so nothing is lost or added.
        assertEquals(249 * frame, span(s.videoPts))
        assertEquals(249 * frame, span(s.pcr))
        assertEquals(first.first(), s.videoPts.first())
        assertEquals(s.videoPts.map { (it - frame) and mask }, s.dts)
    }

    @Test
    fun `the real 22 hour jump is taken out`() {
        val first = frames(1_193_475_600L, 1000)
        val second = frames(8_615_174_400L and mask, 1250)
        val f = file(stream(first + second))

        assertTrue(TsTimestamps.fix(f))
        val s = stamps(f.readBytes())
        assertContinuous(s.videoPts)
        assertContinuous(s.pcr)
        assertEquals(2249 * frame, span(s.videoPts))
    }

    @Test
    fun `a jump backwards is taken out too`() {
        val first = frames(50_000L * 90_000, 100)
        val second = frames(1_000L * 90_000, 100)
        val f = file(stream(first + second))

        assertTrue(TsTimestamps.fix(f))
        val s = stamps(f.readBytes())
        assertContinuous(s.videoPts)
        assertContinuous(s.pcr)
    }

    @Test
    fun `a continuous file is left byte for byte`() {
        val bytes = stream(frames(13_260L * 90_000, 500))
        val f = file(bytes)
        val modified = f.lastModified()

        assertFalse(TsTimestamps.fix(f))
        assertArrayEquals(bytes, f.readBytes())
        assertEquals(modified, f.lastModified())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun `small wobbles under two seconds are left alone`() {
        val values = frames(90_000L, 100).toMutableList()
        values[50] += 150_000 // 1.7 s off, then back
        val bytes = stream(values)
        val f = file(bytes)

        assertFalse(TsTimestamps.fix(f))
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test
    fun `a clock that wraps past 2^33 is not mistaken for a jump`() {
        val bytes = stream(frames(mask - 100 * frame, 250).map { it and mask })
        val f = file(bytes)

        assertFalse(TsTimestamps.fix(f))
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test
    fun `a jump either side of the 2^33 wrap still comes out monotonic`() {
        val first = frames(mask - 100 * frame, 200).map { it and mask } // wraps halfway through
        val second = frames(first.last() + frame + 30 * 90_000, 200).map { it and mask }
        // And a jump that lands the clock just short of the wrap, which it then crosses.
        val third = frames(mask - 50 * frame, 200).map { it and mask }
        val f = file(stream(first + second + third))

        assertTrue(TsTimestamps.fix(f))
        val s = stamps(f.readBytes())
        listOf(s.videoPts, s.dts, s.audioPts, s.pcr).forEach(::assertContinuous)
        assertTrue(s.videoPts.all { it in 0..mask })
        assertEquals(599 * frame, span(s.videoPts))
    }

    @Test
    fun `a jump past the first chunk is still found`() {
        // About 11 MB, so the file is read in several pieces and the jump is in a later one.
        val first = frames(90_000L, 25_000)
        val second = frames(first.last() + frame + 600 * 90_000, 5_000)
        val f = file(stream(first + second))

        assertTrue(TsTimestamps.fix(f))
        val s = stamps(f.readBytes())
        assertContinuous(s.videoPts)
        assertContinuous(s.pcr)
        assertEquals(29_999 * frame, span(s.videoPts))
    }

    @Test
    fun `a torn packet in the middle is passed through and sync picks up after it`() {
        val first = stream(frames(90_000L, 100))
        val second = stream(frames(90_000L + 100 * frame + 20 * 90_000, 100))
        val torn = first.copyOfRange(0, 70) // the start of a packet that never finished
        val f = file(first + torn + second)

        assertTrue(TsTimestamps.fix(f))
        val out = f.readBytes()
        assertArrayEquals(torn, out.copyOfRange(first.size, first.size + torn.size))
        val s = stamps(out.copyOfRange(0, first.size) + out.copyOfRange(first.size + torn.size, out.size))
        assertContinuous(s.videoPts)
        assertContinuous(s.pcr)
    }

    @Test
    fun `a file that is not TS is left alone`() {
        val bytes = ByteArray(10_000) { (it * 31 + 7).toByte() }.also { it[0] = 0x00; it[1] = 0x00; it[2] = 0x00; it[3] = 0x18 }
        for (i in bytes.indices) if (bytes[i] == 0x47.toByte()) bytes[i] = 0x46
        val f = file(bytes)

        assertFalse(TsTimestamps.fix(f))
        assertArrayEquals(bytes, f.readBytes())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun `an empty file does not throw`() {
        val f = file(ByteArray(0))
        assertFalse(TsTimestamps.fix(f))
        assertEquals(0L, f.length())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun `a missing file does not throw`() {
        assertFalse(TsTimestamps.fix(File(tmp.root, "gone.ts")))
    }

    @Test
    fun `a truncated final packet does not throw and is kept`() {
        val first = frames(90_000L, 50)
        val second = frames(first.last() + frame + 10 * 90_000, 50)
        val whole = stream(first + second)
        val cut = whole.copyOf(whole.size - 100)
        val f = file(cut)

        assertTrue(TsTimestamps.fix(f))
        val out = f.readBytes()
        assertEquals(cut.size, out.size)
        // The torn tail goes through untouched.
        assertArrayEquals(cut.copyOfRange(cut.size - 88, cut.size), out.copyOfRange(out.size - 88, out.size))
        assertContinuous(stamps(out).videoPts)
    }

    @Test
    fun `a truncated final packet on a continuous file is left alone`() {
        val whole = stream(frames(90_000L, 50))
        val cut = whole.copyOf(whole.size - 100)
        val f = file(cut)

        assertFalse(TsTimestamps.fix(f))
        assertArrayEquals(cut, f.readBytes())
    }

    @Test
    fun `a file shorter than one packet does not throw`() {
        val f = file(byteArrayOf(0x47, 0x40, 0x00, 0x10))
        assertFalse(TsTimestamps.fix(f))
        assertEquals(4L, f.length())
    }
}
