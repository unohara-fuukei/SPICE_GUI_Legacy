package com.spice.nativeapp

import com.spice.nativeapp.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 块6 ProteinViewPanel parity 测试（对照 /tmp/pv_ref.json，由 inline proteinView.ts 的生成器产出）。
 * 覆盖：物化属性（MW/pI/消光/芳香度/GRAVY/电荷）、PTM 全类型与顺序、密码子频率表
 * （含 aaMap 缺 ATG→X quirk）、三/单字母互转、氨基酸逐碱基展开、尾部 * 剔除、formatMw。
 */
class ProteinViewParityTest {

    private data class PTM(val type: PtmType, val position: Int, val residue: String, val motif: String, val confidence: Double)

    private data class CF(val codon: String, val aa: String, val count: Int, val fraction: Double, val perThousand: Double)

    private val DNA1 = "ATGGTGTCTAAGGGCGAAATCTATAACTTCACCGGCGGTTGTGCCCATTTTTTTCAACACCTGATTAACGGTAAAATTCGCGAAAAAATGGGTTATAAATACCGTAAATTCATGCCTGATTTACAAAAGCTTTATGGGTCGTTTTTCTGCTTATGCTTTACAATTTAAAAAATGATTAAATATCTTTTTACAAAAACTTAATAAAAAAGGTTATCACTTAAAAGGATATTGTGGGTCGTTTTTTTTTCAACACCTGATTTTT"
    private val DNA2 = "ATGAAACGTCCGAAAAAGCCGCCTAA"
    private val DNA3 = "ATGGATGATGAAGAGGATTAA"
    private val DNA4 = "ATGAATCGYATGXXXGGGATTAA"
    private val DNA5 = ""
    private val DNA6 = "AT"

    @Test
    fun proteinViewParity() {
        // ── 翻译序列（含尾部 * 剔除、简并回退）──
        assertEquals("MVSKGEIYNFTGGCAHFFQHLINGKIREKMGYKYRKFMPDLQKLYGSFFCLCFTI*KMIKYLFTKT**KRLSLKRILWVVFFSTPDF", buildProteinView(DNA1).sequence)
        assertEquals("MNRMXGI", buildProteinView(DNA4).sequence)
        assertEquals("", buildProteinView(DNA6).sequence)

        // ── 物化属性 ──
        run {
            val p = calculateProteinProperties(buildProteinView(DNA1).sequence)
assertEquals(87, p.length)
            assertEquals(8541.97, p.molecularWeight, 1e-9)
            assertEquals(4.5, p.isoelectricPoint, 1e-9)
            assertEquals(13325.0, p.extinctionCoeff, 1e-9)
            assertEquals(0.218, p.aromaticity, 1e-9)
            assertEquals(0.0, p.instability, 1e-9)
            assertEquals(0.031, p.gravy, 1e-9)
            assertEquals(11.2, p.charge, 1e-9)
        }
        run {
            val p = calculateProteinProperties(buildProteinView(DNA2).sequence)
assertEquals(8, p.length)
            assertEquals(854.49, p.molecularWeight, 1e-9)
            assertEquals(8.0, p.isoelectricPoint, 1e-9)
            assertEquals(0.0, p.extinctionCoeff, 1e-9)
            assertEquals(0.0, p.aromaticity, 1e-9)
            assertEquals(-0.22, p.instability, 1e-9)
            assertEquals(-2.387, p.gravy, 1e-9)
            assertEquals(4.0, p.charge, 1e-9)
        }
        run {
            val p = calculateProteinProperties(buildProteinView(DNA3).sequence)
assertEquals(6, p.length)
            assertEquals(662.15, p.molecularWeight, 1e-9)
            assertEquals(5.5, p.isoelectricPoint, 1e-9)
            assertEquals(0.0, p.extinctionCoeff, 1e-9)
            assertEquals(0.0, p.aromaticity, 1e-9)
            assertEquals(-0.2, p.instability, 1e-9)
            assertEquals(-2.6, p.gravy, 1e-9)
            assertEquals(-5.0, p.charge, 1e-9)
        }
        run {
            val p = calculateProteinProperties(buildProteinView(DNA5).sequence)
assertEquals(0, p.length)
            assertEquals(0.0, p.molecularWeight, 1e-9)
            assertEquals(7.0, p.isoelectricPoint, 1e-9)
            assertEquals(0.0, p.extinctionCoeff, 1e-9)
            assertEquals(0.0, p.aromaticity, 1e-9)
            assertEquals(0.0, p.instability, 1e-9)
            assertEquals(0.0, p.gravy, 1e-9)
            assertEquals(0.0, p.charge, 1e-9)
        }

        // ── PTM（类型+顺序）──
        val ptms1 = predictPTMs(buildProteinView(DNA1).sequence)
        val expPtms1 = listOf(
            PTM(PtmType.PHOSPHORYLATION, 82, "S", "Proline-directed", 0.7),
            PTM(PtmType.PHOSPHORYLATION, 69, "R", "PKA", 0.7),
            PTM(PtmType.GLYCOSYLATION, 8, "N", "N-X-S/T (N-linked)", 0.85),
            PTM(PtmType.UBIQUITINATION, 3, "K", "Lysine", 0.3),
            PTM(PtmType.DISULFIDE, 13, "C", "Cysteine", 0.5),
            PTM(PtmType.UBIQUITINATION, 24, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 28, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 32, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 35, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 42, "K", "Lysine", 0.3),
            PTM(PtmType.DISULFIDE, 49, "C", "Cysteine", 0.5),
            PTM(PtmType.DISULFIDE, 51, "C", "Cysteine", 0.5),
            PTM(PtmType.UBIQUITINATION, 56, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 59, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 64, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 68, "K", "Lysine", 0.3),
            PTM(PtmType.UBIQUITINATION, 73, "K", "Lysine", 0.3),
        )
        assertEquals(expPtms1.size, ptms1.size)
        expPtms1.zip(ptms1).forEach { (e, a) ->
            assertEquals(e.type, a.type); assertEquals(e.position, a.position)
            assertEquals(e.residue, a.residue); assertEquals(e.motif, a.motif); assertEquals(e.confidence, a.confidence, 1e-12)
        }
        assertEquals(0, predictPTMs(buildProteinView(DNA4).sequence).size)

        // ── 密码子频率表（aaMap 缺 ATG → X quirk；按 aa 升序/fraction 降序）──
        val codons1 = calculateCodonFrequencies(DNA1)
        val expCodons1 = listOf(
            CF("TAA", "*", 3, 1.0, 34.48275862068965),
            CF("GCC", "A", 1, 1.0, 11.494252873563218),
            CF("TGC", "C", 2, 0.6666666666666666, 22.988505747126435),
            CF("TGT", "C", 1, 0.3333333333333333, 11.494252873563218),
            CF("GAT", "D", 2, 1.0, 22.988505747126435),
            CF("GAA", "E", 2, 1.0, 22.988505747126435),
            CF("TTT", "F", 8, 0.7272727272727273, 91.95402298850574),
            CF("TTC", "F", 3, 0.2727272727272727, 34.48275862068965),
            CF("GGT", "G", 3, 0.5, 34.48275862068965),
            CF("GGC", "G", 2, 0.3333333333333333, 22.988505747126435),
            CF("GGG", "G", 1, 0.16666666666666666, 11.494252873563218),
            CF("CAT", "H", 1, 0.5, 11.494252873563218),
            CF("CAC", "H", 1, 0.5, 11.494252873563218),
            CF("ATT", "I", 4, 0.6666666666666666, 45.97701149425287),
            CF("ATC", "I", 1, 0.16666666666666666, 11.494252873563218),
            CF("ATA", "I", 1, 0.16666666666666666, 11.494252873563218),
            CF("AAA", "K", 9, 0.8181818181818182, 103.44827586206897),
            CF("AAG", "K", 2, 0.18181818181818182, 22.988505747126435),
            CF("TTA", "L", 3, 0.375, 34.48275862068965),
            CF("CTT", "L", 3, 0.375, 34.48275862068965),
            CF("CTG", "L", 1, 0.125, 11.494252873563218),
            CF("TTG", "L", 1, 0.125, 11.494252873563218),
            CF("AAC", "N", 2, 1.0, 22.988505747126435),
            CF("CCT", "P", 2, 1.0, 22.988505747126435),
            CF("CAA", "Q", 2, 1.0, 22.988505747126435),
            CF("AGG", "R", 2, 0.5, 22.988505747126435),
            CF("CGC", "R", 1, 0.25, 11.494252873563218),
            CF("CGT", "R", 1, 0.25, 11.494252873563218),
            CF("TCA", "S", 2, 0.5, 22.988505747126435),
            CF("TCT", "S", 1, 0.25, 11.494252873563218),
            CF("TCG", "S", 1, 0.25, 11.494252873563218),
            CF("ACA", "T", 3, 0.6, 34.48275862068965),
            CF("ACC", "T", 1, 0.2, 11.494252873563218),
            CF("ACT", "T", 1, 0.2, 11.494252873563218),
            CF("GTG", "V", 1, 0.3333333333333333, 11.494252873563218),
            CF("GTC", "V", 1, 0.3333333333333333, 11.494252873563218),
            CF("GTT", "V", 1, 0.3333333333333333, 11.494252873563218),
            CF("TGG", "W", 1, 1.0, 11.494252873563218),
            CF("ATG", "X", 4, 1.0, 45.97701149425287),
            CF("TAT", "Y", 4, 0.8, 45.97701149425287),
            CF("TAC", "Y", 1, 0.2, 11.494252873563218),
        )
        assertEquals(expCodons1.size, codons1.size)
        expCodons1.zip(codons1).forEach { (e, a) ->
            assertEquals(e.codon, a.codon); assertEquals(e.aa, a.aa); assertEquals(e.count, a.count)
            assertEquals(e.fraction, a.fraction, 1e-12); assertEquals(e.perThousand, a.perThousand, 1e-9)
        }
        val codons4 = calculateCodonFrequencies(DNA4)
        val expCodons4 = listOf(
            CF("GGA", "G", 1, 1.0, 166.66666666666666),
            CF("TTA", "L", 1, 1.0, 166.66666666666666),
            CF("AAT", "N", 1, 1.0, 166.66666666666666),
            CF("CGA", "R", 1, 1.0, 166.66666666666666),
            CF("TGG", "W", 1, 1.0, 166.66666666666666),
            CF("ATG", "X", 1, 1.0, 166.66666666666666),
        )
        expCodons4.zip(codons4).forEach { (e, a) ->
            assertEquals(e.codon, a.codon); assertEquals(e.aa, a.aa); assertEquals(e.count, a.count)
            assertEquals(e.fraction, a.fraction, 1e-12); assertEquals(e.perThousand, a.perThousand, 1e-9)
        }

        // ── 氨基酸逐碱基展开（aa.ts 蛋白序列语义：每 aa 3 条目，sequenceIndex=idx*3+pos）──
        val data1 = buildProteinView(DNA1).aminoAcidData
        assertEquals(261, data1.size)
        val expHead = listOf(AaBaseData("M", 0, 0, 0), AaBaseData("M", 1, 0, 1), AaBaseData("M", 2, 0, 2), AaBaseData("V", 0, 1, 3), AaBaseData("V", 1, 1, 4), AaBaseData("V", 2, 1, 5))
        val expTail = listOf(AaBaseData("F", 0, 86, 258), AaBaseData("F", 1, 86, 259), AaBaseData("F", 2, 86, 260))
        expHead.zip(data1.take(6)).forEach { (e, a) -> assertEquals(e, a) }
        expTail.zip(data1.takeLast(3)).forEach { (e, a) -> assertEquals(e, a) }

        // ── 三/单字母互转 ──
        assertEquals("Met-Lys-Arg-Pro-Lys-Lys-Pro-Pro", toThreeLetter(buildProteinView(DNA2).sequence))
        assertEquals("MKRPKKPP", toOneLetter(toThreeLetter(buildProteinView(DNA2).sequence)))
        assertEquals("Xxx-Xxx-Xxx", toThreeLetter("BJX"))
        assertEquals("XX", toOneLetter("Zzz-Bj"))

        // ── formatMw ──
        assertEquals("999.4 Da", formatMw(999.4))
        assertEquals("1.0 kDa", formatMw(1000.5))
        assertEquals("15.3 kDa", formatMw(15320.87))
    }
}
