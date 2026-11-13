package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.Feature
import kotlinx.coroutines.launch

// 对齐 main ove-tabs 标签条（+page.svelte:3319）：circular/linear/sequence/features/properties/notes
private enum class GeneView(val label: String) {
    CIRCULAR("环状图谱"), LINEAR("线性图谱"), SEQUENCE("序列视图"),
    FEATURES("特征列表"), PROPERTIES("质粒属性"), NOTES("科研备注")
}

@Composable
fun GeneScreen(
    record: SeqRecord,
    selectedEnzymes: Set<String>,
    onToggleEnzyme: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRecordChange: (SeqRecord) -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onSaveProject: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    aiOpen: Boolean = false,
    onAiOpenChange: (Boolean) -> Unit = {}
) {
    var view by remember { mutableStateOf(GeneView.CIRCULAR) }
    var showFeatures by remember { mutableStateOf(true) }   // 对齐 main showFeatures（图谱特征开关）
    var showCutsites by remember { mutableStateOf(true) }   // 对齐 main showCutsites（图谱切点开关）
    var selection by remember(record.name) { mutableStateOf<IntRange?>(null) }
    var menuOpen by remember { mutableStateOf<String?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<CloningPanel?>(null) }
    var importSource by remember { mutableStateOf<SeqFetch.Source?>(null) }
    var showAddFeature by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<CloningPlus.Hit>>(emptyList()) }
    var analysisPanel by remember { mutableStateOf<AnalysisPanel?>(null) }
    var windowPanel by remember { mutableStateOf<WindowPanel?>(null) }
    var sangerTrace by remember { mutableStateOf<com.spice.nativeapp.core.AbiReader.Trace?>(null) }
    var showSpdLib by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }            // 导出模态框（对齐 main showExportModal）
    var showMapSettings by remember { mutableStateOf(false) }       // 图谱浮动设置面板（对齐 main menuWinSettings 浮窗）
    var pendingExport by remember { mutableStateOf<String?>(null) } // 待写出的格式：spiceg/genbank/fasta/embl
    val scope = rememberCoroutineScope()
    var opLog by remember { mutableStateOf<List<String>>(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var undoStack by remember { mutableStateOf<List<SeqRecord>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<SeqRecord>>(emptyList()) }
    var dirty by remember { mutableStateOf(false) }          // 对齐 main 的未保存指示
    // ── 视图配置（对齐 main 布局 config localStorage 持久化，安卓侧用 SharedPreferences view_* 系列）──
    val gprefs = remember { ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE) }
    var singleStrand by remember { mutableStateOf(gprefs.getBoolean("view_single_strand", false)) }   // 对齐 main 的单链紧凑/双链模式（persist showSingleStrand）
    var colorTheme by remember { mutableStateOf(BaseColorTheme.fromName(gprefs.getString("view_color_theme", null))) }   // 碱基配色（main colorTheme 默认 monochrome）
    var showTranslations by remember { mutableStateOf(gprefs.getBoolean("view_translations", true)) }   // 翻译行（main showTranslations 默认 true）
    var showOrfs by remember { mutableStateOf(gprefs.getBoolean("view_orfs", false)) }                   // ORF 开关（main 仅计算不渲染，1:1 复刻持久化）
    var showPrimerToggle by remember { mutableStateOf(gprefs.getBoolean("view_primers_map", true)) }    // 引物显示开关（main 仅持久化，图谱始终绘制）
    // ELN 笔记页 + 选区注释（对齐 main notebookPages / selectionNotes）
    var elnPages by remember { mutableStateOf(ElnStore.load(ctx)) }
    var elnIdx by remember(elnPages.size) { mutableStateOf(ElnStore.currentIndex(ctx, elnPages.size)) }
    var selectionNotes by remember { mutableStateOf<List<SelNote>>(emptyList()) }
    var editingFeature by remember { mutableStateOf<Feature?>(null) }

    fun applyRecord(next: SeqRecord) {
        opLog = (opLog + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date()) +
            "  ${record.name} → ${next.name}（${next.length} bp）").takeLast(50)
        undoStack = (undoStack + record).takeLast(20)
        redoStack = emptyList()
        dirty = true
        onRecordChange(next)
    }
    fun undo() {
        undoStack.lastOrNull()?.let {
            redoStack = redoStack + record
            undoStack = undoStack.dropLast(1)
            onRecordChange(it)
        }
    }
    fun redo() {
        redoStack.lastOrNull()?.let {
            undoStack = undoStack + record
            redoStack = redoStack.dropLast(1)
            onRecordChange(it)
        }
    }

    // 测序色谱导入（.ab1）
    val sangerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val trace = com.spice.nativeapp.core.AbiReader.parse(bytes)
                if (trace != null && trace.bases.length > 20) {
                    sangerTrace = trace
                    opLog = opLog + "导入 Sanger 色谱 ${trace.bases.length} nt（${if (trace.hasPeaks) "含四色峰图" else "无峰数据"}）"
                }
            }
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // 打开序列文件（.spiceg / .gb / .fasta / .embl / fastq）
    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                if (com.spice.nativeapp.core.Spiceg.isSpiceg(text)) {
                    // main handleGeneFile 的 .spiceg 分支：还原序列/特征并恢复所选酶组
                    com.spice.nativeapp.core.Spiceg.parseFull(text)?.let { p ->
                        applyRecord(p.record)
                        p.enzymes.forEach { e -> if (e !in selectedEnzymes) onToggleEnzyme(e) }
                    } ?: onRecordChange(com.spice.nativeapp.core.SeqIO.parse(text) ?: record)
                } else {
                    com.spice.nativeapp.core.SeqIO.parse(text)?.let { onRecordChange(it) }
                }
            }
        }
    }
    // 导出当前序列（对齐 main exportSequence：spiceg / genbank / fasta / embl，按 pendingExport 写出）
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val fmt = pendingExport
        if (uri != null && fmt != null) {
            runCatching {
                val text = when (fmt) {
                    "spiceg" -> com.spice.nativeapp.core.Spiceg.write(
                        record, "ECOLI", selectedEnzymes.toList(),
                        notes = elnPages.getOrNull(elnIdx)?.content ?: "",
                        notebookPages = org.json.JSONArray().also { arr ->
                            elnPages.forEach { pg ->
                                arr.put(
                                    org.json.JSONObject()
                                        .put("id", pg.id).put("title", pg.title).put("category", pg.category)
                                        .put("date", pg.date).put("author", pg.author)
                                        .put("content", pg.content).put("locked", pg.locked)
                                )
                            }
                        },
                        selectionNotes = org.json.JSONArray().also { arr ->
                            selectionNotes.forEach { n ->
                                arr.put(org.json.JSONObject().put("text", n.text).put("start", n.start).put("end", n.end))
                            }
                        }
                    )
                    "genbank" -> com.spice.nativeapp.core.SeqIO.toGenBank(record)
                    "fasta" -> com.spice.nativeapp.core.SeqIO.toFasta(record)
                    "embl" -> com.spice.nativeapp.core.SeqIO.toEmbl(record)
                    else -> com.spice.nativeapp.core.SeqIO.toGenBank(record)
                }
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
        pendingExport = null
    }

    fun launchExport(fmt: String) {
        pendingExport = fmt
        // 扩展名与 main exportSequence 一致：spiceg / gb / fa / embl
        val ext = when (fmt) {
            "spiceg" -> "spiceg"; "genbank" -> "gb"; "fasta" -> "fa"; "embl" -> "embl"; else -> "txt"
        }
        exportLauncher.launch("${record.name}.$ext")
    }

    fun selectionText(): String = selection?.let { record.sequence.substring(it.first - 1, it.last) } ?: ""

    val hits = remember(record.sequence, selectedEnzymes, record.circular) {
        Enzymes.digest(record.sequence, selectedEnzymes, record.circular)
    }

    // 根容器：浮动窗口叠加层（对齐 main 的 floating-panel 体系：悬浮于页面之上、可拖拽）
    Box(modifier.fillMaxSize()) {

    // 紧凑/矮屏时整页滚动并给图谱保底高度，避免图谱被压成一条细带
    BoxWithConstraints(modifier.fillMaxSize()) {
    val shortScreen = maxHeight < 620.dp
    Column(
        Modifier.fillMaxSize().then(
            if (shortScreen) Modifier.verticalScroll(rememberScrollState()) else Modifier
        )
    ) {
        // 原版 retro 菜单栏：文件 / 编辑 / 分子克隆工具 / 分析与多视图 / 窗口与设置
        // （条目、顺序、分组与启用条件和 main 分支 gene/+page.svelte 的菜单树 1:1 对齐）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "file") null else "file" }) { Text("文件", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "file", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(text = { Text("打开序列文件…", fontSize = 13.sp) }, onClick = { menuOpen = null; openLauncher.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("从 Ensembl 数据库导入…", fontSize = 13.sp) }, onClick = { menuOpen = null; importSource = SeqFetch.Source.ENSEMBL })
                    DropdownMenuItem(text = { Text("从 NCBI 数据库导入…", fontSize = 13.sp) }, onClick = { menuOpen = null; importSource = SeqFetch.Source.NCBI })
                    DropdownMenuItem(text = { Text("从 UniProt 数据库导入…", fontSize = 13.sp) }, onClick = { menuOpen = null; importSource = SeqFetch.Source.UNIPROT })
                    DropdownMenuItem(text = { Text("SPD 基因库 / 载体库…", fontSize = 13.sp) }, onClick = { menuOpen = null; showSpdLib = true })
                    DropdownMenuItem(text = { Text("项目集合管理…", fontSize = 13.sp) }, onClick = { menuOpen = null; onOpenProjects() })
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(text = { Text("保存项目", fontSize = 13.sp) }, onClick = { menuOpen = null; onSaveProject(); dirty = false })
                    DropdownMenuItem(text = { Text("导出当前序列…", fontSize = 13.sp) }, onClick = { menuOpen = null; showExport = true })
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text("导入测序色谱 (Sanger .ab1)…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; sangerLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "edit") null else "edit" }) { Text("编辑", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "edit", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("撤销", fontSize = 13.sp) },
                        enabled = undoStack.isNotEmpty(),
                        onClick = { menuOpen = null; undo(); dirty = true }
                    )
                    DropdownMenuItem(
                        text = { Text("重做", fontSize = 13.sp) },
                        enabled = redoStack.isNotEmpty(),
                        onClick = { menuOpen = null; redo(); dirty = true }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text("切换拓扑：${if (record.circular) "线性" else "环状"}", fontSize = 13.sp) },
                        onClick = { menuOpen = null; applyRecord(record.copy(circular = !record.circular)); selection = null }
                    )
                    DropdownMenuItem(text = { Text("反向互补序列", fontSize = 13.sp) }, onClick = {
                        menuOpen = null
                        applyRecord(record.copy(sequence = Bio.revComp(record.sequence), name = "${record.name}_rc"))
                        selection = null
                    })
                    DropdownMenuItem(
                        text = { Text(if (selection == null) "清除选择区" else "清除选择区（${selection!!.first}..${selection!!.last}）", fontSize = 13.sp) },
                        enabled = selection != null,
                        onClick = { menuOpen = null; selection = null }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(text = { Text("添加特征注释…", fontSize = 13.sp) }, onClick = { menuOpen = null; showAddFeature = true })
                    DropdownMenuItem(text = { Text("自动注释特征", fontSize = 13.sp) }, onClick = {
                        menuOpen = null
                        val auto = CloningPlus.autoAnnotate(record.sequence)
                        if (auto.isNotEmpty()) {
                            val feats = record.features + auto.map { (n, r, t) -> Feature(n, t, r.first, r.last, 1) }
                            applyRecord(record.copy(features = feats))
                        }
                    })
                    DropdownMenuItem(text = { Text("序列搜索", fontSize = 13.sp) }, onClick = { menuOpen = null; showSearch = true })
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "cloning") null else "cloning" }) { Text("分子克隆工具", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "cloning", onDismissRequest = { menuOpen = null }) {
                    // 分组与顺序对齐 main：酶/密码子/引物/PCR/凝胶 → 设计舱/表达盒/载体/CRISPR → 计算器/LIMS → QC/NGS/重组/协同设计 → 寡核苷酸工具组
                    val groups = listOf(
                        listOf(CloningPanel.ENZYMES, CloningPanel.CODON, CloningPanel.PRIMERS, CloningPanel.PCR, CloningPanel.GEL),
                        listOf(CloningPanel.DESIGN, CloningPanel.CASSETTE, CloningPanel.VECTOR, CloningPanel.CRISPR),
                        listOf(CloningPanel.CALC, CloningPanel.LIMS),
                        listOf(CloningPanel.QC, CloningPanel.NGS, CloningPanel.EXTENSION, CloningPanel.CODESIGN),
                        listOf(CloningPanel.ANNEAL, CloningPanel.SILENT, CloningPanel.REVTRANS, CloningPanel.PRIMERMOD, CloningPanel.ENDMOD, CloningPanel.FUSION, CloningPanel.SLIPPAGE),
                        // native 版扩展：批量筛选与批处理（main 挂在项目集合面板内）
                        listOf(CloningPanel.BATCH)
                    )
                    groups.forEachIndexed { gi, group ->
                        if (gi > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        group.forEach { cp ->
                            DropdownMenuItem(
                                text = { Text(cp.title, fontSize = 13.sp) },
                                onClick = { menuOpen = null; panel = cp }
                            )
                        }
                    }
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "analysis") null else "analysis" }) { Text("分析与多视图", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "analysis", onDismissRequest = { menuOpen = null }) {
                    AnalysisPanel.values().forEach { ap ->
                        DropdownMenuItem(
                            text = { Text(ap.title, fontSize = 13.sp) },
                            onClick = { menuOpen = null; analysisPanel = ap }
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "window") null else "window" }) { Text("窗口与设置", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "window", onDismissRequest = { menuOpen = null }) {
                    WindowPanel.values().forEach { wp ->
                        DropdownMenuItem(
                            text = { Text(wp.title, fontSize = 13.sp) },
                            onClick = { menuOpen = null; windowPanel = wp }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DropdownMenuItem(
                        text = { Text(if (singleStrand) "切换为：双链模式" else "切换为：单链紧凑模式", fontSize = 13.sp) },
                        onClick = { menuOpen = null; singleStrand = !singleStrand; gprefs.edit().putBoolean("view_single_strand", singleStrand).apply() }
                    )
                    DropdownMenuItem(
                        text = { Text("碱基配色方案：${MapPalette.theme.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                        enabled = false,
                        onClick = {}
                    )
                    MapTheme.values().forEach { t ->
                        DropdownMenuItem(
                            text = { Text((if (t == MapPalette.theme) "✓ " else "　") + t.label, fontSize = 13.sp) },
                            onClick = { menuOpen = null; MapPalette.theme = t }
                        )
                    }
                }
            }
            // 未保存/已保存指示（对齐 main 菜单栏右侧的 LED 指示）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp)
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Text(
                    if (dirty) "未保存的更改" else "项目已保存",
                    fontSize = 10.sp,
                    color = if (dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }
            Text(
                "${record.length} bp",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp)
            )
        }

        if (searchHits.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "命中 ${searchHits.size} 处：" + searchHits.take(4).joinToString(", ") { "${it.start}(${if (it.strand > 0) "+" else "-"})" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { searchHits = emptyList(); selection = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("关闭", fontSize = 11.sp)
                }
            }
        }

        // 视图切换（窄屏横滑，避免 chip 被压缩到文字折行）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectMode,
                onClick = { selectMode = !selectMode },
                label = { Text(if (selectMode) "选择中" else "选择", fontSize = 13.sp, maxLines = 1) }
            )
            GeneView.values().forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { view = v },
                    label = { Text(v.label, fontSize = 13.sp, maxLines = 1) }
                )
            }
        }

        // ── 主分屏（对齐 main ove-main +page.svelte:3328-3656）：左 pane 按 tab 切换，
        //    右侧序列视图常驻（共享 selection 联动）；仅"序列视图"tab 时隐藏左 pane、序列全宽 ──
        val seqOnly = view == GeneView.SEQUENCE
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (shortScreen) Modifier.height(340.dp) else Modifier.weight(1f))
                .padding(horizontal = 12.dp)
        ) {
        // 左 pane（对齐 main ove-left：circular/linear/features/properties/notes）
        Box(Modifier.then(if (seqOnly) Modifier.width(0.dp) else Modifier.fillMaxWidth(0.52f))) {
        Box(Modifier.fillMaxSize()) {
            // 选区浮动窗口（对应原版 SelectionToolkit 悬浮条）
            val sel = selection
            if (sel != null && sel.last > sel.first) {
                val seqSel = record.sequence.substring(sel.first - 1, sel.last)
                val gcPct = com.spice.nativeapp.core.Bio.gcContent(seqSel) // 百分数
                val tm = if (seqSel.length <= 14)
                    (2 * (seqSel.count { it == 'A' || it == 'T' || it == 'a' || it == 't' }) +
                        4 * (seqSel.count { it == 'G' || it == 'C' || it == 'g' || it == 'c' })).toDouble()
                else 64.9 + 41.0 * ((gcPct / 100.0 * seqSel.length) - 16.4) / seqSel.length
                ElevatedCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .padding(8.dp)
                        .fillMaxWidth(0.97f),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            "${sel.first}..${sel.last} · ${seqSel.length} bp",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            "GC ${"%.1f".format(gcPct)}% · Tm ${"%.0f".format(tm)}℃",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { clipboard.setText(AnnotatedString(seqSel)) }) { Text("复制", fontSize = 12.sp) }
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                val newSeq = record.sequence.replaceRange(sel.first - 1, sel.last, com.spice.nativeapp.core.Bio.revComp(seqSel))
                                onRecordChange(record.copy(sequence = newSeq))
                                selection = null
                            }) { Text("RevComp", fontSize = 12.sp) }
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                val newSeq = record.sequence.removeRange(sel.first - 1, sel.last)
                                onRecordChange(record.copy(sequence = newSeq))
                                selection = null
                            }) { Text("删除", fontSize = 12.sp) }
                            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { selection = null }) { Text("取消", fontSize = 12.sp) }
                        }
                    }
                }
            }
            // ── 浮动特征卡（对齐 main PlasmidMap.svelte:241 plasmid-features-tooltip）──
            if (view == GeneView.CIRCULAR && record.features.isNotEmpty()) {
                var expandedFeature by remember(record.name) { mutableStateOf<Feature?>(null) }
                FloatingPanel(
                    title = "质粒特征 ✥ ${record.features.size}",
                    onDismiss = {},           // main 特征卡无关闭按钮
                    hideDismiss = true,
                    initialX = 4.dp,
                    initialY = 8.dp,
                    width = 220.dp,
                    maxHeight = 250.dp
                ) {
                    record.features.forEach { feat ->
                        val expanded = expandedFeature == feat
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                expandedFeature = if (expanded) null else feat
                                if (!expanded) selection = feat.start..feat.end
                            },
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(feat.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(feat.type.uppercase(), fontSize = 8.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (expanded) {
                                HorizontalDivider()
                                Text("${feat.start} - ${feat.end} bp", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("大小: ${feat.end - feat.start + 1} bp", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("链方向: ${if (feat.strand > 0) "正向" else "反向"}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            when (view) {
                // 序列视图 tab：左 pane 宽度已为 0（main: Sequence tab 隐藏左 pane），无内容渲染
                GeneView.SEQUENCE -> {}
                GeneView.CIRCULAR -> CircularMap(
                    record = record,
                    enzymeNames = if (showCutsites) selectedEnzymes else emptySet(),
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it },
                    selectionNotes = selectionNotes
                )
                GeneView.LINEAR -> LinearMap(
                    record = record,
                    enzymeNames = if (showCutsites) selectedEnzymes else emptySet(),
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it },
                    selectionNotes = selectionNotes,
                    // 对齐 main linearizePlasmid：切点旋转为第 1 位碱基 + 清空选区 + 特征坐标同步平移
                    onLinearizeAt = { pos ->
                        if (pos in 2..record.length) {
                            applyRecord(record.copy(sequence = Bio.rotateTo(record.sequence, pos - 1), circular = false))
                            selection = null
                        }
                    }
                )
                // 左区 3 视图（对齐 main ove-left 按 activeTab 切换：features/properties/notes）
                GeneView.FEATURES -> FeaturesView(
                    record = record,
                    onSelect = { selection = it },
                    onEdit = { editingFeature = it; showAddFeature = true },
                    onDelete = { f -> applyRecord(record.copy(features = record.features - f)) }
                )
                GeneView.PROPERTIES -> PropertiesView(record = record, onChange = { applyRecord(it) })
                GeneView.NOTES -> NotesView(
                    record = record,
                    selection = selection,
                    pages = elnPages,
                    currentIdx = elnIdx,
                    selectionNotes = selectionNotes,
                    onPageChange = { list, idx ->
                        elnPages = list.toMutableList(); elnIdx = idx
                        ElnStore.save(ctx, elnPages, idx)
                    },
                    onAddNote = { text ->
                        selection?.let { sel -> selectionNotes = selectionNotes + SelNote(text, sel.first, sel.last) }
                    },
                    onDeleteNote = { i -> selectionNotes = selectionNotes.filterIndexed { j, _ -> j != i } },
                    onSelectNote = { selection = it },
                    onSavePage = {
                        ElnStore.save(ctx, elnPages, elnIdx)
                        onSaveProject()
                        dirty = false
                    }
                )
            }
        }
        }
        // 右 pane（对齐 main ove-right: DnaViewer 常驻）：任何 tab 下序列视图都在右侧，
        // 与左 pane 图谱共享 selection —— 在图谱上拖选即在序列中看到同一段高亮（联动）
        Box(Modifier.then(if (seqOnly) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.48f))) {
            SequenceView(record, hits, singleStrand, colorTheme, showTranslations, Modifier.fillMaxSize())
        }
        }

        // 概要
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(record.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "${record.length} bp · GC ${"%.1f".format(record.gc)}% · ${hits.size} 切点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // 酶选择
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Enzymes.common.sorted().forEach { name ->
                FilterChip(
                    selected = selectedEnzymes.contains(name),
                    onClick = { onToggleEnzyme(name) },
                    label = { Text(name, fontSize = 12.sp) }
                )
            }
            val e = Enzymes.byName("BsaI")!!
            AssistChip(
                onClick = { onToggleEnzyme(e.name) },
                label = { Text("BsaI（Golden Gate）", fontSize = 12.sp) }
            )
        }

    }
        // 图谱浮动设置窗入口（对齐 main 右上角 menuWinSettings 浮动面板）
        FloatingActionButton(
            onClick = { showMapSettings = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 76.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) { Text("⚙", fontSize = 16.sp) }
    }
        // 浮动窗口叠加层：晚于主内容绘制，保证悬浮在图谱/页面上方（对齐 main z-index 语义）
    // 文件菜单的数据库导入对话框
        importSource?.let { src ->
            SeqImportDialog(
                source = src,
                onDismiss = { importSource = null },
                onSubmit = { acc ->
                    importSource = null
                    scope.launch {
                        val rec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            SeqFetch.fetch(src, acc)
                        }
                        rec?.let { applyRecord(it) }
                    }
                }
            )
        }
        if (showAddFeature) {
            AddFeatureDialog(
                record = record,
                onDismiss = { showAddFeature = false; editingFeature = null },
                onAdd = { name, range, type ->
                    showAddFeature = false
                    if (editingFeature != null) {
                        // 编辑模式：原位替换（保留链方向）
                        applyRecord(record.copy(features = record.features.map {
                            if (it === editingFeature) Feature(name, type, range.first, range.last, it.strand) else it
                        }))
                        editingFeature = null
                    } else {
                        applyRecord(record.copy(features = record.features + Feature(name, type, range.first, range.last, 1)))
                    }
                },
                initial = editingFeature
            )
        }
        if (showSearch) {
            SequenceSearchDialog(
                onDismiss = { showSearch = false },
                onSearch = { q ->
                    showSearch = false
                    searchHits = CloningPlus.search(record.sequence, q)
                    searchHits.firstOrNull()?.let { selection = it.start..(it.start + q.length - 1) }
                }
            )
        }
        if (showSpdLib) {
            SpdLibrarySheet(
                record = record,
                onDismiss = { showSpdLib = false },
                onLoad = { rec -> showSpdLib = false; applyRecord(rec) }
            )
        }
        if (showMapSettings) {
            // 对齐 main gene 浮动设置面板（+page.svelte:4487：topology/显示特征/显示切点，可拖拽、[X] 关闭）
            FloatingPanel(
                title = "设置",
                onDismiss = { showMapSettings = false },
                initialX = 220.dp,
                initialY = 64.dp,
                width = 260.dp,
                maxHeight = 320.dp
            ) {
                Text("拓扑结构:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = record.circular, onClick = { if (!record.circular) applyRecord(record.copy(circular = true)) }, label = { Text("环状", fontSize = 11.sp) })
                    FilterChip(selected = !record.circular, onClick = { if (record.circular) applyRecord(record.copy(circular = false)) }, label = { Text("线性", fontSize = 11.sp) })
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showFeatures, onCheckedChange = { showFeatures = it; gprefs.edit().putBoolean("view_features", it).apply() })
                    Text("显示特征", fontSize = 12.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showCutsites, onCheckedChange = { showCutsites = it; gprefs.edit().putBoolean("view_cutsites", it).apply() })
                    Text("显示切点", fontSize = 12.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = singleStrand, onCheckedChange = { singleStrand = it; gprefs.edit().putBoolean("view_single_strand", it).apply() })
                    Text("单链紧凑模式", fontSize = 12.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showTranslations, onCheckedChange = { showTranslations = it; gprefs.edit().putBoolean("view_translations", it).apply() })
                    Text("翻译行", fontSize = 12.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showOrfs, onCheckedChange = { showOrfs = it; gprefs.edit().putBoolean("view_orfs", it).apply() })
                    Text("ORF（对齐 main：仅计算开关，不渲染）", fontSize = 12.sp)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = showPrimerToggle, onCheckedChange = { showPrimerToggle = it; gprefs.edit().putBoolean("view_primers_map", it).apply() })
                    Text("引物显示（对齐 main：图谱常画）", fontSize = 12.sp)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("碱基配色:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 7 主题下拉（对齐 main 视图菜单 colorTheme 7 项，默认 monochrome）
                var themeMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { themeMenu = true }) { Text(colorTheme.label, fontSize = 11.sp) }
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        BaseColorTheme.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label, fontSize = 12.sp) },
                                onClick = {
                                    colorTheme = t; themeMenu = false
                                    gprefs.edit().putString("view_color_theme", t.name).apply()
                                }
                            )
                        }
                    }
                }
            }
        }
        if (aiOpen) {
            AiCopilotSheet(
                workspace = AiWorkspace.GENE,
                sequence = record.sequence,
                sequenceLabel = "质粒 ${record.name} DNA 序列",
                onImportDna = { dna ->
                    onAiOpenChange(false)
                    applyRecord(record.copy(sequence = dna))
                },
                onDismiss = { onAiOpenChange(false) }
            )
        }
        // 导出模态框（对齐 main showExportModal：spiceg / GenBank / FASTA / EMBL / 保存工程文件）
        if (showExport) {
            AlertDialog(
                onDismissRequest = { showExport = false },
                confirmButton = {
                    TextButton(onClick = { showExport = false }) { Text("完成并关闭", fontSize = 12.sp) }
                },
                title = { Text("导出当前序列...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showExport = false; launchExport("spiceg") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("SPICE 基因 (.spiceg)", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { showExport = false; launchExport("genbank") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("GenBank (.gb)", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { showExport = false; launchExport("fasta") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("FASTA (.fasta)", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { showExport = false; launchExport("embl") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("EMBL (.embl)", fontSize = 12.sp) }
                        Button(
                            onClick = { showExport = false; onSaveProject(); dirty = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("保存工程文件", fontSize = 12.sp) }
                    }
                }
            )
        }
        analysisPanel?.let { ap ->
            AnalysisPanelSheet(
                panel = ap,
                record = record,
                trace = sangerTrace,
                onDismiss = { analysisPanel = null },
                onRecordChange = { next -> applyRecord(next) }
            )
        }
        windowPanel?.let { wp ->
            WindowPanelSheet(
                panel = wp,
                undoStack = undoStack,
                log = opLog,
                onDismiss = { windowPanel = null },
                onRestore = { rec -> onRecordChange(rec); undoStack = emptyList() },
                onOpenSettings = { windowPanel = null; onOpenSettings() },
                onResetLayout = {
                    windowPanel = null
                    selectMode = false
                    selection = null
                    searchHits = emptyList()
                    view = GeneView.CIRCULAR
                }
            )
        }
        panel?.let { cp ->
            CloningPanelSheet(
                panel = cp,
                record = record,
                onDismiss = { panel = null },
                onRecordChange = { next -> applyRecord(next) }
            )
        }
    }
}

@Composable
/**
 * 序列视图（对齐 main DnaViewer/rowRender.ts 的 OVE 式切点渲染）：
 * 酶名标签（防碰撞分层 + 连接线）、上/下链各一道竖直切痕、
 * 上下切痕之间的水平连接线（直观呈现粘性末端方向与长度）。
 */
private fun SequenceView(
    record: SeqRecord,
    sites: List<Enzymes.Hit>,
    singleStrand: Boolean,
    colorTheme: BaseColorTheme,
    showTranslations: Boolean,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    // 序列画布固定深底（对齐 main DnaViewer：亮色碱基/酶标画深底，浅色主题下也保持对比）
    val prefixStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        color = Color(0xFFA9BACD)
    )
    val baseStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)

    val seq = record.sequence
    // 全部切点 → OVE 式 top/bottom 0-based 全局切位（对齐 main topSnipPosition/bottomSnipPosition）
    val rowSitesGlobal = remember(sites, seq) {
        sites.map { h ->
            val e = Enzymes.byName(h.enzyme)
            val top0 = h.pos - 1
            val bot0 = if (e != null && e.bottomCut >= 0) {
                val b = (top0 - e.cutOffset + e.bottomCut) % seq.length
                if (b < 0) b + seq.length else b
            } else top0
            RowSite(h.enzyme, enzymeColor(h.enzyme), top0, bot0)
        }
    }

    LazyColumn(modifier.background(Color(0xFF0B0F17)).padding(8.dp)) {
        itemsIndexed((0 until (seq.length + 59) / 60).map { it * 60 }) { _, start ->
            val rowStart = start + 1
            val row = seq.substring(start, minOf(start + 60, seq.length))
            val inRow = rowSitesGlobal.filter {
                it.top0 in start until (start + 60) || it.bot0 in start until (start + 60)
            }
            SequenceRowCanvas(measurer, prefixStyle, baseStyle, labelStyle, rowStart, row, inRow, singleStrand, colorTheme, showTranslations, record)
        }
    }
}

/** 行内切点：酶名 + 颜色 + 上/下链 0-based 全局切位 */
private data class RowSite(val name: String, val color: Color, val top0: Int, val bot0: Int)

/** 对齐 main getEnzymeColor：hash = Σ charCode + ((hash shl 5) − hash)（32 位回绕），10 色环 */
/** 单行（60 bp）Canvas 渲染：标签区 + 正链（+ 互补链），切痕与连接线按像素定位 */
private data class RowLbl(
    val site: RowSite,
    val layout: androidx.compose.ui.text.TextLayoutResult,
    val xTop: Float, val xc: Float, val w: Float, val level: Int
)

private data class RowAa(val i: Int, val layout: androidx.compose.ui.text.TextLayoutResult)

/** 行级缓存：全部 TextMeasurer 测量结果与防碰撞分层，仅随行内容变化重建 */
private data class RowCache(
    val charW: Float, val strandH: Float, val prefixW: Float,
    val prefixLayout: androidx.compose.ui.text.TextLayoutResult,
    val labelArea: Float, val labels: List<RowLbl>,
    val baseLayouts: Map<Char, androidx.compose.ui.text.TextLayoutResult>,
    val codons: List<RowAa>
)

@Composable
private fun SequenceRowCanvas(
    measurer: androidx.compose.ui.text.TextMeasurer,
    prefixStyle: androidx.compose.ui.text.TextStyle,
    baseStyle: androidx.compose.ui.text.TextStyle,
    labelStyle: androidx.compose.ui.text.TextStyle,
    rowStart: Int,
    row: String,
    sites: List<RowSite>,
    singleStrand: Boolean,
    colorTheme: BaseColorTheme,
    showTranslations: Boolean,
    record: SeqRecord
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val prefix = "%5d ".format(rowStart)
    val labelH = with(density) { 13.dp.toPx() }
    val labelGap = with(density) { 2.dp.toPx() }
    val strandGap = with(density) { 4.dp.toPx() }
    val transH = if (showTranslations) with(density) { 16.dp.toPx() } else 0f

    // ── 测量/防碰撞只在行内容变化时计算一次（对齐 main rowRender 预计算布局；
    //    1.7.9 在 draw lambda 内每帧重测 ~150 次，慢设备主线程过载导致闪退/ANR）──
    val cached = remember(measurer, prefixStyle, baseStyle, labelStyle, rowStart, row, sites, singleStrand, showTranslations) {
        val charW = measurer.measure("A", baseStyle).size.width.toFloat()
        val strandH = measurer.measure("A", baseStyle).size.height.toFloat()
        val prefixLayout = measurer.measure(prefix, prefixStyle)
        val prefixW = prefixLayout.size.width.toFloat()
        val rowBase0 = rowStart - 1

        // 酶名标签防碰撞分层（对齐 main calculateLabelYOffsets 的贪心堆叠）
        data class Lbl0(val s: RowSite, val l: androidx.compose.ui.text.TextLayoutResult, val xTop: Float, val xc: Float, val w: Float, var level: Int = 0)
        val ls = sites.map { s ->
            val xTop = prefixW + (s.top0 - rowBase0) * charW
            val tw = measurer.measure(s.name, labelStyle).size.width.toFloat() + 4f
            val xc = ((xTop - tw / 2).coerceAtLeast(prefixW)) + tw / 2
            Lbl0(s, measurer.measure(s.name, labelStyle), xTop, xc, tw)
        }.sortedBy { it.xc }
        val levelSpans = mutableListOf<MutableList<Pair<Float, Float>>>()
        for (l in ls) {
            var lvl = 0
            while (true) {
                if (lvl >= levelSpans.size) { levelSpans.add(mutableListOf()); break }
                val overlap = levelSpans[lvl].any { it.first < l.xc + l.w / 2 && it.second > l.xc - l.w / 2 }
                if (!overlap) break
                lvl++
            }
            levelSpans[lvl].add(l.xc - l.w / 2 to l.xc + l.w / 2)
            l.level = lvl
        }
        val labels = ls.map { RowLbl(it.s, it.l, it.xTop, it.xc, it.w, it.level) }
        val labelLevels = ls.maxOfOrNull { it.level + 1 } ?: 0
        val labelArea = if (labelLevels > 0) labelLevels * (labelH + labelGap) else 0f

        // 每碱基布局缓存（一行内只出现有限几种字符，正/互补链同缓存）
        val baseLayouts = HashMap<Char, androidx.compose.ui.text.TextLayoutResult>()
        fun put(c: Char) { baseLayouts.getOrPut(c) { measurer.measure(c.toString(), baseStyle) } }
        row.forEach { put(it) }
        if (!singleStrand) row.forEach { put(Bio.complement(it.toString()).firstOrNull() ?: 'N') }

        // 翻译行布局（对齐 main rowRender.ts:843 §5）
        val aaStyle = baseStyle.copy(fontSize = 10.sp, color = Color(0xFFAAAAAA))
        val codons = if (showTranslations) {
            (0 until row.length).mapNotNull { i ->
                val globalPos = rowStart + i          // 1-based
                if ((globalPos - 1) % 3 == 0 && i + 2 < row.length)
                    RowAa(i, measurer.measure(Bio.translateCodon(row.substring(i, i + 3)).toString(), aaStyle))
                else null
            }
        } else emptyList()

        RowCache(charW, strandH, prefixW, prefixLayout, labelArea, labels, baseLayouts, codons)
    }
    val heightPx = cached.labelArea + cached.strandH + (if (!singleStrand) strandGap + cached.strandH else 0f) + transH + with(density) { 4.dp.toPx() }

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .padding(vertical = 1.dp)
    ) {
        val topY = cached.labelArea
        val botY = if (!singleStrand) cached.labelArea + cached.strandH + strandGap else topY

        // ── 酶名标签 + 连接线（对齐 main rowRender §2）──
        cached.labels.forEach { l ->
            val labelBottomY = cached.labelArea - l.level * (labelH + labelGap)
            drawText(l.layout, color = l.site.color, topLeft = Offset(l.xc - l.w / 2, labelBottomY - labelH))
            drawLine(l.site.color.copy(alpha = 0.6f), Offset(l.xc, labelBottomY + 1f), Offset(l.xTop, topY - 2f), 1f)
        }

        // ── 碱基（正链，按 colorTheme 主题着色，对齐 main rowRender.ts:691）──
        drawText(cached.prefixLayout, topLeft = Offset(0f, topY))
        row.forEachIndexed { i, c ->
            drawText(cached.baseLayouts.getValue(c), color = baseColorFor(colorTheme, c), topLeft = Offset(cached.prefixW + i * cached.charW, topY))
        }
        // ── 互补链（逐列对齐）──
        if (!singleStrand) {
            drawText(cached.prefixLayout, topLeft = Offset(0f, botY))
            row.forEachIndexed { i, c ->
                val rc = Bio.complement(c.toString()).firstOrNull() ?: 'N'
                drawText(cached.baseLayouts.getValue(rc), color = baseColorFor(colorTheme, rc), topLeft = Offset(cached.prefixW + i * cached.charW, botY))
            }
        }

        // ── 翻译行：三联体圆角框 + AA 字母，粉色调 ──
        if (showTranslations) {
            val aaY = if (!singleStrand) botY + cached.strandH + 2.dp.toPx() else topY + cached.strandH + 2.dp.toPx()
            val boxH = 13.dp.toPx()
            cached.codons.forEach { c ->
                val x0 = cached.prefixW + c.i * cached.charW
                val x1 = x0 + cached.charW * 3
                drawRoundRect(
                    color = Color(0x33DB2777),   // rgba(219,39,119,0.2)
                    topLeft = Offset(x0 + 1f, aaY),
                    size = androidx.compose.ui.geometry.Size(cached.charW * 3 - 1f, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = Color(0x80DB2777),   // rgba(219,39,119,0.5) 描边
                    topLeft = Offset(x0 + 1f, aaY),
                    size = androidx.compose.ui.geometry.Size(cached.charW * 3 - 1f, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5.dp.toPx())
                )
                drawText(c.layout, topLeft = Offset((x0 + x1) / 2f - c.layout.size.width / 2f, aaY + boxH / 2f - c.layout.size.height / 2f))
            }
        }

        // ── OVE 式切痕：上/下链各一道竖线 + 粘性末端连接线（对齐 main rowRender §4b）──
        sites.forEach { s ->
            val xTop = cached.prefixW + (s.top0 - (rowStart - 1)) * cached.charW
            val xBot = cached.prefixW + (s.bot0 - (rowStart - 1)) * cached.charW
            // 上链切痕
            drawLine(s.color, Offset(xTop, topY - 2f), Offset(xTop, topY + cached.strandH + 2f), 1.5f)
            // 下链切痕
            if (!singleStrand) {
                drawLine(s.color, Offset(xBot, botY - 2f), Offset(xBot, botY + cached.strandH + 2f), 1.5f)
                // 连接线：位于两链之间，呈现粘性末端（错开时可见方向与长度）
                if (xBot != xTop) {
                    val midY = (topY + cached.strandH + botY) / 2f
                    val xL = minOf(xTop, xBot); val xR = maxOf(xTop, xBot)
                    drawLine(s.color.copy(alpha = 0.5f), Offset(xL, midY), Offset(xR, midY), 1.5f)
                }
            } else if (xBot != xTop) {
                // 单链显示：连接线画在链下方，仍保留粘性末端信息
                drawLine(s.color.copy(alpha = 0.5f), Offset(minOf(xTop, xBot), topY + cached.strandH + 4f), Offset(maxOf(xTop, xBot), topY + cached.strandH + 4f), 1.5f)
            }
        }
    }
}
