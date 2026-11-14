package com.spice.nativeapp.core

// ============================================================
// main src/lib/tools/figure/{types(chart part), charts.ts,
// figureAgent.ts} 1:1 移植。
// - 图表节点模型（ChartKind/ChartSeries/ChartSignificance/ChartNode）
// - CSV 解析/序列化（err 表头附差、引号转义、分隔符探测）
// - 刻度（niceStep 1/2/2.5/5/10、8% pad、yMin/yMax 覆盖、bar 含 0）
// - 同构 SVG 片段渲染 + 原生 Canvas 绘制（同几何）
// - figureAgent 命令总线（handler/queue 语义同 main 模块单例）
// ============================================================

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val PT_TO_PX = 96.0 / 72.0
const val DEFAULT_FIGURE_FONT = "Arial"

val CHART_PALETTE = listOf(
    "#2563eb", "#dc2626", "#059669", "#d97706",
    "#7c3aed", "#0891b2", "#be185d", "#65a30d",
)

private var figureIdCounter = 0L

/** main createFigureId：prefix_<时间36>_<随机36>。Kotlin 以原子计数器保序（时间戳+计数）。 */
fun createFigureId(prefix: String): String {
    val now = System.currentTimeMillis().toString(36)
    val n = synchronized(Any()) { figureIdCounter++ }
    return "${prefix}_${now}_${n.toString(36)}"
}

enum class ChartKind(val jsName: String) { BAR("bar"), LINE("line"), SCATTER("scatter") }

fun chartKindFromJs(name: String): ChartKind? =
    when (name) { "bar" -> ChartKind.BAR; "line" -> ChartKind.LINE; "scatter" -> ChartKind.SCATTER; else -> null }

data class ChartSeries(
    val id: String,
    val name: String,
    val color: String,
    val values: List<Double>,
    /** 绝对误差；仅在 showWithErrorBars 开启时参与绘制。 */
    val errors: List<Double>,
)

data class ChartSignificance(
    val id: String,
    /** 括号起始类目索引。 */
    val from: Int,
    /** 括号结束类目索引。 */
    val to: Int,
    val label: String,
)

/** FigureNodeBase（main baseNode）字段随图表节点携带；坐标为文档像素。 */
data class ChartNode(
    val id: String,
    val name: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val fill: String = "#e8f1f8",
    val stroke: String = "#1f6f8b",
    val strokeWidth: Double = 1.5,
    val chartType: ChartKind = ChartKind.BAR,
    val labels: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    val xLabel: String = "",
    val yLabel: String = "",
    /** null 时由渲染器自行选择合理值域。 */
    val yMin: Double? = null,
    val yMax: Double? = null,
    val showLegend: Boolean = true,
    val showGrid: Boolean = true,
    val showValues: Boolean = false,
    val showErrorBars: Boolean = true,
    val significance: List<ChartSignificance> = emptyList(),
    val fontFamily: String = DEFAULT_FIGURE_FONT,
    val fontSize: Double = 10.0,
    val textColor: String = "#334155",
    val plotBackground: String = "none",
)

/** main createChartNode 默认示例数据。 */
fun createChartNode(x: Double, y: Double, w: Double = 320.0, h: Double = 220.0): ChartNode =
    ChartNode(
        id = createFigureId("node"),
        name = "Chart",
        x = x, y = y, w = w, h = h,
        fill = "none",
        stroke = "#475569",
        strokeWidth = 1.0,
        chartType = ChartKind.BAR,
        labels = listOf("Control", "Treatment", "Rescue"),
        series = listOf(
            ChartSeries(
                id = createFigureId("series"),
                name = "Relative signal",
                color = CHART_PALETTE[0],
                values = listOf(1.0, 1.8, 1.2),
                errors = listOf(0.1, 0.18, 0.14),
            )
        ),
        xLabel = "",
        yLabel = "Relative signal",
        yMin = null,
        yMax = null,
        showLegend = true,
        showGrid = true,
        showValues = false,
        showErrorBars = true,
        significance = listOf(ChartSignificance(createFigureId("sig"), 0, 1, "*")),
        fontFamily = DEFAULT_FIGURE_FONT,
        fontSize = 10.0,
        textColor = "#334155",
        plotBackground = "none",
    )

// ---------------------------------------------------------------------- CSV

data class ChartData(val labels: List<String>, val series: List<ChartSeries>)

private fun splitCsvLine(line: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    var cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        if (quoted) {
            if (char == '"' && index + 1 < line.length && line[index + 1] == '"') {
                cell.append('"')
                index += 1
            } else if (char == '"') {
                quoted = false
            } else {
                cell.append(char)
            }
        } else if (char == '"') {
            quoted = true
        } else if (char == delimiter) {
            out.add(cell.toString().trim())
            cell = StringBuilder()
        } else {
            cell.append(char)
        }
        index += 1
    }
    out.add(cell.toString().trim())
    return out
}

private fun detectDelimiter(lines: List<String>): Char {
    val header = lines.firstOrNull() ?: ""
    val counts = listOf(
        ',' to header.count { it == ',' },
        '\t' to header.count { it == '\t' },
        ';' to header.count { it == ';' },
    ).sortedByDescending { it.second }
    return if (counts[0].second > 0) counts[0].first else ','
}

private fun isErrorHeader(header: String): Boolean =
    Regex("""(?:^|[\s_(])(?:err|error|sd|sem)(?:$|[\s_)])""", RegexOption.IGNORE_CASE)
        .containsMatchIn(header.trim())

private fun seriesId(index: Int): String = "series_${index + 1}"

const val CHART_CSV_EXAMPLE = "Condition,Control,Control err,Treatment,Treatment err\n" +
    "0 h,1,0.10,1.8,0.18\n" +
    "24 h,1.2,0.12,2.1,0.20"

/** 解析小型 CSV 表；无可用数值列时返回 null。 */
fun parseChartCsv(text: String): ChartData? {
    val lines = text
        .split(Regex("\\r?\\n"))
        .map { it.trim() }
        // 仅 "# " 视作注释，"#1" 这类标签可安全往返。
        .filter { it.isNotEmpty() && !it.startsWith("# ") }
    if (lines.size < 2) return null
    val delimiter = detectDelimiter(lines)
    val header = splitCsvLine(lines[0], delimiter)
    if (header.size < 2) return null

    val rows = lines.drop(1).map { splitCsvLine(it, delimiter) }
    val labels = rows.mapIndexed { index, row -> row.getOrElse(0) { "" }.ifEmpty { "#${index + 1}" } }
    if (labels.isEmpty()) return null

    val series = mutableListOf<ChartSeries>()
    for (column in 1 until header.size) {
        val name = header[column].ifEmpty { "Series $column" }
        val values = rows.map { row ->
            val value = row.getOrNull(column)?.let { it.toDoubleOrNull() } ?: Double.NaN
            if (value.isFinite()) value else 0.0
        }
        if (isErrorHeader(name)) {
            if (series.isEmpty()) continue
            val target = series.last()
            series[series.size - 1] = target.copy(errors = values.map { abs(it) })
            continue
        }
        series.add(
            ChartSeries(
                id = seriesId(series.size),
                name = name,
                color = "",
                values = values,
                errors = List(labels.size) { 0.0 },
            )
        )
    }
    if (series.isEmpty()) return null
    return ChartData(labels, series)
}

private fun csvCell(value: String): String =
    if (Regex("""[",\n]""").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value

/** CSV 编辑器格式往返。 */
fun chartCsv(node: ChartNode): String {
    val hasErrors = node.series.any { series -> series.errors.any { it != 0.0 } }
    val header = mutableListOf("Label")
    for (series in node.series) {
        header.add(series.name)
        if (hasErrors) header.add("${series.name} err")
    }
    val rows = mutableListOf(header.joinToString(",") { csvCell(it) })
    for (index in node.labels.indices) {
        val row = mutableListOf(node.labels.getOrNull(index) ?: "")
        for (series in node.series) {
            row.add(jsNum(series.values.getOrNull(index) ?: 0.0))
            if (hasErrors) row.add(jsNum(series.errors.getOrNull(index) ?: 0.0))
        }
        rows.add(row.joinToString(","))
    }
    return rows.joinToString("\n")
}

/** 应用解析结果，尽量保留既有系列颜色。 */
fun applyChartCsv(node: ChartNode, data: ChartData, palette: List<String> = CHART_PALETTE): ChartNode {
    val previous = node.series.associateBy { it.name }
    return node.copy(
        labels = data.labels,
        series = data.series.mapIndexed { index, series ->
            val old = previous[series.name]
            series.copy(
                id = old?.id ?: series.id,
                color = old?.color?.takeIf { it.isNotEmpty() } ?: palette[index % palette.size],
                errors = series.errors.take(data.labels.size),
            )
        },
    )
}

// ------------------------------------------------------------------ geometry

private fun valuesInRange(node: ChartNode): List<Double> {
    val values = mutableListOf<Double>()
    for (series in node.series) {
        for (index in node.labels.indices) values.add(series.values.getOrNull(index) ?: 0.0)
    }
    return values
}

private fun dataExtent(node: ChartNode): Pair<Double, Double> {
    val values = valuesInRange(node)
    var mn = if (values.isNotEmpty()) values.min() else 0.0
    var mx = if (values.isNotEmpty()) values.max() else 1.0
    if (node.showErrorBars) {
        for (series in node.series) {
            for (index in node.labels.indices) {
                val value = series.values.getOrNull(index) ?: 0.0
                val error = abs(series.errors.getOrNull(index) ?: 0.0)
                mn = min(mn, value - error)
                mx = max(mx, value + error)
            }
        }
    }
    if (node.chartType == ChartKind.BAR) {
        mn = min(0.0, mn)
        mx = max(0.0, mx)
    }
    return mn to mx
}

private fun niceStep(span: Double, targetTicks: Int): Double {
    if (!span.isFinite() || span <= 0.0) return 1.0
    val raw = span / max(1.0, targetTicks.toDouble())
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

data class TickScale(val min: Double, val max: Double, val ticks: List<Double>)

fun chartScale(node: ChartNode): TickScale {
    val extent = dataExtent(node)
    var mn = node.yMin?.takeIf { it.isFinite() } ?: extent.first
    var mx = node.yMax?.takeIf { it.isFinite() } ?: extent.second
    if (mx <= mn) mx = mn + 1
    val pad = (mx - mn) * 0.08
    if (node.yMin?.takeIf { it.isFinite() } == null) mn = if (mn < 0) mn - pad else min(0.0, mn)
    if (node.yMax?.takeIf { it.isFinite() } == null) mx += pad
    val step = niceStep(mx - mn, 5)
    val start = floor(mn / step) * step
    val end = ceil(mx / step) * step
    val ticks = mutableListOf<Double>()
    var value = start
    while (value <= end + step * 0.001) {
        ticks.add(if (abs(value) < step * 0.0001) 0.0 else value)
        value += step
    }
    return TickScale(start, end, ticks)
}

private fun fmtFixed(value: Double, decimals: Int): String =
    String.format("%.${decimals}f", value)

private fun tickLabel(value: Double, step: Double): String {
    if (value == 0.0) return "0"
    val decimals = if (step >= 1.0) 0 else min(4, ceil(-log10(step)).toInt())
    return fmtFixed(value, decimals)
}

fun significanceLevels(node: ChartNode): List<Int> {
    val levels = mutableListOf<Int>()
    val occupied = mutableListOf<MutableList<Pair<Int, Int>>>()
    for (sig in node.significance) {
        var level = 0
        while (true) {
            val spans = occupied.getOrNull(level)
            if (spans == null || spans.none { (from, to) -> sig.from <= to && sig.to >= from }) break
            level += 1
        }
        while (occupied.size <= level) occupied.add(mutableListOf())
        occupied[level].add(sig.from to sig.to)
        levels.add(level)
    }
    return levels
}

// -------------------------------------------------------------------- render

class ChartRender(val svg: String, val scale: TickScale)

private fun esc(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun num(value: Double): String =
    if (!value.isFinite()) "0" else jsNum(kotlin.math.round(value * 10000) / 10000)

private fun isNone(value: String): Boolean {
    val text = value.trim().lowercase()
    return text.isEmpty() || text == "none" || text == "transparent"
}

/** 在节点边界内渲染图表（SVG 片段，坐标为文档坐标；与 main renderChartNode 同构）。 */
fun renderChartNode(node: ChartNode): ChartRender {
    val fontPx = max(5.0, node.fontSize * PT_TO_PX)
    val textColor = if (isNone(node.textColor)) "#334155" else node.textColor
    val axisColor = if (isNone(node.stroke)) textColor else node.stroke
    val scale = chartScale(node)
    val tickPad = fontPx * 4.2
    val hasXLabel = node.xLabel.trim().isNotEmpty()
    val hasLegend = node.showLegend && node.series.isNotEmpty()
    val padLeft = max(tickPad, fontPx * 2.2)
    val padRight = max(8.0, fontPx * 0.6)
    val padTop = if (hasLegend) fontPx * 2.6 else fontPx * 0.8
    val padBottom = fontPx * 2.4 + (if (hasXLabel) fontPx * 1.6 else 0.0)
    val plotX = node.x + padLeft
    val plotY = node.y + padTop
    val plotW = max(20.0, node.w - padLeft - padRight)
    val plotH = max(20.0, node.h - padTop - padBottom)
    fun yFor(value: Double): Double = plotY + plotH - ((value - scale.min) / (scale.max - scale.min)) * plotH
    val baseline = yFor(min(scale.max, max(scale.min, 0.0)))
    val categoryCount = max(1, node.labels.size)
    val slot = plotW / categoryCount
    val out = mutableListOf<String>()

    if (!isNone(node.plotBackground)) {
        out.add("<rect x=\"${num(plotX)}\" y=\"${num(plotY)}\" width=\"${num(plotW)}\" height=\"${num(plotH)}\" fill=\"${esc(node.plotBackground)}\"/>")
    }

    if (node.showGrid) {
        for (tick in scale.ticks) {
            val y = yFor(tick)
            out.add("<line x1=\"${num(plotX)}\" y1=\"${num(y)}\" x2=\"${num(plotX + plotW)}\" y2=\"${num(y)}\" stroke=\"#e2e8f0\" stroke-width=\"0.6\"/>")
        }
    }

    // Axes
    out.add("<line x1=\"${num(plotX)}\" y1=\"${num(plotY)}\" x2=\"${num(plotX)}\" y2=\"${num(plotY + plotH)}\" stroke=\"${esc(axisColor)}\" stroke-width=\"${num(max(0.6, node.strokeWidth))}\"/>")
    out.add("<line x1=\"${num(plotX)}\" y1=\"${num(plotY + plotH)}\" x2=\"${num(plotX + plotW)}\" y2=\"${num(plotY + plotH)}\" stroke=\"${esc(axisColor)}\" stroke-width=\"${num(max(0.6, node.strokeWidth))}\"/>")

    // Y ticks and labels
    val tickStep = if (scale.ticks.size > 1) scale.ticks[1] - scale.ticks[0] else 1.0
    for (tick in scale.ticks) {
        val y = yFor(tick)
        out.add("<line x1=\"${num(plotX - 3)}\" y1=\"${num(y)}\" x2=\"${num(plotX)}\" y2=\"${num(y)}\" stroke=\"${esc(axisColor)}\" stroke-width=\"0.7\"/>")
        out.add("<text x=\"${num(plotX - 5)}\" y=\"${num(y)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx)}\" fill=\"${esc(textColor)}\" text-anchor=\"end\" dominant-baseline=\"middle\">${esc(tickLabel(tick, tickStep))}</text>")
    }

    // X category labels
    node.labels.forEachIndexed { index, label ->
        val x = plotX + slot * (index + 0.5)
        val maxChars = max(3, floor(slot / (fontPx * 0.58)).toInt())
        val text = if (label.length > maxChars) label.take(max(1, maxChars - 1)) + "…" else label
        out.add("<text x=\"${num(x)}\" y=\"${num(plotY + plotH + fontPx * 1.35)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx)}\" fill=\"${esc(textColor)}\" text-anchor=\"middle\">${esc(text)}</text>")
    }

    // Axis labels
    if (hasXLabel) {
        out.add("<text x=\"${num(plotX + plotW / 2)}\" y=\"${num(node.y + node.h - fontPx * 0.4)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx)}\" fill=\"${esc(textColor)}\" text-anchor=\"middle\">${esc(node.xLabel)}</text>")
    }
    if (node.yLabel.trim().isNotEmpty()) {
        val cx = node.x + fontPx * 1.1
        val cy = plotY + plotH / 2
        out.add("<text x=\"${num(cx)}\" y=\"${num(cy)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx)}\" fill=\"${esc(textColor)}\" text-anchor=\"middle\" transform=\"rotate(-90 ${num(cx)} ${num(cy)})\">${esc(node.yLabel)}</text>")
    }

    // Data marks
    val seriesCount = max(1, node.series.size)
    if (node.chartType == ChartKind.BAR) {
        val groupWidth = slot * 0.72
        val barWidth = groupWidth / seriesCount
        node.series.forEachIndexed { seriesIndex, series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            node.labels.forEachIndexed { index, _ ->
                val value = series.values.getOrNull(index) ?: 0.0
                val x = plotX + slot * index + (slot - groupWidth) / 2 + barWidth * seriesIndex
                val y = yFor(maxOf(scale.min, min(scale.max, value)))
                val top = min(y, baseline)
                val height = max(0.5, abs(baseline - y))
                out.add("<rect x=\"${num(x)}\" y=\"${num(top)}\" width=\"${num(max(0.5, barWidth - 1))}\" height=\"${num(height)}\" fill=\"${esc(color)}\"/>")
                if (node.showValues) {
                    out.add("<text x=\"${num(x + barWidth / 2)}\" y=\"${num((if (value >= 0) y else baseline) - 3)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx * 0.9)}\" fill=\"${esc(textColor)}\" text-anchor=\"middle\">${esc(jsNum(value))}</text>")
                }
            }
        }
    } else {
        node.series.forEach { series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val points = node.labels.mapIndexed { index, _ ->
                Triple(
                    plotX + slot * (index + 0.5),
                    yFor(maxOf(scale.min, min(scale.max, series.values.getOrNull(index) ?: 0.0))),
                    series.values.getOrNull(index) ?: 0.0,
                )
            }
            if (node.chartType == ChartKind.LINE) {
                out.add("<polyline points=\"${points.joinToString(" ") { "${num(it.first)},${num(it.second)}" }}\" fill=\"none\" stroke=\"${esc(color)}\" stroke-width=\"${num(max(1.0, node.strokeWidth * 1.4))}\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>")
            }
            points.forEach { (px, py, value) ->
                out.add("<circle cx=\"${num(px)}\" cy=\"${num(py)}\" r=\"${num(max(2.0, fontPx * 0.34))}\" fill=\"${esc(color)}\"/>")
                if (node.showValues) {
                    out.add("<text x=\"${num(px)}\" y=\"${num(py - fontPx * 0.8)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx * 0.9)}\" fill=\"${esc(textColor)}\" text-anchor=\"middle\">${esc(jsNum(value))}</text>")
                }
            }
        }
    }

    // Error bars
    if (node.showErrorBars) {
        val cap = max(2.0, fontPx * 0.3)
        node.series.forEachIndexed { seriesIndex, series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val barWidth = if (node.chartType == ChartKind.BAR) (slot * 0.72) / seriesCount else 0.0
            node.labels.forEachIndexed { index, _ ->
                val error = abs(series.errors.getOrNull(index) ?: 0.0)
                if (error == 0.0) return@forEachIndexed
                val value = series.values.getOrNull(index) ?: 0.0
                val x = if (node.chartType == ChartKind.BAR)
                    plotX + slot * index + (slot - slot * 0.72) / 2 + barWidth * (seriesIndex + 0.5)
                else
                    plotX + slot * (index + 0.5)
                val high = yFor(min(scale.max, value + error))
                val low = yFor(max(scale.min, value - error))
                out.add("<line x1=\"${num(x)}\" y1=\"${num(high)}\" x2=\"${num(x)}\" y2=\"${num(low)}\" stroke=\"${esc(color)}\" stroke-width=\"1\"/>")
                out.add("<line x1=\"${num(x - cap)}\" y1=\"${num(high)}\" x2=\"${num(x + cap)}\" y2=\"${num(high)}\" stroke=\"${esc(color)}\" stroke-width=\"1\"/>")
                out.add("<line x1=\"${num(x - cap)}\" y1=\"${num(low)}\" x2=\"${num(x + cap)}\" y2=\"${num(low)}\" stroke=\"${esc(color)}\" stroke-width=\"1\"/>")
            }
        }
    }

    // Significance brackets
    if (node.significance.isNotEmpty() && node.labels.size > 1) {
        val levels = significanceLevels(node)
        node.significance.forEachIndexed { index, sig ->
            val level = levels.getOrNull(index) ?: 0
            var peak = 0.0
            for (category in sig.from..sig.to) {
                for (series in node.series) {
                    val value = series.values.getOrNull(category) ?: 0.0
                    val error = if (node.showErrorBars) abs(series.errors.getOrNull(category) ?: 0.0) else 0.0
                    peak = max(peak, value + error)
                }
            }
            val y = max(
                plotY + fontPx * 0.6,
                min(plotY + plotH - 4.0, yFor(min(scale.max, peak)) - 6.0 - level * (fontPx * 1.3)),
            )
            val x1 = plotX + slot * (sig.from + 0.5)
            val x2 = plotX + slot * (sig.to + 0.5)
            val hook = min(6.0, abs(x2 - x1) / 4)
            out.add("<path d=\"M ${num(x1)} ${num(y + hook)} L ${num(x1)} ${num(y)} L ${num(x2)} ${num(y)} L ${num(x2)} ${num(y + hook)}\" fill=\"none\" stroke=\"${esc(textColor)}\" stroke-width=\"0.9\"/>")
            if (sig.label.isNotEmpty()) {
                out.add("<text x=\"${num((x1 + x2) / 2)}\" y=\"${num(y - 2)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx)}\" font-weight=\"600\" fill=\"${esc(textColor)}\" text-anchor=\"middle\">${esc(sig.label)}</text>")
            }
        }
    }

    // Legend
    if (hasLegend) {
        var x = plotX
        val y = node.y + fontPx * 1.1
        val maxX = plotX + plotW
        for (series in node.series) {
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val labelWidth = max(fontPx * 3, series.name.length * fontPx * 0.56)
            if (x + labelWidth + fontPx * 1.6 > maxX) break
            out.add("<rect x=\"${num(x)}\" y=\"${num(y - fontPx * 0.5)}\" width=\"${num(fontPx * 0.55)}\" height=\"${num(fontPx * 0.55)}\" fill=\"${esc(color)}\"/>")
            out.add("<text x=\"${num(x + fontPx * 0.8)}\" y=\"${num(y)}\" font-family=\"${esc(node.fontFamily)}\" font-size=\"${num(fontPx * 0.95)}\" fill=\"${esc(textColor)}\" dominant-baseline=\"middle\">${esc(series.name)}</text>")
            x += fontPx * 0.55 + fontPx * 0.8 + labelWidth + fontPx
        }
    }

    return ChartRender(out.joinToString(""), scale)
}

/** 图层列表与项目元数据用的人类可读摘要。 */
fun chartSummary(node: ChartNode): String {
    val kind = when (node.chartType) { ChartKind.BAR -> "Bar"; ChartKind.LINE -> "Line"; ChartKind.SCATTER -> "Scatter" }
    return "$kind · ${node.series.size} series · ${node.labels.size} categories"
}

// ------------------------------------------------- figureAgent（命令总线 1:1）

/** main figureAgent.ts FigureChartSpec。 */
data class FigureChartSpecSeries(
    val name: String,
    val values: List<Double>,
    val errors: List<Double>? = null,
    val color: String? = null,
)

data class FigureChartSpec(
    val name: String,
    val chartType: ChartKind,
    val labels: List<String>,
    val series: List<FigureChartSpecSeries>,
    val xLabel: String? = null,
    val yLabel: String? = null,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("name", name)
        put("chartType", chartType.jsName)
        put("labels", org.json.JSONArray(labels))
        put("series", org.json.JSONArray().apply {
            series.forEach { s ->
                put(org.json.JSONObject().apply {
                    put("name", s.name)
                    put("values", org.json.JSONArray(s.values))
                    s.errors?.let { put("errors", org.json.JSONArray(it)) }
                    s.color?.let { put("color", it) }
                })
            }
        })
        xLabel?.let { put("xLabel", it) }
        yLabel?.let { put("yLabel", it) }
    }
}

sealed class FigureAgentCommand {
    data class AddChart(val spec: FigureChartSpec) : FigureAgentCommand()
}

/**
 * Co-Pilot 工具注册表与已挂载 Figure Studio 之间的命令总线。
 * main 为模块级单例；Kotlin 以 object 同构（synchronized 保证跨线程语义，
 * main 单线程下的排队/重放行为不变：编辑器未挂载时命令排队，注册时重放）。
 */
object FigureAgent {
    private var activeHandler: ((FigureAgentCommand) -> Unit)? = null
    private val pendingCommands = mutableListOf<FigureAgentCommand>()

    /** 递交给已挂载的 Figure Studio；无编辑器时排队并返回 false。 */
    @Synchronized
    fun dispatchFigureCommand(command: FigureAgentCommand): Boolean {
        val handler = activeHandler
        if (handler != null) {
            handler(command)
            return true
        }
        pendingCommands.add(command)
        return false
    }

    /** 注册挂载 handler 并重放排队命令；返回反注册函数。 */
    @Synchronized
    fun registerFigureCommandHandler(handler: (FigureAgentCommand) -> Unit): () -> Unit {
        activeHandler = handler
        val queued = pendingCommands.toList()
        pendingCommands.clear()
        queued.forEach(handler)
        return {
            synchronized(this) {
                if (activeHandler === handler) activeHandler = null
            }
        }
    }

    /** FigureTool 实例挂载中即为 true。 */
    @Synchronized
    fun figureToolActive(): Boolean = activeHandler != null
}

// --------------------------------------------- native canvas draw（同几何）

private fun parseColorOr(colorText: String, fallback: Int): Int =
    runCatching { android.graphics.Color.parseColor(colorText) }.getOrDefault(fallback)

/** 与 renderChartNode 完全相同的几何，输出到原生 Canvas（画布/PNG 导出共用）。 */
fun drawChartToCanvas(node: ChartNode, canvas: NativeCanvas, offsetX: Float = 0f, offsetY: Float = 0f, scaleFactor: Float = 1f) {
    val fontPx = max(5.0, node.fontSize * PT_TO_PX) * scaleFactor
    val textColor = if (isNone(node.textColor)) "#334155" else node.textColor
    val axisColor = if (isNone(node.stroke)) textColor else node.stroke
    val scale = chartScale(node)
    val tickPad = fontPx * 4.2
    val hasXLabel = node.xLabel.trim().isNotEmpty()
    val hasLegend = node.showLegend && node.series.isNotEmpty()
    val padLeft = max(tickPad, fontPx * 2.2)
    val padRight = max(8.0, fontPx * 0.6)
    val padTop = if (hasLegend) fontPx * 2.6 else fontPx * 0.8
    val padBottom = fontPx * 2.4 + (if (hasXLabel) fontPx * 1.6 else 0.0)
    val nx = node.x * scaleFactor + offsetX
    val ny = node.y * scaleFactor + offsetY
    val plotX = nx + padLeft
    val plotY = ny + padTop
    val plotW = max(20.0, node.w * scaleFactor - padLeft - padRight)
    val plotH = max(20.0, node.h * scaleFactor - padTop - padBottom)
    fun yFor(value: Double): Float = (plotY + plotH - ((value - scale.min) / (scale.max - scale.min)) * plotH).toFloat()
    val baseline = yFor(min(scale.max, max(scale.min, 0.0)))
    val categoryCount = max(1, node.labels.size)
    val slot = plotW / categoryCount

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawText(text: String, x: Float, y: Float, sizePx: Double, color: String, anchor: Paint.Align = Paint.Align.LEFT, baselineMode: Boolean = false, bold: Boolean = false) {
        textPaint.textSize = sizePx.toFloat()
        textPaint.color = parseColorOr(color, 0xFF334155.toInt())
        textPaint.textAlign = anchor
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        val fm = textPaint.fontMetrics
        val dy = if (baselineMode) -(fm.ascent + fm.descent) / 2f else -fm.ascent
        canvas.drawText(text, x, y + dy, textPaint)
    }

    if (!isNone(node.plotBackground)) {
        fillPaint.color = parseColorOr(node.plotBackground, 0xFFFFFFFF.toInt())
        canvas.drawRect(plotX.toFloat(), plotY.toFloat(), (plotX + plotW).toFloat(), (plotY + plotH).toFloat(), fillPaint)
    }

    if (node.showGrid) {
        linePaint.color = 0xFFE2E8F0.toInt()
        linePaint.strokeWidth = 0.6f * scaleFactor
        for (tick in scale.ticks) {
            val y = yFor(tick)
            canvas.drawLine(plotX.toFloat(), y, (plotX + plotW).toFloat(), y, linePaint)
        }
    }

    linePaint.color = parseColorOr(axisColor, 0xFF475569.toInt())
    linePaint.strokeWidth = max(0.6, node.strokeWidth).toFloat() * scaleFactor
    canvas.drawLine(plotX.toFloat(), plotY.toFloat(), plotX.toFloat(), (plotY + plotH).toFloat(), linePaint)
    canvas.drawLine(plotX.toFloat(), (plotY + plotH).toFloat(), (plotX + plotW).toFloat(), (plotY + plotH).toFloat(), linePaint)

    val tickStep = if (scale.ticks.size > 1) scale.ticks[1] - scale.ticks[0] else 1.0
    for (tick in scale.ticks) {
        val y = yFor(tick)
        linePaint.strokeWidth = 0.7f * scaleFactor
        canvas.drawLine((plotX - 3 * scaleFactor).toFloat(), y, plotX.toFloat(), y, linePaint)
        drawText(tickLabel(tick, tickStep), (plotX - 5 * scaleFactor).toFloat(), y, fontPx, textColor, Paint.Align.RIGHT, baselineMode = true)
    }

    node.labels.forEachIndexed { index, label ->
        val x = plotX + slot * (index + 0.5)
        val maxChars = max(3, floor(slot / (fontPx * 0.58)).toInt())
        val text = if (label.length > maxChars) label.take(max(1, maxChars - 1)) + "…" else label
        drawText(text, x.toFloat(), (plotY + plotH + fontPx * 1.35).toFloat(), fontPx, textColor, Paint.Align.CENTER)
    }

    if (hasXLabel) {
        drawText(node.xLabel, (plotX + plotW / 2).toFloat(), (ny + node.h * scaleFactor - fontPx * 0.4).toFloat(), fontPx, textColor, Paint.Align.CENTER)
    }
    if (node.yLabel.trim().isNotEmpty()) {
        val cx = nx + fontPx * 1.1
        val cy = plotY + plotH / 2
        canvas.save()
        canvas.rotate(-90f, cx.toFloat(), cy.toFloat())
        drawText(node.yLabel, cx.toFloat(), cy.toFloat(), fontPx, textColor, Paint.Align.CENTER)
        canvas.restore()
    }

    val seriesCount = max(1, node.series.size)
    if (node.chartType == ChartKind.BAR) {
        val groupWidth = slot * 0.72
        val barWidth = groupWidth / seriesCount
        node.series.forEachIndexed { seriesIndex, series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            node.labels.forEachIndexed { index, _ ->
                val value = series.values.getOrNull(index) ?: 0.0
                val x = plotX + slot * index + (slot - groupWidth) / 2 + barWidth * seriesIndex
                val y = yFor(maxOf(scale.min, min(scale.max, value)))
                val top = min(y, baseline)
                val height = max(0.5, abs(baseline - y).toDouble())
                fillPaint.color = parseColorOr(color, 0xFF2563EB.toInt())
                canvas.drawRect(x.toFloat(), top.toFloat(), (x + max(0.5, barWidth - 1)).toFloat(), (top + height).toFloat(), fillPaint)
                if (node.showValues) {
                    drawText(jsNum(value), (x + barWidth / 2).toFloat(), ((if (value >= 0) y else baseline) - 3 * scaleFactor).toFloat(), fontPx * 0.9, textColor, Paint.Align.CENTER)
                }
            }
        }
    } else {
        node.series.forEach { series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val points = node.labels.mapIndexed { index, _ ->
                Triple(
                    plotX + slot * (index + 0.5),
                    yFor(maxOf(scale.min, min(scale.max, series.values.getOrNull(index) ?: 0.0))),
                    series.values.getOrNull(index) ?: 0.0,
                )
            }
            if (node.chartType == ChartKind.LINE) {
                linePaint.color = parseColorOr(color, 0xFF2563EB.toInt())
                linePaint.strokeWidth = max(1.0, node.strokeWidth * 1.4).toFloat() * scaleFactor
                val path = Path()
                points.forEachIndexed { i, (px, py, _) ->
                    if (i == 0) path.moveTo(px.toFloat(), py.toFloat()) else path.lineTo(px.toFloat(), py.toFloat())
                }
                canvas.drawPath(path, linePaint)
            }
            fillPaint.color = parseColorOr(color, 0xFF2563EB.toInt())
            points.forEach { (px, py, value) ->
                canvas.drawCircle(px.toFloat(), py.toFloat(), max(2.0, fontPx * 0.34).toFloat(), fillPaint)
                if (node.showValues) {
                    drawText(jsNum(value), px.toFloat(), (py - fontPx * 0.8).toFloat(), fontPx * 0.9, textColor, Paint.Align.CENTER)
                }
            }
        }
    }

    if (node.showErrorBars) {
        val cap = max(2.0, fontPx * 0.3)
        node.series.forEachIndexed { seriesIndex, series ->
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val barWidth = if (node.chartType == ChartKind.BAR) (slot * 0.72) / seriesCount else 0.0
            linePaint.color = parseColorOr(color, 0xFF2563EB.toInt())
            linePaint.strokeWidth = 1f * scaleFactor
            node.labels.forEachIndexed { index, _ ->
                val error = abs(series.errors.getOrNull(index) ?: 0.0)
                if (error == 0.0) return@forEachIndexed
                val value = series.values.getOrNull(index) ?: 0.0
                val x = if (node.chartType == ChartKind.BAR)
                    plotX + slot * index + (slot - slot * 0.72) / 2 + barWidth * (seriesIndex + 0.5)
                else
                    plotX + slot * (index + 0.5)
                val high = yFor(min(scale.max, value + error))
                val low = yFor(max(scale.min, value - error))
                canvas.drawLine(x.toFloat(), high, x.toFloat(), low, linePaint)
                canvas.drawLine((x - cap).toFloat(), high, (x + cap).toFloat(), high, linePaint)
                canvas.drawLine((x - cap).toFloat(), low, (x + cap).toFloat(), low, linePaint)
            }
        }
    }

    if (node.significance.isNotEmpty() && node.labels.size > 1) {
        val levels = significanceLevels(node)
        linePaint.color = parseColorOr(textColor, 0xFF334155.toInt())
        linePaint.strokeWidth = 0.9f * scaleFactor
        node.significance.forEachIndexed { index, sig ->
            val level = levels.getOrNull(index) ?: 0
            var peak = 0.0
            for (category in sig.from..sig.to) {
                for (series in node.series) {
                    val value = series.values.getOrNull(category) ?: 0.0
                    val error = if (node.showErrorBars) abs(series.errors.getOrNull(category) ?: 0.0) else 0.0
                    peak = max(peak, value + error)
                }
            }
            val y = max(
                plotY + fontPx * 0.6,
                min(plotY + plotH - 4.0, yFor(min(scale.max, peak)) - 6.0 - level * (fontPx * 1.3)),
            )
            val x1 = plotX + slot * (sig.from + 0.5)
            val x2 = plotX + slot * (sig.to + 0.5)
            val hook = min(6.0, abs(x2 - x1) / 4)
            val path = Path()
            path.moveTo(x1.toFloat(), (y + hook).toFloat())
            path.lineTo(x1.toFloat(), y.toFloat())
            path.lineTo(x2.toFloat(), y.toFloat())
            path.lineTo(x2.toFloat(), (y + hook).toFloat())
            canvas.drawPath(path, linePaint)
            if (sig.label.isNotEmpty()) {
                drawText(sig.label, ((x1 + x2) / 2).toFloat(), (y - 2 * scaleFactor).toFloat(), fontPx, textColor, Paint.Align.CENTER, bold = true)
            }
        }
    }

    if (hasLegend) {
        var x = plotX
        val y = ny + fontPx * 1.1
        val maxX = plotX + plotW
        for (series in node.series) {
            val color = if (isNone(series.color)) "#2563eb" else series.color
            val labelWidth = max(fontPx * 3, series.name.length * fontPx * 0.56)
            if (x + labelWidth + fontPx * 1.6 > maxX) break
            fillPaint.color = parseColorOr(color, 0xFF2563EB.toInt())
            val sw = (fontPx * 0.55).toFloat()
            canvas.drawRect(x.toFloat(), (y - fontPx * 0.5).toFloat(), x.toFloat() + sw, (y - fontPx * 0.5 + sw).toFloat(), fillPaint)
            drawText(series.name, (x + fontPx * 0.8).toFloat(), y.toFloat(), fontPx * 0.95, textColor, baselineMode = true)
            x += fontPx * 0.55 + fontPx * 0.8 + labelWidth + fontPx
        }
    }
}
