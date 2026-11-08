package com.tellybox.tv.data.parser.stalker

import com.tellybox.tv.data.parser.arr
import com.tellybox.tv.data.parser.bool
import com.tellybox.tv.data.parser.double
import com.tellybox.tv.data.parser.int
import com.tellybox.tv.data.parser.objects
import com.tellybox.tv.data.parser.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/** One entry from `type=vod&action=get_categories`; movie and series categories share the list. */
data class StalkerVodCategory(val id: String, val title: String, val censored: Boolean)

/** A title from a category listing. Its `cmd` is a placeholder and is deliberately not kept. */
data class StalkerVodItem(
    val id: String,
    val name: String,
    val isSeries: Boolean,
    val categoryId: String?,
    val description: String?,
    val year: String?,
    val genres: String?,
    val director: String?,
    val actors: String?,
    val rating: Double?,
    val durationMinutes: Int?,
    val country: String?,
    val poster: String?,
    val censored: Boolean,
)

data class StalkerVodPage(val totalItems: Int?, val items: List<StalkerVodItem>)

data class StalkerSeason(val id: String, val number: Int?, val name: String)

data class StalkerEpisode(val id: String, val seasonId: String?, val number: Int?, val name: String)

/**
 * What a stored Stalker VOD row points at. Listings only carry a fake `/media/<id>.mpg`
 * path, so rows hold one of these as their stream URL and the real link is asked for
 * when the title is played.
 */
sealed class StalkerVodRef {
    abstract val marker: String

    data class Movie(val movieId: String) : StalkerVodRef() {
        override val marker get() = "$SCHEME://movie/$movieId"
    }

    /** Not playable itself; the detail screen lists its seasons and episodes. */
    data class Series(val seriesId: String) : StalkerVodRef() {
        override val marker get() = "$SCHEME://series/$seriesId"
    }

    data class Episode(val seriesId: String, val seasonId: String, val episodeId: String) : StalkerVodRef() {
        override val marker get() = "$SCHEME://episode/$seriesId/$seasonId/$episodeId"
    }

    companion object {
        const val SCHEME = "stalker"
        private const val PREFIX = "$SCHEME://"

        fun isMarker(url: String) = url.startsWith(PREFIX)

        fun parse(url: String): StalkerVodRef? {
            if (!isMarker(url)) return null
            val parts = url.removePrefix(PREFIX).split('/')
            if (parts.drop(1).any { it.isEmpty() }) return null
            return when {
                parts.size == 2 && parts[0] == "movie" -> Movie(parts[1])
                parts.size == 2 && parts[0] == "series" -> Series(parts[1])
                parts.size == 4 && parts[0] == "episode" -> Episode(parts[1], parts[2], parts[3])
                else -> null
            }
        }
    }
}

object StalkerVodMapper {
    fun categories(js: JsonElement?): List<StalkerVodCategory> = js.objects().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        if (id == "*") return@mapNotNull null // the portal's own "All" pseudo-category
        StalkerVodCategory(id, o.str("title") ?: id, o.bool("censored"))
    }

    /** [base] resolves the relative `screenshot_uri` paths portals send. */
    fun page(js: JsonElement?, base: HttpUrl?): StalkerVodPage {
        val root = js as? JsonObject
        val items = (root?.arr("data") ?: js).objects().mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            StalkerVodItem(
                id = id,
                name = o.str("name") ?: o.str("o_name") ?: "Title $id",
                // Every item says is_movie:true, series included; only is_series tells them apart.
                isSeries = o.bool("is_series"),
                categoryId = o.str("category_id"),
                description = o.str("description"),
                year = o.str("year")?.takeUnless { it == "0" },
                genres = o.str("genres_str"),
                director = o.str("director"),
                actors = o.str("actors"),
                rating = o.double("rating_imdb")?.takeIf { it > 0 },
                durationMinutes = o.int("time")?.takeIf { it > 0 },
                country = o.str("country"),
                poster = o.str("screenshot_uri")?.let { resolve(base, it) },
                censored = o.bool("censored"),
            )
        }
        return StalkerVodPage(root?.int("total_items"), items)
    }

    fun seasons(js: JsonElement?): List<StalkerSeason> = listData(js).mapNotNull { o ->
        if (o["is_season"] != null && !o.bool("is_season")) return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val number = o.int("season_number")
        StalkerSeason(id, number, o.str("name") ?: o.str("season_name") ?: "Season ${number ?: id}")
    }.sortedBy { it.number ?: Int.MAX_VALUE }

    fun episodes(js: JsonElement?): List<StalkerEpisode> = listData(js).mapNotNull { o ->
        if (o["is_episode"] != null && !o.bool("is_episode")) return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val number = o.int("series_number")
        StalkerEpisode(id, o.str("season_id"), number, o.str("name") ?: "Episode ${number ?: id}")
    }.sortedBy { it.number ?: Int.MAX_VALUE }

    /** The playable link from a `movie_id=` (or `episode_id=`) lookup: `data[0].cmd`. */
    fun streamCmd(js: JsonElement?): String? = listData(js).firstOrNull()?.str("cmd")

    private fun listData(js: JsonElement?) = ((js as? JsonObject)?.arr("data") ?: js).objects()

    private fun resolve(base: HttpUrl?, path: String): String? =
        if (path.contains("://")) path else base?.resolve(path)?.toString()
}

/**
 * Walks `p=1, 2, …` until the portal's `total_items` is reached, a page comes back empty,
 * or [maxPages] pages have been read. A page that fails after earlier ones succeeded ends
 * the walk with what was already read; a failing first page is the caller's problem.
 */
suspend fun <T> collectPages(maxPages: Int = MAX_PAGES, fetch: suspend (page: Int) -> Pair<Int?, List<T>>): List<T> {
    val out = ArrayList<T>()
    for (page in 1..maxPages) {
        val (total, items) = try {
            fetch(page)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (page == 1) throw e else break
        }
        if (items.isEmpty()) break
        out += items
        if (total != null && out.size >= total) break
    }
    return out
}

/**
 * Runaway guard only. Pages hold 14 titles, so this allows over twenty thousand per category;
 * real portals do have categories with several thousand.
 */
const val MAX_PAGES = 1500
