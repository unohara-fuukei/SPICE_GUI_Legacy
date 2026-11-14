package com.spice.nativeapp.tools.annotate

/*
 * SPICE_PROJECT 适配器（图像标注工具），1:1 移植 main 的
 * src/lib/tools/annotate/projectDoc.ts。原版项目 handoff（ToolProjectBar 400ms
 * commit + hub/ELN 摘要）按 T4 惯例不移植（安卓无项目中心），但 summary/
 * parameters/methods 三个纯函数完整保留：可复现包仍按原版内容生成。
 */
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.collections.ArrayList

const val ANNOTATE_DOC_KIND = "annotate"

fun normalizeAnnotatePayload(value: Any?): AnnotateProject? = normalizeProject(value)

fun annotateDocName(project: AnnotateProject): String {
    val name = project.name.trim()
    return name.ifEmpty { "Figure" }
}

fun annotateProfileAnnotations(project: AnnotateProject): List<ProfileAnnotation> =
    project.annotations.filterIsInstance<ProfileAnnotation>()

/** One-line hub/ELN summary. */
fun annotateSummary(project: AnnotateProject): String {
    val geom = buildFigureGeometry(project)
    val profiles = annotateProfileAnnotations(project).size
    val channels = project.panels
        .map { panel ->
            val visible = buildString {
                if (panel.channels.r.show) append("R")
                if (panel.channels.g.show) append("G")
                if (panel.channels.b.show) append("B")
            }
            visible.ifEmpty { "none" }
        }
        .distinct()
        .joinToString("/")
    val base = mutableListOf(
        "${project.panels.size} panel${if (project.panels.size == 1) "" else "s"}",
        "${String.format(Locale.US, "%.1f", project.layout.figureWidthMm)} x ${String.format(Locale.US, "%.1f", geom.height / PX_PER_MM)} mm",
        "${project.layout.exportDpi} dpi",
        "${project.annotations.size} annotation${if (project.annotations.size == 1) "" else "s"}",
    )
    if (profiles > 0) base.add("$profiles profile${if (profiles == 1) "" else "s"}")
    if (channels.isNotEmpty()) base.add("RGB $channels")
    return base.joinToString("; ")
}

/** Processing settings recorded in parameters.json. */
fun annotateParameters(project: AnnotateProject): JSONObject {
    val geom = buildFigureGeometry(project)
    return JSONObject().apply {
        put("figure", JSONObject().apply {
            put("name", annotateDocName(project))
            put("widthMm", project.layout.figureWidthMm)
            put("heightMm", Math.round(geom.height / PX_PER_MM * 1000.0) / 1000.0)
            put("exportDpi", project.layout.exportDpi)
            put("columns", project.layout.columns)
            put("flow", project.layout.flow)
            put("gapMm", project.layout.gapMm)
            put("marginMm", project.layout.marginMm)
            put("background", project.layout.background)
            put("fontFamily", project.layout.fontFamily)
            put("labelStyle", project.layout.labelStyle)
            put("labelPosition", project.layout.labelPosition)
            put("labelFontSizePt", project.layout.labelFontSizePt)
        })
        put("panels", JSONArray().apply {
            project.panels.forEach { panel ->
                put(JSONObject().apply {
                    put("name", panel.name)
                    put("width", panel.width)
                    put("height", panel.height)
                    put("mime", panel.mime)
                    put("crop", JSONObject().apply {
                        put("left", panel.crop.left); put("top", panel.crop.top)
                        put("right", panel.crop.right); put("bottom", panel.crop.bottom)
                    })
                    put("zoom", panel.zoom)
                    put("offsetX", panel.offsetX)
                    put("offsetY", panel.offsetY)
                    put("brightness", panel.brightness)
                    put("contrast", panel.contrast)
                    put("channels", panel.channels.toJson())
                    when (val cal = panel.calibration) {
                        null -> put("calibration", JSONObject.NULL)
                        else -> put("calibration", JSONObject().apply {
                            put("unit", cal.unit); put("pxPerUnit", cal.pxPerUnit)
                        })
                    }
                })
            }
        })
        put("annotations", JSONArray().apply {
            project.annotations.forEach { ann ->
                put(JSONObject().apply {
                    put("id", ann.id)
                    put("kind", when (ann) {
                        is ArrowAnnotation -> "arrow"
                        is RectAnnotation -> "rect"
                        is EllipseAnnotation -> "ellipse"
                        is TextAnnotation -> "text"
                        is ScaleBarAnnotation -> "scalebar"
                        is PanelBarAnnotation -> "panelbar"
                        is ProfileAnnotation -> "profile"
                    })
                    put("color", ann.color)
                    if (ann is ProfileAnnotation) {
                        put("samples", ann.samples)
                        put("x1", ann.x1); put("y1", ann.y1)
                        put("x2", ann.x2); put("y2", ann.y2)
                    }
                })
            }
        })
        put("profileCount", annotateProfileAnnotations(project).size)
        put(
            "profileIntensity",
            "Rec. 709 luminance (0.2126 R + 0.7152 G + 0.0722 B) of the rendered RGB composite, 0-255",
        )
    }
}

/** Draft methods paragraph included in the reproducible bundle. */
fun annotateMethods(project: AnnotateProject): String {
    val channels = project.panels.any { panel ->
        !panel.channels.r.show || !panel.channels.g.show || !panel.channels.b.show ||
            panel.channels.r.gain != 1.0 || panel.channels.g.gain != 1.0 || panel.channels.b.gain != 1.0 ||
            panel.channels.r.invert || panel.channels.g.invert || panel.channels.b.invert
    }
    val profiles = annotateProfileAnnotations(project).size
    val lines = mutableListOf(
        "Figure panels (${project.panels.size}) were assembled in the SPICE image annotator and exported at ${project.layout.exportDpi} dpi as a raster figure (PNG) and as a vector figure (SVG with embedded raster panels and editable text labels).",
        "Panel cropping, zoom and pan were applied non-destructively; brightness and contrast were applied before annotation placement.",
    )
    if (channels) {
        lines.add(
            "RGB channels were composited per panel with explicit show/hide flags, gain in the 0-2 range and optional per-channel inversion; every preview, annotation and export used the same composite.",
        )
    } else {
        lines.add("All three RGB channels were displayed at unit gain without inversion.")
    }
    if (profiles > 0) {
        lines.add(
            "Intensity line profiles ($profiles) were sampled at evenly spaced positions along each measurement line from the rendered composite. Intensity was defined as Rec. 709 luminance (0.2126 R + 0.7152 G + 0.0722 B) on the 0-255 scale and exported as profile CSV alongside the figure.",
        )
    }
    lines.add(
        "Scale bars use the per-panel calibration (source pixels per physical unit) where available; fixed-length bars are expressed in millimetres at the figure scale.",
    )
    return lines.joinToString(" ")
}
