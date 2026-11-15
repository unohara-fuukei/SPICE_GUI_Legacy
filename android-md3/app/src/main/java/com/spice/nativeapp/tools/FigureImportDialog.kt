package com.spice.nativeapp.tools

// FigureImportDialog.kt —— main src/lib/tools/figure/FigureImportDialog.svelte 1:1 移植（文案层）。
//
// 形态差异（README 披露）：main 为 pix 风格自绘模态（overlay+dialog+tabs），安卓侧用
// MD3 AlertDialog 等价结构：双 tab（Mermaid/SVG）、粘贴区、文件选择、错误/警告行、
// 导入方式（插入当前画布/新建画布）、关闭/导入按钮。交互与文案与 main 一致。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.FigureImportVector
import com.spice.nativeapp.core.FigureMermaidImport
import com.spice.nativeapp.core.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FigureImportDialog(
    open: Boolean,
    onClose: () -> Unit,
    onImport: (result: FigureImportVector.VectorImportResult, mode: String) -> Unit,
) {
    if (!open) return

    var tab by remember { mutableStateOf("mermaid") } // mermaid | svg
    var mermaidText by remember { mutableStateOf(TextFieldValue("")) }
    var svgText by remember { mutableStateOf(TextFieldValue("")) }
    var mode by remember { mutableStateOf("insert") } // insert | new
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var warningCount by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    fun close() {
        if (busy) return
        onClose()
    }

    fun selectTab(next: String) {
        if (busy) return
        tab = next
        error = ""
        warningCount = null
    }

    fun loadExample() {
        if (busy) return
        mermaidText = TextFieldValue(FigureMermaidImport.MERMAID_EXAMPLE)
        tab = "mermaid"
        error = ""
        warningCount = null
    }

    fun pasteClipboard() {
        if (busy) return
        error = ""
        val text = clipboard.getText()?.text ?: ""
        if (text.isBlank()) {
            error = I18n.t("请粘贴 Mermaid 代码或选择 SVG 文件。")
            return
        }
        mermaidText = TextFieldValue(text)
        tab = "mermaid"
        warningCount = null
    }

    fun runImport() {
        if (busy) return
        val source = if (tab == "mermaid") mermaidText.text else svgText.text
        if (source.isBlank()) {
            error = I18n.t("请粘贴 Mermaid 代码或选择 SVG 文件。")
            warningCount = null
            return
        }
        busy = true
        error = ""
        warningCount = null
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    if (tab == "mermaid") {
                        FigureMermaidImport.importMermaid(source)
                    } else {
                        FigureImportVector.importSvgMarkup(source)
                    }
                }
            }
            busy = false
            val r = outcome.getOrNull()
            if (r == null) {
                // main: thrown.message ? thrown.message : figImportFailed()
                val message = outcome.exceptionOrNull()?.message
                error = if (!message.isNullOrEmpty()) message else I18n.t("无法导入该图形")
            } else {
                warningCount = r.warnings.size
                onImport(r, mode)
                onClose()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(I18n.t("导入矢量图"), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // ------------------------------------------------ tabs
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = tab == "mermaid",
                        onClick = { selectTab("mermaid") },
                        enabled = !busy,
                        label = { Text(I18n.t("Mermaid"), fontSize = 11.sp) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = tab == "svg",
                        onClick = { selectTab("svg") },
                        enabled = !busy,
                        label = { Text(I18n.t("SVG"), fontSize = 11.sp) },
                    )
                }

                if (tab == "mermaid") {
                    Text(
                        I18n.t("粘贴 Mermaid flowchart / graph 代码。其他图表类型会作为矢量图片导入。"),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = mermaidText,
                        onValueChange = { if (!busy) mermaidText = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        enabled = !busy,
                        placeholder = { Text(I18n.t("粘贴 Mermaid flowchart / graph 代码。其他图表类型会作为矢量图片导入。"), fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { loadExample() }, enabled = !busy) {
                            Text(I18n.t("载入示例"), fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { pasteClipboard() }, enabled = !busy) {
                            Text(I18n.t("从剪贴板粘贴"), fontSize = 11.sp)
                        }
                    }
                } else {
                    Text(
                        I18n.t("选择 SVG 文件，或直接粘贴 SVG 源码。"),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = svgText,
                        onValueChange = { if (!busy) svgText = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        enabled = !busy,
                        placeholder = { Text("<svg …>…</svg>", fontSize = 10.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                    )
                }

                if (error.isNotEmpty()) {
                    Text(error, fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFFFF5D5D))
                }
                val wc = warningCount
                if (wc != null && wc > 0) {
                    // figImportWarnings: 有 {n} 个元素被跳过
                    Text(figFmt("有 {n} 个元素被跳过", "n" to wc.toString()), fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFFF2B84B))
                }

                // ------------------------------------------------ 导入方式
                Text(I18n.t("导入方式"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = mode == "insert",
                        onClick = { mode = "insert" },
                        enabled = !busy,
                        label = { Text(I18n.t("插入当前画布"), fontSize = 11.sp) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = mode == "new",
                        onClick = { mode = "new" },
                        enabled = !busy,
                        label = { Text(I18n.t("新建画布"), fontSize = 11.sp) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { runImport() }, enabled = !busy) {
                Text(if (busy) I18n.t("正在导入…") else I18n.t("导入"), fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }, enabled = !busy) {
                Text(I18n.t("关闭"), fontSize = 12.sp)
            }
        },
    )
}
