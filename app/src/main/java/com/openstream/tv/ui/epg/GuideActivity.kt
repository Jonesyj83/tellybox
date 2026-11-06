package com.openstream.tv.ui.epg

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
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.EpgProgramEntity
import com.openstream.tv.data.db.RecordingSource
import com.openstream.tv.data.db.RecordingStatus
import com.openstream.tv.data.catchup.CatchupUrls
import com.openstream.tv.databinding.ActivityGuideBinding
import com.openstream.tv.ui.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

class GuideActivity : com.openstream.tv.ui.parental.ProtectedActivity(), GuideGridView.Callback {

    private lateinit var binding: ActivityGuideBinding
    private var channels: List<ChannelEntity> = emptyList()

    /** Loaded programmes per lower-cased tvg-id; an empty list means "looked, nothing there". */
    private val programs = HashMap<String, List<EpgProgramEntity>>()
    private val loading = HashSet<String>()

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
        binding.grid.callback = this

        lifecycleScope.launch {
            var lastIds: List<Long>? = null
            appContainer.parental.changes.collect {
                val ids = intent.getLongArrayExtra(EXTRA_CHANNEL_LIST)
                channels = if (ids != null && ids.isNotEmpty()) {
                    appContainer.channels.getByIds(ids)
                } else {
                    appContainer.channels.observeAll().first()
                }
                val visibleIds = channels.map { it.id }
                if (visibleIds == lastIds) return@collect
                lastIds = visibleIds
                binding.empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
                val tokens = appContainer.settings.get().channelNameTokens
                channels = channels.map { it.copy(name = com.openstream.tv.data.settings.ChannelNames.display(it.name, tokens)) }
                binding.grid.setChannels(
                    channels.map { GuideChannel(it.id, it.number, it.name, it.tvgId?.lowercase(), it.catchupDays) },
                    intent.getLongExtra(EXTRA_FOCUS_ID, -1),
                )
                if (!binding.grid.hasFocus()) binding.grid.requestFocus()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun programsFor(channel: GuideChannel): List<EpgProgramEntity>? =
        channel.tvgId?.let { programs[it] }

    override fun onVisibleRows(first: Int, last: Int) {
        // Fetch a screen's worth either side so paging feels instant.
        val span = last - first + 1
        val wanted = channels.subList((first - span).coerceAtLeast(0), (last + span + 1).coerceAtMost(channels.size))
            .mapNotNull { it.tvgId?.lowercase() }
            .filter { it !in programs && it !in loading }
            .distinct()
        if (wanted.isEmpty()) return
        loading += wanted
        lifecycleScope.launch {
            val grid = binding.grid
            val found = appContainer.epg.programsFor(wanted, grid.minTime, grid.maxTime)
            wanted.forEach { programs[it] = found[it].orEmpty() }
            loading -= wanted.toSet()
            grid.refresh()
        }
    }

    override fun onFocus(channel: GuideChannel, block: GuideBlock) {
        val fmt = DateFormat.getTimeFormat(this)
        val day = DateFormat.getMediumDateFormat(this)
        val p = block.program
        binding.infoTitle.text = p?.title ?: channel.name
        val time = "${day.format(Date(block.start))}  ${fmt.format(Date(block.start))} – ${fmt.format(Date(block.stop))}"
        binding.infoTime.text = if (p != null) "${channel.name} · $time" else time
        binding.infoDescription.text = listOfNotNull(p?.subtitle, p?.description).joinToString(" — ")
    }

    override fun onSelect(row: Int, block: GuideBlock) {
        val now = System.currentTimeMillis()
        when {
            block.start > now -> Toast.makeText(this, R.string.guide_future, Toast.LENGTH_SHORT).show()
            block.stop <= now -> playPast(row, block)
            else -> playLive(row)
        }
    }

    override fun onMenu(row: Int, block: GuideBlock) {
        val channel = channels.getOrNull(row) ?: return
        lifecycleScope.launch {
            val c = appContainer
            val now = System.currentTimeMillis()
            val program = block.program
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions += getString(R.string.guide_watch_live) to { playLive(row) }
            if (program != null) {
                val existing = c.recordings.forProgram(channel, program.start, program.stop)
                when {
                    existing != null -> actions += getString(
                        if (existing.status == RecordingStatus.RECORDING) R.string.guide_stop_recording else R.string.guide_cancel_recording,
                    ) to { stopRecording(existing.id, existing.status) }
                    program.stop > now -> actions += getString(R.string.guide_record) to { record(channel, program) }
                    CatchupUrls.available(channel.catchupDays, program.start, program.stop, now) ->
                        actions += getString(R.string.guide_record_archive) to { record(channel, program) }
                }
                if (program.start > now) {
                    val reminder = c.reminders.find(channel, program.start)
                    actions += if (reminder != null) getString(R.string.guide_cancel_reminder) to {
                        lifecycleScope.launch {
                            c.reminders.remove(reminder.id)
                            toast(getString(R.string.reminder_removed))
                        }
                        Unit
                    } else getString(R.string.guide_remind) to { remind(channel, program) }
                }
            }
            actions += getString(R.string.guide_jump_now) to { binding.grid.jumpToNow() }
            AlertDialog.Builder(this@GuideActivity)
                .setTitle(program?.title ?: channel.name)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .show()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun record(channel: ChannelEntity, program: EpgProgramEntity) {
        lifecycleScope.launch {
            val result = appContainer.recordings.scheduleProgram(channel, channel.name, program.title, program.start, program.stop)
            toast(result.fold(
                { rec ->
                    when {
                        rec.source == RecordingSource.CATCHUP -> getString(R.string.recording_archive_started, program.title)
                        rec.startAt <= System.currentTimeMillis() + 5_000 -> getString(R.string.recording_started, program.title)
                        else -> getString(R.string.recording_scheduled, program.title)
                    }
                },
                { getString(R.string.recording_failed_to_schedule, it.message) },
            ))
        }
    }

    private fun stopRecording(id: Long, status: RecordingStatus) {
        lifecycleScope.launch {
            appContainer.recordings.stop(id)
            toast(getString(if (status == RecordingStatus.RECORDING) R.string.recording_stopped else R.string.recording_cancelled))
        }
    }

    private fun remind(channel: ChannelEntity, program: EpgProgramEntity) {
        lifecycleScope.launch {
            val result = appContainer.reminders.add(channel, channel.name, program.title, program.start, program.stop)
            toast(result.fold({ getString(R.string.reminder_set, program.title) }, { getString(R.string.reminder_failed, it.message) }))
        }
    }

    private fun playLive(row: Int) {
        val ids = channels.map { it.id }.toLongArray()
        startActivity(PlayerActivity.intent(this, ids[row], ids, row))
    }

    private fun playPast(row: Int, block: GuideBlock) {
        val channel = channels[row]
        if (block.program == null || !com.openstream.tv.data.catchup.CatchupUrls.available(
                channel.catchupDays, block.start, block.stop, System.currentTimeMillis())) {
            Toast.makeText(this, R.string.guide_no_catchup, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(PlayerActivity.catchupIntent(this, channel.id, block.start, block.stop, block.program.title))
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
