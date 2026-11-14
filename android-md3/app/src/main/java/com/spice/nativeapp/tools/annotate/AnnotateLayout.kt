package com.spice.nativeapp.tools.annotate

/*
 * 图形几何，1:1 移植 main 的 src/lib/tools/annotate/layout.ts。
 * 预览画布、命中测试与栅格/矢量导出共用同一套 FigureGeometry 与标注边界，
 * 坐标统一为 96 dpi CSS 像素（1 图形毫米 = PX_PER_MM 像素）。
 */
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Point(val x: Double, val y: Double)

data class Box(val x: Double, val y: Double, val w: Double, val h: Double)

data class FrameLabel(
    val text: String,
    val x: Double,
    val y: Double,
    val fontPx: Double,
    val align: String, // 'left' | 'center' | 'right'
    val baseline: String, // 'top' | 'bottom'
)

data class PanelFrame(
    val panel: Panel,
    val index: Int,
    val row: Int,
    val col: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val label: FrameLabel,
)

data class FigureGeometry(
    val width: Double,
    val height: Double,
    val frames: List<PanelFrame>,
    val frameById: Map<String, PanelFrame>,
    val rowCount: Int,
    val colCount: Int,
)

data class AnnotationMeta(
    val id: String,
    val box: Box,
    val fontPx: Double,
    val lines: List<String>,
    val barLength: Double?,
    val frame: PanelFrame?,
)

data class BarLayout(
    val x: Double,
    val y: Double,
    val length: Double,
    val label: String,
    val fontPx: Double,
    val frame: PanelFrame?,
    val panel: Panel?,
)

/**
 * 原版 measureWidth 用浏览器 canvas measureText；安卓生产路径用 android.graphics.Paint。
 * 测量函数可注入：parity 测试注入与 Node 参考一致的确定性 stub。
 */
var textMeasurer: (text: String, fontPx: Double, family: String) -> Double = { text, fontPx, family ->
    if (text.isEmpty()) 0.0
    else {
        val paint = Paint()
        paint.textSize = fontPx.toFloat()
        paint.isAntiAlias = true
        paint.typeface = when {
            family.contains("Times", true) || family.contains("Cambria", true) ->
                android.graphics.Typeface.SERIF
            else -> android.graphics.Typeface.SANS_SERIF
        }
        paint.measureText(text).toDouble()
    }
}

fun measureWidth(text: String, fontPx: Double, family: String): Double =
    if (text.isEmpty()) 0.0 else textMeasurer(text, fontPx, family)

fun normalizeBox(a: Box): Box {
    val x = min(a.x, a.x + a.w)
    val y = min(a.y, a.y + a.h)
    return Box(x, y, abs(a.w), abs(a.h))
}

fun unionBox(a: Box, b: Box): Box {
    val x = min(a.x, b.x)
    val y = min(a.y, b.y)
    val r = max(a.x + a.w, b.x + b.w)
    val d = max(a.y + a.h, b.y + b.h)
    return Box(x, y, r - x, d - y)
}

fun panelAspect(panel: Panel): Double {
    val crop = safeCrop(panel)
    val cw = 1 - crop.left - crop.right
    val ch = 1 - crop.top - crop.bottom
    return (cw * panel.width) / max(1.0, ch * panel.height)
}

private fun frameLabel(
    frame: PanelFrame,
    text: String,
    layout: LayoutSettings,
): FrameLabel {
    val fontPx = layout.labelFontSizePt * PT_TO_PX
    val pad = max(2.0, fontPx * 0.2)
    val pos = layout.labelPosition
    val left = pos == "tl" || pos == "bl"
    val right = pos == "tr" || pos == "br"
    val top = pos == "tl" || pos == "tc" || pos == "tr"
    val x = if (left) frame.x + pad else if (right) frame.x + frame.w - pad else frame.x + frame.w / 2
    val y = if (top) frame.y + pad else frame.y + frame.h - pad
    return FrameLabel(
        text = text, x = x, y = y, fontPx = fontPx,
        align = if (left) "left" else if (right) "right" else "center",
        baseline = if (top) "top" else "bottom",
    )
}

/** Lay panels out in a uniform grid while preserving each cropped aspect. */
fun buildFigureGeometry(project: AnnotateProject): FigureGeometry {
    val layout = project.layout
    val width = layout.figureWidthMm * PX_PER_MM
    val margin = layout.marginMm * PX_PER_MM
    val gap = layout.gapMm * PX_PER_MM
    val n = project.panels.size
    if (n == 0) {
        return FigureGeometry(width, width * 0.6, emptyList(), emptyMap(), 0, 0)
    }

    val colCount = max(1, min(layout.columns.toDouble().roundToInt(), n))
    val rowCount = (n + colCount - 1) / colCount
    val cellW = max(4.0, (width - 2 * margin - (colCount - 1) * gap) / colCount)
    fun colOf(index: Int) = if (layout.flow == "row") index % colCount else index / rowCount
    fun rowOf(index: Int) = if (layout.flow == "row") index / colCount else index % rowCount

    data class Item(val panel: Panel, val index: Int, val col: Int, val row: Int, val w: Double, val h: Double)

    val items = project.panels.mapIndexed { index, panel ->
        val w = cellW
        val h = w / max(0.05, panelAspect(panel))
        Item(panel, index, colOf(index), rowOf(index), w, h)
    }

    val rowHeights = DoubleArray(rowCount)
    for (item in items) {
        rowHeights[item.row] = max(rowHeights[item.row], item.h)
    }
    val rowTops = ArrayList<Double>()
    var cursor = margin
    for (r in 0 until rowCount) {
        rowTops.add(cursor)
        cursor += rowHeights[r] + if (r < rowCount - 1) gap else 0.0
    }
    val height = cursor + margin

    val frames = items.map { item ->
        val x = margin + item.col * (cellW + gap)
        val y = rowTops[item.row] + (rowHeights[item.row] - item.h) / 2
        val partial = PanelFrame(
            panel = item.panel, index = item.index, row = item.row, col = item.col,
            x = x, y = y, w = item.w, h = item.h,
            label = FrameLabel("", 0.0, 0.0, 0.0, "left", "top"),
        )
        val text = panelLabel(item.index, layout.labelStyle)
        PanelFrame(partial.panel, partial.index, partial.row, partial.col,
            partial.x, partial.y, partial.w, partial.h, frameLabel(partial, text, layout))
    }

    return FigureGeometry(
        width = width, height = height, frames = frames,
        frameById = frames.associateBy { it.panel.id },
        rowCount = rowCount, colCount = colCount,
    )
}

/** Physical bar length in figure pixels, or null when calibration is missing. */
private fun barLengthFigure(
    value: Double,
    unit: Unit,
    mode: String,
    panel: Panel?,
    frame: PanelFrame?,
): Double? {
    if (value.isNaN() || value <= 0) return null
    if (mode == "fixed") return value * PX_PER_MM
    if (panel == null || frame == null) return null
    val sourcePx = sourcePixelsFor(panel.calibration, value, unit) ?: return null
    val vis = visibleRect(panel)
    val sourceWidth = vis.sw * panel.width
    if (sourceWidth <= 0) return null
    return (sourcePx * frame.w) / sourceWidth
}

private fun barText(value: Double, unit: Unit, showLabel: Boolean): String =
    if (showLabel) "${formatValue(value)} ${unitSymbol(unit)}" else ""

/** Position and label of a scale bar, shared by drawing, hit testing and export. */
fun barLayout(
    ann: Annotation,
    geom: FigureGeometry,
): BarLayout? {
    if (ann !is ScaleBarAnnotation && ann !is PanelBarAnnotation) return null
    val frame = if ((ann as? ScaleBarAnnotation)?.panelId?.isNotEmpty() == true ||
        (ann as? PanelBarAnnotation)?.panelId?.isNotEmpty() == true) {
        val pid = when (ann) {
            is ScaleBarAnnotation -> ann.panelId
            else -> (ann as PanelBarAnnotation).panelId
        }
        geom.frameById[pid]
    } else null
    val panel = frame?.panel
    if (ann is PanelBarAnnotation) {
        if (frame == null || panel == null) return null
        val length = barLengthFigure(ann.value, ann.unit, "calibrated", panel, frame) ?: return null
        return BarLayout(
            x = frame.x + ann.nx * frame.w,
            y = frame.y + ann.ny * frame.h,
            length = length,
            label = barText(ann.value, ann.unit, ann.showLabel),
            fontPx = ann.fontSizePt * PT_TO_PX,
            frame = frame, panel = panel,
        )
    }
    val sb = ann as ScaleBarAnnotation
    val length = barLengthFigure(sb.value, sb.unit, sb.mode, panel, frame) ?: return null
    return BarLayout(
        x = sb.x, y = sb.y, length = length,
        label = barText(sb.value, sb.unit, sb.showLabel),
        fontPx = sb.fontSizePt * PT_TO_PX,
        frame = frame, panel = panel,
    )
}

private fun textMetrics(text: String, fontPx: Double, family: String): Triple<List<String>, Double, Double> {
    val lines = text.split('\n')
    var width = 0.0
    for (line in lines) width = max(width, measureWidth(line, fontPx, family))
    val lineHeight = fontPx * 1.25
    return Triple(lines, width, max(lineHeight, lines.size * lineHeight))
}

/** Bounding boxes and derived metrics for every annotation. */
fun buildAnnotationMeta(project: AnnotateProject, geom: FigureGeometry): Map<String, AnnotationMeta> {
    val out = LinkedHashMap<String, AnnotationMeta>()
    val family = project.layout.fontFamily
    for (ann in project.annotations) {
        var box = Box(0.0, 0.0, 0.0, 0.0)
        var fontPx = 0.0
        var lines: List<String> = emptyList()
        var barLength: Double? = null
        var frame: PanelFrame? = null

        when (ann) {
            is ArrowAnnotation -> {
                val pad = max(ann.strokeWidthPt * PT_TO_PX, ann.headSizePt * PT_TO_PX) * 0.6
                box = normalizeBox(
                    Box(ann.x1 - pad, ann.y1 - pad, ann.x2 - ann.x1 + pad * 2, ann.y2 - ann.y1 + pad * 2)
                )
            }
            is RectAnnotation, is EllipseAnnotation -> {
                val (x, y, w, h) = when (ann) {
                    is RectAnnotation -> listOf(ann.x, ann.y, ann.w, ann.h)
                    else -> {
                        val e = ann as EllipseAnnotation
                        listOf(e.x, e.y, e.w, e.h)
                    }
                }
                box = normalizeBox(Box(x, y, w, h))
            }
            is ProfileAnnotation -> {
                val pad = max(ann.strokeWidthPt * PT_TO_PX, 4.0)
                box = normalizeBox(
                    Box(ann.x1 - pad, ann.y1 - pad, ann.x2 - ann.x1 + pad * 2, ann.y2 - ann.y1 + pad * 2)
                )
            }
            is TextAnnotation -> {
                fontPx = ann.fontSizePt * PT_TO_PX
                val (linesList, width, height) = textMetrics(ann.text.ifEmpty { " " }, fontPx, family)
                lines = linesList
                val pad = ann.paddingPt * PT_TO_PX
                box = Box(ann.x - pad, ann.y - pad, width + pad * 2, height + pad * 2)
            }
            is ScaleBarAnnotation, is PanelBarAnnotation -> {
                val bar = barLayout(ann, geom)
                val pid = when (ann) {
                    is ScaleBarAnnotation -> ann.panelId
                    else -> (ann as PanelBarAnnotation).panelId
                }
                frame = bar?.frame ?: if (pid.isNotEmpty()) geom.frameById[pid] else null
                if (bar != null) {
                    barLength = bar.length
                    fontPx = bar.fontPx
                    val labelWidth = if (bar.label.isNotEmpty()) measureWidth(bar.label, bar.fontPx, family) else 0.0
                    val thickness = when (ann) {
                        is ScaleBarAnnotation -> ann.thicknessPt * PT_TO_PX
                        else -> (ann as PanelBarAnnotation).thicknessPt * PT_TO_PX
                    }
                    val barBox = Box(
                        bar.x - thickness, bar.y - thickness / 2 - 2,
                        bar.length + thickness * 2, thickness + 4,
                    )
                    val labelBox = if (bar.label.isNotEmpty()) Box(
                        bar.x + bar.length / 2 - labelWidth / 2,
                        bar.y - bar.fontPx * 1.2,
                        labelWidth, bar.fontPx * 1.2,
                    ) else Box(bar.x, bar.y, 0.0, 0.0)
                    box = unionBox(barBox, labelBox)
                } else {
                    fontPx = when (ann) {
                        is ScaleBarAnnotation -> ann.fontSizePt * PT_TO_PX
                        else -> (ann as PanelBarAnnotation).fontSizePt * PT_TO_PX
                    }
                    box = if (ann is ScaleBarAnnotation) {
                        Box(ann.x, ann.y - fontPx, 80.0, fontPx * 1.3)
                    } else {
                        Box(0.0, 0.0, 80.0, fontPx * 1.3)
                    }
                }
            }
        }
        out[ann.id] = AnnotationMeta(ann.id, box, fontPx, lines, barLength, frame)
    }
    return out
}

data class HandleDef(val id: String, val x: Double, val y: Double)

fun annotationHandles(ann: Annotation, meta: AnnotationMeta?): List<HandleDef> {
    if (meta == null) return emptyList()
    return when (ann) {
        is ArrowAnnotation -> listOf(
            HandleDef("start", ann.x1, ann.y1),
            HandleDef("end", ann.x2, ann.y2),
        )
        is ProfileAnnotation -> listOf(
            HandleDef("start", ann.x1, ann.y1),
            HandleDef("end", ann.x2, ann.y2),
        )
        is RectAnnotation, is EllipseAnnotation -> listOf(
            HandleDef("nw", meta.box.x, meta.box.y),
            HandleDef("ne", meta.box.x + meta.box.w, meta.box.y),
            HandleDef("sw", meta.box.x, meta.box.y + meta.box.h),
            HandleDef("se", meta.box.x + meta.box.w, meta.box.y + meta.box.h),
        )
        is TextAnnotation -> listOf(HandleDef("se", meta.box.x + meta.box.w, meta.box.y + meta.box.h))
        is ScaleBarAnnotation ->
            if (meta.barLength != null) listOf(HandleDef("end", ann.x + meta.barLength, ann.y)) else emptyList()
        is PanelBarAnnotation -> {
            if (meta.barLength == null || meta.frame == null) return emptyList()
            val x = meta.frame.x + ann.nx * meta.frame.w
            val y = meta.frame.y + ann.ny * meta.frame.h
            listOf(HandleDef("start", x, y), HandleDef("end", x + meta.barLength, y))
        }
    }
}

fun findHandleAt(ann: Annotation, meta: AnnotationMeta?, point: Point, tolerance: Double): String? {
    var best: Pair<String, Double>? = null
    for (handle in annotationHandles(ann, meta)) {
        val d = kotlin.math.hypot(handle.x - point.x, handle.y - point.y)
        if (d <= tolerance && (best == null || d < best!!.second)) best = handle.id to d
    }
    return best?.first
}

fun findFrameAt(geom: FigureGeometry, point: Point): PanelFrame? {
    for (i in geom.frames.indices.reversed()) {
        val frame = geom.frames[i]
        if (point.x >= frame.x && point.x <= frame.x + frame.w &&
            point.y >= frame.y && point.y <= frame.y + frame.h
        ) {
            return frame
        }
    }
    return null
}

fun findAnnotationAt(
    project: AnnotateProject,
    meta: Map<String, AnnotationMeta>,
    point: Point,
    pad: Double = 2.0,
): String? {
    for (i in project.annotations.indices.reversed()) {
        val ann = project.annotations[i]
        val box = meta[ann.id]?.box ?: continue
        if (point.x >= box.x - pad && point.x <= box.x + box.w + pad &&
            point.y >= box.y - pad && point.y <= box.y + box.h + pad
        ) {
            return ann.id
        }
    }
    return null
}

fun mmFromPx(px: Double): Double = px / PX_PER_MM

fun pxFromMm(mm: Double): Double = mm * PX_PER_MM
