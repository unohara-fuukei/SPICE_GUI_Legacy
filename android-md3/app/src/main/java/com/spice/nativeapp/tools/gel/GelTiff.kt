package com.spice.nativeapp.tools.gel

/**
 * 基线 TIFF 解码器。对应原版 decodeExposure 中的 utif2 依赖（Android
 * BitmapFactory 不支持 TIFF，故内置一个覆盖凝胶成像仪常见产物的解码器）。
 *
 * 支持：II/MM 字节序、第 0 个 IFD、未压缩(1)/PackBits(32773)/LZW(5)、
 * 8/16 位样本、白零/黑零灰度、RGB(A)、调色板（ColorMap）、LZW Predictor 2
 * （水平差分）、逐条带组织（StripOffsets/RowsPerStrip）、平面分离（Planar=2）。
 * 不支持：CCITT G3/G4、JPEG-in-TIFF、平铺（Tile）组织——README 如实披露。
 */
object GelTiff {

    class TiffException(message: String) : Exception(message)

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR_CONFIG = 284
    private const val TAG_PREDICTOR = 317
    private const val TAG_COLOR_MAP = 320

    class Decoded(val argb: IntArray, val width: Int, val height: Int)

    fun isTiff(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (
            (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
                (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte())
            )

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size < 8) throw TiffException("TIFF contains no image")
        val little = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> throw TiffException("Not a TIFF file")
        }
        val magic = u16(bytes, 2, little)
        if (magic != 42) throw TiffException("TIFF contains no image")
        val ifdOffset = u32(bytes, 4, little).toInt()
        if (ifdOffset <= 0 || ifdOffset + 2 > bytes.size) throw TiffException("TIFF contains no image")
        val entryCount = u16(bytes, ifdOffset, little)
        if (entryCount == 0) throw TiffException("TIFF contains no image")

        val tags = HashMap<Int, LongArray>()
        for (i in 0 until entryCount) {
            val base = ifdOffset + 2 + i * 12
            if (base + 12 > bytes.size) break
            val tag = u16(bytes, base, little)
            val type = u16(bytes, base + 2, little)
            val count = u32(bytes, base + 4, little).toInt()
            val typeSize = when (type) {
                3 -> 2   // SHORT
                4 -> 4   // LONG
                5 -> 8   // RATIONAL
                else -> 1 // BYTE/ASCII
            }
            val total = typeSize * count
            val values = LongArray(count)
            for (v in 0 until count) {
                val pos = if (total <= 4) base + 8 else u32(bytes, base + 8, little).toInt() + v * typeSize
                values[v] = when (type) {
                    3 -> u16(bytes, pos, little).toLong()
                    4 -> u32(bytes, pos, little)
                    else -> (bytes[pos].toInt() and 0xFF).toLong()
                }
            }
            tags[tag] = values
        }

        val width = tags[TAG_IMAGE_WIDTH]?.firstOrNull()?.toInt() ?: throw TiffException("TIFF contains no image")
        val height = tags[TAG_IMAGE_LENGTH]?.firstOrNull()?.toInt() ?: throw TiffException("TIFF contains no image")
        if (width <= 0 || height <= 0) throw TiffException("TIFF contains no image")
        val compression = tags[TAG_COMPRESSION]?.firstOrNull()?.toInt() ?: 1
        val photometric = tags[TAG_PHOTOMETRIC]?.firstOrNull()?.toInt() ?: 1
        val spp = tags[TAG_SAMPLES_PER_PIXEL]?.firstOrNull()?.toInt() ?: 1
        val planar = tags[TAG_PLANAR_CONFIG]?.firstOrNull()?.toInt() ?: 1
        val predictor = tags[TAG_PREDICTOR]?.firstOrNull()?.toInt() ?: 1
        val bitsArr = tags[TAG_BITS_PER_SAMPLE]
        val bps = if (spp == 1) (bitsArr?.firstOrNull()?.toInt() ?: 8) else (bitsArr?.getOrNull(0)?.toInt() ?: 8)
        if (bps != 8 && bps != 16) throw TiffException("Unsupported BitsPerSample $bps")
        if (spp != 1 && spp != 3 && spp != 4) throw TiffException("Unsupported SamplesPerPixel $spp")
        if (photometric == 2 && spp < 3) throw TiffException("RGB TIFF needs >= 3 samples")

        val stripOffsets = tags[TAG_STRIP_OFFSETS] ?: throw TiffException("TIFF contains no image")
        val stripCounts = tags[TAG_STRIP_BYTE_COUNTS] ?: LongArray(stripOffsets.size) { 0 }
        val rowsPerStrip = tags[TAG_ROWS_PER_STRIP]?.firstOrNull()?.toInt() ?: height
        if (rowsPerStrip <= 0) throw TiffException("TIFF contains no image")

        val bytesPerSample = bps / 8
        val shift = if (bps == 16) 8 else 0
        val mask = if (bps == 16) 0xFFFF else 0xFF
        val stripsPerPlane = if (planar == 2) Math.ceil(height.toDouble() / rowsPerStrip).toInt() else 0

        /** 每个平面的逐像素样本（未做 predictor 修正）。 */
        val planeBuffers = Array(spp) { IntArray(0) }
        val chunky = IntArray(width * height * spp)
        var chunkyWritten = 0

        for (s in stripOffsets.indices) {
            val off = stripOffsets[s].toInt()
            val len = stripCounts[s].toInt()
            if (off < 0 || len <= 0 || off + len > bytes.size) {
                if (planar == 2) continue else break
            }
            val strip = bytes.copyOfRange(off, off + len)
            val expanded: ByteArray = when (compression) {
                1 -> strip
                5 -> lzwDecode(strip)
                32773 -> packBitsDecode(strip)
                else -> throw TiffException("Unsupported TIFF compression $compression")
            }
            if (planar == 2) {
                val plane = (s / stripsPerPlane).coerceIn(0, spp - 1)
                val rowStart = (s % stripsPerPlane) * rowsPerStrip
                val rows = minOf(rowsPerStrip, height - rowStart)
                if (rows <= 0) continue
                if (planeBuffers[plane].isEmpty()) planeBuffers[plane] = IntArray(width * height)
                val buf = planeBuffers[plane]
                for (k in 0 until width * rows) {
                    val byteIdx = k * bytesPerSample
                    if (byteIdx + bytesPerSample > expanded.size) break
                    val v = readSample(expanded, byteIdx, bytesPerSample, little)
                    val x = k % width
                    val y = rowStart + k / width
                    buf[y * width + x] = v and mask
                }
            } else {
                for (k in 0 until width * height * spp) {
                    if (chunkyWritten >= chunky.size) break
                    val byteIdx = k * bytesPerSample
                    if (byteIdx + bytesPerSample > expanded.size) break
                    chunky[chunkyWritten++] = readSample(expanded, byteIdx, bytesPerSample, little) and mask
                }
            }
        }

        // LZW Predictor 2：水平差分还原（逐行、逐通道）。
        if (predictor == 2 && compression == 5) {
            if (planar == 2) {
                for (ch in 0 until spp) {
                    val buf = planeBuffers[ch]
                    for (y in 0 until height) {
                        for (x in 1 until width) {
                            val pi = y * width + x
                            buf[pi] = (buf[pi] + buf[pi - 1]) and mask
                        }
                    }
                }
            } else {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        for (ch in 0 until spp) {
                            if (x > 0) {
                                val pi = (y * width + x) * spp + ch
                                val prev = (y * width + x - 1) * spp + ch
                                chunky[pi] = (chunky[pi] + chunky[prev]) and mask
                            }
                        }
                    }
                }
            }
        }

        fun sampleAt(pi: Int, ch: Int): Int {
            val raw = if (planar == 2) {
                val buf = planeBuffers.getOrNull(ch) ?: return 0
                if (pi < buf.size) buf[pi] else 0
            } else {
                val idx = pi * spp + ch
                if (idx < chunky.size) chunky[idx] else 0
            }
            return (raw shr shift) and 0xFF
        }

        val out = IntArray(width * height)
        when (photometric) {
            0 -> for (pi in out.indices) {
                val v = 255 - sampleAt(pi, 0)
                out[pi] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            1 -> for (pi in out.indices) {
                val v = sampleAt(pi, 0)
                out[pi] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            2 -> for (pi in out.indices) {
                out[pi] = (0xFF shl 24) or (sampleAt(pi, 0) shl 16) or (sampleAt(pi, 1) shl 8) or sampleAt(pi, 2)
            }
            3 -> {
                val cm = tags[TAG_COLOR_MAP]
                for (pi in out.indices) {
                    val v = sampleAt(pi, 0)
                    val r: Int
                    val g: Int
                    val b: Int
                    if (cm != null && cm.size >= 768) {
                        r = ((cm[v].toInt() shr 8) and 0xFF)
                        g = ((cm[256 + v].toInt() shr 8) and 0xFF)
                        b = ((cm[512 + v].toInt() shr 8) and 0xFF)
                    } else {
                        r = v; g = v; b = v
                    }
                    out[pi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            else -> for (pi in out.indices) {
                val v = sampleAt(pi, 0)
                out[pi] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Decoded(out, width, height)
    }

    private fun readSample(b: ByteArray, byteIdx: Int, bytesPerSample: Int, little: Boolean): Int =
        if (bytesPerSample == 2) {
            if (little) ((b[byteIdx + 1].toInt() and 0xFF) shl 8) or (b[byteIdx].toInt() and 0xFF)
            else ((b[byteIdx].toInt() and 0xFF) shl 8) or (b[byteIdx + 1].toInt() and 0xFF)
        } else b[byteIdx].toInt() and 0xFF

    /** TIFF LZW（MSB 位序，clear=256 / EOI=257），标准字典展开实现。 */
    private fun lzwDecode(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(1024, data.size * 4))
        var dict = Array(1 shl 12) { IntArray(2) } // [prefix, suffix]，index >= 258 有效
        var codeWidth = 9
        var dictSize = 258
        var prev = -1
        var bitPos = 0

        fun readCode(): Int {
            if (bitPos + codeWidth > data.size * 8) return 257
            var code = 0
            for (k in 0 until codeWidth) {
                val byteIdx = (bitPos + k) / 8
                val bit = (data[byteIdx].toInt() shr (7 - (bitPos + k) % 8)) and 1
                code = (code shl 1) or bit
            }
            bitPos += codeWidth
            return code
        }

        fun emit(code: Int): Int {
            // 展开串到 out，返回首字节
            val stack = java.util.ArrayDeque<Int>()
            var c = code
            if (c >= 258) {
                var guard = 0
                while (c >= 258 && guard < (1 shl 12)) {
                    stack.push(dict[c][1])
                    c = dict[c][0]
                    guard++
                }
            }
            val first = c and 0xFF
            stack.push(first)
            val it = stack.descendingIterator()
            while (it.hasNext()) out.write(it.next())
            return first
        }

        while (true) {
            val code = readCode()
            if (code == 257) break
            if (code == 256) {
                codeWidth = 9
                dictSize = 258
                prev = -1
                continue
            }
            if (prev == -1) {
                if (code in 0..255) {
                    out.write(code)
                    prev = code
                } else break
                continue
            }
            val first: Int
            if (code < dictSize) {
                first = emit(code)
            } else {
                // KwKwK：code == dictSize
                first = emit(prev)
                out.write(first)
            }
            if (dictSize < (1 shl 12)) {
                dict[dictSize][0] = prev
                dict[dictSize][1] = first
                dictSize++
                if (dictSize == (1 shl codeWidth) - 1 && codeWidth < 12) codeWidth++
            }
            prev = code
        }
        return out.toByteArray()
    }

    /** PackBits：与 TIFF 规范一致的按段游程编码。 */
    private fun packBitsDecode(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val n = data[i].toInt()
            i++
            if (n >= 0) {
                val count = n + 1
                for (k in 0 until count) {
                    if (i < data.size) out.write(data[i].toInt() and 0xFF)
                    i++
                }
            } else if (n != -128) {
                if (i < data.size) {
                    val b = data[i].toInt() and 0xFF
                    i++
                    repeat(1 - n) { out.write(b) }
                }
            }
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, pos: Int, little: Boolean): Int =
        if (little) (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
        else ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, pos: Int, little: Boolean): Long =
        if (little)
            (b[pos].toInt() and 0xFF).toLong() or
                ((b[pos + 1].toInt() and 0xFF).toLong() shl 8) or
                ((b[pos + 2].toInt() and 0xFF).toLong() shl 16) or
                ((b[pos + 3].toInt() and 0xFF).toLong() shl 24)
        else
            ((b[pos].toInt() and 0xFF).toLong() shl 24) or
                ((b[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                ((b[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                (b[pos + 3].toInt() and 0xFF).toLong()
}
