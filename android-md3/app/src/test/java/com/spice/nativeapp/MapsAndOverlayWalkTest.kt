package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.CircularMap
import com.spice.nativeapp.ui.CrashReportOverlay
import com.spice.nativeapp.ui.LinearMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 崩溃报告覆盖层与环形/线性质粒图谱 Canvas 初始态栅格化走查。
 * 图谱传 demo 序列 + 空酶名集合：digest 空命中、空特征分支；
 * PNG 导出/缩放拖动仅在工具栏点击后的协程内触发，初始组合期不写盘、不联网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsAndOverlayWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun crashReportOverlay_rendersWithoutCrash() {
        compose.activity.setContent {
            CrashReportOverlay(report = "demo crash report\nline 2", onDismiss = {})
        }
        compose.waitForIdle()
    }

    @Test
    fun circularMap_emptyEnzymes_rendersWithoutCrash() {
        compose.activity.setContent {
            CircularMap(record = Bio.demoPlasmid(), enzymeNames = emptySet())
        }
        compose.waitForIdle()
    }

    @Test
    fun linearMap_emptyEnzymes_rendersWithoutCrash() {
        compose.activity.setContent {
            LinearMap(record = Bio.demoPlasmid(), enzymeNames = emptySet())
        }
        compose.waitForIdle()
    }
}
