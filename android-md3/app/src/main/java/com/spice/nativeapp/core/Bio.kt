package com.spice.nativeapp.core

/** 质粒/序列特征注释 */
data class Feature(
    val name: String,
    val type: String,
    val start: Int,       // 1-based, inclusive
    val end: Int,         // 1-based, inclusive
    val strand: Int = 1   // +1 / -1
)

/** 一条序列记录（FASTA / GenBank 解析结果或内置演示质粒） */
data class SeqRecord(
    val name: String,
    val sequence: String,
    val circular: Boolean = true,
    val features: List<Feature> = emptyList(),
    val description: String = ""
) {
    val length: Int get() = sequence.length
    val gc: Double get() = Bio.gcContent(sequence)
}

/** 限制酶 */
data class Enzyme(
    val name: String,
    val site: String,
    val cutOffset: Int,
    val buffer: String = "NEB 2.1",
    val bottomCut: Int = -1,
    val aliases: List<String> = emptyList()
)

object Bio {

    private val COMP = mapOf(
        'A' to 'T', 'T' to 'A', 'G' to 'C', 'C' to 'G', 'U' to 'A', 'N' to 'N',
        'R' to 'Y', 'Y' to 'R', 'S' to 'S', 'W' to 'W', 'K' to 'M', 'M' to 'K',
        'B' to 'V', 'V' to 'B', 'D' to 'H', 'H' to 'D'
    )

    fun revComp(seq: String): String = seq.reversed().map { COMP[it.uppercaseChar()] ?: 'N' }.joinToString("")

    fun complement(seq: String): String = seq.map { COMP[it.uppercaseChar()] ?: 'N' }.joinToString("")

    fun gcContent(seq: String): Double {
        if (seq.isEmpty()) return 0.0
        val gc = seq.count { it.equals('G', true) || it.equals('C', true) }
        return gc * 100.0 / seq.length
    }

    fun cleanSequence(raw: String): String =
        raw.filter { it.isLetter() }.uppercase()

    /** 标准密码子表（DNA，第一/二/三碱基顺序 T,C,A,G，与 NCBI 一致） */
    private val BASES = listOf('T', 'C', 'A', 'G')
    private val AA_TABLE = (
        "FFLLSSSSYY**CC*W" +   // T??
        "LLLLPPPPHHQQRRRR" +   // C??
        "IIIMTTTTNNKKSSRR" +   // A??
        "VVVVAAAADDEEGGGG"     // G??
    )

    fun translateCodon(codon: String): Char {
        if (codon.length < 3) return 'X'
        val i1 = BASES.indexOf(codon[0].uppercaseChar())
        val i2 = BASES.indexOf(codon[1].uppercaseChar())
        val i3 = BASES.indexOf(codon[2].uppercaseChar())
        if (i1 < 0 || i2 < 0 || i3 < 0) return 'X'
        return AA_TABLE[i1 * 16 + i2 * 4 + i3]
    }

    fun translate(seq: String, frame: Int = 0): String {
        val sb = StringBuilder()
        var i = frame
        while (i + 3 <= seq.length) {
            sb.append(translateCodon(seq.substring(i, i + 3)))
            i += 3
        }
        return sb.toString()
    }

    /** 六框翻译 */
    fun sixFrames(seq: String): List<Pair<String, String>> {
        val rc = revComp(seq)
        return listOf(
            "+1" to translate(seq, 0), "+2" to translate(seq, 1), "+3" to translate(seq, 2),
            "-1" to translate(rc, 0), "-2" to translate(rc, 1), "-3" to translate(rc, 2)
        )
    }

    /** 最长 ORF（ATG 起始，遇终止密码子结束） */
    fun longestOrf(seq: String, minAa: Int = 30): Triple<Int, Int, Int>? {
        var best: Triple<Int, Int, Int>? = null
        for (frame in 0..2) {
            var i = frame
            var orfStart = -1
            while (i + 3 <= seq.length) {
                val codon = seq.substring(i, i + 3)
                if (codon == "ATG" && orfStart < 0) orfStart = i
                if (codon in setOf("TAA", "TAG", "TGA") && orfStart >= 0) {
                    val aa = (i - orfStart) / 3
                    if (aa >= minAa && (best == null || aa > best.third)) {
                        best = Triple(orfStart + 1, i + 3, aa)
                    }
                    orfStart = -1
                }
                i += 3
            }
        }
        return best
    }

    /** IUPAC 模糊匹配：site 与目标片段等长比较 */
    fun matchesIupac(site: String, target: String): Boolean {
        if (site.length != target.length) return false
        val sets = mapOf(
            'A' to "A", 'C' to "C", 'G' to "G", 'T' to "T",
            'R' to "AG", 'Y' to "CT", 'S' to "GC", 'W' to "AT",
            'K' to "GT", 'M' to "AC", 'B' to "CGT", 'D' to "AGT",
            'H' to "ACT", 'V' to "ACG", 'N' to "ACGT"
        )
        for (i in site.indices) {
            val allowed = sets[site[i].uppercaseChar()] ?: return false
            if (!allowed.contains(target[i].uppercaseChar())) return false
        }
        return true
    }

    /** 环状质粒上查找酶切位点，返回 1-based 切点位置 */
    fun cutSites(seq: String, enzyme: Enzyme, circular: Boolean): List<Int> {
        val site = enzyme.site
        require(site.isNotEmpty())
        val targets = if (circular && seq.length >= site.length) seq + seq.take(site.length - 1) else seq
        val hits = mutableListOf<Int>()
        var i = 0
        while (i + site.length <= targets.length) {
            if (matchesIupac(site, targets.substring(i, i + site.length))) {
                val cut = (i + enzyme.cutOffset) % seq.length + 1
                hits.add(cut)
                i += site.length
            } else i++
        }
        return hits.distinct().sorted()
    }

    /** 内置演示质粒 pUC19（2686 bp, NCBI L09137.2） */
    fun demoPlasmid(): SeqRecord {
        val seq = DEMO_PUC19.replace("\n", "").replace(" ", "")
        return SeqRecord(
            name = "pUC19",
            sequence = seq,
            circular = true,
            features = listOf(
                Feature("lacZα", "CDS", 146, 470, 1),
                Feature("M13 fwd", "primer", 394, 414, 1),
                Feature("M13 rev", "primer", 430, 450, -1),
                Feature("MCS", "misc", 396, 452, 1),
                Feature("bla (AmpR)", "CDS", 1626, 2486, -1),
                Feature("ori", "rep_origin", 2535, 2535, 1),
                Feature("lac promoter", "promoter", 470, 550, 1),
                Feature("ROP", "CDS", 2500, 2600, 1)
            ),
            description = "pUC19 克隆载体（演示序列）"
        )
    }

    private const val DEMO_PUC19 = "TCGCGCGTTTCGGTGATGACGGTGAAAACCTCTGACACATGCAGCTCCCGGAGACGGTCACAGCTTGTC" +
        "TGTAAGCGGATGCCGGGAGCAGACAAGCCCGTCAGGGCGCGTCAGCGGGTGTTGGCGGGTGTCGGGGCTGGCTTAACT" +
        "ATGCGGCATCAGAGCAGATTGTACTGAGAGTGCACCATATGCGGTGTGAAATACCGCACAGATGCGTAAGGAGAAAAT" +
        "ACCGCATCAGGCGCCATTCGCCATTCAGGCTGCGCAACTGTTGGGAAGGGCGATCGGTGCGGGCCTCTTCGCTATTAC" +
        "GCCAGCTGGCGAAAGGGGGATGTGCTGCAAGGCGATTAAGTTGGGTAACGCCAGGGTTTTCCCAGTCACGACGTTGTA" +
        "AAACGACGGCCAGTGAATTCGAGCTCGGTACCCGGGGATCCTCTAGAGTCGACCTGCAGGCATGCAAGCTTGGCGTAA" +
        "TCATGGTCATAGCTGTTTCCTGTGTGAAATTGTTATCCGCTCACAATTCCACACAACATACGAGCCGGAAGCATAAAG" +
        "TGTAAAGCCTGGGGTGCCTAATGAGTGAGCTAACTCACATTAATTGCGTTGCGCTCACTGCCCGCTTTCCAGTCGGGA" +
        "AACCTGTCGTGCCAGCTGCATTAATGAATCGGCCAACGCGCGGGGAGAGGCGGTTTGCGTATTGGGCGCTCTTCCGCT" +
        "TCCTCGCTCACTGACTCGCTGCGCTCGGTCGTTCGGCTGCGGCGAGCGGTATCAGCTCACTCAAAGGCGGTAATACGG" +
        "TTATCCACAGAATCAGGGGATAACGCAGGAAAGAACATGTGAGCAAAAGGCCAGCAAAAGGCCAGGAACCGTAAAAAG" +
        "GCCGCGTTGCTGGCGTTTTTCCATAGGCTCCGCCCCCCTGACGAGCATCACAAAAATCGACGCTCAAGTCAGAGGTGG" +
        "CGAAACCCGACAGGACTATAAAGATACCAGGCGTTTCCCCCTGGAAGCTCCCTCGTGCGCTCTCCTGTTCCGACCCTG" +
        "CCGCTTACCGGATACCTGTCCGCCTTTCTCCCTTCGGGAAGCGTGGCGCTTTCTCATAGCTCACGCTGTAGGTATCTC" +
        "AGTTCGGTGTAGGTCGTTCGCTCCAAGCTGGGCTGTGTGCACGAACCCCCCGTTCAGCCCGACCGCTGCGCCTTATCC" +
        "GGTAACTATCGTCTTGAGTCCAACCCGGTAAGACACGACTTATCGCCACTGGCAGCAGCCACTGGTAACAGGATTAGC" +
        "AGAGCGAGGTATGTAGGCGGTGCTACAGAGTTCTTGAAGTGGTGGCCTAACTACGGCTACACTAGAAGAACAGTATTT" +
        "GGTATCTGCGCTCTGCTGAAGCCAGTTACCTTCGGAAAAAGAGTTGGTAGCTCTTGATCCGGCAAACAAACCACCGCT" +
        "GGTAGCGGTGGTTTTTTTGTTTGCAAGCAGCAGATTACGCGCAGAAAAAAAGGATCTCAAGAAGATCCTTTGATCTTT" +
        "TCTACGGGGTCTGACGCTCAGTGGAACGAAAACTCACGTTAAGGGATTTTGGTCATGAGATTATCAAAAAGGATCTTC" +
        "ACCTAGATCCTTTTAAATTAAAAATGAAGTTTTAAATCAATCTAAAGTATATATGAGTAAACTTGGTCTGACAGTTAC" +
        "CAATGCTTAATCAGTGAGGCACCTATCTCAGCGATCTGTCTATTTCGTTCATCCATAGTTGCCTGACTCCCCGTCGTG" +
        "TAGATAACTACGATACGGGAGGGCTTACCATCTGGCCCCAGTGCTGCAATGATACCGCGAGACCCACGCTCACCGGCT" +
        "CCAGATTTATCAGCAATAAACCAGCCAGCCGGAAGGGCCGAGCGCAGAAGTGGTCCTGCAACTTTATCCGCCTCCATC" +
        "CAGTCTATTAATTGTTGCCGGGAAGCTAGAGTAAGTAGTTCGCCAGTTAATAGTTTGCGCAACGTTGTTGCCATTGCT" +
        "ACAGGCATCGTGGTGTCACGCTCGTCGTTTGGTATGGCTTCATTCAGCTCCGGTTCCCAACGATCAAGGCGAGTTACA" +
        "TGATCCCCCATGTTGTGCAAAAAAGCGGTTAGCTCCTTCGGTCCTCCGATCGTTGTCAGAAGTAAGTTGGCCGCAGTG" +
        "TTATCACTCATGGTTATGGCAGCACTGCATAATTCTCTTACTGTCATGCCATCCGTAAGATGCTTTTCTGTGACTGGT" +
        "GAGTACTCAACCAAGTCATTCTGAGAATAGTGTATGCGGCGACCGAGTTGCTCTTGCCCGGCGTCAATACGGGATAAT" +
        "ACCGCGCCACATAGCAGAACTTTAAAAGTGCTCATCATTGGAAAACGTTCTTCGGGGCGAAAACTCTCAAGGATCTTA" +
        "CCGCTGTTGAGATCCAGTTCGATGTAACCCACTCGTGCACCCAACTGATCTTCAGCATCTTTTACTTTCACCAGCGTT" +
        "TCTGGGTGAGCAAAAACAGGAAGGCAAAATGCCGCAAAAAAGGGAATAAGGGCGACACGGAAATGTTGAATACTCATA" +
        "CTCTTCCTTTTTCAATATTATTGAAGCATTTATCAGGGTTATTGTCTCATGAGCGGATACATATTTGAATGTATTTAG" +
        "AAAAATAAACAAATAGGGGTTCCGCGCACATTTCCCCGAAAAGTGCCACCTGACGTCTAAGAAACCATTATTATCATG" +
        "ACATTAACCTATAAAAATAGGCGTATCACGAGGCCCTTTCGTCTTCAAGA"
}
