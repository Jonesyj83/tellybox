package com.opentelly.tv.data.recording

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Saves a stream to a file without decoding it.
 *
 * - Plain HTTP streams (MPEG-TS from most Xtream/M3U providers, or a single archive file)
 *   are copied byte for byte.
 * - HLS is followed segment by segment: live playlists are re-polled and only new segments
 *   are appended, starting near the live edge; finished (ENDLIST) playlists are fetched in
 *   full. TS segments are concatenated into one .ts file, decrypting AES-128 on the way.
 *
 * Dropped connections are retried with backoff until the deadline. The output is opened
 * in append mode so an interrupted recording can carry on into the same file.
 *
 * [record] blocks; call [stop] from another thread to end it early.
 */
class StreamRecorder(
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val http = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    class Request(
        val url: String,
        val output: File,
        /** Wall-clock time to stop at. */
        val stopAt: Long,
        /** True for archive downloads: reaching the end of the stream means the job is done. */
        val finite: Boolean,
        val userAgent: String? = null,
        val referrer: String? = null,
        val onProgress: (Long) -> Unit = {},
    )

    /** [bytes] is the file size at the end, including anything recorded before a resume. */
    data class Result(val bytes: Long, val error: String?, val stoppedByUser: Boolean)

    /** A problem retrying won't fix. */
    class UnrecordableException(message: String) : IOException(message)

    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    private var written = 0L
    private var lastSequence = -1L
    private val keys = HashMap<String, ByteArray>()

    fun stop() {
        stopped = true
        call?.cancel()
    }

    private fun finished(req: Request) = stopped || clock() >= req.stopAt

    fun record(req: Request): Result {
        val scheme = req.url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result(req.output.length(), "Only http and https streams can be recorded" +
                if (scheme.isNotEmpty()) " (this one is $scheme)" else "", false)
        }
        req.output.parentFile?.mkdirs()
        written = req.output.length()
        val deadline = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "recording-deadline").apply { isDaemon = true } }
        // A blocked read only notices the deadline if the connection is torn down.
        deadline.schedule({ call?.cancel() }, (req.stopAt - clock()).coerceAtLeast(0), TimeUnit.MILLISECONDS)

        var error: String? = null
        var failures = 0
        try {
            BufferedOutputStream(FileOutputStream(req.output, true), 256 * 1024).use { out ->
                while (!finished(req)) {
                    val before = written
                    try {
                        val done = attempt(req, out)
                        out.flush()
                        error = null
                        if (done) break
                    } catch (e: UnrecordableException) {
                        error = e.message
                        break
                    } catch (e: IOException) {
                        out.flush()
                        if (finished(req)) break
                        error = describe(e)
                    }
                    failures = if (written > before) 0 else failures + 1
                    // Keep trying a live recording that has started until its window ends; give
                    // up on one that never got going, and on archives that keep failing.
                    if (failures >= MAX_FAILURES && (written == 0L || req.finite)) break
                    if (finished(req)) break
                    pause(backoff(failures))
                }
            }
        } catch (e: IOException) {
            error = describe(e)
        } finally {
            deadline.shutdownNow()
            call = null
        }
        if (written == 0L && error == null) error = if (stopped) null else "The stream sent no data"
        return Result(written, error, stopped)
    }

    /** One connection's worth of work. Returns true when there is nothing more to fetch. */
    private fun attempt(req: Request, out: OutputStream): Boolean {
        execute(req.url, req).use { resp ->
            val source = resp.body?.source() ?: throw IOException("Empty response")
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val peek = source.peek()
            peek.request(16)
            val head = peek.readUtf8(minOf(peek.buffer.size, 16L))
            if ("mpegurl" in type || HlsPlaylist.looksLikePlaylist(head)) {
                return recordHls(resp.request.url.toString(), source.readUtf8(), req, out)
            }
            if ("text/html" in type) throw IOException("The server sent a web page instead of a stream")
            copy(source, req, out)
            return req.finite && !finished(req)
        }
    }

    private fun copy(source: BufferedSource, req: Request, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        val input = source.inputStream()
        var reported = written
        while (!finished(req)) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            written += n
            if (written - reported >= 1024 * 1024) {
                out.flush()
                reported = written
                req.onProgress(written)
            }
        }
        out.flush()
        req.onProgress(written)
    }

    private fun recordHls(playlistUrl: String, text: String, req: Request, out: OutputStream): Boolean {
        var url = playlistUrl
        var parsed = HlsPlaylist.parse(text, url)
        if (parsed is HlsPlaylist.Master) {
            val variant = parsed.best()
                ?: throw UnrecordableException(parsed.unsupported ?: "The stream's playlist has nothing to record")
            val (finalUrl, media) = fetchPlaylist(variant.url, req)
            url = finalUrl
            parsed = media
        }
        var media = parsed as? HlsPlaylist.Media ?: throw UnrecordableException("Nested HLS playlists aren't supported")
        var lastProgressAt = clock()
        while (true) {
            media.unsupported?.let { throw UnrecordableException(it) }
            val newest = media.segments.lastOrNull()?.sequence
            if (lastSequence < 0) {
                // Join a live stream near its edge rather than pulling its whole time-shift window.
                lastSequence = if (media.ended || req.finite || media.segments.size <= LIVE_EDGE_SEGMENTS) media.mediaSequence - 1
                else media.segments[media.segments.size - LIVE_EDGE_SEGMENTS].sequence - 1
            } else if (newest != null && newest + LIVE_EDGE_SEGMENTS < lastSequence - media.segments.size) {
                // Sequence numbers went backwards: the provider restarted the stream.
                lastSequence = newest - 1
            }
            for (segment in media.segments) {
                if (segment.sequence <= lastSequence) continue
                if (finished(req)) return false
                if (downloadSegment(segment, req, out)) lastProgressAt = clock()
                lastSequence = segment.sequence
            }
            if (media.ended) return true
            if (finished(req)) return false
            val target = media.targetDurationSec * 1000L
            if (clock() - lastProgressAt > maxOf(STALL_MS, target * 4)) {
                throw IOException("The stream stopped sending new video")
            }
            pause((target / 2).coerceIn(500, 10_000))
            if (finished(req)) return false
            media = fetchPlaylist(url, req).second as? HlsPlaylist.Media
                ?: throw IOException("The stream's playlist changed unexpectedly")
        }
    }

    private fun fetchPlaylist(url: String, req: Request): Pair<String, HlsPlaylist> =
        execute(url, req).use { resp ->
            val text = resp.body?.string() ?: throw IOException("Empty playlist")
            if (!HlsPlaylist.looksLikePlaylist(text)) throw IOException("The server didn't send an HLS playlist")
            val finalUrl = resp.request.url.toString()
            finalUrl to HlsPlaylist.parse(text, finalUrl)
        }

    /** Returns true if the segment made it into the file. One bad segment doesn't end a recording. */
    private fun downloadSegment(segment: HlsPlaylist.Segment, req: Request, out: OutputStream): Boolean {
        repeat(2) { tryIndex ->
            try {
                val data = execute(segment.url, req).use { it.body?.bytes() ?: throw IOException("Empty segment") }
                val clear = segment.key?.let { decrypt(data, it, segment.sequence, req) } ?: data
                out.write(clear)
                out.flush()
                written += clear.size
                req.onProgress(written)
                return true
            } catch (e: UnrecordableException) {
                throw e
            } catch (e: IOException) {
                if (finished(req)) throw e
                if (tryIndex == 0) pause(500)
            }
        }
        return false
    }

    private fun decrypt(data: ByteArray, key: HlsPlaylist.Key, sequence: Long, req: Request): ByteArray {
        val keyUrl = key.url ?: throw UnrecordableException("The stream is encrypted but names no key")
        val keyBytes = keys.getOrPut(keyUrl) { execute(keyUrl, req).use { it.body?.bytes() ?: ByteArray(0) } }
        if (keyBytes.size != 16) {
            keys.remove(keyUrl)
            throw UnrecordableException("Couldn't get a usable decryption key for the stream")
        }
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(key.iv ?: HlsPlaylist.sequenceIv(sequence)))
                doFinal(data)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("Couldn't decrypt a segment", e)
        }
    }

    private fun execute(url: String, req: Request): Response {
        val builder = okhttp3.Request.Builder().url(url)
        if (!req.userAgent.isNullOrBlank()) builder.header("User-Agent", req.userAgent)
        if (!req.referrer.isNullOrBlank()) builder.header("Referer", req.referrer)
        val c = http.newCall(builder.build())
        call = c
        if (finished(req)) c.cancel()
        val resp = c.execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(httpMessage(resp.code))
        }
        return resp
    }

    companion object {
        private const val MAX_FAILURES = 5
        private const val LIVE_EDGE_SEGMENTS = 3
        private const val STALL_MS = 45_000L

        fun backoff(failures: Int): Long = (2_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(30_000L)

        fun httpMessage(code: Int): String = when (code) {
            401, 403 -> "The server refused the stream (HTTP $code). Some providers allow only one connection at a time."
            404, 410 -> "The stream wasn't found on the server (HTTP $code)"
            429, 458, 509 -> "The provider's connection limit was reached (HTTP $code)"
            else -> "The server returned HTTP $code"
        }

        fun describe(e: IOException): String {
            val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            return when {
                "ENOSPC" in text || "No space left" in text -> "Storage is full"
                e is UnknownHostException -> "Couldn't reach the server"
                e is SocketTimeoutException -> "The server stopped responding"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}
