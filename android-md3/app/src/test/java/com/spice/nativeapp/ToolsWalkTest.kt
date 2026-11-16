package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.tools.AiScreen
import com.spice.nativeapp.tools.AnnotateScreen
import com.spice.nativeapp.tools.BlotScreen
import com.spice.nativeapp.tools.ColonyScreen
import com.spice.nativeapp.tools.FileScreen
import com.spice.nativeapp.tools.FigureScreen
import com.spice.nativeapp.tools.GelScreen
import com.spice.nativeapp.tools.PhotoScreen
import com.spice.nativeapp.tools.PlateScreen
import com.spice.nativeapp.tools.QpcrScreen
import com.spice.nativeapp.tools.ReportScreen
import com.spice.nativeapp.tools.ToolsScreen
import com.spice.nativeapp.tools.TreeScreen
import com.spice.nativeapp.tools.Viewer3DScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 工具中枢走查：网格页 + 13 个工具子屏逐一直接栅格化，捕获组合期崩溃/空数据异常。
 * 直接调用各子屏 Composable，绕过网格导航文案匹配（i18n 标题可能被词典改写）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolsHub_andSubscreens_renderWithoutCrash() {
        val record = Bio.demoPlasmid()

        // 网格页
        compose.activity.setContent { ToolsScreen(record = record, onImport = {}) }
        compose.waitForIdle()

        // 各子屏
        compose.activity.setContent { GelScreen(record) }
        compose.waitForIdle()

        compose.activity.setContent { QpcrScreen() }
        compose.waitForIdle()

        compose.activity.setContent { BlotScreen() }
        compose.waitForIdle()

        compose.activity.setContent { PlateScreen() }
        compose.waitForIdle()

        compose.activity.setContent { TreeScreen() }
        compose.waitForIdle()

        compose.activity.setContent { Viewer3DScreen() }
        compose.waitForIdle()

        compose.activity.setContent { PhotoScreen() }
        compose.waitForIdle()

        compose.activity.setContent { ReportScreen(record) }
        compose.waitForIdle()

        compose.activity.setContent { FileScreen(record, {}) }
        compose.waitForIdle()

        compose.activity.setContent { AiScreen(record) }
        compose.waitForIdle()

        compose.activity.setContent { FigureScreen() }
        compose.waitForIdle()

        compose.activity.setContent { AnnotateScreen() }
        compose.waitForIdle()

        compose.activity.setContent { ColonyScreen() }
        compose.waitForIdle()
    }
}
