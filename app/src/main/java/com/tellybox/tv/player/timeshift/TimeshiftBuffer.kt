package com.tellybox.tv.player.timeshift

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The bytes of one live session, addressed by logical offset (bytes since the session began).
 *
 * New bytes land in an in-memory window first and are copied to a ring file on disk by a
 * separate writer, so the stream never waits on storage. Once the file reaches [capacity] it
 * wraps and the oldest bytes are overwritten. If the writer falls so far behind that bytes
 * leave the memory window before reaching disk, those bytes are lost from the rewind history
 * (recorded as a hole) rather than holding up the stream.
 *
 * Readers ask for any offset; one that has been overwritten is moved forward to the oldest
 * byte still held, and one at the newest byte waits for more.
 */
class TimeshiftBuffer(
    private val file: File,
    val capacity: Long,
    private val memoryBytes: Int = DEFAULT_MEMORY,
) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val toWrite = lock.newCondition()

    private val memory = ByteArray(memoryBytes)
    private var raf: RandomAccessFile? = RandomAccessFile(file, "rw")

    /** Bytes appended so far; the next byte's offset. */
    var head = 0L
        private set

    /** Everything before this has been written to disk (or given up on). */
    private var flushed = 0L

    /** Lowest offset the file may still hold; below it the ring has wrapped over the bytes. */
    private var diskFloor = 0L

    /** Ranges that never reached disk, oldest first. */
    private val holes = ArrayDeque<LongArray>()

    private var diskEnabled = true
    private var closed = false
    private var failure: IOException? = null

    /** How many bytes the writer has been forced to discard, for logging. */
    var discarded = 0L
        private set

    val hasDisk get() = lock.withLock { diskEnabled }

    /** Bytes still waiting for the writer. */
    internal val unwritten get() = lock.withLock { if (diskEnabled && !closed) head - flushed else 0L }

    /** Lowest offset a reader can be given right now. */
    val oldest: Long get() = lock.withLock { oldestLocked() }

    private fun memoryFloor() = maxOf(0L, head - memoryBytes)

    private fun oldestLocked(): Long {
        val mem = memoryFloor()
        if (!diskEnabled) return mem
        var floor = diskFloor
        for (h in holes) if (h[0] <= floor && h[1] > floor) floor = h[1]
        return minOf(floor, mem)
    }

    /** Called by whoever is reading the upstream. Never blocks on disk. */
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        lock.withLock {
            if (closed) return
            var src = offset
            var left = length
            // A single read bigger than the window only keeps its tail.
            if (left > memoryBytes) {
                val skip = left - memoryBytes
                src += skip
                left -= skip
                markLost(head, head + skip)
                head += skip
            }
            val newHead = head + left
            val newFloor = newHead - memoryBytes
            if (diskEnabled && newFloor > flushed) markLost(flushed, newFloor)
            var pos = head
            while (left > 0) {
                val at = (pos % memoryBytes).toInt()
                val n = minOf(left, memoryBytes - at)
                System.arraycopy(data, src, memory, at, n)
                src += n
                left -= n
                pos += n
            }
            head = newHead
            arrived.signalAll()
            toWrite.signal()
        }
    }

    private fun markLost(from: Long, to: Long) {
        if (to <= from) return
        if (to > flushed) {
            discarded += to - maxOf(from, flushed)
            flushed = to
        }
        val last = holes.lastOrNull()
        if (last != null && last[1] >= from) last[1] = maxOf(last[1], to) else holes.addLast(longArrayOf(from, to))
    }

    /**
     * Writer side: copies the next run of bytes to disk. Waits up to [waitMs] for something to
     * write; returns false once closed or once the disk has been given up.
     */
    fun writeSome(scratch: ByteArray, waitMs: Long): Boolean {
        val start: Long
        val n: Int
        val out: RandomAccessFile
        lock.withLock {
            if (closed || !diskEnabled) return false
            if (head == flushed) {
                toWrite.await(waitMs, TimeUnit.MILLISECONDS)
                if (closed || !diskEnabled) return false
                if (head == flushed) return true
            }
            start = flushed
            n = minOf(head - flushed, scratch.size.toLong(), capacity - start % capacity).toInt()
            var pos = start
            var done = 0
            while (done < n) {
                val at = (pos % memoryBytes).toInt()
                val c = minOf(n - done, memoryBytes - at)
                System.arraycopy(memory, at, scratch, done, c)
                done += c
                pos += c
            }
            // Raise the floor before overwriting, so no reader trusts bytes about to change.
            diskFloor = maxOf(diskFloor, start + n - capacity)
            while (holes.isNotEmpty() && holes.first()[1] <= diskFloor) holes.removeFirst()
            out = raf ?: return false
        }
        try {
            out.seek(start % capacity)
            out.write(scratch, 0, n)
        } catch (e: IOException) {
            if (lock.withLock { closed || !diskEnabled }) return false
            throw e
        }
        lock.withLock {
            if (flushed == start) flushed = start + n
            // If append() gave up on these bytes while we wrote them, its hole stays: harmless.
        }
        return true
    }

    /** Stops using disk: later reads come from memory only. The file is closed but not deleted. */
    fun dropDisk() {
        val out = lock.withLock {
            if (!diskEnabled) return
            diskEnabled = false
            holes.clear()
            toWrite.signalAll()
            arrived.signalAll()
            raf.also { raf = null }
        }
        runCatching { out?.close() }
    }

    /** The stream behind this buffer failed for good; readers at the newest byte get [e]. */
    fun fail(e: IOException) = lock.withLock {
        failure = e
        arrived.signalAll()
    }

    /** The stream is flowing again. */
    fun recover() = lock.withLock { failure = null }

    fun close() {
        val out = lock.withLock {
            if (closed) return
            closed = true
            arrived.signalAll()
            toWrite.signalAll()
            raf.also { raf = null }
        }
        runCatching { out?.close() }
    }

    /**
     * A reader's place in the buffer. Each has its own handle on the file: the writer's seek
     * position isn't shared, and a reader being interrupted (ExoPlayer does that to cancel a
     * load) can't close anything the writer uses.
     */
    inner class Cursor(var position: Long) : java.io.Closeable {
        internal var input: RandomAccessFile? = null

        internal fun file(): RandomAccessFile = input ?: RandomAccessFile(this@TimeshiftBuffer.file, "r").also { input = it }

        override fun close() {
            runCatching { input?.close() }
            input = null
        }
    }

    /**
     * Reads into [target] from [cursor], moving it on. Waits for bytes when the cursor is at
     * the newest one. Returns -1 once the buffer is closed.
     */
    fun read(cursor: Cursor, target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val diskAt: Long
            val n: Int
            lock.lockInterruptibly()
            try {
                if (closed) return -1
                if (cursor.position >= head) {
                    failure?.let { throw IOException("Live stream lost", it) }
                    try {
                        arrived.await(READ_WAIT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        throw InterruptedIOException().apply { initCause(e) }
                    }
                    continue
                }
                val oldest = oldestLocked()
                if (cursor.position < oldest) cursor.position = oldest
                val memFloor = memoryFloor()
                if (cursor.position >= memFloor) {
                    var pos = cursor.position
                    val take = minOf(length.toLong(), head - pos).toInt()
                    var done = 0
                    while (done < take) {
                        val at = (pos % memoryBytes).toInt()
                        val c = minOf(take - done, memoryBytes - at)
                        System.arraycopy(memory, at, target, offset + done, c)
                        done += c
                        pos += c
                    }
                    cursor.position = pos
                    return take
                }
                // Behind the memory window, so it is on disk (oldest() has ruled out the rest).
                val hole = holes.firstOrNull { it[1] > cursor.position }
                if (hole != null && hole[0] <= cursor.position) {
                    cursor.position = hole[1]
                    continue
                }
                var limit = minOf(flushed, memFloor)
                if (hole != null) limit = minOf(limit, hole[0])
                diskAt = cursor.position
                n = minOf(length.toLong(), limit - diskAt, capacity - diskAt % capacity).toInt()
                if (n <= 0) continue
            } finally {
                lock.unlock()
            }
            try {
                val input = cursor.file()
                input.seek(diskAt % capacity)
                input.readFully(target, offset, n)
            } catch (e: IOException) {
                if (lock.withLock { closed || !diskEnabled }) continue
                throw e
            }
            // The writer may have lapped us while we read; if so the bytes are not ours.
            if (!lock.withLock { diskEnabled && diskAt >= diskFloor }) continue
            cursor.position = diskAt + n
            return n
        }
    }

    companion object {
        /** Several seconds of even a high-bitrate stream. */
        const val DEFAULT_MEMORY = 16 * 1024 * 1024
        private const val READ_WAIT_MS = 500L
    }
}
