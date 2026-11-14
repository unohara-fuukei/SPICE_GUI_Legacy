package com.spice.nativeapp.core

/**
 * SPICE 蛋白质生物物理舱算法层（对齐 main 分支 lib/genome/proteinView.ts 1:1）
 *
 * 覆盖：AA 三字母/单字母互转、PTM 预测（磷酸化/N-连接糖基化/二硫键/泛素化）、
 * 蛋白物理性质（MW/pI/消光系数/芳香度/GRAVY/电荷）、密码子频率表、buildProteinView 聚合。
 *
 * 与原版一致的 quirk（刻意保留，勿"修复"）：
 * 1. protViewAromaticityLabel/protViewGravyLabel/protViewPiLabel 的 zh 原文均为 "分子量:"（上游词条复用笔误）；
 *    本文件照常取用 main zh 原文，不做纠正。
 * 2. extCoeff = (trp*5500 + tyr*1490) / n * n 恒等于 trp*5500+tyr*1490（/n*n 互消，未用），返回的是
 *    extCoeffCys（含 Cys*125）；instability 为"疏水性×0.1 均值"的简化式，非标准 Guruprasad。
 * 3. pI 简化线性式（acidic>basic → 3.0+0.5Δ；basic>acidic → 10.0−0.5Δ），非二分法。
 * 4. PTM 磷酸化 motif 正则（/R.[ST]/g、/[ST]xxE/g 等）只约束锚点字符，匹配起点可落在任意残基；
 *    glycosylation N-X-S/T 守卫已含在正则 [^P][ST][^P] 内，运行时 if(gmatch[0][1]!=="P" && gmatch[0][2]!=="P")
 *    为永真冗余守卫（同 main 逻辑）。
 * 5. buildProteinView 里 aminoAcidData 逐氨基酸展开为 (aa, positionInCodon, aminoAcidIndex,
 *    sequenceIndex=aminoAcidIndex*3+positionInCodon) 三元组（getAminoAcidDataForEachBaseOfDna 蛋白序列
 *    语义）；features 参数透传（面板调用恒为空数组）。
 */

// ──────────────────────────────── Types ────────────────────────────────

data class ProteinFeature(
    val id: String,
    val name: String,
    val type: String,
    val start: Int, // 0-based AA position
    val end: Int,
    val color: String,
    val description: String? = null
)

enum class PtmType { PHOSPHORYLATION, GLYCOSYLATION, UBIQUITINATION, ACETYLATION, METHYLATION, DISULFIDE }

data class PostTranslationalModification(
    val type: PtmType,
    val position: Int,
    val residue: String,
    val motif: String,
    val confidence: Double
)

data class CodonFrequency(
    val codon: String,
    val aa: String,
    val count: Int,
    val fraction: Double,
    val perThousand: Double
)

data class ProteinProperties(
    val length: Int,
    val molecularWeight: Double, // Da
    val isoelectricPoint: Double, // pI
    val extinctionCoeff: Double, // M-1 cm-1 at 280nm
    val aromaticity: Double,
    val instability: Double,
    val gravy: Double, // grand average of hydropathy
    val charge: Double // at pH 7
)

/** 蛋白序列展开后的逐碱基氨基酸数据（aa.ts AminoAcidDataForBase 的蛋白序列语义） */
data class AaBaseData(
    val aminoAcid: String?,      // 单字母（null 表示非密码子碱基；蛋白序列语义下恒非 null）
    val positionInCodon: Int?,
    val aminoAcidIndex: Int?,
    val sequenceIndex: Int
)

data class ProteinViewData(
    val sequence: String,
    val dnaSequence: String,
    val features: List<ProteinFeature>,
    val ptms: List<PostTranslationalModification>,
    val properties: ProteinProperties,
    val codonFrequencies: List<CodonFrequency>,
    val aminoAcidData: List<AaBaseData>
)

// ──────────────────────────────── Amino acid maps ────────────────────────────────

private val AA_3LETTER: Map<String, String> = mapOf(
    "A" to "Ala", "R" to "Arg", "N" to "Asn", "D" to "Asp", "C" to "Cys",
    "E" to "Glu", "Q" to "Gln", "G" to "Gly", "H" to "His", "I" to "Ile",
    "L" to "Leu", "K" to "Lys", "M" to "Met", "F" to "Phe", "P" to "Pro",
    "S" to "Ser", "T" to "Thr", "W" to "Trp", "Y" to "Tyr", "V" to "Val",
    "*" to "***"
)

private val AA_MONO_MW: Map<Char, Double> = mapOf(
    'A' to 71.03711, 'R' to 156.10111, 'N' to 114.04293, 'D' to 115.02694, 'C' to 103.00919,
    'E' to 129.04259, 'Q' to 128.05858, 'G' to 57.02146, 'H' to 137.05891, 'I' to 113.08406,
    'L' to 113.08406, 'K' to 128.09496, 'M' to 131.04049, 'F' to 147.06841, 'P' to 97.05276,
    'S' to 87.03203, 'T' to 101.04768, 'W' to 186.07931, 'Y' to 163.06333, 'V' to 99.06841
)

private val AA_HYDRO: Map<Char, Double> = mapOf(
    'A' to 1.8, 'R' to -4.5, 'N' to -3.5, 'D' to -3.5, 'C' to 2.5,
    'E' to -3.5, 'Q' to -3.5, 'G' to -0.4, 'H' to -3.2, 'I' to 4.5,
    'L' to 3.8, 'K' to -3.9, 'M' to 1.9, 'F' to 2.8, 'P' to -1.6,
    'S' to -0.8, 'T' to -0.7, 'W' to -0.9, 'Y' to -1.3, 'V' to 4.2
)

private val AROMATIC_AA = setOf('F', 'W', 'Y', 'H')

// ──────────────────────────────── Conversion ────────────────────────────────

/** aaString → "Ala-Arg-..."；未知字符 → Xxx（main AA_3LETTER[c] || "Xxx"） */
fun toThreeLetter(aaString: String): String =
    aaString.map { AA_3LETTER[it.uppercaseChar().toString()] ?: "Xxx" }.joinToString("-")

/** "Ala-Arg-..." → aaString；未知三字母 → X */
fun toOneLetter(threeLetter: String): String {
    val reversed = AA_3LETTER.entries.associate { (k, v) -> v to k }
    return threeLetter.split(Regex("[-\\s]+")).map { reversed[it] ?: "X" }.joinToString("")
}

// ──────────────────────────────── PTM Prediction ────────────────────────────────

// 磷酸化 motif：只约束锚点字符（JS 原版语义），逐位置扫描等价 exec 循环
private val PHOSPHO_MOTIFS: List<Triple<Regex, String, String>> = listOf(
    Triple(Regex("S[T]E"), "CK1", "S"),
    Triple(Regex("S[T]P"), "Proline-directed", "S"),
    Triple(Regex("R.[ST]"), "PKA", "S"),
    Triple(Regex("[ST]xxE"), "CK2", "S")
)

fun predictPTMs(proteinSeq: String): List<PostTranslationalModification> {
    val ptms = mutableListOf<PostTranslationalModification>()
    val seq = proteinSeq.uppercase()

    // Phosphorylation（g 标志 exec 全匹配等价：findAll 逐个命中，零宽保护不需要—— motif 最短 2 字符）
    for ((motif, type, _) in PHOSPHO_MOTIFS) {
        for (m in motif.findAll(seq)) {
            ptms += PostTranslationalModification(
                PtmType.PHOSPHORYLATION,
                m.range.first,
                seq[m.range.first].toString(),
                type,
                0.7
            )
        }
    }

    // N-linked glycosylation: /N[^P][ST][^P]/gi
    for (g in Regex("N[^P][ST][^P]", RegexOption.IGNORE_CASE).findAll(seq)) {
        val g0 = g.value
        // 原版守卫：gmatch[0][1] !== "P" && gmatch[0][2] !== "P"（正则已排除 [1]="P"；[2]∈{S,T} 恒非 P → 永真）
        if (g0[1] != 'P' && g0[2] != 'P') {
            ptms += PostTranslationalModification(
                PtmType.GLYCOSYLATION,
                g.range.first,
                seq[g.range.first].toString(),
                "N-X-S/T (N-linked)",
                0.85
            )
        }
    }

    // Cysteine (disulfide) / Lysine (ubiquitination)
    for (i in seq.indices) {
        if (seq[i] == 'C') {
            ptms += PostTranslationalModification(PtmType.DISULFIDE, i, "C", "Cysteine", 0.5)
        }
        if (seq[i] == 'K') {
            ptms += PostTranslationalModification(PtmType.UBIQUITINATION, i, "K", "Lysine", 0.3)
        }
    }
    return ptms
}

// ──────────────────────────────── Properties ────────────────────────────────

fun calculateProteinProperties(proteinSeq: String): ProteinProperties {
    val seq = proteinSeq.uppercase()
    val n = seq.length
    if (n == 0) {
        return ProteinProperties(0, 0.0, 7.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    // Molecular weight（肽键脱水）
    var mw = 18.0153
    for (c in seq) mw += AA_MONO_MW[c] ?: 0.0
    mw -= (n - 1) * 18.0153

    // Extinction coefficient（原版 /n*n 互消 quirk 保留；返回含 Cys 项）
    var tyr = 0; var trp = 0; var cys = 0
    for (c in seq) {
        when (c) { 'Y' -> tyr++; 'W' -> trp++; 'C' -> cys++ ; else -> {} }
    }
    val extCoeffCys = trp * 5500.0 + tyr * 1490.0 + cys * 125.0

    // Aromaticity
    val aromatic = seq.count { it in AROMATIC_AA }
    val aromaticity = aromatic.toDouble() / n

    // Instability index（原版简化式：AA_HYDRO*0.1 的均值）
    var instability = 0.0
    if (n > 1) {
        for (i in 0 until n - 1) instability += (AA_HYDRO[seq[i]] ?: 0.0) * 0.1
        instability /= n
    }

    // GRAVY
    var gravy = 0.0
    for (c in seq) gravy += AA_HYDRO[c] ?: 0.0
    gravy /= n

    // Charge at pH 7（H 计 0.1；N/C 末端 +1-1 互消）
    var charge = 0.0
    for (c in seq) {
        if (c == 'K' || c == 'R') charge += 1.0
        if (c == 'D' || c == 'E') charge -= 1.0
        if (c == 'H') charge += 0.1
    }

    // pI（简化线性式）
    var pI = 7.0
    val acidic = seq.count { it == 'D' || it == 'E' }
    val basic = seq.count { it == 'K' || it == 'R' }
    if (acidic > basic) pI = 3.0 + (acidic - basic) * 0.5
    else if (basic > acidic) pI = 10.0 - (basic - acidic) * 0.5

    return ProteinProperties(
        n,
        Math.round(mw * 100) / 100.0,
        Math.round(pI * 100) / 100.0,
        Math.round(extCoeffCys).toDouble(),  // JS Math.round(Long)
        Math.round(aromaticity * 1000) / 1000.0,
        Math.round(instability * 100) / 100.0,
        Math.round(gravy * 1000) / 1000.0,
        Math.round(charge * 100) / 100.0
    )
}

// ──────────────────────────────── Codon Frequency ────────────────────────────────

private val CODON_AA: Map<String, String> = mapOf(
    "TTT" to "F", "TTC" to "F", "TTA" to "L", "TTG" to "L",
    "CTT" to "L", "CTC" to "L", "CTA" to "L", "CTG" to "L",
    "ATT" to "I", "ATC" to "I", "ATA" to "I",
    "GTT" to "V", "GTC" to "V", "GTA" to "V", "GTG" to "V",
    "TCT" to "S", "TCC" to "S", "TCA" to "S", "TCG" to "S",
    "CCT" to "P", "CCC" to "P", "CCA" to "P", "CCG" to "P",
    "ACT" to "T", "ACC" to "T", "ACA" to "T", "ACG" to "T",
    "GCT" to "A", "GCC" to "A", "GCA" to "A", "GCG" to "A",
    "TAT" to "Y", "TAC" to "Y", "TAA" to "*", "TAG" to "*",
    "CAT" to "H", "CAC" to "H", "CAA" to "Q", "CAG" to "Q",
    "AAT" to "N", "AAC" to "N", "AAA" to "K", "AAG" to "K",
    "GAT" to "D", "GAC" to "D", "GAA" to "E", "GAG" to "E",
    "TGT" to "C", "TGC" to "C", "TGA" to "*", "TGG" to "W",
    "CGT" to "R", "CGC" to "R", "CGA" to "R", "CGG" to "R",
    "AGA" to "R", "AGG" to "R", "AGT" to "S", "AGC" to "S",
    "GGT" to "G", "GGC" to "G", "GGA" to "G", "GGG" to "G"
)

fun calculateCodonFrequencies(dnaSeq: String): List<CodonFrequency> {
    val seq = dnaSeq.uppercase().filter { it in "ATCG" }
    // JS object 键序 = 首次出现序；LinkedHashMap 保序
    val counts = LinkedHashMap<String, Int>()
    var i = 0
    while (i + 2 < seq.length) {
        val codon = seq.substring(i, i + 3)
        counts[codon] = (counts[codon] ?: 0) + 1
        i += 3
    }
    val total = counts.values.sum()
    val aaTotals = HashMap<String, Int>()
    for ((codon, cnt) in counts) {
        val aa = CODON_AA[codon] ?: "X"
        aaTotals[aa] = (aaTotals[aa] ?: 0) + cnt
    }
    return counts.entries.map { (codon, count) ->
        val aa = CODON_AA[codon] ?: "X"
        val aaTotal = aaTotals[aa] ?: 1
        CodonFrequency(
            codon, aa, count,
            if (aaTotal > 0) count.toDouble() / aaTotal else 0.0,
            if (total > 0) count * 1000.0 / total else 0.0
        )
    }.sortedWith(compareBy({ it.aa }, { -it.fraction }))
}

// ──────────────────────────────── Main Builder ────────────────────────────────

/** 简并密码子回退表（main aa.ts getAA 的 fallback，键小写；'x'→'n' 替换后查表） */
private val DEGENERATE_CODON: Map<String, Char> = mapOf(
    "atn" to 'I', "ctn" to 'L', "gtn" to 'V', "ttn" to 'F',
    "aan" to 'N', "agn" to 'S', "gan" to 'D', "ggn" to 'G',
    "can" to 'H', "cgn" to 'R', "gcn" to 'A',
    "aay" to 'N', "gay" to 'D', "gar" to 'E',
    "tgy" to 'C', "tay" to 'Y', "tgr" to '*',
    "tga" to '*', "tag" to '*', "taa" to '*',
    "xxx" to 'X'
)

/**
 * main aa.ts getAA 1:1：IUPAC 展开解析表（threeLetterSequenceStringToAminoAcidMap 含
 * expandAndResolve 生成的全部 IUPAC 3-mer，如 CGY→R、GSN→G、ATN→I）→
 * 简并回退表（xxx→n 查 DEGENERATE_CODON）→ 'x'→X。
 */
private fun getAaOfTriplet(triplet: String): Char {
    val key = triplet.lowercase()
    IUPAC_EXPANDED[key]?.let { return it }
    return DEGENERATE_CODON[key.replaceFirst("x", "n")] ?: 'X'  // main: "x".toUpperCase() → proteinAlphabetView["X"]
}

/**
 * main sequence.ts getAminoAcidStringFromSequenceString 1:1（forward=true, doNotExcludeAsterisk=false）：
 * 只取完整密码子；最后一个氨基酸若为 '*' 则剔除（index >= perBase.length-3 的尾部守卫，
 * perBase 按碱基展开 → 只可能命中最后一个氨基酸）。
 */
/** IUPAC 核苷酸码集（main threeLetterSequenceStringToAminoAcidMap.ts IUPAC，U 感知） */
private val IUPAC: Map<Char, List<Char>> = mapOf(
    'A' to listOf('A'), 'C' to listOf('C'), 'G' to listOf('G'), 'T' to listOf('T'), 'U' to listOf('U'),
    'R' to listOf('A', 'G'), 'Y' to listOf('C', 'T', 'U'), 'K' to listOf('G', 'T', 'U'), 'M' to listOf('A', 'C'),
    'S' to listOf('G', 'C'), 'W' to listOf('A', 'T', 'U'), 'B' to listOf('C', 'G', 'T', 'U'),
    'D' to listOf('A', 'G', 'T', 'U'), 'H' to listOf('A', 'C', 'T', 'U'), 'V' to listOf('A', 'C', 'G'),
    'N' to listOf('A', 'C', 'G', 'T', 'U'), 'X' to listOf('A', 'C', 'G', 'T', 'U')
)

/** 初始标准密码子表（小写键；U/T 双写） */
private val INIT_CODON_AA: Map<String, Char> = mapOf(
    "gct" to 'A', "gcc" to 'A', "gca" to 'A', "gcg" to 'A', "gcu" to 'A',
    "cgt" to 'R', "cgc" to 'R', "cga" to 'R', "cgg" to 'R', "aga" to 'R', "agg" to 'R', "cgu" to 'R',
    "aat" to 'N', "aac" to 'N', "aau" to 'N',
    "gat" to 'D', "gac" to 'D', "gau" to 'D',
    "tgt" to 'C', "tgc" to 'C', "ugu" to 'C', "ugc" to 'C',
    "gaa" to 'E', "gag" to 'E',
    "caa" to 'Q', "cag" to 'Q',
    "ggt" to 'G', "ggc" to 'G', "gga" to 'G', "ggg" to 'G', "ggu" to 'G',
    "cat" to 'H', "cac" to 'H', "cau" to 'H',
    "att" to 'I', "atc" to 'I', "ata" to 'I', "auu" to 'I', "auc" to 'I', "aua" to 'I',
    "ctt" to 'L', "ctc" to 'L', "cta" to 'L', "ctg" to 'L', "tta" to 'L', "ttg" to 'L',
    "cuu" to 'L', "cuc" to 'L', "cua" to 'L', "cug" to 'L', "uua" to 'L', "uug" to 'L',
    "aaa" to 'K', "aag" to 'K',
    "atg" to 'M', "aug" to 'M',
    "ttt" to 'F', "ttc" to 'F', "uuu" to 'F', "uuc" to 'F',
    "cct" to 'P', "ccc" to 'P', "cca" to 'P', "ccg" to 'P', "ccu" to 'P',
    "tct" to 'S', "tcc" to 'S', "tca" to 'S', "tcg" to 'S', "agt" to 'S', "agc" to 'S',
    "ucu" to 'S', "ucc" to 'S', "uca" to 'S', "ucg" to 'S', "agu" to 'S',
    "act" to 'T', "acc" to 'T', "aca" to 'T', "acg" to 'T', "acu" to 'T',
    "tgg" to 'W', "ugg" to 'W',
    "tat" to 'Y', "tac" to 'Y', "uau" to 'Y', "uac" to 'Y',
    "gtt" to 'V', "gtc" to 'V', "gta" to 'V', "gtg" to 'V', "guu" to 'V', "guc" to 'V', "gua" to 'V', "gug" to 'V',
    "taa" to '*', "tag" to '*', "tga" to '*', "uaa" to '*', "uag" to '*', "uga" to '*',
    "..." to '.', "---" to '-'
)

/** IUPAC 3-mer 展开解析：所有展开密码子须存在且氨基酸一致，否则不入表（main expandAndResolve） */
private val IUPAC_EXPANDED: Map<String, Char> by lazy {
    val map = HashMap(INIT_CODON_AA)
    val codes = IUPAC.keys.toList()
    for (a in codes) for (b in codes) for (c in codes) {
        val codon = "$a$b$c".lowercase()
        if (codon in map) continue
        var found: Char? = null
        var ok = true
        for (x in IUPAC[a]!!) for (y in IUPAC[b]!!) for (z in IUPAC[c]!!) {
            val lc = "$x$y$z".lowercase()
            val aa = map[lc] ?: map[lc.replace("u", "t")] ?: map[lc.replace("t", "u")]
            if (aa != null) {
                if (found == null) found = aa
                else if (found != aa) { ok = false }
            } else { ok = false }
        }
        if (ok && found != null) map[codon] = found
    }
    map
}

fun getAminoAcidStringFromSequenceString(sequenceString: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i + 3 <= sequenceString.length) {
        sb.append(getAaOfTriplet(sequenceString.substring(i, i + 3)))
        i += 3
    }
    var s = sb.toString()
    if (s.isNotEmpty() && s.last() == '*') s = s.dropLast(1)
    return s
}

fun buildProteinView(
    dnaSeq: String,
    features: List<ProteinFeature> = emptyList(),
    useLowercase: Boolean = false
): ProteinViewData {
    val proteinSeq = if (useLowercase) {
        getAminoAcidStringFromSequenceString(dnaSeq).lowercase()
    } else {
        getAminoAcidStringFromSequenceString(dnaSeq)
    }

    // getAminoAcidDataForEachBaseOfDna(proteinSeq, true, undefined, true) 蛋白序列语义：
    // 每 aa 展开为 3 个碱基条目（positionInCodon 0/1/2，sequenceIndex = index+i）
    val aminoAcidData = mutableListOf<AaBaseData>()
    for ((aminoAcidIndex, aa) in proteinSeq.withIndex()) {
        for (i in 0 until 3) {
            aminoAcidData += AaBaseData(aa.toString(), i, aminoAcidIndex, aminoAcidIndex * 3 + i)
        }
    }

    return ProteinViewData(
        proteinSeq,
        dnaSeq,
        features,
        predictPTMs(proteinSeq),
        calculateProteinProperties(proteinSeq),
        calculateCodonFrequencies(dnaSeq),
        aminoAcidData
    )
}

/** main formatMw：<1000 Da 显示 1 位小数 Da，否则 kDa */
fun formatMw(mw: Double): String =
    if (mw < 1000) String.format(java.util.Locale.US, "%.1f Da", mw)
    else String.format(java.util.Locale.US, "%.1f kDa", mw / 1000)

// ──────────────────────────────── Lifecycle Trace（main biophysics/lifecycle.ts 1:1）────────────────────────────────

/** main LifecycleTraceItem：密码子 ↔ 氨基酸双向追溯坐标 */
data class LifecycleTraceItem(
    val codonIndex: Int,      // 0-indexed 氨基酸残基序号
    val codonSeq: String,     // 3bp DNA 密码子
    val aminoAcid: String,    // 单字母氨基酸
    val dnaStart: Int,        // 0-indexed DNA 起点
    val dnaEnd: Int           // 0-indexed DNA 终点（含端）
)

/**
 * main buildLifecyleTraceMap：DNA 密码子与翻译残基的双向追溯坐标映射。
 * 清洗规则 [^ATCGN]；逐密码子 getAminoAcidStringFromSequenceString（简并表）。
 */
fun buildLifecyleTraceMap(dnaSeq: String, cdsStartIdx: Int, cdsEndIdx: Int): List<LifecycleTraceItem> {
    val cleanDna = dnaSeq.uppercase().filter { it in "ATCGN" }
    // JS slice(cdsStartIdx, cdsEndIdx + 1)；负索引/越界由 substring 前置钳制处理
    val from = if (cdsStartIdx < 0) 0 else cdsStartIdx
    val to = if (cdsEndIdx + 1 > cleanDna.length) cleanDna.length else cdsEndIdx + 1
    val cdsSeq = if (from < to) cleanDna.substring(from, to) else ""
    val traceMap = mutableListOf<LifecycleTraceItem>()
    var i = 0
    while (i + 3 <= cdsSeq.length) {
        val codon = cdsSeq.substring(i, i + 3)
        val aa = getAminoAcidStringFromSequenceString(codon)
        traceMap += LifecycleTraceItem(i / 3, codon, aa, cdsStartIdx + i, cdsStartIdx + i + 2)
        i += 3
    }
    return traceMap
}

/** main applyBackMutationWithOptimization 结果 */
data class BackMutationResult(
    val updatedDna: String,
    val introducedCodon: String,
    val originalCodon: String
)

class LcMutPosOutOfScopeException : Exception(I18n.t("突变位置超出 DNA 序列长度范围"))  // lcMutPosOutOfScope

/**
 * main applyBackMutationWithOptimization：按宿主最优密码子反向回写点突变（保持 ORF）。
 * targetCodon 缺省回退 BEST_CODONS[host]["*"]（同 main || BEST_CODONS[host]["*"] quirk）。
 */
fun applyBackMutationWithOptimization(
    dnaSequence: String,
    cdsStartIdx: Int,
    aaPosition: Int,          // 0-indexed
    targetAa: String,
    host: CodeSign.Host
): BackMutationResult {
    val cleanDna = dnaSequence.uppercase()
    val targetCodon = CodeSign.bestCodon(host, targetAa.uppercase()[0])
        ?: CodeSign.bestCodon(host, '*')!!

    val codonStartIdx = cdsStartIdx + aaPosition * 3
    if (codonStartIdx + 2 >= cleanDna.length) {
        throw LcMutPosOutOfScopeException()
    }

    val originalCodon = cleanDna.substring(codonStartIdx, codonStartIdx + 3)
    val prefix = cleanDna.substring(0, codonStartIdx)
    val suffix = cleanDna.substring(codonStartIdx + 3)
    return BackMutationResult(prefix + targetCodon + suffix, targetCodon, originalCodon)
}
