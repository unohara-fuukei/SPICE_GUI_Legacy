package com.spice.nativeapp.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// SPICE 视觉：深色实验室仪器风格 + MD3 动态色彩层
private val SpiceDark = darkColorScheme(
    primary = Color(0xFF7FE7D6),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF9DFFEE),
    secondary = Color(0xFFB6C4D9),
    onSecondary = Color(0xFF203243),
    secondaryContainer = Color(0xFF364A5E),
    onSecondaryContainer = Color(0xFFD2E2F8),
    tertiary = Color(0xFFFFC56E),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B0F17),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFFA9BACD),
    outline = Color(0xFF3A4657),
    error = Color(0xFFFF8A80)
)

private val SpiceLight = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF37474F),
    tertiary = Color(0xFF8D6E63),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EDF3)
)

@Composable
fun SpiceTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (dark) SpiceDark else SpiceLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
