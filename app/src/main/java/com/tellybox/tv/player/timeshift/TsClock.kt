package com.tellybox.tv.player.timeshift

/**
 * Follows the programme clock (PCR) of an MPEG-TS stream as its bytes go past, and turns it
 * into a steady "content time" in milliseconds from the start of the session.
 *
 * Wall-clock arrival time alone is a poor guide to where a byte sits in the programme:
 * providers send a burst of several seconds on connect, and stall now and then. The PCR is
 * what the decoder uses, so it is what playback positions line up with. It wraps every 26.5
 * hours and jumps when the provider restarts its encoder, so implausible jumps fall back to
 * the wall clock rather than tearing a hole in the timeline.
 */
class TsClock {
    /** Content time of the last PCR seen. */
    var contentMs = 0L
        private set

    /** Logical offset of the packet that carried the last PCR. */
    var lastPcrOffset = -1L
        private set

    private val carry = ByteArray(PACKET)
    private var carried = 0
    private var carryStart = 0L
    private var pcrPid = -1
    private var lastPcrMs = -1L
    private var lastPcrWall = 0L

    /** A new connection: the bytes that follow don't continue the packet that was cut off. */
    fun reset() {
        carried = 0
        pcrPid = -1
        // lastPcrMs is kept: a reconnect to the same encoder carries on its clock.
    }

    /**
     * Feeds [length] bytes that sit at logical [offset]. [onPcr] is called for every PCR,
     * with the offset of the packet that carried it.
     */
    fun feed(data: ByteArray, start: Int, length: Int, offset: Long, wallMs: Long, onPcr: (Long, Long) -> Unit) {
        var i = start
        val end = start + length
        // Finish a packet split across two reads.
        if (carried > 0) {
            val need = PACKET - carried
            val take = minOf(need, end - i)
            System.arraycopy(data, i, carry, carried, take)
            carried += take
            i += take
            if (carried < PACKET) return
            carried = 0
            packet(carry, 0, carryStart, wallMs, onPcr)
        }
        while (i < end) {
            if (data[i] != SYNC) {
                i++
                continue
            }
            if (end - i < PACKET) {
                carryStart = offset + (i - start)
                carried = end - i
                System.arraycopy(data, i, carry, 0, carried)
                return
            }
            // Only trust a sync byte that the next packet agrees with, when there is one to check.
            if (end - i > PACKET && data[i + PACKET] != SYNC) {
                i++
                continue
            }
            packet(data, i, offset + (i - start), wallMs, onPcr)
            i += PACKET
        }
    }

    private fun packet(p: ByteArray, at: Int, offset: Long, wallMs: Long, onPcr: (Long, Long) -> Unit) {
        val pid = ((p[at + 1].toInt() and 0x1F) shl 8) or (p[at + 2].toInt() and 0xFF)
        val adaptation = (p[at + 3].toInt() shr 4) and 0x3
        if (adaptation != 2 && adaptation != 3) return
        val afLength = p[at + 4].toInt() and 0xFF
        if (afLength < 7) return
        val flags = p[at + 5].toInt() and 0xFF
        if (flags and 0x10 == 0) return
        if (pcrPid == -1) pcrPid = pid
        if (pid != pcrPid) return
        val base = ((p[at + 6].toLong() and 0xFF) shl 25) or
            ((p[at + 7].toLong() and 0xFF) shl 17) or
            ((p[at + 8].toLong() and 0xFF) shl 9) or
            ((p[at + 9].toLong() and 0xFF) shl 1) or
            ((p[at + 10].toLong() and 0x80) shr 7)
        advance(base / 90, wallMs)
        lastPcrOffset = offset
        onPcr(offset, contentMs)
    }

    private fun advance(pcrMs: Long, wallMs: Long) {
        if (lastPcrMs >= 0) {
            var delta = pcrMs - lastPcrMs
            if (delta < -WRAP_MS / 2) delta += WRAP_MS
            val wallDelta = wallMs - lastPcrWall
            // A PCR running backwards or leaping ahead is a new encoder, not elapsed time.
            if (delta < -MAX_JITTER_MS || delta > maxOf(MAX_GAP_MS, wallDelta + MAX_GAP_MS)) delta = wallDelta.coerceAtLeast(0)
            contentMs += delta.coerceAtLeast(0)
        }
        lastPcrMs = pcrMs
        lastPcrWall = wallMs
    }

    companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        private const val WRAP_MS = (1L shl 33) / 90
        private const val MAX_JITTER_MS = 1_000L
        private const val MAX_GAP_MS = 10_000L

        /** Whether [data] looks like the start of an MPEG-TS stream: sync bytes a packet apart. */
        fun looksLikeTs(data: ByteArray, length: Int): Boolean {
            if (length < PACKET * 3) return false
            for (i in 0 until minOf(PACKET, length - PACKET * 2)) {
                if (data[i] == SYNC && data[i + PACKET] == SYNC && data[i + PACKET * 2] == SYNC) return true
            }
            return false
        }
    }
}
