package com.spice.nativeapp.core

/**
 * T7 块8：main src/lib/genome/biophysics/syntheticGeneQc.ts 1:1 移植
 * （合成基因质控模块 Features 1-6：哺乳动物表达预检）。
 * 1. 隐蔽剪接位点检测与同义消除  2. 隐蔽 PolyA 信号扫描与同义移除
 * 3. Kozak 翻译起始强度分析与优化  4. 提前终止密码子 (PTC/NMD) 检测
 * 5. 密码子对偏好 (CPB) 评分与优化  6. 低复杂度区域检测与小写遮蔽 (DUST-style)
 * 词条引用（zh 原文见 zh.json）：sgq 系 65 条（tabQc 系 4 条 + syntheticGeneDesignerQC）。
 */
object SyntheticGeneQc {

    // ───────────────────────── 同义密码子表（syntheticGeneQc.ts:17-32）─────────────────────────

    private val CODON_TO_AA: Map<String, Char> = mapOf(
        "TTT" to 'F', "TTC" to 'F', "TTA" to 'L', "TTG" to 'L', "CTT" to 'L', "CTC" to 'L', "CTA" to 'L', "CTG" to 'L',
        "ATT" to 'I', "ATC" to 'I', "ATA" to 'I', "ATG" to 'M', "GTT" to 'V', "GTC" to 'V', "GTA" to 'V', "GTG" to 'V',
        "TCT" to 'S', "TCC" to 'S', "TCA" to 'S', "TCG" to 'S', "AGT" to 'S', "AGC" to 'S', "CCT" to 'P', "CCC" to 'P',
        "CCA" to 'P', "CCG" to 'P', "ACT" to 'T', "ACC" to 'T', "ACA" to 'T', "ACG" to 'T', "GCT" to 'A', "GCC" to 'A',
        "GCA" to 'A', "GCG" to 'A', "TAT" to 'Y', "TAC" to 'Y', "TAA" to '*', "TAG" to '*', "TGA" to '*', "CAT" to 'H',
        "CAC" to 'H', "CAA" to 'Q', "CAG" to 'Q', "AAT" to 'N', "AAC" to 'N', "AAA" to 'K', "AAG" to 'K', "GAT" to 'D',
        "GAC" to 'D', "GAA" to 'E', "GAG" to 'E', "TGT" to 'C', "TGC" to 'C', "TGG" to 'W', "CGT" to 'R', "CGC" to 'R',
        "CGA" to 'R', "CGG" to 'R', "AGA" to 'R', "AGG" to 'R', "GGT" to 'G', "GGC" to 'G', "GGA" to 'G', "GGG" to 'G'
    )

    private val AA_TO_CODONS: Map<Char, List<String>> =
        CODON_TO_AA.entries.groupBy({ it.value }, { it.key })

    private fun clean(dna: String): String = dna.uppercase().filter { it in "ATCG" }

    // ───────────────────────── 1: 隐蔽剪接位点（syntheticGeneQc.ts:48-117）─────────────────────────

    data class SpliceSite(
        val type: String,      // donor / acceptor
        val position: Int,     // 0-based
        val sequence: String,
        val score: Int         // 0-100
    )

    /**
     * main detectCrypticSpliceSites：哺乳动物共识剪接位点扫描。
     * Donor MAG|GTRAGT（M=A/C, R=A/G）PWM 打分 ≥60；Acceptor [C/T]13NYAG|G 多聚嘧啶束打分 ≥65。
     */
    fun detectCrypticSpliceSites(cds: String): List<SpliceSite> {
        val seq = clean(cds)
        val sites = mutableListOf<SpliceSite>()

        // 1. Donor: [A/C]AG GT [A/G]AGT（i 为 GT 的 G 位）
        for (i in 3 until seq.length - 6) {
            val sub = seq.substring(i - 3, i + 6)  // 9bp 窗口
            if (seq.substring(i, i + 2) == "GT") {
                var score = 0
                if (sub[0] == 'A' || sub[0] == 'C') score += 10   // Pos -3
                if (sub[1] == 'A') score += 15                     // Pos -2
                if (sub[2] == 'G') score += 20                     // Pos -1
                score += 25                                        // Pos +1,+2 GT 强制
                if (sub[5] == 'A' || sub[5] == 'G') score += 15    // Pos +3
                if (sub[6] == 'A') score += 5                      // Pos +4
                if (sub[7] == 'G') score += 5                      // Pos +5
                if (sub[8] == 'T') score += 5                      // Pos +6
                if (score >= 60) {
                    sites += SpliceSite("donor", i, sub, score)
                }
            }
        }

        // 2. Acceptor: 富 C/T 区后接 NCAG/TAG G（i 为 AG 后第一位）
        for (i in 15 until seq.length - 1) {
            if (seq.substring(i - 2, i) == "AG") {
                val upstream = seq.substring(i - 15, i - 2)  // 13bp 多聚嘧啶束
                val ctCount = upstream.count { it == 'C' || it == 'T' }
                var score = Math.round(ctCount / 13.0 * 50).toInt()  // 最多 50
                score += 30                                           // AG 存在
                if (seq[i - 3] == 'C' || seq[i - 3] == 'T') score += 10
                if (seq[i] == 'G') score += 10
                if (score >= 65) {
                    sites += SpliceSite("acceptor", i - 2, seq.substring(i - 15, i + 2), score)
                }
            }
        }
        return sites
    }

    data class SpliceEliminationResult(
        val modifiedSequence: String,
        val mutationsCount: Int,
        val log: List<String>
    )

    /**
     * main eliminateCrypticSpliceSites：迭代至多 5 轮，同义突变消除隐蔽剪接位点（阅读框 0）。
     * 每轮只改一个位点即重扫（changedThisIter break 语义逐层）。
     */
    fun eliminateCrypticSpliceSites(cds: String): SpliceEliminationResult {
        var seq = clean(cds)
        val log = mutableListOf<String>()
        var mutationsCount = 0

        for (iter in 0 until 5) {
            val sites = detectCrypticSpliceSites(seq)
            if (sites.isEmpty()) break

            var changedThisIter = false
            outer@ for (site in sites) {
                val pos = site.position  // GT 的 G 或 AG 的 A
                val codonStart1 = (pos / 3) * 3
                val codonStart2 = ((pos + 1) / 3) * 3
                val starts = listOf(codonStart1, codonStart2).distinct().sorted()

                for (start in starts) {
                    if (start + 3 > seq.length) continue
                    val currentCodon = seq.substring(start, start + 3)
                    val aa = CODON_TO_AA[currentCodon] ?: continue
                    val synonyms = (AA_TO_CODONS[aa] ?: emptyList()).filter { it != currentCodon }
                    if (synonyms.isNotEmpty()) {
                        for (syn in synonyms) {
                            val tempSeq = seq.substring(0, start) + syn + seq.substring(start + 3)
                            val remainingSites = detectCrypticSpliceSites(tempSeq)
                            val stillHasSite = remainingSites.any { Math.abs(it.position - pos) <= 5 }
                            if (!stillHasSite) {
                                seq = tempSeq
                                log += if (site.type == "donor")
                                    I18n.tf("[剪接质控] 在第 {v1} bp 处引入同义突变 {v2} ➔ {v3} (消除了位于位点 {v4} 处的隐蔽剪切供体位点)",
                                        start + 1, currentCodon, syn, pos + 1)
                                else
                                    I18n.tf("[剪接质控] 在第 {v1} bp 处引入同义突变 {v2} ➔ {v3} (消除了位于位点 {v4} 处的隐蔽剪切受体位点)",
                                        start + 1, currentCodon, syn, pos + 1)
                                mutationsCount++
                                changedThisIter = true
                                break
                            }
                        }
                    }
                    if (changedThisIter) break
                }
                if (changedThisIter) break@outer  // 改动后重扫
            }
            if (!changedThisIter) {
                log += I18n.t("[警告] 无法通过同义突变完全消除所有隐蔽剪接位点 (可能受限于氨基酸序列约束)")
                break
            }
        }
        return SpliceEliminationResult(seq, mutationsCount, log)
    }

    // ───────────────────────── 2: 隐蔽 PolyA 信号（syntheticGeneQc.ts:188-274）─────────────────────────

    private val POLYA_SIGNALS = listOf(
        "AATAAA", "ATTAAA", "TATAAA", "AGTAAA", "AAGAAA", "AATACA",
        "CATAAA", "GATAAA", "AATATA", "AATGAA", "ACTAAA", "AATAGA"
    )

    data class PolyASignalMatch(val signal: String, val position: Int)

    /** main detectCrypticPolyASignals：12 种 PolyA 六聚体全扫（去重后按 position 升序）。 */
    fun detectCrypticPolyASignals(cds: String): List<PolyASignalMatch> {
        val seq = clean(cds)
        val found = mutableListOf<PolyASignalMatch>()
        for (signal in POLYA_SIGNALS) {
            var pos = seq.indexOf(signal)
            while (pos != -1) {
                if (found.none { it.position == pos }) {
                    found += PolyASignalMatch(signal, pos)
                }
                pos = seq.indexOf(signal, pos + 1)
            }
        }
        return found.sortedBy { it.position }
    }

    data class PolyAEliminationResult(
        val modifiedSequence: String,
        val mutationsCount: Int,
        val log: List<String>
    )

    /** main eliminateCrypticPolyASignals：迭代至多 10 轮，同义突变消除 PolyA 信号。 */
    fun eliminateCrypticPolyASignals(cds: String): PolyAEliminationResult {
        var seq = clean(cds)
        val log = mutableListOf<String>()
        var mutationsCount = 0

        for (iter in 0 until 10) {
            val signals = detectCrypticPolyASignals(seq)
            if (signals.isEmpty()) break

            var changedThisIter = false
            outer@ for (sig in signals) {
                val pos = sig.position
                val codonStarts = mutableListOf<Int>()
                for (i in 0 until 6) {
                    val start = ((pos + i) / 3) * 3
                    if (start !in codonStarts) codonStarts += start
                }
                for (start in codonStarts) {
                    if (start + 3 > seq.length) continue
                    val currentCodon = seq.substring(start, start + 3)
                    val aa = CODON_TO_AA[currentCodon] ?: continue
                    val synonyms = (AA_TO_CODONS[aa] ?: emptyList()).filter { it != currentCodon }
                    for (syn in synonyms) {
                        val tempSeq = seq.substring(0, start) + syn + seq.substring(start + 3)
                        val remaining = detectCrypticPolyASignals(tempSeq)
                        val stillHasSignal = remaining.any { Math.abs(it.position - pos) <= 5 }
                        if (!stillHasSignal) {
                            seq = tempSeq
                            log += I18n.tf(
                                "[PolyA 质控] 在第 {v1} bp 处引入同义突变 {v2} ➔ {v3} (消除了 {v4} 提前转录终止信号)",
                                start + 1, currentCodon, syn, sig.signal)
                            mutationsCount++
                            changedThisIter = true
                            break
                        }
                    }
                    if (changedThisIter) break
                }
                if (changedThisIter) break@outer
            }
            if (!changedThisIter) {
                log += I18n.tf("[警告] 无法通过同义突变消除位于 {v1} 处的 PolyA 信号", signals[0].position + 1)
                break
            }
        }
        return PolyAEliminationResult(seq, mutationsCount, log)
    }

    // ───────────────────────── 3: Kozak 分析与优化（syntheticGeneQc.ts:292-403）─────────────────────────

    data class KozakAnalysis(
        val upstreamSeq: String,     // ATG 前 6bp（N 左填充）
        val startCodon: String,
        val downstreamSeq: String,   // ATG 后 3bp（N 右填充）
        val score: Int,              // 0-100
        val strength: String,        // Strong / Adequate / Weak
        val recommendations: List<String>
    )

    /**
     * main analyzeKozak：对照共识 GCCRCCATGG 评估。
     * 权重：-3 A=40/G=30、+4 G=30、-6 G/-5 C/-4 C/-2 C/-1 C 各 6；≥80 Strong、≥50 Adequate。
     */
    fun analyzeKozak(dnaSeq: String, startIdx: Int): KozakAnalysis {
        val seq = clean(dnaSeq)
        val recommendations = mutableListOf<String>()

        val upStart = maxOf(0, startIdx - 6)
        val upstreamSeq = seq.substring(upStart, startIdx.coerceAtMost(seq.length)).padStart(6, 'N')
        val startCodon = if (startIdx + 3 <= seq.length) seq.substring(startIdx, startIdx + 3) else ""
        val downstreamSeq = (if (startIdx + 6 <= seq.length) seq.substring(startIdx + 3, startIdx + 6) else seq.substring((startIdx + 3).coerceAtMost(seq.length)))
            .padEnd(3, 'N')

        if (startCodon != "ATG") {
            return KozakAnalysis(upstreamSeq, startCodon, downstreamSeq, 0, "Weak",
                listOf(I18n.t("未找到 ATG 起始密码子，无法分析 Kozak 强度。")))
        }

        val baseMinus3 = upstreamSeq[3]
        val basePlus4 = downstreamSeq[0]

        var score = 0
        if (baseMinus3 == 'A') score += 40
        else if (baseMinus3 == 'G') score += 30
        if (basePlus4 == 'G') score += 30
        if (upstreamSeq[0] == 'G') score += 6   // -6
        if (upstreamSeq[1] == 'C') score += 6   // -5
        if (upstreamSeq[2] == 'C') score += 6   // -4
        if (upstreamSeq[4] == 'C') score += 6   // -2
        if (upstreamSeq[5] == 'C') score += 6   // -1

        var strength = "Weak"
        if (score >= 80) strength = "Strong"
        else if (score >= 50) strength = "Adequate"

        if (baseMinus3 != 'A' && baseMinus3 != 'G') {
            recommendations += I18n.t("建议将 -3 位置的碱基突变为 'A' (首选) 或 'G'，这是决定真核翻译强度的核心元件。")
        }
        if (basePlus4 != 'G') {
            recommendations += I18n.t("建议将起始密码子后第一个碱基 (+4 位) 优化为 'G'。如果是 CDS 内部突变，可以通过选择同义密码子来实现。")
        }
        if (strength == "Weak") {
            recommendations += I18n.t("当前 Kozak 序列极弱，在哺乳动物细胞中表达可能面临极低的翻译起始效率，请进行同义突变优化。")
        }
        return KozakAnalysis(upstreamSeq, startCodon, downstreamSeq, score, strength, recommendations)
    }

    data class KozakOptimizeResult(
        val optimizedSequence: String,
        val log: List<String>
    )

    /**
     * main optimizeKozak：优化至 GCCACCATGG。
     * quirk：越界时返回原 dnaSeq（未清洗原串）而非清洗后序列。
     */
    fun optimizeKozak(dnaSeq: String, startIdx: Int): KozakOptimizeResult {
        val seq = clean(dnaSeq)
        if (startIdx < 6 || startIdx + 6 > seq.length) {
            return KozakOptimizeResult(dnaSeq, listOf(I18n.t("位置超出序列范围，无法进行 Kozak 优化")))
        }

        val log = mutableListOf<String>()
        val seqArr = seq.toCharArray()

        // 1. 上游 -6..-1 安全突变
        val optimalUpstream = "GCCACC"
        for (i in 0 until 6) {
            val idx = startIdx - 6 + i
            if (seqArr[idx] != optimalUpstream[i]) {
                log += I18n.tf("[Kozak 优化] 将 -{v1} 位碱基 {v2} 突变为 {v3}", 6 - i, seqArr[idx].toString(), optimalUpstream[i].toString())
                seqArr[idx] = optimalUpstream[i]
            }
        }

        // 2. 下游 +4 同义突变（ATG 后第一密码子选 G 开头同义密码子）
        val nextCodon = seq.substring(startIdx + 3, startIdx + 6)
        if (nextCodon.length == 3) {
            val aa = CODON_TO_AA[nextCodon]
            if (aa != null) {
                val synonyms = AA_TO_CODONS[aa] ?: emptyList()
                val bestSyn = synonyms.firstOrNull { it[0] == 'G' }
                if (bestSyn != null && bestSyn != nextCodon) {
                    seqArr[startIdx + 3] = bestSyn[0]
                    seqArr[startIdx + 4] = bestSyn[1]
                    seqArr[startIdx + 5] = bestSyn[2]
                    log += I18n.tf("[Kozak 优化] 通过同义密码子突变 {v1} ➔ {v2} 将 +4 位优化为 'G' (氨基酸保持 {v3})", nextCodon, bestSyn, aa.toString())
                } else if (nextCodon[0] != 'G') {
                    log += I18n.tf("[Kozak 提示] 无法将 +4 位优化为 'G' (氨基酸 {v1} 无以 'G' 开头的同义密码子)", aa.toString())
                }
            }
        }
        return KozakOptimizeResult(String(seqArr), log)
    }

    // ───────────────────────── 4: PTC 检测（syntheticGeneQc.ts:417-448）─────────────────────────

    data class PrematureStopCodon(
        val codon: String,
        val position: Int,      // 0-based codon 起点
        val aaPosition: Int,    // 1-based
        val triggerNmd: Boolean
    )

    data class PtcResult(
        val hasPtc: Boolean,
        val stops: List<PrematureStopCodon>,
        val cleanCdsLength: Int
    )

    /** main detectPrematureStopCodons：末密码子之前的 TAA/TAG/TGA；NMD 触发 = 剩余 nt > 50。 */
    fun detectPrematureStopCodons(cds: String): PtcResult {
        val seq = clean(cds)
        val stops = mutableListOf<PrematureStopCodon>()
        val cleanCdsLength = (seq.length / 3) * 3

        var i = 0
        while (i < cleanCdsLength - 3) {
            val codon = seq.substring(i, i + 3)
            if (codon == "TAA" || codon == "TAG" || codon == "TGA") {
                val ntRemaining = cleanCdsLength - i
                val triggerNmd = ntRemaining > 50
                stops += PrematureStopCodon(codon, i, i / 3 + 1, triggerNmd)
            }
            i += 3
        }
        return PtcResult(stops.isNotEmpty(), stops, cleanCdsLength)
    }

    // ───────────────────────── 5: 密码子对偏好 CPB（syntheticGeneQc.ts:454-557）─────────────────────────

    // 模拟 CPS 数据库（人/哺乳动物偏好；负值 = 核糖体卡滞对）
    private val CODON_PAIR_SCORES: Map<String, Double> = mapOf(
        "GCC_GAA" to 0.25, "GAA_GCC" to 0.22, "CGC_CGA" to -1.45, "CGC_CGG" to -1.32,
        "CGG_CGC" to -1.25, "CGA_CGC" to -1.38, "AAA_AAA" to 0.18, "AAA_GAA" to 0.20,
        "CCG_CCG" to -0.95, "CCG_CCG_C" to -0.8, "CTA_CUA" to -0.85, "ATT_ATA" to -0.65
    )

    private fun getCodonPairScore(c1: String, c2: String): Double {
        val key = "${c1}_${c2}"
        if (key in CODON_PAIR_SCORES) return CODON_PAIR_SCORES[key]!!
        if (c1.contains("CG") || c2.contains("CG")) return -0.15  // CG 二核苷酸轻微抑制
        return 0.02
    }

    /** main calculateCpbScore：CPB = ΣCPS / (N-1)，toFixed(4) 舍入。 */
    fun calculateCpbScore(cds: String): Double {
        val seq = clean(cds)
        val len = (seq.length / 3) * 3
        if (len < 6) return 0.0

        var totalScore = 0.0
        var pairsCount = 0
        var i = 0
        while (i < len - 5) {
            val c1 = seq.substring(i, i + 3)
            val c2 = seq.substring(i + 3, i + 6)
            totalScore += getCodonPairScore(c1, c2)
            pairsCount++
            i += 3
        }
        return Math.round(totalScore / maxOf(1, pairsCount) * 10000) / 10000.0
    }

    data class CodonPairOptimizeResult(
        val optimizedSequence: String,
        val originalCpb: Double,
        val optimizedCpb: Double,
        val log: List<String>
    )

    /**
     * main optimizeCodonPairs：滑动窗口同义替换负分密码子对（不改蛋白序列）。
     * 首行 unshift sgqCodonPairSummary；尾接未清洗余量 seq.slice(len)。
     */
    fun optimizeCodonPairs(cds: String): CodonPairOptimizeResult {
        val seq = clean(cds)
        val len = (seq.length / 3) * 3
        val originalCpb = calculateCpbScore(seq)
        val log = mutableListOf<String>()

        val codons = mutableListOf<String>()
        var i = 0
        while (i < len) {
            codons += seq.substring(i, i + 3)
            i += 3
        }

        var optimizeCount = 0
        for (idx in 0 until codons.size - 1) {
            val c1 = codons[idx]
            val c2 = codons[idx + 1]
            val currentScore = getCodonPairScore(c1, c2)

            if (currentScore < 0) {  // 卡滞或劣配
                val aa1 = CODON_TO_AA[c1] ?: continue
                val aa2 = CODON_TO_AA[c2] ?: continue
                val synonyms1 = AA_TO_CODONS[aa1] ?: emptyList()
                val synonyms2 = AA_TO_CODONS[aa2] ?: emptyList()

                var bestScore = currentScore
                var bestPair = listOf(c1, c2)

                for (s1 in synonyms1) {
                    for (s2 in synonyms2) {
                        val score = getCodonPairScore(s1, s2)
                        if (score > bestScore) {
                            bestScore = score
                            bestPair = listOf(s1, s2)
                        }
                    }
                }

                if (bestPair[0] != c1 || bestPair[1] != c2) {
                    log += I18n.tf("[密码子对优化] 替换不良配对 AA: {v1}-{v2} ({v3}-{v4} ➔ {v5}-{v6})", aa1.toString(), aa2.toString(), c1, c2, bestPair[0], bestPair[1])
                    codons[idx] = bestPair[0]
                    codons[idx + 1] = bestPair[1]
                    optimizeCount++
                }
            }
        }

        val optimizedSequence = codons.joinToString("") + seq.substring(len)
        val optimizedCpb = calculateCpbScore(optimizedSequence)

        log.add(0, I18n.tf("[密码子对优化] 成功优化了 {v1} 处密码子对，CPB 指数由 {v2} 提升至 {v3}", optimizeCount, originalCpb.toString(), optimizedCpb.toString()))
        return CodonPairOptimizeResult(optimizedSequence, originalCpb, optimizedCpb, log)
    }

    // ───────────────────────── 6: 低复杂度区域（syntheticGeneQc.ts:572-675）─────────────────────────

    data class LowComplexityRegion(
        var start: Int,
        var end: Int,
        var sequence: String,
        var entropy: Double,   // Shannon entropy 0..2
        var type: String       // homopolymer / dinucleotide_repeat / low_entropy
    )

    /** main calculateShannonEntropy：Shannon 熵（bit/base）。 */
    fun calculateShannonEntropy(seq: String): Double {
        val counts = mutableMapOf<Char, Int>()
        for (b in seq) counts[b] = (counts[b] ?: 0) + 1
        var entropy = 0.0
        for (c in counts.values) {
            val p = c.toDouble() / seq.length
            entropy -= p * (Math.log(p) / Math.log(2.0))
        }
        return entropy
    }

    private val HOMOPOLYMER_RE = Regex("([ATCG])\\1{7,}")
    private val DI_REPEAT_RE = Regex("([ATCG]{2})\\1{3,}")

    /**
     * main detectLowComplexityRegions：同聚物 ≥8bp 正则全扫 + 滑窗（步 3）熵 < 1.2；
     * 合并重叠/近邻（gap ≤3）区域，类型不同降级 low_entropy。
     */
    fun detectLowComplexityRegions(
        dnaSeq: String,
        windowSize: Int = 12,
        entropyThreshold: Double = 1.2
    ): List<LowComplexityRegion> {
        val seq = clean(dnaSeq)
        val regions = mutableListOf<LowComplexityRegion>()

        // 1. 同聚物（≥8bp）
        for (match in HOMOPOLYMER_RE.findAll(seq)) {
            regions += LowComplexityRegion(
                match.range.first, match.range.last,
                match.value, calculateShannonEntropy(match.value), "homopolymer"
            )
        }

        // 2. 滑窗熵检查
        var i = 0
        while (i <= seq.length - windowSize) {
            val sub = seq.substring(i, i + windowSize)
            val entropy = calculateShannonEntropy(sub)
            if (entropy < entropyThreshold) {
                val isOverlapped = regions.any { r -> i >= r.start && i + windowSize - 1 <= r.end }
                if (!isOverlapped) {
                    val isDiRepeat = DI_REPEAT_RE.containsMatchIn(sub)
                    regions += LowComplexityRegion(
                        i, i + windowSize - 1, sub,
                        Math.round(entropy * 1000) / 1000.0,
                        if (isDiRepeat) "dinucleotide_repeat" else "low_entropy"
                    )
                }
            }
            i += 3
        }

        // 合并重叠/近邻
        val merged = mutableListOf<LowComplexityRegion>()
        val sorted = regions.sortedBy { it.start }
        for (reg in sorted) {
            if (merged.isEmpty()) {
                merged += reg
            } else {
                val last = merged.last()
                if (reg.start <= last.end + 3) {
                    last.end = maxOf(last.end, reg.end)
                    last.sequence = seq.substring(last.start, last.end + 1)
                    last.entropy = Math.round(calculateShannonEntropy(last.sequence) * 1000) / 1000.0
                    if (last.type != reg.type) last.type = "low_entropy"
                } else {
                    merged += reg
                }
            }
        }
        return merged
    }

    data class DustMaskResult(
        val maskedSequence: String,
        val regions: List<LowComplexityRegion>
    )

    /** main maskLowComplexityRegions：低复杂度区域转小写遮蔽。 */
    fun maskLowComplexityRegions(dnaSeq: String): DustMaskResult {
        val seq = clean(dnaSeq)
        val regions = detectLowComplexityRegions(seq)
        val arr = seq.toCharArray()
        for (reg in regions) {
            for (i in reg.start..reg.end) {
                arr[i] = arr[i].lowercaseChar()
            }
        }
        return DustMaskResult(String(arr), regions)
    }
}
