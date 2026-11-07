package com.tellybox.tv.data.parser

import com.tellybox.tv.data.parser.xtream.XtreamCredentials
import com.tellybox.tv.data.parser.xtream.XtreamMapper
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class XtreamTest {

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun credentialsFromPastedM3uLink() {
        val c = XtreamCredentials.from("http://line.example.com:8080/get.php?username=bob&password=s3cr%26t&type=m3u_plus")
        assertEquals("http://line.example.com:8080", c.server)
        assertEquals("bob", c.username)
        assertEquals("s3cr&t", c.password)
    }

    @Test
    fun credentialsFromBareHost() {
        val c = XtreamCredentials.from("example.com:25461/", "u", "p")
        assertEquals("http://example.com:25461", c.server)
        assertEquals("http://example.com:25461/live/u/p/42.ts", c.liveUrl(42))
        assertEquals("http://example.com:25461/movie/u/p/7.mkv", c.movieUrl(7, "mkv"))
        assertEquals("http://example.com:25461/series/u/p/9.mp4", c.seriesUrl(9, "mp4"))
    }

    @Test
    fun timeshiftUrlUsesServerTimezone() {
        val c = XtreamCredentials("http://h", "u", "p")
        // 2024-01-15 18:00 UTC
        val url = c.timeshiftUrl(5, 1705341600000L, 60, TimeZone.getTimeZone("Europe/Berlin"))
        assertEquals("http://h/timeshift/u/p/60/2024-01-15:19-00/5.ts", url)
    }

    @Test
    fun accountParsing() {
        val a = XtreamMapper.account(json("""
            {"user_info":{"username":"bob","auth":1,"status":"Active","exp_date":"1735689600",
             "max_connections":"2","active_cons":0,"allowed_output_formats":["m3u8","ts"]},
             "server_info":{"timezone":"Europe/London"}}
        """))
        assertTrue(a.authenticated)
        assertEquals(1735689600000L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals(listOf("m3u8", "ts"), a.allowedOutputFormats)
        assertEquals("Europe/London", a.serverTimezone)

        val rejected = XtreamMapper.account(json("""{"user_info":{"auth":0}}"""))
        assertFalse(rejected.authenticated)
        assertNull(rejected.expiresAt)
    }

    @Test
    fun liveStreamsWithMixedTypes() {
        val streams = XtreamMapper.liveStreams(json("""
            [{"num":1,"name":"News HD","stream_id":"101","stream_icon":"","epg_channel_id":"news.uk",
              "category_id":"3","tv_archive":1,"tv_archive_duration":"7","added":"1700000000"},
             {"num":"2","name":"Sport","stream_id":102,"epg_channel_id":null,"category_id":null,
              "category_ids":[5],"tv_archive":"0"},
             {"name":"broken entry without id"}]
        """))
        assertEquals(2, streams.size)
        assertEquals(101L, streams[0].streamId)
        assertNull(streams[0].icon)
        assertTrue(streams[0].tvArchive)
        assertEquals(7, streams[0].tvArchiveDays)
        assertEquals("5", streams[1].categoryId)
        assertNull(streams[1].epgChannelId)
        assertEquals(2, streams[1].num)
    }

    @Test
    fun categoriesToleratesObjectAndEmpty() {
        assertEquals(0, XtreamMapper.categories(json("[]")).size)
        assertEquals(0, XtreamMapper.categories(json("""{"user_info":{"auth":0}}""")).size)
        val cats = XtreamMapper.categories(json("""[{"category_id":"1","category_name":"UK","parent_id":0}]"""))
        assertEquals("UK", cats.single().name)
        assertNull(cats.single().parentId)
    }

    @Test
    fun seriesInfoBothEpisodeShapes() {
        val keyed = XtreamMapper.seriesInfo(json("""
            {"info":{"name":"Show"},"episodes":{"2":[{"id":"20","episode_num":1,"title":"S2E1","container_extension":"mkv"}],
             "1":[{"id":"11","episode_num":"2","title":"S1E2"},{"id":"10","episode_num":1,"title":"S1E1"}]}}
        """))
        assertEquals(listOf(10L, 11L, 20L), keyed.episodes.map { it.id })
        assertEquals(2, keyed.episodes.last().season)
        assertEquals("mkv", keyed.episodes.last().containerExtension)

        val arrays = XtreamMapper.seriesInfo(json("""
            {"episodes":[[{"id":1,"season":1,"episode_num":1,"title":"A"}]]}
        """))
        assertEquals(1, arrays.episodes.single().season)
    }

    @Test
    fun movieDetailsTolerateMissingAndMixedMetadata() {
        val details = XtreamMapper.vodInfo(json("""{"info":{"plot":"A journey", "releasedate":"2024-05-01", "duration_secs":"5400", "actors":"A, B", "director":"C"}}"""))
        assertEquals("A journey", details.plot)
        assertEquals("2024-05-01", details.year)
        assertEquals("90 min", details.duration)
        assertEquals("A, B", details.cast)
        assertEquals("C", details.director)
        assertNull(XtreamMapper.vodInfo(json("[]")).plot)
        assertNull(XtreamMapper.vodInfo(json("{}")).duration)
    }

    @Test
    fun episodeDetailsKeepResumeIdentityAndMetadata() {
        val details = XtreamMapper.seriesInfo(json("""{"episodes":{"3":[{"id":"301", "episode_num":"1", "info":{"plot":"Opening", "duration_secs":"1234", "movie_image":"http://example.com/poster.jpg"}}]}}"""))
        val episode = details.episodes.single()
        assertEquals(301L, episode.id)
        assertEquals(3, episode.season)
        assertEquals(1234, episode.durationSecs)
        assertEquals("Opening", episode.plot)
        assertEquals("http://example.com/poster.jpg", episode.image)
    }

    @Test
    fun epgListingsDecodeBase64() {
        val listings = XtreamMapper.epgListings(json("""
            {"epg_listings":[{"id":"1","epg_id":"5","title":"TmV3cyBhdCBTaXg=","description":"SGVhZGxpbmVz",
              "start":"2024-01-15 18:00:00","end":"2024-01-15 18:30:00",
              "start_timestamp":"1705341600","stop_timestamp":"1705343400","has_archive":1}]}
        """))
        val l = listings.single()
        assertEquals("News at Six", l.title)
        assertEquals("Headlines", l.description)
        assertEquals(1705341600000L, l.start)
        assertEquals(30 * 60_000L, l.stop - l.start)
        assertTrue(l.hasArchive)
    }

    @Test
    fun plainTextTitlesAreLeftAlone() {
        assertEquals("Live Football", XtreamMapper.decode("Live Football"))
        assertEquals("News", XtreamMapper.decode("News"))
    }
}
