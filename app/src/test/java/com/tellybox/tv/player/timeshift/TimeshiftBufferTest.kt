package com.tellybox.tv.player.timeshift

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TimeshiftBufferTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val buffers = mutableListOf<TimeshiftBuffer>()

    @After
    fun closeAll() = buffers.forEach { it.close() }

    private fun buffer(capacity: Long, memory: Int): Pair<TimeshiftBuffer, File> {
        val file = File(tmp.root, "b.part")
        return TimeshiftBuffer(file, capacity, memory).also { buffers += it } to file
    }

    /** The byte at logical offset [i], so any byte read can be checked against where it came from. */
    private fun byteAt(i: Long) = ((i * 31 + 7) % 251).toByte()

    private fun bytes(from: Long, count: Int) = ByteArray(count) { byteAt(from + it) }

    private fun TimeshiftBuffer.flushAll() {
        val scratch = ByteArray(4096)
        while (unwritten > 0) writeSome(scratch, 0)
    }

    /** Appends [count] bytes from [from] the way the pump does: in reads much smaller than the window. */
    private fun TimeshiftBuffer.feed(from: Long, count: Int, flush: Boolean = true) {
        var at = 0
        while (at < count) {
            val n = minOf(2_000, count - at)
            append(bytes(from + at, n), 0, n)
            if (flush) flushAll()
            at += n
        }
    }

    private fun readExactly(buffer: TimeshiftBuffer, cursor: TimeshiftBuffer.Cursor, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = buffer.read(cursor, out, done, count - done)
            check(n > 0)
            done += n
        }
        return out
    }

    @Test
    fun `reads back what was appended, from memory and from disk`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        buffer.feed(0, 50_000)
        val cursor = buffer.Cursor(0)
        // The first 40 KB have left the memory window and come from the file.
        assertArrayEquals(bytes(0, 50_000), readExactly(buffer, cursor, 50_000))
        cursor.close()
    }

    @Test
    fun `file stops growing at the cap and the oldest bytes are the ones lost`() {
        val capacity = 100_000L
        val (buffer, file) = buffer(capacity, memory = 8_000)
        var written = 0L
        while (written < 350_000) {
            buffer.append(bytes(written, 5_000), 0, 5_000)
            written += 5_000
            buffer.flushAll()
            assertTrue("file grew to ${file.length()}", file.length() <= capacity)
        }
        assertEquals(capacity, file.length())
        // Everything older than the last 100 KB is gone; a reader asking for it starts at the oldest kept.
        assertEquals(written - capacity, buffer.oldest)
        val cursor = buffer.Cursor(0)
        val got = readExactly(buffer, cursor, 1_000)
        assertArrayEquals(bytes(written - capacity, 1_000), got)
        cursor.close()
    }

    @Test
    fun `a writer that falls behind loses buffer bytes, never the live ones`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        // Nothing is written to disk while 30 KB arrive: 20 KB of it can't be kept.
        buffer.feed(0, 30_000, flush = false)
        assertEquals(20_000L, buffer.discarded)
        val cursor = buffer.Cursor(0)
        // The reader skips what was lost and gets the newest 10 KB intact.
        assertArrayEquals(bytes(20_000, 10_000), readExactly(buffer, cursor, 10_000))
        assertEquals(30_000L, cursor.position)
        cursor.close()
    }

    @Test
    fun `holes are skipped when reading from disk`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        buffer.feed(0, 5_000)
        buffer.feed(5_000, 25_000, flush = false) // 15 KB of this never reaches disk
        buffer.flushAll()
        buffer.feed(30_000, 20_000)
        val cursor = buffer.Cursor(0)
        assertArrayEquals(bytes(0, 5_000), readExactly(buffer, cursor, 5_000))
        val next = readExactly(buffer, cursor, 100)
        assertEquals(20_100L, cursor.position)
        assertArrayEquals(bytes(20_000, 100), next)
        cursor.close()
    }

    @Test
    fun `a reader at the newest byte waits for more instead of ending`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        buffer.append(bytes(0, 100), 0, 100)
        val cursor = buffer.Cursor(100)
        val got = ByteArray(50)
        val done = CountDownLatch(1)
        var n = 0
        val reader = thread { n = buffer.read(cursor, got, 0, 50); done.countDown() }
        Thread.sleep(200)
        assertEquals(1L, done.count)
        buffer.append(bytes(100, 50), 0, 50)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        reader.join()
        assertEquals(50, n)
        assertArrayEquals(bytes(100, 50), got)
        cursor.close()
    }

    @Test
    fun `closing ends a waiting reader`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        val cursor = buffer.Cursor(0)
        var n = 0
        val reader = thread { n = buffer.read(cursor, ByteArray(10), 0, 10) }
        Thread.sleep(100)
        buffer.close()
        reader.join(2_000)
        assertEquals(-1, n)
    }

    @Test
    fun `a failed stream reaches a reader only once it catches up`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        buffer.append(bytes(0, 100), 0, 100)
        buffer.fail(IOException("gone"))
        val cursor = buffer.Cursor(0)
        assertEquals(100, buffer.read(cursor, ByteArray(100), 0, 100))
        val thrown = runCatching { buffer.read(cursor, ByteArray(10), 0, 10) }.exceptionOrNull()
        assertTrue(thrown is IOException)
        cursor.close()
    }

    @Test
    fun `dropping the disk leaves live reading from memory`() {
        val (buffer, _) = buffer(capacity = 1_000_000, memory = 10_000)
        buffer.feed(0, 50_000)
        buffer.dropDisk()
        assertEquals(40_000L, buffer.oldest)
        val cursor = buffer.Cursor(0)
        assertArrayEquals(bytes(40_000, 10_000), readExactly(buffer, cursor, 10_000))
        buffer.append(bytes(50_000, 1_000), 0, 1_000)
        assertArrayEquals(bytes(50_000, 1_000), readExactly(buffer, cursor, 1_000))
        cursor.close()
    }

    @Test
    fun `reader and writer running together agree on every byte`() {
        val (buffer, _) = buffer(capacity = 200_000, memory = 20_000)
        val total = 2_000_000L
        val writer = thread {
            val scratch = ByteArray(7_000)
            while (buffer.writeSome(scratch, 50)) Unit
        }
        val producer = thread {
            var at = 0L
            while (at < total) {
                buffer.append(bytes(at, 1_000), 0, 1_000)
                at += 1_000
                if (at % 50_000 == 0L) Thread.sleep(1)
            }
        }
        val cursor = buffer.Cursor(0)
        val chunk = ByteArray(3_000)
        var checked = 0L
        while (cursor.position < total) {
            val from = cursor.position
            val n = buffer.read(cursor, chunk, 0, chunk.size)
            // Skips are allowed (the reader was lapped); wrong bytes are not.
            val start = cursor.position - n
            assertTrue(start >= from)
            for (k in 0 until n) assertEquals(byteAt(start + k), chunk[k])
            checked += n
        }
        producer.join()
        buffer.close()
        writer.join()
        cursor.close()
        assertTrue(checked > 0)
    }
}
