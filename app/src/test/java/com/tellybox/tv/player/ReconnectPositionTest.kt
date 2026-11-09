package com.tellybox.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPositionTest {

    @Test
    fun `a live channel goes back to the edge`() {
        assertEquals(0L, resumeAt(isCatchupOrFile = false, playerPosition = 42_000L, savedPosition = 90_000L))
        assertEquals(0L, resumeAt(isCatchupOrFile = false, playerPosition = null, savedPosition = 90_000L))
    }

    @Test
    fun `a catch-up stream keeps watching where it was`() {
        assertEquals(1_800_000L, resumeAt(isCatchupOrFile = true, playerPosition = 1_800_000L, savedPosition = 0L))
    }

    @Test
    fun `a recording keeps watching where it was`() {
        assertEquals(600_000L, resumeAt(isCatchupOrFile = true, playerPosition = 600_000L, savedPosition = 300_000L))
    }

    @Test
    fun `without a position from the player the saved one is used`() {
        assertEquals(500_000L, resumeAt(isCatchupOrFile = true, playerPosition = null, savedPosition = 500_000L))
    }

    @Test
    fun `a player that reports zero is taken at its word`() {
        // The player exists and says zero, which is a real position near the start of the archive.
        assertEquals(0L, resumeAt(isCatchupOrFile = true, playerPosition = 0L, savedPosition = 500_000L))
    }
}
