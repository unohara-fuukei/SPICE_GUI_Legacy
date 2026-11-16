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
 * 蛋白翻译与三维视图五子 tab 切换走查：物化属性 / 折叠结构 / 功能域 / 双向追溯 /
 * 质谱-免疫。经公共入口 AnalysisPanelSheet(PROTEIN) 逐一切换栅格化，捕获切换期
 * 组合期 NPE/越界/空数据异常（#111 质谱-免疫 tab 已由 ProteinAdvancedTabTest 回归）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProteinSubtabWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun proteinSubtabs_all_renderWithoutCrash() {
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

        val subtabs = listOf(
            "物化属性", "折叠结构", "功能域", "双向追溯", "质谱/免疫"
        )
        subtabs.forEach { label ->
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
        }
    }
}
