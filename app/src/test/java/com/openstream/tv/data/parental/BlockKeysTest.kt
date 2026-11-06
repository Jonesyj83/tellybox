package com.openstream.tv.data.parental

import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.ChannelGroupEntity
import com.openstream.tv.data.db.ContentKind
import org.junit.Assert.*
import org.junit.Test

class BlockKeysTest {
    @Test fun channelBlockSurvivesReplacementAndDisplayEdits() {
        val original = ChannelEntity(id = 10, playlistId = 1, groupId = 4, channelKey = "live:42", name = "Original", streamUrl = "http://example.test/42")
        val blocked = setOf(BlockKeys.channel(original))
        val imported = original.copy(id = 99, groupId = 77, name = "New name", streamUrl = "http://example.test/new/42")
        assertTrue(BlockKeys.channel(imported) in blocked)
        assertFalse(BlockKeys.channel(imported.copy(playlistId = 2)) in blocked)
        assertFalse(BlockKeys.channel(imported.copy(channelKey = "live:43")) in blocked)
    }
    @Test fun groupBlockSurvivesReplacementWithoutLeakingToOtherKindsOrPlaylists() {
        val original = ChannelGroupEntity(id = 4, playlistId = 1, name = "Restricted")
        val blocked = setOf(BlockKeys.group(original))
        assertTrue(BlockKeys.group(original.copy(id = 77, sortOrder = 3)) in blocked)
        assertFalse(BlockKeys.group(original.copy(kind = ContentKind.MOVIE)) in blocked)
        assertFalse(BlockKeys.group(original.copy(playlistId = 2)) in blocked)
    }
}
