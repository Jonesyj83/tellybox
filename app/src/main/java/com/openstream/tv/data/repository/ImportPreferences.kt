package com.openstream.tv.data.repository

import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ChannelGroupEntity

/** Retains local choices without keeping stale provider metadata. */
internal fun ChannelEntity.withLocalChoices(previous: ChannelEntity?, newOrder: Int): ChannelEntity = copy(
    id = previous?.id ?: 0,
    sortOrder = previous?.sortOrder ?: newOrder,
    hidden = previous?.hidden ?: false,
)

internal fun ChannelGroupEntity.withLocalChoices(previous: ChannelGroupEntity?, newOrder: Int): ChannelGroupEntity = copy(
    id = previous?.id ?: 0,
    sortOrder = previous?.sortOrder ?: newOrder,
    hidden = previous?.hidden ?: false,
)
