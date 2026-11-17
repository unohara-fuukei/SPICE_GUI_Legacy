package com.spice.nativeapp

import com.spice.nativeapp.core.AbiReader
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.CloneValidation
import com.spice.nativeapp.core.GenomicsTools
import com.spice.nativeapp.core.MultipleAlign
import com.spice.nativeapp.core.ProteinBiophysics
import com.spice.nativeapp.core.RnaStructure
import com.spice.nativeapp.core.buildLifecyleTraceMap
import org.junit.Assert.*
import org.junit.Test

/** 基因页分析模块测试（对齐 main 分支 rnaStructure / genomics / cloningValidation / alignment 语义） */
class GeneAnalysisTest {

    // ───────────────── RnaStructure ─────────────────

    @Test fun nussinovSimpleStemLoop() {
        // 一个明确茎环：5' 与 3' 反向互补配对，中间为环
        val seq = "GGGGAAAACCCC"
        val r = RnaStructure.nussinovFold(seq, isRna = false)
        assertTrue("应有配对，实际 ${r.pairs.size}", r.pairs.size >= 4)
        // dot-bracket 括号应平衡
        assertEquals(r.dotBracket.count { it == '(' }, r.dotBracket.count { it == ')' })
        // MFE 为负
        assertTrue(r.mfe < 0)
    }

    @Test fun nussinovUnpairedRun() {
        val r = RnaStructure.nussinovFold("AAAAAAAAAAAA", isRna = false)
        assertEquals(0, r.pairs.size)
        assertEquals("............", r.dotBracket)
        assertTrue(r.gcContent >= 0.0)
    }

    @Test fun findStructuresSlidingWindow() {
        // 长序列：至少产生一个窗口结构
        val seq = ("GGGGAAAACCCC" + "TTTT".repeat(40) + "GGGGAAAACCCC").replace("T", "U")
        val s = RnaStructure.findStructures(seq, isRna = true)
        assertTrue(s.structures.isNotEmpty())
        assertNotNull(s.longestHairpin)
        assertTrue(s.overallMfe <= 0.0)
        // 清洗后只含 AUGC
        assertTrue(s.sequence.all { it in "AUGC" })
    }

    @Test fun nussinovWobbleGUPair() {
        // GU 摆动配对只在 RNA 模式允许
        val rRna = RnaStructure.nussinovFold("GGGUUU", isRna = true)
        val rDna = RnaStructure.nussinovFold("GGGTTT", isRna = false)
        assertTrue(rRna.pairs.size > 0)
        // DNA 模式 GT 不配对（且环长限制下无配对）
        assertEquals(0, rDna.pairs.size)
    }

    // ───────────────── GenomicsTools ─────────────────

    @Test fun tfbsTataBox() {
        // TATA[AT]A[AT] 共识：TATAAAT 命中（idx 1 起）
        val hits = GenomicsTools.predictTfbsMotifs("TTATAAATAAGGG")
        assertTrue(hits.any { it.name == "TATA-Box" && it.matchSeq == "TATAAAT" })
    }

    @Test fun rbsStrengthOptimalSpacing() {
        // AGGAGG 距 ATG 7bp：接近最优间隔
        val seq = "AAAAAAGGAGGAAAAAAATGAAAA"
        val atg = seq.indexOf("ATG")
        val r = GenomicsTools.calculateRbsStrength(seq, atg)
        assertTrue("TIR 应显著（6/6 匹配），实际 ${r.tirScore}", r.tirScore > 1000)
        assertEquals(-9.0, r.deltaGSD, 0.01)
    }

    @Test fun promoterConsensus() {
        // TTGACA + 17bp 间隔 + TATAAT
        val seq = "TTGACA" + "A".repeat(17) + "TATAAT" + "G".repeat(30)
        val ps = GenomicsTools.predictPromoters(seq)
        assertTrue("应检出启动子", ps.isNotEmpty())
        assertEquals(100, ps.first().score)
    }

    @Test fun cpgIslandDetection() {
        // 高 GC 高 CpG 密度区段（>200bp）
        val cpg = "CGCGCGATCGCGCGATCGCG" .repeat(12)
        val islands = GenomicsTools.detectCpGIslands(cpg)
        assertTrue(islands.isNotEmpty())
        assertTrue(islands.first().gcContent > 50.0)
        assertTrue(islands.first().obsExpRatio > 0.6)
    }

    @Test fun tandemRepeats() {
        val reps = GenomicsTools.detectTandemRepeats("ACACACACACACATTTTTTTTTTG")
        assertTrue(reps.any { it.pattern == "AC" && it.repeatCount == 6 })
        // 单核苷酸需 ≥8
        assertTrue(reps.any { it.pattern == "T" && it.repeatCount == 10 })
    }

    @Test fun prokaryoticGenePrediction() {
        // 含 RBS + ATG + 100bp ORF + TAA
        val orf = "ATG" + "GCA".repeat(33) + "TAA"   // 3 + 99 + 3 = 105 bp
        val seq = "AAAAAAGGAGGTAT" + orf + "CCCCCCCC"
        val genes = GenomicsTools.predictProkaryoticGenes(seq, 90)
        assertTrue("应预测出 ORF，实际 ${genes.size}", genes.isNotEmpty())
        val g = genes.first()
        assertTrue(g.hasRbsMatch)
        assertEquals("ATG", g.startCodon)
        // 翻译长度应为 35 aa（含终止）
        assertEquals(35, g.translation.length)
    }

    @Test fun crisprArrayDetection() {
        val dr = "GTTTTAGAGCTATGTTGTTTTGAATGGTCCCAAAAC"  // 37bp
        val s1 = "ACGTACGTACGTACGTACGTACGTA"              // 25bp
        val s2 = "TTTTGGGGCCCCAAAATTTTGGGG"               // 24bp
        val seq = dr + s1 + dr + s2 + dr
        val arrays = GenomicsTools.detectCrisprArrays(seq)
        assertTrue("应检出 CRISPR array", arrays.isNotEmpty())
        assertEquals(3, arrays.first().repeatCount)
        assertEquals(2, arrays.first().spacers.size)
        assertTrue(arrays.first().confidence >= 80)
    }

    @Test fun signalPeptideDetection() {
        // 经典分泌信号：N 区带 K、H 区疏水、A-x-A 切割位点
        val sp = "MKKLLILLALVLFAAALAAAEDVGSSE"
        val r = GenomicsTools.predictSignalPeptide(sp)
        assertTrue("应检出信号肽：${r.details}", r.hasSignal)
        assertTrue(r.cleavagePos > 10)
    }

    @Test fun transmembraneHelix() {
        val tm = "M" + "LAVILLLAVILLLAVILLLA".repeat(2) + "EKRKEK"
        val helices = GenomicsTools.predictTransmembraneHelices(tm)
        assertTrue(helices.isNotEmpty())
        assertTrue(helices.first().averageHydro > 1.5)
    }

    @Test fun gff3AndBedParsing() {
        val gff = "##gff-version 3\nchr1\t.\tgene\t101\t500\t.\t+\t.\tID=g1\n"
        val feats = GenomicsTools.parseGFF3(gff)
        assertEquals(1, feats.size)
        // main genomics.ts 1:1：GFF 1-based → 0-based（start-1）；name 由 ID 派生
        assertEquals(100, feats.first().start)
        assertEquals("g1", feats.first().name)
        val bed = "chr1\t100\t499\tgene1\t0\t+\n"
        val bf = GenomicsTools.parseBED(bed)
        assertEquals(1, bf.size)
        // main 1:1：BED chromStart 原值 0-based；end = chromEnd-1
        assertEquals(100, bf.first().start)
        assertEquals(498, bf.first().end)
        assertEquals("gene1", bf.first().name)
    }

    // ───────────────── CloneValidation ─────────────────

    @Test fun validatePerfectClone() {
        val ref = "ATG" + "GCA".repeat(30) + "TAA"
        val r = CloneValidation.validateClone(ref, ref, listOf(CloneValidation.CdsFeature("gfp", 0, ref.length - 1)))
        // T7-13 后按 main 1:1：status 决策引擎
        assertTrue(r.passed)
        assertEquals("PASS", r.status)
        assertEquals(100.0, r.identity, 0.01)
        assertTrue(r.variants.isEmpty())
    }

    @Test fun validateMissense() {
        val ref = "ATG" + "GCA".repeat(30) + "TAA"
        // 单点突变：密码子 2 的 GCA(Ala) → GTA(Val)，仅位置 4（0-based）C→T
        val mutated = ref.substring(0, 4) + "T" + ref.substring(5)
        val r = CloneValidation.validateClone(ref, mutated, listOf(CloneValidation.CdsFeature("gene", 0, ref.length - 1)))
        // T7-13 后按 main 1:1：classification/description 语义
        val mis = r.variants.filter { it.classification == "Missense" }
        assertTrue("应检出错义：${r.variants}", mis.isNotEmpty())
        assertEquals("GCA", mis.first().originalCodon)
        assertEquals("GTA", mis.first().mutatedCodon)
        assertEquals("A", mis.first().originalAminoAcid)
        assertEquals("V", mis.first().mutatedAminoAcid)
        assertTrue("desc: ${mis.first().description}", mis.first().description.contains("密码子 GCA ➔ GTA (A ➔ V)"))
    }

    @Test fun validateStopGainQuirk() {
        val ref = "ATG" + "CAA".repeat(30) + "TAA"
        // 单点突变：密码子 2 的 CAA(Gln) → TAA(终止)，位置 3（0-based）C→T。
        // main 1:1 quirk：getAminoAcidStringFromSequenceString 剔除尾部终止子（单密码子调用
        // 时 '*' 恒被剥成空串）→ 终止子获得分类为 Missense[mutatedAminoAcid=""]，而非 Nonsense。
        val mutated = "T".let { ref.substring(0, 3) + it + ref.substring(4) }
        val r = CloneValidation.validateClone(ref, mutated, listOf(CloneValidation.CdsFeature("gene", 0, ref.length - 1)))
        val mis = r.variants.filter { it.inCds }
        assertTrue("应检出 CDS 内变异：${r.variants}", mis.isNotEmpty())
        assertEquals("Missense", mis.first().classification)
        assertEquals("Q", mis.first().originalAminoAcid)
        assertEquals("", mis.first().mutatedAminoAcid)
        assertFalse(r.passed)
    }

    @Test fun validateRcRead() {
        val ref = "ATG" + "GCA".repeat(20) + "TAA" + "TTTTTTTTTTTTTTT"
        val read = Bio.revComp(ref.substring(10, 40))
        val r = CloneValidation.validateClone(ref, read, emptyList())
        // T7-13 后按 main 1:1：rc 换向以 cvRcRealign 警告体现；
        // semi-global target 前缀以 query 缺口计入分母 → identity 88.24（main tsx 实测）
        assertTrue("应含换向警告：${r.warnings}", r.warnings.any { it.contains("反向互补") })
        assertEquals(88.24, r.identity, 0.01)
    }

    // ───────────────── MultipleAlign ─────────────────

    @Test fun msaIdenticalSequences() {
        val m = MultipleAlign.multipleAlign(listOf("a" to "ATGGCCATT", "b" to "ATGGCCATT"))
        assertEquals(2, m.rows.size)
        assertEquals("ATGGCCATT", m.consensus)
        assertEquals(1.0, m.identity, 0.001)
        assertTrue(m.conservation.all { it == 1.0 })
    }

    @Test fun msaWithInsertion() {
        // b = 参考 + 尾部延伸 GGA（12 nt）：master 尾部应补 3 缺口，宽度 = 12
        val m = MultipleAlign.multipleAlign(listOf("ref" to "ATGGCCATT", "b" to "ATGGCCATTGGA"))
        assertEquals(2, m.rows.size)
        assertEquals(12, m.width)
        assertEquals("ATGGCCATT---", m.rows.first().alignedSeq)
        assertEquals("ATGGCCATTGGA", m.rows[1].alignedSeq)
        assertTrue(m.consensus.contains("-"))
        assertTrue(m.identity < 1.0)
    }

    @Test fun pairwiseSemiGlobal() {
        // read 是参考的内部片段
        val ref = "TTTTTTTTATGGCCATTTTTTTTTTTT"
        val read = "ATGGCCATT"
        val p = MultipleAlign.pairwiseAlign(read, ref)
        assertEquals(1.0, p.identity, 0.001)
        assertTrue(p.alignedQuery.length == p.alignedTarget.length)
    }

    // ───────────────── AbiReader（真实 ab1 fixture） ─────────────────

    @Test fun abiReaderParsesFixture() {
        // 测试工作目录不定，向上回溯查找 e2e/fixtures/310.ab1
        var dir = java.io.File(System.getProperty("user.dir"))
        var fixture: java.io.File? = null
        while (dir != null && fixture == null) {
            val f = java.io.File(dir, "e2e/fixtures/310.ab1")
            if (f.exists()) fixture = f else dir = dir.parentFile
        }
        assertNotNull("未找到 310.ab1 fixture（user.dir=${System.getProperty("user.dir")}）", fixture)
        val bytes = fixture!!.readBytes()
        val t = AbiReader.parse(bytes)
        assertNotNull("应能解析 310.ab1", t)
        if (t != null) {
            assertEquals(868, t.bases.length)
            assertTrue(t.bases.startsWith("TGAT"))
            assertTrue(t.qualities.size in 1..868)
            // 四色峰通道
            assertTrue("应有四色通道，实际 ${t.channels.keys}", t.channels.containsKey('G') && t.channels.containsKey('A'))
            // 每通道 10200 采样点
            assertEquals(10200, t.channels['G']?.size)
            // 峰位与碱基数一致
            assertEquals(868, t.peakIndices.size)
            assertTrue(t.peakIndices.first() in 10..20)
        }
    }

    // ───────────────── 双向追溯：终止密码子残基 ddG 空串兜底 ─────────────────

    @Test fun lifecycleTraceStopCodonAminoAcidIsEmpty() {
        // ATG(M) + TAA(终止)：单密码子调用翻译函数时 '*' 被剥成空串（main 1:1 quirk，
        // 见 validateStopGainQuirk）。追溯映射仍为该终止子生成条目，aminoAcid 为 ""。
        val trace = buildLifecyleTraceMap("ATGTAA", 0, 5)
        assertEquals(2, trace.size)
        assertEquals("M", trace[0].aminoAcid)
        assertEquals("", trace[1].aminoAcid)
    }

    @Test fun ddgAcceptsEmptyStopCodonResidueWithoutCrash() {
        val trace = buildLifecyleTraceMap("ATGTAA", 0, 5)
        val stop = trace[1].aminoAcid
        // 对齐 main：main ddG 直接收整串、表查缺失给默认值；native 取首字符时空串必须
        // 兜底为未知残基 'X'（与 getAaOfTriplet 未知兜底一致），不得抛 NoSuchElement。
        val wt = stop.firstOrNull() ?: 'X'
        val res = ProteinBiophysics.mutationalStabilityDdg(wt, 2, 'A')
        assertNotNull(res)
        assertTrue(res.ddG.isFinite())
    }
}
