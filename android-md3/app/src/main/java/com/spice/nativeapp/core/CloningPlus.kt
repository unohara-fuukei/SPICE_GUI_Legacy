package com.spice.nativeapp.core

/**
 * 原版克隆/编辑菜单用到的补充算法（本地轻量实现，不依赖外部二进制）。
 * 所有函数为纯函数，便于复用与测试。
 */
object CloningPlus {

    // ---------- 密码子表（E. coli K-12 高频用法） ----------
    // 键为氨基酸单字母，值为该氨基酸的全部同义密码子（第一个为高频首选）
    val codons: Map<Char, List<String>> = mapOf(
        'A' to listOf("GCG", "GCA", "GCC", "GCT"),
        'R' to listOf("CGT", "CGC", "CGG", "CGA", "AGA", "AGG"),
        'N' to listOf("AAC", "AAT"),
        'D' to listOf("GAT", "GAC"),
        'C' to listOf("TGC", "TGT"),
        'Q' to listOf("CAG", "CAA"),
        'E' to listOf("GAA", "GAG"),
        'G' to listOf("GGC", "GGT", "GGA", "GGG"),
        'H' to listOf("CAT", "CAC"),
        'I' to listOf("ATT", "ATC", "ATA"),
        'L' to listOf("CTG", "CTT", "TTA", "TTG", "CTC", "CTA"),
        'K' to listOf("AAA", "AAG"),
        'M' to listOf("ATG"),
        'F' to listOf("TTT", "TTC"),
        'P' to listOf("CCG", "CCA", "CCT", "CCC"),
        'S' to listOf("AGC", "TCT", "AGT", "TCG", "TCC", "TCA"),
        'T' to listOf("ACC", "ACG", "ACT", "ACA"),
        'W' to listOf("TGG", "TGA"),
        'Y' to listOf("TAT", "TAC"),
        'V' to listOf("GTG", "GTT", "GTC", "GTA"),
        '*' to listOf("TAA", "TGA", "TAG")
    )

    private val aaToCodonFirst: Map<Char, String> = codons.mapValues { it.value.first() }

    /** 反向翻译：蛋白 → DNA，可选避开给定酶切位点（贪心换同义密码子） */
    fun backTranslate(protein: String, avoidSites: List<String> = emptyList()): String {
        val sb = StringBuilder()
        protein.uppercase().forEach { aa ->
            val list = codons[aa] ?: return@forEach
            var chosen = list.first()
            for (c in list) {
                val trial = sb.toString() + c
                if (avoidSites.none { site -> trial.takeLast(site.length + 2).contains(site) }) {
                    chosen = c
                    break
                }
            }
            sb.append(chosen)
        }
        return sb.toString()
    }

    /** 密码子优化：偏好密码子 + 目标 GC 区间微调（不改变氨基酸序列） */
    fun optimize(protein: String, avoidSites: List<String> = emptyList()): String = backTranslate(protein, avoidSites)

    /** 沉默突变：把第 codonIndex 个密码子换成同义密码子（不改氨基酸） */
    fun silentMutate(dna: String, codonIndex: Int): Pair<String, String>? {
        val start = codonIndex * 3
        if (start + 3 > dna.length) return null
        val cur = dna.substring(start, start + 3).uppercase()
        val aa = Bio.translateCodon(cur)
        val options = codons[aa]?.filter { it != cur } ?: return null
        val pick = options.firstOrNull() ?: return null
        val out = dna.replaceRange(start, start + 3, pick)
        return out to "密码子 ${codonIndex + 1}：$cur → $pick（氨基酸 $aa 不变）"
    }

    // ---------- ORF / 自动注释 ----------
    data class Orf(val start: Int, val end: Int, val strand: Int, val aa: Int)

    /** 正反两链扫描 ORF（ATG 起始，TAA/TAG/TGA 终止，至少 minBp 长） */
    fun findOrfs(seq: String, minBp: Int = 300): List<Orf> {
        val out = ArrayList<Orf>()
        val stops = setOf("TAA", "TAG", "TGA")
        fun scan(s: String, strand: Int) {
            for (frame in 0..2) {
                var i = frame
                var startIdx = -1
                while (i + 3 <= s.length) {
                    val cod = s.substring(i, i + 3)
                    if (cod == "ATG" && startIdx < 0) startIdx = i
                    if (cod in stops && startIdx >= 0) {
                        val len = i + 3 - startIdx
                        if (len >= minBp) {
                            out += if (strand > 0) Orf(startIdx + 1, i + 3, 1, len / 3)
                            else Orf(s.length - (i + 3) + 1, s.length - startIdx, -1, len / 3)
                        }
                        startIdx = -1
                    }
                    i += 3
                }
            }
        }
        scan(seq.uppercase(), 1)
        scan(Bio.revComp(seq).uppercase(), -1)
        return out.sortedByDescending { (it.end - it.start) }
    }

    /** 常见元件启发式注释（与原版 autoAnnotate 的"命中即标注"思路一致） */
    fun autoAnnotate(seq: String): List<Triple<String, IntRange, String>> {
        val up = seq.uppercase()
        val rules = listOf(
            Triple("lac promoter", "TTTTTAACCCA", "promoter"),
            Triple("T7 promoter", "TAATACGACTCACTATAG", "promoter"),
            Triple("M13 fwd", "GTAAAACGACGGCCAGT", "primer"),
            Triple("M13 rev", "CAGGAAACAGCTATGAC", "primer"),
            Triple("AmpR", "ATGAGTATTCAACATTTCCG", "CDS"),
            Triple("KanR", "ATGATTGAACAAGATGGATTG", "CDS"),
            Triple("ori", "CTGTGTTGTTTTG", "rep_origin"),
            Triple("6xHis", "CACCACCACCACCACCAC", "tag")
        )
        val out = ArrayList<Triple<String, IntRange, String>>()
        rules.forEach { (name, motif, type) ->
            var idx = up.indexOf(motif)
            while (idx >= 0) {
                out += Triple(name, (idx + 1)..(idx + motif.length), type)
                idx = up.indexOf(motif, idx + 1)
                if (out.size > 40) return@forEach
            }
        }
        return out
    }

    // ---------- 序列搜索 ----------
    data class Hit(val start: Int, val strand: Int, val matched: String)

    fun search(seq: String, query: String, bothStrands: Boolean = true): List<Hit> {
        val q = query.uppercase().filter { it.isLetter() }
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        val up = seq.uppercase()
        var i = up.indexOf(q)
        while (i >= 0) {
            out += Hit(i + 1, 1, q)
            i = up.indexOf(q, i + 1)
        }
        if (bothStrands) {
            val rc = Bio.revComp(q)
            val rev = Bio.revComp(up)
            var j = rev.indexOf(rc)
            while (j >= 0) {
                out += Hit(up.length - j - q.length + 1, -1, rc)
                j = rev.indexOf(rc, j + 1)
            }
        }
        return out.sortedBy { it.start }
    }

    // ---------- CRISPR ----------
    data class Guide(val start: Int, val protospacer: String, val pam: String, val gc: Double, val score: Int)

    /** SpCas9 NGG PAM 扫描（正链 + 反链），按 GC 与位置打分 */
    fun crisprGuides(seq: String, max: Int = 20): List<Guide> {
        val up = seq.uppercase()
        val out = ArrayList<Guide>()
        fun scan(s: String, strand: Int) {
            for (i in 0 until s.length - 23) {
                if (s[i + 20] == 'N') continue
                if (s[i + 20] == 'G' && s[i + 21] == 'G') {
                    val sp = s.substring(i, i + 20)
                    val gc = sp.count { it == 'G' || it == 'C' } * 5.0
                    var score = when {
                        gc in 40.0..70.0 -> 80
                        gc in 30.0..80.0 -> 60
                        else -> 30
                    }
                    if (sp.last() == 'G') score += 8
                    if (sp.take(4).none { it == 'T' }) score += 6
                    val pos = if (strand > 0) i + 1 else s.length - (i + 20) + 1
                    out += Guide(pos, if (strand > 0) sp else Bio.revComp(sp), "NGG", gc, score)
                }
            }
        }
        scan(up, 1)
        scan(Bio.revComp(up), -1)
        return out.sortedByDescending { it.score }.take(max)
    }

    // ---------- 合成基因 QC ----------
    data class QcIssue(val level: String, val text: String)

    fun qc(seq: String, enzymeSites: List<Pair<String, String>> = emptyList()): List<QcIssue> {
        val up = seq.uppercase()
        val issues = ArrayList<QcIssue>()
        val gc = Bio.gcContent(up)
        if (gc < 30.0) issues += QcIssue("高", "GC 含量偏低（${"%.1f".format(gc)}%），表达效率可能下降")
        if (gc > 65.0) issues += QcIssue("高", "GC 含量偏高（${"%.1f".format(gc)}%），合成与 PCR 难度上升")
        Regex("(A{6,}|T{6,}|G{6,}|C{6,})").findAll(up).take(5).forEach {
            issues += QcIssue("中", "同聚物串 ${it.value} @ ${it.range.first + 1}")
        }
        Regex("[ACGT]{8,}").findAll(up).forEach { m ->
            val k = m.value
            if (k.length >= 12 && up.indexOf(k + k) >= 0) {
                issues += QcIssue("中", "串联重复 ${k.take(8)}… @ ${m.range.first + 1}")
            }
        }
        enzymeSites.forEach { (name, site) ->
            if (up.contains(site)) issues += QcIssue("低", "含 $name 位点 $site")
        }
        if (up.startsWith("ATG").not() && up.length > 100) {
            issues += QcIssue("低", "序列未以 ATG 起始（若为 CDS 需确认起始密码子）")
        }
        return issues
    }

    // ---------- 融合阅读框 ----------
    fun fusionCheck(a: String, b: String): Triple<Boolean, Int, String> {
        val total = a.length + b.length
        val linked = a + b
        val frameOk = a.length % 3 == 0
        val internalStop = linked.uppercase().chunked(3).any { it in setOf("TAA", "TAG", "TGA") && it != linked.uppercase().takeLast(3) }
        val msg = buildString {
            append("拼接长度 $total bp；")
            append(if (frameOk) "上游片段长度为 3 的倍数，可直接同框融合" else "上游长度 ${a.length} bp 非 3 倍数，需补/删 ${3 - a.length % 3} bp 才能同框")
            if (internalStop) append("；拼接后存在内部终止密码子，翻译会提前终止")
        }
        return Triple(frameOk && !internalStop, total, msg)
    }

    // ---------- 核糖体滑移 ----------
    fun slippageSites(seq: String): List<Pair<Int, String>> {
        val hits = ArrayList<Pair<Int, String>>()
        Regex("(A{5,}|T{5,}|G{4,}|C{4,})").findAll(seq.uppercase()).take(10).forEach {
            hits += (it.range.first + 1) to it.value
        }
        return hits
    }

    // ---------- 退火寡核苷酸 ----------
    data class Anneal(val overlap: Int, val tm: Double, val overhang5: Int, val ok: Boolean)

    fun anneal(a: String, b: String): Anneal {
        val x = a.uppercase()
        val y = Bio.revComp(b).uppercase()
        var best = 0
        var bestStart = 0
        for (len in minOf(x.length, y.length) downTo 6) {
            for (i in 0..(x.length - len)) {
                if (y.contains(x.substring(i, i + len))) {
                    best = len
                    bestStart = i
                    break
                }
            }
            if (best > 0) break
        }
        val tm = Cloning.tmNearest(x.substring(bestStart, bestStart + best).ifEmpty { x })
        return Anneal(best, tm, bestStart, best >= 15 && tm in 45.0..70.0)
    }

    // ---------- 引物 5' 修饰 ----------
    fun addTail(primer: String, tail: String, protect: Boolean = true): String {
        val p = if (protect) "GCGC" else ""
        return (p + tail + primer).uppercase()
    }

    // ---------- 末端编辑 ----------
    fun addOverhang(seq: String, overhang: String, atFivePrime: Boolean): String =
        if (atFivePrime) overhang.uppercase() + seq else seq + overhang.uppercase()

    // ---------- 计算器 ----------
    fun ligationInsertNg(vectorNg: Double, vectorBp: Int, insertBp: Int, ratio: Double = 3.0): Double {
        val vFmol = (vectorNg / (vectorBp * 660.0)) * 1000.0
        return vFmol * ratio * (insertBp * 660.0) / 1000.0
    }

    fun dilution(c1: Double, c2: Double, v2: Double): Double = (c2 * v2) / c1

    fun pcrMix(volumeUl: Double): List<Pair<String, Double>> = listOf(
        "2× Master Mix" to volumeUl / 2,
        "F 引物 (10 µM)" to volumeUl / 20,
        "R 引物 (10 µM)" to volumeUl / 20,
        "模板 DNA" to volumeUl / 20,
        "ddH2O" to volumeUl - volumeUl / 2 - volumeUl / 20 - volumeUl / 20 - volumeUl / 20
    )

    // ---------- 表达盒 ----------
    fun expressionCassette(promoter: String, rbs: String, cds: String, terminator: String): String =
        (promoter + rbs + cds + terminator).uppercase().replace(Regex("[^ACGT]"), "")

    // ---------- NGS 质控 ----------
    fun ngsStats(fastqLike: String): Triple<Int, Double, Double> {
        val lines = fastqLike.lines().filter { it.isNotBlank() }
        val reads = lines.size / 4
        val q = lines.drop(3).filter { it.length > 4 }.mapNotNull { it.drop(4).firstOrNull()?.code }
        val meanQ = if (q.isEmpty()) 0.0 else q.average() - 33.0
        val gc = if (lines.isEmpty()) 0.0 else Bio.gcContent(lines.joinToString(""))
        return Triple(reads, meanQ, gc)
    }
}
