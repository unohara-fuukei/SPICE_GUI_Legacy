package com.spice.nativeapp.tools

/*
 * Western blot densitometry workstation —— 1:1 移植自原版
 * src/lib/tools/blot/BlotTool.svelte。
 * UI 词条全部使用 main zh 原文（I18n.t 直查 core 词典；命名占位符用 bi() 本地插值）。
 *
 * 平台差异（README Known gaps 披露）：
 * - ToolProjectBar / projectStore（项目中心 handoff）无安卓侧等价物；
 * - sendImageToFigure（跨工具实时桥）→ PNG 落盘 files/figures/；
 * - 文件拖放（onDrop）与桌面键盘快捷键（Ctrl+Z/Y/S、Delete、方向键）不适用触屏
 *   （blotDropHint/blotKeyboardHint 相应不渲染）；
 * - localStorage → SharedPreferences（BlotStorage，快照含 imageDataUrl）；Web Worker → 协程；
 * - utif2 → 内置 GelTiff 解码器；后台 saveExport → filesDir/blot-exports 落盘。
 */
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.GelGenie
import com.spice.nativeapp.core.I18n
import com.spice.nativeapp.tools.blot.MAX_BLOT_BATCH_IMAGES
import com.spice.nativeapp.tools.blot.AnnotationOptions
import com.spice.nativeapp.tools.blot.BandMeasurement
import com.spice.nativeapp.tools.blot.BlotBatchOptions
import com.spice.nativeapp.tools.blot.BlotBand
import com.spice.nativeapp.tools.blot.BlotCrop
import com.spice.nativeapp.tools.blot.BlotLane
import com.spice.nativeapp.tools.blot.BlotProject
import com.spice.nativeapp.tools.blot.BlotReproInput
import com.spice.nativeapp.tools.blot.BlotStorage
import com.spice.nativeapp.tools.blot.ChartOptions
import com.spice.nativeapp.tools.blot.CsvLabels
import com.spice.nativeapp.tools.blot.FigureOptions
import com.spice.nativeapp.tools.blot.GroupStat
import com.spice.nativeapp.tools.blot.ProcessedImage
import com.spice.nativeapp.tools.blot.QuantResult
import com.spice.nativeapp.tools.blot.annotatedCanvas
import com.spice.nativeapp.tools.blot.annotatedPng
import com.spice.nativeapp.tools.blot.bandsFromDetection
import com.spice.nativeapp.tools.blot.bi
import com.spice.nativeapp.tools.blot.blotBatchZipToBytes
import com.spice.nativeapp.tools.blot.buildBlotBatch
import com.spice.nativeapp.tools.blot.chartBitmap
import com.spice.nativeapp.tools.blot.chartSvg
import com.spice.nativeapp.tools.blot.clamp
import com.spice.nativeapp.tools.blot.cropPixels
import com.spice.nativeapp.tools.blot.decodeImageFile
import com.spice.nativeapp.tools.blot.detectBands
import com.spice.nativeapp.tools.blot.detectLanes
import com.spice.nativeapp.tools.blot.emptyProject
import com.spice.nativeapp.tools.blot.figureBitmap
import com.spice.nativeapp.tools.blot.figureSvg
import com.spice.nativeapp.tools.blot.groupNames
import com.spice.nativeapp.tools.blot.lanesFromCount
import com.spice.nativeapp.tools.blot.lanesFromDetection
import com.spice.nativeapp.tools.blot.loadBitmapFromDataUrl
import com.spice.nativeapp.tools.blot.newId
import com.spice.nativeapp.tools.blot.nowIso
import com.spice.nativeapp.tools.blot.quantify
import com.spice.nativeapp.tools.blot.renderProcessed
import com.spice.nativeapp.tools.blot.restoreSnapshot
import com.spice.nativeapp.tools.blot.resultsCsv
import com.spice.nativeapp.tools.blot.rotateCoordinates
import com.spice.nativeapp.tools.blot.saveBlotReproBundle
import com.spice.nativeapp.tools.blot.sanitizeProject
import com.spice.nativeapp.tools.blot.snapshotProject
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.LabeledSlider
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixNumberField
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.PixTextField
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun t(zh: String): String = I18n.t(zh)

private fun interp(template: String, vararg pairs: Pair<String, Any?>): String {
    var out = I18n.t(template)
    pairs.forEach { (k, v) -> out = out.replace("{$k}", v?.toString() ?: "") }
    return out
}

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

private fun readUriBytes(ctx: android.content.Context, uri: Uri): ByteArray =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

private fun readUriText(ctx: android.content.Context, uri: Uri): String =
    String(readUriBytes(ctx, uri), Charsets.UTF_8)

private class DragState(
    val kind: String,
    val laneId: String? = null,
    val bandId: String? = null,
    val startX: Double = 0.0,
    val startY: Double = 0.0,
    val origX: Double = 0.0,
    val origWidth: Double = 0.0,
    val origY: Double = 0.0,
    val origHeight: Double = 0.0,
    val crop: BlotCrop? = null,
)

private class CropDrag(var startNx: Double, var startNy: Double, var nx: Double, var ny: Double)

// ------------------------------------------------------------------ screen

@Composable
fun BlotScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var project by remember { mutableStateOf(emptyProject()) }
    var processed by remember { mutableStateOf<ProcessedImage?>(null) }
    var imageEl by remember { mutableStateOf<Bitmap?>(null) }
    var selectedLaneId by remember { mutableStateOf<String?>(null) }
    var selectedBandId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("select") }
    var rightTab by remember { mutableStateOf("results") }
    var zoom by remember { mutableStateOf(1.0) }
    var busy by remember { mutableStateOf("") }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var uiRev by remember { mutableIntStateOf(0) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchTotal by remember { mutableStateOf(0) }
    var batchDone by remember { mutableStateOf(0) }
    var batchName by remember { mutableStateOf("") }
    var batchOk by remember { mutableStateOf(0) }
    var batchFailed by remember { mutableStateOf(0) }
    var figureBmp by remember { mutableStateOf<Bitmap?>(null) }
    val dragState = remember { mutableStateOf<DragState?>(null) }
    val lastCropDrag = remember { mutableStateOf<CropDrag?>(null) }
    var dragCropActive by remember { mutableStateOf(false) }

    val history = remember { ArrayList<String>() }
    val future = remember { ArrayList<String>() }
    var lastCheckpoint by remember { mutableStateOf(0L) }
    var quotaWarned by remember { mutableStateOf(false) }
    var persistJob by remember { mutableStateOf<Job?>(null) }

    // -------------------------------------------------------------- derived

    val hasImage = project.imageDataUrl.isNotEmpty()
    val orderedLanes = project.lanes.sortedBy { it.x }
    val selectedLane = project.lanes.find { it.id == selectedLaneId }
    val selectedBand = selectedLane?.bands?.find { it.id == selectedBandId }
    val groups = groupNames(project.lanes)
    val metricLabel = if (project.chartMetric == "fold") t("倍数变化") else t("归一化信号")

    // Live quantification (main's $derived quant).
    val quantLive = remember(processed, uiRev) {
        processed?.let { quantify(project, it.rawGray, it.width, it.height) }
    }

    fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun drawStage() {
        uiRev++
    }

    fun schedulePersist() {
        project.updatedAt = nowIso()
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(700)
            if (!BlotStorage.saveSnapshot(ctx, project) && !quotaWarned) {
                quotaWarned = true
                toast(t("浏览器存储已满，自动保存失败。请手动保存项目。"), long = true)
            }
        }
    }

    // ------------------------------------------------------------- history

    fun checkpoint(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckpoint < 600) return
        history.add(snapshotProject(project))
        if (history.size > 60) history.removeAt(0)
        future.clear()
        lastCheckpoint = now
        canUndo = true
        canRedo = false
    }

    fun commit(fn: () -> Unit) {
        checkpoint()
        fn()
        drawStage()
        schedulePersist()
    }

    fun pruneSelection() {
        if (selectedLaneId != null && project.lanes.none { it.id == selectedLaneId }) {
            selectedLaneId = null
            selectedBandId = null
        }
        if (selectedBandId != null && selectedLane?.bands?.none { it.id == selectedBandId } == true) {
            selectedBandId = null
        }
    }

    fun syncNormalization() {
        val laneIds = project.lanes.map { it.id }.toSet()
        if (project.normalization.loadingLaneId == null || !laneIds.contains(project.normalization.loadingLaneId)) {
            project.normalization.loadingLaneId = orderedLanes.firstOrNull()?.id
        }
        if (!groups.contains(project.normalization.controlGroup)) {
            project.normalization.controlGroup = groups.firstOrNull() ?: ""
        }
    }

    // ----------------------------------------------------------- processing

    fun normalizeCrop() {
        val crop = project.crop
        crop.left = clamp(crop.left, 0.0, 0.9)
        crop.top = clamp(crop.top, 0.0, 0.9)
        crop.right = clamp(crop.right, 0.0, 0.9)
        crop.bottom = clamp(crop.bottom, 0.0, 0.9)
        val maxSpan = 0.98
        if (crop.left + crop.right > maxSpan) {
            val scale = maxSpan / (crop.left + crop.right)
            crop.left *= scale
            crop.right *= scale
        }
        if (crop.top + crop.bottom > maxSpan) {
            val scale = maxSpan / (crop.top + crop.bottom)
            crop.top *= scale
            crop.bottom *= scale
        }
    }

    suspend fun refreshProcessed() {
        val source = imageEl
        if (source == null) {
            processed = null
            uiRev++
            return
        }
        withContext(Dispatchers.Default) {
            normalizeCrop()
            processed = renderProcessed(project, source)
        }
        uiRev++
    }

    fun scheduleRender() {
        scope.launch { refreshProcessed() }
    }

    suspend fun loadProjectImage() {
        if (project.imageDataUrl.isEmpty()) {
            imageEl = null
            processed = null
            drawStage()
            return
        }
        try {
            imageEl = withContext(Dispatchers.Default) { loadBitmapFromDataUrl(project.imageDataUrl) }
            if (imageEl == null) throw IllegalArgumentException("decode failed")
            refreshProcessed()
            syncNormalization()
        } catch (e: Exception) {
            toast(t("无法解码该图像 - 请使用 PNG、JPEG、WebP 或 TIFF 文件"), long = true)
        }
    }

    fun undo() {
        if (history.isEmpty()) return
        val snapshot = history.removeAt(history.size - 1)
        val current = snapshotProject(project)
        val restored = restoreSnapshot(snapshot, project.imageDataUrl) ?: return
        future.add(current)
        project = restored
        canUndo = history.isNotEmpty()
        canRedo = true
        pruneSelection()
        syncNormalization()
        scope.launch { refreshProcessed() }
        schedulePersist()
    }

    fun redo() {
        if (future.isEmpty()) return
        val snapshot = future.removeAt(future.size - 1)
        val current = snapshotProject(project)
        val restored = restoreSnapshot(snapshot, project.imageDataUrl) ?: return
        history.add(current)
        project = restored
        canUndo = true
        canRedo = future.isNotEmpty()
        pruneSelection()
        syncNormalization()
        scope.launch { refreshProcessed() }
        schedulePersist()
    }

    // ------------------------------------------------------------- detection

    fun defaultGroup(): String = t("对照")

    /** Keep user-placed bands that no longer coincide with a detected peak. */
    fun mergeManualBands(lane: BlotLane, detected: List<BlotBand>): MutableList<BlotBand> {
        val merged = detected.toMutableList()
        for (band in lane.bands) {
            if (band.manual && merged.none { abs(it.y - band.y) < 0.012 }) {
                merged.add(band.copy())
            }
        }
        merged.sortBy { it.y }
        return merged
    }

    /** GelGenie U-Net band segmentation (native, downloaded model). */
    suspend fun aiDetect() {
        val proc = processed ?: return
        if (busy.isNotEmpty()) return
        busy = t("GelGenie 正在识别...")
        try {
            val result = withContext(Dispatchers.Default) {
                val gray = ByteArray(proc.rawGray.size)
                for (i in proc.rawGray.indices) {
                    gray[i] = (proc.rawGray[i] * 255).roundToInt().coerceIn(0, 255).toByte()
                }
                GelGenie.segment(ctx, gray, proc.width, proc.height)
            }
            if (result.lanes.isEmpty()) {
                toast(t("GelGenie 未识别到条带"))
                return
            }
            commit {
                project.lanes = result.lanes.mapIndexed { index, lane ->
                    BlotLane(
                        id = newId("lane"),
                        x = lane.x.toDouble(),
                        width = lane.width.toDouble(),
                        name = "${index + 1}",
                        sample = "",
                        group = defaultGroup(),
                        bands = lane.bands.mapNotNull { bandIndex ->
                            val band = result.bands.getOrNull(bandIndex) ?: return@mapNotNull null
                            BlotBand(
                                id = newId("band"),
                                y = clamp((band.y + band.height / 2).toDouble(), 0.005, 0.995),
                                height = clamp(band.height.toDouble(), 0.005, 0.5),
                                width = clamp(band.width.toDouble(), 0.008, 0.5),
                            )
                        }.toMutableList(),
                    )
                }.toMutableList()
                selectedLaneId = project.lanes.firstOrNull()?.id
                selectedBandId = null
                syncNormalization()
            }
            toast(interp("GelGenie：{lanes} 条泳道，{bands} 条带", "lanes" to result.lanes.size, "bands" to result.bands.size))
        } catch (error: Throwable) {
            val text = error.message ?: error.toString()
            toast(
                if (text.contains("download", true) || text.contains("模型"))
                    t("请先在设置中下载 GelGenie 模型")
                else "${t("GelGenie 识别失败")} $text",
                long = true,
            )
        } finally {
            busy = ""
        }
    }

    suspend fun autoDetect(notify: Boolean = true) {
        val proc = processed ?: return
        if (busy.isNotEmpty()) return
        busy = t("正在检测泳道...")
        try {
            checkpoint(true)
            val previous = project.lanes.sortedBy { it.x }
            val found = withContext(Dispatchers.Default) {
                detectLanes(proc.rawGray, proc.width, proc.height, project.crop)
            }
            if (found.isEmpty()) {
                toast(t("未检测到泳道 - 调整裁剪或手动添加"))
                return
            }
            val next = withContext(Dispatchers.Default) {
                val lanes = lanesFromDetection(found, project.lanes, defaultGroup())
                lanes.forEachIndexed { index, lane ->
                    val detected = bandsFromDetection(
                        detectBands(proc.rawGray, proc.width, proc.height, lane, project.crop),
                        lane.width,
                    )
                    val carried = previous.getOrNull(index)?.bands
                        ?.filter { it.manual }
                        ?.map { it.copy(id = newId("band"), width = lane.width * 0.9) }
                        ?: emptyList()
                    val tempLane = BlotLane(
                        id = lane.id, x = lane.x, width = lane.width, name = lane.name,
                        sample = lane.sample, group = lane.group, bands = carried.toMutableList(),
                    )
                    lane.bands = mergeManualBands(tempLane, detected)
                }
                lanes
            }
            project.lanes = next.toMutableList()
            selectedLaneId = next.firstOrNull()?.id
            selectedBandId = null
            syncNormalization()
            drawStage()
            schedulePersist()
            if (notify) toast(interp("检测到 {n} 条泳道", "n" to next.size))
        } finally {
            busy = ""
        }
    }

    fun detectBandsFor(lane: BlotLane) {
        val proc = processed ?: return
        val found = detectBands(proc.rawGray, proc.width, proc.height, lane, project.crop)
        if (found.isEmpty()) {
            toast(t("该泳道未检测到条带"))
            return
        }
        val merged = mergeManualBands(lane, bandsFromDetection(found, lane.width))
        commit {
            lane.bands = merged
            selectedLaneId = lane.id
            selectedBandId = null
        }
        toast(interp("检测到 {n} 条条带", "n" to merged.size))
    }

    fun detectBandsAll() {
        val proc = processed ?: return
        var count = 0
        val next = project.lanes.map { lane ->
            val found = detectBands(proc.rawGray, proc.width, proc.height, lane, project.crop)
            val merged = mergeManualBands(lane, bandsFromDetection(found, lane.width))
            count += merged.size
            BlotLane(
                id = lane.id, x = lane.x, width = lane.width, name = lane.name,
                sample = lane.sample, group = lane.group, excluded = lane.excluded,
                bands = merged,
            )
        }
        if (count == 0) {
            toast(t("该泳道未检测到条带"))
            return
        }
        commit {
            project.lanes = next.toMutableList()
            selectedBandId = null
        }
        toast(interp("检测到 {n} 条条带", "n" to count))
    }

    fun setLaneCount(count: Int?) {
        if (count == null || count < 1 || count > 40) return
        commit {
            project.lanes = lanesFromCount(count, project.lanes, project.crop, defaultGroup()).toMutableList()
            selectedBandId = null
            pruneSelection()
            syncNormalization()
        }
    }

    fun addLane() {
        commit {
            val x = selectedLane?.let { clamp(it.x + 0.08, 0.02, 0.98) } ?: 0.5
            val lane = BlotLane(
                id = newId("lane"), x = x, width = 0.05,
                name = "${project.lanes.size + 1}", sample = "", group = defaultGroup(),
                bands = mutableListOf(),
            )
            project.lanes.add(lane)
            selectedLaneId = lane.id
            selectedBandId = null
            syncNormalization()
        }
    }

    fun removeLane(laneId: String) {
        commit {
            project.lanes.removeAll { it.id == laneId }
            if (selectedLaneId == laneId) {
                selectedLaneId = null
                selectedBandId = null
            }
            syncNormalization()
        }
    }

    fun toggleLaneExcluded(lane: BlotLane) {
        commit { lane.excluded = !lane.excluded }
    }

    // ---------------------------------------------------------------- bands

    fun addBandAt(nx: Double, ny: Double) {
        if (processed == null) return
        val existing = project.lanes.find { abs(nx - it.x) <= it.width / 2 && !it.excluded }
        commit {
            val target = existing ?: BlotLane(
                id = newId("lane"), x = clamp(nx, 0.02, 0.98), width = 0.05,
                name = "${project.lanes.size + 1}", sample = "", group = defaultGroup(),
                bands = mutableListOf(),
            ).also { project.lanes.add(it) }
            val band = BlotBand(
                id = newId("band"),
                y = clamp(ny, 0.005, 0.995),
                height = 0.03,
                width = max(0.008, target.width * 0.8),
                manual = true,
                excluded = false,
            )
            target.bands.add(band)
            selectedLaneId = target.id
            selectedBandId = band.id
            syncNormalization()
        }
    }

    fun removeBand(laneId: String, bandId: String) {
        commit {
            val lane = project.lanes.find { it.id == laneId } ?: return@commit
            lane.bands.removeAll { it.id == bandId }
            if (selectedBandId == bandId) selectedBandId = null
        }
    }

    fun deleteSelectedBand() {
        val lane = selectedLane ?: return
        val band = selectedBand ?: return
        removeBand(lane.id, band.id)
    }

    fun toggleBandExcluded(band: BlotBand) {
        commit { band.excluded = !band.excluded }
    }

    fun updateBand(patch: (BlotBand) -> Unit) {
        val band = selectedBand ?: return
        commit { patch(band) }
    }

    fun nudgeSelectedBand(delta: Double) {
        val band = selectedBand ?: return
        commit { band.y = clamp(band.y + delta, 0.005, 0.995) }
    }

    // -------------------------------------------------------- image controls

    fun setDisplay(display: String) {
        if (project.adjust.display == display) return
        commit { project.adjust.display = display }
        scheduleRender()
    }

    fun resetLevels() {
        commit {
            project.adjust.contrast = 1.0
            project.adjust.brightness = 0.0
            project.adjust.gamma = 1.0
            project.adjust.invert = false
        }
        scheduleRender()
    }

    fun autoLevels() {
        val proc = processed ?: return
        scope.launch(Dispatchers.Default) {
            val values = proc.rawGray
            val sample = ArrayList<Float>(min(20000, values.size))
            val step = max(1, values.size / 20000)
            var i = 0
            while (i < values.size) {
                sample.add(values[i])
                i += step
            }
            sample.sort()
            fun pick(p: Double): Double =
                sample[(min(sample.size - 1, (sample.size * p).toInt())).coerceAtLeast(0)].toDouble()
            val lo = pick(0.02)
            val hi = pick(0.98)
            withContext(Dispatchers.Main) {
                commit {
                    project.adjust.brightness = 0.0
                    if (hi - lo < 0.02) {
                        project.adjust.contrast = 1.0
                        project.adjust.gamma = 1.0
                        return@commit
                    }
                    val contrast = min(1.9, max(1.0, 0.9 / (hi - lo)))
                    val mid = clamp((lo + hi) / 2, 0.05, 0.95)
                    val shifted = clamp((mid - 0.5) * contrast + 0.5, 0.05, 0.95)
                    val gamma = kotlin.math.ln(0.5) / kotlin.math.ln(shifted)
                    project.adjust.contrast = (contrast * 100).roundToInt() / 100.0
                    project.adjust.gamma = (clamp(gamma, 0.3, 2.5) * 100).roundToInt() / 100.0
                }
                scheduleRender()
            }
        }
    }

    fun setRotation(next: Double) {
        val value = clamp(next, -15.0, 15.0)
        if (abs(value - project.rotation) < 0.001) return
        imageEl?.let {
            rotateCoordinates(project, project.rotation, value, it.width, it.height)
        }
        project.rotation = value
        scheduleRender()
        schedulePersist()
    }

    fun rotateQuarter(clockwise: Boolean) {
        if (processed == null) return
        val previous = project.lanes
        checkpoint(true)
        scope.launch {
            project.orient = (((project.orient + (if (clockwise) 1 else 3)) % 4) + 4) % 4
            project.crop.left = 0.0
            project.crop.top = 0.0
            project.crop.right = 0.0
            project.crop.bottom = 0.0
            refreshProcessed()
            val fresh = processed
            if (fresh != null) {
                val next = withContext(Dispatchers.Default) {
                    val found = detectLanes(fresh.rawGray, fresh.width, fresh.height, project.crop)
                    val lanes = lanesFromDetection(found, previous, defaultGroup())
                    for (lane in lanes) {
                        lane.bands = bandsFromDetection(
                            detectBands(fresh.rawGray, fresh.width, fresh.height, lane, project.crop),
                            lane.width,
                        ).toMutableList()
                    }
                    lanes
                }
                project.lanes = next.toMutableList()
                selectedLaneId = next.firstOrNull()?.id
                selectedBandId = null
                syncNormalization()
                drawStage()
            }
            schedulePersist()
        }
    }

    fun resetCrop() {
        commit {
            project.crop.left = 0.0
            project.crop.top = 0.0
            project.crop.right = 0.0
            project.crop.bottom = 0.0
        }
        scheduleRender()
    }

    fun onCropInput() {
        normalizeCrop()
        scheduleRender()
        schedulePersist()
    }

    // ----------------------------------------------------------------- export

    fun projectBase(): String = project.imageName.ifEmpty { "blot" }.replace(Regex("\\.[^.]+$"), "")

    fun saveBytes(name: String, bytes: ByteArray): Boolean {
        return try {
            val dir = File(ctx.filesDir, "blot-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            toast("${t("文件已保存")}：blot-exports/$name")
            true
        } catch (e: Exception) {
            toast(t("导出失败"), long = true)
            false
        }
    }

    fun saveText(name: String, text: String): Boolean = saveBytes(name, text.toByteArray(Charsets.UTF_8))

    fun saveProject() {
        if (!hasImage) {
            toast(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始"))
            return
        }
        saveText("${projectBase()}.blot.json", com.spice.nativeapp.tools.blot.projectToJson(project))
    }

    fun blotCsvLabels(): CsvLabels = CsvLabels(
        lane = t("泳道"),
        band = t("条带"),
        sample = t("样本"),
        group = t("分组"),
        included = t("纳入"),
        area = t("面积 px"),
        mean = t("均值"),
        background = t("背景"),
        volume = t("净体积"),
        saturated = t("饱和"),
        normalized = t("归一化"),
        fold = t("倍数"),
        groupMean = t("组均值"),
        groupSd = t("标准差"),
        groupSem = t("标准误"),
        groupCi95 = t("CI95 ±"),
        groupP = t("p（对照）"),
        groupN = t("n"),
        metric = t("图表指标"),
    )

    fun exportCsv() {
        val quant = quantLive
        if (quant == null || quant.rows.isEmpty()) {
            toast(t("尚无定量条带"))
            return
        }
        val csv = resultsCsv(project, quant, blotCsvLabels())
        saveText("${projectBase()}_densitometry.csv", csv)
    }

    fun exportAnnotatedPng() {
        val proc = processed ?: run { toast(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始")); return }
        busy = t("正在导出...")
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val canvas = annotatedCanvas(
                        project, proc.canvas,
                        AnnotationOptions(
                            selectedLaneId = selectedLaneId,
                            selectedBandId = null,
                            laneLabels = project.showLaneLabels,
                            roi = project.showRoi,
                        ),
                    )
                    val summary = "${project.title.ifEmpty { t("印迹定量") }}; ${project.lanes.size} lanes; " +
                        "${project.exportDpi} dpi; background ${project.backgroundMode}; " +
                        "normalisation ${project.normalization.mode}"
                    annotatedPng(canvas, project.exportDpi, summary)
                }
                saveBytes("${projectBase()}_annotated_${project.exportDpi}dpi.png", bytes)
            } catch (e: Exception) {
                toast(t("导出失败"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun sendToFigureNow() {
        val proc = processed ?: run { toast(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始")); return }
        if (busy.isNotEmpty()) return
        // 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（README 披露）。
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val canvas = annotatedCanvas(
                        project, proc.canvas,
                        AnnotationOptions(
                            selectedLaneId = selectedLaneId,
                            selectedBandId = null,
                            laneLabels = project.showLaneLabels,
                            roi = project.showRoi,
                        ),
                    )
                    val png = ByteArrayOutputStream().let { out ->
                        canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                    File(dir, "${projectBase()}_annotated.png").writeBytes(png)
                }
            } catch (error: Exception) {
                toast(t("无法发送到 Figure Studio"), long = true)
            }
        }
    }

    fun figureOptions(): FigureOptions = FigureOptions(
        title = project.title,
        chartTitle = project.chartTitle,
        metricLabel = metricLabel,
        emptyLabel = t("尚无定量条带"),
        nLabel = t("n"),
        noGroupLabel = t("无分组"),
        footer = t("SPICE 印迹定量"),
    )

    fun exportFigureSvg() {
        val proc = processed
        val quant = quantLive
        if (proc == null || quant == null) {
            toast(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始"))
            return
        }
        scope.launch {
            val svg = withContext(Dispatchers.Default) {
                figureSvg(project, proc.canvas, quant, figureOptions())
            }
            saveText("${projectBase()}_figure.svg", svg)
        }
    }

    fun exportReproBundle() {
        val proc = processed
        val quant = quantLive
        if (proc == null || quant == null || quant.rows.isEmpty()) {
            toast(t("请先导入图像并定量条带"))
            return
        }
        busy = t("正在生成可复现数据包...")
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    saveBlotReproBundle(
                        ctx,
                        BlotReproInput(
                            project = project,
                            processed = proc,
                            quant = quant,
                            title = project.title.ifEmpty { t("免疫印迹") },
                            csvLabels = blotCsvLabels(),
                            figure = figureOptions(),
                        ),
                    )
                }
                toast("${t("可复现数据包已保存")}：${result.fileName}")
            } catch (error: Exception) {
                toast(error.message ?: t("无法生成可复现数据包"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun runBatch(files: List<Pair<String, ByteArray>>) {
        if (batchRunning) return
        if (files.isEmpty()) {
            toast(t("未选择印迹图像"))
            return
        }
        batchRunning = true
        batchTotal = files.size
        batchDone = 0
        batchName = ""
        batchOk = 0
        batchFailed = 0
        scope.launch {
            try {
                val built = withContext(Dispatchers.Default) {
                    buildBlotBatch(
                        files,
                        BlotBatchOptions(
                            defaultGroup = defaultGroup(),
                            csvLabels = blotCsvLabels(),
                            onProgress = { done, total, name ->
                                batchDone = done
                                batchTotal = total
                                batchName = name
                            },
                        ),
                    )
                }
                batchOk = built.processed
                batchFailed = built.failed
                if (built.images == 0) {
                    toast(t("未选择印迹图像"))
                    return@launch
                }
                val bytes = withContext(Dispatchers.Default) { blotBatchZipToBytes(built) }
                val stamp = nowIso().substring(0, 10)
                val saved = saveBytes("blot-batch-$stamp.zip", bytes)
                if (saved) {
                    toast(interp("批量完成：成功 {ok}，失败 {failed}", "ok" to built.processed, "failed" to built.failed))
                } else {
                    toast(t("批量导出失败"), long = true)
                }
            } catch (error: Exception) {
                toast(error.message ?: t("批量导出失败"), long = true)
            } finally {
                batchRunning = false
            }
        }
    }

    fun resetAll() {
        history.clear()
        future.clear()
        canUndo = false
        canRedo = false
        imageEl = null
        processed = null
        project = emptyProject()
        project.title = t("免疫印迹")
        selectedLaneId = null
        selectedBandId = null
        mode = "select"
        BlotStorage.clearSnapshot(ctx)
        drawStage()
        toast(t("项目已重置"))
    }

    // ---------------------------------------------------------------- render

    fun showValue(value: Double, digits: Int = 3): String {
        if (!value.isFinite()) return "0"
        return String.format("%.${digits}f", value)
    }

    fun showP(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        if (value > 0 && value < 0.0001) {
            val exp = floor(log10(value)).toInt()
            val mant = value / 10.0.pow(exp)
            return String.format("%.2f", mant) + "e" + (if (exp < 0) "-" + abs(exp) else exp.toString())
        }
        return String.format("%.4f", value)
    }

    // ---------------------------------------------------------------- import

    fun importImageFile(fileName: String, bytes: ByteArray) {
        busy = t("正在导入图像...")
        scope.launch {
            try {
                val decoded = withContext(Dispatchers.Default) { decodeImageFile(fileName, bytes) }
                imageEl = withContext(Dispatchers.Default) { loadBitmapFromDataUrl(decoded.dataUrl) }
                if (imageEl == null) throw IllegalArgumentException("decode failed")
                checkpoint(true)
                project = emptyProject().apply {
                    createdAt = nowIso()
                    imageName = decoded.name
                    imageDataUrl = decoded.dataUrl
                    sourceWidth = decoded.width
                    sourceHeight = decoded.height
                    title = t("免疫印迹")
                }
                selectedLaneId = null
                selectedBandId = null
                history.clear()
                future.clear()
                canUndo = false
                canRedo = false
                refreshProcessed()
                autoDetect(false)
                schedulePersist()
            } catch (e: Exception) {
                toast(t("无法解码该图像 - 请使用 PNG、JPEG、WebP 或 TIFF 文件"), long = true)
            } finally {
                busy = ""
            }
        }
    }

    fun openProjectText(text: String) {
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) {
                    sanitizeProject(JSONObject(text))
                } ?: throw IllegalArgumentException(t("该文件不是有效的印迹项目"))
                project = parsed
                if (project.title.isEmpty()) project.title = t("免疫印迹")
                imageEl = null
                processed = null
                selectedLaneId = project.lanes.firstOrNull()?.id
                selectedBandId = null
                history.clear()
                future.clear()
                canUndo = false
                canRedo = false
                loadProjectImage()
                syncNormalization()
                schedulePersist()
                toast(t("项目已打开"))
            } catch (err: Exception) {
                toast(err.message ?: err.toString(), long = true)
            }
        }
    }

    fun onBatchPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            toast(t("未选择印迹图像"))
            return
        }
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.take(MAX_BLOT_BATCH_IMAGES).map { uri ->
                    uriDisplayName(ctx, uri) to readUriBytes(ctx, uri)
                }
            }
            if (uris.size > MAX_BLOT_BATCH_IMAGES) {
                toast(interp("仅处理前 {n} 张图像", "n" to MAX_BLOT_BATCH_IMAGES), long = true)
            }
            runBatch(files)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uriDisplayName(ctx, uri)
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readUriBytes(ctx, uri) }
                importImageFile(name, bytes)
            }
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { readUriText(ctx, uri) }
                openProjectText(text)
            }
        }
    }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onBatchPicked(uris.toList())
    }

    fun openImagePicker() {
        imagePicker.launch(arrayOf("*/*"))
    }

    fun openProjectPicker() {
        projectPicker.launch(arrayOf("*/*"))
    }

    fun openBatchPicker() {
        batchPicker.launch(arrayOf("*/*"))
    }

    // ------------------------------------------------------------- lifecycle

    LaunchedEffect(Unit) {
        if (project.title.isEmpty()) project.title = t("免疫印迹")
        val snap = BlotStorage.loadSnapshot(ctx)
        if (snap != null) {
            project = snap
            if (project.title.isEmpty()) project.title = t("免疫印迹")
            selectedLaneId = project.lanes.firstOrNull()?.id
            loadProjectImage()
        }
    }

    // Live chart preview (main's chartMarkup, drawn on a Canvas instead of SVG injection).
    val chartBmp = remember(quantLive, uiRev) {
        quantLive?.let {
            chartBitmap(
                it, project,
                ChartOptions(
                    width = 560,
                    height = 380,
                    metricLabel = metricLabel,
                    title = project.chartTitle.ifEmpty { null },
                    emptyLabel = t("尚无定量条带"),
                    nLabel = t("n"),
                    noGroupLabel = t("无分组"),
                ),
            )
        }
    }

    // Debounced figure preview (main: 140ms timer after edits settle).
    LaunchedEffect(processed, uiRev, project.title, project.chartTitle) {
        if (processed == null || !hasImage) {
            figureBmp = null
            return@LaunchedEffect
        }
        val proc = processed ?: return@LaunchedEffect
        delay(140)
        val q = quantLive ?: return@LaunchedEffect
        figureBmp = withContext(Dispatchers.Default) {
            figureBitmap(project, proc.canvas, q, figureOptions())
        }
    }


    // ------------------------------------------------------------------ UI

    fun stageGeometry(maxW: Float, maxH: Float): StageGeom? {
        val proc = processed ?: return null
        val crop = cropPixels(project, proc.width, proc.height)
        val availW = max(120f, maxW - 18f)
        val availH = max(140f, maxH - 18f)
        val fit = min(availW / crop.sw, availH / crop.sh).coerceAtMost(2f) * zoom.toFloat()
        return StageGeom(
            cssW = max(20f, (crop.sw * fit).roundToInt().toFloat()),
            cssH = max(20f, (crop.sh * fit).roundToInt().toFloat()),
            sx = crop.sx, sy = crop.sy, sw = crop.sw, sh = crop.sh,
        )
    }

    @Composable
    fun SidePane() {
        Column(Modifier.fillMaxWidth()) {
            GelSection(t("图像与显示"), startOpen = true) {
                Column(Modifier.fillMaxWidth()) {
                    if (!hasImage) {
                        Text(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixButton(t("导入印迹图像"), onClick = { openImagePicker() })
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                            Text(project.imageName, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("${project.sourceWidth}×${project.sourceHeight}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            PixButton(t("灰度"), active = project.adjust.display == "gray", onClick = { setDisplay("gray") })
                            Spacer(Modifier.width(4.dp))
                            PixButton(t("原始彩色"), active = project.adjust.display == "color", onClick = { setDisplay("color") })
                        }
                        LabeledSlider("${t("对比度")} ${showValue(project.adjust.contrast, 2)}", project.adjust.contrast, 0.2, 3.0, 55) {
                            checkpoint(); project.adjust.contrast = it; scheduleRender(); schedulePersist()
                        }
                        LabeledSlider("${t("亮度")} ${showValue(project.adjust.brightness, 2)}", project.adjust.brightness, -0.5, 0.5, 99) {
                            checkpoint(); project.adjust.brightness = it; scheduleRender(); schedulePersist()
                        }
                        LabeledSlider("${t("伽马")} ${showValue(project.adjust.gamma, 2)}", project.adjust.gamma, 0.3, 2.5, 43) {
                            checkpoint(); project.adjust.gamma = it; scheduleRender(); schedulePersist()
                        }
                        PixCheck(t("反转显示"), project.adjust.invert) {
                            commit { project.adjust.invert = it }; scheduleRender()
                        }
                        Row {
                            PixButton(t("自动色阶"), onClick = { autoLevels() })
                            Spacer(Modifier.width(4.dp))
                            PixButton(t("重置色阶"), onClick = { resetLevels() })
                        }
                        Row {
                            PixButton(t("左旋 90 度"), onClick = { rotateQuarter(false) })
                            Spacer(Modifier.width(4.dp))
                            PixButton(t("右旋 90 度"), onClick = { rotateQuarter(true) })
                            Spacer(Modifier.width(4.dp))
                            PixButton(t("校平"), onClick = { checkpoint(); setRotation(0.0) })
                        }
                        LabeledSlider("${t("微调旋转")} ${showValue(project.rotation, 1)}°", project.rotation, -15.0, 15.0, 299) {
                            checkpoint(true); setRotation(it)
                        }
                        Row {
                            PixButton(t("裁剪"), active = mode == "crop", onClick = { mode = if (mode == "crop") "select" else "crop"; drawStage() })
                            Spacer(Modifier.width(4.dp))
                            PixButton(t("清除裁剪"), onClick = { resetCrop() })
                        }
                        LabeledSlider(t("左裁剪"), project.crop.left, 0.0, 0.8, 159) {
                            checkpoint(true); project.crop.left = it; onCropInput()
                        }
                        LabeledSlider(t("上裁剪"), project.crop.top, 0.0, 0.8, 159) {
                            checkpoint(true); project.crop.top = it; onCropInput()
                        }
                        LabeledSlider(t("右裁剪"), project.crop.right, 0.0, 0.8, 159) {
                            checkpoint(true); project.crop.right = it; onCropInput()
                        }
                        LabeledSlider(t("下裁剪"), project.crop.bottom, 0.0, 0.8, 159) {
                            checkpoint(true); project.crop.bottom = it; onCropInput()
                        }
                        Row {
                            PixCheck(t("显示 ROI"), project.showRoi) { commit { project.showRoi = it } }
                            Spacer(Modifier.width(6.dp))
                            PixCheck(t("显示泳道标签"), project.showLaneLabels) { commit { project.showLaneLabels = it } }
                        }
                    }
                }
            }
            GelSection(t("泳道与条带"), startOpen = true) {
                Column(Modifier.fillMaxWidth()) {
                    Row {
                        PixButton(t("检测泳道"), enabled = hasImage, onClick = { scope.launch { autoDetect(true) } })
                        Spacer(Modifier.width(4.dp))
                        PixButton(t("添加泳道"), enabled = hasImage, onClick = { addLane() })
                        Spacer(Modifier.width(4.dp))
                        PixButton(t("检测全部条带"), enabled = hasImage, onClick = { detectBandsAll() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("泳道数量"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        PixNumberField(project.lanes.size.toString()) { text -> setLaneCount(text.toIntOrNull()) }
                        Spacer(Modifier.width(6.dp))
                        PixButton(t("添加条带"), enabled = hasImage, active = mode == "addBand", onClick = { mode = if (mode == "addBand") "select" else "addBand"; drawStage() })
                        Spacer(Modifier.width(4.dp))
                        PixButton(t("选择"), active = mode == "select", onClick = { mode = "select"; drawStage() })
                    }
                    if (mode == "addBand") HintLine(t("在印迹上单击，将条带 ROI 放入最近的泳道。"))
                    if (mode == "crop") HintLine(t("在印迹上拖出矩形，将图像裁剪到该区域。"))
                    if (selectedBand != null && selectedLane != null) {
                        val band = selectedBand!!
                        val lane = selectedLane!!
                        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)).padding(5.dp)) {
                            Text(
                                "${t("已选条带")} · ${lane.name.ifEmpty { "—" }} #${lane.bands.indexOf(band) + 1}",
                                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                Text(t("中心 Y %"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PixNumberField(showValue(band.y * 100, 1)) { text ->
                                    text.toDoubleOrNull()?.let { updateBand { b -> b.y = clamp(it / 100, 0.0, 1.0) } }
                                }
                                Text(t("高度 %"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PixNumberField(showValue(band.height * 100, 1)) { text ->
                                    text.toDoubleOrNull()?.let { updateBand { b -> b.height = clamp(it / 100, 0.002, 0.5) } }
                                }
                                Text(t("宽度 %"), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PixNumberField(showValue(band.width * 100, 1)) { text ->
                                    text.toDoubleOrNull()?.let { updateBand { b -> b.width = clamp(it / 100, 0.002, 0.8) } }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixCheck(t("纳入"), !band.excluded) { toggleBandExcluded(band) }
                                Spacer(Modifier.width(4.dp))
                                PixButton(t("删除条带"), danger = true, onClick = { deleteSelectedBand() })
                            }
                        }
                    }
                    for (lane in orderedLanes) {
                        Row(
                            Modifier.fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (lane.id == selectedLaneId) Color(0xFFFFD23F) else Color.Transparent,
                                    RoundedCornerShape(2.dp),
                                )
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                                .alpha(if (lane.excluded) 0.6f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lane.name.ifEmpty { "—" },
                                fontSize = 9.sp, color = Color(0xFF4CD6FF),
                                modifier = Modifier.width(26.dp).clickable { selectedLaneId = lane.id; selectedBandId = null; drawStage() },
                                maxLines = 1,
                            )
                            PixTextField(lane.name, t("泳道名称")) { lane.name = it; schedulePersist(); drawStage() }
                            Text(
                                "${lane.bands.size}",
                                fontSize = 8.sp,
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                                    .clickable { detectBandsFor(lane) }
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                            Text(
                                if (lane.excluded) t("排除泳道") else t("纳入"),
                                fontSize = 8.sp,
                                color = if (lane.excluded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                                    .clickable { toggleLaneExcluded(lane) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            Text(
                                t("移除泳道"),
                                fontSize = 8.sp, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                                    .clickable { removeLane(lane.id) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (orderedLanes.isEmpty()) {
                        Text(t("尚未定义泳道"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (hasImage && project.lanes.isEmpty()) {
                        HintLine(t("使用检测泳道或设置泳道数量。"))
                    }
                }
            }
            GelSection(t("定量"), startOpen = true) {
                Column(Modifier.fillMaxWidth()) {
                    Text(t("背景"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        listOf(
                            "ring" to t("ROI 边缘环中位数"),
                            "lane" to t("泳道背景中位数"),
                            "global" to t("全局背景中位数"),
                            "manual" to t("手动水平"),
                        ),
                        project.backgroundMode,
                    ) { commit { project.backgroundMode = it } }
                    if (project.backgroundMode == "manual") {
                        LabeledSlider("${t("手动背景")} ${showValue(project.manualBackground, 3)}", project.manualBackground, 0.0, 0.5, 499) {
                            checkpoint(true); project.manualBackground = it; schedulePersist(); drawStage()
                        }
                    }
                    Text(t("归一化"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        listOf(
                            "none" to t("原始信号"),
                            "loading" to t("内参泳道"),
                            "total" to t("泳道总信号"),
                        ),
                        project.normalization.mode,
                    ) { commit { project.normalization.mode = it; syncNormalization() } }
                    if (project.normalization.mode == "loading") {
                        Text(t("内参泳道"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PixSelect(
                            orderedLanes.map { it.id to (it.name.ifEmpty { it.sample.ifEmpty { "—" } }) },
                            project.normalization.loadingLaneId ?: "",
                        ) { commit { project.normalization.loadingLaneId = it.ifEmpty { null } } }
                    }
                    Text(t("对照组"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        if (groups.isEmpty()) listOf("" to t("无分组")) else groups.map { it to it },
                        project.normalization.controlGroup,
                    ) { commit { project.normalization.controlGroup = it } }
                    Text(t("图表指标"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(
                        listOf("normalized" to t("归一化信号"), "fold" to t("倍数变化")),
                        project.chartMetric,
                    ) { commit { project.chartMetric = it } }
                    if (quantLive != null) {
                        Text("${t("总体积")}: ${showValue(quantLive.totalVolume, 1)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${t("对照均值")}: ${showValue(quantLive.controlMean, 3)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            GelSection(t("导出"), startOpen = true) {
                Column(Modifier.fillMaxWidth()) {
                    Text(t("PNG 分辨率"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixSelect(listOf("300" to "300", "600" to "600"), project.exportDpi.toString()) {
                        commit { project.exportDpi = if (it == "600") 600 else 300 }
                    }
                    PixButton(t("导出 CSV"), enabled = quantLive?.rows?.isNotEmpty() == true, onClick = { exportCsv() })
                    PixButton(t("标注 PNG"), enabled = hasImage, onClick = { exportAnnotatedPng() })
                    PixButton(t("图版 SVG"), enabled = hasImage, onClick = { exportFigureSvg() })
                    PixButton(t("可复现数据包"), enabled = quantLive?.rows?.isNotEmpty() == true && busy.isEmpty(), ok = true, onClick = { exportReproBundle() })
                    Row {
                        PixButton(t("保存项目"), enabled = hasImage, onClick = { saveProject() })
                        Spacer(Modifier.width(4.dp))
                        PixButton(t("打开项目"), onClick = { openProjectPicker() })
                    }
                    HintLine(t("更改会自动保存在本浏览器；持久备份请下载项目 JSON。"))
                }
            }
        }
    }

    @Composable
    fun StagePane() {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("印迹台"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("${(zoom * 100).roundToInt()}%", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                PixButton(t("缩小"), onClick = { zoom = clamp(zoom / 1.25, 0.2, 4.0); drawStage() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("放大"), onClick = { zoom = clamp(zoom * 1.25, 0.2, 4.0); drawStage() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("适应"), onClick = { zoom = 1.0; drawStage() })
            }
            Box(
                Modifier.fillMaxWidth().height(320.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    .background(Color(0xFF04060A)).padding(6.dp),
            ) {
                val proc = processed
                if (hasImage && proc != null) {
                    BoxWithConstraints(Modifier.align(Alignment.Center)) {
                        val geom = stageGeometry(maxWidth.value, maxHeight.value)
                        if (geom != null) {
                            var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
                            val procBmp = proc.canvas
                            Box(
                                Modifier
                                    .size(geom.cssW.dp, geom.cssH.dp)
                                    .onSizeChanged { canvasSizePx = it },
                            ) {
                                // display canvas
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val bmp = procBmp.asImageBitmap()
                                    uiRev // redraw key
                                    drawImage(
                                        image = bmp,
                                        srcOffset = IntOffset(geom.sx, geom.sy),
                                        srcSize = IntSize(geom.sw, geom.sh),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                                    )
                                    // overlay (main drawOverlay)
                                    val w = size.width.toDouble()
                                    val h = size.height.toDouble()
                                    fun mapX(nx: Double): Double = ((nx * proc.width - geom.sx) / geom.sw) * w
                                    fun mapY(ny: Double): Double = ((ny * proc.height - geom.sy) / geom.sh) * h
                                    val visibleLeft = project.crop.left
                                    val visibleRight = 1 - project.crop.right
                                    for (lane in project.lanes) {
                                        if (lane.x < visibleLeft || lane.x > visibleRight) continue
                                        val left = mapX(lane.x - lane.width / 2).toFloat()
                                        val right = mapX(lane.x + lane.width / 2).toFloat()
                                        val selected = selectedLaneId == lane.id
                                        val laneColor = when {
                                            lane.excluded -> Color(0xE6FF5D5D)
                                            selected -> Color(0xFFFF9F1C)
                                            else -> Color(0xCC4CD6FF)
                                        }
                                        val dash = if (lane.excluded) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 3f)) else null
                                        drawLine(laneColor, Offset(left, 0f), Offset(left, size.height), strokeWidth = if (selected) 2f else 1f, pathEffect = dash)
                                        drawLine(laneColor, Offset(right, 0f), Offset(right, size.height), strokeWidth = if (selected) 2f else 1f, pathEffect = dash)
                                        if (project.showRoi) {
                                            for (band in lane.bands) {
                                                val x0 = mapX(lane.x - band.width / 2).toFloat()
                                                val y0 = mapY(band.y - band.height / 2).toFloat()
                                                val x1 = mapX(lane.x + band.width / 2).toFloat()
                                                val y1 = mapY(band.y + band.height / 2).toFloat()
                                                val active = selectedBandId == band.id
                                                val excluded = lane.excluded || band.excluded
                                                val roiColor = when {
                                                    excluded -> Color(0xF2FF5D5D)
                                                    active -> Color(0xFFFFD23F)
                                                    else -> Color(0xF253D769)
                                                }
                                                val roiDash = if (excluded) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null
                                                drawRect(
                                                    roiColor,
                                                    topLeft = Offset(x0, y0),
                                                    size = androidx.compose.ui.geometry.Size(max(1f, x1 - x0), max(1f, y1 - y0)),
                                                    style = Stroke(width = if (active) 2f else 1f, pathEffect = roiDash),
                                                )
                                            }
                                        }
                                        if (project.showLaneLabels) {
                                            val label = lane.name.ifEmpty { "—" }
                                            val nc = drawContext.canvas.nativeCanvas
                                            val bg = android.graphics.Paint().apply { color = 0xB304060A.toInt() }
                                            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                color = if (lane.excluded) 0xFFFF5D5D.toInt() else 0xFFD8E1F2.toInt()
                                                typeface = android.graphics.Typeface.MONOSPACE
                                                textSize = 10f
                                            }
                                            val tw = tp.measureText(label)
                                            nc.drawRect(left + 2, 2f, left + 2 + tw + 6, 16f, bg)
                                            nc.drawText(label, left + 5, 13f, tp)
                                        }
                                    }
                                    if (dragCropActive) {
                                        val cd = lastCropDrag.value
                                        if (cd != null) {
                                            val a = Offset(mapX(min(cd.startNx, cd.nx)).toFloat(), mapY(min(cd.startNy, cd.ny)).toFloat())
                                            val b = Offset(mapX(max(cd.startNx, cd.nx)).toFloat(), mapY(max(cd.startNy, cd.ny)).toFloat())
                                            drawRect(
                                                Color(0xFFFF9F1C),
                                                topLeft = a,
                                                size = androidx.compose.ui.geometry.Size(max(1f, b.x - a.x), max(1f, b.y - a.y)),
                                                style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                                            )
                                        }
                                    }
                                }
                                // gesture overlay
                                androidx.compose.foundation.Canvas(
                                    Modifier.fillMaxSize().pointerInput(mode, uiRev) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val proc2 = processed ?: return@awaitEachGesture
                                            val cw = canvasSizePx.width.toDouble().coerceAtLeast(1.0)
                                            val ch = canvasSizePx.height.toDouble().coerceAtLeast(1.0)
                                            fun nx(px0: Double): Double = (((px0 / cw) * geom.sw + geom.sx) / proc2.width)
                                            fun ny(py0: Double): Double = (((py0 / ch) * geom.sh + geom.sy) / proc2.height)
                                            val px = down.position.x.toDouble()
                                            val py = down.position.y.toDouble()
                                            val dnx = nx(px)
                                            val dny = ny(py)
                                            if (mode == "crop") {
                                                checkpoint(true)
                                                dragState.value = DragState(
                                                    "crop", startX = dnx, startY = dny,
                                                    crop = BlotCrop(project.crop.left, project.crop.top, project.crop.right, project.crop.bottom),
                                                )
                                                lastCropDrag.value = CropDrag(dnx, dny, dnx, dny)
                                                dragCropActive = true
                                                drawStage()
                                                down.consume()
                                            } else if (mode == "addBand") {
                                                addBandAt(dnx, dny)
                                                down.consume()
                                            } else {
                                                // band hit
                                                var hitBand: Triple<BlotLane, BlotBand, String>? = null
                                                for (lane in project.lanes.reversed()) {
                                                    for (band in lane.bands) {
                                                        val x0 = ((lane.x - band.width / 2) * proc2.width - geom.sx) / geom.sw * cw
                                                        val x1 = ((lane.x + band.width / 2) * proc2.width - geom.sx) / geom.sw * cw
                                                        val y0 = ((band.y - band.height / 2) * proc2.height - geom.sy) / geom.sh * ch
                                                        val y1 = ((band.y + band.height / 2) * proc2.height - geom.sy) / geom.sh * ch
                                                        val e = 5.0
                                                        if (px < x0 - e || px > x1 + e || py < y0 - e || py > y1 + e) continue
                                                        val kind = when {
                                                            abs(py - y0) <= e -> "band-top"
                                                            abs(py - y1) <= e -> "band-bottom"
                                                            abs(px - x0) <= e -> "band-left"
                                                            abs(px - x1) <= e -> "band-right"
                                                            else -> "band-move"
                                                        }
                                                        hitBand = Triple(lane, band, kind)
                                                        break
                                                    }
                                                    if (hitBand != null) break
                                                }
                                                val hit = hitBand
                                                if (hit != null) {
                                                    checkpoint(true)
                                                    selectedLaneId = hit.first.id
                                                    selectedBandId = hit.second.id
                                                    dragState.value = DragState(
                                                        kind = hit.third, laneId = hit.first.id, bandId = hit.second.id,
                                                        startX = dnx, startY = dny,
                                                        origY = hit.second.y, origWidth = hit.second.width, origHeight = hit.second.height,
                                                    )
                                                    drawStage()
                                                    down.consume()
                                                } else {
                                                    // lane hit
                                                    var hitLane: Pair<BlotLane, String>? = null
                                                    for (lane in project.lanes.reversed()) {
                                                        val left = ((lane.x - lane.width / 2) * proc2.width - geom.sx) / geom.sw * cw
                                                        val right = ((lane.x + lane.width / 2) * proc2.width - geom.sx) / geom.sw * cw
                                                        val centre = ((lane.x) * proc2.width - geom.sx) / geom.sw * cw
                                                        val e = 6.0
                                                        if (abs(px - left) <= e) { hitLane = lane to "lane-left"; break }
                                                        if (abs(px - right) <= e) { hitLane = lane to "lane-right"; break }
                                                        if (abs(px - centre) <= max(e, (right - left) / 2)) { hitLane = lane to "lane-move"; break }
                                                    }
                                                    val lh = hitLane
                                                    if (lh != null) {
                                                        checkpoint(true)
                                                        selectedLaneId = lh.first.id
                                                        selectedBandId = null
                                                        dragState.value = DragState(
                                                            kind = lh.second, laneId = lh.first.id,
                                                            startX = dnx, startY = dny,
                                                            origX = lh.first.x, origWidth = lh.first.width,
                                                        )
                                                        drawStage()
                                                        down.consume()
                                                    } else {
                                                        selectedLaneId = null
                                                        selectedBandId = null
                                                        drawStage()
                                                    }
                                                }
                                            }
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                if (change.changedToUp()) {
                                                    val d = dragState.value
                                                    val wasCrop = d?.kind == "crop"
                                                    val cropStart = d?.crop
                                                    val cd = lastCropDrag.value
                                                    dragState.value = null
                                                    dragCropActive = false
                                                    if (wasCrop && cd != null && cropStart != null) {
                                                        val visW = 1 - cropStart.left - cropStart.right
                                                        val visH = 1 - cropStart.top - cropStart.bottom
                                                        val x0 = cropStart.left + min(cd.startNx, cd.nx) * visW
                                                        val x1 = cropStart.left + max(cd.startNx, cd.nx) * visW
                                                        val y0 = cropStart.top + min(cd.startNy, cd.ny) * visH
                                                        val y1 = cropStart.top + max(cd.startNy, cd.ny) * visH
                                                        if (x1 - x0 > 0.02 && y1 - y0 > 0.02) {
                                                            project.crop.left = clamp(x0, 0.0, 0.95)
                                                            project.crop.top = clamp(y0, 0.0, 0.95)
                                                            project.crop.right = clamp(1 - x1, 0.0, 0.95)
                                                            project.crop.bottom = clamp(1 - y1, 0.0, 0.95)
                                                            mode = "select"
                                                        }
                                                    }
                                                    lastCropDrag.value = null
                                                    schedulePersist()
                                                    drawStage()
                                                    change.consume()
                                                    break
                                                }
                                                val d = dragState.value
                                                if (d != null) {
                                                    val mnx = nx(change.position.x.toDouble())
                                                    val mny = ny(change.position.y.toDouble())
                                                    if (d.kind == "crop") {
                                                        lastCropDrag.value = CropDrag(d.startX, d.startY, mnx, mny)
                                                        drawStage()
                                                    } else {
                                                        val lane = d.laneId?.let { id -> project.lanes.find { it.id == id } }
                                                        if (lane != null) {
                                                            when (d.kind) {
                                                                "lane-move" -> lane.x = clamp(mnx, 0.01, 0.99)
                                                                "lane-left" -> {
                                                                    val right = d.origX + d.origWidth / 2
                                                                    val left = clamp(mnx, 0.001, right - 0.005)
                                                                    lane.width = right - left
                                                                    lane.x = (left + right) / 2
                                                                }
                                                                "lane-right" -> {
                                                                    val left = d.origX - d.origWidth / 2
                                                                    val right = clamp(mnx, left + 0.005, 0.999)
                                                                    lane.width = right - left
                                                                    lane.x = (left + right) / 2
                                                                }
                                                                else -> {
                                                                    val band = lane.bands.find { it.id == d.bandId }
                                                                    if (band != null) {
                                                                        when (d.kind) {
                                                                            "band-move" -> band.y = clamp(mny, 0.002, 0.998)
                                                                            "band-top" -> {
                                                                                val bottom = d.origY + d.origHeight / 2
                                                                                val top = clamp(mny, 0.0, bottom - 0.004)
                                                                                band.height = bottom - top
                                                                                band.y = (top + bottom) / 2
                                                                            }
                                                                            "band-bottom" -> {
                                                                                val top = d.origY - d.origHeight / 2
                                                                                val bottom = clamp(mny, top + 0.004, 1.0)
                                                                                band.height = bottom - top
                                                                                band.y = (top + bottom) / 2
                                                                            }
                                                                            "band-left", "band-right" -> band.width = clamp(abs(mnx - lane.x) * 2, 0.004, 0.8)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            drawStage()
                                                        }
                                                    }
                                                    change.consume()
                                                }
                                            }
                                        }
                                    },
                                ) {}
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(t("尚无印迹图像 - 导入 PNG、JPEG、WebP 或 TIFF 扫描图开始"), fontSize = 11.sp, color = Color(0xFF68748A))
                        PixButton(t("导入印迹图像"), accent = true, onClick = { openImagePicker() })
                    }
                }
            }
        }
    }

    @Composable
    fun RightPane() {
        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) {
            Row(Modifier.fillMaxWidth()) {
                TabBtn(t("结果"), rightTab == "results", Modifier.weight(1f)) { rightTab = "results" }
                TabBtn(t("图表"), rightTab == "chart", Modifier.weight(1f)) { rightTab = "chart" }
                TabBtn(t("图版"), rightTab == "figure", Modifier.weight(1f)) { rightTab = "figure" }
            }
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (rightTab == "results") {
                    Text(t("样本分配"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    if (project.lanes.isEmpty()) {
                        Text(t("尚未定义泳道"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp))) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Th(t("泳道")); Th(t("样本")); Th(t("分组")); Th(t("内参")); Th(t("纳入"))
                            }
                            for (lane in orderedLanes) {
                                Row(Modifier.fillMaxWidth().alpha(if (lane.excluded) 0.5f else 1f)) {
                                    TdInput(lane.name) { lane.name = it; schedulePersist(); drawStage() }
                                    TdInput(lane.sample, placeholder = lane.name) { lane.sample = it; schedulePersist() }
                                    TdInput(lane.group) { lane.group = it; syncNormalization(); schedulePersist() }
                                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                                        RadioButton(
                                            selected = project.normalization.loadingLaneId == lane.id,
                                            onClick = {
                                                commit {
                                                    project.normalization.mode = "loading"
                                                    project.normalization.loadingLaneId = lane.id
                                                }
                                            },
                                        )
                                    }
                                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                                        Checkbox(checked = !lane.excluded, onCheckedChange = { toggleLaneExcluded(lane) })
                                    }
                                }
                            }
                        }
                    }
                    Text(t("结果"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    val quant = quantLive
                    if (quant == null || quant.rows.isEmpty()) {
                        Text(t("尚无定量条带"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp))) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Th(t("泳道")); Th(t("条带")); Th(t("分组")); Th(t("纳入")); Th(t("面积 px")); Th(t("均值")); Th(t("背景")); Th(t("净体积")); Th(t("归一化")); Th(t("倍数")); Th(t("组均值")); Th(t("标准差")); Th(t("标准误")); Th("CI95 ±"); Th("p（对照）"); Th("n"); Th(t("饱和")); Th("")
                            }
                            for (row in quant.rows) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (row.bandId == selectedBandId) Color(0x14FFD23F) else Color.Transparent)
                                        .clickable { selectedLaneId = row.laneId; selectedBandId = row.bandId; drawStage() },
                                ) {
                                    Td(row.laneName.ifEmpty { "—" }, dim = !row.included)
                                    Td("${row.bandIndex + 1}", dim = !row.included)
                                    Td(row.group.ifEmpty { "—" }, dim = !row.included)
                                    Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                                        Checkbox(
                                            checked = project.lanes.find { it.id == row.laneId }?.bands?.find { it.id == row.bandId }?.excluded != true,
                                            onCheckedChange = {
                                                val lane = project.lanes.find { it.id == row.laneId }
                                                val band = lane?.bands?.find { it.id == row.bandId }
                                                if (band != null) toggleBandExcluded(band)
                                            },
                                        )
                                    }
                                    Td("${row.area.toInt()}", dim = !row.included)
                                    Td(showValue(row.meanSignal), dim = !row.included)
                                    Td(showValue(row.background), dim = !row.included)
                                    Td(showValue(row.netVolume, 1), dim = !row.included)
                                    Td(showValue(row.normalized), dim = !row.included)
                                    Td(showValue(row.fold), dim = !row.included)
                                    Td(showValue(row.groupMean), dim = !row.included)
                                    Td(showValue(row.groupSd), dim = !row.included)
                                    Td(showValue(row.groupSem), dim = !row.included)
                                    Td(if (row.groupN >= 2) showValue(row.groupCi95) else "—", dim = !row.included)
                                    Td(showP(row.groupP), dim = !row.included)
                                    Td("${row.groupN}", dim = !row.included)
                                    Td(if (row.saturated) t("饱和") else "", dim = !row.included)
                                    Text(
                                        t("删除条带"),
                                        fontSize = 8.sp, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clickable { removeBand(row.laneId, row.bandId) }
                                            .padding(horizontal = 5.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                } else if (rightTab == "chart") {
                    Text(t("图表"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp)).background(Color.White).padding(4.dp)) {
                        val cb = chartBmp
                        if (cb != null) {
                            Image(bitmap = cb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(t("尚无定量条带"), fontSize = 9.sp, color = Color(0xFF68748A), modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    val quant = quantLive
                    if (quant != null && quant.groups.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp))) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Th(t("分组")); Th("n"); Th(t("组均值")); Th(t("标准差")); Th(t("标准误")); Th("95% 置信区间"); Th("p（对照）")
                            }
                            for (group in quant.groups) {
                                Row(Modifier.fillMaxWidth()) {
                                    Td(group.name); Td("${group.n}"); Td(showValue(group.mean)); Td(showValue(group.sd)); Td(showValue(group.sem))
                                    Td(if (group.n >= 2) showValue(group.ci95) else "—"); Td(showP(group.pValue))
                                }
                            }
                        }
                        HintLine(t("分组汇总报告 95% 置信区间（Student t）与相对对照组的双侧 Welch t 检验 p 值。"))
                    }
                } else {
                    Text(t("图版"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(t("图版标题"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixTextField(project.title, "") { project.title = it; schedulePersist(); drawStage() }
                    Text(t("图表标题"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixTextField(project.chartTitle, "") { project.chartTitle = it; schedulePersist(); drawStage() }
                    Box(Modifier.fillMaxWidth().height(240.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp)).background(Color.White).padding(4.dp)) {
                        val fb = figureBmp
                        if (fb != null) {
                            Image(bitmap = fb.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(t("加载印迹图像后显示图版预览"), fontSize = 9.sp, color = Color(0xFF68748A), modifier = Modifier.align(Alignment.TopCenter))
                        }
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        PixButton(t("图版 SVG"), enabled = figureBmp != null, onClick = { exportFigureSvg() })
                        Spacer(Modifier.width(4.dp))
                        PixButton(t("标注 PNG"), enabled = hasImage, onClick = { exportAnnotatedPng() })
                    }
                }
            }
        }
    }

    @Composable
    fun Toolbar() {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                PixButton(t("导入印迹图像"), onClick = { openImagePicker() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("自动检测"), enabled = hasImage && busy.isEmpty(), accent = true, onClick = { scope.launch { autoDetect(true) } })
                Spacer(Modifier.width(4.dp))
                PixButton(t("AI 识别 (GelGenie)"), enabled = hasImage && busy.isEmpty(), accent = true, onClick = { scope.launch { aiDetect() } })
                Spacer(Modifier.width(4.dp))
                PixButton(t("批量图像"), enabled = !batchRunning && busy.isEmpty(), onClick = { openBatchPicker() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("撤销"), enabled = canUndo, onClick = { undo() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("重做"), enabled = canRedo, onClick = { redo() })
                Spacer(Modifier.width(8.dp))
                PixButton(t("打开项目"), onClick = { openProjectPicker() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("保存项目"), enabled = hasImage, onClick = { saveProject() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("导出图版"), enabled = hasImage, ok = true, onClick = { exportFigureSvg() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("发送到 Figure Studio"), enabled = hasImage && busy.isEmpty(), onClick = { sendToFigureNow() })
                Spacer(Modifier.width(4.dp))
                PixButton(t("重置"), enabled = hasImage, danger = true, onClick = { showResetConfirm = true })
            }
            if (busy.isNotEmpty()) {
                Text(busy, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 2.dp))
            }
            if (batchRunning) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        interp(
                            "正在处理 {done}/{total}：{name}",
                            "done" to batchDone, "total" to batchTotal,
                            "name" to batchName.ifEmpty { t("批量处理") },
                        ),
                        fontSize = 9.sp, color = Color(0xFF4CD6FF),
                    )
                    LinearProgressIndicator(
                        progress = { if (batchTotal > 0) batchDone.toFloat() / batchTotal else 0f },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                }
            } else if (batchOk + batchFailed > 0) {
                Text(
                    interp("批量完成：成功 {ok}，失败 {failed}", "ok" to batchOk, "failed" to batchFailed),
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > 1100.dp
        if (wide) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Toolbar()
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.width(316.dp).verticalScroll(rememberScrollState())) { SidePane() }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { StagePane() }
                    Column(Modifier.width(440.dp).verticalScroll(rememberScrollState())) { RightPane() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                Toolbar()
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    SidePane()
                    StagePane()
                    RightPane()
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(t("重置")) },
            text = { Text(t("重置印迹项目并清除本地自动保存？")) },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; resetAll() }) { Text(t("重置")) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(t("取消")) }
            },
        )
    }
}

private class StageGeom(val cssW: Float, val cssH: Float, val sx: Int, val sy: Int, val sw: Int, val sh: Int)

@Composable
private fun HintLine(text: String) {
    Text(
        text,
        fontSize = 8.sp,
        color = Color(0xFF4CD6FF),
        modifier = Modifier
            .border(1.dp, Color(0xFF4CD6FF), RoundedCornerShape(0.dp))
            .padding(start = 5.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun TabBtn(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 9.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun Th(text: String) {
    Text(
        text,
        fontSize = 9.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

@Composable
private fun Td(text: String, dim: Boolean = false) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        maxLines = 1,
    )
}

@Composable
private fun TdInput(value: String, placeholder: String = "", onChange: (String) -> Unit) {
    Box(Modifier.width(110.dp)) {
        PixTextField(value, placeholder, onChange)
    }
}
