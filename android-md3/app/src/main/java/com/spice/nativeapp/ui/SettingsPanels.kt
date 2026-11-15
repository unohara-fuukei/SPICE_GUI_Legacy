package com.spice.nativeapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
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
import com.spice.nativeapp.core.Keymap
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

    Text(com.spice.nativeapp.core.I18n.t("SPD 身份与凭据"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                com.spice.nativeapp.core.I18n.t("SPD 是序列设计与协作平台。身份用 ORCID iD 标识，接口用 Universal API Token（Bearer spd_…）。"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(value = base, onValueChange = { base = it; save() }, label = { Text(com.spice.nativeapp.core.I18n.t("实例地址")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; save() },
                label = { Text("SPD API Token:") },
                placeholder = { Text(com.spice.nativeapp.core.I18n.t("粘贴 spd_ 开头的令牌（仅存本机）")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                com.spice.nativeapp.core.I18n.t("在 SPD 网站用 ORCID 登录 → 个人中心 API Tokens → 生成 Universal API Token（只显示一次），粘贴到上方。"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = proxy,
                onValueChange = { proxy = it; save() },
                label = { Text(com.spice.nativeapp.core.I18n.t("网络代理（HTTP）")) },
                placeholder = { Text(com.spice.nativeapp.core.I18n.t("http://user@host:port — 留空直连")) },
                supportingText = {
                    Text(
                        if (proxy.isBlank()) com.spice.nativeapp.core.I18n.t("已清空：所有 SPD 请求直连")
                        else if (SpdLibrary.parseProxy(proxy).proxy != null) com.spice.nativeapp.core.I18n.t("已启用：SPD 请求经此代理转发")
                        else com.spice.nativeapp.core.I18n.tf("代理地址无法解析（{v1}），请求将直连", SpdLibrary.parseProxy(proxy).error ?: ""),
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
                    label = { Text(com.spice.nativeapp.core.I18n.t("署名")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lab,
                    onValueChange = { lab = it; save() },
                    label = { Text(com.spice.nativeapp.core.I18n.t("实验室/机构")) },
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
                            result = r.value?.let { com.spice.nativeapp.core.I18n.tf("服务正常：{v1} · {v2}{v3}", it.service, it.status, if (it.apiVersion.isNotEmpty()) " · v${it.apiVersion}" else "") }
                                ?: com.spice.nativeapp.core.I18n.tf("无法连接：{v1}", r.error)
                            busy = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (busy) com.spice.nativeapp.core.I18n.t("检查中…") else com.spice.nativeapp.core.I18n.t("检查服务")) }
                Button(
                    onClick = {
                        if (token.isBlank()) { result = com.spice.nativeapp.core.I18n.t("请先粘贴 API Token"); return@Button }
                        save()
                        busy = true; result = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SpdLibrary.me(ctx) }
                            result = r.value?.let { com.spice.nativeapp.core.I18n.tf("SPD 身份：{v1}（角色：{v2}）", it.displayName, it.roles) }
                                ?: com.spice.nativeapp.core.I18n.tf("Token 无效或网络不通：{v1}", r.error)
                            busy = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (busy) com.spice.nativeapp.core.I18n.t("请求中…") else com.spice.nativeapp.core.I18n.t("验证 Token")) }
            }
            result?.let {
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Text(com.spice.nativeapp.core.I18n.t("Token 与 ORCID 仅存本机 SharedPreferences，不上传。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Text(com.spice.nativeapp.core.I18n.t("AI 模型（ONNX）"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                com.spice.nativeapp.core.I18n.t("GelGenie U-Net（凝胶条带分割）。策略与原版一致：不打包进 APK，首次使用时从 HuggingFace 按需下载，SHA-256 校验后缓存。"),
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
                        progress = com.spice.nativeapp.core.I18n.t("准备下载…")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ModelStore.download(ctx, { done, total ->
                                    progress = if (total > 0) "%.1f / %.1f MB".format(done / 1048576.0, total / 1048576.0) else "$done B"
                                }, { stage -> progress = stage })
                            }
                            downloading = false
                            progress = r.fold({ com.spice.nativeapp.core.I18n.t("下载完成：%.1f MB").format(it.length() / 1048576.0) }, { com.spice.nativeapp.core.I18n.tf("下载失败：{v1}", it.message) })
                            status = ModelStore.status(ctx)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(if (downloading) com.spice.nativeapp.core.I18n.t("下载中…") else if (status.ok) com.spice.nativeapp.core.I18n.t("重新下载") else com.spice.nativeapp.core.I18n.t("下载模型")) }
                OutlinedButton(
                    onClick = {
                        ModelStore.modelFile(ctx).delete()
                        status = ModelStore.status(ctx)
                        progress = ""
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(com.spice.nativeapp.core.I18n.t("删除本地模型")) }
            }
            if (progress.isNotEmpty()) Text(progress, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                com.spice.nativeapp.core.I18n.t("推理入口：工具 → 凝胶分析。未下载模型时会提示先完成下载，不会静默失败。"),
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
        Text(com.spice.nativeapp.core.I18n.t("ONNX 神经网络核"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(com.spice.nativeapp.core.I18n.t("模型加载状态:"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (status.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Text(if (status.ok) com.spice.nativeapp.core.I18n.t("已就绪") else com.spice.nativeapp.core.I18n.t("离线"), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (status.ok) {
                    // 对齐 main zh.json：modelPathLabel=路径: / modelSizeLabel=容量: / modelHeadsLabel=输出头:（trainedHeads=A, distogram）
                    Text(
                        com.spice.nativeapp.core.I18n.tf("路径: {v1}\n容量: ", status.path) + "%.1f MB".format(status.sizeBytes / 1048576.0) + com.spice.nativeapp.core.I18n.t("\n输出头: A, distogram"),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(com.spice.nativeapp.core.I18n.t("手动指定本地模型绝对路径:"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            dlMsg = if (status.ok) com.spice.nativeapp.core.I18n.tf("模型已加载：{v1}", status.path) else com.spice.nativeapp.core.I18n.t("未找到模型（路径不存在或为空）")
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(com.spice.nativeapp.core.I18n.t("应用")) }
                }
                Text(
                    com.spice.nativeapp.core.I18n.t("从 SPICE 云端下载固定权重量子"),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(com.spice.nativeapp.core.I18n.t("模型权重 URL:"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            if (url.isEmpty()) { dlOk = false; dlMsg = com.spice.nativeapp.core.I18n.t("URL 为空"); return@Button }
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
                                    dlMsg = com.spice.nativeapp.core.I18n.tf("下载成功：{v1}", it.absolutePath)
                                }, {
                                    progress = null
                                    dlOk = false
                                    dlMsg = com.spice.nativeapp.core.I18n.tf("下载失败：{v1}", it.message)
                                })
                            }
                        },
                        enabled = !dlBusy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (dlBusy) com.spice.nativeapp.core.I18n.t("下载中…") else com.spice.nativeapp.core.I18n.t("下载")) }
                }
                progress?.let { (done, total) ->
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        com.spice.nativeapp.core.I18n.t("已完成 ") + "%.1f MB".format(done / 1048576.0) + " / " + (if (total > 0) "%.1f MB".format(total / 1048576.0) else "—"),
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
                    com.spice.nativeapp.core.I18n.t("说明：原生版暂未集成 onnxruntime 推理运行时（已知缺口），此处负责权重就位与状态管理；蛋白页折叠/相图仍走确定性启发式路径。"),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 酶库口径，供设置页展示 */
/**
 * 引物热力学与 PCR 仿真物理参数 —— main settings/+page.svelte「Primer Thermodynamics」
 * 区块 1:1（T7-15 CodonOptimizer 块配套：宿主偏好数据源头 spice_default_host）。
 * main 区块含 defaultHost 下拉 + 单价/二价/引物浓度三输入；盐浓度参数 Android 端
 * PCR/引物算法当前未接线（README 披露），此处与 main 一致持久化同键，供后续接线。
 * 平台差异：localStorage spice_default_host → SharedPreferences 同名键；gelExportMultiplier
 * 已由 VirtualGel 读取（spice_gel_export_multiplier），此区不重复渲染。
 */
@Composable
fun PrimerThermoSettingsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE) }
    var defaultHost by remember { mutableStateOf(prefs.getString("spice_default_host", "ecoli") ?: "ecoli") }
    var monovalentM by remember { mutableStateOf(prefs.getString("spice_monovalent_m", "0.05") ?: "0.05") }
    var divalentM by remember { mutableStateOf(prefs.getString("spice_divalent_m", "0.0015") ?: "0.0015") }
    var primerM by remember { mutableStateOf(prefs.getString("spice_primer_m", "0.0000005") ?: "0.0000005") }
    var hostMenu by remember { mutableStateOf(false) }

    Text(com.spice.nativeapp.core.I18n.t("引物热力学与 PCR 仿真物理参数"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // main defaultHostLabel 下拉（ecoli/yeast/human）
            Text(com.spice.nativeapp.core.I18n.t("默认反向翻译表达宿主:"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { hostMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (defaultHost) {
                            "yeast" -> com.spice.nativeapp.core.I18n.t("酿酒酵母")
                            "human" -> com.spice.nativeapp.core.I18n.t("人源表达系")
                            else -> com.spice.nativeapp.core.I18n.t("大肠杆菌")
                        }, fontSize = 12.sp
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                    listOf(
                        "ecoli" to com.spice.nativeapp.core.I18n.t("大肠杆菌"),
                        "yeast" to com.spice.nativeapp.core.I18n.t("酿酒酵母"),
                        "human" to com.spice.nativeapp.core.I18n.t("人源表达系")
                    ).forEach { (key, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, fontSize = 12.sp) },
                            onClick = {
                                defaultHost = key; hostMenu = false
                                prefs.edit().putString("spice_default_host", key).apply()
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = monovalentM,
                onValueChange = { monovalentM = it; prefs.edit().putString("spice_monovalent_m", it).apply() },
                label = { Text(com.spice.nativeapp.core.I18n.t("单价盐离子浓度 [Na+] (M):")) },
                placeholder = { Text("0.05") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = divalentM,
                onValueChange = { divalentM = it; prefs.edit().putString("spice_divalent_m", it).apply() },
                label = { Text(com.spice.nativeapp.core.I18n.t("二价镁离子浓度 [Mg2+] (M):")) },
                placeholder = { Text("0.0015") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = primerM,
                onValueChange = { primerM = it; prefs.edit().putString("spice_primer_m", it).apply() },
                label = { Text(com.spice.nativeapp.core.I18n.t("退火引物总分子浓度 [Primer] (M):")) },
                placeholder = { Text("0.0000005") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 数据库同步区块（main settings/+page.svelte 数据库同步三块 1:1）：
 * REBASE / CARD AMR / IGSC 三块——标题、描述、缓存计数、同步按钮；
 * toast 链对齐 handleSyncRebase/handleSyncCardAmr/handleSyncBiosecurity
 * （开始 pushToast success "正在同步…" → 成功 success "…同步成功！{n} 种" / 失败 error "…同步失败"）。
 * 差异披露：Android toast 为系统排队展示，非 main 的可堆叠浮动条。
 */
@Composable
fun DatabaseSyncPanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("spice_sync_ui", android.content.Context.MODE_PRIVATE) }
    var rebaseCount by remember { mutableStateOf(prefs.getInt("spice_rebase_count", 0)) }
    var cardAmrCount by remember { mutableStateOf(prefs.getInt("spice_card_amr_count", 0)) }
    var biosecurityCount by remember { mutableStateOf(prefs.getInt("spice_biosecurity_count", 0)) }
    var syncingRebase by remember { mutableStateOf(false) }
    var syncingCard by remember { mutableStateOf(false) }
    var syncingIgsc by remember { mutableStateOf(false) }

    fun toast(kind: String, title: String, body: String) {
        android.widget.Toast.makeText(ctx, "$title：$body", if (kind == "error") android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ── REBASE ──
            Text(com.spice.nativeapp.core.I18n.t("REBASE 限制酶数据库同步"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(com.spice.nativeapp.core.I18n.t("SPICE 基因编辑器内置了对齐 REBASE 的云端限制酶检索。通过同步可拉取最新的限制酶切割参数。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("本地已缓存限制酶:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$rebaseCount " + com.spice.nativeapp.core.I18n.t("种"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                enabled = !syncingRebase,
                onClick = {
                    syncingRebase = true
                    toast("success", com.spice.nativeapp.core.I18n.t("正在同步 REBASE 限制酶数据库..."), com.spice.nativeapp.core.I18n.t("正在从云端拉取 bionet.txt"))
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.spice.nativeapp.core.SyncDb.syncRebase(ctx) }
                        }
                        syncingRebase = false
                        r.fold(
                            onSuccess = { list ->
                                rebaseCount = list.size
                                prefs.edit().putInt("spice_rebase_count", list.size).apply()
                                toast("success", com.spice.nativeapp.core.I18n.t("REBASE 同步成功！"), com.spice.nativeapp.core.I18n.t("共注册 {n} 种限制性内切酶").replace("{n}", list.size.toString()))
                            },
                            onFailure = { e -> toast("error", com.spice.nativeapp.core.I18n.t("REBASE 同步失败"), e.message ?: e.toString()) }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) { Text(if (syncingRebase) com.spice.nativeapp.core.I18n.t("同步中...") else com.spice.nativeapp.core.I18n.t("同步 NEB-REBASE 数据库"), fontSize = 12.sp) }

            // ── CARD ──
            Text(com.spice.nativeapp.core.I18n.t("CARD 抗菌素耐药基因数据库同步"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(com.spice.nativeapp.core.I18n.t("通过在线同步 McMaster 大学 CARD / ResFinder 的公开耐药特征，支持在本地核对表达载体的筛选抗性合理性与超级耐药菌合规风险。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("本地已缓存耐药抗性基因:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$cardAmrCount " + com.spice.nativeapp.core.I18n.t("种"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                enabled = !syncingCard,
                onClick = {
                    syncingCard = true
                    toast("success", com.spice.nativeapp.core.I18n.t("正在同步 CARD 抗菌素耐药数据库..."), com.spice.nativeapp.core.I18n.t("正在从 McMaster 大学拉取最新 ARO 突变记录"))
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.spice.nativeapp.core.SyncDb.syncCardAmr(ctx) }
                        }
                        syncingCard = false
                        r.fold(
                            onSuccess = { list ->
                                cardAmrCount = list.size
                                prefs.edit().putInt("spice_card_amr_count", list.size).apply()
                                toast("success", com.spice.nativeapp.core.I18n.t("CARD 耐药库同步成功！"), com.spice.nativeapp.core.I18n.t("共载入 {n} 种耐药抗性特征").replace("{n}", list.size.toString()))
                            },
                            onFailure = { e -> toast("error", com.spice.nativeapp.core.I18n.t("CARD 同步失败"), e.message ?: e.toString()) }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) { Text(if (syncingCard) com.spice.nativeapp.core.I18n.t("同步中...") else com.spice.nativeapp.core.I18n.t("同步 CARD AMR 数据库"), fontSize = 12.sp) }

            // ── IGSC ──
            Text(com.spice.nativeapp.core.I18n.t("IGSC 受控生物安全毒素与病原基序同步"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(com.spice.nativeapp.core.I18n.t("同步国际基因合成联盟（IGSC）受控病原和受控毒素序列，全面覆盖烈性毒素与人畜共患高风险病原体特征。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("本地已缓存安全审查特征码:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$biosecurityCount " + com.spice.nativeapp.core.I18n.t("种"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Button(
                enabled = !syncingIgsc,
                onClick = {
                    syncingIgsc = true
                    toast("success", com.spice.nativeapp.core.I18n.t("正在同步 IGSC 烈性生物毒素危害源数据库..."), com.spice.nativeapp.core.I18n.t("正在核对受控 select agents 注册特征码"))
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { com.spice.nativeapp.core.SyncDb.syncBiosecurity(ctx) }
                        }
                        syncingIgsc = false
                        r.fold(
                            onSuccess = { list ->
                                biosecurityCount = list.size
                                prefs.edit().putInt("spice_biosecurity_count", list.size).apply()
                                toast("success", com.spice.nativeapp.core.I18n.t("IGSC 安全库同步成功！"), com.spice.nativeapp.core.I18n.t("共载入 {n} 种烈性生物毒理控制基序").replace("{n}", list.size.toString()))
                            },
                            onFailure = { e -> toast("error", com.spice.nativeapp.core.I18n.t("IGSC 同步失败"), e.message ?: e.toString()) }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) { Text(if (syncingIgsc) com.spice.nativeapp.core.I18n.t("同步中...") else com.spice.nativeapp.core.I18n.t("同步 IGSC 生物安全数据库"), fontSize = 12.sp) }
        }
    }
}

@Composable
fun EnzymeLibraryInfo() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(com.spice.nativeapp.core.I18n.t("限制酶库"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                com.spice.nativeapp.core.I18n.tf("REBASE {v1} 条 · {v2} 个识别位点组 · {v3} 个同义酶名", Enzymes.rebaseCount, Enzymes.groupCount, Enzymes.nameCount),
                fontSize = 12.sp
            )
            Text(
                com.spice.nativeapp.core.I18n.t("识别位点支持 IUPAC 简并碱基（如 ggwcc / cnnnnnnng），切点按 top/bottom snip offset 计算。"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** keymap tab 主题紫（main settings #ff5df6）；冲突/重置红 #ff5555 */
private val KeymapPurple = Color(0xFFFF5DF6)
private val KeymapRed = Color(0xFFFF5555)

/**
 * 快捷键映射区块（main `settings/+page.svelte` keymap tab 1:1 移植）。
 *
 * 平台差异（README §16 披露）：桌面 main 靠全局 KeyboardEvent 完成录制 / 绑定 / 冲突检测 /
 * 组合键触发；安卓触屏无物理键盘事件通道，本轮仅只读展示默认映射清单与使用说明，
 * 录制 / 单条清除 / 单条恢复默认 / 冲突 badge / 基因页全局快捷键监听均不挂载；
 * 「一键恢复默认」清空本地 spice_keymap 持久化配置（触屏下仍有效）。
 */
@Composable
fun KeymapPanel() {
    val ctx = LocalContext.current
    var shortcuts by remember { mutableStateOf(Keymap.load(ctx)) }
    val categories = listOf(
        "view" to com.spice.nativeapp.core.I18n.t("视图与导航控制"),
        "edit" to com.spice.nativeapp.core.I18n.t("编辑、保存与撤销"),
        "tools" to com.spice.nativeapp.core.I18n.t("分子克隆与分析面板切换")
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 标题行：标题 / 一键恢复默认
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    com.spice.nativeapp.core.I18n.t("快捷键映射设置"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = KeymapPurple
                )
                TextButton(onClick = {
                    shortcuts = Keymap.reset(ctx)
                    android.widget.Toast.makeText(
                        ctx,
                        "${com.spice.nativeapp.core.I18n.t("一键恢复默认")}：${com.spice.nativeapp.core.I18n.t("所有快捷键已重置为出厂预设值")}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(com.spice.nativeapp.core.I18n.t("一键恢复默认"), color = KeymapRed)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 录制指南说明框
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    com.spice.nativeapp.core.I18n.t("快捷键使用说明与录制指南"),
                    fontWeight = FontWeight.Bold,
                    color = KeymapPurple,
                    fontSize = 12.sp
                )
                listOf(
                    "在右侧列表中点击「录制」按钮，然后直接在键盘上按下组合键，系统将自动捕获并绑定。",
                    "快捷键支持修饰键如 Ctrl、Alt、Shift 以及 macOS 专设的 Command (此处统一抽象显示为 Mod)。",
                    "录制过程中按下 Esc 键，即可中止本次录制，不修改原按键。",
                    "软件自带输入防冲突保护：当您聚焦于任何文本输入框、编辑器、控制台或标注栏时，全局快捷键将自动静默，绝不影响您的日常打字和文本编辑。"
                ).forEach { step ->
                    Text("• ${com.spice.nativeapp.core.I18n.t(step)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            categories.forEach { (cat, label) ->
                Text(label, fontWeight = FontWeight.Bold, color = KeymapPurple, fontSize = 13.sp)
                Keymap.byCategory(shortcuts, cat).forEach { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        Arrangement.spacedBy(10.dp),
                        Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.name, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Text(s.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        KeyBadge(s.key)
                    }
                }
            }
        }
    }
}

/** 按键徽章（main kbd 黑底紫字；None 绑定显示灰字） */
@Composable
private fun KeyBadge(key: String) {
    val none = key.equals("None", ignoreCase = true)
    Box(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            key,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (none) MaterialTheme.colorScheme.onSurfaceVariant else KeymapPurple
        )
    }
}
