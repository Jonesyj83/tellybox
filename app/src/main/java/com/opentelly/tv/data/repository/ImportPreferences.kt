package com.opentelly.tv.data.repository

import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ChannelGroupEntity

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
