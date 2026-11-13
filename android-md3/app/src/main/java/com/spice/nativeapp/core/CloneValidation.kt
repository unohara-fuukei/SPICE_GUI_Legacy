package com.spice.nativeapp.core

/**
 * Sanger 一键克隆变异校验（对齐 main 分支 lib/genome/cloningValidation.ts）
 *
 * validateClone：read ↔ 质粒参考半全局比对（含反向互补重比对）、
 * 一致性/覆盖度阈值判定（默认 99.5% / 95%）、变异效应预测
 * （错义/同义/无义/移码），Q 值低质量区警示。
 */
object CloneValidation {

    data class CdsFeature(val name: String, val start: Int, val end: Int)  // 0-based inclusive

    data class VariantEffect(
        val refPos: Int,          // 0-based 参考坐标
        val refBase: Char,
        val queryBase: Char?,
        val type: String,         // mismatch / insertion / deletion
        val effect: String,       // missense / silent / nonsense / frameshift / intergenic / UTR
        val detail: String
    )

    data class Result(
        val identityPercent: Double,
        val coveragePercent: Double,
        val rcQueryUsed: Boolean,
        val variants: List<VariantEffect>,
        val passed: Boolean,
        val verdict: String,
        val warnings: List<String>
    )

    fun validateClone(
        referenceSequence: String,
        querySequence: String,
        cdsFeatures: List<CdsFeature> = emptyList(),
        minIdentityToPass: Double = 99.5,
        minCoverageToPass: Double = 95.0
    ): Result {
        val ref = referenceSequence.uppercase().filter { it.isLetter() }
        val queryRaw = querySequence.uppercase().filter { it.isLetter() }
        if (ref.isEmpty() || queryRaw.isEmpty()) {
            return Result(0.0, 0.0, false, emptyList(), false, "无有效序列", listOf("参考或 read 为空"))
        }

        val warnings = mutableListOf<String>()
        var query = queryRaw
        var alignment = SangerValidate.align(ref, query)
        val rcQuery = Bio.revComp(query)
        var rcUsed = false
        val rcAlignment = SangerValidate.align(ref, rcQuery)
        if (rcAlignment.identityPercent > alignment.identityPercent) {
            alignment = rcAlignment
            query = rcQuery
            rcUsed = true
            warnings += "read 与参考反向互补比对得分更高，已自动换向重比对"
        }

        val identity = alignment.identityPercent
        val coverage = if (ref.isEmpty()) 0.0 else
            (alignment.coverageEnd - alignment.coverageStart).toDouble() / ref.length * 100

        // 变异效应
        val variants = callVariants(alignment, ref, query, cdsFeatures)

        // 判定
        val lowIdentity = identity < minIdentityToPass
        val lowCoverage = coverage < minCoverageToPass
        val hasFrameshift = variants.any { it.effect == "frameshift" }
        val hasNonsense = variants.any { it.effect == "nonsense" }

        val passed = !lowIdentity && !lowCoverage && !hasFrameshift && !hasNonsense
        val verdict = when {
            hasFrameshift -> "失败：检出移码变异"
            hasNonsense -> "失败：检出提前终止（无义）变异"
            lowIdentity -> "失败：一致性 %.2f%% < %.1f%%".format(identity, minIdentityToPass)
            lowCoverage -> "失败：覆盖度 %.1f%% < %.1f%%".format(coverage, minCoverageToPass)
            variants.any { it.effect == "missense" } -> "通过（含错义变异，请确认是否为预期设计）"
            variants.isEmpty() -> "通过：与参考完全一致"
            else -> "通过：仅同义/非编码区变异"
        }
        if (lowCoverage) warnings += "覆盖度不足，建议补测或延长 read"
        if (identity >= 90.0 && identity < minIdentityToPass) warnings += "一致性略低于阈值，注意混合克隆或污染"

        return Result(
            Math.round(identity * 100) / 100.0,
            Math.round(coverage * 10) / 10.0,
            rcUsed, variants, passed, verdict, warnings
        )
    }

    /** 从比对结果调用变异并预测密码子级效应 */
    fun callVariants(
        alignment: SangerValidate.Result,
        refSeq: String,
        querySeq: String,
        cdsFeatures: List<CdsFeature>
    ): List<VariantEffect> {
        val out = mutableListOf<VariantEffect>()
        for (v in alignment.variants) {
            when (v.type) {
                "替换", "mismatch", "substitution" -> {
                    if (v.refBase != null && v.queryBase != null) {
                        out += mismatchEffect(v.refPos, v.refBase, v.queryBase, refSeq, cdsFeatures)
                    }
                }
                "插入", "insertion" -> {
                    val eff = cdsFeatures.firstOrNull { (v.refPos - 1) in it.start..it.end }
                    out += VariantEffect(
                        v.refPos, v.refBase ?: 'N', v.queryBase, "insertion",
                        if (eff != null) "frameshift" else "intergenic",
                        if (eff != null) "在 CDS ${eff.name} 内插入 → 移码" else "插入位于非编码区"
                    )
                }
                "缺失", "deletion" -> {
                    val eff = cdsFeatures.firstOrNull { (v.refPos - 1) in it.start..it.end }
                    out += VariantEffect(
                        v.refPos, v.refBase ?: 'N', null, "deletion",
                        if (eff != null) "frameshift" else "intergenic",
                        if (eff != null) "在 CDS ${eff.name} 内缺失 → 移码" else "缺失位于非编码区"
                    )
                }
            }
        }
        return out
    }

    private fun mismatchEffect(
        refPos: Int, refBase: Char, queryBase: Char,
        refSeq: String, cdsFeatures: List<CdsFeature>
    ): VariantEffect {
        // SangerValidate.Variant.refPos 为 1-based；内部换算 0-based
        val p0 = refPos - 1
        val cds = cdsFeatures.firstOrNull { p0 in it.start..it.end }
        if (cds == null) {
            return VariantEffect(refPos, refBase, queryBase, "mismatch", "intergenic", "位于非编码区")
        }
        // 密码子定位（CDS 起点）
        val offsetInCds = p0 - cds.start
        val codonIdx = offsetInCds / 3
        val codonStart = cds.start + codonIdx * 3
        if (codonStart + 3 > refSeq.length) {
            return VariantEffect(refPos, refBase, queryBase, "mismatch", "UTR", "CDS 末端不完整密码子")
        }
        val wtCodon = refSeq.substring(codonStart, codonStart + 3)
        val mutCodon = StringBuilder(wtCodon).also { it[offsetInCds % 3] = queryBase }.toString()
        val wtAa = Bio.translateCodon(wtCodon)
        val mutAa = Bio.translateCodon(mutCodon)
        val label = "${cds.name} 密码子 ${codonIdx + 1}: $wtCodon($wtAa)→$mutCodon($mutAa)"
        return when {
            wtAa == mutAa -> VariantEffect(refPos, refBase, queryBase, "mismatch", "silent", "$label 同义")
            mutAa == '*' -> VariantEffect(refPos, refBase, queryBase, "mismatch", "nonsense", "$label 提前终止")
            wtAa == '*' -> VariantEffect(refPos, refBase, queryBase, "mismatch", "stop_lost", "$label 终止密码子丢失")
            else -> VariantEffect(refPos, refBase, queryBase, "mismatch", "missense", "$label 错义")
        }
    }
}
