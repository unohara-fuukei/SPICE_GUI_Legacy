package com.spice.nativeapp.tools.qpcr

/*
 * CSV export. 1:1 port of main's src/lib/tools/qpcr/export.ts: raw wells with
 * QC flags, replicate and sample tables, group statistics, Delta Ct matrices
 * and standard-curve fits in one file.
 */

import java.time.Instant

private fun csvEscape(value: Any?): String {
    if (value === null) return ""
    val text = value.toString()
    if (Regex("[\",\\r\\n]").containsMatchIn(text)) return "\"" + text.replace("\"", "\"\"") + "\""
    return text
}

private fun row(values: List<Any?>): String = values.joinToString(",") { csvEscape(it) }

private fun num(value: Double?, digits: Int = 4): String {
    if (value === null || !value.isFinite()) return ""
    val rounded = String.format(java.util.Locale.US, "%.${digits}f", value).toDouble()
    return rounded.toString()
}

private fun numI(value: Double?, digits: Int = 0): String = num(value, digits)

private fun pValue(value: Double?): String {
    if (value === null || !value.isFinite()) return ""
    if (value > 0 && value < 0.0001) return String.format(java.util.Locale.US, "%.3e", value)
    return String.format(java.util.Locale.US, "%.4f", value)
}

fun buildResultsCsv(
    project: QpcrProject,
    qc: Map<String, WellQc>,
    ddct: DdctAnalysis,
    standard: StandardAnalysis,
): String {
    val lines = ArrayList<String>()
    fun section(title: String) {
        if (lines.isNotEmpty()) lines.add("")
        lines.add(row(listOf(title)))
    }

    lines.add(row(listOf("SPICE qPCR results")))
    lines.add(row(listOf("generated", Instant.now().toString())))
    lines.add(row(listOf("file", project.fileName)))
    lines.add(row(listOf("mode", project.mode)))
    lines.add(
        row(
            listOf(
                "referenceTargets", ddct.references.joinToString(";") { it.label },
                "controlGroup", project.settings.controlGroup,
                "controlSample", project.settings.controlSample,
            )
        )
    )
    lines.add(
        row(
            listOf(
                "highCt", project.settings.highCt,
                "sdLimit", project.settings.sdLimit,
                "outlierLimit", project.settings.outlierLimit,
                "excludeOutliers", if (project.settings.excludeOutliers) "yes" else "no",
                "efficiencyCorrection", if (project.settings.efficiencyCorrection) "yes" else "no",
            )
        )
    )

    section("Raw data with QC")
    lines.add(
        row(
            listOf(
                "row", "well", "sample", "target", "group", "replicate",
                "ct", "tm", "efficiency", "quantity", "include", "qcFlags",
            )
        )
    )
    for (well in project.wells) {
        lines.add(
            row(
                listOf(
                    well.row + 2, well.well, well.sample, well.target, well.group,
                    numI(well.replicate), num(well.ct), num(well.tm, 2),
                    num(well.efficiency), num(well.quantity, 6),
                    if (well.include) "yes" else "no",
                    (qc[well.id]?.flags ?: emptyList()).joinToString("|"),
                )
            )
        )
    }

    section("Replicate results")
    lines.add(
        row(
            listOf(
                "sample", "target", "group", "category", "replicate", "well",
                "ct", "refCt", "dCt", "ddCt", "foldChange", "isControl",
            )
        )
    )
    for (rep in ddct.replicates) {
        lines.add(
            row(
                listOf(
                    rep.sample, rep.target, rep.group, rep.category,
                    numI(rep.replicate), rep.well, num(rep.ct), num(rep.refCt),
                    num(rep.dCt), num(rep.ddCt), num(rep.fold, 6),
                    if (rep.isControl) "yes" else "no",
                )
            )
        )
    }

    section("Sample results")
    lines.add(
        row(
            listOf(
                "sample", "target", "group", "category", "n", "meanCt", "sdCt",
                "dCt", "ddCt", "efficiency", "foldChange", "foldSD", "foldSEM", "isControl",
            )
        )
    )
    for (sample in ddct.sampleResults) {
        lines.add(
            row(
                listOf(
                    sample.sample, sample.target, sample.group, sample.category, sample.n,
                    num(sample.meanCt), num(sample.sdCt), num(sample.dCt), num(sample.ddCt),
                    num(sample.efficiency), num(sample.fold, 6), num(sample.foldSd, 6),
                    num(sample.foldSem, 6), if (sample.isControl) "yes" else "no",
                )
            )
        )
    }

    section("Group results")
    lines.add(
        row(
            listOf(
                "group", "target", "nSamples", "meanFold", "sdFold", "semFold",
                "meanDCt", "sdDCt", "welchP", "isControl",
            )
        )
    )
    for (group in ddct.groupResults) {
        lines.add(
            row(
                listOf(
                    group.category, group.target, group.nSamples,
                    num(group.meanFold, 6), num(group.sdFold, 6), num(group.semFold, 6),
                    num(group.meanDCt), num(group.sdDCt), pValue(group.pValue),
                    if (group.isControl) "yes" else "no",
                )
            )
        )
    }

    section("Delta Ct matrix")
    lines.add(row(listOf("sample", "group", "refCt") + ddct.targets.map { "dCt:${it.label}" }))
    for (matrixRow in ddct.matrix) {
        lines.add(
            row(
                listOf(
                    matrixRow.sample, matrixRow.group, num(matrixRow.refCt),
                ) + ddct.targets.map { num(matrixRow.dCt[it.key]) }
            )
        )
    }

    section("Delta Delta Ct matrix")
    lines.add(row(listOf("sample", "group") + ddct.targets.map { "ddCt:${it.label}" }))
    for (matrixRow in ddct.matrix) {
        lines.add(
            row(
                listOf(matrixRow.sample, matrixRow.group) + ddct.targets.map { num(matrixRow.ddCt[it.key]) }
            )
        )
    }

    section("Control mean Delta Ct")
    lines.add(row(listOf("target", "controlMeanDCt")))
    for (target in ddct.targets) {
        lines.add(row(listOf(target.label, num(ddct.controlMeanDCt[target.key]))))
    }

    section("Reference QC")
    lines.add(row(listOf("sample", "group", "refCt", "missingReferenceTargets")))
    for (matrixRow in ddct.matrix) {
        lines.add(
            row(
                listOf(
                    matrixRow.sample, matrixRow.group, num(matrixRow.refCt),
                    matrixRow.referenceMissing.joinToString(";"),
                )
            )
        )
    }

    section("Standard curve fits")
    lines.add(row(listOf("target", "slope", "intercept", "r2", "efficiency", "n", "xMin", "xMax", "valid", "reason")))
    for (fit in standard.fits) {
        lines.add(
            row(
                listOf(
                    fit.target, num(fit.slope, 6), num(fit.intercept, 6), num(fit.r2, 6),
                    num(fit.efficiency, 6), fit.n, num(fit.xMin, 6), num(fit.xMax, 6),
                    if (fit.valid) "yes" else "no", fit.reason,
                )
            )
        )
    }

    section("Interpolated standard quantities")
    lines.add(
        row(
            listOf(
                "sample", "target", "group", "category", "n", "meanCt",
                "log10Quantity", "quantity", "sdLog10", "semLog10",
            )
        )
    )
    for (sample in standard.samples) {
        lines.add(
            row(
                listOf(
                    sample.sample, sample.target, sample.group, sample.category, sample.n,
                    num(sample.meanCt), num(sample.log10Quantity, 6), num(sample.quantity, 6),
                    num(sample.sdLog, 6), num(sample.semLog, 6),
                )
            )
        )
    }

    return lines.joinToString("\n") + "\n"
}
