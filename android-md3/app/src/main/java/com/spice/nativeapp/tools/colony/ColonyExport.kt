package com.spice.nativeapp.tools.colony

/*
 * 导出助手：带物理 DPI 元数据的标注 PNG、二值掩膜 PNG、CSV 与可移植项目
 * JSON，1:1 移植 main 的 src/lib/tools/colony/export.ts。
 *
 * 平台差异：canvas.toDataURL('image/png') → Bitmap.compress(PNG)；
 * ctx.measureText → android.graphics.Paint.measureText（圆环/编号几何
 * 与原版公式一致，字形宽度可能略有差异，README 披露）。
 */
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ PNG

private val CRC_TABLE: IntArray = IntArray(256).also { table ->
    for (n in 0 until 256) {
        var c = n
        val poly = 0xedb88320.toInt() // Java int 字面量回绕，Kotlin 需显式 toInt
        repeat(8) {
            c = if (c and 1 != 0) (poly xor (c ushr 1)) else (c ushr 1)
        }
        table[n] = c
    }
}

private fun crc32(bytes: ByteArray): Long {
    var c = 0xffffffffL
    for (b in bytes) {
        val idx = ((c xor ((b.toInt() and 0xff).toLong())) and 0xff).toInt()
        c = (CRC_TABLE[idx].toLong() and 0xffffffffL) xor (c ushr 8)
    }
    return (c xor 0xffffffffL) and 0xffffffffL
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    out[0] = ((data.size ushr 24) and 0xff).toByte()
    out[1] = ((data.size ushr 16) and 0xff).toByte()
    out[2] = ((data.size ushr 8) and 0xff).toByte()
    out[3] = (data.size and 0xff).toByte()
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    data.copyInto(out, 8)
    val crcBytes = out.copyOfRange(4, 8 + data.size)
    val crc = crc32(crcBytes)
    out[8 + data.size] = ((crc ushr 24) and 0xff).toByte()
    out[9 + data.size] = ((crc ushr 16) and 0xff).toByte()
    out[10 + data.size] = ((crc ushr 8) and 0xff).toByte()
    out[11 + data.size] = (crc and 0xff).toByte()
    return out
}

private fun bitmapPngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

/** 在位图 PNG 中插入 pHYs 块（物理像素尺寸），与原版 canvasToPngWithDpi 一致。 */
fun canvasToPngWithDpi(bitmap: Bitmap, dpi: Int): ByteArray {
    val png = bitmapPngBytes(bitmap)
    if (png.size < 33) return png
    val ppm = (max(1, dpi) / 0.0254).roundToInt()
    val phys = ByteArray(9)
    for (i in 0 until 3) {
        val shift = (3 - i) * 8
        phys[i] = ((ppm ushr shift) and 0xff).toByte()
        phys[4 + i] = ((ppm ushr shift) and 0xff).toByte()
    }
    phys[8] = 1 // 米
    val physChunk = pngChunk("pHYs", phys)
    // 遍历块直到第一个 IDAT，使 pHYs 与图像头放在一起。
    var offset = 8
    while (offset + 8 <= png.size) {
        val length = ((png[offset].toInt() and 0xff) shl 24) or ((png[offset + 1].toInt() and 0xff) shl 16) or
            ((png[offset + 2].toInt() and 0xff) shl 8) or (png[offset + 3].toInt() and 0xff)
        val type = String(png, offset + 4, 4, Charsets.US_ASCII)
        if (type == "IDAT" || type == "IEND") break
        offset += 12 + length
    }
    val out = ByteArray(png.size + physChunk.size)
    png.copyInto(out, 0, 0, offset)
    physChunk.copyInto(out, offset)
    png.copyInto(out, offset + physChunk.size, offset, png.size)
    return out
}

// --------------------------------------------------------- annotated image

private const val COLOR_INCLUDED = 0xFF53D769.toInt()
private const val COLOR_EXCLUDED = 0xFFFF5D5D.toInt()
private const val BOX_FILL = 0xD106091F.toInt()   // rgba(6,9,15,0.82)

private fun monospacePaint(sizePx: Double): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sizePx.toFloat()
    isFakeBoldText = true
    textAlign = Paint.Align.CENTER
    // 原版 700 monospace（SFMono/JetBrains Mono/Menlo）；安卓用等宽族近似。
    typeface = android.graphics.Typeface.MONOSPACE
}

/**
 * 绘制处理后图像 + 编号圆环。被排除的标记画红叉且不编号。
 * 几何与原版 renderAnnotated 一致：line = max(1.2, medianRadius·0.12)、
 * font = clamp(9, 64, medianRadius·1.1)、radius = max(4, r·1.18)、
 * 编号框 bx = x + r·0.75、by = y - r·0.75、pad = font·0.22。
 */
fun renderAnnotated(
    source: Bitmap,
    markers: List<ColonyMarker>,
    numbering: MarkerNumbering,
    showNumbers: Boolean,
): Bitmap {
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val radii = markers.filter { it.included }.map { it.radius }
    val baseRadius = if (radii.isNotEmpty()) median(radii)
    else min(canvas.width, canvas.height).toDouble() * 0.01
    val line = max(1.2, baseRadius * 0.12)
    val font = max(9.0, min(64.0, baseRadius * 1.1))
    val textPaint = monospacePaint(font)
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = line.toFloat()
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (marker in markers) {
        val color = if (marker.included) COLOR_INCLUDED else COLOR_EXCLUDED
        val radius = max(4.0, marker.radius * 1.18)
        ringPaint.color = color
        canvas.drawCircle(marker.x.toFloat(), marker.y.toFloat(), radius.toFloat(), ringPaint)
        if (marker.included) {
            fillPaint.color = color
            canvas.drawCircle(
                marker.x.toFloat(), marker.y.toFloat(),
                max(1.5, radius * 0.16).toFloat(), fillPaint,
            )
        } else {
            val arm = (radius * 0.7).toFloat()
            val x = marker.x.toFloat()
            val y = marker.y.toFloat()
            ringPaint.strokeWidth = line.toFloat()
            canvas.drawLine(x - arm, y - arm, x + arm, y + arm, ringPaint)
            canvas.drawLine(x + arm, y - arm, x - arm, y + arm, ringPaint)
        }
        if (showNumbers) {
            val label = if (marker.included) (numbering.labels[marker.id]?.toString() ?: "") else "×"
            if (label.isEmpty()) continue
            val bx = marker.x + radius * 0.75
            val by = marker.y - radius * 0.75
            val pad = font * 0.22
            val textWidth = textPaint.measureText(label).toDouble()
            val boxW = textWidth + pad * 2
            val boxH = font + pad * 2
            val left = (bx - boxW / 2).toFloat()
            val top = (by - boxH / 2).toFloat()
            boxPaint.color = BOX_FILL
            canvas.drawRect(left, top, left + boxW.toFloat(), top + boxH.toFloat(), boxPaint)
            ringPaint.strokeWidth = max(1.0, line * 0.6).toFloat()
            ringPaint.color = color
            canvas.drawRect(left, top, left + boxW.toFloat(), top + boxH.toFloat(), ringPaint)
            ringPaint.strokeWidth = line.toFloat()
            textPaint.color = color
            canvas.drawText(label, bx.toFloat(), (by + font * 0.04).toFloat(), textPaint)
        }
    }
    return out
}

private val boxPaint = Paint()

fun annotatedPngBytes(
    source: Bitmap,
    markers: List<ColonyMarker>,
    numbering: MarkerNumbering,
    dpi: Int,
    showNumbers: Boolean,
): ByteArray {
    val canvas = renderAnnotated(source, markers, numbering, showNumbers)
    return canvasToPngWithDpi(canvas, dpi)
}

fun maskPngBytes(mask: ByteArray, width: Int, height: Int, dpi: Int): ByteArray {
    val bitmap = maskExportBitmap(mask, width, height)
    return canvasToPngWithDpi(bitmap, dpi)
}

// --------------------------------------------------------------- project JSON

fun projectJson(project: ColonyProject): String {
    val obj = project.toJson()
    obj.put("savedAt", nowIso())
    return obj.toString(2)
}

fun parseProjectJson(text: String): ColonyProject? {
    val parsed = try {
        JSONObject(text)
    } catch (e: Exception) {
        return null
    }
    if (!isColonyProject(parsed)) return null
    return projectFromJson(parsed)
}

fun projectBaseName(name: String): String {
    val base = (name.ifEmpty { "colony-plate" })
        .replace(Regex("\\.[^.]+$"), "")
        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
    return base.ifEmpty { "colony-plate" }
}
