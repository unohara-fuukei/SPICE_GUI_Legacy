package com.spice.nativeapp.core

import kotlin.math.ln

/**
 * 引物精细分析，对应原版 genome/primerAnalysis.ts：
 * SantaLucia 最近邻 Tm、NEB Ta 近似、自二聚体/交叉二聚体、发夹、3′ 端稳定性、poly-X。
 */
object PrimerAnalysis {

    data class Dimer(val maxComplement: Int, val atThreePrime: Boolean, val shift: Int, val detail: String)
    data class Hairpin(val stemLength: Int, val loopSize: Int, val position: Int)
    data class PolyRun(val base: Char, val length: Int, val position: Int)

    data class Result(
        val length: Int,
        val gcPercent: Double,
        val tmNearestNeighbor: Double,
        val tmNeb: Double,
        val ta: Double?,
        val threePrimeStability: Int,
        val selfDimer: Dimer?,
        val hairpin: Hairpin?,
        val polyRuns: List<PolyRun>,
        val warnings: List<String>
    )

    // SantaLucia 1998 二核苷酸参数（ΔH kcal/mol，ΔS cal/mol·K）
    private val dh = mapOf(
        "AA" to -7.9, "TT" to -7.9, "AT" to -7.2, "TA" to -7.2,
        "CA" to -8.5, "TG" to -8.5, "GT" to -8.4, "AC" to -8.4,
        "CT" to -7.8, "AG" to -7.8, "GA" to -8.2, "TC" to -8.2,
        "CG" to -10.6, "GC" to -9.8, "GG" to -8.0, "CC" to -8.0
    )
    private val ds = mapOf(
        "AA" to -22.2, "TT" to -22.2, "AT" to -20.4, "TA" to -21.3,
        "CA" to -22.7, "TG" to -22.7, "GT" to -22.4, "AC" to -22.4,
        "CT" to -21.0, "AG" to -21.0, "GA" to -22.2, "TC" to -22.2,
        "CG" to -27.2, "GC" to -24.4, "GG" to -19.9, "CC" to -19.9
    )

    private const val R = 1.987 // cal/(mol·K)

    fun clean(seq: String): String = seq.uppercase().filter { it in "ATCGN" }

    fun gcPercent(seq: String): Double {
        if (seq.isEmpty()) return 0.0
        return seq.count { it == 'G' || it == 'C' } * 100.0 / seq.length
    }

    /** 最近邻 Tm（SantaLucia 1998，含盐修正） */
    fun tmNearestNeighbor(
        seq: String,
        naConc: Double = 0.05,
        primerConc: Double = 0.5e-6,
        selfComplementary: Boolean = false
    ): Double {
        val s = clean(seq)
        if (s.length < 2) return 0.0
        var h = 0.0
        var ent = 0.0
        for (i in 0 until s.length - 1) {
            val key = s.substring(i, i + 2).replace('N', 'A')
            h += dh[key] ?: 0.0
            ent += (ds[key] ?: 0.0) + 0.368 * ln(naConc)
        }
        // 末端 AT 惩罚（SantaLucia）
        if (s.first() == 'A' || s.first() == 'T') { h += 2.2; ent += 6.9 }
        if (s.last() == 'A' || s.last() == 'T') { h += 2.2; ent += 6.9 }

        val c = if (selfComplementary) primerConc else primerConc / 4.0
        val tmK = h * 1000.0 / (ent + R * ln(c))
        return tmK - 273.15
    }

    /** NEB Tm 近似：≤13 nt 用 Wallace 规则，>13 nt 用 GC 规则 */
    fun tmNeb(seq: String, naConc: Double = 0.05, primerConc: Double = 0.5e-6): Double {
        val s = clean(seq)
        if (s.length < 14) {
            val at = s.count { it == 'A' || it == 'T' }
            val gc = s.count { it == 'G' || it == 'C' }
            return 2.0 * at + 4.0 * gc
        }
        val gc = gcPercent(s) / 100.0
        // 盐浓度换算成 mM 后的经验式
        val na = naConc * 1000.0
        return 81.5 + 16.6 * kotlin.math.log10(na / 1000.0 + 0.001) + 0.41 * gc * 100.0 -
            675.0 / s.length + 0.7 * (primerConc * 1e9) / 1000.0
    }

    /** NEB Ta 近似（引用产品 Tm 时可代入）；此处用 0.3Tm + 0.7Tp − 14.9 的简化式 */
    fun annealingTemp(primerTm: Double, productTm: Double): Double = 0.3 * primerTm + 0.7 * productTm - 14.9

    /** 3′ 端稳定性：末 5 nt 中 G/C 的个数 */
    fun threePrimeStability(seq: String): Int {
        val tail = seq.takeLast(5)
        return tail.count { it == 'G' || it == 'C' }
    }

    /** 自二聚体 / 交叉二聚体：找最长连续反向互补（≥4） */
    fun dimer(a: String, b: String): Dimer? {
        val x = clean(a)
        val y = clean(b)
        val rc = Bio.revComp(y)
        var best = 0
        var bestShift = 0
        var bestAt3 = false
        // 平移后逐位比较
        for (shift in -(x.length + rc.length)..(x.length + rc.length)) {
            var run = 0
            var runStart = 0
            var bestRunStart = 0
            for (i in 0 until rc.length) {
                val j = i + shift
                if (j in x.indices) {
                    val comp = x[j] == rc[i]
                    if (comp) {
                        if (run == 0) runStart = j
                        run++
                        if (run > best) { best = run; bestShift = shift; bestRunStart = runStart }
                    } else run = 0
                }
            }
            if (best >= 4) {
                // 3′ 端（探针 3′ 对应 x 的末端区域）
                bestAt3 = bestRunStart + best >= x.length - 1 || bestRunStart <= 1
            }
        }
        if (best < 4) return null
        return Dimer(
            maxComplement = best,
            atThreePrime = bestAt3,
            shift = bestShift,
            detail = com.spice.nativeapp.core.I18n.tf("{v1} 个连续互补碱基", best) + if (bestAt3) com.spice.nativeapp.core.I18n.t("（含 3′ 端）") else ""
        )
    }

    private fun complementMatch(a: Char, b: Char): Boolean = when (b) {
        'A' -> a == 'T'
        'T' -> a == 'A'
        'G' -> a == 'C'
        'C' -> a == 'G'
        else -> false
    }

    /** 发夹：茎区 s[i..i+len) 的反向互补出现在 i+len+minLoop 之后 */
    fun hairpin(seq: String, minStem: Int = 4, minLoop: Int = 3): Hairpin? {
        val s = clean(seq)
        if (s.length < minStem * 2 + minLoop) return null
        for (i in 0..(s.length - minStem * 2 - minLoop)) {
            val maxLen = minOf(12, s.length - i - minLoop - minStem)
            for (len in maxLen downTo minStem) {
                val stem = s.substring(i, i + len)
                val rc = Bio.revComp(stem)
                val j = s.indexOf(rc, i + len + minLoop)
                if (j > 0) return Hairpin(len, j - (i + len), i)
            }
        }
        return null
    }

    fun polyRuns(seq: String, minRun: Int = 4): List<PolyRun> {
        val s = clean(seq)
        val out = mutableListOf<PolyRun>()
        var i = 0
        while (i < s.length) {
            var j = i
            while (j < s.length && s[j] == s[i]) j++
            if (j - i >= minRun) out += PolyRun(s[i], j - i, i + 1)
            i = j
        }
        return out
    }

    /** 完整分析（对应原版 analyzePrimer） */
    fun analyze(seq: String, productTm: Double? = null): Result {
        val s = clean(seq)
        val tmnn = tmNearestNeighbor(s)
        val tmN = tmNeb(s)
        val self = dimer(s, s)
        val hp = hairpin(s)
        val runs = polyRuns(s)
        val warnings = mutableListOf<String>()
        if (s.length !in 18..30) warnings += com.spice.nativeapp.core.I18n.tf("长度 {v1} nt，常用引物 18–30 nt", s.length)
        val gc = gcPercent(s)
        if (gc < 40 || gc > 60) warnings += com.spice.nativeapp.core.I18n.tf("GC {v1}%，建议 40–60%", "%.0f".format(gc))
        self?.let { if (it.atThreePrime) warnings += com.spice.nativeapp.core.I18n.tf("自二聚体含 3′ 端：{v1}", it.detail) else warnings += com.spice.nativeapp.core.I18n.tf("自二聚体：{v1}", it.detail) }
        hp?.let { warnings += com.spice.nativeapp.core.I18n.tf("发夹结构：茎 {v1} bp、环 {v2} nt（位置 {v3}）", it.stemLength, it.loopSize, it.position + 1) }
        runs.forEach { warnings += com.spice.nativeapp.core.I18n.tf("poly-{v1} 连续 {v2} nt（位置 {v3}）", it.base, it.length, it.position) }
        val stab = threePrimeStability(s)
        if (stab >= 4) warnings += com.spice.nativeapp.core.I18n.tf("3′ 端末 5 nt 含 {v1} 个 G/C，稳定性偏强，可能非特异扩增", stab)
        return Result(
            length = s.length,
            gcPercent = gc,
            tmNearestNeighbor = tmnn,
            tmNeb = tmN,
            ta = productTm?.let { annealingTemp(tmN, it) },
            threePrimeStability = stab,
            selfDimer = self,
            hairpin = hp,
            polyRuns = runs,
            warnings = warnings
        )
    }
}
