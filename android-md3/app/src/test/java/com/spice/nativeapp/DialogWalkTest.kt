package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.tools.FigureCheckDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 对话框初始态栅格化走查（无输入框项）：投稿检查。
 * 捕获空列表/空结果取值越界、Dialog 组合期异常；回调置空。
 *
 * 注：含 OutlinedTextField 的对话框（序列导入 / 特征编辑 / 序列搜索 / 图形导入）
 * 在 Robolectric 独立 Dialog 窗口内光标闪烁动画使 idling 不收敛（AppNotIdle），
 * 属测试夹具限制，非产品运行期崩溃；留待页面内挂载态或真机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun figureCheckDialog_emptyCounts_rendersWithoutCrash() {
        compose.activity.setContent {
            FigureCheckDialog(
                open = true,
                issues = emptyList(),
                counts = null,
                busy = false,
                onRun = {},
                onExportTiff = {},
                onClose = {}
            )
        }
        compose.waitForIdle()
    }
}
