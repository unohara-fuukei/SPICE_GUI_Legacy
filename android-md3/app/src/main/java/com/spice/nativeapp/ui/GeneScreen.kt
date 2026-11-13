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
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.Feature
import kotlinx.coroutines.launch

private enum class GeneView(val label: String) { CIRCULAR("环状图谱"), LINEAR("线性图谱"), SEQUENCE("序列") }

@Composable
fun GeneScreen(
    record: SeqRecord,
    selectedEnzymes: Set<String>,
    onToggleEnzyme: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRecordChange: (SeqRecord) -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onSaveProject: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var view by remember { mutableStateOf(GeneView.CIRCULAR) }
    var showFeatures by remember { mutableStateOf(true) }
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
    var showAi by remember { mutableStateOf(false) }                // SPICE AI Co-Pilot（对齐 main 全局侧栏）
    var pendingExport by remember { mutableStateOf<String?>(null) } // 待写出的格式：spiceg/genbank/fasta/embl
    val scope = rememberCoroutineScope()
    var opLog by remember { mutableStateOf<List<String>>(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var undoStack by remember { mutableStateOf<List<SeqRecord>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<SeqRecord>>(emptyList()) }
    var dirty by remember { mutableStateOf(false) }          // 对齐 main 的未保存指示
    var singleStrand by remember { mutableStateOf(false) }   // 对齐 main 的单链紧凑/双链模式

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
                    "spiceg" -> com.spice.nativeapp.core.Spiceg.write(record, "ECOLI", selectedEnzymes.toList())
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
            onDismiss = { showAddFeature = false },
            onAdd = { name, range, type ->
                showAddFeature = false
                applyRecord(record.copy(features = record.features + Feature(name, type, range.first, range.last, 1)))
            }
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
    if (showAi) {
        AiCopilotSheet(
            workspace = AiWorkspace.GENE,
            sequence = record.sequence,
            sequenceLabel = "质粒 ${record.name} DNA 序列",
            onImportDna = { dna ->
                showAi = false
                applyRecord(record.copy(sequence = dna))
            },
            onDismiss = { showAi = false }
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
                        onClick = { menuOpen = null; singleStrand = !singleStrand }
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

        Box(
            Modifier
                .fillMaxWidth()
                .then(if (shortScreen) Modifier.height(260.dp) else Modifier.weight(1f))
                .padding(horizontal = 12.dp)
        ) {
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
            when (view) {
                GeneView.CIRCULAR -> CircularMap(
                    record = record,
                    enzymeNames = selectedEnzymes,
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it }
                )
                GeneView.LINEAR -> LinearMap(
                    record = record,
                    enzymeNames = selectedEnzymes,
                    modifier = Modifier.fillMaxSize(),
                    selection = selection,
                    selectionMode = selectMode,
                    onSelect = { selection = it }
                )
                GeneView.SEQUENCE -> SequenceView(record, hits.map { it.pos }.toSet(), singleStrand, Modifier.fillMaxSize())
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

        if (showFeatures) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(12.dp).height(if (shortScreen) 200.dp else 150.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("特征注释（${record.features.size}）", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { showFeatures = false }) { Text("收起") }
                    }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(record.features) { f ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(f.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${f.start}–${f.end} · ${f.type}${if (f.strand < 0) " (−)" else ""}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
        // SPICE AI Co-Pilot 浮动入口（对齐 main 布局级 ai-toggle-btn）
        FloatingActionButton(
            onClick = { showAi = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) { Text("AI", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

private val baseColors = mapOf(
    'A' to Color(0xFF7ED957),
    'T' to Color(0xFFFF8A80),
    'G' to Color(0xFFFFC56E),
    'C' to Color(0xFF69C9F0),
    'N' to Color(0xFF9E9E9E)
)

@Composable
private fun SequenceView(record: SeqRecord, cutPositions: Set<Int>, singleStrand: Boolean, modifier: Modifier = Modifier) {
    val rows = remember(record.sequence) {
        val seq = record.sequence
        (0 until (seq.length + 59) / 60).map { i ->
            val start = i * 60
            seq.substring(start, minOf(start + 60, seq.length))
        }
    }
    val mono = FontFamily.Monospace
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)).padding(8.dp)) {
        itemsIndexed(rows) { index, row ->
            val rowStart = index * 60 + 1
            val annotated: AnnotatedString = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 11.sp)) {
                    append("%5d ".format(rowStart))
                }
                row.forEachIndexed { idx, c ->
                    val pos = rowStart + idx
                    val highlighted = cutPositions.contains(pos)
                    val color = baseColors[c] ?: Color.Unspecified
                    pushStyle(SpanStyle(color = color, fontFamily = mono, fontSize = 12.sp))
                    append(c)
                    pop()
                    if (highlighted) {
                        withStyle(SpanStyle(color = Color(0xFFFF5252), fontFamily = mono, fontSize = 11.sp)) { append("|") }
                    } else if ((idx + 1) % 10 == 0) {
                        append(" ")
                    }
                }
            }
            Text(annotated, modifier = Modifier.padding(vertical = 1.dp))
            // 双链模式：互补链与正链逐列对齐（分隔符位置镜像，保证等宽对齐）
            if (!singleStrand) {
                val annotatedRc: AnnotatedString = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 11.sp)) {
                        append("%5d ".format(rowStart))
                    }
                    row.forEachIndexed { idx, c ->
                        val pos = rowStart + idx
                        val highlighted = cutPositions.contains(pos)
                        val rc = Bio.complement(c.toString()).firstOrNull() ?: 'N'
                        val color = baseColors[rc] ?: Color.Unspecified
                        pushStyle(SpanStyle(color = color, fontFamily = mono, fontSize = 12.sp))
                        append(rc)
                        pop()
                        if (highlighted) {
                            withStyle(SpanStyle(color = Color(0xFFFF5252), fontFamily = mono, fontSize = 11.sp)) { append("|") }
                        } else if ((idx + 1) % 10 == 0) {
                            append(" ")
                        }
                    }
                }
                Text(annotatedRc, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}
