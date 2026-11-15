package com.spice.nativeapp.core

import kotlin.math.roundToInt

/**
 * NGS 质控与原核基因预测算法层（#68 T7 附属块）。
 *
 * 三个真身的 1:1 移植：
 * 1. analyzeFastqQuality —— SPICE_main/src-tauri/src/cloning.rs analyze_fastq_quality_rust
 *    （bio 4.0.1 fastq::Reader 解析语义逐行复刻；rust 失败按 main api.ts guarded() 回落
 *    genome/biophysics/genomics.ts analyzeFastqQuality 的 TS 实现）。
 * 2. findOrfs —— cloning.rs find_orfs_rust（bio seq_analysis::orf::Finder 逐行复刻 +
 *    反向链坐标回映射；rust 失败按 guarded 回落 clone.ts findOrfsInPlasmid——安卓侧
 *    仅空序列会走回退，回退结果与 main 一致为空表，strand=undefined 的浏览器 demo quirk
 *    在安卓不可达，README 披露）。
 * 3. autoIdentifyAndRemoveAdapters —— genomics.ts autoIdentifyAndRemoveAdapters（纯 TS）。
 *
 * f32 语义：rust 报告里 (x*100).round()/100 为 f32 运算，此处全程用 Float 复刻；
 * serde 输出如 26.670000076293945 即 f32(26.67) 的十进制展开，比对时按 Float round-trip。
 */
object NgsQc {

    // ───────────────────── FASTQ Quality Control ─────────────────────

    class FastqException(message: String) : Exception(message)

    /** rust RustFastqQcReport + TS 专有字段的并集（rust 路径下 TS 专有字段为空集） */
    data class FastqQcReport(
        val totalReads: Int,
        val totalBases: Int,
        val averageReadLength: Int,
        val gcContentPercent: Float,
        val baseQualities: List<Float>,
        val duplicateReadsPercent: Float,
        val qualityDistribution: Map<Int, Int> = emptyMap(),
        val adaptersDetected: List<AdapterHit> = emptyList()
    )

    data class AdapterHit(val adapterName: String, val count: Int, val percentage: Double)

    /** main api.ts guarded：rust 报错（空文本/解析失败/无记录）→ TS 回退 */
    fun analyzeFastqQuality(fastqText: String): FastqQcReport =
        try {
            analyzeFastqQualityRust(fastqText)
        } catch (e: Exception) {
            analyzeFastqQualityTs(fastqText)
        }

    /** cloning.rs analyze_fastq_quality_rust 1:1 */
    fun analyzeFastqQualityRust(fastqText: String): FastqQcReport {
        if (fastqText.isEmpty()) throw FastqException("i18n:beErrFastqTextEmpty")

        val reader = LineReader(fastqText)

        var totalReads = 0
        var totalBases = 0
        var gcCount = 0

        val maxReadLen = 250
        val qSumAtPos = FloatArray(maxReadLen)
        val qCountAtPos = IntArray(maxReadLen)

        val seqCounts = HashMap<String, Int>()

        try {
            while (true) {
                val rec = readRecord(reader) ?: break
                val seq = rec.first
                val qual = rec.second
                if (seq.isEmpty() || seq.length != qual.length) continue

                totalReads++
                totalBases += seq.length

                seqCounts.merge(seq, 1, Int::plus)

                for (b in seq) {
                    if (b == 'G' || b == 'C' || b == 'g' || b == 'c') gcCount++
                }

                for (pos in 0 until seq.length) {
                    val q = qual[pos].code - 33 // Phred33 = 字节值 - 33
                    if (pos < maxReadLen) {
                        qSumAtPos[pos] += q.toFloat()
                        qCountAtPos[pos]++
                    }
                }
            }
        } catch (e: FastqException) {
            throw FastqException("i18n:beErrFastqParseFailed::${e.message}")
        }

        if (totalReads == 0) throw FastqException("i18n:beErrFastqNoRecords")

        val baseQualities = ArrayList<Float>()
        run {
            for (pos in 0 until maxReadLen) {
                if (qCountAtPos[pos] > 0) {
                    val avg = qSumAtPos[pos] / qCountAtPos[pos]
                    baseQualities.add((avg * 100f).roundToInt() / 100f)
                } else break
            }
        }

        var duplicateCount = 0
        for (count in seqCounts.values) {
            if (count > 1) duplicateCount += count - 1
        }
        val duplicateReadsPercent = (duplicateCount.toFloat() / totalReads.toFloat()) * 100f
        val gcContentPercent = (gcCount.toFloat() / totalBases.toFloat()) * 100f

        return FastqQcReport(
            totalReads = totalReads,
            totalBases = totalBases,
            averageReadLength = totalBases / totalReads,
            gcContentPercent = (gcContentPercent * 100f).roundToInt() / 100f,
            baseQualities = baseQualities,
            duplicateReadsPercent = (duplicateReadsPercent * 100f).roundToInt() / 100f
        )
    }

    /**
     * bio 4.0.1 io/fastq.rs Reader::read 逐行复刻。
     * 返回 Pair(seq, qual)；EOF（空记录）返回 null。
     * header 不以 @ 开头 → "expected '@' at record start"；
     * 无 qual 行 → IncompleteRecord（bio 的完整错误文案）。
     */
    private fun readRecord(reader: LineReader): Pair<String, String>? {
        val header = reader.readLine() ?: return null
        if (header.isEmpty()) return null
        if (!header.startsWith('@')) {
            throw FastqException("expected '@' at record start")
        }
        // header[1..].trim_end().splitn(2, ' ') —— id 与 desc 此处不参与统计，跳过

        var linesRead = 0
        val seq = StringBuilder()
        var line: String? = reader.readLine()
        while (line != null && line.isNotEmpty() && !line.startsWith('+')) {
            seq.append(line.trimEnd())
            linesRead++
            line = reader.readLine()
        }

        val qual = StringBuilder()
        repeat(linesRead) {
            val l = reader.readLine()
            if (l != null) qual.append(l.trimEnd())
        }

        if (qual.isEmpty()) {
            throw FastqException(
                "Incomplete record. Each FastQ record has to consist of 4 lines: header, sequence, separator and qualities."
            )
        }
        return seq.toString() to qual.toString()
    }

    /** emulates BufRead::read_line over Cursor：返回含 '\n' 的行，EOF 返回 null */
    private class LineReader(private val s: String) {
        private var i = 0
        fun readLine(): String? {
            if (i >= s.length) return null
            val nl = s.indexOf('\n', i)
            return if (nl == -1) {
                val r = s.substring(i); i = s.length; r
            } else {
                val r = s.substring(i, nl + 1); i = nl + 1; r
            }
        }
    }

    /** genomics.ts analyzeFastqQuality（TS 回退路径）1:1 */
    fun analyzeFastqQualityTs(fastqText: String): FastqQcReport {
        val lines = fastqText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val totalLines = lines.size

        var totalReads = 0
        var totalBases = 0
        var gcBases = 0

        val maxReadLen = 250
        val qSumAtPos = DoubleArray(maxReadLen)
        val qCountAtPos = IntArray(maxReadLen)

        val seqCounts = HashMap<String, Int>()

        // TS: for (let i = 0; i < totalLines - 3; i += 4) { if 脏头 i -= 2; continue }
        val adapters = listOf(
            "Illumina TruSeq Universal Adapter" to "AGATCGGAAGAG",
            "Illumina Small RNA Adapter" to "TGGAATTCTCGG",
            "Nextera Transposase Adapter" to "CTGTCTCTTATA"
        )
        val adapterHits = HashMap<String, Int>()
        adapters.forEach { adapterHits[it.first] = 0 }

        val qualityDistribution = HashMap<Int, Int>()

        var i = 0
        while (i < totalLines - 3) {
            if (!lines[i].startsWith("@") || !lines[i + 2].startsWith("+")) {
                i -= 2 // re-sync
                i += 4
                continue
            }
            val seq = lines[i + 1].uppercase()
            val qual = lines[i + 3]
            if (seq.length != qual.length) {
                i += 4
                continue
            }
            totalReads++
            totalBases += seq.length
            seqCounts.merge(seq, 1, Int::plus)

            for (b in seq) if (b == 'G' || b == 'C') gcBases++

            for (a in adapters) if (seq.contains(a.second)) adapterHits.merge(a.first, 1, Int::plus)

            for (pos in 0 until seq.length) {
                val q = qual[pos].code - 33
                qualityDistribution.merge(q, 1, Int::plus)
                if (pos < maxReadLen) {
                    qSumAtPos[pos] += q
                    qCountAtPos[pos]++
                }
            }
            i += 4
        }

        val baseQualities = ArrayList<Float>()
        run {
            for (pos in 0 until maxReadLen) {
                if (qCountAtPos[pos] > 0) {
                    baseQualities.add(jsToFixed2(qSumAtPos[pos] / qCountAtPos[pos]).toFloat())
                } else break
            }
        }

        var duplicateCount = 0
        for (count in seqCounts.values) if (count > 1) duplicateCount += count - 1
        val duplicateReadsPercent = if (totalReads > 0) jsToFixed2(duplicateCount / totalReads.toDouble() * 100) else 0.0

        val adaptersDetected = adapters.map { (name, _) ->
            val count = adapterHits[name] ?: 0
            AdapterHit(
                name, count,
                if (totalReads > 0) jsToFixed2(count / totalReads.toDouble() * 100) else 0.0
            )
        }.filter { it.count > 0 }

        return FastqQcReport(
            totalReads = totalReads,
            totalBases = totalBases,
            averageReadLength = if (totalReads > 0) jsMathRound(totalBases.toDouble() / totalReads) else 0,
            gcContentPercent = if (totalBases > 0) jsToFixed2(gcBases.toDouble() / totalBases * 100).toFloat() else 0f,
            baseQualities = baseQualities,
            qualityDistribution = qualityDistribution,
            adaptersDetected = adaptersDetected,
            duplicateReadsPercent = duplicateReadsPercent.toFloat()
        )
    }

    /** JS Number.prototype.toFixed(2)：BigDecimal HALF_UP 后去尾零的 Number 语义 */
    private fun jsToFixed2(x: Double): Double =
        java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).toDouble()

    /** JS Math.round（.5 远离零方向；本文件值域非负） */
    private fun jsMathRound(x: Double): Int = java.math.BigDecimal(x).setScale(0, java.math.RoundingMode.HALF_UP).toInt()

    // ───────────────────── ORF Finder（find_orfs_rust 1:1）─────────────────────

    /** cloning.rs RustOrfResult：start/end 0 基、end 排他、反向链已映射回正链坐标 */
    data class RustOrf(
        val start: Int,
        val end: Int,
        val strand: String,
        val length: Int,
        val seq: String
    )

    /** main api.ts guarded：rust 报错 → clone.ts findOrfsInPlasmid 回退（安卓仅空序列可达，返回空表） */
    fun findOrfs(dnaSeq: String, minLen: Int): List<RustOrf> =
        try {
            findOrfsRust(dnaSeq, minLen)
        } catch (e: Exception) {
            findOrfsTsFallback(dnaSeq)
        }

    /** cloning.rs find_orfs_rust 1:1（bio seq_analysis::orf::Finder + 反向链坐标回映射） */
    fun findOrfsRust(dnaSeq: String, minLen: Int): List<RustOrf> {
        if (dnaSeq.isEmpty()) throw FastqException("i18n:beErrDnaSequenceEmpty")

        val seq = dnaSeq.uppercase()

        val startCodons = listOf("ATG", "GTG", "TTG")
        val stopCodons = listOf("TAA", "TAG", "TGA")

        val results = ArrayList<RustOrf>()

        // 1. 正向链
        for ((start, end) in finderFindAll(seq, startCodons, stopCodons)) {
            val orfLen = end - start
            if (orfLen >= minLen) {
                results += RustOrf(start, end, "+", orfLen, seq.substring(start, end))
            }
        }

        // 2. 反向互补链（bio alphabets::dna::revcomp：IUPAC 全表、保留大小写）
        val rcSeq = bioRevcomp(seq)
        val len = seq.length
        for ((start, end) in finderFindAll(rcSeq, startCodons, stopCodons)) {
            val orfLen = end - start
            if (orfLen >= minLen) {
                val forwardStart = len - end
                val forwardEnd = len - start
                results += RustOrf(forwardStart, forwardEnd, "-", orfLen, rcSeq.substring(start, end))
            }
        }

        return results
    }

    /**
     * bio 4.0.1 seq_analysis/orf.rs Finder 逐行复刻：
     * 3 字节滑窗 codon（每碱基滑动，offset=(index+1)%3 记帧）；
     * start codon 命中把 index（codon 末碱基位）压栈；
     * stop codon 命中时按入栈序发射 Orf{start=start_pos-2, end=index+1}
     * （index+1-start_pos > min_len=3 才发射，首个过短即 break），
     * 随后清空该帧栈（嵌套 ORF 共享同一 stop）。
     */
    private fun finderFindAll(seq: String, startCodons: List<String>, stopCodons: List<String>): List<Pair<Int, Int>> {
        val startPos = arrayOf(mutableListOf<Int>(), mutableListOf(), mutableListOf())
        val found = ArrayList<Pair<Int, Int>>()
        val codon = StringBuilder()
        for (index in seq.indices) {
            if (codon.length >= 3) codon.deleteCharAt(0)
            codon.append(seq[index])
            if (codon.length < 3) continue
            val c = codon.toString()
            val offset = (index + 1) % 3
            if (c in startCodons) startPos[offset].add(index)
            if (startPos[offset].isNotEmpty() && c in stopCodons) {
                for (sp in startPos[offset]) {
                    if (index + 1 - sp > 3) {
                        found.add((sp - 2) to (index + 1))
                    } else break // 首个过短，后续更短
                }
                startPos[offset] = mutableListOf()
            }
        }
        return found
    }

    /** bio alphabets::dna::revcomp 1:1：COMPLEMENT 256 表（未知字符原样保留） */
    private fun bioRevcomp(seq: String): String {
        val comp = IntArray(256) { it }
        val from = "AGCTYRWSKMDVHBN"
        val to = "TCGARYWSMKHBDVN"
        for (k in from.indices) {
            comp[from[k].code] = to[k].code
            comp[from[k].code + 32] = to[k].code + 32
        }
        return buildString(seq.length) {
            for (i in seq.indices.reversed()) append(comp[seq[i].code].toChar())
        }
    }

    /**
     * clone.ts findOrfsInPlasmid 回退路径（guarded 只在 rust 报错时走）。
     * 安卓侧 rust 仅对空序列报错，此时 main 回退也产出空表，故此处仅返回空表；
     * 浏览器 demo 模式（无 Tauri 时恒走回退）含 strand=undefined 的展示 quirk，
     * 安卓不可达，README 披露。
     */
    private fun findOrfsTsFallback(@Suppress("UNUSED_PARAMETER") dnaSeq: String): List<RustOrf> = emptyList()

    // ───────────────────── Adapter Auto-detection & Trimming ─────────────────────

    /** genomics.ts TrimResult 1:1 */
    data class TrimResult(
        val trimmedSequences: List<String>,
        val trimmedQualityScores: List<String>,
        val trimmedCount: Int,
        val log: List<String>
    )

    /** genomics.ts autoIdentifyAndRemoveAdapters 1:1（纯 TS，无 rust 对应） */
    fun autoIdentifyAndRemoveAdapters(
        reads: List<String>,
        qualities: List<String>,
        customAdapter: String? = null
    ): TrimResult {
        val defaultAdapters = listOf(
            "AGATCGGAAGAGCACACGTCTGAACTCCAGTCA", // Illumina TruSeq R1
            "AGATCGGAAGAGCGTCGTGTAGGGAAGAGTG",   // Illumina TruSeq R2
            "CTGTCTCTTATACACATCT"               // Nextera Transposase
        )

        val targetAdapters = if (customAdapter != null && customAdapter.isNotEmpty()) listOf(customAdapter.uppercase()) else defaultAdapters
        val trimmedSequences = ArrayList<String>()
        val trimmedQualityScores = ArrayList<String>()
        var trimmedCount = 0

        for (i in reads.indices) {
            val seq = reads[i].uppercase()
            val qual = qualities.getOrElse(i) { "" }
            var trimmed = false
            var cutPos = seq.length

            for (adapter in targetAdapters) {
                var foundPos = seq.indexOf(adapter)

                // 3' 端部分匹配（read 以后接体前缀结尾）
                if (foundPos == -1) {
                    var len = minOf(adapter.length - 1, 15)
                    while (len >= 6) {
                        val adapterPrefix = adapter.substring(0, len)
                        if (seq.endsWith(adapterPrefix)) {
                            foundPos = seq.length - len
                            break
                        }
                        len--
                    }
                }

                if (foundPos != -1 && foundPos < cutPos) {
                    cutPos = foundPos
                    trimmed = true
                }
            }

            if (trimmed && cutPos >= 15) { // 保证裁后最小长度
                trimmedSequences.add(seq.substring(0, cutPos))
                trimmedQualityScores.add(if (qual.isNotEmpty()) qual.substring(0, cutPos) else "")
                trimmedCount++
            } else {
                trimmedSequences.add(seq)
                trimmedQualityScores.add(qual)
            }
        }

        val log = listOf(
            I18n.tf(
                "[剪切报告] 扫描了 {v1} 条 reads，共成功切除 3' 接头 {v2} 条，去接头率 {v3}%",
                reads.size, trimmedCount,
                java.math.BigDecimal(trimmedCount.toDouble() / maxOf(1, reads.size).toDouble() * 100)
                    .setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
            )
        )

        return TrimResult(trimmedSequences, trimmedQualityScores, trimmedCount, log)
    }
}
