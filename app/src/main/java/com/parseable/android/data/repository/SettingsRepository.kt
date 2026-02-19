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
        .map { prefs ->
            configMutex.withLock {
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

    suspend fun saveServerConfig(config: ServerConfig) {
        configMutex.withLock {
            try {
                context.dataStore.edit { prefs ->
                    prefs[serverUrlKey] = config.serverUrl
                    prefs[usernameKey] = config.username
                    prefs[useTlsKey] = config.useTls
                }
                withContext(Dispatchers.IO) {
                    encryptedPrefs.edit().putString("password", config.password).apply()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save server config")
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
                    encryptedPrefs.edit().remove("password").apply()
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
     * Returns the server ID.
     */
    suspend fun saveServer(config: ServerConfig): Long {
        return configMutex.withLock {
            val existing = savedServerDao.findByUrlAndUsername(config.serverUrl, config.username)
            val passwordKey = existing?.passwordKey ?: "server_pwd_${System.currentTimeMillis()}"
            val server = SavedServer(
                id = existing?.id ?: 0,
                serverUrl = config.serverUrl,
                username = config.username,
                useTls = config.useTls,
                passwordKey = passwordKey,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) {
                encryptedPrefs.edit().putString(passwordKey, config.password).apply()
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

            // Write to active config
            context.dataStore.edit { prefs ->
                prefs[serverUrlKey] = config.serverUrl
                prefs[usernameKey] = config.username
                prefs[useTlsKey] = config.useTls
                prefs[activeServerIdKey] = serverId
            }
            withContext(Dispatchers.IO) {
                encryptedPrefs.edit().putString("password", config.password).apply()
            }
            config
        }
    }

    /**
     * Remove a saved server and its stored password.
     * If the deleted server was the active one, the active server ID is cleared.
     */
    suspend fun deleteServer(serverId: Long) {
        configMutex.withLock {
            val server = savedServerDao.getById(serverId) ?: return@withLock
            withContext(Dispatchers.IO) {
                encryptedPrefs.edit().remove(server.passwordKey).apply()
            }
            savedServerDao.deleteById(serverId)

            // Clear active server reference if it pointed at the deleted server
            val currentActiveId = context.dataStore.data.first()[activeServerIdKey]
            if (currentActiveId == serverId) {
                context.dataStore.edit { prefs ->
                    prefs.remove(activeServerIdKey)
                }
            }
        }
    }
}
