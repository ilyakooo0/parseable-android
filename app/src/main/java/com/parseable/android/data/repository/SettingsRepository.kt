package com.parseable.android.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.parseable.android.data.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "parseable_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val serverConfig: Flow<ServerConfig?> = context.dataStore.data.map { prefs ->
        val url = prefs[serverUrlKey] ?: return@map null
        val user = prefs[usernameKey] ?: return@map null
        val pass = encryptedPrefs.getString("password", null) ?: return@map null
        ServerConfig(
            serverUrl = url,
            username = user,
            password = pass,
            useTls = prefs[useTlsKey] ?: true,
        )
    }

    suspend fun saveServerConfig(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = config.serverUrl
            prefs[usernameKey] = config.username
            prefs[useTlsKey] = config.useTls
        }
        encryptedPrefs.edit().putString("password", config.password).commit()
    }

    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
        encryptedPrefs.edit().clear().commit()
    }
}
