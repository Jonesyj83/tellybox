package com.opentelly.tv.data.repository

import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ChannelGroupEntity
import org.junit.Assert.*
import org.junit.Test

class ImportPreferencesTest {
    @Test fun reimportKeepsChannelChoicesAndNewProviderData() {
        val old = ChannelEntity(id = 18, playlistId = 1, channelKey = "live:1", name = "Old", streamUrl = "old", sortOrder = 4, hidden = true)
        val fresh = old.copy(id = 0, name = "New", streamUrl = "new", sortOrder = 99, hidden = false)
        val merged = fresh.withLocalChoices(old, 100)
        assertEquals(18L, merged.id)
        assertEquals(4, merged.sortOrder)
        assertTrue(merged.hidden)
        assertEquals("New", merged.name)
        assertEquals("new", merged.streamUrl)
    }

    @Test fun reimportKeepsGroupOrderAndVisibility() {
        val old = ChannelGroupEntity(id = 7, playlistId = 1, name = "Sports", sortOrder = 2, hidden = true)
        val merged = old.copy(id = 0, hidden = false, sortOrder = 0).withLocalChoices(old, 20)
        assertEquals(old, merged)
    }

    @Test fun newlyImportedRowsAppendAfterLocalOrder() {
        val channel = ChannelEntity(playlistId = 1, channelKey = "new", name = "New", streamUrl = "url")
        assertEquals(21, channel.withLocalChoices(null, 21).sortOrder)
        val group = ChannelGroupEntity(playlistId = 1, name = "New")
        assertEquals(12, group.withLocalChoices(null, 12).sortOrder)
        assertFalse(group.withLocalChoices(null, 12).hidden)
    }
}
