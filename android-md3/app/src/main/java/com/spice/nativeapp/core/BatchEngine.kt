package com.spice.nativeapp.core

/**
 * 批量执行引擎（对齐 main 分支 lib/genome/batch.ts 的 BatchEngine）
 *
 * 高通量分子 CAD 批处理：多序列批量酶切筛选、批量引物设计、批量序列导出。
 */
object BatchEngine {

    data class BatchSeq(val name: String, val sequence: String, val circular: Boolean = true)

    data class BatchDigestResult(
        val sequenceName: String, val cutsitesCount: Int,
        val cutsites: List<Enzymes.Hit>
    )

    data class BatchPrimerResult(
        val sequenceName: String, val primers: PrimerPair?
    )

    data class BatchExportResult(
        val sequenceName: String, val content: String, val format: String
    )

    /** 解析多 FASTA 文本（>name 与序列行交替），忽略首个 header 之前的杂行 */
    fun parseFastas(text: String): List<BatchSeq> {
        val out = mutableListOf<BatchSeq>()
        val sb = StringBuilder()
        var name: String? = null
        fun flush() {
            val seq = sb.toString().uppercase().filter { it.isLetter() }
            if (name != null && seq.isNotEmpty()) out += BatchSeq(name!!, seq)
            sb.clear()
        }
        for (line in text.lines()) {
            val l = line.trim()
            if (l.startsWith(">")) {
                flush()
                name = l.removePrefix(">").trim().ifEmpty { "seq_${out.size + 1}" }
            } else if (l.isNotEmpty() && name != null) {
                sb.append(l)
            }
        }
        flush()
        return out
    }

    /** 批量酶切筛选：筛选对目标酶有单一/可控切点的骨架 */
    fun batchDigest(sequences: List<BatchSeq>, enzymeNames: List<String>): List<BatchDigestResult> {
        val names = enzymeNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return emptyList()
        return sequences.map { s ->
            val cuts = Enzymes.digest(s.sequence, names, s.circular)
            BatchDigestResult(s.name, cuts.size, cuts)
        }
    }

    /** 批量引物设计：按相对区间为每条序列设计一对克隆引物（1-based 区间） */
    fun batchDesignPrimers(
        sequences: List<BatchSeq>,
        relativeStart: Int,
        relativeEnd: Int
    ): List<BatchPrimerResult> {
        return sequences.map { s ->
            val len = s.sequence.length
            val start = relativeStart.coerceIn(1, len.coerceAtLeast(1))
            val end = relativeEnd.coerceIn(start, len)
            BatchPrimerResult(s.name, Cloning.designPrimers(s.sequence, start, end))
        }
    }

    /** 批量导出：FASTA / GenBank */
    fun batchExport(sequences: List<BatchSeq>, format: String): List<BatchExportResult> {
        return sequences.map { s ->
            val rec = SeqRecord(s.name, s.sequence, s.circular)
            val content = if (format.equals("genbank", true)) SeqIO.toGenBank(rec) else SeqIO.toFasta(rec)
            BatchExportResult(s.name, content, format)
        }
    }
}
