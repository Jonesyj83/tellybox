package com.openstream.tv.ui.channels

/**
 * Decides what the home screen does with its preview window as the user moves around
 * and presses OK. Plain state, no Android, so the rules can be tested:
 *
 * - OK on a channel previews it; OK on the channel already previewing goes full screen.
 * - Browsing elsewhere never changes the preview unless [followFocus] is on, in which case
 *   resting on a row for [FOCUS_DELAY_MS] previews it (quick scrolling doesn't).
 * - Long-press OK goes full screen on whatever channel is focused, at once.
 * - The preview window itself always goes full screen on what it shows.
 */
class PreviewController(var followFocus: Boolean = false) {

    sealed interface Action {
        data object None : Action
        /** Start previewing after [delayMs]; any earlier pending start is superseded. */
        data class Start(val channelId: Long, val delayMs: Long) : Action
        /** Drop a pending start that hasn't happened yet. */
        data object Cancel : Action
        data class FullScreen(val channelId: Long) : Action
    }

    /** Channel in the preview window, whether or not it has managed to play. */
    var previewing: Long? = null
        private set

    /** Scheduled to start but not started yet. */
    var pending: Long? = null
        private set

    /** The preview stream failed; OK on its row retries rather than going full screen. */
    var failed = false
        private set

    fun pick(channelId: Long): Action = when {
        channelId == previewing && !failed -> {
            pending = null
            Action.FullScreen(channelId)
        }
        // Following focus, the row's preview was about to start: OK means "watch this".
        followFocus && channelId == pending -> {
            pending = null
            Action.FullScreen(channelId)
        }
        else -> {
            pending = channelId
            Action.Start(channelId, 0)
        }
    }

    fun focus(channelId: Long): Action {
        if (!followFocus) return Action.None
        return when (channelId) {
            pending -> Action.None
            previewing -> if (pending != null) { pending = null; Action.Cancel } else Action.None
            else -> {
                pending = channelId
                Action.Start(channelId, FOCUS_DELAY_MS)
            }
        }
    }

    fun longPress(channelId: Long): Action {
        pending = null
        return Action.FullScreen(channelId)
    }

    fun tile(): Action = previewing?.let { pending = null; Action.FullScreen(it) } ?: Action.None

    /**
     * A scheduled start is due. Returns false if it was superseded meanwhile, so the
     * screen shouldn't start anything.
     */
    fun commit(channelId: Long): Boolean {
        if (pending != channelId) return false
        pending = null
        previewing = channelId
        failed = false
        return true
    }

    /** The preview is already showing [channelId], e.g. handed back from full screen or restored. */
    fun adopt(channelId: Long) {
        previewing = channelId
        pending = null
        failed = false
    }

    fun failed(channelId: Long) {
        if (previewing == channelId) failed = true
    }

    fun playing(channelId: Long) {
        if (previewing == channelId) failed = false
    }

    fun stop() {
        previewing = null
        pending = null
        failed = false
    }

    companion object {
        const val FOCUS_DELAY_MS = 700L
    }
}

/** Where focus is on the home screen, for working out what Back does. */
enum class HomeArea { GROUPS, GUIDE, PREVIEW, HEADER }

enum class HomeBack { FOCUS_GROUPS, CLEAR_SEARCH, EXIT }

/** Places Up/Down can send focus to on the home screen. */
enum class FocusTarget { GRID, GROUPS, PREVIEW, WATCH, HEADER }

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
