package com.parseable.android.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.parseable.android.R
import com.parseable.android.ui.LocalErrorHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    sessionExpired: Boolean = false,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val errorHandler = LocalErrorHandler.current

    LaunchedEffect(state.loginSuccess) {
        if (state.loginSuccess) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            errorHandler.showError("Session expired. Please log in again.")
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "Parseable logo",
                modifier = Modifier.size(120.dp),
            )

            Text(
                text = "Parseable",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Log Viewer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://demo.parseable.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                isError = state.serverUrlError != null,
                supportingText = state.serverUrlError?.let { { Text(it) } },
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                isError = state.usernameError != null,
                supportingText = state.usernameError?.let { { Text(it) } },
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.onLogin()
                    },
                ),
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.allowInsecure,
                    onCheckedChange = viewModel::onAllowInsecureChange,
                )
                Text(
                    text = "Allow insecure (HTTP) connections",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.allowInsecure) {
                val isPrivateNetwork = remember(state.serverUrl) {
                    isPrivateHost(extractHost(state.serverUrl))
                }
                if (!isPrivateNetwork) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Warning: HTTP sends credentials and data in plaintext. " +
                                "Only use this for trusted private networks.",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 50.dp),
                enabled = !state.isLoading,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Connect")
            }

            if (state.hasSavedCredentials && state.password.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::loginWithSavedCredentials,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 50.dp),
                    enabled = !state.isLoading,
                ) {
                    Text("Use saved credentials")
                }
            }

            state.error?.let { errorText ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorText,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Extract the bare host from a possibly-schemeless, possibly-ported URL, handling
 * IPv6 literals (`[::1]:8000`) and bare IPv6 addresses correctly.
 */
internal fun extractHost(url: String): String {
    // Drop the scheme if present, then the path.
    var hostPort = url.substringAfter("://").substringBefore("/")
    return if (hostPort.startsWith("[")) {
        // Bracketed IPv6 literal, e.g. "[::1]:8000" -> "::1"
        hostPort.substringAfter("[").substringBefore("]")
    } else {
        // Strip ":port" only when there's a single colon (host:port). A bare IPv6
        // address has multiple colons and no brackets, so leave it intact.
        if (hostPort.count { it == ':' } == 1) hostPort.substringBefore(":") else hostPort
    }
}

/**
 * Whether [host] is a loopback/private-range address. Used to decide whether the
 * plaintext-HTTP warning is needed. Matches actual IPv4 octet ranges (not string
 * prefixes, so "10.example.com" is correctly treated as public) and IPv6
 * loopback/link-local/unique-local addresses.
 */
internal fun isPrivateHost(host: String): Boolean {
    if (host.isBlank()) return true
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".localhost")) return true

    // IPv6 ranges (only meaningful for actual IPv6 literals, which contain ':').
    if (h.contains(":")) {
        if (h == "::1") return true // loopback
        if (h.startsWith("fe80:")) return true // link-local fe80::/10
        if (h.startsWith("fc") || h.startsWith("fd")) return true // unique-local fc00::/7
        return false
    }

    // IPv4: require four numeric octets in 0..255, then match private ranges.
    val octets = h.split(".")
    if (octets.size == 4 && octets.all { val n = it.toIntOrNull(); n != null && n in 0..255 }) {
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return when {
            a == 10 -> true            // 10.0.0.0/8
            a == 127 -> true           // loopback 127.0.0.0/8
            a == 192 && b == 168 -> true // 192.168.0.0/16
            a == 172 && b in 16..31 -> true // 172.16.0.0/12
            else -> false
        }
    }
    return false
}
