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

    @Query("SELECT streamName FROM favorite_streams ORDER BY addedAt DESC")
    fun getAllNames(): Flow<List<String>>

    // IGNORE (not REPLACE): re-favoriting an existing stream must keep its original
    // addedAt so the getAll() "ORDER BY addedAt DESC" ordering stays stable.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteStream)

    @Delete
    suspend fun delete(favorite: FavoriteStream)

    @Query("DELETE FROM favorite_streams WHERE streamName = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_streams WHERE streamName = :name)")
    fun isFavorite(name: String): Flow<Boolean>
}
