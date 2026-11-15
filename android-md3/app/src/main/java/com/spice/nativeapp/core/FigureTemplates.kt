package com.spice.nativeapp.core

/**
 * FigureTemplates —— main tools/figure/templates.ts 的 1:1 移植（数据层）。
 *
 * 模板是纯文档工厂：每个场景以毫米在参考画布上排版，创建时经 mmToPx
 * （96/25.4）转 px；请求不同页宽时统一缩放（scale = requested/designWidth，
 * clamp 20..1200）。描边下限 0.4px（strokePx），字号下限 4pt（fontPt）。
 *
 * 节点字段与 main types.ts 构造器 + createScene 各 builder 选项一致。
 * FIGURE_TEMPLATES 的 name/desc 词条用 main zh 原文（figTemplate*）。
 */
object FigureTemplates {

    /** main types.ts MM_TO_PX */
    const val MM_TO_PX = 96.0 / 25.4

    fun mmToPx(mm: Double): Double = mm * MM_TO_PX

    /** main PALETTE（模板共享色板）。 */
    object Palette {
        const val INK = "#16222d"
        const val MUTED = "#5d6b78"
        const val LINE = "#8aa0ae"
        const val PANEL = "#f3f7fa"
        const val PANEL_ALT = "#e8f1f6"
        const val ACCENT = "#1f6f8b"
        const val ACCENT_DARK = "#14506a"
        const val ACCENT_SOFT = "#d9eaf1"
        const val WARM = "#f8ede1"
        const val WARM_STROKE = "#c46a1b"
        const val SUCCESS = "#e8f4ec"
        const val SUCCESS_STROKE = "#2f7d4a"
        const val WHITE = "#ffffff"
    }

    enum class Kind { RECT, ELLIPSE, TEXT, LINE, ARROW }

    data class TplNode(
        val kind: Kind,
        val name: String,
        val x: Double, val y: Double, val w: Double, val h: Double,
        val fill: String,
        val stroke: String,
        val strokeWidth: Double,
        val dash: String,
        val cornerRadius: Double,
        val opacity: Double,
        val rotation: Double,
        val text: String,
        val fontSize: Double,
        val fontWeight: Int,
        val italic: Boolean,
        val align: String,
        val lineHeight: Double,
        val padding: Double,
        val color: String,
        val background: String,
        val headStart: Boolean,
        val headEnd: Boolean,
        val headSize: Double,
        val groupId: Int
    )

    data class TplGroup(val id: Int, val name: String)

    data class TplDocument(
        val name: String,
        val widthMm: Double,
        val heightMm: Double,
        val nodes: List<TplNode>,
        val groups: List<TplGroup>
    ) {
        /** 文档像素尺寸（main documentWidthPx/HeightPx：mmToPx）。 */
        val widthPx: Double get() = mmToPx(widthMm)
        val heightPx: Double get() = mmToPx(heightMm)
    }

    /** main createScene：场景 DSL 与默认值逐项一致。 */
    private class Scene(private val name: String, designWidthMm: Double, private val designHeightMm: Double, widthMm: Double?) {
        private val requested: Double =
            if (widthMm != null && widthMm.isFinite() && widthMm > 0) widthMm.coerceIn(20.0, 1200.0) else designWidthMm
        private val scale: Double = requested / designWidthMm
        private val nodes = mutableListOf<TplNode>()
        private val groups = mutableListOf<TplGroup>()
        private var groupSeq = 0

        val doc: TplDocument get() = TplDocument(
            name = name,
            widthMm = roundMm(requested),
            heightMm = roundMm(designHeightMm * scale),
            nodes = nodes.toList(),
            groups = groups.toList()
        )

        private fun roundMm(v: Double): Double = Math.round(v * 100) / 100.0

        private fun px(mm: Double): Double = mmToPx(mm * scale)
        private fun strokePx(mm: Double): Double = maxOf(0.4, px(mm))
        private fun fontPt(pt: Double): Double = maxOf(4.0, pt * scale)

        // main baseNode 默认：fill #e8f1f8 / stroke #1f6f8b / strokeWidth 1.5 / dash solid / cornerRadius 0
        fun rect(x: Double, y: Double, w: Double, h: Double, opt: BoxOpts = BoxOpts()): TplNode {
            val node = TplNode(
                Kind.RECT, opt.name ?: "Panel", px(x), px(y), px(w), px(h),
                opt.fill ?: Palette.PANEL, opt.stroke ?: Palette.LINE, strokePx(opt.strokeWidth ?: 0.3),
                opt.dash ?: "solid", px(opt.cornerRadius ?: 0.0), opt.opacity ?: 1.0, opt.rotation ?: 0.0,
                "", 0.0, 400, false, "left", 0.0, 0.0, "", "none",
                false, false, 0.0, -1
            )
            nodes.add(node)
            return node
        }

        fun ellipse(x: Double, y: Double, w: Double, h: Double, opt: BoxOpts = BoxOpts()): TplNode {
            val node = TplNode(
                Kind.ELLIPSE, opt.name ?: "Ellipse", px(x), px(y), px(w), px(h),
                opt.fill ?: Palette.PANEL_ALT, opt.stroke ?: Palette.ACCENT, strokePx(opt.strokeWidth ?: 0.3),
                opt.dash ?: "solid", 0.0, opt.opacity ?: 1.0, opt.rotation ?: 0.0,
                "", 0.0, 400, false, "left", 0.0, 0.0, "", "none",
                false, false, 0.0, -1
            )
            nodes.add(node)
            return node
        }

        fun text(x: Double, y: Double, w: Double, h: Double, content: String, opt: TextOpts = TextOpts()): TplNode {
            val node = TplNode(
                Kind.TEXT, opt.name ?: (content.take(28).ifEmpty { "Text" }), px(x), px(y), px(w), px(h),
                "none", "none", 0.0, "solid", 0.0, opt.opacity ?: 1.0, opt.rotation ?: 0.0,
                content, fontPt(opt.fontSize ?: 10.0), opt.fontWeight ?: 400, opt.italic ?: false,
                opt.align ?: "left", opt.lineHeight ?: 1.25, px(opt.padding ?: 0.4),
                opt.color ?: Palette.INK, opt.background ?: "none",
                false, false, 0.0, -1
            )
            nodes.add(node)
            return node
        }

        private fun lineNode(arrow: Boolean, x: Double, y: Double, w: Double, h: Double, opt: LineOpts): TplNode {
            val node = TplNode(
                if (arrow) Kind.ARROW else Kind.LINE, opt.name ?: (if (arrow) "Arrow" else "Line"),
                px(x), px(y), px(w), px(h),
                "none", opt.stroke ?: Palette.INK, strokePx(opt.strokeWidth ?: 0.35),
                opt.dash ?: "solid", 0.0, opt.opacity ?: 1.0, 0.0,
                "", 0.0, 400, false, "left", 0.0, 0.0, "", "none",
                opt.headStart ?: false, opt.headEnd ?: arrow, px(opt.headSize ?: 2.6), -1
            )
            nodes.add(node)
            return node
        }

        fun line(x: Double, y: Double, w: Double, h: Double, opt: LineOpts = LineOpts()): TplNode =
            lineNode(false, x, y, w, h, opt)

        fun arrow(x: Double, y: Double, w: Double, h: Double, opt: LineOpts = LineOpts()): TplNode =
            lineNode(true, x, y, w, h, opt)

        fun group(groupName: String, members: List<TplNode>): TplGroup {
            groupSeq += 1
            val group = TplGroup(groupSeq, groupName)
            groups.add(group)
            val idx = nodes.size
            // 成员是刚 push 的末尾 N 个节点
            for (i in idx - members.size until idx) nodes[i] = nodes[i].copy(groupId = groupSeq)
            return group
        }
    }

    data class BoxOpts(
        val fill: String? = null, val stroke: String? = null, val strokeWidth: Double? = null,
        val dash: String? = null, val cornerRadius: Double? = null, val opacity: Double? = null,
        val name: String? = null, val rotation: Double? = null
    )

    data class TextOpts(
        val fontSize: Double? = null, val fontWeight: Int? = null, val italic: Boolean? = null,
        val align: String? = null, val color: String? = null, val background: String? = null,
        val lineHeight: Double? = null, val padding: Double? = null, val opacity: Double? = null,
        val name: String? = null, val rotation: Double? = null
    )

    data class LineOpts(
        val stroke: String? = null, val strokeWidth: Double? = null, val dash: String? = null,
        val headStart: Boolean? = null, val headEnd: Boolean? = null, val headSize: Double? = null,
        val name: String? = null, val opacity: Double? = null
    )

    // -------------------------------------------------------------- 场景构建

    private fun buildSingleColumn(widthMm: Double?): TplDocument {
        val s = Scene("Single column figure", 90.0, 110.0, widthMm)
        s.text(5.0, 5.0, 80.0, 9.0, "Figure title", TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700))
        s.text(5.0, 14.0, 80.0, 5.0, "Short subtitle describing the main finding",
            TextOpts(name = "Subtitle", fontSize = 9.0, italic = true, color = Palette.MUTED))
        val heroPanel = s.rect(5.0, 21.0, 80.0, 44.0,
            BoxOpts(name = "Panel A panel", fill = Palette.PANEL, stroke = Palette.ACCENT, strokeWidth = 0.45, cornerRadius = 1.5))
        val heroLabel = s.text(7.0, 23.0, 10.0, 5.0, "A",
            TextOpts(name = "Panel A label", fontSize = 12.0, fontWeight = 700, color = Palette.ACCENT))
        val heroMotif = s.ellipse(28.0, 30.0, 34.0, 22.0,
            BoxOpts(name = "Panel A motif", fill = Palette.ACCENT_SOFT, stroke = Palette.ACCENT, dash = "dashed"))
        val heroText = s.text(30.0, 37.0, 30.0, 7.0, "Overview",
            TextOpts(name = "Panel A text", fontSize = 10.0, fontWeight = 600, align = "center"))
        s.group("Panel A", listOf(heroPanel, heroLabel, heroMotif, heroText))
        val panelB = s.rect(5.0, 68.0, 38.0, 25.0,
            BoxOpts(name = "Panel B panel", fill = Palette.PANEL_ALT, stroke = Palette.LINE, cornerRadius = 1.2))
        s.text(6.5, 69.5, 7.0, 4.5, "B",
            TextOpts(name = "Panel B label", fontSize = 11.0, fontWeight = 700, color = Palette.ACCENT))
        s.text(7.0, 77.0, 34.0, 9.0, "Condition B",
            TextOpts(name = "Panel B text", fontSize = 9.0, align = "center", color = Palette.MUTED))
        s.rect(47.0, 68.0, 38.0, 25.0,
            BoxOpts(name = "Panel C panel", fill = Palette.PANEL_ALT, stroke = Palette.LINE, cornerRadius = 1.2))
        s.text(48.5, 69.5, 7.0, 4.5, "C",
            TextOpts(name = "Panel C label", fontSize = 11.0, fontWeight = 700, color = Palette.ACCENT))
        s.text(49.0, 77.0, 34.0, 9.0, "Condition C",
            TextOpts(name = "Panel C text", fontSize = 9.0, align = "center", color = Palette.MUTED))
        s.text(5.0, 97.0, 80.0, 9.0, "Figure 1. Summary caption describing panels A-C and the main conclusion.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, color = Palette.MUTED, lineHeight = 1.2))
        // main 此处仅对 Panel A 分组；B/C 保持未分组（原样照抄）。
        return s.doc
    }

    private fun buildDoubleColumn(widthMm: Double?): TplDocument {
        val s = Scene("Double column figure", 180.0, 105.0, widthMm)
        s.text(8.0, 5.0, 164.0, 9.0, "Figure title", TextOpts(name = "Title", fontSize = 18.0, fontWeight = 700))
        s.text(8.0, 14.0, 164.0, 5.0, "Subtitle for a two-column journal figure",
            TextOpts(name = "Subtitle", fontSize = 9.5, italic = true, color = Palette.MUTED))
        val panelA = s.rect(8.0, 24.0, 80.0, 50.0,
            BoxOpts(name = "Panel A panel", fill = Palette.PANEL, stroke = Palette.ACCENT, strokeWidth = 0.4, cornerRadius = 1.5))
        val labelA = s.text(10.0, 26.0, 8.0, 5.0, "A",
            TextOpts(name = "Panel A label", fontSize = 12.0, fontWeight = 700, color = Palette.ACCENT))
        val motifA = s.ellipse(30.0, 35.0, 36.0, 24.0,
            BoxOpts(name = "Panel A motif", fill = Palette.ACCENT_SOFT, stroke = Palette.ACCENT, dash = "dashed"))
        val textA = s.text(34.0, 42.0, 28.0, 8.0, "Signal",
            TextOpts(name = "Panel A text", fontSize = 9.5, fontWeight = 600, align = "center"))
        s.group("Panel A", listOf(panelA, labelA, motifA, textA))
        val panelB = s.rect(92.0, 24.0, 80.0, 50.0,
            BoxOpts(name = "Panel B panel", fill = Palette.PANEL_ALT, stroke = Palette.LINE, cornerRadius = 1.5))
        val labelB = s.text(94.0, 26.0, 8.0, 5.0, "B",
            TextOpts(name = "Panel B label", fontSize = 12.0, fontWeight = 700, color = Palette.ACCENT))
        val motifB = s.ellipse(114.0, 35.0, 36.0, 24.0,
            BoxOpts(name = "Panel B motif", fill = Palette.WARM, stroke = Palette.WARM_STROKE, dash = "dashed"))
        val textB = s.text(118.0, 42.0, 28.0, 8.0, "Control",
            TextOpts(name = "Panel B text", fontSize = 9.5, fontWeight = 600, align = "center"))
        s.group("Panel B", listOf(panelB, labelB, motifB, textB))
        val detailLabels = listOf("Detail 1", "Detail 2", "Detail 3", "Detail 4")
        detailLabels.forEachIndexed { index, label ->
            val x = 8.0 + index * 41.5
            s.rect(x, 80.0, 39.5, 12.0,
                BoxOpts(name = "Detail ${index + 1}", fill = Palette.ACCENT_SOFT, stroke = Palette.LINE, strokeWidth = 0.2, cornerRadius = 1.0))
            s.text(x + 1, 83.0, 37.5, 6.0, label,
                TextOpts(name = "Detail ${index + 1} label", fontSize = 8.0, align = "center", color = Palette.ACCENT_DARK))
        }
        s.text(8.0, 96.0, 164.0, 7.0, "Figure 1. Caption covering both conditions and the detail views.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, color = Palette.MUTED))
        return s.doc
    }

    private fun buildSlide(widthMm: Double?): TplDocument {
        val s = Scene("Slide 16:9 figure", 338.7, 190.5, widthMm)
        s.rect(0.0, 0.0, 338.7, 30.0,
            BoxOpts(name = "Title bar", fill = Palette.ACCENT, stroke = "none", strokeWidth = 0.0, cornerRadius = 0.0))
        s.text(12.0, 6.0, 210.0, 14.0, "Presentation figure",
            TextOpts(name = "Slide title", fontSize = 24.0, fontWeight = 700, color = Palette.WHITE))
        s.text(215.0, 10.0, 112.0, 8.0, "SPICE Figure Studio",
            TextOpts(name = "Slide tag", fontSize = 10.0, italic = true, align = "right", color = Palette.WHITE, opacity = 0.9))
        val margin = 12.0
        val gap = 10.0
        val columnWidth = (338.7 - margin * 2 - gap * 2) / 3
        val columnTitles = listOf("Context", "Approach", "Result")
        columnTitles.forEach { title ->
            val x = margin + columnTitles.indexOf(title) * (columnWidth + gap)
            val members = mutableListOf<TplNode>()
            members.add(s.rect(x, 44.0, columnWidth, 112.0,
                BoxOpts(name = "$title panel", fill = Palette.PANEL, stroke = Palette.LINE, strokeWidth = 0.25, cornerRadius = 1.5)))
            members.add(s.rect(x, 44.0, columnWidth, 11.0,
                BoxOpts(name = "$title header", fill = Palette.ACCENT_SOFT, stroke = "none", strokeWidth = 0.0, cornerRadius = 1.5)))
            members.add(s.ellipse(x + 4, 46.5, 6.0, 6.0,
                BoxOpts(name = "$title number", fill = Palette.ACCENT, stroke = "none", strokeWidth = 0.0)))
            members.add(s.text(x + 12, 46.5, columnWidth - 15, 7.0, title,
                TextOpts(name = "$title title", fontSize = 10.5, fontWeight = 700)))
            members.add(s.ellipse(x + 16, 66.0, columnWidth - 32, 34.0,
                BoxOpts(name = "$title motif", fill = Palette.PANEL_ALT, stroke = Palette.ACCENT, dash = "dashed")))
            members.add(s.text(x + 20, 76.0, columnWidth - 40, 10.0, "$title visual",
                TextOpts(name = "$title visual label", fontSize = 9.0, align = "center", color = Palette.MUTED)))
            members.add(s.text(x + 6, 106.0, columnWidth - 12, 16.0, "Add microscopy images, plots or diagrams here.",
                TextOpts(name = "$title note", fontSize = 8.0, color = Palette.MUTED, lineHeight = 1.25)))
            s.group(title, members)
        }
        s.text(12.0, 164.0, 314.7, 16.0,
            "Figure 1. Slide-ready summary. Replace placeholders with data panels and keep the caption below the fold.",
            TextOpts(name = "Caption", fontSize = 9.5, italic = true, color = Palette.MUTED, lineHeight = 1.25))
        return s.doc
    }

    private fun buildGraphicalAbstract(widthMm: Double?): TplDocument {
        val s = Scene("Graphical abstract", 180.0, 100.0, widthMm)
        s.text(10.0, 5.0, 160.0, 9.0, "Graphical abstract",
            TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700, align = "center"))
        val inputPanel = s.rect(8.0, 28.0, 44.0, 42.0,
            BoxOpts(name = "Input panel", fill = Palette.PANEL, stroke = Palette.LINE, cornerRadius = 1.5))
        val inputHeader = s.text(10.0, 30.0, 40.0, 5.0, "Input",
            TextOpts(name = "Input header", fontSize = 9.0, fontWeight = 700, color = Palette.ACCENT))
        val inputMotif = s.ellipse(16.0, 37.0, 28.0, 19.0,
            BoxOpts(name = "Input motif", fill = Palette.ACCENT_SOFT, stroke = Palette.ACCENT, dash = "dashed"))
        val inputText = s.text(18.0, 43.0, 24.0, 6.0, "Samples",
            TextOpts(name = "Input text", fontSize = 8.0, align = "center"))
        val inputSub = s.text(8.0, 71.0, 44.0, 5.0, "Cells / images",
            TextOpts(name = "Input caption", fontSize = 8.0, align = "center", color = Palette.MUTED))
        s.group("Input", listOf(inputPanel, inputHeader, inputMotif, inputText, inputSub))
        val center = s.ellipse(68.0, 24.0, 44.0, 50.0,
            BoxOpts(name = "Mechanism", fill = Palette.WARM, stroke = Palette.WARM_STROKE, strokeWidth = 0.5))
        val centerHeader = s.text(70.0, 27.0, 40.0, 5.0, "Mechanism",
            TextOpts(name = "Mechanism header", fontSize = 9.0, fontWeight = 700, align = "center", color = Palette.WARM_STROKE))
        val centerText = s.text(72.0, 38.0, 36.0, 12.0, "Model",
            TextOpts(name = "Mechanism text", fontSize = 10.0, fontWeight = 600, align = "center", lineHeight = 1.2))
        val centerSub = s.text(68.0, 76.0, 44.0, 5.0, "Computation",
            TextOpts(name = "Mechanism caption", fontSize = 8.0, align = "center", color = Palette.MUTED))
        s.group("Mechanism", listOf(center, centerHeader, centerText, centerSub))
        val outputPanel = s.rect(128.0, 28.0, 44.0, 42.0,
            BoxOpts(name = "Output panel", fill = Palette.SUCCESS, stroke = Palette.SUCCESS_STROKE, cornerRadius = 1.5))
        val outputHeader = s.text(130.0, 30.0, 40.0, 5.0, "Output",
            TextOpts(name = "Output header", fontSize = 9.0, fontWeight = 700, color = Palette.SUCCESS_STROKE))
        val outputMotif = s.ellipse(136.0, 37.0, 28.0, 19.0,
            BoxOpts(name = "Output motif", fill = Palette.WHITE, stroke = Palette.SUCCESS_STROKE, dash = "dashed"))
        val outputText = s.text(138.0, 43.0, 24.0, 6.0, "Result",
            TextOpts(name = "Output text", fontSize = 8.0, align = "center"))
        val outputSub = s.text(128.0, 71.0, 44.0, 5.0, "Figure / report",
            TextOpts(name = "Output caption", fontSize = 8.0, align = "center", color = Palette.MUTED))
        s.group("Output", listOf(outputPanel, outputHeader, outputMotif, outputText, outputSub))
        s.arrow(52.0, 49.0, 16.0, 0.0, LineOpts(name = "Input to mechanism"))
        s.arrow(112.0, 49.0, 16.0, 0.0, LineOpts(name = "Mechanism to output"))
        s.text(10.0, 86.0, 160.0, 10.0, "A one-sentence take-home message belongs here.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, align = "center", color = Palette.MUTED, lineHeight = 1.2))
        return s.doc
    }

    private fun buildMechanism(widthMm: Double?): TplDocument {
        val s = Scene("Mechanism diagram", 180.0, 120.0, widthMm)
        s.text(8.0, 5.0, 164.0, 9.0, "Proposed mechanism",
            TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700))
        s.text(8.0, 14.0, 164.0, 5.0, "Each stage is numbered and connected in order",
            TextOpts(name = "Subtitle", fontSize = 9.0, italic = true, color = Palette.MUTED))
        val stages = listOf(
            Triple("Recognition", "The sensor binds the target with high selectivity.", "Step 1"),
            Triple("Activation", "Conformational change exposes the catalytic site.", "Step 2"),
            Triple("Amplification", "A cascade converts the input into a measurable signal.", "Step 3"),
            Triple("Readout", "The signal is captured and normalised for quantification.", "Step 4")
        )
        stages.forEachIndexed { index, (title, detail, tag) ->
            val y = 26.0 + index * 22.0
            val members = mutableListOf<TplNode>()
            members.add(s.rect(20.0, y, 140.0, 16.0,
                BoxOpts(
                    name = "$title panel",
                    fill = if (index % 2 == 0) Palette.PANEL else Palette.PANEL_ALT,
                    stroke = if (index % 2 == 0) Palette.ACCENT else Palette.LINE,
                    strokeWidth = 0.35, cornerRadius = 1.2
                )))
            members.add(s.ellipse(24.0, y + 3, 10.0, 10.0,
                BoxOpts(name = "$title number", fill = Palette.ACCENT, stroke = "none", strokeWidth = 0.0)))
            members.add(s.text(24.0, y + 3, 10.0, 10.0, (index + 1).toString(),
                TextOpts(name = "$title number text", fontSize = 9.0, fontWeight = 700, align = "center", color = Palette.WHITE)))
            members.add(s.text(38.0, y + 2, 80.0, 6.0, title,
                TextOpts(name = "$title title", fontSize = 10.5, fontWeight = 700)))
            members.add(s.text(38.0, y + 8.5, 116.0, 5.5, detail,
                TextOpts(name = "$title detail", fontSize = 8.0, color = Palette.MUTED)))
            members.add(s.text(128.0, y + 5, 28.0, 5.0, tag,
                TextOpts(name = "$title tag", fontSize = 7.5, align = "right", color = Palette.ACCENT)))
            s.group(title, members)
            if (index < stages.size - 1) {
                s.arrow(90.0, y + 16, 0.0, 6.0, LineOpts(name = "Stage ${index + 1} connector", strokeWidth = 0.4))
            }
        }
        s.text(8.0, 111.0, 164.0, 6.0, "Figure 2. Schematic of the proposed mechanism with numbered stages.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, color = Palette.MUTED))
        return s.doc
    }

    private fun buildWorkflow(widthMm: Double?): TplDocument {
        val s = Scene("Workflow diagram", 180.0, 100.0, widthMm)
        s.text(8.0, 5.0, 164.0, 9.0, "Experimental workflow",
            TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700))
        s.text(8.0, 14.0, 164.0, 5.0, "Four steps from sample to validated result",
            TextOpts(name = "Subtitle", fontSize = 9.0, italic = true, color = Palette.MUTED))
        val cardWidth = (164.0 - 3 * 6) / 4
        val cards = listOf(
            "Prepare" to "Collect samples and set up controls.",
            "Acquire" to "Run the assay and capture raw data.",
            "Analyse" to "Process signals and fit the model.",
            "Validate" to "Confirm with an independent replicate."
        )
        cards.forEachIndexed { index, (title, note) ->
            val x = 8.0 + index * (cardWidth + 6)
            val members = mutableListOf<TplNode>()
            members.add(s.rect(x, 26.0, cardWidth, 44.0,
                BoxOpts(name = "$title card", fill = Palette.PANEL, stroke = Palette.LINE, strokeWidth = 0.3, cornerRadius = 1.3)))
            members.add(s.rect(x, 26.0, cardWidth, 4.0,
                BoxOpts(name = "$title accent", fill = Palette.ACCENT, stroke = "none", strokeWidth = 0.0, cornerRadius = 0.0)))
            members.add(s.ellipse(x + 4, 33.0, 9.0, 9.0,
                BoxOpts(name = "$title number", fill = Palette.ACCENT_SOFT, stroke = Palette.ACCENT, strokeWidth = 0.3)))
            members.add(s.text(x + 4, 33.0, 9.0, 9.0, (index + 1).toString(),
                TextOpts(name = "$title number text", fontSize = 8.0, fontWeight = 700, align = "center", color = Palette.ACCENT_DARK)))
            members.add(s.text(x + 4, 44.0, cardWidth - 8, 6.0, title,
                TextOpts(name = "$title title", fontSize = 9.0, fontWeight = 700)))
            members.add(s.text(x + 4, 51.0, cardWidth - 8, 14.0, note,
                TextOpts(name = "$title note", fontSize = 7.5, color = Palette.MUTED, lineHeight = 1.2)))
            s.group(title, members)
            if (index < cards.size - 1) {
                s.arrow(x + cardWidth + 0.5, 48.0, 5.0, 0.0, LineOpts(name = "Step ${index + 1} connector", strokeWidth = 0.35))
            }
        }
        s.rect(8.0, 76.0, 164.0, 12.0,
            BoxOpts(name = "Outcome band", fill = Palette.ACCENT_SOFT, stroke = Palette.ACCENT, strokeWidth = 0.2, cornerRadius = 1.2))
        s.text(10.0, 78.0, 160.0, 8.0, "Outcome: validated, publication-ready figure",
            TextOpts(name = "Outcome text", fontSize = 9.0, fontWeight = 700, align = "center", color = Palette.ACCENT_DARK))
        s.text(8.0, 91.0, 164.0, 6.0, "Figure 3. Workflow from sample preparation to validation.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, color = Palette.MUTED))
        return s.doc
    }

    private fun buildComparison(widthMm: Double?): TplDocument {
        val s = Scene("Comparison figure", 180.0, 110.0, widthMm)
        s.text(8.0, 5.0, 164.0, 9.0, "Condition comparison",
            TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700))
        s.text(8.0, 14.0, 164.0, 5.0, "Side-by-side view of two experimental conditions",
            TextOpts(name = "Subtitle", fontSize = 9.0, italic = true, color = Palette.MUTED))
        val leftPanel = s.rect(8.0, 26.0, 74.0, 62.0,
            BoxOpts(name = "Condition A panel", fill = Palette.PANEL, stroke = Palette.ACCENT, strokeWidth = 0.4, cornerRadius = 1.5))
        val leftHeader = s.rect(8.0, 26.0, 74.0, 10.0,
            BoxOpts(name = "Condition A header", fill = Palette.ACCENT, stroke = "none", strokeWidth = 0.0, cornerRadius = 1.5))
        val leftTitle = s.text(11.0, 28.0, 68.0, 6.0, "Condition A",
            TextOpts(name = "Condition A title", fontSize = 9.5, fontWeight = 700, color = Palette.WHITE))
        val leftBullet1 = s.text(12.0, 41.0, 66.0, 5.0, "• Baseline signal",
            TextOpts(name = "Condition A bullet 1", fontSize = 8.5, color = Palette.MUTED))
        val leftBullet2 = s.text(12.0, 50.0, 66.0, 5.0, "• Untreated control",
            TextOpts(name = "Condition A bullet 2", fontSize = 8.5, color = Palette.MUTED))
        val leftBullet3 = s.text(12.0, 59.0, 66.0, 5.0, "• Reference condition",
            TextOpts(name = "Condition A bullet 3", fontSize = 8.5, color = Palette.MUTED))
        val leftNote = s.text(12.0, 72.0, 66.0, 7.0, "n = 3 independent replicates",
            TextOpts(name = "Condition A note", fontSize = 7.5, italic = true, color = Palette.MUTED))
        s.group("Condition A", listOf(leftPanel, leftHeader, leftTitle, leftBullet1, leftBullet2, leftBullet3, leftNote))
        val rightPanel = s.rect(98.0, 26.0, 74.0, 62.0,
            BoxOpts(name = "Condition B panel", fill = Palette.SUCCESS, stroke = Palette.SUCCESS_STROKE, strokeWidth = 0.4, cornerRadius = 1.5))
        val rightHeader = s.rect(98.0, 26.0, 74.0, 10.0,
            BoxOpts(name = "Condition B header", fill = Palette.SUCCESS_STROKE, stroke = "none", strokeWidth = 0.0, cornerRadius = 1.5))
        val rightTitle = s.text(101.0, 28.0, 68.0, 6.0, "Condition B",
            TextOpts(name = "Condition B title", fontSize = 9.5, fontWeight = 700, color = Palette.WHITE))
        val rightBullet1 = s.text(102.0, 41.0, 66.0, 5.0, "• Enhanced signal",
            TextOpts(name = "Condition B bullet 1", fontSize = 8.5, color = Palette.MUTED))
        val rightBullet2 = s.text(102.0, 50.0, 66.0, 5.0, "• Treated sample",
            TextOpts(name = "Condition B bullet 2", fontSize = 8.5, color = Palette.MUTED))
        val rightBullet3 = s.text(102.0, 59.0, 66.0, 5.0, "• Test condition",
            TextOpts(name = "Condition B bullet 3", fontSize = 8.5, color = Palette.MUTED))
        val rightNote = s.text(102.0, 72.0, 66.0, 7.0, "n = 3 independent replicates",
            TextOpts(name = "Condition B note", fontSize = 7.5, italic = true, color = Palette.MUTED))
        s.group("Condition B", listOf(rightPanel, rightHeader, rightTitle, rightBullet1, rightBullet2, rightBullet3, rightNote))
        val versus = s.ellipse(82.0, 50.0, 16.0, 16.0,
            BoxOpts(name = "Versus marker", fill = Palette.WHITE, stroke = Palette.INK, strokeWidth = 0.4))
        s.text(82.0, 54.5, 16.0, 6.0, "vs",
            TextOpts(name = "Versus text", fontSize = 9.0, fontWeight = 700, align = "center"))
        s.group("Versus", listOf(versus))
        s.text(8.0, 94.0, 164.0, 8.0, "Figure 4. Direct comparison of conditions A and B.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, color = Palette.MUTED))
        return s.doc
    }

    private fun buildTimeline(widthMm: Double?): TplDocument {
        val s = Scene("Timeline figure", 180.0, 90.0, widthMm)
        s.text(8.0, 4.0, 164.0, 8.0, "Project timeline",
            TextOpts(name = "Title", fontSize = 16.0, fontWeight = 700))
        s.text(8.0, 12.0, 164.0, 5.0, "Key milestones from inception to completion",
            TextOpts(name = "Subtitle", fontSize = 8.5, italic = true, color = Palette.MUTED))
        s.line(14.0, 49.0, 152.0, 0.0, LineOpts(name = "Timeline axis", stroke = Palette.LINE, strokeWidth = 0.6))
        val milestones = listOf(
            "Q1" to "Planning",
            "Q2" to "Data collection",
            "Q3" to "Analysis",
            "Q4" to "Submission"
        )
        milestones.forEachIndexed { index, (date, label) ->
            val cx = 30.0 + index * 40.0
            val above = index % 2 == 0
            val members = mutableListOf<TplNode>()
            members.add(s.ellipse(cx - 4, 45.0, 8.0, 8.0,
                BoxOpts(name = "$label marker", fill = Palette.ACCENT, stroke = Palette.WHITE, strokeWidth = 0.5)))
            if (above) {
                members.add(s.line(cx, 38.0, 0.0, 7.0, LineOpts(name = "$label connector", stroke = Palette.LINE, strokeWidth = 0.3)))
                members.add(s.text(cx - 20, 22.0, 40.0, 5.0, date,
                    TextOpts(name = "$label date", fontSize = 8.0, fontWeight = 700, align = "center", color = Palette.ACCENT)))
                members.add(s.text(cx - 20, 27.0, 40.0, 10.0, label,
                    TextOpts(name = "$label label", fontSize = 8.5, align = "center")))
            } else {
                members.add(s.line(cx, 53.0, 0.0, 7.0, LineOpts(name = "$label connector", stroke = Palette.LINE, strokeWidth = 0.3)))
                members.add(s.text(cx - 20, 60.0, 40.0, 9.0, label,
                    TextOpts(name = "$label label", fontSize = 8.5, align = "center")))
                members.add(s.text(cx - 20, 69.0, 40.0, 5.0, date,
                    TextOpts(name = "$label date", fontSize = 8.0, fontWeight = 700, align = "center", color = Palette.ACCENT)))
            }
            s.group(label, members)
        }
        s.text(8.0, 78.0, 164.0, 6.0, "Figure 5. Timeline of project milestones.",
            TextOpts(name = "Caption", fontSize = 8.5, italic = true, align = "center", color = Palette.MUTED))
        return s.doc
    }

    // -------------------------------------------------------------- 注册表

    data class Template(val id: String, val zhName: String, val zhDesc: String, val build: (Double?) -> TplDocument)

    /** main FIGURE_TEMPLATES：8 模板 + zh 词条（figTemplate*）。 */
    val FIGURE_TEMPLATES: List<Template> = listOf(
        Template("single-column", "单栏", "一个主面板、两个辅助面板和说明文字", ::buildSingleColumn),
        Template("double-column", "双栏", "两个均衡面板、细节条和说明文字", ::buildDoubleColumn),
        Template("slide", "幻灯片 16:9", "带标题栏的宽幅三列布局", ::buildSlide),
        Template("graphical-abstract", "图形摘要", "输入、中心机制和输出由箭头连接", ::buildGraphicalAbstract),
        Template("mechanism", "机制图", "垂直编号通路，包含阶段和连接线", ::buildMechanism),
        Template("workflow", "工作流程", "四个编号步骤，带箭头和注释", ::buildWorkflow),
        Template("comparison", "对比", "并排条件，中间带对比标记", ::buildComparison),
        Template("timeline", "时间轴", "水平里程碑，标签交替排列", ::buildTimeline),
    )

    /** main applyFigureTemplate：未知 id 回退 single-column。 */
    fun applyFigureTemplate(id: String, widthMm: Double? = null): TplDocument {
        val template = FIGURE_TEMPLATES.firstOrNull { it.id == id } ?: FIGURE_TEMPLATES[0]
        return template.build(widthMm)
    }
}
