package com.tellybox.tv.data.repository

import com.tellybox.tv.data.parser.xmltv.XmltvStats

/** Both validation and import must succeed before a replacement can commit. */
internal object EpgRefreshGuard {
    fun requireReplacement(stats: XmltvStats, storedProgrammes: Int) {
        check(stats.error == null) { stats.error ?: "Invalid guide" }
        check(stats.programmes > 0 && storedProgrammes > 0) { "Guide contains no usable programmes; previous guide retained" }
    }

    fun keepProgramme(fullScan: Boolean, selected: Set<String>, channel: String): Boolean =
        fullScan || selected.isEmpty() || channel in selected
}
