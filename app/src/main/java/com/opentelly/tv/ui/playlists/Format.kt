package com.opentelly.tv.ui.playlists

import android.content.Context
import com.opentelly.tv.R
import com.opentelly.tv.data.repository.RefreshSummary

/** "120 channels, 40 movies" — leaves out kinds the playlist doesn't have. */
fun RefreshSummary.describe(context: Context): String {
    val r = context.resources
    val parts = buildList {
        add(r.getQuantityString(R.plurals.count_channels, channels, channels))
        if (movies > 0) add(r.getQuantityString(R.plurals.count_movies, movies, movies))
        if (series > 0) add(r.getQuantityString(R.plurals.count_series, series, series))
    }
    return parts.joinToString(", ")
}
