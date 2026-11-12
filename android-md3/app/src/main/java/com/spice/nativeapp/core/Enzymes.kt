package com.spice.nativeapp.core

/** 常用限制酶目录（识别位点 / 切点偏移 / 推荐缓冲液） */
object Enzymes {

    /** 全量限制酶库：532 个识别位点组、3732 个同义名（对应原版 REBASE 820 条口径） */
    val catalog: List<Enzyme> = EnzymeDb.catalog

    val groupCount: Int get() = EnzymeDb.groupCount
    val nameCount: Int get() = EnzymeDb.nameCount
    const val rebaseCount: Int = EnzymeDb.rebaseCount

    /** 兼容旧引用：前 46 个常用酶 */
    val legacyCommon = catalog.take(46)

    /** 常用酶（MyC 面板默认勾选） */
    val common: Set<String> = setOf("EcoRI", "BamHI", "HindIII", "XbaI", "SalI", "PstI", "SphI", "KpnI", "SacI", "SmaI")

    fun byName(name: String): Enzyme? = catalog.firstOrNull {
        it.name.equals(name, ignoreCase = true) || it.aliases.any { a -> a.equals(name, ignoreCase = true) }
    }

    /** 按关键词搜索（标准名 + 别名），空关键词返回前 [limit] 个 */
    fun search(query: String, limit: Int = 40): List<Enzyme> {
        val q = query.trim()
        if (q.isEmpty()) return catalog.take(limit)
        val lower = q.lowercase()
        return catalog.filter {
            it.name.lowercase().contains(lower) ||
                it.site.contains(lower) ||
                it.aliases.any { a -> a.lowercase().contains(lower) }
        }.take(limit)
    }

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
