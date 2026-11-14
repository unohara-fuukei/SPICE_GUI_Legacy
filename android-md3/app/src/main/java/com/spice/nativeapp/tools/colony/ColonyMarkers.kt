package com.spice.nativeapp.tools.colony

/*
 * 标记簿记：统计、直方图、编号与 CSV 输出，1:1 移植 main 的
 * src/lib/tools/colony/markers.ts。
 * 泊松 95% 区间 = 精确 Garwood 区间：lower = 0.5·chi2(0.025, 2k)，
 * upper = 0.5·chi2(0.975, 2k+2)，卡方分位数用正则化不完全伽马函数求取。
 */
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MarkerNumbering(val labels: Map<Int, Int>)

/** 覆盖层编号：只对计入的标记，按从上到下、从左到右排序。 */
fun numberMarkers(markers: List<ColonyMarker>): MarkerNumbering {
    val included = markers.filter { it.included }.sortedWith(compareBy({ it.y }, { it.x }))
    val labels = mutableMapOf<Int, Int>()
    included.forEachIndexed { index, marker -> labels[marker.id] = index + 1 }
    return MarkerNumbering(labels)
}

fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

fun computeStats(markers: List<ColonyMarker>): ColonyStats {
    val included = markers.filter { it.included }
    val areas = included.map { it.area }
    val intensities = included.map { it.meanIntensity }
    val circularities = included.map { it.circularity }
    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size
    return ColonyStats(
        total = markers.size,
        included = included.size,
        excluded = markers.size - included.size,
        manual = markers.count { it.source == SOURCE_MANUAL },
        meanArea = mean(areas),
        medianArea = median(areas),
        meanIntensity = mean(intensities),
        meanCircularity = mean(circularities),
    )
}

data class ColonyClassCounts(
    val blue: Int,
    val white: Int,
    val unclassified: Int,
)

/** 计入标记中蓝、白与未分类菌落的数量。 */
fun countClasses(markers: List<ColonyMarker>): ColonyClassCounts {
    val included = markers.filter { it.included }
    return ColonyClassCounts(
        blue = included.count { it.colonyClass == CLASS_BLUE },
        white = included.count { it.colonyClass == CLASS_WHITE },
        unclassified = included.count { it.colonyClass != CLASS_BLUE && it.colonyClass != CLASS_WHITE },
    )
}

data class Histogram(
    val counts: List<Int>,
    val min: Double,
    val max: Double,
    val binWidth: Double,
    val peak: Int,
    val total: Int,
)

/** SVG 图表使用的固定分箱面积直方图。 */
fun buildHistogram(areas: List<Double>, binCount: Int = 18): Histogram {
    if (areas.isEmpty()) return Histogram(emptyList(), 0.0, 0.0, 0.0, 0, 0)
    var minV = Double.POSITIVE_INFINITY
    var maxV = Double.NEGATIVE_INFINITY
    for (value in areas) {
        if (value < minV) minV = value
        if (value > maxV) maxV = value
    }
    if (minV == maxV) {
        minV = max(0.0, minV - 1)
        maxV = maxV + 1
    }
    val bins = max(4, min(binCount, kotlin.math.ceil(sqrt(areas.size.toDouble())).toInt() + 4))
    val binWidth = (maxV - minV) / bins
    val counts = IntArray(bins)
    for (value in areas) {
        var index = floor((value - minV) / binWidth).toInt()
        if (index < 0) index = 0
        if (index >= bins) index = bins - 1
        counts[index]++
    }
    return Histogram(
        counts = counts.toList(), min = minV, max = maxV, binWidth = binWidth,
        peak = max(1, counts.maxOrNull() ?: 0), total = areas.size,
    )
}

private fun roundCsv(value: Double, digits: Int): String {
    if (!value.isFinite()) return ""
    return String.format(Locale.US, "%.${digits}f", value)
}

/**
 * 每个标记一行 CSV（含被排除的标记）。当前标记记录追加类别列；
 * 无类别数据的旧数据保持原十列形状以向后兼容。
 */
fun markersToCsv(markers: List<ColonyMarker>, numbering: MarkerNumbering): String {
    val withClass = markers.isNotEmpty()
    val header = if (withClass)
        "id,number,x,y,area_px2,radius_px,mean_intensity,circularity,included,source,class,mean_r,mean_g,mean_b,hue,saturation,class_manual"
    else
        "id,number,x,y,area_px2,radius_px,mean_intensity,circularity,included,source"
    val rows = markers.map { marker ->
        val number = numbering.labels[marker.id]?.toString() ?: ""
        val values = mutableListOf<String>(
            marker.id.toString(),
            number,
            roundCsv(marker.x, 2),
            roundCsv(marker.y, 2),
            roundCsv(marker.area, 2),
            roundCsv(marker.radius, 2),
            roundCsv(marker.meanIntensity, 2),
            roundCsv(marker.circularity, 4),
            if (marker.included) "1" else "0",
            marker.source,
        )
        if (withClass) {
            values.add(marker.colonyClass)
            values.add(roundCsv(marker.meanR, 1))
            values.add(roundCsv(marker.meanG, 1))
            values.add(roundCsv(marker.meanB, 1))
            values.add(roundCsv(marker.hue, 1))
            values.add(roundCsv(marker.saturation, 3))
            values.add(if (marker.classManual) "1" else "0")
        }
        values.joinToString(",")
    }
    return (listOf(header) + rows).joinToString("\n")
}

/** 由计入数、稀释倍数与涂布体积(mL)计算 CFU/mL。 */
fun computeCfu(count: Int, dilutionFactor: Double, platedVolume: Double): Double? {
    if (!dilutionFactor.isFinite() || !platedVolume.isFinite()) return null
    if (dilutionFactor <= 0 || platedVolume <= 0) return null
    return (count * dilutionFactor) / platedVolume
}

data class Interval(val low: Double, val high: Double)

// ------------------------------------------------- Poisson 95% interval

private fun logGamma(x: Double): Double {
    val coefficients = doubleArrayOf(
        76.18009172947146,
        -86.50532032941677,
        24.01409824083091,
        -1.231739572450155,
        0.1208650973866179e-2,
        -0.5395239384953e-5,
    )
    var y = x
    var tmp = x + 5.5
    tmp -= (x + 0.5) * ln(tmp)
    var series = 1.000000000190015
    for (c in coefficients) {
        y += 1
        series += c / y
    }
    return -tmp + ln((2.5066282746310005 * series) / x)
}

private fun gammaSeries(a: Double, x: Double): Double {
    var ap = a
    var sum = 1 / a
    var del = sum
    var n = 1
    while (n < 200) {
        ap += 1
        del *= x / ap
        sum += del
        if (abs(del) < abs(sum) * 1e-12) break
        n++
    }
    return sum * kotlin.math.exp(-x + a * ln(x) - logGamma(a))
}

private fun gammaContinuedFraction(a: Double, x: Double): Double {
    val tiny = 1e-300
    var b = x + 1 - a
    var c = 1 / tiny
    var d = 1 / b
    var h = d
    var i = 1
    while (i < 200) {
        val an = (-i * (i - a)).toDouble()
        b += 2
        d = an * d + b
        if (abs(d) < tiny) d = tiny
        c = b + an / c
        if (abs(c) < tiny) c = tiny
        d = 1 / d
        val del = d * c
        h *= del
        if (abs(del - 1) < 1e-12) break
        i++
    }
    return kotlin.math.exp(-x + a * ln(x) - logGamma(a)) * h
}

private fun regularizedGammaP(a: Double, x: Double): Double {
    if (x <= 0) return 0.0
    if (x < a + 1) return gammaSeries(a, x)
    return 1 - gammaContinuedFraction(a, x)
}

private fun chiSquareCdf(x: Double, df: Double): Double {
    if (x <= 0) return 0.0
    return regularizedGammaP(df / 2, x / 2)
}

private fun chiSquareQuantile(p: Double, df: Double): Double {
    if (df <= 0) return 0.0
    var low = 0.0
    var high = max(1.0, df + 10 * sqrt(2 * df) + 20)
    while (chiSquareCdf(high, df) < p) high *= 2
    repeat(80) {
        val mid = (low + high) / 2
        if (chiSquareCdf(mid, df) < p) low = mid else high = mid
    }
    return (low + high) / 2
}

/** 菌落数的精确泊松 95% 置信区间。 */
fun poissonCi95(count: Int): Interval {
    val k = max(0, count)
    val low = if (k == 0) 0.0 else 0.5 * chiSquareQuantile(0.025, (2 * k).toDouble())
    val high = 0.5 * chiSquareQuantile(0.975, (2 * k + 2).toDouble())
    return Interval(low, high)
}

/** 按同一稀释倍数把泊松计数区间缩放到 CFU/mL。 */
fun cfuCi95(count: Int, dilutionFactor: Double, platedVolume: Double): Interval? {
    if (!dilutionFactor.isFinite() || !platedVolume.isFinite()) return null
    if (dilutionFactor <= 0 || platedVolume <= 0) return null
    val ci = poissonCi95(count)
    val factor = dilutionFactor / platedVolume
    return Interval(ci.low * factor, ci.high * factor)
}

fun formatInterval(interval: Interval?): String {
    if (interval == null) return "—"
    return "${formatScientific(interval.low)} – ${formatScientific(interval.high)}"
}

data class StatisticsRow(
    val label: String,
    val count: Int,
    val countLow: Double,
    val countHigh: Double,
    val blue: Int,
    val white: Int,
    val unclassified: Int,
    val cfu: Double?,
    val cfuLow: Double?,
    val cfuHigh: Double?,
)

/** 单板与批量导出共用的统计 CSV。 */
fun statisticsToCsv(rows: List<StatisticsRow>): String {
    val header =
        "label,count,count_ci95_low,count_ci95_high,blue,white,unclassified,cfu_per_ml,cfu_ci95_low,cfu_ci95_high"
    val lines = rows.map { row ->
        listOf(
            csvCell(row.label),
            roundCsv(row.count.toDouble(), 0),
            roundCsv(row.countLow, 2),
            roundCsv(row.countHigh, 2),
            row.blue.toString(),
            row.white.toString(),
            row.unclassified.toString(),
            roundCsv(row.cfu ?: Double.NaN, 4),
            roundCsv(row.cfuLow ?: Double.NaN, 4),
            roundCsv(row.cfuHigh ?: Double.NaN, 4),
        ).joinToString(",")
    }
    return (listOf(header) + lines).joinToString("\n")
}

private fun csvCell(value: String): String =
    if (Regex("[\",\\r\\n]").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value

/**
 * 对应 JS 的 value.toExponential(2)：1.00e+5（指数无前导零）。
 * 替换 'e+'/'e-' 为 ' × 10^' / ' × 10^-'。
 */
fun formatScientific(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    if (value == 0.0) return "0"
    val formatted = String.format(Locale.US, "%.2e", value)
    val e = formatted.indexOf('e')
    val mantissa = formatted.substring(0, e)
    var exp = formatted.substring(e + 1)
    // JS 指数无前导零：+05 → +5
    val sign = if (exp.startsWith("-")) "-" else "+"
    val digits = exp.removePrefix("-").removePrefix("+").trimStart('0')
    exp = sign + (if (digits.isEmpty()) "0" else digits)
    val joined = "${mantissa}e$exp"
    return joined.replace("e+", " × 10^").replace("e-", " × 10^-")
}

fun formatArea(value: Double): String {
    if (!value.isFinite() || value <= 0) return "0"
    if (value >= 10000) return String.format(Locale.US, "%,d", value.toLong().toDouble().toLong())
    return String.format(Locale.US, "%.${if (value >= 100) 0 else 1}f", value)
}

private var idCounter = 1

/** Id 在多轮检测间保持唯一，手动标记的编号得以保留。 */
fun nextMarkerId(markers: List<ColonyMarker>): Int {
    var mx = idCounter - 1
    for (marker in markers) if (marker.id > mx) mx = marker.id
    idCounter = mx + 1
    return idCounter++
}
