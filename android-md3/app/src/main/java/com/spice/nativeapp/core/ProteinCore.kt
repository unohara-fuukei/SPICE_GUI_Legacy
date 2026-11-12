package com.spice.nativeapp.core

/** 蛋白理化性质（EMBOSS 风格 pKa） */
data class ProteinProps(
    val length: Int,
    val molecularWeight: Double,
    val pI: Double,
    val gravy: Double,
    val a280: Double,
    val chargedAtPh7: Double
)

object ProteinCore {

    private val MW = mapOf(
        'A' to 89.09, 'R' to 174.20, 'N' to 132.12, 'D' to 133.10, 'C' to 121.15,
        'E' to 147.13, 'Q' to 146.15, 'G' to 75.07, 'H' to 155.16, 'I' to 131.17,
        'L' to 131.17, 'K' to 146.19, 'M' to 149.21, 'F' to 165.19, 'P' to 115.13,
        'S' to 105.09, 'T' to 119.12, 'W' to 204.23, 'Y' to 181.19, 'V' to 117.15
    )

    private val KD = mapOf(
        'A' to 1.8, 'R' to -4.5, 'N' to -3.5, 'D' to -3.5, 'C' to 2.5,
        'E' to -3.5, 'Q' to -3.5, 'G' to -0.4, 'H' to -3.2, 'I' to 4.5,
        'L' to 3.8, 'K' to -3.9, 'M' to 1.9, 'F' to 2.8, 'P' to -1.6,
        'S' to -0.8, 'T' to -0.7, 'W' to -0.9, 'Y' to -1.3, 'V' to 4.2
    )

    private val PKA = mapOf(
        "Cterm" to 3.55, "Nterm" to 7.50, 'D' to 4.05, 'E' to 4.45,
        'C' to 9.00, 'Y' to 10.00, 'H' to 5.98, 'K' to 10.00, 'R' to 12.00
    )

    fun clean(raw: String): String = raw.filter { it.isLetter() }.uppercase()

    fun molecularWeight(seq: String): Double {
        if (seq.isEmpty()) return 0.0
        var total = 18.015
        for (c in seq) total += MW[c] ?: 110.0
        return total
    }

    /** 净电荷（给定 pH），用于 pI 二分 */
    private fun chargeAt(seq: String, ph: Double): Double {
        var q = 0.0
        for (c in seq) {
            when (c) {
                'D', 'E', 'C', 'Y' -> {
                    val pka = PKA[c]!!
                    q -= 1.0 / (1.0 + Math.pow(10.0, pka - ph))
                }
                'K', 'R', 'H' -> {
                    val pka = PKA[c]!!
                    q += 1.0 / (1.0 + Math.pow(10.0, ph - pka))
                }
            }
        }
        // 末端基团
        q += 1.0 / (1.0 + Math.pow(10.0, ph - PKA["Nterm"]!!))
        q -= 1.0 / (1.0 + Math.pow(10.0, PKA["Cterm"]!! - ph))
        return q
    }

    fun isoelectricPoint(seq: String): Double {
        if (seq.isEmpty()) return 0.0
        var lo = 0.0
        var hi = 14.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (chargeAt(seq, mid) > 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun gravy(seq: String): Double {
        if (seq.isEmpty()) return 0.0
        return seq.sumOf { (KD[it] ?: 0.0).toDouble() } / seq.length
    }

    fun a280(seq: String): Double {
        return seq.count { it == 'W' } * 5500.0 + seq.count { it == 'Y' } * 1490.0 +
            seq.count { it == 'C' } * 125.0
    }

    fun props(seq: String): ProteinProps = ProteinProps(
        length = seq.length,
        molecularWeight = molecularWeight(seq),
        pI = isoelectricPoint(seq),
        gravy = gravy(seq),
        a280 = a280(seq),
        chargedAtPh7 = chargeAt(seq, 7.0)
    )

    /** 氨基酸组成表 */
    fun composition(seq: String): Map<Char, Int> = seq.groupingBy { it }.eachCount().toSortedMap()

    fun formatMass(da: Double): String =
        if (da >= 1000) String.format("%.2f kDa", da / 1000.0) else String.format("%.1f Da", da)
}
