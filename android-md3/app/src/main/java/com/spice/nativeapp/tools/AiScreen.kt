package com.spice.nativeapp.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 本地启发式建议（离线可用） */
fun localAdvice(rec: SeqRecord): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val gc = rec.gc
    out.add(com.spice.nativeapp.core.I18n.t("序列概览") to "${rec.name}：${rec.length} bp，GC ${"%.1f".format(gc)}%，${rec.features.size} 个特征，${if (rec.circular) "环状" else "线性"}")
    out.add(
        com.spice.nativeapp.core.I18n.t("GC 提示") to when {
            gc < 35 -> com.spice.nativeapp.core.I18n.t("GC 偏低（<35%），PCR 可能需要提高 Mg²⁺ 或使用 GC 缓冲液；测序时留意 poly-A 区。")
            gc > 65 -> com.spice.nativeapp.core.I18n.t("GC 偏高（>65%），建议加 DMSO/甜菜碱或提高退火温度，避免二级结构干扰。")
            else -> com.spice.nativeapp.core.I18n.t("GC 含量处于常规可扩增区间（35–65%）。")
        }
    )
    val runs = Regex("(A{8,}|T{8,}|G{8,}|C{8,})").findAll(rec.sequence).map { it.value }.toList()
    if (runs.isNotEmpty()) {
        out.add(com.spice.nativeapp.core.I18n.t("同聚物风险") to com.spice.nativeapp.core.I18n.tf("检出 {v1} 处 ≥8 nt 同聚物（最长 {v2} nt），Sanger 测序易滑移。", runs.size, runs.maxOf { it.length }))
    }
    val unique = Enzymes.catalog.filter { Bio.cutSites(rec.sequence, it, rec.circular).size == 1 }.map { it.name }
    val zero = Enzymes.catalog.filter { Bio.cutSites(rec.sequence, it, rec.circular).isEmpty() }.map { it.name }
    if (unique.isNotEmpty()) out.add(com.spice.nativeapp.core.I18n.t("单酶切候选") to com.spice.nativeapp.core.I18n.t("唯一切点酶：") + unique.take(8).joinToString("、"))
    if (zero.isNotEmpty()) out.add(com.spice.nativeapp.core.I18n.t("无切点酶") to com.spice.nativeapp.core.I18n.t("不切酶：") + zero.take(8).joinToString("、"))
    val orf = Bio.longestOrf(rec.sequence, 100)
    out.add("ORF" to (orf?.let { com.spice.nativeapp.core.I18n.tf("最长 ORF：{v1}–{v2}（{v3} aa）", it.first, it.second, it.third) } ?: com.spice.nativeapp.core.I18n.t("未检出 ≥100 aa 的 ORF")))
    out.add(com.spice.nativeapp.core.I18n.t("下一步建议") to com.spice.nativeapp.core.I18n.t("①「克隆」页设计引物并核对 Tm；②「工具 › 凝胶」核对酶切图谱；③「项目」页保存留档。"))
    return out
}

private const val PREFS = "spice_ai"

private fun Context.aiPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 调用 OpenAI 兼容接口（非流式），返回文本或错误说明 */
private fun chatCompletion(endpoint: String, key: String, model: String, prompt: String, system: String): String {
    val body = JSONObject().apply {
        put("model", model)
        put("temperature", 0.3)
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", prompt))
        })
    }.toString()
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 40000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $key")
    }
    return try {
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) com.spice.nativeapp.core.I18n.tf("请求失败 HTTP {v1}：{v2}", code, text.take(300))
        else {
            val json = JSONObject(text)
            json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: text.take(400)
        }
    } catch (e: Exception) {
        com.spice.nativeapp.core.I18n.tf("请求异常：{v1}", e.message)
    } finally {
        conn.disconnect()
    }
}

@Composable
fun AiScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("这条质粒做双酶切克隆要注意什么？") }
    var answer by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val prefs = remember { ctx.aiPrefs() }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var showConfig by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("实验助手"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("本地规则离线可用；配置 OpenAI 兼容接口后可对话（Key 只存本机 SharedPreferences）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("问题"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { answer = localAdvice(record) }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("本地分析")) }
            Button(
                onClick = {
                    if (key.isBlank()) { reply = com.spice.nativeapp.core.I18n.t("请先在下方填写 API Key"); return@Button }
                    busy = true
                    scope.launch {
                        val system = com.spice.nativeapp.core.I18n.tf("你是分子生物学实验助手。当前序列：{v1}，{v2} bp，", record.name, record.length) +
                            "GC ${"%.1f".format(record.gc)}%，${if (record.circular) "环状质粒" else "线性"}，" +
                            com.spice.nativeapp.core.I18n.tf("特征：{v1}。回答中文、给可执行步骤。", record.features.take(6).joinToString("、") { it.name })
                        val r = withContext(Dispatchers.IO) {
                            chatCompletion(endpoint, key, model, question, system)
                        }
                        reply = r
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) com.spice.nativeapp.core.I18n.t("请求中…") else com.spice.nativeapp.core.I18n.t("AI 对话")) }
        }

        if (reply.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(com.spice.nativeapp.core.I18n.t("AI 回复"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(reply, fontSize = 12.sp)
                }
            }
        }

        answer.forEach { (title, body) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(body, fontSize = 12.sp)
                }
            }
        }

        OutlinedButton(onClick = { showConfig = !showConfig }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showConfig) com.spice.nativeapp.core.I18n.t("收起接口配置") else com.spice.nativeapp.core.I18n.t("配置 AI 接口"))
        }
        if (showConfig) {
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text(com.spice.nativeapp.core.I18n.t("接口地址"), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(com.spice.nativeapp.core.I18n.t("模型名"), fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Button(
                onClick = {
                    prefs.edit().putString("endpoint", endpoint).putString("model", model).putString("key", key).apply()
                    reply = com.spice.nativeapp.core.I18n.t("配置已保存（仅本机）")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("保存配置")) }
        }
    }
}
