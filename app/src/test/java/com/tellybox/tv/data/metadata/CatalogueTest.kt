package com.tellybox.tv.data.metadata

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CatalogueTest {
    private fun fixture(name: String) = Json.parseToJsonElement(javaClass.getResource("/metadata/$name.json")!!.readText()).jsonObject
    @Test fun playlistTitles() {
        assertEquals("3 days to kill", TitleMatcher.normalize("3 Days to Kill (2014) [WEB-DL x265]").name)
        assertEquals(2014, TitleMatcher.normalize("3 Days to Kill (2014)").year)
        assertEquals("the firm", TitleMatcher.normalize("The Firm (English-Netflix) (4K)").name)
        assertEquals("the firm", TitleMatcher.normalize("The Firm - English - Netflix HD").name)
        assertNull(TitleMatcher.normalize("2001: A Space Odyssey").year)
        assertEquals("amelie", TitleMatcher.normalize("Amélie!").name)
        assertEquals("", TitleMatcher.normalize("ENGLISH | LATEST MOVIES").name)
        for (raw in listOf("House of the Dragon S01E02", "House of the Dragon 1x02")) {
            val t = TitleMatcher.normalize(raw)
            assertEquals("house of the dragon", t.name)
            assertEquals(1, t.season)
            assertEquals(2, t.episode)
        }
    }
    @Test fun conservativeMatching() {
        fun score(a: String, b: String, year: Int? = null) = TitleMatcher.score(TitleMatcher.normalize(a), b, year)
        assertEquals(1.0, score("Amélie (2001)", "Amelie", 2002), 0.0)
        assertEquals(0.0, score("The Firm (1993)", "The Firm", 2009), 0.0)
        assertEquals(0.0, score("Alien", "Aliens"), 0.0)
        assertEquals(0.0, score("Rocky", "Rocky II"), 0.0)
        assertEquals(0.0, score("The Office", "Office Space"), 0.0)
        assertEquals(0.0, score("ENGLISH | LATEST MOVIES", "Latest Movies"), 0.0)
    }
    @Test fun tmdbFields() {
        val d = CatalogueParser.tmdb(fixture("tmdb"), false)
        assertEquals("2014", d.year)
        assertEquals(117, d.minutes)
        assertEquals("Kevin Costner", d.cast)
        assertEquals("McG", d.director)
        assertEquals("Action", d.genre)
        assertEquals("United States of America", d.country)
        assertEquals(6.1, d.rating!!, 0.0)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", d.image)
    }
    @Test fun recordedTvmaze() {
        val o = fixture("tvmaze")
        val d = CatalogueParser.tvmaze(o)
        assertEquals("2022", d.year)
        assertFalse(d.plot!!.contains('<'))
        assertTrue(d.genre!!.contains("Drama"))
        assertTrue(o.obj("_embedded").list("episodes").isNotEmpty())
    }
    @Test fun lookupRequestsAndFailures() = runTest {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            assertEquals("viewer-key", request.url.queryParameter("api_key"))
            val body = if (request.url.encodedPath.contains("search")) {
                assertEquals("2014", request.url.queryParameter("year"))
                """{"results":[{"id":207703,"title":"3 Days to Kill","release_date":"2014-02-14"}]}"""
            } else fixture("tmdb").toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val c = ChannelEntity(playlistId = 1, channelKey = "a", name = "3 Days to Kill (2014)", streamUrl = "url", kind = ContentKind.MOVIE)
        val d = CatalogueService(client).fetch(c, true, "viewer-key")!!
        assertEquals("2014", d.year)
        assertEquals(listOf("/3/search/movie", "/3/movie/207703"), paths)
        val failing = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Offline") }.build()
        assertNull(CatalogueService(failing).fetch(c, true, "viewer-key"))
        assertNull(CatalogueService(client).fetch(c.copy(name = "Another movie (2014)"), true, "viewer-key"))
    }
    @Test fun disabledAndStoredNeverUseNetwork() = runTest {
        val calls = AtomicInteger()
        val service = CatalogueService(OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("Unexpected network") }.build())
        val c = ChannelEntity(playlistId = 1, channelKey = "a", name = "The Firm", streamUrl = "url", kind = ContentKind.MOVIE)
        assertNull(service.fetch(c, false, "test"))
        assertNull(service.fetch(c.copy(plot = "Already stored"), true, "test"))
        assertNull(service.fetch(c, true, ""))
        assertEquals(0, calls.get())
    }
}
