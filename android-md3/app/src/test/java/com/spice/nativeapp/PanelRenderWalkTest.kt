package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * 未覆盖分析面板渲染走查：RNA / Sanger / Validator / Genomics / Synbio /
 * Blast / Alignment / GC / ImportAlign 逐一栅格化，捕获组合期 NPE、越界、未接线异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelRenderWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun allAnalysisPanels_renderWithoutCrash() {
        val panels = listOf(
            AnalysisPanel.RNA,
            AnalysisPanel.SANGER,
            AnalysisPanel.VALIDATOR,
            AnalysisPanel.GENOMICS,
            AnalysisPanel.SYNBIO,
            AnalysisPanel.BLAST,
            AnalysisPanel.ALIGNMENT,
            AnalysisPanel.GC,
            AnalysisPanel.IMPORT_ALIGN
        )
        panels.forEach { panel ->
            compose.activity.setContent {
                AnalysisPanelSheet(
                    panel = panel,
                    record = Bio.demoPlasmid(),
                    trace = null,
                    onDismiss = {},
                    onRecordChange = {}
                )
            }
            compose.waitForIdle()
        }
    }
}
