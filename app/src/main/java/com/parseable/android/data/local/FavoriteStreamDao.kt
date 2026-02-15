package com.parseable.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteStreamDao {
    @Query("SELECT * FROM favorite_streams ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavoriteStream>>

    @Query("SELECT streamName FROM favorite_streams")
    fun getAllNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteStream)

    @Delete
    suspend fun delete(favorite: FavoriteStream)

    @Query("DELETE FROM favorite_streams WHERE streamName = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_streams WHERE streamName = :name)")
    fun isFavorite(name: String): Flow<Boolean>
}
