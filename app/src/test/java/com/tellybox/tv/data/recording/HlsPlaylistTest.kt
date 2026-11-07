package com.tellybox.tv.data.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    @Test
    fun masterPicksHighestBandwidthAndResolvesRelativeUrls() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2"
            /hd/index.m3u8?token=abc
        """.trimIndent()
        val master = HlsPlaylist.parse(text, "http://host:8080/live/ch1/master.m3u8") as HlsPlaylist.Master
        assertEquals(2, master.variants.size)
        assertEquals("http://host:8080/live/ch1/low/index.m3u8", master.variants[0].url)
        assertEquals("http://host:8080/hd/index.m3u8?token=abc", master.best()!!.url)
    }

    @Test
    fun masterWithOnlySeparateAudioIsUnsupported() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            video.m3u8
        """.trimIndent()
        val master = HlsPlaylist.parse(text, "http://h/x/master.m3u8") as HlsPlaylist.Master
        assertNull(master.best())
        assertNotNull(master.unsupported)
    }

    @Test
    fun mediaPlaylistSequencesKeysAndEnd() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXTINF:6.0,
            seg120.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1",IV=0x0000000000000000000000000000002A
            #EXTINF:5.5,
            seg121.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6,
            http://cdn.example/seg122.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsPlaylist.parse(text, "http://h/live/a/index.m3u8") as HlsPlaylist.Media
        assertEquals(6, media.targetDurationSec)
        assertTrue(media.ended)
        assertNull(media.unsupported)
        assertEquals(listOf(120L, 121L, 122L), media.segments.map { it.sequence })
        assertEquals("http://h/live/a/seg120.ts", media.segments[0].url)
        assertNull(media.segments[0].key)
        val key = media.segments[1].key!!
        assertEquals("https://keys.example/k1", key.url)
        assertEquals(42, key.iv!![15].toInt())
        assertNull(media.segments[2].key)
        assertEquals("http://cdn.example/seg122.ts", media.segments[2].url)
    }

    @Test
    fun drmAndFragmentedMp4AreReported() {
        val drm = HlsPlaylist.parse(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n",
            "http://h/i.m3u8",
        ) as HlsPlaylist.Media
        assertTrue(drm.unsupported!!.contains("DRM"))
        val fmp4 = HlsPlaylist.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\na.m4s\n", "http://h/i.m3u8") as HlsPlaylist.Media
        assertTrue(fmp4.unsupported!!.contains("MP4"))
        assertFalse(fmp4.ended)
    }

    @Test
    fun defaultIvIsSequenceNumber() {
        val iv = HlsPlaylist.sequenceIv(0x0102L)
        assertArrayEquals(ByteArray(14) + byteArrayOf(1, 2), iv)
    }

    @Test
    fun attributeListKeepsQuotedCommas() {
        val a = HlsPlaylist.attributes("""BANDWIDTH=1,CODECS="avc1,mp4a",NAME=x""")
        assertEquals("avc1,mp4a", a["CODECS"])
        assertEquals("x", a["NAME"])
    }
}
