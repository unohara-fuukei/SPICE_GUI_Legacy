package com.spice.nativeapp.tools.qpcr

/*
 * Android Canvas bitmap twins of the SVG charts in QpcrChart.kt. The geometry,
 * colours, tick layout and label truncation mirror barChartSvg/heatmapSvg value
 * for value; the bitmap is used for the on-screen preview and for the PNG
 * export (main rasterizes the SVG at scale 2 — the same 2x scale is applied
 * here, documented as a platform difference in the README).
 */

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val FONT_BODY: Typeface = Typeface.MONOSPACE

private fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF333333.toInt(), align: Paint.Align = Paint.Align.LEFT): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(FONT_BODY, if (bold) Typeface.BOLD else Typeface.NORMAL)
        textSize = size
        this.color = color
        textAlign = align
    }

private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
private fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.style = Paint.Style.STROKE
    strokeWidth = width
}

private fun parseCssColor(color: String): Int {
    if (color.startsWith("#")) {
        val hex = if (color.length == 7) color else "#000000"
        return 0xFF000000.toInt() or hex.substring(1, 3).toInt(16).shl(16) or
            hex.substring(3, 5).toInt(16).shl(8) or hex.substring(5, 7).toInt(16)
    }
    // rgb(r,g,b)
    val m = Regex("rgb\\((\\d+),(\\d+),(\\d+)\\)").find(color) ?: return 0xFF000000.toInt()
    val (r, g, b) = m.destructured
    return 0xFF000000.toInt() or r.toInt().shl(16) or g.toInt().shl(8) or b.toInt()
}

private fun blendOver(base: Int, overlay: Int, alpha: Double): Int {
    fun channel(shift: Int): Int {
        val sv = (base shr shift) and 0xFF
        val ov = (overlay shr shift) and 0xFF
        return ((sv + (ov - sv) * alpha).roundToInt()).coerceIn(0, 255)
    }
    val r = channel(16); val g = channel(8); val b = channel(0)
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

private fun truncateB(value: String, maxLen: Int): String =
    if (value.length > maxLen) value.take(maxLen - 1) + "…" else value

/** Canvas twin of barChartSvg; scale 1 for preview, 2 for the exported PNG. */
fun barChartBitmap(data: ChartData, labels: ChartLabels, scale: Float): Bitmap {
    val width = 860
    val height = 440
    val marginLeft = 78
    val marginRight = 24
    val marginBottom = 88
    val titleY = 24f
    val legendStartY = 44
    val legendLineHeight = 16
    val colors = chartColorsPublic(data.series.size)

    // Legend layout — same greedy wrap as the SVG builder.
    val legendLines = ArrayList<MutableList<Pair<String, String>>>(listOf(mutableListOf()))
    var cursor = marginLeft.toDouble()
    data.series.forEachIndexed { index, series ->
        val itemWidth = series.name.length * 6.4 + 28
        if (cursor + itemWidth > width - marginRight && legendLines.last().isNotEmpty()) {
            legendLines.add(mutableListOf())
            cursor = marginLeft.toDouble()
        }
        legendLines.last().add(series.name to colors[index])
        cursor += itemWidth
    }
    val plotTop = legendStartY + legendLines.size * legendLineHeight + 12
    val plotBottom = height - marginBottom
    val plotLeft = marginLeft
    val plotRight = width - marginRight
    val plotWidth = plotRight - plotLeft
    val plotHeight = plotBottom - plotTop

    var yMin = 0.0
    var yMax = 1.0
    var ticks = listOf<Double>()
    if (data.log) {
        val positive = ArrayList<Double>()
        for (series in data.series) for (bar in series.bars) {
            if (bar == null) continue
            val candidates = listOf(bar.value) + bar.points
            for (value in candidates) if (value.isFinite() && value > 0) positive.add(value)
            if (bar.logSem !== null && bar.value > 0) {
                positive.add(bar.value * 10.0.pow(bar.logSem))
                if (bar.value / 10.0.pow(bar.logSem) > 0) positive.add(bar.value / 10.0.pow(bar.logSem))
            }
        }
        var minLog = -1.0
        var maxLog = 1.0
        if (positive.isNotEmpty()) {
            minLog = floor(log10(positive.min()))
            maxLog = ceil(log10(positive.max()))
        }
        if (maxLog - minLog < 1) maxLog = minLog + 1
        yMin = minLog
        yMax = maxLog
        val span = maxLog - minLog
        val step = max(1.0, ceil(span / 7))
        val out = ArrayList<Double>()
        var v = minLog
        while (v <= maxLog + 1e-9) { out.add(v); v += step }
        if (out.last() != maxLog) out.add(maxLog)
        ticks = out
    } else {
        val linear = ArrayList<Double>()
        for (series in data.series) for (bar in series.bars) {
            if (bar == null) continue
            val candidates = listOf(bar.value) + bar.points
            for (value in candidates) if (value.isFinite()) linear.add(value)
            if (bar.sem !== null && bar.sem.isFinite()) linear.add(bar.value + bar.sem)
        }
        val maxValue = if (linear.isNotEmpty()) linear.max() else 0.0
        yMax = if (maxValue > 0) maxValue * 1.08 else 1.0
        val step = niceStepPublic(yMax / 5)
        yMax = ceil(yMax / step) * step
        val out = ArrayList<Double>()
        var v = 0.0
        while (v <= yMax + 1e-9) { out.add(v); v += step }
        ticks = out
    }

    fun yOf(value: Double): Float {
        val ratio = if (data.log) {
            if (value <= 0) 0.0 else ((log10(value) - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
        } else (value / yMax).coerceIn(0.0, 1.0)
        return (plotBottom - ratio * plotHeight).toFloat()
    }

    val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(scale, scale)

    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint(0xFFFFFFFF.toInt()))
    val titlePaint = paint(15f, bold = true, color = 0xFF151A24.toInt())
    canvas.drawText(labels.title, 24f, titleY, titlePaint)

    legendLines.forEachIndexed { lineIndex, line ->
        var x = marginLeft.toFloat()
        val y = (legendStartY + lineIndex * legendLineHeight).toFloat()
        for ((name, color) in line) {
            canvas.drawRect(x, y - 9f, x + 10f, y + 1f, fillPaint(parseCssColor(color)))
            canvas.drawRect(x, y - 9f, x + 10f, y + 1f, strokePaint(0xFF333333.toInt(), 0.6f))
            canvas.drawText(truncateB(name, 26), x + 15f, y, paint(10f))
            x += (name.length * 6.4 + 28).toFloat()
        }
    }

    canvas.drawRect(plotLeft.toFloat(), plotTop.toFloat(), plotRight.toFloat(), plotBottom.toFloat(), fillPaint(0xFFFBFCFE.toInt()))
    canvas.drawRect(plotLeft.toFloat(), plotTop.toFloat(), plotRight.toFloat(), plotBottom.toFloat(), strokePaint(0xFFC9CFDA.toInt(), 1f))
    val gridPaint = strokePaint(0xFFE4E8F0.toInt(), 1f)
    val tickPaint = paint(9f, color = 0xFF4A5262.toInt(), align = Paint.Align.RIGHT)
    for (tick in ticks) {
        val y = if (data.log) yOf(10.0.pow(tick)) else yOf(tick)
        if (y < plotTop - 0.5f || y > plotBottom + 0.5f) continue
        canvas.drawLine(plotLeft.toFloat(), y, plotRight.toFloat(), y, gridPaint)
        val label = if (data.log) "1e${tick.roundToInt()}" else formatTickPublic(tick)
        canvas.drawText(label, (plotLeft - 8).toFloat(), y + 3f, tickPaint)
    }
    val axisPaint = strokePaint(0xFF333B4A.toInt(), 1.2f)
    canvas.drawLine(plotLeft.toFloat(), plotBottom.toFloat(), plotRight.toFloat(), plotBottom.toFloat(), axisPaint)
    canvas.drawLine(plotLeft.toFloat(), plotTop.toFloat(), plotLeft.toFloat(), plotBottom.toFloat(), axisPaint)

    val categoryCount = data.categories.size
    val bandWidth = if (categoryCount > 0) plotWidth.toDouble() / categoryCount else plotWidth.toDouble()
    val groupWidth = bandWidth * 0.78
    val barWidth = groupWidth / max(1, data.series.size)
    val rotateLabels = categoryCount > 6 || data.categories.any { it.length > 10 }

    val barStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f }
    val errorPaint = strokePaint(0xFF222222.toInt(), 1f)
    val pointFill = fillPaint(0xFFFFFFFF.toInt())

    data.categories.forEachIndexed { categoryIndex, category ->
        val groupStart = plotLeft + categoryIndex * bandWidth + (bandWidth - groupWidth) / 2
        data.series.forEachIndexed { seriesIndex, series ->
            val bar = series.bars.getOrNull(categoryIndex) ?: return@forEachIndexed
            if (!bar.value.isFinite() || (data.log && bar.value <= 0)) return@forEachIndexed
            val colorInt = parseCssColor(colors[seriesIndex])
            val x = (groupStart + seriesIndex * barWidth).toFloat()
            val center = (x + barWidth / 2).toFloat()
            val top = yOf(bar.value)
            val barHeight = max(0f, plotBottom - top).toFloat()
            val barPaint = fillPaint(blendOver(0xFFFFFFFF.toInt(), colorInt, 0.82))
            canvas.drawRect((x + barWidth * 0.08).toFloat(), top, (x + barWidth * 0.92).toFloat(), top + barHeight, barPaint)
            barStroke.color = colorInt
            canvas.drawRect((x + barWidth * 0.08).toFloat(), top, (x + barWidth * 0.92).toFloat(), top + barHeight, barStroke)

            if (data.log) {
                val logSem = bar.logSem
                if (logSem !== null && logSem.isFinite() && logSem > 0) {
                    val yHigh = max(plotTop.toFloat(), yOf(bar.value * 10.0.pow(logSem)))
                    val yLow = min(plotBottom.toFloat(), yOf(bar.value / 10.0.pow(logSem)))
                    val cap = min(10.0, barWidth * 0.3).toFloat()
                    canvas.drawLine(center, yHigh, center, yLow, errorPaint)
                    canvas.drawLine(center - cap, yHigh, center + cap, yHigh, errorPaint)
                    canvas.drawLine(center - cap, yLow, center + cap, yLow, errorPaint)
                }
            } else if (bar.sem !== null && bar.sem.isFinite() && bar.sem > 0) {
                val yHigh = yOf(bar.value + bar.sem)
                val yLow = yOf(max(0.0, bar.value - bar.sem))
                val cap = min(10.0, barWidth * 0.3).toFloat()
                canvas.drawLine(center, yHigh, center, yLow, errorPaint)
                canvas.drawLine(center - cap, yHigh, center + cap, yHigh, errorPaint)
                canvas.drawLine(center - cap, yLow, center + cap, yLow, errorPaint)
            }

            val points = bar.points.filter { it.isFinite() && (!data.log || it > 0) }
            points.forEachIndexed { pointIndex, point ->
                val offset = ((pointIndex - (points.size - 1) / 2.0) / max(1, points.size) * barWidth * 0.7).toFloat()
                canvas.drawCircle(center + offset, yOf(point), 2.4f, pointFill)
                canvas.drawCircle(center + offset, yOf(point), 2.4f, strokePaint(colorInt, 1.2f))
            }
        }

        val labelX = (plotLeft + categoryIndex * bandWidth + bandWidth / 2).toFloat()
        val labelPaint = paint(9f, color = 0xFF333B4A.toInt(), align = if (rotateLabels) Paint.Align.RIGHT else Paint.Align.CENTER)
        if (rotateLabels) {
            val pivotY = plotBottom + 12f
            canvas.save()
            canvas.rotate(-32f, labelX, pivotY)
            canvas.drawText(truncateB(category, 20), labelX, pivotY, labelPaint)
            canvas.restore()
        } else {
            canvas.drawText(truncateB(category, 20), labelX, (plotBottom + 18).toFloat(), paint(10f, color = 0xFF333B4A.toInt(), align = Paint.Align.CENTER))
        }
    }

    if (categoryCount == 0) {
        canvas.drawText("-", (plotLeft + plotWidth / 2).toFloat(), (plotTop + plotHeight / 2).toFloat(), paint(11f, color = 0xFF7A8394.toInt(), align = Paint.Align.CENTER))
    }

    val axisLabelY = plotTop + plotHeight / 2.0
    canvas.save()
    canvas.rotate(-90f, 18f, axisLabelY.toFloat())
    canvas.drawText(labels.yLabel, 18f, axisLabelY.toFloat(), paint(10f, color = 0xFF333B4A.toInt(), align = Paint.Align.CENTER))
    canvas.restore()

    canvas.restore()
    return bitmap
}

/** Canvas twin of heatmapSvg. */
fun heatmapBitmap(rows: List<HeatmapRow>, columns: List<String>, labels: HeatmapLabels, scale: Float): Bitmap {
    val left = 150
    val top = 66
    val cellWidth = 82
    val cellHeight = 24
    val right = 24
    val bottom = 66
    val width = left + max(1, columns.size) * cellWidth + right
    val height = top + max(1, rows.size) * cellHeight + bottom

    val allValues = ArrayList<Double>()
    for (row in rows) for (value in row.values) if (value !== null && value.isFinite()) allValues.add(value)
    var min = if (allValues.isNotEmpty()) allValues.min() else 0.0
    var max = if (allValues.isNotEmpty()) allValues.max() else 1.0
    if (max - min < 1e-6) { min -= 0.5; max += 0.5 }

    val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(scale, scale)

    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint(0xFFFFFFFF.toInt()))
    canvas.drawText(labels.title, 24f, 24f, paint(14f, bold = true, color = 0xFF151A24.toInt()))

    columns.forEachIndexed { columnIndex, column ->
        val x = (left + columnIndex * cellWidth + cellWidth / 2.0).toFloat()
        val y = (top - 10).toFloat()
        canvas.save()
        canvas.rotate(-32f, x, y)
        canvas.drawText(truncateB(column, 14), x, y, paint(9f))
        canvas.restore()
    }

    val cellBorder = strokePaint(0xFFFFFFFF.toInt(), 1f)
    rows.forEachIndexed { rowIndex, row ->
        val y = top + rowIndex * cellHeight
        val label = if (row.sub.isNotEmpty()) "${row.label} · ${row.sub}" else row.label
        canvas.drawText(truncateB(label, 24), (left - 8).toFloat(), (y + cellHeight / 2.0 + 3).toFloat(), paint(9f, color = 0xFF333B4A.toInt(), align = Paint.Align.RIGHT))
        columns.forEachIndexed { columnIndex, _ ->
            val value = row.values.getOrNull(columnIndex)
            val x = left + columnIndex * cellWidth
            var fill = 0xFFEEF1F6.toInt()
            var text = ""
            var t = 0.5
            if (value !== null && value.isFinite()) {
                t = (value - min) / (max - min)
                fill = parseCssColor(heatColorPublic(t))
                text = String.format(java.util.Locale.US, "%.2f", value)
            }
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellWidth - 1).toFloat(), (y + cellHeight - 1).toFloat(), fillPaint(fill))
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + cellWidth - 1).toFloat(), (y + cellHeight - 1).toFloat(), cellBorder)
            val textColor = if (t > 0.28 && t < 0.72) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawText(text, (x + (cellWidth - 1) / 2.0).toFloat(), (y + cellHeight / 2.0 + 3).toFloat(), paint(8.5f, color = textColor, align = Paint.Align.CENTER))
        }
    }

    val legendY = height - 36
    val steps = 150
    for (i in 0 until steps) {
        val t = i.toDouble() / (steps - 1)
        canvas.drawRect(
            (left + i).toFloat(), legendY.toFloat(), (left + i + 1).toFloat(), (legendY + 9).toFloat(),
            fillPaint(parseCssColor(heatColorPublic(t))),
        )
    }
    canvas.drawRect(left.toFloat(), legendY.toFloat(), (left + 150).toFloat(), (legendY + 9).toFloat(), strokePaint(0xFFC9CFDA.toInt(), 0.7f))
    canvas.drawText("${labels.low} ${formatTickPublic(min)}", left.toFloat(), (legendY + 22).toFloat(), paint(9f, color = 0xFF4A5262.toInt()))
    canvas.drawText("${formatTickPublic(max)} ${labels.high}", (left + 150).toFloat(), (legendY + 22).toFloat(), paint(9f, color = 0xFF4A5262.toInt(), align = Paint.Align.RIGHT))

    canvas.restore()
    return bitmap
}
