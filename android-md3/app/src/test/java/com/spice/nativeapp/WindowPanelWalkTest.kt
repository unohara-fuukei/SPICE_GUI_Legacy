package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.ui.WindowPanel
import com.spice.nativeapp.ui.WindowPanelSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 浮动窗口面板四枚举初始态栅格化走查：历史树 / CRT 日志 / 设置入口 / 重置入口。
 * 空撤销栈、空事件日志；捕获空列表取值越界、FloatingPanel 组合期 NPE；回调置空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WindowPanelWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun historyPanel_emptyUndoStack_rendersWithoutCrash() {
        compose.activity.setContent {
            WindowPanelSheet(
                panel = WindowPanel.HISTORY,
                undoStack = emptyList(),
                log = emptyList(),
                onDismiss = {},
                onRestore = {},
                onOpenSettings = {},
                onResetLayout = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun loggerPanel_emptyLog_rendersWithoutCrash() {
        compose.activity.setContent {
            WindowPanelSheet(
                panel = WindowPanel.LOGGER,
                undoStack = emptyList(),
                log = emptyList(),
                onDismiss = {},
                onRestore = {},
                onOpenSettings = {},
                onResetLayout = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsPanel_entry_rendersWithoutCrash() {
        compose.activity.setContent {
            WindowPanelSheet(
                panel = WindowPanel.SETTINGS,
                undoStack = emptyList(),
                log = emptyList(),
                onDismiss = {},
                onRestore = {},
                onOpenSettings = {},
                onResetLayout = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun resetPanel_entry_rendersWithoutCrash() {
        compose.activity.setContent {
            WindowPanelSheet(
                panel = WindowPanel.RESET,
                undoStack = emptyList(),
                log = emptyList(),
                onDismiss = {},
                onRestore = {},
                onOpenSettings = {},
                onResetLayout = {}
            )
        }
        compose.waitForIdle()
    }
}
