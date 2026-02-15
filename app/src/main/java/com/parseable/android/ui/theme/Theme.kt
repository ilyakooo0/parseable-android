package com.parseable.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ParseablePrimary = Color(0xFF545BEB)
private val ParseableOnPrimary = Color(0xFFFFFFFF)
private val ParseableSecondary = Color(0xFF3D43B0)
private val ParseableTertiary = Color(0xFF7C82FF)

private val DarkColorScheme = darkColorScheme(
    primary = ParseableTertiary,
    onPrimary = Color.White,
    secondary = ParseableSecondary,
    tertiary = ParseablePrimary,
    background = Color(0xFF0F1117),
    surface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFF25262F),
    onBackground = Color(0xFFE3E3E8),
    onSurface = Color(0xFFE3E3E8),
    onSurfaceVariant = Color(0xFFA0A1A8),
    outline = Color(0xFF3A3B44),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = ParseablePrimary,
    onPrimary = Color.White,
    secondary = ParseableSecondary,
    tertiary = ParseableTertiary,
    background = Color(0xFFF8F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F6),
    onBackground = Color(0xFF1A1B23),
    onSurface = Color(0xFF1A1B23),
    onSurfaceVariant = Color(0xFF5A5B64),
    outline = Color(0xFFD0D0D8),
    error = Color(0xFFD32F2F),
    onError = Color.White,
)

@Composable
fun ParseableTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
