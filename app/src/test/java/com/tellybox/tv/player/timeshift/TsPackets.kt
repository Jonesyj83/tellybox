package com.tellybox.tv.player.timeshift

import java.io.ByteArrayOutputStream

/** Builds minimal MPEG-TS for tests: packets on one PID, some carrying a PCR. */
object TsPackets {
    const val PID = 0x100

    fun packet(pcrMs: Long? = null, pid: Int = PID, fill: Int = 0): ByteArray {
        val p = ByteArray(TsClock.PACKET) { (fill + it).toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (pcrMs == null) {
            p[3] = 0x10 // payload only
            return p
        }
        p[3] = 0x30 // adaptation field and payload
        p[4] = 7
        p[5] = 0x10 // PCR flag
        val base = (pcrMs * 90) and ((1L shl 33) - 1)
        p[6] = (base shr 25).toByte()
        p[7] = (base shr 17).toByte()
        p[8] = (base shr 9).toByte()
        p[9] = (base shr 1).toByte()
        p[10] = (((base and 1) shl 7) or 0x7E).toByte()
        p[11] = 0
        return p
    }

    /** [seconds] of stream at [packetsPerSecond], a PCR every [pcrEveryMs], starting at [startPcrMs]. */
    fun stream(seconds: Int, packetsPerSecond: Int = 100, pcrEveryMs: Long = 100, startPcrMs: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val total = seconds * packetsPerSecond
        var nextPcr = 0L
        for (i in 0 until total) {
            val t = i * 1000L / packetsPerSecond
            if (t >= nextPcr) {
                out.write(packet(startPcrMs + t, fill = i))
                nextPcr += pcrEveryMs
            } else {
                out.write(packet(fill = i))
            }
        }
        return out.toByteArray()
    }
}
