package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqRecord
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val featureColors = listOf(
    Color(0xFF7FE7D6), Color(0xFFFFC56E), Color(0xFF9FA8FF),
    Color(0xFF7ED957), Color(0xFFFF8A80), Color(0xFF69C9F0)
)

internal fun featureColor(type: String): Color = MapPalette.colorFor(
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
// ═══════════════════════════════════════════════════════════════════════════════
// 图谱层模型 1:1 对齐 main CircularMapCanvas.svelte / LinearMapCanvas.svelte：
// 环状 9 层：①DNA 碱基块（放大可见）②骨架双圈 ②b 选区高亮 ③刻度+数字
// ④特征堆叠分层+沿弧内部标签 ⑤引物分层 ⑥轴上切点刻痕 ⑦淡导轨圈
// ⑧外圈松弛酶名指注 ⑨中心文字；双指缩放/平移/旋转 + 工具按钮（＋/－/⟲）
// 线性 7 层：①选区绿块 ②主轴 ③刻度数字 ④放大显碱基 ⑤特征区间堆叠+箭头
// ⑥特征标签防碰撞+引线 ⑦切点竖线+分层酶名；横向平移 + 双指缩放
// ═══════════════════════════════════════════════════════════════════════════════

/** main dnaColorMap（CircularMapCanvas:99 / LinearMapCanvas:284）：放大后的碱基块颜色 */
private val dnaBaseColor = mapOf(
    'A' to Color(0xFF10B981), 'C' to Color(0xFF3B82F6), 'G' to Color(0xFFEAB308),
    'T' to Color(0xFFEF4444), 'N' to Color(0xFF64748B)
)

/** main isDarkColor：感知亮度 <128 视为深色（内部标签反白） */
private fun isDarkColor(c: Color): Boolean =
    0.2126f * c.red * 255f + 0.7152f * c.green * 255f + 0.0722f * c.blue * 255f < 128f

/** main getInternalEllipsizedName（OVE：arcLength/55 − 3 字符容量省略） */
private fun internalEllipsizedName(name: String, radiusPx: Float, annHpx: Float, totalAngle: Float): String? {
    if (name.isEmpty()) return null
    val arcLength = 2f * Math.PI.toFloat() * (radiusPx - annHpx) * abs(totalAngle)
    val annLen = floor(arcLength / 55f - 3f).coerceAtLeast(0f).toInt()
    var ell = if (annLen >= name.length) name else name.take(annLen)
    if (ell.isNotEmpty() && ell != name) {
        ell = if (ell.length >= name.length - 2) name
        else if (ell.length > 3) ell + ".."
        else return null
    }
    return ell.ifEmpty { null }
}

/** main calculateYOffsets 的区间贪心分层：按起点排序，落到首个不重叠层 */
private data class LaneSlot<T>(val item: T, val lane: Int, val a: Float, val b: Float)

private fun <T> stackIntervals(items: List<T>, aOf: (T) -> Float, bOf: (T) -> Float): List<LaneSlot<T>> {
    val laneEnds = mutableListOf<Float>()
    return items.sortedBy { aOf(it) }.map { it ->
        val x0 = aOf(it)
        var x1 = bOf(it)
        if (x1 <= x0) x1 += 1f
        var lane = -1
        for (j in laneEnds.indices) if (laneEnds[j] < x0) { lane = j; break }
        if (lane == -1) { laneEnds.add(x1); lane = laneEnds.size - 1 } else laneEnds[lane] = x1
        LaneSlot(it, lane, x0, x1)
    }
}

/** 图谱工具条（对齐 main PlasmidMap 工具栏的 zoomIn/zoomOut/resetView） */
@Composable
private fun MapToolbar(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0x66000000),
        contentColor = Color(0xFFE2E8F0),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "放大", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onZoomOut, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "缩小", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "重置视图", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun CircularMap(
    record: SeqRecord,
    enzymeNames: Set<String>,
    modifier: Modifier = Modifier,
    selection: IntRange? = null,
    selectionMode: Boolean = false,
    onSelect: (IntRange?) -> Unit = {},
    onFeatureTap: (Feature) -> Unit = {},
    selectionNotes: List<SelNote> = emptyList()
) {
    var rotation by remember(record.name) { mutableFloatStateOf(-90f) }   // 画布角（0=3点方向）
    var zoom by remember(record.name) { mutableFloatStateOf(1f) }
    var panX by remember(record.name) { mutableFloatStateOf(0f) }
    var panY by remember(record.name) { mutableFloatStateOf(0f) }
    val textMeasurer = rememberTextMeasurer()
    val hits = remember(record.sequence, enzymeNames, record.circular) {
        Enzymes.digest(record.sequence, enzymeNames, record.circular)
    }
    val features = record.features
    var lastTap by remember { mutableStateOf<Feature?>(null) }
    var dragAnchorBp by remember { mutableStateOf<Int?>(null) }
    val maxZoom = maxOf(5f, record.length / 100f)
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }

    // 屏幕→几何（与绘制共享，缩放时中心下移使可见弧保持在视口内：main yOffset 公式）
    fun geometry(w: Float, h: Float): Triple<Float, Float, Float> {
        val baseR = minOf(w, h) / 2f - padPx
        val zoomed = zoom != 1f
        val wEq = minOf(w, 800f)
        val percent = (1f / zoom) * (wEq / 800f)
        val initialR = if (!zoomed) baseR
        else maxOf(baseR, w / sin(Math.PI.toFloat() * percent / 2f) / 2f)
        val yOff = if (zoomed) initialR - 50f else 0f
        return Triple(w / 2f + panX, h / 2f + yOff + panY, initialR)
    }

    fun bpAt(pos: Offset, w: Float, h: Float): Int? {
        val (cx, cy, r) = geometry(w, h)
        val dx = pos.x - cx
        val dy = pos.y - cy
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < r * 0.55f || dist > r * 1.15f) return null
        val ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return (((ang - rotation + 720f) % 360f) / 360f * record.length).toInt().coerceIn(0, record.length - 1) + 1
    }

    Box(modifier.background(Color(0xFF0B0F17))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(record.name, selectionMode) {
                    if (selectionMode) {
                        // 选区模式：拖动直接拉出区间
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
                        // 双指缩放 + 平移 + 旋转（对齐 main pinch/wheel/drag 三合一）
                        detectTransformGestures { _, pan, gestureZoom, gestureRotation ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, maxZoom)
                            rotation += Math.toDegrees(gestureRotation.toDouble()).toFloat()
                            panX += pan.x
                            panY += pan.y
                        }
                    }
                }
                .pointerInput(record.name, features) {
                    detectTapGestures(
                        onTap = { pos ->
                            val bp = bpAt(pos, size.width.toFloat(), size.height.toFloat()) ?: return@detectTapGestures
                            val hit = features.firstOrNull { f ->
                                val s = f.start - 1
                                val e = f.end - 1
                                if (s <= e) bp - 1 >= s && bp - 1 <= e else bp - 1 >= s || bp - 1 <= e
                            }
                            if (hit != null) { lastTap = hit; onFeatureTap(hit) }
                        }
                    )
                }
        ) {
            val seqLen = record.length
            val w = size.width
            val h = size.height
            val (cx, cy, initialR) = geometry(w, h)
            val zoomed = zoom != 1f
            val percent = (1f / zoom) * (minOf(w, 800f) / 800f)

            // ── 可见窗口（main rangeToShow）──
            val rangeLen = if (!zoomed) seqLen.toFloat() else seqLen * percent / 2f
            val fracTop = (((-90f - rotation) % 360f + 360f) % 360f) / 360f
            val start0 = fracTop * seqLen - rangeLen / 2f
            val showSeq = (zoomed || seqLen < 50) && rangeLen < 140f
            val showSeqText = rangeLen < 80f
            val spacing = when {
                rangeLen < 10f -> 2f
                rangeLen < 50f -> kotlin.math.ceil(rangeLen / 25f) * 5f
                else -> kotlin.math.ceil(rangeLen / 100f) * 10f
            }

            // ── 半径堆叠布局（main layout 逐层累计）──
            var r = initialR
            val seqR = r
            if (showSeq) r += 12.dp.toPx() + 10.dp.toPx()
            val axisR = r
            r += 12.dp.toPx()
            r += 14.dp.toPx()   // axisNumbers 层
            val merged = features.map { f -> CircFeat(f.name, f.start, f.end, f.type, f.strand > 0, false, f) } +
                selectionNotes.map { n -> CircFeat(n.text, n.start, n.end, "note", true, true, null) }
            val featSlots = stackIntervals(merged, { f -> (f.start - 1).toFloat() / seqLen }, { f -> f.end.toFloat() / seqLen })
            val featLanes = (featSlots.maxOfOrNull { it.lane } ?: -1) + 1
            val featR = r
            if (featLanes > 0) r += featLanes * 14.dp.toPx() + 10.dp.toPx()
            val primSlots = stackIntervals(record.primers, { p -> (p.start - 1).toFloat() / seqLen }, { p -> (p.end).toFloat() / seqLen })
            val primLanes = (primSlots.maxOfOrNull { it.lane } ?: -1) + 1
            val primR = r
            if (primLanes > 0) r += primLanes * 14.dp.toPx() + 10.dp.toPx()
            val finalR = r

            val monoStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 6.5.sp, color = Color(0xFF94A3B8)
            )

            // ── 1. DNA 碱基块（main: showSeq 时彩色块 + 可读字母）──
            if (showSeq) {
                val bw = 7.dp.toPx()
                val bh = if (showSeqText) 14.dp.toPx() else 11.dp.toPx()
                val letterStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 5.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                for (i in 0 until rangeLen.toInt()) {
                    val pos0 = (((start0 + i) % seqLen).toInt() + seqLen) % seqLen
                    val letter = record.sequence[pos0].uppercaseChar()
                    val ang = Math.toRadians((rotation + pos0.toFloat() / seqLen * 360f).toDouble())
                    val p = Offset(cx + seqR * cos(ang).toFloat(), cy + seqR * sin(ang).toFloat())
                    drawRect(
                        color = (dnaBaseColor[letter] ?: Color(0xFF888888)).copy(alpha = 0.85f),
                        topLeft = Offset(p.x - bw / 2f, p.y - bh / 2f),
                        size = Size(bw, bh)
                    )
                    if (showSeqText) {
                        val t = textMeasurer.measure(letter.toString(), letterStyle)
                        drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
                    }
                }
            }

            // ── 2. 骨架双圈（main: axisR ± 3，#475569 lw1）──
            drawCircle(color = Color(0xFF475569), radius = axisR + 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawCircle(color = Color(0xFF475569), radius = axisR - 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

            // ── 2b. 选区高亮（main: 外发光 lw14 α.22 + 内实线 lw7 α.55 + 径向端刻线）──
            if (selection != null && selection.last >= selection.first) {
                val sAng = rotation + (selection.first - 1).toFloat() / seqLen * 360f
                val eAng = rotation + (selection.last - 1).toFloat() / seqLen * 360f
                val sweep = ((eAng - sAng) % 360f + 360f) % 360f
                drawArc(
                    color = Color(0x380A84FF), startAngle = sAng, sweepAngle = sweep.coerceAtLeast(1f),
                    useCenter = false, topLeft = Offset(cx - axisR, cy - axisR), size = Size(axisR * 2, axisR * 2),
                    style = Stroke(14.dp.toPx())
                )
                drawArc(
                    color = Color(0x8C0A84FF), startAngle = sAng, sweepAngle = sweep.coerceAtLeast(1f),
                    useCenter = false, topLeft = Offset(cx - axisR, cy - axisR), size = Size(axisR * 2, axisR * 2),
                    style = Stroke(7.dp.toPx())
                )
                listOf(sAng, eAng).forEach { a ->
                    val rad = Math.toRadians(a.toDouble())
                    val ux = cos(rad).toFloat(); val uy = sin(rad).toFloat()
                    drawLine(
                        color = Color(0xCC0A84FF),
                        start = Offset(cx + (axisR - 8.dp.toPx()) * ux, cy + (axisR - 8.dp.toPx()) * uy),
                        end = Offset(cx + (axisR + 8.dp.toPx()) * ux, cy + (axisR + 8.dp.toPx()) * uy),
                        strokeWidth = 1.5f
                    )
                }
            }

            // ── 3. 刻度 + 数字（main: 径向 r±6，数字 r+14，6.5px #94a3b8）──
            var t0 = kotlin.math.ceil(start0 / spacing) * spacing
            while (t0 < start0 + rangeLen) {
                val pos0 = (((t0 % seqLen).toInt() + seqLen) % seqLen)
                val ang = Math.toRadians((rotation + pos0.toFloat() / seqLen * 360f).toDouble())
                val ux = cos(ang).toFloat(); val uy = sin(ang).toFloat()
                drawLine(
                    color = Color(0xFF64748B),
                    start = Offset(cx + (axisR - 6.dp.toPx()) * ux, cy + (axisR - 6.dp.toPx()) * uy),
                    end = Offset(cx + (axisR + 6.dp.toPx()) * ux, cy + (axisR + 6.dp.toPx()) * uy),
                    strokeWidth = 1f
                )
                val label = textMeasurer.measure("${pos0 + 1}", monoStyle)
                drawText(
                    label,
                    topLeft = Offset(
                        cx + (axisR + 14.dp.toPx()) * ux - label.size.width / 2f,
                        cy + (axisR + 14.dp.toPx()) * uy - label.size.height / 2f
                    )
                )
                t0 += spacing
            }

            // ── 4. 特征堆叠 + 沿弧内部标签（main: 逐层 14px，白色描边高亮 active）──
            featSlots.forEach { slot ->
                val f = slot.item
                val col = if (f.isNote) Color(0xFF4CD6FF) else featureColor(f.type)
                val fr = featR + slot.lane * 14.dp.toPx()
                val sAng = rotation + slot.a * 360f
                val sweep = ((slot.b - slot.a) * 360f).coerceAtLeast(2f)
                drawArc(
                    color = col.copy(alpha = 0.7f),
                    startAngle = sAng, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(cx - fr, cy - fr), size = Size(fr * 2, fr * 2),
                    style = Stroke(10.dp.toPx())
                )
                if (f.origin === lastTap) {
                    drawArc(
                        color = Color.White,
                        startAngle = sAng, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(cx - fr, cy - fr), size = Size(fr * 2, fr * 2),
                        style = Stroke(1.dp.toPx())
                    )
                }
                // 方向端点（fwd 尾端 / rev 首端）
                val dirAng = Math.toRadians((if (f.forward) sAng + sweep else sAng).toDouble())
                drawCircle(col, radius = 2.dp.toPx(), center = Offset(cx + fr * cos(dirAng).toFloat(), cy + fr * sin(dirAng).toFloat()))
                // 内部标签：容量省略 + 下半弧翻转 + 深浅反色（main drawInternalLabel）
                val totalAngle = Math.toRadians(sweep.toDouble()).toFloat()
                val name = internalEllipsizedName(f.name, fr, 10.dp.toPx(), totalAngle)
                if (name != null) {
                    val midDeg = sAng + sweep / 2f
                    val midRad = Math.toRadians(midDeg.toDouble())
                    val p = Offset(cx + fr * cos(midRad).toFloat(), cy + fr * sin(midRad).toFloat())
                    val flip = sin(midRad).toFloat() > 0f   // 画布 y 向下：下半弧翻转
                    val style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 6.sp,
                        color = if (isDarkColor(col)) Color.White else Color(0xFF111827)
                    )
                    val layout = textMeasurer.measure(name, style)
                    rotate(degrees = midDeg + 90f + (if (flip) 180f else 0f), pivot = p) {
                        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                    }
                }
            }

            // ── 5. 引物分层（main: rgba(6,182,212,.45) 填充 / #06b6d4 描边 α0.6）──
            primSlots.forEach { slot ->
                val p = slot.item
                val pr = primR + slot.lane * 14.dp.toPx()
                val sAng = rotation + slot.a * 360f
                val sweep = ((slot.b - slot.a) * 360f).coerceAtLeast(2f)
                drawArc(
                    color = Color(0x9906B6D4),
                    startAngle = sAng, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(cx - pr, cy - pr), size = Size(pr * 2, pr * 2),
                    style = Stroke(9.dp.toPx())
                )
                val dirAng = Math.toRadians((if (p.forward) sAng + sweep else sAng).toDouble())
                drawCircle(Color(0xFF06B6D4), radius = 2.dp.toPx(), center = Offset(cx + pr * cos(dirAng).toFloat(), cy + pr * sin(dirAng).toFloat()))
            }

            // ── 6. 轴上切点刻痕（main fillRect(-0.5,-6,1,12)）──
            hits.forEach { h2 ->
                val ang = Math.toRadians((rotation + (h2.pos - 1).toFloat() / seqLen * 360f).toDouble())
                val c = enzymeColor(h2.enzyme)
                val ux = cos(ang).toFloat(); val uy = sin(ang).toFloat()
                drawLine(
                    color = c,
                    start = Offset(cx + (axisR - 6.dp.toPx()) * ux, cy + (axisR - 6.dp.toPx()) * uy),
                    end = Offset(cx + (axisR + 6.dp.toPx()) * ux, cy + (axisR + 6.dp.toPx()) * uy),
                    strokeWidth = 1.5f
                )
            }

            // ── 7. 淡同心导轨圈（main: finalR+10 / +22，rgba(255,255,255,0.018)）──
            drawCircle(color = Color(0x0AFFFFFF), radius = finalR + 10.dp.toPx(), center = Offset(cx, cy), style = Stroke(0.5f))
            drawCircle(color = Color(0x0AFFFFFF), radius = finalR + 22.dp.toPx(), center = Offset(cx, cy), style = Stroke(0.5f))

            // ── 8. 外圈松弛酶名指注（1.7.8 已 1:1 移植 relaxCutLabels，保留）──
            if (hits.isNotEmpty()) {
                val fontPx = 8.sp.toPx()
                val labelSpacing = fontPx * 2.2f
                val trackR = finalR + 22.dp.toPx()
                val leaderR = axisR + 10.dp.toPx()
                val raw = hits.map { h2 ->
                    val ang = Math.toRadians((rotation + (h2.pos - 1).toFloat() / seqLen * 360f).toDouble())
                    val ux = cos(ang).toFloat()
                    val uy = sin(ang).toFloat()
                    CutLabel(
                        text = h2.enzyme, color = enzymeColor(h2.enzyme), theta = ang.toFloat(),
                        inner = Offset(cx + axisR * ux, cy + axisR * uy),
                        outer = Offset(cx + leaderR * ux, cy + leaderR * uy)
                    ).also {
                        it.x = trackR * ux
                        it.y = trackR * uy
                    }
                }
                val leaderColor = Color(0x2EFFFFFF)
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
                    drawLine(leaderColor, l.inner, l.outer, 0.65f)
                    drawLine(leaderColor, l.outer, anchor, 0.65f)
                    drawCircle(l.color, radius = 1.2f, center = anchor)
                    drawText(
                        textLayout,
                        topLeft = Offset(startX, cy + l.y - textLayout.size.height / 2f)
                    )
                }
            }

            // ── 9. 中心文字（main: 放大时隐藏；名称 #38bdf8 bold + "N bps"/"circular"）──
            if (!zoomed) {
                val title = textMeasurer.measure(
                    record.name,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 9.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF38BDF8)
                    )
                )
                drawText(title, topLeft = Offset(cx - title.size.width / 2f, cy - 4.dp.toPx() - title.size.height / 2f))
                val sub1 = textMeasurer.measure(
                    "$seqLen bps",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 7.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                )
                drawText(sub1, topLeft = Offset(cx - sub1.size.width / 2f, cy + 8.dp.toPx() - sub1.size.height / 2f))
                val sub2 = textMeasurer.measure(
                    "circular",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 7.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                )
                drawText(sub2, topLeft = Offset(cx - sub2.size.width / 2f, cy + 15.dp.toPx() - sub2.size.height / 2f))
            }
        }

        // 工具条（右上角悬浮）
        MapToolbar(
            onZoomIn = { zoom = (zoom * 1.3f).coerceIn(1f, maxZoom) },
            onZoomOut = { zoom = (zoom / 1.3f).coerceIn(1f, maxZoom) },
            onReset = { zoom = 1f; rotation = -90f; panX = 0f; panY = 0f },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        )
    }
}

/** 环状图谱特征统一包装（选区注释以 type 'note' 并入，color #4cd6ff；origin 保留原 Feature 供高亮） */
private class CircFeat(
    val name: String, val start: Int, val end: Int, val type: String,
    val forward: Boolean, val isNote: Boolean, val origin: Feature?
)

// ───────────────────────── 线性图谱（main LinearMapCanvas 7 层）─────────────────────────

@Composable
fun LinearMap(
    record: SeqRecord,
    enzymeNames: Set<String>,
    modifier: Modifier = Modifier,
    selection: IntRange? = null,
    selectionMode: Boolean = false,
    onSelect: (IntRange?) -> Unit = {},
    selectionNotes: List<SelNote> = emptyList()
) {
    val textMeasurer = rememberTextMeasurer()
    val hits = remember(record.sequence, enzymeNames) { Enzymes.digest(record.sequence, enzymeNames, false) }
    val seqLen = record.length
    var zoomScale by remember(record.name) { mutableFloatStateOf(1f) }
    var panBp by remember(record.name) { mutableFloatStateOf(0f) }
    val maxZoom = maxOf(1f, seqLen / 5f)
    var anchorBp by remember { mutableStateOf<Int?>(null) }

    fun clampPan() {
        val maxPan = maxOf(0f, seqLen - seqLen / zoomScale)
        panBp = panBp.coerceIn(0f, maxPan)
    }

    Box(modifier.background(Color(0xFF0B0F17))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(record.name, selectionMode) {
                    if (selectionMode) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val x0 = 30.dp.toPx()
                                val linW = (size.width - 60.dp.toPx()).coerceAtLeast(10f)
                                val a = (panBp + 1 + (pos.x - x0) / (linW * zoomScale) * seqLen).toInt().coerceIn(1, seqLen)
                                anchorBp = a
                                onSelect(a..a)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val x0 = 30.dp.toPx()
                                val linW = (size.width - 60.dp.toPx()).coerceAtLeast(10f)
                                val b = (panBp + 1 + (change.position.x - x0) / (linW * zoomScale) * seqLen)
                                    .toInt().coerceIn(1, seqLen)
                                val a = anchorBp
                                if (a != null) onSelect(if (a <= b) a..b else b..a)
                            },
                            onDragEnd = { anchorBp = null },
                            onDragCancel = { anchorBp = null }
                        )
                    } else {
                        // 双指缩放 + 横向平移（main: zoomScale / panBp 视图变换）
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoomScale = (zoomScale * gestureZoom).coerceIn(1f, maxZoom)
                            val linW = (size.width - 60.dp.toPx()).coerceAtLeast(10f)
                            panBp -= pan.x * seqLen / (linW * zoomScale)
                            clampPan()
                        }
                    }
                }
        ) {
            if (seqLen <= 0) return@Canvas
            clampPan()
            val x0 = 30.dp.toPx()
            val axisY = maxOf(80.dp.toPx(), size.height * 0.55f)
            val x1 = size.width - 30.dp.toPx()
            val linW = (x1 - x0).coerceAtLeast(10f)
            val span = seqLen / zoomScale
            val visStart = panBp + 1f
            val visEnd = minOf(seqLen.toFloat(), panBp + span)
            val bpPixel = linW * zoomScale / maxOf(1, seqLen)

            fun linX(pos: Float): Float = x0 + ((pos - 1f - panBp) / seqLen) * linW * zoomScale

            // main niceTickStep：span/8 → 1/2/5/10 量级取整
            val rawStep = span / 8f
            val mag = 10f.pow(floor(log10(rawStep)))
            val normStep = rawStep / mag
            val tickStep = (if (normStep < 1.5f) 1f else if (normStep < 3f) 2f else if (normStep < 7f) 5f else 10f) * mag

            // ── 1. 选区高亮（main: 绿色半透明矩形 y=axisY−45 h95）──
            if (selection != null && selection.last >= selection.first) {
                val sx1 = linX(selection.first.toFloat())
                val sx2 = linX(selection.last.toFloat())
                drawRect(
                    color = Color(0x2E53D769), topLeft = Offset(sx1, axisY - 45.dp.toPx()),
                    size = Size((sx2 - sx1).coerceAtLeast(1f), 95.dp.toPx())
                )
                drawRect(
                    color = Color(0x6653D769), topLeft = Offset(sx1, axisY - 45.dp.toPx()),
                    size = Size((sx2 - sx1).coerceAtLeast(1f), 95.dp.toPx()),
                    style = Stroke(1f)
                )
            }

            // ── 2. 主轴脊线（main: #1e293b lw7 圆头）──
                drawLine(
                    color = Color(0xFF1E293B), start = Offset(x0, axisY), end = Offset(x1, axisY),
                    strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
                )

            // ── 3. 刻度 + 数字（main: 刻线 y−8..+4，数字 y−14）──
            val tickStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 6.5.sp,
                color = Color(0xFF64748B)
            )
            var p = kotlin.math.ceil((visStart - 1f) / tickStep) * tickStep + 1f
            while (p <= visEnd) {
                val x = linX(p)
                if (x >= x0 - 5f && x <= x1 + 5f) {
                    drawLine(Color(0xFF64748B), Offset(x, axisY - 8.dp.toPx()), Offset(x, axisY + 4.dp.toPx()), 1f)
                    val label = textMeasurer.measure("${p.roundToInt()}", tickStyle)
                    drawText(label, topLeft = Offset(x - label.size.width / 2f, axisY - 14.dp.toPx() - label.size.height / 2f))
                }
                p += tickStep
            }

            // ── 4. 放大显碱基（main: bpPixel > 6px 时彩色块 + 字母）──
            if (bpPixel > 6f) {
                val letterStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 6.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color(0xFF0F172A)
                )
                val sIdx = maxOf(1f, floor(visStart)).toInt()
                val eIdx = minOf(seqLen.toFloat(), kotlin.math.ceil(visEnd)).toInt()
                for (i in sIdx..eIdx) {
                    val x = linX(i.toFloat())
                    if (x < x0 - 5f || x > x1 + 5f) continue
                    val letter = record.sequence[i - 1].uppercaseChar()
                    drawRect(
                        color = dnaBaseColor[letter] ?: Color(0xFF888888),
                        topLeft = Offset(x - bpPixel / 2f + 0.5f, axisY - 9.dp.toPx()),
                        size = Size(bpPixel - 1f, 18.dp.toPx())
                    )
                    val t = textMeasurer.measure(letter.toString(), letterStyle)
                    drawText(t, topLeft = Offset(x - t.size.width / 2f, axisY - t.size.height / 2f))
                }
            }

            // ── 5. 特征区间堆叠 + 箭头（main: 合并 selectionNotes；trackY = axisY−28−lane*14）──
            val merged = record.features.map { LinearFeat(it.name, it.start.toFloat(), (it.end + 1).toFloat(), featureColor(it.type), it.strand > 0) } +
                selectionNotes.map { LinearFeat(it.text, it.start.toFloat(), (it.end + 1).toFloat(), Color(0xFF4CD6FF), true) }
            val featSlots = stackIntervals(merged, { it.a }, { it.b })
            val maxFeatLane = (featSlots.maxOfOrNull { it.lane } ?: -1) + 1
            featSlots.forEach { slot ->
                val f = slot.item
                val fx0 = linX(f.a)
                val fx1 = linX(f.b)
                val fw = maxOf(fx1 - fx0, 6.dp.toPx())
                val trackY = axisY - 28.dp.toPx() - slot.lane * 14.dp.toPx()
                drawRoundRect(
                    color = f.color.copy(alpha = 0.85f),
                    topLeft = Offset(fx0, trackY - 5.dp.toPx()),
                    size = Size(fw, 10.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = Color(0x4D000000),
                    topLeft = Offset(fx0, trackY - 5.dp.toPx()),
                    size = Size(fw, 10.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = Stroke(0.5f)
                )
                // 箭头三角（fwd 指向右端，rev 指向左端）
                val path = Path()
                if (f.forward) {
                    val tip = fx0 + fw
                    path.moveTo(tip, trackY)
                    path.lineTo(tip - 6.dp.toPx(), trackY - 5.dp.toPx())
                    path.lineTo(tip - 6.dp.toPx(), trackY + 5.dp.toPx())
                } else {
                    val tip = fx0
                    path.moveTo(tip, trackY)
                    path.lineTo(tip + 6.dp.toPx(), trackY - 5.dp.toPx())
                    path.lineTo(tip + 6.dp.toPx(), trackY + 5.dp.toPx())
                }
                path.close()
                drawPath(path, f.color)
            }

            // ── 6. 特征标签防碰撞 + 引线（main: labelY = 顶部轨道 − 8 − lane*12）──
            if (featSlots.isNotEmpty()) {
                val labelStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 6.5.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                val measured = featSlots.map { slot ->
                    val layout = textMeasurer.measure(slot.item.name, labelStyle.copy(color = slot.item.color))
                    val midX = (linX(slot.item.a) + linX(slot.item.b)) / 2f
                    Triple(slot, midX, layout)
                }
                // 标签盒贪心分层
                val boxes = stackIntervals(measured, { (_, midX, layout) -> midX - layout.size.width / 2f }, { (_, midX, layout) -> midX + layout.size.width / 2f })
                val trackTop = axisY - 28.dp.toPx() - maxFeatLane * 14.dp.toPx()
                boxes.forEach { box ->
                    val (slot, midX, layout) = box.item
                    val labelY = trackTop - 8.dp.toPx() - box.lane * 12.dp.toPx()
                    drawText(
                        layout,
                        topLeft = Offset(midX - layout.size.width / 2f, labelY - layout.size.height)
                    )
                    drawLine(
                        color = slot.item.color,
                        start = Offset(midX, labelY + 1f),
                        end = Offset(midX, axisY - 28.dp.toPx() - slot.lane * 14.dp.toPx() + 5.dp.toPx()),
                        strokeWidth = 0.5f
                    )
                }
            }

            // ── 7. 切点竖线 + 分层酶名（main: 刻线 y−10..+12，标签 y+18+lane*12）──
            val sortedHits = hits.sortedBy { it.pos }
            val itemHalf = 20.dp.toPx()
            val laneEnd = mutableListOf<Float>()
            val laneOf = IntArray(sortedHits.size)
            sortedHits.forEachIndexed { i, h3 ->
                val x = linX(h3.pos.toFloat())
                val lx0 = x - itemHalf
                val lx1 = x + itemHalf
                var lane = -1
                for (j in laneEnd.indices) if (laneEnd[j] < lx0) { lane = j; break }
                if (lane == -1) { laneEnd.add(lx1); lane = laneEnd.size - 1 } else laneEnd[lane] = lx1
                laneOf[i] = lane
            }
            sortedHits.forEachIndexed { i, h3 ->
                val x = linX(h3.pos.toFloat())
                val c = enzymeColor(h3.enzyme)
                drawLine(c, Offset(x, axisY - 10.dp.toPx()), Offset(x, axisY + 12.dp.toPx()), 1.dp.toPx())
                val textLayout = textMeasurer.measure(
                    h3.enzyme,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 6.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = c
                    )
                )
                val labelY = axisY + 18.dp.toPx() + laneOf[i] * 12.dp.toPx()
                drawText(textLayout, topLeft = Offset(x - textLayout.size.width / 2f, labelY))
            }
        }

        MapToolbar(
            onZoomIn = { zoomScale = (zoomScale * 1.3f).coerceIn(1f, maxZoom); clampPan() },
            onZoomOut = { zoomScale = (zoomScale / 1.3f).coerceIn(1f, maxZoom); clampPan() },
            onReset = { zoomScale = 1f; panBp = 0f },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
        )
    }
}

/** 线性图谱特征（合并 selectionNotes 时的统一表示） */
private class LinearFeat(val name: String, val a: Float, val b: Float, val color: Color, val forward: Boolean)
