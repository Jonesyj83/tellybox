package com.tellybox.tv.data.metadata

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.list(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

data class CatalogueDetails(
    val plot: String?, val year: String?, val cast: String? = null, val director: String? = null,
    val genre: String? = null, val country: String? = null, val minutes: Int? = null,
    val rating: Double? = null, val image: String? = null,
) {
    fun applyTo(c: ChannelEntity) = c.copy(plot = plot, year = year ?: c.year, cast = cast ?: c.cast,
        director = director ?: c.director, genre = genre ?: c.genre, country = country ?: c.country,
        durationMinutes = minutes ?: c.durationMinutes, rating = rating ?: c.rating, logoUrl = c.logoUrl ?: image)
}

object CatalogueParser {
    private fun names(items: JsonArray) = items.mapNotNull { (it as? JsonObject)?.text("name") }.joinToString(", ").ifBlank { null }
    fun tmdb(o: JsonObject, series: Boolean): CatalogueDetails = CatalogueDetails(
        plot = o.text("overview"), year = o.text(if (series) "first_air_date" else "release_date")?.take(4),
        cast = names(o.obj("credits").list("cast")),
        director = names(JsonArray(o.obj("credits").list("crew").filter { (it as? JsonObject)?.text("job") == "Director" })),
        genre = names(o.list("genres")), country = names(o.list("production_countries")),
        minutes = o.text("runtime")?.toIntOrNull() ?: (o.list("episode_run_time").firstOrNull() as? JsonPrimitive)?.intOrNull,
        rating = o.text("vote_average")?.toDoubleOrNull(), image = o.text("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
    )
    fun tvmaze(o: JsonObject): CatalogueDetails = CatalogueDetails(
        plot = o.text("summary")?.replace(Regex("<[^>]*>"), " ")?.replace("&amp;", "&")?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")?.replace("&nbsp;", " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() },
        year = o.text("premiered")?.take(4), genre = o.list("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ").ifBlank { null },
        country = o.obj("network").obj("country").text("name"), minutes = o.text("runtime")?.toIntOrNull(),
        rating = o.obj("rating").text("average")?.toDoubleOrNull(), image = o.obj("image").text("original") ?: o.obj("image").text("medium"),
    )
}

class CatalogueService(private val http: OkHttpClient) {
    suspend fun fetch(channel: ChannelEntity, enabled: Boolean, apiKey: String): CatalogueDetails? {
        if (!enabled || !channel.plot.isNullOrBlank() || channel.kind == ContentKind.LIVE) return null
        val normalized = TitleMatcher.normalize(channel.name)
        val title = normalized.copy(year = normalized.year ?: channel.year?.toIntOrNull())
        if (title.name.isBlank()) return null
        val series = channel.kind == ContentKind.SERIES || title.season != null
        if (apiKey.isBlank() && !series) return null
        return try {
            withContext(Dispatchers.IO) {
                if (apiKey.isBlank()) {
                    val show = get("https://api.tvmaze.com/singlesearch/shows", mapOf("q" to title.name, "embed" to "episodes"))
                    if (TitleMatcher.score(title, show.text("name").orEmpty(), show.text("premiered")?.take(4)?.toIntOrNull()) == 0.0) null
                    else CatalogueParser.tvmaze(show)
                } else {
                    val type = if (series) "tv" else "movie"
                    val params = mutableMapOf("query" to title.name, "api_key" to apiKey)
                    if (!series) title.year?.let { params["year"] = it.toString() }
                    val candidates = get("https://api.themoviedb.org/3/search/$type", params).list("results")
                        .mapNotNull { it as? JsonObject }.map { o -> o to maxOf(
                            TitleMatcher.score(title, o.text(if (series) "name" else "title").orEmpty(), o.text(if (series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()),
                            TitleMatcher.score(title, o.text(if (series) "original_name" else "original_title").orEmpty(), o.text(if (series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull())) }
                        .filter { it.second > 0 }.sortedByDescending { it.second }
                    val best = candidates.firstOrNull()
                    if (best == null || (candidates.size > 1 && candidates[1].second == best.second)) null
                    else best.first.text("id")?.let { id -> CatalogueParser.tmdb(get("https://api.themoviedb.org/3/$type/$id", mapOf("api_key" to apiKey, "append_to_response" to "credits")), series) }
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private fun get(base: String, params: Map<String, String>): JsonObject {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful)
            Json.parseToJsonElement(it.body?.string() ?: error("Empty response")).jsonObject
        }
    }
}
