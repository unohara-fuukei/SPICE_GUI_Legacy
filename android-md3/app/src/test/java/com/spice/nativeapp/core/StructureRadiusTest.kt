package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #125 回归：3D 视图清空氨基酸序列后，合成坐标集合为空，归一化半径不能用
 * 空集 `maxOf`（会抛 NoSuchElementException）。Structure.maxRadius 空集合返回 1f 兜底，
 * 口径与 Parsed 的 maxOfOrNull 兜底一致。
 */
class StructureRadiusTest {

    @Test
    fun emptyPointsRadiusFallsBackToOne() {
        val empty = emptyList<Triple<Float, Float, Float>>()
        // 不抛异常，返回兜底半径 1f
        assertEquals(1f, Structure.maxRadius(empty), 1e-6f)
    }

    @Test
    fun singlePointRadiusIsHypotenuse() {
        val points = listOf(Triple(3f, 4f, 0f))
        assertEquals(5f, Structure.maxRadius(points), 1e-6f)
    }

    @Test
    fun multiplePointsRadiusTakesMax() {
        val points = listOf(
            Triple(3f, 4f, 0f),   // 5
            Triple(0f, 0f, 12f),  // 12
            Triple(1f, 1f, 1f),   // sqrt(3)
        )
        assertEquals(12f, Structure.maxRadius(points), 1e-6f)
    }
}
