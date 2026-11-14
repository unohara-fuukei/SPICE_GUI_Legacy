package com.spice.nativeapp.tools.colony

/*
 * 图像解码与工作图像流水线，1:1 移植 main 的 src/lib/tools/colony/imaging.ts。
 * 源图像解码一次，然后经旋转、裁剪与（可选）降采样生成工作位图。
 * 检测器输入是进一步降采样的灰度副本，结果映射回工作坐标。
 *
 * 平台替换：HTMLImageElement/HTMLCanvasElement → android.graphics.Bitmap；
 * utif2 → 内置 GelTiff 解码器（8/16bit 灰度、LZW、Deflate）；
 * canvas transform → android.graphics.Matrix（同样的 scale/translate/rotate
 * 复合顺序）；willReadFrequently getImageData → Bitmap.getPixels。
 */
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Base64
import com.spice.nativeapp.tools.gel.GelTiff
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 工作画布上限，使 50 MP 手机照片保持响应。 */
const val MAX_WORK_DIM = 4096
/** 检测器分辨率上限。目标映射回工作像素。 */
const val MAX_DETECT_DIM = 1400

data class DecodedImage(
    val dataUrl: String,
    val width: Int,
    val height: Int,
    val mime: String,
    val name: String,
)

class WorkMeta(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val rotate: Int,
    val crop: ColonyCrop,
    /** 旋转后未裁剪帧的尺寸。 */
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    /** 旋转帧内的裁剪矩形（源像素）。 */
    val sx: Int,
    val sy: Int,
    val sw: Int,
    val sh: Int,
    /** 工作像素 / 旋转帧像素（通常 <= 1）。 */
    val scaleX: Double,
    val scaleY: Double,
)

private val imageCache = ConcurrentHashMap<String, Bitmap>()

fun clearColonyImageCache() {
    imageCache.clear()
}

/** 对应 loadImageElement：按 dataUrl 缓存解码位图。 */
fun loadImageBitmap(src: String): Bitmap {
    imageCache[src]?.let { return it }
    val bmp = decodeDataUrlBitmap(src) ?: throw IllegalArgumentException("Image decode failed")
    imageCache[src] = bmp
    return bmp
}

private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma < 0) return null
    val bytes = try {
        Base64.decode(dataUrl.substring(comma + 1), Base64.NO_WRAP)
    } catch (e: Exception) {
        return null
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun bitmapToDataUrl(bitmap: Bitmap, mime: String): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(if (mime == "image/jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 100, out)
    return "data:$mime;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private fun looksLikeTiff(name: String, mime: String?, bytes: ByteArray): Boolean {
    if (Regex("\\.tiff?$", RegexOption.IGNORE_CASE).containsMatchIn(name) || mime == "image/tiff") return true
    if (bytes.size < 4) return false
    val little = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == '*'.code.toByte() && bytes[3] == 0.toByte()
    val big = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0.toByte() && bytes[3] == '*'.code.toByte()
    return little || big
}

/**
 * PNG/JPEG/WebP 走 BitmapFactory；TIFF 走内置 GelTiff，使平板成像仪的
 * 原生 8/16bit 灰度、LZW 与 Deflate 文件也能工作（平台差异：utif2 → GelTiff）。
 */
fun decodeImageFile(bytes: ByteArray, name: String, mime: String?): DecodedImage {
    if (!looksLikeTiff(name, mime, bytes)) {
        val effectiveMime = mime?.takeIf { it.startsWith("image/") } ?: "image/png"
        val dataUrl = "data:$effectiveMime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val img = loadImageBitmap(dataUrl)
        return DecodedImage(dataUrl, img.width, img.height, effectiveMime, name)
    }
    val dec = GelTiff.decode(bytes)
    val width = dec.width
    val height = dec.height
    if (width <= 0 || height <= 0) throw IllegalArgumentException("TIFF has invalid dimensions")
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(dec.argb, 0, width, 0, 0, width, height)
    val dataUrl = bitmapToDataUrl(bmp, "image/png")
    return DecodedImage(dataUrl, width, height, "image/tiff", name)
}

private fun clampFraction(value: Double): Double {
    if (!value.isFinite()) return 0.0
    return min(0.4, max(0.0, value))
}

data class CropRectResult(val sx: Int, val sy: Int, val sw: Int, val sh: Int)

fun cropRect(crop: ColonyCrop, rotatedWidth: Int, rotatedHeight: Int): CropRectResult {
    val left = clampFraction(crop.left)
    val right = clampFraction(crop.right)
    val top = clampFraction(crop.top)
    val bottom = clampFraction(crop.bottom)
    val sx = (left * rotatedWidth).roundToInt()
    val sy = (top * rotatedHeight).roundToInt()
    val sw = max(8, rotatedWidth - sx - (right * rotatedWidth).roundToInt())
    val sh = max(8, rotatedHeight - sy - (bottom * rotatedHeight).roundToInt())
    return CropRectResult(sx, sy, sw, sh)
}

/** 旋转（顺时针四分之一转）、裁剪并封顶工作位图。 */
fun buildWorkBitmap(img: Bitmap, rotate: Int, crop: ColonyCrop): WorkMeta {
    val width = img.width
    val height = img.height
    val r = (((kotlin.math.round(rotate / 90.0) * 90).toInt() % 360) + 360) % 360
    val swap = r == 90 || r == 270
    val rotatedWidth = if (swap) height else width
    val rotatedHeight = if (swap) width else height
    val rect = cropRect(crop, rotatedWidth, rotatedHeight)
    val scale = min(1.0, MAX_WORK_DIM.toDouble() / max(rect.sw, rect.sh))
    val outWidth = max(1, (rect.sw * scale).roundToInt())
    val outHeight = max(1, (rect.sh * scale).roundToInt())
    val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val scaleX = outWidth.toDouble() / rect.sw
    val scaleY = outHeight.toDouble() / rect.sh
    // 与原版 ctx 复合一致：source 坐标先移到中心 → 旋转 → 移回裁剪区 → 缩放。
    val matrix = Matrix()
    matrix.setRotate(r.toFloat())
    matrix.preTranslate(-width / 2f, -height / 2f)
    matrix.postTranslate(rotatedWidth / 2f, rotatedHeight / 2f)
    matrix.postTranslate(-rect.sx.toFloat(), -rect.sy.toFloat())
    matrix.postScale(scaleX.toFloat(), scaleY.toFloat())
    val paint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(img, matrix, paint)
    return WorkMeta(
        bitmap = out, width = outWidth, height = outHeight,
        naturalWidth = width, naturalHeight = height, rotate = r,
        crop = crop, rotatedWidth = rotatedWidth, rotatedHeight = rotatedHeight,
        sx = rect.sx, sy = rect.sy, sw = rect.sw, sh = rect.sh,
        scaleX = scaleX, scaleY = scaleY,
    )
}

/** 工作坐标 → 旋转帧源坐标。 */
fun workToRotated(meta: WorkMeta, x: Double, y: Double): Pair<Double, Double> =
    Pair((x + 0.5) / meta.scaleX - 0.5 + meta.sx, (y + 0.5) / meta.scaleY - 0.5 + meta.sy)

/** 旋转帧源坐标 → 工作坐标。 */
fun rotatedToWork(meta: WorkMeta, x: Double, y: Double): Pair<Double, Double> =
    Pair((x - meta.sx + 0.5) * meta.scaleX - 0.5, (y - meta.sy + 0.5) * meta.scaleY - 0.5)

/**
 * 在旋转/裁剪/重建之间携带标记。落在新帧外的标记被丢弃；
 * 面积跟随工作分辨率变化。
 */
fun transformMarkersBetween(oldMeta: WorkMeta, newMeta: WorkMeta, markers: List<ColonyMarker>): List<ColonyMarker> {
    val delta = ((newMeta.rotate - oldMeta.rotate) % 360 + 360) % 360
    val oldRW = oldMeta.rotatedWidth.toDouble()
    val oldRH = oldMeta.rotatedHeight.toDouble()
    val areaFactor = (newMeta.scaleX * newMeta.scaleY) / max(1e-9, oldMeta.scaleX * oldMeta.scaleY)
    val radiusFactor = kotlin.math.sqrt(areaFactor)
    val out = mutableListOf<ColonyMarker>()
    for (marker in markers) {
        val old = workToRotated(oldMeta, marker.x, marker.y)
        val u = old.first / oldRW
        val v = old.second / oldRH
        var nu = u
        var nv = v
        if (delta == 90) {
            nu = 1 - v; nv = u
        } else if (delta == 180) {
            nu = 1 - u; nv = 1 - v
        } else if (delta == 270) {
            nu = v; nv = 1 - u
        }
        val next = rotatedToWork(newMeta, nu * newMeta.rotatedWidth, nv * newMeta.rotatedHeight)
        if (next.first < -0.5 || next.second < -0.5 ||
            next.first > newMeta.width - 0.5 || next.second > newMeta.height - 0.5
        ) {
            continue
        }
        out.add(
            marker.copy(
                x = next.first, y = next.second,
                radius = marker.radius * radiusFactor,
                area = marker.area * areaFactor,
            )
        )
    }
    return out
}

/** 在工作分辨率上重渲亮度/对比度/灰度/反相。 */
fun renderAdjusted(source: Bitmap, adjust: ColonyAdjust): Bitmap {
    val noColorChange = !adjust.grayscale && !adjust.invert &&
        abs(adjust.contrast - 1) < 1e-4 && abs(adjust.brightness) < 1e-4
    if (noColorChange) return source
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val contrast = max(0.1, adjust.contrast)
    val offset = adjust.brightness / 255
    val lut = IntArray(256)
    for (i in 0 until 256) {
        var v = (i / 255.0 - 0.5) * contrast + 0.5 + offset
        v = min(1.0, max(0.0, v))
        if (adjust.invert) v = 1 - v
        lut[i] = (v * 255).roundToInt()
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        if (adjust.grayscale) {
            val lum = r * 0.299 + g * 0.587 + b * 0.114
            val v = lut[if (lum < 0) 0 else if (lum > 255) 255 else lum.roundToInt()]
            pixels[i] = (p and 0xff000000.toInt()) or (v shl 16) or (v shl 8) or v
        } else {
            val vr = lut[r]
            val vg = lut[g]
            val vb = lut[b]
            pixels[i] = (p and 0xff000000.toInt()) or (vr shl 16) or (vg shl 8) or vb
        }
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

class ScaledBitmap(val bitmap: Bitmap, val scaleX: Double, val scaleY: Double)

fun downscaleCanvas(source: Bitmap, maxDim: Int): ScaledBitmap {
    val scale = min(1.0, maxDim.toDouble() / max(source.width, source.height))
    if (scale >= 1.0) return ScaledBitmap(source, 1.0, 1.0)
    val width = max(1, (source.width * scale).roundToInt())
    val height = max(1, (source.height * scale).roundToInt())
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint().apply { isFilterBitmap = true }
    val matrix = Matrix().apply { setScale(width.toFloat() / source.width, height.toFloat() / source.height) }
    canvas.drawBitmap(source, matrix, paint)
    return ScaledBitmap(out, width.toDouble() / source.width, height.toDouble() / source.height)
}

/** 分割器使用的灰度平面（0..255）。 */
fun extractGray(bitmap: Bitmap): GrayPlane {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val gray = DoubleArray(width * height)
    for (i in pixels.indices) {
        val p = pixels[i]
        gray[i] = ((p shr 16) and 0xff) * 0.299 + ((p shr 8) and 0xff) * 0.587 + (p and 0xff) * 0.114
    }
    return GrayPlane(gray, width, height)
}

class GrayPlane(val gray: DoubleArray, val width: Int, val height: Int)

/** 位图 → 原版 PixelSource（RGBA 展开的 0..255 值数组）。 */
fun bitmapPixelSource(bitmap: Bitmap): PixelSource {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val data = IntArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        data[i * 4] = (p shr 16) and 0xff
        data[i * 4 + 1] = (p shr 8) and 0xff
        data[i * 4 + 2] = p and 0xff
        data[i * 4 + 3] = (p ushr 24) and 0xff
    }
    return PixelSource(data, width, height)
}

/** 二值掩膜预览位图（前景青色）。 */
fun maskPreviewBitmap(mask: ByteArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height)
    for (i in mask.indices) {
        if (mask[i] == 0.toByte()) {
            pixels[i] = 0
            continue
        }
        // rgba(76, 214, 255, 110/255) 预乘到 ARGB_8888
        pixels[i] = (110 shl 24) or (76 * 110 / 255 shl 16) or (214 * 110 / 255 shl 8) or (255 * 110 / 255)
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}

/** 导出分辨率的二值掩膜（前景白，背景黑）。 */
fun maskExportBitmap(mask: ByteArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height)
    for (i in mask.indices) {
        val v = if (mask[i] != 0.toByte()) 255 else 0
        pixels[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
}
