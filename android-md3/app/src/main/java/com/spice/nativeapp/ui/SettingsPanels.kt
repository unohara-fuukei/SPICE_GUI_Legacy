package com.spice.nativeapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.ModelStore
import com.spice.nativeapp.core.SpiceOnnx
import com.spice.nativeapp.core.SpdLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val PREF = "spice_settings"
private const val KEY_BASE = "spd_base_url"
private const val KEY_TOKEN = "spd_api_token"
private const val KEY_PROXY = "spd_proxy_url"
private const val KEY_ORCID = "spd_orcid"
private const val KEY_LAB = "spd_lab"
private const val KEY_NAME = "spd_display_name"
private const val KEY_INFER_PATH = "infer_model_path"
private const val KEY_INFER_URL = "infer_model_url"
private const val DEFAULT_INFER_FILE = "spice_infer_fixed.onnx"
private const val DEFAULT_SPD_BASE = "https://d-api.spicebio.top/api/v1"

/** 与原版 settings/+page.svelte 对齐：SPD 身份（ORCID）、实例地址与 Universal API Token；
 *  本机网络直连 d-api 不可达，SPD 请求默认经 HTTP 代理（可自定义/清空直连）。 */
@Composable
fun SpdSettingsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE) }
    var base by remember { mutableStateOf(prefs.getString(KEY_BASE, DEFAULT_SPD_BASE) ?: DEFAULT_SPD_BASE) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
    var proxy by remember { mutableStateOf(prefs.getString(KEY_PROXY, SpdLibrary.DEFAULT_PROXY) ?: SpdLibrary.DEFAULT_PROXY) }
    var orcid by remember { mutableStateOf(prefs.getString(KEY_ORCID, "") ?: "") }
    var lab by remember { mutableStateOf(prefs.getString(KEY_LAB, "") ?: "") }
    var displayName by remember { mutableStateOf(prefs.getString(KEY_NAME, "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        prefs.edit()
            .putString(KEY_BASE, base.trim())
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_PROXY, proxy.trim())
            .putString(KEY_ORCID, orcid.trim())
            .putString(KEY_LAB, lab.trim())
            .putString(KEY_NAME, displayName.trim())
            .apply()
    }

    Text("SPD 身份与凭据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "SPD 是序列设计与协作平台。身份用 ORCID iD 标识，接口用 Universal API Token（Bearer spd_…）。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(value = base, onValueChange = { base = it; save() }, label = { Text("实例地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; save() },
                label = { Text("SPD API Token:") },
                placeholder = { Text("粘贴 spd_ 开头的令牌（仅存本机）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "在 SPD 网站用 ORCID 登录 → 个人中心 API Tokens → 生成 Universal API Token（只显示一次），粘贴到上方。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = proxy,
                onValueChange = { proxy = it; save() },
                label = { Text("网络代理（HTTP）") },
                placeholder = { Text("http://user@host:port — 留空直连") },
                supportingText = {
                    Text(
                        if (proxy.isBlank()) "已清空：所有 SPD 请求直连"
                        else if (SpdLibrary.parseProxy(proxy).proxy != null) "已启用：SPD 请求经此代理转发"
                        else "代理地址无法解析（${SpdLibrary.parseProxy(proxy).error ?: ""}），请求将直连",
                        fontSize = 10.sp
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(value = orcid, onValueChange = { orcid = it; save() }, label = { Text("ORCID iD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; save() },
                    label = { Text("署名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lab,
                    onValueChange = { lab = it; save() },
                    label = { Text("实验室/机构") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        save()
                        busy = true; result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SpdLibrary.health(ctx) }
                            result = r.value?.let { "服务正常：${it.service} · ${it.status}${if (it.apiVersion.isNotEmpty()) " · v${it.apiVersion}" else ""}" }
                                ?: "无法连接：${r.error}"
                            busy = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (busy) "检查中…" else "检查服务") }
                Button(
                    onClick = {
                        if (token.isBlank()) { result = "请先粘贴 API Token"; return@Button }
                        save()
                        busy = true; result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SpdLibrary.me(ctx) }
                            result = r.value?.let { "SPD 身份：${it.displayName}（角色：${it.roles}）" }
                                ?: "Token 无效或网络不通：${r.error}"
                            busy = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (busy) "请求中…" else "验证 Token") }
            }
            result?.let {
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Text("Token 与 ORCID 仅存本机 SharedPreferences，不上传。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 与原版 ONNX 策略一致：权重不打包 APK，按需下载到应用私有目录并校验 SHA-256 */
@Composable
fun ModelSettingsSection() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(ModelStore.status(ctx)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Text("AI 模型（ONNX）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "GelGenie U-Net（凝胶条带分割）。策略与原版一致：不打包进 APK，首次使用时从 HuggingFace 按需下载，SHA-256 校验后缓存。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val sizeMb = "%.1f".format(status.sizeBytes / 1024.0 / 1024.0)
            Text(
                if (status.ok) "已就绪 · $sizeMb MB · 校验${if (status.shaMatch) "通过" else "失败"}"
                else "未下载（${status.error ?: "—"}）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (downloading) return@Button
                        downloading = true
                        progress = "准备下载…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ModelStore.download(ctx, { done, total ->
                                    progress = if (total > 0) "%.1f / %.1f MB".format(done / 1048576.0, total / 1048576.0) else "$done B"
                                }, { stage -> progress = stage })
                            }
                            downloading = false
                            progress = r.fold({ "下载完成：%.1f MB".format(it.length() / 1048576.0) }, { "下载失败：${it.message}" })
                            status = ModelStore.status(ctx)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (downloading) "下载中…" else if (status.ok) "重新下载" else "下载模型") }
                OutlinedButton(
                    onClick = {
                        ModelStore.modelFile(ctx).delete()
                        status = ModelStore.status(ctx)
                        progress = ""
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("删除本地模型") }
            }
            if (progress.isNotEmpty()) Text(progress, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                "推理入口：工具 → 凝胶分析。未下载模型时会提示先完成下载，不会静默失败。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「ONNX 神经网络核」面板（对齐 main settings/+page.svelte 蛋白 Tab）：
 * 模型加载状态 LED + 手动指定本地路径（应用）+ 从 SPICE 云端下载固定权重（URL + 进度 + 回填路径）。
 * 原生版暂未集成 onnxruntime 推理（README 已知缺口），状态仅检测权重文件是否就位。
 */
@Composable
fun InferModelSettingsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE) }
    fun defaultDest(): java.io.File =
        java.io.File(java.io.File(ctx.filesDir, "models"), DEFAULT_INFER_FILE)

    var modelPathInput by remember { mutableStateOf(prefs.getString(KEY_INFER_PATH, "") ?: "") }
    var modelUrl by remember { mutableStateOf(prefs.getString(KEY_INFER_URL, "") ?: "") }
    var dlBusy by remember { mutableStateOf(false) }
    var dlMsg by remember { mutableStateOf<String?>(null) }
    var dlOk by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val scope = rememberCoroutineScope()

    // 模型加载状态（对齐 main modelStatus）：由 SpiceOnnx 解析（显式路径 > filesDir/models > 外部目录）
    data class ModelStatus(val ok: Boolean, val path: String, val sizeBytes: Long)
    fun checkStatus(): ModelStatus {
        val (ok, path) = SpiceOnnx.status(ctx, modelPathInput.takeIf { it.isNotBlank() })
        val f = java.io.File(path)
        return if (ok && f.exists()) ModelStatus(true, f.absolutePath, f.length())
        else ModelStatus(false, path, 0L)
    }
    var status by remember { mutableStateOf(checkStatus()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ONNX 神经网络核", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("模型加载状态:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (status.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Text(if (status.ok) "已就绪" else "离线", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (status.ok) {
                    // 对齐 main zh.json：modelPathLabel=路径: / modelSizeLabel=容量: / modelHeadsLabel=输出头:（trainedHeads=A, distogram）
                    Text(
                        "路径: ${status.path}\n容量: " + "%.1f MB".format(status.sizeBytes / 1048576.0) + "\n输出头: A, distogram",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("手动指定本地模型绝对路径:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = modelPathInput,
                        onValueChange = { modelPathInput = it },
                        placeholder = { Text("…/spice_infer_fixed.onnx") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            prefs.edit().putString(KEY_INFER_PATH, modelPathInput.trim()).apply()
                            SpiceOnnx.unload()  // 换路径后重载会话
                            status = checkStatus()
                            dlOk = status.ok
                            dlMsg = if (status.ok) "模型已加载：${status.path}" else "未找到模型（路径不存在或为空）"
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("应用") }
                }
                Text(
                    "从 SPICE 云端下载固定权重量子",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("模型权重 URL:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = modelUrl,
                        onValueChange = { modelUrl = it; prefs.edit().putString(KEY_INFER_URL, it).apply() },
                        placeholder = { Text("https://…/spice_infer_fixed.onnx") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val url = modelUrl.trim()
                            if (url.isEmpty()) { dlOk = false; dlMsg = "URL 为空"; return@Button }
                            if (dlBusy) return@Button
                            dlBusy = true; dlMsg = null; progress = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val conn = URL(url).openConnection() as HttpURLConnection
                                        conn.connectTimeout = 15_000; conn.readTimeout = 60_000
                                        try {
                                            if (conn.responseCode !in 200..299)
                                                throw IllegalStateException("HTTP ${conn.responseCode}")
                                            val total = conn.contentLengthLong
                                            val dest = defaultDest()
                                            dest.parentFile?.mkdirs()
                                            conn.inputStream.use { ins ->
                                                java.io.FileOutputStream(dest).use { outs ->
                                                    val buf = ByteArray(64 * 1024)
                                                    var done = 0L
                                                    while (true) {
                                                        val n = ins.read(buf)
                                                        if (n < 0) break
                                                        outs.write(buf, 0, n)
                                                        done += n
                                                        progress = done to total
                                                    }
                                                }
                                            }
                                            dest
                                        } finally { conn.disconnect() }
                                    }
                                }
                                dlBusy = false
                                r.fold({
                                    progress = null
                                    dlOk = true
                                    modelPathInput = it.absolutePath
                                    prefs.edit().putString(KEY_INFER_PATH, it.absolutePath).apply()
                                    status = checkStatus()
                                    dlMsg = "下载成功：${it.absolutePath}"
                                }, {
                                    progress = null
                                    dlOk = false
                                    dlMsg = "下载失败：${it.message}"
                                })
                            }
                        },
                        enabled = !dlBusy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (dlBusy) "下载中…" else "下载") }
                }
                progress?.let { (done, total) ->
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "已完成 " + "%.1f MB".format(done / 1048576.0) + " / " + (if (total > 0) "%.1f MB".format(total / 1048576.0) else "—"),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                dlMsg?.let {
                    Text(
                        it, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (dlOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "说明：原生版暂未集成 onnxruntime 推理运行时（已知缺口），此处负责权重就位与状态管理；蛋白页折叠/相图仍走确定性启发式路径。",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 酶库口径，供设置页展示 */
@Composable
fun EnzymeLibraryInfo() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("限制酶库", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "REBASE ${Enzymes.rebaseCount} 条 · ${Enzymes.groupCount} 个识别位点组 · ${Enzymes.nameCount} 个同义酶名",
                fontSize = 12.sp
            )
            Text(
                "识别位点支持 IUPAC 简并碱基（如 ggwcc / cnnnnnnng），切点按 top/bottom snip offset 计算。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
