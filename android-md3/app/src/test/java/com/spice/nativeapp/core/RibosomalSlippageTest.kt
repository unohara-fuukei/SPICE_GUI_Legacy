package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RibosomalSlippage（main advancedTools.ts simulateRibosomalSlippage 1:1）语义测试。
 * 参考值由 Node 复刻 main 原函数跑出（/tmp/slip_ref.mjs，标准密码子表一致）。
 */
class RibosomalSlippageTest {

    @Test
    fun sarsMinusOnePrf() {
        val seq = "ATGGCTGGTTTTAAACGACGAGGGTTCCAAATAA"
        val r = RibosomalSlippage.simulate(seq, 1, "TTTAAAC", -1)
        assertEquals("MAGFK", r.preProtein)
        assertEquals("TTRVPN", r.postProtein)
        assertEquals("MAGFKTTRVPN", r.fullProtein)
        assertEquals(10, r.sitePosition)
        assertEquals("TTTAAAC", r.siteSequence)
        assertEquals(-1, r.shiftApplied)
        assertEquals(0, r.stopsEncountered)
        assertEquals(5, r.visualAlignment.lines().size)
        assertTrue(r.visualAlignment.isNotBlank())
    }

    @Test
    fun hivMinusOnePrfCountsStop() {
        val seq = "ATGAAATTTTTTACCGCGCTAGCTAGCTAG"
        val r = RibosomalSlippage.simulate(seq, 1, "TTTTTTA", -1)
        assertEquals("MKFF", r.preProtein)
        assertEquals("YRAS", r.postProtein)
        assertEquals("MKFFYRAS", r.fullProtein)
        assertEquals(7, r.sitePosition)
        assertEquals("TTTTTTA", r.siteSequence)
        assertEquals(1, r.stopsEncountered)
    }

    @Test
    fun ecoliPlusOnePrf() {
        val seq = "ATGCTTTGGCGATCGATCGATCGATCG"
        val r = RibosomalSlippage.simulate(seq, 1, "CTTTG", 1)
        assertEquals("ML", r.preProtein)
        assertEquals("GDRSID", r.postProtein)
        assertEquals("MLGDRSID", r.fullProtein)
        assertEquals(4, r.sitePosition)
        assertEquals("CTTTG", r.siteSequence)
        assertEquals(1, r.shiftApplied)
        assertEquals(0, r.stopsEncountered)
    }

    @Test
    fun missingSiteThrows() {
        val seq = "ATGGCGGCGGCGGCG"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            RibosomalSlippage.simulate(seq, 1, "TTTAAAC", -1)
        }
        assertTrue(ex.message?.contains("TTTAAAC") == true)
    }

    @Test
    fun sitePositionOverrideUsed() {
        // 显式位点起始位覆盖自动查找
        val seq = "ATGGCTGGTTTTAAACGACGAGGGTTCCAAATAA"
        val r = RibosomalSlippage.simulate(seq, 1, "TTTAAAC", -1, sitePosition = 10)
        assertEquals(10, r.sitePosition)
        assertEquals("TTTAAAC", r.siteSequence)
        assertFalse(r.fullProtein.isEmpty())
    }
}
