package com.parseable.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.parseable.android.data.model.ServerConfig
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import com.parseable.android.ui.ErrorHandler
import com.parseable.android.ui.GlobalErrorBoundary
import com.parseable.android.ui.navigation.ParseableNavGraph
import com.parseable.android.ui.navigation.Routes
import com.parseable.android.ui.theme.ParseableTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var repository: ParseableRepository

    private val errorHandler = ErrorHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ParseableTheme {
                GlobalErrorBoundary(errorHandler = errorHandler) {
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        val savedConfig: ServerConfig? = settingsRepository.serverConfig.first()
                        if (savedConfig != null) {
                            repository.configure(savedConfig)
                        }
                        startDestination = if (savedConfig != null) Routes.STREAMS else Routes.LOGIN
                    }

                    val dest = startDestination
                    if (dest != null) {
                        val navController = rememberNavController()

                        // Navigate to login on 401 auth errors
                        LaunchedEffect(navController) {
                            repository.authErrors
                                .onEach {
                                    // Several parallel requests can each surface a 401 in quick
                                    // succession. Skip re-navigating (and re-clearing) when we're
                                    // already on the login screen, so a burst can't thrash the
                                    // back stack or re-trigger the "session expired" message.
                                    val onLogin = navController.currentDestination
                                        ?.route?.startsWith(Routes.LOGIN) == true
                                    if (onLogin) return@onEach
                                    settingsRepository.clearConfig()
                                    // The login screen shows the "session expired" message
                                    // itself via the sessionExpired flag; showing it here too
                                    // would queue a duplicate snackbar.
                                    navController.navigate(Routes.login(sessionExpired = true)) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                                .launchIn(this)
                        }

                        ParseableNavGraph(
                            navController = navController,
                            startDestination = dest,
                            repository = repository,
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
