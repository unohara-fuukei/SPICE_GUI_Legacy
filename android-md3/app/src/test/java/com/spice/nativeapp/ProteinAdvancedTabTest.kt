package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.AnalysisPanel
import com.spice.nativeapp.ui.AnalysisPanelSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #111 回归：分析与多视图 → 蛋白质翻译与三维视图 → 「质谱/免疫」advanced 子 tab。
 * 渲染 PMF / 二硫键 / 抗原表位 / 局部电荷 pI 四区块，捕获组合期的空指针、越界或未接线异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProteinAdvancedTabTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun proteinAdvancedTab_rendersWithoutCrash() {
        compose.activity.setContent {
            AnalysisPanelSheet(
                panel = AnalysisPanel.PROTEIN,
                record = Bio.demoPlasmid(),
                trace = null,
                onDismiss = {},
                onRecordChange = {}
            )
        }
        compose.waitForIdle()
        // 默认物化属性 tab 先渲染一遍；再切到第 5 个子 tab「质谱/免疫」(index 4, advanced)
        compose.onNodeWithText("质谱/免疫").performClick()
        compose.waitForIdle()
    }
}
