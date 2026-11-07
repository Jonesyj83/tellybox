package com.opentelly.tv

import android.app.Application
import com.opentelly.tv.data.db.OpenTellyDatabase
import com.opentelly.tv.data.repository.BackgroundRefresh
import com.opentelly.tv.data.repository.ChannelRepository
import com.opentelly.tv.data.repository.EpgRepository
import com.opentelly.tv.data.repository.PlaylistRepository
import com.opentelly.tv.data.repository.StalkerClientCache
import com.opentelly.tv.data.repository.createHttpClient
import com.opentelly.tv.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class OpenTellyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foreground)
        com.opentelly.tv.schedule.Notifications.ensureChannels(this)
        // Alarms are lost on force-stop; put back anything still pending.
        container.appScope.launch {
            container.recordings.restore()
            container.reminders.restore()
        }
        // Follows the guide refresh interval setting, including later changes to it.
        container.appScope.launch {
            container.settings.settings.map { it.guideRefreshHours }.distinctUntilChanged().collect {
                com.opentelly.tv.data.repository.RefreshWorker.schedule(this@OpenTellyApp, it)
            }
        }
    }
}

/** Whether any of our screens is visible, so a reminder may open its popup directly. */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val visible get() = started > 0
    override fun onActivityStarted(activity: android.app.Activity) { started++ }
    override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}

/** Hand-rolled dependency graph; small enough that a DI framework isn't worth it. */
class AppContainer(app: Application) {
    /** For work that should outlive a screen, like a playlist refresh started from the wizard. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val parental = com.opentelly.tv.data.parental.ParentalRepository(app)
    val settings = SettingsRepository(app)
    val database = OpenTellyDatabase.create(app)
    val http = createHttpClient()
    private val stalkerClients = StalkerClientCache(http)

    val playlists = PlaylistRepository(app, database, http, stalkerClients)
    val vod = com.opentelly.tv.data.repository.VodRepository(database, http)
    val channels = ChannelRepository(database, stalkerClients, parental)
    val epg = EpgRepository(app, database, http, settings)
    val backgroundRefresh = BackgroundRefresh(playlists, epg, settings)
    val recordings = com.opentelly.tv.data.recording.RecordingRepository(app, database, settings)
    val reminders = com.opentelly.tv.reminders.ReminderRepository(app, database, settings)
    val foreground = ForegroundTracker()
    val playback = com.opentelly.tv.player.Playback(app, http)
    val sleep = com.opentelly.tv.player.SleepSchedule(playback)
    val lastChannel = com.opentelly.tv.data.settings.LastChannelStore(app)
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as OpenTellyApp).container
