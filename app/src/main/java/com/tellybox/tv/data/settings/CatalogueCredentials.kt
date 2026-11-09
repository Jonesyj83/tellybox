package com.tellybox.tv.data.settings

import android.content.Context

/** Device-only credentials, deliberately separate from exported AppSettings. */
class CatalogueCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("catalogue_credentials", Context.MODE_PRIVATE)
    fun key(): String = prefs.getString("tmdb_key", "").orEmpty()
    fun setKey(value: String) { prefs.edit().putString("tmdb_key", value.trim()).apply() }
}
