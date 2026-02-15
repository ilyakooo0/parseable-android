package com.parseable.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Central error handler that any screen can use to display error messages
 * via a global Snackbar. Access it through [LocalErrorHandler].
 *
 * Uses a [Channel] so that rapid-fire calls to [showError] queue messages
 * rather than overwriting each other (which happened with StateFlow).
 */
class ErrorHandler {
    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    fun showError(message: String) {
        _errors.trySend(message)
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
        errorHandler.errors.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
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
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                content()
            }
        }
    }
}
