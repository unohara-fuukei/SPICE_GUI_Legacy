package com.spice.nativeapp.core

/**
 * Co-Pilot 计算工具箱（T8，#69）：main src/lib/ui/aiState.svelte.ts 三个离线工具的 1:1 移植。
 *
 * - runMutationCalculation：SPICE-SAC 突变自由能估算——复用与 main
 *   genome/biophysics/proteinAnalysis.ts predictMutationalStabilityDdG 同数的
 *   ProteinBiophysics.mutationalStabilityDdg（体积/疏水/电荷三项经验式），
 *   details 文案按 main 同阈值分支取 ppDdg* 词条；再叠加 getCharge 溶剂
 *   重中和推论（K/R、H、D/E 的 pH 分段电荷）。
 * - runCodonOptimization：多宿主密码子调谐——BEST_CODONS 全量回译后按
 *   BsaI/BsmBI/EcoRI 开关做同义位点回避（500 次护栏、首尾密码子窗口内按
 *   宿主权重降序换码），CAI/GC 前后对比。注意 main 的 targetGc 滑杆只影响
 *   展示、不参与算法——1:1 保留该 quirk。
 * - runSopGeneration：克隆 SOP——SantaLucia 最近邻 Tm 取引物退火段、
 *   Phanta/Q5/Taq 延伸速度、Golden Gate / Gibson 两套体系与温育程序。
 * - getSopMarkdown：main AiCopilot.svelte getSopMarkdown 的逐行复刻
 *   （markdown 表格 + copilotMd 与 sopTableHeader、sopHeadingAsmMix 文案）。
 *
 * 词条键一律为 main zh.json 原文（I18n.t / I18n.tf / 命名占位符 replace）。
 * 所有 Math.round(x*10)/10 等 JS 语义用 Math.round 复刻（正数 HALF-UP，同向 +∞）。
 */
object AiOfflineTools {

    // ───────────────────────── 状态与结果 ─────────────────────────

    /** main mutTool（默认值 aiState.svelte.ts） */
    data class MutState(
        var pos: Int = 103,
        var fromRes: String = "Q",
        var toRes: String = "S",
        var ph: Double = 7.0,
        var tempK: Int = 310
    )

    data class MutResult(
        val ddG: Double,               // kcal/mol
        val classification: String,   // Stabilizing / Neutral / Destabilizing…
        val chargeChange: Double,      // e
        val solventNeutralize: String, // "Na+" / "Cl-" / "Neutral"
        val isStable: Boolean,
        val explanation: String
    )

    /** main codonTool（默认值 aiState.svelte.ts；targetGc 仅展示） */
    data class CodonState(
        var host: String = "E. coli",
        var targetGc: Int = 50,
        var avoidBsaI: Boolean = true,
        var avoidBsmBI: Boolean = true,
        var avoidEcoRI: Boolean = true
    )

    data class CodonResult(
        val optimizedDna: String,
        val initialGc: Double,
        val optimizedGc: Double,
        val initialCai: Double,
        val optimizedCai: Double,
        val avoidedCount: Int,
        val mutatedCodonsCount: Int
    )

    /** main sopTool（默认值 aiState.svelte.ts） */
    data class SopState(
        var method: String = "Golden Gate",       // Golden Gate | Gibson Assembly
        var enzyme: String = "BsaI",              // BsaI | BsmBI
        var polymerase: String = "Phanta MasterMix"
    )

    data class RecipeRow(val component: String, val volume: Double, val note: String)
    data class CycleRow(val step: String, val temp: String, val time: String, val note: String)

    data class SopResult(
        val title: String,
        val fwdPrimer: String,
        val revPrimer: String,
        val primerTm: Int,
        val productLength: Int,
        val extensionTimeS: Int,
        val pcrRecipe: List<RecipeRow>,
        val assemblyRecipe: List<RecipeRow>,
        val pcrCycles: List<CycleRow>,
        val assemblyCycles: List<CycleRow>
    )

    /** main pushToast('error', title, desc) 的等价返回（安卓惯例：面板内状态行） */
    data class ToolError(val title: String, val desc: String)

    // ───────────────────────── 工具 1：突变自由能 ─────────────────────────

    /**
     * main aiState.runMutationCalculation 1:1。
     * proteinSequence 为空 / 位置越界 / 目标残基非法 → ToolError（原 toast 对）。
     */
    fun runMutationCalculation(state: MutState, proteinSequence: String): Any {
        if (proteinSequence.isEmpty()) {
            return ToolError(I18n.t("突变估算失败"), I18n.t("未绑定折叠工作台。使用预设的通用测试序列。"))
        }
        val pos = state.pos
        if (pos < 1 || pos > proteinSequence.length) {
            return ToolError(
                I18n.t("突变估算失败"),
                I18n.tf("残基位置 #{v1} 超出当前序列长度 (1-{v2} aa)", pos, proteinSequence.length)
            )
        }
        val fromRes = proteinSequence[pos - 1].uppercaseChar().toString()
        val toRes = state.toRes.uppercase()
        if (!Regex("^[ACDEFGHIKLMNPQRSTVWY]$").matches(toRes)) {
            return ToolError(I18n.t("突变估算失败"), I18n.t("只允许 20 种标准氨基酸字母。"))
        }
        val ph = state.ph
        state.fromRes = fromRes

        // 与蛋白面板同一个经验 ddG 预测器（ProteinBiophysics 1:1 于
        // proteinAnalysis.predictMutationalStabilityDdG 的数值部分）
        val prediction = ProteinBiophysics.mutationalStabilityDdg(fromRes[0], pos, toRes[0])
        val ddG = Math.round(prediction.ddG * 100.0) / 100.0

        // details 文案按 main 同阈值分支（ppDdg* 词条）
        val label = "$fromRes$pos$toRes"
        val details = if (fromRes == toRes) {
            I18n.t("野生型与突变型残基一致，无稳定性能变。")
        } else when (prediction.classification) {
            "Highly Stabilizing" ->
                I18n.tf("突变 {v1} 极大地提升了蛋白质的热力学稳定性 (ΔΔG < -1.0 kcal/mol)。主要是由于增大了核心疏水坍塌能。", label)
            "Stabilizing" ->
                I18n.tf("突变 {v1} 有利于提高蛋白质的热力学稳定性 (ΔΔG = {v2} kcal/mol)。", label, jsToFixed2(prediction.ddG))
            "Neutral" ->
                I18n.tf("突变 {v1} 对主链热力学稳定性的影响基本呈中性 (ΔΔG = {v2} kcal/mol)。", label, jsToFixed2(prediction.ddG))
            "Destabilizing" ->
                I18n.tf("突变 {v1} 会损害蛋白质的热力学稳定性 (ΔΔG = {v2} kcal/mol)。可能破坏了潜在的盐桥或氢键结构。", label, jsToFixed2(prediction.ddG))
            else ->
                I18n.tf("突变 {v1} 严重破坏了蛋白质的主链折叠核心 (ΔΔG > 1.0 kcal/mol)。这通常是由于引入了严重的侧链空间碰撞 (Steric Clash) 或使核心电荷异化。", label)
        }

        // main getCharge：pH 分段电荷
        fun getCharge(residue: String, phVal: Double): Double = when (residue[0]) {
            'K', 'R' -> 1.0
            'H' -> if (phVal < 6.0) 1.0 else if (phVal < 7.5) 0.4 else 0.0
            'D', 'E' -> if (phVal > 4.5) -1.0 else if (phVal > 3.5) -0.5 else 0.0
            else -> 0.0
        }
        val chargeChange = getCharge(toRes, ph) - getCharge(fromRes, ph)
        var solventNeutralize = "Neutral"
        if (chargeChange > 0.1) solventNeutralize = "Cl-"
        else if (chargeChange < -0.1) solventNeutralize = "Na+"

        val chargeNote = if (solventNeutralize == "Neutral") "" else {
            "\n\n" + I18n.tf(
                "• **溶剂在线重中和**：突变引起系统净电荷变化（{v1}e），SPICE 引擎已自动在溶剂层中对应插入 {v2} 离子以保持电解质平衡。",
                (if (chargeChange > 0) "+" else "") + trimDouble(chargeChange),
                solventNeutralize
            )
        }

        return MutResult(
            ddG = ddG,
            classification = prediction.classification,
            chargeChange = chargeChange,
            solventNeutralize = solventNeutralize,
            isStable = ddG < -0.2,
            explanation = details + chargeNote
        )
    }

    // ───────────────────────── 工具 2：密码子调谐 ─────────────────────────

    /**
     * main aiState.runCodonOptimization 1:1。
     * raw 取工作区 DNA；未绑定 / 非法字母 / 非三倍长 → ToolError。
     * targetGc 不参与计算（main quirk，仅 UI 滑杆展示）。
     */
    fun runCodonOptimization(state: CodonState, dnaSequence: String): Any {
        val raw = dnaSequence.uppercase().replace(Regex("[\\s\\n\\r\\t]"), "")
        if (raw.isEmpty() || raw.length < 3) {
            return ToolError(I18n.t("执行智能序列优化"), I18n.t("未绑定基因编辑器。使用测试序列进行演示。"))
        }
        if (!Regex("^[ACGT]+$").matches(raw)) {
            return ToolError(I18n.t("执行智能序列优化"), I18n.t("密码子优化需要无歧义的 DNA 序列（仅限 A、C、G、T）。"))
        }
        if (raw.length % 3 != 0) {
            return ToolError(I18n.t("执行智能序列优化"), I18n.t("序列长度必须是 3 的倍数（当前 {n} bp）。").replace("{n}", raw.length.toString()))
        }

        val hostMap = mapOf("E. coli" to "ecoli", "Yeast" to "yeast", "Human" to "human")
        val host = hostMap[state.host] ?: "ecoli"

        var optimizedDna = CodonOpt.optimizeCodons(raw, host, true).optimized

        // 同义位点回避（main motifs 顺序照抄）
        val motifs = listOf(
            "GGTCTC" to state.avoidBsaI,
            "GAGACC" to state.avoidBsaI,
            "CGTCTC" to state.avoidBsmBI,
            "GAGACG" to state.avoidBsmBI,
            "GAATTC" to state.avoidEcoRI
        ).filter { it.second }.map { it.first }

        fun alternativesFor(codon: String): List<String> {
            val aa = CodonOpt.getAminoAcidOfCodon(codon) ?: return emptyList()
            val weights = CodonOpt.CODON_WEIGHTS[host]?.get(aa) ?: emptyMap()
            return weights.entries.sortedByDescending { it.value }.map { it.key }
        }
        fun hasMotif(dna: String) = motifs.any { dna.contains(it) }

        var avoidedCount = 0
        for (motif in motifs) {
            var guard = 0
            while (optimizedDna.contains(motif) && guard < 500) {
                guard++
                val idx = optimizedDna.indexOf(motif)
                val firstCodon = Math.floorDiv(idx, 3)
                val lastCodon = Math.floorDiv(idx + motif.length - 1, 3)
                var replaced = false
                var codonIndex = firstCodon
                while (codonIndex <= lastCodon && !replaced) {
                    val codon = optimizedDna.substring(codonIndex * 3, codonIndex * 3 + 3)
                    for (alt in alternativesFor(codon)) {
                        if (alt == codon) continue
                        val next = optimizedDna.substring(0, codonIndex * 3) + alt +
                            optimizedDna.substring(codonIndex * 3 + 3)
                        if (!hasMotif(next)) {
                            optimizedDna = next
                            avoidedCount++
                            replaced = true
                            break
                        }
                    }
                    codonIndex++
                }
                if (!replaced) break
            }
        }

        val initial = CodonOpt.calculateCai(raw, host)
        val finalCai = CodonOpt.calculateCai(optimizedDna, host)
        var mutatedCodonsCount = 0
        val count = minOf(raw.length / 3, optimizedDna.length / 3)
        for (i in 0 until count) {
            if (raw.substring(i * 3, i * 3 + 3) != optimizedDna.substring(i * 3, i * 3 + 3)) mutatedCodonsCount++
        }

        return CodonResult(
            optimizedDna = optimizedDna,
            initialGc = Math.round(initial.gc * 10.0) / 10.0,
            optimizedGc = Math.round(finalCai.gc * 10.0) / 10.0,
            initialCai = Math.round(initial.cai * 100.0) / 100.0,
            optimizedCai = Math.round(finalCai.cai * 100.0) / 100.0,
            avoidedCount = avoidedCount,
            mutatedCodonsCount = mutatedCodonsCount
        )
    }

    // ───────────────────────── 工具 3：克隆 SOP ─────────────────────────

    /** main aiState.runSopGeneration 1:1 */
    fun runSopGeneration(state: SopState, dnaSequence: String): Any {
        val seq = dnaSequence.uppercase().replace(Regex("[^ATCG]"), "")
        if (seq.isEmpty()) {
            return ToolError(I18n.t("无缝拼接与 PCR 扩增 SOP"), I18n.t("未绑定基因编辑器。使用测试序列进行演示。"))
        }

        val len = seq.length
        val pm = state.polymerase
        val method = state.method
        val enzyme = state.enzyme

        // 真实最近邻 Tm（退火段）
        val fwdAnneal = seq.substring(0, minOf(20, len))
        val revAnneal = Bio.revComp(seq.substring(maxOf(0, len - 20)))
        fun tmOf(primer: String): Int? {
            val value = PrimerAnalysis.tmNearestNeighbor(primer)
            return if (value.isFinite()) Math.round(value).toInt() else null
        }
        val primerTm = tmOf(fwdAnneal) ?: tmOf(revAnneal) ?: 60

        var speedSecPerKb = 30
        if (pm.contains("Q5")) speedSecPerKb = 20
        else if (pm.contains("Taq")) speedSecPerKb = 60

        val extTime = maxOf(10, Math.round(len / 1000.0 * speedSecPerKb).toInt())

        val pcrRecipe = listOf(
            RecipeRow("2× $pm", 25.0, I18n.t("预混高保真聚合酶，提供高效率与极低突变率")),
            RecipeRow("Fwd Primer (10 µM)", 2.0, I18n.tf("上游引物，退火温度：{v1}°C", primerTm)),
            RecipeRow("Rev Primer (10 µM)", 2.0, I18n.tf("下游引物，退火温度：{v1}°C", primerTm)),
            RecipeRow("Template DNA (10 ng/µL)", 1.0, I18n.t("反应质粒或 cDNA 模板")),
            RecipeRow("Nuclease-free H2O", 20.0, I18n.t("补足反应总体积至 50 µL"))
        )

        val pcrCycles = listOf(
            CycleRow(I18n.t("预变性 (Initial Denac.)"), "95°C", "3 min", I18n.t("模板完全解链")),
            CycleRow(I18n.t("变性 (Denaturation)"), "98°C", "10 s", I18n.t("共进行 30 循环")),
            CycleRow(I18n.t("退火 (Annealing)"), "${maxOf(50, primerTm - 3)}°C", "15 s", I18n.t("基于引物 Tm 自适应退火")),
            CycleRow(I18n.t("延伸 (Extension)"), "72°C", "${extTime} s", I18n.tf("延伸速度：{v1} s/kb", speedSecPerKb)),
            CycleRow(I18n.t("终延伸 (Final Ext.)"), "72°C", "5 min", I18n.t("完成未接全的片段合成")),
            CycleRow(I18n.t("恒温保存 (Hold)"), "4°C", "∞", I18n.t("稳定保持状态"))
        )

        val assemblyRecipe: List<RecipeRow>
        val assemblyCycles: List<CycleRow>
        if (method == "Golden Gate") {
            assemblyRecipe = listOf(
                RecipeRow("pUC19 Vector (50 ng/µL)", 1.5, I18n.t("T-Vector 或 pUC19 骨架")),
                RecipeRow(I18n.t("PCR Insert (3:1 摩尔比)"), 3.5, I18n.t("待拼接的优化 DNA 片段")),
                RecipeRow("Restriction Enzyme $enzyme", 1.0, I18n.t("IIS型酶，产生非回文粘性末端")),
                RecipeRow("T4 DNA Ligase", 1.0, I18n.t("连接端点")),
                RecipeRow("10× T4 DNA Ligase Buffer", 2.0, I18n.t("提供连接反应必要的 ATP")),
                RecipeRow("Nuclease-free H2O", 11.0, I18n.t("补足反应体积至 20 µL"))
            )
            assemblyCycles = listOf(
                CycleRow(I18n.t("酶切反应 (Digestion)"), "37°C", "3 min", I18n.t("交替循环 30 次，实现一步酶切连接")),
                CycleRow(I18n.t("连接反应 (Ligation)"), "16°C", "4 min", ""),
                CycleRow(I18n.t("终止失活 (Inactivation)"), "80°C", "10 min", I18n.t("失活酶切活性，终止连接反应"))
            )
        } else {
            assemblyRecipe = listOf(
                RecipeRow("2× Gibson Assembly Mix", 10.0, I18n.t("包含 5’ 外切酶、聚合酶与连接酶一管混")),
                RecipeRow("Linearized Vector (50 ng)", 2.0, I18n.t("线性化骨架，带有 20 bp 重叠同源臂")),
                RecipeRow("PCR Insert (3:1 ratio)", 4.0, I18n.t("待克隆片段，同源末端匹配")),
                RecipeRow("ddH2O", 4.0, I18n.t("补足 20 µL 反应体系"))
            )
            assemblyCycles = listOf(
                CycleRow(I18n.t("恒温拼接 (Isothermal Assembly)"), "50°C", "60 min", I18n.t("单管一步组装")),
                CycleRow(I18n.t("保存 (Hold)"), "4°C", "∞", I18n.t("产物可立即用于转化"))
            )
        }

        return SopResult(
            title = "$method ${I18n.t("无缝拼接与 PCR 扩增 SOP")}",
            fwdPrimer = "5'-$fwdAnneal-3'",
            revPrimer = "5'-$revAnneal-3'",
            primerTm = primerTm,
            productLength = len,
            extensionTimeS = extTime,
            pcrRecipe = pcrRecipe,
            assemblyRecipe = assemblyRecipe,
            pcrCycles = pcrCycles,
            assemblyCycles = assemblyCycles
        )
    }

    // ───────────────────────── SOP Markdown 导出 ─────────────────────────

    /** main AiCopilot.svelte getSopMarkdown 1:1（含空行与表分隔行） */
    fun getSopMarkdown(state: SopState, r: SopResult): String {
        val sb = StringBuilder()
        sb.append("# ${r.title}\n\n")
        sb.append(I18n.t("验证引物信息 (Primers)\n") + "\n")
        sb.append(I18n.tf("- **上游引物 (Fwd Primer)**: `{v1}` (Tm: {v2}°C)", r.fwdPrimer, r.primerTm) + "\n")
        sb.append(I18n.tf("- **下游引物 (Rev Primer)**: `{v1}` (Tm: {v2}°C)", r.revPrimer, r.primerTm) + "\n")
        sb.append(I18n.tf("- **产物预估长度**: {v1} bp", r.productLength) + "\n\n")

        sb.append(I18n.t("## 2. 高保真 PCR 反应体系 (50 µL)") + "\n")
        sb.append(I18n.t("| 组分 (Component) | 加入体积 (Volume) | 备注 (Note) |") + "\n")
        sb.append("| :--- | :--- | :--- |\n")
        r.pcrRecipe.forEach { sb.append("| ${it.component} | ${trimDouble(it.volume)} µL | ${it.note} |\n") }
        sb.append("\n")

        sb.append(I18n.t("## 3. PCR 反应热循环程序") + "\n")
        sb.append(I18n.t("| 步骤 (Step) | 温度 (Temp) | 时间 (Time) | 备注 (Note) |\n"))
        sb.append("| :--- | :--- | :--- | :--- |\n")
        r.pcrCycles.forEach { sb.append("| ${it.step} | ${it.temp} | ${it.time} | ${it.note} |\n") }
        sb.append("\n")

        sb.append(I18n.t("## 4. {method} 组装反应体系 (20 µL)\n").replace("{method}", state.method))
        sb.append(I18n.t("| 组分 (Component) | 加入体积 (Volume) | 备注 (Note) |") + "\n")
        sb.append("| :--- | :--- | :--- |\n")
        r.assemblyRecipe.forEach { sb.append("| ${it.component} | ${trimDouble(it.volume)} µL | ${it.note} |\n") }
        sb.append("\n")

        sb.append(I18n.t("## 5. 组装温育程序") + "\n")
        sb.append(I18n.t("| 步骤 (Step) | 温度 (Temp) | 时间 (Time) | 备注 (Note) |\n"))
        sb.append("| :--- | :--- | :--- | :--- |\n")
        r.assemblyCycles.forEach { sb.append("| ${it.step} | ${it.temp} | ${it.time} | ${it.note} |\n") }
        return sb.toString()
    }

    // ───────────────────────── JS 数值语义辅助 ─────────────────────────

    /** JS Number toString：整数无小数位，否则去尾零（main `${chargeChange}` 拼接） */
    private fun trimDouble(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    /** JS toFixed(1)：BigDecimal HALF_UP（NgsQc.jsToFixed2 同源） */
    private fun jsToFixed1(v: Double): String =
        java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

    /** JS toFixed(2)（main details 的 ΔΔG = ddG.toFixed(2)） */
    private fun jsToFixed2(v: Double): String =
        java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
