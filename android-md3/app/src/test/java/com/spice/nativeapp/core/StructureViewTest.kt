package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StructureView.kt 表示几何/光照口径测试，对照原版 MolStarViewer.svelte
 * `effectiveRep()` / `applyRepresentation()` / `renderer.light` 语义。
 */
class StructureViewTest {

    @Test
    fun `auto 回落默认表示：完整结构 cartoon，坐标轨迹 trace`() {
        assertEquals(StructureView.REP_CARTOON, StructureView.effectiveRep("auto", isFull = true))
        assertEquals(StructureView.REP_TRACE, StructureView.effectiveRep("auto", isFull = false))
    }

    @Test
    fun `显式表示透传，非法值回落默认`() {
        assertEquals(StructureView.REP_SPACEFILL, StructureView.effectiveRep("spacefill", isFull = false))
        assertEquals(StructureView.REP_BALLSTICK, StructureView.effectiveRep("ballstick", isFull = true))
        assertEquals(StructureView.REP_TRACE, StructureView.effectiveRep("bogus", isFull = false))
        assertEquals(StructureView.REP_CARTOON, StructureView.effectiveRep("bogus", isFull = true))
    }

    @Test
    fun `spacefill 只画大球不画连线`() {
        val g = StructureView.repGeometry(StructureView.REP_SPACEFILL, 1.0f, manyResidues = false)
        assertTrue(g.drawBalls)
        assertFalse(g.drawBonds)
        assertEquals(0f, g.bondWidthDp, 1e-6f)
        assertEquals(4.0f, g.ballRadiusDp, 1e-6f)
    }

    @Test
    fun `ballstick 画中等球与细棍`() {
        val g = StructureView.repGeometry(StructureView.REP_BALLSTICK, 1.0f, manyResidues = false)
        assertTrue(g.drawBalls)
        assertTrue(g.drawBonds)
        assertEquals(1.5f, g.bondWidthDp, 1e-6f)
        assertEquals(3.0f, g.ballRadiusDp, 1e-6f)
        // 球棍：棍细于球半径
        assertTrue(g.bondWidthDp < g.ballRadiusDp)
    }

    @Test
    fun `cartoon 画粗丝带不画球`() {
        val g = StructureView.repGeometry(StructureView.REP_CARTOON, 1.0f, manyResidues = false)
        assertFalse(g.drawBalls)
        assertTrue(g.drawBonds)
        assertEquals(5.0f, g.bondWidthDp, 1e-6f)
        assertEquals(0f, g.ballRadiusDp, 1e-6f)
    }

    @Test
    fun `trace 画球与轨迹线，残基过多收窄线宽`() {
        val slim = StructureView.repGeometry(StructureView.REP_TRACE, 1.0f, manyResidues = true)
        val broad = StructureView.repGeometry(StructureView.REP_TRACE, 1.0f, manyResidues = false)
        assertTrue(slim.drawBalls && slim.drawBonds)
        assertTrue(broad.drawBalls && broad.drawBonds)
        assertEquals(1.2f, slim.bondWidthDp, 1e-6f)
        assertEquals(2.2f, broad.bondWidthDp, 1e-6f)
        assertTrue(slim.bondWidthDp < broad.bondWidthDp)
    }

    @Test
    fun `尺寸系数线性缩放球径并钳制到 0_2_2`() {
        val g2 = StructureView.repGeometry(StructureView.REP_SPACEFILL, 2.0f, false)
        val g02 = StructureView.repGeometry(StructureView.REP_SPACEFILL, 0.2f, false)
        val gBig = StructureView.repGeometry(StructureView.REP_SPACEFILL, 99f, false)
        val gSmall = StructureView.repGeometry(StructureView.REP_SPACEFILL, -5f, false)
        assertEquals(8.0f, g2.ballRadiusDp, 1e-6f)
        assertEquals(0.8f, g02.ballRadiusDp, 1e-6f)
        assertEquals(8.0f, gBig.ballRadiusDp, 1e-6f)   // 钳制上限 2
        assertEquals(0.8f, gSmall.ballRadiusDp, 1e-6f) // 钳制下限 0.2
    }

    @Test
    fun `朝向光源的法向比背光更亮`() {
        val base = floatArrayOf(1f, 1f, 1f)
        // 取与光同方向的法向近似（光向 x/y/z 均为正）
        val lit = StructureView.shade(base, 1f, 1f, 1f, lightI = 1f, exposure = 1f)
        val dark = StructureView.shade(base, -1f, -1f, -1f, lightI = 1f, exposure = 1f)
        assertTrue(lit[0] > dark[0])
        assertTrue(lit[1] > dark[1])
        assertTrue(lit[2] > dark[2])
    }

    @Test
    fun `光照与曝光缩放亮度并钳制到 0_1`() {
        val base = floatArrayOf(0.8f, 0.8f, 0.8f)
        val dim = StructureView.shade(base, 0f, 0f, 1f, lightI = 0.2f, exposure = 0.3f)
        val bright = StructureView.shade(base, 0f, 0f, 1f, lightI = 3f, exposure = 2.5f)
        assertTrue(dim[0] < bright[0])
        assertTrue(bright[0] <= 1f && dim[0] >= 0f)
        // 超限输入钳制，不产生 >1 或 <0 通道
        val over = StructureView.shade(floatArrayOf(2f, -1f, 0.5f), 1f, 1f, 1f, 3f, 3f)
        assertTrue(over[0] in 0f..1f)
        assertTrue(over[1] in 0f..1f)
    }
}
