package com.spice.nativeapp.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 分子生物学与 PCR 反应计算器（对齐 main 分支 biophysics/calculators.ts，算法级 1:1）
 *
 * 覆盖：稀释 C1V1=C2V2、连接插入量（可配置摩尔比）、PCR master mix（Taq/Phusion/Q5，
 * 10% 移液余量）、DNA 浓度换算 ng/µL↔nM、梯度 PCR 12 孔优化、凝胶条带半对数回归估算、
 * ELISA 4PL 标准曲线（log-logit 线性化）、酶动力学 Michaelis-Menten（Lineweaver-Burk）。
 */
object Calculators {

    // ───────────────── 1. 稀释 ─────────────────

    data class DilutionResult(val sourceVol: Double, val waterVol: Double)

    fun calculateDilution(sourceConc: Double, targetConc: Double, targetVol: Double): DilutionResult {
        if (sourceConc <= 0 || targetConc <= 0 || targetConc > sourceConc || targetVol <= 0) {
            return DilutionResult(0.0, targetVol)
        }
        val sourceVol = targetConc * targetVol / sourceConc
        val waterVol = maxOf(0.0, targetVol - sourceVol)
        return DilutionResult(round2(sourceVol), round2(waterVol))
    }

    // ───────────────── 2. 连接插入量 ─────────────────

    /** Mass_insert = Mass_vector × (Size_insert / Size_vector) × Molar_Ratio */
    fun calculateLigationMass(vectorSizeBp: Int, insertSizeBp: Int, vectorMassNg: Double, molarRatio: Double): Double {
        if (vectorSizeBp <= 0 || insertSizeBp <= 0 || vectorMassNg <= 0 || molarRatio <= 0) return 0.0
        return round1(vectorMassNg * (insertSizeBp.toDouble() / vectorSizeBp) * molarRatio)
    }

    // ───────────────── 3. PCR Master Mix ─────────────────

    enum class Polymerase(val label: String) { TAQ("Taq"), PHUSION("Phusion"), Q5("Q5") }

    data class PcrMixRow(val component: String, val volumePerWell: Double, val totalVolume: Double)

    /** 标准 PCR 体系表，总量带 10% 移液余量 */
    fun calculatePcrMasterMix(reactionsCount: Int, totalVolume: Double, polymerase: Polymerase): List<PcrMixRow> {
        val totalMult = reactionsCount * 1.10
        val scale = totalVolume / 50.0
        val base = when (polymerase) {
            Polymerase.Q5 -> listOf(
                com.spice.nativeapp.core.I18n.t("2× Q5 高保真 Master Mix") to 25.0,
                com.spice.nativeapp.core.I18n.t("10 µM 正向引物") to 2.5,
                com.spice.nativeapp.core.I18n.t("10 µM 反向引物") to 2.5,
                com.spice.nativeapp.core.I18n.t("模板 DNA (1-10 ng)") to 2.0,
                com.spice.nativeapp.core.I18n.t("无核酸酶水") to 18.0
            )
            Polymerase.PHUSION -> listOf(
                "5× Phusion HF Buffer" to 10.0,
                "10 mM dNTPs" to 1.0,
                com.spice.nativeapp.core.I18n.t("10 µM 正向引物") to 2.5,
                com.spice.nativeapp.core.I18n.t("10 µM 反向引物") to 2.5,
                com.spice.nativeapp.core.I18n.t("Phusion DNA 聚合酶") to 0.5,
                com.spice.nativeapp.core.I18n.t("模板 DNA") to 2.0,
                com.spice.nativeapp.core.I18n.t("无核酸酶水") to 31.5
            )
            Polymerase.TAQ -> listOf(
                com.spice.nativeapp.core.I18n.t("10× Taq Buffer（含 MgCl₂）") to 5.0,
                "10 mM dNTPs Mix" to 1.0,
                com.spice.nativeapp.core.I18n.t("10 µM 正向引物") to 1.0,
                com.spice.nativeapp.core.I18n.t("10 µM 反向引物") to 1.0,
                com.spice.nativeapp.core.I18n.t("Taq DNA 聚合酶") to 0.25,
                com.spice.nativeapp.core.I18n.t("模板 DNA") to 2.0,
                com.spice.nativeapp.core.I18n.t("无核酸酶水") to 39.75
            )
        }
        return base.map { (name, v) -> PcrMixRow(name, round2(v * scale), round2(v * scale * totalMult)) }
    }

    // ───────────────── 4. DNA 浓度换算 ─────────────────

    data class DnaConcentrationResult(val nM: Double, val molWt: Double, val ngPerUl: Double)

    /** dsDNA: MW ≈ bp×617.96+36.04；ssDNA/RNA: bp×307+79 */
    fun convertDnaConcentration(value: Double, direction: String, lengthBp: Int, isDoubleStranded: Boolean = true): DnaConcentrationResult {
        val molWt = if (isDoubleStranded) lengthBp * 617.96 + 36.04 else lengthBp * 307.0 + 79.0
        return if (direction == "ng_to_nM") {
            val nM = value / molWt * 1_000_000
            DnaConcentrationResult(round2(nM), round2(molWt), round2(value))
        } else {
            val ngPerUl = value * molWt / 1_000_000
            DnaConcentrationResult(round2(value), round2(molWt), round2(ngPerUl))
        }
    }

    // ───────────────── 5. 梯度 PCR 优化 ─────────────────

    data class GradientPcrColumn(
        val column: Int, val temperature: Double,
        val efficiencyRating: String, val recommendation: String
    )

    /** 12 孔梯度模块的温度分布与最优先列（Tm-5°C 经验法则） */
    fun optimizeGradientPcr(
        primer1Tm: Double, primer2Tm: Double,
        minTemp: Double = 50.0, maxTemp: Double = 65.0
    ): Pair<List<GradientPcrColumn>, Int> {
        val target = minOf(primer1Tm, primer2Tm) - 5.0
        val tempStep = (maxTemp - minTemp) / 11.0
        val columns = mutableListOf<GradientPcrColumn>()
        var bestCol = 1
        var minDiff = Double.MAX_VALUE
        for (col in 1..12) {
            val temp = minTemp + (col - 1) * tempStep
            val diff = temp - target
            var rating = "Suboptimal"; var rec = com.spice.nativeapp.core.I18n.t("可尝试，特异性可能不足")
            when {
                temp < target - 5 -> { rating = "Poor"; rec = com.spice.nativeapp.core.I18n.t("温度过低，非特异扩增风险高") }
                abs(diff) <= 1.5 -> {
                    rating = "Optimal"; rec = com.spice.nativeapp.core.I18n.t("最佳退火区间")
                    if (abs(diff) < minDiff) { minDiff = abs(diff); bestCol = col }
                }
                temp > target + 1.5 && temp <= target + 4 -> { rating = "Good"; rec = com.spice.nativeapp.core.I18n.t("良好，特异性优先") }
                temp > target + 4 -> { rating = "Poor"; rec = com.spice.nativeapp.core.I18n.t("温度过高，产量不足") }
            }
            columns += GradientPcrColumn(col, round1(temp), rating, rec)
        }
        return columns to bestCol
    }

    // ───────────────── 6. 凝胶条带估算 ─────────────────

    data class GelStandard(val distanceMm: Double, val sizeBp: Int)

    /** log10(size) vs distance 半对数线性回归 */
    fun estimateGelBandSizes(standards: List<GelStandard>, unknownDistances: List<Double>): Pair<List<Int>, Double> {
        val n = standards.size
        if (n < 2) return unknownDistances.map { 0 } to 0.0
        val meanX = standards.sumOf { it.distanceMm } / n
        val meanY = standards.sumOf { log10(it.sizeBp.toDouble()) } / n
        var num = 0.0; var den = 0.0
        for (s in standards) {
            num += (s.distanceMm - meanX) * (log10(s.sizeBp.toDouble()) - meanY)
            den += (s.distanceMm - meanX).pow(2)
        }
        val slope = if (den != 0.0) num / den else 0.0
        val intercept = meanY - slope * meanX
        var totalSS = 0.0; var residualSS = 0.0
        for (s in standards) {
            val y = log10(s.sizeBp.toDouble())
            val pred = slope * s.distanceMm + intercept
            totalSS += (y - meanY).pow(2)
            residualSS += (y - pred).pow(2)
        }
        val r2 = if (totalSS != 0.0) 1 - residualSS / totalSS else 0.0
        val sizes = unknownDistances.map { 10.0.pow(slope * it + intercept).roundToInt() }
        return sizes to round4(r2)
    }

    // ───────────────── 7. ELISA 4PL 标准曲线 ─────────────────

    data class ElisaPoint(val concentration: Double, val odValue: Double)

    data class ElisaResult(
        val fittedConcentrations: List<Double>,
        val a: Double, val b: Double, val c: Double, val d: Double, val rSquared: Double
    )

    /** 4PL：y = d + (a-d)/(1+(x/c)^b)；以 log-logit 线性化稳健拟合 */
    fun fitElisaStandardCurve(standards: List<ElisaPoint>, unknownOds: List<Double>): ElisaResult {
        val ods = standards.map { it.odValue }
        if (ods.isEmpty()) return ElisaResult(unknownOds.map { 0.0 }, 0.0, 1.0, 1.0, 0.0, 0.0)
        val a = ods.min() * 0.95
        val d = ods.max() * 1.05
        val valid = standards.filter { it.odValue > a && it.odValue < d && it.concentration > 0 }
        val n = valid.size
        if (n < 2) return ElisaResult(unknownOds.map { 0.0 }, round3(a), 1.0, 1.0, round3(d), 0.0)

        val pts = valid.map { s ->
            val yScaled = (s.odValue - a) / (d - a)
            Triple(s, log10(s.concentration), ln(yScaled / (1 - yScaled)))
        }
        val meanX = pts.sumOf { it.second } / n
        val meanY = pts.sumOf { it.third } / n
        var num = 0.0; var den = 0.0
        for (p in pts) {
            num += (p.second - meanX) * (p.third - meanY)
            den += (p.second - meanX).pow(2)
        }
        val b = if (den != 0.0) num / den else 1.0
        val intercept = meanY - b * meanX
        val c = 10.0.pow(-intercept / b)

        val fitted = unknownOds.map { od ->
            val clamped = (a + 0.001).coerceAtLeast((d - 0.001).coerceAtMost(od))
            val yScaled = (clamped - a) / (d - a)
            val logitY = ln(yScaled / (1 - yScaled))
            round3(10.0.pow((logitY - intercept) / b))
        }
        // 回代 R²
        val meanOd = ods.average()
        var totalVar = 0.0; var residualVar = 0.0
        for (s in standards) {
            val pred = d + (a - d) / (1 + (s.concentration / c).pow(b))
            totalVar += (s.odValue - meanOd).pow(2)
            residualVar += (s.odValue - pred).pow(2)
        }
        val r2 = if (totalVar != 0.0) minOf(1.0, 1 - residualVar / totalVar) else 1.0
        return ElisaResult(fitted, round3(a), round3(b), round3(c), round3(d), round4(r2))
    }

    // ───────────────── 8. 酶动力学 ─────────────────

    data class KineticsPoint(val substrateConc: Double, val velocity: Double)

    data class KineticsResult(
        val vMax: Double, val kM: Double, val rSquared: Double, val fittedVelocities: List<Double>
    )

    /** Michaelis-Menten，Lineweaver-Burk 双倒数线性化 */
    fun calculateEnzymeKinetics(points: List<KineticsPoint>): KineticsResult {
        val valid = points.filter { it.substrateConc > 0 && it.velocity > 0 }
        val n = valid.size
        if (n < 2) return KineticsResult(0.0, 0.0, 0.0, points.map { 0.0 })

        val xR = valid.map { 1 / it.substrateConc }
        val yR = valid.map { 1 / it.velocity }
        val meanX = xR.average(); val meanY = yR.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (xR[i] - meanX) * (yR[i] - meanY)
            den += (xR[i] - meanX).pow(2)
        }
        val slope = if (den != 0.0) num / den else 1.0
        val intercept = meanY - slope * meanX
        val vMax = if (intercept != 0.0) 1 / intercept else 0.0
        val kM = slope * vMax

        val fitted = points.map { p ->
            if (p.substrateConc <= 0) 0.0 else round3(vMax * p.substrateConc / (kM + p.substrateConc))
        }
        val meanV = valid.sumOf { it.velocity } / n
        var totalSS = 0.0; var residualSS = 0.0
        for (p in valid) {
            val fv = vMax * p.substrateConc / (kM + p.substrateConc)
            totalSS += (p.velocity - meanV).pow(2)
            residualSS += (p.velocity - fv).pow(2)
        }
        val r2 = if (totalSS != 0.0) 1 - residualSS / totalSS else 0.0
        return KineticsResult(round3(vMax), round3(kM), round4(r2), fitted)
    }

    private fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
    private fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
    private fun round3(v: Double) = kotlin.math.round(v * 1000) / 1000.0
    private fun round4(v: Double) = kotlin.math.round(v * 10000) / 10000.0
}
