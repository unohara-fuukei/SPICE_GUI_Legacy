package com.spice.nativeapp.tools.blot

/*
 * Densitometry: ROI integration on the inverted raw grayscale plane with local
 * background subtraction, then loading-control / total-lane normalisation and
 * per-group replicate statistics. 1:1 port of main's
 * src/lib/tools/blot/quant.ts.
 */

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BandMeasurement(
    val laneId: String,
    val bandId: String,
    val laneIndex: Int,
    val bandIndex: Int,
    val laneName: String,
    val sample: String,
    val group: String,
    val included: Boolean,
    val area: Double,
    val meanSignal: Double,
    val background: Double,
    val netVolume: Double,
    val clippedFraction: Double,
    val saturated: Boolean,
    var normalized: Double,
    var fold: Double,
    var groupMean: Double,
    var groupSd: Double,
    var groupSem: Double,
    /** Half-width of the 95% confidence interval of the group mean. */
    var groupCi95: Double,
    /** Two-sided Welch t-test p value versus the control group; null for control. */
    var groupP: Double?,
    var groupN: Int,
)

class GroupStat(
    val name: String,
    val n: Int,
    val mean: Double,
    val sd: Double,
    val sem: Double,
    /** Half-width of the 95% confidence interval of the mean. */
    val ci95: Double,
    /** Two-sided Welch t-test p value versus the control group; null for control. */
    val pValue: Double?,
    val values: DoubleArray,
    val laneCount: Int,
)

class QuantResult(
    val rows: List<BandMeasurement>,
    val groups: List<GroupStat>,
    val controlMean: Double,
    val totalVolume: Double,
    val hasData: Boolean,
)

private fun median(values: MutableList<Double>): Double {
    if (values.isEmpty()) return 0.0
    values.sort()
    return values[floor(values.size / 2.0).toInt()]
}

class Roi(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/** ROI rectangle from a lane centre and a band, in pixel coordinates. */
fun roiForBand(lane: BlotLane, band: BlotBand, width: Int, height: Int): Roi {
    val cx = lane.x * width
    val halfW = max(1.0, (band.width * width) / 2)
    val halfH = max(1.0, (band.height * height) / 2)
    val cy = band.y * height
    return Roi(
        clamp((cx - halfW).roundToInt().toDouble(), 0.0, (width - 1).toDouble()).toInt(),
        clamp((cy - halfH).roundToInt().toDouble(), 0.0, (height - 1).toDouble()).toInt(),
        clamp((cx + halfW).roundToInt().toDouble(), 1.0, width.toDouble()).toInt(),
        clamp((cy + halfH).roundToInt().toDouble(), 1.0, height.toDouble()).toInt(),
    )
}

private fun ringBackground(gray: FloatArray, width: Int, height: Int, roi: Roi): Double {
    val ring = max(1, (min((roi.x1 - roi.x0).toDouble(), (roi.y1 - roi.y0).toDouble()) * 0.18).roundToInt())
    val values = mutableListOf<Double>()
    for (y in roi.y0 until roi.y1) {
        for (x in roi.x0 until roi.x1) {
            if (x < roi.x0 + ring || x >= roi.x1 - ring || y < roi.y0 + ring || y >= roi.y1 - ring) {
                values.add((1 - gray[y * width + x]).toDouble())
            }
        }
    }
    return median(values)
}

private fun laneBackground(gray: FloatArray, width: Int, height: Int, lane: BlotLane, roi: Roi): Double {
    val x0 = clamp(((lane.x - lane.width / 2) * width).roundToInt().toDouble(), 0.0, (width - 1).toDouble()).toInt()
    val x1 = clamp(((lane.x + lane.width / 2) * width).roundToInt().toDouble(), (x0 + 1).toDouble(), width.toDouble()).toInt()
    val values = mutableListOf<Double>()
    val step = max(1, (x1 - x0) / 48)
    for (y in 0 until height) {
        if (y >= roi.y0 && y < roi.y1) continue
        var x = x0
        while (x < x1) {
            values.add((1 - gray[y * width + x]).toDouble())
            x += step
        }
    }
    return median(values)
}

class MeasureContext(
    val gray: FloatArray,
    val width: Int,
    val height: Int,
    val project: BlotProject,
    val globalBg: Double,
)

fun measureBand(
    lane: BlotLane,
    band: BlotBand,
    laneIndex: Int,
    bandIndex: Int,
    context: MeasureContext,
): BandMeasurement {
    val gray = context.gray
    val width = context.width
    val height = context.height
    val project = context.project
    val globalBg = context.globalBg
    val roi = roiForBand(lane, band, width, height)
    val area = max(0.0, ((roi.x1 - roi.x0) * (roi.y1 - roi.y0)).toDouble())
    var sum = 0.0
    var clipped = 0
    for (y in roi.y0 until roi.y1) {
        for (x in roi.x0 until roi.x1) {
            val v = gray[y * width + x]
            sum += 1.0 - v
            if (v <= 0.004f || v >= 0.996f) clipped++
        }
    }
    val mode = project.backgroundMode
    val background = when (mode) {
        "manual" -> clamp(project.manualBackground, 0.0, 1.0)
        "lane" -> laneBackground(gray, width, height, lane, roi)
        "global" -> globalBg
        else -> ringBackground(gray, width, height, roi)
    }
    var net = 0.0
    for (y in roi.y0 until roi.y1) {
        for (x in roi.x0 until roi.x1) {
            val signal = 1.0 - gray[y * width + x]
            val corrected = signal - background
            if (corrected > 0) net += corrected
        }
    }
    val clippedFraction = clipped / max(1.0, area)
    return BandMeasurement(
        laneId = lane.id,
        bandId = band.id,
        laneIndex = laneIndex,
        bandIndex = bandIndex,
        laneName = lane.name,
        sample = lane.sample.ifEmpty { lane.name },
        group = lane.group.trim(),
        included = !lane.excluded && !band.excluded,
        area = area,
        meanSignal = sum / max(1.0, area),
        background = background,
        netVolume = net,
        clippedFraction = clippedFraction,
        saturated = clippedFraction > 0.005,
        normalized = 0.0,
        fold = 0.0,
        groupMean = 0.0,
        groupSd = 0.0,
        groupSem = 0.0,
        groupCi95 = 0.0,
        groupP = null,
        groupN = 0,
    )
}

/** Measure every band, apply the selected normalisation and fold change, then
 *  attach per-group statistics to each row. */
fun quantify(project: BlotProject, gray: FloatArray, width: Int, height: Int): QuantResult {
    val globalBg = globalBackground(gray, width, height)
    val context = MeasureContext(gray, width, height, project, globalBg)
    val rows = mutableListOf<BandMeasurement>()
    project.lanes.forEachIndexed { laneIndex, lane ->
        lane.bands.forEachIndexed { bandIndex, band ->
            rows.add(measureBand(lane, band, laneIndex, bandIndex, context))
        }
    }

    val includedRows = rows.filter { it.included }
    val laneTotals = HashMap<String, Double>()
    for (row in includedRows) {
        laneTotals[row.laneId] = (laneTotals[row.laneId] ?: 0.0) + row.netVolume
    }

    var loadingReference = 1.0
    if (project.normalization.mode == "loading") {
        val laneId = project.normalization.loadingLaneId
        if (laneId != null) {
            loadingReference = includedRows.filter { it.laneId == laneId }.sumOf { it.netVolume }
        }
        if (!(loadingReference > 0)) loadingReference = 1.0
    }

    val normalizedMap = HashMap<String, Double>()
    for (row in rows) {
        var value = row.netVolume
        if (project.normalization.mode == "loading") {
            value = row.netVolume / loadingReference
        } else if (project.normalization.mode == "total") {
            val total = laneTotals[row.laneId] ?: 0.0
            value = if (total > 0) row.netVolume / total else 0.0
        }
        normalizedMap[row.bandId] = value
        row.normalized = if (row.included) value else 0.0
    }

    val controlGroup = project.normalization.controlGroup.trim()
    val controlValues = includedRows
        .filter { it.group == controlGroup }
        .map { normalizedMap[it.bandId] ?: 0.0 }
        .toDoubleArray()
    val controlMean = mean(controlValues)

    for (row in rows) {
        val normalized = normalizedMap[row.bandId] ?: 0.0
        row.normalized = if (row.included) normalized else 0.0
        row.fold = if (row.included && controlMean > 0) normalized / controlMean else 0.0
    }

    val groups = mutableListOf<GroupStat>()
    for (name in groupNames(project.lanes)) {
        val groupRows = includedRows.filter { it.group == name }
        val values = groupRows.map { normalizedMap[it.bandId] ?: 0.0 }.toDoubleArray()
        val m = mean(values)
        val sd = sampleSd(values)
        val laneCount = project.lanes.count { it.group.trim() == name && !it.excluded }
        val test = if (
            name != controlGroup && controlGroup.isNotEmpty() &&
            controlValues.size >= 2 && values.size >= 2
        ) welchTTest(values, controlValues) else null
        groups.add(
            GroupStat(
                name = name,
                n = values.size,
                mean = m,
                sd = sd,
                sem = sem(values),
                ci95 = ci95HalfWidth(values),
                pValue = test?.p,
                values = values,
                laneCount = laneCount,
            )
        )
    }

    for (row in rows) {
        val group = groups.find { it.name == row.group }
        row.groupMean = group?.mean ?: 0.0
        row.groupSd = group?.sd ?: 0.0
        row.groupSem = group?.sem ?: 0.0
        row.groupCi95 = group?.ci95 ?: 0.0
        row.groupP = group?.pValue
        row.groupN = group?.n ?: 0
    }

    val totalVolume = includedRows.sumOf { it.netVolume }
    return QuantResult(rows, groups, controlMean, totalVolume, includedRows.isNotEmpty())
}

fun metricValue(row: BandMeasurement, metric: String): Double =
    if (metric == "fold") row.fold else row.normalized

fun groupMetricValues(group: GroupStat, rows: List<BandMeasurement>, metric: String): DoubleArray =
    rows.filter { it.included && it.group == group.name }
        .map { metricValue(it, metric) }
        .toDoubleArray()

/** Group mean and SEM in the metric chosen for the chart. */
class GroupMetricStats(val mean: Double, val sem: Double, val values: DoubleArray)

fun groupMetricStats(group: GroupStat, rows: List<BandMeasurement>, metric: String): GroupMetricStats {
    val values = groupMetricValues(group, rows, metric)
    return GroupMetricStats(mean(values), sem(values), values)
}
