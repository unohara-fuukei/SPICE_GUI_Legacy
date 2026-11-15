package com.spice.nativeapp.tools

/*
 * T4 孔板布局设计器 —— PlateTool.svelte 的 1:1 安卓移植（Compose+MD3）。
 *
 * 与 main 原版的行为对照：
 * - 算法层 tools/plate/（types/random/assign/csv/export/storage/samples/raster）
 *   与 TS 源逐行对应，参考值由 Node 内联原版生成并逐孔断言（PlateTest）。
 * - 状态机（generated / layoutSignature / stale / seedReport / paint 工具 /
 *   paintedDuringDrag / importMode / stale 提示 / autosave failed 标志）照搬。
 * - $effect 600ms autosave → LaunchedEffect+delay 节流写 SharedPreferences；
 *   400ms 项目文档 commit 依赖项目中心，安卓无此基建，跳过（README §11）。
 *
 * 平台差异（README Known gaps §11 披露）：
 * - ToolProjectBar/项目 handoff/capture 不移植（无项目中心）。
 * - 布局：原版 aside 左 366px / stage 右；触屏默认上下堆叠，
 *   宽屏 (>900dp) 改两栏（左 320dp 规则、右板图），语义不变。
 * - 拖画：pointerdown/enter 画笔 → pointerInput 拖动跨孔连续绘制；
 *   contextmenu 擦除 → 专用「擦除」paint 工具（原版也有 empty 工具）。
 * - ESC/Enter/Space 键盘捷径不适用触屏，省略；清除选择有按钮。
 * - 颜色选择器 <input type=color> → 简化 #hex 文本输入（README 披露）。
 * - copyTsv → Android ClipboardManager。
 * - 预览/sendToFigure/导出 PNG 走 PlateRaster 位图孪生（与 SVG 几何
 *   逐值一致；原版经 blob-image 光栅化，安卓无此链路）。
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.I18n.t
import com.spice.nativeapp.core.Repro.ReproBundleInput
import com.spice.nativeapp.core.Repro.ReproFile
import com.spice.nativeapp.core.Repro.buildReproBundle
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixNumberField
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.PixTextField
import com.spice.nativeapp.tools.plate.PlateSvgCell
import com.spice.nativeapp.tools.plate.PlateSvgLegendItem
import com.spice.nativeapp.tools.plate.PlateSvgOptions
import com.spice.nativeapp.tools.plate.assignPlate
import com.spice.nativeapp.tools.plate.assignmentsToMap
import com.spice.nativeapp.tools.plate.baseCellFor
import com.spice.nativeapp.tools.plate.buildPlateRows
import com.spice.nativeapp.tools.plate.buildPlateSvg
import com.spice.nativeapp.tools.plate.clampInt
import com.spice.nativeapp.tools.plate.defaultRules
import com.spice.nativeapp.tools.plate.groupColor
import com.spice.nativeapp.tools.plate.manualAuxAssignment
import com.spice.nativeapp.tools.plate.manualSampleAssignment
import com.spice.nativeapp.tools.plate.MAX_REPLICATES
import com.spice.nativeapp.tools.plate.MAX_SEED
import com.spice.nativeapp.tools.plate.normalizeColor
import com.spice.nativeapp.tools.plate.normalizeProject
import com.spice.nativeapp.tools.plate.nowIso
import com.spice.nativeapp.tools.plate.parseSampleSheet
import com.spice.nativeapp.tools.plate.parseSampleTable
import com.spice.nativeapp.tools.plate.platePngWithDpi
import com.spice.nativeapp.tools.plate.plateBitmapPngBytes
import com.spice.nativeapp.tools.plate.plateFileBaseName as fileBaseName
import com.spice.nativeapp.tools.plate.plateRowsToCsv
import com.spice.nativeapp.tools.plate.plateRowsToTsv
import com.spice.nativeapp.tools.plate.plateSvgBitmap
import com.spice.nativeapp.tools.plate.PlateProjectFile
import com.spice.nativeapp.tools.plate.randomSeedValue
import com.spice.nativeapp.tools.plate.PlateRules
import com.spice.nativeapp.tools.plate.PlateStorage
import com.spice.nativeapp.tools.plate.rowLabel
import com.spice.nativeapp.tools.plate.SampleRow
import com.spice.nativeapp.tools.plate.sampleSheetToCsv
import com.spice.nativeapp.tools.plate.sampleSheetToJson
import com.spice.nativeapp.tools.plate.signatureOf
import com.spice.nativeapp.tools.plate.textColorFor
import com.spice.nativeapp.tools.plate.validateLayout
import com.spice.nativeapp.tools.plate.PLATE_FORMATS
import com.spice.nativeapp.tools.plate.wellCellMap
import com.spice.nativeapp.tools.plate.wellId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

private const val PLATE_CONTROL_COLOR = "#b48cff"
private const val PLATE_BLANK_COLOR = "#7f8ba8"
private const val PLATE_RESERVED_COLOR = "#26304a"
private const val PLATE_EMPTY_COLOR = "#0e1220"

private fun parseColor(hex: String): Color {
    val norm = normalizeColor(hex).ifEmpty { PLATE_EMPTY_COLOR }
    return Color(android.graphics.Color.parseColor(norm))
}

private fun interp(template: String, vararg pairs: Pair<String, Any>): String {
    var out = template
    pairs.forEach { (k, v) -> out = out.replace("{$k}", v.toString()) }
    return out
}

@Composable
fun PlateScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ------------------------------------------------------------- state
    var format by remember { mutableStateOf(96) }
    var title by remember { mutableStateOf("") }
    var samples by remember { mutableStateOf(mutableListOf<SampleRow>()) }
    var rules by remember { mutableStateOf(defaultRules()) }
    var assignments by remember { mutableStateOf(LinkedHashMap<String, com.spice.nativeapp.tools.plate.WellAssignment>()) }
    var generated by remember { mutableStateOf(false) }
    var layoutSignature by remember { mutableStateOf("") }
    var seedReport by remember { mutableStateOf<Int?>(null) }
    var selectedWell by remember { mutableStateOf<String?>(null) }
    var selectedSampleId by remember { mutableStateOf<String?>(null) }
    var paintTool by remember { mutableStateOf("select") }
    var painting by remember { mutableStateOf(false) }
    var colorBy by remember { mutableStateOf("group") }
    var showLabels by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(1.0) }
    var pasteText by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf("replace") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf("") }
    var reproBusy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var hydrated by remember { mutableStateOf(false) }
    var uiRev by remember { mutableStateOf(0) }
    var sampleSeq by remember { mutableStateOf(1) }

    fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    val geometry = com.spice.nativeapp.tools.plate.formatGeometry(format)
    val cells = remember(format) { com.spice.nativeapp.tools.plate.buildWellCells(geometry) }
    val assignedCount = assignments.size
    val emptyCount = geometry.total - assignedCount
    val hasExportable = assignedCount > 0
    val validation = if (generated) {
        validateLayout(format, samples, rules, assignments)
    } else {
        com.spice.nativeapp.tools.plate.ValidationResult(emptyList(), emptyList())
    }
    val issues = validation.issues
    val unplaced = validation.unplaced
    val stale = generated && layoutSignature != signatureOf(format, samples, rules)
    val cellPx = maxOf(8, Math.round(baseCellFor(format) * zoom).toInt())
    val selectedSample = samples.firstOrNull { it.id == selectedSampleId }

    // ------------------------------------------------------------ helpers

    fun displayTitle(): String = title.trim().ifEmpty { t("未命名孔板") }
    fun exportNote(): String = interp(t("当前种子 {seed}"), "seed" to rules.seed)
    fun base(): String = fileBaseName(title)

    fun sampleColorFor(sampleId: String, stored: String): String {
        val index = samples.indexOfFirst { it.id == sampleId }
        if (index >= 0) {
            val own = normalizeColor(samples[index].color)
            if (own.isNotEmpty()) return own
            return com.spice.nativeapp.tools.plate.PLATE_PALETTE[index % com.spice.nativeapp.tools.plate.PLATE_PALETTE.size]
        }
        val fallback = normalizeColor(stored)
        if (fallback.isNotEmpty()) return fallback
        val hash = (com.spice.nativeapp.tools.plate.hashText(sampleId).toLong() and 0xffffffffL).toInt()
        return com.spice.nativeapp.tools.plate.PLATE_PALETTE[hash % com.spice.nativeapp.tools.plate.PLATE_PALETTE.size]
    }

    fun fillFor(a: com.spice.nativeapp.tools.plate.WellAssignment): String = when (a.type) {
        "control" -> PLATE_CONTROL_COLOR
        "blank" -> PLATE_BLANK_COLOR
        "reserved" -> PLATE_RESERVED_COLOR
        else -> if (colorBy == "sample") sampleColorFor(a.sampleId, a.color) else groupColor(a.group)
    }

    fun sampleDisplayName(sampleId: String, fallback: String): String {
        val name = samples.firstOrNull { it.id == sampleId }?.name?.trim()
        return if (!name.isNullOrEmpty()) name else fallback.trim().ifEmpty { sampleId }
    }

    fun cellLabel(a: com.spice.nativeapp.tools.plate.WellAssignment): String = when (a.type) {
        "sample" -> sampleDisplayName(a.sampleId, a.sampleName)
        "control" -> interp(t("对照 {n}"), "n" to a.replicate)
        "blank" -> interp(t("空白 {n}"), "n" to a.replicate)
        else -> ""
    }

    fun compactLabel(a: com.spice.nativeapp.tools.plate.WellAssignment): String {
        val label = cellLabel(a)
        val max = maxOf(2, (cellPx - 6) / 7)
        if (label.length <= max) return label
        return label.take(maxOf(1, max - 1)) + "…"
    }

    fun typeLabelOf(a: com.spice.nativeapp.tools.plate.WellAssignment?): String = when {
        a == null -> t("空孔")
        a.type == "sample" -> t("样本")
        a.type == "control" -> t("对照")
        a.type == "blank" -> t("空白")
        else -> t("预留")
    }

    fun newSampleId(): String {
        var id = ""
        do {
            id = "smp_${System.currentTimeMillis().toString(36)}_${sampleSeq++}"
        } while (samples.any { it.id == id })
        return id
    }

    fun countSampleWells(sampleId: String): Int = assignments.values.count { it.type == "sample" && it.sampleId == sampleId }
    fun countType(type: String): Int = assignments.values.count { it.type == type }

    fun setAssignment(value: com.spice.nativeapp.tools.plate.WellAssignment) {
        assignments = LinkedHashMap(assignments).also { it[value.well] = value }
        uiRev++
    }

    fun removeAssignment(id: String) {
        if (assignments.containsKey(id)) {
            assignments = LinkedHashMap(assignments).also { it.remove(id) }
            uiRev++
        }
    }

    fun applyTool(id: String) {
        val cell = cells.firstOrNull { it.id == id } ?: return
        if (paintTool == "select") return
        if (paintTool == "empty") {
            removeAssignment(id)
            return
        }
        val existing = assignments[id]
        if (paintTool == "sample") {
            val selected = selectedSample
            if (selected == null) {
                toast(t("请先在列表中选择样本，再开始绘制。"))
                paintTool = "select"
                return
            }
            if (existing?.type == "sample" && existing.sampleId == selected.id) return
            val replicate = minOf(MAX_REPLICATES, countSampleWells(selected.id) + 1)
            setAssignment(manualSampleAssignment(geometry, selected, cell.row, cell.col, replicate))
            return
        }
        if (paintTool == "control") {
            if (existing?.type == "control") return
            setAssignment(manualAuxAssignment(geometry, "control", cell.row, cell.col, countType("control") + 1))
            return
        }
        if (paintTool == "blank") {
            if (existing?.type == "blank") return
            setAssignment(manualAuxAssignment(geometry, "blank", cell.row, cell.col, countType("blank") + 1))
        }
    }

    fun buildProjectFile(): PlateProjectFile {
        val list = assignments.values
            .map { it.copy() }
            .sortedWith(compareBy({ it.row }, { it.col }))
            .toMutableList()
        return PlateProjectFile(1, "spice-plate", title, format, samples.map { it.copy() }.toMutableList(), rules, list, colorBy, showLabels, nowIso())
    }

    fun applyProject(project: PlateProjectFile) {
        format = project.format
        title = project.title
        samples = project.samples.map { it.copy() }.toMutableList()
        rules = project.rules.copy()
        assignments = LinkedHashMap<String, com.spice.nativeapp.tools.plate.WellAssignment>().also { m ->
            project.assignments.map { it.copy() }.forEach { m[it.well] = it }
        }
        colorBy = project.colorBy
        showLabels = project.showLabels
        generated = assignments.isNotEmpty()
        layoutSignature = signatureOf(project.format, project.samples, project.rules)
        seedReport = if (generated) project.rules.seed else null
        selectedWell = null
        selectedSampleId = samples.firstOrNull()?.id
        paintTool = "select"
        zoom = 1.0
        sampleSeq = maxOf(sampleSeq, samples.size + 1)
        uiRev++
    }

    fun applySampleDrafts(drafts: List<com.spice.nativeapp.tools.plate.SampleDraft>, skipped: Int, notify: Boolean = true) {
        if (drafts.isEmpty()) {
            toast(t("粘贴的文本中没有找到样本行。"))
            return
        }
        val rows = drafts.map { d -> SampleRow(newSampleId(), d.name, d.group, d.color, d.replicates) }
        samples = if (importMode == "replace") {
            assignments = LinkedHashMap()
            generated = false
            layoutSignature = ""
            seedReport = null
            selectedWell = null
            rows.toMutableList()
        } else {
            (samples + rows).toMutableList()
        }
        if (selectedSampleId == null || samples.none { it.id == selectedSampleId }) {
            selectedSampleId = rows.firstOrNull()?.id ?: samples.firstOrNull()?.id
        }
        uiRev++
        if (!notify) return
        val body = if (skipped > 0) {
            "${interp(t("已导入 {n} 行样本。"), "n" to rows.size)} ${interp(t("跳过 {n} 行没有样本名称的数据。"), "n" to skipped)}"
        } else interp(t("已导入 {n} 行样本。"), "n" to rows.size)
        toast(body)
    }

    fun currentSampleSheet(): com.spice.nativeapp.tools.plate.SampleSheet = com.spice.nativeapp.tools.plate.SampleSheet(
        name = displayTitle(),
        samples = samples.map { s ->
            com.spice.nativeapp.tools.plate.SampleSheetEntry(s.id, s.name, s.group, s.color.ifEmpty { null }, s.replicates ?: rules.replicates, null)
        },
        updatedAt = nowIso(),
    )

    fun exportCells(): List<PlateSvgCell?> {
        val list = ArrayList<PlateSvgCell?>(geometry.total)
        repeat(geometry.total) { list.add(null) }
        for (a in assignments.values) {
            val index = a.row * geometry.cols + a.col
            if (index < 0 || index >= list.size) continue
            val fill = fillFor(a)
            list[index] = PlateSvgCell(fill, cellLabel(a), textColorFor(fill), a.well)
        }
        return list
    }

    fun legendItems(): List<Triple<String, String, Int>> {
        // key, label, count — colors resolved per fill kind.
        data class L(var label: String, var color: String, var count: Int)
        val map = LinkedHashMap<String, L>()
        fun add(key: String, label: String, color: String) {
            val e = map[key]
            if (e != null) e.count++ else map[key] = L(label, color, 1)
        }
        for (a in assignments.values) {
            when (a.type) {
                "sample" -> if (colorBy == "sample") {
                    add("sample:${a.sampleId}", sampleDisplayName(a.sampleId, a.sampleName), sampleColorFor(a.sampleId, a.color))
                } else {
                    val label = a.group.trim().ifEmpty { t("未分组") }
                    add("group:${a.group.trim().lowercase()}", label, groupColor(a.group))
                }
                "control" -> add("control", t("对照"), PLATE_CONTROL_COLOR)
                "blank" -> add("blank", t("空白"), PLATE_BLANK_COLOR)
                else -> add("reserved", t("预留"), PLATE_RESERVED_COLOR)
            }
        }
        val items = map.entries.map { Triple(it.value.label, it.value.color, it.value.count) }
            .sortedBy { it.first }
            .toMutableList()
        if (emptyCount > 0) items.add(Triple(t("空孔"), PLATE_EMPTY_COLOR, emptyCount))
        return items
    }

    fun buildSvg() = buildPlateSvg(
        PlateSvgOptions(
            title = displayTitle(),
            rows = geometry.rows,
            cols = geometry.cols,
            cells = exportCells(),
            legend = legendItems().map { PlateSvgLegendItem(it.first, it.second, it.third) },
            legendTitle = t("图例"),
            note = exportNote(),
        )
    )

    // ------------------------------------------------------------- exports

    fun saveBytes(name: String, bytes: ByteArray, silent: Boolean = false): Boolean {
        return try {
            val dir = File(ctx.filesDir, "plate-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            if (!silent) toast(interp(t("已保存 {name}。"), "name" to "plate-exports/$name"))
            true
        } catch (e: Exception) {
            toast(interp(t("导出失败：{message}"), "message" to (e.message ?: "")), long = true)
            false
        }
    }

    fun saveText(name: String, text: String): Boolean = saveBytes(name, text.toByteArray(Charsets.UTF_8))

    fun ensureExportable(): Boolean {
        if (hasExportable) return true
        toast(t("没有可导出的板图数据。"))
        return false
    }

    fun exportCsv() {
        if (!ensureExportable()) return
        saveText("${base()}-plate-map.csv", plateRowsToCsv(buildPlateRows(format, assignments)))
    }

    fun exportSvg() {
        if (!ensureExportable()) return
        saveText("${base()}-plate-map.svg", buildSvg().svg)
    }

    fun exportPng(dpi: Int) {
        if (!hasExportable || exporting.isNotEmpty()) return
        exporting = t("正在导出图像...")
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val figure = buildSvg()
                    // 平台差异：无 blob-image 光栅化链路，位图孪生同几何渲染（README §11）。
                    val bmp = plateSvgBitmap(
                        PlateSvgOptions(
                            title = displayTitle(), rows = geometry.rows, cols = geometry.cols,
                            cells = exportCells(),
                            legend = legendItems().map { PlateSvgLegendItem(it.first, it.second, it.third) },
                            legendTitle = t("图例"), note = exportNote(),
                        ),
                        dpi / 96f,
                    )
                    val png = platePngWithDpi(plateBitmapPngBytes(bmp), dpi, "${displayTitle()} | ${exportNote()}")
                    saveBytes("${base()}-plate-${dpi}dpi.png", png)
                }
            } catch (e: Exception) {
                toast(interp(t("导出失败：{message}"), "message" to (e.message ?: "")), long = true)
            } finally {
                exporting = ""
            }
        }
    }

    fun sendToFigureNow() {
        if (!hasExportable || exporting.isNotEmpty()) return
        exporting = t("正在导出图像...")
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val bmp = plateSvgBitmap(
                        PlateSvgOptions(
                            title = displayTitle(), rows = geometry.rows, cols = geometry.cols,
                            cells = exportCells(),
                            legend = legendItems().map { PlateSvgLegendItem(it.first, it.second, it.third) },
                            legendTitle = t("图例"), note = exportNote(),
                        ),
                        2f,
                    )
                    // 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（README §11）。
                    val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                    File(dir, "${base()}-plate.png").writeBytes(plateBitmapPngBytes(bmp))
                }
            } catch (e: Exception) {
                toast(t("无法发送到 Figure Studio"), long = true)
            } finally {
                exporting = ""
            }
        }
    }

    fun copyTsv() {
        if (!ensureExportable()) return
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("plate", plateRowsToTsv(buildPlateRows(format, assignments))))
            toast(t("板图已复制为 TSV。"))
        } catch (e: Exception) {
            toast(t("无法复制到剪贴板。"))
        }
    }

    fun saveProjectJson() {
        saveText("${base()}-plate-project.json", com.spice.nativeapp.tools.plate.serializePlateProject(buildProjectFile()))
    }

    fun exportSamplesJson() {
        if (samples.isEmpty()) {
            toast(t("导出样本表前请至少添加一个样本。"))
            return
        }
        saveText("${base()}-samples.samples.json", sampleSheetToJson(currentSampleSheet()))
    }

    fun exportSamplesCsv() {
        if (samples.isEmpty()) {
            toast(t("导出样本表前请至少添加一个样本。"))
            return
        }
        saveText("${base()}-samples.csv", sampleSheetToCsv(currentSampleSheet()))
    }

    fun exportQpcrMap() {
        if (!ensureExportable()) return
        val rows = buildPlateRows(format, assignments).filter { it.type != "empty" && it.type != "reserved" }
        saveText("${base()}-plate-map-qpcr.csv", plateRowsToCsv(rows))
    }

    fun exportReproBundle() {
        if (!hasExportable || reproBusy) return
        reproBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val figure = buildSvg()
                    val bmp = plateSvgBitmap(
                        PlateSvgOptions(
                            title = displayTitle(), rows = geometry.rows, cols = geometry.cols,
                            cells = exportCells(),
                            legend = legendItems().map { PlateSvgLegendItem(it.first, it.second, it.third) },
                            legendTitle = t("图例"), note = exportNote(),
                        ),
                        300f / 96f,
                    )
                    val png = platePngWithDpi(plateBitmapPngBytes(bmp), 300, "${displayTitle()} | ${exportNote()}")
                    val rows = buildPlateRows(format, assignments)
                    val params = JSONObject().apply {
                        put("format", format)
                        put("rows", geometry.rows)
                        put("cols", geometry.cols)
                        put("wells", geometry.total)
                        put("assignedWells", assignedCount)
                        put("samples", org.json.JSONArray().apply {
                            samples.forEach { s ->
                                put(JSONObject().apply {
                                    put("id", s.id); put("name", s.name); put("group", s.group)
                                    put("color", s.color)
                                    put("replicates", s.replicates ?: JSONObject.NULL)
                                })
                            }
                        })
                        put("rules", rules.toJson())
                        put("colorBy", colorBy)
                        put("showLabels", showLabels)
                    }
                    val result = buildReproBundle(
                        ctx,
                        ReproBundleInput(
                            tool = "Plate layout",
                            title = displayTitle(),
                            files = listOf(
                                ReproFile("${base()}-plate-map.svg", "image/svg+xml", figure.svg),
                                ReproFile("${base()}-plate-300dpi.png", "image/png", png),
                                ReproFile("${base()}-plate-map.csv", "text/csv", plateRowsToCsv(rows)),
                                ReproFile("${base()}-samples.samples.json", "application/json", sampleSheetToJson(currentSampleSheet())),
                            ),
                            parameters = params,
                            methods = "Plate layouts were generated with SPICE GUI using a deterministic seed: the same sample list, rule set and seed reproduce the same well assignments. Replicate counts, control and blank wells, edge handling, group layout and randomisation were applied in the order recorded in parameters.json. The plate map is exported as vector SVG, as a 300 dpi PNG with physical resolution metadata, and as well,sample,group,type,replicate CSV.",
                            project = buildProjectFile().toJson(),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        toast(interp(t("已保存可复现数据包 {name}。"), "name" to result.fileName))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(interp(t("可复现数据包失败：{message}"), "message" to (e.message ?: "")), long = true)
                }
            } finally {
                withContext(Dispatchers.Main) { reproBusy = false }
            }
        }
    }

    // ------------------------------------------------------------- actions

    fun generateLayout() {
        val seed = rules.seed
        if (seed < 0 || seed > MAX_SEED) {
            toast(t("种子必须是 0 到 2147483647 之间的整数。"))
            return
        }
        if (samples.isEmpty()) {
            toast(t("生成布局前请至少添加一个样本。"))
            return
        }
        val result = assignPlate(format, samples, rules)
        assignments = LinkedHashMap<String, com.spice.nativeapp.tools.plate.WellAssignment>().also { m -> result.assignments.forEach { (k, v) -> m[k] = v } }
        generated = true
        layoutSignature = signatureOf(format, samples, rules)
        seedReport = result.seed
        selectedWell = null
        val missing = result.unplaced.size
        val body = if (missing > 0) {
            "${interp(t("已使用种子 {seed} 生成布局。"), "seed" to result.seed)} ${interp(t("{n} 个未放置"), "n" to missing)}"
        } else interp(t("已使用种子 {seed} 生成布局。"), "seed" to result.seed)
        toast(body, long = missing > 0)
        uiRev++
    }

    fun changeFormat(next: Int) {
        if (next == format) return
        val hadLayout = generated || assignedCount > 0
        format = next
        assignments = LinkedHashMap()
        generated = false
        layoutSignature = ""
        seedReport = null
        selectedWell = null
        showLabels = next != 384
        zoom = 1.0
        if (hadLayout) toast(t("孔板规格已更改。请重新生成布局。"))
    }

    fun updateSample(id: String, patch: (SampleRow) -> SampleRow) {
        val idx = samples.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = patch(samples[idx])
        samples = samples.toMutableList().also { it[idx] = updated }
        var changed = false
        val next = LinkedHashMap(assignments)
        for ((key, a) in next) {
            if (a.type != "sample" || a.sampleId != id) continue
            next[key] = a.copy(sampleName = updated.name, group = updated.group, color = updated.color)
            changed = true
        }
        if (changed) assignments = next
        uiRev++
    }

    fun removeSample(id: String) {
        samples = samples.filter { it.id != id }.toMutableList()
        var changed = false
        val next = LinkedHashMap(assignments)
        for ((key, a) in next) {
            if (a.sampleId == id) {
                next.remove(key)
                changed = true
            }
        }
        if (changed) assignments = next
        if (selectedSampleId == id) selectedSampleId = samples.firstOrNull()?.id
        uiRev++
    }

    fun addSample() {
        var counter = samples.size + 1
        var name = interp(t("样本 {n}"), "n" to counter)
        while (samples.any { it.name == name }) {
            counter++
            name = interp(t("样本 {n}"), "n" to counter)
        }
        val sample = SampleRow(newSampleId(), name, "", "", null)
        samples = (samples + sample).toMutableList()
        selectedSampleId = sample.id
        uiRev++
    }

    fun confirmClear() {
        assignments = LinkedHashMap()
        generated = false
        layoutSignature = ""
        seedReport = null
        selectedWell = null
        showClearConfirm = false
        // Flush immediately so a cleared plate is not restored from a stale copy.
        saveFailed = !PlateStorage.saveSnapshot(ctx, buildProjectFile())
        uiRev++
    }

    fun setZoom(next: Double) {
        zoom = (Math.round(next * 100) / 100.0).coerceIn(0.5, 3.0)
    }

    // --------------------------------------------------------- lifecycle

    // onMount: snapshot restore (project handoff / ToolProjectBar not ported).
    LaunchedEffect(Unit) {
        val snapshot = PlateStorage.loadSnapshot(ctx)
        val project = snapshot?.project
        if (project != null) {
            val hasData = project.samples.isNotEmpty() || project.assignments.isNotEmpty() || project.title.trim().isNotEmpty()
            if (hasData) {
                applyProject(project)
                toast(t("已恢复之前的孔板布局。"))
                val dropped = snapshot.duplicateWells + snapshot.droppedAssignments
                if (dropped > 0) toast(interp(t("导入时丢弃了 {n} 个无效分配。"), "n" to dropped))
            }
        }
        hydrated = true
    }

    // $effect 600ms autosave → SharedPreferences (plateAutosaveFailed kept).
    LaunchedEffect(format, title, samples, rules, assignments, colorBy, showLabels, generated) {
        if (!hydrated) return@LaunchedEffect
        delay(600)
        saveFailed = !PlateStorage.saveSnapshot(ctx, buildProjectFile())
    }

    // --------------------------------------------------------- file pickers

    fun readUriText(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    fun uriDisplayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = readUriText(uri)
            val parsed = parseSampleTable(text)
            applySampleDrafts(parsed.rows, parsed.skipped)
        } catch (e: Exception) {
            toast(t("无法读取所选文件。"))
        }
    }

    val sampleSheetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val sheet = parseSampleSheet(readUriText(uri))
            if (sheet == null) {
                toast(t("所选文件不是有效的 SPICE_SAMPLES 样本表。"))
                return@rememberLauncherForActivityResult
            }
            if (sheet.samples.isEmpty()) {
                toast(t("所选文件不是有效的 SPICE_SAMPLES 样本表。"))
                return@rememberLauncherForActivityResult
            }
            applySampleDrafts(
                sheet.samples.map { s ->
                    com.spice.nativeapp.tools.plate.SampleDraft(s.name, s.group, s.color ?: "", s.replicates)
                },
                0,
                notify = false,
            )
            toast(interp(t("已从样本表导入 {n} 个样本。"), "n" to sheet.samples.size))
        } catch (e: Exception) {
            toast(t("所选文件不是有效的 SPICE_SAMPLES 样本表。"))
        }
    }

    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val parsed = normalizeProject(JSONObject(readUriText(uri)))
            val project = parsed.project
            if (project == null) {
                toast(t("所选文件不是有效的孔板项目。"))
                return@rememberLauncherForActivityResult
            }
            applyProject(project)
            toast(interp(t("已从 {name} 加载项目。"), "name" to uriDisplayName(uri)))
            val dropped = parsed.duplicateWells + parsed.droppedAssignments
            if (dropped > 0) toast(interp(t("导入时丢弃了 {n} 个无效分配。"), "n" to dropped))
        } catch (e: Exception) {
            toast(t("无法加载项目文件。"))
        }
    }

    // ------------------------------------------------------------- layout

    val rulesPane: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ---- 样本
            GelSection(title = t("样本")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (samples.isEmpty()) {
                        Text(t("还没有样本。可粘贴表格、导入 CSV 文件或手动添加行。"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(t("列：样本名称、组或条件、可选颜色、可选重复数。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("在此粘贴 CSV 或 TSV 行"), fontSize = 8.sp)
                    PixTextField(value = pasteText, placeholder = "", onChange = { pasteText = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("应用粘贴的行"), accent = true, onClick = {
                            if (pasteText.trim().isEmpty()) {
                                toast(t("粘贴的文本中没有找到样本行。"))
                            } else {
                                val parsed = parseSampleTable(pasteText)
                                applySampleDrafts(parsed.rows, parsed.skipped)
                                pasteText = ""
                            }
                        })
                        PixButton(label = t("导入 CSV 或 TSV"), onClick = { samplePicker.launch(arrayOf("text/*", "application/octet-stream")) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("导入方式"), fontSize = 9.sp)
                        PixSelect(
                            options = listOf("replace" to t("替换列表"), "append" to t("追加行")),
                            value = importMode,
                            onChange = { importMode = it },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("添加样本"), onClick = { addSample() })
                        PixButton(label = t("导入 .samples.json"), onClick = { sampleSheetPicker.launch(arrayOf("*/*")) })
                    }
                    samples.forEach { sample ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                PixTextField(
                                    value = sample.name,
                                    placeholder = t("样本名称"),
                                    onChange = { v -> if (v != sample.name) updateSample(sample.id) { it.copy(name = v) } },
                                )
                                PixTextField(
                                    value = sample.group,
                                    placeholder = t("组或条件"),
                                    onChange = { v -> if (v != sample.group) updateSample(sample.id) { it.copy(group = v) } },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(t("颜色"), fontSize = 8.sp)
                                    PixTextField(
                                        value = sample.color,
                                        placeholder = "#rrggbb",
                                        onChange = { v -> if (v != sample.color) updateSample(sample.id) { it.copy(color = v) } },
                                    )
                                    PixNumberField(
                                        value = sample.replicates?.toString() ?: "",
                                        onCommit = { v ->
                                            val n = v.trim().toDoubleOrNull()
                                            val rep = if (v.trim().isEmpty() || n == null) null else clampInt(n, 1, MAX_REPLICATES, rules.replicates)
                                            if (rep != sample.replicates) updateSample(sample.id) { it.copy(replicates = rep) }
                                        },
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                PixButton(label = t("绘制样本"), active = selectedSampleId == sample.id, onClick = {
                                    selectedSampleId = sample.id
                                    paintTool = "sample"
                                })
                                PixButton(label = t("移除样本"), danger = true, onClick = { removeSample(sample.id) })
                            }
                        }
                    }
                }
            }
            // ---- 规则
            GelSection(title = t("规则")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("每个样本的重复数"), fontSize = 9.sp)
                    PixNumberField(value = rules.replicates.toString(), onCommit = { v ->
                        val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                        rules = rules.copy(replicates = clampInt(n, 1, MAX_REPLICATES, rules.replicates))
                    })
                    Text(t("适用于未单独设置重复数的样本。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t("对照孔"), fontSize = 9.sp)
                            PixNumberField(value = rules.controls.toString(), onCommit = { v ->
                                val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                                rules = rules.copy(controls = clampInt(n, 0, geometry.total, rules.controls))
                            })
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t("空白孔"), fontSize = 9.sp)
                            PixNumberField(value = rules.blanks.toString(), onCommit = { v ->
                                val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                                rules = rules.copy(blanks = clampInt(n, 0, geometry.total, rules.blanks))
                            })
                        }
                    }
                    PixCheck(label = t("预留边缘孔"), checked = rules.reserveEdge, onChange = { v -> rules = rules.copy(reserveEdge = v) })
                    Text(t("保持外圈为空，并优先用于对照和空白。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixCheck(label = t("样本避开边缘"), checked = rules.avoidEdge, onChange = { v -> rules = rules.copy(avoidEdge = v) })
                    Text(t("容量允许时，将样本放在内部孔位。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixCheck(label = t("同组相邻"), checked = rules.keepTogether, onChange = { v -> rules = rules.copy(keepTogether = v) })
                    Text(t("将每个样本放在一个相邻区块中。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("分组布局"), fontSize = 9.sp)
                    PixSelect(
                        options = listOf(
                            "auto" to t("自动"), "row" to t("连续行"), "column" to t("连续列"), "block" to t("矩形区块"),
                        ),
                        value = rules.groupLayout,
                        onChange = { v -> if (v in listOf("auto", "row", "column", "block")) rules = rules.copy(groupLayout = v) },
                    )
                    if (rules.groupLayout == "block") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(t("每组列数"), fontSize = 8.sp)
                                PixNumberField(value = rules.groupColumns.toString(), onCommit = { v ->
                                    val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                                    rules = rules.copy(groupColumns = clampInt(n, 0, geometry.cols, 0))
                                })
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(t("每组行数"), fontSize = 8.sp)
                                PixNumberField(value = rules.groupRows.toString(), onCommit = { v ->
                                    val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                                    rules = rules.copy(groupRows = clampInt(n, 0, geometry.rows, 0))
                                })
                            }
                        }
                        Text(t("填 0 让工具自动选择紧凑区块。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PixCheck(label = t("随机顺序"), checked = rules.randomize, onChange = { v -> rules = rules.copy(randomize = v) })
                    Text(t("用种子打乱样本和空闲孔位的顺序。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("随机种子"), fontSize = 9.sp)
                    PixNumberField(value = rules.seed.toString(), onCommit = { v ->
                        if (v.trim().isEmpty()) return@PixNumberField
                        val n = v.trim().toDoubleOrNull() ?: return@PixNumberField
                        rules = rules.copy(seed = clampInt(n, 0, MAX_SEED, rules.seed))
                    })
                    Text(t("整数种子。相同种子和规则始终生成相同布局。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("新种子"), onClick = {
                            rules = rules.copy(seed = randomSeedValue())
                            toast(interp(t("当前种子 {seed}"), "seed" to rules.seed))
                        })
                        PixButton(label = t("生成布局"), accent = true, onClick = { generateLayout() })
                    }
                    if (seedReport != null) {
                        Text(
                            interp(t("已使用种子 {seed} 生成布局。"), "seed" to seedReport!!),
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // ---- 规则检查
            GelSection(title = t("规则检查")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val statusText = if (issues.isEmpty()) t("所有规则均已满足。")
                    else interp(t("{n} 条规则警告"), "n" to issues.size)
                    Text(
                        statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (issues.any { it.severity == "error" }) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (stale) {
                        Text(t("上次生成后样本或规则已更改。请重新生成以应用。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (issues.isEmpty()) {
                        Text(t("未发现规则冲突。"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        issues.forEach { issue ->
                            val text = when (issue.key) {
                                "plateIssueCapacity" -> interp(t("容量不足：需要 {needed} 个孔位，但只有 {available} 个可用。"), "needed" to (issue.params["needed"] ?: 0), "available" to (issue.params["available"] ?: 0))
                                "plateIssueReplicatesMissing" -> interp(t("{name} 仅放置了 {placed}/{requested} 个重复。"), "name" to (issue.params["name"] ?: ""), "placed" to (issue.params["placed"] ?: 0), "requested" to (issue.params["requested"] ?: 0))
                                "plateIssueGroupSplit" -> interp(t("{name} 的重复孔不在同一相邻组中。"), "name" to (issue.params["name"] ?: ""))
                                "plateIssueEdgeUsed" -> interp(t("边缘避让启用时，{name} 使用了边缘孔。"), "name" to (issue.params["name"] ?: ""))
                                "plateIssueDuplicate" -> interp(t("在孔位 {well} 检测到重复分配。"), "well" to (issue.params["well"] ?: ""))
                                "plateIssueControlsMissing" -> interp(t("仅放置了 {placed}/{requested} 个对照孔。"), "placed" to (issue.params["placed"] ?: 0), "requested" to (issue.params["requested"] ?: 0))
                                "plateIssueBlanksMissing" -> interp(t("仅放置了 {placed}/{requested} 个空白孔。"), "placed" to (issue.params["placed"] ?: 0), "requested" to (issue.params["requested"] ?: 0))
                                "plateIssueNoSamples" -> t("生成布局前请至少添加一个样本。")
                                "plateSampleNameRequired" -> t("每个样本都需要名称。")
                                "plateDuplicateName" -> interp(t("样本名称重复：{name}"), "name" to (issue.params["name"] ?: ""))
                                else -> issue.key
                            }
                            Text(
                                text,
                                fontSize = 8.5.sp,
                                color = if (issue.severity == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(t("未放置"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    if (unplaced.isEmpty()) {
                        Text(t("所有样本均已完整放置。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        unplaced.forEach { u ->
                            Text(
                                interp(
                                    t("{name}：已放置 {placed}/{requested} 个重复，缺少 {missing} 个。"),
                                    "name" to u.name, "placed" to u.placed, "requested" to u.requested, "missing" to u.missing,
                                ),
                                fontSize = 8.5.sp,
                            )
                        }
                    }
                }
            }
            // ---- 导出
            GelSection(title = t("导出")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("导出使用当前板图和图例。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val busy = exporting.isNotEmpty() || reproBusy
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("板图 CSV"), enabled = hasExportable, onClick = { exportCsv() })
                        PixButton(label = t("SVG 图"), enabled = hasExportable, onClick = { exportSvg() })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = "${t("PNG 图")} ${t("300 dpi")}", enabled = hasExportable && !busy, onClick = { exportPng(300) })
                        PixButton(label = "${t("PNG 图")} ${t("600 dpi")}", enabled = hasExportable && !busy, onClick = { exportPng(600) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("发送到 Figure Studio"), enabled = hasExportable && !busy, onClick = { sendToFigureNow() })
                        PixButton(label = t("复制 TSV"), enabled = hasExportable, onClick = { copyTsv() })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("保存项目 JSON"), onClick = { saveProjectJson() })
                        PixButton(label = t("加载项目 JSON"), onClick = { projectPicker.launch(arrayOf("*/*")) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = "SPICE_SAMPLES JSON", onClick = { exportSamplesJson() })
                        PixButton(label = "SPICE_SAMPLES CSV", onClick = { exportSamplesCsv() })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(label = t("导出 qPCR 孔板图"), enabled = hasExportable, onClick = { exportQpcrMap() })
                        PixButton(
                            label = if (reproBusy) t("正在构建可复现数据包...") else t("可复现数据包"),
                            enabled = hasExportable && !busy,
                            onClick = { exportReproBundle() },
                        )
                    }
                    // ---- autosave
                    Text(t("自动保存"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    if (saveFailed) {
                        Text(t("自动保存失败。浏览器存储可能已满。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(interp(t("当前布局保存在此浏览器中。"), ), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    val stagePane: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // stage-bar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("板图"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    interp(t("已分配 {filled} / {total} 孔"), "filled" to assignedCount, "total" to geometry.total),
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(t("着色依据"), fontSize = 8.sp)
                PixSelect(
                    options = listOf("group" to t("组"), "sample" to t("样本")),
                    value = colorBy,
                    onChange = { colorBy = if (it == "sample") "sample" else "group" },
                )
                PixCheck(label = t("显示孔位标签"), checked = showLabels, onChange = { showLabels = it })
                PixButton(label = "−", onClick = { setZoom(zoom - 0.25) })
                Text("${(zoom * 100).toInt()}%", fontSize = 9.sp)
                PixButton(label = "+", onClick = { setZoom(zoom + 0.25) })
                PixButton(label = t("适应"), onClick = { setZoom(3.0) })
            }
            // paint-bar
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tools = listOf(
                    "select" to t("查看"),
                    "sample" to (selectedSample?.let { interp(t("正在绘制 {name}"), "name" to it.name) } ?: t("绘制样本")),
                    "control" to t("绘制对照"),
                    "blank" to t("绘制空白"),
                    "empty" to t("擦除"),
                )
                tools.forEach { (tool, label) ->
                    PixButton(
                        label = label,
                        active = paintTool == tool,
                        onClick = {
                            if (tool == "sample" && selectedSample == null) {
                                if (samples.isNotEmpty()) {
                                    selectedSampleId = samples[0].id
                                } else {
                                    toast(t("请先在列表中选择样本，再开始绘制。"))
                                    return@PixButton
                                }
                            }
                            paintTool = tool
                        },
                    )
                }
                Text(t("选择工具后，点击或拖动孔位。"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // plate grid
            if (assignedCount == 0 && !generated) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t("构建板图"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(t("导入样本、设置规则并生成确定性的布局。"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("生成布局后即可查看板图。"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grid = @Composable {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .pointerInput(paintTool, uiRev) {
                                detectDragGestures(
                                    onDragStart = { },
                                    onDrag = { change, _ ->
                                        // 拖动跨孔绘制由各 well 的 clickable/pointerInput 完成；
                                        // 此处仅消费手势避免整版滚动干扰绘制。
                                        change.consume()
                                    },
                                )
                            },
                    ) {
                        // 列号行
                        Row {
                            Spacer(Modifier.width(20.dp))
                            (0 until geometry.cols).forEach { c ->
                                Box(Modifier.width(cellPx.dp), contentAlignment = Alignment.Center) {
                                    Text("${c + 1}", fontSize = if (cellPx >= 18) 9.sp else 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        (0 until geometry.rows).forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                    if (cellPx >= 18) Text(rowLabel(r), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                (0 until geometry.cols).forEach { c ->
                                    val id = wellId(r, c)
                                    val a = assignments[id]
                                    val fill = if (a == null) PLATE_EMPTY_COLOR else fillFor(a)
                                    Box(
                                        Modifier
                                            .size(cellPx.dp)
                                            .padding(1.dp)
                                            .background(
                                                parseColor(fill),
                                                RoundedCornerShape(2.dp),
                                            )
                                            .border(
                                                1.dp,
                                                if (selectedWell == id) MaterialTheme.colorScheme.primary
                                                else Color(0xFF121624),
                                                RoundedCornerShape(2.dp),
                                            )
                                            .clickable {
                                                // startPaint 语义：左键按下即选中并应用工具。
                                                selectedWell = id
                                                applyTool(id)
                                            }
                                            .pointerInput(paintTool, uiRev, cellPx) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        painting = true
                                                        selectedWell = id
                                                        applyTool(id)
                                                    },
                                                    onDragEnd = { painting = false },
                                                    onDragCancel = { painting = false },
                                                    onDrag = { change, _ -> change.consume() },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (a != null && cellPx >= 30 && showLabels) {
                                            Text(
                                                compactLabel(a),
                                                fontSize = (cellPx * 0.22f).coerceAtLeast(7f).sp,
                                                color = parseColor(textColorFor(fill)),
                                                maxLines = 1,
                                                overflow = TextOverflow.Clip,
                                            )
                                        } else if (a != null && cellPx >= 18 && showLabels) {
                                            Text(
                                                id,
                                                fontSize = (cellPx * 0.2f).coerceAtLeast(6f).sp,
                                                color = parseColor(textColorFor(fill)).copy(alpha = 0.8f),
                                                maxLines = 1,
                                            )
                                        } else if (cellPx >= 18) {
                                            Text(id, fontSize = 6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                grid()
                // legend
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(t("图例"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    legendItems().forEach { (label, color, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(9.dp).background(parseColor(color), RoundedCornerShape(1.dp))
                                    .border(1.dp, Color(0xFF2A3450), RoundedCornerShape(1.dp)),
                            )
                            Text("${label}（${count}）", fontSize = 8.5.sp)
                        }
                    }
                }
                // wellbar
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(t("选中的孔"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    val selectedAssignment = selectedWell?.let { assignments[it] }
                    val selectedCell = selectedWell?.let { w -> cells.firstOrNull { it.id == w } }
                    if (selectedWell == null) {
                        Text(t("点击孔位查看内容。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${t("孔位")} ${selectedWell}", fontSize = 8.5.sp)
                        Text("${t("类型")}：${typeLabelOf(selectedAssignment)}", fontSize = 8.5.sp)
                        if (selectedAssignment != null) {
                            Text(
                                "${t("内容")}：${
                                    if (selectedAssignment.type == "sample") sampleDisplayName(selectedAssignment.sampleId, selectedAssignment.sampleName)
                                    else cellLabel(selectedAssignment)
                                }",
                                fontSize = 8.5.sp,
                            )
                            if (selectedAssignment.group.trim().isNotEmpty()) {
                                Text("${t("组")}：${selectedAssignment.group.trim()}", fontSize = 8.5.sp)
                            }
                            if (selectedAssignment.replicate > 0) {
                                Text("${t("重复")}：${selectedAssignment.replicate}", fontSize = 8.5.sp)
                            }
                            if (selectedAssignment.manual) {
                                Text(t("手动编辑"), fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (selectedCell?.edge == true) {
                            Text(t("边缘孔"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(label = t("清空孔位"), enabled = selectedAssignment != null, onClick = { removeAssignment(selectedWell!!) })
                            PixButton(label = t("清除选择"), onClick = { selectedWell = null; painting = false })
                        }
                    }
                }
            }
        }
    }

    // 清空确认 modal
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(t("清空板图")) },
            text = { Text(t("要移除当前孔板中的所有孔位分配吗？")) },
            confirmButton = {
                TextButton(onClick = { confirmClear() }) { Text(t("确认")) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(t("取消")) }
            },
        )
    }

    // ------------------------------------------------------------- shell

    androidx.compose.ui.platform.LocalConfiguration.current.let { }
    val isWide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 900
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // toolbar
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("孔板布局"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(t("孔板规格"), fontSize = 8.sp)
            PixSelect(
                options = PLATE_FORMATS.map { it.toString() to interp(t("{n} 孔"), "n" to it) },
                value = format.toString(),
                onChange = { v -> v.toIntOrNull()?.let { if (com.spice.nativeapp.tools.plate.isPlateFormat(it)) changeFormat(it) } },
            )
            Text(t("板图标题"), fontSize = 8.sp)
            Box(Modifier.weight(1f)) {
                PixTextField(value = title, placeholder = t("输入板图标题"), onChange = { title = it })
            }
            Text(
                interp(t("{n} 个样本"), "n" to samples.size),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                interp(t("已分配 {filled} / {total} 孔"), "filled" to assignedCount, "total" to geometry.total),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PixButton(label = t("生成布局"), accent = true, enabled = samples.isNotEmpty(), onClick = { generateLayout() })
            PixButton(label = t("重新随机"), enabled = samples.isNotEmpty(), onClick = { generateLayout() })
            PixButton(label = t("清空孔板"), danger = true, enabled = assignedCount > 0, onClick = { showClearConfirm = true })
        }
        if (isWide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(320.dp)) { rulesPane() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { stagePane() }
            }
        } else {
            stagePane()
            rulesPane()
        }
    }
}

