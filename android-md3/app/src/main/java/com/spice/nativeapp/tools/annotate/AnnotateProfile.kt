package com.spice.nativeapp.tools.annotate

/*
 * 强度剖面：从渲染后的 RGB 合成图上沿线采样，1:1 移植 main 的
 * src/lib/tools/annotate/profile.ts。采样刻意与绘制分离：源位图只含合成
 * 后的面板像素，测量线与其他标注不会干扰被测数据。
 */
import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

const val PROFILE_DEFAULT_SAMPLES = 192
const val PROFILE_MIN_SAMPLES = 16
const val PROFILE_MAX_SAMPLES = 1024

class ProfilePoint(
    val index: Int,
    /** Position along the segment, 0..1. */
    val t: Double,
    val x: Double,
    val y: Double,
    val distancePx: Double,
    val distanceMm: Double,
    /** Rec. 709 luminance of the composite pixel, 0..255. */
    val intensity: Double,
    /** Intensity divided by 255. */
    val normalized: Double,
)

class ProfileSeries(
    val annotationId: String,
    val name: String,
    val color: String,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val samples: Int,
    val points: List<ProfilePoint>,
    val min: Double,
    val max: Double,
    val mean: Double,
)

private fun luminance(r: Double, g: Double, b: Double): Double =
    0.2126 * r + 0.7152 * g + 0.0722 * b

private fun bitmapRgba(bitmap: Bitmap): IntArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    return pixels
}

/**
 * Sample evenly spaced points along a line from a composite pixel buffer.
 * Coordinates are figure CSS pixels; the buffer must use the same space.
 */
fun sampleProfile(
    ann: ProfileAnnotation,
    pixels: IntArray,
    width: Int,
    height: Int,
    name: String = "Profile",
): ProfileSeries {
    val count = clamp(ann.samples.toDouble(), PROFILE_MIN_SAMPLES.toDouble(), PROFILE_MAX_SAMPLES.toDouble()).roundToInt()
    val dx = ann.x2 - ann.x1
    val dy = ann.y2 - ann.y1
    val lengthPx = hypot(dx, dy)
    val points = ArrayList<ProfilePoint>(count)
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    var sum = 0.0

    for (index in 0 until count) {
        val t = if (count <= 1) 0.0 else index.toDouble() / (count - 1)
        val x = ann.x1 + dx * t
        val y = ann.y1 + dy * t
        val px = clamp(x, 0.0, max(0.0, (width - 1).toDouble())).roundToInt()
        val py = clamp(y, 0.0, max(0.0, (height - 1).toDouble())).roundToInt()
        val offset = py * width + px
        // JS data[offset] ?? 0 越界语义
        val c = pixels.getOrNull(offset) ?: 0
        val intensity = luminance(
            (c shr 16 and 0xff).toDouble(),
            (c shr 8 and 0xff).toDouble(),
            (c and 0xff).toDouble(),
        )
        val distancePx = lengthPx * t
        points.add(
            ProfilePoint(
                index = index, t = t, x = x, y = y,
                distancePx = distancePx, distanceMm = distancePx / PX_PER_MM,
                intensity = intensity, normalized = intensity / 255,
            )
        )
        if (intensity < min) min = intensity
        if (intensity > max) max = intensity
        sum += intensity
    }

    return ProfileSeries(
        annotationId = ann.id,
        name = name,
        color = ann.color,
        x1 = ann.x1, y1 = ann.y1, x2 = ann.x2, y2 = ann.y2,
        samples = count,
        points = points,
        min = if (points.isNotEmpty()) min else 0.0,
        max = if (points.isNotEmpty()) max else 0.0,
        mean = if (points.isNotEmpty()) sum / points.size else 0.0,
    )
}

/** Render the composite and sample one profile annotation from it. */
fun computeProfile(
    ann: ProfileAnnotation,
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
    name: String = "Profile",
): ProfileSeries {
    val width = max(1, geom.width.roundToInt())
    val height = max(1, geom.height.roundToInt())
    val canvas = renderCompositeFigure(project, geom, resolve, 1.0)
    val pixels = bitmapRgba(canvas)
    return sampleProfile(ann, pixels, width, height, name)
}

fun collectProfiles(
    project: AnnotateProject,
    geom: FigureGeometry,
    resolve: ArtResolver,
): List<ProfileSeries> {
    val annotations = project.annotations.filterIsInstance<ProfileAnnotation>()
    if (annotations.isEmpty()) return emptyList()
    val width = max(1, geom.width.roundToInt())
    val height = max(1, geom.height.roundToInt())
    val canvas = renderCompositeFigure(project, geom, resolve, 1.0)
    val pixels = bitmapRgba(canvas)
    return annotations.mapIndexed { index, ann ->
        sampleProfile(ann, pixels, width, height, "Profile ${index + 1}")
    }
}

private fun csvCell(value: String): String =
    if (Regex("[\",\\r\\n]").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value

private fun jsFixed(value: Double, digits: Int): String =
    String.format(Locale.US, "%." + digits + "f", value)

fun profileCsv(series: List<ProfileSeries>): String {
    val header =
        "profile,annotation_id,index,distance_px,distance_mm,x_px,y_px,intensity_0_255,intensity_norm"
    val rows = mutableListOf(header)
    for (item in series) {
        for (point in item.points) {
            rows.add(
                listOf(
                    csvCell(item.name),
                    csvCell(item.annotationId),
                    point.index.toString(),
                    jsFixed(point.distancePx, 3),
                    jsFixed(point.distanceMm, 4),
                    jsFixed(point.x, 2),
                    jsFixed(point.y, 2),
                    jsFixed(point.intensity, 3),
                    jsFixed(point.normalized, 5),
                ).joinToString(","),
            )
        }
    }
    return rows.joinToString("\n") + "\n"
}

/** SVG path for a compact plot, normalised to the supplied box. */
fun profilePlotPath(series: ProfileSeries, width: Double, height: Double): String {
    if (series.points.isEmpty()) return ""
    val pad = 2.0
    val innerW = max(1.0, width - pad * 2)
    val innerH = max(1.0, height - pad * 2)
    return series.points
        .mapIndexed { index, point ->
            val x = pad + (index.toDouble() / max(1.0, (series.points.size - 1).toDouble())) * innerW
            val y = pad + (1 - point.normalized) * innerH
            "${if (index == 0) "M" else "L"}${jsFixed(x, 2)} ${jsFixed(y, 2)}"
        }
        .joinToString(" ")
}

fun profileStatsText(series: ProfileSeries): String =
    "${jsFixed(series.min, 1)} / ${jsFixed(series.mean, 1)} / ${jsFixed(series.max, 1)}"
