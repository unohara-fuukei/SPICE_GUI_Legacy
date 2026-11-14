package com.spice.nativeapp.tools.colony

/*
 * 菌落计数器数据模型，1:1 移植 main 的 src/lib/tools/colony/types.ts。
 * 所有标记坐标/面积均以工作图像素空间表示（见 ColonyImaging.kt），
 * 在旋转、裁剪与检测降采样后仍然有效。
 *
 * 平台差异：TS 接口 + 运行时校验 → Kotlin data class + org.json 校验
 * （isColonyProject/normalizeProject 的每条校验规则与回退规则逐行对应）。
 */
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

typealias ColonyPolarity = String   // "auto" | "dark" | "bright"
typealias ColonySource = String     // "auto" | "manual"
typealias ColonyColorMode = String  // "off" | "blue-white"
typealias ColonyClass = String      // "blue" | "white" | "unclassified"

const val POLARITY_AUTO = "auto"
const val POLARITY_DARK = "dark"
const val POLARITY_BRIGHT = "bright"
const val SOURCE_AUTO = "auto"
const val SOURCE_MANUAL = "manual"
const val COLOR_MODE_OFF = "off"
const val COLOR_MODE_BLUE_WHITE = "blue-white"
const val CLASS_BLUE = "blue"
const val CLASS_WHITE = "white"
const val CLASS_UNCLASSIFIED = "unclassified"

data class ColonyAdjust(
    val grayscale: Boolean,
    val invert: Boolean,
    /** -100..100，在对比度之后叠加。 */
    val brightness: Double,
    /** 0.1..3，围绕中灰应用。 */
    val contrast: Double,
)

data class ColonyCrop(
    /** 被旋转后图像各边裁掉的比例（0..0.4）。 */
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class ColonyParams(
    val autoThreshold: Boolean,
    /** 手动阈值，0..255 灰度级。 */
    val manualThreshold: Int,
    /** 高斯模糊半径，工作图像素。 */
    val blurRadius: Double,
    /** 最小目标面积，工作图像素平方（0 关闭）。 */
    val minArea: Double,
    /** 最大目标面积，工作图像素平方（0 关闭）。 */
    val maxArea: Double,
    /** 最小圆度 0..1（0 关闭）。 */
    val minCircularity: Double,
    val splitTouching: Boolean,
    val polarity: ColonyPolarity,
    /** 可选的蓝/白分类（从彩色原图测量）。 */
    val colorMode: ColonyColorMode,
)

data class ColonyMarker(
    val id: Int,
    /** 工作图像布坐标中的质心。 */
    val x: Double,
    val y: Double,
    /** 等效半径，工作图像素。 */
    val radius: Double,
    val area: Double,
    /** 目标内部平均调整强度 0..255。 */
    val meanIntensity: Double,
    val circularity: Double,
    val included: Boolean,
    val source: ColonySource,
    /** 从原始彩色图像采样的平均通道值。 */
    val meanR: Double,
    val meanG: Double,
    val meanB: Double,
    /** HSV 色相（度），无颜色信息时为 -1。 */
    val hue: Double,
    /** HSV 饱和度 0..1。 */
    val saturation: Double,
    val colonyClass: ColonyClass,
    /** 用户手动覆盖自动分类时为 true。 */
    val classManual: Boolean,
)

data class ColonyProject(
    val version: Int,
    val savedAt: String,
    val imageDataUrl: String,
    val imageName: String,
    /** 源图像尺寸。 */
    val imageWidth: Int,
    val imageHeight: Int,
    /** 标记所属工作图像的尺寸。 */
    val workWidth: Int,
    val workHeight: Int,
    val rotate: Int,
    val crop: ColonyCrop,
    val adjust: ColonyAdjust,
    val params: ColonyParams,
    val markers: List<ColonyMarker>,
    val dpi: Int,          // 300 | 600
    val dilutionFactor: Double,
    val platedVolume: Double,
)

data class ColonyStats(
    val total: Int,
    val included: Int,
    val excluded: Int,
    val manual: Int,
    val meanArea: Double,
    val medianArea: Double,
    val meanIntensity: Double,
    val meanCircularity: Double,
)

fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

fun defaultAdjust() = ColonyAdjust(grayscale = true, invert = false, brightness = 0.0, contrast = 1.0)

fun defaultCrop() = ColonyCrop(left = 0.0, top = 0.0, right = 0.0, bottom = 0.0)

fun defaultParams() = ColonyParams(
    autoThreshold = true,
    manualThreshold = 128,
    blurRadius = 1.0,
    minArea = 30.0,
    maxArea = 0.0,
    minCircularity = 0.0,
    splitTouching = true,
    polarity = POLARITY_AUTO,
    colorMode = COLOR_MODE_OFF,
)

fun emptyProject() = ColonyProject(
    version = 1,
    savedAt = nowIso(),
    imageDataUrl = "",
    imageName = "",
    imageWidth = 0,
    imageHeight = 0,
    workWidth = 0,
    workHeight = 0,
    rotate = 0,
    crop = defaultCrop(),
    adjust = defaultAdjust(),
    params = defaultParams(),
    markers = emptyList(),
    dpi = 300,
    dilutionFactor = 1e6,
    platedVolume = 0.1,
)

// ---------------------------------------------------------------- JSON round-trip

fun ColonyProject.toJson(): JSONObject = JSONObject().apply {
    put("version", version)
    put("savedAt", savedAt)
    put("imageDataUrl", imageDataUrl)
    put("imageName", imageName)
    put("imageWidth", imageWidth)
    put("imageHeight", imageHeight)
    put("workWidth", workWidth)
    put("workHeight", workHeight)
    put("rotate", rotate)
    put("crop", JSONObject().apply {
        put("left", crop.left); put("top", crop.top)
        put("right", crop.right); put("bottom", crop.bottom)
    })
    put("adjust", JSONObject().apply {
        put("grayscale", adjust.grayscale)
        put("invert", adjust.invert)
        put("brightness", adjust.brightness)
        put("contrast", adjust.contrast)
    })
    put("params", JSONObject().apply {
        put("autoThreshold", params.autoThreshold)
        put("manualThreshold", params.manualThreshold)
        put("blurRadius", params.blurRadius)
        put("minArea", params.minArea)
        put("maxArea", params.maxArea)
        put("minCircularity", params.minCircularity)
        put("splitTouching", params.splitTouching)
        put("polarity", params.polarity)
        put("colorMode", params.colorMode)
    })
    put("markers", JSONArray().apply {
        markers.forEach { marker ->
            put(JSONObject().apply {
                put("id", marker.id)
                put("x", marker.x); put("y", marker.y)
                put("area", marker.area); put("radius", marker.radius)
                put("meanIntensity", marker.meanIntensity)
                put("circularity", marker.circularity)
                put("included", marker.included)
                put("source", marker.source)
                put("meanR", marker.meanR); put("meanG", marker.meanG); put("meanB", marker.meanB)
                put("hue", marker.hue); put("saturation", marker.saturation)
                put("colonyClass", marker.colonyClass)
                put("classManual", marker.classManual)
            })
        }
    })
    put("dpi", dpi)
    put("dilutionFactor", dilutionFactor)
    put("platedVolume", platedVolume)
}

private fun JSONObject.optD(key: String): Double =
    if (has(key) && !isNull(key)) opt(key).let { v ->
        when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
    } else Double.NaN

private fun JSONObject.optI(key: String): Int =
    if (has(key) && !isNull(key)) opt(key).let { v ->
        when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    } else 0

private fun JSONObject.optB(key: String): Boolean =
    if (has(key) && !isNull(key)) opt(key).let { v ->
        when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v == "true"
            else -> false
        }
    } else false

/** 运行时校验：对应原版 isColonyProject 的逐条规则。 */
fun isColonyProject(value: Any?): Boolean {
    if (value !is JSONObject) return false
    if (value.optI("version") != 1) return false
    if (value.opt("imageDataUrl") !is String) return false
    if (value.opt("imageName") !is String) return false
    val crop = value.opt("crop"); val adjust = value.opt("adjust"); val params = value.opt("params")
    if (crop !is JSONObject || adjust !is JSONObject || params !is JSONObject) return false
    val markers = value.opt("markers") as? JSONArray ?: return false
    for (i in 0 until markers.length()) {
        val marker = markers.opt(i) as? JSONObject ?: return false
        val id = marker.opt("id"); val x = marker.opt("x"); val y = marker.opt("y")
        val area = marker.opt("area"); val radius = marker.opt("radius")
        val intensity = marker.opt("meanIntensity"); val circularity = marker.opt("circularity")
        val included = marker.opt("included"); val source = marker.opt("source")
        if (id !is Number || x !is Number || y !is Number || area !is Number ||
            radius !is Number || intensity !is Number || circularity !is Number ||
            included !is Boolean
        ) return false
        if (source != SOURCE_AUTO && source != SOURCE_MANUAL) return false
    }
    return true
}

/** 补齐旧快照/手工编辑可能缺失的字段，规则与原版 normalizeProject 一致。 */
fun normalizeProject(project: ColonyProject): ColonyProject {
    val base = emptyProject()
    val params = project.params
    val normalizedParams = params.copy(
        colorMode = if (params.colorMode == COLOR_MODE_BLUE_WHITE) COLOR_MODE_BLUE_WHITE else COLOR_MODE_OFF,
    )
    return project.copy(
        version = 1,
        crop = base.crop.let { project.crop },
        adjust = project.adjust,
        params = normalizedParams,
        markers = project.markers.mapIndexed { index, marker ->
            val radius = if (marker.radius.isFinite()) marker.radius else
                kotlin.math.sqrt(kotlin.math.max(0.0, marker.area) / Math.PI)
            marker.copy(
                id = marker.id, // Int 恒有限（原版非有限 → index+1 的回退在 JSON 解析层不可能触发）
                radius = radius,
                included = marker.included,
                source = if (marker.source == SOURCE_MANUAL) SOURCE_MANUAL else SOURCE_AUTO,
                meanR = if (marker.meanR.isFinite()) marker.meanR else 0.0,
                meanG = if (marker.meanG.isFinite()) marker.meanG else 0.0,
                meanB = if (marker.meanB.isFinite()) marker.meanB else 0.0,
                hue = if (marker.hue.isFinite()) marker.hue else -1.0,
                saturation = if (marker.saturation.isFinite()) marker.saturation else 0.0,
                colonyClass = if (marker.colonyClass == CLASS_BLUE || marker.colonyClass == CLASS_WHITE)
                    marker.colonyClass else CLASS_UNCLASSIFIED,
                classManual = marker.classManual,
            )
        },
    )
}

/** JSONObject → ColonyProject（宽松解析，字段缺失走原版回退值）。 */
fun projectFromJson(value: JSONObject): ColonyProject? {
    if (!isColonyProject(value)) return null
    val crop = value.optJSONObject("crop")!!
    val adjust = value.optJSONObject("adjust")!!
    val params = value.optJSONObject("params")!!
    val markersJson = value.optJSONArray("markers")!!
    val markers = (0 until markersJson.length()).mapNotNull { i ->
        val m = markersJson.optJSONObject(i) ?: return@mapNotNull null
        ColonyMarker(
            id = m.optI("id"),
            x = m.optD("x"), y = m.optD("y"),
            area = m.optD("area"), radius = m.optD("radius"),
            meanIntensity = m.optD("meanIntensity"),
            circularity = m.optD("circularity"),
            included = m.optB("included"),
            source = if (m.optString("source") == SOURCE_MANUAL) SOURCE_MANUAL else SOURCE_AUTO,
            meanR = m.optD("meanR"), meanG = m.optD("meanG"), meanB = m.optD("meanB"),
            hue = m.optD("hue"), saturation = m.optD("saturation"),
            colonyClass = m.optString("colonyClass"),
            classManual = m.optB("classManual"),
        )
    }
    return normalizeProject(
        ColonyProject(
            version = 1,
            savedAt = value.optString("savedAt"),
            imageDataUrl = value.optString("imageDataUrl"),
            imageName = value.optString("imageName"),
            imageWidth = value.optI("imageWidth"),
            imageHeight = value.optI("imageHeight"),
            workWidth = value.optI("workWidth"),
            workHeight = value.optI("workHeight"),
            rotate = value.optI("rotate"),
            crop = ColonyCrop(
                crop.optD("left"), crop.optD("top"), crop.optD("right"), crop.optD("bottom"),
            ),
            adjust = ColonyAdjust(
                adjust.optB("grayscale"), adjust.optB("invert"),
                adjust.optD("brightness"), adjust.optD("contrast"),
            ),
            params = ColonyParams(
                autoThreshold = params.optB("autoThreshold"),
                manualThreshold = params.optI("manualThreshold"),
                blurRadius = params.optD("blurRadius"),
                minArea = params.optD("minArea"),
                maxArea = params.optD("maxArea"),
                minCircularity = params.optD("minCircularity"),
                splitTouching = params.optB("splitTouching"),
                polarity = params.optString("polarity"),
                colorMode = params.optString("colorMode"),
            ),
            markers = markers,
            dpi = if (value.optI("dpi") == 600) 600 else 300,
            dilutionFactor = value.optD("dilutionFactor"),
            platedVolume = value.optD("platedVolume"),
        )
    )
}
