package com.spice.nativeapp.ui

import androidx.compose.ui.graphics.asAndroidPath
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * OVE 有向色带路径的弧线本质核验：
 * main circularOve.ts 用 SVG A 命令（真矢量弧），移植必须等价——
 * 尾弧上任意采样点到圆心的距离恒等于 tI/tO（半径偏差 < 0.5px）。
 * 旧的 24 段折线近似在 300° 大弧上弦垂降 ≈ r·(1−cos(6.25°)) ≈ 1.2px，会被本测试检出。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OveArcTest {

    private fun maxTailArcDeviation(
        cx: Float, cy: Float, radius: Float, annHeight: Float, tailThickness: Float,
        edgeDeg: Float, totalDeg: Float, forward: Boolean
    ): Float {
        val path = ovePiePiecePath(cx, cy, radius, annHeight, tailThickness, edgeDeg, totalDeg, forward, 12.73f)
        val tailH = annHeight * tailThickness
        val tI = radius - tailH / 2f
        val tO = radius + tailH / 2f
        // 尾弧本征角跨度（局部坐标，arrowheadAngle ≈ 12.73/r 弧度）
        val ahRad = min(12.73f / radius, Math.toRadians(totalDeg.toDouble()).toFloat())
        val ahDeg = Math.toDegrees(ahRad.toDouble()).toFloat()
        val margin = 3f  // 度：避开箭头径向边与端点连接线
        val lo = ahDeg + margin
        val hi = totalDeg - margin
        if (hi <= lo) return 0f  // 弧太短（箭头占满），无尾弧可验

        val pm = android.graphics.PathMeasure(path.asAndroidPath(), false)
        val n = 600
        var worst = 0f
        val pos = FloatArray(2)
        for (i in 0..n) {
            if (!pm.getPosTan(pm.length * i / n, pos, null)) continue
            val dx = pos[0] - cx
            val dy = pos[1] - cy
            val dist = hypot(dx, dy)
            // 局部角（相对 edge，forward 递增 / reverse 递减都归一到正跨度）
            var local = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() - edgeDeg
            if (!forward) local = -local
            local = ((local % 360f) + 360f) % 360f
            if (local < lo || local > hi) continue
            val dev = min(abs(dist - tI), abs(dist - tO))
            if (dev > worst) worst = dev
        }
        return worst
    }

    @Test
    fun `forward large arc 300deg stays on true circle`() {
        // 24 段折线在此参数下偏差 ≈1.2px；真弧 < 0.1px
        val dev = maxTailArcDeviation(500f, 500f, 200f, 40f, 0.7f, 10f, 300f, forward = true)
        assertTrue("tail arc radius deviation $dev px exceeds 0.5", dev < 0.5f)
    }

    @Test
    fun `reverse large arc 300deg stays on true circle`() {
        val dev = maxTailArcDeviation(500f, 500f, 200f, 40f, 0.7f, 170f, 300f, forward = false)
        assertTrue("tail arc radius deviation $dev px exceeds 0.5", dev < 0.5f)
    }

    @Test
    fun `forward small arc 60deg stays on true circle`() {
        val dev = maxTailArcDeviation(500f, 500f, 300f, 30f, 0.6f, -45f, 60f, forward = true)
        assertTrue("tail arc radius deviation $dev px exceeds 0.5", dev < 0.5f)
    }

    @Test
    fun `full circle 359deg forward stays on true circle`() {
        // near-full sweep，largeArcFlag 路径
        val dev = maxTailArcDeviation(400f, 400f, 150f, 20f, 0.5f, 90f, 359f, forward = true)
        assertTrue("tail arc radius deviation $dev px exceeds 0.5", dev < 0.5f)
    }
}
