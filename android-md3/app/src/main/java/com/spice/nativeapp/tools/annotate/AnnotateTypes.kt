package com.spice.nativeapp.tools.annotate

/*
 * 图像标注/多面板拼版工具数据层，1:1 移植 main 的 src/lib/tools/annotate/types.ts。
 * 一切皆纯 JSON：项目可整体导出、自动保存并恢复，无二进制边车。
 * JS 数值语义（Number 语义按 Double 移植；clamp 边界与原版一致）。
 */
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val PX_PER_MM = 96.0 / 25.4
const val PT_TO_PX = 96.0 / 72.0

/** Unit: um | mm | nm | px */
typealias Unit = String

const val UNIT_UM = "um"
const val UNIT_MM = "mm"
const val UNIT_NM = "nm"
const val UNIT_PX = "px"

/** SI factors. Pixels are not a physical unit. */
private val UNIT_FACTOR = mapOf(UNIT_NM to 1e-9, UNIT_UM to 1e-6, UNIT_MM to 1e-3)

fun unitSymbol(unit: Unit): String = when (unit) {
    UNIT_UM -> "µm"
    UNIT_MM -> "mm"
    UNIT_NM -> "nm"
    else -> "px"
}

fun isPhysicalUnit(unit: Unit): Boolean = unit != UNIT_PX

data class CropRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

data class Calibration(val unit: Unit, val pxPerUnit: Double)

/** One channel of an RGB composite. Gain is a multiplier in the 0..2 range. */
data class ChannelSetting(val show: Boolean, val gain: Double, val invert: Boolean) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("show", show); put("gain", gain); put("invert", invert)
    }
}

data class ChannelMix(val r: ChannelSetting, val g: ChannelSetting, val b: ChannelSetting) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("r", r.toJson()); put("g", g.toJson()); put("b", b.toJson())
    }
}

/** Display channels applied to every preview and export path. */
private val DEFAULT_CHANNEL = ChannelSetting(true, 1.0, false)

fun createDefaultChannels(): ChannelMix = ChannelMix(
    DEFAULT_CHANNEL.copy(), DEFAULT_CHANNEL.copy(), DEFAULT_CHANNEL.copy(),
)

fun channelIsDefault(channel: ChannelSetting): Boolean =
    channel.show && !channel.invert && abs(channel.gain - 1.0) < 1e-6

fun channelsAreDefault(mix: ChannelMix): Boolean =
    channelIsDefault(mix.r) && channelIsDefault(mix.g) && channelIsDefault(mix.b)

data class Panel(
    val id: String,
    val name: String,
    /** Embedded raster, always a data URL (TIFF is re-encoded to PNG). */
    val dataUrl: String,
    val width: Int,
    val height: Int,
    val mime: String,
    val crop: CropRect,
    /** Magnification inside the frame. 1 shows the whole crop. */
    val zoom: Double,
    /** Pan expressed relative to the maximum shift for the current zoom. */
    val offsetX: Double,
    val offsetY: Double,
    val brightness: Double,
    val contrast: Double,
    val channels: ChannelMix,
    val calibration: Calibration?,
) {
    fun withCrop(c: CropRect) = copy(crop = c)
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("dataUrl", dataUrl)
        put("width", width); put("height", height); put("mime", mime)
        put("crop", JSONObject().apply {
            put("left", crop.left); put("top", crop.top); put("right", crop.right); put("bottom", crop.bottom)
        })
        put("zoom", zoom); put("offsetX", offsetX); put("offsetY", offsetY)
        put("brightness", brightness); put("contrast", contrast)
        put("channels", channels.toJson())
        calibration?.let { put("calibration", JSONObject().apply { put("unit", it.unit); put("pxPerUnit", it.pxPerUnit) }) }
    }
}

// ------------------------------------------------------------- annotations

sealed class Annotation {
    abstract val id: String
    abstract val color: String
    abstract fun toJson(): JSONObject
}

data class ArrowAnnotation(
    override val id: String,
    override val color: String,
    val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    val strokeWidthPt: Double,
    val headSizePt: Double,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "arrow"); put("id", id); put("color", color)
        put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
        put("strokeWidthPt", strokeWidthPt); put("headSizePt", headSizePt)
    }
}

data class RectAnnotation(
    override val id: String,
    override val color: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val strokeWidthPt: Double,
    val fill: String,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "rect"); put("id", id); put("color", color)
        put("x", x); put("y", y); put("w", w); put("h", h)
        put("strokeWidthPt", strokeWidthPt); put("fill", fill)
    }
}

data class EllipseAnnotation(
    override val id: String,
    override val color: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val strokeWidthPt: Double,
    val fill: String,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "ellipse"); put("id", id); put("color", color)
        put("x", x); put("y", y); put("w", w); put("h", h)
        put("strokeWidthPt", strokeWidthPt); put("fill", fill)
    }
}

data class TextAnnotation(
    override val id: String,
    override val color: String,
    /** Top-left corner of the text block, in figure CSS pixels. */
    val x: Double,
    val y: Double,
    val text: String,
    val fontSizePt: Double,
    val background: String,
    val paddingPt: Double,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "text"); put("id", id); put("color", color)
        put("x", x); put("y", y); put("text", text)
        put("fontSizePt", fontSizePt); put("background", background); put("paddingPt", paddingPt)
    }
}

data class ScaleBarAnnotation(
    override val id: String,
    override val color: String,
    val x: Double,
    val y: Double,
    val mode: String, // 'calibrated' | 'fixed'
    /** Physical length in `unit` (calibrated) or millimetres (fixed). */
    val value: Double,
    val unit: Unit,
    val panelId: String,
    val thicknessPt: Double,
    val fontSizePt: Double,
    val showLabel: Boolean,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "scalebar"); put("id", id); put("color", color)
        put("x", x); put("y", y); put("mode", mode); put("value", value); put("unit", unit)
        put("panelId", panelId); put("thicknessPt", thicknessPt)
        put("fontSizePt", fontSizePt); put("showLabel", showLabel)
    }
}

data class PanelBarAnnotation(
    override val id: String,
    override val color: String,
    val panelId: String,
    /** Normalized start position inside the panel frame. */
    val nx: Double,
    val ny: Double,
    val value: Double,
    val unit: Unit,
    val thicknessPt: Double,
    val fontSizePt: Double,
    val showLabel: Boolean,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "panelbar"); put("id", id); put("color", color)
        put("panelId", panelId); put("nx", nx); put("ny", ny); put("value", value); put("unit", unit)
        put("thicknessPt", thicknessPt); put("fontSizePt", fontSizePt); put("showLabel", showLabel)
    }
}

/** Straight measurement line with an evenly sampled intensity profile. */
data class ProfileAnnotation(
    override val id: String,
    override val color: String,
    val x1: Double, val y1: Double, val x2: Double, val y2: Double,
    /** Number of evenly spaced samples along the segment. */
    val samples: Int,
    val strokeWidthPt: Double,
) : Annotation() {
    override fun toJson() = JSONObject().apply {
        put("kind", "profile"); put("id", id); put("color", color)
        put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2)
        put("samples", samples); put("strokeWidthPt", strokeWidthPt)
    }
}

// ------------------------------------------------------------- layout settings

data class LayoutSettings(
    val columns: Int,
    val flow: String, // 'row' | 'column'
    val gapMm: Double,
    val marginMm: Double,
    val figureWidthMm: Double,
    val labelStyle: String, // 'upper' | 'lower' | 'number'
    val labelPosition: String, // 'tl'..'br'
    val labelFontSizePt: Double,
    val labelColor: String,
    val background: String,
    val fontFamily: String,
    val exportDpi: Int, // 300 | 600
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("columns", columns); put("flow", flow); put("gapMm", gapMm); put("marginMm", marginMm)
        put("figureWidthMm", figureWidthMm); put("labelStyle", labelStyle); put("labelPosition", labelPosition)
        put("labelFontSizePt", labelFontSizePt); put("labelColor", labelColor)
        put("background", background); put("fontFamily", fontFamily); put("exportDpi", exportDpi)
    }
}

val DEFAULT_LAYOUT = LayoutSettings(
    columns = 2, flow = "row", gapMm = 2.0, marginMm = 2.0, figureWidthMm = 180.0,
    labelStyle = "upper", labelPosition = "tl", labelFontSizePt = 12.0,
    labelColor = "#111111", background = "#ffffff", fontFamily = "Arial", exportDpi = 300,
)

data class AnnotateProject(
    val version: Int = 1,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val panels: MutableList<Panel>,
    val annotations: MutableList<Annotation>,
    val layout: LayoutSettings,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version); put("name", name)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("panels", JSONArray().apply { panels.forEach { put(it.toJson()) } })
        put("annotations", JSONArray().apply { annotations.forEach { put(it.toJson()) } })
        put("layout", layout.toJson())
    }
}

fun clamp(value: Double, min: Double, max: Double): Double = kotlin.math.min(max, kotlin.math.max(min, value))

fun clamp(value: Int, min: Int, max: Int): Int = kotlin.math.min(max, kotlin.math.max(min, value))

/**
 * 原版：`${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`。
 * Math.random().toString(36) 形如 "0.kx7d9f" → slice(2,8) 取小数点后 6 位。
 */
fun createId(prefix: String): String {
    val time = System.currentTimeMillis().toString(36)
    val rand = UUID.randomUUID().toString().replace("-", "")
    val frac = StringBuilder()
    for (ch in rand) {
        if (frac.length >= 6) break
        val c = Character.toLowerCase(ch)
        if (c in '0'..'9' || c in 'a'..'z') frac.append(c)
    }
    while (frac.length < 6) frac.append('0')
    return "${prefix}_${time}_${frac}"
}

fun createDefaultLayout(): LayoutSettings = DEFAULT_LAYOUT.copy()

fun createDefaultProject(): AnnotateProject {
    val now = java.time.Instant.now().toString()
    return AnnotateProject(
        version = 1, name = "Figure", createdAt = now, updatedAt = now,
        panels = mutableListOf(), annotations = mutableListOf(), layout = createDefaultLayout(),
    )
}

/** Crop values are normalized and kept inside a valid non-empty region. */
fun safeCrop(panel: Panel): CropRect {
    val left = clamp(panel.crop.left, 0.0, 0.9)
    val right = clamp(panel.crop.right, 0.0, 0.9 - left)
    val top = clamp(panel.crop.top, 0.0, 0.9)
    val bottom = clamp(panel.crop.bottom, 0.0, 0.9 - top)
    return CropRect(left, top, right, bottom)
}

data class VisibleRect(val sx: Double, val sy: Double, val sw: Double, val sh: Double)

/** Visible source region (normalized 0..1) after crop, zoom and pan. */
fun visibleRect(panel: Panel): VisibleRect {
    val crop = safeCrop(panel)
    val cw = 1 - crop.left - crop.right
    val ch = 1 - crop.top - crop.bottom
    val zoom = clamp(panel.zoom, 1.0, 12.0)
    val vw = cw / zoom
    val vh = ch / zoom
    val mx = (cw - vw) / 2
    val my = (ch - vh) / 2
    val ox = clamp(panel.offsetX, -1.0, 1.0)
    val oy = clamp(panel.offsetY, -1.0, 1.0)
    return VisibleRect(
        sx = crop.left + mx + ox * mx,
        sy = crop.top + my + oy * my,
        sw = vw, sh = vh,
    )
}

/** Source pixels per figure pixel for the current panel view. */
fun sourcePixelsPerFigurePixel(panel: Panel, frameWidth: Double): Double {
    val vis = visibleRect(panel)
    return (vis.sw * panel.width) / max(1.0, frameWidth)
}

/**
 * Convert a physical length into source-image pixels. When the requested unit
 * is pixels the value is used as-is; otherwise the panel calibration is used.
 */
fun sourcePixelsFor(cal: Calibration?, value: Double, unit: Unit): Double? {
    if (value.isNaN() || value <= 0) return null
    if (unit == UNIT_PX) return value
    if (cal == null || cal.pxPerUnit.isNaN() || cal.pxPerUnit <= 0) return null
    if (cal.unit == UNIT_PX) return null
    return (value * (UNIT_FACTOR[unit] ?: 0.0) * cal.pxPerUnit) / (UNIT_FACTOR[cal.unit] ?: 0.0)
}

/** Convert a calibration value from one unit to another without changing scale. */
fun convertCalibration(cal: Calibration, nextUnit: Unit): Calibration? {
    if (nextUnit == UNIT_PX || cal.unit == UNIT_PX) {
        return if (cal.unit == nextUnit) cal.copy() else null
    }
    if (cal.unit == nextUnit) return cal.copy()
    return Calibration(
        unit = nextUnit,
        pxPerUnit = (cal.pxPerUnit * (UNIT_FACTOR[cal.unit] ?: 0.0)) / (UNIT_FACTOR[nextUnit] ?: 0.0),
    )
}

/** Convert an annotation value when the display unit changes. */
fun convertValue(value: Double, from: Unit, to: Unit): Double {
    if (from == to) return value
    if (from == UNIT_PX || to == UNIT_PX) return value
    return (value * (UNIT_FACTOR[from] ?: 0.0)) / (UNIT_FACTOR[to] ?: 0.0)
}

/** JS toFixed 语义：四舍五入（对 .5 采用 JS 的 toFixed 浮点行为，Kotlin String.format(Locale.US) 一致量级）。 */
private fun jsToFixed(value: Double, digits: Int): String =
    String.format(java.util.Locale.US, "%." + digits + "f", value)

fun formatValue(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val abs = abs(value)
    val digits = when {
        abs >= 100 -> 0
        abs >= 10 -> 1
        abs >= 1 -> 2
        else -> 3
    }
    val fixed = jsToFixed(value, digits)
    return fixed
        .replace(Regex("\\.0+$"), "")
        .replace(Regex("(\\.\\d*?)0+$"), "$1")
}

private fun letterLabel(index: Int, lower: Boolean): String {
    var n = index
    var out = ""
    do {
        out = ((if (lower) 97 else 65) + n % 26).toChar() + out
        n = n / 26 - 1
    } while (n >= 0)
    return out
}

fun panelLabel(index: Int, style: String): String {
    if (style == "number") return (index + 1).toString()
    return letterLabel(index, style == "lower")
}

fun emptyPanel(name: String, dataUrl: String, width: Int, height: Int, mime: String): Panel = Panel(
    id = createId("panel"),
    name = name, dataUrl = dataUrl, width = width, height = height, mime = mime,
    crop = CropRect(0.0, 0.0, 0.0, 0.0),
    zoom = 1.0, offsetX = 0.0, offsetY = 0.0,
    brightness = 100.0, contrast = 100.0,
    channels = createDefaultChannels(),
    calibration = null,
)

// ------------------------------------------------------------- parsing

private fun JSONObject.optNum(key: String, fallback: Double): Double {
    val v = opt(key) ?: return fallback
    return when (v) {
        is Number -> if (v.toDouble().isFinite()) v.toDouble() else fallback
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
        else -> fallback
    }
}

private fun JSONObject.optStr(key: String, fallback: String): String {
    val v = opt(key) ?: return fallback
    return if (v is String) v else fallback
}

private fun validUnit(v: Any?): Boolean =
    v == UNIT_UM || v == UNIT_MM || v == UNIT_NM || v == UNIT_PX

private fun parseCalibration(value: Any?): Calibration? {
    if (value !is JSONObject) return null
    if (!validUnit(value.opt("unit"))) return null
    val pxPerUnit = value.optNum("pxPerUnit", 0.0)
    if (pxPerUnit <= 0) return null
    return Calibration(value.optString("unit"), pxPerUnit)
}

private fun parseChannel(value: Any?, fallback: ChannelSetting): ChannelSetting {
    if (value !is JSONObject) return fallback.copy()
    return ChannelSetting(
        show = value.opt("show") != false,
        gain = clamp(value.optNum("gain", fallback.gain), 0.0, 2.0),
        invert = value.opt("invert") == true,
    )
}

/** Missing channel blocks (older files) normalize to the fully visible mix. */
private fun parseChannels(value: Any?): ChannelMix {
    val src = if (value is JSONObject) value else JSONObject()
    return ChannelMix(
        r = parseChannel(src.opt("r"), DEFAULT_CHANNEL),
        g = parseChannel(src.opt("g"), DEFAULT_CHANNEL),
        b = parseChannel(src.opt("b"), DEFAULT_CHANNEL),
    )
}

private fun parsePanel(value: Any?): Panel? {
    if (value !is JSONObject) return null
    val id = value.opt("id") as? String ?: return null
    val dataUrl = value.opt("dataUrl") as? String ?: return null
    if (!Regex("^data:image/", RegexOption.IGNORE_CASE).containsMatchIn(dataUrl)) return null
    val width = value.optNum("width", 0.0).roundToInt()
    val height = value.optNum("height", 0.0).roundToInt()
    if (width <= 0 || height <= 0) return null
    val crop = if (value.opt("crop") is JSONObject) value.getJSONObject("crop") else JSONObject()
    val cropLeft = clamp(crop.optNum("left", 0.0), 0.0, 0.9)
    val cropRight = clamp(crop.optNum("right", 0.0), 0.0, 0.9 - cropLeft)
    val cropTop = clamp(crop.optNum("top", 0.0), 0.0, 0.9)
    val cropBottom = clamp(crop.optNum("bottom", 0.0), 0.0, 0.9 - cropTop)
    return Panel(
        id = id,
        name = value.optStr("name", "Panel"),
        dataUrl = dataUrl,
        width = width,
        height = height,
        mime = value.optStr("mime", "image/png"),
        crop = CropRect(cropLeft, cropTop, cropRight, cropBottom),
        zoom = clamp(value.optNum("zoom", 1.0), 1.0, 12.0),
        offsetX = clamp(value.optNum("offsetX", 0.0), -1.0, 1.0),
        offsetY = clamp(value.optNum("offsetY", 0.0), -1.0, 1.0),
        brightness = clamp(value.optNum("brightness", 100.0), 10.0, 300.0),
        contrast = clamp(value.optNum("contrast", 100.0), 10.0, 300.0),
        channels = parseChannels(value.opt("channels")),
        calibration = parseCalibration(value.opt("calibration")),
    )
}

private fun parseAnnotation(value: Any?): Annotation? {
    if (value !is JSONObject) return null
    val id = value.opt("id") as? String ?: return null
    val color = value.optStr("color", "#111111")
    return when (value.opt("kind")) {
        "arrow" -> ArrowAnnotation(
            id = id, color = color,
            x1 = value.optNum("x1", 0.0), y1 = value.optNum("y1", 0.0),
            x2 = value.optNum("x2", 0.0), y2 = value.optNum("y2", 0.0),
            strokeWidthPt = clamp(value.optNum("strokeWidthPt", 1.5), 0.25, 12.0),
            headSizePt = clamp(value.optNum("headSizePt", 8.0), 2.0, 40.0),
        )
        "rect" -> RectAnnotation(
            id = id, color = color,
            x = value.optNum("x", 0.0), y = value.optNum("y", 0.0),
            w = max(2.0, value.optNum("w", 40.0)),
            h = max(2.0, value.optNum("h", 30.0)),
            strokeWidthPt = clamp(value.optNum("strokeWidthPt", 1.5), 0.25, 12.0),
            fill = value.optStr("fill", "none"),
        )
        "ellipse" -> EllipseAnnotation(
            id = id, color = color,
            x = value.optNum("x", 0.0), y = value.optNum("y", 0.0),
            w = max(2.0, value.optNum("w", 40.0)),
            h = max(2.0, value.optNum("h", 30.0)),
            strokeWidthPt = clamp(value.optNum("strokeWidthPt", 1.5), 0.25, 12.0),
            fill = value.optStr("fill", "none"),
        )
        "text" -> TextAnnotation(
            id = id, color = color,
            x = value.optNum("x", 0.0), y = value.optNum("y", 0.0),
            text = value.optStr("text", ""),
            fontSizePt = clamp(value.optNum("fontSizePt", 10.0), 4.0, 96.0),
            background = value.optStr("background", "none"),
            paddingPt = clamp(value.optNum("paddingPt", 2.0), 0.0, 20.0),
        )
        "scalebar" -> {
            val unit = if (validUnit(value.opt("unit"))) value.optString("unit") else UNIT_MM
            val mode = if (value.opt("mode") == "fixed") "fixed" else "calibrated"
            ScaleBarAnnotation(
                id = id, color = color,
                x = value.optNum("x", 0.0), y = value.optNum("y", 0.0),
                mode = mode,
                value = max(0.0, value.optNum("value", if (mode == "fixed") 5.0 else 10.0)),
                unit = if (mode == "fixed") UNIT_MM else unit,
                panelId = value.optStr("panelId", ""),
                thicknessPt = clamp(value.optNum("thicknessPt", 2.0), 0.25, 16.0),
                fontSizePt = clamp(value.optNum("fontSizePt", 9.0), 4.0, 48.0),
                showLabel = value.opt("showLabel") != false,
            )
        }
        "panelbar" -> PanelBarAnnotation(
            id = id, color = color,
            panelId = value.optStr("panelId", ""),
            nx = clamp(value.optNum("nx", 0.08), 0.0, 1.0),
            ny = clamp(value.optNum("ny", 0.9), 0.0, 1.0),
            value = max(0.0, value.optNum("value", 10.0)),
            unit = if (validUnit(value.opt("unit"))) value.optString("unit") else UNIT_UM,
            thicknessPt = clamp(value.optNum("thicknessPt", 2.0), 0.25, 16.0),
            fontSizePt = clamp(value.optNum("fontSizePt", 9.0), 4.0, 48.0),
            showLabel = value.opt("showLabel") != false,
        )
        "profile" -> ProfileAnnotation(
            id = id, color = color,
            x1 = value.optNum("x1", 0.0), y1 = value.optNum("y1", 0.0),
            x2 = value.optNum("x2", 0.0), y2 = value.optNum("y2", 0.0),
            samples = clamp(value.optNum("samples", 192.0), 16.0, 1024.0).roundToInt(),
            strokeWidthPt = clamp(value.optNum("strokeWidthPt", 1.2), 0.25, 12.0),
        )
        else -> null
    }
}

private fun parseLayout(value: Any?): LayoutSettings {
    val src = if (value is JSONObject) value else JSONObject()
    val labelStyle = when (src.opt("labelStyle")) {
        "lower" -> "lower"
        "number" -> "number"
        else -> "upper"
    }
    val labelPosition = when (src.opt("labelPosition")) {
        "tc", "tr", "bl", "bc", "br" -> src.optString("labelPosition")
        else -> "tl"
    }
    return LayoutSettings(
        columns = clamp(src.optNum("columns", DEFAULT_LAYOUT.columns.toDouble()), 1.0, 12.0).roundToInt(),
        flow = if (src.opt("flow") == "column") "column" else "row",
        gapMm = clamp(src.optNum("gapMm", DEFAULT_LAYOUT.gapMm), 0.0, 30.0),
        marginMm = clamp(src.optNum("marginMm", DEFAULT_LAYOUT.marginMm), 0.0, 40.0),
        figureWidthMm = clamp(src.optNum("figureWidthMm", DEFAULT_LAYOUT.figureWidthMm), 40.0, 400.0),
        labelStyle = labelStyle,
        labelPosition = labelPosition,
        labelFontSizePt = clamp(src.optNum("labelFontSizePt", DEFAULT_LAYOUT.labelFontSizePt), 4.0, 36.0),
        labelColor = src.optStr("labelColor", DEFAULT_LAYOUT.labelColor),
        background = src.optStr("background", DEFAULT_LAYOUT.background),
        fontFamily = src.optStr("fontFamily", DEFAULT_LAYOUT.fontFamily),
        exportDpi = if (src.opt("exportDpi") == 600) 600 else 300,
    )
}

/** Accepts untrusted JSON and returns a sanitized project, or null. */
fun normalizeProject(value: Any?): AnnotateProject? {
    if (value !is JSONObject) return null
    if (value.opt("version") != 1) return null
    val panelsRaw = value.opt("panels")
    if (panelsRaw !is JSONArray) return null
    val panels = mutableListOf<Panel>()
    val panelIds = HashSet<String>()
    for (i in 0 until panelsRaw.length()) {
        val panel = parsePanel(panelsRaw.opt(i)) ?: continue
        if (!panelIds.contains(panel.id)) {
            panelIds.add(panel.id)
            panels.add(panel)
        }
    }
    val annotations = mutableListOf<Annotation>()
    val annotationIds = HashSet<String>()
    val annotationsRaw = value.opt("annotations")
    if (annotationsRaw is JSONArray) {
        for (i in 0 until annotationsRaw.length()) {
            val ann = parseAnnotation(annotationsRaw.opt(i)) ?: continue
            if (!annotationIds.contains(ann.id)) {
                annotationIds.add(ann.id)
                annotations.add(ann)
            }
        }
    }
    val ids = panels.map { it.id }.toHashSet()
    for (ann in annotations) {
        if (ann is PanelBarAnnotation && !ids.contains(ann.panelId)) {
            val fixed = panels.firstOrNull()?.id ?: ""
            annotations[annotations.indexOf(ann)] = ann.copy(panelId = fixed)
        }
        if (ann is ScaleBarAnnotation && ann.panelId.isNotEmpty() && !ids.contains(ann.panelId)) {
            val fixed = panels.firstOrNull()?.id ?: ""
            annotations[annotations.indexOf(ann)] = ann.copy(panelId = fixed)
        }
    }
    return AnnotateProject(
        version = 1,
        name = value.optStr("name", "Figure"),
        createdAt = value.optStr("createdAt", java.time.Instant.now().toString()),
        updatedAt = value.optStr("updatedAt", java.time.Instant.now().toString()),
        panels = panels,
        annotations = annotations,
        layout = parseLayout(value.opt("layout")),
    )
}
