package com.spice.nativeapp.core

/**
 * 表达盒 / 载体组装领域原语（main src/lib/genome/expressionCassette.ts 1:1 移植）。
 * 坐标约定与 main 一致：算法层 0-based；回写 SeqRecord 时由面板 +1 转 1-based inclusive。
 *
 * 类型用 String 字面量对齐 main 联合类型：
 *  - PartRole: promoter / enhancer / 5_utr / translation_initiation_context / rbs /
 *    signal_peptide / targeting_peptide / linker / fusion_tag / cds / 3_utr / terminator /
 *    polyadenylation_signal / other
 *  - Orientation: forward / reverse
 *  - Topology: linear / circular
 */

data class PartFeature(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val role: String? = null,
    val start: Int,
    val end: Int,
    val strand: Int? = 1,
    val color: String? = null
)

data class CassettePart(
    val id: String,
    val role: String,
    val sequence: String,
    val name: String? = null,
    val orientation: String? = "forward",
    val features: List<PartFeature> = emptyList()
)

data class ExpressionCassette(
    val id: String,
    val parts: List<CassettePart>,
    val order: List<String>? = null,
    val topology: String = "linear"
)

data class VectorFeature(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val role: String? = null,
    val start: Int,
    val end: Int,
    val strand: Int? = null,
    val color: String? = null,
    val required: Boolean? = null,
    val editable: Boolean? = null
)

data class InsertionSite(
    val id: String,
    val type: String? = null,
    val afterFeatureId: String? = null,
    val beforeFeatureId: String? = null,
    val preserveFeatureIds: List<String>? = null,
    val allowedCassetteTypes: List<String>? = null,
    val orientation: String? = "forward"
)

data class ValidationRules(
    val requiredFeatureIds: List<String>? = null,
    val preserveFeatureIds: List<String>? = null,
    val checkReadingFrame: Boolean? = false,
    val forbiddenRestrictionSites: List<String>? = null
)

data class VectorTemplate(
    val id: String,
    val name: String? = null,
    val sequence: String? = null,
    val topology: String,
    val features: List<VectorFeature> = emptyList(),
    val insertionSites: List<InsertionSite> = emptyList(),
    val validationRules: ValidationRules? = null
)

data class ProjectedFeature(
    val sourceId: String,
    val sourcePartId: String? = null,
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val role: String? = null,
    val start: Int,
    val end: Int,
    val strand: Int? = 1,
    val color: String? = null,
    val required: Boolean? = null,
    val editable: Boolean? = null
)

data class PartRange(val partId: String, val role: String, val start: Int, val end: Int)

data class AssemblyResult(
    val sequence: String,
    val topology: String,
    val features: List<ProjectedFeature>,
    val partRanges: List<PartRange>,
    val cassette: ExpressionCassette,
    val vector: VectorTemplate? = null
)

data class ValidationIssue(
    val code: String,
    val message: String,
    val featureId: String? = null,
    val severity: String
)

data class ValidationResult(val valid: Boolean, val issues: List<ValidationIssue>)

data class SequenceDiff(
    val type: String,
    val position: Int,
    val reference: String? = null,
    val actual: String? = null,
    val length: Int
)

object ExpressionCassetteAssembler {

    private val COMPLEMENT: Map<Char, Char> = mapOf(
        'A' to 'T', 'T' to 'A', 'C' to 'G', 'G' to 'C',
        'a' to 't', 't' to 'a', 'c' to 'g', 'g' to 'c'
    )

    /** main orient：reverse 时反向互补；非 ACGT 字符原样保留（1:1 复刻） */
    private fun orient(seq: String, direction: String = "forward"): String {
        if (direction == "forward") return seq
        return seq.reversed().map { COMPLEMENT[it] ?: it }.joinToString("")
    }

    private fun orderedParts(cassette: ExpressionCassette): List<CassettePart> {
        val order = cassette.order ?: return cassette.parts
        val byId = cassette.parts.associateBy { it.id }
        return order.mapNotNull { byId[it] }
    }

    private fun featureLength(f: PartFeature): Int = if (f.end >= f.start) f.end - f.start + 1 else 0

    /** 按稳定顺序组装部件并投影其注释（main assembleExpressionCassette） */
    fun assembleExpressionCassette(cassette: ExpressionCassette): AssemblyResult {
        val parts = orderedParts(cassette)
        val features = mutableListOf<ProjectedFeature>()
        val partRanges = mutableListOf<PartRange>()
        var sequence = ""
        var offset = 0
        for (part in parts) {
            val dna = orient(part.sequence, part.orientation ?: "forward")
            val start = offset
            val end = offset + dna.length - 1
            partRanges += PartRange(part.id, part.role, start, end)
            for (f in part.features) {
                val len = featureLength(f)
                val localStart = maxOf(0, f.start)
                val localEnd = minOf(dna.length - 1, f.end)
                if (len == 0 || localStart > localEnd) continue
                val reverse = part.orientation == "reverse"
                val projectedId = f.id ?: "${part.id}:${f.name ?: f.type ?: "feature"}"
                features += ProjectedFeature(
                    sourceId = f.id ?: part.id,
                    sourcePartId = part.id,
                    id = projectedId,
                    name = f.name,
                    type = f.type,
                    role = f.role,
                    start = if (reverse) offset + dna.length - 1 - localEnd else offset + localStart,
                    end = if (reverse) offset + dna.length - 1 - localStart else offset + localEnd,
                    strand = if (reverse) -((f.strand ?: 1)) else f.strand,
                    color = f.color
                )
            }
            sequence += dna
            offset += dna.length
        }
        return AssemblyResult(
            sequence = sequence,
            topology = cassette.topology,
            features = features,
            partRanges = partRanges,
            cassette = cassette
        )
    }

    /** 将组装好的表达盒插入载体，保持载体特征坐标（main assembleVector） */
    fun assembleVector(
        template: VectorTemplate,
        cassette: ExpressionCassette,
        insertionSiteId: String? = null
    ): AssemblyResult {
        val site = template.insertionSites.firstOrNull()?.let {
            insertionSiteId?.let { id -> template.insertionSites.find { s -> s.id == id } }
                ?: template.insertionSites.first()
        }
        val insert = assembleExpressionCassette(cassette)
        val vectorSeq = template.sequence ?: ""
        if (site == null) return insert.copy(vector = template)

        val after = site.afterFeatureId?.let { id -> template.features.find { f -> f.id == id } }
        val before = site.beforeFeatureId?.let { id -> template.features.find { f -> f.id == id } }
        val at = when {
            before != null -> before.start
            after != null -> after.end + 1
            else -> vectorSeq.length
        }
        val cassetteSeq = orient(insert.sequence, site.orientation ?: "forward")
        val shift = cassetteSeq.length
        val features = template.features.map { f ->
            ProjectedFeature(
                sourceId = f.id ?: f.name ?: "vector",
                id = f.id,
                name = f.name,
                type = f.type,
                role = f.role,
                start = if (f.start >= at) f.start + shift else f.start,
                end = if (f.end >= at) f.end + shift else f.end,
                strand = f.strand,
                color = f.color,
                required = f.required,
                editable = f.editable
            )
        }.toMutableList()
        for (f in insert.features) features += f.copy(start = at + f.start, end = at + f.end)
        val sequence = vectorSeq.substring(0, at) + cassetteSeq + vectorSeq.substring(at)
        return AssemblyResult(
            sequence = sequence,
            topology = template.topology,
            features = features,
            partRanges = insert.partRanges.map { r -> r.copy(start = r.start + at, end = r.end + at) },
            cassette = cassette,
            vector = template
        )
    }

    /** 校验结构要求与简单序列约束（main validateVectorAssembly） */
    fun validateVectorAssembly(
        result: AssemblyResult,
        rules: ValidationRules? = result.vector?.validationRules
    ): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val ids = result.features.mapNotNull { it.id }.toSet()
        for (id in rules?.requiredFeatureIds ?: emptyList()) {
            if (id !in ids) issues += ValidationIssue(
                code = "missing_required_feature",
                message = "Missing required feature: $id",
                featureId = id,
                severity = "error"
            )
        }
        for (id in rules?.preserveFeatureIds ?: emptyList()) {
            if (id !in ids) issues += ValidationIssue(
                code = "missing_preserved_feature",
                message = "Preserved feature was not retained: $id",
                featureId = id,
                severity = "error"
            )
        }
        for (site in rules?.forbiddenRestrictionSites ?: emptyList()) {
            val pattern = if (site.matches(Regex("^[ACGTN]+$", RegexOption.IGNORE_CASE))) site else ""
            if (pattern.isNotEmpty() && result.sequence.uppercase().contains(pattern.uppercase())) {
                issues += ValidationIssue(
                    code = "forbidden_restriction_site",
                    message = "Forbidden restriction site present: $site",
                    severity = "error"
                )
            }
        }
        if (rules?.checkReadingFrame == true) {
            for (p in result.cassette.parts) {
                if (p.role == "cds" && p.sequence.length % 3 != 0) {
                    issues += ValidationIssue(
                        code = "cds_frame",
                        message = "CDS ${p.id} is not in frame",
                        severity = "error"
                    )
                }
            }
        }
        return ValidationResult(valid = issues.none { it.severity == "error" }, issues = issues)
    }

    /** 确定性碱基级 diff（线性；环状取最佳旋转，main diffSequences） */
    fun diffSequences(reference: String, actual: String, topology: String = "linear"): List<SequenceDiff> {
        var seq = actual
        if (topology == "circular" && reference.length == actual.length && reference.isNotEmpty()) {
            var best = actual
            var bestScore = Int.MAX_VALUE
            for (i in actual.indices) {
                val rotated = actual.substring(i) + actual.substring(0, i)
                val score = reference.indices.count { j ->
                    reference[j].uppercaseChar() != rotated[j].uppercaseChar()
                }
                if (score < bestScore) { bestScore = score; best = rotated }
            }
            seq = best
        }
        val out = mutableListOf<SequenceDiff>()
        val n = maxOf(reference.length, seq.length)
        var i = 0
        while (i < n) {
            if (i < reference.length && i < seq.length &&
                reference[i].uppercaseChar() == seq[i].uppercaseChar()
            ) { i++; continue }
            val pos = i
            if (i >= reference.length) {
                out += SequenceDiff(
                    type = "insertion", position = pos,
                    actual = seq.substring(i), length = seq.length - i
                )
                break
            }
            if (i >= seq.length) {
                out += SequenceDiff(
                    type = "deletion", position = pos,
                    reference = reference.substring(i), length = reference.length - i
                )
                break
            }
            out += SequenceDiff(type = "mismatch", position = pos,
                reference = reference[i].toString(), actual = seq[i].toString(), length = 1)
            i++
        }
        return out
    }
}
