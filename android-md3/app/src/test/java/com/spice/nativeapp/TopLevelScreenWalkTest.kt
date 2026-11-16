package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.CloningScreen
import com.spice.nativeapp.ui.DemoScreen
import com.spice.nativeapp.ui.ProteinScreen
import com.spice.nativeapp.ui.WelcomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 顶层主屏初始态栅格化走查：克隆舱主屏、独立蛋白页、欢迎页、演示页。
 * 捕获 remember 计算、core 取用、Canvas 组合期异常；回调均置空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopLevelScreenWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cloningScreen_demoRecord_rendersWithoutCrash() {
        compose.activity.setContent {
            CloningScreen(record = Bio.demoPlasmid())
        }
        compose.waitForIdle()
    }

    @Test
    fun proteinScreen_defaultSequence_rendersWithoutCrash() {
        compose.activity.setContent {
            ProteinScreen()
        }
        compose.waitForIdle()
    }

    @Test
    fun welcomeScreen_rendersWithoutCrash() {
        compose.activity.setContent {
            WelcomeScreen(
                versionLabel = "1.7.58 · native",
                onOpenGene = {},
                onOpenProtein = {},
                onOpenProjects = {},
                onOpenTools = {},
                onOpenSettings = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun demoScreen_demoRecord_rendersWithoutCrash() {
        compose.activity.setContent {
            DemoScreen(record = Bio.demoPlasmid(), onExit = {})
        }
        compose.waitForIdle()
    }
}
