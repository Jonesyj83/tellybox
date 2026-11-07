package com.openstream.tv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class VodTitleTest {
    @Test fun bracketedYear() {
        assertEquals(VodTitle("Heat", "1995"), VodTitle.parse("Heat (1995)"))
        assertEquals(VodTitle("Dune Part Two", "2024"), VodTitle.parse(" Dune Part Two [2024] "))
    }

    @Test fun dashedOrTrailingYear() {
        assertEquals(VodTitle("Alien", "1979"), VodTitle.parse("Alien - 1979"))
        assertEquals(VodTitle("Arrival", "2016"), VodTitle.parse("Arrival 2016"))
    }

    @Test fun numbersThatAreNotYearsStayInTheTitle() {
        assertEquals(VodTitle("2001", null), VodTitle.parse("2001"))
        assertEquals(VodTitle("Blade Runner 2049", null), VodTitle.parse("Blade Runner 2049", maxYear = 2027))
        assertEquals(VodTitle("Blade Runner 2049", "2017"), VodTitle.parse("Blade Runner 2049 (2017)", maxYear = 2027))
        assertEquals(VodTitle("Apollo 13", null), VodTitle.parse("Apollo 13"))
        assertEquals(VodTitle("Se7en (Director's Cut)", null), VodTitle.parse("Se7en (Director's Cut)"))
        assertEquals(VodTitle("Room1999", null), VodTitle.parse("Room1999"))
    }
}
