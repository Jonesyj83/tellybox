package com.tellybox.tv.player.timeshift

import android.util.Log
import java.io.File
import java.io.IOException

/** One provider connection, as the session sees it. */
interface UpstreamConnection {
    /** Like DataSource.read: bytes read, or -1 at the end of the stream. */
    fun read(target: ByteArray, offset: Int, length: Int): Int
    fun close()
}

fun interface Upstream {
    fun open(url: String): UpstreamConnection
}

/** The stream turned out not to be MPEG-TS, so it can't be buffered byte for byte. */
class TimeshiftUnsupportedException : IOException("Stream isn't MPEG-TS")

/**
 * Timeshift for one live channel. A pump thread reads the provider's stream and appends it
 * to a [TimeshiftBuffer]; the player reads the buffer, so the stream keeps arriving while the
 * picture is paused or rewound. A second thread copies the buffer to disk and keeps an eye
 * on free space.
 *
 * The pump owns the only connection to the provider. Many providers allow just one per
 * account, so it is closed before any replacement is opened.
 */
class TimeshiftSession internal constructor(
    val file: File,
    val capacity: Long,
    private val upstream: Upstream,
    url: String,
    /** Produces a fresh stream URL; tokens in the old one may have expired. */
    private val resolve: () -> String,
    private val freeSpace: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    memoryBytes: Int = TimeshiftBuffer.DEFAULT_MEMORY,
) {
    val buffer = TimeshiftBuffer(file, capacity, memoryBytes)
    private val index = TimeshiftIndex()
    private val tsClock = TsClock()

    /** Guards the pump generation, the index and the clock. */
    private val lock = Object()
    private var generation = 0
    private var connection: UpstreamConnection? = null
    @Volatile private var checkedFormat = false
    private var closed = false

    @Volatile var url: String = url
        private set

    /** Set once the session can no longer offer timeshift; live playback carries on regardless. */
    @Volatile var unavailable: TimeshiftUnavailable? = null
        private set

    /** Called on a background thread when [unavailable] gets set. */
    @Volatile var onUnavailable: ((TimeshiftUnavailable) -> Unit)? = null

    private val writer = Thread(::writeLoop, "timeshift-writer")

    fun start() {
        writer.start()
        startPump(0)
    }

    private fun startPump(gen: Int) {
        Thread({ pump(gen) }, "timeshift-pump").apply { isDaemon = true }.start()
    }

    /**
     * Drops the current provider connection and opens a new one with [newUrl], for going back
     * to live. Returns the offset at which the new connection's bytes will start.
     * Does network I/O; call off the main thread.
     */
    fun restart(newUrl: String): Long {
        val old: UpstreamConnection?
        val gen: Int
        val offset: Long
        synchronized(lock) {
            if (closed) return buffer.head
            generation++
            gen = generation
            old = connection
            connection = null
            offset = buffer.head
            url = newUrl
        }
        old?.let { runCatching { it.close() } }
        buffer.recover()
        startPump(gen)
        return offset
    }

    private fun pump(gen: Int) {
        val scratch = ByteArray(64 * 1024)
        val probe = ByteArray(TsClock.PACKET * 6)
        var probed = 0
        var failures = 0
        var lastError: IOException? = null
        while (true) {
            val conn = try {
                upstream.open(url)
            } catch (e: IOException) {
                lastError = e
                null
            } catch (e: RuntimeException) {
                lastError = IOException(e)
                null
            }
            if (conn != null) {
                val stale = synchronized(lock) {
                    if (gen != generation || closed) true else { connection = conn; tsClock.reset(); false }
                }
                if (stale) {
                    runCatching { conn.close() }
                    return
                }
                try {
                    while (true) {
                        val n = conn.read(scratch, 0, scratch.size)
                        if (n < 0) break
                        if (n == 0) continue
                        if (!checkedFormat) {
                            val take = minOf(n, probe.size - probed)
                            System.arraycopy(scratch, 0, probe, probed, take)
                            probed += take
                            if (probed == probe.size) {
                                if (!TsClock.looksLikeTs(probe, probed)) {
                                    giveUp(TimeshiftUnavailable.UnsupportedStream)
                                    buffer.fail(TimeshiftUnsupportedException())
                                    runCatching { conn.close() }
                                    return
                                }
                                checkedFormat = true
                            }
                        }
                        val now = clock()
                        synchronized(lock) {
                            if (gen != generation || closed) return
                            tsClock.feed(scratch, 0, n, buffer.head, now) { offset, content -> index.add(offset, now, content) }
                            buffer.append(scratch, 0, n)
                            index.dropBefore(buffer.oldest)
                        }
                        if (failures > 0) {
                            failures = 0
                            buffer.recover()
                        }
                    }
                    lastError = null
                } catch (e: IOException) {
                    lastError = e
                } catch (e: RuntimeException) {
                    // A connection closed from another thread mid-read can surface as anything.
                    lastError = IOException(e)
                } finally {
                    synchronized(lock) { if (connection === conn) connection = null }
                    runCatching { conn.close() }
                }
            }
            if (synchronized(lock) { gen != generation || closed }) return
            failures++
            Log.w(TAG, "Live stream dropped ($failures)", lastError)
            if (failures > MAX_RETRIES) {
                // Whoever is watching at the live edge hears about it; someone behind live
                // keeps watching what is buffered until they catch up.
                buffer.fail(lastError ?: IOException("Live stream ended"))
                return
            }
            try {
                Thread.sleep(RETRY_DELAY_MS * failures)
            } catch (_: InterruptedException) {
                return
            }
            if (synchronized(lock) { gen != generation || closed }) return
            runCatching { resolve() }.getOrNull()?.let { url = it }
        }
    }

    private fun writeLoop() {
        val scratch = ByteArray(256 * 1024)
        var checkedAt = 0L
        var full = false
        try {
            while (buffer.writeSome(scratch, 1_000)) {
                if (!full && buffer.head >= capacity) {
                    full = true
                    Log.i(TAG, "${file.name} reached its cap of ${file.length()} bytes; overwriting the oldest from here")
                }
                val now = clock()
                if (now - checkedAt >= SPACE_CHECK_MS) {
                    checkedAt = now
                    if (TimeshiftStorage.shouldGiveUp(freeSpace())) {
                        giveUp(TimeshiftUnavailable.StorageRanLow)
                        return
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Timeshift buffer write failed", e)
            giveUp(TimeshiftUnavailable.WriteFailed)
        }
    }

    /** Stops writing and throws the file away; the stream itself is left alone. */
    private fun giveUp(reason: TimeshiftUnavailable) {
        if (unavailable != null) return
        unavailable = reason
        Log.w(TAG, "Timeshift stopped for ${file.name}: $reason")
        buffer.dropDisk()
        Thread(::deleteFile, "timeshift-cleanup").start()
        onUnavailable?.invoke(reason)
    }

    private fun deleteFile() {
        // Renamed first: if the process dies mid-delete, the next start still finds and sweeps it.
        val closedName = File(file.parentFile, file.name.removeSuffix(TimeshiftFiles.OPEN_SUFFIX) + TimeshiftFiles.CLOSED_SUFFIX)
        val length = file.length()
        val target = if (file.renameTo(closedName)) closedName else file
        if (!target.delete() && target.exists()) Log.w(TAG, "Couldn't delete ${target.name}")
        else Log.i(TAG, "Deleted ${file.name} ($length bytes, ${buffer.head} received)")
        TimeshiftFiles.release(file)
    }

    /** Ends the session: closes the connection, stops writing and deletes the file. Safe on any thread. */
    fun close() {
        val conn = synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            connection.also { connection = null }
        }
        buffer.close()
        // Closing a connection can touch the network, and the file may be large to delete.
        Thread({
            conn?.let { runCatching { it.close() } }
            runCatching { writer.join(2_000) }
            if (unavailable == null) deleteFile()
        }, "timeshift-close").start()
    }

    val isClosed get() = synchronized(lock) { closed }

    /** The stream has been seen to be MPEG-TS. */
    val isTs get() = checkedFormat

    /** Content time at [offset]: what playback opened there will show first. */
    fun contentAt(offset: Long): Long = synchronized(lock) { index.contentAt(offset) }

    /** Where to open playback to show [contentMs], never before the oldest byte still held. */
    fun offsetFor(contentMs: Long): Long = synchronized(lock) {
        index.dropBefore(buffer.oldest)
        val o = index.offsetFor(contentMs)
        if (o < 0) buffer.oldest else o
    }

    /** What the buffer covers right now, in content time. */
    fun window(): Window = synchronized(lock) {
        index.dropBefore(buffer.oldest)
        val rate = index.byteRate(buffer.head, clock())
        if (rate > 0) lastByteRate = rate
        Window(
            oldestMs = index.firstContent,
            // The clock, not the index: entries are half a second apart.
            liveMs = maxOf(index.lastContent, tsClock.contentMs),
            capacityMs = if (rate > 0) capacity * 1000 / rate else 0L,
            bytesPerSecond = rate,
        )
    }

    data class Window(val oldestMs: Long, val liveMs: Long, val capacityMs: Long, val bytesPerSecond: Long) {
        val spanMs get() = liveMs - oldestMs
    }

    companion object {
        private const val TAG = "Timeshift"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1_500L
        private const val SPACE_CHECK_MS = 5_000L

        /** Bytes per second of the last channel that was buffered, for the settings screen. */
        @Volatile var lastByteRate = 0L

        /**
         * Starts a session, or says why there can't be one. Checks free space and creates the
         * file before any stream is read, so a full disk shows up now rather than mid-programme.
         * Does disk I/O; call off the main thread.
         */
        fun open(
            dir: File,
            configuredBytes: Long,
            upstream: Upstream,
            url: String,
            resolve: () -> String,
        ): OpenResult {
            dir.mkdirs()
            val free = dir.usableSpace
            val capacity = when (val plan = TimeshiftStorage.capacity(free, configuredBytes)) {
                is TimeshiftStorage.Result.Unavailable -> return OpenResult.Unavailable(plan.reason)
                is TimeshiftStorage.Result.Capacity -> plan.bytes
            }
            val file = TimeshiftFiles.claim(dir)
            val session = try {
                // The file grows to the cap and then wraps; it is never larger than that.
                TimeshiftSession(file, capacity, upstream, url, resolve, freeSpace = { dir.usableSpace })
            } catch (e: IOException) {
                file.delete()
                TimeshiftFiles.release(file)
                return OpenResult.Unavailable(TimeshiftUnavailable.WriteFailed)
            }
            Log.i(TAG, "Started ${file.name}: cap $capacity bytes, $free free")
            session.start()
            return OpenResult.Opened(session)
        }
    }

    sealed class OpenResult {
        data class Opened(val session: TimeshiftSession) : OpenResult()
        data class Unavailable(val reason: TimeshiftUnavailable) : OpenResult()
    }
}
