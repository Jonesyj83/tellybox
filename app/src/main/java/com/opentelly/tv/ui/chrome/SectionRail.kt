package com.opentelly.tv.ui.chrome

import android.view.View
import com.opentelly.tv.databinding.ViewRailBinding

/**
 * Binds the shared rail: marks the section on screen as current, reports picks, and moves
 * focus up and down the items without stopping on the spacer above Settings.
 */
class SectionRail(val binding: ViewRailBinding, current: Section, onPick: (Section) -> Unit) {

    val items: Map<Section, View> = linkedMapOf(
        Section.LIVE to binding.railLive,
        Section.MOVIES to binding.railMovies,
        Section.RECORDINGS to binding.railRecordings,
        Section.REMINDERS to binding.railReminders,
        Section.SEARCH to binding.railSearch,
        Section.MULTIVIEW to binding.railMultiview,
        Section.PLAYLISTS to binding.railPlaylists,
        Section.SETTINGS to binding.railSettings,
    )

    var current: Section = current
        set(value) {
            field = value
            items.forEach { (section, view) -> view.isActivated = section == value }
        }

    init {
        this.current = current
        items.forEach { (section, view) -> view.setOnClickListener { onPick(section) } }
    }

    val root: View get() = binding.root

    fun hasFocus() = binding.root.hasFocus()

    fun contains(view: View?): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === binding.root) return true
            v = v.parent as? View
        }
        return false
    }

    fun focus(section: Section = current): Boolean = items.getValue(section).requestFocus()

    /** Up/Down inside the rail; always handled so focus never leaves it vertically. */
    fun moveVertical(up: Boolean) {
        val list = items.values.toList()
        val index = list.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
        list[(index + if (up) -1 else 1).coerceIn(0, list.size - 1)].requestFocus()
    }
}
