package com.tellybox.tv.data.parental

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.tellybox.tv.data.db.ChannelEntity
import com.tellybox.tv.data.db.ChannelGroupEntity
import com.tellybox.tv.data.db.TellyboxDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParentalRepository(context: Context) {
    private val prefs = context.getSharedPreferences("parental", Context.MODE_PRIVATE)
    private val window = UnlockWindow(SystemClock::elapsedRealtime)
    data class State(val open: Boolean, val all: Boolean, val blocks: Set<String>)
    val changes = flow {
        while (true) {
            emit(State(unlocked, allScreens, prefs.getStringSet("blocks", emptySet())!!.toSet()))
            delay(1000)
        }
    }.distinctUntilChanged()
    fun exportBlocks(): Set<String> = prefs.getStringSet("blocks", emptySet())!!.toSet()
    fun replaceBlocks(blocks: Set<String>) { prefs.edit().putStringSet("blocks", blocks).commit() }
    fun exportConfiguration() = ParentalConfiguration(
        hash = prefs.getString("hash", null), salt = prefs.getString("salt", null),
        allScreens = allScreens, duration = duration,
        failures = prefs.getInt("failures", 0), cooldown = prefs.getLong("cooldown", 0),
    )

    fun replaceConfiguration(blocks: Set<String>, configuration: ParentalConfiguration?) {
        require(configuration == null || configuration.valid())
        val editor = prefs.edit().putStringSet("blocks", blocks)
        configuration?.let {
            editor.putString("hash", it.hash).putString("salt", it.salt)
                .putBoolean("all", it.allScreens).putInt("duration", it.duration)
                .putInt("failures", it.failures).putLong("cooldown", it.cooldown)
        }
        check(editor.commit()) { "Could not save parental configuration" }
        window.lock()
    }
    val configured get() = prefs.contains("hash")
    val unlocked get() = !configured || window.isOpen()
    val allScreens get() = prefs.getBoolean("all", false)
    val duration get() = prefs.getInt("duration", 30)
    fun setPolicy(all: Boolean) { prefs.edit().putBoolean("all", all).commit() }
    fun setDuration(minutes: Int) { require(minutes in listOf(0, 10, 30, 60, 120)); prefs.edit().putInt("duration", minutes).commit() }
    fun lock() = window.lock()
    private fun attempts() = PinAttempts(prefs.getInt("failures", 0), prefs.getLong("cooldown", 0))
    fun cooldownSeconds(): Long = attempts().secondsLeft(System.currentTimeMillis())
    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        require(PinSecurity.valid(pin))
        val salt = PinSecurity.salt()
        prefs.edit().putString("salt", encode(salt)).putString("hash", encode(PinSecurity.hash(pin, salt)))
            .putInt("failures", 0).putLong("cooldown", 0).commit()
        window.lock()
    }
    suspend fun verify(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (!configured || cooldownSeconds() > 0) return@withContext false
        val good = PinSecurity.matches(pin, decode(prefs.getString("salt", "")!!), decode(prefs.getString("hash", "")!!))
        if (good) {
            prefs.edit().putInt("failures", 0).putLong("cooldown", 0).commit()
            window.unlock(duration)
        } else {
            val next = attempts().failed(System.currentTimeMillis())
            prefs.edit().putInt("failures", next.failures).putLong("cooldown", next.blockedUntil).commit()
        }
        good
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    fun channelKey(c: ChannelEntity) = BlockKeys.channel(c)
    fun groupKey(g: ChannelGroupEntity) = BlockKeys.group(g)
    fun blocked(key: String) = prefs.getStringSet("blocks", emptySet())!!.contains(key)
    fun toggle(key: String) {
        check(unlocked && configured)
        val blocks = prefs.getStringSet("blocks", emptySet())!!.toMutableSet()
        if (!blocks.remove(key)) blocks.add(key)
        prefs.edit().putStringSet("blocks", blocks).commit()
    }
    suspend fun allowed(c: ChannelEntity, db: TellyboxDatabase): Boolean {
        if (unlocked) return true
        if (blocked(channelKey(c))) return false
        val group = db.groups().getForPlaylist(c.playlistId).find { it.id == c.groupId }
        return if (group == null) c.groupId == null else !blocked(groupKey(group))
    }
    suspend fun visible(items: List<ChannelEntity>, db: TellyboxDatabase): List<ChannelEntity> {
        if (unlocked) return items
        val groups = items.map { it.playlistId }.distinct().flatMap { db.groups().getForPlaylist(it) }
        val allowedGroups = groups.filterNot { blocked(groupKey(it)) }.mapTo(HashSet()) { it.id }
        return items.filter { !blocked(channelKey(it)) && (it.groupId == null || it.groupId in allowedGroups) }
    }
}
