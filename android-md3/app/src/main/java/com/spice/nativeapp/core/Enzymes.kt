package com.spice.nativeapp.core

/** 常用限制酶目录（识别位点 / 切点偏移 / 推荐缓冲液） */
object Enzymes {

    val catalog: List<Enzyme> = listOf(
        Enzyme("EcoRI", "GAATTC", 1, "NEB 1.1"),
        Enzyme("BamHI", "GGATCC", 1, "NEB 3.1"),
        Enzyme("HindIII", "AAGCTT", 1, "NEB 2.1"),
        Enzyme("XbaI", "TCTAGA", 1, "NEB 2.1"),
        Enzyme("SalI", "GTCGAC", 1, "NEB 3.1"),
        Enzyme("PstI", "CTGCAG", 5, "NEB 3.1"),
        Enzyme("SphI", "GCATGC", 5, "NEB 2.1"),
        Enzyme("KpnI", "GGTACC", 5, "NEB 1.1"),
        Enzyme("SacI", "GAGCTC", 5, "NEB 1.1"),
        Enzyme("SmaI", "CCCGGG", 3, "NEB 4.1"),
        Enzyme("XmaI", "CCCGGG", 1, "NEB 4.1"),
        Enzyme("NcoI", "CCATGG", 1, "NEB 3.1"),
        Enzyme("NdeI", "CATATG", 2, "NEB 2.1"),
        Enzyme("SpeI", "ACTAGT", 1, "NEB 3.1"),
        Enzyme("NheI", "GCTAGC", 1, "NEB 1.1"),
        Enzyme("BglII", "AGATCT", 1, "NEB 3.1"),
        Enzyme("ClaI", "ATCGAT", 2, "NEB 3.1"),
        Enzyme("AatII", "GACGTC", 5, "NEB 3.1"),
        Enzyme("AflII", "CTTAAG", 1, "NEB 3.1"),
        Enzyme("DraI", "TTTAAA", 3, "NEB 4.1"),
        Enzyme("EcoRV", "GATATC", 3, "NEB 3.1"),
        Enzyme("FokI", "GGATG", 9, "NEB 4.1"),
        Enzyme("HaeIII", "GGCC", 2, "NEB 2.1"),
        Enzyme("HpaI", "GTTAAC", 3, "NEB 4.1"),
        Enzyme("MluI", "ACGCGT", 1, "NEB 3.1"),
        Enzyme("NruI", "TCGCGA", 3, "NEB 3.1"),
        Enzyme("PvuI", "CGATCG", 4, "NEB 3.1"),
        Enzyme("PvuII", "CAGCTG", 3, "NEB 3.1"),
        Enzyme("ScaI", "AGTACT", 3, "NEB 3.1"),
        Enzyme("StuI", "AGGCCT", 3, "NEB 3.1"),
        Enzyme("BsaI", "GGTCTC", 1, "NEB 3.1"),
        Enzyme("BsmBI", "CGTCTC", 1, "NEB 3.1"),
        Enzyme("BbsI", "GAAGAC", 2, "NEB 3.1"),
        Enzyme("SapI", "GCTCTTC", 1, "NEB 3.1"),
        Enzyme("AarI", "CACCTGC", 11, "NEB 3.1"),
        Enzyme("NotI", "GCGGCCGC", 2, "NEB 3.1"),
        Enzyme("SbfI", "CCTGCAGG", 8, "NEB 3.1"),
        Enzyme("AscI", "GGCGCGCC", 2, "NEB 4.1"),
        Enzyme("PacI", "TTAATTAA", 5, "NEB 4.1"),
        Enzyme("PmeI", "GTTTAAAC", 4, "NEB 4.1"),
        Enzyme("SwaI", "ATTTAAAT", 4, "NEB 4.1"),
        Enzyme("FseI", "GGCCGGCC", 6, "NEB 4.1"),
        Enzyme("I-CeuI", "TAACTATAACGGTCCTAAGGTAGCGAA", 20, "NEB 4.1"),
        Enzyme("I-SceI", "TAGGGATAACAGGGTAAT", 13, "NEB 4.1"),
        Enzyme("PI-PspI", "TGGCAAACAGCTATTATGGGTATTATGGGT", 25, "NEB 4.1"),
        Enzyme("PI-TliI", "TACTCTGTAGAGCTTGGCACCGACCTCGGCG", 12, "NEB 4.1")
    ).sortedBy { it.name }

    /** 常用酶（MyC 面板默认勾选） */
    val common: Set<String> = setOf("EcoRI", "BamHI", "HindIII", "XbaI", "SalI", "PstI", "SphI", "KpnI", "SacI", "SmaI")

    fun byName(name: String): Enzyme? = catalog.firstOrNull { it.name == name }

    data class Hit(val enzyme: String, val pos: Int, val site: String, val buffer: String)

    /** 对选中酶集合计算全部切点 */
    fun digest(seq: String, names: Set<String>, circular: Boolean): List<Hit> {
        val out = mutableListOf<Hit>()
        for (n in names) {
            val e = byName(n) ?: continue
            for (p in Bio.cutSites(seq, e, circular)) out.add(Hit(e.name, p, e.site, e.buffer))
        }
        return out.sortedBy { it.pos }
    }

    /** 单酶切片段（用于电泳 / 凝胶） */
    fun fragments(seq: String, names: Set<String>, circular: Boolean): List<Int> {
        val hits = digest(seq, names, circular)
        val pos = hits.map { it.pos - 1 }.distinct().sorted()
        if (pos.isEmpty()) return listOf(seq.length)
        if (!circular) {
            val cuts = (pos + listOf(0, seq.length)).distinct().sorted()
            return cuts.zipWithNext { a, b -> b - a }.filter { it > 0 }
        }
        val cuts = pos
        return cuts.indices.map { i ->
            val a = cuts[i]
            val b = cuts[(i + 1) % cuts.size]
            if (i == cuts.size - 1) seq.length - a + b else b - a
        }
    }

    /** 双酶切推荐：缓冲液相同且切点数合理 */
    fun doubleDigestSuggest(seq: String, names: Set<String>, circular: Boolean): List<Pair<String, String>> {
        val usable = names.mapNotNull { byName(it) }.filter { Bio.cutSites(seq, it, circular).isNotEmpty() }
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            if (usable[i].buffer == usable[j].buffer) pairs.add(usable[i].name to usable[j].name)
        }
        return pairs
    }
}
