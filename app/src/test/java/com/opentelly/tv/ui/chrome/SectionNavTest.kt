package com.opentelly.tv.ui.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionNavTest {

    @Test fun pickingTheCurrentSectionJustMovesIntoIt() {
        Section.entries.forEach { assertEquals(RailMove.Stay, SectionNav.move(it, it)) }
    }

    @Test fun liveIsAlwaysAStepBack() {
        assertEquals(RailMove.Live(openSearch = false), SectionNav.move(Section.MOVIES, Section.LIVE))
        assertEquals(RailMove.Live(openSearch = false), SectionNav.move(Section.SETTINGS, Section.LIVE))
    }

    @Test fun searchOpensOverTheLiveScreen() {
        assertEquals(RailMove.Live(openSearch = true), SectionNav.move(Section.RECORDINGS, Section.SEARCH))
        assertEquals(RailMove.Stay, SectionNav.move(Section.LIVE, Section.SEARCH))
    }

    @Test fun sectionsReplaceEachOtherSoBackStaysOneStepFromLive() {
        assertEquals(RailMove.Open(Section.MOVIES, replaceCurrent = false), SectionNav.move(Section.LIVE, Section.MOVIES))
        assertEquals(RailMove.Open(Section.PLAYLISTS, replaceCurrent = true), SectionNav.move(Section.MOVIES, Section.PLAYLISTS))
        assertEquals(RailMove.Open(Section.MULTIVIEW, replaceCurrent = true), SectionNav.move(Section.SETTINGS, Section.MULTIVIEW))
    }
}
