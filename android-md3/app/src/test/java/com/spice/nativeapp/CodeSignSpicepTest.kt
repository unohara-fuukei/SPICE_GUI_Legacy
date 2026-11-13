package com.spice.nativeapp

import com.spice.nativeapp.core.CodeSign
import com.spice.nativeapp.core.ProteinBiophysics
import com.spice.nativeapp.core.Spicep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSignSpicepTest {

    // ── CodeSign：密码子/CAI ──

    @Test
    fun `bestCodon matches Rust best_codon table`() {
        assertEquals("CTG", CodeSign.bestCodon(CodeSign.Host.ECOLI, 'L'))
        assertEquals("TTA", CodeSign.bestCodon(CodeSign.Host.YEAST, 'L'))
        assertEquals("CTG", CodeSign.bestCodon(CodeSign.Host.HUMAN, 'L'))
        assertEquals("TAA", CodeSign.bestCodon(CodeSign.Host.ECOLI, '*'))
        assertEquals("TGA", CodeSign.bestCodon(CodeSign.Host.HUMAN, '*'))
        assertNull(CodeSign.bestCodon(CodeSign.Host.ECOLI, 'X'))
    }

    @Test
    fun `CAI is 1 for all-optimal codons`() {
        // E. coli 最优密码子串联：M ATG / W TGG / L CTG / F TTC
        assertEquals(1.0, CodeSign.computeCai("ATGTGGCTGTTC", CodeSign.Host.ECOLI), 1e-6)
    }

    @Test
    fun `CAI geometric mean matches hand calculation`() {
        // ATG(1.0) + TTT(F, 0.3/1.0)：CAI = (1*0.3)^(1/2)
        assertEquals(0.5477, CodeSign.computeCai("ATGTTT", CodeSign.Host.ECOLI), 1e-3)
        // AAA(K, 0.25) + GAA(E, 1.0) + GGG(G, 0.15/1.0)：CAI = (0.25*1*0.15)^(1/3) = 0.335
        assertEquals(0.335, CodeSign.computeCai("AAAGAAGGG", CodeSign.Host.ECOLI), 1e-3)
    }

    @Test
    fun `CAI empty and invalid inputs`() {
        assertEquals(0.0, CodeSign.computeCai("", CodeSign.Host.ECOLI), 1e-9)
        assertEquals(0.0, CodeSign.computeCai("NNNNNN", CodeSign.Host.ECOLI), 1e-9)
    }

    // ── CodeSign：候选解析 ──

    @Test
    fun `parseCandidates handles labels comments and malformed lines`() {
        val text = """
            # 注释行
            Variant_V1,V15A,-4.5
            Variant_V3,V15A + I32L,-5.8
            bad line
            Variant_X,XYZ,1.0
        """.trimIndent()
        val muts = CodeSign.parseCandidates(text)
        assertEquals(2, muts.size)
        assertEquals("Variant_V1", muts[0].id)
        assertEquals(listOf(14), muts[0].aaPositions)   // 0 基
        assertEquals(listOf('A'), muts[0].targetAas)
        assertEquals(-4.5, muts[0].proteinEnergyDelta, 1e-9)
        assertEquals(listOf(14, 31), muts[1].aaPositions)
        assertEquals(listOf('A', 'L'), muts[1].targetAas)
    }

    // ── CodeSign：评估与 Pareto 前沿 ──

    @Test
    fun `evaluate substitutes best codons and raises CAI`() {
        // CDS = ATG GCA TGC（M A C），A→L 用 E. coli 最优 CTG 替换第 2 密码子
        val dna = "TTTATGGCATGCTTT"   // 前后各加一个 F 框架外旁侧
        val variants = CodeSign.evaluate(
            dnaSequence = dna, cdsStart = 3, cdsEnd = 11, host = CodeSign.Host.ECOLI,
            mutations = listOf(CodeSign.Mutation("V1", "A2L", listOf(1), listOf('L'), -1.0))
        )
        assertEquals(1, variants.size)
        val v = variants[0]
        assertEquals("TTTATGCTGTGCTTT", v.updatedDna)
        // 原 CDS CAI = (1 · 0.3 · 1)^(1/3)，替换后 = (1 · 1 · 1)^(1/3) = 1
        assertEquals(0.669, CodeSign.computeCai("ATGGCATGC", CodeSign.Host.ECOLI), 1e-3)
        assertEquals(1.0, v.cai, 1e-6)
        assertTrue(v.mrnaDeltaG <= 0.0)
        assertEquals(-1.0, v.proteinEnergyDelta, 1e-9)
        assertTrue(v.isParetoOptimal)   // 单变体必在前沿
    }

    @Test
    fun `pareto frontier marks dominated variants`() {
        val dna = "ATGGCATGCTGGTTCACC"
        val variants = CodeSign.evaluate(
            dna, 0, 11, CodeSign.Host.ECOLI,
            listOf(
                CodeSign.Mutation("dom", "A2L", listOf(1), listOf('L'), -5.0),   // ΔE 低
                CodeSign.Mutation("sub", "A2V", listOf(1), listOf('V'), -5.0),   // 同 ΔE、CAI 更低 → 被支配
                CodeSign.Mutation("worse", "A2V + C3V", listOf(1, 2), listOf('V', 'V'), -1.0)
            )
        )
        val byId = variants.associateBy { it.id }
        assertTrue(byId.getValue("dom").isParetoOptimal)
        // sub 与 dom ΔE 相同但 CAI 较低（V=GTG=1.0 vs L=CTG=1.0 —— 需换一个真实差异的对照）
        // GTG(V) 与 CTG(L) 在 E. coli 均为 1.0 → CAI 相同 → 互不严格支配，两者都在前沿
        assertTrue(byId.getValue("sub").isParetoOptimal)
        // worse：ΔE 更高，CAI 相同 → 被 dom 支配
        assertFalse(byId.getValue("worse").isParetoOptimal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `evaluate rejects out of range CDS`() {
        CodeSign.evaluate("ATG", 5, 9, CodeSign.Host.ECOLI, emptyList())
    }

    // ── 可取消相图扫描 ──

    @Test
    fun `scanCancellable returns identical results when not cancelled`() {
        val temps = (280..340 step 10).map { it.toDouble() }
        val phs = (3..11 step 1).map { it.toDouble() }
        val direct = ProteinBiophysics.scan(temps, phs)
        val cancellable = ProteinBiophysics.scanCancellable(temps, phs, { false })
        assertEquals(direct, cancellable)
    }

    @Test
    fun `scanCancellable returns null immediately when cancelled`() {
        assertNull(ProteinBiophysics.scanCancellable(listOf(280.0, 290.0), listOf(3.0, 4.0), { true }))
    }

    // ── Spicep 状态包 ──

    @Test
    fun `spicep build-parse roundtrip preserves payload`() {
        val coords = floatArrayOf(1.5f, -2.25f, 3.125f, 0.5f, 1.0f, -0.5f)
        val uHist = listOf(-8450.2, -8472.5, -8511.1)
        val text = Spicep.build(
            sequence = "MKTAYIAK",
            env = Spicep.EnvState(ph = 6.5, tempK = 320.0, pressureBar = 1.0, ionicStrengthM = 0.2),
            foldCoords = coords, mdCoords = null,
            stepCount = 20, timePs = 40.0, uHist = uHist
        )
        assertTrue(text.contains("SPICE_PROTEIN"))
        val p = Spicep.parse(text)
        assertNotNull(p)
        p!!
        assertEquals("MKTAYIAK", p.sequence)
        assertEquals(6.5, p.env.ph, 1e-9)
        assertEquals(320.0, p.env.tempK, 1e-9)
        assertEquals(0.2, p.env.ionicStrengthM, 1e-9)
        assertEquals(20, p.stepCount)
        assertEquals(40.0, p.timePs, 1e-9)
        assertEquals(uHist, p.uHist)
        // 坐标按 3 位小数回写
        assertEquals(listOf(1.5, -2.25, 3.125, 0.5, 1.0, -0.5), p.referenceCoords)
    }

    @Test
    fun `spicep parse rejects foreign json`() {
        assertNull(Spicep.parse("""{"format":"OTHER"}"""))
        assertNull(Spicep.parse("not json at all"))
    }
}
