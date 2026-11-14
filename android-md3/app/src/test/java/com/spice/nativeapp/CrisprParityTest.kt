package com.spice.nativeapp

import com.spice.nativeapp.core.CasType
import com.spice.nativeapp.core.calculateBaseEditingWindow
import com.spice.nativeapp.core.designHdrDonor
import com.spice.nativeapp.core.designPegRna
import com.spice.nativeapp.core.evaluateGrna
import com.spice.nativeapp.core.findGrnaCandidates
import com.spice.nativeapp.core.predictOffTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrisprDesigner 算法层 parity 测试（T7 块 5）—
 * 参考值由原版 SPICE_main tsx inline src/lib/genome/crispr.ts 生成：
 * /tmp/cd_gen.ts → /tmp/cd_ref.json（本文件内嵌快照）。
 *
 * 覆盖：4 种 Cas PAM 扫描（含 SpCas9 PAM "TAG" 命中——正则只约束后 2 位 NG）/
 * Doench 近似评分（GC/PolyT/位置偏好/发卡）/ CFD 脱靶（0/1/2/4 错配 + 权重乘积 +
 * HALF_UP 4 位舍入 + 降序）/ HDR Donor（同源臂 + PAM 同义回避 alts[0] quirk）/
 * pegRNA（PBS/RTT 反向互补 + Wallace Tm + scaffold 拼接）/ 碱基编辑窗口（4-8 位）。
 *
 * 【关键 quirk】main PAM_DATABASE 正则带 g 标志且用 .test()——lastIndex 状态跨调用
 * 持久（模块级单例）。因此所有扫描调用必须按 cd_gen.ts 的脚本顺序执行：
 * scanNoRef → scanSelf → scanVqr → scanSa → scanCas12 → scanSelf2 → empty。
 * 本测试用单个 @Test 方法按序执行以保证状态一致（JVM 不保证方法间顺序）。
 */
class CrisprParityTest {

    private data class G(
        val id: String, val sequence: String, val fullTargetWithPam: String, val pam: String,
        val strand: String, val startIndex: Int, val endIndex: Int, val cutIndex: Int,
        val efficiency: Int, val specificity: Int, val gcContent: Int,
        val polyT: Boolean, val hairpin: Boolean, val finalScore: Int
    )

    private data class T3(val id: String, val specificity: Int, val finalScore: Int)

    private data class SM(val position: Int, val original: String, val mutated: String, val codonChange: String, val reason: String)

    private data class BEC(val index: Int, val originalBase: String, val targetBase: String, val windowPosition: Int, val efficiency: Int)

    private data class OT(val sequence: String, val mismatches: Int, val mismatchPositions: List<Int>, val cfdScore: Double, val location: String, val strand: String)

    private val seqA = "ATGGTGTCTAAGGGCGAAGGCTAGCTAGGGATCGGATCGGATCCGGGATTTTCAGGATCGATCGGATCCGGATCTTTCAGGGCTACATGGCTTGGCTAGGCTAGCTAGCTAGCTAGGATC"
    private val seqB = "ATATATATATATATATATATATATATATATATATATATATATATATATATATATATATAT"

    private val expNoRef = listOf(
            G("gRNA_+_1", "TGGTGTCTAAGGGCGAAGGC", "TGGTGTCTAAGGGCGAAGGCTAG", "TAG", "+", 1, 21, 18, 76, 100, 60, false, false, 81),
            G("gRNA_-_46", "TCCGATCGATCCTGAAAATC", "TCCGATCGATCCTGAAAATCCCG", "CCG", "-", 46, 66, 49, 76, 100, 45, false, false, 81),
            G("gRNA_-_44", "CGATCGATCCTGAAAATCCC", "CGATCGATCCTGAAAATCCCGGA", "GGA", "-", 44, 64, 47, 76, 100, 50, false, false, 81),
            G("gRNA_+_7", "CTAAGGGCGAAGGCTAGCTA", "CTAAGGGCGAAGGCTAGCTAGGG", "GGG", "+", 7, 27, 24, 71, 100, 55, false, false, 77),
            G("gRNA_+_76", "TCAGGGCTACATGGCTTGGC", "TCAGGGCTACATGGCTTGGCTAG", "TAG", "+", 76, 96, 93, 70, 100, 60, false, false, 76),
            G("gRNA_-_69", "CATGTAGCCCTGAAAGATCC", "CATGTAGCCCTGAAAGATCCGGA", "GGA", "-", 69, 89, 72, 70, 100, 50, false, false, 76),
            G("gRNA_+_16", "AAGGCTAGCTAGGGATCGGA", "AAGGCTAGCTAGGGATCGGATCG", "TCG", "+", 16, 36, 33, 65, 100, 55, false, false, 72),
            G("gRNA_+_67", "CCGGATCTTTCAGGGCTACA", "CCGGATCTTTCAGGGCTACATGG", "TGG", "+", 67, 87, 84, 65, 100, 55, false, false, 72),
            G("gRNA_+_78", "AGGGCTACATGGCTTGGCTA", "AGGGCTACATGGCTTGGCTAGGC", "GGC", "+", 78, 98, 95, 65, 100, 55, false, false, 72),
            G("gRNA_-_61", "CCTGAAAGATCCGGATCCGA", "CCTGAAAGATCCGGATCCGATCG", "TCG", "-", 61, 81, 64, 65, 100, 55, false, false, 72),
            G("gRNA_+_5", "GTCTAAGGGCGAAGGCTAGC", "GTCTAAGGGCGAAGGCTAGCTAG", "TAG", "+", 5, 25, 22, 62, 100, 60, false, false, 70),
            G("gRNA_+_13", "GCGAAGGCTAGCTAGGGATC", "GCGAAGGCTAGCTAGGGATCGGA", "GGA", "+", 13, 33, 30, 62, 100, 60, false, false, 70),
            G("gRNA_+_18", "GGCTAGCTAGGGATCGGATC", "GGCTAGCTAGGGATCGGATCGGA", "GGA", "+", 18, 38, 35, 62, 100, 60, false, false, 70),
            G("gRNA_+_81", "GCTACATGGCTTGGCTAGGC", "GCTACATGGCTTGGCTAGGCTAG", "TAG", "+", 81, 101, 98, 62, 100, 60, false, false, 70),
            G("gRNA_-_76", "GCCAAGCCATGTAGCCCTGA", "GCCAAGCCATGTAGCCCTGAAAG", "AAG", "-", 76, 96, 79, 57, 100, 60, false, false, 66),
            G("gRNA_-_17", "ATCCGATCCCTAGCTAGCCT", "ATCCGATCCCTAGCTAGCCTTCG", "TCG", "-", 17, 37, 20, 56, 100, 55, false, false, 65),
            G("gRNA_+_11", "GGGCGAAGGCTAGCTAGGGA", "GGGCGAAGGCTAGCTAGGGATCG", "TCG", "+", 11, 31, 28, 53, 100, 65, false, false, 62),
            G("gRNA_+_24", "CTAGGGATCGGATCGGATCC", "CTAGGGATCGGATCGGATCCGGG", "GGG", "+", 24, 44, 41, 52, 100, 60, false, true, 62),
            G("gRNA_+_49", "TTTCAGGATCGATCGGATCC", "TTTCAGGATCGATCGGATCCGGA", "GGA", "+", 49, 69, 66, 52, 100, 50, false, true, 62),
            G("gRNA_+_85", "CATGGCTTGGCTAGGCTAGC", "CATGGCTTGGCTAGGCTAGCTAG", "TAG", "+", 85, 105, 102, 52, 100, 60, false, true, 62),
            G("gRNA_+_95", "CTAGGCTAGCTAGCTAGCTA", "CTAGGCTAGCTAGCTAGCTAGGA", "GGA", "+", 95, 115, 112, 53, 100, 50, false, true, 62),
            G("gRNA_-_98", "TCCTAGCTAGCTAGCTAGCC", "TCCTAGCTAGCTAGCTAGCCTAG", "TAG", "-", 98, 118, 101, 52, 100, 55, false, true, 62),
            G("gRNA_-_85", "GCTAGCCTAGCCAAGCCATG", "GCTAGCCTAGCCAAGCCATGTAG", "TAG", "-", 85, 105, 88, 51, 100, 60, false, true, 61),
            G("gRNA_+_22", "AGCTAGGGATCGGATCGGAT", "AGCTAGGGATCGGATCGGATCCG", "CCG", "+", 22, 42, 39, 50, 100, 55, false, false, 60),
            G("gRNA_+_72", "TCTTTCAGGGCTACATGGCT", "TCTTTCAGGGCTACATGGCTTGG", "TGG", "+", 72, 92, 89, 50, 100, 50, false, false, 60),
            G("gRNA_-_65", "TAGCCCTGAAAGATCCGGAT", "TAGCCCTGAAAGATCCGGATCCG", "CCG", "-", 65, 85, 68, 50, 100, 50, false, false, 60),
            G("gRNA_-_35", "CTGAAAATCCCGGATCCGAT", "CTGAAAATCCCGGATCCGATCCG", "CCG", "-", 35, 55, 38, 50, 100, 50, false, false, 60),
            G("gRNA_-_71", "GCCATGTAGCCCTGAAAGAT", "GCCATGTAGCCCTGAAAGATCCG", "CCG", "-", 71, 91, 74, 48, 100, 50, false, false, 58),
            G("gRNA_-_27", "CCCGGATCCGATCCGATCCC", "CCCGGATCCGATCCGATCCCTAG", "TAG", "-", 27, 47, 30, 48, 100, 70, false, true, 58),
            G("gRNA_+_89", "GCTTGGCTAGGCTAGCTAGC", "GCTTGGCTAGGCTAGCTAGCTAG", "TAG", "+", 89, 109, 106, 44, 100, 60, false, true, 55),
            G("gRNA_+_93", "GGCTAGGCTAGCTAGCTAGC", "GGCTAGGCTAGCTAGCTAGCTAG", "TAG", "+", 93, 113, 110, 44, 100, 60, false, true, 55),
            G("gRNA_-_93", "GCTAGCTAGCTAGCCTAGCC", "GCTAGCTAGCTAGCCTAGCCAAG", "AAG", "-", 93, 113, 96, 44, 100, 60, false, true, 55),
            G("gRNA_-_23", "GATCCGATCCGATCCCTAGC", "GATCCGATCCGATCCCTAGCTAG", "TAG", "-", 23, 43, 26, 44, 100, 60, false, true, 55),
            G("gRNA_+_41", "TCCGGGATTTTCAGGATCGA", "TCCGGGATTTTCAGGATCGATCG", "TCG", "+", 41, 61, 58, 41, 100, 50, true, false, 53),
            G("gRNA_+_43", "CGGGATTTTCAGGATCGATC", "CGGGATTTTCAGGATCGATCGGA", "GGA", "+", 43, 63, 60, 40, 100, 50, true, false, 52),
            G("gRNA_-_10", "CCCTAGCTAGCCTTCGCCCT", "CCCTAGCTAGCCTTCGCCCTTAG", "TAG", "-", 10, 30, 13, 40, 100, 65, false, false, 52),
            G("gRNA_+_59", "GATCGGATCCGGATCTTTCA", "GATCGGATCCGGATCTTTCAGGG", "GGG", "+", 59, 79, 76, 39, 100, 50, false, true, 51),
            G("gRNA_+_37", "CGGATCCGGGATTTTCAGGA", "CGGATCCGGGATTTTCAGGATCG", "TCG", "+", 37, 57, 54, 35, 100, 55, true, false, 48),
            G("gRNA_+_57", "TCGATCGGATCCGGATCTTT", "TCGATCGGATCCGGATCTTTCAG", "CAG", "+", 57, 77, 74, 32, 100, 50, false, true, 46),
            G("gRNA_-_40", "CGATCCTGAAAATCCCGGAT", "CGATCCTGAAAATCCCGGATCCG", "CCG", "-", 40, 60, 43, 32, 100, 50, false, true, 46),
            G("gRNA_+_34", "GATCGGATCCGGGATTTTCA", "GATCGGATCCGGGATTTTCAGGA", "GGA", "+", 34, 54, 51, 27, 100, 50, true, false, 42),
            G("gRNA_+_47", "ATTTTCAGGATCGATCGGAT", "ATTTTCAGGATCGATCGGATCCG", "CCG", "+", 47, 67, 64, 20, 100, 40, true, false, 36),
            G("gRNA_+_32", "CGGATCGGATCCGGGATTTT", "CGGATCGGATCCGGGATTTTCAG", "CAG", "+", 32, 52, 49, 8, 100, 55, true, true, 26),
    )
    private val expSelf = listOf(
            T3("gRNA_+_1", 100, 88),
            T3("gRNA_-_46", 100, 88),
            T3("gRNA_-_44", 100, 88),
            T3("gRNA_+_7", 100, 86),
            T3("gRNA_+_76", 100, 85),
            T3("gRNA_-_69", 100, 85),
            T3("gRNA_+_16", 100, 83),
            T3("gRNA_+_67", 100, 83),
            T3("gRNA_+_78", 100, 83),
            T3("gRNA_-_61", 100, 83),
            T3("gRNA_+_5", 100, 81),
            T3("gRNA_+_13", 100, 81),
            T3("gRNA_+_18", 100, 81),
            T3("gRNA_+_81", 100, 81),
            T3("gRNA_-_76", 100, 79),
            T3("gRNA_-_17", 100, 78),
            T3("gRNA_+_11", 100, 77),
            T3("gRNA_+_95", 100, 77),
            T3("gRNA_+_24", 100, 76),
            T3("gRNA_+_49", 100, 76),
            T3("gRNA_+_85", 100, 76),
            T3("gRNA_-_98", 100, 76),
            T3("gRNA_-_85", 100, 76),
            T3("gRNA_+_22", 100, 75),
            T3("gRNA_+_72", 100, 75),
            T3("gRNA_-_65", 100, 75),
            T3("gRNA_-_35", 100, 75),
            T3("gRNA_-_71", 100, 74),
            T3("gRNA_-_27", 100, 74),
            T3("gRNA_+_89", 100, 72),
            T3("gRNA_+_93", 100, 72),
            T3("gRNA_-_93", 100, 72),
            T3("gRNA_-_23", 100, 72),
            T3("gRNA_+_41", 100, 71),
            T3("gRNA_+_43", 100, 70),
            T3("gRNA_+_59", 100, 70),
            T3("gRNA_-_10", 100, 70),
            T3("gRNA_+_37", 100, 68),
            T3("gRNA_+_57", 100, 66),
            T3("gRNA_-_40", 100, 66),
            T3("gRNA_+_34", 99, 63),
            T3("gRNA_+_47", 100, 60),
            T3("gRNA_+_32", 100, 54),
    )
    private val expVqr = listOf(
            T3("gRNA_+_9", 100, 89),
            T3("gRNA_+_60", 100, 89),
            T3("gRNA_-_77", 100, 89),
            T3("gRNA_+_1", 100, 88),
            T3("gRNA_-_79", 100, 88),
            T3("gRNA_-_46", 100, 88),
            T3("gRNA_-_44", 100, 88),
            T3("gRNA_+_7", 100, 86),
            T3("gRNA_-_4", 100, 86),
            T3("gRNA_+_76", 100, 85),
            T3("gRNA_-_69", 100, 85),
            T3("gRNA_+_16", 100, 83),
            T3("gRNA_+_67", 100, 83),
            T3("gRNA_+_78", 100, 83),
            T3("gRNA_-_75", 100, 83),
            T3("gRNA_-_61", 100, 83),
            T3("gRNA_-_8", 100, 83),
            T3("gRNA_-_6", 100, 83),
            T3("gRNA_+_5", 100, 81),
            T3("gRNA_+_13", 100, 81),
            T3("gRNA_+_18", 100, 81),
            T3("gRNA_+_81", 100, 81),
            T3("gRNA_-_63", 100, 81),
            T3("gRNA_-_95", 100, 80),
            T3("gRNA_-_90", 100, 80),
            T3("gRNA_-_52", 100, 80),
            T3("gRNA_-_17", 100, 78),
            T3("gRNA_+_11", 100, 77),
            T3("gRNA_+_95", 100, 77),
            T3("gRNA_+_24", 100, 76),
            T3("gRNA_+_49", 100, 76),
            T3("gRNA_+_58", 100, 76),
            T3("gRNA_+_85", 100, 76),
            T3("gRNA_-_98", 100, 76),
            T3("gRNA_-_85", 100, 76),
            T3("gRNA_-_59", 100, 76),
            T3("gRNA_-_38", 100, 76),
            T3("gRNA_+_22", 100, 75),
            T3("gRNA_+_26", 100, 75),
            T3("gRNA_+_72", 100, 75),
            T3("gRNA_-_65", 100, 75),
            T3("gRNA_-_35", 100, 75),
            T3("gRNA_+_35", 100, 74),
            T3("gRNA_-_71", 100, 74),
            T3("gRNA_-_50", 100, 74),
            T3("gRNA_-_27", 100, 74),
            T3("gRNA_+_89", 100, 72),
            T3("gRNA_+_93", 100, 72),
            T3("gRNA_-_93", 100, 72),
            T3("gRNA_-_54", 100, 72),
            T3("gRNA_-_33", 100, 72),
            T3("gRNA_-_23", 100, 72),
            T3("gRNA_+_41", 100, 71),
            T3("gRNA_+_64", 100, 71),
            T3("gRNA_+_43", 100, 70),
            T3("gRNA_-_10", 100, 70),
            T3("gRNA_+_56", 100, 69),
            T3("gRNA_+_37", 100, 68),
            T3("gRNA_+_31", 100, 66),
            T3("gRNA_-_40", 100, 66),
            T3("gRNA_+_47", 100, 60),
            T3("gRNA_+_33", 100, 57),
            T3("gRNA_+_39", 100, 57),
    )
    private val expSelf2 = listOf(
            T3("gRNA_+_1", 100, 88),
            T3("gRNA_-_46", 100, 88),
            T3("gRNA_-_44", 100, 88),
            T3("gRNA_+_7", 100, 86),
            T3("gRNA_+_76", 100, 85),
            T3("gRNA_-_69", 100, 85),
            T3("gRNA_+_16", 100, 83),
            T3("gRNA_+_67", 100, 83),
            T3("gRNA_+_78", 100, 83),
            T3("gRNA_-_61", 100, 83),
            T3("gRNA_+_5", 100, 81),
            T3("gRNA_+_13", 100, 81),
            T3("gRNA_+_18", 100, 81),
            T3("gRNA_+_81", 100, 81),
            T3("gRNA_-_76", 100, 79),
            T3("gRNA_-_17", 100, 78),
            T3("gRNA_+_11", 100, 77),
            T3("gRNA_+_95", 100, 77),
            T3("gRNA_+_24", 100, 76),
            T3("gRNA_+_49", 100, 76),
            T3("gRNA_+_85", 100, 76),
            T3("gRNA_-_98", 100, 76),
            T3("gRNA_-_85", 100, 76),
            T3("gRNA_+_22", 100, 75),
            T3("gRNA_+_72", 100, 75),
            T3("gRNA_-_65", 100, 75),
            T3("gRNA_-_35", 100, 75),
            T3("gRNA_-_71", 100, 74),
            T3("gRNA_-_27", 100, 74),
            T3("gRNA_+_89", 100, 72),
            T3("gRNA_+_93", 100, 72),
            T3("gRNA_-_93", 100, 72),
            T3("gRNA_-_23", 100, 72),
            T3("gRNA_+_41", 100, 71),
            T3("gRNA_+_43", 100, 70),
            T3("gRNA_+_59", 100, 70),
            T3("gRNA_-_10", 100, 70),
            T3("gRNA_+_37", 100, 68),
            T3("gRNA_+_57", 100, 66),
            T3("gRNA_-_40", 100, 66),
            T3("gRNA_+_34", 99, 63),
            T3("gRNA_+_47", 100, 60),
            T3("gRNA_+_32", 100, 54),
    )
    private val expCas12 = listOf(
            G("gRNA_+_78", "AGGGCTACATGGCTTGGCTAGGC", "TTTCAGGGCTACATGGCTTGGCTAGGC", "TTTC", "+", 78, 101, 96, 55, 100, 61, false, false, 78),
            G("gRNA_+_53", "AGGATCGATCGGATCCGGATCTT", "TTTCAGGATCGATCGGATCCGGATCTT", "TTTC", "+", 53, 76, 71, 32, 100, 52, false, true, 66),
    )

    private fun assertGrna(e: G, a: com.spice.nativeapp.core.GrnaCandidate) {
        assertEquals(e.id, a.id)
        assertEquals(e.sequence, a.sequence)
        assertEquals(e.fullTargetWithPam, a.fullTargetWithPam)
        assertEquals(e.pam, a.pam)
        assertEquals(e.strand, a.strand)
        assertEquals(e.startIndex, a.startIndex)
        assertEquals(e.endIndex, a.endIndex)
        assertEquals(e.cutIndex, a.cutIndex)
        assertEquals(e.efficiency, a.scores.efficiency)
        assertEquals(e.specificity, a.scores.specificity)
        assertEquals(e.gcContent, a.scores.gcContent)
        assertEquals(e.polyT, a.scores.polyT)
        assertEquals(e.hairpin, a.scores.hairpin)
        assertEquals(e.finalScore, a.scores.finalScore)
    }

    /** 单方法按序执行全部扫描场景——PAM_DATABASE 单例的 lastIndex 状态跨调用延续（与 main 模块级 RegExp 一致）。 */
    @Test
    fun `crispr 全链路 parity（按 cd_gen 顺序）`() {
        // 1. scanNoRef（SpCas9 无参考）
        val scanNoRef = findGrnaCandidates(seqA, CasType.SpCas9)
        assertEquals(expNoRef.size, scanNoRef.size)
        expNoRef.zip(scanNoRef).forEach { (e, a) -> assertGrna(e, a) }

        // 2. scanSelf（SpCas9 以自身作参考：specificity/finalScore 重算）
        val scanSelf = findGrnaCandidates(seqA, CasType.SpCas9, seqA)
        assertEquals(expSelf.size, scanSelf.size)
        expSelf.zip(scanSelf).forEach { (e, a) ->
            assertEquals(e.id, a.id)
            assertEquals(e.specificity, a.scores.specificity)
            assertEquals(e.finalScore, a.scores.finalScore)
        }

        // 3. scanVqr
        val scanVqr = findGrnaCandidates(seqA, CasType.SpCas9_VQR, seqA)
        assertEquals(expVqr.size, scanVqr.size)
        expVqr.zip(scanVqr).forEach { (e, a) ->
            assertEquals(e.id, a.id)
            assertEquals(e.specificity, a.scores.specificity)
            assertEquals(e.finalScore, a.scores.finalScore)
        }

        // 4. scanSa（NNGRRT：本夹具 0 命中）
        val scanSa = findGrnaCandidates(seqA, CasType.SaCas9, seqA)
        assertEquals(0, scanSa.size)

        // 5. scanCas12（5prime PAM：全字段）
        val scanCas12 = findGrnaCandidates(seqA, CasType.Cas12a, seqA)
        assertEquals(expCas12.size, scanCas12.size)
        expCas12.zip(scanCas12).forEach { (e, a) -> assertGrna(e, a) }

        // 6. scanSelf2（同 2——正则状态已被 VQR/Sa/Cas12 使用后再回 SpCas9）
        val scanSelf2 = findGrnaCandidates(seqA, CasType.SpCas9, seqA)
        assertEquals(expSelf2.size, scanSelf2.size)
        expSelf2.zip(scanSelf2).forEach { (e, a) ->
            assertEquals(e.id, a.id)
            assertEquals(e.specificity, a.scores.specificity)
            assertEquals(e.finalScore, a.scores.finalScore)
        }

        // 7. empty（poly-A 模板 0 命中）
        assertEquals(0, findGrnaCandidates(seqB, CasType.SpCas9, seqB).size)
    }

    @Test
    fun `evaluateGrna 评分 parity`() {
        val ev1 = evaluateGrna("GGGGTTTTGGGGCCCCCCAAAA")
        assertEquals(1, ev1.efficiency)
        assertEquals(64, ev1.gcContent)
        assertEquals(true, ev1.polyT)
        assertEquals(true, ev1.hairpin)

        val ev2 = evaluateGrna("ACGTACGTACGTACGTACGT")
        assertEquals(32, ev2.efficiency)
        assertEquals(50, ev2.gcContent)
        assertEquals(false, ev2.polyT)
        assertEquals(true, ev2.hairpin)

        val ev3 = evaluateGrna("GACCTGCTAGCTAGCTAGCTA")
        assertEquals(24, ev3.efficiency)
        assertEquals(52, ev3.gcContent)
        assertEquals(false, ev3.polyT)
        assertEquals(true, ev3.hairpin)
    }

    @Test
    fun `predictOffTargets CFD parity`() {
        val X = "ACGTACGTACGTACGTACGT"
        val X1 = X.slice(0..4) + "T" + X.slice(6..19)
        val X2 = X.slice(0..11) + "C" + X.slice(13..14) + "C" + X.slice(16..19)
        val X4 = "T" + X.slice(1..4) + "T" + X.slice(6..8) + "T" + X.slice(10..12) + "T" + X.slice(14..19)
        val X5 = "TTTTT" + X.slice(5..7) + "TT" + X.slice(10..12) + "TT" + X.slice(15..17) + "T" + X.slice(19..19)
        val refO = X + "AGG" + X1 + "AGG" + X2 + "AGG" + X4 + "AGG" + X5 + "AGG"
        val otC = predictOffTargets(X, refO)
        val exp = listOf(
            OT("ACGTACGTACGTACGTACGT", 0, listOf<Int>(), 1.0, "RefIndex:0", "+"),
            OT("ACGTATGTACGTACGTACGT", 1, listOf(5), 0.8, "RefIndex:23", "+"),
            OT("TCGTATGTATGTATGTACGT", 4, listOf(0, 5, 9, 13), 0.1971, "RefIndex:69", "+"),
            OT("ACGTACGTACGTCCGCACGT", 2, listOf(12, 15), 0.135, "RefIndex:46", "+"),
        )
        assertEquals(exp.size, otC.size)
        exp.zip(otC).forEach { (e, a) ->
            assertEquals(e.sequence, a.sequence)
            assertEquals(e.mismatches, a.mismatches)
            assertEquals(e.mismatchPositions, a.mismatchPositions)
            assertEquals(e.cfdScore, a.cfdScore, 1e-12)
            assertEquals(e.location, a.location)
            assertEquals(e.strand, a.strand)
        }
    }

    @Test
    fun `designHdrDonor parity（同义回避 quirk）`() {
        val g = com.spice.nativeapp.core.GrnaCandidate(
            id = "gRNA_+_1", sequence = "TGGTGTCTAAGGGCGAAGGC", fullTargetWithPam = "TGGTGTCTAAGGGCGAAGGCTAG",
            pam = "TAG", strand = "+", startIndex = 1, endIndex = 21,
            cutIndex = 18,
            scores = com.spice.nativeapp.core.GrnaScores(76, 100, 60, false, false, 81),
            baseEditingWindows = null
        )

        val hdr = designHdrDonor(seqA, g.cutIndex, "ATGGTGTCTAAGGGCGAA", 50, g)
        assertEquals("ATGGTGTCTAAGGGCGAA", hdr.leftArm)
        assertEquals("GGATAACTGGGAATTGGATCGGATCCGGGATTTTCAGGATCGATCGGATC", hdr.rightArm)
        assertEquals("ATGGTGTCTAAGGGCGAA", hdr.insertedMutations)
        assertEquals("ATGGTGTCTAAGGGCGAAATGGTGTCTAAGGGCGAGGGATAACTGGGAATTGGATCGGATCCGGGATTTTCAGGATCGATCGGATC", hdr.fullDonor)
        assertEquals(6, hdr.silentMutationsIntroduced.size)
        listOf(
                SM(33, "GAA", "GAG", "GAA ➔ GAG", "guide_disruption"),
                SM(36, "GGC", "GGA", "GGC ➔ GGA", "guide_disruption"),
                SM(39, "TAG", "TAA", "TAG ➔ TAA", "PAM_destruction"),
                SM(42, "CTA", "CTG", "CTA ➔ CTG", "PAM_destruction"),
                SM(45, "GGG", "GGA", "GGG ➔ GGA", "PAM_destruction"),
                SM(48, "ATC", "ATT", "ATC ➔ ATT", "PAM_destruction"),
        ).zip(hdr.silentMutationsIntroduced).forEach { (e, a) ->
            assertEquals(e.position, a.position)
            assertEquals(e.original, a.original)
            assertEquals(e.mutated, a.mutated)
            assertEquals(e.codonChange, a.codonChange)
            assertEquals(e.reason, a.reason)
        }
        assertEquals(false, hdr.warning != null)

        val hdr2 = designHdrDonor(seqA, g.cutIndex, "ATGGTGTCTAAGGGCGAA", 12, g)
        assertEquals("TCTAAGGGCGAA", hdr2.leftArm)
        assertEquals("GGATAACTGGGA", hdr2.rightArm)
        assertEquals("TCTAAGGGCGAAATGGTGTCTAAGGGCGAGGGATAACTGGGA", hdr2.fullDonor)
        assertEquals(5, hdr2.silentMutationsIntroduced.size)
        listOf(
                SM(27, "GAA", "GAG", "GAA ➔ GAG", "guide_disruption"),
                SM(30, "GGC", "GGA", "GGC ➔ GGA", "guide_disruption"),
                SM(33, "TAG", "TAA", "TAG ➔ TAA", "PAM_destruction"),
                SM(36, "CTA", "CTG", "CTA ➔ CTG", "PAM_destruction"),
                SM(39, "GGG", "GGA", "GGG ➔ GGA", "PAM_destruction"),
        ).zip(hdr2.silentMutationsIntroduced).forEach { (e, a) ->
            assertEquals(e.position, a.position)
            assertEquals(e.original, a.original)
            assertEquals(e.mutated, a.mutated)
            assertEquals(e.codonChange, a.codonChange)
            assertEquals(e.reason, a.reason)
        }
        assertEquals(false, hdr2.warning != null)
    }

    @Test
    fun `designPegRna parity`() {
        val g = com.spice.nativeapp.core.GrnaCandidate(
            id = "gRNA_+_1", sequence = "TGGTGTCTAAGGGCGAAGGC", fullTargetWithPam = "TGGTGTCTAAGGGCGAAGGCTAG",
            pam = "TAG", strand = "+", startIndex = 1, endIndex = 21,
            cutIndex = 18,
            scores = com.spice.nativeapp.core.GrnaScores(76, 100, 60, false, false, 81),
            baseEditingWindows = null
        )

        val peg = designPegRna(seqA, g, g.cutIndex, "T", 13, 16)
        assertEquals("TGGTGTCTAAGGGCGAAGGC", peg.gRNA)
        assertEquals("TTCGCCCTTAGAC", peg.pbs)
        assertEquals(13, peg.pbsLength)
        assertEquals(40, peg.pbsTm)
        assertEquals("CGATCCCTAGCTAGCA", peg.rtt)
        assertEquals(16, peg.rttLength)
        assertEquals("CGATCCCTAGCTAGCATTCGCCCTTAGAC", peg.full3PrimeExtension)
        assertEquals("TGGTGTCTAAGGGCGAAGGCGTTTTAGAGCTAGAAATAGCAAGTTAAAATAAGGCTAGTCCGTTATCAACTTGAAAAAGTGGCACCGAGTCGGTGCCGATCCCTAGCTAGCATTCGCCCTTAGAC", peg.pegRnaSequence)

        val peg2 = designPegRna(seqA, g, g.cutIndex - 5, "GGT", 10, 20)
        assertEquals("TTCGCCCTTA", peg2.pbs)
        assertEquals(30, peg2.pbsTm)
        assertEquals("GATCCGATCCCTAGCTAACC", peg2.rtt)
        assertEquals("GATCCGATCCCTAGCTAACCTTCGCCCTTA", peg2.full3PrimeExtension)
        assertEquals("TGGTGTCTAAGGGCGAAGGCGTTTTAGAGCTAGAAATAGCAAGTTAAAATAAGGCTAGTCCGTTATCAACTTGAAAAAGTGGCACCGAGTCGGTGCGATCCGATCCCTAGCTAACCTTCGCCCTTA", peg2.pegRnaSequence)
    }

    @Test
    fun `calculateBaseEditingWindow parity`() {
        val g = com.spice.nativeapp.core.GrnaCandidate(
            id = "gRNA_+_1", sequence = "TGGTGTCTAAGGGCGAAGGC", fullTargetWithPam = "TGGTGTCTAAGGGCGAAGGCTAG",
            pam = "TAG", strand = "+", startIndex = 1, endIndex = 21,
            cutIndex = 18,
            scores = com.spice.nativeapp.core.GrnaScores(76, 100, 60, false, false, 81),
            baseEditingWindows = null
        )
        val be = calculateBaseEditingWindow(g.sequence, g.startIndex, "+")
        val expCbe = listOf(
                BEC(7, "C", "T", 7, 60),
        )
        val expAbe = listOf<BEC>()
        assertEquals(expCbe.size, be.cbeCandidates.size)
        expCbe.zip(be.cbeCandidates).forEach { (e, a) ->
            assertEquals(e.index, a.index); assertEquals(e.originalBase, a.originalBase)
            assertEquals(e.targetBase, a.targetBase); assertEquals(e.windowPosition, a.windowPosition)
            assertEquals(e.efficiency, a.efficiency)
        }
        assertEquals(expAbe.size, be.abeCandidates.size)
        expAbe.zip(be.abeCandidates).forEach { (e, a) ->
            assertEquals(e.index, a.index); assertEquals(e.originalBase, a.originalBase)
            assertEquals(e.targetBase, a.targetBase); assertEquals(e.windowPosition, a.windowPosition)
            assertEquals(e.efficiency, a.efficiency)
        }

        val be2 = calculateBaseEditingWindow("CCCCAGGGTTTAAAAACCCC", 5, "-")
        val expCbe2 = listOf(
                BEC(21, "C", "T", 4, 60),
                BEC(19, "G", "T", 6, 85),
                BEC(18, "G", "T", 7, 60),
                BEC(17, "G", "T", 8, 35),
        )
        val expAbe2 = listOf(
                BEC(20, "A", "G", 5, 85),
        )
        assertEquals(expCbe2.size, be2.cbeCandidates.size)
        expCbe2.zip(be2.cbeCandidates).forEach { (e, a) ->
            assertEquals(e.index, a.index); assertEquals(e.originalBase, a.originalBase)
            assertEquals(e.targetBase, a.targetBase); assertEquals(e.windowPosition, a.windowPosition)
            assertEquals(e.efficiency, a.efficiency)
        }
        assertEquals(expAbe2.size, be2.abeCandidates.size)
        expAbe2.zip(be2.abeCandidates).forEach { (e, a) ->
            assertEquals(e.index, a.index); assertEquals(e.originalBase, a.originalBase)
            assertEquals(e.targetBase, a.targetBase); assertEquals(e.windowPosition, a.windowPosition)
            assertEquals(e.efficiency, a.efficiency)
        }
    }
}
