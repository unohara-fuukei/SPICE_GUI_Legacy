package com.spice.nativeapp.tools.gel

/*
 * GelScreen 的 UI 基建：pix-btn/pix-select/pix-input/details 等原版控件的 Compose 等价物，
 * 以及源图画布（SourceStage）与批量面板（GelBatchPanel）。
 * 文案全部来自 main 的 gel 词条 zh 原文（I18n.t 直接命中 core 词典）。
 */
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal fun gt(zh: String): String = I18n.t(zh)

/** 命名占位符插值（main 用 {n}/{ok}…，I18n.tf 只支持 {v1}..{v9}）。 */
internal fun gi(template: String, vararg pairs: Pair<String, Any?>): String {
    var out = I18n.t(template)
    pairs.forEach { (k, v) -> out = out.replace("{$k}", v?.toString() ?: "") }
    return out
}

// ----------------------------------------------------------------- widgets

/** 原版 details（可折叠分区）。 */
@Composable
internal fun GelSection(title: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "▾ " else "▸ ",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) { content() }
        }
    }
}

/** 原版 .pix-btn。 */
@Composable
internal fun PixButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    ok: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val color = when {
        danger -> colorScheme.error
        ok || active -> Color(0xFF69E77A)
        accent -> colorScheme.primary
        else -> colorScheme.onSurface
    }
    val borderColor = if (active) Color(0xFF4CD6FF) else if (ok) Color(0xFF69E77A) else if (accent) colorScheme.primary else colorScheme.outline
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.border(
            1.dp,
            if (enabled) borderColor else colorScheme.outline.copy(alpha = 0.4f),
            androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (enabled) color else colorScheme.onSurface.copy(alpha = 0.4f),
        ),
    ) {
        Text(label, fontSize = 9.sp, maxLines = 1)
    }
}

/** 原版 .link（泳道名/样式/最近项目）。 */
@Composable
internal fun PixLink(label: String, bold: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier = Modifier.wrapContentWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4CD6FF)),
    ) {
        Text(label, fontSize = 9.sp, maxLines = 1, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

/** 原版 label.check。 */
@Composable
internal fun PixCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 8.5.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** 原版 .pix-select（下拉；选项为 value→label）。 */
@Composable
internal fun PixSelect(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value
    Box {
        PixButton("$current ▾", onClick = { open = true })
        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { open = false }) { Text(gt("取消")) } },
                title = {},
                text = {
                    Column {
                        options.forEach { (v, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onChange(v)
                                    open = false
                                }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = v == value, onClick = null)
                                Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                },
            )
        }
    }
}

/** 原版 .pix-input（单行文本）。 */
@Composable
internal fun PixTextField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 原版 input.num（数字输入，onchange 提交）。 */
@Composable
internal fun PixNumberField(value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Box(
        Modifier
            .width(56.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // Compose 无 change/blur 区分：失焦语义近似为每次输入即提交（数值场）。
    LaunchedEffect(text) { onCommit(text) }
}

/** 原版 label.row（滑杆 + 输出值）。steps 为区间隔数-1。 */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    steps: Int,
    enabled: Boolean = true,
    onValue: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Slider(
            value = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f),
            onValueChange = { f -> onValue(min + (max - min) * f.toDouble()) },
            valueRange = 0f..1f,
            steps = if (steps > 0) steps else 0,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

// ------------------------------------------------------------- source stage

/**
 * 源图画布：显示处理后的凝胶图 + 叠加层（网格/裁剪框/泳道线/条带点/抓手），
 * 手势（onSourcePointerDown/Move/Up 的 1:1 等价物）：
 *  - 裁剪边 7px 内拖动裁剪；
 *  - 条带点 y±5px 内拖动条带；
 *  - 最近泳道 x±9px 内拖动泳道（联动手柄重排）。
 */
@Composable
internal fun SourceStage(
    processed: ProcessedImage,
    project: GelProject,
    selectedLaneId: String?,
    selectedBand: Pair<String, Int>?,
    showGrid: Boolean,
    sourceZoom: Double,
    uiRev: Int,
    onDown: (cssX: Float, cssY: Float, cssW: Float, cssH: Float) -> Boolean,
    onMove: (nx: Double, ny: Double) -> Unit,
    onUp: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraintsCompat { availW, availH ->
        val fit = min(
            (availW - 12f) / processed.width.toFloat(),
            (availH - 12f) / processed.height.toFloat(),
        ).coerceAtMost(1f)
        val scale = fit * sourceZoom.toFloat()
        val cssW = processed.width * scale
        val cssH = processed.height * scale
        val bmp = processed.bitmap.asImageBitmap()
        Canvas(
            Modifier
                .size(cssW.dp, cssH.dp)
                .pointerInput(cssW, cssH, uiRev) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val d = density.density
                        val cssX = down.position.x / d
                        val cssY = down.position.y / d
                        if (!onDown(cssX, cssY, cssW.toFloat(), cssH.toFloat())) return@awaitEachGesture
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val cx = change.position.x / d
                            val cy = change.position.y / d
                            if (!change.pressed) {
                                onUp()
                                change.consume()
                                break
                            }
                            onMove((cx / cssW).toDouble(), (cy / cssH).toDouble())
                            change.consume()
                        }
                    }
                },
        ) {
            val pxW = (cssW * density.density).toInt()
            val pxH = (cssH * density.density).toInt()
            drawImage(bmp, srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height), dstSize = androidx.compose.ui.unit.IntSize(pxW, pxH))
            val c = project.crop
            val left = (c.left * cssW).toFloat()
            val right = ((1 - c.right) * cssW).toFloat()
            val top = (c.top * cssH).toFloat()
            val bottom = ((1 - c.bottom) * cssH).toFloat()
            // 网格 rgba(255,159,28,0.25)
            if (showGrid) {
                val grid = Color(0x40FF9F1C)
                for (i in 1..9) {
                    drawLine(grid, Offset(i * cssW / 10, 0f), Offset(i * cssW / 10, cssH), 1f)
                    drawLine(grid, Offset(0f, i * cssH / 10), Offset(cssW, i * cssH / 10), 1f)
                }
            }
            // 裁剪暗遮罩 rgba(0,0,0,0.42) + 橙框 #ff9f1c
            val dark = Color(0x00000000).copy(alpha = 0.42f)
            drawRect(dark, size = androidx.compose.ui.geometry.Size(left, size.height))
            drawRect(dark, topLeft = Offset(right, 0f), size = androidx.compose.ui.geometry.Size(cssW - right, size.height))
            drawRect(dark, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(right - left, top))
            drawRect(dark, topLeft = Offset(left, bottom), size = androidx.compose.ui.geometry.Size(right - left, cssH - bottom))
            val orange = Color(0xFFFF9F1C)
            drawRect(
                orange, topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(1.5.dp.toPx()),
            )
            // 泳道
            val nc = drawContext.canvas.nativeCanvas
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFB8CCD9.toInt()
                textSize = 10f * density.density
                isFakeBoldText = false
                typeface = android.graphics.Typeface.MONOSPACE
            }
            for (lane in project.lanes) {
                val x = (lane.x * cssW).toFloat()
                val color = when {
                    lane.removed == true -> Color(0xE6FF5050)
                    lane.id == selectedLaneId -> orange
                    else -> Color(0xBF4CD6FF)
                }
                val dashEffect = if (!lane.isMarker) androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(5f * density.density, 3f * density.density),
                ) else null
                drawLine(
                    color = color,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = if (lane.id == selectedLaneId) 2f else 1f,
                    pathEffect = dashEffect,
                )
                nc.drawText(lane.name, x + 3f * density.density, top + 11f * density.density, labelPaint)
                // 条带点
                lane.bands.forEachIndexed { index, band ->
                    val y = (band.y * cssH).toFloat()
                    val r = if (selectedBand == lane.id to index) 4f else 3f
                    val bandColor = if (band.showSize == true) Color(0xF2FF9F1C) else Color(0xD96BE77A)
                    drawCircle(bandColor, r * density.density, Offset(x, y))
                }
            }
        }
    }
}

/** BoxWithConstraints 的薄封装（传出可用宽高 dp 值，占满可用区域）。 */
@Composable
private fun BoxWithConstraintsCompat(content: @Composable (availW: Float, availH: Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        content(maxWidth.value, maxHeight.value)
    }
}

// -------------------------------------------------------------- batch panel

/** GelBatch.svelte 的 1:1 移植（协程承载 worker）。 */
@Composable
internal fun GelBatchPanel(customLadders: List<LadderDef>, exportDpi: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<GelBatch.BatchFile>>(emptyList()) }
    var ladderId by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var warning by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var result by remember { mutableStateOf<Pair<Int, Int>?>(null) } // ok to failed
    var resultPath by remember { mutableStateOf<String?>(null) }

    fun toast(kind: Int, msg: String) = android.widget.Toast.makeText(ctx, msg, kind).show()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.map { GelBatch.BatchFile(it, uriName(ctx, it), uriType(ctx, it)) }
            .filter { GelBatch.isImageFile(it.mime, it.name) }
        if (picked.isEmpty()) {
            warning = gt("未选择图像。")
            return@rememberLauncherForActivityResult
        }
        warning = if (picked.size > GelBatch.MAX_BATCH_IMAGES) gi("每批最多 {n} 张图像，多余文件将被跳过。", "n" to GelBatch.MAX_BATCH_IMAGES) else ""
        files = picked.take(GelBatch.MAX_BATCH_IMAGES)
        errors = emptyList()
        result = null
        resultPath = null
    }

    fun run() {
        if (files.isEmpty() || running) return
        running = true
        errors = emptyList()
        result = null
        current = 0
        total = files.size
        scope.launch {
            try {
                val selected = files
                val detect = GelStorage.detectConfigForNewImport(ctx)
                val batch = withContext(Dispatchers.Default) {
                    GelBatch.buildGelBatch(
                        ctx, selected,
                        GelBatch.GelBatchOptions(
                            ladderId = ladderId,
                            dpi = exportDpi,
                            detect = detect,
                            onProgress = { c, t, _ ->
                                scope.let {
                                    current = c
                                    total = t
                                }
                            },
                        ),
                    )
                }
                val stamp = java.time.Instant.now().toString().take(19).replace("[:T]".toRegex(), "-")
                val fileName = "gel-batch-$stamp.zip"
                val zip = GelBatch.zipToBytes(batch)
                val dir = File(ctx.filesDir, "gel-exports").apply { mkdirs() }
                val out = File(dir, fileName)
                withContext(Dispatchers.IO) { out.writeBytes(zip) }
                result = batch.ok to batch.failed
                resultPath = out.absolutePath
                errors = batch.errors
                when {
                    batch.failed > 0 -> toast(
                        android.widget.Toast.LENGTH_LONG,
                        gi("批量处理出现错误：成功 {ok} 张，失败 {failed} 张。", "ok" to batch.ok, "failed" to batch.failed),
                    )
                    else -> toast(
                        android.widget.Toast.LENGTH_SHORT,
                        gi("批量已保存：成功 {ok} 张，失败 {failed} 张。", "ok" to batch.ok, "failed" to batch.failed),
                    )
                }
            } catch (err: Throwable) {
                val message = err.message ?: err.toString()
                toast(android.widget.Toast.LENGTH_LONG, message)
                errors = listOf("" to message)
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(gt("对多张凝胶图像运行自动处理流程，并为每张图像保存包含图像 PNG、项目 JSON 和汇总 CSV 的 ZIP。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PixButton(gt("选择图像"), enabled = !running) { picker.launch(arrayOf("image/*", "application/octet-stream")) }
            if (files.isNotEmpty()) {
                PixButton(gt("清空列表"), danger = true, enabled = !running) {
                    files = emptyList(); errors = emptyList(); result = null; warning = ""; current = 0; total = 0
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gt("分子量标记"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val all = GelLadders.allLadders(customLadders)
            val options = listOf("" to gt("不拟合标记")) +
                all.filter { it.kind == "dna" }.map { it.id to it.name } +
                all.filter { it.kind == "protein" }.map { it.id to it.name }
            PixSelect(options, ladderId) { ladderId = it }
        }
        Text(gt("批量处理使用已保存的默认检测预设。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (warning.isNotEmpty()) Text(warning, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.primary)
        Text(
            if (files.isNotEmpty()) gi("{n} 张图像", "n" to files.size) else gt("选择图像或文件夹开始。"),
            fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        files.take(6).forEach { Text(it.name, fontSize = 8.5.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (files.size > 6) Text("+${files.size - 6}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PixButton(gt("运行批量处理"), ok = true, enabled = files.isNotEmpty() && !running) { run() }
        if (running) {
            val progress = if (total > 0) (current * 100 / total) else 0
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(gi("正在处理 {current} / {total}", "current" to current, "total" to total), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result != null && !running) {
            val (ok, failed) = result!!
            Text(
                if (failed > 0) gi("批量处理出现错误：成功 {ok} 张，失败 {failed} 张。", "ok" to ok, "failed" to failed)
                else gi("批量已保存：成功 {ok} 张，失败 {failed} 张。", "ok" to ok, "failed" to failed),
                fontSize = 8.5.sp, color = if (failed > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            resultPath?.let { Text(it, fontSize = 8.sp, color = Color(0xFF69E77A)) }
        }
        if (errors.isNotEmpty()) {
            errors.take(8).forEach { (name, error) ->
                Text("$name $error", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            if (errors.size > 8) Text("+${errors.size - 8}", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(gt("每张图像生成图像 PNG、项目 JSON 和一行汇总 CSV。"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun uriName(ctx: android.content.Context, uri: android.net.Uri): String {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "image"
        } ?: "image"
    } catch (e: Exception) {
        "image"
    }
}

private fun uriType(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    ctx.contentResolver.getType(uri)
} catch (e: Exception) {
    null
}
