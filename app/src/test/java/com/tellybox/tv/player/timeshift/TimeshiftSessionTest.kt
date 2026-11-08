package com.tellybox.tv.player.timeshift

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TimeshiftSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    /** A provider whose connections hand out whatever the test feeds them. */
    private inner class FakeProvider : Upstream {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val connections = LinkedBlockingQueue<Conn>()
        @Volatile var refuse = false

        inner class Conn(val url: String) : UpstreamConnection {
            val chunks = LinkedBlockingQueue<ByteArray>()
            @Volatile var closed = false
            private var current: ByteArray? = null
            private var at = 0

            fun send(data: ByteArray) = chunks.put(data)
            /** Ends the stream as a provider dropping the connection would. */
            fun drop() = chunks.put(ByteArray(0))

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                while (true) {
                    if (closed) throw IOException("closed")
                    val c = current ?: chunks.poll(50, TimeUnit.MILLISECONDS)?.also { current = it; at = 0 } ?: continue
                    if (c.isEmpty()) return -1
                    val n = minOf(length, c.size - at)
                    System.arraycopy(c, at, target, offset, n)
                    at += n
                    if (at == c.size) current = null
                    return n
                }
            }

            override fun close() {
                if (!closed) log += "close $url"
                closed = true
            }
        }

        override fun open(url: String): UpstreamConnection {
            if (refuse) throw IOException("refused")
            synchronized(log) {
                // A provider allowing one connection per account would refuse this.
                check(connections.none { !it.closed }) { "second connection while one is open" }
                log += "open $url"
            }
            return Conn(url).also { connections.put(it) }
        }

        fun next(): Conn = connections.poll(2, TimeUnit.SECONDS) ?: error("no connection opened")
    }

    private val provider = FakeProvider()
    private val sessions = mutableListOf<TimeshiftSession>()
    private val free = AtomicLong(64L * TimeshiftStorage.GB)
    private val resolves = AtomicInteger()

    @After
    fun closeAll() = sessions.forEach { it.close() }

    private fun session(url: String = "http://p/1.ts", capacity: Long = 4_000_000): TimeshiftSession {
        val dir = tmp.root
        val file = TimeshiftFiles.claim(dir)
        return TimeshiftSession(file, capacity, provider, url,
            resolve = { "http://p/1.ts?token=${resolves.incrementAndGet()}" },
            freeSpace = { free.get() }, memoryBytes = 200_000,
        ).also { sessions += it; it.start() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) error("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `buffers the stream and deletes the file on close`() {
        val s = session()
        val conn = provider.next()
        val ts = TsPackets.stream(seconds = 5)
        conn.send(ts)
        waitFor("bytes") { s.buffer.head == ts.size.toLong() }
        assertTrue(s.isTs)
        assertTrue(s.file.name.endsWith(".part"))
        waitFor("disk") { s.file.length() == ts.size.toLong() }
        val window = s.window()
        assertEquals(0L, window.oldestMs)
        assertEquals(4_900L, window.liveMs)
        // Rewinding two seconds from live lands on an index entry no later than asked for.
        val offset = s.offsetFor(window.liveMs - 2_000)
        assertTrue(s.contentAt(offset) in 2_400L..2_900L)
        assertEquals(TsClock.SYNC, s.file.readBytes()[offset.toInt()])

        s.close()
        waitFor("closed connection") { conn.closed }
        waitFor("file deleted") { tmp.root.listFiles()!!.isEmpty() }
        // The name is let go only after the delete, so a sweep never races it.
        waitFor("name released") { !TimeshiftFiles.isOpen(s.file.name) }
    }

    @Test
    fun `going live closes the old connection before opening a new one`() {
        val s = session()
        val first = provider.next()
        first.send(TsPackets.stream(seconds = 2))
        waitFor("bytes") { s.buffer.head > 0 }
        val head = s.buffer.head
        val offset = s.restart("http://p/1.ts?token=fresh")
        assertEquals(head, offset)
        val second = provider.next()
        assertTrue(first.closed)
        assertEquals(listOf("open http://p/1.ts", "close http://p/1.ts", "open http://p/1.ts?token=fresh"), provider.log.toList())
        second.send(TsPackets.stream(seconds = 1, startPcrMs = 2_500))
        waitFor("new bytes") { s.buffer.head > head }
        assertEquals("http://p/1.ts?token=fresh", s.url)
    }

    @Test
    fun `a stream that isn't ts is refused and its file removed`() {
        val s = session()
        val conn = provider.next()
        conn.send("#EXTM3U\n#EXT-X-TARGETDURATION:6\n".repeat(50).toByteArray())
        waitFor("verdict") { s.unavailable != null }
        assertEquals(TimeshiftUnavailable.UnsupportedStream, s.unavailable)
        assertEquals(0L, s.buffer.head)
        val reader = s.buffer.Cursor(0)
        val error = runCatching { s.buffer.read(reader, ByteArray(10), 0, 10) }.exceptionOrNull()
        assertTrue(generateSequence(error) { it.cause }.any { it is TimeshiftUnsupportedException })
        reader.close()
        waitFor("connection closed") { conn.closed }
        waitFor("file deleted") { !s.file.exists() }
    }

    @Test
    fun `a dropped stream reconnects with a freshly resolved url`() {
        val s = session()
        val first = provider.next()
        first.send(TsPackets.stream(seconds = 1))
        waitFor("bytes") { s.buffer.head > 0 }
        first.drop()
        val second = provider.next()
        assertEquals("http://p/1.ts?token=1", second.url)
        second.send(TsPackets.stream(seconds = 1, startPcrMs = 1_000))
        waitFor("more bytes") { s.buffer.head == 2L * TsPackets.stream(seconds = 1).size }
        assertNull(s.unavailable)
    }

    @Test
    fun `when the provider stays down the viewer at live hears about it`() {
        val s = session()
        val first = provider.next()
        first.send(TsPackets.stream(seconds = 1))
        waitFor("bytes") { s.buffer.head > 0 }
        provider.refuse = true
        first.drop()
        val reader = s.buffer.Cursor(s.buffer.head)
        val error = runCatching { s.buffer.read(reader, ByteArray(10), 0, 10) }.exceptionOrNull()
        assertTrue("got $error", error is IOException)
        reader.close()
        // Timeshift itself is still fine: the buffer is kept and going live starts a new connection.
        assertNull(s.unavailable)
        provider.refuse = false
        s.restart("http://p/1.ts?token=again")
        provider.next().send(TsPackets.stream(seconds = 1))
        val again = s.buffer.Cursor(s.buffer.head)
        assertTrue(s.buffer.read(again, ByteArray(10), 0, 10) > 0)
        again.close()
    }

    @Test
    fun `running low on storage drops the buffer but not the stream`() {
        val s = session()
        val conn = provider.next()
        val ts = TsPackets.stream(seconds = 2)
        conn.send(ts)
        waitFor("disk") { s.file.length() > 0 }
        free.set(100 * TimeshiftStorage.MB)
        conn.send(ts)
        waitFor("gave up", timeoutMs = 10_000) { s.unavailable != null }
        assertEquals(TimeshiftUnavailable.StorageRanLow, s.unavailable)
        waitFor("file deleted") { !s.file.exists() }
        // Live carries on from memory.
        val reader = s.buffer.Cursor(s.buffer.head)
        conn.send(ts)
        assertTrue(s.buffer.read(reader, ByteArray(100), 0, 100) > 0)
        reader.close()
    }

    @Test
    fun `open refuses to start without enough free space`() {
        val dir = File(tmp.root, "ts")
        // The temporary folder has whatever space the machine has; ask for more than exists.
        val result = TimeshiftSession.open(dir, Long.MAX_VALUE, provider, "http://p/1.ts") { "" }
        when (result) {
            is TimeshiftSession.OpenResult.Opened -> {
                // Plenty of disk here: the cap is a quarter of what is free, not what was asked for.
                assertTrue(result.session.capacity <= dir.usableSpace / 4 + TimeshiftStorage.MB)
                sessions += result.session
            }
            is TimeshiftSession.OpenResult.Unavailable ->
                assertTrue(result.reason is TimeshiftUnavailable.LowStorage)
        }
    }
}
