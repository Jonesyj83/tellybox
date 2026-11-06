package com.openstream.tv.ui.channels

import com.openstream.tv.ui.channels.PreviewController.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewControllerTest {

    private fun PreviewController.previewNow(id: Long) {
        assertEquals(Action.Start(id, 0), pick(id))
        assertTrue(commit(id))
    }

    @Test
    fun `first OK previews, second OK on the same channel goes full screen`() {
        val c = PreviewController()
        c.previewNow(5)
        assertEquals(5L, c.previewing)
        assertEquals(Action.FullScreen(5), c.pick(5))
    }

    @Test
    fun `browsing other channels leaves the preview alone`() {
        val c = PreviewController()
        c.previewNow(5)
        assertEquals(Action.None, c.focus(6))
        assertEquals(Action.None, c.focus(7))
        assertEquals(5L, c.previewing)
        assertNull(c.pending)
    }

    @Test
    fun `preview window goes full screen on what it shows, wherever focus has gone`() {
        val c = PreviewController()
        assertEquals(Action.None, c.tile())
        c.previewNow(5)
        c.focus(9)
        assertEquals(Action.FullScreen(5), c.tile())
    }

    @Test
    fun `long press goes full screen on the focused channel at once`() {
        val c = PreviewController()
        c.previewNow(5)
        assertEquals(Action.FullScreen(8), c.longPress(8))
        assertEquals(Action.FullScreen(3), PreviewController().longPress(3))
    }

    @Test
    fun `OK on another channel switches the preview`() {
        val c = PreviewController()
        c.previewNow(5)
        c.previewNow(6)
        assertEquals(6L, c.previewing)
    }

    @Test
    fun `failed preview retries on OK instead of going full screen`() {
        val c = PreviewController()
        c.previewNow(5)
        c.failed(5)
        assertEquals(Action.Start(5, 0), c.pick(5))
        assertTrue(c.commit(5))
        assertFalse(c.failed)
        assertEquals(Action.FullScreen(5), c.pick(5))
        // The tile still offers full screen; the player there shows the error and a retry.
        c.failed(5)
        assertEquals(Action.FullScreen(5), c.tile())
    }

    @Test
    fun `a late failure from an old channel does not mark the new one`() {
        val c = PreviewController()
        c.previewNow(5)
        c.previewNow(6)
        c.failed(5)
        assertFalse(c.failed)
    }

    @Test
    fun `following focus waits before starting and only the last row wins`() {
        val c = PreviewController(followFocus = true)
        assertEquals(Action.Start(1, PreviewController.FOCUS_DELAY_MS), c.focus(1))
        assertEquals(Action.Start(2, PreviewController.FOCUS_DELAY_MS), c.focus(2))
        assertEquals(Action.Start(3, PreviewController.FOCUS_DELAY_MS), c.focus(3))
        assertFalse(c.commit(1))
        assertFalse(c.commit(2))
        assertTrue(c.commit(3))
        assertEquals(3L, c.previewing)
    }

    @Test
    fun `following focus, returning to the previewing row cancels the pending switch`() {
        val c = PreviewController(followFocus = true)
        c.focus(1)
        c.commit(1)
        c.focus(2)
        assertEquals(Action.Cancel, c.focus(1))
        assertNull(c.pending)
        assertEquals(1L, c.previewing)
        assertEquals(Action.None, c.focus(1))
    }

    @Test
    fun `following focus, OK before the preview starts goes straight to full screen`() {
        val c = PreviewController(followFocus = true)
        c.focus(4)
        assertEquals(Action.FullScreen(4), c.pick(4))
        assertFalse(c.commit(4))
    }

    @Test
    fun `adopting a channel handed back from full screen`() {
        val c = PreviewController()
        c.previewNow(5)
        c.adopt(9)
        assertEquals(9L, c.previewing)
        assertEquals(Action.FullScreen(9), c.pick(9))
    }

    @Test
    fun `stop clears everything`() {
        val c = PreviewController()
        c.previewNow(5)
        c.stop()
        assertNull(c.previewing)
        assertEquals(Action.None, c.tile())
    }

    @Test
    fun `back steps out one level at a time and only exits from the groups`() {
        assertEquals(HomeBack.FOCUS_GROUPS, homeBack(HomeArea.GUIDE, searching = false))
        assertEquals(HomeBack.FOCUS_GROUPS, homeBack(HomeArea.PREVIEW, searching = true))
        assertEquals(HomeBack.FOCUS_GROUPS, homeBack(HomeArea.HEADER, searching = false))
        assertEquals(HomeBack.CLEAR_SEARCH, homeBack(HomeArea.GROUPS, searching = true))
        assertEquals(HomeBack.EXIT, homeBack(HomeArea.GROUPS, searching = false))
    }
}
