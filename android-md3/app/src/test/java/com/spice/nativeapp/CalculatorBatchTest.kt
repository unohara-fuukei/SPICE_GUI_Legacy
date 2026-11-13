package com.spice.nativeapp

import com.spice.nativeapp.core.BatchEngine
import com.spice.nativeapp.core.Calculators
import org.junit.Assert.*
import org.junit.Test

/** 反应计算器与批量引擎测试（对齐 main 分支 biophysics/calculators.ts 与 batch.ts 语义） */
class CalculatorBatchTest {

    // ───────────────── 1. 稀释 ─────────────────

    @Test fun dilutionBasic() {
        // C1=100, C2=10, V2=100 → 取 10 µL 母液 + 90 µL 水
        val r = Calculators.calculateDilution(100.0, 10.0, 100.0)
        assertEquals(10.0, r.sourceVol, 1e-9)
        assertEquals(90.0, r.waterVol, 1e-9)
    }

    @Test fun dilutionInvalidInputs() {
        // 目标浓度高于母液 / 非正值 → 0 + 全量缓冲
        val r = Calculators.calculateDilution(10.0, 100.0, 50.0)
        assertEquals(0.0, r.sourceVol, 1e-9)
        assertEquals(50.0, r.waterVol, 1e-9)
        assertEquals(0.0, Calculators.calculateDilution(0.0, 5.0, 50.0).sourceVol, 1e-9)
    }

    // ───────────────── 2. 连接插入量 ─────────────────

    @Test fun ligationMassRatio3() {
        // 100 ng × (1000/3000) × 3 = 100 ng
        assertEquals(100.0, Calculators.calculateLigationMass(3000, 1000, 100.0, 3.0), 1e-9)
    }

    @Test fun ligationMassRatio1() {
        // 摩尔比 1:1 → 33.3 ng
        assertEquals(33.3, Calculators.calculateLigationMass(3000, 1000, 100.0, 1.0), 0.05)
    }

    @Test fun ligationMassInvalid() {
        assertEquals(0.0, Calculators.calculateLigationMass(0, 1000, 100.0, 3.0), 1e-9)
        assertEquals(0.0, Calculators.calculateLigationMass(3000, 1000, -1.0, 3.0), 1e-9)
    }

    // ───────────────── 3. PCR Master Mix ─────────────────

    @Test fun taqMixVolumes() {
        val rows = Calculators.calculatePcrMasterMix(8, 50.0, Calculators.Polymerase.TAQ)
        // Taq 50µL：buffer 5 + dNTP 1 + F 1 + R 1 + 酶 0.25 + 模板 2 + 水 39.75 = 50
        assertEquals(7, rows.size)
        assertEquals(5.0, rows[0].volumePerWell, 1e-9)
        assertEquals(39.75, rows[6].volumePerWell, 1e-9)
        assertEquals(50.0, rows.sumOf { it.volumePerWell }, 1e-9)
        // 总量 = 单孔 × 8 × 1.10
        assertEquals(5.0 * 8 * 1.1, rows[0].totalVolume, 0.01)
    }

    @Test fun phusionMixVolumes() {
        val rows = Calculators.calculatePcrMasterMix(1, 50.0, Calculators.Polymerase.PHUSION)
        assertEquals(7, rows.size)
        assertEquals(31.5, rows[6].volumePerWell, 1e-9)
        assertEquals(50.0, rows.sumOf { it.volumePerWell }, 1e-9)
    }

    @Test fun q5MixScalesWithVolume() {
        val rows = Calculators.calculatePcrMasterMix(2, 25.0, Calculators.Polymerase.Q5)
        // 25µL 体系 scale=0.5 → Master Mix 12.5 µL/孔
        assertEquals(5, rows.size)
        assertEquals(12.5, rows[0].volumePerWell, 1e-9)
        assertEquals(25.0, rows.sumOf { it.volumePerWell }, 1e-9)
    }

    // ───────────────── 4. DNA 浓度换算 ─────────────────

    @Test fun ngToNmDsDna() {
        // MW(3000bp ds) = 3000×617.96+36.04 = 1853884.04；100 ng/µL → 53.94 nM
        val r = Calculators.convertDnaConcentration(100.0, "ng_to_nM", 3000)
        assertEquals(1853916.04, r.molWt, 0.01)
        assertEquals(53.94, r.nM, 0.01)
    }

    @Test fun nmToNgRoundTrip() {
        val fwd = Calculators.convertDnaConcentration(100.0, "ng_to_nM", 3000)
        val back = Calculators.convertDnaConcentration(fwd.nM, "nM_to_ng", 3000)
        assertEquals(100.0, back.ngPerUl, 0.01)
    }

    @Test fun ssDnaUsesSsMolWt() {
        val r = Calculators.convertDnaConcentration(100.0, "ng_to_nM", 24, isDoubleStranded = false)
        // MW(24nt ss) = 24×307+79 = 7447；100/7447×1e6 = 13427 nM
        assertEquals(7447.0, r.molWt, 0.01)
        assertEquals(13428.23, r.nM, 0.1)
    }

    // ───────────────── 5. 梯度 PCR ─────────────────

    @Test fun gradientPcrBestColumn() {
        // Tm=60/62 → 目标 55°C；50..65 线性分布，第 7 列 ≈ 55.0
        val (cols, best) = Calculators.optimizeGradientPcr(60.0, 62.0)
        assertEquals(12, cols.size)
        assertEquals(50.0, cols[0].temperature, 0.01)
        assertEquals(65.0, cols[11].temperature, 0.01)
        assertEquals(55.0, cols[best - 1].temperature, 1.51)
        assertEquals("Optimal", cols[best - 1].efficiencyRating)
    }

    @Test fun gradientPcrPoorAtEdges() {
        val (cols, _) = Calculators.optimizeGradientPcr(80.0, 79.0) // 目标 74°C，50-65°C 全部过低
        assertTrue(cols.all { it.efficiencyRating == "Poor" })
    }

    // ───────────────── 6. 凝胶条带估算 ─────────────────

    @Test fun gelBandPerfectLinearFit() {
        // 构造严格 log 线性的标准带：距离 = 40 - 10×log2(size)… 用手算值
        val std = listOf(
            Calculators.GelStandard(10.0, 10000),
            Calculators.GelStandard(20.0, 1000),
            Calculators.GelStandard(30.0, 100)
        )
        // log10(size) = 4 - 0.1×(distance-10)：完美线性，R²=1
        val (sizes, r2) = Calculators.estimateGelBandSizes(std, listOf(25.0, 15.0))
        assertEquals(1.0, r2, 1e-9)
        assertEquals(316, sizes[0])  // 10^(4-1.5) ≈ 316
        assertEquals(3162, sizes[1]) // 10^(4-0.5) ≈ 3162
    }

    @Test fun gelBandInsufficientStandards() {
        val (sizes, r2) = Calculators.estimateGelBandSizes(listOf(Calculators.GelStandard(10.0, 1000)), listOf(15.0))
        assertEquals(0, sizes[0])
        assertEquals(0.0, r2, 1e-9)
    }

    // ───────────────── 7. ELISA 4PL ─────────────────

    @Test fun elisaRecoversKnownConcentration() {
        // 用 4PL 生成严格单调数据：a=0.1,d=1.1,b=1,c=100
        val std = listOf(1.0, 10.0, 100.0, 1000.0).map { c ->
            Calculators.ElisaPoint(c, 1.1 + (0.1 - 1.1) / (1 + (c / 100.0)))
        }
        // EC50 处 OD 应回算到 100 的同数量级（渐近线按 min×0.95/max×1.05 估计，允许偏差）
        val res = Calculators.fitElisaStandardCurve(std, listOf(0.6))
        assertEquals(100.0, res.fittedConcentrations[0], 25.0)
        assertEquals(100.0, res.c, 25.0)
        assertTrue(res.rSquared > 0.95)
    }

    @Test fun elisaTooFewPoints() {
        val res = Calculators.fitElisaStandardCurve(listOf(Calculators.ElisaPoint(10.0, 0.3)), listOf(0.5))
        assertEquals(0.0, res.fittedConcentrations[0], 1e-9)
        assertEquals(0.0, res.rSquared, 1e-9)
    }

    // ───────────────── 8. 酶动力学 ─────────────────

    @Test fun kineticsRecoversVmaxKm() {
        // Vmax=100, Km=2 生成数据
        val pts = listOf(0.5, 1.0, 2.0, 5.0, 10.0).map { s ->
            Calculators.KineticsPoint(s, 100.0 * s / (2.0 + s))
        }
        val r = Calculators.calculateEnzymeKinetics(pts)
        assertEquals(100.0, r.vMax, 1.0)
        assertEquals(2.0, r.kM, 0.15)
        assertTrue(r.rSquared > 0.99)
        // 拟合速度回代单调递增
        assertEquals(5, r.fittedVelocities.size)
    }

    @Test fun kineticsInvalidPoints() {
        val r = Calculators.calculateEnzymeKinetics(listOf(Calculators.KineticsPoint(0.0, 5.0)))
        assertEquals(0.0, r.vMax, 1e-9)
    }

    // ───────────────── BatchEngine ─────────────────

    private val fastaInput = """
        >pUC19 segment
        GAATTCGCGGCCGCAAGCTTCTGCAGGTCGACTCTAGAGGATCCCCGG
        >lambda fragment
        GAATTCAAAAAAGCTTGGGCCCCAATTCCGGGGATCC
    """.trimIndent()

    @Test fun parseFastasMultiRecord() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        assertEquals(2, seqs.size)
        assertEquals("pUC19 segment", seqs[0].name)
        assertEquals(48, seqs[0].sequence.length)
        assertEquals("lambda fragment", seqs[1].name)
        assertEquals(37, seqs[1].sequence.length)
    }

    @Test fun parseFastasBlankAndEmpty() {
        assertTrue(BatchEngine.parseFastas("").isEmpty())
        // 首个 > 之前的杂行忽略
        val noHeader = BatchEngine.parseFastas("no header line\nACGT")
        assertTrue(noHeader.isEmpty())
        assertEquals(1, BatchEngine.parseFastas(">s1\nACGT").size)
    }

    @Test fun batchDigestCountsCutsites() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        // EcoRI(GAATTC) ×1 + HindIII(AAGCTT) ×1 → 每条 2 切点
        val out = BatchEngine.batchDigest(seqs, listOf("EcoRI", "HindIII"))
        assertEquals(2, out.size)
        assertEquals(2, out[0].cutsitesCount)
        assertEquals(2, out[1].cutsitesCount)
        // 酶库以 REBASE 主名记录（EcoRI/HindIII 为别名），按识别位点断言
        assertEquals(setOf("gaattc", "aagctt"), out[0].cutsites.map { it.site.lowercase() }.toSet())
    }

    @Test fun batchDigestEmptyEnzymes() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        assertTrue(BatchEngine.batchDigest(seqs, listOf(" ", "")).isEmpty())
    }

    @Test fun batchDesignPrimersClampsRange() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        // 区间越界时收敛到序列内
        val out = BatchEngine.batchDesignPrimers(seqs, 1, 100000)
        assertEquals(2, out.size)
        out.forEach { assertNotNull(it.primers) }
        assertEquals(20, out[0].primers!!.forward.length)
    }

    @Test fun batchDesignPrimersInvalidStart() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        val out = BatchEngine.batchDesignPrimers(seqs, 0, 5)  // start<1 → 收敛 1
        assertNotNull(out[0].primers)
    }

    @Test fun batchExportGenbankAndFasta() {
        val seqs = BatchEngine.parseFastas(fastaInput)
        val gb = BatchEngine.batchExport(seqs, "genbank")
        assertEquals(2, gb.size)
        assertTrue(gb[0].content.contains("LOCUS"))
        assertTrue(gb[0].content.contains("ORIGIN"))
        val fa = BatchEngine.batchExport(seqs, "fasta")
        assertTrue(fa[0].content.startsWith(">"))
        assertTrue(fa[0].content.contains(seqs[0].sequence.take(10)))
    }
}
