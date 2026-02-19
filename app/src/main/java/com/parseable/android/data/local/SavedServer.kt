package com.parseable.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_servers")
data class SavedServer(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serverUrl: String,
    val username: String,
    val useTls: Boolean = true,
    /** Key into EncryptedSharedPreferences for this server's password. */
    val passwordKey: String,
    val addedAt: Long = System.currentTimeMillis(),
)
