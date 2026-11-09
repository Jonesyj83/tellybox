package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.db.FavoriteEntity
import com.tellybox.tv.data.db.GroupWithCount
import com.tellybox.tv.data.db.TellyboxDatabase
import com.tellybox.tv.data.db.PlaylistEntity
import com.tellybox.tv.data.db.PlaylistType
import com.tellybox.tv.data.parser.stalker.StalkerVodRef
import com.tellybox.tv.data.db.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class ChannelRepository(
    private val db: TellyboxDatabase,
    private val stalkerClients: StalkerClientCache,
    private val parental: com.tellybox.tv.data.parental.ParentalRepository,
) {
    fun observeGroups(kind: ContentKind = ContentKind.LIVE): Flow<List<GroupWithCount>> =
        combine(db.groups().observeVisible(kind), parental.changes) { groups, _ ->
            groups.filter { g -> parental.unlocked || !parental.blocked("g:${g.playlistId}:${g.kind}:${g.name}") }
        }.distinctUntilChanged()

    fun observeGroup(groupId: Long): Flow<List<ChannelEntity>> = visible(db.channels().observeByGroup(groupId))

    fun observeAll(kind: ContentKind = ContentKind.LIVE): Flow<List<ChannelEntity>> = visible(db.channels().observeAll(kind))

    fun observeFavorites(): Flow<List<ChannelEntity>> = visible(db.channels().observeFavorites())

    fun observeRecent(): Flow<List<ChannelEntity>> = visible(db.history().observeRecent())

    /** Forgets what has been watched. Favourites and the playlists are left alone. */
    suspend fun clearHistory() = db.history().clear()

    private fun visible(source: Flow<List<ChannelEntity>>) = combine(source, parental.changes) { items, _ -> parental.visible(items, db) }.distinctUntilChanged()

    suspend fun get(id: Long): ChannelEntity? = db.channels().get(id)

    /** Follows a specific set of rows, for a guide opened from a filtered list. */
    fun observeByIds(ids: List<Long>): kotlinx.coroutines.flow.Flow<List<ChannelEntity>> =
        db.channels().observeByIds(ids).map { parental.visible(it, db) }

    /** Looks up channels by id, keeping the order of [ids] and skipping any that are gone. */
    suspend fun getByIds(ids: LongArray): List<ChannelEntity> {
        val found = HashMap<Long, ChannelEntity>()
        ids.toList().chunked(900).forEach { chunk -> db.channels().getByIds(chunk).forEach { found[it.id] = it } }
        return parental.visible(ids.toList().mapNotNull { found[it] }, db)
    }

    suspend fun channelsInGroup(groupId: Long): List<ChannelEntity> = parental.visible(db.channels().getByGroup(groupId), db)

    suspend fun search(query: String): List<ChannelEntity> = parental.visible(db.channels().search(query.trim()), db)

    suspend fun isFavorite(channel: ChannelEntity) = db.favorites().isFavorite(channel.playlistId, channel.channelKey)

    /** Returns true if the channel is now a favorite. */
    suspend fun toggleFavorite(channel: ChannelEntity): Boolean {
        return if (isFavorite(channel)) {
            db.favorites().delete(channel.playlistId, channel.channelKey)
            false
        } else {
            db.favorites().insert(
                FavoriteEntity(channel.playlistId, channel.channelKey, db.favorites().maxSortOrder() + 1),
            )
            true
        }
    }

    suspend fun recordWatched(channel: ChannelEntity, watchedMs: Long, positionMs: Long = 0) {
        val old = db.history().get(channel.playlistId, channel.channelKey)
        db.history().upsert(
            WatchHistoryEntity(
                playlistId = channel.playlistId,
                channelKey = channel.channelKey,
                lastWatched = System.currentTimeMillis(),
                totalWatchMs = (old?.totalWatchMs ?: 0) + watchedMs,
                positionMs = positionMs,
            ),
        )
    }

    /**
     * Turns a stored channel into something the player can open. Most sources store a
     * direct URL; Stalker portals hand out a fresh, short-lived link per play.
     */
    suspend fun resolveStreamUrl(channel: ChannelEntity): String {
        val playlist = db.playlists().get(channel.playlistId) ?: return channel.streamUrl
        return when (playlist.type) {
            PlaylistType.STALKER -> if (StalkerVodRef.isMarker(channel.streamUrl)) resolveStalkerVod(playlist, channel, fresh = false)
                else stalkerClients.get(playlist).createLink(channel.streamUrl)
            else -> channel.streamUrl
        }
    }

    /** True for titles whose link comes from the portal on play and can go stale. */
    fun canReResolve(channel: ChannelEntity) = StalkerVodRef.isMarker(channel.streamUrl)

    /** Asks the portal again, ignoring the cached link; for when that link stopped working. */
    suspend fun reResolveStreamUrl(channel: ChannelEntity): String {
        val playlist = requireNotNull(db.playlists().get(channel.playlistId)) { "Playlist removed" }
        return resolveStalkerVod(playlist, channel, fresh = true)
    }

    /** Episodes have no row of their own, so their links are only remembered for this run. */
    private val episodeLinks = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    private suspend fun resolveStalkerVod(playlist: PlaylistEntity, channel: ChannelEntity, fresh: Boolean): String {
        val ref = StalkerVodRef.parse(channel.streamUrl) ?: error("Not a portal title")
        val memoryKey = "${playlist.id}|${ref.marker}"
        if (!fresh) {
            channel.resolvedUrl?.let { return it }
            synchronized(episodeLinks) { episodeLinks[memoryKey] }?.let { return it }
        }
        val url = stalkerClients.get(playlist).resolve(ref)
        if (db.channels().setResolvedUrl(channel.playlistId, channel.channelKey, url) == 0) {
            synchronized(episodeLinks) { episodeLinks[memoryKey] = url }
        }
        return url
    }

    suspend fun resolveCatchupUrl(channel: ChannelEntity, start: Long, stop: Long): String {
        val now = System.currentTimeMillis()
        require(com.tellybox.tv.data.catchup.CatchupUrls.available(channel.catchupDays, start, stop, now)) {
            "No catch-up available for this program"
        }
        val playlist = requireNotNull(db.playlists().get(channel.playlistId))
        return when (playlist.type) {
            PlaylistType.XTREAM -> {
                val credentials = com.tellybox.tv.data.parser.xtream.XtreamCredentials.from(
                    playlist.url, playlist.username, playlist.password,
                )
                credentials.timeshiftUrl(channel.channelKey.substringAfter("live:").toLong(), start,
                    ((stop - start + 59_999) / 60_000).toInt())
            }
            PlaylistType.STALKER -> stalkerClients.get(playlist).createArchiveLink(channel.streamUrl, start, stop)
            PlaylistType.M3U -> com.tellybox.tv.data.catchup.CatchupUrls.build(
                channel.streamUrl, channel.catchupType, channel.catchupSource, start, stop, now,
            ) ?: error("No catch-up source for this channel")
        }
    }

    suspend fun userAgentFor(channel: ChannelEntity): String? =
        channel.userAgent ?: db.playlists().get(channel.playlistId)?.userAgent
}
