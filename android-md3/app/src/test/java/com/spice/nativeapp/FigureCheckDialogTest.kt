package com.spice.nativeapp

import com.spice.nativeapp.core.FigureCompliance
import com.spice.nativeapp.tools.figureIssueText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FigureCheckDialog 词句映射测试（main FigureCheckDialog.svelte issueText 1:1）。
 * 测试环境 I18n lang=zh 直返 zh 原文；命名占位符经 figFmt 替换。
 */
class FigureCheckDialogTest {

    private fun issue(code: String, name: String, params: Map<String, String>) =
        FigureCompliance.Issue("id", FigureCompliance.Severity.ERROR, code, "n1", name, params)

    @Test
    fun issue_texts_map_codes_to_named_placeholder_words() {
        assertEquals(
            "文字“样本”仅 4.5 pt，印刷后可能无法辨认。",
            figureIssueText(issue("fontTiny", "样本", mapOf("size" to "4.5")))
        )
        assertEquals(
            "文字“样本”为 5.5 pt，期刊通常要求至少 6 pt。",
            figureIssueText(issue("fontSmall", "样本", mapOf("size" to "5.5")))
        )
        assertEquals(
            "“箭头”的线宽为 0.22 pt，建议至少 0.25 pt。",
            figureIssueText(issue("strokeThin", "箭头", mapOf("size" to "0.22")))
        )
        assertEquals(
            "“图片 A”的有效分辨率为 192 dpi，建议使用 300 dpi。",
            figureIssueText(issue("imageLowDpi", "图片 A", mapOf("dpi" to "192")))
        )
        assertEquals(
            "“图片 B”的有效分辨率仅 96 dpi，低于 150 dpi 会明显像素化。",
            figureIssueText(issue("imageVeryLowDpi", "图片 B", mapOf("dpi" to "96")))
        )
        assertEquals(
            "图表系列颜色“ctrl”和“treat”较难区分。",
            figureIssueText(issue("seriesColorsClose", "图表", mapOf("a" to "ctrl", "b" to "treat")))
        )
        assertEquals(
            "文字“标题”可能超出文本框。",
            figureIssueText(issue("textOverflow", "标题", emptyMap()))
        )
    }

    @Test
    fun unknown_code_falls_back_to_name() {
        assertEquals("某节点", figureIssueText(issue("whatever", "某节点", emptyMap())))
    }

    @Test
    fun missing_params_render_as_empty_like_main() {
        // main issueText：params.size ?? '' 兜底
        assertEquals(
            "文字“样本”仅  pt，印刷后可能无法辨认。",
            figureIssueText(issue("fontTiny", "样本", emptyMap()))
        )
    }
}
