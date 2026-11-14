package com.spice.nativeapp.core

/**
 * SPICE Genome CRISPR Engine — main src/lib/genome/crispr.ts 1:1 移植（T7 块 5）。
 *
 * 覆盖：gRNA 靶点扫描（4 种 Cas PAM）、Doench-Rule Set 2 近似效率评分、
 * CFD 脱靶预测、HDR Donor 设计（PAM 同义回避突变）、Prime Editing pegRNA 设计、
 * Base Editing 窗口计算。
 *
 * main quirk 逐字保留（红线：完全 1:1）：
 * 1. PAM_DATABASE 的 pamPattern 是带 g 标志的 JS RegExp 且用 .test() 调用——
 *    g 标志下 test 有 lastIndex 状态：一次成功匹配会把 lastIndex 推进到匹配末端，
 *    紧接着的下一次 test（即使内容可匹配）会因起点越过可匹配位置而必然失败并复位。
 *    实测后果：扫描循环中相邻两个 i（重叠 PAM 窗口）不可能同时命中，第二个被跳过。
 *    本文件用 StickyRegex 复刻该状态语义，且与 main 一样状态跨调用持久（模块级单例）。
 * 2. predictOffTargets 的 PAM 判定硬编码 NGG（windowPam[1]=='G' && windowPam[2]=='G'），
 *    与所选 Cas 类型无关；比较只取 guide 前 20nt。
 * 3. designHdrDonor 同义突变引擎固定取候选密码子表第一项（alts[0]）。
 * 4. hairpin 检测的 remaining 从 start1+len+3 起扫描（3bp loop 最小间距），非严格发卡。
 * 5. cfdScore = Number(cfd.toFixed(4))——BigDecimal HALF_UP 后回填 Double。
 * 6. specificity 初值 100（无参考序列时），finalScore = round(eff*0.8+20)；
 *    有参考序列时 finalScore = round(eff*0.5 + spec*0.5)。排序稳定（JS sort 稳定）。
 */

// ──────────────────────────────── Types ────────────────────────────────

enum class CasType(val label: String) {
    SpCas9("SpCas9 (NGG, 20nt)"),
    SpCas9_VQR("SpCas9-VQR (NGA, 20nt)"),
    SaCas9("SaCas9 (NNGRRT, 21nt)"),
    Cas12a("Cas12a/Cpf1 (TTTN, 23nt)")
}

/** 复刻 JS 带 g 标志 RegExp 的 .test() lastIndex 状态语义（固定长度模式）。 */
class StickyRegex(private val matchLen: Int, private val pred: (String, Int) -> Boolean) {
    var lastIndex: Int = 0

    fun test(s: String): Boolean {
        var i = lastIndex
        while (i + matchLen <= s.length && i <= s.length) {
            if (pred(s, i)) {
                lastIndex = i + matchLen
                return true
            }
            i++
        }
        lastIndex = 0
        return false
    }
}

class PamConfig(
    val cas: CasType,
    /** 前链 PAM 匹配（等价 main 正则，语义见类头注释 1） */
    val pamPattern: StickyRegex,
    val pamLength: Int,
    val guideLength: Int,
    /** "3prime" | "5prime" */
    val pamDirection: String,
    val cutOffsetFromPam: Int
)

object PAM_DATABASE {
    private val spCas9 = StickyRegex(2, { s, i -> s[i] in "ACG" && s[i + 1] == 'G' })            // /[ACG][GG]/gi
    private val spCas9Vqr = StickyRegex(2, { s, i -> s[i] in "ACG" && (s[i + 1] == 'G' || s[i + 1] == 'A') }) // /[ACG][GA]/gi
    private val saCas9 = StickyRegex(6, { s, i ->
        s[i] in "ATCG" && s[i + 1] in "ATCG" && s[i + 2] == 'G' &&
            (s[i + 3] == 'A' || s[i + 3] == 'G') && s[i + 4] == 'T' && (s[i + 5] == 'A' || s[i + 5] == 'G')
    }) // /[ATCG]{2}G[AG]T[AG]/gi
    private val cas12a = StickyRegex(4, { s, i -> s[i] == 'T' && s[i + 1] == 'T' && s[i + 2] == 'T' && s[i + 3] in "ACG" }) // /TTT[ACG]/gi

    val config: Map<CasType, PamConfig> = mapOf(
        CasType.SpCas9 to PamConfig(CasType.SpCas9, spCas9, 3, 20, "3prime", -3),
        CasType.SpCas9_VQR to PamConfig(CasType.SpCas9_VQR, spCas9Vqr, 3, 20, "3prime", -3),
        CasType.SaCas9 to PamConfig(CasType.SaCas9, saCas9, 6, 21, "3prime", -3),
        CasType.Cas12a to PamConfig(CasType.Cas12a, cas12a, 4, 23, "5prime", 18)
    )

    operator fun get(cas: CasType): PamConfig = config.getValue(cas)
}

data class GrnaScores(
    val efficiency: Int,
    val specificity: Int,
    val gcContent: Int,
    val polyT: Boolean,
    val hairpin: Boolean,
    val finalScore: Int
)

data class BaseEditCandidate(
    val index: Int,
    val originalBase: String,
    val targetBase: String,
    val windowPosition: Int,
    val efficiency: Int
)

data class BaseEditingResult(
    val cbeCandidates: List<BaseEditCandidate>,
    val abeCandidates: List<BaseEditCandidate>
)

data class GrnaCandidate(
    val id: String,
    val sequence: String,
    val fullTargetWithPam: String,
    val pam: String,
    val strand: String,
    val startIndex: Int,
    val endIndex: Int,
    val cutIndex: Int,
    val scores: GrnaScores,
    val baseEditingWindows: BaseEditingResult?
)

data class OffTargetSite(
    val sequence: String,
    val mismatches: Int,
    val mismatchPositions: List<Int>,
    val cfdScore: Double,
    val location: String,
    val strand: String
)

data class SilentMutation(
    val position: Int,
    val original: String,
    val mutated: String,
    val codonChange: String,
    val reason: String // "PAM_destruction" | "guide_disruption"
)

data class HdrDonorTemplate(
    val leftArm: String,
    val rightArm: String,
    val insertedMutations: String,
    val fullDonor: String,
    val silentMutationsIntroduced: List<SilentMutation>,
    val warning: String? = null
)

data class PegRnaDesign(
    val gRNA: String,
    val pbs: String,
    val pbsLength: Int,
    val pbsTm: Int,
    val rtt: String,
    val rttLength: Int,
    val full3PrimeExtension: String,
    val pegRnaSequence: String
)

// ──────────────────────────────── JS 语义 helpers ────────────────────────────────

/** getReverseComplementSequenceString：未知字符原样保留（main sequence.ts:72）。 */
internal fun rcSeq(sequence: String): String {
    val map = mapOf('A' to 'T', 'T' to 'A', 'G' to 'C', 'C' to 'G', 'U' to 'A')
    val sb = StringBuilder()
    for (i in sequence.length - 1 downTo 0) {
        sb.append(map[sequence[i]] ?: sequence[i])
    }
    return sb.toString()
}

/** JS String.prototype.slice(start, end)：负索引回折、越界钳制、start>=end 为空。 */
internal fun jsSlice(s: String, start: Int, end: Int): String {
    var st = if (start < 0) (s.length + start).coerceAtLeast(0) else start.coerceAtMost(s.length)
    var en = if (end < 0) (s.length + end).coerceAtLeast(0) else end.coerceAtMost(s.length)
    if (st > en) { val t = st; st = en; en = t }
    return s.substring(st, en)
}

/** JS String.prototype.slice(start) 单参形态。 */
internal fun jsSliceFrom(s: String, start: Int): String =
    jsSlice(s, start, s.length)

// ──────────────────────────────── gRNA Finder & Scorer ────────────────────────────────

/**
 * 在 DNA 模板内按特定 Cas 核酸酶的 PAM 约束寻找 gRNA 候选。
 * 注意：PAM_DATABASE 是模块级单例，StickyRegex 的 lastIndex 状态跨调用持久（与 main 一致）。
 */
fun findGrnaCandidates(
    template: String,
    cas: CasType = CasType.SpCas9,
    referenceGenomeForOffTarget: String? = null
): List<GrnaCandidate> {
    val seq = template.uppercase()
    val rcSeqLocal = rcSeq(seq)
    val config = PAM_DATABASE[cas]
    val candidates = ArrayList<GrnaCandidate>()

    val pamLen = config.pamLength
    val guideLen = config.guideLength

    // 1. 正链扫描
    var i = 0
    while (i <= seq.length - pamLen - guideLen) {
        var pamMatchIdx = 0
        var guideMatchIdx = 0
        var ok = false

        if (config.pamDirection == "3prime") {
            pamMatchIdx = i + guideLen
            val potentialPam = jsSlice(seq, pamMatchIdx, pamMatchIdx + pamLen)
            if (config.pamPattern.test(potentialPam)) {
                ok = true
                guideMatchIdx = i
            }
        } else {
            pamMatchIdx = i
            val potentialPam = jsSlice(seq, pamMatchIdx, pamMatchIdx + pamLen)
            if (config.pamPattern.test(potentialPam)) {
                ok = true
                guideMatchIdx = i + pamLen
            }
        }

        if (ok) {
            val guideSeq = jsSlice(seq, guideMatchIdx, guideMatchIdx + guideLen)
            val pamSeq = jsSlice(seq, pamMatchIdx, pamMatchIdx + pamLen)
            val fullTarget = if (config.pamDirection == "3prime") guideSeq + pamSeq else pamSeq + guideSeq

            val cutIdx = if (config.pamDirection == "3prime")
                pamMatchIdx + config.cutOffsetFromPam
            else
                pamMatchIdx + pamLen + config.cutOffsetFromPam

            val scores = evaluateGrna(guideSeq)
            val baseEditing = calculateBaseEditingWindow(guideSeq, guideMatchIdx, "+")

            val eff = scores.efficiency
            candidates.add(
                GrnaCandidate(
                    id = "gRNA_+_$guideMatchIdx",
                    sequence = guideSeq,
                    fullTargetWithPam = fullTarget,
                    pam = pamSeq,
                    strand = "+",
                    startIndex = guideMatchIdx,
                    endIndex = guideMatchIdx + guideLen,
                    cutIndex = cutIdx,
                    scores = GrnaScores(
                        efficiency = eff,
                        specificity = 100,
                        gcContent = scores.gcContent,
                        polyT = scores.polyT,
                        hairpin = scores.hairpin,
                        finalScore = Math.round(eff * 0.8 + 20).toInt()
                    ),
                    baseEditingWindows = baseEditing
                )
            )
        }
        i++
    }

    // 2. 反链扫描（坐标全部映射回正链）
    i = 0
    while (i <= rcSeqLocal.length - pamLen - guideLen) {
        var pamMatchIdx = 0
        var guideMatchIdx = 0
        var ok = false

        if (config.pamDirection == "3prime") {
            pamMatchIdx = i + guideLen
            val potentialPam = jsSlice(rcSeqLocal, pamMatchIdx, pamMatchIdx + pamLen)
            if (config.pamPattern.test(potentialPam)) {
                ok = true
                guideMatchIdx = i
            }
        } else {
            pamMatchIdx = i
            val potentialPam = jsSlice(rcSeqLocal, pamMatchIdx, pamMatchIdx + pamLen)
            if (config.pamPattern.test(potentialPam)) {
                ok = true
                guideMatchIdx = i + pamLen
            }
        }

        if (ok) {
            val guideSeq = jsSlice(rcSeqLocal, guideMatchIdx, guideMatchIdx + guideLen)
            val pamSeq = jsSlice(rcSeqLocal, pamMatchIdx, pamMatchIdx + pamLen)
            val fullTarget = if (config.pamDirection == "3prime") guideSeq + pamSeq else pamSeq + guideSeq

            val fwdStart = seq.length - (guideMatchIdx + guideLen)
            val fwdEnd = seq.length - guideMatchIdx

            val rcCutIdxInRc = if (config.pamDirection == "3prime")
                pamMatchIdx + config.cutOffsetFromPam
            else
                pamMatchIdx + pamLen + config.cutOffsetFromPam
            val cutIdx = seq.length - rcCutIdxInRc

            val scores = evaluateGrna(guideSeq)
            val baseEditing = calculateBaseEditingWindow(guideSeq, fwdStart, "-")

            val eff = scores.efficiency
            candidates.add(
                GrnaCandidate(
                    id = "gRNA_-_$fwdStart",
                    sequence = guideSeq,
                    fullTargetWithPam = fullTarget,
                    pam = pamSeq,
                    strand = "-",
                    startIndex = fwdStart,
                    endIndex = fwdEnd,
                    cutIndex = cutIdx,
                    scores = GrnaScores(
                        efficiency = eff,
                        specificity = 100,
                        gcContent = scores.gcContent,
                        polyT = scores.polyT,
                        hairpin = scores.hairpin,
                        finalScore = Math.round(eff * 0.8 + 20).toInt()
                    ),
                    baseEditingWindows = baseEditing
                )
            )
        }
        i++
    }

    // 3. 提供参考序列时计算脱靶特异性
    if (referenceGenomeForOffTarget != null) {
        for (k in candidates.indices) {
            val cand = candidates[k]
            val offTargets = predictOffTargets(cand.sequence, referenceGenomeForOffTarget)
            var cfdSum = 0.0
            for (ot in offTargets) {
                if (ot.mismatches > 0) cfdSum += ot.cfdScore
            }
            val specScore = (100 - (cfdSum * 12)).round().coerceIn(0, 100)
            candidates[k] = cand.copy(
                scores = cand.scores.copy(
                    specificity = specScore,
                    finalScore = Math.round(cand.scores.efficiency * 0.5 + specScore * 0.5).toInt()
                )
            )
        }
    }

    // 按总分降序（稳定排序，与 JS sort 一致）
    return candidates.sortedByDescending { it.scores.finalScore }
}

private fun Double.round(): Int = Math.round(this).toInt()

/**
 * Doench-Rule Set 2 近似效率评分：GC 区间 / Poly-T 终止 / 位置特异性偏好 / 自互补发卡。
 */
fun evaluateGrna(guideSeq: String): GrnaScores {
    val seq = guideSeq.uppercase()
    var baseScore = 50

    // 1. GC 含量（理想 40%-60%）
    val gCount = seq.count { it == 'G' }
    val cCount = seq.count { it == 'C' }
    val gc = Math.round((gCount + cCount).toDouble() / seq.length * 100).toInt()

    if (gc >= 40 && gc <= 60) {
        baseScore += 15
    } else if (gc >= 30 && gc < 40) {
        baseScore += 5
    } else if (gc > 60 && gc <= 75) {
        baseScore += 5
    } else {
        baseScore -= 20
    }

    // 2. Poly-T 终止
    val polyT = seq.contains("TTTT") || seq.contains("UUUU")
    if (polyT) baseScore -= 30

    // 3. 位置特异性偏好
    val pos20 = if (seq.length > 19) seq[19] else null
    if (pos20 == 'G') baseScore += 12
    if (pos20 == 'T') baseScore -= 15
    if (pos20 == 'C') baseScore += 5

    val pos16 = if (seq.length > 15) seq[15] else null
    if (pos16 == 'A') baseScore += 6

    val pos1 = if (seq.isNotEmpty()) seq[0] else null
    if (pos1 == 'G') baseScore -= 8

    // 4. 自互补发卡检测（4bp 茎，最小 3bp loop；verbatim 含 len*2-3 边界）
    var hairpin = false
    var len = 4
    while (len <= 6 && !hairpin) {
        var start1 = 0
        loop@ while (start1 <= seq.length - len * 2 - 3) {
            val stem1 = jsSlice(seq, start1, start1 + len)
            val remaining = jsSliceFrom(seq, start1 + len + 3)
            val stem2Rc = rcSeq(stem1)
            if (remaining.contains(stem2Rc)) {
                hairpin = true
                baseScore -= 18
                break@loop
            }
            start1++
        }
        if (hairpin) break
        len++
    }

    val finalEff = baseScore.coerceIn(1, 99)

    return GrnaScores(
        efficiency = finalEff,
        specificity = 0, // 由调用方覆盖
        gcContent = gc,
        polyT = polyT,
        hairpin = hairpin,
        finalScore = 0
    )
}

// ──────────────────────────────── CFD Off-Target Prediction ────────────────────────────────

/**
 * 在参考序列中预测 20bp guide 的脱靶位点（CFD 模型近似）。
 * main quirk：PAM 判定硬编码 NGG，与所选 Cas 无关；guide 比较只取前 20nt。
 */
fun predictOffTargets(guide: String, referenceGenome: String): List<OffTargetSite> {
    val g = guide.uppercase()
    val ref = referenceGenome.uppercase()
    val hits = ArrayList<OffTargetSite>()

    var i = 0
    while (i <= ref.length - 23) {
        val window23 = jsSlice(ref, i, i + 23)
        val windowGuide = jsSlice(window23, 0, 20)
        val windowPam = jsSlice(window23, 20, 23)

        if (windowPam.length >= 3 && windowPam[1] == 'G' && windowPam[2] == 'G') {
            val mismatches = ArrayList<Int>()
            var mismatchCount = 0

            for (j in 0 until 20) {
                if (g[j] != windowGuide[j]) {
                    mismatches.add(j)
                    mismatchCount++
                }
            }

            if (mismatchCount <= 4) {
                var cfd = 1.0
                for (pos in mismatches) {
                    val posWeight = if (pos >= 10) {
                        val distFromPam = 19 - pos
                        0.1 + 0.05 * distFromPam
                    } else {
                        0.7 + 0.02 * pos
                    }
                    cfd *= posWeight
                }
                hits.add(
                    OffTargetSite(
                        sequence = windowGuide,
                        mismatches = mismatchCount,
                        mismatchPositions = mismatches,
                        cfdScore = java.math.BigDecimal(cfd).setScale(4, java.math.RoundingMode.HALF_UP).toDouble(),
                        location = "RefIndex:$i",
                        strand = "+"
                    )
                )
            }
        }
        i++
    }

    return hits.sortedByDescending { it.cfdScore }
}

// ──────────────────────────────── HDR Donor Design with PAM Evasion ────────────────────────────────

/** main CODON_MAP verbatim（同义密码子表）。 */
private val CODON_MAP: Map<String, List<String>> = mapOf(
    "GCA" to listOf("GCG", "GCT", "GCC"), "GCC" to listOf("GCA", "GCG", "GCT"), "GCG" to listOf("GCA", "GCC", "GCT"), "GCT" to listOf("GCA", "GCC", "GCG"),
    "TGC" to listOf("TGT"), "TGT" to listOf("TGC"),
    "GAC" to listOf("GAT"), "GAT" to listOf("GAC"),
    "GAA" to listOf("GAG"), "GAG" to listOf("GAA"),
    "TTC" to listOf("TTT"), "TTT" to listOf("TTC"),
    "GGA" to listOf("GGG", "GGT", "GGC"), "GGC" to listOf("GGA", "GGG", "GGT"), "GGG" to listOf("GGA", "GGC", "GGT"), "GGT" to listOf("GGA", "GGC", "GGG"),
    "CAC" to listOf("CAT"), "CAT" to listOf("CAC"),
    "ATA" to listOf("ATT", "ATC"), "ATC" to listOf("ATT", "ATA"), "ATT" to listOf("ATC", "ATA"),
    "AAA" to listOf("AAG"), "AAG" to listOf("AAA"),
    "CTA" to listOf("CTG", "CTC", "CTT", "TTA", "TTG"), "CTC" to listOf("CTG", "CTA", "CTT", "TTA", "TTG"), "CTG" to listOf("CTC", "CTA", "CTT", "TTA", "TTG"), "CTT" to listOf("CTG", "CTC", "CTA", "TTA", "TTG"), "TTA" to listOf("TTG", "CTA", "CTG", "CTC", "CTT"), "TTG" to listOf("TTA", "CTA", "CTG", "CTC", "CTT"),
    "ATG" to listOf("ATG"),
    "AAC" to listOf("AAT"), "AAT" to listOf("AAC"),
    "CCA" to listOf("CCG", "CCT", "CCC"), "CCC" to listOf("CCA", "CCG", "CCT"), "CCG" to listOf("CCA", "CCC", "CCT"), "CCT" to listOf("CCA", "CCC", "CCG"),
    "CAA" to listOf("CAG"), "CAG" to listOf("CAA"),
    "AGA" to listOf("AGG", "CGT", "CGC", "CGA", "CGG"), "AGG" to listOf("AGA", "CGT", "CGC", "CGA", "CGG"), "CGA" to listOf("CGG", "CGC", "CGT", "AGA", "AGG"), "CGC" to listOf("CGG", "CGA", "CGT", "AGA", "AGG"), "CGG" to listOf("CGA", "CGC", "CGT", "AGA", "AGG"), "CGT" to listOf("CGG", "CGA", "CGC", "AGA", "AGG"),
    "AGC" to listOf("AGT", "TCA", "TCC", "TCG", "TCT"), "AGT" to listOf("AGC", "TCA", "TCC", "TCG", "TCT"), "TCA" to listOf("TCG", "TCC", "TCT", "AGC", "AGT"), "TCC" to listOf("TCG", "TCA", "TCT", "AGC", "AGT"), "TCG" to listOf("TCC", "TCA", "TCT", "AGC", "AGT"), "TCT" to listOf("TCG", "TCC", "TCA", "AGC", "AGT"),
    "ACA" to listOf("ACG", "ACT", "ACC"), "ACC" to listOf("ACA", "ACG", "ACT"), "ACG" to listOf("ACA", "ACC", "ACT"), "ACT" to listOf("ACA", "ACC", "ACG"),
    "GTA" to listOf("GTG", "GTC", "GTT"), "GTC" to listOf("GTA", "GTG", "GTT"), "GTG" to listOf("GTA", "GTC", "GTT"), "GTT" to listOf("GTA", "GTC", "GTG"),
    "TGG" to listOf("TGG"),
    "TAC" to listOf("TAT"), "TAT" to listOf("TAC"),
    "TAA" to listOf("TAG", "TGA"), "TAG" to listOf("TAA", "TGA"), "TGA" to listOf("TAA", "TAG")
)

/**
 * HDR 供体模板自动化设计：同源臂合成 + PAM/种子区同义回避突变。
 */
fun designHdrDonor(
    template: String,
    cutIndex: Int,
    insertSequence: String,
    homologyArmLength: Int,
    gRNA: GrnaCandidate,
    readingFrameOffset: Int? = null
): HdrDonorTemplate {
    val seq = template.uppercase()

    // 1. 原始同源臂
    val leftStart = (cutIndex - homologyArmLength).coerceAtLeast(0)
    val leftArmRaw = jsSlice(seq, leftStart, cutIndex)

    val rightEnd = (cutIndex + homologyArmLength).coerceAtMost(seq.length)
    val rightArmRaw = jsSlice(seq, cutIndex, rightEnd)

    // 2. 拼接供体
    val donorWithInsert = leftArmRaw + insertSequence.uppercase() + rightArmRaw

    // 3. 回避突变：破坏供体上的 PAM / gRNA 结合位点
    val targetFull = gRNA.fullTargetWithPam

    val silentMutations = ArrayList<SilentMutation>()
    var modifiedDonor = donorWithInsert

    val fullTargetIdx = donorWithInsert.indexOf(targetFull)

    if (fullTargetIdx != -1) {
        // 密码子相位
        val frame = readingFrameOffset ?: (fullTargetIdx % 3)
        val startCodonBound = fullTargetIdx - frame

        // PAM 在靶点内的位置：guide 之后
        val pamLocalStart = fullTargetIdx + gRNA.sequence.length
        val pamCodonStart = pamLocalStart - ((pamLocalStart - startCodonBound) % 3)

        val donorArr = modifiedDonor.toCharArray()
        var mutatedSuccess = false

        var offset = -6
        while (offset <= 9) {
            val codonIdx = pamCodonStart + offset
            if (codonIdx >= 0 && codonIdx + 3 <= donorArr.size) {
                val codon = String(donorArr, codonIdx, 3)
                val alts = CODON_MAP[codon]
                if (!alts.isNullOrEmpty()) {
                    val newCodon = alts[0] // main 固定取第一项
                    donorArr[codonIdx] = newCodon[0]
                    donorArr[codonIdx + 1] = newCodon[1]
                    donorArr[codonIdx + 2] = newCodon[2]

                    silentMutations.add(
                        SilentMutation(
                            position = codonIdx,
                            original = codon,
                            mutated = newCodon,
                            codonChange = "$codon ➔ $newCodon",
                            reason = if (offset >= 0) "PAM_destruction" else "guide_disruption"
                        )
                    )
                    mutatedSuccess = true
                }
            }
            offset += 3
        }

        if (mutatedSuccess) {
            modifiedDonor = String(donorArr)
        }
    }

    // 重新分离左右同源臂
    val finalLeftArm = jsSlice(modifiedDonor, 0, leftArmRaw.length)
    val finalRightArm = jsSliceFrom(modifiedDonor, leftArmRaw.length + insertSequence.length)

    return HdrDonorTemplate(
        leftArm = finalLeftArm,
        rightArm = finalRightArm,
        insertedMutations = insertSequence,
        fullDonor = modifiedDonor,
        silentMutationsIntroduced = silentMutations,
        warning = if (silentMutations.isEmpty())
            com.spice.nativeapp.core.I18n.t("警告: 未能在供体模板中引入同义突变。Cas9 可能会重复剪切已修复的目标区！")
        else null
    )
}

// ──────────────────────────────── Prime Editing pegRNA Design ────────────────────────────────

/**
 * Prime Editing pegRNA 设计：spacer + scaffold + 3' 延伸（RTT + PBS）。
 */
fun designPegRna(
    template: String,
    gRNA: GrnaCandidate,
    editStartIndex: Int,
    editSequence: String,
    pbsLength: Int = 13,
    rttLength: Int = 16
): PegRnaDesign {
    val seq = template.uppercase()

    val nickIndex = gRNA.cutIndex

    // PBS：切点上游 pbsLength nt（目标链），pegRNA 上为其反向互补
    val pbsStart = nickIndex - pbsLength
    val pbsTargetStrand = jsSlice(seq, pbsStart, nickIndex)
    val pbsPegRna = rcSeq(pbsTargetStrand)

    // RTT：切点下游 rttLength nt，替换入编辑序列
    val genomicDownstream = jsSlice(seq, nickIndex, nickIndex + rttLength)

    val relativeEditOffset = editStartIndex - nickIndex
    val rttGenomicEdited: String = if (relativeEditOffset >= 0 && relativeEditOffset < rttLength) {
        jsSlice(genomicDownstream, 0, relativeEditOffset) + editSequence +
            jsSliceFrom(genomicDownstream, relativeEditOffset + editSequence.length)
    } else {
        editSequence + jsSliceFrom(genomicDownstream, editSequence.length)
    }

    val rttPegRna = rcSeq(jsSlice(rttGenomicEdited, 0, rttLength))

    // PBS 解链温度：4*(G+C) + 2*(A+T)
    val gCount = pbsPegRna.count { it == 'G' }
    val cCount = pbsPegRna.count { it == 'C' }
    val aCount = pbsPegRna.count { it == 'A' }
    val tCount = pbsPegRna.count { it == 'T' }
    val pbsTm = 4 * (gCount + cCount) + 2 * (aCount + tCount)

    val fullExtension = rttPegRna + pbsPegRna
    val scaffold = "GTTTTAGAGCTAGAAATAGCAAGTTAAAATAAGGCTAGTCCGTTATCAACTTGAAAAAGTGGCACCGAGTCGGTGC"
    val fullPeg = gRNA.sequence + scaffold + fullExtension

    return PegRnaDesign(
        gRNA = gRNA.sequence,
        pbs = pbsPegRna,
        pbsLength = pbsLength,
        pbsTm = pbsTm,
        rtt = rttPegRna,
        rttLength = rttLength,
        full3PrimeExtension = fullExtension,
        pegRnaSequence = fullPeg
    )
}

// ──────────────────────────────── Base Editing Window Calculation ────────────────────────────────

/**
 * 评估 gRNA 对 Base Editing（CBE/ABE）的适配性：编辑窗口为 protospacer 第 4-8 位（1-indexed）。
 */
fun calculateBaseEditingWindow(
    guideSeq: String,
    guideStartIndex: Int,
    strand: String
): BaseEditingResult {
    val cbeCandidates = ArrayList<BaseEditCandidate>()
    val abeCandidates = ArrayList<BaseEditCandidate>()

    for (i in 3..7) {
        if (i >= guideSeq.length) break
        val base = guideSeq[i]
        val windowPos = i + 1

        val absoluteIdx = if (strand == "+") guideStartIndex + i else guideStartIndex + (20 - 1 - i)

        val baseEff = if (windowPos == 5 || windowPos == 6) 85 else (if (windowPos == 4 || windowPos == 7) 60 else 35)

        if (base == 'C') {
            cbeCandidates.add(BaseEditCandidate(absoluteIdx, "C", "T", windowPos, baseEff))
        } else if (base == 'G' && strand == "-") {
            cbeCandidates.add(BaseEditCandidate(absoluteIdx, "G", "T", windowPos, baseEff))
        }

        if (base == 'A') {
            abeCandidates.add(BaseEditCandidate(absoluteIdx, "A", "G", windowPos, baseEff))
        } else if (base == 'T' && strand == "-") {
            abeCandidates.add(BaseEditCandidate(absoluteIdx, "T", "G", windowPos, baseEff))
        }
    }

    return BaseEditingResult(cbeCandidates, abeCandidates)
}
