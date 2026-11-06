package com.openstream.tv.data.repository

import com.openstream.tv.data.db.*
import com.openstream.tv.data.parser.xtream.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class VodRepository(private val db: OpenStreamDatabase, private val http: OkHttpClient) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, VodDetails>>()

    suspend fun details(channel: ChannelEntity): VodDetails = mutex.withLock {
        val playlist = db.playlists().get(channel.playlistId) ?: error("Playlist removed")
        if (playlist.type != PlaylistType.XTREAM) return@withLock VodDetails()
        val key = "${playlist.id}:${playlist.lastUpdated}:${playlist.hashCode()}:${channel.channelKey}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return@withLock it.second }
        val clientHttp = if (playlist.userAgent.isNullOrBlank()) http else http.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", playlist.userAgent).build())
        }.build()
        val client = XtreamClient(XtreamCredentials.from(playlist.url, playlist.username, playlist.password), clientHttp)
        val id = channel.channelKey.substringAfter(':').toLong()
        val result = if (channel.kind == ContentKind.SERIES) {
            val info = client.seriesInfo(id)
            VodDetails(plot = info.plot, year = info.year, cast = info.cast, director = info.director, episodes = info.episodes)
        } else client.vodInfo(id)
        if (cache.size >= 100) cache.clear()
        cache[key] = System.currentTimeMillis() to result
        result
    }

    suspend fun episode(series: ChannelEntity, episodeId: Long): ChannelEntity {
        val episode = details(series).episodes.firstOrNull { it.id == episodeId } ?: error("Episode no longer available")
        val playlist = db.playlists().get(series.playlistId) ?: error("Playlist removed")
        val credentials = XtreamCredentials.from(playlist.url, playlist.username, playlist.password)
        return series.copy(channelKey = "episode:${episode.id}", name = episode.title,
            streamUrl = credentials.seriesUrl(episode.id, episode.containerExtension), kind = ContentKind.MOVIE,
            logoUrl = episode.image ?: series.logoUrl)
    }
}
