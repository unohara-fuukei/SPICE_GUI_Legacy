package com.spice.nativeapp

import com.spice.nativeapp.core.GenomicsTools
import com.spice.nativeapp.core.GenomicsTools.calculateRbsStrength
import com.spice.nativeapp.core.GenomicsTools.detectCpGIslands
import com.spice.nativeapp.core.GenomicsTools.detectTandemRepeats
import com.spice.nativeapp.core.GenomicsTools.predictPromoters
import com.spice.nativeapp.core.GenomicsTools.predictSignalPeptide
import com.spice.nativeapp.core.GenomicsTools.predictTfbsMotifs
import com.spice.nativeapp.core.GenomicsTools.predictTransmembraneHelices
import com.spice.nativeapp.core.GenomicsTools.calculateSkew
import com.spice.nativeapp.core.getAminoAcidStringFromSequenceString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T7-12 AdvancedGenomicsPanel 算法层 parity（main biophysics/genomics.ts 1:1）。
 * 场景字面量与 /tmp/gen12/gen.ts 完全一致（scen.json 单源）；
 * 预期值内联自 tsx 运行 main 真实 genomics.ts 的输出（/tmp/gen12/ref.json）。
 * 覆盖：GFF3/GTF/BED 解析（含 trim 尾 tab 丢行、/\s+/ 空段折叠、name 派生链）、
 * TFBS 正反链、Salis RBS 热力学（含 spacer 惩罚分支）、σ70 启动子、
 * 信号肽三分区（细节串走 I18n，此处比对 hasSignal/cleavagePos/score）、
 * 跨膜螺旋、GC skew（窗 100/步 20）、CpG 岛合并重算、串联重复覆盖去重。
 */
class GenomicsParityTest {

    private val gff3Text = "##gff-version 3\nchr1\tSPICE\tgene\t101\t400\t.\t+\t.\tID=geneA;Name=AmpR\n# a comment line\nchr1\tSPICE\tCDS\t51\t200\t.\t-\t.\tName=HisTag\nchr1\tSPICE\tmisc\t11\t40\t.\t+\t.\tID=onlyId\nchr1\tSPICE\tregion\t5\t10\t.\t+\t.\t\n"
    private val gtfText = "chr1\tSPICE\tgene\t101\t400\t.\t+\t.\tgene_id \"g1\"; gene_name \"lacZ\";\nchr1\tSPICE\ttranscript\t51\t200\t.\t-\t.\tgene_id \"g2\"; transcript_id \"t2\";\nchr1\tSPICE\texon\t11\t40\t.\t+\t.\ttranscript_id \"t3\";\nchr1\tSPICE\tCDS\t5\t10\t.\t+\t.\tno_valid_attr here\n"
    private val bedText = "track name=demo\nchr1\t0\t199\tislandA\t0\t+\nchr1\t200\t399\t\t.\t-\nchr1\t400\t599\tbar\n"
    private val dnaMain = "GCAATTTGACACTGGTACGATTGCAATATAATCGTATCGGATCCCGTTATAAAGCAAAACAAAGTTCGCTATAATGGATCCAAAAAAAATGGGGATGATGATGATGATGATGGCTAGCGGTACGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGCGC"
    private val rbsSeq1 = "GGAGGATTTTTTTATGAAACGTTTAAAGGGCCC"
    private val rbsSeq2 = "TTTTTTTTTTGGTTTTTTTTTTTATGAAACCC"
    private val aaSignal1 = "MKRKGGSLLLIILLVFFIVLLLAQASTNDEFGHIKLMNPQRSTVWY"
    private val aaSignal2 = "DEFGHIKLMNPQRSTVWYQWERTY"
    private val aaTm = "LLLLLLLLLLLLLLLLLLLLLLLLLLEEE"

    @Test
    fun parseGff3Parity() {
        val feats = GenomicsTools.parseGFF3(gff3Text)
        assertEquals(3, feats.size)
        assertEquals("AmpR", feats[0].name)
        assertEquals("gene", feats[0].type)
        assertEquals(100, feats[0].start)
        assertEquals(399, feats[0].end)
        assertEquals("+", feats[0].strand)
        assertEquals("SPICE", feats[0].source)
        assertEquals("HisTag", feats[1].name)
        assertEquals("CDS", feats[1].type)
        assertEquals(50, feats[1].start)
        assertEquals(199, feats[1].end)
        assertEquals("-", feats[1].strand)
        assertEquals("SPICE", feats[1].source)
        assertEquals("onlyId", feats[2].name)
        assertEquals("misc", feats[2].type)
        assertEquals(10, feats[2].start)
        assertEquals(39, feats[2].end)
        assertEquals("+", feats[2].strand)
        assertEquals("SPICE", feats[2].source)
    }

    @Test
    fun parseGtfParity() {
        val feats = GenomicsTools.parseGTF(gtfText)
        assertEquals(4, feats.size)
        assertEquals("lacZ", feats[0].name)
        assertEquals("gene", feats[0].type)
        assertEquals(100, feats[0].start)
        assertEquals(399, feats[0].end)
        assertEquals("+", feats[0].strand)
        assertEquals("g2", feats[1].name)
        assertEquals("transcript", feats[1].type)
        assertEquals(50, feats[1].start)
        assertEquals(199, feats[1].end)
        assertEquals("-", feats[1].strand)
        assertEquals("t3", feats[2].name)
        assertEquals("exon", feats[2].type)
        assertEquals(10, feats[2].start)
        assertEquals(39, feats[2].end)
        assertEquals("+", feats[2].strand)
        assertEquals("CDS_5", feats[3].name)
        assertEquals("CDS", feats[3].type)
        assertEquals(4, feats[3].start)
        assertEquals(9, feats[3].end)
        assertEquals("+", feats[3].strand)
    }

    @Test
    fun parseBedParity() {
        // JS split(/\s+/) 折叠空段：无名行列第 4 列实为 "."（分数列前移）
        val feats = GenomicsTools.parseBED(bedText)
        assertEquals(3, feats.size)
        assertEquals("islandA", feats[0].name)
        assertEquals("region", feats[0].type)
        assertEquals(0, feats[0].start)
        assertEquals(198, feats[0].end)
        assertEquals("+", feats[0].strand)
        assertEquals("BED_file", feats[0].source)
        assertEquals(".", feats[1].name)
        assertEquals("region", feats[1].type)
        assertEquals(200, feats[1].start)
        assertEquals(398, feats[1].end)
        assertEquals("+", feats[1].strand)
        assertEquals("BED_file", feats[1].source)
        assertEquals("bar", feats[2].name)
        assertEquals("region", feats[2].type)
        assertEquals(400, feats[2].start)
        assertEquals(598, feats[2].end)
        assertEquals("+", feats[2].strand)
        assertEquals("BED_file", feats[2].source)
    }

    @Test
    fun tfbsPromoterRbsParity() {
        val tfbs = predictTfbsMotifs(dnaMain)
        assertEquals(3, tfbs.size)
        assertEquals("Pribnow-Box (-10)", tfbs[0].name)
        assertEquals(26, tfbs[0].pos)
        assertEquals("+", tfbs[0].strand)
        assertEquals("TATAAT", tfbs[0].matchSeq)
        assertEquals("Sox9 Binding Site", tfbs[1].name)
        assertEquals(57, tfbs[1].pos)
        assertEquals("+", tfbs[1].strand)
        assertEquals("AACAAAG", tfbs[1].matchSeq)
        assertEquals("Pribnow-Box (-10)", tfbs[2].name)
        assertEquals(69, tfbs[2].pos)
        assertEquals("+", tfbs[2].strand)
        assertEquals("TATAAT", tfbs[2].matchSeq)
        val promoters = predictPromoters(dnaMain)
        assertEquals(1, promoters.size)
        assertEquals(5, promoters[0].pos)
        assertEquals(100, promoters[0].score)
        assertEquals("TTGACA---------------TATAAT", promoters[0].sequence)
        val rbs1 = calculateRbsStrength(rbsSeq1, 13)
        assertEquals(66598, rbs1.tirScore)
        assertEquals(-9.0, rbs1.deltaGSD, 1e-9)
        assertEquals(0.0, rbs1.deltaGSpacer, 1e-9)
        assertEquals(-9.0, rbs1.totalDeltaG, 1e-9)
        assertEquals(7, rbs1.spacerLength)
        assertEquals("GGAGGA", rbs1.sdOverhangSequence)
        val rbs2 = calculateRbsStrength(rbsSeq2, 23)
        assertEquals(4963, rbs2.tirScore)
        assertEquals(-3.0, rbs2.deltaGSD, 1e-9)
        assertEquals(1.0, rbs2.deltaGSpacer, 1e-9)
        assertEquals(-2.0, rbs2.totalDeltaG, 1e-9)
        assertEquals(10, rbs2.spacerLength)
        assertEquals("TTTGGT", rbs2.sdOverhangSequence)
    }

    @Test
    fun localizationParity() {
        val aa = getAminoAcidStringFromSequenceString(dnaMain)
        assertEquals("AI*HWYDCNIIVSDPVIKQNKVRYNGSKKNGDDDDDDG*RYARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARARA", aa)
        val s1 = predictSignalPeptide(aaSignal1)
        assertEquals(true, s1.hasSignal)
        assertEquals(25, s1.cleavagePos)
        assertEquals(100, s1.score)
        val s2 = predictSignalPeptide(aaSignal2)
        assertEquals(false, s2.hasSignal)
        assertEquals(0, s2.cleavagePos)
        assertEquals(0, s2.score)
        val tms = predictTransmembraneHelices(aaTm)
        assertEquals(1, tms.size)
        assertEquals(0, tms[0].start)
        assertEquals(19, tms[0].end)
        assertEquals(3.8, tms[0].averageHydro, 1e-9)
    }

    @Test
    fun skewCpgTandemParity() {
        val skew = calculateSkew(dnaMain, "gc", 100, 20)
        assertEquals(13, skew.size)
        val expSkew = listOf(50 to 0.135, 70 to 0.263, 90 to 0.184, 110 to 0.194, 130 to 0.147, 150 to 0.068, 170 to 0, 190 to 0, 210 to 0, 230 to 0, 250 to 0, 270 to 0, 290 to 0)
        for (i in expSkew.indices) {
            assertEquals(expSkew[i].first, skew[i].pos)
            assertEquals(expSkew[i].second.toDouble(), skew[i].skew, 1e-9)
        }
        val cpg = detectCpGIslands(dnaMain)
        assertEquals(1, cpg.size)
        assertEquals(0, cpg[0].start)
        assertEquals(299, cpg[0].end)
        assertEquals(300, cpg[0].length)
        assertEquals(75.0, cpg[0].gcContent, 1e-9)
        assertEquals(2.260, cpg[0].obsExpRatio, 1e-9)
        val tandem = detectTandemRepeats(dnaMain)
        assertEquals(3, tandem.size)
        assertEquals(81, tandem[0].start); assertEquals(88, tandem[0].end); assertEquals("A", tandem[0].pattern); assertEquals(8, tandem[0].repeatCount); assertEquals(8, tandem[0].totalLength)
        assertEquals(93, tandem[1].start); assertEquals(110, tandem[1].end); assertEquals("GAT", tandem[1].pattern); assertEquals(6, tandem[1].repeatCount); assertEquals(18, tandem[1].totalLength)
        assertEquals(122, tandem[2].start); assertEquals(341, tandem[2].end); assertEquals("CG", tandem[2].pattern); assertEquals(110, tandem[2].repeatCount); assertEquals(220, tandem[2].totalLength)
    }
}
