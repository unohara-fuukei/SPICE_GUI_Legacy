package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.SangerValidate
import com.spice.nativeapp.core.SeqRecord

/** 原版「分析与多视图」菜单的 10 个条目 */
enum class AnalysisPanel(val title: String, val hint: String) {
    PROTEIN("蛋白视图", "把 CDS 翻译成蛋白并给出性质"),
    RNA("RNA 结构", "DNA→RNA 与二级结构配对预测"),
    SANGER("Sanger 色谱", "最近导入的色谱碱基与质量"),
    VALIDATOR("克隆验证器", "读框、内部终止与元件完整性检查"),
    GENOMICS("基因组工具", "六框翻译与 ORF 统计"),
    SYNBIO("合成生物学工具", "元件清单与组装建议"),
    BLAST("本地 BLAST", "k-mer 相似度比对"),
    ALIGNMENT("多序列比对", "星型中心对齐与一致性"),
    GC("GC 窗口", "滑窗 GC 曲线"),
    IMPORT_ALIGN("导入比对文件", "Clustal / FASTA 多序列读取")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisPanelSheet(
    panel: AnalysisPanel,
    record: SeqRecord,
    lastTrace: String?,
    onDismiss: () -> Unit,
    onRecordChange: (SeqRecord) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(panel.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(panel.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (panel) {
                AnalysisPanel.PROTEIN -> ProteinView(record, clipboard)
                AnalysisPanel.RNA -> RnaView(record)
                AnalysisPanel.SANGER -> SangerView(record, lastTrace)
                AnalysisPanel.VALIDATOR -> ValidatorView(record)
                AnalysisPanel.GENOMICS -> GenomicsView(record)
                AnalysisPanel.SYNBIO -> SynbioView(record)
                AnalysisPanel.BLAST -> BlastView(record, clipboard)
                AnalysisPanel.ALIGNMENT -> AlignmentView(record, clipboard, onRecordChange)
                AnalysisPanel.GC -> GcView(record)
                AnalysisPanel.IMPORT_ALIGN -> Text("请用「文件 → 打开序列文件」导入 Clustal / FASTA 多序列文件；单条序列会直接载入编辑器。", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProteinView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val cds = remember(record.sequence) {
        CloningPlus.findOrfs(record.sequence, 90).firstOrNull()?.let { o ->
            if (o.strand > 0) record.sequence.substring(o.start - 1, o.end)
            else Bio.revComp(record.sequence.substring(o.start - 1, o.end))
        } ?: record.sequence
    }
    val protein = remember(cds) { Bio.translate(cds) }
    val props = remember(protein) { com.spice.nativeapp.core.ProteinCore.props(protein) }
    Text("最长 ORF ${cds.length} bp → ${protein.length} aa", fontSize = 11.sp)
    Text(
        protein.chunked(60).joinToString("\n"),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Mw ${"%.0f".format(props.molecularWeight)} Da · pI ${"%.2f".format(props.pI)} · GRAVY ${"%.3f".format(props.gravy)}",
        fontSize = 11.sp
    )
    TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { clipboard.setText(AnnotatedString(protein)) }) { Text("复制蛋白序列") }
}

@Composable
private fun RnaView(record: SeqRecord) {
    val rna = remember(record.sequence) { record.sequence.replace('T', 'U').replace('t', 'u') }
    Text("转录（前 300 nt）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(
        rna.take(300).chunked(60).joinToString("\n"),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // 简易二级结构：找 ≥4 nt 的反向互补配对片段（茎区近似）
    val pairs = remember(record.sequence) {
        val seq = record.sequence.uppercase()
        var count = 0
        var i = 0
        while (i < seq.length - 8 && count < 20) {
            val stem = seq.substring(i, i + 5)
            val rc = Bio.revComp(stem)
            val j = seq.indexOf(rc, i + 6)
            if (j > 0) {
                count++
                i = j + 5
            } else i += 1
        }
        count
    }
    Text("预测茎区（≥5 nt 反向互补）${pairs} 处；如需最小自由能结构，请用 RNAfold 等专用工具。", fontSize = 11.sp)
}

@Composable
private fun SangerView(record: SeqRecord, lastTrace: String?) {
    if (lastTrace == null) {
        Text("尚未导入色谱。用「文件 → 导入测序色谱 (.ab1/.scf)」载入后在此查看。", fontSize = 12.sp)
        return
    }
    var query by remember { mutableStateOf(lastTrace) }
    Text("碱基长度 ${lastTrace.length} nt（当前序列参考 ${record.length} bp）", fontSize = 11.sp)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.uppercase().filter { c -> c in "ACGTN" } },
        label = { Text("测序 read（可对应参考序列编辑/修剪）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    val cmp = remember(record.sequence, query) {
        if (query.length < 20) null else SangerValidate.align(record.sequence, query)
    }
    if (cmp != null) {
        Text("${cmp.verdict} · 一致性 ${"%.2f".format(cmp.identityPercent)}%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "匹配 ${cmp.identities} · 错配 ${cmp.mismatches} · 插入 ${cmp.insertions} · 缺失 ${cmp.deletions} · 覆盖 ${cmp.coverageStart}–${cmp.coverageEnd}",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        cmp.warnings.forEach { Text("⚠ $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        if (cmp.variants.isNotEmpty()) {
            Text("变异（前 20）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            cmp.variants.take(20).forEach { v ->
                Text(
                    "${v.refPos}: ${v.refBase ?: "—"} → ${v.queryBase ?: "—"}  ${v.type}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
        }
    }
    Text("read 原文", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(query.chunked(60).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun ValidatorView(record: SeqRecord) {
    val orfs = remember(record.sequence) { CloningPlus.findOrfs(record.sequence, 90) }
    val stops = remember(record.sequence) {
        val seq = record.sequence.uppercase()
        val map = mutableListOf<Int>()
        seq.chunked(3).forEachIndexed { i, c -> if (c in setOf("TAA", "TAG", "TGA")) map += i }
        map
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ORF ${orfs.size} 个；框内终止信号 ${stops.size} 个", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        orfs.take(6).forEach { o ->
            Text("· ${o.start}..${o.end}（${o.aa} aa，链 ${if (o.strand > 0) "+" else "-"}，含起始 ATG）", fontSize = 11.sp)
        }
        val issues = CloningPlus.qc(record.sequence)
        if (issues.isEmpty()) Text("✓ 未发现读框/元件级严重问题", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        else issues.take(6).forEach { Text("[${it.level}] ${it.text}", fontSize = 11.sp) }
    }
}

@Composable
private fun GenomicsView(record: SeqRecord) {
    val frames = remember(record.sequence) { Bio.sixFrames(record.sequence) }
    frames.forEach { (label, protein) ->
        Text("$label：${protein.take(60)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SynbioView(record: SeqRecord) {
    val parts = remember(record.sequence) { CloningPlus.autoAnnotate(record.sequence) }
    Text("检出元件 ${parts.size} 个", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    parts.forEach { (name, range, type) ->
        Text("$name  ${range.first}..${range.last}  [$type]", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    if (parts.isEmpty()) Text("未命中内置元件库（启动子/RBS/CDS/终止子/tag），可手动添加特征注释。", fontSize = 11.sp)
}

@Composable
private fun BlastView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var query by remember { mutableStateOf("") }
    val kmers = 12
    val score = remember(query, record.sequence) {
        if (query.length < kmers) 0.0
        else {
            val q = query.uppercase().filter { it.isLetter() }
            val t = record.sequence.uppercase()
            val qs = q.windowed(kmers).toSet()
            val ts = t.windowed(kmers).toSet()
            if (qs.isEmpty()) 0.0 else qs.count { it in ts }.toDouble() / qs.size * 100
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.uppercase().filter { c -> c.isLetter() } },
        label = { Text("查询序列（$kmers-mer 比对）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    Text("相似度命中率 ${"%.1f".format(score)}%（严格 k-mer 集合覆盖；非 BLAST 的 E-value 模型）", fontSize = 11.sp)
    if (query.length >= kmers) CopyRow(clipboard, query)
}

@Composable
private fun AlignmentView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    var extra by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val rows = remember(extra, show) {
        if (!show) emptyList() else buildList {
            addAll(extra.split("\n").filter { it.length > 10 }.map { it.uppercase().filter { c -> c.isLetter() } }.take(6))
        }
    }
    OutlinedTextField(
        value = extra,
        onValueChange = { extra = it },
        label = { Text("额外序列（每行一条）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("对齐") }
        OutlinedButton(
            onClick = {
                val all = (listOf(record.sequence) + rows).map { it.uppercase() }
                val cols = all.minOfOrNull { it.length } ?: 0
                val consensus = (0 until cols).count { i -> all.all { it[i] == all[0][i] } }
                // 一致性写入备注供查看
                onRecordChange(record.copy(description = "比对一致性 ${"%.1f".format(consensus * 100.0 / (cols.coerceAtLeast(1)))}% ($cols 列)"))
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("计算一致性") }
    }
    if (rows.isNotEmpty()) {
        Text("参考序列", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(record.sequence.take(120).uppercase().chunked(60).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        rows.forEachIndexed { i, r ->
            Text("seq${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(r.take(120).chunked(60).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Text("（无间隙并排展示；MAFFT 级比对需外部引擎，故不臆造比对分数）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GcView(record: SeqRecord) {
    val window = 50
    val values = remember(record.sequence) {
        val seq = record.sequence.uppercase()
        if (seq.length < window) emptyList()
        else (0..(seq.length - window) step maxOf(1, window / 2)).map { i ->
            Bio.gcContent(seq.substring(i, i + window))
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Text("滑窗 ${window} bp，窗口数 ${values.size}，范围 ${"%.1f".format(values.minOrNull() ?: 0.0)}–${"%.1f".format(values.maxOrNull() ?: 0.0)}%", fontSize = 11.sp)
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        if (values.isEmpty()) return@Canvas
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        drawLine(outline, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1f)
        values.forEachIndexed { i, v ->
            val y = size.height - (v / 100.0 * size.height).toFloat()
            val x = i * stepX
            if (i > 0) {
                val px = (i - 1) * stepX
                val py = size.height - (values[i - 1] / 100.0 * size.height).toFloat()
                drawLine(primary, Offset(px, py), Offset(x, y), 2f)
            }
            drawCircle(primary, 2f, Offset(x, y))
        }
    }
}
