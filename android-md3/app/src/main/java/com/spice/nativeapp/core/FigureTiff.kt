package com.spice.nativeapp.core

import java.io.ByteArrayOutputStream

/**
 * FigureTiff —— main tools/figure/tiff.ts 的 1:1 移植。
 *
 * Baseline TIFF writer：little-endian、8-bit RGB、单条未压缩 strip、
 * 无 extra samples；元数据显式（DPI rational、Description、Software、
 * DateTime）。tag 升序排列，>4 字节载荷对齐到偶数偏移。
 *
 * renderTiffFromBitmap 对应 main renderTiff：栅格化后 flattenOnWhite
 * （透明角落合成到白底，alpha 丢弃）再编码。
 */
object FigureTiff {

    const val SOFTWARE = "SPICE Figure Studio"

    // ------------------------------------------------ 基础写码（JS DataView LE 语义）

    private fun u16(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (i in values.indices) {
            out[i * 2] = (values[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((values[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun u32(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            out[i * 4] = (values[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((values[i] shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((values[i] shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((values[i] shr 24) and 0xFF).toByte()
        }
        return out
    }

    /** ASCII tag 载荷，按 TIFF 规范 NUL 结尾。 */
    private fun ascii(value: String?): ByteArray {
        val text = (value ?: "").toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(text.size + 1)
        System.arraycopy(text, 0, bytes, 0, text.size)
        return bytes
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0L) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return if (x == 0L) 1L else x
    }

    /** main reduceRational：整数 → n/1；否则 ×1000 后约分。 */
    fun reduceRational(value: Double): Pair<Long, Long> {
        val rounded = Math.round(value)
        if (kotlin.math.abs(value - rounded) < 1e-9 && rounded > 0) return Pair(rounded, 1L)
        var numerator = Math.round(value * 1000)
        var denominator = 1000L
        val divisor = gcd(numerator, denominator)
        numerator /= divisor
        denominator /= divisor
        return Pair(numerator, denominator)
    }

    private fun rational(value: Double): ByteArray {
        val (numerator, denominator) = reduceRational(value)
        return u32(intArrayOf(numerator.toInt(), denominator.toInt()))
    }

    /** main tiffDateTime："yyyy:MM:dd HH:mm:ss"（可注入以便测试）。 */
    fun tiffDateTime(now: java.util.Date = java.util.Date()): String {
        val pad = { v: Int -> if (v < 10) "0$v" else v.toString() }
        val cal = java.util.Calendar.getInstance().apply { time = now }
        return "${cal.get(java.util.Calendar.YEAR)}:${pad(cal.get(java.util.Calendar.MONTH) + 1)}:${pad(cal.get(java.util.Calendar.DAY_OF_MONTH))} " +
            "${pad(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${pad(cal.get(java.util.Calendar.MINUTE))}:${pad(cal.get(java.util.Calendar.SECOND))}"
    }

    private data class IfdEntry(val tag: Int, val type: Int, val count: Int, var bytes: ByteArray)

    class TiffException(message: String) : Exception(message)

    /**
     * RGBA（R,G,B,A 字节序，main Uint8ClampedArray 语义）编码为未压缩 baseline RGB TIFF。
     */
    fun encodeTiffRgb(rgba: ByteArray, width: Int, height: Int, dpi: Double = 300.0, description: String = ""): ByteArray {
        if (width <= 0 || height <= 0) throw TiffException("Invalid TIFF dimensions: $width x $height")
        if (dpi.isFinite().not() || dpi <= 0) throw TiffException("Invalid TIFF resolution: $dpi")
        val expected = width * height * 4
        if (rgba.size != expected) throw TiffException("Invalid RGBA buffer: expected $expected bytes, got ${rgba.size}")

        val samples = 3
        val pixels = ByteArray(width * height * samples)
        var source = 0
        var target = 0
        while (source < rgba.size) {
            pixels[target] = rgba[source]
            pixels[target + 1] = rgba[source + 1]
            pixels[target + 2] = rgba[source + 2]
            source += 4
            target += samples
        }

        val bitsPerSample = u16(intArrayOf(8, 8, 8))
        val sampleFormat = u16(intArrayOf(1, 1, 1))
        val descriptionBytes = ascii(description)
        val softwareBytes = ascii(SOFTWARE)
        val dateBytes = ascii(tiffDateTime())

        // tag 升序；273（StripOffsets）占位，写出前回填。
        val entries = mutableListOf(
            IfdEntry(256, 4, 1, u32(intArrayOf(width))),
            IfdEntry(257, 4, 1, u32(intArrayOf(height))),
            IfdEntry(258, 3, 3, bitsPerSample),
            IfdEntry(259, 3, 1, u16(intArrayOf(1))),
            IfdEntry(262, 3, 1, u16(intArrayOf(2))),
            IfdEntry(270, 2, descriptionBytes.size, descriptionBytes),
            IfdEntry(273, 4, 1, u32(intArrayOf(0))),
            IfdEntry(277, 3, 1, u16(intArrayOf(samples))),
            IfdEntry(278, 4, 1, u32(intArrayOf(height))),
            IfdEntry(279, 4, 1, u32(intArrayOf(pixels.size))),
            IfdEntry(282, 5, 1, rational(dpi)),
            IfdEntry(283, 5, 1, rational(dpi)),
            IfdEntry(296, 3, 1, u16(intArrayOf(2))),
            IfdEntry(305, 2, softwareBytes.size, softwareBytes),
            IfdEntry(306, 2, dateBytes.size, dateBytes),
            IfdEntry(339, 3, 3, sampleFormat)
        )

        val ifdOffset = 8
        val ifdSize = 2 + entries.size * 12 + 4
        var cursor = ifdOffset + ifdSize
        val placements = entries.map { entry ->
            if (entry.bytes.size <= 4) return@map Pair(true, 0)
            if (cursor % 2 != 0) cursor++
            val offset = cursor
            cursor += entry.bytes.size
            if (cursor % 2 != 0) cursor++
            Pair(false, offset)
        }
        val stripOffset = cursor
        val total = stripOffset + pixels.size
        val out = ByteArray(total)

        // 回填 strip offset。
        val stripEntry = entries.first { it.tag == 273 }
        stripEntry.bytes = u32(intArrayOf(stripOffset))

        out[0] = 0x49 // 'I'
        out[1] = 0x49 // 'I'（little-endian）
        writeU16(out, 2, 42)
        writeU32(out, 4, ifdOffset)
        writeU16(out, ifdOffset, entries.size)

        var pointer = ifdOffset + 2
        entries.forEachIndexed { index, entry ->
            writeU16(out, pointer, entry.tag)
            writeU16(out, pointer + 2, entry.type)
            writeU32(out, pointer + 4, entry.count)
            val (inline, offset) = placements[index]
            if (inline) {
                System.arraycopy(entry.bytes, 0, out, pointer + 8, entry.bytes.size)
            } else {
                writeU32(out, pointer + 8, offset)
                System.arraycopy(entry.bytes, 0, out, offset, entry.bytes.size)
            }
            pointer += 12
        }
        writeU32(out, pointer, 0) // next IFD

        System.arraycopy(pixels, 0, out, stripOffset, pixels.size)
        return out
    }

    /** 透明像素直接合成到白页（main flattenOnWhite），alpha 置 255。
     *  main 赋值目标是 Uint8ClampedArray——越界自动截断到 [0,255]。 */
    fun flattenOnWhite(rgba: ByteArray): ByteArray {
        val out = ByteArray(rgba.size)
        var i = 0
        while (i < rgba.size) {
            // Kotlin Byte 有符号；main Uint8ClampedArray 元素为 0..255 无符号。
            val alpha = (rgba[i + 3].toInt() and 0xFF) / 255.0
            val inverse = 1 - alpha
            out[i] = Math.round((rgba[i].toInt() and 0xFF) * alpha + 255 * inverse).toInt().coerceIn(0, 255).toByte()
            out[i + 1] = Math.round((rgba[i + 1].toInt() and 0xFF) * alpha + 255 * inverse).toInt().coerceIn(0, 255).toByte()
            out[i + 2] = Math.round((rgba[i + 2].toInt() and 0xFF) * alpha + 255 * inverse).toInt().coerceIn(0, 255).toByte()
            out[i + 3] = 255.toByte()
            i += 4
        }
        return out
    }

    /** Android ARGB_8888 int 像素 → RGBA 字节序。 */
    fun argbIntsToRgba(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            out[i * 4] = ((argb[i] shr 16) and 0xFF).toByte()
            out[i * 4 + 1] = ((argb[i] shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = (argb[i] and 0xFF).toByte()
            out[i * 4 + 3] = ((argb[i] shr 24) and 0xFF).toByte()
        }
        return out
    }

    /**
     * main renderTiff 的安卓等价物：栅格化画布（透明背景、96dpi 文档像素口径）
     * → 白底合成 → 300 dpi 标签编码。
     *
     * 尺寸守卫与 main renderSvgToCanvas 相同：px 总数 > 40,000,000 拒绝。
     */
    fun renderTiffFromCanvas(
        docWidthPx: Int, docHeightPx: Int, dpi: Double = 300.0,
        render: (canvas: android.graphics.Canvas) -> Unit,
        description: String
    ): ByteArray {
        val scale = maxOf(0.01, dpi / 96)
        val width = maxOf(1, Math.round(docWidthPx * scale).toInt())
        val height = maxOf(1, Math.round(docHeightPx * scale).toInt())
        if (width.toLong() * height > 40_000_000L) {
            throw TiffException("Figure is too large to rasterise at ${dpi.toInt()} dpi ($width x $height px). Lower the DPI or page size.")
        }
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale.toFloat(), scale.toFloat())
        render(canvas)
        val argb = IntArray(width * height)
        bmp.getPixels(argb, 0, width, 0, 0, width, height)
        bmp.recycle()
        val flattened = flattenOnWhite(argbIntsToRgba(argb))
        return encodeTiffRgb(flattened, width, height, dpi, description)
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
