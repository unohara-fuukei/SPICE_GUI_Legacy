package com.spice.nativeapp

import com.spice.nativeapp.core.EndModification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * EndModification parity 测试 —— 1:1 对齐 main `genome/endModification.ts`：
 * Klenow 补平（右端 5' 突出端追加反向互补）、T4 削平（右端 3' 突出端截短）、
 * 磷酸化状态切换；左端变化不改 top 链序列。
 */
class EndModificationTest {

    private fun dna(
        seq: String,
        leftType: String = EndModification.BLUNT,
        leftSeq: String = "",
        leftPhos: Boolean = true,
        rightType: String = EndModification.BLUNT,
        rightSeq: String = "",
        rightPhos: Boolean = true,
    ) = EndModification.LinearDna(
        sequence = seq,
        leftEnd = EndModification.LinearDnaEnd(leftType, leftSeq, leftPhos),
        rightEnd = EndModification.LinearDnaEnd(rightType, rightSeq, rightPhos),
    )

    @Test
    fun fillInRightFiveOverhangAppendsReverseComplement() {
        // right 5'-overhang ATGC → 追加反向互补 GCAT，置平末端
        val out = EndModification.fillIn(
            dna("AAAA", rightType = EndModification.FIVE_OVERHANG, rightSeq = "ATGC")
        )
        assertEquals("AAAAGCAT", out.sequence)
        assertEquals(EndModification.BLUNT, out.rightEnd.overhangType)
        assertEquals("", out.rightEnd.overhangSeq)
    }

    @Test
    fun fillInLeftFiveOverhangLeavesTopSequenceUntouched() {
        val out = EndModification.fillIn(
            dna("AAAA", leftType = EndModification.FIVE_OVERHANG, leftSeq = "ATGC")
        )
        assertEquals("AAAA", out.sequence)
        assertEquals(EndModification.BLUNT, out.leftEnd.overhangType)
    }

    @Test
    fun fillInBluntUnchanged() {
        val out = EndModification.fillIn(dna("GCGC"))
        assertEquals("GCGC", out.sequence)
    }

    @Test
    fun chewBackRightThreeOverhangTruncatesTopTail() {
        // right 3'-overhang 长度 4 → 截去 top 链尾部 4 个碱基
        val out = EndModification.chewBack(
            dna("AAAAATGC", rightType = EndModification.THREE_OVERHANG, rightSeq = "ATGC")
        )
        assertEquals("AAAA", out.sequence)
        assertEquals(EndModification.BLUNT, out.rightEnd.overhangType)
    }

    @Test
    fun chewBackLeftThreeOverhangLeavesTopSequenceUntouched() {
        val out = EndModification.chewBack(
            dna("ATGCAAAA", leftType = EndModification.THREE_OVERHANG, leftSeq = "ATGC")
        )
        assertEquals("ATGCAAAA", out.sequence)
        assertEquals(EndModification.BLUNT, out.leftEnd.overhangType)
    }

    @Test
    fun setPhosphorylationTogglesBothEnds() {
        val dephos = EndModification.setPhosphorylation(dna("AAAA", leftPhos = true, rightPhos = true), false)
        assertFalse(dephos.leftEnd.phosphorylated)
        assertFalse(dephos.rightEnd.phosphorylated)
        assertEquals("AAAA", dephos.sequence)

        val phos = EndModification.setPhosphorylation(dephos, true)
        assertEquals(true, phos.leftEnd.phosphorylated)
        assertEquals(true, phos.rightEnd.phosphorylated)
    }
}
