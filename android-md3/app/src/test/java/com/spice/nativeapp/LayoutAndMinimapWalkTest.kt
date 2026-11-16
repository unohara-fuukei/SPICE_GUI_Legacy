package com.spice.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.ui.AdaptiveSplit
import com.spice.nativeapp.ui.SequenceMinimap
import com.spice.nativeapp.ui.WidthClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 序列缩略图 Canvas 与自适应分栏容器初始态栅格化走查。
 * Minimap 用空特征/空选区/初始 LazyListState（totalItemsCount=0，走整宽视口兜底分支）；
 * 分栏容器分别覆盖 EXPANDED（左右分栏 + 分隔条）与 COMPACT（仅 master）两分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutAndMinimapWalkTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun sequenceMinimap_emptyState_rendersWithoutCrash() {
        val dna = Bio.demoPlasmid().sequence
        compose.activity.setContent {
            SequenceMinimap(
                dnaLen = dna.length,
                features = emptyList(),
                selection = null,
                listState = rememberLazyListState(),
                onJump = {}
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun adaptiveSplit_expanded_rendersWithoutCrash() {
        compose.activity.setContent {
            AdaptiveSplit(widthClass = WidthClass.EXPANDED, master = {}, detail = {})
        }
        compose.waitForIdle()
    }

    @Test
    fun adaptiveSplit_compact_rendersWithoutCrash() {
        compose.activity.setContent {
            AdaptiveSplit(widthClass = WidthClass.COMPACT, master = {}, detail = {})
        }
        compose.waitForIdle()
    }
}
