package com.spice.nativeapp.tools.qpcr

/*
 * Table parsing for qPCR exports. 1:1 port of main's
 * src/lib/tools/qpcr/parse.ts plus the plate-map interchange
 * (parsePlateMap / plateMapToQpcrTsv) from src/lib/tools/shared/samples.ts.
 */

import java.util.Locale

class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val delimiter: String,
)

private val DELIMITERS = listOf('\t', ',', ';')

/** Count delimiter candidates outside quoted sections of one line (1:1 detectDelimiter). */
private fun detectDelimiter(line: String): Char {
    val counts = HashMap<Char, Int>().apply {
        put('\t', 0); put(',', 0); put(';', 0)
    }
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (ch == '"') {
            if (inQuotes && i + 1 < line.length && line[i + 1] == '"') i++
            else inQuotes = !inQuotes
        } else if (!inQuotes && ch in counts) {
            counts[ch] = (counts[ch] ?: 0) + 1
        }
        i++
    }
    var best = ','
    var bestCount = 0
    for (d in DELIMITERS) {
        if ((counts[d] ?: 0) > bestCount) {
            best = d
            bestCount = counts[d] ?: 0
        }
    }
    return best
}

/** Parse a delimited text table, honouring double-quoted fields (1:1 parseDelimited). */
fun parseDelimited(text: String, delimiter: Char? = null): ParsedTable {
    val clean = text.replace(Regex("^﻿"), "")
    val firstLineEnd = Regex("\\r?\\n").find(clean)?.range?.first ?: -1
    val firstLine = if (firstLineEnd == -1) clean else clean.substring(0, firstLineEnd)
    val delim = delimiter ?: detectDelimiter(firstLine)

    val rawRows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    var field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < clean.length) {
        val ch = clean[i]
        if (inQuotes) {
            if (ch == '"') {
                if (i + 1 < clean.length && clean[i + 1] == '"') {
                    field.append('"'); i++
                } else {
                    inQuotes = false
                }
            } else {
                field.append(ch)
            }
            i++
            continue
        }
        when {
            ch == '"' -> inQuotes = true
            ch == delim -> {
                row.add(field.toString()); field = StringBuilder()
            }
            ch == '\n' -> {
                row.add(field.toString()); field = StringBuilder()
                rawRows.add(row); row = ArrayList()
            }
            ch == '\r' -> {
                // CRLF: the LF branch closes the row. A lone CR is treated as a newline.
                if (i + 1 >= clean.length || clean[i + 1] != '\n') {
                    row.add(field.toString()); field = StringBuilder()
                    rawRows.add(row); row = ArrayList()
                }
            }
            else -> field.append(ch)
        }
        i++
    }
    row.add(field.toString())
    rawRows.add(row)

    val nonEmpty = rawRows.filter { r -> r.any { it.trim().isNotEmpty() } }
    if (nonEmpty.isEmpty()) return ParsedTable(emptyList(), emptyList(), delim.toString())

    val headers = nonEmpty[0].map { it.trim() }
    val width = headers.size
    val rows = nonEmpty.drop(1).map { r ->
        val padded = r.take(width).toMutableList()
        while (padded.size < width) padded.add("")
        padded.map { it.trim() }
    }
    return ParsedTable(headers, rows, delim.toString())
}

private val COLUMN_SYNONYMS: Map<String, List<String>> = mapOf(
    "well" to listOf("well", "wellid", "wellname", "wellnumber", "position", "wellposition"),
    "sample" to listOf("sample", "samplename", "sampleid", "samplenumber", "name", "id"),
    "target" to listOf(
        "target", "targetname", "gene", "genename", "assay", "assayname",
        "detector", "primer", "amplicon",
    ),
    "ct" to listOf("ct", "cq", "ctvalue", "cqvalue", "ctmean", "cqmean", "thresholdcycle", "cyclethreshold", "cr"),
    "group" to listOf("group", "groupname", "condition", "treatment", "cohort", "genotype", "strain"),
    "replicate" to listOf(
        "replicate", "rep", "replicatenumber", "replicateid", "technicalreplicate",
        "bioreplicate", "techrep", "biorep",
    ),
    "efficiency" to listOf("efficiency", "eff", "amplificationefficiency", "pcr efficiency", "primerefficiency"),
    "tm" to listOf("tm", "melttemp", "melttemperature", "meltingtemperature", "meltingtemp", "temperature"),
    "quantity" to listOf(
        "quantity", "quantitycopies", "copies", "copynumber", "standardquantity",
        "startingquantity", "log10quantity", "logquantity",
    ),
)

private val AUTO_MAP_ORDER = listOf("ct", "sample", "target", "well", "group", "replicate", "efficiency", "tm", "quantity")

private fun normalizeHeader(value: String): String =
    value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

/** Case-insensitive header auto-mapping. A column is used at most once (1:1 autoMap). */
fun autoMap(headers: List<String>): QpcrColumnMapping {
    val mapping = QpcrColumnMapping()
    val used = mutableSetOf<Int>()
    val normalized = headers.map { normalizeHeader(it) }

    // Exact synonym matches first, then a conservative containment pass.
    for (key in AUTO_MAP_ORDER) {
        val synonyms = COLUMN_SYNONYMS[key]!!.map { normalizeHeader(it) }
        for (i in normalized.indices) {
            if (i in used || normalized[i].isEmpty() || normalized[i] !in synonyms) continue
            mapping.set(key, i)
            used.add(i)
            break
        }
    }
    for (key in AUTO_MAP_ORDER) {
        if (mapping.get(key) >= 0) continue
        val synonyms = COLUMN_SYNONYMS[key]!!.map { normalizeHeader(it) }.filter { it.length >= 3 }
        for (i in normalized.indices) {
            val h = normalized[i]
            if (i in used || h.isEmpty() || synonyms.none { h.contains(it) }) continue
            mapping.set(key, i)
            used.add(i)
            break
        }
    }
    return mapping
}

fun missingRequiredColumns(mapping: QpcrColumnMapping): List<String> =
    listOf("sample", "target", "ct").filter { mapping.get(it) < 0 }

fun mappedColumnCount(mapping: QpcrColumnMapping): Int =
    QpcrColumns.COLUMN_KEYS.count { mapping.get(it) >= 0 }

private val UNDETERMINED = setOf(
    "", "-", "--", "na", "n/a", "nan", "none", "null", "undetermined", "undet",
    "noct", "no ct", "notdetected", "not detected",
)

/** Parse a Ct cell. Returns null for undetermined or non-numeric values (1:1 parseCtCell). */
fun parseCtCell(value: String): Double? {
    val raw = value.trim()
    if (raw.lowercase(Locale.ROOT) in UNDETERMINED) return null
    return parseNumericCell(raw)
}

fun parseNumericCell(value: String): Double? {
    var raw = value.trim()
    if (raw.isEmpty()) return null
    if (Regex("^-?\\d+,\\d+$").matches(raw)) raw = raw.replace(",", ".")
    return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Parse an efficiency cell. Values above 2 are treated as percentages (1:1 parseEfficiencyCell). */
fun parseEfficiencyCell(value: String): Double? {
    val raw = value.trim()
    if (raw.isEmpty()) return null
    val percent = raw.endsWith("%")
    val n = parseNumericCell(if (percent) raw.dropLast(1) else raw)
    if (n == null || n <= 0) return null
    val fraction = if (percent || n > 2) n / 100 else n
    return if (fraction > 0 && fraction.isFinite()) fraction else null
}

fun parseReplicateCell(value: String): Double? = parseNumericCell(value)

fun parseQuantityCell(value: String): Double? = parseNumericCell(value)

/** Build the editable well list from a parsed table and a column mapping (1:1 buildWells). */
fun buildWells(
    table: ParsedTable,
    mapping: QpcrColumnMapping,
    previous: List<QpcrWell> = emptyList(),
): List<QpcrWell> {
    val previousInclude = previous.associate { it.row to it.include }
    val cell = { row: List<String>, key: String ->
        val index = mapping.get(key)
        if (index >= 0) row.getOrNull(index)?.trim() ?: "" else ""
    }
    return table.rows.mapIndexed { index, row ->
        QpcrWell(
            id = "r$index",
            row = index,
            well = cell(row, "well"),
            sample = cell(row, "sample"),
            target = cell(row, "target"),
            group = cell(row, "group"),
            replicate = parseReplicateCell(cell(row, "replicate")),
            ct = parseCtCell(cell(row, "ct")),
            tm = parseNumericCell(cell(row, "tm")),
            efficiency = parseEfficiencyCell(cell(row, "efficiency")),
            quantity = parseQuantityCell(cell(row, "quantity")),
            include = previousInclude[index] ?: true,
        )
    }
}

// ------------------------------------------------- plate-map interchange
// 1:1 port of the plate-map half of src/lib/tools/shared/samples.ts
// (parsePlateMap / plateMapToQpcrTsv); the SPICE_SAMPLES sheet half is
// carried by the plate tool (T4).

class PlateMapRow(
    val well: String,
    val sample: String,
    val group: String,
    val type: String,
    val replicate: Double?,
)

private fun splitDelimited(line: String, delimiter: Char): List<String> {
    val out = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            quoted -> {
                if (ch == '"' && i + 1 < line.length && line[i + 1] == '"') {
                    cell.append('"'); i++
                } else if (ch == '"') quoted = false
                else cell.append(ch)
            }
            ch == '"' -> quoted = true
            ch == delimiter -> {
                out.add(cell.toString()); cell.setLength(0)
            }
            else -> cell.append(ch)
        }
        i++
    }
    out.add(cell.toString())
    return out.map { it.trim() }
}

/** Parse the `well,sample,group,type,replicate` CSV/TSV emitted by plate (1:1 parsePlateMap). */
fun parsePlateMap(text: String): List<PlateMapRow> {
    val lines = text.split(Regex("\\r?\\n")).filter { it.trim().isNotEmpty() }
    if (lines.isEmpty()) return emptyList()
    val delimiter = if (lines[0].contains('\t')) '\t' else ','
    val header = splitDelimited(lines[0], delimiter).map { it.lowercase(Locale.ROOT) }
    fun at(vararg names: String) = header.indexOfFirst { it in names }
    val wellAt = at("well", "well_id", "position")
    val sampleAt = at("sample", "sample_name", "name")
    val groupAt = at("group", "condition", "treatment")
    val typeAt = at("type", "kind")
    val repAt = at("replicate", "rep", "n")
    if (wellAt < 0) return emptyList()
    val rows = ArrayList<PlateMapRow>()
    for (line in lines.drop(1)) {
        val cells = splitDelimited(line, delimiter)
        val well = cells.getOrNull(wellAt)?.trim()?.uppercase(Locale.ROOT) ?: ""
        if (well.isEmpty()) continue
        val rep = if (repAt >= 0) cells.getOrNull(repAt)?.trim()?.toDoubleOrNull() else null
        rows.add(
            PlateMapRow(
                well = well,
                sample = if (sampleAt >= 0) cells.getOrNull(sampleAt)?.trim() ?: "" else "",
                group = if (groupAt >= 0) cells.getOrNull(groupAt)?.trim() ?: "" else "",
                type = if (typeAt >= 0) (cells.getOrNull(typeAt)?.trim() ?: "").lowercase(Locale.ROOT) else "sample",
                replicate = if (rep != null && rep.isFinite() && rep > 0) kotlin.math.round(rep).toDouble() else null,
            )
        )
    }
    return rows
}

/**
 * Normalise plate-map labels into a qPCR-ready TSV (1:1 plateMapToQpcrTsv).
 * Target and Ct are left blank for the user/instrument to fill.
 */
fun plateMapToQpcrTsv(rows: List<PlateMapRow>, target: String = ""): String {
    val header = "well\tsample\ttarget\tct\tgroup\treplicate"
    val body = rows.map { r ->
        listOf(r.well, r.sample, target, "", r.group, r.replicate?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "").joinToString("\t")
    }
    return (listOf(header) + body).joinToString("\n")
}
