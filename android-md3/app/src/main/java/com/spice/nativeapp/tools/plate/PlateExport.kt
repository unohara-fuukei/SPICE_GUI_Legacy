package com.spice.nativeapp.tools.plate

// Plate map figure export: vector SVG and raster PNG with physical DPI
// metadata. 1:1 port of src/lib/tools/plate/export.ts (no external libraries).
//
// Platform note: renderSvgToCanvas/canvasToPngBytes (browser blob-image
// rasterization) have no Android equivalent here; PlateRaster.kt carries the
// Canvas bitmap twin with identical geometry instead.

import java.util.Locale

data class PlateSvgCell(
    val fill: String,
    val label: String,
    val textColor: String,
    val well: String,
)

data class PlateSvgLegendItem(
    val label: String,
    val color: String,
    val count: Int,
)

data class PlateSvgOptions(
    val title: String,
    val rows: Int,
    val cols: Int,
    val cells: List<PlateSvgCell?>,
    val legend: List<PlateSvgLegendItem>,
    val legendTitle: String,
    val note: String? = null,
)

data class PlateSvgResult(
    val svg: String,
    val width: Int,
    val height: Int,
)

private fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun truncate(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    if (maxChars <= 1) return value.take(maxChars)
    return value.take(maxChars - 1) + "…"
}

private fun estimateWidth(text: String): Double {
    var width = 0.0
    for (char in text) {
        width += if (char.code > 0x2e80) 10.0 else 5.9
    }
    return width
}

fun cellSizeFor(cols: Int): Int = when {
    cols >= 20 -> 24
    cols >= 16 -> 28
    cols >= 12 -> 38
    cols >= 8 -> 46
    cols >= 6 -> 56
    else -> 70
}

fun buildPlateSvg(options: PlateSvgOptions): PlateSvgResult {
    val (title, rows, cols, cells, legend, legendTitle, note) = options
    val cellSize = cellSizeFor(cols)
    val left = 44
    val top = 84
    val right = 28
    val plateWidth = cols * cellSize
    val plateHeight = rows * cellSize
    val width = maxOf(560, left + plateWidth + right)
    val contentWidth = width - left - right

    // Legend layout is computed first so the canvas can grow to fit it.
    val legendPositions = ArrayList<Triple<Int, Int, Pair<PlateSvgLegendItem, String>>>()
    var cursorX = 0
    var cursorY = 0
    var legendLines = 0
    for (item in legend) {
        val text = "${item.label} (${item.count})"
        val itemWidth = (16 + estimateWidth(text) + 18).toInt()
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

    val parts = ArrayList<String>()
    parts.add(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" role=\"img\" aria-label=\"${escapeXml(title)}\">"
    )
    parts.add("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#0b0e14\"/>")
    parts.add(
        "<text x=\"$left\" y=\"30\" fill=\"#ffd23f\" font-family=\"monospace\" font-size=\"17\" font-weight=\"700\">${escapeXml(title)}</text>"
    )
    if (note != null) {
        parts.add(
            "<text x=\"$left\" y=\"50\" fill=\"#8b96b5\" font-family=\"monospace\" font-size=\"9\">${escapeXml(note)}</text>"
        )
    }

    val headerFont = maxOf(6.5, minOf(11.0, cellSize * 0.3))
    for (col in 0 until cols) {
        val x = left + col * cellSize + cellSize / 2.0
        parts.add(
            "<text x=\"${"%.1f".format(Locale.US, x)}\" y=\"${"%.1f".format(Locale.US, (top - 8).toDouble())}\" fill=\"#8b96b5\" font-family=\"monospace\" font-size=\"${"%.1f".format(Locale.US, headerFont)}\" text-anchor=\"middle\">${col + 1}</text>"
        )
    }
    for (row in 0 until rows) {
        val y = top + row * cellSize + cellSize / 2.0 + headerFont * 0.35
        parts.add(
            "<text x=\"${left - 10}\" y=\"${"%.1f".format(Locale.US, y)}\" fill=\"#8b96b5\" font-family=\"monospace\" font-size=\"${"%.1f".format(Locale.US, headerFont)}\" text-anchor=\"end\">${rowLabel(row)}</text>"
        )
    }

    parts.add(
        "<rect x=\"$left\" y=\"$top\" width=\"$plateWidth\" height=\"$plateHeight\" fill=\"none\" stroke=\"#4a5a82\" stroke-width=\"2\"/>"
    )

    val labelFont = maxOf(7.0, minOf(10.5, cellSize * 0.22))
    val showLabels = cellSize >= 34
    val showWellIds = cellSize >= 44
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val cell = cells.getOrNull(row * cols + col)
            val x = left + col * cellSize + 1
            val y = top + row * cellSize + 1
            val size = cellSize - 2
            val fill = cell?.fill ?: "#0e1220"
            parts.add(
                "<rect x=\"$x\" y=\"$y\" width=\"$size\" height=\"$size\" fill=\"$fill\" stroke=\"#121624\" stroke-width=\"1\"/>"
            )
            if (cell == null) continue
            if (showWellIds) {
                parts.add(
                    "<text x=\"${"%.1f".format(Locale.US, x + 2.5)}\" y=\"${"%.1f".format(Locale.US, y + 7.0)}\" fill=\"${cell.textColor}\" opacity=\"0.7\" font-family=\"monospace\" font-size=\"6\">${escapeXml(cell.well)}</text>"
                )
            }
            if (showLabels && cell.label.isNotEmpty()) {
                val maxChars = maxOf(3, ((size - 8) / 5.4).toInt())
                val label = truncate(cell.label, maxChars)
                parts.add(
                    "<text x=\"${"%.1f".format(Locale.US, x + size / 2.0)}\" y=\"${"%.1f".format(Locale.US, y + size / 2.0 + labelFont * 0.35)}\" fill=\"${cell.textColor}\" font-family=\"monospace\" font-size=\"${"%.1f".format(Locale.US, labelFont)}\" text-anchor=\"middle\">${escapeXml(label)}</text>"
                )
            }
        }
    }

    if (legend.isNotEmpty()) {
        parts.add(
            "<text x=\"$left\" y=\"$legendTop\" fill=\"#8b96b5\" font-family=\"monospace\" font-size=\"9\" font-weight=\"700\">${escapeXml(legendTitle)}</text>"
        )
        for (position in legendPositions) {
            val x = left + position.first
            val y = legendTop + 14 + position.second
            val (item, text) = position.third
            parts.add(
                "<rect x=\"$x\" y=\"${"%.1f".format(Locale.US, (y - 8).toDouble())}\" width=\"10\" height=\"10\" fill=\"${item.color}\" stroke=\"#2a3450\" stroke-width=\"1\"/>"
            )
            parts.add(
                "<text x=\"${"%.1f".format(Locale.US, (x + 15).toDouble())}\" y=\"${"%.1f".format(Locale.US, (y + 0.5))}\" fill=\"#d8e1f2\" font-family=\"monospace\" font-size=\"9\">${escapeXml(text)}</text>"
            )
        }
    }

    parts.add("</svg>")
    return PlateSvgResult(parts.joinToString(""), width, height)
}

// ------------------------------------------------------------- PNG metadata
//
// pHYs + sRGB + iTXt Description chunks, laid out exactly like the original
// export.ts: keyword at 0, four zero bytes (compression flag, method, empty
// language tag, empty translated keyword), then the description.

private val PNG_CRC_TABLE: IntArray = IntArray(256).also { table ->
    for (n in 0 until 256) {
        var c = n
        for (k in 0 until 8) c = if ((c and 1) == 1) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1
        table[n] = c
    }
}

private fun pngCrc32(bytes: ByteArray): Int {
    var c = -0x1
    for (b in bytes) c = PNG_CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c xor -0x1
}

private fun platePngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    val length = data.size
    out[0] = ((length ushr 24) and 0xff).toByte()
    out[1] = ((length ushr 16) and 0xff).toByte()
    out[2] = ((length ushr 8) and 0xff).toByte()
    out[3] = (length and 0xff).toByte()
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    System.arraycopy(data, 0, out, 8, data.size)
    val crc = pngCrc32(out.copyOfRange(4, 8 + data.size))
    out[8 + data.size] = ((crc ushr 24) and 0xff).toByte()
    out[9 + data.size] = ((crc ushr 16) and 0xff).toByte()
    out[10 + data.size] = ((crc ushr 8) and 0xff).toByte()
    out[11 + data.size] = (crc and 0xff).toByte()
    return out
}

/** Adds pHYs (physical resolution), sRGB and an iTXt description to a PNG. */
fun platePngWithDpi(png: ByteArray, dpi: Int, description: String): ByteArray {
    val pixelsPerMetre = Math.round(dpi / 0.0254)
    val physical = ByteArray(9)
    physical[0] = ((pixelsPerMetre ushr 24) and 0xff).toByte()
    physical[1] = ((pixelsPerMetre ushr 16) and 0xff).toByte()
    physical[2] = ((pixelsPerMetre ushr 8) and 0xff).toByte()
    physical[3] = (pixelsPerMetre and 0xff).toByte()
    physical[4] = ((pixelsPerMetre ushr 24) and 0xff).toByte()
    physical[5] = ((pixelsPerMetre ushr 16) and 0xff).toByte()
    physical[6] = ((pixelsPerMetre ushr 8) and 0xff).toByte()
    physical[7] = (pixelsPerMetre and 0xff).toByte()
    physical[8] = 1

    val keyword = "Description".toByteArray(Charsets.UTF_8)
    val text = description.toByteArray(Charsets.UTF_8)
    val itxt = ByteArray(keyword.size + 4 + text.size)
    System.arraycopy(keyword, 0, itxt, 0, keyword.size)
    // compression flag, compression method, empty language tag, empty translated keyword
    System.arraycopy(text, 0, itxt, keyword.size + 4, text.size)

    val chunks = listOf(
        platePngChunk("pHYs", physical),
        platePngChunk("sRGB", byteArrayOf(0)),
        platePngChunk("iTXt", itxt),
    )
    val ihdrEnd = 8 + 25
    if (png.size < ihdrEnd) return png
    val extra = chunks.sumOf { it.size }
    val out = ByteArray(png.size + extra)
    System.arraycopy(png, 0, out, 0, ihdrEnd)
    var offset = ihdrEnd
    for (chunk in chunks) {
        System.arraycopy(chunk, 0, out, offset, chunk.size)
        offset += chunk.size
    }
    System.arraycopy(png, ihdrEnd, out, offset, png.size - ihdrEnd)
    return out
}
