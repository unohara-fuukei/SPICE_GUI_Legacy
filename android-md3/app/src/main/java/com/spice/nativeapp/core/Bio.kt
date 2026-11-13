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
/** 引物（1-based inclusive；对齐 main ovePrimers {name,start,end,forward}） */
data class Primer(
    val name: String,
    val start: Int,
    val end: Int,
    val forward: Boolean = true
)

data class SeqRecord(
    val name: String,
    val sequence: String,
    val circular: Boolean = true,
    val features: List<Feature> = emptyList(),
    val description: String = "",
    // GenBank 属性页字段（对齐 main gene/+page.svelte:820 起的 Rich Properties 状态组）
    val createdDate: String = "",
    val modifiedDate: String = "",
    val dnaType: String = "Natural DNA",
    val sequenceClass: String = "PLN - plant, fungal, and algal",
    val labHost: String = "",
    val accession: String = "",
    val codeNumber: String = "",
    val author: String = "",
    val comments: String = "",
    val references: String = "",
    // 引物（对齐 main ovePrimers；图谱/序列视图的引物轨道数据源）
    val primers: List<Primer> = emptyList()
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

        /** pJ5_00001 演示质粒（对齐 main exampleSequenceData.ts：5299 bp 环状，22 特征，Teselagen 设计）
     *  坐标已从 main 的 0-based inclusive 转为 1-based inclusive；类型沿用 GenBank 类型名。 */
    private const val DEMO_PJ5 =
        "gacgtcttatgacaacttgacggctacatcattcactttttcttcacaaccggcacggaactcgctcgggctggccccggtgcatttttt" +
        "aaatacccgcgagaaatagagttgatcgtcaaaaccaacattgcgaccgacggtggcgataggcatccgggtggtgctcaaaagcagctt" +
        "cgcctggctgatacgttggtcctcgcgccagcttaagacgctaatccctaactgctggcggaaaagatgtgacagacgcgacggcgacaa" +
        "gcaaacatgctgtgcgacgctggcgatatcaaaattgctgtctgccaggtgatcgctgatgtactgacaagcctcgcgtacccgattatc" +
        "catcggtggatggagcgactcgttaatcgcttccatgcgccgcagtaacaattgctcaagcagatttatcgccagcagctccgaatagcg" +
        "cccttccccttgcccggcgttaatgatttgcccaaacaggtcgctgaaatgcggctggtgcgcttcatccgggcgaaagaaccccgtatt" +
        "ggcaaatattgacggccagttaagccattcatgccagtaggcgcgcggacgaaagtaaacccactggtgataccattcgcgagcctccgg" +
        "atgacgaccgtagtgatgaatctctcctggcgggaacagcaaaatatcacccggtcggcaaacaaattctcgtccctgatttttcaccac" +
        "cccctgaccgcgaatggtgagattgagaatataacctttcattcccagcggtcggtcgataaaaaaatcgagataaccgttggcctcaat" +
        "cggcgttaaacccgccaccagatgggcattaaacgagtatcccggcagcaggggatcattttgcgcttcagccatacttttcatactccc" +
        "gccattcagagaagaaaccaattgtccatattgcatcagacattgccgtcactgcgtcttttactggctcttctcgctaaccaaaccggt" +
        "aaccccgcttattaaaagcattctgtaacaaagcgggaccaaagccatgacaaaaacgcgtaacaaaagtgtctataatcacggcagaaa" +
        "agtccacattgattatttgcacggcgtcacactttgctatgccatagcatttttatccataagattagcggattctacctgacgcttttt" +
        "atcgcaactctctactgtttctccatacccgtttttttgggaatttttaagaaggagatatacatatgagtaaaggagaagaacttttca" +
        "ctggagttgtcccaattcttgttgaattagatggtgatgttaatgggcacaaattttctgtcagtggagagggtgaaggtgatgcaacat" +
        "acggaaaacttacccttaaatttatttgcactactggaaaactacctgttccatggccaacacttgtcactactttctcttatggtgttc" +
        "aatgcttttcccgttatccggatcatatgaaacggcatgactttttcaagagtgccatgcccgaaggttatgtacaggaacgcactatat" +
        "ctttcaaagatgacgggaactacaagacgcgtgctgaagtcaagtttgaaggtgatacccttgttaatcgtatcgagttaaaaggtattg" +
        "attttaaagaagatggaaacattctcggacacaaactcgaatacaactataactcacacaatgtatacatcacggcagacaaacaaaaga" +
        "atggaatcaaagctaacttcaaaattcgccacaacattgaagatggatctgttcaactagcagaccattatcaacaaaatactccaattg" +
        "gcgatggccctgtccttttaccagacaaccattacctgtcgacacaatctgccctttcgaaagatcccaacgaaaagcgtgaccacatgg" +
        "tccttcttgagtttgtaactgctgctgggattacacatggcatggatgagctcggcggcggcggcagcaaggtctacggcaaggaacagt" +
        "ttttgcggatgcgccagagcatgttccccgatcgctaaatcgagtaaggatctccaggcatcaaataaaacgaaaggctcagtcgaaaga" +
        "ctgggcctttcgttttatctgttgtttgtcggtgaacgctctctactagagtcacactggctcaccttcgggtgggcctttctgcgttta" +
        "tacctagggtacgggttttgctgcccgcaaacgggctgttctggtgttgctagtttgttatcagaatcgcagatccggcttcagccggtt" +
        "tgccggctgaaagcgctatttcttccagaattgccatgattttttccccacgggaggcgtcactggctcccgtgttgtcggcagctttga" +
        "ttcgataagcagcatcgcctgtttcaggctgtctatgtgtgactgttgagctgtaacaagttgtctcaggtgttcaatttcatgttctag" +
        "ttgctttgttttactggtttcacctgttctattaggtgttacatgctgttcatctgttacattgtcgatctgttcatggtgaacagcttt" +
        "gaatgcaccaaaaactcgtaaaagctctgatgtatctatcttttttacaccgttttcatctgtgcatatggacagttttccctttgatat" +
        "gtaacggtgaacagttgttctacttttgtttgttagtcttgatgcttcactgatagatacaagagccataagaacctcagatccttccgt" +
        "atttagccagtatgttctctagtgtggttcgttgtttttgcgtgagccatgagaacgaaccattgagatcatacttactttgcatgtcac" +
        "tcaaaaattttgcctcaaaactggtgagctgaatttttgcagttaaagcatcgtgtagtgtttttcttagtccgttatgtaggtaggaat" +
        "ctgatgtaatggttgttggtattttgtcaccattcatttttatctggttgttctcaagttcggttacgagatccatttgtctatctagtt" +
        "caacttggaaaatcaacgtatcagtcgggcggcctcgcttatcaaccaccaatttcatattgctgtaagtgtttaaatctttacttattg" +
        "gtttcaaaacccattggttaagccttttaaactcatggtagttattttcaagcattaacatgaacttaaattcatcaaggctaatctcta" +
        "tatttgccttgtgagttttcttttgtgttagttcttttaataaccactcataaatcctcatagagtatttgttttcaaaagacttaacat" +
        "gttccagattatattttatgaatttttttaactggaaaagataaggcaatatctcttcactaaaaactaattctaatttttcgcttgaga" +
        "acttggcatagtttgtccactggaaaatctcaaagcctttaaccaaaggattcctgatttccacagttctcgtcatcagctctctggttg" +
        "ctttagctaatacaccataagcattttccctactgatgttcatcatctgagcgtattggttataagtgaacgataccgtccgttctttcc" +
        "ttgtagggttttcaatcgtggggttgagtagtgccacacagcataaaattagcttggtttcatgctccgttaagtcatagcgactaatcg" +
        "ctagttcatttgctttgaaaacaactaattcagacatacatctcaattggtctaggtgattttaatcactataccaattgagatgggcta" +
        "gtcaatgataattactagtccttttcccgggtgatctgggtatctgtaaattctgctagacctttgctggaaaacttgtaaattctgcta" +
        "gaccctctgtaaattccgctagacctttgtgtgttttttttgtttatattcaagtggttataatttatagaataaagaaagaataaaaaa" +
        "agataaaaagaatagatcccagccctgtgtataactcactactttagtcagttccgcagtattacaaaaggatgtcgcaaacgctgtttg" +
        "ctcctctacaaaacagaccttaaaaccctaaaggcttaagtagcaccctcgcaagctcgggcaaatcgctgaatattccttttgtctccg" +
        "accatcaggcacctgagtcgctgtctttttcgtgacattcagttcgctgcgctcacggctctggcagtgaatgggggtaaatggcactac" +
        "aggcgccttttatggattcatgcaaggaaactacccataatacaagaaaagcccgtcacgggcttctcagggcgttttatggcgggtctg" +
        "ctatgtggtgctatctgactttttgctgttcagcagttcctgccctctgattttccagtctgaccacttcggattatcccgtgacaggtc" +
        "attcagactggctaatgcacccagtaaggcagcggtatcatcaacaggcttacccgtcttactgtccctagtgcttggattctcaccaat" +
        "aaaaaacgcccggcggcaaccgagcgttctgaacaaatccagatggagttctgaggtcattactggatctatcaacaggagtccaagcga" +
        "gctcgatatcaaattacgccccgccctgccactcatcgcagtactgttgtaattcattaagcattctgccgacatggaagccatcacaaa" +
        "cggcatgatgaacctgaatcgccagcggcatcagcaccttgtcgccttgcgtataatatttgcccatggtgaaaacgggggcgaagaagt" +
        "tgtccatattggccacgtttaaatcaaaactggtgaaactcacccagggattggctgagacgaaaaacatattctcaataaaccctttag" +
        "ggaaataggccaggttttcaccgtaacacgccacatcttgcgaatatatgtgtagaaactgccggaaatcgtcgtggtattcactccaga" +
        "gcgatgaaaacgtttcagtttgctcatggaaaacggtgtaacaagggtgaacactatcccatatcaccagctcaccgtctttcattgcca" +
        "tacgaaattccggatgagcattcatcaggcgggcaagaatgtgaataaaggccggataaaacttgtgcttatttttctttacggtcttta" +
        "aaaaggccgtaatatccagctgaacggtctggttataggtacattgagcaactgactgaaatgcctcaaaatgttctttacgatgccatt" +
        "gggatatatcaacggtggtatatccagtgatttttttctccattttagcttccttagctcctgaaaatctcgataactcaaaaaatacgc" +
        "ccggtagtgatcttatttcattatggtgaaagttggaacctcttacgtgccgatcaacgtctcattttcgccagatatc"

    /** 默认演示记录：pJ5_00001（对齐 main 基因页默认加载的 OVE demo，替代早期占位 pUC19） */
    fun demoPlasmid(): SeqRecord = SeqRecord(
        name = "pj5_00001",
        sequence = DEMO_PJ5,
        circular = true,
        features = listOf(
            Feature("araC", "CDS", 7, 885, -1),  // 0-based 6..884
            Feature("Operator I2 and I1", "protein_bind", 1124, 1162, 1),  // 0-based 1123..1161
            Feature("signal_peptide", "CDS", 1953, 2015, 1),  // 0-based 1952..2014
            Feature("dbl term", "terminator", 2034, 2162, 1),  // 0-based 2033..2161
            Feature("operator O1", "protein_bind", 1072, 1093, 1),  // 0-based 1071..1092
            Feature("CAP site", "misc_binding", 1115, 1128, 1),  // 0-based 1114..1127
            Feature("pBAD promoter", "promoter", 1161, 1188, 1),  // 0-based 1160..1187
            Feature("GFPuv", "CDS", 1236, 2018, 1),  // 0-based 1235..2017
            Feature("XhoI_silent_mutation", "misc_feature", 1661, 1661, 1),  // 0-based 1660..1660
            Feature("RBS", "RBS", 1216, 1235, 1),  // 0-based 1215..1234
            Feature("operator O2", "protein_bind", 914, 931, 1),  // 0-based 913..930
            Feature("BamHI_silent_mutation", "misc_feature", 1760, 1760, 1),  // 0-based 1759..1759
            Feature("CmR", "misc_marker", 4514, 5173, -1),  // 0-based 4513..5172
            Feature("pSC101**", "rep_origin", 2164, 4392, -1),  // 0-based 2163..4391
            Feature("T0", "terminator", 4393, 4498, 1),  // 0-based 4392..4497
            Feature("araE promoter", "promoter", 1036, 1064, -1),  // 0-based 1035..1063
            Feature("araD", "misc_feature", 7, 885, -1),  // 0-based 6..884
            Feature("dbl term", "misc_feature", 2034, 2162, 1),  // 0-based 2033..2161
            Feature("CmR I'm a real long label", "misc_feature", 4514, 5173, -1),  // 0-based 4513..5172
            Feature("GFPuv", "misc_feature", 1242, 1952, 1),  // 0-based 1241..1951
            Feature("pSC101**", "misc_feature", 2164, 4392, -1),  // 0-based 2163..4391
            Feature("pS8c-vector_backbone", "misc_feature", 2016, 1238, 1)  // 0-based 2015..1237
        ),
        description = "pJ5_00001 · araC/pBAD-GFPuv/pSC101 演示质粒（24-APR-2018，对齐 main OVE demo）",
        // Rich Properties 演示默认值（逐项对齐 main gene/+page.svelte:820-830 的 $state 初始值）
        createdDate = "Oct 25, 2005",
        modifiedDate = "Oct 25, 2005",
        dnaType = "Natural DNA",
        sequenceClass = "PLN - plant, fungal, and algal",
        labHost = "Komagataella pastoris (Pichia pastoris)",
        accession = "DQ115396",
        codeNumber = "",
        author = "",
        comments = "",
        references = "",
        // 引物（main exampleSequenceData：Example Primer 1，0-based 280..300 → 1-based 281..301，forward）
        primers = listOf(Primer("Example Primer 1", 281, 301, true))
    )
}
