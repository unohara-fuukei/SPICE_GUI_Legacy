package com.spice.nativeapp.tools.plate

// Cross-tool sample sheet ("SPICE_SAMPLES") half of
// src/lib/tools/shared/samples.ts (the plate-map half lives in qpcr/QpcrParse.kt).
// The plate designer can write it, qPCR/blot/colony can read it, and the
// generic sample fields stay intentionally small so every tool can map them.

import org.json.JSONArray
import org.json.JSONObject

data class SampleSheetEntry(
    val id: String?,
    val name: String,
    val group: String,
    val color: String?,
    val replicates: Int?,
    val notes: String?,
)

data class SampleSheet(
    val name: String,
    val samples: List<SampleSheetEntry>,
    val updatedAt: String,
)

private fun clean(value: Any?): String = if (value is String) value.trim() else ""

fun normalizeSampleSheet(input: Any?): SampleSheet? {
    if (input !is JSONObject) return null
    val raw = input
    val list: JSONArray? = when {
        raw.opt("samples") is JSONArray -> raw.optJSONArray("samples")
        else -> null
    }
    if (list == null) return null
    val samples = ArrayList<SampleSheetEntry>()
    for (i in 0 until list.length()) {
        val item = list.opt(i) ?: continue
        if (item !is JSONObject) continue
        val e = item
        val name = clean(e.opt("name")).ifEmpty { clean(e.opt("sample")) }
        if (name.isEmpty()) continue
        val replicateValue = (e.opt("replicates") ?: e.opt("n") ?: 1).let {
            when (it) {
                is Number -> it.toDouble()
                else -> (it?.toString()?.toDoubleOrNull() ?: 1.0)
            }
        }
        samples.add(
            SampleSheetEntry(
                id = clean(e.opt("id")).ifEmpty { null },
                name = name,
                group = clean(e.opt("group")).ifEmpty { clean(e.opt("condition")) },
                color = clean(e.opt("color")).ifEmpty { null },
                replicates = if (replicateValue.isFinite() && replicateValue > 0) Math.round(replicateValue).toInt() else null,
                notes = clean(e.opt("notes")).ifEmpty { null },
            )
        )
    }
    return SampleSheet(
        name = clean(raw.opt("name")).ifEmpty { "Samples" },
        samples = samples,
        updatedAt = clean(raw.opt("updatedAt")).ifEmpty { nowIso() },
    )
}

fun parseSampleSheet(text: String): SampleSheet? {
    return try {
        normalizeSampleSheet(JSONObject(text))
    } catch (e: Exception) {
        null
    }
}

fun sampleSheetToJson(sheet: SampleSheet): String {
    val obj = JSONObject().apply {
        put("format", "SPICE_SAMPLES")
        put("version", 1)
        put("name", sheet.name)
        put("samples", JSONArray().apply {
            for (s in sheet.samples) {
                put(JSONObject().apply {
                    if (s.id !== null) put("id", s.id)
                    put("name", s.name)
                    put("group", s.group)
                    if (s.color !== null) put("color", s.color)
                    if (s.replicates !== null) put("replicates", s.replicates)
                    if (s.notes !== null) put("notes", s.notes)
                })
            }
        })
        put("updatedAt", nowIso())
    }
    return obj.toString(2)
}

private fun sheetCsvCell(value: String?): String {
    if (value === null) return ""
    if (Regex("[\",\\r\\n]").containsMatchIn(value)) return "\"${value.replace("\"", "\"\"")}\""
    return value
}

fun sampleSheetToCsv(sheet: SampleSheet): String {
    val header = "sample,group,color,replicates,notes"
    val body = sheet.samples.map { s ->
        listOf(s.name, s.group, s.color ?: "", s.replicates?.toString() ?: "", s.notes ?: "")
            .joinToString(",") { sheetCsvCell(it) }
    }
    return (listOf(header) + body).joinToString("\n")
}
