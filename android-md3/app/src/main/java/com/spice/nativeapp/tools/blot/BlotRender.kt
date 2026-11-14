package com.spice.nativeapp.tools.blot

/*
 * Annotated raster output, SVG chart and the combined figure panel. The figure
 * is assembled as SVG so the same markup drives the live preview and the
 * exported vector file; PNG export rasterises the annotated blot bitmap.
 * 1:1 port of main's src/lib/tools/blot/render.ts.
 */

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// One measure across groups uses one bar hue; group identity is carried by the
// axis labels, so no categorical colour cycle or legend is needed.
private const val BAR = "#2f7f94"
private const val TEXT = "#1b2230"
private const val MUTED = "#68748a"
private const val GRID = "#dde3ec"

private fun parseColor(hex: String): Int = Color.parseColor(hex)

fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun fmt(value: Double, digits: Int = 4): String =
    if (!value.isFinite()) "0" else if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
        value.toLong().toString()
    } else value.toString()

private fun fmt(value: Double): String = fmt(value, 4)

class AnnotationOptions(
    val selectedLaneId: String? = null,
    val selectedBandId: String? = null,
    val laneLabels: Boolean? = null,
    val roi: Boolean? = null,
)

/** Annotated blot at native crop resolution, with a label strip below. */
fun annotatedCanvas(
    project: BlotProject,
    processed: Bitmap,
    options: AnnotationOptions = AnnotationOptions(),
): Bitmap {
    val crop = cropCanvas(processed, project.crop)
    val labelH = if (options.laneLabels == false) 0 else max(22, (crop.height * 0.07).roundToInt())
    val out = Bitmap.createBitmap(crop.width, crop.height + labelH, Bitmap.Config.ARGB_8888)
    val ctx = Canvas(out)
    ctx.drawColor(Color.WHITE)
    ctx.drawBitmap(crop, 0f, 0f, null)

    val sx = project.crop.left * processed.width
    val sy = project.crop.top * processed.height
    val scale = crop.width.toDouble() / max(1.0, processed.width * (1 - project.crop.left - project.crop.right))
    fun mapX(x: Double): Double = (x * processed.width - sx) * scale
    fun mapY(y: Double): Double = (y * processed.height - sy) * scale
    val line = max(1, (out.width / 900.0).roundToInt()).toFloat()
    val font = max(9.0, out.width / 110.0).roundToInt().toFloat()

    val visibleLeft = project.crop.left
    val visibleRight = 1 - project.crop.right
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.BLACK
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = font
        textAlign = Paint.Align.RIGHT
    }
    for (lane in project.lanes) {
        if (lane.x < visibleLeft || lane.x > visibleRight) continue
        val x0 = mapX(lane.x - lane.width / 2)
        val x1 = mapX(lane.x + lane.width / 2)
        val selected = options.selectedLaneId == lane.id
        paint.color = when {
            lane.excluded -> Color.argb((0.9 * 255).roundToInt(), 184, 67, 63)
            selected -> parseColor("#d97b1f")
            else -> Color.argb((0.85 * 255).roundToInt(), 31, 138, 158)
        }
        paint.strokeWidth = if (selected) line * 2 else line
        paint.style = Paint.Style.STROKE
        paint.pathEffect = if (lane.excluded) DashPathEffect(floatArrayOf(6f, 4f), 0f) else null
        ctx.drawLine(x0.toFloat(), 0f, x0.toFloat(), crop.height.toFloat(), paint)
        ctx.drawLine(x1.toFloat(), 0f, x1.toFloat(), crop.height.toFloat(), paint)
        paint.pathEffect = null
        if (options.roi != false) {
            for (band in lane.bands) {
                val bx0 = mapX(lane.x - band.width / 2)
                val bx1 = mapX(lane.x + band.width / 2)
                val by0 = mapY(band.y - band.height / 2)
                val by1 = mapY(band.y + band.height / 2)
                val active = options.selectedBandId == band.id
                val excluded = lane.excluded || band.excluded
                paint.color = when {
                    excluded -> Color.argb((0.95 * 255).roundToInt(), 184, 67, 63)
                    active -> parseColor("#d97b1f")
                    else -> Color.argb((0.95 * 255).roundToInt(), 63, 143, 79)
                }
                paint.strokeWidth = if (active) line * 2 else line
                paint.pathEffect = if (excluded) DashPathEffect(floatArrayOf(5f, 3f), 0f) else null
                ctx.drawRect(
                    bx0.toFloat(),
                    by0.toFloat(),
                    max(bx0 + 1, bx1).toFloat(),
                    max(by0 + 1, by1).toFloat(),
                    paint,
                )
                paint.pathEffect = null
            }
        }
        if (labelH > 0) {
            ctx.save()
            ctx.translate(((x0 + x1) / 2).toFloat(), (crop.height + 4).toFloat())
            ctx.rotate(-45f)
            textPaint.color = if (lane.excluded) parseColor("#b8433f") else parseColor(TEXT)
            ctx.drawText(lane.name.ifEmpty { "" }, 0f, 0f, textPaint)
            ctx.restore()
        }
    }
    return out
}

private fun niceMax(value: Double): Double {
    if (!(value > 0)) return 1.0
    val exp = floor(log10(value))
    val base = 10.0.pow(exp)
    val n = value / base
    val step = when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 5 -> 5.0
        else -> 10.0
    }
    return step * base
}

private class ChartBodyOptions(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val metricLabel: String,
    val title: String? = null,
    val emptyLabel: String,
    val nLabel: String,
    val noGroupLabel: String,
    val showTitle: Boolean? = null,
)

private fun chartBody(
    groups: List<GroupStat>,
    rows: List<BandMeasurement>,
    metric: String,
    opts: ChartBodyOptions,
): String {
    val parts = mutableListOf<String>()
    val x = opts.x
    val y = opts.y
    val width = opts.width
    val height = opts.height
    if (opts.title != null && opts.title.isNotEmpty() && opts.showTitle != false) {
        parts.add(
            "<text x=\"${fmt(x)}\" y=\"${fmt(y - 12)}\" font-family=\"monospace\" font-size=\"15\" font-weight=\"700\" fill=\"$TEXT\">${escapeXml(opts.title)}</text>"
        )
    }
    val plotTop = y + 18
    val plotBottom = y + height - 48
    val plotLeft = x + 58
    val plotRight = x + width - 16
    val plotW = max(20.0, plotRight - plotLeft)
    val plotH = max(20.0, plotBottom - plotTop)

    data class Entry(val group: GroupStat, val stats: GroupMetricStats)

    val data = groups.map { Entry(it, groupMetricStats(it, rows, metric)) }
    val allValues = data.flatMap { it.stats.values.toList() }
    val maxValue = allValues.maxOrNull() ?: 0.0
    val maxError = if (data.isNotEmpty()) data.maxOf { it.stats.mean + it.stats.sem } else 0.0
    val rawMax = max(maxValue, maxError)
    val yMax = if (rawMax > 0) niceMax(rawMax) else 1.0
    val tickDigits = when {
        yMax >= 10 -> 1
        yMax >= 1 -> 2
        else -> 4
    }
    parts.add("<line x1=\"${fmt(plotLeft)}\" y1=\"${fmt(plotBottom)}\" x2=\"${fmt(plotRight)}\" y2=\"${fmt(plotBottom)}\" stroke=\"$TEXT\" stroke-width=\"1.2\"/>")
    parts.add("<line x1=\"${fmt(plotLeft)}\" y1=\"${fmt(plotTop)}\" x2=\"${fmt(plotLeft)}\" y2=\"${fmt(plotBottom)}\" stroke=\"$TEXT\" stroke-width=\"1.2\"/>")
    val ticks = 4
    for (i in 0..ticks) {
        val value = (yMax * i) / ticks
        val ty = plotBottom - (plotH * i) / ticks
        parts.add("<line x1=\"${fmt(plotLeft)}\" y1=\"${fmt(ty)}\" x2=\"${fmt(plotRight)}\" y2=\"${fmt(ty)}\" stroke=\"$GRID\" stroke-width=\"1\"/>")
        parts.add("<text x=\"${fmt(plotLeft - 8)}\" y=\"${fmt(ty + 4)}\" text-anchor=\"end\" font-family=\"monospace\" font-size=\"11\" fill=\"$MUTED\">${fmt(value, tickDigits)}</text>")
    }
    parts.add(
        "<text transform=\"translate(${fmt(x + 14)},${fmt(plotTop + plotH / 2)}) rotate(-90)\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"12\" fill=\"$TEXT\">${escapeXml(opts.metricLabel)}</text>"
    )
    if (data.none { it.stats.values.isNotEmpty() }) {
        parts.add(
            "<text x=\"${fmt(x + width / 2)}\" y=\"${fmt(plotTop + plotH / 2)}\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"12\" fill=\"$MUTED\">${escapeXml(opts.emptyLabel)}</text>"
        )
        return parts.joinToString("")
    }
    val slot = plotW / max(1, data.size)
    val barW = min(slot * 0.5, 76.0)
    data.forEachIndexed { index, entry ->
        val cx = plotLeft + slot * (index + 0.5)
        val meanY = plotBottom - (entry.stats.mean / yMax) * plotH
        val zeroY = plotBottom
        val label = entry.group.name.ifEmpty { opts.noGroupLabel }
        val marks = mutableListOf<String>()
        marks.add(
            "<rect x=\"${fmt(cx - barW / 2)}\" y=\"${fmt(min(meanY, zeroY))}\" width=\"${fmt(barW)}\" height=\"${fmt(kotlin.math.abs(zeroY - meanY))}\" rx=\"2\" fill=\"$BAR\" fill-opacity=\"0.68\" stroke=\"$BAR\" stroke-width=\"1\"/>"
        )
        if (entry.stats.values.size > 1) {
            val semTop = plotBottom - ((entry.stats.mean + entry.stats.sem) / yMax) * plotH
            val semBottom = plotBottom - ((max(0.0, entry.stats.mean - entry.stats.sem)) / yMax) * plotH
            marks.add("<line x1=\"${fmt(cx)}\" y1=\"${fmt(semTop)}\" x2=\"${fmt(cx)}\" y2=\"${fmt(semBottom)}\" stroke=\"$TEXT\" stroke-width=\"1.4\"/>")
            marks.add("<line x1=\"${fmt(cx - 5)}\" y1=\"${fmt(semTop)}\" x2=\"${fmt(cx + 5)}\" y2=\"${fmt(semTop)}\" stroke=\"$TEXT\" stroke-width=\"1.4\"/>")
            marks.add("<line x1=\"${fmt(cx - 5)}\" y1=\"${fmt(semBottom)}\" x2=\"${fmt(cx + 5)}\" y2=\"${fmt(semBottom)}\" stroke=\"$TEXT\" stroke-width=\"1.4\"/>")
        }
        entry.stats.values.forEachIndexed { pointIndex, value ->
            val jitter = ((pointIndex % 5) - 2) * (barW / 10)
            val py = plotBottom - (value / yMax) * plotH
            marks.add("<circle cx=\"${fmt(cx + jitter)}\" cy=\"${fmt(py)}\" r=\"3\" fill=\"$TEXT\" fill-opacity=\"0.78\"/>")
        }
        marks.add("<text x=\"${fmt(cx)}\" y=\"${fmt(plotBottom + 18)}\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"11\" fill=\"$TEXT\">${escapeXml(label)}</text>")
        marks.add("<text x=\"${fmt(cx)}\" y=\"${fmt(plotBottom + 33)}\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"10\" fill=\"$MUTED\">${escapeXml(opts.nLabel)}=${entry.stats.values.size}</text>")
        val tooltip = "$label: ${fmt(entry.stats.mean, 3)} ± ${fmt(entry.stats.sem, 3)} (${opts.nLabel}=${entry.stats.values.size})"
        parts.add("<g><title>${escapeXml(tooltip)}</title>${marks.joinToString("")}</g>")
    }
    return parts.joinToString("")
}

class ChartOptions(
    val width: Int,
    val height: Int,
    val metricLabel: String,
    val title: String? = null,
    val emptyLabel: String,
    val nLabel: String,
    val noGroupLabel: String,
)

/** Standalone SVG chart for the results pane. */
fun chartSvg(result: QuantResult, project: BlotProject, opts: ChartOptions): String {
    val body = chartBody(
        result.groups, result.rows, project.chartMetric,
        ChartBodyOptions(
            x = 12.0,
            y = 34.0,
            width = (opts.width - 24).toDouble(),
            height = (opts.height - 46).toDouble(),
            metricLabel = opts.metricLabel,
            title = opts.title,
            emptyLabel = opts.emptyLabel,
            nLabel = opts.nLabel,
            noGroupLabel = opts.noGroupLabel,
        )
    )
    val aria = escapeXml(opts.title ?: opts.metricLabel)
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${opts.width}\" height=\"${opts.height}\" viewBox=\"0 0 ${opts.width} ${opts.height}\" role=\"img\" aria-label=\"$aria\"><rect width=\"${opts.width}\" height=\"${opts.height}\" fill=\"#ffffff\"/>$body</svg>"
}

class FigureOptions(
    val title: String,
    val chartTitle: String,
    val metricLabel: String,
    val emptyLabel: String,
    val nLabel: String,
    val noGroupLabel: String,
    val footer: String,
)

class PlacedImage(val x: Double, val y: Double, val width: Double, val height: Double)

/** Full figure panel: title, annotated blot with lane labels, chart, footer. */
fun figureSvg(
    project: BlotProject,
    processed: Bitmap,
    result: QuantResult,
    opts: FigureOptions,
): String {
    val crop = cropCanvas(processed, project.crop)
    val dataUrl = bitmapToDataUrl(crop)
    val width = 1200
    val height = 760
    val margin = 44
    val titleH = if (opts.title.trim().isNotEmpty()) 52 else 22
    val panelTop = margin + titleH
    val panelH = height - panelTop - 58
    val blotPanelW = ((width - margin * 2) * 0.54).roundToInt()
    val chartX = margin + blotPanelW + 26
    val chartW = width - margin - chartX
    val image = fitImage(crop.width.toDouble(), crop.height.toDouble(), margin.toDouble(), panelTop.toDouble(), blotPanelW.toDouble(), (panelH - 46).toDouble())
    val parts = mutableListOf<String>()

    parts.add("<rect width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>")
    if (opts.title.trim().isNotEmpty()) {
        parts.add(
            "<text x=\"${width / 2}\" y=\"${margin + 24}\" text-anchor=\"middle\" font-family=\"monospace\" font-size=\"24\" font-weight=\"700\" fill=\"$TEXT\">${escapeXml(opts.title)}</text>"
        )
    }
    parts.add(
        "<image x=\"${fmt(image.x)}\" y=\"${fmt(image.y)}\" width=\"${fmt(image.width)}\" height=\"${fmt(image.height)}\" href=\"$dataUrl\" preserveAspectRatio=\"none\"/>"
    )

    val sx = project.crop.left * processed.width
    val sy = project.crop.top * processed.height
    val cropW = max(1.0, processed.width * (1 - project.crop.left - project.crop.right))
    val cropH = max(1.0, processed.height * (1 - project.crop.top - project.crop.bottom))
    fun mapX(value: Double): Double = image.x + ((value * processed.width - sx) / cropW) * image.width
    fun mapY(value: Double): Double = image.y + ((value * processed.height - sy) / cropH) * image.height

    val visibleLeft = project.crop.left
    val visibleRight = 1 - project.crop.right
    val visibleTop = project.crop.top
    val visibleBottom = 1 - project.crop.bottom
    for (lane in project.lanes) {
        if (lane.x < visibleLeft || lane.x > visibleRight) continue
        val lx0 = mapX(lane.x - lane.width / 2)
        val lx1 = mapX(lane.x + lane.width / 2)
        val laneColor = if (lane.excluded) "#b8433f" else "#1f8a9e"
        val dash = if (lane.excluded) "6 4" else "none"
        parts.add("<line x1=\"${fmt(lx0)}\" y1=\"${fmt(image.y)}\" x2=\"${fmt(lx0)}\" y2=\"${fmt(image.y + image.height)}\" stroke=\"$laneColor\" stroke-width=\"1.2\" stroke-dasharray=\"$dash\" opacity=\"0.9\"/>")
        parts.add("<line x1=\"${fmt(lx1)}\" y1=\"${fmt(image.y)}\" x2=\"${fmt(lx1)}\" y2=\"${fmt(image.y + image.height)}\" stroke=\"$laneColor\" stroke-width=\"1.2\" stroke-dasharray=\"$dash\" opacity=\"0.9\"/>")
        if (project.showRoi) {
            for (band in lane.bands) {
                if (band.y < visibleTop || band.y > visibleBottom) continue
                val bx0 = mapX(lane.x - band.width / 2)
                val bx1 = mapX(lane.x + band.width / 2)
                val by0 = mapY(band.y - band.height / 2)
                val by1 = mapY(band.y + band.height / 2)
                val excluded = lane.excluded || band.excluded
                parts.add(
                    "<rect x=\"${fmt(bx0)}\" y=\"${fmt(by0)}\" width=\"${fmt(max(1.0, bx1 - bx0))}\" height=\"${fmt(max(1.0, by1 - by0))}\" fill=\"none\" stroke=\"${if (excluded) "#b8433f" else "#3f8f4f"}\" stroke-width=\"1.2\" stroke-dasharray=\"${if (excluded) "5 3" else "none"}\"/>"
                )
            }
        }
        if (project.showLaneLabels) {
            val label = lane.sample.ifEmpty { lane.name }
            parts.add(
                "<text transform=\"translate(${fmt((lx0 + lx1) / 2)},${fmt(image.y + image.height + 8)}) rotate(-45)\" text-anchor=\"end\" font-family=\"monospace\" font-size=\"12\" fill=\"${if (lane.excluded) "#b8433f" else TEXT}\">${escapeXml(label)}</text>"
            )
        }
    }

    parts.add(
        chartBody(
            result.groups, result.rows, project.chartMetric,
            ChartBodyOptions(
                x = chartX.toDouble(),
                y = (panelTop + 22).toDouble(),
                width = chartW.toDouble(),
                height = (panelH - 30).toDouble(),
                metricLabel = opts.metricLabel,
                title = opts.chartTitle,
                emptyLabel = opts.emptyLabel,
                nLabel = opts.nLabel,
                noGroupLabel = opts.noGroupLabel,
            )
        )
    )
    parts.add(
        "<text x=\"${width - margin}\" y=\"${height - 20}\" text-anchor=\"end\" font-family=\"monospace\" font-size=\"11\" fill=\"$MUTED\">${escapeXml(opts.footer)}</text>"
    )

    val aria = escapeXml(opts.title.ifEmpty { opts.chartTitle })
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" role=\"img\" aria-label=\"$aria\">${parts.joinToString("")}</svg>"
}

private fun fitImage(
    sourceW: Double,
    sourceH: Double,
    x: Double,
    y: Double,
    maxW: Double,
    maxH: Double,
): PlacedImage {
    val scale = min(maxW / max(1.0, sourceW), maxH / max(1.0, sourceH))
    val width = max(1.0, sourceW * scale)
    val height = max(1.0, sourceH * scale)
    return PlacedImage(x + (maxW - width) / 2, y + (maxH - height) / 2, width, height)
}

// --------------------------------------------------------------------- CSV

class CsvLabels(
    val lane: String,
    val band: String,
    val sample: String,
    val group: String,
    val included: String,
    val area: String,
    val mean: String,
    val background: String,
    val volume: String,
    val saturated: String,
    val normalized: String,
    val fold: String,
    val groupMean: String,
    val groupSd: String,
    val groupSem: String,
    val groupCi95: String,
    val groupP: String,
    val groupN: String,
    val metric: String,
)

/** JS String(area): integral doubles print without a trailing ".0". */
private fun jsNumber(value: Double): String =
    if (!value.isFinite()) "0" else if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
        value.toLong().toString()
    } else value.toString()



private fun csvCell(value: Any): String {
    val text = value.toString()
    return if (Regex("[\",\\n\\r]").containsMatchIn(text)) "\"${text.replace("\"", "\"\"")}\"" else text
}

fun resultsCsv(project: BlotProject, result: QuantResult, labels: CsvLabels): String {
    val header = listOf(
        labels.lane, labels.band, labels.sample, labels.group, labels.included, labels.area,
        labels.mean, labels.background, labels.volume, labels.saturated, labels.normalized,
        labels.fold, labels.groupMean, labels.groupSd, labels.groupSem, labels.groupCi95,
        labels.groupP, labels.groupN, labels.metric,
    )
    val lines = mutableListOf(header.joinToString(",") { csvCell(it) })
    for (row in result.rows) {
        val pValue = row.groupP
        val cells = listOf(
            row.laneName,
            (row.bandIndex + 1).toString(),
            row.sample,
            row.group,
            if (row.included) "1" else "0",
            jsNumber(row.area),
            fmt(row.meanSignal, 6),
            fmt(row.background, 6),
            fmt(row.netVolume, 6),
            if (row.saturated) "1" else "0",
            fmt(row.normalized, 6),
            fmt(row.fold, 6),
            fmt(row.groupMean, 6),
            fmt(row.groupSd, 6),
            fmt(row.groupSem, 6),
            if (row.groupN >= 2) fmt(row.groupCi95, 6) else "",
            when {
                pValue == null -> ""
                pValue > 0 && pValue < 0.0001 -> jsExponential3(pValue)
                else -> fmt(pValue, 6)
            },
            row.groupN.toString(),
            project.chartMetric,
        )
        lines.add(cells.joinToString(",") { csvCell(it) })
    }
    return "\uFEFF" + lines.joinToString("\r\n") + "\r\n"
}

/** JS toExponential(3): e.g. 1.234e-5 (exponent without leading zero). */
private fun jsExponential3(value: Double): String {
    val raw = String.format("%.3e", value)
    return raw.replace(Regex("e([+-])0(\\d)$"), "e$1$2")
}

// ------------------------------------------------------------ PNG metadata

private val CRC_TABLE: IntArray = IntArray(256).also { table ->
    for (n in 0 until 256) {
        var c = n
        for (k in 0 until 8) c = if ((c and 1) == 1) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1
        table[n] = c
    }
}

private fun crc32(bytes: ByteArray): Int {
    var c = -0x1
    for (b in bytes) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c xor -0x1
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    val length = data.size
    out[0] = ((length ushr 24) and 0xff).toByte()
    out[1] = ((length ushr 16) and 0xff).toByte()
    out[2] = ((length ushr 8) and 0xff).toByte()
    out[3] = (length and 0xff).toByte()
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    System.arraycopy(data, 0, out, 8, data.size)
    val crc = crc32(out.copyOfRange(4, 8 + data.size))
    out[8 + data.size] = ((crc ushr 24) and 0xff).toByte()
    out[9 + data.size] = ((crc ushr 16) and 0xff).toByte()
    out[10 + data.size] = ((crc ushr 8) and 0xff).toByte()
    out[11 + data.size] = (crc and 0xff).toByte()
    return out
}

/** Insert pHYs + iTXt Description chunks so the annotated PNG carries the
 *  requested print DPI and a textual description. */
fun annotatedPng(bitmap: Bitmap, dpi: Int, description: String): ByteArray {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    val png = stream.toByteArray()

    val ppm = (dpi / 0.0254).roundToInt()
    val phys = ByteArray(9)
    for (i in 0 until 4) phys[i] = ((ppm ushr (24 - 8 * i)) and 0xff).toByte()
    for (i in 0 until 4) phys[4 + i] = ((ppm ushr (24 - 8 * i)) and 0xff).toByte()
    phys[8] = 1.toByte()
    val chunk = pngChunk("pHYs", phys)

    val keyword = "Description".toByteArray(Charsets.UTF_8)
    val text = description.toByteArray(Charsets.UTF_8)
    val itxt = ByteArray(keyword.size + 5 + text.size)
    System.arraycopy(keyword, 0, itxt, 0, keyword.size)
    var p = keyword.size
    itxt[p++] = 0
    itxt[p++] = 0
    itxt[p++] = 0
    itxt[p++] = 0
    itxt[p++] = 0
    System.arraycopy(text, 0, itxt, p, text.size)
    val textChunk = pngChunk("iTXt", itxt)

    val ihdrEnd = 33
    val out = ByteArray(png.size + chunk.size + textChunk.size)
    System.arraycopy(png, 0, out, 0, ihdrEnd)
    System.arraycopy(chunk, 0, out, ihdrEnd, chunk.size)
    System.arraycopy(textChunk, 0, out, ihdrEnd + chunk.size, textChunk.size)
    System.arraycopy(png, ihdrEnd, out, ihdrEnd + chunk.size + textChunk.size, png.size - ihdrEnd)
    return out
}

/** Parse a PNG byte array back to a bitmap (figure preview rendering helper). */
fun pngBytesToBitmap(bytes: ByteArray): Bitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
