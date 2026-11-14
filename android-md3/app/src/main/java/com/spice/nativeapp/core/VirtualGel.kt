package com.spice.nativeapp.core

/**
 * main src/lib/gene/VirtualGel.svelte 算法层 1:1（虚拟琼脂糖凝胶电泳）。
 *
 * 纯算法：marker 集、迁移率模型（琼脂糖浓度/时间）、泳道条带标签防碰撞、
 * 条带输入解析、泳道命名/gelWidth 派生。渲染与 PNG 导出在 UI 层
 * （ui/CloningPanels.kt VirtualGelPanel，Compose Canvas ≈ SVG/Canvas）。
 *
 * 平台差异（披露）：PNG 导出用 android.graphics.Bitmap 离屏画布替代
 * document.createElement('canvas')，倍率读取 SharedPreferences
 * spice_gel_export_multiplier（对应 main localStorage 同名键）；
 * backend.saveExport → 应用外部 Pictures 目录写文件 + toast 路径。
 */
object VirtualGel {

    /** main VirtualGel.svelte GelLane interface 1:1 */
    data class GelLane(
        val id: String,
        val name: String,
        val bands: List<Int>,
        val color: String
    )

    /** 防碰撞条带标签项（main resolveLabelCollisions 返回 {bp, y, labelY}） */
    data class BandLabel(
        val bp: Int,
        val y: Double,
        val labelY: Double
    )

    /** main MARKER_SETS verbatim（4 组 ladder） */
    val MARKER_SETS: LinkedHashMap<String, List<Int>> = linkedMapOf(
        "DL2000" to listOf(2000, 1500, 1000, 750, 500, 250, 100),
        "DL5000" to listOf(5000, 3000, 2000, 1500, 1000, 500, 250, 100),
        "DL10000" to listOf(10000, 7000, 5000, 3000, 2000, 1500, 1000, 500),
        "1kb Plus" to listOf(12000, 8000, 5000, 3000, 2000, 1500, 1000, 500, 300, 100)
    )

    /**
     * 凝胶迁移率模型（main getMigrationY verbatim）：
     * agaroseFactor = ln(琼脂糖%)/ln(1.2)；A = 210 - factor×15；B = 25 + factor×3；
     * bp 钳制 [50, 20000]；timeFactor = 时间/30；
     * y = max(25, min(240, (A - B×ln(钳制bp)) × timeFactor × 1.6))。
     */
    fun getMigrationY(bp: Int, agarosePercent: Double, runTime: Int): Double {
        val agaroseFactor = kotlin.math.ln(agarosePercent) / kotlin.math.ln(1.2)
        val a = 210 - agaroseFactor * 15
        val b = 25 + agaroseFactor * 3
        val clamped = kotlin.math.max(50, kotlin.math.min(20000, bp))
        val timeFactor = runTime / 30.0
        val raw = (a - b * kotlin.math.ln(clamped.toDouble())) * timeFactor * 1.6
        return kotlin.math.max(25.0, kotlin.math.min(240.0, raw))
    }

    /**
     * 条带标签防碰撞（main resolveLabelCollisions verbatim，minDist=9px）：
     * 按 y 升序排（稳定序），相邻标签间距 <minDist 时向下推。
     */
    fun resolveLabelCollisions(bps: List<Int>, minDist: Int = 9, agarosePercent: Double = 1.2, runTime: Int = 30): List<BandLabel> {
        val items = bps.map { bp ->
            Triple(bp, getMigrationY(bp, agarosePercent, runTime), getMigrationY(bp, agarosePercent, runTime))
        }.map { BandLabel(it.first, it.second, it.third) }
            .sortedBy { it.y } // JS Array.sort 比较器 (a.y-b.y)；sortedBy 稳定序一致
        val out = items.toMutableList()
        for (i in 1 until out.size) {
            val prev = out[i - 1]
            val curr = out[i]
            if (curr.labelY - prev.labelY < minDist) {
                out[i] = curr.copy(labelY = prev.labelY + minDist)
            }
        }
        return out
    }

    /** 条带输入解析（main updateLaneBands verbatim）：逗号拆分、parseInt、NaN/≤0 过滤 */
    fun parseLaneBands(text: String): List<Int> {
        return text.split(",").mapNotNull { s ->
            val t = s.trim()
            // JS parseInt：前导数字即取（"12000.9" → 12000）；非数字开头 → NaN
            Regex("^\\s*[+-]?\\d+").find(t)?.value?.toIntOrNull()
        }.filter { it > 0 }
    }

    /** main addLane：泳道命名 `lane{idx}` / `Lane {idx}`，默认色 #4cd6ff */
    fun addLane(lanes: List<GelLane>, fragments: List<Int>): List<GelLane> {
        val idx = lanes.size + 1
        return lanes + GelLane(
            id = "lane$idx",
            name = "Lane $idx",
            bands = fragments.toList(),
            color = "#4cd6ff"
        )
    }

    /** main gelWidth derived：120 + 泳道数×110 */
    fun gelWidth(laneCount: Int): Int = 120 + laneCount * 110
}
