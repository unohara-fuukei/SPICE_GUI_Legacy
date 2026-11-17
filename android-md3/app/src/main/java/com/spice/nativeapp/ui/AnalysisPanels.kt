package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.spice.nativeapp.core.Feature
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.spice.nativeapp.core.SpdLibrary
import com.spice.nativeapp.core.GeneFeatureLite
import com.spice.nativeapp.core.SynBioRegistry
import com.spice.nativeapp.core.SynBio
import com.spice.nativeapp.core.formatMw
import com.spice.nativeapp.core.getAminoAcidStringFromSequenceString
import com.spice.nativeapp.core.applyBackMutationWithOptimization
import com.spice.nativeapp.core.buildLifecyleTraceMap
import com.spice.nativeapp.core.buildProteinView
import com.spice.nativeapp.core.ProteinBiophysics
import com.spice.nativeapp.core.CodeSign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
    BLAST("序列比对、BLAST与系统发育进化舱", "k-mer 相似度比对"),
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
    // 可拖拽浮动窗口（对齐 main floating-panel：分析/多视图面板悬浮在图谱上，[X] 关闭）
    Box {
        FloatingPanel(
            title = com.spice.nativeapp.core.I18n.t(panel.title),
            onDismiss = onDismiss,
            width = 340.dp,
            maxHeight = 500.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(com.spice.nativeapp.core.I18n.t(panel.hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (panel) {
                    AnalysisPanel.PROTEIN -> ProteinView(record, clipboard, onRecordChange)
                    AnalysisPanel.RNA -> RnaView(record)
                    AnalysisPanel.SANGER -> SangerView(record, trace)
                    AnalysisPanel.VALIDATOR -> ValidatorView(record)
                    AnalysisPanel.GENOMICS -> GenomicsView(record, onRecordChange)
                    AnalysisPanel.SYNBIO -> SynbioView(record, onRecordChange)
                    AnalysisPanel.BLAST -> BlastView(record, clipboard)
                    AnalysisPanel.ALIGNMENT -> AlignmentView(record, clipboard, onRecordChange)
                    AnalysisPanel.GC -> GcView(record)
                    AnalysisPanel.IMPORT_ALIGN -> ImportAlignView(clipboard)
                }
            }
        }
    }
}

@Composable
private fun ProteinView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    // T7 块6：main gene/panels/ProteinViewPanel.svelte 1:1（算法 genome/proteinView.ts → core/ProteinView.kt；
    // 结构/溶解性/结构域/PMF 等 → 既有 core/ProteinBiophysics.kt 同名 1:1 移植）。
    // 平台差异（披露）：toast → status 行；select → FilterChip 选项行；bind:selectionStart/End → status 行提示定位区间。
    val t = com.spice.nativeapp.core.I18n
    val pixGreen = Color(0xFF53d769); val pixRed = Color(0xFFff5d5d); val pixCyan = Color(0xFF4cd6ff)
    val pixPurple = Color(0xFFb48cff); val pixYellow = Color(0xFFffd23f); val pixOrange = Color(0xFFff9f1c)
    val pixFgDim = MaterialTheme.colorScheme.onSurfaceVariant

    val dnaSeq = record.sequence
    val aaSeq = remember(dnaSeq) { getAminoAcidStringFromSequenceString(dnaSeq) }
    val protData = remember(dnaSeq) { buildProteinView(dnaSeq) }

    var tab by remember { mutableStateOf(0) } // 0 properties / 1 structure / 2 domains / 3 lifecycle / 4 advanced
    var status by remember { mutableStateOf<String?>(null) }
    var selectedAaIndex by remember { mutableStateOf<Int?>(null) }
    var backMutationAa by remember { mutableStateOf("A") }
    var backMutationHost by remember { mutableStateOf(CodeSign.Host.ECOLI) }

    val traceMap = remember(dnaSeq) { buildLifecyleTraceMap(dnaSeq, 0, dnaSeq.length - 1) }
    val currentTrace = selectedAaIndex?.let { traceMap.getOrNull(it) }
    val ddg = currentTrace?.let { ct -> ProteinBiophysics.mutationalStabilityDdg(ct.aminoAcid.firstOrNull() ?: 'X', selectedAaIndex!! + 1, backMutationAa.first()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Sub-Tab Selector（main 5 tab pix-btn 行）
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(t.t("物化属性"), t.t("折叠结构"), t.t("功能域"), t.t("双向追溯"), t.t("质谱/免疫")).forEachIndexed { i, label ->
                androidx.compose.material3.FilterChip(
                    selected = tab == i, onClick = { tab = i },
                    label = { Text(label, fontSize = 9.sp, maxLines = 1, color = if (i == 3) pixCyan else if (i == 4) pixYellow else MaterialTheme.colorScheme.onSurface) }
                )
            }
        }
        status?.let { Text(it, fontSize = 10.sp, color = if (it.startsWith("✓")) pixGreen else pixRed) }

        when (tab) {
            // TAB 1: PROPERTIES
            0 -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("长度 (Length):"), fontSize = 10.sp, color = pixFgDim)
                    Text("${protData.properties.length} aa", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("分子量:"), fontSize = 10.sp, color = pixFgDim)
                    Text(formatMw(protData.properties.molecularWeight), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("分子量:"), fontSize = 10.sp, color = pixFgDim) // protViewPiLabel（zh 原文即"分子量:"）
                    Text("${protData.properties.isoelectricPoint}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("消光系数 (A280):"), fontSize = 10.sp, color = pixFgDim)
                    Text("${protData.properties.extinctionCoeff}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("分子量:"), fontSize = 10.sp, color = pixFgDim) // protViewAromaticityLabel（zh 原文即"分子量:"）
                    Text("${protData.properties.aromaticity}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("分子量:"), fontSize = 10.sp, color = pixFgDim) // protViewGravyLabel（zh 原文即"分子量:"）
                    Text("${protData.properties.gravy}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = if (protData.properties.gravy > 0) pixOrange else pixGreen)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("电荷 (pH 7):"), fontSize = 10.sp, color = pixFgDim)
                    Text("${protData.properties.charge}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                Text(t.t("翻译后修饰预测 (PTM Sites):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 90.dp).verticalScroll(rememberScrollState())) {
                    val shown = protData.ptms.take(15)
                    if (shown.isEmpty()) {
                        Text(t.t("未检测到典型 PTM 共基序"), fontSize = 8.sp, color = pixFgDim, modifier = Modifier.padding(vertical = 4.dp))
                    } else shown.forEach { ptm ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ptm.type.name.lowercase(), fontSize = 8.5.sp, color = pixYellow)
                            Text("pos ${ptm.position + 1} (${ptm.residue})", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            // TAB 2: STRUCTURE & FOLDING
            1 -> {
                val cfRes = remember(aaSeq) { ProteinBiophysics.chouFasman(aaSeq) }
                val totalHelix = cfRes.count { it.second == 'H' }
                val totalSheet = cfRes.count { it.second == 'E' }
                val helixPct = Math.round(totalHelix * 100.0 / maxOf(1, aaSeq.length))
                val sheetPct = Math.round(totalSheet * 100.0 / maxOf(1, aaSeq.length))
                val coilPct = 100 - helixPct - sheetPct
                val kdProfile = remember(aaSeq) { ProteinBiophysics.kyteDoolittleProfile(aaSeq, 9) }
                val solData = remember(aaSeq) { ProteinBiophysics.solubilityAndAggregation(aaSeq) }

                Text(t.t("二级结构倾向性:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(t.t("螺旋 (H)"), fontSize = 8.sp, color = pixGreen)
                        Text("$helixPct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(t.t("Beta 折叠 (E)"), fontSize = 8.sp, color = pixCyan)
                        Text("$sheetPct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(t.t("无规则卷曲 (C)"), fontSize = 8.sp, color = pixFgDim)
                        Text("$coilPct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("固有无序比率 (Disorder):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
                    // main: (gravy < -1.2 ? 0.75 : 0.12).toFixed(2)
                    Text(if (protData.properties.gravy < -1.2) "0.75" else "0.12", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixPurple, fontFamily = FontFamily.Monospace)
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("大肠杆菌重组表达可溶性概率 (WH):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
                    Text("${solData.solubilityScore}%", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (solData.solubilityScore >= 50) pixGreen else pixRed, fontFamily = FontFamily.Monospace)
                }
                Text(t.t("核心聚集高风险段预测:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                Column(Modifier.fillMaxWidth().heightIn(max = 60.dp).verticalScroll(rememberScrollState())) {
                    if (solData.aggregationHotspots.isEmpty()) {
                        Text("✓ " + t.t("未检测到高聚集倾向的 β-片层聚集核"), fontSize = 8.sp, color = pixGreen, modifier = Modifier.padding(vertical = 2.dp))
                    } else solData.aggregationHotspots.forEach { spot ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("AA ${spot.start + 1}-${spot.end + 1}: ${spot.sequence}", fontSize = 8.sp, color = pixRed, fontFamily = FontFamily.Monospace)
                            Text(t.tf("倾向率: {v1}%", spot.score), fontSize = 8.sp, color = pixRed)
                        }
                    }
                }
                Text(t.t("疏水性滑动曲线 (Window=9):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                // Sparkline（main: 45 柱，(score+4.5)/9*100 高度）
                val step = maxOf(1, kdProfile.size / 45)
                val sampled = kdProfile.filterIndexed { idx, _ -> idx % step == 0 }
                Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    sampled.forEach { score ->
                        val rawH = ((score + 4.5) / 9.0 * 100).coerceIn(0.0, 100.0)
                        Box(Modifier.weight(1f).fillMaxHeight((rawH / 100.0).toFloat())
                            .background(if (score > 1.5) pixRed else if (score > 0) pixOrange else pixCyan))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("N-terminal", fontSize = 7.5.sp, color = pixFgDim)
                    Text("C-terminal", fontSize = 7.5.sp, color = pixFgDim)
                }
            }
            // TAB 3: DOMAINS
            2 -> {
                val domains = remember(aaSeq) { ProteinBiophysics.proteinDomains(aaSeq) }
                Text(t.t("结构域共识特征检索:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (domains.isEmpty()) {
                        Text(t.t("未检测到典型特定的结构域共有签名。"), fontSize = 9.sp, color = pixFgDim, modifier = Modifier.padding(vertical = 12.dp))
                    } else domains.forEach { dom ->
                        Column(Modifier.fillMaxWidth().background(Color(0x084cd6ff)).padding(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(dom.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                                Text("AA ${dom.start + 1}-${dom.end + 1}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = pixCyan)
                            }
                            Text(dom.desc, fontSize = 8.sp, color = pixFgDim)
                        }
                    }
                }
            }
            // TAB 4: LIFECYCLE TRACE
            3 -> {
                Text(t.t("双向读码追溯:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Text(t.t("双击特定氨基酸残基在主工作区中定位对应 DNA 密码子，或直接在下方执行密码子反向优化突变："), fontSize = 10.sp, color = pixFgDim)
                // 水平残基滚动条（main 18px 按钮流；双击定位 → 点击 + status 提示）
                Row(Modifier.fillMaxWidth().heightIn(max = 60.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    traceMap.forEachIndexed { idx, tm ->
                        val sel = selectedAaIndex == tm.codonIndex
                        Column(
                            Modifier
                                .width(20.dp)
                                .background(if (sel) pixOrange else Color(0x08FFFFFF))
                                .clickable {
                                    selectedAaIndex = tm.codonIndex
                                    status = "ℹ " + t.t("核苷酸定位") + ": " + t.tf("残基 {v1}（密码子 {v2}）对应 {v3}-{v4} bp",
                                        tm.aminoAcid, idx + 1, tm.dnaStart + 1, tm.dnaEnd + 1)
                                },
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(tm.aminoAcid, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                color = if (sel) Color.Black else MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
                            Text("${idx + 1}", fontSize = 7.sp, color = pixFgDim)
                        }
                    }
                }
                currentTrace?.let { ct ->
                    Column(Modifier.fillMaxWidth().background(Color(0x054cd6ff)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.tf("已选中残基: {v1}{v2}", ct.aminoAcid, ct.codonIndex + 1), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                            Text(t.tf("当前密码子: {v1}", ct.codonSeq), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                        Text(t.t("反向翻译密码子偏好突变 (Back-mutation & Codon Optimize):"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                        Text(t.t("目标 AA:"), fontSize = 9.sp, color = pixFgDim)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            "ACDEFGHIKLMNPQRSTVWY*".forEach { aa ->
                                androidx.compose.material3.FilterChip(
                                    selected = backMutationAa == aa.toString(), onClick = { backMutationAa = aa.toString() },
                                    label = { Text(aa.toString(), fontSize = 9.sp) }
                                )
                            }
                        }
                        Text(t.t("优化宿主:"), fontSize = 9.sp, color = pixFgDim)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                CodeSign.Host.ECOLI to t.t("大肠杆菌 (E. coli)"),
                                CodeSign.Host.YEAST to t.t("酵母 (毕赤酵母)"),
                                CodeSign.Host.HUMAN to t.t("人 (智人)")
                            ).forEach { (h, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = backMutationHost == h, onClick = { backMutationHost = h },
                                    label = { Text(label, fontSize = 9.sp, maxLines = 1) }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                try {
                                    val res = applyBackMutationWithOptimization(
                                        dnaSeq, 0, selectedAaIndex!!, backMutationAa, backMutationHost
                                    )
                                    onRecordChange(record.copy(sequence = res.updatedDna))
                                    status = "✓ " + t.tf("突变反向回写: {v1}{v2} ➔ {v3}，基于 {v4} 密码子偏好选用 [{v5}]",
                                        ct.aminoAcid, (selectedAaIndex!! + 1).toString(), backMutationAa,
                                        t.t(backMutationHost.label), res.introducedCodon)
                                    selectedAaIndex = null
                                } catch (e: Exception) {
                                    status = "✗ " + t.t("突变失败") + ": ${e.message}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                        ) { Text(t.t("🚀 执行密码子反向突变并写入质粒"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        ddg?.let { d ->
                            androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t.t("经验场 ΔΔG 突变稳定性预测:"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                                Text("${d.ddG} kcal/mol", fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                                    color = if (d.ddG < -0.2) pixGreen else if (d.ddG > 0.2) pixRed else MaterialTheme.colorScheme.onSurface)
                            }
                            Text("${d.classificationZh} · ${d.classification}", fontSize = 7.5.sp, color = pixFgDim)
                        }
                    }
                }
            }
            // TAB 5: PMF / DISULFIDE / IMMUNO / CHARGE
            else -> {
                val pmf = remember(aaSeq) { ProteinBiophysics.peptideMassFingerprint(aaSeq) }
                val disulfide = remember(aaSeq) { ProteinBiophysics.disulfideBonds(aaSeq) }
                val immuno = remember(aaSeq) { ProteinBiophysics.antigenicity(aaSeq, 7) }
                val pIDist = remember(aaSeq) { ProteinBiophysics.chargeDistribution(aaSeq, 30) }

                Text(t.t("胰蛋白酶切肽质量指纹谱 (Trypsin PMF):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 90.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(t.t("区间"), fontSize = 8.sp, color = pixCyan, modifier = Modifier.weight(0.35f))
                        Text(t.t("肽段序列"), fontSize = 8.sp, color = pixCyan, modifier = Modifier.weight(1f))
                        Text(t.t("区间"), fontSize = 8.sp, color = pixCyan) // protViewPmfMassTh（zh 原文即"区间"）
                    }
                    pmf.take(20).forEach { fp ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${fp.start}-${fp.end}", fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.35f))
                            Text(fp.sequence, fontSize = 8.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("${fp.mass}", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = pixGreen)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                Text(t.t("二硫键连接对预测 (Disulfide Bonds):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 60.dp).verticalScroll(rememberScrollState())) {
                    if (disulfide.isEmpty()) {
                        Text("✓ " + t.t("未发现倾向性配对 Cys 离子对"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.padding(vertical = 2.dp))
                    } else disulfide.forEach { bond ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cys${bond.cys1} - Cys${bond.cys2}", fontSize = 8.sp, color = pixGreen)
                            Text("%.2f".format(bond.pairingProbability.toDouble()), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                Text(t.t("细胞抗原线性表位 (Immunogenicity):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 60.dp).verticalScroll(rememberScrollState())) {
                    if (immuno.epitopes.isEmpty()) {
                        Text(t.t("未检测到高强度免疫抗原性特征段"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.padding(vertical = 2.dp))
                    } else immuno.epitopes.forEach { ep ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("AA ${ep.start}-${ep.end}: ${ep.sequence}", fontSize = 8.sp, color = pixYellow, fontFamily = FontFamily.Monospace)
                            Text("Score: ${ep.averageScore}", fontSize = 8.sp, color = pixGreen)
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0x1AFFFFFF))
                Text(t.t("局部滑动等电点与电荷分布 (Domain pI):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                Column(Modifier.fillMaxWidth().heightIn(max = 60.dp).verticalScroll(rememberScrollState())) {
                    pIDist.forEach { d ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.tf("位置 ~{v1} AA", d.position), fontSize = 8.sp, color = pixFgDim)
                            val chargeTxt = (if (d.chargeAtPh7 > 0) "+" else "") + "%.1f".format(d.chargeAtPh7)
                            Text("%.1f | $chargeTxt".format(d.pI), fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RnaView(record: SeqRecord) {
    // Nussinov 滑窗折叠（对齐 main 的 rnaStructure.ts findRnaStructures；截前 2400 nt 保证移动端性能）
    val capped = record.sequence.take(2400)
    val rna = remember(capped) { RnaStructure.findStructures(capped, isRna = true) }
    Text(com.spice.nativeapp.core.I18n.t("转录（前 300 nt）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Text(
        record.sequence.take(300).uppercase().chunked(60).joinToString("\n"),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    HorizontalDivider()
    Text(
        com.spice.nativeapp.core.I18n.tf("Nussinov 二级结构（滑窗 120/60{v1}）", if (capped.length < record.sequence.length) com.spice.nativeapp.core.I18n.t("，已截取前 2400 nt") else "") +
            com.spice.nativeapp.core.I18n.tf(" · 窗口结构 {v1} 个 · 总体 MFE {v2} kcal/mol", rna.structures.size, rna.overallMfe),
        fontSize = 11.sp, fontWeight = FontWeight.Bold
    )
    val best = rna.longestHairpin
    if (best == null) {
        Text(com.spice.nativeapp.core.I18n.t("未发现稳定配对结构。"), fontSize = 11.sp)
        return
    }
    Text(
        com.spice.nativeapp.core.I18n.tf("最长配对窗口：{v1} nt · 配对 {v2} 对 · 发夹 {v3} 个 · MFE {v4} · GC {v5}%", best.sequence.length, best.stemCount, best.hairpinCount, best.mfe, best.gcContent),
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
        Text(com.spice.nativeapp.core.I18n.t("发夹环（前 10）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        best.stemLoops.take(10).forEach {
            Text(com.spice.nativeapp.core.I18n.tf("· 环 {v1}–{v2}（{v3} nt 环内）", it.start + 1, it.end + 1, it.end - it.start - 1), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SangerView(record: SeqRecord, trace: com.spice.nativeapp.core.AbiReader.Trace?) {
    if (trace == null) {
        Text(com.spice.nativeapp.core.I18n.t("尚未导入色谱。用「文件 → 导入测序色谱 (.ab1)」载入后在此查看峰图并做克隆验证。"), fontSize = 12.sp)
        return
    }
    var query by remember(trace) { mutableStateOf(trace.bases) }
    var winStart by remember(trace) { mutableStateOf(0) }
    Text(com.spice.nativeapp.core.I18n.tf("read {v1} nt（参考 {v2} bp）", trace.bases.length, record.length) +
        (if (trace.qualities.isNotEmpty()) com.spice.nativeapp.core.I18n.tf(" · 平均 Q {v1}", "%.1f".format(trace.qualities.average())) else ""), fontSize = 11.sp)

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
            OutlinedButton(onClick = { winStart = (winStart - window).coerceAtLeast(0) }, modifier = Modifier.heightIn(min = 40.dp)) { Text(com.spice.nativeapp.core.I18n.t("← 前")) }
            Text("${winStart + 1}–${minOf(winStart + window, trace.bases.length)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = { winStart = (winStart + window).coerceAtMost(maxStart) }, modifier = Modifier.heightIn(min = 40.dp)) { Text(com.spice.nativeapp.core.I18n.t("后 →")) }
        }
    } else {
        Text(com.spice.nativeapp.core.I18n.t("（该文件无峰通道数据，仅展示碱基与比对）"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    HorizontalDivider()

    // ── 一键克隆验证（对齐 main 的 validateClone 语义）──
    var validate by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { validate = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("一键克隆验证")) }
    }
    if (validate && query.length >= 20) {
        // T7-13：validateClone 换 main 1:1 新 API（status/classification/description；
        // CDS 坐标按 main quirk 原样透传，不再 -1 换算）
        val cds = record.features.filter { it.type.equals("CDS", true) }.map { CloneValidation.CdsFeature(it.name, it.start, it.end, true) }
        val v = remember(record.sequence, query) { CloneValidation.validateClone(record.sequence, query, cds) }
        Text(
            (if (v.passed) "✓ " else "✗ ") + v.status,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (v.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            com.spice.nativeapp.core.I18n.tf("一致性 {v1}% · 覆盖 {v2}%", "%.2f".format(v.identity), "%.1f".format(v.coverage)) +
                (if (v.warnings.any { w -> w.contains("反向互补") }) com.spice.nativeapp.core.I18n.t(" · 已反向互补重比对") else ""),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        v.warnings.forEach { Text("⚠ $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        if (v.variants.isNotEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("变异效应（前 24）"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            v.variants.take(24).forEach { ve ->
                val color = when (ve.classification) {
                    "Nonsense", "Frameshift", "Deletion", "Insertion" -> MaterialTheme.colorScheme.error
                    "Missense" -> Color(0xFFB45309)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "· " + (ve.refPos + 1) + " " + ve.refSeq.ifEmpty { "—" } + "→" + ve.querySeq.ifEmpty { "—" } + " [" + ve.classification + "] " + ve.description,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = color
                )
            }
        } else {
            Text(com.spice.nativeapp.core.I18n.t("未检出变异。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }

    HorizontalDivider()
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.uppercase().filter { c -> c in "ACGTN" } },
        label = { Text(com.spice.nativeapp.core.I18n.t("测序 read（可编辑/修剪后重新验证）")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )
    val cmp = remember(record.sequence, query) {
        if (query.length < 20) null else SangerValidate.align(record.sequence, query)
    }
    if (cmp != null) {
        Text(com.spice.nativeapp.core.I18n.tf("{v1} · 一致性 {v2}%", cmp.verdict, "%.2f".format(cmp.identityPercent)), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            com.spice.nativeapp.core.I18n.tf("匹配 {v1} · 错配 {v2} · 插入 {v3} · 缺失 {v4} · 覆盖 {v5}–{v6}", cmp.identities, cmp.mismatches, cmp.insertions, cmp.deletions, cmp.coverageStart, cmp.coverageEnd),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        cmp.warnings.forEach { Text("⚠ $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * T7-13：main src/lib/gene/CloningValidator.svelte（225 行）1:1
 * （Sanger 一键克隆变异校验：粘贴 read → 半全局比对 + VEP 变异表 + 决策徽章 +
 * traceQuality Q-score 微条形图）。算法层 core/CloneValidation.kt
 * （lib/genome/cloningValidation.ts 1:1，CloneValidatorParityTest 守护）。
 * 平台差异（披露）：
 * - pushToast/pushLog → 面板 status 行合并显示（valPassLog/valWarnLog/valFailLog 为
 *   命名占位符 {identity}/{coverage}/{length}，与 I18n.tf 的 {vN} 不同，手动 replace）；
 * - input[type=number] step 0.1 → 数字键盘 OutlinedTextField；
 * - checkbox → MD3 Checkbox；lucide 图标 → 文本符号（✓/⚠）；
 * - lucide 图标徽章 24px 图标 → 文本符号；VEP 表格 → 逐行数据行（同 GenomicsView 先例）；
 * - 旧自造「ORF 扫描/读框 QC」段落已按 1:1 移除（main 该面板无此内容）。
 */
@Composable
private fun ValidatorView(record: SeqRecord) {
    val t = com.spice.nativeapp.core.I18n
    val pixGreen = Color(0xFF53d769); val pixRed = Color(0xFFff5d5d); val pixYellow = Color(0xFFffd23f)
    val pixCyanValidator = Color(0xFF4cd6ff)
    val pixFgDim = MaterialTheme.colorScheme.onSurfaceVariant
    val pixWell = Color(0xFF141724)

    val dnaSeq = record.sequence
    // main 默认 read：蓝色荧光蛋白（BFP）序列（verbatim）
    var querySeq by remember { mutableStateOf("TCGACATGGTGTCTAAGGGCGAAGAGCTGATTAAGGAGAACATGCACATGAAGCTGTACATGGAGGGCACCGTGGACAACCATCACTTCAAGTGCACATCCGAGGGCGAAGGCAAGCCCTACGAGGGCACCCAGACCATGAGAATCAAGGTGGTCGAGGGCGGCCCTCTCCCCTTCGCCTTCGACATCCTGGCTACTAGCTTCCTCTACGGCAGCAAGACCTTCATCAACCACACCCAGGGCATCCCCGACTTCTTCAAGCAGTCCTTCCCTGAGGGCTTCACATGGGAGAGAGTCACCACATACGAAGACGGGGGCGTGCTGACCGCTACCCAGGACACCAGCCTCCAGGACGGCTGCCTCATCTACAACGTCAAGATCAGAGGGGTGAACTTCACATCCAATGGCCCTGTGATGCAGAAGAAAACACTCGGCTGGGAGGCCTTCACCGAGACGCTGTACCCCGCTGACGGCGGCCTGGAAGGCAGAAACGACATGGCCCTGAAGCTCGTGGGCGGGAGCCATCTGATCGCAAACATCAAGACCACATATAGATCCAAGAAACCCGCTAAGAACCTCAAGATGCCCGGCGTCTACTATGTGGACTACAGACTGGAAAGAATCAAGGAGGCCAACAACGAGACCTACGTCGAGCAGCACGAGGTGGCAGTGGCCAGATACTGCGACCTCCCTAGCAAACTGGGGCACAAACTTAATGGTACC") }
    var minIdentityText by remember { mutableStateOf("99.0") } // main minIdentity $state(99.0)
    val minIdentity = minIdentityText.toDoubleOrNull() ?: 99.0
    var allowSynonymous by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<CloneValidation.Result?>(null) }
    var isRun by remember { mutableStateOf(false) }

    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusOk = ok
        status = (if (ok) "✓ " else "⚠ ") + title + (text?.let { " — " + it } ?: "")
    }

    // main cdsFeatures $derived：type cds/feature → CDS（forward 简化恒 true；坐标原样透传）
    val cdsFeatures = remember(record.features) {
        record.features
            .filter { f -> f.type.lowercase() == "cds" || f.type.lowercase() == "feature" }
            .map { f -> CloneValidation.CdsFeature(f.name.ifEmpty { "CDS_Feature" }, f.start, f.end, true) }
    }

    fun runValidation() {
        if (dnaSeq.isEmpty() || dnaSeq.length < 10) {
            toast(false, t.t("验证失败"), t.t("载体/模板序列为空或过短"))
            return
        }
        if (querySeq.isEmpty() || querySeq.length < 10) {
            toast(false, t.t("验证失败"), t.t("测序查询序列为空或过短"))
            return
        }
        try {
            val res = CloneValidation.validateClone(
                referenceSequence = dnaSeq,
                querySequence = querySeq,
                cdsFeatures = cdsFeatures,
                minIdentityToPass = minIdentity,
                minCoverageToPass = 90.0, // main minCoverage $state(90.0)（无输入框）
                allowSynonymousMutations = allowSynonymous
            )
            result = res
            isRun = true
            if (res.passed) {
                toast(true, t.t("克隆验证通过 (PASS)"),
                    t.t("一致性") + ": ${res.identity}% | " + t.t("突变数") + ": ${res.variants.size}")
                status += " · " + t.t("验证通过：测序比对一致性为 {identity}%，覆盖度 {coverage}%，未检测到有害突变")
                    .replace("{identity}", res.identity.toString()).replace("{coverage}", res.coverage.toString())
            } else {
                if (res.status == "WARNING") {
                    toast(false, t.t("克隆验证有警告"),
                        t.t("检测到") + " ${res.variants.size} " + t.t("处良性/同义突变"))
                    status += " · " + t.t("克隆验证警告：检测到 {length} 处非关键性单碱基变异")
                        .replace("{length}", res.variants.size.toString())
                } else {
                    toast(false, t.t("克隆验证未通过 (FAIL)"), t.t("发现关键位置突变或一致性不达标"))
                    status += " · " + t.t("克隆验证失败：在 CDS 区域内检测到关键变异或发生了移码突变！")
                }
            }
        } catch (e: Exception) {
            toast(false, t.t("运行时异常"), e.message ?: e.javaClass.simpleName)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 输入区 ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.t("高通量克隆测序一键比对与变异判定"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.t("输入 Sanger 测序序列 (5' -> 3' DNA):"), fontSize = 10.sp, color = pixFgDim)
                OutlinedTextField(
                    value = querySeq,
                    onValueChange = { querySeq = it.uppercase().filter { c -> c.isLetter() || c == '-' } },
                    placeholder = { Text(t.t("粘贴 Sanger 测序结果..."), fontSize = 9.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(t.t("最低一致性阈值 (%):"), fontSize = 10.sp, color = pixFgDim)
                    OutlinedTextField(
                        value = minIdentityText,
                        onValueChange = { v -> minIdentityText = v.filter { it.isDigit() || it == '.' }.take(6) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(52.dp)
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(t.t("允许同义突变:"), fontSize = 10.sp, color = pixFgDim)
                    Checkbox(checked = allowSynonymous, onCheckedChange = { allowSynonymous = it })
                }
            }
            Button(
                onClick = { runValidation() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = pixGreen),
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
            ) { Text("✓ " + t.t("一键自动克隆变异注释与判定"), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }

        // ── 校验报告 ──
        if (isRun && result != null) {
            val res = result!!
            val (badgeBorder, badgeText) = when (res.status) {
                "PASS" -> pixGreen to pixGreen
                "WARNING" -> pixYellow to pixYellow
                else -> pixRed to pixRed
            }
            Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 状态徽章
                Row(
                    Modifier.fillMaxWidth().background(badgeText.copy(alpha = 0.15f)).border(2.dp, badgeBorder).padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (res.status == "PASS") "✓" else "⚠", fontSize = 20.sp, color = badgeText)
                        Column {
                            Text(t.t("克隆测序比对结果: ") + res.status, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = badgeText)
                            Text(t.t("比对一致性:") + " ${res.identity}% | " + t.t("参考序列覆盖度:") + " ${res.coverage}%", fontSize = 9.sp, color = pixFgDim)
                        }
                    }
                    Text("${res.variants.size} " + t.t("处突变"), fontSize = 10.sp)
                }

                // 警告区
                if (res.warnings.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().background(Color(0x05ff5d5d)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        res.warnings.forEach { warn -> Text("• $warn", fontSize = 9.sp, color = pixYellow) }
                    }
                }

                // VEP 表
                Text(t.t("突变功能效应预测 (Variant Effect Prediction):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixYellow)
                if (res.variants.isEmpty()) {
                    Text(t.t("🎉 完美匹配！未在比对区域内发现任何碱基突变。"), fontSize = 9.sp, color = pixGreen, modifier = Modifier.fillMaxWidth().background(pixWell).padding(10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    Column(Modifier.fillMaxWidth().background(pixWell).padding(vertical = 2.dp)) {
                        // 表头
                        Row(Modifier.fillMaxWidth().background(Color(0x0AFFFFFF)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Text(t.t("位置"), fontSize = 9.sp, modifier = Modifier.weight(0.16f), color = pixFgDim)
                            Text(t.t("变异"), fontSize = 9.sp, modifier = Modifier.weight(0.24f), color = pixFgDim)
                            Text(t.t("所属编码区"), fontSize = 9.sp, modifier = Modifier.weight(0.22f), color = pixFgDim)
                            Text(t.t("蛋白质改变"), fontSize = 9.sp, modifier = Modifier.weight(0.16f), color = pixFgDim)
                            Text(t.t("效应分类"), fontSize = 9.sp, modifier = Modifier.weight(0.22f), color = pixFgDim)
                        }
                        res.variants.forEach { variant ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text("${variant.refPos + 1} bp", fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.16f))
                                Text("${variant.refSeq.ifEmpty { "-" }} ➔ ${variant.querySeq.ifEmpty { "-" }}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = pixCyanValidator, modifier = Modifier.weight(0.24f))
                                Text(if (variant.inCds) (variant.cdsName ?: "") else t.t("非编码区"), fontSize = 9.sp, color = pixFgDim, modifier = Modifier.weight(0.22f))
                                Text(
                                    if (variant.inCds && variant.originalAminoAcid != null)
                                        "${variant.originalAminoAcid}${(variant.codonIndex ?: 0) + 1}${variant.mutatedAminoAcid}"
                                    else "-",
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.16f)
                                )
                                Text(
                                    variant.classification ?: "-",
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    color = when (variant.classification) {
                                        "Synonymous" -> pixGreen
                                        "Intronic/Intergenic" -> pixFgDim
                                        else -> pixRed
                                    },
                                    modifier = Modifier.weight(0.22f)
                                )
                            }
                        }
                    }
                }

                // traceQuality Q-score 微条形图
                res.traceQuality?.let { tq ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.t("Sanger 测序信号质量分布:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
                            Text(t.t("平均 Q-score") + ": ${tq.averageQ} (" + t.t("高质") + " Q30+: ${tq.highQualityPercent}%)", fontSize = 9.sp, color = pixGreen)
                        }
                        // main：每 max(1, floor(n/60)) 取一点；高 ≥30 绿、≥20 黄、其余红
                        val qs = tq.qScores
                        val step = maxOf(1, qs.size / 60)
                        Row(Modifier.fillMaxWidth().height(24.dp).background(Color(0x33000000)).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            qs.filterIndexed { i, _ -> i % step == 0 }.forEach { q ->
                                Box(
                                    Modifier.weight(1f).fillMaxHeight((q / 60.0).coerceIn(0.0, 1.0).toFloat())
                                        .background(when {
                                            q >= 30 -> pixGreen
                                            q >= 20 -> pixYellow
                                            else -> pixRed
                                        })
                                )
                            }
                        }
                    }
                }
            }
        }

        status?.let { Text(it, fontSize = 9.sp, color = if (statusOk) pixGreen else pixRed, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun GenomicsView(record: SeqRecord, onRecordChange: (SeqRecord) -> Unit) {
    val t = com.spice.nativeapp.core.I18n
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pixGreen = Color(0xFF53d769); val pixRed = Color(0xFFff5d5d); val pixCyan = Color(0xFF4cd6ff)
    val pixPurple = Color(0xFFb48cff); val pixYellow = Color(0xFFffd23f); val pixOrange = Color(0xFFff9f1c)
    val pixFgDim = MaterialTheme.colorScheme.onSurfaceVariant

    val dnaSeq = record.sequence
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var rbsAtgText by remember { mutableStateOf("100") } // main rbsAtgIndex $state(100)

    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusOk = ok
        status = (if (ok) "✓ " else if (title == t.t("导入警告")) "⚠ " else "✗ ") + title + (text?.let { " — " + it } ?: "")
    }

    // main $derived 组
    val rbsAtgIndex = rbsAtgText.toIntOrNull() ?: 100
    val rbs = remember(dnaSeq, rbsAtgIndex) { GenomicsTools.calculateRbsStrength(dnaSeq, rbsAtgIndex - 1) }
    val tfbsMatches = remember(dnaSeq) { GenomicsTools.predictTfbsMotifs(dnaSeq) }
    val promoters = remember(dnaSeq) { GenomicsTools.predictPromoters(dnaSeq) }
    val aaSeq = remember(dnaSeq) { getAminoAcidStringFromSequenceString(dnaSeq) }
    val localization = remember(aaSeq) { GenomicsTools.predictSignalPeptide(aaSeq) }
    val tmHelices = remember(aaSeq) { GenomicsTools.predictTransmembraneHelices(aaSeq) }
    val sk = remember(dnaSeq) { GenomicsTools.calculateSkew(dnaSeq, "gc", 100, 20) }
    val cpgs = remember(dnaSeq) { GenomicsTools.detectCpGIslands(dnaSeq) }
    val tReps = remember(dnaSeq) { GenomicsTools.detectTandemRepeats(dnaSeq) }

    // §1 GFF3/GTF/BED 导入（main onchange：按后缀分派解析 → 追加特征表 → toast + log）
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            var fname = ""
            runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) fname = c.getString(idx)
                }
            }
            val lower = fname.lowercase()
            val parsed: List<GenomicsTools.GenomicFeature> = when {
                lower.endsWith(".gff") || lower.endsWith(".gff3") -> GenomicsTools.parseGFF3(text)
                lower.endsWith(".gtf") -> GenomicsTools.parseGTF(text)
                lower.endsWith(".bed") -> GenomicsTools.parseBED(text)
                else -> emptyList()
            }
            if (parsed.isNotEmpty()) {
                val mapped = parsed.map { feat ->
                    Feature(
                        name = feat.name,
                        type = feat.type,
                        start = feat.start + 1,
                        end = feat.end + 1,
                        strand = if (feat.strand == "-") -1 else 1,
                        color = if (feat.type == "cds") "#b48cff" else "#ff9f1c"
                    )
                }
                onRecordChange(record.copy(features = record.features + mapped))
                toast(true, t.t("导入基因组注释成功！"), t.tf("成功解析出 {v1} 个注释特征", parsed.size))
                // main pushLog(m.genomicsImportLog{fname, n})：native 无独立日志区，折入 status 第二行
                status = (status ?: "") + "\n" + t.tf(
                    "[Genomics] 成功解析并导入基因组文件 \"{v1}\"，提取出 {v2} 个特征结构并载入特征表。",
                    fname, parsed.size
                )
            } else {
                toast(false, t.t("导入警告"), t.t("未从文件中识别到有效注释行"))
            }
        } catch (e: Exception) {
            toast(false, t.t("解析错误"), e.message ?: e.javaClass.simpleName)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t.t("高级基因组学与转录分析"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixYellow)

        // ── §1 导入基因组注释文件 ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.t("1. 导入基因组注释文件 (GFF3/GTF/BED)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            Button(
                onClick = { launcher.launch("*/*") },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = pixCyan),
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
            ) { Text("📂 GFF3 / GTF / BED", fontSize = 9.sp) }
        }

        // ── §2 Salis RBS ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.t("2. Salis 密码子翻译起始热力学 (RBS Strength)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(t.t("CDS 起始 ATG 位置 (bp):"), fontSize = 9.sp, color = pixFgDim, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = rbsAtgText,
                    onValueChange = { v -> rbsAtgText = v.filter { it.isDigit() }.take(9) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(60.dp)
                )
            }
            Column(Modifier.fillMaxWidth().background(Color(0x33000000)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("TIR 翻译起始率:"), fontSize = 8.5.sp, color = pixFgDim)
                    Text("${rbs.tirScore} (Arbitrary units)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("16S aSD 结合能 (dG_SD):"), fontSize = 8.5.sp, color = pixFgDim)
                    Text("${rbs.deltaGSD} kcal/mol", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("Spacer 间距惩罚 (dG_spacer):"), fontSize = 8.5.sp, color = pixFgDim)
                    Text("${rbs.deltaGSpacer} kcal/mol " + t.tf("(间距: {v1}bp)", rbs.spacerLength), fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("总热力学自由能 (dG_total):"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
                    Text("${rbs.totalDeltaG} kcal/mol", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = pixCyan)
                }
                Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
                    Text(t.t("最适 aSD 杂交窗口序列:") + " ", fontSize = 7.5.sp, color = pixFgDim)
                    Text(rbs.sdOverhangSequence, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, color = pixCyan)
                    Text(" (" + t.t("抗 aSD: CCTCCT") + ")", fontSize = 7.5.sp, color = pixFgDim)
                }
            }
        }

        // ── §3 Sigma70 启动子与 TFBS ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.t("3. Sigma70 启动子与转录因子靶点 (TFBS)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            Column(Modifier.fillMaxWidth().background(Color(0x1A000000)).padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.t("Sigma70 -35/-10 启动子区间:"), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = pixCyan)
                if (promoters.isNotEmpty()) {
                    promoters.take(3).forEach { prom ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("pos ${prom.pos + 1} bp", fontSize = 8.sp)
                            Text(prom.sequence, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = pixGreen)
                            Text("Score: ${prom.score}%", fontSize = 8.sp)
                        }
                    }
                } else {
                    Text(t.t("未检测到强 consensus 启动子区域"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(t.t("TFBS 调控因子结合基序:"), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = pixCyan, modifier = Modifier.padding(top = 3.dp))
                if (tfbsMatches.isNotEmpty()) {
                    tfbsMatches.take(5).forEach { tf ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${tf.name} (${tf.strand})", fontSize = 8.sp, color = pixYellow)
                            Text("pos ${tf.pos + 1} bp", fontSize = 8.sp)
                            Text(tf.matchSeq, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = pixCyan)
                        }
                    }
                } else {
                    Text(t.t("未找到典型 TFBS (TATA-box/NFkB 等)"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }

        // ── §4 定位与信号肽 / 跨膜 ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.t("4. 细胞定位与信号肽 / 跨膜结构 (Targeting)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("分泌信号肽 (SignalP):"), fontSize = 8.5.sp, color = pixFgDim)
                Text(
                    if (localization.hasSignal) "YES (Cleaved at pos ${localization.cleavagePos})" else "NO",
                    fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                    color = if (localization.hasSignal) pixGreen else pixFgDim
                )
            }
            Text(localization.details, fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth().background(Color(0x1A000000)).padding(2.dp))
            Text(t.t("跨膜螺旋区预测 (TMHMM 疏水核心螺旋):"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixFgDim)
            if (tmHelices.isNotEmpty()) {
                tmHelices.forEach { tm ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.tf("TM 螺旋: AA {v1}-{v2}", tm.start + 1, tm.end + 1), fontSize = 8.sp, color = pixOrange)
                        Text(t.tf("平均疏水性: {v1} (K-D scale)", tm.averageHydro), fontSize = 8.sp, color = pixOrange)
                    }
                }
            } else {
                Text(t.t("未检测到强疏水跨膜 alpha 螺旋（属于可溶性蛋白）"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        // ── §5 Skew / CpG / 串联重复 ──
        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.t("5. Skew 滑动分析与 CpG 岛/重复序列检测 (GC Skew, CpG & Tandem Scan)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("平均 GC Skew 偏好性:"), fontSize = 8.5.sp, color = pixFgDim)
                val avg = if (sk.isEmpty()) 0.0 else sk.sumOf { it.skew } / maxOf(1, sk.size)
                Text("%.3f".format(avg), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = pixCyan)
            }
            Text(t.tf("CpG 岛表观遗传富集区 ({v1}):", cpgs.size), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixCyan)
            if (cpgs.isNotEmpty()) {
                cpgs.take(3).forEach { cpg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.t("区间:") + " ${cpg.start + 1}-${cpg.end + 1} bp (${cpg.length}bp)", fontSize = 8.sp, color = pixGreen)
                        Text("GC%: ${cpg.gcContent}% | Obs/Exp: ${cpg.obsExpRatio}", fontSize = 8.sp, color = pixGreen)
                    }
                }
            } else {
                Text("✓ " + t.t("未扫描到符合 Gardiner-Garden 标准的 CpG 岛"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(t.tf("质粒基因组串联重复序列 ({v1}):", tReps.size), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = pixOrange)
            if (tReps.isNotEmpty()) {
                tReps.take(3).forEach { rep ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.tf("bp {v1}-{v2}: 重复 {v3} x{v4}", rep.start + 1, rep.end + 1, rep.pattern, rep.repeatCount), fontSize = 8.sp, color = pixOrange)
                        Text(t.tf("共 {v1}bp", rep.totalLength), fontSize = 8.sp, color = pixOrange)
                    }
                }
            } else {
                Text("✓ " + t.t("✓ 未发现不稳定的多联串联重复区"), fontSize = 7.5.sp, color = pixFgDim, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        status?.let { Text(it, fontSize = 9.sp, color = if (statusOk) pixGreen else pixRed, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SynbioView(record: SeqRecord, onRecordChange: (SeqRecord) -> Unit) {
    // T7 块7：main gene/panels/SyntheticBiologyCadPanel.svelte 1:1（synBio.ts → core/SynBio.kt；
    // SynBioHub 注册库 → core/SynBioRegistry.kt）。
    // 平台差异（披露）：pushToast/pushLog → status 行；浏览器下载 → clipboard XML；
    // localStorage 自定义安全库 → 暂不读取（用默认库，Rust DB 同步属后续消息层块）。
    val t = com.spice.nativeapp.core.I18n
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var statusOk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    val dnaSeq = record.sequence
    val feats = androidx.compose.runtime.remember(record) {
        record.features.map { GeneFeatureLite(it.name, it.type, it.start, it.end) }
    }

    // MoClo 四输入（4bp，默认 GGAG/CGCT/GGAG/CGCT）
    var mocloVL by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("GGAG") }
    var mocloVR by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("CGCT") }
    var mocloIL by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("GGAG") }
    var mocloIR by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("CGCT") }
    var transMethod by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("chemical") }
    var transQuality by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("ligation_product") }

    // 注册库
    var synBioQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var synBioFilter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("all") }
    var synBioResults by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<SynBioRegistry.StandardBioPart>>(emptyList()) }
    var searchingSynBio by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var importingPartId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    val brick = androidx.compose.runtime.remember(dnaSeq) { SynBio.checkBioBrickCompatibility(dnaSeq) }
    val moclo = androidx.compose.runtime.remember(mocloVL, mocloVR, mocloIL, mocloIR) {
        SynBio.verifyMoCloAssembly(mocloVL, mocloVR, mocloIL, mocloIR)
    }
    val trans = androidx.compose.runtime.remember(transMethod, transQuality, dnaSeq) {
        SynBio.estimateTransformationEfficiency(transMethod, dnaSeq.length, transQuality)
    }
    val gates = androidx.compose.runtime.remember(record) { SynBio.analyzeGeneticCircuits(feats) }
    val copyEst = androidx.compose.runtime.remember(record) { SynBio.estimateCopyNumberAndIncompatibility(feats) }
    val securityAudit = androidx.compose.runtime.remember(dnaSeq) { SynBio.auditBiosecurityScreening(dnaSeq) }
    val amrAudit = androidx.compose.runtime.remember(dnaSeq) { SynBio.auditCardAmrScreening(dnaSeq) }

    fun performSearch() {
        searchingSynBio = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val proxy = kotlin.runCatching { SpdLibrary.parseProxy(SpdLibrary.proxySetting(ctx)).proxy }.getOrNull()
            val res = SynBioRegistry.searchBioParts(synBioQuery, if (synBioFilter == "all") null else synBioFilter, proxy)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                synBioResults = res.data
                searchingSynBio = false
                if (res.error != null) {
                    status = "⚠ " + t.t("离线模式") + ": " + t.t("无法连接注册库，已切换到本地示例元件库。")
                    statusOk = false
                } else {
                    status = null
                }
            }
        }
    }

    fun importPart(part: SynBioRegistry.StandardBioPart) {
        importingPartId = part.partId
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val proxy = kotlin.runCatching { SpdLibrary.parseProxy(SpdLibrary.proxySetting(ctx)).proxy }.getOrNull()
            val seq = SynBioRegistry.fetchPartSequence(part.partId, proxy)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (seq.isNotEmpty()) {
                    val prevLen = dnaSeq.length
                    val newSeq = dnaSeq + seq.uppercase()
                    // main 自动特征注释（1-based inclusive：start=prevLen+0 即新序列首碱基）
                    val color = when (part.partType) {
                        "promoter" -> "#f59e0b"; "rbs" -> "#10b981"; "cds" -> "#3b82f6"; else -> "#ec4899"
                    }
                    val newFeat = com.spice.nativeapp.core.Feature(
                        name = part.partId, type = part.partType,
                        start = prevLen + 1, end = prevLen + seq.length,
                        color = color
                    )
                    onRecordChange(record.copy(sequence = newSeq, features = record.features + newFeat))
                    status = "✓ " + t.tf("{v1} ({v2} bp) 已插入质粒末端并生成特征", part.partId, seq.length)
                    statusOk = true
                } else {
                    status = "✗ " + t.t("元件导入失败") + ": " + t.t("解析到的序列为空")
                    statusOk = false
                }
                importingPartId = ""
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        status?.let {
            Text(it, fontSize = 9.sp, color = if (statusOk) PIX_GREEN else PIX_RED)
        }

        // ── SBOL 标准元件格式导出 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("1. SBOL 标准元件格式导出 (SBOL 2.0 XML)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Button(
                onClick = {
                    val xml = SynBio.exportToSBOL(record.name.ifEmpty { "SPICE_Plasmid" }, dnaSeq, feats)
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(xml))
                    status = "✓ " + t.t("导出成功") + ": " + t.t("电子书图谱已下载")
                    statusOk = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📥 " + t.t("导出 SBOL 电子物种图谱 (RDF/XML)"), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            Text(t.t("SBOL 图谱已导出: {v1}").replace("{v1}", record.name.ifEmpty { "SPICE_Plasmid" }),
                fontSize = 7.5.sp, color = PIX_FG_DIM)
        }

        // ── BioBrick RFC 10 审查 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("生物砖标准相容性审查 (BioBrick RFC 10)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            if (brick.compatible) {
                Text("✔ " + t.t("完全兼容 BioBrick RFC 10 酶切组装规范 (无非法内切酶位点)。"), fontSize = 8.5.sp, color = PIX_GREEN, fontWeight = FontWeight.Bold)
            } else {
                Text("⚠ " + t.t("警告：存在非法克隆酶切位点 (BioBrick incompatibilities)！"), fontSize = 8.5.sp, color = PIX_RED, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().heightIn(max = 50.dp).verticalScroll(rememberScrollState())) {
                    brick.matchingSites.forEach { site ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(site.enzyme, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = PIX_RED)
                            Text(site.sequence, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = PIX_RED)
                        }
                    }
                }
            }
        }

        // ── MoClo 突出端检查 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("金门金木金级组装突出端检查 (MoClo Overhangs)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(t.t("载体 Left/Right (4bp):"), fontSize = 8.sp, color = PIX_FG_DIM)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedTextField(value = mocloVL, onValueChange = { if (it.length <= 4) mocloVL = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = mocloVR, onValueChange = { if (it.length <= 4) mocloVR = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(t.t("片段 Left/Right (4bp):"), fontSize = 8.sp, color = PIX_FG_DIM)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedTextField(value = mocloIL, onValueChange = { if (it.length <= 4) mocloIL = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = mocloIR, onValueChange = { if (it.length <= 4) mocloIR = it },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color(0x33000000)).padding(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("组装级别判定 (Standard Level):"), fontSize = 8.5.sp, color = PIX_FG_DIM)
                    Text(moclo.level, fontSize = 8.5.sp, color = PIX_CYAN, fontWeight = FontWeight.Bold)
                }
                if (moclo.compatible) {
                    Text("✓ " + t.t("左右突出端完美互补，MoClo 适配兼容！"), fontSize = 8.5.sp, color = PIX_GREEN, fontWeight = FontWeight.Bold)
                } else {
                    moclo.errors.forEach { err -> Text("⚠ $err", fontSize = 8.sp, color = PIX_RED) }
                }
            }
        }

        // ── 转化效率估算 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("细胞转化效率估算"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(t.t("细胞转化效率双电泳估算 (Transformation Efficiency)"), fontSize = 8.sp, color = PIX_FG_DIM)
                    androidx.compose.material3.FilterChip(selected = transMethod == "chemical",
                        onClick = { transMethod = "chemical" },
                        label = { Text(t.t("化学转化法"), fontSize = 8.sp, maxLines = 1) })
                    androidx.compose.material3.FilterChip(selected = transMethod == "electroporation",
                        onClick = { transMethod = "electroporation" },
                        label = { Text(t.t("电穿孔转化法"), fontSize = 8.sp, maxLines = 1) })
                }
                Column(Modifier.weight(1f)) {
                    Text(t.t("质粒状态:"), fontSize = 8.sp, color = PIX_FG_DIM)
                    androidx.compose.material3.FilterChip(selected = transQuality == "supercoiled",
                        onClick = { transQuality = "supercoiled" },
                        label = { Text(com.spice.nativeapp.core.I18n.t("超螺旋 (supercoiled)"), fontSize = 8.sp, maxLines = 1) })
                    androidx.compose.material3.FilterChip(selected = transQuality == "ligation_product",
                        onClick = { transQuality = "ligation_product" },
                        label = { Text(com.spice.nativeapp.core.I18n.t("连接产物 (ligation)"), fontSize = 8.sp, maxLines = 1) })
                }
            }
            Column(Modifier.fillMaxWidth().background(Color(0x33000000)).padding(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("转化效率 (CFU/µg pUC19):"), fontSize = 8.5.sp, color = PIX_FG_DIM)
                    Text(java.lang.String.format("%.1e", trans.efficiency), fontSize = 8.5.sp, color = PIX_GREEN, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.t("单平皿预计长菌斑数 (基于 10ng DNA):"), fontSize = 8.5.sp, color = PIX_FG_DIM)
                    Text(t.tf("~ {v1} 个单克隆", trans.colonyEstimate), fontSize = 8.5.sp, color = PIX_CYAN, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── 遗传线路与逻辑门 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("合成线路与逻辑门识别 (Genetic Circuits)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Column(Modifier.fillMaxWidth().heightIn(max = 80.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (gates.isEmpty()) {
                    Text(t.t("未扫描到符合标准 Promoter-CDS-Terminator 级联拓扑的逻辑门通路。"),
                        fontSize = 7.5.sp, color = PIX_FG_DIM, modifier = Modifier.padding(vertical = 10.dp))
                } else gates.forEach { gate ->
                    Row(Modifier.fillMaxWidth().background(Color(0x1A000000)).padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.t("门") + " ${gate.gateType}: " + gate.outputCdsName, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        Text(gate.status, fontSize = 8.sp, color = PIX_FG_DIM)
                    }
                }
            }
        }

        // ── 拷贝数 + 生物安全 + AMR ──
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.5.dp)) {
            Text(t.t("质粒拷贝数分类与生物安全审查 (Copy Control & Biosecurity)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("复制子类型:"), fontSize = 8.5.sp, color = PIX_FG_DIM)
                Text(copyEst.oriName, fontSize = 8.5.sp, color = PIX_CYAN, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("估算拷贝数 (Copies/cell):"), fontSize = 8.5.sp, color = PIX_FG_DIM)
                Text(copyEst.copyNumberRange, fontSize = 8.5.sp, color = PIX_GREEN, fontWeight = FontWeight.Bold)
            }
            Text("${t.t("拷贝特性")}: ${copyEst.expressionProfile}\n${t.t("拷贝特性")}: ${copyEst.incompatibilityGroup}",
                fontSize = 7.5.sp, color = PIX_FG_DIM, modifier = Modifier.background(Color(0x1A000000)).padding(2.5.dp))

            androidx.compose.material3.HorizontalDivider(color = Color(0x0DFFFFFF))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("合规审查安全等级 (Biosecurity):"), fontSize = 8.5.sp, color = PIX_FG_DIM, fontWeight = FontWeight.Bold)
                Text(securityAudit.securityLevel, fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                    color = if (securityAudit.passed) PIX_GREEN else PIX_RED)
            }
            if (securityAudit.flags.isEmpty()) {
                Text(t.t("✓ 生物安全审查通过（未检测到任何已知毒性或受控基因序列特征）。"), fontSize = 7.5.sp, color = PIX_GREEN)
            } else securityAudit.flags.forEach { flag ->
                Column(Modifier.fillMaxWidth().background(Color(0x14EF4444)).padding(3.dp)) {
                    Text("⚠️ " + t.t("风险因子") + ": " + flag.agent, fontSize = 7.5.sp, color = PIX_RED)
                    Text("${t.t("描述")}: ${flag.description}", fontSize = 7.5.sp, color = PIX_RED)
                }
            }

            androidx.compose.material3.HorizontalDivider(color = Color(0x0DFFFFFF))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("抗生素耐药筛查 (CARD AMR):"), fontSize = 8.5.sp, color = PIX_FG_DIM, fontWeight = FontWeight.Bold)
                Text(if (amrAudit.passed) "SAFE" else "AMR DETECTED", fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                    color = if (amrAudit.passed) PIX_GREEN else PIX_YELLOW)
            }
            if (amrAudit.flags.isEmpty()) {
                Text(t.t("未检测到额外风险耐药标记（抗生素阻遏状态正常）。"), fontSize = 7.5.sp, color = PIX_GREEN)
            } else amrAudit.flags.forEach { flag ->
                Column(Modifier.fillMaxWidth().background(Color(0x14E2B448)).padding(3.dp)) {
                    Text("⚠️ " + t.t("耐药基因") + ": " + flag.gene, fontSize = 7.5.sp, color = PIX_YELLOW)
                    Text("${t.t("耐药类型")}: ${flag.resistanceTo}", fontSize = 7.5.sp, color = PIX_YELLOW)
                    Text("${t.t("匹配基序")}: ${flag.matches}", fontSize = 7.5.sp, color = PIX_YELLOW, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── SynBioHub 注册库查询 ──
        Column(Modifier.fillMaxWidth()) {
            Text(t.t("注册元件库云查询与导入 (SynBioHub Registry)"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OutlinedTextField(value = synBioQuery, onValueChange = { synBioQuery = it },
                    placeholder = { Text("J23100, GFP, B0015...", fontSize = 8.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.5.sp),
                    modifier = Modifier.weight(1f), singleLine = true)
                androidx.compose.material3.FilterChip(selected = false, onClick = {
                    synBioFilter = when (synBioFilter) {
                        "all" -> "promoter"; "promoter" -> "rbs"; "rbs" -> "cds"; "cds" -> "terminator"; else -> "all"
                    }
                }, label = {
                    Text(when (synBioFilter) {
                        "all" -> t.t("全部"); "promoter" -> t.t("全部"); "rbs" -> "RBS"; "cds" -> "CDS"; else -> t.t("终止子 (Terminator)")
                    }, fontSize = 8.sp)
                })
                Button(onClick = { performSearch() }, enabled = !searchingSynBio) {
                    Text(if (searchingSynBio) "..." else t.t("搜索"), fontSize = 8.sp)
                }
            }
            if (synBioResults.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    synBioResults.forEach { part ->
                        Column(Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(2.5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("BBa_${part.partId} [${part.partType}]",
                                    fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PIX_GREEN, fontFamily = FontFamily.Monospace)
                                TextButton(onClick = { importPart(part) }, enabled = importingPartId != part.partId) {
                                    Text(if (importingPartId == part.partId) t.t("拉取中...") else t.t("导入"), fontSize = 7.5.sp)
                                }
                            }
                            Text(part.shortDesc, fontSize = 7.5.sp, color = PIX_FG_DIM)
                        }
                    }
                }
            } else {
                Text(t.t("输入关键字查询 iGEM 标准元件并一键拼装至表达环"), fontSize = 7.5.sp, color = PIX_FG_DIM,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun BlastView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    // 1:1 移植 main SequenceEvolutionPanel.svelte：进化舱四 tab（BLAST / Tree / Logo / DotPlot）
    var analysisTab by remember { mutableStateOf(0) } // 0=blast 1=tree 2=logo 3=dotplot
    val collections = remember { com.spice.nativeapp.core.defaultGeneCollections() }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val tabs = listOf(
            com.spice.nativeapp.core.I18n.t("本地 BLAST 检索") to MaterialTheme.colorScheme.primary,
            com.spice.nativeapp.core.I18n.t("系统发育进化树") to MaterialTheme.colorScheme.primary,
            com.spice.nativeapp.core.I18n.t("序列 Info Logo") to MaterialTheme.colorScheme.primary,
            com.spice.nativeapp.core.I18n.t("2D Dot Plot 对角图") to MaterialTheme.colorScheme.tertiary
        )
        tabs.forEachIndexed { i, (label, active) ->
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                onClick = { analysisTab = i }
            ) {
                Text(label, fontSize = 8.sp, color = if (analysisTab == i) active else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    when (analysisTab) {
        0 -> EvoBlastTab(record, collections)
        1 -> EvoTreeTab(collections)
        2 -> EvoLogoTab()
        else -> EvoDotPlotTab(record, collections)
    }
}

// ── 进化舱四 tab（main SequenceEvolutionPanel.svelte 1:1；文案 zh.json verbatim）──

private val PIX_CYAN = Color(0xFF4CD6FF)
private val PIX_ACCENT2 = Color(0xFFFFD23F)
private val PIX_GREEN = Color(0xFF53D769)
private val PIX_RED = Color(0xFFFF5D5D)
private val PIX_YELLOW = Color(0xFFE2B448)
private val PIX_FG = Color(0xFFD8E1F2)
private val PIX_FG_DIM = Color(0xFF8B96B5)
private val PIX_BORDER = Color(0xFF2A3450)
private val PIX_PANEL_DARK = Color(0xFF040508)

@Composable
private fun EvoBlastTab(record: SeqRecord, collections: List<com.spice.nativeapp.core.EvoCollection>) {
    var blastQuery by remember { mutableStateOf("self") } // self | all | <collection name>
    var blastHits by remember { mutableStateOf<List<com.spice.nativeapp.core.BlastHit>>(emptyList()) }
    var blastRan by remember { mutableStateOf(false) }

    Text(com.spice.nativeapp.core.I18n.t("本地极速 BLAST 同源比对 (k-mer seed-and-extend):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(com.spice.nativeapp.core.I18n.t("本地极速 BLAST 同源比对 (k-mer seed-and-extend):"), fontSize = 9.5.sp, color = PIX_FG_DIM, modifier = Modifier.weight(1f))
        // 目标选择（main：select all/self/collectionsList）
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(modifier = Modifier.heightIn(min = 40.dp), onClick = { menuOpen = true }) {
                Text(if (blastQuery == "self") "self" else if (blastQuery == "all") "all" else blastQuery, fontSize = 9.5.sp, color = PIX_CYAN)
            }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("self", fontSize = 9.5.sp) },
                    onClick = { blastQuery = "self"; menuOpen = false }, leadingIcon = null
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("all", fontSize = 9.5.sp) },
                    onClick = { blastQuery = "all"; menuOpen = false }, leadingIcon = null
                )
                collections.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(c.name, fontSize = 9.5.sp) },
                        onClick = { blastQuery = c.name; menuOpen = false }, leadingIcon = null
                    )
                }
            }
        }
        Button(
            modifier = Modifier.heightIn(min = 40.dp),
            onClick = {
                // main：db = collectionsList（除当前质粒），self 时附加 "(Self)"
                val db = collections.filter { it.name != record.name }.map { it.name to it.seq }.toMutableList()
                if (blastQuery == "self") db.add("${record.name} (Self)" to record.sequence)
                val targets = when (blastQuery) {
                    "all" -> db
                    "self" -> listOf("${record.name} (Self)" to record.sequence)
                    else -> db.filter { it.first == blastQuery }
                }
                blastHits = com.spice.nativeapp.core.runLocalBlast(record.sequence, targets)
                blastRan = true
            }
        ) { Text("BLAST", fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
    }

    // 命中列表
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (blastHits.isEmpty()) {
            if (blastRan) {
                Text(com.spice.nativeapp.core.I18n.t("比对完成！"), fontSize = 9.5.sp, color = PIX_CYAN)
            } else {
                // main：evoBlastHint 虚线空态框
                Text(
                    com.spice.nativeapp.core.I18n.t("请选择比对目标，一键执行本地 S-W 种子延伸 BLAST 同源扫描。"),
                    fontSize = 9.5.sp, color = PIX_FG_DIM,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 25.dp, horizontal = 8.dp)
                        .evoDashedFrame()
                )
            }
        } else {
            blastHits.forEach { hit ->
                Column(
                    Modifier.fillMaxWidth().padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🎯 ${hit.subjectName}", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                        Text(com.spice.nativeapp.core.I18n.tf("一致性 {v1}%", hit.identity), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = PIX_GREEN)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.tf("位置: {v1}..{v2} ➔ {v3}..{v4}", hit.queryStart, hit.queryEnd, hit.subjectStart, hit.subjectEnd), fontSize = 8.5.sp, color = PIX_FG_DIM)
                        Text("Score: ${hit.score}", fontSize = 8.5.sp, color = PIX_FG)
                    }
                    Text(
                        hit.alignmentStr, fontSize = 7.sp, color = PIX_CYAN, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EvoTreeTab(collections: List<com.spice.nativeapp.core.EvoCollection>) {
    val treeNames = remember(collections) { collections.map { it.name.split(".").first() } }
    // main：标准质粒预计算距离矩阵，动态喂给 NJ 算法
    val treeMatrix = remember {
        listOf(
            listOf(0.0, 0.45, 0.52, 0.85),
            listOf(0.45, 0.0, 0.22, 0.88),
            listOf(0.52, 0.22, 0.0, 0.91),
            listOf(0.85, 0.88, 0.91, 0.0)
        )
    }
    Text(com.spice.nativeapp.core.I18n.t("系统发育亲缘进化树:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
    if (treeNames.size < 2) return
    val (newick, root) = remember(treeNames) {
        com.spice.nativeapp.core.buildPhylogeneticTree(treeNames, treeMatrix)
    }
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
        // 递归渲染（main renderTreeNode：depth*14px 缩进 + 左虚线）
        EvoTreeNodeRow(root, 0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("演化格式串 (Standard String):"), fontSize = 10.sp, color = PIX_FG_DIM)
        Text(
            newick, fontSize = 7.5.sp, color = PIX_CYAN, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EvoTreeNodeRow(node: com.spice.nativeapp.core.PhylogenyNode, depth: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = if (node.isLeaf) PIX_GREEN else PIX_CYAN, fontWeight = FontWeight.Bold)) {
                    append((if (node.isLeaf) "🌿" else "📁") + " " + node.name.take(16))
                }
                if (node.branchLength > 0) {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = PIX_FG_DIM, fontSize = 8.sp)) {
                        append("  (${node.branchLength})")
                    }
                }
            },
            fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = (depth * 14).dp)
        )
        node.children.forEach { child -> EvoTreeNodeRow(child, depth + 1) }
    }
}

@Composable
private fun EvoLogoTab() {
    // main：精选保守引物基序多重比对输入，计算真实 Shannon 信息熵
    val alignedSeqs = remember {
        listOf(
            "GTTTTCCCAGTCACGAC",
            "GTTTTCCAAGTCACGAC",
            "GTTTTCCCAGTCACGAT",
            "GTTTTCCAAGTCACGAT",
            "GTTTTCACAGTCACGAC"
        )
    }
    val logoProfile = remember(alignedSeqs) { com.spice.nativeapp.core.calculateSequenceLogo(alignedSeqs) }
    Text(com.spice.nativeapp.core.I18n.t("位置信息量 Shannon 信息熵序列 Logo:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp).height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom
    ) {
        logoProfile.forEach { pos ->
            Column(
                Modifier.width(18.dp).fillMaxHeight(),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                pos.frequencies.forEach { f ->
                    val charH = (f.bitsScaled / 2.0 * 80).toInt()
                    if (charH > 1) {
                        // main：A green / T red / C、G 引用未定义 CSS 变量 → 继承前景色
                        val cColor = when (f.char) {
                            'A' -> PIX_GREEN
                            'T' -> PIX_RED
                            else -> PIX_FG
                        }
                        Text(
                            f.char.toString(), fontSize = maxOf(6, charH).sp, fontWeight = FontWeight.Bold,
                            color = cColor, lineHeight = charH.sp,
                            modifier = Modifier.height(charH.dp)
                        )
                    }
                }
                Text("${pos.position}", fontSize = 7.sp, color = PIX_FG_DIM, fontFamily = FontFamily.Monospace)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(com.spice.nativeapp.core.I18n.t("保守 M13 引物基序"), fontSize = 7.5.sp, color = PIX_FG_DIM)
        Text(com.spice.nativeapp.core.I18n.t("上限 2.0 bits"), fontSize = 7.5.sp, color = PIX_GREEN)
    }
}

@Composable
private fun EvoDotPlotTab(record: SeqRecord, collections: List<com.spice.nativeapp.core.EvoCollection>) {
    var dotPlotQuery by remember { mutableStateOf("self") }
    var dotPlotWindow by remember { mutableStateOf("15") }
    var dotPlotThreshold by remember { mutableStateOf("12") }
    val subSeq = if (dotPlotQuery == "self") record.sequence
        else collections.find { it.name == dotPlotQuery }?.seq ?: record.sequence
    val win = dotPlotWindow.toIntOrNull()?.coerceIn(5, 50) ?: 15
    val thres = dotPlotThreshold.toIntOrNull()?.coerceIn(3, 50) ?: 12
    val dotPoints = remember(record.sequence, subSeq, win, thres) {
        com.spice.nativeapp.core.calculateDotPlot(record.sequence, subSeq, win, thres)
    }

    Text(com.spice.nativeapp.core.I18n.t("碱基同线性对角点阵图 (2D Dot Plot Matrix):"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(com.spice.nativeapp.core.I18n.t("碱基同线性对角点阵图 (2D Dot Plot Matrix):"), fontSize = 9.5.sp, color = PIX_FG_DIM, modifier = Modifier.weight(1f))
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(modifier = Modifier.heightIn(min = 40.dp), onClick = { menuOpen = true }) {
                Text(if (dotPlotQuery == "self") "self" else dotPlotQuery, fontSize = 9.5.sp, color = PIX_CYAN)
            }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("self", fontSize = 9.5.sp) },
                    onClick = { dotPlotQuery = "self"; menuOpen = false }, leadingIcon = null
                )
                collections.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(c.name, fontSize = 9.5.sp) },
                        onClick = { dotPlotQuery = c.name; menuOpen = false }, leadingIcon = null
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = dotPlotWindow, onValueChange = { dotPlotWindow = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("滑动窗口 (Window):"), fontSize = 8.sp) },
            modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.5.sp)
        )
        OutlinedTextField(
            value = dotPlotThreshold, onValueChange = { dotPlotThreshold = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("匹配阈值 (Threshold):"), fontSize = 8.sp) },
            modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.5.sp)
        )
    }
    Box(
        Modifier.fillMaxWidth().padding(5.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Canvas(Modifier.width(150.dp).height(150.dp)) {
            val n = maxOf(1, record.sequence.length)
            val m = maxOf(1, subSeq.length)
            dotPoints.forEach { pt ->
                val cx = (pt.x.toFloat() / n) * size.width
                val cy = size.height - (pt.y.toFloat() / m) * size.height
                drawCircle(PIX_CYAN, radius = 1.dp.toPx() / 2, center = androidx.compose.ui.geometry.Offset(cx, cy))
            }
            // 反对角虚线
            drawLine(
                color = PIX_FG_DIM.copy(alpha = 0.12f),
                start = androidx.compose.ui.geometry.Offset(0f, size.height),
                end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(com.spice.nativeapp.core.I18n.tf("X轴: 当前序列 ({v1} bp)", record.sequence.length), fontSize = 7.5.sp, color = PIX_FG_DIM)
        Text(com.spice.nativeapp.core.I18n.tf("Y轴: {v1} ({v2} bp)", dotPlotQuery.split(".").first().take(14), subSeq.length), fontSize = 7.5.sp, color = PIX_FG_DIM)
    }
}

@Composable
private fun AlignmentView(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    var extra by remember { mutableStateOf("") }
    var msa by remember { mutableStateOf<MultipleAlign.MultiAlignment?>(null) }
    OutlinedTextField(
        value = extra,
        onValueChange = { extra = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("额外序列（每行一条，≤6 条）")) },
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
                    listOf(com.spice.nativeapp.core.I18n.t("参考") to record.sequence.take(2000)) + extras.mapIndexed { i, s -> "seq${i + 1}" to s.take(2000) }
                )
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text(com.spice.nativeapp.core.I18n.t("多序列比对")) }
        OutlinedButton(
            onClick = {
                onRecordChange(record.copy(description = com.spice.nativeapp.core.I18n.tf("比对一致性 {v1}%（{v2} 列）", "%.1f".format((msa?.identity ?: 0.0) * 100), msa?.width ?: 0)))
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text(com.spice.nativeapp.core.I18n.t("写入一致性到备注")) }
    }
    msa?.let { m -> MsaGrid(m) }
}

/** 共享 MSA 网格渲染：逐行着色（共识一致亮色 / 不一致暗色 / 缺口灰）+ 逐列保守性条带 */
@Composable
internal fun MsaGrid(m: MultipleAlign.MultiAlignment) {
    Text(
        com.spice.nativeapp.core.I18n.tf("比对完成：{v1} 条 × {v2} 列 · 总体一致性 {v3}%", m.rows.size, m.width, "%.1f".format(m.identity * 100)),
        fontSize = 12.sp, fontWeight = FontWeight.Bold
    )
    // 网格渲染：逐行着色（与共识不一致的列标暗色，缺口灰色）
    val matchColor = MaterialTheme.colorScheme.primary
    val mismatchColor = MaterialTheme.colorScheme.error
    val consensusColor = MaterialTheme.colorScheme.tertiary
    val cols = 60
    val blocks = (m.width + cols - 1) / cols
    val blockRange = if (blocks > 4) 0..3 else 0 until blocks.coerceAtLeast(1)
    if (blocks > 4) Text(com.spice.nativeapp.core.I18n.tf("（序列较长，仅展示前 {v1} 列）", cols * 4), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (blk in blockRange) {
        val from = blk * cols
        val to = minOf(from + cols, m.width)
        Text(com.spice.nativeapp.core.I18n.tf("列 {v1}–{v2}", from + 1, to), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        m.rows.forEach { row ->
            val slice = row.alignedSeq.substring(from, minOf(to, row.alignedSeq.length))
            Text(
                buildAnnotatedString {
                    if (row.isRef) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(com.spice.nativeapp.core.I18n.t("参考 ").padEnd(6)) }
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
                append(com.spice.nativeapp.core.I18n.t("共识 ").padEnd(6))
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
    Text(com.spice.nativeapp.core.I18n.t("↑ 逐列保守性（深色 = 全列一致）"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Text(com.spice.nativeapp.core.I18n.tf("滑窗 {v1} bp，窗口数 {v2}，范围 {v3}–{v4}%", window, values.size, "%.1f".format(values.minOrNull() ?: 0.0), "%.1f".format(values.maxOrNull() ?: 0.0)), fontSize = 11.sp)
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
            if (text.isBlank()) { err = com.spice.nativeapp.core.I18n.t("未读到文件内容"); return@rememberLauncherForActivityResult }
            val imported = AlignmentIO.parse(text)
            if (imported.rows.isEmpty()) {
                err = com.spice.nativeapp.core.I18n.t("未解析到序列数据")
                return@rememberLauncherForActivityResult
            }
            // 过滤空序列行并对齐 main：跑渐进式 MSA 后展示
            val seqs = imported.rows.filter { it.seq.isNotBlank() }
                .mapIndexed { i, r -> (if (r.name.isBlank()) "seq$i" else r.name) to r.seq }
            msa = MultipleAlign.multipleAlign(seqs)
            importedInfo = com.spice.nativeapp.core.I18n.tf("已导入 {v1} 比对文件：{v2} 条序列", imported.format.uppercase(), seqs.size)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { launcher.launch("*/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(com.spice.nativeapp.core.I18n.t("选择比对文件…"))
        }
    }
    Text(
        com.spice.nativeapp.core.I18n.t("支持 Clustal / FASTA / NEXUS / PHYLIP / MSF / Stockholm（自动检测格式），导入后执行渐进式多序列比对。"),
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    importedInfo?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
    msa?.let { m ->
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(m.rows.joinToString("\n") { ">${it.name}\n${it.alignedSeq}" })) },
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text(com.spice.nativeapp.core.I18n.t("复制比对结果 (FASTA)"), fontSize = 11.sp) }
        MsaGrid(m)
    }
}

/** main 虚线边框（border: 1px dashed var(--pix-border)）的 Compose 等价 */
private fun Modifier.evoDashedFrame(): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = PIX_BORDER,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
        )
    }
)
