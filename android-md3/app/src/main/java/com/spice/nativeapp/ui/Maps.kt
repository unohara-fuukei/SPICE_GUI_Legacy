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

private fun featureColor(type: String): Color = MapPalette.colorFor(
    when (type.lowercase()) {
        "cds" -> "CDS"
        "promoter", "terminator" -> type.lowercase()
        "primer", "primer_bind", "rbs" -> "primer"
        "rep_origin", "origin" -> "rep_origin"
        "misc", "misc_marker", "misc_binding", "misc_feature", "protein_bind" -> "misc"
        else -> type
    }
)

/** 酶名 10 色环哈希（对齐 main getEnzymeColor / gene +page.svelte:1907） */
internal val enzymePalette = listOf(
    Color(0xFFFF5D5D), Color(0xFFFF9F1C), Color(0xFFFFD23F), Color(0xFF4CD6FF), Color(0xFFB48CFF),
    Color(0xFF53D769), Color(0xFFFF5DF6), Color(0xFF53D7A9), Color(0xFFFF9E9E), Color(0xFF5DF6FF)
)

internal fun enzymeColor(name: String): Color {
    var hash = 0
    for (c in name) hash = c.code + ((hash shl 5) - hash)
    return enzymePalette[Math.abs(hash) % enzymePalette.size]
}

/** 环状图谱酶名标签（对齐 main CircularMapCanvas OveLabel 语义） */
private class CutLabel(
    val text: String,
    val color: Color,
    val theta: Float,          // 画布角：0=3 点方向，顺时针（y 向下）
    val inner: Offset,         // 引线第 1 点（轴上切点）
    val outer: Offset,         // 引线第 2 点（外扩 10dp）
    var x: Float = 0f,         // 标签轨道相对坐标（relax 前为自然位置）
    var y: Float = 0f,
    var subs: Int = 0          // 合并进来的溢出标签数（显示 "+N," 前缀）
) {
    fun displayText(): String = if (subs > 0) "+$subs,$text" else text
}

/** main 角度约定（0=12 点钟，顺时针）——由画布角换算 */
private fun mainAngleOf(theta: Float): Float {
    val twoPi = (2.0 * Math.PI).toFloat()
    var a = (theta + (Math.PI / 2.0).toFloat()) % twoPi
    if (a < 0f) a += twoPi
    return a
}

/**
 * 1:1 移植 main geometry.ts relaxLabelAngles：
 * 四象限槽位避让（spacing 间隔槽位、x 收敛到 sqrt(maxR²−y²)），
 * 超出半径的标签合并进上一个标签的 "+N," 子标签。
 */
private fun relaxCutLabels(labels: List<CutLabel>, spacing: Float, maxR: Float): List<CutLabel> {
    val halfPi = (Math.PI / 2.0).toFloat()
    val pi = Math.PI.toFloat()
    val threeHalfPi = (3.0 * Math.PI / 2.0).toFloat()
    val maxPerQuadrant = (maxR / spacing).toInt() + 4

    val rightTop = mutableListOf<CutLabel>()
    val rightBottom = mutableListOf<CutLabel>()
    val leftBottom = mutableListOf<CutLabel>()
    val leftTop = mutableListOf<CutLabel>()
    labels.forEach { l ->
        val a = mainAngleOf(l.theta)
        when {
            a <= halfPi -> rightTop.add(l)
            a <= pi -> rightBottom.add(l)
            a <= threeHalfPi -> leftBottom.add(l)
            else -> leftTop.add(l)
        }
    }

    fun reposition(arr: List<CutLabel>): List<CutLabel> {
        val extra = (maxPerQuadrant - arr.size).coerceAtLeast(0)
        var lastY = 0f - spacing / 2f
        var last: CutLabel? = null
        val res = mutableListOf<CutLabel>()
        arr.forEachIndexed { idx, label ->
            if (kotlin.math.abs(lastY) > maxR + 80f) {
                // 槽位用尽：并入上一个标签，渲染为 "+N," 前缀（对齐 main labelAndSublabels）
                last?.subs = (last?.subs ?: 0) + 1
                return@forEachIndexed
            }
            last = label
            if (label.y < lastY) {
                val naturalSlot = (kotlin.math.abs(label.y) / spacing).toInt()
                if (naturalSlot > extra) {
                    if (idx < naturalSlot && extra > 0) lastY = label.y
                    label.y = lastY
                }
                val x = kotlin.math.sqrt(maxR * maxR - label.y * label.y)
                label.x = if (label.x > 0) x else -x
                lastY = label.y - spacing
            } else {
                label.y = lastY
                lastY = label.y - spacing
            }
            res.add(label)
        }
        return res
    }

    fun flipY(arr: List<CutLabel>) = arr.map { it.y = -it.y; it }

    val out = mutableListOf<CutLabel>()
    // Q1 右上：角降序（自下而上推向 12 点）
    out += reposition(rightTop.sortedByDescending { mainAngleOf(it.theta) })
    // Q2 右下：角升序，翻到上半处理后翻回
    out += flipY(reposition(flipY(rightBottom.sortedBy { mainAngleOf(it.theta) })))
    // Q3 左下：角降序，翻到上半处理后翻回
    out += flipY(reposition(flipY(leftBottom.sortedByDescending { mainAngleOf(it.theta) })))
    // Q4 左上：角升序
    out += reposition(leftTop.sortedBy { mainAngleOf(it.theta) })
    return out
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

    Box(modifier.background(Color(0xFF0B0F17))) {
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

            // 酶切位点：轴上径向刻痕（对齐 main CircularMapCanvas 第 6 步 fillRect(-0.5,-6,1,12)）
            val tickHalf = 6.dp.toPx()
            hits.forEach { h ->
                val ang = Math.toRadians((rotation + (h.pos - 1).toFloat() / record.length * 360f).toDouble())
                val c = enzymeColor(h.enzyme)
                val ux = cos(ang).toFloat()
                val uy = sin(ang).toFloat()
                drawLine(
                    color = c,
                    start = Offset(cx + (radius - tickHalf) * ux, cy + (radius - tickHalf) * uy),
                    end = Offset(cx + (radius + tickHalf) * ux, cy + (radius + tickHalf) * uy),
                    strokeWidth = 1.5f
                )
            }

            // 酶名外圈指注（1:1 对齐 main relaxedLabels：外圈轨道标签 + 三段引线 + 四象限槽位避让 + "+N," 合并）
            if (hits.isNotEmpty()) {
                val fontPx = 8.sp.toPx()
                val labelSpacing = fontPx * 2.2f
                val trackR = radius + 22.dp.toPx()
                val leaderR = radius + 10.dp.toPx()
                val raw = hits.map { h ->
                    val ang = Math.toRadians((rotation + (h.pos - 1).toFloat() / record.length * 360f).toDouble())
                    val ux = cos(ang).toFloat()
                    val uy = sin(ang).toFloat()
                    CutLabel(
                        text = h.enzyme, color = enzymeColor(h.enzyme), theta = ang.toFloat(),
                        inner = Offset(cx + radius * ux, cy + radius * uy),
                        outer = Offset(cx + leaderR * ux, cy + leaderR * uy)
                    ).also {
                        it.x = trackR * ux
                        it.y = trackR * uy
                    }
                }
                val leaderColor = Color(0x2EFFFFFF) // main leader: rgba(255,255,255,0.18)
                relaxCutLabels(raw, labelSpacing, trackR).forEach { l ->
                    val onLeft = mainAngleOf(l.theta) > Math.PI.toFloat()
                    val textLayout = textMeasurer.measure(
                        l.displayText(),
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 8.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = l.color
                        )
                    )
                    val textW = textLayout.size.width.toFloat()
                    val startX = cx + l.x - (if (onLeft) textW else 0f)
                    val anchor = Offset(if (onLeft) startX + textW else startX, cy + l.y)
                    // 引线：inner → outer → anchor（main leaderPoints 折线）
                    drawLine(leaderColor, l.inner, l.outer, 0.65f)
                    drawLine(leaderColor, l.outer, anchor, 0.65f)
                    // 锚点圆点 + 酶名文本
                    drawCircle(l.color, radius = 1.2f, center = anchor)
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(startX, cy + l.y - textLayout.size.height / 2f)
                    )
                }
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
            .background(Color(0xFF0B0F17))
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
                val label = textMeasurer.measure(f.name, style = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, color = Color(0xFFE2E8F0)))
                drawText(label, topLeft = Offset(x1 + 2.dp.toPx(), y + laneH / 2f - label.size.height / 2f))
            }
        }

        // 酶名标签碰撞分层（对齐 main LinearMapCanvas calculateLabelBoxes：x 区间重叠则 yOffset+1）
        val sortedHits = hits.sortedBy { it.pos }
        val itemHalf = 20.dp.toPx()
        val laneEnd = mutableListOf<Float>()
        val laneOf = IntArray(sortedHits.size)
        sortedHits.forEachIndexed { i, h ->
            val x = padX + usable * (h.pos - 1).toFloat() / record.length
            val x0 = x - itemHalf
            val x1 = x + itemHalf
            var lane = -1
            for (j in laneEnd.indices) if (laneEnd[j] < x0) { lane = j; break }
            if (lane == -1) { laneEnd.add(x1); lane = laneEnd.size - 1 } else laneEnd[lane] = x1
            laneOf[i] = lane
        }
        sortedHits.forEachIndexed { i, h ->
            val x = padX + usable * (h.pos - 1).toFloat() / record.length
            val c = enzymeColor(h.enzyme)
            // 竖直刻痕穿过主轴（main: BACKBONE_Y-10 → +12）
            drawLine(c, Offset(x, axisY - 10.dp.toPx()), Offset(x, axisY + 12.dp.toPx()), 1.dp.toPx())
            // 酶名居中在刻痕下方，分层错开（main: labelY = BACKBONE_Y+18 + yOffset*12）
            val textLayout = textMeasurer.measure(
                h.enzyme,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 7.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = c
                )
            )
            val labelY = axisY + 18.dp.toPx() + laneOf[i] * 12.dp.toPx()
            drawText(textLayout, topLeft = Offset(x - textLayout.size.width / 2f, labelY))
        }
    }
}
