package com.spice.nativeapp.tools.gel

/**
 * Gel figure project model (v2). 1:1 移植自原版 src/lib/tools/gel/types.ts。
 *
 * Coordinates are normalised to the *processed full image* (orientation and
 * fine rotation applied, before cropping), so changing the crop only selects
 * which wells are shown and never moves names, markers or annotations.
 */
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GelExposure(
    var id: String,
    var name: String,
    var dataUrl: String,
    var width: Int,
    var height: Int,
    var mime: String,
    /** Raw file bytes, base64. Kept for the "original data" export. */
    var originalBase64: String? = null,
    var sha256: String? = null,
)

data class GelBand(
    /** Normalised y in the processed full image. */
    var y: Double,
    var intensity: Double,
    var size: Double? = null,
    /** Label/tick visible on the figure. Hiding keeps calibration. */
    var showSize: Boolean? = null,
    /** Typed in by hand instead of fitted; survives re-fitting of others. */
    var manual: Boolean? = null,
    /** Size lies outside the ladder range or disagrees between markers. */
    var approx: Boolean? = null,
    /** Non-specific band mark: '*' | '#' | '†'. */
    var mark: String? = null,
    var hidden: Boolean? = null,
)

data class LaneStyle(
    var fontSize: Double? = null,
    var angle: Double? = null,
    var italic: Boolean? = null,
    var superscript: Boolean? = null,
    var subscript: Boolean? = null,
    var red: Boolean? = null,
)

data class GelLane(
    var id: String,
    /** Normalised x in the processed full image. */
    var x: Double,
    /** Normalised lane width used for band detection. */
    var width: Double,
    var name: String,
    var isMarker: Boolean,
    var ladderId: String? = null,
    var bands: MutableList<GelBand> = mutableListOf(),
    /** Lane removed from the figure but kept in the project/report. */
    var removed: Boolean? = null,
    /** "none" | "gap" | "line" */
    var splice: String? = null,
    var style: LaneStyle? = null,
)

data class GelGroup(
    var id: String,
    var name: String,
    var laneIds: MutableList<String> = mutableListOf(),
)

data class ConditionRow(
    var id: String,
    var label: String,
    /** laneId -> "+" | "-" | "" */
    var cells: MutableMap<String, String> = mutableMapOf(),
)

data class GelLadderFit(
    var ladderId: String,
    var laneIds: MutableList<String> = mutableListOf(),
    /** log10(size) = a + b*y + c*y^2 (c = 0 for a straight fit). */
    var a: Double,
    var b: Double,
    var c: Double,
    /** 1 | 2 */
    var degree: Int,
    var r2: Double,
    var n: Int,
)

/** Annotation color: "default" | "red". */
typealias AnnotationColor = String

sealed class GelAnnotation {
    abstract val id: String
    abstract var color: AnnotationColor
    abstract var fontSize: Double?

    data class Arrow(
        override val id: String,
        var laneId: String,
        var bandIndex: Int,
        /** "left" | "right" */
        var side: String,
        var label: String? = null,
        override var color: AnnotationColor = "default",
        override var fontSize: Double? = null,
    ) : GelAnnotation()

    data class Box(
        override val id: String,
        /** Normalised to the processed full image. */
        var x: Double,
        var y: Double,
        var w: Double,
        var h: Double,
        var label: String,
        /** Draw an enlarged copy beside the gel. */
        var enlarge: Boolean,
        override var color: AnnotationColor = "default",
        override var fontSize: Double? = null,
    ) : GelAnnotation()

    data class Bracket(
        override val id: String,
        /** Vertical brackets span y; horizontal brackets span x. */
        /** "left" | "right" | "top" | "bottom" */
        var side: String,
        /** Normalised start/end along the bracket axis (y for left/right, x for top/bottom). */
        var start: Double,
        var end: Double,
        var label: String,
        override var color: AnnotationColor = "default",
        override var fontSize: Double? = null,
    ) : GelAnnotation()

    data class SideText(
        override val id: String,
        /** "left" | "right" | "top" | "bottom" */
        var side: String,
        /** 0..1 along the edge. */
        var pos: Double,
        var text: String,
        override var color: AnnotationColor = "default",
        override var fontSize: Double? = null,
    ) : GelAnnotation()
}

data class GelLayoutSettings(
    /** "Arial" | "Times New Roman" | "Helvetica" | "Calibri" | "Cambria" */
    var fontFamily: String = "Arial",
    var nameFontSize: Double = 9.0,
    var labelFontSize: Double = 8.0,
    /** Degrees, 0 keeps horizontal names. */
    var nameAngle: Double = 0.0,
    /** Red annotations and lane names on request. */
    var red: Boolean = false,
    /** Default false: names hug the gel, condition rows stack below them. */
    var conditionsNearestGel: Boolean = false,
    /** Printed figure width in millimetres. */
    var figureWidthMm: Double = 120.0,
    /** Group labels that do not fit: "wrap" | "shrink" | "one". */
    var groupLabelFit: String = "wrap",
)

data class GelProject(
    var version: Int = 2,
    var imageName: String = "",
    var exposures: MutableList<GelExposure> = mutableListOf(),
    var activeExposureId: String = "",
    /** "gray" | "color" */
    var display: String = "gray",
    /** Fine rotation, -45..45 degrees. */
    var rotation: Double = 0.0,
    /** Quarter turns clockwise, 0..3. */
    var orient: Int = 0,
    var mirrorH: Boolean = false,
    var mirrorV: Boolean = false,
    /** Grayscale remains the default for publication figures. */
    var adjust: GelAdjust = GelAdjust(),
    var crop: GelCrop = GelCrop(),
    /** "dna" | "protein" */
    var gelType: String = "dna",
    /** Stain preset and advanced lane/band detection settings. */
    var detect: GelDetectConfig = GelPresets.defaultDetectConfig(),
    /** "bp" | "kDa" */
    var unit: String = "bp",
    var ladderId: String = "gr1kb",
    /** Local, project-resident ladder definitions. */
    var customLadders: MutableList<LadderDef> = mutableListOf(),
    /** Ladder ids the user actually uses; preferred when matching. */
    var favorites: MutableList<String> = mutableListOf(),
    var lanes: MutableList<GelLane> = mutableListOf(),
    var groups: MutableList<GelGroup> = mutableListOf(),
    var conditionRows: MutableList<ConditionRow> = mutableListOf(),
    var annotations: MutableList<GelAnnotation> = mutableListOf(),
    var layout: GelLayoutSettings = GelLayoutSettings(),
    var fit: GelLadderFit? = null,
    var notes: String = "",
    var createdAt: String = "",
    var updatedAt: String = "",
) {
    /** Grayscale remains the default for publication figures. */
    data class GelAdjust(
        var grayscale: Boolean = true,
        var invert: Boolean = false,
        var contrast: Double = 1.0,
        var brightness: Double = 0.0,
        var gamma: Double = 1.0,
        var gammaLocked: Boolean = true,
    )

    data class GelCrop(
        var left: Double = 0.0,
        var top: Double = 0.0,
        var right: Double = 0.0,
        var bottom: Double = 0.0,
    )
}

object GelTypes {

    fun newId(prefix: String = "id"): String =
        "${prefix}_${UUID.randomUUID().toString().take(8)}"

    fun newLaneId(): String = newId("lane")

    fun emptyLayout(): GelLayoutSettings = GelLayoutSettings()

    fun emptyProject(): GelProject {
        val now = java.time.OffsetDateTime.now().toString()
        return GelProject(createdAt = now, updatedAt = now)
    }

    fun activeExposure(p: GelProject): GelExposure? =
        p.exposures.firstOrNull { it.id == p.activeExposureId }
            ?: p.exposures.firstOrNull()

    fun touch(p: GelProject) {
        p.updatedAt = java.time.OffsetDateTime.now().toString()
    }

    fun isGelProject(o: JSONObject?): Boolean {
        if (o == null) return false
        return o.optInt("version") == 2 &&
            o.has("exposures") && o.optJSONArray("exposures") != null &&
            o.has("lanes") && o.optJSONArray("lanes") != null &&
            o.has("annotations") && o.optJSONArray("annotations") != null &&
            o.optJSONObject("layout") != null &&
            o.optJSONObject("adjust") != null &&
            o.optJSONObject("crop") != null
    }

    /** v1 projects stored a single image and a flat ladder-size list. */
    fun migrateV1(v1: JSONObject): GelProject {
        val p = emptyProject()
        val exposure = GelExposure(
            id = newId("exp"),
            name = v1.optString("imageName").ifEmpty { "image" },
            dataUrl = v1.optString("imageDataUrl"),
            width = v1.optInt("imageWidth"),
            height = v1.optInt("imageHeight"),
            mime = "image/png",
        )
        p.imageName = v1.optString("imageName")
        p.exposures = mutableListOf(exposure)
        p.activeExposureId = exposure.id
        p.rotation = if (v1.has("rotation")) v1.optDouble("rotation") else 0.0
        v1.optJSONObject("adjust")?.let { adj ->
            p.adjust = GelProject.GelAdjust(
                grayscale = adj.optBoolean("grayscale", true),
                invert = adj.optBoolean("invert", false),
                contrast = adj.optDouble("contrast", 1.0),
                brightness = adj.optDouble("brightness", 0.0),
                gamma = adj.optDouble("gamma", 1.0),
                gammaLocked = true,
            )
        }
        v1.optJSONObject("crop")?.let { cr ->
            p.crop = GelProject.GelCrop(
                left = cr.optDouble("left", 0.0),
                top = cr.optDouble("top", 0.0),
                right = cr.optDouble("right", 0.0),
                bottom = cr.optDouble("bottom", 0.0),
            )
        }
        p.unit = if (v1.optString("unit") == "kDa") "kDa" else "bp"
        p.gelType = if (p.unit == "kDa") "protein" else "dna"
        v1.optJSONArray("lanes")?.let { arr ->
            p.lanes = MutableList(arr.length()) { i -> laneFromJson(arr.getJSONObject(i)) }
            p.lanes.forEach { lane -> lane.bands.forEach { if (it.showSize == null) it.showSize = true } }
        }
        v1.optJSONArray("groups")?.let { arr ->
            p.groups = MutableList(arr.length()) { i -> groupFromJson(arr.getJSONObject(i)) }
        }
        // A one-off ladder defined by the old size list is preserved as a custom one.
        val sizes = ArrayList<Double>()
        v1.optJSONArray("ladderSizes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optDouble(i, Double.NaN)
                if (n.isFinite() && n > 0) sizes.add(n)
            }
        }
        sizes.sortDescending()
        if (sizes.size >= 2) {
            val def = LadderDef(
                id = newId("custom"),
                name = "Custom ladder (${sizes.size} bands)",
                kind = p.gelType,
                sizes = sizes,
                ref = emptyList(),
                custom = true,
            )
            p.customLadders = mutableListOf(def)
            p.ladderId = def.id
        } else {
            p.ladderId = if (p.gelType == "protein") "biorad_dual" else "gr1kb"
        }
        return p
    }

    // ---------------------------------------------------------------- JSON

    fun bandToJson(b: GelBand): JSONObject = JSONObject().apply {
        put("y", b.y)
        put("intensity", b.intensity)
        b.size?.let { put("size", GelLadders.numberForJson(it)) }
        b.showSize?.let { put("showSize", it) }
        b.manual?.let { put("manual", it) }
        b.approx?.let { put("approx", it) }
        b.mark?.let { put("mark", it) }
        b.hidden?.let { put("hidden", it) }
    }

    fun bandFromJson(o: JSONObject): GelBand = GelBand(
        y = o.optDouble("y", 0.0),
        intensity = o.optDouble("intensity", 0.0),
        size = o.optDoubleOrNull("size"),
        showSize = if (o.has("showSize")) o.optBoolean("showSize") else null,
        manual = if (o.has("manual")) o.optBoolean("manual") else null,
        approx = if (o.has("approx")) o.optBoolean("approx") else null,
        mark = o.optStringOrNull("mark"),
        hidden = if (o.has("hidden")) o.optBoolean("hidden") else null,
    )

    fun styleToJson(s: LaneStyle?): JSONObject? {
        if (s == null) return null
        val o = JSONObject()
        s.fontSize?.let { o.put("fontSize", it) }
        s.angle?.let { o.put("angle", it) }
        s.italic?.let { o.put("italic", it) }
        s.superscript?.let { o.put("superscript", it) }
        s.subscript?.let { o.put("subscript", it) }
        s.red?.let { o.put("red", it) }
        return if (o.length() == 0) null else o
    }

    fun styleFromJson(o: JSONObject?): LaneStyle? {
        if (o == null) return null
        return LaneStyle(
            fontSize = o.optDoubleOrNull("fontSize"),
            angle = o.optDoubleOrNull("angle"),
            italic = if (o.has("italic")) o.optBoolean("italic") else null,
            superscript = if (o.has("superscript")) o.optBoolean("superscript") else null,
            subscript = if (o.has("subscript")) o.optBoolean("subscript") else null,
            red = if (o.has("red")) o.optBoolean("red") else null,
        )
    }

    fun laneToJson(l: GelLane): JSONObject = JSONObject().apply {
        put("id", l.id)
        put("x", l.x)
        put("width", l.width)
        put("name", l.name)
        put("isMarker", l.isMarker)
        l.ladderId?.let { put("ladderId", it) }
        put("bands", JSONArray().apply { l.bands.forEach { put(bandToJson(it)) } })
        l.removed?.let { put("removed", it) }
        l.splice?.let { put("splice", it) }
        styleToJson(l.style)?.let { put("style", it) }
    }

    fun laneFromJson(o: JSONObject): GelLane {
        val bands = mutableListOf<GelBand>()
        o.optJSONArray("bands")?.let { arr ->
            for (i in 0 until arr.length()) bands.add(bandFromJson(arr.getJSONObject(i)))
        }
        return GelLane(
            id = o.optString("id", newId("lane")),
            x = o.optDouble("x", 0.5),
            width = o.optDouble("width", 0.05),
            name = o.optString("name", ""),
            isMarker = o.optBoolean("isMarker", false),
            ladderId = o.optStringOrNull("ladderId"),
            bands = bands,
            removed = if (o.has("removed")) o.optBoolean("removed") else null,
            splice = o.optStringOrNull("splice"),
            style = styleFromJson(o.optJSONObject("style")),
        )
    }

    fun groupFromJson(o: JSONObject): GelGroup {
        val laneIds = mutableListOf<String>()
        o.optJSONArray("laneIds")?.let { arr ->
            for (i in 0 until arr.length()) laneIds.add(arr.getString(i))
        }
        return GelGroup(id = o.optString("id", newId("group")), name = o.optString("name", ""), laneIds = laneIds)
    }

    fun groupToJson(g: GelGroup): JSONObject = JSONObject().apply {
        put("id", g.id)
        put("name", g.name)
        put("laneIds", JSONArray(g.laneIds))
    }

    fun conditionRowFromJson(o: JSONObject): ConditionRow {
        val cells = mutableMapOf<String, String>()
        o.optJSONObject("cells")?.let { c ->
            c.keys().forEach { k -> cells[k] = c.optString(k, "") }
        }
        return ConditionRow(id = o.optString("id", newId("cond")), label = o.optString("label", ""), cells = cells)
    }

    fun conditionRowToJson(r: ConditionRow): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("label", r.label)
        put("cells", JSONObject(r.cells.toMap()))
    }

    fun annotationToJson(a: GelAnnotation): JSONObject {
        val o = JSONObject()
        o.put("id", a.id)
        when (a) {
            is GelAnnotation.Arrow -> {
                o.put("type", "arrow")
                o.put("laneId", a.laneId)
                o.put("bandIndex", a.bandIndex)
                o.put("side", a.side)
                a.label?.let { o.put("label", it) }
                o.put("color", a.color)
                a.fontSize?.let { o.put("fontSize", it) }
            }
            is GelAnnotation.Box -> {
                o.put("type", "box")
                o.put("x", a.x)
                o.put("y", a.y)
                o.put("w", a.w)
                o.put("h", a.h)
                o.put("label", a.label)
                o.put("enlarge", a.enlarge)
                o.put("color", a.color)
                a.fontSize?.let { o.put("fontSize", it) }
            }
            is GelAnnotation.Bracket -> {
                o.put("type", "bracket")
                o.put("side", a.side)
                o.put("start", a.start)
                o.put("end", a.end)
                o.put("label", a.label)
                o.put("color", a.color)
                a.fontSize?.let { o.put("fontSize", it) }
            }
            is GelAnnotation.SideText -> {
                o.put("type", "sideText")
                o.put("side", a.side)
                o.put("pos", a.pos)
                o.put("text", a.text)
                o.put("color", a.color)
                a.fontSize?.let { o.put("fontSize", it) }
            }
        }
        return o
    }

    fun annotationFromJson(o: JSONObject): GelAnnotation? {
        val id = o.optString("id", newId("ann"))
        val color = if (o.optString("color") == "red") "red" else "default"
        val fontSize = o.optDoubleOrNull("fontSize")
        return when (o.optString("type")) {
            "arrow" -> GelAnnotation.Arrow(
                id = id,
                laneId = o.optString("laneId", ""),
                bandIndex = o.optInt("bandIndex", 0),
                side = if (o.optString("side") == "right") "right" else "left",
                label = o.optStringOrNull("label"),
                color = color,
                fontSize = fontSize,
            )
            "box" -> GelAnnotation.Box(
                id = id,
                x = o.optDouble("x", 0.0),
                y = o.optDouble("y", 0.0),
                w = o.optDouble("w", 0.0),
                h = o.optDouble("h", 0.0),
                label = o.optString("label", ""),
                enlarge = o.optBoolean("enlarge", false),
                color = color,
                fontSize = fontSize,
            )
            "bracket" -> GelAnnotation.Bracket(
                id = id,
                side = listOf("left", "right", "top", "bottom").firstOrNull { it == o.optStringOrNull("side") } ?: "left",
                start = o.optDouble("start", 0.0),
                end = o.optDouble("end", 0.0),
                label = o.optString("label", ""),
                color = color,
                fontSize = fontSize,
            )
            "sideText" -> GelAnnotation.SideText(
                id = id,
                side = listOf("left", "right", "top", "bottom").firstOrNull { it == o.optStringOrNull("side") } ?: "left",
                pos = o.optDouble("pos", 0.0),
                text = o.optString("text", ""),
                color = color,
                fontSize = fontSize,
            )
            else -> null
        }
    }

    fun fitToJson(f: GelLadderFit?): JSONObject? {
        if (f == null) return null
        return JSONObject().apply {
            put("ladderId", f.ladderId)
            put("laneIds", JSONArray(f.laneIds))
            put("a", f.a)
            put("b", f.b)
            put("c", f.c)
            put("degree", f.degree)
            put("r2", f.r2)
            put("n", f.n)
        }
    }

    fun fitFromJson(o: JSONObject?): GelLadderFit? {
        if (o == null) return null
        val laneIds = mutableListOf<String>()
        o.optJSONArray("laneIds")?.let { arr ->
            for (i in 0 until arr.length()) laneIds.add(arr.getString(i))
        }
        return GelLadderFit(
            ladderId = o.optString("ladderId", ""),
            laneIds = laneIds,
            a = o.optDouble("a", 0.0),
            b = o.optDouble("b", 0.0),
            c = o.optDouble("c", 0.0),
            degree = o.optInt("degree", 1),
            r2 = o.optDouble("r2", 0.0),
            n = o.optInt("n", 0),
        )
    }

    fun exposureFromJson(o: JSONObject): GelExposure = GelExposure(
        id = o.optString("id", newId("exp")),
        name = o.optString("name", ""),
        dataUrl = o.optString("dataUrl", ""),
        width = o.optInt("width", 0),
        height = o.optInt("height", 0),
        mime = o.optString("mime", "image/png"),
        originalBase64 = o.optStringOrNull("originalBase64"),
        sha256 = o.optStringOrNull("sha256"),
    )

    fun exposureToJson(e: GelExposure, includeOriginal: Boolean = true): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.name)
        put("dataUrl", e.dataUrl)
        put("width", e.width)
        put("height", e.height)
        put("mime", e.mime)
        if (includeOriginal) e.originalBase64?.let { put("originalBase64", it) }
        e.sha256?.let { put("sha256", it) }
    }

    fun projectToJson(p: GelProject, includeOriginals: Boolean = true): JSONObject = JSONObject().apply {
        put("version", p.version)
        put("imageName", p.imageName)
        put("exposures", JSONArray().apply { p.exposures.forEach { put(exposureToJson(it, includeOriginals)) } })
        put("activeExposureId", p.activeExposureId)
        put("display", p.display)
        put("rotation", p.rotation)
        put("orient", p.orient)
        put("mirrorH", p.mirrorH)
        put("mirrorV", p.mirrorV)
        put("adjust", JSONObject().apply {
            put("grayscale", p.adjust.grayscale)
            put("invert", p.adjust.invert)
            put("contrast", p.adjust.contrast)
            put("brightness", p.adjust.brightness)
            put("gamma", p.adjust.gamma)
            put("gammaLocked", p.adjust.gammaLocked)
        })
        put("crop", JSONObject().apply {
            put("left", p.crop.left)
            put("top", p.crop.top)
            put("right", p.crop.right)
            put("bottom", p.crop.bottom)
        })
        put("gelType", p.gelType)
        put("detect", GelPresets.toJson(p.detect))
        put("unit", p.unit)
        put("ladderId", p.ladderId)
        put("customLadders", JSONArray().apply { p.customLadders.forEach { put(GelLadders.toJson(it)) } })
        put("favorites", JSONArray(p.favorites))
        put("lanes", JSONArray().apply { p.lanes.forEach { put(laneToJson(it)) } })
        put("groups", JSONArray().apply { p.groups.forEach { put(groupToJson(it)) } })
        put("conditionRows", JSONArray().apply { p.conditionRows.forEach { put(conditionRowToJson(it)) } })
        put("annotations", JSONArray().apply { p.annotations.forEach { put(annotationToJson(it)) } })
        put("layout", JSONObject().apply {
            put("fontFamily", p.layout.fontFamily)
            put("nameFontSize", p.layout.nameFontSize)
            put("labelFontSize", p.layout.labelFontSize)
            put("nameAngle", p.layout.nameAngle)
            put("red", p.layout.red)
            put("conditionsNearestGel", p.layout.conditionsNearestGel)
            put("figureWidthMm", p.layout.figureWidthMm)
            put("groupLabelFit", p.layout.groupLabelFit)
        })
        fitToJson(p.fit)?.let { put("fit", it) }
        put("notes", p.notes)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
    }

    /** Parse a v2 project (defensive; sanitize happens in GelProjectDoc). */
    fun projectFromJson(o: JSONObject): GelProject {
        val p = emptyProject()
        p.version = o.optInt("version", 2)
        p.imageName = o.optString("imageName", "")
        o.optJSONArray("exposures")?.let { arr ->
            for (i in 0 until arr.length()) p.exposures.add(exposureFromJson(arr.getJSONObject(i)))
        }
        p.activeExposureId = o.optString("activeExposureId", "")
        p.display = if (o.optString("display") == "color") "color" else "gray"
        p.rotation = o.optDouble("rotation", 0.0)
        p.orient = o.optInt("orient", 0)
        p.mirrorH = o.optBoolean("mirrorH", false)
        p.mirrorV = o.optBoolean("mirrorV", false)
        o.optJSONObject("adjust")?.let { adj ->
            p.adjust = GelProject.GelAdjust(
                grayscale = adj.optBoolean("grayscale", true),
                invert = adj.optBoolean("invert", false),
                contrast = adj.optDouble("contrast", 1.0),
                brightness = adj.optDouble("brightness", 0.0),
                gamma = adj.optDouble("gamma", 1.0),
                gammaLocked = adj.optBoolean("gammaLocked", true),
            )
        }
        o.optJSONObject("crop")?.let { cr ->
            p.crop = GelProject.GelCrop(
                left = cr.optDouble("left", 0.0),
                top = cr.optDouble("top", 0.0),
                right = cr.optDouble("right", 0.0),
                bottom = cr.optDouble("bottom", 0.0),
            )
        }
        p.gelType = if (o.optString("gelType") == "protein") "protein" else "dna"
        o.optJSONObject("detect")?.let { p.detect = GelPresets.fromJson(it) }
        p.unit = if (o.optString("unit") == "kDa") "kDa" else "bp"
        p.ladderId = o.optString("ladderId", "gr1kb")
        o.optJSONArray("customLadders")?.let { arr ->
            for (i in 0 until arr.length()) p.customLadders.add(GelLadders.fromJson(arr.getJSONObject(i)))
        }
        o.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) p.favorites.add(arr.getString(i))
        }
        o.optJSONArray("lanes")?.let { arr ->
            for (i in 0 until arr.length()) p.lanes.add(laneFromJson(arr.getJSONObject(i)))
        }
        o.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) p.groups.add(groupFromJson(arr.getJSONObject(i)))
        }
        o.optJSONArray("conditionRows")?.let { arr ->
            for (i in 0 until arr.length()) p.conditionRows.add(conditionRowFromJson(arr.getJSONObject(i)))
        }
        o.optJSONArray("annotations")?.let { arr ->
            for (i in 0 until arr.length()) {
                annotationFromJson(arr.getJSONObject(i))?.let { p.annotations.add(it) }
            }
        }
        o.optJSONObject("layout")?.let { l ->
            p.layout = GelLayoutSettings(
                fontFamily = l.optStringOrNull("fontFamily")?.takeIf { listOf("Arial", "Times New Roman", "Helvetica", "Calibri", "Cambria").contains(it) } ?: "Arial",
                nameFontSize = l.optDouble("nameFontSize", 9.0),
                labelFontSize = l.optDouble("labelFontSize", 8.0),
                nameAngle = l.optDouble("nameAngle", 0.0),
                red = l.optBoolean("red", false),
                conditionsNearestGel = l.optBoolean("conditionsNearestGel", false),
                figureWidthMm = l.optDouble("figureWidthMm", 120.0),
                groupLabelFit = listOf("wrap", "shrink", "one").firstOrNull { it == l.optStringOrNull("groupLabelFit") } ?: "wrap",
            )
        }
        p.fit = fitFromJson(o.optJSONObject("fit"))
        p.notes = o.optString("notes", "")
        p.createdAt = o.optString("createdAt", "")
        p.updatedAt = o.optString("updatedAt", "")
        return p
    }

    /** Snapshot for storage: drop the raw originals, which can be huge. */
    fun forStorageJson(p: GelProject): JSONObject = projectToJson(p, includeOriginals = false)
}
