package com.spice.nativeapp.tools.annotate

/*
 * 本地自动保存、项目 JSON 导入导出与紧凑历史签名，1:1 移植 main 的
 * src/lib/tools/annotate/storage.ts。平台替换：localStorage →
 * SharedPreferences（键与序列化格式保持原版）。
 */
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

private const val PREFS = "spice_tools"
const val SNAPSHOT_KEY = "spice_annotate_project_v1"

fun saveSnapshot(ctx: Context, project: AnnotateProject): Boolean {
    return try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SNAPSHOT_KEY, project.toJson().toString()).commit()
    } catch (e: Exception) {
        false
    }
}

fun loadSnapshot(ctx: Context): AnnotateProject? {
    return try {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SNAPSHOT_KEY, null)
            ?: return null
        normalizeProject(JSONObject(raw))
    } catch (e: Exception) {
        null
    }
}

fun clearSnapshot(ctx: Context) {
    try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SNAPSHOT_KEY).apply()
    } catch (e: Exception) {
        /* storage may be unavailable */
    }
}

fun projectJson(project: AnnotateProject): String = project.toJson().toString(2)

fun parseProjectText(text: String): AnnotateProject? {
    return try {
        normalizeProject(JSONObject(text))
    } catch (e: Exception) {
        null
    }
}

/**
 * Signature used to skip no-op history entries. Image payloads are excluded
 * because they never change in place; all editable panel fields are included.
 */
fun historyKey(project: AnnotateProject): String {
    val obj = JSONObject().apply {
        put("name", project.name)
        put("layout", project.layout.toJson())
        put("panels", JSONArray().apply {
            project.panels.forEach { panel ->
                put(JSONObject().apply {
                    put("id", panel.id)
                    put("name", panel.name)
                    put("width", panel.width)
                    put("height", panel.height)
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
                    panel.calibration?.let {
                        put("calibration", JSONObject().apply {
                            put("unit", it.unit); put("pxPerUnit", it.pxPerUnit)
                        })
                    }
                })
            }
        })
        put("annotations", JSONArray().apply { project.annotations.forEach { put(it.toJson()) } })
    }
    return obj.toString()
}

fun safeFileName(name: String): String {
    val cleaned = name
        .trim()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
        .replace(Regex("_+"), "_")
        .take(60)
    return cleaned.ifEmpty { "figure" }
}
