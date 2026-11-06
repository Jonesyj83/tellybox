package com.openstream.tv

import android.app.Application
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.repository.BackgroundRefresh
import com.openstream.tv.data.repository.ChannelRepository
import com.openstream.tv.data.repository.EpgRepository
import com.openstream.tv.data.repository.PlaylistRepository
import com.openstream.tv.data.repository.StalkerClientCache
import com.openstream.tv.data.repository.createHttpClient
import com.openstream.tv.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class OpenStreamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-rolled dependency graph; small enough that a DI framework isn't worth it. */
class AppContainer(app: Application) {
    /** For work that should outlive a screen, like a playlist refresh started from the wizard. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val database = OpenStreamDatabase.create(app)
    val http = createHttpClient()
    private val stalkerClients = StalkerClientCache(http)

    val playlists = PlaylistRepository(app, database, http, stalkerClients)
    val channels = ChannelRepository(database, stalkerClients)
    val epg = EpgRepository(app, database, http, settings)
    val backgroundRefresh = BackgroundRefresh(playlists, epg, settings)
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as OpenStreamApp).container
