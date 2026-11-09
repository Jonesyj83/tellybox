package com.tellybox.tv.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ChannelGroupEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.db.EpgSourceEntity
import com.tellybox.tv.data.db.TellyboxDatabase
import com.tellybox.tv.data.db.PlaylistEntity
import com.tellybox.tv.data.db.PlaylistType
import com.tellybox.tv.data.parser.m3u.M3uParser
import com.tellybox.tv.data.parser.m3u.M3uPlaylist
import com.tellybox.tv.data.parser.stalker.LoadCompletion
import com.tellybox.tv.data.parser.stalker.StalkerClient
import com.tellybox.tv.data.parser.xtream.XtreamClient
import com.tellybox.tv.data.parser.xtream.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

data class RefreshSummary(
    val groups: Int,
    val channels: Int,
    val movies: Int,
    val series: Int,
    val epgUrls: List<String>,
    val keptKinds: Set<ContentKind> = emptySet(),
    val incompleteCategories: Set<String> = emptySet(),
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
    private val db: TellyboxDatabase,
    private val http: OkHttpClient,
    private val stalkerClients: StalkerClientCache,
    /** True when adult (portal-flagged) categories should not be imported. */
    private val hideAdult: () -> Boolean = { false },
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
        stalkerRefresh.delete(id) {
            // Groups, channels, favorites, history and EPG sources all cascade.
            db.playlists().delete(id)
            stalkerClients.forget(id)
        }
    }

    suspend fun setEnabled(playlist: PlaylistEntity, enabled: Boolean) {
        db.playlists().update(playlist.copy(enabled = enabled))
    }

    suspend fun edit(edited: PlaylistEntity): Result<RefreshSummary> = refreshLocks.withLock(edited.id) {
        require(edited.name.isNotBlank() && edited.url.isNotBlank()) { "Name and source are required" }
        db.withTransaction {
            val old = db.playlists().get(edited.id)
            if (old?.type == PlaylistType.XTREAM &&
                (old.url != edited.url || old.username != edited.username || old.password != edited.password)) {
                val oldGuide = runCatching { XtreamCredentials.from(old.url, old.username, old.password).xmltvUrl() }.getOrNull()
                db.epg().getSourcesForPlaylist(edited.id).filter { it.url == oldGuide }
                    .forEach { db.epg().deleteSource(it.id) }
            }
            db.playlists().update(edited)
        }
        // The old portal's tokens and its half-finished film pass both have to go.
        stalkerRefresh.cancel(edited.id)
        stalkerClients.forget(edited.id)
        doRefresh(edited.id)
    }

    private val refreshLocks = KeyedLock<Long>()

    suspend fun refresh(playlistId: Long): Result<RefreshSummary> =
        refreshLocks.withLock(playlistId) { doRefresh(playlistId) }

    private suspend fun doRefresh(playlistId: Long): Result<RefreshSummary> {
        val playlist = db.playlists().get(playlistId)
            ?: return Result.failure(IllegalArgumentException("Playlist $playlistId not found"))
        return try {
            val summary = when (playlist.type) {
                PlaylistType.M3U -> refreshM3u(playlist)
                PlaylistType.XTREAM -> refreshXtream(playlist)
                PlaylistType.STALKER -> refreshStalker(playlist)
            }
            summary.epgUrls.forEach { addEpgSource(playlistId, it) }
            db.epg().getEnabledSources().filter { it.playlistId == null || it.playlistId == playlistId }
                .forEach { EpgRepository.recomputeMatches(db, it) }
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
        val client = XtreamClient(creds, http.withUserAgent(playlist.userAgent))
        val account = client.authenticate()
        db.playlists().setMaxConnections(playlist.id, account.maxConnections?.takeIf { it > 0 })
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
        val moviesCompletion = optionalImport {
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
        }

        val seriesCompletion = optionalImport {
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
        }

        return store(playlist.id, out, listOf(creds.xmltvUrl()), groups, ImportCompletion(mapOf(
            ContentKind.LIVE to LoadCompletion.FULL,
            ContentKind.MOVIE to moviesCompletion, ContentKind.SERIES to seriesCompletion)))
    }

    // --- Stalker -----------------------------------------------------------------------

    private suspend fun refreshStalker(playlist: PlaylistEntity): RefreshSummary {
        val client = stalkerClients.get(playlist)
        client.connect()
        // Adult categories are left out entirely once a parental PIN exists.
        val imported = StalkerImport.live(client, playlist.id)
        val stored = stalkerRefresh.refresh(playlist.id) {
            store(playlist.id, imported.channels, emptyList(), imported.groups, imported.completion)
        }
        // Films land later, so report what the playlist holds rather than what just arrived,
        // otherwise the summary makes it look like the library was wiped.
        return stored.copy(
            movies = db.channels().countForPlaylistByKind(playlist.id, ContentKind.MOVIE),
            series = db.channels().countForPlaylistByKind(playlist.id, ContentKind.SERIES),
        )
    }

    private val stalkerRefresh = StalkerRefresh(applicationScope, ::importStalkerVod)

    suspend fun refreshStalkerVod(playlistId: Long) = stalkerRefresh.vod(playlistId)

    /**
     * Runs after the live channels are on screen, so a big film library cannot block them. The
     * walk is stored a chunk of categories at a time, so the film list fills in as it goes rather
     * than staying empty until the last page.
     */
    private suspend fun importStalkerVod(playlistId: Long) {
        try {
            val playlist = db.playlists().get(playlistId) ?: return
            if (playlist.type != PlaylistType.STALKER) return
            val client = stalkerClients.get(playlist)
            val categories = client.vodCategories().filterNot { hideAdult() && it.censored }
            if (categories.isEmpty()) {
                Log.w("PlaylistRepository", "The portal listed no VOD categories")
                return
            }
            val started = System.nanoTime()
            categories.chunked(VOD_CHUNK).forEach { chunk ->
                val left = VOD_BUDGET_MS - (System.nanoTime() - started) / 1_000_000
                if (left <= 0) return@forEach
                val part = StalkerImport.loadVod(playlistId, false, chunk, left, client::vodItemsResult)
                // Only this chunk may be pruned. Every other category is marked PARTIAL for this
                // store, otherwise its rows look missing and get deleted.
                val chunkIds = chunk.map { it.id }.toSet()
                val others = categories.map { it.id }.filterNot { it in chunkIds }
                store(playlistId, part.channels, emptyList(), part.groups, batchedCompletion(part.completion.categories, others))
                part.vodError?.let { Log.w("PlaylistRepository", "Stalker VOD: $it") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PlaylistRepository", "Stalker VOD refresh failed", e)
        }
    }

    // --- persistence -------------------------------------------------------------------

    private suspend fun store(
        playlistId: Long,
        channels: List<ImportedChannel>,
        epgUrls: List<String>,
        declaredGroups: List<ImportedGroup> = emptyList(),
        completion: ImportCompletion = ImportCompletion(),
    ): RefreshSummary {
        // Group order: provider's declared order first, then any extra names in the order seen.
        val groupKeys = LinkedHashMap<Pair<ContentKind, String>, ImportedGroup>()
        declaredGroups.forEach { groupKeys.putIfAbsent(it.kind to it.name, it) }
        channels.forEach { groupKeys.putIfAbsent(it.kind to it.groupName, ImportedGroup(it.groupName, it.kind)) }

        // Keep the user's hidden/ordering choices for groups that still exist.
        db.withTransaction {
            val previous = db.groups().getForPlaylist(playlistId).associateBy { it.kind to it.name }
            val previousById = previous.values.associateBy { it.id }
            val oldChannels = db.channels().getForPlaylist(playlistId).associateBy { it.channelKey }
            val nextGroupOrder = (previous.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val nextChannelOrder = (oldChannels.values.maxOfOrNull { it.sortOrder } ?: -1) + 1

            val groupEntities = groupKeys.values.mapIndexed { i, g ->
                val old = previous[g.kind to g.name]
                ChannelGroupEntity(
                    playlistId = playlistId,
                    name = g.name,
                    kind = g.kind,
                    remoteId = g.remoteId,
                ).withLocalChoices(old, nextGroupOrder + i)
            }
            // Explicit ids keep open players and guides attached to the same rows.
            val groupPlan = planGroupRefresh(previous.values.toList(), groupEntities, { completion.canPrune(it.kind, it) })
            val ids = db.groups().insertAll(groupPlan.upserts)
            val idByKey = groupPlan.upserts.zip(ids).associate { (g, id) -> (g.kind to g.name) to id }

            val seenKeys = HashMap<String, Int>()
            val nextNumber = HashMap<ContentKind, Int>()
            val incomingChannels = channels.map { ic ->
                // Duplicate URLs happen in hand-made lists; keep every entry but make keys unique.
                val n = seenKeys.merge(ic.channel.channelKey, 1, Int::plus)!!
                val key = if (n == 1) ic.channel.channelKey else "${ic.channel.channelKey}#$n"
                // Channels without a provider number are numbered in playlist order.
                val position = nextNumber.merge(ic.kind, 1, Int::plus)!!
                ic.withGroupId(idByKey).copy(
                    channelKey = key,
                    number = ic.channel.number ?: position,
                ).withLocalChoices(oldChannels[key], nextChannelOrder + position)
            }
            val channelPlan = planChannelRefresh(oldChannels.values.toList(), incomingChannels, { row ->
                completion.canPrune(row.kind, previousById[row.groupId])
            })
            channelPlan.upserts.chunked(INSERT_CHUNK).forEach { db.channels().insertAll(it) }
            channelPlan.deleteIds.chunked(INSERT_CHUNK).forEach { db.channels().deleteByIds(it) }
            // Remove groups after channels have moved, so SET_NULL cannot detach surviving rows.
            groupPlan.deleteIds.chunked(INSERT_CHUNK).forEach { db.groups().deleteByIds(it) }
        }

        return RefreshSummary(
            groups = groupKeys.size,
            channels = channels.count { it.kind == ContentKind.LIVE },
            movies = channels.count { it.kind == ContentKind.MOVIE },
            series = channels.count { it.kind == ContentKind.SERIES },
            epgUrls = epgUrls,
            keptKinds = completion.keptKinds,
            incompleteCategories = completion.categories.filterValues { it != LoadCompletion.FULL }.keys,
        )
    }

    companion object {
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /**
         * Categories per store. One at a time: a single big category can take a minute, and the
         * point is that the ones already walked are visible while the rest are still coming.
         */
        private const val VOD_CHUNK = 1
        private const val VOD_BUDGET_MS = 10 * 60 * 1000L
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
            StalkerClient(
                portalUrl = playlist.url,
                macAddress = playlist.macAddress.orEmpty(),
                http = http,
                userAgent = playlist.userAgent,
                stbType = playlist.stalkerStbType,
                serial = playlist.stalkerSerial,
                deviceId = playlist.stalkerDeviceId,
                deviceId2 = playlist.stalkerDeviceId2,
            )
        }

    @Synchronized
    fun forget(playlistId: Long) {
        clients.remove(playlistId)
    }
}
