package com.parseable.android.data.repository

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.parseable.android.data.local.SavedServer
import com.parseable.android.data.local.SavedServerDao
import com.parseable.android.data.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "parseable_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedServerDao: SavedServerDao,
) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val usernameKey = stringPreferencesKey("username")
    private val useTlsKey = booleanPreferencesKey("use_tls")
    private val activeServerIdKey = longPreferencesKey("active_server_id")

    /** Guards concurrent reads/writes across DataStore and EncryptedSharedPreferences. */
    private val configMutex = Mutex()

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // Keystore corruption (common after backup/restore). Delete and recreate.
            Timber.e(e, "EncryptedSharedPreferences corrupted, resetting")
            try {
                val prefsFile = java.io.File(context.filesDir.parent, "shared_prefs/parseable_secure_prefs.xml")
                if (prefsFile.exists()) prefsFile.delete()
            } catch (_: Exception) { /* best-effort cleanup */ }
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "parseable_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Active server config (current connection) ──────────────────────

    val serverConfig: Flow<ServerConfig?> = context.dataStore.data
        .catch { e ->
            Timber.e(e, "Failed to read DataStore")
            emit(emptyPreferences())
        }
        .map {
            // Read url/username/tls AND the encrypted password together under configMutex,
            // re-reading a fresh DataStore snapshot inside the lock. Writers (switch/save/clear)
            // hold this mutex across their whole multi-store edit, so acquiring it here guarantees
            // the url/username returned always belong to the same server as the password. A
            // lock-free read could otherwise pair one server's username with another server's
            // password if a concurrent switch's writes landed between the DataStore read and the
            // encrypted-password read. All callers consume this via .first(), so serializing the
            // read behind the (brief) write critical sections costs at most one extra snapshot read.
            configMutex.withLock {
                val prefs = context.dataStore.data.first()
                val url = prefs[serverUrlKey] ?: return@withLock null
                val user = prefs[usernameKey] ?: return@withLock null
                val pass = try {
                    encryptedPrefs.getString("password", null)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read encrypted password")
                    null
                } ?: return@withLock null
                ServerConfig(
                    serverUrl = url,
                    username = user,
                    password = pass,
                    useTls = prefs[useTlsKey] ?: true,
                )
            }
        }
        .flowOn(Dispatchers.IO)

    val activeServerId: Flow<Long?> = context.dataStore.data
        .catch { e ->
            Timber.e(e, "Failed to read DataStore")
            emit(emptyPreferences())
        }
        .map { prefs -> prefs[activeServerIdKey] }
        .flowOn(Dispatchers.IO)

    suspend fun getSavedPassword(): ServerConfig? = serverConfig.first()

    /**
     * Persist the server config. Returns true only if the credentials were durably saved.
     * Callers (e.g. login) must not report success when this returns false — otherwise the
     * session would appear logged in but vanish on the next launch.
     */
    suspend fun saveServerConfig(config: ServerConfig): Boolean {
        return configMutex.withLock {
            try {
                // Persist the password (encrypted) first and confirm it committed. If the
                // Keystore-backed write fails we abort before touching DataStore, so we never
                // leave a new URL/username paired with a stale password.
                val passwordSaved = withContext(Dispatchers.IO) {
                    encryptedPrefs.edit().putString("password", config.password).commit()
                }
                if (!passwordSaved) {
                    Timber.e("Failed to persist credentials securely; aborting config save")
                    return@withLock false
                }
                context.dataStore.edit { prefs ->
                    prefs[serverUrlKey] = config.serverUrl
                    prefs[usernameKey] = config.username
                    prefs[useTlsKey] = config.useTls
                }
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save server config")
                false
            }
        }
    }

    suspend fun clearConfig() {
        configMutex.withLock {
            try {
                context.dataStore.edit { prefs ->
                    prefs.remove(serverUrlKey)
                    prefs.remove(usernameKey)
                    prefs.remove(useTlsKey)
                    prefs.remove(activeServerIdKey)
                }
                withContext(Dispatchers.IO) {
                    // commit() (not apply()) so the cleared password is durably gone before we
                    // return — a queued async clear could survive into the next session.
                    encryptedPrefs.edit().remove("password").commit()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear config")
            }
        }
    }

    // ── Multi-server management ────────────────────────────────────────

    val savedServers: Flow<List<SavedServer>> = savedServerDao.getAll()

    /**
     * Persist the current server connection as a saved server entry.
     * If a server with the same URL and username already exists, updates it.
     * Returns the server ID, or null if the credentials could not be persisted.
     */
    suspend fun saveServer(config: ServerConfig): Long? {
        return configMutex.withLock {
            val existing = savedServerDao.findByUrlAndUsername(config.serverUrl, config.username)
            // Use a UUID, not System.currentTimeMillis(): two distinct new servers saved
            // within the same millisecond would otherwise share a key and the second
            // would overwrite the first's encrypted password.
            val passwordKey = existing?.passwordKey ?: "server_pwd_${java.util.UUID.randomUUID()}"
            val server = SavedServer(
                id = existing?.id ?: 0,
                serverUrl = config.serverUrl,
                username = config.username,
                useTls = config.useTls,
                passwordKey = passwordKey,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
            // Persist the password durably (commit + check) BEFORE inserting the row and
            // marking it active. With apply() a crash could leave a saved-server row and an
            // active-server-id pointing at a passwordKey that was never flushed, making the
            // server unusable (switchToServer would return null).
            val passwordSaved = withContext(Dispatchers.IO) {
                encryptedPrefs.edit().putString(passwordKey, config.password).commit()
            }
            if (!passwordSaved) {
                // Return null rather than existing?.id: reusing the existing id would look like a
                // successful update to the caller, while the new password was never written and a
                // later switchToServer would silently load the stale credentials.
                Timber.e("Failed to persist saved-server credentials securely; aborting saveServer")
                return@withLock null
            }
            val id = savedServerDao.insert(server)

            // Update active server ID
            context.dataStore.edit { prefs ->
                prefs[activeServerIdKey] = id
            }
            id
        }
    }

    /**
     * Switch to a previously saved server. Loads its credentials into the
     * active config (DataStore + encrypted password).
     */
    suspend fun switchToServer(serverId: Long): ServerConfig? {
        return configMutex.withLock {
            val server = savedServerDao.getById(serverId) ?: return@withLock null
            val password = withContext(Dispatchers.IO) {
                try {
                    encryptedPrefs.getString(server.passwordKey, null)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read server password")
                    null
                }
            } ?: return@withLock null

            val config = ServerConfig(
                serverUrl = server.serverUrl,
                username = server.username,
                password = password,
                useTls = server.useTls,
            )

            // Persist the active password durably (commit + check) BEFORE updating the active
            // DataStore config. With apply() a crash could leave the active URL/username pointing
            // at a stale/missing password — the exact inconsistency saveServerConfig avoids.
            val passwordSaved = withContext(Dispatchers.IO) {
                encryptedPrefs.edit().putString("password", config.password).commit()
            }
            if (!passwordSaved) {
                Timber.e("Failed to persist credentials securely; aborting server switch")
                return@withLock null
            }
            // Write to active config
            context.dataStore.edit { prefs ->
                prefs[serverUrlKey] = config.serverUrl
                prefs[usernameKey] = config.username
                prefs[useTlsKey] = config.useTls
                prefs[activeServerIdKey] = serverId
            }
            config
        }
    }

    /**
     * Remove a saved server and its stored password.
     * If the deleted server was the active one, the active server ID is cleared.
     * Returns true if the deleted server was the active connection (so callers can clear any
     * now-stale displayed server info), false otherwise.
     */
    suspend fun deleteServer(serverId: Long): Boolean {
        return configMutex.withLock {
            val server = savedServerDao.getById(serverId) ?: return@withLock false
            withContext(Dispatchers.IO) {
                // commit() so the password is durably removed before we drop the row that
                // references its key — otherwise a crash could orphan the encrypted entry.
                encryptedPrefs.edit().remove(server.passwordKey).commit()
            }
            savedServerDao.deleteById(serverId)

            // Clear active server reference if it pointed at the deleted server. The active
            // connection keys (url/username/tls) and the active encrypted password live
            // separately from the per-server row, so they must be torn down too — otherwise
            // serverConfig still emits a valid config and the app stays logged in to the
            // server we just deleted (and restores that session on next launch).
            val currentActiveId = context.dataStore.data.first()[activeServerIdKey]
            val wasActive = currentActiveId == serverId
            if (wasActive) {
                context.dataStore.edit { prefs ->
                    prefs.remove(activeServerIdKey)
                    prefs.remove(serverUrlKey)
                    prefs.remove(usernameKey)
                    prefs.remove(useTlsKey)
                }
                withContext(Dispatchers.IO) {
                    encryptedPrefs.edit().remove("password").commit()
                }
            }
            wasActive
        }
    }

    /**
     * Repair a dangling active_server_id left by a row-collapsing migration. MIGRATION_2_3 deletes
     * duplicate saved_servers rows as raw SQL, but the active-server pointer lives in DataStore and
     * can't be touched there — so after that upgrade it may reference one of the deleted duplicates.
     * Re-link it to the surviving row for the active (url, username) when one exists; otherwise just
     * clear the stale pointer. The active connection (url/username/password) is left intact, so a
     * working session is never logged out — only the saved-servers radio selection is corrected.
     * Best-effort, safe to run at startup.
     */
    suspend fun reconcileActiveServerId() {
        configMutex.withLock {
            val prefs = context.dataStore.data.first()
            val activeId = prefs[activeServerIdKey] ?: return@withLock
            if (savedServerDao.getById(activeId) != null) return@withLock
            val url = prefs[serverUrlKey]
            val user = prefs[usernameKey]
            val surviving = if (url != null && user != null) {
                savedServerDao.findByUrlAndUsername(url, user)
            } else {
                null
            }
            context.dataStore.edit { p ->
                if (surviving != null) {
                    p[activeServerIdKey] = surviving.id
                } else {
                    p.remove(activeServerIdKey)
                }
            }
        }
    }

    /**
     * Remove EncryptedSharedPreferences password entries that no longer correspond to a
     * saved_servers row. Row-collapsing schema migrations (e.g. MIGRATION_2_3, which deletes
     * duplicate rows) run as raw SQL and can't reach EncryptedSharedPreferences, so the
     * removed rows' "server_pwd_*" keys would otherwise linger encrypted on disk forever.
     * Best-effort, safe to run at startup. Only touches per-server keys ("server_pwd_*") so
     * the active-connection "password" key is never disturbed.
     */
    suspend fun cleanupOrphanedServerPasswords() {
        configMutex.withLock {
            val referenced = savedServerDao.getAll().first().map { it.passwordKey }.toSet()
            withContext(Dispatchers.IO) {
                val orphans = encryptedPrefs.all.keys.filter {
                    it.startsWith("server_pwd_") && it !in referenced
                }
                if (orphans.isNotEmpty()) {
                    encryptedPrefs.edit().apply {
                        orphans.forEach { remove(it) }
                    }.commit()
                    Timber.i("Removed ${orphans.size} orphaned server password entries")
                }
            }
        }
    }
}
