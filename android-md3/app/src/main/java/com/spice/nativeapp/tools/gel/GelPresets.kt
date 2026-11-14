package com.spice.nativeapp.tools.gel

/**
 * Stain presets and the detection configuration they drive.
 * 1:1 移植自原版 src/lib/tools/gel/presets.ts。
 *
 * A preset bundles the colour channel, band polarity, gel type and display
 * mode that suit one staining method. The advanced panel can override every
 * field; changing one turns the preset into "custom" without losing the rest.
 */

data class LaneDetectOptions(
    /** Active-run cut as a fraction of the strongest column. */
    var cut: Double = 0.08,
    /** Gaps up to this fraction of the image width merge into one lane. */
    var mergeGap: Double = 0.005,
    /** Minimum lane width as a fraction of the image width. */
    var minWidth: Double = 0.004,
    /** Smoothing window as a fraction of the image width. */
    var smooth: Double = 0.02,
    /** Minimum peak prominence as a fraction of the normalised profile. */
    var prominence: Double = 0.1,
)

data class BandDetectOptions(
    /** Peak prominence gate, in profile standard deviations. */
    var prominence: Double = 0.5,
    /** Minimum gap between bands as a fraction of the image height. */
    var minGap: Double = 0.006,
    /** Smoothing window in pixels; protein gels add two pixels. */
    var smooth: Int = 3,
)

data class GelDetectConfig(
    var presetId: String = "auto",
    /** "auto" | "dna" | "protein" */
    var type: String = "auto",
    /** "auto" | "luma" | "red" | "green" | "blue" | "red-blue" | "green-red" */
    var channel: String = "auto",
    /** "auto" | "bright" | "dark" */
    var polarity: String = "auto",
    /** 0..1; higher keeps fainter signal. */
    var sensitivity: Double = 0.35,
    /** Morphological opening radius on the native backend, 0..2. */
    var denoise: Int = 1,
    var lanes: LaneDetectOptions = LaneDetectOptions(),
    var bands: BandDetectOptions = BandDetectOptions(),
)

data class StainPreset(
    val id: String,
    /** Paraglide message key for the selector label. */
    val labelKey: String,
    /** Display mode applied together with the preset, when set. */
    val display: String?,
    val config: GelDetectConfig,
)

object GelPresets {

    val DETECT_CHANNELS = listOf("auto", "luma", "red", "green", "blue", "red-blue", "green-red")
    val DETECT_POLARITIES = listOf("auto", "bright", "dark")
    val DETECT_TYPE_MODES = listOf("auto", "dna", "protein")

    /** Sensitivity that maps to the thresholds the tool shipped with. */
    const val DEFAULT_SENSITIVITY = 0.35

    fun defaultDetectConfig(): GelDetectConfig = GelDetectConfig()

    private fun preset(
        id: String,
        labelKey: String,
        display: String?,
        patch: (GelDetectConfig) -> Unit,
    ): StainPreset {
        val config = defaultDetectConfig()
        patch(config)
        config.presetId = id
        return StainPreset(id, labelKey, display, config)
    }

    /** Built-in stains. "custom" is not offered as a starting point; it only
     *  appears when the user edits a preset's fields. */
    val STAIN_PRESETS: List<StainPreset> = listOf(
        preset("auto", "gelPresetAuto", null) { },
        // Ethidium bromide / SYBR Safe on a UV box: purple background, pink bands.
        // R-B separates the bands from the background far better than luminance.
        preset("uv", "gelPresetUv", "color") {
            it.type = "dna"
            it.channel = "red-blue"
            it.polarity = "bright"
        },
        preset("sybr", "gelPresetSybr", "color") {
            it.type = "dna"
            it.channel = "green"
            it.polarity = "bright"
            it.sensitivity = 0.4
        },
        // Coomassie and silver: dark bands on a light, uneven background.
        preset("coomassie", "gelPresetCoomassie", "gray") {
            it.type = "protein"
            it.channel = "luma"
            it.polarity = "dark"
            it.sensitivity = 0.3
        },
        preset("silver", "gelPresetSilver", "gray") {
            it.type = "protein"
            it.channel = "luma"
            it.polarity = "dark"
            it.sensitivity = 0.45
        },
    )

    fun presetById(id: String): StainPreset? = STAIN_PRESETS.firstOrNull { it.id == id }

    /** Options for the select: built-ins plus "custom" when it is active. */
    fun presetChoices(currentId: String): List<Pair<String, String>> {
        val choices = STAIN_PRESETS.map { it.id to it.labelKey }
        if (currentId == "custom" || presetById(currentId) == null) {
            return choices + ("custom" to "gelPresetCustom")
        }
        return choices
    }

    private fun clamp(v: Double?, lo: Double, hi: Double, fallback: Double): Double =
        if (v == null || !v.isFinite()) fallback else minOf(hi, maxOf(lo, v))

    private fun pick(v: String?, allowed: List<String>, fallback: String): String =
        if (v != null && allowed.contains(v)) v else fallback

    /** Clamp a stored or hand-edited config; missing fields inherit the preset. */
    fun sanitizeDetectConfig(o: org.json.JSONObject?): GelDetectConfig {
        val raw = o
        val rawPreset = if (raw?.opt("presetId") is String) raw.optString("presetId") else "auto"
        val base = presetById(rawPreset)?.config ?: defaultDetectConfig()
        val lanes = raw?.optJSONObject("lanes")
        val bands = raw?.optJSONObject("bands")
        val presetId = if (rawPreset == "custom" || presetById(rawPreset) != null) rawPreset else "custom"
        val cfg = GelDetectConfig(
            presetId = presetId,
            type = pick(raw?.optStringOrNull("type"), DETECT_TYPE_MODES, base.type),
            channel = pick(raw?.optStringOrNull("channel"), DETECT_CHANNELS, base.channel),
            polarity = pick(raw?.optStringOrNull("polarity"), DETECT_POLARITIES, base.polarity),
            sensitivity = clamp(raw?.optDoubleOrNull("sensitivity"), 0.0, 1.0, base.sensitivity),
            denoise = Math.round(clamp(raw?.optDoubleOrNull("denoise"), 0.0, 2.0, base.denoise.toDouble())).toInt(),
        )
        cfg.lanes = LaneDetectOptions(
            cut = clamp(lanes?.optDoubleOrNull("cut"), 0.02, 0.4, base.lanes.cut),
            mergeGap = clamp(lanes?.optDoubleOrNull("mergeGap"), 0.0, 0.02, base.lanes.mergeGap),
            minWidth = clamp(lanes?.optDoubleOrNull("minWidth"), 0.001, 0.02, base.lanes.minWidth),
            smooth = clamp(lanes?.optDoubleOrNull("smooth"), 0.0, 0.05, base.lanes.smooth),
            prominence = clamp(lanes?.optDoubleOrNull("prominence"), 0.01, 0.3, base.lanes.prominence),
        )
        cfg.bands = BandDetectOptions(
            prominence = clamp(bands?.optDoubleOrNull("prominence"), 0.1, 2.0, base.bands.prominence),
            minGap = clamp(bands?.optDoubleOrNull("minGap"), 0.001, 0.05, base.bands.minGap),
            smooth = Math.round(clamp(bands?.optDoubleOrNull("smooth"), 1.0, 15.0, base.bands.smooth.toDouble())).toInt(),
        )
        return cfg
    }

    /** Cache identity for the binarized mask. Lane/band fields do not change it. */
    fun detectConfigKey(config: GelDetectConfig): String =
        "${config.channel}|${"%.2f".format(config.sensitivity)}|${config.denoise}"

    /** The native binarizer mirrors polarity and sensitivity. */
    fun nativeBinarizeOptions(config: GelDetectConfig): Triple<Int, Int, Int> {
        val polarity = when (config.polarity) {
            "bright" -> 1
            "dark" -> -1
            else -> 0
        }
        // The adaptive threshold accepts a negative delta only, so more sensitivity
        // means a smaller required margin. 0.35 maps to the original -8.
        return Triple(polarity, -Math.round(4 + config.sensitivity * 11.4).toInt(), config.denoise)
    }

    /** Multiplier for the JS threshold/floor. 1.0 at the shipped sensitivity. */
    fun thresholdScale(config: GelDetectConfig): Double =
        minOf(1.35, maxOf(0.35, 1 + (DEFAULT_SENSITIVITY - config.sensitivity)))

    /**
     * Channel plane from ARGB pixels (0xAARRGGBB, row-major).
     * 对应原版 channelPlane(rgba, width, height, channel)。
     */
    fun channelPlane(argb: IntArray, width: Int, height: Int, channel: String): FloatArray {
        val out = FloatArray(width * height)
        val n = minOf(out.size, argb.size)
        for (p in 0 until n) {
            val px = argb[p]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            out[p] = when (channel) {
                "red" -> r / 255.0
                "green" -> g / 255.0
                "blue" -> b / 255.0
                "red-blue" -> 0.5 + (r - b) / 510.0
                "green-red" -> 0.5 + (g - r) / 510.0
                else -> (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
            }.toFloat()
        }
        return out
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(config: GelDetectConfig): org.json.JSONObject = org.json.JSONObject().apply {
        put("presetId", config.presetId)
        put("type", config.type)
        put("channel", config.channel)
        put("polarity", config.polarity)
        put("sensitivity", config.sensitivity)
        put("denoise", config.denoise)
        put("lanes", org.json.JSONObject().apply {
            put("cut", config.lanes.cut)
            put("mergeGap", config.lanes.mergeGap)
            put("minWidth", config.lanes.minWidth)
            put("smooth", config.lanes.smooth)
            put("prominence", config.lanes.prominence)
        })
        put("bands", org.json.JSONObject().apply {
            put("prominence", config.bands.prominence)
            put("minGap", config.bands.minGap)
            put("smooth", config.bands.smooth)
        })
    }

    fun fromJson(o: org.json.JSONObject?): GelDetectConfig = sanitizeDetectConfig(o)
}

/** org.json 辅助：optString 但区分 null。 */
internal fun org.json.JSONObject?.optStringOrNull(key: String): String? {
    if (this == null || !has(key) || isNull(key)) return null
    val v = opt(key) ?: return null
    return if (v is String) v else v.toString()
}

internal fun org.json.JSONObject?.optDoubleOrNull(key: String): Double? {
    if (this == null || !has(key) || isNull(key)) return null
    val v = opt(key) ?: return null
    val d = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }
    return d
}
