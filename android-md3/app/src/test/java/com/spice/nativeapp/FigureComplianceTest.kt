package com.spice.nativeapp

import com.spice.nativeapp.core.FigureCompliance
import com.spice.nativeapp.core.FigureCompliance.Document
import com.spice.nativeapp.core.FigureCompliance.ImagePixelSize
import com.spice.nativeapp.core.FigureCompliance.Node
import com.spice.nativeapp.core.FigureCompliance.Severity
import org.junit.Assert.assertEquals

/**
 * FigureComplianceTest —— T9 figure 投稿检查算法层 1:1 校验。
 *
 * 期望值由 main compliance.ts 语义手算烤入：
 * 阈值 5/6 pt 字号、0.25 pt 描边（px×0.75）、150/300 dpi、CIE76 ΔE 18、
 * 0.58 em 字宽 × 1.1 溢出容差、severity→节点序→产出序稳定排序。
 */
class FigureComplianceTest {

    private fun textNode(
        id: String, fontSize: Double, text: String, w: Double,
        padding: Double = 0.0, hidden: Boolean = false, opacity: Double = 1.0
    ) = Node(id = id, type = "text", w = w, h = 20.0, fontSize = fontSize,
        text = text, padding = padding, hidden = hidden, opacity = opacity,
        stroke = "#000000", strokeWidth = 1.0)

    // ───────────────────────── 字号检查 ─────────────────────────

    @org.junit.Test
    fun font_tiny_error_below_5pt() {
        val issues = FigureCompliance.checkFigure(Document(listOf(textNode("t1", 4.0, "abc", 100.0))))
        assertEquals(1, issues.size)
        assertEquals(Severity.ERROR, issues[0].severity)
        assertEquals("fontTiny", issues[0].code)
        assertEquals("text", issues[0].name)          // 无 name → type
        assertEquals("4", issues[0].params["size"])   // round(4.0) → JS toString
    }

    @org.junit.Test
    fun font_small_warn_5_to_6pt() {
        val issues = FigureCompliance.checkFigure(Document(listOf(textNode("t2", 5.5, "abc", 100.0))))
        assertEquals(1, issues.size)
        assertEquals(Severity.WARN, issues[0].severity)
        assertEquals("fontSmall", issues[0].code)
        assertEquals("5.5", issues[0].params["size"])
    }

    @org.junit.Test
    fun font_ok_at_6pt() {
        // 6pt 无字号问题；但 text 节点需避开 stroke（text 不检查描边）与溢出
        val issues = FigureCompliance.checkFigure(
            Document(listOf(textNode("t3", 6.0, "a", 500.0)))
        )
        assertEquals(0, issues.size)
    }

    @org.junit.Test
    fun font_skips_hidden_and_zero_opacity() {
        val doc = Document(listOf(textNode("t4", 4.0, "abc", 100.0, hidden = true)))
        assertEquals(0, FigureCompliance.checkFigure(doc).size)
        val doc2 = Document(listOf(textNode("t5", 4.0, "abc", 100.0, opacity = 0.0)))
        assertEquals(0, FigureCompliance.checkFigure(doc2).size)
    }

    @org.junit.Test
    fun font_hidden_group_suppresses_member() {
        val doc = Document(
            nodes = listOf(textNode("t6", 4.0, "abc", 100.0).let { it.copy(groupId = "g1") }),
            groups = listOf(FigureCompliance.Group("g1", hidden = true))
        )
        assertEquals(0, FigureCompliance.checkFigure(doc).size)
    }

    // ───────────────────────── 描边检查 ─────────────────────────

    @org.junit.Test
    fun stroke_thin_warn_below_quarter_pt() {
        // rect 描边 0.3px → 0.225pt < 0.25 → warn
        val rect = Node("r1", type = "rect", w = 50.0, h = 30.0, stroke = "#000", strokeWidth = 0.3)
        val issues = FigureCompliance.checkFigure(Document(listOf(rect)))
        assertEquals(1, issues.size)
        assertEquals(Severity.WARN, issues[0].severity)
        assertEquals("strokeThin", issues[0].code)
        // 0.225×100 双精度 = 22.499999…→ round 22 → 0.22（main JS 同值）
        assertEquals("0.22", issues[0].params["size"])
    }

    @org.junit.Test
    fun stroke_none_or_text_image_asset_exempt() {
        // stroke "none" 跳过
        assertEquals(0, FigureCompliance.checkFigure(
            Document(listOf(Node("r2", "rect", w = 1.0, h = 1.0, stroke = "none", strokeWidth = 0.1)))
        ).size)
        // text / asset / image 不检查描边
        assertEquals(0, FigureCompliance.checkFigure(
            Document(listOf(textNode("t7", 10.0, "a", 500.0, padding = 0.0)))
        ).size)
        val img = Node("i1", type = "image", w = 10.0, h = 10.0, stroke = "#000", strokeWidth = 0.1)
        assertEquals(0, FigureCompliance.checkFigure(Document(listOf(img))).size)
    }

    // ───────────────────────── 图像分辨率 ─────────────────────────

    @org.junit.Test
    fun image_low_dpi_warn_150_to_300() {
        // 600×400 px 放在 300×200 CSS px：dpiX=600/(300/96)=192, dpiY 同 → warn
        val img = Node("i2", type = "image", w = 300.0, h = 200.0)
        val issues = FigureCompliance.checkFigure(Document(listOf(img)), mapOf("i2" to ImagePixelSize(600.0, 400.0)))
        assertEquals(1, issues.size)
        assertEquals(Severity.WARN, issues[0].severity)
        assertEquals("imageLowDpi", issues[0].code)
        assertEquals("192", issues[0].params["dpi"])
    }

    @org.junit.Test
    fun image_very_low_dpi_error_below_150() {
        // 200×100 px 放在 100×100：dpiX=192, dpiY=96 → min=96 → error
        val img = Node("i3", type = "image", w = 100.0, h = 100.0)
        val issues = FigureCompliance.checkFigure(Document(listOf(img)), mapOf("i3" to ImagePixelSize(200.0, 100.0)))
        assertEquals(1, issues.size)
        assertEquals(Severity.ERROR, issues[0].severity)
        assertEquals("imageVeryLowDpi", issues[0].code)
        assertEquals("96", issues[0].params["dpi"])
    }

    @org.junit.Test
    fun image_300dpi_passes_and_dataurl_fallback_key() {
        // 900×600 px 放在 300×200：dpi=288? → 900/(300/96)=288 <300 warn；用 300 dpi 整：1000/(300/96)=320 → pass
        val img = Node("i4", type = "image", w = 300.0, h = 200.0, dataUrl = "data:image/png;base64,XX")
        assertEquals(0, FigureCompliance.checkFigure(
            Document(listOf(img)),
            mapOf("data:image/png;base64,XX" to ImagePixelSize(1000.0, 667.0))
        ).size)
    }

    // ───────────────────────── 系列颜色 CIE76 ─────────────────────────

    @org.junit.Test
    fun color_distance_black_white_is_100() {
        // CIE76 ΔE(黑,白) 浮点非精确 100（main 同样的 Lab 常数链）
        assertEquals(100.0, FigureCompliance.colorDistance(Triple(0, 0, 0), Triple(255, 255, 255)), 1e-5)
    }

    @org.junit.Test
    fun series_colors_close_warns_with_fallback_names() {
        // #000000 与 #0a0a0a：ΔE 远小于 18 → warn；未命名系列用 "Series 1"/"Series 2"
        val chart = Node(
            "c1", type = "chart", w = 100.0, h = 100.0, fontSize = 10.0,
            series = listOf(
                Node.Series(null, "#000000"),
                Node.Series(null, "#0a0a0a")
            )
        )
        val issues = FigureCompliance.checkFigure(Document(listOf(chart)))
        assertEquals(1, issues.size)
        assertEquals(Severity.WARN, issues[0].severity)
        assertEquals("seriesColorsClose", issues[0].code)
        assertEquals("Series 1", issues[0].params["a"])
        assertEquals("Series 2", issues[0].params["b"])
    }

    @org.junit.Test
    fun series_named_colors_far_apart_pass() {
        // red 与 green：CIE76 ≈ 172 > 18 → 无 issue；具名色 + named-color 字符串
        val chart = Node(
            "c2", type = "chart", w = 100.0, h = 100.0, fontSize = 10.0,
            series = listOf(
                Node.Series("ctrl", "red"),
                Node.Series("treat", "green")
            )
        )
        assertEquals(0, FigureCompliance.checkFigure(Document(listOf(chart))).size)
    }

    // ───────────────────────── 文本溢出 ─────────────────────────

    @org.junit.Test
    fun text_overflow_warns_when_longer_than_box() {
        // 10pt 字、text "AAAA"（4 码点）：fontSizePx=13.333，longest=4×13.333×0.58=30.93；
        // w=20、padding=0 → available=20，20×1.1=22 < 30.93 → warn
        val issues = FigureCompliance.checkFigure(Document(listOf(textNode("t8", 10.0, "AAAA", 20.0))))
        assertEquals(1, issues.size)
        assertEquals("textOverflow", issues[0].code)
        assertEquals(Severity.WARN, issues[0].severity)
    }

    @org.junit.Test
    fun text_multiline_and_tabs_use_longest_line() {
        // 第二行更长触发；tab = 4 空格（5 chars）；第一行短
        val issues = FigureCompliance.checkFigure(
            Document(listOf(textNode("t9", 10.0, "ab\n\tcde", 40.0)))
        )
        // 行2 "\tcde" = 4+3=7 chars → 7×13.333×0.58=54.1 > 44 → warn
        assertEquals(1, issues.size)
    }

    @org.junit.Test
    fun text_empty_and_zero_width_exempt() {
        assertEquals(0, FigureCompliance.checkFigure(
            Document(listOf(textNode("t10", 10.0, "   ", 20.0)))
        ).size)
    }

    // ───────────────────────── 排序与计数 ─────────────────────────

    @org.junit.Test
    fun stable_order_severity_then_document_order() {
        // n1 (fontTiny error) / n2 (fontSmall warn) / n3 (strokeThin warn) / n4 (textOverflow warn)
        // 期望顺序：error(n1) → warn 按文档序 n2, n3, n4
        val doc = Document(listOf(
            textNode("n1", 4.0, "abc", 10.0),                          // fontTiny error（同时 textOverflow，但 error 先）
            textNode("n2", 5.5, "abc", 10.0),                          // fontSmall warn（+overflow warn）
            Node("n3", "rect", w = 5.0, h = 5.0, stroke = "#000", strokeWidth = 0.2),  // strokeThin warn
            textNode("n4", 10.0, "AAAA", 20.0)                          // 仅 textOverflow warn
        ))
        val issues = FigureCompliance.checkFigure(doc)
        assertEquals(Severity.ERROR, issues[0].severity)
        assertEquals("n1", issues[0].nodeId)
        val warnNodes = issues.drop(1).filter { it.severity == Severity.WARN }.mapNotNull { it.nodeId }
        // 同节点多 issue 保持产出序：n2 的 fontSmall 在 overflow 前
        assertEquals(listOf("n2", "n2", "n3", "n4"), warnNodes)
    }

    @org.junit.Test
    fun counts_aggregate_by_severity() {
        val doc = Document(listOf(
            textNode("a1", 4.0, "abc", 10.0),
            textNode("a2", 5.5, "abc", 10.0),
            Node("a3", "rect", w = 5.0, h = 5.0, stroke = "#000", strokeWidth = 0.2)
        ))
        val counts = FigureCompliance.complianceCounts(FigureCompliance.checkFigure(doc))
        assertEquals(1, counts.errors)
        assertEquals(3, counts.warns)   // a2 fontSmall(+overflow)、a3 strokeThin
        assertEquals(0, counts.infos)
        assertEquals(4, counts.total)
    }
}
