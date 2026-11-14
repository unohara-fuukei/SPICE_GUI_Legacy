package com.spice.nativeapp.core

/**
 * SPICE Homology, Evolution, and Sequence Analysis Engine（Features 7, 8, 9 & 10）
 * 1:1 移植 main 分支 src/lib/genome/biophysics/sequenceAnalysis.ts：
 * 本地 BLAST 种子延伸比对、Neighbor-Joining 系统发育树、Shannon 信息熵序列 Logo、2D Dot Plot。
 */

// ──────────────────────────────── 7: Local BLAST Seed-and-Extend ────────────────────────────────

data class BlastHit(
    val subjectName: String,
    val score: Int,
    val identity: Double, // 0..100
    val queryStart: Int,
    val queryEnd: Int,
    val subjectStart: Int,
    val subjectEnd: Int,
    val alignmentStr: String // 垂直匹配线
)

/**
 * 高性能本地 Seed-and-Extend 比对（简化 BLAST）。k-mer (k=11) 播种后双向延伸。
 */
fun runLocalBlast(
    query: String,
    subjects: List<Pair<String, String>>, // name to seq
    k: Int = 11
): List<BlastHit> {
    val qSeq = query.uppercase()
    val hits = mutableListOf<BlastHit>()

    for ((subName, subSeqRaw) in subjects) {
        val sSeq = subSeqRaw.uppercase()

        // 1. 为 Subject 建 k-mer 种子索引
        val sIdxMap = HashMap<String, MutableList<Int>>()
        var i = 0
        while (i <= sSeq.length - k) {
            val kmer = sSeq.substring(i, i + k)
            sIdxMap.getOrPut(kmer) { mutableListOf() }.add(i)
            i++
        }

        // 2. 扫描 Query 寻找命中种子
        var qPos = 0
        while (qPos <= qSeq.length - k) {
            val kmer = qSeq.substring(qPos, qPos + k)
            val sPositions = sIdxMap[kmer]
            if (sPositions != null) {
                // 低复杂度过滤：跳过命中过多的种子，防止 N² 卡死
                if (sPositions.size > 8) { qPos++; continue }

                for (sPos in sPositions) {
                    // 3. 双向延伸种子
                    var qLeft = qPos
                    var sLeft = sPos
                    while (qLeft > 0 && sLeft > 0 && qSeq[qLeft - 1] == sSeq[sLeft - 1]) {
                        qLeft--; sLeft--
                    }
                    var qRight = qPos + k
                    var sRight = sPos + k
                    while (qRight < qSeq.length && sRight < sSeq.length && qSeq[qRight] == sSeq[sRight]) {
                        qRight++; sRight++
                    }

                    val matchLen = qRight - qLeft
                    if (matchLen >= k + 4) { // 只记录显著命中
                        val alignmentSliceQuery = qSeq.substring(qLeft, qRight)
                        val alignmentSliceSubject = sSeq.substring(sLeft, sRight)

                        // 一致性 %
                        var matches = 0
                        val sb = StringBuilder()
                        for (j in 0 until matchLen) {
                            if (alignmentSliceQuery[j] == alignmentSliceSubject[j]) {
                                matches++
                                sb.append('|')
                            } else {
                                sb.append(' ')
                            }
                        }
                        val identity = matches.toDouble() / matchLen * 100
                        val score = matches * 2 - (matchLen - matches) * 1 // 标准 match/mismatch 计分

                        // 防止重复记录同一延伸窗口
                        val exists = hits.any { h ->
                            h.subjectName == subName && h.subjectStart == sLeft && h.queryStart == qLeft
                        }
                        if (!exists) {
                            hits.add(
                                BlastHit(
                                    subjectName = subName,
                                    score = score,
                                    identity = Math.round(identity * 10.0) / 10.0,
                                    queryStart = qLeft,   // main 原样输出 0-based 下标
                                    queryEnd = qRight - 1,
                                    subjectStart = sLeft,
                                    subjectEnd = sRight - 1,
                                    alignmentStr = "$alignmentSliceQuery\n${sb}\n$alignmentSliceSubject"
                                )
                            )
                            if (hits.size >= 100) {
                                return hits.sortedByDescending { h -> h.score }
                            }
                        }
                    }
                }
            }
            qPos++
        }
    }
    return hits.sortedByDescending { h -> h.score }
}

// ──────────────────────────────── 8: Neighbor-Joining (NJ) Phylogenetic Tree ────────────────────────────────

class PhylogenyNode(
    val id: String,
    val name: String,
    val branchLength: Double,
    val children: List<PhylogenyNode>,
    val isLeaf: Boolean
)

/**
 * 从进化距离矩阵用 Neighbor-Joining 重建系统发育树。
 * 输出标准 Newick 格式串与层级节点树。
 * 抛出 [EvolutionException]（比对序列数目为零，无法构建进化树）。
 */
class EvolutionException(message: String) : Exception(message)

fun buildPhylogeneticTree(names: List<String>, distanceMatrix: List<List<Double>>): Pair<String, PhylogenyNode> {
    val n = names.size
    if (n == 0) throw EvolutionException("比对序列数目为零，无法构建进化树")

    // 局部克隆
    class Active(
        val id: String,
        val name: String,
        val isLeaf: Boolean,
        val branchLength: Double,
        val children: List<PhylogenyNode>,
        val index: Int
    )

    var activeNodes = names.mapIndexed { idx, name ->
        Active("node_$idx", name, true, 0.0, emptyList(), idx)
    }.toMutableList()

    var matrix = distanceMatrix.map { row -> row.toMutableList() }.toMutableList()
    var nextNodeId = n

    // NJ 迭代至剩 2 个节点
    while (activeNodes.size > 2) {
        val len = activeNodes.size

        // 1. 计算每个活动节点的净分歧度 r_i
        val r = DoubleArray(len)
        for (i in 0 until len) {
            var sum = 0.0
            for (j in 0 until len) {
                if (i != j) sum += matrix[activeNodes[i].index][activeNodes[j].index]
            }
            r[i] = sum
        }

        // 2. Q 准则找最近对：Q_ij = (L - 2) * d_ij - r_i - r_j
        var minQ = Double.MAX_VALUE
        var u = -1; var v = -1
        for (i in 0 until len) {
            for (j in i + 1 until len) {
                val dIJ = matrix[activeNodes[i].index][activeNodes[j].index]
                val qIJ = (len - 2) * dIJ - r[i] - r[j]
                if (qIJ < minQ) { minQ = qIJ; u = i; v = j }
            }
        }

        // 3. 把 u、v 连到新父节点
        val nodeU = activeNodes[u]
        val nodeV = activeNodes[v]
        val dUV = matrix[nodeU.index][nodeV.index]

        // 父节点 W 到子节点的枝长
        // d_wu = 0.5 * d_uv + (r_u - r_v) / (2 * (L - 2))
        val dWU = 0.5 * dUV + (r[u] - r[v]) / (2 * (len - 2))
        val dWV = dUV - dWU

        val nextId = nextNodeId++
        val parent = Active(
            id = "node_$nextId",
            name = "Internal_${nextNodeId + 1}",
            isLeaf = false,
            branchLength = 0.0,
            children = listOf(
                PhylogenyNode(nodeU.id, nodeU.name, Math.max(0.0, Math.round(dWU * 1e5) / 1e5), nodeU.children, nodeU.isLeaf),
                PhylogenyNode(nodeV.id, nodeV.name, Math.max(0.0, Math.round(dWV * 1e5) / 1e5), nodeV.children, nodeV.isLeaf)
            ),
            index = matrix.size // 距离矩阵新行
        )

        // 4. 更新距离矩阵：d_wj = 0.5 * (d_uj + d_vj - d_uv)
        val newDistances = DoubleArray(matrix.size)
        for (j in 0 until matrix.size) {
            newDistances[j] = 0.5 * (matrix[nodeU.index][j] + matrix[nodeV.index][j] - dUV)
        }
        matrix.add(newDistances.toMutableList().also { it.add(0.0) })
        for (rIdx in 0 until matrix.size - 1) {
            matrix[rIdx].add(newDistances[rIdx])
        }

        // 5. 重排活动节点
        activeNodes = activeNodes.filterIndexed { idx, _ -> idx != u && idx != v }.toMutableList()
        activeNodes.add(parent)
    }

    // 最后两个节点用标准根连接
    val rootU = activeNodes[0]
    val rootV = activeNodes[1]
    val dLast = matrix[rootU.index][rootV.index]

    val rootNode = PhylogenyNode(
        id = "root",
        name = "Root",
        branchLength = 0.0,
        isLeaf = false,
        children = listOf(
            PhylogenyNode(rootU.id, rootU.name, Math.round(dLast / 2 * 1e5) / 1e5, rootU.children, rootU.isLeaf),
            PhylogenyNode(rootV.id, rootV.name, Math.round(dLast / 2 * 1e5) / 1e5, rootV.children, rootV.isLeaf)
        )
    )

    // 递归转标准 Newick
    fun toNewick(node: PhylogenyNode): String {
        if (node.isLeaf) return "${node.name}:${node.branchLength}"
        val childrenStr = node.children.joinToString(",") { c -> toNewick(c) }
        return "($childrenStr)${if (node.id == "root") ";" else ":${node.branchLength}"}"
    }

    return toNewick(rootNode) to rootNode
}

// ──────────────────────────────── 9: Shannon Information Entropy Sequence Logo ────────────────────────────────

data class LogoFreq(val char: Char, val bitsScaled: Double, val pct: Double)
data class LogoPosition(
    val position: Int,
    val totalEntropyBits: Double, // 保守 bits
    val frequencies: List<LogoFreq>
)

/**
 * 从多条已比对序列计算 Shannon 信息熵 Logo 谱。
 */
fun calculateSequenceLogo(alignedSeqs: List<String>): List<LogoPosition> {
    if (alignedSeqs.isEmpty()) return emptyList()
    val len = alignedSeqs[0].length

    val logo = mutableListOf<LogoPosition>()
    val maxBits = kotlin.math.log(4.0, 2.0) // 默认核酸

    for (i in 0 until len) {
        val counts = HashMap<Char, Int>()
        var totalValid = 0
        for (seq in alignedSeqs) {
            val char = (seq.getOrNull(i)?.uppercaseChar() ?: '-')
            if (char != '-' && char != ' ') {
                counts[char] = (counts[char] ?: 0) + 1
                totalValid++
            }
        }
        if (totalValid == 0) continue

        // Shannon 熵
        var entropy = 0.0
        val freqs = mutableListOf<LogoFreq>()
        for ((char, count) in counts) {
            val p = count.toDouble() / totalValid
            entropy -= p * kotlin.math.log(p, 2.0)
            freqs.add(LogoFreq(char, 0.0, kotlin.math.round(p * 100 * 10.0) / 10.0))
        }

        // 信息量 R_i = maxBits - H_i
        val rI = Math.max(0.0, maxBits - entropy)

        logo.add(
            LogoPosition(
                position = i + 1,
                totalEntropyBits = Math.round(rI * 1000.0) / 1000.0,
                frequencies = freqs.map { f ->
                    LogoFreq(f.char, Math.round(rI * (f.pct / 100) * 1000.0) / 1000.0, f.pct)
                }.sortedByDescending { f -> f.bitsScaled }
            )
        )
    }
    return logo
}

// ──────────────────────────────── 10: Dot Plot 2D Similarity Matrix ────────────────────────────────

data class DotPlotMatchPoint(val x: Int, val y: Int) // x 为 seq1 下标，y 为 seq2 下标

/**
 * 用滑动窗口在 2D 对角矩阵上计算 Dot Plot 匹配点。
 */
fun calculateDotPlot(
    seq1: String,
    seq2: String,
    windowSize: Int = 15,
    threshold: Int = 12 // windowSize 内最少匹配数
): List<DotPlotMatchPoint> {
    val clean1 = seq1.uppercase()
    val clean2 = seq2.uppercase()
    val points = mutableListOf<DotPlotMatchPoint>()

    val maxCheck = 200 // 限制渲染分辨率，画布即时反馈
    val step1 = maxOf(1, clean1.length / maxCheck)
    val step2 = maxOf(1, clean2.length / maxCheck)

    var i = 0
    while (i <= clean1.length - windowSize) {
        val sub1 = clean1.substring(i, i + windowSize)
        var j = 0
        while (j <= clean2.length - windowSize) {
            val sub2 = clean2.substring(j, j + windowSize)

            // 计算局部匹配碱基
            var matchCount = 0
            for (k in 0 until windowSize) {
                if (sub1[k] == sub2[k]) matchCount++
            }

            if (matchCount >= threshold) {
                points.add(DotPlotMatchPoint(i, j))
                if (points.size >= 1500) {
                    return points // 防低复杂度渲染过载
                }
            }
            j += step2
        }
        i += step1
    }
    return points
}

// ──────────────────────────────── 默认 collections（main gene/+page.svelte 首次启动默认表） ────────────────────────────────

data class EvoCollection(
    val id: String,
    val name: String,
    val size: Int,
    val format: String,
    val tags: List<String>,
    val copyNumber: String,
    val resistance: String,
    val seq: String
)

/** 默认本地 collection 四条（pUC19 / pcDNA3.1 / pET28a / EGFP），序列与 main 逐字符一致。 */
fun defaultGeneCollections(): List<EvoCollection> = listOf(
    EvoCollection(
        id = "col_1",
        name = "pUC19_cloning_vector.gb",
        size = 2686,
        format = "GenBank",
        tags = listOf("Cloning Vector", "pUC Origin", "AmpR"),
        copyNumber = "High (500-700)",
        resistance = "AmpR",
        // 恰含 15 个 Dam (GATC) 甲基化位点
        seq = "GATCTTCG" + "GATC".repeat(14) + "A".repeat(2622)
    ),
    EvoCollection(
        id = "col_2",
        name = "pcDNA3.1_mammalian_expression.gb",
        size = 5428,
        format = "GenBank",
        tags = listOf("Expression", "CMV Promoter", "NeoR"),
        copyNumber = "Medium (30-40)",
        resistance = "AmpR, NeoR",
        // 恰含 8 个 Dam (GATC) 位点
        seq = "GATC" + "GATC".repeat(7) + "G".repeat(5396)
    ),
    EvoCollection(
        id = "col_3",
        name = "pET28a_protein_expression.gb",
        size = 5369,
        format = "GenBank",
        tags = listOf("Bacterial", "T7 Promoter", "KanR"),
        copyNumber = "Low (15-20)",
        resistance = "KanR",
        // 恰含 12 个 Dam (GATC) 位点
        seq = "GATC" + "GATC".repeat(11) + "C".repeat(5321)
    ),
    EvoCollection(
        id = "col_4",
        name = "egfp_reporter_gene.fasta",
        size = 720,
        format = "FASTA",
        tags = listOf("Reporter", "GFP"),
        copyNumber = "N/A",
        resistance = "None",
        // 真实 EGFP CDS 序列，恰含 1 个 GATC 位点
        seq = "ATGGTGAGCAAGGGCGAGGAGCTGTTCACCGGGGTGGTGCCCATCCTGGTCGAGCTGGACGGCGACGTAAACGGCCACAAGTTCAGCGTGTCCGGCGAGGGCGAGGGCGATGCCACCTACGGCAAGCTGACCCTGAAGTTCATCTGCACCACCGGCAAGCTGCCCGTGCCCTGGCCCACCCTCGTGACCACCCTGACCTACGGCGTGCAGTGCTTCAGCCGCTACCCCGACCACATGAAGCAGCACGACTTCTTCAAGTCCGCCATGCCCGAAGGCTACGTCCAGGAGCGCACCATCTTCTTCAAGGACGACGGCAACTACAAGACCCGCGCCGAGGTGAAGTTCGAGGGCGACACCCTGGTGAACCGCATCGAGCTGAAGGGCATCGACTTCAAGGAGGACGGCAACATCCTGGGGCACAAGCTGGAGTACAACTACAACAGCCACAACGTCTATATCATGGCCGACAAGCAGAAGAACGGCATCAAGGTGAACTTCAAGATCCGCCACAACATCGAGGACGGCAGCGTGCAGCTCGCCGACCACTACCAGCAGAACACCCCCATCGGCGACGGCCCCGTGCTGCTGCCCGACAACCACTACCTGAGCACCCAGTCCGCCCTGAGCAAAGACCCCAACGAGAAGCGCGATCACATGGTCCTGCTGGAGTTCGTGACCGCCGCCGGGATCACTCTCGGCATGGACGAGCTGTACAAGTAA"
    )
)
