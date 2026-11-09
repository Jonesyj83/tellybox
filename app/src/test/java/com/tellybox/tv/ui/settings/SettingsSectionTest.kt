package com.tellybox.tv.ui.settings

import com.tellybox.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionTest {
    private data class Row(val id: Int, val title: String)

    @Test
    fun `translated or duplicate titles cannot change section membership`() {
        val language = Row(R.string.setting_language, "اللغة")
        val clock = Row(R.string.appearance_show_clock, "اللغة")
        val timeshift = Row(R.string.setting_timeshift, "Décalage temporel")
        val rows = listOf(timeshift, clock, language)
        assertEquals(listOf(language), SettingsSection.GENERAL.select(rows) { it.id })
        assertEquals(listOf(clock), SettingsSection.APPEARANCE.select(rows) { it.id })
        assertEquals(listOf(timeshift), SettingsSection.PLAYER.select(rows) { it.id })
        assertTrue(SettingsSection.EPG.select(rows) { it.id }.isEmpty())
    }

    @Test
    fun `row identities belong to exactly one section`() {
        val ids = SettingsSection.entries.flatMap { it.rowIds.toList() }
        assertEquals(ids.size, ids.toSet().size)
        // These used to depend on the positions of section headers.
        assertTrue(R.string.setting_timeshift_buffer in SettingsSection.PLAYER.rowIds)
        assertTrue(R.string.guide_full_scan in SettingsSection.EPG.rowIds)
    }
}
