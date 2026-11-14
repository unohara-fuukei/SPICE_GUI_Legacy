package com.spice.nativeapp.tools

import android.content.Intent
import com.spice.nativeapp.core.ChartKind
import com.spice.nativeapp.core.ChartNode
import com.spice.nativeapp.core.CHART_CSV_EXAMPLE
import com.spice.nativeapp.core.CHART_PALETTE
import com.spice.nativeapp.core.FigureAgent
import com.spice.nativeapp.core.FigureAgentCommand
import com.spice.nativeapp.core.FigureChartSpec
import com.spice.nativeapp.core.ImageTools
import com.spice.nativeapp.core.PptxShape
import com.spice.nativeapp.core.PptxWriter
import com.spice.nativeapp.core.applyChartCsv
import com.spice.nativeapp.core.chartCsv
import com.spice.nativeapp.core.chartSummary
import com.spice.nativeapp.core.createChartNode
import com.spice.nativeapp.core.createFigureId
import com.spice.nativeapp.core.drawChartToCanvas
import com.spice.nativeapp.core.parseChartCsv
import java.io.File

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** Figure 文档空间（px）：与 PNG 导出同尺寸（main 为 A4 px 文档，此处沿用既有 1600×900 口径）。 */
private const val DOC_W = 1600.0
private const val DOC_H = 900.0

/** main installFigureAgent 的 AI 加图尺寸公式（page = 文档）。 */
private fun aiChartWidth(pageW: Double): Double = minOf(360.0, maxOf(220.0, pageW * 0.55))
private fun aiChartHeight(pageH: Double): Double = minOf(260.0, maxOf(170.0, pageH * 0.45))

private sealed class FigItem {
    data class Box(val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color) : FigItem()
    data class Label(val text: String, val x: Float, val y: Float) : FigItem()
    data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : FigItem()
    data class Picture(val bitmap: android.graphics.Bitmap, val x: Float, val y: Float, val w: Float, val h: Float) : FigItem()
}

/** main FigureTool installFigureAgent：把 AI spec 落成默认可编辑节点。 */
private fun chartNodeFromSpec(spec: FigureChartSpec): ChartNode {
    val width = aiChartWidth(DOC_W)
    val height = aiChartHeight(DOC_H)
    val base = createChartNode((DOC_W - width) / 2, (DOC_H - height) / 2, width, height)
    return base.copy(
        name = spec.name,
        chartType = spec.chartType,
        labels = spec.labels,
        series = spec.series.mapIndexed { index, s ->
            com.spice.nativeapp.core.ChartSeries(
                id = createFigureId("series"),
                name = s.name,
                color = s.color ?: CHART_PALETTE[index % CHART_PALETTE.size],
                values = s.values,
                errors = s.errors ?: List(spec.labels.size) { 0.0 },
            )
        },
        xLabel = spec.xLabel ?: "",
        yLabel = spec.yLabel ?: "",
    )
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
    var charts by remember { mutableStateOf(listOf<ChartNode>()) }
    var selectedChartId by remember { mutableStateOf<String?>(null) }
    var labelText by remember { mutableStateOf("新元件") }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    val selectedChart = charts.firstOrNull { it.id == selectedChartId }

    fun updateChart(id: String, transform: (ChartNode) -> ChartNode) {
        charts = charts.map { if (it.id == id) transform(it) else it }
    }

    fun addChart(node: ChartNode, select: Boolean = true) {
        charts = charts + node
        if (select) selectedChartId = node.id
    }

    // main figureAgent.ts：编辑器挂载即注册；卸载反注册，排队命令随注册重放。
    DisposableEffect(Unit) {
        val unregister = FigureAgent.registerFigureCommandHandler { command ->
            when (command) {
                is FigureAgentCommand.AddChart -> addChart(chartNodeFromSpec(command.spec))
            }
        }
        onDispose { unregister() }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("图形工作室（示意图）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("几何元件拼版 + 图表层（柱状/折线/散点，CSV 数据、误差线与显著性括号）。位图导入与 PDF 导出需原版图像引擎。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 文档 1600×900 等比缩放到画布宽。
            val canvasHeight = maxWidth * (DOC_H / DOC_W).toFloat()
            val scale = constraints.maxWidth / DOC_W.toFloat()
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(canvasHeight)
                    .pointerInput(charts) {
                        detectTapGestures { tap ->
                            val docX = tap.x / scale
                            val docY = tap.y / scale
                            val hit = charts.lastOrNull { node ->
                                docX >= node.x && docX <= node.x + node.w && docY >= node.y && docY <= node.y + node.h
                            }
                            selectedChartId = hit?.id
                        }
                    }
            ) {
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
                        is FigItem.Picture -> {
                            val img = it.bitmap.asImageBitmap()
                            drawImage(
                                image = img,
                                srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                                srcSize = androidx.compose.ui.unit.IntSize(it.bitmap.width, it.bitmap.height),
                                dstOffset = androidx.compose.ui.unit.IntOffset((it.x * w).toInt(), (it.y * h).toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize((it.w * w).toInt(), (it.h * h).toInt())
                            )
                        }
                        is FigItem.Label -> drawRect(Color(0xFF7FE7D6), Offset(it.x * w, it.y * h - 2.dp.toPx()), Size(it.text.length * 6f, 2.dp.toPx()))
                    }
                }
                charts.forEach { node ->
                    drawChartToCanvas(node, drawContext.canvas.nativeCanvas, 0f, 0f, scale)
                    if (node.id == selectedChartId) {
                        drawRect(
                            Color(0xFF69C9F0),
                            Offset((node.x * scale).toFloat(), (node.y * scale).toFloat()),
                            Size((node.w * scale).toFloat(), (node.h * scale).toFloat()),
                            style = Stroke(1.dp.toPx())
                        )
                    }
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
        var picMsg by remember { mutableStateOf("") }
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri: android.net.Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bmp = ImageTools.loadScaled(ctx, uri, 1200)
            if (bmp == null) { picMsg = "图片解码失败"; return@rememberLauncherForActivityResult }
            val w = 0.3f
            val h = (w * bmp.height / bmp.width.toFloat()).coerceAtMost(0.6f)
            items = items + FigItem.Picture(bmp, 0.6f, 0.62f, w, h)
            picMsg = "已导入 ${bmp.width}×${bmp.height} 位图"
        }
        OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("导入位图") }
        if (picMsg.isNotBlank()) Text(picMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        // ------------------------------------------------------------- 图表层
        OutlinedButton(
            onClick = {
                addChart(createChartNode((DOC_W - 320.0) / 2, (DOC_H - 220.0) / 2, 320.0, 220.0))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("图表") }

        if (selectedChart != null) {
            ChartInspector(
                chart = selectedChart,
                onChange = { transform -> updateChart(selectedChart.id, transform) },
                onDelete = {
                    charts = charts.filterNot { it.id == selectedChart.id }
                    if (selectedChartId == selectedChart.id) selectedChartId = null
                },
            )
        } else if (charts.isNotEmpty()) {
            Text("点选画布中的图表节点进行编辑（AI 的 figure_add_chart 也会在此生成节点）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton(
            onClick = {
                val desc = (items.map { it ->
                    when (it) {
                        is FigItem.Box -> "BOX ${it.label} @(${"%.2f".format(it.x)},${"%.2f".format(it.y)}) ${"%.2f".format(it.w)}×${"%.2f".format(it.h)}"
                        is FigItem.Arrow -> "ARROW ${"%.2f".format(it.x1)},${"%.2f".format(it.y1)} -> ${"%.2f".format(it.x2)},${"%.2f".format(it.y2)}"
                        is FigItem.Label -> "LABEL ${it.text} @(${"%.2f".format(it.x)},${"%.2f".format(it.y)})"
                        is FigItem.Picture -> "IMAGE @(${"%.2f".format(it.x)},${"%.2f".format(it.y)}) ${"%.2f".format(it.w)}×${"%.2f".format(it.h)}"
                    }
                } + charts.map { "CHART ${it.name} [${chartSummary(it)}] @(${"%.0f".format(it.x)},${"%.0f".format(it.y)}) ${"%.0f".format(it.w)}×${"%.0f".format(it.h)}" })
                    .joinToString("\n")
                clipboard.setText(AnnotatedString(desc))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制示意图描述（可贴到绘图软件）") }

        var pngMsg by remember { mutableStateOf("") }
    var pptxMsg by remember { mutableStateOf("") }
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
                            is FigItem.Picture -> canvas.drawBitmap(
                                item.bitmap,
                                android.graphics.Rect(0, 0, item.bitmap.width, item.bitmap.height),
                                android.graphics.RectF(item.x * w, item.y * h, (item.x + item.w) * w, (item.y + item.h) * h),
                                null
                            )
                            is FigItem.Label -> canvas.drawText(item.text, item.x * w, item.y * h, textPaint)
                            is FigItem.Arrow -> canvas.drawLine(item.x1 * w, item.y1 * h, item.x2 * w, item.y2 * h, stroke.apply { strokeWidth = 6f })
                        }
                    }
                    charts.forEach { node -> drawChartToCanvas(node, canvas, 0f, 0f, 1f) }
                    val dir = java.io.File(ctx.filesDir, "figures").apply { mkdirs() }
                    val f = java.io.File(dir, "figure_${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    pngMsg = "已保存 PNG：${f.name}（${f.length() / 1024} KB）"
                }.onFailure { pngMsg = "导出失败：${it.message}" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("导出 PNG（1600×900）") }

        OutlinedButton(
            onClick = {
                runCatching {
                    val shapes = items.map { item ->
                        when (item) {
                            is FigItem.Box -> PptxShape.Box(item.label, item.x, item.y, item.w, item.h,
                                Integer.toHexString(item.color.toArgb()).takeLast(6).uppercase())
                            is FigItem.Label -> PptxShape.Label(item.text, item.x, item.y)
                            is FigItem.Arrow -> PptxShape.Arrow(item.x1, item.y1, item.x2, item.y2)
                            is FigItem.Picture -> {
                                val bos = java.io.ByteArrayOutputStream()
                                item.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                                PptxShape.Image(bos.toByteArray(), item.x, item.y, item.w, item.h)
                            }
                        }
                    } + charts.map { node ->
                        // main pptx.ts：chart 节点栅格化嵌入（PowerPoint 无原生图表面板）。
                        val bmp = android.graphics.Bitmap.createBitmap(
                            node.w.toInt().coerceAtLeast(8), node.h.toInt().coerceAtLeast(8),
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        drawChartToCanvas(node, android.graphics.Canvas(bmp), (-node.x).toFloat(), (-node.y).toFloat(), 1f)
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                        PptxShape.Image(bos.toByteArray(), (node.x / DOC_W).toFloat(), (node.y / DOC_H).toFloat(),
                            (node.w / DOC_W).toFloat(), (node.h / DOC_H).toFloat())
                    }
                    val dir = File(ctx.filesDir, "figures").apply { mkdirs() }
                    val f = File(dir, "figure_${System.currentTimeMillis()}.pptx")
                    f.outputStream().use { PptxWriter.write(shapes, it) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(view, "打开 PPTX"))
                    pptxMsg = "已导出 PPTX：${f.name}（${f.length() / 1024} KB）"
                }.onFailure { pptxMsg = "PPTX 导出失败：${it.message}" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("导出 PPTX") }
        if (pptxMsg.isNotBlank()) Text(pptxMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        if (pngMsg.isNotBlank()) Text(pngMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        Text("当前元件 ${items.size + charts.size} 个", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- 图表检查器

private var chartCsvDraft by mutableStateOf("")
private var chartCsvError by mutableStateOf(false)

/** main FigureInspector.svelte 图表 tab（CSV 编辑/示例/轴与开关/显著性括号）。 */
@Composable
private fun ChartInspector(
    chart: ChartNode,
    onChange: (transform: (ChartNode) -> ChartNode) -> Unit,
    onDelete: () -> Unit,
) {
    // 选中对象变化时同步 CSV 草稿（main selectedChart 切换即重置 draft）。
    LaunchedEffect(chart.id) {
        chartCsvDraft = chartCsv(chart)
        chartCsvError = false
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("图表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(chartSummary(chart), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 图表类型
        Text("图表类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "柱状图" to ChartKind.BAR,
                "折线图" to ChartKind.LINE,
                "散点图" to ChartKind.SCATTER,
            ).forEach { (label, kind) ->
                FilterChip(
                    selected = chart.chartType == kind,
                    onClick = { onChange { it.copy(chartType = kind) } },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }

        // 数据（CSV）
        OutlinedTextField(
            value = chartCsvDraft,
            onValueChange = { text ->
                // main applyChartText：解析失败保留原文并置错误，不覆写节点。
                chartCsvDraft = text
                val data = parseChartCsv(text)
                if (data == null) {
                    chartCsvError = true
                } else {
                    chartCsvError = false
                    onChange { applyChartCsv(it, data) }
                }
            },
            label = { Text("数据（CSV）", fontSize = 12.sp) },
            supportingText = { Text("第一列为分类；表头以 err 结尾的列作为误差线。", fontSize = 10.sp) },
            isError = chartCsvError,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        if (chartCsvError) Text("无法解析图表数据", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            val data = parseChartCsv(CHART_CSV_EXAMPLE) ?: return@OutlinedButton
            chartCsvDraft = CHART_CSV_EXAMPLE
            chartCsvError = false
            onChange { applyChartCsv(it, data) }
        }) { Text("载入示例") }

        // 轴标题
        OutlinedTextField(
            value = chart.xLabel,
            onValueChange = { text -> onChange { it.copy(xLabel = text) } },
            label = { Text("X 轴标题", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = chart.yLabel,
            onValueChange = { text -> onChange { it.copy(yLabel = text) } },
            label = { Text("Y 轴标题", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Y 轴范围（空 = 自动）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Y 轴范围", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onChange { it.copy(yMin = null, yMax = null) } }) { Text("自动", fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = chart.yMin?.let { "%.4g".format(it) } ?: "",
                onValueChange = { text ->
                    val v = text.trim().toDoubleOrNull()
                    onChange { it.copy(yMin = v) }
                },
                label = { Text("min", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = chart.yMax?.let { "%.4g".format(it) } ?: "",
                onValueChange = { text ->
                    val v = text.trim().toDoubleOrNull()
                    onChange { it.copy(yMax = v) }
                },
                label = { Text("max", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        // 显示开关
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ("图例" to chart.showLegend) to { n: ChartNode -> n.copy(showLegend = !n.showLegend) },
                ("网格" to chart.showGrid) to { n: ChartNode -> n.copy(showGrid = !n.showGrid) },
                ("数值" to chart.showValues) to { n: ChartNode -> n.copy(showValues = !n.showValues) },
                ("误差线" to chart.showErrorBars) to { n: ChartNode -> n.copy(showErrorBars = !n.showErrorBars) },
            ).forEach { (state, transform) ->
                val (label, selected) = state
                FilterChip(
                    selected = selected,
                    onClick = { onChange(transform) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }

        // 显著性括号
        Text("显著性", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        chart.significance.forEachIndexed { index, sig ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("从", fontSize = 11.sp)
                Text("${sig.from}", fontSize = 11.sp)
                OutlinedButton(onClick = { stepIndex(chart, index, 'f', -1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−", fontSize = 12.sp) }
                OutlinedButton(onClick = { stepIndex(chart, index, 'f', 1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+", fontSize = 12.sp) }
                Text("到", fontSize = 11.sp)
                Text("${sig.to}", fontSize = 11.sp)
                OutlinedButton(onClick = { stepIndex(chart, index, 't', -1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−", fontSize = 12.sp) }
                OutlinedButton(onClick = { stepIndex(chart, index, 't', 1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+", fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = sig.label,
                    onValueChange = { text -> onChange { n -> n.copy(significance = n.significance.mapIndexed { i, s -> if (i == index) s.copy(label = text) else s }) } },
                    label = { Text("标记", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = { onChange { n -> n.copy(significance = n.significance.filterIndexed { i, _ -> i != index }) } }) { Text("移除", fontSize = 11.sp) }
            }
        }
        OutlinedButton(onClick = {
            // main addSignificance：from=0,to=min(1,len-1)，label='*'。
            if (chart.labels.size >= 2) {
                onChange { n ->
                    n.copy(significance = n.significance + ChartSigStub(n))
                }
            }
        }) { Text("添加显著性括号") }

        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("删除图表") }
    }
}

private fun ChartSigStub(chart: ChartNode): com.spice.nativeapp.core.ChartSignificance =
    com.spice.nativeapp.core.ChartSignificance(
        id = createFigureId("sig"),
        from = 0,
        to = minOf(1, chart.labels.size - 1),
        label = "*",
    )

/** 从/到 索引步进（main changeSignificanceIndex：clamp + 另一端联动）。 */
private fun stepIndex(
    chart: ChartNode,
    sigIndex: Int,
    edge: Char,
    delta: Int,
    onChange: (transform: (ChartNode) -> ChartNode) -> Unit,
) {
    val maxIndex = (chart.labels.size - 1).coerceAtLeast(0)
    onChange { n ->
        n.copy(significance = n.significance.mapIndexed { i, s ->
            if (i != sigIndex) s
            else {
                if (edge == 'f') {
                    val from = (s.from + delta).coerceIn(0, maxIndex)
                    if (s.to < from) s.copy(from = from, to = from) else s.copy(from = from)
                } else {
                    val to = (s.to + delta).coerceIn(0, maxIndex)
                    if (s.from > to) s.copy(to = to, from = to) else s.copy(to = to)
                }
            }
        })
    }
}
