package com.spice.nativeapp.tools.colony

/*
 * 培养皿照片文件夹批量计数，1:1 移植 main 的 src/lib/tools/colony/batch.ts。
 * 每张图像走与编辑器相同的解码路径（含 TIFF），用当前检测参数分割，
 * 导出标注 PNG + 标记 CSV，汇总为 summary.csv + failures.txt。
 *
 * 平台差异：Web Worker/await → 协程（Dispatchers.Default）；
 * File[] → List<ColonyBatchFile(name, bytes)>；ZIP 由调用方用 Repro.zipStore
 * 组装（本模块只构建条目，与原版职责划分一致）。
 */
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** UI 与 runner 共享的硬上限。 */
const val MAX_BATCH_PLATES = 40

class ColonyBatchFile(val name: String, val bytes: ByteArray)

class ColonyBatchInput(
    val params: ColonyParams,
    val adjust: ColonyAdjust,
    val dpi: Int,          // 300 | 600
    val showNumbers: Boolean,
    val dilutionFactor: Double,
    val platedVolume: Double,
)

class ColonyBatchFailure(val name: String, val message: String)

class ColonyBatchResult(
    val entries: List<Pair<String, ByteArray>>,
    val processed: Int,
    val failed: Int,
    val failures: List<ColonyBatchFailure>,
    /** 超过 MAX_BATCH_PLATES 被忽略的图像数。 */
    val clipped: Int,
)

typealias ColonyBatchProgress = (done: Int, total: Int, name: String) -> Unit

/** 对应 shared/batch.ts 的 fileBaseName。 */
private fun fileBaseName(name: String, fallback: String): String {
    val stem = name.replace(Regex("\\.[^.]+$"), "")
        .trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("^-+|-+$"), "")
    return stem.ifEmpty { fallback }
}

private fun csvCell(value: String?): String {
    if (value == null) return ""
    return if (Regex("[\",\\r\\n]").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value
}

private fun uniqueStem(name: String, used: MutableSet<String>): String {
    val base = fileBaseName(name, "plate")
    var candidate = base
    var index = 2
    while (used.contains(candidate)) candidate = "$base-${index++}"
    used.add(candidate)
    return candidate
}

private fun batchSegmentRequest(params: ColonyParams, fx: Double, fy: Double): SegmentRequest {
    val areaScale = fx * fy
    return SegmentRequest(
        threshold = if (params.autoThreshold) null
        else min(255, max(0, params.manualThreshold)),
        polarity = params.polarity,
        blurRadius = params.blurRadius * min(fx, fy),
        minArea = max(0.0, params.minArea) * areaScale,
        maxArea = max(0.0, params.maxArea) * areaScale,
        minCircularity = params.minCircularity,
        splitTouching = params.splitTouching,
    )
}

private fun fixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

/** 逐张处理；onProgress 在每张前后各回调一次（done/total/name）。 */
suspend fun runColonyBatch(
    files: List<ColonyBatchFile>,
    input: ColonyBatchInput,
    onProgress: ColonyBatchProgress? = null,
): ColonyBatchResult = withContext(Dispatchers.Default) {
    val selected = files.take(MAX_BATCH_PLATES)
    val clipped = max(0, files.size - selected.size)
    val entries = mutableListOf<Pair<String, ByteArray>>()
    val failures = mutableListOf<ColonyBatchFailure>()
    val usedStems = mutableSetOf<String>()
    val summaryRows = mutableListOf(
        "file,status,count,blue,white,unclassified,count_ci95_low,count_ci95_high,cfu_per_ml,cfu_ci95_low,cfu_ci95_high,threshold,polarity",
    )
    var processed = 0

    for ((index, file) in selected.withIndex()) {
        onProgress?.invoke(index, selected.size, file.name)
        try {
            val decoded = decodeImageFile(file.bytes, file.name, null)
            val image = loadImageBitmap(decoded.dataUrl)
            val work = buildWorkBitmap(image, 0, defaultCrop())
            val processedCanvas = renderAdjusted(work.bitmap, input.adjust)
            val scaled = downscaleCanvas(processedCanvas, MAX_DETECT_DIM)
            val plane = extractGray(scaled.bitmap)
            val fx = plane.width.toDouble() / max(1, work.width)
            val fy = plane.height.toDouble() / max(1, work.height)
            val areaScale = fx * fy
            val result = segmentGray(plane.gray, plane.width, plane.height, batchSegmentRequest(input.params, fx, fy))

            var markers: List<ColonyMarker> = result.colonies.mapIndexed { markerIndex, colony ->
                val area = colony.area / areaScale
                ColonyMarker(
                    id = markerIndex + 1,
                    x = colony.x / fx,
                    y = colony.y / fy,
                    radius = sqrt(max(0.0, area) / Math.PI),
                    area = area,
                    meanIntensity = colony.meanIntensity,
                    circularity = colony.circularity,
                    included = true,
                    source = SOURCE_AUTO,
                    meanR = 0.0, meanG = 0.0, meanB = 0.0,
                    hue = -1.0, saturation = 0.0,
                    colonyClass = CLASS_UNCLASSIFIED,
                    classManual = false,
                )
            }

            if (input.params.colorMode == COLOR_MODE_BLUE_WHITE) {
                markers = classifyMarkers(bitmapPixelSource(work.bitmap), markers).second
            }

            val numbering = numberMarkers(markers)
            val stats = computeStats(markers)
            val classes = countClasses(markers)
            val cfu = computeCfu(stats.included, input.dilutionFactor, input.platedVolume)
            val countCi = poissonCi95(stats.included)
            val cfuInterval = cfuCi95(stats.included, input.dilutionFactor, input.platedVolume)
            val stem = uniqueStem(file.name, usedStems)

            val png = annotatedPngBytes(processedCanvas, markers, numbering, input.dpi, input.showNumbers)
            entries.add("$stem" + "_annotated_${input.dpi}dpi.png" to png)
            entries.add("$stem" + "_markers.csv" to markersToCsv(markers, numbering).toByteArray(Charsets.UTF_8))

            summaryRows.add(
                listOf(
                    csvCell(file.name),
                    "ok",
                    stats.included.toString(),
                    classes.blue.toString(),
                    classes.white.toString(),
                    classes.unclassified.toString(),
                    fixed(countCi.low, 2),
                    fixed(countCi.high, 2),
                    if (cfu == null) "" else fixed(cfu, 4),
                    if (cfuInterval == null) "" else fixed(cfuInterval.low, 4),
                    if (cfuInterval == null) "" else fixed(cfuInterval.high, 4),
                    result.threshold.toDouble().roundToInt().toString(),
                    result.polarity,
                ).joinToString(","),
            )
            processed++
        } catch (error: Exception) {
            val message = error.message ?: error.toString()
            failures.add(ColonyBatchFailure(file.name, message))
            summaryRows.add(
                listOf(csvCell(file.name), "failed", "", "", "", "", "", "", "", "", "", "", "").joinToString(","),
            )
        }
        onProgress?.invoke(index + 1, selected.size, file.name)
    }

    entries.add("summary.csv" to summaryRows.joinToString("\n").toByteArray(Charsets.UTF_8))
    entries.add(
        "failures.txt" to (
            if (failures.isNotEmpty())
                failures.joinToString("\n") { "${it.name}: ${it.message}" }
            else "No failures."
            ).toByteArray(Charsets.UTF_8),
    )

    ColonyBatchResult(entries, processed, failures.size, failures, clipped)
}
