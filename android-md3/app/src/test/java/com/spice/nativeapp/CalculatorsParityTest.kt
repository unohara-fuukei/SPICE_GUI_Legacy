package com.spice.nativeapp

import com.spice.nativeapp.core.Calculators
import org.junit.Assert.*
import org.junit.Test

/**
 * 反应计算器 parity 测试（T7 / ReactionCalculators 块）。
 *
 * 参考值由 tsx 直跑 main 分支原版 calculators.ts 生成（/tmp/rc_gen.ts → /tmp/rc_ref.json，
 * 除 paraglide import 行替换为 stub 外逐字一致），此处将产出内联为常量。
 * 全部为精确断言（双方同为 IEEE double + 相同 toFixed/round 语义）。
 */
class CalculatorsParityTest {

    // ───────────────── 1. 稀释 ─────────────────

    @Test fun dilution_matches_reference() {
        // 面板默认 C1=100, C2=10, V2=50
        val r = Calculators.calculateDilution(100.0, 10.0, 50.0)
        assertEquals(5.0, r.sourceVol, 1e-9)
        assertEquals(45.0, r.waterVol, 1e-9)
        // 非整数值 5 → 3.3, 37
        val r2 = Calculators.calculateDilution(5.0, 3.3, 37.0)
        assertEquals(24.42, r2.sourceVol, 1e-9)
        assertEquals(12.58, r2.waterVol, 1e-9)
        // 边界：sourceConc=0 / targetConc>sourceConc / targetVol=0
        assertEquals(0.0, Calculators.calculateDilution(0.0, 10.0, 50.0).sourceVol, 1e-9)
        assertEquals(50.0, Calculators.calculateDilution(0.0, 10.0, 50.0).waterVol, 1e-9)
        assertEquals(50.0, Calculators.calculateDilution(100.0, 200.0, 50.0).waterVol, 1e-9)
        assertEquals(0.0, Calculators.calculateDilution(100.0, 10.0, 0.0).waterVol, 1e-9)
    }

    // ───────────────── 2. 连接插入量 ─────────────────

    @Test fun ligation_matches_reference() {
        // 面板默认 5000/1000/100/3 → 60 ng
        assertEquals(60.0, Calculators.calculateLigationMass(5000, 1000, 100.0, 3.0), 1e-9)
        assertEquals(125.0, Calculators.calculateLigationMass(3000, 300, 250.0, 5.0), 1e-9)
        assertEquals(0.0, Calculators.calculateLigationMass(0, 300, 250.0, 5.0), 1e-9)
        assertEquals(22.2, Calculators.calculateLigationMass(4321, 1234, 77.7, 1.0), 1e-9)
        assertEquals(221.9, Calculators.calculateLigationMass(4321, 1234, 77.7, 10.0), 1e-9)
    }

    // ───────────────── 3. PCR Master Mix ─────────────────

    @Test fun pcrMix_q5_matches_reference() {
        val rows = Calculators.calculatePcrMasterMix(8, 50.0, Calculators.Polymerase.Q5)
        assertEquals(5, rows.size)
        // 组分名为原版字面量（英文原样）
        assertEquals(
            listOf(
                "2X Q5 High-Fidelity Master Mix", "10 uM Forward Primer", "10 uM Reverse Primer",
                "Template DNA (1-10 ng)", "Nuclease-Free Water"
            ), rows.map { it.component }
        )
        assertEquals(25.0, rows[0].volumePerWell, 1e-9)
        assertEquals(220.0, rows[0].totalVolume, 1e-9)
        assertEquals(18.0, rows[4].volumePerWell, 1e-9)
        assertEquals(158.4, rows[4].totalVolume, 1e-9)
    }

    @Test fun pcrMix_taq_matches_reference() {
        val rows = Calculators.calculatePcrMasterMix(8, 50.0, Calculators.Polymerase.TAQ)
        assertEquals(
            listOf(
                "10X Taq Buffer with MgCl2", "10 mM dNTPs Mix", "10 uM Forward Primer",
                "10 uM Reverse Primer", "Taq DNA Polymerase", "Template DNA", "Nuclease-Free Water"
            ), rows.map { it.component }
        )
        assertEquals(5.0, rows[0].volumePerWell, 1e-9)
        assertEquals(44.0, rows[0].totalVolume, 1e-9)
        assertEquals(0.25, rows[4].volumePerWell, 1e-9)
        assertEquals(2.2, rows[4].totalVolume, 1e-9)
        assertEquals(39.75, rows[6].volumePerWell, 1e-9)
        assertEquals(349.8, rows[6].totalVolume, 1e-9)
    }

    @Test fun pcrMix_scaling_matches_reference() {
        // 12 孔 × 25 µL（scale=0.5）：Q5 预混液 12.5/孔 × 13.2 总系数 = 165
        val q5 = Calculators.calculatePcrMasterMix(12, 25.0, Calculators.Polymerase.Q5)
        assertEquals(12.5, q5[0].volumePerWell, 1e-9)
        assertEquals(165.0, q5[0].totalVolume, 1e-9)
        // 1 孔 × 20 µL（scale=0.4）Taq：buffer 2/孔、水 15.9/孔、总量 17.49
        val taq = Calculators.calculatePcrMasterMix(1, 20.0, Calculators.Polymerase.TAQ)
        assertEquals(2.0, taq[0].volumePerWell, 1e-9)
        assertEquals(2.2, taq[0].totalVolume, 1e-9)
        assertEquals(15.9, taq[6].volumePerWell, 1e-9)
        assertEquals(17.49, taq[6].totalVolume, 1e-9)
    }

    // ───────────────── 4. DNA/RNA 浓度换算 ─────────────────

    @Test fun concentration_matches_reference() {
        // ng/µL→nM，dsDNA 3000bp：MW=1853916.04，50ng/µL=26.97nM
        val ds = Calculators.convertDnaConcentration(50.0, "ng_to_nM", 3000, true)
        assertEquals(26.97, ds.nM, 1e-9)
        assertEquals(1853916.04, ds.molWt, 1e-9)
        assertEquals(50.0, ds.ngPerUl, 1e-9)
        // nM→ng/µL 反向
        val back = Calculators.convertDnaConcentration(50.0, "nM_to_ng", 3000, true)
        assertEquals(92.7, back.ngPerUl, 1e-9)
        assertEquals(50.0, back.nM, 1e-9)
        // ssDNA 3000nt：MW=921079
        val ss = Calculators.convertDnaConcentration(50.0, "ng_to_nM", 3000, false)
        assertEquals(54.28, ss.nM, 1e-9)
        assertEquals(921079.0, ss.molWt, 1e-9)
        // ss 777nt 25.4nM → 6.06 ng/µL
        val ss2 = Calculators.convertDnaConcentration(25.4, "nM_to_ng", 777, false)
        assertEquals(6.06, ss2.ngPerUl, 1e-9)
        assertEquals(238618.0, ss2.molWt, 1e-9)
        // 默认 ds：500bp 100ng/µL → 323.61 nM
        val dflt = Calculators.convertDnaConcentration(100.0, "ng_to_nM", 500)
        assertEquals(323.61, dflt.nM, 1e-9)
        assertEquals(309016.04, dflt.molWt, 1e-9)
    }

    // ───────────────── 5. 梯度 PCR ─────────────────

    @Test fun gradient_matches_reference_50_66() {
        // 面板实际调用：optimizeGradientPcr(58.5, 60, 50, 66)
        val g = Calculators.optimizeGradientPcr(58.5, 60.0, 50.0, 66.0)
        val cols = g.columns
        assertEquals(12, cols.size)
        assertEquals("52.0°C - 55.0°C", g.optimalTempRange)
        assertEquals(3, g.bestColumn)
        val expect = listOf(
            50.0 to "Suboptimal", 51.5 to "Suboptimal", 52.9 to "Optimal", 54.4 to "Optimal",
            55.8 to "Good", 57.3 to "Good", 58.7 to "Poor", 60.2 to "Poor",
            61.6 to "Poor", 63.1 to "Poor", 64.5 to "Poor", 66.0 to "Poor"
        )
        cols.forEachIndexed { i, c ->
            assertEquals(i + 1, c.column)
            assertEquals(expect[i].first, c.temperature, 1e-9)
            assertEquals(expect[i].second, c.efficiencyRating)
        }
        // 推荐文案 = main calcTa* 词条原文
        assertEquals("退火温度偏低，可能出现非特异条带。", cols[0].recommendation)
        assertEquals("理想退火温度！引物结合效率极佳且无非特异性扩增。", cols[2].recommendation)
        assertEquals("退火较强，特异性极高。但如果产物不足，可适当降低温度。", cols[4].recommendation)
        assertEquals("温度过高，可能导致引物无法退火，无 PCR 产物。", cols[6].recommendation)
    }

    @Test fun gradient_matches_reference_default65() {
        val g = Calculators.optimizeGradientPcr(58.5, 59.2)
        assertEquals(4, g.bestColumn)
        assertEquals("52.0°C - 55.0°C", g.optimalTempRange)
        assertEquals(51.4, g.columns[1].temperature, 1e-9)
        assertEquals("Optimal", g.columns[3].efficiencyRating)
        assertEquals(56.8, g.columns[5].temperature, 1e-9)
        assertEquals("Good", g.columns[5].efficiencyRating)
    }

    @Test fun gradient_no_optimal_column_keeps_first() {
        // Tm 52/75 → 目标 47°C，50..66 全部偏高，无 Optimal 列 → bestColumn 停在 1（原版行为）
        val g = Calculators.optimizeGradientPcr(52.0, 75.0, 50.0, 66.0)
        assertEquals(1, g.bestColumn)
        assertEquals("45.5°C - 48.5°C", g.optimalTempRange)
        assertEquals("Good", g.columns[0].efficiencyRating)
    }

    // ───────────────── 6. 凝胶估算 ─────────────────

    @Test fun gelEstimator_matches_reference() {
        val est = Calculators.estimateGelBandSizes(
            listOf(
                Calculators.GelStandard(20.0, 10000),
                Calculators.GelStandard(45.0, 5000),
                Calculators.GelStandard(80.0, 2000)
            ), listOf(55.0)
        )
        assertEquals(0.9997, est.second, 1e-9)
        assertEquals(3883, est.first[0])
        // 多未知条带
        val multi = Calculators.estimateGelBandSizes(
            listOf(
                Calculators.GelStandard(10.0, 8000),
                Calculators.GelStandard(30.0, 3000),
                Calculators.GelStandard(55.0, 900)
            ), listOf(20.0, 40.0, 70.0)
        )
        assertEquals(1.0, multi.second, 1e-9)
        assertEquals(listOf(4906, 1859, 433), multi.first)
        // 两点回归（无残差 R²=1）
        val two = Calculators.estimateGelBandSizes(
            listOf(Calculators.GelStandard(12.0, 5000), Calculators.GelStandard(60.0, 700)), listOf(35.0)
        )
        assertEquals(1949, two.first[0])
        // 单标样退化
        val deg = Calculators.estimateGelBandSizes(listOf(Calculators.GelStandard(20.0, 100)), listOf(50.0))
        assertEquals(0, deg.first[0])
        assertEquals(0.0, deg.second, 1e-9)
    }

    // ───────────────── 7. ELISA 4PL ─────────────────

    @Test fun elisa_matches_reference() {
        // 面板默认 3 标点 + 未知 OD 1.15
        val r = Calculators.fitElisaStandardCurve(
            listOf(
                Calculators.ElisaPoint(1000.0, 2.4),
                Calculators.ElisaPoint(250.0, 0.85),
                Calculators.ElisaPoint(62.5, 0.22)
            ), listOf(1.15)
        )
        assertEquals(0.209, r.a, 1e-9)
        assertEquals(6.849, r.b, 1e-9)
        assertEquals(365.761, r.c, 1e-9)
        assertEquals(2.52, r.d, 1e-9)
        assertEquals(0.9021, r.rSquared, 1e-9)
        assertEquals(322.371, r.fittedConcentrations[0], 1e-9)
    }

    @Test fun elisa_4points_and_clamps() {
        val r = Calculators.fitElisaStandardCurve(
            listOf(
                Calculators.ElisaPoint(4000.0, 2.95),
                Calculators.ElisaPoint(1000.0, 2.41),
                Calculators.ElisaPoint(250.0, 0.9),
                Calculators.ElisaPoint(62.5, 0.21)
            ), listOf(1.5, 0.5, 2.8, 0.02, 3.9)
        )
        assertEquals(0.199, r.a, 1e-9)
        assertEquals(4.641, r.b, 1e-9)
        assertEquals(696.017, r.c, 1e-9)
        assertEquals(3.098, r.d, 1e-9)
        assertEquals(0.8913, r.rSquared, 1e-9)
        assertEquals(628.485, r.fittedConcentrations[0], 1e-9)
        assertEquals(238.688, r.fittedConcentrations[1], 1e-9)
        assertEquals(2040.891, r.fittedConcentrations[2], 1e-9)
        assertEquals(13.33, r.fittedConcentrations[3], 1e-9)
        assertEquals(36342.337, r.fittedConcentrations[4], 1e-6)
        // 两点体系可拟合
        val n2 = Calculators.fitElisaStandardCurve(
            listOf(Calculators.ElisaPoint(1000.0, 2.4), Calculators.ElisaPoint(250.0, 0.85)), listOf(1.15)
        )
        assertEquals(0.807, n2.a, 1e-9)
        assertEquals(10.392, n2.b, 1e-9)
        assertEquals(563.894, n2.c, 1e-9)
        assertEquals(2.52, n2.d, 1e-9)
        assertEquals(0.9874, n2.rSquared, 1e-9)
        assertEquals(414.761, n2.fittedConcentrations[0], 1e-9)
    }

    // ───────────────── 8. 鸡动力学 ─────────────────

    @Test fun kinetics_matches_reference() {
        // 面板默认 3 点
        val r = Calculators.calculateEnzymeKinetics(
            listOf(
                Calculators.KineticsPoint(10.0, 4.2),
                Calculators.KineticsPoint(2.5, 2.1),
                Calculators.KineticsPoint(0.5, 0.65)
            )
        )
        assertEquals(5.369, r.vMax, 1e-9)
        assertEquals(3.638, r.kM, 1e-9)
        assertEquals(0.9879, r.rSquared, 1e-9)
        assertEquals(listOf(3.937, 2.187, 0.649), r.fittedVelocities)
        // 严格 Michaelis-Menten 数据（Vmax=1, Km=1）
        val perfect = Calculators.calculateEnzymeKinetics(
            listOf(
                Calculators.KineticsPoint(1.0, 0.5),
                Calculators.KineticsPoint(2.0, 2.0 / 3.0),
                Calculators.KineticsPoint(4.0, 0.8)
            )
        )
        assertEquals(1.0, perfect.vMax, 1e-9)
        assertEquals(1.0, perfect.kM, 1e-9)
        assertEquals(1.0, perfect.rSquared, 1e-9)
        assertEquals(listOf(0.5, 0.667, 0.8), perfect.fittedVelocities)
        // 无效点过滤后不足 2 个 → 全 0
        val edge = Calculators.calculateEnzymeKinetics(
            listOf(Calculators.KineticsPoint(0.0, 4.2), Calculators.KineticsPoint(2.5, -1.0))
        )
        assertEquals(0.0, edge.vMax, 1e-9)
        assertEquals(0.0, edge.kM, 1e-9)
        assertEquals(0.0, edge.rSquared, 1e-9)
        assertEquals(listOf(0.0, 0.0), edge.fittedVelocities)
        // 4 点
        val extra = Calculators.calculateEnzymeKinetics(
            listOf(
                Calculators.KineticsPoint(5.0, 3.0), Calculators.KineticsPoint(1.0, 1.2),
                Calculators.KineticsPoint(0.4, 0.55), Calculators.KineticsPoint(20.0, 4.9)
            )
        )
        assertEquals(5.402, extra.vMax, 1e-9)
        assertEquals(3.527, extra.kM, 1e-9)
        assertEquals(0.9893, extra.rSquared, 1e-9)
        assertEquals(listOf(3.168, 1.193, 0.55, 4.592), extra.fittedVelocities)
    }
}
