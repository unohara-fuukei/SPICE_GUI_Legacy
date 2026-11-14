package com.spice.nativeapp.tools.annotate

/*
 * T5 annotate tool algorithm-layer parity tests.
 *
 * Reference values were produced by running the ORIGINAL TypeScript sources
 * (SPICE_main src/lib/tools/annotate/{types,layout,projectDoc,storage}.ts)
 * under Node.js (see /tmp/annotate_gen.ts); Canvas measureText was stubbed as
 * width = text.length * 7 on BOTH sides, so geometry/label numbers below are
 * deterministic and the Kotlin port must reproduce them bit-exactly.
 */
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AnnotateTest {

    @Before
    fun injectStubMeasurer() {
        // Same stub as the Node reference generator (width = len * 7).
        textMeasurer = { text, _, _ -> text.length.toDouble() * 7.0 }
    }

    // ------------------------------------------------------------ fixtures

    private fun panel(
        id: String, name: String, width: Int, height: Int,
        dataUrl: String = "data:image/png;base64,AAAA",
    ) = Panel(
        id, name, dataUrl, width, height, "image/png",
        CropRect(0.0, 0.0, 0.0, 0.0), 1.0, 0.0, 0.0, 100.0, 100.0,
        createDefaultChannels(), null,
    )

    private fun buildProject(): AnnotateProject {
        val p1 = panel("p1", "Gel A", 320, 240)
        val p2 = panel("p2", "Merge B", 400, 200, "data:image/png;base64,BBBB").copy(
            crop = CropRect(0.1, 0.05, 0.0, 0.2),
            zoom = 2.0, offsetX = 0.5, offsetY = -0.5,
            brightness = 130.0, contrast = 80.0,
            channels = ChannelMix(
                ChannelSetting(false, 1.5, true),
                ChannelSetting(true, 1.0, false),
                ChannelSetting(true, 0.75, false),
            ),
            calibration = Calibration("um", 3.2),
        )
        val p3 = panel("p3", "Wide C", 512, 128, "data:image/png;base64,CCCC")
        val annotations = mutableListOf(
            ArrowAnnotation("ann_arrow", "#111111", 10.0, 20.0, 40.0, 25.0, 1.5, 8.0),
            RectAnnotation("ann_rect", "#ff5d5d", 15.0, 16.0, 30.0, 12.0, 1.2, "none"),
            EllipseAnnotation("ann_ell", "#111111", 50.0, 16.0, 24.0, 24.0, 1.5, "#ffd23f"),
            TextAnnotation("ann_text", "#0b0e14", 50.0, 60.0, "Merge B", 10.0, "#ffffff", 2.0),
            ScaleBarAnnotation("ann_bar", "#111111", 20.0, 80.0, "calibrated", 5.0, "um", "p2", 2.0, 9.0, true),
            PanelBarAnnotation("ann_pbar", "#111111", "p2", 0.1, 0.9, 10.0, "um", 2.0, 9.0, true),
            ProfileAnnotation("ann_prof", "#ffd23f", 10.0, 10.0, 60.0, 40.0, 192, 1.2),
        )
        val layout = LayoutSettings(
            2, "column", 4.0, 8.0, 160.0, "upper", "tr",
            12.0, "#0b0e14", "#ffffff", "Arial", 600,
        )
        return AnnotateProject(
            1, "My Figure", "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z",
            mutableListOf(p1, p2, p3), annotations, layout,
        )
    }

    // --------------------------------------------------------- scalar units

    @Test
    fun pxPerMm_and_unitHelpers_match_reference() {
        assertEquals(3.7795275590551185, PX_PER_MM, 0.0)
        assertEquals(1.3333333333333333, PT_TO_PX, 1e-15)
        assertEquals("µm", unitSymbol("um"))
        assertEquals("mm", unitSymbol("mm"))
        assertEquals("nm", unitSymbol("nm"))
        assertEquals("px", unitSymbol("px"))
        assertEquals(67.20416666666667, mmFromPx(254.0), 0.0)
        assertEquals(37.795275590551185, pxFromMm(10.0), 0.0)
    }

    @Test
    fun defaultProject_matches_reference() {
        val project = createDefaultProject()
        assertEquals("Figure", project.name)
        assertEquals(1, project.version)
        assertEquals(0, project.panels.size)
        assertEquals(0, project.annotations.size)
        val layout = project.layout
        assertEquals(2, layout.columns)
        assertEquals("row", layout.flow)
        assertEquals(2.0, layout.gapMm, 0.0)
        assertEquals(2.0, layout.marginMm, 0.0)
        assertEquals(180.0, layout.figureWidthMm, 0.0)
        assertEquals("upper", layout.labelStyle)
        assertEquals("tl", layout.labelPosition)
        assertEquals(12.0, layout.labelFontSizePt, 0.0)
        assertEquals("#111111", layout.labelColor)
        assertEquals("#ffffff", layout.background)
        assertEquals("Arial", layout.fontFamily)
        assertEquals(300, layout.exportDpi)
    }

    @Test
    fun panelLabel_matches_reference() {
        val expected = listOf(
            listOf("A", "a", "1"), listOf("B", "b", "2"), listOf("C", "c", "3"),
            listOf("D", "d", "4"), listOf("AA", "aa", "27"), listOf("AB", "ab", "28"),
        )
        listOf(0, 1, 2, 3, 26, 27).forEachIndexed { row, index ->
            assertEquals(expected[row][0], panelLabel(index, "upper"))
            assertEquals(expected[row][1], panelLabel(index, "lower"))
            assertEquals(expected[row][2], panelLabel(index, "number"))
        }
    }

    @Test
    fun formatValue_matches_reference() {
        val values = listOf(0.0, 1.0, 1.234, 10.01, 100.5, 1234.5, 0.001, 0.000123, -2.5, Double.POSITIVE_INFINITY, Double.NaN)
        val expected = listOf("0", "1", "1.23", "10", "101", "1235", "0.001", "0", "-2.5", "0", "0")
        values.forEachIndexed { i, v -> assertEquals("case $i", expected[i], formatValue(v)) }
    }

    @Test
    fun crop_and_view_helpers_match_reference() {
        val p2 = buildProject().panels[1]
        val crop = safeCrop(p2)
        assertEquals(0.1, crop.left, 0.0)
        assertEquals(0.05, crop.top, 0.0)
        assertEquals(0.0, crop.right, 0.0)
        assertEquals(0.2, crop.bottom, 0.0)

        val v1 = visibleRect(buildProject().panels[0])
        assertEquals(0.0, v1.sx, 0.0); assertEquals(0.0, v1.sy, 0.0)
        assertEquals(1.0, v1.sw, 0.0); assertEquals(1.0, v1.sh, 0.0)

        val v2 = visibleRect(p2)
        assertEquals(0.4375, v2.sx, 0.0)
        assertEquals(0.14375, v2.sy, 0.0)
        assertEquals(0.45, v2.sw, 0.0)
        assertEquals(0.375, v2.sh, 0.0)

        // frame width of p2 from the reference geometry (column flow)
        val frameW = 264.5669291338583
        assertEquals(0.6803571428571428, sourcePixelsPerFigurePixel(p2, frameW), 0.0)
    }

    @Test
    fun calibration_conversion_matches_reference() {
        val cal = Calibration("um", 3.2)
        assertEquals(Calibration("mm", 0.0031999999999999997), convertCalibration(cal, "mm"))
        assertEquals(Calibration("nm", 3199.9999999999995), convertCalibration(cal, "nm"))
        assertNull(convertCalibration(cal, "px"))
        assertNull(convertCalibration(Calibration("px", 9.0), "um"))
        assertEquals(Calibration("px", 9.0), convertCalibration(Calibration("px", 9.0), "px"))

        assertEquals(0.004999999999999999, convertValue(5.0, "um", "mm"), 0.0)
        assertEquals(4999.999999999999, convertValue(5.0, "um", "nm"), 0.0)
        assertEquals(2500.0, convertValue(2.5, "mm", "um"), 0.0)
        assertEquals(7.0, convertValue(7.0, "px", "um"), 0.0)
        assertEquals(7.0, convertValue(7.0, "um", "um"), 0.0)
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun buildFigureGeometry_matches_reference() {
        val geom = buildFigureGeometry(buildProject())
        assertEquals(604.724409448819, geom.width, 0.0)
        assertEquals(384.25196850393706, geom.height, 0.0)
        assertEquals(2, geom.rowCount)
        assertEquals(2, geom.colCount)
        assertEquals(3, geom.frames.size)

        // frame 0: p1 at row0/col0
        val f0 = geom.frames[0]
        assertEquals("p1", f0.panel.id); assertEquals(0, f0.index)
        assertEquals(0, f0.row); assertEquals(0, f0.col)
        assertEquals(30.236220472440948, f0.x, 0.0)
        assertEquals(30.236220472440948, f0.y, 0.0)
        assertEquals(264.5669291338583, f0.w, 0.0)
        assertEquals(198.42519685039375, f0.h, 0.0)
        assertEquals("A", f0.label.text)
        assertEquals(291.60314960629927, f0.label.x, 0.0)
        assertEquals(33.43622047244095, f0.label.y, 0.0)
        assertEquals(16.0, f0.label.fontPx, 0.0)
        assertEquals("right", f0.label.align)
        assertEquals("top", f0.label.baseline)

        // frame 1: p2 (column flow → row 1)
        val f1 = geom.frames[1]
        assertEquals("p2", f1.panel.id)
        assertEquals(1, f1.row); assertEquals(0, f1.col)
        assertEquals(30.236220472440948, f1.x, 0.0)
        assertEquals(243.77952755905517, f1.y, 0.0)
        assertEquals(264.5669291338583, f1.w, 0.0)
        assertEquals(110.23622047244096, f1.h, 0.0)
        assertEquals("B", f1.label.text)
        assertEquals(246.97952755905516, f1.label.y, 0.0)

        // frame 2: p3 (row 0, col 1)
        val f2 = geom.frames[2]
        assertEquals("p3", f2.panel.id)
        assertEquals(0, f2.row); assertEquals(1, f2.col)
        assertEquals(309.92125984251976, f2.x, 0.0)
        assertEquals(96.37795275590554, f2.y, 0.0)
        assertEquals(264.5669291338583, f2.w, 0.0)
        assertEquals(66.14173228346458, f2.h, 0.0)
        assertEquals("C", f2.label.text)
        assertEquals(571.288188976378, f2.label.x, 0.0)
        assertEquals(99.57795275590554, f2.label.y, 0.0)
    }

    @Test
    fun buildAnnotationMeta_matches_reference() {
        val project = buildProject()
        val geom = buildFigureGeometry(project)
        val meta = buildAnnotationMeta(project, geom)

        val arrow = meta["ann_arrow"]!!
        assertEquals(3.6000000000000005, arrow.box.x, 0.0)
        assertEquals(13.600000000000001, arrow.box.y, 0.0)
        assertEquals(42.8, arrow.box.w, 0.0)
        assertEquals(17.799999999999997, arrow.box.h, 0.0)
        assertEquals(0.0, arrow.fontPx, 0.0)
        assertTrue(arrow.lines.isEmpty())
        assertNull(arrow.barLength)
        assertNull(arrow.frame)

        val rect = meta["ann_rect"]!!
        assertEquals(15.0, rect.box.x, 0.0); assertEquals(16.0, rect.box.y, 0.0)
        assertEquals(30.0, rect.box.w, 0.0); assertEquals(12.0, rect.box.h, 0.0)

        val ell = meta["ann_ell"]!!
        assertEquals(50.0, ell.box.x, 0.0); assertEquals(16.0, ell.box.y, 0.0)
        assertEquals(24.0, ell.box.w, 0.0); assertEquals(24.0, ell.box.h, 0.0)

        val text = meta["ann_text"]!!
        assertEquals(47.333333333333336, text.box.x, 0.0)
        assertEquals(57.333333333333336, text.box.y, 0.0)
        assertEquals(54.333333333333336, text.box.w, 0.0)
        assertEquals(21.999999999999996, text.box.h, 0.0)
        assertEquals(13.333333333333332, text.fontPx, 0.0)
        assertEquals(listOf("Merge B"), text.lines)

        val bar = meta["ann_bar"]!!
        assertEquals(17.333333333333332, bar.box.x, 0.0)
        assertEquals(65.6, bar.box.y, 0.0)
        assertEquals(28.850393700787404, bar.box.w, 0.0)
        assertEquals(17.73333333333335, bar.box.h, 0.0)
        assertEquals(12.0, bar.fontPx, 0.0)
        assertEquals(23.51706036745407, bar.barLength!!, 0.0)
        assertEquals("p2", bar.frame!!.panel.id)

        val pbar = meta["ann_pbar"]!!
        assertEquals(54.02624671916012, pbar.box.x, 0.0)
        assertEquals(328.59212598425205, pbar.box.y, 0.0)
        assertEquals(52.36745406824148, pbar.box.w, 0.0)
        assertEquals(17.73333333333335, pbar.box.h, 0.0)
        assertEquals(47.03412073490814, pbar.barLength!!, 0.0)
        assertEquals("p2", pbar.frame!!.panel.id)

        val prof = meta["ann_prof"]!!
        assertEquals(6.0, prof.box.x, 0.0); assertEquals(6.0, prof.box.y, 0.0)
        assertEquals(58.0, prof.box.w, 0.0); assertEquals(38.0, prof.box.h, 0.0)
    }

    @Test
    fun barLayout_matches_reference() {
        val project = buildProject()
        val geom = buildFigureGeometry(project)
        val sb = project.annotations.first { it.id == "ann_bar" }
        val pb = project.annotations.first { it.id == "ann_pbar" }
        val sbLayout = barLayout(sb, geom)!!
        assertEquals(20.0, sbLayout.x, 0.0)
        assertEquals(80.0, sbLayout.y, 0.0)
        assertEquals(23.51706036745407, sbLayout.length, 0.0)
        assertEquals("5 µm", sbLayout.label)
        assertEquals(12.0, sbLayout.fontPx, 0.0)
        assertEquals("p2", sbLayout.frame!!.panel.id)

        val pbLayout = barLayout(pb, geom)!!
        assertEquals(56.692913385826785, pbLayout.x, 0.0)
        assertEquals(342.992125984252, pbLayout.y, 0.0)
        assertEquals(47.03412073490814, pbLayout.length, 0.0)
        assertEquals("10 µm", pbLayout.label)
    }

    @Test
    fun handles_and_hit_tests_match_reference() {
        val project = buildProject()
        val geom = buildFigureGeometry(project)
        val meta = buildAnnotationMeta(project, geom)
        val arrow = project.annotations.first { it.id == "ann_arrow" }
        val text = project.annotations.first { it.id == "ann_text" }

        val arrowHandles = annotationHandles(arrow, meta["ann_arrow"])
        assertEquals(2, arrowHandles.size)
        assertEquals("start", arrowHandles[0].id)
        assertEquals(10.0, arrowHandles[0].x, 0.0)
        assertEquals(20.0, arrowHandles[0].y, 0.0)
        assertEquals("end", arrowHandles[1].id)
        assertEquals(40.0, arrowHandles[1].x, 0.0)
        assertEquals(25.0, arrowHandles[1].y, 0.0)

        val textHandles = annotationHandles(text, meta["ann_text"])
        assertEquals(1, textHandles.size)
        assertEquals("se", textHandles[0].id)
        assertEquals(101.66666666666667, textHandles[0].x, 0.0)
        assertEquals(79.33333333333333, textHandles[0].y, 0.0)

        assertEquals("start", findHandleAt(arrow, meta["ann_arrow"], Point(10.0, 20.0), 5.0))
        assertEquals("end", findHandleAt(arrow, meta["ann_arrow"], Point(40.4, 25.3), 5.0))
        assertNull(findHandleAt(arrow, meta["ann_arrow"], Point(100.0, 100.0), 5.0))

        val f0 = geom.frames[0]
        val f1 = geom.frames[1]
        assertEquals("p1", findFrameAt(geom, Point(f0.x + f0.w / 2, f0.y + f0.h / 2))?.panel?.id)
        assertEquals("p2", findFrameAt(geom, Point(f1.x + 1, f1.y + 1))?.panel?.id)
        assertNull(findFrameAt(geom, Point(-50.0, -50.0)))

        assertEquals("ann_prof", findAnnotationAt(project, meta, Point(25.0, 20.0), 5.0))
        assertEquals("ann_prof", findAnnotationAt(project, meta, Point(20.0, 22.0), 5.0))
        assertNull(findAnnotationAt(project, meta, Point(500.0, 500.0), 5.0))
    }

    // ------------------------------------------------------------- projectDoc

    @Test
    fun annotateSummary_and_docName_match_reference() {
        val project = buildProject()
        assertEquals("My Figure", annotateDocName(project))
        // Blank name falls back to the default figure name.
        assertEquals("Figure", annotateDocName(project.copy(name = "   ")))
        assertEquals("3 panels; 160.0 x 101.7 mm; 600 dpi; 7 annotations; 1 profile; RGB RGB/GB", annotateSummary(project))
        assertEquals(1, annotateProfileAnnotations(project).size)
        assertEquals("ann_prof", annotateProfileAnnotations(project)[0].id)
    }

    @Test
    fun annotateParameters_matches_reference() {
        val expected = JSONObject(
            """
            {"figure": {"name": "My Figure", "widthMm": 160, "heightMm": 101.667, "exportDpi": 600, "columns": 2, "flow": "column", "gapMm": 4, "marginMm": 8, "background": "#ffffff", "fontFamily": "Arial", "labelStyle": "upper", "labelPosition": "tr", "labelFontSizePt": 12},
             "panels": [
               {"name": "Gel A", "width": 320, "height": 240, "mime": "image/png", "crop": {"left": 0, "top": 0, "right": 0, "bottom": 0}, "zoom": 1, "offsetX": 0, "offsetY": 0, "brightness": 100, "contrast": 100, "channels": {"r": {"show": true, "gain": 1, "invert": false}, "g": {"show": true, "gain": 1, "invert": false}, "b": {"show": true, "gain": 1, "invert": false}}, "calibration": null},
               {"name": "Merge B", "width": 400, "height": 200, "mime": "image/png", "crop": {"left": 0.1, "top": 0.05, "right": 0, "bottom": 0.2}, "zoom": 2, "offsetX": 0.5, "offsetY": -0.5, "brightness": 130, "contrast": 80, "channels": {"r": {"show": false, "gain": 1.5, "invert": true}, "g": {"show": true, "gain": 1, "invert": false}, "b": {"show": true, "gain": 0.75, "invert": false}}, "calibration": {"unit": "um", "pxPerUnit": 3.2}},
               {"name": "Wide C", "width": 512, "height": 128, "mime": "image/png", "crop": {"left": 0, "top": 0, "right": 0, "bottom": 0}, "zoom": 1, "offsetX": 0, "offsetY": 0, "brightness": 100, "contrast": 100, "channels": {"r": {"show": true, "gain": 1, "invert": false}, "g": {"show": true, "gain": 1, "invert": false}, "b": {"show": true, "gain": 1, "invert": false}}, "calibration": null}
             ],
             "annotations": [
               {"id": "ann_arrow", "kind": "arrow", "color": "#111111"},
               {"id": "ann_rect", "kind": "rect", "color": "#ff5d5d"},
               {"id": "ann_ell", "kind": "ellipse", "color": "#111111"},
               {"id": "ann_text", "kind": "text", "color": "#0b0e14"},
               {"id": "ann_bar", "kind": "scalebar", "color": "#111111"},
               {"id": "ann_pbar", "kind": "panelbar", "color": "#111111"},
               {"id": "ann_prof", "kind": "profile", "color": "#ffd23f", "samples": 192, "x1": 10, "y1": 10, "x2": 60, "y2": 40}
             ],
             "profileCount": 1,
             "profileIntensity": "Rec. 709 luminance (0.2126 R + 0.7152 G + 0.0722 B) of the rendered RGB composite, 0-255"}
            """.trimIndent(),
        )
        assertEquals(canonical(expected), canonical(annotateParameters(buildProject())))
    }

    @Test
    fun annotateMethods_matches_reference_verbatim() {
        val expected =
            "Figure panels (3) were assembled in the SPICE image annotator and exported at 600 dpi as a raster " +
                "figure (PNG) and as a vector figure (SVG with embedded raster panels and editable text labels). " +
                "Panel cropping, zoom and pan were applied non-destructively; brightness and contrast were applied " +
                "before annotation placement. RGB channels were composited per panel with explicit show/hide flags, " +
                "gain in the 0-2 range and optional per-channel inversion; every preview, annotation and export " +
                "used the same composite. Intensity line profiles (1) were sampled at evenly spaced positions " +
                "along each measurement line from the rendered composite. Intensity was defined as Rec. 709 " +
                "luminance (0.2126 R + 0.7152 G + 0.0722 B) on the 0-255 scale and exported as profile CSV " +
                "alongside the figure. Scale bars use the per-panel calibration (source pixels per physical unit) " +
                "where available; fixed-length bars are expressed in millimetres at the figure scale."
        assertEquals(expected, annotateMethods(buildProject()))
    }

    // --------------------------------------------------------------- storage

    @Test
    fun historyKey_is_stable_and_excludes_raster() {
        val key = historyKey(buildProject())
        // Embedded raster data must not be part of the autosave signature.
        assertFalse(key.contains("dataUrl"))
        assertFalse(key.contains("base64"))
        assertTrue(key.contains("\"name\":\"My Figure\""))
        assertTrue(key.contains("\"figureWidthMm\":160"))
        assertTrue(key.contains("\"kind\":\"scalebar\""))
        assertTrue(key.contains("\"pxPerUnit\":3.2"))
        // Renaming the project DOES change the key (name is part of the signature).
        val renamed = buildProject().copy(name = "Other")
        assertFalse(key == historyKey(renamed))
        // Changing a crop does.
        val cropped = buildProject()
        cropped.panels[0] = cropped.panels[0].copy(crop = CropRect(0.1, 0.0, 0.0, 0.0))
        assertFalse(key == historyKey(cropped))
    }

    @Test
    fun projectJson_roundTrips_through_parseProjectText() {
        val project = buildProject()
        val key = historyKey(project)
        val reloaded = parseProjectText(projectJson(project))!!
        assertEquals(key, historyKey(reloaded))
        assertEquals("My Figure", reloaded.name)
        assertEquals(3, reloaded.panels.size)
        assertEquals(7, reloaded.annotations.size)
        assertEquals(600, reloaded.layout.exportDpi)
        assertEquals(CropRect(0.1, 0.05, 0.0, 0.2), reloaded.panels[1].crop)
        assertEquals(Calibration("um", 3.2), reloaded.panels[1].calibration)
        assertEquals(
            ChannelSetting(false, 1.5, true),
            reloaded.panels[1].channels.r,
        )
        val bar = reloaded.annotations[4] as ScaleBarAnnotation
        assertEquals("calibrated", bar.mode)
        assertEquals("p2", bar.panelId)
        // projectJson is pretty-printed with 2-space indent.
        assertTrue(projectJson(project).contains("\n"))
    }

    @Test
    fun safeFileName_matches_reference() {
        assertEquals("My_Figure!_中文", safeFileName("My Figure! 中文"))
        assertEquals("---", safeFileName("---"))
        assertEquals("a".repeat(60), safeFileName("a".repeat(90)))
    }

    // ------------------------------------------------------- normalizeProject

    private fun messyRaw(): JSONObject = JSONObject(
        """
        {
          "kind": "spice-annotate",
          "version": 1,
          "name": 123,
          "layout": {
            "columns": 20, "flow": "diagonal", "gapMm": -5, "marginMm": "x", "figureWidthMm": 999,
            "fontFamily": "", "labelStyle": "weird", "labelPosition": "cc", "labelFontSizePt": 0,
            "labelColor": 5, "background": "#fff", "exportDpi": 900
          },
          "panels": [
            {"id": "p1", "name": "P1", "dataUrl": "data:image/png;base64,x", "width": 100, "height": 50, "mime": "image/png",
             "crop": {"left": -1, "right": 2}, "zoom": 0, "offsetX": "a", "brightness": "b",
             "channels": {"r": {"show": 1, "gain": "x", "invert": 0}}, "calibration": {"unit": "km", "pxPerUnit": -1}},
            {"name": "NoId", "dataUrl": "", "width": 0, "height": -5},
            "garbage"
          ],
          "annotations": [
            {"kind": "arrow", "id": "a1", "color": 3, "x1": "x", "y1": 1, "x2": 2, "y2": 3},
            {"kind": "nope", "id": "a2"},
            {"kind": "text", "id": "a3", "text": "  hi  ", "fontSizePt": 1000, "background": "#fff", "paddingPt": -2},
            {"kind": "scalebar", "id": "a4", "mode": "weird", "value": -3, "unit": "km", "panelId": "missing",
             "thicknessPt": 99, "fontSizePt": 0, "showLabel": "yes"},
            {"kind": "profile", "id": "a5", "samples": 99999, "strokeWidthPt": 0}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun normalizeProject_sanitizes_and_drops_bad_entries() {
        val normalized = normalizeProject(messyRaw())!!
        assertEquals(1, normalized.version)
        assertEquals("Figure", normalized.name) // name: 123 → fallback
        // Panel without a valid id/dataUrl is dropped (no id generation).
        assertEquals(1, normalized.panels.size)
        val p = normalized.panels[0]
        assertEquals("p1", p.id)
        assertEquals("P1", p.name)
        assertEquals(100, p.width)
        assertEquals(50, p.height)
        // crop.left -1 → 0; crop.right 2 clamps to 0.9 - left.
        assertEquals(0.0, p.crop.left, 0.0)
        assertEquals(0.9, p.crop.right, 0.0)
        assertEquals(0.0, p.crop.top, 0.0)
        assertEquals(0.0, p.crop.bottom, 0.0)
        assertEquals(1.0, p.zoom, 0.0) // zoom 0 → clamp 1
        assertEquals(100.0, p.brightness, 0.0)
        assertEquals(
            ChannelMix(
                ChannelSetting(true, 1.0, false),
                ChannelSetting(true, 1.0, false),
                ChannelSetting(true, 1.0, false),
            ),
            p.channels,
        )
        assertNull(p.calibration) // invalid unit 'km'

        // Unknown annotation kind dropped → 4 survive.
        assertEquals(4, normalized.annotations.size)
        val a1 = normalized.annotations[0] as ArrowAnnotation
        assertEquals("#111111", a1.color) // color: 3 → fallback
        assertEquals(0.0, a1.x1, 0.0) // x1: "x" → fallback 0
        assertEquals(1.0, a1.y1, 0.0)
        assertEquals(1.5, a1.strokeWidthPt, 0.0)
        assertEquals(8.0, a1.headSizePt, 0.0)
        val a3 = normalized.annotations[1] as TextAnnotation
        assertEquals("  hi  ", a3.text) // whitespace preserved verbatim
        assertEquals(96.0, a3.fontSizePt, 0.0) // 1000 → clamp 96
        assertEquals(0.0, a3.paddingPt, 0.0) // -2 → clamp 0
        val a4 = normalized.annotations[2] as ScaleBarAnnotation
        assertEquals("calibrated", a4.mode) // 'weird' → calibrated
        assertEquals(0.0, a4.value, 0.0)
        assertEquals("mm", a4.unit) // 'km' → fallback mm
        assertEquals("p1", a4.panelId) // missing panelId → re-pointed to first panel
        assertEquals(16.0, a4.thicknessPt, 0.0)
        assertEquals(4.0, a4.fontSizePt, 0.0)
        assertTrue(a4.showLabel)
        val a5 = normalized.annotations[3] as ProfileAnnotation
        assertEquals(1024, a5.samples) // 99999 → clamp 1024
        assertEquals(0.25, a5.strokeWidthPt, 0.0)

        val layout = normalized.layout
        assertEquals(12, layout.columns) // 20 → clamp 12
        assertEquals("row", layout.flow) // 'diagonal' → row
        assertEquals(0.0, layout.gapMm, 0.0) // -5 → clamp 0
        assertEquals(2.0, layout.marginMm, 0.0) // 'x' → default 2
        assertEquals(400.0, layout.figureWidthMm, 0.0) // 999 → clamp 400
        assertEquals("upper", layout.labelStyle)
        assertEquals("tl", layout.labelPosition)
        assertEquals(4.0, layout.labelFontSizePt, 0.0) // 0 → clamp 4
        assertEquals("#111111", layout.labelColor) // 5 → fallback
        assertEquals("#fff", layout.background) // strings kept verbatim
        assertEquals("", layout.fontFamily)
        assertEquals(300, layout.exportDpi) // 900 → 300
    }

    @Test
    fun normalizeProject_rejects_invalid_payloads() {
        assertNull(normalizeProject(null))
        assertNull(normalizeProject("a string"))
        // Missing version → rejected.
        assertNull(normalizeProject(JSONObject("{\"panels\": []}")))
        assertNull(normalizeAnnotatePayload(null))
        // normalizeAnnotatePayload delegates to normalizeProject.
        assertEquals("Figure", normalizeAnnotatePayload(messyRaw())!!.name)
    }

    @Test
    fun normalizeProject_dedupes_duplicate_ids() {
        val raw = JSONObject(
            """
            {"version": 1,
             "panels": [
               {"id": "d", "name": "First", "dataUrl": "data:image/png;base64,x", "width": 10, "height": 10},
               {"id": "d", "name": "Second", "dataUrl": "data:image/png;base64,y", "width": 20, "height": 20}
             ],
             "annotations": [
               {"kind": "rect", "id": "r", "x": 1, "y": 2, "w": 3, "h": 4},
               {"kind": "rect", "id": "r", "x": 9, "y": 9, "w": 1, "h": 1}
             ]}
            """.trimIndent(),
        )
        val normalized = normalizeProject(raw)!!
        assertEquals(1, normalized.panels.size)
        assertEquals("First", normalized.panels[0].name)
        assertEquals(1, normalized.annotations.size)
        assertEquals(1.0, (normalized.annotations[0] as RectAnnotation).x, 0.0)
    }

    // -------------------------------------------------------------- helpers

    /** Canonical JSON for structure-insensitive comparison (sorted keys). */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = mutableListOf<String>()
            value.keys().forEach { keys.add(it) }
            keys.sorted().joinToString(",", "{", "}") { k ->
                canonical(k) + ":" + canonical(value.get(k))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> JSONObject.numberToString(value)
        else -> value.toString()
    }
}
