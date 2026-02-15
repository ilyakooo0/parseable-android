package com.parseable.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class SettingsState(
    val serverUrl: String = "",
    val username: String = "",
    val useTls: Boolean = true,
    val aboutInfo: AboutInfo? = null,
    val users: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val config = settingsRepository.serverConfig.first()
            if (config != null) {
                _state.update {
                    it.copy(
                        serverUrl = config.serverUrl,
                        username = config.username,
                        useTls = config.useTls,
                    )
                }
            }

            val aboutDeferred = async { repository.getAbout() }
            val usersDeferred = async { repository.listUsers() }

            val aboutResult = aboutDeferred.await()
            val usersResult = usersDeferred.await()

            if (aboutResult is ApiResult.Success) {
                _state.update { it.copy(aboutInfo = aboutResult.data) }
            }

            if (usersResult is ApiResult.Success) {
                val userNames = usersResult.data.mapNotNull { obj ->
                    obj["id"]?.jsonPrimitive?.content
                        ?: obj["username"]?.jsonPrimitive?.content
                }
                _state.update { it.copy(users = userNames) }
            }
        }
    }
}
