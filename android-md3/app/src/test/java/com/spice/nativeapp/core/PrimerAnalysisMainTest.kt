package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对照 main primerAnalysis.ts analyzePrimer 参考值（Node 复刻 calculateNebTm
 * + findHairpins/findSelfDimers + 评分模型）。Tm 容差 ±0.5°C，GC 取整比对，
 * 评分/结构计数/Clamp/N 计数/同聚物长度精确比对。
 */
class PrimerAnalysisMainTest {

    private fun approx(a: Double, b: Double) = kotlin.math.abs(a - b) <= 0.5

    @Test
    fun `standard 20mer self-dimer no gc clamp score 71`() {
        val a = PrimerAnalysisMain.analyzePrimer("ATGCGTACGTAGCTAGCTGA")
        assertEquals(20, a.length)
        assertEquals(50.0, a.gcContent, 0.5)
        assertTrue(approx(65.51, a.tm))
        assertFalse(a.gcClamp)
        assertFalse(a.gcClamp5)
        assertEquals(0, a.hairpins.size)
        assertEquals(3, a.selfDimers.size)
        assertEquals(0, a.polyX.size)
        assertEquals(1, a.maxHomopolymer)
        assertEquals(71, a.score)
        assertTrue(a.warnings.any { it.contains("自二聚体") })
        assertTrue(a.warnings.any { it.contains("GC clamp") })
    }

    @Test
    fun `homopolymer rich hairpin polyX score 25`() {
        val a = PrimerAnalysisMain.analyzePrimer("GGGGCCCCAAAATTTTCCCC")
        assertEquals(20, a.length)
        assertEquals(60.0, a.gcContent, 0.5)
        assertTrue(approx(70.661, a.tm))
        assertTrue(a.gcClamp)
        assertTrue(a.gcClamp5)
        assertEquals(1, a.hairpins.size)
        assertEquals(4, a.hairpins[0].stemLength)
        assertEquals(12, a.hairpins[0].loopLength)
        assertEquals(5, a.polyX.size)
        assertEquals(25, a.score)
    }

    @Test
    fun `short poly-A zero score`() {
        val a = PrimerAnalysisMain.analyzePrimer("AAAAAAAA")
        assertEquals(8, a.length)
        assertEquals(0.0, a.gcContent, 0.0)
        assertEquals(1, a.polyX.size)
        assertEquals(8, a.maxHomopolymer)
        assertEquals(0, a.score)
        assertEquals(7, a.warnings.size)
    }

    @Test
    fun `overlong repetitive hairpins`() {
        val a = PrimerAnalysisMain.analyzePrimer("ATCGATCGATCGATCGATCGATCGATCGATCGATCGATCG")
        assertEquals(40, a.length)
        assertEquals(50.0, a.gcContent, 0.5)
        assertTrue(a.gcClamp)
        assertFalse(a.gcClamp5)
        assertEquals(10, a.hairpins.size)
        assertEquals(0, a.selfDimers.size)
        assertEquals(0, a.score)
    }

    @Test
    fun `high GC hairpin and self dimer`() {
        val a = PrimerAnalysisMain.analyzePrimer("GCGCGCGCGCGCGCGCGC")
        assertEquals(18, a.length)
        assertEquals(100.0, a.gcContent, 0.5)
        assertTrue(a.hairpins.isNotEmpty())
        assertTrue(a.selfDimers.isNotEmpty())
        assertEquals(0, a.score)
    }

    @Test
    fun `degenerate N bases zero Tm and counted`() {
        val a = PrimerAnalysisMain.analyzePrimer("NNNNATCGATCG")
        assertEquals(12, a.length)
        assertEquals(4, a.nBaseCount)
        assertEquals(0.0, a.tm, 0.0)
        assertEquals(1, a.polyX.size)
    }
}
