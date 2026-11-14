package com.spice.nativeapp.ui

// 基因页左区三个视图（对齐 main gene/+page.svelte activeTab 的 6 tab 划区：
// circular / linear / sequence / features / properties / notes）：
// - FeaturesView   ← main :3468 特征列表面板（色块/名称/起点/长度/类型/编辑/删除）
// - PropertiesView ← main :3350 属性页（GenBank 字段 + 编辑 DNA 区）
// - NotesView      ← main :3514 ELN 电子实验记录本（页管理器 + Markdown 笔记 + 选区注释）

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── 碱基配色主题（对齐 main rowRender.ts:691 的 colorTheme）─────────────────────────
// main 默认 monochrome；7 项下拉与 gene/+page.svelte:3285 完全一致；hex 逐项取自 rowRender.ts:691-716
enum class BaseColorTheme(val label: String) {
    MONO("单色"),
    ATCG("AIO 标准染色"),
    SNAPGENE("SnapGene 经典染色"),
    BENCHLING("Benchling 现代粉彩"),
    SANGER("Sanger 测序通道色"),
    PURINE("嘌呤/嘧啶化学分类色"),
    GC("GC 含量突出");

    companion object {
        fun fromName(s: String?): BaseColorTheme =
            entries.firstOrNull { it.name == s } ?: MONO
    }
}

/** 碱基颜色（逐项对齐 main rowRender.ts:691-716；monochrome = 前景亮色；未知碱基灰） */
fun baseColorFor(theme: BaseColorTheme, base: Char): Color {
    val b = base.uppercaseChar()
    val c: Long = when (theme) {
        BaseColorTheme.MONO -> 0xFFE2E8F0                       // main COLOR_BASE（单色前景）
        BaseColorTheme.ATCG -> when (b) {
            'A' -> 0xFF53D769; 'T' -> 0xFFFF453A; 'C' -> 0xFF0A84FF; 'G' -> 0xFFFF9F1C; else -> 0xFF9E9E9E
        }
        BaseColorTheme.SNAPGENE -> when (b) {
            'A' -> 0xFFFF453A; 'T' -> 0xFF53D769; 'C' -> 0xFF0A84FF; 'G' -> 0xFFFFD23F; else -> 0xFF9E9E9E
        }
        BaseColorTheme.BENCHLING -> when (b) {
            'A' -> 0xFF94EBD4; 'T' -> 0xFFFDB5C9; 'C' -> 0xFF9AC8FC; 'G' -> 0xFFFFE29A; else -> 0xFF9E9E9E
        }
        BaseColorTheme.SANGER -> when (b) {
            'A' -> 0xFF10AF10; 'T' -> 0xFFDF1010; 'C' -> 0xFF1010DF; 'G' -> 0xFFDFAF10; else -> 0xFF9E9E9E
        }
        BaseColorTheme.PURINE -> if (b == 'A' || b == 'G') 0xFFC084FC else 0xFF38BDF8   // 嘌呤/嘧啶
        BaseColorTheme.GC -> if (b == 'G' || b == 'C') 0xFF4CD6FF else 0xFF475569
    }
    return Color(c)
}

// ───────────────────────── ELN 电子实验记录本（对齐 main notebookPages / localStorage）─────────────────────────

/** ELN 页面（main 元素结构：id/title/category/date/author/content/locked + 签署元数据） */
data class ElnPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val category: String = "General",
    val date: String = SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(Date()),
    val author: String = "",
    val content: String = "",
    val tags: List<String> = listOf("SPICE", "ELN", "Auto_Sign"),
    val locked: Boolean = false,
    // 签署元数据（main createElnSignatureSnapshot：signer/signedAt/reason/witness + digest）
    val signer: String = "",
    val signedAt: String = "",
    val reason: String = "符合实验数据管理规范并核对无误",
    val witness: String = "",
    val orcid: String = "",
    val signatureDigest: String = ""
) {
    /** main 类别 5 值（elnCat*）：存英文 key，显示中文 */
    fun categoryLabel(): String = when (category) {
        "Cloning" -> com.spice.nativeapp.core.I18n.t("分子克隆 (Cloning)")
        "PCR" -> com.spice.nativeapp.core.I18n.t("聚合酶链反应 (PCR)")
        "Cell_Transformation" -> com.spice.nativeapp.core.I18n.t("细胞转化 (Transformation)")
        "Electrophoresis" -> com.spice.nativeapp.core.I18n.t("跑胶验证 (Electrophoresis)")
        else -> com.spice.nativeapp.core.I18n.t("通用实验 (General)")
    }
}

/** main canonicalizeElnSignatureSnapshot verbatim：键字母序 JSON（undefined 省略） */
fun canonicalizeElnSignature(signer: String, signedAt: String, reason: String?, witness: String?, witnessSignedAt: String?): String {
    val esc = { v: String -> v.replace("\\", "\\\\").replace("\"", "\\\"") }
    val parts = mutableListOf<String>()
    reason?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add("\"reason\":\"${esc(it)}\"") }
    parts.add("\"signedAt\":\"${esc(signedAt)}\"")
    parts.add("\"signer\":\"${esc(signer.trim())}\"")
    witness?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add("\"witness\":\"${esc(it)}\"") }
    witnessSignedAt?.let { parts.add("\"witnessSignedAt\":\"${esc(it)}\"") }
    return parts.joinToString(",", "{", "}")
}

/** main digestElnSignatureSnapshot verbatim：SHA-256 lowercase hex */
fun digestElnSignature(canonical: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** ELN 持久化（对齐 main localStorage spice_gene_active_notebook_pages，安卓侧用 SharedPreferences JSON） */
object ElnStore {
    private const val KEY_PAGES = "eln_pages"
    private const val KEY_IDX = "eln_current_idx"

    fun load(ctx: android.content.Context): MutableList<ElnPage> {
        val prefs = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PAGES, null) ?: return mutableListOf(ElnPage())
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ElnPage(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    title = o.optString("title", ""),
                    category = o.optString("category", "General"),
                    date = o.optString("date", ""),
                    author = o.optString("author", ""),
                    content = o.optString("content", ""),
                    tags = o.optJSONArray("tags")?.let { arr2 -> (0 until arr2.length()).map { arr2.optString(it) } } ?: listOf("SPICE", "ELN", "Auto_Sign"),
                    locked = o.optBoolean("locked", false),
                    signer = o.optString("signer", ""),
                    signedAt = o.optString("signedAt", ""),
                    reason = o.optString("reason", com.spice.nativeapp.core.I18n.t("符合实验数据管理规范并核对无误")),
                    witness = o.optString("witness", ""),
                    orcid = o.optString("orcid", ""),
                    signatureDigest = o.optString("signatureDigest", "")
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf(ElnPage())
        }
    }

    fun currentIndex(ctx: android.content.Context, size: Int): Int {
        val prefs = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
        return (prefs.getInt(KEY_IDX, 0)).coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun save(ctx: android.content.Context, pages: List<ElnPage>, idx: Int) {
        val prefs = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
        val arr = org.json.JSONArray()
        pages.forEach { p ->
            arr.put(
                org.json.JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("category", p.category)
                    .put("date", p.date)
                    .put("author", p.author)
                    .put("content", p.content)
                    .put("tags", org.json.JSONArray(p.tags))
                    .put("locked", p.locked)
                    .put("signer", p.signer)
                    .put("signedAt", p.signedAt)
                    .put("reason", p.reason)
                    .put("witness", p.witness)
                    .put("orcid", p.orcid)
                    .put("signatureDigest", p.signatureDigest)
            )
        }
        prefs.edit().putString(KEY_PAGES, arr.toString()).putInt(KEY_IDX, idx).apply()
    }
}

/** 选区注释（对齐 main selectionNotes：{text,start,end}） */
data class SelNote(val text: String, val start: Int, val end: Int)

// ───────────────────────── 特征列表视图（main :3468）─────────────────────────

@Composable
fun FeaturesView(
    record: SeqRecord,
    onSelect: (IntRange) -> Unit,
    onEdit: (Feature) -> Unit,
    onDelete: (Feature) -> Unit,
    onAddFeature: () -> Unit = {},
    onAutoAnnotate: () -> Unit = {}
) {
    // ── 搜索 / 类型过滤 / 排序（main +page.svelte:3478-3494 featureSearchText / featureFilterType / featureSortKey）──
    var searchText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var filterType by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("all") }
    var sortKey by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("start") }
    var filterOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var sortOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val allTypes = record.features.map { it.type }.distinct().sorted()
    val sortedFiltered = record.features
        .filter { f -> filterType == "all" || f.type == filterType }
        .filter { f -> searchText.isBlank() || f.name.contains(searchText, ignoreCase = true) }
        .let { fs ->
            when (sortKey) {
                "name" -> fs.sortedBy { it.name.lowercase() }
                "size" -> fs.sortedBy { kotlin.math.abs(it.end - it.start) + 1 }
                "type" -> fs.sortedBy { it.type }
                "color" -> fs.sortedBy { featureColorOf(it).hashCode() }   // main: 按 color 排
                else -> fs.sortedBy { it.start }                              // start（默认）
            }
        }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        // 头行：注释特征 + 表头搜索过滤排序 (main :3472)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("注释特征 (${sortedFiltered.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddFeature, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 32.dp)) { Text("添加特征注释…", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onAutoAnnotate, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 32.dp)) { Text("自动注释特征", fontSize = 9.sp) }
        }
        // 搜索 + 类型过滤 + 排序
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = searchText, onValueChange = { searchText = it },
                placeholder = { Text("特征名称...", fontSize = 9.5.sp) },
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.5.sp),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp)
            )
            Box {
                TextButton(onClick = { filterOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(if (filterType == "all") "全部类型" else filterType.uppercase(), fontSize = 9.5.sp)
                }
                androidx.compose.material3.DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("全部类型", fontSize = 10.sp) }, onClick = { filterType = "all"; filterOpen = false })
                    allTypes.forEach { t -> androidx.compose.material3.DropdownMenuItem(text = { Text(t.uppercase(), fontSize = 10.sp) }, onClick = { filterType = t; filterOpen = false }) }
                }
            }
            Box {
                TextButton(onClick = { sortOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp), modifier = Modifier.heightIn(min = 36.dp)) {
                    Text(
                        when (sortKey) { "name" -> "名称"; "size" -> "大小"; "type" -> "类型"; "color" -> "颜色"; else -> "起始" },
                        fontSize = 9.5.sp
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    listOf("start" to "起始", "name" to "名称", "size" to "大小", "type" to "类型", "color" to "颜色").forEach { (k, label) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(label, fontSize = 10.sp) }, onClick = { sortKey = k; sortOpen = false })
                    }
                }
            }
        }
        // 列头（main :3495 grid：色块 | 名称 | 起始 | 大小 | 类型 | 编辑 | 删除）
        Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("", modifier = Modifier.size(12.dp))
            Text("名称", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("起始", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
            Text("大小", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
            Text("类型", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
            Text("", modifier = Modifier.size(40.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        LazyColumn(Modifier.fillMaxSize().weight(1f)) {
            items(sortedFiltered) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(f.start..f.end) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.size(10.dp).background(featureColorOf(f), RoundedCornerShape(2.dp)))
                    Text(
                        f.name, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("${f.start}", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
                    Text("${kotlin.math.abs(f.end - f.start) + 1}", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
                    Text(f.type.uppercase(), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp))
                    Icon(Icons.Filled.Edit, contentDescription = "编辑特征", modifier = Modifier.size(16.dp).clickable { onEdit(f) }, tint = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Filled.Delete, contentDescription = "删除特征", modifier = Modifier.size(16.dp).clickable { onDelete(f) }, tint = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
            if (sortedFiltered.isEmpty()) {
                item { Text(if (record.features.isEmpty()) "暂无特征注释" else "无匹配特征", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

// ───────────────────────── 属性视图（main :3350 GenBank 属性页）─────────────────────────

@Composable
fun PropertiesView(record: SeqRecord, onChange: (SeqRecord) -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 创建 / 修改日期（SnapGene 标准头部信息）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = record.createdDate, onValueChange = { onChange(record.copy(createdDate = it)) },
                label = { Text(com.spice.nativeapp.core.I18n.t("创建日期"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = record.modifiedDate, onValueChange = { onChange(record.copy(modifiedDate = it)) },
                label = { Text(com.spice.nativeapp.core.I18n.t("修改日期"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        // 拓扑类型 / 序列类别（对齐 main 的两个下拉）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                label = com.spice.nativeapp.core.I18n.t("拓扑类型"),
                options = listOf("Natural DNA", "Synthetic DNA", "Genomic DNA", "Plasmid"),
                value = record.dnaType,
                onPick = { onChange(record.copy(dnaType = it)) },
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                label = com.spice.nativeapp.core.I18n.t("序列类别"),
                options = listOf(
                    "PLN - plant, fungal, and algal",
                    "BCT - bacteria",
                    "VRL - virus",
                    "SYN - synthetic"
                ),
                value = record.sequenceClass,
                onPick = { onChange(record.copy(sequenceClass = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = record.labHost, onValueChange = { onChange(record.copy(labHost = it)) },
            label = { Text(com.spice.nativeapp.core.I18n.t("实验室宿主"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = record.description, onValueChange = { onChange(record.copy(description = it)) },
            label = { Text(com.spice.nativeapp.core.I18n.t("描述"), fontSize = 11.sp) },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )
        // Accession（+ 在 NCBI 打开，对齐 main 的 Globe 按钮）与 Code Number
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = record.accession, onValueChange = { onChange(record.copy(accession = it)) },
                label = { Text("Accession", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1.4f)
            )
            IconButton(
                onClick = {
                    if (record.accession.isNotBlank()) {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.ncbi.nlm.nih.gov/nuccore/${record.accession}"))
                        )
                    }
                }
            ) { Icon(Icons.Filled.OpenInNew, contentDescription = com.spice.nativeapp.core.I18n.t("在 NCBI 打开")) }
            OutlinedTextField(
                value = record.codeNumber, onValueChange = { onChange(record.copy(codeNumber = it)) },
                label = { Text("Code Number", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = record.author, onValueChange = { onChange(record.copy(author = it)) },
                label = { Text(com.spice.nativeapp.core.I18n.t("作者"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = record.comments, onValueChange = { onChange(record.copy(comments = it)) },
                label = { Text(com.spice.nativeapp.core.I18n.t("备注"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = record.references, onValueChange = { onChange(record.copy(references = it)) },
            label = { Text(com.spice.nativeapp.core.I18n.t("参考文献"), fontSize = 11.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        Text(com.spice.nativeapp.core.I18n.t("编辑 DNA"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = record.name, onValueChange = { onChange(record.copy(name = it)) },
            label = { Text(com.spice.nativeapp.core.I18n.t("质粒名称"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        var seqDraft by remember(record.name, record.sequence) { mutableStateOf(record.sequence) }
        OutlinedTextField(
            value = seqDraft,
            onValueChange = { seqDraft = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("DNA 序列"), fontSize = 11.sp) },
            minLines = 4, maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onChange(record.copy(sequence = seqDraft.filter { !it.isWhitespace() }.uppercase())) }) {
                Text(com.spice.nativeapp.core.I18n.t("应用序列"), fontSize = 12.sp)
            }
            TextButton(onClick = { seqDraft = record.sequence }) { Text(com.spice.nativeapp.core.I18n.t("还原"), fontSize = 12.sp) }
        }
        // 长度 / 拓扑 / GC 摘要（对齐 main 属性页底部 kv 行；GC 越界警示）
        val gc = record.gc
        Text(
            "长度 ${record.length} bp · 拓扑 ${if (record.circular) "环状" else "线性"} · GC ${"%.1f".format(gc)}%" +
                if (gc > 60 || gc < 40) com.spice.nativeapp.core.I18n.t("  ⚠ GC 超出常规范围") else "",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = if (gc > 60 || gc < 40) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 下拉选择（main 属性页 pix-select 的 MD3 等价物） */
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    value: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value, onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
        )
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(o, fontSize = 12.sp) },
                    onClick = { expanded = false; onPick(o) }
                )
            }
        }
    }
}

// ───────────────────────── ELN 笔记视图（main :3514 notes tab）─────────────────────────

@Composable
fun NotesView(
    record: SeqRecord,
    selection: IntRange?,
    pages: MutableList<ElnPage>,
    currentIdx: Int,
    selectionNotes: List<SelNote>,
    onPageChange: (MutableList<ElnPage>, Int) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Int) -> Unit,
    onSelectNote: (IntRange) -> Unit,
    onSavePage: () -> Unit
) {
    val page = pages.getOrNull(currentIdx) ?: pages.firstOrNull() ?: ElnPage()
    var showManager by remember { mutableStateOf(false) }
    var newNoteText by remember { mutableStateOf("") }
    // 签署面板本地状态（main elnWitness/elnReason/ORCID 身份）
    var witnessText by remember(page.id) { mutableStateOf(page.witness) }
    var reasonText by remember(page.id) { mutableStateOf(page.reason) }
    var orcidText by remember(page.id) { mutableStateOf(page.orcid) }
    var orcidResolved by remember(page.id) { mutableStateOf(page.orcid.isNotEmpty()) }

    // main insertDnaBlock：选区（或全长）→ 10bp/块、5 块/行 → ### 🧬 DNA Sequence Block + ```dna 围栏
    fun buildDnaBlockMd(): String {
        val sel = selection
        val (raw, name) = if (sel != null && sel.last >= sel.first) {
            record.sequence.substring((sel.first - 1).coerceIn(0, record.length), sel.last.coerceAtMost(record.length)) to
                com.spice.nativeapp.core.I18n.tf("选区片段 ({v1}..{v2} bp)", sel.first, sel.last)
        } else record.sequence to com.spice.nativeapp.core.I18n.tf("{v1} 全长序列", record.name)
        val clean = raw.lowercase().filter { it in "atcgn" }
        val lines = clean.chunked(50).map { it.chunked(10).joinToString(" ") }
        return "\n\n### 🧬 DNA Sequence Block - $name [${raw.length} bp]\n```dna\n${lines.joinToString("\n")}\n```\n"
    }
    // main insertProteinBlock：选区（或全长）翻译蛋白块
    fun buildProteinBlockMd(): String {
        val sel = selection
        val (raw, name) = if (sel != null && sel.last >= sel.first) {
            record.sequence.substring((sel.first - 1).coerceIn(0, record.length), sel.last.coerceAtMost(record.length)) to
                com.spice.nativeapp.core.I18n.tf("选区翻译蛋白 ({v1}..{v2} bp)", sel.first, sel.last)
        } else record.sequence to com.spice.nativeapp.core.I18n.tf("{v1} 全长翻译蛋白", record.name)
        val protein = com.spice.nativeapp.core.Bio.translate(raw)
        return "\n\n### 🧪 Protein Sequence Block - $name [${protein.length} aa]\n```protein\n${protein.chunked(60).joinToString("\n")}\n```\n"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部：保存页 / 页管理器（对齐 main [保存页] [页管理器 (N)]）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(com.spice.nativeapp.core.I18n.t("实验记录"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSavePage) { Text(com.spice.nativeapp.core.I18n.t("[保存页]"), fontSize = 11.sp, color = Color(0xFF4CAF50)) }
                TextButton(onClick = { showManager = true }) { Text(com.spice.nativeapp.core.I18n.tf("[页管理器 ({v1})]", pages.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
        // 当前页信息条（对齐 main 黄色左边框信息行）
        Text(
            com.spice.nativeapp.core.I18n.tf("当前页：{v1}. {v2} · {v3} · {v4}", currentIdx + 1, page.title.ifBlank { "(untitled)" }, page.author.ifBlank { "—" }, page.date) +
                if (page.locked) com.spice.nativeapp.core.I18n.t(" · 🔒 已锁定") else "",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        // 标题 / 类别（main :3600 记录标题+实验类型下拉 5 值 verbatim）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = page.title, onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(title = p) } },
                label = { Text(com.spice.nativeapp.core.I18n.t("记录标题 (ELN Title)"), fontSize = 11.sp) }, singleLine = true,
                readOnly = page.locked, modifier = Modifier.weight(1f)
            )
            var catMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { if (!page.locked) catMenu = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(page.categoryLabel(), fontSize = 11.sp, maxLines = 1) }
                DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                    listOf("Cloning" to com.spice.nativeapp.core.I18n.t("分子克隆 (Cloning)"), "PCR" to com.spice.nativeapp.core.I18n.t("聚合酶链反应 (PCR)"),
                        "Cell_Transformation" to com.spice.nativeapp.core.I18n.t("细胞转化 (Transformation)"),
                        "Electrophoresis" to com.spice.nativeapp.core.I18n.t("跑胶验证 (Electrophoresis)"),
                        "General" to com.spice.nativeapp.core.I18n.t("通用实验 (General)")).forEach { (v, label) ->
                        DropdownMenuItem(
                            text = { Text((if (page.category == v) "✓ " else "　") + label, fontSize = 12.sp) },
                            onClick = {
                                catMenu = false
                                updatePage(pages, currentIdx, onPageChange) { it.copy(category = v) }
                            }
                        )
                    }
                }
            }
        }
        // 文献标签（main elnTagsLabel，英文逗号分隔）
        OutlinedTextField(
            value = page.tags.joinToString(", "),
            onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(tags = p.split(",").map { it.trim() }.filter { it.isNotEmpty() }) } },
            label = { Text(com.spice.nativeapp.core.I18n.t("文献标签 (Tags, 英文逗号分隔)"), fontSize = 11.sp) },
            placeholder = { Text(com.spice.nativeapp.core.I18n.t("例如: SPICE, ELN, Auto_Sign"), fontSize = 11.sp) },
            singleLine = true, readOnly = page.locked, modifier = Modifier.fillMaxWidth()
        )
        // Markdown 内容（main MilkdownEditor 的原生等价物：纯 Markdown 文本编辑）
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                updatePage(pages, currentIdx, onPageChange) { it.copy(content = it.content + buildDnaBlockMd()) }
            }, enabled = !page.locked) { Text(com.spice.nativeapp.core.I18n.t("🧬 插入 DNA 选区"), fontSize = 11.sp) }
            OutlinedButton(onClick = {
                updatePage(pages, currentIdx, onPageChange) { it.copy(content = it.content + buildProteinBlockMd()) }
            }, enabled = !page.locked) { Text(com.spice.nativeapp.core.I18n.t("🧪 插入翻译蛋白选区"), fontSize = 11.sp) }
        }
        OutlinedTextField(
            value = page.content,
            onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(content = p) } },
            label = { Text(com.spice.nativeapp.core.I18n.t("Markdown 内容"), fontSize = 11.sp) },
            readOnly = page.locked,
            minLines = 6, maxLines = 14,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
        // 选区注释创建块（对齐 main selection note block）
        if (selection != null && selection.last >= selection.first) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    com.spice.nativeapp.core.I18n.tf("为选区添加注释（{v1}..{v2} bp）", selection.first, selection.last),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNoteText, onValueChange = { newNoteText = it },
                        placeholder = { Text(com.spice.nativeapp.core.I18n.t("注释内容…"), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (newNoteText.isNotBlank()) {
                            onAddNote(newNoteText); newNoteText = ""
                        }
                    }) { Text(com.spice.nativeapp.core.I18n.t("添加"), fontSize = 12.sp) }
                }
            }
        }
        // ── ELN 数字签署面板（main :3611-3648 Feature 25 verbatim）──
        Text(com.spice.nativeapp.core.I18n.t("📔 电子实验记录本数字签署 (ELN Digital Signing) [F-25]"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // ORCID 身份行（main :3628 ORCID iD + 解析状态 + [解析 ORCID]）
                Row(
                    Modifier.fillMaxWidth().padding(5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    var orcidInput by remember(page.id) { mutableStateOf(page.orcid) }
                    Column(Modifier.weight(1f)) {
                        Text("ORCID iD: ${page.orcid.ifEmpty { "未解析" }}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!page.locked) OutlinedTextField(
                            value = orcidInput, onValueChange = { orcidInput = it },
                            placeholder = { Text("0000-0002-1825-0097", fontSize = 10.sp) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!page.locked) TextButton(onClick = {
                        // main resolveElnIdentity：本地登记 16 位 ORCID 即视为已解析（离线分支版语义）
                        if (Regex("^\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]$").matches(orcidInput.trim())) {
                            updatePage(pages, currentIdx, onPageChange) { it.copy(orcid = orcidInput.trim(), author = it.author.ifEmpty { orcidInput.trim() }) }
                            orcidResolved = true
                        }
                    }) { Text(com.spice.nativeapp.core.I18n.t("[解析 ORCID]"), fontSize = 10.sp) }
                }
                if (!page.locked) {
                    // 见证复核人 / 签署理由（main :3634-3644 双列）
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = witnessText, onValueChange = { witnessText = it },
                            label = { Text(com.spice.nativeapp.core.I18n.t("见证复核人 (Witness/Reviewer)"), fontSize = 10.sp) },
                            placeholder = { Text(com.spice.nativeapp.core.I18n.t("输入复核人/导师姓名..."), fontSize = 10.sp) },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = reasonText, onValueChange = { reasonText = it },
                            label = { Text(com.spice.nativeapp.core.I18n.t("签署理由 (Signing Reason)"), fontSize = 10.sp) },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    // 防伪提示（main elnSignHint* 21 CFR Part 11）
                    Text(
                        com.spice.nativeapp.core.I18n.t("ℹ️ 防伪数字签名提示 (21 CFR Part 11): 签署后页面将被永久锁定且不可修改，并联同当前的审计追踪日志 (Audit Trail)、质粒序列与特征元数据一并执行 SHA-256 学术数字指纹加密签名。"),
                        fontSize = 9.sp, lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)).padding(5.dp)
                    )
                    Button(
                        onClick = {
                            if (page.orcid.isEmpty()) return@Button
                            // main signCurrentEln：canonicalize → SHA-256 → 锁页归档
                            val signedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(Date())
                            val canonical = canonicalizeElnSignature(page.orcid, signedAt, reasonText, witnessText, witnessText.takeIf { it.isNotBlank() }?.let { signedAt })
                            val digest = digestElnSignature(canonical)
                            updatePage(pages, currentIdx, onPageChange) {
                                it.copy(locked = true, signer = page.orcid, signedAt = signedAt, reason = reasonText, witness = witnessText, signatureDigest = digest)
                            }
                        },
                        enabled = page.orcid.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(com.spice.nativeapp.core.I18n.t("🚀 确认签署并归档锁定页面 (.md)"), fontSize = 11.sp) }
                } else {
                    // 已锁定状态（main :3645-3649 🔒）
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(com.spice.nativeapp.core.I18n.t("🔒 实验记录已签署并锁定"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("ORCID iD: ${page.orcid.ifEmpty { "未解析" }}", fontSize = 9.sp)
                        Text(com.spice.nativeapp.core.I18n.tf("签名摘要: {v1}", page.signatureDigest.ifEmpty { "不可用" }), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text(com.spice.nativeapp.core.I18n.tf("签署时间: {v1}", page.signedAt), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 选区注释列表（点击跳转选区，可删除）
        Text(com.spice.nativeapp.core.I18n.tf("选区注释（{v1}）", selectionNotes.size), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        selectionNotes.forEachIndexed { i, n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelectNote(n.start..n.end) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.spice.nativeapp.core.I18n.tf("区间 {v1} - {v2} bp（{v3} bp）", n.start, n.end, n.end - n.start + 1), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.Delete, contentDescription = com.spice.nativeapp.core.I18n.t("删除注释"),
                    modifier = Modifier.size(16.dp).clickable { onDeleteNote(i) },
                    tint = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }

    // 页管理器（对齐 main showPageManagerModal：新建 / 选择 / 删除）
    if (showManager) {
        AlertDialog(
            onDismissRequest = { showManager = false },
            title = { Text(com.spice.nativeapp.core.I18n.t("页面管理器")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(com.spice.nativeapp.core.I18n.tf("共 {v1} 页", pages.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        pages.add(ElnPage(title = com.spice.nativeapp.core.I18n.tf("新页 {v1}", pages.size + 1)))
                        onPageChange(pages, pages.size - 1)
                    }) { Text(com.spice.nativeapp.core.I18n.t("+ 新建页"), fontSize = 12.sp) }
                    LazyColumn(Modifier.heightIn(280.dp)) {
                        pageRows(pages, currentIdx, onSelect = { idx ->
                            onPageChange(pages, idx); showManager = false
                        }, onDelete = { idx ->
                            if (pages.size > 1) {
                                pages.removeAt(idx)
                                onPageChange(pages, currentIdx.coerceAtMost(pages.size - 1))
                            }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showManager = false }) { Text(com.spice.nativeapp.core.I18n.t("完成并关闭")) } }
        )
    }
}

private fun updatePage(
    pages: MutableList<ElnPage>,
    idx: Int,
    onChange: (MutableList<ElnPage>, Int) -> Unit,
    transform: (ElnPage) -> ElnPage
) {
    val i = idx.coerceIn(0, pages.size - 1)
    pages[i] = transform(pages[i])
    onChange(pages, i)
}

/** 页管理器列表（main：序号 / 标题 / 类别·日期·🔒 / 删除，当前页青色描边） */
private fun androidx.compose.foundation.lazy.LazyListScope.pageRows(
    pages: List<ElnPage>,
    currentIdx: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit
) {
    items(pages.size) { idx ->
        val p = pages[idx]
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(idx) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${idx + 1}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Column(Modifier.weight(1f)) {
                Text(p.title.ifBlank { "(untitled)" }, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.category} · ${p.date}" + if (p.locked) " · 🔒" else "",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (idx == currentIdx) {
                Text(com.spice.nativeapp.core.I18n.t("当前"), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
            Icon(
                Icons.Filled.Delete, contentDescription = com.spice.nativeapp.core.I18n.t("删除页"),
                modifier = Modifier.size(16.dp).clickable { onDelete(idx) },
                tint = MaterialTheme.colorScheme.error
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}
