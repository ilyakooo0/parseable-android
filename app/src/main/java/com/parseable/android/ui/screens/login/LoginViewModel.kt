package com.parseable.android.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parseable.android.data.model.ApiResult
import com.parseable.android.data.model.ServerConfig
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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

    /**
     * Emitted exactly once when login succeeds so the screen navigates away. A one-shot [Channel]
     * (like the codebase's other nav events) rather than a retained StateFlow flag: a retained
     * `loginSuccess = true` would re-fire navigation on any later recomposition that re-collects
     * the state if this ViewModel instance outlived the first navigation.
     */
    private val _loginSuccessEvent = Channel<Unit>(Channel.BUFFERED)
    val loginSuccessEvent = _loginSuccessEvent.receiveAsFlow()

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
        // Guard against a second tap (or a concurrent onLogin()) launching a duplicate
        // login. Set isLoading synchronously so the guard is effective before the
        // coroutine starts.
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val config = settingsRepository.getSavedPassword()
            if (config == null) {
                // URL/username are present but the saved password can't be read (e.g. the
                // Keystore entry was lost). Surface this instead of silently stopping the
                // spinner, and hide the now-useless "use saved credentials" button.
                _state.update {
                    it.copy(
                        isLoading = false,
                        hasSavedCredentials = false,
                        error = "Saved credentials could not be read. Please log in again.",
                    )
                }
                return@launch
            }
            // Restore the saved URL/username so the saved password is paired with the
            // server/user it was saved for, even if the user edited those fields.
            _state.update {
                it.copy(
                    serverUrl = config.serverUrl,
                    username = config.username,
                    allowInsecure = !config.useTls,
                    serverUrlError = null,
                    usernameError = null,
                    passwordError = null,
                )
            }
            performLogin(config.serverUrl, config.username, config.password, !config.useTls)
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

        // With saved credentials the password field is intentionally left empty (the user is
        // expected to tap "Use saved credentials"). Tapping the primary Connect button in that
        // state should do the obvious thing — log in with the saved password — rather than
        // reject with "Password is required".
        if (current.password.isBlank() && current.hasSavedCredentials) {
            loginWithSavedCredentials()
            return
        }

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

        // Guard against a duplicate in-flight login and set isLoading synchronously so a
        // fast second tap can't launch a concurrent performLogin that races on _state.
        if (current.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        // Pass every field from the same snapshot so the validated password can't be
        // paired with a URL/username the user edited after tapping.
        viewModelScope.launch {
            performLogin(current.serverUrl, current.username, current.password, current.allowInsecure)
        }
    }

    private suspend fun performLogin(
        serverUrl: String,
        username: String,
        password: String,
        allowInsecure: Boolean,
    ) {
        _state.update { it.copy(isLoading = true, error = null) }

        // Strip trailing slashes so the persisted URL matches the canonical form the API
        // client uses (ParseableApiClient.configure also does trimEnd('/')). Without this,
        // "http://host:8000" and "http://host:8000/" persist as distinct strings, defeating
        // saveServer's (serverUrl, username) dedup and the UNIQUE index — the same server
        // ends up as two saved-server rows.
        var url = serverUrl.trim().trimEnd('/')
        val urlLower = url.lowercase()
        if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
            url = if (allowInsecure) "http://$url" else "https://$url"
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
            username = username.trim(),
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
                        // Only report success if the credentials were durably persisted.
                        // Otherwise the app would navigate in and clear the typed password,
                        // yet bounce back to login on next launch with nothing to restore.
                        if (settingsRepository.saveServerConfig(config)) {
                            settingsRepository.saveServer(config)
                            _state.update { it.copy(isLoading = false, password = "") }
                            _loginSuccessEvent.send(Unit)
                        } else {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Connected, but your credentials couldn't be saved securely on this device. Please try again.",
                                )
                            }
                        }
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
