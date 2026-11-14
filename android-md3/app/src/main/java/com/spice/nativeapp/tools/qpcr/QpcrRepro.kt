package com.spice.nativeapp.tools.qpcr

/*
 * Reproducibility bundle for the qPCR workspace. 1:1 port of main's
 * src/lib/tools/qpcr/repro.ts: chart PNG + SVG, the full results CSV,
 * parameters and a methods paragraph recording the reference/control/QC
 * choices actually used. The PNG is the Canvas bitmap twin of the SVG
 * (scale 2, same as main's svgToPngBytes).
 */

import android.content.Context
import android.graphics.Bitmap
import com.spice.nativeapp.core.Repro
import com.spice.nativeapp.tools.gel.GelBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class QpcrReproInput(
    val project: QpcrProject,
    val chart: SvgDoc,
    val chartData: ChartData,
    val chartLabels: ChartLabels,
    val qc: Map<String, WellQc>,
    val ddct: DdctAnalysis,
    val standard: StandardAnalysis,
    val title: String,
    val flagged: Int,
)

private fun optD(o: JSONObject, key: String, value: Double?) {
    if (value !== null && value.isFinite()) o.put(key, value) else o.put(key, JSONObject.NULL)
}

private fun methodsText(input: QpcrReproInput): String {
    val project = input.project
    val ddct = input.ddct
    val standard = input.standard
    val settings = project.settings
    val references = ddct.references.joinToString(", ") { it.label }.ifEmpty { "none" }
    val control = ddct.controlLabel.ifEmpty {
        settings.controlSample.ifEmpty { settings.controlGroup.ifEmpty { "the first sample" } }
    }
    val lines = ArrayList<String>()
    if (project.mode == "ddct") {
        lines.add(
            "qPCR data were analysed with SPICE GUI using the Delta Delta Ct method. Reference Ct for each sample was calculated as the geometric mean of the Ct values of the selected reference targets ($references). Target Ct was the mean of the included technical replicates, and Delta Ct was target Ct minus reference Ct."
        )
        lines.add(
            "Delta Delta Ct was calculated relative to the control group or sample \"$control\". Fold change was calculated as 2^-DeltaDeltaCt, or as (1+E)^-DeltaDeltaCt when efficiency correction was enabled (default efficiency ${settings.defaultEfficiencyPct}%)."
        )
        lines.add(
            "Group summaries report the mean fold change, sample SD and SEM. Two-sided Welch t-tests were computed on Delta Ct values of each group against the control group."
        )
    } else {
        lines.add(
            "qPCR data were analysed with SPICE GUI using standard-curve quantification. For each target, Ct was regressed on the log10 standard quantity by ordinary least squares; amplification efficiency was calculated as 10^(-1/slope) - 1."
        )
        lines.add(
            "Unknown sample quantities were interpolated from the fitted curve per target and summarised as the mean log10 quantity with SD and SEM."
        )
    }
    lines.add(
        "Quality control flagged ${input.flagged} wells. Ct values above ${settings.highCt} were marked as high Ct, replicate SD above ${settings.sdLimit} was flagged, and wells deviating more than ${settings.outlierLimit} Ct from the replicate mean were treated as outliers${if (settings.excludeOutliers) " and excluded automatically" else " but retained"}. Wells without Ct and selected reference targets without Ct were flagged."
    )
    lines.add(
        "All analysis parameters are recorded in parameters.json and the complete editable state is stored in project.json."
    )
    if (project.mode == "ddct" && standard.fits.isNotEmpty()) {
        lines.add(
            "Standard curves were also fitted for ${standard.fits.size} target(s) present in the data."
        )
    }
    return lines.joinToString("\n\n")
}

private fun parameters(input: QpcrReproInput): JSONObject {
    val project = input.project
    val ddct = input.ddct
    val standard = input.standard
    val settings = project.settings
    return JSONObject().apply {
        put("mode", project.mode)
        put("settings", JSONObject().apply {
            put("highCt", settings.highCt)
            put("sdLimit", settings.sdLimit)
            put("outlierLimit", settings.outlierLimit)
            put("excludeOutliers", settings.excludeOutliers)
            put("efficiencyCorrection", settings.efficiencyCorrection)
            put("defaultEfficiencyPct", settings.defaultEfficiencyPct)
            put("quantityIsLog10", settings.quantityIsLog10)
            put("showHeatmap", settings.showHeatmap)
            put("referenceTarget", settings.referenceTarget)
            put("referenceTargets", JSONArray(settings.referenceTargets))
            put("controlGroup", settings.controlGroup)
            put("controlSample", settings.controlSample)
        })
        put("referenceTargets", JSONArray(ddct.references.map { it.label }))
        put("controlLabel", ddct.controlLabel)
        put("controlGroup", settings.controlGroup)
        put("controlSample", settings.controlSample)
        put("wells", project.wells.size)
        put("flaggedWells", input.flagged)
        put("sampleResults", ddct.sampleResults.size)
        put("groupResults", JSONArray(ddct.groupResults.map { group ->
            JSONObject().apply {
                put("group", group.category)
                put("target", group.target)
                put("nSamples", group.nSamples)
                optD(this, "meanFold", group.meanFold)
                optD(this, "sdFold", group.sdFold)
                optD(this, "semFold", group.semFold)
                optD(this, "meanDCt", group.meanDCt)
                optD(this, "sdDCt", group.sdDCt)
                optD(this, "welchP", group.pValue)
                put("isControl", group.isControl)
            }
        }))
        put("standardFits", JSONArray(standard.fits.map { fit ->
            JSONObject().apply {
                put("target", fit.target)
                optD(this, "slope", fit.slope)
                optD(this, "intercept", fit.intercept)
                optD(this, "r2", fit.r2)
                optD(this, "efficiency", fit.efficiency)
                put("n", fit.n)
                put("valid", fit.valid)
                put("reason", fit.reason)
            }
        }))
    }
}

private fun bitmapPngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

fun buildQpcrReproBundle(ctx: Context, input: QpcrReproInput): Repro.ReproBundleResult {
    val project = input.project
    val base = GelBatch.fileBaseName(project.fileName.ifEmpty { input.title }, "qpcr")
    val png = bitmapPngBytes(barChartBitmap(input.chartData, input.chartLabels, scale = 2f))
    val csv = buildResultsCsv(project, input.qc, input.ddct, input.standard)
    return Repro.buildReproBundle(
        ctx,
        Repro.ReproBundleInput(
            tool = "qpcr",
            title = input.title,
            files = listOf(
                Repro.ReproFile("${base}_qpcr_chart.png", "image/png", png),
                Repro.ReproFile("${base}_qpcr_chart.svg", "image/svg+xml", input.chart.svg),
                Repro.ReproFile("${base}_qpcr_results.csv", "text/csv", csv),
            ),
            parameters = parameters(input),
            methods = methodsText(input),
            project = JSONObject(projectToJson(project)),
        ),
    )
}
