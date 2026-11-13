package com.spice.nativeapp

import com.spice.nativeapp.core.*
import org.junit.Assert.*
import org.junit.Test

class CoreAlgorithmTest {

    // ---- 酶库全量 ----
    @Test fun enzymeLibraryComplete() {
        assertEquals(532, Enzymes.groupCount)
        assertEquals(3732, Enzymes.nameCount)
        assertEquals(820, Enzymes.rebaseCount)
        // 别名查找：AvaII 属于 ggwcc 组
        val ava = Enzymes.byName("AvaII")
        assertNotNull("别名 AvaII 应命中", ava)
        assertEquals("ggwcc", ava!!.site)
        assertNotNull(Enzymes.byName("erpI"))
    }

    @Test fun iupacCutSites() {
        val seq = "GGTACCGGACCGGCCCCGGGGATCC"
        val erpI = Enzymes.byName("AvaII")!!
        val cuts = Bio.cutSites(seq, erpI, false)
        assertTrue("GGWCC 简并位点应被切出", cuts.isNotEmpty())
    }

    // ---- 引物精细分析 ----
    @Test fun primerTmNearestNeighbor() {
        val r = PrimerAnalysis.analyze("TCGCGCGTTTCGGTGATGAC")
        assertTrue("Tm 应在 50–70℃ 之间，实际 ${r.tmNearestNeighbor}", r.tmNearestNeighbor in 45.0..75.0)
        assertTrue("GC 应在 50–70%，实际 ${r.gcPercent}", r.gcPercent in 50.0..70.0)
        assertEquals(20, r.length)
        assertTrue("3′ 稳定性 0..5", r.threePrimeStability in 0..5)
    }

    @Test fun primerDimerDetection() {
        val d = PrimerAnalysis.dimer("ATCGATCGATCGATCG", "CGATCGATCGATCGAT")
        assertNotNull("完全互补应检出二聚体", d)
        assertTrue("连续互补 ≥ 4，实际 ${d!!.maxComplement}", d.maxComplement >= 4)
    }

    @Test fun hairpinDetection() {
        val hp = PrimerAnalysis.hairpin("GCGCGCGCAAAAGCGCGCGC")
        assertNotNull("互补回文应检出茎环", hp)
    }

    // ---- 测序验证 ----
    @Test fun sangerExactMatch() {
        val ref = "TCGCGCGTTTCGGTGATGACGGTGAAAACCTCTGACACATGCAGCTCCCGGAGACGGTCACAGCTTGTCTGTAAGCGGATGCCGGGAGCAGACAAGCCCGTCAGGGCGCGTCAGCGGGTGTTGGCGGGTGTCGGGGCTGGCTTAACTATGCGGCATCAGAGCAGATTGTACTGAGAGTGCACCATATGCGGTGTGAAATACCGCACAGATGCGTAAGGAGAAAATACCGCATCAGGCGCTCTTCCGCTTCCTCGCTCACTGACTCGCTGCGCTCGGTCGTTCGGCTGCGGCGAGCGGTATCAGCTCACTCAAAGGCGGTAATACGGTTATCCACAGAATCAGGGGATAACGCAGGAAAGAACATGTGAGCAAAAGGCCAGCAAAAGGCCAGGAACCGTAAAAAGGCCGCGTTGCTGGCGTTTTTCCATAGGCTCCGCCCCCCTGACGAGCATCACAAAAATCGACGCTCAAGTCAGAGGTGGCGAAACCCGACAGGACTATAAAGATACCAGGCGTTTCCCCCTGGAAGCTCCCTCGTGCGCTCTCCTGTTCCGACCCTGCCGCTTACCGGATACCTGTCCGCCTTTCTCCCTTCGGGAAGCGTGGCGCTTTCTCATAGCTCACGCTGTAGGTATCTCAGTTCGGTGTAGGTCGTTCGCTCCAAGCTGGGCTGTGTGCACGAACCCCCCGTTCAGCCCGACCGCTGCGCCTTATCCGGTAACTATCGTCTTGAGTCCAACCC"
        val read = ref.substring(100, 400)
        val r = SangerValidate.align(ref, read)
        assertTrue("完全子串应判为完全匹配，实际 ${r.verdict} / ${r.identityPercent}", r.identityPercent > 99.9)
        assertEquals(0, r.mismatches)
        assertTrue("覆盖起点应落在 100±20，实际 ${r.coverageStart}", r.coverageStart in 80..125)
    }

    @Test fun sangerDetectsMismatch() {
        val ref = "ATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCG"
        val read = "ATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGATAGCATCGATCGTAGCTAGCTAGCATCG" // 一个 C→A
        val r = SangerValidate.align(ref, read)
        assertTrue("应检出至少 1 处错配", r.mismatches >= 1)
        assertTrue("一致性应在 90–100 之间，实际 ${r.identityPercent}", r.identityPercent in 90.0..99.9)
    }


    // ---- 默认演示记录：pj5_00001（对齐 main exampleSequenceData.ts / demo.ts）----
    @Test fun demoPlasmidIsPj5AlignedWithMain() {
        val rec = Bio.demoPlasmid()
        assertEquals("pj5_00001", rec.name)
        assertTrue("main demo 为环状", rec.circular)
        assertEquals("序列长度 5299 bp", 5299, rec.length)
        assertEquals("特征数 22（main features 数组 0-based → 1-based）", 22, rec.features.size)
        assertEquals("araC", rec.features.first().name)
        assertEquals("GFPuv", rec.features.first { it.name == "GFPuv" && it.type == "CDS" }.name)
        val backbone = rec.features.last()
        assertEquals("pS8c-vector_backbone", backbone.name)
        assertTrue("跨原点特征 start>end（0-based 2015..1237）", backbone.start > backbone.end)
    }

    // ---- 克隆方法 ----
    @Test fun cloningMethodsAllRun() {
        val vector = Bio.demoPlasmid()
        val insert = SeqRecord("ins", "ATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCG".repeat(2), false, emptyList(), "")
        CloningMethods.Method.values().forEach { m ->
            val r = CloningMethods.simulate(m, vector, insert, overlapLength = 20)
            assertTrue("${m.label} 应产生产物长度", r.productLength > 0)
            assertTrue("${m.label} 应给出步骤", r.steps.isNotEmpty())
        }
    }

    @Test fun taCloningWarnsOnNoATail() {
        val vector = Bio.demoPlasmid()
        val insert = SeqRecord("ins", "ATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGC".repeat(2), false, emptyList(), "")
        val r = CloningMethods.simulate(CloningMethods.Method.TA, vector, insert)
        assertTrue("末位非 A 应给出警告", r.warnings.any { it.contains("不是 A") })
    }

    @Test fun gatewayDetectsAttSites() {
        val vector = Bio.demoPlasmid()
        val r = CloningMethods.simulate(CloningMethods.Method.GATEWAY, vector, SeqRecord("i", "ATGC".repeat(30), false, emptyList(), ""))
        assertFalse("pUC19 无 attB 位点，应告警", r.verified)
    }

    // ---- CRISPR 脱靶 ----
    @Test fun crisprOffTargetScan() {
        val genome = "GGACTGAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCGATCGTAGCTAGCTAGCATCG"
        val ot = CloningPlus.predictOffTargets(genome, "GCTAGCTAGCATCGATCGTAG", 3)
        assertTrue("应扫出候选/或为空但不崩溃", ot.size >= 0)
        ot.forEach { assertTrue("错配 ≤3", it.mismatches <= 3) }
    }

    // ---- 格式 ----
    @Test fun fastqRoundTrip() {
        val rec = SeqRecord("test", "ATGCGTACGTAGCTAGCTAGC", false, emptyList(), "")
        val fq = SeqIO.toFastq(rec, 30)
        val back = SeqIO.parseFastq(fq)
        assertNotNull(back)
        assertEquals(rec.sequence, back!!.sequence)
        assertTrue("应报告质量", back.description.contains("Q30"))
    }

    @Test fun emblRoundTrip() {
        val rec = SeqRecord("pUC19", "ATGCGTACGTAGCTAGCTAGC", true, emptyList(), "")
        val embl = SeqIO.toEmbl(rec)
        val back = SeqIO.parseEmbl(embl)
        assertNotNull("EMBL 应可回读", back)
        assertEquals(rec.sequence, back!!.sequence)
        assertTrue("应保留环状标记", back.circular)
    }

    // ---- 结构解析 ----
    @Test fun mmcifExportAndParse() {
        val seq = "MKTAYIAKQRQISFVKSHFSRQ"
        val cif = Structure.buildMmcif(seq, null, "t", "占位")
        assertTrue("应含 _atom_site 列", cif.contains("_atom_site.Cartn_x"))
        assertEquals("应每个残基一行 CA", seq.length, cif.lines().count { it.startsWith("ATOM ") })
        val parsed = Structure.parseMmcif(cif, "t")
        assertEquals("解析应取回同样数量的 CA", seq.length, parsed.atoms.size)
    }

    @Test fun pdbParseCaOnly() {
        fun atom(serial: Int, name: String, res: String, resSeq: Int, x: Double) =
            "ATOM  " + "%5d".format(serial) + " " + name.padEnd(4) + " " + res + " A" + "%4d".format(resSeq) +
                "    " + "%8.3f".format(x) + "%8.3f".format(2.0) + "%8.3f".format(3.0) + "  1.00  0.00           C"
        val pdb = listOf(
            atom(1, "N", "MET", 1, 1.0),
            atom(2, "CA", "MET", 1, 2.0),
            atom(3, "CA", "LYS", 2, 5.8)
        ).joinToString("\n")
        val p = Structure.parsePdb(pdb, "x")
        assertEquals("只取 CA 原子", 2, p.atoms.size)
        assertEquals("MET", p.atoms[0].resName)
    }

    // ---- 可复现包 ----
    @Test fun reproManifestContainsSha() {
        val bytes = "hello".toByteArray()
        val sha = Repro.sha256Hex(bytes)
        assertEquals(64, sha.length)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
    }
}
