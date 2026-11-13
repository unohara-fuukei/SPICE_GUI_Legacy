package com.spice.nativeapp.core

/**
 * RNA 二级结构预测（对齐 main 分支 lib/genome/rnaStructure.ts，算法级 1:1）
 *
 * Nussinov 最大配对数折叠 + dot-bracket 输出；支持 ssRNA（AU/GC/GU 摆动）与 ssDNA（AT/GC）。
 * MFE 为粗略近似（每对 -1 kcal/mol + 环惩罚 -0.5）。
 */
object RnaStructure {

    data class StemLoop(val start: Int, val end: Int, val type: String)

    data class FoldResult(
        val sequence: String,
        val dotBracket: String,
        val pairs: List<Pair<Int, Int>>,
        val mfe: Double,
        val gcContent: Double,
        val stemCount: Int,
        val loopCount: Int,
        val hairpinCount: Int,
        val stemLoops: List<StemLoop>
    )

    data class SecondaryStructure(
        val sequence: String,
        val isRna: Boolean,
        val structures: List<FoldResult>,
        val longestHairpin: FoldResult?,
        val overallMfe: Double
    )

    private fun pairMap(isRna: Boolean): Map<Char, Char> =
        if (isRna) mapOf('A' to 'U', 'U' to 'A', 'G' to 'C', 'C' to 'G')
        else mapOf('A' to 'T', 'T' to 'A', 'G' to 'C', 'C' to 'G')

    fun canPair(a: Char, b: Char, isRna: Boolean = true, allowWobble: Boolean = true): Boolean {
        val m = pairMap(isRna)
        if (!allowWobble) return m[a] == b
        return m[a] == b || (isRna && a == 'G' && b == 'U')
    }

    /** Nussinov DP 最大配对折叠（minLoop = 发夹环最小残基数，默认 3） */
    fun nussinovFold(seqRaw: String, isRna: Boolean = true, minLoop: Int = 3): FoldResult {
        val seq = seqRaw.uppercase()
        val n = seq.length
        if (n < 4) {
            return FoldResult(seq, ".".repeat(n), emptyList(), 0.0, 0.0, 0, 0, 0, emptyList())
        }
        val pm = pairMap(isRna)

        fun pairsAt(i: Int, k: Int): Boolean =
            pm[seq[i]] == seq[k] || (isRna && seq[i] == 'G' && seq[k] == 'U')

        // DP 填表
        val dp = Array(n) { IntArray(n) }
        for (len in minLoop + 2 until n) {
            var i = 0
            while (i + len < n) {
                val j = i + len
                var best = dp[i + 1][j]
                for (k in i + minLoop + 1..j) {
                    if (pairsAt(i, k)) {
                        val score = (if (i + 1 <= k - 1) dp[i + 1][k - 1] else 0) +
                            1 + (if (k + 1 <= j) dp[k + 1][j] else 0)
                        if (score > best) best = score
                    }
                }
                dp[i][j] = best
                i++
            }
        }

        // 回溯配对
        val pairs = mutableListOf<Pair<Int, Int>>()
        fun backtrack(i: Int, j: Int) {
            if (i >= j) return
            if (dp[i][j] == dp[i + 1][j]) {
                backtrack(i + 1, j)
                return
            }
            for (k in i + minLoop + 1..j) {
                if (pairsAt(i, k)) {
                    val score = (if (i + 1 <= k - 1) dp[i + 1][k - 1] else 0) +
                        1 + (if (k + 1 <= j) dp[k + 1][j] else 0)
                    if (score == dp[i][j]) {
                        pairs += i to k
                        backtrack(i + 1, k - 1)
                        backtrack(k + 1, j)
                        return
                    }
                }
            }
        }
        backtrack(0, n - 1)

        // dot-bracket
        val db = CharArray(n) { '.' }
        for ((i, j) in pairs) { db[i] = '('; db[j] = ')' }

        // GC 含量
        val gc = seq.count { it == 'G' || it == 'C' }
        val gcContent = Math.round(gc * 100.0 / n * 10) / 10.0

        // 近似 MFE
        val pairEnergy = pairs.size * -1.0
        val loopEnergy = -0.5 * maxOf(0, n - pairs.size * 2 - 1)
        val mfe = Math.round((pairEnergy + loopEnergy) * 10) / 10.0

        // 发夹环识别：闭合对内部无其他配对
        val stemLoops = mutableListOf<StemLoop>()
        var hairpinCount = 0
        for ((i, j) in pairs) {
            var isHairpin = true
            for (k in i + 1 until j) {
                if (db[k] == '(' || db[k] == ')') { isHairpin = false; break }
            }
            if (isHairpin && j - i > 2) {
                hairpinCount++
                stemLoops += StemLoop(i, j, "hairpin")
            }
        }
        return FoldResult(
            seq, String(db), pairs, mfe, gcContent,
            pairs.size, hairpinCount, hairpinCount, stemLoops
        )
    }

    /** 滑窗结构搜索：窗口 120 / 步进 60，保留 MFE ≤ minMfe 或有配对的结构 */
    fun findStructures(
        seqRaw: String,
        isRna: Boolean = true,
        windowSize: Int = 120,
        stepSize: Int = 60,
        minMfe: Double = -15.0
    ): SecondaryStructure {
        val cleaned = seqRaw
            .replace("t", "u").replace("T", "U")
            .uppercase()
            .filter { it in "AUGC" }
        val structures = mutableListOf<FoldResult>()
        var i = 0
        while (i < cleaned.length) {
            val end = minOf(i + windowSize, cleaned.length)
            val window = cleaned.substring(i, end)
            if (window.length >= 20) {
                val r = nussinovFold(window, isRna)
                if (r.mfe <= minMfe || r.pairs.isNotEmpty()) structures += r
            }
            if (end >= cleaned.length) break
            i += stepSize
        }
        val longest = structures.maxByOrNull { it.pairs.size }
        val overallMfe = structures.minOfOrNull { it.mfe } ?: 0.0
        return SecondaryStructure(cleaned, isRna, structures, longest, overallMfe)
    }
}
