package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.GeneScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 基因页内视图 tab 切换走查：环状图谱 / 线性图谱 / 序列视图 / 特征列表 /
 * 质粒属性 / 科研备注。经公共入口 GeneScreen 逐一切换栅格化，捕获切换期
 * 组合期 NPE、空序列/特征列表越界、pref 读取异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneInnerTabWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun geneInnerTabs_all_renderWithoutCrash() {
        compose.activity.setContent {
            GeneScreen(
                record = Bio.demoPlasmid(),
                selectedEnzymes = emptySet(),
                onToggleEnzyme = {}
            )
        }
        compose.waitForIdle()

        val tabs = listOf(
            "环状图谱", "线性图谱", "序列视图", "特征列表", "质粒属性", "科研备注"
        )
        tabs.forEach { label ->
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
        }
    }
}
