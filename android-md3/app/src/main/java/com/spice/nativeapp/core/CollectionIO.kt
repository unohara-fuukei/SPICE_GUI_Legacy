package com.spice.nativeapp.core

/**
 * 项目集合工作空间（CollectionsBatchPanel）算法层 1:1 移植。
 *
 * 来源对照（branch main）：
 * - detectFormat / parseSequenceFile / parseFasta / parseSnapGeneDna /
 *   parseSnapGeneFeaturesXml：src/lib/genome/formats.ts
 * - filterSequenceString / calculatePercentGC / getReverseComplementSequenceString：
 *   src/lib/genome/sequence.ts（DNA 默认形态）
 * - calculateNebTm：src/lib/genome/tm.ts（NEB SantaLucia1998 + Owczarzy 盐修正；
 *   末项 `Math.pow(9.4, -6)` 是主版 verbatim 数字（应为 9.4e-6 的笔误），按 1:1 保留）
 * - designPrimersForCloning：src/lib/genome/primerAnalysis.ts
 * - batchDigest / batchDesignPrimers：src/lib/genome/batch.ts（BatchEngine 静态方法）
 * - exportCollectionZip / importCollectionZip：src-tauri/src/cloning.rs（Tauri 命令；
 *   main 浏览器 fallback 为 JSON 退化实现，Android 具备本地 zip 能力 → 按 Rust 语义
 *   用 java.util.zip 实现，README 披露）
 */
object CollectionIO {

    // ── 数据形态 ──

    data class ColFeature(
        val id: Int,
        val name: String,
        val type: String,
        val start: Int,
        val end: Int,
        val forward: Boolean,
        val color: String,
        val strand: Int = if (forward) 1 else -1
    )

    data class ColSequenceData(
        val sequence: String,
        val name: String,
        val circular: Boolean = false,
        val features: List<ColFeature> = emptyList()
    )

    data class ZipEntryData(val name: String, val seq: String)

    data class PrimerArm(
        val sequence: String,
        val annealingSeq: String,
        val overhangSeq: String,
        val length: Int,
        val tm: Double,
        val gcContent: Double
    )

    data class PrimerDesignResult(val forward: PrimerArm, val reverse: PrimerArm)

    data class BatchDigestEntry(
        val sequenceName: String,
        val cutsitesCount: Int,
        val cutsites: List<EnzymesVe.VeCutsite>
    )

    // ── formats.ts：detectFormat ──

    fun detectFormat(text: String, fileName: String? = null): String {
        if (fileName != null) {
            val ext = fileName.lowercase().substringAfterLast('.', "")
            when (ext) {
                "gb", "gbk", "genbank" -> return "genbank"
                "fasta", "fa", "fna", "faa" -> return "fasta"
                "embl", "ebl" -> return "embl"
                "fastq", "fq" -> return "fastq"
                "spiceg" -> return "spiceg"
            }
        }
        val t = text.trim()
        if (t.startsWith("LOCUS")) return "genbank"
        if (t.startsWith("ID   ")) return "embl"
        if (t.startsWith("@")) return "fastq"
        if (t.startsWith(">")) return "fasta"
        if (t.contains("\"format\": \"SPICE_GENE\"")) return "spiceg"
        return "plain"
    }

    // ── formats.ts：parseFasta（返回 id/description/sequence 全集） ──

    data class FastaRecord(val id: String, val description: String, val sequence: String)

    fun parseFasta(text: String): List<FastaRecord> {
        val records = mutableListOf<FastaRecord>()
        var current: FastaRecord? = null
        var seqLines = mutableListOf<String>()
        fun push() {
            current?.let {
                records.add(it.copy(sequence = seqLines.joinToString("")))
            }
        }
        for (line in text.split(Regex("\r?\n"))) {
            if (line.startsWith(">")) {
                push()
                val header = line.substring(1)
                val spaceIdx = header.indexOf(' ')
                current = if (spaceIdx >= 0) {
                    FastaRecord(header.substring(0, spaceIdx), header.substring(spaceIdx + 1), "")
                } else {
                    FastaRecord(header, "", "")
                }
                seqLines = mutableListOf()
            } else if (line.startsWith(";")) {
                continue
            } else {
                seqLines.add(line.trim())
            }
        }
        push()
        return records
    }

    // ── sequence.ts：filterSequenceString（DNA 默认形态：不做替换、仅剔除非法字符） ──
    // 接受字符集 = ambiguous_rna ∪ ambiguous_dna（teselagen 小写集 acgtumrwsykvhdbn），
    // 大小写原样保留（主版 chars.includes(lowerLetter) 后追加原字母）。

    private val ACCEPTED_DNA = "acgtumrwsykvhdbn".toSet()

    fun filterSequenceString(sequenceString: String): String {
        val sb = StringBuilder()
        for (letter in sequenceString) {
            if (letter.lowercaseChar() in ACCEPTED_DNA) sb.append(letter)
        }
        return sb.toString()
    }

    // ── sequence.ts：calculatePercentGC ──

    fun calculatePercentGC(bps: String): Double {
        if (bps.isEmpty()) return 0.0
        val count = Regex("[cg]", RegexOption.IGNORE_CASE).findAll(bps).count()
        val v = count * 100.0 / bps.length
        // 主版 `(...) || 0`：NaN → 0；此处 length>0 恒非 NaN，语义一致
        return if (v.isNaN()) 0.0 else v
    }

    // ── formats.ts：parseSequenceFile ──

    fun parseSequenceFile(text: String, fileName: String? = null): ColSequenceData {
        return when (detectFormat(text, fileName)) {
            "genbank" -> tidy(parseGenBank(text))
            "embl" -> tidy(parseEmbl(text))
            "fasta" -> {
                val rec = parseFasta(text).firstOrNull() ?: FastaRecord("Imported", "", "")
                ColSequenceData(
                    sequence = filterSequenceString(rec.sequence),
                    name = rec.id.ifEmpty { "Imported" },
                    circular = false
                )
            }
            "fastq" -> {
                val rec = parseFastq(text).firstOrNull() ?: FastaRecord("Imported", "", "")
                ColSequenceData(
                    sequence = filterSequenceString(rec.sequence),
                    name = rec.id.ifEmpty { "Imported" },
                    circular = false
                )
            }
            "spiceg" -> {
                val rec = Spiceg.parseFull(text)?.record
                ColSequenceData(
                    sequence = filterSequenceString(rec?.sequence ?: ""),
                    name = rec?.name ?: "Imported",
                    circular = rec?.circular ?: false
                )
            }
            else -> {
                ColSequenceData(
                    sequence = filterSequenceString(text),
                    name = fileName?.replace(Regex("\\.[^.]+$"), "") ?: "Imported",
                    circular = false
                )
            }
        }
    }

    /** model.ts tidyUpSequenceData 的 ColSequenceData 形态（非蛋白：仅再过滤一次序列） */
    private fun tidy(d: ColSequenceData): ColSequenceData = d.copy(sequence = filterSequenceString(d.sequence))

    // ── formats.ts：parseFastq（id/plus/quality 校验；序列原样保留大小写） ──

    fun parseFastq(text: String): List<FastaRecord> {
        val lines = text.split(Regex("\r?\n"))
        val records = mutableListOf<FastaRecord>()
        var i = 0
        while (i + 3 < lines.size || (i + 3 == lines.size && i + 3 < lines.size)) {
            if (lines.getOrNull(i)?.startsWith("@") != true) {
                i++
                if (i > lines.size) break
                continue
            }
            val header = lines[i].substring(1)
            val spaceIdx = header.indexOf(' ')
            val seq = lines.getOrNull(i + 1) ?: ""
            val plus = lines.getOrNull(i + 2) ?: ""
            val qual = lines.getOrNull(i + 3) ?: ""
            if (plus.startsWith("+") && qual.length == seq.length) {
                records.add(
                    FastaRecord(
                        id = if (spaceIdx >= 0) header.substring(0, spaceIdx) else header,
                        description = if (spaceIdx >= 0) header.substring(spaceIdx + 1) else "",
                        sequence = seq
                    )
                )
                i += 4
            } else {
                i++
            }
        }
        return records
    }

    // ── formats.ts：parseGenBank（LOCUS 命名/circular；ORIGIN 序列保留大小写；
    //    FEATURES 注释 qualifier 解析 + \d+..\d+ 区间，0-based） ──

    private class GbAnnotation(val type: String, val locations: String) {
        val quals = mutableMapOf<String, String>()
    }

    private fun parseGbFeatures(annotations: List<GbAnnotation>): List<ColFeature> {
        val features = mutableListOf<ColFeature>()
        annotations.forEachIndexed { idx, a ->
            val locStr = a.locations
            val strand = if (locStr.startsWith("complement")) -1 else 1
            val positions = Regex("(\\d+)\\.\\.(\\d+)").findAll(locStr)
                .map { (it.groupValues[1].toInt() - 1) to (it.groupValues[2].toInt() - 1) }
                .toMutableList()
            if (positions.isEmpty()) {
                Regex("(\\d+)").find(locStr)?.let {
                    val p = it.groupValues[1].toInt() - 1
                    positions.add(p to p)
                }
            }
            if (positions.isEmpty()) return@forEachIndexed
            val q = a.quals
            features.add(
                ColFeature(
                    id = idx + 1,
                    name = q["gene"] ?: q["label"] ?: q["product"] ?: q["note"] ?: a.type.ifEmpty { "Untitled" },
                    type = a.type,
                    start = positions.first().first,
                    end = positions.last().second,
                    forward = strand == 1,
                    strand = strand,
                    color = q["apeinfo_fwdcolor"] ?: q["apeinfo_graphic_color"] ?: q["color"] ?: ""
                )
            )
        }
        return features
    }

    fun parseGenBank(text: String): ColSequenceData {
        val lines = text.split(Regex("\r?\n"))
        var name = "Imported"
        var circular = false
        val annotations = mutableListOf<GbAnnotation>()
        val seqSb = StringBuilder()
        var inOrigin = false
        var currentFeat: GbAnnotation? = null
        var qualifierKey = ""
        var qualifierBuf = ""

        fun flushQualifier() {
            val f = currentFeat ?: return
            if (qualifierBuf.isNotEmpty() && qualifierKey.isNotEmpty()) f.quals[qualifierKey] = qualifierBuf
            qualifierBuf = ""
            qualifierKey = ""
        }

        for (line in lines) {
            if (line.startsWith("LOCUS")) {
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                name = parts.getOrNull(1) ?: "Imported"
                if (Regex("circular", RegexOption.IGNORE_CASE).containsMatchIn(line)) circular = true
                continue
            }
            if (line.startsWith("DEFINITION") || line.startsWith("FEATURES")) continue
            if (Regex("^\\s{5}\\S+").matches(line)) {
                flushQualifier()
                currentFeat?.let { annotations.add(it) }
                val m = Regex("^\\s{5}(\\S+)\\s+(.*)").find(line)
                currentFeat = GbAnnotation(
                    type = m?.groupValues?.get(1)?.lowercase() ?: "misc_feature",
                    locations = m?.groupValues?.get(2) ?: ""
                )
                qualifierBuf = ""
                qualifierKey = ""
                continue
            }
            val qualMatch = Regex("^\\s+/(\\w+)=?\"([^\"]*)\"?$").find(line)
            if (qualMatch != null) {
                if (currentFeat != null && qualifierKey.isNotEmpty() && qualifierBuf.isNotEmpty()) {
                    currentFeat!!.quals[qualifierKey] = qualifierBuf
                }
                qualifierKey = qualMatch.groupValues[1]
                qualifierBuf = qualMatch.groupValues[2]
                continue
            }
            if (line.startsWith("                     ") && qualifierKey.isNotEmpty()) {
                qualifierBuf += line.trim()
                continue
            }
            if (line.startsWith("ORIGIN")) {
                flushQualifier()
                currentFeat?.let { annotations.add(it) }
                currentFeat = null
                inOrigin = true
                continue
            }
            if (inOrigin) {
                if (line.startsWith("//")) break
                seqSb.append(line.filter { it.isLetter() })
            }
        }
        flushQualifier()
        currentFeat?.let { annotations.add(it) }

        return ColSequenceData(
            sequence = seqSb.toString(),
            features = parseGbFeatures(annotations),
            name = name,
            circular = circular
        )
    }

    // ── formats.ts：parseEmbl（ID/SQ；FT 特征；序列保留大小写） ──

    fun parseEmbl(text: String): ColSequenceData {
        val lines = text.split(Regex("\r?\n"))
        var name = "Imported"
        var circular = false
        val annotations = mutableListOf<GbAnnotation>()
        val seqSb = StringBuilder()
        var inSeq = false
        var currentFeat: GbAnnotation? = null
        var qualifierKey = ""
        var qualifierBuf = ""
        for (line in lines) {
            if (line.startsWith("ID")) {
                Regex("^ID\\s+(\\S+)").find(line)?.let { name = it.groupValues[1] }
                if (Regex("circular", RegexOption.IGNORE_CASE).containsMatchIn(line)) circular = true
                continue
            }
            if (line.startsWith("DE") || line.startsWith("FH")) continue
            if (line.startsWith("FT")) {
                val content = line.substring(2).trim()
                val ftMatch = Regex("^(\\S+)\\s+(.*)").find(content)
                if (ftMatch != null) {
                    if (currentFeat != null) {
                        if (qualifierBuf.isNotEmpty() && qualifierKey.isNotEmpty()) {
                            currentFeat!!.quals[qualifierKey] = qualifierBuf
                        }
                        annotations.add(currentFeat!!)
                    }
                    currentFeat = GbAnnotation(
                        type = ftMatch.groupValues[1].lowercase(),
                        locations = ftMatch.groupValues[2]
                    )
                    qualifierBuf = ""
                    qualifierKey = ""
                } else {
                    val qualMatch = Regex("^/(\\w+)=?\"([^\"]*)\"?$").find(content)
                    if (qualMatch != null) {
                        if (currentFeat != null && qualifierBuf.isNotEmpty() && qualifierKey.isNotEmpty()) {
                            currentFeat!!.quals[qualifierKey] = qualifierBuf
                        }
                        qualifierKey = qualMatch.groupValues[1]
                        qualifierBuf = qualMatch.groupValues[2]
                    } else if (qualifierKey.isNotEmpty()) {
                        qualifierBuf += content.replace("\"", "")
                    }
                }
                continue
            }
            if (line.startsWith("SQ")) {
                if (currentFeat != null) {
                    if (qualifierBuf.isNotEmpty() && qualifierKey.isNotEmpty()) {
                        currentFeat!!.quals[qualifierKey] = qualifierBuf
                    }
                    annotations.add(currentFeat!!)
                    currentFeat = null
                }
                inSeq = true
                continue
            }
            if (line.startsWith("//")) break
            if (inSeq) seqSb.append(line.filter { it.isLetter() })
        }
        return ColSequenceData(
            sequence = seqSb.toString(),
            features = parseGbFeatures(annotations),
            name = name,
            circular = circular
        )
    }

    // ── formats.ts：parseSnapGeneDna（二进制 .dna 解析） ──
    // 布局：0x09 + "SnapGene"(8B) + flags(1B, &0x01=circular)，随后重复
    // [tag 1B][length 4B big-endian][payload]；tag 0=序列，1=特征 XML，6=拓扑 XML。

    class SnapGeneFormatException(message: String) : Exception(message)

    fun parseSnapGeneDna(bytes: ByteArray): ColSequenceData {
        val len = bytes.size
        if (len < 11) throw SnapGeneFormatException("Invalid .dna file: file is too short")
        if (bytes[0].toInt() and 0xFF != 0x09) throw SnapGeneFormatException("Invalid .dna file: incorrect magic byte")
        val magic = String(bytes, 1, 8, Charsets.UTF_8)
        if (magic != "SnapGene") throw SnapGeneFormatException("Invalid .dna file: incorrect magic string: $magic")

        val flags = bytes[9].toInt() and 0xFF
        var isCircular = (flags and 0x01) == 0x01

        var sequence = ""
        var features: List<ColFeature> = emptyList()

        var offset = 11
        while (offset + 5 <= len) {
            val tag = bytes[offset].toInt() and 0xFF
            // big-endian uint32
            val blockLen = ((bytes[offset + 1].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 8) or
                (bytes[offset + 4].toInt() and 0xFF)
            offset += 5
            if (offset + blockLen > len) break
            val payload = bytes.copyOfRange(offset, offset + blockLen)
            offset += blockLen

            if (tag == 0) {
                sequence = String(payload, Charsets.ISO_8859_1).uppercase()
                    .filter { it in "ATCGN" }
            } else if (tag == 1) {
                features = parseSnapGeneFeaturesXml(String(payload, Charsets.UTF_8))
            } else if (tag == 6) {
                val xmlText = String(payload, Charsets.UTF_8)
                if (xmlText.contains("circular=\"true\"") || xmlText.contains("circular=\"1\"")) {
                    isCircular = true
                } else if (xmlText.contains("circular=\"false\"") || xmlText.contains("circular=\"0\"")) {
                    isCircular = false
                }
            }
        }

        return ColSequenceData(
            sequence = sequence,
            features = features,
            name = "Imported SnapGene",
            circular = isCircular
        )
    }

    /** formats.ts parseSnapGeneFeaturesXml（DOM 解析，Android 用 DocumentBuilderFactory 对应 DOMParser） */
    fun parseSnapGeneFeaturesXml(xmlText: String): List<ColFeature> {
        val features = mutableListOf<ColFeature>()
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xmlText.byteInputStream())
            val featureNodes = doc.getElementsByTagName("Feature")
            for (i in 0 until featureNodes.length) {
                val node = featureNodes.item(i) as org.w3c.dom.Element
                val name = node.getAttribute("name").ifEmpty { "Untitled" }
                val type = node.getAttribute("type").ifEmpty { "misc_feature" }
                val color = node.getAttribute("color").ifEmpty { "#5c6bc0" }
                val directionAttr = node.getAttribute("direction").ifEmpty { "1" }
                val forward = directionAttr != "2" // 1 = fwd, 2 = rev, 0 = bidirectional
                val segmentNodes = node.getElementsByTagName("Segment")
                for (j in 0 until segmentNodes.length) {
                    val seg = segmentNodes.item(j) as org.w3c.dom.Element
                    val range = seg.getAttribute("range")
                    if (range.isNotEmpty()) {
                        val parts = range.split("-")
                        val start = parts.getOrNull(0)?.toIntOrNull()
                        val end = parts.getOrNull(1)?.toIntOrNull()
                        if (start != null && end != null) {
                            features.add(
                                ColFeature(
                                    id = features.size + 1,
                                    name = name,
                                    start = start,
                                    end = end,
                                    type = type,
                                    color = color,
                                    forward = forward
                                )
                            )
                        }
                    }
                }
            }
            features
        } catch (e: Exception) {
            // 主版 console.error 后返回已解析部分（空表），此处同语义
            features
        }
    }

    // ── cloning.rs：exportCollectionZip / importCollectionZip ──
    // Rust：无 '.' 的文件名补 ".gb"；Deflated 压缩；导入跳过目录、UTF-8 读全文。
    // 错误文案对齐主版 i18n:beErrZip* 协议的 zh 原文。

    fun exportCollectionZip(entries: List<ZipEntryData>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            for (entry in entries) {
                val filename = if (entry.name.contains('.')) entry.name else "${entry.name}.gb"
                try {
                    zip.putNextEntry(java.util.zip.ZipEntry(filename))
                } catch (e: Exception) {
                    throw SnapGeneFormatException(I18n.tf("无法启动 ZIP 目录文件: {v1}", e.message ?: ""))
                }
                try {
                    zip.write(entry.seq.toByteArray())
                } catch (e: Exception) {
                    throw SnapGeneFormatException(I18n.tf("写入 ZIP 内容失败: {v1}", e.message ?: ""))
                }
            }
            try {
                zip.finish()
            } catch (e: Exception) {
                throw SnapGeneFormatException(I18n.tf("结束 ZIP 归档失败: {v1}", e.message ?: ""))
            }
        }
        return out.toByteArray()
    }

    fun importCollectionZip(bytes: ByteArray): List<ZipEntryData> {
        val entries = mutableListOf<ZipEntryData>()
        try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val contents = zip.readBytes().toString(Charsets.UTF_8)
                        entries.add(ZipEntryData(entry.name, contents))
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            throw SnapGeneFormatException(I18n.tf("解压并打开 ZIP 归档失败: {v1}", e.message ?: ""))
        }
        return entries
    }

    // ── tm.ts：calculateNebTm（NEB Tm；非法/简并碱基返回 null 对应主版抛错的 string 路径） ──

    private val NEB_H = mapOf(
        "AA/TT" to -7.9, "AT/TA" to -7.2, "TA/AT" to -7.2, "CA/GT" to -8.5,
        "GT/CA" to -8.4, "CT/GA" to -7.8, "GA/CT" to -8.2, "CG/GC" to -10.6,
        "GC/CG" to -9.8, "GG/CC" to -8.0, "TT/AA" to -7.9, "TG/AC" to -8.5,
        "AC/TG" to -8.4, "AG/TC" to -7.8, "TC/AG" to -8.2, "CC/GG" to -8.0
    )
    private val NEB_S = mapOf(
        "AA/TT" to -22.2, "AT/TA" to -20.4, "TA/AT" to -21.3, "CA/GT" to -22.7,
        "GT/CA" to -22.4, "CT/GA" to -21.0, "GA/CT" to -22.2, "CG/GC" to -27.2,
        "GC/CG" to -24.4, "GG/CC" to -19.9, "TT/AA" to -22.2, "TG/AC" to -22.7,
        "AC/TG" to -22.4, "AG/TC" to -21.0, "TC/AG" to -22.2, "CC/GG" to -19.9
    )

    private fun complementChar(c: Char): Char = when (c) {
        'A' -> 'T'; 'T' -> 'A'; 'G' -> 'C'; 'C' -> 'G'; else -> c
    }

    fun calculateNebTm(
        sequence: String,
        monovalentCationConc: Double = 0.05,
        primerConc: Double = 0.0000005
    ): Double? {
        // 主版 /[^atgc]/i → throw（简并碱基禁用）
        if (sequence.any { it.lowercaseChar() !in "atgc" }) return null
        val seq = sequence.uppercase().toCharArray()
        var h = 0.0
        var s = 0.0
        var hi = 0.0
        var si = 0.0
        val r = 1.987
        for (i in seq.indices) {
            if (i == 0 || i == seq.size - 1) {
                if (seq[i] == 'G' || seq[i] == 'C') {
                    hi += 0.1; si += -2.8
                } else if (seq[i] == 'A' || seq[i] == 'T') {
                    hi += 2.3; si += 4.1
                }
            }
            if (i < seq.size - 1) {
                val dimer = "${seq[i]}${seq[i + 1]}"
                val complement = "${complementChar(seq[i])}${complementChar(seq[i + 1])}"
                val dimerDuplex = "$dimer/$complement"
                val dh = NEB_H[dimerDuplex]
                val ds = NEB_S[dimerDuplex]
                if (dh == null || ds == null) return null
                h += dh; s += ds
            }
        }
        val deltaH = h + hi
        val deltaS = s + si
        val meltingTemp = deltaH * 1000.0 / (deltaS + r * kotlin.math.ln(primerConc)) - 273.15
        if (monovalentCationConc != 0.0) {
            val lnOfMonoConc = kotlin.math.ln(monovalentCationConc)
            val gcContent = calculatePercentGC(sequence) / 100.0
            val part = 4.29 * gcContent - 3.95
            // 主版 verbatim：Math.pow(10, -5) 与 Math.pow(9.4, -6)（后者为原实现的数字笔误，1:1 保留）
            val saltCorrection = part * Math.pow(10.0, -5.0) * lnOfMonoConc +
                Math.pow(9.4, -6.0) * lnOfMonoConc * lnOfMonoConc
            return 1.0 / (1.0 / (meltingTemp + 273.15) + saltCorrection) - 273.15
        }
        return meltingTemp
    }

    // ── sequence.ts：getReverseComplementSequenceString ──

    fun revComp(sequence: String): String {
        val sb = StringBuilder(sequence.length)
        for (i in sequence.indices.reversed()) {
            sb.append(when (sequence[i]) {
                'a' -> 't'; 'A' -> 'T'; 't' -> 'a'; 'T' -> 'A'
                'g' -> 'c'; 'G' -> 'C'; 'c' -> 'g'; 'C' -> 'G'
                'u' -> 'a'; 'U' -> 'A'
                'm' -> 'k'; 'M' -> 'K'; 'k' -> 'm'; 'K' -> 'M'
                'r' -> 'y'; 'R' -> 'Y'; 'y' -> 'r'; 'Y' -> 'R'
                'b' -> 'v'; 'B' -> 'V'; 'v' -> 'b'; 'V' -> 'B'
                'd' -> 'h'; 'D' -> 'H'; 'h' -> 'd'; 'H' -> 'D'
                's' -> 's'; 'S' -> 'S'; 'w' -> 'w'; 'W' -> 'W'
                'n' -> 'n'; 'N' -> 'N'
                else -> sequence[i]
            })
        }
        return sb.toString()
    }

    /** JS String.prototype.slice（DNA 字符串用：start/end 越界钳制，负值自尾部，start>end 为空） */
    private fun jsSlice(s: String, a: Int, b: Int): String {
        val from = if (a < 0) maxOf(s.length + a, 0) else minOf(a, s.length)
        val to = if (b < 0) maxOf(s.length + b, 0) else minOf(b, s.length)
        return if (from >= to) "" else s.substring(from, to)
    }

    // ── primerAnalysis.ts：designPrimersForCloning ──

    fun designPrimersForCloning(
        templateSequence: String,
        targetRangeStart: Int,
        targetRangeEnd: Int,
        vectorSequence: String? = null,
        vectorInsertionPoint: Int = 0,
        targetTm: Double = 60.0,
        homologyArmLength: Int = 20
    ): PrimerDesignResult {
        val tSeq = templateSequence.uppercase()
        val vSeq = vectorSequence?.uppercase() ?: ""

        // 1. 正向引物退火区（目标区间 5' 端起）
        var fwdAnnealLen = 18
        var fwdAnnealing = ""
        var fwdTm = 0.0
        while (fwdAnnealLen <= 35) {
            fwdAnnealing = jsSlice(tSeq, targetRangeStart, targetRangeStart + fwdAnnealLen)
            val calculated = calculateNebTm(fwdAnnealing)
            fwdTm = calculated ?: 0.0
            if (fwdTm >= targetTm) break
            fwdAnnealLen++
        }

        // 2. 反向引物退火区（目标区间 3' 端反向互补）
        var revAnnealLen = 18
        var revAnnealing = ""
        var revTm = 0.0
        while (revAnnealLen <= 35) {
            val endPart = jsSlice(tSeq, targetRangeEnd - revAnnealLen + 1, targetRangeEnd + 1)
            revAnnealing = revComp(endPart)
            val calculated = calculateNebTm(revAnnealing)
            revTm = calculated ?: 0.0
            if (revTm >= targetTm) break
            revAnnealLen++
        }

        // 3. 载体同源臂（未提供载体时为空——面板调用形态）
        var fwdOverhang = ""
        var revOverhang = ""
        if (vSeq.isNotEmpty()) {
            val len = vSeq.length
            val upstreamStart = ((vectorInsertionPoint - homologyArmLength + len) % len + len) % len
            val upstream = StringBuilder()
            for (i in 0 until homologyArmLength) {
                upstream.append(vSeq[(upstreamStart + i) % len])
            }
            fwdOverhang = upstream.toString()
            val downstreamStart = ((vectorInsertionPoint % len) + len) % len
            val downstream = StringBuilder()
            for (i in 0 until homologyArmLength) {
                downstream.append(vSeq[(downstreamStart + i) % len])
            }
            revOverhang = revComp(downstream.toString())
        }

        val fwdFull = fwdOverhang + fwdAnnealing
        val revFull = revOverhang + revAnnealing

        return PrimerDesignResult(
            forward = PrimerArm(
                sequence = fwdFull,
                annealingSeq = fwdAnnealing,
                overhangSeq = fwdOverhang,
                length = fwdFull.length,
                tm = fwdTm,
                gcContent = calculatePercentGC(fwdFull)
            ),
            reverse = PrimerArm(
                sequence = revFull,
                annealingSeq = revAnnealing,
                overhangSeq = revOverhang,
                length = revFull.length,
                tm = revTm,
                gcContent = calculatePercentGC(revFull)
            )
        )
    }

    // ── batch.ts：BatchEngine.batchDigest（getEnzymeByName + getCutsitesFromSequenceFlat） ──

    fun batchDigest(
        sequences: List<BatchEngine.BatchSeq>,
        enzymeNames: List<String>,
        circular: Boolean = true
    ): List<BatchDigestEntry> {
        val enzymes = enzymeNames.map { EnzymesVe.getEnzymeByName(it) }.filterNotNull()
        if (enzymes.isEmpty()) return emptyList()
        return sequences.map { s ->
            val cuts = EnzymesVe.getCutsitesFromSequenceFlat(s.sequence, s.circular, enzymes)
            BatchDigestEntry(
                sequenceName = s.name.ifEmpty { "Untitled" },
                cutsitesCount = cuts.size,
                cutsites = cuts
            )
        }
    }

    // ── batch.ts：BatchEngine.batchDesignPrimers ──

    fun batchDesignPrimers(
        sequences: List<BatchEngine.BatchSeq>,
        relativeStart: Int,
        relativeEnd: Int,
        targetTm: Double = 60.0,
        homologyArmLength: Int = 20
    ): List<Pair<String, PrimerDesignResult>> {
        return sequences.map { s ->
            val len = s.sequence.length
            val start = maxOf(0, minOf(relativeStart, len - 1))
            val end = maxOf(start, minOf(relativeEnd, len - 1))
            val primers = designPrimersForCloning(
                templateSequence = s.sequence,
                targetRangeStart = start,
                targetRangeEnd = end,
                targetTm = targetTm,
                homologyArmLength = homologyArmLength
            )
            (s.name.ifEmpty { "Untitled" }) to primers
        }
    }
}
