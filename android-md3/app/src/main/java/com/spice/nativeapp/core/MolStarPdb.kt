package com.spice.nativeapp.core

/**
 * T7-14：main src/lib/backend/preprocess.ts 的 pdbFromCoords / oneToThree / cleanSeq 1:1。
 * 用途：蛋白工作站 MOL* 查看器（MolStarViewer.svelte 平台差异实现）的
 * Cα trace → 固定列 PDB 文本（78 列），供导出/外部查看器使用。
 *
 * main quirk（实测锁定）：
 * - l = min(cleanLen, floor(coords/3))，clean 先截断到 l；
 * - SEQRES 每行 17 残基，总数字段 = 截断后 clean.length（非原始长度）；
 * - oneToThree：U→SEC，其余未知字符（含 B/Z/X 及星号）→ 'UNK'；
 * - ATOM 行 toFixed(3) 左补空格至 8 列；occ 1.00、tempFactor '  0.00'、元素 'C'。
 */
object MolStarPdb {

    private val ONE_TO_THREE = mapOf(
        'A' to "ALA", 'C' to "CYS", 'D' to "ASP", 'E' to "GLU", 'F' to "PHE",
        'G' to "GLY", 'H' to "HIS", 'I' to "ILE", 'K' to "LYS", 'L' to "LEU",
        'M' to "MET", 'N' to "ASN", 'P' to "PRO", 'Q' to "GLN", 'R' to "ARG",
        'S' to "SER", 'T' to "THR", 'V' to "VAL", 'W' to "TRP", 'Y' to "TYR",
        'U' to "SEC",
    )

    fun oneToThree(c: Char): String = ONE_TO_THREE[c] ?: "UNK"

    /** cleanSeq：去非字母、大写（main: seq.replace(/[^A-Za-z]/g, '').toUpperCase()） */
    fun cleanSeq(seq: String): String = seq.filter { it.isLetter() }.uppercase()

    /**
     * Generate a minimal PDB text from a Cα trace + sequence（main pdbFromCoords 1:1）。
     * Only CA atoms + SEQRES are emitted.
     */
    fun pdbFromCoords(seq: String, coords: DoubleArray): String {
        val l = minOf(cleanSeq(seq).length, coords.size / 3)
        val lines = mutableListOf<String>()
        val clean = cleanSeq(seq).take(l)
        // SEQRES（每行 17 残基）
        var s = 0
        while (s < clean.length) {
            val chunk = clean.substring(s, minOf(s + 17, clean.length))
            val res = chunk.map { oneToThree(it).padStart(3) }.joinToString(" ")
            lines.add("SEQRES ${s / 17 + 1} A ${clean.length} $res")
            s += 17
        }
        for (i in 0 until l) {
            lines.add(
                pdbAtomLine(
                    serial = i + 1, atomName = "CA", resName = oneToThree(clean[i]), chain = "A",
                    resSeq = i + 1,
                    x = coords[i * 3], y = coords[i * 3 + 1], z = coords[i * 3 + 2],
                    occ = 1.0, elem = "C"
                )
            )
        }
        lines.add("TER")
        lines.add("END")
        return lines.joinToString("\n")
    }

    /** Fixed-column PDB ATOM record (78 columns)（main pdbAtomLine 1:1） */
    private fun pdbAtomLine(
        serial: Int, atomName: String, resName: String, chain: String,
        resSeq: Int, x: Double, y: Double, z: Double, occ: Double, elem: String
    ): String {
        var s = "ATOM  "
        s += serial.toString().padStart(5)
        s += " "
        s += atomName.padEnd(4) // cols 12-15
        s += " " // col 16 altLoc
        s += resName.padStart(3) // cols 17-19
        s += " "
        s += chain // col 21
        s += resSeq.toString().padStart(4) // cols 22-25
        s += " " // col 26 iCode
        s += "   " // cols 27-29
        s += jsToFixed(x, 3).padStart(8) // cols 30-37
        s += jsToFixed(y, 3).padStart(8) // cols 38-45
        s += jsToFixed(z, 3).padStart(8) // cols 46-53
        s += jsToFixed(occ, 2).padStart(6) // cols 54-59
        s += "  0.00" // tempFactor cols 60-65
        s += "          " // cols 66-75
        s += elem.padStart(2) // cols 76-77
        return s
    }

    /** JS Number.prototype.toFixed：toFixed(0) 无小数点；Java String.format 恒输出 dot+0。 */
    private fun jsToFixed(v: Double, d: Int): String {
        val s = java.math.BigDecimal(v).setScale(d, java.math.RoundingMode.HALF_UP).toPlainString()
        return if (d == 0) s.substringBefore('.') else s
    }
}
