package com.spice.nativeapp.tools.plate

// Shared types and normalization helpers for the plate layout tool.
// 1:1 port of src/lib/tools/plate/types.ts — everything is plain data so
// layouts round-trip through JSON, local persistence and exported CSV.

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

typealias PlateFormat = Int

val PLATE_FORMATS: List<PlateFormat> = listOf(6, 12, 24, 48, 96, 384)

data class PlateGeometry(
    val format: PlateFormat,
    val rows: Int,
    val cols: Int,
) {
    val total: Int get() = rows * cols
}

private val GEOMETRY: Map<PlateFormat, Pair<Int, Int>> = mapOf(
    6 to (2 to 3),
    12 to (3 to 4),
    24 to (4 to 6),
    48 to (6 to 8),
    96 to (8 to 12),
    384 to (16 to 24),
)

fun isPlateFormat(value: Int): Boolean = value in PLATE_FORMATS

fun formatGeometry(format: PlateFormat): PlateGeometry {
    val (rows, cols) = GEOMETRY[format] ?: (8 to 12)
    return PlateGeometry(format, rows, cols)
}

/** Row labels: A..Z, then AA, AB and so on. */
fun rowLabel(row: Int): String {
    var n = row
    val sb = StringBuilder()
    do {
        sb.insert(0, ('A' + (n % 26)))
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

fun wellId(row: Int, col: Int): String = "${rowLabel(row)}${col + 1}"

fun isEdgeWell(row: Int, col: Int, rows: Int, cols: Int): Boolean =
    row == 0 || col == 0 || row == rows - 1 || col == cols - 1

data class WellCell(
    val id: String,
    val row: Int,
    val col: Int,
    val edge: Boolean,
)

fun buildWellCells(geometry: PlateGeometry): List<WellCell> {
    val cells = ArrayList<WellCell>(geometry.total)
    for (row in 0 until geometry.rows) {
        for (col in 0 until geometry.cols) {
            cells.add(WellCell(wellId(row, col), row, col, isEdgeWell(row, col, geometry.rows, geometry.cols)))
        }
    }
    return cells
}

fun wellCellMap(geometry: PlateGeometry): Map<String, WellCell> =
    buildWellCells(geometry).associateBy { it.id }

/** sample | control | blank | reserved（存储类型，空孔不入表）。 */
typealias StoredWellType = String

data class WellAssignment(
    val well: String,
    val row: Int,
    val col: Int,
    val type: StoredWellType,
    val sampleId: String,
    val sampleName: String,
    val group: String,
    val replicate: Int,
    val color: String,
    val manual: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("well", well)
        put("row", row)
        put("col", col)
        put("type", type)
        put("sampleId", sampleId)
        put("sampleName", sampleName)
        put("group", group)
        put("replicate", replicate)
        put("color", color)
        put("manual", manual)
    }

    companion object {
        fun fromJson(obj: JSONObject): WellAssignment = WellAssignment(
            well = obj.optString("well", ""),
            row = obj.optInt("row", 0),
            col = obj.optInt("col", 0),
            type = obj.optString("type", ""),
            sampleId = obj.optString("sampleId", ""),
            sampleName = obj.optString("sampleName", ""),
            group = obj.optString("group", ""),
            replicate = obj.optInt("replicate", 0),
            color = obj.optString("color", ""),
            manual = obj.optBoolean("manual", false),
        )
    }
}

data class SampleRow(
    val id: String,
    var name: String,
    var group: String,
    var color: String,
    /** Null means the global replicate rule is used. */
    var replicates: Int?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("group", group)
        put("color", color)
        if (replicates !== null) put("replicates", replicates!!) else put("replicates", JSONObject.NULL)
    }
}

typealias GroupLayout = String

data class PlateRules(
    var replicates: Int,
    var controls: Int,
    var blanks: Int,
    var reserveEdge: Boolean,
    var avoidEdge: Boolean,
    var keepTogether: Boolean,
    var groupLayout: GroupLayout,
    var groupColumns: Int,
    var groupRows: Int,
    var randomize: Boolean,
    var seed: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("replicates", replicates)
        put("controls", controls)
        put("blanks", blanks)
        put("reserveEdge", reserveEdge)
        put("avoidEdge", avoidEdge)
        put("keepTogether", keepTogether)
        put("groupLayout", groupLayout)
        put("groupColumns", groupColumns)
        put("groupRows", groupRows)
        put("randomize", randomize)
        put("seed", seed)
    }
}

const val MIN_REPLICATES = 1
const val MAX_REPLICATES = 12
const val MAX_SEED = 2147483647

fun defaultRules(): PlateRules = PlateRules(
    replicates = 3,
    controls = 2,
    blanks = 2,
    reserveEdge = false,
    avoidEdge = true,
    keepTogether = true,
    groupLayout = "auto",
    groupColumns = 0,
    groupRows = 0,
    randomize = true,
    seed = 20261102,
)

data class PlateProjectFile(
    val version: Int,
    val kind: String,
    var title: String,
    var format: PlateFormat,
    var samples: MutableList<SampleRow>,
    var rules: PlateRules,
    var assignments: MutableList<WellAssignment>,
    var colorBy: String,
    var showLabels: Boolean,
    var updatedAt: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("kind", kind)
        put("title", title)
        put("format", format)
        put("samples", JSONArray().apply { samples.forEach { put(it.toJson()) } })
        put("rules", rules.toJson())
        put("assignments", JSONArray().apply { assignments.forEach { put(it.toJson()) } })
        put("colorBy", colorBy)
        put("showLabels", showLabels)
        put("updatedAt", updatedAt)
    }
}

typealias IssueSeverity = String

data class LayoutIssue(
    val key: String,
    val severity: IssueSeverity,
    val params: Map<String, Any> = emptyMap(),
)

data class UnplacedSample(
    val sampleId: String,
    val name: String,
    val requested: Int,
    val placed: Int,
    val missing: Int,
)

data class SampleDraft(
    val name: String,
    val group: String,
    val color: String,
    val replicates: Int?,
)

data class SampleParseResult(
    val rows: List<SampleDraft>,
    val skipped: Int,
    val delimiter: String,
    val hadHeader: Boolean,
)

fun clampInt(value: Double, min: Int, max: Int, fallback: Int): Int {
    if (!value.isFinite()) return fallback
    return minOf(max, maxOf(min, Math.round(value).toInt()))
}

fun normalizeColor(raw: String?): String {
    if (raw === null) return ""
    val value = raw.trim().lowercase()
    if (Regex("^#[0-9a-f]{6}$").matches(value)) return value
    if (Regex("^#[0-9a-f]{3}$").matches(value)) {
        return "#" + value[1] + value[1] + value[2] + value[2] + value[3] + value[3]
    }
    return ""
}

fun requiredReplicates(sample: SampleRow, rules: PlateRules): Int {
    val value = sample.replicates ?: rules.replicates
    return clampInt(value.toDouble(), MIN_REPLICATES, MAX_REPLICATES, rules.replicates)
}

private fun normalizeSample(raw: Any?, index: Int): SampleRow? {
    if (raw !is JSONObject) return null
    val name = if (raw.has("name") && raw.opt("name") is String) raw.optString("name").trim() else ""
    val group = if (raw.has("group") && raw.opt("group") is String) raw.optString("group").trim() else ""
    val color = normalizeColor(if (raw.opt("color") is String) raw.optString("color") else null)
    val replicates = if (raw.opt("replicates") is Number && raw.optDouble("replicates").isFinite()) {
        clampInt(raw.optDouble("replicates"), MIN_REPLICATES, MAX_REPLICATES, 1)
    } else null
    val id = if (raw.has("id") && raw.optString("id", "").isNotEmpty()) raw.optString("id") else "smp_import_${index + 1}"
    return SampleRow(id, name, group, color, replicates)
}

private fun normalizeRules(raw: Any?, geometry: PlateGeometry): PlateRules {
    val base = defaultRules()
    if (raw !is JSONObject) return base
    fun numberField(key: String, min: Int, max: Int, fallback: Int): Int =
        if (raw.opt(key) is Number) clampInt(raw.optDouble(key), min, max, fallback) else fallback
    fun boolField(key: String, fallback: Boolean): Boolean =
        if (raw.opt(key) is Boolean) raw.optBoolean(key) else fallback
    val layout = raw.optString("groupLayout")
    val groupLayout: GroupLayout = when (layout) {
        "auto", "row", "column", "block" -> layout
        else -> base.groupLayout
    }
    return PlateRules(
        replicates = numberField("replicates", MIN_REPLICATES, MAX_REPLICATES, base.replicates),
        controls = numberField("controls", 0, geometry.total, base.controls),
        blanks = numberField("blanks", 0, geometry.total, base.blanks),
        reserveEdge = boolField("reserveEdge", base.reserveEdge),
        avoidEdge = boolField("avoidEdge", base.avoidEdge),
        keepTogether = boolField("keepTogether", base.keepTogether),
        groupLayout = groupLayout,
        groupColumns = numberField("groupColumns", 0, geometry.cols, base.groupColumns),
        groupRows = numberField("groupRows", 0, geometry.rows, base.groupRows),
        randomize = boolField("randomize", base.randomize),
        seed = numberField("seed", 0, MAX_SEED, base.seed),
    )
}

data class NormalizedProject(
    val project: PlateProjectFile?,
    val duplicateWells: Int,
    val droppedAssignments: Int,
)

/**
 * Accepts a saved project or a partial snapshot and returns a safe project.
 * Invalid wells are dropped and counted so the UI can report them instead of
 * silently discarding imported data.
 */
fun normalizeProject(raw: Any?): NormalizedProject {
    var duplicateWells = 0
    var droppedAssignments = 0
    if (raw !is JSONObject) return NormalizedProject(null, 0, 0)
    val obj = raw
    val format = if (isPlateFormat(obj.optInt("format", -1))) obj.optInt("format") else 96
    val geometry = formatGeometry(format)
    val byWell = wellCellMap(geometry)

    val samples = ArrayList<SampleRow>()
    val seenIds = HashSet<String>()
    if (obj.opt("samples") is JSONArray) {
        val arr = obj.optJSONArray("samples")!!
        for (index in 0 until arr.length()) {
            val sample = normalizeSample(arr.opt(index), index) ?: continue
            var id = sample.id
            var suffix = 1
            while (id in seenIds) id = "${sample.id}_${suffix++}"
            seenIds.add(id)
            samples.add(sample.copy(id = id))
        }
    }

    val rules = normalizeRules(obj.opt("rules"), geometry)

    val rawAssignments: List<Any?> = when (val a = obj.opt("assignments")) {
        is JSONArray -> (0 until a.length()).map { a.opt(it) }
        is JSONObject -> { val keys = ArrayList<String>(); val kit = a.keys(); while (kit.hasNext()) keys.add(kit.next()); keys.map { a.opt(it) } }
        else -> emptyList()
    }

    val assignments = ArrayList<WellAssignment>()
    val seenWells = HashSet<String>()
    for (entry in rawAssignments) {
        if (entry !is JSONObject) {
            droppedAssignments++
            continue
        }
        val value = entry
        val id = if (value.opt("well") is String) value.optString("well").uppercase() else ""
        val cell = byWell[id]
        if (cell == null) {
            droppedAssignments++
            continue
        }
        if (id in seenWells) {
            duplicateWells++
            continue
        }
        val type = value.optString("type")
        if (type !in listOf("sample", "control", "blank", "reserved")) {
            droppedAssignments++
            continue
        }
        seenWells.add(id)
        assignments.add(
            WellAssignment(
                well = id,
                row = cell.row,
                col = cell.col,
                type = type,
                sampleId = if (value.opt("sampleId") is String) value.optString("sampleId") else "",
                sampleName = if (value.opt("sampleName") is String) value.optString("sampleName") else "",
                group = if (value.opt("group") is String) value.optString("group") else "",
                replicate = if (value.opt("replicate") is Number) clampInt(value.optDouble("replicate"), 0, MAX_REPLICATES, 0) else 0,
                color = normalizeColor(if (value.opt("color") is String) value.optString("color") else null),
                manual = value.opt("manual") == true,
            )
        )
    }
    assignments.sortWith(compareBy({ it.row }, { it.col }))

    val colorBy = if (obj.optString("colorBy") == "sample") "sample" else "group"
    val title = if (obj.opt("title") is String) obj.optString("title") else ""
    val showLabels = if (obj.opt("showLabels") is Boolean) obj.optBoolean("showLabels") else format != 384
    val updatedAt = if (obj.opt("updatedAt") is String) obj.optString("updatedAt") else nowIso()

    return NormalizedProject(
        PlateProjectFile(1, "spice-plate", title, format, samples, rules, assignments, colorBy, showLabels, updatedAt),
        duplicateWells,
        droppedAssignments,
    )
}

fun assignmentsToMap(assignments: List<WellAssignment>): MutableMap<String, WellAssignment> {
    val map = LinkedHashMap<String, WellAssignment>()
    for (assignment in assignments) map[assignment.well] = assignment
    return map
}

/** JS `new Date().toISOString()` equivalent (UTC, millisecond precision). */
fun nowIso(): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        cal.get(Calendar.MILLISECOND),
    )
}
