package com.parseable.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import com.parseable.android.data.model.ServerConfig
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.data.repository.SettingsRepository
import com.parseable.android.ui.navigation.ParseableNavGraph
import com.parseable.android.ui.navigation.Routes
import com.parseable.android.ui.theme.ParseableTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        // Check if we have saved credentials
        val savedConfig: ServerConfig? = runBlocking {
            settingsRepository.serverConfig.first()
        }

        if (savedConfig != null) {
            repository.configure(savedConfig)
        }

        val startDestination = if (savedConfig != null) Routes.STREAMS else Routes.LOGIN

        setContent {
            ParseableTheme {
                val navController = rememberNavController()
                ParseableNavGraph(
                    navController = navController,
                    startDestination = startDestination,
                )
            }
        }
    }
}
