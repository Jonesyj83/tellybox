package com.tellybox.tv.data.recording

import android.content.Context
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.TellyboxDatabase
import com.tellybox.tv.data.db.RecordingEntity
import com.tellybox.tv.data.db.RecordingSource
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.data.settings.SettingsRepository
import com.tellybox.tv.recording.RecordingAlarmReceiver
import com.tellybox.tv.recording.RecordingService
import com.tellybox.tv.schedule.Alarms
import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordingRepository(
    private val app: Context,
    private val db: TellyboxDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = db.recordings()

    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)

    /** Where recordings go: app-specific external storage, which needs no permission. */
    fun directory(): File =
        (app.getExternalFilesDir("Recordings") ?: File(app.filesDir, "Recordings")).apply { mkdirs() }

    /**
     * Records a guide programme: a live capture with padding if it hasn't ended, or an
     * archive download if it has and the channel has catch-up.
     */
    suspend fun scheduleProgram(channel: ChannelEntity, channelName: String, title: String, start: Long, stop: Long): Result<RecordingEntity> {
        val s = settings.get()
        val now = System.currentTimeMillis()
        val rec = when (val plan = RecordingPlan.forProgram(start, stop, now, s.recordPadBefore, s.recordPadAfter, channel.catchupDays)) {
            is RecordingPlan.Plan.Refused -> return Result.failure(IllegalStateException(plan.reason))
            is RecordingPlan.Plan.Live -> RecordingEntity(
                playlistId = channel.playlistId, channelKey = channel.channelKey, channelName = channelName,
                title = title, programStart = start, programStop = stop, startAt = plan.startAt, stopAt = plan.stopAt,
            )
            is RecordingPlan.Plan.Catchup -> RecordingEntity(
                playlistId = channel.playlistId, channelKey = channel.channelKey, channelName = channelName,
                title = title, programStart = start, programStop = stop, startAt = plan.start, stopAt = plan.stop,
                source = RecordingSource.CATCHUP,
            )
        }
        return insertAndArm(rec)
    }

    /** Starts recording [channel] right away for [durationMs]. */
    suspend fun recordNow(channel: ChannelEntity, channelName: String, title: String, durationMs: Long): Result<RecordingEntity> {
        val now = System.currentTimeMillis()
        val plan = RecordingPlan.instant(now, durationMs)
        return insertAndArm(
            RecordingEntity(
                playlistId = channel.playlistId, channelKey = channel.channelKey, channelName = channelName,
                title = title, programStart = plan.startAt, programStop = plan.stopAt,
                startAt = plan.startAt, stopAt = plan.stopAt,
            ),
        )
    }

    private suspend fun insertAndArm(rec: RecordingEntity): Result<RecordingEntity> {
        if (dao.activeOverlapping(rec.playlistId, rec.channelKey, rec.startAt, rec.stopAt).isNotEmpty()) {
            return Result.failure(IllegalStateException("This channel is already being recorded at that time"))
        }
        val saved = rec.copy(id = dao.insert(rec))
        arm(saved, fromForeground = true)
        return Result.success(saved)
    }

    /** Active recording of this channel right now, if any. */
    suspend fun activeFor(channel: ChannelEntity, at: Long = System.currentTimeMillis()): RecordingEntity? =
        dao.activeOverlapping(channel.playlistId, channel.channelKey, at, at + 1)
            .firstOrNull { it.status == RecordingStatus.RECORDING || it.startAt <= at }

    /** Scheduled or running recording covering this programme, if any. */
    suspend fun forProgram(channel: ChannelEntity, start: Long, stop: Long): RecordingEntity? =
        dao.activeOverlapping(channel.playlistId, channel.channelKey, start, stop)
            .firstOrNull { it.programStart == start || (it.startAt <= start && it.stopAt >= stop) }

    /**
     * Ends a running recording (keeping what was captured) or drops a scheduled one.
     */
    suspend fun stop(id: Long) {
        val rec = dao.get(id) ?: return
        when (rec.status) {
            RecordingStatus.RECORDING -> RecordingService.stop(app, id)
            RecordingStatus.SCHEDULED -> {
                Alarms.cancel(app, alarm(id))
                // A catch-up download may already be picked up by the service.
                if (rec.source == RecordingSource.CATCHUP) RecordingService.stop(app, id)
                dao.update(rec.copy(status = RecordingStatus.CANCELLED))
            }
            else -> Unit
        }
    }

    /** Removes the entry and its file. */
    suspend fun delete(id: Long) {
        val rec = dao.get(id) ?: return
        if (rec.status == RecordingStatus.RECORDING || rec.status == RecordingStatus.SCHEDULED) {
            Alarms.cancel(app, alarm(id))
            if (rec.status == RecordingStatus.RECORDING) RecordingService.stop(app, id)
        }
        dao.delete(id)
        rec.filePath?.let { File(it).delete() }
    }

    /**
     * Puts alarms back after a reboot, an app update or the app being force-stopped, and
     * closes out recordings whose window passed while nothing was running.
     */
    suspend fun restore() {
        val now = System.currentTimeMillis()
        for (rec in dao.withStatus(listOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING))) {
            when {
                rec.source == RecordingSource.CATCHUP -> arm(rec, fromForeground = false)
                rec.stopAt <= now -> {
                    val size = rec.filePath?.let { File(it).length() } ?: 0L
                    dao.update(
                        if (size > 0) rec.copy(status = RecordingStatus.COMPLETED, bytes = size,
                            error = "Interrupted: the device or app stopped during the recording, so part of it is missing")
                        else rec.copy(status = RecordingStatus.FAILED,
                            error = "Missed: the device was off or the app was stopped when the recording was due"),
                    )
                }
                else -> arm(rec, fromForeground = false)
            }
        }
    }

    /**
     * From a screen the service can start directly. From the background (boot, app start)
     * Android only lets an alarm start it, so an immediate job goes through an alarm too.
     */
    private fun arm(rec: RecordingEntity, fromForeground: Boolean) {
        val now = System.currentTimeMillis()
        val due = rec.source == RecordingSource.CATCHUP || rec.startAt <= now + 5_000
        if (due && fromForeground) {
            RecordingService.start(app, rec.id)
        } else {
            Alarms.set(app, if (due) now + 2_000 else rec.startAt, alarm(rec.id))
        }
    }

    internal fun alarm(id: Long) =
        Alarms.broadcast(app, RecordingAlarmReceiver::class.java, "recording", id, RecordingAlarmReceiver.EXTRA_ID)
}
