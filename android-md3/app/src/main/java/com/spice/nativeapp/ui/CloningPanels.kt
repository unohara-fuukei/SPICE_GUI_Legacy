package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.spice.nativeapp.core.jsNum
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Calculators
import com.spice.nativeapp.core.Cloning
import com.spice.nativeapp.core.CloningMethods
import com.spice.nativeapp.core.CloningPlus
import com.spice.nativeapp.core.CodeSign
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.PrimerAnalysis
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
    CALC("分子克隆与 PCR 反应计算器", "按 Tm 与摩尔比推算 8 类反应配方与动力学参数"),
    LIMS("实验室引物库存管理舱", "本地保存引物记录"),
    QC("合成基因表达预检舱", "GC/同聚物/重复/酶切位点体检"),
    NGS("NGS 质控与原核基因预测", "读长/质量/GC 与 ORF 预测"),
    EXTENSION("重组克隆与标记引物设计", "同源臂 + 标签引物"),
    CODESIGN("协同设计舱", "序列-结构-表达多目标 Pareto 协同优化"),
    ANNEAL("退火寡核苷酸", "两条 oligo 的配对区与 Tm"),
    SILENT("沉默突变", "同义密码子替换，蛋白不变"),
    REVTRANS("反向翻译", "蛋白 → DNA（E. coli 偏好）"),
    PRIMERMOD("引物 5' 修饰", "加酶切位点/保护碱基"),
    ENDMOD("DNA 末端编辑", "添加 5'/3' 突出端"),
    FUSION("融合阅读框检查", "两段 CDS 拼接是否同框"),
    SLIPPAGE("核糖体滑移翻译", "滑移 motif 扫描"),
    BATCH("批量筛选与批处理", "多序列批量酶切/引物/导出（BatchEngine）")
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
    // 可拖拽浮动窗口（对齐 main floating-panel：克隆工具面板悬浮在页面之上，[X] 关闭）
    Box {
        FloatingPanel(
            title = panel.title,
            onDismiss = onDismiss,
            width = 350.dp,
            maxHeight = 520.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                CloningPanel.BATCH -> BatchPanel(record, clipboard)
                CloningPanel.CODESIGN -> CodesignPanel(record, onRecordChange)
            }
            }
        }
    }
}

@Composable
private fun Block(label: String, text: String = "") {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    if (text.isNotEmpty()) Text(
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
        ) { Text(com.spice.nativeapp.core.I18n.t("复制")) }
        if (apply != null) {
            Button(onClick = apply, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("应用到编辑器")) }
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
    Block(com.spice.nativeapp.core.I18n.t("方案"), text)
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
        com.spice.nativeapp.core.I18n.tf("全库 {v1} 组 / {v2} 个同义名（REBASE {v3} 条）· 当前序列命中 {v4}/{v5}", Enzymes.groupCount, Enzymes.nameCount, Enzymes.rebaseCount, results.size, Enzymes.search(query, 30).size),
        fontSize = 11.sp
    )
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("搜索酶名 / 别名 / 识别位点（如 EcoRI、ggwcc）")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (results.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("该筛选下当前序列无位点；放宽关键词或换酶。"), fontSize = 11.sp)
    results.forEach { (name, cuts) ->
        val enz = Enzymes.byName(name)
        Text(
            com.spice.nativeapp.core.I18n.tf("{v1} ({v2}) → {v3} [共 {v4} 切点]", name, enz?.site?.uppercase() ?: "", cuts.joinToString(", "), cuts.size),
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
        Text(com.spice.nativeapp.core.I18n.t("单酶切片段（常用酶）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        label = { Text(com.spice.nativeapp.core.I18n.t("CDS（可粘贴）"), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    )
    Text(com.spice.nativeapp.core.I18n.tf("优化后长度 {v1} bp · GC {v2}%", optimized.length, "%.1f".format(Bio.gcContent(optimized))), fontSize = 11.sp)
    Block(com.spice.nativeapp.core.I18n.t("优化序列（已规避 BsaI/BsmBI）"), optimized)
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
        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text(com.spice.nativeapp.core.I18n.t("起点")) }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text(com.spice.nativeapp.core.I18n.t("终点")) }, modifier = Modifier.weight(1f))
    }
    val p = pair
    if (p == null) {
        Text(com.spice.nativeapp.core.I18n.t("区间无效或过短"), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    } else {
        Block(com.spice.nativeapp.core.I18n.t("正向引物 F"), "${p.forward}\nTm ${"%.1f".format(p.tmF)}℃ · GC ${"%.1f".format(p.gcF)}%")
        Block(com.spice.nativeapp.core.I18n.t("反向引物 R"), "${p.reverse}\nTm ${"%.1f".format(p.tmR)}℃ · GC ${"%.1f".format(p.gcR)}%")
        Text("产物 ${p.productLength} bp${if (p.warnings.isEmpty()) " · 参数合格" else " · ${p.warnings.joinToString("；")}"}", fontSize = 11.sp)
        val fa = remember(p.forward) { PrimerAnalysis.analyze(p.forward, productTm = null) }
        val ra = remember(p.reverse) { PrimerAnalysis.analyze(p.reverse, productTm = null) }
        Text(com.spice.nativeapp.core.I18n.t("精细分析（SantaLucia 最近邻 / NEB 近似）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            com.spice.nativeapp.core.I18n.tf("F：Tm(NN) {v1}℃ · Tm(NEB) {v2}℃ · GC {v3}% · 3′稳定性 {v4}", "%.1f".format(fa.tmNearestNeighbor), "%.1f".format(fa.tmNeb), "%.1f".format(fa.gcPercent), fa.threePrimeStability),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        Text(
            com.spice.nativeapp.core.I18n.tf("R：Tm(NN) {v1}℃ · Tm(NEB) {v2}℃ · GC {v3}% · 3′稳定性 {v4}", "%.1f".format(ra.tmNearestNeighbor), "%.1f".format(ra.tmNeb), "%.1f".format(ra.gcPercent), ra.threePrimeStability),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        val cross = remember(p.forward, p.reverse) { PrimerAnalysis.dimer(p.forward, p.reverse) }
        Text(
            cross?.let { com.spice.nativeapp.core.I18n.tf("交叉二聚体：{v1}", it.detail) } ?: com.spice.nativeapp.core.I18n.t("交叉二聚体：未检出（<4 连续互补）"),
            fontSize = 10.sp
        )
        (fa.warnings + ra.warnings).distinct().take(6).forEach { w ->
            Text("⚠ $w", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
        }
        val ta = PrimerAnalysis.annealingTemp((fa.tmNeb + ra.tmNeb) / 2, fa.tmNeb)
        Text(com.spice.nativeapp.core.I18n.tf("建议退火温度 ≈ {v1}℃（0.3×Tm + 0.7×Tp − 14.9 近似）", "%.1f".format(ta)), fontSize = 10.sp)
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
        appendLine(com.spice.nativeapp.core.I18n.tf("模板：{v1}（{v2} bp）", record.name, record.length))
        appendLine(com.spice.nativeapp.core.I18n.tf("预期产物：{v1} bp", product))
        appendLine(com.spice.nativeapp.core.I18n.tf("退火温度建议：{v1}℃（Tm 均值 {v2}℃ − 5℃）", "%.1f".format(tm - 5), "%.1f".format(tm)))
        appendLine(com.spice.nativeapp.core.I18n.tf("延伸时间：{v1} s（Taq ~1 kb/min）", maxOf(30, (product / 1000.0 * 60).toInt())))
        appendLine(com.spice.nativeapp.core.I18n.tf("循环：95℃ 3 min → [95℃ 30 s / {v1}℃ 30 s / 72℃ {v2} s] × 30 → 72℃ 5 min", "%.0f".format(tm - 5), maxOf(30, (product / 1000.0 * 60).toInt())))
        appendLine(com.spice.nativeapp.core.I18n.t("若产物 >5 kb 或高 GC，改用 Q5/Phusion 并延长延伸时间"))
    }
    Block(com.spice.nativeapp.core.I18n.t("仿真参数"), text)
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
    Text(com.spice.nativeapp.core.I18n.t("1% 琼脂糖、1× TAE、5 V/cm 估算；迁移值越大条带越靠下"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Block(com.spice.nativeapp.core.I18n.t("预测条带"), rows.joinToString("\n"))
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
    OutlinedTextField(value = promoter, onValueChange = { promoter = it }, label = { Text(com.spice.nativeapp.core.I18n.t("启动子")) }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = rbs, onValueChange = { rbs = it }, label = { Text("RBS") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = cds, onValueChange = { cds = it }, label = { Text("CDS") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = term, onValueChange = { term = it }, label = { Text(com.spice.nativeapp.core.I18n.t("终止子")) }, modifier = Modifier.fillMaxWidth())
    Text(com.spice.nativeapp.core.I18n.tf("表达盒 {v1} bp", cassette.length), fontSize = 11.sp)
    Block(com.spice.nativeapp.core.I18n.t("拼接结果"), cassette)
    CopyRow(clipboard, cassette, apply = { onRecordChange(SeqRecord("expression_cassette", cassette, false, emptyList(), com.spice.nativeapp.core.I18n.t("表达盒"))) })
}

@Composable
private fun VectorPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var insert by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf("20") }
    val arms = remember(record.sequence, insert, arm) {
        if (insert.length < 40) null
        else Cloning.gibsonHomology(record.sequence, insert, arm.toIntOrNull() ?: 20)
    }
    var method by remember { mutableStateOf(CloningMethods.Method.NEBUILDER) }
    val sim = remember(record.sequence, insert, method, arm) {
        if (insert.length < 40) null
        else CloningMethods.simulate(
            method = method,
            vector = record,
            insert = com.spice.nativeapp.core.SeqRecord(
                name = "insert", sequence = insert.uppercase(), circular = false,
                features = emptyList(), description = ""
            ),
            overlapLength = arm.toIntOrNull()
        )
    }
    Text(com.spice.nativeapp.core.I18n.t("克隆方法（对应原版 8 种模拟）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CloningMethods.Method.values().forEach { m ->
            val on = m == method
            if (on) Button(onClick = { method = m }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m.label, fontSize = 11.sp) }
            else OutlinedButton(onClick = { method = m }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m.label, fontSize = 11.sp) }
        }
    }
    sim?.let {
        Text("产物 ${it.productLength} bp · 需同源臂 ${it.requiredOverlap} bp · 校验${if (it.verified) "通过" else "有警告"}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        it.steps.forEach { line -> Text("· $line", fontSize = 11.sp) }
        it.warnings.forEach { line -> Text("⚠ $line", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
    }
    OutlinedTextField(
        value = insert,
        onValueChange = { insert = it.uppercase().filter { c -> c in "ACGT" } },
        label = { Text(com.spice.nativeapp.core.I18n.t("插入片段序列")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3
    )
    OutlinedTextField(value = arm, onValueChange = { arm = it }, label = { Text(com.spice.nativeapp.core.I18n.t("同源臂长度")) }, modifier = Modifier.fillMaxWidth())
    val a = arms
    if (a == null) Text(com.spice.nativeapp.core.I18n.t("插入片段至少 40 bp"), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else {
        Block(com.spice.nativeapp.core.I18n.t("骨架侧同源臂"), a.first)
        Block(com.spice.nativeapp.core.I18n.t("插入侧同源臂"), a.second)
        CopyRow(clipboard, "${a.first}\n${a.second}")
    }
}

@Composable
private fun CrisprPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val guides = remember(record.sequence) { CloningPlus.crisprGuides(record.sequence, 12) }
    Text(com.spice.nativeapp.core.I18n.tf("共 {v1} 条候选（NGG PAM，按 GC 与 3' 端偏好排序）", guides.size), fontSize = 11.sp)
    guides.forEach { g ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("${g.start}  ${g.protospacer}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(com.spice.nativeapp.core.I18n.tf("GC {v1}% · 打分 {v2} · PAM {v3}", "%.0f".format(g.gc), g.score, g.pam), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (guides.isNotEmpty()) CopyRow(clipboard, guides.joinToString("\n") { it.protospacer })
    Text(com.spice.nativeapp.core.I18n.t("脱靶预测（以当前序列作基因组，≤3 错配 + NGG PAM）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    if (guides.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("先在上方选一条 guide 再预测。"), fontSize = 11.sp)
    else {
        val offtargets = remember(record.sequence, guides.first().protospacer) {
            CloningPlus.predictOffTargets(record.sequence, guides.first().protospacer, 3)
        }
        Text(com.spice.nativeapp.core.I18n.tf("guide {v1} → {v2} 个潜在脱靶位点", guides.first().protospacer, offtargets.size), fontSize = 11.sp)
        offtargets.take(8).forEach { ot ->
            Text(
                "${ot.pos}  ${ot.seq}  ${ot.mismatches} mm  PAM ${ot.pam}" + if (ot.atThreePrime) com.spice.nativeapp.core.I18n.t("  ⚠3′错配") else "",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (offtargets.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("未发现 ≤3 错配的 NGG 位点（在本地序列范围内）。"), fontSize = 11.sp)
    }
}

@Composable
private fun CalcPanel() {
    // T7 (#67)：ReactionCalculators.svelte 1:1 — 8 子 tab（顺序/文案/默认值与 main 一致）。
    // 平台差异（披露）：HTML number input → OutlinedTextField；select → FilterChip 选项行；
    // table → 权重三列的等宽文本行；面板拖拽/关钮由外层 CloningPanelSheet 承载。
    val t = com.spice.nativeapp.core.I18n
    var calcTab by remember { mutableStateOf(0) } // 0..7: dilution/ligation/pcr/conc/gradient/gel/elisa/kinetics
    val tabs = listOf(
        t.t("母液稀释"), t.t("Ligation"), t.t("MasterMix"), t.t("浓度换算"),
        t.t("梯度PCR"), t.t("凝胶尺寸"), t.t("ELISA标曲"), t.t("酶动力学")
    )
    for (r in 0..1) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            for (c in 0..3) {
                val idx = r * 4 + c
                androidx.compose.material3.FilterChip(
                    selected = calcTab == idx, onClick = { calcTab = idx },
                    label = { Text(tabs[idx], fontSize = 10.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    when (calcTab) {
        0 -> RcDilutionTab(t)
        1 -> RcLigationTab(t)
        2 -> RcPcrTab(t)
        3 -> RcConcTab(t)
        4 -> RcGradientTab(t)
        5 -> RcGelTab(t)
        6 -> RcElisaTab(t)
        7 -> RcKineticsTab(t)
    }
}

private typealias I18nO = com.spice.nativeapp.core.I18n

@Composable
private fun RcSection(title: String) {
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun RcField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp, maxLines = 1) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        singleLine = true, modifier = modifier
    )
}

@Composable
private fun RcRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.primary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = FontFamily.Monospace)
    }
}

// TAB 1: 母液稀释（默认 100 / 10 / 50）
@Composable
private fun RcDilutionTab(t: I18nO) {
    var c1 by remember { mutableStateOf("100") }
    var c2 by remember { mutableStateOf("10") }
    var v2 by remember { mutableStateOf("50") }
    val dil = remember(c1, c2, v2) {
        Calculators.calculateDilution(c1.toDoubleOrNull() ?: 0.0, c2.toDoubleOrNull() ?: 0.0, v2.toDoubleOrNull() ?: 0.0)
    }
    RcSection(t.t("引物与试剂稀释配制 (C1 * V1 = C2 * V2):"))
    RcField(t.t("母液浓度 (uM / mM):"), c1, { c1 = it }, Modifier.fillMaxWidth())
    RcField(t.t("目标终浓度 (uM / mM):"), c2, { c2 = it }, Modifier.fillMaxWidth())
    RcField(t.t("目标终体积 (uL):"), v2, { v2 = it }, Modifier.fillMaxWidth())
    Text(t.t("稀释配料方案 (Recipe):"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    RcRow(t.t("加入母液体积 (Source):"), jsNum(dil.sourceVol) + " uL")
    RcRow(t.t("加入水或缓冲液体积 (H2O):"), jsNum(dil.waterVol) + " uL")
}

// TAB 2: Ligation（默认 5000 / 1000 / 100 / 3）
@Composable
private fun RcLigationTab(t: I18nO) {
    var vSize by remember { mutableStateOf("5000") }
    var iSize by remember { mutableStateOf("1000") }
    var vMass by remember { mutableStateOf("100") }
    var ratio by remember { mutableStateOf("3") }
    val insertMass = remember(vSize, iSize, vMass, ratio) {
        Calculators.calculateLigationMass(
            vSize.toIntOrNull() ?: 0, iSize.toIntOrNull() ?: 0,
            vMass.toDoubleOrNull() ?: 0.0, ratio.toDoubleOrNull() ?: 3.0
        )
    }
    RcSection(t.t("连接反应摩尔配料比 (Molar Ligation Ratio):"))
    RcField(t.t("载体大小 (Vector bp):"), vSize, { vSize = it }, Modifier.fillMaxWidth())
    RcField(t.t("片段大小 (Insert bp):"), iSize, { iSize = it }, Modifier.fillMaxWidth())
    RcField(t.t("加入载体质量 (ng):"), vMass, { vMass = it }, Modifier.fillMaxWidth())
    Text(t.t("推荐摩尔比 (Insert : Vector):"), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            "1" to "1 : 1", "3" to t.t("3 : 1 (推荐)"), "5" to "5 : 1", "10" to "10 : 1"
        ).forEach { (v, label) ->
            androidx.compose.material3.FilterChip(
                selected = ratio == v, onClick = { ratio = v },
                label = { Text(label, fontSize = 10.sp) }
            )
        }
    }
    Text(t.t("配方结论 (Ligation Mix):"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    RcRow(t.t("需要加入插入片段质量 (Insert):"), jsNum(insertMass) + " ng")
    Text("* " + t.t("推荐使用 T4 DNA 连接酶在 16°C 下连接 1-2 小时。"), fontSize = 8.sp)
}

// TAB 3: PCR Master Mix（默认 8 孔 / 50 µL / Q5）
@Composable
private fun RcPcrTab(t: I18nO) {
    var wells by remember { mutableStateOf("8") }
    var vol by remember { mutableStateOf("50") }
    var poly by remember { mutableStateOf("Q5") }
    val rows = remember(wells, vol, poly) {
        Calculators.calculatePcrMasterMix(
            wells.toIntOrNull() ?: 1, vol.toDoubleOrNull() ?: 50.0,
            when (poly) {
                "Phusion" -> Calculators.Polymerase.PHUSION
                "Taq" -> Calculators.Polymerase.TAQ
                else -> Calculators.Polymerase.Q5
            }
        )
    }
    RcSection(t.t("PCR Master Mix 体系自动配料配比:"))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RcField(t.t("反应孔数 (Reactions):"), wells, { wells = it }, Modifier.weight(1f))
        RcField(t.t("单管总体积 (uL):"), vol, { vol = it }, Modifier.weight(1f))
    }
    Text(t.t("聚合酶体系 (Polymerase):"), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Q5" to "NEB Q5 High-Fidelity", "Phusion" to "Thermo Phusion", "Taq" to "Standard Taq").forEach { (v, label) ->
            androidx.compose.material3.FilterChip(
                selected = poly == v, onClick = { poly = v },
                label = { Text(label, fontSize = 10.sp, maxLines = 1) }
            )
        }
    }
    // 配方表：组分 Component | 单孔 uL | 预混液 (µL):
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t.t("组分 Component"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(2.2f))
        Text(t.t("单孔 uL"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(t.t("预混液 (µL):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1.2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.component, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2.2f))
            Text(jsNum(r.volumePerWell), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(jsNum(r.totalVolume), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1.2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
    Text("* " + t.t("Master Mix 总体积已包含 10% 的备用移液损耗余量。"), fontSize = 8.sp)
}

// TAB 4: 浓度换算（默认 50 / ng→nM / 3000 bp / ds）
@Composable
private fun RcConcTab(t: I18nO) {
    var value by remember { mutableStateOf("50") }
    var dir by remember { mutableStateOf("ng_to_nM") }
    var lengthBp by remember { mutableStateOf("3000") }
    var isDs by remember { mutableStateOf(true) }
    val res = remember(value, dir, lengthBp, isDs) {
        Calculators.convertDnaConcentration(value.toDoubleOrNull() ?: 0.0, dir, lengthBp.toIntOrNull() ?: 0, isDs)
    }
    RcSection(t.t("DNA / RNA 浓度与摩尔质量换算:"))
    Text(t.t("换算方向 (Direction):"), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.FilterChip(selected = dir == "ng_to_nM", onClick = { dir = "ng_to_nM" }, label = { Text("ng/µL ➔ nM", fontSize = 10.sp) })
        androidx.compose.material3.FilterChip(selected = dir == "nM_to_ng", onClick = { dir = "nM_to_ng" }, label = { Text("nM ➔ ng/µL", fontSize = 10.sp) })
    }
    RcField(t.t("输入数值 (Value):"), value, { value = it }, Modifier.fillMaxWidth())
    Text(t.t("核酸链型 (Chain Type):"), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.FilterChip(selected = isDs, onClick = { isDs = true }, label = { Text(t.t("双链 DNA (dsDNA)"), fontSize = 10.sp) })
        androidx.compose.material3.FilterChip(selected = !isDs, onClick = { isDs = false }, label = { Text(t.t("单链 RNA/ssDNA"), fontSize = 10.sp) })
    }
    RcField(t.t("序列长度 (Length bp):"), lengthBp, { lengthBp = it }, Modifier.fillMaxWidth())
    Text(t.t("换算结果 (Result):"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    RcRow(t.t("估算摩尔质量 (MW):"), jsNum(res.molWt) + " g/mol")
    if (dir == "ng_to_nM") RcRow(t.t("摩尔浓度 (nM):"), jsNum(res.nM) + " nM")
    else RcRow(t.t("质量浓度 (ng/µL):"), jsNum(res.ngPerUl) + " ng/µL")
}

// TAB 5: 梯度 PCR（默认 58.5 / 59.2；面板实际传 50..66）
@Composable
private fun RcGradientTab(t: I18nO) {
    var p1 by remember { mutableStateOf("58.5") }
    var p2 by remember { mutableStateOf("59.2") }
    val grad = remember(p1, p2) {
        Calculators.optimizeGradientPcr(p1.toDoubleOrNull() ?: 58.5, p2.toDoubleOrNull() ?: 59.2, 50.0, 66.0)
    }
    RcSection(t.t("12孔物理梯度 PCR 退火温度分布推荐:"))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RcField(t.t("引物 1 Tm (°C):"), p1, { p1 = it }, Modifier.weight(1f))
        RcField(t.t("引物 2 Tm (°C):"), p2, { p2 = it }, Modifier.weight(1f))
    }
    Text(
        t.tf("最佳退火温度范围: {v1} (推荐孔位: {v2} 号孔)", grad.optimalTempRange, grad.bestColumn.toString()),
        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
    )
    // 表头：孔位 | 设定温度 | 状态效率
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.t("孔位"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        Text(t.t("设定温度"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        Text(t.t("状态效率"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
    }
    grad.columns.forEach { c ->
        val color = when (c.efficiencyRating) {
            "Optimal" -> MaterialTheme.colorScheme.primary
            "Good" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            Modifier.fillMaxWidth().then(if (c.column == grad.bestColumn) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(t.tf("{v1} 号孔", c.column.toString()), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(jsNum(c.temperature) + " °C", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(c.efficiencyRating, fontSize = 10.sp, color = color)
        }
    }
}

// TAB 6: 凝胶尺寸估算（默认 20/10000 · 45/5000 · 80/2000 · 未知 55）
@Composable
private fun RcGelTab(t: I18nO) {
    var d1 by remember { mutableStateOf("20") }; var s1 by remember { mutableStateOf("10000") }
    var d2 by remember { mutableStateOf("45") }; var s2 by remember { mutableStateOf("5000") }
    var d3 by remember { mutableStateOf("80") }; var s3 by remember { mutableStateOf("2000") }
    var unk by remember { mutableStateOf("55") }
    val est = remember(d1, s1, d2, s2, d3, s3, unk) {
        val std = listOf(
            Calculators.GelStandard(d1.toDoubleOrNull() ?: 0.0, s1.toIntOrNull() ?: 0),
            Calculators.GelStandard(d2.toDoubleOrNull() ?: 0.0, s2.toIntOrNull() ?: 0),
            Calculators.GelStandard(d3.toDoubleOrNull() ?: 0.0, s3.toIntOrNull() ?: 0)
        )
        Calculators.estimateGelBandSizes(std, listOf(unk.toDoubleOrNull() ?: 0.0))
    }
    RcSection(t.t("半对数琼脂糖凝胶未知条带分子量估算:"))
    Text(t.t("1. 设定 Marker 标定标准曲线 (y = m*x + b):"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("带 1:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = d1, onValueChange = { d1 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("泳动 mm"), fontSize = 9.sp) })
        OutlinedTextField(value = s1, onValueChange = { s1 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("大小 bp"), fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("带 2:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = d2, onValueChange = { d2 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("泳动 mm"), fontSize = 9.sp) })
        OutlinedTextField(value = s2, onValueChange = { s2 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("大小 bp"), fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("带 3:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = d3, onValueChange = { d3 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("泳动 mm"), fontSize = 9.sp) })
        OutlinedTextField(value = s3, onValueChange = { s3 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("大小 bp"), fontSize = 9.sp) })
    }
    Text(t.t("2. 输入未知目标条带泳动位移:"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    RcField(t.t("未知条带迁移距离 (mm / px):"), unk, { unk = it }, Modifier.fillMaxWidth())
    Text(t.t("估算结果 (MW Estimate):"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    RcRow(t.t("标准曲线拟合精度 (R²):"), jsNum(est.second), MaterialTheme.colorScheme.tertiary)
    RcRow(t.t("估计条带分子量:"), jsNum(est.first.first().toDouble()) + " bp")
}

// TAB 7: ELISA 标曲（默认 1000/2.4 · 250/0.85 · 62.5/0.22 · 未知 1.15）
@Composable
private fun RcElisaTab(t: I18nO) {
    var c1 by remember { mutableStateOf("1000") }; var o1 by remember { mutableStateOf("2.4") }
    var c2 by remember { mutableStateOf("250") }; var o2 by remember { mutableStateOf("0.85") }
    var c3 by remember { mutableStateOf("62.5") }; var o3 by remember { mutableStateOf("0.22") }
    var unkOd by remember { mutableStateOf("1.15") }
    val res = remember(c1, o1, c2, o2, c3, o3, unkOd) {
        Calculators.fitElisaStandardCurve(
            listOf(
                Calculators.ElisaPoint(c1.toDoubleOrNull() ?: 0.0, o1.toDoubleOrNull() ?: 0.0),
                Calculators.ElisaPoint(c2.toDoubleOrNull() ?: 0.0, o2.toDoubleOrNull() ?: 0.0),
                Calculators.ElisaPoint(c3.toDoubleOrNull() ?: 0.0, o3.toDoubleOrNull() ?: 0.0)
            ),
            listOf(unkOd.toDoubleOrNull() ?: 0.0)
        )
    }
    RcSection(t.t("ELISA 四参数 Logistic (4PL) 拟合标曲:"))
    Text(t.t("1. 录入标准品浓度与对应 OD 值:"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("标准品 1:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = c1, onValueChange = { c1 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("浓度 (pg/mL)"), fontSize = 9.sp) })
        OutlinedTextField(value = o1, onValueChange = { o1 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("OD 值"), fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("标准品 2:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = c2, onValueChange = { c2 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("浓度 (pg/mL)"), fontSize = 9.sp) })
        OutlinedTextField(value = o2, onValueChange = { o2 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("OD 值"), fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("标准品 3:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = c3, onValueChange = { c3 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("浓度 (pg/mL)"), fontSize = 9.sp) })
        OutlinedTextField(value = o3, onValueChange = { o3 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text(t.t("OD 值"), fontSize = 9.sp) })
    }
    Text(t.t("2. 输入测得未知样品的 OD 读数:"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    RcField(t.t("未知待测孔 OD 值:"), unkOd, { unkOd = it }, Modifier.fillMaxWidth())
    Text(t.t("拟合参数:"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("EC50 (c): " + jsNum(res.c) + " | Hill Slope (b): " + jsNum(res.b), fontSize = 8.sp)
        Text("R²: " + jsNum(res.rSquared), fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.tertiary)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.t("未知孔算得终浓度:"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(jsNum(res.fittedConcentrations.first()) + " pg/mL", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

// TAB 8: 酶动力学（默认 10/4.2 · 2.5/2.1 · 0.5/0.65）
@Composable
private fun RcKineticsTab(t: I18nO) {
    var s1 by remember { mutableStateOf("10") }; var v1s by remember { mutableStateOf("4.2") }
    var s2 by remember { mutableStateOf("2.5") }; var v2s by remember { mutableStateOf("2.1") }
    var s3 by remember { mutableStateOf("0.5") }; var v3s by remember { mutableStateOf("0.65") }
    val res = remember(s1, v1s, s2, v2s, s3, v3s) {
        Calculators.calculateEnzymeKinetics(
            listOf(
                Calculators.KineticsPoint(s1.toDoubleOrNull() ?: 0.0, v1s.toDoubleOrNull() ?: 0.0),
                Calculators.KineticsPoint(s2.toDoubleOrNull() ?: 0.0, v2s.toDoubleOrNull() ?: 0.0),
                Calculators.KineticsPoint(s3.toDoubleOrNull() ?: 0.0, v3s.toDoubleOrNull() ?: 0.0)
            )
        )
    }
    RcSection(t.t("米氏动力学常数双倒数 (Lineweaver-Burk) 拟合:"))
    Text(t.t("1. 测定底物浓度 [S] 及初速度 V:"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("孔 1:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s1, onValueChange = { s1 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("[S] (mM)", fontSize = 9.sp) })
        OutlinedTextField(value = v1s, onValueChange = { v1s = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("v (uM/min)", fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("孔 2:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s2, onValueChange = { s2 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("[S] (mM)", fontSize = 9.sp) })
        OutlinedTextField(value = v2s, onValueChange = { v2s = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("v (uM/min)", fontSize = 9.sp) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(t.t("孔 3:"), fontSize = 10.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(value = s3, onValueChange = { s3 = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("[S] (mM)", fontSize = 9.sp) })
        OutlinedTextField(value = v3s, onValueChange = { v3s = it }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), singleLine = true, modifier = Modifier.weight(1.2f), placeholder = { Text("v (uM/min)", fontSize = 9.sp) })
    }
    Text(t.t("双倒数线性拟合动力学常数:"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    RcRow(t.t("最大反应速率 Vmax:"), jsNum(res.vMax) + " uM/min")
    RcRow(t.t("米氏常数 Km:"), jsNum(res.kM) + " mM")
    Text(t.tf("回归系数 R²: {v1}", jsNum(res.rSquared)), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}


@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun BatchPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    // ── 批量筛选与批处理（对齐 main 的 BatchEngine）──
    var fasta by remember { mutableStateOf("") }
    var enzymeNames by remember { mutableStateOf("EcoRI, HindIII") }
    var relStart by remember { mutableStateOf("1") }
    var relEnd by remember { mutableStateOf("100") }
    var digestOut by remember { mutableStateOf<List<com.spice.nativeapp.core.BatchEngine.BatchDigestResult>>(emptyList()) }
    var primerOut by remember { mutableStateOf<List<com.spice.nativeapp.core.BatchEngine.BatchPrimerResult>>(emptyList()) }
    var exportOut by remember { mutableStateOf<List<com.spice.nativeapp.core.BatchEngine.BatchExportResult>>(emptyList()) }

    OutlinedTextField(
        value = fasta,
        onValueChange = { fasta = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("多序列 FASTA（>名称 换行 序列；留空则用当前序列）")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4
    )
    val seqs = remember(fasta, record.sequence) {
        val parsed = com.spice.nativeapp.core.BatchEngine.parseFastas(fasta)
        if (parsed.isNotEmpty()) parsed
        else listOf(com.spice.nativeapp.core.BatchEngine.BatchSeq(record.name, record.sequence, record.circular))
    }
    Text(com.spice.nativeapp.core.I18n.tf("待处理序列 {v1} 条", seqs.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = enzymeNames, onValueChange = { enzymeNames = it }, label = { Text(com.spice.nativeapp.core.I18n.t("酶（逗号分隔）")) }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = relStart, onValueChange = { relStart = it }, label = { Text(com.spice.nativeapp.core.I18n.t("引物区间起")) }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = relEnd, onValueChange = { relEnd = it }, label = { Text(com.spice.nativeapp.core.I18n.t("引物区间止")) }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { digestOut = com.spice.nativeapp.core.BatchEngine.batchDigest(seqs, enzymeNames.split(",")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("批量酶切") }
        Button(onClick = { primerOut = com.spice.nativeapp.core.BatchEngine.batchDesignPrimers(seqs, relStart.toIntOrNull() ?: 1, relEnd.toIntOrNull() ?: 100) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("批量引物")) }
        OutlinedButton(onClick = { exportOut = com.spice.nativeapp.core.BatchEngine.batchExport(seqs, "genbank") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("批量导出")) }
    }

    if (digestOut.isNotEmpty()) {
        SectionTitle(com.spice.nativeapp.core.I18n.t("批量酶切结果"))
        digestOut.forEach { r ->
            Text(
                com.spice.nativeapp.core.I18n.tf("{v1}：{v2} 个切点", r.sequenceName, r.cutsitesCount) +
                    (if (r.cutsites.isNotEmpty()) "（${r.cutsites.take(6).joinToString { "${it.enzyme}@${it.pos}" }}${if (r.cutsites.size > 6) " …" else ""}）" else ""),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
    if (primerOut.isNotEmpty()) {
        SectionTitle(com.spice.nativeapp.core.I18n.t("批量引物结果"))
        primerOut.forEach { r ->
            val p = r.primers
            if (p == null) Text(com.spice.nativeapp.core.I18n.tf("{v1}：区间无效", r.sequenceName), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            else Text(
                "${r.sequenceName}：\n  F ${p.forward}（Tm ${"%.1f".format(p.tmF)}）\n  R ${p.reverse}（Tm ${"%.1f".format(p.tmR)}）",
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
    if (exportOut.isNotEmpty()) {
        SectionTitle(com.spice.nativeapp.core.I18n.t("批量导出（GenBank）"))
        Text(
            exportOut.joinToString("\n\n") { it.content }.take(2000),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(exportOut.joinToString("\n\n") { it.content }))
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("复制全部导出内容")) }
    }
}

@Composable
private fun LimsPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val pair = remember(record.sequence) { Cloning.designPrimers(record.sequence, 1, minOf(record.length, 800)) }
    var saved by remember { mutableStateOf(listOf<String>()) }
    Text(com.spice.nativeapp.core.I18n.t("当前设计引物"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val p = pair
    if (p == null) Text(com.spice.nativeapp.core.I18n.t("无法设计引物"), fontSize = 12.sp)
    else {
        Block("F", p.forward)
        Block("R", p.reverse)
        Button(
            onClick = {
                saved = saved + "${record.name}_F ${p.forward} Tm ${"%.1f".format(p.tmF)}" +
                    "\n${record.name}_R ${p.reverse} Tm ${"%.1f".format(p.tmR)}"
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text(com.spice.nativeapp.core.I18n.t("保存到库存（本次会话）")) }
    }
    if (saved.isNotEmpty()) {
        Block(com.spice.nativeapp.core.I18n.tf("库存（{v1}）", saved.size), saved.joinToString("\n"))
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { clipboard.setText(AnnotatedString(saved.joinToString("\n"))) }) { Text(com.spice.nativeapp.core.I18n.t("导出全部")) }
    }
}

@Composable
private fun QcPanel(record: SeqRecord) {
    val sites = remember { Enzymes.common.take(8).mapNotNull { n -> Enzymes.byName(n)?.let { n to it.site } } }
    val issues = remember(record.sequence) { CloningPlus.qc(record.sequence, sites) }
    Text(com.spice.nativeapp.core.I18n.tf("体检项 {v1} 条（高/中/低）", issues.size), fontSize = 11.sp)
    issues.take(20).forEach {
        Text("[$it.level] ${it.text}", fontSize = 11.sp)
    }
    if (issues.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("未发现明显问题"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NgsPanel() {
    var fastq by remember { mutableStateOf("") }
    val stats = remember(fastq) { CloningPlus.ngsStats(fastq) }
    OutlinedTextField(
        value = fastq,
        onValueChange = { fastq = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("粘贴 FASTQ 片段（每 4 行一条 read）")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4
    )
    Block(com.spice.nativeapp.core.I18n.t("统计"), com.spice.nativeapp.core.I18n.tf("reads {v1}\n平均质量 Q{v2}\nGC {v3}%", stats.first, "%.1f".format(stats.second), "%.1f".format(stats.third)))
    val orfs = remember(fastq) { if (fastq.length > 50) CloningPlus.findOrfs(fastq.filter { it in "ACGTacgt" }, 300) else emptyList() }
    Block(com.spice.nativeapp.core.I18n.t("ORF 预测"), if (orfs.isEmpty()) com.spice.nativeapp.core.I18n.t("无 ≥300 bp 的 ORF") else orfs.take(5).joinToString("\n") { "${it.start}..${it.end} (${it.aa} aa, ${it.strand})" })
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
    OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text(com.spice.nativeapp.core.I18n.t("5' 标签序列")) }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = arm, onValueChange = { arm = it }, label = { Text(com.spice.nativeapp.core.I18n.t("同源臂长度")) }, modifier = Modifier.fillMaxWidth())
    Block(com.spice.nativeapp.core.I18n.t("正向标记引物"), fwd)
    Block(com.spice.nativeapp.core.I18n.t("反向标记引物"), rev)
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
    if (r == null) Text(com.spice.nativeapp.core.I18n.t("两条 oligo 各至少 6 nt"), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else Block(com.spice.nativeapp.core.I18n.t("配对结果"), "互补区 ${r.overlap} nt · Tm ${"%.1f".format(r.tm)}℃ · 5' 突出 ${r.overhang5} nt\n${if (r.ok) "可直接退火" else "建议延长互补区或调整盐浓度"}")
}

@Composable
private fun SilentPanel(record: SeqRecord, onRecordChange: (SeqRecord) -> Unit) {
    var idx by remember { mutableStateOf("1") }
    val res = remember(record.sequence, idx) {
        CloningPlus.silentMutate(record.sequence, (idx.toIntOrNull() ?: 1) - 1)
    }
    OutlinedTextField(value = idx, onValueChange = { idx = it }, label = { Text(com.spice.nativeapp.core.I18n.t("密码子序号")) }, modifier = Modifier.fillMaxWidth())
    val r = res
    if (r == null) Text(com.spice.nativeapp.core.I18n.t("序号超出 CDS 范围或无可替换同义密码子"), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
    else {
        Block(com.spice.nativeapp.core.I18n.t("替换说明"), r.second)
        Button(onClick = { onRecordChange(record.copy(sequence = r.first)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("应用突变")) }
    }
}

@Composable
private fun RevTransPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    val protein = remember(record.sequence) { Bio.translate(record.sequence) }
    var aa by remember(record.name) { mutableStateOf(protein.take(300)) }
    val dna = remember(aa) { CloningPlus.backTranslate(aa) }
    OutlinedTextField(value = aa, onValueChange = { aa = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("蛋白序列")) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    Text("DNA ${dna.length} bp · GC ${"%.1f".format(Bio.gcContent(dna))}%", fontSize = 11.sp)
    Block(com.spice.nativeapp.core.I18n.t("反向翻译"), dna)
    CopyRow(clipboard, dna, apply = { onRecordChange(record.copy(sequence = dna, name = "${record.name}_rt")) })
}

@Composable
private fun PrimerModPanel(clipboard: androidx.compose.ui.platform.ClipboardManager) {
    var primer by remember { mutableStateOf("") }
    var tail by remember { mutableStateOf("GGATCC") }
    var protect by remember { mutableStateOf(true) }
    val out = remember(primer, tail, protect) { CloningPlus.addTail(primer, tail, protect) }
    OutlinedTextField(value = primer, onValueChange = { primer = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("引物")) }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = tail, onValueChange = { tail = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("5' 附加（酶切位点）")) }, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { protect = !protect }) {
            Text(if (protect) com.spice.nativeapp.core.I18n.t("保护碱基：开") else com.spice.nativeapp.core.I18n.t("保护碱基：关"))
        }
    }
    Block(com.spice.nativeapp.core.I18n.t("修饰后引物"), out)
    CopyRow(clipboard, out)
}

@Composable
private fun EndModPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    var overhang by remember { mutableStateOf("ACGT") }
    var five by remember { mutableStateOf(true) }
    val out = remember(record.sequence, overhang, five) { CloningPlus.addOverhang(record.sequence, overhang, five) }
    OutlinedTextField(value = overhang, onValueChange = { overhang = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("突出端序列")) }, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { five = !five }) { Text(if (five) com.spice.nativeapp.core.I18n.t("5' 端（点击切换）") else com.spice.nativeapp.core.I18n.t("3' 端")) }
    }
    Block(com.spice.nativeapp.core.I18n.t("末端序列"), out.take(60) + if (out.length > 60) "…" else "")
    CopyRow(clipboard, out, apply = { onRecordChange(record.copy(sequence = out)) })
}

@Composable
private fun FusionPanel(record: SeqRecord) {
    var a by remember { mutableStateOf(record.sequence.take(300)) }
    var b by remember { mutableStateOf("") }
    val res = remember(a, b) { if (a.length >= 3 && b.length >= 3) CloningPlus.fusionCheck(a, b) else null }
    OutlinedTextField(value = a, onValueChange = { a = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("上游片段")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    OutlinedTextField(value = b, onValueChange = { b = it.uppercase() }, label = { Text(com.spice.nativeapp.core.I18n.t("下游片段")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    val r = res
    if (r == null) Text(com.spice.nativeapp.core.I18n.t("两段序列各至少 3 bp"), fontSize = 12.sp)
    else Text(if (r.first) "✓ ${r.third}" else "⚠ ${r.third}", fontSize = 12.sp, color = if (r.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun SlippagePanel(record: SeqRecord) {
    val sites = remember(record.sequence) { CloningPlus.slippageSites(record.sequence) }
    Text(com.spice.nativeapp.core.I18n.tf("滑移 motif 命中 {v1} 处（≥6×A/T 或 ≥4×G/C 视为高风险）", sites.size), fontSize = 11.sp)
    sites.forEach { Text("${it.first}: ${it.second}", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
    if (sites.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("未检测到明显滑移位点"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
}

/** 协同设计舱（对齐 main 的 CodesignParetoPanel + Rust pareto_codesign_evaluate） */
@Composable
private fun CodesignPanel(record: SeqRecord, onRecordChange: (SeqRecord) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 自动定位 CDS 特征（无 CDS 时取整段可整除 3 的部分，与 main 一致）
    val cds = remember(record) {
        val f = record.features.firstOrNull { it.type.uppercase() == "CDS" }
        if (f != null) f.start - 1 to (f.end - 1).coerceAtMost(record.length - 1)
        else 0 to (record.length / 3) * 3 - 1
    }
    var host by remember { mutableStateOf(CodeSign.Host.ECOLI) }
    var candidates by remember { mutableStateOf(
        "Variant_V1,V15A,-4.5\nVariant_V2,I32L + L45M,-1.2\nVariant_V3,V15A + I32L,-5.8\nVariant_V4,V15A + L45M + I32L,-6.2\nVariant_V5,I32L + L45M + L50M,-2.1"
    ) }
    var variants by remember { mutableStateOf<List<CodeSign.Variant>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    Block(com.spice.nativeapp.core.I18n.t("CDS 区间"), com.spice.nativeapp.core.I18n.tf("{v1}..{v2}（{v3} bp，{v4} 密码子）", cds.first + 1, cds.second + 1, cds.second - cds.first + 1, (cds.second - cds.first + 1) / 3))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CodeSign.Host.values().forEach { h ->
            androidx.compose.material3.FilterChip(
                selected = host == h,
                onClick = { host = h },
                label = { Text(h.label, fontSize = 12.sp) }
            )
        }
    }
    OutlinedTextField(
        value = candidates,
        onValueChange = { candidates = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("候选清单（ID, 突变, ΔE kcal/mol）")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 5,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    )
    Button(onClick = {
        err = null
        variants = emptyList()
        val muts = CodeSign.parseCandidates(candidates)
        if (muts.isEmpty()) { err = com.spice.nativeapp.core.I18n.t("未解析到候选（每行：ID, V15A + I32L, -4.5）"); return@Button }
        try {
            variants = CodeSign.evaluate(record.sequence, cds.first, cds.second, host, muts)
        } catch (e: Exception) { err = e.message }
    }) { Text(com.spice.nativeapp.core.I18n.t("运行 Pareto 协同优化")) }

    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary) }

    if (variants.isNotEmpty()) {
        val paretoCount = variants.count { it.isParetoOptimal }
        Block(com.spice.nativeapp.core.I18n.t("评估结果"), com.spice.nativeapp.core.I18n.tf("{v1} 个变体 · Pareto 前沿 {v2} 个（ΔE 越低越好，CAI 越高越好）", variants.size, paretoCount))
        variants.forEach { v ->
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (v.isParetoOptimal) 0.5f else 0.25f)
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            (if (v.isParetoOptimal) "★ " else "") + v.id,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (v.isParetoOptimal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(v.mutations, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "ΔE %+.2f kcal/mol · CAI %.3f · mRNA 5'ΔG %.1f".format(v.proteinEnergyDelta, v.cai, v.mrnaDeltaG),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row {
                        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            onRecordChange(record.copy(sequence = v.updatedDna, name = "${record.name}_${v.id}"))
                        }) { Text(com.spice.nativeapp.core.I18n.t("应用 DNA 变体"), fontSize = 11.sp) }
                        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            clipboard.setText(AnnotatedString(v.updatedDna))
                        }) { Text(com.spice.nativeapp.core.I18n.t("复制序列"), fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
