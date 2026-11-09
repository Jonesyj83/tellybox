package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ChannelGroupEntity
import com.tellybox.tv.data.db.ContentKind

internal data class RefreshPlan<T>(val upserts: List<T>, val deleteIds: List<Long>)

internal fun planChannelRefresh(
    existing: List<ChannelEntity>,
    incoming: List<ChannelEntity>,
): RefreshPlan<ChannelEntity> = planRefresh(
    existing, incoming,
    key = { it.playlistId to it.channelKey },
    id = { it.id },
    withId = { row, id -> row.copy(id = id) },
)

internal fun planGroupRefresh(
    existing: List<ChannelGroupEntity>,
    incoming: List<ChannelGroupEntity>,
): RefreshPlan<ChannelGroupEntity> = planRefresh(
    existing, incoming,
    key = { Triple(it.playlistId, it.kind, it.name) },
    id = { it.id },
    withId = { row, id -> row.copy(id = id) },
)

private fun <T, K> planRefresh(
    existing: List<T>,
    incoming: List<T>,
    key: (T) -> K,
    id: (T) -> Long,
    withId: (T, Long) -> T,
): RefreshPlan<T> {
    val oldByKey = existing.associateBy(key)
    // Match the unique index, retaining the last provider value for repeated keys.
    val incomingByKey = incoming.associateBy(key)
    return RefreshPlan(
        upserts = incomingByKey.map { (key, row) -> withId(row, oldByKey[key]?.let(id) ?: 0) },
        deleteIds = existing.filter { key(it) !in incomingByKey }.map(id),
    )
}

internal fun ImportedChannel.withGroupId(ids: Map<Pair<ContentKind, String>, Long>): ChannelEntity =
    channel.copy(groupId = ids[kind to groupName])
