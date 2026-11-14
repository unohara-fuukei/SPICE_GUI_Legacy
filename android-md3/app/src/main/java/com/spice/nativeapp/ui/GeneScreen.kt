package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
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
import com.spice.nativeapp.core.Part
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
    var showTranslations by remember { mutableStateOf(gprefs.getBoolean("view_translations", true)) }
    var seqTool by remember { mutableStateOf("browse") }      // §4.1 浏览/编辑/注释模式（main seqTool）
    var showHybridization by remember { mutableStateOf(false) }  // §4.1 PCR 引物退火杂交配置   // 翻译行（main showTranslations 默认 true）
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
    val narrowScreen = maxWidth < 620.dp   // 竖屏窄幅：分屏改上下堆叠
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
                TextButton(onClick = { menuOpen = if (menuOpen == "file") null else "file" }) { Text(com.spice.nativeapp.core.I18n.t("文件"), fontSize = 13.sp) }
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
                TextButton(onClick = { menuOpen = if (menuOpen == "edit") null else "edit" }) { Text(com.spice.nativeapp.core.I18n.t("编辑"), fontSize = 13.sp) }
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
                TextButton(onClick = { menuOpen = if (menuOpen == "analysis") null else "analysis" }) { Text(com.spice.nativeapp.core.I18n.t("分析与多视图"), fontSize = 13.sp) }
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
                TextButton(onClick = { menuOpen = if (menuOpen == "window") null else "window" }) { Text(com.spice.nativeapp.core.I18n.t("窗口与设置"), fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "window", onDismissRequest = { menuOpen = null }) {
                    WindowPanel.values().forEach { wp ->
                        DropdownMenuItem(
                            text = { Text(wp.title, fontSize = 13.sp) },
                            onClick = { menuOpen = null; windowPanel = wp }
                        )
                    }
                    // 设置浮窗（对齐 main menuWinSettings：菜单内 toggle，面板浮于右上角 right:10 top:60）
                    DropdownMenuItem(
                        text = { Text(if (showMapSettings) "✓ 设置" else "设置", fontSize = 13.sp) },
                        onClick = { menuOpen = null; showMapSettings = !showMapSettings }
                    )
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
                label = { Text(if (selectMode) com.spice.nativeapp.core.I18n.t("选择") + "*" else com.spice.nativeapp.core.I18n.t("选择"), fontSize = 13.sp, maxLines = 1) }
            )
            GeneView.values().forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { view = v },
                    label = { Text(com.spice.nativeapp.core.I18n.t(v.label), fontSize = 13.sp, maxLines = 1) }
                )
            }
        }

        // ── 主分屏（对齐 main ove-main +page.svelte:3328-3656）：左 pane 按 tab 切换，
        //    右侧序列视图常驻（共享 selection 联动）；仅"序列视图"tab 时隐藏左 pane、序列全宽 ──
        val seqOnly = view == GeneView.SEQUENCE
        // 竖屏窄幅（maxWidth<620dp）：上下分屏（上=图谱/左区视图 46%，下=序列视图 54%），
        // 手机竖屏可读性优先；横屏/平板维持左右分屏（main 桌面 ove-main 布局 1:1）
        val stacked = narrowScreen && !seqOnly
        val leftContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            when (view) {
                // 序列视图 tab：左 pane 宽度已为 0（main: Sequence tab 隐藏左 pane），无内容渲染
                GeneView.SEQUENCE -> {}
                GeneView.CIRCULAR -> CircularMap(
                    record = record,
                    enzymeNames = if (showCutsites) selectedEnzymes + Enzymes.mapCommon else emptySet(),   // main COMMON_ENZYMES ∪ selected
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it },
                    selectionNotes = selectionNotes
                )
                GeneView.LINEAR -> LinearMap(
                    record = record,
                    enzymeNames = if (showCutsites) selectedEnzymes + Enzymes.mapCommon else emptySet(),   // main COMMON_ENZYMES ∪ selected
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it },
                    selectionNotes = selectionNotes,
                    // 对齐 main PlasmidMap.svelte:112 linearizePlasmid：仅 rotateBpsToPosition(pos-1)
                    // 旋转序列 + selection 重置 1..1 + 成功 toast；不改拓扑、不切视图、不平移特征坐标
                    onLinearizeAt = { pos ->
                        if (pos in 2..record.length) {
                            applyRecord(record.copy(sequence = Bio.rotateTo(record.sequence, pos - 1)))
                            selection = 1..1
                            android.widget.Toast.makeText(
                                ctx, "质粒线性化成功", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                // 左区 3 视图（对齐 main ove-left 按 activeTab 切换：features/properties/notes）
                GeneView.FEATURES -> FeaturesView(
                    record = record,
                    onSelect = { selection = it },
                    onEdit = { editingFeature = it; showAddFeature = true },
                    onDelete = { f -> applyRecord(record.copy(features = record.features - f)) },
                    onAddFeature = { editingFeature = null; showAddFeature = true },
                    onAutoAnnotate = {
                        // main runAutoAnnotate（+page.svelte:2627）：内置库扫描 → 跳过已有 name|start|end → 追加
                        val fresh = com.spice.nativeapp.core.AutoAnnotate.runOnRecord(record)
                        if (fresh.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "未发现新的注释特征", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            applyRecord(record.copy(features = record.features + fresh))
                            android.widget.Toast.makeText(ctx, "自动注释完成：新增 ${fresh.size} 条特征", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
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
            // ── 浮动特征卡（对齐 main PlasmidMap.svelte:241 plasmid-features-tooltip）──
            if (view == GeneView.CIRCULAR && record.features.isNotEmpty()) {
                var expandedFeature by remember(record.name) { mutableStateOf<Feature?>(null) }
                FloatingPanel(
                    title = "质粒特征 ✥ ${record.features.size}",
                    onDismiss = {},           // main 特征卡无关闭按钮
                    hideDismiss = true,
                    initialX = 4.dp,
                    initialY = 52.dp,   // §3.1 工具栏头（标题+按钮行）下沿约 44dp，避免遮挡（main tooltip 亦在工具栏下方）
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
                            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                // main tooltip：特征名 = feat.color 彩色粗体（与图谱色带同源同色）
                                Text(feat.name, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = com.spice.nativeapp.ui.featureColorOf(feat),
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                // main tooltip：类型徽章 = {feat.color}22 底 + color 描边 + color 文字
                                val fcol = com.spice.nativeapp.ui.featureColorOf(feat)
                                Text(feat.type.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = fcol,
                                    modifier = Modifier
                                        .border(1.dp, fcol, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                        .background(fcol.copy(alpha = 0.13f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (expanded) {
                                HorizontalDivider()
                                Text("${feat.start} - ${feat.end} bp", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("大小: ${feat.end - feat.start + 1} bp", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val featSeg = record.sequence.substring((feat.start - 1).coerceIn(0, record.length), feat.end.coerceAtMost(record.length))
                                Text("GC 含量: ${"%.1f".format(com.spice.nativeapp.core.Bio.gcContent(featSeg))}%", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                featureMwKda(feat)?.let { mw -> Text("分子量 (MW): $mw kDa", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.tertiary) }
                                Text("链方向: ${if (feat.strand > 0) "正向" else "反向"}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        }
        val rightContent: @Composable () -> Unit = {
            val rcScope = androidx.compose.runtime.rememberCoroutineScope()
            SequenceView(
                record, hits, singleStrand, colorTheme, showTranslations,
                showCutsites = showCutsites,
                showFeatures = showFeatures,
                selection = selection,
                seqTool = seqTool,
                onSeqTool = { seqTool = it },
                onToggleCutsites = { showCutsites = !showCutsites },
                onToggleFeatures = { showFeatures = !showFeatures },
                onToggleTranslations = { showTranslations = !showTranslations },
                onOpenHybridization = { showHybridization = true },
                onSelect = { selection = it },
                onAnnotateDone = { sel ->
                    // §4.3 注释模式：拖选松手 → 预填特征编辑器（main openFeatureEditor）
                    editingFeature = Feature("", "misc_feature", sel.first, sel.last)
                    showAddFeature = true
                },
                onAddNote = {
                    selection?.let { sel ->
                        selectionNotes = selectionNotes + SelNote("批注 ${sel.first}..${sel.last}", sel.first, sel.last)
                        android.widget.Toast.makeText(ctx, "已添加选区批注", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onCopy = {
                    // main tkCopySeqFeat：复制选区序列 + 命中特征摘要
                    selection?.let { sel ->
                        val seg = record.sequence.substring((sel.first - 1).coerceIn(0, record.length), sel.last.coerceAtMost(record.length))
                        val feats = record.features.filter { it.start <= sel.last && it.end >= sel.first }
                        val text = if (feats.isEmpty()) seg
                        else seg + "\n" + feats.joinToString("\n") { f -> "${f.name} (${f.start}..${f.end}, ${f.type})" }
                        clipboard.setText(AnnotatedString(text))
                        android.widget.Toast.makeText(ctx, "已复制序列与特征", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onCopyImage = {
                    selection?.let { sel ->
                        val segSel = record.sequence.substring((sel.first - 1).coerceIn(0, record.length), sel.last.coerceAtMost(record.length))
                        rcScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val path = exportSelectionPng(ctx, record.name, sel, segSel)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "PNG 已导出：$path", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "PNG 导出失败", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                },
                onRevComp = {
                    selection?.let { sel ->
                        val seqSel = record.sequence.substring((sel.first - 1).coerceIn(0, record.length), sel.last.coerceAtMost(record.length))
                        onRecordChange(record.copy(sequence = record.sequence.replaceRange(sel.first - 1, sel.last, com.spice.nativeapp.core.Bio.revComp(seqSel))))
                        selection = null
                    }
                },
                onDelete = {
                    selection?.let { sel ->
                        onRecordChange(record.copy(sequence = record.sequence.removeRange(sel.first - 1, sel.last)))
                        selection = null
                    }
                },
                Modifier.fillMaxSize()
            )
        }
        if (stacked) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (shortScreen) Modifier.height(560.dp) else Modifier.weight(1f))
                    .padding(horizontal = 12.dp)
            ) {
                Box(Modifier.weight(0.46f).fillMaxWidth()) { leftContent() }
                Box(Modifier.weight(0.54f).fillMaxWidth()) { rightContent() }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (shortScreen) Modifier.height(340.dp) else Modifier.weight(1f))
                    .padding(horizontal = 12.dp)
            ) {
                Box(Modifier.then(if (seqOnly) Modifier.width(0.dp) else Modifier.fillMaxWidth(0.52f))) { leftContent() }
                Box(Modifier.then(if (seqOnly) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.48f))) { rightContent() }
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
        if (showHybridization) {
            // §4.1 PCR 引物杂交参数（main HybridizationDialog.svelte）
            HybridizationDialog(
                onDismiss = { showHybridization = false },
                onConfirm = { _, tm ->
                    android.widget.Toast.makeText(ctx, "退火配置已更新（Tm: ${tm}°C）", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
        if (showAddFeature) {
            AddFeatureDialog(
                record = record,
                onDismiss = { showAddFeature = false; editingFeature = null },
                onAdd = { name, range, type, color, forward ->
                    showAddFeature = false
                    if (editingFeature != null) {
                        // 编辑模式：原位替换（main saveFeature：名称/区间/类型/颜色/链向全部可改）
                        applyRecord(record.copy(features = record.features.map {
                            if (it === editingFeature) Feature(name, type, range.first, range.last, if (forward) 1 else -1, color) else it
                        }))
                        editingFeature = null
                    } else {
                        applyRecord(record.copy(features = record.features + Feature(name, type, range.first, range.last, if (forward) 1 else -1, color)))
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
                initialX = 140.dp,   // 右上角（main right:10px top:60px；411dp 屏 - 260dp 宽 - 10dp 边距）
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
                plasmidName = record.name,
                gcPercent = record.gc,
                onImportDna = { dna ->
                    onAiOpenChange(false)
                    applyRecord(record.copy(sequence = dna))
                },
                onApplyDna = { dna -> applyRecord(record.copy(sequence = dna)) },
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
private fun SequenceView(
    record: SeqRecord,
    sites: List<Enzymes.Hit>,
    singleStrand: Boolean,
    colorTheme: BaseColorTheme,
    showTranslations: Boolean,
    showCutsites: Boolean,
    showFeatures: Boolean,
    selection: IntRange?,
    seqTool: String,
    onSeqTool: (String) -> Unit,
    onToggleCutsites: () -> Unit,
    onToggleFeatures: () -> Unit,
    onToggleTranslations: () -> Unit,
    onOpenHybridization: () -> Unit,
    onSelect: (IntRange?) -> Unit,
    onAnnotateDone: (IntRange) -> Unit,
    onAddNote: () -> Unit,
    onCopy: () -> Unit,
    onCopyImage: () -> Unit,
    onRevComp: () -> Unit,
    onDelete: () -> Unit,
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

    // ── §4.1 DnaViewer.svelte:1249 布局：minimap → SelectionToolkit → Row(工具轨 + 序列滚动区) ──
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selActive = selection != null && !(selection.first == 1 && selection.last == 1)
    Column(modifier.background(Color(0xFF0B0F17))) {
        SequenceMinimap(
            dnaLen = record.length,
            features = if (showFeatures) record.features else emptyList(),
            selection = selection,
            listState = listState,
            onJump = { pct ->
                val info = listState.layoutInfo
                val target = (pct * (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)).toInt()
                scope.launch { listState.scrollToItem(target) }
            }
        )
        if (selActive && selection != null) {
            SelectionToolkitBar(
                sel = selection,
                dna = record.sequence,
                onCopy = onCopy,
                onCopyImage = onCopyImage,
                onRevComp = onRevComp,
                onDelete = onDelete,
                onCancel = { onSelect(null) }
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SequenceToolRail(
                seqTool = seqTool,
                onSeqTool = onSeqTool,
                showEnzymes = showCutsites,
                showFeatures = showFeatures,
                showTranslations = showTranslations,
                onToggleEnzymes = onToggleCutsites,
                onToggleFeatures = onToggleFeatures,
                onToggleTranslations = onToggleTranslations,
                onOpenHybridization = onOpenHybridization,
                onAddNote = onAddNote
            )
            // 窄幅（竖屏上下分屏后手机宽度）：30 bp/行（3×10 块）；宽幅维持 60 bp/行（main 桌面）
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                val rowLen = if (maxWidth < 480.dp) 30 else 60
                // ── Gap 17 Scroll Alignment（main DnaViewer.svelte:287 verbatim 语义）：
                //    selectionStart > 1 且变化时平滑滚动到选区所在行（顶部余量 ≈ -20px）
                androidx.compose.runtime.LaunchedEffect(selection?.first, record.name) {
                    val startBp = selection?.first ?: 0
                    if (startBp > 1) {
                        val target = ((startBp - 1) / rowLen).coerceAtLeast(0)
                        listState.animateScrollToItem(target, scrollOffset = -8)
                    }
                }
                // §4.3 注释模式：colX 反算（与 SequenceRowCanvas 同式：prefixW + i*charW + (i/10)*blockGap）
                val charW = measurer.measure("A", baseStyle).size.width.toFloat()
                val prefixW = measurer.measure("%5d ".format(1), prefixStyle).size.width.toFloat()
                val blockGap = charW * 0.6f
                fun bpAt(x: Float, y: Float): Int? {
                    val info = listState.layoutInfo.visibleItemsInfo
                    val item = info.firstOrNull { y >= it.offset.toFloat() && y < (it.offset + it.size).toFloat() } ?: return null
                    val rowStartBp = item.index * rowLen + 1
                    if (x < prefixW) return rowStartBp
                    val i0 = (x - prefixW) / charW
                    val idx = (i0 - ((i0 / 10f).toInt()) * blockGap / charW).coerceIn(0f, (rowLen - 1).toFloat())
                    return (rowStartBp + idx.toInt()).coerceAtMost(record.length)
                }
                val annotateMode = seqTool == "annotate"
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(
                        if (annotateMode) Modifier.pointerInput(record.name) {
                            // 注释模式拖选（main §4.3）：拖动拉出区间 → 松手开特征编辑器
                            detectDragGestures(
                                onDragStart = { pos -> bpAt(pos.x, pos.y)?.let { onSelect(it..it) } },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val b = bpAt(change.position.x, change.position.y)
                                    val a = selection?.first
                                    if (a != null && b != null) onSelect(if (a <= b) a..b else b..a)
                                },
                                onDragEnd = { selection?.let { sel -> if (sel.last > sel.first) onAnnotateDone(sel) } },
                                onDragCancel = { }
                            )
                        } else Modifier
                    )
                ) {
                    itemsIndexed((0 until (seq.length + rowLen - 1) / rowLen).map { it * rowLen }) { _, start ->
                        val rowStart = start + 1
                        val row = seq.substring(start, minOf(start + rowLen, seq.length))
                        val inRow = rowSitesGlobal.filter {
                            it.top0 in start until (start + rowLen) || it.bot0 in start until (start + rowLen)
                        }
                        SequenceRowCanvas(
                            measurer, prefixStyle, baseStyle, labelStyle, rowStart, row, inRow, singleStrand, colorTheme,
                            showTranslations, if (showFeatures) record.features else emptyList(), record.parts, seq
                        )
                    }
                }
            }
        }
        // ── §3.4 选区属性浮卡（main DnaViewer.svelte:1401：底部居中悬浮） ──
        if (selActive && selection != null) {
            Box(Modifier.fillMaxWidth()) {
                SelectionPropertiesCard(
                    sel = selection,
                    dna = record.sequence,
                    onAddNote = onAddNote,
                    onCopy = onCopy,
                    onDismiss = { onSelect(null) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                )
            }
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
    val charW: Float, val strandH: Float, val prefixW: Float, val blockGap: Float,
    val prefixLayout: androidx.compose.ui.text.TextLayoutResult,
    val labelArea: Float, val labels: List<RowLbl>,
    val baseLayouts: Map<Char, androidx.compose.ui.text.TextLayoutResult>,
    val codons: List<RowAa>,
    // ── §6/§7 注释轨道（main rowRender.ts FEATURE_TRACK_H=18 / PART_COLOR=#ac68cc）──
    val featAnns: List<AnnItem>, val partAnns: List<AnnItem>,
    val featLabelH: Float, val featStackH: Float,
    val partLabelH: Float, val partStackH: Float
)

/** 单条注释（特征/部件）行内布局：标签分层 + 轨道分层（对齐 main calculateRowYOffsets/calculateLabelYOffsets） */
private data class AnnItem(
    val name: String, val start: Int, val end: Int,
    val forward: Boolean, val isBox: Boolean, val color: Color,
    val rangeType: AnnRangeType, val fw: Float,
    val x0: Float, val x1: Float, val midX: Float,
    val labelLevel: Int, val trackLevel: Int,
    val label: androidx.compose.ui.text.TextLayoutResult,
    val inShapeLabel: androidx.compose.ui.text.TextLayoutResult?,
    val feat: Feature?
)

/** main rowRender.ts getAnnotationRangeType verbatim */
private enum class AnnRangeType { MIDDLE, START, END, BEGINNING_AND_END }

private fun annRangeType(rowStart: Int, rowEnd: Int, s: Int, e: Int): AnnRangeType {
    val sIn = s >= rowStart && s <= rowEnd
    val eIn = e >= rowStart && e <= rowEnd
    return if (sIn && eIn) AnnRangeType.BEGINNING_AND_END
    else if (sIn) AnnRangeType.START
    else if (eIn) AnnRangeType.END
    else AnnRangeType.MIDDLE
}

/** main rowRender.ts getFeatureShapeType：arrow/promoter→箭头形，其余 box */
private val ANN_ARROW_TYPES = listOf("cds", "gene", "exon", "mat_peptide", "sig_peptide", "signal_peptide",
    "promoter", "enhancer", "operator", "caat_signal", "gc_signal", "-35_signal", "-10_signal", "tata_box")
private fun annIsBox(type: String): Boolean = !ANN_ARROW_TYPES.contains(type.lowercase())

/** main rowRender.ts calculateRowYOffsets：按长度降序贪心分层，区间 [start,end] 重叠检测 */
private fun trackLevels(spans: List<Pair<Int, Int>>): List<Int> {
    val order = spans.indices.sortedByDescending { spans[it].second - spans[it].first }
    val levels = mutableListOf<MutableList<Pair<Int, Int>>>()
    val out = IntArray(spans.size)
    for (i in order) {
        val s = spans[i]
        var lvl = 0
        while (lvl < levels.size) {
            if (levels[lvl].none { maxOf(s.first, it.first) <= minOf(s.second, it.second) }) break
            lvl++
        }
        if (lvl == levels.size) levels.add(mutableListOf())
        levels[lvl].add(s)
        out[i] = lvl
    }
    return out.toList()
}

/** main rowRender.ts calculateLabelYOffsets：标签盒 [x0,x1] 贪心分层 */
private fun labelLevels(boxes: List<Pair<Float, Float>>): List<Int> {
    val levels = mutableListOf<MutableList<Pair<Float, Float>>>()
    val out = IntArray(boxes.size)
    boxes.forEachIndexed { i, b ->
        var lvl = 0
        while (lvl < levels.size) {
            if (levels[lvl].none { b.first <= it.second && b.second >= it.first }) break
            lvl++
        }
        if (lvl == levels.size) levels.add(mutableListOf())
        levels[lvl].add(b)
        out[i] = lvl
    }
    return out.toList()
}

// main rowRender.ts 常量：PART_COLOR / FEATURE_BODY_H / FEATURE_TRACK_H / LABEL_H
private val PART_TRACK_COLOR = Color(0xFFAC68CC)
private const val FEATURE_BODY_H = 11f
private const val FEATURE_TRACK_H = 18f
private const val ANN_LABEL_H = 12f
private val FEATURE_LABEL_BG = Color(0xFF0A84FF)
private val FEATURE_LABEL_TEXT = Color(0xFFFFFFFF)

/** main drawFeatureShape：box=矩形，arrow/promoter=箭头五边形（反链 x→2x0+fw−x 镜像）；
 *  fill α0.88 + rgba(0,0,0,0.25) 0.5px 描边（Android 无 hover，isHovered 恒 false） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnShape(
    x0: Float, y: Float, fw: Float, h: Float,
    rangeType: AnnRangeType, forward: Boolean, isBox: Boolean, color: Color
) {
    val arrowW = minOf(fw / 2f, 7f)
    val path = androidx.compose.ui.graphics.Path()
    fun px(x: Float) = if (forward) x else 2f * x0 + fw - x
    if (isBox || rangeType == AnnRangeType.MIDDLE) {
        path.moveTo(x0, y)
        path.lineTo(x0 + fw, y)
        path.lineTo(x0 + fw, y + h)
        path.lineTo(x0, y + h)
        path.close()
    } else {
        val tipX = x0 + fw
        val bodyEnd = x0 + maxOf(0f, fw - arrowW)
        path.moveTo(px(x0), y)
        path.lineTo(px(bodyEnd), y)
        path.lineTo(px(tipX), y + h / 2f)
        path.lineTo(px(bodyEnd), y + h)
        path.lineTo(px(x0), y + h)
        path.close()
    }
    drawPath(path, color.copy(alpha = 0.88f))
    drawPath(path, Color.Black.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5f))
}

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
    feats: List<Feature>,
    parts: List<Part>,
    dna: String
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val prefix = "%5d ".format(rowStart)
    val labelH = with(density) { 13.dp.toPx() }
    val labelGap = with(density) { 2.dp.toPx() }
    val strandGap = with(density) { 4.dp.toPx() }
    val transH = if (showTranslations) with(density) { 16.dp.toPx() } else 0f

    // ── 测量/防碰撞只在行内容变化时计算一次（对齐 main rowRender 预计算布局；
    //    1.7.9 在 draw lambda 内每帧重测 ~150 次，慢设备主线程过载导致闪退/ANR）──
    val cached = remember(measurer, prefixStyle, baseStyle, labelStyle, rowStart, row, sites, singleStrand, showTranslations, feats, parts) {
        val charW = measurer.measure("A", baseStyle).size.width.toFloat()
        val strandH = measurer.measure("A", baseStyle).size.height.toFloat()
        val prefixLayout = measurer.measure(prefix, prefixStyle)
        val prefixW = prefixLayout.size.width.toFloat()
        val rowBase0 = rowStart - 1

        // 酶名标签防碰撞分层（对齐 main calculateLabelYOffsets 的贪心堆叠）
        // 列坐标按 10 碱基分块（main §4.1 "10-base blocks"）：每 10 列插入 blockGap
        val blockGap = charW * 0.6f
        fun colX(i: Int) = prefixW + i * charW + (i / 10) * blockGap
        data class Lbl0(val s: RowSite, val l: androidx.compose.ui.text.TextLayoutResult, val xTop: Float, val xc: Float, val w: Float, var level: Int = 0)
        val ls = sites.map { s ->
            val xTop = colX(s.top0 - rowBase0)
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

        // ── §6/§7 注释轨道布局（main rowRender drawAnnotationLabels/drawAnnotationTrack 预计算）──
        val rowEnd = rowStart + row.length - 1
        val annStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
        val annBoldStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        fun annX(s: Int, e: Int): Pair<Float, Float> {
            val x0 = colX(maxOf(s, rowStart) - rowStart)
            val x1 = colX(minOf(e, rowEnd) - rowStart) + charW
            return x0 to x1
        }
        fun fitLabel(name: String, maxWidth: Float, bold: Boolean): String {
            if (maxWidth < 12f) return ""
            val st = if (bold) annBoldStyle else annStyle
            if (measurer.measure(name, st).size.width <= maxWidth) return name
            var i = name.length
            while (i > 3) {
                val cand = name.substring(0, i - 2) + ".."
                if (measurer.measure(cand, st).size.width <= maxWidth) return cand
                i--
            }
            return ""
        }

        val rowFeats = feats.filter { !(it.end < rowStart || it.start > rowEnd) }
        val rowParts = parts.filter { !(it.end < rowStart || it.start > rowEnd) }

        val featTrack = trackLevels(rowFeats.map { it.start to it.end })
        val featLblLv = labelLevels(rowFeats.map { f ->
            val (x0, x1) = annX(f.start, f.end)
            val tw = (f.name.length + 2) * 6f
            val mid = (x0 + x1) / 2f
            (mid - tw / 2f) to (mid + tw / 2f)
        })
        val featAnns = rowFeats.mapIndexed { i, f ->
            val (x0, x1) = annX(f.start, f.end)
            val fw = maxOf(x1 - x0, 4f)
            val isBox = annIsBox(f.type)
            val rt = annRangeType(rowStart, rowEnd, f.start, f.end)
            val inShape = if (!isBox && fw > 30f) {
                fitLabel(f.name, fw - 8f, bold = true).takeIf { it.isNotEmpty() }?.let { measurer.measure(it, annBoldStyle) }
            } else null
            AnnItem(
                name = f.name, start = f.start, end = f.end,
                forward = f.strand != -1, isBox = isBox, color = featureColorOf(f),
                rangeType = rt, fw = fw, x0 = x0, x1 = x1, midX = (x0 + x1) / 2f,
                labelLevel = featLblLv[i], trackLevel = featTrack[i],
                label = measurer.measure(fitLabel(f.name, (x1 - x0) + 20f, bold = false), annStyle),
                inShapeLabel = inShape, feat = f
            )
        }
        val partTrack = trackLevels(rowParts.map { it.start to it.end })
        val partLblLv = labelLevels(rowParts.map { p ->
            val (x0, x1) = annX(p.start, p.end)
            val tw = (p.name.length + 2) * 6f
            val mid = (x0 + x1) / 2f
            (mid - tw / 2f) to (mid + tw / 2f)
        })
        val partAnns = rowParts.mapIndexed { i, p ->
            val (x0, x1) = annX(p.start, p.end)
            val fw = maxOf(x1 - x0, 4f)
            AnnItem(
                name = p.name, start = p.start, end = p.end,
                forward = true, isBox = true, color = PART_TRACK_COLOR,  // parts 无 type → main getFeatureShapeType('')=box
                rangeType = annRangeType(rowStart, rowEnd, p.start, p.end), fw = fw,
                x0 = x0, x1 = x1, midX = (x0 + x1) / 2f,
                labelLevel = partLblLv[i], trackLevel = partTrack[i],
                label = measurer.measure(fitLabel(p.name, (x1 - x0) + 20f, bold = false), annStyle),
                inShapeLabel = null, feat = null
            )
        }
        val featStackH = if (featAnns.isNotEmpty()) (featAnns.maxOf { it.trackLevel } + 1) * FEATURE_TRACK_H + 5f else 0f
        val featLabelH = if (featAnns.isNotEmpty()) (featAnns.maxOf { it.labelLevel } + 1) * ANN_LABEL_H else 0f
        val partStackH = if (partAnns.isNotEmpty()) (partAnns.maxOf { it.trackLevel } + 1) * FEATURE_TRACK_H + 5f else 0f
        val partLabelH = if (partAnns.isNotEmpty()) (partAnns.maxOf { it.labelLevel } + 1) * ANN_LABEL_H else 0f

        RowCache(charW, strandH, prefixW, blockGap, prefixLayout, labelArea, labels, baseLayouts, codons,
            featAnns, partAnns, featLabelH, featStackH, partLabelH, partStackH)
    }
    val featBandH = if (cached.featAnns.isNotEmpty()) cached.featLabelH + cached.featStackH + 5f else 0f
    val partBandH = if (cached.partAnns.isNotEmpty()) cached.partLabelH + cached.partStackH + 5f else 0f
    val heightPx = cached.labelArea + cached.strandH + (if (!singleStrand) strandGap + cached.strandH else 0f) + transH +
        featBandH + partBandH + with(density) { 4.dp.toPx() }

    // ── 轨道纵向定位（对齐 main getFeatureTrackYForRow 的累加语义）──
    val topY = cached.labelArea
    val botY = if (!singleStrand) cached.labelArea + cached.strandH + strandGap else topY
    val seqBottomY = if (!singleStrand) botY + cached.strandH else topY + cached.strandH
    val annStartY = seqBottomY + transH
    val featCurY = annStartY + cached.featLabelH                    // 特征标签↑/轨道↓ 分界
    val partCurY = featCurY + featBandH + cached.partLabelH         // parts 同构（main §7 紧跟 §6）

    // ── 特征点击 → 锁定浮卡（main handleMouseDown 命中检测 + toggle；parts 无点击交互）──
    var tapped by remember(rowStart) { mutableStateOf<Feature?>(null) }
    var tapPos by remember(rowStart) { mutableStateOf(Offset.Zero) }
    fun hitTest(pos: Offset): Feature? {
        if (cached.featAnns.isEmpty()) return null
        if (pos.y < featCurY || pos.y > featCurY + cached.featStackH || pos.x < cached.prefixW) return null
        var bp = -1
        for (i in 0 until row.length) {
            val cx = cached.prefixW + i * cached.charW + (i / 10) * cached.blockGap
            if (pos.x >= cx && pos.x <= cx + cached.charW) { bp = rowStart + i; break }
        }
        if (bp < 0) return null
        // main: y ∈ [trackYStart, trackYStart+14] 且 pos ∈ [feat.start, feat.end]
        return cached.featAnns.firstOrNull { a ->
            a.feat != null && bp >= a.start && bp <= a.end &&
                pos.y >= featCurY + a.trackLevel * FEATURE_TRACK_H &&
                pos.y <= featCurY + a.trackLevel * FEATURE_TRACK_H + 14f
        }?.feat
    }

    Box(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { heightPx.toDp() })
                .padding(vertical = 1.dp)
                .pointerInput(cached, rowStart) {
                    detectTapGestures { pos ->
                        val hit = hitTest(pos)
                        tapped = when {
                            hit == null -> null                                     // 未命中 → 关闭（main mousedown 先置 null）
                            hit == tapped -> null                                   // 二次点击同特征 → toggle 关闭
                            else -> hit
                        }
                        if (tapped != null) tapPos = pos
                    }
                }
        ) {

        // ── 酶名标签 + 连接线（对齐 main rowRender §2）──
        cached.labels.forEach { l ->
            val labelBottomY = cached.labelArea - l.level * (labelH + labelGap)
            drawText(l.layout, color = l.site.color, topLeft = Offset(l.xc - l.w / 2, labelBottomY - labelH))
            drawLine(l.site.color.copy(alpha = 0.6f), Offset(l.xc, labelBottomY + 1f), Offset(l.xTop, topY - 2f), 1f)
        }

        // ── 列坐标（10 碱基分块，main §4.1）──
        fun colX(i: Int) = cached.prefixW + i * cached.charW + (i / 10) * cached.blockGap

        // ── 碱基（正链，按 colorTheme 主题着色，对齐 main rowRender.ts:691）──
        drawText(cached.prefixLayout, topLeft = Offset(0f, topY))
        row.forEachIndexed { i, c ->
            drawText(cached.baseLayouts.getValue(c), color = baseColorFor(colorTheme, c), topLeft = Offset(colX(i), topY))
        }
        // ── 互补链（逐列对齐）──
        if (!singleStrand) {
            drawText(cached.prefixLayout, topLeft = Offset(0f, botY))
            row.forEachIndexed { i, c ->
                val rc = Bio.complement(c.toString()).firstOrNull() ?: 'N'
                drawText(cached.baseLayouts.getValue(rc), color = baseColorFor(colorTheme, rc), topLeft = Offset(colX(i), botY))
            }
        }

        // ── 翻译行：三联体圆角框 + AA 字母，粉色调 ──
        if (showTranslations) {
            val aaY = if (!singleStrand) botY + cached.strandH + 2.dp.toPx() else topY + cached.strandH + 2.dp.toPx()
            val boxH = 13.dp.toPx()
            cached.codons.forEach { c ->
                val x0 = colX(c.i)
                val x1 = colX(c.i + 3)
                drawRoundRect(
                    color = Color(0x33DB2777),   // rgba(219,39,119,0.2)
                    topLeft = Offset(x0 + 1f, aaY),
                    size = androidx.compose.ui.geometry.Size((x1 - x0 - 1f).coerceAtLeast(2f), boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = Color(0x80DB2777),   // rgba(219,39,119,0.5) 描边
                    topLeft = Offset(x0 + 1f, aaY),
                    size = androidx.compose.ui.geometry.Size((x1 - x0 - 1f).coerceAtLeast(2f), boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5.dp.toPx())
                )
                drawText(c.layout, topLeft = Offset((x0 + x1) / 2f - c.layout.size.width / 2f, aaY + boxH / 2f - c.layout.size.height / 2f))
            }
        }

        // ── OVE 式切痕：上/下链各一道竖线 + 粘性末端连接线（对齐 main rowRender §4b）──
        sites.forEach { s ->
            val xTop = colX(s.top0 - (rowStart - 1))
            val xBot = colX(s.bot0 - (rowStart - 1))
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

        // ── §6. Features（main rowRender.ts drawAnnotationLabels/Track：标签↑ 从 featCurY、轨道↓ 逐层 18px）──
        if (cached.featAnns.isNotEmpty()) {
            cached.featAnns.forEach { a ->
                val y = featCurY - 2f - a.labelLevel * ANN_LABEL_H
                val tw = a.label.size.width.toFloat()
                // SnapGene 式蓝底白字圆角标签（COLOR_FEATURE_LABEL_BG #0a84ff）
                drawRoundRect(
                    FEATURE_LABEL_BG,
                    topLeft = Offset(a.midX - tw / 2f - 4f, y - 9f),
                    size = androidx.compose.ui.geometry.Size(tw + 8f, 10f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
                )
                drawText(a.label, color = FEATURE_LABEL_TEXT, topLeft = Offset(a.midX - tw / 2f, y + 1f - a.label.size.height))
                drawLine(Color(0x660A84FF), Offset(a.midX, y + 1f), Offset(a.midX, featCurY + 5f), 0.5f)  // rgba(10,132,255,0.4) 引线
            }
            cached.featAnns.forEach { a ->
                val itemY = featCurY + a.trackLevel * FEATURE_TRACK_H
                drawAnnShape(a.x0, itemY, a.fw, FEATURE_BODY_H, a.rangeType, a.forward, a.isBox, a.color)
                a.inShapeLabel?.let {
                    drawText(it, topLeft = Offset((a.x0 + a.x1) / 2f - it.size.width / 2f, itemY + FEATURE_BODY_H / 2f - it.size.height / 2f))
                }
            }
        }

        // ── §7. Parts（main PART_COLOR '#ac68cc'：标签同色无底、box 轨道、无 hover(-1)、无 toggle 恒显示）──
        if (cached.partAnns.isNotEmpty()) {
            cached.partAnns.forEach { a ->
                val y = partCurY - 2f - a.labelLevel * ANN_LABEL_H
                val tw = a.label.size.width.toFloat()
                drawText(a.label, color = PART_TRACK_COLOR, topLeft = Offset(a.midX - tw / 2f, y + 1f - a.label.size.height))
                drawLine(PART_TRACK_COLOR, Offset(a.midX, y + 1f), Offset(a.midX, partCurY + 5f), 0.5f)
            }
            cached.partAnns.forEach { a ->
                drawAnnShape(a.x0, partCurY + a.trackLevel * FEATURE_TRACK_H, a.fw, FEATURE_BODY_H, a.rangeType, a.forward, a.isBox, PART_TRACK_COLOR)
            }
        }
    }

        // ── 特征锁定浮卡（main clickedFeature tooltip：240px 宽、特征色描边、区块下方锚定）──
        tapped?.let { f ->
            FeatureTipCard(
                f, dna,
                Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            (tapPos.x - 120.dp.toPx()).toInt().coerceAtLeast(0),
                            (featCurY + cached.featStackH + 4f).toInt()
                        )
                    }
                    .zIndex(10f)
            ) { tapped = null }
        }
    }
}

/** 特征信息浮卡（main DnaViewer.svelte:1352 clickedFeature tooltip 1:1：物理区间/长度/GC/分子量） */
@Composable
private fun FeatureTipCard(f: Feature, dna: String, modifier: Modifier, onDismiss: () -> Unit) {
    val fLen = f.end - f.start + 1
    // main getFeatureStats：seg = slice(fStart, fEnd+1)（0-based 半开）
    val seg = dna.substring((f.start - 1).coerceAtLeast(0), f.end.coerceAtMost(dna.length))
    val gc = Bio.gcContent(seg)
    val isCds = f.type.lowercase() in listOf("cds", "gene", "exon")
    val mwKda = if (isCds) "%.1f".format(fLen / 3.0 * 110 / 1000) else null
    val borderColor = featureColorOf(f)
    Column(
        modifier
            .width(240.dp)
            .background(Color(0xFF0C0F16), RoundedCornerShape(6.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.name + " ✥", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = borderColor,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("[X]", fontSize = 10.sp, color = Color(0xFFFF453A), modifier = Modifier.clickable(onClick = onDismiss).padding(2.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(com.spice.nativeapp.core.I18n.t("物理区间:"), fontSize = 10.sp, color = Color(0xFF8892A6))
            Text("${f.start}..${f.end} bp", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE0E8F0))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(com.spice.nativeapp.core.I18n.t("长度:"), fontSize = 10.sp, color = Color(0xFF8892A6))
            Text("$fLen bp", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE0E8F0))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(com.spice.nativeapp.core.I18n.t("GC 含量:"), fontSize = 10.sp, color = Color(0xFF8892A6))
            Text("${"%.1f".format(gc)}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF4CD6FF))
        }
        if (mwKda != null) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("估计分子量:"), fontSize = 10.sp, color = Color(0xFF8892A6))
                Text("$mwKda kDa", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF53D769))
            }
        }
    }
}
