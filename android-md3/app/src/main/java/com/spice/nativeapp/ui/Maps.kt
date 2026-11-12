package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqRecord
import kotlin.math.cos
import kotlin.math.sin

private val featureColors = listOf(
    Color(0xFF7FE7D6), Color(0xFFFFC56E), Color(0xFF9FA8FF),
    Color(0xFF7ED957), Color(0xFFFF8A80), Color(0xFF69C9F0)
)

private fun featureColor(type: String): Color = when (type.lowercase()) {
    "cds" -> Color(0xFF7FE7D6)
    "promoter" -> Color(0xFFFFC56E)
    "primer" -> Color(0xFF9FA8FF)
    "rep_origin" -> Color(0xFF7ED957)
    "misc" -> Color(0xFF69C9F0)
    else -> Color(0xFFB0BEC5)
}

/** 环状质粒图谱：骨架 + 特征弧 + 酶切刻度，可拖动旋转 */
@Composable
fun CircularMap(
    record: SeqRecord,
    enzymeNames: Set<String>,
    modifier: Modifier = Modifier,
    selection: IntRange? = null,
    selectionMode: Boolean = false,
    onSelect: (IntRange?) -> Unit = {},
    onFeatureTap: (Feature) -> Unit = {}
) {
    var rotation by remember(record.name) { mutableFloatStateOf(-90f) }
    var zoom by remember(record.name) { mutableFloatStateOf(1f) }
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val hits = remember(record.sequence, enzymeNames, record.circular) {
        Enzymes.digest(record.sequence, enzymeNames, record.circular)
    }
    val features = record.features
    var lastTap by remember { mutableStateOf<Feature?>(null) }
    var dragAnchorBp by remember { mutableStateOf<Int?>(null) }
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    // 屏幕坐标 → 碱基序号（与绘制共用同一套几何参数）
    fun bpAt(pos: Offset, w: Float, h: Float): Int? {
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f - padPx
        val dx = pos.x - cx
        val dy = pos.y - cy
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < r * 0.55f || dist > r * 1.12f) return null
        val ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return (((ang - rotation + 720f) % 360f) / 360f * record.length).toInt().coerceIn(0, record.length - 1) + 1
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(record.name, selectionMode) {
                    if (selectionMode) {
                        // 选区模式：拖动直接拉出区间，不旋转视图
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragAnchorBp = bpAt(pos, size.width.toFloat(), size.height.toFloat())
                                dragAnchorBp?.let { onSelect(it..it) }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val a = dragAnchorBp
                                val b = bpAt(change.position, size.width.toFloat(), size.height.toFloat())
                                if (a != null && b != null) onSelect(if (a <= b) a..b else b..a)
                            },
                            onDragEnd = { dragAnchorBp = null },
                            onDragCancel = { dragAnchorBp = null }
                        )
                    } else {
                        detectDragGestures { change, drag ->
                            change.consume()
                            rotation += (drag.x / 4f)
                        }
                    }
                }
                .pointerInput(record.name, features) {
                    detectTapGestures(
                        onTap = { pos ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = minOf(size.width, size.height) / 2f - 34.dp.toPx()
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (dist > r * 0.72f && dist < r * 1.06f) {
                            val ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            val bp = ((ang - rotation + 720f) % 360f) / 360f * record.length
                            val hit = features.firstOrNull { f ->
                                val s = f.start - 1
                                val e = f.end - 1
                                if (s <= e) bp >= s && bp <= e else bp >= s || bp <= e
                            }
                            if (hit != null) {
                                lastTap = hit
                                onFeatureTap(hit)
                            }
                        }
                        }
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(size.width, size.height) / 2f - 34.dp.toPx()
            val ringW = 10.dp.toPx()

            // 骨架双圈
            drawCircle(color = outline, radius = radius, center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawCircle(
                color = outline.copy(alpha = 0.5f),
                radius = radius - ringW - 4.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(1.dp.toPx())
            )

            // 特征弧
            features.forEach { f ->
                val s = (f.start - 1).toFloat() / record.length
                val e = (f.end - 1).toFloat() / record.length
                val startAngle = rotation + s * 360f
                val sweep = if (e >= s) (e - s) * 360f else (1f - s + e) * 360f
                drawArc(
                    color = featureColor(f.type),
                    startAngle = startAngle,
                    sweepAngle = sweep.coerceAtLeast(2f),
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = ringW, cap = androidx.compose.ui.graphics.StrokeCap.Butt)
                )
                // 箭头指示链方向
                if (sweep > 8f) {
                    val endRad = Math.toRadians((startAngle + sweep).toDouble())
                    val arrowColor = featureColor(f.type)
                    val px = cx + radius * cos(endRad).toFloat()
                    val py = cy + radius * sin(endRad).toFloat()
                    drawCircle(arrowColor, radius = 3.dp.toPx(), center = Offset(px, py))
                }
            }

            // 选区高亮（环带外侧加粗扇区）
            if (selection != null && selection.last > selection.first) {
                val s0 = (selection.first - 1).toFloat() / record.length
                val e0 = selection.last.toFloat() / record.length
                drawArc(
                    color = primary.copy(alpha = 0.35f),
                    startAngle = rotation + s0 * 360f,
                    sweepAngle = ((e0 - s0) * 360f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(cx - radius - ringW, cy - radius - ringW),
                    size = Size((radius + ringW) * 2, (radius + ringW) * 2),
                    style = Stroke(width = ringW * 1.8f)
                )
            }

            // 刻度：按长度选择步长
            val step = when {
                record.length <= 3000 -> 250
                record.length <= 10000 -> 500
                record.length <= 50000 -> 2000
                else -> 10000
            }
            var tick = 0
            while (tick < record.length) {
                val ang = Math.toRadians((rotation + tick.toFloat() / record.length * 360f).toDouble())
                val outer = radius + 6.dp.toPx()
                val inner = radius + 1.dp.toPx()
                drawLine(
                    color = outline,
                    start = Offset(cx + inner * cos(ang).toFloat(), cy + inner * sin(ang).toFloat()),
                    end = Offset(cx + outer * cos(ang).toFloat(), cy + outer * sin(ang).toFloat()),
                    strokeWidth = 1.dp.toPx()
                )
                if (record.length <= 5000 || tick % 5000 == 0) {
                    val tx = cx + (radius + 15.dp.toPx()) * cos(ang).toFloat()
                    val ty = cy + (radius + 15.dp.toPx()) * sin(ang).toFloat()
                    val label = textMeasurer.measure(if (tick == 0) "1" else "$tick", style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp))
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(tx - label.size.width / 2f, ty - label.size.height / 2f)
                    )
                }
                tick += step
            }

            // 酶切位点：交替半径防重叠
            hits.forEachIndexed { idx, h ->
                val ang = Math.toRadians((rotation + (h.pos - 1).toFloat() / record.length * 360f).toDouble())
                val r1 = radius + 4.dp.toPx()
                val r2 = radius + (if (idx % 2 == 0) 20.dp.toPx() else 13.dp.toPx())
                drawLine(
                    color = primary.copy(alpha = 0.9f),
                    start = Offset(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat()),
                    end = Offset(cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat()),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // 中心信息
            val title = textMeasurer.measure(record.name, style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
            drawText(
                textLayoutResult = title,
                topLeft = Offset(cx - title.size.width / 2f, cy - title.size.height - 2.dp.toPx())
            )
            val sub = textMeasurer.measure("${record.length} bp · GC ${"%.1f".format(record.gc)}%", style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp))
            drawText(textLayoutResult = sub, topLeft = Offset(cx - sub.size.width / 2f, cy + 2.dp.toPx()))
            val hint = textMeasurer.measure("拖动旋转", style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp))
            drawText(
                textLayoutResult = hint,
                topLeft = Offset(cx - hint.size.width / 2f, cy + 18.dp.toPx())
            )
        }
    }
}

/** 线性图谱：特征块 + 刻度尺 */
@Composable
fun LinearMap(
    record: SeqRecord,
    enzymeNames: Set<String>,
    modifier: Modifier = Modifier,
    selection: IntRange? = null,
    selectionMode: Boolean = false,
    onSelect: (IntRange?) -> Unit = {}
) {
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val hits = remember(record.sequence, enzymeNames) {
        Enzymes.digest(record.sequence, enzymeNames, false)
    }

    var anchorBp by remember { mutableStateOf<Int?>(null) }
    val padXPx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    fun bpAtX(x: Float, w: Float): Int {
        val padX = padXPx
        val usable = (w - padX * 2).coerceAtLeast(1f)
        val frac = ((x - padX) / usable).coerceIn(0f, 1f)
        return (frac * record.length).toInt().coerceIn(0, record.length - 1) + 1
    }

    Canvas(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .pointerInput(record.name, selectionMode) {
                if (selectionMode) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            anchorBp = bpAtX(pos.x, size.width.toFloat())
                            anchorBp?.let { onSelect(it..it) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val a = anchorBp
                            val b = bpAtX(change.position.x, size.width.toFloat())
                            if (a != null) onSelect(if (a <= b) a..b else b..a)
                        },
                        onDragEnd = { anchorBp = null },
                        onDragCancel = { anchorBp = null }
                    )
                }
            }
    ) {
        val padX = 14.dp.toPx()
        val axisY = size.height / 2f
        val usable = size.width - padX * 2
        drawLine(outline, Offset(padX, axisY), Offset(padX + usable, axisY), 2.dp.toPx())

        val step = when {
            record.length <= 3000 -> 250
            record.length <= 10000 -> 500
            record.length <= 50000 -> 2000
            else -> 10000
        }
        var tick = 0
        while (tick <= record.length) {
            val x = padX + usable * tick.toFloat() / record.length
            drawLine(outline, Offset(x, axisY - 5.dp.toPx()), Offset(x, axisY + 5.dp.toPx()), 1.dp.toPx())
            if (record.length <= 5000) {
                val label = textMeasurer.measure("$tick", style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp))
                drawText(label, topLeft = Offset(x - label.size.width / 2f, axisY + 8.dp.toPx()))
            }
            tick += step
        }

        // 选区高亮
        if (selection != null && selection.last > selection.first) {
            val sx1 = padX + usable * (selection.first - 1).toFloat() / record.length
            val sx2 = padX + usable * selection.last.toFloat() / record.length
            drawRect(primary.copy(alpha = 0.22f), Offset(sx1, 0f), Size((sx2 - sx1).coerceAtLeast(1f), size.height))
            drawLine(primary, Offset(sx1, 0f), Offset(sx1, size.height), 1.5.dp.toPx())
            drawLine(primary, Offset(sx2, 0f), Offset(sx2, size.height), 1.5.dp.toPx())
        }

        val laneH = 12.dp.toPx()
        record.features.forEachIndexed { i, f ->
            val x1 = padX + usable * (f.start - 1).toFloat() / record.length
            val x2 = padX + usable * f.end.toFloat() / record.length
            val lane = i % 3
            val y = axisY - laneH - 6.dp.toPx() - lane * (laneH + 3.dp.toPx())
            drawRect(
                color = featureColor(f.type),
                topLeft = Offset(x1, y),
                size = Size((x2 - x1).coerceAtLeast(2.dp.toPx()), laneH)
            )
            if (x2 - x1 > 40.dp.toPx()) {
                val label = textMeasurer.measure(f.name, style = androidx.compose.ui.text.TextStyle(fontSize = 8.sp))
                drawText(label, topLeft = Offset(x1 + 2.dp.toPx(), y + laneH / 2f - label.size.height / 2f))
            }
        }

        hits.forEach { h ->
            val x = padX + usable * (h.pos - 1).toFloat() / record.length
            drawLine(primary, Offset(x, axisY - 16.dp.toPx()), Offset(x, axisY + 4.dp.toPx()), 1.2.dp.toPx())
        }
    }
}
