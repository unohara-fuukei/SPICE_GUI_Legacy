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

/**
 * 无 KVM 无法跑模拟器：用 Robolectric 在 JVM 上真实渲染 GeneScreen，
 * 点击各 tab（尤其“序列视图”）复现 1.7.9 闪退并固化 stack trace（tab 名已对齐 main i18n 原文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SequenceTabCrashTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun geneTabs_allTabs_noCrash() {
        compose.activity.setContent {
            GeneScreen(
                record = Bio.demoPlasmid(),
                selectedEnzymes = setOf("EcoRI", "BamHI", "HindIII"),
                onToggleEnzyme = {},
                onRecordChange = {},
                onSaveProject = {},
                onOpenSettings = {},
                onAiOpenChange = {}
            )
        }
        compose.waitForIdle()
        // 逐个 tab 点击，任何一个内部异常都会让本测试失败并给出 stack trace
        listOf("环状图谱", "线性图谱", "序列视图", "特征列表", "质粒属性", "科研备注").forEach { label ->
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
        }
    }
}
