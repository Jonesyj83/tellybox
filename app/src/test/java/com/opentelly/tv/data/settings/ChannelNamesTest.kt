package com.opentelly.tv.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNamesTest {
    @Test fun removesRepeatedEdgeTokens() {
        assertEquals("News", ChannelNames.display("UK: News | HD FHD", "UK: \n| HD\nFHD"))
    }

    @Test fun preservesInteriorAndCase() {
        assertEquals("News HD Today", ChannelNames.display("News HD Today", "HD"))
        assertEquals("uk: News", ChannelNames.display("uk: News", "UK: "))
    }

    @Test fun emptyConfigurationPreservesRawName() {
        assertEquals(" UK: News ", ChannelNames.display(" UK: News ", "\n  \n"))
    }

    @Test fun neverErasesEntireName() {
        assertEquals("FHD", ChannelNames.display("FHD", "FHD"))
    }

    @Test fun prefersLongestTokenAndTreatsPatternsLiterally() {
        assertEquals("News", ChannelNames.display("UK: News.*", "UK\nUK: \n.*"))
        assertEquals("News", ChannelNames.display("News", ".*"))
    }
}
