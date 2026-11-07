package com.tellybox.tv.data.parser.xmltv

import com.tellybox.tv.data.parser.decompressIfGzip
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

data class XmltvChannel(
    val id: String,
    val displayName: String,
    val iconUrl: String?,
    val displayNames: List<String> = listOf(displayName),
)

data class XmltvProgramme(
    val channelId: String,
    /** Epoch millis, UTC. */
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val iconUrl: String?,
    val episode: String?,
)

interface XmltvListener {
    fun onChannel(channel: XmltvChannel) {}
    fun onProgramme(programme: XmltvProgramme) {}
}

data class XmltvStats(
    val channels: Int,
    val programmes: Int,
    val skipped: Int,
    /** Set when the document was truncated or malformed part way through. */
    val error: String? = null,
)

/**
 * Streaming XMLTV reader. Guide files from providers are routinely hundreds of MB
 * uncompressed, so nothing is buffered beyond the element currently being read.
 * A malformed document doesn't throw: everything read up to the fault is delivered
 * and the problem is reported in [XmltvStats.error].
 */
object XmltvParser {

    fun parse(input: InputStream, listener: XmltvListener, includeDescriptions: Boolean = true): XmltvStats =
        run(InputSource(input.decompressIfGzip()), listener, includeDescriptions)

    fun parse(text: String, listener: XmltvListener, includeDescriptions: Boolean = true): XmltvStats =
        run(InputSource(StringReader(text)), listener, includeDescriptions)

    /** Convenience for tests and small guides. */
    fun parseAll(text: String): Pair<List<XmltvChannel>, List<XmltvProgramme>> {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<XmltvProgramme>()
        parse(text, object : XmltvListener {
            override fun onChannel(channel: XmltvChannel) { channels += channel }
            override fun onProgramme(programme: XmltvProgramme) { programmes += programme }
        })
        return channels to programmes
    }

    private fun run(source: InputSource, listener: XmltvListener, includeDescriptions: Boolean): XmltvStats {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        ).forEach { feature -> runCatching { factory.setFeature(feature, false) } }

        val handler = Handler(listener, includeDescriptions)
        val error = try {
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // Most guides reference xmltv.dtd; never go fetch it.
            reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(source)
            null
        } catch (e: SAXException) {
            e.message ?: "Malformed XMLTV"
        }
        return XmltvStats(handler.channelCount, handler.programmeCount, handler.skipped, error)
    }

    private class Handler(
        private val listener: XmltvListener,
        private val includeDescriptions: Boolean,
    ) : DefaultHandler() {
        var channelCount = 0
        var programmeCount = 0
        var skipped = 0

        private val text = StringBuilder()
        private var collecting = false

        // <channel>
        private var inChannel = false
        private var chId: String? = null
        private var chName: String? = null
        private val chNames = ArrayList<String>()
        private var chIcon: String? = null

        // <programme>
        private var inProgramme = false
        private var pChannel: String? = null
        private var pStart: Long? = null
        private var pStop: Long? = null
        private var pTitle: String? = null
        private var pSubtitle: String? = null
        private var pDesc: String? = null
        private var pCategory: String? = null
        private var pIcon: String? = null
        private var pEpisodeOnscreen: String? = null
        private var pEpisodeNs: String? = null
        private var episodeSystem: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (qName) {
                "channel" -> if (!inProgramme) {
                    inChannel = true
                    chId = attrs.getValue("id")?.trim()
                    chName = null
                    chNames.clear()
                    chIcon = null
                }
                "programme" -> {
                    inProgramme = true
                    pChannel = attrs.getValue("channel")?.trim()
                    pStart = attrs.getValue("start")?.let(XmltvTime::parse)
                    pStop = attrs.getValue("stop")?.let(XmltvTime::parse)
                    pTitle = null; pSubtitle = null; pDesc = null; pCategory = null
                    pIcon = null; pEpisodeOnscreen = null; pEpisodeNs = null
                }
                "display-name", "title", "sub-title", "category" -> startText()
                "desc" -> if (includeDescriptions) startText()
                "episode-num" -> {
                    episodeSystem = attrs.getValue("system")
                    startText()
                }
                "icon" -> {
                    val src = attrs.getValue("src")?.trim()?.ifEmpty { null }
                    if (inProgramme) { if (pIcon == null) pIcon = src }
                    else if (inChannel) { if (chIcon == null) chIcon = src }
                }
            }
        }

        private fun startText() {
            text.setLength(0)
            collecting = true
        }

        private fun takeText(): String? {
            collecting = false
            return text.toString().trim().ifEmpty { null }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collecting) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                // Keep the first of any repeated element (usually the primary language).
                "display-name" -> takeText()?.let { if (inChannel) { chNames += it; if (chName == null) chName = it } }
                "title" -> takeText()?.let { if (pTitle == null) pTitle = it }
                "sub-title" -> takeText()?.let { if (pSubtitle == null) pSubtitle = it }
                "desc" -> if (includeDescriptions) takeText()?.let { if (pDesc == null) pDesc = it }
                "category" -> takeText()?.let { if (pCategory == null) pCategory = it }
                "episode-num" -> takeText()?.let {
                    when (episodeSystem) {
                        "onscreen" -> if (pEpisodeOnscreen == null) pEpisodeOnscreen = it
                        "xmltv_ns" -> if (pEpisodeNs == null) pEpisodeNs = XmltvTime.formatXmltvNs(it)
                    }
                }
                "channel" -> if (inChannel) {
                    inChannel = false
                    val id = chId
                    if (id.isNullOrEmpty()) {
                        skipped++
                    } else {
                        channelCount++
                        listener.onChannel(XmltvChannel(id, chName ?: id, chIcon, chNames.toList()))
                    }
                }
                "programme" -> {
                    inProgramme = false
                    val channel = pChannel
                    val start = pStart
                    // A missing stop is legal XMLTV; treat as unknown and drop, since
                    // the guide grid can't place it. Bad ranges are dropped too.
                    val stop = pStop
                    if (channel.isNullOrEmpty() || start == null || stop == null || stop <= start) {
                        skipped++
                    } else {
                        programmeCount++
                        listener.onProgramme(
                            XmltvProgramme(
                                channelId = channel,
                                start = start,
                                stop = stop,
                                title = pTitle ?: "",
                                subtitle = pSubtitle,
                                description = pDesc,
                                category = pCategory,
                                iconUrl = pIcon,
                                episode = pEpisodeOnscreen ?: pEpisodeNs,
                            ),
                        )
                    }
                }
            }
        }

        // Recoverable errors: keep going.
        override fun error(e: org.xml.sax.SAXParseException) {}
        override fun warning(e: org.xml.sax.SAXParseException) {}
    }
}
