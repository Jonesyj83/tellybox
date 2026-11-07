package com.opentelly.tv.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Wake-up alarms for recordings and reminders. Exact alarms are used whenever the system
 * allows them; otherwise Android may deliver the alarm a few minutes late, which is why
 * recordings are padded.
 */
object Alarms {
    fun exactAllowed(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun set(context: Context, at: Long, pending: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (exactAllowed(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, pending: PendingIntent) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    /** One distinct PendingIntent per item, told apart by the intent's data URI. */
    fun broadcast(context: Context, receiver: Class<*>, kind: String, id: Long, extraName: String): PendingIntent {
        val intent = Intent(context, receiver)
            .setData(Uri.parse("opentelly://$kind/$id"))
            .putExtra(extraName, id)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
