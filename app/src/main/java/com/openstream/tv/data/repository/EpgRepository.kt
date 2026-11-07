package com.openstream.tv.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.openstream.tv.data.db.EpgMatchEntity
import com.openstream.tv.data.epg.ChannelMatcher
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
import kotlinx.coroutines.flow.first
import com.openstream.tv.data.settings.SettingsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class EpgRepository(
    private val context: Context,
    private val db: OpenStreamDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    fun observeSources(): Flow<List<EpgSourceEntity>> = db.epg().observeSources()

    suspend fun addSource(url: String, name: String, playlistId: Long?): Long {
        val existing = db.epg().observeSources().first().find { it.url == url.trim() && it.playlistId == playlistId }
        if (existing != null) {
            db.epg().setSourceEnabled(existing.id, true)
            return existing.id
        }
        return db.epg().insertSource(EpgSourceEntity(playlistId = playlistId, name = name, url = url.trim()))
    }

    suspend fun deleteSource(id: Long) {
        db.epg().setSourceEnabled(id, false)
        db.epg().clearMatches(id)
        db.epg().deleteProgramsForSource(id)
        db.epg().deleteChannelsForSource(id)
    }

    suspend fun refreshAll(): List<Pair<EpgSourceEntity, Result<XmltvStats>>> {
        return db.epg().getEnabledSources().map { it to refresh(it) }
    }

    suspend fun staleSources(updatedBefore: Long): List<EpgSourceEntity> =
        db.epg().getEnabledSources().filter { it.lastUpdated < updatedBefore }

    suspend fun refreshForPlaylist(playlistId: Long) {
        db.epg().getSourcesForPlaylist(playlistId).filter { it.enabled }.forEach { refresh(it) }
    }

    /**
     * Validates a cached download before atomically replacing a source's guide.
     * Unmatched guides retain all programmes so matching cannot erase the guide.
     */
    private val refreshLocks = KeyedLock<Long>()

    suspend fun refresh(source: EpgSourceEntity): Result<XmltvStats> =
        refreshLocks.withLock(source.id) { doRefresh(source) }

    private suspend fun doRefresh(source: EpgSourceEntity): Result<XmltvStats> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val playlistChannels = db.epg().matchingChannels(source.playlistId).map {
            ChannelMatcher.Channel(it.id, it.tvgId?.lowercase(java.util.Locale.ROOT), listOfNotNull(it.tvgName, it.name))
        }
        val cutoff = System.currentTimeMillis() - KEEP_PAST
        val prefs = settings.get()
        val offset = TimeUnit.MINUTES.toMillis(prefs.epgOffsetMinutes.toLong())
        val keepDescriptions = prefs.storeDescriptions
        val cache = java.io.File.createTempFile("guide-", ".xmltv", context.cacheDir)
        return@withContext try {
            openSource(context, http, source.url) { input ->
                cache.outputStream().use { input.copyTo(it) }
            }
            val guideNames = ArrayList<ChannelMatcher.Guide>()
            val validation = cache.inputStream().use { input ->
                XmltvParser.parse(input, object : XmltvListener {
                    override fun onChannel(channel: XmltvChannel) {
                        guideNames += ChannelMatcher.Guide(channel.id.lowercase(java.util.Locale.ROOT),
                            channel.displayNames.ifEmpty { listOf(channel.displayName) })
                    }
                }, includeDescriptions = false)
            }
            EpgRefreshGuard.requireReplacement(validation, validation.programmes)
            val stats = db.withTransaction {
                db.epg().clearMatches(source.id)
                db.epg().deleteProgramsForSource(source.id)
                db.epg().deleteChannelsForSource(source.id)

                var stored = 0
                val imported = cache.inputStream().use { input ->
                    val programmes = ArrayList<EpgProgramEntity>(BATCH)
                    val channels = ArrayList<EpgChannelEntity>(BATCH)
                    val listener = object : XmltvListener {
                        override fun onChannel(channel: XmltvChannel) {
                            val id = channel.id.lowercase(java.util.Locale.ROOT)
                            channels += EpgChannelEntity(source.id, id, channel.displayName, channel.iconUrl, channel.displayNames.joinToString("\n"))
                            if (channels.size >= BATCH) flushChannels(channels)
                        }

                        override fun onProgramme(programme: XmltvProgramme) {
                            val id = programme.channelId.lowercase(java.util.Locale.ROOT)
                            if (programme.stop + offset < cutoff) return
                            stored++
                            programmes += EpgProgramEntity(
                                sourceId = source.id,
                                channelXmltvId = id,
                                start = programme.start + offset,
                                stop = programme.stop + offset,
                                title = programme.title,
                                subtitle = programme.subtitle,
                                description = if (keepDescriptions) programme.description else null,
                                category = programme.category,
                                iconUrl = programme.iconUrl,
                                episode = programme.episode,
                            )
                            if (programmes.size >= BATCH) flushProgrammes(programmes)
                        }
                    }
                    XmltvParser.parse(input, listener, includeDescriptions = keepDescriptions).also {
                        flushChannels(channels)
                        flushProgrammes(programmes)
                    }
                }
                EpgRefreshGuard.requireReplacement(imported, stored)
                recomputeMatches(db, source)
                val warning: String? = null
                db.epg().setSourceRefreshResult(source.id, System.currentTimeMillis(), warning)
                imported.copy(error = warning)
            }
            Result.success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.epg().setSourceRefreshResult(source.id, source.lastUpdated, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        } finally {
            cache.delete()
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

    suspend fun currentProgram(channelId: String?, now: Long = System.currentTimeMillis()): EpgProgramEntity? =
        channelId?.let { programsFor(listOf(it), now, now + 1)[it]?.firstOrNull() }

    suspend fun currentPrograms(channelIds: Collection<String>, now: Long = System.currentTimeMillis()): Map<String, EpgProgramEntity> =
        programsFor(channelIds, now, now + 1).mapNotNull { (id, programs) -> programs.firstOrNull()?.let { id to it } }.toMap()

    /** Persisted channel mappings include the source, so identical ids in different guides cannot collide. */
    suspend fun programsFor(channelIds: Collection<String>, from: Long, to: Long): Map<String, List<EpgProgramEntity>> {
        val out = HashMap<String, List<EpgProgramEntity>>()
        channelIds.mapNotNull { it.toLongOrNull() }.distinct().chunked(900).forEach { ids ->
            val matches = db.epg().matches(ids).distinctBy { it.channelId }
            matches.groupBy { it.sourceId }.forEach { (source, mappings) ->
                val programs = db.epg().mappedPrograms(source, mappings.map { it.xmltvId }.distinct(), from, to).groupBy { it.channelXmltvId }
                mappings.forEach { out[it.channelId.toString()] = programs[it.xmltvId].orEmpty() }
            }
        }
        return out
    }

    suspend fun programs(channelId: String, from: Long, to: Long): List<EpgProgramEntity> =
        programsFor(listOf(channelId), from, to)[channelId].orEmpty()

    companion object {
        suspend fun recomputeMatches(db: OpenStreamDatabase, source: EpgSourceEntity) {
            val channels = db.epg().matchingChannels(source.playlistId)
            val guides = db.epg().guideChannels(source.id).map {
                ChannelMatcher.Guide(it.xmltvId, (it.aliases.split('\n') + it.displayName).filter(String::isNotBlank))
            }
            val matches = ChannelMatcher.match(channels.map {
                ChannelMatcher.Channel(it.id, it.tvgId?.lowercase(java.util.Locale.ROOT), listOfNotNull(it.tvgName, it.name))
            }, guides)
            db.withTransaction {
                db.epg().clearMatches(source.id)
                matches.chunked(BATCH).forEach { chunk ->
                    db.epg().insertMatches(chunk.map { EpgMatchEntity(it.channel, source.id, it.guideId, it.pass) })
                }
            }
        }

        private const val BATCH = 1000
        /** How far back to keep programmes, enough to cover typical catch-up windows. */
        private val KEEP_PAST = TimeUnit.DAYS.toMillis(7)
    }
}
