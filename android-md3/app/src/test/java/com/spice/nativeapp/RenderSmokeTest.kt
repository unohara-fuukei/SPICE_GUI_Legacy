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
 * 渲染冒烟截图：Robolectric NATIVE graphics 真实栅格化 GeneScreen，
 * 各 tab 截 PNG 落盘 /tmp/renders/，供人工核对：
 * - Plasmid Features 浮动卡是否被图谱不透明背景覆盖（z-order）
 * - 序列行文字 / 图谱下方概要与酶选择行是否可见
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun snapshot(name: String) {
        val content = compose.activity.window
            .findViewById<android.view.ViewGroup>(android.R.id.content)
        val w = content.width.coerceAtLeast(1)
        val h = content.height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        compose.activity.runOnUiThread {
            content.draw(canvas)
        }
        java.io.File("/tmp/renders").apply { mkdirs() }
        java.io.FileOutputStream("/tmp/renders/$name.png").use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun geneScreen_renderShots() {
        compose.activity.setContent {
            GeneScreen(
                record = Bio.demoPlasmid(),
                selectedEnzymes = com.spice.nativeapp.core.Enzymes.common,
                onToggleEnzyme = {},
                onRecordChange = {},
                onSaveProject = {},
                onOpenSettings = {},
                onAiOpenChange = {}
            )
        }
        compose.waitForIdle()
        snapshot("00_circular")
        val tabs = mapOf(
            "线性图谱" to "01_linear",
            "序列视图" to "02_sequence",
            "特征列表" to "03_features",
            "质粒属性" to "04_properties",
            "科研备注" to "05_notes"
        )
        tabs.forEach { (label, file) ->
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            Thread.sleep(200)
            snapshot(file)
        }
    }
}
