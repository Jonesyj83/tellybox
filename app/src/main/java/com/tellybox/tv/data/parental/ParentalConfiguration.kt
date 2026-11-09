package com.tellybox.tv.data.parental

import kotlinx.serialization.Serializable

@Serializable
data class ParentalConfiguration(
    val hash: String? = null,
    val salt: String? = null,
    val allScreens: Boolean = false,
    val duration: Int = 30,
    val failures: Int = 0,
    val cooldown: Long = 0,
) {
    fun valid() = (hash == null) == (salt == null) &&
        duration in listOf(0, 10, 30, 60, 120) && failures >= 0 && cooldown >= 0

    override fun toString() = "ParentalConfiguration(redacted)"
}

// Older backups cannot replace credentials they never carried.
suspend fun applyParentalBackup(
    blocks: Set<String>,
    configuration: ParentalConfiguration?,
    replace: suspend (Set<String>, ParentalConfiguration?) -> Unit,
) = replace(blocks, configuration)
