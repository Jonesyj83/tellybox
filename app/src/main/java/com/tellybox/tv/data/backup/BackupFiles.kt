package com.tellybox.tv.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backups live in Downloads, the one folder a person can reach with a file manager on a TV.
 *
 * The system pickers are unusable with a remote — the save dialog collapses its own fields as soon
 * as the keyboard opens and nothing scrolls — so saving is done straight to the folder and reading
 * is done by listing what is already there.
 */
object BackupFiles {

    private const val PREFIX = "tellybox-backup"
    private const val MIME = "application/json"

    /** `tellybox-backup-2026-11-09-1340.json` — sorts by name, reads without opening it. */
    fun defaultName(now: Long = System.currentTimeMillis()): String =
        "$PREFIX-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".json"

    /** Writes the file and returns its name. Throws if the folder refuses the write. */
    fun save(context: Context, name: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Downloads is not writable")
            try {
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                    ?: throw java.io.IOException("Downloads is not writable")
            } finally {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } else {
            folder().mkdirs()
            File(folder(), name).writeText(text)
        }
    }

    /**
     * Newest first. Empty when nothing has been exported to this device yet.
     *
     * Both the media index and the folder itself are read: a file pushed on over USB is in the
     * folder long before the index hears about it, and a file written through the index is not
     * always something [File] can see on newer releases.
     */
    fun list(context: Context): List<String> {
        val names = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$PREFIX%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
            }
        }
        folder().listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name }
            ?.forEach { names.add(it.name) }
        return names.sortedDescending()
    }

    fun read(context: Context, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    )
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?.let { return it }
                }
            }
        }
        val file = File(folder(), name)
        if (file.exists()) return file.readText()
        throw java.io.FileNotFoundException(name)
    }

    private fun folder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}
