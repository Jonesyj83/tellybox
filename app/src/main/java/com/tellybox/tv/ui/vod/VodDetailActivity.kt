package com.tellybox.tv.ui.vod

import com.tellybox.tv.R
import com.tellybox.tv.ui.parental.requestPin
import android.os.Bundle
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.tellybox.tv.data.parser.xtream.XtreamEpisode
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
    private lateinit var episodes: RecyclerView
    private var loaded = false
    private var selectedSeason: Int? = null
    private var focusedEpisode: Long? = null
    private fun view(id: Int): View = findViewById(id)
    private fun text(id: Int, value: String?) {
        findViewById<TextView>(id).apply {
            text = value
            visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod_detail)
        selectedSeason = savedInstanceState?.getInt("season", -1)?.takeIf { it != -1 }
        focusedEpisode = savedInstanceState?.getLong("episode", -1)?.takeIf { it != -1L }
        episodes = findViewById(R.id.episodes)
        episodes.layoutManager = LinearLayoutManager(this)
        view(R.id.retry).setOnClickListener { load() }
        view(R.id.artwork).setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                if (episodes.visibility == View.VISIBLE && episodes.adapter?.itemCount != 0) focusEpisode()
                else if (view(R.id.actions).visibility == View.VISIBLE) view(R.id.watch).requestFocus()
                else if (view(R.id.retry).visibility == View.VISIBLE) view(R.id.retry).requestFocus()
                true
            } else false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedSeason?.let { outState.putInt("season", it) }
        focusedEpisode?.let { outState.putLong("episode", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) load()
        else lifecycleScope.launch {
            val channel = appContainer.channels.get(intent.getLongExtra("channel_id", -1))
            if (channel == null || !appContainer.parental.allowed(channel, appContainer.database)) load()
        }
    }

    private fun focusEpisode() {
        val adapter = episodes.adapter as? EpisodeAdapter ?: return
        val position = adapter.items.indexOfFirst { it.id == focusedEpisode }.coerceAtLeast(0)
        if (adapter.items.isEmpty()) return
        episodes.doOnNextLayout {
            episodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        episodes.scrollToPosition(position)
        episodes.requestLayout()
    }

    override fun onParentalUnlock() { load() }

    private fun load() {
        historyJob?.cancel()
        loadJob?.cancel()
        loaded = false
        text(R.id.status, getString(R.string.ui_loading))
        listOf(R.id.actions, R.id.retry, R.id.seasonScroll, R.id.episodes).forEach { view(it).visibility = View.GONE }
        listOf(R.id.title, R.id.metadata, R.id.genre, R.id.plot, R.id.credits, R.id.artworkFallback).forEach { text(it, null) }
        findViewById<ImageView>(R.id.poster).load(null)
        loadJob = lifecycleScope.launch {
            val channel = appContainer.channels.get(intent.getLongExtra("channel_id", -1))
            if (channel == null) { text(R.id.status, getString(R.string.ui_title_no_longer_available)); return@launch }
            if (appContainer.parental.allScreens && !appContainer.parental.unlocked) return@launch
            if (!appContainer.parental.allowed(channel, appContainer.database)) {
                requestPin(cancel = { finish() }) { load() }
                return@launch
            }
            text(R.id.title, channel.name)
            text(R.id.artworkFallback, channel.name)
            view(R.id.artwork).contentDescription = channel.name
            findViewById<ImageView>(R.id.backdrop).apply {
                if (channel.logoUrl.isNullOrBlank()) visibility = View.GONE
                else load(channel.logoUrl)
            }
            findViewById<ImageView>(R.id.poster).load(channel.logoUrl) {
                listener(onSuccess = { _, _ -> view(R.id.artworkFallback).visibility = View.GONE },
                    onError = { _, _ -> view(R.id.artworkFallback).visibility = View.VISIBLE })
            }
            if (channel.kind != ContentKind.SERIES) playbackButtons(channel, null)
            try {
                val details = appContainer.vod.details(channel)
                text(R.id.status, null)
                text(R.id.metadata, listOfNotNull(details.year, details.duration,
                    details.rating?.let { getString(R.string.ui_rating_1_s, it) }, details.country)
                    .filter { it.isNotBlank() }.joinToString(" · "))
                text(R.id.genre, details.genre)
                text(R.id.plot, details.plot)
                text(R.id.credits, listOfNotNull(
                    details.cast?.let { getString(R.string.ui_cast_1_s, it) },
                    details.director?.let { getString(R.string.ui_director_1_s, it) }).joinToString(" · "))
                loaded = true
                if (channel.kind == ContentKind.SERIES) {
                    if (details.episodes.isEmpty()) text(R.id.status, getString(R.string.ui_no_episodes_available))
                    else {
                        val seasons = findViewById<LinearLayout>(R.id.seasons)
                        seasons.removeAllViews()
                        view(R.id.seasonScroll).visibility = View.VISIBLE
                        episodes.visibility = View.VISIBLE
                        val numbers = details.episodes.map { it.season }.distinct()
                        fun season(number: Int, focus: Boolean) {
                            selectedSeason = number
                            for (index in 0 until seasons.childCount) {
                                seasons.getChildAt(index).isActivated = seasons.getChildAt(index).tag == number
                            }
                            episodes.adapter = EpisodeAdapter(channel, details.episodes.filter { it.season == number })
                            if (focus) focusEpisode()
                        }
                        numbers.forEach { number ->
                            val button = layoutInflater.inflate(R.layout.item_vod_season, seasons, false) as Button
                            button.id = View.generateViewId()
                            button.tag = number
                            button.text = getString(R.string.ui_season_1_d, number)
                            button.nextFocusLeftId = if (seasons.childCount == 0) R.id.artwork else seasons.getChildAt(seasons.childCount - 1).id
                            if (seasons.childCount > 0) seasons.getChildAt(seasons.childCount - 1).nextFocusRightId = button.id
                            button.setOnClickListener { season(number, true) }
                            button.setOnKeyListener { _, key, event ->
                                if (event.action == KeyEvent.ACTION_DOWN &&
                                    (key == KeyEvent.KEYCODE_DPAD_DOWN ||
                                        key == KeyEvent.KEYCODE_DPAD_RIGHT && number == numbers.last())) {
                                    season(number, true)
                                    true
                                } else false
                            }
                            seasons.addView(button)
                        }
                        season(selectedSeason?.takeIf { it in numbers } ?: numbers.first(), true)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                text(R.id.status, getString(R.string.ui_details_unavailable_check_your_connection_and_try_again))
                view(R.id.retry).visibility = View.VISIBLE
                if (channel.kind == ContentKind.SERIES) view(R.id.retry).requestFocus()
            }
        }
    }

    private fun playbackButtons(channel: ChannelEntity, episode: Long?) {
        val resume = findViewById<Button>(R.id.watch)
        val beginning = findViewById<Button>(R.id.beginning)
        view(R.id.actions).visibility = View.VISIBLE
        resume.setOnClickListener { play(channel, episode, false) }
        beginning.setOnClickListener { play(channel, episode, true) }
        resume.nextFocusLeftId = R.id.artwork
        resume.requestFocus()
        historyJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.database.history().observe(channel.playlistId, channel.channelKey).collect { history ->
                    val position = history?.positionMs ?: 0
                    resume.text = if (position > 0) getString(R.string.ui_resume_1_s, resources.getQuantityString(R.plurals.minutes, (position / 60_000).toInt(), (position / 60_000).toInt())) else getString(R.string.recording_play)
                    if (position <= 0 && beginning.hasFocus()) resume.requestFocus()
                    beginning.visibility = if (position > 0) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun play(channel: ChannelEntity, episode: Long?, beginning: Boolean) {
        startActivity(PlayerActivity.intent(this, channel.id, longArrayOf(channel.id), 0)
            .putExtra("episode_id", episode ?: -1L).putExtra("from_beginning", beginning))
    }
    private inner class EpisodeAdapter(val channel: ChannelEntity, val items: List<XtreamEpisode>) : RecyclerView.Adapter<EpisodeHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].id
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EpisodeHolder(layoutInflater.inflate(R.layout.item_row, parent, false))
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val episode = items[position]
            holder.itemView.findViewById<TextView>(R.id.choiceTitle).text =
                listOfNotNull(episode.episodeNum?.toString(), episode.title).joinToString(". ")
            holder.itemView.findViewById<TextView>(R.id.choiceDesc).text = episode.durationSecs?.let {
                resources.getQuantityString(R.plurals.minutes, it / 60, it / 60)
            }
            holder.itemView.nextFocusLeftId = R.id.artwork
            holder.itemView.setOnFocusChangeListener { _, focused -> if (focused) focusedEpisode = episode.id }
            holder.itemView.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN ||
                    key == KeyEvent.KEYCODE_DPAD_UP && position == 0 && event.action == KeyEvent.ACTION_DOWN) {
                    val seasons = findViewById<LinearLayout>(R.id.seasons)
                    val index = (0 until seasons.childCount).firstOrNull {
                        seasons.getChildAt(it).tag == selectedSeason
                    } ?: 0
                    seasons.getChildAt(index)?.requestFocus()
                    true
                } else key == KeyEvent.KEYCODE_DPAD_DOWN && position == items.lastIndex
            }
            holder.itemView.setOnClickListener { openEpisode(channel, episode) }
        }
    }

    private class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun openEpisode(channel: ChannelEntity, episode: XtreamEpisode) {
        lifecycleScope.launch {
            try {
                val playable = appContainer.vod.episode(channel, episode.id)
                val position = appContainer.database.history().get(playable.playlistId, playable.channelKey)?.positionMs ?: 0
                androidx.appcompat.app.AlertDialog.Builder(this@VodDetailActivity)
                    .setTitle(episode.title)
                    .setMessage(listOfNotNull(episode.plot, episode.durationSecs?.let {
                        resources.getQuantityString(R.plurals.minutes, it / 60, it / 60)
                    }).joinToString("\n"))
                    .setPositiveButton(if (position > 0) getString(R.string.ui_resume) else getString(R.string.recording_play)) { _, _ -> play(channel, episode.id, false) }
                    .setNeutralButton(getString(R.string.ui_play_from_beginning)) { _, _ -> play(channel, episode.id, true) }
                    .setNegativeButton(getString(R.string.cancel), null).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Toast.makeText(this@VodDetailActivity, getString(R.string.ui_unable_to_open_episode), Toast.LENGTH_LONG).show()
            }
        }
    }
}
