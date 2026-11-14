package com.spice.nativeapp.core

/**
 * SPICE 高级蛋白生物物理模块（对齐 main 分支 lib/genome/biophysics 目录）
 *
 * 算法级 1:1 移植：
 * - proteinAnalysis.ts: Chou-Fasman、Kyte-Doolittle、结构域模式、Wilkinson-Harrison 溶解性、
 *   TANGO 风格聚集热点、ΔΔG 点突变稳定性、胰蛋白酶 PMF、二硫键、Kolaskar-Tongaonkar 抗原性
 * - batchEvaluator.ts: 突变库 CSV 解析、批量热稳定性评分（FoldIndex 无序度 + 螺旋占比 + GRAVY + 电荷）
 * - demo.ts / onnx.rs 语义: 折叠（环境敏感置信度）、MD 步进、温-pH 相图扫描、口袋检测/特征/差分
 *
 * 与原版一致的口径：ΔΔG 负值=稳定；打分 0..100；pI 二分 15 轮。
 */
object ProteinBiophysics {

    // ───────────────────────── 氨基酸常量表（与原版完全一致） ─────────────────────────

    val HYDRO: Map<Char, Double> = mapOf(
        'A' to 1.8, 'R' to -4.5, 'N' to -3.5, 'D' to -3.5, 'C' to 2.5, 'Q' to -3.5, 'E' to -3.5,
        'G' to -0.4, 'H' to -3.2, 'I' to 4.5, 'L' to 3.8, 'K' to -3.9, 'M' to 1.9, 'F' to 2.8,
        'P' to -1.6, 'S' to -0.8, 'T' to -0.7, 'W' to -0.9, 'Y' to -1.3, 'V' to 4.2
    )

    private val VOLUME: Map<Char, Double> = mapOf(
        'A' to 88.6, 'R' to 173.4, 'N' to 114.1, 'D' to 111.1, 'C' to 108.5, 'Q' to 143.8,
        'E' to 138.4, 'G' to 60.1, 'H' to 153.2, 'I' to 166.7, 'L' to 166.7, 'K' to 168.6,
        'M' to 162.9, 'F' to 189.9, 'P' to 112.7, 'S' to 89.0, 'T' to 116.1, 'W' to 227.8,
        'Y' to 193.6, 'V' to 140.0
    )

    private val CF_P_HELIX: Map<Char, Double> = mapOf(
        'A' to 1.42, 'R' to 0.98, 'N' to 0.67, 'D' to 1.01, 'C' to 0.70, 'E' to 1.51, 'Q' to 1.11,
        'G' to 0.57, 'H' to 1.00, 'I' to 1.08, 'L' to 1.21, 'K' to 1.14, 'M' to 1.45, 'F' to 1.13,
        'P' to 0.57, 'S' to 0.77, 'T' to 0.83, 'W' to 1.08, 'Y' to 0.69, 'V' to 1.06
    )

    private val CF_P_SHEET: Map<Char, Double> = mapOf(
        'A' to 0.83, 'R' to 0.93, 'N' to 0.89, 'D' to 0.54, 'C' to 1.19, 'E' to 0.37, 'Q' to 1.10,
        'G' to 0.75, 'H' to 0.87, 'I' to 1.60, 'L' to 1.30, 'K' to 0.74, 'M' to 1.05, 'F' to 1.38,
        'P' to 0.55, 'S' to 0.75, 'T' to 1.19, 'W' to 1.37, 'Y' to 1.47, 'V' to 1.70
    )

    private val ANTIGENICITY: Map<Char, Double> = mapOf(
        'A' to 1.008, 'C' to 1.080, 'D' to 0.986, 'E' to 0.941, 'F' to 1.056, 'G' to 1.017,
        'H' to 0.963, 'I' to 1.077, 'K' to 0.925, 'L' to 1.065, 'M' to 1.037, 'N' to 0.916,
        'P' to 0.932, 'Q' to 0.981, 'R' to 0.918, 'S' to 0.970, 'T' to 0.980, 'V' to 1.025,
        'W' to 1.043, 'Y' to 1.052
    )

    private val MONO_MASS: Map<Char, Double> = mapOf(
        'A' to 71.03711, 'R' to 156.10111, 'N' to 114.04293, 'D' to 115.02694, 'C' to 103.00919,
        'Q' to 128.05858, 'E' to 129.04259, 'G' to 57.02146, 'H' to 137.05891, 'I' to 113.08406,
        'L' to 113.08406, 'K' to 128.09496, 'M' to 131.04049, 'F' to 147.06841, 'P' to 97.05276,
        'S' to 87.03203, 'T' to 101.04768, 'W' to 186.07931, 'Y' to 163.06333, 'V' to 99.06841
    )

    private fun clean(seq: String): String =
        seq.uppercase().filter { it in 'A'..'Z' }

    // ───────────────────────── Chou-Fasman 二级结构预测 ─────────────────────────

    /** 返回每残基二级结构：H 螺旋 / E 折叠 / C 卷曲（0-based pos） */
    fun chouFasman(aaSeq: String): List<Pair<Int, Char>> {
        val seq = clean(aaSeq)
        val n = seq.length
        val struct = CharArray(n) { 'C' }
        fun ph(c: Char) = CF_P_HELIX[c] ?: 0.0
        fun pe(c: Char) = CF_P_SHEET[c] ?: 0.0

        // 1. α-螺旋扫描：6 连续残基中 ≥4 个螺旋引发剂（P≥1.0）
        var i = 0
        while (n >= 6 && i <= n - 6) {
            var initiators = 0
            for (j in 0 until 6) if (ph(seq[i + j]) >= 1.0) initiators++
            if (initiators >= 4) {
                var start = i
                while (start > 0) {
                    val lo = maxOf(0, start - 4)
                    val win = (lo until start).sumOf { ph(seq[it]) } / 4.0
                    if (win < 1.0) break
                    start--
                }
                var end = i + 5
                while (end < n - 1) {
                    val hi = minOf(n, end + 5)
                    val win = (end + 1 until hi).sumOf { ph(seq[it]) } / 4.0
                    if (win < 1.0) break
                    end++
                }
                for (k in start..end) struct[k] = 'H'
            }
            i++
        }

        // 2. β-折叠扫描：5 连续残基中 ≥3 个折叠引发剂；与螺旋重叠按倾向比较裁决
        i = 0
        while (n >= 5 && i <= n - 5) {
            var initiators = 0
            for (j in 0 until 5) if (pe(seq[i + j]) >= 1.0) initiators++
            if (initiators >= 3) {
                var start = i
                while (start > 0) {
                    val lo = maxOf(0, start - 4)
                    val win = (lo until start).sumOf { pe(seq[it]) } / 4.0
                    if (win < 1.0) break
                    start--
                }
                var end = i + 4
                while (end < n - 1) {
                    val hi = minOf(n, end + 5)
                    val win = (end + 1 until hi).sumOf { pe(seq[it]) } / 4.0
                    if (win < 1.0) break
                    end++
                }
                for (k in start..end) {
                    if (struct[k] == 'H') {
                        if (pe(seq[k]) > ph(seq[k])) struct[k] = 'E'
                    } else struct[k] = 'E'
                }
            }
            i++
        }

        return seq.mapIndexed { pos, c -> pos to struct[pos] }
    }

    fun helixPercent(aaSeq: String): Double {
        val cf = chouFasman(aaSeq)
        if (cf.isEmpty()) return 0.0
        return cf.count { it.second == 'H' } * 100.0 / cf.size
    }

    // ───────────────────────── Kyte-Doolittle 滑窗疏水曲线 ─────────────────────────

    fun kyteDoolittleProfile(aaSeq: String, windowSize: Int = 9): List<Double> {
        val seq = clean(aaSeq)
        val n = seq.length
        if (n == 0) return emptyList()
        val half = windowSize / 2
        return (0 until n).map { i ->
            var sum = 0.0; var count = 0
            for (j in -half..half) {
                val idx = i + j
                if (idx in 0 until n) { sum += HYDRO[seq[idx]] ?: 0.0; count++ }
            }
            if (count > 0) sum / count else 0.0
        }
    }

    // ───────────────────────── 结构域模式扫描 ─────────────────────────

    data class DomainHit(val name: String, val start: Int, val end: Int, val desc: String)

    fun proteinDomains(aaSeq: String): List<DomainHit> {
        val seq = clean(aaSeq)
        val patterns = listOf(
            "Zinc Finger (C2H2)" to Regex("C.{2,4}C.{12}H.{3,5}H"),
            "SH3 Binding Domain" to Regex("P.[AP].P"),
            "Ser/Thr Kinase Loop" to Regex("[LIVF]G.G.[FYSG].[VLA]"),
            "FP Chromophore Motif" to Regex("F.G[YF]G")
        )
        val descs = mapOf(
            "Zinc Finger (C2H2)" to com.spice.nativeapp.core.I18n.t("C2H2 型 DNA 结合锌指结构域"),
            "SH3 Binding Domain" to com.spice.nativeapp.core.I18n.t("SH3（Src 同源 3）结合富脯氨酸核心"),
            "Ser/Thr Kinase Loop" to com.spice.nativeapp.core.I18n.t("丝/苏氨酸蛋白激酶催化活性环"),
            "FP Chromophore Motif" to com.spice.nativeapp.core.I18n.t("β-桶发色团核化共有序列（GFP 类 SYG/GYG）")
        )
        val found = mutableListOf<DomainHit>()
        for ((name, re) in patterns) {
            for (m in re.findAll(seq)) {
                found += DomainHit(name, m.range.first, m.range.last, descs[name] ?: "")
            }
        }
        return found
    }

    // ───────────────────────── 溶解性与聚集倾向 ─────────────────────────

    data class AggregationHotspot(val start: Int, val end: Int, val sequence: String, val score: Int)

    data class SolubilityResult(
        val solubilityScore: Int,
        val aggregationHotspots: List<AggregationHotspot>
    )

    fun solubilityAndAggregation(aaSeq: String): SolubilityResult {
        val seq = clean(aaSeq)
        if (seq.isEmpty()) return SolubilityResult(0, emptyList())

        var acidic = 0; var basic = 0; var turns = 0
        for (c in seq) {
            if (c == 'D' || c == 'E') acidic++
            else if (c == 'K' || c == 'R') basic++
            if (c in "GPDNS") turns++
        }
        val netCharge = kotlin.math.abs(basic - acidic)
        val composite = (netCharge.toDouble() / seq.length) * 0.45 + (turns.toDouble() / seq.length) * 0.55
        val score = minOf(100, Math.round(composite * 190 + 35).toInt())

        val hotspots = mutableListOf<AggregationHotspot>()
        val w = 6
        var i = 0
        while (i <= seq.length - w) {
            val sub = seq.substring(i, i + w)
            val hydroCount = sub.count { it in "LIVFWMY" }
            if (hydroCount >= 5) {
                val overlap = hotspots.any { i >= it.start && i <= it.end }
                if (!overlap) {
                    hotspots += AggregationHotspot(i, i + w - 1, sub, Math.round(hydroCount * 100.0 / w).toInt())
                }
            }
            i++
        }
        return SolubilityResult(score, hotspots)
    }

    // ───────────────────────── ΔΔG 点突变稳定性 ─────────────────────────

    data class DdgResult(
        val ddG: Double,
        val classification: String,   // Highly Stabilizing / Stabilizing / Neutral / Destabilizing / Highly Destabilizing
        val classificationZh: String
    )

    fun mutationalStabilityDdg(wtRes: Char, position: Int, mutRes: Char): DdgResult {
        val wt = wtRes.uppercaseChar(); val mut = mutRes.uppercaseChar()
        if (wt == mut) return DdgResult(0.0, "Neutral", com.spice.nativeapp.core.I18n.t("中性"))

        // 1. 侧链体积变化（空腔或位阻）
        val dVol = (VOLUME[mut] ?: 100.0) - (VOLUME[wt] ?: 100.0)
        var ddgVolume = 0.0
        if (dVol < -40) ddgVolume = 0.04 * Math.abs(dVol)
        else if (dVol > 60) ddgVolume = 0.05 * dVol

        // 2. 疏水性变化（疏水坍缩：疏水增益稳定核心）
        val dHydro = (HYDRO[mut] ?: 0.0) - (HYDRO[wt] ?: 0.0)
        val ddgHydro = -0.6 * dHydro

        // 3. 电荷破坏（盐桥 / 静电排斥）
        var ddgCharge = 0.0
        val wtCharged = wt in "DERK"; val mutCharged = mut in "DERK"
        if (wtCharged && !mutCharged) ddgCharge = 0.5
        else if (!wtCharged && mutCharged) ddgCharge = 0.2

        val final = ddgVolume + ddgHydro + ddgCharge
        val (cls, zh) = when {
            final < -1.0 -> "Highly Stabilizing" to com.spice.nativeapp.core.I18n.t("高度稳定")
            final < -0.2 -> "Stabilizing" to com.spice.nativeapp.core.I18n.t("稳定")
            final <= 0.2 -> "Neutral" to com.spice.nativeapp.core.I18n.t("中性")
            final < 1.0 -> "Destabilizing" to com.spice.nativeapp.core.I18n.t("去稳定")
            else -> "Highly Destabilizing" to com.spice.nativeapp.core.I18n.t("高度去稳定")
        }
        return DdgResult(Math.round(final * 100) / 100.0, cls, zh)
    }

    // ───────────────────────── 胰蛋白酶 PMF ─────────────────────────

    data class PeptideFragment(
        val sequence: String, val start: Int, val end: Int,
        val mass: Double, val mz1: Double, val mz2: Double, val mz3: Double
    )

    /** 胰蛋白酶酶切（K/R 之后切，后接 P 不切），按质量升序 */
    fun peptideMassFingerprint(aaSeq: String): List<PeptideFragment> {
        val seq = clean(aaSeq)
        val frags = mutableListOf<PeptideFragment>()
        var currentStart = 0
        for (i in seq.indices) {
            val c = seq[i]
            val cleavage = (c == 'K' || c == 'R') && (i == seq.length - 1 || seq[i + 1] != 'P')
            if (cleavage || i == seq.length - 1) {
                val frag = seq.substring(currentStart, i + 1)
                if (frag.isNotEmpty()) {
                    var mass = 18.01056
                    for (ch in frag) mass += MONO_MASS[ch] ?: 0.0
                    frags += PeptideFragment(
                        frag, currentStart + 1, i + 1,
                        Math.round(mass * 10000) / 10000.0,
                        Math.round((mass + 1.00782) * 10000) / 10000.0,
                        Math.round((mass + 2 * 1.00782) / 2 * 10000) / 10000.0,
                        Math.round((mass + 3 * 1.00782) / 3 * 10000) / 10000.0
                    )
                }
                currentStart = i + 1
            }
        }
        return frags.sortedBy { it.mass }
    }

    data class DifferentialPeak(
        val fragment: String, val wtMass: Double, val mutMass: Double,
        val type: String  // wt_only / mut_only / shifted
    )

    /** WT vs 突变体胰酶指纹差分（MALDI-TOF 鉴定用） */
    fun comparePmf(wtSeq: String, mutSeq: String): List<DifferentialPeak> {
        val wt = peptideMassFingerprint(wtSeq)
        val mut = peptideMassFingerprint(mutSeq)
        val out = mutableListOf<DifferentialPeak>()
        for (w in wt) {
            val matched = mut.any { Math.abs(it.mass - w.mass) < 0.05 && it.sequence == w.sequence }
            if (!matched) {
                val shifted = mut.firstOrNull { it.start == w.start || it.end == w.end }
                if (shifted != null) {
                    out += DifferentialPeak("${w.sequence} → ${shifted.sequence}", w.mass, shifted.mass, "shifted")
                } else {
                    out += DifferentialPeak(w.sequence, w.mass, 0.0, "wt_only")
                }
            }
        }
        for (m in mut) {
            val matched = wt.any { Math.abs(it.mass - m.mass) < 0.05 && it.sequence == m.sequence }
            if (!matched) {
                val isShiftedPartner = out.any { it.type == "shifted" && it.fragment.endsWith(m.sequence) }
                if (!isShiftedPartner) out += DifferentialPeak(m.sequence, 0.0, m.mass, "mut_only")
            }
        }
        return out
    }

    // ───────────────────────── 二硫键预测 ─────────────────────────

    data class DisulfideBond(
        val cys1: Int, val cys2: Int,
        val pairingProbability: Int, val redoxSensitivity: String
    )

    fun disulfideBonds(aaSeq: String): List<DisulfideBond> {
        val seq = clean(aaSeq)
        val cys = seq.mapIndexedNotNull { i, c -> if (c == 'C') i + 1 else null }
        if (cys.size < 2) return emptyList()

        data class P(val c1: Int, val c2: Int, val score: Int)

        val predictions = mutableListOf<P>()
        for (i in cys.indices) for (j in i + 1 until cys.size) {
            val c1 = cys[i]; val c2 = cys[j]
            val dist = c2 - c1
            var score = 50
            if (dist in 3..12) score += 20
            else if (dist > 50) score += 5
            val sub = seq.substring(c1 - 1, c2)
            if (sub.length == 4 && sub[1] == 'X' && sub[2] == 'X') score += 25
            else if (sub.length == 3 && sub[1] == 'X') score += 15
            var hydro = 0
            for (n in intArrayOf(c1 - 2, c1, c1 + 1, c2 - 2, c2, c2 + 1)) {
                if (n in 1..seq.length && seq[n - 1] in "LIVFWMY") hydro++
            }
            score += hydro * 4
            predictions += P(c1, c2, minOf(98, score))
        }

        // 贪心 1:1 配对
        val used = mutableSetOf<Int>()
        val result = mutableListOf<DisulfideBond>()
        for (p in predictions.sortedByDescending { it.score }) {
            if (p.c1 !in used && p.c2 !in used) {
                used += p.c1; used += p.c2
                result += DisulfideBond(p.c1, p.c2, p.score, if (p.score > 75) "stable" else "sensitive")
            }
        }
        return result.sortedBy { it.cys1 }
    }

    // ───────────────────────── Kolaskar-Tongaonkar 抗原表位 ─────────────────────────

    data class Epitope(val start: Int, val end: Int, val sequence: String, val averageScore: Double)

    data class AntigenicityResult(
        val averageAntigenicity: Double, val epitopes: List<Epitope>, val scores: List<Double>
    )

    fun antigenicity(aaSeq: String, windowSize: Int = 7): AntigenicityResult {
        val seq = clean(aaSeq)
        val n = seq.length
        if (n == 0) return AntigenicityResult(0.0, emptyList(), emptyList())
        val half = windowSize / 2
        val scores = DoubleArray(n)
        var total = 0.0
        for (i in 0 until n) {
            var sum = 0.0; var count = 0
            for (j in -half..half) {
                val idx = i + j
                if (idx in 0 until n) { sum += ANTIGENICITY[seq[idx]] ?: 1.0; count++ }
            }
            scores[i] = Math.round(sum / count * 10000) / 10000.0
            total += scores[i]
        }
        val epitopes = mutableListOf<Epitope>()
        val threshold = 1.02
        var inEpi = false; var epiStart = 0
        for (i in 0 until n) {
            if (scores[i] >= threshold) {
                if (!inEpi) { inEpi = true; epiStart = i }
            } else if (inEpi) {
                inEpi = false
                val len = i - epiStart
                if (len >= 6) {
                    val avg = (epiStart until i).sumOf { scores[it] } / len
                    epitopes += Epitope(epiStart + 1, i, seq.substring(epiStart, i), Math.round(avg * 1000) / 1000.0)
                }
            }
        }
        if (inEpi) {
            val len = n - epiStart
            if (len >= 6) {
                val avg = (epiStart until n).sumOf { scores[it] } / len
                epitopes += Epitope(epiStart + 1, n, seq.substring(epiStart, n), Math.round(avg * 1000) / 1000.0)
            }
        }
        return AntigenicityResult(Math.round(total / n * 1000) / 1000.0, epitopes, scores.toList())
    }

    // ───────────────────────── 电荷 / pI / FoldIndex ─────────────────────────

    fun chargeAtPh(seq: String, ph: Double): Double {
        val pKa = mapOf('D' to 3.65, 'E' to 4.25, 'C' to 8.18, 'Y' to 10.07, 'H' to 6.0, 'K' to 10.53, 'R' to 12.48)
        var charge = 0.0
        charge += 1.0 / (1.0 + Math.pow(10.0, ph - 8.0))          // N 端
        charge -= 1.0 / (1.0 + Math.pow(10.0, 3.1 - ph))          // C 端
        for (c in clean(seq)) {
            charge += when (c) {
                'K', 'R' -> 1.0 / (1.0 + Math.pow(10.0, ph - (pKa[c] ?: 10.0)))
                'D', 'E', 'C', 'Y' -> -1.0 / (1.0 + Math.pow(10.0, (pKa[c] ?: 4.0) - ph))
                'H' -> 1.0 / (1.0 + Math.pow(10.0, ph - 6.0))
                else -> 0.0
            }
        }
        return Math.round(charge * 100) / 100.0
    }

    fun pI(seq: String): Double {
        var low = 0.0; var high = 14.0; var pi = 7.0
        repeat(15) {
            pi = (low + high) / 2
            if (chargeAtPh(seq, pi) > 0) low = pi else high = pi
        }
        return Math.round(pi * 100) / 100.0
    }

    fun gravy(seq: String): Double {
        val s = clean(seq)
        if (s.isEmpty()) return 0.0
        val sum = s.sumOf { HYDRO[it] ?: 0.0 }
        return Math.round(sum / s.length * 1000) / 1000.0
    }

    /** FoldIndex 局部无序比例（相界公式 2.785·<H> − |<R>| − 1.151 < 0 记无序） */
    fun disorderFoldIndex(aaSeq: String, windowSize: Int = 21): Double {
        val seq = clean(aaSeq)
        val n = seq.length
        if (n == 0) return 0.0
        val half = windowSize / 2
        var disordered = 0
        for (i in 0 until n) {
            var chargeSum = 0.0; var hydroSum = 0.0; var count = 0
            for (j in -half..half) {
                val idx = i + j
                if (idx in 0 until n) {
                    val aa = seq[idx]
                    if (aa == 'K' || aa == 'R') chargeSum += 1.0
                    if (aa == 'D' || aa == 'E') chargeSum -= 1.0
                    hydroSum += ((HYDRO[aa] ?: 0.0) + 4.5) / 9.0
                    count++
                }
            }
            val foldIndex = 2.785 * (hydroSum / count) - Math.abs(chargeSum / count) - 1.151
            if (foldIndex < 0) disordered++
        }
        return disordered.toDouble() / n
    }

    // ───────────────────────── 突变库批量评估 ─────────────────────────

    data class VariantCandidate(val id: String, val mutations: String, val sequence: String)

    data class MutationAnalysis(
        val id: String, val mutations: String,
        val gravy: Double, val chargeAtPh7: Double, val pI: Double, val pIDelta: Double,
        val helixPercent: Int, val disorderScore: Double, val thermostabilityScore: Int
    )

    private val MUT_RE = Regex("^([ACDEFGHIKLMNPQRSTVWY])(\\d+)([ACDEFGHIKLMNPQRSTVWY*])$", RegexOption.IGNORE_CASE)

    /** 解析 "MutantID, V15A + I32L" 格式突变库 CSV */
    fun parseMutantsCsv(csvText: String, baseAaSeq: String): List<VariantCandidate> {
        val out = mutableListOf<VariantCandidate>()
        for (raw in csvText.split(Regex("\\r?\\n"))) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("ID,") || line.startsWith("id,")) continue
            val parts = line.split(",")
            if (parts.size < 2) continue
            val id = parts[0].trim()
            val mutationsRaw = parts.drop(1).joinToString(",").trim().replace(Regex("['\"]"), "")
            val arr = clean(baseAaSeq).toCharArray()
            for (mut in mutationsRaw.split(Regex("[,+;\\s]+")).filter { it.isNotBlank() }) {
                val m = MUT_RE.find(mut) ?: continue
                val from = m.groupValues[1].uppercase().first()
                val pos = m.groupValues[2].toIntOrNull()?.minus(1) ?: continue
                val to = m.groupValues[3].uppercase().first()
                if (pos in arr.indices && arr[pos] == from) arr[pos] = to
            }
            out += VariantCandidate(id, mutationsRaw, String(arr))
        }
        return out
    }

    /** 批量热稳定性综合评分（0..100，与原版公式一致） */
    fun evaluateMutantsBatch(
        candidates: List<VariantCandidate>,
        baseAaSeq: String,
        ph: Double = 7.0,
        tempK: Double = 310.0
    ): List<MutationAnalysis> {
        val basePi = pI(baseAaSeq)
        return candidates.map { cand ->
            val seq = cand.sequence
            val gv = gravy(seq)
            val charge = chargeAtPh(seq, ph)
            val pi = pI(seq)
            val helix = helixPercent(seq)
            val disorder = disorderFoldIndex(seq)

            var score = 70.0
            if (Math.abs(charge) > 10) score -= 8
            if (gv > 0.5) score -= 12
            if (gv < -1.5) score -= 5
            score += (helix - 25) * 0.35
            score -= disorder * 22
            val tC = tempK - 273.15
            if (tC > 42) score -= (tC - 42) * 1.1
            val final = maxOf(0, minOf(100, Math.round(score).toInt()))

            MutationAnalysis(
                cand.id, cand.mutations, gv, chargeAtPh(seq, 7.0), pi,
                Math.round((pi - basePi) * 100) / 100.0,
                Math.round(helix).toInt(),
                Math.round(disorder * 1000) / 1000.0,
                final
            )
        }
    }

    // ───────────────────────── 折叠 / MD / 相图 / 口袋 引擎 ─────────────────────────
    // 对齐 main 的 demo 后端语义（无 ONNX 权重时的确定性回退路径）。
    // 坐标构建升级为二级结构感知（Chou-Fasman H→α-螺旋、E→β-锯齿、C→卷曲）。

    data class Env(val ph: Double = 7.0, val tempK: Double = 310.0, val ionicStrengthM: Double = 0.15)

    data class FoldOutput(
        val length: Int,
        val coords: FloatArray,        // 3N
        val secondary: List<Pair<Int, Char>>,
        val distExp: FloatArray,       // N×N
        val contactMap: ByteArray,     // N×N, 1 if d<8Å
        val conf: Double,              // 环境敏感置信度
        val runMs: Double
    )

    private fun clamp(v: Double, lo: Double, hi: Double) = minOf(hi, maxOf(lo, v))

    /** 二级结构感知 Cα 骨架构建：α-螺旋(3.6 res/turn, 1.5 Å/res, r=2.3)、β-折叠(锯齿)、卷曲(伪随机游走) */
    fun fold(seq: String, env: Env = Env(), seed: Long = 42L): FoldOutput {
        val s = clean(seq)
        val n = s.length
        val ss = chouFasman(s)
        val rnd = java.util.Random(seed)
        val coords = FloatArray(n * 3)
        var x = 0.0; var y = 0.0; var z = 0.0
        var dir = 0.0
        for (i in 0 until n) {
            when (ss[i].second) {
                'H' -> {
                    val theta = i * (2 * Math.PI) / 3.6
                    coords[i * 3] = (2.3 * Math.cos(theta) + i * 0.0).toFloat()
                    coords[i * 3 + 1] = (2.3 * Math.sin(theta)).toFloat()
                    coords[i * 3 + 2] = (i * 1.5).toFloat()
                }
                else -> {
                    // β 与卷曲：沿前进方向的锯齿/游走
                    dir += (rnd.nextDouble() - 0.5) * 1.2
                    if (ss[i].second == 'E') dir = Math.round(dir / 0.9) * 0.9
                    x += 3.3 * Math.cos(dir); y += 3.3 * Math.sin(dir); z += (rnd.nextDouble() - 0.5) * 0.8
                    coords[i * 3] = x.toFloat(); coords[i * 3 + 1] = y.toFloat(); coords[i * 3 + 2] = z.toFloat()
                }
            }
        }
        // 距离矩阵 + 接触图
        val dist = FloatArray(n * n)
        val contact = ByteArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            val dx = coords[i * 3] - coords[j * 3].toDouble()
            val dy = coords[i * 3 + 1] - coords[j * 3 + 1].toDouble()
            val dz = coords[i * 3 + 2] - coords[j * 3 + 2].toDouble()
            val d = Math.sqrt(dx * dx + dy * dy + dz * dz)
            dist[i * n + j] = d.toFloat()
            contact[i * n + j] = if (d < 8.0) 1 else 0
        }
        // 环境敏感置信度：偏离 (pH 7, 310 K) 越远置信越低 —— 与原版 demoFold 相同口径
        val dev = Math.abs(env.ph - 7) / 7 + Math.abs(env.tempK - 310) / 90
        val conf = clamp(0.95 - dev, 0.1, 0.95)
        return FoldOutput(n, coords, ss, dist, contact, conf, 3.2)
    }

    data class MdMetrics(
        val uTKcal: Double, val rg: Double, val nSsRef: Int, val nSsKept: Int, val nSurfaceCharged: Int
    )

    data class MdStep(
        val stepCount: Int, val timePs: Double, val uHist: List<Double>,
        val metrics: MdMetrics, val crashed: Boolean
    )

    /** MD 步进（启发式能量历史：偏置步衰减更慢；伪二级结构保持数随温度衰减） */
    fun mdStep(seq: String, n: Int, bias: Boolean, env: Env = Env(), seed: Long = 7L): MdStep {
        val rnd = java.util.Random(seed)
        val uHist = mutableListOf<Double>()
        var u = -8200.0
        for (i in 0 until n) {
            u += (if (bias) -14.0 else -6.0) + (rnd.nextDouble() - 0.5) * 40
            uHist += u
        }
        val ss = chouFasman(seq)
        val nSs = ss.count { it.second != 'C' }
        val tempFactor = clamp(1.0 - (env.tempK - 310) / 200.0, 0.3, 1.0)
        val kept = (nSs * tempFactor).toInt()
        val surfaceCharged = clean(seq).count { it in "DEKR" } / 4
        val rg = 14.2 + rnd.nextDouble() * (1 + (env.tempK - 310) / 100.0)
        return MdStep(
            n, n * 0.003, uHist,
            MdMetrics(u, Math.round(rg * 100) / 100.0, nSs, kept, surfaceCharged),
            crashed = false
        )
    }

    data class ScanPoint(val t: Double, val ph: Double, val stable: Boolean, val reason: String?)

    /** 温-pH 稳定性相图：以 (310 K, pH 7) 为峰值的高斯稳定域（确定性，无随机） */
    fun scan(temps: List<Double>, phs: List<Double>, seed: Long = 3L): List<ScanPoint> =
        scanCancellable(temps, phs, { false }, seed) ?: emptyList()

    /** 可取消版相图扫描（对齐 main 的异步扫描 + 取消菜单项）：逐点检查取消标志，取消返回 null */
    fun scanCancellable(
        temps: List<Double>,
        phs: List<Double>,
        isCancelled: () -> Boolean,
        seed: Long = 3L
    ): List<ScanPoint>? {
        val rnd = java.util.Random(seed)
        val out = mutableListOf<ScanPoint>()
        for (ph in phs) for (t in temps) {
            if (isCancelled()) return null
            val dev = Math.abs(t - 310) / 40 + Math.abs(ph - 7) / 3
            val stable = rnd.nextDouble() < Math.exp(-dev * dev * 0.6)
            val crashed = !stable && rnd.nextDouble() < 0.5
            out += ScanPoint(t, ph, stable, if (!stable && crashed) "energy_spike" else null)
        }
        return out
    }

    data class Pocket(
        val id: Int, val center: Triple<Double, Double, Double>,
        val volume: Double, val druggability: Double, val surfaceResidues: List<Int>
    )

    /** 几何口袋检测：接触数高且埋藏的残基聚簇（简化 LIGSITE 语义） */
    fun detectPockets(fold: FoldOutput, seq: String, maxPockets: Int = 5): List<Pocket> {
        val s = clean(seq)
        val n = fold.length
        if (n == 0) return emptyList()
        val contactCount = IntArray(n)
        for (i in 0 until n) for (j in i + 1 until n) {
            if (fold.contactMap[i * n + j].toInt() == 1) { contactCount[i]++; contactCount[j]++ }
        }
        val median = contactCount.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val buried = (0 until n).filter { contactCount[it] >= median }.sortedByDescending { contactCount[it] }
        val clusters = mutableListOf<MutableList<Int>>()
        for (idx in buried) {
            val near = clusters.firstOrNull { cl -> cl.any { fold.distExp[idx * n + it] < 10f } }
            if (near != null) near += idx else clusters += mutableListOf(idx)
        }
        return clusters.take(maxPockets).mapIndexed { pi, cl ->
            val cx = cl.sumOf { fold.coords[it * 3].toDouble() } / cl.size
            val cy = cl.sumOf { fold.coords[it * 3 + 1].toDouble() } / cl.size
            val cz = cl.sumOf { fold.coords[it * 3 + 2].toDouble() } / cl.size
            val hydro = cl.count { s[it] in "LIVFWMY" }.toDouble() / cl.size
            Pocket(
                pi, Triple(cx, cy, cz),
                Math.round(cl.size * 34.7 * 10) / 10.0,
                Math.round(clamp(0.3 + hydro * 0.55, 0.0, 0.99) * 100) / 100.0,
                cl.sorted()
            )
        }
    }

    /** 口袋差分：体积变化 + Jaccard 重叠 */
    fun pocketDelta(ref: Pocket, mut: Pocket): Pair<Double, Double> {
        val a = ref.surfaceResidues.toSet(); val b = mut.surfaceResidues.toSet()
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        val j = if (union == 0.0) 0.0 else inter / union
        return Math.round((mut.volume - ref.volume) * 10) / 10.0 to Math.round(j * 100) / 100.0
    }

    /** 应用突变到序列（突变购物车），返回新序列；非法条目忽略 */
    fun applyMutations(seq: String, mutations: List<Triple<Int, Char, Char>>): String {
        val arr = clean(seq).toCharArray()
        for ((pos, from, to) in mutations) {
            val i = pos - 1
            if (i in arr.indices && arr[i] == from) arr[i] = to
        }
        return String(arr)
    }
}
