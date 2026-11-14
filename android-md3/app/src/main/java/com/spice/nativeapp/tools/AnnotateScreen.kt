package com.spice.nativeapp.tools

/*
 * T5 图像标注工具 —— AnnotateTool.svelte（3702 行）的 1:1 安卓移植
 * （Compose+MD3，零 WebView）。
 *
 * 与 main 原版的行为对照：
 * - 算法层 tools/annotate/（types/layout/decode/render/profile/storage/
 *   projectDoc）与 TS 源逐行对应；UI 状态机（tool/selectedPanelId/
 *   selectedAnnId/zoomFactor/drag 五态/undoStack·redoStack 60 条/
 *   autosave 900ms/profileSeries 异步采样）照搬。
 * - $state 可变对象 → Kotlin 不可变 data class + 替换式更新
 *   （withCommit / applyNoCommit）；历史栈直接持旧引用（对象永不就地
 *   修改，等价原版 cloneProject 深拷贝）。
 * - $effect 画布重绘 → Canvas 读 state 自动重绘（renderScale 含
 *   devicePixelRatio、8192 上限语义用 density 近似）。
 * - previewArt 120ms debounce + maxDimension 1400 → LaunchedEffect
 *   开头 delay(120)；profile 采样 120ms debounce + maxDimension 1600。
 *
 * 平台差异（README §12 披露）：
 * - ToolProjectBar/项目 handoff/capture 不移植（无项目中心，同 T4）。
 * - 布局：原版三栏（238px 列表 / stage / 332px 检查器）；触屏默认上下
 *   堆叠，宽屏 (>1000dp) 三栏，语义不变。
 * - 拖放导入 → 系统 picker（OpenMultipleDocuments）；Shift 角度约束、
 *   hover 十字光标、键盘捷径（Ctrl+Z/Y/D、Delete、方向键）不适用触屏。
 * - 颜色 <input type=color> → #hex 文本输入（同 T4 先例）。
 * - sendToFigure 无跨工具实时桥，PNG 落盘 files/figures/（同 T3/T4）。
 * - 导出落盘 filesDir/annotate-exports/，Toast 反馈。
 */

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Repro.ReproBundleInput
import com.spice.nativeapp.core.Repro.ReproFile
import com.spice.nativeapp.core.Repro.buildReproBundle
import com.spice.nativeapp.tools.annotate.Annotation
import com.spice.nativeapp.tools.annotate.AnnotationMeta
import com.spice.nativeapp.tools.annotate.ArrowAnnotation
import com.spice.nativeapp.tools.annotate.Box
import com.spice.nativeapp.tools.annotate.Calibration
import com.spice.nativeapp.tools.annotate.ChannelSetting
import com.spice.nativeapp.tools.annotate.DraftShape
import com.spice.nativeapp.tools.annotate.EllipseAnnotation
import com.spice.nativeapp.tools.annotate.FigureGeometry
import com.spice.nativeapp.tools.annotate.Panel
import com.spice.nativeapp.tools.annotate.PanelArt
import com.spice.nativeapp.tools.annotate.PanelBarAnnotation
import com.spice.nativeapp.tools.annotate.PROFILE_DEFAULT_SAMPLES
import com.spice.nativeapp.tools.annotate.PROFILE_MAX_SAMPLES
import com.spice.nativeapp.tools.annotate.PROFILE_MIN_SAMPLES
import com.spice.nativeapp.tools.annotate.PX_PER_MM
import com.spice.nativeapp.tools.annotate.ProfileAnnotation
import com.spice.nativeapp.tools.annotate.ProfileSeries
import com.spice.nativeapp.tools.annotate.RectAnnotation
import com.spice.nativeapp.tools.annotate.ScaleBarAnnotation
import com.spice.nativeapp.tools.annotate.SelectionInfo
import com.spice.nativeapp.tools.annotate.TextAnnotation
import com.spice.nativeapp.tools.annotate.UNIT_MM
import com.spice.nativeapp.tools.annotate.UNIT_PX
import com.spice.nativeapp.tools.annotate.annotateDocName
import com.spice.nativeapp.tools.annotate.annotateMethods
import com.spice.nativeapp.tools.annotate.annotateParameters
import com.spice.nativeapp.tools.annotate.annotateProfileAnnotations
import com.spice.nativeapp.tools.annotate.barLayout
import com.spice.nativeapp.tools.annotate.buildAnnotationMeta
import com.spice.nativeapp.tools.annotate.buildArtwork
import com.spice.nativeapp.tools.annotate.buildFigureGeometry
import com.spice.nativeapp.tools.annotate.clearImageCache
import com.spice.nativeapp.tools.annotate.clearSnapshot
import com.spice.nativeapp.tools.annotate.clamp
import com.spice.nativeapp.tools.annotate.collectProfiles
import com.spice.nativeapp.tools.annotate.computeProfile
import com.spice.nativeapp.tools.annotate.convertCalibration
import com.spice.nativeapp.tools.annotate.convertValue
import com.spice.nativeapp.tools.annotate.createDefaultProject
import com.spice.nativeapp.tools.annotate.createId
import com.spice.nativeapp.tools.annotate.drawDraft
import com.spice.nativeapp.tools.annotate.drawFigure
import com.spice.nativeapp.tools.annotate.drawSelection
import com.spice.nativeapp.tools.annotate.encodePngWithDpi
import com.spice.nativeapp.tools.annotate.fileToPanel
import com.spice.nativeapp.tools.annotate.figureSummary
import com.spice.nativeapp.tools.annotate.figureToSvg
import com.spice.nativeapp.tools.annotate.findAnnotationAt
import com.spice.nativeapp.tools.annotate.findFrameAt
import com.spice.nativeapp.tools.annotate.findHandleAt
import com.spice.nativeapp.tools.annotate.formatValue
import com.spice.nativeapp.tools.annotate.historyKey
import com.spice.nativeapp.tools.annotate.loadImageBitmap
import com.spice.nativeapp.tools.annotate.loadSnapshot
import com.spice.nativeapp.tools.annotate.panelLabel
import com.spice.nativeapp.tools.annotate.parseProjectText
import com.spice.nativeapp.tools.annotate.profileCsv
import com.spice.nativeapp.tools.annotate.profileStatsText
import com.spice.nativeapp.tools.annotate.projectJson
import com.spice.nativeapp.tools.annotate.pxFromMm
import com.spice.nativeapp.tools.annotate.renderFigurePng
import com.spice.nativeapp.tools.annotate.safeCrop
import com.spice.nativeapp.tools.annotate.safeFileName
import com.spice.nativeapp.tools.annotate.saveSnapshot
import com.spice.nativeapp.tools.annotate.sourcePixelsPerFigurePixel
import com.spice.nativeapp.tools.annotate.unitSymbol
import com.spice.nativeapp.tools.annotate.visibleRect
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixNumberField
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.PixTextField
import com.spice.nativeapp.tools.gel.gt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject

// --------------------------------------------------------------- drag models

private sealed interface AnnotateDrag

private data class AnnotateDrawDrag(
    val tool: String, // 'arrow' | 'rect' | 'ellipse' | 'profile'
    val start: com.spice.nativeapp.tools.annotate.Point,
    var end: com.spice.nativeapp.tools.annotate.Point,
    val before: com.spice.nativeapp.tools.annotate.AnnotateProject,
) : AnnotateDrag

private data class AnnotateCalibrateDrag(
    val panelId: String,
    val start: com.spice.nativeapp.tools.annotate.Point,
    var end: com.spice.nativeapp.tools.annotate.Point,
    val before: com.spice.nativeapp.tools.annotate.AnnotateProject,
) : AnnotateDrag

private class AnnotateMoveDrag(
    val id: String,
    val start: com.spice.nativeapp.tools.annotate.Point,
    val orig: Annotation,
    val before: com.spice.nativeapp.tools.annotate.AnnotateProject,
) : AnnotateDrag {
    var moved = false
}

private class AnnotateResizeDrag(
    val id: String,
    val handle: String,
    val orig: Annotation,
    val origBox: Box,
    val start: com.spice.nativeapp.tools.annotate.Point,
    val before: com.spice.nativeapp.tools.annotate.AnnotateProject,
) : AnnotateDrag {
    var moved = false
}

private class AnnotatePanDrag(
    val panelId: String,
    val start: com.spice.nativeapp.tools.annotate.Point,
    val origX: Double,
    val origY: Double,
    val before: com.spice.nativeapp.tools.annotate.AnnotateProject,
) : AnnotateDrag {
    var moved = false
}

/** JS Number(value.toPrecision(4))：4 位十进制有效数字。 */
private fun toPrecision4(value: Double): Double {
    if (value == 0.0) return 0.0
    val exponent = kotlin.math.floor(kotlin.math.log10(abs(value)))
    val digits = (4 - 1 - exponent).toInt()
    return if (digits > 0) {
        val factor = 10.0.pow(digits.toDouble())
        kotlin.math.round(value * factor) / factor
    } else {
        val factor = 10.0.pow(-digits.toDouble())
        kotlin.math.round(value / factor) * factor
    }
}

private fun jsFixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%." + digits + "f", value)

// ------------------------------------------------------------------- screen

@Composable
fun AnnotateScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun t(zh: String): String = gt(zh)

    fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    fun interp(template: String, vararg pairs: Pair<String, Any?>): String {
        var out = t(template)
        pairs.forEach { (k, v) -> out = out.replace("{$k}", v?.toString() ?: "") }
        return out
    }

    // ------------------------------------------------------------- state
    var project by remember { mutableStateOf(createDefaultProject()) }
    var ready by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf("select") }
    var selectedPanelId by remember { mutableStateOf<String?>(null) }
    var selectedAnnId by remember { mutableStateOf<String?>(null) }
    var zoomFactor by remember { mutableStateOf(1.0) }
    var drag by remember { mutableStateOf<AnnotateDrag?>(null) }
    var busy by remember { mutableStateOf("idle") }
    var autosaveState by remember { mutableStateOf("idle") }
    var savedAt by remember { mutableStateOf("") }
    var calibrateKnown by remember { mutableStateOf(100.0) }
    var calibrateUnit by remember { mutableStateOf<String>("um") }
    var showNewConfirm by remember { mutableStateOf(false) }
    var profileSeries by remember { mutableStateOf<ProfileSeries?>(null) }
    var profileBusy by remember { mutableStateOf(false) }
    var viewScaleState by remember { mutableStateOf(1.0) }

    val undoStack = remember { mutableStateListOf<com.spice.nativeapp.tools.annotate.AnnotateProject>() }
    val redoStack = remember { mutableStateListOf<com.spice.nativeapp.tools.annotate.AnnotateProject>() }
    var lastSavedKey by remember { mutableStateOf("") }
    var quotaWarned by remember { mutableStateOf(false) }

    // 预览缓存：key = previewKey(panel)；artVersion 对应原版异步解码 bump。
    val previewCache = remember { mutableMapOf<String, Pair<String, PanelArt>>() }
    var previewArts by remember { mutableStateOf<Map<String, PanelArt>>(emptyMap()) }
    var artVersion by remember { mutableStateOf(0) }

    // ------------------------------------------------------------ derived
    val geom = remember(project) { buildFigureGeometry(project) }
    val meta = remember(project, geom) { buildAnnotationMeta(project, geom) }
    val selectedPanel = project.panels.firstOrNull { it.id == selectedPanelId }
    val selectedAnn = project.annotations.firstOrNull { it.id == selectedAnnId }
    val selectedFrame = selectedPanel?.let { geom.frameById[it.id] }
    val canUndo = undoStack.isNotEmpty()
    val canRedo = redoStack.isNotEmpty()
    val figureHeightMm = geom.height / PX_PER_MM

    // ------------------------------------------------------------- history

    fun pushHistory(before: com.spice.nativeapp.tools.annotate.AnnotateProject) {
        if (historyKey(before) == historyKey(project)) return
        undoStack.add(before)
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** 原版 withCommit：先快照、变更、再 pushHistory。 */
    fun withCommit(block: (com.spice.nativeapp.tools.annotate.AnnotateProject) -> com.spice.nativeapp.tools.annotate.AnnotateProject) {
        val before = project
        project = block(project)
        pushHistory(before)
    }

    /** 原版 bind:value / oninput 直写路径（无历史）。 */
    fun applyNoCommit(block: (com.spice.nativeapp.tools.annotate.AnnotateProject) -> com.spice.nativeapp.tools.annotate.AnnotateProject) {
        project = block(project)
    }

    fun setPanelById(panelId: String, transform: (Panel) -> Panel, commit: Boolean = true) {
        val block: (com.spice.nativeapp.tools.annotate.AnnotateProject) -> com.spice.nativeapp.tools.annotate.AnnotateProject = { p ->
            p.copy(panels = p.panels.map { if (it.id == panelId) transform(it) else it }.toMutableList())
        }
        if (commit) withCommit(block) else applyNoCommit(block)
    }

    fun setAnnById(annId: String, transform: (Annotation) -> Annotation, commit: Boolean = true) {
        val block: (com.spice.nativeapp.tools.annotate.AnnotateProject) -> com.spice.nativeapp.tools.annotate.AnnotateProject = { p ->
            p.copy(annotations = p.annotations.map { if (it.id == annId) transform(it) else it }.toMutableList())
        }
        if (commit) withCommit(block) else applyNoCommit(block)
    }

    fun afterHistoryJump() {
        if (selectedAnnId != null && project.annotations.none { it.id == selectedAnnId }) selectedAnnId = null
        if (selectedPanelId != null && project.panels.none { it.id == selectedPanelId }) {
            selectedPanelId = project.panels.firstOrNull()?.id
        }
        drag = null
        artVersion++
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = project
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(current)
        if (redoStack.size > 60) redoStack.removeAt(0)
        project = previous
        afterHistoryJump()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = project
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(current)
        if (undoStack.size > 60) undoStack.removeAt(0)
        project = next
        afterHistoryJump()
    }

    fun mmValue(px: Double): String = jsFixed(px / PX_PER_MM, 1)
    fun percentValue(fraction: Double): String = jsFixed(fraction * 100, 1)

    fun setLayout(transform: (com.spice.nativeapp.tools.annotate.LayoutSettings) -> com.spice.nativeapp.tools.annotate.LayoutSettings) {
        withCommit { p -> p.copy(layout = transform(p.layout)) }
    }

    // ------------------------------------------------------- image artwork

    fun previewKey(panel: Panel): String =
        "${panel.brightness}|${panel.contrast}|${panel.channels.toJson()}|${panel.width}x${panel.height}|${panel.dataUrl.length}"

    fun panelsSig(): String = project.panels.joinToString(";;") { "${it.id}:${previewKey(it)}" }

    // 原版 previewArt：120ms debounce + maxDimension 1400 + artVersion bump。
    LaunchedEffect(panelsSig()) {
        delay(120)
        for (panel in project.panels) {
            val key = previewKey(panel)
            val cached = previewCache[panel.id]
            if (cached != null && cached.first == key) continue
            yield()
            try {
                val art = withContext(Dispatchers.Default) { buildArtwork(panel, 1400, false) }
                previewCache[panel.id] = key to art
                previewArts = previewCache.mapValues { entry -> entry.value.second }
                artVersion++
            } catch (e: Exception) {
                /* an unreadable panel stays blank until the project is fixed */
            }
        }
    }

    val resolver: (Panel) -> PanelArt? = { panel -> previewArts[panel.id] }

    suspend fun buildExportArts(withDataUrl: Boolean): Map<String, PanelArt> {
        val out = mutableMapOf<String, PanelArt>()
        for (panel in project.panels) {
            try {
                out[panel.id] = withContext(Dispatchers.Default) { buildArtwork(panel, 0, withDataUrl) }
            } catch (e: Exception) {
                /* skip panels that cannot be decoded */
            }
        }
        return out
    }

    /** Medium-resolution composite art for interactive profile sampling. */
    suspend fun buildProfileArts(): Map<String, PanelArt> {
        val out = mutableMapOf<String, PanelArt>()
        for (panel in project.panels) {
            try {
                out[panel.id] = withContext(Dispatchers.Default) { buildArtwork(panel, 1600, false) }
            } catch (e: Exception) {
                /* skip panels that cannot be decoded */
            }
        }
        return out
    }

    fun clearArtCaches() {
        previewCache.clear()
        previewArts = emptyMap()
        clearImageCache()
        artVersion++
    }

    // --------------------------------------------------------- load/save

    LaunchedEffect(Unit) {
        val saved = loadSnapshot(ctx)
        if (saved != null && (saved.panels.isNotEmpty() || saved.annotations.isNotEmpty())) {
            project = saved
            selectedPanelId = saved.panels.firstOrNull()?.id
            toast(t("已恢复自动保存的项目"))
        }
        ready = true
    }

    fun flushAutosave() {
        if (!ready) return
        val snap = project
        val key = historyKey(snap)
        if (key == lastSavedKey) return
        if (saveSnapshot(ctx, snap)) {
            lastSavedKey = key
            autosaveState = "saved"
            savedAt = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, Locale.getDefault()).format(Date())
        }
    }

    DisposableEffect(Unit) {
        onDispose { flushAutosave() }
    }

    // 原版 $effect：900ms autosave。
    LaunchedEffect(ready, project) {
        if (!ready) return@LaunchedEffect
        val key = historyKey(project)
        if (key == lastSavedKey) {
            autosaveState = "saved"
            return@LaunchedEffect
        }
        val snap = project
        autosaveState = "saving"
        delay(900)
        if (saveSnapshot(ctx, snap)) {
            autosaveState = "saved"
            savedAt = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, Locale.getDefault()).format(Date())
            lastSavedKey = key
        } else {
            autosaveState = "failed"
            if (!quotaWarned) {
                quotaWarned = true
                toast(t("自动保存失败。请导出项目 JSON 以保留工作。"), long = true)
            }
        }
    }

    // 选中项失效自动清理（原版 $effect）。
    LaunchedEffect(project) {
        if (selectedAnnId != null && project.annotations.none { it.id == selectedAnnId }) selectedAnnId = null
        if (selectedPanelId != null && project.panels.none { it.id == selectedPanelId }) {
            selectedPanelId = project.panels.firstOrNull()?.id
        }
    }

    LaunchedEffect(selectedPanelId, project) {
        project.panels.firstOrNull { it.id == selectedPanelId }?.calibration?.let { calibrateUnit = it.unit }
    }

    // 原版 $effect：选中 profile 异步采样（120ms debounce + 1600 合成图）。
    LaunchedEffect(selectedAnnId, project, artVersion) {
        val ann = project.annotations.firstOrNull { it.id == selectedAnnId }
        if (ann == null || ann !is ProfileAnnotation || project.panels.isEmpty()) {
            profileSeries = null
            profileBusy = false
            return@LaunchedEffect
        }
        profileBusy = true
        delay(120)
        try {
            val current = project
            val figure = buildFigureGeometry(current)
            val arts = buildProfileArts()
            val lineResolver: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
            val series = withContext(Dispatchers.Default) {
                computeProfile(ann, current, figure, lineResolver, t("强度剖面"))
            }
            profileSeries = series
        } catch (e: Exception) {
            profileSeries = null
        } finally {
            profileBusy = false
        }
    }

    // ------------------------------------------------------ project actions

    fun readUriBytes(uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    fun uriDisplayName(uri: Uri): String {
        var name = ""
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: ""
            }
        } catch (e: Exception) {
        }
        return name.ifEmpty { uri.lastPathSegment ?: "file" }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || busy != "idle") return
        busy = "import"
        scope.launch {
            try {
                val decoded = ArrayList<Panel>()
                var failed = 0
                for (uri in uris) {
                    val name = uriDisplayName(uri)
                    try {
                        val bytes = withContext(Dispatchers.IO) { readUriBytes(uri) }
                        decoded.add(withContext(Dispatchers.Default) { fileToPanel(name, bytes, null) })
                    } catch (e: Exception) {
                        failed += 1
                    }
                }
                if (decoded.isNotEmpty()) {
                    val before = project
                    project = project.copy(panels = (project.panels + decoded).toMutableList())
                    selectedPanelId = decoded[0].id
                    selectedAnnId = null
                    zoomFactor = 1.0
                    pushHistory(before)
                    artVersion++
                    toast(interp("已导入 {n} 个面板", "n" to decoded.size))
                }
                if (failed > 0) toast(interp("有 {n} 个文件无法导入", "n" to failed))
            } finally {
                busy = "idle"
            }
        }
    }

    fun applyProjectFile(text: String) {
        val parsed = parseProjectText(text)
        if (parsed == null) {
            toast(t("此项目文件无效"), long = true)
            return
        }
        val before = project
        project = parsed
        selectedPanelId = project.panels.firstOrNull()?.id
        selectedAnnId = null
        zoomFactor = 1.0
        clearArtCaches()
        pushHistory(before)
        lastSavedKey = ""
        toast(t("项目已加载"))
    }

    fun newProject() {
        project = createDefaultProject()
        selectedPanelId = null
        selectedAnnId = null
        zoomFactor = 1.0
        drag = null
        undoStack.clear()
        redoStack.clear()
        lastSavedKey = ""
        clearArtCaches()
        clearSnapshot(ctx)
        toast(t("已新建项目"))
    }

    fun baseName(): String = safeFileName(project.name)

    fun saveBytes(name: String, bytes: ByteArray, message: String) {
        try {
            val dir = File(ctx.filesDir, "annotate-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            toast(message)
        } catch (e: Exception) {
            toast(e.message ?: t("导出失败"), long = true)
        }
    }

    fun saveText(name: String, text: String, message: String) {
        try {
            val dir = File(ctx.filesDir, "annotate-exports").apply { mkdirs() }
            File(dir, name).writeText(text)
            toast(message)
        } catch (e: Exception) {
            toast(e.message ?: t("导出失败"), long = true)
        }
    }

    fun exportPng() {
        if (project.panels.isEmpty() || busy != "idle") {
            if (project.panels.isEmpty()) toast(t("请先添加至少一个面板"))
            return
        }
        busy = "png"
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val current = project
                    val figure = buildFigureGeometry(current)
                    val boxes = buildAnnotationMeta(current, figure)
                    val arts = buildExportArts(false)
                    val r: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
                    val dpi = current.layout.exportDpi
                    val canvas = renderFigurePng(current, figure, r, boxes, dpi)
                    val bytes = encodePngWithDpi(canvas, dpi, figureSummary(current, figure, dpi))
                    saveBytes("${baseName()}_${dpi}dpi.png", bytes, t("图形已导出"))
                }
            } catch (e: Exception) {
                toast(e.message ?: t("导出失败"), long = true)
            } finally {
                busy = "idle"
            }
        }
    }

    fun exportSvg() {
        if (project.panels.isEmpty() || busy != "idle") {
            if (project.panels.isEmpty()) toast(t("请先添加至少一个面板"))
            return
        }
        busy = "svg"
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val current = project
                    val figure = buildFigureGeometry(current)
                    val boxes = buildAnnotationMeta(current, figure)
                    val arts = buildExportArts(true)
                    val r: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
                    val svg = figureToSvg(current, figure, r, boxes)
                    saveText("${baseName()}.svg", svg, t("图形已导出"))
                }
            } catch (e: Exception) {
                toast(e.message ?: t("导出失败"), long = true)
            } finally {
                busy = "idle"
            }
        }
    }

    fun exportProjectJson() {
        if (busy != "idle") return
        busy = "project"
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    saveText("${baseName()}.annot.json", projectJson(project), t("项目已保存"))
                }
            } finally {
                busy = "idle"
            }
        }
    }

    fun exportProfilesCsv() {
        if (busy != "idle") return
        busy = "csv"
        scope.launch {
            try {
                val series = withContext(Dispatchers.Default) {
                    val current = project
                    if (annotateProfileAnnotations(current).isEmpty()) return@withContext emptyList<ProfileSeries>()
                    val figure = buildFigureGeometry(current)
                    val arts = buildExportArts(false)
                    val r: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
                    collectProfiles(current, figure, r)
                }
                if (series.isEmpty()) {
                    toast(t("请先添加至少一条剖面线。"))
                } else {
                    saveText("${baseName()}_profiles.csv", profileCsv(series), t("已导出剖面 CSV。"))
                }
            } catch (e: Exception) {
                toast(e.message ?: t("导出失败"), long = true)
            } finally {
                busy = "idle"
            }
        }
    }

    fun exportBundle() {
        if (project.panels.isEmpty() || busy != "idle") {
            if (project.panels.isEmpty()) toast(t("请先添加至少一个面板"))
            return
        }
        busy = "bundle"
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val current = project
                    val figure = buildFigureGeometry(current)
                    val boxes = buildAnnotationMeta(current, figure)
                    val arts = buildExportArts(true)
                    val r: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
                    val dpi = current.layout.exportDpi
                    val canvas = renderFigurePng(current, figure, r, boxes, dpi)
                    val png = encodePngWithDpi(canvas, dpi, figureSummary(current, figure, dpi))
                    val svg = figureToSvg(current, figure, r, boxes)
                    val profiles = collectProfiles(current, figure, r)
                    val stem = baseName()
                    val files = mutableListOf(
                        ReproFile("${stem}_${dpi}dpi.png", "image/png", png),
                        ReproFile("${stem}.svg", "image/svg+xml", svg),
                    )
                    if (profiles.isNotEmpty()) {
                        files.add(ReproFile("${stem}_profiles.csv", "text/csv", profileCsv(profiles)))
                    }
                    val params = annotateParameters(current)
                    params.put("profiles", JSONArray().apply {
                        profiles.forEach { series ->
                            put(
                                JSONObject().apply {
                                    put("name", series.name)
                                    put("annotationId", series.annotationId)
                                    put("samples", series.samples)
                                    put("min", jsFixed(series.min, 3).toDouble())
                                    put("max", jsFixed(series.max, 3).toDouble())
                                    put("mean", jsFixed(series.mean, 3).toDouble())
                                },
                            )
                        }
                    })
                    val result = buildReproBundle(
                        ctx,
                        ReproBundleInput(
                            tool = "annotate",
                            title = annotateDocName(current),
                            files = files,
                            parameters = params,
                            methods = annotateMethods(current),
                            project = current.toJson(),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        toast(interp("已导出可复现包。") + " " + result.fileName)
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: t("可复现包导出失败。"), long = true)
            } finally {
                busy = "idle"
            }
        }
    }

    /** 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（README §12，同 T3/T4）。 */
    fun sendToFigureNow() {
        if (project.panels.isEmpty() || busy != "idle") {
            if (project.panels.isEmpty()) toast(t("请先添加至少一个面板"))
            return
        }
        busy = "png"
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val current = project
                    val figure = buildFigureGeometry(current)
                    val boxes = buildAnnotationMeta(current, figure)
                    val arts = buildExportArts(false)
                    val r: (Panel) -> PanelArt? = { panel -> arts[panel.id] }
                    val canvas = renderFigurePng(current, figure, r, boxes, min(current.layout.exportDpi, 300))
                    val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                    val out = File(dir, "${baseName()} figure.png")
                    out.outputStream().use { canvas.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } catch (e: Exception) {
                toast(t("无法发送到 Figure Studio"), long = true)
            } finally {
                busy = "idle"
            }
        }
    }

    // ------------------------------------------------------------- pointer

    fun applyMove(d: AnnotateMoveDrag, point: com.spice.nativeapp.tools.annotate.Point) {
        val dx = point.x - d.start.x
        val dy = point.y - d.start.y
        if (abs(dx) < 0.01 && abs(dy) < 0.01) return
        val ann = project.annotations.firstOrNull { it.id == d.id } ?: return
        val next: Annotation = when {
            ann is ArrowAnnotation && d.orig is ArrowAnnotation ->
                ann.copy(x1 = d.orig.x1 + dx, y1 = d.orig.y1 + dy, x2 = d.orig.x2 + dx, y2 = d.orig.y2 + dy)
            ann is ProfileAnnotation && d.orig is ProfileAnnotation ->
                ann.copy(x1 = d.orig.x1 + dx, y1 = d.orig.y1 + dy, x2 = d.orig.x2 + dx, y2 = d.orig.y2 + dy)
            ann is TextAnnotation && d.orig is TextAnnotation ->
                ann.copy(x = d.orig.x + dx, y = d.orig.y + dy)
            ann is PanelBarAnnotation && d.orig is PanelBarAnnotation -> {
                val frame = geom.frameById[ann.panelId] ?: return
                ann.copy(nx = clamp(d.orig.nx + dx / frame.w, 0.0, 1.0), ny = clamp(d.orig.ny + dy / frame.h, 0.0, 1.0))
            }
            else -> ann
        }
        if (next == ann) return
        val before = project
        project = project.copy(annotations = project.annotations.map { if (it.id == ann.id) next else it }.toMutableList())
        if (!d.moved) {
            d.moved = true
            pushHistory(before)
        }
    }

    fun applyResize(d: AnnotateResizeDrag, point: com.spice.nativeapp.tools.annotate.Point) {
        val ann = project.annotations.firstOrNull { it.id == d.id } ?: return
        val next: Annotation = when {
            ann is ArrowAnnotation && d.orig is ArrowAnnotation ->
                if (d.handle == "start") ann.copy(x1 = point.x, y1 = point.y)
                else ann.copy(x2 = point.x, y2 = point.y)
            ann is ProfileAnnotation && d.orig is ProfileAnnotation ->
                if (d.handle == "start") ann.copy(x1 = point.x, y1 = point.y)
                else ann.copy(x2 = point.x, y2 = point.y)
            ann is RectAnnotation && d.orig is RectAnnotation -> {
                val anchorX = if (d.handle.contains("w")) d.origBox.x + d.origBox.w else d.origBox.x
                val anchorY = if (d.handle.contains("n")) d.origBox.y + d.origBox.h else d.origBox.y
                ann.copy(
                    x = min(anchorX, point.x), y = min(anchorY, point.y),
                    w = max(2.0, abs(point.x - anchorX)), h = max(2.0, abs(point.y - anchorY)),
                )
            }
            ann is EllipseAnnotation && d.orig is EllipseAnnotation -> {
                val anchorX = if (d.handle.contains("w")) d.origBox.x + d.origBox.w else d.origBox.x
                val anchorY = if (d.handle.contains("n")) d.origBox.y + d.origBox.h else d.origBox.y
                ann.copy(
                    x = min(anchorX, point.x), y = min(anchorY, point.y),
                    w = max(2.0, abs(point.x - anchorX)), h = max(2.0, abs(point.y - anchorY)),
                )
            }
            ann is TextAnnotation && d.orig is TextAnnotation -> {
                val ratio = (point.x - d.origBox.x) / max(10.0, d.origBox.w)
                ann.copy(fontSizePt = clamp(d.orig.fontSizePt * ratio, 4.0, 96.0))
            }
            ann is ScaleBarAnnotation && d.orig is ScaleBarAnnotation -> {
                val bar = barLayout(d.orig, geom) ?: return
                val ratio = max(0.02, (point.x - d.orig.x) / max(2.0, bar.length))
                ann.copy(value = max(0.001, toPrecision4(d.orig.value * ratio)))
            }
            ann is PanelBarAnnotation && d.orig is PanelBarAnnotation -> {
                val frame = geom.frameById[d.orig.panelId] ?: return
                val bar = barLayout(d.orig, geom) ?: return
                val startX = frame.x + d.orig.nx * frame.w
                val ratio = max(0.02, (point.x - startX) / max(2.0, bar.length))
                ann.copy(value = max(0.001, toPrecision4(d.orig.value * ratio)))
            }
            else -> return
        }
        val before = project
        project = project.copy(annotations = project.annotations.map { if (it.id == ann.id) next else it }.toMutableList())
        if (!d.moved) {
            d.moved = true
            pushHistory(before)
        }
    }

    fun applyPan(d: AnnotatePanDrag, point: com.spice.nativeapp.tools.annotate.Point) {
        val panel = project.panels.firstOrNull { it.id == d.panelId } ?: return
        val frame = geom.frameById[d.panelId] ?: return
        val crop = safeCrop(panel)
        val vis = visibleRect(panel)
        val cw = 1 - crop.left - crop.right
        val ch = 1 - crop.top - crop.bottom
        val mx = (cw - vis.sw) / 2
        val my = (ch - vis.sh) / 2
        var next = panel
        if (mx > 0.000001) {
            val delta = -(point.x - d.start.x) * (vis.sw * panel.width) / frame.w / panel.width
            next = next.copy(offsetX = clamp(d.origX + delta / mx, -1.0, 1.0))
        }
        if (my > 0.000001) {
            val delta = -(point.y - d.start.y) * (vis.sh * panel.height) / frame.h / panel.height
            next = next.copy(offsetY = clamp(d.origY + delta / my, -1.0, 1.0))
        }
        if (next == panel) return
        val before = project
        project = project.copy(panels = project.panels.map { if (it.id == panel.id) next else it }.toMutableList())
        if (!d.moved) {
            d.moved = true
            pushHistory(before)
        }
    }

    fun finishDraw(d: AnnotateDrawDrag) {
        val viewScale = viewScaleState
        val distance = hypot(d.end.x - d.start.x, d.end.y - d.start.y)
        if (distance < 4 / viewScale) return
        val before = d.before
        val ann: Annotation = when (d.tool) {
            "arrow" -> ArrowAnnotation(
                id = createId("ann"), color = "#111111",
                x1 = d.start.x, y1 = d.start.y, x2 = d.end.x, y2 = d.end.y,
                strokeWidthPt = 1.5, headSizePt = 8.0,
            )
            "profile" -> ProfileAnnotation(
                id = createId("ann"), color = "#ffd23f",
                x1 = d.start.x, y1 = d.start.y, x2 = d.end.x, y2 = d.end.y,
                samples = PROFILE_DEFAULT_SAMPLES, strokeWidthPt = 1.2,
            )
            else -> {
                val x = min(d.start.x, d.end.x)
                val y = min(d.start.y, d.end.y)
                val w = max(2.0, abs(d.end.x - d.start.x))
                val h = max(2.0, abs(d.end.y - d.start.y))
                if (d.tool == "rect") {
                    RectAnnotation(createId("ann"), "#111111", x, y, w, h, 1.5, "none")
                } else {
                    EllipseAnnotation(createId("ann"), "#111111", x, y, w, h, 1.5, "none")
                }
            }
        }
        project = project.copy(annotations = (project.annotations + ann).toMutableList())
        selectedAnnId = ann.id
        pushHistory(before)
    }

    fun finishCalibration(d: AnnotateCalibrateDrag) {
        val viewScale = viewScaleState
        val panel = project.panels.firstOrNull { it.id == d.panelId } ?: return
        val frame = geom.frameById[d.panelId] ?: return
        val length = hypot(d.end.x - d.start.x, d.end.y - d.start.y)
        if (length < 5 / viewScale) {
            toast(t("校准线太短"))
            return
        }
        if (!(calibrateKnown > 0)) {
            toast(t("请输入正的长度"))
            return
        }
        val sourcePx = length * sourcePixelsPerFigurePixel(panel, frame.w)
        val pxPerUnit = sourcePx / calibrateKnown
        val before = project
        project = project.copy(
            panels = project.panels.map {
                if (it.id == panel.id) it.copy(calibration = Calibration(calibrateUnit, pxPerUnit)) else it
            }.toMutableList(),
        )
        selectedPanelId = panel.id
        tool = "select"
        pushHistory(before)
        toast(t("校准已保存"))
    }

    fun defaultBarValue(unit: String): Double = when (unit) {
        UNIT_MM -> 1.0
        "nm" -> 100.0
        UNIT_PX -> 100.0
        else -> 10.0
    }

    fun onCanvasPointerDown(point: com.spice.nativeapp.tools.annotate.Point) {
        val viewScale = viewScaleState
        val frame = findFrameAt(geom, point)

        if (tool == "select") {
            if (selectedAnn != null) {
                val handle = findHandleAt(selectedAnn, meta[selectedAnn.id], point, 10 / viewScale)
                if (handle != null) {
                    drag = AnnotateResizeDrag(
                        id = selectedAnn.id,
                        handle = handle,
                        orig = selectedAnn,
                        origBox = meta[selectedAnn.id]?.box ?: Box(0.0, 0.0, 0.0, 0.0),
                        start = point,
                        before = project,
                    )
                    return
                }
            }
            val hit = findAnnotationAt(project, meta, point, 5 / viewScale)
            if (hit != null) {
                selectedAnnId = hit
                if (frame != null) selectedPanelId = frame.panel.id
                val ann = project.annotations.firstOrNull { it.id == hit }
                if (ann != null) {
                    drag = AnnotateMoveDrag(id = hit, start = point, orig = ann, before = project)
                }
                return
            }
            selectedAnnId = null
            if (frame != null) selectedPanelId = frame.panel.id
            return
        }

        if (frame != null) selectedPanelId = frame.panel.id

        when (tool) {
            "pan" -> {
                if (frame == null) return
                drag = AnnotatePanDrag(
                    panelId = frame.panel.id,
                    start = point,
                    origX = frame.panel.offsetX,
                    origY = frame.panel.offsetY,
                    before = project,
                )
            }
            "text" -> {
                val before = project
                val ann = TextAnnotation(
                    id = createId("ann"), color = "#111111",
                    x = point.x, y = point.y,
                    text = t("文本"), fontSizePt = 10.0, background = "none", paddingPt = 2.0,
                )
                project = project.copy(annotations = (project.annotations + ann).toMutableList())
                selectedAnnId = ann.id
                pushHistory(before)
            }
            "scalebar" -> {
                val panel = frame?.panel ?: selectedPanel
                val calibration = panel?.calibration
                val before = project
                val ann = ScaleBarAnnotation(
                    id = createId("ann"), color = "#111111",
                    x = point.x, y = point.y,
                    mode = if (calibration != null) "calibrated" else "fixed",
                    value = calibration?.let { defaultBarValue(it.unit) } ?: 5.0,
                    unit = calibration?.unit ?: UNIT_MM,
                    panelId = panel?.id ?: "",
                    thicknessPt = 2.0, fontSizePt = 9.0, showLabel = true,
                )
                project = project.copy(annotations = (project.annotations + ann).toMutableList())
                selectedAnnId = ann.id
                pushHistory(before)
            }
            "panelbar" -> {
                val target = frame ?: selectedFrame
                if (target == null) {
                    toast(t("请先选择面板"))
                    return
                }
                val panel = target.panel
                selectedPanelId = panel.id
                if (panel.calibration == null) {
                    toast(t("请先校准该面板，才能使用校准比例尺。"))
                    return
                }
                val nx = clamp((point.x - target.x) / target.w, 0.02, 0.9)
                val ny = clamp((point.y - target.y) / target.h, 0.05, 0.95)
                val before = project
                val ann = PanelBarAnnotation(
                    id = createId("ann"), color = "#111111",
                    panelId = panel.id, nx = nx, ny = ny,
                    value = defaultBarValue(panel.calibration.unit),
                    unit = panel.calibration.unit,
                    thicknessPt = 2.0, fontSizePt = 9.0, showLabel = true,
                )
                project = project.copy(annotations = (project.annotations + ann).toMutableList())
                selectedAnnId = ann.id
                pushHistory(before)
            }
            "calibrate" -> {
                if (frame == null) {
                    toast(t("请先选择面板"))
                    return
                }
                drag = AnnotateCalibrateDrag(panelId = frame.panel.id, start = point, end = point, before = project)
            }
            "arrow", "rect", "ellipse", "profile" -> {
                drag = AnnotateDrawDrag(tool = tool, start = point, end = point, before = project)
            }
        }
    }

    fun onCanvasPointerMove(point: com.spice.nativeapp.tools.annotate.Point) {
        val d = drag ?: return
        when (d) {
            is AnnotateDrawDrag -> d.end = point
            is AnnotateCalibrateDrag -> d.end = point
            is AnnotateMoveDrag -> applyMove(d, point)
            is AnnotateResizeDrag -> applyResize(d, point)
            is AnnotatePanDrag -> applyPan(d, point)
        }
    }

    fun onCanvasPointerUp() {
        val finished = drag
        drag = null
        if (finished is AnnotateDrawDrag) finishDraw(finished)
        else if (finished is AnnotateCalibrateDrag) finishCalibration(finished)
    }

    fun deleteAnnotationById(id: String) {
        withCommit { p -> p.copy(annotations = p.annotations.filter { it.id != id }.toMutableList()) }
        if (selectedAnnId == id) selectedAnnId = null
    }

    fun duplicateAnnotationById(id: String) {
        val source = project.annotations.firstOrNull { it.id == id } ?: return
        val copy: Annotation = when (source) {
            is PanelBarAnnotation -> source.copy(
                id = createId("ann"),
                nx = clamp(source.nx + 0.03, 0.0, 1.0),
                ny = clamp(source.ny + 0.03, 0.0, 1.0),
            )
            is ArrowAnnotation -> source.copy(id = createId("ann"), x1 = source.x1 + 12, y1 = source.y1 + 12, x2 = source.x2 + 12, y2 = source.y2 + 12)
            is ProfileAnnotation -> source.copy(id = createId("ann"), x1 = source.x1 + 12, y1 = source.y1 + 12, x2 = source.x2 + 12, y2 = source.y2 + 12)
            is RectAnnotation -> source.copy(id = createId("ann"), x = source.x + 12, y = source.y + 12)
            is EllipseAnnotation -> source.copy(id = createId("ann"), x = source.x + 12, y = source.y + 12)
            is TextAnnotation -> source.copy(id = createId("ann"), x = source.x + 12, y = source.y + 12)
            is ScaleBarAnnotation -> source.copy(id = createId("ann"), x = source.x + 12, y = source.y + 12)
        }
        withCommit { p -> p.copy(annotations = (p.annotations + copy).toMutableList()) }
        selectedAnnId = copy.id
    }

    // ----------------------------------------------------------- panel ops

    fun movePanel(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= project.panels.size) return
        withCommit { p ->
            val list = p.panels.toMutableList()
            val panel = list.removeAt(index)
            list.add(target, panel)
            p.copy(panels = list)
        }
    }

    fun duplicatePanel(index: Int) {
        val panel = project.panels.getOrNull(index) ?: return
        withCommit { p ->
            val copy = panel.copy(
                id = createId("panel"),
                name = "${panel.name} ${t("副本")}",
                channels = com.spice.nativeapp.tools.annotate.ChannelMix(
                    ChannelSetting(panel.channels.r.show, panel.channels.r.gain, panel.channels.r.invert),
                    ChannelSetting(panel.channels.g.show, panel.channels.g.gain, panel.channels.g.invert),
                    ChannelSetting(panel.channels.b.show, panel.channels.b.gain, panel.channels.b.invert),
                ),
            )
            val list = p.panels.toMutableList()
            list.add(index + 1, copy)
            p.copy(panels = list)
        }
        selectedPanelId = project.panels.getOrNull(index + 1)?.id
        selectedAnnId = null
    }

    fun removePanel(index: Int) {
        val panel = project.panels.getOrNull(index) ?: return
        withCommit { p ->
            val panels = p.panels.toMutableList().also { it.removeAt(index) }
            val annotations = p.annotations
                .filter { !(it is PanelBarAnnotation && it.panelId == panel.id) }
                .toMutableList()
            annotations.replaceAll { ann ->
                if (ann is ScaleBarAnnotation && ann.panelId == panel.id) {
                    ann.copy(panelId = panels.firstOrNull()?.id ?: "")
                } else ann
            }
            p.copy(panels = panels, annotations = annotations)
        }
        if (selectedPanelId == panel.id) {
            selectedPanelId = project.panels.getOrNull(min(index, project.panels.size - 1))?.id
        }
        if (selectedAnnId != null && project.annotations.none { it.id == selectedAnnId }) selectedAnnId = null
    }

    fun setCrop(panel: Panel, key: String, percent: Double) {
        val value = clamp(percent / 100, 0.0, 0.9)
        val crop = panel.crop
        val next = when (key) {
            "left" -> crop.copy(left = min(value, 0.9 - crop.right))
            "right" -> crop.copy(right = min(value, 0.9 - crop.left))
            "top" -> crop.copy(top = min(value, 0.9 - crop.bottom))
            else -> crop.copy(bottom = min(value, 0.9 - crop.top))
        }
        setPanelById(panel.id, { it.copy(crop = next) }, commit = true)
    }

    // -------------------------------------------------------- calibration

    fun calibrationText(panel: Panel): String {
        val calibration = panel.calibration ?: return t("未校准")
        val value = calibration.pxPerUnit
        val text =
            if (value >= 1000 || value < 0.01) String.format(Locale.US, "%.3e", value) else formatValue(value)
        return "$text px / ${unitSymbol(calibration.unit)}"
    }

    fun startCalibration(panel: Panel) {
        selectedPanelId = panel.id
        selectedAnnId = null
        tool = "calibrate"
    }

    fun setScaleMode(ann: ScaleBarAnnotation, mode: String) {
        if (ann.mode == mode) return
        withCommit { p ->
            val target = p.annotations.firstOrNull { it.id == ann.id } as? ScaleBarAnnotation
                ?: return@withCommit p
            val next: ScaleBarAnnotation = if (mode == "fixed") {
                val bar = barLayout(target, geom)
                target.copy(
                    value = bar?.let { kotlin.math.round(it.length / PX_PER_MM * 1000.0) / 1000.0 } ?: 5.0,
                    unit = UNIT_MM,
                    mode = "fixed",
                )
            } else {
                val panel = p.panels.firstOrNull { it.id == target.panelId }
                    ?: selectedPanel
                    ?: p.panels.firstOrNull { it.calibration != null }
                val unit = panel?.calibration?.unit
                val nextUnit = if (unit != null && unit != UNIT_PX) unit else "um"
                target.copy(
                    mode = "calibrated",
                    panelId = panel?.id ?: "",
                    unit = nextUnit,
                    value = defaultBarValue(nextUnit),
                )
            }
            p.copy(annotations = p.annotations.map { if (it.id == next.id) next else it }.toMutableList())
        }
    }

    fun setBarUnit(annId: String, next: String) {
        val ann = project.annotations.firstOrNull { it.id == annId }
        val currentUnit: String? = when (ann) {
            is ScaleBarAnnotation -> ann.unit
            is PanelBarAnnotation -> ann.unit
            else -> null
        }
        if (currentUnit == null || currentUnit == next) return
        withCommit { p ->
            val current = p.annotations.firstOrNull { it.id == annId } ?: return@withCommit p
            val updated: Annotation = when (current) {
                is ScaleBarAnnotation -> current.copy(value = convertValue(current.value, current.unit, next), unit = next)
                is PanelBarAnnotation -> current.copy(value = convertValue(current.value, current.unit, next), unit = next)
                else -> current
            }
            p.copy(annotations = p.annotations.map { if (it.id == updated.id) updated else it }.toMutableList())
        }
    }

    fun barLengthText(ann: Annotation): String {
        val bar = when (ann) {
            is ScaleBarAnnotation -> barLayout(ann, geom)
            is PanelBarAnnotation -> barLayout(ann, geom)
            else -> null
        } ?: return t("请先校准该面板，才能使用校准比例尺。")
        return "${jsFixed(bar.length / PX_PER_MM, 1)} ${t("mm")}"
    }

    fun annSummary(ann: Annotation): String = when (ann) {
        is ArrowAnnotation -> t("箭头")
        is RectAnnotation -> t("矩形")
        is EllipseAnnotation -> t("椭圆")
        is TextAnnotation -> {
            val text = ann.text.trim().replace(Regex("\\s+"), " ")
            "${t("文本")}: ${if (text.isEmpty()) "—" else if (text.length > 24) text.take(24) + "…" else text}"
        }
        is ScaleBarAnnotation -> "${t("比例尺")}: ${formatValue(ann.value)} ${unitSymbol(ann.unit)}"
        is PanelBarAnnotation -> {
            val index = project.panels.indexOfFirst { it.id == ann.panelId }
            val tag = if (index >= 0) panelLabel(index, project.layout.labelStyle) else "—"
            "${t("面板比例尺")} $tag: ${formatValue(ann.value)} ${unitSymbol(ann.unit)}"
        }
        is ProfileAnnotation -> {
            val lengthMm = hypot(ann.x2 - ann.x1, ann.y2 - ann.y1) / PX_PER_MM
            "${t("强度剖面")}: ${jsFixed(lengthMm, 1)} ${t("mm")}"
        }
    }

    // ---------------------------------------------------------------- pickers

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importUris(uris.toList())
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = with(java.io.ByteArrayOutputStream()) {
                ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(this) }
                toString(Charsets.UTF_8)
            }
            applyProjectFile(text)
        } catch (e: Exception) {
            toast(t("此项目文件无效"), long = true)
        }
    }

    // ------------------------------------------------------------- labels

    fun toolLabel(id: String): String = when (id) {
        "select" -> t("选择")
        "pan" -> t("平移图像")
        "arrow" -> t("箭头")
        "rect" -> t("矩形")
        "ellipse" -> t("椭圆")
        "text" -> t("文本")
        "scalebar" -> t("比例尺")
        "panelbar" -> t("面板比例尺")
        "profile" -> t("强度剖面")
        else -> t("校准")
    }

    val toolDefs = listOf(
        "select", "pan", "arrow", "rect", "ellipse",
        "text", "scalebar", "panelbar", "profile", "calibrate",
    )

    val hint: String = when (tool) {
        "select" -> t("点击面板或标注。拖动可移动，拖动控制点可缩放。")
        "pan" -> t("在面板内拖动以移动放大的图像。")
        "arrow" -> t("从尾部拖到头部。按住 Shift 可约束角度。")
        "rect" -> t("拖动以绘制矩形。")
        "ellipse" -> t("拖动以绘制椭圆或圆。")
        "text" -> t("点击图形放置文本标签，然后在右侧检查器中编辑。")
        "scalebar" -> t("点击图形放置比例尺，然后在检查器中设置长度和单位。")
        "panelbar" -> t("在已校准的面板内点击，将比例尺附着到该面板。")
        "profile" -> t("沿图面拖动以采集强度剖面。")
        else -> t("先在面板中填写已知长度，再沿该长度拖动一条线。")
    }

    fun setTool(next: String) {
        tool = next
    }

    // ================================================================ layout

    @Composable
    fun Toolbar() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toolDefs.forEach { id ->
                    PixButton(label = toolLabel(id), active = tool == id, onClick = { setTool(id) })
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PixButton(label = t("撤销"), enabled = canUndo, onClick = { undo() })
                PixButton(label = t("重做"), enabled = canRedo, onClick = { redo() })
                PixButton(label = t("导入图像"), enabled = busy == "idle", onClick = { imagePicker.launch(arrayOf("*/*")) })
                PixButton(label = t("打开项目"), enabled = busy == "idle", onClick = { projectPicker.launch(arrayOf("*/*")) })
                Text(t("分辨率"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixSelect(
                    options = listOf("300" to "300", "600" to "600"),
                    value = project.layout.exportDpi.toString(),
                    onChange = { v -> setLayout { it.copy(exportDpi = if (v == "600") 600 else 300) } },
                )
                PixButton(label = "PNG", ok = true, enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { exportPng() })
                PixButton(label = "SVG", ok = true, enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { exportSvg() })
                PixButton(label = t("发送到 Figure Studio"), enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { sendToFigureNow() })
                PixButton(label = "JSON", enabled = busy == "idle", onClick = { exportProjectJson() })
                PixButton(label = t("新建项目"), danger = true, enabled = busy == "idle", onClick = { showNewConfirm = true })
            }
        }
    }

    @Composable
    fun PanelList(modifier: Modifier) {
        Column(modifier.padding(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("面板"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(interp("{n} 个面板", "n" to project.panels.size), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            PixButton(label = t("添加图像"), enabled = busy == "idle", onClick = { imagePicker.launch(arrayOf("*/*")) })
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(project.panels.size) { index ->
                    val panel = project.panels[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (panel.id == selectedPanelId) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else MaterialTheme.colorScheme.surface,
                            )
                            .border(
                                1.dp,
                                if (panel.id == selectedPanelId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(2.dp),
                            )
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val thumb = remember(panel.id, panel.dataUrl) { loadImageBitmap(panel.dataUrl).asImageBitmap() }
                        Box(
                            Modifier
                                .size(64.dp)
                                .background(Color(0xFF04060A))
                                .clickable {
                                    selectedPanelId = panel.id
                                    selectedAnnId = null
                                },
                        ) {
                            Image(bitmap = thumb, contentDescription = panel.name, modifier = Modifier.fillMaxSize())
                            Text(
                                panelLabel(index, project.layout.labelStyle),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD23F),
                                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xB8000000)).padding(horizontal = 3.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            PixTextField(value = panel.name, placeholder = "", onChange = { name ->
                                setPanelById(panel.id, { it.copy(name = name) })
                            })
                            Text(
                                "${panel.width} × ${panel.height} px",
                                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                PixButton(label = "↑", enabled = index > 0, onClick = { movePanel(index, -1) })
                                PixButton(label = "↓", enabled = index < project.panels.size - 1, onClick = { movePanel(index, 1) })
                                PixButton(label = t("复制"), onClick = { duplicatePanel(index) })
                                PixButton(label = t("移除"), danger = true, onClick = { removePanel(index) })
                            }
                        }
                    }
                }
            }
            Text(
                t("将 PNG、JPEG、WebP 或 TIFF 文件拖放到此处。"),
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }

    /** 原版 range-row：label + slider + output。 */
    @Composable
    fun SliderRow(
        label: String,
        value: Double,
        min: Double,
        max: Double,
        enabled: Boolean = true,
        output: String,
        onValue: (Double) -> Unit,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
            Slider(
                value = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f),
                onValueChange = { f -> onValue(min + (max - min) * f.toDouble()) },
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            )
            Text(output, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(44.dp))
        }
    }

    /** 小网格（原版 .grid2）。 */
    @Composable
    fun Grid2(content: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { content() }
        }
    }

    @Composable
    fun Field(label: String, value: String, onCommit: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(label, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PixNumberField(value = value, onCommit = onCommit)
        }
    }

    @Composable
    fun ColorField(label: String, value: String, onValue: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text("$label (#hex)", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PixTextField(value = value, placeholder = "#111111", onChange = onValue)
        }
    }

    @Composable
    fun LayoutSectionBody() {
        val layout = project.layout
        // 名称（原版 bind:value + focus/blur gesture；安卓即时更新）
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(t("图形名称"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PixTextField(value = project.name, placeholder = "", onChange = { name ->
                applyNoCommit { p -> p.copy(name = name) }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Field(t("列数"), layout.columns.toString()) { raw ->
                    raw.toDoubleOrNull()?.let { v ->
                        setLayout { it.copy(columns = clamp(kotlin.math.round(v), 1.0, 12.0).toInt()) }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(t("面板顺序"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixSelect(
                    options = listOf("row" to t("按行"), "column" to t("按列")),
                    value = layout.flow,
                    onChange = { v -> setLayout { it.copy(flow = if (v == "column") "column" else "row") } },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Field("${t("间距")} (${t("mm")})", jsFixed(layout.gapMm, 1)) { raw ->
                    raw.toDoubleOrNull()?.let { v -> setLayout { it.copy(gapMm = clamp(v, 0.0, 30.0)) } }
                }
            }
            Column(Modifier.weight(1f)) {
                Field("${t("边距")} (${t("mm")})", jsFixed(layout.marginMm, 1)) { raw ->
                    raw.toDoubleOrNull()?.let { v -> setLayout { it.copy(marginMm = clamp(v, 0.0, 40.0)) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Field("${t("图形宽度")} (${t("mm")})", jsFixed(layout.figureWidthMm, 1)) { raw ->
                    raw.toDoubleOrNull()?.let { v -> setLayout { it.copy(figureWidthMm = clamp(v, 40.0, 400.0)) } }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(t("字体"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixSelect(
                    options = listOf(
                        "Arial" to "Arial",
                        "Helvetica" to "Helvetica",
                        "Times New Roman" to "Times New Roman",
                        "Calibri" to "Calibri",
                        "Cambria" to "Cambria",
                    ),
                    value = layout.fontFamily,
                    onChange = { v -> setLayout { it.copy(fontFamily = v) } },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t("标签样式"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixSelect(
                    options = listOf(
                        "upper" to t("A B C"), "lower" to t("a b c"), "number" to t("1 2 3"),
                    ),
                    value = layout.labelStyle,
                    onChange = { v ->
                        setLayout {
                            it.copy(labelStyle = when (v) {
                                "lower" -> "lower"; "number" -> "number"; else -> "upper"
                            })
                        }
                    },
                )
            }
            Column(Modifier.weight(1f)) {
                Text(t("标签位置"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixSelect(
                    options = listOf(
                        "tl" to t("左上"), "tc" to t("上中"), "tr" to t("右上"),
                        "bl" to t("左下"), "bc" to t("下中"), "br" to t("右下"),
                    ),
                    value = layout.labelPosition,
                    onChange = { v -> setLayout { it.copy(labelPosition = v) } },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Field("${t("标签大小")} (${t("pt")})", jsFixed(layout.labelFontSizePt, 1)) { raw ->
                    raw.toDoubleOrNull()?.let { v -> setLayout { it.copy(labelFontSizePt = clamp(v, 4.0, 36.0)) } }
                }
            }
            Column(Modifier.weight(1f)) {
                ColorField(t("标签颜色"), layout.labelColor) { v ->
                    applyNoCommit { p -> p.copy(layout = p.layout.copy(labelColor = v)) }
                }
            }
        }
        ColorField(t("背景"), layout.background) { v ->
            applyNoCommit { p -> p.copy(layout = p.layout.copy(background = v)) }
        }
        Text(
            "${t("图形尺寸")}: ${jsFixed(layout.figureWidthMm, 1)} × ${jsFixed(figureHeightMm, 1)} ${t("mm")}",
            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    @Composable
    fun PanelSectionBody() {
        val panel = selectedPanel ?: return
        Column(Modifier.fillMaxWidth()) {
            Text(t("面板名称"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PixTextField(value = panel.name, placeholder = "", onChange = { name ->
                setPanelById(panel.id, { it.copy(name = name) })
            })
            Text(
                "${t("原始尺寸")}: ${panel.width} × ${panel.height} px",
                fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )

            Text(t("裁剪 (%)"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Field(t("左"), percentValue(panel.crop.left)) { raw -> raw.toDoubleOrNull()?.let { setCrop(panel, "left", it) } }
                }
                Column(Modifier.weight(1f)) {
                    Field(t("右"), percentValue(panel.crop.right)) { raw -> raw.toDoubleOrNull()?.let { setCrop(panel, "right", it) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Field(t("上"), percentValue(panel.crop.top)) { raw -> raw.toDoubleOrNull()?.let { setCrop(panel, "top", it) } }
                }
                Column(Modifier.weight(1f)) {
                    Field(t("下"), percentValue(panel.crop.bottom)) { raw -> raw.toDoubleOrNull()?.let { setCrop(panel, "bottom", it) } }
                }
            }
            PixButton(label = t("重置裁剪"), onClick = {
                setPanelById(panel.id, { it.copy(crop = com.spice.nativeapp.tools.annotate.CropRect(0.0, 0.0, 0.0, 0.0)) })
            })

            Text(t("视图"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            SliderRow(t("缩放"), panel.zoom, 1.0, 8.0, output = "${jsFixed(panel.zoom, 2)}×") { v ->
                setPanelById(panel.id, { it.copy(zoom = v) }, commit = false)
            }
            SliderRow(t("水平平移"), panel.offsetX, -1.0, 1.0, enabled = panel.zoom > 1.001, output = jsFixed(panel.offsetX, 2)) { v ->
                setPanelById(panel.id, { it.copy(offsetX = v) }, commit = false)
            }
            SliderRow(t("垂直平移"), panel.offsetY, -1.0, 1.0, enabled = panel.zoom > 1.001, output = jsFixed(panel.offsetY, 2)) { v ->
                setPanelById(panel.id, { it.copy(offsetY = v) }, commit = false)
            }
            PixButton(label = t("重置视图"), onClick = {
                setPanelById(panel.id, { it.copy(zoom = 1.0, offsetX = 0.0, offsetY = 0.0) })
            })

            Text(t("图像调整"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            SliderRow(t("亮度"), panel.brightness, 10.0, 300.0, output = "${panel.brightness.roundToInt()}%") { v ->
                setPanelById(panel.id, { it.copy(brightness = v) }, commit = false)
            }
            SliderRow(t("对比度"), panel.contrast, 10.0, 300.0, output = "${panel.contrast.roundToInt()}%") { v ->
                setPanelById(panel.id, { it.copy(contrast = v) }, commit = false)
            }
            PixButton(label = t("重置图像"), onClick = {
                setPanelById(panel.id, { it.copy(brightness = 100.0, contrast = 100.0) })
            })

            Text(t("RGB 通道"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            listOf(
                Triple("R", panel.channels.r, 0), Triple("G", panel.channels.g, 1), Triple("B", panel.channels.b, 2),
            ).forEach { (label, channel, idx) ->
                fun updChannel(transform: (ChannelSetting) -> ChannelSetting) {
                    setPanelById(panel.id, {
                        it.copy(
                            channels = when (idx) {
                                0 -> it.channels.copy(r = transform(it.channels.r))
                                1 -> it.channels.copy(g = transform(it.channels.g))
                                else -> it.channels.copy(b = transform(it.channels.b))
                            },
                        )
                    }, commit = true)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = channel.show, onCheckedChange = { v -> updChannel { it.copy(show = v) } }, modifier = Modifier.size(24.dp))
                    Text(label, fontSize = 9.sp, modifier = Modifier.width(16.dp))
                    Slider(
                        value = channel.gain.toFloat(),
                        onValueChange = { f ->
                            // 原版 oninput 直写（无历史）
                            val v = clamp(f.toDouble(), 0.0, 2.0)
                            setPanelById(panel.id, {
                                it.copy(
                                    channels = when (idx) {
                                        0 -> it.channels.copy(r = it.channels.r.copy(gain = v))
                                        1 -> it.channels.copy(g = it.channels.g.copy(gain = v))
                                        else -> it.channels.copy(b = it.channels.b.copy(gain = v))
                                    },
                                )
                            }, commit = false)
                        },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    )
                    Text(jsFixed(channel.gain, 2), fontSize = 9.sp, modifier = Modifier.width(30.dp), color = MaterialTheme.colorScheme.onSurface)
                    PixCheck(label = t("反转"), checked = channel.invert, onChange = { v -> updChannel { it.copy(invert = v) } })
                }
            }
            PixButton(label = t("重置通道"), onClick = {
                setPanelById(panel.id, { it.copy(channels = com.spice.nativeapp.tools.annotate.createDefaultChannels()) })
            })
            Text(
                t("增益范围为 0 至 2。隐藏的通道不会出现在预览、标注和导出中。"),
                fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Text(t("校准"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            Text(
                calibrationText(panel),
                fontSize = 9.sp,
                color = if (panel.calibration != null) Color(0xFF69E77A) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    .padding(3.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(t("校准单位"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        options = listOf("um" to "µm", "mm" to "mm", "nm" to "nm", "px" to "px"),
                        value = calibrateUnit,
                        onChange = { next ->
                            calibrateUnit = next
                            val converted = panel.calibration?.let { convertCalibration(it, next) }
                            if (converted != null) {
                                setPanelById(panel.id, { it.copy(calibration = converted) })
                            }
                        },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Field("${t("每单位像素数")} (${unitSymbol(calibrateUnit)})", if (panel.calibration != null && panel.calibration.unit == calibrateUnit) {
                        // JS Number(pxPerUnit.toPrecision(6))
                        panel.calibration.pxPerUnit.let {
                            if (it == 0.0) "0" else {
                                val exp = kotlin.math.floor(kotlin.math.log10(abs(it)))
                                val digits = (6 - 1 - exp).toInt()
                                if (digits > 0) jsFixed(it, digits).trimEnd('0').trimEnd('.')
                                else {
                                    val factor = 10.0.pow(-digits.toDouble())
                                    jsFixed(kotlin.math.round(it / factor) * factor, 0).trimEnd('0').trimEnd('.')
                                }
                            }
                        }
                    } else "") { raw ->
                        raw.toDoubleOrNull()?.let { v ->
                            if (v > 0) setPanelById(panel.id, { it.copy(calibration = Calibration(calibrateUnit, v)) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Field("${t("已知长度")} (${unitSymbol(calibrateUnit)})", toPrecisionString(calibrateKnown)) { raw ->
                        raw.toDoubleOrNull()?.let { calibrateKnown = it }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Spacer(Modifier.height(12.dp))
                    PixButton(label = t("画线"), onClick = { startCalibration(panel) })
                }
            }
            Text(
                t("输入已知长度，然后沿同样长度的特征拖动一条线。校准按面板分别保存。"),
                fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            if (panel.calibration != null) {
                PixButton(label = t("清除校准"), danger = true, onClick = {
                    setPanelById(panel.id, { it.copy(calibration = null) })
                })
            }
        }
    }

    @Composable
    fun AnnEditor(ann: Annotation) {
        Column(Modifier.fillMaxWidth()) {
            when (ann) {
                is ArrowAnnotation -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("X1")} (${t("mm")})", mmValue(ann.x1)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(x1 = pxFromMm(it)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("Y1")} (${t("mm")})", mmValue(ann.y1)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(y1 = pxFromMm(it)) }) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("X2")} (${t("mm")})", mmValue(ann.x2)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(x2 = pxFromMm(it)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("Y2")} (${t("mm")})", mmValue(ann.y2)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(y2 = pxFromMm(it)) }) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("线宽")} (${t("pt")})", jsFixed(ann.strokeWidthPt, 2)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(strokeWidthPt = clamp(it, 0.25, 12.0)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("箭头大小")} (${t("pt")})", jsFixed(ann.headSizePt, 1)) { raw -> raw.toDoubleOrNull()?.let { setAnnById(ann.id, { a -> (a as ArrowAnnotation).copy(headSizePt = clamp(it, 2.0, 40.0)) }) } }
                        }
                    }
                }
                is RectAnnotation, is EllipseAnnotation -> {
                    val (x, y, w, h, sw) = when (ann) {
                        is RectAnnotation -> listOf(ann.x, ann.y, ann.w, ann.h, ann.strokeWidthPt).also { }
                        is EllipseAnnotation -> listOf(ann.x, ann.y, ann.w, ann.h, ann.strokeWidthPt).also { }
                        else -> listOf(0.0, 0.0, 0.0, 0.0, 0.0)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("X")} (${t("mm")})", mmValue(x)) { raw ->
                                raw.toDoubleOrNull()?.let { v ->
                                    setAnnById(ann.id, { a ->
                                        when (a) {
                                            is RectAnnotation -> a.copy(x = pxFromMm(v))
                                            is EllipseAnnotation -> a.copy(x = pxFromMm(v))
                                            else -> a
                                        }
                                    })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("Y")} (${t("mm")})", mmValue(y)) { raw ->
                                raw.toDoubleOrNull()?.let { v ->
                                    setAnnById(ann.id, { a ->
                                        when (a) {
                                            is RectAnnotation -> a.copy(y = pxFromMm(v))
                                            is EllipseAnnotation -> a.copy(y = pxFromMm(v))
                                            else -> a
                                        }
                                    })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("宽度")} (${t("mm")})", mmValue(w)) { raw ->
                                raw.toDoubleOrNull()?.let { v ->
                                    setAnnById(ann.id, { a ->
                                        when (a) {
                                            is RectAnnotation -> a.copy(w = max(1.0, pxFromMm(v)))
                                            is EllipseAnnotation -> a.copy(w = max(1.0, pxFromMm(v)))
                                            else -> a
                                        }
                                    })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("高度")} (${t("mm")})", mmValue(h)) { raw ->
                                raw.toDoubleOrNull()?.let { v ->
                                    setAnnById(ann.id, { a ->
                                        when (a) {
                                            is RectAnnotation -> a.copy(h = max(1.0, pxFromMm(v)))
                                            is EllipseAnnotation -> a.copy(h = max(1.0, pxFromMm(v)))
                                            else -> a
                                        }
                                    })
                                }
                            }
                        }
                    }
                    Field("${t("线宽")} (${t("pt")})", jsFixed(sw, 2)) { raw ->
                        raw.toDoubleOrNull()?.let { v ->
                            setAnnById(ann.id, { a ->
                                when (a) {
                                    is RectAnnotation -> a.copy(strokeWidthPt = clamp(v, 0.25, 12.0))
                                    is EllipseAnnotation -> a.copy(strokeWidthPt = clamp(v, 0.25, 12.0))
                                    else -> a
                                }
                            })
                        }
                    }
                    val fill = when (ann) {
                        is RectAnnotation -> ann.fill
                        is EllipseAnnotation -> ann.fill
                        else -> "none"
                    }
                    PixCheck(label = t("填充"), checked = fill != "none", onChange = { v ->
                        setAnnById(ann.id, { a ->
                            val next = if (v) "#ffd23f" else "none"
                            when (a) {
                                is RectAnnotation -> a.copy(fill = next)
                                is EllipseAnnotation -> a.copy(fill = next)
                                else -> a
                            }
                        })
                    })
                    if (fill != "none") {
                        ColorField(t("填充"), fill) { v ->
                            setAnnById(ann.id, { a ->
                                when (a) {
                                    is RectAnnotation -> a.copy(fill = v)
                                    is EllipseAnnotation -> a.copy(fill = v)
                                    else -> a
                                }
                            }, commit = false)
                        }
                    }
                }
                is TextAnnotation -> {
                    Text(t("文本"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixTextField(value = ann.text, placeholder = "", onChange = { v ->
                        setAnnById(ann.id, { a -> (a as TextAnnotation).copy(text = v) }, commit = false)
                    })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("X")} (${t("mm")})", mmValue(ann.x)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as TextAnnotation).copy(x = pxFromMm(v)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("Y")} (${t("mm")})", mmValue(ann.y)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as TextAnnotation).copy(y = pxFromMm(v)) }) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("字号")} (${t("pt")})", jsFixed(ann.fontSizePt, 1)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as TextAnnotation).copy(fontSizePt = clamp(v, 4.0, 96.0)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("内边距")} (${t("pt")})", jsFixed(ann.paddingPt, 1)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as TextAnnotation).copy(paddingPt = clamp(v, 0.0, 20.0)) }) } }
                        }
                    }
                    PixCheck(label = t("背景"), checked = ann.background != "none", onChange = { v ->
                        setAnnById(ann.id, { a -> (a as TextAnnotation).copy(background = if (v) "#ffffff" else "none") })
                    })
                    if (ann.background != "none") {
                        ColorField(t("背景"), ann.background) { v ->
                            setAnnById(ann.id, { a -> (a as TextAnnotation).copy(background = v) }, commit = false)
                        }
                    }
                }
                is ScaleBarAnnotation -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t("比例尺模式"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixSelect(
                                options = listOf("calibrated" to t("按校准"), "fixed" to t("固定长度")),
                                value = ann.mode,
                                onChange = { v -> setScaleMode(ann, if (v == "fixed") "fixed" else "calibrated") },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Field(t("数值"), formatValue(ann.value)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(value = max(0.001, v)) }) } }
                        }
                    }
                    if (ann.mode == "calibrated") {
                        Text(t("校准面板"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(
                            options = project.panels.mapIndexed { index, p -> p.id to "${panelLabel(index, project.layout.labelStyle)} · ${p.name}" },
                            value = ann.panelId,
                            onChange = { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(panelId = v) }) },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("单位"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PixSelect(
                                    options = listOf("um" to "µm", "mm" to "mm", "nm" to "nm", "px" to "px"),
                                    value = ann.unit,
                                    onChange = { v -> setBarUnit(ann.id, v) },
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(t("比例尺长度"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    barLengthText(ann), fontSize = 9.sp, color = Color(0xFF4CD6FF),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)).padding(3.dp),
                                )
                            }
                        }
                        if (barLayout(ann, geom) == null) {
                            Text(t("请先校准该面板，才能使用校准比例尺。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text(
                            "${t("固定长度")}: ${t("比例尺长度")} = ${barLengthText(ann)}",
                            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("X")} (${t("mm")})", mmValue(ann.x)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(x = pxFromMm(v)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("Y")} (${t("mm")})", mmValue(ann.y)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(y = pxFromMm(v)) }) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("线粗")} (${t("pt")})", jsFixed(ann.thicknessPt, 2)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(thicknessPt = clamp(v, 0.25, 16.0)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("字号")} (${t("pt")})", jsFixed(ann.fontSizePt, 1)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(fontSizePt = clamp(v, 4.0, 48.0)) }) } }
                        }
                    }
                    PixCheck(label = t("显示标签"), checked = ann.showLabel, onChange = { v ->
                        setAnnById(ann.id, { a -> (a as ScaleBarAnnotation).copy(showLabel = v) })
                    })
                }
                is ProfileAnnotation -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field(t("采样点"), ann.samples.toString()) { raw ->
                                raw.toDoubleOrNull()?.let { v ->
                                    setAnnById(ann.id, { a -> (a as ProfileAnnotation).copy(samples = clamp(kotlin.math.round(v), PROFILE_MIN_SAMPLES.toDouble(), PROFILE_MAX_SAMPLES.toDouble()).toInt()) })
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${t("线长")} (${t("mm")})", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                jsFixed(hypot(ann.x2 - ann.x1, ann.y2 - ann.y1) / PX_PER_MM, 2),
                                fontSize = 9.sp, color = Color(0xFF4CD6FF),
                                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)).padding(3.dp),
                            )
                        }
                    }
                    Text(t("强度剖面"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (profileBusy) {
                        Text(t("正在采样合成图..."), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (profileSeries?.annotationId == ann.id) {
                        val series = profileSeries ?: return
                        // 原版 160×52 profilePlotPath
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(160f / 52f)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp))
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            val pad = 2f
                            val innerW = size.width - pad * 2
                            val innerH = size.height - pad * 2
                            val path = androidx.compose.ui.graphics.Path()
                            series.points.forEachIndexed { index, point ->
                                val x = pad + (index.toFloat() / max(1f, (series.points.size - 1).toFloat())) * innerW
                                val y = pad + (1f - point.normalized.toFloat()) * innerH
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, color = try {
                                Color(android.graphics.Color.parseColor(ann.color))
                            } catch (e: Exception) {
                                Color(0xFFFFD23F)
                            }, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        }
                        Text(
                            "${t("最小 / 平均 / 最大")}: ${profileStatsText(series)}",
                            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            t("在渲染后的 RGB 合成图上沿直线等距采样。"),
                            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(t("尚无剖面数据。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is PanelBarAnnotation -> {
                    Text(t("校准面板"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        options = project.panels.mapIndexed { index, p -> p.id to "${panelLabel(index, project.layout.labelStyle)} · ${p.name}" },
                        value = ann.panelId,
                        onChange = { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(panelId = v) }) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field(t("数值"), formatValue(ann.value)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(value = max(0.001, v)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t("单位"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixSelect(
                                options = listOf("um" to "µm", "mm" to "mm", "nm" to "nm", "px" to "px"),
                                value = ann.unit,
                                onChange = { v -> setBarUnit(ann.id, v) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("位置 X")} (%)", percentValue(ann.nx)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(nx = clamp(v / 100, 0.0, 1.0)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("位置 Y")} (%)", percentValue(ann.ny)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(ny = clamp(v / 100, 0.0, 1.0)) }) } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Field("${t("线粗")} (${t("pt")})", jsFixed(ann.thicknessPt, 2)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(thicknessPt = clamp(v, 0.25, 16.0)) }) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Field("${t("字号")} (${t("pt")})", jsFixed(ann.fontSizePt, 1)) { raw -> raw.toDoubleOrNull()?.let { v -> setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(fontSizePt = clamp(v, 4.0, 48.0)) }) } }
                        }
                    }
                    PixCheck(label = t("显示标签"), checked = ann.showLabel, onChange = { v ->
                        setAnnById(ann.id, { a -> (a as PanelBarAnnotation).copy(showLabel = v) })
                    })
                    Text(t("比例尺长度"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        barLengthText(ann), fontSize = 9.sp, color = Color(0xFF4CD6FF),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)).padding(3.dp),
                    )
                    if (barLayout(ann, geom) == null) {
                        Text(t("请先校准该面板，才能使用校准比例尺。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 通用：颜色 + 复制/删除
            ColorField(t("颜色"), ann.color) { v ->
                setAnnById(ann.id, { a -> setColorOf(a, v) }, commit = false)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PixButton(label = t("复制"), onClick = { duplicateAnnotationById(ann.id) })
                PixButton(label = t("删除"), danger = true, onClick = { deleteAnnotationById(ann.id) })
            }
        }
    }

    @Composable
    fun AnnotationsSectionBody() {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                PixButton(label = t("箭头"), active = tool == "arrow", onClick = { setTool("arrow") })
                PixButton(label = t("矩形"), active = tool == "rect", onClick = { setTool("rect") })
                PixButton(label = t("椭圆"), active = tool == "ellipse", onClick = { setTool("ellipse") })
                PixButton(label = t("文本"), active = tool == "text", onClick = { setTool("text") })
                PixButton(label = t("比例尺"), active = tool == "scalebar", onClick = { setTool("scalebar") })
                PixButton(label = t("面板比例尺"), active = tool == "panelbar", onClick = { setTool("panelbar") })
                PixButton(label = t("强度剖面"), active = tool == "profile", onClick = { setTool("profile") })
            }

            if (project.annotations.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxWidth().height(180.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(project.annotations.size) { index ->
                        val ann = project.annotations[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (ann.id == selectedAnnId) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .border(
                                    1.dp,
                                    if (ann.id == selectedAnnId) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(1.dp),
                                )
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                annSummary(ann),
                                fontSize = 8.5.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedAnnId = ann.id
                                        val panelId = when (ann) {
                                            is PanelBarAnnotation -> ann.panelId
                                            is ScaleBarAnnotation -> ann.panelId
                                            else -> null
                                        }
                                        if (panelId != null && panelId.isNotEmpty()) selectedPanelId = panelId
                                    }
                                    .padding(2.dp),
                            )
                            Text(
                                t("复制"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { duplicateAnnotationById(ann.id) }.padding(2.dp),
                            )
                            Text(
                                t("删除"), fontSize = 8.sp, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { deleteAnnotationById(ann.id) }.padding(2.dp),
                            )
                        }
                    }
                }
            } else {
                Text(t("尚无标注。选择工具并点击图形。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val editorAnn = selectedAnn
            if (editorAnn != null) {
                Column(
                    Modifier.fillMaxWidth().padding(4.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    AnnEditor(editorAnn)
                }
            }
        }
    }

    @Composable
    fun ExportSectionBody() {
        Column(Modifier.fillMaxWidth()) {
            Text(t("分辨率"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PixSelect(
                options = listOf("300" to "300", "600" to "600"),
                value = project.layout.exportDpi.toString(),
                onChange = { v -> setLayout { it.copy(exportDpi = if (v == "600") 600 else 300) } },
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PixButton(label = t("导出 PNG"), ok = true, enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { exportPng() }, modifier = Modifier.weight(1f))
                PixButton(label = t("导出 SVG"), ok = true, enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { exportSvg() }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PixButton(label = t("保存项目"), enabled = busy == "idle", onClick = { exportProjectJson() }, modifier = Modifier.weight(1f))
                PixButton(
                    label = t("剖面 CSV"),
                    enabled = busy == "idle" && annotateProfileAnnotations(project).isNotEmpty(),
                    onClick = { exportProfilesCsv() }, modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PixButton(label = t("可复现包"), accent = true, enabled = busy == "idle" && project.panels.isNotEmpty(), onClick = { exportBundle() }, modifier = Modifier.weight(1f))
                PixButton(label = t("打开项目"), enabled = busy == "idle", onClick = { projectPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f))
            }
            Text(
                when (autosaveState) {
                    "saving" -> t("正在本地保存…")
                    "saved" -> "${t("已保存到本地")} $savedAt"
                    "failed" -> t("自动保存失败。请导出项目 JSON 以保留工作。")
                    else -> t("自动保存已开启")
                },
                fontSize = 8.5.sp,
                color = when (autosaveState) {
                    "saved" -> Color(0xFF69E77A)
                    "failed" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            if (busy == "bundle") {
                Text(t("正在构建可复现包..."), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (busy != "idle") {
                Text(t("处理中…"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    fun Inspector(modifier: Modifier) {
        Column(
            modifier
                .padding(4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            GelSection(title = t("图形布局"), startOpen = true) { LayoutSectionBody() }
            GelSection(title = t("所选面板"), startOpen = selectedPanel != null) {
                if (selectedPanel == null) {
                    Text(t("在左侧列表中选择面板，以编辑裁剪、视图和校准。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    PanelSectionBody()
                }
            }
            GelSection(title = t("标注"), startOpen = selectedAnn != null) { AnnotationsSectionBody() }
            GelSection(title = t("导出"), startOpen = false) { ExportSectionBody() }
        }
    }

    @Composable
    fun Stage(modifier: Modifier) {
        val density = LocalDensity.current
        Column(modifier.padding(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${t("图形预览")} · ${jsFixed(project.layout.figureWidthMm, 1)} × ${jsFixed(figureHeightMm, 1)} ${t("mm")}",
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixButton(label = "−", onClick = { zoomFactor = clamp(zoomFactor / 1.25, 0.25, 5.0) })
                    Text("${(viewScaleState * 100).roundToInt()}%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixButton(label = "+", onClick = { zoomFactor = clamp(zoomFactor * 1.25, 0.25, 5.0) })
                    PixButton(label = t("适应"), onClick = { zoomFactor = 1.0 })
                }
            }
            Spacer(Modifier.height(2.dp))
            var stageWdp by remember { mutableStateOf(0f) }
            var stageHdp by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF04060A))
                    .onSizeChanged { size ->
                        with(density) {
                            stageWdp = size.width.toDp().value
                            stageHdp = size.height.toDp().value
                        }
                    },
            ) {
                val scrollState = rememberScrollState()
                val hScrollState = rememberScrollState()
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .horizontalScroll(hScrollState)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (project.panels.isEmpty()) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(t("构建图形"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                t("导入显微图像即可开始。面板会自动排列，所有标注都会随图形一起导出。"),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PixButton(label = t("添加图像"), accent = true, onClick = { imagePicker.launch(arrayOf("*/*")) })
                            Text(t("将 PNG、JPEG、WebP 或 TIFF 文件拖放到此处。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        // fit scale：原版 stageBox-30、上限 1.6、下限 0.02。
                        val availableW = max(160.0, stageWdp - 30.0)
                        val availableH = max(160.0, stageHdp - 30.0)
                        val fitScale = min(
                            1.6,
                            max(0.02, min(availableW / max(1.0, geom.width), availableH / max(1.0, geom.height))),
                        )
                        val viewScale = fitScale * zoomFactor
                        LaunchedEffect(viewScale) { viewScaleState = viewScale }
                        val cssW = max(1.0, geom.width * viewScale)
                        val cssH = max(1.0, geom.height * viewScale)
                        Canvas(
                            Modifier
                                .size(with(density) { cssW.toFloat().toDp() }, with(density) { cssH.toFloat().toDp() })
                                .background(androidx.compose.ui.graphics.Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .pointerInput(tool, viewScale, geom.width, geom.height) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        fun toFig(offset: Offset): com.spice.nativeapp.tools.annotate.Point =
                                            com.spice.nativeapp.tools.annotate.Point(
                                                offset.x / (viewScale * density.density),
                                                offset.y / (viewScale * density.density),
                                            )
                                        onCanvasPointerDown(toFig(down.position))
                                        var handled = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (change.changedToUp()) {
                                                onCanvasPointerMove(toFig(change.position))
                                                onCanvasPointerUp()
                                                handled = true
                                                break
                                            }
                                            if (change.positionChange() != Offset.Zero) {
                                                change.consume()
                                                onCanvasPointerMove(toFig(change.position))
                                            }
                                        }
                                        if (!handled) onCanvasPointerUp()
                                    }
                                },
                        ) {
                            val nc = drawContext.canvas.nativeCanvas
                            val pxScale = viewScale * density.density
                            nc.save()
                            nc.scale(pxScale.toFloat(), pxScale.toFloat())
                            drawFigure(nc, project, geom, { panel -> previewArts[panel.id] }, meta)
                            drawSelection(
                                nc, project, geom, meta,
                                SelectionInfo(selectedAnnId, selectedPanelId),
                                viewScale,
                            )
                            val currentDrag = drag
                            if (currentDrag is AnnotateDrawDrag) {
                                drawDraft(
                                    nc,
                                    DraftShape(
                                        if (currentDrag.tool == "profile") "line" else currentDrag.tool,
                                        currentDrag.start, currentDrag.end,
                                    ),
                                    viewScale,
                                )
                            } else if (currentDrag is AnnotateCalibrateDrag) {
                                drawDraft(nc, DraftShape("line", currentDrag.start, currentDrag.end), viewScale)
                            }
                            nc.restore()
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.primary)
        }
    }

    // ================================================================ root

    BoxWithConstraints {
        val wide = maxWidth > 1000.dp
        Column(Modifier.fillMaxSize()) {
            Toolbar()
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    PanelList(Modifier.width(238.dp).fillMaxSize())
                    Stage(Modifier.weight(1f))
                    Inspector(Modifier.width(332.dp))
                }
            } else {
                // 触屏：上下堆叠（面板列表 → stage → 检查器），语义不变。
                PanelList(Modifier.fillMaxWidth().height(220.dp))
                Stage(Modifier.fillMaxWidth().height(380.dp))
                Inspector(Modifier.fillMaxWidth())
            }
        }
    }

    // 新建项目确认（原版 confirm()）。
    if (showNewConfirm) {
        AlertDialog(
            onDismissRequest = { showNewConfirm = false },
            title = { Text(t("新建项目")) },
            text = { Text(t("新建项目？当前项目将从此浏览器中移除。")) },
            confirmButton = {
                TextButton(onClick = {
                    showNewConfirm = false
                    newProject()
                }) { Text(t("新建项目")) }
            },
            dismissButton = {
                TextButton(onClick = { showNewConfirm = false }) { Text(t("取消")) }
            },
        )
    }
}

/** 原版 Number(x.toPrecision(6)) 字符串形式。 */
private fun toPrecisionString(value: Double): String {
    if (value == 0.0) return "0"
    val exp = kotlin.math.floor(kotlin.math.log10(abs(value)))
    val digits = (6 - 1 - exp).toInt()
    return if (digits > 0) {
        jsFixed2(value, digits).trimEnd('0').trimEnd('.')
    } else {
        val factor = 10.0.pow(-digits.toDouble())
        val scaled = kotlin.math.round(value / factor) * factor
        if (abs(scaled) >= 1e16 || abs(scaled) < 1e-6) String.format(Locale.US, "%.3e", scaled)
        else jsFixed2(scaled, 0)
    }
}

private fun jsFixed2(value: Double, digits: Int): String =
    String.format(Locale.US, "%." + digits + "f", value)

private fun setColorOf(a: Annotation, color: String): Annotation = when (a) {
    is ArrowAnnotation -> a.copy(color = color)
    is RectAnnotation -> a.copy(color = color)
    is EllipseAnnotation -> a.copy(color = color)
    is TextAnnotation -> a.copy(color = color)
    is ScaleBarAnnotation -> a.copy(color = color)
    is PanelBarAnnotation -> a.copy(color = color)
    is ProfileAnnotation -> a.copy(color = color)
    else -> a
}
