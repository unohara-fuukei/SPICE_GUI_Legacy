package com.spice.nativeapp

import com.spice.nativeapp.core.FigureTiff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FigureTiffTest —— tiff.ts 1:1 编码层校验（JVM 纯算法）。
 *
 * 2×2 图像手算头部字节；大结构（IFD tag 升序、strip 偏移回填、
 * rational 约分、flatten 白底合成）逐一断言。
 */
class FigureTiffTest {

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** 2×2 RGBA：红/绿/蓝/白。 */
    private val rgba2x2 = byteArrayOf(
        0xFF.toByte(), 0, 0, 0xFF.toByte(),
        0, 0xFF.toByte(), 0, 0xFF.toByte(),
        0, 0, 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
    )

    @Test
    fun header_is_little_endian_baseline_tiff() {
        val t = FigureTiff.encodeTiffRgb(rgba2x2, 2, 2, 300.0, "d")
        assertEquals(0x49, t[0].toInt())              // 'I'
        assertEquals(0x49, t[1].toInt())              // 'I'
        assertEquals(42, le16(t, 2))                  // magic 42
        assertEquals(8, le32(t, 4))                  // first IFD at 8
    }

    @Test
    fun ifd_has_16_ascending_tags_with_core_values() {
        val t = FigureTiff.encodeTiffRgb(rgba2x2, 2, 2, 300.0, "d")
        val count = le16(t, 8)
        assertEquals(16, count)
        var p = 10
        val tags = mutableListOf<Int>()
        repeat(count) {
            tags.add(le16(t, p))
            p += 12
        }
        assertEquals(tags.sorted(), tags)                        // 升序
        assertEquals(0, le32(t, p))                              // next IFD = 0
        fun entry(tag: Int): ByteArray {
            var q = 10
            repeat(count) {
                if (le16(t, q) == tag) return t.copyOfRange(q, q + 12)
                q += 12
            }
            fail("tag $tag missing")
            return ByteArray(0)
        }
        // 256/257 = 宽高；262=RGB(2)；277=3 samples；278 strip 数 = height；296 单位=2(inch)
        assertEquals(2, le32(entry(256), 8))
        assertEquals(2, le32(entry(257), 8))
        assertEquals(2, le16(entry(262), 8))
        assertEquals(3, le16(entry(277), 8))
        assertEquals(2, le32(entry(278), 8))
        assertEquals(2, le16(entry(296), 8))
        // 279 StripByteCounts = 2*2*3
        assertEquals(12, le32(entry(279), 8))
        // 282/283 XRes/YRes：300 → rational 300/1，偏移指向 8 字节 300,1
        val xResOff = le32(entry(282), 8)
        assertEquals(300, le32(t, xResOff))
        assertEquals(1, le32(t, xResOff + 4))
    }

    @Test
    fun strip_pixels_are_rgb_dropped_alpha() {
        val t = FigureTiff.encodeTiffRgb(rgba2x2, 2, 2, 300.0, "d")
        var p = 10
        var stripOff = 0
        repeat(16) {
            if (le16(t, p) == 273) stripOff = le32(t, p + 8)
            p += 12
        }
        val expected = byteArrayOf(
            0xFF.toByte(), 0, 0,
            0, 0xFF.toByte(), 0,
            0, 0, 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
        assertArrayEquals(expected, t.copyOfRange(stripOff, stripOff + 12))
        assertEquals(t.size, stripOff + 12)           // strip 到文件尾
    }

    @Test
    fun rational_reduces_integer_and_fraction() {
        assertEquals(300L to 1L, FigureTiff.reduceRational(300.0))
        // 299.5 → 299500/1000 → gcd 500 → 599/2
        assertEquals(599L to 2L, FigureTiff.reduceRational(299.5))
    }

    @Test
    fun flatten_composites_alpha_onto_white() {
        // 半透明红（128/255）→ round(255*128/255 + 255*127/255) = round(128+127)=255? 按 JS 语义：
        // alpha=128/255, inverse=127/255 → r = round(255*128/255 + 255*127/255) = round(255*(255/255))=255
        val half = byteArrayOf(0xFF.toByte(), 0, 0, 128.toByte())
        val out = FigureTiff.flattenOnWhite(half)
        assertEquals(255, out[0].toInt() and 0xFF)    // 红通道：全 alpha 加白即白
        assertEquals(127, out[1].toInt() and 0xFF)     // 0*alpha + 255*127/255 = 127
        assertEquals(127, out[2].toInt() and 0xFF)
        assertEquals(255, out[3].toInt() and 0xFF)    // alpha 置 255
        // 完全透明 → 纯白
        val gone = FigureTiff.flattenOnWhite(byteArrayOf(10, 20, 30, 0))
        assertEquals(255, gone[0].toInt() and 0xFF)
        assertEquals(255, gone[1].toInt() and 0xFF)
        assertEquals(255, gone[2].toInt() and 0xFF)
    }

    @Test
    fun invalid_dimensions_and_buffer_rejected() {
        try {
            FigureTiff.encodeTiffRgb(ByteArray(16), 0, 4)
            fail("expected TiffException")
        } catch (_: FigureTiff.TiffException) { }
        try {
            FigureTiff.encodeTiffRgb(ByteArray(4), 2, 2)
            fail("expected TiffException")
        } catch (_: FigureTiff.TiffException) { }
        try {
            FigureTiff.encodeTiffRgb(ByteArray(16), 2, 2, dpi = 0.0)
            fail("expected TiffException")
        } catch (_: FigureTiff.TiffException) { }
    }

    @Test
    fun argb_ints_convert_to_rgba_byte_order() {
        val rgba = FigureTiff.argbIntsToRgba(intArrayOf(0xFFFF0000.toInt())) // opaque red
        assertEquals(0xFF.toByte(), rgba[0])
        assertEquals(0.toByte(), rgba[1])
        assertEquals(0.toByte(), rgba[2])
        assertEquals(0xFF.toByte(), rgba[3])
    }

    @Test
    fun datetime_format_padded() {
        val d = java.util.Date(1262304000000L) // 2010-01-01 00:00:00 UTC
        val s = FigureTiff.tiffDateTime(d)
        assertTrue("expected yyyy:MM:dd HH:mm:ss, got $s", Regex("""^\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}$""").matches(s))
    }
}
