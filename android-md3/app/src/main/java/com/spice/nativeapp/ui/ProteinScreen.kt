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
import androidx.compose.material3.Button
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
import com.spice.nativeapp.core.SpiceOnnx
import com.spice.nativeapp.core.Spicep
import com.spice.nativeapp.core.SpdLibrary
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
fun ProteinScreen(
    modifier: Modifier = Modifier,
    aiOpen: Boolean = false,
    onAiOpenChange: (Boolean) -> Unit = {}
) {
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
    var mutPos by remember { mutableStateOf("1") }
    var mutAa by remember { mutableStateOf("A") }

    // SPD 发布面板状态（对齐 main protein/+page.svelte spdBusy/spdMessage/spdFoldId/spdProteinName）
    var spdBusy by remember { mutableStateOf(false) }
    var spdMessage by remember { mutableStateOf<String?>(null) }
    var spdFoldId by remember { mutableStateOf<String?>(null) }
    var spdProteinName by remember { mutableStateOf(SpdLibrary.lastProteinName(ctx)) }
    var spdFoldDetail by remember { mutableStateOf<org.json.JSONObject?>(null) }

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

    // SPD 发布面板流程（对齐 main querySpdExact / publishToSpd；文案取 main zh.json 原文）
    fun querySpdExact() {
        if (seq.isEmpty()) { spdMessage = com.spice.nativeapp.core.I18n.t("请先输入氨基酸序列"); return }
        spdBusy = true; spdMessage = null; spdFoldId = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // 图 DSL 批量查询（POST /query）：本机已知 SPD 序列 id 为根，sequence→folds 边一次走完；
                    // 匿名 20/深度2、有令牌 40/深度3 的预算内，根数取 5（带令牌）/4（匿名），folds limit 3
                    val sha = SpdLibrary.sequenceSha256(seq)
                    val seqKey = sha.removePrefix("sha256:").take(20)
                    val currentId = SpdLibrary.cachedSequenceId(ctx, seqKey)
                    val entries = SpdLibrary.cachedSequenceEntries(ctx)
                    val hasToken = SpdLibrary.token(ctx).isNotBlank()
                    val ordered = if (currentId.isNotBlank())
                        listOf(currentId) + entries.map { it.second }.filter { it != currentId }
                    else entries.map { it.second }
                    val roots = ordered.take(if (hasToken) 5 else 4)
                    if (roots.isEmpty()) return@runCatching "spdQueryNoCache"
                    val foldNode = org.json.JSONObject().apply {
                        put("limit", 3)
                        put("select", org.json.JSONArray().put("id").put("sequenceId").put("environmentId").put("foldIdentityHash").put("createdAt"))
                        if (hasToken) put("expand", org.json.JSONObject().put("environment",
                            org.json.JSONObject().put("select", org.json.JSONArray().put("ph").put("temperatureK").put("pressureBar").put("ionicStrengthM"))))
                    }
                    val rootsArr = org.json.JSONArray().apply {
                        roots.forEach { id ->
                            put(org.json.JSONObject().apply {
                                put("type", "sequence"); put("id", id)
                                put("select", org.json.JSONArray().put("id").put("sha256").put("length"))
                                put("expand", org.json.JSONObject().put("folds", foldNode))
                            })
                        }
                    }
                    val q = SpdLibrary.graphQuery(ctx, rootsArr)
                    val data = q.value ?: return@runCatching "ERR:${q.error}"
                    val results = data.optJSONArray("results") ?: org.json.JSONArray()
                    var total = 0
                    var currentFolds: org.json.JSONArray? = null
                    for (i in 0 until results.length()) {
                        val node = results.optJSONObject(i) ?: continue
                        val folds = node.optJSONArray("folds") ?: continue
                        total += folds.length()
                        if (node.optString("id") == currentId) currentFolds = folds
                    }
                    if (currentFolds != null && currentFolds.length() > 0) {
                        var matched: org.json.JSONObject? = null
                        if (hasToken) {
                            for (i in 0 until currentFolds.length()) {
                                val f = currentFolds.optJSONObject(i) ?: continue
                                val ev = f.optJSONObject("environment") ?: continue
                                if (ev.optDouble("ph") == env.ph && ev.optDouble("temperatureK") == env.tempK &&
                                    ev.optDouble("pressureBar") == 1.0 && ev.optDouble("ionicStrengthM") == env.ionicStrengthM
                                ) { matched = f; break }
                            }
                        }
                        (matched ?: currentFolds.optJSONObject(0))?.optString("id")?.let { spdFoldId = it }
                        "HIT:${currentFolds.length()}"
                    } else if (total > 0) "NO_CURRENT:$total"
                    else "EMPTY"
                }.getOrElse { "ERR:${it.message ?: it.toString()}" }
            }
            spdBusy = false
            spdMessage = when {
                r.startsWith("ERR:") -> r.removePrefix("ERR:")
                r == "spdQueryNoCache" -> com.spice.nativeapp.core.I18n.t("本机未记录该序列的 SPD ID；点发布即可（服务端自动去重）")
                r.startsWith("HIT:") -> "查询到 ${r.removePrefix("HIT:")} 条 fold"
                r.startsWith("NO_CURRENT:") -> "当前序列暂无 fold；批量共找到 ${r.removePrefix("NO_CURRENT:")} 条"
                r == "EMPTY" -> com.spice.nativeapp.core.I18n.t("未找到 SPD 精确匹配")
                else -> r
            }
        }
    }

    fun publishToSpd() {
        if (seq.isEmpty() || fold == null) { spdMessage = com.spice.nativeapp.core.I18n.t("请先运行折叠预测，再发布到 SPD"); return }
        if (SpdLibrary.token(ctx).isBlank()) { spdMessage = com.spice.nativeapp.core.I18n.t("请先在 设置 → 公开集成 配置 API Token"); return }
        spdBusy = true; spdMessage = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val sha = SpdLibrary.sequenceSha256(seq)
                    val seqKey = sha.removePrefix("sha256:").take(20)
                    val steps = mutableListOf<String>()

                    // ① 定位父级蛋白记录（protein 行无服务端去重 → 本地缓存；可粘贴既有 spd:protein: id）
                    steps += com.spice.nativeapp.core.I18n.t("定位父级蛋白记录")
                    val name = spdProteinName.trim()
                    val pastedId = if (name.startsWith("spd:protein:")) name else ""
                    var proteinId = SpdLibrary.cachedProteinId(ctx, seqKey)
                    if (pastedId.isNotBlank()) proteinId = pastedId
                    else if (proteinId.isBlank()) {
                        val pr = SpdLibrary.createProtein(ctx, name.ifEmpty { "unnamed-${seqKey.take(8)}" })
                        val id = SpdLibrary.receiptEntityId(pr.value, "protein")
                        if (pr.error != null || id.isBlank()) return@runCatching "ERR:protein 步骤失败：${pr.error ?: "回执缺少 protein.id"}"
                        proteinId = id
                        SpdLibrary.rememberProteinId(ctx, seqKey, proteinId)
                    }
                    if (name.isNotBlank() && pastedId.isBlank()) SpdLibrary.rememberProteinName(ctx, name)

                    // ② 登记序列（服务端按 SHA-256 去重；本地缓存公开 id）
                    steps += com.spice.nativeapp.core.I18n.t("登记序列（父级：蛋白/构建体/变体）")
                    var sequenceId = SpdLibrary.cachedSequenceId(ctx, seqKey)
                    if (sequenceId.isBlank()) {
                        val sr = SpdLibrary.createSequence(ctx, proteinId, seq, sha)
                        val id = SpdLibrary.receiptEntityId(sr.value, "sequence")
                        if (sr.error != null || id.isBlank()) return@runCatching "ERR:sequence 步骤失败：${sr.error ?: "回执缺少 sequence.id"}"
                        sequenceId = id
                        SpdLibrary.rememberSequenceId(ctx, seqKey, sequenceId)
                    }

                    // ③④ fold 内嵌身份的幂等 upsert（环境 + 模型；create_fold 对陌生身份会 400）
                    steps += com.spice.nativeapp.core.I18n.t("核对 fold 内嵌的 MD 环境身份（去重）")
                    val environment = SpdLibrary.EnvironmentInput(env.ph, env.tempK, 1.0, env.ionicStrengthM)
                    val envR = SpdLibrary.createEnvironment(ctx, environment)
                    if (envR.error != null) return@runCatching com.spice.nativeapp.core.I18n.tf("ERR:environment 步骤失败：{v1}", envR.error)

                    steps += com.spice.nativeapp.core.I18n.t("核对 fold 内嵌的模型身份（去重）")
                    // 模型校验和：ONNX 权重文件流式 SHA-256（对齐 main modelSha256 桌面门槛的语义；
                    // 无权重时发布仍完整走通（零哈希占位），README 差异节注明）
                    val configuredPath = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
                        .getString("infer_model_path", null)
                    val modelFile = configuredPath?.let { java.io.File(it) }
                        ?: java.io.File(java.io.File(ctx.filesDir, "models"), "spice_infer_fixed.onnx")
                    val checkpointSha = if (modelFile.exists()) {
                        val h = SpdLibrary.fileSha256(modelFile.absolutePath)
                        if (h.value != null) h.value else null
                    } else null
                    val model = SpdLibrary.ModelInput(
                        modelName = modelFile.name.ifEmpty { "SPICE" },
                        modelVersion = "pretrain",
                        checkpointSha256 = checkpointSha ?: "sha256:" + "0".repeat(64),
                        inputProtocolVersion = SpdLibrary.SPD_INPUT_PROTOCOL_VERSION,
                        inferenceRuntime = "ort"
                    )
                    val modelR = SpdLibrary.createModel(ctx, model)
                    if (modelR.error != null) return@runCatching com.spice.nativeapp.core.I18n.tf("ERR:model 步骤失败：{v1}", modelR.error)

                    // ⑤ 提交 fold（只带轻量摘要：服务端请求体上限 2 MiB，坐标/矩阵留本地）
                    steps += com.spice.nativeapp.core.I18n.t("提交 fold（内嵌环境与模型）")
                    val f = fold!!
                    val prediction = org.json.JSONObject().apply {
                        put("length", f.length)
                        put("confidence", org.json.JSONObject().put("headA", f.conf))
                        put("runtime", "android")
                    }
                    val fr = SpdLibrary.createFold(ctx, sequenceId, environment, model, prediction)
                    val foldId = SpdLibrary.receiptEntityId(fr.value, "fold")
                    if (fr.error != null || foldId.isBlank()) return@runCatching "ERR:fold 步骤失败：${fr.error ?: "回执缺少 fold.id"}"
                    val dedup = (fr.value as? org.json.JSONObject)?.optBoolean("deduplicated", false) == true
                    (if (dedup) "DEDUP:" else "NEW:") + foldId
                }.getOrElse { "ERR:${it.message ?: it.toString()}" }
            }
            spdBusy = false
            spdMessage = when {
                r.startsWith("ERR:") -> r.removePrefix("ERR:")
                r.startsWith("DEDUP:") -> {
                    spdFoldId = r.removePrefix("DEDUP:")
                    com.spice.nativeapp.core.I18n.tf("SPD 已存在同一 fold（未新建）：{v1}", spdFoldId)
                }
                r.startsWith("NEW:") -> {
                    spdFoldId = r.removePrefix("NEW:")
                    com.spice.nativeapp.core.I18n.tf("已发布到 SPD：{v1}", spdFoldId)
                }
                else -> r
            }
        }
    }

    fun openFoldDetail() {
        val id = spdFoldId ?: return
        spdBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { SpdLibrary.getFold(ctx, id) }
            spdBusy = false
            if (r.value != null) spdFoldDetail = r.value else spdMessage = com.spice.nativeapp.core.I18n.tf("详情获取失败：{v1}", r.error)
        }
    }

    // 相图扫描的异步执行与取消（对齐 main 的 phaseBusy / 取消扫描菜单项）
    var phaseBusy by remember { mutableStateOf(false) }
    var phaseCancel by remember { mutableStateOf(false) }
    fun startPhaseScan() {
        if (seq.isEmpty()) { info = com.spice.nativeapp.core.I18n.t("请先输入氨基酸序列"); return }
        phaseCancel = false
        phaseBusy = true
        info = com.spice.nativeapp.core.I18n.t("相图扫描运行中…")
        scope.launch(Dispatchers.Default) {
            val temps = (280..340 step 10).map { it.toDouble() }
            val phs = (3..11 step 1).map { it.toDouble() }
            val result = ProteinBiophysics.scanCancellable(temps, phs, { phaseCancel })
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                phaseBusy = false
                if (result == null) {
                    info = com.spice.nativeapp.core.I18n.t("相图扫描已取消")
                } else {
                    scan = result
                    info = com.spice.nativeapp.core.I18n.tf("相图扫描完成：{v1} 点（{v2} 温度 × {v3} pH），稳定 {v4} 点", result.size, temps.size, phs.size, result.count { it.stable })
                }
            }
        }
    }

    // 应用突变购物车到预测结构（对齐 main 的 applyMutCart）
    fun applyMutCart() {
        if (cart.isEmpty()) { info = com.spice.nativeapp.core.I18n.t("突变购物车为空"); return }
        val out = ProteinBiophysics.applyMutations(seq, cart)
        fold = ProteinBiophysics.fold(out, env)
        pockets = null
        info = com.spice.nativeapp.core.I18n.tf("已应用 {v1} 个突变到预测结构（{v2}），已重新折叠", cart.size, cart.joinToString(" ") { (p, f, t) -> "$f$p$t" })
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
                mutant && cart.isNotEmpty() -> com.spice.nativeapp.core.I18n.tf("突变体：{v1}", cart.joinToString(" ") { (p, f, t) -> "$f$p$t" })
                structure != null -> com.spice.nativeapp.core.I18n.t("来自载入结构")
                fold != null -> com.spice.nativeapp.core.I18n.t("来自折叠预测（二级结构感知骨架）")
                else -> com.spice.nativeapp.core.I18n.t("Cα 直链占位（未载入结构）")
            }
        )
        val f = java.io.File(ctx.filesDir, "exports/protein_${System.currentTimeMillis()}.cif")
        f.parentFile?.mkdirs()
        f.writeText(cif)
        info = com.spice.nativeapp.core.I18n.tf("已导出 mmCIF：{v1}", f.absolutePath) + if (mutant && cart.isNotEmpty()) com.spice.nativeapp.core.I18n.tf("（含 {v1} 个突变）", cart.size) else ""
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
                            // 对齐 main doFold：ONNX 模型就位时走真推理，缺失/失败回退确定性启发式
                            val modelPath = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
                                .getString("infer_model_path", null)
                            val r = runCatching { SpiceOnnx.fold(ctx, seq, env, modelPath) }
                            if (r.isSuccess) {
                                val onnx = r.getOrThrow()
                                // ONNX 输出映射到 FoldOutput（接触图阈值 0.5，与 main 掩膜语义一致）
                                val contacts = ByteArray(onnx.length * onnx.length) { i -> if (onnx.pContact[i] > 0.5f) 1 else 0 }
                                fold = ProteinBiophysics.FoldOutput(
                                    length = onnx.length,
                                    coords = onnx.coords,
                                    secondary = ProteinBiophysics.chouFasman(seq).mapIndexed { i, pr -> i to pr.second },
                                    distExp = onnx.distExp,
                                    contactMap = contacts,
                                    conf = onnx.conf[0].toDouble().coerceIn(0.0, 1.0),
                                    runMs = onnx.runMs
                                )
                                info = "ONNX 折叠完成：${onnx.length} 残基 · %.1f ms（pH ${env.ph} / ${env.tempK} K）\n模型：${onnx.modelPath}".format(onnx.runMs)
                            } else {
                                fold = ProteinBiophysics.fold(seq, env)
                                info = "折叠完成（回退启发式，无 ONNX 权重）：${fold!!.length} 残基，置信度 %.0f%%\n提示：${r.exceptionOrNull()?.message ?: "设置 → ONNX 神经网络核 → 指定模型路径"}".format(fold!!.conf * 100)
                            }
                            pockets = null  // 口袋基于最新折叠结果
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

        // SPD fold 详情（对齐 main spdInspector.open('fold', id) 的面板，此处以对话框呈现关键摘要）
        if (spdFoldDetail != null) {
            val fd = spdFoldDetail!!
            AlertDialog(
                onDismissRequest = { spdFoldDetail = null },
                title = { Text("SPD 记录详情") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "ID" to fd.optString("id"),
                            "序列" to fd.optString("sequenceId"),
                            "环境" to fd.optString("environmentId"),
                            "置信头" to (fd.optJSONObject("prediction")?.optJSONObject("confidence")?.let {
                                "headA " + "%.3f".format(it.optDouble("headA"))
                            } ?: "—"),
                            "验证级别" to fd.optString("verificationLevel", "—"),
                            "协议版本" to fd.optString("inferenceProtocolVersion", "—")
                        ).forEach { (k, v) -> Text("$k：$v", fontSize = 12.sp) }
                    }
                },
                confirmButton = { TextButton(onClick = { spdFoldDetail = null }) { Text("关闭") } }
            )
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

        // SPD 发布面板（对齐 main spd-panel：名称/查重/发布/fold 收据/详情）
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SPD", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = spdProteinName,
                    onValueChange = { spdProteinName = it },
                    label = { Text("蛋白记录名称:", fontSize = 11.sp) },
                    placeholder = { Text("或粘贴已有 spd:protein:… 记录 ID", fontSize = 10.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { querySpdExact() },
                        enabled = !spdBusy,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    ) { Text(if (spdBusy) "检查中…" else "批量查询 SPD fold", fontSize = 11.sp) }
                    Button(
                        onClick = { publishToSpd() },
                        enabled = !spdBusy && fold != null,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    ) { Text("发布折叠结果到 SPD", fontSize = 11.sp) }
                }
                if (spdFoldId != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "fold ID: $spdFoldId",
                            fontSize = 10.sp, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, maxLines = 1
                        )
                        OutlinedButton(onClick = { openFoldDetail() }, modifier = Modifier.heightIn(min = 40.dp)) { Text("详情", fontSize = 10.sp) }
                    }
                }
                spdMessage?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
        // AI 浮动入口已移除：标题栏已有 AI Co-Pilot 按钮（对齐 main 顶栏唯一入口），避免重复
        if (aiOpen) {
            AiCopilotSheet(
                workspace = AiWorkspace.PROTEIN,
                sequence = input,
                sequenceLabel = "蛋白序列",
                envPh = env.ph,
                envTempK = env.tempK,
                envIonic = env.ionicStrengthM,
                onImportMutations = { muts ->
                    cart = cart + muts
                    info = "AI 已导入 ${muts.size} 个突变到购物车（共 ${cart.size} 个）"
                },
                onApplyProtein = { seq -> input = seq },
                onDismiss = { onAiOpenChange(false) }
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
        title = { Text(com.spice.nativeapp.core.I18n.t("从 RCSB PDB 拉取结构"), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("输入 4 位 PDB ID（如 2LYZ / 1UBQ），拉取 mmCIF 后按 Cα 轨迹载入。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    if (text.isBlank()) Result.failure(IllegalStateException(com.spice.nativeapp.core.I18n.t("空响应")))
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
            ) { Text(if (busy) com.spice.nativeapp.core.I18n.t("拉取中…") else com.spice.nativeapp.core.I18n.t("拉取")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(com.spice.nativeapp.core.I18n.t("取消")) } }
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
