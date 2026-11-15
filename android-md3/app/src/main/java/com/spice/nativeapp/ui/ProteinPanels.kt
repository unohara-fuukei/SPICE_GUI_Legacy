package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ProteinBiophysics
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 蛋白工作台面板（对齐 main 分支蛋白页右侧六个分析 Tab：
 * fold / md / pocket / phase / mut / batch），纯 Canvas 自绘。
 */

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun KV(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ───────────────────────── MOL* 查看器（main MolStarViewer.svelte 1:1） ─────────────────────────
//
// main 组件 632 行：molstar npm headless WebGL 渲染。安卓零 WebView 红线下以
// Compose Canvas 自绘 Cα 渲染引擎实现同一功能面（工具栏/表示五模式/视图设置/
// 论文 PNG 导出），行为与状态模型 1:1，渲染管线为披露的平台差异。
//
// 平台差异（README 披露）：
// - WebGL Mol* 渲染管线 → Canvas 软件渲染：cartoon 按 Chou-Fasman 二级结构着色
//   （H=螺旋红 / E=折叠片黄 / 卷曲灰），近似 Mol* polymer cartoon；
// - outline/shadow/bloom/AO 为 Canvas 近似（描边/投影偏移/光晕/深度调光），
//   语义与 Mol* 后处理开关一致，效果为简化实现；
// - molstar hover 交互（onResidueHover）与 pockets 高级渲染参数保留 API 位但
//   未接线（依赖 WebGL pick）；onMount 异步 init 为同步，viewerLoading 恒不显示；
// - 导出目标：main backend.saveExport('spice_fold.png') → 应用专属 PICTURES 目录
//   spice_fold.png（同一文件名）。

private val MolStarRepOptions = listOf("auto", "trace", "spacefill", "cartoon", "ballstick")

/** rainbow：main Mol* 非 pdbText 路径默认着色（按索引 HSL 彩虹）。 */
private fun rainbowColor(i: Int, n: Int, exposure: Float): Color {
    val hue = if (n <= 1) 0f else 300f * i / (n - 1) // Mol* rainbow: 蓝→红
    val h = (hue % 360f + 360f) % 360f
    val c = androidx.compose.ui.graphics.Color.hsv(h, 0.75f, 1f)
    // exposure：向白/黑双向缩放（近似 renderer.exposure）
    val e = (exposure).coerceIn(0.1f, 3f)
    val r = (c.red * e).coerceIn(0f, 1f); val g = (c.green * e).coerceIn(0f, 1f); val b = (c.blue * e).coerceIn(0f, 1f)
    return Color(r, g, b)
}

@Composable
fun MolStarViewer(
    fold: ProteinBiophysics.FoldOutput,
    repExternal: String? = null,       // main bind:rep（外部同步；null=内部自持）
    highlightRange: Pair<Int, Int>? = null,
    pockets: List<ProteinBiophysics.Pocket> = emptyList(),
    selectedPocketId: Int? = null,
) {
    val t = com.spice.nativeapp.core.I18n
    val pixGreen = Color(0xFF53d769); val pixFgDim = MaterialTheme.colorScheme.onSurfaceVariant

    // main repSel（$state(rep) + $effect 同步）
    var repSel by remember { mutableStateOf(repExternal ?: "auto") }
    if (repExternal != null) repSel = repExternal
    var viewOpen by remember { mutableStateOf(false) }
    var shotBusy by remember { mutableStateOf(false) }

    // Mol* view settings（main $state 一一对应）
    var bg by remember { mutableStateOf("dark") }            // dark | white | transparent
    var camMode by remember { mutableStateOf("perspective") } // perspective | orthographic
    var axes by remember { mutableStateOf(false) }
    var exposure by remember { mutableStateOf(1.0f) }
    var lightI by remember { mutableStateOf(1.0f) }
    var outline by remember { mutableStateOf(false) }
    var shadow by remember { mutableStateOf(false) }
    var bloom by remember { mutableStateOf(false) }
    var ao by remember { mutableStateOf(false) }
    var sizeFactor by remember { mutableStateOf(1.0f) }
    var exportWatermark by remember { mutableStateOf(true) }
    var exportTransparent by remember { mutableStateOf(false) }
    var exportMultiplier by remember { mutableStateOf(2) } // 1, 2, 4

    // 交互（main 由 Mol* 相机控制；Canvas 等价：拖拽旋转 + 缩放）
    var rotX by remember { mutableFloatStateOf(-0.35f) }
    var rotY by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableStateOf(1f) }
    fun resetCamera() { rotX = -0.35f; rotY = 0.5f; zoom = 1f } // main managers.camera.reset()

    fun effectiveRep(): String {
        val def = "trace" // isFull()=false（coordsData 路径）→ 默认 trace
        return if (repSel == "auto") def else repSel
    }

    val n = fold.length
    val graphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun exportPaperPng() {
        if (shotBusy) return
        shotBusy = true
        scope.launch {
            try {
                val bmp0 = graphicsLayer.toImageBitmap().asAndroidBitmap()
                val w = (bmp0.width * exportMultiplier).coerceAtLeast(1)
                val h = (bmp0.height * exportMultiplier).coerceAtLeast(1)
                val bmp = android.graphics.Bitmap.createScaledBitmap(bmp0, w, h, true)
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(out)
                if (exportTransparent) {
                    // clearRect
                } else {
                    cv.drawColor(android.graphics.Color.WHITE)
                }
                cv.drawBitmap(bmp, 0f, 0f, null)
                if (exportWatermark) {
                    val pad = (w * 0.03f).toInt()
                    val logoH = (h * 0.055f).toInt()
                    val text = "Folded by SPICE"
                    val pTxt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        isFakeBoldText = true; textSize = logoH * 0.52f
                        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    }
                    val tw = pTxt.measureText(text)
                    val totalW = logoH + 10 + tw
                    val x0 = w - pad - totalW
                    val y0 = h - pad - logoH
                    val pBg = android.graphics.Paint().apply {
                        color = if (exportTransparent) android.graphics.Color.argb(0xC0, 0x12, 0x16, 0x24)
                        else android.graphics.Color.argb(0xD9, 0xFF, 0xFF, 0xFF)
                    }
                    cv.drawRect(x0 - 6f, y0 - 5f, x0 + totalW + 6f, y0 + logoH + 5f, pBg)
                    val logo = android.graphics.BitmapFactory.decodeResource(ctx.resources, com.spice.nativeapp.R.drawable.spice_logo)
                    if (logo != null) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(logo, logoH, logoH, true)
                        cv.drawBitmap(scaled, x0.toFloat(), y0.toFloat(), null)
                    }
                    pTxt.color = if (exportTransparent) android.graphics.Color.WHITE else android.graphics.Color.argb(0xFF, 0x11, 0x11, 0x11)
                    pTxt.textAlign = android.graphics.Paint.Align.LEFT
                    val fm = pTxt.fontMetrics
                    cv.drawText(text, x0 + logoH + 10, y0 + logoH / 2f - (fm.ascent + fm.descent) / 2f, pTxt)
                }
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                val f = java.io.File(dir, "spice_fold.png") // main 文件名
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                android.widget.Toast.makeText(ctx, t.t("导出完成") + ": " + f.absolutePath, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, t.t("导出 PNG（论文）") + ": " + (e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
            }
            shotBusy = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // ── msv-toolbar ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(t.t("MOL* 查看器"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixGreen)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.t("表示"), fontSize = 11.sp, color = pixFgDim)
                val repZh = mapOf(
                    "auto" to t.t("自动"), "trace" to t.t("Cα 轨迹"), "spacefill" to t.t("空间填充"),
                    "cartoon" to t.t("卡通"), "ballstick" to t.t("球棍")
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        repSel = MolStarRepOptions[(MolStarRepOptions.indexOf(effectiveRep().let { if (MolStarRepOptions.contains(repSel)) repSel else "auto" }) + 1) % MolStarRepOptions.size]
                    },
                    modifier = Modifier.heightIn(min = 36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) { Text(repZh[repSel] ?: repSel, fontSize = 10.sp) }
            }
            androidx.compose.material3.OutlinedButton(onClick = { viewOpen = !viewOpen }, modifier = Modifier.heightIn(min = 36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("⚙ " + t.t("视图设置"), fontSize = 10.sp) }
            androidx.compose.material3.OutlinedButton(onClick = { resetCamera() }, modifier = Modifier.heightIn(min = 36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("⟲ " + t.t("复位"), fontSize = 10.sp) }
            androidx.compose.material3.OutlinedButton(onClick = { exportPaperPng() }, enabled = !shotBusy, modifier = Modifier.heightIn(min = 36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("▣ " + t.t("导出 PNG（论文）"), fontSize = 10.sp) }
            // busy LED（main shotBusy/busy）
            androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) { drawCircle(if (shotBusy) Color(0xFFFFD23F) else pixGreen) }
        }

        Box(Modifier.fillMaxWidth().height(300.dp)) {
            // ── msv-container（渲染画布；同时录层供 PNG 导出） ──
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxWidth().height(300.dp)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            rotY += drag.x / 120f
                            rotX += drag.y / 160f
                        }
                    }
                    .pointerInput(Unit) {
                        // 双指/滚轮缩放（main Mol* 相机 zoom 等价）
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val m = event.calculateZoom()
                                if (m != 1f) { zoom = (zoom * m).coerceIn(0.3f, 4f); event.changes.forEach { it.consume() } }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .drawWithCache {
                        onDrawWithContent {
                            // PNG 录层（Robolectric 软渲染不支持 RenderNode 时降级直绘）
                            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                                graphicsLayer.record(androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt())) { this@onDrawWithContent.drawContent() }
                                drawLayer(graphicsLayer)
                            } else {
                                drawContent()
                            }
                        }
                    }
            ) {
                val bgCol = when (bg) {
                    "white" -> Color(0xFFFFFFFF)
                    "transparent" -> null // 透出面板
                    else -> Color(0xFF0B0E14) // main bgColor() 0x0b0e14
                }
                bgCol?.let { drawRect(it, size = size) }
                if (n == 0) return@Canvas
                val coords = fold.coords
                val cx = size.width / 2f; val cy = size.height / 2f
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                for (i in 0 until n) {
                    minX = min(minX, coords[i * 3]); maxX = max(maxX, coords[i * 3])
                    minY = min(minY, coords[i * 3 + 1]); maxY = max(maxY, coords[i * 3 + 1])
                    minZ = min(minZ, coords[i * 3 + 2]); maxZ = max(maxZ, coords[i * 3 + 2])
                }
                val ctrX = (minX + maxX) / 2f; val ctrY = (minY + maxY) / 2f; val ctrZ = (minZ + maxZ) / 2f
                val maxR = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(1f)
                val scale = min(size.width, size.height) * 0.42f / maxR * zoom

                val ortho = camMode == "orthographic"
                // 投影 + 深度（AO 用）
                data class P(val x: Float, val y: Float, val z: Float)
                val pts = (0 until n).map { i ->
                    val x = coords[i * 3] - ctrX
                    val y = coords[i * 3 + 1] - ctrY
                    val z = coords[i * 3 + 2] - ctrZ
                    val x1 = x * cos(rotY.toDouble()).toFloat() - z * sin(rotY.toDouble()).toFloat()
                    val z1 = x * sin(rotY.toDouble()).toFloat() + z * cos(rotY.toDouble()).toFloat()
                    val y2 = y * cos(rotX.toDouble()).toFloat() - z1 * sin(rotX.toDouble()).toFloat()
                    val z2 = y * sin(rotX.toDouble()).toFloat() + z1 * cos(rotX.toDouble()).toFloat()
                    val persp = if (ortho) 1f else 1f / (1f + z2 / (maxR + 40f))
                    P(cx + x1 * scale * persp, cy + y2 * scale * persp, z2)
                }

                val kind = effectiveRep()
                val exp = exposure * lightI / 2f + 0.5f // 曝光×光照合成（近似 renderer.exposure + light.intensity）
                fun col(i: Int): Color {
                    val base = if (kind == "cartoon") {
                        val ss = fold.secondary.firstOrNull { it.first == i }?.second ?: 'C'
                        when (ss) {
                            'H' -> Color(0xFFFF5D5D) // 螺旋
                            'E' -> Color(0xFFFFD23F) // 折叠片
                            else -> Color(0xFF9FA8B8) // 卷曲
                        }
                    } else rainbowColor(i, n, 1f)
                    val e = exp.coerceIn(0.1f, 3f)
                    return Color(
                        (base.red * e).coerceIn(0f, 1f),
                        (base.green * e).coerceIn(0f, 1f),
                        (base.blue * e).coerceIn(0f, 1f)
                    )
                }

                // shadow：主几何向下偏移的暗色投影（后处理 shadow 近似）
                if (shadow) {
                    for (i in 0 until n - 1) {
                        drawLine(
                            Color.Black.copy(alpha = 0.35f),
                            Offset(pts[i].x + 2f, pts[i].y + 2f), Offset(pts[i + 1].x + 2f, pts[i + 1].y + 2f), 2.dp.toPx()
                        )
                    }
                }

                // 主几何
                val lineW = when (kind) {
                    "trace" -> if (n > 250) 1.2.dp.toPx() else 2.2.dp.toPx()
                    "cartoon" -> 5.dp.toPx() * sizeFactor
                    "ballstick" -> 1.5.dp.toPx()
                    else -> 0f
                }
                if (lineW > 0f) {
                    for (i in 0 until n - 1) {
                        val c = col(i)
                        if (bloom) { // 光晕近似：宽低 alpha 底线
                            drawLine(c.copy(alpha = 0.18f), Offset(pts[i].x, pts[i].y), Offset(pts[i + 1].x, pts[i + 1].y), lineW * 3f)
                        }
                        drawLine(c, Offset(pts[i].x, pts[i].y), Offset(pts[i + 1].x, pts[i + 1].y), lineW)
                    }
                }
                val ptR = when (kind) {
                    "trace" -> 1.4.dp.toPx() * sizeFactor * 0.5f * 2f // spacefill sizeFactor*0.5 半径
                    "spacefill" -> 4.dp.toPx() * sizeFactor
                    "ballstick" -> 3.dp.toPx() * sizeFactor
                    "cartoon" -> 0f
                    else -> 0f
                }
                if (ptR > 0f) {
                    pts.forEachIndexed { i, p ->
                        var c = col(i)
                        if (ao) { // AO 近似：按深度调光（近亮远暗）
                            val dim = 1f - ((p.z - minZ) / (maxZ - minZ).coerceAtLeast(0.001f)) * 0.45f
                            c = c.copy(red = c.red * dim, green = c.green * dim, blue = c.blue * dim)
                        }
                        if (outline) { // 描边：黑描一圈
                            drawCircle(Color.Black, ptR + 1.dp.toPx(), Offset(p.x, p.y))
                        }
                        drawCircle(c, ptR, Offset(p.x, p.y))
                    }
                }

                // N/C 端点（main Mol* 无显式端标——旧 FoldPanel 遗留，去掉以 1:1）
                // highlightRange：选区高亮（main lociHighlights/selects 等价）
                if (highlightRange != null) {
                    val (hs, he) = highlightRange
                    val sel = pts.filterIndexed { i, _ -> i >= hs && i <= he }
                    if (sel.isNotEmpty()) {
                        for (i in 0 until sel.size - 1) {
                            drawLine(Color.White, Offset(sel[i].x, sel[i].y), Offset(sel[i + 1].x, sel[i + 1].y), lineW.coerceAtLeast(2.dp.toPx()) + 2.dp.toPx())
                        }
                    }
                }

                // axes gizmo（main camera.helper.axes）
                if (axes) {
                    val ox = 24f; val oy = size.height - 24f; val L = 20f
                    drawLine(Color(0xFFFF5D5D), Offset(ox, oy), Offset(ox + L, oy), 2f)
                    drawLine(Color(0xFF53D769), Offset(ox, oy), Offset(ox, oy - L), 2f)
                    drawLine(Color(0xFF4CD6FF), Offset(ox, oy), Offset(ox - L * 0.7f, oy + L * 0.7f), 2f)
                }
            }

            // ── msv-settings（浮层，absolute top-right） ──
            if (viewOpen) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF3A3F4E)),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).width(260.dp)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(t.t("视图设置"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            androidx.compose.material3.TextButton(onClick = { viewOpen = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("✕", fontSize = 11.sp) }
                        }
                        @Composable fun settingRow(label: String, options: List<String>, cur: String, zh: (String) -> String, set: (String) -> Unit) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(label, fontSize = 11.sp, color = pixFgDim)
                                androidx.compose.material3.OutlinedButton(onClick = { set(options[(options.indexOf(cur) + 1) % options.size]) }, modifier = Modifier.heightIn(min = 32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text(zh(cur), fontSize = 10.sp) }
                            }
                        }
                        settingRow(t.t("背景"), listOf("dark", "white", "transparent"), bg, { v -> mapOf("dark" to t.t("深色"), "white" to t.t("白色"), "transparent" to t.t("透明"))[v]!! }, { bg = it })
                        settingRow(t.t("相机"), listOf("perspective", "orthographic"), camMode, { v -> if (v == "perspective") t.t("透视") else t.t("正交") }, { camMode = it })
                        settingRow(t.t("坐标轴"), listOf("off", "on"), if (axes) "on" else "off", { v -> if (v == "on") t.t("开") else t.t("关") }, { axes = it == "on" })
                        @Composable fun sliderRow(label: String, cur: Float, min: Float, max: Float, set: (Float) -> Unit) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontSize = 11.sp, color = pixFgDim)
                                    Text("%.1f".format(cur), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                androidx.compose.material3.Slider(value = cur, onValueChange = { set((it * 10).toInt() / 10f) }, valueRange = min..max, modifier = Modifier.fillMaxWidth().height(28.dp))
                            }
                        }
                        sliderRow(t.t("曝光"), exposure, 0.3f, 2.5f) { exposure = it }
                        sliderRow(t.t("光照"), lightI, 0.2f, 3f) { lightI = it }
                        sliderRow(t.t("尺寸系数"), sizeFactor, 0.2f, 2f) { sizeFactor = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            @Composable
                            fun tgl(label: String, cur: Boolean, set: (Boolean) -> Unit) {
                                androidx.compose.material3.FilterChip(selected = cur, onClick = { set(!cur) }, label = { Text(label, fontSize = 10.sp) })
                            }
                            tgl(t.t("描边"), outline) { outline = it }
                            tgl(t.t("阴影"), shadow) { shadow = it }
                            tgl(t.t("泛光"), bloom) { bloom = it }
                            tgl(t.t("环境光遮蔽"), ao) { ao = it }
                        }
                        Text(t.t("导出 PNG（论文）"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixGreen)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            androidx.compose.material3.FilterChip(selected = exportWatermark, onClick = { exportWatermark = !exportWatermark }, label = { Text(t.t("保留 SPICE 水印"), fontSize = 10.sp) })
                            androidx.compose.material3.FilterChip(selected = exportTransparent, onClick = { exportTransparent = !exportTransparent }, label = { Text(t.t("透明背景导出"), fontSize = 10.sp) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(t.t("导出分辨率"), fontSize = 11.sp, color = pixFgDim)
                            androidx.compose.material3.OutlinedButton(onClick = {
                                val opts = listOf(1, 2, 4)
                                exportMultiplier = opts[(opts.indexOf(exportMultiplier) + 1) % opts.size]
                            }, modifier = Modifier.heightIn(min = 32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                                Text(t.t("{v}倍超采样").replace("{v}", exportMultiplier.toString()), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 折叠面板（结构折叠数据 + MOL* 查看器） ─────────────────────────

@Composable
fun FoldPanel(fold: ProteinBiophysics.FoldOutput, sequence: String) {
    PanelCard(com.spice.nativeapp.core.I18n.t("结构折叠面板")) {
        MolStarViewer(fold = fold)
        val n = fold.length
        val helix = fold.secondary.count { it.second == 'H' }
        val sheet = fold.secondary.count { it.second == 'E' }
        val coil = n - helix - sheet
        val contacts = fold.contactMap.sumOf { it.toInt() } / 2
        KV(com.spice.nativeapp.core.I18n.t("残基数"), "$n aa")
        KV(com.spice.nativeapp.core.I18n.t("二级结构"), com.spice.nativeapp.core.I18n.tf("α {v1} · β {v2} · 卷曲 {v3}（Chou-Fasman）", helix, sheet, coil))
        KV(com.spice.nativeapp.core.I18n.t("接触对 (d<8Å)"), "$contacts")
        KV(com.spice.nativeapp.core.I18n.t("预测置信度"), "%.0f%%".format(fold.conf * 100), warn = fold.conf < 0.5)
        Text(
            com.spice.nativeapp.core.I18n.t("Cα 骨架由二级结构感知几何构建（α-螺旋 3.6 res/turn；环境偏离 pH7/310K 时置信度下降）。N（蓝）→ C（红）。"),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ───────────────────────── MD 轨迹面板 ─────────────────────────

@Composable
fun MdPanel(md: ProteinBiophysics.MdStep) {
    PanelCard(com.spice.nativeapp.core.I18n.t("动力学轨迹面板（MD）")) {
        // 主题感知图表色：浅色主题下用深 primary/outline，避免亮青细线低对比
        val lineColor = MaterialTheme.colorScheme.primary
        val axisColor = MaterialTheme.colorScheme.outline
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val h = md.uHist
            if (h.size < 2) return@Canvas
            val minU = h.min(); val maxU = h.max()
            val range = (maxU - minU).coerceAtLeast(1e-6)
            val dx = size.width / (h.size - 1)
            val pts = h.mapIndexed { i, u ->
                Offset(i * dx, size.height * 0.9f - ((u - minU) / range).toFloat() * size.height * 0.8f)
            }
            drawLine(axisColor, Offset(0f, size.height * 0.9f), Offset(size.width, size.height * 0.9f), 1.dp.toPx())
            pts.zipWithNext { a, b -> drawLine(lineColor, a, b, 1.6.dp.toPx()) }
        }
        KV(com.spice.nativeapp.core.I18n.t("步数 / 模拟时长"), com.spice.nativeapp.core.I18n.tf("{v1} 步 · %.2f ps", md.stepCount).format(md.timePs))
        KV(com.spice.nativeapp.core.I18n.t("势能 U"), "%.0f kcal/mol".format(md.metrics.uTKcal))
        KV(com.spice.nativeapp.core.I18n.t("回转半径 Rg"), "%.2f Å".format(md.metrics.rg))
        KV(com.spice.nativeapp.core.I18n.t("二级结构保持"), "${md.metrics.nSsKept}/${md.metrics.nSsRef}")
        KV(com.spice.nativeapp.core.I18n.t("表面带电残基"), "${md.metrics.nSurfaceCharged}")
        Text(com.spice.nativeapp.core.I18n.t("能量历史（U vs 步数）；启发式轨迹，用于稳定性趋势研判。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 温-pH 相图面板 ─────────────────────────

@Composable
fun PhasePanel(points: List<ProteinBiophysics.ScanPoint>, bifurcation: com.spice.nativeapp.core.PhaseBifurcation.Output? = null) {
    PanelCard(com.spice.nativeapp.core.I18n.t("环境相图面板（温-pH 稳定性扫描）")) {
        val temps = points.map { it.t }.distinct().sorted()
        val phs = points.map { it.ph }.distinct().sorted()
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            if (temps.isEmpty() || phs.isEmpty()) return@Canvas
            val left = 34f; val top = 10f
            val cellW = (size.width - left - 6f) / phs.size
            val cellH = (size.height - top - 26f) / temps.size
            for (i in temps.indices) for (j in phs.indices) {
                val p = points.firstOrNull { it.t == temps[i] && it.ph == phs[j] } ?: continue
                val color = when {
                    p.stable -> Color(0xFF4CAF50)
                    p.reason != null -> Color(0xFFF44336)
                    else -> Color(0xFFFFA726)
                }
                drawRect(
                    color.copy(alpha = 0.85f),
                    topLeft = Offset(left + j * cellW, top + i * cellH),
                    size = androidx.compose.ui.geometry.Size(cellW - 2f, cellH - 2f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(com.spice.nativeapp.core.I18n.tf("温度 {v1}–{v2} K（纵）", temps.minOrNull()?.toInt(), temps.maxOrNull()?.toInt()), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(com.spice.nativeapp.core.I18n.tf("pH {v1}–{v2}（横）", phs.minOrNull(), phs.maxOrNull()), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val stable = points.count { it.stable }
        val crashed = points.count { it.reason != null }
        KV(com.spice.nativeapp.core.I18n.t("稳定 / 失稳 / 崩溃"), "$stable / ${points.size - stable - crashed} / $crashed")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFF4CAF50)) }; Text(com.spice.nativeapp.core.I18n.t(" 稳定"), fontSize = 10.sp) }
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFFFFA726)) }; Text(com.spice.nativeapp.core.I18n.t(" 失稳"), fontSize = 10.sp) }
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFFF44336)) }; Text(com.spice.nativeapp.core.I18n.t(" 崩溃"), fontSize = 10.sp) }
        }
        Text(com.spice.nativeapp.core.I18n.t("以 (310 K, pH 7) 为稳定中心的扫描；绿色域为可保持折叠的环境窗口。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ── Bifurcation Tracker 卡（main protein/+page.svelte:2231-2260：扫描 ≥6 点拟合并渲染；boundary_curve 数据在场但卡内不画曲线，1:1）──
        if (bifurcation != null) {
            val b = bifurcation!!
            val pixGreen = Color(0xFF53d769); val pixRed = Color(0xFFFF5d5d); val pixAccent = Color(0xFF4cd6ff)
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🔬", fontSize = 11.sp)
                    Text(com.spice.nativeapp.core.I18n.t("热力学协同崩溃分叉追踪器 (Bifurcation Tracker)"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pixAccent)
                }
                KV(com.spice.nativeapp.core.I18n.t("亚稳折叠中心 (Optimal Folding Center):"),
                    f32str(b.optimalTempK) + " K / pH " + f32str(b.optimalPh), warn = false)
                KV(com.spice.nativeapp.core.I18n.t("临界协同失活分叉界限 (Bifurcation Limit):"),
                    f32str(b.bifurcationT) + " K / pH " + f32str(b.bifurcationPh), warn = true)
                KV(com.spice.nativeapp.core.I18n.t("相曲面类型 (Landau Surface):"),
                    com.spice.nativeapp.core.I18n.t(if (b.isSaddle) "⚠️ 鞍形失稳表面 (Unstable Saddle)" else "✅ 稳定物理势阱 (Metastable Well)"),
                    warn = b.isSaddle)
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(com.spice.nativeapp.core.I18n.t("拟合公式 (Landau expansion):"), fontSize = 10.sp, color = pixAccent)
                val cx = listOf("x", "x²", "y", "y²", "xy")
                Text(
                    "ΔG(T, pH) ≈ " + jsToFixed2(b.coefficients[0]) + (1..5).joinToString(" + ") { jsToFixed2(b.coefficients[it]) + cx[it - 1] },
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = pixAccent
                )
            }
        }
    }
}

/** f32 最短显示（serde f32 → JS String 同构）：Float.toString + 去 ".0" 尾。 */
private fun f32str(v: Double): String = java.lang.Float.toString(v.toFloat()).removeSuffix(".0")

/** JS Number.toFixed(2)：BigDecimal HALF_UP。 */
private fun jsToFixed2(v: Double): String = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()



// ───────────────────────── 活性口袋面板 ─────────────────────────

@Composable
fun PocketPanel(pockets: List<ProteinBiophysics.Pocket>) {
    PanelCard(com.spice.nativeapp.core.I18n.t("活性结合口袋监测")) {
        if (pockets.isEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("未检测到埋藏口袋（序列过短或全为卷曲）"), fontSize = 12.sp)
            return@PanelCard
        }
        pockets.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KV(com.spice.nativeapp.core.I18n.tf("口袋 #{v1}", p.id), com.spice.nativeapp.core.I18n.t("体积 %.1f Å³ · 成药性 %.2f").format(p.volume, p.druggability), warn = p.druggability > 0.7)
                Text(
                    com.spice.nativeapp.core.I18n.tf("中心 ({v1}, {v2}, {v3}) · 表面残基 {v4}", p.center.first.toInt(), p.center.second.toInt(), p.center.third.toInt(), p.surfaceResidues.joinToString(",")),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(com.spice.nativeapp.core.I18n.t("基于接触数聚簇的几何检测；成药性 > 0.7 的口袋优先关注。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 突变购物车面板 ─────────────────────────

@Composable
fun MutationCartPanel(
    cart: List<Triple<Int, Char, Char>>,
    sequence: String,
    onRemove: (Int) -> Unit,
    onApply: (String) -> Unit,
    onClear: () -> Unit
) {
    PanelCard(com.spice.nativeapp.core.I18n.tf("突变分析面板（购物车 {v1} 项）", cart.size)) {
        if (cart.isEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("暂无突变。用「单点突变设计」或序列扫描加入突变。"), fontSize = 12.sp)
            return@PanelCard
        }
        cart.forEachIndexed { idx, (pos, from, to) ->
            val ddg = ProteinBiophysics.mutationalStabilityDdg(from, pos, to)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "$from$pos$to",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
                )
                Text(
                    "ΔΔG %+.2f kcal/mol · ${ddg.classificationZh}".format(ddg.ddG),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = if (ddg.ddG > 1.0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onRemove(idx) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("移除"), fontSize = 11.sp) }
            }
        }
        val mutated = ProteinBiophysics.applyMutations(sequence, cart)
        if (mutated != sequence) {
            val diff = ProteinBiophysics.comparePmf(sequence, mutated)
            val shifted = diff.count { it.type == "shifted" }
            val newPeaks = diff.count { it.type == "mut_only" }
            KV(com.spice.nativeapp.core.I18n.t("PMF 差分"), com.spice.nativeapp.core.I18n.tf("移位峰 {v1} · 新峰 {v2}（MALDI 鉴定用）", shifted, newPeaks))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onApply(mutated) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("应用到序列"), fontSize = 11.sp) }
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("清空"), fontSize = 11.sp) }
        }
    }
}

// ───────────────────────── 批量筛选面板 ─────────────────────────

@Composable
fun BatchPanel(results: List<ProteinBiophysics.MutationAnalysis>) {
    PanelCard(com.spice.nativeapp.core.I18n.tf("批量设计筛选面板（{v1} 个突变体）", results.size)) {
        if (results.isEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("导入突变库 CSV（每行：ID, V15A + I32L）后评估。"), fontSize = 12.sp)
            return@PanelCard
        }
        results.sortedByDescending { it.thermostabilityScore }.take(30).forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${r.id}  ${r.mutations}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        com.spice.nativeapp.core.I18n.tf("稳定性 {v1}/100", r.thermostabilityScore),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (r.thermostabilityScore >= 70) Color(0xFF4CAF50)
                        else if (r.thermostabilityScore >= 50) Color(0xFFFFA726)
                        else MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    com.spice.nativeapp.core.I18n.t("ΔpI %+.2f · GRAVY %.3f · pH7 电荷 %+.1f · 螺旋 %d%% · 无序 %.3f").format(
                        r.pIDelta, r.gravy, r.chargeAtPh7, r.helixPercent, r.disorderScore
                    ),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
