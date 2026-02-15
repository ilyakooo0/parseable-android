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
    val isLoading: Boolean = false,
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
            _state.update { it.copy(isLoading = true) }

            val config = settingsRepository.serverConfig.first()

            // Show server info immediately
            _state.update {
                it.copy(
                    serverUrl = config?.serverUrl ?: it.serverUrl,
                    username = config?.username ?: it.username,
                    useTls = config?.useTls ?: it.useTls,
                )
            }

            val aboutDeferred = async { repository.getAbout() }
            val usersDeferred = async { repository.listUsers() }

            val aboutResult = aboutDeferred.await()
            val usersResult = usersDeferred.await()

            val userNames = (usersResult as? ApiResult.Success)?.data?.mapNotNull { obj ->
                obj["id"]?.jsonPrimitive?.content
                    ?: obj["username"]?.jsonPrimitive?.content
            } ?: emptyList()

            _state.update {
                it.copy(
                    aboutInfo = (aboutResult as? ApiResult.Success)?.data ?: it.aboutInfo,
                    users = userNames.ifEmpty { it.users },
                    isLoading = false,
                )
            }
        }
    }
}
