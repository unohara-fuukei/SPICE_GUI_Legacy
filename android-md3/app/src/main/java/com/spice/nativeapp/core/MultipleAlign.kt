package com.spice.nativeapp.core

/**
 * 多序列比对（对齐 main 分支 lib/genome/alignment.ts 的 pairwiseAlign/multipleAlign）
 *
 * 半全局 Needleman-Wunsch（query 为局部 read，target 为参考）+ 以参考为中心的
 * 渐进式多序列比对 + 共识序列与逐列保守性。
 */
object MultipleAlign {

    data class Pairwise(
        val alignedQuery: String,
        val alignedTarget: String,
        val identity: Double,     // 0..1（仅比对区内）
        val queryStart: Int,
        val targetStart: Int
    )

    private const val MATCH = 2
    private const val MISMATCH = -1
    private const val GAP = -4

    /** 半全局比对：query 两端可自由延伸（target 为参考/master） */
    fun pairwiseAlign(query: String, target: String): Pairwise {
        val q = query.uppercase().filter { it.isLetter() }
        val t = target.uppercase().filter { it.isLetter() }
        val n = q.length; val m = t.length
        if (n == 0 || m == 0) return Pairwise("", "", 0.0, 0, 0)

        val NEG = Int.MIN_VALUE / 4
        // dp[i][j]：q 前 i 对 t 前 j。半全局：target（参考）两端免罚，query（read）须整体比对
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (j in 1..m) dp[0][j] = 0            // query 可从 target 任意位置开始（免罚）
        for (i in 1..n) dp[i][0] = NEG           // query 不允许整体前置空对

        for (i in 1..n) {
            for (j in 1..m) {
                val diag = dp[i - 1][j - 1] + if (q[i - 1] == t[j - 1]) MATCH else MISMATCH
                val up = dp[i - 1][j] + GAP
                val left = dp[i][j - 1] + GAP
                dp[i][j] = maxOf(diag, up, left)
            }
        }
        // 找最优终点：i = n 固定，j 自由（query 尾部对 target 内部结束）
        var bestJ = 0; var bestScore = Int.MIN_VALUE
        for (j in 0..m) if (dp[n][j] > bestScore) { bestScore = dp[n][j]; bestJ = j }

        // 回溯
        val aq = StringBuilder(); val at = StringBuilder()
        var i = n; var j = bestJ
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 -> {
                    val diag = dp[i - 1][j - 1] + if (q[i - 1] == t[j - 1]) MATCH else MISMATCH
                    if (dp[i][j] == diag) {
                        aq.append(q[i - 1]); at.append(t[j - 1]); i--; j--
                    } else if (dp[i][j] == dp[i - 1][j] + GAP) {
                        aq.append(q[i - 1]); at.append('-'); i--
                    } else {
                        aq.append('-'); at.append(t[j - 1]); j--
                    }
                }
                i > 0 -> { aq.append(q[i - 1]); at.append('-'); i-- }
                else -> { aq.append('-'); at.append(t[j - 1]); j-- }
            }
        }
        val alignedQ = aq.reverse().toString()
        val alignedT = at.reverse().toString()

        // 起点：跳过 target 前导 gap
        var tStart = 0
        while (tStart < alignedT.length && alignedT[tStart] == '-') tStart++
        val qStart = tStart  // 对齐串同长，此处近似（首列同为 '-' 时归零）

        var match = 0; var cols = 0
        for (k in alignedQ.indices) {
            if (alignedQ[k] != '-' && alignedT[k] != '-') {
                cols++
                if (alignedQ[k] == alignedT[k]) match++
            }
        }
        val identity = if (cols > 0) match.toDouble() / cols else 0.0
        return Pairwise(alignedQ, alignedT, identity, qStart, tStart)
    }

    data class MsaRow(
        val name: String, val alignedSeq: String,
        val ungappedSeq: String, val isRef: Boolean
    )

    data class MultiAlignment(
        val rows: List<MsaRow>,
        val consensus: String,
        val conservation: List<Double>,  // 每列 0..1
        val width: Int,
        val identity: Double             // 总体一致性 0..1
    )

    fun multipleAlign(sequences: List<Pair<String, String>>, refIdx: Int = 0): MultiAlignment {
        if (sequences.isEmpty()) return MultiAlignment(emptyList(), "", emptyList(), 0, 0.0)
        if (sequences.size == 1) {
            val s = sequences[0].second.uppercase()
            return MultiAlignment(
                listOf(MsaRow(sequences[0].first, s, s, true)),
                s, List(s.length) { 1.0 }, s.length, 1.0
            )
        }
        val ref = sequences[refIdx]
        val aligned = mutableListOf<MsaRow>()
        var masterAln = ref.second.uppercase()
        aligned += MsaRow(ref.first, masterAln, masterAln, true)

        for (s in sequences.indices) {
            if (s == refIdx) continue
            val seq = sequences[s].second.uppercase()
            val aln = pairwiseAlign(seq, masterAln.replace("-", ""))
            val aq = aln.alignedQuery
            val at = aln.alignedTarget
            val newMaster = StringBuilder()
            val newSeqAligned = StringBuilder()
            var ri = 0
            for (k in aq.indices) {
                when {
                    at[k] == '-' -> { newMaster.append('-'); newSeqAligned.append(aq[k]) }
                    aq[k] == '-' -> { newMaster.append(masterAln.getOrNull(ri) ?: '-'); newSeqAligned.append('-'); ri++ }
                    else -> { newMaster.append(masterAln.getOrNull(ri) ?: '-'); newSeqAligned.append(aq[k]); ri++ }
                }
            }
            val newMasterStr = newMaster.toString()
            // 旧行按旧 master 非缺口位映射到新 master 位置
            val oldToNew = mutableListOf<Int>()
            for (k in newMasterStr.indices) if (newMasterStr[k] != '-') oldToNew += k
            for (r in aligned.indices) {
                val oldSeq = aligned[r].alignedSeq
                val rebuilt = CharArray(newMasterStr.length) { '-' }
                for (old in oldSeq.indices) {
                    oldToNew.getOrNull(old)?.let { rebuilt[it] = oldSeq[old] }
                }
                aligned[r] = aligned[r].copy(alignedSeq = String(rebuilt))
            }
            masterAln = newMasterStr
            aligned += MsaRow(sequences[s].first, newSeqAligned.toString(), seq, false)
        }

        val width = masterAln.length
        val consensus = StringBuilder()
        val conservation = mutableListOf<Double>()
        for (col in 0 until width) {
            val counts = LinkedHashMap<Char, Int>()  // 保持首见序，并列时取先出现者（同 main 的 Object.entries）
            var total = 0
            for (row in aligned) {
                val ch = row.alignedSeq.getOrNull(col) ?: '-'
                counts[ch] = (counts[ch] ?: 0) + 1
                total++
            }
            var best = '-'; var bestCount = 0
            for ((ch, c) in counts) if (c > bestCount) { best = ch; bestCount = c }
            consensus.append(best)
            conservation += if (total > 0) bestCount.toDouble() / total else 0.0
        }
        var matchCols = 0
        for (col in 0 until width) {
            val ch = aligned.firstOrNull()?.alignedSeq?.getOrNull(col)
            if (ch != null && aligned.all { it.alignedSeq.getOrNull(col) == ch }) matchCols++
        }
        val identity = if (width > 0) matchCols.toDouble() / width else 0.0
        return MultiAlignment(aligned, consensus.toString(), conservation, width, identity)
    }
}
