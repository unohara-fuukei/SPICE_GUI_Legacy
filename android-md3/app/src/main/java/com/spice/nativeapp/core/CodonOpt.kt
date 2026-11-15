package com.spice.nativeapp.core

/**
 * 密码子优化助手 —— main src/lib/genome/codon.ts 1:1 移植（T7-15 CodonOptimizer 块）。
 *
 * main 结构：
 *  - AA_OF_CODON：标准遗传密码表（64 密码子 → 单字母 AA，终止子 '*'）
 *  - CODON_WEIGHTS：ecoli/yeast/human 三宿主相对适应性权重（0..1）
 *  - BEST_CODONS：每宿主每 AA 最优密码子（'*' 键为终止子）
 *  - calculateCai(dna, host)：geometric mean of w over codons（w==1 计 optimizedCount），
 *    未知密码子跳过；Math.max(w, 0.0001) 下限防 log(-)
 *  - optimizeCodons(input, host, isDna)：isDna 先翻译；逐 AA 取 BEST_CODONS，
 *    无映射 → 'NNN'；changed 统计 isDna 时 AA_OF_CODON[best] !== aa 的位点
 *
 * 注：安卓侧另有 CodeSign.computeCai（相对频率归一算法）为 codesign 协同设计
 * 面板所用，与本模块（main codon.ts）算法不同，不可混用。
 */
object CodonOpt {

    /** main AA_OF_CODON（标准密码子表；终止子 TAA/TAG/TGA → '*'） */
    val AA_OF_CODON: Map<String, String> = mapOf(
        "TTT" to "F", "TTC" to "F", "TTA" to "L", "TTG" to "L",
        "CTT" to "L", "CTC" to "L", "CTA" to "L", "CTG" to "L",
        "ATT" to "I", "ATC" to "I", "ATA" to "I",
        "GTT" to "V", "GTC" to "V", "GTA" to "V", "GTG" to "V",
        "TCT" to "S", "TCC" to "S", "TCA" to "S", "TCG" to "S",
        "CCT" to "P", "CCC" to "P", "CCA" to "P", "CCG" to "P",
        "ACT" to "T", "ACC" to "T", "ACA" to "T", "ACG" to "T",
        "GCT" to "A", "GCC" to "A", "GCA" to "A", "GCG" to "A",
        "TAT" to "Y", "TAC" to "Y",
        "CAT" to "H", "CAC" to "H",
        "CAA" to "Q", "CAG" to "Q",
        "AAT" to "N", "AAC" to "N",
        "AAA" to "K", "AAG" to "K",
        "GAT" to "D", "GAC" to "D",
        "GAA" to "E", "GAG" to "E",
        "TGT" to "C", "TGC" to "C",
        "CGT" to "R", "CGC" to "R", "CGA" to "R", "CGG" to "R", "AGA" to "R", "AGG" to "R",
        "AGT" to "S", "AGC" to "S",
        "GGT" to "G", "GGC" to "G", "GGA" to "G", "GGG" to "G",
        "ATG" to "M",
        "TGG" to "W",
        "TAA" to "*", "TAG" to "*", "TGA" to "*"
    )

    /** main CODON_WEIGHTS：三宿主相对适应性权重（0..1）——逐值照抄 */
    val CODON_WEIGHTS: Map<String, Map<String, Map<String, Double>>> = mapOf(
        "ecoli" to mapOf(
            "F" to mapOf("TTT" to 0.3, "TTC" to 1.0),
            "L" to mapOf("TTA" to 0.1, "TTG" to 0.1, "CTT" to 0.1, "CTC" to 0.1, "CTA" to 0.05, "CTG" to 1.0),
            "I" to mapOf("ATT" to 0.5, "ATC" to 1.0, "ATA" to 0.05),
            "V" to mapOf("GTT" to 0.4, "GTC" to 0.2, "GTA" to 0.15, "GTG" to 1.0),
            "S" to mapOf("TCT" to 0.3, "TCC" to 0.4, "TCA" to 0.1, "TCG" to 0.1, "AGT" to 0.15, "AGC" to 1.0),
            "P" to mapOf("CCT" to 0.15, "CCC" to 0.1, "CCA" to 0.2, "CCG" to 1.0),
            "T" to mapOf("ACT" to 0.35, "ACC" to 1.0, "ACA" to 0.1, "ACG" to 0.15),
            "A" to mapOf("GCT" to 0.3, "GCC" to 0.2, "GCA" to 0.3, "GCG" to 1.0),
            "Y" to mapOf("TAT" to 0.3, "TAC" to 1.0),
            "H" to mapOf("CAT" to 0.3, "CAC" to 1.0),
            "Q" to mapOf("CAA" to 0.3, "CAG" to 1.0),
            "N" to mapOf("AAT" to 0.3, "AAC" to 1.0),
            "K" to mapOf("AAA" to 0.25, "AAG" to 1.0),
            "D" to mapOf("GAT" to 0.4, "GAC" to 1.0),
            "E" to mapOf("GAA" to 1.0, "GAG" to 0.3),
            "C" to mapOf("TGT" to 0.4, "TGC" to 1.0),
            "R" to mapOf("CGT" to 0.4, "CGC" to 0.4, "CGA" to 0.05, "CGG" to 0.05, "AGA" to 0.1, "AGG" to 0.1),
            "G" to mapOf("GGT" to 0.4, "GGC" to 1.0, "GGA" to 0.1, "GGG" to 0.15),
            "M" to mapOf("ATG" to 1.0),
            "W" to mapOf("TGG" to 1.0),
            "*" to mapOf("TAA" to 1.0, "TAG" to 0.1, "TGA" to 0.1)
        ),
        "yeast" to mapOf(
            "F" to mapOf("TTT" to 1.0, "TTC" to 0.6),
            "L" to mapOf("TTA" to 1.0, "TTG" to 0.8, "CTT" to 0.1, "CTC" to 0.1, "CTA" to 0.2, "CTG" to 0.1),
            "I" to mapOf("ATT" to 1.0, "ATC" to 0.4, "ATA" to 0.15),
            "V" to mapOf("GTT" to 1.0, "GTC" to 0.4, "GTA" to 0.3, "GTG" to 0.2),
            "S" to mapOf("TCT" to 1.0, "TCC" to 0.5, "TCA" to 0.4, "TCG" to 0.1, "AGT" to 0.3, "AGC" to 0.2),
            "P" to mapOf("CCT" to 0.4, "CCC" to 0.15, "CCA" to 1.0, "CCG" to 0.05),
            "T" to mapOf("ACT" to 1.0, "ACC" to 0.4, "ACA" to 0.3, "ACG" to 0.1),
            "A" to mapOf("GCT" to 1.0, "GCC" to 0.4, "GCA" to 0.5, "GCG" to 0.1),
            "Y" to mapOf("TAT" to 1.0, "TAC" to 0.4),
            "H" to mapOf("CAT" to 1.0, "CAC" to 0.4),
            "Q" to mapOf("CAA" to 1.0, "CAG" to 0.3),
            "N" to mapOf("AAT" to 1.0, "AAC" to 0.5),
            "K" to mapOf("AAA" to 1.0, "AAG" to 0.4),
            "D" to mapOf("GAT" to 1.0, "GAC" to 0.4),
            "E" to mapOf("GAA" to 1.0, "GAG" to 0.3),
            "C" to mapOf("TGT" to 1.0, "TGC" to 0.2),
            "R" to mapOf("CGT" to 0.2, "CGC" to 0.1, "CGA" to 0.1, "CGG" to 0.05, "AGA" to 1.0, "AGG" to 0.2),
            "G" to mapOf("GGT" to 1.0, "GGC" to 0.2, "GGA" to 0.3, "GGG" to 0.1),
            "M" to mapOf("ATG" to 1.0),
            "W" to mapOf("TGG" to 1.0),
            "*" to mapOf("TAA" to 1.0, "TAG" to 0.1, "TGA" to 0.1)
        ),
        "human" to mapOf(
            "F" to mapOf("TTT" to 0.4, "TTC" to 1.0),
            "L" to mapOf("TTA" to 0.1, "TTG" to 0.25, "CTT" to 0.2, "CTC" to 0.3, "CTA" to 0.1, "CTG" to 1.0),
            "I" to mapOf("ATT" to 0.45, "ATC" to 1.0, "ATA" to 0.15),
            "V" to mapOf("GTT" to 0.3, "GTC" to 0.4, "GTA" to 0.15, "GTG" to 1.0),
            "S" to mapOf("TCT" to 0.2, "TCC" to 0.4, "TCA" to 0.2, "TCG" to 0.1, "AGT" to 0.25, "AGC" to 1.0),
            "P" to mapOf("CCT" to 0.3, "CCC" to 0.4, "CCA" to 0.3, "CCG" to 1.0),
            "T" to mapOf("ACT" to 0.25, "ACC" to 1.0, "ACA" to 0.3, "ACG" to 0.15),
            "A" to mapOf("GCT" to 0.4, "GCC" to 1.0, "GCA" to 0.3, "GCG" to 0.15),
            "Y" to mapOf("TAT" to 0.4, "TAC" to 1.0),
            "H" to mapOf("CAT" to 0.4, "CAC" to 1.0),
            "Q" to mapOf("CAA" to 0.35, "CAG" to 1.0),
            "N" to mapOf("AAT" to 0.4, "AAC" to 1.0),
            "K" to mapOf("AAA" to 0.4, "AAG" to 1.0),
            "D" to mapOf("GAT" to 0.45, "GAC" to 1.0),
            "E" to mapOf("GAA" to 0.45, "GAG" to 1.0),
            "C" to mapOf("TGT" to 0.4, "TGC" to 1.0),
            "R" to mapOf("CGT" to 0.1, "CGC" to 0.35, "CGA" to 0.1, "CGG" to 0.2, "AGA" to 1.0, "AGG" to 0.9),
            "G" to mapOf("GGT" to 0.3, "GGC" to 1.0, "GGA" to 0.4, "GGG" to 0.3),
            "M" to mapOf("ATG" to 1.0),
            "W" to mapOf("TGG" to 1.0),
            "*" to mapOf("TAA" to 0.3, "TAG" to 0.2, "TGA" to 1.0)
        )
    )

    /** main BEST_CODONS：每宿主每 AA 最优密码子（逐值照抄；'*' 为终止子） */
    val BEST_CODONS: Map<String, Map<String, String>> = mapOf(
        "ecoli" to mapOf(
            "M" to "ATG", "W" to "TGG", "F" to "TTC", "L" to "CTG", "I" to "ATC", "V" to "GTG",
            "S" to "AGC", "P" to "CCG", "T" to "ACC", "A" to "GCG", "Y" to "TAC", "H" to "CAC",
            "Q" to "CAG", "N" to "AAC", "K" to "AAG", "D" to "GAC", "E" to "GAA", "C" to "TGC",
            "R" to "CGT", "G" to "GGC", "*" to "TAA"
        ),
        "yeast" to mapOf(
            "M" to "ATG", "W" to "TGG", "F" to "TTT", "L" to "TTA", "I" to "ATT", "V" to "GTT",
            "S" to "TCT", "P" to "CCA", "T" to "ACT", "A" to "GCT", "Y" to "TAT", "H" to "CAT",
            "Q" to "CAA", "N" to "AAT", "K" to "AAA", "D" to "GAT", "E" to "GAA", "C" to "TGT",
            "R" to "AGA", "G" to "GGT", "*" to "TAA"
        ),
        "human" to mapOf(
            "M" to "ATG", "W" to "TGG", "F" to "TTC", "L" to "CTG", "I" to "ATC", "V" to "GTG",
            "S" to "AGC", "P" to "CCG", "T" to "ACC", "A" to "GCC", "Y" to "TAC", "H" to "CAC",
            "Q" to "CAG", "N" to "AAC", "K" to "AAG", "D" to "GAC", "E" to "GAG", "C" to "TGC",
            "R" to "AGA", "G" to "GGC", "*" to "TGA"
        )
    )

    fun getAminoAcidOfCodon(codon: String): String? = AA_OF_CODON[codon.uppercase()]

    // ── main src/lib/genome/sequence.ts getAminoAcidStringFromSequenceString 1:1 ──
    // 链路：getAminoAcidDataForEachBaseOfDna（getNextTriplet 尾部不足3bp 无 fullCodon；
    // getAA 走 threeLetterMap + IUPAC 简并展开 + RNA-U 兼容 + xxx→X）→ 剔除
    // 尾 3bp 终止子（doNotExcludeAsterisk=false）。注意 aa.ts getAA 的 fallback 简并表
    // 覆盖了 IUPAC 展开的全部结果（n/y/r 等两两一致才映射，歧义 → X），这里用同一语义的
    // 局部表实现：先标准 64 表（T/U 等价），再 main fallback 简并表，再 IUPAC 展开歧义判 X。

    /** main aa.ts getAA：标准表 → fallback 简并表 → xxx→X；RNA U 与 T 等价 */
    private fun getAaInfo(triplet: String): String? {
        val seq = triplet.lowercase()
        // threeLetterMap：gcu/aau 等含 U 键 + 标准 T 键（展开时 u↔t 互查）
        STANDARD_WITH_U[seq]?.let { return it }
        val asT = seq.replace("u", "t")
        STANDARD_WITH_U[asT]?.let { return it }
        val asU = seq.replace("t", "u")
        STANDARD_WITH_U[asU]?.let { return it }
        // main getAA fallback 简并表（"xxx" → proteinAlphabetView.X，value="X" 大写）
        if (seq == "xxx") return "X"
        FALLBACK_DEGENERATE[seq]?.let { return it }
        val xToN = seq.replace("x", "n")
        FALLBACK_DEGENERATE[xToN]?.let { return it }
        // IUPAC 展开歧义解析（expandAndResolve：全部展开结果一致才映射，否则 null）
        // main 尾行 proteinAlphabetView[letter.toUpperCase()] —— x → "X"
        expandIupac(seq)?.let { return it }
        return "X"
    }

    private val STANDARD_WITH_U: Map<String, String> = buildMap {
        for ((codon, aa) in AA_OF_CODON) {
            put(codon.lowercase(), aa)
            put(codon.lowercase().replace('t', 'u'), aa)
        }
    }

    /** main getAA 内联 fallback 简并表（逐键照抄） */
    private val FALLBACK_DEGENERATE: Map<String, String> = mapOf(
        "atn" to "I", "ctn" to "L", "gtn" to "V", "ttn" to "F",
        "aan" to "N", "agn" to "S", "gan" to "D", "ggn" to "G",
        "can" to "H", "cgn" to "R", "gcn" to "A",
        "aay" to "N", "gay" to "D", "gar" to "E",
        "tgy" to "C", "tay" to "Y", "tgr" to "*",
        "xxx" to "X"
    )

    /** main IUPAC 碱基集（X 视作 N 全集） */
    private val IUPAC: Map<Char, List<Char>> = mapOf(
        'A' to listOf('A'), 'C' to listOf('C'), 'G' to listOf('G'), 'T' to listOf('T'), 'U' to listOf('U'),
        'R' to listOf('A', 'G'), 'Y' to listOf('C', 'T', 'U'), 'K' to listOf('G', 'T', 'U'),
        'M' to listOf('A', 'C'), 'S' to listOf('G', 'C'), 'W' to listOf('A', 'T', 'U'),
        'B' to listOf('C', 'G', 'T', 'U'), 'D' to listOf('A', 'G', 'T', 'U'),
        'H' to listOf('A', 'C', 'T', 'U'), 'V' to listOf('A', 'C', 'G'),
        'N' to listOf('A', 'C', 'G', 'T', 'U'), 'X' to listOf('A', 'C', 'G', 'T', 'U')
    )

    /** main expandAndResolve：IUPAC 三联展开，全部展开子密码子须映射同一 AA，否则 null */
    private fun expandIupac(codon: String): String? {
        if (codon.length != 3) return null
        var all = listOf("")
        for (c in codon.uppercase()) {
            val set = IUPAC[c] ?: return null
            val next = ArrayList<String>()
            for (prefix in all) for (b in set) next.add(prefix + b)
            all = next
        }
        var found: String? = null
        for (c in all) {
            val lower = c.lowercase()
            val aa = STANDARD_WITH_U[lower]
                ?: STANDARD_WITH_U[lower.replace("u", "t")]
                ?: STANDARD_WITH_U[lower.replace("t", "u")]
                ?: return null
            if (found == null) found = aa
            else if (found != aa) return null
        }
        return found
    }

    /**
     * main getAminoAcidStringFromSequenceString（forward，doNotExcludeAsterisk=false）1:1：
     * 3bp 步进、尾不足 3bp 排除（fullCodon）、氨基酸索引即 aaArray 槽位、
     * 尾 3bp 若为终止子则剔除（内部终止子保留为 '*'）。
     * getAA 对未识别字符返回 'x'（proteinAlphabetView.x，value 小写 x——原样保留）。
     */
    fun translatedProtein(sequenceString: String): String {
        val n = sequenceString.length
        val sb = StringBuilder()
        var index = 0
        while (index + 3 <= n) {
            val codon = sequenceString.substring(index, index + 3)
            // fullCodon：getNextTriplet 3 碱基全在序列内（exonRange 全域）→ 此处恒 true
            val aa = getAaInfo(codon) ?: "x"   // getAA("xxx") → X；其它未知 → 'x'
            // 尾 3bp 终止子剔除：index >= n-3 && aa == "*"
            if (index >= n - 3 && aa == "*") {
                index += 3
                continue
            }
            sb.append(aa)
            index += 3
        }
        return sb.toString()
    }

    data class CaiResult(
        val cai: Double,
        val gc: Double,
        val optimizedCount: Int,
        val totalCodons: Int
    )

    /**
     * main calculateCai：CAI = exp(Σ ln w / n)，w 为宿主权重（下限 0.0001）。
     * 未知密码子 / 无权重密码子跳过（不计入 n）；GC% 以 clean 全长为分母。
     */
    fun calculateCai(dnaSequence: String, host: String): CaiResult {
        val weights = CODON_WEIGHTS[host] ?: CODON_WEIGHTS.getValue("ecoli")
        val clean = dnaSequence.uppercase().filter { it in "ATCG" }
        var geometricSum = 0.0
        var totalCodons = 0
        var optimizedCount = 0
        var gcCount = 0
        var i = 0
        while (i + 3 <= clean.length) {
            val codon = clean.substring(i, i + 3)
            val aa = AA_OF_CODON[codon]
            if (aa != null) {
                val w = weights[aa]?.get(codon)
                if (w != null) {
                    geometricSum += kotlin.math.ln(maxOf(w, 0.0001))
                    totalCodons++
                    if (w == 1.0) optimizedCount++
                }
            }
            i += 3
        }
        for (c in clean) if (c == 'G' || c == 'C') gcCount++
        val cai = if (totalCodons > 0) kotlin.math.exp(geometricSum / totalCodons) else 0.0
        val gc = if (clean.isNotEmpty()) gcCount * 100.0 / clean.length else 0.0
        return CaiResult(cai, gc, optimizedCount, totalCodons)
    }

    data class OptimizeResult(
        val optimized: String,
        val changed: Int
    )

    /**
     * main optimizeCodons：input 为蛋白（isDna=false）或 DNA（isDna=true，先逐密码子翻译，
     * 未知密码子 → 'X'）；逐 AA 取 BEST_CODONS[host][aa]，无映射 → 'NNN'。
     * changed 仅 isDna 时统计（AA_OF_CODON[best] !== aa 即氨基酸改变——用最优密码子回译必然同义，
     * 实际为 0，1:1 保留 main 语义）。
     */
    fun optimizeCodons(input: String, host: String, isDna: Boolean = false): OptimizeResult {
        val clean = input.uppercase().filter { it in "ATCG" }
        val aaString: String = if (isDna) {
            buildString {
                var i = 0
                while (i + 3 <= clean.length) {
                    append(AA_OF_CODON[clean.substring(i, i + 3)] ?: "X")
                    i += 3
                }
            }
        } else {
            clean
        }
        val best = BEST_CODONS[host] ?: BEST_CODONS.getValue("ecoli")
        val optimized = StringBuilder()
        var changed = 0
        for (aa in aaString) {
            val b = best[aa.toString()]
            if (b != null) {
                optimized.append(b)
                if (isDna && AA_OF_CODON[b] != aa.toString()) changed++
            } else {
                optimized.append("NNN")
            }
        }
        return OptimizeResult(optimized.toString(), changed)
    }
}
