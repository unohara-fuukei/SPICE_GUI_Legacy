package com.spice.nativeapp

import com.spice.nativeapp.core.CloneValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7-13 CloningValidator parity：预期值全部由 tsx 直跑 main
 * lib/genome/cloningValidation.ts（validateClone / generateMockSangerTraceQuality /
 * pairwiseAlign semi-global）生成（ref.json），scenario 单源 scen.json。
 *
 * 覆盖：PASS / WARNING(同义豁免) / FAIL(错义/移码/整码缺失) / synStrict /
 * stopGain 尾终止子剥离 quirk / intergenic / rc 换向重比对 /
 * target 前缀缺口 quirk（perfectMid 一致性 66.67%）/ lowIdentity 阈值兜底（含
 * jsNumberString(99.0)="99"）/ 反向链 CDS 密码子翻译 / mock traceQuality 确定性。
 */
class CloneValidatorParityTest {

    private val ref0 = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGAGGATAACCCCGGTTAACCGGTTAACCGGTTAACCGGTCCCCGGTTAACCGGTTAACCGGTTAACCGGTCCCCGGTTAACCGGTTAACCGGTTAACCGGTCCCCGGTTAACCGGTTAACCG"


    @Test
    fun perfectStart() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(true, r.passed)
        assertEquals("PASS", r.status)
        assertEquals(100.0, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(0, r.warnings.size)
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(0, r.variants.size)
    }

    @Test
    fun perfectMid() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "GCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGAGGATAACCCCGGTTAACCGGTTAACCGGTTAACCGGTCCCCGGTTAACCGGTTAACCGGT",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(66.67, r.identity, 1e-9)
        assertEquals(75.0, r.coverage, 1e-9)
        assertEquals(180, r.alignedLength)
        assertEquals(2, r.warnings.size)
        assertTrue(r.warnings.contains("验证警告: 比对存在 1 处变异，但它们均处于非编码区，或属于同义突变。"))
        assertTrue(r.warnings.contains("验证失败: 序列一致性 (66.67%) 低于通过阈值 (99%)."))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("deletion", v0.type)
        assertEquals(0, v0.refPos)
        assertEquals(0, v0.queryPos)
        assertEquals("TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCA", v0.refSeq)
        assertEquals("", v0.querySeq)
        assertEquals(60, v0.length)
        assertEquals(false, v0.inCds)
        assertEquals(null, v0.cdsName)
        assertEquals(null, v0.codonIndex)
        assertEquals(null, v0.originalCodon)
        assertEquals(null, v0.mutatedCodon)
        assertEquals(null, v0.originalAminoAcid)
        assertEquals(null, v0.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v0.classification)
        assertEquals("缺失 (Deletion): 缺失了 60 bp (TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCA)，起始于参考序列位置 1 bp", v0.description)
    }

    @Test
    fun missense() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCCTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证失败: 在关键编码区 (CDS) 发现 1 个非同义或移码突变！"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(90, v0.refPos)
        assertEquals(90, v0.queryPos)
        assertEquals("T", v0.refSeq)
        assertEquals("C", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(20, v0.codonIndex)
        assertEquals("TTC", v0.originalCodon)
        assertEquals("CTC", v0.mutatedCodon)
        assertEquals("F", v0.originalAminoAcid)
        assertEquals("L", v0.mutatedAminoAcid)
        assertEquals("Missense", v0.classification)
        assertEquals("单碱基错配 (Mismatch): T ➔ C，位置 91 bp [位于 gfp 编码区，错义突变: 密码子 TTC ➔ CTC (F ➔ L)]", v0.description)
    }

    @Test
    fun synonymous() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGCGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(true, r.passed)
        assertEquals("WARNING", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证警告: 比对存在 1 处变异，但它们均处于非编码区，或属于同义突变。"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(95, v0.refPos)
        assertEquals(95, v0.queryPos)
        assertEquals("A", v0.refSeq)
        assertEquals("C", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(21, v0.codonIndex)
        assertEquals("GGA", v0.originalCodon)
        assertEquals("GGC", v0.mutatedCodon)
        assertEquals("G", v0.originalAminoAcid)
        assertEquals("G", v0.mutatedAminoAcid)
        assertEquals("Synonymous", v0.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ C，位置 96 bp [位于 gfp 编码区，同义突变: 密码子 GGA ➔ GGC (G)]", v0.description)
    }

    @Test
    fun synStrict() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGCGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = false
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证警告: 比对存在 1 处变异，但它们均处于非编码区，或属于同义突变。"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(95, v0.refPos)
        assertEquals(95, v0.queryPos)
        assertEquals("A", v0.refSeq)
        assertEquals("C", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(21, v0.codonIndex)
        assertEquals("GGA", v0.originalCodon)
        assertEquals("GGC", v0.mutatedCodon)
        assertEquals("G", v0.originalAminoAcid)
        assertEquals("G", v0.mutatedAminoAcid)
        assertEquals("Synonymous", v0.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ C，位置 96 bp [位于 gfp 编码区，同义突变: 密码子 GGA ➔ GGC (G)]", v0.description)
    }

    @Test
    fun stopGainQuirk() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCTGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证失败: 在关键编码区 (CDS) 发现 1 个非同义或移码突变！"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(93, v0.refPos)
        assertEquals(93, v0.queryPos)
        assertEquals("G", v0.refSeq)
        assertEquals("T", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(21, v0.codonIndex)
        assertEquals("GGA", v0.originalCodon)
        assertEquals("TGA", v0.mutatedCodon)
        assertEquals("G", v0.originalAminoAcid)
        assertEquals("", v0.mutatedAminoAcid)
        assertEquals("Missense", v0.classification)
        assertEquals("单碱基错配 (Mismatch): G ➔ T，位置 94 bp [位于 gfp 编码区，错义突变: 密码子 GGA ➔ TGA (G ➔ )]", v0.description)
    }

    @Test
    fun intergenic() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAGCCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(true, r.passed)
        assertEquals("WARNING", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证警告: 比对存在 1 处变异，但它们均处于非编码区，或属于同义突变。"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(15, v0.refPos)
        assertEquals(15, v0.queryPos)
        assertEquals("A", v0.refSeq)
        assertEquals("G", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(false, v0.inCds)
        assertEquals(null, v0.cdsName)
        assertEquals(null, v0.codonIndex)
        assertEquals(null, v0.originalCodon)
        assertEquals(null, v0.mutatedCodon)
        assertEquals(null, v0.originalAminoAcid)
        assertEquals(null, v0.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v0.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ G，位置 16 bp", v0.description)
    }

    @Test
    fun delInframe() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(97.5, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(2, r.warnings.size)
        assertTrue(r.warnings.contains("验证失败: 在关键编码区 (CDS) 发现 1 个非同义或移码突变！"))
        assertTrue(r.warnings.contains("验证失败: 序列一致性 (97.50%) 低于通过阈值 (99%)."))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(89.7, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(117, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("deletion", v0.type)
        assertEquals(63, v0.refPos)
        assertEquals(63, v0.queryPos)
        assertEquals("TTC", v0.refSeq)
        assertEquals("", v0.querySeq)
        assertEquals(3, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(null, v0.codonIndex)
        assertEquals(null, v0.originalCodon)
        assertEquals(null, v0.mutatedCodon)
        assertEquals(null, v0.originalAminoAcid)
        assertEquals(null, v0.mutatedAminoAcid)
        assertEquals("Deletion", v0.classification)
        assertEquals("缺失 (Deletion): 缺失了 3 bp (TTC)，起始于参考序列位置 64 bp [位于 gfp 编码区内，整码缺失]", v0.description)
    }

    @Test
    fun insFrameshift() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证失败: 在关键编码区 (CDS) 发现 1 个非同义或移码突变！"))
        assertEquals(38.1, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(89.3, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(121, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("frameshift", v0.type)
        assertEquals(90, v0.refPos)
        assertEquals(90, v0.queryPos)
        assertEquals("", v0.refSeq)
        assertEquals("T", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("gfp", v0.cdsName)
        assertEquals(null, v0.codonIndex)
        assertEquals(null, v0.originalCodon)
        assertEquals(null, v0.mutatedCodon)
        assertEquals(null, v0.originalAminoAcid)
        assertEquals(null, v0.mutatedAminoAcid)
        assertEquals("Frameshift", v0.classification)
        assertEquals("插入 (Insertion): 插入了 1 bp (T)，起始于参考序列位置 91 bp [位于 gfp 编码区内，引起移码突变！]", v0.description)
    }

    @Test
    fun rcRealign() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TCCTCCTCCTCCTCCTCCTCCTCCTCCGAAGAAGAAGAAGAAGAAGAAGAAGAAGAATGCTGCTGCTGCTGCTGCTGCTGCTGCTGCCATAGGCCTTAATCCGGTTAAGCCTTAGCGTAA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(true, r.passed)
        assertEquals("PASS", r.status)
        assertEquals(100.0, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("测序引物方向为反向，已自动对查询序列进行反向互补比对。"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(0, r.variants.size)
    }

    @Test
    fun lowIdentity() {
        val cds = CloneValidation.CdsFeature(name = "gfp", start = 30, end = 125, forward = true)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "GATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTA",
            cdsFeatures = listOf(cds),
            qScores = listOf(15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15,15),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(21.69, r.identity, 1e-9)
        assertEquals(12.92, r.coverage, 1e-9)
        assertEquals(31, r.alignedLength)
        assertEquals(3, r.warnings.size)
        assertTrue(r.warnings.contains("测序信噪比低: 平均 Phred 质量分数 (Q-score) 为 15.0 < 25，可能会产生假阳性突变。"))
        assertTrue(r.warnings.contains("验证警告: 比对存在 15 处变异，但它们均处于非编码区，或属于同义突变。"))
        assertTrue(r.warnings.contains("验证失败: 序列一致性 (21.69%) 低于通过阈值 (99%)."))
        assertEquals(15.0, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(0.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(80, r.traceQuality!!.qScores.size)
        assertEquals(15, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("insertion", v0.type)
        assertEquals(0, v0.refPos)
        assertEquals(0, v0.queryPos)
        assertEquals("", v0.refSeq)
        assertEquals("GATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGA", v0.querySeq)
        assertEquals(51, v0.length)
        assertEquals(false, v0.inCds)
        assertEquals(null, v0.cdsName)
        assertEquals(null, v0.codonIndex)
        assertEquals(null, v0.originalCodon)
        assertEquals(null, v0.mutatedCodon)
        assertEquals(null, v0.originalAminoAcid)
        assertEquals(null, v0.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v0.classification)
        assertEquals("插入 (Insertion): 插入了 51 bp (GATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGATTACAGA)，起始于参考序列位置 1 bp", v0.description)
        val v1 = r.variants[1]
        assertEquals("frameshift", v1.type)
        assertEquals(4, v1.refPos)
        assertEquals(55, v1.queryPos)
        assertEquals("", v1.refSeq)
        assertEquals("A", v1.querySeq)
        assertEquals(1, v1.length)
        assertEquals(false, v1.inCds)
        assertEquals(null, v1.cdsName)
        assertEquals(null, v1.codonIndex)
        assertEquals(null, v1.originalCodon)
        assertEquals(null, v1.mutatedCodon)
        assertEquals(null, v1.originalAminoAcid)
        assertEquals(null, v1.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v1.classification)
        assertEquals("插入 (Insertion): 插入了 1 bp (A)，起始于参考序列位置 5 bp", v1.description)
        val v2 = r.variants[2]
        assertEquals("mismatch", v2.type)
        assertEquals(5, v2.refPos)
        assertEquals(57, v2.queryPos)
        assertEquals("C", v2.refSeq)
        assertEquals("A", v2.querySeq)
        assertEquals(1, v2.length)
        assertEquals(false, v2.inCds)
        assertEquals(null, v2.cdsName)
        assertEquals(null, v2.codonIndex)
        assertEquals(null, v2.originalCodon)
        assertEquals(null, v2.mutatedCodon)
        assertEquals(null, v2.originalAminoAcid)
        assertEquals(null, v2.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v2.classification)
        assertEquals("单碱基错配 (Mismatch): C ➔ A，位置 6 bp", v2.description)
        val v3 = r.variants[3]
        assertEquals("mismatch", v3.type)
        assertEquals(7, v3.refPos)
        assertEquals(59, v3.queryPos)
        assertEquals("A", v3.refSeq)
        assertEquals("T", v3.querySeq)
        assertEquals(1, v3.length)
        assertEquals(false, v3.inCds)
        assertEquals(null, v3.cdsName)
        assertEquals(null, v3.codonIndex)
        assertEquals(null, v3.originalCodon)
        assertEquals(null, v3.mutatedCodon)
        assertEquals(null, v3.originalAminoAcid)
        assertEquals(null, v3.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v3.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ T，位置 8 bp", v3.description)
        val v4 = r.variants[4]
        assertEquals("mismatch", v4.type)
        assertEquals(9, v4.refPos)
        assertEquals(61, v4.queryPos)
        assertEquals("G", v4.refSeq)
        assertEquals("C", v4.querySeq)
        assertEquals(1, v4.length)
        assertEquals(false, v4.inCds)
        assertEquals(null, v4.cdsName)
        assertEquals(null, v4.codonIndex)
        assertEquals(null, v4.originalCodon)
        assertEquals(null, v4.mutatedCodon)
        assertEquals(null, v4.originalAminoAcid)
        assertEquals(null, v4.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v4.classification)
        assertEquals("单碱基错配 (Mismatch): G ➔ C，位置 10 bp", v4.description)
        val v5 = r.variants[5]
        assertEquals("mismatch", v5.type)
        assertEquals(10, v5.refPos)
        assertEquals(62, v5.queryPos)
        assertEquals("G", v5.refSeq)
        assertEquals("A", v5.querySeq)
        assertEquals(1, v5.length)
        assertEquals(false, v5.inCds)
        assertEquals(null, v5.cdsName)
        assertEquals(null, v5.codonIndex)
        assertEquals(null, v5.originalCodon)
        assertEquals(null, v5.mutatedCodon)
        assertEquals(null, v5.originalAminoAcid)
        assertEquals(null, v5.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v5.classification)
        assertEquals("单碱基错配 (Mismatch): G ➔ A，位置 11 bp", v5.description)
        val v6 = r.variants[6]
        assertEquals("mismatch", v6.type)
        assertEquals(11, v6.refPos)
        assertEquals(63, v6.queryPos)
        assertEquals("C", v6.refSeq)
        assertEquals("G", v6.querySeq)
        assertEquals(1, v6.length)
        assertEquals(false, v6.inCds)
        assertEquals(null, v6.cdsName)
        assertEquals(null, v6.codonIndex)
        assertEquals(null, v6.originalCodon)
        assertEquals(null, v6.mutatedCodon)
        assertEquals(null, v6.originalAminoAcid)
        assertEquals(null, v6.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v6.classification)
        assertEquals("单碱基错配 (Mismatch): C ➔ G，位置 12 bp", v6.description)
        val v7 = r.variants[7]
        assertEquals("mismatch", v7.type)
        assertEquals(12, v7.refPos)
        assertEquals(64, v7.queryPos)
        assertEquals("T", v7.refSeq)
        assertEquals("A", v7.querySeq)
        assertEquals(1, v7.length)
        assertEquals(false, v7.inCds)
        assertEquals(null, v7.cdsName)
        assertEquals(null, v7.codonIndex)
        assertEquals(null, v7.originalCodon)
        assertEquals(null, v7.mutatedCodon)
        assertEquals(null, v7.originalAminoAcid)
        assertEquals(null, v7.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v7.classification)
        assertEquals("单碱基错配 (Mismatch): T ➔ A，位置 13 bp", v7.description)
        val v8 = r.variants[8]
        assertEquals("mismatch", v8.type)
        assertEquals(14, v8.refPos)
        assertEquals(66, v8.queryPos)
        assertEquals("A", v8.refSeq)
        assertEquals("T", v8.querySeq)
        assertEquals(1, v8.length)
        assertEquals(false, v8.inCds)
        assertEquals(null, v8.cdsName)
        assertEquals(null, v8.codonIndex)
        assertEquals(null, v8.originalCodon)
        assertEquals(null, v8.mutatedCodon)
        assertEquals(null, v8.originalAminoAcid)
        assertEquals(null, v8.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v8.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ T，位置 15 bp", v8.description)
        val v9 = r.variants[9]
        assertEquals("frameshift", v9.type)
        assertEquals(16, v9.refPos)
        assertEquals(68, v9.queryPos)
        assertEquals("C", v9.refSeq)
        assertEquals("", v9.querySeq)
        assertEquals(1, v9.length)
        assertEquals(false, v9.inCds)
        assertEquals(null, v9.cdsName)
        assertEquals(null, v9.codonIndex)
        assertEquals(null, v9.originalCodon)
        assertEquals(null, v9.mutatedCodon)
        assertEquals(null, v9.originalAminoAcid)
        assertEquals(null, v9.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v9.classification)
        assertEquals("缺失 (Deletion): 缺失了 1 bp (C)，起始于参考序列位置 17 bp", v9.description)
        val v10 = r.variants[10]
        assertEquals("mismatch", v10.type)
        assertEquals(18, v10.refPos)
        assertEquals(69, v10.queryPos)
        assertEquals("G", v10.refSeq)
        assertEquals("A", v10.querySeq)
        assertEquals(1, v10.length)
        assertEquals(false, v10.inCds)
        assertEquals(null, v10.cdsName)
        assertEquals(null, v10.codonIndex)
        assertEquals(null, v10.originalCodon)
        assertEquals(null, v10.mutatedCodon)
        assertEquals(null, v10.originalAminoAcid)
        assertEquals(null, v10.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v10.classification)
        assertEquals("单碱基错配 (Mismatch): G ➔ A，位置 19 bp", v10.description)
        val v11 = r.variants[11]
        assertEquals("mismatch", v11.type)
        assertEquals(24, v11.refPos)
        assertEquals(75, v11.queryPos)
        assertEquals("A", v11.refSeq)
        assertEquals("C", v11.querySeq)
        assertEquals(1, v11.length)
        assertEquals(false, v11.inCds)
        assertEquals(null, v11.cdsName)
        assertEquals(null, v11.codonIndex)
        assertEquals(null, v11.originalCodon)
        assertEquals(null, v11.mutatedCodon)
        assertEquals(null, v11.originalAminoAcid)
        assertEquals(null, v11.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v11.classification)
        assertEquals("单碱基错配 (Mismatch): A ➔ C，位置 25 bp", v11.description)
        val v12 = r.variants[12]
        assertEquals("mismatch", v12.type)
        assertEquals(25, v12.refPos)
        assertEquals(76, v12.queryPos)
        assertEquals("G", v12.refSeq)
        assertEquals("A", v12.querySeq)
        assertEquals(1, v12.length)
        assertEquals(false, v12.inCds)
        assertEquals(null, v12.cdsName)
        assertEquals(null, v12.codonIndex)
        assertEquals(null, v12.originalCodon)
        assertEquals(null, v12.mutatedCodon)
        assertEquals(null, v12.originalAminoAcid)
        assertEquals(null, v12.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v12.classification)
        assertEquals("单碱基错配 (Mismatch): G ➔ A，位置 26 bp", v12.description)
        val v13 = r.variants[13]
        assertEquals("mismatch", v13.type)
        assertEquals(27, v13.refPos)
        assertEquals(78, v13.queryPos)
        assertEquals("C", v13.refSeq)
        assertEquals("A", v13.querySeq)
        assertEquals(1, v13.length)
        assertEquals(false, v13.inCds)
        assertEquals(null, v13.cdsName)
        assertEquals(null, v13.codonIndex)
        assertEquals(null, v13.originalCodon)
        assertEquals(null, v13.mutatedCodon)
        assertEquals(null, v13.originalAminoAcid)
        assertEquals(null, v13.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v13.classification)
        assertEquals("单碱基错配 (Mismatch): C ➔ A，位置 28 bp", v13.description)
        val v14 = r.variants[14]
        assertEquals("mismatch", v14.type)
        assertEquals(28, v14.refPos)
        assertEquals(79, v14.queryPos)
        assertEquals("C", v14.refSeq)
        assertEquals("T", v14.querySeq)
        assertEquals(1, v14.length)
        assertEquals(false, v14.inCds)
        assertEquals(null, v14.cdsName)
        assertEquals(null, v14.codonIndex)
        assertEquals(null, v14.originalCodon)
        assertEquals(null, v14.mutatedCodon)
        assertEquals(null, v14.originalAminoAcid)
        assertEquals(null, v14.mutatedAminoAcid)
        assertEquals("Intronic/Intergenic", v14.classification)
        assertEquals("单碱基错配 (Mismatch): C ➔ T，位置 29 bp", v14.description)
    }

    @Test
    fun reverseCds() {
        val cds = CloneValidation.CdsFeature(name = "rgene", start = 30, end = 125, forward = false)
        val r = CloneValidation.validateClone(
            referenceSequence = ref0,
            querySequence = "TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCCTCGGAGGAGGAGGAGGAGGAGGAGGAGGA",
            cdsFeatures = listOf(cds),
            qScores = listOf(22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40,22,40,40,40,40,40,40,40,40,40),
            minIdentityToPass = 99.0,
            minCoverageToPass = 90.0,
            allowSynonymousMutations = true
        )
        assertEquals(false, r.passed)
        assertEquals("FAIL", r.status)
        assertEquals(99.17, r.identity, 1e-9)
        assertEquals(50.0, r.coverage, 1e-9)
        assertEquals(120, r.alignedLength)
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.contains("验证失败: 在关键编码区 (CDS) 发现 1 个非同义或移码突变！"))
        assertEquals(38.2, r.traceQuality!!.averageQ, 1e-9)
        assertEquals(90.0, r.traceQuality!!.highQualityPercent, 1e-9)
        assertEquals(120, r.traceQuality!!.qScores.size)
        assertEquals(1, r.variants.size)
        val v0 = r.variants[0]
        assertEquals("mismatch", v0.type)
        assertEquals(90, v0.refPos)
        assertEquals(90, v0.queryPos)
        assertEquals("T", v0.refSeq)
        assertEquals("C", v0.querySeq)
        assertEquals(1, v0.length)
        assertEquals(true, v0.inCds)
        assertEquals("rgene", v0.cdsName)
        assertEquals(11, v0.codonIndex)
        assertEquals("GAA", v0.originalCodon)
        assertEquals("GAC", v0.mutatedCodon)
        assertEquals("E", v0.originalAminoAcid)
        assertEquals("D", v0.mutatedAminoAcid)
        assertEquals("Missense", v0.classification)
        assertEquals("单碱基错配 (Mismatch): T ➔ C，位置 91 bp [位于 rgene 编码区，错义突变: 密码子 GAA ➔ GAC (E ➔ D)]", v0.description)
    }

    @Test
    fun mockTraceQualityDeterministic() {
        // mock：ref[0:120] 完美匹配 → 无错配惩罚 → sin/cos 噪声曲线全确定性（无 Math.random 调用）
        val aln = CloneValidation.pairwiseAlign(ref0.substring(0, 120), ref0, "semi-global")
        val mq = CloneValidation.generateMockSangerTraceQuality(aln)
        assertEquals(120, mq.qScores.size)
        assertEquals(120, mq.positions.size)
        assertEquals(12, mq.qScores.min())
        assertEquals(45, mq.qScores.max())
        assertEquals(25.6, mq.averageQ, 1e-9)
        assertEquals(35.8, mq.highQualityPercent, 1e-9)
        assertEquals(12, mq.qScores[0])
        assertEquals(13, mq.qScores[1])
        assertEquals(15, mq.qScores[2])
        assertEquals(15, mq.qScores[3])
        assertEquals(14, mq.qScores[4])
    }

    @Test
    fun pairwiseSemiGlobalDirect() {
        // rcRealign 场景的正向 query 直接比对：锁 semi-global 终点扫描/回溯 quirk
        val aln = CloneValidation.pairwiseAlign("TCCTCCTCCTCCTCCTCCTCCTCCTCCGAAGAAGAAGAAGAAGAAGAAGAAGAAGAATGCTGCTGCTGCTGCTGCTGCTGCTGCTGCCATAGGCCTTAATCCGGTTAAGCCTTAGCGTAA", ref0, "semi-global")
        assertEquals("TCCTCCTCCTCCTCCTCC-----TCCTCCT-CCGAAGAAGAAGAAGAAGAAGAAGAAGAAGAATGCTGCTGCTGCTGCTGCTGCTGCTGCTGCCATAGGCCTTAATCCGGTTAAGCCTTAGCGTAA", aln.alignedQuery)
        assertEquals("TTACGCTAAGGCTTAACCGGATTAAGGCCTATGGCAGCAGCAGCAGCAGCAGCAGCAGCAGCATTCTTCTTCTTCTTCTTCTTCTTCTTCTTCGGAGGAGGAGGAGGAGGAGGAGGAGGAGGATAA", aln.alignedTarget)
        assertEquals(0.48412698412698413, aln.identity, 1e-9)
        assertEquals(47, aln.score)
        assertEquals(6, aln.gaps)
        assertEquals(0, aln.queryStart)
        assertEquals(120, aln.queryEnd)
        assertEquals(0, aln.targetStart)
        assertEquals(126, aln.targetEnd)
    }
}
