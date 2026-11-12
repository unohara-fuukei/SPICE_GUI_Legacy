package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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

private enum class GeneView(val label: String) { CIRCULAR("环状图谱"), LINEAR("线性图谱"), SEQUENCE("序列") }

@Composable
fun GeneScreen(
    record: SeqRecord,
    selectedEnzymes: Set<String>,
    onToggleEnzyme: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRecordChange: (SeqRecord) -> Unit = {}
) {
    var view by remember { mutableStateOf(GeneView.CIRCULAR) }
    var showFeatures by remember { mutableStateOf(true) }
    var selection by remember(record.name) { mutableStateOf<IntRange?>(null) }
    var menuOpen by remember { mutableStateOf<String?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 打开序列文件（.gb/.gbk/.fasta/.fa/.txt）
    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                com.spice.nativeapp.core.SeqIO.parse(text)?.let { onRecordChange(it) }
            }
        }
    }
    // 导出当前序列（GenBank）
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(com.spice.nativeapp.core.SeqIO.toGenBank(record).toByteArray())
                }
            }
        }
    }

    fun selectionText(): String = selection?.let { record.sequence.substring(it.first - 1, it.last) } ?: ""

    val hits = remember(record.sequence, selectedEnzymes, record.circular) {
        Enzymes.digest(record.sequence, selectedEnzymes, record.circular)
    }

    // 紧凑/矮屏时整页滚动并给图谱保底高度，避免图谱被压成一条细带
    BoxWithConstraints(modifier.fillMaxSize()) {
    val shortScreen = maxHeight < 620.dp
    Column(
        Modifier.fillMaxSize().then(
            if (shortScreen) Modifier.verticalScroll(rememberScrollState()) else Modifier
        )
    ) {
        // 原版 retro 菜单（文件 / 编辑）：Material 3 下拉菜单，菜单项 48dp
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "file") null else "file" }) {
                    Text("文件", fontSize = 13.sp)
                }
                DropdownMenu(expanded = menuOpen == "file", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("打开序列文件…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; openLauncher.launch(arrayOf("*/*")) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出当前序列 (GenBank)…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; exportLauncher.launch("${record.name}.gb") }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "edit") null else "edit" }) {
                    Text("编辑", fontSize = 13.sp)
                }
                DropdownMenu(expanded = menuOpen == "edit", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("反向互补序列", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            val rc = com.spice.nativeapp.core.Bio.revComp(record.sequence)
                            onRecordChange(record.copy(sequence = rc, name = "${record.name}_rc"))
                            selection = null
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("切换拓扑（环状 / 线性）", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            onRecordChange(record.copy(circular = !record.circular))
                            selection = null
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (selection == null) "清除选择区" else "清除选择区（${selection!!.first}..${selection!!.last}）",
                                fontSize = 13.sp
                            )
                        },
                        enabled = selection != null,
                        onClick = { menuOpen = null; selection = null }
                    )
                    DropdownMenuItem(
                        text = { Text("复制选区序列", fontSize = 13.sp) },
                        enabled = selection != null,
                        onClick = {
                            menuOpen = null
                            clipboard.setText(AnnotatedString(selectionText()))
                        }
                    )
                }
            }
            Text(
                "${record.length} bp",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
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
                GeneView.SEQUENCE -> SequenceView(record, hits.map { it.pos }.toSet(), Modifier.fillMaxSize())
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
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
private fun SequenceView(record: SeqRecord, cutPositions: Set<Int>, modifier: Modifier = Modifier) {
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
        }
    }
}
