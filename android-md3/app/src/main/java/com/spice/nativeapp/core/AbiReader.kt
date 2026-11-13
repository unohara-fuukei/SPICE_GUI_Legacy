package com.spice.nativeapp.core

/**
 * ABIF（.ab1）色谱读取（对齐 main 分支 lib/genome/sangerTrace.ts 的 parseAb1）
 *
 * 目录项布局（ABIF v2 规范，28 字节/项）：
 *   tagName(4) tagNumber(4) elementType(2) elementSize(2) numElements(4) dataSize(4) dataOffset(4) dataHandle(4)
 *
 * 读取：PBAS1 碱基、PCON1 质量、PLOC1 峰位、DATA1-4 四色荧光通道（G/A/T/C）。
 */
object AbiReader {

    /** 四色峰通道；无色谱数据时 channels 为空（兼容纯文本导入） */
    data class Trace(
        val bases: String,
        val qualities: IntArray,
        val channels: Map<Char, IntArray> = emptyMap(), // 'G','A','T','C' → 原始荧光
        val peakIndices: IntArray = IntArray(0)          // 每个碱基的峰采样点位置
    ) {
        val hasPeaks: Boolean get() = channels.isNotEmpty()
    }

    fun parse(bytes: ByteArray): Trace? {
        runCatching {
            if (bytes.size < 128) return null
            if (!(bytes[0] == 'A'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
                    bytes[2] == 'I'.code.toByte() && bytes[3] == 'F'.code.toByte())) return null
            val entries = directory(bytes) ?: return null
            val base = entries.firstOrNull { it.name == "PBAS" && it.num == 1 } ?: return null
            val bases = readChars(bytes, base).uppercase().filter { it in "ACGTN" }
            if (bases.isEmpty()) return null
            val qual = entries.firstOrNull { it.name == "PCON" && it.num == 1 }
            val qualities = qual?.let { readShorts(bytes, it) } ?: IntArray(0)
            val ploc = entries.firstOrNull { it.name == "PLOC" && it.num == 1 }
            val peakIndices = ploc?.let { readShorts(bytes, it) } ?: IntArray(0)
            // DATA1..4 → G/A/T/C（对齐 main 的 getChannelName 映射）
            val channelMap = "GATC".mapIndexed { idx, ch ->
                val e = entries.firstOrNull { it.name == "DATA" && it.num == idx + 1 }
                ch to (e?.let { readShorts(bytes, it) } ?: IntArray(0))
            }.toMap().filterValues { it.isNotEmpty() }
            return Trace(bases, qualities, channelMap, peakIndices)
        }
        return null
    }

    private data class Entry(
        val name: String, val num: Int, val type: Int,
        val size: Int, val numElements: Int, val dataSize: Int, val dataOffset: Int
    )

    private fun directory(bytes: ByteArray): List<Entry>? {
        val dirOffset = readInt(bytes, 26)
        if (dirOffset <= 0 || dirOffset + 28 > bytes.size) return null
        val entries = ArrayList<Entry>()
        var p = dirOffset
        var guard = 0
        while (p + 28 <= bytes.size && guard < 256) {
            val name = buildString {
                append(bytes[p].toInt().toChar())
                append(bytes[p + 1].toInt().toChar())
                append(bytes[p + 2].toInt().toChar())
                append(bytes[p + 3].toInt().toChar())
            }
            if (name.any { it.code == 0 }) break
            entries += Entry(
                name,
                readInt(bytes, p + 4),      // tagNumber
                readShort(bytes, p + 8),    // elementType
                readShort(bytes, p + 10),   // elementSize
                readInt(bytes, p + 12),     // numElements
                readInt(bytes, p + 16),     // dataSize
                readInt(bytes, p + 20)      // dataOffset
            )
            p += 28
            guard++
        }
        return entries
    }

    /** 数据起始：dataSize ≤ 4 时数据内联在条目的 dataOffset 字段（相对条目 +20） */
    private fun dataStart(bytes: ByteArray, e: Entry): Int =
        if (e.dataSize in 1..4) e.dataOffset else e.dataOffset

    private fun readChars(bytes: ByteArray, e: Entry): String {
        val start = dataStart(bytes, e)
        val count = if (e.numElements > 0) e.numElements else e.dataSize
        if (start < 0 || start >= bytes.size) return ""
        val end = minOf(bytes.size, start + count)
        return String(bytes.copyOfRange(start, end), Charsets.ISO_8859_1)
    }

    private fun readShorts(bytes: ByteArray, e: Entry): IntArray {
        val start = dataStart(bytes, e)
        val count = e.numElements
        if (start < 0 || count <= 0 || start + count * 2 > bytes.size) return IntArray(0)
        return IntArray(count) { i -> readShort(bytes, start + i * 2) }
    }

    private fun readInt(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun readShort(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }
}
