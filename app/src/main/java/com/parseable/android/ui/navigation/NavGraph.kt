package com.parseable.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.compose.runtime.LaunchedEffect
import com.parseable.android.data.repository.ParseableRepository
import com.parseable.android.ui.screens.alerts.AlertsScreen
import com.parseable.android.ui.screens.login.LoginScreen
import com.parseable.android.ui.screens.logviewer.LogViewerScreen
import com.parseable.android.ui.screens.logviewer.StreamInfoScreen
import com.parseable.android.ui.screens.settings.SettingsScreen
import com.parseable.android.ui.screens.streams.StreamsScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val LOGIN = "login"
    const val STREAMS = "streams"
    const val LOG_VIEWER = "log_viewer/{streamName}"
    const val STREAM_INFO = "stream_info/{streamName}"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"

    fun login(sessionExpired: Boolean = false) =
        if (sessionExpired) "login?sessionExpired=true" else LOGIN
    fun logViewer(streamName: String) =
        "log_viewer/${URLEncoder.encode(streamName, "UTF-8")}"
    fun streamInfo(streamName: String) =
        "stream_info/${URLEncoder.encode(streamName, "UTF-8")}"
}

@Composable
fun ParseableNavGraph(
    navController: NavHostController,
    startDestination: String,
    repository: ParseableRepository,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(
            route = "${Routes.LOGIN}?sessionExpired={sessionExpired}",
            arguments = listOf(navArgument("sessionExpired") {
                type = NavType.BoolType
                defaultValue = false
            }),
        ) { backStackEntry ->
            val sessionExpired = backStackEntry.arguments?.getBoolean("sessionExpired") ?: false
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.STREAMS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                sessionExpired = sessionExpired,
            )
        }

        composable(
            route = Routes.STREAMS,
            deepLinks = listOf(navDeepLink { uriPattern = "parseable://streams" }),
        ) {
            if (!repository.isConfigured) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                return@composable
            }
            StreamsScreen(
                onStreamClick = { streamName ->
                    navController.navigate(Routes.logViewer(streamName)) {
                        launchSingleTop = true
                    }
                },
                onAlertsClick = {
                    navController.navigate(Routes.ALERTS) {
                        launchSingleTop = true
                    }
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                    }
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
            deepLinks = listOf(navDeepLink { uriPattern = "parseable://stream/{streamName}" }),
        ) { backStackEntry ->
            // Guard: redirect to login if the API client isn't configured
            if (!repository.isConfigured) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                return@composable
            }
            val streamName = URLDecoder.decode(
                backStackEntry.arguments?.getString("streamName") ?: "", "UTF-8"
            )
            LogViewerScreen(
                streamName = streamName,
                onBack = { navController.popBackStack() },
                onStreamInfo = { name ->
                    navController.navigate(Routes.streamInfo(name)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Routes.STREAM_INFO,
            arguments = listOf(navArgument("streamName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val streamName = URLDecoder.decode(
                backStackEntry.arguments?.getString("streamName") ?: "", "UTF-8"
            )
            StreamInfoScreen(
                streamName = streamName,
                onBack = { navController.popBackStack() },
                onStreamDeleted = {
                    navController.popBackStack(Routes.STREAMS, inclusive = false)
                },
            )
        }

        composable(
            route = Routes.ALERTS,
            deepLinks = listOf(navDeepLink { uriPattern = "parseable://alerts" }),
        ) {
            if (!repository.isConfigured) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                return@composable
            }
            AlertsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            if (!repository.isConfigured) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                return@composable
            }
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
