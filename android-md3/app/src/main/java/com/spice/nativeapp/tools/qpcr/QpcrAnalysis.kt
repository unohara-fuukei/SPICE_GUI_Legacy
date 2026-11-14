package com.spice.nativeapp.tools.qpcr

/*
 * QC, Delta Ct / Delta Delta Ct analysis, standard-curve fitting and chart data.
 * 1:1 port of main's src/lib/tools/qpcr/analysis.ts.
 */

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow

object QcFlags {
    const val MISSING_CT = "missingCt"
    const val HIGH_CT = "highCt"
    const val REPLICATE_SD = "replicateSd"
    const val OUTLIER = "outlier"
    const val DUPLICATE_WELL = "duplicateWell"
    const val MISSING_REF = "missingRef"
    const val INVALID = "invalid"
}

class WellQc(
    val flags: List<String>,
    val replicateN: Int,
    val replicateMean: Double?,
    val replicateSd: Double?,
)

fun normKey(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

class InfoItem(val key: String, val label: String)

private fun uniqueInfo(wells: List<QpcrWell>, pick: (QpcrWell) -> String): List<InfoItem> {
    val map = LinkedHashMap<String, String>()
    for (well in wells) {
        val raw = pick(well).trim()
        val key = normKey(raw)
        if (key.isNotEmpty() && key !in map) map[key] = raw
    }
    // localeCompare(sensitivity: base) 对中文/ASCII 样本名与二进制排序几乎一致；
    // 保持与 main 相同的稳定语义（按 label 基本排序，大小写不敏感）。
    return map.entries.map { InfoItem(it.key, it.value) }
        .sortedWith(compareBy { it.label.lowercase(Locale.ROOT) })
}

fun listTargets(wells: List<QpcrWell>): List<InfoItem> = uniqueInfo(wells) { it.target }

fun listSamples(wells: List<QpcrWell>): List<InfoItem> = uniqueInfo(wells) { it.sample }

fun listGroups(wells: List<QpcrWell>): List<InfoItem> = uniqueInfo(wells) { it.group }

fun resolveInfo(label: String, items: List<InfoItem>): InfoItem? {
    val key = normKey(label)
    if (key.isEmpty()) return null
    return items.firstOrNull { it.key == key }
}

fun isStandardWell(well: QpcrWell): Boolean = well.quantity !== null

fun computeQc(wells: List<QpcrWell>, settings: QpcrSettings): Map<String, WellQc> {
    val highCt = if (settings.highCt.isFinite()) settings.highCt else Double.POSITIVE_INFINITY
    val sdLimit = if (settings.sdLimit.isFinite()) settings.sdLimit else Double.POSITIVE_INFINITY
    val outlierLimit = if (settings.outlierLimit.isFinite()) settings.outlierLimit else Double.POSITIVE_INFINITY
    val referenceKeys = (settings.referenceTargets + (if (settings.referenceTarget.isNotEmpty()) listOf(settings.referenceTarget) else emptyList()))
        .map { normKey(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    val replicateGroups = LinkedHashMap<String, MutableList<QpcrWell>>()
    val wellCounts = HashMap<String, Int>()

    for (well in wells) {
        val sampleKey = normKey(well.sample)
        val targetKey = normKey(well.target)
        if (sampleKey.isNotEmpty() && targetKey.isNotEmpty()) {
            val key = "$sampleKey\u0000$targetKey" // \u0000 分隔在 Kotlin 中同样不可见，用空格保持语义等价分隔
            replicateGroups.getOrPut(key) { mutableListOf() }.add(well)
        }
        val wellKey = normKey(well.well)
        if (wellKey.isNotEmpty()) wellCounts[wellKey] = (wellCounts[wellKey] ?: 0) + 1
    }

    data class RepStats(val n: Int, val mean: Double?, val sd: Double?)
    val replicateStats = HashMap<String, RepStats>()
    for ((key, list) in replicateGroups) {
        val values = list.filter { it.ct !== null }.map { it.ct!! }
        replicateStats[key] = RepStats(values.size, if (values.isNotEmpty()) mean(values) else null, sampleSd(values))
    }

    // Outlier flags use the replicate mean as requested (1:1 including the
    // all-candidates edge case: only the farthest well is flagged).
    val outlierWells = mutableSetOf<String>()
    for (list in replicateGroups.values) {
        val measured = list.filter { it.ct !== null }
        if (measured.size < 3) continue
        val center = mean(measured.map { it.ct!! })
        val candidates = measured.filter { abs(it.ct!! - center) > outlierLimit }
        if (candidates.isEmpty()) continue
        if (candidates.size == measured.size) {
            var farthest = candidates[0]
            for (candidate in candidates) {
                if (abs(candidate.ct!! - center) > abs(farthest.ct!! - center)) farthest = candidate
            }
            outlierWells.add(farthest.id)
        } else {
            for (candidate in candidates) outlierWells.add(candidate.id)
        }
    }

    val out = HashMap<String, WellQc>()
    for (well in wells) {
        val flags = ArrayList<String>()
        val sampleKey = normKey(well.sample)
        val targetKey = normKey(well.target)
        val wellKey = normKey(well.well)
        if (sampleKey.isEmpty() || targetKey.isEmpty()) flags.add(QcFlags.INVALID)
        val ctValue = well.ct
        if (ctValue === null) {
            flags.add(QcFlags.MISSING_CT)
            if (targetKey in referenceKeys) flags.add(QcFlags.MISSING_REF)
        } else if (ctValue > highCt) flags.add(QcFlags.HIGH_CT)

        val groupKey = "$sampleKey\u0000$targetKey"
        val stats = replicateStats[groupKey]
        if (stats != null && well.ct !== null) {
            if (stats.n >= 2 && stats.sd !== null && stats.sd > sdLimit) flags.add(QcFlags.REPLICATE_SD)
            if (well.id in outlierWells) flags.add(QcFlags.OUTLIER)
        }
        if (wellKey.isNotEmpty() && (wellCounts[wellKey] ?: 0) > 1) flags.add(QcFlags.DUPLICATE_WELL)

        out[well.id] = WellQc(flags, stats?.n ?: 0, stats?.mean, stats?.sd)
    }
    return out
}

fun usableWell(well: QpcrWell, qc: WellQc?, settings: QpcrSettings): Boolean {
    if (!well.include) return false
    if (well.sample.trim().isEmpty() || well.target.trim().isEmpty()) return false
    if (well.ct === null) return false
    if (settings.excludeOutliers && qc?.flags?.contains(QcFlags.OUTLIER) == true) return false
    return true
}

private fun efficiencyForTarget(targetKey: String, wells: List<QpcrWell>, settings: QpcrSettings): Double {
    val values = wells.filter { normKey(it.target) == targetKey && it.efficiency !== null }.map { it.efficiency!! }
    if (values.isNotEmpty()) return mean(values)
    val pct = if (settings.defaultEfficiencyPct.isFinite()) settings.defaultEfficiencyPct else 100.0
    return maxOf(0.01, pct / 100)
}

// --------------------------------------------------------------- Delta Ct

class DdctReplicate(
    val sample: String,
    val sampleKey: String,
    val target: String,
    val targetKey: String,
    val group: String,
    val category: String,
    val replicate: Double?,
    val well: String,
    val ct: Double,
    val refCt: Double,
    val dCt: Double,
    val ddCt: Double,
    val fold: Double,
    val isControl: Boolean,
)

class DdctSample(
    val sample: String,
    val sampleKey: String,
    val target: String,
    val targetKey: String,
    val group: String,
    val category: String,
    val n: Int,
    val meanCt: Double,
    val sdCt: Double?,
    val dCt: Double,
    val ddCt: Double,
    val efficiency: Double,
    val fold: Double,
    val foldSd: Double?,
    val foldSem: Double?,
    val foldValues: List<Double>,
    val isControl: Boolean,
)

class DdctGroup(
    val category: String,
    val categoryKey: String,
    val target: String,
    val targetKey: String,
    val nSamples: Int,
    val meanFold: Double,
    val sdFold: Double?,
    val semFold: Double?,
    val meanDCt: Double,
    val sdDCt: Double?,
    val pValue: Double?,
    val foldValues: List<Double>,
    val isControl: Boolean,
)

class DdctMatrixRow(
    val sample: String,
    val sampleKey: String,
    val group: String,
    val category: String,
    val isControl: Boolean,
    val refCt: Double?,
    /** Selected reference targets without a Ct value for this sample. */
    val referenceMissing: List<String>,
    val dCt: Map<String, Double?>,
    val ddCt: Map<String, Double?>,
)

class DdctAnalysis(
    /** First selected reference, kept for single-reference callers. */
    val reference: InfoItem?,
    val references: List<InfoItem>,
    val targets: List<InfoItem>,
    val samples: List<InfoItem>,
    val groups: List<InfoItem>,
    val hasGroups: Boolean,
    val matrix: List<DdctMatrixRow>,
    val sampleResults: List<DdctSample>,
    val groupResults: List<DdctGroup>,
    val replicates: List<DdctReplicate>,
    val controlMeanDCt: Map<String, Double?>,
    val controlLabel: String,
    val controlSampleKeys: List<String>,
    val missingReference: Boolean,
    val missingControl: Boolean,
    val referenceFallback: Boolean,
    val controlFallback: Boolean,
    /** Samples where at least one selected reference target has no Ct. */
    val referenceMissingSamples: List<DdctMatrixRow>,
)

private class SampleBucket(
    val sample: String,
    val sampleKey: String,
    val group: String,
    val category: String,
    var isControl: Boolean,
    val refWells: LinkedHashMap<String, MutableList<QpcrWell>>,
    val targetWells: LinkedHashMap<String, MutableList<QpcrWell>>,
)

/** Geometric mean of positive Ct values; null when no usable value exists. */
private fun geometricMean(values: List<Double>): Double? {
    val usable = values.filter { it.isFinite() && it > 0 }
    if (usable.isEmpty()) return null
    var logSum = 0.0
    for (value in usable) logSum += ln(value)
    return exp(logSum / usable.size)
}

fun computeDdct(
    wells: List<QpcrWell>,
    settings: QpcrSettings,
    qc: Map<String, WellQc>,
): DdctAnalysis {
    // Standards are calibration wells: they must not appear as samples, groups or
    // selectable reference targets in the Delta Delta Ct analysis.
    val analysisWells = wells.filter { !isStandardWell(it) }
    val targets = listTargets(analysisWells)
    val samples = listSamples(analysisWells)
    val groups = listGroups(analysisWells)
    val hasGroups = groups.isNotEmpty()
    val requestedLabels = (settings.referenceTargets + (if (settings.referenceTarget.isNotEmpty()) listOf(settings.referenceTarget) else emptyList()))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val requestedKeys = ArrayList<String>()
    for (label in requestedLabels) {
        val key = normKey(label)
        if (key.isNotEmpty() && key !in requestedKeys) requestedKeys.add(key)
    }
    val resolvedReferences = ArrayList<InfoItem>()
    for (key in requestedKeys) {
        val item = targets.firstOrNull { it.key == key }
        if (item != null && resolvedReferences.none { it.key == item.key }) resolvedReferences.add(item)
    }
    val references = if (resolvedReferences.isNotEmpty()) resolvedReferences else targets.take(1)
    val reference = references.firstOrNull()
    val referenceFallback = references.isNotEmpty() &&
        (requestedKeys.isEmpty() || resolvedReferences.size < requestedKeys.size)
    val referenceKeys = references.map { it.key }.toSet()

    val groupLabelByKey = groups.associate { it.key to it.label }
    val sampleLabelByKey = samples.associate { it.key to it.label }
    val targetLabelByKey = targets.associate { it.key to it.label }

    val buckets = LinkedHashMap<String, SampleBucket>()
    for (well in wells) {
        if (!usableWell(well, qc[well.id], settings)) continue
        if (isStandardWell(well)) continue
        val sampleKey = normKey(well.sample)
        val targetKey = normKey(well.target)
        var bucket = buckets[sampleKey]
        if (bucket == null) {
            val groupKey = normKey(well.group)
            val groupLabel = if (groupKey.isNotEmpty()) groupLabelByKey[groupKey] ?: well.group.trim() else ""
            bucket = SampleBucket(
                sample = sampleLabelByKey[sampleKey] ?: well.sample.trim(),
                sampleKey = sampleKey,
                group = groupLabel,
                category = if (hasGroups && groupLabel.isNotEmpty()) groupLabel
                    else sampleLabelByKey[sampleKey] ?: well.sample.trim(),
                isControl = false,
                refWells = LinkedHashMap(),
                targetWells = LinkedHashMap(),
            )
            buckets[sampleKey] = bucket
        }
        if (targetKey in referenceKeys) {
            bucket.refWells.getOrPut(targetKey) { mutableListOf() }.add(well)
        } else {
            bucket.targetWells.getOrPut(targetKey) { mutableListOf() }.add(well)
        }
    }

    val orderedKeys = buckets.keys.sortedWith(compareBy({ buckets[it]!!.category.lowercase(Locale.ROOT) }, { buckets[it]!!.sample.lowercase(Locale.ROOT) }))

    var controlKeys = emptySet<String>()
    var controlLabel = ""
    var controlFallback = true
    if (hasGroups && settings.controlGroup.trim().isNotEmpty()) {
        val groupKey = normKey(settings.controlGroup)
        val group = groups.firstOrNull { it.key == groupKey }
        if (group != null) {
            val keys = orderedKeys.filter { normKey(buckets[it]!!.group) == groupKey }.toSet()
            if (keys.isNotEmpty()) {
                controlKeys = keys
                controlLabel = group.label
                controlFallback = false
            }
        }
    }
    if (controlKeys.isEmpty() && settings.controlSample.trim().isNotEmpty()) {
        val sample = samples.firstOrNull { it.key == normKey(settings.controlSample) }
        if (sample != null && buckets.containsKey(sample.key)) {
            controlKeys = setOf(sample.key)
            controlLabel = sample.label
            controlFallback = false
        }
    }
    if (controlKeys.isEmpty()) {
        val first = orderedKeys.firstOrNull()
        if (first != null) {
            controlKeys = setOf(first)
            controlLabel = buckets[first]!!.sample
        }
        controlFallback = true
    }
    for (key in controlKeys) {
        buckets[key]?.isControl = true
    }

    val refCtBySample = HashMap<String, Double?>()
    val refMissingBySample = HashMap<String, List<String>>()
    for (key in orderedKeys) {
        val bucket = buckets[key]!!
        val perTarget = ArrayList<Double>()
        val missing = ArrayList<String>()
        for (ref in references) {
            val refWells = bucket.refWells[ref.key] ?: emptyList()
            val values = refWells.map { it.ct }.filterNotNull()
            if (values.isNotEmpty()) perTarget.add(mean(values)) else missing.add(ref.label)
        }
        refCtBySample[key] = geometricMean(perTarget)
        refMissingBySample[key] = missing
    }
    val referenceUsableInControl = references.isNotEmpty() &&
        controlKeys.any { (refCtBySample[it] ?: null) !== null }

    val controlMeanDCt = LinkedHashMap<String, Double?>()
    for (target in targets) {
        if (target.key in referenceKeys) {
            controlMeanDCt[target.key] = 0.0
            continue
        }
        val values = ArrayList<Double>()
        for (key in controlKeys) {
            val bucket = buckets[key]
            val refCt = refCtBySample[key] ?: null
            if (bucket == null || refCt === null) continue
            val targetWells = bucket.targetWells[target.key]
            if (targetWells.isNullOrEmpty()) continue
            val ctValues = targetWells.map { it.ct!! }
            values.add(mean(ctValues) - refCt)
        }
        controlMeanDCt[target.key] = if (values.isNotEmpty()) mean(values) else null
    }

    val matrix = ArrayList<DdctMatrixRow>()
    val sampleResults = ArrayList<DdctSample>()
    val replicates = ArrayList<DdctReplicate>()

    for (key in orderedKeys) {
        val bucket = buckets[key]!!
        val refCt = refCtBySample[key] ?: null
        val dCt = LinkedHashMap<String, Double?>()
        val ddCt = LinkedHashMap<String, Double?>()

        for (target in targets) {
            var value: Double? = null
            if (target.key in referenceKeys) {
                value = if (refCt === null) null else 0.0
            } else if (refCt !== null) {
                val list = bucket.targetWells[target.key]
                if (!list.isNullOrEmpty()) value = mean(list.map { it.ct!! }) - refCt
            }
            dCt[target.key] = value
            val control = controlMeanDCt[target.key]
            ddCt[target.key] = if (value !== null && control !== null) value - control else null
        }

        matrix.add(
            DdctMatrixRow(
                sample = bucket.sample,
                sampleKey = bucket.sampleKey,
                group = bucket.group,
                category = bucket.category,
                isControl = bucket.isControl,
                refCt = refCt,
                referenceMissing = refMissingBySample[key] ?: emptyList(),
                dCt = dCt,
                ddCt = ddCt,
            )
        )

        if (refCt === null) continue
        for (target in targets) {
            if (target.key in referenceKeys) continue
            val targetWells = bucket.targetWells[target.key]
            val controlMean = controlMeanDCt[target.key]
            if (targetWells.isNullOrEmpty() || controlMean === null) continue

            val ctValues = targetWells.map { it.ct!! }
            val meanCt = mean(ctValues)
            val sdCt = sampleSd(ctValues)
            val dCtValue = meanCt - refCt
            val ddCtValue = dCtValue - controlMean
            val efficiency = efficiencyForTarget(target.key, wells, settings)
            val base = if (settings.efficiencyCorrection) 1 + efficiency else 2.0
            val foldValues = targetWells.map { base.pow(-(it.ct!! - refCt - controlMean)) }
            val fold = mean(foldValues)

            sampleResults.add(
                DdctSample(
                    sample = bucket.sample,
                    sampleKey = bucket.sampleKey,
                    target = targetLabelByKey[target.key] ?: target.label,
                    targetKey = target.key,
                    group = bucket.group,
                    category = bucket.category,
                    n = targetWells.size,
                    meanCt = meanCt,
                    sdCt = sdCt,
                    dCt = dCtValue,
                    ddCt = ddCtValue,
                    efficiency = efficiency,
                    fold = fold,
                    foldSd = sampleSd(foldValues),
                    foldSem = sem(foldValues),
                    foldValues = foldValues,
                    isControl = bucket.isControl,
                )
            )

            for (well in targetWells) {
                val ct = well.ct!!
                val repDCt = ct - refCt
                val repDDCt = repDCt - controlMean
                replicates.add(
                    DdctReplicate(
                        sample = bucket.sample,
                        sampleKey = bucket.sampleKey,
                        target = targetLabelByKey[target.key] ?: target.label,
                        targetKey = target.key,
                        group = bucket.group,
                        category = bucket.category,
                        replicate = well.replicate,
                        well = well.well,
                        ct = ct,
                        refCt = refCt,
                        dCt = repDCt,
                        ddCt = repDDCt,
                        fold = base.pow(-repDDCt),
                        isControl = bucket.isControl,
                    )
                )
            }
        }
    }

    sampleResults.sortWith(
        compareBy(
            { it.category.lowercase(Locale.ROOT) },
            { it.sample.lowercase(Locale.ROOT) },
            { it.target.lowercase(Locale.ROOT) },
        )
    )
    replicates.sortWith(
        compareBy(
            { it.category.lowercase(Locale.ROOT) },
            { it.sample.lowercase(Locale.ROOT) },
            { it.target.lowercase(Locale.ROOT) },
            { it.replicate ?: 0.0 },
        )
    )

    // Group-level summaries and Welch tests against the control samples.
    val groupResults = ArrayList<DdctGroup>()
    val categoryOrder = ArrayList<String>()
    for (row in matrix) {
        if (row.category !in categoryOrder) categoryOrder.add(row.category)
    }
    val resolvedTargets = targets.filter { it.key !in referenceKeys }
    for (category in categoryOrder) {
        val categoryKey = normKey(category)
        val controlValuesByTarget = HashMap<String, List<Double>>()
        for (target in resolvedTargets) {
            val values = matrix.filter { it.isControl && it.dCt[target.key] !== null }.map { it.dCt[target.key]!! }
            controlValuesByTarget[target.key] = values
        }
        for (target in resolvedTargets) {
            val rows = sampleResults.filter { it.category == category && it.targetKey == target.key }
            if (rows.isEmpty()) continue
            val foldValues = rows.map { it.fold }
            val dCtValues = rows.map { it.dCt }
            val isControl = rows.all { it.isControl }
            val controlValues = controlValuesByTarget[target.key] ?: emptyList()
            val test = if (!isControl && controlValues.size >= 2 && dCtValues.size >= 2) welchTTest(dCtValues, controlValues) else null
            groupResults.add(
                DdctGroup(
                    category = category,
                    categoryKey = categoryKey,
                    target = target.label,
                    targetKey = target.key,
                    nSamples = rows.size,
                    meanFold = mean(foldValues),
                    sdFold = sampleSd(foldValues),
                    semFold = sem(foldValues),
                    meanDCt = mean(dCtValues),
                    sdDCt = sampleSd(dCtValues),
                    pValue = test?.p,
                    foldValues = foldValues,
                    isControl = isControl,
                )
            )
        }
    }

    return DdctAnalysis(
        reference = reference,
        references = references,
        targets = targets,
        samples = samples,
        groups = groups,
        hasGroups = hasGroups,
        matrix = matrix,
        sampleResults = sampleResults,
        groupResults = groupResults,
        replicates = replicates,
        controlMeanDCt = controlMeanDCt,
        controlLabel = controlLabel,
        controlSampleKeys = controlKeys.toList(),
        missingReference = references.isEmpty() || resolvedTargets.isEmpty() || !referenceUsableInControl,
        missingControl = controlKeys.isEmpty(),
        referenceFallback = referenceFallback,
        controlFallback = controlFallback,
        referenceMissingSamples = matrix.filter { it.referenceMissing.isNotEmpty() },
    )
}

// ----------------------------------------------------------- chart data

fun chartDataFromDdct(analysis: DdctAnalysis): ChartData? {
    if (analysis.groupResults.isEmpty()) return null
    val categories = ArrayList<String>()
    for (row in analysis.groupResults) {
        if (row.category !in categories) categories.add(row.category)
    }
    val seriesTargets = ArrayList<InfoItem>()
    for (row in analysis.groupResults) {
        if (seriesTargets.none { it.key == row.targetKey }) seriesTargets.add(InfoItem(row.targetKey, row.target))
    }
    val series = seriesTargets.map { target ->
        ChartSeries(
            name = target.label,
            bars = categories.map { category ->
                val row = analysis.groupResults.firstOrNull { it.category == category && it.targetKey == target.key }
                    ?: return@map null
                ChartBar(
                    value = row.meanFold,
                    points = row.foldValues,
                    sem = row.semFold,
                    logSem = null,
                    n = row.nSamples,
                )
            },
        )
    }
    return ChartData(categories, series, log = false)
}

// ------------------------------------------------------- standard curve

object CurveReasons {
    const val OK = "ok"
    const val NO_POINTS = "noPoints"
    const val FEW_POINTS = "fewPoints"
    const val NO_VARIANCE = "noVariance"
    const val POSITIVE_SLOPE = "positiveSlope"
}

class CurveFit(
    val targetKey: String,
    val target: String,
    val slope: Double?,
    val intercept: Double?,
    val r2: Double?,
    val efficiency: Double?,
    val n: Int,
    val xMin: Double?,
    val xMax: Double?,
    val valid: Boolean,
    val reason: String,
)

class StandardSample(
    val sample: String,
    val sampleKey: String,
    val target: String,
    val targetKey: String,
    val group: String,
    val category: String,
    val n: Int,
    val meanCt: Double,
    val log10Quantity: Double,
    val quantity: Double,
    val sdLog: Double?,
    val semLog: Double?,
    val quantityValues: List<Double>,
)

class StandardAnalysis(
    val fits: List<CurveFit>,
    val samples: List<StandardSample>,
    val missingStandards: Boolean,
)

private fun standardX(quantity: Double, isLog10: Boolean): Double? {
    if (isLog10) return if (quantity.isFinite()) quantity else null
    if (quantity <= 0) return null
    return log10(quantity)
}

fun computeStandard(
    wells: List<QpcrWell>,
    settings: QpcrSettings,
    qc: Map<String, WellQc>,
): StandardAnalysis {
    val targets = listTargets(wells)
    val hasGroups = listGroups(wells).isNotEmpty()
    val groupLabelByKey = listGroups(wells).associate { it.key to it.label }
    val sampleLabelByKey = listSamples(wells).associate { it.key to it.label }

    val pointsByTarget = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
    var totalStandardPoints = 0
    for (well in wells) {
        if (!usableWell(well, qc[well.id], settings)) continue
        val quantity = well.quantity ?: continue
        val ct = well.ct ?: continue
        val x = standardX(quantity, settings.quantityIsLog10) ?: continue
        val key = normKey(well.target)
        if (key.isEmpty()) continue
        pointsByTarget.getOrPut(key) { mutableListOf() }.add(x to ct)
        totalStandardPoints++
    }

    val fits = ArrayList<CurveFit>()
    val fitByTarget = HashMap<String, CurveFit>()
    for (target in targets) {
        val points = pointsByTarget[target.key]
        if (points.isNullOrEmpty()) continue
        val fit: CurveFit = if (points.size < 2) {
            CurveFit(target.key, target.label, null, null, null, null, points.size, null, null, false, CurveReasons.FEW_POINTS)
        } else {
            val regression = linearRegression(points)
            if (regression == null) {
                CurveFit(
                    target.key, target.label, null, null, null, null, points.size,
                    points.minOf { it.first }, points.maxOf { it.first },
                    false, CurveReasons.NO_VARIANCE,
                )
            } else {
                val efficiency = efficiencyFromSlope(regression.slope)
                val valid = regression.slope < 0 && efficiency.isFinite()
                CurveFit(
                    target.key, target.label,
                    regression.slope, regression.intercept, regression.r2, efficiency,
                    regression.n, regression.xMin, regression.xMax,
                    valid, if (valid) CurveReasons.OK else CurveReasons.POSITIVE_SLOPE,
                )
            }
        }
        fits.add(fit)
        fitByTarget[target.key] = fit
    }

    class SampleBucket(
        var sample: String = "",
        var sampleKey: String = "",
        var target: String = "",
        var targetKey: String = "",
        var group: String = "",
        var category: String = "",
        val ctValues: MutableList<Double> = mutableListOf(),
        val logValues: MutableList<Double> = mutableListOf(),
    )

    val sampleBuckets = LinkedHashMap<String, SampleBucket>()
    for (well in wells) {
        if (!usableWell(well, qc[well.id], settings)) continue
        if (well.quantity !== null) continue
        val ct = well.ct ?: continue
        val targetKey = normKey(well.target)
        val sampleKey = normKey(well.sample)
        val fit = fitByTarget[targetKey]
        if (sampleKey.isEmpty() || fit == null || !fit.valid || fit.slope == null || fit.slope == 0.0 || fit.intercept == null) {
            continue
        }
        val logQuantity = (ct - fit.intercept) / fit.slope
        val bucketKey = "$sampleKey\u0000$targetKey"
        var bucket = sampleBuckets[bucketKey]
        if (bucket == null) {
            val groupKey = normKey(well.group)
            val groupLabel = if (groupKey.isNotEmpty()) groupLabelByKey[groupKey] ?: well.group.trim() else ""
            bucket = SampleBucket(
                sample = sampleLabelByKey[sampleKey] ?: well.sample.trim(),
                sampleKey = sampleKey,
                target = fit.target,
                targetKey = targetKey,
                group = groupLabel,
                category = if (hasGroups && groupLabel.isNotEmpty()) groupLabel
                    else sampleLabelByKey[sampleKey] ?: well.sample.trim(),
            )
            sampleBuckets[bucketKey] = bucket
        }
        bucket.ctValues.add(ct)
        bucket.logValues.add(logQuantity)
    }

    val samples = sampleBuckets.values.map { bucket ->
        val meanCt = mean(bucket.ctValues)
        val meanLog = mean(bucket.logValues)
        StandardSample(
            sample = bucket.sample,
            sampleKey = bucket.sampleKey,
            target = bucket.target,
            targetKey = bucket.targetKey,
            group = bucket.group,
            category = bucket.category,
            n = bucket.ctValues.size,
            meanCt = meanCt,
            log10Quantity = meanLog,
            quantity = 10.0.pow(meanLog),
            sdLog = sampleSd(bucket.logValues),
            semLog = sem(bucket.logValues),
            quantityValues = bucket.logValues.map { 10.0.pow(it) },
        )
    }.sortedWith(
        compareBy(
            { it.category.lowercase(Locale.ROOT) },
            { it.sample.lowercase(Locale.ROOT) },
            { it.target.lowercase(Locale.ROOT) },
        )
    )

    return StandardAnalysis(fits, samples, missingStandards = totalStandardPoints == 0)
}

fun chartDataFromStandard(analysis: StandardAnalysis): ChartData? {
    if (analysis.samples.isEmpty()) return null
    val categories = ArrayList<String>()
    for (row in analysis.samples) {
        if (row.category !in categories) categories.add(row.category)
    }
    val seriesTargets = ArrayList<InfoItem>()
    for (row in analysis.samples) {
        if (seriesTargets.none { it.key == row.targetKey }) seriesTargets.add(InfoItem(row.targetKey, row.target))
    }
    val series = seriesTargets.map { target ->
        ChartSeries(
            name = target.label,
            bars = categories.map { category ->
                val rows = analysis.samples.filter { it.category == category && it.targetKey == target.key }
                if (rows.isEmpty()) return@map null
                val logValues = rows.map { it.log10Quantity }
                val meanLog = mean(logValues)
                ChartBar(
                    value = 10.0.pow(meanLog),
                    points = rows.map { it.quantity },
                    sem = null,
                    logSem = sem(logValues),
                    n = rows.size,
                )
            },
        )
    }
    return ChartData(categories, series, log = true)
}
