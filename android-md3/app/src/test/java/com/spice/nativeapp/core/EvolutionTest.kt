package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Evolution.kt（main sequenceAnalysis.ts 1:1）算法语义测试。
 */
class EvolutionTest {

    // ── 7: runLocalBlast ──

    @Test fun blastSelfHitFullIdentity() {
        val egfp = defaultGeneCollections()[3].seq
        val query = egfp.substring(60, 120) // 60bp 片段
        val hits = runLocalBlast(query, listOf("egfp" to egfp))
        assertTrue(hits.isNotEmpty())
        val best = hits.first()
        assertEquals(100.0, best.identity, 0.0)
        assertEquals(60, best.queryEnd - best.queryStart + 1)
        assertEquals(60, best.subjectEnd - best.subjectStart + 1)
        // score = matches*2 - mismatches*1 = 120
        assertEquals(120, best.score)
        // 位置从片段真实起点开始（0-based）
        assertEquals(60, best.subjectStart)
        // alignmentStr 三行：query / 匹配线 / subject
        assertEquals(3, best.alignmentStr.lines().size)
        assertTrue(best.alignmentStr.lines()[1].all { it == '|' })
    }

    @Test fun blastLowComplexitySeedFiltered() {
        // 主体中该种子命中 > 8 次 → 跳过（防 N² 卡死），不应产生命中
        val seq = "GATC".repeat(40)
        val hits = runLocalBlast("GATC".repeat(3) + "TTTAGCAGT", listOf("rep" to seq))
        assertTrue(hits.none { h -> h.alignmentStr.contains("GATCGATCGATC") })
    }

    @Test fun blastMinLengthGateKPlus4() {
        // 命中长度 >= k+4 = 15 才记录；给一个只有 11bp 的完全匹配不会入表
        val subject = "A".repeat(11) + "CCCCCCCCCCCCCCCCCCGATCGATCGATCGGG"
        val query = "A".repeat(11)
        val hits = runLocalBlast(query, listOf("s" to subject))
        assertTrue(hits.isEmpty())
    }

    @Test fun blastDedupAndSort() {
        val egfp = defaultGeneCollections()[3].seq
        val query = egfp.substring(100, 130)
        val hits = runLocalBlast(query, listOf("a" to egfp, "b" to egfp))
        // 同一 subject 内不重复记录同一延伸窗口
        assertTrue(hits.none { h -> h.subjectName == "a" && hits.count { it.subjectName == "a" && it.subjectStart == h.subjectStart } > 1 })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    // ── 8: buildPhylogeneticTree（NJ）──

    @Test fun njJoinsClosestPairFirst() {
        val names = listOf("pUC19_cloning_vector", "pcDNA3.1_mammalian_expression", "pET28a_protein_expression", "egfp_reporter_gene")
        val matrix = listOf(
            listOf(0.0, 0.45, 0.52, 0.85),
            listOf(0.45, 0.0, 0.22, 0.88),
            listOf(0.52, 0.22, 0.0, 0.91),
            listOf(0.85, 0.88, 0.91, 0.0)
        )
        val (newick, root) = buildPhylogeneticTree(names, matrix)
        assertTrue(newick.endsWith(";"))
        // Q 准则（L=4）：Q(0,3)=2*0.85-1.82-2.64=-2.76 与 Q(1,2)=2*0.22-3.20=-2.76 并列，
        // 扫描序 (i<j) 先命中 (0,3) → 首聚合 pUC19+egfp，d_wu=0.5*0.85+(1.82-2.64)/4=0.22
        val first = root.children.first { c -> !c.isLeaf && c.children.size == 2 && !c.children.any { it.isLeaf && (it.name == names[1] || it.name == names[2]) } }
        val pair = first.children
        assertEquals(names[0], pair[0].name)
        assertEquals(names[3], pair[1].name)
        assertEquals(0.22, pair[0].branchLength, 1e-5)
        assertEquals(0.63, pair[1].branchLength, 1e-5)
        // Newick 含全部 4 个叶名
        names.forEach { n -> assertTrue(newick.contains(n)) }
        // 叶总数 = 4
        fun leaves(n: PhylogenyNode): Int = if (n.isLeaf) 1 else n.children.sumOf { leaves(it) }
        assertEquals(4, leaves(root))
    }

    @Test fun njEmptyThrowsMessage() {
        try {
            buildPhylogeneticTree(emptyList(), emptyList())
            fail("expected EvolutionException")
        } catch (e: EvolutionException) {
            assertEquals("比对序列数目为零，无法构建进化树", e.message)
        }
    }

    // ── 9: calculateSequenceLogo ──

    @Test fun logoConservedPositionFullBits() {
        val seqs = listOf(
            "GTTTTCCCAGTCACGAC",
            "GTTTTCCAAGTCACGAC",
            "GTTTTCCCAGTCACGAT",
            "GTTTTCCAAGTCACGAT",
            "GTTTTCACAGTCACGAC"
        )
        val logo = calculateSequenceLogo(seqs)
        assertEquals(17, logo.size)
        val p1 = logo.first()
        // 位置 1 全 G：熵 0 → R = 2 bits
        assertEquals(2.0, p1.totalEntropyBits, 1e-6)
        assertEquals(1, p1.frequencies.size)
        assertEquals('G', p1.frequencies[0].char)
        assertEquals(2.0, p1.frequencies[0].bitsScaled, 1e-6)
        // 频率按 bitsScaled 降序
        logo.forEach { pos -> assertTrue(pos.frequencies.zipWithNext().all { (a, b) -> a.bitsScaled >= b.bitsScaled }) }
    }

    @Test fun logoSkipsGapOnlyColumns() {
        val logo = calculateSequenceLogo(listOf("A--", "A--"))
        assertEquals(1, logo.size)
        assertEquals('A', logo[0].frequencies[0].char)
    }

    // ── 10: calculateDotPlot ──

    @Test fun dotPlotSelfDiagonal() {
        val seq = "ACGTACGTACGTACGTACGTACGTACGT".repeat(3)
        val pts = calculateDotPlot(seq, seq, 15, 12)
        // 自比较必有主对角线点
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.any { it.x == it.y })
    }

    @Test fun dotPlotCap1500() {
        val seq = "A".repeat(2000)
        val pts = calculateDotPlot(seq, seq, 15, 12)
        assertTrue(pts.size <= 1500)
    }

    @Test fun dotPlotNoMatchDifferentSeqs() {
        // 随机两序列窗口匹配数 < threshold → 无点
        val a = "ACACACACACACACACACACACACACACACACACACACAC"
        val b = "GTGTGTGTGTGTGTGTGTGTGTGTGTGTGTGTGTGTGTGT"
        assertTrue(calculateDotPlot(a, b, 15, 12).isEmpty())
    }

    // ── 默认 collections（main gene/+page.svelte 默认表）──

    @Test fun defaultCollectionsMatchMainSizes() {
        val cols = defaultGeneCollections()
        assertEquals(4, cols.size)
        assertEquals(listOf("col_1", "col_2", "col_3", "col_4"), cols.map { it.id })
        cols.forEach { c -> assertEquals(c.size, c.seq.length) } // size 与真实序列长度一致
        // pUC19 恰含 15 个 GATC
        assertEquals(15, Regex("GATC").findAll(cols[0].seq).count())
        assertEquals(8, Regex("GATC").findAll(cols[1].seq).count())
        assertEquals(12, Regex("GATC").findAll(cols[2].seq).count())
        // main 注释称 "exactly 1 GATC site"，实测序列含 3 处 GATC（数据以 main 逐字符为准）
        assertEquals(3, Regex("GATC").findAll(cols[3].seq).count())
        // egfp CDS 以 ATG 起 TAA 止
        assertTrue(cols[3].seq.startsWith("ATGGTGAGCAAG"))
        assertTrue(cols[3].seq.endsWith("TACAAGTAA"))
    }
}
