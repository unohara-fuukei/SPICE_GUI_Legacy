package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Co-Pilot 结构化块解析与校验（1:1 移植 main 分支 src/lib/ai/parse.ts）。
 * 模型输出是不可信输入：非法块绝不进入编辑器回调。
 */
object AiBlocks {

    data class Mutation(val pos: Int, val from: String, val to: String, val score: Double? = null)

    private val AMINO_ACIDS = "ACDEFGHIKLMNPQRSTVWY".toSet()
    private val DNA_IUPAC = Regex("^[ACGTUNRYSWKMBDHV]+$")
    private val FENCE = Regex("```([a-zA-Z0-9_-]*)\\s*\\n?([\\s\\S]*?)```")

    private fun fencedBlocks(text: String, language: String): List<String> {
        val out = mutableListOf<String>()
        for (m in FENCE.findAll(text)) {
            val tag = (m.groupValues[1]).lowercase()
            if (tag == language) out.add(m.groupValues[2].trim())
        }
        return out
    }

    /** 从所有 ```json 块收集突变数组；非法行丢弃（对齐 parseMutationBlocks，支持 {mutations:[...]} 包装） */
    fun parseMutationBlocks(text: String): List<Mutation> {
        val out = mutableListOf<Mutation>()
        for (block in fencedBlocks(text, "json")) {
            val parsed: Any = try {
                val trimmed = block.trim()
                if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            } catch (_: Exception) {
                continue
            }
            val rows: List<Any> = when {
                parsed is JSONArray -> List(parsed.length()) { parsed.get(it) }
                parsed is JSONObject && parsed.optJSONArray("mutations") != null -> {
                    val arr = parsed.getJSONArray("mutations")
                    List(arr.length()) { arr.get(it) }
                }
                else -> emptyList()
            }
            for (row in rows) {
                if (row !is JSONObject) continue
                val pos = when (val v = row.opt("pos")) {
                    is Int -> v
                    is Long -> v.toInt()
                    is Number -> if (v.toDouble() % 1.0 == 0.0) v.toInt() else null
                    else -> v?.toString()?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
                } ?: continue
                val from = row.optString("from").trim().uppercase()
                val to = row.optString("to").trim().uppercase()
                if (pos < 1 || from.isEmpty() || to.isEmpty()) continue
                val score = (row.opt("score") as? Number)?.toDouble()?.takeIf { it.isFinite() }
                out += Mutation(pos, from, to, score)
            }
        }
        return out
    }

    /** 第一个非空 ```dna 块（去空白、大写）；对齐 parseDnaBlocks */
    fun parseDnaBlocks(text: String): String? {
        for (block in fencedBlocks(text, "dna")) {
            val sequence = block.replace(Regex("[\\s\\n\\r\\t]"), "").uppercase()
            if (sequence.isNotEmpty()) return sequence
        }
        return null
    }

    data class MutationValidation(val valid: List<Mutation>, val errors: List<String>)

    /** 对照当前序列校验突变建议（野生型不匹配则拒绝，对齐 validateMutations） */
    fun validateMutations(rows: List<Mutation>, sequence: String?): MutationValidation {
        val valid = mutableListOf<Mutation>()
        val errors = mutableListOf<String>()
        val seq = (sequence ?: "").uppercase()
        for (row in rows) {
            if (row.pos < 1) {
                errors += "invalid position: ${row.pos}"
                continue
            }
            if (row.from.length != 1 || row.from[0] !in AMINO_ACIDS ||
                row.to.length != 1 || row.to[0] !in AMINO_ACIDS
            ) {
                errors += "invalid residue at position ${row.pos}"
                continue
            }
            if (seq.isNotEmpty()) {
                if (row.pos > seq.length) {
                    errors += "position ${row.pos} is outside the ${seq.length}-residue sequence"
                    continue
                }
                if (seq[row.pos - 1].toString() != row.from) {
                    errors += "position ${row.pos} is ${seq[row.pos - 1]}, not ${row.from}"
                    continue
                }
            }
            valid += row
        }
        return MutationValidation(valid, errors)
    }

    /** 归一化并校验模型给出的 DNA 序列（IUPAC 兼容，对齐 validateDnaSequence） */
    fun validateDnaSequence(raw: String): Pair<String, String?> {
        val sequence = raw.replace(Regex("[\\s\\n\\r\\t]"), "").uppercase()
        if (sequence.isEmpty()) return "" to "empty sequence"
        if (!DNA_IUPAC.matches(sequence)) return "" to "sequence contains non-DNA characters"
        return sequence to null
    }
}
