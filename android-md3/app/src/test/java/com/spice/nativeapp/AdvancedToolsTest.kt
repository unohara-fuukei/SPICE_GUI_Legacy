package com.spice.nativeapp

import com.spice.nativeapp.core.AdvancedTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdvancedTools parity 测试 —— 1:1 对齐 main `genome/advancedTools.ts`：
 * GC 含量滑动窗口、融合阅读框检查（帧位置/帧偏移/连接处翻译）、沉默诱变不可达回退。
 */
class AdvancedToolsTest {

    @Test
    fun gcSlidingWindowSingleFullWindow() {
        val pts = AdvancedTools.gcSlidingWindow("GCGC", windowSize = 4, stepSize = 4)
        assertEquals(1, pts.size)
        assertEquals(0, pts[0].position)
        assertEquals(100.0, pts[0].gc, 1e-6)
    }

    @Test
    fun gcSlidingWindowStepsAcrossSequence() {
        val seq = "GCGCGCGC" // 8 bp，窗 4 步 4 → 2 点，均 100% GC
        val pts = AdvancedTools.gcSlidingWindow(seq, windowSize = 4, stepSize = 4)
        assertEquals(2, pts.size)
        assertEquals(0, pts[0].position)
        assertEquals(4, pts[1].position)
    }

    @Test
    fun gcSlidingWindowEmptySequence() {
        assertTrue(AdvancedTools.gcSlidingWindow("").isEmpty())
    }

    @Test
    fun fusionInFrameWithZeroOffset() {
        // feat1End=1（frame=0）→ (1-0+1)%3=2 → 同框；feat2Start=2, feat1End=1 → 帧偏移 0
        val r = AdvancedTools.checkFusionReadingFrame("ATGGCATAAGCT", 1, 1, 2, 8, 0)
        assertTrue(r.inFrame)
        assertEquals(0, r.frameOffset)
        assertTrue(r.junctionSequence.isNotEmpty())
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun fusionFrameshiftReportsOffset() {
        // feat1End=2 → (2+1)%3=0 ≠ 2 → 移码；feat2Start=5, feat1End=2 → offset=((5-2-1)%3+3)%3=2
        val r = AdvancedTools.checkFusionReadingFrame("ATGGCATAAGCT", 1, 2, 5, 10, 0)
        assertFalse(r.inFrame)
        assertEquals(2, r.frameOffset)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun silentMutagenesisAddUnreachableKeepsSequenceAndWarns() {
        val r = AdvancedTools.simulateSilentMutagenesis("ATG", addSite = "GAATTC", readingFrame = 0)
        assertEquals("ATG", r.modifiedSequence)
        assertTrue(r.mutations.isEmpty())
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun silentMutagenesisRemoveUnreachableKeepsSequenceAndWarns() {
        val r = AdvancedTools.simulateSilentMutagenesis("ATG", removeSite = "GAATTC", readingFrame = 0)
        assertEquals("ATG", r.modifiedSequence)
        assertTrue(r.mutations.isEmpty())
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun annealPerfectComplementSameLength() {
        // top ATCG；reverse 5'->3' = GCTA，其反向互补 TAGC 与 top 完全配对（offset 0）
        val r = AdvancedTools.annealOligos("ATCG", "GCTA")
        assertEquals(4, r.duplexLength)
        assertEquals(4, r.topLength)
        assertEquals(4, r.bottomLength)
        assertEquals("", r.leftOverhang)
        assertEquals("", r.rightOverhang)
        assertTrue(r.warnings.isEmpty())
        // GC 50%；Tm = 64.9 + 41·(50−16.4)/4 = 409.3（main 公式原样，短链失真不修正）
        assertEquals(409.3, r.tm, 1e-6)
    }

    @Test
    fun annealTooShortWarns() {
        val r = AdvancedTools.annealOligos("AT", "AT")
        assertTrue(r.warnings.any { it.contains("过短") })
    }

    @Test
    fun annealNoEffectivePairingWarns() {
        // AAAA 与 CCCC 的反向互补 GGGG 互不配对
        val r = AdvancedTools.annealOligos("AAAA", "CCCC")
        assertTrue(r.warnings.any { it.contains("无法有效退火") })
    }

    @Test
    fun annealFivePrimeTopOverhang() {
        // top GGATCG；bottomRc 对齐 top 后 4 位 ATCG（offset 2），左突出 top 前 2 位 GG
        val r = AdvancedTools.annealOligos("GGATCG", "GCTA")
        assertEquals(4, r.duplexLength)
        assertEquals("GG", r.leftOverhang)
    }
}
