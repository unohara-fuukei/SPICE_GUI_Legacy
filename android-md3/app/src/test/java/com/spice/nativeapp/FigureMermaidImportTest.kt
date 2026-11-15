package com.spice.nativeapp

import com.spice.nativeapp.core.FigureImportVector
import com.spice.nativeapp.core.FigureMermaidImport
import com.spice.nativeapp.core.FigureMermaidImport.MermaidImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FigureMermaidImportTest —— main mermaidImport.ts 纯逻辑 1:1。
 * （importMermaid 的渲染路径在 main 中即依赖浏览器 DOM；安卓侧固定走其
 * "browser environment" 异常分支，README 披露。）
 */
class FigureMermaidImportTest {

    @Test
    fun example_matches_main_source() {
        assertEquals(
            "flowchart TD\n  A[Sample] --> B[QC]\n  B -->|pass| C[Analyze]\n  B -->|fail| D[Repeat]\n  D --> A",
            FigureMermaidImport.MERMAID_EXAMPLE,
        )
    }

    @Test
    fun first_keyword_skips_frontmatter_and_directives() {
        assertEquals("flowchart", FigureMermaidImport.firstKeyword("flowchart TD\n  A --> B"))
        assertEquals("graph", FigureMermaidImport.firstKeyword("graph LR\n  A --> B"))
        // frontmatter（--- config ---）先于图表关键字。
        assertEquals(
            "flowchart",
            FigureMermaidImport.firstKeyword("---\ntitle: x\n---\nflowchart TD\n  A --> B"),
        )
        // 指令与注释行不含关键字。
        assertEquals("graph", FigureMermaidImport.firstKeyword("%%{init: {}}%%\n%% comment\ngraph TD"))
        assertEquals("", FigureMermaidImport.firstKeyword("   "))
    }

    @Test
    fun assert_flowchart_accepts_flowchart_keywords() {
        // 不抛即通过
        FigureMermaidImport.assertFlowchart("flowchart TD\n  A --> B")
        FigureMermaidImport.assertFlowchart("graph LR\n  A --> B")
        FigureMermaidImport.assertFlowchart("flowchart-beta TD\n  A --> B")
        FigureMermaidImport.assertFlowchart("FLOWCHART TD\n  A --> B")
    }

    @Test
    fun assert_flowchart_rejects_empty_and_non_flowchart() {
        val empty = try {
            FigureMermaidImport.assertFlowchart("")
            null
        } catch (e: MermaidImportException) { e }
        assertEquals("Mermaid source is empty.", empty?.message)

        val seq = try {
            FigureMermaidImport.assertFlowchart("sequenceDiagram\n  A->>B: hi")
            null
        } catch (e: MermaidImportException) { e }
        val seqMessage = seq?.message ?: ""
        assertTrue(seqMessage.contains("sequenceDiagram"))
        assertTrue(seqMessage.contains("SVG tab"))
    }

    @Test
    fun import_mermaid_throws_browser_environment_error_on_android() {
        val thrown = try {
            FigureMermaidImport.importMermaid("flowchart TD\n  A --> B")
            null
        } catch (e: MermaidImportException) { e }
        assertEquals("Mermaid import requires a browser environment.", thrown?.message)
    }

    @Test
    fun import_vector_result_is_usable_by_place() {
        // 端到端：SVG 路线完整可用（安卓上的主路线）。
        val result = FigureImportVector.importSvgMarkup(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\">" +
                "<rect x=\"10\" y=\"20\" width=\"30\" height=\"40\" fill=\"#ff0000\"/></svg>",
        )
        assertEquals(1, result.nodes.size)
        val (nodes, _) = FigureImportVector.placeVectorImport(result, 1600.0, 900.0)
        assertTrue(nodes[0].x >= 0.0 && nodes[0].y >= 0.0)
        assertTrue(nodes[0].w > 0.0 && nodes[0].h > 0.0)
    }
}
