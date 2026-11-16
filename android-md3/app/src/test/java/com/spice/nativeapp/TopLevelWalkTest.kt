package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.ProjectScreen
import com.spice.nativeapp.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 顶层页渲染走查：项目集合（空项目态）、设置页各 tab/控件，捕获组合期异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopLevelWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun projectScreen_empty_rendersWithoutCrash() {
        compose.activity.setContent {
            ProjectScreen(
                projects = emptyList(),
                current = Bio.demoPlasmid(),
                onSaveCurrent = {},
                onOpen = {},
                onDelete = {},
                onUpdate = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsScreen_rendersWithoutCrash() {
        compose.activity.setContent {
            SettingsScreen(
                darkTheme = false,
                onDarkThemeChange = {},
                amoled = false,
                onAmoledChange = {},
                autoRotate = false,
                onAutoRotateChange = {},
                demoMode = false,
                onDemoModeChange = {}
            )
        }
        compose.waitForIdle()
    }
}
