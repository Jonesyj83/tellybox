package com.openstream.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET lastUpdated = :time, lastError = :error WHERE id = :id")
    suspend fun setRefreshResult(id: Long, time: Long, error: String?)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}

@Dao
interface ChannelGroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<ChannelGroupEntity>): List<Long>

    @Query("SELECT * FROM channel_groups WHERE playlistId = :playlistId")
    suspend fun getForPlaylist(playlistId: Long): List<ChannelGroupEntity>

    @Query("DELETE FROM channel_groups WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query(
        """
        SELECT g.id, g.playlistId, g.name, g.kind, COUNT(c.id) AS channelCount
        FROM channel_groups g
        JOIN playlists p ON p.id = g.playlistId AND p.enabled = 1
        LEFT JOIN channels c ON c.groupId = g.id AND c.hidden = 0
        WHERE g.hidden = 0 AND g.kind = :kind
        GROUP BY g.id
        HAVING channelCount > 0
        ORDER BY p.sortOrder, p.id, g.sortOrder
        """,
    )
    fun observeVisible(kind: ContentKind = ContentKind.LIVE): Flow<List<GroupWithCount>>

    @Query("UPDATE channel_groups SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE groupId = :groupId AND hidden = 0 ORDER BY sortOrder")
    fun observeByGroup(groupId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE groupId = :groupId AND hidden = 0 ORDER BY sortOrder")
    suspend fun getByGroup(groupId: Long): List<ChannelEntity>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0 AND c.kind = :kind
        ORDER BY p.sortOrder, p.id, c.sortOrder
        """,
    )
    fun observeAll(kind: ContentKind = ContentKind.LIVE): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0
        ORDER BY f.sortOrder, f.addedAt
        """,
    )
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0 AND c.name LIKE '%' || :query || '%'
        ORDER BY c.name LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun countForPlaylist(playlistId: Long): Int

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_sources ORDER BY priority, id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources WHERE enabled = 1 ORDER BY priority, id")
    suspend fun getEnabledSources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE id = :id")
    suspend fun getSource(id: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE playlistId = :playlistId")
    suspend fun getSourcesForPlaylist(playlistId: Long): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: EpgSourceEntity): Long

    @Query("UPDATE epg_sources SET lastUpdated = :time, lastError = :error WHERE id = :id")
    suspend fun setSourceRefreshResult(id: Long, time: Long, error: String?)

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    /** Blocking variant for use from inside the streaming XMLTV parser callbacks. */
    @Insert
    fun insertProgramsBlocking(programs: List<EpgProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChannelsBlocking(channels: List<EpgChannelEntity>)

    @Query("SELECT DISTINCT LOWER(tvgId) FROM channels WHERE tvgId IS NOT NULL AND (:playlistId IS NULL OR playlistId = :playlistId)")
    suspend fun knownTvgIds(playlistId: Long?): List<String>

    @Query("DELETE FROM epg_programs WHERE sourceId = :sourceId")
    suspend fun deleteProgramsForSource(sourceId: Long)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun deleteChannelsForSource(sourceId: Long)

    @Query("DELETE FROM epg_programs WHERE stop < :before")
    suspend fun deleteProgramsEndingBefore(before: Long)

    @Query(
        """
        SELECT * FROM epg_programs
        WHERE channelXmltvId = :xmltvId AND stop > :from AND start < :to
        ORDER BY start
        """,
    )
    suspend fun programsFor(xmltvId: String, from: Long, to: Long): List<EpgProgramEntity>

    @Query(
        """
        SELECT * FROM epg_programs
        WHERE channelXmltvId = :xmltvId AND start <= :now AND stop > :now
        ORDER BY sourceId LIMIT 1
        """,
    )
    suspend fun current(xmltvId: String, now: Long): EpgProgramEntity?

    /** Programmes airing at [now] for a batch of (lower-cased) XMLTV ids. Keep batches under 900. */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE channelXmltvId IN (:xmltvIds) AND start <= :now AND stop > :now
        """,
    )
    suspend fun currentFor(xmltvIds: List<String>, now: Long): List<EpgProgramEntity>

    @Query("SELECT COUNT(*) FROM epg_programs WHERE sourceId = :sourceId")
    suspend fun programCount(sourceId: Long): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY sortOrder, addedAt")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE playlistId = :playlistId AND channelKey = :key)")
    suspend fun isFavorite(playlistId: Long, key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND channelKey = :key")
    suspend fun delete(playlistId: Long, key: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM favorites")
    suspend fun maxSortOrder(): Int
}

@Dao
interface WatchHistoryDao {
    @Query(
        """
        SELECT c.* FROM channels c
        JOIN watch_history h ON h.playlistId = c.playlistId AND h.channelKey = c.channelKey
        WHERE c.hidden = 0
        ORDER BY h.lastWatched DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 50): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM watch_history WHERE playlistId = :playlistId AND channelKey = :key")
    suspend fun get(playlistId: Long, key: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
