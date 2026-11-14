package com.spice.nativeapp.tools.colony

/*
 * 菌落计数器本地持久化，1:1 移植 main 的 src/lib/tools/colony/storage.ts。
 * 浏览器快照以相同像素尺寸压缩图像，使标记坐标仍然有效；可下载的
 * 项目 JSON 保留原始编码图像。
 *
 * 平台差异：localStorage → SharedPreferences("spice_tools")
 * （键名 spice_colony_project_v1 与原版一致）；canvas.toDataURL(jpeg)
 * → Bitmap.compress(JPEG)。IMAGE_BUDGET 字符预算逐字保留。
 */
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min

const val COLONY_SNAPSHOT_KEY = "spice_colony_project_v1"
/** 重编码为 JPEG 前的大致字符预算。 */
const val COLONY_IMAGE_BUDGET = 2_600_000

private const val PREFS = "spice_tools"

fun loadSnapshot(ctx: Context): ColonyProject? {
    return try {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(COLONY_SNAPSHOT_KEY, null) ?: return null
        val parsed = org.json.JSONObject(raw)
        if (isColonyProject(parsed)) projectFromJson(parsed) else null
    } catch (e: Exception) {
        null
    }
}

fun clearSnapshot(ctx: Context) {
    try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(COLONY_SNAPSHOT_KEY).apply()
    } catch (e: Exception) {
        /* 忽略 */
    }
}

private fun decodeDataUrl(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma < 0) return null
    val bytes = try {
        Base64.decode(dataUrl.substring(comma + 1), Base64.NO_WRAP)
    } catch (e: Exception) {
        return null
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun bitmapJpegDataUrl(bitmap: Bitmap, quality: Int): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** 以相同尺寸把大图像重编码（JPEG）以适配本地存储。 */
fun compactProjectImage(project: ColonyProject): ColonyProject {
    if (project.imageDataUrl.isEmpty() || project.imageDataUrl.length <= COLONY_IMAGE_BUDGET) return project
    try {
        val img = decodeDataUrl(project.imageDataUrl) ?: return project
        var dataUrl = bitmapJpegDataUrl(img, 86)
        if (dataUrl.length > COLONY_IMAGE_BUDGET) dataUrl = bitmapJpegDataUrl(img, 68)
        if (dataUrl.startsWith("data:image/jpeg")) {
            return project.copy(imageDataUrl = dataUrl, imageName = project.imageName)
        }
    } catch (e: Exception) {
        /* 保留原图 */
    }
    return project
}

fun saveSnapshot(ctx: Context, project: ColonyProject): Boolean {
    return try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(COLONY_SNAPSHOT_KEY, project.toJson().toString())
            .apply()
        true
    } catch (e: Exception) {
        false
    }
}
