package com.spice.nativeapp.tools.blot

/*
 * Replicate statistics for blot group summaries. 1:1 port of main's
 * src/lib/tools/blot/stats.ts — the Welch t-test and the Student t quantile
 * are implemented locally so the tool has no dependency on the qPCR module.
 */

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.sin
import kotlin.math.sqrt

fun mean(values: DoubleArray): Double {
    if (values.isEmpty()) return 0.0
    var sum = 0.0
    for (value in values) sum += value
    return sum / values.size
}

fun mean(values: List<Double>): Double = mean(values.toDoubleArray())

/** Sample standard deviation (n - 1). */
fun sampleSd(values: DoubleArray): Double {
    if (values.size < 2) return 0.0
    val m = mean(values)
    var sum = 0.0
    for (value in values) sum += (value - m) * (value - m)
    return sqrt(sum / (values.size - 1))
}

fun sampleSd(values: List<Double>): Double = sampleSd(values.toDoubleArray())

fun sem(values: DoubleArray): Double {
    val sd = sampleSd(values)
    return if (values.isNotEmpty()) sd / sqrt(values.size.toDouble()) else 0.0
}

fun sem(values: List<Double>): Double = sem(values.toDoubleArray())

/** Two-sided 97.5% Student t quantiles by degrees of freedom. */
private val T_CRIT_975 = doubleArrayOf(
    Double.POSITIVE_INFINITY,
    12.7062, 4.3027, 3.1824, 2.7764, 2.5706, 2.4469, 2.3646, 2.306, 2.2622, 2.2281,
    2.201, 2.1788, 2.1604, 2.1448, 2.1314, 2.1199, 2.1098, 2.1009, 2.093, 2.086,
    2.0796, 2.0739, 2.0687, 2.0639, 2.0595, 2.0555, 2.0518, 2.0484, 2.0452, 2.0423,
    2.0395, 2.0369, 2.0345, 2.0322, 2.0301, 2.0281, 2.0262, 2.0244, 2.0227, 2.0211,
)

/** Two-sided 95% Student t critical value, interpolated between table rows. */
fun tCritical95(df: Double): Double {
    if (!df.isFinite() || df <= 0) return 1.959964
    if (df <= 40) {
        val low = kotlin.math.max(1, kotlin.math.floor(df).toInt())
        val high = kotlin.math.min(40, low + 1)
        val a = if (low < T_CRIT_975.size) T_CRIT_975[low] else 2.0211
        val b = if (high < T_CRIT_975.size) T_CRIT_975[high] else 2.0211
        val t = df - low
        return a + (b - a) * t
    }
    if (df >= 120) return 1.959964
    // Linear interpolation between common large-sample anchors.
    val anchors = arrayOf(
        40.0 to 2.0211,
        60.0 to 2.0003,
        120.0 to 1.9799,
    )
    for (i in 1 until anchors.size) {
        val (d1, t1) = anchors[i - 1]
        val (d2, t2) = anchors[i]
        if (df <= d2) return t1 + ((t2 - t1) * (df - d1)) / (d2 - d1)
    }
    return 1.959964
}

/** Half-width of the two-sided 95% confidence interval of a mean. */
fun ci95HalfWidth(values: DoubleArray): Double {
    if (values.size < 2) return 0.0
    return tCritical95((values.size - 1).toDouble()) * sem(values)
}

fun ci95HalfWidth(values: List<Double>): Double = ci95HalfWidth(values.toDoubleArray())

// ------------------------------------------------------------- Welch t-test

private val LANCZOS = doubleArrayOf(
    0.99999999999980993,
    676.5203681218851,
    -1259.1392167224028,
    771.32342877765313,
    -176.61502916214059,
    12.507343278686905,
    -0.13857109526572012,
    9.9843695780195716e-6,
    1.5056327351493116e-7,
)

private fun logGamma(z: Double): Double {
    if (z < 0.5) return ln(Math.PI / sin(Math.PI * z)) - logGamma(1 - z)
    var x = LANCZOS[0]
    val t = z - 1
    for (i in 1 until LANCZOS.size) x += LANCZOS[i] / (t + i)
    val w = t + 7.5
    return 0.5 * ln(2 * Math.PI) + (t + 0.5) * ln(w) - w + ln(x)
}

private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
    val maxIterations = 300
    val epsilon = 3e-14
    val tiny = 1e-300
    val qab = a + b
    val qap = a + 1
    val qam = a - 1
    var c = 1.0
    var d = 1 - (qab * x) / qap
    if (abs(d) < tiny) d = tiny
    d = 1 / d
    var h = d
    for (m in 1..maxIterations) {
        val m2 = 2.0 * m
        var aa = (m * (b - m) * x) / ((qam + m2) * (a + m2))
        d = 1 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1 / d
        h *= d * c
        aa = (-(a + m) * (qab + m) * x) / ((a + m2) * (qap + m2))
        d = 1 + aa * d
        if (abs(d) < tiny) d = tiny
        c = 1 + aa / c
        if (abs(c) < tiny) c = tiny
        d = 1 / d
        val del = d * c
        h *= del
        if (abs(del - 1) < epsilon) break
    }
    return h
}

private fun incompleteBeta(a: Double, b: Double, x: Double): Double {
    if (x <= 0) return 0.0
    if (x >= 1) return 1.0
    val logTerm = logGamma(a + b) - logGamma(a) - logGamma(b) + a * ln(x) + b * ln(1 - x)
    val term = exp(logTerm)
    return if (x < (a + 1) / (a + b + 2)) {
        (term * betaContinuedFraction(a, b, x)) / a
    } else {
        1 - (term * betaContinuedFraction(b, a, 1 - x)) / b
    }
}

/** Two-sided p-value for a Student t statistic. */
fun studentTTwoSidedP(t: Double, df: Double): Double? {
    if (!df.isFinite() || df <= 0) return null
    if (!t.isFinite()) return 0.0
    if (t == 0.0) return 1.0
    val x = df / (df + t * t)
    return kotlin.math.min(1.0, kotlin.math.max(0.0, incompleteBeta(df / 2, 0.5, x)))
}

class WelchResult(val t: Double, val df: Double, val p: Double)

/** Two-sided Welch t-test for two independent samples. */
fun welchTTest(a: DoubleArray, b: DoubleArray): WelchResult? {
    if (a.size < 2 || b.size < 2) return null
    val meanA = mean(a)
    val meanB = mean(b)
    val varA = sampleSd(a) * sampleSd(a)
    val varB = sampleSd(b) * sampleSd(b)
    val termA = varA / a.size
    val termB = varB / b.size
    val standardError = sqrt(termA + termB)
    val diff = meanA - meanB
    if (standardError == 0.0) {
        if (diff == 0.0) return WelchResult(0.0, (a.size + b.size - 2).toDouble(), 1.0)
        val t = if (diff > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        return WelchResult(t, (a.size + b.size - 2).toDouble(), 0.0)
    }
    val t = diff / standardError
    val numerator = (termA + termB) * (termA + termB)
    val denominator = (termA * termA) / (a.size - 1) + (termB * termB) / (b.size - 1)
    val df = if (denominator == 0.0) (a.size + b.size - 2).toDouble() else numerator / denominator
    val p = studentTTwoSidedP(t, df) ?: return null
    return WelchResult(t, df, p)
}
