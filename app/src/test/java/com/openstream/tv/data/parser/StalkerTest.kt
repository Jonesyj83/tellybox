package com.openstream.tv.data.parser

import com.openstream.tv.data.parser.stalker.StalkerClient
import com.openstream.tv.data.parser.stalker.StalkerMapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerTest {

    @Test
    fun archiveLinkUsesArchiveEndpoint() = kotlinx.coroutines.runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = java.net.ServerSocket(0)
        val worker = Thread {
            repeat(3) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val query = input.readLine()
                    requests += query
                    while (!input.readLine().isNullOrEmpty()) { }
                    val body = when {
                        "action=handshake" in query -> """{"js":{"token":"session"}}"""
                        "action=get_profile" in query -> """{"js":{}}"""
                        else -> """{"js":{"cmd":"ffmpeg http://archive.example/video.ts"}}"""
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val client = StalkerClient("http://127.0.0.1:${server.localPort}/portal.php",
                "00:1A:79:AB:CD:EF", okhttp3.OkHttpClient())
            assertEquals("http://archive.example/video.ts", client.createArchiveLink("auto http://localhost/ch/1", 100000, 220000))
            val query = requests.last()
            assertTrue(query.contains("type=tv_archive"))
            assertTrue(query.contains("action=create_link"))
            assertTrue(query.contains("utc=100"))
            assertTrue(query.contains("lutc=220"))
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun macNormalisation() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac("00-1a-79-ab-cd-ef"))
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac("001A79ABCDEF"))
        assertTrue(StalkerClient.isValidMac("00:1A:79:AB:CD:EF"))
        assertFalse(StalkerClient.isValidMac("00:1A:79:AB:CD"))
        assertFalse(StalkerClient.isValidMac("00:1A:79:AB:CD:XZ"))
    }

    @Test
    fun endpointCandidates() {
        val c = StalkerClient.endpointCandidates("http://portal.example:8080/stalker_portal/c/").map { it.toString() }
        assertEquals("http://portal.example:8080/stalker_portal/server/load.php", c.first())

        val plain = StalkerClient.endpointCandidates("portal.example/c/").map { it.toString() }
        assertEquals("http://portal.example/portal.php", plain.first())

        val direct = StalkerClient.endpointCandidates("http://p.example/portal.php?x=1").map { it.toString() }
        assertEquals(listOf("http://p.example/portal.php"), direct)
    }

    @Test
    fun cmdToUrl() {
        assertEquals("http://h/ch/1", StalkerMapper.urlFromCmd("ffmpeg http://h/ch/1"))
        assertEquals("http://h/ch/1?a=b", StalkerMapper.urlFromCmd("auto http://h/ch/1?a=b"))
        assertEquals("rtmp://h/live", StalkerMapper.urlFromCmd("rtmp://h/live"))
        assertNull(StalkerMapper.urlFromCmd("nothing here"))
    }

    @Test
    fun channelsAndGenres() {
        val genres = StalkerMapper.genres(Json.parseToJsonElement(
            """[{"id":"*","title":"All"},{"id":"3","title":"News"}]""",
        ))
        assertEquals(listOf("News"), genres.map { it.title })

        val channels = StalkerMapper.channels(Json.parseToJsonElement(
            """{"total_items":2,"data":[
                 {"id":"10","name":"One","number":"1","cmd":"ffmpeg http://localhost/ch/10_","tv_genre_id":"3","tv_archive":"1","tv_archive_duration":"72"},
                 {"id":"11","name":"No cmd"}]}""",
        ))
        val one = channels.single()
        assertEquals("One", one.name)
        assertEquals(1, one.number)
        assertTrue(one.hasArchive)
        assertEquals(3, one.archiveDays)
    }
}
