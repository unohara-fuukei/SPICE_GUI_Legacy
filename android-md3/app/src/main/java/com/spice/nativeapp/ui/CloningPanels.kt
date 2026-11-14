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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import com.spice.nativeapp.core.SyntheticGeneQc
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Calculators
import com.spice.nativeapp.core.CasType
import com.spice.nativeapp.core.GrnaCandidate
import com.spice.nativeapp.core.designHdrDonor
import com.spice.nativeapp.core.designPegRna
import com.spice.nativeapp.core.findGrnaCandidates
import com.spice.nativeapp.core.CloningExt
import com.spice.nativeapp.core.CloningSim
import com.spice.nativeapp.core.EnzymesVe
import com.spice.nativeapp.core.I18n
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
    SIMULATOR("分子克隆仿真舱", "9 类连接重组方法仿真与产物导入物理图谱"),
    CASSETTE("表达盒构建器", "启动子 + RBS + CDS + 终止子拼接"),
    VECTOR("载体模板组装", "骨架与插入片段的同源臂设计"),
    CRISPR("CRISPR 基因编辑设计舱", "4 种 Cas 体系 gRNA 扫描打分 + HDR/pegRNA/碱基编辑设计"),
    CALC("分子克隆与 PCR 反应计算器", "按 Tm 与摩尔比推算 8 类反应配方与动力学参数"),
    LIMS("实验室引物库存管理舱", "本地保存引物记录"),
    QC("合成基因表达预检舱", "GC/同聚物/重复/酶切位点体检"),
    NGS("NGS 质控与原核基因预测", "读长/质量/GC 与 ORF 预测"),
    EXTENSION("重组克隆与标记引物设计", "Golden Gate 组装/重组酶克隆/SSR 育种标记/SNP 分型/RFLP 多态"),
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
    onRecordChange: (SeqRecord) -> Unit,
    onEnzymesAdopt: (List<String>) -> Unit = {},
    onLog: (List<String>) -> Unit = {}
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
                CloningPanel.SIMULATOR -> CloningSimulatorPanel(record, onRecordChange, onEnzymesAdopt, onLog)
                CloningPanel.CASSETTE -> CassettePanel(clipboard, onRecordChange)
                CloningPanel.VECTOR -> VectorPanel(record, clipboard)
                CloningPanel.CRISPR -> CrisprPanel(record, clipboard, onLog)
                CloningPanel.CALC -> CalcPanel()
                CloningPanel.LIMS -> LimsPanel(record, clipboard)
                CloningPanel.QC -> QcPanel(record, onRecordChange, onLog)
                CloningPanel.NGS -> NgsPanel()
                CloningPanel.EXTENSION -> ExtensionPanel(record, onRecordChange, onLog)
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
private fun CrisprPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onLog: (List<String>) -> Unit = {}) {
    // T7 块5：main gene/CrisprDesigner.svelte 1:1（算法 genome/crispr.ts → core/Crispr.kt）。
    // 平台差异（披露）：localStorage → SharedPreferences；toast → status 行；HTML select/number → FilterChip/OutlinedTextField。
    val t = I18nO
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences("spice_crispr_designer_config", android.content.Context.MODE_PRIVATE) }

    // main onMount：恢复持久化配置（缺省值与 Svelte $state 初始一致）
    var selectedCas by remember { mutableStateOf(prefs.getString("selectedCas", null)?.let { n -> CasType.entries.firstOrNull { it.name == n } } ?: CasType.SpCas9) }
    var insertSeq by remember { mutableStateOf(prefs.getString("insertSeq", null) ?: "ATGGTGTCTAAGGGCGAA") }
    var homologyArmLen by remember { mutableStateOf(prefs.getString("homologyArmLen", null) ?: "50") }
    var pbsLength by remember { mutableStateOf(prefs.getString("pbsLength", null) ?: "13") }
    var rttLength by remember { mutableStateOf(prefs.getString("rttLength", null) ?: "16") }
    var editStartIndex by remember { mutableStateOf(prefs.getString("editStartIndex", null) ?: "0") }
    var editSequence by remember { mutableStateOf(prefs.getString("editSequence", null) ?: "T") }
    var activeTab by remember { mutableStateOf(prefs.getString("activeTab", null) ?: "hdr") }
    var status by remember { mutableStateOf<String?>(null) }

    // main $effect：配置变更即持久化
    androidx.compose.runtime.LaunchedEffect(selectedCas, insertSeq, homologyArmLen, pbsLength, rttLength, editStartIndex, editSequence, activeTab) {
        prefs.edit().putString("selectedCas", selectedCas.name).putString("insertSeq", insertSeq)
            .putString("homologyArmLen", homologyArmLen).putString("pbsLength", pbsLength)
            .putString("rttLength", rttLength).putString("editStartIndex", editStartIndex)
            .putString("editSequence", editSequence).putString("activeTab", activeTab).apply()
    }

    var gRnas by remember { mutableStateOf<List<GrnaCandidate>>(emptyList()) }
    var selectedGrnaIdx by remember { mutableStateOf(0) }
    var showScanResults by remember { mutableStateOf(false) }

    val selectedGrna = gRnas.getOrNull(selectedGrnaIdx)

    fun runCrisprScan() {
        if (record.sequence.isEmpty() || record.sequence.length < 30) {
            status = "✗ " + t.t("扫描失败") + ": " + t.t("DNA 序列太短，无法进行 CRISPR 设计")
            return
        }
        val candidates = findGrnaCandidates(record.sequence, selectedCas, record.sequence)
        gRnas = candidates
        selectedGrnaIdx = 0
        showScanResults = true
        status = "✓ " + t.t("CRISPR 扫描完成") + ": " +
            t.t("使用 {cas} 发现 {count} 个潜在靶点！").replace("{cas}", selectedCas.name).replace("{count}", candidates.size.toString())
        onLog(listOf(
            t.t("[CRISPR] 扫描完成: 筛选出 {count} 个具有有效 PAM ({cas}) 的 gRNA 靶标")
                .replace("{count}", candidates.size.toString()).replace("{cas}", selectedCas.name)
        ))
    }

    fun copyToClipboard(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        status = "✓ " + t.t("已复制") + ": " + t.t("{label} 序列已复制到剪贴板！").replace("{label}", label)
    }

    // HDR / pegRNA 随选中 gRNA 与输入变化重算（main $effect 等价）
    val hdrResult = remember(selectedGrna, insertSeq, homologyArmLen) {
        selectedGrna?.let {
            runCatching {
                designHdrDonor(record.sequence, it.cutIndex, insertSeq, homologyArmLen.toIntOrNull() ?: 50, it)
            }.getOrNull()
        }
    }
    val pegRnaResult = remember(selectedGrna, pbsLength, rttLength, editStartIndex, editSequence) {
        selectedGrna?.let {
            runCatching {
                designPegRna(
                    record.sequence, it,
                    it.cutIndex + (editStartIndex.toIntOrNull() ?: 0),
                    editSequence, pbsLength.toIntOrNull() ?: 13, rttLength.toIntOrNull() ?: 16
                )
            }.getOrNull()
        }
    }

    val pixGreen = Color(0xFF53d769); val pixRed = Color(0xFFff5d5d); val pixCyan = Color(0xFF4cd6ff)
    val pixYellow = Color(0xFFffd23f); val pixOrange = Color(0xFFff9f1c)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header / Cas Selector
        Text(t.t("Cas 核酸酶体系:"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CasType.entries.forEach { cas ->
                androidx.compose.material3.FilterChip(
                    selected = selectedCas == cas, onClick = { selectedCas = cas },
                    label = { Text(cas.label, fontSize = 10.sp, maxLines = 1) }
                )
            }
        }
        Button(onClick = { runCrisprScan() }, modifier = Modifier.fillMaxWidth()) {
            Text(t.t("一键全局高特异性 gRNA 扫描"), fontSize = 11.sp)
        }
        status?.let { Text(it, fontSize = 10.sp, color = if (it.startsWith("✓")) pixGreen else pixRed) }

        if (showScanResults) {
            if (gRnas.isEmpty()) {
                Text(t.t("未能在当前模板中找到与所选 PAM 配对的 gRNA 靶点！"), fontSize = 10.sp, color = pixRed)
            } else {
                Text(t.t("筛选排序后的 gRNA 候选列表"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                // 候选表（main max-height 120px 滚动表）
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())
                ) {
                    // 表头
                    Row(Modifier.fillMaxWidth().background(Color(0xFF161b22)).padding(horizontal = 3.dp, vertical = 3.dp)) {
                        Text(t.t("靶点标号"), fontSize = 9.sp, modifier = Modifier.weight(0.9f), color = pixCyan)
                        Text(t.t("20nt 靶向序列 (5'->3')"), fontSize = 9.sp, modifier = Modifier.weight(2.6f), color = pixCyan)
                        Text(t.t("方向"), fontSize = 9.sp, modifier = Modifier.weight(0.5f), color = pixCyan)
                        Text(t.t("效率"), fontSize = 9.sp, modifier = Modifier.weight(0.7f), color = pixCyan)
                        Text(t.t("特异性"), fontSize = 9.sp, modifier = Modifier.weight(0.9f), color = pixCyan)
                        Text(t.t("总分"), fontSize = 9.sp, modifier = Modifier.weight(0.7f), color = pixCyan)
                    }
                    gRnas.forEachIndexed { idx, grna ->
                        val sel = selectedGrnaIdx == idx
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (sel) Color(0x2600ffff) else Color.Transparent)
                                .clickable { selectedGrnaIdx = idx }
                                .padding(horizontal = 3.dp, vertical = 3.dp)
                        ) {
                            Text("#${idx + 1}", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                            Text(grna.sequence, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = pixGreen, modifier = Modifier.weight(2.6f))
                            Text(grna.strand, fontSize = 9.sp, modifier = Modifier.weight(0.5f))
                            Text("${grna.scores.efficiency}", fontSize = 9.sp, color = pixCyan, modifier = Modifier.weight(0.7f))
                            Text("${grna.scores.specificity}", fontSize = 9.sp, color = pixOrange, modifier = Modifier.weight(0.9f))
                            Text("${grna.scores.finalScore}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixGreen, modifier = Modifier.weight(0.7f))
                        }
                    }
                }

                selectedGrna?.let { sg ->
                    // 选中 gRNA 生物物理详析
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.t("选中 gRNA PAM:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${sg.pam} " + t.t("剪切点: {index} bp").replace("{index}", sg.cutIndex.toString()),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = pixYellow)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.t("GC 含量:") + " ${sg.scores.gcContent}%", fontSize = 10.sp)
                            Text(t.t("Poly-T 终止区:") + " " + if (sg.scores.polyT) t.t("有 (异常)") else t.t("无 (安全)"),
                                fontSize = 10.sp, color = if (sg.scores.polyT) pixRed else pixGreen)
                            Text(t.t("发卡结构:") + " " + if (sg.scores.hairpin) t.t("有自互补") else t.t("无 (安全)"),
                                fontSize = 10.sp, color = if (sg.scores.hairpin) pixYellow else pixGreen)
                        }
                    }

                    // 子工作流 tabs
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.FilterChip(selected = activeTab == "hdr", onClick = { activeTab = "hdr" }, label = { Text(t.t("🛠️ 同源重组 Donor 设计"), fontSize = 10.sp, maxLines = 1) })
                        androidx.compose.material3.FilterChip(selected = activeTab == "prime", onClick = { activeTab = "prime" }, label = { Text(t.t("🎯 Prime Editing (pegRNA)"), fontSize = 10.sp, maxLines = 1) })
                        androidx.compose.material3.FilterChip(selected = activeTab == "base", onClick = { activeTab = "base" }, label = { Text(t.t("🧬 Base Editing (C->T / A->G)"), fontSize = 10.sp, maxLines = 1) })
                    }

                    when (activeTab) {
                        "hdr" -> {
                            Text(t.t("高精度 HDR 模板自动化设计 (HDR Synthesis Wizard)"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                            RcField(t.t("插入突变序列 (DNA):"), insertSeq, { insertSeq = it }, Modifier.fillMaxWidth())
                            RcField(t.t("同源臂长度 (Homology Arms, bp):"), homologyArmLen, { homologyArmLen = it }, Modifier.fillMaxWidth())
                            hdrResult?.let { h ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                                    Text(t.t("[合成供体全长]:"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                                    Text(h.fullDonor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(start = 2.dp))
                                    TextButton(onClick = { copyToClipboard(h.fullDonor, t.t("HDR 供体全长")) }) { Text("⧉", fontSize = 11.sp, color = pixYellow) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.t("左同源臂") + " (${h.leftArm.length}bp):", fontSize = 9.sp, color = pixGreen)
                                        Text(h.leftArm, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(t.t("右同源臂") + " (${h.rightArm.length}bp):", fontSize = 9.sp, color = pixGreen)
                                        Text(h.rightArm, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Text(t.t("🛡️ PAM 同义回避突变 (PAM Silent Evasion):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                                if (h.silentMutationsIntroduced.isEmpty()) {
                                    Text("⚠ " + t.t("未能引入回避突变。注意: Cas9 可能会消化已整合了的 DNA 供体模板！"), fontSize = 9.sp, color = pixYellow)
                                } else {
                                    Text(t.t("成功引入 {count} 处同义突变阻止 Cas9 复切：").replace("{count}", h.silentMutationsIntroduced.size.toString()), fontSize = 9.sp, color = pixGreen)
                                    h.silentMutationsIntroduced.forEach { mut ->
                                        Text("• " + t.t("位置 {v1}bp").replace("{v1}", mut.position.toString()) + ": ${mut.codonChange} (" +
                                            (if (mut.reason == "PAM_destruction") t.t("破坏 PAM 识别") else t.t("破坏 gRNA 杂交")) + ")",
                                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        "prime" -> {
                            Text(t.t("新型引导 RNA pegRNA 设计 (Prime Editing Synthesis)"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RcField(t.t("PBS 长度 (nt):"), pbsLength, { pbsLength = it }, Modifier.weight(1f))
                                RcField(t.t("RTT 长度 (nt):"), rttLength, { rttLength = it }, Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RcField(t.t("编辑位点相对于剪切位点偏移量:"), editStartIndex, { editStartIndex = it }, Modifier.weight(1f))
                                RcField(t.t("修改序列:"), editSequence, { editSequence = it }, Modifier.weight(1f))
                            }
                            pegRnaResult?.let { peg ->
                                Text(t.t("[完整的 pegRNA 序列 (5'->3')]:"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                                Text(peg.pegRnaSequence, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                OutlinedButton(onClick = { copyToClipboard(peg.pegRnaSequence, t.t("pegRNA 序列")) }) {
                                    Text(t.t("复制完整 pegRNA"), fontSize = 9.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.t("PBS 序列 (Primer Binding Site):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                                        Text(peg.pbs, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        Text("Tm: ${peg.pbsTm}°C", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(t.t("RTT 序列 (Reverse Transcription Template):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                                        Text(peg.rtt, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        Text(t.t("含突变及同源臂"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        else -> {
                            Text(t.t("碱基编辑器 (Base Editor CBE/ABE) 窗口靶向评估"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                            Text(t.t("根据公认的生物化学模型，在 SpCas9 20nt 靶区的第 4-8 位碱基 (1-indexed) 属于高活性编辑窗口："), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val bew = sg.baseEditingWindows
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.t("CBE 胞苷编辑器 (C ➔ T):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                                    if (bew == null || bew.cbeCandidates.isEmpty()) {
                                        Text(t.t("窗口内无活性胞嘧啶 (C)"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else bew.cbeCandidates.forEach { c ->
                                        Text(t.t("• 靶位点 #{windowPosition}: C➔T (效率: {efficiency}%)")
                                            .replace("{windowPosition}", c.windowPosition.toString()).replace("{efficiency}", c.efficiency.toString()),
                                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(t.t("ABE 腺苷编辑器 (A ➔ G):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                                    if (bew == null || bew.abeCandidates.isEmpty()) {
                                        Text(t.t("窗口内无活性腺嘌呤 (A)"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else bew.abeCandidates.forEach { c ->
                                        Text(t.t("• 靶位点 #{windowPosition}: A➔G (效率: {efficiency}%)")
                                            .replace("{windowPosition}", c.windowPosition.toString()).replace("{efficiency}", c.efficiency.toString()),
                                            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

private val PIX_CYAN = Color(0xFF4CD6FF)
private val PIX_ACCENT2 = Color(0xFFFFD23F)
private val PIX_GREEN = Color(0xFF53D769)
private val PIX_RED = Color(0xFFFF5D5D)
private val PIX_FG_DIM = Color(0xFF8B96B5)

/**
 * main src/lib/gene/SyntheticGeneQc.svelte 1:1（5 tab：剪接/PolyA、Kozak、PTC、CPB、DUST）。
 * 平台差异（披露）：toast → 面板内 status 行；bind:dnaSeq 回写 → onRecordChange(record.copy(sequence=...))；
 * res.log.forEach(pushLog) → onLog(res.log)；第 5 个 tab 标签为原版纯文本 "DUST"（无词条）。
 */
@Composable
private fun QcPanel(
    record: SeqRecord,
    onRecordChange: (SeqRecord) -> Unit,
    onLog: (List<String>) -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0=splice_polyA 1=kozak 2=ptc 3=cpb 4=dust
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }

    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusGood = ok
        status = "✓ " + title + (text?.let { " — " + it } ?: "")
    }

    val dnaSeq = record.sequence

    // Sub-Tab Selector（原版 8px 小按钮行）
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tabs = listOf(
            com.spice.nativeapp.core.I18n.t("剪接/PolyA"),
            com.spice.nativeapp.core.I18n.t("Kozak优化"),
            com.spice.nativeapp.core.I18n.t("PTC无义"),
            com.spice.nativeapp.core.I18n.t("密码对CPB"),
            "DUST"
        )
        tabs.forEachIndexed { i, label ->
            OutlinedButton(
                onClick = { tab = i },
                modifier = Modifier.weight(if (i == 0) 1.2f else if (i == 2 || i == 4) 0.8f else 1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) { Text(label, fontSize = 8.sp, maxLines = 1, color = if (tab == i) PIX_GREEN else MaterialTheme.colorScheme.onSurface) }
        }
    }

    when (tab) {
        // ── TAB 1: SPLICE & POLYA ──
        0 -> {
            val splices = remember(dnaSeq) { SyntheticGeneQc.detectCrypticSpliceSites(dnaSeq) }
            val polyas = remember(dnaSeq) { SyntheticGeneQc.detectCrypticPolyASignals(dnaSeq) }
            Text(com.spice.nativeapp.core.I18n.t("隐蔽剪切位点与 PolyA 信号消除:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)

            // A-1 隐蔽剪切位点
            Text(com.spice.nativeapp.core.I18n.t("隐蔽剪切位点 (GT-AG Pairs) [A-1]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
            if (splices.isEmpty()) {
                Text("✓ " + com.spice.nativeapp.core.I18n.t("未在当前序列中发现隐蔽剪切 GT-AG 配对。"), fontSize = 8.sp, color = PIX_GREEN)
            } else {
                splices.forEach { sp ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Intron " + (if (sp.type == "donor") com.spice.nativeapp.core.I18n.t("GT 供体") else com.spice.nativeapp.core.I18n.t("AG 受体")) + " (pos ${sp.position + 1})",
                            fontSize = 8.sp, color = PIX_RED
                        )
                        Text(sp.sequence, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = PIX_CYAN)
                        Text(com.spice.nativeapp.core.I18n.tf("置信度: {v1}%", sp.score), fontSize = 8.sp)
                    }
                }
            }

            // A-2 隐蔽转录终止信号
            Text(com.spice.nativeapp.core.I18n.t("隐蔽转录终止信号 (PolyA) [A-2]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
            if (polyas.isEmpty()) {
                Text("✓ " + com.spice.nativeapp.core.I18n.t("未发现内部 AATAAA 等提前终止转录信号。"), fontSize = 8.sp, color = PIX_GREEN)
            } else {
                polyas.forEach { pl ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.tf("终止信号: {v1}", pl.signal), fontSize = 8.sp, color = PIX_RED)
                        Text(com.spice.nativeapp.core.I18n.tf("位置: {v1} bp", pl.position + 1), fontSize = 8.sp, color = PIX_FG_DIM)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        val res = SyntheticGeneQc.eliminateCrypticSpliceSites(dnaSeq)
                        onRecordChange(record.copy(sequence = res.modifiedSequence))
                        onLog(res.log)
                        toast(true, com.spice.nativeapp.core.I18n.t("剪切点消除成功"), com.spice.nativeapp.core.I18n.tf("修复 {v1} 处同义突变", res.mutationsCount))
                    },
                    enabled = splices.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text(com.spice.nativeapp.core.I18n.t("同义切除剪切位点"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        val res = SyntheticGeneQc.eliminateCrypticPolyASignals(dnaSeq)
                        onRecordChange(record.copy(sequence = res.modifiedSequence))
                        onLog(res.log)
                        toast(true, com.spice.nativeapp.core.I18n.t("PolyA 消除成功"), com.spice.nativeapp.core.I18n.tf("修复 {v1} 处提前终止密码子", res.mutationsCount))
                    },
                    enabled = polyas.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text(com.spice.nativeapp.core.I18n.t("同义切除 PolyA 信号"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // ── TAB 2: KOZAK CONSENSUS ──
        1 -> {
            val firstCds = remember(record.features) { record.features.firstOrNull { it.type.lowercase() == "cds" } }
            val startIdx = remember(dnaSeq, firstCds) {
                firstCds?.let { it.start - 1 }
                    ?: dnaSeq.uppercase().indexOf("ATG").let { if (it >= 0) it else 0 }
            }
            val kozak = remember(dnaSeq, startIdx) { SyntheticGeneQc.analyzeKozak(dnaSeq, startIdx) }
            Text(com.spice.nativeapp.core.I18n.t("真核翻译 Kozak 强度分析 [A-3]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("起始密码子附近上下文:"), fontSize = 10.sp)
                Text(
                    kozak.upstreamSeq + " " + kozak.startCodon + " " + kozak.downstreamSeq,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("Kozak 翻译起始强度评级:"), fontSize = 10.sp)
                Text(
                    kozak.strength + " " + com.spice.nativeapp.core.I18n.tf("({v1} 分)", kozak.score),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (kozak.strength == "Strong") PIX_GREEN else if (kozak.strength == "Adequate") PIX_CYAN else PIX_RED
                )
            }

            Text(com.spice.nativeapp.core.I18n.t("优化推荐 (Recommendations):"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
            if (kozak.recommendations.isEmpty()) {
                Text("✓ " + com.spice.nativeapp.core.I18n.t("当前 Kozak 上下文已经极其完美，无需优化！"), fontSize = 7.5.sp, color = PIX_GREEN)
            } else {
                kozak.recommendations.forEach { rec ->
                    Text(rec, fontSize = 7.5.sp, color = PIX_FG_DIM)
                }
            }

            OutlinedButton(
                onClick = {
                    val res = SyntheticGeneQc.optimizeKozak(dnaSeq, startIdx)
                    onRecordChange(record.copy(sequence = res.optimizedSequence))
                    onLog(res.log)
                    toast(true, com.spice.nativeapp.core.I18n.t("Kozak 序列优化完毕！"), com.spice.nativeapp.core.I18n.tf("已针对 pos {v1} bp 起始位点匹配强真核共识 GCCACC", startIdx + 1))
                },
                enabled = kozak.strength != "Strong",
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("一键优化突变匹配强真核 Kozak 上下文"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }

        // ── TAB 3: PTC ──
        2 -> {
            val ptc = remember(dnaSeq) { SyntheticGeneQc.detectPrematureStopCodons(dnaSeq) }
            Text(com.spice.nativeapp.core.I18n.t("提前终止密码子 (PTC) & NMD 降解预警 [A-4]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)

            if (ptc.stops.isEmpty()) {
                Text("✓ " + com.spice.nativeapp.core.I18n.t("完美！未在当前阅读框内检测到任何提前终止密码子 (PTC)。"), fontSize = 9.5.sp, color = PIX_GREEN)
            } else {
                ptc.stops.forEach { st ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(com.spice.nativeapp.core.I18n.t("提前终止密码子 (PTC)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PIX_RED)
                            Text("[${st.codon}]", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = PIX_RED)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(com.spice.nativeapp.core.I18n.tf("碱基位置: pos {v1} bp | 氨基酸: 第 {v2} aa", st.position + 1, st.aaPosition), fontSize = 8.sp, color = PIX_FG_DIM)
                            Text(
                                if (st.triggerNmd) com.spice.nativeapp.core.I18n.t("极高风险触发 NMD 降解") else com.spice.nativeapp.core.I18n.t("无显著降解风险"),
                                fontSize = 8.sp,
                                color = if (st.triggerNmd) PIX_RED else PIX_CYAN
                            )
                        }
                    }
                }
            }
        }

        // ── TAB 4: CODON PAIR BIAS (CPB) ──
        3 -> {
            val cpbVal = remember(dnaSeq) { SyntheticGeneQc.calculateCpbScore(dnaSeq) }
            Text(com.spice.nativeapp.core.I18n.t("相邻密码子对偏好指数 (Codon Pair Bias) [A-5]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(com.spice.nativeapp.core.I18n.t("当前基因核糖体翻译 CPB 指数:"), fontSize = 10.sp)
                Text(
                    jsNum(cpbVal),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (cpbVal >= 0.1) PIX_GREEN else if (cpbVal >= 0.0) PIX_CYAN else PIX_RED
                )
            }
            Text(com.spice.nativeapp.core.I18n.t("说明：CPB 指数代表核糖体翻译相邻密码子时的偏好性。正值代表搭配极佳、不易卡滞；负值代表存在不良搭配，会产生核糖体暂停 (Ribosome stalls)，导致表达量腰斩。"), fontSize = 7.5.sp, color = PIX_FG_DIM)

            OutlinedButton(
                onClick = {
                    val res = SyntheticGeneQc.optimizeCodonPairs(dnaSeq)
                    onRecordChange(record.copy(sequence = res.optimizedSequence))
                    onLog(res.log)
                    toast(true, com.spice.nativeapp.core.I18n.t("密码子对优化完成"), com.spice.nativeapp.core.I18n.tf("CPB 指数大幅上升至 {v1}", jsNum(res.optimizedCpb)))
                },
                enabled = cpbVal < 0.18,
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("一键执行密码子对同义替换优化 (Maximize CPB)"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }

        // ── TAB 5: LOW COMPLEXITY DUST ──
        else -> {
            val dust = remember(dnaSeq) { SyntheticGeneQc.detectLowComplexityRegions(dnaSeq) }
            Text(com.spice.nativeapp.core.I18n.t("低复杂度/微重复序列扫描 (DUST-style) [A-6]:"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)

            if (dust.isEmpty()) {
                Text("✓ " + com.spice.nativeapp.core.I18n.t("未检测到高风险的低复杂度或微卫星重复区域。"), fontSize = 8.5.sp, color = PIX_GREEN)
            } else {
                dust.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("[${r.type.uppercase()}] pos ${r.start + 1}-${r.end + 1} bp", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                        Text(r.sequence.take(24), fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = PIX_CYAN, maxLines = 1)
                        Text(com.spice.nativeapp.core.I18n.tf("熵: {v1}", jsNum(r.entropy)), fontSize = 8.sp, color = PIX_FG_DIM)
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val res = SyntheticGeneQc.maskLowComplexityRegions(dnaSeq)
                    onRecordChange(record.copy(sequence = res.maskedSequence))
                    onLog(listOf(com.spice.nativeapp.core.I18n.tf("[DUST] 自动遮蔽了 {v1} 处低复杂度序列，以便于基因合成比对过滤。", res.regions.size)))
                    toast(true, com.spice.nativeapp.core.I18n.t("低复杂度遮蔽成功"), com.spice.nativeapp.core.I18n.tf("成功将 {v1} 处区域转换至 lowercase 小写形态", res.regions.size))
                },
                enabled = dust.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("一键低复杂度遮蔽 (Mask with Lowercase)"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }

    status?.let {
        Text(
            it,
            fontSize = 10.sp,
            color = if (statusGood) PIX_GREEN else PIX_RED,
            modifier = Modifier.fillMaxWidth()
        )
    }
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
private fun ExtensionPanel(
    record: SeqRecord,
    onRecordChange: (SeqRecord) -> Unit,
    onLog: (List<String>) -> Unit
) {
    // main gene/panels/CloningExtensionPanel.svelte 1:1（5 子 tab）
    // 平台差异（披露）：toast → 面板内 status 行；select/number → FilterChip/OutlinedTextField；
    // main 的 loading 态（Tauri 异步）在本地同步计算下瞬时完成，不再单独渲染。
    var tab by remember { mutableStateOf(0) } // 0=goldengate 1=recombinase 2=ssr 3=snp 4=rflp
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }

    var ggEnzyme by remember { mutableStateOf("BsaI") }
    var ggFragmentsText by remember { mutableStateOf("Fragment_1_Vector,GCAGGGTCTCTTAAACGATGCTAGCGGAAATGGAGAGACCGCTG\nFragment_2_Insert_1,AGTCGGTCTCTATGGCGCAACGGCCCCGAGAGACCATGC\nFragment_3_Insert_2,TTACGGTCTCTCCCGCGTGCGTTTTAAAGAGACCGATC") }
    var ggResult by remember { mutableStateOf<CloningExt.GgResult?>(null) }

    var recomSystem by remember { mutableStateOf("Cre-lox") }
    var recomInsertSeq by remember { mutableStateOf("GAAGTTCCTATTCTCTAGAAAGTATAGGAACTTC") }

    var snpIndexText by remember { mutableStateOf("100") }
    var snpAllele1 by remember { mutableStateOf("A") }
    var snpAllele2 by remember { mutableStateOf("G") }

    var rflpEnzymeName by remember { mutableStateOf("EcoRI") }
    var rflpEnzymeSite by remember { mutableStateOf("GAATTC") }
    var rflpMutantSeq by remember { mutableStateOf("GAATTC") }

    fun toast(typeOk: Boolean, title: String, text: String? = null) {
        statusGood = typeOk
        status = title + (text?.let { " — " + it } ?: "")
    }

    fun runGg() {
        val frags = ggFragmentsText.trim().split('\n').map { line ->
            val parts = line.split(',')
            val seqPart = if (parts.size > 1) parts[1] else parts[0]
            seqPart.uppercase().filter { it in "ATGCN" }
        }.filter { it.isNotEmpty() }
        if (frags.size < 2) {
            toast(false, com.spice.nativeapp.core.I18n.t("片段不足"), com.spice.nativeapp.core.I18n.t("多片段组装至少需要 2 个输入序列"))
            return
        }
        val res = CloningExt.simulateGoldenGateAssembly(frags, ggEnzyme)
        ggResult = res
        if (res.success) {
            onLog(listOf(com.spice.nativeapp.core.I18n.tf("[GOLDEN GATE] 组装仿真成功！共拼接 {v1} 个片段，生成环状重组质粒，长度: {v2} bp", res.fragments.size, res.productSeq.length)))
            toast(true, com.spice.nativeapp.core.I18n.t("组装模拟成功"), com.spice.nativeapp.core.I18n.t("生成了闭合环状质粒产品！"))
        } else {
            toast(false, com.spice.nativeapp.core.I18n.t("组装匹配失败"), com.spice.nativeapp.core.I18n.t("黏性末端不兼容，详见错误清单"))
        }
    }

    fun applyGg() {
        val r = ggResult
        if (r != null && r.success) {
            onRecordChange(record.copy(sequence = r.productSeq))
            onLog(listOf(com.spice.nativeapp.core.I18n.tf("[GOLDEN GATE] 一键应用 Golden Gate 组装质粒产物（长度: {v1} bp）到编辑器中。", r.productSeq.length)))
            toast(true, com.spice.nativeapp.core.I18n.t("载入质粒成功"), com.spice.nativeapp.core.I18n.t("新组装的质粒序列已载入主编辑器！"))
        }
    }

    fun loadRecomScene(kind: Int) {
        val site = if (recomSystem == "Cre-lox") "ATAACTTCGTATAATGTATGCTATACGAAGTTAT" else "GAAGTTCCTATTCTCTAGAAAGTATAGGAACTTC"
        when (kind) {
            0 -> {
                onRecordChange(record.copy(sequence = "GATCTTCG" + site + "A".repeat(100)))
                recomInsertSeq = site + "T".repeat(50)
                toast(true, com.spice.nativeapp.core.I18n.t("场景已加载"), com.spice.nativeapp.core.I18n.t("已注入包含单位点的载体与供体（测试整合反应）"))
            }
            1 -> {
                onRecordChange(record.copy(sequence = "GATCTTCG" + site + "G".repeat(30) + site + "A".repeat(50)))
                recomInsertSeq = ""
                toast(true, com.spice.nativeapp.core.I18n.t("场景已加载"), com.spice.nativeapp.core.I18n.t("已注入包含两个同向位点的载体（测试环出切除）"))
            }
            2 -> {
                onRecordChange(record.copy(sequence = "GATCTTCG" + site + "G".repeat(30) + Bio.revComp(site) + "A".repeat(50)))
                recomInsertSeq = ""
                toast(true, com.spice.nativeapp.core.I18n.t("场景已加载"), com.spice.nativeapp.core.I18n.t("已注入包含两个反向位点的载体（测试倒位反应）"))
            }
        }
    }

    fun runRecom() {
        val res = CloningExt.simulateRecombinaseCloning(record.sequence, recomInsertSeq, recomSystem)
        if (res.reactionType == "no_reaction") {
            toast(false, com.spice.nativeapp.core.I18n.t("未发生重组反应"), com.spice.nativeapp.core.I18n.t("载体或供体序列中未检测到匹配的特异位点对！"))
            onLog(listOf(com.spice.nativeapp.core.I18n.tf("[{v1}] 未找到匹配的重组位点对，无法触发位点特异性基因重组。", recomSystem)))
            return
        }
        onRecordChange(record.copy(sequence = res.productSequence))
        onLog(res.log)
        toast(true, com.spice.nativeapp.core.I18n.t("重组反应成功！"), com.spice.nativeapp.core.I18n.tf("重组机制类型: {v1}", res.reactionType))
    }

    // 子 tab 选择器（main 5 按钮）
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            com.spice.nativeapp.core.I18n.t("GoldenGate组装"),
            com.spice.nativeapp.core.I18n.t("重组克隆"),
            com.spice.nativeapp.core.I18n.t("SSR育种标记"),
            com.spice.nativeapp.core.I18n.t("SNP等位分型"),
            com.spice.nativeapp.core.I18n.t("RFLP多态")
        ).forEachIndexed { i, label ->
            androidx.compose.material3.FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label, fontSize = 9.sp) })
        }
    }

    when (tab) {
        0 -> {
            // TAB 0: GOLDEN GATE ASSEMBLY SIMULATOR
            Text(com.spice.nativeapp.core.I18n.t("Golden Gate (Type IIS) 组装模拟舱:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("选择限制酶:"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("BsaI" to "BsaI (GGTCTC)", "BsmBI" to "BsmBI (CGTCTC)", "BbsI" to "BbsI (GAAGAC)").forEach { (v, label) ->
                        androidx.compose.material3.FilterChip(selected = ggEnzyme == v, onClick = { ggEnzyme = v }, label = { Text(label, fontSize = 9.sp) })
                    }
                }
                Text(com.spice.nativeapp.core.I18n.t("输入多片段序列 (格式: 名称,序列):"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = ggFragmentsText, onValueChange = { ggFragmentsText = it },
                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("格式: 片段名称, 序列"), fontSize = 8.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(onClick = { runGg() }, modifier = Modifier.fillMaxWidth()) {
                Text(com.spice.nativeapp.core.I18n.t("⚡ 运行多片段 Golden Gate 拓扑组装"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            ggResult?.let { res ->
                if (res.success) {
                    Column(Modifier.fillMaxWidth().background(Color(0x0253D769)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("✓ " + com.spice.nativeapp.core.I18n.t("组装成功！(形成环状质粒)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CsPixGreen)
                        Text(
                            com.spice.nativeapp.core.I18n.t("组装片段顺序: ") + res.sortedIndices.joinToString(" ➔ ") { "Frag_${it + 1}" },
                            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            com.spice.nativeapp.core.I18n.t("产生的悬垂接头 (Overhangs): ") + res.overhangs.joinToString(" · "),
                            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { applyGg() }) {
                            Text(com.spice.nativeapp.core.I18n.t("[一键将组装质粒载入编辑器]"), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = CsPixGreen)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().background(Color(0x03FF5D5D)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("✗ " + com.spice.nativeapp.core.I18n.t("组装失败："), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CsPixRed)
                        res.errors.forEach { err -> Text("• $err", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (res.warnings.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().background(Color(0x02FFD23F)).padding(5.dp)) {
                        res.warnings.forEach { w -> Text("⚠ $w", fontSize = 8.sp, color = CsPixAccent2) }
                    }
                }
            }
        }
        1 -> {
            // TAB 1: SITE-SPECIFIC RECOMBINASE CLONING
            Text(com.spice.nativeapp.core.I18n.t("重组酶位点特异克隆系统模拟 (Cre-loxP / FLP) [D-15]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("选择重组系统 (Recombinase):"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Cre-lox" to "Cre-loxP (34 bp loxP)", "FLP-FRT" to "FLP-FRT (34 bp FRT)").forEach { (v, label) ->
                        androidx.compose.material3.FilterChip(selected = recomSystem == v, onClick = { recomSystem = v }, label = { Text(label, fontSize = 9.sp) })
                    }
                }
                Text(com.spice.nativeapp.core.I18n.t("输入重组供体片段 (Insert DNA with site):"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = recomInsertSeq, onValueChange = { recomInsertSeq = it },
                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("输入包含 loxP/FRT 的插入片段..."), fontSize = 8.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(com.spice.nativeapp.core.I18n.t("快捷加载重组测试场景:"), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = CsPixAccent2)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { loadRecomScene(0) }) { Text(com.spice.nativeapp.core.I18n.t("1. 整合反应"), fontSize = 8.sp) }
                // main cloningExtRecomExciseBtn 文案 verbatim（主版该按钮文案即 toast 文本，笔误原样保留）
                OutlinedButton(onClick = { loadRecomScene(1) }) { Text(com.spice.nativeapp.core.I18n.t("已注入包含两个同向位点的载体（测试环出切除）"), fontSize = 8.sp) }
                OutlinedButton(onClick = { loadRecomScene(2) }) { Text(com.spice.nativeapp.core.I18n.t("3. 片段倒位"), fontSize = 8.sp) }
            }
            Button(onClick = { runRecom() }, modifier = Modifier.fillMaxWidth()) {
                Text(com.spice.nativeapp.core.I18n.t("执行位点特异性基因重组克隆模拟"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        2 -> {
            // TAB 2: SSR MARKERS FLANKING DESIGN
            Text(com.spice.nativeapp.core.I18n.t("简单重复序列 (SSR) 微卫星标记引物设计 [D-16]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            val ssrs = remember(record.sequence) { CloningExt.designSsrMarkers(record.sequence) }
            if (ssrs.isEmpty()) {
                Text(com.spice.nativeapp.core.I18n.t("未扫描到符合标记设计要求的微卫星串联重复段。"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ssrs.forEach { ssr ->
                    Column(Modifier.fillMaxWidth().background(Color(0x03B48CFF)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SSR: ${ssr.repeatPattern} (${ssr.repeatCount}x)", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = CsPixPurple)
                            Text(com.spice.nativeapp.core.I18n.t("长度") + ": ${ssr.productSize} bp", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            "F-Primer: ${ssr.forwardPrimer} (Tm ${ssr.tm}°C)\nR-Primer: ${ssr.reversePrimer} (Tm ${ssr.tm}°C)",
                            fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        3 -> {
            // TAB 3: SNP GENOTYPING PRIMERS
            Text(com.spice.nativeapp.core.I18n.t("SNP 等位基因特异性 ASP-PCR 引物设计 [D-17]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(com.spice.nativeapp.core.I18n.t("SNP 核苷酸位点:"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = snpIndexText, onValueChange = { snpIndexText = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp), modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f)) {
                    Text(com.spice.nativeapp.core.I18n.t("等位基因 1 (A1):"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = snpAllele1, onValueChange = { snpAllele1 = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp), modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f)) {
                    Text(com.spice.nativeapp.core.I18n.t("等位基因 2 (A2):"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = snpAllele2, onValueChange = { snpAllele2 = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp), modifier = Modifier.fillMaxWidth())
                }
            }
            // 1:1 复刻主版运行行为：main svelte 读取 snpRes.compatible/asp1Primer 等字段而引擎实际
            // 返回 kasp/taqman —— 字段失配致主版恒走 ⚠️ 分支且 message 为 undefined（渲染为空）。
            // 本面板按主版行为渲染 ⚠️ 空消息（不虚构主版不存在的引物展示）。
            val snpRes = remember(record.sequence, snpIndexText, snpAllele1, snpAllele2) {
                CloningExt.designSnpGenotypingPrimers(record.sequence, snpIndexText.toIntOrNull() ?: 100, snpAllele1, snpAllele2)
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp)) {
                Text("⚠️ ", fontSize = 8.sp, color = CsPixRed)
            }
        }
        4 -> {
            // TAB 4: RFLP POLYMORPHISM ANALYSIS
            // 1:1 复刻主版运行行为：main svelte 读取 rflp.wildTypeFragments.length 时因引擎实际
            // 返回 fragmentsWt 而抛 TypeError，结果区自崩溃点起不可渲染。Android 以"结果区不渲染"
            // 复刻其不可用性（不虚构主版不存在的片段谱输出），平台差异在 README 披露。
            Text(com.spice.nativeapp.core.I18n.t("制限性片段长度多态性 (RFLP) 变异谱检测 [D-18]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(com.spice.nativeapp.core.I18n.t("限制酶名称 (Enzyme):"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = rflpEnzymeName, onValueChange = { rflpEnzymeName = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp), modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1.2f)) {
                    Text(com.spice.nativeapp.core.I18n.t("切点识别位点 (Site):"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = rflpEnzymeSite, onValueChange = { rflpEnzymeSite = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                }
            }
            Text(com.spice.nativeapp.core.I18n.t("输入突变型变异序列进行比对 (Mutant DNA):"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = rflpMutantSeq, onValueChange = { rflpMutantSeq = it },
                placeholder = { Text(com.spice.nativeapp.core.I18n.t("输入用于RFLP多态对比的突变序列..."), fontSize = 8.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // toast 等价物：面板内 status 行
    status?.let {
        Text(it, fontSize = 9.sp, color = if (statusGood) CsPixGreen else CsPixRed)
    }
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

// ═══════════════ T7 块2：分子克隆仿真舱（main src/lib/gene/CloningSimulator.svelte 1:1） ═══════════════
// 平台差异（披露）：HTML select/checkbox/radio → FilterChip；number input → OutlinedTextField；
// 原版左右两栏网格 → 单栏纵向流（内容与顺序 1:1）；toast → 面板内 status 行；
// 成功后"确认导入"对齐 main gene/+page.svelte handleCloneSuccess（含 leftEnzyme 位点定位特征注册、
// 日志两条、选中酶自动收编）；cloningHistory 持久列表 main 有而 Kotlin 未建，节点经 Map 传递。
// 面板内配色对齐 main app.css：green #53d769 / red #ff5d5d / cyan #4cd6ff / accent-2 #ffd23f / purple #b48cff。

private data class CsPreset(val name: String, val seq: String, val leftEnzyme: String, val rightEnzyme: String)

/** main CloningSimulator PRESET_INSERTS verbatim（序列与酶切对原样保留） */
private val CS_PRESETS = listOf(
    CsPreset(
        "mTagBFP2 (Fluorescent Protein)",
        "TCGACATGGTGTCTAAGGGCGAAGAGCTGATTAAGGAGAACATGCACATGAAGCTGTACATGGAGGGCACCGTGGACAACCATCACTTCAAGTGCACATCCGAGGGCGAAGGCAAGCCCTACGAGGGCACCCAGACCATGAGAATCAAGGTGGTCGAGGGCGGCCCTCTCCCCTTCGCCTTCGACATCCTGGCTACTAGCTTCCTCTACGGCAGCAAGACCTTCATCAACCACACCCAGGGCATCCCCGACTTCTTCAAGCAGTCCTTCCCTGAGGGCTTCACATGGGAGAGAGTCACCACATACGAAGACGGGGGCGTGCTGACCGCTACCCAGGACACCAGCCTCCAGGACGGCTGCCTCATCTACAACGTCAAGATCAGAGGGGTGAACTTCACATCCAATGGCCCTGTGATGCAGAAGAAAACACTCGGCTGGGAGGCCTTCACCGAGACGCTGTACCCCGCTGACGGCGGCCTGGAAGGCAGAAACGACATGGCCCTGAAGCTCGTGGGCGGGAGCCATCTGATCGCAAACATCAAGACCACATATAGATCCAAGAAACCCGCTAAGAACCTCAAGATGCCCGGCGTCTACTATGTGGACTACAGACTGGAAAGAATCAAGGAGGCCAACAACGAGACCTACGTCGAGCAGCACGAGGTGGCAGTGGCCAGATACTGCGACCTCCCTAGCAAACTGGGGCACAAACTTAATGGTACC",
        "SalI", "KpnI"
    ),
    CsPreset(
        "miniSOG (Optogenetic Flavin-binding)",
        "GAATTCATGGAAAGAAGTTTCGTGATTACGGATCCAAGATTGCCGGATAACCCAATTATTTTTGCATCAGATGGTTTCTTGGAATTGACGGAATATTCTCGTGAAGAAATTTTGGGTAGAAATGGTAGATTTTTGCAAGGTCCAGAAACGGATCAAGCTACGGTTCAAAAAATTAGAGATGCGATTAGAGATCAACGTGAAATTACGGTTCAATTGATTAACTATACGAAAAGTGGTAAAAAATTCTGGAACTTGTTGCATTTGCAACCAATGCGTGATCAAAAAGGTGAATTGCAATATTTCATTGGTGTTCAATTGGATGGTTCTAGACATCACCATCACCATCACTAA",
        "EcoRI", "BamHI"
    )
)

private data class CsMethod(val id: String, val label: String, val descZh: String)

/** main METHODS verbatim（label 英文字面量 + desc zh 原文） */
private val CS_METHODS = listOf(
    CsMethod(CloningSim.M_RESTRICTION, "Restriction Cloning", "限制性内切酶切连接克隆 (经典 cohesive/sticky end 克隆)"),
    CsMethod(CloningSim.M_GIBSON, "Gibson Assembly", "吉布森多片段无缝同源重组组装 (Gibson)"),
    CsMethod(CloningSim.M_GOLDEN_GATE, "Golden Gate Assembly", "金门 IIs 型限制性酶定向组装 (Golden Gate)"),
    CsMethod(CloningSim.M_INFUSION, "In-Fusion Cloning", "Takara In-Fusion 同源重组无缝克隆"),
    CsMethod(CloningSim.M_NEBUILDER_HIFI, "NEBuilder HiFi", "NEBuilder 高忠实度无缝组装"),
    CsMethod(CloningSim.M_GATEWAY, "Gateway Cloning", "Gateway att 场地特异性重组系统"),
    CsMethod(CloningSim.M_TOPO_DIRECTIONAL, "TOPO (Directional)", "定向 TOPO 克隆 - CACC 4nt 黏性末端"),
    CsMethod(CloningSim.M_TOPO, "TOPO (Non-directional)", "非定向平末端 TOPO 克隆"),
    CsMethod(CloningSim.M_TA, "TA Cloning", "TA 克隆 - 3' A 突出端黏性连接"),
    CsMethod(CloningSim.M_GC, "GC Cloning", "GC / Blunt 平末端钝性连接克隆")
)

/** main ENZYME_OVERHANGS verbatim（粘性末端可视化表） */
private data class CsOverhang(val top: String, val bot: String, val is5Prime: Boolean, val len: Int)

private val CS_OVERHANGS = mapOf(
    "EcoRI" to CsOverhang("G", "AATTC", true, 4),
    "BamHI" to CsOverhang("G", "GATCC", true, 4),
    "HindIII" to CsOverhang("A", "AGCTT", true, 4),
    "SalI" to CsOverhang("G", "TCGAC", true, 4),
    "XhoI" to CsOverhang("C", "TCGAG", true, 4),
    "NdeI" to CsOverhang("CA", "TATG", true, 2),
    "SacI" to CsOverhang("GAGCT", "C", false, 4),
    "KpnI" to CsOverhang("GGTAC", "C", false, 4)
)

private val CsPixGreen = Color(0xFF53D769)
private val CsPixRed = Color(0xFFFF5D5D)
private val CsPixCyan = Color(0xFF4CD6FF)
private val CsPixAccent2 = Color(0xFFFFD23F)
private val CsPixPurple = Color(0xFFB48CFF)

@Composable
private fun CsStepTitle(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixAccent2)
}

@Composable
private fun CsChip(selected: Boolean, onClick: () -> Unit, label: String) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp) }
    )
}

@Composable
private fun CsDimText(text: String) {
    Text(text, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CsResultRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

/** 粘性末端拼接区可视化（main junction 行 verbatim，等宽字体） */
@Composable
private fun CsJunctionBlock(title: String, line1: String, line2: String, line3: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(6.dp)
    ) {
        Text(title, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = CsPixAccent2)
        Text(line1, fontSize = 8.sp, color = CsPixGreen, fontFamily = FontFamily.Monospace)
        Text(line2, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), fontFamily = FontFamily.Monospace)
        Text(line3, fontSize = 8.sp, color = CsPixCyan, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CloningSimulatorPanel(
    record: SeqRecord,
    onRecordChange: (SeqRecord) -> Unit,
    onEnzymesAdopt: (List<String>) -> Unit = {},
    onLog: (List<String>) -> Unit = {}
) {
    val vectorSeq = record.sequence
    val vectorName = record.name

    var simulationFinished by remember { mutableStateOf(false) }
    var resultSeq by remember { mutableStateOf("") }
    var resultName by remember { mutableStateOf("") }
    var resultWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultNode by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }

    var selectedMethod by remember { mutableStateOf<String>(CloningSim.M_RESTRICTION) }
    var usePreset by remember { mutableStateOf(true) }
    var selectedInsertIdx by remember { mutableStateOf(0) }
    var customInsertName by remember { mutableStateOf("MyInsert") }
    var customInsertSeq by remember { mutableStateOf("GAATTC...GGATCC") }
    var vectorLeftEnzyme by remember { mutableStateOf("SalI") }
    var vectorRightEnzyme by remember { mutableStateOf("KpnI") }
    var goldenGateEnzyme by remember { mutableStateOf("BsaI") }
    var overlapText by remember { mutableStateOf("25") }
    var directional by remember { mutableStateOf(true) }
    var vPhosL by remember { mutableStateOf(true) }
    var vPhosR by remember { mutableStateOf(true) }
    var iPhosL by remember { mutableStateOf(true) }
    var iPhosR by remember { mutableStateOf(true) }
    val attL1 = true // main：状态存在但无 UI 控件，恒传引擎
    val overlapLength = overlapText.toIntOrNull()

    val activeInsert = if (usePreset) CS_PRESETS[selectedInsertIdx.coerceIn(0, CS_PRESETS.size - 1)]
    else CsPreset(
        customInsertName, customInsertSeq.uppercase().filter { it in "ATCG" },
        vectorLeftEnzyme, vectorRightEnzyme
    )

    // ── 校验（main validationResult derived 1:1） ──
    val warnings = mutableListOf<String>()
    var ready = true
    if (vectorSeq.isEmpty() || vectorSeq.length < 10) {
        warnings += I18n.t("载体序列过短"); ready = false
    }
    if (activeInsert.seq.isEmpty() || activeInsert.seq.length < 10) {
        warnings += I18n.t("插入片段序列过短"); ready = false
    }
    when (selectedMethod) {
        CloningSim.M_RESTRICTION -> {
            val vcl = EnzymesVe.getCutsitesFromSequenceFlat(vectorSeq, true, listOf(EnzymesVe.resolveEnzyme(vectorLeftEnzyme)))
            val vcr = EnzymesVe.getCutsitesFromSequenceFlat(vectorSeq, true, listOf(EnzymesVe.resolveEnzyme(vectorRightEnzyme)))
            if (vcl.isEmpty()) { warnings += I18n.tf("载体中未找到 {v1} 位点", vectorLeftEnzyme); ready = false }
            if (vcr.isEmpty()) { warnings += I18n.tf("载体中未找到 {v1} 位点", vectorRightEnzyme); ready = false }
            if (vectorLeftEnzyme != activeInsert.leftEnzyme || vectorRightEnzyme != activeInsert.rightEnzyme) {
                warnings += I18n.t("载体与插入片段酶不匹配")
            }
        }
        CloningSim.M_GOLDEN_GATE -> {
            // main `if (!ggEnz)` 为死分支（resolveEnzyme 恒非空）——1:1 保留 0 切点路径
            val ggEnz = EnzymesVe.resolveEnzyme(goldenGateEnzyme)
            val vcuts = EnzymesVe.getCutsitesFromSequenceFlat(vectorSeq, true, listOf(ggEnz))
            if (vcuts.isEmpty()) warnings += I18n.tf("载体中未找到 {v1} 位点", goldenGateEnzyme)
        }
        CloningSim.M_TOPO_DIRECTIONAL ->
            if (!activeInsert.seq.startsWith("CACC")) warnings += I18n.t("定向 TOPO 需要插入片段 5' 端为 CACC 突出端")
    }

    fun handleLigate() {
        if (!ready) {
            status = I18n.t("连接条件不满足") + "：" + warnings.joinToString("; ")
            return
        }
        val result = CloningSim.simulateCloning(
            CloningSim.CloningInput(
                vector = CloningSim.SimSequenceData(name = vectorName, sequence = vectorSeq, circular = true),
                insert = CloningSim.SimSequenceData(name = activeInsert.name, sequence = activeInsert.seq, circular = false),
                method = selectedMethod,
                leftEnzyme = vectorLeftEnzyme,
                rightEnzyme = vectorRightEnzyme,
                overlapLength = overlapLength,
                goldenGateEnzyme = goldenGateEnzyme,
                attL1 = attL1,
                directional = directional,
                vectorPhosphorLeft = vPhosL,
                vectorPhosphorRight = vPhosR,
                insertPhosphorLeft = iPhosL,
                insertPhosphorRight = iPhosR
            )
        )
        val productSeq = result.product.sequence
        val productName = result.productName
        val ws = result.warnings
        if (ws.isNotEmpty()) status = I18n.t("克隆完成 (有警告)") + "：" + ws.joinToString("; ")
        resultNode = mapOf(
            "productName" to productName,
            "size" to productSeq.length.toString(),
            "timestamp" to java.time.Instant.now().toString(),
            "parentVector" to vectorName,
            "parentVectorSize" to vectorSeq.length.toString(),
            "insertName" to activeInsert.name,
            "insertSize" to activeInsert.seq.length.toString(),
            "leftEnzyme" to vectorLeftEnzyme,
            "rightEnzyme" to vectorRightEnzyme,
            "method" to selectedMethod
        )
        resultSeq = productSeq
        resultName = productName
        resultWarnings = ws
        simulationFinished = true
        status = I18n.t("克隆连接成功！") + " [$selectedMethod] $productName (${productSeq.length} bp)"
    }

    /** main gene/+page.svelte handleCloneSuccess 1:1（特征注册/日志/酶收编） */
    fun handleImport(newSeq: String, newName: String, node: Map<String, String>) {
        if (newSeq.isEmpty()) return
        val leftEnz = node["leftEnzyme"] ?: "SalI"
        val rightEnz = node["rightEnzyme"] ?: "KpnI"
        val leftE = Enzymes.byName(leftEnz)
        val leftSite = leftE?.site ?: "GTCGAC" // main fallback {seq:'GTCGAC', cut:1}
        val leftCut = leftE?.cutOffset ?: 1
        val leftIndex = newSeq.uppercase().indexOf(leftSite)
        val insertStart = if (leftIndex != -1) leftIndex + leftCut + 1 else 1
        val insertSize = node["insertSize"]?.toIntOrNull() ?: 0
        val insertEnd = if (insertSize > 0) insertStart + insertSize - 1 else insertStart
        val insertName = node["insertName"].takeUnless { it.isNullOrEmpty() } ?: "Insert"
        val shortName = insertName.split(" ").first().ifEmpty { "Insert" }
        onRecordChange(
            record.copy(
                name = newName.ifEmpty { vectorName },
                sequence = newSeq,
                features = record.features + com.spice.nativeapp.core.Feature(
                    name = shortName, type = "cds", start = insertStart, end = insertEnd, strand = 1,
                    color = "#ffd23f" // main 特征色 var(--pix-accent-2)
                )
            )
        )
        onEnzymesAdopt(listOf(leftEnz, rightEnz))
        onLog(
            listOf(
                I18n.t("[CLONE] 载体剪切与连接反应 成功完成！构建产物: {name} ({length} bp)")
                    .replace("{name}", newName).replace("{length}", newSeq.length.toString()),
                I18n.t("[FEATURE] 已向物理图谱动态注册表达框: {name} [{start} - {end} bp]")
                    .replace("{name}", shortName)
                    .replace("{start}", insertStart.toString()).replace("{end}", insertEnd.toString())
            )
        )
    }

    fun reset() {
        simulationFinished = false
        resultSeq = ""
        resultName = ""
        resultWarnings = emptyList()
        resultNode = emptyMap()
    }

    if (!simulationFinished) {
        // 面板内小标题（FloatingPanel 已有窗口标题，此处 1:1 保留原面板内 heading）
        Text("✦ " + I18n.t("分子克隆仿真舱"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)

        // 方法选择器（main 10 按钮 → FilterChip 行）
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CS_METHODS.forEach { mth ->
                CsChip(selected = selectedMethod == mth.id, onClick = { selectedMethod = mth.id }, label = mth.label)
            }
        }

        // 1. 选择插入片段
        CsStepTitle(I18n.t("1. 选择插入片段 (Insert Source)"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CsChip(selected = usePreset, onClick = { usePreset = true }, label = I18n.t("预设片段"))
            CsChip(selected = !usePreset, onClick = { usePreset = false }, label = I18n.t("自定义"))
        }
        if (usePreset) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CS_PRESETS.forEachIndexed { i, pr ->
                    CsChip(selected = selectedInsertIdx == i, onClick = { selectedInsertIdx = i }, label = pr.name)
                }
            }
        } else {
            OutlinedTextField(
                value = customInsertName, onValueChange = { customInsertName = it }, singleLine = true,
                placeholder = { Text(I18n.t("片段名 (e.g. EGFP)"), fontSize = 10.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customInsertSeq, onValueChange = { customInsertSeq = it },
                placeholder = { Text(I18n.t("粘贴 DNA 序列 (ATCG)..."), fontSize = 9.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 方法特定参数
        when (selectedMethod) {
            CloningSim.M_RESTRICTION -> {
                CsStepTitle(I18n.t("2. 酶切位点 (Enzyme Settings)"))
                CsDimText(I18n.t("5' 侧限制酶 (左)"))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "SalI" to "SalI (G^TCGAC)", "EcoRI" to "EcoRI (G^AATTC)", "XhoI" to "XhoI (C^TCGAG)",
                        "BamHI" to "BamHI (G^GATCC)", "HindIII" to "HindIII (A^AGCTT)", "NdeI" to "NdeI (CA^TATG)",
                        "SacI" to "SacI (GAGCT^C)", "KpnI" to "KpnI (GGTAC^C)"
                    ).forEach { (v, label) -> CsChip(selected = vectorLeftEnzyme == v, onClick = { vectorLeftEnzyme = v }, label = label) }
                }
                CsDimText(I18n.t("3' 侧限制酶 (右)"))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "KpnI" to "KpnI (GGTAC^C)", "BamHI" to "BamHI (G^GATCC)", "HindIII" to "HindIII (A^AGCTT)",
                        "SalI" to "SalI (G^TCGAC)", "EcoRI" to "EcoRI (G^AATTC)", "XhoI" to "XhoI (C^TCGAG)",
                        "NdeI" to "NdeI (CA^TATG)", "SacI" to "SacI (GAGCT^C)"
                    ).forEach { (v, label) -> CsChip(selected = vectorRightEnzyme == v, onClick = { vectorRightEnzyme = v }, label = label) }
                }
                // 磷酸化状态（main 两栏块）
                CsStepTitle(I18n.t("载体 5' 末端状态 (Vector)"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CsChip(selected = vPhosL, onClick = { vPhosL = !vPhosL }, label = I18n.t("5' 左侧磷酸化"))
                    CsChip(selected = vPhosR, onClick = { vPhosR = !vPhosR }, label = I18n.t("5' 右侧磷酸化"))
                }
                CsStepTitle(I18n.t("插入片段 5' 末端状态 (Insert)"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CsChip(selected = iPhosL, onClick = { iPhosL = !iPhosL }, label = I18n.t("5' 左侧磷酸化"))
                    CsChip(selected = iPhosR, onClick = { iPhosR = !iPhosR }, label = I18n.t("5' 右侧磷酸化"))
                }
            }
            CloningSim.M_GIBSON, CloningSim.M_INFUSION, CloningSim.M_NEBUILDER_HIFI -> {
                CsStepTitle(I18n.t("2. 同源重组参数"))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CsDimText(I18n.t("重叠长度 (bp):"))
                    OutlinedTextField(
                        value = overlapText, onValueChange = { overlapText = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                        modifier = Modifier.width(80.dp)
                    )
                    CsDimText("(${I18n.t("推荐 20-40")})")
                }
                when (selectedMethod) {
                    CloningSim.M_GIBSON -> CsDimText(I18n.t("Gibson: 5' 核酸外切酶 + 高保真 DNA 聚合酶 + Taq 连接酶"))
                    CloningSim.M_INFUSION -> CsDimText(I18n.t("In-Fusion: 同源重组酶介导的同源末端连接"))
                    else -> CsDimText(I18n.t("NEBuilder HiFi: 高保真 DNA 聚合酶 + 重组酶"))
                }
            }
            CloningSim.M_GOLDEN_GATE -> {
                CsStepTitle(I18n.t("2. Golden Gate Type IIS 酶"))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 选项偏移标注按 main 原文 verbatim（原文标注与酶库实际值不一致，1:1 保留）
                    listOf(
                        "BsaI" to "BsaI (GGTCTC 1/5)", "BbsI" to "BbsI (GAAGAC 2/6)", "BsmBI" to "BsmBI (CGTCTC 5/1)",
                        "BtgZI" to "BtgZI (GCGATG 10/14)", "SapI" to "SapI (GCTCTTC 1/4)", "AarI" to "AarI (CACCTGC 4/8)"
                    ).forEach { (v, label) -> CsChip(selected = goldenGateEnzyme == v, onClick = { goldenGateEnzyme = v }, label = label) }
                }
                CsDimText(I18n.t("Type IIS 酶在识别位点下游切割，产生可定制的粘性末端"))
            }
            CloningSim.M_GATEWAY, CloningSim.M_TOPO -> {
                // main：gateway 与 topo 共用 TOPO 参数段（directional 共享状态）
                CsStepTitle(I18n.t("2. TOPO 克隆参数"))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CsChip(selected = directional, onClick = { directional = !directional }, label = I18n.t("定向克隆 (CACC 5' 突出端)"))
                }
                CsDimText(I18n.t("拓扑异构酶 I 共价连接载体与插入片段，无需连接酶"))
            }
            CloningSim.M_TA -> {
                CsStepTitle(I18n.t("2. TA 克隆"))
                CsDimText(I18n.t("Taq 聚合酶在 3' 端添加 A 突出端，与 T 超出端载体连接"))
            }
            CloningSim.M_GC -> {
                CsStepTitle(I18n.t("2. GC (平端) 克隆"))
                CsDimText(I18n.t("平末端连接，无需粘性末端，但效率较低"))
            }
        }

        // 3. 验证状态
        CsStepTitle(I18n.t("3. 验证状态 (Validation)"))
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(6.dp)
        ) {
            if (warnings.isEmpty()) {
                Text("✓ " + I18n.t("所有条件检查通过"), fontSize = 9.sp, color = CsPixGreen)
            } else {
                warnings.forEach { w -> Text("⚠ $w", fontSize = 9.sp, color = CsPixRed) }
            }
        }

        // 拼接示意：载体 → 插入 → Ready/Block
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(I18n.t("载体"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vectorName, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
            }
            Text("→", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(I18n.t("插入片段"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(activeInsert.name.split(" ").first(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixAccent2)
            }
            Text("→", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(I18n.t("重组 Product"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (ready) "Ready" else "Block", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (ready) CsPixGreen else CsPixRed)
            }
        }

        // 动作按钮
        if (ready) {
            Button(onClick = { handleLigate() }, modifier = Modifier.fillMaxWidth()) {
                Text("⚗ " + I18n.t("模拟连接反应 (Simulate Ligation)"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(enabled = false, onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("⚠ " + I18n.t("条件不满足 (Conditions Not Met)"), fontSize = 10.sp)
            }
        }
        // 当前方法描述
        Text(
            CS_METHODS.first { it.id == selectedMethod }.descZh,
            fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 粘性末端拼接细节（仅 restriction）
        if (selectedMethod == CloningSim.M_RESTRICTION) {
            CsStepTitle(I18n.t("末端重组配对细节 (Sticky End Overhangs):"))
            val lo = CS_OVERHANGS[vectorLeftEnzyme]
            if (lo == null) {
                CsDimText(I18n.t("平末端 (Blunt End Ligation)"))
            } else {
                if (lo.is5Prime) {
                    CsJunctionBlock(
                        I18n.tf("5' 拼接区 ({v1}):", vectorLeftEnzyme),
                        "V-End 5'-... ${lo.top}      [Ligate]      ${lo.bot.take(lo.len)}...-3'",
                        "            |                    |",
                        "I-Start 3'-... ${lo.bot.take(lo.len)}   [Ligate]   ${lo.top}...-5'"
                    )
                } else {
                    CsJunctionBlock(
                        I18n.tf("5' 拼接区 ({v1}):", vectorLeftEnzyme),
                        "V-End 5'-... ${lo.top}   [Ligate]   ${lo.top.takeLast(lo.len)}...-3'",
                        "            |                    |",
                        "I-Start 3'-... ${lo.bot.takeLast(lo.len)}      [Ligate]      ${lo.bot}...-5'"
                    )
                }
            }
            val ro = CS_OVERHANGS[vectorRightEnzyme]
            if (ro == null) {
                CsDimText(I18n.t("平末端 (Blunt End Ligation)"))
            } else {
                if (ro.is5Prime) {
                    CsJunctionBlock(
                        I18n.tf("3' 拼接区 ({v1}):", vectorRightEnzyme),
                        "I-End 5'-... ${ro.top}      [Ligate]      ${ro.bot.take(ro.len)}...-3'",
                        "            |                    |",
                        "V-Start 3'-... ${ro.bot.take(ro.len)}   [Ligate]   ${ro.top}...-5'"
                    )
                } else {
                    CsJunctionBlock(
                        I18n.tf("3' 拼接区 ({v1}):", vectorRightEnzyme),
                        "I-End 5'-... ${ro.top}   [Ligate]   ${ro.top.takeLast(ro.len)}...-3'",
                        "            |                    |",
                        "V-Start 3'-... ${ro.bot.takeLast(ro.len)}      [Ligate]      ${ro.bot}...-5'"
                    )
                }
            }
        }
    } else {
        // ── 仿真结果卡（main success 分支 1:1） ──
        Text("✓ " + I18n.t("基因克隆与重组反应 仿真连接成功！"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CsPixGreen)
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CsResultRow(I18n.t("连接重组方法 (Method):"), CS_METHODS.first { it.id == selectedMethod }.label, CsPixCyan)
            CsResultRow(I18n.t("重组产物名称 (Product Name):"), resultName, CsPixAccent2)
            CsResultRow(I18n.t("总碱基长度 (Total Length):"), "${resultSeq.length} bp", CsPixGreen)
            CsResultRow(I18n.t("母本载体 (Parent Vector):"), "$vectorName (${vectorSeq.length} bp)")
            CsResultRow(I18n.t("插入片段 (Insert Part):"), "${activeInsert.name} (${activeInsert.seq.length} bp)")
            if (selectedMethod == CloningSim.M_RESTRICTION) {
                CsResultRow(I18n.t("酶切拼接位点 (Enzymes):"), "$vectorLeftEnzyme / $vectorRightEnzyme", CsPixPurple)
            }
        }
        if (resultWarnings.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0x14FFB703)).padding(6.dp)
            ) {
                Text("⚠ " + I18n.t("连接过程检测到潜在警告 (Warnings):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CsPixAccent2)
                resultWarnings.forEach { w ->
                    Text("• $w", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reset() }, modifier = Modifier.weight(1f)) {
                Text("↩ " + I18n.t("返回重新设计"), fontSize = 10.sp)
            }
            Button(
                onClick = {
                    handleImport(resultSeq, resultName, resultNode)
                    reset()
                },
                modifier = Modifier.weight(2f)
            ) {
                Text("🚀 " + I18n.t("确认导入至物理设计图谱"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // toast 等价物：面板内 status 行
    status?.let {
        Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
