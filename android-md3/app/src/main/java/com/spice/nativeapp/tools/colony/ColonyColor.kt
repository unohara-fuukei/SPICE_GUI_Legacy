package com.spice.nativeapp.tools.colony

/*
 * 可选的蓝/白菌落分类，1:1 移植 main 的 src/lib/tools/colony/color.ts。
 * 标记在原始彩色工作画布（而非灰度检测副本）上测量，使分类在灰度显示
 * 模式下仍然保留。真正的灰度源会被整体检出，所有标记回退到"未分类"，
 * 而不是从 RGB 噪声中编造类别。
 *
 * 平台差异：ImageData Uint8ClampedArray → IntArray（ARGB 打包像素在
 * ColonyImaging 中展开为与原版完全一致的 R,G,B 顺序访问）。
 */
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ColorSample(
    val r: Double,
    val g: Double,
    val b: Double,
    val hue: Double,
    val saturation: Double,
    val value: Double,
)

/** 原版 PixelSource：data 为按 RGBA 展开的字节（此处用 IntArray 存 0..255 值）。 */
class PixelSource(val data: IntArray, val width: Int, val height: Int)

private fun rgbToHsv(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val delta = mx - mn
    var hue = -1.0
    if (delta > 1e-6) {
        if (mx == r) hue = 60 * (((g - b) / delta) % 6)
        else if (mx == g) hue = 60 * ((b - r) / delta + 2)
        else hue = 60 * ((r - g) / delta + 4)
        if (hue < 0) hue += 360
    }
    return Triple(hue, if (mx > 0) delta / mx else 0.0, mx / 255)
}

/**
 * 图像是否携带足以支持颜色分类的色度。稀疏颜色检查使中性背景与 JPEG
 * 噪声不会把灰度平板变成错误的蓝/白判定。
 */
fun canvasHasColor(source: PixelSource): Boolean {
    val step = max(1, (min(source.width, source.height) / 96.0).roundToInt())
    var total = 0
    var colored = 0
    var maxChroma = 0
    var y = 0
    while (y < source.height) {
        var x = 0
        while (x < source.width) {
            val offset = (y * source.width + x) * 4
            val r = source.data[offset]
            val g = source.data[offset + 1]
            val b = source.data[offset + 2]
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            if (chroma >= 18) colored++
            if (chroma > maxChroma) maxChroma = chroma
            total++
            x += step
        }
        y += step
    }
    if (total == 0) return false
    // 少量强饱和像素足以证明稀疏的蓝色菌落；
    // 灰度 JPEG 噪声停在色度地板以下。
    if (maxChroma >= 60 && colored >= 2) return true
    return colored >= max(8, (total * 0.001).toInt())
}

/** 标记足迹内的平均颜色；步进采样，大平板仍保持流畅。 */
fun sampleMarkerColor(source: PixelSource, marker: ColonyMarker): ColorSample {
    val radius = max(1.5, marker.radius * 0.82)
    val step = max(1, floor(radius / 12).toInt())
    val minX = max(0, floor(marker.x - radius).toInt())
    val maxX = min(source.width - 1, ceil(marker.x + radius).toInt())
    val minY = max(0, floor(marker.y - radius).toInt())
    val maxY = min(source.height - 1, ceil(marker.y + radius).toInt())
    val radiusSq = radius * radius
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var count = 0
    var y = minY
    while (y <= maxY) {
        val dy = (y - marker.y).toDouble()
        var x = minX
        while (x <= maxX) {
            val dx = (x - marker.x).toDouble()
            if (dx * dx + dy * dy > radiusSq) { x += step; continue }
            val offset = (y * source.width + x) * 4
            sumR += source.data[offset]
            sumG += source.data[offset + 1]
            sumB += source.data[offset + 2]
            count++
            x += step
        }
        y += step
    }
    if (count == 0) {
        val cx = max(0, min(source.width - 1, marker.x.roundToInt()))
        val cy = max(0, min(source.height - 1, marker.y.roundToInt()))
        val offset = (cy * source.width + cx) * 4
        sumR = source.data[offset].toDouble()
        sumG = source.data[offset + 1].toDouble()
        sumB = source.data[offset + 2].toDouble()
        count = 1
    }
    val r = sumR / count
    val g = sumG / count
    val b = sumB / count
    val hsv = rgbToHsv(r, g, b)
    return ColorSample(r, g, b, hsv.first, hsv.second, hsv.third)
}

/** 分类一个采样标记。图像级颜色检查对灰度输入设门。 */
fun classifyColor(sample: ColorSample, imageHasColor: Boolean): ColonyClass {
    if (!imageHasColor) return CLASS_UNCLASSIFIED
    val mx = max(sample.r, max(sample.g, sample.b))
    val mn = min(sample.r, min(sample.g, sample.b))
    val chroma = mx - mn
    if (mx < 18 || chroma < 6) return CLASS_UNCLASSIFIED
    val saturation = if (mx > 0) chroma / mx else 0.0
    // 淡色/发白的生长物：低饱和但明显比平板亮。
    if (saturation <= 0.28 && mx >= 70) return CLASS_WHITE
    // X-gal 蓝色跨越青色到紫色；要求可见色度。
    if (saturation >= 0.16 && sample.hue >= 155 && sample.hue <= 310 && mx >= 28) return CLASS_BLUE
    return CLASS_UNCLASSIFIED
}

data class ClassifyResult(
    val imageHasColor: Boolean,
    val classified: Int,
)

/**
 * 为每个标记测量 RGB/色相并更新类别（用户手动覆盖过的除外）。
 * 返回源图像是否有可用颜色。
 */
fun classifyMarkers(source: PixelSource, markers: List<ColonyMarker>): Pair<ClassifyResult, List<ColonyMarker>> {
    val imageHasColor = canvasHasColor(source)
    if (markers.isEmpty()) return ClassifyResult(imageHasColor, 0) to markers
    var classified = 0
    val out = markers.map { marker ->
        val sample = sampleMarkerColor(source, marker)
        var next = marker.copy(
            meanR = sample.r, meanG = sample.g, meanB = sample.b,
            hue = sample.hue, saturation = sample.saturation,
        )
        if (!marker.classManual) {
            val cls = classifyColor(sample, imageHasColor)
            if (cls != CLASS_UNCLASSIFIED) classified++
            next = next.copy(colonyClass = cls)
        }
        next
    }
    return ClassifyResult(imageHasColor, classified) to out
}

/** 关闭可选分类模式时重置类别。 */
fun clearClassification(markers: List<ColonyMarker>): List<ColonyMarker> =
    markers.map { it.copy(colonyClass = CLASS_UNCLASSIFIED, classManual = false) }

/** 恢复项目时使用的低成本图像级颜色检查。 */
fun detectImageColor(source: PixelSource): Boolean = canvasHasColor(source)
