package com.openstream.tv.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ChannelGroupEntity
import com.openstream.tv.data.db.ContentKind
import com.openstream.tv.data.db.EpgSourceEntity
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.db.PlaylistEntity
import com.openstream.tv.data.db.PlaylistType
import com.openstream.tv.data.parser.m3u.M3uParser
import com.openstream.tv.data.parser.m3u.M3uPlaylist
import com.openstream.tv.data.parser.stalker.StalkerClient
import com.openstream.tv.data.parser.xtream.XtreamClient
import com.openstream.tv.data.parser.xtream.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

data class RefreshSummary(
    val groups: Int,
    val channels: Int,
    val movies: Int,
    val series: Int,
    val epgUrls: List<String>,
)

/** A channel before it has a database id, tied to its group by name. */
internal data class ImportedChannel(
    val groupName: String,
    val kind: ContentKind,
    val channel: ChannelEntity,
)

internal data class ImportedGroup(val name: String, val kind: ContentKind, val remoteId: String? = null)

class PlaylistRepository(
    private val context: Context,
    private val db: OpenStreamDatabase,
    private val http: OkHttpClient,
    private val stalkerClients: StalkerClientCache,
) {
    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    suspend fun getPlaylists(): List<PlaylistEntity> = db.playlists().getAll()

    suspend fun get(id: Long): PlaylistEntity? = db.playlists().get(id)

    suspend fun count(): Int = db.playlists().count()

    suspend fun add(playlist: PlaylistEntity, epgUrl: String?): Long {
        val id = db.playlists().insert(playlist.copy(sortOrder = db.playlists().count()))
        if (!epgUrl.isNullOrBlank()) addEpgSource(id, epgUrl.trim())
        return id
    }

    suspend fun delete(id: Long) {
        // Groups, channels, favorites, history and EPG sources all cascade.
        db.playlists().delete(id)
        stalkerClients.forget(id)
    }

    suspend fun setEnabled(playlist: PlaylistEntity, enabled: Boolean) {
        db.playlists().update(playlist.copy(enabled = enabled))
    }

    suspend fun refresh(playlistId: Long): Result<RefreshSummary> {
        val playlist = db.playlists().get(playlistId)
            ?: return Result.failure(IllegalArgumentException("Playlist $playlistId not found"))
        return try {
            val summary = when (playlist.type) {
                PlaylistType.M3U -> refreshM3u(playlist)
                PlaylistType.XTREAM -> refreshXtream(playlist)
                PlaylistType.STALKER -> refreshStalker(playlist)
            }
            summary.epgUrls.forEach { addEpgSource(playlistId, it) }
            db.playlists().setRefreshResult(playlistId, System.currentTimeMillis(), null)
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.playlists().setRefreshResult(playlistId, playlist.lastUpdated, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    private suspend fun addEpgSource(playlistId: Long, url: String) {
        val name = runCatching { java.net.URI(url).host }.getOrNull() ?: "Guide"
        db.epg().insertSource(EpgSourceEntity(playlistId = playlistId, name = name, url = url))
    }

    // --- M3U ---------------------------------------------------------------------------

    private suspend fun refreshM3u(playlist: PlaylistEntity): RefreshSummary {
        val parsed = openSource(context, http, playlist.url, playlist.userAgent) { M3uParser.parse(it) }
        if (parsed.entries.isEmpty()) throw IllegalStateException("No channels found in playlist")
        val imported = mapM3u(playlist.id, parsed)
        return store(playlist.id, imported, parsed.header.epgUrls)
    }

    internal fun mapM3u(playlistId: Long, parsed: M3uPlaylist): List<ImportedChannel> =
        parsed.entries.mapIndexed { index, e ->
            val kind = guessKind(e.url)
            ImportedChannel(
                groupName = e.groupTitle ?: UNGROUPED,
                kind = kind,
                channel = ChannelEntity(
                    playlistId = playlistId,
                    channelKey = e.url,
                    name = e.name,
                    streamUrl = e.url,
                    kind = kind,
                    logoUrl = e.tvgLogo,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    number = e.channelNumber,
                    catchupType = e.catchupType,
                    catchupDays = e.catchupDays ?: 0,
                    catchupSource = e.catchupSource,
                    userAgent = e.userAgent,
                    referrer = e.referrer,
                    sortOrder = index,
                ),
            )
        }

    /** Xtream-generated M3U lists put VOD under /movie/ and /series/ paths. */
    private fun guessKind(url: String): ContentKind = when {
        url.contains("/movie/") -> ContentKind.MOVIE
        url.contains("/series/") -> ContentKind.SERIES
        else -> ContentKind.LIVE
    }

    // --- Xtream ------------------------------------------------------------------------

    private suspend fun refreshXtream(playlist: PlaylistEntity): RefreshSummary {
        val creds = XtreamCredentials.from(playlist.url, playlist.username, playlist.password)
        val client = XtreamClient(creds, http)
        val account = client.authenticate()
        val liveExt = if (account.allowedOutputFormats.isEmpty() || "ts" in account.allowedOutputFormats) "ts" else "m3u8"

        val out = ArrayList<ImportedChannel>()
        val groups = ArrayList<ImportedGroup>()

        val liveCats = client.liveCategories().associateBy { it.id }
        liveCats.values.forEach { groups += ImportedGroup(it.name, ContentKind.LIVE, it.id) }
        client.liveStreams().forEachIndexed { i, s ->
            out += ImportedChannel(
                groupName = liveCats[s.categoryId]?.name ?: UNGROUPED,
                kind = ContentKind.LIVE,
                channel = ChannelEntity(
                    playlistId = playlist.id,
                    channelKey = "live:${s.streamId}",
                    name = s.name,
                    streamUrl = creds.liveUrl(s.streamId, liveExt),
                    logoUrl = s.icon,
                    tvgId = s.epgChannelId,
                    number = s.num,
                    catchupType = if (s.tvArchive) CATCHUP_XTREAM else null,
                    catchupDays = if (s.tvArchive) s.tvArchiveDays else 0,
                    sortOrder = i,
                    addedAt = s.added ?: System.currentTimeMillis(),
                ),
            )
        }

        // VOD is optional on many lines; don't fail the whole refresh over it.
        runCatching {
            val cats = client.vodCategories().associateBy { it.id }
            cats.values.forEach { groups += ImportedGroup(it.name, ContentKind.MOVIE, it.id) }
            client.vodStreams().forEachIndexed { i, s ->
                out += ImportedChannel(
                    groupName = cats[s.categoryId]?.name ?: UNGROUPED,
                    kind = ContentKind.MOVIE,
                    channel = ChannelEntity(
                        playlistId = playlist.id,
                        channelKey = "movie:${s.streamId}",
                        name = s.name,
                        streamUrl = creds.movieUrl(s.streamId, s.containerExtension),
                        kind = ContentKind.MOVIE,
                        logoUrl = s.icon,
                        number = s.num,
                        sortOrder = i,
                        addedAt = s.added ?: System.currentTimeMillis(),
                    ),
                )
            }
        }.onFailure { if (it is CancellationException) throw it }

        runCatching {
            val cats = client.seriesCategories().associateBy { it.id }
            cats.values.forEach { groups += ImportedGroup(it.name, ContentKind.SERIES, it.id) }
            client.series().forEachIndexed { i, s ->
                out += ImportedChannel(
                    groupName = cats[s.categoryId]?.name ?: UNGROUPED,
                    kind = ContentKind.SERIES,
                    channel = ChannelEntity(
                        playlistId = playlist.id,
                        channelKey = "series:${s.seriesId}",
                        name = s.name,
                        // Episodes are fetched on demand; this is a marker, not a stream.
                        streamUrl = "xtream-series:${s.seriesId}",
                        kind = ContentKind.SERIES,
                        logoUrl = s.cover,
                        number = s.num,
                        sortOrder = i,
                        addedAt = s.lastModified ?: System.currentTimeMillis(),
                    ),
                )
            }
        }.onFailure { if (it is CancellationException) throw it }

        return store(playlist.id, out, listOf(creds.xmltvUrl()), groups)
    }

    // --- Stalker -----------------------------------------------------------------------

    private suspend fun refreshStalker(playlist: PlaylistEntity): RefreshSummary {
        val client = stalkerClients.get(playlist)
        client.connect()
        val genres = client.genres().associateBy { it.id }
        val groups = genres.values.map { ImportedGroup(it.title, ContentKind.LIVE, it.id) }
        val channels = client.channels().mapIndexed { i, c ->
            ImportedChannel(
                groupName = genres[c.genreId]?.title ?: UNGROUPED,
                kind = ContentKind.LIVE,
                channel = ChannelEntity(
                    playlistId = playlist.id,
                    channelKey = "stalker:${c.id}",
                    name = c.name,
                    // Resolved through create_link at play time.
                    streamUrl = c.cmd,
                    logoUrl = c.logo,
                    tvgId = c.xmltvId,
                    number = c.number,
                    catchupType = if (c.hasArchive) CATCHUP_STALKER else null,
                    catchupDays = c.archiveDays,
                    sortOrder = i,
                ),
            )
        }
        return store(playlist.id, channels, emptyList(), groups)
    }

    // --- persistence -------------------------------------------------------------------

    private suspend fun store(
        playlistId: Long,
        channels: List<ImportedChannel>,
        epgUrls: List<String>,
        declaredGroups: List<ImportedGroup> = emptyList(),
    ): RefreshSummary {
        // Group order: provider's declared order first, then any extra names in the order seen.
        val groupKeys = LinkedHashMap<Pair<ContentKind, String>, ImportedGroup>()
        declaredGroups.forEach { groupKeys.putIfAbsent(it.kind to it.name, it) }
        channels.forEach { groupKeys.putIfAbsent(it.kind to it.groupName, ImportedGroup(it.groupName, it.kind)) }

        // Keep the user's hidden/ordering choices for groups that still exist.
        val previous = db.groups().getForPlaylist(playlistId).associateBy { it.kind to it.name }

        db.withTransaction {
            db.channels().deleteForPlaylist(playlistId)
            db.groups().deleteForPlaylist(playlistId)

            val groupEntities = groupKeys.values.mapIndexed { i, g ->
                val old = previous[g.kind to g.name]
                ChannelGroupEntity(
                    playlistId = playlistId,
                    name = g.name,
                    kind = g.kind,
                    remoteId = g.remoteId,
                    sortOrder = i,
                    hidden = old?.hidden ?: false,
                )
            }
            val ids = db.groups().insertAll(groupEntities)
            val idByKey = groupEntities.zip(ids).associate { (g, id) -> (g.kind to g.name) to id }

            val seenKeys = HashMap<String, Int>()
            val nextNumber = HashMap<ContentKind, Int>()
            channels.asSequence()
                .map { ic ->
                    // Duplicate URLs happen in hand-made lists; keep every entry but make keys unique.
                    val n = seenKeys.merge(ic.channel.channelKey, 1, Int::plus)!!
                    val key = if (n == 1) ic.channel.channelKey else "${ic.channel.channelKey}#$n"
                    // Channels without a provider number are numbered in playlist order.
                    val position = nextNumber.merge(ic.kind, 1, Int::plus)!!
                    ic.channel.copy(
                        channelKey = key,
                        groupId = idByKey[ic.kind to ic.groupName],
                        number = ic.channel.number ?: position,
                    )
                }
                .chunked(INSERT_CHUNK)
                .forEach { db.channels().insertAll(it) }
        }

        return RefreshSummary(
            groups = groupKeys.size,
            channels = channels.count { it.kind == ContentKind.LIVE },
            movies = channels.count { it.kind == ContentKind.MOVIE },
            series = channels.count { it.kind == ContentKind.SERIES },
            epgUrls = epgUrls,
        )
    }

    companion object {
        const val UNGROUPED = "Other"
        const val CATCHUP_XTREAM = "xtream"
        const val CATCHUP_STALKER = "stalker"
        private const val INSERT_CHUNK = 500
    }
}

/** Keeps one logged-in Stalker session per playlist so tokens are reused between plays. */
class StalkerClientCache(private val http: OkHttpClient) {
    private val clients = HashMap<Long, StalkerClient>()

    @Synchronized
    fun get(playlist: PlaylistEntity): StalkerClient =
        clients.getOrPut(playlist.id) {
            StalkerClient(playlist.url, playlist.macAddress.orEmpty(), http)
        }

    @Synchronized
    fun forget(playlistId: Long) {
        clients.remove(playlistId)
    }
}
