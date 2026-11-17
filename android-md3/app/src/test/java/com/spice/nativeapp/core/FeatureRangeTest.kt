package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeatureRange.kt 算法语义测试，对齐原版 `tests/gene/featureRange.test.ts`。
 * 区间坐标为 1-based inclusive；end < start 即跨原点 wrap。
 */
class FeatureRangeTest {

    private val sequence = "ACGTACGTACGTACGTACGT"
    private val length = sequence.length

    @Test
    fun `反向存储的区间判定为跨原点`() {
        assertTrue(isWrappedRange(FeatureRange(15, 5)))
        assertTrue(!isWrappedRange(FeatureRange(5, 15)))
        assertEquals(11, rangeLength(FeatureRange(15, 5), length))
        assertEquals(11, rangeLength(FeatureRange(5, 15), length))
    }

    @Test
    fun `读取碱基时跟随原点回绕`() {
        assertEquals("ACGTACGT", extractRange(sequence, FeatureRange(17, 4)))
        assertEquals("GTAC", extractRange(sequence, FeatureRange(3, 6)))
    }

    @Test
    fun `偏移映射回文档位置并跨原点取模`() {
        assertEquals(17, positionAt(FeatureRange(17, 4), 0, length))
        assertEquals(1, positionAt(FeatureRange(17, 4), 4, length))
        assertEquals(5, positionAt(FeatureRange(17, 4), 8, length))
    }

    @Test
    fun `wrap 区间按文档顺序拆成两段`() {
        assertEquals(
            listOf(FeatureRange(15, length), FeatureRange(1, 5)),
            rangeSegments(FeatureRange(15, 5), length)
        )
        assertEquals(
            listOf(FeatureRange(5, 15)),
            rangeSegments(FeatureRange(5, 15), length)
        )
    }

    @Test
    fun `只在跨原点处存在的重叠可被找到`() {
        assertEquals(
            FeatureRange(18, length),
            firstOverlap(FeatureRange(15, 5), FeatureRange(18, 2), length)
        )
        assertNull(firstOverlap(FeatureRange(15, 5), FeatureRange(7, 12), length))
    }

    @Test
    fun `序列范围外的坐标判定越界`() {
        assertTrue(isRangeInBounds(FeatureRange(1, length), length))
        assertTrue(!isRangeInBounds(FeatureRange(0, 5), length))
        assertTrue(!isRangeInBounds(FeatureRange(5, length + 1), length))
    }

    @Test
    fun `跨原点特征在 GenBank 与 EMBL 写为 join`() {
        val rec = SeqRecord(
            name = "pWRAP",
            sequence = sequence,
            circular = true,
            features = listOf(Feature("backbone", "misc_feature", 15, 5, 1))
        )
        assertTrue(SeqIO.toGenBank(rec).contains("join(15..20,1..5)"))
        assertTrue(SeqIO.toEmbl(rec).contains("join(15..20,1..5)"))
    }
}
