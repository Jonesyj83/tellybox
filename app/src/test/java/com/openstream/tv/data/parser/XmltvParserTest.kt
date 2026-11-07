package com.openstream.tv.data.parser

import com.openstream.tv.data.parser.xmltv.XmltvChannel
import com.openstream.tv.data.parser.xmltv.XmltvListener
import com.openstream.tv.data.parser.xmltv.XmltvParser
import com.openstream.tv.data.parser.xmltv.XmltvProgramme
import com.openstream.tv.data.parser.xmltv.XmltvTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvParserTest {
    @Test fun retainsEveryDisplayNameForMatching() {
        val (channels, _) = XmltvParser.parseAll("""<tv><channel id="bbc"><display-name>BBC 1</display-name><display-name>BBC One</display-name></channel></tv>""")
        assertEquals("BBC 1", channels.single().displayName)
        assertEquals(listOf("BBC 1", "BBC One"), channels.single().displayNames)
    }


    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="bbc1.uk">
            <display-name lang="en">BBC One</display-name>
            <display-name lang="cy">BBC Un</display-name>
            <icon src="http://img/bbc1.png"/>
          </channel>
          <channel id="itv.uk">
            <display-name>ITV</display-name>
          </channel>
          <programme start="20240115180000 +0000" stop="20240115183000 +0000" channel="bbc1.uk">
            <title lang="en">News at Six</title>
            <sub-title>Evening bulletin</sub-title>
            <desc lang="en">The latest headlines &amp; weather.</desc>
            <category>News</category>
            <episode-num system="xmltv_ns">0.11.</episode-num>
          </programme>
          <programme start="20240115190000 +0100" stop="20240115200000 +0100" channel="itv.uk">
            <title>Quiz Night</title>
            <episode-num system="onscreen">S02E05</episode-num>
            <icon src="http://img/quiz.jpg"/>
          </programme>
          <programme start="20240115200000 +0000" channel="itv.uk">
            <title>No stop time</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesChannelsAndProgrammes() {
        val (channels, programmes) = XmltvParser.parseAll(sample)

        assertEquals(2, channels.size)
        assertEquals(XmltvChannel("bbc1.uk", "BBC One", "http://img/bbc1.png", listOf("BBC One", "BBC Un")), channels[0])
        assertEquals("ITV", channels[1].displayName)
        assertNull(channels[1].iconUrl)

        assertEquals(2, programmes.size)
        val news = programmes[0]
        assertEquals("bbc1.uk", news.channelId)
        assertEquals("News at Six", news.title)
        assertEquals("Evening bulletin", news.subtitle)
        assertEquals("The latest headlines & weather.", news.description)
        assertEquals("News", news.category)
        assertEquals("S1 E12", news.episode)
        assertEquals(30 * 60_000L, news.stop - news.start)

        val quiz = programmes[1]
        assertEquals("S02E05", quiz.episode)
        assertEquals("http://img/quiz.jpg", quiz.iconUrl)
        // 19:00 +0100 == 18:00 UTC
        assertEquals(XmltvTime.parse("20240115180000 +0000"), quiz.start)
    }

    @Test
    fun skipsProgrammesWithoutStop() {
        val stats = XmltvParser.parse(sample, object : XmltvListener {})
        assertEquals(2, stats.programmes)
        assertEquals(1, stats.skipped)
        assertNull(stats.error)
    }

    @Test
    fun descriptionsCanBeDropped() {
        val list = ArrayList<XmltvProgramme>()
        XmltvParser.parse(sample, object : XmltvListener {
            override fun onProgramme(programme: XmltvProgramme) { list += programme }
        }, includeDescriptions = false)
        assertNull(list[0].description)
        assertEquals("News at Six", list[0].title)
    }

    @Test
    fun readsGzippedGuide() {
        val gz = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(sample.toByteArray()) }
        }.toByteArray()

        var count = 0
        val stats = XmltvParser.parse(ByteArrayInputStream(gz), object : XmltvListener {
            override fun onProgramme(programme: XmltvProgramme) { count++ }
        })
        assertEquals(2, count)
        assertEquals(2, stats.channels)
    }

    @Test
    fun truncatedFileKeepsWhatWasRead() {
        val cut = sample.substring(0, sample.indexOf("<programme start=\"20240115190000"))
        val stats = XmltvParser.parse(cut, object : XmltvListener {})
        assertEquals(1, stats.programmes)
        assertNotNull(stats.error)
    }

    @Test
    fun timeFormats() {
        val base = XmltvTime.parse("20240115180000 +0000")!!
        assertEquals(base, XmltvTime.parse("20240115180000"))
        assertEquals(base, XmltvTime.parse("202401151800"))
        assertEquals(base, XmltvTime.parse("20240115200000 +0200"))
        assertEquals(base, XmltvTime.parse("20240115133000 -0430"))
        assertEquals(base, XmltvTime.parse("20240115190000 +01:00"))
        assertEquals(1705341600000L, base)
        assertNull(XmltvTime.parse("garbage"))
        assertNull(XmltvTime.parse("20241315180000 +0000"))
        assertNull(XmltvTime.parse("20240115180000 EST"))
    }
}
