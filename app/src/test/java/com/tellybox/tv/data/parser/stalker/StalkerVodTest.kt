package com.tellybox.tv.data.parser.stalker

import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.parser.objects
import com.tellybox.tv.data.repository.StalkerImport
import com.tellybox.tv.data.repository.StalkerImportResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Driven by responses captured from a real portal, under src/test/resources/stalker. */
class StalkerVodTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/stalker/$name")) { "missing fixture $name" }.readText()

    private fun js(name: String): JsonElement? = (Json.parseToJsonElement(fixture(name)) as JsonObject)["js"]

    private val base = "http://portal.example:8080/stalker_portal/c/".toHttpUrl()

    // --- parsing --------------------------------------------------------------------------

    @Test
    fun categoriesSkipAllAndFlagCensored() {
        val cats = StalkerVodMapper.categories(js("real-vod-get-categories.json"))
        assertEquals(152, cats.size) // 153 entries, less the "*" pseudo-category
        assertTrue(cats.none { it.id == "*" })
        assertEquals("ENGLISH | LATEST MOVIES 4K UHD", cats.single { it.id == "5" }.title)
        assertEquals("ENGLISH | SERIES", cats.single { it.id == "42" }.title)
        assertFalse(cats.single { it.id == "5" }.censored)
        assertEquals(setOf("96", "73", "114", "118", "116", "143", "148", "149", "153"),
            cats.filter { it.censored }.map { it.id }.toSet())
        assertEquals("ADULT | GENERAL", cats.single { it.id == "73" }.title)
    }

    @Test
    fun moviesPage() {
        val page = StalkerVodMapper.page(js("real-movies-cat-5.json"), base)
        assertEquals(3759, page.totalItems) // sent as the string "3759"
        assertEquals(14, page.items.size)
        val first = page.items.first()
        assertEquals("491960", first.id)
        assertEquals("Hunting Matthew Nichols (English)", first.name)
        assertFalse(first.isSeries)
        assertEquals("2026", first.year)
        assertEquals(89, first.durationMinutes)
        assertEquals("Markian Tarasiuk", first.director)
        assertEquals("Canada, United States of America", first.country)
        assertEquals("ENGLISH | LATEST MOVIES 4K UHD", first.genres)
        assertTrue(first.description!!.startsWith("Twenty-three years after"))
        assertNull(first.rating) // rating_imdb 0 means unrated
        assertEquals("http://portal.example:8080/stalker_portal/screenshots/5899/589804.jpg", first.poster)
        assertNull(page.items[1].durationMinutes) // "time": ""
    }

    @Test
    fun seriesPage() {
        val page = StalkerVodMapper.page(js("real-series-cat-42.json"), base)
        assertEquals(7777, page.totalItems)
        assertEquals(14, page.items.size)
        assertTrue(page.items.all { it.isSeries })
        val dragon = page.items.single { it.id == "273000" }
        assertEquals("House of the Dragon (English-HBO)", dragon.name)
        assertEquals(6.1, dragon.rating!!, 0.001)
        assertEquals("2022", dragon.year)
    }

    @Test
    fun seriesAreToldFromMoviesByIsSeriesNotIsMovie() {
        // Both fixtures say is_movie:true on every item, series included.
        listOf("real-movies-cat-5.json", "real-series-cat-42.json").forEach { name ->
            val raw = (js(name) as JsonObject)["data"].objects()
            assertTrue(raw.all { it["is_movie"].toString() == "true" })
        }
        assertTrue(StalkerVodMapper.page(js("real-movies-cat-5.json"), base).items.none { it.isSeries })
        assertTrue(StalkerVodMapper.page(js("real-series-cat-42.json"), base).items.all { it.isSeries })

        val rows = StalkerVodMapper.page(js("real-series-cat-42.json"), base).items.take(1) +
            StalkerVodMapper.page(js("real-movies-cat-5.json"), base).items.take(1)
        val mapped = rows.mapIndexed { i, it -> StalkerImport.row(7, it, i) }
        assertEquals(ContentKind.SERIES, mapped[0].kind)
        assertEquals("stalker://series/290603", mapped[0].streamUrl)
        assertEquals(ContentKind.MOVIE, mapped[1].kind)
        assertEquals("stalker://movie/491960", mapped[1].streamUrl)
        // The listing's fake "/media/491960.mpg" is never stored as something to play.
        assertTrue(mapped.none { it.streamUrl.contains("/media/") })
    }

    @Test
    fun seasons() {
        val seasons = StalkerVodMapper.seasons(js("real-seasons-273000.json"))
        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals(listOf("137774", "158164", "144224"), seasons.map { it.id })
        assertEquals("Season 3", seasons.last().name)
    }

    @Test
    fun episodes() {
        val episodes = StalkerVodMapper.episodes(js("real-episodes-144224.json"))
        assertEquals((1..8).toList(), episodes.map { it.number })
        assertTrue(episodes.all { it.seasonId == "144224" })
        assertEquals("2986168", episodes.last().id)
        assertEquals("Episode 8. 2026-08-10", episodes.last().name)
    }

    @Test
    fun markerRoundTrip() {
        val refs = listOf(
            StalkerVodRef.Movie("491960"),
            StalkerVodRef.Series("273000"),
            StalkerVodRef.Episode("273000", "144224", "2986168"),
        )
        assertEquals(listOf("stalker://movie/491960", "stalker://series/273000", "stalker://episode/273000/144224/2986168"),
            refs.map { it.marker })
        refs.forEach { assertEquals(it, StalkerVodRef.parse(it.marker)) }
        assertNull(StalkerVodRef.parse("http://host/movie/1"))
        assertNull(StalkerVodRef.parse("stalker://movie/"))
        assertNull(StalkerVodRef.parse("stalker://episode/1/2"))
        assertNull(StalkerVodRef.parse("stalker://trailer/1"))
        assertFalse(StalkerVodRef.isMarker("ffmpeg http://localhost/ch/10_"))
    }

    @Test
    fun realUrlComesFromFirstDataCmd() {
        val movie = StalkerVodMapper.streamCmd(js("real-movie-491960.json"))
        assertEquals("http://89.187.181.21/CF5A68754DSTZZZ16867aca8615f56dca544994788cc750476a4ebe6bc3ea4b538676d9f16872cedaabbf517ca749c89dd8814676faa4cc65bfc667d5f83516029c0a07600b709df/index.m3u8",
            movie)
        val episode = StalkerVodMapper.streamCmd(js("real-episode-2986168.json"))
        assertTrue(episode!!.startsWith("http://95.173.206.33/") && episode.endsWith("/index.m3u8"))
        assertNull(StalkerVodMapper.streamCmd(Json.parseToJsonElement("""{"total_items":"0","data":[]}""")))
    }

    @Test
    fun pagingStopsAtTotalAtEmptyPageAndAtCap() = runBlocking {
        var calls = 0
        val byTotal = collectPages { p -> calls++; 30 to List(14) { "$p-$it" } }
        assertEquals(3, calls) // 14 + 14 + 14 >= 30
        assertEquals(42, byTotal.size)

        calls = 0
        collectPages { p -> calls++; null to (if (p < 4) listOf(p) else emptyList()) }
        assertEquals(4, calls)

        calls = 0
        collectPages { p -> calls++; 1_000_000 to listOf(p) }
        assertEquals(MAX_PAGES, calls)

        // A later page failing keeps what came before.
        val partial = collectPages { p -> if (p == 3) throw java.io.IOException("boom") else 100 to listOf(p) }
        assertEquals(listOf(1, 2), partial)
    }

    // --- against a portal speaking the captured responses -------------------------------------

    private val servers = ArrayList<FakePortal>()

    @After
    fun stopServers() = servers.forEach { it.close() }

    private fun portal(failVod: Boolean = false): FakePortal = FakePortal { q ->
        val empty = """{"js":{"total_items":"0","max_page_items":14,"data":[]}}"""
        when {
            q["action"] == "handshake" -> 200 to """{"js":{"token":"session"}}"""
            q["action"] == "get_profile" -> 200 to """{"js":{}}"""
            q["type"] == "itv" && q["action"] == "get_genres" ->
                200 to """{"js":[{"id":"*","title":"All"},{"id":"3","title":"News"}]}"""
            q["type"] == "itv" && q["action"] == "get_all_channels" ->
                200 to """{"js":{"total_items":1,"data":[{"id":"10","name":"One","number":"1","cmd":"ffmpeg http://localhost/ch/10_","tv_genre_id":"3"}]}}"""
            q["type"] == "vod" && failVod -> 500 to "Internal Server Error"
            q["action"] == "create_link" ->
                200 to """{"js":{"cmd":"${q["cmd"]?.replace("\"", "")}"}}"""
            q["action"] == "get_categories" -> 200 to fixture("real-vod-get-categories.json")
            q["action"] != "get_ordered_list" -> 404 to ""
            q["episode_id"] != null -> 200 to fixture("real-episode-2986168.json")
            q["season_id"] == "0" -> 200 to fixture("real-seasons-273000.json")
            q["season_id"] == "144224" -> 200 to fixture("real-episodes-144224.json")
            q["season_id"] != null -> 200 to empty
            q["movie_id"] == "491960" -> 200 to fixture("real-movie-491960.json")
            q["p"] != "1" -> 200 to empty
            q["category"] == "5" -> 200 to fixture("real-movies-cat-5.json")
            q["category"] == "42" -> 200 to fixture("real-series-cat-42.json")
            else -> 200 to empty
        }
    }.also { servers += it }


    /** Live plus the whole film walk, the way the old one-call import did it. */
    private suspend fun importAll(client: StalkerClient, playlistId: Long, hideCensored: Boolean): StalkerImportResult {
        val live = StalkerImport.live(client, playlistId)
        val vod = try {
            StalkerImport.loadVod(playlistId, hideCensored, client.vodCategories(), fetch = client::vodItemsResult)
        } catch (e: Exception) {
            StalkerImportResult(emptyList(), emptyList(), e.message ?: e.javaClass.simpleName)
        }
        return StalkerImportResult(live.channels + vod.channels, live.groups + vod.groups, vod.vodError, vod.completion)
    }

    private fun client(p: FakePortal) = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())

    @Test
    fun movieResolvesThroughMovieIdLookup() = runBlocking {
        val p = portal()
        val url = client(p).resolve(StalkerVodRef.parse("stalker://movie/491960")!!)
        assertTrue(url.endsWith("/index.m3u8"))
        assertTrue(url.startsWith("http://89.187.181.21/"))
        val lookup = p.requests.last { it["action"] == "get_ordered_list" }
        assertEquals("491960", lookup["movie_id"])
        assertNull(lookup["category"])
        // The link is exchanged before it is played.
        assertEquals("create_link", p.requests.last()["action"])
    }

    @Test
    fun seriesWalksSeasonsThenEpisodesThenResolvesOne() = runBlocking {
        val p = portal()
        val c = client(p)
        val seasons = c.seasons("273000")
        assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        assertEquals("273000", p.requests.last()["movie_id"])

        val season3 = seasons.single { it.number == 3 }
        val episodes = c.episodes("273000", season3.id)
        assertEquals(8, episodes.size)
        assertEquals("144224", p.requests.last()["season_id"])

        val ref = StalkerVodRef.Episode("273000", season3.id, episodes.last().id)
        assertEquals("stalker://episode/273000/144224/2986168", ref.marker)
        val url = c.resolve(StalkerVodRef.parse(ref.marker)!!)
        assertTrue(url.startsWith("http://95.173.206.33/") && url.endsWith("/index.m3u8"))
        val lookup = p.requests.last { it["action"] == "get_ordered_list" }
        assertEquals(listOf("273000", "144224", "2986168"),
            listOf(lookup["movie_id"], lookup["season_id"], lookup["episode_id"]))
    }

    @Test
    fun importStoresMarkersAndNeverResolvesTitles() = runBlocking {
        val p = portal()
        val c = client(p).also { it.connect() }
        val result = importAll(c, playlistId = 1, hideCensored = false)

        val movies = result.channels.filter { it.kind == ContentKind.MOVIE }
        val series = result.channels.filter { it.kind == ContentKind.SERIES }
        assertEquals(14, movies.size)
        assertEquals(14, series.size)
        assertTrue(movies.all { it.channel.streamUrl.startsWith("stalker://movie/") })
        assertTrue(series.all { it.channel.streamUrl.startsWith("stalker://series/") })
        assertTrue(movies.all { it.groupName == "ENGLISH | LATEST MOVIES 4K UHD" })
        assertNotNull(result.groups.singleOrNull { it.kind == ContentKind.MOVIE && it.remoteId == "5" })
        assertNotNull(result.groups.singleOrNull { it.kind == ContentKind.SERIES && it.remoteId == "42" })
        assertEquals(1, result.channels.count { it.kind == ContentKind.LIVE })
        assertNull(result.vodError)

        val ordered = p.requests.filter { it["action"] == "get_ordered_list" }
        // Metadata only: no title was looked up for its stream during the import.
        assertTrue(ordered.none { it["movie_id"] != null })
        // Paged until an empty page: category 5 claims 3759 items but page 2 is empty.
        assertEquals(listOf("1", "2"), ordered.filter { it["category"] == "5" }.map { it["p"] })
        // With no PIN, adult categories are fetched like any other.
        assertTrue(ordered.any { it["category"] == "73" })
    }

    @Test
    fun aPortalThatListsNoVodCategoriesIsNotTreatedAsEmpty() = runBlocking {
        val result = StalkerImport.loadVod(1, false, emptyList()) { _, _ -> error("must not fetch") }

        assertEquals(0, result.channels.size)
        assertNotNull(result.vodError)
        // PARTIAL keeps the films already stored instead of pruning them away.
        assertEquals(LoadCompletion.PARTIAL, result.completion.kinds[ContentKind.MOVIE])
        assertEquals(LoadCompletion.PARTIAL, result.completion.kinds[ContentKind.SERIES])
    }

    @Test
    fun liveImportNeverAsksForVod() = runBlocking {
        val p = portal()
        val c = client(p).also { it.connect() }
        val result = StalkerImport.live(c, playlistId = 1)

        assertEquals(listOf("One"), result.channels.map { it.channel.name })
        assertEquals(listOf("News"), result.groups.map { it.name })
        assertEquals(LoadCompletion.FULL, result.completion.kinds[ContentKind.LIVE])
        // Movies and series are a separate pass, so nothing here may touch them.
        val vod = p.requests.filter { it["type"] == "vod" }
        assertTrue(vod.isEmpty())
    }

    @Test
    fun censoredCategoriesAreSkippedWhenAPinIsSet() = runBlocking {
        val p = portal()
        val c = client(p).also { it.connect() }
        importAll(c, playlistId = 1, hideCensored = true)
        val asked = p.requests.filter { it["action"] == "get_ordered_list" }.mapNotNull { it["category"] }.toSet()
        listOf("96", "73", "114", "118", "116", "143", "148", "149", "153").forEach { assertFalse(it in asked) }
        assertTrue("5" in asked && "42" in asked)
    }

    @Test
    fun portalThatErrorsOnVodStillImportsLive() = runBlocking {
        val p = portal(failVod = true)
        val c = client(p).also { it.connect() }
        val result = importAll(c, playlistId = 1, hideCensored = false)
        assertEquals(listOf("One"), result.channels.map { it.channel.name })
        assertTrue(result.channels.all { it.kind == ContentKind.LIVE })
        assertEquals(listOf("News"), result.groups.map { it.name })
        assertNotNull(result.vodError)
        assertTrue(p.requests.any { it["type"] == "vod" })
    }
}

/** A tiny HTTP server answering each request from its query string, one connection at a time. */
class FakePortal(private val route: (Map<String, String>) -> Pair<Int, String>) : AutoCloseable {
    /** Extra response headers, for redirects and the like. */
    var headers: (Map<String, String>) -> Map<String, String> = { emptyMap() }
    private val server = ServerSocket(0)
    val port get() = server.localPort
    val requests = CopyOnWriteArrayList<Map<String, String>>()

    private val worker = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: java.io.IOException) { break }
            socket.use {
                val input = it.getInputStream().bufferedReader()
                val line = input.readLine() ?: return@use
                while (!input.readLine().isNullOrEmpty()) { }
                val url = ("http://localhost" + line.split(' ')[1]).toHttpUrl()
                val q = url.queryParameterNames.associateWith { name -> url.queryParameter(name).orEmpty() }
                requests += q
                val (code, body) = route(q)
                val bytes = body.toByteArray()
                val extra = headers(q).entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
                it.getOutputStream().write(
                    ("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n$extra" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
                )
            }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        server.close()
        worker.join(1000)
    }
}
