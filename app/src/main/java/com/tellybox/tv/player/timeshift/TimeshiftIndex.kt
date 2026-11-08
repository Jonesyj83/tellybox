package com.tellybox.tv.player.timeshift

/**
 * Where things are in the buffer: (byte offset, wall-clock arrival, content time), one entry
 * about every [spacingMs] of programme. Every offset is the start of a TS packet, so playback
 * opened at one starts cleanly.
 *
 * Not thread-safe; the session guards it.
 */
class TimeshiftIndex(private val spacingMs: Long = 500) {
    private var offsets = LongArray(1024)
    private var walls = LongArray(1024)
    private var contents = LongArray(1024)
    private var first = 0
    private var count = 0

    val size get() = count
    val isEmpty get() = count == 0

    fun add(offset: Long, wallMs: Long, contentMs: Long) {
        if (count > 0) {
            val last = at(count - 1)
            if (offset <= offsets[last]) return
            if (contentMs - contents[last] < spacingMs) return
        }
        if (first + count == offsets.size) compact()
        val i = first + count
        offsets[i] = offset
        walls[i] = wallMs
        contents[i] = contentMs
        count++
    }

    /** Forgets entries for bytes that have been overwritten. */
    fun dropBefore(offset: Long) {
        while (count > 0 && offsets[first] < offset) {
            first++
            count--
        }
        if (count == 0) first = 0
    }

    fun clear() {
        first = 0
        count = 0
    }

    val firstOffset get() = if (count == 0) -1L else offsets[first]
    val firstContent get() = if (count == 0) 0L else contents[first]
    val lastOffset get() = if (count == 0) -1L else offsets[at(count - 1)]
    val lastContent get() = if (count == 0) 0L else contents[at(count - 1)]
    val lastWall get() = if (count == 0) 0L else walls[at(count - 1)]

    /** Content time of the first entry at or after [offset]: what playback opened there will show first. */
    fun contentAt(offset: Long): Long {
        if (count == 0) return 0L
        val i = lowerBound(offset)
        return if (i < count) contents[at(i)] else lastContent
    }

    /** Offset to open for [contentMs]: the last entry not after it, so playback lands just before, not past. */
    fun offsetFor(contentMs: Long): Long {
        if (count == 0) return -1L
        var lo = 0
        var hi = count - 1
        if (contents[at(0)] >= contentMs) return offsets[at(0)]
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (contents[at(mid)] <= contentMs) lo = mid else hi = mid - 1
        }
        return offsets[at(lo)]
    }

    /** Bytes per second over roughly the last [windowMs] of arrivals, or 0 if not known yet. */
    fun byteRate(headOffset: Long, nowMs: Long, windowMs: Long = 30_000): Long {
        if (count == 0) return 0L
        var i = count - 1
        while (i > 0 && nowMs - walls[at(i - 1)] <= windowMs) i--
        val elapsed = nowMs - walls[at(i)]
        if (elapsed < 2_000) return 0L
        return (headOffset - offsets[at(i)]) * 1000 / elapsed
    }

    private fun at(i: Int) = first + i

    private fun lowerBound(offset: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[at(mid)] < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compact() {
        if (first > offsets.size / 2) {
            System.arraycopy(offsets, first, offsets, 0, count)
            System.arraycopy(walls, first, walls, 0, count)
            System.arraycopy(contents, first, contents, 0, count)
        } else {
            val size = offsets.size * 2
            offsets = offsets.copyOfRange(first, first + count).copyOf(size)
            walls = walls.copyOfRange(first, first + count).copyOf(size)
            contents = contents.copyOfRange(first, first + count).copyOf(size)
        }
        first = 0
    }
}
