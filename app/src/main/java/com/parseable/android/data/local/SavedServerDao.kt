package com.parseable.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedServerDao {
    @Query("SELECT * FROM saved_servers ORDER BY addedAt DESC")
    fun getAll(): Flow<List<SavedServer>>

    @Query("SELECT * FROM saved_servers WHERE id = :id")
    suspend fun getById(id: Long): SavedServer?

    @Query("SELECT * FROM saved_servers WHERE serverUrl = :url AND username = :username LIMIT 1")
    suspend fun findByUrlAndUsername(url: String, username: String): SavedServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: SavedServer): Long

    @Query("DELETE FROM saved_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
