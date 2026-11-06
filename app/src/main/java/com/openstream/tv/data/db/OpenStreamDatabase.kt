package com.openstream.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun playlistTypeToString(value: PlaylistType): String = value.name
    @TypeConverter fun stringToPlaylistType(value: String): PlaylistType = PlaylistType.valueOf(value)
    @TypeConverter fun kindToString(value: ContentKind): String = value.name
    @TypeConverter fun stringToKind(value: String): ContentKind = ContentKind.valueOf(value)
}

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        EpgSourceEntity::class,
        EpgChannelEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenStreamDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun groups(): ChannelGroupDao
    abstract fun channels(): ChannelDao
    abstract fun epg(): EpgDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): WatchHistoryDao

    companion object {
        fun create(context: Context): OpenStreamDatabase =
            Room.databaseBuilder(context, OpenStreamDatabase::class.java, "openstream.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
