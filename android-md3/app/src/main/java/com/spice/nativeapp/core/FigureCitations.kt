package com.spice.nativeapp.core

// FigureCitations.kt —— main src/lib/tools/figure/exporters.ts citationMarkdown() 1:1。
//
// 安卓侧 figure 文档暂无 asset 节点（素材库未移植），citation 输出与 main
// "无使用素材" 分支一致：头部块 + 'No third-party assets used.'（README 披露）。

object FigureCitations {

    /** main citationMarkdown：使用中第三方素材的溯源块。 */
    fun citationMarkdown(docName: String, generatedAt: String = nowIso()): String {
        val lines = mutableListOf(
            "# Figure Studio — asset provenance",
            "",
            "_${docName.ifEmpty { "Figure" }} — generated ${generatedAt}_",
            "",
        )
        // 安卓文档无 asset 节点：与 main used.length === 0 分支一致。
        lines.add("No third-party assets used.")
        return lines.joinToString("\n") + "\n"
    }

    private fun nowIso(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }
}
