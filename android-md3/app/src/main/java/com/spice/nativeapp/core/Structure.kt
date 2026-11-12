package com.spice.nativeapp.core

/**
 * 结构文件处理：对应原版 backend/mmcif.ts（mmCIF 生成）与 viewer/MolStarViewer 的数据源。
 * 原生版不引入 molstar（JS 依赖），改为解析 CA 轨迹后用 Canvas 渲染；这里只负责解析与导出。
 */
object Structure {

    data class Atom(val resName: String, val resSeq: Int, val chain: String, val x: Float, val y: Float, val z: Float)

    data class Parsed(
        val name: String,
        val atoms: List<Atom>,
        val source: String,
        val note: String? = null
    ) {
        val minX get() = atoms.minOfOrNull { it.x } ?: 0f
        val maxX get() = atoms.maxOfOrNull { it.x } ?: 1f
        val minY get() = atoms.minOfOrNull { it.y } ?: 0f
        val maxY get() = atoms.maxOfOrNull { it.y } ?: 1f
        val minZ get() = atoms.minOfOrNull { it.z } ?: 0f
        val maxZ get() = atoms.maxOfOrNull { it.z } ?: 1f

        fun center() = Triple((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
    }

    /** 解析 PDB：取 CA 原子（蛋白质主链） */
    fun parsePdb(text: String, name: String): Parsed {
        val atoms = mutableListOf<Atom>()
        text.lineSequence().forEach { line ->
            if (line.startsWith("ATOM") && line.length >= 54) {
                val atomName = line.substring(12, 16).trim()
                if (atomName == "CA") {
                    val resName = line.substring(17, 20).trim()
                    val chain = line.getOrNull(21)?.toString() ?: "A"
                    val resSeq = line.substring(22, 26).trim().toIntOrNull() ?: 0
                    val x = line.substring(30, 38).trim().toFloatOrNull()
                    val y = line.substring(38, 46).trim().toFloatOrNull()
                    val z = line.substring(46, 54).trim().toFloatOrNull()
                    if (x != null && y != null && z != null) atoms += Atom(resName, resSeq, chain, x, y, z)
                }
            }
        }
        return Parsed(name, atoms, "PDB", if (atoms.isEmpty()) "未找到 CA 原子（可能是核酸或仅杂原子结构）" else null)
    }

    /** 解析 mmCIF：按 _atom_site loop 的列顺序取 Cartn_x/y/z 与 label_atom_id == CA */
    fun parseMmcif(text: String, name: String): Parsed {
        val atoms = mutableListOf<Atom>()
        val lines = text.lines()
        val header = mutableListOf<String>()
        var started = false
        var xIdx = -1; var yIdx = -1; var zIdx = -1
        var atomIdx = -1; var compIdx = -1; var seqIdx = -1; var asymIdx = -1

        for (i in lines.indices) {
            val raw = lines[i].trim()
            if (!started && raw.startsWith("_atom_site.")) {
                header += raw.substringAfter("_atom_site.").substringBefore(" ")
                continue
            }
            if (!started && raw == "loop_") continue
            if (header.isNotEmpty() && !started && !raw.startsWith("_") && raw.isNotBlank()) {
                // 第一行数据
                started = true
                xIdx = header.indexOf("Cartn_x").takeIf { it >= 0 } ?: header.indexOf("Cartn_x")
                yIdx = header.indexOf("Cartn_y")
                zIdx = header.indexOf("Cartn_z")
                atomIdx = header.indexOf("label_atom_id").takeIf { it >= 0 } ?: header.indexOf("auth_atom_id")
                compIdx = header.indexOf("label_comp_id")
                seqIdx = header.indexOf("label_seq_id").takeIf { it >= 0 } ?: header.indexOf("auth_seq_id")
                asymIdx = header.indexOf("label_asym_id").takeIf { it >= 0 } ?: header.indexOf("auth_asym_id")
            }
            if (started) {
                val cols = raw.replace("\"", "").split(Regex("\\s+"))
                if (cols.size <= maxOf(xIdx, yIdx, zIdx)) continue
                val atom = if (atomIdx in cols.indices) cols[atomIdx] else ""
                if (atom != "CA") continue
                val x = cols[xIdx].toFloatOrNull() ?: continue
                val y = cols[yIdx].toFloatOrNull() ?: continue
                val z = cols[zIdx].toFloatOrNull() ?: continue
                atoms += Atom(
                    resName = if (compIdx in cols.indices) cols[compIdx] else "UNK",
                    resSeq = if (seqIdx in cols.indices) cols[seqIdx].toIntOrNull() ?: 0 else 0,
                    chain = if (asymIdx in cols.indices) cols[asymIdx] else "A",
                    x = x, y = y, z = z
                )
            }
        }
        return Parsed(name, atoms.toList(), "mmCIF", if (atoms.isEmpty()) "未解析到 CA 原子（检查 _atom_site 列头）" else null)
    }

    /** 按扩展名自动分派 */
    fun parse(text: String, name: String): Parsed =
        if (text.trimStart().startsWith("data_") || text.contains("_atom_site.")) parseMmcif(text, name)
        else parsePdb(text, name)

    /**
     * 生成 mmCIF（对应原版 buildMmcif）。coords 为每残基 CA 坐标（x,y,z 展平）；
     * 若没有真实坐标则按 3.8 Å Cα-Cα 距离生成延伸链，并在 note 里标明是直链占位。
     */
    fun buildMmcif(
        sequence: String,
        coords: FloatArray? = null,
        modelName: String = "spice_model",
        note: String? = null
    ): String {
        val seq = sequence.filter { it.isLetter() }.uppercase()
        val n = seq.length
        val atoms = if (coords != null && coords.size >= n * 3) coords else {
            FloatArray(n * 3).also { arr ->
                for (i in 0 until n) {
                    arr[i * 3] = i * 3.8f
                    arr[i * 3 + 1] = 0f
                    arr[i * 3 + 2] = 0f
                }
            }
        }
        val threeTo1 = mapOf(
            'A' to "ALA", 'R' to "ARG", 'N' to "ASN", 'D' to "ASP", 'C' to "CYS",
            'Q' to "GLN", 'E' to "GLU", 'G' to "GLY", 'H' to "HIS", 'I' to "ILE",
            'L' to "LEU", 'K' to "LYS", 'M' to "MET", 'F' to "PHE", 'P' to "PRO",
            'S' to "SER", 'T' to "THR", 'W' to "TRP", 'Y' to "TYR", 'V' to "VAL"
        )
        val sb = StringBuilder()
        sb.appendLine("data_$modelName")
        sb.appendLine("#")
        if (note != null) {
            sb.appendLine("_struct.title '$note'")
            sb.appendLine("#")
        }
        sb.appendLine("loop_")
        sb.appendLine("_atom_site.group_PDB")
        sb.appendLine("_atom_site.id")
        sb.appendLine("_atom_site.type_symbol")
        sb.appendLine("_atom_site.label_atom_id")
        sb.appendLine("_atom_site.label_comp_id")
        sb.appendLine("_atom_site.label_asym_id")
        sb.appendLine("_atom_site.label_seq_id")
        sb.appendLine("_atom_site.Cartn_x")
        sb.appendLine("_atom_site.Cartn_y")
        sb.appendLine("_atom_site.Cartn_z")
        sb.appendLine("_atom_site.occupancy")
        sb.appendLine("_atom_site.B_iso_or_equiv")
        for (i in 0 until n) {
            val comp = threeTo1[seq[i]] ?: "UNK"
            val x = atoms[i * 3]; val y = atoms[i * 3 + 1]; val z = atoms[i * 3 + 2]
            sb.appendLine("ATOM ${i + 1} C CA $comp A ${i + 1} ${"%.3f".format(x)} ${"%.3f".format(y)} ${"%.3f".format(z)} 1.00 0.00")
        }
        sb.appendLine("#")
        return sb.toString()
    }
}
