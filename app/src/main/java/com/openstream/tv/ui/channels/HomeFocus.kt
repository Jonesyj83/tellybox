package com.openstream.tv.ui.channels

/** Where focus is on the home screen, for working out what Back does. */
enum class HomeArea { GROUPS, GUIDE, PREVIEW, HEADER, RAIL }

enum class HomeBack { FOCUS_GROUPS, CLEAR_SEARCH, EXIT }

/** Places Up/Down can send focus to on the home screen. */
enum class FocusTarget { GRID, GROUPS, PREVIEW, WATCH, HEADER, RAIL }

/**
 * Up/Down between the home screen's areas, decided here instead of by Android's focus
 * search so the same key always lands in the same place. [previewing] is whether
 * something is in the preview window (the Watch button only exists then).
 *
 * grid row 0 --Up--> preview window --Up--> Watch button (header)
 * header --Down--> preview window --Down--> grid
 * Without a preview: grid row 0 --Up--> groups, header --Down--> grid.
 */
object HomeFocus {
    fun leftFrom(area: HomeArea): FocusTarget = when (area) {
        HomeArea.GROUPS, HomeArea.RAIL -> FocusTarget.RAIL
        else -> FocusTarget.GROUPS
    }

    fun rightFrom(area: HomeArea): FocusTarget = when (area) {
        HomeArea.RAIL -> FocusTarget.GROUPS
        HomeArea.GROUPS -> FocusTarget.GRID
        HomeArea.PREVIEW -> FocusTarget.WATCH
        else -> FocusTarget.GRID
    }

    fun verticalIndex(index: Int, count: Int, up: Boolean): Int =
        (index + if (up) -1 else 1).coerceIn(0, (count - 1).coerceAtLeast(0))

    fun upFromGridTop(previewing: Boolean) = if (previewing) FocusTarget.PREVIEW else FocusTarget.GROUPS

    fun upFromPreview(previewing: Boolean) = if (previewing) FocusTarget.WATCH else FocusTarget.HEADER

    fun downFromPreview() = FocusTarget.GRID

    fun downFromHeader(previewing: Boolean) = if (previewing) FocusTarget.PREVIEW else FocusTarget.GRID

    /** Up from the first group goes to the preview window, the same as Left. */
    fun upFromGroupsTop(previewing: Boolean) = if (previewing) FocusTarget.PREVIEW else FocusTarget.HEADER
}

/**
 * Back steps outwards one level at a time: anywhere in the guide, the preview or the
 * buttons goes to the groups column; from there it clears a search, and only then leaves.
 */
fun homeBack(area: HomeArea, searching: Boolean): HomeBack = when {
    area != HomeArea.GROUPS -> HomeBack.FOCUS_GROUPS
    searching -> HomeBack.CLEAR_SEARCH
    else -> HomeBack.EXIT
}
