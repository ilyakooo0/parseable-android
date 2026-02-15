package com.parseable.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteStream::class],
    version = 1,
    exportSchema = true,
)
abstract class ParseableDatabase : RoomDatabase() {
    abstract fun favoriteStreamDao(): FavoriteStreamDao
}
