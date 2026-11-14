package com.spice.nativeapp.tools.qpcr

/*
 * Dependency-free SVG chart builders. 1:1 port of main's
 * src/lib/tools/qpcr/chart.ts: a grouped bar chart with individual points and
 * SEM error bars, plus an optional Delta Ct heatmap. The generated SVG is used
 * inline and exported as SVG or rasterized to PNG.
 */

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Public wrappers so the Canvas twins in QpcrRaster.kt share the exact same
// palette / tick / step / colour-ramp logic as the SVG builders.
fun chartColorsPublic(count: Int): List<String> = chartColors(count)
fun formatTickPublic(value: Double): String = formatTick(value)
fun niceStepPublic(raw: Double): Double = niceStep(raw)
fun heatColorPublic(t: Double): String = heatColor(t)

class ChartBar(
    val value: Double,
    val points: List<Double>,
    val sem: Double?,
    val logSem: Double? = null,
    val n: Int,
)

class ChartSeries(
    val name: String,
    val bars: List<ChartBar?>,
)

class ChartData(
    val categories: List<String>,
    val series: List<ChartSeries>,
    val log: Boolean,
)

class SvgDoc(
    val svg: String,
    val width: Int,
    val height: Int,
)

class ChartLabels(
    val title: String,
    val yLabel: String,
)

private val PALETTE = listOf(
    "#2f6fed",
    "#e8892b",
    "#2f9e5f",
    "#d64545",
    "#8a5cd6",
    "#2aa7b8",
    "#b8860b",
    "#777777",
)

private const val FONT = "'SFMono-Regular','JetBrains Mono','Fira Code','Menlo',monospace"

private fun esc(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun truncate(value: String, max: Int): String =
    if (value.length > max) value.take(max - 1) + "…" else value

private fun formatTick(value: Double): String {
    if (abs(value) >= 10000 || (abs(value) > 0 && abs(value) < 0.01)) {
        return String.format(java.util.Locale.US, "%.1e", value).replace("e-0", "e-").replace("e+0", "e+")
    }
    if (abs(value - value.roundToInt()) < 1e-9) return value.roundToInt().toString()
    return String.format(java.util.Locale.US, "%.3g", value)
}

private fun niceStep(raw: Double): Double {
    if (!raw.isFinite() || raw <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val factor = when {
        normalized <= 1 -> 1.0
        normalized <= 2 -> 2.0
        normalized <= 5 -> 5.0
        else -> 10.0
    }
    return factor * magnitude
}

private fun chartColors(count: Int): List<String> =
    List(count) { i -> PALETTE[i % PALETTE.size] }

/** Grouped bar chart with individual points and SEM error bars (1:1 barChartSvg). */
fun barChartSvg(data: ChartData, labels: ChartLabels): SvgDoc {
    val width = 860
    val height = 440
    val marginLeft = 78
    val marginRight = 24
    val marginBottom = 88
    val titleY = 24
    val legendStartY = 44
    val legendLineHeight = 16
    val colors = chartColors(data.series.size)

    val legendLines = ArrayList<MutableList<Pair<String, String>>>(listOf(mutableListOf()))
    var cursor = marginLeft.toDouble()
    val availableWidth = width - marginLeft - marginRight
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

    val linearValues = ArrayList<Double>()
    val logValues = ArrayList<Double>()
    for (series in data.series) {
        for (bar in series.bars) {
            if (bar == null) continue
            val candidates = listOf(bar.value) + bar.points
            for (value in candidates) {
                if (!value.isFinite()) continue
                if (data.log) {
                    if (value > 0) logValues.add(value)
                } else {
                    linearValues.add(value)
                }
            }
            if (bar.sem !== null && bar.sem.isFinite()) {
                if (!data.log) linearValues.add(bar.value + bar.sem)
            }
            if (data.log && bar.logSem !== null && bar.value > 0) {
                logValues.add(bar.value * 10.0.pow(bar.logSem))
                if (bar.value / 10.0.pow(bar.logSem) > 0) logValues.add(bar.value / 10.0.pow(bar.logSem))
            }
        }
    }

    var yMin = 0.0
    var yMax = 1.0
    var ticks = listOf<Double>()
    if (data.log) {
        val positive = logValues.filter { it > 0 }
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
        while (v <= maxLog + 1e-9) {
            out.add(v); v += step
        }
        if (out.last() != maxLog) out.add(maxLog)
        ticks = out
    } else {
        val maxValue = if (linearValues.isNotEmpty()) linearValues.max() else 0.0
        yMax = if (maxValue > 0) maxValue * 1.08 else 1.0
        val step = niceStep(yMax / 5)
        yMax = ceil(yMax / step) * step
        val out = ArrayList<Double>()
        var v = 0.0
        while (v <= yMax + 1e-9) {
            out.add(v); v += step
        }
        ticks = out
    }

    val yOf = { value: Double ->
        if (data.log) {
            if (value <= 0) plotBottom.toDouble()
            else {
                val ratio = (log10(value) - yMin) / (yMax - yMin)
                plotBottom - ratio.coerceIn(0.0, 1.0) * plotHeight
            }
        } else {
            val ratio = value / yMax
            plotBottom - ratio.coerceIn(0.0, 1.0) * plotHeight
        }
    }

    val parts = ArrayList<String>()
    parts.add("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" role=\"img\">")
    parts.add("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>")
    parts.add("<text x=\"24\" y=\"$titleY\" font-family=\"$FONT\" font-size=\"15\" font-weight=\"700\" fill=\"#151a24\">${esc(labels.title)}</text>")

    legendLines.forEachIndexed { lineIndex, line ->
        var x = marginLeft.toDouble()
        val y = legendStartY + lineIndex * legendLineHeight
        for ((name, color) in line) {
            parts.add("<rect x=\"$x\" y=\"${y - 9}\" width=\"10\" height=\"10\" fill=\"$color\" stroke=\"#333333\" stroke-width=\"0.6\"/>")
            parts.add("<text x=\"${x + 15}\" y=\"$y\" font-family=\"$FONT\" font-size=\"10\" fill=\"#333333\">${esc(truncate(name, 26))}</text>")
            x += name.length * 6.4 + 28
        }
    }

    // Plot frame and horizontal grid.
    parts.add("<rect x=\"$plotLeft\" y=\"$plotTop\" width=\"$plotWidth\" height=\"$plotHeight\" fill=\"#fbfcfe\" stroke=\"#c9cfda\" stroke-width=\"1\"/>")
    for (tick in ticks) {
        val y = if (data.log) yOf(10.0.pow(tick)) else yOf(tick)
        if (y < plotTop - 0.5 || y > plotBottom + 0.5) continue
        parts.add("<line x1=\"$plotLeft\" y1=\"$y\" x2=\"$plotRight\" y2=\"$y\" stroke=\"#e4e8f0\" stroke-width=\"1\"/>")
        val label = if (data.log) "1e${tick.roundToInt()}" else formatTick(tick)
        parts.add("<text x=\"${plotLeft - 8}\" y=\"${y + 3}\" text-anchor=\"end\" font-family=\"$FONT\" font-size=\"9\" fill=\"#4a5262\">${esc(label)}</text>")
    }
    parts.add("<line x1=\"$plotLeft\" y1=\"$plotBottom\" x2=\"$plotRight\" y2=\"$plotBottom\" stroke=\"#333b4a\" stroke-width=\"1.2\"/>")
    parts.add("<line x1=\"$plotLeft\" y1=\"$plotTop\" x2=\"$plotLeft\" y2=\"$plotBottom\" stroke=\"#333b4a\" stroke-width=\"1.2\"/>")

    val categoryCount = data.categories.size
    val bandWidth = if (categoryCount > 0) plotWidth.toDouble() / categoryCount else plotWidth.toDouble()
    val groupWidth = bandWidth * 0.78
    val barWidth = groupWidth / max(1, data.series.size)
    val rotateLabels = categoryCount > 6 || data.categories.any { it.length > 10 }

    data.categories.forEachIndexed { categoryIndex, category ->
        val groupStart = plotLeft + categoryIndex * bandWidth + (bandWidth - groupWidth) / 2
        data.series.forEachIndexed { seriesIndex, series ->
            val bar = series.bars[categoryIndex] ?: return@forEachIndexed
            if (!bar.value.isFinite() || (data.log && bar.value <= 0)) return@forEachIndexed
            val color = colors[seriesIndex]
            val x = groupStart + seriesIndex * barWidth
            val center = x + barWidth / 2
            val top = yOf(bar.value)
            val barPixelHeight = max(0.0, plotBottom - top)
            parts.add("<rect x=\"${x + barWidth * 0.08}\" y=\"$top\" width=\"${barWidth * 0.84}\" height=\"$barPixelHeight\" fill=\"$color\" fill-opacity=\"0.82\" stroke=\"$color\" stroke-width=\"0.8\"/>")

            if (data.log) {
                val logSem = bar.logSem
                if (logSem !== null && logSem.isFinite() && logSem > 0) {
                    val high = bar.value * 10.0.pow(logSem)
                    val low = bar.value / 10.0.pow(logSem)
                    val yHigh = max(plotTop.toDouble(), yOf(high))
                    val yLow = min(plotBottom.toDouble(), yOf(low))
                    val cap = min(10.0, barWidth * 0.3)
                    parts.add("<line x1=\"$center\" y1=\"$yHigh\" x2=\"$center\" y2=\"$yLow\" stroke=\"#222222\" stroke-width=\"1\"/>")
                    parts.add("<line x1=\"${center - cap}\" y1=\"$yHigh\" x2=\"${center + cap}\" y2=\"$yHigh\" stroke=\"#222222\" stroke-width=\"1\"/>")
                    parts.add("<line x1=\"${center - cap}\" y1=\"$yLow\" x2=\"${center + cap}\" y2=\"$yLow\" stroke=\"#222222\" stroke-width=\"1\"/>")
                }
            } else if (bar.sem !== null && bar.sem.isFinite() && bar.sem > 0) {
                val yHigh = yOf(bar.value + bar.sem)
                val yLow = yOf(max(0.0, bar.value - bar.sem))
                val cap = min(10.0, barWidth * 0.3)
                parts.add("<line x1=\"$center\" y1=\"$yHigh\" x2=\"$center\" y2=\"$yLow\" stroke=\"#222222\" stroke-width=\"1\"/>")
                parts.add("<line x1=\"${center - cap}\" y1=\"$yHigh\" x2=\"${center + cap}\" y2=\"$yHigh\" stroke=\"#222222\" stroke-width=\"1\"/>")
                parts.add("<line x1=\"${center - cap}\" y1=\"$yLow\" x2=\"${center + cap}\" y2=\"$yLow\" stroke=\"#222222\" stroke-width=\"1\"/>")
            }

            val points = bar.points.filter { it.isFinite() && (!data.log || it > 0) }
            points.forEachIndexed { pointIndex, point ->
                val offset = (pointIndex - (points.size - 1) / 2.0) / max(1, points.size) * barWidth * 0.7
                val pointY = yOf(point)
                parts.add("<circle cx=\"${center + offset}\" cy=\"$pointY\" r=\"2.4\" fill=\"#ffffff\" stroke=\"$color\" stroke-width=\"1.2\"/>")
            }
        }

        val labelX = plotLeft + categoryIndex * bandWidth + bandWidth / 2
        if (rotateLabels) {
            parts.add("<text x=\"$labelX\" y=\"${plotBottom + 12}\" transform=\"rotate(-32 $labelX ${plotBottom + 12})\" text-anchor=\"end\" font-family=\"$FONT\" font-size=\"9\" fill=\"#333b4a\">${esc(truncate(category, 20))}</text>")
        } else {
            parts.add("<text x=\"$labelX\" y=\"${plotBottom + 18}\" text-anchor=\"middle\" font-family=\"$FONT\" font-size=\"10\" fill=\"#333b4a\">${esc(truncate(category, 20))}</text>")
        }
    }

    if (categoryCount == 0) {
        parts.add("<text x=\"${plotLeft + plotWidth / 2}\" y=\"${plotTop + plotHeight / 2}\" text-anchor=\"middle\" font-family=\"$FONT\" font-size=\"11\" fill=\"#7a8394\">-</text>")
    }

    // Axis label.
    val axisLabelY = plotTop + plotHeight / 2
    parts.add("<text x=\"18\" y=\"$axisLabelY\" transform=\"rotate(-90 18 $axisLabelY)\" text-anchor=\"middle\" font-family=\"$FONT\" font-size=\"10\" fill=\"#333b4a\">${esc(labels.yLabel)}</text>")
    parts.add("</svg>")
    return SvgDoc(parts.joinToString(""), width, height)
}

// --------------------------------------------------------------- heatmap

class HeatmapRow(
    val label: String,
    val sub: String,
    val values: List<Double?>,
)

class HeatmapLabels(
    val title: String,
    val low: String,
    val high: String,
)

private fun parseHex(hex: String): Triple<Int, Int, Int> = Triple(
    hex.substring(1, 3).toInt(16),
    hex.substring(3, 5).toInt(16),
    hex.substring(5, 7).toInt(16),
)

private fun lerpColor(a: String, b: String, t: Double): String {
    val (ar, ag, ab) = parseHex(a)
    val (br, bg, bb) = parseHex(b)
    val r = (ar + (br - ar) * t).roundToInt()
    val g = (ag + (bg - ag) * t).roundToInt()
    val blue = (ab + (bb - ab) * t).roundToInt()
    return "rgb($r,$g,$blue)"
}

private fun heatColor(t: Double): String =
    if (t <= 0.5) lerpColor("#2166ac", "#f7f7f7", t * 2)
    else lerpColor("#f7f7f7", "#b2182b", (t - 0.5) * 2)

/** Delta Ct heatmap with sample rows and target columns (1:1 heatmapSvg). */
fun heatmapSvg(
    rows: List<HeatmapRow>,
    columns: List<String>,
    labels: HeatmapLabels,
): SvgDoc {
    val left = 150
    val top = 66
    val cellWidth = 82
    val cellHeight = 24
    val right = 24
    val bottom = 66
    val width = left + max(1, columns.size) * cellWidth + right
    val height = top + max(1, rows.size) * cellHeight + bottom

    val allValues = ArrayList<Double>()
    for (row in rows) {
        for (value in row.values) {
            if (value !== null && value.isFinite()) allValues.add(value)
        }
    }
    var min = if (allValues.isNotEmpty()) allValues.min() else 0.0
    var max = if (allValues.isNotEmpty()) allValues.max() else 1.0
    if (max - min < 1e-6) {
        min -= 0.5
        max += 0.5
    }

    val parts = ArrayList<String>()
    parts.add("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" role=\"img\">")
    parts.add("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>")
    parts.add("<text x=\"24\" y=\"24\" font-family=\"$FONT\" font-size=\"14\" font-weight=\"700\" fill=\"#151a24\">${esc(labels.title)}</text>")

    columns.forEachIndexed { columnIndex, column ->
        val x = left + columnIndex * cellWidth + cellWidth / 2.0
        val y = top - 10.0
        parts.add("<text x=\"$x\" y=\"$y\" transform=\"rotate(-32 $x $y)\" text-anchor=\"start\" font-family=\"$FONT\" font-size=\"9\" fill=\"#333b4a\">${esc(truncate(column, 14))}</text>")
    }

    rows.forEachIndexed { rowIndex, row ->
        val y = top + rowIndex * cellHeight
        val label = if (row.sub.isNotEmpty()) "${row.label} · ${row.sub}" else row.label
        parts.add("<text x=\"${left - 8}\" y=\"${y + cellHeight / 2.0 + 3}\" text-anchor=\"end\" font-family=\"$FONT\" font-size=\"9\" fill=\"#333b4a\">${esc(truncate(label, 24))}</text>")
        columns.forEachIndexed { columnIndex, _ ->
            val value = row.values.getOrNull(columnIndex)
            val x = left + columnIndex * cellWidth
            var fill = "#eef1f6"
            var text = ""
            if (value !== null && value.isFinite()) {
                val t = (value - min) / (max - min)
                fill = heatColor(t)
                text = String.format(java.util.Locale.US, "%.2f", value)
            }
            parts.add("<rect x=\"$x\" y=\"$y\" width=\"${cellWidth - 1}\" height=\"${cellHeight - 1}\" fill=\"$fill\" stroke=\"#ffffff\" stroke-width=\"1\"/>")
            val t = if (value !== null && value.isFinite()) (value - min) / (max - min) else 0.5
            val textColor = if (t > 0.28 && t < 0.72) "#1a1a1a" else "#ffffff"
            parts.add("<text x=\"${x + (cellWidth - 1) / 2.0}\" y=\"${y + cellHeight / 2.0 + 3}\" text-anchor=\"middle\" font-family=\"$FONT\" font-size=\"8.5\" fill=\"$textColor\">${esc(text)}</text>")
        }
    }

    val legendY = height - 36
    parts.add("<defs><linearGradient id=\"qpcrHeat\" x1=\"0\" x2=\"1\" y1=\"0\" y2=\"0\"><stop offset=\"0\" stop-color=\"#2166ac\"/><stop offset=\"0.5\" stop-color=\"#f7f7f7\"/><stop offset=\"1\" stop-color=\"#b2182b\"/></linearGradient></defs>")
    parts.add("<rect x=\"$left\" y=\"$legendY\" width=\"150\" height=\"9\" fill=\"url(#qpcrHeat)\" stroke=\"#c9cfda\" stroke-width=\"0.7\"/>")
    parts.add("<text x=\"$left\" y=\"${legendY + 22}\" font-family=\"$FONT\" font-size=\"9\" fill=\"#4a5262\">${esc(labels.low)} ${formatTick(min)}</text>")
    parts.add("<text x=\"${left + 150}\" y=\"${legendY + 22}\" text-anchor=\"end\" font-family=\"$FONT\" font-size=\"9\" fill=\"#4a5262\">${formatTick(max)} ${esc(labels.high)}</text>")
    parts.add("</svg>")
    return SvgDoc(parts.joinToString(""), width, height)
}
