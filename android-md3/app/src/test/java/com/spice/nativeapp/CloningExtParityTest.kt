package com.spice.nativeapp

import com.spice.nativeapp.core.CloningExt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CloningExt parity 测试 — 参考值由原版 SPICE_main（tsx 逐字 import 原版
 * cloningEngineering.ts / primersLims.ts / genomics.ts + backend/api.ts GG fallback）
 * 生成：/tmp/cext_gen.ts → /tmp/cext_ref.json。
 * 覆盖：重组酶五场景（含主版 inversion 不可达 quirk——第二位点为反向互补时
 * indexOf(sitePattern) 找不到 → no_reaction，主版行为原样复刻）、SSR 标记、
 * SNP KASP/TaqMan、RFLP 两场景、GG fallback 四场景。
 */
class CloningExtParityTest {

    // ── 夹具（与 cext_gen.ts 完全一致） ──
    private val loxp = "ATAACTTCGTATAATGTATGCTATACGAAGTTAT"
    private val frt = "GAAGTTCCTATTCTCTAGAAAGTATAGGAACTTC"

    private val ssrSeq = "ACGTTTTTTTTTACGTACGTACGT" + "CACACACACACACACACACACACACACACACACA" + "TTTAGCTAGCTAGCTAGCTAGCTAAAACCCCGGGGTTTACGTACGTACGTACGT"
    private val snpSeq = "ATGCGTACGTTGCACTGCAGTTACGCATGCATGCTAACGTACGTCGATCGATCGTAGCTAGCTAGCATCGATCGTACGATCAC"
    private val wtSeq = "ACGATCGATCGAATTCGCTAGCTAGCTAGCTAATTCGATCGATC"
    private val mutSeq = "ACGATCGATCGAATTAGCTAGCTAGCTAGCTAATTCGATCGATC"
    private val mut2Seq = "ACGATCGATCGAATTAGCTAGCTAGCTAGCTAGAATTCATCGATC"

    // ── 15: 重组酶 ──

    @Test
    fun `重组酶 - 整合场景 Cre-lox`() {
        val v = "GATCTTCG" + loxp + "A".repeat(100)
        val i = loxp + "T".repeat(50)
        val r = CloningExt.simulateRecombinaseCloning(v, i, "Cre-lox")
        assertEquals("integration", r.reactionType)
        assertEquals(
            "GATCTTCGATAACTTCGTATAATGTATGCTATACGAAGTTAT" + "T".repeat(50) +
                loxp + "A".repeat(100), r.productSequence)
        assertEquals(1, r.log.size)
    }

    @Test
    fun `重组酶 - 环出切除场景 Cre-lox`() {
        val v = "GATCTTCG" + loxp + "G".repeat(30) + loxp + "A".repeat(50)
        val r = CloningExt.simulateRecombinaseCloning(v, "", "Cre-lox")
        assertEquals("excision", r.reactionType)
        assertEquals("GATCTTCG" + loxp + "A".repeat(50), r.productSequence)
    }

    @Test
    fun `重组酶 - 倒位场景为主版不可达 quirk（no_reaction 原样复刻）`() {
        // 主版算法以 indexOf(sitePattern) 找第二位点，反向互补位点搜不到 →
        // "inversion" 为死分支，倒位场景恒 no_reaction 且产物序列不变
        val v = "GATCTTCG" + loxp + "G".repeat(30) +
            com.spice.nativeapp.core.Bio.revComp(loxp) + "A".repeat(50)
        val r = CloningExt.simulateRecombinaseCloning(v, "", "Cre-lox")
        assertEquals("no_reaction", r.reactionType)
        assertEquals(v, r.productSequence)
    }

    @Test
    fun `重组酶 - 整合场景 FLP-FRT`() {
        val v = "GATCTTCG" + frt + "C".repeat(80)
        val i = frt + "T".repeat(50)
        val r = CloningExt.simulateRecombinaseCloning(v, i, "FLP-FRT")
        assertEquals("integration", r.reactionType)
        assertEquals(
            "GATCTTCG" + frt + "T".repeat(50) + frt + "C".repeat(80), r.productSequence)
    }

    @Test
    fun `重组酶 - 无位点对`() {
        val r = CloningExt.simulateRecombinaseCloning("ACGTACGTAGCTAGCT", "GGGGCCCC", "Cre-lox")
        assertEquals("no_reaction", r.reactionType)
        assertEquals("ACGTACGTAGCTAGCT", r.productSequence)
        assertEquals("[Cre-lox] 未找到匹配的重组位点对，无法触发位点特异性基因重组。", r.log[0])
    }

    // ── 16: SSR ──

    @Test
    fun `SSR 标记设计`() {
        val ms = CloningExt.designSsrMarkers(ssrSeq)
        assertEquals(1, ms.size)
        val m = ms[0]
        assertEquals("TAGC", m.repeatPattern)
        assertEquals(5, m.repeatCount)
        assertEquals(61, m.start)
        assertEquals(80, m.end)
        assertEquals("CACACACACACACACACATT", m.forwardPrimer)
        assertEquals("GTACGTAAACCCCGGGGTTTTA", m.reversePrimer)
        assertEquals(62, m.productSize)
        assertEquals(50, m.tm)
    }

    // ── 17: SNP KASP/TaqMan ──

    @Test
    fun `SNP 分型引物 KASP 与 TaqMan`() {
        val r = CloningExt.designSnpGenotypingPrimers(snpSeq, 40, "C", "T")
        assertEquals("GAAGGTGACCAAGTTCATGCTTTACGCATGCATGCTAACGTC", r.kasp.allele1Forward)
        assertEquals("GAAGGTCGGTCAAGCGACTGCTTACGCATGCATGCTAACGTT", r.kasp.allele2Forward)
        assertEquals("CTAGCTACGATCGATCGACG", r.kasp.commonReverse)
        assertEquals(52, r.kasp.tm)
        assertEquals("", r.taqman.forwardPrimer)
        assertEquals("GTG", r.taqman.reversePrimer)
        assertEquals("5'-[FAM]-ATGCTAACGTCCGTCGATCG-[BHQ1]-3'", r.taqman.probeAllele1)
        assertEquals("5'-[VIC]-ATGCTAACGTTCGTCGATCG-[BHQ1]-3'", r.taqman.probeAllele2)
        assertEquals(2, r.log.size)
    }

    // ── 18: RFLP ──

    @Test
    fun `RFLP - 酶切位点被破坏（多态）`() {
        val r = CloningExt.analyzeRflp(wtSeq, mutSeq, "EcoRI", "GAATTC")
        assertEquals("EcoRI", r.enzymeUsed)
        assertArrayEquals(intArrayOf(13), r.cleavageInWt.toIntArray())
        assertTrue(r.cleavageInMut.isEmpty())
        assertArrayEquals(intArrayOf(31, 13), r.fragmentsWt.toIntArray())
        assertArrayEquals(intArrayOf(44), r.fragmentsMut.toIntArray())
        assertArrayEquals(intArrayOf(44, 31, 13), r.fragmentsHet.toIntArray())
        assertTrue(r.isDiscriminative)
    }

    @Test
    fun `RFLP - 切点数相同（无多态区分）`() {
        val r = CloningExt.analyzeRflp(wtSeq, mut2Seq, "EcoRI", "GAATTC")
        assertArrayEquals(intArrayOf(31, 13), r.fragmentsWt.toIntArray())
        assertArrayEquals(intArrayOf(35, 10), r.fragmentsMut.toIntArray())
        assertArrayEquals(intArrayOf(35, 31, 13, 10), r.fragmentsHet.toIntArray())
        assertTrue(!r.isDiscriminative)
    }

    // ── 19: Golden Gate fallback ──

    @Test
    fun `GG fallback - 预置三片段标准顺序拼装`() {
        val frags = listOf(
            "GCAGGGTCTCTTAAACGATGCTAGCGGAAATGGAGAGACCGCTG",
            "AGTCGGTCTCTATGGCGCAACGGCCCCGAGAGACCATGC",
            "TTACGGTCTCTCCCGCGTGCGTTTTAAAGAGACCGATC"
        )
        val r = CloningExt.simulateGoldenGateAssembly(frags, "BsaI")
        assertTrue(r.success)
        assertEquals("CGATGCTAGCGGAAACGCAACGGCCCGTGCGTTT", r.productSeq)
        assertArrayEquals(intArrayOf(0, 1, 2), r.sortedIndices.toIntArray())
        assertArrayEquals(arrayOf("TAAA", "ATGG", "CCCG"), r.overhangs.toTypedArray())
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertEquals(3, r.fragments.size)
        assertEquals(listOf(listOf(0, "TAAA", "TGGA", "CGATGCTAGCGGAAA"),
            listOf(1, "ATGG", "CCGA", "CGCAACGGCC"),
            listOf(2, "CCCG", "TAAA", "CGTGCGTTT")),
            r.fragments.map { listOf(it.index, it.leftOverhang, it.rightOverhang, it.insertSeq) })
    }

    @Test
    fun `GG fallback - BbsI 位点不配对报错`() {
        val r = CloningExt.simulateGoldenGateAssembly(listOf(
            "GGTCTCGAAAGAGACC", "GAAGACGGGCGTCTTC", "GAAGACCCCCGTCTTC"), "BbsI")
        assertTrue(!r.success)
        assertEquals(listOf("第 1 个片段未包含成对的 GAAGAC/GTCTTC 限制性识别位点"), r.errors)
    }

    @Test
    fun `GG fallback - 片段过短报错`() {
        val r = CloningExt.simulateGoldenGateAssembly(listOf("ACGTACGTACGT", "GGTCTCAGAGACC"), "BsaI")
        assertTrue(!r.success)
        assertEquals(listOf(
            "第 1 个片段长度过短，无法包含 Type IIS 识别位点",
            "第 2 个片段长度过短，无法包含 Type IIS 识别位点"), r.errors)
    }

    @Test
    fun `GG fallback - substring 交换语义（insert 起点大于终点）`() {
        val r = CloningExt.simulateGoldenGateAssembly(listOf(
            "GGTCTCAGAGACCTTTTT", "GGTCTCTAAAAGAGACCGGGG"), "BsaI")
        assertTrue(r.success)
        assertEquals(listOf(listOf(0, "GAGA", "CTCA", "CTCAGAGA"),
            listOf(1, "AAAA", "AAAA", "AAAA")),
            r.fragments.map { listOf(it.index, it.leftOverhang, it.rightOverhang, it.insertSeq) })
    }
}
