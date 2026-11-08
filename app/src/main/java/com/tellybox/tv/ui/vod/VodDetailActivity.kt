package com.tellybox.tv.ui.vod

import com.tellybox.tv.R
import com.tellybox.tv.ui.parental.requestPin
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ContentKind
import com.tellybox.tv.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VodDetailActivity : com.tellybox.tv.ui.parental.ProtectedActivity() {
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
        text(getString(R.string.ui_loading))
        loadJob = lifecycleScope.launch {
            val channel = appContainer.channels.get(intent.getLongExtra("channel_id", -1))
            content.removeAllViews()
            if (channel == null) { text(getString(R.string.ui_title_no_longer_available)); return@launch }
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
                listOf(details.year, details.duration, details.rating?.let { getString(R.string.ui_rating_1_s, it) }, details.genre, details.country,
                    details.plot, details.cast?.let { getString(R.string.ui_cast_1_s, it) }, details.director?.let { getString(R.string.ui_director_1_s, it) })
                    .filterNot { it.isNullOrBlank() }.forEach { text(it!!) }
                if (channel.kind == ContentKind.SERIES) {
                    if (details.episodes.isEmpty()) text(getString(R.string.ui_no_episodes_available))
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
                                                .setMessage(listOfNotNull(episode.plot, episode.durationSecs?.let { resources.getQuantityString(R.plurals.minutes, it / 60, it / 60) }).joinToString("\n"))
                                                .setPositiveButton(if (position > 0) getString(R.string.ui_resume) else getString(R.string.recording_play)) { _, _ -> play(channel, episode.id, false) }
                                                .setNeutralButton(getString(R.string.ui_play_from_beginning)) { _, _ -> play(channel, episode.id, true) }
                                                .setNegativeButton(getString(R.string.cancel), null).show()
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { Toast.makeText(this@VodDetailActivity, getString(R.string.ui_unable_to_open_episode), Toast.LENGTH_LONG).show() }
                                    }
                                }
                            })
                        }
                    }
                    details.episodes.map { it.season }.distinct().forEach { number ->
                        seasons.addView(Button(this@VodDetailActivity).apply { text = getString(R.string.ui_season_1_d, number); setOnClickListener { season(number) } })
                    }
                    details.episodes.firstOrNull()?.let { season(it.season) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { text(getString(R.string.ui_details_unavailable_check_your_connection_and_try_again)); button(getString(R.string.retry)) { load() } }
        }
    }

    private fun playbackButtons(channel: ChannelEntity, episode: Long?) {
        val resume = Button(this).apply { text = getString(R.string.recording_play); setOnClickListener { play(channel, episode, false) } }
        val beginning = Button(this).apply {
            text = getString(R.string.ui_play_from_beginning); visibility = android.view.View.GONE
            setOnClickListener { play(channel, episode, true) }
        }
        content.addView(resume)
        content.addView(beginning)
        historyJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.database.history().observe(channel.playlistId, channel.channelKey).collect { history ->
                    val position = history?.positionMs ?: 0
                    resume.text = if (position > 0) getString(R.string.ui_resume_1_s, resources.getQuantityString(R.plurals.minutes, (position / 60_000).toInt(), (position / 60_000).toInt())) else getString(R.string.recording_play)
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
