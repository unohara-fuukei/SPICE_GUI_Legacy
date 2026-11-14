package com.spice.nativeapp.tools

/*
 * Gel figure editor —— 1:1 移植自原版 src/lib/tools/gel/GelEditor.svelte。
 * UI 词条全部使用 main zh 原文（I18n.t 直查 core 词典；命名占位符用 gi() 本地插值）。
 *
 * 平台差异（README Known gaps 披露）：
 * - ToolProjectBar / projectStore（项目中心 handoff）无安卓侧等价物；
 * - sendImageToFigure（跨工具实时桥）→ PNG 落盘 files/figures/；
 * - 文件拖放（onDrop）与桌面键盘快捷键（Ctrl+Z/Y/S、H、Delete、方向键）不适用触屏；
 * - localStorage → SharedPreferences（GelStorage）；Web Worker → 协程 Dispatchers.Default；
 * - 双击条带切换标签 → 300ms 内两次点按（触屏）。
 */
import android.graphics.Bitmap
import android.graphics.Canvas as GCanvas
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.GelGenie
import com.spice.nativeapp.core.I18n
import com.spice.nativeapp.core.Repro
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.tools.gel.DetectedLane
import com.spice.nativeapp.tools.gel.FigHit
import com.spice.nativeapp.tools.gel.FigItem
import com.spice.nativeapp.tools.gel.FigureLayout
import com.spice.nativeapp.tools.gel.GelAnnotation
import com.spice.nativeapp.tools.gel.GelBand
import com.spice.nativeapp.tools.gel.GelBatch
import com.spice.nativeapp.tools.gel.GelBatchPanel
import com.spice.nativeapp.tools.gel.ConditionRow
import com.spice.nativeapp.tools.gel.GraySizePoint
import com.spice.nativeapp.tools.gel.GelDetect
import com.spice.nativeapp.tools.gel.GelExport
import com.spice.nativeapp.tools.gel.GelFigure
import com.spice.nativeapp.tools.gel.GelGroup
import com.spice.nativeapp.tools.gel.GelLane
import com.spice.nativeapp.tools.gel.GelLadderFit
import com.spice.nativeapp.tools.gel.GelLadders
import com.spice.nativeapp.tools.gel.GelMath
import com.spice.nativeapp.tools.gel.GelPipeline
import com.spice.nativeapp.tools.gel.GelPresets
import com.spice.nativeapp.tools.gel.GelProject
import com.spice.nativeapp.tools.gel.GelProjectDoc
import com.spice.nativeapp.tools.gel.GelRender
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.GelStorage
import com.spice.nativeapp.tools.gel.GelTypes
import com.spice.nativeapp.tools.gel.LabeledSlider
import com.spice.nativeapp.tools.gel.LadderDef
import com.spice.nativeapp.tools.gel.PolyFit
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixLink
import com.spice.nativeapp.tools.gel.PixNumberField
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.PixTextField
import com.spice.nativeapp.tools.gel.ProcessedImage
import com.spice.nativeapp.tools.gel.SourceStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ helpers

private fun t(zh: String): String = I18n.t(zh)

private fun interp(template: String, vararg pairs: Pair<String, Any?>): String =
    com.spice.nativeapp.tools.gel.gi(template, *pairs)

private fun uriDisplayName(ctx: android.content.Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "file"
        } ?: "file"
    } catch (e: Exception) {
        "file"
    }
}

private fun uriMime(ctx: android.content.Context, uri: Uri): String? = try {
    ctx.contentResolver.getType(uri)
} catch (e: Exception) {
    null
}

private fun readUriText(ctx: android.content.Context, uri: Uri): String =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

private fun bitmapToPng(bitmap: Bitmap): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private data class EditingLane(val id: String, val value: String, val x: Float, val y: Float, val w: Float)

// ------------------------------------------------------------------ screen

@Composable
fun GelScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ------------------------------------------------------------- state
    var project by remember { mutableStateOf(GelTypes.emptyProject()) }
    var processed by remember { mutableStateOf<ProcessedImage?>(null) }
    var layout by remember { mutableStateOf<FigureLayout?>(null) }
    var imageEl by remember { mutableStateOf<Bitmap?>(null) }

    var busy by remember { mutableStateOf("") }
    var exportDpi by remember { mutableStateOf(300) }
    var showGrid by remember { mutableStateOf(false) }
    var linkGuides by remember { mutableStateOf(true) }
    var sourceZoom by remember { mutableStateOf(1.0) }
    var figScale by remember { mutableStateOf(1.0) }
    var selectedLaneId by remember { mutableStateOf<String?>(null) }
    var selectedBand by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var selectedAnnotationId by remember { mutableStateOf<String?>(null) }
    var editingLane by remember { mutableStateOf<EditingLane?>(null) }
    var preflightItems by remember { mutableStateOf<List<GelExport.PreflightItem>>(emptyList()) }
    var typeReasons by remember { mutableStateOf<List<String>>(emptyList()) }
    var detectStale by remember { mutableStateOf(false) }
    var confirmRedetect by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf<List<GelStorage.RecentEntry>>(emptyList()) }
    var styles by remember { mutableStateOf<List<GelStorage.GelStyle>>(emptyList()) }
    var recentEdit by remember { mutableStateOf<Pair<String, String>?>(null) }
    var styleName by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customSizes by remember { mutableStateOf("") }
    var bulkNames by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf("replace") }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var uiRev by remember { mutableIntStateOf(0) }

    val history = remember { ArrayList<String>() }
    val future = remember { ArrayList<String>() }
    var lastCheckpoint by remember { mutableStateOf(0L) }
    var persistJob by remember { mutableStateOf<Job?>(null) }
    var quotaWarned by remember { mutableStateOf(false) }
    var layoutJob by remember { mutableStateOf<Job?>(null) }
    var lastFigTapTime by remember { mutableStateOf(0L) }
    var lastFigTapKey by remember { mutableStateOf("") }

    class DragState {
        var type = "" // "lane" | "crop" | "band"
        var laneId: String? = null
        var index: Int? = null
        var edge = "" // left|right|top|bottom
    }

    val drag = remember { DragState() }
    var nameDragFrom by remember { mutableStateOf<String?>(null) }
    var nameDragMoved by remember { mutableStateOf(false) }
    var nameDragX0 by remember { mutableStateOf(0f) }
    var nameDragY0 by remember { mutableStateOf(0f) }

    class AnnDrag {
        var id = ""
        var isBox = false
        var startX = 0.0
        var startY = 0.0
        var x = 0.0
        var y = 0.0
    }

    val annDrag = remember { AnnDrag() }
    var annDragActive by remember { mutableStateOf(false) }

    // --------------------------------------------------------- invalidate
    fun scheduleLayout() {
        uiRev++
        val proc = processed ?: run { layout = null; preflightItems = emptyList(); return }
        layoutJob?.cancel()
        layoutJob = scope.launch(Dispatchers.Default) {
            val fig = GelFigure.buildFigure(project, proc)
            val items = GelExport.preflight(project, fig, proc, proc.rawGray, proc.gray)
            layout = fig
            preflightItems = items
        }
    }

    suspend fun refreshProcessed() = withContext(Dispatchers.Default) {
        val exp = GelTypes.activeExposure(project)
        if (exp == null) {
            processed = null
            layout = null
            return@withContext
        }
        if (imageEl == null) imageEl = GelPipeline.loadBitmapFromDataUrl(exp.dataUrl)
        if (imageEl == null) return@withContext
        processed = GelPipeline.renderProcessed(exp, project, imageEl!!)
        scheduleLayout()
    }

    fun schedulePixels() {
        uiRev++
        scope.launch { refreshProcessed() }
    }

    suspend fun loadActiveImage() {
        val exp = GelTypes.activeExposure(project)
        if (exp == null) {
            imageEl = null
            processed = null
            layout = null
            return
        }
        withContext(Dispatchers.Default) {
            imageEl = GelPipeline.loadBitmapFromDataUrl(exp.dataUrl)
        }
        refreshProcessed()
    }

    // ----------------------------------------------------------- history/io
    fun projectJson(): String = GelTypes.projectToJson(project).toString()

    fun plainClone(p: GelProject): GelProject =
        GelProjectDoc.normalizeProject(GelTypes.projectFromJson(JSONObject(GelTypes.projectToJson(p).toString())))

    fun checkpoint(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckpoint < 650) return
        history.add(projectJson())
        if (history.size > 80) history.removeAt(0)
        future.clear()
        lastCheckpoint = now
        canUndo = true
        canRedo = false
    }

    fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(800)
            val ok = withContext(Dispatchers.IO) { GelStorage.saveSnapshot(ctx, project) }
            if (!ok && !quotaWarned) {
                quotaWarned = true
                Toast.makeText(ctx, t("浏览器存储空间不足，自动保存失败；请手动保存工程"), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun commit(pixels: Boolean = false, fn: () -> Unit) {
        checkpoint()
        fn()
        project.updatedAt = java.time.Instant.now().toString()
        if (pixels) schedulePixels() else scheduleLayout()
        schedulePersist()
    }

    fun undo() {
        if (history.isEmpty()) return
        val prev = history.removeAt(history.size - 1)
        future.add(projectJson())
        project = GelTypes.projectFromJson(JSONObject(prev))
        canUndo = history.isNotEmpty()
        canRedo = true
        schedulePersist()
        scope.launch { loadActiveImage() }
    }

    fun redo() {
        if (future.isEmpty()) return
        val next = future.removeAt(future.size - 1)
        history.add(projectJson())
        project = GelTypes.projectFromJson(JSONObject(next))
        canUndo = true
        canRedo = future.isNotEmpty()
        schedulePersist()
        scope.launch { loadActiveImage() }
    }

    fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun saveBytes(name: String, bytes: ByteArray, okMsg: String): Boolean {
        return try {
            val dir = File(ctx.filesDir, "gel-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            toast(okMsg)
            true
        } catch (err: Exception) {
            toast(err.message ?: err.toString(), long = true)
            false
        }
    }

    fun saveText(name: String, text: String, okMsg: String): Boolean =
        saveBytes(name, text.toByteArray(Charsets.UTF_8), okMsg)

    fun projectBase(): String = project.imageName.replace(Regex("\\.[^.]+$"), "").ifEmpty { "gel" }

    // -------------------------------------------------------------- import
    fun bestStraightenAngle(): Double {
        val img = imageEl ?: return 0.0
        // baseOrientedCanvas：orient + mirror 应用后的底图
        val orient = ((project.orient % 4) + 4) % 4
        val swap = orient % 2 == 1
        val w = img.width
        val h = img.height
        val base = Bitmap.createBitmap(if (swap) h else w, if (swap) w else h, Bitmap.Config.ARGB_8888)
        val bc = GCanvas(base)
        bc.translate(base.width / 2f, base.height / 2f)
        bc.rotate(orient * 90f)
        bc.scale(if (project.mirrorH) -1f else 1f, if (project.mirrorV) -1f else 1f)
        bc.drawBitmap(img, null, android.graphics.RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val maxSide = 300
        val scale = maxSide / max(base.width, base.height).toDouble()
        val sw = max(8, Math.round(base.width * scale)).toInt()
        val sh = max(8, Math.round(base.height * scale)).toInt()
        val small = Bitmap.createScaledBitmap(base, sw, sh, true)

        fun scoreAt(deg: Double): Double {
            val rad = Math.toRadians(deg)
            val cos = abs(Math.cos(rad))
            val sin = abs(Math.sin(rad))
            val w2 = Math.round(small.width * cos + small.height * sin).toInt()
            val h2 = Math.round(small.width * sin + small.height * cos).toInt()
            if (w2 <= 0 || h2 <= 0) return 0.0
            val c = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888)
            val cv = GCanvas(c)
            cv.drawColor(android.graphics.Color.BLACK)
            cv.translate(w2 / 2f, h2 / 2f)
            cv.rotate(deg.toFloat())
            cv.drawBitmap(small, null, android.graphics.RectF(-sw / 2f, -sh / 2f, sw / 2f, sh / 2f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(w2 * h2)
            c.getPixels(px, 0, w2, 0, 0, w2, h2)
            return GelMath.rotationScore(GelMath.toGrayPlane(px, w2, h2), w2, h2)
        }

        var best = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var a = -15.0
        while (a <= 15.0001) {
            val s = scoreAt(a)
            if (s > bestScore) {
                bestScore = s
                best = a
            }
            a += 0.5
        }
        var fine = best
        a = best - 0.4
        while (a <= best + 0.4001) {
            val s = scoreAt(a)
            if (s > bestScore) {
                bestScore = s
                fine = a
            }
            a += 0.1
        }
        return Math.round(fine * 10) / 10.0
    }

    /** runDetection：在当前处理平面上运行泳道/类型/条带检测。 */
    fun remapLaneReferences(oldLanes: List<GelLane>) {
        val before = oldLanes.sortedBy { it.x }
        val after = project.lanes.sortedBy { it.x }
        val map = HashMap<String, String>()
        before.forEachIndexed { i, lane -> after.getOrNull(i)?.let { map[lane.id] = it.id } }
        project.annotations = project.annotations.map { a ->
            if (a is GelAnnotation.Arrow) map[a.laneId]?.let { a.copy(laneId = it) } ?: a else a
        }.toMutableList()
        project.conditionRows = project.conditionRows.map { row ->
            val cells = mutableMapOf<String, String>()
            row.cells.forEach { (id, v) -> map[id]?.let { cells[it] = v } }
            row.copy(cells = cells)
        }.toMutableList()
        project.groups = project.groups.map { g ->
            g.copy(laneIds = g.laneIds.map { map[it] ?: it }.filter { id -> project.lanes.any { it.id == id } }.toMutableList())
        }.toMutableList()
    }

    fun applyLaneDetection(found: List<DetectedLane>, keepNames: Boolean = false) {
        val old = project.lanes.sortedBy { it.x }
        project.lanes = found.mapIndexed { i, d ->
            val prior = if (keepNames) old.getOrNull(i) else null
            GelLane(
                id = GelTypes.newLaneId(),
                x = d.x,
                width = d.width,
                name = prior?.name ?: (i + 1).toString(),
                isMarker = prior?.isMarker ?: false,
                ladderId = prior?.ladderId,
                bands = mutableListOf(),
            )
        }.toMutableList()
        project.fit = null
        selectedLaneId = null
        selectedBand = null
        remapLaneReferences(old)
    }

    fun detectBandsAll(showMessage: Boolean = true) {
        val proc = processed ?: return
        val config = project.detect
        var n = 0
        for (lane in project.lanes) {
            if (lane.removed == true) continue
            val manual = lane.bands.filter { it.manual == true }
            val peaks = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, DetectedLane(lane.x, lane.width), project.gelType, config)
            lane.bands = peaks.map { p ->
                manual.find { b -> abs(b.y - p.y) < 0.01 } ?: GelBand(y = p.y, intensity = p.intensity, showSize = true)
            }.toMutableList()
            n += lane.bands.size
        }
        // 箭头可能指向重新检测后不存在的条带下标。
        project.annotations = project.annotations.map { a ->
            if (a is GelAnnotation.Arrow) {
                val lane = project.lanes.find { it.id == a.laneId }
                val last = max(0, (lane?.bands?.size ?: 1) - 1)
                a.copy(bandIndex = min(a.bandIndex, last))
            } else a
        }.toMutableList()
        if (showMessage) toast(if (n > 0) interp("检测到 {n} 个条带", "n" to n) else t("该泳道未检测到条带"))
        scheduleLayout()
    }

    fun detectBandsForLane(lane: GelLane) {
        val proc = processed ?: return
        val config = project.detect
        val peaks = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, DetectedLane(lane.x, lane.width), project.gelType, config)
        commit {
            lane.bands = peaks.map { GelBand(y = it.y, intensity = it.intensity, showSize = true) }.toMutableList()
            project.fit = null
        }
        toast(if (peaks.isNotEmpty()) interp("检测到 {n} 个条带", "n" to peaks.size) else t("该泳道未检测到条带"))
    }

    // ---------------------------------------------------- detection presets
    fun detectMarkers(showMessage: Boolean = true) {
        if (processed == null) return
        val ladders = GelLadders.laddersFor(project.gelType, project.customLadders)
        val result = GelDetect.identifyMarkers(
            project.lanes,
            com.spice.nativeapp.tools.gel.MatchOptions(kind = project.gelType, ladders = ladders, favorites = project.favorites),
        )
        if (result == null) {
            project.fit = null
            if (showMessage) toast(t("未找到匹配的 Marker"))
            scheduleLayout()
            return
        }
        commit {
            project.ladderId = result.ladder.id
            project.fit = GelLadderFit(
                ladderId = result.ladder.id,
                laneIds = result.laneIds.toMutableList(),
                a = result.fit.a,
                b = result.fit.b,
                c = result.fit.c,
                degree = result.fit.degree,
                r2 = result.fit.r2,
                n = result.fit.n,
            )
            for (lane in project.lanes) {
                val assigned = result.assignments[lane.id]
                lane.isMarker = assigned != null
                lane.ladderId = if (assigned != null) result.ladder.id else null
                if (assigned != null) {
                    val sorted = lane.bands.filter { it.hidden != true }.sortedBy { it.y }
                    sorted.forEachIndexed { i, band ->
                        if (i < assigned.size) {
                            band.size = assigned[i]
                            band.showSize = true
                            band.approx = false
                        } else {
                            band.size = null
                            band.showSize = false
                        }
                    }
                }
            }
            val alternatives = result.laneIds.mapNotNull { id ->
                val lane = project.lanes.find { it.id == id } ?: return@mapNotNull null
                val pts = lane.bands
                    .filter { it.hidden != true && it.size != null }
                    .sortedBy { it.y }
                    .map { GraySizePoint(it.y, it.size!!) }
                if (pts.size >= 2) GelMath.robustFit(pts, 1) else null
            }
            for (lane in project.lanes) {
                if (lane.isMarker || lane.removed == true) continue
                val est = GelDetect.estimateBandSizes(lane.bands, result.fit, result.ladder, alternatives)
                lane.bands.forEachIndexed { i, band ->
                    band.size = est[i].size
                    band.approx = est[i].approx
                    band.showSize = true
                }
            }
        }
        if (showMessage) toast(interp("识别到 {n} 条 Marker 泳道（{name}）", "n" to result.laneIds.size, "name" to result.ladder.name))
    }

    fun fitCurrentMarkers() {
        if (processed == null) return
        val ladder = GelLadders.ladderById(project.ladderId, project.customLadders)
        if (ladder == null) {
            toast(t("条带大小列表无效（至少两个数字）"))
            return
        }
        val markers = project.lanes.filter { it.isMarker && it.removed != true }
        if (markers.isEmpty()) {
            toast(t("未找到匹配的 Marker"))
            return
        }
        val points = ArrayList<GraySizePoint>()
        markers.forEach { lane ->
            val sizes = ladder.sizes
            val bands = lane.bands.filter { it.hidden != true }.sortedBy { it.y }
            if (bands.isNotEmpty() && bands.all { it.size != null }) {
                for (b in bands) points.add(GraySizePoint(b.y, b.size!!))
                return@forEach
            }
            if (bands.size != sizes.size && bands.size < 4) return@forEach
            val use = if (bands.size == sizes.size) sizes else sizes.subList(0, bands.size)
            bands.take(use.size).forEachIndexed { i, b ->
                b.size = use[i]
                points.add(GraySizePoint(b.y, use[i]))
            }
        }
        val fit = GelMath.robustFit(points, if (points.size >= 8) 2 else 1)
        if (fit == null) {
            toast(t("条带大小列表无效（至少两个数字）"))
            return
        }
        commit {
            project.fit = GelLadderFit(
                ladderId = ladder.id,
                laneIds = markers.map { it.id }.toMutableList(),
                a = fit.a, b = fit.b, c = fit.c, degree = fit.degree, r2 = fit.r2, n = fit.n,
            )
            for (lane in project.lanes) {
                if (lane.isMarker || lane.removed == true) continue
                val est = GelDetect.estimateBandSizes(lane.bands, fit, ladder)
                lane.bands.forEachIndexed { i, band ->
                    band.size = est[i].size
                    band.approx = est[i].approx
                    band.showSize = true
                }
            }
        }
        toast(interp("拟合完成（R² = {r2}）", "r2" to String.format(java.util.Locale.US, "%.3f", fit.r2)))
    }

    fun estimateSizesNow() {
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
        val pf = project.fit
        if (pf == null || ladder == null) {
            toast(t("先对 Marker 泳道完成拟合"))
            return
        }
        val fit = PolyFit(pf.a, pf.b, pf.c, pf.degree, pf.r2, pf.n)
        commit {
            for (lane in project.lanes) {
                if (lane.isMarker || lane.removed == true) continue
                val est = GelDetect.estimateBandSizes(lane.bands, fit, ladder)
                lane.bands.forEachIndexed { i, band ->
                    band.size = est[i].size
                    band.approx = est[i].approx
                    band.showSize = true
                }
            }
        }
        toast(t("已按拟合曲线标注其他泳道条带大小"))
    }

    // -------------------------------------------------------------- transforms
    suspend fun runDetection(proc: ProcessedImage) {
        val config = project.detect
        val gray = proc.detectGray
        val forced = config.type
        var preliminary = GelDetect.detectLanesAuto(gray, proc.width, proc.height, forced == "protein", config)
        val preliminaryBands = preliminary.map { lane ->
            GelDetect.detectBandsInLane(gray, proc.width, proc.height, lane, if (forced == "auto") "dna" else forced, config)
        }
        if (forced == "auto") {
            val maxBands = preliminaryBands.maxOfOrNull { it.size } ?: 0
            val widths = preliminaryBands.flatten().map { it.width }.sorted()
            val medianWidth = if (widths.isNotEmpty()) widths[widths.size / 2] else 0.0
            // 处理画布是灰度；类型投票取同一曝光的彩色渲染。
            var rgba = IntArray(proc.width * proc.height)
            withContext(Dispatchers.Default) {
                proc.bitmap.getPixels(rgba, 0, proc.width, 0, 0, proc.width, proc.height)
                val exp = GelTypes.activeExposure(project)
                if (exp != null && imageEl != null) {
                    try {
                        val colorProject = GelTypes.emptyProject().also {
                            it.exposures = project.exposures
                            it.activeExposureId = project.activeExposureId
                            it.rotation = project.rotation
                            it.mirrorH = project.mirrorH
                            it.mirrorV = project.mirrorV
                            it.orient = project.orient
                            it.adjust = project.adjust
                            it.crop = project.crop
                            it.detect = project.detect
                            it.display = "color"
                        }
                        val color = GelPipeline.renderProcessed(exp, colorProject, imageEl!!)
                        if (color != null) {
                            rgba = IntArray(proc.width * proc.height)
                            color.bitmap.getPixels(rgba, 0, proc.width, 0, 0, proc.width, proc.height)
                        }
                    } catch (e: Exception) {
                        // 回落到灰度平面；宽度/密度投票仍然进行。
                    }
                }
            }
            val mask = GelDetect.signalPlane(gray, proc.width, proc.height, config)
            val guess = GelDetect.detectGelType(rgba, maxBands, preliminary.size, medianWidth, mask)
            typeReasons = guess.reasons
            if (project.gelType != guess.kind) {
                project.gelType = guess.kind
                project.unit = GelLadders.unitForKind(guess.kind)
                project.ladderId = GelLadders.laddersFor(guess.kind, project.customLadders).firstOrNull()?.id ?: project.ladderId
                project.fit = null
            }
            if (guess.kind == "protein" && forced != "protein") {
                preliminary = GelDetect.detectLanesAuto(gray, proc.width, proc.height, true, config)
            }
        } else {
            typeReasons = emptyList()
            if (project.gelType != forced) {
                project.gelType = forced
                project.unit = GelLadders.unitForKind(forced)
                project.ladderId = GelLadders.laddersFor(forced, project.customLadders).firstOrNull()?.id ?: project.ladderId
                project.fit = null
            }
        }
        val oldLanes = project.lanes.toList()
        project.lanes = preliminary.mapIndexed { i, d ->
            val prior = oldLanes.sortedBy { it.x }.getOrNull(i)
            GelLane(
                id = GelTypes.newLaneId(),
                x = d.x,
                width = d.width,
                name = prior?.name ?: (i + 1).toString(),
                isMarker = false,
                bands = mutableListOf(),
            )
        }.toMutableList()
        remapLaneReferences(oldLanes)
        detectBandsAll(false)
        detectMarkers(false)
        detectStale = false
    }

    /** GelGenie U-Net 分割。 */
    suspend fun aiDraft() {
        val proc = processed ?: return
        busy = t("GelGenie 正在识别...")
        try {
            val result = withContext(Dispatchers.Default) {
                val gray = ByteArray(proc.rawGray.size)
                for (i in proc.rawGray.indices) {
                    gray[i] = ((proc.rawGray[i] * 255).roundToInt()).coerceIn(0, 255).toByte()
                }
                GelGenie.segment(ctx, gray, proc.width, proc.height)
            }
            if (result.lanes.isEmpty()) {
                toast(t("GelGenie 未识别到条带"))
                return
            }
            checkpoint(true)
            val old = project.lanes.sortedBy { it.x }
            project.lanes = result.lanes.mapIndexed { index, lane ->
                GelLane(
                    id = GelTypes.newLaneId(),
                    x = lane.x.toDouble(),
                    width = lane.width.toDouble(),
                    name = old.getOrNull(index)?.name ?: (index + 1).toString(),
                    isMarker = false,
                    bands = lane.bands.mapNotNull { bandIndex ->
                        val band = result.bands.getOrNull(bandIndex) ?: return@mapNotNull null
                        GelBand(y = (band.y + band.height / 2).toDouble(), intensity = band.intensity.toDouble(), showSize = true)
                    }.toMutableList(),
                )
            }.toMutableList()
            project.fit = null
            selectedLaneId = null
            selectedBand = null
            remapLaneReferences(old)
            scheduleLayout()
            schedulePersist()
            toast(interp("GelGenie：{lanes} 条泳道，{bands} 条带", "lanes" to result.lanes.size, "bands" to result.bands.size))
        } catch (error: Throwable) {
            val text = error.message ?: error.toString()
            toast(if (text.contains("download", true) || text.contains("模型")) t("请先在设置中下载 GelGenie 模型") else "${t("GelGenie 识别失败")} $text", long = true)
        } finally {
            busy = ""
        }
    }

    suspend fun autoDraft() {
        if (processed == null) return
        busy = t("自动分析中…")
        checkpoint(true)
        try {
            project.rotation = bestStraightenAngle()
            refreshProcessed()
            val proc = processed ?: return
            project.crop = GelMath.autoCropRect(proc.gray, proc.width, proc.height)
            runDetection(proc)
            scheduleLayout()
            schedulePersist()
        } finally {
            busy = ""
        }
    }

    fun setRotation(next: Double) {
        val clamped = 45.0.coerceAtMost(-45.0.coerceAtLeast(next))
        if (abs(clamped - project.rotation) < 0.001) return
        imageEl?.let { img ->
            GelPipeline.rotateProjectCoordinates(project, project.rotation, clamped, img.width, img.height)
        }
        project.rotation = clamped
        schedulePixels()
        schedulePersist()
    }

    suspend fun straighten() {
        busy = t("摆正中…")
        try {
            val old = project.rotation
            val next = bestStraightenAngle()
            if (abs(next - old) < 0.05) {
                busy = ""
                return
            }
            checkpoint()
            imageEl?.let { img ->
                GelPipeline.rotateProjectCoordinates(project, old, next, img.width, img.height)
            }
            project.rotation = next
            refreshProcessed()
            busy = ""
            schedulePersist()
        } catch (e: Exception) {
            busy = ""
        }
    }

    fun autoCrop() {
        val proc = processed ?: return
        commit { project.crop = GelMath.autoCropRect(proc.gray, proc.width, proc.height) }
    }

    /** 泳道 id 变化时保持注解/条件/编组挂接。 */
    fun autoLanes() {
        val proc = processed ?: return
        val config = project.detect
        val found = GelDetect.detectLanesAuto(proc.detectGray, proc.width, proc.height, project.gelType == "protein", config)
        if (found.isEmpty()) {
            toast(t("未检测到泳道，请手动添加"))
            return
        }
        commit {
            applyLaneDetection(found, false)
            for (lane in project.lanes) {
                lane.bands = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, DetectedLane(lane.x, lane.width), project.gelType, config).map {
                    GelBand(y = it.y, intensity = it.intensity, showSize = true)
                }.toMutableList()
            }
        }
        toast(interp("已检测到 {n} 条泳道", "n" to found.size))
    }

    fun reDetectInCrop() {
        val proc = processed ?: return
        val c = project.crop
        val x0 = Math.round(c.left * proc.width).toInt().coerceIn(0, proc.width)
        val x1 = Math.round((1 - c.right) * proc.width).toInt().coerceIn(0, proc.width)
        val y0 = Math.round(c.top * proc.height).toInt().coerceIn(0, proc.height)
        val y1 = Math.round((1 - c.bottom) * proc.height).toInt().coerceIn(0, proc.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val config = project.detect
        val sub = FloatArray(w * h)
        for (y in y0 until y1) {
            for (x in x0 until x1) sub[(y - y0) * w + (x - x0)] = proc.detectGray[y * proc.width + x]
        }
        val found = GelDetect.detectLanesAuto(sub, w, h, project.gelType == "protein", config).map { d ->
            DetectedLane(
                x = (x0 + d.x * w) / proc.width.toDouble(),
                width = (d.width * w) / proc.width.toDouble(),
            )
        }
        if (found.isEmpty()) {
            toast(t("未检测到泳道，请手动添加"))
            return
        }
        commit {
            applyLaneDetection(found, true)
            for (lane in project.lanes) {
                lane.bands = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, DetectedLane(lane.x, lane.width), project.gelType, config).map {
                    GelBand(y = it.y, intensity = it.intensity, showSize = true)
                }.toMutableList()
            }
        }
        toast(interp("已检测到 {n} 条泳道", "n" to found.size))
    }

    fun addLane() {
        if (processed == null) return
        commit {
            val lane = GelLane(
                id = GelTypes.newLaneId(),
                x = 0.5,
                width = 0.04,
                name = (project.lanes.size + 1).toString(),
                isMarker = false,
                bands = mutableListOf(),
            )
            project.lanes.add(lane)
            selectedLaneId = lane.id
        }
    }

    fun removeLane(id: String) {
        commit {
            project.lanes = project.lanes.filter { it.id != id }.toMutableList()
            project.groups = project.groups
                .map { it.copy(laneIds = it.laneIds.filter { x -> x != id }.toMutableList()) }
                .filter { it.laneIds.isNotEmpty() }.toMutableList()
            project.conditionRows = project.conditionRows.map { row ->
                val cells = row.cells.toMutableMap()
                cells.remove(id)
                row.copy(cells = cells)
            }.toMutableList()
            project.annotations = project.annotations.filter { a -> !(a is GelAnnotation.Arrow && a.laneId == id) }.toMutableList()
            if (selectedLaneId == id) selectedLaneId = null
        }
    }

    fun toggleSplice(lane: GelLane) {
        commit {
            lane.removed = lane.removed != true
            lane.splice = if (lane.removed == true) {
                if (!lane.splice.isNullOrEmpty() && lane.splice != "none") lane.splice else "gap"
            } else "none"
        }
    }

    fun restoreLane(lane: GelLane) {
        commit {
            lane.removed = false
            lane.splice = "none"
        }
    }

    fun setLaneCount(count: Int) {
        if (processed == null || count < 1 || count > 60) return
        commit {
            val sorted = project.lanes.sortedBy { it.x }
            val firstX = sorted.firstOrNull()?.x ?: (project.crop.left + 0.05)
            val lastX = sorted.lastOrNull()?.x ?: (1 - project.crop.right - 0.05)
            val width = sorted.firstOrNull()?.width ?: 0.04
            val next = ArrayList<GelLane>()
            for (i in 0 until count) {
                val frac = if (count == 1) 0.5 else i.toDouble() / (count - 1)
                val prior = sorted.getOrNull(i)
                next.add(
                    GelLane(
                        id = prior?.id ?: GelTypes.newLaneId(),
                        x = if (count == 1) 0.5 else firstX + (lastX - firstX) * frac,
                        width = prior?.width ?: width,
                        name = prior?.name ?: (i + 1).toString(),
                        isMarker = prior?.isMarker ?: false,
                        ladderId = prior?.ladderId,
                        bands = prior?.bands ?: mutableListOf(),
                        removed = prior?.removed,
                        splice = prior?.splice,
                        style = prior?.style,
                    ),
                )
            }
            project.lanes = next
            val keep = next.map { it.id }.toSet()
            project.groups = project.groups
                .map { it.copy(laneIds = it.laneIds.filter { id -> keep.contains(id) }.toMutableList()) }
                .filter { it.laneIds.isNotEmpty() }.toMutableList()
            project.conditionRows = project.conditionRows.map { row ->
                row.copy(cells = row.cells.filterKeys { id -> keep.contains(id) }.toMutableMap())
            }.toMutableList()
            project.annotations = project.annotations.filter { a -> a !is GelAnnotation.Arrow || keep.contains(a.laneId) }.toMutableList()
        }
    }

    fun setLaneStyle(key: String, value: Any?) {
        val lane = project.lanes.find { it.id == selectedLaneId } ?: return
        commit {
            val style = lane.style ?: com.spice.nativeapp.tools.gel.LaneStyle()
            when (key) {
                "fontSize" -> style.fontSize = value as Double
                "angle" -> style.angle = value as Double
                "italic" -> style.italic = value as Boolean?
                "red" -> style.red = value as Boolean?
            }
            lane.style = style
        }
    }

    fun selectedBandRef(): Triple<GelLane, GelBand, Int>? {
        val sb = selectedBand ?: return null
        val lane = project.lanes.find { it.id == sb.first } ?: return null
        val band = lane.bands.getOrNull(sb.second) ?: return null
        return Triple(lane, band, sb.second)
    }

    fun updateSelectedBand(patch: (GelBand) -> Unit) {
        val ref = selectedBandRef() ?: return
        commit { patch(ref.second) }
    }

    fun deleteSelectedBand() {
        val ref = selectedBandRef() ?: return
        commit {
            ref.first.bands.removeAt(ref.third)
            selectedBand = null
        }
        if (project.fit != null) fitCurrentMarkers()
    }

    fun removeEmptyLanes() {
        commit {
            for (lane in project.lanes) {
                if (lane.removed != true && lane.bands.isEmpty()) {
                    lane.removed = true
                    lane.splice = "gap"
                }
            }
        }
    }

    fun redistributeFromEnds() {
        val sorted = project.lanes.sortedBy { it.x }
        if (sorted.size < 2) return
        commit {
            val first = sorted.first().x
            val last = sorted.last().x
            sorted.forEachIndexed { i, lane ->
                if (i == 0 || i == sorted.size - 1) return@forEachIndexed
                lane.x = first + ((last - first) * i) / (sorted.size - 1)
            }
        }
    }

    fun onDetectInput() {
        project.detect.presetId = "custom"
        detectStale = true
        confirmRedetect = false
        schedulePixels()
        schedulePersist()
    }

    fun applyStainPreset(id: String) {
        val chosen = GelPresets.presetById(id) ?: return
        checkpoint(true)
        project.detect = GelPresets.sanitizeDetectConfig(GelPresets.toJson(chosen.config))
        project.detect.presetId = chosen.id
        if (chosen.display != null) project.display = chosen.display
        val kind = chosen.config.type
        if (kind != "auto" && project.gelType != kind) {
            project.gelType = kind
            project.unit = GelLadders.unitForKind(kind)
            project.ladderId = GelLadders.laddersFor(kind, project.customLadders).firstOrNull()?.id ?: project.ladderId
            project.fit = null
            for (lane in project.lanes) {
                lane.isMarker = false
                lane.ladderId = null
                lane.bands = lane.bands.map { it.copy(size = null, approx = false) }.toMutableList()
            }
        }
        typeReasons = emptyList()
        detectStale = true
        confirmRedetect = false
        schedulePixels()
        schedulePersist()
    }

    fun resetDetectPreset() {
        val current = GelPresets.presetById(project.detect.presetId)
        applyStainPreset(current?.id ?: "auto")
    }

    fun saveCurrentDetectDefault() {
        val ok = GelStorage.saveDetectDefault(ctx, project.detect)
        toast(if (ok) t("检测设置已保存为新图片的默认值") else t("无法保存默认检测设置"))
    }

    suspend fun redetectAll() {
        if (processed == null) return
        if (!confirmRedetect && project.lanes.any { lane -> lane.bands.any { it.manual == true } }) {
            confirmRedetect = true
            return
        }
        confirmRedetect = false
        busy = t("自动分析中…")
        checkpoint(true)
        try {
            refreshProcessed()
            val proc = processed ?: return
            runDetection(proc)
            scheduleLayout()
            schedulePersist()
            toast(t("检测已更新"))
        } finally {
            busy = ""
        }
    }

    fun typeReasonText(reason: String): String = when (reason) {
        "blue" -> t("蓝色主导（考马斯）")
        "red" -> t("偏红/橙色信号")
        "many" -> t("条带多且均匀（DNA ladder）")
        "wide" -> t("条带较宽（蛋白）")
        else -> t("颜色信息不足，按 DNA 处理")
    }

    fun setGelType(kind: String) {
        typeReasons = emptyList()
        val proc = processed
        commit {
            project.gelType = kind
            project.detect.type = kind
            project.detect.presetId = "custom"
            detectStale = false
            project.unit = GelLadders.unitForKind(kind)
            project.ladderId = GelLadders.laddersFor(kind, project.customLadders).firstOrNull()?.id ?: ""
            project.fit = null
            for (lane in project.lanes) {
                lane.isMarker = false
                lane.ladderId = null
                lane.bands = lane.bands.map { it.copy(size = null, approx = false) }.toMutableList()
            }
            if (proc != null) {
                for (lane in project.lanes) {
                    lane.bands = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, DetectedLane(lane.x, lane.width), kind, project.detect).map {
                        GelBand(y = it.y, intensity = it.intensity, showSize = true)
                    }.toMutableList()
                }
            }
        }
    }

    suspend fun rotateQuarter(clockwise: Boolean) {
        if (processed == null) return
        checkpoint(true)
        val oldOrdered = project.lanes.sortedBy { it.x }
        val nameById = oldOrdered.associate { it.id to it.name }
        val oldArrows = project.annotations.filterIsInstance<GelAnnotation.Arrow>()
            .map { it to (nameById[it.laneId] ?: "") }
        project.annotations = project.annotations.filter { it !is GelAnnotation.Arrow }.toMutableList()
        project.lanes = mutableListOf()
        GelPipeline.transformForQuarterTurn(project, clockwise)
        project.orient = (((project.orient + (if (clockwise) 1 else 3)) % 4) + 4) % 4
        val oldNames = oldOrdered.map { Triple(it.name, it.isMarker, it.ladderId) }
        refreshProcessed()
        val proc = processed ?: return
        val config = project.detect
        val found = GelDetect.detectLanesAuto(proc.detectGray, proc.width, proc.height, project.gelType == "protein", config)
        project.lanes = found.mapIndexed { i, d ->
            GelLane(
                id = GelTypes.newLaneId(),
                x = d.x,
                width = d.width,
                name = oldNames.getOrNull(i)?.first ?: (i + 1).toString(),
                isMarker = oldNames.getOrNull(i)?.second ?: false,
                ladderId = oldNames.getOrNull(i)?.third,
                bands = GelDetect.detectBandsInLane(proc.detectGray, proc.width, proc.height, d, project.gelType, config).map {
                    GelBand(y = it.y, intensity = it.intensity, showSize = true)
                }.toMutableList(),
            )
        }.toMutableList()
        // 箭头按同名泳道跟随，条带下标钳制。
        val byName = project.lanes.associateBy { it.name }
        for ((arrow, laneName) in oldArrows) {
            val lane = byName[laneName] ?: continue
            if (lane.bands.isEmpty()) continue
            project.annotations.add(arrow.copy(laneId = lane.id, bandIndex = min(arrow.bandIndex, lane.bands.size - 1)))
        }
        if (project.fit != null) {
            project.fit = null
            detectMarkers(false)
        }
        scheduleLayout()
        schedulePersist()
    }

    suspend fun flip(axis: String) {
        checkpoint()
        GelPipeline.transformForFlip(project, axis)
        if (axis == "h") project.mirrorH = !project.mirrorH
        else if (axis == "v") project.mirrorV = !project.mirrorV
        refreshProcessed()
        schedulePersist()
    }


    // ------------------------------------------------------------- annotations
    fun annotationColor(): String = if (project.layout.red) "red" else "default"

    fun addArrow() {
        val sb = selectedBand
        if (sb == null) {
            toast(t("先点击一个条带"))
            return
        }
        val lane = project.lanes.find { it.id == sb.first } ?: return
        commit {
            project.annotations.add(
                GelAnnotation.Arrow(
                    id = GelTypes.newId("ann"),
                    laneId = lane.id,
                    bandIndex = sb.second,
                    side = if (lane.x > 0.5) "right" else "left",
                    label = "",
                    color = annotationColor(),
                ),
            )
        }
    }

    fun addBox(enlarge: Boolean) {
        var cx = 0.5
        var cy = 0.5
        selectedBand?.let { sb ->
            val lane = project.lanes.find { it.id == sb.first }
            val band = lane?.bands?.getOrNull(sb.second)
            cx = lane?.x ?: 0.5
            cy = band?.y ?: 0.5
        }
        val w = 0.16
        val h = 0.1
        commit {
            val box = GelAnnotation.Box(
                id = GelTypes.newId("ann"),
                x = (1 - w).coerceAtMost(0.0.coerceAtLeast(cx - w / 2)),
                y = (1 - h).coerceAtMost(0.0.coerceAtLeast(cy - h / 2)),
                w = w,
                h = h,
                label = "",
                enlarge = enlarge,
                color = annotationColor(),
            )
            project.annotations.add(box)
            selectedAnnotationId = box.id
        }
    }

    fun addBracket() {
        val visH = 1 - project.crop.top - project.crop.bottom
        commit {
            project.annotations.add(
                GelAnnotation.Bracket(
                    id = GelTypes.newId("ann"),
                    side = "left",
                    start = project.crop.top + visH * 0.2,
                    end = project.crop.top + visH * 0.5,
                    label = "",
                    color = annotationColor(),
                ),
            )
        }
    }

    fun addSideText() {
        commit {
            project.annotations.add(
                GelAnnotation.SideText(
                    id = GelTypes.newId("ann"),
                    side = "left",
                    pos = 0.5,
                    text = "",
                    color = annotationColor(),
                ),
            )
        }
    }

    fun cycleMark() {
        val sb = selectedBand
        if (sb == null) {
            toast(t("先点击一个条带"))
            return
        }
        val lane = project.lanes.find { it.id == sb.first }
        val band = lane?.bands?.getOrNull(sb.second) ?: return
        val order = listOf<String?>("*", "#", "†", null)
        val idx = order.indexOf(band.mark)
        val next = order[(idx + 1) % order.size]
        commit { band.mark = next }
    }

    fun deleteAnnotation(id: String) {
        commit {
            project.annotations = project.annotations.filter { it.id != id }.toMutableList()
            if (selectedAnnotationId == id) selectedAnnotationId = null
        }
    }

    fun addGroupFromRange(fromId: String, toId: String) {
        val ordered = project.lanes.sortedBy { it.x }
        val i0 = ordered.indexOfFirst { it.id == fromId }
        val i1 = ordered.indexOfFirst { it.id == toId }
        if (i0 < 0 || i1 < 0) return
        val lo = min(i0, i1)
        val hi = max(i0, i1)
        val ids = ordered.subList(lo, hi + 1).map { it.id }
        commit {
            project.groups.add(
                GelGroup(
                    id = GelTypes.newId("grp"),
                    name = t("分组") + " " + (project.groups.size + 1),
                    laneIds = ids.toMutableList(),
                ),
            )
        }
        toast(t("已添加分组"))
    }

    fun addConditionRow() {
        commit {
            project.conditionRows.add(ConditionRow(id = GelTypes.newId("cond"), label = "", cells = mutableMapOf()))
        }
    }

    fun cycleCondition(rowId: String, laneId: String) {
        val row = project.conditionRows.find { it.id == rowId } ?: return
        val order = listOf("", "+", "−")
        val next = order[(order.indexOf(row.cells[laneId] ?: "") + 1) % order.size]
        commit { row.cells[laneId] = next }
    }

    fun addCustomLadder() {
        val sizes = GelLadders.normalizeSizes(customSizes.split(Regex("[,\\s]+")).mapNotNull { it.toDoubleOrNull() })
        if (customName.trim().isEmpty() || sizes.size < 2) {
            toast(t("条带大小列表无效（至少两个数字）"))
            return
        }
        val def = LadderDef(
            id = GelTypes.newId("custom"),
            name = customName.trim(),
            kind = project.gelType,
            sizes = sizes,
            ref = emptyList(),
            custom = true,
        )
        commit {
            project.customLadders.add(def)
            project.ladderId = def.id
        }
        customName = ""
        customSizes = ""
        GelStorage.saveLadderLibrary(ctx, project.customLadders, project.favorites)
    }

    fun toggleFavorite(id: String) {
        commit {
            project.favorites = (if (project.favorites.contains(id)) project.favorites.filter { it != id } else project.favorites + id).toMutableList()
        }
        GelStorage.saveLadderLibrary(ctx, project.customLadders, project.favorites)
    }

    fun setLadder(id: String) {
        commit {
            project.ladderId = id
            project.fit = null
        }
    }

    // ------------------------------------------------------------- figure input
    fun startNameEdit(laneId: String) {
        val lane = project.lanes.find { it.id == laneId } ?: return
        val lay = layout ?: return
        val item = lay.items.firstOrNull {
            it is FigItem.Text && it.hit is FigHit.LaneName && (it.hit as FigHit.LaneName).laneId == laneId
        } as? FigItem.Text
        val bbox = item?.bbox ?: com.spice.nativeapp.tools.gel.FigBox(0.0, 0.0, 60.0, 14.0)
        val scale = figScale
        editingLane = EditingLane(
            laneId,
            lane.name,
            (bbox.x * scale).toFloat(),
            (bbox.y * scale).toFloat(),
            max(50f, (bbox.w * scale + 8).toFloat()),
        )
    }

    fun commitNameEdit(next: Boolean = false) {
        val editing = editingLane ?: return
        val lane = project.lanes.find { it.id == editing.id }
        val value = editing.value
        val id = editing.id
        editingLane = null
        if (lane != null && lane.name != value) {
            commit { lane.name = value }
        }
        if (next) {
            val ordered = project.lanes.sortedBy { it.x }
            val i = ordered.indexOfFirst { it.id == id }
            ordered.getOrNull(i + 1)?.let { startNameEdit(it.id) }
        }
    }

    // --------------------------------------------------------------- files
    suspend fun openProjectText(text: String) {
        try {
            val parsed = JSONObject(text)
            val next = GelProjectDoc.sanitizeGelProject(parsed) ?: throw IllegalArgumentException(t("工程文件格式不正确"))
            project = next
            selectedLaneId = null
            selectedBand = null
            selectedAnnotationId = null
            loadActiveImage()
            toast(t("工程已打开"))
            schedulePersist()
        } catch (err: Exception) {
            toast(err.message ?: err.toString(), long = true)
        }
    }

    suspend fun importFiles(files: List<GelBatch.BatchFile>, mode: String = "replace") {
        val allowed = files.filter {
            (it.mime ?: "").startsWith("image/") || Regex("\\.(tiff?|png|jpe?g|bmp|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }
        if (allowed.isEmpty()) return
        busy = t("正在读取图像…")
        try {
            if (project.exposures.isNotEmpty()) GelStorage.saveRecent(ctx, project)
            val decoded = withContext(Dispatchers.Default) {
                allowed.mapNotNull { f -> GelPipeline.decodeExposure(ctx, f.uri, f.name, f.mime) }
            }
            if (decoded.isEmpty()) return
            val scores = ArrayList<Double>()
            for (exp in decoded) {
                val img = GelPipeline.loadBitmapFromDataUrl(exp.dataUrl) ?: continue
                val probe = GelTypes.emptyProject().also { it.detect = project.detect }
                val rendered = withContext(Dispatchers.Default) { GelPipeline.renderProcessed(exp, probe, img) }
                rendered?.let { scores.add(GelPipeline.exposureScore(it)) }
            }
            val best = if (scores.isNotEmpty()) scores.indexOf(scores.maxOrNull() ?: 0.0) else 0
            if (mode == "append" && project.exposures.isNotEmpty()) {
                // 同一凝胶的又一张曝光：保留泳道/注解/拟合，显示所有曝光中最好的一帧。
                decoded.forEach { project.exposures.add(it) }
                var bestId = decoded[best].id
                var bestScore = Double.NEGATIVE_INFINITY
                for (exp in project.exposures) {
                    try {
                        val img = GelPipeline.loadBitmapFromDataUrl(exp.dataUrl) ?: continue
                        val probe = GelTypes.emptyProject().also { it.detect = project.detect }
                        val rendered = withContext(Dispatchers.Default) { GelPipeline.renderProcessed(exp, probe, img) }
                        rendered?.let {
                            val score = GelPipeline.exposureScore(it)
                            if (score > bestScore) {
                                bestScore = score
                                bestId = exp.id
                            }
                        }
                    } catch (e: Exception) {
                        /* keep the previous candidate */
                    }
                }
                project.activeExposureId = bestId
                project.imageName = project.exposures.find { it.id == bestId }?.name ?: decoded[best].name
                loadActiveImage()
                schedulePersist()
                return
            }
            val fresh = GelTypes.emptyProject()
            fresh.layout = project.layout
            fresh.customLadders = project.customLadders
            fresh.favorites = project.favorites
            fresh.detect = GelStorage.detectConfigForNewImport(ctx)
            fresh.exposures = decoded.toMutableList()
            fresh.activeExposureId = decoded[best].id
            fresh.imageName = decoded[best].name
            project = fresh
            selectedLaneId = null
            selectedBand = null
            selectedAnnotationId = null
            recents = GelStorage.listRecent(ctx)
            loadActiveImage()
            autoDraft()
            schedulePersist()
        } catch (err: Exception) {
            toast(err.message ?: err.toString(), long = true)
        } finally {
            busy = ""
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val mode = importMode
            importMode = "replace"
            scope.launch {
                importFiles(uris.map { GelBatch.BatchFile(it, uriDisplayName(ctx, it), uriMime(ctx, it)) }, mode)
            }
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { openProjectText(readUriText(ctx, uri)) }
    }
    val ladderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val parsed = JSONObject(readUriText(ctx, uri))
                if (parsed.optInt("version", 0) != 1 || parsed.optJSONArray("custom") == null) {
                    throw IllegalArgumentException(t("ladder 库格式不正确"))
                }
                val valid = ArrayList<LadderDef>()
                parsed.optJSONArray("custom")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("id").isNotEmpty() && (o.optJSONArray("sizes")?.length() ?: 0) >= 2) {
                            valid.add(GelLadders.fromJson(o))
                        }
                    }
                }
                if (valid.isEmpty()) throw IllegalArgumentException(t("ladder 库格式不正确"))
                commit {
                    val ids = project.customLadders.map { it.id }.toSet()
                    valid.filter { it.id !in ids }.forEach {
                        project.customLadders.add(LadderDef(it.id, it.name, project.gelType, it.sizes, it.ref, true))
                    }
                    val favs = LinkedHashSet(project.favorites)
                    parsed.optJSONArray("favorites")?.let { arr ->
                        for (i in 0 until arr.length()) (arr.opt(i) as? String)?.let { f -> favs.add(f) }
                    }
                    project.favorites = favs.toMutableList()
                }
                GelStorage.saveLadderLibrary(ctx, project.customLadders, project.favorites)
                toast(interp("已导入 {n} 个 ladder", "n" to valid.size))
            } catch (err: Exception) {
                toast(err.message ?: err.toString(), long = true)
            }
        }
    }

    fun openImagePicker(mode: String) {
        importMode = mode
        imagePicker.launch(arrayOf("image/*", "application/octet-stream"))
    }

    fun setExposure(id: String) {
        checkpoint()
        project.activeExposureId = id
        project.imageName = GelTypes.activeExposure(project)?.name ?: project.imageName
        scope.launch { loadActiveImage() }
        schedulePersist()
    }

    fun removeExposure(id: String) {
        if (project.exposures.size <= 1) return
        checkpoint()
        project.exposures = project.exposures.filter { it.id != id }.toMutableList()
        if (project.activeExposureId == id) project.activeExposureId = project.exposures[0].id
        scope.launch { loadActiveImage() }
        schedulePersist()
    }

    // ----------------------------------------------------------- detection
    fun saveProject() {
        val ok = saveText("${projectBase()}.gel.json", projectJson(), t("工程已保存"))
        if (ok) {
            GelStorage.saveRecent(ctx, project)
            recents = GelStorage.listRecent(ctx)
        }
    }

    // ----------------------------------------------------------------- exports
    fun exportPngNow() {
        val proc = processed ?: run { toast(t("无法生成可复现数据包")); return }
        busy = t("正在准备导出…")
        scope.launch(Dispatchers.Default) {
            try {
                val fig = GelFigure.buildFigure(project, proc)
                val canvas = GelRender.rasterizeFigure(fig, proc, exportDpi.toDouble())
                val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
                val bytes = GelExport.exportPngWithMetadata(canvas, exportDpi.toDouble(), GelExport.summaryText(project, proc, ladder))
                withContext(Dispatchers.Main) {
                    saveBytes("${projectBase()}_figure_${exportDpi}dpi.png", bytes, t("图像已导出"))
                    busy = ""
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) {
                    toast(err.message ?: err.toString(), long = true)
                    busy = ""
                }
            }
        }
    }

    fun sendToFigureNow() {
        val proc = processed ?: return
        // 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（README 披露）。
        scope.launch(Dispatchers.Default) {
            try {
                val fig = layout ?: GelFigure.buildFigure(project, proc)
                val canvas = GelRender.rasterizeFigure(fig, proc, min(exportDpi, 300).toDouble())
                val png = bitmapToPng(canvas)
                val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                File(dir, "${projectBase()}_figure.png").writeBytes(png)
            } catch (err: Exception) {
                withContext(Dispatchers.Main) { toast(t("无法发送到 Figure Studio"), long = true) }
            }
        }
    }

    fun exportTiffNow() {
        val proc = processed ?: return
        busy = t("正在准备导出…")
        scope.launch(Dispatchers.Default) {
            try {
                val fig = GelFigure.buildFigure(project, proc)
                val canvas = GelRender.rasterizeFigure(fig, proc, exportDpi.toDouble())
                val rgba = IntArray(canvas.width * canvas.height)
                canvas.getPixels(rgba, 0, canvas.width, 0, 0, canvas.width, canvas.height)
                val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
                val bytes = GelExport.encodeTiff(rgba, canvas.width, canvas.height, exportDpi, GelExport.summaryText(project, proc, ladder), false)
                withContext(Dispatchers.Main) {
                    saveBytes("${projectBase()}_figure_${exportDpi}dpi.tif", bytes, t("图像已导出"))
                    busy = ""
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) {
                    toast(err.message ?: err.toString(), long = true)
                    busy = ""
                }
            }
        }
    }

    fun exportSvgNow() {
        val proc = processed
        val lay = layout
        if (proc == null || lay == null) return
        try {
            val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bitmapToPng(proc.bitmap))
            val svg = GelRender.figureToSvg(lay, dataUrl, proc.width, proc.height)
            saveText("${projectBase()}_figure.svg", svg, t("图像已导出"))
        } catch (err: Exception) {
            toast(err.message ?: err.toString(), long = true)
        }
    }

    fun exportPptxNow() {
        val proc = processed
        val lay = layout
        if (proc == null || lay == null) return
        busy = t("正在准备导出…")
        scope.launch(Dispatchers.Default) {
            try {
                val bytes = GelExport.makePptx(lay, proc)
                withContext(Dispatchers.Main) {
                    saveBytes("${projectBase()}_figure.pptx", bytes, t("图像已导出"))
                    busy = ""
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) {
                    toast(err.message ?: err.toString(), long = true)
                    busy = ""
                }
            }
        }
    }

    fun exportUncroppedNow() {
        val proc = processed ?: return
        scope.launch(Dispatchers.Default) {
            try {
                val canvas = GelRender.renderUncropped(project, proc, 1.0)
                val png = bitmapToPng(canvas)
                withContext(Dispatchers.Main) { saveBytes("${projectBase()}_uncropped.png", png, t("图像已导出")) }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) { toast(err.message ?: err.toString(), long = true) }
            }
        }
    }

    fun exportOriginalNow() {
        val exp = GelTypes.activeExposure(project)
        if (exp?.originalBase64 == null) {
            toast(t("没有保留原始文件数据"))
            return
        }
        val bytes = Base64.getDecoder().decode(exp.originalBase64)
        val sha = exp.sha256 ?: GelExport.sha256Hex(bytes)
        exp.sha256 = sha
        val ext = exp.name.split(".").lastOrNull() ?: "bin"
        saveBytes("${projectBase()}_original_${sha.take(8)}.$ext", bytes, t("原始文件已导出"))
    }

    fun exportLogNow() {
        val proc = processed
        val lay = layout
        if (proc == null || lay == null) return
        val exp = GelTypes.activeExposure(project)
        var sha = exp?.sha256
        if (sha == null && exp?.originalBase64 != null) {
            sha = GelExport.sha256Hex(Base64.getDecoder().decode(exp.originalBase64))
            exp.sha256 = sha
        }
        try {
            val log = GelExport.makeLog(project, proc, lay, project.customLadders, sha)
            saveText("${projectBase()}_processing_log.json", log.toString(2), t("处理日志已导出"))
        } catch (err: Exception) {
            toast(err.message ?: err.toString(), long = true)
        }
    }

    fun exportReproNow() {
        val proc = processed ?: run { toast(t("无法生成可复现数据包")); return }
        busy = t("正在生成可复现数据包")
        scope.launch(Dispatchers.Default) {
            try {
                val fig = layout ?: GelFigure.buildFigure(project, proc)
                val canvas = GelRender.rasterizeFigure(fig, proc, exportDpi.toDouble())
                val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
                val png = GelExport.exportPngWithMetadata(canvas, exportDpi.toDouble(), GelExport.summaryText(project, proc, ladder))
                val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bitmapToPng(proc.bitmap))
                val svg = GelRender.figureToSvg(fig, dataUrl, proc.width, proc.height)
                val base = projectBase()
                Repro.buildReproBundle(
                    ctx,
                    Repro.ReproBundleInput(
                        tool = "gel",
                        title = base.ifEmpty { t("电泳图工具") },
                        files = listOf(
                            Repro.ReproFile("${base}_figure_${exportDpi}dpi.png", "image/png", png),
                            Repro.ReproFile("${base}_figure.svg", "image/svg+xml", svg.toByteArray(Charsets.UTF_8)),
                            Repro.ReproFile("${base}_bands.csv", "text/csv", GelProjectDoc.gelCsv(project).toByteArray(Charsets.UTF_8)),
                        ),
                        parameters = GelProjectDoc.gelParameters(project, exportDpi),
                        methods = GelProjectDoc.gelMethods(project),
                        project = GelTypes.projectToJson(project),
                        notes = project.notes.takeIf { it.isNotBlank() },
                    ),
                )
                withContext(Dispatchers.Main) {
                    busy = ""
                    toast(t("可复现数据包已保存"))
                }
            } catch (err: Exception) {
                withContext(Dispatchers.Main) {
                    busy = ""
                    toast(err.message ?: err.toString(), long = true)
                }
            }
        }
    }

    fun applyResolutionFix() {
        val proc = processed ?: return
        val visW = 1 - project.crop.left - project.crop.right
        val maxMm = ((proc.width * visW / 300) * 25.4).toInt()
        commit { project.layout.figureWidthMm = max(60.0, min(300.0, maxMm.toDouble())) }
    }

    fun resetLevels() {
        commit {
            project.adjust.contrast = 1.0
            project.adjust.brightness = 0.0
            project.adjust.gamma = 1.0
        }
    }

    fun autoLevels() {
        val proc = processed ?: return
        val lo = GelMath.percentile(proc.rawGray, 0.02)
        val hi = GelMath.percentile(proc.rawGray, 0.98)
        commit {
            project.adjust.brightness = 0.0
            if (hi - lo < 0.02) {
                project.adjust.contrast = 1.0
                project.adjust.gamma = 1.0
                return@commit
            }
            val contrast = 1.8.coerceAtMost(1.0.coerceAtLeast(0.9 / (hi - lo)))
            val mid = 0.92.coerceAtMost(0.08.coerceAtLeast((lo + hi) / 2))
            val after = 0.95.coerceAtMost(0.08.coerceAtLeast((mid - 0.5) * contrast + 0.5))
            val gamma = Math.log(0.5) / Math.log(after)
            project.adjust.contrast = Math.round(contrast * 100) / 100.0
            project.adjust.gamma = Math.round(2.5.coerceAtMost(0.3.coerceAtLeast(gamma)) * 100) / 100.0
        }
    }

    fun fixTinyFont() {
        commit {
            project.layout.nameFontSize = max(8.0, project.layout.nameFontSize)
            project.layout.labelFontSize = max(7.0, project.layout.labelFontSize)
        }
    }

    fun applyBulkNames() {
        val names = bulkNames.split(Regex("[\\n,;\\t]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return
        commit {
            val ordered = project.lanes.filter { it.removed != true }.sortedBy { it.x }
            ordered.forEachIndexed { i, lane ->
                names.getOrNull(i)?.let { lane.name = it }
            }
        }
        bulkNames = ""
    }

    fun preflightText(item: GelExport.PreflightItem): String = when (item.id) {
        "saturation" -> interp("相机过曝/欠曝：{value}% 像素到达量程", "value" to String.format(java.util.Locale.US, "%.1f", item.value))
        "clip" -> interp("显示调整造成截断：{value}% 像素", "value" to String.format(java.util.Locale.US, "%.1f", item.value))
        "resolution" -> interp("有效分辨率 {value} dpi，低于 300", "value" to String.format(java.util.Locale.US, "%.0f", item.value))
        "tinyFont" -> interp("最小字号 {value} pt，可能过小", "value" to String.format(java.util.Locale.US, "%.1f", item.value))
        "approx" -> interp("{n} 个条带大小由外推或存在分歧（标 ≈）", "n" to String.format(java.util.Locale.US, "%.0f", item.value))
        "protein" -> t("蛋白 ladders 为表观分子量，图注需注明 apparent molecular weight")
        "ladder" -> t("尚未完成 ladder 拟合")
        else -> item.id
    }

    // ------------------------------------------------------------------ styles
    fun saveCurrentStyle() {
        if (styleName.trim().isEmpty()) return
        val style = GelStorage.GelStyle(
            id = GelTypes.newId("style"),
            name = styleName.trim(),
            layout = plainClone(project).layout,
            customLadders = plainClone(project).customLadders,
            favorites = project.favorites.toList(),
            gelType = project.gelType,
            createdAt = java.time.Instant.now().toString(),
        )
        if (GelStorage.saveStyle(ctx, style)) {
            styles = GelStorage.listStyles(ctx)
            styleName = ""
            toast(t("样式已保存"))
        } else {
            toast(t("浏览器存储空间不足，自动保存失败；请手动保存工程"), long = true)
        }
    }

    fun applyStyle(style: GelStorage.GelStyle) {
        val typeChanged = style.gelType != project.gelType
        commit {
            project.layout = style.layout.copy()
            project.customLadders = style.customLadders.toMutableList()
            project.favorites = style.favorites.toMutableList()
        }
        if (typeChanged) setGelType(style.gelType)
    }

    fun deleteStyle(id: String) {
        GelStorage.removeStyle(ctx, id)
        styles = GelStorage.listStyles(ctx)
    }

    fun openRecent(id: String) {
        val p = GelStorage.loadRecent(ctx, id)
        if (p == null) {
            toast(t("工程文件格式不正确"))
            return
        }
        project = p
        selectedLaneId = null
        selectedBand = null
        scope.launch { loadActiveImage() }
        toast(t("工程已打开"))
    }

    fun deleteRecent(id: String) {
        GelStorage.removeRecent(ctx, id)
        recents = GelStorage.listRecent(ctx)
    }

    fun commitRecentRename() {
        val edit = recentEdit ?: return
        GelStorage.renameRecent(ctx, edit.first, edit.second)
        recentEdit = null
        recents = GelStorage.listRecent(ctx)
    }

    fun resetAll() {
        checkpoint(true)
        project = GelTypes.emptyProject()
        imageEl = null
        processed = null
        layout = null
        history.clear()
        future.clear()
        canUndo = false
        canRedo = false
        GelStorage.clearSnapshot(ctx)
        uiRev++
    }

    // ---------------------------------------------------------------- lifecycle
    fun presetLabel(id: String): String = when (id) {
        "auto" -> t("自动")
        "uv" -> t("UV / 溴化乙锭")
        "sybr" -> t("SYBR Green")
        "coomassie" -> t("考马斯亮蓝")
        "silver" -> t("银染")
        else -> t("自定义")
    }

    fun typeModeLabel(mode: String): String = when (mode) {
        "dna" -> t("DNA")
        "protein" -> t("蛋白")
        else -> t("自动判断")
    }

    fun channelLabel(channel: String): String = when (channel) {
        "luma" -> t("亮度")
        "red" -> t("红")
        "green" -> t("绿")
        "blue" -> t("蓝")
        "red-blue" -> t("红 − 蓝")
        "green-red" -> t("绿 − 红")
        else -> t("自动（显示）")
    }

    fun polarityLabel(polarity: String): String = when (polarity) {
        "bright" -> t("亮条带")
        "dark" -> t("暗条带")
        else -> t("自动")
    }

    fun annTitle(ann: GelAnnotation): String = when (ann) {
        is GelAnnotation.Arrow -> t("箭头")
        is GelAnnotation.Box -> if (ann.enlarge) t("放大框") else t("区域框")
        is GelAnnotation.Bracket -> t("侧括号")
        is GelAnnotation.SideText -> t("侧文字")
    }

    fun fmt(v: Double, digits: Int): String = String.format(java.util.Locale.US, "%.${digits}f", v)

    LaunchedEffect(Unit) {
        // hydrateFromSnapshot：恢复快照并合并梯子库
        val snap = GelStorage.loadSnapshot(ctx)
        if (snap != null) {
            project = snap
            loadActiveImage()
        }
        val library = GelStorage.loadLadderLibrary(ctx)
        if (library.custom.isNotEmpty()) {
            val ids = project.customLadders.map { it.id }.toSet()
            library.custom.filter { it.id !in ids }.forEach { project.customLadders.add(it) }
            val favs = LinkedHashSet(project.favorites)
            favs.addAll(library.favorites)
            project.favorites = favs.toMutableList()
        }
        recents = GelStorage.listRecent(ctx)
        styles = GelStorage.listStyles(ctx)
    }

    val hasImage = GelTypes.activeExposure(project) != null
    val ladderOptions = GelLadders.laddersFor(project.gelType, project.customLadders)
    val emptyLaneCount = project.lanes.count { it.removed != true && it.bands.isEmpty() }
    val selectedLane = project.lanes.find { it.id == selectedLaneId }
    val bandRef = selectedBandRef()

    // label 映射（原版 presetLabel/typeModeLabel/polarityLabel/channelLabel）
    // ================================================================ UI tree
    Column(modifier.fillMaxSize()) {
        // toolbar
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PixButton(t("导入胶图")) { openImagePicker("replace") }
            PixButton(if (busy.isNotEmpty()) busy else t("自动完成"), accent = true, enabled = hasImage && busy.isEmpty()) { scope.launch { autoDraft() } }
            PixButton(t("AI 识别 (GelGenie)"), enabled = hasImage && busy.isEmpty()) { scope.launch { aiDraft() } }
            PixButton(t("撤销 (Ctrl+Z)"), enabled = hasImage && canUndo) { undo() }
            PixButton(t("重做 (Ctrl+Shift+Z)"), enabled = hasImage && canRedo) { redo() }
            PixButton(t("打开工程")) { projectPicker.launch(arrayOf("*/*")) }
            PixButton(t("保存工程"), enabled = hasImage) { saveProject() }
            PixButton(t("导出 PNG"), ok = true, enabled = hasImage) { exportPngNow() }
            PixButton(t("发送到 Figure Studio"), enabled = hasImage && busy.isEmpty()) { sendToFigureNow() }
            PixButton(t("清空"), danger = true, enabled = hasImage) { resetAll() }
        }

        Row(Modifier.fillMaxSize().weight(1f)) {
            // ------------------------------------------------------- sidebar
            Column(
                Modifier
                    .width(330.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // -------------------------------------------- 图像
                GelSection(t("图像"), startOpen = true) {
                    if (!hasImage) {
                        Text(t("尚未导入胶图，点击「导入胶图」选择图片"), fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(t("曝光"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        project.exposures.forEach { exp ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PixLink(exp.name.ifEmpty { t("曝光名称") }, bold = exp.id == project.activeExposureId) { setExposure(exp.id) }
                                Text("${exp.width}×${exp.height}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (exp.id == project.activeExposureId) {
                                    Text(t("最佳"), fontSize = 8.sp, color = Color(0xFF69E77A))
                                } else {
                                    PixButton(t("使用此曝光")) { setExposure(exp.id) }
                                }
                                if (project.exposures.size > 1) {
                                    PixButton(t("移除曝光"), danger = true) { removeExposure(exp.id) }
                                }
                            }
                        }
                        PixButton("+ ${t("添加曝光")}") { openImagePicker("append") }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("左转 90°")) { scope.launch { rotateQuarter(false) } }
                            PixButton(t("右转 90°")) { scope.launch { rotateQuarter(true) } }
                            PixButton(t("水平镜像")) { scope.launch { flip("h") } }
                            PixButton(t("垂直镜像")) { scope.launch { flip("v") } }
                        }
                        LabeledSlider("${t("微调旋转")} ${fmt(project.rotation, 1)}°", project.rotation, -45.0, 45.0, 899) { setRotation(it) }
                        PixCheck(t("显示参考网格"), showGrid) { showGrid = it; uiRev++ }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("自动摆正"), enabled = busy.isEmpty()) { scope.launch { straighten() } }
                            PixButton(t("自动裁切")) { autoCrop() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("灰度"), active = project.display == "gray") {
                                checkpoint(); project.display = "gray"; schedulePixels()
                            }
                            PixButton(t("原色"), active = project.display == "color") {
                                checkpoint(); project.display = "color"; schedulePixels()
                            }
                        }
                        if (project.display == "gray") {
                            PixCheck(t("灰度"), project.adjust.grayscale) { checkpoint(true); project.adjust.grayscale = it; schedulePixels() }
                            PixCheck(t("反相"), project.adjust.invert) { checkpoint(true); project.adjust.invert = it; schedulePixels() }
                            LabeledSlider("${t("对比度")} ${fmt(project.adjust.contrast, 2)}", project.adjust.contrast, 0.2, 3.0, 55) { checkpoint(true); project.adjust.contrast = it; schedulePixels() }
                            LabeledSlider("${t("亮度")} ${fmt(project.adjust.brightness, 2)}", project.adjust.brightness, -0.5, 0.5, 99) { checkpoint(true); project.adjust.brightness = it; schedulePixels() }
                            LabeledSlider("${t("伽马")} ${fmt(project.adjust.gamma, 2)}", project.adjust.gamma, 0.3, 2.5, 43, enabled = !project.adjust.gammaLocked) { checkpoint(true); project.adjust.gamma = it; schedulePixels() }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PixCheck(t("锁定伽马为 1（仅线性调整）"), project.adjust.gammaLocked) { checkpoint(true); project.adjust.gammaLocked = it }
                                PixButton(t("自动色阶")) { autoLevels() }
                                PixButton(t("重置亮度/对比度")) { resetLevels() }
                            }
                        }
                        LabeledSlider(t("左裁切"), project.crop.left, 0.0, 0.6, 119) { checkpoint(true); project.crop.left = it; scheduleLayout() }
                        LabeledSlider(t("上裁切"), project.crop.top, 0.0, 0.6, 119) { checkpoint(true); project.crop.top = it; scheduleLayout() }
                        LabeledSlider(t("右裁切"), project.crop.right, 0.0, 0.6, 119) { checkpoint(true); project.crop.right = it; scheduleLayout() }
                        LabeledSlider(t("下裁切"), project.crop.bottom, 0.0, 0.6, 119) { checkpoint(true); project.crop.bottom = it; scheduleLayout() }
                    }
                }

                // -------------------------------------------- 检测 / 染色预设
                GelSection(t("检测 / 染色预设"), startOpen = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("染色预设"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(GelPresets.presetChoices(project.detect.presetId).map { it.first to presetLabel(it.first) }, project.detect.presetId) { applyStainPreset(it) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("凝胶类型"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(GelPresets.DETECT_TYPE_MODES.map { it to typeModeLabel(it) }, project.detect.type) { project.detect.type = it; onDetectInput() }
                        Text(t("条带极性"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(GelPresets.DETECT_POLARITIES.map { it to polarityLabel(it) }, project.detect.polarity) { project.detect.polarity = it; onDetectInput() }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("检测通道"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(GelPresets.DETECT_CHANNELS.map { it to channelLabel(it) }, project.detect.channel) { project.detect.channel = it; onDetectInput() }
                    }
                    LabeledSlider("${t("灵敏度")} ${(project.detect.sensitivity * 100).roundToInt()}%", project.detect.sensitivity, 0.0, 1.0, 99) { checkpoint(true); project.detect.sensitivity = it; onDetectInput() }
                    LabeledSlider("${t("降噪（原生）")} ${project.detect.denoise}", project.detect.denoise.toDouble(), 0.0, 2.0, 1) { checkpoint(true); project.detect.denoise = it.roundToInt(); onDetectInput() }
                    // 高级参数
                    Text(t("高级参数"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("泳道与条带"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabeledSlider("${t("泳道切割")} ${(project.detect.lanes.cut * 100).roundToInt()}%", project.detect.lanes.cut, 0.02, 0.4, 37) { checkpoint(true); project.detect.lanes.cut = it; onDetectInput() }
                    LabeledSlider("${t("合并间隙")} ${fmt(project.detect.lanes.mergeGap * 100, 1)}%", project.detect.lanes.mergeGap, 0.0, 0.02, 19) { checkpoint(true); project.detect.lanes.mergeGap = it; onDetectInput() }
                    LabeledSlider("${t("最小泳道宽度")} ${fmt(project.detect.lanes.minWidth * 100, 1)}%", project.detect.lanes.minWidth, 0.001, 0.02, 18) { checkpoint(true); project.detect.lanes.minWidth = it; onDetectInput() }
                    LabeledSlider("${t("泳道平滑")} ${fmt(project.detect.lanes.smooth * 100, 1)}%", project.detect.lanes.smooth, 0.0, 0.05, 9) { checkpoint(true); project.detect.lanes.smooth = it; onDetectInput() }
                    LabeledSlider("${t("泳道显著度")} ${(project.detect.lanes.prominence * 100).roundToInt()}%", project.detect.lanes.prominence, 0.01, 0.3, 28) { checkpoint(true); project.detect.lanes.prominence = it; onDetectInput() }
                    Text(t("条带"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabeledSlider("${t("条带显著度")} ${fmt(project.detect.bands.prominence, 2)}σ", project.detect.bands.prominence, 0.1, 2.0, 37) { checkpoint(true); project.detect.bands.prominence = it; onDetectInput() }
                    LabeledSlider("${t("最小条带间隙")} ${fmt(project.detect.bands.minGap * 100, 1)}%", project.detect.bands.minGap, 0.001, 0.05, 48) { checkpoint(true); project.detect.bands.minGap = it; onDetectInput() }
                    LabeledSlider("${t("条带平滑")} ${project.detect.bands.smooth}px", project.detect.bands.smooth.toDouble(), 1.0, 15.0, 13) { checkpoint(true); project.detect.bands.smooth = it.roundToInt(); onDetectInput() }
                    if (detectStale) {
                        Text(t("参数已修改，重新检测后生效。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (confirmRedetect) {
                        Text(t("重新检测会覆盖手工修改的泳道和条带，继续？"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("覆盖"), danger = true, enabled = hasImage && busy.isEmpty()) { scope.launch { redetectAll() } }
                            PixButton(t("取消")) { confirmRedetect = false }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("重新检测"), enabled = hasImage && busy.isEmpty()) { scope.launch { redetectAll() } }
                            PixButton(t("恢复预设")) { resetDetectPreset() }
                            PixButton(t("存为默认")) { saveCurrentDetectDefault() }
                        }
                    }
                }

                // -------------------------------------------- 凝胶类型 / 分子量标准
                GelSection("${t("凝胶类型")} / ${t("分子量标准")}", startOpen = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(t("DNA（琼脂糖）"), active = project.gelType == "dna") { setGelType("dna") }
                        PixButton(t("蛋白（SDS-PAGE）"), active = project.gelType == "protein") { setGelType("protein") }
                    }
                    Text("${t("单位")}: ${project.unit}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (typeReasons.isNotEmpty()) {
                        Text(typeReasons.joinToString(" · ") { typeReasonText(it) }, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("分子量标准"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(
                            ladderOptions.map { it.id to (if (project.favorites.contains(it.id)) "★ " else "") + it.name },
                            project.ladderId,
                        ) { setLadder(it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton("★ ${t("收藏")}") { toggleFavorite(project.ladderId) }
                        PixButton(t("自动识别 Marker"), enabled = hasImage) { detectMarkers(true) }
                        PixButton(t("拟合"), enabled = hasImage) { fitCurrentMarkers() }
                    }
                    project.fit?.let { fit ->
                        Text(
                            interp("拟合 R² = {r2} · {n} 点", "r2" to fmt(fit.r2, 3), "n" to fit.n),
                            fontSize = 9.sp, color = Color(0xFF69E77A),
                        )
                    }
                    PixButton(t("标注其他泳道条带大小"), enabled = project.fit != null) { estimateSizesNow() }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(t("导出 ladder 库")) {
                            saveText(
                                "${projectBase()}_ladders.json",
                                JSONObject().apply {
                                    put("version", 1)
                                    put("custom", JSONArray().apply { project.customLadders.forEach { put(GelLadders.toJson(it)) } })
                                    put("favorites", JSONArray(project.favorites))
                                }.toString(2),
                                t("ladder 库已导出"),
                            )
                        }
                        PixButton(t("导入 ladder 库")) { ladderPicker.launch(arrayOf("*/*")) }
                    }
                    Text(t("自定义 ladder"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixTextField(customName, t("名称")) { customName = it }
                    PixTextField(customSizes, t("条带大小，如 5000, 3000, 2000")) { customSizes = it }
                    PixButton("+ ${t("添加")}") { addCustomLadder() }
                }

                // -------------------------------------------- 泳道与条带
                GelSection(t("泳道与条带"), startOpen = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton(t("自动检测泳道"), enabled = hasImage) { autoLanes() }
                        PixButton(t("在裁切区内重新检测"), enabled = hasImage) { reDetectInCrop() }
                        PixButton("+ ${t("添加泳道")}", enabled = hasImage) { addLane() }
                        PixButton(t("检测条带"), enabled = hasImage) { detectBandsAll(true) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("泳道数"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixNumberField(project.lanes.size.toString()) { v -> v.toIntOrNull()?.let { if (it in 1..60) setLaneCount(it) } }
                        PixCheck(t("联动手柄"), linkGuides) { linkGuides = it }
                        PixButton(t("等距排列")) { redistributeFromEnds() }
                        if (emptyLaneCount > 0) {
                            PixButton(interp("{n} 条空泳道", "n" to emptyLaneCount)) { removeEmptyLanes() }
                        }
                    }
                    if (selectedLane != null) {
                        Text(t("泳道名样式"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t("泳道名字号"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixNumberField((selectedLane!!.style?.fontSize ?: project.layout.nameFontSize).toString()) { v -> v.toDoubleOrNull()?.let { setLaneStyle("fontSize", it) } }
                            Text(t("泳道名角度"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixNumberField((selectedLane!!.style?.angle ?: project.layout.nameAngle).toString()) { v -> v.toDoubleOrNull()?.let { setLaneStyle("angle", it) } }
                            PixCheck(t("斜体"), selectedLane!!.style?.italic == true) { setLaneStyle("italic", it) }
                            PixCheck(t("红色"), selectedLane!!.style?.red == true) { setLaneStyle("red", it) }
                        }
                    }
                    if (bandRef != null) {
                        Text(
                            "${t("条带")} · ${bandRef!!.first.name.ifEmpty { "—" }} #${bandRef!!.third + 1}",
                            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t("大小"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixNumberField(bandRef!!.second.size?.let { fmt(Math.round(it * 10) / 10.0, 1) } ?: "") { v ->
                                val d = v.toDoubleOrNull()
                                if (d != null && d > 0) updateSelectedBand { b ->
                                    b.size = d; b.manual = true; b.showSize = true; b.approx = false
                                }
                            }
                            PixCheck(t("显示标签"), bandRef!!.second.showSize == true) { v -> updateSelectedBand { b -> b.showSize = v } }
                            PixCheck(t("隐藏（保留校准）"), bandRef!!.second.hidden == true) { v -> updateSelectedBand { b -> b.hidden = v } }
                            PixButton(t("删除条带"), danger = true) { deleteSelectedBand() }
                        }
                    }
                    if (project.lanes.isEmpty()) {
                        Text(t("暂无泳道：点「自动检测泳道」或「添加泳道」"), fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    project.lanes.forEach { lane ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixLink(lane.name.ifEmpty { "—" }, bold = lane.id == selectedLaneId) {
                                selectedLaneId = lane.id
                                uiRev++
                            }
                            PixTextField(lane.name, t("泳道名")) {
                                lane.name = it
                                scheduleLayout(); schedulePersist()
                            }
                            PixCheck(t("Marker"), lane.isMarker) {
                                selectedLaneId = lane.id
                                checkpoint(true)
                                lane.isMarker = it
                                scheduleLayout()
                            }
                            PixButton("${lane.bands.size}") { detectBandsForLane(lane) }
                            PixButton(if (lane.removed == true) "🗙" else "👁") { toggleSplice(lane) }
                            PixButton(t("删除泳道"), danger = true) { removeLane(lane.id) }
                        }
                        if (lane.removed == true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t("拼接标记"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PixSelect(listOf("gap" to t("缺口"), "line" to t("线")), lane.splice ?: "gap") { lane.splice = it; schedulePersist() }
                                PixButton(t("恢复")) { restoreLane(lane) }
                            }
                        }
                    }
                    Text(t("批量命名"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixTextField(bulkNames, t("每行一个泳道名，或用逗号分隔")) { bulkNames = it }
                    PixButton(t("应用名称"), enabled = bulkNames.isNotBlank()) { applyBulkNames() }
                }

                // -------------------------------------------- 条件行 / 分组
                GelSection("${t("条件行")} / ${t("分组")}", startOpen = false) {
                    PixButton("+ ${t("添加条件行")}") { addConditionRow() }
                    project.conditionRows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixTextField(row.label, t("条件名称")) { row.label = it; scheduleLayout() }
                            PixButton("🗑", danger = true) {
                                commit { project.conditionRows = project.conditionRows.filter { it.id != row.id }.toMutableList() }
                            }
                        }
                    }
                    Text(t("在图上从一个泳道名拖到另一个泳道名即可建立分组"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    project.groups.forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixTextField(group.name, t("分组名称")) { group.name = it; scheduleLayout() }
                            Text("${group.laneIds.size}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixButton("🗑", danger = true) {
                                commit { project.groups = project.groups.filter { it.id != group.id }.toMutableList() }
                            }
                        }
                    }
                }

                // -------------------------------------------- 标注
                GelSection(t("标注"), startOpen = selectedAnnotationId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixButton("← ${t("箭头")}") { addArrow() }
                        PixButton(t("区域框")) { addBox(false) }
                        PixButton(t("放大框")) { addBox(true) }
                        PixButton(t("侧括号")) { addBracket() }
                        PixButton("T ${t("侧文字")}") { addSideText() }
                        PixButton("*") { cycleMark() }
                    }
                    if (selectedBand == null) {
                        Text(t("先点击一个条带"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    project.annotations.forEach { ann ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (ann.id == selectedAnnotationId) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                PixLink(annTitle(ann)) { selectedAnnotationId = ann.id }
                                when (ann) {
                                    is GelAnnotation.Box -> {
                                        PixTextField(ann.label, t("标签")) { ann.label = it; scheduleLayout() }
                                        Text(t("区域位置"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        LabeledSlider("X ${fmt(ann.x, 3)}", ann.x, 0.0, 1.0, 199) { ann.x = it; scheduleLayout() }
                                        LabeledSlider("Y ${fmt(ann.y, 3)}", ann.y, 0.0, 1.0, 199) { ann.y = it; scheduleLayout() }
                                        LabeledSlider("W ${fmt(ann.w, 3)}", ann.w, 0.02, 0.6, 115) { ann.w = it; scheduleLayout() }
                                        LabeledSlider("H ${fmt(ann.h, 3)}", ann.h, 0.02, 0.6, 115) { ann.h = it; scheduleLayout() }
                                        PixCheck(t("在右侧放大"), ann.enlarge) { ann.enlarge = it; scheduleLayout() }
                                    }
                                    is GelAnnotation.Arrow -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t("箭头方向"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            PixSelect(listOf("left" to t("左"), "right" to t("右")), ann.side) { ann.side = it; scheduleLayout() }
                                        }
                                        PixTextField(ann.label ?: "", t("标签")) { ann.label = it; scheduleLayout() }
                                    }
                                    is GelAnnotation.Bracket -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t("位置"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            PixSelect(
                                                listOf("left" to t("左"), "right" to t("右"), "top" to t("上"), "bottom" to t("下")),
                                                ann.side,
                                            ) { ann.side = it; scheduleLayout() }
                                        }
                                        Text(t("范围"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        LabeledSlider(fmt(ann.start, 3), ann.start, 0.0, 1.0, 199) { ann.start = it; scheduleLayout() }
                                        LabeledSlider("→ ${fmt(ann.end, 3)}", ann.end, 0.0, 1.0, 199) { ann.end = it; scheduleLayout() }
                                        PixTextField(ann.label, t("标签")) { ann.label = it; scheduleLayout() }
                                    }
                                    is GelAnnotation.SideText -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t("位置"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            PixSelect(
                                                listOf("left" to t("左"), "right" to t("右"), "top" to t("上"), "bottom" to t("下")),
                                                ann.side,
                                            ) { ann.side = it; scheduleLayout() }
                                        }
                                        PixTextField(ann.text, t("文字内容")) { ann.text = it; scheduleLayout() }
                                        LabeledSlider("${t("沿边位置")} ${fmt(ann.pos, 2)}", ann.pos, 0.0, 1.0, 99) { ann.pos = it; scheduleLayout() }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Aa", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PixNumberField((ann.fontSize ?: project.layout.labelFontSize).toString()) { v ->
                                        v.toDoubleOrNull()?.let { ann.fontSize = it; scheduleLayout() }
                                    }
                                    Text(t("颜色"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PixSelect(listOf("default" to t("默认"), "red" to t("红色")), ann.color) { ann.color = it; scheduleLayout() }
                                    PixButton(t("删除标注"), danger = true) { deleteAnnotation(ann.id) }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------- 排版
                GelSection(t("排版"), startOpen = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("字体"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(
                            listOf("Arial", "Times New Roman", "Helvetica", "Calibri", "Cambria").map { it to it },
                            project.layout.fontFamily,
                        ) { project.layout.fontFamily = it; scheduleLayout(); schedulePersist() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("泳道名字号"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixNumberField(project.layout.nameFontSize.toString()) { v -> v.toDoubleOrNull()?.let { project.layout.nameFontSize = it; scheduleLayout() } }
                        Text(t("标注字号"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixNumberField(project.layout.labelFontSize.toString()) { v -> v.toDoubleOrNull()?.let { project.layout.labelFontSize = it; scheduleLayout() } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("泳道名角度"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixNumberField(project.layout.nameAngle.toString()) { v -> v.toDoubleOrNull()?.let { project.layout.nameAngle = it; scheduleLayout() } }
                        Text("${t("图宽")} (mm)", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixNumberField(project.layout.figureWidthMm.toString()) { v -> v.toDoubleOrNull()?.let { project.layout.figureWidthMm = it.coerceIn(60.0, 300.0); scheduleLayout() } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(85.0, 120.0, 180.0).forEach { mm ->
                            PixButton("${mm.roundToInt()} mm", active = project.layout.figureWidthMm == mm) {
                                project.layout.figureWidthMm = mm
                                scheduleLayout(); schedulePersist()
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("分组标签"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(
                            listOf("wrap" to t("换行"), "shrink" to t("缩小"), "one" to t("单行")),
                            project.layout.groupLabelFit,
                        ) { project.layout.groupLabelFit = it; scheduleLayout() }
                    }
                    PixCheck(t("标注使用红色"), project.layout.red) { project.layout.red = it; scheduleLayout() }
                    PixCheck(t("条件行靠近凝胶"), project.layout.conditionsNearestGel) { project.layout.conditionsNearestGel = it; scheduleLayout() }
                    Text(t("样式模板"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PixTextField(styleName, t("样式名称")) { styleName = it }
                        PixButton(t("保存样式")) { saveCurrentStyle() }
                    }
                    styles.forEach { style ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixLink(style.name) { applyStyle(style) }
                            PixButton("🗑", danger = true) { deleteStyle(style.id) }
                        }
                    }
                    if (styles.isEmpty()) {
                        Text(t("暂无样式"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(t("最近项目"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recents.forEach { recent ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (recentEdit?.first == recent.id) {
                                PixTextField(recentEdit!!.second, t("名称")) { v -> recentEdit = recent.id to v }
                                PixButton(t("保存")) { commitRecentRename() }
                            } else {
                                PixLink(recent.name) { openRecent(recent.id) }
                                PixButton(t("重命名")) { recentEdit = recent.id to recent.name }
                            }
                            PixButton(t("删除记录"), danger = true) { deleteRecent(recent.id) }
                        }
                    }
                    if (recents.isEmpty()) {
                        Text(t("暂无最近项目"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // -------------------------------------------- 导出
                GelSection(t("导出"), startOpen = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("分辨率"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(listOf(300, 600, 1200).map { it.toString() to it.toString() }, exportDpi.toString()) {
                            exportDpi = it.toIntOrNull() ?: 300
                        }
                    }
                    // btn-grid 两列
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("导出 PNG"), enabled = hasImage) { exportPngNow() }
                            PixButton(t("导出 TIFF"), enabled = hasImage) { exportTiffNow() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("导出 SVG"), enabled = hasImage) { exportSvgNow() }
                            PixButton(t("导出 PPTX"), enabled = hasImage && busy.isEmpty()) { exportPptxNow() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("未裁切图"), enabled = hasImage) { exportUncroppedNow() }
                            PixButton(t("原始文件"), enabled = hasImage) { exportOriginalNow() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PixButton(t("处理日志"), enabled = hasImage) { exportLogNow() }
                            PixButton(t("导出可复现数据包"), enabled = hasImage && busy.isEmpty()) { exportReproNow() }
                        }
                    }
                    Text(t("导出前检查"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (preflightItems.isEmpty()) {
                        Text(t("检查通过"), fontSize = 9.sp, color = Color(0xFF69E77A))
                    } else {
                        preflightItems.forEach { item ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    preflightText(item),
                                    fontSize = 8.5.sp,
                                    color = if (item.severity == "warn") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                when (item.id) {
                                    "resolution" -> PixButton(t("设为 300 dpi 最大宽度")) { applyResolutionFix() }
                                    "clip" -> PixButton(t("重置亮度/对比度")) { resetLevels() }
                                    "tinyFont" -> PixButton(t("放大字号")) { fixTinyFont() }
                                    "ladder" -> PixButton(t("自动识别 Marker")) { detectMarkers(true) }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------- 批量模式
                GelSection(t("批量模式"), startOpen = false) {
                    GelBatchPanel(customLadders = project.customLadders, exportDpi = exportDpi)
                }
            } // sidebar

            // ------------------------------------------------------- stages
            Column(Modifier.weight(1f).fillMaxSize()) {
                // 源图 stage
                Column(Modifier.weight(1f).fillMaxWidth().padding(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(t("胶图编辑器画布"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            PixButton(t("缩小")) { sourceZoom = max(1.0, sourceZoom / 1.5); uiRev++ }
                            Text("${(sourceZoom * 100).roundToInt()}%", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PixButton(t("放大")) { sourceZoom = min(8.0, sourceZoom * 1.5); uiRev++ }
                            PixButton(t("适应窗口")) { sourceZoom = 1.0; uiRev++ }
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().background(Color(0xFF04060A)),
                    ) {
                        val proc = processed
                        if (hasImage && proc != null) {
                            SourceStage(
                                processed = proc,
                                project = project,
                                selectedLaneId = selectedLaneId,
                                selectedBand = selectedBand,
                                showGrid = showGrid,
                                sourceZoom = sourceZoom,
                                uiRev = uiRev,
                                onDown = { cssX, cssY, cssW, cssH ->
                                    // onSourcePointerDown：裁剪边 7px / 条带点 5px / 最近泳道 9px
                                    val c = project.crop
                                    val edges = listOf(
                                        "left" to abs(cssX - c.left * cssW),
                                        "right" to abs(cssX - (1 - c.right) * cssW),
                                        "top" to abs(cssY - c.top * cssH),
                                        "bottom" to abs(cssY - (1 - c.bottom) * cssH),
                                    ).sortedBy { it.second }
                                    if (edges[0].second <= 7f * density.density) {
                                        checkpoint(true)
                                        drag.type = "crop"
                                        drag.edge = edges[0].first
                                        return@SourceStage true
                                    }
                                    var bandHit: Pair<GelLane, Int>? = null
                                    for (lane in project.lanes) {
                                        if (abs(cssX - lane.x * cssW) > 8f * density.density) continue
                                        lane.bands.forEachIndexed { index, band ->
                                            if (abs(cssY - band.y * cssH) <= 5f * density.density) bandHit = lane to index
                                        }
                                    }
                                    if (bandHit != null) {
                                        selectedLaneId = bandHit!!.first.id
                                        selectedBand = bandHit!!.first.id to bandHit!!.second
                                        checkpoint(true)
                                        drag.type = "band"
                                        drag.laneId = bandHit!!.first.id
                                        drag.index = bandHit!!.second
                                        uiRev++
                                        return@SourceStage true
                                    }
                                    var nearest: GelLane? = null
                                    var dist = 9.0 * density.density
                                    for (lane in project.lanes) {
                                        val dd = abs(cssX - lane.x * cssW)
                                        if (dd < dist) {
                                            dist = dd
                                            nearest = lane
                                        }
                                    }
                                    if (nearest != null) {
                                        selectedLaneId = nearest.id
                                        checkpoint(true)
                                        drag.type = "lane"
                                        drag.laneId = nearest.id
                                        uiRev++
                                        return@SourceStage true
                                    }
                                    false
                                },
                                onMove = { nx, ny ->
                                    when (drag.type) {
                                        "crop" -> {
                                            val c = project.crop
                                            when (drag.edge) {
                                                "left" -> c.left = 0.6.coerceAtMost(0.0.coerceAtLeast(nx))
                                                "right" -> c.right = 0.6.coerceAtMost(0.0.coerceAtLeast(1 - nx))
                                                "top" -> c.top = 0.6.coerceAtMost(0.0.coerceAtLeast(ny))
                                                "bottom" -> c.bottom = 0.6.coerceAtMost(0.0.coerceAtLeast(1 - ny))
                                            }
                                            if (c.left + c.right > 0.85) {
                                                if (drag.edge == "left") c.left = 0.85 - c.right
                                                else c.right = 0.85 - c.left
                                            }
                                            if (c.top + c.bottom > 0.85) {
                                                if (drag.edge == "top") c.top = 0.85 - c.bottom
                                                else c.bottom = 0.85 - c.top
                                            }
                                            uiRev++
                                        }
                                        "lane" -> {
                                            val lane = project.lanes.find { it.id == drag.laneId } ?: return@SourceStage
                                            lane.x = 0.995.coerceAtMost(0.005.coerceAtLeast(nx))
                                            if (linkGuides) {
                                                val sorted = project.lanes.sortedBy { it.x }
                                                if (sorted.size > 2 && (lane.id == sorted.first().id || lane.id == sorted.last().id)) {
                                                    val first = sorted.first().x
                                                    val last = sorted.last().x
                                                    sorted.forEachIndexed { i, l ->
                                                        if (i == 0 || i == sorted.size - 1) return@forEachIndexed
                                                        l.x = first + ((last - first) * i) / (sorted.size - 1)
                                                    }
                                                }
                                            }
                                            uiRev++
                                        }
                                        "band" -> {
                                            val lane = project.lanes.find { it.id == drag.laneId } ?: return@SourceStage
                                            val band = lane.bands.getOrNull(drag.index ?: -1) ?: return@SourceStage
                                            band.y = 0.995.coerceAtMost(0.005.coerceAtLeast(ny))
                                            uiRev++
                                        }
                                    }
                                },
                                onUp = {
                                    val wasBand = drag.type == "band"
                                    drag.type = ""
                                    schedulePersist()
                                    if (wasBand && project.fit != null) {
                                        fitCurrentMarkers()
                                    }
                                    uiRev++
                                },
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(t("尚未导入胶图，点击「导入胶图」选择图片"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // figure pane
                Column(Modifier.weight(1f).fillMaxWidth().padding(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t("成图预览"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(t("单击泳道名编辑；拖动泳道名建立分组；单击条件格切换 +/−"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth()
                            .background(Color(0xFF04060A))
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        val lay = layout
                        val proc = processed
                        if (lay != null && hasImage && proc != null) {
                            val fitted = min(
                                1.4f,
                                max(
                                    0.2f,
                                    min(
                                        (maxWidth.value - 20f) / lay.width.toFloat(),
                                        (maxHeight.value - 20f) / lay.height.toFloat(),
                                    ),
                                ),
                            )
                            LaunchedEffect(fitted) { figScale = fitted.toDouble() }
                            val preview: androidx.compose.ui.graphics.ImageBitmap? by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, lay, proc, fitted, uiRev) {
                                kotlinx.coroutines.withContext(Dispatchers.Default) {
                                    val w = max(1, Math.round(lay.width * fitted * density.density).toInt())
                                    val h = max(1, Math.round(lay.height * fitted * density.density).toInt())
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    GelRender.drawFigure(GCanvas(bmp), lay, proc, (fitted * density.density).toDouble())
                                    value = bmp.asImageBitmap()
                                }
                            }
                            Box {
                                Canvas(
                                    Modifier
                                        .width((lay.width * fitted).dp)
                                        .height((lay.height * fitted).dp)
                                        .pointerInput(lay, fitted, uiRev) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val pxPerUnit = fitted * density.density // 屏幕 px / layout 单位
                                                var fx = down.position.x
                                                var fy = down.position.y
                                                val px = fx / pxPerUnit
                                                val py = fy / pxPerUnit
                                                val hit = GelFigure.hitTest(lay, px.toDouble(), py.toDouble())
                                                if (hit is FigHit.LaneName) {
                                                    nameDragFrom = hit.laneId
                                                    nameDragMoved = false
                                                    nameDragX0 = down.position.x
                                                    nameDragY0 = down.position.y
                                                    selectedLaneId = hit.laneId
                                                    uiRev++
                                                } else if (hit is FigHit.Band) {
                                                    selectedBand = hit.laneId to hit.bandIndex
                                                    selectedLaneId = hit.laneId
                                                    uiRev++
                                                } else if (hit is FigHit.ConditionCell) {
                                                    cycleCondition(hit.rowId, hit.laneId)
                                                } else if (hit is FigHit.Annotation) {
                                                    selectedAnnotationId = hit.annotationId
                                                    uiRev++
                                                    val ann = project.annotations.find { it.id == hit.annotationId }
                                                    if (ann is GelAnnotation.Box) {
                                                        checkpoint()
                                                        annDrag.id = ann.id
                                                        annDrag.isBox = true
                                                        annDrag.startX = px.toDouble()
                                                        annDrag.startY = py.toDouble()
                                                        annDrag.x = ann.x
                                                        annDrag.y = ann.y
                                                        annDragActive = true
                                                    } else if (ann is GelAnnotation.SideText) {
                                                        checkpoint()
                                                        annDrag.id = ann.id
                                                        annDrag.isBox = false
                                                        annDrag.startX = px.toDouble()
                                                        annDrag.startY = py.toDouble()
                                                        annDragActive = true
                                                    }
                                                }
                                                // 双击条带切换标签（300ms 内两次点按同一命中）。
                                                val now = System.currentTimeMillis()
                                                val key = hit?.toString() ?: ""
                                                val isDouble = now - lastFigTapTime < 300 && lastFigTapKey == key
                                                lastFigTapTime = now
                                                lastFigTapKey = key
                                                if (isDouble && hit is FigHit.Band) {
                                                    val lane = project.lanes.find { it.id == hit.laneId }
                                                    val band = lane?.bands?.getOrNull(hit.bandIndex)
                                                    if (band != null) commit { band.showSize = band.showSize != true }
                                                }
                                                if (annDragActive) {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        fx = change.position.x
                                                        fy = change.position.y
                                                        if (!change.pressed) {
                                                            annDragActive = false
                                                            schedulePersist()
                                                            change.consume()
                                                            break
                                                        }
                                                        val cx = fx / pxPerUnit
                                                        val cy = fy / pxPerUnit
                                                        val visW = 1 - project.crop.left - project.crop.right
                                                        val visH = 1 - project.crop.top - project.crop.bottom
                                                        val ann = project.annotations.find { it.id == annDrag.id }
                                                        if (ann is GelAnnotation.Box) {
                                                            val dx = ((cx - annDrag.startX) / lay.gel.w) * visW
                                                            val dy = ((cy - annDrag.startY) / lay.gel.h) * visH
                                                            ann.x = (1 - ann.w).coerceAtMost(0.0.coerceAtLeast(annDrag.x + dx))
                                                            ann.y = (1 - ann.h).coerceAtMost(0.0.coerceAtLeast(annDrag.y + dy))
                                                            uiRev++
                                                        } else if (ann is GelAnnotation.SideText) {
                                                            val rel = if (ann.side == "left" || ann.side == "right") {
                                                                (cy - lay.gel.y) / lay.gel.h
                                                            } else {
                                                                (cx - lay.gel.x) / lay.gel.w
                                                            }
                                                            ann.pos = 1.0.coerceAtMost(0.0.coerceAtLeast(rel))
                                                            uiRev++
                                                        }
                                                        change.consume()
                                                    }
                                                } else if (nameDragFrom != null) {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        fx = change.position.x
                                                        fy = change.position.y
                                                        if (!change.pressed) {
                                                            val from = nameDragFrom
                                                            nameDragFrom = null
                                                            if (from != null) {
                                                                if (nameDragMoved) {
                                                                    val finalHit = GelFigure.hitTest(lay, (fx / pxPerUnit).toDouble(), (fy / pxPerUnit).toDouble())
                                                                    if (finalHit is FigHit.LaneName) addGroupFromRange(from, finalHit.laneId)
                                                                } else {
                                                                    startNameEdit(from)
                                                                }
                                                            }
                                                            change.consume()
                                                            break
                                                        }
                                                        if (!nameDragMoved && kotlin.math.hypot(
                                                                (fx - nameDragX0).toDouble(),
                                                                (fy - nameDragY0).toDouble(),
                                                            ) > 6
                                                        ) nameDragMoved = true
                                                        change.consume()
                                                    }
                                                }
                                            }
                                        },
                                ) {
                                    preview?.let { drawImage(it, Offset.Zero) }
                                }
                                val editing = editingLane
                                if (editing != null) {
                                    BasicTextField(
                                        value = editing.value,
                                        onValueChange = { v -> editingLane = editing.copy(value = v) },
                                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF111111)),
                                        modifier = Modifier
                                            .offset { IntOffset((editing.x * density.density).toInt(), (editing.y * density.density).toInt()) }
                                            .width(editing.w.dp)
                                            .background(Color.White)
                                            .padding(2.dp),
                                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { commitNameEdit(true) }),
                                        singleLine = true,
                                    )
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(t("尚未导入胶图，点击「导入胶图」选择图片"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
