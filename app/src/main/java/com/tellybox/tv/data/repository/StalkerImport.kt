package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.parser.stalker.LoadCompletion
import com.tellybox.tv.data.parser.stalker.PageCollection
import com.tellybox.tv.data.parser.stalker.StalkerClient
import com.tellybox.tv.data.parser.stalker.StalkerVodCategory
import com.tellybox.tv.data.parser.stalker.StalkerVodItem
import com.tellybox.tv.data.parser.stalker.StalkerVodRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class StalkerImportResult(
    val channels: List<ImportedChannel>,
    val groups: List<ImportedGroup>,
    /** Why movies and series are missing or incomplete, if they are. */
    val vodError: String? = null,
    val completion: ImportCompletion = ImportCompletion(),
)

/**
 * Reads a Stalker portal into rows: live channels first, exactly as before, then movies and
 * series. VOD never fails the import: a portal without it, or a category that errors, just
 * contributes nothing. Titles are stored with a `stalker://` marker and resolved on play.
 */
internal object StalkerImport {
    /** Categories fetched at once; portals don't like being hammered. */
    private const val PARALLEL = 4

    suspend fun load(client: StalkerClient, playlistId: Long, hideCensored: Boolean): StalkerImportResult {
        val live = live(client, playlistId)
        val vod = try {
            vod(client, playlistId, hideCensored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StalkerImportResult(emptyList(), emptyList(), e.message ?: e.javaClass.simpleName,
                ImportCompletion(mapOf(ContentKind.LIVE to LoadCompletion.FULL, ContentKind.MOVIE to LoadCompletion.FAILED, ContentKind.SERIES to LoadCompletion.FAILED)))
        }
        return StalkerImportResult(
            live.channels + vod.channels,
            live.groups + vod.groups,
            vod.vodError,
            ImportCompletion(live.completion.kinds + vod.completion.kinds, vod.completion.categories),
        )
    }

    internal suspend fun live(client: StalkerClient, playlistId: Long): StalkerImportResult {
        val genres = client.genres().associateBy { it.id }
        val groups = genres.values.map { ImportedGroup(it.title, ContentKind.LIVE, it.id) }
        val channels = client.channels().mapIndexed { i, c ->
            ImportedChannel(
                groupName = genres[c.genreId]?.title ?: PlaylistRepository.UNGROUPED,
                kind = ContentKind.LIVE,
                channel = ChannelEntity(
                    playlistId = playlistId,
                    channelKey = "stalker:${c.id}",
                    name = c.name,
                    // Resolved through create_link at play time.
                    streamUrl = c.cmd,
                    logoUrl = c.logo,
                    tvgId = c.xmltvId,
                    number = c.number,
                    catchupType = if (c.hasArchive) PlaylistRepository.CATCHUP_STALKER else null,
                    catchupDays = c.archiveDays,
                    sortOrder = i,
                ),
            )
        }
        return StalkerImportResult(channels, groups, completion = ImportCompletion(mapOf(ContentKind.LIVE to LoadCompletion.FULL)))
    }

    internal suspend fun vod(client: StalkerClient, playlistId: Long, hideCensored: Boolean): StalkerImportResult {
        val started = System.nanoTime()
        val categories = withTimeoutOrNull(VOD_BUDGET_MS) { client.vodCategories() }
            ?: return StalkerImportResult(
                emptyList(), emptyList(), "VOD time budget expired",
                ImportCompletion(mapOf(ContentKind.MOVIE to LoadCompletion.PARTIAL, ContentKind.SERIES to LoadCompletion.PARTIAL)),
            )
        val remaining = VOD_BUDGET_MS - (System.nanoTime() - started) / 1_000_000
        return loadVod(playlistId, hideCensored, categories, remaining.coerceAtLeast(1), client::vodItemsResult)
    }

    private const val VOD_BUDGET_MS = 10 * 60 * 1000L

    internal suspend fun loadVod(
        playlistId: Long,
        hideCensored: Boolean,
        declaredCategories: List<StalkerVodCategory>,
        budgetMs: Long = VOD_BUDGET_MS,
        fetch: suspend (String, Int) -> PageCollection<StalkerVodItem>,
    ): StalkerImportResult {
        // A portal that answers with no categories is a bad answer, not an empty film library.
        if (declaredCategories.isEmpty()) {
            return StalkerImportResult(
                emptyList(), emptyList(), "The portal listed no VOD categories",
                ImportCompletion(mapOf(ContentKind.MOVIE to LoadCompletion.PARTIAL, ContentKind.SERIES to LoadCompletion.PARTIAL)),
            )
        }
        val categories = declaredCategories.filterNot { hideCensored && it.censored }
        val gate = Semaphore(PARALLEL)
        val results = arrayOfNulls<Result<PageCollection<StalkerVodItem>>>(categories.size)
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                categories.mapIndexed { index, cat ->
                    async {
                        gate.withPermit {
                            results[index] = try {
                                // Off the UI thread, so the whole category is worth having.
                                Result.success(fetch(cat.id, com.tellybox.tv.data.parser.stalker.MAX_PAGES))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val perCategory = categories.mapIndexed { index, cat ->
            cat to (results[index] ?: Result.success(PageCollection(emptyList(), LoadCompletion.PARTIAL)))
        }

        val groups = ArrayList<ImportedGroup>()
        val channels = ArrayList<ImportedChannel>()
        // Categories come in the portal's order; a mixed one yields a movie and a series group.
        for ((cat, result) in perCategory) {
            val items = result.getOrNull()?.items?.filterNot { hideCensored && it.censored } ?: continue
            val (series, movies) = items.partition { it.isSeries }
            if (movies.isNotEmpty()) groups += ImportedGroup(cat.title, ContentKind.MOVIE, cat.id)
            if (series.isNotEmpty()) groups += ImportedGroup(cat.title, ContentKind.SERIES, cat.id)
            items.forEach { channels += ImportedChannel(cat.title, kindOf(it), row(playlistId, it, channels.size)) }
        }
        val error = perCategory.firstNotNullOfOrNull { (cat, r) ->
            r.exceptionOrNull()?.let { "${cat.title}: ${it.message ?: it.javaClass.simpleName}" }
        }
        return StalkerImportResult(channels, groups, error, ImportCompletion(kinds = mapOf(ContentKind.MOVIE to LoadCompletion.FULL, ContentKind.SERIES to LoadCompletion.FULL), categories = perCategory.associate { (cat, result) ->
            cat.id to (result.getOrNull()?.completion ?: LoadCompletion.FAILED)
        }))
    }

    private fun kindOf(item: StalkerVodItem) = if (item.isSeries) ContentKind.SERIES else ContentKind.MOVIE

    fun row(playlistId: Long, item: StalkerVodItem, order: Int): ChannelEntity {
        val ref = if (item.isSeries) StalkerVodRef.Series(item.id) else StalkerVodRef.Movie(item.id)
        return ChannelEntity(
            playlistId = playlistId,
            channelKey = (if (item.isSeries) "stalker-series:" else "stalker-movie:") + item.id,
            name = item.name,
            streamUrl = ref.marker,
            kind = kindOf(item),
            logoUrl = item.poster,
            sortOrder = order,
            plot = item.description,
            year = item.year,
            cast = item.actors,
            director = item.director,
            genre = item.genres,
            country = item.country,
            durationMinutes = item.durationMinutes,
            rating = item.rating,
        )
    }
}
