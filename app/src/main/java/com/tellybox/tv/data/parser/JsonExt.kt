package com.tellybox.tv.data.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Lenient accessors for provider JSON. Panels return the same field as a string, a number,
 * a boolean, null or an empty string depending on version and mood, so every read goes
 * through here instead of strict decoding.
 */

fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull || el !is JsonPrimitive) return null
    return el.content.trim().ifEmpty { null }?.takeUnless { it == "null" }
}

fun JsonObject.long(key: String): Long? {
    val s = str(key) ?: return null
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.double(key: String): Double? = str(key)?.replace(',', '.')?.toDoubleOrNull()

fun JsonObject.bool(key: String): Boolean {
    val s = str(key) ?: return false
    return s == "1" || s.equals("true", ignoreCase = true)
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Arrays of strings, or a single string. Handles `"backdrop_path": ["a", "b"]` and `"a"`. */
fun JsonObject.strList(key: String): List<String> = when (val el = this[key]) {
    is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.ifEmpty { null } }
    is JsonPrimitive -> listOfNotNull(str(key))
    else -> emptyList()
}

/** Treats `[...]` as a list and `{"0": {...}, "1": {...}}` as its values. */
fun JsonElement?.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}
