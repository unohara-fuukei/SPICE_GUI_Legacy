package com.spice.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────────
// SPICE 视觉：深色实验室仪器风格（品牌主题）
// 色板严格按 Material 3 规范补全全部色彩角色：
// primary/secondary/tertiary/error 四色 + 各自 on/container，
// surface 全家族（surface/surfaceVariant/surfaceContainer 低→高/surfaceDim/surfaceBright），
// inverse 系列、outline/outlineVariant、scrim、surfaceTint——
// 不再依赖 lightColorScheme/darkColorScheme 的 baseline 默认值（避免紫色 tint 泄露）。
// ─────────────────────────────────────────────────────────────────

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
    error = Color(0xFFFF8A80),
    onError = Color(0xFF530005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9B8B3),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B0F17),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFFA9BACD),
    // MD3 surface 层级（无影卡面）
    surfaceDim = Color(0xFF0B0F17),
    surfaceBright = Color(0xFF21262F),
    surfaceContainerLowest = Color(0xFF060909),
    surfaceContainerLow = Color(0xFF131722),
    surfaceContainer = Color(0xFF171B24),
    surfaceContainerHigh = Color(0xFF21262F),
    surfaceContainerHighest = Color(0xFF2B3039),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF0B0F17),
    inversePrimary = Color(0xFF00695C),
    outline = Color(0xFF3A4657),
    outlineVariant = Color(0xFF4A5567),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF7FE7D6)
)

private val SpiceLight = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF2E0),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF37474F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4EA),
    onSecondaryContainer = Color(0xFF0A1D24),
    tertiary = Color(0xFF8D6E63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC5),
    onTertiaryContainer = Color(0xFF332016),
    error = Color(0xFF904A43),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3B0907),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF3F4946),
    // MD3 surface 层级（无影卡面）
    surfaceDim = Color(0xFFD8DBD9),
    surfaceBright = Color(0xFFF7F9F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F0),
    surfaceContainer = Color(0xFFE9ECE9),
    surfaceContainerHigh = Color(0xFFE2E5E1),
    surfaceContainerHighest = Color(0xFFDCE0DC),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEFF1EC),
    inversePrimary = Color(0xFF80D5C5),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C4),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF00695C)
)

/** 安全取 Activity：逐层解包 ContextWrapper，避免非 Activity 上下文强转崩溃 */
private tailrec fun Context.findActivity(): Activity? {
    if (this is Activity) return this
    val base = (this as? ContextWrapper)?.baseContext ?: return null
    return base.findActivity()
}

@Composable
fun SpiceTheme(
    dark: Boolean = isSystemInDarkTheme(),
    // Android 12+ 动态取色（Material You）：默认关闭以保留 SPICE 实验室仪器品牌视觉
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) SpiceDark else SpiceLight
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                @Suppress("DEPRECATION") // targetSdk 29；API 35 起由 edge-to-edge 接管
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
