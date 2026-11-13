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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.CloneValidation
import com.spice.nativeapp.core.GenomicsTools
import com.spice.nativeapp.core.MultipleAlign
import com.spice.nativeapp.core.RnaStructure
import com.spice.nativeapp.core.SangerValidate
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.core.AlignmentIO

/** 原版「分析与多视图」菜单的 10 个条目 */
enum class AnalysisPanel(val title: String, val hint: String) {
    PROTEIN("蛋白质翻译与三维视图", "把 CDS 翻译成蛋白并给出性质"),
    RNA("RNA 二级结构预测", "Nussinov 二级结构折叠与 dot-bracket 可视化"),
    SANGER("Sanger 测序峰图验证", "四色色谱、质量值与一键克隆验证"),
    VALIDATOR("Sanger 一键克隆变异校验", "读框、内部终止与元件完整性检查"),
    GENOMICS("高级基因组学工具舱", "六框翻译、TFBS/启动子/CpG/重复/CRISPR 与基因预测"),
    SYNBIO("合成生物学专用设计舱", "元件清单与组装建议"),
    BLAST("序列比对、BLAST 与系统发育进化舱", "k-mer 相似度比对"),
    ALIGNMENT("序列双重/多重比对", "渐进式 MSA、共识序列与保守性"),
    GC("GC 含量滑动窗口", "滑窗 GC 曲线"),
    IMPORT_ALIGN("导入比对文件", "Clustal / FASTA 多序列读取")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisPanelSheet(
    panel: AnalysisPanel,
    record: SeqRecord,
    trace: com.spice.nativeapp.core.AbiReader.Trace?,
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
                AnalysisPanel.SANGER -> SangerView(record, trace)
                AnalysisPanel.VALIDATOR -> ValidatorView(record)
                AnalysisPanel.GENOMICS -> GenomicsView(record)
                AnalysisPanel.SYNBIO -> SynbioView(record)
                AnalysisPanel.BLAST -> BlastView(record, clipboard)
                AnalysisPanel.ALIGNMENT -> AlignmentView(record, clipboard, onRecordChange)
                AnalysisPanel.GC -> GcView(record)
                AnalysisPanel.IMPORT_ALIGN -> ImportAlignView(clipboard)
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
    // Nussinov 滑窗折叠（对齐 main 的 rnaStructure.ts findRnaStructures；截前 2400 nt 保证移动端性能）
    val capped = record.sequence.take(2400)
    val rna = remember(capped) { RnaStructure.findStructures(capped, isRna = true) }
    Text("转录（前 300 nt）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(
        record.sequence.take(300).uppercase().chunked(60).joinToString("\n"),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider()
    Text(
        "Nussinov 二级结构（滑窗 120/60${if (capped.length < record.sequence.length) "，已截取前 2400 nt" else ""}）" +
            " · 窗口结构 ${rna.structures.size} 个 · 总体 MFE ${rna.overallMfe} kcal/mol",
        fontSize = 11.sp, fontWeight = FontWeight.Bold
    )
    val best = rna.longestHairpin
    if (best == null) {
        Text("未发现稳定配对结构。", fontSize = 11.sp)
        return
    }
    Text(
        "最长配对窗口：${best.sequence.length} nt · 配对 ${best.stemCount} 对 · 发夹 ${best.hairpinCount} 个 · MFE ${best.mfe} · GC ${best.gcContent}%",
        fontSize = 10.sp, fontFamily = FontFamily.Monospace
    )
    // dot-bracket 弧线可视化（对齐 main 的 dotBracketToSvgPaths：骨架 + 绿色配对弧）
    val backbone = MaterialTheme.colorScheme.outline
    val arcColor = MaterialTheme.colorScheme.primary
    Text("dot-bracket：" + best.dotBracket.chunked(60).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val db = best.dotBracket
        val n = db.length
        if (n == 0) return@Canvas
        val dx = size.width / (n + 1)
        drawLine(backbone, Offset(dx, size.height / 2), Offset(dx * n, size.height / 2), 1.5f)
        var i = 0
        while (i < n) {
            if (db[i] == '(') {
                var depth = 1
                var j = i + 1
                while (j < n) {
                    if (db[j] == '(') depth++
                    if (db[j] == ')') {
                        depth--
                        if (depth == 0) {
                            val x1 = dx * (i + 1)
                            val x2 = dx * (j + 1)
                            val r = maxOf(3f, kotlin.math.abs(x2 - x1) / 2f)
                            drawArc(
                                arcColor,
                                startAngle = 180f, sweepAngle = 180f, useCenter = false,
                                topLeft = Offset((x1 + x2) / 2 - r, size.height / 2 - r),
                                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)
                            )
                            break
                        }
                    }
                    j++
                }
            }
            i++
        }
    }
    if (best.stemLoops.isNotEmpty()) {
        Text("发夹环（前 10）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        best.stemLoops.take(10).forEach {
            Text("· 环 ${it.start + 1}–${it.end + 1}（${it.end - it.start - 1} nt 环内）", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SangerView(record: SeqRecord, trace: com.spice.nativeapp.core.AbiReader.Trace?) {
    if (trace == null) {
        Text("尚未导入色谱。用「文件 → 导入测序色谱 (.ab1)」载入后在此查看峰图并做克隆验证。", fontSize = 12.sp)
        return
    }
    var query by remember(trace) { mutableStateOf(trace.bases) }
    var winStart by remember(trace) { mutableStateOf(0) }
    Text("read ${trace.bases.length} nt（参考 ${record.length} bp）" +
        (if (trace.qualities.isNotEmpty()) " · 平均 Q ${"%.1f".format(trace.qualities.average())}" else ""), fontSize = 11.sp)

    // ── 四色峰图（DATA1-4 通道，SnapGene 配色 A绿 C蓝 G黑 T红）──
    if (trace.hasPeaks) {
        val window = 120
        val maxStart = (trace.bases.length - window).coerceAtLeast(0)
        winStart = winStart.coerceIn(0, maxStart)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            // 峰采样点窗口：peakIndices 是每个碱基的采样位置
            val p0 = trace.peakIndices.getOrNull(winStart) ?: 0
            val p1 = trace.peakIndices.getOrNull((winStart + window).coerceAtMost(trace.bases.length - 1))
                ?: trace.channels.values.firstOrNull()?.size ?: 0
            val points = (p1 - p0).coerceAtLeast(1)
            val maxAmp = trace.channels.values.maxOf { ch -> ch.max() }.coerceAtLeast(1)
            val colorsMap = mapOf(
                // 对齐 main SangerChromatogram CHANNEL_COLORS：G 用橙色而非黑（深底可读性）
                'A' to Color(0xFF2ECC71), 'T' to Color(0xFFE74C3C),
                'C' to Color(0xFF3498DB), 'G' to Color(0xFFF39C12)
            )
            drawRect(Color(0xFF030303), size = androidx.compose.ui.geometry.Size(size.width, size.height))
            for ((ch, data) in trace.channels) {
                val c = colorsMap[ch] ?: continue
                val stepX = size.width / points
                var k = p0
                var prevY = 0f
                while (k < minOf(p1, data.size)) {
                    val x = (k - p0) * stepX
                    val y = size.height - (data[k].toFloat() / maxAmp) * (size.height * 0.92f)
                    if (k > p0) drawLine(c, Offset(x - stepX, prevY), Offset(x, y), 1.2f)
                    prevY = y
                    k++
                }
            }
            // 碱基标注
            var bi = winStart
            while (bi < minOf(winStart + window, trace.bases.length)) {
                val pi = trace.peakIndices.getOrNull(bi) ?: break
                if (pi in p0..p1) {
                    val x = (pi - p0) * (size.width / points)
                    val b = trace.bases[bi]
                    drawCircle(colorsMap[b] ?: Color.Gray, 2.5f, Offset(x, size.height - 4f))
                }
                bi++
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { winStart = (winStart - window).coerceAtLeast(0) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("← 前") }
            Text("${winStart + 1}–${minOf(winStart + window, trace.bases.length)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { winStart = (winStart + window).coerceAtMost(maxStart) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("后 →") }
        }
    } else {
        Text("（该文件无峰通道数据，仅展示碱基与比对）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    HorizontalDivider()

    // ── 一键克隆验证（对齐 main 的 validateClone 语义）──
    var validate by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { validate = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("一键克隆验证") }
    }
    if (validate && query.length >= 20) {
        val cds = record.features.filter { it.type.equals("CDS", true) }.map { CloneValidation.CdsFeature(it.name, it.start - 1, it.end - 1) }
        val v = remember(record.sequence, query) { CloneValidation.validateClone(record.sequence, query, cds) }
        Text(
            (if (v.passed) "✓ " else "✗ ") + v.verdict,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (v.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            "一致性 ${"%.2f".format(v.identityPercent)}% · 覆盖 ${"%.1f".format(v.coveragePercent)}%" +
                (if (v.rcQueryUsed) " · 已反向互补重比对" else ""),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        v.warnings.forEach { Text("⚠ $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        if (v.variants.isNotEmpty()) {
            Text("变异效应（前 24）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            v.variants.take(24).forEach { ve ->
                val color = when (ve.effect) {
                    "nonsense", "frameshift", "stop_lost" -> MaterialTheme.colorScheme.error
                    "missense" -> Color(0xFFB45309)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "· ${ve.refPos} ${ve.refBase}→${ve.queryBase ?: "—"} [${ve.effect}] ${ve.detail}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = color
                )
            }
        } else {
            Text("未检出变异。", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }

    HorizontalDivider()
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.uppercase().filter { c -> c in "ACGTN" } },
        label = { Text("测序 read（可编辑/修剪后重新验证）") },
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
    }
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
    val seq = record.sequence
    val frames = remember(seq) { Bio.sixFrames(seq) }
    Text("六框翻译（各取前 60 aa）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    frames.forEach { (label, protein) ->
        Text("$label：${protein.take(60)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    HorizontalDivider()

    // ── 高级基因组学工具舱（对齐 main 的 genomics.ts）──
    val capped = seq.take(60000) // 移动端性能保护
    val tfbs = remember(capped) { GenomicsTools.predictTfbsMotifs(capped) }
    val promoters = remember(capped) { GenomicsTools.predictPromoters(capped) }
    val cpg = remember(capped) { GenomicsTools.detectCpGIslands(capped) }
    val repeats = remember(capped) { GenomicsTools.detectTandemRepeats(capped) }
    val crispr = remember(capped) { GenomicsTools.detectCrisprArrays(capped) }
    Text("调控与重复元件", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text("TFBS 基序 ${tfbs.size} 个 · σ70 启动子 ${promoters.size} 个 · CpG 岛 ${cpg.size} 个 · 串联重复 ${repeats.size} 处 · CRISPR array ${crispr.size} 个" +
        (if (capped.length < seq.length) "（已截前 60 kb）" else ""), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    tfbs.take(8).forEach { Text("· ${it.name}  ${it.pos + 1}(${it.strand})  ${it.matchSeq}", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    promoters.take(5).forEach { Text("· 启动子 -35/-10  ${it.pos + 1}  分数 ${it.score}  ${it.sequence}", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    cpg.take(5).forEach { Text("· CpG 岛 ${it.start + 1}–${it.end + 1}（${it.length} bp，GC ${it.gcContent}%，O/E ${it.obsExpRatio}）", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    repeats.take(8).forEach { Text("· 串联重复 ${it.start + 1}–${it.end + 1}  (${it.pattern})×${it.repeatCount}", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    crispr.take(3).forEach { c ->
        Text("· CRISPR ${c.start + 1}–${c.end + 1}  DR ${c.repeatCount}× · spacer ${c.spacers.size} · 置信度 ${c.confidence}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
    HorizontalDivider()

    // ── 蛋白级预测：信号肽 / 跨膜 / RBS ──
    val genes = remember(capped) { GenomicsTools.predictProkaryoticGenes(capped, 90) }
    Text("原核基因预测（双链三框 + RBS 加分）：${genes.size} 个 ORF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    genes.take(6).forEach { g ->
        Text(
            "· ${g.id} ${g.start + 1}..${g.end + 1}(${g.strand}) ${g.startCodon}→${g.stopCodon} ${g.translation.length} aa · 分数 ${g.score}${if (g.hasRbsMatch) " · 含RBS" else ""}",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
    val topGene = genes.firstOrNull()
    if (topGene != null) {
        val signal = remember(topGene) { GenomicsTools.predictSignalPeptide(topGene.translation) }
        val tms = remember(topGene) { GenomicsTools.predictTransmembraneHelices(topGene.translation) }
        val rbs = remember(topGene) { GenomicsTools.calculateRbsStrength(capped, topGene.start) }
        Text("最长 ORF 蛋白特征（${topGene.id}，${topGene.translation.length} aa）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("信号肽：${signal.details}" + (if (signal.hasSignal) "（切割位点 ~${signal.cleavagePos}，分数 ${signal.score}）" else ""), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("跨膜螺旋：${if (tms.isEmpty()) "无" else tms.joinToString("；") { "${it.start + 1}–${it.end + 1}（KD ${it.averageHydro}）" }}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            "RBS（Salis 模型）：TIR ${rbs.tirScore} · ΔG(SD) ${rbs.deltaGSD} · 间隔 ${rbs.spacerLength} bp · SD 序列 ${rbs.sdOverhangSequence.ifEmpty { "—" }}",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
    HorizontalDivider()
    Text("GC/AT 偏斜（窗 1000/步 100）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val skew = remember(capped) { GenomicsTools.calculateSkew(capped, "gc") }
    if (skew.isEmpty()) {
        Text("序列短于一个窗口。", fontSize = 10.sp)
    } else {
        val primary = MaterialTheme.colorScheme.primary
        val outline = MaterialTheme.colorScheme.outline
        Text("范围 ${"%.3f".format(skew.minOf { it.skew })} .. ${"%.3f".format(skew.maxOf { it.skew })}（${skew.size} 点）", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val maxAbs = maxOf(0.05, skew.maxOf { kotlin.math.abs(it.skew) }).toFloat()
            drawLine(outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            val stepX = size.width / (skew.size - 1).coerceAtLeast(1)
            skew.forEachIndexed { i, pt ->
                val x = i * stepX
                val y = size.height / 2 - (pt.skew / maxAbs).toFloat() * (size.height / 2 - 4f)
                if (i > 0) {
                    val py = size.height / 2 - (skew[i - 1].skew / maxAbs).toFloat() * (size.height / 2 - 4f)
                    drawLine(primary, Offset(x - stepX, py), Offset(x, y), 1.5f)
                }
            }
        }
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
    var msa by remember { mutableStateOf<MultipleAlign.MultiAlignment?>(null) }
    OutlinedTextField(
        value = extra,
        onValueChange = { extra = it },
        label = { Text("额外序列（每行一条，≤6 条）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3
    )
    val extras = remember(extra) {
        extra.split("\n").map { it.uppercase().filter { c -> c.isLetter() } }.filter { it.length > 10 }.take(6)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                msa = MultipleAlign.multipleAlign(
                    listOf("参考" to record.sequence.take(2000)) + extras.mapIndexed { i, s -> "seq${i + 1}" to s.take(2000) }
                )
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("多序列比对") }
        OutlinedButton(
            onClick = {
                onRecordChange(record.copy(description = "比对一致性 ${"%.1f".format((msa?.identity ?: 0.0) * 100)}%（${msa?.width ?: 0} 列）"))
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("写入一致性到备注") }
    }
    msa?.let { m -> MsaGrid(m) }
}

/** 共享 MSA 网格渲染：逐行着色（共识一致亮色 / 不一致暗色 / 缺口灰）+ 逐列保守性条带 */
@Composable
internal fun MsaGrid(m: MultipleAlign.MultiAlignment) {
    Text(
        "比对完成：${m.rows.size} 条 × ${m.width} 列 · 总体一致性 ${"%.1f".format(m.identity * 100)}%",
        fontSize = 12.sp, fontWeight = FontWeight.Bold
    )
    // 网格渲染：逐行着色（与共识不一致的列标暗色，缺口灰色）
    val matchColor = MaterialTheme.colorScheme.primary
    val mismatchColor = MaterialTheme.colorScheme.error
    val consensusColor = MaterialTheme.colorScheme.tertiary
    val cols = 60
    val blocks = (m.width + cols - 1) / cols
    val blockRange = if (blocks > 4) 0..3 else 0 until blocks.coerceAtLeast(1)
    if (blocks > 4) Text("（序列较长，仅展示前 ${cols * 4} 列）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (blk in blockRange) {
        val from = blk * cols
        val to = minOf(from + cols, m.width)
        Text("列 ${from + 1}–$to", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        m.rows.forEach { row ->
            val slice = row.alignedSeq.substring(from, to)
            Text(
                buildAnnotatedString {
                    if (row.isRef) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("参考 ".padEnd(6)) }
                    else append("seq${m.rows.indexOf(row)} ".padEnd(6))
                    slice.forEachIndexed { i, ch ->
                        val col = when {
                            ch == '-' -> MaterialTheme.colorScheme.outlineVariant
                            ch == m.consensus.getOrNull(from + i) && m.conservation.getOrNull(from + i) != null && m.conservation[from + i] >= 1.0 -> matchColor
                            else -> mismatchColor
                        }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = col)) { append(ch) }
                    }
                },
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Text(
            buildAnnotatedString {
                append("共识 ".padEnd(6))
                m.consensus.substring(from, to).forEachIndexed { i, ch ->
                    val cons = m.conservation.getOrNull(from + i) ?: 0.0
                    withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = if (cons >= 1.0) matchColor else if (cons >= 0.5) consensusColor else mismatchColor
                        )
                    ) { append(ch) }
                }
            },
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        HorizontalDivider()
    }
    // 保守性条带
    val consColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        if (m.width == 0) return@Canvas
        val stepX = size.width / m.width
        m.conservation.forEachIndexed { i, c ->
            drawRect(
                consColor.copy(alpha = (0.15 + c * 0.85).toFloat()),
                androidx.compose.ui.geometry.Offset(i * stepX, size.height * (1f - c.toFloat())),
                androidx.compose.ui.geometry.Size(stepX + 0.5f, size.height * c.toFloat())
            )
        }
    }
    Text("↑ 逐列保守性（深色 = 全列一致）", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 导入比对文件（对齐 main 的 handleAlignmentFile）：6 格式自动检测 → multipleAlign → MSA 网格 */
@Composable
private fun ImportAlignView(clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msa by remember { mutableStateOf<MultipleAlign.MultiAlignment?>(null) }
    var importedInfo by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            err = null
            importedInfo = null
            msa = null
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            }.getOrNull() ?: ""
            if (text.isBlank()) { err = "未读到文件内容"; return@rememberLauncherForActivityResult }
            val imported = AlignmentIO.parse(text)
            if (imported.rows.isEmpty()) {
                err = "未解析到序列数据"
                return@rememberLauncherForActivityResult
            }
            // 过滤空序列行并对齐 main：跑渐进式 MSA 后展示
            val seqs = imported.rows.filter { it.seq.isNotBlank() }
                .mapIndexed { i, r -> (if (r.name.isBlank()) "seq$i" else r.name) to r.seq }
            msa = MultipleAlign.multipleAlign(seqs)
            importedInfo = "已导入 ${imported.format.uppercase()} 比对文件：${seqs.size} 条序列"
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { launcher.launch("*/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("选择比对文件…")
        }
    }
    Text(
        "支持 Clustal / FASTA / NEXUS / PHYLIP / MSF / Stockholm（自动检测格式），导入后执行渐进式多序列比对。",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    importedInfo?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
    msa?.let { m ->
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(m.rows.joinToString("\n") { ">${it.name}\n${it.alignedSeq}" })) },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("复制比对结果 (FASTA)", fontSize = 11.sp) }
        MsaGrid(m)
    }
}
