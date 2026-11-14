package com.spice.nativeapp.tools.gel

/**
 * Raster and vector rendering of a FigureLayout.
 * 1:1 移植自原版 src/lib/tools/gel/render.ts（Canvas2D → android.graphics.Canvas）。
 */
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

object GelRender {

    /** CSS 色串 → ARGB。支持 #rgb/#rrggbb/rgba()/transparent。 */
    fun parseCssColor(s: String): Int {
        val t = s.trim()
        if (t == "transparent") return 0x00000000
        if (t.startsWith("#")) return Color.parseColor(t)
        if (t.startsWith("rgba(") || t.startsWith("rgb(")) {
            val inner = t.substring(t.indexOf('(') + 1, t.lastIndexOf(')'))
            val parts = inner.split(',').map { it.trim() }
            val r = parts[0].toInt()
            val g = parts[1].toInt()
            val b = parts[2].toInt()
            val a = if (parts.size > 3) (parts[3].toDouble() * 255).toInt() else 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return try {
            Color.parseColor(t)
        } catch (e: Exception) {
            Color.BLACK
        }
    }

    private fun androidFamily(family: String): Typeface = when (family) {
        "Times New Roman", "Cambria" -> Typeface.SERIF
        "Arial", "Helvetica", "Calibri" -> Typeface.SANS_SERIF
        "monospace" -> Typeface.MONOSPACE
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    private fun textPaint(style: TextStyle): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = style.size.toFloat()
        p.typeface = android.graphics.Typeface.create(
            androidFamily(style.family),
            when {
                style.bold && style.italic -> Typeface.BOLD_ITALIC
                style.bold -> Typeface.BOLD
                style.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
        p.color = parseCssColor(style.color)
        p.textAlign = when (style.align ?: "left") {
            "center" -> Paint.Align.CENTER
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        return p
    }

    /** Canvas 基线偏移：Canvas2D textBaseline → Android drawText 的 y 偏移。 */
    private fun baselineOffset(style: TextStyle, p: Paint): Double {
        val fm = p.fontMetrics
        return when (style.baseline ?: "alphabetic") {
            "top" -> -fm.top.toDouble()
            "middle" -> -(fm.ascent + fm.descent) / 2.0
            "bottom" -> -fm.bottom.toDouble()
            else -> 0.0
        }
    }

    fun drawFigure(
        canvas: Canvas,
        layout: FigureLayout,
        processed: ProcessedImage,
        scale: Double = 1.0,
    ) {
        canvas.save()
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.drawColor(Color.WHITE)
        val crop = layout.crop
        val visW = 1 - crop.left - crop.right
        val visH = 1 - crop.top - crop.bottom
        val srcBmp = processed.bitmap

        for (item in layout.items) {
            when (item) {
                is FigItem.Image -> {
                    val src = if (item.source == "gel") {
                        Rect(
                            (crop.left * processed.width).toInt(),
                            (crop.top * processed.height).toInt(),
                            ((crop.left + visW) * processed.width).toInt(),
                            ((crop.top + visH) * processed.height).toInt(),
                        )
                    } else {
                        val b = item.box!!
                        Rect(
                            (b.x * processed.width).toInt(),
                            (b.y * processed.height).toInt(),
                            ((b.x + b.w) * processed.width).toInt(),
                            ((b.y + b.h) * processed.height).toInt(),
                        )
                    }
                    val dst = RectF(
                        item.x.toFloat(), item.y.toFloat(),
                        (item.x + item.w).toFloat(), (item.y + item.h).toFloat(),
                    )
                    canvas.drawBitmap(srcBmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
                is FigItem.Text -> {
                    val p = textPaint(item.style)
                    val angle = item.style.angle ?: 0.0
                    canvas.save()
                    canvas.translate(item.x.toFloat(), item.y.toFloat())
                    if (angle != 0.0) canvas.rotate(angle.toFloat())
                    if (item.halo) {
                        val w = p.measureText(item.text).toDouble()
                        val ax = when (item.style.align) {
                            "center" -> -w / 2
                            "right" -> -w
                            else -> 0.0
                        }
                        val top = if (item.style.baseline == "middle") -item.style.size * 0.65 else -item.style.size * 0.95
                        val halo = Paint().apply { color = (0.82 * 255).toInt() shl 24 } // rgba(255,255,255,0.82)
                        canvas.drawRect(
                            (ax - 1).toFloat(), top.toFloat(),
                            (ax + w + 1).toFloat(), (top + item.style.size * 1.35).toFloat(),
                            halo,
                        )
                    }
                    val dy = baselineOffset(item.style, p).toFloat()
                    canvas.drawText(item.text, 0f, dy, p)
                    canvas.restore()
                }
                is FigItem.Line -> {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = parseCssColor(item.color)
                        strokeWidth = item.width.toFloat()
                    }
                    if (item.dash.isNotEmpty()) p.pathEffect = DashPathEffect(item.dash.map { it.toFloat() }.toFloatArray(), 0f)
                    canvas.drawLine(item.x1.toFloat(), item.y1.toFloat(), item.x2.toFloat(), item.y2.toFloat(), p)
                }
                is FigItem.Rect -> {
                    if (item.fill != null) {
                        val p = Paint().apply { color = parseCssColor(item.fill) }
                        canvas.drawRect(
                            item.x.toFloat(), item.y.toFloat(),
                            (item.x + item.w).toFloat(), (item.y + item.h).toFloat(), p,
                        )
                    }
                    if (item.width > 0) {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            color = parseCssColor(item.color)
                            strokeWidth = item.width.toFloat()
                        }
                        canvas.drawRect(
                            item.x.toFloat(), item.y.toFloat(),
                            (item.x + item.w).toFloat(), (item.y + item.h).toFloat(), p,
                        )
                    }
                }
                is FigItem.Arrow -> {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = parseCssColor(item.color)
                        strokeWidth = item.width.toFloat()
                    }
                    canvas.drawLine(item.x1.toFloat(), item.y1.toFloat(), item.x2.toFloat(), item.y2.toFloat(), p)
                    val ang = Math.atan2(item.y2 - item.y1, item.x2 - item.x1)
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = parseCssColor(item.color) }
                    val path = Path()
                    path.moveTo(item.x2.toFloat(), item.y2.toFloat())
                    path.lineTo(
                        (item.x2 - item.head * Math.cos(ang - 0.42)).toFloat(),
                        (item.y2 - item.head * Math.sin(ang - 0.42)).toFloat(),
                    )
                    path.lineTo(
                        (item.x2 - item.head * Math.cos(ang + 0.42)).toFloat(),
                        (item.y2 - item.head * Math.sin(ang + 0.42)).toFloat(),
                    )
                    path.close()
                    canvas.drawPath(path, fill)
                }
            }
        }
        canvas.restore()
    }

    /** Render the figure at a target dots-per-inch. */
    fun rasterizeFigure(layout: FigureLayout, processed: ProcessedImage, dpi: Double): Bitmap {
        val scale = maxOf(1.0, dpi / 96)
        val w = Math.round(layout.width * scale).toInt().coerceAtLeast(1)
        val h = Math.round(layout.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawFigure(canvas, layout, processed, scale)
        return bmp
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JS 数字串（整数无小数点），用于 SVG 属性。 */
    private fun jsNum(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun f2(v: Double): String = "%.2f".format(v)

    /** SVG keeps text and lines editable; the gel is embedded once and cropped
     *  with a nested viewBox. */
    fun figureToSvg(
        layout: FigureLayout,
        imageHref: String,
        imageWidth: Int,
        imageHeight: Int,
    ): String {
        val crop = layout.crop
        val visW = 1 - crop.left - crop.right
        val visH = 1 - crop.top - crop.bottom
        val out = ArrayList<String>()
        out.add(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${jsNum(layout.width)}\" height=\"${jsNum(layout.height)}\" viewBox=\"0 0 ${jsNum(layout.width)} ${jsNum(layout.height)}\">"
        )
        out.add("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
        for (item in layout.items) {
            when (item) {
                is FigItem.Image -> {
                    val vbX: Double
                    val vbY: Double
                    val vbW: Double
                    val vbH: Double
                    if (item.source == "gel") {
                        vbX = crop.left * imageWidth
                        vbY = crop.top * imageHeight
                        vbW = visW * imageWidth
                        vbH = visH * imageHeight
                    } else {
                        val b = item.box!!
                        vbX = b.x * imageWidth
                        vbY = b.y * imageHeight
                        vbW = b.w * imageWidth
                        vbH = b.h * imageHeight
                    }
                    out.add(
                        "<svg x=\"${f2(item.x)}\" y=\"${f2(item.y)}\" width=\"${f2(item.w)}\" height=\"${f2(item.h)}\" viewBox=\"${f2(vbX)} ${f2(vbY)} ${f2(vbW)} ${f2(vbH)}\" preserveAspectRatio=\"none\"><image href=\"${imageHref}\" width=\"$imageWidth\" height=\"$imageHeight\"/></svg>"
                    )
                }
                is FigItem.Text -> {
                    val s = item.style
                    val anchor = if (s.align == "center") "middle" else if (s.align == "right") "end" else "start"
                    val dominant = if (s.baseline == "middle") "central" else if (s.baseline == "top") "text-before-edge" else "auto"
                    val transform = if (s.angle != null && s.angle != 0.0) " transform=\"rotate(${jsNum(s.angle)} ${jsNum(item.x)} ${jsNum(item.y)})\"" else ""
                    if (item.halo) {
                        val approx = maxOf(4.0, item.text.length * s.size * 0.58)
                        val x = if (s.align == "center") item.x - approx / 2 else if (s.align == "right") item.x - approx else item.x
                        val y = if (s.baseline == "middle") item.y - s.size * 0.65 else item.y - s.size
                        out.add(
                            "<rect x=\"${f2(x)}\" y=\"${f2(y)}\" width=\"${f2(approx)}\" height=\"${f2(s.size * 1.35)}\" fill=\"#ffffff\" opacity=\"0.82\"$transform/>"
                        )
                    }
                    out.add(
                        "<text x=\"${f2(item.x)}\" y=\"${f2(item.y)}\" font-family=\"${esc(s.family)}\" font-size=\"${jsNum(s.size)}\" font-weight=\"${if (s.bold) "bold" else "normal"}\" font-style=\"${if (s.italic) "italic" else "normal"}\" fill=\"${s.color}\" text-anchor=\"$anchor\" dominant-baseline=\"$dominant\"$transform>${esc(item.text)}</text>"
                    )
                }
                is FigItem.Line -> {
                    val dash = if (item.dash.isNotEmpty()) " stroke-dasharray=\"${item.dash.joinToString(" ") { jsNum(it) }}\"" else ""
                    out.add(
                        "<line x1=\"${f2(item.x1)}\" y1=\"${f2(item.y1)}\" x2=\"${f2(item.x2)}\" y2=\"${f2(item.y2)}\" stroke=\"${item.color}\" stroke-width=\"${jsNum(item.width)}\"$dash/>"
                    )
                }
                is FigItem.Rect -> {
                    out.add(
                        "<rect x=\"${f2(item.x)}\" y=\"${f2(item.y)}\" width=\"${f2(item.w)}\" height=\"${f2(item.h)}\" fill=\"${item.fill ?: "none"}\" stroke=\"${item.color}\" stroke-width=\"${jsNum(item.width)}\"/>"
                    )
                }
                is FigItem.Arrow -> {
                    out.add(
                        "<line x1=\"${f2(item.x1)}\" y1=\"${f2(item.y1)}\" x2=\"${f2(item.x2)}\" y2=\"${f2(item.y2)}\" stroke=\"${item.color}\" stroke-width=\"${jsNum(item.width)}\"/>"
                    )
                    val ang = Math.atan2(item.y2 - item.y1, item.x2 - item.x1)
                    val p1 = "${f2(item.x2 - item.head * Math.cos(ang - 0.42))},${f2(item.y2 - item.head * Math.sin(ang - 0.42))}"
                    val p2 = "${f2(item.x2 - item.head * Math.cos(ang + 0.42))},${f2(item.y2 - item.head * Math.sin(ang + 0.42))}"
                    out.add(
                        "<polygon points=\"${f2(item.x2)},${f2(item.y2)} $p1 $p2\" fill=\"${item.color}\"/>"
                    )
                }
            }
        }
        out.add("</svg>")
        return out.joinToString("\n")
    }

    /** Full processed image with the crop frame, well numbers and splice marks. */
    fun renderUncropped(project: GelProject, processed: ProcessedImage, scale: Double = 1.0): Bitmap {
        val w = Math.round(processed.width * scale).toInt().coerceAtLeast(1)
        val h = Math.round(processed.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(processed.bitmap, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), bitmapPaint)
        canvas.save()
        canvas.scale(scale.toFloat(), scale.toFloat())
        val iw = processed.width.toDouble()
        val ih = processed.height.toDouble()
        val cr = project.crop
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = parseCssColor("#ff8f1f")
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        }
        canvas.drawRect(
            (cr.left * iw).toFloat(), (cr.top * ih).toFloat(),
            ((1 - cr.right) * iw).toFloat(), ((1 - cr.bottom) * ih).toFloat(),
            framePaint,
        )
        val labelSize = maxOf(10.0, Math.round(ih * 0.018).toDouble())
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSize.toFloat()
            typeface = Typeface.MONOSPACE
            color = parseCssColor("#ff8f1f")
            textAlign = Paint.Align.LEFT
        }
        project.lanes.forEachIndexed { i, lane ->
            val x = lane.x * iw
            canvas.drawText("${i + 1}", (x - 4).toFloat(), maxOf(12.0, cr.top * ih - 5).toFloat(), textPaint)
            if (lane.removed == true) {
                val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = parseCssColor("#ff2d2d")
                    strokeWidth = 2f
                }
                canvas.drawLine(x.toFloat(), (cr.top * ih).toFloat(), x.toFloat(), ((1 - cr.bottom) * ih).toFloat(), rp)
            }
        }
        canvas.restore()
        return bmp
    }
}
