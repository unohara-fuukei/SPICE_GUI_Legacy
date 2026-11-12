package com.spice.nativeapp.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private sealed class FigItem {
    data class Box(val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color) : FigItem()
    data class Label(val text: String, val x: Float, val y: Float) : FigItem()
    data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : FigItem()
}

@Composable
fun FigureScreen(modifier: Modifier = Modifier) {
    var items by remember {
        mutableStateOf(
            listOf<FigItem>(
                FigItem.Box("启动子", 0.08f, 0.16f, 0.22f, 0.14f, Color(0xFFFFC56E)),
                FigItem.Box("CDS", 0.34f, 0.16f, 0.34f, 0.14f, Color(0xFF7FE7D6)),
                FigItem.Box("终止子", 0.72f, 0.16f, 0.14f, 0.14f, Color(0xFF9FA8FF)),
                FigItem.Arrow(0.14f, 0.42f, 0.82f, 0.42f),
                FigItem.Label("表达盒示意", 0.08f, 0.52f)
            )
        )
    }
    var labelText by remember { mutableStateOf("新元件") }
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("图形工作室（示意图）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("几何元件拼版：盒子/标签/箭头。位图导入与 PDF 导出需原版图像引擎，这里产出矢量示意图描述。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Canvas(Modifier.fillMaxWidth().height(300.dp)) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFF0B0F17), Offset(0f, 0f), Size(w, h))
            items.forEach { it ->
                when (it) {
                    is FigItem.Box -> {
                        val tl = Offset(it.x * w, it.y * h)
                        val sz = Size(it.w * w, it.h * h)
                        drawRect(it.color.copy(alpha = 0.85f), tl, sz)
                        drawRect(it.color, tl, sz, style = Stroke(1.dp.toPx()))
                    }
                    is FigItem.Arrow -> drawLine(Color(0xFF9FA8FF), Offset(it.x1 * w, it.y1 * h), Offset(it.x2 * w, it.y2 * h), 2.dp.toPx())
                    is FigItem.Label -> drawRect(Color(0xFF7FE7D6), Offset(it.x * w, it.y * h - 2.dp.toPx()), Size(it.text.length * 6f, 2.dp.toPx()))
                }
            }
        }

        OutlinedTextField(
            value = labelText,
            onValueChange = { labelText = it },
            label = { Text("新增元件名称", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val y = 0.16f + (items.count { it is FigItem.Box }) * 0.18f
                    if (y < 0.85f) items = items + FigItem.Box(labelText.ifBlank { "元件" }, 0.1f, y, 0.26f, 0.14f, Color(0xFF69C9F0))
                },
                modifier = Modifier.weight(1f)
            ) { Text("加方框") }
            Button(
                onClick = {
                    val y = 0.6f + (items.count { it is FigItem.Label }) * 0.1f
                    if (y < 0.95f) items = items + FigItem.Label(labelText.ifBlank { "标注" }, 0.08f, y)
                },
                modifier = Modifier.weight(1f)
            ) { Text("加标签") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { items = items + FigItem.Arrow(0.2f, 0.5f, 0.8f, 0.5f) },
                modifier = Modifier.weight(1f)
            ) { Text("加箭头") }
            OutlinedButton(
                onClick = { items = emptyList() },
                modifier = Modifier.weight(1f)
            ) { Text("清空") }
        }
        OutlinedButton(
            onClick = {
                val desc = items.joinToString("\n") { it ->
                    when (it) {
                        is FigItem.Box -> "BOX ${it.label} @(${"%.2f".format(it.x)},${"%.2f".format(it.y)}) ${"%.2f".format(it.w)}×${"%.2f".format(it.h)}"
                        is FigItem.Arrow -> "ARROW ${"%.2f".format(it.x1)},${"%.2f".format(it.y1)} -> ${"%.2f".format(it.x2)},${"%.2f".format(it.y2)}"
                        is FigItem.Label -> "LABEL ${it.text} @(${"%.2f".format(it.x)},${"%.2f".format(it.y)})"
                    }
                }
                clipboard.setText(AnnotatedString(desc))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制示意图描述（可贴到绘图软件）") }

        val ctx = LocalContext.current
        var pngMsg by remember { mutableStateOf("") }
        OutlinedButton(
            onClick = {
                runCatching {
                    val w = 1600
                    val h = 900
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.rgb(11, 15, 23))
                    val boxPaint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL }
                    val stroke = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.WHITE
                    }
                    val textPaint = android.graphics.Paint().apply {
                        textSize = 34f; color = android.graphics.Color.WHITE; isAntiAlias = true
                    }
                    items.forEach { item ->
                        when (item) {
                            is FigItem.Box -> {
                                boxPaint.color = item.color.toArgb()
                                canvas.drawRect(item.x * w, item.y * h, (item.x + item.w) * w, (item.y + item.h) * h, boxPaint)
                                canvas.drawRect(item.x * w, item.y * h, (item.x + item.w) * w, (item.y + item.h) * h, stroke)
                                canvas.drawText(item.label, item.x * w + 16f, item.y * h + item.h * h / 2f + 12f, textPaint.apply { color = android.graphics.Color.BLACK; textSize = 28f })
                                textPaint.textSize = 34f
                            }
                            is FigItem.Label -> canvas.drawText(item.text, item.x * w, item.y * h, textPaint)
                            is FigItem.Arrow -> canvas.drawLine(item.x1 * w, item.y1 * h, item.x2 * w, item.y2 * h, stroke.apply { strokeWidth = 6f })
                        }
                    }
                    val dir = java.io.File(ctx.filesDir, "figures").apply { mkdirs() }
                    val f = java.io.File(dir, "figure_${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    pngMsg = "已保存 PNG：${f.name}（${f.length() / 1024} KB）"
                }.onFailure { pngMsg = "导出失败：${it.message}" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("导出 PNG（1600×900）") }
        if (pngMsg.isNotBlank()) Text(pngMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        Text("当前元件 ${items.size} 个", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
