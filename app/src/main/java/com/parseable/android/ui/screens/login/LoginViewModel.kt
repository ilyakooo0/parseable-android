package com.parseable.android.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.ServerConfig
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowInsecure: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false,
    val serverUrlError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: ParseableRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.serverConfig.collect { config ->
                if (config != null) {
                    _state.update {
                        it.copy(
                            serverUrl = config.serverUrl,
                            username = config.username,
                            password = config.password,
                            allowInsecure = !config.useTls,
                        )
                    }
                }
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value, serverUrlError = null, error = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, usernameError = null, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null, error = null) }
    }

    fun onAllowInsecureChange(value: Boolean) {
        _state.update { it.copy(allowInsecure = value) }
    }

    fun onLogin() {
        val current = _state.value

        // Validate
        var hasError = false
        var urlError: String? = null
        var userError: String? = null
        var passError: String? = null

        if (current.serverUrl.isBlank()) {
            urlError = "Server URL is required"
            hasError = true
        }
        if (current.username.isBlank()) {
            userError = "Username is required"
            hasError = true
        }
        if (current.password.isBlank()) {
            passError = "Password is required"
            hasError = true
        }

        if (hasError) {
            _state.update {
                it.copy(
                    serverUrlError = urlError,
                    usernameError = userError,
                    passwordError = passError,
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            var url = current.serverUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (current.allowInsecure) "http://$url" else "https://$url"
            }

            val config = ServerConfig(
                serverUrl = url,
                username = current.username.trim(),
                password = current.password,
                useTls = url.startsWith("https"),
            )

            repository.configure(config)

            when (val result = repository.testConnection()) {
                is ApiResult.Success -> {
                    settingsRepository.saveServerConfig(config)
                    _state.update { it.copy(isLoading = false, loginSuccess = true) }
                }
                is ApiResult.Error -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = "Connection failed: ${result.userMessage}",
                        )
                    }
                }
            }
        }
    }
}
