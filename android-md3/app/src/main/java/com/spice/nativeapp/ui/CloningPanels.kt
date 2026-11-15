package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.spice.nativeapp.core.jsNum
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import com.spice.nativeapp.core.GenomicsTools
import com.spice.nativeapp.core.NgsQc
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SyntheticGeneQc
import com.spice.nativeapp.core.VirtualGel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect

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
    onLog: (List<String>) -> Unit = {},
    gelLanes: List<VirtualGel.GelLane> = emptyList(),
    onGelLanesChange: (List<VirtualGel.GelLane>) -> Unit = {},
    selectedEnzymes: Set<String> = emptySet(),
    onSelectedEnzymesChange: (List<String>) -> Unit = {}
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
                CloningPanel.ENZYMES -> EnzymeSelectorPanel(record, selectedEnzymes, onSelectedEnzymesChange)
                CloningPanel.CODON -> CodonPanel(record, clipboard, onRecordChange, onLog)
                CloningPanel.PRIMERS -> PrimerPanel(record, clipboard)
                CloningPanel.PCR -> PcrSimulationPanel(record, onRecordChange, onLog, gelLanes, onGelLanesChange)
                CloningPanel.GEL -> VirtualGelPanel(record, gelLanes, onGelLanesChange)
                CloningPanel.SIMULATOR -> CloningSimulatorPanel(record, onRecordChange, onEnzymesAdopt, onLog)
                CloningPanel.CASSETTE -> CassettePanel(clipboard, onRecordChange)
                CloningPanel.VECTOR -> VectorPanel(record, clipboard)
                CloningPanel.CRISPR -> CrisprPanel(record, clipboard, onLog)
                CloningPanel.CALC -> CalcPanel()
                CloningPanel.LIMS -> LimsPanel(record, clipboard)
                CloningPanel.QC -> QcPanel(record, onRecordChange, onLog)
                CloningPanel.NGS -> NgsPanel(record, onLog)
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

/**
 * main src/lib/gene/EnzymeSelector.svelte 1:1（限制酶选择器 648 行：REBASE 模糊搜索 +
 * 选中酶列表 + 切点列表（Dam/Dcm/EcoKI 甲基化阻断标注）+ NEB 缓冲液活性四格 +
 * 酶组管理模态框（9 系统预设 + 自定义组保存/重命名/删除，applyPreset 整表替换选中集））。
 * 算法层 core/EnzymeSelector.kt（restrictionSites 推导 / colorOf / PRESETS /
 * bufferCompatibility）+ core/EnzymesVe.kt 切点引擎。
 * 平台差异（披露）：
 * - enzymeDatabase：main 为 REBASE/SPD 网络同步写 localStorage 的动态库 → 安卓内置
 *   EnzymeDb 全库（532 组 / 3732 同义名，既有 REBASE 口径披露）；
 * - localStorage 'spice.gene.customEnzymeSetsGrid' → SharedPreferences(spice_settings)
 *   同键 JSON；toast → 面板内 status 行；模态框 portal → androidx Dialog；
 * - autocomplete 绝对定位下拉 → 输入框下方固定展示区（同功能同条数上限 5）；
 * - selectedEnzymes $bindable 数组 → onSelectedEnzymesChange 整表回写（顺序保持）；
 * - main 模态框内联重命名 Enter/Esc → ✓/✗ 按钮（触屏惯例，等价）；
 * - activeDetail 恒非空（main getEnzymeBufferCompatibility 恒返回对象），
 *   enzymeNoBuffer 分支与 main 同为不可达，保留结构不渲染。
 */
@Composable
private fun EnzymeSelectorPanel(
    record: SeqRecord,
    selectedEnzymes: Set<String>,
    onSelectedEnzymesChange: (List<String>) -> Unit
) {
    val t = com.spice.nativeapp.core.I18n
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val enzymeDatabase = remember { com.spice.nativeapp.core.EnzymeSelector.database }
    val dbKeys = remember { enzymeDatabase.keys.toList() }

    var searchQuery by remember { mutableStateOf("") }
    var groupSearchQuery by remember { mutableStateOf("") }
    var showGroupModal by remember { mutableStateOf(false) }
    var newSetNameInput by remember { mutableStateOf("") }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var editingNameText by remember { mutableStateOf("") }
    var customSets by remember { mutableStateOf(loadEnzymeSets(ctx)) }
    var selectedPresetName by remember { mutableStateOf("") }
    var activeDetailEnzyme by remember { mutableStateOf("EcoRI") }
    var bufferSearchQuery by remember { mutableStateOf("EcoRI") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }

    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusGood = ok
        status = (if (ok) "✓ " else "✗ ") + title + (text?.let { " — " + it } ?: "")
    }

    val selected = selectedEnzymes.toList()

    fun toggleEnzyme(name: String) {
        onSelectedEnzymesChange(if (selected.contains(name)) selected - name else selected + name)
    }

    fun persist(sets: List<Pair<String, List<String>>>) {
        customSets = sets
        saveEnzymeSets(ctx, sets)
    }

    fun applyPreset(enzymesList: List<String>) {
        onSelectedEnzymesChange(enzymesList.toList())
    }

    fun saveCustomEnzymeSetInline() {
        var name = newSetNameInput.trim()
        if (name.isEmpty()) name = "Set ${customSets.size + 1}"
        val existingIdx = customSets.indexOfFirst { it.first == name }
        if (existingIdx != -1) {
            val next = customSets.toMutableList()
            next[existingIdx] = name to selected.toList()
            persist(next)
            toast(true, t.tf("已覆盖并保存酶组 \"{v1}\"", name), name)
        } else {
            persist(customSets + (name to selected.toList()))
            toast(true, t.t("保存自定义酶组成功"), name)
        }
        newSetNameInput = ""
    }

    fun finishRename(idx: Int) {
        val newName = editingNameText.trim()
        if (newName.isEmpty()) return
        val next = customSets.toMutableList()
        next[idx] = newName to next[idx].second
        persist(next)
        editingIdx = null
        toast(true, t.t("酶组重命名成功"), newName)
    }

    // main $effect：选中集与预设/自定义组同步（isSameSet 排序比较）
    LaunchedEffect(selectedEnzymes, customSets) {
        fun isSameSet(a: List<String>, b: List<String>): Boolean {
            if (a.size != b.size) return false
            val sa = a.sorted()
            val sb = b.sorted()
            return sa.indices.all { sa[it] == sb[it] }
        }
        selectedPresetName = com.spice.nativeapp.core.EnzymeSelector.PRESETS.firstOrNull { isSameSet(selected, it.second) }?.first
            ?: customSets.firstOrNull { isSameSet(selected, it.second) }?.first
            ?: ""
    }
    // main $effect：bufferSearchQuery 跟随 activeDetailEnzyme
    LaunchedEffect(activeDetailEnzyme) { bufferSearchQuery = activeDetailEnzyme }

    val searchResults = remember(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) emptyList()
        else dbKeys.filter { it.uppercase().contains(q.uppercase()) }.take(5)
    }
    val bufferSearchResults = remember(bufferSearchQuery) {
        val q = bufferSearchQuery.trim()
        if (q.isEmpty() || bufferSearchQuery == activeDetailEnzyme) emptyList()
        else dbKeys.filter { it.uppercase().contains(q.uppercase()) }.take(5)
    }
    val sites = remember(record.sequence, selectedEnzymes) {
        com.spice.nativeapp.core.EnzymeSelector.restrictionSites(record.sequence, selectedEnzymes)
    }
    val activeDetail = remember(activeDetailEnzyme) {
        com.spice.nativeapp.core.EnzymeSelector.bufferCompatibility(activeDetailEnzyme, ctx)
    }

    fun pixColor(c: String): Color =
        if (c.startsWith("#")) Color(android.graphics.Color.parseColor(c)) else PIX_CYAN

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── 选择器触发按钮 + 清空 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = { showGroupModal = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = PIX_CYAN),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) {
                Text(
                    if (selectedPresetName.isNotEmpty())
                        "🧪 " + t.t("酶组") + ": " + selectedPresetName
                    else "🧪 " + t.t("选择/管理酶组"),
                    fontSize = 9.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(" 📂", fontSize = 9.sp)
            }
            Text(
                t.t("[清空]"),
                fontSize = 9.5.sp, color = PIX_RED,
                modifier = Modifier.clickable { onSelectedEnzymesChange(emptyList()) }
            )
        }

        // ── REBASE 模糊搜索 ──
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(t.t("搜索 800+ 限制酶并回车加入..."), fontSize = 10.sp, color = PIX_FG_DIM) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                val query = searchQuery.trim()
                if (query.isEmpty()) return@KeyboardActions
                val exactMatch = dbKeys.firstOrNull { it.equals(query, ignoreCase = true) }
                val chosen = exactMatch ?: searchResults.firstOrNull()
                if (chosen != null) {
                    if (selected.contains(chosen)) toast(false, t.tf("限制酶 {v1} 已在选择列表中", chosen), chosen)
                    else { toggleEnzyme(chosen); toast(true, t.tf("已添加限制酶 {v1}", chosen), chosen) }
                    searchQuery = ""
                } else {
                    toast(false, t.tf("未在数据库中找到 \"{v1}\"", query), query)
                }
            }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            modifier = Modifier.fillMaxWidth()
        )
        if (searchResults.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF0B0F19)).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                searchResults.forEach { name ->
                    val entry = enzymeDatabase[name]
                    Row(
                        Modifier.fillMaxWidth().clickable { toggleEnzyme(name); searchQuery = "" }.padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$name (${entry?.seq})", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = pixColor(entry?.color ?: "#ffffff"))
                        if (selected.contains(name)) Text("✓", fontSize = 9.sp, color = PIX_GREEN)
                    }
                }
            }
        }

        // ── 选中酶列表 ──
        Column(
            Modifier.fillMaxWidth().background(Color(0x15000000)).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(t.t("已选限制酶:") + " (${selected.size})", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
            if (selected.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    selected.forEach { name ->
                        val entry = enzymeDatabase[name]
                        Row(
                            Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                "$name (${entry?.seq ?: ""})",
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                color = pixColor(entry?.color ?: "#ffffff")
                            )
                            Text(
                                "-",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_RED,
                                modifier = Modifier.clickable { toggleEnzyme(name) }.padding(horizontal = 3.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    t.t("无已选限制酶。可在上方搜索并按回车直接加入。"),
                    fontSize = 9.sp, color = PIX_FG_DIM,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        androidx.compose.material3.HorizontalDivider(color = Color(0x33FFFFFF))

        // ── 切点列表 ──
        Column(
            Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sites.forEach { st ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        st.name + (if (st.methylated) " " else ""),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (st.methylated) PIX_RED else pixColor(st.color)
                    )
                    Text(st.seq, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = if (st.methylated) PIX_RED else pixColor(st.color))
                    Text("${jsNum(st.pos.toDouble())} bp", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (st.methylated) PIX_RED else pixColor(st.color))
                }
                if (st.methylated) {
                    Text(
                        "[" + t.t("阻断") + "] " + (st.methylationType ?: ""),
                        fontSize = 8.sp, color = PIX_RED
                    )
                }
            }
            if (sites.isEmpty()) {
                Text(
                    t.t("（未检测到酶切点）"),
                    fontSize = 10.sp, color = PIX_FG_DIM,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // ── NEB 缓冲液活性面板（Gap 4） ──
        Column(
            Modifier.fillMaxWidth().background(Color(0x33000000)).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(t.t("NEB 双酶切缓冲液活性"), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                OutlinedTextField(
                    value = bufferSearchQuery,
                    onValueChange = { bufferSearchQuery = it },
                    placeholder = { Text(t.t("搜索限制酶..."), fontSize = 8.5.sp, color = PIX_FG_DIM) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.5.sp, fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        val exactMatch = dbKeys.firstOrNull { it.equals(bufferSearchQuery.trim(), ignoreCase = true) }
                        if (exactMatch != null) activeDetailEnzyme = exactMatch
                        else if (bufferSearchResults.isNotEmpty()) activeDetailEnzyme = bufferSearchResults.first()
                        bufferSearchQuery = activeDetailEnzyme
                    }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    modifier = Modifier.weight(1f)
                )
            }
            if (bufferSearchResults.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF0B0F19)).padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    bufferSearchResults.forEach { enz ->
                        Text(
                            enz, fontSize = 8.5.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                activeDetailEnzyme = enz; bufferSearchQuery = enz
                            }.padding(2.dp)
                        )
                    }
                }
            }
            // main activeDetail 恒非空（else 分支不可达，保留注释不渲染）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    "r1.1" to activeDetail.r1_1,
                    "r2.1" to activeDetail.r2_1,
                    "r3.1" to activeDetail.r3_1,
                    "CutSmart" to activeDetail.cutSmart
                ).forEach { (label, pct) ->
                    Column(
                        Modifier.weight(1f).background(Color(0x05FFFFFF)).padding(2.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(label, fontSize = 7.5.sp, color = PIX_FG_DIM)
                        Text(
                            "${pct}%", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = if (pct >= 100) PIX_GREEN else if (pct >= 50) PIX_ACCENT2 else PIX_RED
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.t("热失活 (65°C, 20min):") + " ", fontSize = 8.sp, color = PIX_FG_DIM)
                Text(t.t("可热失活"), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PIX_GREEN)
            }
        }

        status?.let {
            Text(it, fontSize = 9.sp, color = if (statusGood) PIX_GREEN else PIX_RED, modifier = Modifier.fillMaxWidth())
        }
    }

    // ── 酶组管理模态框（main showGroupModal + portal） ──
    if (showGroupModal) {
        Dialog(onDismissRequest = { showGroupModal = false }) {
            Surface(color = Color(0xFF0B0F19), modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("🧬 " + t.t("选择与管理酶组"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                        Text(
                            "[" + t.t("关闭") + " X]",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_RED,
                            modifier = Modifier.clickable { showGroupModal = false }.padding(4.dp)
                        )
                    }
                    // 当前选中详情
                    Column(
                        Modifier.fillMaxWidth().background(Color(0x40000000)).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(t.t("当前选中的酶:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
                            Text("(${selected.size} ${t.t("个")})", fontSize = 9.sp, color = PIX_FG_DIM)
                        }
                        Text(
                            if (selected.isNotEmpty()) selected.joinToString(", ") else t.t("未选中任何酶"),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 组名搜索
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = groupSearchQuery,
                            onValueChange = { groupSearchQuery = it },
                            placeholder = { Text(t.t("搜索系统预设或自定义酶组名称..."), fontSize = 10.sp, color = PIX_FG_DIM) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (groupSearchQuery.isNotEmpty()) {
                            Text(
                                "[Clear]", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PIX_RED,
                                modifier = Modifier.clickable { groupSearchQuery = "" }.padding(4.dp)
                            )
                        }
                    }
                    // 系统预设
                    val q = groupSearchQuery.trim().lowercase()
                    val filteredPresets = com.spice.nativeapp.core.EnzymeSelector.PRESETS.filter { it.first.lowercase().contains(q) }
                    val filteredCustom = customSets.filter { it.first.lowercase().contains(q) }
                    Text(t.t("系统预设组"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_CYAN)
                    if (filteredPresets.isNotEmpty()) {
                        filteredPresets.chunked(2).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowItems.forEach { (pName, pEnzymes) ->
                                    val isCurrent = selectedPresetName == pName
                                    Column(
                                        Modifier.weight(1f).background(
                                            if (isCurrent) Color(0x1400F0FF) else Color(0x05FFFFFF)
                                        ).clickable { applyPreset(pEnzymes); showGroupModal = false }.padding(6.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(pName, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) PIX_CYAN else Color.White,
                                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            if (isCurrent) Text("● Active", fontSize = 8.sp, color = PIX_GREEN)
                                        }
                                        Text(pEnzymes.joinToString(", "), fontSize = 8.5.sp, color = PIX_FG_DIM,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                                if (rowItems.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        Text(t.t("未找到匹配的内置预设酶组"), fontSize = 9.sp, color = PIX_FG_DIM,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color(0x14FFFFFF))
                    // 自定义组
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(t.t("用户自定组"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newSetNameInput,
                                onValueChange = { newSetNameInput = it },
                                placeholder = { Text(t.t("输入新酶组名..."), fontSize = 8.5.sp, color = PIX_FG_DIM) },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 8.5.sp),
                                singleLine = true,
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { saveCustomEnzymeSetInline() }),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                modifier = Modifier.width(100.dp)
                            )
                            Button(
                                onClick = { saveCustomEnzymeSetInline() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = PIX_GREEN),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                                modifier = Modifier.heightIn(min = 18.dp)
                            ) { Text(t.t("保存当前"), fontSize = 8.5.sp) }
                        }
                    }
                    if (filteredCustom.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            filteredCustom.forEach { set ->
                                val setOriginalIdx = customSets.indexOfFirst { it.first == set.first }
                                val isCurrent = selectedPresetName == set.first
                                val editing = editingIdx == setOriginalIdx
                                Row(
                                    Modifier.fillMaxWidth().background(
                                        if (isCurrent) Color(0x0D00F0FF) else Color(0x26000000)
                                    ).padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    if (editing) {
                                        OutlinedTextField(
                                            value = editingNameText,
                                            onValueChange = { editingNameText = it },
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("✓", fontSize = 9.sp, color = PIX_GREEN,
                                            modifier = Modifier.clickable { setOriginalIdx.takeIf { it >= 0 }?.let { finishRename(it) } }.padding(4.dp))
                                        Text("✗", fontSize = 9.sp, color = PIX_RED,
                                            modifier = Modifier.clickable { editingIdx = null }.padding(4.dp))
                                    } else {
                                        Column(
                                            Modifier.weight(1f).clickable { applyPreset(set.second); showGroupModal = false }
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(set.first, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                    color = if (isCurrent) PIX_CYAN else Color.White,
                                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                Text("(${set.second.size} ${t.t("种酶")})", fontSize = 8.5.sp, color = PIX_FG_DIM)
                                            }
                                            Text(set.second.joinToString(", "), fontSize = 8.5.sp, color = PIX_FG_DIM,
                                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                        Text("📝", fontSize = 9.sp, color = PIX_CYAN,
                                            modifier = Modifier.clickable {
                                                val idx = customSets.indexOfFirst { it.first == set.first }
                                                if (idx != -1) { editingIdx = idx; editingNameText = customSets[idx].first }
                                            }.padding(4.dp))
                                        Text("-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PIX_RED,
                                            modifier = Modifier.clickable {
                                                persist(customSets.filter { it.first != set.first })
                                                toast(true, t.t("已删除自定义酶组"), set.first)
                                            }.padding(4.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(t.t("暂无匹配的自定义酶组"), fontSize = 9.5.sp, color = PIX_FG_DIM,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    // Footer
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { showGroupModal = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = Color.White),
                            modifier = Modifier.heightIn(min = 28.dp)
                        ) { Text(t.t("关闭"), fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

/** main localStorage 'spice.gene.customEnzymeSetsGrid' → SharedPreferences(spice_settings) 同键 JSON */
private fun loadEnzymeSets(ctx: android.content.Context): List<Pair<String, List<String>>> {
    return try {
        val raw = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
            .getString("spice.gene.customEnzymeSetsGrid", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val enzymes = o.optJSONArray("enzymes") ?: JSONArray()
            o.getString("name") to (0 until enzymes.length()).map { enzymes.getString(it) }
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveEnzymeSets(ctx: android.content.Context, sets: List<Pair<String, List<String>>>) {
    val arr = JSONArray()
    for ((name, enzymes) in sets) {
        val o = JSONObject()
        o.put("name", name)
        o.put("enzymes", JSONArray(enzymes))
        arr.put(o)
    }
    ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
        .edit().putString("spice.gene.customEnzymeSetsGrid", arr.toString()).apply()
}

/**
 * T7-15 密码子优化面板 —— main src/lib/gene/CodonOptimizer.svelte 1:1。
 *
 * main 组件（79 行）挂在 gene 页浮动面板（showCodonOptimizer，menuCloneCodon 入口）：
 *  - bind:dnaSeq（优化回写 DNA）/ codonHost（ecoli|yeast|human，设置页默认宿主初始化）
 *    / translatedProtein（getAminoAcidStringFromSequenceString(dnaSeq)）/ onOptimizeLog → pushLog；
 *  - caiScore = $derived(calculateCai(dnaSeq, host).cai)，dnaSeq < 3 → 0；
 *  - optimizeCodons()：translatedProtein 空报错（优化失败/翻译后的蛋白序列为空）；否则逐 AA
 *    BEST_CODONS[host][aa] ?? ATG 回写 dnaSeq，log = codonOptimizeSuccessLog(宿主大写, prevCai.toFixed(3))，
 *    toast 成功「密码子优化成功 / Host: ECOLI」。
 *
 * 平台差异（沿既有披露惯例）：toast → 面板内 status 行；pushLog → onLog；
 * pushHistory+bind:dnaSeq 回写 → onRecordChange。旧自造「规避 BsaI/BsmBI 回译」
 * 面板整体替换（算法与 main 不符）。宿主切换 UI 为接入 codonHost 3 态的最小形态
 * （main codonHost 由设置页 spice_default_host 传入，面板只读展示 + 切换）。
 */
@Composable
private fun CodonPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit, onLog: (List<String>) -> Unit = {}) {
    val t = com.spice.nativeapp.core.I18n
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // main codonHost：设置页 spice_default_host（localStorage）→ gene 页 $state，默认 ecoli
    val gprefs = remember { ctx.getSharedPreferences("spice_prefs", android.content.Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(
        listOf("ecoli", "yeast", "human").firstOrNull { it == gprefs.getString("spice_default_host", null) } ?: "ecoli"
    ) }
    val dnaSeq = record.sequence
    val translatedProtein = remember(dnaSeq) { com.spice.nativeapp.core.CodonOpt.translatedProtein(dnaSeq) }
    // main caiScore：dnaSeq.length < 3 → 0
    val caiScore = remember(dnaSeq, host) {
        if (dnaSeq.length < 3) 0.0 else com.spice.nativeapp.core.CodonOpt.calculateCai(dnaSeq, host).cai
    }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (msg, ok) ← pushToast

    val pixGreen = androidx.compose.ui.graphics.Color(0xFF53d769)
    val pixCyan = androidx.compose.ui.graphics.Color(0xFF4cd6ff)
    val pixFgDim = MaterialTheme.colorScheme.onSurfaceVariant

    // ── opt-panel ──
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 宿主行：宿主表达体系: ECOLI（pix-cyan 大写粗体）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t.t("宿主表达体系:"), fontSize = 11.sp, color = pixFgDim)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "ecoli" to t.t("大肠杆菌"), "yeast" to t.t("酿酒酵母"), "human" to t.t("人源表达系")
                ).forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = host == key, onClick = { host = key },
                        label = { Text(if (host == key) key.uppercase() else label, fontSize = 10.sp) }
                    )
                }
            }
        }
        // CAI 行：密码子适应指数 (CAI): 0.4317（>0.8 good 绿 / 否则 warn 黄）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t.t("密码子适应指数 (CAI):"), fontSize = 11.sp, color = pixFgDim)
            Text("%.4f".format(caiScore), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (caiScore > 0.8) pixGreen else androidx.compose.ui.graphics.Color(0xFFFFD23F))
        }
        // CAI 进度条：宽度 cai*100%，>0.8 绿否则青
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val w = size.width * caiScore.toFloat().coerceIn(0f, 1f)
            drawRect(androidx.compose.ui.graphics.Color(0xFF3A3F4E), size = size)
            drawRect(if (caiScore > 0.8) pixGreen else pixCyan,
                topLeft = androidx.compose.ui.geometry.Offset.Zero,
                size = androidx.compose.ui.geometry.Size(w, size.height))
        }
        // 优化按钮（main pix-btn 全宽）
        Button(
            onClick = {
                if (translatedProtein.isEmpty()) {
                    // main pushToast('error', 优化失败, 翻译后的蛋白序列为空)
                    status = t.t("优化失败") + ": " + t.t("翻译后的蛋白序列为空") to false
                    return@Button
                }
                val prevCai = caiScore
                val optimized = com.spice.nativeapp.core.CodonOpt.optimizeCodons(dnaSeq, host, true).optimized
                onRecordChange(record.copy(sequence = optimized))
                // main codonOptimizeSuccessLog：[优化] … [宿主: {toUpperCase}]，CAI指数从 {toFixed} 提升至 1.000
                val log = t.t("[优化] 成功对密码子偏好性进行同步重构 [宿主: {toUpperCase}]，CAI指数从 {toFixed} 提升至 1.000")
                    .replace("{toUpperCase}", host.uppercase())
                    .replace("{toFixed}", "%.3f".format(prevCai))
                onLog(listOf(log))
                status = t.t("密码子优化成功") + ": " + t.t("宿主表达体系:").trimEnd(':') + " " + host.uppercase() to true
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
        ) { Text(t.t("一键沉默同义突变优化 (CAI -> 1.0)"), fontSize = 11.sp) }
        status?.let { (msg, ok) ->
            Text(if (ok) "✓ $msg" else "✗ $msg", fontSize = 11.sp,
                color = if (ok) pixGreen else MaterialTheme.colorScheme.error)
        }
    }
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

/**
 * main src/lib/gene/panels/PcrSimulationPanel.svelte 1:1（PCR 模拟器，3 模式：
 * standard / overlap / mutagenesis）。算法层 core/PcrSimulation.kt
 * （main src/lib/genome/primerAnalysis.ts simulatePCR / simulateOverlapExtensionPCR /
 * simulateSiteDirectedMutagenesis）。
 * 平台差异（披露）：
 * - 浮动面板标题沿用菜单词条（FloatingPanel 复用枚举 title），main 面板头为
 *   m.pcrSimulation()「PCR 模拟」；面板内部布局/交互 1:1；
 * - toast → 面板内 status 行；pushLog → onLog；pushHistory+bind:dnaSeq 回写 →
 *   onRecordChange（GeneScreen applyRecord 统一入 undoStack）；
 * - showVirtualGel 置位 → 泳道链 onGelLanesChange 后由用户切到 GEL 面板查看
 *   （Android 面板互斥路由，main 为多浮窗共存；既有披露惯例）；
 * - number input bind:value null 语义 → toIntOrNull() 空输入 null 同构；
 * - 跨模式残留 pcrResult（main 渲染 undefined）→ 安全转型不渲染行，仅按钮无操作。
 */
@Composable
private fun PcrSimulationPanel(
    record: SeqRecord,
    onRecordChange: (SeqRecord) -> Unit,
    onLog: (List<String>) -> Unit,
    gelLanes: List<VirtualGel.GelLane>,
    onGelLanesChange: (List<VirtualGel.GelLane>) -> Unit
) {
    val t = com.spice.nativeapp.core.I18n
    var pcrMode by remember { mutableStateOf("standard") }
    var pcrFwd by remember { mutableStateOf("") }
    var pcrRev by remember { mutableStateOf("") }
    var pcrResult by remember { mutableStateOf<Any?>(null) }
    var mutPosText by remember { mutableStateOf("0") }
    var mutBase by remember { mutableStateOf("A") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }

    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusGood = ok
        status = (if (ok) "✓ " else "✗ ") + title + (text?.let { " — " + it } ?: "")
    }

    val dnaSeq = record.sequence

    // ── 三模式切换 ──
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("standard" to t.t("标准"), "overlap" to t.t("重叠延伸"), "mutagenesis" to t.t("定点突变")).forEach { (mode, label) ->
            val on = pcrMode == mode
            if (on) Button(onClick = { pcrMode = mode }, modifier = Modifier.heightIn(min = 36.dp)) { Text(label, fontSize = 9.sp) }
            else OutlinedButton(onClick = { pcrMode = mode }, modifier = Modifier.heightIn(min = 36.dp)) { Text(label, fontSize = 9.sp) }
        }
    }

    if (pcrMode == "standard") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Fwd (5'->3'):", fontSize = 9.sp, color = PIX_FG_DIM)
            OutlinedTextField(
                value = pcrFwd, onValueChange = { pcrFwd = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t.t("引物序列 (5'->3')..."), fontSize = 8.sp, color = PIX_FG_DIM) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                singleLine = true
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Rev (5'->3'):", fontSize = 9.sp, color = PIX_FG_DIM)
            OutlinedTextField(
                value = pcrRev, onValueChange = { pcrRev = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(t.t("引物序列 (5'->3')..."), fontSize = 8.sp, color = PIX_FG_DIM) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                singleLine = true
            )
        }
        Button(onClick = {
            if (pcrFwd.isNotEmpty() && pcrRev.isNotEmpty()) {
                val r = com.spice.nativeapp.core.PcrSimulation.simulatePCR(dnaSeq, pcrFwd, pcrRev, circular = !record.circular)
                pcrResult = r
                if (r.product.isNotEmpty()) {
                    onLog(listOf(
                        t.t("[PCR] 产物: {productLength} bp, Tm {toFixed}°C, GC {arg0}%")
                            .replace("{productLength}", r.productLength.toString())
                            .replace("{toFixed}", "%.1f".format(r.meltingTemp))
                            .replace("{arg0}", "%.1f".format(r.gcContent))
                    ))
                    toast(true, t.t("PCR 扩增成功！"),
                        t.t("产物长度 {productLength} bp, 融解温度: {tm}°C")
                            .replace("{productLength}", r.productLength.toString())
                            .replace("{tm}", "%.1f".format(r.meltingTemp)))
                } else {
                    onLog(listOf(t.t("[PCR] 失败: {join}").replace("{join}", r.warnings.joinToString("; "))))
                    toast(false, t.t("PCR 扩增失败"), r.warnings.joinToString("; ").ifEmpty { t.t("未在序列中找到匹配的引物结合位点！") })
                }
            } else {
                toast(false, t.t("PCR 失败"), t.t("请输入完整的正向与反向引物序列"))
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) { Text(t.t("运行 PCR"), fontSize = 10.sp) }
    } else if (pcrMode == "overlap") {
        Text(t.t("重叠延伸 PCR: 对当前序列进行自组装"), fontSize = 9.sp, color = PIX_FG_DIM)
        Button(onClick = {
            val half = dnaSeq.length / 2 // main Math.floor(len/2)
            val frags = listOf(dnaSeq.substring(0, half) to "Frag1", dnaSeq.substring(half) to "Frag2")
            val r = com.spice.nativeapp.core.PcrSimulation.simulateOverlapExtensionPCR(frags, 20)
            pcrResult = r
            if (r.product.isNotEmpty()) {
                onLog(listOf(
                    t.t("[OE-PCR] 产物: {productLength} bp, {length} 警告")
                        .replace("{productLength}", r.productLength.toString())
                        .replace("{length}", r.warnings.size.toString())
                ))
                toast(true, t.t("重叠延伸 PCR 模拟成功！"),
                    t.t("扩增拼合产物长 {productLength} bp").replace("{productLength}", r.productLength.toString()))
            } else {
                onLog(listOf(t.t("[OE-PCR] 失败")))
                toast(false, t.t("重叠延伸失败"), t.t("片段无法通过末端同源段进行自退火延伸"))
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) { Text(t.t("运行 OE-PCR"), fontSize = 10.sp) }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t.t("位置:"), fontSize = 9.sp, color = PIX_FG_DIM)
            OutlinedTextField(
                value = mutPosText, onValueChange = { mutPosText = it },
                modifier = Modifier.width(56.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                singleLine = true
            )
            Text(t.t("碱基:"), fontSize = 9.sp, color = PIX_FG_DIM)
            OutlinedTextField(
                value = mutBase, onValueChange = { mutBase = it },
                modifier = Modifier.width(44.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                singleLine = true
            )
        }
        Button(onClick = {
            val pos = mutPosText.toIntOrNull()
            if (pos == null || mutBase.isEmpty()) {
                toast(false, t.t("定点突变失败"), t.t("请指定有效的突变位置和目标碱基！"))
                return@Button
            }
            val r = com.spice.nativeapp.core.PcrSimulation.simulateSiteDirectedMutagenesis(dnaSeq, pos, mutBase.uppercase())
            pcrResult = r
            if (r.mutatedSequence.isNotEmpty()) {
                onLog(listOf(
                    t.t("[Mutagenesis] 位点 {mutagenesisPos} -> {mutagenesisBase}, 产物 {length} bp")
                        .replace("{mutagenesisPos}", pos.toString())
                        .replace("{mutagenesisBase}", mutBase)
                        .replace("{length}", r.mutatedSequence.length.toString())
                ))
                toast(true, t.t("定点突变设计成功！"),
                    t.t("突变产物全长 {length} bp").replace("{length}", r.mutatedSequence.length.toString()))
            } else {
                onLog(listOf(t.t("[Mutagenesis] 失败")))
                toast(false, t.t("定点突变失败"), t.t("无效的突变设定或超出序列长度范围"))
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) { Text(t.t("定点突变"), fontSize = 10.sp) }
    }

    // ── 结果卡 ──
    pcrResult?.let {
        Column(Modifier.fillMaxWidth().background(Color(0xFF141724)).padding(4.dp)) {
            if (pcrMode == "mutagenesis") {
                val mr = it as? com.spice.nativeapp.core.PcrSimulation.MutagenesisResult
                Button(onClick = {
                    if (mr != null && mr.mutatedSequence.isNotEmpty()) {
                        onRecordChange(record.copy(sequence = mr.mutatedSequence))
                        onLog(listOf(t.t("[OK] 突变序列已应用到主序列")))
                        val pos = mutPosText.toIntOrNull()
                        toast(true, t.t("定点突变成功"),
                            t.t("位置 {pos} -> {base}").replace("{pos}", pos?.toString() ?: "").replace("{base}", mutBase))
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) { Text(t.t("应用突变"), fontSize = 9.sp) }
            } else {
                val r = it as? com.spice.nativeapp.core.PcrSimulation.PcrProduct
                r?.let { pr ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.t("产物长度:"), fontSize = 9.sp, color = PIX_FG_DIM)
                        Text("${pr.productLength} bp", fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.t("Tm:"), fontSize = 9.sp, color = PIX_FG_DIM)
                        Text("%.1f°C".format(pr.meltingTemp), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.t("GC%:"), fontSize = 9.sp, color = PIX_FG_DIM)
                        Text("%.1f%".format(pr.gcContent), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (pr.warnings.isNotEmpty()) {
                        Text(pr.warnings.joinToString("; "), fontSize = 8.sp, color = PIX_CYAN)
                    }
                    if (pr.product.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = {
                                onRecordChange(record.copy(sequence = pr.product))
                                onLog(listOf(t.t("[OK] PCR 产物已应用到主序列")))
                                toast(true, t.t("应用产物成功"), "${pr.productLength} bp")
                            }, modifier = Modifier.weight(1f).heightIn(min = 36.dp)) { Text(t.t("应用产物"), fontSize = 9.sp) }
                            Button(
                                onClick = {
                                    if (pr.productLength <= 0) return@Button
                                    val idx = gelLanes.size + 1
                                    onGelLanesChange(gelLanes + VirtualGel.GelLane(
                                        id = "pcr_lane_${System.currentTimeMillis()}",
                                        name = "PCR Prod $idx",
                                        bands = listOf(pr.productLength),
                                        color = "#4cd6ff"
                                    ))
                                    onLog(listOf(
                                        t.t("[Gel] 已送入 PCR 产物 ({productLength} bp) 至泳道 {idx}")
                                            .replace("{productLength}", pr.productLength.toString())
                                            .replace("{idx}", idx.toString())
                                    ))
                                    toast(true, t.t("已加入电泳泳道"),
                                        t.t("泳道 {idx}: {productLength} bp")
                                            .replace("{idx}", idx.toString())
                                            .replace("{productLength}", pr.productLength.toString()))
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = PIX_CYAN, contentColor = MaterialTheme.colorScheme.onPrimary),
                                modifier = Modifier.weight(1f).heightIn(min = 36.dp)
                            ) { Text(t.t("送入电泳"), fontSize = 9.sp) }
                        }
                    }
                }
            }
        }
    }

    status?.let {
        Text(it, fontSize = 9.sp, color = if (statusGood) PIX_GREEN else PIX_RED, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * main src/lib/gene/VirtualGel.svelte 1:1（虚拟琼脂糖凝胶电泳，main 中由
 * gene 页 menuCloneGel 菜单挂载为浮动面板；Android 侧路由即 CloningPanel.GEL）。
 * 平台差异（披露）：
 * - SVG/Canvas 凝胶渲染 → Compose Canvas；drop-shadow 滤镜 → 半透明加宽线近似；
 * - PNG 导出 document.createElement('canvas') → android.graphics.Bitmap 离屏画布，
 *   倍率读 SharedPreferences spice_gel_export_multiplier（对应 main localStorage 同名键）；
 * - backend.saveExport → 应用外部 Pictures 目录写文件，toast → 面板内 status 行；
 * - 空参数输入回退上一有效值（main 空输入 NaN 会导致渲染 NaN，不复制该缺陷）；
 * - select → FilterChip、number/text input → OutlinedTextField（既有披露惯例）；
 * - 条带输入框受控回显 = bands 解析结果 join(", ")（与 main Svelte 受控重置行为一致）。
 */
@Composable
private fun VirtualGelPanel(
    record: SeqRecord,
    lanes: List<VirtualGel.GelLane>,
    onLanesChange: (List<VirtualGel.GelLane>) -> Unit
) {
    val t = com.spice.nativeapp.core.I18n
    var agaroseText by remember { mutableStateOf("1.2") }
    var runTimeText by remember { mutableStateOf("30") }
    var voltageText by remember { mutableStateOf("100") }
    var selectedMarker by remember { mutableStateOf("DL2000") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val agarosePercent = agaroseText.toDoubleOrNull() ?: 1.2
    val runTime = runTimeText.toIntOrNull() ?: 30
    val voltage = voltageText.toIntOrNull() ?: 100

    // 导出 PNG（main exportGelPng：倍率×离屏画布 → 文件）
    fun exportGelPng() {
        scope.launch {
            try {
                val mult = ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
                    .getString("spice_gel_export_multiplier", "4")?.toIntOrNull() ?: 4
                val bmp = renderGelExportBitmap(lanes, selectedMarker, agarosePercent, runTime, voltage, mult)
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                val f = java.io.File(dir, record.name.replace(' ', '_') + "_gel_" + com.spice.nativeapp.core.jsNum(agarosePercent) + "pct.png")
                withContext(Dispatchers.IO) {
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                statusGood = true
                status = "✓ " + t.tf("电泳图导出成功 ({v1}x 超分)", mult) + " — " + f.absolutePath
            } catch (e: Exception) {
                statusGood = false
                status = "✗ " + t.t("电泳图导出失败") + ": " + (e.message ?: e.toString())
            }
        }
    }

    // ── 导出按钮行 ──
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { exportGelPng() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
            Text(t.t("导出 PNG 凝胶图"), fontSize = 9.sp)
        }
    }

    // ── 参数行（琼脂糖% / 时间 / 电压）──
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t.t("琼脂糖%:"), fontSize = 9.sp, color = PIX_FG_DIM, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        OutlinedTextField(
            value = agaroseText, onValueChange = { agaroseText = it },
            modifier = Modifier.width(44.dp).heightIn(min = 40.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
            singleLine = true
        )
        Text(t.t("时间(min):"), fontSize = 9.sp, color = PIX_FG_DIM, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        OutlinedTextField(
            value = runTimeText, onValueChange = { runTimeText = it },
            modifier = Modifier.width(44.dp).heightIn(min = 40.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
            singleLine = true
        )
        Text(t.t("电压(V):"), fontSize = 9.sp, color = PIX_FG_DIM, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        OutlinedTextField(
            value = voltageText, onValueChange = { voltageText = it },
            modifier = Modifier.width(44.dp).heightIn(min = 40.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
            singleLine = true
        )
    }
    // Marker 选择（main <select> → FilterChip）
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Marker:", fontSize = 9.sp, color = PIX_FG_DIM, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        VirtualGel.MARKER_SETS.keys.forEach { mk ->
            androidx.compose.material3.FilterChip(
                selected = selectedMarker == mk,
                onClick = { selectedMarker = mk },
                label = { Text(mk, fontSize = 8.sp, maxLines = 1) }
            )
        }
    }

    // ── 凝胶工作区：左凝胶画布（横向滚动）+ 右泳道管理 ──
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 左：凝胶画布
        Box(
            Modifier
                .width(230.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            GelCanvas(lanes, selectedMarker, agarosePercent, runTime)
        }
        // 右：泳道管理
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(t.t("泳道管理:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PIX_ACCENT2)
                TextButton(
                    onClick = { onLanesChange(VirtualGel.addLane(lanes, emptyList())) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
                ) { Text("+", fontSize = 12.sp, color = PIX_GREEN) }
            }
            lanes.forEachIndexed { idx, lane ->
                Column(Modifier.fillMaxWidth().background(Color(0xFF141724))) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = lane.name,
                            onValueChange = { txt -> onLanesChange(lanes.mapIndexed { i, l -> if (i == idx) l.copy(name = txt) else l }) },
                            modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = pixColor(lane.color)),
                            singleLine = true
                        )
                        if (idx > 0) {
                            TextButton(
                                onClick = { onLanesChange(lanes.filterNot { it.id == lane.id }) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
                            ) { Text("✕", fontSize = 10.sp, color = PIX_RED) }
                        }
                    }
                    Text(t.t("条带大小 (bp):"), fontSize = 8.sp, color = PIX_FG_DIM)
                    OutlinedTextField(
                        value = lane.bands.joinToString(", "),
                        onValueChange = { txt ->
                            onLanesChange(lanes.mapIndexed { i, l -> if (i == idx) l.copy(bands = VirtualGel.parseLaneBands(txt)) else l })
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                        placeholder = { Text(t.t("例如: 2000, 1000, 500"), fontSize = 8.sp, color = PIX_FG_DIM) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = pixColor(lane.color)),
                        singleLine = true
                    )
                }
            }
            Text(t.t("迁移率受琼脂糖浓度和时间影响。高浓度凝胶分离小片段更佳。"), fontSize = 8.sp, color = PIX_FG_DIM)
        }
    }

    status?.let {
        Text(it, fontSize = 9.sp, color = if (statusGood) PIX_GREEN else PIX_RED, modifier = Modifier.fillMaxWidth())
    }
}

/** main hex 泳道色 → Compose Color */
private fun pixColor(hex: String): Color =
    if (hex.length == 7 && hex.startsWith("#")) Color(android.graphics.Color.parseColor(hex)) else PIX_CYAN

/** Compose Canvas 凝胶渲染（main SVG 视图 1:1 布局/坐标；文本用基线近似摆放） */
@Composable
private fun GelCanvas(
    lanes: List<VirtualGel.GelLane>,
    selectedMarker: String,
    agarosePercent: Double,
    runTime: Int
) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val markerBands = VirtualGel.MARKER_SETS[selectedMarker] ?: VirtualGel.MARKER_SETS["DL2000"]!!
    val markerLabels = remember(markerBands, agarosePercent, runTime) { VirtualGel.resolveLabelCollisions(markerBands, 9, agarosePercent, runTime) }
    val laneRes = remember(lanes, agarosePercent, runTime) { lanes.map { VirtualGel.resolveLabelCollisions(it.bands, 9, agarosePercent, runTime) } }
    val gelW = VirtualGel.gelWidth(lanes.size)
    val dim = Color(0xFFA0A5B5)
    val green = Color(0xFF53D769)

    Canvas(modifier = Modifier.size(gelW.dp, 260.dp)) {
        // 背景 + 径向光晕（radial-gradient rgba(116,76,255,0.08)）
        drawRect(Color(0xFF040508), size = size)
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(Color(0x14744CFF), Color(0x00000000)),
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                radius = size.width * 0.8f
            ),
            size = size
        )
        fun dp(v: Int) = v.dp.toPx()

        // Marker 泳道
        val wellFill = Color(0xFF141724)
        drawRect(wellFill, topLeft = androidx.compose.ui.geometry.Offset(dp(20), dp(6)), size = androidx.compose.ui.geometry.Size(dp(50), dp(5)))
        drawRect(color = Color(0x33FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()), topLeft = androidx.compose.ui.geometry.Offset(dp(20), dp(6)), size = androidx.compose.ui.geometry.Size(dp(50), dp(5)))
        val mk = textMeasurer.measure(selectedMarker, androidx.compose.ui.text.TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Bold, color = dim, fontFamily = FontFamily.Monospace))
        drawText(mk, topLeft = androidx.compose.ui.geometry.Offset(dp(45) - mk.size.width / 2f, dp(15) - mk.size.height * 0.8f))
        markerLabels.forEach { item ->
            val y = item.y.dp.toPx()
            drawLine(Color(0x5953D769), androidx.compose.ui.geometry.Offset(dp(21), y), androidx.compose.ui.geometry.Offset(dp(69), y), strokeWidth = 4.dp.toPx()) // drop-shadow 近似
            drawLine(green, androidx.compose.ui.geometry.Offset(dp(22), y), androidx.compose.ui.geometry.Offset(dp(68), y), strokeWidth = 2.2.dp.toPx())
            val lb = textMeasurer.measure(item.bp.toString(), androidx.compose.ui.text.TextStyle(fontSize = 5.sp, color = dim, fontFamily = FontFamily.Monospace))
            drawText(lb, topLeft = androidx.compose.ui.geometry.Offset(dp(15) - lb.size.width, item.labelY.dp.toPx() - lb.size.height * 0.8f + dp(2)))
        }

        // 样品泳道
        lanes.forEachIndexed { idx, lane ->
            val lx = 100 + idx * 110
            val laneColor = pixColor(lane.color)
            drawRect(wellFill, topLeft = androidx.compose.ui.geometry.Offset(dp(lx), dp(6)), size = androidx.compose.ui.geometry.Size(dp(50), dp(5)))
            drawRect(color = Color(0x33FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()), topLeft = androidx.compose.ui.geometry.Offset(dp(lx), dp(6)), size = androidx.compose.ui.geometry.Size(dp(50), dp(5)))
            val nm = textMeasurer.measure(lane.name, androidx.compose.ui.text.TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Bold, color = laneColor, fontFamily = FontFamily.Monospace))
            drawText(nm, topLeft = androidx.compose.ui.geometry.Offset(dp(lx + 25) - nm.size.width / 2f, dp(15) - nm.size.height * 0.8f))
            laneRes[idx].forEach { item ->
                val y = item.y.dp.toPx()
                drawLine(laneColor.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(dp(lx + 1), y), androidx.compose.ui.geometry.Offset(dp(lx + 49), y), strokeWidth = 4.dp.toPx()) // drop-shadow 近似
                drawLine(laneColor, androidx.compose.ui.geometry.Offset(dp(lx + 2), y), androidx.compose.ui.geometry.Offset(dp(lx + 48), y), strokeWidth = 2.5.dp.toPx())
                val lb = textMeasurer.measure(item.bp.toString() + " bp", androidx.compose.ui.text.TextStyle(fontSize = 5.5.sp, fontWeight = FontWeight.Bold, color = laneColor, fontFamily = FontFamily.Monospace))
                drawText(lb, topLeft = androidx.compose.ui.geometry.Offset(dp(lx + 52), item.labelY.dp.toPx() - lb.size.height * 0.8f + dp(2)))
            }
        }
    }
}

/** main exportGelPng 离屏画布 1:1（android.graphics.Bitmap；shadowBlur → setShadowLayer） */
private fun renderGelExportBitmap(
    lanes: List<VirtualGel.GelLane>,
    selectedMarker: String,
    agarosePercent: Double,
    runTime: Int,
    voltage: Int,
    mult: Int
): android.graphics.Bitmap {
    val markerBands = VirtualGel.MARKER_SETS[selectedMarker] ?: VirtualGel.MARKER_SETS["DL2000"]!!
    val resolvedMarker = VirtualGel.resolveLabelCollisions(markerBands, 9, agarosePercent, runTime)
    val w = VirtualGel.gelWidth(lanes.size) * mult
    val h = 260 * mult
    val wf = w.toFloat(); val hf = h.toFloat()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val monoBold = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    val mono = android.graphics.Typeface.MONOSPACE

    // 背景
    p.color = 0xFF040508.toInt(); cv.drawRect(0f, 0f, wf, hf, p)
    // 径向光晕（rgba(116,76,255,0.15)）
    p.shader = android.graphics.RadialGradient(wf / 2f, hf / 2f, wf, intArrayOf(0x26744CFF, 0x00000000), floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, wf, hf, p)
    p.shader = null

    val laneW = 50f * mult
    val wellY = 6f * mult
    val wellH = 5f * mult

    // Marker 泳道（x=20*mult）
    val mx = 20f * mult
    p.color = 0xFF141724.toInt(); cv.drawRect(mx, wellY, mx + laneW, wellY + wellH, p)
    p.style = android.graphics.Paint.Style.STROKE
    p.strokeWidth = 0.5f * mult
    p.color = 0x33FFFFFF.toInt()
    cv.drawRect(mx, wellY, mx + laneW, wellY + wellH, p)
    p.style = android.graphics.Paint.Style.FILL
    p.color = 0xFFA0A5B5.toInt()
    p.typeface = monoBold
    p.textSize = 5f * mult
    p.textAlign = android.graphics.Paint.Align.CENTER
    cv.drawText(selectedMarker, mx + laneW / 2f, 15f * mult, p)

    p.setShadowLayer(4f * mult, 0f, 0f, 0xFF53D769.toInt())
    p.color = 0xFF53D769.toInt()
    p.style = android.graphics.Paint.Style.STROKE
    p.strokeWidth = 2.2f * mult
    resolvedMarker.forEach { item ->
        val y = item.y.toFloat() * mult
        cv.drawLine(mx + 2f * mult, y, mx + laneW - 2f * mult, y, p)
    }
    p.setShadowLayer(0f, 0f, 0f, 0)
    p.style = android.graphics.Paint.Style.FILL
    p.color = 0xFFA0A5B5.toInt()
    p.typeface = mono
    p.textSize = 4f * mult
    p.textAlign = android.graphics.Paint.Align.RIGHT
    resolvedMarker.forEach { item ->
        cv.drawText(item.bp.toString(), mx - 2f * mult, item.labelY.toFloat() * mult + 1.5f * mult, p)
    }

    // 样品泳道（lx = 100 + idx*110）
    lanes.forEachIndexed { idx, lane ->
        val lx = (100f + idx * 110f) * mult
        val laneColor = android.graphics.Color.parseColor(lane.color)
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.color = 0xFF141724.toInt()
        cv.drawRect(lx, wellY, lx + laneW, wellY + wellH, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 0.5f * mult
        p.color = 0x33FFFFFF.toInt()
        cv.drawRect(lx, wellY, lx + laneW, wellY + wellH, p)
        p.style = android.graphics.Paint.Style.FILL
        p.color = laneColor
        p.typeface = monoBold
        p.textSize = 5f * mult
        cv.drawText(lane.name, lx + laneW / 2f, 15f * mult, p)

        p.setShadowLayer(5f * mult, 0f, 0f, laneColor)
        p.color = laneColor
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 2.5f * mult
        val resolvedBands = VirtualGel.resolveLabelCollisions(lane.bands, 9, agarosePercent, runTime)
        resolvedBands.forEach { item ->
            val y = item.y.toFloat() * mult
            cv.drawLine(lx + 2f * mult, y, lx + laneW - 2f * mult, y, p)
        }
        p.setShadowLayer(0f, 0f, 0f, 0)
        p.style = android.graphics.Paint.Style.FILL
        p.color = laneColor
        p.typeface = monoBold
        p.textSize = 4.5f * mult
        p.textAlign = android.graphics.Paint.Align.LEFT
        resolvedBands.forEach { item ->
            cv.drawText(item.bp.toString() + " bp", lx + laneW + 2f * mult, item.labelY.toFloat() * mult + 1.5f * mult, p)
        }
    }

    // 底注（rgba(255,255,255,0.25)）
    p.color = 0x40FFFFFF.toInt()
    p.typeface = mono
    p.textSize = 4f * mult
    p.textAlign = android.graphics.Paint.Align.CENTER
    cv.drawText(
        "SPICE Gel — " + com.spice.nativeapp.core.jsNum(agarosePercent) + "% agarose, " + runTime + "min, " + voltage + "V (Super-Res " + mult + "x)",
        wf / 2f, hf - 6f * mult, p
    )
    return bmp
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
private fun NgsPanel(record: SeqRecord, onLog: (List<String>) -> Unit) {
    // main gene/panels/NgsGenomicsPanel.svelte 1:1（4 子 tab）
    // 平台差异（披露）：main 的 "Rust 计算中..."/"Rust 基因搜索中..." loading 态来自 Tauri
    // 异步 invoke，安卓本地同步计算瞬时完成，不再渲染；toast → 面板内 status 行。
    // main 面板默认 FASTQ demo 文本第一条 read qual 为 32 字符（≠seq 33）→ 该记录
    // 按 rust 解析器语义被跳过，属 main 原版 quirk，1:1 保留。
    var tab by remember { mutableStateOf(0) } // 0=fastq_qc 1=trimmer 2=orf_gene 3=crispr
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }
    fun toast(ok: Boolean, title: String, text: String? = null) {
        statusGood = ok
        status = title + (text?.let { " — " + it } ?: "")
    }

    var fastqInputText by remember { mutableStateOf("@SEQ_ID\nGATCGGAAGAGCACACGTCTGAACTCCAGTCAC\n+\n!''*((((***+))%%%++)(%%%%).1***-\n@SEQ_ID2\nAGATCGGAAGAGCACACGTCTGAACTCCAGTCA\n+\nI!IIIIIIIIIIIIIIIIIIIIIIIIIIIIIII") }
    var trimmerAdapterInput by remember { mutableStateOf("") }

    // main $effect: 每次 fastqInputText 变化即重算（rust 真身 + guarded TS 回退）
    val fastqReport = remember(fastqInputText) { NgsQc.analyzeFastqQuality(fastqInputText) }

    // main $effect: findOrfsRust(dnaSeq, 90) + 面板层 RBS/评分映射（startCodon/stopCodon
    // 缺省 'ATG'/'TAA'，rust 真身不回填 → 恒显默认值，main quirk 1:1）
    val predictedOrfs = remember(record.sequence) {
        NgsQc.findOrfs(record.sequence, 90).mapIndexed { idx, o ->
            val startPos = o.start
            val upstreamStart = maxOf(0, startPos - 18)
            val upstream = record.sequence.substring(upstreamStart, startPos).uppercase()
            var hasRbsMatch = false
            var rbsBonus = 0
            if (upstream.contains("AGGAGG") || upstream.contains("GGAGG") || upstream.contains("GAGG")) {
                hasRbsMatch = true; rbsBonus = 35
            } else if (upstream.contains("GGAG") || upstream.contains("AGGA")) {
                hasRbsMatch = true; rbsBonus = 20
            }
            val score = kotlin.math.min(
                99.0,
                45.0 + rbsBonus + kotlin.math.min(45.0, (o.length / 300.0) * 10)
            ).let { java.math.BigDecimal(it).setScale(0, java.math.RoundingMode.HALF_UP).toInt() }
            NgsGene(idx + 1, o.start, o.end, o.strand, "ATG", "TAA", hasRbsMatch, score)
        }
    }

    // main TAB 4: detectCrisprArrays(dnaSeq)（core/GenomicsTools.kt 已 1:1 移植）
    val crisprs = remember(record.sequence) { GenomicsTools.detectCrisprArrays(record.sequence) }

    // 子 tab 选择器（main 4 按钮）
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            com.spice.nativeapp.core.I18n.t("FASTQ质控"),
            com.spice.nativeapp.core.I18n.t("接头切除"),
            com.spice.nativeapp.core.I18n.t("原核基因预测"),
            com.spice.nativeapp.core.I18n.t("CRISPR检索")
        ).forEachIndexed { i, label ->
            androidx.compose.material3.FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label, fontSize = 9.sp) })
        }
    }

    when (tab) {
        0 -> {
            // TAB 1: FASTQ QC
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("FASTQ 质量门控与接头去除 [C-12]"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
                androidx.compose.material3.OutlinedTextField(
                    value = fastqInputText,
                    onValueChange = { fastqInputText = it },
                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("粘贴 FASTQ 序列..."), fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                )
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.t("总 Reads 数:"), fontSize = 10.sp)
                        Text(com.spice.nativeapp.core.I18n.tf("测序总 Reads 数量:", fastqReport.totalReads), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CsPixGreen)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.t("测序总 Reads 数量:"), fontSize = 10.sp)
                        Text("${fastqReport.averageReadLength} bp", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.t("平均 GC 含量:"), fontSize = 10.sp)
                        Text("${fastqReport.gcContentPercent}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CsPixCyan)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(com.spice.nativeapp.core.I18n.t("重复序列比例:"), fontSize = 10.sp)
                        Text("${fastqReport.duplicateReadsPercent}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = if (fastqReport.duplicateReadsPercent > 20) CsPixRed else CsPixGreen)
                    }
                }
            }
        }
        1 -> {
            // TAB 2: TRIMMER
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("自动接头去除与清理 [C-12]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
                androidx.compose.material3.OutlinedTextField(
                    value = trimmerAdapterInput,
                    onValueChange = { trimmerAdapterInput = it },
                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("自动接头去除与清理 [C-12]:"), fontSize = 9.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                )
                androidx.compose.material3.Button(
                    onClick = {
                        val lines = fastqInputText.split("\n")
                        val reads = ArrayList<String>()
                        val quals = ArrayList<String>()
                        var i = 0
                        while (i < lines.size - 3) {
                            if (lines[i].startsWith("@") && lines[i + 2].startsWith("+")) {
                                reads.add(lines[i + 1])
                                quals.add(lines[i + 3])
                            }
                            i += 4
                        }
                        val res = NgsQc.autoIdentifyAndRemoveAdapters(reads, quals, trimmerAdapterInput.ifEmpty { null })
                        onLog(res.log)
                        toast(true, com.spice.nativeapp.core.I18n.t("接头裁剪成功！"), com.spice.nativeapp.core.I18n.tf("共处理并切除了 {v1} 条 reads", res.trimmedCount))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                ) {
                    Text("✂️ " + com.spice.nativeapp.core.I18n.t("接头裁剪成功！"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        2 -> {
            // TAB 3: PROKARYOTIC GENE PREDICTOR
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("原核带 SD/RBS 界标基因边界预测 [C-13]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (predictedOrfs.isEmpty()) {
                        Text(com.spice.nativeapp.core.I18n.t("未扫描到符合原核保守特性的基因编码区。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp).align(androidx.compose.ui.Alignment.CenterHorizontally))
                    }
                    predictedOrfs.forEach { g ->
                        Column(
                            Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0x034CD6FF)).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("🧬 Gene ID: ${g.id}", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                Text(com.spice.nativeapp.core.I18n.t("区间:") + " ${g.start + 1}-${g.end + 1} bp (${g.strand})", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(com.spice.nativeapp.core.I18n.tf("起始: {v1} | 终止: {v2}", g.startCodon, g.stopCodon), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (g.hasRbsMatch) "✓ " + com.spice.nativeapp.core.I18n.t("SD 序列匹配") else com.spice.nativeapp.core.I18n.t("无显著 SD 序列"),
                                    fontSize = 8.5.sp,
                                    color = if (g.hasRbsMatch) CsPixGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(com.spice.nativeapp.core.I18n.t("综合可信度得分:"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                Text("${g.score} / 100", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = CsPixCyan)
                            }
                        }
                    }
                }
            }
        }
        else -> {
            // TAB 4: CRISPR ARRAY FINDER
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("免疫防御 system Direct Repeat 阵列预测 [C-14]:"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CsPixCyan)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (crisprs.isEmpty()) {
                        Text(com.spice.nativeapp.core.I18n.t("未在当前基因组模板中检索到天然 CRISPR DR-Spacer 重复阵列。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 25.dp).align(androidx.compose.ui.Alignment.CenterHorizontally))
                    }
                    crisprs.forEach { c ->
                        Column(
                            Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0x0353D769)).padding(5.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("🦠 CRISPR Array (Repeats: ${c.repeatCount})", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = CsPixGreen)
                                Text(com.spice.nativeapp.core.I18n.t("区间:") + " ${c.start + 1}-${c.end + 1} bp", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text("DR Consensus: ${c.repeatConsensus}", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = CsPixCyan)
                            Text(com.spice.nativeapp.core.I18n.tf("包含噬菌体转录间隔 Spacers 数: {v1} 条", c.spacers.size), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(com.spice.nativeapp.core.I18n.t("阵列保守性综合得分:"), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                Text("${c.confidence}%", fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = CsPixGreen)
                            }
                        }
                    }
                }
            }
        }
    }

    status?.let {
        Text(it, fontSize = 9.sp, color = if (statusGood) PIX_GREEN else PIX_RED, modifier = Modifier.fillMaxWidth())
    }
}

/** NgsPanel 的基因预测行（main 面板 predictedOrfs 映射结构 1:1） */
private data class NgsGene(
    val id: Int,
    val start: Int,
    val end: Int,
    val strand: String,
    val startCodon: String,
    val stopCodon: String,
    val hasRbsMatch: Boolean,
    val score: Int
)

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
/** 反向翻译面板 —— main gene/panels/ReverseTranslationPanel.svelte 1:1（floating-panel → 面板 sheet 内嵌区）。 */
private fun RevTransPanel(record: SeqRecord, clipboard: androidx.compose.ui.platform.ClipboardManager, onRecordChange: (SeqRecord) -> Unit) {
    // main 面板独立于工作台序列，默认 protein = 'MKT'；安卓沿用默认值（1:1）。
    var protein by remember { mutableStateOf("MKT") }
    var host by remember { mutableStateOf("ecoli") }
    var includeStop by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("both") } // both | dna | mrna
    var result by remember { mutableStateOf<com.spice.nativeapp.core.ReverseTranslate.Result?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = protein,
        onValueChange = { protein = it },
        label = { Text(com.spice.nativeapp.core.I18n.t("输入蛋白质序列")) },
        placeholder = { Text(com.spice.nativeapp.core.I18n.t("输入氨基酸序列（1 字母码）…")) },
        modifier = Modifier.fillMaxWidth(), minLines = 3,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(com.spice.nativeapp.core.I18n.t("Kazusa 宿主") + "：", fontSize = 11.sp)
        androidx.compose.material3.FilterChip(
            selected = host == "ecoli", onClick = { host = "ecoli" },
            label = { Text(com.spice.nativeapp.core.I18n.t("大肠杆菌"), fontSize = 10.sp) },
        )
        androidx.compose.material3.FilterChip(
            selected = host == "yeast", onClick = { host = "yeast" },
            label = { Text(com.spice.nativeapp.core.I18n.t("酿酒酵母"), fontSize = 10.sp) },
        )
        androidx.compose.material3.FilterChip(
            selected = host == "human", onClick = { host = "human" },
            label = { Text(com.spice.nativeapp.core.I18n.t("人源表达系"), fontSize = 10.sp) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.FilterChip(
            selected = output == "both", onClick = { output = "both" },
            label = { Text(com.spice.nativeapp.core.I18n.t("DNA + mRNA"), fontSize = 10.sp) },
        )
        androidx.compose.material3.FilterChip(
            selected = output == "dna", onClick = { output = "dna" },
            label = { Text(com.spice.nativeapp.core.I18n.t("cDNA"), fontSize = 10.sp) },
        )
        androidx.compose.material3.FilterChip(
            selected = output == "mrna", onClick = { output = "mrna" },
            label = { Text(com.spice.nativeapp.core.I18n.t("mRNA"), fontSize = 10.sp) },
        )
        androidx.compose.material3.FilterChip(
            selected = includeStop, onClick = { includeStop = !includeStop },
            label = { Text(com.spice.nativeapp.core.I18n.t("终止密码子"), fontSize = 10.sp) },
        )
    }
    Button(
        onClick = {
            if (protein.isBlank()) {
                toast = com.spice.nativeapp.core.I18n.t("反向翻译") + " — " + com.spice.nativeapp.core.I18n.t("请输入蛋白质序列")
                return@Button
            }
            try {
                val r = com.spice.nativeapp.core.ReverseTranslate.reverseTranslate(
                    protein,
                    com.spice.nativeapp.core.ReverseTranslate.Options(host = host, optimizeGc = false, includeStop = includeStop),
                )
                result = r
                toast = com.spice.nativeapp.core.I18n.t("反向翻译完成") + " — ${r.dnaSequence.length} " + com.spice.nativeapp.core.I18n.t("bp 已生成")
            } catch (e: Exception) {
                toast = com.spice.nativeapp.core.I18n.t("反向翻译失败") + " — " + (e.message ?: "")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(com.spice.nativeapp.core.I18n.t("生成并验证")) }
    toast?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
    result?.let { r ->
        Text(
            com.spice.nativeapp.core.I18n.t("验证") + ": " +
                (if (r.validation.valid) com.spice.nativeapp.core.I18n.t("通过") else com.spice.nativeapp.core.I18n.t("失败")) +
                " · ${r.codonTrace.size} " + com.spice.nativeapp.core.I18n.t("个密码子"),
            fontSize = 11.sp,
            color = if (r.validation.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (output != "mrna") Block(com.spice.nativeapp.core.I18n.t("cDNA"), r.cdnaSequence)
        if (output != "dna") Block(com.spice.nativeapp.core.I18n.t("mRNA"), r.mrnaSequence)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(r.cdnaSequence))
                toast = com.spice.nativeapp.core.I18n.t("已复制") + " — " + com.spice.nativeapp.core.I18n.t("已复制 cDNA")
            }) { Text(com.spice.nativeapp.core.I18n.t("复制 cDNA"), fontSize = 10.sp) }
            OutlinedButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(r.mrnaSequence))
                toast = com.spice.nativeapp.core.I18n.t("已复制") + " — " + com.spice.nativeapp.core.I18n.t("已复制 mRNA")
            }) { Text(com.spice.nativeapp.core.I18n.t("复制 mRNA"), fontSize = 10.sp) }
            OutlinedButton(onClick = {
                onRecordChange(record.copy(sequence = r.cdnaSequence, name = record.name + "_rt"))
            }) { Text(com.spice.nativeapp.core.I18n.t("应用为 DNA 序列"), fontSize = 10.sp) }
        }
        var traceOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { traceOpen = !traceOpen }) { Text(com.spice.nativeapp.core.I18n.t("密码子轨迹"), fontSize = 10.sp) }
        if (traceOpen) {
            Text(
                r.codonTrace.joinToString("\n") { t -> "${t.index + 1}. ${t.aminoAcid} ${t.codon} [${t.dnaStart + 1}-${t.dnaEnd}]" },
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            )
        }
        if (r.warnings.isNotEmpty()) {
            Text(r.warnings.joinToString(" · "), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
        }
    }
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
