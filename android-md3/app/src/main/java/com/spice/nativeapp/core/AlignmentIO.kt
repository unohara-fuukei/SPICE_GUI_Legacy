package com.spice.nativeapp.core

/**
 * 比对文件导入（对齐 main 分支 advancedTools.ts 的 parseAlignmentFile）：
 * 支持 Clustal / FASTA / NEXUS / PHYLIP / MSF / Stockholm 六种格式的自动检测与解析。
 */
object AlignmentIO {

    data class Row(val id: String, val name: String, val seq: String)
    data class ImportedAlignment(val rows: List<Row>, val format: String)

    /** 与 main 的 detectAlignmentFormat 一致：按头部特征判定格式，未知回退 FASTA */
    fun detectFormat(text: String): String {
        val header = text.take(200).uppercase()
        return when {
            header.contains("CLUSTAL") -> "clustal"
            header.contains("#NEXUS") || header.contains("#NEX") -> "nexus"
            header.contains("#STOCKHOLM") -> "stockholm"
            header.contains("MSF:") -> "msf"
            header.contains(">") -> "fasta"
            Regex("^\\s*\\d+\\s+\\d+\\s*$").containsMatchIn(text.take(20)) -> "phylip"
            else -> "fasta"
        }
    }

    fun parse(text: String, format: String? = null): ImportedAlignment {
        val detected = format ?: detectFormat(text)
        return when (detected) {
            "clustal" -> parseClustal(text)
            "nexus" -> parseNexus(text)
            "phylip" -> parsePhylip(text)
            "msf" -> parseMsf(text)
            "stockholm" -> parseStockholm(text)
            else -> parseFasta(text)
        }
    }

    private fun parseFasta(text: String): ImportedAlignment {
        val rows = mutableListOf<Row>()
        var currentName = ""
        var currentSeq = StringBuilder()
        var idx = 0
        for (line in text.lines()) {
            if (line.startsWith(">")) {
                if (currentName.isNotEmpty()) rows += Row("seq${idx++}", currentName, currentSeq.toString())
                currentName = line.drop(1).trim().split(Regex("\\s+")).firstOrNull() ?: ""
                currentSeq = StringBuilder()
            } else {
                currentSeq.append(line.trim())
            }
        }
        if (currentName.isNotEmpty()) rows += Row("seq${idx++}", currentName, currentSeq.toString())
        return ImportedAlignment(rows, "fasta")
    }

    private fun parseClustal(text: String): ImportedAlignment {
        val rows = LinkedHashMap<String, StringBuilder>()
        val ids = LinkedHashMap<String, String>()
        var idx = 0
        val lines = text.lines().filter {
            it.isNotBlank() && !it.uppercase().startsWith("CLUSTAL") && !it.startsWith(" ")
        }
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0]
                val seq = parts[1]
                if (!rows.containsKey(name)) {
                    rows[name] = StringBuilder()
                    ids[name] = "seq${idx++}"
                }
                rows[name]!!.append(seq)
            }
        }
        return ImportedAlignment(rows.map { (n, s) -> Row(ids[n] ?: "", n, s.toString()) }, "clustal")
    }

    private fun parseNexus(text: String): ImportedAlignment {
        val rows = LinkedHashMap<String, StringBuilder>()
        val ids = LinkedHashMap<String, String>()
        var idx = 0
        val matrixMatch = Regex("MATRIX\\s*([\\s\\S]*?)\\s*;", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1) ?: return ImportedAlignment(emptyList(), "nexus")
        for (line in matrixMatch.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0].replace(Regex("[\"']"), "")
                val seq = parts[1]
                if (!rows.containsKey(name)) {
                    rows[name] = StringBuilder()
                    ids[name] = "seq${idx++}"
                }
                rows[name]!!.append(seq)
            }
        }
        return ImportedAlignment(rows.map { (n, s) -> Row(ids[n] ?: "", n, s.toString()) }, "nexus")
    }

    private fun parsePhylip(text: String): ImportedAlignment {
        val lines = text.trim().lines()
        if (lines.size < 2) return ImportedAlignment(emptyList(), "phylip")
        val rows = mutableListOf<Row>()
        var idx = 0
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0].take(10)
                val seq = parts.drop(1).joinToString("")
                rows += Row("seq${idx++}", name, seq)
            }
        }
        return ImportedAlignment(rows, "phylip")
    }

    private fun parseMsf(text: String): ImportedAlignment {
        val rows = LinkedHashMap<String, StringBuilder>()
        val ids = LinkedHashMap<String, String>()
        var idx = 0
        var inSeq = false
        for (line in text.lines()) {
            if (line.contains("//")) { inSeq = true; continue }
            if (!inSeq) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0]
                val seq = parts.drop(1).joinToString("")
                if (!rows.containsKey(name)) {
                    rows[name] = StringBuilder()
                    ids[name] = "seq${idx++}"
                }
                rows[name]!!.append(seq)
            }
        }
        return ImportedAlignment(rows.map { (n, s) -> Row(ids[n] ?: "", n, s.toString()) }, "msf")
    }

    private fun parseStockholm(text: String): ImportedAlignment {
        val rows = LinkedHashMap<String, StringBuilder>()
        val ids = LinkedHashMap<String, String>()
        var idx = 0
        for (line in text.lines()) {
            if (line.startsWith("#") || line.startsWith("//")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[0]
                val seq = parts[1]
                if (!rows.containsKey(name)) {
                    rows[name] = StringBuilder()
                    ids[name] = "seq${idx++}"
                }
                rows[name]!!.append(seq)
            }
        }
        return ImportedAlignment(rows.map { (n, s) -> Row(ids[n] ?: "", n, s.toString()) }, "stockholm")
    }
}
