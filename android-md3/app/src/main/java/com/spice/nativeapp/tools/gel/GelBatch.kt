package com.spice.nativeapp.tools.gel

/**
 * Folder batch mode for the gel figure tool.
 * 1:1 移植自原版 src/lib/tools/gel/batch.ts（+ shared/batch.ts 辅助）。
 * 原版 batch.worker.ts 的 Web Worker 在安卓侧由调用方协程承载，语义一致。
 *
 * Every image runs the same automatic pipeline as the editor: straighten,
 * auto-crop, infer the gel type, detect lanes and bands, optionally fit the
 * selected marker ladder, then render a publication figure. Results are packed
 * as one ZIP with a PNG, project JSON and one summary.csv row per image.
 */
import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GelBatch {

    const val MAX_BATCH_IMAGES = 60

    /** shared/batch.ts fileBaseName：去扩展名、小写、非字母数字折叠为连字符。 */
    fun fileBaseName(name: String, fallback: String = "file"): String {
        val stem = name.replace(Regex("\\.[^.]+$"), "")
            .trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
        return stem.ifEmpty { fallback }
    }

    /** shared/batch.ts imageFiles：按 MIME 或扩展名筛图片。 */
    fun isImageFile(mime: String?, name: String): Boolean =
        (mime != null && mime.startsWith("image/")) ||
            Regex("\\.(png|jpe?g|webp|tiff?)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    class ZipEntryData(val name: String, val data: ByteArray)

    class GelBatchOptions(
        /** Selected marker ladder; empty string skips fitting. */
        val ladderId: String = "",
        /** Figure resolution, matching the editor export selector. */
        val dpi: Int = 300,
        /** Stain/detection config; defaults to the neutral built-in preset. */
        val detect: GelDetectConfig? = null,
        val onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null,
    )

    class GelBatchBuild(
        val entries: List<ZipEntryData>,
        val ok: Int,
        val failed: Int,
        /** Images beyond MAX_BATCH_IMAGES that were not processed. */
        val skipped: Int,
        val errors: List<Pair<String, String>>,
        val images: Int,
    )

    private class ProcessedGel(
        val project: GelProject,
        val processed: ProcessedImage,
        val png: ByteArray,
    )

    /** 输入文件句柄：Android 侧用 Uri + 显示名替代浏览器 File。 */
    class BatchFile(val uri: android.net.Uri, val name: String, val mime: String?)

    /** Downscaled, unrotated copy used only to search for the straighten angle. */
    private fun smallImageBitmap(img: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = 300
        val width = img.width
        val height = img.height
        val scale = maxSide / maxOf(width, height).toDouble()
        val w = maxOf(8, Math.round(width * scale)).toInt()
        val h = maxOf(8, Math.round(height * scale)).toInt()
        return android.graphics.Bitmap.createScaledBitmap(img, w, h, true)
    }

    private fun rotationScoreAt(small: android.graphics.Bitmap, deg: Double): Double {
        val rad = Math.toRadians(deg)
        val cos = Math.abs(Math.cos(rad))
        val sin = Math.abs(Math.sin(rad))
        val width = maxOf(8, Math.round(small.width * cos + small.height * sin)).toInt()
        val height = maxOf(8, Math.round(small.width * sin + small.height * cos)).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(deg.toFloat())
        canvas.drawBitmap(small, null, android.graphics.RectF(-small.width / 2f, -small.height / 2f, small.width / 2f, small.height / 2f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        val gray = GelMath.toGrayPlane(px, width, height)
        return GelMath.rotationScore(gray, width, height)
    }

    /** Mirror of the editor straighten search, at a slightly coarser step. */
    fun bestStraightenAngle(img: android.graphics.Bitmap): Double {
        val small = smallImageBitmap(img)
        var best = 0.0
        var bestScore = Double.NEGATIVE_INFINITY
        var a = -15.0
        while (a <= 15.0001) {
            val score = rotationScoreAt(small, a)
            if (score > bestScore) {
                bestScore = score
                best = a
            }
            a += 1.0
        }
        var fine = best
        a = best - 0.5
        while (a <= best + 0.5001) {
            val score = rotationScoreAt(small, a)
            if (score > bestScore) {
                bestScore = score
                fine = a
            }
            a += 0.1
        }
        return Math.round(fine * 10) / 10.0
    }

    /** Assign the selected ladder and size estimates to the detected lanes. */
    private fun fitSelectedLadder(project: GelProject, ladder: LadderDef): Boolean {
        val result = GelDetect.identifyMarkers(
            project.lanes,
            MatchOptions(kind = project.gelType, ladders = listOf(ladder), favorites = project.favorites),
        ) ?: return false

        project.ladderId = ladder.id
        project.fit = GelLadderFit(
            ladderId = ladder.id,
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
            lane.ladderId = if (assigned != null) ladder.id else null
            if (assigned != null) {
                val sorted = lane.bands.filter { it.hidden != true }.sortedBy { it.y }
                sorted.forEachIndexed { index, band ->
                    if (index < assigned.size) {
                        band.size = assigned[index]
                        band.showSize = true
                        band.approx = false
                    } else {
                        band.size = null
                        band.showSize = false
                    }
                }
            }
        }
        for (lane in project.lanes) {
            if (lane.isMarker || lane.removed == true) continue
            val estimated = GelDetect.estimateBandSizes(lane.bands, result.fit, ladder)
            lane.bands.forEachIndexed { index, band ->
                band.size = estimated[index].size
                band.approx = estimated[index].approx
                band.showSize = true
            }
        }
        return true
    }

    /** Run the automatic editor pipeline on one image and render its figure. */
    private fun processImage(
        ctx: Context,
        file: BatchFile,
        ladderId: String,
        dpi: Int,
        detect: GelDetectConfig?,
    ): ProcessedGel {
        val exposure = GelPipeline.decodeExposure(ctx, file.uri, file.name, file.mime)
            ?: throw IllegalArgumentException("decode failed: ${file.name}")
        val img = GelPipeline.loadBitmapFromDataUrl(exposure.dataUrl)
            ?: throw IllegalArgumentException("load failed: ${file.name}")
        val project = GelTypes.emptyProject()
        project.detect = if (detect != null) GelPresets.sanitizeDetectConfig(GelPresets.toJson(detect)) else GelPresets.defaultDetectConfig()
        project.exposures = mutableListOf(exposure)
        project.activeExposureId = exposure.id
        project.imageName = exposure.name

        // 1. Straighten, then crop the gel slab.
        project.rotation = bestStraightenAngle(img)
        val processed = GelPipeline.renderProcessed(exposure, project, img)
            ?: throw IllegalArgumentException("render failed: ${file.name}")
        val gray = processed.detectGray
        project.crop = GelMath.autoCropRect(processed.gray, processed.width, processed.height)

        // 2. Infer the gel type unless the preset forces one.
        val forced = project.detect.type
        val preliminaryProtein = forced == "protein"
        var preliminary = GelDetect.detectLanesAuto(gray, processed.width, processed.height, preliminaryProtein, project.detect)
        val preliminaryBands = preliminary.map { lane ->
            GelDetect.detectBandsInLane(
                gray, processed.width, processed.height, lane,
                if (forced == "auto") "dna" else forced,
                project.detect,
            )
        }
        var kind = if (forced == "auto") "dna" else forced
        if (forced == "auto") {
            val maxBands = preliminaryBands.maxOfOrNull { it.size } ?: 0
            val widths = preliminaryBanksFlat(preliminaryBands)
            val medianWidth = if (widths.isNotEmpty()) widths[widths.size / 2] else 0.0
            // The stored canvas is grayscale in the default display mode, so vote on a
            // colour render of the same orientation.
            val colorProject = GelTypes.emptyProject().also {
                // 字段级复制 project 的处理状态，display 改 color
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
            val colorProcessed = GelPipeline.renderProcessed(exposure, colorProject, img)
                ?: throw IllegalArgumentException("color render failed: ${file.name}")
            val rgba = IntArray(processed.width * processed.height)
            colorProcessed.bitmap.getPixels(rgba, 0, processed.width, 0, 0, processed.width, processed.height)
            val mask = GelDetect.signalPlane(gray, processed.width, processed.height, project.detect)
            kind = GelDetect.detectGelType(rgba, maxBands, preliminary.size, medianWidth, mask).kind
            if (kind == "protein" && !preliminaryProtein) {
                preliminary = GelDetect.detectLanesAuto(gray, processed.width, processed.height, true, project.detect)
            }
        }
        project.gelType = kind
        project.unit = GelLadders.unitForKind(kind)
        project.ladderId = GelLadders.laddersFor(kind, project.customLadders).firstOrNull()?.id ?: project.ladderId

        // 3. Lanes and bands with the selected gel kind.
        project.lanes = preliminary.mapIndexed { index, lane ->
            GelLane(
                id = GelTypes.newLaneId(),
                x = lane.x,
                width = lane.width,
                name = (index + 1).toString(),
                isMarker = false,
                bands = mutableListOf(),
            )
        }.toMutableList()
        for (lane in project.lanes) {
            val detected = GelDetect.detectBandsInLane(
                gray, processed.width, processed.height,
                DetectedLane(x = lane.x, width = lane.width),
                project.gelType,
                project.detect,
            )
            lane.bands = detected.map { GelBand(y = it.y, intensity = it.intensity, showSize = true) }.toMutableList()
        }

        // 4. Optional calibration with the ladder chosen in the batch panel.
        if (ladderId.isNotEmpty()) {
            val ladder = GelLadders.ladderById(ladderId, project.customLadders)
            if (ladder != null && ladder.kind == project.gelType) fitSelectedLadder(project, ladder)
        }

        // 5. Render the figure exactly like the editor export.
        val layout = GelFigure.buildFigure(project, processed)
        val canvas = GelRender.rasterizeFigure(layout, processed, dpi.toDouble())
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
        val png = GelExport.exportPngWithMetadata(canvas, dpi.toDouble(), GelExport.summaryText(project, processed, ladder))
        return ProcessedGel(project, processed, png)
    }

    private fun preliminaryBanksFlat(bands: List<List<BandPeak>>): List<Double> =
        bands.flatten().map { it.width }.sorted()

    private fun uniqueBase(base: String, used: HashMap<String, Int>): String {
        val count = (used[base] ?: 0) + 1
        used[base] = count
        return if (count == 1) base else "$base-$count"
    }

    private val CSV_HEADER = listOf(
        "file", "width", "height", "gel_type", "unit", "lane_count", "band_count",
        "marker_lanes", "ladder", "fit_degree", "fit_r2", "rotation_deg",
        "crop_left", "crop_top", "crop_right", "crop_bottom", "png", "project", "error",
    ).joinToString(",")

    private fun csvCell(value: Any?): String {
        val text = if (value == null) "" else value.toString()
        return if (Regex("[\",\r\n]").containsMatchIn(text)) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    private fun successRow(fileName: String, result: ProcessedGel, pngName: String, projectName: String): String {
        val (project, processed) = result.project to result.processed
        val exposure = project.exposures.find { it.id == project.activeExposureId } ?: project.exposures.firstOrNull()
        val ladder = if (project.fit != null) GelLadders.ladderById(project.fit!!.ladderId, project.customLadders) else null
        val markerLanes = project.lanes.count { it.isMarker && it.removed != true }
        val bands = project.lanes.sumOf { it.bands.size }
        return listOf(
            fileName,
            (exposure?.width ?: processed.width).toString(),
            (exposure?.height ?: processed.height).toString(),
            project.gelType,
            project.unit,
            project.lanes.count { it.removed != true }.toString(),
            bands.toString(),
            markerLanes.toString(),
            ladder?.name ?: (if (project.fit != null) project.fit!!.ladderId else ""),
            project.fit?.degree?.toString() ?: "",
            if (project.fit != null) String.format(Locale.US, "%.4f", project.fit!!.r2) else "",
            String.format(Locale.US, "%.2f", project.rotation),
            String.format(Locale.US, "%.4f", project.crop.left),
            String.format(Locale.US, "%.4f", project.crop.top),
            String.format(Locale.US, "%.4f", project.crop.right),
            String.format(Locale.US, "%.4f", project.crop.bottom),
            pngName,
            projectName,
            "",
        ).joinToString(",")
    }

    private fun failureRow(fileName: String, error: String): String =
        (listOf(fileName) + List(17) { "" } + listOf(error)).joinToString(",")

    /**
     * Process up to MAX_BATCH_IMAGES images and return one ZIP payload. Saving is
     * left to the caller（原版 worker-safe 设计，安卓侧同样纯计算）。
     */
    fun buildGelBatch(ctx: Context, files: List<BatchFile>, options: GelBatchOptions = GelBatchOptions()): GelBatchBuild {
        val all = files.filter { isImageFile(it.mime, it.name) }
        val images = all.take(MAX_BATCH_IMAGES)
        val skipped = all.size - images.size
        val dpi = options.dpi
        val errors = ArrayList<Pair<String, String>>()
        val entries = ArrayList<ZipEntryData>()
        val rows = ArrayList<String>()
        rows.add(CSV_HEADER)
        val used = HashMap<String, Int>()
        var ok = 0
        var failed = 0

        for ((index, file) in images.withIndex()) {
            options.onProgress?.invoke(index + 1, images.size, file.name)
            try {
                val result = processImage(ctx, file, options.ladderId, dpi, options.detect)
                val base = uniqueBase(fileBaseName(file.name), used)
                val pngName = "$base-figure.png"
                val projectName = "$base.gel.json"
                entries.add(ZipEntryData(pngName, result.png))
                entries.add(ZipEntryData(projectName, GelTypes.projectToJson(result.project).toString(2).toByteArray()))
                rows.add(successRow(file.name, result, pngName, projectName))
                ok++
            } catch (err: Exception) {
                val message = err.message ?: err.toString()
                errors.add(file.name to message)
                rows.add(failureRow(file.name, message))
                failed++
            }
        }

        entries.add(ZipEntryData("summary.csv", (rows.joinToString("\n") + "\n").toByteArray()))
        return GelBatchBuild(entries, ok, failed, skipped, errors, images.size)
    }

    /** 把批次结果落盘为 ZIP（调用方在协程中执行）。 */
    fun zipToBytes(build: GelBatchBuild): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (e in build.entries) {
                zip.putNextEntry(ZipEntry(e.name))
                zip.write(e.data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
