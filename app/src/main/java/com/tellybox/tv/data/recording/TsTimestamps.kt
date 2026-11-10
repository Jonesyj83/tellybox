package com.tellybox.tv.data.recording

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Makes the timestamps in a finished MPEG-TS recording run continuously.
 *
 * Recordings are HLS segments laid end to end, and some providers splice in segments whose
 * clock is somewhere else entirely (ads, encoder restarts). The player works the length out
 * from the first and last timestamps, so one jump turns a 90 second recording into 22 hours.
 * This walks the file packet by packet and shifts every timestamp after a jump so the clock
 * carries on from where it was. A file without jumps is left exactly as it is.
 */
object TsTimestamps {

    private const val PACKET = 188
    private const val SYNC = 0x47.toByte()
    private const val CHUNK = 4 * 1024 * 1024
    private const val MASK = (1L shl 33) - 1
    private const val HALF = 1L shl 32
    /** Deviations smaller than this are normal (reordering, audio against video) and left alone. */
    const val JUMP_TICKS = 180_000L
    /** Where a shifted timestamp lands after the one before it: about one frame, 40 ms. */
    const val GAP_TICKS = 3_600L

    /** Rewrites PTS/DTS/PCR in [file] so it runs continuously. Returns true if it changed. */
    fun fix(file: File): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            val changed = rewrite(file, tmp)
            if (!changed || !file.exists()) {
                // Unchanged, or deleted while we were reading it: don't bring it back.
                tmp.delete()
                false
            } else if (tmp.renameTo(file)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    /** Writes the corrected copy to [tmp]. Returns false (and [tmp] is junk) if nothing needed shifting. */
    private fun rewrite(src: File, tmp: File): Boolean {
        if (!src.isFile || src.length() < PACKET) return false
        val shifter = Shifter()
        FileInputStream(src).use { input ->
            FileOutputStream(tmp).use { output ->
                val buf = ByteArray(CHUNK + PACKET * 2)
                var len = 0
                var eof = false
                var checked = false
                while (!eof || len > 0) {
                    while (!eof && len < CHUNK) {
                        val n = input.read(buf, len, CHUNK - len)
                        if (n < 0) eof = true else len += n
                    }
                    if (!checked) {
                        val start = findSync(buf, len) ?: return false
                        checked = true
                        // Anything before the first packet goes through as it is.
                        output.write(buf, 0, start)
                        System.arraycopy(buf, start, buf, 0, len - start)
                        len -= start
                    }
                    var pos = 0
                    while (true) {
                        val avail = len - pos
                        if (avail <= 0) break
                        // Keep a packet and one byte of lookahead back for the next chunk.
                        if (!eof && avail <= PACKET) break
                        if (avail < PACKET) {
                            pos = len // truncated final packet, copied through untouched
                            break
                        }
                        // The next packet has to start where this one ends, or this is the
                        // stub of a torn packet and its "timestamps" are someone else's bytes.
                        val synced = buf[pos] == SYNC && (avail == PACKET || buf[pos + PACKET] == SYNC)
                        if (synced) {
                            shifter.packet(buf, pos)
                            pos += PACKET
                        } else {
                            // Lost sync: pass bytes through until it comes back.
                            pos++
                        }
                    }
                    output.write(buf, 0, pos)
                    System.arraycopy(buf, pos, buf, 0, len - pos)
                    len -= pos
                    if (eof && len > 0) {
                        output.write(buf, 0, len)
                        len = 0
                    }
                }
                output.flush()
                if (shifter.shifted) output.fd.sync()
            }
        }
        return shifter.shifted
    }

    /** First offset in the opening packet that starts a run of TS packets, or null if this isn't TS. */
    private fun findSync(buf: ByteArray, len: Int): Int? {
        for (i in 0 until minOf(PACKET, len)) {
            if (buf[i] != SYNC) continue
            if (len - i < PACKET) return null
            if (len - i > PACKET && buf[i + PACKET] != SYNC) continue
            if (len - i > PACKET * 2 && buf[i + PACKET * 2] != SYNC) continue
            return i
        }
        return null
    }

    /** Signed distance from [b] to [a] on the 33 bit clock. */
    private fun diff(a: Long, b: Long): Long {
        val d = (a - b) and MASK
        return if (d >= HALF) d - (1L shl 33) else d
    }

    private class Shifter {
        /** Added to every timestamp, always kept within 0 until 2^33. */
        private var offset = 0L
        private var lastPts = -1L
        private var lastPcr = -1L
        var shifted = false
            private set

        /**
         * Moves the clock on to [value] (already offset). If it is too far from [last], shift
         * from here on so it lands just after [last]. Returns the corrected value.
         */
        private fun follow(value: Long, last: Long): Long {
            if (last < 0) return value
            val d = diff(value, last)
            if (d > JUMP_TICKS || d < -JUMP_TICKS) {
                val target = (last + GAP_TICKS) and MASK
                offset = (offset + diff(target, value)) and MASK
                shifted = offset != 0L || shifted
                return target
            }
            return value
        }

        fun packet(b: ByteArray, p: Int) {
            val pusi = (b[p + 1].toInt() and 0x40) != 0
            val pid = (b[p + 1].toInt() and 0x1F shl 8) or (b[p + 2].toInt() and 0xFF)
            if (pid == 0x1FFF) return
            val afc = b[p + 3].toInt() shr 4 and 3
            val end = p + PACKET
            var payload = p + 4
            if ((afc and 2) != 0) {
                val afLen = b[p + 4].toInt() and 0xFF
                if (p + 5 + afLen > end) return
                if (afLen >= 7 && (b[p + 5].toInt() and 0x10) != 0) {
                    val at = p + 6
                    // PCR runs on its own clock: it leads the PTS by a varying amount.
                    val pcr = follow((readPcr(b, at) + offset) and MASK, lastPcr)
                    lastPcr = pcr
                    if (offset != 0L) writePcr(b, at, pcr)
                }
                payload = p + 5 + afLen
            }
            if ((afc and 1) == 0 || !pusi) return
            if (payload + 14 > end) return
            if (b[payload] != 0.toByte() || b[payload + 1] != 0.toByte() || b[payload + 2] != 1.toByte()) return
            if (!hasPesHeader(b[payload + 3].toInt() and 0xFF)) return
            if ((b[payload + 6].toInt() and 0xC0) != 0x80) return
            val flags = b[payload + 7].toInt() shr 6 and 3
            if ((flags and 2) == 0) return
            val ptsAt = payload + 9
            if (!markersOk(b, ptsAt)) return
            val pts = follow((readPts(b, ptsAt) + offset) and MASK, lastPts)
            lastPts = pts
            if (offset != 0L) writePts(b, ptsAt, pts)
            if (flags == 3 && payload + 19 <= end && markersOk(b, ptsAt + 5) && offset != 0L) {
                writePts(b, ptsAt + 5, (readPts(b, ptsAt + 5) + offset) and MASK)
            }
        }

        private fun hasPesHeader(streamId: Int) = when (streamId) {
            0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF -> false
            else -> true
        }
    }

    private fun markersOk(b: ByteArray, at: Int) =
        (b[at].toInt() and 1) == 1 && (b[at + 2].toInt() and 1) == 1 && (b[at + 4].toInt() and 1) == 1

    internal fun readPts(b: ByteArray, at: Int): Long =
        ((b[at].toLong() shr 1 and 0x07) shl 30) or
            ((b[at + 1].toLong() and 0xFF) shl 22) or
            ((b[at + 2].toLong() shr 1 and 0x7F) shl 15) or
            ((b[at + 3].toLong() and 0xFF) shl 7) or
            (b[at + 4].toLong() shr 1 and 0x7F)

    /** Keeps the 4 bit prefix and the marker bits already there. */
    internal fun writePts(b: ByteArray, at: Int, v: Long) {
        b[at] = ((b[at].toInt() and 0xF1) or ((v shr 30 and 0x07).toInt() shl 1)).toByte()
        b[at + 1] = (v shr 22 and 0xFF).toByte()
        b[at + 2] = (((v shr 15 and 0x7F).toInt() shl 1) or (b[at + 2].toInt() and 1)).toByte()
        b[at + 3] = (v shr 7 and 0xFF).toByte()
        b[at + 4] = (((v and 0x7F).toInt() shl 1) or (b[at + 4].toInt() and 1)).toByte()
    }

    /** The 33 bit, 90 kHz base of a PCR. */
    internal fun readPcr(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 25) or
            ((b[at + 1].toLong() and 0xFF) shl 17) or
            ((b[at + 2].toLong() and 0xFF) shl 9) or
            ((b[at + 3].toLong() and 0xFF) shl 1) or
            (b[at + 4].toLong() shr 7 and 1)

    /** Keeps the reserved bits and the 27 MHz extension. */
    internal fun writePcr(b: ByteArray, at: Int, base: Long) {
        b[at] = (base shr 25 and 0xFF).toByte()
        b[at + 1] = (base shr 17 and 0xFF).toByte()
        b[at + 2] = (base shr 9 and 0xFF).toByte()
        b[at + 3] = (base shr 1 and 0xFF).toByte()
        b[at + 4] = (((base and 1).toInt() shl 7) or (b[at + 4].toInt() and 0x7F)).toByte()
    }
}
