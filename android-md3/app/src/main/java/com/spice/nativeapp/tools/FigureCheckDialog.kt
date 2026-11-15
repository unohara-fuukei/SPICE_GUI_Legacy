package com.spice.nativeapp.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.FigureCompliance
import com.spice.nativeapp.core.I18n

/**
 * FigureCheckDialog —— main tools/figure/FigureCheckDialog.svelte 的安卓形态。
 *
 * 对话框只负责渲染问题清单；文档与位图尺寸探测由 FigureScreen 持有
 * （main 同构：编辑器 runPublicationCheck 探测后传入）。
 * 形态差异（README 披露）：main 为 pix 风格自绘模态，此处为 MD3 AlertDialog；
 * 摘要行/问题行配色沿用 main（error #ff5d5d / warn #f2b84b / info #4cd6ff）。
 */
object FigureCheckUi {
    val ERROR_COLOR = Color(0xFFFF5D5D)
    val WARN_COLOR = Color(0xFFF2B84B)
    val INFO_COLOR = Color(0xFF4CD6FF)
}

/** 命名占位符插值：main messages.ts interpolate 用 `{(\w+)}` 正则；安卓 I18n.tf 只有 {v1}..{v9}。 */
internal fun figFmt(tpl: String, vararg pairs: Pair<String, String>): String {
    var s = I18n.t(tpl)
    for ((k, v) in pairs) s = s.replace("{$k}", v)
    return s
}

/** main FigureCheckDialog.svelte issueText() 1:1：code → figCheck* 词条 + 命名参数。 */
internal fun figureIssueText(issue: FigureCompliance.Issue): String {
    val p = issue.params
    return when (issue.code) {
        "fontTiny" -> figFmt("文字“{name}”仅 {size} pt，印刷后可能无法辨认。", "name" to issue.name, "size" to (p["size"] ?: ""))
        "fontSmall" -> figFmt("文字“{name}”为 {size} pt，期刊通常要求至少 6 pt。", "name" to issue.name, "size" to (p["size"] ?: ""))
        "strokeThin" -> figFmt("“{name}”的线宽为 {size} pt，建议至少 0.25 pt。", "name" to issue.name, "size" to (p["size"] ?: ""))
        "imageLowDpi" -> figFmt("“{name}”的有效分辨率为 {dpi} dpi，建议使用 300 dpi。", "name" to issue.name, "dpi" to (p["dpi"] ?: ""))
        "imageVeryLowDpi" -> figFmt("“{name}”的有效分辨率仅 {dpi} dpi，低于 150 dpi 会明显像素化。", "name" to issue.name, "dpi" to (p["dpi"] ?: ""))
        "seriesColorsClose" -> figFmt("图表系列颜色“{a}”和“{b}”较难区分。", "a" to (p["a"] ?: ""), "b" to (p["b"] ?: ""))
        "textOverflow" -> figFmt("文字“{name}”可能超出文本框。", "name" to issue.name)
        else -> issue.name
    }
}

@Composable
fun FigureCheckDialog(
    open: Boolean,
    issues: List<FigureCompliance.Issue>,
    counts: FigureCompliance.Counts?,
    busy: Boolean,
    onRun: () -> Unit,
    onExportTiff: () -> Unit,
    onClose: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(I18n.t("投稿检查"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // summary：main 侧 busy / pass / issues 三态
                if (busy) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.width(13.dp).heightIn(max = 13.dp), strokeWidth = 2.dp)
                        Text(I18n.t("运行检查") + "…", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (counts == null || counts.total == 0) {
                    Text(I18n.t("当前检查项未发现问题。"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF7D))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            figFmt("{n} 个问题", "n" to counts.total.toString()),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FigureCheckUi.WARN_COLOR
                        )
                        Text(
                            "${counts.errors} error · ${counts.warns} warn · ${counts.infos} info",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                    , verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    issues.forEach { issue ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when (issue.severity) {
                                        FigureCompliance.Severity.ERROR -> FigureCheckUi.ERROR_COLOR
                                        FigureCompliance.Severity.WARN -> FigureCheckUi.WARN_COLOR
                                        FigureCompliance.Severity.INFO -> FigureCheckUi.INFO_COLOR
                                    }.copy(alpha = 0.10f),
                                    RoundedCornerShape(2.dp)
                                )
                                .padding(start = 6.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                Modifier
                                    .width(3.dp)
                                    .heightIn(min = 14.dp)
                                    .background(
                                        when (issue.severity) {
                                            FigureCompliance.Severity.ERROR -> FigureCheckUi.ERROR_COLOR
                                            FigureCompliance.Severity.WARN -> FigureCheckUi.WARN_COLOR
                                            FigureCompliance.Severity.INFO -> FigureCheckUi.INFO_COLOR
                                        },
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                            Text(figureIssueText(issue), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onRun, enabled = !busy) { Text(I18n.t("运行检查")) }
                TextButton(onClick = onExportTiff, enabled = !busy) { Text(I18n.t("导出 TIFF")) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(I18n.t("关闭")) } },
    )
}
