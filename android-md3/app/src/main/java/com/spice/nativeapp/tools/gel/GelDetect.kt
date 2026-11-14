package com.spice.nativeapp.tools.gel

/**
 * High-level gel detection: lane finding, band finding, gel-type inference and
 * marker-lane matching. 1:1 移植自原版 src/lib/tools/gel/detect.ts。
 *
 * 纯函数 + 一个灰度平面身份缓存。原版的 native.ts Rust 二值化在 Android
 * 侧不可用，走 binarizeGray 纯算法 fallback 路径（与原版 JS fallback 一致）。
 */
import java.util.IdentityHashMap

data class BandPeak(
    /** Normalised y in the processed full image. */
    val y: Double,
    val intensity: Double,
    /** Full width at half maximum, as a fraction of image height. */
    val width: Double,
)

data class GelTypeGuess(
    val kind: String,  // "dna" | "protein"
    /** Reason codes, translated by the UI. */
    val reasons: List<String>,  // "blue" | "red" | "wide" | "many" | "neutral"
)

data class MarkerMatch(
    val laneId: String,
    val ladder: LadderDef,
    val fit: PolyFit,
    /** Sizes assigned to the lane's bands, top to bottom. */
    val sizes: List<Double>,
    val score: Double,
)

data class JointMarkerResult(
    val ladder: LadderDef,
    val laneIds: List<String>,
    val fit: PolyFit,
    /** laneId -> sizes assigned to its bands, top to bottom. */
    val assignments: Map<String, List<Double>>,
    val score: Double,
)

data class MatchOptions(
    val kind: String,  // "dna" | "protein"
    val ladders: List<LadderDef>,
    val favorites: List<String> = emptyList(),
)

data class EstimatedSize(val size: Double, val approx: Boolean)

object GelDetect {

    private class FallbackEntry(val width: Int, val height: Int, val signal: FloatArray)

    /** 对应原版 detect.ts 的 fallbackCache（WeakMap）。 */
    private val fallbackCache = IdentityHashMap<FloatArray, HashMap<String, FallbackEntry>>()

    private fun fallbackKey(config: GelDetectConfig?): String =
        if (config != null) GelPresets.detectConfigKey(config) else "auto"

    /** Detection works on a binary signal plane, not raw grayscale. This is the
     *  synchronous lookup used by the profile math, with the pure threshold as
     *  the cold-start fallback (原版 native 缓存在 Android 上不存在). */
    fun signalPlane(gray: FloatArray, width: Int, height: Int, config: GelDetectConfig? = null): FloatArray {
        val key = fallbackKey(config)
        val hit = fallbackCache[gray]?.get(key)
        if (hit != null && hit.width == width && hit.height == height) return hit.signal
        val signal = GelMath.binarizeGray(gray, width, height, config).signal
        val entries = fallbackCache.getOrPut(gray) { HashMap() }
        entries[key] = FallbackEntry(width, height, signal)
        return signal
    }

    /** Contiguous profile regions above `cut`, merging small gaps and dropping
     *  specks. Returned as inclusive [start, end] column ranges. */
    private fun activeRuns(profile: FloatArray, cut: Double, minWidth: Int, mergeGap: Int): List<IntArray> {
        val runs = ArrayList<IntArray>()
        var start = -1
        for (i in profile.indices) {
            val on = profile[i] >= cut
            if (on && start < 0) start = i
            else if (!on && start >= 0) {
                runs.add(intArrayOf(start, i - 1))
                start = -1
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, profile.size - 1))
        val merged = ArrayList<IntArray>()
        for (run in runs) {
            val prev = merged.lastOrNull()
            if (prev != null && run[0] - prev[1] - 1 <= mergeGap) prev[1] = run[1]
            else merged.add(intArrayOf(run[0], run[1]))
        }
        return merged.filter { it[1] - it[0] + 1 >= minWidth }
    }

    /** Lane centres from the well row, corrected by band signal below it. Lanes
     *  that run together in crowded protein gels still separate at the wells. */
    fun detectLanesAuto(
        gray: FloatArray,
        width: Int,
        height: Int,
        protein: Boolean = false,
        config: GelDetectConfig? = null,
    ): List<DetectedLane> {
        val signal = signalPlane(gray, width, height, config)
        val wellTop = Math.floor(height * 0.02).toInt()
        val wellBottom = Math.floor(height * 0.2).toInt()
        val bodyTop = Math.floor(height * 0.15).toInt()
        val bodyBottom = Math.floor(height * 0.95).toInt()
        val wells = GelMath.columnProfile(signal, width, height, wellTop, wellBottom)
        val body = GelMath.columnProfile(signal, width, height, bodyTop, bodyBottom)
        // Normalise both profiles so the well row can guide the band signal.
        fun norm(p: FloatArray): DoubleArray {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (v in p) {
                lo = minOf(lo, v.toDouble())
                hi = maxOf(hi, v.toDouble())
            }
            val span = (hi - lo).takeIf { it != 0.0 } ?: 1.0
            val out = DoubleArray(p.size)
            for (i in p.indices) out[i] = (p[i] - lo) / span
            return out
        }
        val nw = norm(wells)
        val nb = norm(body)
        val combined = DoubleArray(width)
        for (i in 0 until width) combined[i] = 0.55 * nw[i] + 0.45 * nb[i]
        var peakMax = 0.0
        for (v in combined) peakMax = maxOf(peakMax, v)

        // A lane is a contiguous active run, not a local maximum: on real UV photos
        // the lane itself glows, so one lane spans a plateau that peak picking
        // splits into several false lanes. A low relative cut recovers the run; if
        // the lanes merge into one run, raise the cut before giving up.
        // Protein gels often run lanes together with no dark gap; there the peak
        // detector separates them better, so the run split is DNA-only.
        if (!protein) {
            val laneOpts = config?.lanes
            val minRunWidth = maxOf(3, Math.round(width * (laneOpts?.minWidth ?: 0.004)).toInt())
            val mergeRunGap = maxOf(1, Math.round(width * (laneOpts?.mergeGap ?: 0.005)).toInt())
            val baseCut = laneOpts?.cut ?: 0.08
            for (cutFraction in listOf(baseCut, baseCut * 2, baseCut * 3)) {
                val runs = activeRuns(
                    combined.map { it.toFloat() }.toFloatArray(),
                    peakMax * cutFraction, minRunWidth, mergeRunGap,
                )
                if (runs.size >= 2) {
                    return runs.map { r ->
                        val laneWidth = maxOf(0.008, minOf(0.2, (r[1] - r[0] + 1).toDouble() / width))
                        DetectedLane(x = (r[0] + r[1]) / 2.0 / maxOf(1, width - 1), width = laneWidth)
                    }
                }
            }
        }
        // Binary profiles have no intensity dynamic range; gate on prominence only,
        // otherwise one saturated ladder lane raises the mean and hides the samples.
        // The smoothing window is sized to a lane: without it, two bands in one lane
        // that sit a few pixels apart become two lanes.
        val minProminence = maxOf(0.01, peakMax * (config?.lanes?.prominence ?: 0.1))
        val minGap = maxOf(6, Math.round(width * (if (protein) 0.02 else 0.03)).toInt())
        val smoothFraction = (config?.lanes?.smooth ?: 0.02) * (if (protein) 0.75 else 1.0)
        val combinedF = combined.map { it.toFloat() }.toFloatArray()
        var peaks = GelMath.findPeaks(
            combinedF,
            PeakOptions(
                useThreshold = false,
                minProminence = minProminence,
                minGap = minGap,
                smooth = maxOf(3, Math.round(width * smoothFraction).toInt()),
            ),
        )
        if (peaks.size < 2) {
            peaks = GelMath.findPeaks(
                nb.map { it.toFloat() }.toFloatArray(),
                PeakOptions(
                    useThreshold = false,
                    minProminence = minProminence,
                    minGap = maxOf(6, Math.round(width * 0.01).toInt()),
                    smooth = 3,
                ),
            )
        }
        if (peaks.size < 2) return emptyList()
        val kept = GelMath.collapseFlatPeaks(combinedF, peaks)
        val xs = kept.map { it.frac }
        val gaps = ArrayList<Double>()
        for (i in 1 until xs.size) gaps.add(xs[i] - xs[i - 1])
        gaps.sort()
        val medianGap = if (gaps.isEmpty()) 0.05 else gaps[gaps.size / 2]
        val widthFraction = maxOf(0.008, minOf(0.2, medianGap * 0.62))
        return xs.map { DetectedLane(it, widthFraction) }
    }

    fun detectBandsInLane(
        gray: FloatArray,
        width: Int,
        height: Int,
        lane: DetectedLane,
        kind: String = "dna",
        config: GelDetectConfig? = null,
    ): List<BandPeak> {
        val x0 = Math.round((lane.x - lane.width / 2) * width).toInt()
        val x1 = Math.round((lane.x + lane.width / 2) * width).toInt()
        val signal = signalPlane(gray, width, height, config)
        val profile = GelMath.rowProfile(signal, width, height, x0, x1)
        val protein = kind == "protein"
        val prominence = config?.bands?.prominence ?: 0.5
        val gapFraction = (config?.bands?.minGap ?: 0.006) * (if (protein) 2.0 / 3.0 else 1.0)
        val smooth = config?.bands?.smooth ?: 3
        val peaks = GelMath.collapseFlatPeaks(
            profile,
            GelMath.findPeaks(
                profile,
                PeakOptions(
                    prominenceStd = prominence * (if (protein) 0.6 else 1.0),
                    minProminence = if (protein) 0.003 else 0.006,
                    minGap = maxOf(3, Math.round(height * gapFraction).toInt()),
                    smooth = if (protein) smooth + 2 else smooth,
                ),
            ),
        )
        // Full width at half maximum, used to tell broad protein bands from thin
        // DNA bands when the image carries no colour clue.
        var mean = 0.0
        for (v in profile) mean += v.toDouble()
        mean /= maxOf(1, profile.size)
        return peaks.take(40).map { p ->
            val idx = Math.round(p.frac * (profile.size - 1)).toInt()
            val half = mean + (p.value - mean) * 0.5
            var l = idx
            var r = idx
            while (l > 0 && profile[l - 1] >= half) l--
            while (r < profile.size - 1 && profile[r + 1] >= half) r++
            BandPeak(y = p.frac, intensity = p.value, width = (r - l + 1).toDouble() / maxOf(1, profile.size))
        }
    }

    /** Mean colour of the band pixels only. A UV gel's purple background would
     *  otherwise dominate the whole-image mean and vote "blue" (protein), even
     *  though the bands themselves are red/pink DNA. Falls back to the full image
     *  when the mask is missing or covers almost nothing. */
    private fun bandMeanRgb(argb: IntArray, mask: FloatArray?): GelMath.MeanRgb {
        // 原版条件：mask.length * 4 !== rgba.length（rgba 每像素 4 字节 → 即长度不等）。
        if (mask == null || mask.size != argb.size) return GelMath.meanRgb(argb)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var sat = 0.0
        var n = 0
        for (p in mask.indices) {
            if (mask[p] == 0f) continue
            val px = argb[p]
            val red = (px shr 16) and 0xFF
            val green = (px shr 8) and 0xFF
            val blue = px and 0xFF
            r += red
            g += green
            b += blue
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            sat += if (max == 0) 0.0 else (max - min).toDouble() / max
            n++
        }
        if (n < maxOf(64, (argb.size * 0.001).toInt())) return GelMath.meanRgb(argb)
        return GelMath.MeanRgb(r / n, g / n, b / n, sat / n)
    }

    /** Best-effort DNA/protein vote from colour and band character. */
    fun detectGelType(
        argb: IntArray,
        bandCount: Int,
        laneCount: Int,
        bandWidth: Double = 0.0,
        mask: FloatArray? = null,
    ): GelTypeGuess {
        val m = bandMeanRgb(argb, mask)
        val reasons = ArrayList<String>()
        val blue = m.b > m.r * 1.08 && m.b > m.g * 1.03 && m.sat > 0.04
        val red = m.r > m.b * 1.08 && m.sat > 0.04
        if (blue) reasons.add("blue")
        if (red) reasons.add("red")
        // A crowded gel with many evenly spaced bands is almost always a DNA ladder;
        // protein lysates show fewer, broader bands.
        val many = bandCount >= 12 && laneCount >= 3
        val wide = bandWidth >= 0.018 || (bandCount in 4..8 && laneCount <= 3 && bandWidth >= 0.012)
        if (many) reasons.add("many")
        if (wide) reasons.add("wide")
        if (reasons.isEmpty()) reasons.add("neutral")
        if (blue) return GelTypeGuess("protein", reasons)
        if (red || many) return GelTypeGuess("dna", reasons)
        if (wide) return GelTypeGuess("protein", reasons)
        return GelTypeGuess("dna", reasons)
    }

    private fun fitForSizes(bandsY: List<Double>, sizes: List<Double>): PolyFit? {
        val points = bandsY.mapIndexed { i, y -> GraySizePoint(y, sizes[i]) }
        val degree = if (points.size >= 8) 2 else 1
        return GelMath.robustFit(points, degree)
    }

    /** All contiguous windows of descending sizes that can pair with the bands.
     *  Missing faint bands at either end of a protein marker are tolerated. */
    private fun sizeWindows(ladder: LadderDef, count: Int, protein: Boolean): List<List<Double>> {
        val sizes = ladder.sizes
        if (count == sizes.size) return listOf(sizes)
        if (!protein || count > sizes.size) return emptyList()
        val out = ArrayList<List<Double>>()
        var start = 0
        while (start + count <= sizes.size) {
            out.add(sizes.subList(start, start + count).toList())
            start++
        }
        return out
    }

    /** Rank every lane/ladder pairing; the editor keeps the best ladder overall,
     *  matching several marker lanes jointly. */
    fun findMarkerMatches(
        lanes: List<GelLane>,
        opts: MatchOptions,
    ): List<MarkerMatch> {
        val protein = opts.kind == "protein"
        val favorites = opts.favorites.toSet()
        val matches = ArrayList<MarkerMatch>()
        for (lane in lanes) {
            if (lane.removed == true) continue
            val bands = lane.bands.filter { it.hidden != true }.sortedBy { it.y }
            if (bands.size < (if (protein) 4 else 3)) continue
            val ys = bands.map { it.y }
            for (ladder in opts.ladders) {
                if (ladder.kind != opts.kind) continue
                for (sizes in sizeWindows(ladder, bands.size, protein)) {
                    val fit = fitForSizes(ys, sizes) ?: continue
                    // Bands lower down the gel are smaller, so log10(size) must fall as y
                    // grows. A non-negative slope means the ladder was matched upside down.
                    val slope = if (fit.degree == 2) fit.b + fit.c else fit.b
                    if (slope >= -0.1) continue
                    val countPenalty = ladder.sizes.size - bands.size
                    val fewBands = if (bands.size < 4) 0.12 else 0.0
                    val score = fit.r2 -
                        countPenalty * (if (protein) 0.02 else 0.06) -
                        fewBands +
                        (if (favorites.contains(ladder.id)) 0.02 else 0.0)
                    matches.add(MarkerMatch(lane.id, ladder, fit, sizes, score))
                }
            }
        }
        return matches.sortedByDescending { it.score }
    }

    /** Pick the ladder that best explains one or more lanes and fit it jointly. */
    fun identifyMarkers(lanes: List<GelLane>, opts: MatchOptions): JointMarkerResult? {
        val matches = findMarkerMatches(lanes, opts)
        if (matches.isEmpty()) return null
        // The best single match chooses the ladder; other lanes that fit it join.
        val best = matches[0]
        // Only lanes whose own fit is credible may join; a lane with a different
        // band count or a much worse fit is a sample lane, not a second marker.
        val sameLadder = matches.filter {
            it.ladder.id == best.ladder.id &&
                it.laneId != best.laneId &&
                it.sizes.size == best.sizes.size &&
                it.score >= best.score - 0.08
        }
        // One lane per ladder contribution: keep the best match for each lane.
        val byLane = LinkedHashMap<String, MarkerMatch>()
        for (m in listOf(best) + sameLadder) if (!byLane.containsKey(m.laneId)) byLane[m.laneId] = m
        val chosen = byLane.values.toList()
        val points = ArrayList<GraySizePoint>()
        for (m in chosen) {
            val lane = lanes.firstOrNull { it.id == m.laneId } ?: continue
            val bins = lane.bands.filter { it.hidden != true }.sortedBy { it.y }
            bins.take(m.sizes.size).forEachIndexed { i, b -> points.add(GraySizePoint(b.y, m.sizes[i])) }
        }
        val degree = if (points.size >= 8) 2 else 1
        val joint = GelMath.robustFit(points, degree) ?: best.fit
        val assignments = HashMap<String, List<Double>>()
        for (m in chosen) assignments[m.laneId] = m.sizes
        return JointMarkerResult(
            ladder = best.ladder,
            laneIds = chosen.map { it.laneId },
            fit = joint,
            assignments = assignments,
            score = chosen.fold(0.0) { s, m -> s + m.score } / chosen.size,
        )
    }

    /** Sizes for a lane's detected bands, with extrapolation flags. */
    fun estimateBandSizes(
        bands: List<GelBand>,
        fit: PolyFit,
        ladder: LadderDef,
        alternatives: List<PolyFit> = emptyList(),
    ): List<EstimatedSize> {
        val lo = Math.log10(ladder.sizes.min())
        val hi = Math.log10(ladder.sizes.max())
        return bands.map { b ->
            val log = GelMath.polyAt(fit, b.y)
            val size = Math.pow(10.0, log)
            val outOfRange = log < lo - 0.01 || log > hi + 0.01
            val disagree = alternatives.any { alt ->
                val other = Math.pow(10.0, GelMath.polyAt(alt, b.y))
                Math.abs(other - size) / maxOf(1.0, size) > 0.1
            }
            EstimatedSize(size, outOfRange || disagree)
        }
    }

    fun isOutOfRange(size: Double, fit: PolyFit, ladder: LadderDef): Boolean {
        val log = Math.log10(maxOf(1e-6, size))
        val lo = Math.log10(ladder.sizes.min())
        val hi = Math.log10(ladder.sizes.max())
        // Also treat positions beyond the matched band span as extrapolated.
        return log < lo - 0.01 || log > hi + 0.01
    }
}
