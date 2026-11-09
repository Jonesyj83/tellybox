package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.*
import com.tellybox.tv.data.parser.stalker.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportCompletionTest {
    private fun row(id: Long, kind: ContentKind, group: Long? = null) = ChannelEntity(
        id = id, playlistId = 1, channelKey = "$kind:$id", name = "Title", streamUrl = "url", kind = kind, groupId = group,
    )

    private class FakeXtream {
        suspend fun movies(): List<ChannelEntity> = throw java.io.IOException("timeout")
        suspend fun series() = listOf(ChannelEntity(playlistId = 1, channelKey = "series:new", name = "New", streamUrl = "url", kind = ContentKind.SERIES))
    }

    @Test fun failedMoviesSurviveWhileNewSeriesImportAndVanishedSeriesDisappear() = runBlocking {
        val client = FakeXtream()
        val incoming = mutableListOf<ChannelEntity>()
        val movie = optionalImport { incoming += client.movies() }
        val series = optionalImport { incoming += client.series() }
        val completion = ImportCompletion(mapOf(ContentKind.MOVIE to movie, ContentKind.SERIES to series))
        val old = listOf(row(1, ContentKind.MOVIE), row(2, ContentKind.SERIES))
        val plan = planChannelRefresh(old, incoming) { completion.canPrune(it.kind, null) }
        val stored = old.filterNot { it.id in plan.deleteIds } + plan.upserts
        assertEquals(listOf("MOVIE:1", "series:new"), stored.map { it.channelKey })
        assertEquals(setOf(ContentKind.MOVIE), completion.keptKinds)
    }

    private class FakePages(private val fail: Boolean) {
        suspend fun fetch(page: Int): Pair<Int?, List<String>> = when {
            page == 1 -> 2 to listOf("new")
            fail -> throw java.io.IOException("timeout")
            else -> 2 to listOf("other")
        }
    }

    @Test fun partialStalkerCategoryKeepsMissingRowsButSuccessfulCategoryPrunes() = runBlocking {
        val partial = collectPagesResult(fetch = FakePages(true)::fetch)
        val full = collectPagesResult(fetch = FakePages(false)::fetch)
        assertEquals(LoadCompletion.PARTIAL, partial.completion)
        assertEquals(listOf("new"), partial.items)
        assertEquals(LoadCompletion.FULL, full.completion)
        val groups = listOf(
            ChannelGroupEntity(id = 10, playlistId = 1, name = "Partial", kind = ContentKind.MOVIE, remoteId = "p"),
            ChannelGroupEntity(id = 20, playlistId = 1, name = "Full", kind = ContentKind.MOVIE, remoteId = "f"),
            ChannelGroupEntity(id = 30, playlistId = 1, name = "Failed", kind = ContentKind.SERIES, remoteId = "bad"),
        )
        val completion = ImportCompletion(categories = mapOf("p" to partial.completion, "f" to full.completion, "bad" to LoadCompletion.FAILED))
        val plan = planChannelRefresh(listOf(row(1, ContentKind.MOVIE, 10), row(2, ContentKind.MOVIE, 20), row(3, ContentKind.SERIES, 30)), emptyList()) {
            completion.canPrune(it.kind, groups.find { g -> g.id == it.groupId })
        }
        assertEquals(listOf(2L), plan.deleteIds)
        assertTrue(completion.canPrune(ContentKind.LIVE, null))
        assertEquals(listOf(20L), planGroupRefresh(groups, emptyList()) { completion.canPrune(it.kind, it) }.deleteIds)
    }

    @Test fun pageLimitAndPrematureEmptyPageArePartial() = runBlocking {
        assertEquals(LoadCompletion.PARTIAL, collectPagesResult(1) { 100 to listOf(1) }.completion)
        assertEquals(LoadCompletion.PARTIAL, collectPagesResult<Int> { 100 to emptyList() }.completion)
        assertEquals(LoadCompletion.FULL, collectPagesResult<Int> { 0 to emptyList() }.completion)
    }

    @Test fun fullRefreshPrunesVanishedMoviesAndSeries() {
        val completion = ImportCompletion()
        assertEquals(listOf(1L, 2L), planChannelRefresh(listOf(row(1, ContentKind.MOVIE), row(2, ContentKind.SERIES)), emptyList()) {
            completion.canPrune(it.kind, null)
        }.deleteIds)
    }

    @Test fun stalkerCategoryFailuresAndPartialPagesReachTheReconciliationPlan() = runBlocking {
        val title = StalkerVodItem("new", "New series", true, "good", null, null, null, null,
            null, null, null, null, null, false)
        val result = StalkerImport.loadVod(1, false, listOf(
            StalkerVodCategory("bad", "Movies", false),
            StalkerVodCategory("partial", "Partial movies", false),
            StalkerVodCategory("good", "Series", false),
        )) { category, _ ->
            when (category) {
                "bad" -> throw java.io.IOException("timeout")
                "partial" -> PageCollection(emptyList(), LoadCompletion.PARTIAL)
                else -> PageCollection(listOf(title), LoadCompletion.FULL)
            }
        }
        assertEquals(LoadCompletion.FAILED, result.completion.categories["bad"])
        assertEquals(LoadCompletion.PARTIAL, result.completion.categories["partial"])
        assertNotNull(result.vodError)
        val groups = listOf(
            ChannelGroupEntity(id = 10, playlistId = 1, name = "Movies", kind = ContentKind.MOVIE, remoteId = "bad"),
            ChannelGroupEntity(id = 20, playlistId = 1, name = "Partial movies", kind = ContentKind.MOVIE, remoteId = "partial"),
            ChannelGroupEntity(id = 30, playlistId = 1, name = "Series", kind = ContentKind.SERIES, remoteId = "good"),
        )
        val old = listOf(row(1, ContentKind.MOVIE, 10), row(2, ContentKind.MOVIE, 20), row(3, ContentKind.SERIES, 30))
        val plan = planChannelRefresh(old, result.channels.map { it.channel }) {
            result.completion.canPrune(it.kind, groups.find { g -> g.id == it.groupId })
        }
        val stored = old.filterNot { it.id in plan.deleteIds } + plan.upserts
        assertEquals(listOf("MOVIE:1", "MOVIE:2", "stalker-series:new"), stored.map { it.channelKey })
    }
}
