package com.tellybox.tv.data.repository

import com.tellybox.tv.data.parser.xmltv.XmltvListener
import com.tellybox.tv.data.parser.xmltv.XmltvParser
import com.tellybox.tv.data.parser.xmltv.XmltvStats
import org.junit.Assert.*
import org.junit.Test

class EpgRefreshGuardTest {
    @Test fun failedParseCannotAuthorizeDeletionEvenAfterReadingRows() {
        val stats = XmltvParser.parse("<tv><channel id=\"a\"/><programme channel=\"a\" start=\"20261107000000 +0000\" stop=\"20261107010000 +0000\"><title>News</title></programme>", object : XmltvListener {})
        assertEquals(1, stats.programmes)
        assertNotNull(stats.error)
        assertThrows(IllegalStateException::class.java) { EpgRefreshGuard.requireReplacement(stats, 1) }
    }

    @Test fun emptyOrFilteredImportCannotReplaceExistingGuide() {
        assertThrows(IllegalStateException::class.java) { EpgRefreshGuard.requireReplacement(XmltvStats(1, 0, 0), 0) }
        assertThrows(IllegalStateException::class.java) { EpgRefreshGuard.requireReplacement(XmltvStats(1, 4, 0), 0) }
        EpgRefreshGuard.requireReplacement(XmltvStats(1, 4, 0), 4)
    }

    @Test fun zeroMatchesRetainsEveryProgrammeWithoutInventingMatches() {
        assertTrue(EpgRefreshGuard.keepProgramme(false, emptySet(), "a"))
        assertFalse(EpgRefreshGuard.keepProgramme(false, setOf("b"), "a"))
        assertTrue(EpgRefreshGuard.keepProgramme(true, setOf("b"), "a"))
    }
}
