package com.spice.nativeapp.tools.blot

/*
 * Blot UI helpers: i18n shortcuts plus the Canvas (android.graphics) mirror of
 * the SVG chart body and figure panel, so the live previews match the exported
 * vector files geometry-for-geometry. 1:1 port of main's
 * src/lib/tools/blot/render.ts drawing primitives.
 */

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import com.spice.nativeapp.core.I18n
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun bt(zh: String): String = I18n.t(zh)

/** 命名占位符插值（main 用 {n}/{lanes}/{done}…，I18n.tf 只支持 {v1}..{v9}）。 */
internal fun bi(template: String, vararg pairs: Pair<String, Any?>): String {
    var out = I18n.t(template)
    pairs.forEach { (k, v) -> out = out.replace("{$k}", v?.toString() ?: "") }
    return out
}

private const val BAR = 0xFF2F7F94.toInt()
private const val TEXT = 0xFF1B2230.toInt()
private const val MUTED = 0xFF68748A.toInt()
private const val GRID = 0xFFDDE3EC.toInt()

private fun monoPaint(size: Float, color: Int, bold: Boolean = false): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        textSize = size
    }

private fun svgNumber(value: Double): String =
    if (!value.isFinite()) "0" else if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun niceMaxD(value: Double): Double {
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

class ChartDrawOptions(
    val metricLabel: String,
    val title: String? = null,
    val emptyLabel: String,
    val nLabel: String,
    val noGroupLabel: String,
    val showTitle: Boolean = true,
)

/** Canvas mirror of chartBody() in BlotRender.kt (same geometry, same digits). */
internal fun drawChartBody(
    canvas: Canvas,
    groups: List<GroupStat>,
    rows: List<BandMeasurement>,
    metric: String,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    opts: ChartDrawOptions,
) {
    val titlePaint = monoPaint(15f, TEXT, bold = true)
    val tickPaint = monoPaint(11f, MUTED)
    val axisTextPaint = monoPaint(12f, TEXT)
    val labelPaint = monoPaint(11f, TEXT)
    val nLabelPaint = monoPaint(10f, MUTED)
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BAR
        strokeWidth = 1f
        style = Paint.Style.FILL_AND_STROKE
    }
    val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR; style = Paint.Style.STROKE; strokeWidth = 1f }
    val whiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; strokeWidth = 1.4f }
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; alpha = (0.78 * 255).roundToInt() }
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID; strokeWidth = 1f }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; strokeWidth = 1.2f }

    if (opts.title != null && opts.title.isNotEmpty() && opts.showTitle) {
        canvas.drawText(opts.title, x.toFloat(), (y - 12).toFloat(), titlePaint)
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
    val yMax = if (rawMax > 0) niceMaxD(rawMax) else 1.0
    val tickDigits = when {
        yMax >= 10 -> 1
        yMax >= 1 -> 2
        else -> 4
    }

    canvas.drawLine(plotLeft.toFloat(), plotBottom.toFloat(), plotRight.toFloat(), plotBottom.toFloat(), axisPaint)
    canvas.drawLine(plotLeft.toFloat(), plotTop.toFloat(), plotLeft.toFloat(), plotBottom.toFloat(), axisPaint)
    val ticks = 4
    tickPaint.textAlign = Paint.Align.RIGHT
    for (i in 0..ticks) {
        val value = (yMax * i) / ticks
        val ty = plotBottom - (plotH * i) / ticks
        canvas.drawLine(plotLeft.toFloat(), ty.toFloat(), plotRight.toFloat(), ty.toFloat(), gridPaint)
        canvas.drawText(String.format("%.${tickDigits}f", value), (plotLeft - 8).toFloat(), (ty + 4).toFloat(), tickPaint)
    }
    tickPaint.textAlign = Paint.Align.LEFT
    // Y-axis label rotated -90 about its anchor.
    canvas.save()
    canvas.translate((x + 14).toFloat(), (plotTop + plotH / 2).toFloat())
    canvas.rotate(-90f)
    val ylabelPaint = monoPaint(12f, TEXT).apply { textAlign = Paint.Align.CENTER }
    canvas.drawText(opts.metricLabel, 0f, 0f, ylabelPaint)
    canvas.restore()

    if (data.none { it.stats.values.isNotEmpty() }) {
        val emptyPaint = monoPaint(12f, MUTED).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(opts.emptyLabel, (x + width / 2).toFloat(), (plotTop + plotH / 2).toFloat(), emptyPaint)
        return
    }

    val slot = plotW / max(1, data.size)
    val barW = min(slot * 0.5, 76.0)
    labelPaint.textAlign = Paint.Align.CENTER
    nLabelPaint.textAlign = Paint.Align.CENTER
    data.forEachIndexed { index, entry ->
        val cx = plotLeft + slot * (index + 0.5)
        val meanY = plotBottom - (entry.stats.mean / yMax) * plotH
        val zeroY = plotBottom
        val label = entry.group.name.ifEmpty { opts.noGroupLabel }
        val top = min(meanY, zeroY)
        val barHeight = abs(zeroY - meanY)
        barPaint.alpha = (0.68 * 255).roundToInt()
        canvas.drawRoundRect(
            (cx - barW / 2).toFloat(), top.toFloat(), (cx + barW / 2).toFloat(), (top + barHeight).toFloat(),
            2f, 2f, barPaint,
        )
        barPaint.alpha = 255
        canvas.drawRoundRect(
            (cx - barW / 2).toFloat(), top.toFloat(), (cx + barW / 2).toFloat(), (top + barHeight).toFloat(),
            2f, 2f, barStroke,
        )
        if (entry.stats.values.size > 1) {
            val semTop = plotBottom - ((entry.stats.mean + entry.stats.sem) / yMax) * plotH
            val semBottom = plotBottom - (max(0.0, entry.stats.mean - entry.stats.sem) / yMax) * plotH
            canvas.drawLine(cx.toFloat(), semTop.toFloat(), cx.toFloat(), semBottom.toFloat(), whiskerPaint)
            canvas.drawLine((cx - 5).toFloat(), semTop.toFloat(), (cx + 5).toFloat(), semTop.toFloat(), whiskerPaint)
            canvas.drawLine((cx - 5).toFloat(), semBottom.toFloat(), (cx + 5).toFloat(), semBottom.toFloat(), whiskerPaint)
        }
        entry.stats.values.forEachIndexed { pointIndex, value ->
            val jitter = ((pointIndex % 5) - 2) * (barW / 10)
            val py = plotBottom - (value / yMax) * plotH
            canvas.drawCircle((cx + jitter).toFloat(), py.toFloat(), 3f, dotPaint)
        }
        canvas.drawText(label, cx.toFloat(), (plotBottom + 18).toFloat(), labelPaint)
        canvas.drawText("${opts.nLabel}=${entry.stats.values.size}", cx.toFloat(), (plotBottom + 33).toFloat(), nLabelPaint)
    }
    labelPaint.textAlign = Paint.Align.LEFT
    nLabelPaint.textAlign = Paint.Align.LEFT
}

/** Standalone chart bitmap at the same 560×380 size as chartSvg(). */
internal fun chartBitmap(result: QuantResult, project: BlotProject, opts: ChartOptions): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(opts.width, opts.height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)
    drawChartBody(
        canvas, result.groups, result.rows, project.chartMetric,
        x = 12.0, y = 34.0,
        width = (opts.width - 24).toDouble(),
        height = (opts.height - 46).toDouble(),
        opts = ChartDrawOptions(
            metricLabel = opts.metricLabel,
            title = opts.title,
            emptyLabel = opts.emptyLabel,
            nLabel = opts.nLabel,
            noGroupLabel = opts.noGroupLabel,
        ),
    )
    return bmp
}

/** Canvas mirror of figureSvg(): title, annotated blot, lanes, chart, footer. */
internal fun drawFigurePanel(
    canvas: Canvas,
    project: BlotProject,
    processed: android.graphics.Bitmap,
    result: QuantResult,
    opts: FigureOptions,
) {
    val width = 1200
    val height = 760
    val margin = 44
    canvas.drawColor(Color.WHITE)
    val titleH = if (opts.title.trim().isNotEmpty()) 52 else 22
    val panelTop = margin + titleH
    val panelH = height - panelTop - 58
    val blotPanelW = ((width - margin * 2) * 0.54).roundToInt()
    val chartX = margin + blotPanelW + 26
    val chartW = width - margin - chartX

    if (opts.title.trim().isNotEmpty()) {
        val titlePaint = monoPaint(24f, TEXT, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(opts.title, width / 2f, (margin + 24).toFloat(), titlePaint)
    }

    // Fitted blot image
    val crop = cropCanvas(processed, project.crop)
    val maxW = blotPanelW.toDouble()
    val maxH = (panelH - 46).toDouble()
    val scale = min(maxW / max(1.0, crop.width.toDouble()), maxH / max(1.0, crop.height.toDouble()))
    val imgW = max(1.0, crop.width * scale)
    val imgH = max(1.0, crop.height * scale)
    val imgX = margin + (maxW - imgW) / 2
    val imgY = panelTop + (maxH - imgH) / 2
    val dst = android.graphics.RectF(imgX.toFloat(), imgY.toFloat(), (imgX + imgW).toFloat(), (imgY + imgH).toFloat())
    canvas.drawBitmap(crop, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

    val sx = project.crop.left * processed.width
    val sy = project.crop.top * processed.height
    val cropW = max(1.0, processed.width * (1 - project.crop.left - project.crop.right))
    val cropH = max(1.0, processed.height * (1 - project.crop.top - project.crop.bottom))
    fun mapX(value: Double): Double = imgX + ((value * processed.width - sx) / cropW) * imgW
    fun mapY(value: Double): Double = imgY + ((value * processed.height - sy) / cropH) * imgH

    val visibleLeft = project.crop.left
    val visibleRight = 1 - project.crop.right
    val visibleTop = project.crop.top
    val visibleBottom = 1 - project.crop.bottom
    val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F8A9E.toInt()
        strokeWidth = 1.2f
        alpha = (0.9 * 255).roundToInt()
    }
    val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    val labelPaint = monoPaint(12f, TEXT)
    for (lane in project.lanes) {
        if (lane.x < visibleLeft || lane.x > visibleRight) continue
        val lx0 = mapX(lane.x - lane.width / 2)
        val lx1 = mapX(lane.x + lane.width / 2)
        lanePaint.color = if (lane.excluded) 0xFFB8433F.toInt() else 0xFF1F8A9E.toInt()
        lanePaint.pathEffect = if (lane.excluded) DashPathEffect(floatArrayOf(6f, 4f), 0f) else null
        canvas.drawLine(lx0.toFloat(), imgY.toFloat(), lx0.toFloat(), (imgY + imgH).toFloat(), lanePaint)
        canvas.drawLine(lx1.toFloat(), imgY.toFloat(), lx1.toFloat(), (imgY + imgH).toFloat(), lanePaint)
        lanePaint.pathEffect = null
        if (project.showRoi) {
            for (band in lane.bands) {
                if (band.y < visibleTop || band.y > visibleBottom) continue
                val bx0 = mapX(lane.x - band.width / 2)
                val bx1 = mapX(lane.x + band.width / 2)
                val by0 = mapY(band.y - band.height / 2)
                val by1 = mapY(band.y + band.height / 2)
                val excluded = lane.excluded || band.excluded
                roiPaint.color = if (excluded) 0xFFB8433F.toInt() else 0xFF3F8F4F.toInt()
                roiPaint.pathEffect = if (excluded) DashPathEffect(floatArrayOf(5f, 3f), 0f) else null
                canvas.drawRect(
                    bx0.toFloat(), by0.toFloat(),
                    max(bx0 + 1, bx1).toFloat(), max(by0 + 1, by1).toFloat(),
                    roiPaint,
                )
                roiPaint.pathEffect = null
            }
        }
        if (project.showLaneLabels) {
            val label = lane.sample.ifEmpty { lane.name }
            labelPaint.color = if (lane.excluded) 0xFFB8433F.toInt() else TEXT
            canvas.save()
            canvas.translate(((lx0 + lx1) / 2).toFloat(), (imgY + imgH + 8).toFloat())
            canvas.rotate(-45f)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, 0f, 0f, labelPaint)
            canvas.restore()
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }

    drawChartBody(
        canvas, result.groups, result.rows, project.chartMetric,
        x = chartX.toDouble(),
        y = (panelTop + 22).toDouble(),
        width = chartW.toDouble(),
        height = (panelH - 30).toDouble(),
        opts = ChartDrawOptions(
            metricLabel = opts.metricLabel,
            title = opts.chartTitle,
            emptyLabel = opts.emptyLabel,
            nLabel = opts.nLabel,
            noGroupLabel = opts.noGroupLabel,
        ),
    )
    val footerPaint = monoPaint(11f, MUTED).apply { textAlign = Paint.Align.RIGHT }
    canvas.drawText(opts.footer, (width - margin).toFloat(), (height - 20).toFloat(), footerPaint)
    footerPaint.textAlign = Paint.Align.LEFT
}

/** Figure panel bitmap at the same 1200×760 size as figureSvg(). */
internal fun figureBitmap(
    project: BlotProject,
    processed: android.graphics.Bitmap,
    result: QuantResult,
    opts: FigureOptions,
): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(1200, 760, android.graphics.Bitmap.Config.ARGB_8888)
    drawFigurePanel(Canvas(bmp), project, processed, result, opts)
    return bmp
}
