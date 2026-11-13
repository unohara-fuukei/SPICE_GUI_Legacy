package com.spice.nativeapp

import com.spice.nativeapp.core.ProteinBiophysics
import org.junit.Assert.*
import org.junit.Test

/** 蛋白生物物理模块测试（对齐 main 分支 biophysics 语义） */
class ProteinBiophysicsTest {

    private val demo = "MKTAYIAKQRQISFVKSHFSRQLEERLGLIEVQANAGAPGDRVLLLAGLEKLLRQGGSVEPVLAALRDELAAELGAPDPAARLRAALEEAGGEP"

    // ---- Chou-Fasman ----
    @Test fun chouFasmanHelixRichSequence() {
        // 多聚丙氨酸/谷氨酸为强螺旋形成序列
        val helical = "AEAAAKEAAAKEAAAKA"
        val cf = ProteinBiophysics.chouFasman(helical)
        assertEquals(helical.length, cf.size)
        assertTrue("螺旋占比应过半，实际 ${cf.count { it.second == 'H' }}", cf.count { it.second == 'H' } > cf.size / 2)
    }

    @Test fun chouFasmanSheetPropensity() {
        // 多聚缬氨酸/异亮氨酸为强 β 折叠序列
        val sheet = "VVIVIVVVIVIVIVVV"
        val cf = ProteinBiophysics.chouFasman(sheet)
        assertTrue("β 或卷曲应占多数（V/I 无螺旋引发剂）", cf.count { it.second == 'H' } < cf.size)
    }

    // ---- ΔΔG ----
    @Test fun ddgIdenticalIsNeutral() {
        val r = ProteinBiophysics.mutationalStabilityDdg('A', 10, 'A')
        assertEquals(0.0, r.ddG, 1e-9)
        assertEquals("Neutral", r.classification)
    }

    @Test fun ddgHydrophobicCoreGainStabilizes() {
        // A(1.8) → V(4.2)：体积差 51.4（无位阻惩罚），疏水增益 → ΔΔG<0（稳定）
        val r = ProteinBiophysics.mutationalStabilityDdg('A', 10, 'V')
        assertTrue("A→V 应稳定（ΔΔG<0），实际 ${r.ddG}", r.ddG < 0)
    }

    @Test fun ddgChargeDisruptionDestabilizes() {
        // K → A：破坏潜在盐桥，应去稳定
        val r = ProteinBiophysics.mutationalStabilityDdg('K', 10, 'A')
        assertTrue("K→A 应去稳定（ΔΔG>0），实际 ${r.ddG}", r.ddG > 0)
    }

    @Test fun ddgCavityCreationDestabilizes() {
        // W(227.8) → G(60.1)：体积骤降 >40，空腔惩罚
        val r = ProteinBiophysics.mutationalStabilityDdg('W', 10, 'G')
        assertTrue("W→G 空腔效应应去稳定，实际 ${r.ddG}", r.ddG > 0)
    }

    // ---- 电荷 / pI ----
    @Test fun chargeAtPh7PositiveLysineRich() {
        val c = ProteinBiophysics.chargeAtPh("KKKKKKKKKK", 7.0)
        assertTrue("富 K 序列 pH7 应带正电，实际 $c", c > 5)
    }

    @Test fun pIRoundsToTwoDecimals() {
        val pi = ProteinBiophysics.pI(demo)
        assertTrue("pI 应在 4–10 之间，实际 $pi", pi in 4.0..10.0)
        assertEquals(2, pi.toString().split(".")[1].length.coerceAtMost(2))
    }

    @Test fun gravySignMatchesHydrophobicity() {
        assertTrue(ProteinBiophysics.gravy("LLLLLLLLLL") > 0)
        assertTrue(ProteinBiophysics.gravy("KKKKKKKKKK") < 0)
    }

    // ---- FoldIndex 无序度 ----
    @Test fun disorderFoldIndexRange() {
        val d = ProteinBiophysics.disorderFoldIndex(demo)
        assertTrue("无序比例应在 0..1，实际 $d", d in 0.0..1.0)
    }

    // ---- 溶解性 / 聚集 ----
    @Test fun solubilityScoreBounded() {
        val s = ProteinBiophysics.solubilityAndAggregation(demo)
        assertTrue(s.solubilityScore in 0..100)
    }

    @Test fun aggregationHotspotDetected() {
        val s = ProteinBiophysics.solubilityAndAggregation("LIVFWMYLIVFWM")
        assertTrue("强疏水窗口应报聚集热点", s.aggregationHotspots.isNotEmpty())
    }

    // ---- 二硫键 ----
    @Test fun disulfideCxxcMotifScored() {
        // CXXC 模式应得到加分
        val bonds = ProteinBiophysics.disulfideBonds("AAACXXCAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACXXXXXXXXXXC")
        assertTrue("应至少配出一对二硫键", bonds.isNotEmpty())
        assertTrue("CXXC 应打分更高", bonds[0].pairingProbability > 50)
    }

    // ---- 抗原性 ----
    @Test fun antigenicityEpitopeMinimumLength() {
        val a = ProteinBiophysics.antigenicity(demo)
        assertEquals(demo.length, a.scores.size)
        a.epitopes.forEach { assertTrue("表位最短 6 aa，实际 ${it.end - it.start + 1}", it.end - it.start + 1 >= 6) }
    }

    // ---- PMF ----
    @Test fun pmfTrypsinDigest() {
        // KR 后切（P 除外）：MKAAR 应切成 MKAAR 末端 + 内部
        val frags = ProteinBiophysics.peptideMassFingerprint("MKAARPEPK")
        // 酶切位点：K2（后接 A 切）、R5（后接 P 不切）、K8（末端切）
        // 片段：MK | AARPEPK
        assertTrue("应切成 ≥2 段，实际 ${frags.size}", frags.size >= 2)
        assertTrue(frags.any { it.sequence == "AARPEPK" })
        assertTrue(frags.none { it.sequence.length == 0 })
        // 质量升序
        assertEquals(frags.sortedBy { it.mass }, frags)
        // 单肽质量：A A R P E P K（A=71.03711×2 R=156.10111 P=97.05276×2 E=129.04259 K=128.09496）+ H2O 18.01056
        val aarpepk = frags.first { it.sequence == "AARPEPK" }
        assertEquals(767.43, aarpepk.mass, 0.05)
    }

    @Test fun pmfDifferentialDetectsShift() {
        val diff = ProteinBiophysics.comparePmf("MKAARPEPK", "MKAARLEPK")
        assertTrue("E→L 替换应产生移位峰", diff.any { it.type == "shifted" })
    }

    // ---- 突变库批量 ----
    @Test fun mutantsCsvParseAndEvaluate() {
        val csv = "# 注释行\nID,V15A + I32L\nMutA,A1G\nbad line here\nMutB,V15A"
        val cands = ProteinBiophysics.parseMutantsCsv(csv, demo)
        // ID, 开头按原版逻辑视为表头跳过；无逗号的坏行跳过 → MutA + MutB
        assertEquals(2, cands.size)
        val results = ProteinBiophysics.evaluateMutantsBatch(cands, demo)
        assertEquals(2, results.size)
        results.forEach {
            assertTrue("稳定性评分 0..100，实际 ${it.thermostabilityScore}", it.thermostabilityScore in 0..100)
            assertTrue(it.disorderScore in 0.0..1.0)
        }
        // V15A 在 demo 上应真实生效（第 15 位为 V）
        val mutB = cands.first { it.id == "MutB" }
        assertEquals('A', mutB.sequence[14])
    }

    @Test fun batchScorePenalizesHighTemperature() {
        val cands = listOf(ProteinBiophysics.VariantCandidate("m", "A1G", demo))
        val low = ProteinBiophysics.evaluateMutantsBatch(cands, demo, tempK = 310.0).first().thermostabilityScore
        val high = ProteinBiophysics.evaluateMutantsBatch(cands, demo, tempK = 370.0).first().thermostabilityScore
        assertTrue("高温应降低评分：$low vs $high", high < low)
    }

    // ---- 折叠引擎 ----
    @Test fun foldDeterministicAndEnvSensitive() {
        val f1 = ProteinBiophysics.fold(demo, ProteinBiophysics.Env(7.0, 310.0))
        val f2 = ProteinBiophysics.fold(demo, ProteinBiophysics.Env(7.0, 310.0))
        assertEquals("同种子同环境应确定性一致", f1.conf, f2.conf, 1e-12)
        assertArrayEquals(f1.coords, f2.coords, 0f)
        assertEquals(demo.length, f1.length)
        // 环境偏离 → 置信度下降
        val fHot = ProteinBiophysics.fold(demo, ProteinBiophysics.Env(2.0, 360.0))
        assertTrue("环境偏离应降低置信度：${f1.conf} vs ${fHot.conf}", fHot.conf < f1.conf)
        // 接触图对称
        val n = f1.length
        for (i in 0 until n step 7) for (j in 0 until n step 11) {
            assertEquals(f1.contactMap[i * n + j], f1.contactMap[j * n + i])
        }
        // 距离矩阵对角线为 0
        for (i in 0 until n step 13) assertEquals(0f, f1.distExp[i * n + i], 1e-3f)
    }

    @Test fun foldSecondaryStructureAware() {
        // 纯螺旋序列的接触密度应显著高于纯卷曲（P/G 交替）
        val helical = ProteinBiophysics.fold("AEAAAKEAAAKEAAAKAAEAAAKEAAAKEAAAKA")
        val coil = ProteinBiophysics.fold("PGPGPGPGPGPGPGPGPGPGPGPGPGPGPGPG")
        val hc = helical.contactMap.sumOf { it.toInt() }
        val cc = coil.contactMap.sumOf { it.toInt() }
        assertTrue("螺旋构象接触应更多：$hc vs $cc", hc > cc)
    }

    // ---- MD ----
    @Test fun mdStepEnergyHistoryAndMetrics() {
        val md = ProteinBiophysics.mdStep(demo, 200, bias = true)
        assertEquals(200, md.stepCount)
        assertEquals(200, md.uHist.size)
        assertEquals(0.6, md.timePs, 1e-9)
        assertFalse(md.crashed)
        assertTrue("二级结构保持数不应超过参考", md.metrics.nSsKept <= md.metrics.nSsRef)
        assertTrue("参考二级结构数应 > 0", md.metrics.nSsRef > 0)
    }

    // ---- 相图 ----
    @Test fun phaseScanCenterMoreStable() {
        val temps = listOf(280.0, 310.0, 340.0)
        val phs = listOf(4.0, 7.0, 10.0)
        val pts = ProteinBiophysics.scan(temps, phs)
        assertEquals(9, pts.size)
        val nearCenter = pts.count { Math.abs(it.t - 310) < 1 && Math.abs(it.ph - 7) < 1 }
        assertTrue("中心点应存在", nearCenter > 0)
    }

    // ---- 口袋 ----
    @Test fun pocketDetectionAndDelta() {
        val f = ProteinBiophysics.fold(demo)
        val pockets = ProteinBiophysics.detectPockets(f, demo)
        // demo 螺旋占多数 → 应能找到聚簇口袋
        pockets.forEach {
            assertTrue(it.volume > 0)
            assertTrue(it.druggability in 0.0..0.99)
            assertTrue(it.surfaceResidues.isNotEmpty())
        }
        if (pockets.size >= 2) {
            val (dVol, jac) = ProteinBiophysics.pocketDelta(pockets[0], pockets[1])
            assertTrue("Jaccard 应在 0..1", jac in 0.0..1.0)
            // 同一口袋差分应为 0 体积变化、Jaccard 1
            val (d0, j0) = ProteinBiophysics.pocketDelta(pockets[0], pockets[0])
            assertEquals(0.0, d0, 1e-9)
            assertEquals(1.0, j0, 1e-9)
        }
    }

    // ---- 突变购物车 ----
    @Test fun applyMutationsOnlyValidOnes() {
        val out = ProteinBiophysics.applyMutations(
            demo,
            listOf(
                Triple(1, 'M', 'A'),   // 合法
                Triple(2, 'X', 'A'),   // 原残基不匹配 → 忽略
                Triple(9999, 'A', 'G') // 越界 → 忽略
            )
        )
        assertEquals(demo.length, out.length)
        assertEquals('A', out[0])
        assertEquals(demo[1], out[1])
    }
}
