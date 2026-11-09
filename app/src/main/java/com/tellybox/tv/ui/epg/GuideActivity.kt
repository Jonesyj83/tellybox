package com.tellybox.tv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.EpgProgramEntity
import com.tellybox.tv.databinding.ActivityGuideBinding
import com.tellybox.tv.ui.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

class GuideActivity : com.tellybox.tv.ui.parental.ProtectedActivity(), GuideGridView.Callback {

    private lateinit var binding: ActivityGuideBinding
    private var channels: List<ChannelEntity> = emptyList()
    private lateinit var guide: GuideSupport

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            binding.clock.text = DateFormat.getTimeFormat(this@GuideActivity).format(Date())
            binding.grid.invalidate()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lifecycleScope.launch { appContainer.settings.settings.collect { settings ->
            binding.clock.visibility = if (settings.showClock) View.VISIBLE else View.GONE
            binding.grid.dimPast = settings.dimPast
            binding.grid.panelOpacity = settings.panelOpacity
        } }

        binding.grid.exitLeftAtNow = true
        binding.grid.onExitLeft = {
            startActivity(Intent(this, com.tellybox.tv.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("focus_rail", true))
            finish()
            true
        }
        binding.grid.callback = this
        guide = GuideSupport(this, binding.grid)

        lifecycleScope.launch {
            // Reload when parental settings change, and again whenever the channels themselves
            // change: a playlist refresh while the guide is open used to leave it showing rows
            // that no longer existed.
            appContainer.parental.changes.collect {
                loadChannels()
            }
        }
        lifecycleScope.launch {
            val explicit = intent.getLongArrayExtra(EXTRA_CHANNEL_LIST)
            if (explicit != null && explicit.isNotEmpty()) {
                // Opened from a filtered list: follow those rows, so an edit or refresh shows up.
                appContainer.channels.observeByIds(explicit.toList()).collect { list ->
                    applyChannels(list)
                }
            } else {
                appContainer.channels.observeAll().collect { list ->
                    applyChannels(list)
                }
            }
        }
    }


    private var lastIds: List<Long>? = null

    /** Where focus should land: what the viewer is on now, else what the screen was opened for. */
    private fun keepFocusId(): Long =
        binding.grid.focusedChannel?.id ?: intent.getLongExtra(EXTRA_FOCUS_ID, -1)

    private suspend fun loadChannels() {
        val ids = intent.getLongArrayExtra(EXTRA_CHANNEL_LIST)
        val list = if (ids != null && ids.isNotEmpty()) appContainer.channels.getByIds(ids)
            else appContainer.channels.observeAll().first()
        applyChannels(list)
    }

    private suspend fun applyChannels(list: List<com.tellybox.tv.data.db.ChannelEntity>) {
        val visibleIds = list.map { it.id }
        if (visibleIds == lastIds) return
        val previous = lastIds
        val focus = keepFocusId()
        lastIds = visibleIds
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val tokens = appContainer.settings.get().channelNameTokens
        channels = list.map { it.copy(name = com.tellybox.tv.data.settings.ChannelNames.display(it.name, tokens)) }
        guide.channels = channels
        // Same guide, updated: keep the highlighted row and the time on screen. A different
        // list (a new filter) should open where it was asked to.
        val sameList = previous != null && previous.any { it in visibleIds.toSet() }
        binding.grid.setChannels(channels.map { GuideSupport.guideChannel(it) }, focus, keepPosition = sameList)
        if (!binding.grid.hasFocus()) binding.grid.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun programsFor(channel: GuideChannel): List<EpgProgramEntity>? = guide.programsFor(channel)

    override fun onVisibleRows(first: Int, last: Int) = guide.onVisibleRows(first, last)

    override fun onFocus(channel: GuideChannel, block: GuideBlock) {
        val info = guide.describe(channel, block)
        binding.infoTitle.text = info.title
        binding.infoTime.text = info.time
        binding.infoDescription.text = info.description
    }

    override fun onSelect(row: Int, block: GuideBlock) {
        val now = System.currentTimeMillis()
        when {
            block.strip != null -> playLive(row)
            block.start > now -> Toast.makeText(this, R.string.guide_future, Toast.LENGTH_SHORT).show()
            block.stop <= now -> guide.catchupIntent(row, block)?.let(::startActivity)
            else -> playLive(row)
        }
    }

    override fun onMenu(row: Int, block: GuideBlock) {
        val channel = guide.channelFor(row) ?: return
        lifecycleScope.launch {
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions += getString(R.string.guide_watch_live) to { playLive(row) }
            actions += guide.programActions(row, block)
            actions += getString(R.string.guide_jump_now) to { binding.grid.jumpToNow() }
            AlertDialog.Builder(this@GuideActivity)
                .setTitle(block.program?.title ?: channel.name)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .show()
        }
    }

    private fun playLive(row: Int) {
        val ids = channels.map { it.id }.toLongArray()
        startActivity(PlayerActivity.intent(this, ids[row], ids, row))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> binding.grid.jumpBy(TimeUnit.DAYS.toMillis(1))
            KeyEvent.KEYCODE_MEDIA_REWIND -> binding.grid.jumpBy(-TimeUnit.DAYS.toMillis(1))
            KeyEvent.KEYCODE_GUIDE -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CHANNEL_LIST = "channel_list"
        private const val EXTRA_FOCUS_ID = "focus_id"

        fun intent(context: Context, channelIds: LongArray?, focusChannelId: Long? = null): Intent =
            Intent(context, GuideActivity::class.java)
                .putExtra(EXTRA_CHANNEL_LIST, channelIds)
                .putExtra(EXTRA_FOCUS_ID, focusChannelId ?: -1L)
    }
}
