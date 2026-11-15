package com.spice.nativeapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.AiOfflineTools
import com.spice.nativeapp.core.I18n

/**
 * Co-Pilot「计算工具箱」tab（T8 #69）：main AiCopilot.svelte 工具面板 1:1。
 *
 * - 三子工具：突变热力学（蛋白工作区）/ 密码子调谐、克隆 SOP（基因工作区）；
 *   跨工作区点击 → main pushToast('warn', workspaceMismatch, …tip) 的状态行等价。
 * - main pushToast（复制/导入/应用成功失败）在本面板以顶部状态行呈现（安卓 toast 惯例）。
 * - 工具状态持久化到 SharedPreferences spice_ai_mut_tool / spice_ai_codon_tool /
 *   spice_ai_sop_tool（同 main localStorage 键）。
 * - main 的复制走 navigator.clipboard；安卓 LocalClipboardManager。
 */
@Composable
internal fun AiToolsPanel(
    workspace: AiWorkspace,
    sequence: String,
    onApplyDna: ((String) -> Unit)?,
    onApplyProtein: ((String) -> Unit)?,
    onImportMutations: ((List<Triple<Int, Char, Char>>) -> Unit)?
) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
    }

    // ── 子工具选择（main activeTool：protein→mutation / gene→codon 默认）──
    var activeTool by remember { mutableStateOf(if (workspace == AiWorkspace.PROTEIN) "mutation" else "codon") }
    LaunchedEffect(workspace) {
        activeTool = if (workspace == AiWorkspace.PROTEIN) "mutation" else "codon"
    }

    // ── 面板内状态行（toast 等价）──
    var status by remember { mutableStateOf<String?>(null) }

    // ── 三工具状态（持久化，对齐 main spice_ai_*_tool）──
    val mutState = remember { restore(AiOfflineTools.MutState(), prefs, "spice_ai_mut_tool") { json, s ->
        json.optInt("pos", 0).takeIf { it != 0 }?.let { s.pos = it }
        s.fromRes = json.optString("fromRes", s.fromRes)
        s.toRes = json.optString("toRes", s.toRes)
        s.ph = json.optDouble("ph", s.ph)
        s.tempK = json.optInt("tempK", s.tempK)
    } }
    val codonState = remember { restore(AiOfflineTools.CodonState(), prefs, "spice_ai_codon_tool") { json, s ->
        s.host = json.optString("host", s.host)
        s.targetGc = json.optInt("targetGc", s.targetGc)
        s.avoidBsaI = json.optBoolean("avoidBsaI", s.avoidBsaI)
        s.avoidBsmBI = json.optBoolean("avoidBsmBI", s.avoidBsmBI)
        s.avoidEcoRI = json.optBoolean("avoidEcoRI", s.avoidEcoRI)
    } }
    val sopState = remember { restore(AiOfflineTools.SopState(), prefs, "spice_ai_sop_tool") { json, s ->
        s.method = json.optString("method", s.method)
        s.enzyme = json.optString("enzyme", s.enzyme)
        s.polymerase = json.optString("polymerase", s.polymerase)
    } }
    fun persistMut() = persistPrefs(prefs, "spice_ai_mut_tool") {
        put("pos", mutState.pos).put("fromRes", mutState.fromRes).put("toRes", mutState.toRes)
            .put("ph", mutState.ph).put("tempK", mutState.tempK)
    }
    fun persistCodon() = persistPrefs(prefs, "spice_ai_codon_tool") {
        put("host", codonState.host).put("targetGc", codonState.targetGc)
            .put("avoidBsaI", codonState.avoidBsaI).put("avoidBsmBI", codonState.avoidBsmBI)
            .put("avoidEcoRI", codonState.avoidEcoRI)
    }
    fun persistSop() = persistPrefs(prefs, "spice_ai_sop_tool") {
        put("method", sopState.method).put("enzyme", sopState.enzyme).put("polymerase", sopState.polymerase)
    }

    var mutResult by remember { mutableStateOf<AiOfflineTools.MutResult?>(null) }
    var codonResult by remember { mutableStateOf<AiOfflineTools.CodonResult?>(null) }
    var sopResult by remember { mutableStateOf<AiOfflineTools.SopResult?>(null) }

    // main switchTool：跨工作区 → warn toast，不切换
    fun switchTool(tool: String) {
        if (workspace == AiWorkspace.PROTEIN && (tool == "codon" || tool == "sop")) {
            status = I18n.t("工作区不匹配") + " — " +
                I18n.t("当前在【蛋白验证】工作区，请先在顶部导航栏切换至【基因设计】页面以启用该工具！")
            return
        }
        if (workspace == AiWorkspace.GENE && tool == "mutation") {
            status = I18n.t("工作区不匹配") + " — " +
                I18n.t("当前在【基因设计】工作区，请先在顶部导航栏切换至【蛋白验证】页面以启用该工具！")
            return
        }
        status = null
        activeTool = tool
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ── 子工具选择条（main toolbox-selector-bar）──
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolChip(I18n.t("突变热力学"), activeTool == "mutation", workspace == AiWorkspace.PROTEIN) { switchTool("mutation") }
            ToolChip(I18n.t("密码子调谐"), activeTool == "codon", workspace == AiWorkspace.GENE) { switchTool("codon") }
            ToolChip(I18n.t("克隆 SOP"), activeTool == "sop", workspace == AiWorkspace.GENE) { switchTool("sop") }
        }
        status?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }

        when (activeTool) {
            // ═══════════ 工具 1：突变自由能 ═══════════
            "mutation" -> {
                Text(I18n.t("SPICE-SAC 突变自由能与结构硬度估算"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                LinkedAlert(
                    linked = sequence.isNotEmpty(),
                    linkedText = I18n.t("已绑定当前折叠工作台序列（长度：{n} aa）").replace("{n}", sequence.length.toString()),
                    unlinkedText = I18n.t("未绑定折叠工作台。使用预设的通用测试序列。")
                )
                var posText by remember { mutableStateOf(mutState.pos.toString()) }
                var phText by remember { mutableStateOf(mutState.ph.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = posText, onValueChange = { posText = it; posText.toIntOrNull()?.let { v -> mutState.pos = v; persistMut() } },
                        label = { Text(I18n.t("突变位点"), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = mutState.fromRes, onValueChange = { if (it.length <= 1) { mutState.fromRes = it.uppercase(); persistMut() } },
                        label = { Text(I18n.t("野生型 (From)"), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DropdownField(
                        label = I18n.t("突变体 (To)"),
                        options = listOf("A","C","D","E","F","G","H","I","K","L","M","N","P","Q","R","S","T","V","W","Y"),
                        selected = mutState.toRes,
                        onSelect = { mutState.toRes = it; persistMut() },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = phText, onValueChange = { phText = it; it.toDoubleOrNull()?.let { v -> mutState.ph = v; persistMut() } },
                        label = { Text(I18n.t("pH 环境"), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                }
                Text(I18n.t("模拟温度 (K)") + " ${mutState.tempK} K", fontSize = 11.sp)
                Slider(
                    value = mutState.tempK.toFloat(),
                    onValueChange = { mutState.tempK = it.toInt(); persistMut() },
                    valueRange = 250f..450f,
                    steps = 39
                )
                Button(
                    onClick = {
                        when (val r = AiOfflineTools.runMutationCalculation(mutState, sequence)) {
                            is AiOfflineTools.ToolError -> { status = r.title + " — " + r.desc; mutResult = null }
                            is AiOfflineTools.MutResult -> { status = null; mutResult = r }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) { Text(I18n.t("运行 SPICE 物理计算预测"), fontSize = 12.sp) }

                mutResult?.let { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(I18n.t("预测结果报告"), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Badge(containerColor = if (r.isStable) androidx.compose.ui.graphics.Color(0xFF53d769) else androidx.compose.ui.graphics.Color(0xFFff5d5d)) {
                                    Text("ΔΔG = ${if (r.ddG > 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", r.ddG)} kcal/mol", fontSize = 9.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(I18n.t("预测结果报告"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(r.classification, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Text(if (r.isStable) "Stabilizing" else "Destabilizing", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(I18n.t("重中和所需溶剂离子"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (r.solventNeutralize == "Neutral") "Neutral" else r.solventNeutralize, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        "Net Charge: ${if (r.chargeChange > 0) "+" else ""}${trimJs(r.chargeChange)}e",
                                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(r.explanation, fontSize = 10.sp)
                            OutlinedButton(
                                onClick = {
                                    if (onImportMutations != null) {
                                        onImportMutations(listOf(Triple(mutState.pos, mutState.fromRes.first(), mutState.toRes.first())))
                                        status = I18n.t("AI 突变导入成功") + " — " +
                                            I18n.t("已向设计工作台导入 {n} 个突变位点！").replace("{n}", "1")
                                    } else {
                                        status = I18n.t("未能找到活动的蛋白质突变面板。请先切换到“蛋白质”工作区。")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(I18n.t("一键导入此单个突变至工作台"), fontSize = 11.sp) }
                        }
                    }
                }
            }

            // ═══════════ 工具 2：密码子调谐 ═══════════
            "codon" -> {
                Text(I18n.t("多宿主密码子调谐器 & GC 约束平滑"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                LinkedAlert(
                    linked = sequence.isNotEmpty(),
                    linkedText = I18n.t("已拉取当前基因编辑器序列（{n} bp）").replace("{n}", sequence.length.toString()),
                    unlinkedText = I18n.t("未绑定基因编辑器。使用测试序列进行演示。")
                )
                DropdownField(
                    label = I18n.t("目标表达宿主"),
                    options = listOf("E. coli", "Yeast", "Human"),
                    labels = listOf("E. coli", "S. cerevisiae", "H. sapiens"),
                    selected = codonState.host,
                    onSelect = { codonState.host = it; persistCodon() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(I18n.t("目标平滑 GC 含量 (%)") + " ${codonState.targetGc}%", fontSize = 11.sp)
                Slider(
                    value = codonState.targetGc.toFloat(),
                    onValueChange = { codonState.targetGc = it.toInt(); persistCodon() },
                    valueRange = 35f..65f,
                    steps = 29
                )
                Text(I18n.t("避开限制性内切酶克隆位点"), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("BsaI" to { v: Boolean -> codonState.avoidBsaI = v }, "BsmBI" to { v: Boolean -> codonState.avoidBsmBI = v }, "EcoRI" to { v: Boolean -> codonState.avoidEcoRI = v }).forEach { (name, setter) ->
                        val checked = when (name) { "BsaI" -> codonState.avoidBsaI; "BsmBI" -> codonState.avoidBsmBI; else -> codonState.avoidEcoRI }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { setter(it); persistCodon() })
                            Text(name, fontSize = 11.sp)
                        }
                    }
                }
                Button(
                    onClick = {
                        when (val r = AiOfflineTools.runCodonOptimization(codonState, sequence)) {
                            is AiOfflineTools.ToolError -> { status = r.title + " — " + r.desc; codonResult = null }
                            is AiOfflineTools.CodonResult -> { status = null; codonResult = r }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) { Text(I18n.t("执行智能序列优化"), fontSize = 12.sp) }

                codonResult?.let { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(I18n.t("密码子优化报告"), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                val pct = Math.round(((r.optimizedCai - r.initialCai) / r.initialCai) * 100)
                                Badge(containerColor = androidx.compose.ui.graphics.Color(0xFF53d769)) {
                                    Text(I18n.tf("适应指数 (CAI) +{v1}%", pct), fontSize = 9.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(I18n.t("GC 含量"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${r.initialGc}% → ${r.optimizedGc}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(I18n.t("CAI 指数"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$r.initialCai → $r.optimizedCai", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Text(I18n.t("突变/调整密码子数: {n} 个").replace("{n}", r.mutatedCodonsCount.toString()), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(I18n.t("破除高危限制性内切酶切位点: {n} 处").replace("{n}", r.avoidedCount.toString()), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(I18n.t("优化后 DNA 序列 (5' -> 3')"), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(r.optimizedDna))
                                    status = I18n.t("复制成功") + " — " + I18n.t("已复制代码子优化 DNA 序列")
                                }) { Text(I18n.t("复制"), fontSize = 10.sp) }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Text(r.optimizedDna, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(6.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    if (onApplyDna != null) {
                                        onApplyDna(r.optimizedDna)
                                        status = I18n.t("AI DNA 序列应用成功") + " — " + I18n.t("已成功将优化后的序列加载到基因编辑器中。")
                                    } else {
                                        status = I18n.t("加载失败") + " — " + I18n.t("未能找到活动的基因编辑器。请先切换到“基因设计”工作区。")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(I18n.t("一键应用优化后序列到基因编辑器"), fontSize = 11.sp) }
                        }
                    }
                }
            }

            // ═══════════ 工具 3：克隆 SOP ═══════════
            "sop" -> {
                Text(I18n.t("分子克隆与引物设计 SOP 自适应构建"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                DropdownField(
                    label = I18n.t("重组克隆组装方法"),
                    options = listOf("Golden Gate", "Gibson Assembly"),
                    labels = listOf("Golden Gate Assembly", "Gibson Assembly"),
                    selected = sopState.method,
                    onSelect = { sopState.method = it; persistSop() },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DropdownField(
                        label = I18n.t("克隆限制酶"),
                        options = listOf("BsaI", "BsmBI"),
                        labels = listOf("BsaI-HF v2", "BsmBI-v2"),
                        selected = sopState.enzyme,
                        onSelect = { sopState.enzyme = it; persistSop() },
                        enabled = sopState.method == "Golden Gate",
                        modifier = Modifier.weight(1f)
                    )
                    DropdownField(
                        label = I18n.t("高保真聚合酶 (PCR)"),
                        options = listOf("Phanta MasterMix", "Q5 High-Fidelity", "Taq Polymerase"),
                        labels = listOf("Phanta Max", "Q5 Polymerase", "Taq Mix"),
                        selected = sopState.polymerase,
                        onSelect = { sopState.polymerase = it; persistSop() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = {
                        when (val r = AiOfflineTools.runSopGeneration(sopState, sequence)) {
                            is AiOfflineTools.ToolError -> { status = r.title + " — " + r.desc; sopResult = null }
                            is AiOfflineTools.SopResult -> { status = null; sopResult = r }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) { Text(I18n.t("生成高度定制克隆 SOP 方案"), fontSize = 12.sp) }

                sopResult?.let { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(r.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(AiOfflineTools.getSopMarkdown(sopState, r)))
                                    status = I18n.t("复制成功") + " — " + I18n.t("内容已成功复制到剪贴板！")
                                }) { Text(I18n.t("复制 Markdown"), fontSize = 10.sp) }
                            }
                            // 引物卡
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(I18n.t("突变片段扩增引物对 (Designed Primers)"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("Fwd Primer (5'-3'): " + r.fwdPrimer, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text("Rev Primer (5'-3'): " + r.revPrimer, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Text(
                                        I18n.t("引物退火温度: {tm} °C | 预估扩增产物长度: {len} bp")
                                            .replace("{tm}", r.primerTm.toString()).replace("{len}", r.productLength.toString()),
                                        fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            // Step 1: PCR 反应体系
                            Text(I18n.t("Step 1: PCR 扩增反应体系 (50 µL)"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            RecipeTable(
                                headers = listOf(I18n.t("组分"), I18n.t("体积 (µL)")),
                                rows = r.pcrRecipe.map { listOf(it.component, String.format(java.util.Locale.US, "%.1f", it.volume)) }
                            )
                            // Step 2: PCR 循环参数
                            Text(I18n.t("Step 2: High-Fidelity PCR 循环参数"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            RecipeTable(
                                headers = listOf(I18n.t("步骤"), I18n.t("参数"), I18n.t("时间")),
                                rows = r.pcrCycles.map { listOf(it.step, it.temp, it.time) }
                            )
                            // Step 3: 组装体系
                            Text(I18n.t("Step 3: {method} 组装体系 (20 µL)").replace("{method}", sopState.method), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            RecipeTable(
                                headers = listOf(I18n.t("组分"), I18n.t("体积 (µL)")),
                                rows = r.assemblyRecipe.map { listOf(it.component, String.format(java.util.Locale.US, "%.1f", it.volume)) }
                            )
                            // Step 4: 组装温育条件
                            Text(I18n.t("Step 4: 组装反应温育条件"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            RecipeTable(
                                headers = listOf(I18n.t("步骤"), I18n.t("参数"), I18n.t("时间")),
                                rows = r.assemblyCycles.map { listOf(it.step, it.temp, it.time) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ───────────────────────── 辅助组件 ─────────────────────────

/** main context-linked-alert / context-warning-alert */
@Composable
private fun LinkedAlert(linked: Boolean, linkedText: String, unlinkedText: String) {
    val color = if (linked) androidx.compose.ui.graphics.Color(0xFF53d769) else androidx.compose.ui.graphics.Color(0xFFff5d5d)
    Text(
        (if (linked) "● " else "⚠ ") + if (linked) linkedText else unlinkedText,
        fontSize = 10.sp, color = color
    )
}

/** main tool-selector-btn（禁用态 = 跨工作区） */
@Composable
private fun ToolChip(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 10.sp) }
}

/** 下拉选择（main pix-select） */
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String>? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val idx = options.indexOf(selected).coerceAtLeast(0)
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text((labels?.getOrNull(idx)) ?: options[idx], fontSize = 11.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text((labels?.getOrNull(i)) ?: opt, fontSize = 11.sp) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

/** main recipe-table（表头 + 等宽行） */
@Composable
private fun RecipeTable(headers: List<String>, rows: List<List<String>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                headers.forEachIndexed { i, h ->
                    Text(h, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(if (i == 0) 1.6f else 1f))
                }
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { i, cell ->
                        Text(cell, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(if (i == 0) 1.6f else 1f))
                    }
                }
            }
        }
    }
}

// ───────────────────────── 持久化辅助 ─────────────────────────

/** 恢复 SharedPreferences JSON 到工具状态（main localStorage spice_ai_*_tool） */
private fun <T> restore(state: T, prefs: android.content.SharedPreferences, key: String, apply: (org.json.JSONObject, T) -> Unit): T {
    runCatching {
        val saved = prefs.getString(key, null) ?: return state
        apply(org.json.JSONObject(saved), state)
    }
    return state
}

private fun persistPrefs(prefs: android.content.SharedPreferences, key: String, build: org.json.JSONObject.() -> Unit) {
    val json = org.json.JSONObject().apply(build)
    prefs.edit().putString(key, json.toString()).apply()
}

/** JS Number toString（整数去小数点） */
private fun trimJs(v: Double): String {
    val r = Math.round(v * 100.0) / 100.0
    return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
}
