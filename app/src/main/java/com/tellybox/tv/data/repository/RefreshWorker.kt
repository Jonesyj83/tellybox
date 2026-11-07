package com.tellybox.tv.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tellybox.tv.appContainer
import java.util.concurrent.TimeUnit

/** Periodic playlist and guide refresh, so data stays fresh even if the app is never reopened. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appContainer.backgroundRefresh.refreshStale(RefreshSchedule.SLACK_MS)
        return Result.success()
    }

    companion object {
        private const val NAME = "periodic_refresh"

        /** Safe to call repeatedly; only changes the schedule when the interval did. */
        fun schedule(context: Context, hours: Int) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(RefreshSchedule.periodHours(hours), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

object RefreshSchedule {
    /**
     * Periodic work drifts by its flex window, so a playlist refreshed by the previous run can
     * look a few minutes too young. Without some slack every other run would skip it.
     */
    const val SLACK_MS = 30 * 60_000L

    fun periodHours(settingHours: Int): Long = settingHours.coerceAtLeast(1).toLong()

    /** Anything last updated before this is due for a refresh. */
    fun staleCutoff(now: Long, settingHours: Int, slackMs: Long = 0): Long =
        now - TimeUnit.HOURS.toMillis(periodHours(settingHours)) + slackMs
}
