package com.openstream.tv.data.repository

import android.content.Context
import com.openstream.tv.data.db.EpgChannelEntity
import com.openstream.tv.data.db.EpgProgramEntity
import com.openstream.tv.data.db.EpgSourceEntity
import com.openstream.tv.data.db.OpenStreamDatabase
import com.openstream.tv.data.parser.xmltv.XmltvChannel
import com.openstream.tv.data.parser.xmltv.XmltvListener
import com.openstream.tv.data.parser.xmltv.XmltvParser
import com.openstream.tv.data.parser.xmltv.XmltvProgramme
import com.openstream.tv.data.parser.xmltv.XmltvStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class EpgRepository(
    private val context: Context,
    private val db: OpenStreamDatabase,
    private val http: OkHttpClient,
) {
    fun observeSources(): Flow<List<EpgSourceEntity>> = db.epg().observeSources()

    suspend fun addSource(url: String, name: String, playlistId: Long?): Long =
        db.epg().insertSource(EpgSourceEntity(playlistId = playlistId, name = name, url = url.trim()))

    suspend fun deleteSource(id: Long) = db.epg().deleteSource(id)

    suspend fun refreshAll(): List<Pair<EpgSourceEntity, Result<XmltvStats>>> {
        db.epg().deleteProgramsEndingBefore(System.currentTimeMillis() - KEEP_PAST)
        return db.epg().getEnabledSources().map { it to refresh(it) }
    }

    suspend fun staleSources(updatedBefore: Long): List<EpgSourceEntity> =
        db.epg().getEnabledSources().filter { it.lastUpdated < updatedBefore }

    suspend fun refreshForPlaylist(playlistId: Long) {
        db.epg().getSourcesForPlaylist(playlistId).filter { it.enabled }.forEach { refresh(it) }
    }

    /**
     * Downloads and imports one guide. Only programmes for channels we actually have are
     * stored, which keeps the database small when a provider ships a guide for thousands
     * of channels the user's playlist doesn't contain.
     */
    private val refreshLocks = KeyedLock<Long>()

    suspend fun refresh(source: EpgSourceEntity): Result<XmltvStats> =
        refreshLocks.withLock(source.id) { doRefresh(source) }

    private suspend fun doRefresh(source: EpgSourceEntity): Result<XmltvStats> {
        val wanted = db.epg().knownTvgIds(source.playlistId).toHashSet()
        val cutoff = System.currentTimeMillis() - KEEP_PAST
        return try {
            db.epg().deleteProgramsForSource(source.id)
            db.epg().deleteChannelsForSource(source.id)

            val stats = openSource(context, http, source.url) { input ->
                val programmes = ArrayList<EpgProgramEntity>(BATCH)
                val channels = ArrayList<EpgChannelEntity>(BATCH)
                val listener = object : XmltvListener {
                    override fun onChannel(channel: XmltvChannel) {
                        val id = channel.id.lowercase()
                        if (wanted.isNotEmpty() && id !in wanted) return
                        channels += EpgChannelEntity(source.id, id, channel.displayName, channel.iconUrl)
                        if (channels.size >= BATCH) flushChannels(channels)
                    }

                    override fun onProgramme(programme: XmltvProgramme) {
                        val id = programme.channelId.lowercase()
                        if (programme.stop < cutoff) return
                        if (wanted.isNotEmpty() && id !in wanted) return
                        programmes += EpgProgramEntity(
                            sourceId = source.id,
                            channelXmltvId = id,
                            start = programme.start,
                            stop = programme.stop,
                            title = programme.title,
                            subtitle = programme.subtitle,
                            description = programme.description,
                            category = programme.category,
                            iconUrl = programme.iconUrl,
                            episode = programme.episode,
                        )
                        if (programmes.size >= BATCH) flushProgrammes(programmes)
                    }
                }
                XmltvParser.parse(input, listener).also {
                    flushChannels(channels)
                    flushProgrammes(programmes)
                }
            }
            val error = stats.error?.takeIf { stats.programmes == 0 }
            db.epg().setSourceRefreshResult(source.id, System.currentTimeMillis(), error)
            if (error != null) Result.failure(IllegalStateException(error)) else Result.success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.epg().setSourceRefreshResult(source.id, source.lastUpdated, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    private fun flushProgrammes(list: MutableList<EpgProgramEntity>) {
        if (list.isEmpty()) return
        db.runInTransaction { db.epg().insertProgramsBlocking(list) }
        list.clear()
    }

    private fun flushChannels(list: MutableList<EpgChannelEntity>) {
        if (list.isEmpty()) return
        db.epg().insertChannelsBlocking(list)
        list.clear()
    }

    suspend fun currentProgram(tvgId: String?, now: Long = System.currentTimeMillis()): EpgProgramEntity? =
        tvgId?.let { db.epg().current(it.lowercase(), now) }

    /** Now-playing lookup for a whole list of channels, keyed by lower-cased tvg-id. */
    suspend fun currentPrograms(tvgIds: Collection<String>, now: Long = System.currentTimeMillis()): Map<String, EpgProgramEntity> {
        val ids = tvgIds.map { it.lowercase() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, EpgProgramEntity>()
        ids.chunked(900).forEach { chunk ->
            db.epg().currentFor(chunk, now).forEach { out.putIfAbsent(it.channelXmltvId, it) }
        }
        return out
    }

    suspend fun programs(tvgId: String, from: Long, to: Long): List<EpgProgramEntity> =
        db.epg().programsFor(tvgId.lowercase(), from, to)

    companion object {
        private const val BATCH = 1000
        /** How far back to keep programmes, enough to cover typical catch-up windows. */
        private val KEEP_PAST = TimeUnit.DAYS.toMillis(7)
    }
}
