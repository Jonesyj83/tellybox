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
