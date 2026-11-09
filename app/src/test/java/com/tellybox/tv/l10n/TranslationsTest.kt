package com.tellybox.tv.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards every translated strings.xml against the default one: the same keys, and the same
 * format placeholders in every value. A dropped or renumbered placeholder crashes at runtime.
 */
class TranslationsTest {

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val default = parse(File(res, "values/strings.xml"))
    private val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!
        .sortedBy { it.name }

    /** One resource: its placeholder tokens per item ("" for a plain string, the quantity for a plural). */
    private class Entry(val items: Map<String, List<String>>)

    @Test
    fun `translations exist`() {
        assertTrue("no values-* folders with a strings.xml", locales.isNotEmpty())
    }

    @Test
    fun `every locale has the expected keys`() {
        val problems = locales.mapNotNull { dir ->
            val keys = parse(File(dir, "strings.xml")).keys
            val missing = default.keys - keys
            val extra = keys - default.keys
            if (missing.isEmpty() && extra.isEmpty()) null
            else "${dir.name}: missing ${missing.sorted()}, extra ${extra.sorted()}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `every translated value keeps the default placeholders`() {
        val problems = mutableListOf<String>()
        for (dir in locales) {
            val translated = parse(File(dir, "strings.xml"))
            for ((key, entry) in translated) {
                val source = default[key] ?: continue
                if (key.startsWith("plurals:")) {
                    // Each language has its own quantities; every one must carry the same placeholders.
                    val want = source.items["other"] ?: source.items.values.first()
                    if ("other" !in entry.items) problems += "${dir.name} $key: no \"other\" quantity"
                    entry.items.forEach { (quantity, tokens) ->
                        if (tokens != want) problems += "${dir.name} $key[$quantity]: $tokens, expected $want"
                    }
                } else if (entry.items != source.items) {
                    problems += "${dir.name} $key: ${entry.items}, expected ${source.items}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `locale config lists every translated locale`() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml")).getElementsByTagName("locale")
        val tags = (0 until config.length).map { (config.item(it) as Element).getAttribute("android:name") }
        fun qualifier(tag: String): String {
            val parts = tag.split('-')
            return when {
                parts.size == 1 -> parts[0]
                parts[1].length == 4 -> "b+" + parts.joinToString("+")
                else -> "${parts[0]}-r${parts[1]}"
            }
        }
        val folders = locales.map { it.name.removePrefix("values-") }
        val unlisted = folders.filter { folder -> tags.none { qualifier(it) == folder || it.substringBefore('-') == folder } }
        val missing = tags.filter { it != "en" }.filter { tag -> qualifier(tag) !in folders && tag.substringBefore('-') !in folders }
        assertEquals("folders not in locales_config.xml", emptyList<String>(), unlisted)
        assertEquals("locales_config.xml entries without strings", emptyList<String>(), missing)
    }

    private fun parse(file: File): Map<String, Entry> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = linkedMapOf<String, Entry>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            val name = "${e.tagName}:${e.getAttribute("name")}"
            val items = when (e.tagName) {
                "string" -> mapOf("" to tokens(e.textContent))
                "plurals" -> e.elements("item").associate { it.getAttribute("quantity") to tokens(it.textContent) }
                "string-array" -> e.elements("item").withIndex().associate { (n, it) -> n.toString() to tokens(it.textContent) }
                else -> continue
            }
            out[name] = Entry(items)
        }
        return out
    }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    companion object {
        private val TOKEN = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

        /** Format placeholders in [text], sorted so word order may change but nothing may go missing. */
        fun tokens(text: String): List<String> = TOKEN.findAll(text.replace("%%", "")).map { it.value }.sorted().toList()
    }
}
