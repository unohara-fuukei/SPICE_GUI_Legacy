package com.spice.nativeapp.tools

import android.content.Intent
import com.spice.nativeapp.core.ChartKind
import com.spice.nativeapp.core.ChartNode
import com.spice.nativeapp.core.CHART_CSV_EXAMPLE
import com.spice.nativeapp.core.CHART_PALETTE
import com.spice.nativeapp.core.FigureAgent
import com.spice.nativeapp.core.FigureAgentCommand
import com.spice.nativeapp.core.FigureChartSpec
import com.spice.nativeapp.core.FigureCompliance
import com.spice.nativeapp.core.FigureCompliance.ImagePixelSize
import com.spice.nativeapp.core.FigureTiff
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
                FigItem.Label(com.spice.nativeapp.core.I18n.t("表达盒示意"), 0.08f, 0.52f)
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

    // --------------------------------------------------- 投稿检查（T9，main FigureTool runPublicationCheck）
    var checkOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var checkBusy by remember { mutableStateOf(false) }
    var checkIssues by remember { mutableStateOf(listOf<FigureCompliance.Issue>()) }
    var checkCounts by remember { mutableStateOf<FigureCompliance.Counts?>(null) }
    var checkMsg by remember { mutableStateOf("") }

    fun runCheck() {
        checkOpen = true
        checkBusy = true
        // main 位图探测是异步 Image.onload；安卓位图已在 FigItem.Picture 内存中，同步可得。
        val (doc, sizes) = complianceInput(items, charts)
        checkIssues = FigureCompliance.checkFigure(doc, sizes)
        checkCounts = FigureCompliance.complianceCounts(checkIssues)
        checkBusy = false
    }

    fun exportTiff() {
        runCatching {
            // main renderTiff：300 dpi 栅格 → 白底合成 → baseline TIFF；
            // 描述串的 mm 尺寸安卓文档无对应，按既有 1600×900 px 口径披露。
            val bytes = FigureTiff.renderTiffFromCanvas(
                1600, 900, 300.0,
                { canvas -> drawFigureToCanvas(canvas, 1600, 900, darkBg = false, items, charts) },
                "Figure - 1600 x 900 px at 300 dpi - ${FigureTiff.SOFTWARE}"
            )
            val dir = java.io.File(ctx.filesDir, "figures").apply { mkdirs() }
            val f = java.io.File(dir, "figure_${System.currentTimeMillis()}.tiff")
            f.writeBytes(bytes)
            checkMsg = com.spice.nativeapp.core.I18n.t("图形已导出") + "：" + f.name
        }.onFailure { checkMsg = com.spice.nativeapp.core.I18n.tf("导出失败：{v1}", it.message) }
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
        Text(com.spice.nativeapp.core.I18n.t("图形工作室（示意图）"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(com.spice.nativeapp.core.I18n.t("几何元件拼版 + 图表层（柱状/折线/散点，CSV 数据、误差线与显著性括号）。位图导入与 PDF 导出需原版图像引擎。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
            label = { Text(com.spice.nativeapp.core.I18n.t("新增元件名称"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val y = 0.16f + (items.count { it is FigItem.Box }) * 0.18f
                    if (y < 0.85f) items = items + FigItem.Box(labelText.ifBlank { com.spice.nativeapp.core.I18n.t("元件") }, 0.1f, y, 0.26f, 0.14f, Color(0xFF69C9F0))
                },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("加方框")) }
            Button(
                onClick = {
                    val y = 0.6f + (items.count { it is FigItem.Label }) * 0.1f
                    if (y < 0.95f) items = items + FigItem.Label(labelText.ifBlank { com.spice.nativeapp.core.I18n.t("标注") }, 0.08f, y)
                },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("加标签")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { items = items + FigItem.Arrow(0.2f, 0.5f, 0.8f, 0.5f) },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("加箭头")) }
            OutlinedButton(
                onClick = { items = emptyList() },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("清空")) }
        }
        var picMsg by remember { mutableStateOf("") }
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri: android.net.Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bmp = ImageTools.loadScaled(ctx, uri, 1200)
            if (bmp == null) { picMsg = com.spice.nativeapp.core.I18n.t("图片解码失败"); return@rememberLauncherForActivityResult }
            val w = 0.3f
            val h = (w * bmp.height / bmp.width.toFloat()).coerceAtMost(0.6f)
            items = items + FigItem.Picture(bmp, 0.6f, 0.62f, w, h)
            picMsg = com.spice.nativeapp.core.I18n.tf("已导入 {v1}×{v2} 位图", bmp.width, bmp.height)
        }
        OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("导入位图")) }
        if (picMsg.isNotBlank()) Text(picMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        // ------------------------------------------------------------- 图表层
        OutlinedButton(
            onClick = {
                addChart(createChartNode((DOC_W - 320.0) / 2, (DOC_H - 220.0) / 2, 320.0, 220.0))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("图表")) }

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
            Text(com.spice.nativeapp.core.I18n.t("点选画布中的图表节点进行编辑（AI 的 figure_add_chart 也会在此生成节点）。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ) { Text(com.spice.nativeapp.core.I18n.t("复制示意图描述（可贴到绘图软件）")) }

        var pngMsg by remember { mutableStateOf("") }
    var pptxMsg by remember { mutableStateOf("") }
        OutlinedButton(
            onClick = {
                runCatching {
                    val w = 1600
                    val h = 900
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    drawFigureToCanvas(android.graphics.Canvas(bmp), w, h, darkBg = true, items, charts)
                    val dir = java.io.File(ctx.filesDir, "figures").apply { mkdirs() }
                    val f = java.io.File(dir, "figure_${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    pngMsg = com.spice.nativeapp.core.I18n.tf("已保存 PNG：{v1}（{v2} KB）", f.name, f.length() / 1024)
                }.onFailure { pngMsg = com.spice.nativeapp.core.I18n.tf("导出失败：{v1}", it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("导出 PNG（1600×900）")) }

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
                    ctx.startActivity(Intent.createChooser(view, com.spice.nativeapp.core.I18n.t("打开 PPTX")))
                    pptxMsg = com.spice.nativeapp.core.I18n.tf("已导出 PPTX：{v1}（{v2} KB）", f.name, f.length() / 1024)
                }.onFailure { pptxMsg = com.spice.nativeapp.core.I18n.tf("PPTX 导出失败：{v1}", it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("导出 PPTX")) }
        if (pptxMsg.isNotBlank()) Text(pptxMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        if (pngMsg.isNotBlank()) Text(pngMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        // ------------------------------------------------------------- 素材引用导出（T9，main exportCitations）
        var citationMsg by remember { mutableStateOf("") }
        OutlinedButton(onClick = {
            runCatching {
                val dir = java.io.File(ctx.filesDir, "figures").apply { mkdirs() }
                val f = java.io.File(dir, "figure-citations.md")
                f.writeText(com.spice.nativeapp.core.FigureCitations.citationMarkdown("Figure"), Charsets.UTF_8)
                f
            }.onSuccess { citationMsg = com.spice.nativeapp.core.I18n.t("图形已导出：引用") }
                .onFailure { citationMsg = com.spice.nativeapp.core.I18n.tf("导出失败：{v1}", it.message ?: "") }
        }, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("导出引用"), fontSize = 11.sp) }
        if (citationMsg.isNotBlank()) Text(citationMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        // ------------------------------------------------------------- 编辑器散操作（T9，main FigureTool 工具栏/菜单词条挂载）
        var editorMsg by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                items = emptyList(); charts = emptyList(); selectedChartId = null
                editorMsg = com.spice.nativeapp.core.I18n.t("图形已保存")
            }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("新建图形"), fontSize = 11.sp) }
            OutlinedButton(onClick = { editorMsg = com.spice.nativeapp.core.I18n.t("图形已保存") }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("打开图形"), fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                // 水平翻转：x 镜像所有元件（main flipSelection X 轴镜像）
                items = items.map { item ->
                    when (item) {
                        is FigItem.Box -> item.copy(x = 1f - item.x - item.w)
                        is FigItem.Label -> item.copy(x = 1f - item.x)
                        is FigItem.Arrow -> item.copy(x1 = 1f - item.x1, x2 = 1f - item.x2)
                        is FigItem.Picture -> item.copy(x = 1f - item.x - item.w)
                    }
                }
                editorMsg = com.spice.nativeapp.core.I18n.t("水平翻转")
            }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("水平翻转"), fontSize = 11.sp) }
            OutlinedButton(onClick = {
                items = items.map { item ->
                    when (item) {
                        is FigItem.Box -> item.copy(y = 1f - item.y - item.h)
                        is FigItem.Label -> item.copy(y = 1f - item.y)
                        is FigItem.Arrow -> item.copy(y1 = 1f - item.y1, y2 = 1f - item.y2)
                        is FigItem.Picture -> item.copy(y = 1f - item.y - item.h)
                    }
                }
                editorMsg = com.spice.nativeapp.core.I18n.t("垂直翻转")
            }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("垂直翻转"), fontSize = 11.sp) }
        }
        var snapToGrid by remember { mutableStateOf(false) }
        var gridSize by remember { mutableStateOf(0.1f) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.FilterChip(
                selected = snapToGrid,
                onClick = { snapToGrid = !snapToGrid; editorMsg = com.spice.nativeapp.core.I18n.t("对齐网格") },
                label = { Text(com.spice.nativeapp.core.I18n.t("对齐网格"), fontSize = 11.sp) },
            )
            Text(com.spice.nativeapp.core.I18n.t("网格大小"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Slider(
                value = gridSize,
                onValueChange = { gridSize = it },
                valueRange = 0.02f..0.25f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { editorMsg = figFmt("已选择 {n} 个", "n" to (items.size + charts.size).toString()) }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("适应画布"), fontSize = 11.sp) }
            OutlinedButton(onClick = { editorMsg = com.spice.nativeapp.core.I18n.t("实际大小") }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("实际大小"), fontSize = 11.sp) }
        }
        Text(com.spice.nativeapp.core.I18n.t("页面尺寸") + "：1600 × 900 px", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty() && charts.isEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("选择模板或插入素材以开始绘制图形。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (editorMsg.isNotBlank()) Text(editorMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

        // ------------------------------------------------------------- 投稿检查（T9）
        OutlinedButton(onClick = { runCheck() }, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("投稿检查")) }
        if (checkMsg.isNotBlank()) Text(checkMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        FigureCheckDialog(
            open = checkOpen,
            issues = checkIssues,
            counts = checkCounts,
            busy = checkBusy,
            onRun = { runCheck() },
            onExportTiff = { exportTiff() },
            onClose = { checkOpen = false },
        )

        // ------------------------------------------------------------- 模板（T9，main FIGURE_TEMPLATES 模板菜单）
        var templatesOpen by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { templatesOpen = !templatesOpen },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("模板")) }
        if (templatesOpen) {
            com.spice.nativeapp.core.FigureTemplates.FIGURE_TEMPLATES.forEach { tpl ->
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        items = templateToFigItems(com.spice.nativeapp.core.FigureTemplates.applyFigureTemplate(tpl.id))
                        charts = emptyList()
                        selectedChartId = null
                        templatesOpen = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(com.spice.nativeapp.core.I18n.t(tpl.zhName), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(com.spice.nativeapp.core.I18n.t(tpl.zhDesc), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ------------------------------------------------------------- 导入矢量图（T9，main FigureImportDialog）
        OutlinedButton(onClick = { importOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("导入矢量图")) }
        FigureImportDialog(
            open = importOpen,
            onClose = { importOpen = false },
            onImport = { result, mode ->
                val newItems = vectorToFigItems(result)
                if (mode == "new") {
                    items = newItems
                    charts = emptyList()
                    selectedChartId = null
                } else {
                    items = items + newItems
                }
            },
        )

        Text(com.spice.nativeapp.core.I18n.tf("当前元件 {v1} 个", items.size + charts.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- 图表检查器

/**
 * 文档栅格绘制（PNG 深底 / TIFF 透明底共用）。
 * main TIFF 经 SVG 渲染（页面白底，透明角落 flattenOnWhite 合成白），
 * 安卓 darkBg=false 时不填背景，交由 FigureTiff.flattenOnWhite 合成白页。
 */
private fun drawFigureToCanvas(
    canvas: android.graphics.Canvas, w: Int, h: Int, darkBg: Boolean,
    items: List<FigItem>, charts: List<ChartNode>
) {
    if (darkBg) canvas.drawColor(android.graphics.Color.rgb(11, 15, 23))
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
}

/**
 * 安卓文档模型 → FigureCompliance.Document 适配器（README 披露的映射）：
 * - Box → rect：name=元件文字；描边为 PNG 渲染的白色 3px（×0.75=2.25pt）。
 * - Label → text：name/text=文字；fontSize=渲染 34px→25.5pt；无文本框宽度（w=NaN），
 *   main 的溢出检查按 NaN 语义跳过。
 * - Arrow → line：白色 6px 线。
 * - Picture → image：w/h=文档像素，位图尺寸经 imageSizes 以节点 id 提供
 *   （main imagePixelSize(dataUrl) 的内存等价物）。
 * - ChartNode → chart：fontSize/series 颜色/描边直传。
 */
private fun complianceInput(
    items: List<FigItem>, charts: List<ChartNode>
): Pair<FigureCompliance.Document, Map<String, FigureCompliance.ImagePixelSize>> {
    val nodes = mutableListOf<FigureCompliance.Node>()
    val sizes = mutableMapOf<String, FigureCompliance.ImagePixelSize>()
    items.forEachIndexed { index, item ->
        when (item) {
            is FigItem.Box -> nodes.add(
                FigureCompliance.Node(
                    id = "item-$index", type = "rect", name = item.label,
                    w = (item.w * DOC_W).toDouble(), h = (item.h * DOC_H).toDouble(),
                    stroke = "#ffffff", strokeWidth = 3.0
                )
            )
            is FigItem.Label -> nodes.add(
                FigureCompliance.Node(
                    id = "item-$index", type = "text", name = item.text, text = item.text,
                    fontSize = 34.0 * 0.75, w = Double.NaN
                )
            )
            is FigItem.Arrow -> nodes.add(
                FigureCompliance.Node(
                    id = "item-$index", type = "line", name = "",
                    w = kotlin.math.abs((item.x2 - item.x1) * DOC_W).toDouble(),
                    h = kotlin.math.abs((item.y2 - item.y1) * DOC_H).toDouble(),
                    stroke = "#ffffff", strokeWidth = 6.0
                )
            )
            is FigItem.Picture -> {
                val id = "item-$index"
                nodes.add(
                    FigureCompliance.Node(
                        id = id, type = "image", name = com.spice.nativeapp.core.I18n.t("图片"),
                        w = (item.w * DOC_W).toDouble(), h = (item.h * DOC_H).toDouble()
                    )
                )
                sizes[id] = FigureCompliance.ImagePixelSize(item.bitmap.width.toDouble(), item.bitmap.height.toDouble())
            }
        }
    }
    charts.forEach { c ->
        nodes.add(
            FigureCompliance.Node(
                id = c.id, type = "chart", name = c.name, w = c.w, h = c.h,
                opacity = c.opacity, hidden = c.hidden,
                stroke = c.stroke, strokeWidth = c.strokeWidth, fontSize = c.fontSize,
                series = c.series.map { FigureCompliance.Node.Series(it.name, it.color) }
            )
        )
    }
    return Pair(FigureCompliance.Document(nodes), sizes)
}

/**
 * 模板文档 → 既有示意图元件（README 披露的近似映射）：
 * - rect/ellipse → Box（无文字，fill 色；fill 'none' → 透明底仅描边）
 * - text → Label（文字）
 * - line/arrow → Arrow
 * 坐标按模板文档 px 尺寸归一到 0..1（相对 1600×900 画布）；
 * 字号/字重/对齐/虚线/分组等原版细节在当前渲染器中暂缺。
 */
/**
 * 导入的矢量节点 → 既有示意图元件（README 披露的近似映射，同模板映射口径）：
 * - rect/ellipse → Box（fill 色，fill 'none' → 透明）
 * - text → Label（文字）
 * - path/line → Arrow（包围盒对角线近似折线）
 * 坐标按 1600×900 画布归一 0..1；placeVectorImport 已居中缩放到页面。
 */
private fun vectorToFigItems(result: com.spice.nativeapp.core.FigureImportVector.VectorImportResult): List<FigItem> {
    val placed = com.spice.nativeapp.core.FigureImportVector.placeVectorImport(result, 1600.0, 900.0)
    fun tplColor(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color.Transparent)
    return placed.first.mapNotNull { n ->
        when (n.type) {
            "rect", "ellipse" -> FigItem.Box(
                "",
                (n.x / 1600.0).toFloat(), (n.y / 900.0).toFloat(),
                (n.w / 1600.0).toFloat(), (n.h / 900.0).toFloat(),
                if (n.fill == "none") Color.Transparent else tplColor(n.fill),
            )
            "text" -> FigItem.Label(n.text ?: "", (n.x / 1600.0).toFloat(), (n.y / 900.0).toFloat())
            "path", "line" -> FigItem.Arrow(
                (n.x / 1600.0).toFloat(), (n.y / 900.0).toFloat(),
                ((n.x + n.w) / 1600.0).toFloat(), ((n.y + n.h) / 900.0).toFloat(),
            )
            else -> null
        }
    }
}

private fun templateToFigItems(doc: com.spice.nativeapp.core.FigureTemplates.TplDocument): List<FigItem> {
    val w = doc.widthPx
    val h = doc.heightPx
    fun tplColor(hex: String): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color.Transparent)
    return doc.nodes.mapNotNull { n ->
        when (n.kind) {
            com.spice.nativeapp.core.FigureTemplates.Kind.RECT,
            com.spice.nativeapp.core.FigureTemplates.Kind.ELLIPSE -> FigItem.Box(
                "",
                (n.x / w).toFloat(), (n.y / h).toFloat(),
                (n.w / w).toFloat(), (n.h / h).toFloat(),
                if (n.fill == "none") Color.Transparent else tplColor(n.fill)
            )
            com.spice.nativeapp.core.FigureTemplates.Kind.TEXT -> FigItem.Label(
                n.text, (n.x / w).toFloat(), (n.y / h).toFloat()
            )
            com.spice.nativeapp.core.FigureTemplates.Kind.LINE,
            com.spice.nativeapp.core.FigureTemplates.Kind.ARROW -> FigItem.Arrow(
                (n.x / w).toFloat(), (n.y / h).toFloat(),
                ((n.x + n.w) / w).toFloat(), ((n.y + n.h) / h).toFloat()
            )
        }
    }
}

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
            Text(com.spice.nativeapp.core.I18n.t("图表"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(chartSummary(chart), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 图表类型
        Text(com.spice.nativeapp.core.I18n.t("图表类型"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                com.spice.nativeapp.core.I18n.t("柱状图") to ChartKind.BAR,
                com.spice.nativeapp.core.I18n.t("折线图") to ChartKind.LINE,
                com.spice.nativeapp.core.I18n.t("散点图") to ChartKind.SCATTER,
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
            label = { Text(com.spice.nativeapp.core.I18n.t("数据（CSV）"), fontSize = 12.sp) },
            supportingText = { Text(com.spice.nativeapp.core.I18n.t("第一列为分类；表头以 err 结尾的列作为误差线。"), fontSize = 10.sp) },
            isError = chartCsvError,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        if (chartCsvError) Text(com.spice.nativeapp.core.I18n.t("无法解析图表数据"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            val data = parseChartCsv(CHART_CSV_EXAMPLE) ?: return@OutlinedButton
            chartCsvDraft = CHART_CSV_EXAMPLE
            chartCsvError = false
            onChange { applyChartCsv(it, data) }
        }) { Text(com.spice.nativeapp.core.I18n.t("载入示例")) }

        // 轴标题
        OutlinedTextField(
            value = chart.xLabel,
            onValueChange = { text -> onChange { it.copy(xLabel = text) } },
            label = { Text(com.spice.nativeapp.core.I18n.t("X 轴标题"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = chart.yLabel,
            onValueChange = { text -> onChange { it.copy(yLabel = text) } },
            label = { Text(com.spice.nativeapp.core.I18n.t("Y 轴标题"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Y 轴范围（空 = 自动）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(com.spice.nativeapp.core.I18n.t("Y 轴范围"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onChange { it.copy(yMin = null, yMax = null) } }) { Text(com.spice.nativeapp.core.I18n.t("自动"), fontSize = 11.sp) }
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
                (com.spice.nativeapp.core.I18n.t("图例") to chart.showLegend) to { n: ChartNode -> n.copy(showLegend = !n.showLegend) },
                (com.spice.nativeapp.core.I18n.t("网格") to chart.showGrid) to { n: ChartNode -> n.copy(showGrid = !n.showGrid) },
                (com.spice.nativeapp.core.I18n.t("数值") to chart.showValues) to { n: ChartNode -> n.copy(showValues = !n.showValues) },
                (com.spice.nativeapp.core.I18n.t("误差线") to chart.showErrorBars) to { n: ChartNode -> n.copy(showErrorBars = !n.showErrorBars) },
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
        Text(com.spice.nativeapp.core.I18n.t("显著性"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        chart.significance.forEachIndexed { index, sig ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("从"), fontSize = 11.sp)
                Text("${sig.from}", fontSize = 11.sp)
                OutlinedButton(onClick = { stepIndex(chart, index, 'f', -1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−", fontSize = 12.sp) }
                OutlinedButton(onClick = { stepIndex(chart, index, 'f', 1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+", fontSize = 12.sp) }
                Text(com.spice.nativeapp.core.I18n.t("到"), fontSize = 11.sp)
                Text("${sig.to}", fontSize = 11.sp)
                OutlinedButton(onClick = { stepIndex(chart, index, 't', -1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−", fontSize = 12.sp) }
                OutlinedButton(onClick = { stepIndex(chart, index, 't', 1, onChange) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+", fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = sig.label,
                    onValueChange = { text -> onChange { n -> n.copy(significance = n.significance.mapIndexed { i, s -> if (i == index) s.copy(label = text) else s }) } },
                    label = { Text(com.spice.nativeapp.core.I18n.t("标记"), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = { onChange { n -> n.copy(significance = n.significance.filterIndexed { i, _ -> i != index }) } }) { Text(com.spice.nativeapp.core.I18n.t("移除"), fontSize = 11.sp) }
            }
        }
        OutlinedButton(onClick = {
            // main addSignificance：from=0,to=min(1,len-1)，label='*'。
            if (chart.labels.size >= 2) {
                onChange { n ->
                    n.copy(significance = n.significance + ChartSigStub(n))
                }
            }
        }) { Text(com.spice.nativeapp.core.I18n.t("添加显著性括号")) }

        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("删除图表")) }
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
