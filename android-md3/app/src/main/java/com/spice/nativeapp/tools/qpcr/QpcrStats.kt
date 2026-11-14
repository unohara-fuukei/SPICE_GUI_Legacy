package com.spice.nativeapp.tools.qpcr

/*
 * Small statistics toolkit. 1:1 port of main's src/lib/tools/qpcr/stats.ts:
 * descriptive statistics, a two-sided Welch t-test with a real Student t CDF
 * (regularized incomplete beta), and least-squares linear regression for
 * standard curves. No external dependencies.
 */

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun mean(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    var sum = 0.0
    for (v in values) sum += v
    return sum / values.size
}

/** Sample standard deviation (n - 1). Null for fewer than two values. */
fun sampleSd(values: List<Double>): Double? {
    if (values.size < 2) return null
    val m = mean(values)
    var sum = 0.0
    for (v in values) {
        val d = v - m
        sum += d * d
    }
    return sqrt(sum / (values.size - 1))
}

fun sem(values: List<Double>): Double? {
    val sd = sampleSd(values) ?: return null
    return sd / sqrt(values.size.toDouble())
}

// ------------------------------------------------------------------ t-test

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

fun logGamma(z: Double): Double {
    if (z < 0.5) {
        // Reflection formula.
        return ln(Math.PI / sin(Math.PI * z)) - logGamma(1 - z)
    }
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
        val m2 = 2 * m
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

/** Regularized incomplete beta function I_x(a, b) for a, b > 0. */
fun incompleteBeta(a: Double, b: Double, x: Double): Double {
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

/**
 * Two-sided p-value for a Student t statistic with `df` degrees of freedom.
 * p = I_{df/(df+t^2)}(df/2, 1/2).
 */
fun studentTTwoSidedP(t: Double, df: Double): Double? {
    if (!df.isFinite() || df <= 0) return null
    if (!t.isFinite()) return 0.0
    if (t == 0.0) return 1.0
    val x = df / (df + t * t)
    val p = incompleteBeta(df / 2, 0.5, x)
    return p.coerceIn(0.0, 1.0)
}

class WelchResult(
    val t: Double,
    val df: Double,
    val p: Double,
)

/**
 * Welch t-test for two independent samples. Returns null when either group has
 * fewer than two observations. Zero variance is handled analytically.
 */
fun welchTTest(a: List<Double>, b: List<Double>): WelchResult? {
    if (a.size < 2 || b.size < 2) return null
    val meanA = mean(a)
    val meanB = mean(b)
    val varA = (sampleSd(a) ?: return null).pow(2)
    val varB = (sampleSd(b) ?: return null).pow(2)
    val termA = varA / a.size
    val termB = varB / b.size
    val standardError = sqrt(termA + termB)
    val diff = meanA - meanB

    if (standardError == 0.0) {
        if (diff == 0.0) return WelchResult(0.0, (a.size + b.size - 2).toDouble(), 1.0)
        return WelchResult(if (diff > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY, (a.size + b.size - 2).toDouble(), 0.0)
    }
    val t = diff / standardError
    val numerator = (termA + termB).pow(2)
    val denominator = termA.pow(2) / (a.size - 1) + termB.pow(2) / (b.size - 1)
    val df = if (denominator == 0.0) (a.size + b.size - 2).toDouble() else numerator / denominator
    val p = studentTTwoSidedP(t, df) ?: return null
    return WelchResult(t, df, p)
}

// ------------------------------------------------------------- regression

class Regression(
    val slope: Double,
    val intercept: Double,
    val r2: Double,
    val n: Int,
    val xMin: Double,
    val xMax: Double,
)

/** Ordinary least squares fit of y = slope * x + intercept. */
fun linearRegression(points: List<Pair<Double, Double>>): Regression? {
    val n = points.size
    if (n < 2) return null
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    for ((x, y) in points) {
        sumX += x
        sumY += y
        sumXX += x * x
        sumXY += x * y
    }
    val denominator = n * sumXX - sumX * sumX
    if (denominator == 0.0) return null
    val slope = (n * sumXY - sumX * sumY) / denominator
    val intercept = (sumY - slope * sumX) / n
    val meanY = sumY / n
    var ssTot = 0.0
    var ssRes = 0.0
    for ((x, y) in points) {
        val predicted = slope * x + intercept
        ssTot += (y - meanY).pow(2)
        ssRes += (y - predicted).pow(2)
    }
    val r2 = if (ssTot == 0.0) 0.0 else 1 - ssRes / ssTot
    val xs = points.map { it.first }
    return Regression(
        slope = slope,
        intercept = intercept,
        r2 = if (r2.isFinite()) r2.coerceIn(0.0, 1.0) else 0.0,
        n = n,
        xMin = xs.min(),
        xMax = xs.max(),
    )
}

/** Amplification efficiency from a standard-curve slope: 10^(-1/slope) - 1. */
fun efficiencyFromSlope(slope: Double): Double {
    if (!slope.isFinite() || slope == 0.0) return Double.NaN
    return 10.0.pow(-1 / slope) - 1
}
