package com.spice.nativeapp

import com.spice.nativeapp.core.CloningSim
import com.spice.nativeapp.core.CloningSim.CloningInput
import com.spice.nativeapp.core.CloningSim.SimSequenceData
import com.spice.nativeapp.core.EnzymesVe
import com.spice.nativeapp.core.EnzymesVe.VeCutsite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CloningSimulator parity 测试 — 参考值由原版 SPICE_main（tsx 逐字 import 原版
 * cloningMethods.ts / enzymes.ts）生成：/tmp/cs_gen.ts → /tmp/cs_ref.json（27 用例）。
 * 断言 productName / productSeq / insertStart / insertEnd / warnings / features /
 * 切点引擎位置；不涉及 timestamp、id 等易变字段。
 */
class CloningSimParityTest {

    // ── 夹具（与 cs_gen.ts 完全一致） ──
    private val vRest = "CCCCGAATTCAAAAGTCGACTTTTGGTACCCGGGGG"
    private val vNoRight = "CCCCGAATTCAAAAGTCGACCCCGGGGG"
    private val iSalKpn = "GTCGACATGATGATGGGTACCGCGCGC"
    private val iMismatch = "TATATATATATATATATATATATATAT"
    private val vGibson = "A".repeat(25) + "G".repeat(10) + "C".repeat(25)
    private val iGibsonOk = "C".repeat(25) + "TTTTTT" + "A".repeat(25)
    private val iGibsonBad = "G".repeat(40)
    private val iInfusion = "C".repeat(15) + "AAATAA" + "A".repeat(15)
    private val iNeb = "C".repeat(20) + "AAATAA" + "A".repeat(20)
    private val vGg = "AAGGTCTCTAAAAAAAGGTCTCTTTTT"
    private val iGg = "TTGGTCTCTCCCCCCGGTCTCTGGGG"
    private val vAtt = "TTT" + "GCTTTTTTGTACAAAAAAGCAGGCT" + "GG" + "ACCCACTTTGTACAAGAAAGCTGGG" + "CCC"
    private val iAtt = "GCTTTTTTGTACAAAAAAGCAGGCT" + "ATGATG" + "ACCCACTTTGTACAAGAAAGCTGGG"
    private val vTopo = "ACGTGTGGACGTACGT"
    private val iCacc = "CACCATGGGGTTTAAA"
    private val vPlain = "ACGTACGTAC"

    private val zhT4 = "T4 连接酶警报: 载体与插入片段两端均处于去磷酸化状态！由于缺少 5' 磷酸基团，T4 DNA 连接酶将无法形成任何共价磷酸二酯键，克隆连接效率为 0%！建议使用 T4 PNK 激酶对插入片段进行磷酸化，或对载体进行磷酸化修饰。"

    private fun vec(seq: String) = SimSequenceData(sequence = seq, name = "pVec", circular = true)
    private fun ins(seq: String) = SimSequenceData(sequence = seq, name = "mTag", circular = false)

    // ── 酶解析：main defaultDb 成员资格 + 偏移 ──

    @Test
    fun enzymeDefinitions() {
        val expect = listOf(
            Triple("BsaI", 7 to 11, true), Triple("BsmBI", 7 to 11, true),
            Triple("BtgZI", 0 to 0, false), Triple("SapI", 8 to 11, true),
            Triple("AarI", 11 to 15, true), Triple("EcoRI", 1 to 5, true),
            Triple("SalI", 1 to 5, true), Triple("KpnI", 5 to 1, true),
            Triple("SacI", 5 to 1, true), Triple("NdeI", 2 to 4, true),
            Triple("BbsI", 8 to 12, true) // main 参考值：gaagac 8/12（BspBS31I 同义名）
        )
        for ((name, offs, found) in expect) {
            val e = EnzymesVe.getEnzymeByName(name)
            if (!found) {
                assertEquals(null, e) // BtgZI 不在 main defaultDb（Kotlin EnzymeDb 有同名主条目但被 1:1 门控拦截）
                continue
            }
            assertTrue("$name not found", e != null)
            assertEquals(name, e!!.name) // main：返回酶 name = 查询别名本身
            assertEquals(offs.first, e.topSnipOffset)
            assertEquals(offs.second, e.bottomSnipOffset)
        }
    }

    // ── 切点引擎 ──

    private fun cutsOf(seq: String, circular: Boolean, enzyme: String): List<VeCutsite> =
        EnzymesVe.cutSequenceByRestrictionEnzyme(seq, circular, EnzymesVe.getEnzymeByName(enzyme)!!)

    @Test
    fun cutEngineEcoRiLinear() {
        val cs = cutsOf(vRest, false, "EcoRI")
        assertEquals(1, cs.size)
        val c = cs[0]
        assertEquals(4, c.start); assertEquals(9, c.end)
        assertEquals(5, c.topSnipPosition); assertEquals(9, c.bottomSnipPosition)
        assertEquals(true, c.forward)
        assertEquals("AATT", c.overhangBps)
        assertEquals(4 to 9, c.recognitionSiteRange.start to c.recognitionSiteRange.end)
    }

    @Test
    fun cutEngineEcoRiCircular() {
        val cs = cutsOf(vRest, true, "EcoRI")
        assertEquals(1, cs.size)
        assertEquals(5, cs[0].topSnipPosition); assertEquals(9, cs[0].bottomSnipPosition)
    }

    @Test
    fun cutEngineSalI() {
        val cs = cutsOf(vRest, true, "SalI")
        assertEquals(1, cs.size)
        assertEquals(14, cs[0].start); assertEquals(19, cs[0].end)
        assertEquals(15, cs[0].topSnipPosition); assertEquals(19, cs[0].bottomSnipPosition)
        assertEquals("TCGA", cs[0].overhangBps)
    }

    @Test
    fun cutEngineKpnI3PrimeOverhang() {
        val cs = cutsOf(vRest, true, "KpnI")
        assertEquals(1, cs.size)
        assertEquals(24, cs[0].start); assertEquals(29, cs[0].end)
        assertEquals(29, cs[0].topSnipPosition); assertEquals(25, cs[0].bottomSnipPosition)
        assertEquals("GTAC", cs[0].overhangBps) // 3' 突出端
    }

    @Test
    fun cutEngineBsaIFirstTwoSites() {
        val cs = cutsOf(vGg, true, "BsaI")
        assertEquals(2, cs.size)
        assertEquals(9, cs[0].topSnipPosition); assertEquals(13, cs[0].bottomSnipPosition)
        assertEquals("AAAA", cs[0].overhangBps)
        assertEquals(23, cs[1].topSnipPosition); assertEquals(27, cs[1].bottomSnipPosition)
        assertEquals("TTTT", cs[1].overhangBps)
        val ics = cutsOf(iGg, false, "BsaI")
        assertEquals(2, ics.size)
        assertEquals(9, ics[0].topSnipPosition); assertEquals("CCCC", ics[0].overhangBps)
        assertEquals(22, ics[1].topSnipPosition); assertEquals("GGGG", ics[1].overhangBps)
    }

    @Test
    fun cutEngineSacINoSite() {
        assertEquals(0, cutsOf(vRest, true, "SacI").size)
    }

    @Test
    fun cutsitesFlatCount() {
        val flat = EnzymesVe.getCutsitesFromSequenceFlat(
            vRest, true,
            listOf("EcoRI", "SalI", "KpnI").map { EnzymesVe.getEnzymeByName(it)!! }
        )
        assertEquals(3, flat.size)
    }

    // ── 仿真：restriction ──

    @Test
    fun restrictionOk() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vRest), ins(iSalKpn), CloningSim.M_RESTRICTION,
                leftEnzyme = "SalI", rightEnzyme = "KpnI",
                vectorPhosphorLeft = true, vectorPhosphorRight = true,
                insertPhosphorLeft = true, insertPhosphorRight = true)
        )
        assertEquals("pVec_cloned_mTag", r.productName)
        assertEquals("CCCCGAATTCAAAAGTCGACATGATGATGGGTACCCGGGGG", r.product.sequence)
        assertEquals(15, r.insertStart); assertEquals(33, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
        assertEquals(1, r.product.features.size)
        val f = r.product.features[0]
        assertEquals("mTag", f.name); assertEquals("CDS", f.type)
        assertEquals(15, f.start); assertEquals(33, f.end); assertEquals(true, f.forward)
    }

    @Test
    fun restrictionMissingRightSite() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vNoRight), ins(iSalKpn), CloningSim.M_RESTRICTION,
                leftEnzyme = "SalI", rightEnzyme = "KpnI")
        )
        assertEquals("pVec", r.productName)
        assertEquals(vNoRight, r.product.sequence)
        assertEquals(0, r.insertStart); assertEquals(0, r.insertEnd)
        assertEquals(
            listOf("Right enzyme KpnI not found in vector", "Enzyme sites missing in vector or insert"),
            r.warnings
        )
    }

    @Test
    fun restrictionDephosWarning() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vRest), ins(iSalKpn), CloningSim.M_RESTRICTION,
                leftEnzyme = "SalI", rightEnzyme = "KpnI",
                vectorPhosphorLeft = false, vectorPhosphorRight = false,
                insertPhosphorLeft = false, insertPhosphorRight = false)
        )
        assertEquals("CCCCGAATTCAAAAGTCGACATGATGATGGGTACCCGGGGG", r.product.sequence)
        assertEquals(listOf(zhT4), r.warnings)
    }

    @Test
    fun restrictionInsertMismatch() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vRest), ins(iMismatch), CloningSim.M_RESTRICTION,
                leftEnzyme = "SalI", rightEnzyme = "KpnI")
        )
        assertEquals(vRest, r.product.sequence) // 错误时 product = 原 vector 直拷
        assertEquals(
            listOf("Left enzyme SalI not found in insert", "Right enzyme KpnI not found in insert",
                "Enzyme sites missing in vector or insert"),
            r.warnings
        )
    }

    // ── 仿真：overlap 组装（gibson / in_fusion / nebuilder） ──

    @Test
    fun gibsonOk() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGibson), ins(iGibsonOk), CloningSim.M_GIBSON, overlapLength = 25)
        )
        assertEquals("pVec_gibson_mTag", r.productName)
        assertEquals("A".repeat(25) + "G".repeat(10) + "TTTTTT" + "C".repeat(25), r.product.sequence)
        assertEquals(true, r.product.circular)
        assertEquals(35, r.insertStart); assertEquals(40, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun gibsonBothEndsMismatch() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGibson), ins(iGibsonBad), CloningSim.M_GIBSON, overlapLength = 25)
        )
        assertEquals(
            "A".repeat(25) + "G".repeat(10) + "G".repeat(40) + "C".repeat(25),
            r.product.sequence
        )
        assertEquals(35, r.insertStart); assertEquals(74, r.insertEnd)
        assertEquals(
            listOf("Insert 5' end does not share 25bp homology with vector 3' end",
                "Insert 3' end does not share 25bp homology with vector 5' start"),
            r.warnings
        )
    }

    @Test
    fun inFusionDefaultOverlap15() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGibson), ins(iInfusion), CloningSim.M_INFUSION)
        )
        assertEquals("A".repeat(25) + "G".repeat(10) + "C".repeat(10) + "AAATAA" + "C".repeat(15), r.product.sequence)
        assertEquals(45, r.insertStart); assertEquals(50, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun nebuilderDefaultOverlap20() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGibson), ins(iNeb), CloningSim.M_NEBUILDER_HIFI)
        )
        assertEquals("A".repeat(25) + "G".repeat(10) + "C".repeat(5) + "AAATAA" + "C".repeat(20), r.product.sequence)
        assertEquals(40, r.insertStart); assertEquals(45, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
    }

    // ── 仿真：golden gate ──

    @Test
    fun goldenGateOk() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGg), ins(iGg), CloningSim.M_GOLDEN_GATE, goldenGateEnzyme = "BsaI")
        )
        assertEquals("pVec_goldengate_mTag", r.productName)
        assertEquals("AAGGTCTCTCCCCCCGGTCTCTTTTT", r.product.sequence)
        assertEquals(9, r.insertStart); assertEquals(21, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun goldenGateInsufficientSites() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vRest), ins(iMismatch), CloningSim.M_GOLDEN_GATE, goldenGateEnzyme = "BsaI")
        )
        assertEquals(vRest, r.product.sequence)
        assertEquals(
            listOf("Vector has 0 BsaI sites (need >= 2)", "Insert has 0 BsaI sites (need >= 2)",
                "Insufficient BsaI sites"),
            r.warnings
        )
    }

    @Test
    fun goldenGateUnknownEnzymeNameResolvesEmpty() {
        // main resolveEnzyme 永不返回 null（enzymeFromSite 空位点回退）→ 0 切点 → Insufficient 路径
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vGg), ins(iGg), CloningSim.M_GOLDEN_GATE, goldenGateEnzyme = "BtgZI")
        )
        assertTrue(r.warnings.any { it.startsWith("Vector has 0 BtgZI sites") })
        assertTrue(r.warnings.contains("Insufficient BtgZI sites"))
    }

    // ── 仿真：gateway ──

    @Test
    fun gatewayStandardRecombination() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vAtt), ins(iAtt), CloningSim.M_GATEWAY)
        )
        assertEquals("pVec_gateway_mTag", r.productName)
        assertEquals("TTTGCTTTTTTGTACAAAAAAGCAGGCTATGATGCCC", r.product.sequence)
        assertEquals(3, r.insertStart); assertEquals(33, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
    }

    @Test
    fun gatewayFallbackAttL() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vPlain), ins("AAAACCCCGGGGTTTT"), CloningSim.M_GATEWAY)
        )
        assertEquals("ACGTACGTACCAACTTGTATATAAAGTTGAAAACCCCGGGGTTTTACCCAGCTTTCTTGTACAAAGTGG", r.product.sequence)
        assertEquals(29, r.insertStart); assertEquals(44, r.insertEnd)
        assertEquals(
            listOf("attB1/attP1 not found in vector", "attB2/attP2 not found in vector",
                "attB1/attP1 not found in insert", "attB2/attP2 not found in insert"),
            r.warnings
        )
    }

    // ── 仿真：topo / ta / gc / dispatch ──

    @Test
    fun topoDirectionalOk() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vTopo), ins(iCacc), CloningSim.M_TOPO_DIRECTIONAL, directional = true)
        )
        assertEquals("pVec_topo_mTag", r.productName)
        assertEquals("ACGTCACCATGGGGTTTAAAGTGGACGTACGT", r.product.sequence)
        assertEquals(4, r.insertStart); assertEquals(19, r.insertEnd)
        assertEquals(emptyList<String>(), r.warnings)
        assertEquals(CloningSim.M_TOPO_DIRECTIONAL, r.historyNode.method)
    }

    @Test
    fun topoDirectionalMissingCacc() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vTopo), ins("GGGGAAAACCCC"), CloningSim.M_TOPO_DIRECTIONAL, directional = true)
        )
        assertEquals("ACGTGGGGAAAACCCCGTGGACGTACGT", r.product.sequence)
        assertEquals(listOf("Directional TOPO requires 5' CACC overhang on insert"), r.warnings)
    }

    @Test
    fun topoNonDirectionalAppendsWhenSiteMissing() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vPlain), ins(iCacc), CloningSim.M_TOPO, directional = false)
        )
        assertEquals("ACGTACGTACCACCATGGGGTTTAAA", r.product.sequence)
        assertEquals(10, r.insertStart); assertEquals(25, r.insertEnd)
        assertEquals(listOf("TOPO site (GTTT) not found in vector, appending insert"), r.warnings)
    }

    @Test
    fun taConcat() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vPlain), ins(iSalKpn), CloningSim.M_TA)
        )
        assertEquals("pVec_TA_mTag", r.productName)
        assertEquals("ACGTACGTACGTCGACATGATGATGGGTACCGCGCGC", r.product.sequence)
        assertEquals(10, r.insertStart); assertEquals(36, r.insertEnd)
    }

    @Test
    fun gcViaDispatchIsTa() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vPlain), ins(iSalKpn), CloningSim.M_GC)
        )
        assertEquals("pVec_TA_mTag", r.productName)
        assertEquals("ACGTACGTACGTCGACATGATGATGGGTACCGCGCGC", r.product.sequence)
        // main simulateTACloning 的 historyNode 硬编码 method:"ta"（即使经 gc 分发）
        assertEquals(CloningSim.M_TA, r.historyNode.method)
    }

    @Test
    fun unknownMethodFallsToError() {
        val r = CloningSim.simulateCloning(
            CloningInput(vec(vPlain), ins(iSalKpn), "nope")
        )
        assertEquals("pVec", r.productName)
        assertEquals(vPlain, r.product.sequence)
        assertEquals(listOf("Unknown method: nope"), r.warnings)
    }

    @Test
    fun emptyNamesFallBackToDefaults() {
        val r = CloningSim.simulateCloning(
            CloningInput(
                SimSequenceData(sequence = vPlain, name = "", circular = true),
                SimSequenceData(sequence = iSalKpn, name = "", circular = false),
                CloningSim.M_TA
            )
        )
        assertEquals("Vector_TA_Insert", r.productName)
    }
}
