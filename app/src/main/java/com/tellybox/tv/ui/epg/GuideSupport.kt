package com.tellybox.tv.ui.epg

import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.catchup.CatchupUrls
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.EpgProgramEntity
import com.tellybox.tv.ui.recording.confirmLongRecording
import com.tellybox.tv.data.db.RecordingSource
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The parts of a guide screen that don't depend on its layout: loading programmes for the
 * rows on screen, describing the focused cell, and the record / remind actions. Shared by
 * the full guide and the home screen so both behave the same.
 */
class GuideSupport(private val activity: AppCompatActivity, private val grid: GuideGridView) {

    /** Rows of the grid, in order, with display names already applied. */
    var channels: List<ChannelEntity> = emptyList()

    /** Loaded programmes per playlist channel id; an empty list means "looked, nothing there". */
    private val programs = HashMap<String, List<EpgProgramEntity>>()
    private val loading = HashSet<String>()
    private var generation = 0
    private val matched = HashSet<String>()

    init {
        grid.emptyRowText = ::emptyRowText
    }

    private fun emptyRowText(channel: GuideChannel): String = activity.getString(
        if (channel.tvgId in matched) R.string.guide_no_programmes else R.string.guide_no_data,
    )

    fun channelFor(row: Int): ChannelEntity? = channels.getOrNull(row)

    fun programsFor(channel: GuideChannel): List<EpgProgramEntity>? {
        val id = channel.tvgId ?: return emptyList()
        return programs[id]
    }

    fun onVisibleRows(first: Int, last: Int) {
        if (channels.isEmpty()) return
        // Fetch a screen's worth either side so paging feels instant.
        val span = last - first + 1
        val wanted = channels.subList((first - span).coerceIn(0, channels.size), (last + span + 1).coerceIn(0, channels.size))
            .map { it.id.toString() }
            .filter { it !in programs && it !in loading }
            .distinct()
        if (wanted.isEmpty()) return
        loading += wanted
        val gen = generation
        activity.lifecycleScope.launch {
            val found = activity.appContainer.epg.programsFor(wanted, grid.minTime, grid.maxTime)
            if (gen != generation) return@launch
            matched += found.keys
            wanted.forEach { programs[it] = found[it].orEmpty() }
            loading -= wanted.toSet()
            grid.refresh()
        }
    }

    /** A guide import finished: forget what was loaded and fetch the visible rows again. */
    fun invalidate() {
        generation++
        programs.clear()
        loading.clear()
        matched.clear()
        grid.reload()
    }

    /** [time] includes the channel name when there's a programme; [timeOnly] never does. */
    data class Info(val title: String, val time: String, val description: String, val timeOnly: String = time)

    fun describe(channel: GuideChannel, block: GuideBlock): Info {
        val p = block.program
        if (block.strip != null) {
            val text = if (block.strip == GuideBlock.Strip.NO_DATA) emptyRowText(channel) else ""
            return Info(channel.name, text, "", text)
        }
        val fmt = DateFormat.getTimeFormat(activity)
        val day = DateFormat.getMediumDateFormat(activity)
        val time = "${day.format(Date(block.start))}  ${fmt.format(Date(block.start))} – ${fmt.format(Date(block.stop))}"
        return Info(
            title = p?.title ?: channel.name,
            time = if (p != null) "${channel.name} · $time" else time,
            description = listOfNotNull(p?.subtitle, p?.description).joinToString(" — "),
            timeOnly = time,
        )
    }

    /** Whether OK on [block] should replay it from the archive rather than tune in live. */
    fun isReplayable(row: Int, block: GuideBlock, now: Long = System.currentTimeMillis()): Boolean {
        val channel = channels.getOrNull(row) ?: return false
        return block.program != null && block.stop <= now &&
            CatchupUrls.available(channel.catchupDays, block.start, block.stop, now)
    }

    /** Player intent for replaying a past programme, or null (with a message) if there's no archive. */
    fun catchupIntent(row: Int, block: GuideBlock): Intent? {
        val channel = channels.getOrNull(row) ?: return null
        val program = block.program
        if (program == null || !isReplayable(row, block)) {
            Toast.makeText(activity, R.string.guide_no_catchup, Toast.LENGTH_SHORT).show()
            return null
        }
        return PlayerActivity.catchupIntent(activity, channel.id, block.start, block.stop, program.title)
    }

    /** Record / reminder entries for a programme's menu. */
    suspend fun programActions(row: Int, block: GuideBlock): List<Pair<String, () -> Unit>> {
        val channel = channels.getOrNull(row) ?: return emptyList()
        val program = block.program ?: return emptyList()
        val c = activity.appContainer
        val now = System.currentTimeMillis()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val existing = c.recordings.forProgram(channel, program.start, program.stop)
        when {
            existing != null -> actions += activity.getString(
                if (existing.status == RecordingStatus.RECORDING) R.string.guide_stop_recording else R.string.guide_cancel_recording,
            ) to { stopRecording(existing.id, existing.status) }
            program.stop > now -> actions += activity.getString(R.string.guide_record) to { record(channel, program) }
            CatchupUrls.available(channel.catchupDays, program.start, program.stop, now) ->
                actions += activity.getString(R.string.guide_record_archive) to { record(channel, program) }
        }
        // Escape hatch for streams our player struggles with: live now, or from the archive.
        val liveNow = program.start <= now && program.stop > now
        if (liveNow || isReplayable(row, block, now)) {
            actions += activity.getString(R.string.external_player) to {
                activity.lifecycleScope.launch {
                    if (liveNow) com.tellybox.tv.ui.player.ExternalPlayer.open(activity, channel, channel.name)
                    else com.tellybox.tv.ui.player.ExternalPlayer.open(activity, channel, program.title, program.start, program.stop)
                }
                Unit
            }
        }
        if (program.start > now) {
            val reminder = c.reminders.find(channel, program.start)
            actions += if (reminder != null) {
                activity.getString(R.string.guide_cancel_reminder) to {
                    activity.lifecycleScope.launch {
                        c.reminders.remove(reminder.id)
                        toast(activity.getString(R.string.reminder_removed))
                    }
                    Unit
                }
            } else {
                activity.getString(R.string.guide_remind) to { remind(channel, program) }
            }
        }
        return actions
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun record(channel: ChannelEntity, program: EpgProgramEntity) {
        activity.confirmLongRecording(program.stop - program.start) { schedule(channel, program) }
    }

    private fun schedule(channel: ChannelEntity, program: EpgProgramEntity) {
        activity.lifecycleScope.launch {
            val result = activity.appContainer.recordings.scheduleProgram(channel, channel.name, program.title, program.start, program.stop)
            toast(result.fold(
                { rec ->
                    when {
                        rec.source == RecordingSource.CATCHUP -> activity.getString(R.string.recording_archive_started, program.title)
                        rec.startAt <= System.currentTimeMillis() + 5_000 -> activity.getString(R.string.recording_started, program.title)
                        else -> activity.getString(R.string.recording_scheduled, program.title)
                    }
                },
                { activity.getString(R.string.recording_failed_to_schedule, it.message) },
            ))
        }
    }

    private fun stopRecording(id: Long, status: RecordingStatus) {
        activity.lifecycleScope.launch {
            activity.appContainer.recordings.stop(id)
            toast(activity.getString(if (status == RecordingStatus.RECORDING) R.string.recording_stopped else R.string.recording_cancelled))
        }
    }

    private fun remind(channel: ChannelEntity, program: EpgProgramEntity) {
        activity.lifecycleScope.launch {
            val result = activity.appContainer.reminders.add(channel, channel.name, program.title, program.start, program.stop)
            toast(result.fold(
                { activity.getString(R.string.reminder_set, program.title) },
                { activity.getString(R.string.reminder_failed, it.message) },
            ))
        }
    }

    companion object {
        fun guideChannel(c: ChannelEntity, favorite: Boolean = false) =
            GuideChannel(c.id, c.number, c.name, c.id.toString(), c.catchupDays, favorite)
    }
}
