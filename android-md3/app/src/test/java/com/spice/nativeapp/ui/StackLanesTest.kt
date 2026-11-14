package com.spice.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图谱特征分层算法 1:1 核验（main CircularMapCanvas calculateYOffsets / rowRender calculateRowYOffsets）：
 * - 环状：按环长降序 + 跨零点拆两段闭区间重叠 + 最低可用层
 * - 线性：按长度降序 + 闭区间重叠 + 最低可用层
 * 验证任意序列（含跨零点特征）下 lane 分配与 main 语义一致，而非仅 demo 路径。
 */
class StackLanesTest {

    data class F(val start: Int, val end: Int)

    // ── 环状（main CircularMapCanvas.svelte:156 calculateYOffsets）──

    @Test
    fun `circular longest first then non overlapping reuses lane 0`() {
        // 长 6 的特征先进 lane0；短特征与其重叠进 lane1；不重叠的回 lane0
        val slots = stackCircular(listOf(F(3, 8), F(10, 12), F(5, 6)), { it.start }, { it.end }, 20)
        val byStart = slots.associate { it.item.start to it.lane }
        assertEquals(0, byStart[3])   // 最长，lane0
        assertEquals(0, byStart[10])  // 10..12 与 3..8 不重叠 → lane0
        assertEquals(1, byStart[5])   // 5..6 被 3..8 覆盖 → lane1
    }

    @Test
    fun `circular adjacent touching counts as overlap`() {
        // main circularRangesOverlap 闭区间：max(s)<=min(e) → 3..8 与 9..12 不相邻碰不到
        // 但 3..8 与 8..12 相接（共享 8）算重叠
        val slots = stackCircular(listOf(F(3, 8), F(8, 12)), { it.start }, { it.end }, 20)
        assertEquals(0, slots.first { it.item.start == 3 }.lane)
        assertEquals(1, slots.first { it.item.start == 8 }.lane)
    }

    @Test
    fun `circular cross-origin feature wraps and blocks both segments`() {
        // 跨零点特征 18..3（seqLen 20）拆为 [18,19]+[0,3]；
        // 它先按环长进 lane0；随后 0..2（被 [0,3] 命中）与 17..19（被 [18,19] 命中）都必须让到 lane1
        val slots = stackCircular(listOf(F(18, 3), F(0, 2), F(17, 19)), { it.start }, { it.end }, 20)
        val byStart = slots.associate { it.item.start to it.lane }
        assertEquals(0, byStart[18])  // 环长 6 最长
        assertEquals(1, byStart[0])
        assertEquals(1, byStart[17])
    }

    @Test
    fun `circular cross-origin b fraction extends past 1 for rendering`() {
        // 渲染需要 b>1 表示跨零点弧
        val slots = stackCircular(listOf(F(18, 3)), { it.start }, { it.end }, 20)
        val s = slots.single()
        assertEquals((18 - 1) / 20f, s.a, 1e-6f)
        assertEquals(3f / 20f + 1f, s.b, 1e-6f)
    }

    // ── 线性（main rowRender.ts:89 calculateRowYOffsets）──

    @Test
    fun `linear longest first closed interval overlap`() {
        // (2..8) 长 6 lane0；(3..4) 被覆盖 lane1；(10..12) 不重叠 lane0
        val slots = stackLinear(
            listOf(Triple(2f, 9f, "A"), Triple(3f, 5f, "B"), Triple(10f, 13f, "C")),
            { it.first }, { it.second }
        )
        val byName = slots.associate { it.item.third to it.lane }
        assertEquals(0, byName["A"])
        assertEquals(1, byName["B"])
        assertEquals(0, byName["C"])
    }

    @Test
    fun `linear touching intervals share a lane`() {
        // main checkRangesOverlap 闭区间：1..5 与 5..9 重叠（共享 5）→ 不同层；
        // 但 1..5 与 6..9 不重叠 → 同层。这里用半开 a/b（a=起点, b=终点+1）等价转换：
        // [1,6) 与 [5,10) 重叠 → lane 不同；[1,6) 与 [6,10) 不重叠 → 同层
        val slots = stackLinear(
            listOf(Triple(1f, 6f, "A"), Triple(5f, 10f, "B"), Triple(6f, 10f, "C")),
            { it.first }, { it.second }
        )
        val byName = slots.associate { it.item.third to it.lane }
        assertEquals(0, byName["A"])
        assertEquals(1, byName["B"])
        assertEquals(0, byName["C"])
    }
}
