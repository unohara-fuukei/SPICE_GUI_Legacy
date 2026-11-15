package com.spice.nativeapp.core

/**
 * FigureCompliance —— main tools/figure/compliance.ts 的 1:1 移植。
 *
 * 投稿合规检查（纯函数）：字号下限（5 pt error / 6 pt warn）、描边下限
 * （0.25 pt，px→pt ×0.75）、位图有效分辨率（<150 error / <300 warn，
 * dpi = min(w_px/(node.w/96), h_px/(node.h/96))）、图表系列颜色 CIE76
 * Lab 距离（<18 warn）、文本溢出（0.58 em 字宽 × 1.1 容差）。
 * 输出按 severity(error<warn<info) → 节点文档序 → 检查产出序稳定排序。
 *
 * 词句子典与本地化无关：本模块只产出 code + params，UI 层按 code 取词条
 * （figCheckFontTiny 等，见 FigureCheckDialog.svelte issueText）。
 */
object FigureCompliance {

    /** main types.ts PT_TO_PX */
    const val PT_TO_PX = 96.0 / 72.0

    const val FONT_TINY_PT = 5.0
    const val FONT_SMALL_PT = 6.0
    const val STROKE_MIN_PT = 0.25
    const val IMAGE_VERY_LOW_DPI = 150.0
    const val IMAGE_LOW_DPI = 300.0
    const val SERIES_COLOR_DISTANCE = 18.0
    const val GLYPH_WIDTH_FACTOR = 0.58
    const val OVERFLOW_TOLERANCE = 1.1

    /** main FigureNodeType（compliance 关心的子集 + 全量字符串值保持一致） */
    object NodeType {
        const val TEXT = "text"
        const val CHART = "chart"
        const val SCALEBAR = "scalebar"
        const val ASSET = "asset"
        const val IMAGE = "image"
    }

    enum class Severity { ERROR, WARN, INFO }

    data class Issue(
        val id: String,
        val severity: Severity,
        val code: String,          // fontTiny / fontSmall / strokeThin / imageLowDpi / ...
        val nodeId: String?,
        val name: String,
        val params: Map<String, String>
    )

    /** main ImagePixelSize（key：image 节点 id，data URL 兜底） */
    data class ImagePixelSize(val width: Double, val height: Double)

    /** compliance 检查所需的最小节点视图（main FigureNodeBase + 各类型差异字段） */
    data class Node(
        val id: String,
        val type: String,
        val name: String = "",
        val w: Double = 0.0,
        val h: Double = 0.0,
        val opacity: Double = 1.0,
        val hidden: Boolean = false,
        val groupId: String? = null,
        val stroke: String? = null,
        val strokeWidth: Double = Double.NaN,
        val fontSize: Double = Double.NaN,       // text / chart / scalebar
        val text: String = "",                   // text
        val padding: Double = 0.0,               // text
        val dataUrl: String? = null,             // image
        val series: List<Series> = emptyList()   // chart
    ) {
        data class Series(val name: String?, val color: String?)
    }

    data class Group(val id: String, val hidden: Boolean)

    data class Document(val nodes: List<Node>, val groups: List<Group> = emptyList())

    // ───────────────────────── JS round(x, 2) ─────────────────────────

    private fun jsRound(value: Double, digits: Int = 2): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }

    private fun nodeLabel(node: Node): String = node.name.trim().ifEmpty { node.type }

    private fun isNone(value: String?): Boolean {
        val text = (value ?: "").trim().lowercase()
        return text.isEmpty() || text == "none" || text == "transparent"
    }

    private fun isVisible(doc: Document, node: Node): Boolean {
        if (node.hidden || node.opacity <= 0) return false
        if (node.groupId == null) return true
        val group = doc.groups.firstOrNull { it.id == node.groupId } ?: return true
        return !group.hidden
    }

    // ───────────────────────── typography ─────────────────────────

    private fun fontPt(node: Node): Double? =
        if (node.type == NodeType.TEXT || node.type == NodeType.CHART || node.type == NodeType.SCALEBAR) {
            if (node.fontSize.isFinite()) node.fontSize else null
        } else null

    private fun checkFont(node: Node): Issue? {
        val size = fontPt(node) ?: return null
        val params = mapOf(
            "name" to nodeLabel(node),
            "size" to numToStr(jsRound(size))
        )
        if (size < FONT_TINY_PT) {
            return Issue("fontTiny:${node.id}", Severity.ERROR, "fontTiny", node.id, nodeLabel(node), params)
        }
        if (size < FONT_SMALL_PT) {
            return Issue("fontSmall:${node.id}", Severity.WARN, "fontSmall", node.id, nodeLabel(node), params)
        }
        return null
    }

    // ───────────────────────── strokes ─────────────────────────

    private fun checkStroke(node: Node): Issue? {
        if (node.type == NodeType.TEXT || node.type == NodeType.ASSET || node.type == NodeType.IMAGE) return null
        if (isNone(node.stroke)) return null
        val widthPx = node.strokeWidth
        if (!widthPx.isFinite() || widthPx <= 0) return null
        val widthPt = widthPx * 0.75
        if (widthPt >= STROKE_MIN_PT) return null
        return Issue(
            "strokeThin:${node.id}", Severity.WARN, "strokeThin", node.id, nodeLabel(node),
            mapOf("name" to nodeLabel(node), "size" to numToStr(jsRound(widthPt)))
        )
    }

    // ───────────────────────── images ─────────────────────────

    private fun checkImage(node: Node, imageSizes: Map<String, ImagePixelSize>): Issue? {
        if (node.type != NodeType.IMAGE) return null
        val pixels = imageSizes[node.id]
            ?: node.dataUrl?.let { imageSizes[it] }
            ?: return null
        if (!pixels.width.isFinite() || !pixels.height.isFinite() ||
            pixels.width <= 0 || pixels.height <= 0 || node.w <= 0 || node.h <= 0
        ) return null
        val dpiX = pixels.width / (node.w / 96.0)
        val dpiY = pixels.height / (node.h / 96.0)
        val dpi = minOf(dpiX, dpiY)
        if (!dpi.isFinite() || dpi <= 0) return null

        val params = mapOf("name" to nodeLabel(node), "dpi" to Math.round(dpi).toInt().toString())
        if (dpi < IMAGE_VERY_LOW_DPI) {
            return Issue("imageVeryLowDpi:${node.id}", Severity.ERROR, "imageVeryLowDpi", node.id, nodeLabel(node), params)
        }
        if (dpi < IMAGE_LOW_DPI) {
            return Issue("imageLowDpi:${node.id}", Severity.WARN, "imageLowDpi", node.id, nodeLabel(node), params)
        }
        return null
    }

    // ───────────────────────── colour math ─────────────────────────

    private val NAMED_COLORS: Map<String, Triple<Int, Int, Int>> = mapOf(
        "black" to Triple(0, 0, 0),
        "white" to Triple(255, 255, 255),
        "red" to Triple(255, 0, 0),
        "green" to Triple(0, 128, 0),
        "blue" to Triple(0, 0, 255),
        "yellow" to Triple(255, 255, 0),
        "orange" to Triple(255, 165, 0),
        "purple" to Triple(128, 0, 128),
        "gray" to Triple(128, 128, 128),
        "grey" to Triple(128, 128, 128)
    )

    private fun parseHex(hex: String): Triple<Int, Int, Int>? {
        val value = hex.substring(1)
        if (!Regex("^[0-9a-f]+$", RegexOption.IGNORE_CASE).matches(value)) return null
        return if (value.length == 3 || value.length == 4) {
            Triple(
                Integer.parseInt(value[0].toString() + value[0], 16),
                Integer.parseInt(value[1].toString() + value[1], 16),
                Integer.parseInt(value[2].toString() + value[2], 16)
            )
        } else if (value.length == 6 || value.length == 8) {
            Triple(
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16)
            )
        } else null
    }

    private fun parseColor(input: String?): Triple<Int, Int, Int>? {
        val text = (input ?: "").trim().lowercase()
        if (text.isEmpty() || text == "none" || text == "transparent") return null
        if (text.startsWith("#")) return parseHex(text)
        val match = Regex("""^rgba?\(\s*([\d.]+)\s*[, ]\s*([\d.]+)\s*[, ]\s*([\d.]+)""").find(text)
        if (match != null) {
            val rgb = Triple(
                match.groupValues[1].toDoubleOrNull(),
                match.groupValues[2].toDoubleOrNull(),
                match.groupValues[3].toDoubleOrNull()
            )
            if (rgb.first != null && rgb.second != null && rgb.third != null &&
                rgb.first!!.isFinite() && rgb.second!!.isFinite() && rgb.third!!.isFinite()
            ) {
                @Suppress("UNCHECKED_CAST")
                return Triple(
                    rgb.first!!.toInt(), rgb.second!!.toInt(), rgb.third!!.toInt()
                )
            }
            return null
        }
        return NAMED_COLORS[text]
    }

    private fun srgbToLinear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }

    private fun labF(value: Double): Double =
        if (value > 0.008856) Math.cbrt(value) else 7.787 * value + 16.0 / 116.0

    private fun labFromRgb(rgb: Triple<Int, Int, Int>): DoubleArray {
        val (r, g, b) = rgb
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)
        val x = lr * 0.4124564 + lg * 0.3575761 + lb * 0.1804375
        val y = lr * 0.2126729 + lg * 0.7151522 + lb * 0.072175
        val z = lr * 0.0193339 + lg * 0.119192 + lb * 0.9503041
        val fx = labF(x / 0.95047)
        val fy = labF(y / 1.0)
        val fz = labF(z / 1.08883)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** CIE76 ΔE */
    fun colorDistance(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Double {
        val la = labFromRgb(a)
        val lb = labFromRgb(b)
        return Math.hypot(Math.hypot(la[0] - lb[0], la[1] - lb[1]), la[2] - lb[2])
    }

    private fun checkSeriesColors(node: Node): List<Issue> {
        if (node.type != NodeType.CHART) return emptyList()
        val issues = mutableListOf<Issue>()
        for (i in node.series.indices) {
            val firstColor = parseColor(node.series[i].color) ?: continue
            for (j in i + 1 until node.series.size) {
                val secondColor = parseColor(node.series[j].color) ?: continue
                if (colorDistance(firstColor, secondColor) >= SERIES_COLOR_DISTANCE) continue
                issues.add(
                    Issue(
                        "seriesColorsClose:${node.id}:$i-$j", Severity.WARN, "seriesColorsClose",
                        node.id, nodeLabel(node),
                        mapOf(
                            "a" to (node.series[i].name?.trim()?.ifEmpty { null } ?: "Series ${i + 1}"),
                            "b" to (node.series[j].name?.trim()?.ifEmpty { null } ?: "Series ${j + 1}")
                        )
                    )
                )
            }
        }
        return issues
    }

    // ───────────────────────── text fit ─────────────────────────

    private fun longestLineWidthPx(node: Node): Double {
        val fontSizePx = maxOf(0.0, node.fontSize) * PT_TO_PX
        var longest = 0.0
        for (raw in node.text.split('\n')) {
            val line = raw.replace("\t", "    ")
            // JS [...line].length：按 Unicode 码点计数
            val chars = line.codePointCount()
            longest = maxOf(longest, chars * fontSizePx * GLYPH_WIDTH_FACTOR)
        }
        return longest
    }

    private fun checkTextOverflow(node: Node): Issue? {
        if (node.type != NodeType.TEXT) return null
        if (node.text.trim().isEmpty()) return null
        val padding = maxOf(0.0, node.padding)
        val available = node.w - padding * 2
        val longest = longestLineWidthPx(node)
        if (available <= 0 || longest > available * OVERFLOW_TOLERANCE) {
            return Issue(
                "textOverflow:${node.id}", Severity.WARN, "textOverflow",
                node.id, nodeLabel(node), mapOf("name" to nodeLabel(node))
            )
        }
        return null
    }

    // ───────────────────────── driver ─────────────────────────

    /** main checkFigure：全量检查 + 稳定排序（severity → 节点文档序 → 产出序） */
    fun checkFigure(doc: Document, imageSizes: Map<String, ImagePixelSize> = emptyMap()): List<Issue> {
        val issues = mutableListOf<Issue>()
        for (node in doc.nodes) {
            if (!isVisible(doc, node)) continue
            checkFont(node)?.let { issues.add(it) }
            checkStroke(node)?.let { issues.add(it) }
            checkImage(node, imageSizes)?.let { issues.add(it) }
            issues.addAll(checkSeriesColors(node))
            checkTextOverflow(node)?.let { issues.add(it) }
        }

        val order = HashMap<String, Int>()
        doc.nodes.forEachIndexed { index, node -> order[node.id] = index }
        val severityRank = mapOf(Severity.ERROR to 0, Severity.WARN to 1, Severity.INFO to 2)
        return issues
            .mapIndexed { index, issue -> Triple(issue, index, nodeRank(order, issue)) }
            .sortedWith(
                compareBy(
                    { severityRank[it.first.severity]!! },
                    { it.third },
                    { it.second }
                )
            )
            .map { it.first }
    }

    private fun nodeRank(order: Map<String, Int>, issue: Issue): Int =
        issue.nodeId?.let { order[it] } ?: Int.MAX_VALUE

    data class Counts(val errors: Int, val warns: Int, val infos: Int) {
        val total: Int get() = errors + warns + infos
    }

    fun complianceCounts(issues: List<Issue>): Counts {
        var errors = 0; var warns = 0; var infos = 0
        for (issue in issues) {
            when (issue.severity) {
                Severity.ERROR -> errors++
                Severity.WARN -> warns++
                Severity.INFO -> infos++
            }
        }
        return Counts(errors, warns, infos)
    }

    /** JS 模板 `${size}`：整数无小数位（params 渲染用） */
    private fun numToStr(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    private fun String.codePointCount(): Int = this.codePointCount(0, this.length)
}
