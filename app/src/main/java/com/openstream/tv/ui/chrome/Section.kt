package com.openstream.tv.ui.chrome

/** The places on the left rail, top to bottom. Settings sits on its own at the bottom. */
enum class Section { LIVE, MOVIES, RECORDINGS, REMINDERS, SEARCH, MULTIVIEW, PLAYLISTS, SETTINGS }

/** What picking a rail item does, decided in one place so every screen behaves the same. */
sealed interface RailMove {
    /** Already here: just move into the content. */
    data object Stay : RailMove
    /** Back to the live screen, optionally opening search there. */
    data class Live(val openSearch: Boolean) : RailMove
    /** Open another section in place of this one, so Back still goes straight to Live. */
    data class Open(val section: Section, val replaceCurrent: Boolean) : RailMove
}

object SectionNav {
    /**
     * [from] is the section on screen. Live is the root: sections open on top of it and
     * replace each other, so the back stack is never more than Live plus one section.
     * Search is a card over the live screen rather than a screen of its own.
     */
    fun move(from: Section, to: Section): RailMove = when {
        to == from -> RailMove.Stay
        to == Section.LIVE -> RailMove.Live(openSearch = false)
        to == Section.SEARCH -> if (from == Section.LIVE) RailMove.Stay else RailMove.Live(openSearch = true)
        else -> RailMove.Open(to, replaceCurrent = from != Section.LIVE)
    }
}
