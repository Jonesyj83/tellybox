package com.tellybox.tv.data.recording

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class StreamRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: TestHttpServer
    private lateinit var base: String
    private val http = OkHttpClient()
    private val noPause: (Long) -> Unit = {}

    @Before
    fun start() {
        server = TestHttpServer()
        base = server.base
    }

    @After
    fun stop() = server.close()

    private fun serve(path: String, handler: (TestHttpServer.Exchange) -> Unit) = server.route(path, handler)

    private val m3u8 = "application/vnd.apple.mpegurl"

    @Test
    fun archiveFileIsCopiedWhole() {
        val data = Random(1).nextBytes(300_000)
        serve("/timeshift/1.ts") { it.send(data) }
        val out = tmp.root.resolve("a.ts")
        val result = StreamRecorder(http, pause = noPause).record(
            StreamRecorder.Request("$base/timeshift/1.ts", out, System.currentTimeMillis() + 30_000, finite = true),
        )
        assertNull(result.error)
        assertEquals(data.size.toLong(), result.bytes)
        assertArrayEquals(data, out.readBytes())
    }

    @Test
    fun finishedHlsPlaylistIsConcatenatedFromBestVariant() {
        serve("/master.m3u8") {
            it.send("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhi/index.m3u8\n".toByteArray(), m3u8)
        }
        serve("/low.m3u8") { it.send(ByteArray(0), m3u8, 500) }
        serve("/hi/index.m3u8") {
            it.send("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:2,\ns7.ts\n#EXTINF:2,\ns8.ts\n#EXTINF:2,\ns9.ts\n#EXT-X-ENDLIST\n".toByteArray(), m3u8)
        }
        serve("/hi/") { ex -> ex.send("[${ex.path.substringAfterLast('/')}]".toByteArray()) }
        val out = tmp.root.resolve("b.ts")
        val result = StreamRecorder(http, pause = noPause).record(
            StreamRecorder.Request("$base/master.m3u8", out, System.currentTimeMillis() + 30_000, finite = true),
        )
        assertNull(result.error)
        assertEquals("[s7.ts][s8.ts][s9.ts]", out.readText())
    }

    @Test
    fun aes128SegmentsAreDecrypted() {
        val key = Random(2).nextBytes(16)
        fun enc(plain: ByteArray, seq: Long): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(HlsPlaylist.sequenceIv(seq)))
            doFinal(plain)
        }
        val plain = listOf(Random(3).nextBytes(5000), Random(4).nextBytes(188 * 7))
        serve("/enc.m3u8") {
            it.send("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nseg/3.ts\n#EXTINF:4,\nseg/4.ts\n#EXT-X-ENDLIST\n".toByteArray(), "audio/mpegurl")
        }
        serve("/key.bin") { it.send(key, "application/octet-stream") }
        serve("/seg/") { ex ->
            val seq = ex.path.substringAfterLast('/').removeSuffix(".ts").toLong()
            ex.send(enc(plain[(seq - 3).toInt()], seq))
        }
        val out = tmp.root.resolve("c.ts")
        val result = StreamRecorder(http, pause = noPause).record(
            StreamRecorder.Request("$base/enc.m3u8", out, System.currentTimeMillis() + 30_000, finite = true),
        )
        assertNull(result.error)
        assertArrayEquals(plain[0] + plain[1], out.readBytes())
    }

    @Test
    fun missingStreamFailsWithReasonAndNoFileContent() {
        val hits = AtomicInteger()
        serve("/gone.ts") { hits.incrementAndGet(); it.send(ByteArray(0), "text/plain", 404) }
        val out = tmp.root.resolve("d.ts")
        val result = StreamRecorder(http, pause = noPause).record(
            StreamRecorder.Request("$base/gone.ts", out, System.currentTimeMillis() + 30_000, finite = false),
        )
        assertEquals(0L, result.bytes)
        assertTrue(result.error!!.contains("404"))
        assertEquals(5, hits.get())
    }

    @Test
    fun drmStreamIsRefusedWithoutRetrying() {
        val hits = AtomicInteger()
        serve("/drm.m3u8") {
            hits.incrementAndGet()
            it.send("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\"\n#EXTINF:6,\na.ts\n".toByteArray(), m3u8)
        }
        val result = StreamRecorder(http, pause = noPause).record(
            StreamRecorder.Request("$base/drm.m3u8", tmp.root.resolve("e.ts"), System.currentTimeMillis() + 30_000, finite = false),
        )
        assertTrue(result.error!!.contains("DRM"))
        assertEquals(1, hits.get())
    }

    @Test
    fun nonHttpStreamsAreRefused() {
        val result = StreamRecorder(http).record(
            StreamRecorder.Request("rtmp://host/live/x", tmp.root.resolve("f.ts"), System.currentTimeMillis() + 1000, finite = false),
        )
        assertTrue(result.error!!.contains("rtmp"))
    }

    @Test
    fun liveStreamStopsAtDeadlineAndAppendsToExistingFile() {
        serve("/live.ts") { ex ->
            val body = ex.startStream()
            try {
                repeat(1000) {
                    body.write(ByteArray(188) { 0x47 })
                    body.flush()
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
            }
        }
        val out = tmp.root.resolve("g.ts").apply { writeText("OLD") }
        val started = System.currentTimeMillis()
        val result = StreamRecorder(http).record(
            StreamRecorder.Request("$base/live.ts", out, started + 1_500, finite = false),
        )
        val took = System.currentTimeMillis() - started
        assertNull(result.error)
        assertTrue("took $took ms", took in 1_400..4_000)
        assertTrue(result.bytes > 1000)
        assertEquals("OLD", out.readBytes().copyOfRange(0, 3).decodeToString())
        assertEquals(out.length(), result.bytes)
    }

    @Test
    fun liveHlsJoinsNearEdgeAndNeverRepeatsSegments() {
        val polls = AtomicInteger()
        serve("/live/index.m3u8") { ex ->
            val n = polls.getAndIncrement()
            val body = buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$n\n")
                for (s in n until n + 5) append("#EXTINF:1,\n$s.ts\n")
            }
            ex.send(body.toByteArray(), m3u8)
        }
        serve("/live/") { ex -> ex.send("S${ex.path.substringAfterLast('/').removeSuffix(".ts")};".toByteArray()) }
        val out = tmp.root.resolve("h.ts")
        val result = StreamRecorder(http).record(
            StreamRecorder.Request("$base/live/index.m3u8", out, System.currentTimeMillis() + 2_200, finite = false),
        )
        assertNull(result.error)
        val segs = out.readText().split(';').filter { it.isNotEmpty() }.map { it.removePrefix("S").toInt() }
        assertEquals(listOf(2, 3, 4), segs.take(3))
        assertEquals(segs.indices.map { it + 2 }, segs)
        assertTrue(segs.size >= 5)
    }

    @Test
    fun stopEndsRecordingEarly() {
        serve("/slow.ts") { ex ->
            val body = ex.startStream()
            try {
                repeat(1000) { body.write(ByteArray(100)); body.flush(); Thread.sleep(20) }
            } catch (_: Exception) {
            }
        }
        val recorder = StreamRecorder(http)
        Thread { Thread.sleep(500); recorder.stop() }.start()
        val started = System.currentTimeMillis()
        val result = recorder.record(
            StreamRecorder.Request("$base/slow.ts", tmp.root.resolve("i.ts"), started + 60_000, finite = false),
        )
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertTrue(result.stoppedByUser)
        assertTrue(result.bytes > 0)
        assertNull(result.error)
    }
}
