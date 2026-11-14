package com.spice.nativeapp.tools.plate

// Canvas bitmap twin of buildPlateSvg (export.ts) with identical geometry:
// same cell size table, margins, header/label font formulas, legend greedy
// wrap, legend growth of the canvas height, truncation rules and estimateWidth
// text metric. Mirrors the platform convention used by the gel/blot/qPCR chart
// previews: previews and the send-to-figure PNG render through this twin
// instead of injecting SVG into the DOM (@html has no Compose equivalent).

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun parseHexColor(hex: String): Int {
    val value = hex.trim().lowercase()
    val full = if (Regex("^#[0-9a-f]{3}$").matches(value)) {
        "#" + value[1] + value[1] + value[2] + value[2] + value[3] + value[3]
    } else value
    return if (Regex("^#[0-9a-f]{6}$").matches(full)) {
        Color.parseColor(full)
    } else {
        Color.parseColor("#0e1220")
    }
}

/** Java monospaced advance ≈ 6px at font-size 10; the original assumes 5.9/char at 9px. */
private fun paintForText(color: Int, fontSizePx: Float, bold: Boolean = false): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = fontSizePx
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }

private fun textWidthMonospace(text: String, fontSizePx: Float): Float {
    var width = 0.0f
    for (ch in text) {
        width += if (ch.code > 0x2e80) fontSizePx * (10f / 9f) else fontSizePx * (5.9f / 9f)
    }
    return width
}

private fun canvasTruncate(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    if (maxChars <= 1) return value.take(maxChars)
    return value.take(maxChars - 1) + "…"
}

fun plateSvgBitmap(options: PlateSvgOptions, scale: Float): Bitmap {
    val (title, rows, cols, cells, legend, legendTitle, note) = options
    val cellSize = cellSizeFor(cols)
    val left = 44
    val top = 84
    val right = 28
    val plateWidth = cols * cellSize
    val plateHeight = rows * cellSize
    val width = maxOf(560, left + plateWidth + right)
    val contentWidth = width - left - right

    // Legend layout identical to buildPlateSvg (greedy wrap, 19px lines).
    val legendPositions = ArrayList<Triple<Int, Int, Pair<PlateSvgLegendItem, String>>>()
    var cursorX = 0
    var cursorY = 0
    var legendLines = 0
    for (item in legend) {
        val text = "${item.label} (${item.count})"
        val itemWidth = (16 + textWidthMonospace(text, 9f) * 1.0f + 18).roundToInt()
        if (cursorX > 0 && cursorX + itemWidth > contentWidth) {
            cursorX = 0
            cursorY += 19
            legendLines++
        }
        legendPositions.add(Triple(cursorX, cursorY, item to text))
        cursorX += itemWidth
    }
    val legendHeight = if (legend.isNotEmpty()) 26 + (legendLines + 1) * 19 else 0
    val height = top + plateHeight + 18 + legendHeight + 10
    val legendTop = top + plateHeight + 22

    val bmp = Bitmap.createBitmap(
        maxOf(1, (width * scale).roundToInt()),
        maxOf(1, (height * scale).roundToInt()),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bmp)
    canvas.save()
    canvas.scale(scale, scale)

    val bgPaint = Paint().apply { color = parseHexColor("#0b0e14") }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    canvas.drawText(title, left.toFloat(), 30f, paintForText(parseHexColor("#ffd23f"), 17f, bold = true))
    if (note != null) {
        canvas.drawText(note, left.toFloat(), 50f, paintForText(parseHexColor("#8b96b5"), 9f))
    }

    val headerFont = max(6.5, min(11.0, cellSize * 0.3)).toFloat()
    val headerPaint = paintForText(parseHexColor("#8b96b5"), headerFont)
    for (col in 0 until cols) {
        val x = left + col * cellSize + cellSize / 2f
        headerPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${col + 1}", x, (top - 8).toFloat(), headerPaint)
    }
    headerPaint.textAlign = Paint.Align.RIGHT
    for (row in 0 until rows) {
        val y = top + row * cellSize + cellSize / 2f + headerFont * 0.35f
        canvas.drawText(rowLabel(row), (left - 10).toFloat(), y, headerPaint)
    }

    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseHexColor("#4a5a82")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRect(
        left.toFloat(), top.toFloat(),
        (left + plateWidth).toFloat(), (top + plateHeight).toFloat(),
        framePaint,
    )

    val cellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parseHexColor("#121624")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val labelFont = max(7.0, min(10.5, cellSize * 0.22)).toFloat()
    val showLabels = cellSize >= 34
    val showWellIds = cellSize >= 44
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val cell = cells.getOrNull(row * cols + col)
            val x = left + col * cellSize + 1
            val y = top + row * cellSize + 1
            val size = cellSize - 2
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseHexColor(cell?.fill ?: "#0e1220")
            }
            canvas.drawRect(
                x.toFloat(), y.toFloat(),
                (x + size).toFloat(), (y + size).toFloat(),
                fillPaint,
            )
            canvas.drawRect(
                x.toFloat(), y.toFloat(),
                (x + size).toFloat(), (y + size).toFloat(),
                cellBorderPaint,
            )
            if (cell == null) continue
            val textColor = parseHexColor(cell.textColor)
            if (showWellIds) {
                val idPaint = paintForText(textColor, 6f).apply { alpha = (0.7f * 255).roundToInt() }
                canvas.drawText(cell.well, (x + 2.5f), (y + 7f), idPaint)
            }
            if (showLabels && cell.label.isNotEmpty()) {
                val maxChars = max(3, ((size - 8) / 5.4).toInt())
                val label = canvasTruncate(cell.label, maxChars)
                val labelPaint = paintForText(textColor, labelFont).apply { textAlign = Paint.Align.CENTER }
                canvas.drawText(
                    label,
                    (x + size / 2f),
                    (y + size / 2f + labelFont * 0.35f),
                    labelPaint,
                )
            }
        }
    }

    if (legend.isNotEmpty()) {
        canvas.drawText(legendTitle, left.toFloat(), legendTop.toFloat(), paintForText(parseHexColor("#8b96b5"), 9f, bold = true))
        for (position in legendPositions) {
            val x = left + position.first
            val y = legendTop + 14 + position.second
            val (item, text) = position.third
            val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = parseHexColor(item.color) }
            val swatchStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseHexColor("#2a3450")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawRect(x.toFloat(), (y - 8).toFloat(), (x + 10).toFloat(), (y + 2).toFloat(), swatchPaint)
            canvas.drawRect(x.toFloat(), (y - 8).toFloat(), (x + 10).toFloat(), (y + 2).toFloat(), swatchStroke)
            canvas.drawText(text, (x + 15).toFloat(), (y + 0.5f), paintForText(parseHexColor("#d8e1f2"), 9f))
        }
    }

    canvas.restore()
    return bmp
}

/** PNG bytes for a plate map bitmap at the requested scale. */
fun plateBitmapPngBytes(bitmap: Bitmap): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
