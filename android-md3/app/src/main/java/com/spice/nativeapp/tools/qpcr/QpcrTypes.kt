package com.spice.nativeapp.tools.qpcr

/*
 * qPCR data model. 1:1 port of main's src/lib/tools/qpcr/types.ts —
 * everything is plain JSON so a project can be exported, imported and
 * autosaved without extra serialization code.
 */

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** ColumnKey union: 'well' | 'sample' | 'target' | 'ct' | 'group' | 'replicate' | 'efficiency' | 'tm' | 'quantity' */
object QpcrColumns {
    val COLUMN_KEYS = listOf("well", "sample", "target", "ct", "group", "replicate", "efficiency", "tm", "quantity")
    const val WELL = "well"
    const val SAMPLE = "sample"
    const val TARGET = "target"
    const val CT = "ct"
    const val GROUP = "group"
    const val REPLICATE = "replicate"
    const val EFFICIENCY = "efficiency"
    const val TM = "tm"
    const val QUANTITY = "quantity"
}

/** ColumnMapping: Record<ColumnKey, Int>（-1 = 未映射）。 */
class QpcrColumnMapping {
    var well: Int = -1
    var sample: Int = -1
    var target: Int = -1
    var ct: Int = -1
    var group: Int = -1
    var replicate: Int = -1
    var efficiency: Int = -1
    var tm: Int = -1
    var quantity: Int = -1

    fun get(key: String): Int = when (key) {
        QpcrColumns.WELL -> well
        QpcrColumns.SAMPLE -> sample
        QpcrColumns.TARGET -> target
        QpcrColumns.CT -> ct
        QpcrColumns.GROUP -> group
        QpcrColumns.REPLICATE -> replicate
        QpcrColumns.EFFICIENCY -> efficiency
        QpcrColumns.TM -> tm
        else -> quantity
    }

    fun set(key: String, value: Int) {
        when (key) {
            QpcrColumns.WELL -> well = value
            QpcrColumns.SAMPLE -> sample = value
            QpcrColumns.TARGET -> target = value
            QpcrColumns.CT -> ct = value
            QpcrColumns.GROUP -> group = value
            QpcrColumns.REPLICATE -> replicate = value
            QpcrColumns.EFFICIENCY -> efficiency = value
            QpcrColumns.TM -> tm = value
            else -> quantity = value
        }
    }

    fun copyFrom(other: QpcrColumnMapping) {
        QpcrColumns.COLUMN_KEYS.forEach { set(it, other.get(it)) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        QpcrColumns.COLUMN_KEYS.forEach { put(it, get(it)) }
    }

    companion object {
        fun fromJson(raw: Any?): QpcrColumnMapping {
            val out = QpcrColumnMapping()
            if (raw is JSONObject) {
                QpcrColumns.COLUMN_KEYS.forEach { key ->
                    val v = raw.opt(key)
                    if (v is Int) out.set(key, v)
                }
            }
            return out
        }
    }
}

class QpcrWell(
    var id: String = "",
    /** Zero-based data row index (the header is not counted). */
    var row: Int = 0,
    var well: String = "",
    var sample: String = "",
    var target: String = "",
    var group: String = "",
    var replicate: Double? = null,
    var ct: Double? = null,
    var tm: Double? = null,
    var efficiency: Double? = null,
    /** Known standard quantity. Wells with a value are treated as standards. */
    var quantity: Double? = null,
    /** User-controlled inclusion flag from the raw data table. */
    var include: Boolean = true,
)

class QpcrSettings(
    var highCt: Double = 35.0,
    var sdLimit: Double = 0.5,
    var outlierLimit: Double = 0.5,
    var excludeOutliers: Boolean = false,
    var efficiencyCorrection: Boolean = false,
    var defaultEfficiencyPct: Double = 100.0,
    var quantityIsLog10: Boolean = false,
    var showHeatmap: Boolean = false,
    /** Legacy single reference; kept in sync with the first reference target. */
    var referenceTarget: String = "",
    /** One or more reference targets; their Ct geometric mean is the reference. */
    var referenceTargets: MutableList<String> = mutableListOf(),
    var controlGroup: String = "",
    var controlSample: String = "",
)

class QpcrProject(
    var createdAt: String = "",
    var updatedAt: String = "",
    var fileName: String = "",
    var rawText: String = "",
    var mapping: QpcrColumnMapping = QpcrColumnMapping(),
    var wells: MutableList<QpcrWell> = mutableListOf(),
    var settings: QpcrSettings = QpcrSettings(),
    /** 'ddct' | 'standard' */
    var mode: String = "ddct",
)

fun nowIso(): String = Instant.now().toString()

fun emptyMapping(): QpcrColumnMapping = QpcrColumnMapping()

fun defaultSettings(): QpcrSettings = QpcrSettings()

/** Clamp numeric settings and normalize optional booleans/strings (1:1 sanitizeSettings). */
fun sanitizeSettings(raw: Any?): QpcrSettings {
    val settings = raw as? JSONObject ?: return defaultSettings()
    val number = { v: Any?, lo: Double, hi: Double, fallback: Double ->
        val n = if (v is Number && v.toDouble().isFinite()) v.toDouble() else fallback
        n.coerceIn(lo, hi)
    }
    val text = { v: Any? -> if (v is String) v else "" }
    val legacy = text(settings.optString("referenceTarget", "")).trim()
    val list = ArrayList<String>()
    val rawList = settings.optJSONArray("referenceTargets")
    if (rawList != null) {
        for (i in 0 until rawList.length()) {
            val v = rawList.opt(i)
            if (v is String && v.trim().isNotEmpty()) list.add(v.trim())
        }
    }
    val source = if (list.isNotEmpty()) list else if (legacy.isNotEmpty()) arrayListOf(legacy) else emptyList()
    val unique = ArrayList<String>()
    for (value in source) {
        if (unique.none { it.lowercase() == value.lowercase() }) unique.add(value)
    }
    return QpcrSettings(
        highCt = number(settings.opt("highCt"), 10.0, 45.0, 35.0),
        sdLimit = number(settings.opt("sdLimit"), 0.0, 5.0, 0.5),
        outlierLimit = number(settings.opt("outlierLimit"), 0.0, 5.0, 0.5),
        excludeOutliers = settings.optBoolean("excludeOutliers", false),
        efficiencyCorrection = settings.optBoolean("efficiencyCorrection", false),
        defaultEfficiencyPct = number(settings.opt("defaultEfficiencyPct"), 1.0, 200.0, 100.0),
        quantityIsLog10 = settings.optBoolean("quantityIsLog10", false),
        showHeatmap = settings.optBoolean("showHeatmap", false),
        referenceTarget = unique.firstOrNull() ?: legacy,
        referenceTargets = unique,
        controlGroup = text(settings.opt("controlGroup")),
        controlSample = text(settings.opt("controlSample")),
    )
}

fun emptyProject(): QpcrProject {
    val now = nowIso()
    return QpcrProject(createdAt = now, updatedAt = now)
}

/** 1:1 projectToJson: plain JSON with the original field set. */
fun projectToJson(project: QpcrProject): String = JSONObject().apply {
    put("format", "SPICE_QPCR")
    put("version", 1)
    put("createdAt", project.createdAt)
    put("updatedAt", project.updatedAt)
    put("fileName", project.fileName)
    put("rawText", project.rawText)
    put("mapping", project.mapping.toJson())
    put("wells", JSONArray().apply {
        project.wells.forEach { w ->
            put(JSONObject().apply {
                put("id", w.id)
                put("row", w.row)
                put("well", w.well)
                put("sample", w.sample)
                put("target", w.target)
                put("group", w.group)
                put("replicate", w.replicate ?: JSONObject.NULL)
                put("ct", w.ct ?: JSONObject.NULL)
                put("tm", w.tm ?: JSONObject.NULL)
                put("efficiency", w.efficiency ?: JSONObject.NULL)
                put("quantity", w.quantity ?: JSONObject.NULL)
                put("include", w.include)
            })
        }
    })
    put("settings", JSONObject().apply {
        put("highCt", project.settings.highCt)
        put("sdLimit", project.settings.sdLimit)
        put("outlierLimit", project.settings.outlierLimit)
        put("excludeOutliers", project.settings.excludeOutliers)
        put("efficiencyCorrection", project.settings.efficiencyCorrection)
        put("defaultEfficiencyPct", project.settings.defaultEfficiencyPct)
        put("quantityIsLog10", project.settings.quantityIsLog10)
        put("showHeatmap", project.settings.showHeatmap)
        put("referenceTarget", project.settings.referenceTarget)
        put("referenceTargets", JSONArray(project.settings.referenceTargets))
        put("controlGroup", project.settings.controlGroup)
        put("controlSample", project.settings.controlSample)
    })
    put("mode", project.mode)
}.toString(2)

/** 1:1 normalizeWell. */
private fun normalizeWell(raw: Any?, index: Int): QpcrWell? {
    if (raw !is JSONObject) return null
    val num = { v: Any? -> if (v is Number && v.toDouble().isFinite()) v.toDouble() else null }
    val id = raw.optString("id", "")
    return QpcrWell(
        id = if (id.isNotEmpty()) id else "r$index",
        row = if (raw.opt("row") is Number) (raw.opt("row") as Number).toInt() else index,
        well = if (raw.opt("well") is String) raw.optString("well") else "",
        sample = if (raw.opt("sample") is String) raw.optString("sample") else "",
        target = if (raw.opt("target") is String) raw.optString("target") else "",
        group = if (raw.opt("group") is String) raw.optString("group") else "",
        replicate = num(raw.opt("replicate")),
        ct = num(raw.opt("ct")),
        tm = num(raw.opt("tm")),
        efficiency = num(raw.opt("efficiency")),
        quantity = num(raw.opt("quantity")),
        include = raw.optBoolean("include", true),
    )
}

/** Validate an imported project and fill missing optional fields (1:1 normalizeProject). */
fun normalizeProject(input: Any?): QpcrProject? {
    if (input !is JSONObject) return null
    if (input.optString("format") != "SPICE_QPCR") return null
    if (input.optInt("version", -1) != 1) return null
    val rawText = input.opt("rawText")
    val wellsRaw = input.optJSONArray("wells")
    if (rawText !is String || wellsRaw == null) return null
    val base = emptyProject()
    val seenIds = mutableSetOf<String>()
    val wells = ArrayList<QpcrWell>()
    for (i in 0 until wellsRaw.length()) {
        val well = normalizeWell(wellsRaw.opt(i), i) ?: continue
        if (well.id in seenIds) well.id = "r$i-${seenIds.size}"
        seenIds.add(well.id)
        wells.add(well)
    }
    val mergedSettings = JSONObject()
    // {...defaultSettings(), ...(raw.settings ?? {})} — 主对象覆盖默认键
    val defaults = JSONObject().apply {
        val d = defaultSettings()
        put("highCt", d.highCt); put("sdLimit", d.sdLimit); put("outlierLimit", d.outlierLimit)
        put("excludeOutliers", d.excludeOutliers); put("efficiencyCorrection", d.efficiencyCorrection)
        put("defaultEfficiencyPct", d.defaultEfficiencyPct); put("quantityIsLog10", d.quantityIsLog10)
        put("showHeatmap", d.showHeatmap); put("referenceTarget", d.referenceTarget)
        put("referenceTargets", JSONArray(d.referenceTargets))
        put("controlGroup", d.controlGroup); put("controlSample", d.controlSample)
    }
    val rawSettings = input.optJSONObject("settings")
    if (rawSettings != null) {
        for (k in rawSettings.keys()) defaults.put(k, rawSettings.opt(k))
    }
    val mapping = QpcrColumnMapping()
    input.optJSONObject("mapping")?.let { raw -> QpcrColumns.COLUMN_KEYS.forEach { k -> mapping.set(k, raw.optInt(k, -1)) } }
    return QpcrProject(
        createdAt = if (input.opt("createdAt") is String) input.optString("createdAt") else base.createdAt,
        updatedAt = if (input.opt("updatedAt") is String) input.optString("updatedAt") else base.updatedAt,
        fileName = if (input.opt("fileName") is String) input.optString("fileName") else "",
        rawText = rawText,
        mapping = mapping,
        settings = sanitizeSettings(mergedSettings.put("merged", defaults).opt("merged")),
        mode = if (input.optString("mode") == "standard") "standard" else "ddct",
        wells = wells,
    )
}

/** 入口别名，对应 main 的 sanitizeQpcrProject。 */
fun sanitizeQpcrProject(input: Any?): QpcrProject? = normalizeProject(input)

fun projectFromJson(text: String): QpcrProject? = try {
    normalizeProject(JSONObject(text))
} catch (e: Exception) {
    null
}
