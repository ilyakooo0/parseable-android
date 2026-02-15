package com.parseable.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/**
 * Central error handler that any screen can use to display error messages
 * via a global Snackbar. Access it through [LocalErrorHandler].
 */
class ErrorHandler {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun showError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }
}

/**
 * CompositionLocal providing access to the global [ErrorHandler].
 * Any composable in the tree can call `LocalErrorHandler.current.showError(...)`.
 */
val LocalErrorHandler = compositionLocalOf<ErrorHandler> {
    error("No ErrorHandler provided. Wrap your content in GlobalErrorBoundary.")
}

/**
 * Wraps [content] in a Scaffold with a SnackbarHost that reacts to errors
 * posted via the provided [ErrorHandler]. The handler is exposed to the
 * entire subtree through [LocalErrorHandler].
 */
@Composable
fun GlobalErrorBoundary(
    errorHandler: ErrorHandler = remember { ErrorHandler() },
    content: @Composable () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect errors and show them as Snackbars
    LaunchedEffect(errorHandler) {
        errorHandler.error
            .filterNotNull()
            .collectLatest { message ->
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short,
                )
                errorHandler.clearError()
            }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalErrorHandler provides errorHandler,
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                content()
            }
        }
    }
}
