package com.parseable.android.data.repository

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val usernameKey = stringPreferencesKey("username")
    private val useTlsKey = booleanPreferencesKey("use_tls")

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
                context.dataStore.edit { it.clear() }
                withContext(Dispatchers.IO) {
                    encryptedPrefs.edit().clear().apply()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear config")
            }
        }
    }
}
