package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.spice.nativeapp.core.Structure
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ProteinCore
import com.spice.nativeapp.core.ProteinBiophysics
import com.spice.nativeapp.core.Spicep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 蛋白工作台（对齐 main 分支 /protein 页）：
 * 序列输入 + 环境参数 + fold / md / pocket / phase / mut / batch 六面板。
 * 菜单：文件 / 建模与仿真 / 突变与设计 / 窗口。
 */
@Composable
fun ProteinScreen(modifier: Modifier = Modifier) {
    var input by remember {
        mutableStateOf("MKTAYIAKQRQISFVKSHFSRQLEERLGLIEVQANAGAPGDRVLLLAGLEKLLRQGGSVEPVLAALRDELAAELGAPDPAARLRAALEEAGGEP")
    }
    val seq = remember(input) { ProteinCore.clean(input) }
    val props = remember(seq) { if (seq.isNotEmpty()) ProteinCore.props(seq) else null }
    val seqLen = seq.length
    var info by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf<String?>(null) }

    // 环境参数（fold / MD / phase 共用，对齐 main 的 Env 输入）
    var envPh by remember { mutableStateOf("7.0") }
    var envTempK by remember { mutableStateOf("310") }
    var envIonic by remember { mutableStateOf("0.15") }
    val env = ProteinBiophysics.Env(
        ph = envPh.toDoubleOrNull() ?: 7.0,
        tempK = envTempK.toDoubleOrNull() ?: 310.0,
        ionicStrengthM = envIonic.toDoubleOrNull() ?: 0.15
    )

    // 工作台状态
    var fold by remember { mutableStateOf<ProteinBiophysics.FoldOutput?>(null) }
    var md by remember { mutableStateOf<ProteinBiophysics.MdStep?>(null) }
    var scan by remember { mutableStateOf<List<ProteinBiophysics.ScanPoint>?>(null) }
    var pockets by remember { mutableStateOf<List<ProteinBiophysics.Pocket>?>(null) }
    var structure by remember { mutableStateOf<Structure.Parsed?>(null) }
    var batch by remember { mutableStateOf<List<ProteinBiophysics.MutationAnalysis>?>(null) }
    var cart by remember { mutableStateOf(listOf<Triple<Int, Char, Char>>()) }
    var scanSummary by remember { mutableStateOf<String?>(null) }
    var showRcsb by remember { mutableStateOf(false) }
    var showMut by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }            // SPICE AI Co-Pilot（对齐 main 全局侧栏）
    var mutPos by remember { mutableStateOf("1") }
    var mutAa by remember { mutableStateOf("A") }

    val structLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: "" }.getOrNull() ?: ""
            // .spicep 状态包：还原序列/环境/结构（对齐 main 的 loadSpicep）
            val spicep = if ((uri.lastPathSegment ?: "").endsWith(".spicep") ||
                (text.trimStart().startsWith("{") && text.contains("SPICE_PROTEIN"))) Spicep.parse(text) else null
            if (spicep != null) {
                input = spicep.sequence
                envPh = "%.2f".format(spicep.env.ph)
                envTempK = "%.0f".format(spicep.env.tempK)
                envIonic = "%.2f".format(spicep.env.ionicStrengthM)
                fold = null
                md = null
                scan = null
                pockets = null
                structure = if (spicep.referenceCoords.isNotEmpty()) {
                    val atoms = spicep.referenceCoords.chunked(3).mapIndexed { i, c ->
                        Structure.Atom("GLY", i + 1, "A", c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
                    }
                    Structure.Parsed("spicep", atoms, spicep.model)
                } else null
                info = "已载入 SPICE 状态包：${spicep.sequence.length} aa · 模型 ${spicep.model} · pH ${spicep.env.ph} / ${spicep.env.tempK} K" +
                        if (spicep.stepCount > 0) " · MD ${spicep.stepCount} 步（${spicep.timePs} ps）" else ""
            } else {
                val p = Structure.parse(text, uri.lastPathSegment ?: "structure")
                structure = p
                info = if (p.atoms.isEmpty()) p.note ?: "未解析到 Cα 原子"
                else "已载入 ${p.source}：${p.atoms.size} 个 Cα，链 ${p.atoms.map { it.chain }.distinct().size}，残基 ${p.atoms.first().resSeq}–${p.atoms.last().resSeq}"
            }
        }
    }

    // 突变库 CSV 导入
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: "" }.getOrNull() ?: ""
            val candidates = ProteinBiophysics.parseMutantsCsv(text, seq)
            batch = if (candidates.isEmpty()) null
            else ProteinBiophysics.evaluateMutantsBatch(candidates, seq, env.ph, env.tempK)
            info = if (candidates.isEmpty()) "未解析到突变条目（格式：MutantID, V15A + I32L）"
            else "批量评估完成：${candidates.size} 个突变体"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                val body = ">protein\n" + seq.chunked(60).joinToString("\n") + "\n"
                out.write(body.toByteArray())
            }
        }
    }

    // SPICE 状态包 (.spicep) 导出（对齐 main 的 exportSpicep）
    val spicepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = Spicep.build(
                sequence = seq,
                env = Spicep.EnvState(env.ph, env.tempK, 1.0, env.ionicStrengthM),
                foldCoords = fold?.coords ?: structure?.let { s ->
                    FloatArray(s.atoms.size * 3).also { arr ->
                        s.atoms.forEachIndexed { i, a -> arr[i * 3] = a.x; arr[i * 3 + 1] = a.y; arr[i * 3 + 2] = a.z }
                    }
                },
                mdCoords = null,
                stepCount = md?.stepCount ?: 0,
                timePs = md?.timePs ?: 0.0,
                uHist = md?.uHist ?: emptyList()
            )
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            info = "已导出 SPICE 状态包：${seq.length} aa · pH ${env.ph} / ${env.tempK} K"
        }.onFailure { info = "导出失败：${it.message}" }
    }

    // 相图扫描的异步执行与取消（对齐 main 的 phaseBusy / 取消扫描菜单项）
    var phaseBusy by remember { mutableStateOf(false) }
    var phaseCancel by remember { mutableStateOf(false) }
    fun startPhaseScan() {
        if (seq.isEmpty()) { info = "请先输入氨基酸序列"; return }
        phaseCancel = false
        phaseBusy = true
        info = "相图扫描运行中…"
        scope.launch(Dispatchers.Default) {
            val temps = (280..340 step 10).map { it.toDouble() }
            val phs = (3..11 step 1).map { it.toDouble() }
            val result = ProteinBiophysics.scanCancellable(temps, phs, { phaseCancel })
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                phaseBusy = false
                if (result == null) {
                    info = "相图扫描已取消"
                } else {
                    scan = result
                    info = "相图扫描完成：${result.size} 点（${temps.size} 温度 × ${phs.size} pH），稳定 ${result.count { it.stable }} 点"
                }
            }
        }
    }

    // 应用突变购物车到预测结构（对齐 main 的 applyMutCart）
    fun applyMutCart() {
        if (cart.isEmpty()) { info = "突变购物车为空"; return }
        val out = ProteinBiophysics.applyMutations(seq, cart)
        fold = ProteinBiophysics.fold(out, env)
        pockets = null
        info = "已应用 ${cart.size} 个突变到预测结构（${cart.joinToString(" ") { (p, f, t) -> "$f$p$t" }}），已重新折叠"
    }

    // 导出 mmCIF：mutant=true 时应用购物车突变（对齐 main 的 exportFoldMmcif / exportMutMmcif）
    fun exportMmcif(mutant: Boolean) {
        val outSeq = if (mutant && cart.isNotEmpty()) ProteinBiophysics.applyMutations(seq, cart) else seq
        val cif = Structure.buildMmcif(
            sequence = outSeq.ifEmpty { "MKTAYIAKQRQISFVKSHFSRQ" },
            coords = structure?.let { p ->
                FloatArray(p.atoms.size * 3).also { arr ->
                    p.atoms.forEachIndexed { i, a ->
                        arr[i * 3] = a.x; arr[i * 3 + 1] = a.y; arr[i * 3 + 2] = a.z
                    }
                }
            } ?: fold?.coords,
            modelName = if (mutant) "protein_mutant" else "protein_native",
            note = when {
                mutant && cart.isNotEmpty() -> "突变体：${cart.joinToString(" ") { (p, f, t) -> "$f$p$t" }}"
                structure != null -> "来自载入结构"
                fold != null -> "来自折叠预测（二级结构感知骨架）"
                else -> "Cα 直链占位（未载入结构）"
            }
        )
        val f = java.io.File(ctx.filesDir, "exports/protein_${System.currentTimeMillis()}.cif")
        f.parentFile?.mkdirs()
        f.writeText(cif)
        info = "已导出 mmCIF：${f.absolutePath}" + if (mutant && cart.isNotEmpty()) "（含 ${cart.size} 个突变）" else ""
    }

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 菜单栏：文件 / 建模与仿真 / 突变与设计 / 窗口 ──
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "file") null else "file" }) { Text("文件", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "file", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("打开序列与结构文件…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; structLauncher.launch("*/*") }
                    )
                    DropdownMenuItem(
                        text = { Text("从 RCSB PDB 数据库拉取结构…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; showRcsb = true }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text("导出当前预测结构 (.mmcif)", fontSize = 13.sp) },
                        enabled = fold != null || structure != null,
                        onClick = { menuOpen = null; exportMmcif(mutant = false) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出突变体结构 (.mmcif)", fontSize = 13.sp) },
                        enabled = (fold != null || structure != null) && cart.isNotEmpty(),
                        onClick = { menuOpen = null; exportMmcif(mutant = true) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出 SPICE 状态包 (.spicep)", fontSize = 13.sp) },
                        enabled = fold != null || structure != null,
                        onClick = { menuOpen = null; spicepLauncher.launch("spice_protein_${System.currentTimeMillis()}.spicep") }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "model") null else "model" }) { Text("建模与仿真", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "model", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("运行全原子折叠预测", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            if (seq.isEmpty()) { info = "请先输入氨基酸序列"; return@DropdownMenuItem }
                            fold = ProteinBiophysics.fold(seq, env)
                            pockets = null  // 口袋基于最新折叠结果
                            info = "折叠完成：${fold!!.length} 残基，置信度 %.0f%%（pH ${env.ph} / ${env.tempK} K）".format(fold!!.conf * 100)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("运行全原子热动力学 MD 仿真", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            if (seq.isEmpty()) { info = "请先输入氨基酸序列"; return@DropdownMenuItem }
                            md = ProteinBiophysics.mdStep(seq, 200, bias = env.ph in 6.0..8.0, env = env)
                            info = "MD 完成：${md!!.stepCount} 步 · %.2f ps · Rg %.2f Å".format(md!!.timePs, md!!.metrics.rg)
                        }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text(if (pockets == null) "开启活性结合口袋监测" else "关闭活性结合口袋监测", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            if (pockets != null) {
                                pockets = null
                                info = "已关闭活性结合口袋监测"
                            } else {
                                if (seq.isEmpty()) { info = "请先输入氨基酸序列"; return@DropdownMenuItem }
                                val f = fold ?: ProteinBiophysics.fold(seq, env).also { fold = it }
                                pockets = ProteinBiophysics.detectPockets(f, seq)
                                info = "口袋监测开启：${pockets!!.size} 个候选口袋"
                            }
                        }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "mut") null else "mut" }) { Text("突变与设计", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "mut", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("运行点突变稳定性评估", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            if (seq.isEmpty()) { info = "请先输入氨基酸序列"; return@DropdownMenuItem }
                            val aas = "ACDEFGHIKLMNPQRSTVWY"
                            data class Hit(val pos: Int, val from: Char, val to: Char, val ddg: Double)
                            val best = mutableListOf<Hit>(); val worst = mutableListOf<Hit>()
                            for ((i, from) in seq.withIndex()) {
                                var bestHere: Hit? = null; var worstHere: Hit? = null
                                for (to in aas) {
                                    if (to == from) continue
                                    val d = ProteinBiophysics.mutationalStabilityDdg(from, i + 1, to).ddG
                                    val h = Hit(i + 1, from, to, d)
                                    if (bestHere == null || d < bestHere.ddg) bestHere = h
                                    if (worstHere == null || d > worstHere.ddg) worstHere = h
                                }
                                bestHere?.let { best += it }; worstHere?.let { worst += it }
                            }
                            val topStab = best.sortedBy { it.ddg }.take(8)
                            val topDestab = worst.sortedByDescending { it.ddg }.take(8)
                            scanSummary = buildString {
                                appendLine("每位点最优稳定突变（Top 8）：")
                                topStab.forEach { append("  ${it.from}${it.pos}${it.to} ΔΔG %+.2f   ".format(it.ddg)) }
                                appendLine()
                                appendLine("每位点最去稳定突变（Top 8，避免）：")
                                topDestab.forEach { append("  ${it.from}${it.pos}${it.to} ΔΔG %+.2f   ".format(it.ddg)) }
                            }
                            info = "点突变稳定性评估完成（${seq.length} 位点 × 19 替换）"
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("应用突变购物车到预测结构", fontSize = 13.sp) },
                        enabled = cart.isNotEmpty(),
                        onClick = { menuOpen = null; applyMutCart() }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text("开始温-pH 稳定性相图扫描", fontSize = 13.sp) },
                        enabled = !phaseBusy,
                        onClick = { menuOpen = null; startPhaseScan() }
                    )
                    DropdownMenuItem(
                        text = { Text("取消温-pH 稳定性相图扫描", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                        enabled = phaseBusy,
                        onClick = { menuOpen = null; phaseCancel = true }
                    )
                    DropdownMenuItem(
                        text = { Text("开启批量序列抗崩溃筛选", fontSize = 13.sp) },
                        onClick = { menuOpen = null; csvLauncher.launch("*/*") }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "win") null else "win" }) { Text("窗口与设置", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "win", onDismissRequest = { menuOpen = null }) {
                    // 面板标签与就绪态对齐 main 的 menuPane* 系列（active 高亮）
                    listOf(
                        "结构折叠面板" to (fold != null),
                        "动力学轨迹面板" to (md != null),
                        "环境相图面板" to (scan != null),
                        "突变分析面板" to (cart.isNotEmpty() || batch != null),
                        "批量设计筛选面板" to (batch != null)
                    ).forEach { (name, ready) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (ready) "$name ✓" else "$name（未运行）",
                                    fontSize = 13.sp,
                                    fontWeight = if (ready) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { menuOpen = null; info = "已运行的分析面板在下方纵向排列；当前就绪：$name" + if (!ready) "（请先在建模/突变菜单运行）" else "" }
                        )
                    }
                }
            }
            Text("$seqLen aa", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }

        if (info.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(10.dp)) {
                    Text(info, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(info)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制", fontSize = 11.sp) }
                }
            }
        }

        if (showRcsb) {
            RcsbFetchDialog(
                onDismiss = { showRcsb = false },
                onFetched = { text, id ->
                    showRcsb = false
                    val p = Structure.parse(text, "RCSB:$id")
                    structure = p
                    info = if (p.atoms.isEmpty()) "RCSB $id 解析失败：${p.note ?: "无 Cα 原子"}"
                    else "已从 RCSB 拉取 $id：${p.atoms.size} 个 Cα，残基 ${p.atoms.first().resSeq}–${p.atoms.last().resSeq}"
                },
                onError = { id, e ->
                    showRcsb = false
                    info = "RCSB $id 拉取失败：$e"
                }
            )
        }

        if (showMut) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("单点突变设计", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = mutPos, onValueChange = { mutPos = it }, label = { Text("位点", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = mutAa, onValueChange = { mutAa = it.uppercase() }, label = { Text("目标 AA", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    val pos = mutPos.toIntOrNull() ?: 0
                    val from = seq.getOrNull(pos - 1)
                    val to = mutAa.firstOrNull()
                    if (from != null && pos in 1..seq.length && to != null && to != from) {
                        val ddg = ProteinBiophysics.mutationalStabilityDdg(from, pos, to)
                        val opt = com.spice.nativeapp.core.CloningPlus.codons[to]?.firstOrNull()
                        Text(
                            "$from$pos$to  ·  ΔΔG %+.2f kcal/mol（${ddg.classificationZh}）\n推荐密码子 ${opt ?: "—"}（E. coli 偏好）",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "疏水性变化：${ProteinBiophysics.HYDRO.let { m -> "%+.1f".format((m[to] ?: 0.0) - (m[from] ?: 0.0)) }}",
                            fontSize = 11.sp
                        )
                        TextButton(
                            onClick = {
                                if (cart.none { it.first == pos }) cart = cart + Triple(pos, from, to)
                                showMut = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("加入突变购物车") }
                    } else if (to != null && to == from) {
                        Text("目标氨基酸与原残基相同", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Text("输入有效位点（1..${seq.length}）与单字母氨基酸", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                    TextButton(onClick = { showMut = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("氨基酸序列（1 字母码）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        // native 版工具行（main 蛋白菜单无对应条目，故置于页面内而非菜单）
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { showMut = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("单点突变设计…", fontSize = 11.sp) }
            TextButton(onClick = { exportLauncher.launch("protein.fasta") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("导出 FASTA", fontSize = 11.sp) }
            TextButton(
                onClick = {
                    info = props?.let {
                        "Mw ${"%.1f".format(it.molecularWeight)} Da\npI ${"%.2f".format(it.pI)}\nGRAVY ${"%.3f".format(it.gravy)}\nA280 ${"%.3f".format(it.a280)}"
                    } ?: "无序列"
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("性质报告", fontSize = 11.sp) }
        }

        // 环境参数（fold/MD/相图共用）
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("环境参数（Env）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = envPh, onValueChange = { envPh = it }, label = { Text("pH", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = envTempK, onValueChange = { envTempK = it }, label = { Text("温度 K", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = envIonic, onValueChange = { envIonic = it }, label = { Text("离子强度 M", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                Text("偏离 (pH 7, 310 K) 越远，折叠置信度越低；温度 >42 °C 时批量评分衰减。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (props != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("理化性质", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    PropRow("残基数", "${props.length} aa")
                    PropRow("分子量", ProteinCore.formatMass(props.molecularWeight))
                    PropRow("等电点 pI", "%.2f".format(props.pI))
                    PropRow("pH 7 净电荷", "%.2f".format(props.chargedAtPh7))
                    PropRow("GRAVY 疏水指数", "%.3f".format(props.gravy))
                    PropRow("A280 消光系数", "%.0f M⁻¹cm⁻¹".format(props.a280))
                }
            }

            // 高级蛋白分析（对齐 main 蛋白页：溶解性/二级结构/二硫键/抗原表位/结构域）
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("高级蛋白分析", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val sol = remember(seq) { ProteinBiophysics.solubilityAndAggregation(seq) }
                    val ss = remember(seq) { ProteinBiophysics.chouFasman(seq) }
                    val disulfide = remember(seq) { ProteinBiophysics.disulfideBonds(seq) }
                    val anti = remember(seq) { ProteinBiophysics.antigenicity(seq) }
                    val domains = remember(seq) { ProteinBiophysics.proteinDomains(seq) }
                    PropRow("可溶性概率（Wilkinson-Harrison）", "${sol.solubilityScore}%")
                    PropRow(
                        "二级结构组成",
                        "α %d%% · β %d%% · 卷曲 %d%%".format(
                            ss.count { it.second == 'H' } * 100 / seq.length.coerceAtLeast(1),
                            ss.count { it.second == 'E' } * 100 / seq.length.coerceAtLeast(1),
                            ss.count { it.second == 'C' } * 100 / seq.length.coerceAtLeast(1)
                        )
                    )
                    if (sol.aggregationHotspots.isNotEmpty()) {
                        PropRow("聚集热点（TANGO 风格）", "${sol.aggregationHotspots.size} 处", warn = true)
                        sol.aggregationHotspots.take(5).forEach {
                            Text(
                                "  ${it.start + 1}–${it.end + 1} ${it.sequence}（评分 ${it.score}）",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    if (disulfide.isNotEmpty()) {
                        PropRow("预测二硫键", "${disulfide.size} 对")
                        disulfide.take(5).forEach {
                            Text(
                                "  C${it.cys1}–C${it.cys2}（${it.pairingProbability}% · ${if (it.redoxSensitivity == "stable") "稳定" else "氧化敏感"}）",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    PropRow("平均抗原性（Kolaskar）", "%.3f".format(anti.averageAntigenicity))
                    if (anti.epitopes.isNotEmpty()) {
                        PropRow("B 细胞线性表位", "${anti.epitopes.size} 个")
                        anti.epitopes.take(5).forEach {
                            Text("  ${it.start}–${it.end} ${it.sequence}（%.3f）".format(it.averageScore), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (domains.isNotEmpty()) {
                        PropRow("结构域模式", "${domains.size} 处")
                        domains.take(5).forEach {
                            Text("  ${it.start + 1}–${it.end + 1} ${it.name}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        if (scanSummary != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(10.dp)) {
                    Text(scanSummary!!, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(scanSummary!!)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制", fontSize = 11.sp) }
                }
            }
        }

        // ── 六面板（纵向排列，对应原版右侧 Tab） ──
        fold?.let { FoldPanel(it, seq) }
        md?.let { MdPanel(it) }
        pockets?.let { PocketPanel(it) }
        scan?.let { PhasePanel(it) }
        if (cart.isNotEmpty()) {
            MutationCartPanel(
                cart = cart,
                sequence = seq,
                onRemove = { idx -> cart = cart.filterIndexed { i, _ -> i != idx } },
                onApply = { mutated ->
                    input = mutated
                    info = "已应用 ${cart.size} 个突变到序列（原序列可在输入框回退）"
                },
                onClear = { cart = emptyList() }
            )
        }
        batch?.let { BatchPanel(it) }

        OutlinedButton(
            onClick = { input = "" },
            modifier = Modifier.fillMaxWidth()
        ) { Text("清空") }

        Text(
            "说明：折叠/MD/相图为确定性生物物理启发式（对齐 WebView 版无权重回退路径）；接入 spice_infer ONNX 权重后可切换为模型推理。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
        // SPICE AI Co-Pilot 浮动入口（对齐 main 布局级 ai-toggle-btn）
        FloatingActionButton(
            onClick = { showAi = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) { Text("AI", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        if (showAi) {
            AiCopilotSheet(
                workspace = AiWorkspace.PROTEIN,
                sequence = input,
                sequenceLabel = "蛋白序列",
                onImportMutations = { muts ->
                    cart = cart + muts
                    info = "AI 已导入 ${muts.size} 个突变到购物车（共 ${cart.size} 个）"
                },
                onDismiss = { showAi = false }
            )
        }
    }
}

/** RCSB PDB 结构拉取对话框（https://files.rcsb.org/download/{id}.cif） */
@Composable
private fun RcsbFetchDialog(
    onDismiss: () -> Unit,
    onFetched: (String, String) -> Unit,
    onError: (String, String) -> Unit
) {
    var pdbId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 RCSB PDB 拉取结构", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入 4 位 PDB ID（如 2LYZ / 1UBQ），拉取 mmCIF 后按 Cα 轨迹载入。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pdbId,
                    onValueChange = { pdbId = it.trim().uppercase() },
                    label = { Text("PDB ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pdbId.length == 4,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val conn = (URL("https://files.rcsb.org/download/${pdbId}.cif").openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 15000
                                    readTimeout = 20000
                                    setRequestProperty("User-Agent", "SPICE-GUI-Native/1.0")
                                }
                                try {
                                    if (conn.responseCode !in 200..299) return@runCatching Result.failure<String>(IllegalStateException("HTTP ${conn.responseCode}"))
                                    val text = conn.inputStream.use { it.readBytes().decodeToString() }
                                    if (text.isBlank()) Result.failure(IllegalStateException("空响应"))
                                    else Result.success(text)
                                } finally {
                                    conn.disconnect()
                                }
                            }.getOrElse { Result.failure(it) }
                        }
                        result.onSuccess { onFetched(it, pdbId) }
                            .onFailure { onError(pdbId, it.message ?: it.javaClass.simpleName) }
                    }
                }
            ) { Text(if (busy) "拉取中…" else "拉取") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PropRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}
