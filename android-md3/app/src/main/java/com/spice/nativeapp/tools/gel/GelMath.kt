package com.spice.nativeapp.tools.gel

/**
 * Gel figure tool — image profiles, peak detection, straighten search and
 * ladder fitting. 1:1 移植自原版 src/lib/tools/gel/gelmath.ts。
 *
 * Pure functions only; 所有 FloatArray 均为 0..1 灰度平面（行主序，长度 w*h）。
 */

data class Peak(
    /** Row/column index (fraction of the profile length). */
    val frac: Double,
    /** Raw profile value at the peak. */
    val value: Double,
    /** Prominence above the local baseline (profile units). */
    val prominence: Double,
)

class PeakOptions(
    /** Minimum prominence as a multiple of the profile standard deviation. */
    val prominenceStd: Double = 0.6,
    /** Minimum absolute prominence (profile units, 0..1 gray scale). */
    val minProminence: Double = 0.01,
    /** Minimum distance between peaks in profile buckets. */
    val minGap: Int = 4,
    /** Smoothing window (odd number of buckets, 1 = off). */
    val smooth: Int = 3,
    /** Keep the mean+std gate. Binary signal profiles have no intensity range,
     *  so leaving it on can hide weak lanes next to a saturated ladder. */
    val useThreshold: Boolean = true,
)

data class DetectedLane(
    val x: Double,      // fraction
    val width: Double,  // fraction
)

data class LadderFit(
    /** log10(size) = a * y + b */
    val a: Double,
    val b: Double,
    val r2: Double,
    val n: Int,
)

data class PolyFit(
    val a: Double,
    val b: Double,
    val c: Double,
    val degree: Int,  // 1 | 2
    val r2: Double,
    val n: Int,
)

data class GraySizePoint(val y: Double, val size: Double)

object GelMath {

    /** Average RGB to a 0..1 gray plane (row-major, length w*h). ARGB 输入。 */
    fun toGrayPlane(argb: IntArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        val n = minOf(out.size, argb.size)
        for (p in 0 until n) {
            val px = argb[p]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            out[p] = ((r * 0.299 + g * 0.587 + b * 0.114) / 255.0).toFloat()
        }
        return out
    }

    private fun smoothProfile(profile: FloatArray, window: Int): FloatArray {
        if (window <= 1) return profile
        val half = window / 2
        val out = FloatArray(profile.size)
        for (i in profile.indices) {
            var sum = 0.0
            var n = 0
            var j = maxOf(0, i - half)
            val hi = minOf(profile.size - 1, i + half)
            while (j <= hi) {
                sum += profile[j].toDouble()
                n++
                j++
            }
            out[i] = (sum / n).toFloat()
        }
        return out
    }

    private fun meanStd(values: FloatArray, from: Int = 0, to: Int = values.size): Pair<Double, Double> {
        var sum = 0.0
        for (i in from until to) sum += values[i].toDouble()
        val mean = sum / maxOf(1, to - from)
        var sq = 0.0
        for (i in from until to) sq += (values[i] - mean) * (values[i] - mean)
        return mean to Math.sqrt(sq / maxOf(1, to - from))
    }

    /** Local-maxima peak finder with prominence and minimum-gap filtering. */
    fun findPeaks(profile: FloatArray, opts: PeakOptions = PeakOptions()): List<Peak> {
        val smoothed = smoothProfile(profile, opts.smooth)
        val n = smoothed.size
        if (n < 3) return emptyList()
        val (mean, std) = meanStd(smoothed)
        val minProminence = opts.minProminence
        val minGap = maxOf(1, opts.minGap)
        val threshold = mean + opts.prominenceStd * std

        val candidates = ArrayList<Peak>()
        for (i in 1 until n - 1) {
            val v = smoothed[i].toDouble()
            if (opts.useThreshold && v < threshold) continue
            // Strict on the falling side, lenient on the rising side: a flat-topped
            // plateau (a wide band column) yields exactly one candidate, not one per
            // pixel of its top.
            if (v < smoothed[i - 1] || v <= smoothed[i + 1]) continue
            // Baseline = minimum in a local window around the peak.
            val lo = maxOf(0, i - minGap * 3)
            val hi = minOf(n, i + minGap * 3 + 1)
            var base = v
            for (j in lo until hi) base = minOf(base, smoothed[j].toDouble())
            val prominence = v - base
            if (prominence < minProminence) continue
            candidates.add(Peak(frac = i.toDouble() / (n - 1), value = v, prominence = prominence))
        }

        // Greedy non-maximum suppression by prominence.
        val sorted = candidates.sortedByDescending { it.prominence }
        val kept = ArrayList<Peak>()
        val minFrac = minGap.toDouble() / maxOf(1, n - 1)
        for (cand in sorted) {
            if (kept.all { Math.abs(it.frac - cand.frac) >= minFrac }) kept.add(cand)
        }
        return kept.sortedBy { it.frac }
    }

    /** Merge local maxima that share one flat top. Scanner dither turns a solid
     *  band or lane into a small sawtooth, which otherwise becomes several peaks.
     *  The merged peak sits at the prominence-weighted centroid. */
    fun collapseFlatPeaks(profile: FloatArray, peaks: List<Peak>): List<Peak> {
        if (peaks.size < 2) return peaks
        val ordered = peaks.sortedBy { it.frac }
        val clusters = ArrayList<MutableList<Peak>>()
        for (peak in ordered) {
            val current = clusters.lastOrNull()
            if (current == null) {
                clusters.add(mutableListOf(peak))
                continue
            }
            val prev = current.last()
            val i0 = Math.round(prev.frac * (profile.size - 1)).toInt()
            val i1 = Math.round(peak.frac * (profile.size - 1)).toInt()
            var valley = Double.POSITIVE_INFINITY
            for (i in i0..i1) valley = minOf(valley, profile[i].toDouble())
            val shallow = valley >= minOf(prev.value, peak.value) * 0.7
            if (peak.frac - prev.frac <= 0.012 || shallow) current.add(peak)
            else clusters.add(mutableListOf(peak))
        }
        return clusters.map { cluster ->
            val weight = cluster.fold(0.0) { s, p -> s + p.prominence }.takeIf { it != 0.0 } ?: 1.0
            val frac = cluster.fold(0.0) { s, p -> s + p.frac * p.prominence } / weight
            val best = cluster.reduce { a, b -> if (b.value > a.value) b else a }
            best.copy(frac = frac)
        }
    }

    /** Mean gray per COLUMN inside a row band — finds lane centers. */
    fun columnProfile(gray: FloatArray, width: Int, height: Int, y0: Int = 0, y1: Int = height): FloatArray {
        val out = FloatArray(width)
        val top = maxOf(0, minOf(height, y0))
        val bottom = maxOf(top + 1, minOf(height, y1))
        for (x in 0 until width) {
            var sum = 0.0
            for (y in top until bottom) sum += gray[y * width + x].toDouble()
            out[x] = (sum / (bottom - top)).toFloat()
        }
        return out
    }

    /** Mean gray per ROW inside a column band — finds band positions in a lane. */
    fun rowProfile(gray: FloatArray, width: Int, height: Int, x0: Int, x1: Int): FloatArray {
        val out = FloatArray(height)
        val left = maxOf(0, minOf(width, x0))
        val right = maxOf(left + 1, minOf(width, x1))
        for (y in 0 until height) {
            var sum = 0.0
            for (x in left until right) sum += gray[y * width + x].toDouble()
            out[y] = (sum / (right - left)).toFloat()
        }
        return out
    }

    /** Column-profile lane detection: peaks are lane centers; width is derived
     *  from the nearest-neighbour spacing so adjacent lanes do not overlap. */
    fun detectLanes(gray: FloatArray, width: Int, height: Int): List<DetectedLane> {
        val profile = columnProfile(gray, width, height, Math.floor(height * 0.08).toInt(), Math.floor(height * 0.92).toInt())
        val peaks = findPeaks(
            profile,
            PeakOptions(
                prominenceStd = 0.35,
                minProminence = 0.004,
                minGap = maxOf(6, Math.round(width * 0.01).toInt()),
                smooth = 5,
            ),
        )
        if (peaks.size < 2) return emptyList()
        val xs = peaks.map { it.frac }
        val gaps = ArrayList<Double>()
        for (i in 1 until xs.size) gaps.add(xs[i] - xs[i - 1])
        val sorted = gaps.sorted()
        val medianGap = if (sorted.isEmpty()) 0.05 else sorted[sorted.size / 2]
        val widthFraction = maxOf(0.01, minOf(0.2, medianGap * 0.62))
        return xs.map { DetectedLane(it, widthFraction) }
    }

    /** Least-squares fit of log10(size) against normalized band positions.
     *  `points` must pair each band's y with its known ladder size. */
    fun fitLadder(points: List<GraySizePoint>): LadderFit? {
        if (points.size < 2) return null
        val xs = points.map { it.y }
        val ys = points.map { Math.log10(it.size) }
        val n = xs.size
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        if (sxx == 0.0) return null
        val a = sxy / sxx
        val b = my - a * mx
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val pred = a * xs[i] + b
            ssTot += (ys[i] - my) * (ys[i] - my)
            ssRes += (ys[i] - pred) * (ys[i] - pred)
        }
        val r2 = if (ssTot == 0.0) 1.0 else 1 - ssRes / ssTot
        return LadderFit(a, b, r2, n)
    }

    fun sizeFromY(fit: LadderFit, y: Double): Double = Math.pow(10.0, fit.a * y + fit.b)

    /** Score a candidate rotation: sum of squared row-mean differences (bands
     *  aligned horizontally produce sharp row contrast). Used by auto-straighten. */
    fun rotationScore(gray: FloatArray, width: Int, height: Int): Double {
        val rows = rowProfile(gray, width, height, 0, width)
        var score = 0.0
        for (y in 1 until rows.size) score += (rows[y] - rows[y - 1]) * (rows[y] - rows[y - 1]).toDouble()
        return score
    }

    fun percentile(values: FloatArray, p: Double): Double {
        val arr = values.map { it.toDouble() }.sorted()
        if (arr.isEmpty()) return 0.0
        val i = minOf(arr.size - 1, maxOf(0, Math.round((arr.size - 1) * p).toInt()))
        return arr[i]
    }

    /** Fraction of pixels at the black or white rail. */
    fun clippedFraction(gray: FloatArray): Pair<Double, Double> {
        var dark = 0
        var bright = 0
        for (i in gray.indices) {
            if (gray[i] <= 0.004f) dark++
            else if (gray[i] >= 0.996f) bright++
        }
        val n = maxOf(1, gray.size)
        return (dark.toDouble() / n) to (bright.toDouble() / n)
    }

    /** Tile-mean background, bilinearly interpolated. Removes the broad glow of a
     *  UV transilluminator and scanner vignetting before thresholding, so a single
     *  global cut stays valid across the whole slab. */
    fun backgroundPlane(gray: FloatArray, width: Int, height: Int, quantile: Double = 0.5): FloatArray {
        // The window must stay much larger than a band, otherwise the band raises
        // its own background estimate and vanishes from the residual.
        val tile = maxOf(12, Math.round(Math.min(width, height) / 6.0).toInt())
        val gw = maxOf(2, Math.ceil(width / tile.toDouble()).toInt())
        val gh = maxOf(2, Math.ceil(height / tile.toDouble()).toInt())
        val grid = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val cy = minOf(height - 1, gy * tile + tile / 2)
            val y0 = maxOf(0, cy - tile / 2)
            val y1 = minOf(height, y0 + tile)
            for (gx in 0 until gw) {
                val cx = minOf(width - 1, gx * tile + tile / 2)
                val x0 = maxOf(0, cx - tile / 2)
                val x1 = minOf(width, x0 + tile)
                // A per-tile quantile rather than the mean: a bright band can fill a
                // large share of its tile, and the mean would then absorb it into the
                // background. The low quantile tracks the background under bright bands,
                // the high quantile under dark ones.
                val hist = IntArray(256)
                var n = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        hist[Math.round(maxOf(0.0, minOf(1.0, gray[y * width + x].toDouble())) * 255).toInt()]++
                        n++
                    }
                }
                var acc = 0
                var level = 0
                val target = n * quantile
                var found = false
                for (i in 0 until 256) {
                    acc += hist[i]
                    if (acc >= target) {
                        level = i
                        found = true
                        break
                    }
                }
                grid[gy * gw + gx] = (if (n != 0 && found) level / 255.0 else 0.0).toFloat()
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val fy = y / tile - 0.5
            val gy0: Int
            val gy1: Int
            val ty: Double
            if (fy < 0) {
                gy0 = 0
                gy1 = minOf(gh - 1, 1)
                ty = fy
            } else if (fy > gh - 1) {
                gy0 = maxOf(0, gh - 2)
                gy1 = gh - 1
                ty = fy - gy0
            } else {
                gy0 = Math.floor(fy).toInt()
                gy1 = minOf(gh - 1, gy0 + 1)
                ty = fy - gy0
            }
            for (x in 0 until width) {
                val fx = x / tile - 0.5
                val gx0: Int
                val gx1: Int
                val tx: Double
                if (fx < 0) {
                    gx0 = 0
                    gx1 = minOf(gw - 1, 1)
                    tx = fx
                } else if (fx > gw - 1) {
                    gx0 = maxOf(0, gw - 2)
                    gx1 = gw - 1
                    tx = fx - gx0
                } else {
                    gx0 = Math.floor(fx).toInt()
                    gx1 = minOf(gw - 1, gx0 + 1)
                    tx = fx - gx0
                }
                val a = grid[gy0 * gw + gx0].toDouble()
                val b = grid[gy0 * gw + gx1].toDouble()
                val c = grid[gy1 * gw + gx0].toDouble()
                val d = grid[gy1 * gw + gx1].toDouble()
                out[y * width + x] = ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty).toFloat()
            }
        }
        return out
    }

    /** Otsu cut over [lo, hi]; values outside are clamped into the end bins. */
    private fun otsuThreshold(values: FloatArray, lo: Double, hi: Double, bins: Int = 256): Double {
        if (!(hi > lo)) return lo
        val hist = IntArray(bins)
        val scale = (bins - 1) / (hi - lo)
        for (i in values.indices) {
            val b = Math.round((values[i] - lo) * scale).toInt()
            hist[if (b < 0) 0 else if (b >= bins) bins - 1 else b]++
        }
        var total = 0L
        var weighted = 0.0
        for (i in 0 until bins) {
            total += hist[i]
            weighted += i * hist[i].toDouble()
        }
        var sumB = 0.0
        var wB = 0L
        var best = -1.0
        var cut = lo
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (weighted - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                cut = lo + (i + 0.5) / scale
            }
        }
        return cut
    }

    /** Signal polarity from the central region: compare how far the dark and
     *  bright tails reach from the median. A gradient background spreads across
     *  many bins, so the histogram mode is not a reliable background marker, but
     *  the tail that reaches further is always the bands. The central window keeps
     *  a black scanner surround out of the vote. */
    private fun signalPolarity(values: FloatArray, width: Int, height: Int, bins: Int = 256): Int {
        val x0 = Math.floor(width * 0.1).toInt()
        val x1 = maxOf(x0 + 1, width - x0)
        val y0 = Math.floor(height * 0.1).toInt()
        val y1 = maxOf(y0 + 1, height - y0)
        val hist = IntArray(bins)
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val value = maxOf(0.0, minOf(1.0, values[y * width + x].toDouble()))
                hist[Math.round(value * (bins - 1)).toInt()]++
                count++
            }
        }
        if (count == 0) return 1
        val quantile = fun(q: Double): Double {
            val target = count * q
            var acc = 0
            for (i in 0 until bins) {
                acc += hist[i]
                if (acc >= target) return i.toDouble() / (bins - 1)
            }
            return 1.0
        }
        val low = quantile(0.01)
        val median = quantile(0.5)
        val high = quantile(0.99)
        return if (high - median >= median - low) 1 else -1
    }

    /** Median of |values| from a histogram, capped at `hi`. Robust to the bands
     *  themselves because they cover far less area than the background. */
    private fun medianMagnitude(values: FloatArray, hi: Double, bins: Int = 512): Double {
        val hist = IntArray(bins)
        for (i in values.indices) {
            val v = Math.abs(values[i].toDouble())
            val b = minOf(bins - 1, Math.floor(v / hi * bins).toInt())
            hist[b]++
        }
        val half = values.size / 2.0
        var acc = 0
        for (b in 0 until bins) {
            acc += hist[b]
            if (acc >= half) return (b + 0.5) * (hi / bins)
        }
        return hi
    }

    data class BinaryResult(
        /** 1 where a band/well pixel is present, 0 elsewhere. */
        val signal: FloatArray,
        /** +1 for bright bands on a dark background, -1 for dark bands on light. */
        val polarity: Int,
        /** Signal threshold on the polarity-corrected residual. */
        val threshold: Double,
    )

    /** Flatten illumination, detect bright/dark polarity and threshold. This is
     *  what makes one detector work on UV/fluorescence gels and on Coomassie or
     *  silver-stained gels, which have opposite contrast. */
    fun binarizeGray(
        gray: FloatArray,
        width: Int,
        height: Int,
        config: GelDetectConfig? = null,
    ): BinaryResult {
        val n = width * height
        val polarity: Int = when (config?.polarity) {
            "bright" -> 1
            "dark" -> -1
            else -> signalPolarity(gray, width, height)
        }
        // 1.0 at the shipped sensitivity, so an untouched config is bit-identical.
        val scale = if (config != null) GelPresets.thresholdScale(config) else 1.0
        // Follow the background on the side away from the bands: a low quantile for
        // bright bands, a high quantile for dark bands.
        val background = backgroundPlane(gray, width, height, if (polarity == 1) 0.2 else 0.8)
        val residual = FloatArray(n)
        var hi = 0.0
        for (i in 0 until n) {
            val value = if (polarity == 1) gray[i] - background[i] else background[i] - gray[i]
            residual[i] = value
            if (value > hi) hi = value.toDouble()
        }
        val sigma = maxOf(0.002, 1.4826 * medianMagnitude(residual, 0.5))
        val floor = maxOf(0.008, 3 * sigma)
        hi = minOf(1.0, hi)
        if (hi <= floor) return BinaryResult(FloatArray(n), polarity, hi)
        val threshold = maxOf(otsuThreshold(residual, -3 * sigma, hi), floor) * scale
        val signal = FloatArray(n)
        for (i in 0 until n) signal[i] = if (residual[i] >= threshold) 1f else 0f
        // Scanner vignetting and the slab border can survive the residual as a
        // full-height false lane; drop the outer 3% like the native binarizer does.
        val bx = maxOf(2, Math.ceil(width * 0.03).toInt())
        val by = maxOf(2, Math.ceil(height * 0.03).toInt())
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < bx || y < by || x >= width - bx || y >= height - by) signal[y * width + x] = 0f
            }
        }
        return BinaryResult(signal, polarity, threshold)
    }

    /** Locate the gel slab as the contiguous run through the image centre that
     *  differs from the scanner background. Returns normalised crop fractions. */
    fun autoCropRect(gray: FloatArray, width: Int, height: Int): GelProject.GelCrop {
        val cols = columnProfile(gray, width, height)
        val rows = rowProfile(gray, width, height, 0, width)
        val cx = width / 2
        val cy = height / 2
        val middleCol = cols[cx].toDouble()
        val middleRow = rows[cy].toDouble()
        val border = (cols[0] + cols[width - 1] + rows[0] + rows[height - 1]) / 4.0
        // Whichever direction the slab lies relative to the background.
        val sign = if (middleCol - border >= 0) 1 else if (middleCol - border < 0) -1 else 1
        val cut = fun(v: Double): Boolean = (v - border) * sign > Math.abs(middleCol - border) * 0.22
        var x0 = cx
        var x1 = cx
        while (x0 > 0 && cut(cols[x0 - 1].toDouble())) x0--
        while (x1 < width - 1 && cut(cols[x1 + 1].toDouble())) x1++
        var y0 = cy
        var y1 = cy
        while (y0 > 0 && cut(rows[y0 - 1].toDouble())) y0--
        while (y1 < height - 1 && cut(rows[y1 + 1].toDouble())) y1++
        if (x1 - x0 < width * 0.3) {
            x0 = 0
            x1 = width - 1
        }
        if (y1 - y0 < height * 0.3) {
            y0 = 0
            y1 = height - 1
        }
        val padX = Math.round((x1 - x0) * 0.015).toInt()
        val padY = Math.round((y1 - y0) * 0.015).toInt()
        x0 = maxOf(0, x0 - padX)
        x1 = minOf(width - 1, x1 + padX)
        y0 = maxOf(0, y0 - padY)
        y1 = minOf(height - 1, y1 + padY)
        return GelProject.GelCrop(
            left = x0.toDouble() / width,
            right = 1 - (x1 + 1).toDouble() / width,
            top = y0.toDouble() / height,
            bottom = 1 - (y1 + 1).toDouble() / height,
        )
    }

    data class MeanRgb(val r: Double, val g: Double, val b: Double, val sat: Double)

    /** Mean RGB of the processed full image, for gel-type voting. ARGB 输入。 */
    fun meanRgb(argb: IntArray): MeanRgb {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var sat = 0.0
        val n = maxOf(1, argb.size)
        for (px in argb) {
            val ri = (px shr 16) and 0xFF
            val gi = (px shr 8) and 0xFF
            val bi = px and 0xFF
            r += ri
            g += gi
            b += bi
            val max = maxOf(ri, gi, bi)
            val min = minOf(ri, gi, bi)
            sat += if (max == 0) 0.0 else (max - min).toDouble() / max
        }
        return MeanRgb(r / n, g / n, b / n, sat / n)
    }

    fun polyAt(fit: PolyFit, y: Double): Double =
        fit.a + fit.b * y + (if (fit.degree == 2) fit.c * y * y else 0.0)

    /** 原版 TS 结构类型：GelLadderFit 可直接传入 polyAt，这里提供同构重载。 */
    fun polyAt(fit: GelLadderFit, y: Double): Double =
        fit.a + fit.b * y + (if (fit.degree == 2) fit.c * y * y else 0.0)

    /** Gaussian elimination for the normal equations (degree-2 least squares). */
    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        // augmented 3x4
        val a = Array(3) { i -> doubleArrayOf(m[i][0], m[i][1], m[i][2], v[i]) }
        for (i in 0 until 3) {
            var p = i
            for (j in i + 1 until 3) if (Math.abs(a[j][i]) > Math.abs(a[p][i])) p = j
            if (Math.abs(a[p][i]) < 1e-12) return null
            val tmp = a[i]; a[i] = a[p]; a[p] = tmp
            for (j in i + 1 until 3) {
                val f = a[j][i] / a[i][i]
                for (k in i until 4) a[j][k] -= f * a[i][k]
            }
        }
        val out = DoubleArray(3)
        for (i in 2 downTo 0) {
            var s = a[i][3]
            for (j in i + 1 until 3) s -= a[i][j] * out[j]
            out[i] = s / a[i][i]
        }
        return out
    }

    private fun leastSquares(points: List<GraySizePoint>, degree: Int): PolyFit? {
        val n = points.size
        if (n < (if (degree == 2) 4 else 2)) return null
        val xs = points.map { it.y }
        val ys = points.map { Math.log10(it.size) }
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        var sx3 = 0.0
        var sx4 = 0.0
        var sx2y = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxx += dx * dx
            sxy += dx * dy
            sx3 += dx * dx * dx
            sx4 += dx * dx * dx * dx
            // Normal-equation RHS for the quadratic term is Σ(dx² · y), not Σ(dx² · dy).
            // Using the mean-centred dy silently drops meanY·S2 and corrupts the fit.
            sx2y += dx * dx * ys[i]
        }
        val a: Double
        val b: Double
        var c = 0.0
        if (degree == 2) {
            val sol = solve3(
                arrayOf(
                    doubleArrayOf(n.toDouble(), 0.0, sxx),
                    doubleArrayOf(0.0, sxx, sx3),
                    doubleArrayOf(sxx, sx3, sx4),
                ),
                doubleArrayOf(n * meanY, sxy, sx2y),
            ) ?: return null
            // The normal equations were solved in centred coordinates; expand back to
            // raw y so polyAt can evaluate the stored coefficients directly.
            val ca = sol[0]
            val cb = sol[1]
            c = sol[2]
            a = ca - cb * meanX + c * meanX * meanX
            b = cb - 2 * c * meanX
        } else {
            if (sxx == 0.0) return null
            b = sxy / sxx
            a = meanY - b * meanX
        }
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val pred = a + b * xs[i] + (if (degree == 2) c * xs[i] * xs[i] else 0.0)
            ssTot += (ys[i] - meanY) * (ys[i] - meanY)
            ssRes += (ys[i] - pred) * (ys[i] - pred)
        }
        return PolyFit(a, b, c, degree, if (ssTot == 0.0) 1.0 else 1 - ssRes / ssTot, n)
    }

    /** Robust fit of log10(size) against band position. RANSAC rejects a
     *  mis-detected or mislabelled band; falls back to all points. */
    fun robustFit(points: List<GraySizePoint>, degree: Int = 1): PolyFit? {
        val clean = points.filter { it.y.isFinite() && it.size.isFinite() && it.size > 0 }
        if (clean.size < (if (degree == 2) 4 else 3)) return leastSquares(clean, degree)
        var best: PolyFit? = null
        var bestInliers = -1
        val need = if (degree == 2) 3 else 2
        val iterations = 160
        for (it in 0 until iterations) {
            val pick = ArrayList<GraySizePoint>()
            while (pick.size < need) {
                val cand = clean[kotlin.random.Random.nextInt(clean.size)]
                if (!pick.contains(cand)) pick.add(cand)
            }
            val model = leastSquares(pick, degree) ?: continue
            var inliers = 0
            for (p in clean) {
                val pred = polyAt(model, p.y)
                if (Math.abs(pred - Math.log10(p.size)) < 0.03) inliers++
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                best = model
            }
        }
        if (best == null) return leastSquares(clean, degree)
        val full = leastSquares(clean, degree)
        // A clean ladder should report every band it was fitted with; RANSAC is only
        // there to reject a grossly mislabelled point.
        if (full != null && full.r2 >= 0.985) return full
        val inlierPoints = clean.filter { p -> Math.abs(polyAt(best, p.y) - Math.log10(p.size)) < 0.05 }
        return leastSquares(if (inlierPoints.size >= need) inlierPoints else clean, degree)
    }
}
