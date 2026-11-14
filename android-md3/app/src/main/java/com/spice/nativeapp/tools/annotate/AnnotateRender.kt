package com.spice.nativeapp.tools.annotate

/*
 * 画布预览与发表级导出，1:1 移植 main 的 src/lib/tools/annotate/render.ts。
 * 预览、PNG 与 SVG 全部从同一套 FigureGeometry 与标注边界取坐标，
 * 导出图形与屏上所见一致。平台替换：CanvasRenderingContext2D → android.graphics.Canvas。
 */
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

typealias ArtResolver = (Panel) -> PanelArt?

private fun fontPaint(sizePx: Double, family: String, bold: Boolean = false): Paint = Paint().apply {
    textSize = sizePx.toFloat()
    isAntiAlias = true
    isSubpixelText = true
    val serif = family.contains("Times", true) || family.contains("Cambria", true)
    typeface = when {
        bold -> Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, Typeface.BOLD)
        serif -> Typeface.SERIF
        family.contains("Mono", true) || family.contains("Courier", true) -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

private fun lineWidth(pt: Double): Double = max(0.35, pt * PT_TO_PX)

private fun arrowHead(x1: Double, y1: Double, x2: Double, y2: Double, size: Double): Triple<Point, Point, Point> {
    val angle = atan2(y2 - y1, x2 - x1)
    val spread = 0.42
    return Triple(
        Point(x2, y2),
        Point(x2 - size * cos(angle - spread), y2 - size * sin(angle - spread)),
        Point(x2 - size * cos(angle + spread), y2 - size * sin(angle + spread)),
    )
}

private fun drawPanelImage(canvas: Canvas, frame: PanelFrame, art: PanelArt) {
    val vis = visibleRect(frame.panel)
    canvas.save()
    canvas.clipRect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.w).toFloat(), (frame.y + frame.h).toFloat())
    val src = android.graphics.Rect(
        (vis.sx * art.width).toInt(),
        (vis.sy * art.height).toInt(),
        ((vis.sx + vis.sw) * art.width).toInt(),
        ((vis.sy + vis.sh) * art.height).toInt(),
    )
    val dst = RectF(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.w).toFloat(), (frame.y + frame.h).toFloat())
    val paint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(art.source, src, dst, paint)
    canvas.restore()
}

private fun parseColorOr(color: String, fallback: Int): Int = try {
    android.graphics.Color.parseColor(color)
} catch (e: Exception) {
    fallback
}

private fun drawTextBlock(canvas: Canvas, ann: TextAnnotation, meta: AnnotationMeta, family: String) {
    if (ann.background != "none") {
        val box = meta.box
        val p = Paint().apply { color = parseColorOr(ann.background, 0xFFFFFFFF.toInt()) }
        canvas.drawRect(box.x.toFloat(), box.y.toFloat(), (box.x + box.w).toFloat(), (box.y + box.h).toFloat(), p)
    }
    val metrics = if (meta.lines.isNotEmpty()) meta.lines else listOf("")
    val lineHeight = meta.fontPx * 1.25
    val paint = fontPaint(meta.fontPx, family).apply { color = parseColorOr(ann.color, 0xFF111111.toInt()) }
    // JS textBaseline='top'：drawText 以 baseline 定位，top ≈ -ascent。
    val topShift = -paint.ascent()
    metrics.forEachIndexed { i, line ->
        canvas.drawText(line, ann.x.toFloat(), (ann.y + i * lineHeight + topShift).toFloat(), paint)
    }
}

private fun drawArrow(canvas: Canvas, ann: ArrowAnnotation) {
    val width = lineWidth(ann.strokeWidthPt)
    val paint = Paint().apply {
        color = parseColorOr(ann.color, 0xFF111111.toInt())
        style = Paint.Style.STROKE
        strokeWidth = width.toFloat()
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }
    canvas.drawLine(ann.x1.toFloat(), ann.y1.toFloat(), ann.x2.toFloat(), ann.y2.toFloat(), paint)
    val head = ann.headSizePt * PT_TO_PX
    val (tip, a, b) = arrowHead(ann.x1, ann.y1, ann.x2, ann.y2, head)
    val fill = android.graphics.Path().apply {
        moveTo(tip.x.toFloat(), tip.y.toFloat())
        lineTo(a.x.toFloat(), a.y.toFloat())
        lineTo(b.x.toFloat(), b.y.toFloat())
        close()
    }
    paint.style = Paint.Style.FILL
    canvas.drawPath(fill, paint)
}

private fun drawProfile(canvas: Canvas, ann: ProfileAnnotation) {
    val width = lineWidth(ann.strokeWidthPt)
    val paint = Paint().apply {
        color = parseColorOr(ann.color, 0xFF111111.toInt())
        style = Paint.Style.STROKE
        strokeWidth = width.toFloat()
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf((width * 3).toFloat(), (width * 2).toFloat()), 0f)
    }
    canvas.drawLine(ann.x1.toFloat(), ann.y1.toFloat(), ann.x2.toFloat(), ann.y2.toFloat(), paint)
    paint.pathEffect = null
    val radius = max(1.5, width * 1.4)
    paint.style = Paint.Style.FILL
    for ((x, y) in listOf(ann.x1 to ann.y1, ann.x2 to ann.y2)) {
        canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), paint)
    }
}

private fun drawBar(
    canvas: Canvas,
    ann: Annotation,
    geom: FigureGeometry,
    family: String,
) {
    val bar = barLayout(ann, geom) ?: return
    val (color, thicknessPt) = when (ann) {
        is ScaleBarAnnotation -> ann.color to ann.thicknessPt
        is PanelBarAnnotation -> ann.color to ann.thicknessPt
        else -> return
    }
    val thickness = lineWidth(thicknessPt)
    val barColorInt = parseColorOr(color, 0xFF111111.toInt())
    val paint = Paint().apply {
        this.color = barColorInt
        style = Paint.Style.STROKE
        strokeWidth = thickness.toFloat()
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }
    canvas.drawLine(bar.x.toFloat(), bar.y.toFloat(), (bar.x + bar.length).toFloat(), bar.y.toFloat(), paint)
    if (bar.label.isNotEmpty()) {
        val textPaint = fontPaint(bar.fontPx, family).apply { this.color = barColorInt }
        textPaint.textAlign = Paint.Align.CENTER
        // JS textBaseline=bottom：bottom ≈ descent。
        val baselineY = bar.y - thickness / 2 - 2 + textPaint.descent()
        canvas.drawText(bar.label, (bar.x + bar.length / 2).toFloat(), baselineY.toFloat(), textPaint)
    }
}

private fun drawAnnotation(
    canvas: Canvas,
    ann: Annotation,
    meta: AnnotationMeta?,
    project: AnnotateProject,
    geom: FigureGeometry,
) {
    val family = project.layout.fontFamily
    when (ann) {
        is ArrowAnnotation -> drawArrow(canvas, ann)
        is RectAnnotation -> {
            val paint = Paint().apply {
                color = parseColorOr(ann.color, 0xFF111111.toInt())
                style = Paint.Style.STROKE
                strokeWidth = lineWidth(ann.strokeWidthPt).toFloat()
                isAntiAlias = true
            }
            if (ann.fill != "none") {
                val fp = Paint().apply { color = parseColorOr(ann.fill, 0xFFFFFFFF.toInt()) }
                canvas.drawRect(ann.x.toFloat(), ann.y.toFloat(), (ann.x + ann.w).toFloat(), (ann.y + ann.h).toFloat(), fp)
            }
            canvas.drawRect(ann.x.toFloat(), ann.y.toFloat(), (ann.x + ann.w).toFloat(), (ann.y + ann.h).toFloat(), paint)
        }
        is EllipseAnnotation -> {
            val paint = Paint().apply {
                color = parseColorOr(ann.color, 0xFF111111.toInt())
                style = Paint.Style.STROKE
                strokeWidth = lineWidth(ann.strokeWidthPt).toFloat()
                isAntiAlias = true
            }
            if (ann.fill != "none") {
                val fp = Paint().apply { color = parseColorOr(ann.fill, 0xFFFFFFFF.toInt()) }
                canvas.drawOval(
                    RectF(ann.x.toFloat(), ann.y.toFloat(), (ann.x + ann.w).toFloat(), (ann.y + ann.h).toFloat()),
                    fp,
                )
            }
            canvas.drawOval(RectF(ann.x.toFloat(), ann.y.toFloat(), (ann.x + ann.w).toFloat(), (ann.y + ann.h).toFloat()), paint)
        }
        is TextAnnotation -> meta?.let { drawTextBlock(canvas, ann, it, family) }
        is ScaleBarAnnotation, is PanelBarAnnotation -> drawBar(canvas, ann, geom, family)
        is ProfileAnnotation -> drawProfile(canvas, ann)
    }
}

/**
 * Draw only the composite background and panel pixels. Used for intensity
 * sampling so measurement lines, labels and other annotations never perturb
 * the profile they measure.
 */
fun drawCompositeFigure(
    canvas: Canvas,
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
) {
    val bg = Paint().apply { color = parseColorOr(project.layout.background, 0xFFFFFFFF.toInt()) }
    canvas.drawRect(0f, 0f, geom.width.toFloat(), geom.height.toFloat(), bg)
    for (frame in geom.frames) {
        val art = resolve(frame.panel) ?: continue
        drawPanelImage(canvas, frame, art)
    }
}

/** Rasterise the panel composite on its own for line-profile sampling. */
fun renderCompositeFigure(
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
    scale: Double = 1.0,
): Bitmap {
    val width = max(1, (geom.width * scale).roundToInt())
    val height = max(1, (geom.height * scale).roundToInt())
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(scale.toFloat(), scale.toFloat())
    drawCompositeFigure(canvas, project, geom, resolve)
    canvas.restore()
    return bitmap
}

fun drawFigure(
    canvas: Canvas,
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
    meta: Map<String, AnnotationMeta>,
) {
    val layout = project.layout
    val bg = Paint().apply { color = parseColorOr(layout.background, 0xFFFFFFFF.toInt()) }
    canvas.drawRect(0f, 0f, geom.width.toFloat(), geom.height.toFloat(), bg)

    for (frame in geom.frames) {
        val art = resolve(frame.panel) ?: continue
        drawPanelImage(canvas, frame, art)
    }

    for (frame in geom.frames) {
        val label = frame.label
        if (label.text.isEmpty()) continue
        val paint = fontPaint(label.fontPx, layout.fontFamily, bold = true)
        paint.textAlign = when (label.align) {
            "left" -> Paint.Align.LEFT
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        // 描边（strokeText white halo）+填充：paint_order=stroke 的视觉等价。
        val halo = android.graphics.Paint(paint).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(2.0, label.fontPx * 0.18).toFloat()
            color = android.graphics.Color.WHITE
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        paint.color = parseColorOr(layout.labelColor, 0xFF111111.toInt())
        // baseline: top → text-before-edge ≈ -ascent；bottom → text-after-edge ≈ descent。
        val baselineShift = if (label.baseline == "top") -paint.ascent() else paint.descent()
        val y = (label.y + baselineShift).toFloat()
        canvas.drawText(label.text, label.x.toFloat(), y, halo)
        canvas.drawText(label.text, label.x.toFloat(), y, paint)
    }

    for (ann in project.annotations) {
        drawAnnotation(canvas, ann, meta[ann.id], project, geom)
    }
}

class SelectionInfo(val annotationId: String?, val panelId: String?)

fun drawSelection(
    canvas: Canvas,
    project: AnnotateProject,
    geom: FigureGeometry,
    meta: Map<String, AnnotationMeta>,
    selection: SelectionInfo,
    viewScale: Double,
) {
    val unit = 1 / max(0.0001, viewScale)

    if (selection.panelId != null) {
        val frame = geom.frameById[selection.panelId]
        if (frame != null) {
            val p = Paint().apply {
                color = android.graphics.Color.argb(140, 76, 214, 255)
                style = Paint.Style.STROKE
                strokeWidth = (1 * unit).toFloat()
                pathEffect = DashPathEffect(floatArrayOf((5 * unit).toFloat(), (4 * unit).toFloat()), 0f)
            }
            canvas.drawRect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.w).toFloat(), (frame.y + frame.h).toFloat(), p)
            p.pathEffect = null
        }
    }

    val ann = selection.annotationId?.let { id -> project.annotations.firstOrNull { it.id == id } }
    if (ann != null) {
        val info = meta[ann.id]
        if (info != null) {
            val p = Paint().apply {
                color = parseColorOr("#4cd6ff", 0xFF4CD6FF.toInt())
                style = Paint.Style.STROKE
                strokeWidth = (1.5 * unit).toFloat()
                pathEffect = DashPathEffect(floatArrayOf((4 * unit).toFloat(), (3 * unit).toFloat()), 0f)
            }
            canvas.drawRect(
                info.box.x.toFloat(), info.box.y.toFloat(),
                (info.box.x + info.box.w).toFloat(), (info.box.y + info.box.h).toFloat(), p,
            )
            p.pathEffect = null
            val size = (7 * unit).toFloat()
            val handles = mutableListOf<Point>()
            when (ann) {
                is ArrowAnnotation -> {
                    handles.add(Point(ann.x1, ann.y1)); handles.add(Point(ann.x2, ann.y2))
                }
                is ProfileAnnotation -> {
                    handles.add(Point(ann.x1, ann.y1)); handles.add(Point(ann.x2, ann.y2))
                }
                is RectAnnotation, is EllipseAnnotation -> {
                    handles.add(Point(info.box.x, info.box.y))
                    handles.add(Point(info.box.x + info.box.w, info.box.y))
                    handles.add(Point(info.box.x, info.box.y + info.box.h))
                    handles.add(Point(info.box.x + info.box.w, info.box.y + info.box.h))
                }
                is TextAnnotation -> handles.add(Point(info.box.x + info.box.w, info.box.y + info.box.h))
                is ScaleBarAnnotation -> if (info.barLength != null) handles.add(Point(ann.x + info.barLength, ann.y))
                is PanelBarAnnotation -> {
                    val frame = geom.frameById[ann.panelId]
                    if (frame != null && info.barLength != null) {
                        val x = frame.x + ann.nx * frame.w
                        val y = frame.y + ann.ny * frame.h
                        handles.add(Point(x, y)); handles.add(Point(x + info.barLength, y))
                    }
                }
            }
            val fill = Paint().apply { color = parseColorOr("#0b0e14", 0xFF0B0E14.toInt()) }
            for (handle in handles) {
                val l = (handle.x - size / 2).toFloat()
                val t = (handle.y - size / 2).toFloat()
                canvas.drawRect(l, t, l + size, t + size, fill)
                canvas.drawRect(l, t, l + size, t + size, p)
            }
        }
    }
}

class DraftShape(val kind: String, val start: Point, val end: Point) // 'arrow' | 'rect' | 'ellipse' | 'line'

fun drawDraft(
    canvas: Canvas,
    draft: DraftShape,
    viewScale: Double,
) {
    val unit = 1 / max(0.0001, viewScale)
    val color = if (draft.kind == "line") parseColorOr("#ffd23f", 0xFFFFD23F.toInt()) else parseColorOr("#ff9f1c", 0xFFFF9F1C.toInt())
    val paint = Paint().apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = (1.5 * unit).toFloat()
        pathEffect = DashPathEffect(floatArrayOf((5 * unit).toFloat(), (3 * unit).toFloat()), 0f)
        isAntiAlias = true
    }
    val x = min(draft.start.x, draft.end.x)
    val y = min(draft.start.y, draft.end.y)
    val w = abs(draft.end.x - draft.start.x)
    val h = abs(draft.end.y - draft.start.y)
    when (draft.kind) {
        "rect" -> canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
        "ellipse" -> canvas.drawOval(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), paint)
        else -> {
            canvas.drawLine(draft.start.x.toFloat(), draft.start.y.toFloat(), draft.end.x.toFloat(), draft.end.y.toFloat(), paint)
            if (draft.kind == "arrow") {
                val head = 8 * unit
                val (tip, a, b) = arrowHead(draft.start.x, draft.start.y, draft.end.x, draft.end.y, head)
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
                val path = android.graphics.Path().apply {
                    moveTo(tip.x.toFloat(), tip.y.toFloat())
                    lineTo(a.x.toFloat(), a.y.toFloat())
                    lineTo(b.x.toFloat(), b.y.toFloat())
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}

/** Render the complete figure at a target dots-per-inch. */
fun renderFigurePng(
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
    meta: Map<String, AnnotationMeta>,
    dpi: Int,
): Bitmap {
    val scale = dpi / 96.0
    val width = max(1, (geom.width * scale).roundToInt())
    val height = max(1, (geom.height * scale).roundToInt())
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(scale.toFloat(), scale.toFloat())
    drawFigure(canvas, project, geom, resolve, meta)
    canvas.restore()
    return bitmap
}

// ------------------------------------------------------------------- PNG metadata

private val CRC_TABLE: IntArray = IntArray(256).also { table ->
    for (n in 0 until 256) {
        var c = n
        for (k in 0 until 8) c = if (c and 1 != 0) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1
        table[n] = c
    }
}

private fun crc32(bytes: ByteArray): Long {
    var c = -1L
    for (b in bytes) c = CRC_TABLE[((c xor b.toLong()) and 0xffL).toInt()].toLong() xor (c ushr 8)
    return (c xor 0xffffffffL) and 0xffffffffL
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    val len = data.size
    out[0] = ((len ushr 24) and 0xff).toByte()
    out[1] = ((len ushr 16) and 0xff).toByte()
    out[2] = ((len ushr 8) and 0xff).toByte()
    out[3] = (len and 0xff).toByte()
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    data.copyInto(out, 8)
    val crc = crc32(out.copyOfRange(4, 8 + data.size))
    out[8 + data.size] = ((crc ushr 24) and 0xff).toByte()
    out[9 + data.size] = ((crc ushr 16) and 0xff).toByte()
    out[10 + data.size] = ((crc ushr 8) and 0xff).toByte()
    out[11 + data.size] = (crc and 0xff).toByte()
    return out
}

private fun canvasPngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

/** Add a pHYs chunk (physical resolution) and a UTF-8 description to a PNG. */
fun encodePngWithDpi(
    bitmap: Bitmap,
    dpi: Int,
    description: String,
): ByteArray {
    val png = canvasPngBytes(bitmap)
    val ppm = Math.round(dpi / 0.0254).toInt()
    val phys = ByteArray(9)
    for (i in 0 until 3) {
        val shift = (3 - i) * 8
        phys[i] = ((ppm ushr shift) and 0xff).toByte()
        phys[4 + i] = ((ppm ushr shift) and 0xff).toByte()
    }
    phys[8] = 1
    val chunks = mutableListOf(pngChunk("pHYs", phys), pngChunk("sRGB", byteArrayOf(0)))
    val keyword = "Description".toByteArray(Charsets.UTF_8)
    val text = description.toByteArray(Charsets.UTF_8)
    val itxt = ByteArray(keyword.size + 5 + text.size)
    var offset = 0
    keyword.copyInto(itxt, 0)
    offset = keyword.size
    itxt[offset++] = 0
    itxt[offset++] = 0
    itxt[offset++] = 0
    itxt[offset++] = 0
    itxt[offset++] = 0
    text.copyInto(itxt, offset)
    chunks.add(pngChunk("iTXt", itxt))

    val ihdrEnd = 8 + 25
    val extra = chunks.sumOf { it.size }
    val out = ByteArray(png.size + extra)
    png.copyInto(out, 0, 0, ihdrEnd)
    var cursor = ihdrEnd
    for (chunk in chunks) {
        chunk.copyInto(out, cursor)
        cursor += chunk.size
    }
    png.copyInto(out, cursor, ihdrEnd)
    return out
}

// ------------------------------------------------------------------- SVG

private fun esc(value: String): String = value
    .replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), "")
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun svgAttrs(attrs: List<Pair<String, Any?>>): String =
    attrs.filter { it.second != null && it.second.toString().isNotEmpty() }
        .joinToString(" ") { "${it.first}=\"${esc(it.second.toString())}\"" }

private fun svgNumber(value: Double): String =
    if (value.isFinite()) String.format(Locale.US, "%.2f", value) else "0"

private fun svgPanel(frame: PanelFrame, art: PanelArt): List<String> {
    if (art.dataUrl == null) return emptyList()
    val vis = visibleRect(frame.panel)
    val viewBox = listOf(
        svgNumber(vis.sx * art.width),
        svgNumber(vis.sy * art.height),
        svgNumber(vis.sw * art.width),
        svgNumber(vis.sh * art.height),
    ).joinToString(" ")
    return listOf(
        "<svg x=\"${svgNumber(frame.x)}\" y=\"${svgNumber(frame.y)}\" width=\"${svgNumber(frame.w)}\" height=\"${svgNumber(frame.h)}\" viewBox=\"$viewBox\" preserveAspectRatio=\"none\" overflow=\"hidden\"><image href=\"${art.dataUrl}\" width=\"${svgNumber(art.width.toDouble())}\" height=\"${svgNumber(art.height.toDouble())}\" preserveAspectRatio=\"none\"/></svg>",
    )
}

private fun svgAnnotation(
    ann: Annotation,
    meta: AnnotationMeta?,
    project: AnnotateProject,
    geom: FigureGeometry,
): List<String> {
    val out = mutableListOf<String>()
    val family = project.layout.fontFamily
    when (ann) {
        is ArrowAnnotation -> {
            out.add(
                "<line ${svgAttrs(listOf("x1" to svgNumber(ann.x1), "y1" to svgNumber(ann.y1), "x2" to svgNumber(ann.x2), "y2" to svgNumber(ann.y2), "stroke" to ann.color, "stroke-width" to svgNumber(lineWidth(ann.strokeWidthPt))))}/>",
            )
            val head = ann.headSizePt * PT_TO_PX
            val (tip, a, b) = arrowHead(ann.x1, ann.y1, ann.x2, ann.y2, head)
            out.add(
                "<polygon points=\"${svgNumber(tip.x)},${svgNumber(tip.y)} ${svgNumber(a.x)},${svgNumber(a.y)} ${svgNumber(b.x)},${svgNumber(b.y)}\" fill=\"${esc(ann.color)}\"/>",
            )
        }
        is RectAnnotation -> out.add(
            "<rect ${svgAttrs(listOf("x" to svgNumber(ann.x), "y" to svgNumber(ann.y), "width" to svgNumber(ann.w), "height" to svgNumber(ann.h), "fill" to (if (ann.fill == "none") "none" else ann.fill), "stroke" to ann.color, "stroke-width" to svgNumber(lineWidth(ann.strokeWidthPt))))}/>",
        )
        is EllipseAnnotation -> out.add(
            "<ellipse ${svgAttrs(listOf("cx" to svgNumber(ann.x + ann.w / 2), "cy" to svgNumber(ann.y + ann.h / 2), "rx" to svgNumber(abs(ann.w / 2)), "ry" to svgNumber(abs(ann.h / 2)), "fill" to (if (ann.fill == "none") "none" else ann.fill), "stroke" to ann.color, "stroke-width" to svgNumber(lineWidth(ann.strokeWidthPt))))}/>",
        )
        is TextAnnotation -> {
            if (meta == null) return out
            if (ann.background != "none") {
                out.add(
                    "<rect ${svgAttrs(listOf("x" to svgNumber(meta.box.x), "y" to svgNumber(meta.box.y), "width" to svgNumber(meta.box.w), "height" to svgNumber(meta.box.h), "fill" to ann.background))}/>",
                )
            }
            val lineHeight = meta.fontPx * 1.25
            val lines = if (meta.lines.isNotEmpty()) meta.lines else listOf("")
            lines.forEachIndexed { index, line ->
                out.add(
                    "<text ${svgAttrs(listOf("x" to svgNumber(ann.x), "y" to svgNumber(ann.y + index * lineHeight), "font-family" to family, "font-size" to svgNumber(meta.fontPx), "fill" to ann.color, "dominant-baseline" to "text-before-edge"))}>${esc(line)}</text>",
                )
            }
        }
        is ProfileAnnotation -> {
            val width = lineWidth(ann.strokeWidthPt)
            out.add(
                "<line ${svgAttrs(listOf("x1" to svgNumber(ann.x1), "y1" to svgNumber(ann.y1), "x2" to svgNumber(ann.x2), "y2" to svgNumber(ann.y2), "stroke" to ann.color, "stroke-width" to svgNumber(width), "stroke-dasharray" to "${svgNumber(width * 3)} ${svgNumber(width * 2)}", "stroke-linecap" to "round"))}/>",
            )
            val radius = max(1.5, width * 1.4)
            for ((x, y) in listOf(ann.x1 to ann.y1, ann.x2 to ann.y2)) {
                out.add("<circle ${svgAttrs(listOf("cx" to svgNumber(x), "cy" to svgNumber(y), "r" to svgNumber(radius), "fill" to ann.color))}/>")
            }
        }
        is ScaleBarAnnotation, is PanelBarAnnotation -> {
            val bar = barLayout(ann, geom) ?: return out
            val (color, thicknessPt) = when (ann) {
                is ScaleBarAnnotation -> ann.color to ann.thicknessPt
                else -> {
                    val pb = ann as PanelBarAnnotation
                    pb.color to pb.thicknessPt
                }
            }
            val thickness = lineWidth(thicknessPt)
            out.add(
                "<line ${svgAttrs(listOf("x1" to svgNumber(bar.x), "y1" to svgNumber(bar.y), "x2" to svgNumber(bar.x + bar.length), "y2" to svgNumber(bar.y), "stroke" to color, "stroke-width" to svgNumber(thickness)))}/>",
            )
            if (bar.label.isNotEmpty()) {
                out.add(
                    "<text ${svgAttrs(listOf("x" to svgNumber(bar.x + bar.length / 2), "y" to svgNumber(bar.y - thickness / 2 - 2), "font-family" to family, "font-size" to svgNumber(bar.fontPx), "fill" to color, "text-anchor" to "middle"))}>${esc(bar.label)}</text>",
                )
            }
        }
    }
    return out
}

/** Hand-built SVG: images stay embedded and all labels remain real text. */
fun figureToSvg(
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
    meta: Map<String, AnnotationMeta>,
): String {
    val out = mutableListOf<String>()
    val heightMm = geom.height / PX_PER_MM
    out.add(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${svgNumber(project.layout.figureWidthMm)}mm\" height=\"${svgNumber(heightMm)}mm\" viewBox=\"0 0 ${svgNumber(geom.width)} ${svgNumber(geom.height)}\">",
    )
    out.add("<title>${esc(project.name)}</title>")
    out.add("<rect width=\"100%\" height=\"100%\" fill=\"${esc(project.layout.background)}\"/>")

    for (frame in geom.frames) {
        val art = resolve(frame.panel) ?: continue
        out.addAll(svgPanel(frame, art))
    }

    for (frame in geom.frames) {
        val label = frame.label
        if (label.text.isEmpty()) continue
        out.add(
            "<text ${svgAttrs(listOf(
                "x" to svgNumber(label.x),
                "y" to svgNumber(label.y),
                "font-family" to project.layout.fontFamily,
                "font-size" to svgNumber(label.fontPx),
                "font-weight" to "bold",
                "fill" to project.layout.labelColor,
                "text-anchor" to (if (label.align == "left") "start" else if (label.align == "right") "end" else "middle"),
                "dominant-baseline" to (if (label.baseline == "top") "text-before-edge" else "text-after-edge"),
                "paint-order" to "stroke",
                "stroke" to "#ffffff",
                "stroke-width" to svgNumber(max(2.0, label.fontPx * 0.18)),
                "stroke-linejoin" to "round",
            ))}>${esc(label.text)}</text>",
        )
    }

    for (ann in project.annotations) {
        out.addAll(svgAnnotation(ann, meta[ann.id], project, geom))
    }
    out.add("</svg>")
    return out.joinToString("\n")
}

fun figureSummary(project: AnnotateProject, geom: FigureGeometry, dpi: Int): String {
    val heightMm = geom.height / PX_PER_MM
    return listOf(
        "SPICE annotator figure",
        "panels: ${project.panels.size}",
        "size: ${String.format(Locale.US, "%.1f", project.layout.figureWidthMm)} x ${String.format(Locale.US, "%.1f", heightMm)} mm",
        "resolution: $dpi dpi",
        "export pixels: ${Math.round(geom.width * (dpi / 96.0))} x ${Math.round(geom.height * (dpi / 96.0))}",
    ).joinToString("; ")
}
