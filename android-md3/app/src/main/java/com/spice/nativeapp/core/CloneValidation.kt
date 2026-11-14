package com.spice.nativeapp.core

/**
 * Sanger 一键克隆变异校验引擎 —— main lib/genome/cloningValidation.ts（467 行）1:1
 *
 * 1:1 内容：
 * - pairwiseAlign（main lib/genome/alignment.ts pairwiseAlign 1:1，三模式 + 仿射缺口，
 *   仅本模块内嵌 semi-global 供 validateClone 使用）
 * - validateClone：read ↔ 质粒参考半全局比对、反向互补换向重比对、
 *   PASS/WARNING/FAIL 决策引擎（关键 CDS 非同义/移码 → FAIL；变异 → WARNING；
 *   一致性阈值兜底）、allowSynonymousMutations 豁免、traceQuality
 *   （真实 qScores 或 generateMockSangerTraceQuality 模拟分布）
 * - callVariantsAndPredictEffects / predictSingleMismatchEffect：VEP
 *   （Synonymous/Missense/Nonsense/Frameshift/Deletion/Insertion/Intronic-Intergenic，
 *   含反向链 CDS 密码子翻译）
 *
 * 平台差异（披露）：
 * - 文案键 = main m.cv* / val* zh 原文（六语言词典既有键）；
 * - valPassLog/valWarnLog 为命名占位符（{identity}/{coverage}/{length}），
 *   I18n.tf 仅支持 {vN}，命名替换由调用方（UI）完成；
 * - description/classification 中 status 与英文分类字符串保持 main 英文原样。
 */
object CloneValidation {

    // ──────────────────────── Types（1:1 VariantEffect/SangerTraceQuality/CloneValidationResult） ────────────────────────

    data class CdsFeature(
        val name: String,
        val start: Int,   // 0-based inclusive（与 main 一致；面板侧直接透传 record.features 坐标——main 同样不换算）
        val end: Int,
        val forward: Boolean = true
    )

    data class VariantEffect(
        val type: String,                 // mismatch / insertion / deletion / frameshift
        val refPos: Int,                   // 0-based 参考坐标
        val queryPos: Int,                 // 0-based 查询坐标
        val refSeq: String,
        val querySeq: String,
        val length: Int,
        val inCds: Boolean,
        val cdsName: String? = null,
        val codonIndex: Int? = null,       // CDS 内 0-based
        val originalCodon: String? = null,
        val mutatedCodon: String? = null,
        val originalAminoAcid: String? = null,
        val mutatedAminoAcid: String? = null,
        val classification: String? = null, // Synonymous/Missense/Nonsense/Frameshift/Deletion/Insertion/Intronic-Intergenic；null=main 未定（未定≠同义/非编码 → 决策按关键计）
        val description: String
    )

    data class SangerTraceQuality(
        val positions: List<Int>,
        val qScores: List<Int>,            // Phred 0..~60
        val averageQ: Double,
        val highQualityPercent: Double     // Q30+ 百分比
    )

    data class Result(
        val passed: Boolean,
        val status: String,                // PASS / WARNING / FAIL
        val identity: Double,              // 0..100
        val coverage: Double,              // 0..100
        val alignedLength: Int,
        val variants: List<VariantEffect>,
        val warnings: List<String>,
        val traceQuality: SangerTraceQuality?
    )

    // ──────────────────────── Pairwise Alignment（alignment.ts 1:1） ────────────────────────

    private const val MATCH = 2
    private const val MISMATCH = -1
    private const val GAP_OPEN = -5
    private const val GAP_EXTEND = -1

    /** trace: 0=null, 1=diag, 2=up, 3=left */
    data class PairwiseAlignment(
        val query: String,
        val target: String,
        val alignedQuery: String,
        val alignedTarget: String,
        val midline: String,
        val score: Int,
        val identity: Double,              // 0..1
        val queryStart: Int,
        val queryEnd: Int,
        val targetStart: Int,
        val targetEnd: Int,
        val gaps: Int,
        val mode: String                  // global / local / semi-global
    )

    fun pairwiseAlign(query: String, target: String, mode: String = "global"): PairwiseAlignment {
        val q = query.uppercase()
        val t = target.uppercase()
        val m = q.length
        val n = t.length
        if (m == 0 || n == 0) {
            return PairwiseAlignment(
                query, target,
                if (q.isNotEmpty()) q else "-".repeat(n),
                if (t.isNotEmpty()) t else "-".repeat(m),
                " ".repeat(maxOf(m, n)),
                0, 0.0, 0, 0, 0, 0, maxOf(m, n), mode
            )
        }
        val rows = m + 1
        val cols = n + 1
        val dpScore = Array(rows) { IntArray(cols) }
        val dpTrace = Array(rows) { IntArray(cols) } // 0=null 1=diag 2=up 3=left

        // 初始化（1:1：local/semi-global 首列 0；global 首列仿射；首列 trace=up 仅 global；首行仿射 trace=left）
        for (i in 0 until rows) {
            if (mode == "local" || mode == "semi-global") {
                dpScore[i][0] = 0; dpTrace[i][0] = 0
            } else {
                dpScore[i][0] = GAP_OPEN + (i - 1) * GAP_EXTEND; dpTrace[i][0] = 2
            }
        }
        for (j in 0 until cols) {
            if (mode == "local") {
                dpScore[0][j] = 0; dpTrace[0][j] = 0
            } else {
                dpScore[0][j] = GAP_OPEN + (j - 1) * GAP_EXTEND; dpTrace[0][j] = 3
            }
        }
        dpScore[0][0] = 0; dpTrace[0][0] = 0

        var bestI = 0; var bestJ = 0; var bestScore = 0
        for (i in 1 until rows) {
            for (j in 1 until cols) {
                val matchScore = if (q[i - 1] == t[j - 1]) MATCH else MISMATCH
                val diag = dpScore[i - 1][j - 1] + matchScore
                val up = dpScore[i - 1][j] + (if (dpTrace[i - 1][j] == 2) GAP_EXTEND else GAP_OPEN + GAP_EXTEND)
                val left = dpScore[i][j - 1] + (if (dpTrace[i][j - 1] == 3) GAP_EXTEND else GAP_OPEN + GAP_EXTEND)
                var maxScore = diag
                var trace = 1
                if (up > maxScore) { maxScore = up; trace = 2 }
                if (left > maxScore) { maxScore = left; trace = 3 }
                if (mode == "local" && maxScore < 0) { maxScore = 0; trace = 0 }
                dpScore[i][j] = maxScore; dpTrace[i][j] = trace
                if (mode == "local" && maxScore > bestScore) { bestScore = maxScore; bestI = i; bestJ = j }
            }
        }

        // 回溯
        val aq = StringBuilder(); val at = StringBuilder()
        var i = if (mode == "local") bestI else rows - 1
        var j = if (mode == "local") bestJ else cols - 1
        // semi-global：末列/末行找最优终点（>= → 后者优先，1:1）
        if (mode == "semi-global") {
            var bestEnd = 0
            for (ii in 0 until rows) {
                if (dpScore[ii][cols - 1] >= bestEnd) { bestEnd = dpScore[ii][cols - 1]; i = ii; j = cols - 1 }
            }
            for (jj in 0 until cols) {
                if (dpScore[rows - 1][jj] >= bestEnd) { bestEnd = dpScore[rows - 1][jj]; i = rows - 1; j = jj }
            }
        }
        val qEndIdx = i
        val tEndIdx = j

        while (i > 0 || j > 0) {
            if (mode == "local" && dpScore[i][j] == 0) break
            val trace = dpTrace[i][j]
            if (i > 0 && j > 0 && trace == 1) {
                aq.append(q[i - 1]); at.append(t[j - 1]); i--; j--
            } else if (i > 0 && (trace == 2 || j == 0)) {
                aq.append(q[i - 1]); at.append('-'); i--
            } else {
                aq.append('-'); at.append(t[j - 1]); j--
            }
        }

        val alignedQuery = aq.reverse().toString()
        val alignedTarget = at.reverse().toString()
        val score = dpScore[qEndIdx][tEndIdx]

        var matches = 0
        var gaps = 0
        val mid = StringBuilder()
        for (k in 0 until alignedQuery.length) {
            val qc = alignedQuery[k]; val tc = alignedTarget[k]
            if (qc == tc && qc != '-') { matches++; mid.append('|') }
            else if (qc == '-' || tc == '-') { gaps++; mid.append(' ') }
            else mid.append('.')
        }
        val identity = if (alignedQuery.isNotEmpty()) matches.toDouble() / alignedQuery.length else 0.0

        return PairwiseAlignment(
            query, target, alignedQuery, alignedTarget, mid.toString(), score, identity,
            i, qEndIdx, j, tEndIdx, gaps, mode
        )
    }

    // ──────────────────────── Core Validation Pipeline（1:1） ────────────────────────

    fun validateClone(
        referenceSequence: String,
        querySequence: String,
        cdsFeatures: List<CdsFeature> = emptyList(),
        qScores: List<Int>? = null,
        minIdentityToPass: Double = 99.5,
        minCoverageToPass: Double = 95.0,
        allowSynonymousMutations: Boolean = true
    ): Result {
        val ref = referenceSequence.uppercase()
        var query = querySequence.uppercase()

        val warnings = mutableListOf<String>()

        val alignment = pairwiseAlign(query, ref, "semi-global")
        var rcQueryUsed = false
        var finalAlignment = alignment

        val rcQuery = Bio.revComp(query)
        val rcAlignment = pairwiseAlign(rcQuery, ref, "semi-global")
        if (rcAlignment.identity > alignment.identity) {
            finalAlignment = rcAlignment
            query = rcQuery
            rcQueryUsed = true
            warnings += I18n.t("测序引物方向为反向，已自动对查询序列进行反向互补比对。")
        }

        val identityPercent = finalAlignment.identity * 100
        val totalRefLen = ref.length
        val alignedRefLen = finalAlignment.targetEnd - finalAlignment.targetStart
        val coveragePercent = if (totalRefLen == 0) 0.0 else alignedRefLen.toDouble() / totalRefLen * 100

        val variants = callVariantsAndPredictEffects(finalAlignment, ref, query, cdsFeatures)

        // traceQuality：真实 qScores（换向时反转）或模拟分布
        var traceQuality: SangerTraceQuality? = null
        if (qScores != null && qScores.isNotEmpty()) {
            val resolvedQScores = if (rcQueryUsed) qScores.reversed() else qScores
            val avg = resolvedQScores.sum().toDouble() / resolvedQScores.size
            val highQ = resolvedQScores.count { it >= 30 }
            val highQPercent = highQ.toDouble() / resolvedQScores.size * 100
            traceQuality = SangerTraceQuality(
                positions = List(resolvedQScores.size) { it },
                qScores = resolvedQScores.toList(),
                averageQ = jsToFixed1(avg),
                highQualityPercent = jsToFixed1(highQPercent)
            )
            if (avg < 25) {
                warnings += I18n.tf("测序信噪比低: 平均 Phred 质量分数 (Q-score) 为 {v1} < 25，可能会产生假阳性突变。", jsToFixed1String(avg))
            }
        } else {
            traceQuality = generateMockSangerTraceQuality(finalAlignment)
        }

        // 决策引擎（PASS / WARNING / FAIL）
        var status = "PASS"
        val criticalCdsMutations = variants.filter {
            it.inCds && it.classification != "Synonymous" && it.classification != "Intronic/Intergenic"
        }
        if (criticalCdsMutations.isNotEmpty()) {
            status = "FAIL"
            warnings += I18n.tf("验证失败: 在关键编码区 (CDS) 发现 {v1} 个非同义或移码突变！", criticalCdsMutations.size)
        } else if (variants.isNotEmpty()) {
            status = "WARNING"
            warnings += I18n.tf("验证警告: 比对存在 {v1} 处变异，但它们均处于非编码区，或属于同义突变。", variants.size)
        }

        if (identityPercent < minIdentityToPass) {
            status = "FAIL"
            warnings += I18n.tf(
                "验证失败: 序列一致性 ({v1}%) 低于通过阈值 ({v2}%).",
                jsToFixed2String(identityPercent), jsNumberString(minIdentityToPass)
            )
        }

        val passed = status == "PASS" || (status == "WARNING" && allowSynonymousMutations)

        return Result(
            passed,
            if (passed) (if (status == "WARNING") "WARNING" else "PASS") else "FAIL",
            jsToFixed2(identityPercent),
            jsToFixed2(coveragePercent),
            alignedRefLen,
            variants,
            warnings,
            traceQuality
        )
    }

    // ──────────────────────── Variant Calling & VEP（1:1） ────────────────────────

    fun callVariantsAndPredictEffects(
        alignment: PairwiseAlignment,
        refSeq: String,
        querySeq: String,
        cdsFeatures: List<CdsFeature>
    ): List<VariantEffect> {
        val aQuery = alignment.alignedQuery
        val aRef = alignment.alignedTarget
        val variants = mutableListOf<VariantEffect>()
        var refIdx = alignment.targetStart
        var queryIdx = alignment.queryStart
        val len = aQuery.length
        var i = 0
        while (i < len) {
            val qChar = aQuery[i]
            val rChar = aRef[i]
            if (qChar == rChar) {
                if (rChar != '-') refIdx++
                if (qChar != '-') queryIdx++
                i++
                continue
            }
            if (qChar != '-' && rChar != '-') {
                // Case 1: Mismatch
                variants += predictSingleMismatchEffect(refIdx, queryIdx, rChar, qChar, refSeq, cdsFeatures)
                refIdx++; queryIdx++
            } else if (qChar == '-') {
                // Case 2: Deletion in Query
                var delLen = 1
                while (i + 1 < len && aQuery[i + 1] == '-') { delLen++; i++ }
                val deletedBases = aRef.substring(i - delLen + 1, i + 1).replace("-", "")
                val isFrameshift = delLen % 3 != 0
                val overlappingCds = cdsFeatures.firstOrNull { refIdx >= it.start && refIdx <= it.end }
                val desc = I18n.tf("缺失 (Deletion): 缺失了 {v1} bp ({v2})，起始于参考序列位置 {v3}", delLen, deletedBases, refPosToString(refIdx))
                val effect = VariantEffect(
                    type = if (isFrameshift) "frameshift" else "deletion",
                    refPos = refIdx, queryPos = queryIdx,
                    refSeq = deletedBases, querySeq = "",
                    length = delLen, inCds = overlappingCds != null,
                    cdsName = overlappingCds?.name,
                    classification = if (overlappingCds != null) (if (isFrameshift) "Frameshift" else "Deletion") else "Intronic/Intergenic",
                    description = if (overlappingCds != null) {
                        desc + " " + (if (isFrameshift)
                            I18n.tf("[位于 {v1} 编码区内，引起移码突变！]", overlappingCds.name)
                        else
                            I18n.tf("[位于 {v1} 编码区内，整码缺失]", overlappingCds.name))
                    } else desc
                )
                variants += effect
                refIdx += delLen
            } else {
                // Case 3: Insertion in Query
                var insLen = 1
                while (i + 1 < len && aRef[i + 1] == '-') { insLen++; i++ }
                val insertedBases = aQuery.substring(i - insLen + 1, i + 1).replace("-", "")
                val isFrameshift = insLen % 3 != 0
                val overlappingCds = cdsFeatures.firstOrNull { refIdx >= it.start && refIdx <= it.end }
                val desc = I18n.tf("插入 (Insertion): 插入了 {v1} bp ({v2})，起始于参考序列位置 {v3}", insLen, insertedBases, refPosToString(refIdx))
                val effect = VariantEffect(
                    type = if (isFrameshift) "frameshift" else "insertion",
                    refPos = refIdx, queryPos = queryIdx,
                    refSeq = "", querySeq = insertedBases,
                    length = insLen, inCds = overlappingCds != null,
                    cdsName = overlappingCds?.name,
                    classification = if (overlappingCds != null) (if (isFrameshift) "Frameshift" else "Insertion") else "Intronic/Intergenic",
                    description = if (overlappingCds != null) {
                        desc + " " + (if (isFrameshift)
                            I18n.tf("[位于 {v1} 编码区内，引起移码突变！]", overlappingCds.name)
                        else
                            I18n.tf("[位于 {v1} 编码区内，整码插入]", overlappingCds.name))
                    } else desc
                )
                variants += effect
                queryIdx += insLen
            }
            i++
        }
        return variants
    }

    private fun predictSingleMismatchEffect(
        refPos: Int, queryPos: Int, refBase: Char, queryBase: Char,
        refSeq: String, cdsFeatures: List<CdsFeature>
    ): VariantEffect {
        val desc = I18n.tf("单碱基错配 (Mismatch): {v1} ➔ {v2}，位置 {v3}", refBase.toString(), queryBase.toString(), refPosToString(refPos))
        val effect = VariantEffect(
            type = "mismatch", refPos = refPos, queryPos = queryPos,
            refSeq = refBase.toString(), querySeq = queryBase.toString(),
            length = 1, inCds = false, description = desc
        )
        val cds = cdsFeatures.firstOrNull { refPos >= it.start && refPos <= it.end } ?: return effect.copy(
            classification = "Intronic/Intergenic"
        )

        // 密码子定位（正向/反向链）
        var relPos = refPos - cds.start
        if (!cds.forward) relPos = cds.end - refPos
        val codonIndex = relPos / 3
        val codonOffset = relPos % 3

        var refCodon = ""
        var qCodon = ""
        if (cds.forward) {
            val codonStartRef = cds.start + codonIndex * 3
            refCodon = if (codonStartRef + 3 <= refSeq.length) refSeq.substring(codonStartRef, codonStartRef + 3)
                       else refSeq.substring(codonStartRef)
            val codonArr = refCodon.toCharArray()
            if (codonOffset < codonArr.size) codonArr[codonOffset] = queryBase
            qCodon = String(codonArr)
        } else {
            val codonEndRef = cds.end - codonIndex * 3
            val fwdCodon = if (codonEndRef - 2 >= 0 && codonEndRef + 1 <= refSeq.length) refSeq.substring(codonEndRef - 2, codonEndRef + 1) else ""
            refCodon = Bio.revComp(fwdCodon)
            val fwdCodonArr = fwdCodon.toCharArray()
            val relativeOffsetInFwdCodon = 2 - codonOffset
            if (relativeOffsetInFwdCodon in fwdCodonArr.indices) {
                fwdCodonArr[relativeOffsetInFwdCodon] = Bio.revComp(queryBase.toString()).first()
            }
            qCodon = Bio.revComp(String(fwdCodonArr))
        }

        var eff = effect.copy(inCds = true, cdsName = cds.name, codonIndex = codonIndex)
        if (refCodon.length == 3 && qCodon.length == 3) {
            // main quirk：getAminoAcidStringFromSequenceString 默认剔除尾部终止子（doNotExcludeAsterisk=false），
            // 单密码子调用时 '*' 恒被剥成空串 → mutatedAA=="*" 分支不可达（终止子获得实为 Missense[AA=""]）；
            // 非法碱基密码子 aminoAcid 为 null → 空串。1:1 复刻。
            val originalAA = jsAa(refCodon)
            val mutatedAA = jsAa(qCodon)
            eff = eff.copy(
                originalCodon = refCodon, mutatedCodon = qCodon,
                originalAminoAcid = originalAA, mutatedAminoAcid = mutatedAA
            )
            eff = if (originalAA == mutatedAA) {
                eff.copy(classification = "Synonymous", description = eff.description + " " + I18n.tf(
                    "[位于 {v1} 编码区，同义突变: 密码子 {v2} ➔ {v3} ({v4})]", cds.name, refCodon, qCodon, originalAA))
            } else if (mutatedAA == "*") {
                eff.copy(classification = "Nonsense", description = eff.description + " " + I18n.tf(
                    "[位于 {v1} 编码区，无义突变: 密码子 {v2} ➔ {v3} ({v4} ➔ 终止子 *)]", cds.name, refCodon, qCodon, originalAA))
            } else {
                eff.copy(classification = "Missense", description = eff.description + " " + I18n.tf(
                    "[位于 {v1} 编码区，错义突变: 密码子 {v2} ➔ {v3} ({v4} ➔ {v5})]", cds.name, refCodon, qCodon, originalAA, mutatedAA))
            }
        }
        // 密码子不完整（len != 3）：classification 保持 null（main quirk：未定分类在决策引擎中按关键突变计）
        return eff
    }

    private fun refPosToString(pos: Int): String = "${pos + 1} bp"

    // ──────────────────────── Sanger Trace Mocking（1:1） ────────────────────────

    fun generateMockSangerTraceQuality(alignment: PairwiseAlignment): SangerTraceQuality {
        val size = alignment.query.length
        val qScores = mutableListOf<Int>()
        for (i in 0 until size) {
            // 基线钟形曲线：前 50bp 爬升，尾部 100bp 衰减
            var baselineQ = 45.0
            if (i < 50) {
                baselineQ = 10 + (i / 50.0) * 32
            } else if (i > size - 100) {
                val distFromEnd = size - i
                baselineQ = 15 + (distFromEnd / 100.0) * 30
            }
            val noise = kotlin.math.sin(i / 1.5) * 2 + kotlin.math.cos(i / 4.0) * 1.5
            var resolvedQ = baselineQ + noise
            // 错配位点惩罚（1:1：取比对串列索引；越界时 JS undefined!==undefined → 非错配）
            val isMismatch = i < alignment.alignedQuery.length && i < alignment.alignedTarget.length &&
                alignment.alignedQuery[i] != alignment.alignedTarget[i] &&
                alignment.alignedQuery[i] != '-' && alignment.alignedTarget[i] != '-'
            if (isMismatch) {
                resolvedQ = 12 + kotlin.random.Random.nextDouble() * 6
            }
            qScores += Math.round(maxOf(2.0, minOf(55.0, resolvedQ))).toInt()
        }
        val avg = if (size == 0) 0.0 else qScores.sum().toDouble() / size
        val highQPercent = if (size == 0) 0.0 else qScores.count { it >= 30 }.toDouble() / size * 100
        return SangerTraceQuality(
            positions = List(size) { it },
            qScores = qScores,
            averageQ = jsToFixed1(avg),
            highQualityPercent = jsToFixed1(highQPercent)
        )
    }

    /** main getAminoAcidStringFromSequenceString(codon) 1:1：终止子 → ""（尾部剥离）；非法碱基 → "" */
    private fun jsAa(codon: String): String {
        val aa = Bio.translateCodon(codon)
        return if (aa == '*' || aa == 'X') "" else aa.toString()
    }

    // ──────────────────────── JS 数值语义辅助 ────────────────────────

    /** JS Number(x.toFixed(n))：toFixed 四舍五入（half away from zero，浮点误差同 JS）→ 截尾 0 */
    private fun jsToFixed(v: Double, digits: Int): Double {
        return jsToFixedString(v, digits).toDouble()
    }

    private fun jsToFixedString(v: Double, digits: Int): String {
        // Kotlin "%.nf" HALF_UP 与 JS toFixed（half away from zero）在正数一致
        return "%.${digits}f".format(v)
    }

    private fun jsToFixed1(v: Double) = jsToFixed(v, 1)
    private fun jsToFixed2(v: Double) = jsToFixed(v, 2)
    private fun jsToFixed1String(v: Double) = jsToFixedString(v, 1)
    private fun jsToFixed2String(v: Double) = jsToFixedString(v, 2)

    /** JS String(number)：99.0 → "99"，98.5 → "98.5"（整数尾去 .0） */
    private fun jsNumberString(v: Double): String {
        return if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
