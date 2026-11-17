package com.spice.nativeapp.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 结构查看器的表示几何与光照口径，对照原版 `src/lib/viewer/MolStarViewer.svelte`
 * 的 `applyRepresentation()`（representation 类型 / `sizeParams.sizeFactor`）与
 * `renderer.light`（单光源 inclination 60° / azimuth 30°）。
 *
 * 平台差异（README 披露）：原版由 Mol* WebGL 管线输出真实 3D 光照球体；
 * Android 无 WebView/JS 运行时，改由 Compose Canvas 软件渲染。本对象只承载与
 * 渲染无关的纯几何/颜色口径（表示选择、球/棍尺寸基准、朗伯漫反射系数），
 * 球面渐变与高光由绘制侧 Canvas 近似，保证口径在两侧一致、可单测锁定。
 */
object StructureView {

    const val REP_AUTO = "auto"
    const val REP_TRACE = "trace"
    const val REP_SPACEFILL = "spacefill"
    const val REP_CARTOON = "cartoon"
    const val REP_BALLSTICK = "ballstick"

    /** 原版 `<select>` 五选项顺序（auto | trace | spacefill | cartoon | ballstick）。 */
    val REP_OPTIONS = listOf(REP_AUTO, REP_TRACE, REP_SPACEFILL, REP_CARTOON, REP_BALLSTICK)

    /**
     * 原版 `effectiveRep()`：`isFull()`（有完整 pdbText）→ 默认 cartoon，否则默认 trace；
     * `auto` 回落该默认值，非法值同样回落默认。
     */
    fun effectiveRep(repSel: String, isFull: Boolean): String {
        val def = if (isFull) REP_CARTOON else REP_TRACE
        return if (repSel == REP_AUTO) def else if (repSel in REP_OPTIONS) repSel else def
    }

    /**
     * 一种表示下的几何开关与尺寸基准（dp，未乘透视缩放；球径基准对齐原版 sizeFactor 作用面）。
     * - [drawBonds] 是否画残基间连线（棍/轨迹线/丝带）；
     * - [drawBalls] 是否画残基球体；
     * - [bondWidthDp] 连线/丝带带宽；[ballRadiusDp] 球体半径。
     */
    data class RepGeometry(
        val kind: String,
        val drawBalls: Boolean,
        val drawBonds: Boolean,
        val bondWidthDp: Float,
        val ballRadiusDp: Float,
    )

    /**
     * 各表示几何基准，`sizeFactor` 对齐原版 `sizeParams.sizeFactor`（设置滑杆 0.2..2.0）。
     * 口径对应原版 `addRepresentation` 分支：
     * - trace：`polymer-trace` 线 + `spacefill`（sizeFactor `0.5*sizeFactor`）小球；
     * - spacefill：`spacefill` 大球，不画连线；
     * - ballstick：`ballstick` 中等球 + 细棍；
     * - cartoon：`polymer-cartoon` 粗丝带，不画球。
     *
     * `manyResidues` 对齐原版 trace 细线分支（残基 > 250 时收窄线宽）。
     * 球/棍的 dp 基准是 Canvas 平台近似（原版按原子范德华半径 + WebGL 像素比），
     * 相对比例（spacefill 球 > ballstick 球 > trace 球；ballstick 棍 < 球）与原版一致。
     */
    fun repGeometry(kind: String, sizeFactor: Float, manyResidues: Boolean): RepGeometry {
        val sf = sizeFactor.coerceIn(0.2f, 2f)
        return when (kind) {
            REP_SPACEFILL -> RepGeometry(kind, drawBalls = true, drawBonds = false, bondWidthDp = 0f, ballRadiusDp = 4.0f * sf)
            REP_BALLSTICK -> RepGeometry(kind, drawBalls = true, drawBonds = true, bondWidthDp = 1.5f, ballRadiusDp = 3.0f * sf)
            REP_CARTOON -> RepGeometry(kind, drawBalls = false, drawBonds = true, bondWidthDp = 5.0f * sf, ballRadiusDp = 0f)
            else -> RepGeometry(
                REP_TRACE, drawBalls = true, drawBonds = true,
                bondWidthDp = if (manyResidues) 1.2f else 2.2f,
                ballRadiusDp = 1.4f * sf,
            )
        }
    }

    /** 方向光：原版 inclination 60° / azimuth 30°（`renderer.light` 单光源）。 */
    private val LIGHT = run {
        val laz = Math.toRadians(30.0)
        val lincl = Math.toRadians(60.0)
        Triple(
            cos(laz).toFloat() * sin(lincl).toFloat(),
            sin(laz).toFloat() * sin(lincl).toFloat(),
            cos(lincl).toFloat(),
        )
    }

    /**
     * 朗伯漫反射着色，对齐原版 `renderer.exposure` + `light.intensity` 近似口径。
     * `base` 为 0..1 的 rgb；`nx/ny/nz` 为残基→质心方向法向（调用侧提供，函数内归一化）。
     * 返回 0..1 的 rgb；环境光兜底系数 0.55，漫反射贡献 0.45。
     */
    fun shade(base: FloatArray, nx: Float, ny: Float, nz: Float, lightI: Float, exposure: Float): FloatArray {
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        var diff = (nx / nl * LIGHT.first + ny / nl * LIGHT.second + nz / nl * LIGHT.third)
        diff = (diff * 0.5f + 0.5f).coerceIn(0f, 1f)
        val k = (0.55f + lightI * diff * 0.45f) * exposure.coerceIn(0.3f, 2.5f)
        val kk = k.coerceIn(0f, 1f)
        return FloatArray(3) { (base[it].coerceIn(0f, 1f) * kk).coerceIn(0f, 1f) }
    }
}
