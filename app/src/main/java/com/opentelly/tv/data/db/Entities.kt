package com.opentelly.tv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U, XTREAM, STALKER }

/** What a channel row actually is. M3U lists often mix live channels with VOD. */
enum class ContentKind { LIVE, MOVIE, SERIES }

@Entity(tableName = "playlists")
@kotlinx.serialization.Serializable
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U: http(s) URL or content:// URI. Xtream: server base URL. Stalker: portal URL. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    val userAgent: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Simultaneous streams the provider allows, when it says (Xtream accounts do). */
    val maxConnections: Int? = null,
)

@Entity(
    tableName = "channel_groups",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId", "kind", "name"], unique = true)],
)
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val kind: ContentKind = ContentKind.LIVE,
    /** Category id on the provider side (Xtream/Stalker), null for M3U. */
    val remoteId: String? = null,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "channelKey"], unique = true),
        Index("groupId"),
        Index("tvgId"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val groupId: Long? = null,
    /**
     * Identifier that survives a playlist refresh: the provider stream id when there is
     * one, otherwise the stream URL. Favorites and history are keyed on this, not [id].
     */
    val channelKey: String,
    val name: String,
    val streamUrl: String,
    val kind: ContentKind = ContentKind.LIVE,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val number: Int? = null,
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "epg_sources",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index(value = ["playlistId", "url"], unique = true)],
)
@kotlinx.serialization.Serializable
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Playlist this guide belongs to. Null means it applies to every playlist. */
    val playlistId: Long? = null,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
)

@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceId", "xmltvId"],
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    val displayName: String,
    val iconUrl: String? = null,
    @ColumnInfo(defaultValue = "''") val aliases: String = "",
)

@Entity(
    tableName = "epg_programs",
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["channelXmltvId", "start"]),
        Index(value = ["sourceId", "channelXmltvId", "start"]),
        Index("sourceId"),
        Index("stop"),
    ],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val channelXmltvId: String,
    /** Epoch millis, UTC. */
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val episode: String? = null,
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
@kotlinx.serialization.Serializable
data class FavoriteEntity(
    val playlistId: Long,
    val channelKey: String,
    val sortOrder: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["playlistId", "channelKey"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lastWatched")],
)
@kotlinx.serialization.Serializable
data class WatchHistoryEntity(
    val playlistId: Long,
    val channelKey: String,
    val lastWatched: Long,
    @ColumnInfo(defaultValue = "0") val totalWatchMs: Long = 0,
    /** Resume point for VOD, 0 for live. */
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0,
)

/** Group row plus how many visible channels it holds, for the sidebar. */
data class GroupWithCount(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val kind: ContentKind,
    val channelCount: Int,
)

enum class RecordingStatus { SCHEDULED, RECORDING, COMPLETED, FAILED, CANCELLED }

/** LIVE captures the stream as it airs; CATCHUP downloads the provider's archive of a past programme. */
enum class RecordingSource { LIVE, CATCHUP }

/**
 * A scheduled, running or finished recording. Channels are referenced by (playlistId,
 * channelKey) because channel row ids change every time a playlist is refreshed.
 */
@Entity(tableName = "recordings", indices = [Index("status"), Index("startAt")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    /** The programme (or requested span) before padding. */
    val programStart: Long,
    val programStop: Long,
    /** Wall-clock window the recorder runs in, padding included. For catch-up, the archive span. */
    val startAt: Long,
    val stopAt: Long,
    val source: RecordingSource = RecordingSource.LIVE,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "0") val bytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "reminders",
    indices = [Index(value = ["playlistId", "channelKey", "programStart"], unique = true), Index("remindAt")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val programStart: Long,
    val programStop: Long,
    val remindAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "epg_matches", primaryKeys = ["channelId", "sourceId"],
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EpgSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")])
data class EpgMatchEntity(val channelId: Long, val sourceId: Long, val xmltvId: String, val pass: String)
