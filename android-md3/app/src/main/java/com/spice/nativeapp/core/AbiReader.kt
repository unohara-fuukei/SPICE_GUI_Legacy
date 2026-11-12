package com.spice.nativeapp.core

import java.io.ByteArrayInputStream

/** 最简 ABIF（.ab1 / .scf）读取：只取碱基序列与质量值，够做"导入测序色谱" */
object AbiReader {

    data class Trace(val bases: String, val qualities: IntArray)

    fun parse(bytes: ByteArray): Trace? {
        runCatching {
            val dir = directory(bytes) ?: return null
            val base = dir.firstOrNull { it.name == "PBAS" || it.name == "BASE" } ?: return null
            val bases = readChars(bytes, base)
            val qual = dir.firstOrNull { it.name == "PCON" || it.name == "CON2" }
            val qualities = qual?.let { readShorts(bytes, it) } ?: IntArray(0)
            if (bases.isEmpty()) return null
            return Trace(bases.uppercase().filter { it in "ACGTN" }, qualities)
        }
        return null
    }

    private data class Entry(val name: String, val type: Int, val num: Int, val size: Int, val dataSize: Int, val offset: Int)

    private fun directory(bytes: ByteArray): List<Entry>? {
        val b = ByteArrayInputStream(bytes)
        var magicOk = false
        var dirOffset = 0
        // ABIF 头：magic "ABIF"(4) + version(2) + ... 目录项偏移在固定位置
        if (bytes.size < 128) return null
        magicOk = bytes[0] == 'A'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
            bytes[2] == 'I'.code.toByte() && bytes[3] == 'F'.code.toByte()
        if (!magicOk) return null
        dirOffset = readInt(bytes, 26) // 目录项起始（相对 0）
        val entries = ArrayList<Entry>()
        var p = dirOffset
        var guard = 0
        while (p + 28 <= bytes.size && guard < 128) {
            val name = buildString {
                append(bytes[p].toInt().toChar())
                append(bytes[p + 1].toInt().toChar())
                append(bytes[p + 2].toInt().toChar())
                append(bytes[p + 3].toInt().toChar())
            }
            if (name.isBlank() || name.any { it.code == 0 }) break
            val num = readInt(bytes, p + 12)
            val type = readShort(bytes, p + 16)
            val size = readShort(bytes, p + 18)
            val dataSize = readInt(bytes, p + 20)
            val offset = readInt(bytes, p + 24)
            entries += Entry(name, type, num, size, dataSize, offset)
            p += 28
            guard++
        }
        b.close()
        return entries
    }

    private fun readChars(bytes: ByteArray, e: Entry): String {
        val start = if (e.dataSize <= 4) e.offset else readInt(bytes, e.offset)
        val count = (e.num * e.size).let { if (it <= 0) e.dataSize else it }
        if (start < 0 || start >= bytes.size) return ""
        val end = minOf(bytes.size, start + count)
        return String(bytes.copyOfRange(start, end), Charsets.ISO_8859_1)
    }

    private fun readShorts(bytes: ByteArray, e: Entry): IntArray {
        val start = if (e.dataSize <= 4) e.offset else readInt(bytes, e.offset)
        val count = e.num
        if (start < 0 || start + count * 2 > bytes.size) return IntArray(0)
        return IntArray(count) { i -> readShort(bytes, start + i * 2) }
    }

    private fun readInt(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun readShort(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) return 0
        return ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)
    }
}
