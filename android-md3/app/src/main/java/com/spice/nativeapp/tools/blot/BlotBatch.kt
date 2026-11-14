package com.spice.nativeapp.tools.blot

/*
 * Folder-style batch processing for western blots. Each image is decoded,
 * lanes and bands are detected with the interactive tool algorithms, and the
 * result is written as an annotated PNG plus a densitometry CSV. A summary
 * table and a failure log accompany the per-image files in one ZIP.
 * 1:1 port of main's src/lib/tools/blot/batch.ts (batch.worker.ts runs on a
 * caller coroutine on Android — see README "Native platform differences").
 */

import com.spice.nativeapp.tools.gel.GelBatch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val MAX_BLOT_BATCH_IMAGES = 40

class BlotBatchOptions(
    val defaultGroup: String,
    val csvLabels: CsvLabels,
    val onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
)

class ZipEntryData(val name: String, val data: ByteArray)

class BlotBatchBuild(
    val entries: List<ZipEntryData>,
    val processed: Int,
    val failed: Int,
    val failures: List<String>,
    val images: Int,
)

private fun csvCell(value: String?): String {
    if (value == null) return ""
    return if (Regex("[\",\r\n]").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value
}

private fun csvRow(values: List<String?>): String = values.joinToString(",") { csvCell(it) }

private fun uniqueStem(stem: String, used: MutableSet<String>): String {
    var name = stem
    var index = 2
    while (used.contains(name)) name = "${stem}-${index++}"
    used.add(name)
    return name
}

private fun errorText(error: Throwable): String = error.message ?: error.toString()

fun buildBlotBatch(
    files: List<Pair<String, ByteArray>>,
    options: BlotBatchOptions,
): BlotBatchBuild {
    val queue = files.take(MAX_BLOT_BATCH_IMAGES)
    val entries = mutableListOf<ZipEntryData>()
    val failures = mutableListOf<String>()
    val summary = mutableListOf<List<String?>>(listOf("image", "source", "lanes", "bands", "groups", "totalVolume", "controlMean", "groupMeans"))
    val used = mutableSetOf<String>()

    for ((index, pair) in queue.withIndex()) {
        val (fileName, bytes) = pair
        options.onProgress?.invoke(index, queue.size, fileName)
        try {
            val decoded = decodeImageFile(fileName, bytes)
            val image = loadBitmapFromDataUrl(decoded.dataUrl)
                ?: throw IllegalArgumentException("cannot decode image")
            val project = emptyProject().apply {
                createdAt = nowIso()
                imageName = decoded.name
                imageDataUrl = decoded.dataUrl
                sourceWidth = decoded.width
                sourceHeight = decoded.height
                title = decoded.name.replace(Regex("\\.[^.]+$"), "")
            }
            val processed = renderProcessed(project, image)
            val detected = detectLanes(processed.rawGray, processed.width, processed.height, project.crop)
            if (detected.isEmpty()) throw IllegalStateException("no lanes detected")
            val lanes = lanesFromDetection(detected, emptyList(), options.defaultGroup)
            for (lane in lanes) {
                lane.bands = bandsFromDetection(
                    detectBands(processed.rawGray, processed.width, processed.height, lane, project.crop),
                    lane.width,
                ).toMutableList()
            }
            val bandCount = lanes.sumOf { it.bands.size }
            if (bandCount == 0) throw IllegalStateException("no bands detected")
            project.lanes.addAll(lanes)

            val result = quantify(project, processed.rawGray, processed.width, processed.height)
            if (!result.hasData) throw IllegalStateException("no quantifiable bands")

            val canvas = annotatedCanvas(
                project, processed.canvas,
                AnnotationOptions(laneLabels = true, roi = true)
            )
            val stem = uniqueStem(GelBatch.fileBaseName(fileName, "blot"), used)
            val description = "$fileName; ${lanes.size} lanes; $bandCount bands; background ${project.backgroundMode}"
            val png = annotatedPng(canvas, project.exportDpi, description)
            val csv = resultsCsv(project, result, options.csvLabels)

            entries.add(ZipEntryData("${stem}_annotated.png", png))
            entries.add(ZipEntryData("${stem}_densitometry.csv", csv.toByteArray(Charsets.UTF_8)))
            val groupMeans = result.groups.joinToString("; ") { "${it.name}=${String.format("%.4f", it.mean)}" }
            summary.add(listOf(
                stem, fileName, lanes.size.toString(), bandCount.toString(),
                result.groups.size.toString(), result.totalVolume.toString(),
                result.controlMean.toString(), groupMeans
            ))
        } catch (error: Throwable) {
            failures.add("$fileName: ${errorText(error)}")
            summary.add(listOf(
                uniqueStem(GelBatch.fileBaseName(fileName, "blot"), used),
                fileName, "0", "0", "0", null, null, ""
            ))
        }
    }

    options.onProgress?.invoke(queue.size, queue.size, "")
    entries.add(ZipEntryData("summary.csv", (summary.joinToString("\r\n") { csvRow(it) } + "\r\n").toByteArray(Charsets.UTF_8)))
    entries.add(ZipEntryData(
        "failures.txt",
        (if (failures.isNotEmpty()) failures.joinToString("\n") + "\n" else "No failures.\n").toByteArray(Charsets.UTF_8)
    ))
    return BlotBatchBuild(
        entries = entries,
        processed = summary.size - 1 - failures.size,
        failed = failures.size,
        failures = failures,
        images = queue.size,
    )
}

/** 把批次结果打包为 ZIP 字节（STORE 由调用方写盘）。 */
fun blotBatchZipToBytes(build: BlotBatchBuild): ByteArray {
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
