package com.spice.nativeapp.tools.annotate

/*
 * 图像导入与处理，1:1 移植 main 的 src/lib/tools/annotate/decode.ts。
 * 平台替换：HTMLImageElement → android.graphics.Bitmap（缓存保留）；
 * utif2 → 内置 GelTiff 解码器（README 披露）；canvas filter → 单遍像素合成
 * （原版 applyComposite 与 applyLevels 的数学在合成路径上等价，canvas 存的
 * 本来就是 Uint8ClampedArray）。
 *
 * 原版语义要点：非 TIFF 文件原样内嵌原始字节（dataUrl 保留原 mime）；
 * TIFF 解码后重编码 PNG；合成 = 亮度/对比 + RGB 通道混合单遍完成，
 * 预览与导出共用同一条路径，剖面采样因此与显示像素一致。
 */
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import com.spice.nativeapp.tools.gel.GelTiff
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PanelArt(
    val source: Bitmap,
    val width: Int,
    val height: Int,
    /** Present only for export artwork that is embedded in the SVG. */
    val dataUrl: String? = null,
)

// ------------------------------------------------------------------ image cache

private val imageCache = ConcurrentHashMap<String, Bitmap>()

fun clearImageCache() {
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

// ------------------------------------------------------------------ file import

private fun fileExtension(name: String): String =
    Regex("\\.([a-z0-9]+)$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.lowercase() ?: ""

private fun isTiff(name: String, mime: String?): Boolean =
    mime == "image/tiff" || fileExtension(name) == "tif" || fileExtension(name) == "tiff"

private fun supportedMime(name: String, mime: String?): String? {
    if (mime == "image/png" || mime == "image/jpeg" || mime == "image/webp") return mime
    return when (fileExtension(name)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }
}

private fun readDataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun decodeTiffToPanel(name: String, bytes: ByteArray): Panel {
    val dec = GelTiff.decode(bytes)
    val width = dec.width
    val height = dec.height
    if (width <= 0 || height <= 0) throw IllegalArgumentException("TIFF has invalid dimensions")
    val bmp = Bitmap.createBitmap(dec.argb, width, height, Bitmap.Config.ARGB_8888)
    val dataUrl = bitmapToPngDataUrl(bmp)
    return emptyPanel(name, dataUrl, width, height, "image/png")
}

fun bitmapToPngDataUrl(bitmap: Bitmap): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/** Decode one user file into a panel. Throws for unsupported or corrupt input. */
fun fileToPanel(name: String, bytes: ByteArray, mime: String?): Panel {
    if (isTiff(name, mime)) return decodeTiffToPanel(name, bytes)
    val resolved = supportedMime(name, mime) ?: throw IllegalArgumentException("Unsupported image format")
    val dataUrl = readDataUrl(bytes, resolved)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) throw IllegalArgumentException("Image has no pixels")
    return emptyPanel(name, dataUrl, width, height, resolved)
}

// ------------------------------------------------------------------ compositing

private fun byteClip(value: Double): Int =
    when {
        value <= 0.0 -> 0
        value >= 255.0 -> 255
        else -> Math.round(value).toInt() // JS Math.round: .5 向上
    }

private fun compositeChannel(value: Double, channel: ChannelSetting): Int {
    if (!channel.show) return 0
    val scaled = value * channel.gain
    return byteClip(if (channel.invert) 255 - scaled else scaled)
}

/**
 * Apply brightness/contrast and the RGB channel mix in one pass. This is the
 * single raster composite used by both the on-screen preview and every export,
 * so annotations and sampled profiles always see the same pixels.
 */
private fun applyComposite(
    bitmap: Bitmap,
    brightness: Double,
    contrast: Double,
    channels: ChannelMix,
) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val bf = clamp(brightness, 10.0, 300.0) / 100.0
    val cf = clamp(contrast, 10.0, 300.0) / 100.0
    val r = channels.r
    val g = channels.g
    val b = channels.b
    for (i in pixels.indices) {
        val px = pixels[i]
        val pr = (px shr 16 and 0xff).toDouble()
        val pg = (px shr 8 and 0xff).toDouble()
        val pb = (px and 0xff).toDouble()
        val lr = (pr * bf - 128) * cf + 128
        val lg = (pg * bf - 128) * cf + 128
        val lb = (pb * bf - 128) * cf + 128
        pixels[i] = (px and 0xff000000.toInt()) or
            (compositeChannel(lr, r) shl 16) or
            (compositeChannel(lg, g) shl 8) or
            compositeChannel(lb, b)
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}

/**
 * Build a drawable version of the panel. Preview art is downscaled for speed;
 * export art stays at native resolution and carries a PNG data URL for SVG.
 */
fun buildArtwork(
    panel: Panel,
    maxDimension: Int = 0,
    withDataUrl: Boolean = false,
): PanelArt {
    val image = loadImageBitmap(panel.dataUrl)
    val naturalWidth = if (panel.width > 0) panel.width else image.width
    val naturalHeight = if (panel.height > 0) panel.height else image.height
    val longest = max(naturalWidth, naturalHeight)
    val scale = if (maxDimension > 0 && longest > maxDimension) maxDimension.toDouble() / longest else 1.0
    val width = max(1, (naturalWidth * scale).roundToInt())
    val height = max(1, (naturalHeight * scale).roundToInt())
    val brightness = clamp(panel.brightness, 10.0, 300.0)
    val contrast = clamp(panel.contrast, 10.0, 300.0)
    val needsLevels = brightness != 100.0 || contrast != 100.0
    val channels = panel.channels
    val needsChannels = !channelIsDefault(channels.r) || !channelIsDefault(channels.g) || !channelIsDefault(channels.b)
    if (!needsLevels && !needsChannels && !withDataUrl && width == naturalWidth && height == naturalHeight) {
        return PanelArt(image, naturalWidth, naturalHeight)
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    canvas.drawBitmap(image, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    if (needsLevels || needsChannels) {
        applyComposite(bitmap, brightness, contrast, channels)
    }
    return PanelArt(
        source = bitmap,
        width = width,
        height = height,
        dataUrl = if (withDataUrl) bitmapToPngDataUrl(bitmap) else null,
    )
}
