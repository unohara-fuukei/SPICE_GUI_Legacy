package com.spice.nativeapp.core

import kotlin.math.log
import kotlin.math.pow

/**
 * 1:1 移植 main `src/lib/genome/primerAnalysis.ts` 的 analyzePrimer 口径
 * （PrimerTable.svelte 分析卡所用）：发夹 / 自二聚体 / 交叉二聚体、poly-X、
 * GC clamp、N 计数、最大同聚物，以及 0–100 质量评分与告警。
 *
 * 与既有 [PrimerAnalysis]（SantaLucia 最近邻 + 3′ 稳定性，服务旧精细分析块）
 * 为两套并行模型；本文件严格对齐 main 的字段口径与评分规则，供分析卡复用。
 *
 * Tm 采用 main `tm.ts` calculateNebTm：SantaLucia 最近邻焓熵 + 单价阳离子盐修正；
 * 序列含简并碱基（N）时 main 抛错、Tm 取 0（本实现同构返回 0.0）。
 */
object PrimerAnalysisMain {

    data class HairpinResult(
        val position: Int,
        val stemLength: Int,
        val loopLength: Int,
        val meltingTemp: Double,
        val sequence: String,
        val structure: String
    )

    data class DimerResult(
        val position: Int,
        val length: Int,
        val meltingTemp: Double,
        val structure: String
    )

    data class PolyX(val base: String, val length: Int, val position: Int)

    data class Analysis(
        val sequence: String,
        val length: Int,
        val gcContent: Double,
        val tm: Double,
        val hairpins: List<HairpinResult>,
        val selfDimers: List<DimerResult>,
        val crossDimers: List<DimerResult>,
        val polyX: List<PolyX>,
        val gcClamp: Boolean,
        val gcClamp5: Boolean,
        val nBaseCount: Int,
        val maxHomopolymer: Int,
        val score: Int,
        val warnings: List<String>
    )

    private val DNA_PAIRS = mapOf('A' to 'T', 'T' to 'A', 'G' to 'C', 'C' to 'G')
    private fun canPair(a: Char, b: Char): Boolean = DNA_PAIRS[a.uppercaseChar()] == b.uppercaseChar()

    private val DH = mapOf(
        "AA/TT" to -7.9, "AT/TA" to -7.2, "TA/AT" to -7.2, "CA/GT" to -8.5,
        "GT/CA" to -8.4, "CT/GA" to -7.8, "GA/CT" to -8.2, "CG/GC" to -10.6,
        "GC/CG" to -9.8, "GG/CC" to -8.0, "TT/AA" to -7.9, "TG/AC" to -8.5,
        "AC/TG" to -8.4, "AG/TC" to -7.8, "TC/AG" to -8.2, "CC/GG" to -8.0
    )
    private val DS = mapOf(
        "AA/TT" to -22.2, "AT/TA" to -20.4, "TA/AT" to -21.3, "CA/GT" to -22.7,
        "GT/CA" to -22.4, "CT/GA" to -21.0, "GA/CT" to -22.2, "CG/GC" to -27.2,
        "GC/CG" to -24.4, "GG/CC" to -19.9, "TT/AA" to -22.2, "TG/AC" to -22.7,
        "AC/TG" to -22.4, "AG/TC" to -21.0, "TC/AG" to -22.2, "CC/GG" to -19.9
    )
    private const val R = 1.987
    private const val K2C = -273.15
    private const val C2K = 273.15
    private const val KCAL = 1000.0

    private fun gcPercent(bps: String): Double =
        if (bps.isEmpty()) 0.0 else bps.count { it == 'G' || it == 'C' } * 100.0 / bps.length

    /** main calculateNebTm：含 N/非 ATCG → main 返回错误串、Tm 取 0，此处同构返回 0.0。 */
    fun nebTm(sequence: String, monovalentCationConc: Double = 0.05, primerConc: Double = 0.0000005): Double {
        if (!sequence.all { it in "ATCG" }) return 0.0
        val seq = sequence.uppercase().toList()
        var h = 0.0; var s = 0.0; var hi = 0.0; var si = 0.0
        for (i in seq.indices) {
            if (i == 0 || i == seq.size - 1) {
                if (seq[i] == 'G' || seq[i] == 'C') { hi += 0.1; si += -2.8 }
                else if (seq[i] == 'A' || seq[i] == 'T') { hi += 2.3; si += 4.1 }
            }
            if (i < seq.size - 1) {
                val dimer = "${seq[i]}${seq[i + 1]}"
                val comp = "${DNA_PAIRS[seq[i]]}${DNA_PAIRS[seq[i + 1]]}"
                val duplex = "$dimer/$comp"
                val dh = DH[duplex] ?: return 0.0
                val ds = DS[duplex] ?: return 0.0
                h += dh; s += ds
            }
        }
        val deltaH = h + hi
        val deltaS = s + si
        val mt = deltaH * KCAL / (deltaS + R * log(primerConc, Math.E)) + K2C
        if (monovalentCationConc > 0) {
            val lnMono = log(monovalentCationConc, Math.E)
            val gc = gcPercent(sequence) / 100.0
            val part = 4.29 * gc - 3.95
            val saltCorrection = part * 10.0.pow(-5) * lnMono + 9.4.pow(-6) * lnMono.pow(2)
            return 1.0 / (1.0 / (mt + C2K) + saltCorrection) + K2C
        }
        return mt
    }

    fun findHairpins(seq: String, minStem: Int = 4, maxLoop: Int = 30): List<HairpinResult> {
        val results = mutableListOf<HairpinResult>()
        val s = seq.uppercase()
        var i = 0
        while (i < s.length - minStem * 2 - 3) {
            var stem = minStem
            while (stem <= 12 && i + stem * 2 + 3 <= s.length) {
                var loop = 3
                loop@ while (loop <= maxLoop && i + stem + loop + stem <= s.length) {
                    val left = s.substring(i, i + stem)
                    val right = s.substring(i + stem + loop, i + stem + loop + stem)
                    var match = true
                    for (k in 0 until stem) {
                        if (!canPair(left[k], right[stem - 1 - k])) { match = false; break }
                    }
                    if (match) {
                        val hairpinSeq = s.substring(i, i + stem + loop + stem)
                        val tm = nebTm(left)
                        val structure = "${left}(${s.substring(i + stem, i + stem + loop)})${right}"
                        results += HairpinResult(i, stem, loop, tm, hairpinSeq, structure)
                        break@loop // 每个茎长只取首个环长匹配
                    }
                    loop++
                }
                stem++
            }
            i++
        }
        results.sortByDescending { it.stemLength }
        return results.take(10)
    }

    fun findSelfDimers(seq: String, minLength: Int = 4): List<DimerResult> {
        val results = mutableListOf<DimerResult>()
        val s = seq.uppercase()
        val rc = Bio.revComp(s)
        var offset = -s.length + minLength
        while (offset < s.length - minLength) {
            var matchCount = 0; var matchStart = 0; var inMatch = false
            for (i in s.indices) {
                val j = i - offset
                if (j in rc.indices && canPair(s[i], rc[j])) {
                    if (!inMatch) { matchStart = i; inMatch = true }
                    matchCount++
                } else {
                    if (inMatch && matchCount >= minLength) {
                        val tm = nebTm(s.substring(matchStart, matchStart + matchCount))
                        results += DimerResult(offset, matchCount, tm, "offset $offset, ${matchCount}bp")
                    }
                    matchCount = 0; inMatch = false
                }
            }
            if (inMatch && matchCount >= minLength) {
                val tm = nebTm(s.substring(matchStart, matchStart + matchCount))
                results += DimerResult(offset, matchCount, tm, "offset $offset, ${matchCount}bp")
            }
            offset++
        }
        results.sortByDescending { it.length }
        return results.take(5)
    }

    fun findCrossDimers(fwd: String, rev: String, minLength: Int = 4): List<DimerResult> {
        val results = mutableListOf<DimerResult>()
        val f = fwd.uppercase(); val r = rev.uppercase()
        var offset = -f.length + minLength
        while (offset < r.length - minLength) {
            var matchCount = 0; var matchStart = 0; var inMatch = false
            for (i in f.indices) {
                val j = i - offset
                if (j in r.indices && canPair(f[i], r[j])) {
                    if (!inMatch) { matchStart = i; inMatch = true }
                    matchCount++
                } else {
                    if (inMatch && matchCount >= minLength) {
                        val tm = nebTm(f.substring(matchStart, matchStart + matchCount))
                        results += DimerResult(offset, matchCount, tm, "offset $offset, ${matchCount}bp")
                    }
                    matchCount = 0; inMatch = false
                }
            }
            if (inMatch && matchCount >= minLength) {
                val tm = nebTm(f.substring(matchStart, matchStart + matchCount))
                results += DimerResult(offset, matchCount, tm, "offset $offset, ${matchCount}bp")
            }
            offset++
        }
        results.sortByDescending { it.length }
        return results.take(5)
    }

    fun analyzePrimer(seq: String): Analysis {
        val s = seq.uppercase().replace(Regex("[^ATCGN]"), "")
        val warnings = mutableListOf<String>()

        val gc = gcPercent(s)
        val tmNum = nebTm(s)

        val hairpins = findHairpins(s)
        val selfDimers = findSelfDimers(s)
        val crossDimers: List<DimerResult> = emptyList()

        // Poly-X runs
        val polyX = mutableListOf<PolyX>()
        if (s.isNotEmpty()) {
            var currentBase = s[0].toString(); var currentLen = 1; var currentStart = 0
            for (i in 1 until s.length) {
                if (s[i].toString() == currentBase) currentLen++
                else {
                    if (currentLen >= 4) polyX += PolyX(currentBase, currentLen, currentStart)
                    currentBase = s[i].toString(); currentLen = 1; currentStart = i
                }
            }
            if (currentLen >= 4) polyX += PolyX(currentBase, currentLen, currentStart)
        }

        val gcClamp = s.endsWith("G") || s.endsWith("C")
        val gcClamp5 = s.startsWith("G") || s.startsWith("C")
        val nBaseCount = s.count { it == 'N' }

        var maxHomo = 1; var homoLen = 1
        for (i in 1 until s.length) {
            if (s[i] == s[i - 1]) { homoLen++; if (homoLen > maxHomo) maxHomo = homoLen } else homoLen = 1
        }

        var score = 100
        if (gc < 30 || gc > 70) { score -= 20; warnings += I18n.tf("GC 含量异常: {v1}%", "%.1f".format(gc)) }
        if (tmNum < 50 || tmNum > 75) { score -= 15; warnings += I18n.tf("Tm 值偏离范围: {v1}°C", "%.1f".format(tmNum)) }
        if (hairpins.isNotEmpty()) { score -= 10 * hairpins.size; warnings += I18n.tf("发现 {v1} 个发夹结构", hairpins.size) }
        if (selfDimers.isNotEmpty()) { score -= 8 * selfDimers.size; warnings += I18n.tf("发现 {v1} 个自二聚体", selfDimers.size) }
        if (polyX.isNotEmpty()) { score -= 5 * polyX.size; warnings += I18n.tf("发现 {v1} 个多聚重复", polyX.size) }
        if (!gcClamp) { score -= 5; warnings += I18n.t("3' 端无 GC clamp") }
        if (maxHomo > 5) { score -= 5; warnings += I18n.tf("最大同聚物长度: {v1}", maxHomo) }
        if (s.length < 15 || s.length > 35) { score -= 10; warnings += I18n.tf("引物长度异常 ({v1} bp), 建议在 15-35 bp 之间", s.length) }
        score = maxOf(0, score)

        return Analysis(
            sequence = s,
            length = s.length,
            gcContent = gc,
            tm = tmNum,
            hairpins = hairpins,
            selfDimers = selfDimers,
            crossDimers = crossDimers,
            polyX = polyX,
            gcClamp = gcClamp,
            gcClamp5 = gcClamp5,
            nBaseCount = nBaseCount,
            maxHomopolymer = maxHomo,
            score = score,
            warnings = warnings
        )
    }
}
