package com.spice.nativeapp.tools.blot

/*
 * Reproducibility bundle for the blot tool. Everything is built from the same
 * in-memory project that drives the figure and CSV exports: annotated PNG,
 * vector figure, densitometry CSV, project JSON, parameters and methods.
 * 1:1 port of main's src/lib/tools/blot/repro.ts.
 */

import android.content.Context
import com.spice.nativeapp.core.Repro
import com.spice.nativeapp.tools.gel.GelBatch
import org.json.JSONArray
import org.json.JSONObject

class BlotReproInput(
    val project: BlotProject,
    val processed: ProcessedImage,
    val quant: QuantResult,
    val title: String,
    val csvLabels: CsvLabels,
    val figure: FigureOptions,
)

private fun backgroundText(mode: String): String = when (mode) {
    "lane" -> "the median signal of the lane outside the band ROI"
    "global" -> "a border-ring median over the whole image"
    "manual" -> "a fixed manual background level"
    else -> "a local ring median around each band ROI"
}

private fun loadingLaneName(project: BlotProject): String {
    val lane = project.lanes.find { it.id == project.normalization.loadingLaneId }
    return (lane?.sample ?: lane?.name) ?: ""
}

private fun normalizationText(project: BlotProject): String {
    if (project.normalization.mode == "loading") {
        val name = loadingLaneName(project)
        return if (name.isNotEmpty()) {
            "Band signal was normalised to the loading-control lane \"$name\"."
        } else {
            "Band signal was normalised to the selected loading-control lane."
        }
    }
    if (project.normalization.mode == "total") {
        return "Band signal was normalised to the total signal of each lane."
    }
    return "No lane-level normalisation was applied; normalised signal equals net band volume."
}

private fun groupList(project: BlotProject): String {
    val groups = mutableListOf<String>()
    for (lane in project.lanes) {
        val group = lane.group.trim()
        if (group.isNotEmpty() && !groups.contains(group)) groups.add(group)
    }
    return if (groups.isNotEmpty()) groups.joinToString(", ") else "none"
}

private fun methodsText(project: BlotProject, quant: QuantResult): String {
    val lanes = project.lanes.count { !it.excluded }
    val bands = project.lanes.sumOf { lane -> lane.bands.count { !it.excluded } }
    val control = project.normalization.controlGroup.trim()
    val lines = listOf(
        "Western blot images were analysed with SPICE GUI. $lanes lanes and $bands band regions of interest were detected on the inverted grayscale plane and reviewed before quantification. Integrated band signal was corrected for local background estimated from ${backgroundText(project.backgroundMode)}.",
        "${normalizationText(project)} The loading-control lane setting was ${loadingLaneName(project).ifEmpty { "not assigned" }}.",
        "Lanes were grouped by the sample-table labels (${groupList(project)}). Replicate summaries report the mean, sample standard deviation, standard error and the 95% confidence interval from the Student t distribution.",
        if (control.isNotEmpty()) {
            "Two-sided Welch t-tests were computed for each group against the control group \"$control\" on normalised signal."
        } else {
            "No control group was selected, so Welch t-test p values are not reported."
        },
        "The annotated figure PNG was rendered at ${project.exportDpi} dpi. All parameters are recorded in parameters.json and the complete editable state is stored in project.json.",
    )
    return lines.joinToString("\n\n")
}

private fun parameters(project: BlotProject, quant: QuantResult): JSONObject {
    return JSONObject().apply {
        put("backgroundMode", project.backgroundMode)
        put("manualBackground", project.manualBackground)
        put("normalization", project.normalization.mode)
        put("loadingLane", loadingLaneName(project))
        put("controlGroup", project.normalization.controlGroup)
        put("chartMetric", project.chartMetric)
        put("exportDpi", project.exportDpi)
        put("crop", JSONObject().apply {
            put("left", project.crop.left)
            put("top", project.crop.top)
            put("right", project.crop.right)
            put("bottom", project.crop.bottom)
        })
        put("rotationDegrees", project.rotation)
        put("orientationQuarterTurns", project.orient)
        put("display", JSONObject().apply {
            put("display", project.adjust.display)
            put("contrast", project.adjust.contrast)
            put("brightness", project.adjust.brightness)
            put("gamma", project.adjust.gamma)
            put("invert", project.adjust.invert)
        })
        put("laneCount", project.lanes.size)
        put("includedLanes", project.lanes.count { !it.excluded })
        put("bandCount", quant.rows.size)
        put("includedBands", quant.rows.count { it.included })
        put("controlMean", quant.controlMean)
        put("totalVolume", quant.totalVolume)
        put("groups", JSONArray().apply {
            for (group in quant.groups) {
                put(JSONObject().apply {
                    put("name", group.name)
                    put("n", group.n)
                    put("mean", group.mean)
                    put("sd", group.sd)
                    put("sem", group.sem)
                    put("ci95", if (group.n >= 2) group.ci95 else JSONObject.NULL)
                    if (group.pValue != null) put("welchP", group.pValue) else put("welchP", JSONObject.NULL)
                })
            }
        })
    }
}

fun saveBlotReproBundle(ctx: Context, input: BlotReproInput): Repro.ReproBundleResult {
    val project = input.project
    val processed = input.processed
    val quant = input.quant
    val title = input.title
    val base = GelBatch.fileBaseName(project.imageName.ifEmpty { title }, "blot")
    val canvas = annotatedCanvas(
        project, processed.canvas,
        AnnotationOptions(laneLabels = project.showLaneLabels, roi = project.showRoi)
    )
    val description = "$title; ${project.lanes.size} lanes; ${project.exportDpi} dpi; background ${project.backgroundMode}; normalisation ${project.normalization.mode}"
    val png = annotatedPng(canvas, project.exportDpi, description)
    val svg = figureSvg(project, processed.canvas, quant, input.figure)
    val csv = resultsCsv(project, quant, input.csvLabels)

    return Repro.buildReproBundle(
        ctx,
        Repro.ReproBundleInput(
            tool = "blot",
            title = title,
            files = listOf(
                Repro.ReproFile("${base}_annotated_${project.exportDpi}dpi.png", "image/png", png),
                Repro.ReproFile("${base}_figure.svg", "image/svg+xml", svg),
                Repro.ReproFile("${base}_densitometry.csv", "text/csv", csv),
            ),
            parameters = parameters(project, quant),
            methods = methodsText(project, quant),
            project = JSONObject(projectToJson(project)),
        )
    )
}
