package com.spice.nativeapp.core

/**
 * ReverseTranslate.kt —— main src/lib/genome/advancedTools.ts reverseTranslate() 1:1 移植
 * （基因页 ReverseTranslationPanel 的算法层）。
 *
 * - SYNONYMOUS_BY_AA / REVERSE_CODON_AA：同义密码子表与反向映射，逐值照抄。
 * - DEFAULT_CODON_TABLE：每 AA 首选密码子（legacy/Kazusa 兼容形状），逐值照抄。
 * - chooseCodon：首选密码子有效且 !optimizeGc 时直接用首选（main 同款分支顺序，
 *   宿主权重仅在首选无效或 optimizeGc 时参与排序）。
 * - 校验链：mismatches / invalidCodons / warnings 与 main 一致。
 * - 警告文案用 main zh 原文（键=zh 原文，走 I18n.t/tf）。
 */
object ReverseTranslate {

    /** main SYNONYMOUS_BY_AA */
    val SYNONYMOUS_BY_AA: Map<String, List<String>> = mapOf(
        "F" to listOf("TTT", "TTC"), "L" to listOf("TTA", "TTG", "CTT", "CTC", "CTA", "CTG"),
        "I" to listOf("ATT", "ATC", "ATA"), "M" to listOf("ATG"),
        "V" to listOf("GTT", "GTC", "GTA", "GTG"), "S" to listOf("TCT", "TCC", "TCA", "TCG", "AGT", "AGC"),
        "P" to listOf("CCT", "CCC", "CCA", "CCG"), "T" to listOf("ACT", "ACC", "ACA", "ACG"),
        "A" to listOf("GCT", "GCC", "GCA", "GCG"), "Y" to listOf("TAT", "TAC"), "H" to listOf("CAT", "CAC"),
        "Q" to listOf("CAA", "CAG"), "N" to listOf("AAT", "AAC"), "K" to listOf("AAA", "AAG"),
        "D" to listOf("GAT", "GAC"), "E" to listOf("GAA", "GAG"), "C" to listOf("TGT", "TGC"),
        "R" to listOf("CGT", "CGC", "CGA", "CGG", "AGA", "AGG"), "G" to listOf("GGT", "GGC", "GGA", "GGG"),
        "W" to listOf("TGG"), "*" to listOf("TAA", "TAG", "TGA"),
    )

    /** main REVERSE_CODON_AA：codon → aa 反向映射 */
    val REVERSE_CODON_AA: Map<String, String> =
        SYNONYMOUS_BY_AA.flatMap { (aa, codons) -> codons.map { it to aa } }.toMap()

    /** main DEFAULT_CODON_TABLE */
    val DEFAULT_CODON_TABLE: Map<String, String> = mapOf(
        "M" to "ATG", "W" to "TGG", "F" to "TTC", "L" to "CTG", "I" to "ATC", "V" to "GTG",
        "S" to "AGC", "P" to "CCG", "T" to "ACC", "A" to "GCG", "Y" to "TAC", "H" to "CAC",
        "Q" to "CAG", "N" to "AAC", "K" to "AAG", "D" to "GAC", "E" to "GAA", "C" to "TGC",
        "R" to "CGT", "G" to "GGC", "*" to "TAA",
    )

    data class CodonTrace(
        val index: Int, val aminoAcid: String, val codon: String,
        val dnaStart: Int, val dnaEnd: Int, val gcPercent: Double,
    )

    data class Mismatch(val index: Int, val requested: String, val translated: String)
    data class InvalidCodon(val index: Int, val codon: String)

    data class Validation(
        val valid: Boolean,
        val translatedProtein: String,
        val requestedProtein: String,
        val mismatches: List<Mismatch>,
        val invalidCodons: List<InvalidCodon>,
    )

    data class Options(
        val host: String? = null,
        val codonUsage: Map<String, Any>? = null, // codonUsage 未在面板路径使用；面板只用 host + includeStop + output
        val codonTable: Map<String, String>? = null,
        val optimizeGc: Boolean = false,
        val targetGc: Double? = null,
        val includeStop: Boolean = false,
        val startCodon: String? = null,
        val stopCodon: String? = null,
        val avoidRestrictionSites: List<String> = emptyList(),
    )

    data class Result(
        val dnaSequence: String,
        val mrnaSequence: String,
        val cdnaSequence: String,
        val proteinSequence: String,
        val codonMap: Map<String, String>,
        val codonTrace: List<CodonTrace>,
        val validation: Validation,
        val warnings: List<String>,
    )

    /** main calculatePercentGC（sequence.ts）：[cg]/length*100，空串 0。 */
    private fun calculatePercentGC(bps: String): Double =
        if (bps.isEmpty()) 0.0 else bps.count { it == 'c' || it == 'C' || it == 'g' || it == 'G' } * 100.0 / bps.length

    /** main reverseTranslate 1:1。 */
    fun reverseTranslate(proteinSeq: String, options: Options = Options()): Result {
        val warnings = mutableListOf<String>()
        val requested = proteinSeq.uppercase().filter { it in 'A'..'Z' || it == '*' }
        if (requested.length != proteinSeq.replace(Regex("\\s"), "").length) {
            warnings.add(I18n.t("蛋白质序列包含无法识别的字符，已忽略"))
        }
        val usage: Map<String, Map<String, Double>>? = if (options.host != null)
            CodonOpt.CODON_WEIGHTS[options.host] else null
        val table: Map<String, String> = DEFAULT_CODON_TABLE + (options.codonTable ?: emptyMap())
        val trace = mutableListOf<CodonTrace>()
        val dna = StringBuilder()

        fun chooseCodon(aa: String): String {
            val candidates: List<String> = run {
                // 面板路径 codonUsage 为空，usage 只来自 CODON_WEIGHTS（数值权重）。
                usage?.get(aa)?.let { SYNONYMOUS_BY_AA[aa] } ?: SYNONYMOUS_BY_AA[aa] ?: emptyList()
            }
            val preferred = table[aa]?.uppercase()?.replace("U", "T")
            val valid = candidates.filter { REVERSE_CODON_AA[it.uppercase()] == aa }
            if (preferred != null && REVERSE_CODON_AA[preferred] == aa && !options.optimizeGc) return preferred
            val pool = if (valid.isNotEmpty()) valid
            else if (preferred != null && REVERSE_CODON_AA[preferred] == aa) listOf(preferred) else emptyList()
            if (pool.isEmpty()) {
                return if (preferred != null && Regex("^[ATCG]{3}$").matches(preferred)) preferred else "NNN"
            }
            val weights: Map<String, Double>? = usage?.get(aa)
            val sorted = when {
                weights != null -> pool.sortedByDescending { weights[it] ?: 0.0 }
                options.optimizeGc -> {
                    val target = options.targetGc ?: 50.0
                    pool.sortedBy { c -> kotlin.math.abs(c.count { it == 'G' || it == 'C' } * 100.0 / 3 - target) }
                }
                else -> pool.sorted() // main: a.localeCompare(b)（ASCII 字母序等价）
            }
            return sorted.first()
        }

        val start = options.startCodon?.uppercase()?.replace("U", "T")
        for (i in requested.indices) {
            val aa = requested[i].toString()
            var codon = chooseCodon(aa)
            if (i == 0 && start != null && aa == "M") codon = start
            if (REVERSE_CODON_AA[codon] != aa) {
                warnings.add(I18n.tf("无法为 {v1} 选择有效密码子，使用 {v2}", aa, codon))
            }
            val dnaStart = dna.length
            dna.append(codon)
            trace.add(
                CodonTrace(
                    index = i, aminoAcid = aa, codon = codon,
                    dnaStart = dnaStart, dnaEnd = dna.length,
                    gcPercent = kotlin.math.round(calculatePercentGC(codon) * 10) / 10,
                )
            )
        }
        if (options.includeStop || options.stopCodon != null) {
            val stop = (options.stopCodon ?: "TAA").uppercase().replace("U", "T")
            if (REVERSE_CODON_AA[stop] != "*") {
                warnings.add(I18n.tf("终止密码子无效: {v1}", stop))
            } else dna.append(stop)
        }
        for (rawSite in options.avoidRestrictionSites) {
            val site = rawSite.uppercase().replace("U", "T").filter { it in "ATCG" }
            if (site.isNotEmpty() && dna.contains(site)) {
                warnings.add(I18n.tf("产物包含应避免的限制酶位点: {v1}", site))
            }
        }
        val codonCount = dna.length / 3
        val translated = (0 until codonCount).map { REVERSE_CODON_AA[dna.substring(it * 3, it * 3 + 3)] ?: "X" }.joinToString("")
        val mismatches = requested.mapIndexed { i, aa ->
            Mismatch(i, aa.toString(), translated.getOrNull(i)?.toString() ?: "X")
        }.filter { it.requested != it.translated }
        val invalidCodons = (0 until codonCount).map { InvalidCodon(it, dna.substring(it * 3, it * 3 + 3)) }
            .filter { !REVERSE_CODON_AA.containsKey(it.codon) }
        val validation = Validation(
            valid = mismatches.isEmpty() && invalidCodons.isEmpty(),
            translatedProtein = translated, requestedProtein = requested,
            mismatches = mismatches, invalidCodons = invalidCodons,
        )
        if (!validation.valid) warnings.add(I18n.t("反向翻译校验失败：生成序列翻译结果与目标蛋白不一致"))
        val mrnaSequence = dna.toString().replace("T", "U")
        return Result(
            dnaSequence = dna.toString(), mrnaSequence = mrnaSequence, cdnaSequence = dna.toString(),
            proteinSequence = requested, codonMap = table, codonTrace = trace,
            validation = validation, warnings = warnings,
        )
    }
}
