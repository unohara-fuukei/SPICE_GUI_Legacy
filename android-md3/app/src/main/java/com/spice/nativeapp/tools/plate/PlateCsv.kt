package com.spice.nativeapp.tools.plate

// CSV/TSV helpers for sample import and plate map export.
// 1:1 port of src/lib/tools/plate/csv.ts.

private fun splitLine(line: String, delimiter: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (quoted) {
            if (ch == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                current.append(ch)
            }
            i++
            continue
        }
        if (ch == '"') {
            quoted = true
        } else if (ch.toString() == delimiter) {
            out.add(current.toString().trim())
            current.setLength(0)
        } else {
            current.append(ch)
        }
        i++
    }
    out.add(current.toString().trim())
    return out
}

private fun detectDelimiter(line: String): String {
    var tabs = 0
    var commas = 0
    var semicolons = 0
    var quoted = false
    for (ch in line) {
        if (ch == '"') quoted = !quoted
        if (quoted) continue
        if (ch == '\t') tabs++
        else if (ch == ',') commas++
        else if (ch == ';') semicolons++
    }
    if (tabs >= commas && tabs >= semicolons && tabs > 0) return "\t"
    if (semicolons > commas) return ";"
    return ","
}

private val NAME_HEADERS = setOf(
    "sample", "samples", "sample id", "sampleid", "sample name", "samplename",
    "sample_id", "name", "id",
)

private val GROUP_HEADERS = setOf("group", "condition", "treatment", "category", "class")

private val COLOR_HEADERS = setOf("color", "colour", "hex", "fill")

private val REPLICATE_HEADERS = setOf("replicate", "replicates", "reps", "rep", "n", "count")

private fun headerIndex(cells: List<String>, headers: Set<String>): Int {
    for (i in cells.indices) {
        if (cells[i].lowercase() in headers) return i
    }
    return -1
}

private fun parseReplicates(raw: String): Int? {
    if (raw.trim().isEmpty()) return null
    val value = raw.trim().toDoubleOrNull() ?: return null
    if (!value.isFinite()) return null
    return clampInt(value, 1, 12, 1)
}

/** Parses pasted or uploaded CSV/TSV sample tables. */
fun parseSampleTable(text: String): SampleParseResult {
    val clean = text.replace(Regex("^\\uFEFF"), "").replace(Regex("\\r\\n?"), "\n")
    val lines = clean.split("\n").filter { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
    val firstLine = lines.firstOrNull() ?: ""
    val delimiter = detectDelimiter(firstLine)
    val firstCells = splitLine(firstLine, delimiter)
    val knownHeaders = NAME_HEADERS + GROUP_HEADERS + COLOR_HEADERS + REPLICATE_HEADERS
    val hasNameHeader = firstCells.any { it.lowercase() in NAME_HEADERS }
    val allKnown = firstCells.all { it.isEmpty() || it.lowercase() in knownHeaders }
    val hadHeader = hasNameHeader && allKnown

    var nameIndex = 0
    var groupIndex = 1
    var colorIndex = 2
    var replicateIndex = 3
    var start = 0
    if (hadHeader) {
        start = 1
        val name = headerIndex(firstCells, NAME_HEADERS)
        val group = headerIndex(firstCells, GROUP_HEADERS)
        val color = headerIndex(firstCells, COLOR_HEADERS)
        val replicates = headerIndex(firstCells, REPLICATE_HEADERS)
        nameIndex = if (name >= 0) name else 0
        groupIndex = group
        colorIndex = color
        replicateIndex = replicates
    }

    val rows = ArrayList<SampleDraft>()
    var skipped = 0
    for (i in start until lines.size) {
        val cells = splitLine(lines[i], delimiter)
        val name = cells.getOrNull(nameIndex)?.trim() ?: ""
        if (name.isEmpty()) {
            skipped++
            continue
        }
        val group = if (groupIndex >= 0) (cells.getOrNull(groupIndex)?.trim() ?: "") else ""
        val color = if (colorIndex >= 0) normalizeColor(cells.getOrNull(colorIndex)) else ""
        val replicates = if (replicateIndex >= 0) parseReplicates(cells.getOrNull(replicateIndex) ?: "") else null
        rows.add(SampleDraft(name, group, color, replicates))
    }

    return SampleParseResult(rows, skipped, delimiter, hadHeader)
}

data class PlateTableRow(
    val well: String,
    val sample: String,
    val group: String,
    val type: String,
    val replicate: Int?,
)

fun buildPlateRows(format: PlateFormat, assignments: Map<String, WellAssignment>): List<PlateTableRow> {
    val geometry = formatGeometry(format)
    val rows = ArrayList<PlateTableRow>()
    for (row in 0 until geometry.rows) {
        for (col in 0 until geometry.cols) {
            val id = wellId(row, col)
            val assignment = assignments[id]
            if (assignment == null) {
                rows.add(PlateTableRow(id, "", "", "empty", null))
                continue
            }
            rows.add(
                PlateTableRow(
                    well = id,
                    sample = assignment.sampleName.ifEmpty { assignment.sampleId },
                    group = assignment.group,
                    type = assignment.type,
                    replicate = if (assignment.type == "reserved") null else assignment.replicate,
                )
            )
        }
    }
    return rows
}

private fun csvCell(value: String?): String {
    if (value === null) return ""
    if (Regex("[\",\\r\\n]").containsMatchIn(value)) return "\"${value.replace("\"", "\"\"")}\""
    return value
}

private fun tsvCell(value: String?): String {
    if (value === null) return ""
    return value.replace(Regex("[\\t\\r\\n]+"), " ")
}

fun plateRowsToCsv(rows: List<PlateTableRow>): String {
    val header = "well,sample,group,type,replicate"
    val body = rows.map { row ->
        listOf(row.well, row.sample, row.group, row.type, row.replicate?.toString() ?: "")
            .joinToString(",") { csvCell(it) }
    }
    return (listOf(header) + body).joinToString("\n")
}

fun plateRowsToTsv(rows: List<PlateTableRow>): String {
    val header = "well\tsample\tgroup\ttype\treplicate"
    val body = rows.map { row ->
        listOf(row.well, row.sample, row.group, row.type, row.replicate?.toString() ?: "")
            .joinToString("\t") { tsvCell(it) }
    }
    return (listOf(header) + body).joinToString("\n")
}

/** Safe ASCII file stem derived from the user-supplied plate title. */
fun plateFileBaseName(title: String, fallback: String = "plate-layout"): String {
    val slug = title
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("^-+|-+$"), "")
        .take(60)
    return slug.ifEmpty { fallback }
}
