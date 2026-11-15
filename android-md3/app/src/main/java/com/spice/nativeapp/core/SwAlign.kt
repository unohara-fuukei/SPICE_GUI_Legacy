package com.spice.nativeapp.core

/**
 * Smith-Waterman 局部比对 + SW 自动注释（T7-16 Rust cloning.rs 消息层块）。
 *
 * 1:1 判定基准：main 桌面端 src-tauri/src/cloning.rs 的
 * - run_local_alignment（bio 4.0.1 Aligner，match 2/mismatch -1/gap_open -5/gap_extend -1）
 * - annotate_features_sw（k=12 双链 k-mer prefilter + 同一 Aligner local，
 *   min_span 默认 24、entry 截断 cap 1000、阈值 max(len/2, min_span)、
 *   输出 0-based inclusive 前后向命中、按 start 排序）。
 *
 * Aligner::local 的真身 = bio::alignment::pairwise::mod.rs custom() 模式
 * （clip penalty 全 0）+ filter_clip_operations + 回溯；Kotlin 侧逐分支复刻：
 * - 三状态 DP（S/I/D）+ 零值 clip（prefix/suffix 均 0 分）
 * - I/D 状态开局取 gap_open（首字符直接跳过 clip，与 rust-bio custom 的
 *   “clip 后 gap_open”分支在 clip=0 时 i_score>c_score 恒假不同——真身
 *   TB_START 路径与 rust-bio 逐字节一致由 SwAlignParityTest 对 ref_sw.json 断言）
 * - x_end/y_end 为 exclusive，xstart..xend 对应 query 对齐段；
 *   annotate 的 end = xend-1（0-based inclusive）。
 *
 * 平台差异（README 披露）：main 前端 run_local_alignment 在桌面端因 bio
 * Local 模式 cigar() panic 实际永远走 JS fallback（score=100 假数据）——
 * 命令是死路径；本实现给出真实 SW 结果，alignment_str 不含 CIGAR（与
 * Rust panic 行为对齐——桌面拿不到 CIGAR）。
 */
object SwAlign {

    /** bio AlignmentOperation 等价（Local 模式 clip 已被过滤，只剩 4 态）。 */
    enum class Op { MATCH, SUBST, INS, DEL }

    /** main LocalAlignmentResult（camelCase）等价；cigar 不提供（Local panic quirk）。 */
    data class LocalAlignment(
        val score: Int,
        val queryStart: Int, val queryEnd: Int,        // xstart/xend（exclusive）
        val subjectStart: Int, val subjectEnd: Int,    // ystart/yend（exclusive）
        val operations: List<Op>
    ) {
        val alignmentStr: String
            get() = "Score: $score\nQuery Start: $queryStart\nSubject Start: $subjectStart" +
                "\nCIGAR: ${if (operations.isEmpty()) "" else "unavailable in local mode"}"
    }

    /**
     * bio 4.0.1 Aligner::with_capacity(qLen, sLen, -5, -1, |a,b| if a==b {2} else {-1}).local(q, s)
     * 逐分支复刻。query=x、subject=y；M=x.len（列内循环 i 走 x）、N=y.len（行 j 走 y）。
     */
    fun localAlign(query: String, subject: String): LocalAlignment {
        val x = query.uppercase().toByteArray()
        val y = subject.uppercase().toByteArray()
        val m = x.size
        val n = y.size
        // 与 main run_local_alignment 一致：空序列报错由调用方处理（i18n:beErrInputSequenceEmpty）
        if (m == 0 || n == 0) return LocalAlignment(0, 0, 0, 0, 0, emptyList())

        val GAP_OPEN = -5
        val GAP_EXT = -1
        val MIN = Int.MIN_VALUE / 4   // rust MIN_SCORE 语义（防溢出）

        // rust Traceback 三元位（S/I/D 各 1 字节）：
        // 0=START 1=INS 2=DEL 3=MATCH 4=SUBST 5=XCLIP_PREFIX 6=XCLIP_SUFFIX 7=YCLIP_PREFIX 8=YCLIP_SUFFIX
        val sTb = Array(n + 1) { ByteArray(m + 1) }
        val iTb = Array(n + 1) { ByteArray(m + 1) }
        val dTb = Array(n + 1) { ByteArray(m + 1) }
        // 后缀 clip 长度（Lx[j]=m-i、Ly[i]=n-j）
        val lx = IntArray(n + 1)
        val ly = IntArray(m + 1)

        // 与 rust 同构的双行奇偶槽（k = j%2）
        val S = Array(2) { IntArray(m + 1) }
        val I = Array(2) { IntArray(m + 1) }
        val D = Array(2) { IntArray(m + 1) }
        val sn = IntArray(m + 1)   // Sn[i]：suffix-clip(y) 后 S 的最优

        // ── 初始边界（rust 597-672 的 for k in 0..2 循环）──
        for (k in 0..1) {
            for (i in 0..m) { S[k][i] = MIN; I[k][i] = MIN; D[k][i] = MIN }
            S[k][0] = 0
            if (k == 0) {
                sTb[0][0] = 0; iTb[0][0] = 0; dTb[0][0] = 0   // set_all(TB_START)
                sn[0] = 0    // yclip_suffix = 0（local）
                ly[0] = n
            }
            for (i in 1..m) {
                if (i == 1) {
                    I[k][i] = GAP_OPEN
                    iTb[0][i] = 0    // TB_START
                } else {
                    val iScore = GAP_OPEN + GAP_EXT * (i - 1)
                    val cScore = GAP_OPEN          // xclip_prefix(0) + gap_open
                    if (iScore > cScore) { I[k][i] = iScore; iTb[0][i] = 1 }
                    else { I[k][i] = cScore; iTb[0][i] = 5 }
                }
                if (i == m) sTb[0][i] = 6
                else S[k][i] = MIN
                if (I[k][i] > S[k][i]) { S[k][i] = I[k][i]; sTb[0][i] = 1 }
                if (0 > S[k][i]) { S[k][i] = 0; sTb[0][i] = 5 }   // xclip_prefix > S
                if (i != m && S[k][i] + 0 > S[k][m]) { S[k][m] = S[k][i]; lx[0] = m - i }
                if (k == 0) {
                    if (S[k][i] + 0 > sn[i]) { sn[i] = S[k][i]; ly[i] = n }
                }
            }
        }

        // ── 主循环（rust 674-806）──
        for (j in 1..n) {
            val curr = j % 2
            val prev = 1 - curr

            // i = 0（rust 678-717）
            I[curr][0] = MIN
            if (j == 1) {
                D[curr][0] = GAP_OPEN
                dTb[j][0] = 0    // TB_START
            } else {
                val dScore = GAP_OPEN + GAP_EXT * (j - 1)
                val cScore = GAP_OPEN          // yclip_prefix(0) + gap_open
                if (dScore > cScore) { D[curr][0] = dScore; dTb[j][0] = 2 }
                else { D[curr][0] = cScore; dTb[j][0] = 7 }
            }
            if (D[curr][0] > 0) { S[curr][0] = D[curr][0]; sTb[j][0] = 2 }
            else { S[curr][0] = 0; sTb[j][0] = 7 }
            if (j == n && sn[0] > S[curr][0]) {
                S[curr][0] = sn[0]; sTb[j][0] = 8
            } else if (S[curr][0] + 0 > sn[0]) {
                sn[0] = S[curr][0]; ly[0] = n - j
            }

            for (i in 1..m) S[curr][i] = MIN

            val qc = y[j - 1]
            val xclipScore = maxOf(0, GAP_OPEN + GAP_EXT * (j - 1))   // xclip_prefix + max(yclip_prefix, go+ge*(j-1))

            for (i in 1..m) {
                val p = x[i - 1]
                val mScore = S[prev][i - 1] + if (p == qc) 2 else -1

                val iScoreExt = I[curr][i - 1] + GAP_EXT
                val iScoreOpen = S[curr][i - 1] + GAP_OPEN
                val bestI: Int
                if (iScoreExt > iScoreOpen) { bestI = iScoreExt; iTb[j][i] = 1 }
                else { bestI = iScoreOpen; iTb[j][i] = sTb[j][i - 1] }

                val dScoreExt = D[prev][i] + GAP_EXT
                val dScoreOpen = S[prev][i] + GAP_OPEN
                val bestD: Int
                if (dScoreExt > dScoreOpen) { bestD = dScoreExt; dTb[j][i] = 2 }
                else { bestD = dScoreOpen; dTb[j][i] = sTb[j - 1][i] }

                sTb[j][i] = 6   // TB_XCLIP_SUFFIX 默认
                var bestS = S[curr][i]
                if (mScore > bestS) { bestS = mScore; sTb[j][i] = if (p == qc) 3 else 4 }
                if (bestI > bestS) { bestS = bestI; sTb[j][i] = 1 }
                if (bestD > bestS) { bestS = bestD; sTb[j][i] = 2 }
                if (xclipScore > bestS) { bestS = xclipScore; sTb[j][i] = 5 }
                val yclipScore = GAP_OPEN + GAP_EXT * (i - 1)
                if (yclipScore > bestS) { bestS = yclipScore; sTb[j][i] = 7 }

                S[curr][i] = bestS; I[curr][i] = bestI; D[curr][i] = bestD

                // suffix clip(x)（rust 792-796）
                if (S[curr][i] + 0 > S[curr][m]) { S[curr][m] = S[curr][i]; lx[j] = m - i }
                // suffix clip(y)（rust 798-802）
                if (S[curr][i] + 0 > sn[i]) { sn[i] = S[curr][i]; ly[i] = n - j }
            }
        }

        // ── 末列（j=n）后处理（rust 808-843）──
        val curr = n % 2
        for (i in 0..m) {
            if (sn[i] > S[curr][i]) { S[curr][i] = sn[i]; sTb[n][i] = 8 }
            if (S[curr][i] + 0 > S[curr][m]) { S[curr][m] = S[curr][i]; lx[n] = m - i; sTb[n][m] = 6 }
        }
        for (i in 1..m) {
            val sScore = S[curr][i - 1] + GAP_OPEN
            if (sScore > I[curr][i]) { I[curr][i] = sScore; iTb[n][i] = sTb[n][i - 1] }
            if (sScore > S[curr][i]) {
                S[curr][i] = sScore; sTb[n][i] = 1    // TB_INS
                if (S[curr][i] + 0 > S[curr][m]) { S[curr][m] = S[curr][i]; lx[n] = m - i; sTb[n][m] = 6 }
            }
        }

        val score = S[curr][m]

        // 回溯（rust：i=m,j=n 起，从 s_bits 走）
        var i = m; var j = n
        var xstart = 0; var ystart = 0; var xend = m; var yend = n
        val opsRev = ArrayList<Op>()
        var layer = sTb[j][i].toInt()
        loop@ while (true) {
            when (layer) {
                0 -> break@loop                                    // TB_START
                1 -> { opsRev.add(Op.INS); layer = iTb[j][i].toInt(); i-- }          // TB_INS
                2 -> { opsRev.add(Op.DEL); layer = dTb[j][i].toInt(); j-- }          // TB_DEL
                3 -> { opsRev.add(Op.MATCH); layer = sTb[j-1][i-1].toInt(); i--; j-- } // TB_MATCH
                4 -> { opsRev.add(Op.SUBST); layer = sTb[j-1][i-1].toInt(); i--; j-- } // TB_SUBST
                5 -> { xstart = i; i = 0; layer = sTb[j][0].toInt() }       // TB_XCLIP_PREFIX：rust get(0,j) → x=0 列（clip len 不进 ops）
                6 -> { i -= lx[j]; xend = i; layer = sTb[j][i].toInt() }    // TB_XCLIP_SUFFIX
                7 -> { ystart = j; j = 0; layer = sTb[0][i].toInt() }  // TB_YCLIP_PREFIX：跳到 x=i、j=0 的 S 位
                8 -> { j -= ly[i]; yend = j; layer = sTb[j][i].toInt() }    // TB_YCLIP_SUFFIX
                else -> break@loop
            }
        }
        opsRev.reverse()
        // Local：filter_clip_operations —— 本实现 clip 不进 ops，无需过滤。
        // rust Alignment.filter_clip_operations 后 xstart/ystart 由 clip 分支记录。
        return LocalAlignment(score, xstart, xend, ystart, yend, opsRev)
    }

    // ───────── annotate_features_sw（cloning.rs:1520 逐行） ─────────

    data class SwEntry(val name: String, val seq: String)
    data class SwHit(val name: String, val start: Int, val end: Int, val strand: String, val score: Int)

    private fun code4(b: Byte): Int? = when (b) {
        'A'.code.toByte() -> 0; 'C'.code.toByte() -> 1; 'G'.code.toByte() -> 2; 'T'.code.toByte() -> 3; else -> null
    }

    private fun kmerCode(s: ByteArray, from: Int, k: Int): Long? {
        var c = 0L
        for (idx in from until from + k) {
            val v = code4(s[idx]) ?: return null
            c = (c shl 2) or v.toLong()
        }
        return c
    }

    private fun rcKmerCode(s: ByteArray, from: Int, k: Int): Long? {
        var c = 0L
        for (idx in from + k - 1 downTo from) {
            val v = code4(s[idx]) ?: return null
            c = (c shl 2) or (3 - v).toLong()
        }
        return c
    }

    private fun revCompBytes(s: ByteArray): ByteArray =
        s.reversedArray().map { b -> when (b) { 'A'.code.toByte() -> 'T'.code.toByte(); 'T'.code.toByte() -> 'A'.code.toByte(); 'C'.code.toByte() -> 'G'.code.toByte(); 'G'.code.toByte() -> 'C'.code.toByte(); else -> b } }.toByteArray()

    /**
     * main annotate_features_sw 1:1：k=12 双链 k-mer prefilter + SW local
     * （同 localAlign 计分）；entry >1000bp 截前缀；阈值 score≥max(len/2,min_span)
     * 且 span≥min_span；forward 优先、reverse 仅更高分替换；按 start 升序。
     */
    fun annotateFeaturesSw(query: String, entries: List<SwEntry>, minSpan: Int? = null): List<SwHit> {
        val k = 12
        val ms = minSpan ?: 24
        val q = query.uppercase().toByteArray()
        val n = q.size
        if (n < k) return emptyList()

        val qset = HashSet<Long>(n * 2)
        for (i in 0..n - k) kmerCode(q, i, k)?.let { qset.add(it) }

        val cap = 1000
        val hits = ArrayList<SwHit>()
        for (e in entries) {
            val pFull = e.seq.uppercase().toByteArray()
            val m = pFull.size
            if (m < 12) continue
            var shareFwd = false; var shareRev = false
            val mk = m - k
            outer@ for (i in 0..mk) {
                kmerCode(pFull, i, k)?.let { if (it in qset) shareFwd = true }
                rcKmerCode(pFull, i, k)?.let { if (it in qset) shareRev = true }
                if (shareFwd && shareRev) break@outer
            }
            if (!shareFwd && !shareRev) continue

            val p = if (m > cap) pFull.copyOf(cap) else pFull
            val pm = p.size
            var best: Triple<Int, Int, String>? = null   // (start, end, strand)
            var bestScore = Int.MIN_VALUE
            if (shareFwd) {
                val al = localAlignOn(q, p, n, pm)
                val span = (al.queryEnd - al.queryStart).coerceAtLeast(0)
                val threshold = maxOf(pm / 2, ms)
                if (al.score >= threshold && span >= ms) {
                    best = Triple(al.queryStart, (al.queryEnd - 1).coerceAtLeast(0), "forward")
                    bestScore = al.score
                }
            }
            if (shareRev) {
                val rp = revCompBytes(p)
                val al = localAlignOn(q, rp, n, rp.size)
                val span = (al.queryEnd - al.queryStart).coerceAtLeast(0)
                val threshold = maxOf(rp.size / 2, ms)
                if (al.score >= threshold && span >= ms) {
                    if (best == null || al.score > bestScore) {
                        best = Triple(al.queryStart, (al.queryEnd - 1).coerceAtLeast(0), "reverse")
                        bestScore = al.score
                    }
                }
            }
            best?.let { (st, en, strand) -> hits.add(SwHit(e.name, st, en, strand, bestScore)) }
        }
        hits.sortBy { it.start }
        return hits
    }

    /** localAlign 的字节数组版（复用主 DP；query 已大写）。 */
    private fun localAlignOn(q: ByteArray, p: ByteArray, mUnused: Int, nUnused: Int): LocalAlignment =
        localAlign(String(q, Charsets.US_ASCII), String(p, Charsets.US_ASCII))
}
