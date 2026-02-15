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
import com.parseable.android.ui.navigation.ParseableNavGraph
import com.parseable.android.ui.navigation.Routes
import com.parseable.android.ui.theme.ParseableTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var repository: ParseableRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ParseableTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val savedConfig: ServerConfig? = settingsRepository.serverConfig.first()
                    if (savedConfig != null) {
                        repository.configure(savedConfig)
                    }
                    startDestination = if (savedConfig != null) Routes.STREAMS else Routes.LOGIN
                }

                if (startDestination != null) {
                    val navController = rememberNavController()
                    ParseableNavGraph(
                        navController = navController,
                        startDestination = startDestination!!,
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
