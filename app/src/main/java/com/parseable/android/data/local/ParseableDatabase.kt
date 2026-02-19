package com.parseable.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteStream::class, SavedServer::class],
    version = 2,
    exportSchema = true,
)
abstract class ParseableDatabase : RoomDatabase() {
    abstract fun favoriteStreamDao(): FavoriteStreamDao
    abstract fun savedServerDao(): SavedServerDao
}
