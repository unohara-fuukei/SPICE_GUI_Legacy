package com.spice.nativeapp.tools.gel

/**
 * Figure layout shared by the on-screen preview, PNG/TIFF/SVG/PPTX exports and
 * hit testing. 1:1 移植自原版 src/lib/tools/gel/figure.ts。
 *
 * Build a list of primitives once, then rasterise or serialise it; this keeps
 * every output identical to what the user sees.
 * 文本测量用 android.graphics.Paint（原版用 canvas.measureText）。
 */

data class FigBox(val x: Double, val y: Double, val w: Double, val h: Double)

sealed class FigHit {
    data class LaneName(val laneId: String) : FigHit()
    data class Band(val laneId: String, val bandIndex: Int) : FigHit()
    data class ConditionCell(val rowId: String, val laneId: String) : FigHit()
    data class Group(val groupId: String) : FigHit()
    data class Annotation(val annotationId: String) : FigHit()
    data class LaneGuide(val laneId: String) : FigHit()
}

data class TextStyle(
    val family: String,
    val size: Double,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: String,
    val angle: Double? = null,
    /** "left" | "center" | "right" */
    val align: String? = null,
    /** "top" | "middle" | "alphabetic" | "bottom" */
    val baseline: String? = null,
)

sealed class FigItem {
    data class Image(
        val x: Double, val y: Double, val w: Double, val h: Double,
        /** "gel" | "box" */
        val source: String,
        val box: FigBox? = null,
    ) : FigItem()

    data class Text(
        val x: Double, val y: Double, val text: String,
        val style: TextStyle, val bbox: FigBox,
        val halo: Boolean = false, val hit: FigHit? = null,
    ) : FigItem()

    data class Line(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val color: String, val width: Double,
        val dash: List<Double> = emptyList(),
    ) : FigItem()

    data class Rect(
        val x: Double, val y: Double, val w: Double, val h: Double,
        val color: String, val width: Double,
        val fill: String? = null, val hit: FigHit? = null,
    ) : FigItem()

    data class Arrow(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val color: String, val width: Double, val head: Double,
    ) : FigItem()
}

class FigureLayout(
    val width: Double,
    val height: Double,
    val gel: FigBox,
    val crop: GelProject.GelCrop,
    val gelDark: Boolean,
    val fontFamily: String,
    val items: List<FigItem>,
)

object GelFigure {

    const val PX_PER_MM = 96.0 / 25.4
    const val MARGIN = 8.0
    const val UNIT_ROW = 13.0
    const val GEL_HALO = "#ffffff"

    const val DEFAULT_INK = "#111111"
    const val DEFAULT_ON_GEL_LIGHT = "#111111"
    const val DEFAULT_ON_GEL_DARK = "#f4f4f4"
    const val RED_ANNOTATION = "#d21f2a"

    /** CSS 字体族 → Android 字体族（仅影响像素测量与位图绘制；SVG 导出保留原名）。 */
    private fun androidFamily(family: String): String = when (family) {
        "Times New Roman", "Cambria" -> "serif"
        "Arial", "Helvetica", "Calibri" -> "sans-serif"
        else -> family
    }

    private val measurePaint by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    fun measureWidth(text: String, style: TextStyle): Double {
        val p = measurePaint
        p.textSize = style.size.toFloat()
        val tf = android.graphics.Typeface.create(
            androidFamily(style.family),
            when {
                style.bold && style.italic -> android.graphics.Typeface.BOLD_ITALIC
                style.bold -> android.graphics.Typeface.BOLD
                style.italic -> android.graphics.Typeface.ITALIC
                else -> android.graphics.Typeface.NORMAL
            },
        )
        p.typeface = tf
        return p.measureText(text).toDouble()
    }

    private class WrappedLabel(val lines: List<String>, val style: TextStyle)

    private fun wrapLabel(text: String, maxWidth: Double, style: TextStyle, mode: String): WrappedLabel {
        if (text.isEmpty()) return WrappedLabel(listOf(""), style)
        val width = measureWidth(text, style)
        if (width <= maxWidth || mode == "one") return WrappedLabel(listOf(text), style)
        if (mode == "shrink") {
            var size = style.size
            while (size > 5 && measureWidth(text, style.copy(size = size)) > maxWidth) size -= 0.5
            return WrappedLabel(listOf(text), style.copy(size = size))
        }
        // Wrap at spaces when possible, otherwise per character (CJK).
        val words: List<String> =
            if (text.contains(' ')) Regex("(\\s+)").findAll(text).map { it.value }.let { toks ->
                // 交替 word/空白 token，与 JS split(/(\s+)/) 保持一致
                val out = ArrayList<String>()
                var rest = text
                val delims = toks.toList()
                for (d in delims) {
                    val idx = rest.indexOf(d)
                    if (idx > 0) out.add(rest.substring(0, idx))
                    out.add(d)
                    rest = rest.substring(idx + d.length)
                }
                if (rest.isNotEmpty()) out.add(rest)
                out
            }
            else text.toCharArray().map { it.toString() }
        val lines = ArrayList<String>()
        var line = ""
        for (word in words) {
            val next = line + word
            if (line.isNotEmpty() && measureWidth(next, style) > maxWidth) {
                lines.add(line.trimEnd())
                line = word.trimStart()
            } else {
                line = next
            }
            if (!text.contains(' ') && measureWidth(line, style) > maxWidth && line.length > 1) {
                lines.add(line.substring(0, line.length - 1))
                line = line.substring(line.length - 1)
            }
        }
        if (line.isNotEmpty()) lines.add(line.trimEnd())
        return WrappedLabel(lines.take(4), style)
    }

    private fun annotationColor(a: GelAnnotation, gelDark: Boolean): String =
        if (a.color == "red") RED_ANNOTATION
        else if (gelDark) DEFAULT_ON_GEL_DARK else DEFAULT_ON_GEL_LIGHT

    private fun laneColor(lane: GelLane, gelDark: Boolean): String =
        if (lane.style?.red == true) RED_ANNOTATION
        else if (gelDark) DEFAULT_ON_GEL_DARK else DEFAULT_ON_GEL_LIGHT

    class FigureOptions(val widthMm: Double? = null)

    fun buildFigure(
        project: GelProject,
        processed: ProcessedImage,
        opts: FigureOptions = FigureOptions(),
    ): FigureLayout {
        val crop = project.crop
        val visW = maxOf(0.05, 1 - crop.left - crop.right)
        val visH = maxOf(0.05, 1 - crop.top - crop.bottom)
        val family = project.layout.fontFamily
        val layout = project.layout
        val widthMm = opts.widthMm ?: layout.figureWidthMm
        val width = maxOf(280.0, widthMm * PX_PER_MM)

        val sideTexts = project.annotations.filterIsInstance<GelAnnotation.SideText>()
        val arrows = project.annotations.filterIsInstance<GelAnnotation.Arrow>()
        val brackets = project.annotations.filterIsInstance<GelAnnotation.Bracket>()
        val hasTop = sideTexts.any { it.side == "top" }
        val enlargeBoxes = project.annotations.filterIsInstance<GelAnnotation.Box>().filter { it.enlarge }
        val labelFs0 = layout.labelFontSize

        // Reserve a column on each side for side text, brackets and arrow labels;
        // otherwise those annotations would be clipped at the figure edge.
        val laneById = project.lanes.associateBy { it.id }
        fun arrowWidth(side: String): Double {
            var w = 0.0
            for (a in arrows) {
                if (a.side != side) continue
                val lane = laneById[a.laneId]
                val band = lane?.bands?.getOrNull(a.bandIndex)
                val arrowLabel = a.label
                val text = if (!arrowLabel.isNullOrEmpty()) arrowLabel
                else (if (band?.size != null) "${if (band.approx == true) "≈" else ""}${formatSize(band.size!!)}" else "")
                w = maxOf(w, measureWidth(text, TextStyle(family, a.fontSize ?: labelFs0, color = "#000")))
            }
            return if (w != 0.0) w + 10 else 0.0
        }
        fun sideTextW(side: String): Double =
            if (sideTexts.any { it.side == side }) 14 + labelFs0 else 0.0
        fun bracketW(side: String): Double =
            if (brackets.any { it.side == side }) 14 + labelFs0 else 0.0
        val leftW = maxOf(sideTextW("left"), arrowWidth("left"), bracketW("left"))
        val rightW = maxOf(sideTextW("right"), arrowWidth("right"), bracketW("right"))

        // The gel occupies the full width between side columns; enlarged boxes take
        // a column on the right, so the gel shrinks to leave room for them.
        val gelArea = width - MARGIN * 2 - leftW - rightW
        val enlargeW = if (enlargeBoxes.isNotEmpty()) maxOf(60.0, gelArea * 0.34) + 12 else 0.0
        val gelW = maxOf(120.0, gelArea - enlargeW)
        val gelH = gelW / ((processed.width * visW) / maxOf(1.0, (processed.height * visH).toDouble()))
        val gelX = MARGIN + leftW
        val gelY = MARGIN + UNIT_ROW + (if (hasTop) layout.labelFontSize * 1.7 else 0.0)

        val gelDark = isDark(processed)
        val inK = if (gelDark) DEFAULT_ON_GEL_DARK else DEFAULT_ON_GEL_LIGHT
        val visible = project.lanes.filter { it.removed != true && inCropX(it.x, crop) }

        // --- rows under the gel -------------------------------------------------
        val nameFs = layout.nameFontSize
        val labelFs = layout.labelFontSize
        val angle = maxOf(0.0, minOf(90.0, layout.nameAngle))
        val nameRowBase = nameFs * 1.55 + (if (angle != 0.0) maxNameWidth(visible, family, nameFs) * Math.sin(Math.toRadians(angle)) else 0.0)

        // Lane names can collide at the chosen width; stagger them onto two rows.
        val nameSlots = placeNames(visible, crop, gelX, gelW, nameRowBase, nameFs, family)
        val nameRowH = if (nameSlots.twoRows) nameRowBase * 1.9 else nameRowBase

        val condRows = project.conditionRows
        val condH = condRows.size * labelFs * 1.45
        val namesFirst = !layout.conditionsNearestGel
        val namesY = gelY + gelH + (if (namesFirst) 0.0 else condH) + nameFs * 0.95
        val condY = gelY + gelH + (if (namesFirst) nameRowH else 0.0)
        val below = gelY + gelH + nameRowH + condH

        // --- groups -------------------------------------------------------------
        val groupItems = ArrayList<FigItem>()
        val groupBottom = placeGroups(project, visible, crop, gelX, gelW, below + 4, labelFs, family, gelDark, groupItems)
        var cursorY = groupBottom + 6

        // --- bottom caption -----------------------------------------------------
        val bottomText = sideTexts.firstOrNull { it.side == "bottom" }
        if (bottomText != null) cursorY += labelFs * 1.6
        var height = cursorY + MARGIN

        val items = ArrayList<FigItem>()
        items.add(FigItem.Image(gelX, gelY, gelW, gelH, "gel"))

        // Unit heading beside the upper edge.
        items.add(
            FigItem.Text(
                x = gelX, y = gelY - 4, text = project.unit,
                style = TextStyle(family, labelFs, bold = true, color = DEFAULT_INK, align = "left", baseline = "alphabetic"),
                bbox = FigBox(gelX, gelY - 4 - labelFs, 40.0, labelFs + 2),
            )
        )

        // Lane guides, band labels and band marks.
        for (lane in visible) {
            val x = mapX(lane.x, crop, gelX, gelW)
            val nameHit = FigHit.LaneName(lane.id)
            val namePlace = nameSlots.positions[lane.id]
            if (namePlace != null) {
                val style = TextStyle(
                    family = family,
                    size = lane.style?.fontSize ?: nameFs,
                    italic = lane.style?.italic == true,
                    color = laneColor(lane, gelDark),
                    angle = angle,
                    align = "center",
                    baseline = "top",
                )
                val baseY = namesY + namePlace.row * nameRowBase
                val w = measureWidth(lane.name, style)
                val hitW = maxOf(18.0, w + 6)
                items.add(
                    FigItem.Text(
                        x = x, y = baseY, text = lane.name, style = style,
                        bbox = FigBox(x - hitW / 2, baseY, hitW, nameFs * 1.3),
                        hit = nameHit,
                    )
                )
            }

            // Faint guide under the name to the gel.
            if (lane.name.isNotEmpty()) {
                items.add(FigItem.Line(x, gelY + gelH, x, gelY + gelH + 3, inK, 0.6))
            }

            // Band labels, pushed apart so they never overlap.
            data class LabelPos(val band: GelBand, val index: Int, var y: Double)
            val labels = lane.bands
                .mapIndexed { index, band -> Pair(band, index) }
                .filter { (band, _) -> band.hidden != true && band.showSize == true && band.size != null && inCropY(band.y, crop) }
                .map { (band, index) -> LabelPos(band, index, mapY(band.y, crop, gelY, gelH)) }
                .sortedBy { it.y }
            val step = labelFs + 1.2
            for (i in 1 until labels.size) {
                if (labels[i].y < labels[i - 1].y + step) labels[i].y = labels[i - 1].y + step
            }
            val overflow = if (labels.isNotEmpty()) labels[labels.size - 1].y - (gelY + gelH - 2) else 0.0
            if (overflow > 0) for (l in labels) l.y = maxOf(gelY + 2, l.y - overflow)

            for (l in labels) {
                val approx = if (l.band.approx == true) "≈" else ""
                val text = "$approx${formatSize(l.band.size!!)}"
                val laneFs = lane.style?.fontSize
                val style = TextStyle(
                    family,
                    if (laneFs != null) maxOf(6.0, laneFs - 0.5) else labelFs,
                    color = inK, align = "left", baseline = "middle",
                )
                val w = measureWidth(text, style)
                val bx = x + 5
                items.add(
                    FigItem.Text(
                        x = bx, y = l.y, text = text, style = style,
                        bbox = FigBox(bx, l.y - labelFs * 0.8, w, labelFs * 1.4),
                        halo = true, hit = FigHit.Band(lane.id, l.index),
                    )
                )
                if (l.band.mark != null) {
                    val mw = measureWidth(l.band.mark!!, style.copy(bold = true))
                    items.add(
                        FigItem.Text(
                            x = bx + w + 3, y = l.y, text = l.band.mark!!,
                            style = style.copy(bold = true),
                            bbox = FigBox(bx + w + 2, l.y - labelFs, mw + 2, labelFs * 2),
                            halo = true,
                        )
                    )
                }
            }

            // A small tick where each band was detected (even if the label is hidden).
            for ((index, band) in lane.bands.withIndex()) {
                if (band.hidden == true || !inCropY(band.y, crop)) continue
                val y = mapY(band.y, crop, gelY, gelH)
                items.add(
                    FigItem.Line(
                        x - 2, y, x + 2, y,
                        if (lane.isMarker) inK else "rgba(0,0,0,0.35)",
                        if (lane.isMarker) 0.9 else 0.5,
                    )
                )
                items.add(
                    FigItem.Rect(x - 4, y - 4, 8.0, 8.0, "transparent", 0.0, hit = FigHit.Band(lane.id, index))
                )
            }
        }

        // Condition matrix.
        condRows.forEachIndexed { ri, row ->
            val y = condY + ri * labelFs * 1.45 + labelFs * 0.9
            val style = TextStyle(family, labelFs, color = DEFAULT_INK, align = "right", baseline = "middle")
            val lw = measureWidth(row.label, style)
            items.add(
                FigItem.Text(
                    x = gelX - 3, y = y, text = row.label, style = style,
                    bbox = FigBox(gelX - 3 - lw, y - labelFs, lw, labelFs * 2),
                )
            )
            for (lane in visible) {
                val cell = row.cells[lane.id] ?: ""
                val x = mapX(lane.x, crop, gelX, gelW)
                items.add(
                    FigItem.Text(
                        x = x, y = y, text = cell,
                        style = style.copy(align = "center", bold = true),
                        bbox = FigBox(x - 6, y - labelFs, 12.0, labelFs * 2),
                        hit = FigHit.ConditionCell(row.id, lane.id),
                    )
                )
            }
        }

        // --- annotations --------------------------------------------------------
        placeArrows(arrows, project, visible, crop, items, gelX, gelW, gelY, gelH, family, labelFs, gelDark)

        for (b in brackets) {
            val sideX = if (b.side == "left") gelX - 6 else gelX + gelW + 6
            val color = annotationColor(b, gelDark)
            val vertical = b.side == "left" || b.side == "right"
            if (vertical) {
                val y0 = mapY(minOf(b.start, b.end), crop, gelY, gelH)
                val y1 = mapY(maxOf(b.start, b.end), crop, gelY, gelH)
                items.add(FigItem.Line(sideX, y1, sideX, y0, color, 1.0))
                items.add(FigItem.Line(sideX - 3, y0, sideX + 3, y0, color, 1.0))
                items.add(FigItem.Line(sideX - 3, y1, sideX + 3, y1, color, 1.0))
                val style = TextStyle(family, b.fontSize ?: labelFs, color = color, angle = 90.0, align = "center", baseline = "middle")
                val w = measureWidth(b.label, style)
                val tx = if (b.side == "left") sideX - 5 else sideX + 5
                items.add(
                    FigItem.Text(
                        x = tx, y = (y0 + y1) / 2, text = b.label, style = style,
                        bbox = FigBox(tx - labelFs * 0.7, (y0 + y1) / 2 - w / 2, labelFs * 1.4, w),
                        hit = FigHit.Annotation(b.id),
                    )
                )
            } else {
                val x0 = mapX(minOf(b.start, b.end), crop, gelX, gelW)
                val x1 = mapX(maxOf(b.start, b.end), crop, gelX, gelW)
                val by = if (b.side == "top") gelY - 6 else gelY + gelH + 6
                items.add(FigItem.Line(x0, by, x1, by, color, 1.0))
                items.add(FigItem.Line(x0, by - 3, x0, by + 3, color, 1.0))
                items.add(FigItem.Line(x1, by - 3, x1, by + 3, color, 1.0))
                val style = TextStyle(family, b.fontSize ?: labelFs, color = color, align = "center", baseline = if (b.side == "top") "bottom" else "top")
                items.add(
                    FigItem.Text(
                        x = (x0 + x1) / 2,
                        y = if (b.side == "top") by - 5 else by + labelFs,
                        text = b.label, style = style,
                        bbox = FigBox(x0, if (b.side == "top") by - labelFs - 5 else by, maxOf(20.0, x1 - x0), labelFs * 2),
                        hit = FigHit.Annotation(b.id),
                    )
                )
            }
        }

        val boxes = project.annotations.filterIsInstance<GelAnnotation.Box>()
        for (box in boxes) {
            val bx = mapX(box.x, crop, gelX, gelW)
            val by = mapY(box.y, crop, gelY, gelH)
            val bw = (box.w / visW) * gelW
            val bh = (box.h / visH) * gelH
            val visibleBox = clipRect(FigBox(bx, by, bw, bh), FigBox(gelX, gelY, gelW, gelH)) ?: continue
            val color = annotationColor(box, gelDark)
            items.add(FigItem.Rect(visibleBox.x, visibleBox.y, visibleBox.w, visibleBox.h, color, 1.2, hit = FigHit.Annotation(box.id)))
            if (box.label.isNotEmpty()) {
                val style = TextStyle(family, box.fontSize ?: labelFs, color = color, align = "left", baseline = "alphabetic")
                val w = measureWidth(box.label, style)
                items.add(
                    FigItem.Text(
                        x = visibleBox.x, y = visibleBox.y - 3, text = box.label, style = style,
                        bbox = FigBox(visibleBox.x, visibleBox.y - 3 - labelFs, w, labelFs + 2),
                        halo = true, hit = FigHit.Annotation(box.id),
                    )
                )
            }
            if (box.enlarge) {
                // An enlarged copy sits to the right of the figure and adds width.
                val ew = enlargeW - 12
                val eh = ew * (bh / maxOf(1.0, bw))
                val ex = gelX + gelW + 8
                val ey = gelY + minOf(gelH - eh, maxOf(0.0, by - gelY))
                items.add(FigItem.Image(ex, ey, ew, eh, "box", FigBox(box.x, box.y, box.w, box.h)))
                items.add(FigItem.Rect(ex, ey, ew, eh, color, 1.0, hit = FigHit.Annotation(box.id)))
                if (box.label.isNotEmpty()) {
                    val style = TextStyle(family, labelFs, color = color, align = "center", baseline = "alphabetic")
                    items.add(
                        FigItem.Text(
                            x = ex + ew / 2, y = ey + eh + labelFs + 2, text = box.label, style = style,
                            bbox = FigBox(ex, ey + eh, ew, labelFs * 2),
                        )
                    )
                }
                // The enlarged copy may extend past the nominal width.
                height = maxOf(height, ey + eh + labelFs * 2 + MARGIN)
            }
        }

        for (t in sideTexts) {
            val style = TextStyle(family, t.fontSize ?: labelFs, color = annotationColor(t, gelDark))
            if (t.side == "top") {
                val w = measureWidth(t.text, style)
                val y = gelY - UNIT_ROW - labelFs * 0.4
                items.add(
                    FigItem.Text(
                        x = gelX + gelW / 2, y = y, text = t.text,
                        style = style.copy(align = "center", baseline = "alphabetic"),
                        bbox = FigBox(gelX + gelW / 2 - w / 2, y - labelFs, w, labelFs * 2),
                        hit = FigHit.Annotation(t.id),
                    )
                )
            } else if (t.side == "bottom") {
                val w = measureWidth(t.text, style)
                val y = height - MARGIN - labelFs * 1.4
                items.add(
                    FigItem.Text(
                        x = gelX + gelW / 2, y = y, text = t.text,
                        style = style.copy(align = "center"),
                        bbox = FigBox(gelX + gelW / 2 - w / 2, y - labelFs, w, labelFs * 2),
                        hit = FigHit.Annotation(t.id),
                    )
                )
            } else {
                val edge = if (t.side == "left") gelX - leftW / 2 else gelX + gelW + rightW / 2
                val y = gelY + visH * gelH * t.pos
                val angle = if (t.side == "left") -90.0 else 90.0
                val w = measureWidth(t.text, style)
                items.add(
                    FigItem.Text(
                        x = edge, y = y, text = t.text,
                        style = style.copy(angle = angle, align = "center", baseline = "middle"),
                        bbox = FigBox(edge - labelFs, y - w / 2, labelFs * 2, w),
                        hit = FigHit.Annotation(t.id),
                    )
                )
            }
        }

        // Splice marks for removed lanes (never silently delete evidence).
        for (lane in project.lanes) {
            if (lane.removed != true || !inCropX(lane.x, crop)) continue
            val x = mapX(lane.x, crop, gelX, gelW)
            val color = if (lane.splice == "line") inK else "#ffffff"
            items.add(FigItem.Line(x, gelY, x, gelY + gelH, color, if (lane.splice == "line") 1.4 else 4.0))
        }

        items.addAll(groupItems)
        return FigureLayout(width, height, FigBox(gelX, gelY, gelW, gelH), crop, gelDark, family, items)
    }

    private fun maxNameWidth(lanes: List<GelLane>, family: String, size: Double): Double {
        var w = 0.0
        for (l in lanes) w = maxOf(w, measureWidth(l.name, TextStyle(family, size, color = "#000")))
        return w
    }

    private class NameSlot(val row: Int)

    private class NameSlots(val positions: Map<String, NameSlot>, val twoRows: Boolean)

    private fun placeNames(
        lanes: List<GelLane>,
        crop: GelProject.GelCrop,
        gelX: Double,
        gelW: Double,
        rowH: Double,
        size: Double,
        family: String,
    ): NameSlots {
        val positions = HashMap<String, NameSlot>()
        val occupied = ArrayList<Triple<Int, Double, Double>>() // row, x0, x1
        var twoRows = false
        for (lane in lanes.sortedBy { it.x }) {
            val x = mapX(lane.x, crop, gelX, gelW)
            val w = maxOf(8.0, measureWidth(lane.name, TextStyle(family, lane.style?.fontSize ?: size, color = "#000")))
            val x0 = x - w / 2 - 1
            val x1 = x + w / 2 + 1
            var row = 0
            while (occupied.any { o -> o.first == row && !(x1 < o.second || x0 > o.third) }) row++
            if (row > 1) row = 1
            if (row == 1) twoRows = true
            occupied.add(Triple(row, x0, x1))
            positions[lane.id] = NameSlot(row)
        }
        return NameSlots(positions, twoRows)
    }

    private fun placeGroups(
        project: GelProject,
        visible: List<GelLane>,
        crop: GelProject.GelCrop,
        gelX: Double,
        gelW: Double,
        startY: Double,
        fs: Double,
        family: String,
        gelDark: Boolean,
        out: MutableList<FigItem>,
    ): Double {
        val lanesById = visible.associateBy { it.id }
        val rows = ArrayList<MutableList<Pair<Double, Double>>>() // per row: x0..x1 spans
        var y = startY
        for (group in project.groups) {
            val xs = group.laneIds
                .mapNotNull { lanesById[it] }
                .map { mapX(it.x, crop, gelX, gelW) }
            if (xs.isEmpty()) continue
            val x0 = xs.min() - 6
            val x1 = xs.max() + 6
            val baseStyle = TextStyle(family, fs, color = if (gelDark) DEFAULT_ON_GEL_DARK else DEFAULT_INK, align = "center")
            val fitted = wrapLabel(group.name, maxOf(20.0, x1 - x0), baseStyle, project.layout.groupLabelFit)
            val labelW = fitted.lines.maxOf { measureWidth(it, fitted.style) }
            var row = 0
            while (
                rows.getOrNull(row)?.any { r -> !(x1 < r.first || x0 > r.second) || Math.abs((x0 + x1) / 2 - (r.first + r.second) / 2) < labelW } == true
            ) {
                row++
            }
            while (rows.size <= row) rows.add(ArrayList())
            rows[row].add(x0 to x1)
            val gy = startY + row * (fs * (fitted.lines.size + 1.8))
            out.add(FigItem.Line(x0, gy + 4, x0, gy, DEFAULT_INK, 0.9))
            out.add(FigItem.Line(x0, gy, x1, gy, DEFAULT_INK, 0.9))
            out.add(FigItem.Line(x1, gy, x1, gy + 4, DEFAULT_INK, 0.9))
            fitted.lines.forEachIndexed { i, line ->
                out.add(
                    FigItem.Text(
                        x = (x0 + x1) / 2, y = gy + fs * (1.1 + i), text = line,
                        style = fitted.style.copy(align = "center", baseline = "top"),
                        bbox = FigBox((x0 + x1) / 2 - labelW / 2, gy, labelW, fs * 1.3),
                        hit = FigHit.Group(group.id),
                    )
                )
            }
            y = maxOf(y, gy + fs * (fitted.lines.size + 2.1))
        }
        return y
    }

    private fun placeArrows(
        arrows: List<GelAnnotation.Arrow>,
        project: GelProject,
        visible: List<GelLane>,
        crop: GelProject.GelCrop,
        out: MutableList<FigItem>,
        gelX: Double,
        gelW: Double,
        gelY: Double,
        gelH: Double,
        family: String,
        fs: Double,
        gelDark: Boolean,
    ) {
        val lanesById = visible.associateBy { it.id }
        val rows = ArrayList<Double>()
        for (a in arrows) {
            val lane = lanesById[a.laneId] ?: continue
            val band = lane.bands.getOrNull(a.bandIndex) ?: continue
            if (band.hidden == true || !inCropY(band.y, crop)) continue
            val tx = mapX(lane.x, crop, gelX, gelW)
            val ty = mapY(band.y, crop, gelY, gelH)
            val color = annotationColor(a, gelDark)
            val arrowLabel = a.label
            val label = if (!arrowLabel.isNullOrEmpty()) arrowLabel
            else (if (band.size != null) "${if (band.approx == true) "≈" else ""}${formatSize(band.size!!)}" else "")
            val size = a.fontSize ?: fs
            val labelW = if (label.isNotEmpty()) measureWidth(label, TextStyle(family, size, color = color)) else 0.0
            val edge = if (a.side == "left") gelX - 4 else gelX + gelW + 4
            val tail = edge
            // Stack arrows that would otherwise collide.
            var labelY = ty
            for (r in rows) if (Math.abs(r - labelY) < size + 1.5) labelY = r + size + 1.5
            rows.add(labelY)
            out.add(FigItem.Arrow(tail, labelY, tx, ty, color, 0.9, 3.2))
            if (label.isNotEmpty()) {
                out.add(
                    FigItem.Text(
                        x = if (a.side == "left") edge - labelW - 3 else edge + 3,
                        y = labelY, text = label,
                        style = TextStyle(family, size, color = color, align = "left", baseline = "middle"),
                        bbox = FigBox(
                            if (a.side == "left") edge - labelW - 3 else edge + 3,
                            labelY - size * 0.7, labelW + 2, size * 1.4,
                        ),
                        hit = FigHit.Annotation(a.id),
                    )
                )
            }
        }
    }

    fun formatSize(size: Double): String {
        if (size >= 1000) {
            val k = size / 1000
            return if (k % 1 == 0.0) "${k.toLong()}k" else "${"%.1f".format(k)}k"
        }
        if (size >= 100) return Math.round(size).toString()
        if (size >= 10) return trimZero(Math.round(size * 10) / 10.0)
        return trimZero(Math.round(size * 100) / 100.0)
    }

    /** JS String(number) 的整数值不带小数点。 */
    private fun trimZero(v: Double): String =
        if (v % 1 == 0.0) v.toLong().toString() else v.toString()

    fun mapX(x: Double, crop: GelProject.GelCrop, gelX: Double, gelW: Double): Double =
        gelX + ((x - crop.left) / maxOf(0.05, 1 - crop.left - crop.right)) * gelW

    fun mapY(y: Double, crop: GelProject.GelCrop, gelY: Double, gelH: Double): Double =
        gelY + ((y - crop.top) / maxOf(0.05, 1 - crop.top - crop.bottom)) * gelH

    fun unmapX(px: Double, crop: GelProject.GelCrop, gelX: Double, gelW: Double): Double =
        crop.left + ((px - gelX) / gelW) * maxOf(0.05, 1 - crop.left - crop.right)

    fun unmapY(py: Double, crop: GelProject.GelCrop, gelY: Double, gelH: Double): Double =
        crop.top + ((py - gelY) / gelH) * maxOf(0.05, 1 - crop.top - crop.bottom)

    private fun inCropX(x: Double, crop: GelProject.GelCrop): Boolean =
        x >= crop.left - 0.01 && x <= 1 - crop.right + 0.01

    private fun inCropY(y: Double, crop: GelProject.GelCrop): Boolean =
        y >= crop.top - 0.01 && y <= 1 - crop.bottom + 0.01

    private fun clipRect(r: FigBox, bounds: FigBox): FigBox? {
        val x0 = maxOf(r.x, bounds.x)
        val y0 = maxOf(r.y, bounds.y)
        val x1 = minOf(r.x + r.w, bounds.x + bounds.w)
        val y1 = minOf(r.y + r.h, bounds.y + bounds.h)
        if (x1 - x0 < 0.5 || y1 - y0 < 0.5) return null
        return FigBox(x0, y0, x1 - x0, y1 - y0)
    }

    private fun isDark(processed: ProcessedImage): Boolean {
        val w = minOf(64, processed.width)
        val h = minOf(64, processed.height)
        if (w <= 0 || h <= 0) return false
        val px = IntArray(w * h)
        processed.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (p in px) {
            sum += ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114
        }
        return sum / px.size < 110
    }

    /** Exposed for the editor: nearest hit at a point in figure coordinates. */
    fun hitTest(layout: FigureLayout, x: Double, y: Double): FigHit? {
        for (i in layout.items.indices.reversed()) {
            val item = layout.items[i]
            when (item) {
                is FigItem.Text -> {
                    val hit = item.hit ?: continue
                    val b = item.bbox
                    if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) return hit
                }
                is FigItem.Rect -> {
                    val hit = item.hit ?: continue
                    if (x >= item.x && x <= item.x + item.w && y >= item.y && y <= item.y + item.h) return hit
                }
                else -> {}
            }
        }
        return null
    }
}
