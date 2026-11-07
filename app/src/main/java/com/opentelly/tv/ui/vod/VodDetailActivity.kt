package com.opentelly.tv.ui.vod

import com.opentelly.tv.ui.parental.requestPin
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.opentelly.tv.appContainer
import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ContentKind
import com.opentelly.tv.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VodDetailActivity : com.opentelly.tv.ui.parental.ProtectedActivity() {
    private var historyJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private lateinit var content: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onParentalUnlock() { load() }

    private fun load() {
        historyJob?.cancel()
        loadJob?.cancel()
        content.removeAllViews()
        text("Loading…")
        loadJob = lifecycleScope.launch {
            val channel = appContainer.channels.get(intent.getLongExtra("channel_id", -1))
            content.removeAllViews()
            if (channel == null) { text("Title no longer available"); return@launch }
            if (appContainer.parental.allScreens && !appContainer.parental.unlocked) return@launch
            if (!appContainer.parental.allowed(channel, appContainer.database)) {
                requestPin(cancel = { finish() }) { load() }
                return@launch
            }
            text(channel.name, 16f)
            content.addView(ImageView(this@VodDetailActivity).apply { load(channel.logoUrl); scaleType = ImageView.ScaleType.FIT_START }, LinearLayout.LayoutParams(200, 260))
            if (channel.kind != ContentKind.SERIES) playbackButtons(channel, null)
            try {
                val details = appContainer.vod.details(channel)
                listOf(details.year, details.duration, details.plot, details.cast?.let { "Cast: $it" }, details.director?.let { "Director: $it" })
                    .filterNot { it.isNullOrBlank() }.forEach { text(it!!) }
                if (channel.kind == ContentKind.SERIES) {
                    if (details.episodes.isEmpty()) text("No episodes available")
                    val episodes = LinearLayout(this@VodDetailActivity).apply { orientation = LinearLayout.VERTICAL }
                    val seasons = LinearLayout(this@VodDetailActivity)
                    content.addView(HorizontalScrollView(this@VodDetailActivity).apply { addView(seasons) })
                    content.addView(episodes)
                    fun season(number: Int) {
                        episodes.removeAllViews()
                        details.episodes.filter { it.season == number }.forEach { episode ->
                            episodes.addView(Button(this@VodDetailActivity).apply {
                                text = "${episode.episodeNum ?: ""}. ${episode.title}"
                                setOnClickListener {
                                    lifecycleScope.launch {
                                        try {
                                            val playable = appContainer.vod.episode(channel, episode.id)
                                            val position = appContainer.database.history().get(playable.playlistId, playable.channelKey)?.positionMs ?: 0
                                            androidx.appcompat.app.AlertDialog.Builder(this@VodDetailActivity)
                                                .setTitle(episode.title)
                                                .setMessage(listOfNotNull(episode.plot, episode.durationSecs?.let { "${it / 60} min" }).joinToString("\n"))
                                                .setPositiveButton(if (position > 0) "Resume" else "Play") { _, _ -> play(channel, episode.id, false) }
                                                .setNeutralButton("Play from beginning") { _, _ -> play(channel, episode.id, true) }
                                                .setNegativeButton("Cancel", null).show()
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { Toast.makeText(this@VodDetailActivity, "Unable to open episode", Toast.LENGTH_LONG).show() }
                                    }
                                }
                            })
                        }
                    }
                    details.episodes.map { it.season }.distinct().forEach { number ->
                        seasons.addView(Button(this@VodDetailActivity).apply { text = "Season $number"; setOnClickListener { season(number) } })
                    }
                    details.episodes.firstOrNull()?.let { season(it.season) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { text("Details unavailable. Check your connection and try again."); button("Retry") { load() } }
        }
    }

    private fun playbackButtons(channel: ChannelEntity, episode: Long?) {
        val resume = Button(this).apply { text = "Play"; setOnClickListener { play(channel, episode, false) } }
        val beginning = Button(this).apply {
            text = "Play from beginning"; visibility = android.view.View.GONE
            setOnClickListener { play(channel, episode, true) }
        }
        content.addView(resume)
        content.addView(beginning)
        historyJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.database.history().observe(channel.playlistId, channel.channelKey).collect { history ->
                    val position = history?.positionMs ?: 0
                    resume.text = if (position > 0) "Resume (${position / 60_000} min)" else "Play"
                    beginning.visibility = if (position > 0) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun play(channel: ChannelEntity, episode: Long?, beginning: Boolean) {
        startActivity(PlayerActivity.intent(this, channel.id, longArrayOf(channel.id), 0)
            .putExtra("episode_id", episode ?: -1L).putExtra("from_beginning", beginning))
    }
    private fun text(value: String, size: Float = 12f) { content.addView(TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 8) }) }
    private fun button(label: String, click: () -> Unit) { content.addView(Button(this).apply { text = label; setOnClickListener { click() } }) }
}
