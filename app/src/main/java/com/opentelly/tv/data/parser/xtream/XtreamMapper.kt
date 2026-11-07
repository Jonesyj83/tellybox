package com.opentelly.tv.data.parser.xtream

import com.opentelly.tv.data.parser.arr
import com.opentelly.tv.data.parser.bool
import com.opentelly.tv.data.parser.double
import com.opentelly.tv.data.parser.int
import com.opentelly.tv.data.parser.long
import com.opentelly.tv.data.parser.obj
import com.opentelly.tv.data.parser.objects
import com.opentelly.tv.data.parser.str
import com.opentelly.tv.data.parser.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.decodeBase64
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure JSON → model mapping for the Xtream player API, kept separate for testing. */
object XtreamMapper {

    fun account(json: JsonElement): XtreamAccount {
        val user = (json as? JsonObject)?.obj("user_info")
            ?: throw XtreamAuthException("Server did not return account info")
        val server = (json as JsonObject).obj("server_info")
        return XtreamAccount(
            username = user.str("username"),
            status = user.str("status"),
            authenticated = user.bool("auth"),
            expiresAt = user.long("exp_date")?.takeIf { it > 0 }?.times(1000),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            allowedOutputFormats = user.strList("allowed_output_formats"),
            serverTimezone = server?.str("timezone"),
        )
    }

    fun categories(json: JsonElement): List<XtreamCategory> = json.objects().mapNotNull { o ->
        val id = o.str("category_id") ?: return@mapNotNull null
        XtreamCategory(id, o.str("category_name") ?: id, o.str("parent_id")?.takeUnless { it == "0" })
    }

    fun liveStreams(json: JsonElement): List<XtreamLiveStream> = json.objects().mapNotNull { o ->
        val id = o.long("stream_id") ?: return@mapNotNull null
        XtreamLiveStream(
            streamId = id,
            num = o.int("num"),
            name = o.str("name") ?: "Channel $id",
            icon = o.str("stream_icon"),
            epgChannelId = o.str("epg_channel_id"),
            categoryId = o.str("category_id") ?: firstCategoryId(o),
            tvArchive = o.bool("tv_archive"),
            tvArchiveDays = o.int("tv_archive_duration") ?: 0,
            added = o.long("added")?.times(1000),
        )
    }

    fun vodStreams(json: JsonElement): List<XtreamVodStream> = json.objects().mapNotNull { o ->
        val id = o.long("stream_id") ?: return@mapNotNull null
        XtreamVodStream(
            streamId = id,
            num = o.int("num"),
            name = o.str("name") ?: "Movie $id",
            icon = o.str("stream_icon"),
            categoryId = o.str("category_id") ?: firstCategoryId(o),
            rating = o.double("rating"),
            containerExtension = o.str("container_extension") ?: "mp4",
            added = o.long("added")?.times(1000),
        )
    }

    fun series(json: JsonElement): List<XtreamSeries> = json.objects().mapNotNull { o ->
        val id = o.long("series_id") ?: return@mapNotNull null
        XtreamSeries(
            seriesId = id,
            num = o.int("num"),
            name = o.str("name") ?: "Series $id",
            cover = o.str("cover"),
            categoryId = o.str("category_id") ?: firstCategoryId(o),
            plot = o.str("plot"),
            cast = o.str("cast"),
            director = o.str("director"),
            genre = o.str("genre"),
            releaseDate = o.str("releaseDate") ?: o.str("release_date"),
            rating = o.double("rating"),
            lastModified = o.long("last_modified")?.times(1000),
        )
    }

    fun vodInfo(json: JsonElement): VodDetails {
        val info = (json as? JsonObject)?.obj("info") ?: return VodDetails()
        return VodDetails(
            plot = info.str("plot") ?: info.str("description"),
            year = info.str("releasedate") ?: info.str("releaseDate") ?: info.str("year"),
            duration = info.str("duration") ?: info.int("duration_secs")?.let { "${it / 60} min" },
            cast = info.str("cast") ?: info.str("actors"), director = info.str("director"),
        )
    }

    fun seriesInfo(json: JsonElement): XtreamSeriesInfo {
        val root = json as? JsonObject ?: return XtreamSeriesInfo(null, null, null, emptyList())
        val info = root.obj("info")
        // "episodes" is normally {"1": [...], "2": [...]} but some panels send [[...], [...]].
        val groups: List<Pair<Int?, JsonElement>> = when (val eps = root["episodes"]) {
            is JsonObject -> eps.entries.map { it.key.toIntOrNull() to it.value }
            is JsonArray -> eps.map { null to it }
            else -> emptyList()
        }
        val episodes = groups.flatMap { (seasonKey, list) ->
            list.objects().mapNotNull { e ->
                val id = e.long("id") ?: return@mapNotNull null
                val eInfo = e.obj("info")
                XtreamEpisode(
                    id = id,
                    season = e.int("season") ?: seasonKey ?: 1,
                    episodeNum = e.int("episode_num"),
                    title = e.str("title") ?: "Episode ${e.int("episode_num") ?: id}",
                    containerExtension = e.str("container_extension") ?: "mp4",
                    plot = eInfo?.str("plot"),
                    durationSecs = eInfo?.int("duration_secs"),
                    image = eInfo?.str("movie_image"),
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNum ?: Int.MAX_VALUE }))
        return XtreamSeriesInfo(info?.str("name"), info?.str("plot"), info?.str("cover"), episodes,
            cast = info?.str("cast"), director = info?.str("director"),
            year = info?.str("releaseDate") ?: info?.str("release_date") ?: info?.str("year"))
    }

    /** Handles both `get_short_epg` and `get_simple_data_table`, which share a shape. */
    fun epgListings(json: JsonElement, serverTimeZone: TimeZone? = null): List<XtreamEpgListing> {
        val list = (json as? JsonObject)?.get("epg_listings") ?: return emptyList()
        return list.objects().mapNotNull { o ->
            val start = o.long("start_timestamp")?.times(1000) ?: parseDate(o.str("start"), serverTimeZone)
            val stop = o.long("stop_timestamp")?.times(1000) ?: parseDate(o.str("end") ?: o.str("stop"), serverTimeZone)
            if (start == null || stop == null) return@mapNotNull null
            XtreamEpgListing(
                epgId = o.str("epg_id") ?: o.str("channel_id"),
                title = decode(o.str("title")).orEmpty(),
                description = decode(o.str("description")),
                start = start,
                stop = stop,
                hasArchive = o.bool("has_archive"),
                nowPlaying = o.bool("now_playing"),
            )
        }
    }

    /** Some panels put categories in a `category_ids` array instead of `category_id`. */
    private fun firstCategoryId(o: JsonObject): String? =
        o.arr("category_ids")?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

    /** EPG text is base64 on most panels; fall back to the raw value if it isn't. */
    internal fun decode(value: String?): String? {
        if (value == null) return null
        val looksBase64 = value.length % 4 == 0 && value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (!looksBase64) return value
        val decoded = value.decodeBase64()?.utf8() ?: return value
        // Short plain words ("News") are also valid base64; reject output that isn't clean text.
        val clean = decoded.none { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }
        return if (clean) decoded.trim().ifEmpty { null } else value
    }

    private fun parseDate(value: String?, tz: TimeZone?): Long? {
        if (value == null) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = tz ?: TimeZone.getTimeZone("UTC")
            }.parse(value)?.time
        }.getOrNull()
    }
}
