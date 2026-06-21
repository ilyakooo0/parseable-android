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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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

    /**
     * Emits the server ID after a successful switch so the screen can navigate. A [Channel]
     * (matching the codebase's other one-shot events) so the navigation fires exactly once and
     * is not re-delivered on rotation — a StateFlow retains its last value and would re-navigate
     * when the screen re-collects after a config change.
     */
    private val _switchEvent = Channel<Long>(Channel.BUFFERED)
    val switchEvent = _switchEvent.receiveAsFlow()

    private var loadJob: Job? = null

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
        // Cancel any in-flight load so a slower completion can't overwrite a newer one.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                        obj["username"]?.jsonPrimitive?.content
                            ?: obj["id"]?.jsonPrimitive?.content
                    } catch (_: IllegalStateException) {
                        null
                    }
                } ?: emptyList()

                // Only treat a failure of the primary server-info call (/about) as a blocking
                // error. The user list (/user) is secondary and is commonly forbidden for
                // restricted accounts — failing it shouldn't hide otherwise-valid server info.
                val error = (aboutResult as? ApiResult.Error)?.userMessage

                _state.update {
                    it.copy(
                        aboutInfo = (aboutResult as? ApiResult.Success)?.data,
                        users = userNames,
                        isLoading = false,
                        error = error,
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
                _switchEvent.send(serverId)
            }
            _state.update { it.copy(isSwitching = false) }
        }
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            settingsRepository.deleteServer(serverId)
        }
    }
}
