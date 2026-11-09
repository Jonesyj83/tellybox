package com.tellybox.tv.ui.playlists

import android.content.Context
import com.tellybox.tv.R
import com.tellybox.tv.data.repository.RefreshSummary

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

/** Each count chooses its own plural; translators control summary order. */
fun playlistSummary(context: Context, channels: Int, groups: Int, guideAttached: Boolean): String = context.getString(
    R.string.playlist_summary,
    context.resources.getQuantityString(R.plurals.count_channels, channels, channels),
    context.resources.getQuantityString(R.plurals.count_groups, groups, groups),
    context.getString(if (guideAttached) R.string.playlist_guide_attached else R.string.playlist_no_guide),
)

fun Context.contentKindLabel(kind: com.tellybox.tv.data.db.ContentKind): String = getString(when (kind) {
    com.tellybox.tv.data.db.ContentKind.LIVE -> R.string.rail_live
    com.tellybox.tv.data.db.ContentKind.MOVIE -> R.string.vod_movies
    com.tellybox.tv.data.db.ContentKind.SERIES -> R.string.vod_series
})
