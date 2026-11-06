package com.openstream.tv.data.parental

import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ChannelGroupEntity

/** Provider identity and group names remain stable when imports replace Room rows. */
object BlockKeys {
    fun channel(c: ChannelEntity) = "c:${c.playlistId}:${c.channelKey}"
    fun group(g: ChannelGroupEntity) = "g:${g.playlistId}:${g.kind}:${g.name}"
}
