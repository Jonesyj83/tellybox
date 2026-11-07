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

    @Query("UPDATE playlists SET maxConnections = :max WHERE id = :id")
    suspend fun setMaxConnections(id: Long, max: Int?)

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

    @Query("UPDATE channel_groups SET sortOrder = :position WHERE id = :id")
    suspend fun setOrder(id: Long, position: Int)

    @Query("UPDATE channel_groups SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY sortOrder, id")
    suspend fun getForPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("UPDATE channels SET sortOrder = :position WHERE id = :id")
    suspend fun setOrder(id: Long, position: Int)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE groupId = :groupId AND hidden = 0 AND groupId IN (SELECT id FROM channel_groups WHERE hidden = 0) ORDER BY sortOrder, id")
    fun observeByGroup(groupId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE groupId = :groupId AND hidden = 0 AND groupId IN (SELECT id FROM channel_groups WHERE hidden = 0) ORDER BY sortOrder, id")
    suspend fun getByGroup(groupId: Long): List<ChannelEntity>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0 AND (c.groupId IS NULL OR c.groupId IN (SELECT id FROM channel_groups WHERE hidden = 0)) AND c.kind = :kind
        ORDER BY p.sortOrder, p.id, c.sortOrder
        """,
    )
    fun observeAll(kind: ContentKind = ContentKind.LIVE): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN favorites f ON f.playlistId = c.playlistId AND f.channelKey = c.channelKey
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0 AND (c.groupId IS NULL OR c.groupId IN (SELECT id FROM channel_groups WHERE hidden = 0))
        ORDER BY f.sortOrder, f.addedAt
        """,
    )
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1
        WHERE c.hidden = 0 AND (c.groupId IS NULL OR c.groupId IN (SELECT id FROM channel_groups WHERE hidden = 0)) AND c.name LIKE '%' || :query || '%'
        ORDER BY c.name LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 200): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun countForPlaylist(playlistId: Long): Int

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND channelKey = :channelKey")
    suspend fun findByKey(playlistId: Long, channelKey: String): ChannelEntity?
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM channels WHERE kind = 'LIVE' AND (:playlistId IS NULL OR playlistId = :playlistId) ORDER BY playlistId, sortOrder, id")
    suspend fun matchingChannels(playlistId: Long?): List<ChannelEntity>

    @Query("SELECT * FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun guideChannels(sourceId: Long): List<EpgChannelEntity>

    @Query("DELETE FROM epg_matches WHERE sourceId = :sourceId")
    suspend fun clearMatches(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatches(matches: List<EpgMatchEntity>)

    @Query("SELECT m.* FROM epg_matches m JOIN epg_sources s ON s.id = m.sourceId WHERE s.enabled = 1 AND m.channelId IN (:ids) ORDER BY s.priority, s.id")
    suspend fun matches(ids: List<Long>): List<EpgMatchEntity>

    @Query("SELECT COUNT(DISTINCT m.channelId) FROM epg_matches m JOIN epg_sources s ON s.id = m.sourceId AND s.enabled = 1 JOIN channels c ON c.id = m.channelId JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1")
    fun observeMatchCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels c JOIN playlists p ON p.id = c.playlistId AND p.enabled = 1 WHERE c.kind = 'LIVE'")
    fun observeLiveCount(): Flow<Int>

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

    @Query("UPDATE epg_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: Long, enabled: Boolean)

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

    /** Programmes overlapping [from, to) for a batch of (lower-cased) XMLTV ids. Keep batches under 900. */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE channelXmltvId IN (:xmltvIds) AND stop > :from AND start < :to
        ORDER BY channelXmltvId, start
        """,
    )
    suspend fun programsForIds(xmltvIds: List<String>, from: Long, to: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg_programs WHERE sourceId = :sourceId AND channelXmltvId IN (:ids) AND stop > :from AND start < :to ORDER BY start")
    suspend fun mappedPrograms(sourceId: Long, ids: List<String>, from: Long, to: Long): List<EpgProgramEntity>

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
    @Query("SELECT * FROM watch_history")
    suspend fun getAll(): List<WatchHistoryEntity>

    @Query(
        """
        SELECT c.* FROM channels c
        JOIN watch_history h ON h.playlistId = c.playlistId AND h.channelKey = c.channelKey
        WHERE c.hidden = 0 AND (c.groupId IS NULL OR c.groupId IN (SELECT id FROM channel_groups WHERE hidden = 0))
        ORDER BY h.lastWatched DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 50): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM watch_history WHERE playlistId = :playlistId AND channelKey = :key")
    suspend fun get(playlistId: Long, key: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE playlistId = :playlistId AND channelKey = :key")
    fun observe(playlistId: Long, key: String): Flow<WatchHistoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}

@Dao
interface RecordingDao {
    @Query(
        """
        SELECT * FROM recordings ORDER BY
        CASE status WHEN 'RECORDING' THEN 0 WHEN 'SCHEDULED' THEN 1 ELSE 2 END,
        CASE WHEN status = 'SCHEDULED' THEN startAt ELSE -startAt END
        """,
    )
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: List<RecordingStatus>): List<RecordingEntity>

    @Query(
        """
        SELECT * FROM recordings WHERE playlistId = :playlistId AND channelKey = :channelKey
        AND status IN ('SCHEDULED', 'RECORDING') AND startAt < :to AND stopAt > :from
        """,
    )
    suspend fun activeOverlapping(playlistId: Long, channelKey: String, from: Long, to: Long): List<RecordingEntity>

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recordings SET bytes = :bytes WHERE id = :id")
    suspend fun setBytes(id: Long, bytes: Long)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY programStart")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE playlistId = :playlistId AND channelKey = :channelKey AND programStart = :programStart")
    suspend fun find(playlistId: Long, channelKey: String, programStart: Long): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}
