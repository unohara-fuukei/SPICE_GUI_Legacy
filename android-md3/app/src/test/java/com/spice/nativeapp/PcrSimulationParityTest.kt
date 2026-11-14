package com.spice.nativeapp

import com.spice.nativeapp.core.PcrSimulation
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * main src/lib/genome/primerAnalysis.ts simulatePCR / simulateOverlapExtensionPCR /
 * simulateSiteDirectedMutagenesis parity（对照 /tmp/pcr_gen.ts 内联原版逻辑 + 依赖函数
 * 直跑输出的 /tmp/pcr_ref.json）：标准 PCR 11 例（10bp 种子回退 / 环状跨起点 / 简并清洗 /
 * >5000bp 警告 / 空模板）、OE-PCR 7 例（完美重叠 / 反向互补重叠 / 不匹配直拼 / 3 片段 /
 * 单片段 / ov10 / ov0=JS slice(-0) 整串语义）、定点突变 8 例（点突变 / 区间替换 / 越界 /
 * 插入过大 / primerLength 变体）。
 */
class PcrSimulationParityTest {
    private fun assertD(a: Double, b: Double) {
        assertTrue("expected $a got $b", abs(a - b) < 1e-9)
    }

    private val BASE = "ACGTACGTACGTATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTAGGCATCGGATCCATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTAGGCATCGGATCCTTTTGGGC"
    private val BIG = "ATGCGGCTTAAAGGTC" + "ACGTACGTACGT".repeat(420) + "GCATCGGATCCATGCGG"
    private val CIRC = "AAAAAAAAAACCCCTTTTACGTACGTACGATCGA"
    private fun rc(s: String) = com.spice.nativeapp.core.CollectionIO.revComp(s)

    @Test
    fun simulatePcrParity() {
        run { // std1
            val r = PcrSimulation.simulatePCR(BASE, "ATGCGGCTTAAAGGTC", rc("GCATCGGATCCATGCGG"), circular = false)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTAGGCATCGGATCCATGCGG", r.product)
            assertEquals(67, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("CCGCATGGATCCGATGC", r.reversePrimer)
            assertEquals(12, r.templateStart)
            assertEquals(79, r.templateEnd)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(55.223880597014926, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // cleanDegenerate
            val r = PcrSimulation.simulatePCR(BASE, "nN" + "atgcggcttaaaggtc", rc("GCATCGGATCCATGCGG"), circular = false)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTAGGCATCGGATCCATGCGG", r.product)
            assertEquals(67, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("CCGCATGGATCCGATGC", r.reversePrimer)
            assertEquals(12, r.templateStart)
            assertEquals(79, r.templateEnd)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(55.223880597014926, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // fwdMiss
            val r = PcrSimulation.simulatePCR(BASE, "GGGGGGGGGGGGGGGG", rc("GCATCGGATCCATGCGG"), circular = false)
            assertEquals("", r.product)
            assertEquals(0, r.productLength)
            assertEquals("GGGGGGGGGGGGGGGG", r.forwardPrimer)
            assertEquals("CCGCATGGATCCGATGC", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(0, r.templateEnd)
            assertD(0.0, r.meltingTemp)
            assertD(0.0, r.gcContent)
            assertEquals(listOf("PCR 模拟失败"), r.warnings)
        }
        run { // revMiss
            val r = PcrSimulation.simulatePCR(BASE, "ATGCGGCTTAAAGGTC", "GGGGGGGGGGGGGGGG", circular = false)
            assertEquals("", r.product)
            assertEquals(0, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("GGGGGGGGGGGGGGGG", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(0, r.templateEnd)
            assertD(0.0, r.meltingTemp)
            assertD(0.0, r.gcContent)
            assertEquals(listOf("PCR 模拟失败"), r.warnings)
        }
        run { // shortProduct
            val r = PcrSimulation.simulatePCR(BASE, "ATGCGGCTTAAAGGTC", rc("CGATCGTAGCTAGGCT"), circular = false)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCT", r.product)
            assertEquals(32, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("AGCCTAGCTACGATCG", r.reversePrimer)
            assertEquals(12, r.templateStart)
            assertEquals(44, r.templateEnd)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(53.125, r.gcContent)
            assertEquals(listOf("PCR 产物过短 (<50bp)"), r.warnings)
        }
        run { // circularWrap
            val r = PcrSimulation.simulatePCR(CIRC, "AAAAAAAAAA", rc("ACGTACGTACGGGGGGGG"), circular = true)
            assertEquals("AAAAAAAAAACCCCTTTTACGTACGTACGATCGAAA", r.product)
            assertEquals(36, r.productLength)
            assertEquals("AAAAAAAAAA", r.forwardPrimer)
            assertEquals("CCCCCCCCGTACGTACGT", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(36, r.templateEnd)
            assertD(52.25548675645297, r.meltingTemp)
            assertD(33.33333333333333, r.gcContent)
            assertEquals(listOf("PCR 产物过短 (<50bp)"), r.warnings)
        }
        run { // linearSameRev
            val r = PcrSimulation.simulatePCR(CIRC, "AAAAAAAAAA", rc("ACGTACGTACGGGGGGGG"), circular = false)
            assertEquals("AAAAAAAAAACCCCTTTTACGTACGTACGATCGA", r.product)
            assertEquals(34, r.productLength)
            assertEquals("AAAAAAAAAA", r.forwardPrimer)
            assertEquals("CCCCCCCCGTACGTACGT", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(36, r.templateEnd)
            assertD(52.25548675645297, r.meltingTemp)
            assertD(35.294117647058826, r.gcContent)
            assertEquals(listOf("PCR 产物过短 (<50bp)"), r.warnings)
        }
        run { // emptyTemplate
            val r = PcrSimulation.simulatePCR("", "ACGT", "ACGT", circular = false)
            assertEquals("", r.product)
            assertEquals(0, r.productLength)
            assertEquals("ACGT", r.forwardPrimer)
            assertEquals("ACGT", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(0, r.templateEnd)
            assertD(0.0, r.meltingTemp)
            assertD(0.0, r.gcContent)
            assertEquals(listOf("PCR 模拟失败"), r.warnings)
        }
        run { // seed10
            val r = PcrSimulation.simulatePCR("ACGT" + "ATGCGGCTTA" + "TTTT", "ATGCGGCTTAXXXXXXXXXXXXXX", rc("TTTT"), circular = false)
            assertEquals("ATGCGGCTTATTTT", r.product)
            assertEquals(14, r.productLength)
            assertEquals("ATGCGGCTTA", r.forwardPrimer)
            assertEquals("AAAA", r.reversePrimer)
            assertEquals(4, r.templateStart)
            assertEquals(18, r.templateEnd)
            assertD(49.10022925633422, r.meltingTemp)
            assertD(35.714285714285715, r.gcContent)
            assertEquals(listOf("PCR 产物过短 (<50bp)"), r.warnings)
        }
        run { // longProduct
            val r = PcrSimulation.simulatePCR(BIG, "ATGCGGCTTAAAGGTC", rc("GCATCGGATCCATGCGG"), circular = false)
            assertEquals("ATGCGGCTTAAAGGTCACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTGCATCGGATCCATGCGG", r.product)
            assertEquals(5073, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("CCGCATGGATCCGATGC", r.reversePrimer)
            assertEquals(0, r.templateStart)
            assertEquals(5073, r.templateEnd)
            assertD(67.21449075234824, r.meltingTemp)
            assertD(50.049280504632364, r.gcContent)
            assertEquals(listOf("PCR 产物较长，可能需要优化延伸时间"), r.warnings)
        }
        run { // revSeed10
            val r = PcrSimulation.simulatePCR(BASE, "ATGCGGCTTAAAGGTC", rc("GCATCGGATCCATGCGG").substring(0, 10), circular = false)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTAGGCATCGGATCCATGCGG", r.product)
            assertEquals(67, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTC", r.forwardPrimer)
            assertEquals("CCGCATGGAT", r.reversePrimer)
            assertEquals(12, r.templateStart)
            assertEquals(79, r.templateEnd)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(55.223880597014926, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
    }

    @Test
    fun overlapExtensionParity() {
        val S = "ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC"
        run { // perfect
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", (S.substring(S.length - 20) + "GGGGCCCCAAAATTTT") to "F2"), 20)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCGGGGCCCCAAAATTTT", r.product)
            assertEquals(63, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("AAAATTTTGGGGCCCCGGAT", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(52.38095238095239, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // rcOverlap
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", (rc(S.substring(S.length - 20)) + "TTTTAAAA") to "F2"), 20)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTTTTAAAA", r.product)
            assertEquals(55, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("TTTTAAAAAGGCTTAAGGCA", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(45.45454545454545, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // mismatch
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", "CCCCGGGGTTTTAAAACCCCGGGG" to "F2"), 20)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCCCCCGGGGTTTTAAAACCCCGGGG", r.product)
            assertEquals(71, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("CCCCGGGGTTTTAAAACCCC", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(57.74647887323944, r.gcContent)
            assertEquals(listOf("片段 1 与前一片段重叠区域不匹配"), r.warnings)
        }
        run { // threeFrags
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", (S.substring(S.length - 20) + "AAAACCCC") to "F2", ("AAAACCCC" + "GGGG") to "F3"), 20)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCAAAACCCCAAAACCCCGGGG", r.product)
            assertEquals(67, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("CCCCGGGGTTTT", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(55.223880597014926, r.gcContent)
            assertEquals(listOf("片段 2 与前一片段重叠区域不匹配"), r.warnings)
        }
        run { // single
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1"), 20)
            assertEquals("", r.product)
            assertEquals(0, r.productLength)
            assertEquals("", r.forwardPrimer)
            assertEquals("", r.reversePrimer)
            assertD(0.0, r.meltingTemp)
            assertD(0.0, r.gcContent)
            assertEquals(listOf("PCR 模拟失败"), r.warnings)
        }
        run { // ov10
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", (S.substring(S.length - 10) + "TTTT") to "F2"), 10)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCTTTT", r.product)
            assertEquals(51, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("AAAAGGATCCGATG", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(49.01960784313725, r.gcContent)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // ov0
            val r = PcrSimulation.simulateOverlapExtensionPCR(listOf(S to "F1", (S + S) to "F2"), 0)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.product)
            assertEquals(141, r.productLength)
            assertEquals("ATGCGGCTTAAAGGTCCGAT", r.forwardPrimer)
            assertEquals("GGATCCGATGCCTTAAGCCT", r.reversePrimer)
            assertD(66.87359455598835, r.meltingTemp)
            assertD(53.191489361702125, r.gcContent)
            assertEquals(listOf("片段 1 与前一片段重叠区域不匹配"), r.warnings)
        }
    }

    @Test
    fun mutagenesisParity() {
        val TM = "ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC"
        run { // pointMid
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 10, "A")
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(10, r.mutationStart)
            assertEquals(10, r.mutationEnd)
            assertEquals("ATGCGGCTTAAAGGTCCGATCGT", r.fwdPrimer)
            assertEquals("ACGATCGGACCTTTAAGCCGCAT", r.revPrimer)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // point0
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 0, "G")
            assertEquals("GTGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(0, r.mutationStart)
            assertEquals(0, r.mutationEnd)
            assertEquals("GTGCGGCTTAAAG", r.fwdPrimer)
            assertEquals("CTTTAAGCCGCAC", r.revPrimer)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // pointEnd
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 46, "C")
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(46, r.mutationStart)
            assertEquals(46, r.mutationEnd)
            assertEquals("AGGCATCGGATCC", r.fwdPrimer)
            assertEquals("GGATCCGATGCCT", r.revPrimer)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // outOfRange
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 500, "A")
            assertEquals("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCA", r.mutatedSequence)
            assertEquals(500, r.mutationStart)
            assertEquals(500, r.mutationEnd)
            assertEquals("", r.fwdPrimer)
            assertEquals("", r.revPrimer)
            assertEquals(listOf("突变位置超出模板范围"), r.warnings)
        }
        run { // insertLarge
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 5, 10, "ACGTACGTACGTACGTACGTACGTACGT")
            assertEquals("ATGCGACGTACGTACGTACGTACGTACGTACGTAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(5, r.mutationStart)
            assertEquals(10, r.mutationEnd)
            assertEquals("ATGCGACGTACGTACGTACG", r.fwdPrimer)
            assertEquals("CGTACGTACGTACGTCGCAT", r.revPrimer)
            assertEquals(listOf("插入序列较大，可能影响引物退火"), r.warnings)
        }
        run { // rangeReplace
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 5, 10, "GGGG")
            assertEquals("ATGCGGGGGAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(5, r.mutationStart)
            assertEquals(10, r.mutationEnd)
            assertEquals("ATGCGGGGGAGGTCCGATCG", r.fwdPrimer)
            assertEquals("CGATCGGACCTCCCCCGCAT", r.revPrimer)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // primerLen15
            val r = PcrSimulation.simulateSiteDirectedMutagenesis(TM, 20, "T", primerLength = 15)
            assertEquals("ATGCGGCTTAAAGGTCCGATTGTAGCTAGGCTTAAGGCATCGGATCC", r.mutatedSequence)
            assertEquals(20, r.mutationStart)
            assertEquals(20, r.mutationEnd)
            assertEquals("GTCCGATTGTAGCTA", r.fwdPrimer)
            assertEquals("TAGCTACAATCGGAC", r.revPrimer)
            assertEquals(emptyList<String>(), r.warnings)
        }
        run { // emptyTemplate
            val r = PcrSimulation.simulateSiteDirectedMutagenesis("", 0, "A")
            assertEquals("A", r.mutatedSequence)
            assertEquals(0, r.mutationStart)
            assertEquals(0, r.mutationEnd)
            assertEquals("A", r.fwdPrimer)
            assertEquals("T", r.revPrimer)
            assertEquals(listOf("突变位置超出模板范围"), r.warnings)
        }
    }
}
