package com.spice.nativeapp.tools.plate

// Colour helpers and layout signature shared by the screen and tests.
// 1:1 port of the PALETTE constant, hashText/groupColor/textColorFor/
// baseCellFor helpers inside PlateTool.svelte's script block.

import org.json.JSONArray
import org.json.JSONObject

val PLATE_PALETTE = listOf(
    "#4cd6ff", "#ffd23f", "#53d769", "#b48cff", "#ff9f1c", "#ff5d5d",
    "#61e0c0", "#f78fb3", "#a3d977", "#8bb8ff", "#e0aaff", "#ffcf9f",
    "#7ce0ff", "#f2e06b",
)

const val PLATE_CONTROL_COLOR = "#b48cff"
const val PLATE_BLANK_COLOR = "#7f8ba8"
const val PLATE_RESERVED_COLOR = "#26304a"
const val PLATE_EMPTY_COLOR = "#0e1220"

/** FNV-1a 32-bit, normalized to unsigned exactly like JS `hash >>> 0`. */
fun hashText(value: String): Int {
    // JS starts from 2166136261 (a positive 2^31+ Number); every bitwise op
    // (`^=` and Math.imul) coerces to Int32 first, so the whole pipeline is
    // Int32 arithmetic. 2166136261 as Int32 = -2128831035.
    var hash = -2128831035
    for (ch in value) {
        hash = hash xor ch.code
        hash *= 16777619
    }
    return hash
}

fun groupColor(group: String): String {
    val key = group.trim()
    if (key.isEmpty()) return "#8b96b5"
    return PLATE_PALETTE[((hashText(key.lowercase()).toLong() and 0xffffffffL) % PLATE_PALETTE.size).toInt()]
}

fun textColorFor(fill: String): String {
    val hex = normalizeColor(fill).ifEmpty { PLATE_EMPTY_COLOR }
    val red = hex.substring(1, 3).toInt(16)
    val green = hex.substring(3, 5).toInt(16)
    val blue = hex.substring(5, 7).toInt(16)
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return if (luminance > 145) "#0b0e14" else "#eef3ff"
}

/** Interactive plate cell size per format (zoom 1), 1:1 baseCellFor. */
fun baseCellFor(value: PlateFormat): Int = when (value) {
    6 -> 64
    12 -> 54
    24 -> 44
    48 -> 36
    96 -> 27
    else -> 15
}

/**
 * Placement-affecting signature: only fields that change assignment logic are
 * included, so renaming or recolouring a sample keeps the layout fresh.
 * 1:1 port of signatureOf in PlateTool.svelte.
 */
fun signatureOf(formatValue: PlateFormat, sampleList: List<SampleRow>, ruleValue: PlateRules): String {
    val obj = JSONObject().apply {
        put("format", formatValue)
        put("samples", JSONArray().apply {
            sampleList.forEach { sample ->
                put(JSONObject().apply {
                    put("id", sample.id)
                    put("replicates", sample.replicates ?: JSONObject.NULL)
                })
            }
        })
        put("rules", ruleValue.toJson())
    }
    return obj.toString()
}
