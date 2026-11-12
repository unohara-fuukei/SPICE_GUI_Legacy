package com.spice.nativeapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val PREF = "spice_settings"
private const val KEY_BASE = "spd_base_url"
private const val KEY_TOKEN = "spd_api_token"
private const val KEY_ORCID = "spd_orcid"
private const val KEY_LAB = "spd_lab"
private const val KEY_NAME = "spd_display_name"
private const val DEFAULT_SPD_BASE = "https://d-api.spicebio.top/api/v1"

/** 与原版 settings/+page.svelte 对齐：SPD 身份（ORCID）、实例地址与 Universal API Token */
@Composable
fun SpdSettingsSection() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE) }
    var base by remember { mutableStateOf(prefs.getString(KEY_BASE, DEFAULT_SPD_BASE) ?: DEFAULT_SPD_BASE) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
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
            .putString(KEY_ORCID, orcid.trim())
            .putString(KEY_LAB, lab.trim())
            .putString(KEY_NAME, displayName.trim())
            .apply()
    }

    Text("SPD 身份与凭据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
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
                label = { Text("API Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
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
                            result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val json = httpGet("${base.trim().ifEmpty { DEFAULT_SPD_BASE }.trimEnd('/')}/health", null)
                                    "服务正常：${json.optString("status", "ok")} · ${json.optString("service", "spd")} ${json.optString("version", "")}".trim()
                                }.getOrElse { "无法连接：${it.message}" }
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("检查服务") }
                Button(
                    onClick = {
                        save()
                        busy = true; result = null
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val json = httpGet("${base.trim().ifEmpty { DEFAULT_SPD_BASE }.trimEnd('/')}/user/me", token.trim())
                                    val id = json.optString("id", json.optString("user_id", ""))
                                    val email = json.optString("email", "")
                                    val plan = json.optString("plan", "")
                                    "已认证：${email.ifEmpty { id }}${if (plan.isNotEmpty()) " · $plan" else ""}"
                                }.getOrElse { "Token 无效或网络不通：${it.message}" }
                            }
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
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

/** 酶库口径，供设置页展示 */
@Composable
fun EnzymeLibraryInfo() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
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

private fun httpGet(url: String, token: String?): JSONObject {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
    return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
}
