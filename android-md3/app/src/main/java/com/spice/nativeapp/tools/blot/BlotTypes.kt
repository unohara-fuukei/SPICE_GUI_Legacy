package com.spice.nativeapp.tools.blot

/*
 * Western blot densitometry project model. 1:1 port of main's
 * src/lib/tools/blot/types.ts — coordinates are normalised to the processed
 * (orientation + fine-rotation applied) image, independent of crop.
 */

import org.json.JSONArray
import org.json.JSONObject

class BlotAdjust(
    var display: String = "gray", // "gray" | "color"
    var contrast: Double = 1.0,
    var brightness: Double = 0.0,
    var gamma: Double = 1.0,
    var invert: Boolean = false,
)

class BlotCrop(
    var left: Double = 0.0,
    var top: Double = 0.0,
    var right: Double = 0.0,
    var bottom: Double = 0.0,
)

data class BlotBand(
    val id: String,
    /** Vertical centre as a fraction of the processed image height. */
    var y: Double,
    /** ROI height as a fraction of the processed image height. */
    var height: Double,
    /** ROI width as a fraction of the processed image width. */
    var width: Double,
    var manual: Boolean = false,
    var excluded: Boolean = false,
)

class BlotLane(
    val id: String,
    /** Lane centre as a fraction of the processed image width. */
    var x: Double,
    /** Lane width as a fraction of the processed image width. */
    var width: Double,
    var name: String,
    var sample: String,
    var group: String,
    var excluded: Boolean = false,
    var bands: MutableList<BlotBand>,
)

class BlotNormalization(
    var mode: String = "none", // "none" | "loading" | "total"
    var loadingLaneId: String? = null,
    var controlGroup: String = "",
)

class BlotProject(
    val version: Int = 1,
    var createdAt: String = "",
    var updatedAt: String = "",
    var imageName: String = "",
    /** Normalised PNG data URL of the imported image. */
    var imageDataUrl: String = "",
    var sourceWidth: Int = 0,
    var sourceHeight: Int = 0,
    val adjust: BlotAdjust = BlotAdjust(),
    /** Clockwise quarter turns, 0..3. */
    var orient: Int = 0,
    /** Fine rotation in degrees, -15..15. */
    var rotation: Double = 0.0,
    val crop: BlotCrop = BlotCrop(),
    var lanes: MutableList<BlotLane> = mutableListOf(),
    val normalization: BlotNormalization = BlotNormalization(),
    var backgroundMode: String = "ring", // "ring" | "lane" | "global" | "manual"
    /** Manual background level in signal units (0..1). */
    var manualBackground: Double = 0.0,
    var chartMetric: String = "normalized", // "normalized" | "fold"
    var title: String = "",
    var chartTitle: String = "",
    var exportDpi: Int = 300, // 300 | 600
    var showRoi: Boolean = true,
    var showLaneLabels: Boolean = true,
)

private var idCounter = 0L

fun newId(prefix: String): String {
    idCounter += 1
    val rand = (Math.random() * 46656).toInt().toString(36).padStart(3, '0')
    val time = (System.currentTimeMillis() + idCounter).toString(36).takeLast(4)
    return "${prefix}_${rand}${time}"
}

fun nowIso(): String = java.time.Instant.now().toString()

fun emptyProject(): BlotProject = BlotProject(
    createdAt = nowIso(),
    updatedAt = nowIso(),
)

private fun finiteNumber(value: Double?): Double = when {
    value == null || !value.isFinite() -> 0.0
    else -> value
}

private fun finiteNumberInt(value: Int?): Int = value ?: 0

fun clamp(value: Double, min: Double, max: Double): Double =
    if (value < min) min else if (value > max) max else value

// ---------------------------------------------------------------- JSON round-trip

fun projectToJson(project: BlotProject): String {
    val o = JSONObject()
    o.put("version", project.version)
    o.put("createdAt", project.createdAt)
    o.put("updatedAt", project.updatedAt)
    o.put("imageName", project.imageName)
    o.put("imageDataUrl", project.imageDataUrl)
    o.put("sourceWidth", project.sourceWidth)
    o.put("sourceHeight", project.sourceHeight)
    val a = JSONObject()
    a.put("display", project.adjust.display)
    a.put("contrast", project.adjust.contrast)
    a.put("brightness", project.adjust.brightness)
    a.put("gamma", project.adjust.gamma)
    a.put("invert", project.adjust.invert)
    o.put("adjust", a)
    o.put("orient", project.orient)
    o.put("rotation", project.rotation)
    val c = JSONObject()
    c.put("left", project.crop.left); c.put("top", project.crop.top)
    c.put("right", project.crop.right); c.put("bottom", project.crop.bottom)
    o.put("crop", c)
    val lanes = JSONArray()
    for (lane in project.lanes) {
        val l = JSONObject()
        l.put("id", lane.id); l.put("x", lane.x); l.put("width", lane.width)
        l.put("name", lane.name); l.put("sample", lane.sample); l.put("group", lane.group)
        l.put("excluded", lane.excluded)
        val bands = JSONArray()
        for (band in lane.bands) {
            val b = JSONObject()
            b.put("id", band.id); b.put("y", band.y); b.put("height", band.height)
            b.put("width", band.width); b.put("manual", band.manual); b.put("excluded", band.excluded)
            bands.put(b)
        }
        l.put("bands", bands)
        lanes.put(l)
    }
    o.put("lanes", lanes)
    val n = JSONObject()
    n.put("mode", project.normalization.mode)
    if (project.normalization.loadingLaneId != null) n.put("loadingLaneId", project.normalization.loadingLaneId)
    n.put("controlGroup", project.normalization.controlGroup)
    o.put("normalization", n)
    o.put("backgroundMode", project.backgroundMode)
    o.put("manualBackground", project.manualBackground)
    o.put("chartMetric", project.chartMetric)
    o.put("title", project.title)
    o.put("chartTitle", project.chartTitle)
    o.put("exportDpi", project.exportDpi)
    o.put("showRoi", project.showRoi)
    o.put("showLaneLabels", project.showLaneLabels)
    return o.toString()
}

/** Accepts older or hand-edited projects and repairs missing fields (1:1 sanitizeProject). */
fun sanitizeProject(input: Any?): BlotProject? {
    if (input == null) return null
    val raw = try {
        if (input is String) JSONObject(input) else input as? JSONObject ?: return null
    } catch (_: Exception) {
        return null
    }
    if (!raw.has("version") || raw.optInt("version", -1) != 1) return null
    val imageDataUrl = raw.optString("imageDataUrl")
    if (imageDataUrl.isEmpty()) return null
    val base = emptyProject()
    val adjust = raw.optJSONObject("adjust") ?: JSONObject()
    val crop = raw.optJSONObject("crop") ?: JSONObject()
    val normalization = raw.optJSONObject("normalization") ?: JSONObject()
    val lanes = mutableListOf<BlotLane>()
    val rawLanes = raw.optJSONArray("lanes") ?: JSONArray()
    for (i in 0 until rawLanes.length()) {
        val lane = rawLanes.optJSONObject(i) ?: continue
        val bands = mutableListOf<BlotBand>()
        val rawBands = lane.optJSONArray("bands") ?: JSONArray()
        for (j in 0 until rawBands.length()) {
            val band = rawBands.optJSONObject(j) ?: continue
            val laneWidth = lane.optDouble("width", 0.05)
            bands.add(
                BlotBand(
                    id = if (band.optString("id").isNotEmpty()) band.optString("id") else newId("band"),
                    y = clamp(finiteNumber(band.optDouble("y", 0.5)), 0.0, 1.0),
                    height = clamp(finiteNumber(band.optDouble("height", 0.035)), 0.004, 0.5),
                    width = clamp(finiteNumber(band.optDouble("width", laneWidth)), 0.004, 0.8),
                    manual = band.optBoolean("manual", false),
                    excluded = band.optBoolean("excluded", false),
                )
            )
        }
        lanes.add(
            BlotLane(
                id = if (lane.optString("id").isNotEmpty()) lane.optString("id") else newId("lane"),
                x = clamp(finiteNumber(lane.optDouble("x", 0.5)), 0.0, 1.0),
                width = clamp(finiteNumber(lane.optDouble("width", 0.05)), 0.005, 0.5),
                name = lane.optString("name").ifEmpty { "${i + 1}" },
                sample = lane.optString("sample"),
                group = lane.optString("group"),
                excluded = lane.optBoolean("excluded", false),
                bands = bands,
            )
        )
    }
    val p = BlotProject(
        createdAt = raw.optString("createdAt").ifEmpty { base.createdAt },
        updatedAt = raw.optString("updatedAt").ifEmpty { base.updatedAt },
        imageName = raw.optString("imageName"),
        imageDataUrl = imageDataUrl,
        sourceWidth = finiteNumberInt(raw.optInt("sourceWidth", 0)),
        sourceHeight = finiteNumberInt(raw.optInt("sourceHeight", 0)),
        orient = ((finiteNumber(raw.optDouble("orient").toDouble()).toInt() % 4) + 4) % 4,
        rotation = clamp(finiteNumber(raw.optDouble("rotation", 0.0)), -15.0, 15.0),
        backgroundMode = when (raw.optString("backgroundMode")) {
            "lane", "global", "manual" -> raw.optString("backgroundMode")
            else -> "ring"
        },
        manualBackground = clamp(finiteNumber(raw.optDouble("manualBackground", 0.0)), 0.0, 1.0),
        chartMetric = if (raw.optString("chartMetric") == "fold") "fold" else "normalized",
        title = raw.optString("title"),
        chartTitle = raw.optString("chartTitle"),
        exportDpi = if (raw.optInt("exportDpi", 300) == 600) 600 else 300,
        showRoi = raw.optBoolean("showRoi", true),
        showLaneLabels = raw.optBoolean("showLaneLabels", true),
    )
    p.adjust.display = if (adjust.optString("display") == "color") "color" else "gray"
    p.adjust.contrast = clamp(finiteNumber(adjust.optDouble("contrast", 1.0)), 0.1, 5.0)
    p.adjust.brightness = clamp(finiteNumber(adjust.optDouble("brightness", 0.0)), -1.0, 1.0)
    p.adjust.gamma = clamp(finiteNumber(adjust.optDouble("gamma", 1.0)), 0.1, 5.0)
    p.adjust.invert = adjust.optBoolean("invert", false)
    p.crop.left = clamp(finiteNumber(crop.optDouble("left", 0.0)), 0.0, 0.9)
    p.crop.top = clamp(finiteNumber(crop.optDouble("top", 0.0)), 0.0, 0.9)
    p.crop.right = clamp(finiteNumber(crop.optDouble("right", 0.0)), 0.0, 0.9)
    p.crop.bottom = clamp(finiteNumber(crop.optDouble("bottom", 0.0)), 0.0, 0.9)
    p.lanes.addAll(lanes)
    p.normalization.mode = when (normalization.optString("mode")) {
        "loading", "total" -> normalization.optString("mode")
        else -> "none"
    }
    p.normalization.loadingLaneId = normalization.optString("loadingLaneId").ifEmpty { null }
    p.normalization.controlGroup = normalization.optString("controlGroup")
    return p
}

/** Project snapshot without the image payload, used by undo/redo. */
fun snapshotProject(project: BlotProject): String {
    val o = JSONObject(projectToJson(project))
    o.remove("imageDataUrl")
    return o.toString()
}

fun restoreSnapshot(snapshot: String, imageDataUrl: String): BlotProject? {
    return try {
        sanitizeProject(JSONObject(snapshot).put("imageDataUrl", imageDataUrl))
    } catch (_: Exception) {
        null
    }
}
