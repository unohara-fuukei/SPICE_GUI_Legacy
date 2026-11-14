package com.spice.nativeapp.core

/**
 * 高级基因组学工具舱（对齐 main 分支 lib/genome/biophysics/genomics.ts，算法级 1:1）
 *
 * 覆盖：TFBS 基序、RBS(Shine-Dalgarno) 翻译起始强度、σ70 启动子、信号肽、跨膜螺旋、
 * GC/AT 偏斜、CpG 岛（Gardiner-Garden 标准）、串联重复(SSR)、GFF3/BED 解析、
 * 原核基因预测、CRISPR array 检测。
 */
object GenomicsTools {

    private fun dna(seq: String) = seq.uppercase().filter { it in "ATCG" }
    private fun aa(seq: String) = seq.uppercase().filter { it in 'A'..'Z' }

    // ───────────────────── 转录因子结合位点 ─────────────────────

    data class TfbsHit(val name: String, val pos: Int, val strand: String, val matchSeq: String)

    fun predictTfbsMotifs(dnaSeq: String): List<TfbsHit> {
        val seq = dna(dnaSeq)
        val rcSeq = Bio.revComp(seq)
        val consensus = listOf(
            "TATA-Box" to Regex("TATA[AT]A[AT]"),
            "Pribnow-Box (-10)" to Regex("TATAAT"),
            "Sox9 Binding Site" to Regex("AACAAAG"),
            "NF-kB Site" to Regex("GGG[AG].[CT][CT]CC"),
            "CREB consensus" to Regex("TGACGTCA")
        )
        val out = mutableListOf<TfbsHit>()
        for ((name, re) in consensus) {
            for (m in re.findAll(seq)) out += TfbsHit(name, m.range.first, "+", m.value)
            for (m in re.findAll(rcSeq)) {
                val rcPos = seq.length - m.range.first - m.value.length
                out += TfbsHit(name, rcPos, "-", Bio.revComp(m.value))
            }
        }
        return out.sortedBy { it.pos }
    }

    // ───────────────────── RBS 翻译起始强度（Salis 风格） ─────────────────────

    data class RbsResult(
        val tirScore: Int,        // 翻译起始速率（任意单位 0..150000）
        val deltaGSD: Double,     // SD 杂交能量 kcal/mol
        val deltaGSpacer: Double, // 间隔惩罚
        val totalDeltaG: Double,
        val spacerLength: Int,
        val sdOverhangSequence: String
    )

    fun calculateRbsStrength(dnaSeq: String, atgStartIndex: Int): RbsResult {
        val seq = dna(dnaSeq)
        val rbsWindowStart = maxOf(0, atgStartIndex - 25)
        val rbsWindow = seq.substring(rbsWindowStart, atgStartIndex.coerceAtMost(seq.length))
        val asd = "CCTCCT" // E. coli 16S rRNA 反 SD 序列

        var maxMatch = 0
        var bestIdx = -1
        var i = 0
        while (i + 6 <= rbsWindow.length) {
            val sub = rbsWindow.substring(i, i + 6)
            var match = 0
            for (j in 0 until 6) {
                val a = sub[j]; val b = asd[j]
                if ((a == 'A' && b == 'T') || (a == 'T' && b == 'A') ||
                    (a == 'G' && b == 'C') || (a == 'C' && b == 'G')) match++
            }
            if (match > maxMatch) { maxMatch = match; bestIdx = i }
            i++
        }

        val deltaGSD = -1.5 * maxMatch
        val absoluteSdStart = rbsWindowStart + bestIdx
        val spacerLength = atgStartIndex - (absoluteSdStart + 6)
        var deltaGSpacer = 0.0
        if (spacerLength < 5) deltaGSpacer = 0.8 * (5 - spacerLength).toDouble().pow2() + 1.0
        else if (spacerLength > 9) deltaGSpacer = 0.5 * (spacerLength - 9).toDouble().pow2() + 0.5
        val total = deltaGSD + deltaGSpacer
        val tir = Math.round(150000.0 / (1.0 + Math.exp(0.45 * (total + 9.5)))).toInt()

        return RbsResult(
            tir,
            Math.round(deltaGSD * 100) / 100.0,
            Math.round(deltaGSpacer * 100) / 100.0,
            Math.round(total * 100) / 100.0,
            spacerLength,
            if (bestIdx >= 0) rbsWindow.substring(bestIdx, bestIdx + 6) else ""
        )
    }

    private fun Double.pow2(): Double = this * this

    // ───────────────────── σ70 启动子预测 ─────────────────────

    data class PromoterHit(val pos: Int, val score: Int, val sequence: String)

    fun predictPromoters(dnaSeq: String): List<PromoterHit> {
        val seq = dna(dnaSeq)
        val out = mutableListOf<PromoterHit>()
        val ref35 = "TTGACA"; val ref10 = "TATAAT"
        var i = 0
        while (i < seq.length - 40) {
            val box35 = seq.substring(i, i + 6)
            for (s in 15..20) {
                val b10Start = 6 + s
                if (b10Start + 6 > 40) continue
                val box10 = seq.substring(i + b10Start, i + b10Start + 6)
                var m35 = 0; var m10 = 0
                for (j in 0 until 6) {
                    if (box35[j] == ref35[j]) m35++
                    if (box10[j] == ref10[j]) m10++
                }
                if (m35 >= 4 && m10 >= 4) {
                    out += PromoterHit(i, Math.round((m35 + m10) * 100.0 / 12).toInt(), box35 + "-".repeat(s) + box10)
                }
            }
            i++
        }
        return out.sortedByDescending { it.score }
    }

    // ───────────────────── 信号肽预测（三分区扫描） ─────────────────────

    data class SignalPeptideResult(
        val hasSignal: Boolean, val cleavagePos: Int, val score: Int, val details: String
    )

    fun predictSignalPeptide(aaSeq: String): SignalPeptideResult {
        val seq = aa(aaSeq).take(50)
        if (seq.length < 20) return SignalPeptideResult(false, 0, 0, com.spice.nativeapp.core.I18n.t("序列过短（<20 aa）"))

        // 1. N 区正电荷（K/R）
        val positive = seq.take(8).count { it == 'K' || it == 'R' }

        // 2. H 区疏水核心（10 aa 窗口）
        var bestHydro = 0
        var hStart = -1
        val w = 10
        var i = 4
        while (i <= seq.length - w - 5) {
            val sub = seq.substring(i, i + w)
            val hc = sub.count { it in "LAVIFM" }
            if (hc > bestHydro) { bestHydro = hc; hStart = i }
            i++
        }

        // 3. C 区切割位点（A-X-A 共识）
        var cleavagePos = -1
        var axa = false
        if (hStart != -1) {
            val searchStart = hStart + w
            var c = searchStart
            while (c < minOf(seq.length - 2, searchStart + 10)) {
                val p3 = seq[c]; val p1 = seq[c + 2]
                if (p3 in "AVGST" && p1 in "AVGS") { cleavagePos = c + 3; axa = true; break }
                c++
            }
        }

        val hasSignal = positive >= 1 && bestHydro >= 7 && axa
        val score = if (hasSignal) minOf(100, 50 + positive * 10 + bestHydro * 3) else 0
        return SignalPeptideResult(
            hasSignal, if (hasSignal) cleavagePos else 0, score,
            if (hasSignal) com.spice.nativeapp.core.I18n.tf("N 区正电荷 {v1} · H 区疏水 {v2}/10 · 切割位点 ~{v3}", positive, bestHydro, cleavagePos)
            else com.spice.nativeapp.core.I18n.t("未检出分泌信号肽")
        )
    }

    // ───────────────────── 跨膜螺旋预测 ─────────────────────

    data class TmHelix(val start: Int, val end: Int, val averageHydro: Double)

    fun predictTransmembraneHelices(aaSeq: String): List<TmHelix> {
        val seq = aa(aaSeq)
        val hydro = ProteinBiophysics.HYDRO
        val w = 20
        val out = mutableListOf<TmHelix>()
        var i = 0
        while (i <= seq.length - w) {
            val avg = (i until i + w).sumOf { hydro[seq[it]] ?: 0.0 } / w
            if (avg > 1.5) {
                val overlap = out.any { i >= it.start && i <= it.end }
                if (!overlap) out += TmHelix(i, i + w - 1, Math.round(avg * 100) / 100.0)
            }
            i++
        }
        return out
    }

    // ───────────────────── GC/AT 偏斜 ─────────────────────

    data class SkewPoint(val pos: Int, val skew: Double)

    fun calculateSkew(dnaSeq: String, type: String = "gc", windowSize: Int = 1000, step: Int = 100): List<SkewPoint> {
        val seq = dna(dnaSeq)
        val points = mutableListOf<SkewPoint>()
        var i = 0
        while (i <= seq.length - windowSize) {
            val sub = seq.substring(i, i + windowSize)
            var c1 = 0; var c2 = 0
            for (b in sub) {
                if (type == "gc") { if (b == 'G') c1++; else if (b == 'C') c2++ }
                else { if (b == 'A') c1++; else if (b == 'T') c2++ }
            }
            val denom = c1 + c2
            val skew = if (denom > 0) (c1 - c2).toDouble() / denom else 0.0
            points += SkewPoint(i + windowSize / 2, Math.round(skew * 1000) / 1000.0)
            i += step
        }
        return points
    }

    // ───────────────────── CpG 岛检测 ─────────────────────

    data class CpGIsland(
        val start: Int, val end: Int, val length: Int,
        val gcContent: Double, val obsExpRatio: Double
    )

    /** Gardiner-Garden 标准：≥200bp、GC>50%、Obs/Exp>0.6 */
    fun detectCpGIslands(
        dnaSeq: String,
        minLen: Int = 200,
        gcMin: Double = 50.0,
        ratioMin: Double = 0.6
    ): List<CpGIsland> {
        val seq = dna(dnaSeq)
        val islands = mutableListOf<CpGIsland>()
        val w = 200
        val step = 50
        var i = 0
        while (i <= seq.length - w) {
            val sub = seq.substring(i, i + w)
            var c = 0; var g = 0; var cg = 0
            for (j in sub.indices) {
                if (sub[j] == 'C') c++
                if (sub[j] == 'G') g++
                if (j < sub.length - 1 && sub[j] == 'C' && sub[j + 1] == 'G') cg++
            }
            val gc = (c + g) * 100.0 / w
            val ratio = if (c > 0 && g > 0) cg.toDouble() * w / (c * g) else 0.0
            if (gc >= gcMin && ratio >= ratioMin) {
                val start = i
                val end = i + w - 1
                val overlap = islands.firstOrNull { maxOf(start, it.start) <= minOf(end, it.end) }
                if (overlap != null) {
                    val ns = minOf(overlap.start, start)
                    val ne = maxOf(overlap.end, end)
                    val merged = seq.substring(ns, ne + 1)
                    var mc = 0; var mg = 0; var mcg = 0
                    for (k in merged.indices) {
                        if (merged[k] == 'C') mc++
                        if (merged[k] == 'G') mg++
                        if (k < merged.length - 1 && merged[k] == 'C' && merged[k + 1] == 'G') mcg++
                    }
                    val idx = islands.indexOf(overlap)
                    islands[idx] = CpGIsland(
                        ns, ne, ne - ns + 1,
                        Math.round((mc + mg) * 1000.0 / merged.length) / 10.0,
                        Math.round(mcg.toDouble() * merged.length / (mc * mg) * 100) / 100.0
                    )
                } else {
                    islands += CpGIsland(
                        start, end, w,
                        Math.round(gc * 10) / 10.0,
                        Math.round(ratio * 100) / 100.0
                    )
                }
            }
            i += step
        }
        return islands
    }

    // ───────────────────── 串联重复（SSR/STR） ─────────────────────

    data class TandemRepeat(
        val start: Int, val end: Int, val pattern: String,
        val repeatCount: Int, val totalLength: Int
    )

    fun detectTandemRepeats(dnaSeq: String, minRepeatLen: Int = 2): List<TandemRepeat> {
        val seq = dna(dnaSeq)
        val repeats = mutableListOf<TandemRepeat>()
        for (patLen in 1..6) {
            var i = 0
            while (i <= seq.length - patLen * 2) {
                val pattern = seq.substring(i, i + patLen)
                var count = 1
                var nextPos = i + patLen
                while (nextPos + patLen <= seq.length && seq.substring(nextPos, nextPos + patLen) == pattern) {
                    count++
                    nextPos += patLen
                }
                val totalLength = count * patLen
                val minTotal = if (patLen == 1) 8 else patLen * 3
                if (count >= 3 && totalLength >= minTotal) {
                    val start = i
                    val end = nextPos - 1
                    val covered = repeats.any { start >= it.start && end <= it.end }
                    if (!covered) {
                        repeats += TandemRepeat(start, end, pattern, count, totalLength)
                    }
                    i = end
                }
                i++
            }
        }
        return repeats.sortedBy { it.start }
    }

    // ───────────────────── GFF3 / BED 解析 ─────────────────────

    data class GenomicFeature(
        val seqid: String, val source: String, val type: String,
        val start: Int, val end: Int, val strand: String, val attributes: String
    )

    fun parseGFF3(text: String): List<GenomicFeature> = parseGff(text, "GFF3")

    fun parseGTF(text: String): List<GenomicFeature> = parseGff(text, "GTF")

    private fun parseGff(text: String, flavor: String): List<GenomicFeature> {
        val out = mutableListOf<GenomicFeature>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val f = l.split("\t")
            if (f.size < 8) continue
            val start = f[3].toIntOrNull() ?: continue
            val end = f[4].toIntOrNull() ?: continue
            out += GenomicFeature(f[0], f[1], f[2], start, end, f[6], f[8])
        }
        return out
    }

    fun parseBED(text: String): List<GenomicFeature> {
        val out = mutableListOf<GenomicFeature>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("track") || l.startsWith("browser")) continue
            val f = l.split(Regex("\\s+"))
            if (f.size < 3) continue
            val start = f[1].toIntOrNull() ?: continue
            val end = f[2].toIntOrNull() ?: continue
            out += GenomicFeature(
                f[0], "BED", f.getOrNull(3) ?: "region", start, end,
                if (f.getOrNull(5) == "-") "-" else "+", ""
            )
        }
        return out
    }

    // ───────────────────── 原核基因预测 ─────────────────────

    data class PredictedGene(
        val id: String, val start: Int, val end: Int, val strand: String,
        val startCodon: String, val stopCodon: String, val score: Int,
        val translation: String, val hasRbsMatch: Boolean
    )

    fun predictProkaryoticGenes(dnaSeq: String, minLengthBp: Int = 90): List<PredictedGene> {
        val seq = dna(dnaSeq)
        val rcSeq = Bio.revComp(seq)
        val len = seq.length
        val predicted = mutableListOf<PredictedGene>()
        val starts = listOf("ATG", "GTG", "TTG")
        val stops = listOf("TAA", "TAG", "TGA")

        fun scanStrand(target: String, isForward: Boolean) {
            for (frame in 0 until 3) {
                var i = frame
                while (i <= target.length - minLengthBp) {
                    val codon = target.substring(i, i + 3)
                    if (codon in starts) {
                        val startPos = i
                        var stopPos = -1
                        var j = startPos + 3
                        while (j <= target.length - 3) {
                            val nc = target.substring(j, j + 3)
                            if (nc in stops) { stopPos = j; break }
                            j += 3
                        }
                        if (stopPos != -1 && stopPos + 3 - startPos >= minLengthBp) {
                            val geneSeq = target.substring(startPos, stopPos + 3)
                            val genomeStart = if (isForward) startPos else len - stopPos - 3
                            val genomeEnd = if (isForward) stopPos + 2 else len - startPos - 1
                            val upstream = target.substring(maxOf(0, startPos - 18), startPos)
                            var hasRbs = false
                            var rbsBonus = 0
                            if ("AGGAGG" in upstream || "GGAGG" in upstream || "GAGG" in upstream) {
                                hasRbs = true; rbsBonus = 35
                            } else if ("GGAG" in upstream || "AGGA" in upstream) {
                                hasRbs = true; rbsBonus = 20
                            }
                            val lengthBonus = minOf(45.0, geneSeq.length / 300.0 * 10)
                            val score = minOf(99, Math.round(40 + rbsBonus + lengthBonus).toInt())
                            predicted += PredictedGene(
                                "gene_${predicted.size + 1}",
                                genomeStart, genomeEnd, if (isForward) "+" else "-",
                                codon, target.substring(stopPos, stopPos + 3), score,
                                Bio.translate(geneSeq), hasRbs
                            )
                            i = stopPos
                        }
                    }
                    i += 3
                }
            }
        }
        scanStrand(seq, true)
        scanStrand(rcSeq, false)
        return predicted.sortedByDescending { it.score }
    }

    // ───────────────────── CRISPR array 检测 ─────────────────────

    data class CrisprArray(
        val start: Int, val end: Int, val repeatConsensus: String,
        val repeatCount: Int, val spacers: List<String>, val confidence: Int
    )

    fun detectCrisprArrays(dnaSeq: String): List<CrisprArray> {
        val seq = dna(dnaSeq)
        val arrays = mutableListOf<CrisprArray>()
        for (repLen in 23..40) {
            var i = 0
            while (i < seq.length - (repLen * 3 + 40)) {
                val candidateRepeat = seq.substring(i, i + repLen)
                val spacers = mutableListOf<String>()
                var currentPos = i + repLen
                var matchCount = 1
                while (currentPos < seq.length) {
                    var found = false
                    for (spacerLen in 21..72) {
                        if (currentPos + spacerLen + repLen > seq.length) break
                        val target = seq.substring(currentPos + spacerLen, currentPos + spacerLen + repLen)
                        var mismatches = 0
                        for (k in 0 until repLen) {
                            if (candidateRepeat[k] != target[k]) mismatches++
                        }
                        if (mismatches <= 2) {
                            spacers += seq.substring(currentPos, currentPos + spacerLen)
                            currentPos = currentPos + spacerLen + repLen
                            matchCount++
                            found = true
                            break
                        }
                    }
                    if (!found) break
                }
                if (matchCount >= 3) {
                    val start = i
                    val end = currentPos - 1
                    val covered = arrays.any { start >= it.start && end <= it.end }
                    if (!covered) {
                        val confidence = minOf(100, 50 + matchCount * 10)
                        arrays += CrisprArray(start, end, candidateRepeat, matchCount, spacers, confidence)
                    }
                    i = end
                }
                i++
            }
        }
        return arrays.sortedBy { it.start }
    }
}
