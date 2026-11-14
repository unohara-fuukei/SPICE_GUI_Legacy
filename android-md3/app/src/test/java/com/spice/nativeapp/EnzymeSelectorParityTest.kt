package com.spice.nativeapp

import com.spice.nativeapp.core.EnzymeMethylation
import com.spice.nativeapp.core.EnzymeMethylation.Region
import com.spice.nativeapp.core.EnzymeMethylation.Status
import com.spice.nativeapp.core.EnzymeSelector
import com.spice.nativeapp.core.EnzymesVe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * main EnzymeSelector 算法层 parity（对照 tsx 直跑 SPICE_main 真实 enzymes.ts 模块 + 内联
 * +page.svelte:2001 restrictionSites 推导输出的 /tmp/enz_ref.json）：
 * - findMethylatedRegions：Dam GATC / Dcm CC[AT]GG / EcoKI 双向基序扫描，线性 + 环状 %len 归一
 * - checkCutsiteMethylation：ClaI/XbaI 上下文特判 / MboI 全阻断 / DpnI required 分支 /
 *   StuI Dcm 阻断 / 无敏感表酶恒 NONE
 * - getEnzymeBufferCompatibility：命中 + 大小写不敏感 + 未知回退 + SmaI/AarI 非默认缓冲液
 * - getEnzymeColor：名称哈希 pix 10 色板
 * - restrictionSites：COMMON ∪ selected → OVE 引擎 → Dam/Dcm/EcoKI 位点集重叠标注 → pos 排序
 * - getCutsitesFromSequenceFlat methylationStatus 引擎级端到端（14 切点）
 *
 * 平台差异（披露）：parity 场景选两侧 bundled 库均可解析的酶（ClaI/MboI/DpnI/XbaI/TaqI/StuI）；
 * main 浏览器态 REBASE 同步后 enzymeDatabase 兜底可解析 EcoKI/EcoRII 等，安卓侧以 EnzymeDb
 * 全库等价该兜底（既有 REBASE 口径披露）。
 */
class EnzymeSelectorParityTest {

    @Test
    fun methylatedRegionsParity() {
        run { // lin
            val regions = EnzymeMethylation.findMethylatedRegions("TTTGATCCCATGGGATCAACGAGTGCAGTGCAGTTT", circular = false)
            assertEquals(2, regions.size)
            assertTrue(regions.any { it.start == 3 && it.end == 6 && it.type == "Dam" && it.motif == "GATC" })
            assertTrue(regions.any { it.start == 13 && it.end == 16 && it.type == "Dam" && it.motif == "GATC" })
        }
        run { // circ
            val regions = EnzymeMethylation.findMethylatedRegions("GATCGATCCAGGCCTGGGTGATCAACGGGGGTGCAGTTGCACGGGGGGGTT", circular = true)
            assertEquals(6, regions.size)
            assertTrue(regions.any { it.start == 0 && it.end == 3 && it.type == "Dam" && it.motif == "GATC" })
            assertTrue(regions.any { it.start == 4 && it.end == 7 && it.type == "Dam" && it.motif == "GATC" })
            assertTrue(regions.any { it.start == 19 && it.end == 22 && it.type == "Dam" && it.motif == "GATC" })
            assertTrue(regions.any { it.start == 7 && it.end == 11 && it.type == "Dcm" && it.motif == "CCAGG" })
            assertTrue(regions.any { it.start == 12 && it.end == 16 && it.type == "Dcm" && it.motif == "CCTGG" })
            assertTrue(regions.any { it.start == 38 && it.end == 50 && it.type == "EcoKI" && it.motif == "GCACGGGGGGGTT" })
        }
        run { // empty
            val regions = EnzymeMethylation.findMethylatedRegions("", circular = true)
            assertEquals(0, regions.size)
        }
    }

    @Test
    fun methylationStatusParity() {
        val seq = "GGATCGATCCAGGCCTGGTCTAGATCGATCTAACGAGTGCAGTGCACCAGGTAACGGGGGTGCAGTGC"
        val regions = EnzymeMethylation.findMethylatedRegions(seq, circular = true)
        run { // claiBlockedPrevG
            val s = seq.indexOf("GATCGAT")
            val st = EnzymeMethylation.checkCutsiteMethylation("ClaI", s, s + 7 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dam", st.type)
            assertEquals("GATC", st.motif)
        }
        run { // claiNotBlocked
            val s = seq.indexOf("ATCGATC")
            val st = EnzymeMethylation.checkCutsiteMethylation("ClaI", s, s + 7 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dam", st.type)
            assertEquals("GATC", st.motif)
        }
        run { // mboiAnyOverlap
            val s = seq.indexOf("GATC")
            val st = EnzymeMethylation.checkCutsiteMethylation("MboI", s, s + 4 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dam", st.type)
            assertEquals("GATC", st.motif)
        }
        run { // dpniMethylated
            val s = seq.indexOf("GATC")
            val st = EnzymeMethylation.checkCutsiteMethylation("DpnI", s, s + 4 - 1, seq, circular = true, regions)
            assertEquals(false, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertNull(st.type)
            assertNull(st.motif)
        }
        run { // xbaiBlockedNext
            val s = seq.indexOf("TCTAGATC")
            val st = EnzymeMethylation.checkCutsiteMethylation("XbaI", s, s + 8 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dam", st.type)
            assertEquals("GATC", st.motif)
        }
        run { // taqiBlocked
            val s = seq.indexOf("GATCGA")
            val st = EnzymeMethylation.checkCutsiteMethylation("TaqI", s, s + 6 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dam", st.type)
            assertEquals("GATC", st.motif)
        }
        run { // ecor2Blocked
            val s = seq.indexOf("CCAGG")
            val st = EnzymeMethylation.checkCutsiteMethylation("EcoRII", s, s + 5 - 1, seq, circular = true, regions)
            assertEquals(false, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertNull(st.type)
            assertNull(st.motif)
        }
        run { // stuiBlocked
            val s = seq.indexOf("CCAGGCCTGG")
            val st = EnzymeMethylation.checkCutsiteMethylation("StuI", s, s + 10 - 1, seq, circular = true, regions)
            assertEquals(true, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertEquals("Dcm", st.type)
            assertEquals("CCAGG", st.motif)
        }
        run { // ecokiBlocked
            val s = seq.indexOf("AACGAGTGCAGTGC")
            val st = EnzymeMethylation.checkCutsiteMethylation("EcoKI", s, s + 14 - 1, seq, circular = true, regions)
            assertEquals(false, st.isBlocked)
            assertEquals(false, st.requiredButUnmethylated)
            assertNull(st.type)
            assertNull(st.motif)
        }
    }

    @Test
    fun bufferCompatibilityParity() {
        run { // ecri
            val bc = EnzymeSelector.bufferCompatibility("EcoRI")
            assertEquals(100, bc.cutSmart)
            assertEquals(25, bc.r1_1)
            assertEquals(100, bc.r2_1)
            assertEquals(100, bc.r3_1)
            assertEquals("CutSmart", bc.preferredBuffer)
        }
        run { // caseIns
            val bc = EnzymeSelector.bufferCompatibility("eCoRi")
            assertEquals(100, bc.cutSmart)
            assertEquals(25, bc.r1_1)
            assertEquals(100, bc.r2_1)
            assertEquals(100, bc.r3_1)
            assertEquals("CutSmart", bc.preferredBuffer)
        }
        run { // unknown
            val bc = EnzymeSelector.bufferCompatibility("ZzUnknownI")
            assertEquals(100, bc.cutSmart)
            assertEquals(75, bc.r1_1)
            assertEquals(100, bc.r2_1)
            assertEquals(75, bc.r3_1)
            assertEquals("CutSmart", bc.preferredBuffer)
        }
        run { // smai
            val bc = EnzymeSelector.bufferCompatibility("SmaI")
            assertEquals(50, bc.cutSmart)
            assertEquals(50, bc.r1_1)
            assertEquals(25, bc.r2_1)
            assertEquals(10, bc.r3_1)
            assertEquals("Buffer 1.1", bc.preferredBuffer)
        }
        run { // aari
            val bc = EnzymeSelector.bufferCompatibility("AarI")
            assertEquals(100, bc.cutSmart)
            assertEquals(10, bc.r1_1)
            assertEquals(50, bc.r2_1)
            assertEquals(100, bc.r3_1)
            assertEquals("Buffer 3.1", bc.preferredBuffer)
        }
    }

    @Test
    fun enzymeColorParity() {
        assertEquals("#b48cff", EnzymeSelector.colorOf("EcoRI"))
        assertEquals("#53d7a9", EnzymeSelector.colorOf("BamHI"))
        assertEquals("#ff5df6", EnzymeSelector.colorOf("HindIII"))
        assertEquals("#ff5df6", EnzymeSelector.colorOf("XhoI"))
        assertEquals("#ffd23f", EnzymeSelector.colorOf("NdeI"))
        assertEquals("#ffd23f", EnzymeSelector.colorOf("SacI"))
        assertEquals("#ff9f1c", EnzymeSelector.colorOf("SalI"))
        assertEquals("#ff5d5d", EnzymeSelector.colorOf("KpnI"))
        assertEquals("#ff9f1c", EnzymeSelector.colorOf("BsaI"))
        assertEquals("#ff9e9e", EnzymeSelector.colorOf("NotI"))
        assertEquals("#53d7a9", EnzymeSelector.colorOf("EcoO109I"))
        assertEquals("#b48cff", EnzymeSelector.colorOf("Sse8387I"))
        assertEquals("#53d769", EnzymeSelector.colorOf("A"))
    }

    @Test
    fun restrictionSitesParity() {
        run { // demo
            val sites = EnzymeSelector.restrictionSites("GGATCGATCCAGGCCTGGTCTAGATCGATCTAACGAGTGCAGTGCACGATCGAATTCGGG", listOf("ClaI", "MboI", "XbaI", "TaqI", "DpnI", "StuI").toSet())
            assertEquals(19, sites.size)
            assertTrue(sites.any { it.name == "MboI" && it.pos == 2 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "DpnI" && it.pos == 4 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "ClaI" && it.pos == 5 && it.seq == "ATCGAT" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "TaqI" && it.pos == 5 && it.seq == "TCGA" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "MboI" && it.pos == 6 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam/Dcm" })
            assertTrue(sites.any { it.name == "DpnI" && it.pos == 8 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam/Dcm" })
            assertTrue(sites.any { it.name == "HaeIII" && it.pos == 14 && it.seq == "GGCC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dcm" })
            assertTrue(sites.any { it.name == "StuI" && it.pos == 14 && it.seq == "AGGCCT" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dcm" })
            assertTrue(sites.any { it.name == "XbaI" && it.pos == 20 && it.seq == "TCTAGA" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "MboI" && it.pos == 23 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "DpnI" && it.pos == 25 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "ClaI" && it.pos == 26 && it.seq == "ATCGAT" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "TaqI" && it.pos == 26 && it.seq == "TCGA" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "MboI" && it.pos == 27 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "DpnI" && it.pos == 29 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "MboI" && it.pos == 48 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "DpnI" && it.pos == 50 && it.seq == "GATC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "TaqI" && it.pos == 51 && it.seq == "TCGA" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
            assertTrue(sites.any { it.name == "EcoRI" && it.pos == 53 && it.seq == "GAATTC" && it.color == "#b48cff" && it.methylated == false && it.methylationType == null })
        }
        run { // plain
            val sites = EnzymeSelector.restrictionSites("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCATGCGGCTTAAAGGTC", listOf("EcoRI", "BamHI").toSet())
            assertEquals(2, sites.size)
            assertTrue(sites.any { it.name == "AflII" && it.pos == 32 && it.seq == "CTTAAG" && it.color == "#ff9f1c" && it.methylated == false && it.methylationType == null })
            assertTrue(sites.any { it.name == "BamHI" && it.pos == 43 && it.seq == "GGATCC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
        }
        run { // emptySel
            val sites = EnzymeSelector.restrictionSites("ATGCGGCTTAAAGGTCCGATCGTAGCTAGGCTTAAGGCATCGGATCCATGCGGCTTAAAGGTC", emptySet<String>())
            assertEquals(2, sites.size)
            assertTrue(sites.any { it.name == "AflII" && it.pos == 32 && it.seq == "CTTAAG" && it.color == "#ff9f1c" && it.methylated == false && it.methylationType == null })
            assertTrue(sites.any { it.name == "BamHI" && it.pos == 43 && it.seq == "GGATCC" && it.color == "var(--pix-red)" && it.methylated == true && it.methylationType == "Dam" })
        }
    }

    @Test
    fun flatCutsiteMethylationParity() {
        val seq = "GGATCGATCCAGGCCTGGTCTAGATCGATCTAACGAGTGCAGTGC"
        val enzymes = listOf("ClaI", "MboI", "DpnI", "XbaI", "TaqI", "StuI").mapNotNull { EnzymeSelector.resolveEnzymeFor(it) }
        val flat = EnzymesVe.getCutsitesFromSequenceFlat(seq, circular = true, enzymes)
        assertEquals(14, flat.size)
        assertEquals(true, flat[0].methylationStatus?.isBlocked)
        assertEquals(false, flat[0].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[0].methylationStatus?.type)
        assertEquals("GATC", flat[0].methylationStatus?.motif)
        assertEquals("ClaI", flat[0].name)
        assertEquals(true, flat[1].methylationStatus?.isBlocked)
        assertEquals(false, flat[1].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[1].methylationStatus?.type)
        assertEquals("GATC", flat[1].methylationStatus?.motif)
        assertEquals("ClaI", flat[1].name)
        assertEquals(true, flat[2].methylationStatus?.isBlocked)
        assertEquals(false, flat[2].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[2].methylationStatus?.type)
        assertEquals("GATC", flat[2].methylationStatus?.motif)
        assertEquals("MboI", flat[2].name)
        assertEquals(true, flat[3].methylationStatus?.isBlocked)
        assertEquals(false, flat[3].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[3].methylationStatus?.type)
        assertEquals("GATC", flat[3].methylationStatus?.motif)
        assertEquals("MboI", flat[3].name)
        assertEquals(true, flat[4].methylationStatus?.isBlocked)
        assertEquals(false, flat[4].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[4].methylationStatus?.type)
        assertEquals("GATC", flat[4].methylationStatus?.motif)
        assertEquals("MboI", flat[4].name)
        assertEquals(true, flat[5].methylationStatus?.isBlocked)
        assertEquals(false, flat[5].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[5].methylationStatus?.type)
        assertEquals("GATC", flat[5].methylationStatus?.motif)
        assertEquals("MboI", flat[5].name)
        assertEquals(false, flat[6].methylationStatus?.isBlocked)
        assertEquals(false, flat[6].methylationStatus?.requiredButUnmethylated)
        assertNull(flat[6].methylationStatus?.type)
        assertNull(flat[6].methylationStatus?.motif)
        assertEquals("DpnI", flat[6].name)
        assertEquals(false, flat[7].methylationStatus?.isBlocked)
        assertEquals(false, flat[7].methylationStatus?.requiredButUnmethylated)
        assertNull(flat[7].methylationStatus?.type)
        assertNull(flat[7].methylationStatus?.motif)
        assertEquals("DpnI", flat[7].name)
        assertEquals(false, flat[8].methylationStatus?.isBlocked)
        assertEquals(false, flat[8].methylationStatus?.requiredButUnmethylated)
        assertNull(flat[8].methylationStatus?.type)
        assertNull(flat[8].methylationStatus?.motif)
        assertEquals("DpnI", flat[8].name)
        assertEquals(false, flat[9].methylationStatus?.isBlocked)
        assertEquals(false, flat[9].methylationStatus?.requiredButUnmethylated)
        assertNull(flat[9].methylationStatus?.type)
        assertNull(flat[9].methylationStatus?.motif)
        assertEquals("DpnI", flat[9].name)
        assertEquals(true, flat[10].methylationStatus?.isBlocked)
        assertEquals(false, flat[10].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[10].methylationStatus?.type)
        assertEquals("GATC", flat[10].methylationStatus?.motif)
        assertEquals("XbaI", flat[10].name)
        assertEquals(true, flat[11].methylationStatus?.isBlocked)
        assertEquals(false, flat[11].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[11].methylationStatus?.type)
        assertEquals("GATC", flat[11].methylationStatus?.motif)
        assertEquals("TaqI", flat[11].name)
        assertEquals(true, flat[12].methylationStatus?.isBlocked)
        assertEquals(false, flat[12].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dam", flat[12].methylationStatus?.type)
        assertEquals("GATC", flat[12].methylationStatus?.motif)
        assertEquals("TaqI", flat[12].name)
        assertEquals(true, flat[13].methylationStatus?.isBlocked)
        assertEquals(false, flat[13].methylationStatus?.requiredButUnmethylated)
        assertEquals("Dcm", flat[13].methylationStatus?.type)
        assertEquals("CCAGG", flat[13].methylationStatus?.motif)
        assertEquals("StuI", flat[13].name)
    }
}
