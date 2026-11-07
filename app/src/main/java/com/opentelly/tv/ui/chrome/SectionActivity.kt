package com.opentelly.tv.ui.chrome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.opentelly.tv.databinding.ScreenChromeBinding
import com.opentelly.tv.ui.MainActivity
import com.opentelly.tv.ui.parental.ProtectedActivity

/**
 * Base for every section screen: the shared rail on the left, the section's content to its
 * right, the same rail navigation everywhere, and Back that ends on the live screen.
 */
abstract class SectionActivity : ProtectedActivity() {

    abstract val section: Section

    protected lateinit var chrome: ScreenChromeBinding
        private set
    protected lateinit var rail: SectionRail
        private set
    /** Set by screens using the standard categories + list shape. */
    protected var columns: TwoColumn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chrome = ScreenChromeBinding.inflate(layoutInflater)
        rail = SectionRail(chrome.rail, section, ::onRailPick)
        setContentView(chrome.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!onSectionBack()) goLive()
            }
        })
    }

    /** Puts [content] to the right of the rail. */
    protected fun setSectionContent(content: View) {
        chrome.sectionContent.removeAllViews()
        chrome.sectionContent.addView(content)
    }

    /**
     * One step outwards. Default: from the right column to the categories; from there
     * (or anywhere else) the caller goes back to Live. Return true if handled.
     */
    protected open fun onSectionBack(): Boolean {
        val c = columns ?: return false
        if (c.rightHasFocus()) return c.focusCategories()
        return false
    }

    /** Right from the rail. */
    protected open fun focusContent(): Boolean =
        columns?.focusCategories() ?: chrome.sectionContent.requestFocus()

    protected open fun onRailPick(to: Section) {
        when (val move = SectionNav.move(section, to)) {
            RailMove.Stay -> focusContent()
            is RailMove.Live -> goLive(move.openSearch)
            is RailMove.Open -> {
                startActivity(intentFor(this, move.section))
                overridePendingTransition(0, 0)
                if (move.replaceCurrent) finish()
            }
        }
    }

    /** Back to the live screen, with the rail on the section we came from. */
    protected fun goLive(openSearch: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_RAIL_SECTION, section.name)
                .putExtra(MainActivity.EXTRA_OPEN_SEARCH, openSearch),
        )
        overridePendingTransition(0, 0)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val key = event.keyCode
            if (rail.hasFocus()) {
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        rail.moveVertical(key == KeyEvent.KEYCODE_DPAD_UP); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.repeatCount == 0) focusContent(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                }
            } else if (columns?.handleKey(event, rail) == true) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun intentFor(context: Context, section: Section): Intent = Intent(
            context,
            when (section) {
                Section.MOVIES -> com.opentelly.tv.ui.vod.VodActivity::class.java
                Section.RECORDINGS -> com.opentelly.tv.ui.recordings.RecordingsActivity::class.java
                Section.REMINDERS -> com.opentelly.tv.ui.reminders.RemindersActivity::class.java
                Section.MULTIVIEW -> com.opentelly.tv.ui.multiview.MultiviewActivity::class.java
                Section.PLAYLISTS -> com.opentelly.tv.ui.playlists.PlaylistsActivity::class.java
                Section.SETTINGS -> com.opentelly.tv.ui.settings.SettingsActivity::class.java
                Section.LIVE, Section.SEARCH -> MainActivity::class.java
            },
        )
    }
}
