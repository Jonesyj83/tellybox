package com.tellybox.tv.data.db

import android.content.Context
import androidx.room.AutoMigration
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
    @TypeConverter fun recordingStatusToString(value: RecordingStatus): String = value.name
    @TypeConverter fun stringToRecordingStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)
    @TypeConverter fun recordingSourceToString(value: RecordingSource): String = value.name
    @TypeConverter fun stringToRecordingSource(value: String): RecordingSource = RecordingSource.valueOf(value)
}

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        EpgSourceEntity::class,
        EpgChannelEntity::class,
        EpgMatchEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        RecordingEntity::class,
        ReminderEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
@TypeConverters(Converters::class)
abstract class TellyboxDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun groups(): ChannelGroupDao
    abstract fun channels(): ChannelDao
    abstract fun epg(): EpgDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): WatchHistoryDao
    abstract fun recordings(): RecordingDao
    abstract fun reminders(): ReminderDao

    companion object {
        fun create(context: Context): TellyboxDatabase =
            Room.databaseBuilder(context, TellyboxDatabase::class.java, "tellybox.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
