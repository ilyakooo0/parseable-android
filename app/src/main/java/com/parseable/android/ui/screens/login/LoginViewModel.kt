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
import kotlinx.coroutines.flow.first
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
    val hasSavedCredentials: Boolean = false,
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
            val config = settingsRepository.serverConfig.first()
            if (config != null) {
                _state.update {
                    it.copy(
                        serverUrl = config.serverUrl,
                        username = config.username,
                        allowInsecure = !config.useTls,
                        hasSavedCredentials = true,
                    )
                }
            }
        }
    }

    fun loginWithSavedCredentials() {
        viewModelScope.launch {
            val config = settingsRepository.getSavedPassword() ?: return@launch
            performLogin(config.password)
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
            performLogin(current.password)
        }
    }

    private suspend fun performLogin(password: String) {
        val current = _state.value
        _state.update { it.copy(isLoading = true, error = null) }

        var url = current.serverUrl.trim()
        val urlLower = url.lowercase()
        if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
            url = if (current.allowInsecure) "http://$url" else "https://$url"
        }

        val isValidUrl = try {
            val uri = java.net.URI(url)
            uri.scheme != null && !uri.host.isNullOrBlank() && !uri.host!!.contains(" ")
        } catch (_: Exception) {
            false
        }
        if (!isValidUrl) {
            _state.update {
                it.copy(
                    isLoading = false,
                    serverUrlError = "Invalid URL format",
                )
            }
            return
        }

        val config = ServerConfig(
            serverUrl = url,
            username = current.username.trim(),
            password = password,
            useTls = url.lowercase().startsWith("https"),
        )

        repository.configure(config)

        when (val result = repository.testConnection()) {
            is ApiResult.Success -> {
                // Verify this is actually a Parseable server and credentials are valid.
                // Uses verifyServer() to avoid triggering the global auth-error handler.
                when (val aboutResult = repository.verifyServer()) {
                    is ApiResult.Success -> {
                        settingsRepository.saveServerConfig(config)
                        settingsRepository.saveServer(config)
                        _state.update { it.copy(isLoading = false, loginSuccess = true, password = "") }
                    }
                    is ApiResult.Error -> {
                        val errorMsg = if (aboutResult.isUnauthorized) {
                            "Invalid credentials. Check your username and password."
                        } else {
                            "Server responded but doesn't appear to be Parseable: ${aboutResult.userMessage}"
                        }
                        _state.update {
                            it.copy(isLoading = false, error = errorMsg)
                        }
                    }
                }
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
