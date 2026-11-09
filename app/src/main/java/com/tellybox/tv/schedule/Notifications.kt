package com.tellybox.tv.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_RECORDING_RESULT = "recording_result"
    const val CHANNEL_REMINDER = "reminder"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, context.getString(com.tellybox.tv.R.string.channel_recording_name), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING_RESULT, context.getString(com.tellybox.tv.R.string.channel_recording_result_name), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, context.getString(com.tellybox.tv.R.string.channel_reminder_name), NotificationManager.IMPORTANCE_HIGH))
    }
}
