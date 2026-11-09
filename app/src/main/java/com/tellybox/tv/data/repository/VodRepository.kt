package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.*
import com.tellybox.tv.data.parser.stalker.StalkerVodRef
import com.tellybox.tv.data.parser.xtream.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class VodRepository(
    private val db: TellyboxDatabase,
    private val http: OkHttpClient,
    private val stalkerClients: StalkerClientCache,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, VodDetails>>()

    suspend fun details(channel: ChannelEntity): VodDetails = mutex.withLock {
        val playlist = db.playlists().get(channel.playlistId) ?: error("Playlist removed")
        if (playlist.type == PlaylistType.STALKER && channel.kind == ContentKind.MOVIE) return@withLock storedDetails(channel)
        if (playlist.type != PlaylistType.XTREAM && playlist.type != PlaylistType.STALKER) return@withLock VodDetails()
        val key = "${playlist.id}:${playlist.lastUpdated}:${playlist.hashCode()}:${channel.channelKey}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return@withLock it.second }
        val result = if (playlist.type == PlaylistType.STALKER) stalkerSeries(playlist, channel) else xtream(playlist, channel)
        if (cache.size >= 100) cache.clear()
        cache[key] = System.currentTimeMillis() to result
        result
    }

    private suspend fun xtream(playlist: PlaylistEntity, channel: ChannelEntity): VodDetails {
        val clientHttp = if (playlist.userAgent.isNullOrBlank()) http else http.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", playlist.userAgent).build())
        }.build()
        val client = XtreamClient(XtreamCredentials.from(playlist.url, playlist.username, playlist.password), clientHttp)
        val id = channel.channelKey.substringAfter(':').toLong()
        return if (channel.kind == ContentKind.SERIES) {
            val info = client.seriesInfo(id)
            VodDetails(plot = info.plot, year = info.year, cast = info.cast, director = info.director, episodes = info.episodes)
        } else client.vodInfo(id)
    }

    /** Stalker listings already carry the details; they were stored with the row. */
    private fun storedDetails(channel: ChannelEntity) = VodDetails(
        plot = channel.plot, year = channel.year, duration = channel.durationMinutes?.let { "$it min" },
        cast = channel.cast, director = channel.director, genre = channel.genre, country = channel.country,
        rating = channel.rating,
    )

    /** Seasons, then each season's episodes, in the same shape the Xtream series screen uses. */
    private suspend fun stalkerSeries(playlist: PlaylistEntity, series: ChannelEntity): VodDetails {
        val ref = StalkerVodRef.parse(series.streamUrl) as? StalkerVodRef.Series ?: return storedDetails(series)
        val client = stalkerClients.get(playlist)
        val seasons = client.seasons(ref.seriesId)
        var failure: Exception? = null
        val episodes = seasons.flatMapIndexed { index, season ->
            val list = try {
                client.episodes(ref.seriesId, season.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One season the portal can't list shouldn't hide the rest.
                failure = e
                emptyList()
            }
            list.mapNotNull { e ->
                XtreamEpisode(
                    id = e.id.toLongOrNull() ?: return@mapNotNull null,
                    season = season.number ?: (index + 1),
                    episodeNum = e.number,
                    title = e.name,
                    containerExtension = "",
                    plot = null,
                    durationSecs = null,
                    image = null,
                    seasonId = season.id,
                )
            }
        }
        if (episodes.isEmpty()) failure?.let { throw it }
        return storedDetails(series).copy(episodes = episodes)
    }

    suspend fun episode(series: ChannelEntity, episodeId: Long): ChannelEntity {
        val episode = details(series).episodes.firstOrNull { it.id == episodeId } ?: error("Episode no longer available")
        val playlist = db.playlists().get(series.playlistId) ?: error("Playlist removed")
        if (playlist.type == PlaylistType.STALKER) {
            val ref = StalkerVodRef.parse(series.streamUrl) as? StalkerVodRef.Series ?: error("Not a series")
            val seasonId = episode.seasonId ?: error("Episode no longer available")
            return series.copy(channelKey = "stalker-episode:${episode.id}", name = episode.title,
                streamUrl = StalkerVodRef.Episode(ref.seriesId, seasonId, episode.id.toString()).marker,
                kind = ContentKind.MOVIE, resolvedUrl = null)
        }
        val credentials = XtreamCredentials.from(playlist.url, playlist.username, playlist.password)
        return series.copy(channelKey = "episode:${episode.id}", name = episode.title,
            streamUrl = credentials.seriesUrl(episode.id, episode.containerExtension), kind = ContentKind.MOVIE,
            logoUrl = episode.image ?: series.logoUrl)
    }
}
