package com.spice.nativeapp.tools.gel

/**
 * Gel project document helpers shared by the SPICE_PROJECT bridge, the
 * reproducible bundle and the folder batch mode.
 * 1:1 移植自原版 src/lib/tools/gel/projectDoc.ts。
 *
 * The sanitizer is deliberately defensive: project documents travel through
 * storage, .spiceproj files and handoffs, so a single bad number must not
 * break the editor. It keeps the v1 migration path and normalises every field
 * the figure renderer reads.
 */
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

object GelProjectDoc {

    private val SIDES = listOf("left", "right", "top", "bottom")

    private fun num(o: JSONObject?, key: String, fallback: Double): Double {
        val v = o?.opt(key)
        return if (v is Number && v.toDouble().isFinite()) v.toDouble() else fallback
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = minOf(hi, maxOf(lo, v))

    private fun asString(o: JSONObject?, key: String, fallback: String = ""): String {
        val v = o?.opt(key)
        return if (v is String) v else fallback
    }

    private fun asSide(o: JSONObject?, key: String, fallback: String = "left"): String {
        val v = o?.opt(key)
        return if (v is String && v in SIDES) v else fallback
    }

    private fun sanitizeLadders(value: Any?): MutableList<LadderDef> {
        val out = ArrayList<LadderDef>()
        if (value !is JSONArray) return out
        for (i in 0 until value.length()) {
            val l = value.optJSONObject(i) ?: continue
            val id = l.optString("id")
            if (id.isEmpty() || l.opt("id") !is String || l.opt("name") !is String) continue
            val name = l.optString("name")
            val sizesRaw = ArrayList<Double>()
            l.optJSONArray("sizes")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val v = arr.opt(j)
                    if (v is Number && v.toDouble().isFinite()) sizesRaw.add(v.toDouble())
                }
            }
            val sizes = GelLadders.normalizeSizes(sizesRaw)
            if (sizes.size < 2) continue
            val refRaw = ArrayList<Double>()
            l.optJSONArray("ref")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val v = arr.opt(j)
                    if (v is Number && v.toDouble().isFinite()) refRaw.add(v.toDouble())
                }
            }
            val ref = GelLadders.normalizeSizes(refRaw).filter { it in sizes }
            out.add(
                LadderDef(
                    id = id, name = name,
                    kind = if (l.optString("kind") == "protein") "protein" else "dna",
                    sizes = sizes, ref = ref, custom = true,
                )
            )
        }
        return out
    }

    private fun laneStyleIsEmpty(s: LaneStyle): Boolean =
        s.fontSize == null && s.angle == null && s.italic == null &&
            s.superscript == null && s.subscript == null && s.red == null

    private fun sanitizeLaneStyle(o: JSONObject?): LaneStyle? {
        if (o == null) return null
        val style = LaneStyle()
        val fs = o.opt("fontSize")
        if (fs is Number && fs.toDouble().isFinite()) style.fontSize = clamp(fs.toDouble(), 4.0, 40.0)
        val ang = o.opt("angle")
        if (ang is Number && ang.toDouble().isFinite()) style.angle = clamp(ang.toDouble(), -90.0, 90.0)
        if (o.opt("italic") == true) style.italic = true
        if (o.opt("superscript") == true) style.superscript = true
        if (o.opt("subscript") == true) style.subscript = true
        if (o.opt("red") == true) style.red = true
        return if (laneStyleIsEmpty(style)) null else style
    }

    private fun sanitizeBands(value: Any?): MutableList<GelBand> {
        val out = ArrayList<GelBand>()
        if (value !is JSONArray) return out
        for (i in 0 until value.length()) {
            val b = value.optJSONObject(i) ?: continue
            val band = GelBand(
                y = clamp(num(b, "y", 0.0), 0.0, 1.0),
                intensity = num(b, "intensity", 0.0),
                showSize = b.opt("showSize") != false,
            )
            val size = b.opt("size")
            if (size is Number && size.toDouble().isFinite() && size.toDouble() > 0) band.size = size.toDouble()
            if (b.opt("manual") == true) band.manual = true
            if (b.opt("approx") == true) band.approx = true
            val mark = b.opt("mark")
            if (mark is String && mark.isNotEmpty()) band.mark = mark
            if (b.opt("hidden") == true) band.hidden = true
            out.add(band)
        }
        return out
    }

    private fun sanitizeLanes(value: Any?): MutableList<GelLane> {
        val out = ArrayList<GelLane>()
        if (value !is JSONArray) return out
        val used = HashSet<String>()
        for (i in 0 until value.length()) {
            val l = value.optJSONObject(i) ?: continue
            val rawId = l.opt("id")
            var id = if (rawId is String && rawId.isNotEmpty() && !used.contains(rawId)) rawId else GelTypes.newLaneId()
            while (used.contains(id)) id = GelTypes.newLaneId()
            used.add(id)
            val lane = GelLane(
                id = id,
                x = clamp(num(l, "x", 0.5), 0.0, 1.0),
                width = clamp(num(l, "width", 0.05), 0.005, 1.0),
                name = if (l.opt("name") is String) l.optString("name") else (i + 1).toString(),
                isMarker = l.opt("isMarker") == true,
                bands = sanitizeBands(l.opt("bands")),
            )
            val ladderId = l.opt("ladderId")
            if (ladderId is String) lane.ladderId = ladderId
            if (l.opt("removed") == true) lane.removed = true
            val splice = l.optString("splice")
            if (splice == "gap" || splice == "line") lane.splice = splice
            val style = sanitizeLaneStyle(l.optJSONObject("style"))
            if (style != null) lane.style = style
            out.add(lane)
        }
        return out
    }

    private fun sanitizeGroups(value: Any?, laneIds: Set<String>): MutableList<GelGroup> {
        val out = ArrayList<GelGroup>()
        if (value !is JSONArray) return out
        for (i in 0 until value.length()) {
            val g = value.optJSONObject(i) ?: continue
            val id = g.optString("id")
            if (id.isEmpty() || g.opt("id") !is String) continue
            val ids = LinkedHashSet<String>()
            g.optJSONArray("laneIds")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val v = arr.opt(j)
                    if (v is String && laneIds.contains(v)) ids.add(v)
                }
            }
            if (ids.isEmpty()) continue
            out.add(GelGroup(id = id, name = asString(g, "name"), laneIds = ids.toMutableList()))
        }
        return out
    }

    private fun sanitizeConditionRows(value: Any?, laneIds: Set<String>): MutableList<ConditionRow> {
        val out = ArrayList<ConditionRow>()
        if (value !is JSONArray) return out
        for (i in 0 until value.length()) {
            val row = value.optJSONObject(i) ?: continue
            val id = row.optString("id")
            if (id.isEmpty() || row.opt("id") !is String) continue
            val cells = LinkedHashMap<String, String>()
            row.optJSONObject("cells")?.let { cellsObj ->
                for (laneId in cellsObj.keys()) {
                    val mark = cellsObj.opt(laneId)
                    if (laneIds.contains(laneId) && mark is String) cells[laneId] = mark
                }
            }
            out.add(ConditionRow(id = id, label = asString(row, "label"), cells = cells))
        }
        return out
    }

    private fun sanitizeAnnotations(value: Any?, laneIds: Set<String>): MutableList<GelAnnotation> {
        val out = ArrayList<GelAnnotation>()
        if (value !is JSONArray) return out
        for (i in 0 until value.length()) {
            val a = value.optJSONObject(i) ?: continue
            val rawId = a.opt("id")
            val id = if (rawId is String && rawId.isNotEmpty()) rawId else GelTypes.newId("ann")
            val color = if (a.optString("color") == "red") "red" else "default"
            val fsRaw = a.opt("fontSize")
            val fontSize = if (fsRaw is Number && fsRaw.toDouble().isFinite()) clamp(fsRaw.toDouble(), 4.0, 60.0) else null
            when (a.optString("type")) {
                "arrow" -> {
                    val laneId = a.optString("laneId")
                    if (a.opt("laneId") !is String || !laneIds.contains(laneId)) continue
                    val bandIndex = Math.floor(num(a, "bandIndex", -1.0)).toInt()
                    if (bandIndex < 0) continue
                    val arrow = GelAnnotation.Arrow(
                        id = id, laneId = laneId, bandIndex = bandIndex,
                        side = if (a.optString("side") == "right") "right" else "left",
                        color = color,
                    )
                    val label = a.opt("label")
                    if (label is String && label.isNotEmpty()) arrow.label = label
                    if (fontSize != null) arrow.fontSize = fontSize
                    out.add(arrow)
                }
                "box" -> {
                    val box = GelAnnotation.Box(
                        id = id,
                        x = clamp(num(a, "x", 0.0), 0.0, 1.0),
                        y = clamp(num(a, "y", 0.0), 0.0, 1.0),
                        w = clamp(num(a, "w", 0.1), 0.001, 1.0),
                        h = clamp(num(a, "h", 0.1), 0.001, 1.0),
                        label = asString(a, "label"),
                        enlarge = a.opt("enlarge") == true,
                        color = color,
                    )
                    if (fontSize != null) box.fontSize = fontSize
                    out.add(box)
                }
                "bracket" -> {
                    val bracket = GelAnnotation.Bracket(
                        id = id,
                        side = asSide(a, "side"),
                        start = clamp(num(a, "start", 0.0), 0.0, 1.0),
                        end = clamp(num(a, "end", 1.0), 0.0, 1.0),
                        label = asString(a, "label"),
                        color = color,
                    )
                    if (fontSize != null) bracket.fontSize = fontSize
                    out.add(bracket)
                }
                "sideText" -> {
                    val text = GelAnnotation.SideText(
                        id = id,
                        side = asSide(a, "side"),
                        pos = clamp(num(a, "pos", 0.5), 0.0, 1.0),
                        text = asString(a, "text"),
                        color = color,
                    )
                    if (fontSize != null) text.fontSize = fontSize
                    out.add(text)
                }
            }
        }
        return out
    }

    private fun sanitizeAdjust(o: JSONObject?): GelProject.GelAdjust {
        val raw = o ?: JSONObject()
        return GelProject.GelAdjust(
            grayscale = raw.opt("grayscale") != false,
            invert = raw.opt("invert") == true,
            contrast = clamp(num(raw, "contrast", 1.0), 0.05, 10.0),
            brightness = clamp(num(raw, "brightness", 0.0), -1.0, 1.0),
            gamma = clamp(num(raw, "gamma", 1.0), 0.1, 5.0),
            gammaLocked = raw.opt("gammaLocked") != false,
        )
    }

    private fun sanitizeCrop(o: JSONObject?): GelProject.GelCrop {
        val raw = o ?: JSONObject()
        // Each side is capped below 0.5 so the remaining window never collapses.
        return GelProject.GelCrop(
            left = clamp(num(raw, "left", 0.0), 0.0, 0.49),
            top = clamp(num(raw, "top", 0.0), 0.0, 0.49),
            right = clamp(num(raw, "right", 0.0), 0.0, 0.49),
            bottom = clamp(num(raw, "bottom", 0.0), 0.0, 0.49),
        )
    }

    private val FONT_FAMILIES = listOf("Arial", "Times New Roman", "Helvetica", "Calibri", "Cambria")

    private fun sanitizeLayout(o: JSONObject?): GelLayoutSettings {
        val base = GelTypes.emptyProject().layout
        val raw = o ?: JSONObject()
        val family = raw.optString("fontFamily")
        return GelLayoutSettings(
            fontFamily = if (family in FONT_FAMILIES) family else base.fontFamily,
            nameFontSize = clamp(num(raw, "nameFontSize", base.nameFontSize), 4.0, 40.0),
            labelFontSize = clamp(num(raw, "labelFontSize", base.labelFontSize), 4.0, 40.0),
            nameAngle = clamp(num(raw, "nameAngle", base.nameAngle), 0.0, 90.0),
            red = raw.opt("red") == true,
            conditionsNearestGel = raw.opt("conditionsNearestGel") == true,
            figureWidthMm = clamp(num(raw, "figureWidthMm", base.figureWidthMm), 40.0, 600.0),
            groupLabelFit = if (raw.optString("groupLabelFit") == "shrink" || raw.optString("groupLabelFit") == "one") raw.optString("groupLabelFit") else "wrap",
        )
    }

    private fun sanitizeFit(o: JSONObject?): GelLadderFit? {
        if (o == null) return null
        val ladderId = o.optString("ladderId")
        if (ladderId.isEmpty() || o.opt("ladderId") !is String) return null
        if (o.opt("laneIds") !is JSONArray) return null
        val laneIds = ArrayList<String>()
        val arr = o.optJSONArray("laneIds")!!
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) laneIds.add(v)
        }
        val aRaw = o.opt("a")
        val bRaw = o.opt("b")
        val a = if (aRaw is Number && aRaw.toDouble().isFinite()) aRaw.toDouble() else Double.NaN
        val b = if (bRaw is Number && bRaw.toDouble().isFinite()) bRaw.toDouble() else Double.NaN
        val c = num(o, "c", 0.0)
        if (a.isNaN() || b.isNaN() || c.isNaN()) return null
        return GelLadderFit(
            ladderId = ladderId,
            laneIds = laneIds,
            a = a, b = b, c = c,
            degree = if (o.opt("degree") == 2 || o.opt("degree") == 2.0) 2 else 1,
            r2 = clamp(num(o, "r2", 0.0), 0.0, 1.0),
            n = maxOf(0, Math.round(num(o, "n", 0.0)).toInt()),
        )
    }

    private fun sanitizeExposures(value: Any?): MutableList<GelExposure> {
        val out = ArrayList<GelExposure>()
        if (value !is JSONArray) return out
        val used = HashSet<String>()
        for (i in 0 until value.length()) {
            val e = value.optJSONObject(i) ?: continue
            val dataUrl = e.optString("dataUrl")
            if (e.opt("dataUrl") !is String || !dataUrl.startsWith("data:image/")) continue
            val rawId = e.opt("id")
            var id = if (rawId is String && rawId.isNotEmpty() && !used.contains(rawId)) rawId else GelTypes.newId("exp")
            while (used.contains(id)) id = GelTypes.newId("exp")
            used.add(id)
            val exposure = GelExposure(
                id = id,
                name = asString(e, "name", "image ${out.size + 1}"),
                dataUrl = dataUrl,
                width = maxOf(1, Math.round(num(e, "width", 1.0)).toInt()),
                height = maxOf(1, Math.round(num(e, "height", 1.0)).toInt()),
                mime = asString(e, "mime", "image/png"),
            )
            val originalBase64 = e.opt("originalBase64")
            if (originalBase64 is String && originalBase64.isNotEmpty()) exposure.originalBase64 = originalBase64
            val sha = e.opt("sha256")
            if (sha is String && sha.isNotEmpty()) exposure.sha256 = sha
            out.add(exposure)
        }
        return out
    }

    /**
     * Validate every field the editor reads. Exported so the storage loaders
     * use the same path as project documents. 先经 projectToJson 序列化、再走
     * 与外部文档一致的 sanitize 管线（与原版 unknown 入口语义等价）。
     */
    fun normalizeProject(p: GelProject): GelProject {
        val base = GelTypes.emptyProject()
        val doc = GelTypes.projectToJson(p)
        val exposures = sanitizeExposures(doc.optJSONArray("exposures"))
        val lanes = sanitizeLanes(doc.optJSONArray("lanes"))
        val laneIds = lanes.map { it.id }.toSet()
        val activeExposureId =
            if (exposures.any { it.id == p.activeExposureId }) p.activeExposureId
            else (exposures.firstOrNull()?.id ?: "")
        return GelProject(
            version = 2,
            imageName = if (p.imageName.isNotEmpty()) p.imageName else (exposures.firstOrNull()?.name ?: ""),
            exposures = exposures,
            activeExposureId = activeExposureId,
            display = if (p.display == "color") "color" else "gray",
            rotation = clamp(p.rotation, -45.0, 45.0),
            orient = ((Math.round(p.orient.toDouble()) % 4 + 4) % 4).toInt(),
            mirrorH = p.mirrorH,
            mirrorV = p.mirrorV,
            adjust = sanitizeAdjust(doc.optJSONObject("adjust")),
            crop = sanitizeCrop(doc.optJSONObject("crop")),
            gelType = if (p.gelType == "protein") "protein" else "dna",
            detect = GelPresets.sanitizeDetectConfig(doc.optJSONObject("detect")),
            unit = if (p.unit == "kDa") "kDa" else "bp",
            ladderId = if (p.ladderId.isNotEmpty()) p.ladderId else base.ladderId,
            customLadders = sanitizeLadders(doc.optJSONArray("customLadders")),
            favorites = p.favorites.filter { it is String }.toMutableList(),
            lanes = lanes,
            groups = sanitizeGroups(doc.optJSONArray("groups"), laneIds),
            conditionRows = sanitizeConditionRows(doc.optJSONArray("conditionRows"), laneIds),
            annotations = sanitizeAnnotations(doc.optJSONArray("annotations"), laneIds),
            layout = sanitizeLayout(doc.optJSONObject("layout")),
            fit = sanitizeFit(doc.optJSONObject("fit")),
            notes = p.notes,
            createdAt = if (p.createdAt.isNotEmpty()) p.createdAt else base.createdAt,
            updatedAt = if (p.updatedAt.isNotEmpty()) p.updatedAt else base.updatedAt,
        )
    }

    /** Validate and migrate a SPICE_PROJECT gel document payload. */
    fun sanitizeGelProject(value: Any?): GelProject? {
        return try {
            if (value !is JSONObject) return null
            when {
                value.opt("version") == 1 -> normalizeProject(GelTypes.migrateV1(value))
                !GelTypes.isGelProject(value) -> null
                else -> normalizeProject(GelTypes.projectFromJson(value))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** One-line summary stored on the project document and shown in the hub. */
    fun gelSummary(project: GelProject): String {
        val lanes = project.lanes.filter { it.removed != true }
        val bands = lanes.sumOf { it.bands.size }
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
        val parts = mutableListOf("${lanes.size} lanes", "$bands bands")
        if (ladder != null) parts.add(ladder.name)
        project.fit?.let { parts.add("R2 " + String.format(Locale.US, "%.3f", it.r2)) }
        return parts.joinToString(" · ")
    }

    /** Raster preview rendered through the normal figure pipeline. */
    fun gelProjectPreview(project: GelProject): String? {
        return try {
            val exposure = GelTypes.activeExposure(project) ?: return null
            val bitmap = GelPipeline.loadBitmapFromDataUrl(exposure.dataUrl) ?: return null
            val processed = GelPipeline.renderProcessed(exposure, project, bitmap) ?: return null
            val layout = GelFigure.buildFigure(project, processed)
            val canvas = GelRender.rasterizeFigure(layout, processed, 96.0)
            val out = ByteArrayOutputStream()
            canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Every setting that influences the rendered figure, for parameters.json. */
    fun gelParameters(project: GelProject, exportDpi: Int = 300): JSONObject {
        val exposure = GelTypes.activeExposure(project)
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
        val o = JSONObject()
        o.put("schema", "SPICE_GEL_PROJECT")
        o.put("projectVersion", project.version)
        o.put("exportDpi", exportDpi)
        o.put("image", if (exposure != null) JSONObject().apply {
            put("name", exposure.name)
            put("width", exposure.width)
            put("height", exposure.height)
            put("mime", exposure.mime)
            put("sha256", exposure.sha256 ?: JSONObject.NULL)
        } else JSONObject.NULL)
        o.put("exposures", JSONArray().apply {
            for (e in project.exposures) {
                put(JSONObject().apply {
                    put("name", e.name)
                    put("width", e.width)
                    put("height", e.height)
                    put("mime", e.mime)
                    put("sha256", e.sha256 ?: JSONObject.NULL)
                })
            }
        })
        val doc = GelTypes.projectToJson(project)
        o.put("processing", JSONObject().apply {
            put("display", project.display)
            put("rotation", project.rotation)
            put("orient", project.orient)
            put("mirrorH", project.mirrorH)
            put("mirrorV", project.mirrorV)
            put("adjust", doc.optJSONObject("adjust"))
            put("crop", doc.optJSONObject("crop"))
        })
        o.put("gel", JSONObject().apply {
            put("type", project.gelType)
            put("unit", project.unit)
            put("ladderId", project.ladderId)
            put("ladderName", ladder?.name ?: JSONObject.NULL)
            put("ladderSizes", if (ladder != null) JSONArray().apply {
                ladder.sizes.forEach { put(GelLadders.numberForJson(it)) }
            } else JSONObject.NULL)
            put("favoriteLadders", JSONArray(project.favorites))
        })
        o.put("lanes", JSONArray().apply {
            project.lanes.forEachIndexed { index, lane ->
                put(JSONObject().apply {
                    put("index", index + 1)
                    put("name", lane.name)
                    put("x", lane.x)
                    put("width", lane.width)
                    put("marker", lane.isMarker)
                    put("removed", lane.removed == true)
                    put("splice", lane.splice ?: "none")
                    put("bandCount", lane.bands.size)
                })
            }
        })
        o.put("fit", GelTypes.fitToJson(project.fit) ?: JSONObject.NULL)
        o.put("annotations", doc.optJSONArray("annotations") ?: JSONArray())
        o.put("layout", doc.optJSONObject("layout") ?: JSONObject())
        o.put("notes", project.notes)
        return o
    }

    private fun csvCell(value: Any?): String {
        val text = if (value == null) "" else value.toString()
        return if (Regex("[\",\r\n]").containsMatchIn(text)) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    /** JS Number(value.toPrecision(6)) 的字符串形态：6 位有效数字。 */
    private fun toPrecision6(v: Double): String {
        if (v == 0.0) return "0"
        return String.format(Locale.US, "%.5g", v)
    }

    /** Per-band data table included in the reproducible bundle. */
    fun gelCsv(project: GelProject): String {
        val rows = ArrayList<String>()
        rows.add(listOf("lane", "lane_name", "marker", "band", "y", "size", "unit", "approx", "visible", "intensity").joinToString(","))
        project.lanes.forEachIndexed { laneIndex, lane ->
            lane.bands.forEachIndexed { bandIndex, band ->
                rows.add(
                    listOf(
                        (laneIndex + 1).toString(),
                        csvCell(lane.name),
                        lane.isMarker.toString(),
                        (bandIndex + 1).toString(),
                        String.format(Locale.US, "%.5f", band.y),
                        if (band.size == null) "" else toPrecision6(band.size!!),
                        project.unit,
                        (band.approx == true).toString(),
                        (band.hidden != true && band.showSize != false).toString(),
                        toPrecision6(band.intensity),
                    ).joinToString(",")
                )
            }
        }
        return rows.joinToString("\n") + "\n"
    }

    /** Draft methods text for the reproducible bundle. */
    fun gelMethods(project: GelProject): String {
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, project.customLadders)
        val mirror =
            if (project.mirrorH || project.mirrorV)
                " Mirrored ${if (project.mirrorH) "horizontally" else ""}${if (project.mirrorH && project.mirrorV) " and " else ""}${if (project.mirrorV) "vertically" else ""}."
            else ""
        val calibration = if (project.fit != null) {
            val f = project.fit!!
            "Band sizes were calibrated against ${ladder?.name ?: f.ladderId} using ${f.n} marker bands (${f.degree}-degree fit of log10 size against band position, R2 = ${String.format(Locale.US, "%.3f", f.r2)}). Size estimates outside the fitted marker range are marked as approximate."
        } else {
            "No marker ladder calibration was applied; band positions are reported without interpolated sizes."
        }
        return listOf(
            "Gel images were processed and figures assembled in SPICE GUI. All image transforms and measurements were performed locally on the original exposure file.",
            "Gel type: ${if (project.gelType == "protein") "protein" else "DNA"}; size unit: ${project.unit}. Fine rotation ${String.format(Locale.US, "%.1f", project.rotation)} degrees; quarter-turn orientation ${project.orient * 90} degrees.$mirror",
            "Display: ${project.display}; grayscale ${if (project.adjust.grayscale) "on" else "off"}; contrast ${project.adjust.contrast}; brightness ${project.adjust.brightness}; gamma ${project.adjust.gamma}; invert ${if (project.adjust.invert) "on" else "off"}.",
            "Visible lanes: ${project.lanes.filter { it.removed != true }.size}; detected bands: ${project.lanes.sumOf { it.bands.size }}.",
            calibration,
            "The figure PNG, editable SVG, project JSON, band table CSV and parameters.json in this bundle were generated from the same in-memory project state.",
        ).joinToString("\n\n")
    }
}
