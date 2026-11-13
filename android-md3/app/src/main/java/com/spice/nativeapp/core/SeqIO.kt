package com.spice.nativeapp.core

/** FASTA / GenBank 文本解析（导入用） */
object SeqIO {

    fun parse(text: String): SeqRecord? {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") && Spiceg.isSpiceg(text) -> Spiceg.parseFull(text)?.record
            trimmed.startsWith("LOCUS") -> parseGenBank(text)
            trimmed.startsWith("ID ") || trimmed.contains("\nSQ ") -> parseEmbl(text)
            trimmed.startsWith("@") -> parseFastq(text)
            trimmed.startsWith(">") -> parseFasta(text)
            else -> {
                val seq = Bio.cleanSequence(text)
                if (seq.length < 10) null
                else SeqRecord(name = "导入序列", sequence = seq, circular = false)
            }
        }
    }

    fun parseFasta(text: String): SeqRecord? {
        var name = "未命名"
        val sb = StringBuilder()
        text.lines().forEach { raw ->
            val l = raw.trim()
            when {
                l.startsWith(">") -> if (sb.isEmpty()) name = l.removePrefix(">").trim().ifBlank { "未命名" }
                l.isNotEmpty() -> sb.append(l.filter { it.isLetter() })
            }
        }
        val seq = sb.toString().uppercase()
        return if (seq.length < 5) null else SeqRecord(name = name, sequence = seq, circular = false)
    }

    /** FASTQ：@header + 序列 + + + 质量行 */
    fun parseFastq(text: String): SeqRecord? {
        val lines = text.lines().filter { it.isNotBlank() }
        var name = "未命名"
        val sb = StringBuilder()
        var qualSum = 0L
        var qualCount = 0
        var i = 0
        while (i + 3 < lines.size + 0) {
            val header = lines.getOrNull(i) ?: break
            if (!header.startsWith("@")) { i++; continue }
            if (sb.isEmpty()) name = header.removePrefix("@").substringBefore(" ").ifBlank { "未命名" }
            val seq = lines.getOrNull(i + 1)?.filter { it.isLetter() } ?: ""
            sb.append(seq)
            val qual = lines.getOrNull(i + 3)
            if (qual != null) { qual.forEach { qualSum += it.code - 33 }; qualCount += qual.length }
            i += 4
        }
        if (sb.isEmpty()) return null
        val meanQ = if (qualCount > 0) qualSum.toDouble() / qualCount else null
        return SeqRecord(
            name = name,
            sequence = sb.toString().uppercase(),
            circular = false,
            features = emptyList(),
            description = "FASTQ 导入" + (meanQ?.let { " · 平均质量 Q${"%.1f".format(it)}" } ?: "")
        )
    }

    /** EMBL：ID 行 + SQ 之后的序列 */
    fun parseEmbl(text: String): SeqRecord? {
        var name = "未命名"
        var circular = false
        val sb = StringBuilder()
        var inSeq = false
        text.lines().forEach { raw ->
            val l = raw.trimEnd()
            when {
                l.startsWith("ID ") -> {
                    name = l.removePrefix("ID").trim().substringBefore(";").trim().ifBlank { "未命名" }
                    if (l.contains("circular")) circular = true
                }
                l.startsWith("SQ ") -> inSeq = true
                l.startsWith("//") -> inSeq = false
                inSeq -> sb.append(l.filter { it.isLetter() || it == '*' }.replace("*", ""))
            }
        }
        val seq = sb.toString().uppercase()
        if (seq.length < 10) return null
        return SeqRecord(name, seq, circular, emptyList(), "EMBL 导入")
    }

    fun toFastq(rec: SeqRecord, quality: Int = 30): String {
        val q = (quality + 33).toChar().toString()
        return "@${rec.name}\n${rec.sequence}\n+\n${q.repeat(rec.length)}\n"
    }

    fun toEmbl(rec: SeqRecord): String {
        val sb = StringBuilder()
        sb.append("ID   ${rec.name.take(16).padEnd(16)} standard; DNA; ${if (rec.circular) "circular" else "linear"}; ${rec.length} BP.\n")
        sb.append("SQ   Sequence ${rec.length} BP;\n")
        rec.sequence.lowercase().chunked(60).forEach { chunk ->
            sb.append("     ").append(chunk.chunked(10).joinToString(" ")).append("\n")
        }
        sb.append("//\n")
        return sb.toString()
    }

    fun parseGenBank(text: String): SeqRecord? {
        var name = "未命名"
        var circular = false
        var declaredLen = 0
        val features = mutableListOf<Feature>()
        val seqSb = StringBuilder()
        var inFeatures = false
        var inOrigin = false
        var currentKey = ""
        var currentStart = 0
        var currentEnd = 0
        var currentStrand = 1
        var currentName = ""
        var currentType = "misc"

        fun flushFeature() {
            if (currentKey.isNotBlank() && currentName.isNotBlank() && currentStart > 0) {
                features.add(Feature(currentName, currentType, currentStart, maxOf(currentStart, currentEnd), currentStrand))
            }
            currentKey = ""; currentStart = 0; currentEnd = 0; currentStrand = 1; currentName = ""
        }

        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("LOCUS") -> {
                    val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.size > 1) name = parts[1]
                    parts.forEach { p -> p.toIntOrNull()?.let { if (it > 50) declaredLen = it } }
                    circular = line.contains("circular", true)
                }
                line.startsWith("ORIGIN") -> { inOrigin = true; inFeatures = false }
                line.startsWith("FEATURES") -> { inFeatures = true }
                inOrigin -> seqSb.append(line.filter { it.isLetter() })
                inFeatures && line.length > 5 && line.take(5).trim().isEmpty() && line[5] != ' ' -> {
                    // 新的特征条目（第 6 列起为 key）
                    flushFeature()
                    currentKey = line.substring(5).trim().split(Regex("\\s+"))[0]
                    val rest = line.substring(5 + currentKey.length).trim()
                    val nums = Regex("(complement\\()?<?(\\d+)[^\\d]*(\\d+)?").find(rest)
                    if (nums != null) {
                        currentStart = nums.groupValues[2].toIntOrNull() ?: 0
                        currentEnd = nums.groupValues.getOrNull(3)?.toIntOrNull() ?: currentStart
                    }
                    currentStrand = if (rest.contains("complement")) -1 else 1
                    currentType = currentKey.lowercase().replace(" ", "_")
                }
                inFeatures && line.length > 21 && currentKey.isNotBlank() -> {
                    val qual = line.substring(21).trim()
                    if (qual.startsWith("/")) {
                        val body = qual.removePrefix("/")
                        val key = body.substringBefore("=")
                        val value = body.substringAfter("=", "").trim().trim('"')
                        if (key == "gene" || key == "label" || key == "product" || key == "note") {
                            currentName = value.ifBlank { currentName }
                        }
                    }
                }
            }
        }
        val seq = seqSb.toString().uppercase()
        if (seq.length < 5) return null
        if (features.isEmpty()) features.add(Feature(name, "misc", 1, seq.length, 1))
        return SeqRecord(name = name, sequence = seq, circular = circular, features = features.map {
            it.copy(name = it.name.ifBlank { it.type })
        })
    }

    /** 导出 FASTA（60 bp 换行） */
    fun toFasta(rec: SeqRecord): String {
        val header = ">${rec.name}${if (rec.description.isNotBlank()) " ${rec.description}" else ""}"
        val body = rec.sequence.chunked(60).joinToString("\n")
        return "$header\n$body\n"
    }

    /** 导出 GenBank（LOCUS + FEATURES + ORIGIN） */
    fun toGenBank(rec: SeqRecord): String {
        val sb = StringBuilder()
        sb.append("LOCUS       ${rec.name.take(20).padEnd(20)} ${rec.length} bp ${if (rec.circular) "circular" else "linear"} DNA\n")
        sb.append("DEFINITION  ${rec.description}\n")
        sb.append("FEATURES             Location/Qualifiers\n")
        rec.features.forEach { f ->
            val loc = if (f.strand < 0) "complement(${f.start}..${f.end})" else "${f.start}..${f.end}"
            sb.append("     ${f.type.padEnd(15).take(15)} $loc\n")
            sb.append("                     /label=\"${f.name}\"\n")
        }
        sb.append("ORIGIN\n")
        val seq = rec.sequence.lowercase()
        var i = 0
        var lineNo = 1
        while (i < seq.length) {
            val chunk = seq.substring(i, minOf(i + 60, seq.length))
            sb.append("%9d ".format(lineNo))
            chunk.chunked(10).forEach { sb.append("$it ") }
            sb.append("\n")
            i += 60
            lineNo += 60
        }
        sb.append("//\n")
        return sb.toString()
    }
}
