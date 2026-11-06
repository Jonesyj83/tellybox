package com.openstream.tv.data.repository

import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.data.db.FavoriteEntity
import com.openstream.tv.data.db.GroupWithCount
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.db.PlaylistType
import com.openstream.tv.data.db.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

class ChannelRepository(
    private val db: OpenStreamDatabase,
    private val stalkerClients: StalkerClientCache,
) {
    fun observeGroups(kind: ContentKind = ContentKind.LIVE): Flow<List<GroupWithCount>> =
        db.groups().observeVisible(kind)

    fun observeGroup(groupId: Long): Flow<List<ChannelEntity>> = db.channels().observeByGroup(groupId)

    fun observeAll(kind: ContentKind = ContentKind.LIVE): Flow<List<ChannelEntity>> = db.channels().observeAll(kind)

    fun observeFavorites(): Flow<List<ChannelEntity>> = db.channels().observeFavorites()

    fun observeRecent(): Flow<List<ChannelEntity>> = db.history().observeRecent()

    suspend fun get(id: Long): ChannelEntity? = db.channels().get(id)

    suspend fun channelsInGroup(groupId: Long): List<ChannelEntity> = db.channels().getByGroup(groupId)

    suspend fun search(query: String): List<ChannelEntity> = db.channels().search(query.trim())

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
            PlaylistType.STALKER -> stalkerClients.get(playlist).createLink(channel.streamUrl)
            else -> channel.streamUrl
        }
    }

    suspend fun userAgentFor(channel: ChannelEntity): String? =
        channel.userAgent ?: db.playlists().get(channel.playlistId)?.userAgent
}
