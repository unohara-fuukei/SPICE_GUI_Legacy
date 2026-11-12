package com.spice.nativeapp.core

/** 引物设计结果 */
data class PrimerPair(
    val forward: String,
    val reverse: String,
    val tmF: Double,
    val tmR: Double,
    val gcF: Double,
    val gcR: Double,
    val productLength: Int,
    val warnings: List<String>
)

object Cloning {

    /** Wallace 规则 Tm = 2(A+T) + 4(G+C) */
    fun tmWallace(primer: String): Double {
        val at = primer.count { it == 'A' || it == 'T' }
        val gc = primer.count { it == 'G' || it == 'C' }
        return 2.0 * at + 4.0 * gc
    }

    /** 最近邻 Tm（SantaLucia 简化）：用于 14 bp 以上引物 */
    fun tmNearest(primer: String, naConc: Double = 50.0, primerConc: Double = 0.25): Double {
        if (primer.length < 2) return 0.0
        // 简化 ΔH / ΔS（kcal/mol, cal/mol·K）按二核苷酸平均
        val dh = mapOf(
            "AA" to -7.9, "AT" to -7.2, "AC" to -8.4, "AG" to -7.8,
            "TA" to -7.2, "TT" to -7.9, "TC" to -8.2, "TG" to -8.5,
            "CA" to -8.5, "CT" to -7.8, "CC" to -8.0, "CG" to -10.6,
            "GA" to -8.2, "GT" to -8.4, "GC" to -9.8, "GG" to -8.0
        )
        val ds = mapOf(
            "AA" to -22.2, "AT" to -20.4, "AC" to -22.4, "AG" to -21.0,
            "TA" to -21.3, "TT" to -22.2, "TC" to -21.3, "TG" to -22.7,
            "CA" to -22.7, "CT" to -21.0, "CC" to -19.9, "CG" to -27.2,
            "GA" to -22.2, "GT" to -21.2, "GC" to -24.4, "GG" to -19.9
        )
        var sumH = 0.0
        var sumS = 0.0
        for (i in 0 until primer.length - 1) {
            val k = primer.substring(i, i + 2).uppercase()
            sumH += dh[k] ?: -8.0
            sumS += ds[k] ?: -21.0
        }
        // 起始修正
        sumH += 0.2
        sumS += -5.7
        val r = 1.987
        val ct = primerConc * 1e-9
        val salt = (naConc + 120.0) * 1e-3
        return (sumH * 1000.0) / (sumS + r * Math.log(ct / 4.0)) - 273.15 +
            16.6 * Math.log10(salt)
    }

    fun gcPercent(primer: String): Double =
        if (primer.isEmpty()) 0.0 else primer.count { it == 'G' || it == 'C' } * 100.0 / primer.length

    fun hasGcClamp(primer: String): Boolean {
        val tail = primer.takeLast(5)
        return tail.count { it == 'G' || it == 'C' } >= 2
    }

    /** 3' 端互补（自身二聚体 / 发夹风险粗筛） */
    private fun dimerScore(a: String, b: String): Int {
        val x = a.uppercase()
        val y = Bio.revComp(b.uppercase())
        var best = 0
        for (i in x.indices) for (j in y.indices) {
            var k = 0
            while (i + k < x.length && j + k < y.length && (x[i + k] == y[j + k] ||
                    (x[i + k] == 'A' && y[j + k] == 'T') || (x[i + k] == 'T' && y[j + k] == 'A') ||
                    (x[i + k] == 'G' && y[j + k] == 'C') || (x[i + k] == 'C' && y[j + k] == 'G'))
            ) k++
            if (k > best) best = k
        }
        return best
    }

    /**
     * 针对给定模板序列与目标区域设计一对引物。
     * @param template 模板序列
     * @param start 1-based 起点
     * @param end 1-based 终点（含）
     */
    fun designPrimers(template: String, start: Int, end: Int, primerLen: Int = 20): PrimerPair? {
        if (template.isEmpty()) return null
        val s = start.coerceIn(1, template.length)
        val e = end.coerceIn(s, template.length)
        val fwd = template.substring(s - 1, minOf(s - 1 + primerLen, template.length))
        val rev = Bio.revComp(template.substring(maxOf(e - primerLen, 0), e))
        val warnings = mutableListOf<String>()
        val tmF = if (fwd.length < 14) tmWallace(fwd) else tmNearest(fwd)
        val tmR = if (rev.length < 14) tmWallace(rev) else tmNearest(rev)
        val gcF = gcPercent(fwd)
        val gcR = gcPercent(rev)
        if (gcF < 40 || gcF > 60) warnings.add("正向引物 GC 含量 ${"%.0f".format(gcF)}%，建议 40–60%")
        if (gcR < 40 || gcR > 60) warnings.add("反向引物 GC 含量 ${"%.0f".format(gcR)}%，建议 40–60%")
        if (!hasGcClamp(fwd)) warnings.add("正向引物 3' 端 GC clamp 不足")
        if (!hasGcClamp(rev)) warnings.add("反向引物 3' 端 GC clamp 不足")
        if (Math.abs(tmF - tmR) > 5) warnings.add("两条引物 Tm 相差 ${"%.1f".format(Math.abs(tmF - tmR))} ℃，建议 ≤5 ℃")
        if (dimerScore(fwd, rev) >= 6) warnings.add("引物间存在 ≥6 bp 互补，可能形成二聚体")
        return PrimerPair(fwd, rev, tmF, tmR, gcF, gcR, e - s + 1, warnings)
    }

    /** Gibson 同源臂设计：返回 (上游片段尾端同源臂, 下游片段首端同源臂) */
    fun gibsonHomology(backbone: String, insert: String, armLen: Int = 20): Pair<String, String> {
        val up = backbone.takeLast(armLen.coerceAtMost(backbone.length))
        val down = backbone.take(armLen.coerceAtMost(backbone.length))
        return up to down
    }

    /** Golden Gate 位点筛查 */
    fun goldenGateSites(seq: String, enzyme: String = "BsaI"): List<Int> {
        val e = Enzymes.byName(enzyme) ?: return emptyList()
        return Bio.cutSites(seq, e, true)
    }

    /** 摩尔浓度换算：ng DNA → fmol（dsDNA） */
    fun ngToFmol(ng: Double, lengthBp: Int): Double {
        if (lengthBp <= 0) return 0.0
        val mwPerBp = 650.0
        return ng / (lengthBp * mwPerBp) * 1e6
    }
}
