package com.spice.nativeapp.core

// FigureMermaidImport.kt —— main src/lib/tools/figure/mermaidImport.ts 1:1 移植。
//
// main 的 importMermaid 依赖 mermaid.js 在浏览器 DOM 中渲染 flowchart → SVG，
// 再走 importSvgMarkup。安卓侧无浏览器环境：main 源码在 `document === undefined`
// 分支本身即抛 'Mermaid import requires a browser environment.'，本移植固定走该
// 分支（README 披露：Mermaid 路线在安卓版不可用，请使用 SVG 路线）。
// assertFlowchart/firstKeyword/MERMAID_EXAMPLE 的纯逻辑部分 1:1 保留。

object FigureMermaidImport {

    /** 对话框"载入示例"按钮用的小 flowchart。 */
    const val MERMAID_EXAMPLE = "flowchart TD\n  A[Sample] --> B[QC]\n  B -->|pass| C[Analyze]\n  B -->|fail| D[Repeat]\n  D --> A"

    private val FLOWCHART_KEYWORDS = setOf("flowchart", "graph", "flowchart-beta")

    fun firstKeyword(source: String): String {
        var text = source.replace("^﻿".toRegex(), "").trim()
        // Mermaid frontmatter（--- config ---）在图表关键字之前。
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end >= 0) {
                text = text.substring(end + 4)
            }
        }
        val lines = text.split(Regex("\\r?\\n"))
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 指令（%%{init: ...}%%）与注释不含图表关键字。
            if (line.startsWith("%%")) continue
            return line.split(Regex("\\s+"))[0]
        }
        return ""
    }

    class MermaidImportException(message: String) : Exception(message)

    fun assertFlowchart(source: String) {
        if (source.isBlank()) {
            throw MermaidImportException("Mermaid source is empty.")
        }
        val keyword = firstKeyword(source)
        if (keyword.isEmpty()) {
            throw MermaidImportException("Mermaid source is empty.")
        }
        val normalized = keyword.lowercase()
        if (FLOWCHART_KEYWORDS.contains(normalized) || normalized.startsWith("flowchart-")) return
        throw MermaidImportException(
            "Mermaid \"$keyword\" diagrams cannot be imported as editable shapes. " +
                "Save the diagram as an SVG file and import it through the SVG tab instead.",
        )
    }

    /**
     * 渲染 Mermaid flowchart 为 SVG 并转换为 figure 节点（需要浏览器环境）。
     * 安卓无浏览器 DOM：与 main 的 `document === undefined` 分支行为一致。
     */
    fun importMermaid(source: String): FigureImportVector.VectorImportResult {
        assertFlowchart(source)
        throw MermaidImportException("Mermaid import requires a browser environment.")
    }
}
