package com.openstream.tv.reminders

import android.content.Context
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.db.ReminderEntity
import com.openstream.tv.data.recording.ReminderPlan
import com.openstream.tv.data.settings.SettingsRepository
import com.openstream.tv.schedule.Alarms
import kotlinx.coroutines.flow.Flow

class ReminderRepository(
    private val app: Context,
    private val db: OpenStreamDatabase,
    private val settings: SettingsRepository,
) {
    private val dao get() = db.reminders()

    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()

    suspend fun find(channel: ChannelEntity, programStart: Long) = dao.find(channel.playlistId, channel.channelKey, programStart)

    suspend fun add(channel: ChannelEntity, channelName: String, title: String, start: Long, stop: Long): Result<ReminderEntity> {
        val now = System.currentTimeMillis()
        val at = ReminderPlan.remindAt(start, now, settings.get().reminderMinutes)
            ?: return Result.failure(IllegalStateException("That programme has already started"))
        val rec = ReminderEntity(
            playlistId = channel.playlistId, channelKey = channel.channelKey, channelName = channelName,
            title = title, programStart = start, programStop = stop, remindAt = at,
        )
        val saved = rec.copy(id = dao.insert(rec))
        Alarms.set(app, at, alarm(saved.id))
        return Result.success(saved)
    }

    suspend fun remove(id: Long) {
        Alarms.cancel(app, alarm(id))
        dao.delete(id)
    }

    /** Called when the alarm fires: the reminder is used up. */
    suspend fun take(id: Long): ReminderEntity? = dao.get(id)?.also { dao.delete(id) }

    suspend fun restore() {
        val now = System.currentTimeMillis()
        for (r in dao.getAll()) {
            when {
                r.programStart <= now -> dao.delete(r.id)
                else -> Alarms.set(app, maxOf(r.remindAt, now + 2_000), alarm(r.id))
            }
        }
    }

    private fun alarm(id: Long) =
        Alarms.broadcast(app, ReminderReceiver::class.java, "reminder", id, ReminderReceiver.EXTRA_ID)
}
