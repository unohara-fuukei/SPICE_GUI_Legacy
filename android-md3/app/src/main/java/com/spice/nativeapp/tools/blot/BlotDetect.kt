package com.spice.nativeapp.tools.blot

/*
 * Lane and band detection for blots. 1:1 port of main's
 * src/lib/tools/blot/detect.ts — dark bands on a light background are the
 * signal, so every profile is inverted before peak picking. Pure functions
 * over the raw grayscale plane.
 */

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Peak(val frac: Double, val value: Double, val prominence: Double)

private class PeakOptions(
    val prominenceStd: Double = 0.6,
    val minProminence: Double = 0.01,
    val minGap: Double = 4.0,
    val smooth: Double = 3.0,
)

private fun smoothProfile(profile: FloatArray, window: Double): FloatArray {
    if (window <= 1) return profile
    val half = floor(window / 2).toInt()
    val out = FloatArray(profile.size)
    for (i in profile.indices) {
        var sum = 0f
        var n = 0
        val lo = max(0, i - half)
        val hi = min(profile.size - 1, i + half)
        for (j in lo..hi) {
            sum += profile[j]
            n++
        }
        out[i] = sum / max(1, n).coerceAtLeast(1)
    }
    return out
}

private class MeanStd(val mean: Double, val std: Double)

private fun meanStd(values: FloatArray): MeanStd {
    if (values.isEmpty()) return MeanStd(0.0, 0.0)
    var sum = 0.0
    for (v in values) sum += v
    val mean = sum / values.size
    var sq = 0.0
    for (v in values) sq += (v - mean) * (v - mean)
    return MeanStd(mean, sqrtChecked(sq / values.size))
}

private fun sqrtChecked(v: Double): Double = kotlin.math.sqrt(v)

private fun findPeaks(profile: FloatArray, opts: PeakOptions = PeakOptions()): List<Peak> {
    val smoothed = smoothProfile(profile, opts.smooth)
    val n = smoothed.size
    if (n < 3) return emptyList()
    val ms = meanStd(smoothed)
    val prominenceStd = opts.prominenceStd
    val minProminence = opts.minProminence
    val minGap = max(1.0, opts.minGap).toInt()
    val threshold = ms.mean + prominenceStd * ms.std
    val candidates = mutableListOf<Peak>()
    for (i in 1 until n - 1) {
        val v = smoothed[i].toDouble()
        if (v < threshold) continue
        // Lenient on the rising side keeps flat-topped plateaus to one peak.
        if (v < smoothed[i - 1] || v <= smoothed[i + 1]) continue
        val lo = max(0, i - minGap * 3)
        val hi = min(n, i + minGap * 3 + 1)
        var base = v
        for (j in lo until hi) base = min(base, smoothed[j].toDouble())
        val prominence = v - base
        if (prominence < minProminence) continue
        candidates.add(Peak(i.toDouble() / (n - 1), v, prominence))
    }
    candidates.sortByDescending { it.prominence }
    val kept = mutableListOf<Peak>()
    val minFrac = minGap.toDouble() / max(1, n - 1)
    for (cand in candidates) {
        if (kept.all { abs(it.frac - cand.frac) >= minFrac }) kept.add(cand)
    }
    return kept.sortedBy { it.frac }
}

/** Mean inverted intensity per column inside a row range. */
fun columnSignal(gray: FloatArray, width: Int, height: Int, y0: Double, y1: Double): FloatArray {
    val out = FloatArray(width)
    val top = clamp(kotlin.math.round(y0), 0.0, max(0.0, (height - 1).toDouble()).toInt().toDouble()).toInt()
    val bottom = clamp(kotlin.math.round(y1).toDouble(), (top + 1).toDouble(), height.toDouble()).toInt()
    for (x in 0 until width) {
        var sum = 0.0
        for (y in top until bottom) sum += 1.0 - gray[y * width + x]
        out[x] = (sum / max(1, bottom - top)).toFloat()
    }
    return out
}

/** Mean inverted intensity per row inside a column range. */
fun rowSignal(gray: FloatArray, width: Int, height: Int, x0: Double, x1: Double): FloatArray {
    val out = FloatArray(height)
    val left = clamp(kotlin.math.round(x0).toDouble(), 0.0, max(0.0, (width - 1).toDouble())).toInt()
    val right = clamp(kotlin.math.round(x1).toDouble(), (left + 1).toDouble(), width.toDouble()).toInt()
    for (y in 0 until height) {
        var sum = 0.0
        for (x in left until right) sum += 1.0 - gray[y * width + x]
        out[y] = (sum / max(1, right - left)).toFloat()
    }
    return out
}

/** Lower-envelope baseline via a grayscale opening (see detect.ts comment). */
private fun openBaseline(profile: FloatArray, window: Int): FloatArray {
    val n = profile.size
    if (window < 3 || n < 3) return profile
    val half = max(1, window / 2)
    val eroded = FloatArray(n)
    for (i in 0 until n) {
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        var v = Float.MAX_VALUE
        for (j in lo..hi) v = min(v, profile[j])
        eroded[i] = v
    }
    val out = FloatArray(n)
    for (i in 0 until n) {
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        var v = -Float.MAX_VALUE
        for (j in lo..hi) v = max(v, eroded[j])
        out[i] = profile[i] - v
    }
    return out
}

class DetectedLane(val x: Double, val width: Double)

/** Merge peaks that sit inside one lane (see detect.ts comment). */
private fun mergeClosePeaks(peaks: List<Peak>): List<Peak> {
    if (peaks.size < 3) return peaks
    val gaps = mutableListOf<Double>()
    for (i in 1 until peaks.size) gaps.add(peaks[i].frac - peaks[i - 1].frac)
    gaps.sort()
    val medianGap = if (gaps.size > 0) gaps[gaps.size / 2] else 0.0
    if (medianGap <= 0) return peaks
    val merged = mutableListOf<Peak>()
    for (peak in peaks) {
        val last = merged.lastOrNull()
        if (last != null && peak.frac - last.frac < medianGap * 0.55) {
            if (peak.prominence > last.prominence) merged[merged.size - 1] = peak
        } else {
            merged.add(peak)
        }
    }
    return merged
}

/** Column-profile lane finding inside the visible crop. */
fun detectLanes(gray: FloatArray, width: Int, height: Int, crop: BlotCrop): List<DetectedLane> {
    val y0 = crop.top * height
    val y1 = (1 - crop.bottom) * height
    val raw = columnSignal(gray, width, height, y0, y1)

    // Scanned blots often carry a dark frame or shadow on the outer columns.
    // Those peaks are huge enough to raise the mean/std threshold and hide the
    // real lanes, so keep the edges out of both the baseline and the stats.
    val edge = max(1, (width * 0.02).roundToInt())
    val usableStart = if (width > edge * 4) edge else 0
    val usableEnd = if (width > edge * 4) width - edge else width
    val usable = raw.copyOfRange(usableStart, usableEnd)
    val profile = openBaseline(usable, max(9, (width * 0.1).roundToInt()))
    val rawPeaks = findPeaks(
        profile,
        PeakOptions(
            prominenceStd = 0.8,
            minProminence = 0.01,
            minGap = max(6.0, (width * 0.03).toDouble()),
            smooth = max(3.0, (width * 0.03).toDouble()),
        )
    )
    val mapped = rawPeaks
        .map { peak -> Peak((peak.frac * usable.size + edge) / width, peak.value, peak.prominence) }
        .filter { it.frac > 0.02 && it.frac < 0.98 }
    val peaks = mergeClosePeaks(mapped)
    if (peaks.isEmpty()) return emptyList()
    val xs = peaks.map { it.frac }
    val gaps = mutableListOf<Double>()
    for (i in 1 until xs.size) gaps.add(xs[i] - xs[i - 1])
    gaps.sort()
    val medianGap = if (gaps.size > 0) gaps[gaps.size / 2] else 0.06
    val laneWidth = max(0.012, min(0.16, medianGap * 0.62))
    return xs.map { DetectedLane(it, laneWidth) }
}

private fun fwhm(profile: FloatArray, index: Int): Double {
    val peak = profile[index].toDouble()
    var meanValue = 0.0
    for (v in profile) meanValue += v
    meanValue /= max(1, profile.size).coerceAtLeast(1)
    val half = meanValue + (peak - meanValue) * 0.5
    var l = index
    var r = index
    while (l > 0 && profile[l - 1] >= half) l--
    while (r < profile.size - 1 && profile[r + 1] >= half) r++
    return (r - l + 1).toDouble() / max(1, profile.size).coerceAtLeast(1)
}

class DetectedBand(val y: Double, val height: Double)

/** Row-profile band finding inside one lane. */
fun detectBands(
    gray: FloatArray,
    width: Int,
    height: Int,
    lane: BlotLane,
    crop: BlotCrop,
): List<DetectedBand> {
    val x0 = (lane.x - lane.width / 2) * width
    val x1 = (lane.x + lane.width / 2) * width
    val raw = rowSignal(gray, width, height, x0, x1)
    val top = (crop.top * height).roundToInt()
    val bottom = ((1 - crop.bottom) * height).roundToInt()
    val visStart = top
    val visEnd = max(top + 3, bottom).coerceAtMost(height)
    val visible = raw.copyOfRange(visStart.coerceIn(0, height), visEnd)
    val profile = openBaseline(visible, max(7, (height * 0.12).roundToInt()))
    val peaks = findPeaks(
        profile,
        PeakOptions(
            prominenceStd = 0.62,
            minProminence = 0.004,
            minGap = max(4.0, (height * 0.008).toDouble()),
            smooth = 3.0,
        )
    )
    return peaks.take(24).map { p ->
        val local = (p.frac * max(0, profile.size - 1)).roundToInt()
        val thickness = clamp(fwhm(profile, local), 0.008, 0.12)
        DetectedBand(
            y = clamp((top + p.frac * (bottom - top)) / height, 0.002, 0.998),
            height = max(0.012, thickness),
        )
    }
}

fun bandsFromDetection(found: List<DetectedBand>, laneWidth: Double): List<BlotBand> {
    return found.map { band ->
        BlotBand(
            id = newId("band"),
            y = band.y,
            height = band.height,
            width = max(0.008, laneWidth * 0.9),
            manual = false,
            excluded = false,
        )
    }
}

fun lanesFromDetection(
    found: List<DetectedLane>,
    previous: List<BlotLane>,
    defaultGroup: String,
): List<BlotLane> {
    val prior = previous.sortedBy { it.x }
    return found.mapIndexed { index, lane ->
        BlotLane(
            id = newId("lane"),
            x = lane.x,
            width = lane.width,
            name = prior.getOrNull(index)?.name ?: "${index + 1}",
            sample = prior.getOrNull(index)?.sample ?: "",
            group = prior.getOrNull(index)?.group ?: defaultGroup,
            excluded = false,
            bands = mutableListOf(),
        )
    }
}

/** Deterministic initial lane count: spread lanes evenly in the crop. */
fun lanesFromCount(
    count: Int,
    previous: List<BlotLane>,
    crop: BlotCrop,
    defaultGroup: String,
): List<BlotLane> {
    val prior = previous.sortedBy { it.x }
    val left = crop.left + (1 - crop.left - crop.right) * 0.04
    val right = 1 - crop.right - (1 - crop.left - crop.right) * 0.04
    val laneWidth = min(0.12, ((right - left) / max(1, count)) * 0.62)
    val next = mutableListOf<BlotLane>()
    for (i in 0 until count) {
        val t = if (count == 1) 0.5 else i.toDouble() / (count - 1)
        val x = left + (right - left) * t
        val old = prior.getOrNull(i)
        next.add(
            BlotLane(
                id = old?.id ?: newId("lane"),
                x = x,
                width = old?.width ?: laneWidth,
                name = old?.name ?: "${i + 1}",
                sample = old?.sample ?: "",
                group = old?.group ?: defaultGroup,
                excluded = old?.excluded ?: false,
                bands = old?.bands?.map { it.copy() }?.toMutableList() ?: mutableListOf(),
            )
        )
    }
    return next
}

/** Landmark positions for the chart, independent of pixel work. */
fun groupNames(lanes: List<BlotLane>): List<String> {
    val out = mutableListOf<String>()
    for (lane in lanes) {
        val name = lane.group.trim()
        if (name.isNotEmpty() && !out.contains(name)) out.add(name)
    }
    return out
}
