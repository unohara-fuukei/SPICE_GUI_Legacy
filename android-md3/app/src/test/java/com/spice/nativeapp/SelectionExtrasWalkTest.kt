package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.SelectionPropertiesCard
import com.spice.nativeapp.ui.SelectionToolkitBar
import com.spice.nativeapp.ui.SequenceToolRail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 序列选区辅助组件初始态栅格化走查：工具条 / 竖工具轨 / 选区属性卡。
 * 用 demo 序列 + 合法 1..10 选区；substring 已 coerceIn/coerceAtMost 兜底，
 * 捕获空序列/越界取值、GC/Tm 计算异常；回调置空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionExtrasWalkTest {

    private val dna get() = Bio.demoPlasmid().sequence
    private val sel get() = 1..10

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectionToolkitBar_rendersWithoutCrash() {
        compose.activity.setContent {
            SelectionToolkitBar(
                sel = sel,
                dna = dna,
                onCopy = {},
                onCopyImage = {},
                onRevComp = {},
                onDelete = {},
                onCancel = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun sequenceToolRail_rendersWithoutCrash() {
        compose.activity.setContent {
            SequenceToolRail(
                seqTool = "browse",
                onSeqTool = {},
                showEnzymes = false,
                showFeatures = false,
                showTranslations = false,
                onToggleEnzymes = {},
                onToggleFeatures = {},
                onToggleTranslations = {},
                onOpenHybridization = {},
                onAddNote = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun selectionPropertiesCard_rendersWithoutCrash() {
        compose.activity.setContent {
            SelectionPropertiesCard(
                sel = sel,
                dna = dna,
                onAddNote = {},
                onCopy = {},
                onDismiss = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun selectionPropertiesCard_wideRange_fullWidthLayoutWithoutCrash() {
        // 大区间 + fillMaxWidth 挂载：属性行/按钮单行不截断，纵向压缩后栅格化不崩。
        compose.activity.setContent {
            SelectionPropertiesCard(
                sel = 1..dna.length,
                dna = dna,
                onAddNote = {},
                onCopy = {},
                onDismiss = {}
            )
        }
        compose.waitForIdle()
    }
}
