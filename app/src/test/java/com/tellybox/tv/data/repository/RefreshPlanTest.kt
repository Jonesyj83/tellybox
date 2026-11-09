package com.tellybox.tv.data.repository

import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ChannelGroupEntity
import com.tellybox.tv.data.db.ContentKind
import org.junit.Assert.*
import org.junit.Test

class RefreshPlanTest {
    private fun channel(key: String, id: Long = 0, playlist: Long = 1) = ChannelEntity(
        id = id, playlistId = playlist, channelKey = key, name = key, streamUrl = "url:$key", addedAt = 0,
    )

    private fun group(name: String, id: Long = 0, playlist: Long = 1, kind: ContentKind = ContentKind.LIVE) =
        ChannelGroupEntity(id = id, playlistId = playlist, name = name, kind = kind)

    @Test fun channelsKeepIdsInIncomingOrderAndUpdateMetadata() {
        val plan = planChannelRefresh(
            listOf(channel("a", 41), channel("b", 72)),
            listOf(channel("b").copy(name = "Updated"), channel("a")),
        )
        assertEquals(listOf(72L, 41L), plan.upserts.map { it.id })
        assertEquals("Updated", plan.upserts.first().name)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test fun channelsInsertNewKeysAndDeleteOnlyMissingKeys() {
        val plan = planChannelRefresh(listOf(channel("gone", 41)), listOf(channel("new", 999)))
        assertEquals(listOf(channel("new")), plan.upserts)
        assertEquals(listOf(41L), plan.deleteIds)
    }

    @Test fun duplicateChannelsCollapseAndRetainExistingId() {
        val plan = planChannelRefresh(
            listOf(channel("a", 41)),
            listOf(channel("a"), channel("a").copy(name = "Last"), channel("new"), channel("new")),
        )
        assertEquals(listOf(41L, 0L), plan.upserts.map { it.id })
        assertEquals("Last", plan.upserts.first().name)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test fun channelKeysAreScopedToPlaylist() {
        val plan = planChannelRefresh(listOf(channel("a", 41)), listOf(channel("a", playlist = 2)))
        assertEquals(0L, plan.upserts.single().id)
        assertEquals(listOf(41L), plan.deleteIds)
    }

    @Test fun groupsKeepIdsInIncomingOrderAndUpdateMetadata() {
        val plan = planGroupRefresh(
            listOf(group("Sports", 18), group("News", 29)),
            listOf(group("News").copy(remoteId = "new"), group("Sports")),
        )
        assertEquals(listOf(29L, 18L), plan.upserts.map { it.id })
        assertEquals("new", plan.upserts.first().remoteId)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test fun groupsInsertNewKeysAndDeleteOnlyMissingKeys() {
        val plan = planGroupRefresh(listOf(group("Gone", 18)), listOf(group("New", 999)))
        assertEquals(listOf(group("New")), plan.upserts)
        assertEquals(listOf(18L), plan.deleteIds)
    }

    @Test fun duplicateGroupsCollapseAndRetainExistingId() {
        val plan = planGroupRefresh(
            listOf(group("Sports", 18)),
            listOf(group("Sports"), group("Sports").copy(remoteId = "last"), group("New"), group("New")),
        )
        assertEquals(listOf(18L, 0L), plan.upserts.map { it.id })
        assertEquals("last", plan.upserts.first().remoteId)
        assertTrue(plan.deleteIds.isEmpty())
    }

    @Test fun groupKeysIncludePlaylistAndKind() {
        val plan = planGroupRefresh(
            listOf(group("Sports", 18)),
            listOf(group("Sports", playlist = 2), group("Sports", kind = ContentKind.MOVIE)),
        )
        assertEquals(listOf(0L, 0L), plan.upserts.map { it.id })
        assertEquals(listOf(18L), plan.deleteIds)
    }

    @Test fun channelsUsePreservedGroupIdsAndNewlyInsertedGroupIds() {
        val groups = planGroupRefresh(
            listOf(group("Sports", 18), group("Gone", 29)),
            listOf(group("New"), group("Sports"), group("Sports", kind = ContentKind.MOVIE)),
        )
        // Room returns the explicit id for existing groups and generated ids for new groups.
        val ids = groups.upserts.zip(listOf(30L, 18L, 31L))
            .associate { (g, id) -> (g.kind to g.name) to id }
        val imported = listOf(
            ImportedChannel("Sports", ContentKind.LIVE, channel("a")),
            ImportedChannel("New", ContentKind.LIVE, channel("b")),
            ImportedChannel("Sports", ContentKind.MOVIE, channel("c")),
        )
        val plan = planChannelRefresh(
            listOf(channel("a", 41).copy(groupId = 29)),
            imported.map { it.withGroupId(ids) },
        )
        assertEquals(listOf(18L, 30L, 31L), plan.upserts.map { it.groupId })
        assertEquals(listOf(41L, 0L, 0L), plan.upserts.map { it.id })
        assertEquals(listOf(29L), groups.deleteIds)
    }

    @Test fun emptyRefreshDeletesAllRowsAndEmptyDatabaseAcceptsAllRows() {
        assertEquals(RefreshPlan<ChannelEntity>(emptyList(), listOf(41L)),
            planChannelRefresh(listOf(channel("a", 41)), emptyList()))
        assertEquals(RefreshPlan<ChannelGroupEntity>(emptyList(), listOf(18L)),
            planGroupRefresh(listOf(group("Sports", 18)), emptyList()))
        assertEquals(listOf(channel("a")), planChannelRefresh(emptyList(), listOf(channel("a"))).upserts)
        assertEquals(listOf(group("Sports")), planGroupRefresh(emptyList(), listOf(group("Sports"))).upserts)
    }
}
