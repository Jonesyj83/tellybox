package com.opentelly.tv.ui.chrome

import android.os.Handler
import android.os.Looper
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.opentelly.tv.databinding.SectionTwoColumnBinding

/**
 * The standard section body: categories on the left (live TV rows), a header and a list on
 * the right. Handles focus between the columns the same way the live screen does:
 * resting on a category shows it, OK moves into the list, Left from the list returns to
 * the chosen category, Left again reaches the rail.
 */
class TwoColumn(
    inflater: LayoutInflater,
    parent: ViewGroup?,
    private val onSelect: (Category) -> Unit,
) {
    val b: SectionTwoColumnBinding = SectionTwoColumnBinding.inflate(inflater, parent, false)
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastListPosition = 0

    val categories = CategoryAdapter(
        onFocused = { c ->
            pending?.let(handler::removeCallbacks)
            pending = Runnable { select(c) }.also { handler.postDelayed(it, 250) }
        },
        onClick = { c ->
            pending?.let(handler::removeCallbacks)
            select(c)
            lastListPosition = 0
            b.list.post { focusList() }
        },
    )

    init {
        b.categories.layoutManager = LinearLayoutManager(b.root.context)
        b.categories.adapter = categories
        b.categories.itemAnimator = null
        b.list.itemAnimator = null
    }

    private fun select(c: Category) {
        if (categories.activeKey == c.key) return
        categories.activeKey = c.key
        onSelect(c)
    }

    fun rightHasFocus() = b.rightColumn.hasFocus()

    fun focusCategories(): Boolean {
        val index = categories.indexOf(categories.activeKey).coerceAtLeast(0)
        if (categories.itemCount == 0) return false
        rememberListFocus()
        b.categories.findViewHolderForAdapterPosition(index)?.itemView?.let { if (it.requestFocus()) return true }
        b.categories.scrollToPosition(index)
        b.categories.post { b.categories.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        return true
    }

    private fun rememberListFocus() {
        val focused = b.list.focusedChild ?: return
        lastListPosition = b.list.getChildAdapterPosition(focused).coerceAtLeast(0)
    }

    /** Into the list, on the row last used; falls back to the header actions. */
    fun focusList(): Boolean {
        val count = b.list.adapter?.itemCount ?: 0
        if (count == 0) return focusFirstAction()
        val target = lastListPosition.coerceIn(0, count - 1)
        b.list.findViewHolderForAdapterPosition(target)?.itemView?.let { if (it.requestFocus()) return true }
        b.list.scrollToPosition(target)
        b.list.post {
            (b.list.findViewHolderForAdapterPosition(target)?.itemView ?: b.list.getChildAt(0))?.requestFocus()
        }
        return true
    }

    fun focusFirstAction(): Boolean =
        (0 until b.actions.childCount).map { b.actions.getChildAt(it) }
            .firstOrNull { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }?.requestFocus() == true

    /** Moves focus between the columns and the rail. True when the key was used. */
    fun handleKey(event: KeyEvent, rail: SectionRail): Boolean {
        val key = event.keyCode
        val focused = b.root.findFocus() ?: return false
        if (b.categories.hasFocus()) {
            return when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { rail.focus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.repeatCount == 0) focusList(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val index = b.categories.findContainingViewHolder(focused)?.bindingAdapterPosition ?: 0
                    val next = (index + if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                        .coerceIn(0, (categories.itemCount - 1).coerceAtLeast(0))
                    b.categories.scrollToPosition(next)
                    b.categories.post { b.categories.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
                    true
                }
                else -> false
            }
        }
        if (b.list.hasFocus()) {
            // Decided on adapter positions: rows scrolled off screen don't exist as views, so a
            // focus search would wrongly think the first visible row is the top of the list.
            val position = b.list.findContainingViewHolder(focused)?.bindingAdapterPosition ?: return false
            val span = (b.list.layoutManager as? GridLayoutManager)?.spanCount ?: 1
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (position % span == 0) { focusCategories(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (position < span) { focusFirstAction(); return true }
            }
            return false
        }
        if (b.rightColumn.hasFocus() && key == KeyEvent.KEYCODE_DPAD_LEFT) {
            // Header actions: Left past the first one goes back to the categories.
            val next = FocusFinder.getInstance().findNextFocus(b.root as ViewGroup, focused, View.FOCUS_LEFT)
            if (next == null || !isInside(b.rightColumn, next)) {
                focusCategories()
                return true
            }
        }
        if (b.actions.hasFocus() && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (focusList()) return true
        }
        return false
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        fun isInside(parent: View, view: View): Boolean {
            var v: View? = view
            while (v != null) {
                if (v === parent) return true
                v = v.parent as? View
            }
            return false
        }
    }
}
