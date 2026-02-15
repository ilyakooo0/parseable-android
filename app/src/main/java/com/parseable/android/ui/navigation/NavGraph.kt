package com.parseable.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.parseable.android.ui.screens.alerts.AlertsScreen
import com.parseable.android.ui.screens.login.LoginScreen
import com.parseable.android.ui.screens.logviewer.LogViewerScreen
import com.parseable.android.ui.screens.logviewer.StreamInfoScreen
import com.parseable.android.ui.screens.settings.SettingsScreen
import com.parseable.android.ui.screens.streams.StreamsScreen

object Routes {
    const val LOGIN = "login"
    const val STREAMS = "streams"
    const val LOG_VIEWER = "log_viewer/{streamName}"
    const val STREAM_INFO = "stream_info/{streamName}"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"

    fun logViewer(streamName: String) = "log_viewer/$streamName"
    fun streamInfo(streamName: String) = "stream_info/$streamName"
}

@Composable
fun ParseableNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.STREAMS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.STREAMS) {
            StreamsScreen(
                onStreamClick = { streamName ->
                    navController.navigate(Routes.logViewer(streamName))
                },
                onAlertsClick = {
                    navController.navigate(Routes.ALERTS)
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.LOG_VIEWER,
            arguments = listOf(navArgument("streamName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val streamName = backStackEntry.arguments?.getString("streamName") ?: ""
            LogViewerScreen(
                streamName = streamName,
                onBack = { navController.popBackStack() },
                onStreamInfo = { name ->
                    navController.navigate(Routes.streamInfo(name))
                },
            )
        }

        composable(
            route = Routes.STREAM_INFO,
            arguments = listOf(navArgument("streamName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val streamName = backStackEntry.arguments?.getString("streamName") ?: ""
            StreamInfoScreen(
                streamName = streamName,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ALERTS) {
            AlertsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
