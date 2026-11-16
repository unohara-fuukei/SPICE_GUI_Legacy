package com.spice.nativeapp.core

/**
 * 高级分子生物学工具 —— 1:1 移植 main `src/lib/genome/advancedTools.ts` 中
 * 沉默诱变、融合阅读框检查、GC 含量滑动窗口三段纯算法。
 * 不依赖外部二进制；warnings 文案沿用 native I18n 词典（与 main paraglide 键对应）。
 */
object AdvancedTools {

    /** 密码子 → 氨基酸（对齐 main CODON_TO_AA） */
    private val CODON_TO_AA: Map<String, String> = mapOf(
        "TTT" to "F", "TTC" to "F", "TTA" to "L", "TTG" to "L",
        "CTT" to "L", "CTC" to "L", "CTA" to "L", "CTG" to "L",
        "ATT" to "I", "ATC" to "I", "ATA" to "I", "ATG" to "M",
        "GTT" to "V", "GTC" to "V", "GTA" to "V", "GTG" to "V",
        "TCT" to "S", "TCC" to "S", "TCA" to "S", "TCG" to "S", "AGT" to "S", "AGC" to "S",
        "CCT" to "P", "CCC" to "P", "CCA" to "P", "CCG" to "P",
        "ACT" to "T", "ACC" to "T", "ACA" to "T", "ACG" to "T",
        "GCT" to "A", "GCC" to "A", "GCA" to "A", "GCG" to "A",
        "TAT" to "Y", "TAC" to "Y", "TAA" to "*", "TAG" to "*",
        "CAT" to "H", "CAC" to "H", "CAA" to "Q", "CAG" to "Q",
        "AAT" to "N", "AAC" to "N", "AAA" to "K", "AAG" to "K",
        "GAT" to "D", "GAC" to "D", "GAA" to "E", "GAG" to "E",
        "TGT" to "C", "TGC" to "C", "TGA" to "*", "TGG" to "W",
        "CGT" to "R", "CGC" to "R", "CGA" to "R", "CGG" to "R", "AGA" to "R", "AGG" to "R",
        "GGT" to "G", "GGC" to "G", "GGA" to "G", "GGG" to "G",
    )

    /** 氨基酸 → 同义密码子（对齐 main SYNONYMOUS_CODONS） */
    private val SYNONYMOUS_CODONS: Map<String, List<String>> = mapOf(
        "F" to listOf("TTT", "TTC"),
        "L" to listOf("TTA", "TTG", "CTT", "CTC", "CTA", "CTG"),
        "I" to listOf("ATT", "ATC", "ATA"),
        "M" to listOf("ATG"),
        "V" to listOf("GTT", "GTC", "GTA", "GTG"),
        "S" to listOf("TCT", "TCC", "TCA", "TCG", "AGT", "AGC"),
        "P" to listOf("CCT", "CCC", "CCA", "CCG"),
        "T" to listOf("ACT", "ACC", "ACA", "ACG"),
        "A" to listOf("GCT", "GCC", "GCA", "GCG"),
        "Y" to listOf("TAT", "TAC"),
        "H" to listOf("CAT", "CAC"),
        "Q" to listOf("CAA", "CAG"),
        "N" to listOf("AAT", "AAC"),
        "K" to listOf("AAA", "AAG"),
        "D" to listOf("GAT", "GAC"),
        "E" to listOf("GAA", "GAG"),
        "C" to listOf("TGT", "TGC"),
        "W" to listOf("TGG"),
        "R" to listOf("CGT", "CGC", "CGA", "CGG", "AGA", "AGG"),
        "G" to listOf("GGT", "GGC", "GGA", "GGG"),
        "*" to listOf("TAA", "TAG", "TGA"),
    )

    data class SilentMutation(
        val position: Int,
        val originalBase: String,
        val newBase: String,
        val codonBefore: String,
        val codonAfter: String,
    )

    data class SilentMutagenesisResult(
        val modifiedSequence: String,
        val mutations: List<SilentMutation>,
        val restrictionSiteAdded: String? = null,
        val restrictionSiteRemoved: String? = null,
        val warnings: List<String>,
    )

    /**
     * 通过沉默突变（不改变蛋白的同义密码子替换）添加或移除一个限制酶识别位点。
     * addSite / removeSite 二选一；readingFrame 为起始阅读框偏移（默认 0）。
     */
    fun simulateSilentMutagenesis(
        dnaSeq: String,
        addSite: String? = null,
        removeSite: String? = null,
        readingFrame: Int = 0,
    ): SilentMutagenesisResult {
        val warnings = mutableListOf<String>()
        var seq = dnaSeq.uppercase().filter { it in "ATCGN" }
        val frame = readingFrame
        val mutations = mutableListOf<SilentMutation>()

        addSite?.let { siteRaw ->
            val site = siteRaw.uppercase()
            var applied = false
            outer@ for (i in frame..seq.length - site.length) {
                var possible = true
                val changes = mutableListOf<SilentMutation>()
                for (j in site.indices) {
                    val pos = i + j
                    val codonStart = ((pos - frame) / 3) * 3 + frame
                    val co = pos - codonStart
                    val origCodon = seq.substring(codonStart, minOf(codonStart + 3, seq.length))
                    if (origCodon.length < 3) { possible = false; break }
                    val origAa = CODON_TO_AA[origCodon]
                    if (origAa == null) { possible = false; break }
                    val newCodon = origCodon.substring(0, co) + site[j] + origCodon.substring(co + 1)
                    val newAa = CODON_TO_AA[newCodon]
                    if (newAa != origAa) { possible = false; break }
                    if (newCodon != origCodon) {
                        changes += SilentMutation(pos, seq[pos].toString(), site[j].toString(), origCodon, newCodon)
                    }
                }
                if (possible && changes.isNotEmpty()) {
                    val arr = seq.toCharArray()
                    for (c in changes) {
                        arr[c.position] = c.newBase[0]
                        mutations += c
                    }
                    seq = String(arr)
                    applied = true
                    break@outer
                }
            }
            if (!applied) {
                warnings += I18n.tf("无法通过沉默突变在阅读框 {v1} 中引入位点 {v2}", frame, site)
                return SilentMutagenesisResult(seq, mutations, warnings = warnings)
            }
            return SilentMutagenesisResult(seq, mutations, restrictionSiteAdded = site, warnings = warnings)
        }

        removeSite?.let { siteRaw ->
            val site = siteRaw.uppercase()
            var applied = false
            var pos = seq.indexOf(site)
            while (pos != -1) {
                var possible = true
                val changes = mutableListOf<SilentMutation>()
                for (j in site.indices) {
                    val absPos = pos + j
                    val codonStart = ((absPos - frame) / 3) * 3 + frame
                    val co = absPos - codonStart
                    val origCodon = seq.substring(codonStart, minOf(codonStart + 3, seq.length))
                    if (origCodon.length < 3) { possible = false; break }
                    val origAa = CODON_TO_AA[origCodon]
                    if (origAa == null) { possible = false; break }
                    val syns = SYNONYMOUS_CODONS[origAa] ?: emptyList()
                    var found = false
                    for (syn in syns) {
                        if (syn[co] != site[j]) {
                            val newAa = CODON_TO_AA[syn]
                            if (newAa == origAa) {
                                changes += SilentMutation(absPos, seq[absPos].toString(), syn[co].toString(), origCodon, syn)
                                found = true
                                break
                            }
                        }
                    }
                    if (!found) { possible = false; break }
                }
                if (possible && changes.isNotEmpty()) {
                    val arr = seq.toCharArray()
                    for (c in changes) {
                        arr[c.position] = c.newBase[0]
                        mutations += c
                    }
                    seq = String(arr)
                    applied = true
                    break
                }
                pos = seq.indexOf(site, pos + 1)
            }
            if (!applied) {
                warnings += I18n.tf("无法通过沉默突变移除位点 {v1}", site)
                return SilentMutagenesisResult(seq, mutations, warnings = warnings)
            }
            return SilentMutagenesisResult(seq, mutations, restrictionSiteRemoved = site, warnings = warnings)
        }

        return SilentMutagenesisResult(seq, mutations, warnings = warnings)
    }

    data class FusionFrameResult(
        val inFrame: Boolean,
        val frameOffset: Int,
        val junctionSequence: String,
        val junctionTranslation: String,
        val warnings: List<String>,
    )

    /**
     * 检查两段特征在连接处是否处于同一阅读框（融合蛋白设计关键校验）。
     * 坐标为 main 一致的 0-based 区间端点；frame 为起始阅读框。
     */
    fun checkFusionReadingFrame(
        dnaSeq: String,
        feat1Start: Int,
        feat1End: Int,
        feat2Start: Int,
        feat2End: Int,
        frame: Int = 0,
    ): FusionFrameResult {
        val warnings = mutableListOf<String>()
        val seq = dnaSeq.uppercase()

        val feat1EndFrame = (feat1End - frame + 1) % 3
        val feat2StartFrame = (feat2Start - frame) % 3
        val inFrame = feat1EndFrame == 2
        val frameOffset = ((feat2Start - feat1End - 1) % 3 + 3) % 3

        val junctionStart = maxOf(0, feat1End - 6)
        val junctionEnd = minOf(seq.length, feat2Start + 6)
        val junctionSeq = if (junctionEnd > junctionStart) seq.substring(junctionStart, junctionEnd) else ""
        val junctionTranslation = Bio.translate(junctionSeq, 0)

        if (!inFrame) {
            warnings += I18n.tf("融合阅读框不一致: feat1 结束于密码子位置 {v1}, feat2 开始于密码子位置 {v2}", feat1EndFrame, feat2StartFrame)
        }
        if (frameOffset != 0) {
            warnings += I18n.tf("两特征间存在 {v1} bp 间隔，可能导致移码", frameOffset)
        }

        return FusionFrameResult(inFrame, frameOffset, junctionSeq, junctionTranslation, warnings)
    }

    data class GcWindowPoint(val position: Int, val gc: Double)

    /** GC 含量滑动窗口（默认 50 bp 窗、10 bp 步长）。 */
    fun gcSlidingWindow(dnaSeq: String, windowSize: Int = 50, stepSize: Int = 10): List<GcWindowPoint> {
        val seq = dnaSeq.uppercase()
        val points = mutableListOf<GcWindowPoint>()
        var i = 0
        while (i < seq.length) {
            val end = minOf(i + windowSize, seq.length)
            val window = seq.substring(i, end)
            points += GcWindowPoint(i, Bio.gcContent(window))
            i += stepSize
        }
        return points
    }

    /** 互补碱基配对表（对齐 main DNA_PAIRS；N 不参与配对） */
    private val DNA_PAIRS: Map<Char, Char> = mapOf('A' to 'T', 'T' to 'A', 'G' to 'C', 'C' to 'G')

    private fun canPair(a: Char, b: Char): Boolean = DNA_PAIRS[a.uppercaseChar()] == b.uppercaseChar()

    data class AnnealResult(
        val doubleStranded: String,
        val topStrand: String,
        val bottomStrand: String,
        val leftOverhang: String,
        val rightOverhang: String,
        val topLength: Int,
        val bottomLength: Int,
        val duplexLength: Int,
        val tm: Double,
        val warnings: List<String>,
    )

    /**
     * 退火两条寡核苷酸为含可能粘性末端的双链产物（1:1 对齐 main `annealOligos`）。
     * 正向为 top 链 5'→3'；反向按 5'→3' 提供、代表 bottom 链。最佳偏移滑窗配对，
     * 匹配 <4 bp 判为无法退火；双链 Tm = 64.9 + 41·(GC−16.4)/双链长度。
     */
    fun annealOligos(forwardOligo: String, reverseOligo: String): AnnealResult {
        val top = forwardOligo.uppercase().filter { it in "ATCGN" }
        val revOligo = reverseOligo.uppercase().filter { it in "ATCGN" }
        val bottomRc = Bio.revComp(revOligo)
        val warnings = mutableListOf<String>()

        if (top.length < 4 || revOligo.length < 4) {
            warnings += I18n.t("引物长度过短 (<4bp)，退火可能不稳定")
        }

        var bestOffset = 0
        var bestMatch = 0
        val minOffset = -(bottomRc.length - 4)
        val maxOffset = top.length - 4
        for (offset in minOffset..maxOffset) {
            var matchCount = 0
            for (i in bottomRc.indices) {
                val j = i + offset
                if (j >= 0 && j < top.length && canPair(top[j], bottomRc[i])) matchCount++
            }
            if (matchCount > bestMatch) {
                bestMatch = matchCount
                bestOffset = offset
            }
        }

        if (bestMatch < 4) {
            warnings += I18n.t("两条寡核苷酸无法有效退火 (匹配 <4bp)")
        }

        val leftOverhangTop = if (bestOffset > 0) top.substring(0, bestOffset) else ""
        val topStart = maxOf(0, bestOffset)
        val bottomEndInTop = bestOffset + bottomRc.length
        val rightOverhangTop = if (bottomEndInTop < top.length) top.substring(bottomEndInTop) else ""

        val leftOverhangBottom = if (bestOffset < 0) bottomRc.substring(0, -bestOffset) else ""
        val rightOverhangBottom = if (bottomEndInTop > top.length) bottomRc.substring(top.length - bestOffset) else ""

        val duplexStart = maxOf(0, bestOffset)
        val duplexEnd = minOf(top.length, bottomEndInTop)
        val duplexLength = maxOf(0, duplexEnd - duplexStart)
        val duplexSeq = if (duplexEnd > duplexStart) top.substring(duplexStart, duplexEnd) else ""
        val gc = Bio.gcContent(duplexSeq)
        val tm = if (duplexLength > 0) {
            val raw = 64.9 + 41.0 * (gc - 16.4) / maxOf(duplexLength, 1)
            Math.round(raw * 10) / 10.0
        } else 0.0

        val padLen = maxOf(0, -bestOffset)
        val topAligned = "-".repeat(padLen) + top.substring(topStart, duplexEnd) + (rightOverhangTop)
        val bottomAligned = "-".repeat(maxOf(0, bestOffset)) +
            bottomRc.substring(maxOf(0, -bestOffset), maxOf(0, -bestOffset) + duplexLength) +
            rightOverhangBottom
        val doubleStranded = topAligned + "\n" + bottomAligned.reversed()

        val leftOverhang = leftOverhangTop.ifEmpty { Bio.revComp(rightOverhangBottom) }
        val rightOverhang = rightOverhangTop.ifEmpty { Bio.revComp(leftOverhangBottom) }

        return AnnealResult(
            doubleStranded = doubleStranded,
            topStrand = top,
            bottomStrand = revOligo,
            leftOverhang = leftOverhang,
            rightOverhang = rightOverhang,
            topLength = top.length,
            bottomLength = revOligo.length,
            duplexLength = duplexLength,
            tm = tm,
            warnings = warnings,
        )
    }

    // ───────────────── 引物 5' 修饰（SnapGene parity，对齐 main runPrimerModification） ─────────────────

    /** main PrimerModificationResult（enzymeSite 为去 ^ 的识别位点；length 不含 '/' 前缀） */
    data class PrimerModResult(
        val original: String,
        val modified: String,
        val enzymeSite: String,
        val protectBases: String,
        val phosphorylated: Boolean,
        val length: Int
    )

    /**
     * main gene/+page.svelte runPrimerModification 1:1：
     * enzymeSite = 酶库识别位点（去 `^`、大写）；protect = 'GCA'.slice(0, protectBases)（越界截断到 3）；
     * modified = (phosphorylated ? '/' : '') + protect + enzymeSite + primer；
     * length = modified 去掉 '/' 后的长度。
     */
    fun modifyPrimer(primer: String, enzymeName: String, protectBases: Int, phosphorylated: Boolean): PrimerModResult {
        val enzymeSite = Enzymes.byName(enzymeName)?.site?.replace("^", "")?.uppercase().orEmpty()
        val n = protectBases.coerceIn(0, 6)
        val protect = if (n <= 0) "" else "GCA".substring(0, minOf(n, "GCA".length))
        val modified = (if (phosphorylated) "/" else "") + protect + enzymeSite + primer
        val length = modified.replace("/", "").length
        return PrimerModResult(
            original = primer,
            modified = modified,
            enzymeSite = enzymeSite,
            protectBases = protect,
            phosphorylated = phosphorylated,
            length = length,
        )
    }
}
