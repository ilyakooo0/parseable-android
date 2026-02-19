package com.parseable.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.local.SavedServer
import com.parseable.android.data.model.AboutInfo
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedServers: List<SavedServer> = emptyList(),
    val activeServerId: Long? = null,
    val isSwitching: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repository: ParseableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Emits the server ID after a successful switch so the screen can navigate. */
    private val _switchEvent = MutableStateFlow<Long?>(null)
    val switchEvent: StateFlow<Long?> = _switchEvent.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            settingsRepository.savedServers.collect { servers ->
                _state.update { it.copy(savedServers = servers) }
            }
        }
        viewModelScope.launch {
            settingsRepository.activeServerId.collect { id ->
                _state.update { it.copy(activeServerId = id) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val config = settingsRepository.serverConfig.first()

            // Show server info immediately
            _state.update {
                it.copy(
                    serverUrl = config?.serverUrl ?: it.serverUrl,
                    username = config?.username ?: it.username,
                    useTls = config?.useTls ?: it.useTls,
                )
            }

            try {
                val (aboutResult, usersResult) = coroutineScope {
                    val aboutDeferred = async { repository.getAbout() }
                    val usersDeferred = async { repository.listUsers() }
                    aboutDeferred.await() to usersDeferred.await()
                }

                val userNames = (usersResult as? ApiResult.Success)?.data?.mapNotNull { obj ->
                    try {
                        obj["id"]?.jsonPrimitive?.content
                            ?: obj["username"]?.jsonPrimitive?.content
                    } catch (_: IllegalStateException) {
                        null
                    }
                } ?: emptyList()

                val errors = listOfNotNull(
                    (aboutResult as? ApiResult.Error)?.userMessage,
                    (usersResult as? ApiResult.Error)?.userMessage,
                ).distinct().joinToString("\n").ifEmpty { null }

                _state.update {
                    it.copy(
                        aboutInfo = (aboutResult as? ApiResult.Success)?.data,
                        users = userNames,
                        isLoading = false,
                        error = errors,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(isLoading = false) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load settings")
                }
            }
        }
    }

    fun switchToServer(serverId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isSwitching = true) }
            val config = settingsRepository.switchToServer(serverId)
            if (config != null) {
                repository.configure(config)
                _switchEvent.value = serverId
            }
            _state.update { it.copy(isSwitching = false) }
        }
    }

    fun consumeSwitchEvent() {
        _switchEvent.value = null
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            settingsRepository.deleteServer(serverId)
        }
    }
}
