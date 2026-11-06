package com.openstream.tv.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFocusTest {

    @Test
    fun `rail categories guide route works in both directions`() {
        assertEquals(FocusTarget.GROUPS, HomeFocus.rightFrom(HomeArea.RAIL))
        assertEquals(FocusTarget.GRID, HomeFocus.rightFrom(HomeArea.GROUPS))
        assertEquals(FocusTarget.GROUPS, HomeFocus.leftFrom(HomeArea.GUIDE))
        assertEquals(FocusTarget.RAIL, HomeFocus.leftFrom(HomeArea.GROUPS))
    }

    @Test
    fun `vertical movement stays inside rail and category bounds`() {
        assertEquals(0, HomeFocus.verticalIndex(0, 6, up = true))
        assertEquals(1, HomeFocus.verticalIndex(0, 6, up = false))
        assertEquals(5, HomeFocus.verticalIndex(5, 6, up = false))
        assertEquals(4, HomeFocus.verticalIndex(5, 6, up = true))
        assertEquals(8, HomeFocus.verticalIndex(7, 20, up = false))
        assertEquals(6, HomeFocus.verticalIndex(7, 20, up = true))
    }

    @Test
    fun `back from rail reaches categories before exit`() {
        assertEquals(HomeBack.FOCUS_GROUPS, homeBack(HomeArea.RAIL, searching = false))
        assertEquals(HomeBack.EXIT, homeBack(HomeArea.GROUPS, searching = false))
    }

    @Test
    fun `up from the top of the guide goes to the preview window`() {
        assertEquals(FocusTarget.PREVIEW, HomeFocus.upFromGridTop(previewing = true))
    }

    @Test
    fun `up from the top of the guide goes to the groups when nothing is previewing`() {
        assertEquals(FocusTarget.GROUPS, HomeFocus.upFromGridTop(previewing = false))
    }

    @Test
    fun `up from the preview window reaches the watch button`() {
        assertEquals(FocusTarget.WATCH, HomeFocus.upFromPreview(previewing = true))
    }

    @Test
    fun `down from the preview window returns to the guide`() {
        assertEquals(FocusTarget.GRID, HomeFocus.downFromPreview())
    }

    @Test
    fun `down from the header lands on the preview window when it is showing`() {
        assertEquals(FocusTarget.PREVIEW, HomeFocus.downFromHeader(previewing = true))
        assertEquals(FocusTarget.GRID, HomeFocus.downFromHeader(previewing = false))
    }

    @Test
    fun `up from the first group reaches the preview window`() {
        assertEquals(FocusTarget.PREVIEW, HomeFocus.upFromGroupsTop(previewing = true))
        assertEquals(FocusTarget.HEADER, HomeFocus.upFromGroupsTop(previewing = false))
    }

    @Test
    fun `preview survives browsing so the watch route stays open`() {
        val c = PreviewController()
        assertEquals(PreviewController.Action.Start(7, 0), c.pick(7))
        assertEquals(true, c.commit(7))
        // Moving focus around does not touch the preview.
        assertEquals(PreviewController.Action.None, c.focus(8))
        assertEquals(PreviewController.Action.None, c.focus(9))
        assertEquals(7L, c.previewing)
        // The window itself still goes full screen on what it shows.
        assertEquals(PreviewController.Action.FullScreen(7), c.tile())
    }
}
