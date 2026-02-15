package com.parseable.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_streams")
data class FavoriteStream(
    @PrimaryKey
    val streamName: String,
    val addedAt: Long = System.currentTimeMillis(),
)
