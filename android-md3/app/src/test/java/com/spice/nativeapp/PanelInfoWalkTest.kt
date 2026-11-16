package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.ui.BatchPanel
import com.spice.nativeapp.ui.EnzymeLibraryInfo
import com.spice.nativeapp.ui.ModelSettingsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 纯展示/无输入框设置小节与蛋白批量面板初始态栅格化走查。
 * 捕获纯展示 Card 取值越界、ModelStore 状态读取异常、空突变库占位分支；
 * 下载/删除动作仅在点击后的 IO 协程内触发，初始组合期不联网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelInfoWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun enzymeLibraryInfo_rendersWithoutCrash() {
        compose.activity.setContent { EnzymeLibraryInfo() }
        compose.waitForIdle()
    }

    @Test
    fun batchPanel_emptyResults_rendersWithoutCrash() {
        compose.activity.setContent { BatchPanel(emptyList()) }
        compose.waitForIdle()
    }

    @Test
    fun modelSettingsSection_initialState_rendersWithoutCrash() {
        compose.activity.setContent { ModelSettingsSection() }
        compose.waitForIdle()
    }
}
