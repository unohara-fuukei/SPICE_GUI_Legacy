package com.spice.nativeapp

import com.spice.nativeapp.core.AiOfflineTools
import com.spice.nativeapp.core.AiOfflineTools.CodonResult
import com.spice.nativeapp.core.AiOfflineTools.CodonState
import com.spice.nativeapp.core.AiOfflineTools.MutResult
import com.spice.nativeapp.core.AiOfflineTools.MutState
import com.spice.nativeapp.core.AiOfflineTools.RecipeRow
import com.spice.nativeapp.core.AiOfflineTools.SopResult
import com.spice.nativeapp.core.AiOfflineTools.SopState
import com.spice.nativeapp.core.AiOfflineTools.ToolError
import com.spice.nativeapp.core.AiOfflineTools.CycleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * AiOfflineToolsTest —— T8 CoPilot 计算工具箱三算法 1:1 校验。
 *
 * 期望值全部由 main 源码手算烤入：
 * - 突变自由能：proteinAnalysis.ts AA_VOLUME/AA_HYDRO_SCALE + aiState.svelte.ts getCharge
 *   （Q→S 0.572→0.57 Destabilizing；K→E -0.24 Stabilizing；R→A 0.112→0.11 Neutral；
 *    G→W 8.685→8.69 Highly Destabilizing；同残基 0）
 * - 密码子调谐：codon.ts BEST_CODONS/CODON_WEIGHTS（ecoli/yeast）+ main 替换循环
 *   （含 hasMotif 全串否决 quirk 与 TGA→TAA 回译）
 * - 克隆 SOP：aiState.runSopGeneration 结构（extTime=max(10, round(len/1000×speed))）
 * - getSopMarkdown：AiCopilot.svelte getSopMarkdown 逐行（zh 直返 + trimDouble 体积）
 */
class AiOfflineToolsTest {

    private fun protein(wt: Char, pos: Int = 103, len: Int = 150): String =
        buildString { repeat(len) { i -> append(if (i == pos - 1) wt else 'M') } }

    // ───────────────────────── 突变自由能 ─────────────────────────

    @org.junit.Test
    fun mut_qs_destabilizing_with_na_note() {
        val r = AiOfflineTools.runMutationCalculation(MutState(), protein('Q')) as MutResult
        assertEquals(0.57, r.ddG, 1e-12)
        assertEquals("Destabilizing", r.classification)
        // Q/S 均不带电（main getCharge 非 K/R/H/D/E → 0），无溶剂中和注记
        assertEquals(0.0, r.chargeChange, 1e-12)
        assertEquals("Neutral", r.solventNeutralize)
        assertFalse(r.isStable)
        assertEquals(
            "突变 Q103S 会损害蛋白质的热力学稳定性 (ΔΔG = 0.57 kcal/mol)。可能破坏了潜在的盐桥或氢键结构。",
            r.explanation
        )
    }

    @org.junit.Test
    fun mut_ke_stabilizing_with_na_note() {
        val r = AiOfflineTools.runMutationCalculation(MutState(toRes = "E"), protein('K')) as MutResult
        assertEquals(-0.24, r.ddG, 1e-12)
        assertEquals("Stabilizing", r.classification)
        assertEquals(-2.0, r.chargeChange, 1e-12)
        assertEquals("Na+", r.solventNeutralize)
        assertTrue(r.isStable)
        assertEquals(
            "突变 K103E 有利于提高蛋白质的热力学稳定性 (ΔΔG = -0.24 kcal/mol)。" +
                "\n\n• **溶剂在线重中和**：突变引起系统净电荷变化（-2e），SPICE 引擎已自动在溶剂层中对应插入 Na+ 离子以保持电解质平衡。",
            r.explanation
        )
    }

    @org.junit.Test
    fun mut_ra_neutral_with_na_note() {
        val r = AiOfflineTools.runMutationCalculation(MutState(toRes = "A"), protein('R')) as MutResult
        assertEquals(0.11, r.ddG, 1e-12)
        assertEquals("Neutral", r.classification)
        assertEquals(-1.0, r.chargeChange, 1e-12)
        assertEquals("Na+", r.solventNeutralize)
        assertFalse(r.isStable)
        assertEquals(
            "突变 R103A 对主链热力学稳定性的影响基本呈中性 (ΔΔG = 0.11 kcal/mol)。" +
                "\n\n• **溶剂在线重中和**：突变引起系统净电荷变化（-1e），SPICE 引擎已自动在溶剂层中对应插入 Na+ 离子以保持电解质平衡。",
            r.explanation
        )
    }

    @org.junit.Test
    fun mut_gw_highly_destabilizing_no_note() {
        val r = AiOfflineTools.runMutationCalculation(MutState(toRes = "W"), protein('G')) as MutResult
        assertEquals(8.69, r.ddG, 1e-12)
        assertEquals("Highly Destabilizing", r.classification)
        assertEquals(0.0, r.chargeChange, 1e-12)
        assertEquals("Neutral", r.solventNeutralize)
        assertFalse(r.isStable)
        assertEquals(
            "突变 G103W 严重破坏了蛋白质的主链折叠核心 (ΔΔG > 1.0 kcal/mol)。这通常是由于引入了严重的侧链空间碰撞 (Steric Clash) 或使核心电荷异化。",
            r.explanation
        )
    }

    @org.junit.Test
    fun mut_same_residue_neutral() {
        val r = AiOfflineTools.runMutationCalculation(MutState(toRes = "Q"), protein('Q')) as MutResult
        assertEquals(0.0, r.ddG, 1e-12)
        assertEquals("Neutral", r.classification)
        assertEquals(0.0, r.chargeChange, 1e-12)
        assertEquals("Neutral", r.solventNeutralize)
        assertFalse(r.isStable)
        assertEquals("野生型与突变型残基一致，无稳定性能变。", r.explanation)
    }

    @org.junit.Test
    fun mut_err_unlinked_protein() {
        val e = AiOfflineTools.runMutationCalculation(MutState(), "") as ToolError
        assertEquals("突变估算失败", e.title)
        assertEquals("未绑定折叠工作台。使用预设的通用测试序列。", e.desc)
    }

    @org.junit.Test
    fun mut_err_pos_out_of_range() {
        val e = AiOfflineTools.runMutationCalculation(MutState(pos = 999), protein('Q')) as ToolError
        assertEquals("突变估算失败", e.title)
        assertEquals("残基位置 #999 超出当前序列长度 (1-150 aa)", e.desc)
    }

    @org.junit.Test
    fun mut_err_invalid_to_res() {
        val e = AiOfflineTools.runMutationCalculation(MutState(toRes = "X"), protein('Q')) as ToolError
        assertEquals("突变估算失败", e.title)
        assertEquals("只允许 20 种标准氨基酸字母。", e.desc)
    }

    // ───────────────────────── 密码子调谐 ─────────────────────────

    @org.junit.Test
    fun codon_ecoli_ecori_avoided() {
        // ATG GAA TTC TGA（M-E-F-*）：BEST 回译 TGA→TAA，EcoRI motif GAATTC 经
        // GAA→GAG（ecoli E 次优 0.3）替换回避。CAI 0.5623→0.7401。
        val r = AiOfflineTools.runCodonOptimization(CodonState(), "ATGGAATTCTGA") as CodonResult
        assertEquals("ATGGAGTTCTAA", r.optimizedDna)
        assertEquals(33.3, r.initialGc, 1e-12)
        assertEquals(33.3, r.optimizedGc, 1e-12)
        assertEquals(0.56, r.initialCai, 1e-12)
        assertEquals(0.74, r.optimizedCai, 1e-12)
        assertEquals(1, r.avoidedCount)
        assertEquals(2, r.mutatedCodonsCount)
    }

    @org.junit.Test
    fun codon_yeast_backtranslation_removes_bsai() {
        // GGT CTC GGT CTC（G-L-G-L）→ yeast BEST GGT/TTA，GGTCTC motif 随回译消失。
        val r = AiOfflineTools.runCodonOptimization(CodonState(host = "Yeast"), "GGTCTCGGTCTC") as CodonResult
        assertEquals("GGTTTAGGTTTA", r.optimizedDna)
        assertEquals(66.7, r.initialGc, 1e-12)
        assertEquals(33.3, r.optimizedGc, 1e-12)
        assertEquals(0.32, r.initialCai, 1e-12)
        assertEquals(1.0, r.optimizedCai, 1e-12)
        assertEquals(0, r.avoidedCount)
        assertEquals(2, r.mutatedCodonsCount)
    }

    @org.junit.Test
    fun codon_double_motif_quirk_kept() {
        // GAATTC GAATTC：任一同义替换后全串仍含第二个 motif（main hasMotif 全串
        // 检查 quirk），替换被否决 → motif 保留、avoidedCount=0。
        val r = AiOfflineTools.runCodonOptimization(CodonState(), "GAATTCGAATTC") as CodonResult
        assertEquals("GAATTCGAATTC", r.optimizedDna)
        assertEquals(33.3, r.initialGc, 1e-12)
        assertEquals(33.3, r.optimizedGc, 1e-12)
        assertEquals(1.0, r.initialCai, 1e-12)
        assertEquals(1.0, r.optimizedCai, 1e-12)
        assertEquals(0, r.avoidedCount)
        assertEquals(0, r.mutatedCodonsCount)
    }

    @org.junit.Test
    fun codon_err_paths() {
        val e1 = AiOfflineTools.runCodonOptimization(CodonState(), "") as ToolError
        assertEquals("执行智能序列优化", e1.title)
        assertEquals("未绑定基因编辑器。使用测试序列进行演示。", e1.desc)

        val e2 = AiOfflineTools.runCodonOptimization(CodonState(), "ATGN") as ToolError
        assertEquals("执行智能序列优化", e2.title)
        assertEquals("密码子优化需要无歧义的 DNA 序列（仅限 A、C、G、T）。", e2.desc)

        val e3 = AiOfflineTools.runCodonOptimization(CodonState(), "ATGAA") as ToolError
        assertEquals("执行智能序列优化", e3.title)
        assertEquals("序列长度必须是 3 的倍数（当前 5 bp）。", e3.desc)
    }

    // ───────────────────────── 克隆 SOP ─────────────────────────

    @org.junit.Test
    fun sop_golden_gate_structure() {
        val seq = "ATCG".repeat(15) // 60 bp
        val r = AiOfflineTools.runSopGeneration(SopState(), seq) as SopResult
        assertEquals("Golden Gate 无缝拼接与 PCR 扩增 SOP", r.title)
        assertEquals("5'-ATCGATCGATCGATCGATCG-3'", r.fwdPrimer)
        assertEquals("5'-CGATCGATCGATCGATCGAT-3'", r.revPrimer)
        assertEquals(60, r.productLength)
        // 60bp×30 s/kb（Phanta）→ max(10, round(1.8)) = 10
        assertEquals(10, r.extensionTimeS)
        assertEquals(5, r.pcrRecipe.size)
        assertEquals(6, r.assemblyRecipe.size)
        assertEquals(6, r.pcrCycles.size)
        assertEquals(3, r.assemblyCycles.size)
        // Tm 一致性：SopResult 用同一 PrimerAnalysis 最近邻值（面板已单独 parity）
        val tm = com.spice.nativeapp.core.PrimerAnalysis.tmNearestNeighbor("ATCGATCGATCGATCGATCG")
        assertEquals(Math.round(tm).toInt(), r.primerTm)
        // 配方首行与末行（zh 原文）
        assertEquals("2× Phanta MasterMix", r.pcrRecipe[0].component)
        assertEquals(25.0, r.pcrRecipe[0].volume, 1e-12)
        assertEquals("预混高保真聚合酶，提供高效率与极低突变率", r.pcrRecipe[0].note)
        assertEquals("Nuclease-free H2O", r.pcrRecipe[4].component)
        assertEquals("补足反应总体积至 50 µL", r.pcrRecipe[4].note)
        // Golden Gate 组装体系：酶行 + 温育三步
        assertEquals("Restriction Enzyme BsaI", r.assemblyRecipe[2].component)
        assertEquals("酶切反应 (Digestion)", r.assemblyCycles[0].step)
        assertEquals("交替循环 30 次，实现一步酶切连接", r.assemblyCycles[0].note)
        assertEquals("连接反应 (Ligation)", r.assemblyCycles[1].step)
        assertEquals("", r.assemblyCycles[1].note)
        // PCR 循环关键行：退火 max(50, Tm-3)、延伸 extTime
        assertEquals("${maxOf(50, r.primerTm - 3)}°C", r.pcrCycles[2].temp)
        assertEquals("72°C", r.pcrCycles[3].temp)
        assertEquals("10 s", r.pcrCycles[3].time)
    }

    @org.junit.Test
    fun sop_gibson_structure() {
        val r = AiOfflineTools.runSopGeneration(SopState(method = "Gibson Assembly"), "ATCG".repeat(15)) as SopResult
        assertEquals("Gibson Assembly 无缝拼接与 PCR 扩增 SOP", r.title)
        assertEquals(4, r.assemblyRecipe.size)
        assertEquals(2, r.assemblyCycles.size)
        assertEquals("2× Gibson Assembly Mix", r.assemblyRecipe[0].component)
        assertEquals("恒温拼接 (Isothermal Assembly)", r.assemblyCycles[0].step)
    }

    @org.junit.Test
    fun sop_err_unlinked_gene() {
        val e = AiOfflineTools.runSopGeneration(SopState(), "") as ToolError
        assertEquals("无缝拼接与 PCR 扩增 SOP", e.title)
        assertEquals("未绑定基因编辑器。使用测试序列进行演示。", e.desc)
    }

    // ───────────────────────── SOP Markdown 导出 ─────────────────────────

    @org.junit.Test
    fun sop_markdown_full_text() {
        val r = SopResult(
            title = "Golden Gate 无缝拼接与 PCR 扩增 SOP",
            fwdPrimer = "5'-ATGC-3'",
            revPrimer = "5'-GCAT-3'",
            primerTm = 62,
            productLength = 120,
            extensionTimeS = 10,
            pcrRecipe = listOf(
                RecipeRow("2× Phanta MasterMix", 25.0, "预混高保真聚合酶，提供高效率与极低突变率"),
                RecipeRow("Fwd Primer (10 µM)", 2.0, "上游引物，退火温度：62°C"),
                RecipeRow("Nuclease-free H2O", 20.0, "补足反应总体积至 50 µL")
            ),
            assemblyRecipe = listOf(
                RecipeRow("pUC19 Vector (50 ng/µL)", 1.5, "T-Vector 或 pUC19 骨架"),
                RecipeRow("Nuclease-free H2O", 11.0, "补足反应体积至 20 µL")
            ),
            pcrCycles = listOf(
                CycleRow("预变性 (Initial Denac.)", "95°C", "3 min", "模板完全解链"),
                CycleRow("保存 (Hold)", "4°C", "∞", "稳定保持状态")
            ),
            assemblyCycles = listOf(
                CycleRow("酶切反应 (Digestion)", "37°C", "3 min", "交替循环 30 次，实现一步酶切连接"),
                CycleRow("连接反应 (Ligation)", "16°C", "4 min", "")
            )
        )
        val md = AiOfflineTools.getSopMarkdown(SopState(), r)
        val expected = buildString {
            append("# Golden Gate 无缝拼接与 PCR 扩增 SOP\n\n")
            append("验证引物信息 (Primers)\n\n")
            append("- **上游引物 (Fwd Primer)**: `5'-ATGC-3'` (Tm: 62°C)\n")
            append("- **下游引物 (Rev Primer)**: `5'-GCAT-3'` (Tm: 62°C)\n")
            append("- **产物预估长度**: 120 bp\n\n")
            append("## 2. 高保真 PCR 反应体系 (50 µL)\n")
            append("| 组分 (Component) | 加入体积 (Volume) | 备注 (Note) |\n")
            append("| :--- | :--- | :--- |\n")
            append("| 2× Phanta MasterMix | 25 µL | 预混高保真聚合酶，提供高效率与极低突变率 |\n")
            append("| Fwd Primer (10 µM) | 2 µL | 上游引物，退火温度：62°C |\n")
            append("| Nuclease-free H2O | 20 µL | 补足反应总体积至 50 µL |\n")
            append("\n")
            append("## 3. PCR 反应热循环程序\n")
            append("| 步骤 (Step) | 温度 (Temp) | 时间 (Time) | 备注 (Note) |\n")
            append("| :--- | :--- | :--- | :--- |\n")
            append("| 预变性 (Initial Denac.) | 95°C | 3 min | 模板完全解链 |\n")
            append("| 保存 (Hold) | 4°C | ∞ | 稳定保持状态 |\n")
            append("\n")
            append("## 4. Golden Gate 组装反应体系 (20 µL)\n")
            append("| 组分 (Component) | 加入体积 (Volume) | 备注 (Note) |\n")
            append("| :--- | :--- | :--- |\n")
            append("| pUC19 Vector (50 ng/µL) | 1.5 µL | T-Vector 或 pUC19 骨架 |\n")
            append("| Nuclease-free H2O | 11 µL | 补足反应体积至 20 µL |\n")
            append("\n")
            append("## 5. 组装温育程序\n")
            append("| 步骤 (Step) | 温度 (Temp) | 时间 (Time) | 备注 (Note) |\n")
            append("| :--- | :--- | :--- | :--- |\n")
            append("| 酶切反应 (Digestion) | 37°C | 3 min | 交替循环 30 次，实现一步酶切连接 |\n")
            append("| 连接反应 (Ligation) | 16°C | 4 min |  |\n")
        }
        assertEquals(expected, md)
    }
}
