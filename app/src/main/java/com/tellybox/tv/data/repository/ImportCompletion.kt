package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.data.db.ChannelGroupEntity
import com.tellybox.tv.data.parser.stalker.LoadCompletion
import kotlinx.coroutines.CancellationException

internal data class ImportCompletion(
    val kinds: Map<ContentKind, LoadCompletion> = ContentKind.entries.associateWith { LoadCompletion.FULL },
    val categories: Map<String, LoadCompletion> = emptyMap(),
) {
    val keptKinds get() = kinds.filterValues { it != LoadCompletion.FULL }.keys +
        if (categories.values.any { it != LoadCompletion.FULL }) setOf(ContentKind.MOVIE, ContentKind.SERIES) else emptySet()
    // Missing titles are evidence of removal only after their provider scope finished loading.
    fun canPrune(kind: ContentKind, group: ChannelGroupEntity?): Boolean {
        if (kinds[kind] != LoadCompletion.FULL) return false
        if (kind == ContentKind.LIVE) return true
        return group?.remoteId?.let { categories[it] != LoadCompletion.PARTIAL && categories[it] != LoadCompletion.FAILED } ?: categories.values.all { it == LoadCompletion.FULL }
    }
}

/**
 * Completion for one batch of VOD categories. Categories not walked yet are marked PARTIAL, so
 * their stored rows survive while the finished ones are pruned normally.
 */
internal fun batchedCompletion(
    finished: Map<String, LoadCompletion>,
    remaining: List<String>,
): ImportCompletion = ImportCompletion(
    kinds = mapOf(ContentKind.MOVIE to LoadCompletion.FULL, ContentKind.SERIES to LoadCompletion.FULL),
    categories = finished + remaining.associateWith { LoadCompletion.PARTIAL },
)

/**
 * A provider list that came back empty (or as an error object, which maps to nothing) is a
 * failed load, not "the provider has none": treating it as complete would prune every row.
 */
internal fun <T> requireLoaded(items: List<T>, what: String): List<T> {
    if (items.isEmpty()) throw IllegalStateException("No $what came back from the server")
    return items
}

internal suspend fun optionalImport(load: suspend () -> Unit): LoadCompletion = try {
    load()
    LoadCompletion.FULL
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadCompletion.FAILED
}
