package com.spice.nativeapp.core

/**
 * 测序验证，对应原版 genome/alignment.ts：
 * alignToReference（半全局比对）、callVariants（变异检出）、validateSangerAgainstRef（一致性判定）。
 * 用 k-mer 定锚 + 局部 Needleman-Wunsch，对千级长度 reads 可即时返回。
 */
object SangerValidate {

    data class Variant(val refPos: Int, val refBase: Char?, val queryBase: Char?, val type: String)

    data class Result(
        val alignedLength: Int,
        val identities: Int,
        val mismatches: Int,
        val insertions: Int,
        val deletions: Int,
        val identityPercent: Double,
        val coverageStart: Int,
        val coverageEnd: Int,
        val offset: Int,
        val variants: List<Variant>,
        val verdict: String,
        val warnings: List<String>
    )

    private const val MATCH = 2
    private const val MISMATCH = -3
    private const val GAP = -5

    /** 半全局比对：参考为长序列（模板），query 为测序 read */
    fun align(reference: String, query: String, maxCells: Int = 4_000_000): Result {
        val ref = reference.uppercase().filter { it.isLetter() }
        val q = query.uppercase().filter { it.isLetter() }
        if (ref.isEmpty() || q.isEmpty()) {
            return Result(0, 0, 0, 0, 0, 0.0, 0, 0, 0, emptyList(), "无有效序列", listOf("参考或 read 为空"))
        }

        // 定锚：用 query 前 20 nt 在参考中模糊定位，缩小区间
        val anchorLen = minOf(24, q.length)
        val anchor = q.take(anchorLen)
        var offset = 0
        var bestScore = -1
        val searchLimit = (ref.length - anchorLen).coerceAtLeast(0)
        for (i in 0..searchLimit) {
            var score = 0
            for (k in 0 until anchorLen) if (ref[i + k] == anchor[k]) score++
            if (score > bestScore) { bestScore = score; offset = i }
            if (bestScore == anchorLen) break
        }
        if (bestScore < anchorLen / 2) offset = 0

        // 取局部窗口：offset 前 60 / 后 q.length + 60
        val start = (offset - 60).coerceAtLeast(0)
        val end = (offset + q.length + 60).coerceAtMost(ref.length)
        val window = ref.substring(start, end)

        if (window.length.toLong() * q.length.toLong() > maxCells) {
            return Result(0, 0, 0, 0, 0, 0.0, 0, 0, offset, emptyList(),
                "序列过长，超出即时比对上限", listOf("参考窗口 ${window.length} × read ${q.length} 超限"))
        }

        // Needleman-Wunsch（semi-global：参考两端可免罚）
        val n = window.length
        val m = q.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) dp[i][0] = 0            // 参考可自由裁切
        for (j in 1..m) dp[0][j] = j * GAP      // read 必须全部比上
        for (i in 1..n) {
            for (j in 1..m) {
                val s = if (window[i - 1] == q[j - 1]) MATCH else MISMATCH
                dp[i][j] = maxOf(
                    dp[i - 1][j - 1] + s,
                    dp[i - 1][j] + GAP,
                    dp[i][j - 1] + GAP
                )
            }
        }
        // 终点：参考可自由裁切 → 取最后一列最大
        var endI = n
        var best = dp[n][m]
        for (i in 1..n) if (dp[i][m] > best) { best = dp[i][m]; endI = i }

        // 回溯
        var i = endI
        var j = m
        var identities = 0
        var mismatches = 0
        var insertions = 0
        var deletions = 0
        val variants = mutableListOf<Variant>()
        var lastRef = -1
        while (j > 0) {
            when {
                i > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (window[i - 1] == q[j - 1]) MATCH else MISMATCH) -> {
                    val refBase = window[i - 1]
                    val qBase = q[j - 1]
                    val refPos = start + i
                    if (refBase == qBase) identities++ else {
                        mismatches++
                        variants += Variant(refPos, refBase, qBase, "替换")
                    }
                    lastRef = refPos
                    i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + GAP -> {
                    deletions++
                    variants += Variant(start + i, window[i - 1], null, "缺失")
                    i--
                }
                else -> {
                    insertions++
                    variants += Variant(if (lastRef > 0) lastRef else start + i, null, q[j - 1], "插入")
                    j--
                }
            }
        }
        val coverageStart = start + i + 1
        val coverageEnd = start + endI
        val total = identities + mismatches + insertions + deletions
        val identity = if (total == 0) 0.0 else identities * 100.0 / total

        val verdict = when {
            identity >= 99.9 && insertions == 0 && deletions == 0 -> "完全匹配"
            identity >= 95.0 -> "高度一致（建议核对变异位点）"
            identity >= 85.0 -> "部分一致（可能存在杂合/混合峰）"
            else -> "不一致（检查引物、模板或拼接方向）"
        }
        val warnings = mutableListOf<String>()
        if (insertions > 0 || deletions > 0) warnings += "存在 ${insertions} 处插入、${deletions} 处缺失（indel 常见于混合峰或同聚物）"
        if (identity < 85) warnings += "一致性偏低，建议查看 chromatogram 质量"
        return Result(
            alignedLength = total,
            identities = identities,
            mismatches = mismatches,
            insertions = insertions,
            deletions = deletions,
            identityPercent = identity,
            coverageStart = coverageStart,
            coverageEnd = coverageEnd,
            offset = start,
            variants = variants.take(200),
            verdict = verdict,
            warnings = warnings
        )
    }
}
