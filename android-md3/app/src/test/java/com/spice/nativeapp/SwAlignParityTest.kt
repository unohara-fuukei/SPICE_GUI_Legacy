package com.spice.nativeapp

import com.spice.nativeapp.core.SwAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T7-16 Rust cloning.rs 消息层 parity：ref_sw.json 由独立 cargo bin 直跑
 * bio 4.0.1 Aligner（逐字复刻 SPICE_main/src-tauri/src/cloning.rs 的
 * run_local_alignment / annotate_features_sw）生成；
 * 本测试断言 Kotlin SwAlign 输出与 rust 真身完全一致。
 */
class SwAlignParityTest {
    // ── annotate_features_sw（k=12 prefilter + SW + 阈值/边界）──

    @Test
    fun testAnnotateCase1() {
        val query = "ACGTTACGTTACGTTACGTTTTTAATACGACTCACTATAGGGCATGCATGCATGCATGCATGC"
        val e0 = SwAlign.SwEntry("T7 promoter", "TAATACGACTCACTATAGGG")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), null)
        assertEquals(0, hits.size)
    }

    @Test
    fun testAnnotateCase2() {
        val query = "GGGGCGCGCGCGTTGGGTAACACCGCCCCATGACGAAATAGCGGCGACACGGAAATGTTGAATACTCATTTTTACGTACGTACGTACGT"
        val e0 = SwAlign.SwEntry("AmpR", "ATGAGTATTCAACATTTCCGTGTCGCCGCTATTTCGTCATGGGGCGGTGTTACCCA")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), null)
        assertEquals(1, hits.size)
        assertEquals("AmpR", hits[0].name)
        assertEquals(13, hits[0].start)
        assertEquals(68, hits[0].end)
        assertEquals("reverse", hits[0].strand)
        assertEquals(112, hits[0].score)
    }

    @Test
    fun testAnnotateCase3() {
        val query = "TTTTTTTTTTTTTAATACGACCCACTATAGGGGGGGGGGGGGGG"
        val e0 = SwAlign.SwEntry("T7 promoter", "TAATACGACTCACTATAGGG")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), null)
        assertEquals(0, hits.size)
    }

    @Test
    fun testAnnotateCase4() {
        val query = "AAAAAACCCCCCGAATTCGAGCTCCGTCGAGCTTCTGCAGGGTACCGGATCCTCTAGAGGGGGGTTTTTT"
        val e0 = SwAlign.SwEntry("MCS", "GAATTCGAGCTCCGTCGACAAGCTTCTGCAGGGTACCGGATCCTCTAGA")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), null)
        assertEquals(1, hits.size)
        assertEquals("MCS", hits[0].name)
        assertEquals(12, hits[0].start)
        assertEquals(57, hits[0].end)
        assertEquals("forward", hits[0].strand)
        assertEquals(85, hits[0].score)
    }

    @Test
    fun testAnnotateCase5() {
        val query = "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT"
        val e0 = SwAlign.SwEntry("T7 promoter", "TAATACGACTCACTATAGGG")
        val e1 = SwAlign.SwEntry("AmpR", "ATGAGTATTCAACATTTCCGTGTCGCCGCTATTTCGTCATGGGGCGGTGTTACCCA")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0, e1), null)
        assertEquals(0, hits.size)
    }

    @Test
    fun testAnnotateCase6() {
        val query = "acgtnacgtnacgtacgtnactaatacgactcactatagggnnnnacgt"
        val e0 = SwAlign.SwEntry("T7 promoter", "TAATACGACTCACTATAGGG")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), null)
        assertEquals(0, hits.size)
    }

    @Test
    fun testAnnotateCase7() {
        val query = "TAATACGACTCACTATAGGGACGTACGTACGTACGTATGAGTATTCAACATTTCCGTGTCGCCGCTATTTCGTCATGGGGCGGTGTTACCCACGCGCGCGCGCGCGCGCGCGCGCGTCTAGAGGATCCGGTACCCTGCAGAAGCTTGTCGACGGAGCTCGAATTC"
        val e0 = SwAlign.SwEntry("T7 promoter", "TAATACGACTCACTATAGGG")
        val e1 = SwAlign.SwEntry("AmpR", "ATGAGTATTCAACATTTCCGTGTCGCCGCTATTTCGTCATGGGGCGGTGTTACCCA")
        val e2 = SwAlign.SwEntry("MCS", "GAATTCGAGCTCCGTCGACAAGCTTCTGCAGGGTACCGGATCCTCTAGA")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0, e1, e2), null)
        assertEquals(2, hits.size)
        assertEquals("AmpR", hits[0].name)
        assertEquals(36, hits[0].start)
        assertEquals(91, hits[0].end)
        assertEquals("forward", hits[0].strand)
        assertEquals(112, hits[0].score)
        assertEquals("MCS", hits[1].name)
        assertEquals(116, hits[1].start)
        assertEquals(164, hits[1].end)
        assertEquals("reverse", hits[1].strand)
        assertEquals(98, hits[1].score)
    }

    @Test
    fun testAnnotateCase8() {
        val query = "TTTTTTTTTTTTTTTTTTTTTAATACGACTCACCCCCCCCCCCCCCCCCCCCC"
        val e0 = SwAlign.SwEntry("short", "TAATACGA")
        val e1 = SwAlign.SwEntry("partial", "TAATACGACTCAC")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0, e1), null)
        assertEquals(0, hits.size)
    }

    @Test
    fun testAnnotateCase9() {
        val query = "TTTTTTTTTTTTTTTTTTTTTAATACGACTCACCCCCCCCCCCCCCCCCCCCC"
        val e0 = SwAlign.SwEntry("partial", "TAATACGACTCAC")
        val hits = SwAlign.annotateFeaturesSw(query, listOf(e0), 6)
        assertEquals(1, hits.size)
        assertEquals("partial", hits[0].name)
        assertEquals(20, hits[0].start)
        assertEquals(32, hits[0].end)
        assertEquals("forward", hits[0].strand)
        assertEquals(26, hits[0].score)
    }

    // ── run_local_alignment DP 字段（bio Local：score/starts/ends/operations）──

    @Test
    fun testLocalAlignCase1() {
        val al = SwAlign.localAlign("ACGTACGTACGT", "ACGTACGTACGT")
        assertEquals(24, al.score)
        assertEquals(0, al.queryStart)
        assertEquals(12, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(12, al.subjectEnd)
        assertEquals(12, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.MATCH, al.operations[4])
        assertEquals(SwAlign.Op.MATCH, al.operations[5])
        assertEquals(SwAlign.Op.MATCH, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.MATCH, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
        assertEquals(SwAlign.Op.MATCH, al.operations[10])
        assertEquals(SwAlign.Op.MATCH, al.operations[11])
    }

    @Test
    fun testLocalAlignCase2() {
        val al = SwAlign.localAlign("AAAAACCGTTGACGGCCAA", "CCGTCCGGCAAGGG")
        assertEquals(10, al.score)
        assertEquals(5, al.queryStart)
        assertEquals(18, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(11, al.subjectEnd)
        assertEquals(13, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.INS, al.operations[4])
        assertEquals(SwAlign.Op.INS, al.operations[5])
        assertEquals(SwAlign.Op.SUBST, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.MATCH, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
        assertEquals(SwAlign.Op.MATCH, al.operations[10])
        assertEquals(SwAlign.Op.SUBST, al.operations[11])
        assertEquals(SwAlign.Op.MATCH, al.operations[12])
    }

    @Test
    fun testLocalAlignCase3() {
        val al = SwAlign.localAlign("ACGTTTACGCGTTACGTA", "ACGTTTACGCGTTA")
        assertEquals(28, al.score)
        assertEquals(0, al.queryStart)
        assertEquals(14, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(14, al.subjectEnd)
        assertEquals(14, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.MATCH, al.operations[4])
        assertEquals(SwAlign.Op.MATCH, al.operations[5])
        assertEquals(SwAlign.Op.MATCH, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.MATCH, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
        assertEquals(SwAlign.Op.MATCH, al.operations[10])
        assertEquals(SwAlign.Op.MATCH, al.operations[11])
        assertEquals(SwAlign.Op.MATCH, al.operations[12])
        assertEquals(SwAlign.Op.MATCH, al.operations[13])
    }

    @Test
    fun testLocalAlignCase4() {
        val al = SwAlign.localAlign("AAAATTTTGGGGCCCC", "TTTTGGGGCCCCAAAA")
        assertEquals(24, al.score)
        assertEquals(4, al.queryStart)
        assertEquals(16, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(12, al.subjectEnd)
        assertEquals(12, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.MATCH, al.operations[4])
        assertEquals(SwAlign.Op.MATCH, al.operations[5])
        assertEquals(SwAlign.Op.MATCH, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.MATCH, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
        assertEquals(SwAlign.Op.MATCH, al.operations[10])
        assertEquals(SwAlign.Op.MATCH, al.operations[11])
    }

    @Test
    fun testLocalAlignCase5() {
        val al = SwAlign.localAlign("ACGT", "ACGT")
        assertEquals(8, al.score)
        assertEquals(0, al.queryStart)
        assertEquals(4, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(4, al.subjectEnd)
        assertEquals(4, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
    }

    @Test
    fun testLocalAlignCase6() {
        val al = SwAlign.localAlign("AANACGT", "ACGT")
        assertEquals(8, al.score)
        assertEquals(3, al.queryStart)
        assertEquals(7, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(4, al.subjectEnd)
        assertEquals(4, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
    }

    @Test
    fun testLocalAlignCase7() {
        val al = SwAlign.localAlign("TAATACGACTCACTATAGGG", "CCCTATAGTGAGTCGTATTA")
        assertEquals(14, al.score)
        assertEquals(10, al.queryStart)
        assertEquals(20, al.queryEnd)
        assertEquals(0, al.subjectStart)
        assertEquals(10, al.subjectEnd)
        assertEquals(10, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.SUBST, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.MATCH, al.operations[4])
        assertEquals(SwAlign.Op.MATCH, al.operations[5])
        assertEquals(SwAlign.Op.MATCH, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.SUBST, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
    }

    @Test
    fun testLocalAlignCase8() {
        val al = SwAlign.localAlign("AAAATAATACGACTCACTATAGGGTTTT", "CCTAATACGACTCACTATAGGGGG")
        assertEquals(40, al.score)
        assertEquals(4, al.queryStart)
        assertEquals(24, al.queryEnd)
        assertEquals(2, al.subjectStart)
        assertEquals(22, al.subjectEnd)
        assertEquals(20, al.operations.size)
        assertEquals(SwAlign.Op.MATCH, al.operations[0])
        assertEquals(SwAlign.Op.MATCH, al.operations[1])
        assertEquals(SwAlign.Op.MATCH, al.operations[2])
        assertEquals(SwAlign.Op.MATCH, al.operations[3])
        assertEquals(SwAlign.Op.MATCH, al.operations[4])
        assertEquals(SwAlign.Op.MATCH, al.operations[5])
        assertEquals(SwAlign.Op.MATCH, al.operations[6])
        assertEquals(SwAlign.Op.MATCH, al.operations[7])
        assertEquals(SwAlign.Op.MATCH, al.operations[8])
        assertEquals(SwAlign.Op.MATCH, al.operations[9])
        assertEquals(SwAlign.Op.MATCH, al.operations[10])
        assertEquals(SwAlign.Op.MATCH, al.operations[11])
        assertEquals(SwAlign.Op.MATCH, al.operations[12])
        assertEquals(SwAlign.Op.MATCH, al.operations[13])
        assertEquals(SwAlign.Op.MATCH, al.operations[14])
        assertEquals(SwAlign.Op.MATCH, al.operations[15])
        assertEquals(SwAlign.Op.MATCH, al.operations[16])
        assertEquals(SwAlign.Op.MATCH, al.operations[17])
        assertEquals(SwAlign.Op.MATCH, al.operations[18])
        assertEquals(SwAlign.Op.MATCH, al.operations[19])
    }
}
