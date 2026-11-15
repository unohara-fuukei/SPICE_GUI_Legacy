package com.spice.nativeapp

import com.spice.nativeapp.core.FigureImportVector
import com.spice.nativeapp.core.FigureImportVector.SvgImportException
import com.spice.nativeapp.core.FigureImportVector.VNode
import com.spice.nativeapp.core.FigureImportVector.VectorImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FigureImportVectorTest —— main tests/figure/importVector.test.ts 1:1 对应。
 *
 * main 在 jsdom 环境跑（getBBox/getCTM 缺失 → 属性回退路径），
 * 与本移植固定的回退路径行为一致，断言逐条对齐。
 */
class FigureImportVectorTest {

    private fun markup(body: String, viewBox: String = "0 0 200 100"): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$viewBox\">$body</svg>"

    private fun import(body: String): VectorImportResult =
        FigureImportVector.importSvgMarkup(markup(body))

    private fun expectThrows(svg: String, messagePart: String) {
        try {
            FigureImportVector.importSvgMarkup(svg)
            fail("expected SvgImportException containing '$messagePart'")
        } catch (e: SvgImportException) {
            assertTrue("message '${e.message}' should contain '$messagePart'", e.message!!.contains(messagePart, ignoreCase = true))
        }
    }

    // ------------------------------------------------------------- primitives

    @Test
    fun converts_a_rect_preserves_paint_and_normalises_the_origin() {
        val result = import(
            "<rect x=\"10\" y=\"20\" width=\"30\" height=\"40\" fill=\"#ff0000\" stroke=\"#00ff00\" stroke-width=\"2\"/>",
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.nodes.size)
        assertEquals(30.0, result.width, 1e-6)
        assertEquals(40.0, result.height, 1e-6)

        val node = result.nodes[0]
        assertEquals("rect", node.type)
        assertEquals(0.0, node.x, 1e-6)
        assertEquals(0.0, node.y, 1e-6)
        assertEquals(30.0, node.w, 1e-6)
        assertEquals(40.0, node.h, 1e-6)
        assertEquals("#ff0000", node.fill)
        assertEquals("#00ff00", node.stroke)
        assertEquals(2.0, node.strokeWidth, 1e-6)
        assertEquals(0.0, node.rotation, 1e-6)
    }

    @Test
    fun converts_circles_and_ellipses_to_ellipse_nodes() {
        val result = import("<ellipse cx=\"100\" cy=\"50\" rx=\"20\" ry=\"10\" fill=\"#123456\"/>")

        val node = result.nodes[0]
        assertEquals("ellipse", node.type)
        assertEquals(0.0, node.x, 1e-6)
        assertEquals(0.0, node.y, 1e-6)
        assertEquals(40.0, node.w, 1e-6)
        assertEquals(20.0, node.h, 1e-6)
        assertEquals("#123456", node.fill)
        assertEquals(40.0, result.width, 1e-6)
        assertEquals(20.0, result.height, 1e-6)
    }

    @Test
    fun converts_text_with_font_weight_alignment_and_colour() {
        val result = import(
            "<text x=\"10\" y=\"40\" font-size=\"16\" font-family=\"Arial\" font-weight=\"bold\" font-style=\"italic\" fill=\"#112233\" text-anchor=\"middle\">Hello</text>",
        )

        val node = result.nodes[0]
        assertEquals("text", node.type)
        assertEquals("Hello", node.text)
        // 96 dpi 下 16 px -> 12 pt。
        assertEquals(12.0, node.fontSize!!, 1e-6)
        assertEquals("Arial", node.fontFamily)
        assertEquals(700, node.fontWeight)
        assertTrue(node.italic!!)
        assertEquals("center", node.align)
        assertEquals("#112233", node.color)
        assertEquals(0.0, node.strokeWidth, 1e-6)
    }

    @Test
    fun joins_tspan_lines_with_newlines() {
        val result = import(
            "<text x=\"0\" y=\"10\"><tspan x=\"0\" y=\"10\">Line one</tspan><tspan x=\"0\" y=\"24\">Line two</tspan></text>",
        )

        val node = result.nodes[0]
        assertEquals("Line one\nLine two", node.text)
        assertEquals("left", node.align)
    }

    @Test
    fun flattens_a_closed_path_and_keeps_the_polygon_closed() {
        val result = import("<path d=\"M0 0 L10 0 L10 10 Z\" fill=\"#abcdef\" stroke=\"none\"/>")

        val node = result.nodes[0]
        assertEquals("path", node.type)
        assertTrue(node.closed!!)
        assertEquals("#abcdef", node.fill)
        assertEquals("none", node.stroke)
        assertEquals(false, node.headEnd)
        assertTrue(node.points!!.size >= 4)
        assertTrue(node.points!!.size <= 200)
        assertEquals(0.0, node.points!![0].x, 1e-6)
        assertEquals(0.0, node.points!![0].y, 1e-6)
        assertEquals(10.0, node.w, 1e-6)
        assertEquals(10.0, node.h, 1e-6)
    }

    @Test
    fun maps_marker_end_to_a_path_arrow_head() {
        val result = import(
            "<path d=\"M0 0 L20 0\" fill=\"none\" stroke=\"#000000\" stroke-width=\"2\" marker-end=\"url(#arrow)\"/>",
        )

        val node = result.nodes[0]
        assertTrue(node.headEnd!!)
        assertEquals(false, node.headStart!!)
        assertTrue(node.headSize!! > 0)
    }

    @Test
    fun keeps_polyline_and_line_geometry() {
        val result = import(
            "<polyline points=\"0,0 20,0 20,10\" fill=\"none\" stroke=\"#000000\"/><line x1=\"0\" y1=\"20\" x2=\"20\" y2=\"20\" stroke=\"#333333\"/>",
        )

        assertEquals(2, result.nodes.size)
        val (polyline, line) = result.nodes
        assertEquals(false, polyline.closed)
        assertEquals(3, polyline.points!!.size)
        assertEquals(false, line.closed)
        assertEquals(2, line.points!!.size)
    }

    // --------------------------------------------------- transforms and groups

    @Test
    fun applies_translate_and_scale_from_ancestor_groups() {
        val result = import(
            "<g transform=\"translate(100 50) scale(2)\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"5\" fill=\"#eeeeee\"/></g>",
        )

        val node = result.nodes[0]
        assertEquals(20.0, node.w, 1e-6)
        assertEquals(10.0, node.h, 1e-6)
        assertEquals(0.0, node.x, 1e-6)
        assertEquals(0.0, node.y, 1e-6)
        assertEquals(20.0, result.width, 1e-6)
        assertEquals(10.0, result.height, 1e-6)
    }

    @Test
    fun decomposes_rotation_and_keeps_non_rotated_local_dimensions() {
        val result = import(
            "<rect x=\"0\" y=\"0\" width=\"20\" height=\"10\" transform=\"rotate(90 5 5)\" fill=\"#eeeeee\"/>",
        )

        val node = result.nodes[0]
        assertEquals(90.0, node.rotation, 1e-4)
        assertEquals(20.0, node.w, 1e-6)
        assertEquals(10.0, node.h, 1e-6)
        // 旋转后的 20x10 矩形占据 10x20 轴对齐框。
        assertEquals(10.0, result.width, 1e-6)
        assertEquals(20.0, result.height, 1e-6)
    }

    @Test
    fun groups_a_mermaid_node_shape_and_label_under_one_group() {
        val result = import(
            """
            <g class="node default" id="flowchart-A-0" transform="translate(20 10)">
              <rect x="0" y="0" width="40" height="20" fill="#eeeeee" stroke="#333333"/>
              <g class="label"><text x="20" y="15" text-anchor="middle" fill="#111111">Sample</text></g>
            </g>
            """.trimIndent(),
        )

        assertEquals(2, result.nodes.size)
        assertEquals(1, result.groups.size)
        val group = result.groups[0]
        assertEquals("Sample", group.name)
        assertEquals(group.id, result.nodes[0].groupId)
        assertEquals(group.id, result.nodes[1].groupId)
        assertEquals("rect", result.nodes[0].type)
        assertEquals("text", result.nodes[1].type)
    }

    @Test
    fun drops_empty_groups_but_keeps_groups_that_have_members() {
        val result = import(
            "<g id=\"empty\"></g><g id=\"kept\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#000000\"/></g>",
        )

        assertEquals(1, result.groups.size)
        assertEquals("kept", result.groups[0].name)
        assertEquals(result.groups[0].id, result.nodes[0].groupId)
    }

    // ------------------------------------------------------- style resolution

    @Test
    fun reads_inline_css_colours_and_converts_rgb_to_hex() {
        val result = import(
            "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" style=\"fill: rgb(255, 0, 0); stroke: rgb(0, 128, 0); stroke-width: 3\"/>",
        )

        val node = result.nodes[0]
        assertEquals("#ff0000", node.fill)
        assertEquals("#008000", node.stroke)
        assertEquals(3.0, node.strokeWidth, 1e-6)
    }

    @Test
    fun resolves_gradients_to_their_first_stop_colour() {
        val result = import(
            """
            <defs>
              <linearGradient id="grad">
                <stop offset="0" stop-color="#123456"/>
                <stop offset="1" stop-color="#654321"/>
              </linearGradient>
            </defs>
            <rect x="0" y="0" width="10" height="10" fill="url(#grad)"/>
            """.trimIndent(),
        )

        assertEquals("#123456", result.nodes[0].fill)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun inherits_paint_from_ancestor_groups() {
        val result = import("<g fill=\"#ff00ff\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\"/></g>")

        assertEquals("#ff00ff", result.nodes[0].fill)
    }

    @Test
    fun multiplies_group_opacity_into_nodes() {
        val result = import(
            "<g opacity=\"0.5\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" opacity=\"0.5\" fill=\"#000000\"/></g>",
        )

        assertEquals(0.25, result.nodes[0].opacity, 1e-6)
    }

    @Test
    fun maps_stroke_dasharray_onto_the_dash_style() {
        val result = import(
            "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" stroke=\"#000000\" stroke-width=\"1\" stroke-dasharray=\"6 3\"/>" +
                "<rect x=\"20\" y=\"0\" width=\"10\" height=\"10\" stroke=\"#000000\" stroke-width=\"1\" stroke-dasharray=\"1 1\"/>",
        )

        assertEquals("dashed", result.nodes[0].dash)
        assertEquals("dotted", result.nodes[1].dash)
    }

    // --------------------------------------------- warnings and malformed input

    @Test
    fun warns_once_for_unsupported_elements() {
        val result = import(
            "<use href=\"#shape\"/><foreignObject width=\"10\" height=\"10\"/><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#000000\"/>",
        )

        assertEquals(1, result.nodes.size)
        assertTrue(result.warnings.size >= 2)
        assertTrue(result.warnings.any { it.contains("<use>") })
        assertTrue(result.warnings.any { it.lowercase().contains("foreignobject") })
    }

    @Test
    fun warns_about_images_that_are_not_embedded_data_urls() {
        val result = import(
            "<image x=\"0\" y=\"0\" width=\"10\" height=\"10\" href=\"photo.png\"/><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#000000\"/>",
        )

        assertEquals(1, result.nodes.size)
        assertTrue(result.warnings.any { it.contains("data:") })
    }

    @Test
    fun rejects_empty_non_svg_and_malformed_input() {
        expectThrows("", "empty")
        expectThrows("   ", "empty")
        expectThrows("not svg at all", "parse")
        expectThrows("<div>hello</div>", "not an SVG")
        expectThrows("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect></svg>", "parse")
    }

    @Test
    fun rejects_svgs_with_no_supported_drawables() {
        expectThrows(markup("<defs><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\"/></defs>"), "No supported vector elements")
    }

    // ---------------------------------------------------------- placeVectorImport

    @Test
    fun uniformly_scales_centres_and_scales_strokes() {
        val result = import(
            "<rect x=\"10\" y=\"10\" width=\"100\" height=\"50\" fill=\"#000000\" stroke=\"#111111\" stroke-width=\"2\"/>",
        )
        val (nodes, _) = FigureImportVector.placeVectorImport(result, 1000.0, 500.0, 0.1)

        // 100x50 源放进 800x400 安全区 -> 缩放 8 倍并居中。
        assertEquals(1, nodes.size)
        val node = nodes[0]
        assertEquals(100.0, node.x, 1e-6)
        assertEquals(50.0, node.y, 1e-6)
        assertEquals(800.0, node.w, 1e-6)
        assertEquals(400.0, node.h, 1e-6)
        assertEquals(16.0, node.strokeWidth, 1e-6)
    }

    @Test
    fun scales_font_size_and_padding_with_the_geometry() {
        val text = VNode(
            id = "node_test", type = "text", name = "Hi",
            x = 0.0, y = 0.0, w = 100.0, h = 50.0,
            fontSize = 12.0, padding = 4.0, strokeWidth = 2.0,
        )
        val result = VectorImportResult(listOf(text), emptyList(), 100.0, 50.0, emptyList())

        val (nodes, _) = FigureImportVector.placeVectorImport(result, 200.0, 100.0, 0.0)
        val node = nodes[0]
        assertEquals(0.0, node.x, 1e-6)
        assertEquals(0.0, node.y, 1e-6)
        assertEquals(200.0, node.w, 1e-6)
        assertEquals(100.0, node.h, 1e-6)
        assertEquals(24.0, node.fontSize!!, 1e-6)
        assertEquals(8.0, node.padding!!, 1e-6)
        assertEquals(4.0, node.strokeWidth, 1e-6)
    }

    @Test
    fun centres_a_square_import_on_a_wide_page() {
        val result = import("<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\" fill=\"#000000\"/>")
        val (nodes, _) = FigureImportVector.placeVectorImport(result, 400.0, 200.0, 0.0)

        assertEquals(200.0, nodes[0].w, 1e-6)
        assertEquals(200.0, nodes[0].h, 1e-6)
        assertEquals(100.0, nodes[0].x, 1e-6)
        assertEquals(0.0, nodes[0].y, 1e-6)
    }
}
