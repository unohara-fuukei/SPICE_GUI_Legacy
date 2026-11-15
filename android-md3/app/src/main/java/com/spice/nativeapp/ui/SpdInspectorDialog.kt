package com.spice.nativeapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.I18n
import com.spice.nativeapp.core.SpdLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * SpdInspectorDialog —— main src/lib/spd/SpdInspector.svelte 1:1（记录检查器）。
 *
 * 形态差异（披露）：main 为 470px 可拖拽浮动面板；Android 以 MD3 AlertDialog 呈现
 * 同一信息结构（kind 标签 + ID 行 + 概览 + kind 特有分区 + 原始 JSON）。
 * 数据链 1:1：load() 直接从 API 取服务器当前记录（vector 取 sequenceArtifactId
 * 长度、fold 取关联 sequence/environment/model、gene 取 regions）；文本取值 text()
 * 的 '—' 空值语义；timeText 的 createdAt/updatedAt 等秒→本地时间与 organism 拼接。
 */
@Composable
fun SpdInspectorDialog(
    ctx: android.content.Context,
    kind: String,
    id: String,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var record by remember { mutableStateOf<JSONObject?>(null) }
    var related by remember { mutableStateOf<Triple<JSONObject?, JSONObject?, JSONObject?>?>(null) }
    var geneRegions by remember { mutableStateOf<JSONArray?>(null) }
    var sequenceLength by remember { mutableStateOf<Int?>(null) }
    var copied by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current

    val kindLabel = when (kind) {
        "vector" -> I18n.t("载体")
        "protein" -> I18n.t("蛋白")
        "fold" -> I18n.t("Fold")
        "enzyme" -> I18n.t("限制酶")
        "artifact" -> I18n.t("产物")
        "gene" -> I18n.t("基因")
        else -> kind
    }

    LaunchedEffect(kind, id, reloadKey) {
        loading = true; error = ""; record = null; related = null; geneRegions = null; sequenceLength = null
        val r = withContext(Dispatchers.IO) { SpdLibrary.getRecord(ctx, kind, id) }
        if (r.error != null) { error = r.error ?: ""; loading = false; return@LaunchedEffect }
        val rec = r.value ?: JSONObject()
        record = rec
        when (kind) {
            "vector" -> {
                val artifactId = rec.optString("sequenceArtifactId", "")
                if (artifactId.isNotBlank()) {
                    val fasta = withContext(Dispatchers.IO) { SpdLibrary.getArtifactContentText(ctx, artifactId) }
                    fasta.value?.let { text ->
                        sequenceLength = text.split(Regex("\\r?\\n"))
                            .filterNot { it.startsWith(">") }
                            .joinToString("").replace(Regex("\\s+"), "").length
                    }
                }
            }
            "fold" -> {
                val sequenceId = rec.optString("sequenceId", "")
                val environmentId = rec.optString("environmentId", "")
                val modelId = rec.optString("modelId", "")
                val seq = if (sequenceId.isNotBlank()) withContext(Dispatchers.IO) { SpdLibrary.getRecord(ctx, "sequence", sequenceId) }.value else null
                val env = if (environmentId.isNotBlank()) withContext(Dispatchers.IO) { SpdLibrary.getRecord(ctx, "environment", environmentId) }.value else null
                val mdl = if (modelId.isNotBlank()) withContext(Dispatchers.IO) { SpdLibrary.getRecord(ctx, "model", modelId) }.value else null
                related = Triple(seq, env, mdl)
            }
            "gene" -> {
                val regions = withContext(Dispatchers.IO) { SpdLibrary.listGeneRegionsRaw(ctx, id) }
                geneRegions = regions.value ?: JSONArray()
            }
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("SPD 记录详情") + " · " + kindLabel, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    loading -> Text(I18n.t("正在获取记录…"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error.isNotBlank() -> {
                        Text(I18n.t("记录获取失败"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Text(error, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { reloadKey++ }) { Text(I18n.t("重试")) }
                    }
                    record != null -> {
                        val rec = record!!
                        // ID 行 + 复制 ID（main copyId）
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(id, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(id)); copied = true
                            }) { Text(if (copied) I18n.t("已复制") else I18n.t("复制 ID"), fontSize = 10.sp) }
                        }
                        // 概览（main buildOverview 键序 1:1）
                        val overviewKeys = when (kind) {
                            "vector" -> listOf("name", "topology", "format", "schemaVersion", "coordinateSystem", "sequenceArtifactId")
                            "protein" -> listOf("preferredName", "geneName", "organism", "license", "annotationScore", "status")
                            "fold" -> listOf("verificationLevel", "validationStatus", "sequenceId", "environmentId", "modelId", "foldIdentityHash", "createdAt", "updatedAt")
                            "enzyme" -> listOf("name", "recognitionSite", "cutPosition", "enzymeType", "targetMolecule", "proteinId", "source", "sourceRelease", "sourceFetchedAt", "license", "citation")
                            "artifact" -> listOf("kind", "mediaType", "byteLength", "sha256", "status")
                            "gene" -> listOf("name", "symbol", "geneType", "organism", "accession", "locusTag", "ncbiGeneId", "moleculeType", "topology", "chromosome", "mapLocation", "strand", "dnaLength", "rnaLength", "sequenceVersion", "sourceUpdatedAt", "createdAt")
                            else -> emptyList()
                        }
                        Text(I18n.t("概览"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        overviewKeys.filter { rec.has(it) && !rec.isNull(it) }.forEach { key ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(key, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(inspectorText(key, rec.get(key)), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        sequenceLength?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("sequenceLength", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(it.toString(), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        // fold：关联记录（sequence/environment/model 卡）+ prediction/derivedMetrics/runtimeMetadata/artifactIds
                        if (kind == "fold") {
                            Text(I18n.t("关联记录"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            related?.let { (seq, env, mdl) ->
                                seq?.let {
                                    InspectorKvCard(I18n.t("序列"), listOf("id", "length", "sequenceRole", "proteinId", "sha256"), it)
                                }
                                env?.let {
                                    InspectorKvCard("environment", listOf("ph", "temperatureK", "pressureBar", "ionicStrengthM", "protonationModel"), it)
                                }
                                mdl?.let {
                                    InspectorKvCard("model", listOf("modelName", "modelVersion", "inferenceRuntime", "inferenceCodeVersion", "checkpointSha256"), it)
                                }
                            }
                            rec.optJSONObject("prediction")?.let {
                                InspectorJsonSection(I18n.t("预测"), it)
                            }
                            rec.optJSONObject("derivedMetrics")?.let {
                                InspectorJsonSection(I18n.t("派生指标"), it)
                            }
                            rec.optJSONObject("runtimeMetadata")?.let {
                                InspectorJsonSection(I18n.t("运行时元数据"), it)
                            }
                            rec.optJSONArray("artifactIds")?.let {
                                InspectorJsonSection(I18n.t("产物"), it)
                            }
                        }
                        // enzyme：metadata 原始 JSON
                        if (kind == "enzyme" && rec.has("metadata") && !rec.isNull("metadata")) {
                            Text(I18n.t("原始 JSON"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prettyJson(rec.opt("metadata")), fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 180.dp))
                        }
                        // 原始 JSON（所有 kind）
                        Text(I18n.t("原始 JSON"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(prettyJson(rec), fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 200.dp))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    // main openInSpd：{root}/{route}/{id}（d-api → d）
                    val route = when (kind) {
                        "vector" -> "vectors"; "protein" -> "proteins"; "fold" -> "folds"
                        "enzyme" -> "enzymes"; "artifact" -> "artifacts"; "gene" -> "genes"; else -> kind
                    }
                    val root = SpdLibrary.baseUrl(ctx).trimEnd('/')
                        .replace(Regex("/api/v1/?$"), "")
                        .replace("://d-api.", "://d.")
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$root/$route/$id")))
                    }
                }) { Text(I18n.t("在 SPD 网站打开"), fontSize = 10.sp) }
                TextButton(onClick = { reloadKey++ }) { Text(I18n.t("重新获取"), fontSize = 10.sp) }
                TextButton(onClick = onDismiss) { Text(I18n.t("关闭"), fontSize = 10.sp) }
            }
        }
    )
}

@Composable
private fun InspectorKvCard(title: String, keys: List<String>, o: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
        keys.filter { o.has(it) && !o.isNull(it) }.forEach { key ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(key, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(inspectorText(key, o.get(key)), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun InspectorJsonSection(title: String, value: Any) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
        Text(prettyJson(value), fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 160.dp))
    }
}

/** main text()：null/undefined/'' → '—'；object → JSON 字符串。 */
internal fun inspectorText(key: String, value: Any): String = when {
    key == "organism" && value is JSONObject -> {
        val label = listOfNotNull(
            value.optString("scientificName").takeIf { it.isNotBlank() },
            value.opt("taxId")?.takeIf { it.toString().isNotEmpty() }?.let { "taxId $it" },
        ).joinToString(" · ")
        if (label.isNotBlank()) label else "—"
    }
    (key == "createdAt" || key == "updatedAt" || key == "completedAt" || key == "sourceUpdatedAt" || key == "sourceFetchedAt") && value is Number -> {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(value.toLong() * 1000))
    }
    value == JSONObject.NULL -> "—"
    value is String && value.isEmpty() -> "—"
    value is JSONObject || value is JSONArray -> value.toString()
    else -> value.toString()
}

/** main pretty()：JSON.stringify(value, null, 2)。 */
internal fun prettyJson(value: Any): String = try {
    when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }
} catch (e: Exception) {
    value.toString()
}
