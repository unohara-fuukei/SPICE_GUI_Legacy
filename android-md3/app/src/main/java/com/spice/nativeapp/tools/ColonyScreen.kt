package com.spice.nativeapp.tools

/*
 * 菌落计数器主界面，1:1 移植 main 的 src/lib/tools/colony/ColonyTool.svelte
 * （行为段 1-1290 / 模板段 1290-1900；CSS 1900-2147 的三栏尺寸、配色
 * 以 Material 化近似实现，几何与文案逐条对应）。
 *
 * 平台差异（README §13 披露）：
 * - HTMLCanvas 双层（view+overlay）→ 单个 Compose Canvas 等价绘制；
 * - localStorage → SharedPreferences("spice_tools")，键 spice_colony_project_v1；
 * - File 拖放/文件输入 → 系统 picker（OpenDocument/OpenMultipleDocuments）；
 * - 批量 Web Worker → 协程 Dispatchers.Default（40 张上限同原版）；
 * - canvasPreview 项目缩略图、ToolProjectBar、项目 handoff/capture 不移植
 *   （同 T3/T4/T5 口径）；
 * - 键盘 Delete/Esc 捷径不适用触屏；鼠标右键等同屏蔽；
 * - sendToFigure 无跨工具实时桥，PNG 落盘 files/figures/；
 * - 导出落盘 filesDir/colony-exports/，Toast 反馈。
 */
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Repro
import com.spice.nativeapp.tools.colony.CLASS_BLUE
import com.spice.nativeapp.tools.colony.CLASS_UNCLASSIFIED
import com.spice.nativeapp.tools.colony.CLASS_WHITE
import com.spice.nativeapp.tools.colony.COLOR_MODE_BLUE_WHITE
import com.spice.nativeapp.tools.colony.ColonyAdjust
import com.spice.nativeapp.tools.colony.ColonyBatchFile
import com.spice.nativeapp.tools.colony.ColonyBatchInput
import com.spice.nativeapp.tools.colony.ColonyCrop
import com.spice.nativeapp.tools.colony.ColonyMarker
import com.spice.nativeapp.tools.colony.ColonyProject
import com.spice.nativeapp.tools.colony.ColonyStats
import com.spice.nativeapp.tools.colony.MAX_BATCH_PLATES
import com.spice.nativeapp.tools.colony.MAX_DETECT_DIM
import com.spice.nativeapp.tools.colony.MAX_WORK_DIM
import com.spice.nativeapp.tools.colony.POLARITY_AUTO
import com.spice.nativeapp.tools.colony.POLARITY_BRIGHT
import com.spice.nativeapp.tools.colony.POLARITY_DARK
import com.spice.nativeapp.tools.colony.SOURCE_AUTO
import com.spice.nativeapp.tools.colony.SOURCE_MANUAL
import com.spice.nativeapp.tools.colony.SegmentRequest
import com.spice.nativeapp.tools.colony.StatisticsRow
import com.spice.nativeapp.tools.colony.WorkMeta
import com.spice.nativeapp.tools.colony.annotatedPngBytes
import com.spice.nativeapp.tools.colony.bitmapPixelSource
import com.spice.nativeapp.tools.colony.buildHistogram
import com.spice.nativeapp.tools.colony.buildWorkBitmap
import com.spice.nativeapp.tools.colony.cfuCi95
import com.spice.nativeapp.tools.colony.classifyMarkers
import com.spice.nativeapp.tools.colony.clearClassification
import com.spice.nativeapp.tools.colony.clearSnapshot
import com.spice.nativeapp.tools.colony.colonyProjectName
import com.spice.nativeapp.tools.colony.compactProjectImage
import com.spice.nativeapp.tools.colony.computeCfu
import com.spice.nativeapp.tools.colony.computeStats
import com.spice.nativeapp.tools.colony.countClasses
import com.spice.nativeapp.tools.colony.decodeImageFile
import com.spice.nativeapp.tools.colony.defaultAdjust
import com.spice.nativeapp.tools.colony.defaultCrop
import com.spice.nativeapp.tools.colony.defaultParams
import com.spice.nativeapp.tools.colony.detectImageColor
import com.spice.nativeapp.tools.colony.downscaleCanvas
import com.spice.nativeapp.tools.colony.extractGray
import com.spice.nativeapp.tools.colony.formatArea
import com.spice.nativeapp.tools.colony.formatScientific
import com.spice.nativeapp.tools.colony.loadImageBitmap
import com.spice.nativeapp.tools.colony.loadSnapshot
import com.spice.nativeapp.tools.colony.maskPngBytes
import com.spice.nativeapp.tools.colony.maskPreviewBitmap
import com.spice.nativeapp.tools.colony.markersToCsv
import com.spice.nativeapp.tools.colony.median
import com.spice.nativeapp.tools.colony.nextMarkerId
import com.spice.nativeapp.tools.colony.nowIso
import com.spice.nativeapp.tools.colony.numberMarkers
import com.spice.nativeapp.tools.colony.parseProjectJson
import com.spice.nativeapp.tools.colony.poissonCi95
import com.spice.nativeapp.tools.colony.projectBaseName
import com.spice.nativeapp.tools.colony.projectJson
import com.spice.nativeapp.tools.colony.renderAdjusted
import com.spice.nativeapp.tools.colony.renderAnnotated
import com.spice.nativeapp.tools.colony.runColonyBatch
import com.spice.nativeapp.tools.colony.saveSnapshot
import com.spice.nativeapp.tools.colony.segmentGray
import com.spice.nativeapp.tools.colony.statisticsToCsv
import com.spice.nativeapp.tools.colony.transformMarkersBetween
import com.spice.nativeapp.tools.colony.toJson
import com.spice.nativeapp.tools.gel.GelSection
import com.spice.nativeapp.tools.gel.PixButton
import com.spice.nativeapp.tools.gel.PixCheck
import com.spice.nativeapp.tools.gel.PixSelect
import com.spice.nativeapp.tools.gel.gt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// -------------------------------------------------------------------- palette

private val C_INC = Color(0xFF53D769)
private val C_EXC = Color(0xFFFF5D5D)
private val C_MANUAL = Color(0xFF4CD6FF)
private val C_BLUE = Color(0xFF5AA9FF)
private val C_WHITE = Color(0xFFF2F2F2)
private val C_SEL = Color(0xFFFFD23F)
private val C_MASK = Color(0xFF4CD6FF)
private val C_DIM = Color(0xFF9AA5B1)

// -------------------------------------------------------------- non-reactive

/** 检测平面（runDetection 的工作副本）。 */
private class DetectPlane(
    val gray: DoubleArray,
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
)

private class MaskPlane(val mask: ByteArray, val width: Int, val height: Int)

/** 大位图与检测平面不进响应式代理（对应 Svelte 的普通 let）。 */
private class ColonyPipeline {
    var sourceImage: Bitmap? = null
    var workMeta: WorkMeta? = null
    var processed: Bitmap? = null
    var maskBitmap: Bitmap? = null
    var maskPlane: MaskPlane? = null
    var detectPlane: DetectPlane? = null
}

private class ColonyDragState(
    val id: Int,
    val startX: Double,
    val startY: Double,
    val offsetX: Double,
    val offsetY: Double,
    var moved: Boolean,
)

private fun jsFixed(value: Double, digits: Int): String {
    val symbols = DecimalFormatSymbols(Locale.US)
    return DecimalFormat("0." + "#".repeat(maxOf(0, digits)), symbols).apply {
        isGroupingUsed = false
    }.format(value)
}

// --------------------------------------------------------------------- screen

@Composable
fun ColonyScreen() {
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

    // ---------------------------------------------------------- 状态（$state）
    var imageDataUrl by remember { mutableStateOf("") }
    var imageName by remember { mutableStateOf("") }
    var sourceWidth by remember { mutableStateOf(0) }
    var sourceHeight by remember { mutableStateOf(0) }
    var rotate by remember { mutableStateOf(0) }
    var crop by remember { mutableStateOf(defaultCrop()) }
    var adjust by remember { mutableStateOf(defaultAdjust()) }
    var params by remember { mutableStateOf(defaultParams()) }
    var markers by remember { mutableStateOf<List<ColonyMarker>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var showMask by remember { mutableStateOf(true) }
    var showNumbers by remember { mutableStateOf(true) }
    var dpi by remember { mutableStateOf(300) }
    var dilutionText by remember { mutableStateOf("1000000") }
    var platedVolumeText by remember { mutableStateOf("0.1") }
    var busy by remember { mutableStateOf("") }
    var autoDetect by remember { mutableStateOf(true) }
    var otsuValue by remember { mutableStateOf(0) }
    var detectedThreshold by remember { mutableStateOf(0) }
    var resolvedPolarity by remember { mutableStateOf("dark") }
    var workInfo by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) }
    var hasMask by remember { mutableStateOf(false) }
    var hydrated by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("") }
    var reproBusy by remember { mutableStateOf(false) }
    var imageHasColor by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf("") }
    var batchRunning by remember { mutableStateOf(false) }
    var batchDone by remember { mutableStateOf(0) }
    var batchTotal by remember { mutableStateOf(0) }
    var batchCurrent by remember { mutableStateOf("") }

    // 非响应式位图管道 + 重绘版本号
    val pipeline = remember { ColonyPipeline() }
    var renderVersion by remember { mutableStateOf(0) }

    // debounce 信号
    var adjustSig by remember { mutableStateOf(0) }
    var cropSig by remember { mutableStateOf(0) }
    var detectSig by remember { mutableStateOf(0) }

    // token 失效器
    val imageToken = remember { intArrayOf(0) }
    val detectToken = remember { intArrayOf(0) }
    var autosaveWarned by remember { mutableStateOf(false) }

    // 舞台尺寸（fitDisplay 用）
    var stageSize by remember { mutableStateOf(IntSize.Zero) }

    // ------------------------------------------------------------- 派生值
    val stats = remember(markers) { computeStats(markers) }
    val classCounts = remember(markers) { countClasses(markers) }
    val numbering = remember(markers) { numberMarkers(markers) }
    val histogram = remember(markers) { buildHistogram(markers.filter { it.included }.map { it.area }) }
    val selected = remember(markers, selectedId) { markers.firstOrNull { it.id == selectedId } }
    val dilutionFactorNum = dilutionText.toDoubleOrNull()
    val platedVolumeNum = platedVolumeText.toDoubleOrNull()
    val cfu = remember(stats, dilutionFactorNum, platedVolumeNum) {
        computeCfu(stats.included, dilutionFactorNum ?: Double.NaN, platedVolumeNum ?: Double.NaN)
    }
    val countCi = remember(stats) { poissonCi95(stats.included) }
    val cfuInterval = remember(stats, dilutionFactorNum, platedVolumeNum) {
        cfuCi95(stats.included, dilutionFactorNum ?: Double.NaN, platedVolumeNum ?: Double.NaN)
    }
    val colorModeOn = params.colorMode == COLOR_MODE_BLUE_WHITE
    val hasImage = imageDataUrl.isNotEmpty() && workInfo != null

    fun baseName(): String = projectBaseName(imageName)

    fun snapshot(): ColonyProject = ColonyProject(
        version = 1,
        savedAt = nowIso(),
        imageDataUrl = imageDataUrl,
        imageName = imageName,
        imageWidth = sourceWidth,
        imageHeight = sourceHeight,
        workWidth = workInfo?.first ?: 0,
        workHeight = workInfo?.second ?: 0,
        rotate = rotate,
        crop = ColonyCrop(crop.left, crop.top, crop.right, crop.bottom),
        adjust = ColonyAdjust(adjust.grayscale, adjust.invert, adjust.brightness, adjust.contrast),
        params = params,
        markers = markers,
        dpi = dpi,
        dilutionFactor = dilutionFactorNum ?: 0.0,
        platedVolume = platedVolumeNum ?: 0.0,
    )

    fun statisticsRow(label: String): StatisticsRow = StatisticsRow(
        label = label,
        count = stats.included,
        countLow = countCi.low,
        countHigh = countCi.high,
        blue = classCounts.blue,
        white = classCounts.white,
        unclassified = classCounts.unclassified,
        cfu = cfu,
        cfuLow = cfuInterval?.low,
        cfuHigh = cfuInterval?.high,
    )

    // ----------------------------------------------------------- 核心流水线

    fun cancelDetection() {
        detectToken[0]++
        detectSig++ // 失效在途的 debounce LaunchedEffect
        if (busy == "detect") busy = ""
    }

    /** 原版 renderNow：重建调整位图，作废旧掩膜，可再排自动检测。 */
    fun renderNow(runAutoDetect: Boolean) {
        val workBitmap = pipeline.workMeta?.bitmap ?: return
        scope.launch(Dispatchers.Default) {
            pipeline.processed = renderAdjusted(workBitmap, adjust)
            pipeline.maskBitmap = null
            pipeline.maskPlane = null
            pipeline.detectPlane = null
            withContext(Dispatchers.Main) {
                hasMask = false
                otsuValue = 0
                renderVersion++
                if (runAutoDetect) detectSig++
            }
        }
    }

    /** 原版 rebuildWork：旋转/裁剪后重建工作画布（可携带标记）。 */
    fun rebuildWork(carryMarkers: Boolean, runAutoDetect: Boolean) {
        val source = pipeline.sourceImage ?: return
        cancelDetection()
        val oldMeta = pipeline.workMeta
        scope.launch(Dispatchers.Default) {
            val next = buildWorkBitmap(source, rotate, crop)
            withContext(Dispatchers.Main) {
                if (carryMarkers && oldMeta != null) {
                    markers = transformMarkersBetween(oldMeta, next, markers)
                    if (selectedId != null && markers.none { it.id == selectedId }) selectedId = null
                }
                pipeline.workMeta = next
                workInfo = Triple(next.width, next.height, next.scaleX < 0.999 || next.scaleY < 0.999)
                pipeline.maskBitmap = null
                pipeline.maskPlane = null
                pipeline.detectPlane = null
                hasMask = false
                otsuValue = 0
                renderNow(runAutoDetect)
            }
        }
    }

    /** 原版 runDetection：降采样分割 + included 继承 + 分类。 */
    fun runDetection(notify: Boolean) {
        val sourceBitmap = pipeline.processed
        val meta = pipeline.workMeta
        if (sourceBitmap == null || meta == null) return
        if (busy == "detect") return
        detectToken[0]++
        val token = detectToken[0]
        busy = "detect"
        scope.launch(Dispatchers.Default) {
            try {
                val scaled = downscaleCanvas(sourceBitmap, MAX_DETECT_DIM)
                val plane = extractGray(scaled.bitmap)
                val fx = plane.width.toDouble() / max(1, meta.width)
                val fy = plane.height.toDouble() / max(1, meta.height)
                val areaScale = fx * fy
                val request = SegmentRequest(
                    threshold = if (params.autoThreshold) null
                    else min(255, max(0, params.manualThreshold)),
                    polarity = params.polarity,
                    blurRadius = params.blurRadius * min(fx, fy),
                    minArea = max(0.0, params.minArea) * areaScale,
                    maxArea = max(0.0, params.maxArea) * areaScale,
                    minCircularity = params.minCircularity,
                    splitTouching = params.splitTouching,
                )
                val result = segmentGray(plane.gray, plane.width, plane.height, request)
                if (token != detectToken[0]) return@launch
                val previousAuto = markers.filter { it.source == SOURCE_AUTO }
                var mapped: List<ColonyMarker> = result.colonies.map { colony ->
                    val area = colony.area / areaScale
                    val x = colony.x / fx
                    val y = colony.y / fy
                    val radius = sqrt(max(0.0, area) / Math.PI)
                    var included = true
                    var bestDistance = Double.POSITIVE_INFINITY
                    var best: ColonyMarker? = null
                    for (old in previousAuto) {
                        val distance = hypot(old.x - x, old.y - y)
                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = old
                        }
                    }
                    if (best != null && bestDistance <= max(4.0, best.radius * 0.7)) included = best.included
                    val keepManualClass = best?.classManual == true
                    ColonyMarker(
                        id = nextMarkerId(markers),
                        x = x, y = y, radius = radius, area = area,
                        meanIntensity = colony.meanIntensity,
                        circularity = colony.circularity,
                        included = included,
                        source = SOURCE_AUTO,
                        meanR = if (keepManualClass) best!!.meanR else 0.0,
                        meanG = if (keepManualClass) best!!.meanG else 0.0,
                        meanB = if (keepManualClass) best!!.meanB else 0.0,
                        hue = if (keepManualClass) best!!.hue else -1.0,
                        saturation = if (keepManualClass) best!!.saturation else 0.0,
                        colonyClass = if (keepManualClass) best!!.colonyClass else CLASS_UNCLASSIFIED,
                        classManual = keepManualClass,
                    )
                }
                if (params.colorMode == COLOR_MODE_BLUE_WHITE && pipeline.workMeta != null) {
                    val (colorResult, classified) = classifyMarkers(
                        bitmapPixelSource(pipeline.workMeta!!.bitmap), mapped,
                    )
                    imageHasColor = colorResult.imageHasColor
                    mapped = classified
                } else {
                    imageHasColor = false
                }
                val manual = markers.filter { it.source == SOURCE_MANUAL }
                val nextMarkers = mapped + manual
                val maskPreview = maskPreviewBitmap(result.mask, plane.width, plane.height)
                withContext(Dispatchers.Main) {
                    if (token != detectToken[0]) return@withContext
                    otsuValue = result.otsu
                    detectedThreshold = result.threshold
                    resolvedPolarity = result.polarity
                    pipeline.maskPlane = MaskPlane(result.mask, plane.width, plane.height)
                    pipeline.detectPlane = DetectPlane(plane.gray, plane.width, plane.height, fx, fy)
                    pipeline.maskBitmap = maskPreview
                    hasMask = true
                    markers = nextMarkers
                    if (selectedId != null && markers.none { it.id == selectedId }) selectedId = null
                    renderVersion++
                    if (notify) {
                        if (result.colonies.isNotEmpty()) {
                            toast(interp("检测到 {count} 个菌落", "count" to result.colonies.size))
                        } else {
                            toast(t("未检测到标记 - 请调整阈值或手动添加菌落"))
                        }
                    }
                }
            } catch (e: Exception) {
                if (notify && token == detectToken[0]) {
                    withContext(Dispatchers.Main) { toast(t("检测失败")) }
                }
            } finally {
                if (token == detectToken[0]) {
                    withContext(Dispatchers.Main) { if (busy == "detect") busy = "" }
                }
            }
        }
    }

    /** 原版 adoptImage：载入新图并全量重置。 */
    fun adoptImage(dataUrl: String, name: String, width: Int, height: Int) {
        cancelDetection()
        imageToken[0]++
        val token = imageToken[0]
        imageDataUrl = dataUrl
        imageName = name
        sourceWidth = width
        sourceHeight = height
        rotate = 0
        crop = defaultCrop()
        adjust = defaultAdjust()
        markers = emptyList()
        selectedId = null
        hasMask = false
        otsuValue = 0
        detectedThreshold = 0
        imageHasColor = false
        scope.launch(Dispatchers.Default) {
            val img = loadImageBitmap(dataUrl)
            if (token != imageToken[0]) return@launch
            withContext(Dispatchers.Main) {
                pipeline.sourceImage = img
                pipeline.workMeta = null
                rebuildWork(false, true)
            }
        }
    }

    /** 原版 applyProject：从项目 JSON 恢复。 */
    fun applyProject(project: ColonyProject) {
        cancelDetection()
        imageToken[0]++
        val token = imageToken[0]
        imageDataUrl = project.imageDataUrl
        imageName = project.imageName
        sourceWidth = project.imageWidth
        sourceHeight = project.imageHeight
        rotate = project.rotate
        crop = project.crop
        adjust = project.adjust
        params = project.params
        dpi = if (project.dpi == 600) 600 else 300
        dilutionText = if (project.dilutionFactor > 0) jsPlain(project.dilutionFactor) else "1000000"
        platedVolumeText = if (project.platedVolume > 0) jsPlain(project.platedVolume) else "0.1"
        markers = emptyList()
        selectedId = null
        hasMask = false
        otsuValue = 0
        detectedThreshold = 0
        workInfo = null
        pipeline.workMeta = null
        pipeline.processed = null
        scope.launch(Dispatchers.Default) {
            val img = loadImageBitmap(project.imageDataUrl)
            if (token != imageToken[0]) return@launch
            withContext(Dispatchers.Main) {
                pipeline.sourceImage = img
                if (sourceWidth == 0) sourceWidth = img.width
                if (sourceHeight == 0) sourceHeight = img.height
                rebuildWork(false, false)
            }
            // rebuildWork 在 Main 上另启协程；此处等待 workMeta 就绪再挂标记
            while (pipeline.workMeta == null && token == imageToken[0]) delay(16)
            if (token != imageToken[0]) return@launch
            withContext(Dispatchers.Main) {
                markers = project.markers
                imageHasColor = if (project.params.colorMode == COLOR_MODE_BLUE_WHITE && pipeline.workMeta != null) {
                    detectImageColor(bitmapPixelSource(pipeline.workMeta!!.bitmap))
                } else false
                renderVersion++
                if (markers.isEmpty() && autoDetect) detectSig++
            }
        }
    }

    fun resetAll() {
        cancelDetection()
        imageToken[0]++
        clearSnapshot(ctx)
        imageDataUrl = ""
        imageName = ""
        sourceWidth = 0
        sourceHeight = 0
        rotate = 0
        crop = defaultCrop()
        adjust = defaultAdjust()
        params = defaultParams()
        markers = emptyList()
        selectedId = null
        dpi = 300
        dilutionText = "1000000"
        platedVolumeText = "0.1"
        pipeline.sourceImage = null
        pipeline.workMeta = null
        pipeline.processed = null
        pipeline.maskBitmap = null
        pipeline.maskPlane = null
        pipeline.detectPlane = null
        workInfo = null
        hasMask = false
        otsuValue = 0
        saveStatus = ""
        imageHasColor = false
        batchStatus = ""
        renderVersion++
    }

    // ------------------------------------------------------------- 手动编辑

    fun sampleIntensity(x: Double, y: Double): Double {
        val plane = pipeline.detectPlane ?: return 0.0
        val cx = (x * plane.fx).roundToInt()
        val cy = (y * plane.fy).roundToInt()
        var sum = 0.0
        var count = 0
        for (dy in -1..1) {
            val py = cy + dy
            if (py < 0 || py >= plane.height) continue
            for (dx in -1..1) {
                val px = cx + dx
                if (px < 0 || px >= plane.width) continue
                sum += plane.gray[py * plane.width + px]
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    fun addManual(x: Double, y: Double) {
        val info = workInfo ?: return
        val radii = markers.filter { it.source == SOURCE_AUTO && it.included }.map { it.radius }
        val radius = if (radii.isNotEmpty()) median(radii)
        else max(6.0, min(info.first, info.second).toDouble() / 70)
        var marker = ColonyMarker(
            id = nextMarkerId(markers),
            x = x.coerceIn(0.0, (info.first - 1).toDouble()),
            y = y.coerceIn(0.0, (info.second - 1).toDouble()),
            radius = radius,
            area = Math.PI * radius * radius,
            meanIntensity = sampleIntensity(x, y),
            circularity = 1.0,
            included = true,
            source = SOURCE_MANUAL,
            meanR = 0.0, meanG = 0.0, meanB = 0.0,
            hue = -1.0, saturation = 0.0,
            colonyClass = CLASS_UNCLASSIFIED,
            classManual = false,
        )
        if (params.colorMode == COLOR_MODE_BLUE_WHITE && pipeline.workMeta != null) {
            val (colorResult, classified) = classifyMarkers(
                bitmapPixelSource(pipeline.workMeta!!.bitmap), listOf(marker),
            )
            imageHasColor = colorResult.imageHasColor
            marker = classified.first()
        }
        markers = markers + marker
        selectedId = marker.id
        renderVersion++
    }

    fun toggleSelected() {
        val marker = markers.firstOrNull { it.id == selectedId } ?: return
        markers = markers.map { if (it.id == marker.id) it.copy(included = !it.included) else it }
    }

    fun deleteSelected() {
        if (selectedId == null) return
        markers = markers.filter { it.id != selectedId }
        selectedId = null
    }

    fun clearMarkers() {
        markers = emptyList()
        selectedId = null
        toast(t("已清除所有标记"))
    }

    fun includeAll() {
        markers = markers.map { it.copy(included = true) }
    }

    fun setSelectedClass(value: String) {
        val marker = markers.firstOrNull { it.id == selectedId } ?: return
        markers = markers.map {
            if (it.id == marker.id) it.copy(colonyClass = value, classManual = true) else it
        }
    }

    // --------------------------------------------------------------- 导出

    fun saveBytes(name: String, bytes: ByteArray, message: String) {
        try {
            val dir = File(ctx.filesDir, "colony-exports").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            toast(message)
        } catch (e: Exception) {
            toast(e.message ?: t("导出失败"), long = true)
        }
    }

    fun saveText(name: String, text: String, message: String) {
        try {
            val dir = File(ctx.filesDir, "colony-exports").apply { mkdirs() }
            File(dir, name).writeText(text)
            toast(message)
        } catch (e: Exception) {
            toast(e.message ?: t("导出失败"), long = true)
        }
    }

    fun exportAnnotated() {
        val processed = pipeline.processed
        if (processed == null || !hasImage) return
        busy = "export"
        scope.launch(Dispatchers.Default) {
            try {
                val bytes = annotatedPngBytes(processed, markers, numbering, dpi, showNumbers)
                withContext(Dispatchers.Main) {
                    saveBytes("${baseName()}_annotated_${dpi}dpi.png", bytes, t("导出已保存"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(t("导出失败")) }
            } finally {
                withContext(Dispatchers.Main) { busy = "" }
            }
        }
    }

    /** 平台差异：无跨工具实时桥，PNG 落盘 files/figures/（同 T3/T4/T5）。 */
    fun sendToFigureNow() {
        val processed = pipeline.processed
        if (processed == null || !hasImage || busy != "") return
        busy = "export"
        scope.launch(Dispatchers.Default) {
            try {
                val canvas = renderAnnotated(processed, markers, numbering, showNumbers)
                val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                File(dir, "${baseName()}_annotated.png").outputStream().use {
                    canvas.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(t("无法发送到 Figure Studio"), long = true) }
            } finally {
                withContext(Dispatchers.Main) { busy = "" }
            }
        }
    }

    fun exportMask() {
        val plane = pipeline.maskPlane
        if (plane == null) return
        busy = "export"
        scope.launch(Dispatchers.Default) {
            try {
                val bytes = maskPngBytes(plane.mask, plane.width, plane.height, dpi)
                withContext(Dispatchers.Main) {
                    saveBytes("${baseName()}_mask_${dpi}dpi.png", bytes, t("导出已保存"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(t("导出失败")) }
            } finally {
                withContext(Dispatchers.Main) { busy = "" }
            }
        }
    }

    fun exportCsv() {
        if (markers.isEmpty()) return
        saveText("${baseName()}_markers.csv", markersToCsv(markers, numbering), t("导出已保存"))
    }

    fun exportStatisticsCsv() {
        if (markers.isEmpty()) return
        saveText(
            "${baseName()}_statistics.csv",
            statisticsToCsv(listOf(statisticsRow(projectBaseName(imageName)))),
            t("导出已保存"),
        )
    }

    fun exportReproBundle() {
        val processed = pipeline.processed
        val plane = pipeline.maskPlane
        if (!hasImage || processed == null || plane == null || reproBusy) return
        reproBusy = true
        scope.launch(Dispatchers.Default) {
            try {
                val snap = snapshot()
                val annotated = annotatedPngBytes(processed, markers, numbering, dpi, showNumbers)
                val mask = maskPngBytes(plane.mask, plane.width, plane.height, dpi)
                val files = listOf(
                    Repro.ReproFile("${baseName()}_annotated_${dpi}dpi.png", "image/png", annotated),
                    Repro.ReproFile("${baseName()}_mask_${dpi}dpi.png", "image/png", mask),
                    Repro.ReproFile("${baseName()}_markers.csv", "text/csv", markersToCsv(markers, numbering)),
                    Repro.ReproFile(
                        "${baseName()}_statistics.csv", "text/csv",
                        statisticsToCsv(listOf(statisticsRow(projectBaseName(imageName)))),
                    ),
                )
                val parameters = JSONObject().apply {
                    put("image", JSONObject().apply {
                        put("name", imageName)
                        put("width", sourceWidth)
                        put("height", sourceHeight)
                        put("workWidth", workInfo?.first ?: 0)
                        put("workHeight", workInfo?.second ?: 0)
                    })
                    put("rotate", rotate)
                    put("crop", JSONObject().apply {
                        put("left", crop.left); put("top", crop.top)
                        put("right", crop.right); put("bottom", crop.bottom)
                    })
                    put("adjust", JSONObject().apply {
                        put("grayscale", adjust.grayscale)
                        put("invert", adjust.invert)
                        put("brightness", adjust.brightness)
                        put("contrast", adjust.contrast)
                    })
                    put("detection", JSONObject().apply {
                        put("autoThreshold", params.autoThreshold)
                        put("manualThreshold", params.manualThreshold)
                        put("blurRadius", params.blurRadius)
                        put("minArea", params.minArea)
                        put("maxArea", params.maxArea)
                        put("minCircularity", params.minCircularity)
                        put("splitTouching", params.splitTouching)
                        put("polarity", params.polarity)
                        put("colorMode", params.colorMode)
                    })
                    put("otsu", otsuValue)
                    put("threshold", detectedThreshold)
                    put("polarity", resolvedPolarity)
                    put("dpi", dpi)
                    put("dilutionFactor", dilutionFactorNum ?: Double.NaN)
                    put("platedVolume", platedVolumeNum ?: Double.NaN)
                    put("count", stats.included)
                    put("countCi95", JSONObject().apply {
                        put("low", countCi.low); put("high", countCi.high)
                    })
                    put("cfu", cfu ?: JSONObject.NULL)
                    put("cfuCi95", cfuInterval?.let {
                        JSONObject().apply { put("low", it.low); put("high", it.high) }
                    } ?: JSONObject.NULL)
                    put("classCounts", JSONObject().apply {
                        put("blue", classCounts.blue)
                        put("white", classCounts.white)
                        put("unclassified", classCounts.unclassified)
                    })
                }
                val methods = "Colonies were segmented from the plate image with SPICE GUI using " +
                    "Gaussian pre-blurring, Otsu or manual thresholding, connected-component " +
                    "analysis and optional distance-transform watershed splitting for touching " +
                    "colonies. Area and circularity filters were applied exactly as recorded in " +
                    "parameters.json. Markers were reviewed in the editor; excluded markers " +
                    "remain in the marker CSV. Blue/white classes, when enabled, come from mean " +
                    "RGB and HSV hue measured on the original color image, with user overrides " +
                    "recorded in the class_manual column. The count and CFU/mL intervals are " +
                    "exact Poisson 95% intervals (Garwood)."
                val result = Repro.buildReproBundle(
                    ctx,
                    Repro.ReproBundleInput(
                        tool = "Colony counter",
                        title = colonyProjectName(snap, t("菌落计数器")),
                        files = files,
                        parameters = parameters,
                        methods = methods,
                        project = snap.toJson(),
                    ),
                )
                withContext(Dispatchers.Main) { toast(t("可复现数据包已保存。")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(t("可复现数据包失败。"), long = true) }
            } finally {
                withContext(Dispatchers.Main) { reproBusy = false }
            }
        }
    }

    fun saveProject() {
        if (!hasImage) return
        try {
            val dir = File(ctx.filesDir, "colony-exports").apply { mkdirs() }
            File(dir, "${baseName()}.colony.json").writeText(projectJson(snapshot()))
            toast(t("项目已保存"))
        } catch (e: Exception) {
            toast(e.message ?: t("导出失败"), long = true)
        }
    }

    // ------------------------------------------------------------ picker 输入

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

    fun readUriBytes(uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    fun isImageName(name: String): Boolean =
        Regex("\\.(png|jpe?g|webp|tiff?)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun importBytes(name: String, bytes: ByteArray, mime: String?) {
        val looksImage = mime?.startsWith("image/") == true || isImageName(name)
        if (!looksImage) {
            toast(t("无法读取图像"))
            return
        }
        busy = "image"
        scope.launch(Dispatchers.Default) {
            try {
                val decoded = decodeImageFile(bytes, name, mime)
                withContext(Dispatchers.Main) {
                    adoptImage(decoded.dataUrl, decoded.name, decoded.width, decoded.height)
                    toast(t("培养皿图像已载入"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(t("无法读取图像")) }
            } finally {
                withContext(Dispatchers.Main) { busy = "" }
            }
        }
    }

    fun importProjectText(text: String) {
        val project = parseProjectJson(text)
        if (project == null) {
            toast(t("该文件不是菌落项目"), long = true)
            return
        }
        applyProject(project)
        toast(t("项目已恢复"))
    }

    fun runBatch(files: List<ColonyBatchFile>) {
        if (files.isEmpty()) {
            toast(t("未选择孔板图片。"))
            return
        }
        if (files.size > MAX_BATCH_PLATES) {
            toast(interp("仅处理前 {max} 张图片。", "max" to MAX_BATCH_PLATES))
        }
        batchRunning = true
        batchDone = 0
        batchTotal = min(files.size, MAX_BATCH_PLATES)
        batchCurrent = files.first().name
        batchStatus = ""
        scope.launch(Dispatchers.Default) {
            try {
                val input = ColonyBatchInput(
                    params = params,
                    adjust = adjust,
                    dpi = dpi,
                    showNumbers = showNumbers,
                    dilutionFactor = dilutionFactorNum ?: Double.NaN,
                    platedVolume = platedVolumeNum ?: Double.NaN,
                )
                val result = runColonyBatch(files, input) { done, total, name ->
                    batchDone = done
                    batchTotal = total
                    batchCurrent = name
                }
                val zipName = colonyBatchBaseName(files.first().name) + "-batch.zip"
                val dir = File(ctx.filesDir, "colony-exports").apply { mkdirs() }
                val out = File(dir, zipName)
                Repro.zipStore(result.entries, out)
                val done = interp(
                    "批量完成：成功处理 {ok} 张，失败 {failed} 张。",
                    "ok" to result.processed, "failed" to result.failed,
                )
                batchStatus = done
                withContext(Dispatchers.Main) { toast(done) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(interp("批量处理失败：{message}", "message" to (e.message ?: "")), long = true)
                }
            } finally {
                batchRunning = false
                batchCurrent = ""
            }
        }
    }

    // -------------------------------------------------------------- debounce

    // scheduleDetect：260ms
    LaunchedEffect(detectSig) {
        if (detectSig == 0) return@LaunchedEffect
        if (!autoDetect || pipeline.processed == null) return@LaunchedEffect
        delay(260)
        runDetection(false)
    }

    // scheduleAdjust：60ms
    LaunchedEffect(adjustSig) {
        if (adjustSig == 0) return@LaunchedEffect
        delay(60)
        renderNow(autoDetect)
    }

    // scheduleCrop：180ms
    LaunchedEffect(cropSig) {
        if (cropSig == 0) return@LaunchedEffect
        delay(180)
        rebuildWork(true, autoDetect)
    }

    // 自动保存：800ms debounce（快照内容签名变化触发）
    var saveSig by remember { mutableStateOf(0) }
    var lastSaveSig by remember { mutableStateOf(0) }
    LaunchedEffect(
        saveSig, hydrated,
    ) {
        if (!hydrated || imageDataUrl.isEmpty() || workInfo == null) return@LaunchedEffect
        lastSaveSig++
        val token = lastSaveSig
        delay(800)
        if (token != lastSaveSig) return@LaunchedEffect
        try {
            val compact = withContext(Dispatchers.Default) { compactProjectImage(snapshot()) }
            val ok = saveSnapshot(ctx, compact)
            if (ok) {
                saveStatus = t("已自动保存到本地")
            } else if (!autosaveWarned) {
                autosaveWarned = true
                toast(t("自动保存失败 - 浏览器存储已满，请使用保存项目"), long = true)
            }
        } catch (e: Exception) {
            // 快照失败不打断主流程
        }
    }
    // 快照签名：任何入快照的状态变化都递增
    LaunchedEffect(
        imageDataUrl, imageName, sourceWidth, sourceHeight, rotate, crop, adjust, params,
        markers, dpi, dilutionText, platedVolumeText, workInfo, hasMask,
    ) {
        if (hydrated && imageDataUrl.isNotEmpty() && workInfo != null) saveSig++
    }

    // 挂载：恢复本地会话（原版 onMount 无项目 handoff 分支）
    LaunchedEffect(Unit) {
        val saved = loadSnapshot(ctx)
        if (saved != null && saved.imageDataUrl.isNotEmpty()) {
            try {
                applyProject(saved)
                toast(t("已恢复上次的本地会话"))
            } catch (e: Exception) {
                /* 忽略损坏的快照 */
            }
        }
        hydrated = true
    }

    // --------------------------------------------------------------- pickers

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uriDisplayName(uri)
            val mime = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null }
            importBytes(name, readUriBytes(uri), mime)
        }
    }

    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                readUriBytes(uri).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            if (text.isNotEmpty()) importProjectText(text) else toast(t("该文件不是菌落项目"), long = true)
        }
    }

    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris == null || uris.isEmpty()) {
            toast(t("未选择孔板图片。"))
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { uri ->
                try {
                    val name = uriDisplayName(uri)
                    val mime = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null }
                    if (mime?.startsWith("image/") != true && !isImageName(name)) return@mapNotNull null
                    ColonyBatchFile(name, readUriBytes(uri))
                } catch (e: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main) { runBatch(files) }
        }
    }

    // ================================================================ 布局

    Column(Modifier.fillMaxSize().padding(6.dp)) {
        // ------------------------------------------------------ 工具栏
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixButton(
                if (hasImage) t("替换图像") else t("导入培养皿图像"),
                enabled = busy == "",
                onClick = { imagePicker.launch(arrayOf("image/*", "application/octet-stream")) },
            )
            PixButton(
                if (busy == "detect") t("检测中…") else t("检测菌落"),
                accent = true,
                enabled = hasImage && busy == "",
                onClick = { runDetection(true) },
            )
            PixButton(
                t("批量计数"),
                enabled = busy == "" && !batchRunning,
                onClick = { batchPicker.launch(arrayOf("image/*")) },
            )
            Spacer(Modifier.weight(1f))
            if (batchRunning) {
                Text(
                    interp(
                        "正在处理 {done}/{total}：{name}",
                        "done" to batchDone, "total" to batchTotal, "name" to batchCurrent,
                    ),
                    fontSize = 8.sp, color = C_MASK, maxLines = 1,
                )
            } else if (batchStatus.isNotEmpty()) {
                Text(batchStatus, fontSize = 8.sp, color = C_MASK, maxLines = 1)
            }
            PixButton(t("打开项目"), onClick = { projectPicker.launch(arrayOf("*/*")) })
            PixButton(t("保存项目"), enabled = hasImage && busy != "export", onClick = { saveProject() })
            PixButton(t("重置工具"), danger = true, onClick = { resetAll() })
            if (saveStatus.isNotEmpty()) {
                Text(saveStatus, fontSize = 8.sp, color = C_INC)
            }
        }

        // ------------------------------------------------------ 三栏主体
        Row(Modifier.fillMaxSize().padding(top = 4.dp)) {
            // ------------------------------------------------- 左侧面板
            Column(
                Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                GelSection(t("图像"), startOpen = true) {
                    if (!hasImage) {
                        HintText(t("尚无培养皿图像 - 导入 PNG、JPEG、WebP 或 TIFF 照片开始计数"))
                        HintText(t("可将图像拖放到页面任意位置"))
                    } else {
                        MetaText(interp("源图像：{w} × {h} px", "w" to sourceWidth, "h" to sourceHeight))
                        MetaText(
                            interp(
                                "工作图像：{w} × {h} px",
                                "w" to (workInfo?.first ?: 0), "h" to (workInfo?.second ?: 0),
                            ),
                        )
                        if (workInfo?.third == true) {
                            WarnText(interp("大图像：处理尺寸上限为 {max} px，结果已映射回原坐标", "max" to MAX_WORK_DIM))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            PixButton(t("左转"), onClick = {
                                rotate = (rotate - 90 + 360) % 360
                                rebuildWork(true, autoDetect)
                            })
                            PixButton(t("右转"), onClick = {
                                rotate = (rotate + 90) % 360
                                rebuildWork(true, autoDetect)
                            })
                            PixButton(t("旋转 180°"), onClick = {
                                rotate = (rotate + 180) % 360
                                rebuildWork(true, autoDetect)
                            })
                        }
                        SubHead(t("裁剪"))
                        CropSlider(t("左"), crop.left) { crop = crop.copy(left = it); cropSig++ }
                        CropSlider(t("上"), crop.top) { crop = crop.copy(top = it); cropSig++ }
                        CropSlider(t("右"), crop.right) { crop = crop.copy(right = it); cropSig++ }
                        CropSlider(t("下"), crop.bottom) { crop = crop.copy(bottom = it); cropSig++ }
                        PixButton(t("重置裁剪"), onClick = {
                            crop = defaultCrop()
                            rebuildWork(true, autoDetect)
                        })
                        SubHead(t("重置调整"))
                        PixCheck(t("灰度"), adjust.grayscale) {
                            adjust = adjust.copy(grayscale = it)
                            adjustSig++
                        }
                        PixCheck(t("反相"), adjust.invert) {
                            adjust = adjust.copy(invert = it)
                            adjustSig++
                        }
                        ValueSlider(t("亮度"), adjust.brightness, -100.0, 100.0, 200) {
                            adjust = adjust.copy(brightness = it)
                            adjustSig++
                        }
                        ValueSlider(t("对比度"), adjust.contrast, 0.2, 3.0, 55) {
                            adjust = adjust.copy(contrast = it)
                            adjustSig++
                        }
                        PixButton(t("重置调整"), onClick = {
                            adjust = defaultAdjust()
                            renderNow(autoDetect)
                        })
                    }
                }

                GelSection(t("检测"), startOpen = true) {
                    PixCheck(t("参数变化时自动检测"), autoDetect) { checked ->
                        autoDetect = checked
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    PixCheck(t("Otsu 自动阈值"), params.autoThreshold) { checked ->
                        params = params.copy(
                            autoThreshold = checked,
                            manualThreshold = if (!checked && otsuValue > 0)
                                otsuValue.toDouble().roundToInt() else params.manualThreshold,
                        )
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    ValueSlider(
                        t("阈值"),
                        if (params.autoThreshold) otsuValue.toDouble() else params.manualThreshold.toDouble(),
                        0.0, 255.0, 255,
                        enabled = !params.autoThreshold,
                    ) {
                        params = params.copy(manualThreshold = it.roundToInt(), autoThreshold = false)
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    if (otsuValue > 0) {
                        HintText(
                            interp("Otsu 值：{value}", "value" to kotlin.math.round(otsuValue.toDouble())) +
                                " · " + t("阈值") + ": " + detectedThreshold + " · " +
                                if (resolvedPolarity == "dark") t("亮底暗菌落") else t("暗底亮菌落")
                        )
                    }
                    ValueSlider(t("模糊半径"), params.blurRadius, 0.0, 10.0, 19) {
                        params = params.copy(blurRadius = it)
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("菌落极性"), fontSize = 8.5.sp)
                        Spacer(Modifier.width(4.dp))
                        PixSelect(
                            listOf(
                                POLARITY_AUTO to t("自动"),
                                POLARITY_DARK to t("亮底暗菌落"),
                                POLARITY_BRIGHT to t("暗底亮菌落"),
                            ),
                            params.polarity,
                        ) { value ->
                            params = params.copy(polarity = value)
                            if (autoDetect) {
                                cancelDetection()
                                detectSig++
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t("最小面积 (px²)"), fontSize = 8.5.sp)
                            NumberField(jsPlain(params.minArea)) { text ->
                                text.toDoubleOrNull()?.let { v ->
                                    params = params.copy(minArea = max(0.0, v))
                                    if (autoDetect) {
                                        cancelDetection()
                                        detectSig++
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t("最大面积 (px²，0 = 关闭)"), fontSize = 8.5.sp)
                            NumberField(jsPlain(params.maxArea)) { text ->
                                text.toDoubleOrNull()?.let { v ->
                                    params = params.copy(maxArea = max(0.0, v))
                                    if (autoDetect) {
                                        cancelDetection()
                                        detectSig++
                                    }
                                }
                            }
                        }
                    }
                    ValueSlider(t("最小圆度 (0 = 关闭)"), params.minCircularity, 0.0, 1.0, 19) {
                        params = params.copy(minCircularity = it)
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    PixCheck(t("分离粘连菌落"), params.splitTouching) {
                        params = params.copy(splitTouching = it)
                        if (autoDetect) {
                            cancelDetection()
                            detectSig++
                        }
                    }
                    HintText(t("通过距离变换、局部极大值和分水岭式分割拆开融合的菌落"))
                }

                GelSection(t("蓝白斑分类"), startOpen = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("菌落颜色分类"), fontSize = 8.5.sp)
                        Spacer(Modifier.width(4.dp))
                        PixSelect(
                            listOf(
                                "off" to t("关闭"),
                                COLOR_MODE_BLUE_WHITE to t("蓝/白"),
                            ),
                            params.colorMode,
                        ) { value ->
                            params = params.copy(colorMode = value)
                            if (value == COLOR_MODE_BLUE_WHITE && pipeline.workMeta != null) {
                                val (colorResult, classified) = classifyMarkers(
                                    bitmapPixelSource(pipeline.workMeta!!.bitmap), markers,
                                )
                                imageHasColor = colorResult.imageHasColor
                                markers = classified
                            } else {
                                markers = clearClassification(markers)
                                imageHasColor = false
                            }
                            renderVersion++
                        }
                    }
                    HintText(t("测量每个标记的平均 RGB 和色相。灰度图像保持未分类。"))
                    if (colorModeOn) {
                        if (!imageHasColor) {
                            WarnText(t("检测到灰度图像：无法进行颜色分类。"))
                        }
                        MetaText(
                            interp(
                                "蓝色 {blue} · 白色 {white} · 未分类 {unclassified}",
                                "blue" to classCounts.blue, "white" to classCounts.white,
                                "unclassified" to classCounts.unclassified,
                            ),
                        )
                        LegendRow(
                            listOf(
                                C_BLUE to t("蓝色"),
                                C_WHITE to t("白色"),
                                C_DIM to t("未分类"),
                            ),
                        )
                    }
                }

                GelSection(t("手动校正"), startOpen = true) {
                    HintText(t("点击空白处添加菌落"))
                    HintText(t("点击标记可排除或恢复该菌落"))
                    HintText(t("拖动标记可移动位置"))
                    MetaText(interp("手动标记：{count}", "count" to stats.manual))
                    if (selected != null) {
                        MarkerCard(
                            selected = selected,
                            colorModeOn = colorModeOn,
                            onSetClass = { setSelectedClass(it) },
                            onToggle = { toggleSelected() },
                            onDelete = { deleteSelected() },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        ) {
                            Text(t("未选中标记"), fontSize = 8.5.sp, modifier = Modifier.padding(4.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        PixButton(t("全部计入"), enabled = markers.isNotEmpty(), onClick = { includeAll() })
                        PixButton(t("清除所有标记"), danger = true, enabled = markers.isNotEmpty(), onClick = { clearMarkers() })
                    }
                    LegendRow(
                        listOf(
                            C_INC to t("计入"),
                            C_EXC to t("排除"),
                            C_MANUAL to t("手动"),
                        ),
                    )
                }
            }

            // ------------------------------------------------------- 中央舞台
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("检测视图"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    PixCheck(t("显示掩膜"), showMask) { if (hasMask) showMask = it }
                    Spacer(Modifier.width(6.dp))
                    PixCheck(t("显示编号"), showNumbers) { showNumbers = it }
                    Spacer(Modifier.width(6.dp))
                    Text("${stats.included} / ${stats.total}", fontSize = 8.sp, color = C_DIM)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0B1015))
                        .onSizeChanged { stageSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasImage && pipeline.processed != null && workInfo != null) {
                        ColonyStage(
                            pipeline = pipeline,
                            renderVersion = renderVersion,
                            markers = markers,
                            numbering = numbering,
                            selectedId = selectedId,
                            showMask = showMask,
                            showNumbers = showNumbers,
                            colorModeOn = colorModeOn,
                            busyDetect = busy == "detect",
                            stageSize = stageSize,
                            workInfo = workInfo!!,
                            onTapAdd = { x, y -> addManual(x, y) },
                            onHitSelect = { id -> selectedId = id },
                            onDragMove = { id, x, y ->
                                markers = markers.map {
                                    if (it.id == id) it.copy(x = x, y = y) else it
                                }
                            },
                            onDragEnd = { id, moved ->
                                val marker = markers.firstOrNull { it.id == id }
                                if (marker != null) {
                                    if (!moved) {
                                        markers = markers.map {
                                            if (it.id == id) it.copy(included = !it.included) else it
                                        }
                                        selectedId = id
                                    } else {
                                        markers = markers.map {
                                            if (it.id == id) it.copy(meanIntensity = sampleIntensity(it.x, it.y)) else it
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("🔬", fontSize = 30.sp)
                            Text(t("导入图像后开始计数"), fontSize = 10.sp)
                            PixButton(t("导入培养皿图像"), accent = true) {
                                imagePicker.launch(arrayOf("image/*", "application/octet-stream"))
                            }
                            Text(t("可将图像拖放到页面任意位置"), fontSize = 8.sp, color = C_DIM)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    if (hasMask && showMask) {
                        HintText(t("掩膜 PNG 按检测分辨率导出"))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(t("px²"), fontSize = 8.sp, color = C_DIM)
                }
            }

            // ------------------------------------------------- 右侧结果面板
            Column(
                Modifier
                    .width(235.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                GelSection(t("结果"), startOpen = true) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(t("菌落计数"), fontSize = 9.sp, color = C_DIM)
                        Text(
                            stats.included.toString(),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = C_INC,
                        )
                    }
                    Text(
                        interp(
                            "计数 95% 置信区间 {low} – {high}",
                            "low" to formatScientific(countCi.low),
                            "high" to formatScientific(countCi.high),
                        ),
                        fontSize = 8.sp, color = C_DIM,
                    )
                    StatRow(t("计入"), stats.included.toString(), color = C_INC)
                    StatRow(t("排除"), stats.excluded.toString(), color = C_EXC)
                    if (colorModeOn) {
                        StatRow(t("蓝色"), classCounts.blue.toString())
                        StatRow(t("白色"), classCounts.white.toString())
                        StatRow(t("未分类"), classCounts.unclassified.toString())
                    }
                    StatRow(t("平均面积"), formatArea(stats.meanArea) + " " + t("px²"))
                    StatRow(t("中位面积"), formatArea(stats.medianArea) + " " + t("px²"))
                    StatRow(t("平均强度"), jsFixed(stats.meanIntensity, 1))
                    SubHead(t("菌落面积分布"))
                    if (histogram.counts.isNotEmpty()) {
                        HistogramView(histogram)
                    } else {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        ) {
                            Text(t("尚无计入的菌落"), fontSize = 8.5.sp, modifier = Modifier.padding(4.dp))
                        }
                    }
                    if (markers.isEmpty()) {
                        HintText(t("未检测到标记 - 请调整阈值或手动添加菌落"))
                    }
                }

                GelSection(t("CFU 计算器"), startOpen = true) {
                    Text(t("稀释倍数"), fontSize = 8.5.sp)
                    NumberField(dilutionText) { dilutionText = it }
                    Spacer(Modifier.height(3.dp))
                    Text(t("涂布体积 (mL)"), fontSize = 8.5.sp)
                    NumberField(platedVolumeText) { platedVolumeText = it }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(t("CFU/mL"), fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatScientific(cfu),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (cfu == null) {
                        WarnText(t("请输入大于零的稀释倍数和涂布体积"))
                    } else {
                        Text(
                            interp(
                                "95% 置信区间 {low} – {high}",
                                "low" to formatScientific(cfuInterval?.low),
                                "high" to formatScientific(cfuInterval?.high),
                            ),
                            fontSize = 8.sp, color = C_DIM,
                        )
                    }
                    HintText(t("CFU/mL = 菌落数 × 稀释倍数 ÷ 涂布体积"))
                }

                GelSection(t("导出"), startOpen = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("分辨率"), fontSize = 8.5.sp)
                        Spacer(Modifier.weight(1f))
                        PixButton("300 dpi", active = dpi == 300, onClick = { dpi = 300 })
                        PixButton("600 dpi", active = dpi == 600, onClick = { dpi = 600 })
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        PixButton(t("标注 PNG"), enabled = hasImage && busy == "", onClick = { exportAnnotated() })
                        PixButton(
                            t("发送到 Figure Studio"),
                            enabled = hasImage && pipeline.processed != null && busy == "",
                            onClick = { sendToFigureNow() },
                        )
                        PixButton(t("掩膜 PNG"), enabled = hasMask && busy == "", onClick = { exportMask() })
                        PixButton(t("标记 CSV"), enabled = markers.isNotEmpty() && busy == "", onClick = { exportCsv() })
                        PixButton(t("统计 CSV"), enabled = markers.isNotEmpty() && busy == "", onClick = { exportStatisticsCsv() })
                        PixButton(
                            if (reproBusy) t("正在构建可复现数据包...") else t("可复现数据包"),
                            enabled = hasImage && pipeline.maskPlane != null && !reproBusy,
                            onClick = { exportReproBundle() },
                        )
                    }
                    HintText(t("标注 PNG 包含圆环和编号；DPI 写入文件元数据"))
                    HintText(t("掩膜 PNG 按检测分辨率导出"))
                    HintText(t("CSV 包含所有标记，包括已排除的标记"))
                    HintText(t("统计 CSV 包含计数和 CFU/mL 的精确泊松 95% 置信区间。"))
                    HintText(t("数据包包含标注 PNG、掩膜 PNG、标记 CSV、统计结果和项目 JSON。"))
                    HintText(t("项目 JSON 保存图像、参数和标记，便于以后继续编辑"))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ helpers

/** JS Number.toString()：1e6 → "1000000"，0.1 → "0.1"。 */
private fun jsPlain(value: Double): String {
    if (!value.isFinite()) return "0"
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return jsFixed(value, 12).trimEnd('0').trimEnd('.')
}

/** 对应 shared/batch.ts fileBaseName(name,'colony')。 */
private fun colonyBatchBaseName(name: String): String {
    val stem = name.replace(Regex("\\.[^.]+$"), "")
        .trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("^-+|-+$"), "")
    return stem.ifEmpty { "colony" }
}

@Composable
private fun HintText(text: String) {
    Text(text, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 11.sp)
}

@Composable
private fun WarnText(text: String) {
    Text(text, fontSize = 8.sp, color = Color(0xFFE9B44C), lineHeight = 11.sp)
}

@Composable
private fun MetaText(text: String) {
    Text(text, fontSize = 8.5.sp, lineHeight = 11.sp)
}

@Composable
private fun SubHead(text: String) {
    Text(text, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun StatRow(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 8.5.sp)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = color ?: Color.Unspecified)
    }
}

@Composable
private fun LegendRow(items: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape),
                )
                Text(label, fontSize = 8.sp, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

/** 原版 input[type=range]。 */
@Composable
private fun ValueSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    steps: Int,
    enabled: Boolean = true,
    onValue: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 8.5.sp)
            Spacer(Modifier.weight(1f))
            val shown = when {
                label.contains("亮度") -> jsFixed(value, 0)
                label.contains("对比度") -> jsFixed(value, 2)
                label.contains("模糊") -> jsFixed(value, 1)
                label.contains("圆度") -> jsFixed(value, 2)
                else -> kotlin.math.round(value).toString()
            }
            Text(shown, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (steps > 0) steps else 0,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
    }
}

/** 裁剪滑杆（0..0.4 step .005 → 79 段 → steps=78）。 */
@Composable
private fun CropSlider(label: String, value: Double, onValue: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Text(jsFixed(value, 3), fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toDouble()) },
            valueRange = 0f..0.4f,
            steps = 78,
            modifier = Modifier.fillMaxWidth().height(20.dp),
        )
    }
}

/** 原版 .pix-input number：文本输入即提交。 */
@Composable
private fun NumberField(value: String, onCommit: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onCommit,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth().height(40.dp),
    )
}

/** 选中标记卡（原版 .marker-card）。 */
@Composable
private fun MarkerCard(
    selected: ColonyMarker,
    colorModeOn: Boolean,
    onSetClass: (String) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(6.dp),
    ) {
        Text(
            gt("选中的标记") + " · " + gt("标记 #{id}").replace("{id}", selected.id.toString()),
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
        )
        StatRow(gt("面积"), formatArea(selected.area) + " " + gt("px²"))
        StatRow(gt("平均强度"), jsFixed(selected.meanIntensity, 1))
        StatRow(gt("圆度"), jsFixed(selected.circularity, 2))
        if (colorModeOn) {
            StatRow(gt("类别"), classLabel(selected.colonyClass))
            if (selected.hue >= 0) {
                StatRow(
                    gt("平均 RGB"),
                    jsFixed(selected.meanR, 0) + ", " + jsFixed(selected.meanG, 0) + ", " + jsFixed(selected.meanB, 0),
                )
                StatRow(gt("色相"), jsFixed(selected.hue, 0) + "° · " + jsFixed(selected.saturation, 2))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PixButton(gt("蓝色"), active = selected.colonyClass == CLASS_BLUE, onClick = { onSetClass(CLASS_BLUE) })
                PixButton(gt("白色"), active = selected.colonyClass == CLASS_WHITE, onClick = { onSetClass(CLASS_WHITE) })
                PixButton(gt("未分类"), active = selected.colonyClass == CLASS_UNCLASSIFIED, onClick = { onSetClass(CLASS_UNCLASSIFIED) })
            }
            HintText(gt("为所选标记设置类别。"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PixButton(
                if (selected.included) gt("排除") else gt("计入"),
                ok = !selected.included,
                onClick = onToggle,
            )
            PixButton(gt("删除标记"), danger = true, onClick = onDelete)
        }
    }
}

private fun classLabel(value: String): String = when (value) {
    CLASS_BLUE -> gt("蓝色")
    CLASS_WHITE -> gt("白色")
    else -> gt("未分类")
}

// ------------------------------------------------------------- stage canvas

/**
 * 中央检测视图：等价于原版 viewCanvas（调整后图像）+ overlayCanvas（掩膜 +
 * 圆环/编号/选中环）的双层画布，并承载指针手势（点选/拖动/添加）。
 */
@Composable
private fun ColonyStage(
    pipeline: ColonyPipeline,
    renderVersion: Int,
    markers: List<ColonyMarker>,
    numbering: com.spice.nativeapp.tools.colony.MarkerNumbering,
    selectedId: Int?,
    showMask: Boolean,
    showNumbers: Boolean,
    colorModeOn: Boolean,
    busyDetect: Boolean,
    stageSize: IntSize,
    workInfo: Triple<Int, Int, Boolean>,
    onTapAdd: (Double, Double) -> Unit,
    onHitSelect: (Int) -> Unit,
    onDragMove: (Int, Double, Double) -> Unit,
    onDragEnd: (Int, Boolean) -> Unit,
) {
    // rememberUpdatedState：手势闭包读最新值而不重启 pointerInput
    val markersState = rememberUpdatedState(markers)
    val busyDetectState = rememberUpdatedState(busyDetect)
    val showMaskState = rememberUpdatedState(showMask)
    val showNumbersState = rememberUpdatedState(showNumbers)
    val selectedIdState = rememberUpdatedState(selectedId)
    val colorModeOnState = rememberUpdatedState(colorModeOn)

    val density = LocalDensity.current
    val stageW = stageSize.width
    val stageH = stageSize.height

    // fitDisplay：min((w-16)/W, (h-16)/H)
    val dispW: Int
    val dispH: Int
    if (stageW >= 8 && stageH >= 8) {
        val scale = min((stageW - 16.0) / workInfo.first, (stageH - 16.0) / workInfo.second)
        dispW = max(32, kotlin.math.round(workInfo.first * scale).toInt())
        dispH = max(32, kotlin.math.round(workInfo.second * scale).toInt())
    } else {
        dispW = max(32, workInfo.first)
        dispH = max(32, workInfo.second)
    }

    // 渲染版本驱动重绘
    key(renderVersion) { /* no-op：仅作重组键 */ }

    val image = remember(renderVersion) { pipeline.processed?.asImageBitmap() }
    val maskImage = remember(renderVersion) { pipeline.maskBitmap?.asImageBitmap() }

    Box(
        Modifier
            .size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() })
            .pointerInput(workInfo.first, workInfo.second) {
                fun canvasPoint(pos: Offset): Offset? {
                    if (dispW < 2 || dispH < 2) return null
                    val x = ((pos.x / dispW).toDouble() * workInfo.first).coerceIn(0.0, workInfo.first.toDouble())
                    val y = ((pos.y / dispH).toDouble() * workInfo.second).coerceIn(0.0, workInfo.second.toDouble())
                    return Offset(x.toFloat(), y.toFloat())
                }
                val displayScale = if (dispW > 0) workInfo.first.toDouble() / dispW else 1.0
                fun hitMarker(x: Double, y: Double): ColonyMarker? {
                    val list = markersState.value
                    val minRadius = 9.0 * displayScale
                    for (i in list.indices.reversed()) {
                        val marker = list[i]
                        val radius = max(marker.radius * 1.25, minRadius)
                        if (hypot(marker.x - x, marker.y - y) <= radius) return marker
                    }
                    return null
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (busyDetectState.value) return@awaitEachGesture
                    val point = canvasPoint(down.position) ?: return@awaitEachGesture
                    val px = point.x.toDouble()
                    val py = point.y.toDouble()
                    val hit = hitMarker(px, py)
                    var drag: ColonyDragState? = null
                    var pendingAdd: Offset? = null
                    if (hit != null) {
                        drag = ColonyDragState(hit.id, px, py, hit.x - px, hit.y - py, false)
                        pendingAdd = null
                        onHitSelect(hit.id)
                    } else {
                        pendingAdd = point
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToUp()) {
                            val up = canvasPoint(change.position)
                            if (drag != null) {
                                onDragEnd(drag.id, drag.moved)
                            } else if (pendingAdd != null && up != null) {
                                onTapAdd(up.x.toDouble(), up.y.toDouble())
                            }
                            break
                        }
                        val move = canvasPoint(change.position) ?: break
                        val threshold = 4.0 * displayScale
                        if (drag != null) {
                            if (!drag.moved &&
                                hypot(move.x - drag.startX, move.y - drag.startY) > threshold
                            ) {
                                drag.moved = true
                            }
                            if (drag.moved) {
                                onDragMove(
                                    drag.id,
                                    (move.x + drag.offsetX).coerceIn(0.0, workInfo.first - 1.0),
                                    (move.y + drag.offsetY).coerceIn(0.0, workInfo.second - 1.0),
                                )
                            }
                        } else if (pendingAdd != null) {
                            if (hypot(move.x - pendingAdd.x, move.y - pendingAdd.y) > threshold * 1.5) {
                                pendingAdd = null
                            }
                        }
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / workInfo.first
            val sy = size.height / workInfo.second
            // viewCanvas：调整后图像
            if (image != null) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            // 掩膜叠加（rgba(76,214,255,110) 预乘）
            if (showMaskState.value && maskImage != null) {
                drawImage(
                    image = maskImage,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(maskImage.width, maskImage.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            // 圆环/编号/选中环
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = with(density) { 10.dp.toPx() }
            }
            val native = drawContext.canvas.nativeCanvas
            for (marker in markers) {
                val x = (marker.x * sx).toFloat()
                val y = (marker.y * sy).toFloat()
                val radius = max(5.0, marker.radius * sx).toFloat()
                val color = when {
                    !marker.included -> C_EXC
                    marker.source == SOURCE_MANUAL -> C_MANUAL
                    colorModeOnState.value && marker.colonyClass == CLASS_BLUE -> C_BLUE
                    colorModeOnState.value && marker.colonyClass == CLASS_WHITE -> C_WHITE
                    else -> C_INC
                }
                drawCircle(color = color, radius = radius, center = Offset(x, y), style = Stroke(width = 1.6f))
                if (marker.included) {
                    drawCircle(
                        color = color,
                        radius = max(1.2, radius * 0.14).toFloat(),
                        center = Offset(x, y),
                    )
                } else {
                    val arm = radius * 0.7f
                    drawLine(color, Offset(x - arm, y - arm), Offset(x + arm, y + arm), strokeWidth = 1.6f)
                    drawLine(color, Offset(x + arm, y - arm), Offset(x - arm, y + arm), strokeWidth = 1.6f)
                }
                if (showNumbersState.value) {
                    val label = if (marker.included) {
                        numbering.labels[marker.id]?.toString() ?: ""
                    } else "×"
                    if (label.isNotEmpty()) {
                        val bx = x + radius * 0.8f
                        val by = y - radius * 0.8f
                        val textWidth = textPaint.measureText(label)
                        val boxWidth = textWidth + 6
                        val boxHeight = textPaint.textSize + 4
                        val left = bx - boxWidth / 2
                        val top = by - boxHeight / 2
                        drawRect(
                            color = Color(0xCC060915),
                            topLeft = Offset(left, top),
                            size = Size(boxWidth, boxHeight),
                        )
                        drawRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = Size(boxWidth, boxHeight),
                            style = Stroke(width = 0.8f),
                        )
                        textPaint.color = android.graphics.Color.argb(
                            (color.alpha * 255).toInt(),
                            (color.red * 255).toInt(),
                            (color.green * 255).toInt(),
                            (color.blue * 255).toInt(),
                        )
                        val baseline = by + 0.5f * density.density
                        native.drawText(label, bx, baseline, textPaint)
                    }
                }
                if (marker.id == selectedIdState.value) {
                    // 黄色虚线选中环（[3,3] dash）
                    val r = radius + 3
                    var angle = 0f
                    val segments = 45
                    val step = 360f / segments
                    while (angle < 360f) {
                        drawArc(
                            color = C_SEL,
                            startAngle = angle,
                            sweepAngle = step / 2,
                            useCenter = false,
                            topLeft = Offset(x - r, y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = 1.4f),
                        )
                        angle += step
                    }
                }
            }
        }
    }
}

/** 面积分布直方图（原版 SVG viewBox 0 0 280 132）。 */
@Composable
private fun HistogramView(histogram: com.spice.nativeapp.tools.colony.Histogram) {
    val density = LocalDensity.current
    var boxWidth by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().onSizeChanged { boxWidth = it.width.toFloat() }) {
        if (boxWidth > 0f) {
            val s = (boxWidth / 280f).coerceAtLeast(0.3f)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height((132 * s).dp),
            ) {
                val counts = histogram.counts
                val barWidth = 260f / counts.size
                for (i in counts.indices) {
                    val barHeight = counts[i] / histogram.peak.toFloat() * 92f
                    drawRect(
                        color = C_MASK.copy(alpha = 0.78f),
                        topLeft = Offset((10 + i * barWidth + 1) * s, (102 - barHeight) * s),
                        size = Size(max(1f, (barWidth - 2) * s), max(0f, barHeight * s)),
                    )
                }
                drawLine(
                    color = Color(0xFF3D4B5C),
                    start = Offset(10 * s, 103 * s),
                    end = Offset(270 * s, 103 * s),
                    strokeWidth = 1f * s,
                )
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = with(density) { 8.dp.toPx() } * s
                    color = android.graphics.Color.argb(255, 0x9A, 0xA5, 0xB1)
                }
                val native = drawContext.canvas.nativeCanvas
                native.drawText(kotlin.math.round(histogram.min).toString(), 10 * s, 122 * s, paint)
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                native.drawText(kotlin.math.round(histogram.max).toString(), 270 * s, 122 * s, paint)
                paint.textAlign = android.graphics.Paint.Align.CENTER
                native.drawText(gt("px²"), 140 * s, 122 * s, paint)
            }
        }
    }
}
