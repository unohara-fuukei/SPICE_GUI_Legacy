package com.spice.nativeapp

import com.spice.nativeapp.core.NgsQc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #68 NgsGenomicsPanel parity：ref_ngs 由独立 cargo bin 逐字复刻
 * SPICE_main/src-tauri/src/cloning.rs 的 analyze_fastq_quality_rust / find_orfs_rust
 * （bio 4.0.1 fastq::Reader + seq_analysis::orf::Finder）在 8+13 个用例上生成；
 * 本测试断言 Kotlin NgsQc 输出与 rust 真身完全一致（f32 按 round-trip 字面量）。
 * 用例集：/tmp/ngsref/src/main.rs（LCG 确定性伪随机序列保证可复现）。
 */
class NgsQcParityTest {

    @Test
    fun fastq_f1_panel_default() {
        val r = NgsQc.analyzeFastqQualityRust(F_f1_panel_default)
        assertEquals(1, r.totalReads)
        assertEquals(33, r.totalBases)
        assertEquals(33, r.averageReadLength)
        assertEquals(51.52000045776367f, r.gcContentPercent, 0f)
        assertEquals(listOf(40.0f,0.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f), r.baseQualities)
        assertEquals(0.0f, r.duplicateReadsPercent, 0f)
    }

    @Test
    fun fastq_f2_multiline_dup_lowercase() {
        val r = NgsQc.analyzeFastqQualityRust(F_f2_multiline_dup_lowercase)
        assertEquals(3, r.totalReads)
        assertEquals(38, r.totalBases)
        assertEquals(12, r.averageReadLength)
        assertEquals(36.84000015258789f, r.gcContentPercent, 0f)
        assertEquals(listOf(26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,26.670000076293945f,40.0f,40.0f,40.0f,40.0f), r.baseQualities)
        assertEquals(0.0f, r.duplicateReadsPercent, 0f)
    }

    @Test
    fun fastq_f3_mismatch_skip() {
        val r = NgsQc.analyzeFastqQualityRust(F_f3_mismatch_skip)
        assertEquals(1, r.totalReads)
        assertEquals(10, r.totalBases)
        assertEquals(10, r.averageReadLength)
        assertEquals(100.0f, r.gcContentPercent, 0f)
        assertEquals(listOf(40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f), r.baseQualities)
        assertEquals(0.0f, r.duplicateReadsPercent, 0f)
    }

    @Test
    fun fastq_f6_crlf() {
        val r = NgsQc.analyzeFastqQualityRust(F_f6_crlf)
        assertEquals(1, r.totalReads)
        assertEquals(10, r.totalBases)
        assertEquals(10, r.averageReadLength)
        assertEquals(40.0f, r.gcContentPercent, 0f)
        assertEquals(listOf(40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f), r.baseQualities)
        assertEquals(0.0f, r.duplicateReadsPercent, 0f)
    }

    @Test
    fun fastq_f8_long_read_gt250() {
        val r = NgsQc.analyzeFastqQualityRust(F_f8_long_read_gt250)
        assertEquals(1, r.totalReads)
        assertEquals(300, r.totalBases)
        assertEquals(300, r.averageReadLength)
        assertEquals(52.66999816894531f, r.gcContentPercent, 0f)
        assertEquals(listOf(40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f,40.0f), r.baseQualities)
        assertEquals(0.0f, r.duplicateReadsPercent, 0f)
    }

    @Test fun fastq_f4_empty_err() {
        try { NgsQc.analyzeFastqQualityRust(""); fail() } catch (e: NgsQc.FastqException) {
            assertEquals("i18n:beErrFastqTextEmpty", e.message) } }

    @Test fun fastq_f5_missing_at_err() {
        try { NgsQc.analyzeFastqQualityRust(F_f5_missing_at); fail() } catch (e: NgsQc.FastqException) {
            assertEquals("i18n:beErrFastqParseFailed::expected '@' at record start", e.message) } }

    @Test fun fastq_f7_incomplete_err() {
        try { NgsQc.analyzeFastqQualityRust(F_f7_empty_seq_record); fail() } catch (e: NgsQc.FastqException) {
            assertEquals("i18n:beErrFastqParseFailed::Incomplete record. Each FastQ record has to consist of 4 lines: header, sequence, separator and qualities.", e.message) } }

    // main api.ts guarded：rust 报错 → TS analyzeFastqQuality 回退
    @Test fun fastq_fallback_empty() {
        val r = NgsQc.analyzeFastqQuality("")  // rust Err → TS 回退：零报告（TS 不报错）
        assertEquals(0, r.totalReads); assertEquals(0, r.totalBases)
        assertEquals(0, r.averageReadLength); assertEquals(0f, r.gcContentPercent, 0f)
        assertEquals(0f, r.duplicateReadsPercent, 0f)
        assertTrue(r.baseQualities.isEmpty()); assertTrue(r.adaptersDetected.isEmpty()) }

    @Test fun fastq_fallback_missing_at() {
        val r = NgsQc.analyzeFastqQuality(F_f5_missing_at)  // TS 重同步后 0 条
        assertEquals(0, r.totalReads); assertEquals(0f, r.duplicateReadsPercent, 0f) }

    @Test fun fastq_fallback_bad_record_keeps_good() {
        // TS 回退：脏记录（len 不匹配）跳过，好记录保留
        val r = NgsQc.analyzeFastqQuality(F_f3_mismatch_skip)
        assertEquals(1, r.totalReads); assertEquals(10, r.totalBases); assertEquals(10, r.averageReadLength)
        assertEquals(100f, r.gcContentPercent, 0f) }

    @Test
    fun orf_s10_lowercase() {
        val orfs = NgsQc.findOrfsRust(S_s10_lowercase, 90)
        assertEquals(1, orfs.size)
        assertEquals(listOf(0, 99, "+", 99), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
    }

    @Test
    fun orf_s11_trailing_start() {
        val orfs = NgsQc.findOrfsRust(S_s11_trailing_start, 90)
        assertEquals(0, orfs.size)
    }

    @Test
    fun orf_s12_lcg_3000() {
        val orfs = NgsQc.findOrfsRust(S_s12_lcg_3000, 90)
        assertEquals(69, orfs.size)
        assertEquals(listOf(2, 158, "+", 156), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals(listOf(458, 560, "+", 102), listOf(orfs[1].start, orfs[1].end, orfs[1].strand, orfs[1].length))
        assertEquals(listOf(360, 603, "+", 243), listOf(orfs[2].start, orfs[2].end, orfs[2].strand, orfs[2].length))
        assertEquals(listOf(402, 603, "+", 201), listOf(orfs[3].start, orfs[3].end, orfs[3].strand, orfs[3].length))
        assertEquals(listOf(453, 603, "+", 150), listOf(orfs[4].start, orfs[4].end, orfs[4].strand, orfs[4].length))
        assertEquals(listOf(456, 603, "+", 147), listOf(orfs[5].start, orfs[5].end, orfs[5].strand, orfs[5].length))
        assertEquals(listOf(462, 603, "+", 141), listOf(orfs[6].start, orfs[6].end, orfs[6].strand, orfs[6].length))
        assertEquals(listOf(477, 603, "+", 126), listOf(orfs[7].start, orfs[7].end, orfs[7].strand, orfs[7].length))
        assertEquals(listOf(492, 603, "+", 111), listOf(orfs[8].start, orfs[8].end, orfs[8].strand, orfs[8].length))
        assertEquals(listOf(532, 658, "+", 126), listOf(orfs[9].start, orfs[9].end, orfs[9].strand, orfs[9].length))
        assertEquals(listOf(535, 658, "+", 123), listOf(orfs[10].start, orfs[10].end, orfs[10].strand, orfs[10].length))
        assertEquals(listOf(866, 968, "+", 102), listOf(orfs[11].start, orfs[11].end, orfs[11].strand, orfs[11].length))
        assertEquals(listOf(1020, 1188, "+", 168), listOf(orfs[12].start, orfs[12].end, orfs[12].strand, orfs[12].length))
        assertEquals(listOf(1092, 1188, "+", 96), listOf(orfs[13].start, orfs[13].end, orfs[13].strand, orfs[13].length))
        assertEquals(listOf(1214, 1319, "+", 105), listOf(orfs[14].start, orfs[14].end, orfs[14].strand, orfs[14].length))
        assertEquals(listOf(1236, 1329, "+", 93), listOf(orfs[15].start, orfs[15].end, orfs[15].strand, orfs[15].length))
        assertEquals(listOf(1198, 1390, "+", 192), listOf(orfs[16].start, orfs[16].end, orfs[16].strand, orfs[16].length))
        assertEquals(listOf(1210, 1390, "+", 180), listOf(orfs[17].start, orfs[17].end, orfs[17].strand, orfs[17].length))
        assertEquals(listOf(1252, 1390, "+", 138), listOf(orfs[18].start, orfs[18].end, orfs[18].strand, orfs[18].length))
        assertEquals(listOf(1297, 1390, "+", 93), listOf(orfs[19].start, orfs[19].end, orfs[19].strand, orfs[19].length))
        assertEquals(listOf(1578, 1842, "+", 264), listOf(orfs[20].start, orfs[20].end, orfs[20].strand, orfs[20].length))
        assertEquals(listOf(1728, 1842, "+", 114), listOf(orfs[21].start, orfs[21].end, orfs[21].strand, orfs[21].length))
        assertEquals(listOf(1625, 1871, "+", 246), listOf(orfs[22].start, orfs[22].end, orfs[22].strand, orfs[22].length))
        assertEquals(listOf(1634, 1871, "+", 237), listOf(orfs[23].start, orfs[23].end, orfs[23].strand, orfs[23].length))
        assertEquals(listOf(1646, 1871, "+", 225), listOf(orfs[24].start, orfs[24].end, orfs[24].strand, orfs[24].length))
        assertEquals(listOf(1750, 1909, "+", 159), listOf(orfs[25].start, orfs[25].end, orfs[25].strand, orfs[25].length))
        assertEquals(listOf(1765, 1909, "+", 144), listOf(orfs[26].start, orfs[26].end, orfs[26].strand, orfs[26].length))
        assertEquals(listOf(1898, 2084, "+", 186), listOf(orfs[27].start, orfs[27].end, orfs[27].strand, orfs[27].length))
        assertEquals(listOf(1985, 2084, "+", 99), listOf(orfs[28].start, orfs[28].end, orfs[28].strand, orfs[28].length))
        assertEquals(listOf(2041, 2137, "+", 96), listOf(orfs[29].start, orfs[29].end, orfs[29].strand, orfs[29].length))
        assertEquals(listOf(2209, 2353, "+", 144), listOf(orfs[30].start, orfs[30].end, orfs[30].strand, orfs[30].length))
        assertEquals(listOf(2233, 2353, "+", 120), listOf(orfs[31].start, orfs[31].end, orfs[31].strand, orfs[31].length))
        assertEquals(listOf(2133, 2394, "+", 261), listOf(orfs[32].start, orfs[32].end, orfs[32].strand, orfs[32].length))
        assertEquals(listOf(2139, 2394, "+", 255), listOf(orfs[33].start, orfs[33].end, orfs[33].strand, orfs[33].length))
        assertEquals(listOf(2199, 2394, "+", 195), listOf(orfs[34].start, orfs[34].end, orfs[34].strand, orfs[34].length))
        assertEquals(listOf(2362, 2476, "+", 114), listOf(orfs[35].start, orfs[35].end, orfs[35].strand, orfs[35].length))
        assertEquals(listOf(2365, 2476, "+", 111), listOf(orfs[36].start, orfs[36].end, orfs[36].strand, orfs[36].length))
        assertEquals(listOf(2553, 2718, "+", 165), listOf(orfs[37].start, orfs[37].end, orfs[37].strand, orfs[37].length))
        assertEquals(listOf(2722, 2848, "+", 126), listOf(orfs[38].start, orfs[38].end, orfs[38].strand, orfs[38].length))
        assertEquals(listOf(2760, 2856, "+", 96), listOf(orfs[39].start, orfs[39].end, orfs[39].strand, orfs[39].length))
        assertEquals(listOf(2870, 2960, "+", 90), listOf(orfs[40].start, orfs[40].end, orfs[40].strand, orfs[40].length))
        assertEquals(listOf(2862, 2994, "+", 132), listOf(orfs[41].start, orfs[41].end, orfs[41].strand, orfs[41].length))
        assertEquals(listOf(2892, 2994, "+", 102), listOf(orfs[42].start, orfs[42].end, orfs[42].strand, orfs[42].length))
        assertEquals(listOf(2848, 2956, "-", 108), listOf(orfs[43].start, orfs[43].end, orfs[43].strand, orfs[43].length))
        assertEquals(listOf(2766, 2877, "-", 111), listOf(orfs[44].start, orfs[44].end, orfs[44].strand, orfs[44].length))
        assertEquals(listOf(2683, 2812, "-", 129), listOf(orfs[45].start, orfs[45].end, orfs[45].strand, orfs[45].length))
        assertEquals(listOf(2543, 2642, "-", 99), listOf(orfs[46].start, orfs[46].end, orfs[46].strand, orfs[46].length))
        assertEquals(listOf(2256, 2445, "-", 189), listOf(orfs[47].start, orfs[47].end, orfs[47].strand, orfs[47].length))
        assertEquals(listOf(2179, 2272, "-", 93), listOf(orfs[48].start, orfs[48].end, orfs[48].strand, orfs[48].length))
        assertEquals(listOf(1586, 1916, "-", 330), listOf(orfs[49].start, orfs[49].end, orfs[49].strand, orfs[49].length))
        assertEquals(listOf(1586, 1772, "-", 186), listOf(orfs[50].start, orfs[50].end, orfs[50].strand, orfs[50].length))
        assertEquals(listOf(1586, 1697, "-", 111), listOf(orfs[51].start, orfs[51].end, orfs[51].strand, orfs[51].length))
        assertEquals(listOf(1386, 1512, "-", 126), listOf(orfs[52].start, orfs[52].end, orfs[52].strand, orfs[52].length))
        assertEquals(listOf(1366, 1528, "-", 162), listOf(orfs[53].start, orfs[53].end, orfs[53].strand, orfs[53].length))
        assertEquals(listOf(654, 783, "-", 129), listOf(orfs[54].start, orfs[54].end, orfs[54].strand, orfs[54].length))
        assertEquals(listOf(654, 780, "-", 126), listOf(orfs[55].start, orfs[55].end, orfs[55].strand, orfs[55].length))
        assertEquals(listOf(654, 777, "-", 123), listOf(orfs[56].start, orfs[56].end, orfs[56].strand, orfs[56].length))
        assertEquals(listOf(654, 759, "-", 105), listOf(orfs[57].start, orfs[57].end, orfs[57].strand, orfs[57].length))
        assertEquals(listOf(549, 648, "-", 99), listOf(orfs[58].start, orfs[58].end, orfs[58].strand, orfs[58].length))
        assertEquals(listOf(260, 515, "-", 255), listOf(orfs[59].start, orfs[59].end, orfs[59].strand, orfs[59].length))
        assertEquals(listOf(260, 479, "-", 219), listOf(orfs[60].start, orfs[60].end, orfs[60].strand, orfs[60].length))
        assertEquals(listOf(260, 452, "-", 192), listOf(orfs[61].start, orfs[61].end, orfs[61].strand, orfs[61].length))
        assertEquals(listOf(260, 401, "-", 141), listOf(orfs[62].start, orfs[62].end, orfs[62].strand, orfs[62].length))
        assertEquals(listOf(260, 389, "-", 129), listOf(orfs[63].start, orfs[63].end, orfs[63].strand, orfs[63].length))
        assertEquals(listOf(260, 359, "-", 99), listOf(orfs[64].start, orfs[64].end, orfs[64].strand, orfs[64].length))
        assertEquals(listOf(159, 321, "-", 162), listOf(orfs[65].start, orfs[65].end, orfs[65].strand, orfs[65].length))
        assertEquals(listOf(159, 306, "-", 147), listOf(orfs[66].start, orfs[66].end, orfs[66].strand, orfs[66].length))
        assertEquals(listOf(159, 285, "-", 126), listOf(orfs[67].start, orfs[67].end, orfs[67].strand, orfs[67].length))
        assertEquals(listOf(159, 282, "-", 123), listOf(orfs[68].start, orfs[68].end, orfs[68].strand, orfs[68].length))
    }

    @Test
    fun orf_s13_minlen_300() {
        val orfs = NgsQc.findOrfsRust(S_s13_minlen_300, 300)
        assertEquals(7, orfs.size)
        assertEquals(listOf(1987, 2509, "-", 522), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals(listOf(1987, 2488, "-", 501), listOf(orfs[1].start, orfs[1].end, orfs[1].strand, orfs[1].length))
        assertEquals(listOf(1987, 2485, "-", 498), listOf(orfs[2].start, orfs[2].end, orfs[2].strand, orfs[2].length))
        assertEquals(listOf(1987, 2446, "-", 459), listOf(orfs[3].start, orfs[3].end, orfs[3].strand, orfs[3].length))
        assertEquals(listOf(1987, 2443, "-", 456), listOf(orfs[4].start, orfs[4].end, orfs[4].strand, orfs[4].length))
        assertEquals(listOf(1987, 2386, "-", 399), listOf(orfs[5].start, orfs[5].end, orfs[5].strand, orfs[5].length))
        assertEquals(listOf(1987, 2383, "-", 396), listOf(orfs[6].start, orfs[6].end, orfs[6].strand, orfs[6].length))
    }

    @Test
    fun orf_s1_nested_forward() {
        val orfs = NgsQc.findOrfsRust(S_s1_nested_forward, 90)
        assertEquals(2, orfs.size)
        assertEquals(listOf(0, 99, "+", 99), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals(listOf(3, 99, "+", 96), listOf(orfs[1].start, orfs[1].end, orfs[1].strand, orfs[1].length))
        assertEquals("ATGATGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATAA", orfs[0].seq)
        assertEquals("ATGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATAA", orfs[1].seq)
    }

    @Test
    fun orf_s2_gtg_start() {
        val orfs = NgsQc.findOrfsRust(S_s2_gtg_start, 90)
        assertEquals(1, orfs.size)
        assertEquals(listOf(3, 102, "+", 99), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
    }

    @Test
    fun orf_s3_ttg_start() {
        val orfs = NgsQc.findOrfsRust(S_s3_ttg_start, 90)
        assertEquals(1, orfs.size)
        assertEquals(listOf(3, 99, "+", 96), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
    }

    @Test
    fun orf_s4_reverse_only() {
        val orfs = NgsQc.findOrfsRust(S_s4_reverse_only, 90)
        assertEquals(1, orfs.size)
        assertEquals(listOf(0, 99, "-", 99), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals("ATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATAA", orfs[0].seq)
    }

    @Test
    fun orf_s5_too_short() {
        val orfs = NgsQc.findOrfsRust(S_s5_too_short, 90)
        assertEquals(0, orfs.size)
    }

    @Test
    fun orf_s6_both_strands_multi() {
        val orfs = NgsQc.findOrfsRust(S_s6_both_strands_multi, 90)
        assertEquals(20, orfs.size)
        assertEquals(listOf(3, 102, "+", 99), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals(listOf(105, 207, "+", 102), listOf(orfs[1].start, orfs[1].end, orfs[1].strand, orfs[1].length))
        assertEquals(listOf(108, 207, "+", 99), listOf(orfs[2].start, orfs[2].end, orfs[2].strand, orfs[2].length))
        assertEquals(listOf(211, 310, "+", 99), listOf(orfs[3].start, orfs[3].end, orfs[3].strand, orfs[3].length))
        assertEquals(listOf(477, 582, "+", 105), listOf(orfs[4].start, orfs[4].end, orfs[4].strand, orfs[4].length))
        assertEquals(listOf(480, 582, "+", 102), listOf(orfs[5].start, orfs[5].end, orfs[5].strand, orfs[5].length))
        assertEquals(listOf(483, 582, "+", 99), listOf(orfs[6].start, orfs[6].end, orfs[6].strand, orfs[6].length))
        assertEquals(listOf(486, 582, "+", 96), listOf(orfs[7].start, orfs[7].end, orfs[7].strand, orfs[7].length))
        assertEquals(listOf(489, 582, "+", 93), listOf(orfs[8].start, orfs[8].end, orfs[8].strand, orfs[8].length))
        assertEquals(listOf(492, 582, "+", 90), listOf(orfs[9].start, orfs[9].end, orfs[9].strand, orfs[9].length))
        assertEquals(listOf(578, 677, "-", 99), listOf(orfs[10].start, orfs[10].end, orfs[10].strand, orfs[10].length))
        assertEquals(listOf(473, 575, "-", 102), listOf(orfs[11].start, orfs[11].end, orfs[11].strand, orfs[11].length))
        assertEquals(listOf(473, 572, "-", 99), listOf(orfs[12].start, orfs[12].end, orfs[12].strand, orfs[12].length))
        assertEquals(listOf(370, 469, "-", 99), listOf(orfs[13].start, orfs[13].end, orfs[13].strand, orfs[13].length))
        assertEquals(listOf(98, 203, "-", 105), listOf(orfs[14].start, orfs[14].end, orfs[14].strand, orfs[14].length))
        assertEquals(listOf(98, 200, "-", 102), listOf(orfs[15].start, orfs[15].end, orfs[15].strand, orfs[15].length))
        assertEquals(listOf(98, 197, "-", 99), listOf(orfs[16].start, orfs[16].end, orfs[16].strand, orfs[16].length))
        assertEquals(listOf(98, 194, "-", 96), listOf(orfs[17].start, orfs[17].end, orfs[17].strand, orfs[17].length))
        assertEquals(listOf(98, 191, "-", 93), listOf(orfs[18].start, orfs[18].end, orfs[18].strand, orfs[18].length))
        assertEquals(listOf(98, 188, "-", 90), listOf(orfs[19].start, orfs[19].end, orfs[19].strand, orfs[19].length))
    }

    @Test
    fun orf_s7_exactly_90() {
        val orfs = NgsQc.findOrfsRust(S_s7_exactly_90, 90)
        assertEquals(1, orfs.size)
        assertEquals(listOf(0, 90, "+", 90), listOf(orfs[0].start, orfs[0].end, orfs[0].strand, orfs[0].length))
        assertEquals("ATGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGTAA", orfs[0].seq)
    }

    @Test
    fun orf_s8_exactly_89() {
        val orfs = NgsQc.findOrfsRust(S_s8_exactly_89, 90)
        assertEquals(0, orfs.size)
    }

    @Test
    fun orf_s9_empty() {
        try { NgsQc.findOrfsRust("", 90); fail() } catch (e: NgsQc.FastqException) {
            assertEquals("i18n:beErrDnaSequenceEmpty", e.message) }
    }

    @Test fun orf_guarded_empty_falls_back() {
        assertTrue(NgsQc.findOrfs("", 90).isEmpty()) }

    @Test fun orf_guarded_nonempty_uses_rust() {
        assertEquals(NgsQc.findOrfsRust(S_s7_exactly_90, 90), NgsQc.findOrfs(S_s7_exactly_90, 90)) }

    // genomics.ts autoIdentifyAndRemoveAdapters（纯 TS）1:1
    @Test fun trim_full_adapter() {
        // 完整接头命中（切点=20 ≥15 才真切除）
        val read = "A".repeat(20) + "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA"
        val qual = "I".repeat(read.length)
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(1, r.trimmedCount); assertEquals(20, r.trimmedSequences[0].length); assertEquals(20, r.trimmedQualityScores[0].length) }

    @Test fun trim_partial_3prime() {
        // 末尾 10bp 是 TruSeq R1 前缀（15..6 递减扫描命中，切点 16 ≥15）
        val read = "ACGTACGTACGTACGTAGATCGGAAG"
        val qual = "I".repeat(read.length)
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(1, r.trimmedCount); assertEquals(16, r.trimmedSequences[0].length) }

    @Test fun trim_no_match_kept() {
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf("ACGTACGTACGT"), listOf("IIIIIIIIIIII"))
        assertEquals(0, r.trimmedCount); assertEquals("ACGTACGTACGT", r.trimmedSequences[0]) }

    @Test fun trim_too_short_after_cut_kept() {
        // 切点 10 < 15 → 不切，保留原 read
        val read = "A".repeat(10) + "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA"
        val qual = "I".repeat(read.length)
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(0, r.trimmedCount); assertEquals(read, r.trimmedSequences[0]) }

    @Test fun trim_boundary_15() {
        val read = "A".repeat(15) + "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA"
        val qual = "I".repeat(read.length)
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(1, r.trimmedCount); assertEquals(15, r.trimmedSequences[0].length) }

    @Test fun trim_custom_adapter_only() {
        val read = "A".repeat(15) + "CTGTCTCTTATACACATCT"
        val qual = "I".repeat(read.length)
        val rDef = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(1, rDef.trimmedCount)  // Nextera 在默认表
        val rCustom = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual), "TTAACC")
        assertEquals(0, rCustom.trimmedCount)  // 自定义表替换默认表，不命中
        val rCustom2 = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual), "CTGTCTCTTATACACATCT")
        assertEquals(1, rCustom2.trimmedCount); assertEquals(15, rCustom2.trimmedSequences[0].length) }

    @Test fun trim_log_line() {
        val read = "A".repeat(15) + "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA"
        val qual = "I".repeat(read.length)
        val r = NgsQc.autoIdentifyAndRemoveAdapters(listOf(read), listOf(qual))
        assertEquals(1, r.log.size)
        assertEquals("[剪切报告] 扫描了 1 条 reads，共成功切除 3' 接头 1 条，去接头率 100.0%", r.log[0]) }

    companion object {
        // 用例输入与 /tmp/ngsref/src/main.rs 完全一致
        private val F_f1_panel_default = "@SEQ_ID\nGATCGGAAGAGCACACGTCTGAACTCCAGTCAC\n+\n!''*((((***+))%%%++)(%%%%).1***-\n@SEQ_ID2\nAGATCGGAAGAGCACACGTCTGAACTCCAGTCA\n+\nI!IIIIIIIIIIIIIIIIIIIIIIIIIIIIIII"
        private val F_f2_multiline_dup_lowercase = "@r1 desc here\nACGTACGTAC\nGGTT\n+\nIIII\nIIIIIIIIII\n@r1 dup\nacgtacgtac\nggtt\n+\nIIII\nIIIIIIIIII\n@r3\nAAAAAAAAAA\n+\n!!!!!!!!!!\n"
        private val F_f3_mismatch_skip = "@bad\nACGTACGTAC\n+\nIIII\n@good\nGGGGCCGGGG\n+\nIIIIIIIIII\n"
        private val F_f5_missing_at = "no at sign here\nACGT\n+\nIIII\n"
        private val F_f6_crlf = "@win\r\nACGTACGTAA\r\n+\r\nIIIIIIIIII\r\n"
        private val F_f7_empty_seq_record = "@short\n\n+\n\n"
        private val F_f8_long_read_gt250 = "@long\n" + lcg(300, 7) + "\n+\n" + "I".repeat(300) + "\n"
        private val S_s1_nested_forward = "ATGATG" + "AAA".repeat(30) + "TAAGG"
        private val S_s2_gtg_start = "CCAGTG" + "CCG".repeat(31) + "TAA"
        private val S_s3_ttg_start = "GGTTTG" + "ACG".repeat(30) + "TAGA"
        private val S_s4_reverse_only = revcomp("ATG" + "GCA".repeat(31) + "TAA")
        private val S_s5_too_short = "ATGAAATAA"
        private val S_s6_both_strands_multi = run { val f = "GGGATG" + "CCC".repeat(31) + "TAAGGGTTGGTG" + "AAC".repeat(31) + "TAGGGCCATG" + "GGT".repeat(31) + "TGA" + "TTT".repeat(10); f + revcomp(f) }
        private val S_s7_exactly_90 = "ATG" + "ACG".repeat(28) + "TAA"
        private val S_s8_exactly_89 = "ATGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGACGTAA"
        private val S_s10_lowercase = "atg" + "acg".repeat(31) + "taa"
        private val S_s11_trailing_start = "TTT".repeat(40) + "ATGAA"
        private val S_s12_lcg_3000 = lcg(3000, 42)
        private val S_s13_minlen_300 = lcg(3000, 99)

        private fun lcg(n: Int, seed: Long): String {
            var s = seed
            val bases = charArrayOf('A', 'C', 'G', 'T')
            val sb = StringBuilder(n)
            repeat(n) {
                s = s * 6364136223846793005L + 1442695040888963407L
                sb.append(bases[((s ushr 33) % 4).toInt()])
            }
            return sb.toString()
        }

        private fun revcomp(seq: String): String = seq.reversed().map {
            when (it) { 'A' -> 'T'; 'C' -> 'G'; 'G' -> 'C'; 'T' -> 'A'; else -> it }
        }.joinToString("")
    }
}
