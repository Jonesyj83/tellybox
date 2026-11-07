package com.opentelly.tv.data.parser.xtream

data class XtreamAccount(
    val username: String?,
    val status: String?,
    val authenticated: Boolean,
    /** Epoch millis, null when unlimited. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedOutputFormats: List<String>,
    val serverTimezone: String?,
)

data class XtreamCategory(
    val id: String,
    val name: String,
    val parentId: String?,
)

data class XtreamLiveStream(
    val streamId: Long,
    val num: Int?,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val tvArchive: Boolean,
    val tvArchiveDays: Int,
    val added: Long?,
)

data class XtreamVodStream(
    val streamId: Long,
    val num: Int?,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val rating: Double?,
    val containerExtension: String,
    val added: Long?,
)

data class XtreamSeries(
    val seriesId: Long,
    val num: Int?,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val lastModified: Long?,
)

data class XtreamEpisode(
    val id: Long,
    val season: Int,
    val episodeNum: Int?,
    val title: String,
    val containerExtension: String,
    val plot: String?,
    val durationSecs: Int?,
    val image: String?,
)

data class XtreamSeriesInfo(
    val name: String?,
    val plot: String?,
    val cover: String?,
    val episodes: List<XtreamEpisode>,
    val cast: String? = null,
    val director: String? = null,
    val year: String? = null,
)

data class XtreamEpgListing(
    val epgId: String?,
    val title: String,
    val description: String?,
    /** Epoch millis. */
    val start: Long,
    val stop: Long,
    val hasArchive: Boolean,
    val nowPlaying: Boolean,
)

class XtreamAuthException(message: String) : Exception(message)

data class VodDetails(
    val plot: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val episodes: List<XtreamEpisode> = emptyList(),
)
