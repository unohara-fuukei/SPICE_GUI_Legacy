package com.spice.nativeapp.core

/**
 * 本地引物 LIMS-lite 仓库（main src/lib/genome/biophysics/primersLims.ts +
 * limsCollaboration.ts Feature 24/26 1:1 移植）。
 *
 * - StockPrimer：本地库存引物（id/name/sequence/tm/concentration/volume/location）；
 * - matchQueryToStockPrimers：k-mer 种子滑动比对，判断库存引物能否结合查询序列；
 * - exportPrimerOrderFormat：Sangon / Tsingke / IDT 合成下单表 CSV；
 * - generate2DGrid：96 孔板 / 10×10 冻箱盒二维网格映射 + 点位冲突检测。
 *
 * Tm 复用 [CloningExt.calculatePrimerTm]（<14nt Wallace，否则 GC 经验式）。
 */

data class StockPrimer(
    val id: String,
    val name: String,
    val sequence: String,
    val tm: Double,
    val concentration: Int,
    val volume: Int,
    val location: String,
    val orderedDate: String? = null
)

data class OrderPrimer(
    val name: String,
    val sequence: String,
    val scale: String,
    val purification: String,
    val mod5: String? = null,
    val mod3: String? = null
)

data class GridSample(
    val sampleId: String,
    val name: String,
    val type: String,
    val position: String,
    val color: String? = null
)

data class GridCell(
    val coordinate: String,
    val rowLabel: String,
    val colLabel: String,
    val sample: GridSample? = null
)

data class GridResult(
    val rows: List<String>,
    val columns: List<String>,
    val grid: List<List<GridCell>>,
    val overlapClashes: List<String>
)

object PrimerLims {

    private fun cleanDna(s: String): String = s.uppercase().filter { it in "ATCG" }

    /**
     * 对齐并检索可结合查询序列的库存引物（main matchQueryToStockPrimers）。
     * 先做包含判断；否则滑动偏移计算连续匹配长度，达到 [minOverlap] 视为命中。
     */
    fun matchQueryToStockPrimers(
        primerSequence: String,
        inventory: List<StockPrimer>,
        minOverlap: Int = 15
    ): List<StockPrimer> {
        val query = cleanDna(primerSequence)
        if (query.length < minOverlap) return emptyList()

        val matches = mutableListOf<StockPrimer>()
        for (item in inventory) {
            val stockSeq = cleanDna(item.sequence)

            if (stockSeq.contains(query) || query.contains(stockSeq)) {
                matches += item
                continue
            }

            var maxOverlap = 0
            for (offset in -stockSeq.length + minOverlap until query.length - minOverlap) {
                var currentMatchLen = 0
                for (i in stockSeq.indices) {
                    val queryIdx = i + offset
                    if (queryIdx in query.indices) {
                        if (stockSeq[i] == query[queryIdx]) currentMatchLen++ else currentMatchLen = 0
                    }
                }
                if (currentMatchLen > maxOverlap) maxOverlap = currentMatchLen
            }

            if (maxOverlap >= minOverlap) matches += item
        }
        return matches
    }

    /**
     * 把引物列表格式化为标准合成下单表（main exportPrimerOrderFormat）。
     * company: "Sangon" | "Tsingke" | "IDT"。
     */
    fun exportPrimerOrderFormat(primers: List<OrderPrimer>, company: String): String {
        val lines = mutableListOf<String>()
        when (company) {
            "IDT" -> {
                lines += "Name,Sequence,Scale,Purification"
                for (p in primers) {
                    var seq = cleanDna(p.sequence)
                    if (!p.mod5.isNullOrBlank()) seq = "/${p.mod5}/$seq"
                    if (!p.mod3.isNullOrBlank()) seq = "$seq/${p.mod3}/"
                    val scaleVal = p.scale.replace(" nmol", "nm")
                    lines += "\"${p.name}\",\"$seq\",\"$scaleVal\",\"${p.purification}\""
                }
            }
            "Sangon" -> {
                lines += I18n.t("引物名称,引物序列(5'-3'),合成规模,纯化方式,5'修饰,3'修饰")
                for (p in primers) {
                    val cleanSeq = cleanDna(p.sequence)
                    val mod5 = p.mod5 ?: I18n.t("无")
                    val mod3 = p.mod3 ?: I18n.t("无")
                    lines += "\"${p.name}\",\"$cleanSeq\",\"${p.scale}\",\"${p.purification}\",\"$mod5\",\"$mod3\""
                }
            }
            else -> { // Tsingke
                lines += "Name,Sequence(5'-3'),Scale,Purification,5'-Mod,3'-Mod"
                for (p in primers) {
                    val cleanSeq = cleanDna(p.sequence)
                    lines += "\"${p.name}\",\"$cleanSeq\",\"${p.scale}\",\"${p.purification}\",\"${p.mod5 ?: ""}\",\"${p.mod3 ?: ""}\""
                }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 把扁平样本列表翻译成二维网格（main generate2DGrid）。
     * gridType: "96-well"（A–H × 1–12）| "10x10-freezer"（R1–R10 × C1–C10）。
     */
    fun generate2DGrid(samples: List<GridSample>, gridType: String): GridResult {
        val rows = if (gridType == "96-well") {
            listOf("A", "B", "C", "D", "E", "F", "G", "H")
        } else {
            (1..10).map { "R$it" }
        }
        val columns = if (gridType == "96-well") {
            (1..12).map { it.toString() }
        } else {
            (1..10).map { "C$it" }
        }

        val posMap = mutableMapOf<String, GridSample>()
        val overlapClashes = mutableListOf<String>()
        for (s in samples) {
            val posUpper = s.position.uppercase()
            val existing = posMap[posUpper]
            if (existing != null) {
                overlapClashes += I18n.tf(
                    "位置冲突！坐标 {v1} 同时分配给了 \"{v2}\" 和 \"{v3}\"",
                    s.position, existing.name, s.name
                )
            } else {
                posMap[posUpper] = s
            }
        }

        val grid = rows.map { rowLabel ->
            columns.map { colLabel ->
                val coordinate = if (gridType == "96-well") "$rowLabel$colLabel" else "$rowLabel-$colLabel"
                GridCell(coordinate, rowLabel, colLabel, posMap[coordinate.uppercase()])
            }
        }
        return GridResult(rows, columns, grid, overlapClashes)
    }
}
