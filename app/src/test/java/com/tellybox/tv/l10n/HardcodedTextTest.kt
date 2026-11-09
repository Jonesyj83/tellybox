package com.tellybox.tv.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against text being typed straight into a layout.
 *
 * A literal in `android:text` looks fine in English and then stays English in all thirty-two
 * other languages, which is exactly the sort of thing nobody notices until they switch language
 * and half the screen is wrong. Anything shown to the viewer belongs in `strings.xml`.
 */
class HardcodedTextTest {

    private val resDir: File = listOf(
        File("src/main/res"),
        File("app/src/main/res"),
    ).firstOrNull { it.isDirectory }
        ?: error("Can't find the resource folder from ${File(".").absolutePath}")

    /** Attributes whose value ends up in front of the viewer. */
    private val watched = listOf(
        "android:text",
        "android:hint",
        "android:contentDescription",
        "android:title",
        "android:summary",
    )

    @Test
    fun `no layout types its text in by hand`() {
        val offences = ArrayList<String>()

        resDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { it.parentFile.name == "layout" || it.parentFile.name == "menu" || it.parentFile.name == "xml" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    watched.forEach { attr ->
                        val match = Regex("""$attr\s*=\s*"([^"]*)"""").find(line) ?: return@forEach
                        val value = match.groupValues[1].trim()
                        val isReference = value.startsWith("@") || value.startsWith("?")
                        val isPlaceholder = value.isEmpty() || value.startsWith("{")
                        if (!isReference && !isPlaceholder) {
                            offences += "${file.name}:${index + 1} $attr=\"$value\""
                        }
                    }
                }
            }

        assertTrue(
            "Move these into strings.xml:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `every translated string has content`() {
        val strings = resDir.resolve("values/strings.xml")
        val empty = Regex("""<string name="([^"]+)"\s*/>""").findAll(strings.readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals("Empty strings: $empty", 0, empty.size)
    }
}
