package com.opentelly.tv.data.parser

import com.opentelly.tv.data.parser.m3u.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class M3uParserTest {

    @Test
    fun parsesStandardEntries() {
        val text = """
            #EXTM3U url-tvg="http://epg.example.com/guide.xml.gz"
            #EXTINF:-1 tvg-id="news.uk" tvg-name="News One" tvg-logo="http://img/n1.png" group-title="News",News One HD
            http://example.com/live/1.ts
            #EXTINF:-1 tvg-id="sport.uk" group-title="Sport",Sport 2
            http://example.com/live/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(listOf("http://epg.example.com/guide.xml.gz"), result.header.epgUrls)
        assertEquals(2, result.entries.size)
        val first = result.entries[0]
        assertEquals("News One HD", first.name)
        assertEquals("news.uk", first.tvgId)
        assertEquals("News One", first.tvgName)
        assertEquals("http://img/n1.png", first.tvgLogo)
        assertEquals("News", first.groupTitle)
        assertEquals("http://example.com/live/1.ts", first.url)
        assertEquals(-1L, first.duration)
        assertEquals("Sport", result.entries[1].groupTitle)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitTitle() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Movies, Classic" group-title="Films, Old",Classic Movies, Channel 4
            http://x/1
        """.trimIndent()

        val e = M3uParser.parse(text).entries.single()
        assertEquals("Movies, Classic", e.tvgName)
        assertEquals("Films, Old", e.groupTitle)
        assertEquals("Classic Movies, Channel 4", e.name)
    }

    @Test
    fun missingAttributesAndHeader() {
        val text = "#EXTINF:-1,Plain Channel\r\nhttp://x/plain\r\n\r\nhttp://x/no-extinf.ts\r\n"

        val entries = M3uParser.parse(text).entries
        assertEquals(2, entries.size)
        assertEquals("Plain Channel", entries[0].name)
        assertNull(entries[0].tvgId)
        assertNull(entries[0].groupTitle)
        assertNull(entries[0].tvgLogo)
        // no #EXTINF: fall back to the last path segment
        assertEquals("no-extinf.ts", entries[1].name)
    }

    @Test
    fun emptyTitleFallsBackToTvgName() {
        val e = M3uParser.parse("#EXTINF:-1 tvg-name=\"Backup Name\",\nhttp://x/1").entries.single()
        assertEquals("Backup Name", e.name)
    }

    @Test
    fun catchupAttributesAndHeaderDefaults() {
        val text = """
            #EXTM3U catchup="shift" catchup-days="3"
            #EXTINF:-1 tvg-id="a" catchup="append" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}",A
            http://x/a
            #EXTINF:-1 tvg-id="b",B
            http://x/b
            #EXTINF:-1 timeshift="2",C
            http://x/c
        """.trimIndent()

        val entries = M3uParser.parse(text).entries
        assertEquals("append", entries[0].catchupType)
        assertEquals(7, entries[0].catchupDays)
        assertEquals("?utc={utc}&lutc={lutc}", entries[0].catchupSource)
        // inherits from header
        assertEquals("shift", entries[1].catchupType)
        assertEquals(3, entries[1].catchupDays)
        assertEquals(2, entries[2].catchupDays)
    }

    @Test
    fun unquotedAttributesExtgrpAndVlcOpts() {
        val text = """
            #EXTM3U
            #EXTINF:0 tvg-id=abc.de tvg-chno=12,Channel
            #EXTGRP:Germany
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://x/de
        """.trimIndent()

        val e = M3uParser.parse(text).entries.single()
        assertEquals("abc.de", e.tvgId)
        assertEquals(12, e.channelNumber)
        assertEquals("Germany", e.groupTitle)
        assertEquals("SomePlayer/1.0", e.userAgent)
        assertEquals("http://ref.example/", e.referrer)
        assertEquals(0L, e.duration)
    }

    @Test
    fun groupTitleWinsOverExtgrp() {
        val text = "#EXTINF:-1 group-title=\"A\",X\n#EXTGRP:B\nhttp://x"
        assertEquals("A", M3uParser.parse(text).entries.single().groupTitle)
    }

    @Test
    fun handlesBomAndLowercaseTags() {
        val text = "﻿#extm3u x-tvg-url=\"http://a/1.xml,http://a/2.xml\"\n#extinf:-1,Lower\nhttp://x"
        val result = M3uParser.parse(text)
        assertEquals(listOf("http://a/1.xml", "http://a/2.xml"), result.header.epgUrls)
        assertEquals("Lower", result.entries.single().name)
    }

    @Test
    fun parsesGzippedStream() {
        val text = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",Zipped\nhttp://x/z\n"
        val bytes = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        }.toByteArray()

        val e = M3uParser.parse(ByteArrayInputStream(bytes)).entries.single()
        assertEquals("Zipped", e.name)
        assertEquals("G", e.groupTitle)
    }

    @Test
    fun attributeParserToleratesUnterminatedQuote() {
        val attrs = M3uParser.parseAttributes(""" tvg-id="ok" tvg-logo="http://broken""")
        assertEquals("ok", attrs["tvg-id"])
        assertEquals("http://broken", attrs["tvg-logo"])
    }
}
