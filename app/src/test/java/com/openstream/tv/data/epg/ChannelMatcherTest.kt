package com.openstream.tv.data.epg

import org.junit.Assert.*
import org.junit.Test

class ChannelMatcherTest {
    private fun channel(id: Long, name: String, tvgId: String? = null) =
        ChannelMatcher.Channel(id, tvgId, listOf(name))
    private fun guide(id: String, vararg names: String) = ChannelMatcher.Guide(id, names.toList())

    @Test fun realisticQualityAndRegionNames() {
        val matches = ChannelMatcher.match(listOf(
            channel(1, "BBC One HD"), channel(2, "AU: Fox Sports 503 | HD"), channel(3, "Sky Sports F1 FHD"),
        ), listOf(guide("bbc", "BBC One"), guide("fox", "Fox Sports 503"), guide("sky", "Sky Sports F1")))
        assertEquals(listOf("bbc", "fox", "sky"), matches.map { it.guideId })
        assertTrue(matches.all { it.pass == "normalized" })
    }

    @Test fun normalizationIsStableAndLocaleIndependent() {
        assertEquals("fox sports 503", ChannelMatcher.normalize("  AU:  Fox  Sports 503 [VIP] | HD HEVC backup "))
        assertEquals("bbc one", ChannelMatcher.normalize("UK | BBC One (London) FHD by Example"))
        assertEquals("sky sports f1", ChannelMatcher.normalize("Sky Sports F1"))
    }

    @Test fun badIdFallsBackToAnyDisplayName() {
        val match = ChannelMatcher.match(listOf(channel(1, "BBC One", "wrong")), listOf(guide("bbc", "Other language", "BBC One"))).single()
        assertEquals("bbc", match.guideId)
        assertEquals("name", match.pass)
    }

    @Test fun tvgNameCanMatchWhenVisibleNameCannot() {
        val matches = ChannelMatcher.match(listOf(ChannelMatcher.Channel(1, "wrong", listOf("BBC One", "Provider slot 99"))), listOf(guide("bbc", "BBC One")))
        assertEquals("name", matches.single().pass)
    }

    @Test fun exactIdWinsBeforeNamesAndContainment() {
        val matches = ChannelMatcher.match(listOf(channel(1, "BBC One"), channel(2, "Different", "bbc")), listOf(guide("bbc", "BBC One")))
        assertEquals(listOf(ChannelMatcher.Match(2, "bbc", "id")), matches)
    }

    @Test fun regionalDuplicatesNeverShareGuideAndWinnerIsDeterministic() {
        val channels = listOf(channel(9, "AU: News HD"), channel(2, "UK | News HD"))
        val guides = listOf(guide("news", "News"))
        assertEquals(listOf(ChannelMatcher.Match(2, "news", "normalized")), ChannelMatcher.match(channels, guides))
        assertEquals(ChannelMatcher.match(channels, guides), ChannelMatcher.match(channels.reversed(), guides))
    }

    @Test fun ambiguousGuideNamesAreNotGuessed() {
        assertTrue(ChannelMatcher.match(listOf(channel(1, "News HD")), listOf(guide("au", "News"), guide("uk", "News"))).isEmpty())
    }

    @Test fun unrelatedAndShortPartialNamesRemainUnmatched() {
        assertTrue(ChannelMatcher.match(listOf(channel(1, "Unknown Shopping"), channel(2, "Sky Sports F2"), channel(3, "One"), channel(4, "BBC One +1")),
            listOf(guide("bbc", "BBC One"), guide("sky", "Sky Sports F1"))).isEmpty())
    }

    @Test fun uniqueTokenContainmentWorksButTiesRemainUnmatched() {
        val channels = listOf(channel(1, "BBC One London"))
        assertEquals("contained", ChannelMatcher.match(channels, listOf(guide("bbc", "BBC One"))).single().pass)
        assertTrue(ChannelMatcher.match(listOf(channel(1, "BBC One")), listOf(guide("a", "BBC One London"), guide("b", "BBC One Scotland"))).isEmpty())
    }

    @Test fun largePlaylistUsesNameIndexes() {
        val channels = (1L..31000).map { channel(it, "Station $it HD") }
        val guides = (1..31000).map { guide("id$it", "Station $it") }
        assertEquals(31000, ChannelMatcher.match(channels, guides).size)
    }
}
