package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.SeqRecord
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

    /**
     * 边界输入回归：空 / 不足密码子 / 非法字符 / 纯氨基酸串进入蛋白面板，
     * 逐子 tab 切换（含质谱/免疫 advanced），捕获组合期 NPE、越界、除零或未接线异常。
     * 真机上由导入短片段、空记录、蛋白序列路径进入蛋白面板时最易触发。
     */
    @Test
    fun proteinAdvancedTab_edgeSequences_noCrash() {
        val edgeRecords = listOf(
            SeqRecord("empty", ""),
            SeqRecord("one-base", "A"),
            SeqRecord("two-base", "AT"),
            SeqRecord("four-base", "ATCG"),
            SeqRecord("illegal-chars", "XYZ123"),
            SeqRecord("protein-letters", "MKTAYIAKQRQISFVKSHFSRQLEERL"),
            SeqRecord("stop-codon", "ATGTAA")
        )
        val subtabs = listOf("物化属性", "折叠结构", "功能域", "双向追溯", "质谱/免疫")
        edgeRecords.forEach { record ->
            compose.activity.setContent {
                AnalysisPanelSheet(
                    panel = AnalysisPanel.PROTEIN,
                    record = record,
                    trace = null,
                    onDismiss = {},
                    onRecordChange = {}
                )
            }
            compose.waitForIdle()
            subtabs.forEach { label ->
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
            }
        }
    }
}
