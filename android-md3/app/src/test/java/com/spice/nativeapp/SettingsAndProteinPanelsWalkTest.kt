package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.KeymapPanel
import com.spice.nativeapp.ui.DatabaseSyncPanel
import com.spice.nativeapp.ui.MutationCartPanel
import com.spice.nativeapp.ui.PhasePanel
import com.spice.nativeapp.ui.PocketPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页散面板 + 蛋白空态子面板初始态栅格化走查。
 * 捕获空列表/空结果取值越界、pref 读取异常、面板组合期 NPE；回调置空。
 * FoldPanel / MdPanel 依赖完整折叠/MD 输出对象，本轮不构造，留待算法链路走查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAndProteinPanelsWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun databaseSyncPanel_initialState_rendersWithoutCrash() {
        compose.activity.setContent { DatabaseSyncPanel() }
        compose.waitForIdle()
    }

    @Test
    fun keymapPanel_initialState_rendersWithoutCrash() {
        compose.activity.setContent { KeymapPanel() }
        compose.waitForIdle()
    }

    @Test
    fun pocketPanel_empty_rendersWithoutCrash() {
        compose.activity.setContent { PocketPanel(emptyList()) }
        compose.waitForIdle()
    }

    @Test
    fun mutationCartPanel_empty_rendersWithoutCrash() {
        compose.activity.setContent {
            MutationCartPanel(
                cart = emptyList(),
                sequence = Bio.demoPlasmid().sequence,
                onRemove = {},
                onApply = {},
                onClear = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun phasePanel_emptyPoints_rendersWithoutCrash() {
        compose.activity.setContent { PhasePanel(emptyList(), null) }
        compose.waitForIdle()
    }
}
