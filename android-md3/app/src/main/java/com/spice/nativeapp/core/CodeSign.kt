package com.spice.nativeapp.core

/**
 * SPICE Pareto 协同设计引擎 — 序列-结构-表达多目标（对齐 main 分支 src-tauri/src/cloning.rs 的
 * pareto_codesign_evaluate）：对每组候选突变，按宿主最优密码子回译 CDS，计算 CAI 与 mRNA 5' 折叠
 * 自由能，输出 Pareto 最优前沿（ΔE 越低越好，CAI 越高越好）。
 */
object CodeSign {

    /** 宿主：大肠杆菌 / 酵母 / 人 */
    enum class Host(val label: String) { ECOLI("E. coli"), YEAST("酵母"), HUMAN("人") }

    /** 密码子 → 氨基酸（单字母，* 为终止） */
    private val CODON_TO_AA: Map<String, Char> = buildMap {
        fun put(aa: Char, vararg codons: String) = codons.forEach { put(it, aa) }
        put('F', "TTT", "TTC"); put('L', "TTA", "TTG", "CTT", "CTC", "CTA", "CTG")
        put('I', "ATT", "ATC", "ATA"); put('M', "ATG")
        put('V', "GTT", "GTC", "GTA", "GTG")
        put('S', "TCT", "TCC", "TCA", "TCG", "AGT", "AGC")
        put('P', "CCT", "CCC", "CCA", "CCG")
        put('T', "ACT", "ACC", "ACA", "ACG")
        put('A', "GCT", "GCC", "GCA", "GCG")
        put('Y', "TAT", "TAC"); put('H', "CAT", "CAC")
        put('Q', "CAA", "CAG"); put('N', "AAT", "AAC")
        put('K', "AAA", "AAG"); put('D', "GAT", "GAC")
        put('E', "GAA", "GAG"); put('C', "TGT", "TGC")
        put('R', "CGT", "CGC", "CGA", "CGG", "AGA", "AGG")
        put('G', "GGT", "GGC", "GGA", "GGG")
        put('W', "TGG"); put('*', "TAA", "TAG", "TGA")
    }

    fun codonToAa(codon: String): Char = CODON_TO_AA[codon] ?: 'X'

    /** 宿主特异密码子使用权重（与 Rust codon_weights 一致） */
    fun codonWeights(host: Host): Map<Char, Map<String, Double>> {
        fun m(vararg pairs: Pair<String, Double>) = pairs.toMap()
        return when (host) {
            Host.ECOLI -> mapOf(
                'F' to m("TTT" to 0.3, "TTC" to 1.0),
                'L' to m("TTA" to 0.1, "TTG" to 0.1, "CTT" to 0.1, "CTC" to 0.1, "CTA" to 0.05, "CTG" to 1.0),
                'I' to m("ATT" to 0.5, "ATC" to 1.0, "ATA" to 0.05),
                'V' to m("GTT" to 0.4, "GTC" to 0.2, "GTA" to 0.15, "GTG" to 1.0),
                'S' to m("TCT" to 0.3, "TCC" to 0.4, "TCA" to 0.1, "TCG" to 0.1, "AGT" to 0.15, "AGC" to 1.0),
                'P' to m("CCT" to 0.15, "CCC" to 0.1, "CCA" to 0.2, "CCG" to 1.0),
                'T' to m("ACT" to 0.35, "ACC" to 1.0, "ACA" to 0.1, "ACG" to 0.15),
                'A' to m("GCT" to 0.3, "GCC" to 0.2, "GCA" to 0.3, "GCG" to 1.0),
                'Y' to m("TAT" to 0.3, "TAC" to 1.0),
                'H' to m("CAT" to 0.3, "CAC" to 1.0),
                'Q' to m("CAA" to 0.3, "CAG" to 1.0),
                'N' to m("AAT" to 0.3, "AAC" to 1.0),
                'K' to m("AAA" to 0.25, "AAG" to 1.0),
                'D' to m("GAT" to 0.4, "GAC" to 1.0),
                'E' to m("GAA" to 1.0, "GAG" to 0.3),
                'C' to m("TGT" to 0.4, "TGC" to 1.0),
                'R' to m("CGT" to 0.4, "CGC" to 0.4, "CGA" to 0.05, "CGG" to 0.05, "AGA" to 0.1, "AGG" to 0.1),
                'G' to m("GGT" to 0.4, "GGC" to 1.0, "GGA" to 0.1, "GGG" to 0.15),
                'M' to m("ATG" to 1.0),
                'W' to m("TGG" to 1.0),
                '*' to m("TAA" to 1.0, "TAG" to 0.1, "TGA" to 0.1)
            )
            Host.YEAST -> mapOf(
                'F' to m("TTT" to 1.0, "TTC" to 0.6),
                'L' to m("TTA" to 1.0, "TTG" to 0.8, "CTT" to 0.1, "CTC" to 0.1, "CTA" to 0.2, "CTG" to 0.1),
                'I' to m("ATT" to 1.0, "ATC" to 0.4, "ATA" to 0.15),
                'V' to m("GTT" to 1.0, "GTC" to 0.4, "GTA" to 0.3, "GTG" to 0.2),
                'S' to m("TCT" to 1.0, "TCC" to 0.5, "TCA" to 0.4, "TCG" to 0.1, "AGT" to 0.3, "AGC" to 0.2),
                'P' to m("CCT" to 0.4, "CCC" to 0.15, "CCA" to 1.0, "CCG" to 0.05),
                'T' to m("ACT" to 1.0, "ACC" to 0.4, "ACA" to 0.3, "ACG" to 0.1),
                'A' to m("GCT" to 1.0, "GCC" to 0.4, "GCA" to 0.5, "GCG" to 0.1),
                'Y' to m("TAT" to 1.0, "TAC" to 0.4),
                'H' to m("CAT" to 1.0, "CAC" to 0.4),
                'Q' to m("CAA" to 1.0, "CAG" to 0.3),
                'N' to m("AAT" to 1.0, "AAC" to 0.5),
                'K' to m("AAA" to 1.0, "AAG" to 0.4),
                'D' to m("GAT" to 1.0, "GAC" to 0.4),
                'E' to m("GAA" to 1.0, "GAG" to 0.3),
                'C' to m("TGT" to 1.0, "TGC" to 0.2),
                'R' to m("CGT" to 0.2, "CGC" to 0.1, "CGA" to 0.1, "CGG" to 0.05, "AGA" to 1.0, "AGG" to 0.2),
                'G' to m("GGT" to 1.0, "GGC" to 0.2, "GGA" to 0.3, "GGG" to 0.1),
                'M' to m("ATG" to 1.0),
                'W' to m("TGG" to 1.0),
                '*' to m("TAA" to 1.0, "TAG" to 0.1, "TGA" to 0.1)
            )
            Host.HUMAN -> mapOf(
                'F' to m("TTT" to 0.4, "TTC" to 1.0),
                'L' to m("TTA" to 0.1, "TTG" to 0.25, "CTT" to 0.2, "CTC" to 0.3, "CTA" to 0.1, "CTG" to 1.0),
                'I' to m("ATT" to 0.45, "ATC" to 1.0, "ATA" to 0.15),
                'V' to m("GTT" to 0.3, "GTC" to 0.4, "GTA" to 0.15, "GTG" to 1.0),
                'S' to m("TCT" to 0.2, "TCC" to 0.4, "TCA" to 0.2, "TCG" to 0.1, "AGT" to 0.25, "AGC" to 1.0),
                'P' to m("CCT" to 0.3, "CCC" to 0.4, "CCA" to 0.3, "CCG" to 1.0),
                'T' to m("ACT" to 0.25, "ACC" to 1.0, "ACA" to 0.3, "ACG" to 0.15),
                'A' to m("GCT" to 0.4, "GCC" to 1.0, "GCA" to 0.3, "GCG" to 0.15),
                'Y' to m("TAT" to 0.4, "TAC" to 1.0),
                'H' to m("CAT" to 0.4, "CAC" to 1.0),
                'Q' to m("CAA" to 0.35, "CAG" to 1.0),
                'N' to m("AAT" to 0.4, "AAC" to 1.0),
                'K' to m("AAA" to 0.4, "AAG" to 1.0),
                'D' to m("GAT" to 0.45, "GAC" to 1.0),
                'E' to m("GAA" to 0.45, "GAG" to 1.0),
                'C' to m("TGT" to 0.4, "TGC" to 1.0),
                'R' to m("CGT" to 0.1, "CGC" to 0.35, "CGA" to 0.1, "CGG" to 0.2, "AGA" to 1.0, "AGG" to 0.9),
                'G' to m("GGT" to 0.3, "GGC" to 1.0, "GGA" to 0.4, "GGG" to 0.3),
                'M' to m("ATG" to 1.0),
                'W' to m("TGG" to 1.0),
                '*' to m("TAA" to 0.3, "TAG" to 0.2, "TGA" to 1.0)
            )
        }
    }

    /** 每个氨基酸在指定宿主下的最优（最大适应性）密码子（与 Rust best_codon 一致） */
    fun bestCodon(host: Host, aa: Char): String? = when (host) {
        Host.ECOLI -> mapOf(
            'M' to "ATG", 'W' to "TGG", 'F' to "TTC", 'L' to "CTG", 'I' to "ATC",
            'V' to "GTG", 'S' to "AGC", 'P' to "CCG", 'T' to "ACC", 'A' to "GCG",
            'Y' to "TAC", 'H' to "CAC", 'Q' to "CAG", 'N' to "AAC", 'K' to "AAG",
            'D' to "GAC", 'E' to "GAA", 'C' to "TGC", 'R' to "CGT", 'G' to "GGC",
            '*' to "TAA"
        )
        Host.YEAST -> mapOf(
            'M' to "ATG", 'W' to "TGG", 'F' to "TTT", 'L' to "TTA", 'I' to "ATT",
            'V' to "GTT", 'S' to "TCT", 'P' to "CCA", 'T' to "ACT", 'A' to "GCT",
            'Y' to "TAT", 'H' to "CAT", 'Q' to "CAA", 'N' to "AAT", 'K' to "AAA",
            'D' to "GAT", 'E' to "GAA", 'C' to "TGT", 'R' to "AGA", 'G' to "GGT",
            '*' to "TAA"
        )
        Host.HUMAN -> mapOf(
            'M' to "ATG", 'W' to "TGG", 'F' to "TTC", 'L' to "CTG", 'I' to "ATC",
            'V' to "GTG", 'S' to "AGC", 'P' to "CCG", 'T' to "ACC", 'A' to "GCC",
            'Y' to "TAC", 'H' to "CAC", 'Q' to "CAG", 'N' to "AAC", 'K' to "AAG",
            'D' to "GAC", 'E' to "GAG", 'C' to "TGC", 'R' to "AGA", 'G' to "GGC",
            '*' to "TGA"
        )
    }[aa]

    /** 密码子适应性指数 CAI（与 Rust compute_cai 一致：几何平均的 w = freq/maxFreq） */
    fun computeCai(cds: String, host: Host): Double {
        val weights = codonWeights(host)
        var logSum = 0.0
        var count = 0
        var i = 0
        while (i + 3 <= cds.length) {
            val codon = cds.substring(i, i + 3)
            val aa = codonToAa(codon)
            i += 3
            if (aa == 'X') continue
            val aaWeights = weights[aa] ?: continue
            val maxFreq = (aaWeights.values.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
            val currentFreq = aaWeights[codon] ?: 0.01
            logSum += kotlin.math.ln(currentFreq / maxFreq)
            count++
        }
        return if (count == 0) 0.0 else kotlin.math.exp(logSum / count)
    }

    /** mRNA 5' 折叠自由能（kcal/mol，CDS 前 80bp 滑窗 Nussinov MFE，is_rna=true） */
    fun mrna5PrimeDg(cds: String): Double {
        val window = if (cds.length > 80) cds.substring(0, 80) else cds
        return RnaStructure.nussinovFold(window, isRna = true, minLoop = 3).mfe
    }

    /** 候选突变（0 基氨基酸位点） */
    data class Mutation(
        val id: String,
        val label: String,
        val aaPositions: List<Int>,
        val targetAas: List<Char>,
        val proteinEnergyDelta: Double
    )

    data class Variant(
        val id: String,
        val mutations: String,
        val proteinEnergyDelta: Double,
        val cai: Double,
        val mrnaDeltaG: Double,
        val isParetoOptimal: Boolean,
        val updatedDna: String
    )

    /**
     * 协同设计多目标评估：每组突变按宿主最优密码子回译 CDS，计算 CAI 与 mRNA ΔG，
     * 再以 (ΔE, -CAI) 双目标最小化做非支配排序，标记 Pareto 前沿。
     * @param cdsStart 0 基包含起点；@param cdsEnd 0 基包含终点
     */
    fun evaluate(
        dnaSequence: String,
        cdsStart: Int,
        cdsEnd: Int,
        host: Host,
        mutations: List<Mutation>
    ): List<Variant> {
        val cleanDna = dnaSequence.uppercase()
        val cdsLength = cdsEnd - cdsStart + 1
        require(cdsLength >= 3 && cdsEnd < cleanDna.length && cdsStart >= 0) { "CDS 坐标越界" }
        val originalCds = cleanDna.substring(cdsStart, cdsEnd + 1)
        val nAa = cdsLength / 3

        val variants = mutableListOf<Variant>()
        for (mut in mutations) {
            val cds = originalCds.toCharArray()
            var valid = true
            for ((i, aaPos) in mut.aaPositions.withIndex()) {
                if (aaPos >= nAa) { valid = false; break }
                val targetAa = mut.targetAas.getOrNull(i) ?: run { valid = false; break }
                val codon = bestCodon(host, targetAa)
                    ?: throw IllegalArgumentException("未知氨基酸: $targetAa")
                val codonStart = aaPos * 3
                if (codonStart + 3 <= cds.size) {
                    cds[codonStart] = codon[0]
                    cds[codonStart + 1] = codon[1]
                    cds[codonStart + 2] = codon[2]
                }
            }
            if (!valid) continue
            val mutatedCds = String(cds)
            val cai = computeCai(mutatedCds, host)
            val mrnaDg = mrna5PrimeDg(mutatedCds)
            val updatedDna = cleanDna.substring(0, cdsStart) + mutatedCds + cleanDna.substring(cdsEnd + 1)
            variants += Variant(
                id = mut.id,
                mutations = mut.label,
                proteinEnergyDelta = mut.proteinEnergyDelta,
                cai = Math.round(cai * 1000.0) / 1000.0,
                mrnaDeltaG = Math.round(mrnaDg * 10.0) / 10.0,
                isParetoOptimal = false,
                updatedDna = updatedDna
            )
        }

        // Pareto 前沿：ΔE 越低越好，CAI 越高越好（与 Rust 一致的双重循环支配判定）
        val n = variants.size
        val isPareto = BooleanArray(n) { true }
        for (i in 0 until n) {
            if (!isPareto[i]) continue
            for (j in 0 until n) {
                if (i == j) continue
                val betterOrEqual = variants[j].proteinEnergyDelta <= variants[i].proteinEnergyDelta &&
                        variants[j].cai >= variants[i].cai
                val strictlyBetter = variants[j].proteinEnergyDelta < variants[i].proteinEnergyDelta ||
                        variants[j].cai > variants[i].cai
                if (betterOrEqual && strictlyBetter) { isPareto[i] = false; break }
            }
        }
        return variants.mapIndexed { i, v -> v.copy(isParetoOptimal = isPareto[i]) }
    }

    private val MUT_REGEX = Regex("^([A-Z])(\\d+)([A-Z])$")

    /**
     * 解析候选清单文本（与 main 前端一致）：
     * 每行 `ID, 突变标签, ΔE`，突变标签形如 "V15A + I32L"，# 开头为注释。
     */
    fun parseCandidates(text: String): List<Mutation> {
        val out = mutableListOf<Mutation>()
        for (rawLine in text.trim().lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(",")
            if (parts.size < 3) continue
            val id = parts[0].trim()
            val label = parts[1].trim()
            val energy = parts[2].trim().toDoubleOrNull() ?: 0.0
            val positions = mutableListOf<Int>()
            val targets = mutableListOf<Char>()
            for (m in label.split("+")) {
                val match = MUT_REGEX.matchEntire(m.trim()) ?: continue
                val (_, pos, to) = match.destructured
                // 校验野生型一致由调用方完成（此处与 Rust 相同仅记录位点与目标）
                positions += pos.toInt() - 1
                targets += to[0]
            }
            if (positions.isNotEmpty()) out += Mutation(id, label, positions, targets, energy)
        }
        return out
    }
}
