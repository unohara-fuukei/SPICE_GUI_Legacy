package com.spice.nativeapp.core

/**
 * 自动注释（对齐 main src/lib/genome/advancedTools.ts:475-660 verbatim 移植）：
 * - COMMON_FEATURES_DB：内置精选特征库（14 条，序列/类型/配色与 main 逐字一致）
 * - annotateFeatures：exact（正向+反向互补子串）/ kmer（种子容错扩展 + 同名区间合并）两种匹配器
 * - inferFeatureType / FEATURE_COLORS：类型推断与类型配色
 * 坐标为 0-based 闭区间（与 main 相同）；入库前 +1 转 1-based。
 */
object AutoAnnotate {

    data class LibraryEntry(val name: String, val seq: String, val type: String? = null, val color: String? = null)

    data class AnnotatedFeature(
        val name: String, val type: String,
        val start: Int, val end: Int,          // 0-based 闭区间
        val forward: Boolean,
        val colorHex: String
    )

    data class Result(val features: List<AnnotatedFeature>, val count: Int)

    // ── main advancedTools.ts:475 COMMON_FEATURES_DB verbatim ──
    private val COMMON_FEATURES_DB = listOf(
        Lib("T7 promoter", "promoter", "TAATACGACTCACTATAGGG", "#ff5d5d"),
        Lib("SP6 promoter", "promoter", "ATTTAGGTGACACTATAG", "#ff5d5d"),
        Lib("lac promoter", "promoter", "AATTGTGAGCGGATAACAAT", "#ff9f1c"),
        Lib("T7 terminator", "terminator", "TGCTCGACGGAGTACTGCTAATACGACTCACTATAGGG", "#53d769"),
        Lib("pMB1 ori", "ori", "CTAAGAAACCGAAAA", "#4cd6ff"),
        Lib("AmpR", "cds", "ATGAGTATTCAACATTTCCGTGTCG", "#b48cff"),
        Lib("KanR", "cds", "ATGAGCCATATTCAACGGGAAAC", "#b48cff"),
        Lib("CmR", "cds", "ATGGAGAAAAAAATCACTGGAT", "#b48cff"),
        Lib("His-tag", "cds", "CATCACCATCACCATCACCAT", "#ffd23f"),
        Lib("FLAG-tag", "cds", "GACTACAAGGACGACGATGACAAG", "#ffd23f"),
        Lib("HA-tag", "cds", "TACCCATACGATGTTCCAGATTACGCT", "#ffd23f"),
        Lib("Myc-tag", "cds", "GAGCAGAAACTCATCTCTGAAGAGG", "#ffd23f"),
        Lib("GST-tag", "cds", "ATGTCCCCTATACTAGGTTATTGG", "#ffd23f"),
        Lib("MCS", "misc", "GAATTCGAGCTCCGTCGACAAGCTTCTGCAGGGTACCGGATCCTCTAGA", "#ff5df6")
    )
    private data class Lib(val name: String, val type: String, val seq: String, val color: String)

    /** 内置精选特征库（main getCommonFeatureLibrary） */
    fun commonFeatureLibrary(): List<LibraryEntry> =
        COMMON_FEATURES_DB.map { LibraryEntry(it.name, it.seq, it.type, it.color) }

    // ── main advancedTools.ts FEATURE_COLORS verbatim ──
    val FEATURE_COLORS = mapOf(
        "promoter" to "#ff5d5d", "terminator" to "#53d769", "ori" to "#4cd6ff",
        "cds" to "#b48cff", "tag" to "#ffd23f", "mrna" to "#53d7a9",
        "repeat" to "#ff9f1c", "misc" to "#5df6ff"
    )

    // ── main inferFeatureType verbatim（正则按出现顺序短路） ──
    fun inferFeatureType(name: String): String {
        val n = (name ?: "").lowercase()
        return when {
            Regex("promoter|enhancer|operator|rna pol|t7 rnap|sp6 promoter|cmv|sv40|35s promoter|nos promoter|u6 promoter|gata|hsp").containsMatchIn(n) -> "promoter"
            Regex("terminator|polya|poly-a|poly a|trna term|rrnb").containsMatchIn(n) -> "terminator"
            Regex("origin|replication|rep ?ori|\\bori\\b|resist origin|2 ?micr|col ?e ?1|pmb1|p15a|plasmid replication|ara ?cba|replicon|scaffold|attachment site|integration").containsMatchIn(n) -> "ori"
            Regex("tag|flag|his[- ]?tag|myc|ha[- ]?tag|v5|strep|s[- ]?tag|myc tag").containsMatchIn(n) -> "tag"
            Regex("mRNA|transcript|5' utr|3' utr|intron|exon").containsMatchIn(n) -> "mrna"
            Regex("resistance|kanr|ampr|tet|cat|gent|hyg|bleo|aph|aac|str.*r|chloramphen|antibiotic|bla|erm|van").containsMatchIn(n) -> "cds"
            Regex("linker|adapte|primer|cloning site|polylinker|\\bmcs\\b|multiple cloning|vector|backbone|cloning vector|spacer").containsMatchIn(n) -> "repeat"
            else -> "misc"
        }
    }

    private fun normalizeDna(s: String): String = s.uppercase().filter { it in 'A'..'Z' }

    /** main mergeIntervals verbatim：区间排序后按 gap 合并 */
    private fun mergeIntervals(ints: List<Pair<Int, Int>>, gap: Int): List<Pair<Int, Int>> {
        if (ints.isEmpty()) return emptyList()
        val sorted = ints.sortedBy { it.first }
        val out = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = out.removeAt(out.size - 1)
            val (s, e) = sorted[i]
            if (s <= last.second + gap) out.add(last.first to maxOf(last.second, e))
            else { out.add(last); out.add(sorted[i]) }
        }
        return out
    }

    /**
     * main annotateFeatures verbatim 移植。
     * @param algorithm "exact"（正向+revcomp 子串）或 "kmer"（默认：seed=22, minMatch=24, mergeGap=6，
     *        种子定位后容错（tol=k/10）逐碱基扩展，覆盖元件 ≥60% 即命中）
     */
    fun annotateFeatures(
        query: String,
        library: List<LibraryEntry>,
        algorithm: String = "kmer",
        seedLen0: Int = 22, minMatch0: Int = 24, mergeGap0: Int = 6
    ): Result {
        val q = normalizeDna(query)
        val seedLen = seedLen0
        val minMatch = minMatch0
        val mergeGap = mergeGap0

        class Rec(val type: String, val color: String) {
            val ints = mutableListOf<Pair<Int, Int>>()
        }
        val groups = LinkedHashMap<String, Rec>()
        fun add(name: String, type: String, color: String, forward: Boolean, start: Int, end: Int) {
            val key = "$name|${if (forward) "forward" else "reverse"}"
            groups.getOrPut(key) { Rec(type, color) }.ints.add(start to end)
        }
        fun findSeed(seed: String): List<Int> {
            val res = mutableListOf<Int>()
            var p = q.indexOf(seed)
            while (p != -1) { res.add(p); p = q.indexOf(seed, p + 1) }
            return res
        }

        for (e in library) {
            val s = normalizeDna(e.seq)
            if (s.length < 8) continue
            val type = e.type ?: inferFeatureType(e.name)
            val color = e.color ?: FEATURE_COLORS[type] ?: FEATURE_COLORS.getValue("misc")

            if (algorithm == "exact") {
                listOf(s to true, Bio.revComp(s) to false).forEach { (pat, fwd) ->
                    if (pat.length < 8) return@forEach
                    var p = q.indexOf(pat)
                    while (p != -1) { add(e.name, type, color, fwd, p, p + pat.length - 1); p = q.indexOf(pat, p + 1) }
                }
                continue
            }

            val k = minOf(seedLen, s.length)
            val tol = maxOf(1, k / 10)
            fun orient(pat: String, fwd: Boolean) {
                val seed = pat.take(k)
                for (p in findSeed(seed)) {
                    var end = p
                    var mism = 0
                    for (j in 0 until pat.length) {
                        val qc = q.getOrNull(p + j) ?: break
                        if (qc == pat[j]) end = p + j
                        else { mism++; if (mism > tol) break; end = p + j }
                    }
                    val span = end - p + 1
                    if (span >= minMatch && span >= pat.length * 0.6) add(e.name, type, color, fwd, p, end)
                }
            }
            orient(s, true)
            orient(Bio.revComp(s), false)
        }

        val features = mutableListOf<AnnotatedFeature>()
        for ((key, rec) in groups) {
            val sep = key.lastIndexOf('|')
            val name = key.substring(0, sep)
            val forward = key.substring(sep + 1) == "forward"
            for ((st, en) in mergeIntervals(rec.ints, mergeGap)) {
                features.add(AnnotatedFeature(name, rec.type, st, en, forward, rec.color))
            }
        }
        return Result(features, features.size)
    }

    /**
     * main runAutoAnnotate（+page.svelte:2627）等价入口：
     * 内置库扫描 → 跳过已存在（name|start|end 相同）→ 返回新特征（1-based, Feature 形态）。
     */
    fun runOnRecord(record: SeqRecord, algorithm: String = "kmer"): List<Feature> {
        val result = annotateFeatures(record.sequence, commonFeatureLibrary(), algorithm)
        if (result.count == 0) return emptyList()
        val existing = record.features.map { "${it.name}|${it.start}|${it.end}" }.toSet()
        return result.features
            .filter { "${it.name}|${it.start + 1}|${it.end + 1}" !in existing }
            .map { f ->
                Feature(
                    name = f.name,
                    type = f.type,
                    start = f.start + 1,
                    end = f.end + 1,
                    strand = if (f.forward) 1 else -1,
                    color = f.colorHex
                )
            }
    }
}
