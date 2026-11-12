package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Cloning
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord

/** 原版基因页 CLONING 菜单的全部条目 */
enum class CloningPanel(val title: String, val hint: String) {
    DESIGN("分子克隆设计舱", "按目标给出载体/插入/酶/验证的完整方案"),
    ENZYMES("限制性内切酶切位点", "列出当前序列上的切点与片段长度"),
    CODON("密码子优化", "按 E. coli 偏好重编码 CDS，可选规避酶切位点"),
    PRIMERS("扩增引物设计表", "按区间设计一对引物并给 Tm/GC 检查"),
    PCR("PCR 仿真模拟器", "产物长度、退火温度与延伸时间建议"),
    GEL("琼脂糖凝胶电泳", "按酶切片段预测条带位置"),
    CASSETTE("表达盒构建器", "启动子 + RBS + CDS + 终止子拼接"),
    VECTOR("载体模板组装", "骨架与插入片段的同源臂设计"),
    CRISPR("CRISPR 基因编辑设计舱", "SpCas9 NGG PAM 扫描与打分"),
    CALC("分子克隆与 PCR 计算器", "连接摩尔比、稀释、PCR 体系"),
    LIMS("实验室引物库存", "本地保存引物记录"),
    QC("合成基因表达预检", "GC/同聚物/重复/酶切位点体检"),
    NGS("NGS 质控与基因预测", "读长/质量/GC 与 ORF 预测"),
    EXTENSION("重组克隆与标记引物", "同源臂 + 标签引物"),
    ANNEAL("退火寡核苷酸", "两条 oligo 的配对区与 Tm"),
    SILENT("沉默突变", "同义密码子替换，蛋白不变"),
    REVTRANS("反向翻译", "蛋白 → DNA（E. coli 偏好）"),
    PRIMERMOD("引物 5' 修饰", "加酶切位点/保护碱基"),
    ENDMOD("DNA 末端编辑", "添加 5'/3' 突出端"),
    FUSION("融合阅读框检查", "两段 CDS 拼接是否同框"),
    SLIPPAGE("核糖体滑移翻译", "滑移 motif 扫描")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloningPanelSheet(
    panel: CloningPanel,
    record: SeqRecord,
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
                CloningPanel.DESIGN -> DesignPanel(record)
                CloningPanel.ENZYMES -> EnzymeSitesPanel(record)
                CloningPanel.CODON -> CodonPanel(record, clipboard, onRecordChange)
                CloningPanel.PRIMERS -> PrimerPanel(record, clipboard)
                CloningPanel.PCR -> PcrPanel(record)
                CloningPanel.GEL -> GelPanel(record)
                CloningPanel.CASSETTE -> CassettePanel(clipboard, onRecordChange)
                CloningPanel.VECTOR -> VectorPanel(record, clipboard)
                CloningPanel.CRISPR -> CrisprPanel(record, clipboard)
                CloningPanel.CALC -> CalcPanel()
                CloningPanel.LIMS -> LimsPanel(record, clipboard)
                CloningPanel.QC -> QcPanel(record)
                CloningPanel.NGS -> NgsPanel()
                CloningPanel.EXTENSION -> ExtensionPanel(record, clipboard)
                CloningPanel.ANNEAL -> AnnealPanel()
                CloningPanel.SILENT -> SilentPanel(record, onRecordChange)
                CloningPanel.REVTRANS -> RevTransPanel(record, clipboard, onRecordChange)
                CloningPanel.PRIMERMOD -> PrimerModPanel(clipboard)
                CloningPanel.ENDMOD -> EndModPanel(record, clipboard, onRecordChange)
                CloningPanel.FUSION -> FusionPanel(record)
                CloningPanel.SLIPPAGE -> SlippagePanel(record)
            }
        }
    }
}

@Composable
private fun Block(label: String, text: String) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(
        text,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun CopyRow(clipboard: androidx.compose.ui.platform.ClipboardManager, text: String, apply: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("复制") }
        if (apply != null) {
            Button(onClick = apply, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用到编辑器") }
        }
    }
}

@Composable
private fun DesignPanel(record: SeqRecord) {
    val orfs = remember(record.sequence) { CloningPlus.findOrfs(record.sequence, 200) }
    val text = remember(record) {
        buildString {
            appendLine("1. 载体：当前 ${record.name}（${record.length} bp，${if (record.circular) "环状" else "线性"}）")
            appendLine("2. 插入片段候选 ORF：${orfs.size} 个")
            orfs.take(3).forEach { appendLine("   · ${it.start}..${it.end}（${it.aa} aa，链 ${if (it.strand > 0) "+" else "-"}）") }
            appendLine("3. 推荐酶：${Enzymes.common.take(4).joinToString(" / ")}（需确认载体多克隆位点无内部位点）")
            appendLine("4. 装配：Gibson 同源臂 20–25 bp，或 BsaI Golden Gate（4 nt 突出端）")
            appendLine("5. 验证：菌落 PCR 正向引物落在插入片段内、反向落在骨架内")
        }
    }
    Block("方案", text)
}

@Composable
private fun EnzymeSitesPanel(record: SeqRecord) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, record.sequence, record.circular) {
        Enzymes.search(query, 30).mapNotNull { enz ->
            val cuts = Bio.cutSites(record.sequence, enz, record.circular)
            if (cuts.isEmpty()) null else enz.name to cuts
        }
    }
    Text(
        "全库 ${Enzymes.groupCount} 组 / ${Enzymes.nameCount} 个同义名（REBASE ${Enzymes.rebaseCount} 条）· 当前序列命中 ${results.size}/${Enzymes.search(query, 30).size}",
        fontSize = 11.sp
    )
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("搜索酶名 / 别名 / 识别位点（如 EcoRI、ggwcc）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (results.isEmpty()) Text("该筛选下当前序列无位点；放宽关键词或换酶。", fontSize = 11.sp)
    results.forEach { (name, cuts) ->
        val enz = Enzymes.byName(name)
        Text(
            "$name (${enz?.site?.uppercase() ?: ""}) → ${cuts.joinToString(", ")} [共 ${cuts.size} 切点]",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
    val fragments = remember(record.sequence, record.circular) {
        Enzymes.common.mapNotNull { name ->
            val enz = Enzymes.byName(name) ?: return@mapNotNull null
            val cuts = Bio.cutSites(record.sequence, enz, record.circular)
            if (cuts.size < 1) null else {
                val sorted = cuts.sorted()
                val lens = if (record.circular) {
                    sorted.indices.map { i ->
                        val next = sorted[(i + 1) % sorted.size]
                        if (next > sorted[i]) next - sorted[i] else record.length - sorted[i] + next
                    }
                } else sorted.zipWithNext { a, b -> b - a }
                "$name 单酶切 → ${lens.joinToString("/")} bp"
            }
        }
    }
    if (fragments.isNotEmpty()) {
        Text("单酶切片段（常用酶）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        fragments.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun CodonPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    val defaultCds = remember(record.sequence) {
        CloningPlus.findOrfs(record.sequence, 150).firstOrNull()?.let {
            val sub = if (it.strand > 0) record.sequence.substring(it.start - 1, it.end)
            else Bio.revComp(record.sequence.substring(it.start - 1, it.end))
            sub
        } ?: record.sequence
    }
    var cds by remember(record.name) { mutableStateOf(defaultCds) }
    val optimized = remember(cds) { CloningPlus.backTranslate(Bio.translate(cds), listOf("GGTCTC", "GAAGAC")) }
    OutlinedTextField(
        value = cds,
        onValueChange = { cds = it.uppercase().filter { c -> c in "ACGT" } },
        label = { Text("CDS（可粘贴）", fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    )
    Text("优化后长度 ${optimized.length} bp · GC ${"%.1f".format(Bio.gcContent(optimized))}%", fontSize = 11.sp)
    Block("优化序列（已规避 BsaI/BsmBI）", optimized)
    CopyRow(clipboard, optimized, apply = {
        onRecordChange(record.copy(sequence = optimized, name = "${record.name}_opt"))
    })
}

@Composable
private fun PrimerPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var start by remember { mutableStateOf("1") }
    var end by remember { mutableStateOf(record.length.toString()) }
    val pair = remember(start, end) {
        Cloning.designPrimers(record.sequence, start.toIntOrNull() ?: 1, end.toIntOrNull() ?: record.length)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("起点") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("终点") }, modifier = Modifier.weight(1f))
    }
    val p = pair
    if (p == null) {
        Text("区间无效或过短", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    } else {
        Block("正向引物 F", "${p.forward}\nTm ${"%.1f".format(p.tmF)}℃ · GC ${"%.1f".format(p.gcF)}%")
        Block("反向引物 R", "${p.reverse}\nTm ${"%.1f".format(p.tmR)}℃ · GC ${"%.1f".format(p.gcR)}%")
        Text("产物 ${p.productLength} bp${if (p.warnings.isEmpty()) " · 参数合格" else " · ${p.warnings.joinToString("；")}"}", fontSize = 11.sp)
        CopyRow(clipboard, "${p.forward}\n${p.reverse}")
    }
}

@Composable
private fun PcrPanel(record: SeqRecord) {
    val tm = remember(record.sequence) {
        val p = Cloning.designPrimers(record.sequence, 1, minOf(record.length, 800))
        p?.let { (it.tmF + it.tmR) / 2 } ?: 58.0
    }
    val product = remember(record.sequence) { minOf(record.length, 800) }
    val text = buildString {
        appendLine("模板：${record.name}（${record.length} bp）")
        appendLine("预期产物：$product bp")
        appendLine("退火温度建议：${"%.1f".format(tm - 5)}℃（Tm 均值 ${"%.1f".format(tm)}℃ − 5℃）")
        appendLine("延伸时间：${maxOf(30, (product / 1000.0 * 60).toInt())} s（Taq ~1 kb/min）")
        appendLine("循环：95℃ 3 min → [95℃ 30 s / ${"%.0f".format(tm - 5)}℃ 30 s / 72℃ ${maxOf(30, (product / 1000.0 * 60).toInt())} s] × 30 → 72℃ 5 min")
        appendLine("若产物 >5 kb 或高 GC，改用 Q5/Phusion 并延长延伸时间")
    }
    Block("仿真参数", text)
}

@Composable
private fun GelPanel(record: SeqRecord) {
    val rows = remember(record.sequence, record.circular) {
        Enzymes.common.take(6).mapNotNull { name ->
            val enz = Enzymes.byName(name) ?: return@mapNotNull null
            val cuts = Bio.cutSites(record.sequence, enz, record.circular)
            if (cuts.isEmpty()) null else {
                val sorted = cuts.sorted()
                val lens = if (record.circular) sorted.indices.map { i ->
                    val next = sorted[(i + 1) % sorted.size]
                    if (next > sorted[i]) next - sorted[i] else record.length - sorted[i] + next
                } else (sorted + record.length).zipWithNext { a, b -> b - a }
                val mig = lens.map { (1.0 / kotlin.math.log10(it.toDouble() + 1)) * 100 }.map { "%.0f".format(it) }
                "$name：${lens.joinToString("/")} bp（相对迁移 ${mig.joinToString("/")}）"
            }
        }
    }
    Text("1% 琼脂糖、1× TAE、5 V/cm 估算；迁移值越大条带越靠下", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Block("预测条带", rows.joinToString("\n"))
}

@Composable
private fun CassettePanel(clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    var promoter by remember { mutableStateOf("TTTTTAACCCA") }
    var rbs by remember { mutableStateOf("AGGAGG") }
    var cds by remember { mutableStateOf("ATG") }
    var term by remember { mutableStateOf("CTAGGATCCTAGG") }
    val cassette = remember(promoter, rbs, cds, term) {
        CloningPlus.expressionCassette(promoter, rbs, cds, term)
    }
    OutlinedTextField(value = promoter, onValueChange = { promoter = it }, label = { Text("启动子") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = rbs, onValueChange = { rbs = it }, label = { Text("RBS") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = cds, onValueChange = { cds = it }, label = { Text("CDS") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = term, onValueChange = { term = it }, label = { Text("终止子") }, modifier = Modifier.fillMaxWidth())
    Text("表达盒 ${cassette.length} bp", fontSize = 11.sp)
    Block("拼接结果", cassette)
    CopyRow(clipboard, cassette, apply = { onRecordChange(SeqRecord("expression_cassette", cassette, false, emptyList(), "表达盒")) })
}

@Composable
private fun VectorPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var insert by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf("20") }
    val arms = remember(record.sequence, insert, arm) {
        if (insert.length < 40) null
        else Cloning.gibsonHomology(record.sequence, insert, arm.toIntOrNull() ?: 20)
    }
    OutlinedTextField(
        value = insert,
        onValueChange = { insert = it.uppercase().filter { c -> c in "ACGT" } },
        label = { Text("插入片段序列") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3
    )
    OutlinedTextField(value = arm, onValueChange = { arm = it }, label = { Text("同源臂长度") }, modifier = Modifier.fillMaxWidth())
    val a = arms
    if (a == null) Text("插入片段至少 40 bp", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else {
        Block("骨架侧同源臂", a.first)
        Block("插入侧同源臂", a.second)
        CopyRow(clipboard, "${a.first}\n${a.second}")
    }
}

@Composable
private fun CrisprPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val guides = remember(record.sequence) { CloningPlus.crisprGuides(record.sequence, 12) }
    Text("共 ${guides.size} 条候选（NGG PAM，按 GC 与 3' 端偏好排序）", fontSize = 11.sp)
    guides.forEach { g ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("${g.start}  ${g.protospacer}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("GC ${"%.0f".format(g.gc)}% · 打分 ${g.score} · PAM ${g.pam}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (guides.isNotEmpty()) CopyRow(clipboard, guides.joinToString("\n") { it.protospacer })
}

@Composable
private fun CalcPanel() {
    var vectorNg by remember { mutableStateOf("100") }
    var vectorBp by remember { mutableStateOf("3000") }
    var insertBp by remember { mutableStateOf("1000") }
    var c1 by remember { mutableStateOf("100") }
    var c2 by remember { mutableStateOf("10") }
    var v2 by remember { mutableStateOf("100") }

    val insertNg = remember(vectorNg, vectorBp, insertBp) {
        CloningPlus.ligationInsertNg(vectorNg.toDoubleOrNull() ?: 0.0, vectorBp.toIntOrNull() ?: 0, insertBp.toIntOrNull() ?: 0)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = vectorNg, onValueChange = { vectorNg = it }, label = { Text("载体 ng") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = vectorBp, onValueChange = { vectorBp = it }, label = { Text("载体 bp") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = insertBp, onValueChange = { insertBp = it }, label = { Text("插入 bp") }, modifier = Modifier.weight(1f))
    }
    Block("连接反应", "插入片段需 ${"%.1f".format(insertNg)} ng（摩尔比 1:3）")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = c1, onValueChange = { c1 = it }, label = { Text("母液 C1") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = c2, onValueChange = { c2 = it }, label = { Text("目标 C2") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = v2, onValueChange = { v2 = it }, label = { Text("体积 V2 µL") }, modifier = Modifier.weight(1f))
    }
    Block("稀释", "取 ${"%.1f".format(CloningPlus.dilution(c1.toDoubleOrNull() ?: 1.0, c2.toDoubleOrNull() ?: 0.0, v2.toDoubleOrNull() ?: 0.0))} µL 母液，补至 ${v2} µL")
    Block("20 µL PCR 体系", CloningPlus.pcrMix(20.0).joinToString("\n") { "${it.first}：${"%.1f".format(it.second)} µL" })
}

@Composable
private fun LimsPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val pair = remember(record.sequence) { Cloning.designPrimers(record.sequence, 1, minOf(record.length, 800)) }
    var saved by remember { mutableStateOf(listOf<String>()) }
    Text("当前设计引物", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val p = pair
    if (p == null) Text("无法设计引物", fontSize = 12.sp)
    else {
        Block("F", p.forward)
        Block("R", p.reverse)
        Button(
            onClick = {
                saved = saved + "${record.name}_F ${p.forward} Tm ${"%.1f".format(p.tmF)}" +
                    "\n${record.name}_R ${p.reverse} Tm ${"%.1f".format(p.tmR)}"
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("保存到库存（本次会话）") }
    }
    if (saved.isNotEmpty()) {
        Block("库存（${saved.size}）", saved.joinToString("\n"))
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { clipboard.setText(AnnotatedString(saved.joinToString("\n"))) }) { Text("导出全部") }
    }
}

@Composable
private fun QcPanel(record: SeqRecord) {
    val sites = remember { Enzymes.common.take(8).mapNotNull { n -> Enzymes.byName(n)?.let { n to it.site } } }
    val issues = remember(record.sequence) { CloningPlus.qc(record.sequence, sites) }
    Text("体检项 ${issues.size} 条（高/中/低）", fontSize = 11.sp)
    issues.take(20).forEach {
        Text("[$it.level] ${it.text}", fontSize = 11.sp)
    }
    if (issues.isEmpty()) Text("未发现明显问题", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NgsPanel() {
    var fastq by remember { mutableStateOf("") }
    val stats = remember(fastq) { CloningPlus.ngsStats(fastq) }
    OutlinedTextField(
        value = fastq,
        onValueChange = { fastq = it },
        label = { Text("粘贴 FASTQ 片段（每 4 行一条 read）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4
    )
    Block("统计", "reads ${stats.first}\n平均质量 Q${"%.1f".format(stats.second)}\nGC ${"%.1f".format(stats.third)}%")
    val orfs = remember(fastq) { if (fastq.length > 50) CloningPlus.findOrfs(fastq.filter { it in "ACGTacgt" }, 300) else emptyList() }
    Block("ORF 预测", if (orfs.isEmpty()) "无 ≥300 bp 的 ORF" else orfs.take(5).joinToString("\n") { "${it.start}..${it.end} (${it.aa} aa, ${it.strand})" })
}

@Composable
private fun ExtensionPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var tag by remember { mutableStateOf("CACCATCACCATCACCAC") } // 6xHis
    var arm by remember { mutableStateOf("20") }
    val len = arm.toIntOrNull() ?: 20
    val fwd = remember(record.sequence, tag, len) {
        CloningPlus.addTail(record.sequence.take(len), tag, false)
    }
    val rev = remember(record.sequence, tag, len) {
        CloningPlus.addTail(Bio.revComp(record.sequence.takeLast(len)), "", false)
    }
    OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("5' 标签序列") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = arm, onValueChange = { arm = it }, label = { Text("同源臂长度") }, modifier = Modifier.fillMaxWidth())
    Block("正向标记引物", fwd)
    Block("反向标记引物", rev)
    CopyRow(clipboard, "$fwd\n$rev")
}

@Composable
private fun AnnealPanel() {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val res = remember(a, b) { if (a.length >= 6 && b.length >= 6) CloningPlus.anneal(a, b) else null }
    OutlinedTextField(value = a, onValueChange = { a = it.uppercase() }, label = { Text("Oligo A") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = b, onValueChange = { b = it.uppercase() }, label = { Text("Oligo B") }, modifier = Modifier.fillMaxWidth())
    val r = res
    if (r == null) Text("两条 oligo 各至少 6 nt", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else Block("配对结果", "互补区 ${r.overlap} nt · Tm ${"%.1f".format(r.tm)}℃ · 5' 突出 ${r.overhang5} nt\n${if (r.ok) "可直接退火" else "建议延长互补区或调整盐浓度"}")
}

@Composable
private fun SilentPanel(record: SeqRecord, onRecordChange: (SeqRecord) -> Unit) {
    var idx by remember { mutableStateOf("1") }
    val res = remember(record.sequence, idx) {
        CloningPlus.silentMutate(record.sequence, (idx.toIntOrNull() ?: 1) - 1)
    }
    OutlinedTextField(value = idx, onValueChange = { idx = it }, label = { Text("密码子序号") }, modifier = Modifier.fillMaxWidth())
    val r = res
    if (r == null) Text("序号超出 CDS 范围或无可替换同义密码子", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else {
        Block("替换说明", r.second)
        Button(onClick = { onRecordChange(record.copy(sequence = r.first)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用突变") }
    }
}

@Composable
private fun RevTransPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    val protein = remember(record.sequence) { Bio.translate(record.sequence) }
    var aa by remember(record.name) { mutableStateOf(protein.take(300)) }
    val dna = remember(aa) { CloningPlus.backTranslate(aa) }
    OutlinedTextField(value = aa, onValueChange = { aa = it.uppercase() }, label = { Text("蛋白序列") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Text("DNA ${dna.length} bp · GC ${"%.1f".format(Bio.gcContent(dna))}%", fontSize = 11.sp)
    Block("反向翻译", dna)
    CopyRow(clipboard, dna, apply = { onRecordChange(record.copy(sequence = dna, name = "${record.name}_rt")) })
}

@Composable
private fun PrimerModPanel(clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var primer by remember { mutableStateOf("") }
    var tail by remember { mutableStateOf("GGATCC") }
    var protect by remember { mutableStateOf(true) }
    val out = remember(primer, tail, protect) { CloningPlus.addTail(primer, tail, protect) }
    OutlinedTextField(value = primer, onValueChange = { primer = it.uppercase() }, label = { Text("引物") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = tail, onValueChange = { tail = it.uppercase() }, label = { Text("5' 附加（酶切位点）") }, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { protect = !protect }) {
            Text(if (protect) "保护碱基：开" else "保护碱基：关")
        }
    }
    Block("修饰后引物", out)
    CopyRow(clipboard, out)
}

@Composable
private fun EndModPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    var overhang by remember { mutableStateOf("ACGT") }
    var five by remember { mutableStateOf(true) }
    val out = remember(record.sequence, overhang, five) { CloningPlus.addOverhang(record.sequence, overhang, five) }
    OutlinedTextField(value = overhang, onValueChange = { overhang = it.uppercase() }, label = { Text("突出端序列") }, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { five = !five }) { Text(if (five) "5' 端（点击切换）" else "3' 端") }
    }
    Block("末端序列", out.take(60) + if (out.length > 60) "…" else "")
    CopyRow(clipboard, out, apply = { onRecordChange(record.copy(sequence = out)) })
}

@Composable
private fun FusionPanel(record: SeqRecord) {
    var a by remember { mutableStateOf(record.sequence.take(300)) }
    var b by remember { mutableStateOf("") }
    val res = remember(a, b) { if (a.length >= 3 && b.length >= 3) CloningPlus.fusionCheck(a, b) else null }
    OutlinedTextField(value = a, onValueChange = { a = it.uppercase() }, label = { Text("上游片段") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    OutlinedTextField(value = b, onValueChange = { b = it.uppercase() }, label = { Text("下游片段") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    val r = res
    if (r == null) Text("两段序列各至少 3 bp", fontSize = 12.sp)
    else Text(if (r.first) "✓ ${r.third}" else "⚠ ${r.third}", fontSize = 12.sp, color = if (r.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun SlippagePanel(record: SeqRecord) {
    val sites = remember(record.sequence) { CloningPlus.slippageSites(record.sequence) }
    Text("滑移 motif 命中 ${sites.size} 处（≥6×A/T 或 ≥4×G/C 视为高风险）", fontSize = 11.sp)
    sites.forEach { Text("${it.first}: ${it.second}", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    if (sites.isEmpty()) Text("未检测到明显滑移位点", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}
