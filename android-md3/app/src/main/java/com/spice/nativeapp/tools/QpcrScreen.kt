package com.spice.nativeapp.tools

/*
 * qPCR 分析工作站 —— 1:1 移植自原版 src/lib/tools/qpcr/QpcrTool.svelte。
 * 算法层见 tools/qpcr/（Types/Parse/Stats/Example/Analysis/Chart/Raster/Export/Storage/Repro）。
 * UI 词条全部使用 main zh 原文（I18n.t 直查 core 词典；命名占位符用 interp() 本地插值）。
 *
 * 平台差异（README Known gaps §10 披露）：
 * - ToolProjectBar / projectStore（项目中心 handoff、qpcrDocSummary/qpcrDocPreview）无安卓侧等价物；
 * - 文件拖放不适用触屏（粘贴框 + 系统 picker）；
 * - window.confirm → AlertDialog（qpcrResetConfirm）；
 * - localStorage 自动保存（$effect 600ms）→ SharedPreferences 全量 JSON（QpcrStorage）；
 * - backend.saveExport → 落盘 filesDir/qpcr-exports/；sendChartToFigure（跨工具实时桥）
 *   → PNG 落盘 files/figures/，失败 toast sendToFigureFailed；
 * - @html SVG 注入预览 → Canvas 位图孪生（QpcrRaster，几何与 SVG 逐值一致）；
 *   PNG 导出为同一位图 scale 2（对应 main 的 svgToPngBytes scale 2）。
 */
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.I18n
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixNumberField
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.PixTextField
import com.spice.nativeapp.tools.qpcr.ChartData
import com.spice.nativeapp.tools.qpcr.ChartLabels
import com.spice.nativeapp.tools.qpcr.CurveReasons
import com.spice.nativeapp.tools.qpcr.DdctAnalysis
import com.spice.nativeapp.tools.qpcr.EXAMPLE_TSV
import com.spice.nativeapp.tools.qpcr.HeatmapLabels
import com.spice.nativeapp.tools.qpcr.HeatmapRow
import com.spice.nativeapp.tools.qpcr.ParsedTable
import com.spice.nativeapp.tools.qpcr.PlateMapRow
import com.spice.nativeapp.tools.qpcr.QpcrColumnMapping
import com.spice.nativeapp.tools.qpcr.QpcrColumns
import com.spice.nativeapp.tools.qpcr.QpcrReproInput
import com.spice.nativeapp.tools.qpcr.QpcrSettings
import com.spice.nativeapp.tools.qpcr.QpcrStorage
import com.spice.nativeapp.tools.qpcr.QpcrWell
import com.spice.nativeapp.tools.qpcr.StandardAnalysis
import com.spice.nativeapp.tools.qpcr.WellQc
import com.spice.nativeapp.tools.qpcr.autoMap
import com.spice.nativeapp.tools.qpcr.barChartBitmap
import com.spice.nativeapp.tools.qpcr.barChartSvg
import com.spice.nativeapp.tools.qpcr.buildResultsCsv
import com.spice.nativeapp.tools.qpcr.buildQpcrReproBundle
import com.spice.nativeapp.tools.qpcr.buildWells
import com.spice.nativeapp.tools.qpcr.chartDataFromDdct
import com.spice.nativeapp.tools.qpcr.chartDataFromStandard
import com.spice.nativeapp.tools.qpcr.computeDdct
import com.spice.nativeapp.tools.qpcr.computeQc
import com.spice.nativeapp.tools.qpcr.computeStandard
import com.spice.nativeapp.tools.qpcr.emptyProject
import com.spice.nativeapp.tools.qpcr.heatmapBitmap
import com.spice.nativeapp.tools.qpcr.listGroups
import com.spice.nativeapp.tools.qpcr.listSamples
import com.spice.nativeapp.tools.qpcr.listTargets
import com.spice.nativeapp.tools.qpcr.missingRequiredColumns
import com.spice.nativeapp.tools.qpcr.normKey
import com.spice.nativeapp.tools.qpcr.nowIso
import com.spice.nativeapp.tools.qpcr.parseDelimited
import com.spice.nativeapp.tools.qpcr.parsePlateMap
import com.spice.nativeapp.tools.qpcr.plateMapToQpcrTsv
import com.spice.nativeapp.tools.qpcr.projectFromJson
import com.spice.nativeapp.tools.qpcr.projectToJson
import com.spice.nativeapp.tools.qpcr.sanitizeSettings
import com.spice.nativeapp.tools.qpcr.usableWell
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round

private fun t(zh: String): String = I18n.t(zh)

/** main 的具名占位词条（{n}/{name}/{names}/{limit}/{v}…）本地插值。 */
private fun interp(template: String, vararg pairs: Pair<String, Any?>): String {
    var out = I18n.t(template)
    pairs.forEach { (k, v) -> out = out.replace("{$k}", v?.toString() ?: "") }
    return out
}

// ------------------------------------------------------------------ formatting

/** JS toExponential/toPrecision 风格指数写法：去指数前导零（e+04 → e+4）。 */
private fun jsExp(raw: String): String =
    raw.replace(Regex("e([+-])0(\\d)"), "e$1$2")

private fun fmt(value: Double?, digits: Int = 2): String {
    if (value === null || !value.isFinite()) return "—"
    return String.format(java.util.Locale.US, "%.${digits}f", value)
}

private fun fmtFold(value: Double?): String {
    if (value === null || !value.isFinite()) return "—"
    if (value >= 1000 || (value > 0 && value < 0.01)) return jsExp(String.format(java.util.Locale.US, "%.3g", value))
    return String.format(java.util.Locale.US, "%.3f", value)
}

private fun fmtQty(value: Double?): String {
    if (value === null || !value.isFinite()) return "—"
    if (value >= 10000 || (value > 0 && value < 0.001)) return jsExp(String.format(java.util.Locale.US, "%.2e", value))
    val precision = String.format(java.util.Locale.US, "%.3g", value).toDouble()
    return BigDecimal(precision).stripTrailingZeros().toPlainString()
}

private fun fmtEff(value: Double?): String {
    if (value === null || !value.isFinite()) return "—"
    return "${round(value * 100).toInt()}%"
}

private fun fmtP(value: Double?): String {
    if (value === null || !value.isFinite()) return "—"
    if (value > 0 && value < 0.0001) return jsExp(String.format(java.util.Locale.US, "%.2e", value))
    return String.format(java.util.Locale.US, "%.4f", value)
}

/** JS 数字字符串化（1.0 → "1"，1.5 → "1.5"）。 */
private fun fmtNumber(value: Double?): String {
    if (value === null || !value.isFinite()) return "—"
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else BigDecimal(value).stripTrailingZeros().toPlainString()
}

private fun uriDisplayName(ctx: android.content.Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "file"
    } ?: "file"
} catch (e: Exception) {
    "file"
}

private fun readUriText(ctx: android.content.Context, uri: Uri): String =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

private class PlatePreview(
    val fileName: String,
    val rows: List<PlateMapRow>,
    var target: String,
)

private fun plateSampleCount(rows: List<PlateMapRow>): Int =
    rows.map { it.sample.trim() }.filter { it.isNotEmpty() }.toSet().size

private fun plateGroupCount(rows: List<PlateMapRow>): Int =
    rows.map { it.group.trim() }.filter { it.isNotEmpty() }.toSet().size

// --------------------------------------------------------------------- screen

@Composable
fun QpcrScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var project by remember { mutableStateOf(emptyProject()) }
    var headers by remember { mutableStateOf(listOf<String>()) }
    var pasteText by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("fold") }
    var busy by remember { mutableStateOf("") }
    var uiRev by remember { mutableIntStateOf(0) }
    var platePreview by remember { mutableStateOf<PlatePreview?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var quotaWarned by remember { mutableStateOf(false) }
    var persistJob by remember { mutableStateOf<Job?>(null) }

    fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun schedulePersist() {
        project.updatedAt = nowIso()
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(600)
            if (!loaded) return@launch
            if (QpcrStorage.saveSnapshot(ctx, project)) {
                quotaWarned = false
            } else if (!quotaWarned) {
                quotaWarned = true
                toast(t("本机存储已满，请下载项目 JSON 备份"), long = true)
            }
        }
    }

    fun bump() {
        uiRev++
        schedulePersist()
    }

    fun refreshHeaders() {
        headers = if (project.rawText.trim().isEmpty()) emptyList() else try {
            parseDelimited(project.rawText).headers
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------- lifecycle

    LaunchedEffect(Unit) {
        val snapshot = QpcrStorage.loadSnapshot(ctx)
        if (snapshot != null) {
            project = snapshot
            refreshHeaders()
            if (snapshot.mode == "standard") activeTab = "curve"
            uiRev++
        }
        loaded = true
    }

    // ---------------------------------------------------------------- derived

    val qc = remember(uiRev) { computeQc(project.wells, project.settings) }
    val ddct = remember(uiRev) { computeDdct(project.wells, project.settings, qc) }
    val standard = remember(uiRev) { computeStandard(project.wells, project.settings, qc) }
    val mappingReady = missingRequiredColumns(project.mapping).isEmpty()
    val hasData = project.wells.isNotEmpty()
    val flaggedCount = qc.values.count { it.flags.isNotEmpty() }

    val chartData: ChartData? = remember(uiRev) {
        if (project.mode == "standard") chartDataFromStandard(standard) else chartDataFromDdct(ddct)
    }
    val chartTitle = t(if (project.mode == "standard") "拷贝数 / 标准品量" else "倍数变化")
    val chartYLabel = t(if (project.mode == "standard") "数量（log10）" else "倍数变化")
    val chartLabels = ChartLabels(chartTitle, chartYLabel)
    val chartDoc = remember(uiRev) { chartData?.let { barChartSvg(it, chartLabels) } }
    val chartBmp = remember(uiRev) { chartData?.let { barChartBitmap(it, chartLabels, 1f) } }
    val heatmapBmp = remember(uiRev) {
        if (!project.settings.showHeatmap || project.mode != "ddct" || ddct.targets.isEmpty() || ddct.matrix.isEmpty()) null
        else heatmapBitmap(
            ddct.matrix.map { row -> HeatmapRow(row.sample, row.group, ddct.targets.map { row.dCt[it.key] }) },
            ddct.targets.map { it.label },
            HeatmapLabels(t("ΔCt 热图"), t("低"), t("高")),
            1f,
        )
    }

    // ---------------------------------------------------------------- helpers

    fun applySelectionDefaults() {
        val settings = project.settings
        val targets = listTargets(project.wells)
        val groups = listGroups(project.wells)
        val samples = listSamples(project.wells)
        val validReferences = ArrayList<String>()
        for (label in settings.referenceTargets) {
            val target = targets.firstOrNull { it.key == normKey(label) }
            if (target != null && validReferences.none { normKey(it) == target.key }) validReferences.add(target.label)
        }
        if (validReferences.isEmpty() && settings.referenceTarget.isNotEmpty()) {
            val legacy = targets.firstOrNull { it.key == normKey(settings.referenceTarget) }
            if (legacy != null) validReferences.add(legacy.label)
        }
        if (validReferences.isEmpty() && targets.isNotEmpty()) validReferences.add(targets[0].label)
        // 无靶标时（如等待默认靶标的孔板图）保留既有选择。
        if (validReferences.isNotEmpty() || targets.isNotEmpty()) {
            settings.referenceTargets = validReferences
            settings.referenceTarget = validReferences.firstOrNull() ?: ""
        }
        if (groups.none { it.key == normKey(settings.controlGroup) }) settings.controlGroup = groups.firstOrNull()?.label ?: ""
        if (samples.none { it.key == normKey(settings.controlSample) }) settings.controlSample = samples.firstOrNull()?.label ?: ""
        bump()
    }

    fun applyText(text: String, name: String, preserveInclude: Boolean = false): Boolean {
        if (text.trim().isEmpty()) {
            toast(t("请先粘贴表格或选择文件"))
            return false
        }
        val table: ParsedTable = try {
            parseDelimited(text)
        } catch (error: Exception) {
            toast(t("无法读取该表格"))
            return false
        }
        if (table.headers.isEmpty()) {
            toast(t("无法读取该表格"))
            return false
        }
        val mapping = autoMap(table.headers)
        val previous = if (preserveInclude) project.wells else emptyList()
        headers = table.headers
        project.rawText = text
        project.fileName = name
        project.mapping = mapping
        project.wells = buildWells(table, mapping, previous).toMutableList()
        applySelectionDefaults()
        if (!project.wells.any { it.sample.trim().isNotEmpty() && it.target.trim().isNotEmpty() }) {
            toast(t("没有找到包含样本和靶标的行"))
        } else {
            toast(interp("已载入 {n} 个孔", "n" to project.wells.size))
        }
        return true
    }

    fun loadPasted() {
        applyText(pasteText, "pasted-data.tsv")
    }

    fun loadExample() {
        pasteText = EXAMPLE_TSV
        applyText(EXAMPLE_TSV, "qpcr-example.tsv")
    }

    fun onMapChange(key: String, value: Int) {
        project.mapping.set(key, value)
        if (project.rawText.trim().isEmpty()) {
            bump()
            return
        }
        val table = parseDelimited(project.rawText)
        headers = table.headers
        project.wells = buildWells(table, project.mapping, project.wells).toMutableList()
        applySelectionDefaults()
    }

    fun setInclude(wellId: String, value: Boolean) {
        project.wells.firstOrNull { it.id == wellId }?.let { it.include = value }
        bump()
    }

    fun includeAll() {
        for (well in project.wells) well.include = true
        bump()
    }

    fun excludeFlagged() {
        for (well in project.wells) {
            val entry = qc[well.id]
            if (entry != null && entry.flags.isNotEmpty()) well.include = false
        }
        bump()
    }

    fun normalizeCurrentSettings() {
        val s = project.settings
        val json = JSONObject().apply {
            put("highCt", s.highCt)
            put("sdLimit", s.sdLimit)
            put("outlierLimit", s.outlierLimit)
            put("excludeOutliers", s.excludeOutliers)
            put("efficiencyCorrection", s.efficiencyCorrection)
            put("defaultEfficiencyPct", s.defaultEfficiencyPct)
            put("quantityIsLog10", s.quantityIsLog10)
            put("showHeatmap", s.showHeatmap)
            put("referenceTarget", s.referenceTarget)
            put("referenceTargets", org.json.JSONArray(s.referenceTargets))
            put("controlGroup", s.controlGroup)
            put("controlSample", s.controlSample)
        }
        project.settings = sanitizeSettings(json)
        bump()
    }

    fun setMode(mode: String) {
        project.mode = mode
        activeTab = if (mode == "standard") "curve" else "fold"
        bump()
    }

    fun isReference(label: String): Boolean {
        val key = normKey(label)
        return project.settings.referenceTargets.any { normKey(it) == key }
    }

    fun toggleReference(label: String, checked: Boolean) {
        val key = normKey(label)
        val current = project.settings.referenceTargets
        val next = if (checked) current.filter { normKey(it) != key } + label
        else current.filter { normKey(it) != key }
        if (next.isEmpty()) {
            toast(t("请至少选择一个参考靶标。"))
            return
        }
        val unique = ArrayList<String>()
        for (item in next) {
            if (unique.none { normKey(it) == normKey(item) }) unique.add(item)
        }
        project.settings.referenceTargets = unique
        project.settings.referenceTarget = unique.firstOrNull() ?: ""
        bump()
    }

    fun applyPlatePreview() {
        val preview = platePreview ?: return
        val text = plateMapToQpcrTsv(preview.rows, preview.target.trim())
        val table = try {
            parseDelimited(text)
        } catch (error: Exception) {
            toast(t("无法读取该表格"))
            return
        }
        if (table.headers.isEmpty()) {
            toast(t("无法读取该表格"))
            return
        }
        val mapping = autoMap(table.headers)
        val wells = buildWells(table, mapping).toMutableList()
        // 预留孔与空白孔保留在表中但默认排除。
        preview.rows.forEachIndexed { index, row ->
            val well = wells.getOrNull(index) ?: return@forEachIndexed
            if (row.type == "reserved" || row.sample.trim().isEmpty()) well.include = false
        }
        headers = table.headers
        project.rawText = text
        project.fileName = preview.fileName
        project.mapping = mapping
        project.wells = wells
        bump()
        toast(interp("孔板图已载入：{n} 孔", "n" to wells.size))
        platePreview = null
    }

    fun baseName(): String {
        val name = project.fileName.replace(Regex("\\.[^.]+$"), "").ifEmpty { "qpcr" }
        return name.replace(Regex("[^\\w.-]+"), "_")
    }

    fun docName(): String =
        project.fileName.replace(Regex("\\.[^.]+$"), "").trim().ifEmpty { t("qPCR 分析") }

    fun saveBytes(name: String, bytes: ByteArray): Boolean {
        return try {
            val dir = File(ctx.filesDir, "qpcr-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            toast("${t("文件已保存")}：qpcr-exports/$name")
            true
        } catch (e: Exception) {
            toast(t("导出失败"), long = true)
            false
        }
    }

    fun saveText(name: String, text: String): Boolean = saveBytes(name, text.toByteArray(Charsets.UTF_8))

    fun exportCsv() {
        if (!hasData) {
            toast(t("请先粘贴或上传数据"))
            return
        }
        val csv = buildResultsCsv(project, qc, ddct, standard)
        saveText("${baseName()}_qpcr_results.csv", csv)
    }

    fun exportReproBundle() {
        val data = chartData
        if (!hasData || data == null) {
            toast(t("请先载入数据并生成图表"))
            return
        }
        busy = t("正在生成可复现数据包...")
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    buildQpcrReproBundle(
                        ctx,
                        QpcrReproInput(
                            project = project,
                            chart = barChartSvg(data, chartLabels),
                            chartData = data,
                            chartLabels = chartLabels,
                            qc = qc,
                            ddct = ddct,
                            standard = standard,
                            title = docName(),
                            flagged = flaggedCount,
                        ),
                    )
                }
                toast("${t("可复现数据包已保存")}：${result.fileName}")
            } catch (error: Exception) {
                toast(t("无法生成可复现数据包"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun exportChartSvg() {
        val doc = chartDoc ?: run { toast(t("暂无可导出的图表")); return }
        saveText("${baseName()}_qpcr_chart.svg", doc.svg)
    }

    fun exportChartPng() {
        val data = chartData ?: run { toast(t("暂无可导出的图表")); return }
        busy = t("正在导出…")
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val bmp = barChartBitmap(data, chartLabels, 2f)
                    val bytes = ByteArrayOutputStream().let { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    saveBytes("${baseName()}_qpcr_chart.png", bytes)
                }
            } catch (error: Exception) {
                toast(t("导出失败"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun sendChartToFigure() {
        val data = chartData ?: run { toast(t("暂无可导出的图表")); return }
        if (busy.isNotEmpty()) return
        busy = t("正在导出…")
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    // 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（README 披露）。
                    val bmp = barChartBitmap(data, chartLabels, 2f)
                    val bytes = ByteArrayOutputStream().let { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                    File(dir, "${baseName()}_qpcr_chart.png").writeBytes(bytes)
                }
            } catch (error: Exception) {
                toast(t("无法发送到 Figure Studio"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun exportProjectJson() {
        project.updatedAt = nowIso()
        saveText("${baseName()}_qpcr_project.json", projectToJson(project))
    }

    fun resetAll() {
        project = emptyProject()
        headers = emptyList()
        pasteText = ""
        activeTab = "fold"
        QpcrStorage.clearSnapshot(ctx)
        toast(t("工作区已清空"))
        uiRev++
    }

    // ---------------------------------------------------------------- pickers

    val dataPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                applyText(readUriText(ctx, uri), uriDisplayName(ctx, uri))
            } catch (error: Exception) {
                toast(t("无法读取该表格"), long = true)
            }
        }
    }
    val platePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val rows = parsePlateMap(readUriText(ctx, uri))
                if (rows.isEmpty()) {
                    toast(t("该孔板图中未找到孔"))
                } else {
                    platePreview = PlatePreview(uriDisplayName(ctx, uri), rows, "")
                }
            } catch (error: Exception) {
                toast(t("无法读取该表格"), long = true)
            }
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val parsed = projectFromJson(readUriText(ctx, uri))
                if (parsed == null) {
                    toast(t("qPCR 项目文件无效"))
                } else {
                    project = parsed
                    refreshHeaders()
                    if (parsed.mode == "standard") activeTab = "curve"
                    uiRev++
                    schedulePersist()
                    toast(t("项目已恢复"))
                }
            } catch (error: Exception) {
                toast(t("qPCR 项目文件无效"))
            }
        }
    }

    // -------------------------------------------------------------- UI atoms

    @Composable
    fun Chip(text: String, warn: Boolean = false) {
        Text(
            text,
            fontSize = 8.5.sp,
            color = if (warn) Color(0xFFE8892B.toInt()) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .border(1.dp, if (warn) Color(0xFFE8892B.toInt()) else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    @Composable
    fun Banner(message: String, kind: String) {
        val (bg, fg) = when (kind) {
            "error" -> Color(0x26D64545) to Color(0xFFD64545.toInt())
            "warn" -> Color(0x26E8892B) to Color(0xFFE8892B.toInt())
            else -> Color(0x264CD6FF) to Color(0xFF4CD6FF.toInt())
        }
        Text(
            message,
            fontSize = 9.5.sp,
            color = fg,
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }

    @Composable
    fun NumberFieldRow(label: String, value: Double, onCommit: (Double) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 9.sp, modifier = Modifier.weight(1f))
            PixNumberField(
                value = fmtNumber(value),
                onCommit = { text -> text.trim().toDoubleOrNull()?.let(onCommit) },
            )
        }
    }

    @Composable
    fun FlagChip(flag: String, well: QpcrWell, entry: WellQc?) {
        val (bg, fg) = when (flag) {
            "missingCt" -> Color(0xFFD64545.toInt()) to Color.White
            "highCt" -> Color(0xFF4CD6FF.toInt()) to Color(0xFF10344A)
            "replicateSd" -> Color(0xFF8A5CD6.toInt()) to Color.White
            "outlier" -> Color(0xFFD64545.toInt()) to Color.White
            "duplicateWell" -> Color(0xFF2AA7B8.toInt()) to Color.White
            "missingRef" -> Color(0xFF8A5CD6.toInt()) to Color.White
            "invalid" -> Color(0xFFD64545.toInt()) to Color.White
            else -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.onSurface
        }
        Text(
            flagShort(flag),
            fontSize = 8.sp,
            color = fg,
            modifier = Modifier
                .background(bg, RoundedCornerShape(2.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }

    // --------------------------------------------------------------- sidebar

    @Composable
    fun SidePane() {
        Column(Modifier.fillMaxWidth()) {
            GelSection(t("数据输入"), startOpen = true) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp),
                ) {
                    if (pasteText.isEmpty()) {
                        Text(t("粘贴 TSV 或 CSV 表格，首行为列名"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        textStyle = TextStyle(fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixButton(t("载入粘贴数据"), modifier = Modifier.weight(1f), enabled = pasteText.trim().isNotEmpty(), ok = true, onClick = { loadPasted() })
                    PixButton(t("上传文件"), modifier = Modifier.weight(1f), onClick = { dataPicker.launch(arrayOf("*/*")) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixButton(t("载入示例数据"), modifier = Modifier.weight(1f), onClick = { loadExample() })
                    PixButton(t("导入孔板图"), modifier = Modifier.weight(1f), onClick = { platePicker.launch(arrayOf("*/*")) })
                }
                HintLine(t("支持 .csv / .tsv / .txt，自动识别分隔符与列名"))
                HintLine(t("读取孔板工具导出的 well,sample,group,type,replicate CSV。"))

                val preview = platePreview
                if (preview != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                            .padding(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("孔板图预览"), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            PixButton(t("取消"), onClick = { platePreview = null })
                        }
                        Text(preview.fileName, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Chip(interp("{n} 孔", "n" to preview.rows.size))
                            Chip(interp("{n} 个样本", "n" to plateSampleCount(preview.rows)))
                            Chip(interp("{n} 组", "n" to plateGroupCount(preview.rows)))
                        }
                        preview.rows.take(8).forEach { row ->
                            Text(
                                "${row.well}  ${row.sample}  ${row.group}",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t("默认靶标"), fontSize = 9.sp, modifier = Modifier.weight(1f))
                            PixTextField(value = preview.target, placeholder = "", onChange = { preview.target = it })
                        }
                        PixButton(t("载入孔板图"), modifier = Modifier.fillMaxWidth().padding(top = 4.dp), ok = true, onClick = { applyPlatePreview() })
                    }
                }
            }

            GelSection(t("列映射"), startOpen = true) {
                if (headers.isEmpty()) {
                    HintLine(t("载入数据后可手动指定列"))
                } else {
                    QpcrColumns.COLUMN_KEYS.forEach { key ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val required = key == "sample" || key == "target" || key == "ct"
                            Text(
                                if (required) "${columnLabel(key)}*" else columnLabel(key),
                                fontSize = 9.sp,
                                color = if (required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            PixSelect(
                                options = buildList {
                                    add("-1" to t("不使用"))
                                    headers.forEachIndexed { index, header ->
                                        add("$index" to header.ifEmpty { interp("第 {n} 列", "n" to index + 1) })
                                    }
                                },
                                value = project.mapping.get(key).toString(),
                                onChange = { onMapChange(key, it.toIntOrNull() ?: -1) },
                            )
                        }
                    }
                    if (!mappingReady) {
                        Text(t("样本、靶标和 Ct 列为必选"), fontSize = 8.5.sp, color = Color(0xFFE8892B.toInt()))
                    }
                }
            }

            GelSection(t("分析设置"), startOpen = true) {
                Text(t("参考基因"), fontSize = 9.sp)
                if (ddct.targets.isNotEmpty()) {
                    ddct.targets.forEach { target ->
                        PixCheck(
                            label = target.label,
                            checked = isReference(target.label),
                            onChange = { toggleReference(target.label, it) },
                        )
                    }
                } else {
                    Text("—", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HintLine(t("参考 Ct 为所选靶标 Ct 值的几何平均值。"))
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("对照组"), fontSize = 9.sp, modifier = Modifier.weight(1f))
                    PixSelect(
                        options = buildList {
                            add("" to t("无分组"))
                            ddct.groups.forEach { add(it.label to it.label) }
                        },
                        value = project.settings.controlGroup,
                        onChange = { project.settings.controlGroup = it; bump() },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("对照样本"), fontSize = 9.sp, modifier = Modifier.weight(1f))
                    PixSelect(
                        options = buildList {
                            add("" to "—")
                            ddct.samples.forEach { add(it.label to it.label) }
                        },
                        value = project.settings.controlSample,
                        onChange = { project.settings.controlSample = it; bump() },
                    )
                }
                PixCheck(
                    label = t("启用效率校正"),
                    checked = project.settings.efficiencyCorrection,
                    onChange = { project.settings.efficiencyCorrection = it; bump() },
                )
                NumberFieldRow(t("默认扩增效率 (%)"), project.settings.defaultEfficiencyPct) {
                    project.settings.defaultEfficiencyPct = it; normalizeCurrentSettings()
                }
                if (project.mode == "standard") {
                    PixCheck(
                        label = t("标准品量列已是 log10 值"),
                        checked = project.settings.quantityIsLog10,
                        onChange = { project.settings.quantityIsLog10 = it; bump() },
                    )
                }
            }

            GelSection(t("质控阈值"), startOpen = true) {
                NumberFieldRow(t("Ct 上限"), project.settings.highCt) {
                    project.settings.highCt = it; normalizeCurrentSettings()
                }
                NumberFieldRow(t("重复孔 SD 上限"), project.settings.sdLimit) {
                    project.settings.sdLimit = it; normalizeCurrentSettings()
                }
                NumberFieldRow(t("离群 |ΔCt| 上限"), project.settings.outlierLimit) {
                    project.settings.outlierLimit = it; normalizeCurrentSettings()
                }
                PixCheck(
                    label = t("自动排除离群孔"),
                    checked = project.settings.excludeOutliers,
                    onChange = { project.settings.excludeOutliers = it; bump() },
                )
                PixCheck(
                    label = t("显示 ΔCt 热图"),
                    checked = project.settings.showHeatmap,
                    onChange = { project.settings.showHeatmap = it; bump() },
                )
            }

            GelSection(t("导出与项目"), startOpen = true) {
                PixButton(t("结果 CSV"), modifier = Modifier.fillMaxWidth(), enabled = hasData, onClick = { exportCsv() })
                PixButton(
                    t("可复现数据包"),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    enabled = hasData && chartData != null && busy.isEmpty(),
                    ok = true,
                    onClick = { exportReproBundle() },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixButton(t("图表 SVG"), modifier = Modifier.weight(1f), enabled = chartData != null, onClick = { exportChartSvg() })
                    PixButton(t("图表 PNG"), modifier = Modifier.weight(1f), enabled = chartData != null, onClick = { exportChartPng() })
                }
                PixButton(
                    t("发送到 Figure Studio"),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    enabled = chartData != null && busy.isEmpty(),
                    onClick = { sendChartToFigure() },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PixButton(t("下载项目 JSON"), modifier = Modifier.weight(1f), onClick = { exportProjectJson() })
                    PixButton(t("导入项目 JSON"), modifier = Modifier.weight(1f), onClick = { projectPicker.launch(arrayOf("*/*")) })
                }
                PixButton(t("清空工作区"), modifier = Modifier.fillMaxWidth().padding(top = 4.dp), danger = true, onClick = { showResetConfirm = true })
                Text(t("数据会自动保存在本机浏览器中"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }

    // ------------------------------------------------------------ workspace

    @Composable
    fun WorkspaceBar() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PixButton(t("ΔΔCt 法"), active = project.mode == "ddct", onClick = { setMode("ddct") })
            PixButton(t("标准曲线"), active = project.mode == "standard", onClick = { setMode("standard") })
            Chip(interp("{n} 个孔", "n" to project.wells.size))
            if (flaggedCount > 0) Chip(interp("{n} 个标记", "n" to flaggedCount), warn = true)
            if (project.fileName.isNotEmpty()) {
                Text(project.fileName, fontSize = 8.sp, maxLines = 1, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (busy.isNotEmpty()) Text(busy, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    fun Banners() {
        if (!mappingReady) {
            Banner(t("样本、靶标和 Ct 列为必选"), "error")
        } else if (project.mode == "ddct") {
            if (ddct.missingReference) {
                Banner(t("请选择在对照样本中可检出的内参基因。"), "warn")
            } else if (ddct.referenceFallback) {
                Banner(interp("未指定内参，暂用 {name}。", "name" to ddct.references.joinToString(", ") { it.label }), "info")
            }
            if (ddct.referenceMissingSamples.isNotEmpty()) {
                val names = ddct.referenceMissingSamples.take(5).joinToString(", ") { it.sample }
                val display = if (ddct.referenceMissingSamples.size > 5) "$names…" else names
                Banner(interp("以下样本缺少参考 Ct：{names}", "names" to display), "warn")
            }
            if (ddct.samples.isEmpty()) {
                Banner(t("请选择对照组或对照样本。"), "warn")
            } else if (ddct.controlFallback) {
                Banner(interp("未指定对照，暂用 {name}。", "name" to ddct.controlLabel), "info")
            }
        } else if (standard.missingStandards) {
            Banner(t("未找到带已知标准品量的标准孔。"), "warn")
        }
    }

    @Composable
    fun RawDataCard() {
        SectionCard(title = t("原始数据与质控")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(interp("{n} 个孔", "n" to project.wells.size), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (flaggedCount > 0) {
                    Text(interp("{n} 个标记", "n" to flaggedCount), fontSize = 8.5.sp, color = Color(0xFFE8892B.toInt()))
                }
                Spacer(Modifier.weight(1f))
                PixButton(t("全部纳入"), onClick = { includeAll() })
                PixButton(t("排除标记孔"), onClick = { excludeFlagged() })
            }
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                // 12 列：行/纳入/孔位/样本/靶标/分组/重复/Ct/Tm/效率/标准品量/质控标记
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Th(t("行"), 26f); Th(t("纳入"), 30f); Th(t("孔位"), 40f); Th(t("样本"), 64f)
                    Th(t("靶标"), 64f); Th(t("分组"), 64f); Th(t("重复"), 32f); Th(t("Ct"), 48f)
                    Th(t("Tm"), 44f); Th(t("效率"), 44f); Th(t("标准品量"), 56f); Th(t("质控标记"), 120f)
                }
                project.wells.forEach { well ->
                    val entry = qc[well.id]
                    val usable = usableWell(well, entry, project.settings)
                    Row(Modifier.fillMaxWidth().alpha(if (usable) 1f else 0.5f)) {
                        Td("${well.row + 2}", 26f, dim = true)
                        Box(Modifier.width(30f.dp)) {
                            Checkbox(
                                checked = well.include,
                                onCheckedChange = { setInclude(well.id, it) },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Td(well.well.ifEmpty { "—" }, 40f)
                        Td(well.sample.ifEmpty { "—" }, 64f)
                        Td(well.target.ifEmpty { "—" }, 64f)
                        Td(well.group.ifEmpty { "—" }, 64f)
                        Td(fmtNumber(well.replicate), 32f)
                        Text(
                            if (well.ct === null) t("未确定") else fmt(well.ct),
                            fontSize = 8.5.sp,
                            color = if (well.ct === null) Color(0xFFD64545.toInt()) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(48f.dp).padding(horizontal = 3.dp, vertical = 3.dp),
                            maxLines = 1,
                        )
                        Td(fmt(well.tm, 1), 44f)
                        Td(if (well.efficiency === null) "—" else fmtEff(well.efficiency), 44f)
                        Td(fmtQty(well.quantity), 56f)
                        Row(
                            Modifier.width(120f.dp).padding(horizontal = 3.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            entry?.flags?.forEach { flag -> FlagChip(flag, well, entry) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ChartCard() {
        SectionCard(title = chartTitle, meta = t(if (project.mode == "standard") "标准曲线" else "ΔΔCt 法")) {
            val bmp = chartBmp
            if (bmp != null) {
                BitmapView(bmp, bmp.width, bmp.height)
            } else {
                EmptyLine(t("暂无可绘制的数据"))
            }
            val heat = heatmapBmp
            if (heat != null) {
                BitmapView(heat, heat.width, heat.height)
            }
        }
    }

    @Composable
    fun FoldTables(analysis: DdctAnalysis) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
            // 样本结果 12 列
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                Th(t("样本"), 72f); Th(t("靶标"), 64f); Th(t("分组"), 64f); Th(t("n"), 26f)
                Th(t("平均 Ct"), 48f); Th(t("Ct SD"), 48f); Th(t("ΔCt"), 48f); Th(t("ΔΔCt"), 48f)
                Th(t("效率 E"), 48f); Th(t("倍数变化"), 60f); Th(t("SD"), 56f); Th(t("SEM"), 56f)
            }
            analysis.sampleResults.forEach { result ->
                Row(
                    Modifier.fillMaxWidth().alpha(if (result.isControl) 0.75f else 1f)
                        .background(if (result.isControl) Color(0x14FFFFFF) else Color.Transparent),
                ) {
                    Td(if (result.isControl) "${result.sample} ${t("对照")}" else result.sample, 72f)
                    Td(result.target, 64f)
                    Td(result.group.ifEmpty { "—" }, 64f)
                    Td("${result.n}", 26f)
                    Td(fmt(result.meanCt), 48f)
                    Td(fmt(result.sdCt), 48f)
                    Td(fmt(result.dCt), 48f)
                    Td(fmt(result.ddCt), 48f)
                    Td(if (project.settings.efficiencyCorrection) fmtEff(result.efficiency) else "—", 48f)
                    Text(fmtFold(result.fold), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60f.dp).padding(horizontal = 3.dp, vertical = 3.dp), maxLines = 1)
                    Td(fmtFold(result.foldSd), 56f)
                    Td(fmtFold(result.foldSem), 56f)
                }
            }
        }
        SectionTitle(t("分组汇总"))
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                Th(t("分组"), 72f); Th(t("靶标"), 64f); Th(t("样本数"), 44f); Th(t("倍数变化"), 60f)
                Th(t("SD"), 56f); Th(t("SEM"), 56f); Th(t("p 值"), 56f)
            }
            analysis.groupResults.forEach { group ->
                Row(Modifier.fillMaxWidth().alpha(if (group.isControl) 0.75f else 1f)) {
                    Td(if (group.isControl) "${group.category} ${t("对照")}" else group.category, 72f)
                    Td(group.target, 64f)
                    Td("${group.nSamples}", 44f)
                    Text(fmtFold(group.meanFold), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60f.dp).padding(horizontal = 3.dp, vertical = 3.dp), maxLines = 1)
                    Td(fmtFold(group.sdFold), 56f)
                    Td(fmtFold(group.semFold), 56f)
                    Td(fmtP(group.pValue), 56f)
                }
            }
        }
        Text(t("p 值为相对对照组的双尾 Welch t 检验（基于 ΔCt）。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        if (analysis.replicates.isNotEmpty()) {
            SectionTitle(t("重复孔明细"))
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Th(t("样本"), 72f); Th(t("靶标"), 64f); Th(t("重复"), 36f); Th(t("孔位"), 44f)
                    Th(t("Ct"), 48f); Th(t("内参 Ct"), 52f); Th(t("ΔCt"), 48f); Th(t("ΔΔCt"), 48f); Th(t("倍数变化"), 60f)
                }
                analysis.replicates.forEach { rep ->
                    Row(Modifier.fillMaxWidth().alpha(if (rep.isControl) 0.75f else 1f)) {
                        Td(rep.sample, 72f); Td(rep.target, 64f); Td(fmtNumber(rep.replicate), 36f)
                        Td(rep.well.ifEmpty { "—" }, 44f); Td(fmt(rep.ct), 48f); Td(fmt(rep.refCt), 52f)
                        Td(fmt(rep.dCt), 48f); Td(fmt(rep.ddCt), 48f); Td(fmtFold(rep.fold), 60f)
                    }
                }
            }
        }
    }

    @Composable
    fun DctMatrix(analysis: DdctAnalysis) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                Th(t("样本"), 88f); Th(t("分组"), 72f); Th(t("内参 Ct"), 56f)
                analysis.targets.forEach { Th(it.label, 56f) }
            }
            analysis.matrix.forEach { row ->
                Row(Modifier.fillMaxWidth().alpha(if (row.isControl) 0.75f else 1f)) {
                    Td(if (row.isControl) "${row.sample} ${t("对照")}" else row.sample, 88f)
                    Td(row.group.ifEmpty { "—" }, 72f)
                    Td(fmt(row.refCt), 56f)
                    analysis.targets.forEach { target -> Td(fmt(row.dCt[target.key]), 56f) }
                }
            }
            Row(Modifier.fillMaxWidth().background(Color(0x14FFFFFF))) {
                Td(t("对照均值"), 88f); Td("", 72f); Td("", 56f)
                analysis.targets.forEach { target -> Td(fmt(analysis.controlMeanDCt[target.key]), 56f) }
            }
        }
    }

    @Composable
    fun DdctMatrix(analysis: DdctAnalysis) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                Th(t("样本"), 88f); Th(t("分组"), 72f)
                analysis.targets.forEach { Th(it.label, 56f) }
            }
            analysis.matrix.forEach { row ->
                Row(Modifier.fillMaxWidth().alpha(if (row.isControl) 0.75f else 1f)) {
                    Td(if (row.isControl) "${row.sample} ${t("对照")}" else row.sample, 88f)
                    Td(row.group.ifEmpty { "—" }, 72f)
                    analysis.targets.forEach { target -> Td(fmt(row.ddCt[target.key]), 56f) }
                }
            }
        }
    }

    @Composable
    fun CurveTables(analysis: StandardAnalysis) {
        if (analysis.missingStandards) {
            EmptyLine(t("未找到带已知标准品量的标准孔。"))
            return
        }
        SectionTitle(t("标准曲线拟合"))
        if (analysis.fits.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Th(t("靶标"), 72f); Th(t("斜率"), 48f); Th(t("截距"), 48f); Th(t("R²"), 48f)
                    Th(t("效率"), 44f); Th(t("标准点数"), 48f); Th(t("质控标记"), 120f)
                }
                analysis.fits.forEach { fit ->
                    Row(Modifier.fillMaxWidth()) {
                        Td(fit.target, 72f); Td(fmt(fit.slope, 3), 48f); Td(fmt(fit.intercept, 3), 48f)
                        Td(fmt(fit.r2, 4), 48f)
                        Td(if (fit.valid) fmtEff(fit.efficiency) else "—", 44f)
                        Td("${fit.n}", 48f)
                        Td(if (fit.reason != CurveReasons.OK) curveReasonText(fit.reason) else "", 120f)
                    }
                }
            }
        } else {
            EmptyLine(t("未找到带已知标准品量的标准孔。"))
        }
        Text(
            t("在标准品量列填写已知拷贝数或浓度；未知样本将按拟合曲线插值。"),
            fontSize = 8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        SectionTitle(t("未知样本插值"))
        if (analysis.samples.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Th(t("样本"), 72f); Th(t("靶标"), 64f); Th(t("分组"), 64f); Th(t("n"), 26f)
                    Th(t("平均 Ct"), 48f); Th(t("log10 数量"), 56f); Th(t("数量"), 52f)
                    Th(t("log10 SD"), 52f); Th(t("log10 SEM"), 52f)
                }
                analysis.samples.forEach { sample ->
                    Row(Modifier.fillMaxWidth()) {
                        Td(sample.sample, 72f); Td(sample.target, 64f); Td(sample.group.ifEmpty { "—" }, 64f)
                        Td("${sample.n}", 26f); Td(fmt(sample.meanCt), 48f)
                        Td(fmt(sample.log10Quantity, 3), 56f); Td(fmtQty(sample.quantity), 52f)
                        Td(fmt(sample.sdLog, 3), 52f); Td(fmt(sample.semLog, 3), 52f)
                    }
                }
            }
        } else {
            EmptyLine(t("尚无可计算结果，请检查内参、对照与列映射。"))
        }
    }

    @Composable
    fun ResultsCard() {
        SectionCard(title = t("分析结果")) {
            Row(Modifier.fillMaxWidth()) {
                TabBtn(t("倍数变化"), activeTab == "fold", Modifier.weight(1f)) { activeTab = "fold" }
                TabBtn(t("ΔCt"), activeTab == "dct", Modifier.weight(1f)) { activeTab = "dct" }
                TabBtn(t("ΔΔCt"), activeTab == "ddct", Modifier.weight(1f)) { activeTab = "ddct" }
                TabBtn(t("标准曲线"), activeTab == "curve", Modifier.weight(1f)) { activeTab = "curve" }
            }
            if (activeTab == "fold") {
                if (ddct.sampleResults.isNotEmpty()) FoldTables(ddct) else EmptyLine(t("尚无可计算结果，请检查内参、对照与列映射。"))
            } else if (activeTab == "dct") {
                DctMatrix(ddct)
            } else if (activeTab == "ddct") {
                DdctMatrix(ddct)
            } else {
                CurveTables(standard)
            }
        }
    }

    @Composable
    fun Workspace() {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkspaceBar()
            if (!hasData) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t("尚无 qPCR 数据"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        t("在左侧粘贴表格、上传 CSV/TSV 文件，或载入示例数据开始分析。"),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixButton(t("载入示例数据"), ok = true, onClick = { loadExample() })
                        PixButton(t("上传文件"), onClick = { dataPicker.launch(arrayOf("*/*")) })
                    }
                }
            } else {
                Banners()
                RawDataCard()
                ChartCard()
                ResultsCard()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(8.dp)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { SidePane() }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) { Workspace() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                SidePane()
                Workspace()
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(t("清空工作区")) },
            text = { Text(t("确定清空当前 qPCR 工作区？")) },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; resetAll() }) { Text(t("清空工作区")) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(t("取消")) }
            },
        )
    }
}

// ------------------------------------------------------------ shared widgets

private fun columnLabel(key: String): String = when (key) {
    "well" -> t("孔位")
    "sample" -> t("样本")
    "target" -> t("靶标 / 基因")
    "ct" -> t("Ct / Cq")
    "group" -> t("分组")
    "replicate" -> t("重复")
    "efficiency" -> t("扩增效率")
    "tm" -> t("熔解温度")
    else -> t("标准品量")
}

private fun flagShort(flag: String): String = when (flag) {
    "missingCt" -> t("无 Ct")
    "highCt" -> t("高 Ct")
    "replicateSd" -> t("SD")
    "outlier" -> t("离群")
    "duplicateWell" -> t("重复孔")
    "missingRef" -> t("REF")
    "invalid" -> t("无效")
    else -> flag
}

/** main flagTitle() 的 1:1 文案（触屏无 tooltip，保留对照语义）。 */
private fun flagTitle(flag: String, settings: QpcrSettings, well: QpcrWell, entry: WellQc?): String = when (flag) {
    "missingCt" -> t("缺失 Ct")
    "highCt" -> interp("Ct 超过上限 {limit}", "limit" to fmt(settings.highCt))
    "replicateSd" -> interp("重复孔 SD = {v}", "v" to fmt(entry?.replicateSd))
    "outlier" -> {
        val replicateMean = entry?.replicateMean
        val ctValue = well.ct
        val deviation = if (ctValue !== null && replicateMean !== null) abs(ctValue - replicateMean) else null
        interp("离群值，偏离 {v}", "v" to fmt(deviation))
    }
    "duplicateWell" -> t("孔位重复")
    "missingRef" -> t("所选参考靶标缺少 Ct")
    "invalid" -> t("缺少样本或靶标")
    else -> flag
}

private fun curveReasonText(reason: String): String = when (reason) {
    "fewPoints" -> t("标准点不足两个")
    "noVariance" -> t("标准品量没有梯度")
    "positiveSlope" -> t("斜率为正，无法计算效率")
    else -> t("未找到带已知标准品量的标准孔。")
}

@Composable
private fun HintLine(text: String) {
    Text(
        text,
        fontSize = 8.sp,
        color = Color(0xFF4CD6FF.toInt()),
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun SectionCard(title: String, meta: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (meta != null) Text(meta, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}

@Composable
private fun TabBtn(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 9.5.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Th(text: String, widthDp: Float) {
    Text(
        text,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(widthDp.dp).padding(horizontal = 3.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

@Composable
private fun Td(text: String, widthDp: Float, dim: Boolean = false) {
    Text(
        text,
        fontSize = 8.5.sp,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(widthDp.dp).padding(horizontal = 3.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

/** 图表位图预览：宽度铺满、高度按位图纵横比换算。 */
@Composable
private fun BitmapView(bmp: Bitmap, srcW: Int, srcH: Int) {
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height((360f * srcH / srcW).dp),
    )
}
