package com.spice.nativeapp.core

/**
 * T7-16 Rust 消息层块：solve_phase_bifurcation（cloning.rs:2405）1:1。
 *
 * 热力学相图分叉求解器：对 T-pH 稳定性扫描点拟合 Landau 二次面
 * ΔG ≈ w0 + w1·x + w2·x² + w3·y + w4·y² + w5·xy（z-score 归一化坐标），
 * 提取亚稳中心、相边界曲线（ΔG=0 等值线）与协同失活分叉点。
 *
 * 平台差异（README 披露）：main 桌面端为 Rust tauri command；浏览器模式
 * fallback 为空（无相图拟合）——本机 1:1 复刻 Rust 真身（solvePhaseBifurcation
 * 需 ≥6 点，不足报错 beErrPhaseFitNeedsSixPoints）。
 */
object PhaseBifurcation {

    /** main ScanPointInput（camelCase serde）。安卓 PhaseScan 无 buildFailed 字段 → 默认 false（README 披露）。 */
    data class ScanPoint(val t: Double, val ph: Double, val stable: Boolean, val crashed: Boolean, val buildFailed: Boolean = false)

    /** main PhaseBifurcationOutput 逐字段（camelCase）。 */
    data class Output(
        val optimalTempK: Double,
        val optimalPh: Double,
        val isSaddle: Boolean,
        val coefficients: List<Double>,
        val meanT: Double, val stdT: Double,
        val meanPh: Double, val stdPh: Double,
        val boundaryCurve: List<Pair<Double, Double>>,
        val deepestWellT: Double, val deepestWellPh: Double,
        val bifurcationT: Double, val bifurcationPh: Double
    )

    class NeedsSixPointsException : Exception("i18n:beErrPhaseFitNeedsSixPoints")

    /** solve_least_squares_6d（cloning.rs:2354）：列主元高斯消元 + 回代。 */
    internal fun solveLeastSquares6d(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val mat = a.map { it.copyOf() }.toTypedArray()
        val rhs = b.copyOf()
        val n = 6
        for (col in 0 until n) {
            var maxRow = col
            var maxVal = kotlin.math.abs(mat[col][col])
            for (row in col + 1 until n) {
                val v = kotlin.math.abs(mat[row][col])
                if (v > maxVal) { maxVal = v; maxRow = row }
            }
            if (maxVal < 1e-12) continue
            if (maxRow != col) {
                val tm = mat[col]; mat[col] = mat[maxRow]; mat[maxRow] = tm
                val tr = rhs[col]; rhs[col] = rhs[maxRow]; rhs[maxRow] = tr
            }
            for (row in col + 1 until n) {
                val factor = mat[row][col] / mat[col][col]
                for (j in col until n) mat[row][j] -= factor * mat[col][j]
                rhs[row] -= factor * rhs[col]
            }
        }
        val x = DoubleArray(6)
        for (i in n - 1 downTo 0) {
            var sum = rhs[i]
            for (j in i + 1 until n) sum -= mat[i][j] * x[j]
            if (kotlin.math.abs(mat[i][i]) > 1e-12) x[i] = sum / mat[i][i]
        }
        return x
    }

    /** f32 语义的 round（rust: (opt_t * 10.0).round() / 10.0，全程 f32，输出即 f32）。 */
    private fun round10(v: Float): Double = (kotlin.math.round(v * 10.0f) / 10.0f).toDouble()
    private fun round100(v: Float): Double = (kotlin.math.round(v * 100.0f) / 100.0f).toDouble()
    /** rust: ((v * 10000.0).round() / 10000.0) as f32 —— f64 计算后转 f32。 */
    private fun roundCoeff(v: Double): Double = ((kotlin.math.round(v * 10000.0) / 10000.0).toFloat()).toDouble()

    /** solve_phase_bifurcation 逐行。不足 6 点抛 NeedsSixPointsException。 */
    fun solvePhaseBifurcation(pts: List<ScanPoint>): Output {
        if (pts.size < 6) throw NeedsSixPointsException()

        val n = pts.size.toDouble()
        val meanT = pts.sumOf { it.t } / n
        val meanPh = pts.sumOf { it.ph } / n
        val varT = pts.sumOf { (it.t - meanT) * (it.t - meanT) } / n
        val varPh = pts.sumOf { (it.ph - meanPh) * (it.ph - meanPh) } / n
        val stdT = maxOf(kotlin.math.sqrt(varT), 1.0)
        val stdPh = maxOf(kotlin.math.sqrt(varPh), 0.1)

        val xtx = Array(6) { DoubleArray(6) }
        val xty = DoubleArray(6)
        for (p in pts) {
            val x = (p.t - meanT) / stdT
            val y = (p.ph - meanPh) / stdPh
            // 伪自由能：stable → -1.5（深井），crashed/buildFailed → +1.5，其余 0
            val z = when {
                p.stable && !p.crashed && !p.buildFailed -> -1.5
                p.crashed || p.buildFailed -> 1.5
                else -> 0.0
            }
            val row = doubleArrayOf(1.0, x, x * x, y, y * y, x * y)
            for (i in 0 until 6) {
                xty[i] += row[i] * z
                for (j in 0 until 6) xtx[i][j] += row[i] * row[j]
            }
        }

        val w = solveLeastSquares6d(xtx, xty)

        // 极值点（克莱姆法则）
        val det = 4.0 * w[2] * w[4] - w[5] * w[5]
        val (xOpt, yOpt) = if (kotlin.math.abs(det) > 1e-8) {
            ((-w[1] * 2.0 * w[4] + w[3] * w[5]) / det) to ((-w[3] * 2.0 * w[2] + w[1] * w[5]) / det)
        } else 0.0 to 0.0
        val optT = (xOpt * stdT + meanT).toFloat()
        val optPh = (yOpt * stdPh + meanPh).toFloat()

        val isSaddle = w[2] > 0.0 || w[4] > 0.0

        // 相边界曲线（ΔG=0 等值线，±2σ 80 步二次公式）
        val boundary = mutableListOf<Pair<Double, Double>>()
        val xRange = 4.0
        val steps = 80
        for (i in 0..steps) {
            val x = -xRange + 2.0 * xRange * (i.toDouble() / steps.toDouble())
            val a = w[4]
            val b = w[3] + w[5] * x
            val c = w[0] + w[1] * x + w[2] * x * x
            if (kotlin.math.abs(a) > 1e-8) {
                val disc = b * b - 4.0 * a * c
                if (disc >= 0.0) {
                    val sqrtDisc = kotlin.math.sqrt(disc)
                    for (sign in doubleArrayOf(-1.0, 1.0)) {
                        val y = (-b + sign * sqrtDisc) / (2.0 * a)
                        if (kotlin.math.abs(y) <= xRange) {
                            val t = (x * stdT + meanT).toFloat()
                            val ph = (y * stdPh + meanPh).toFloat()
                            boundary.add(t.toDouble() to ph.toDouble())
                        }
                    }
                }
            } else if (kotlin.math.abs(b) > 1e-8) {
                val y = -c / b
                if (kotlin.math.abs(y) <= xRange) {
                    val t = (x * stdT + meanT).toFloat()
                    val ph = (y * stdPh + meanPh).toFloat()
                    boundary.add(t.toDouble() to ph.toDouble())
                }
            }
        }

        // 分叉点 = 边界上距最优中心最远点（pH 距离 ×100 加权）
        var bifurcationT = optT
        var bifurcationPh = optPh
        var maxDist = 0.0f
        for ((t, ph) in boundary) {
            val dt = t.toFloat() - optT
            val dp = ph.toFloat() - optPh
            val dist = kotlin.math.sqrt(dt * dt + dp * dp * 100.0f)   // rust f32 逐点
            if (dist > maxDist) { maxDist = dist; bifurcationT = t.toFloat(); bifurcationPh = ph.toFloat() }
        }

        return Output(
            optimalTempK = round10(optT),
            optimalPh = round100(optPh),
            isSaddle = isSaddle,
            coefficients = w.map { roundCoeff(it) },
            meanT = meanT.toFloat().toDouble(),
            stdT = stdT.toFloat().toDouble(),
            meanPh = meanPh.toFloat().toDouble(),
            stdPh = stdPh.toFloat().toDouble(),
            boundaryCurve = boundary,
            deepestWellT = round10(optT),
            deepestWellPh = round100(optPh),
            bifurcationT = round10(bifurcationT),
            bifurcationPh = round100(bifurcationPh)
        )
    }
}
