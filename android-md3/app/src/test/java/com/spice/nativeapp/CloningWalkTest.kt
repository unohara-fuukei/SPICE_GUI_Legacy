package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.CloningPanel
import com.spice.nativeapp.ui.CloningPanelSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 克隆设计舱走查：遍历 26 个 CloningPanel，经公共入口 CloningPanelSheet 逐一栅格化，
 * 捕获组合期 NPE/越界/空数据异常。仅验证初始态，交互后路径留待后续轮次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloningWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun cloningPanels_all_renderWithoutCrash() {
        val record = Bio.demoPlasmid()
        CloningPanel.values().forEach { panel ->
            compose.activity.setContent {
                CloningPanelSheet(
                    panel = panel,
                    record = record,
                    onDismiss = {},
                    onRecordChange = {}
                )
            }
            compose.waitForIdle()
        }
    }
}
