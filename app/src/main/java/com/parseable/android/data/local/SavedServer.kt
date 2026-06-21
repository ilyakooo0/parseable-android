package com.parseable.android.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_servers",
    // One logical server per (url, username). The dedupe in SettingsRepository.saveServer
    // relies on this invariant; enforcing it in the schema means REPLACE actually upserts on
    // the natural key instead of silently inserting a duplicate row if the read-back misses.
    indices = [Index(value = ["serverUrl", "username"], unique = true)],
)
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
