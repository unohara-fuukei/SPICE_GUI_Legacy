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

/** ELN 页面（main 元素结构：id/title/category/date/author/content/locked） */
data class ElnPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val category: String = "实验记录",
    val date: String = SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(Date()),
    val author: String = "",
    val content: String = "",
    val locked: Boolean = false
)

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
                    category = o.optString("category", "实验记录"),
                    date = o.optString("date", ""),
                    author = o.optString("author", ""),
                    content = o.optString("content", ""),
                    locked = o.optBoolean("locked", false)
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
                    .put("locked", p.locked)
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
    onDelete: (Feature) -> Unit
) {
    if (record.features.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无特征注释", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        items(record.features) { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(f.start..f.end) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 色块（对齐 main 特征行首的 10px 色块）
                Box(Modifier.size(10.dp).background(featureColor(f.type), RoundedCornerShape(2.dp)))
                Text(
                    f.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("${f.start}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("${kotlin.math.abs(f.end - f.start) + 1}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    f.type,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Filled.Edit, contentDescription = "编辑特征",
                    modifier = Modifier.size(16.dp).clickable { onEdit(f) },
                    tint = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Filled.Delete, contentDescription = "删除特征",
                    modifier = Modifier.size(16.dp).clickable { onDelete(f) },
                    tint = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
                label = { Text("创建日期", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = record.modifiedDate, onValueChange = { onChange(record.copy(modifiedDate = it)) },
                label = { Text("修改日期", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        // 拓扑类型 / 序列类别（对齐 main 的两个下拉）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                label = "拓扑类型",
                options = listOf("Natural DNA", "Synthetic DNA", "Genomic DNA", "Plasmid"),
                value = record.dnaType,
                onPick = { onChange(record.copy(dnaType = it)) },
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                label = "序列类别",
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
            label = { Text("实验室宿主", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = record.description, onValueChange = { onChange(record.copy(description = it)) },
            label = { Text("描述", fontSize = 11.sp) },
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
            ) { Icon(Icons.Filled.OpenInNew, contentDescription = "在 NCBI 打开") }
            OutlinedTextField(
                value = record.codeNumber, onValueChange = { onChange(record.copy(codeNumber = it)) },
                label = { Text("Code Number", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = record.author, onValueChange = { onChange(record.copy(author = it)) },
                label = { Text("作者", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = record.comments, onValueChange = { onChange(record.copy(comments = it)) },
                label = { Text("备注", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = record.references, onValueChange = { onChange(record.copy(references = it)) },
            label = { Text("参考文献", fontSize = 11.sp) }, minLines = 2, modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        Text("编辑 DNA", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = record.name, onValueChange = { onChange(record.copy(name = it)) },
            label = { Text("质粒名称", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        var seqDraft by remember(record.name, record.sequence) { mutableStateOf(record.sequence) }
        OutlinedTextField(
            value = seqDraft,
            onValueChange = { seqDraft = it },
            label = { Text("DNA 序列", fontSize = 11.sp) },
            minLines = 4, maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onChange(record.copy(sequence = seqDraft.filter { !it.isWhitespace() }.uppercase())) }) {
                Text("应用序列", fontSize = 12.sp)
            }
            TextButton(onClick = { seqDraft = record.sequence }) { Text("还原", fontSize = 12.sp) }
        }
        // 长度 / 拓扑 / GC 摘要（对齐 main 属性页底部 kv 行；GC 越界警示）
        val gc = record.gc
        Text(
            "长度 ${record.length} bp · 拓扑 ${if (record.circular) "环状" else "线性"} · GC ${"%.1f".format(gc)}%" +
                if (gc > 60 || gc < 40) "  ⚠ GC 超出常规范围" else "",
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部：保存页 / 页管理器（对齐 main [保存页] [页管理器 (N)]）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("实验记录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSavePage) { Text("[保存页]", fontSize = 11.sp, color = Color(0xFF4CAF50)) }
                TextButton(onClick = { showManager = true }) { Text("[页管理器 (${pages.size})]", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
        // 当前页信息条（对齐 main 黄色左边框信息行）
        Text(
            "当前页：${currentIdx + 1}. ${page.title.ifBlank { "(untitled)" }} · ${page.author.ifBlank { "—" }} · ${page.date}" +
                if (page.locked) " · 🔒 已锁定" else "",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
        // 标题 / 类别
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = page.title, onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(title = p) } },
                label = { Text("页标题", fontSize = 11.sp) }, singleLine = true,
                readOnly = page.locked, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = page.category, onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(category = p) } },
                label = { Text("类别", fontSize = 11.sp) }, singleLine = true,
                readOnly = page.locked, modifier = Modifier.weight(1f)
            )
        }
        // Markdown 内容（main MilkdownEditor 的原生等价物：纯 Markdown 文本编辑）
        OutlinedTextField(
            value = page.content,
            onValueChange = { p -> updatePage(pages, currentIdx, onPageChange) { it.copy(content = p) } },
            label = { Text("Markdown 内容", fontSize = 11.sp) },
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
                    "为选区添加注释（${selection.first}..${selection.last} bp）",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNoteText, onValueChange = { newNoteText = it },
                        placeholder = { Text("注释内容…", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (newNoteText.isNotBlank()) {
                            onAddNote(newNoteText); newNoteText = ""
                        }
                    }) { Text("添加", fontSize = 12.sp) }
                }
            }
        }
        // 选区注释列表（点击跳转选区，可删除）
        Text("选区注释（${selectionNotes.size}）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                    Text("区间 ${n.start} - ${n.end} bp（${n.end - n.start + 1} bp）", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.Delete, contentDescription = "删除注释",
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
            title = { Text("页面管理器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("共 ${pages.size} 页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        pages.add(ElnPage(title = "新页 ${pages.size + 1}"))
                        onPageChange(pages, pages.size - 1)
                    }) { Text("+ 新建页", fontSize = 12.sp) }
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
            confirmButton = { TextButton(onClick = { showManager = false }) { Text("完成并关闭") } }
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
                Text("当前", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
            Icon(
                Icons.Filled.Delete, contentDescription = "删除页",
                modifier = Modifier.size(16.dp).clickable { onDelete(idx) },
                tint = MaterialTheme.colorScheme.error
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}
