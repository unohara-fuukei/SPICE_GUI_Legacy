package com.spice.nativeapp.tools.gel

/**
 * Image decoding and the processed-image pipeline: orientation, fine rotation,
 * crop-free pixel adjustments and the grayscale planes used by detection.
 * 1:1 移植自原版 src/lib/tools/gel/pipeline.ts（HTMLCanvas → Android Bitmap/Matrix）。
 */
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ProcessedImage(
    /** 渲染后的完整处理图（含 orientation/rotation/mirror/adjust）。 */
    val bitmap: Bitmap,
    /** Grayscale before display adjustments (camera data). */
    val rawGray: FloatArray,
    /** Grayscale after adjustments (also used for geometry). */
    val gray: FloatArray,
    /** Plane lane/band detection thresholds. Same reference as `gray` unless a
     *  stain preset selects an explicit colour channel. */
    val detectGray: FloatArray,
    val width: Int,
    val height: Int,
)

object GelPipeline {

    /** 对应原版 loadImageElement + decodeExposure：从 ContentResolver 读文件。 */
    fun decodeExposure(ctx: Context, uri: Uri, displayName: String, mime: String?): GelExposure? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = displayName.ifEmpty { "image" }
        val id = "exp_${java.util.UUID.randomUUID().toString().take(8)}"
        val originalBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val bmp: Bitmap = (
            if (GelTiff.isTiff(bytes)) {
                try {
                    val dec = GelTiff.decode(bytes)
                    Bitmap.createBitmap(dec.argb, dec.width, dec.height, Bitmap.Config.ARGB_8888)
                } catch (e: Exception) {
                    null
                }
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ) ?: return null

        // TIFF 走 utif2 解码后转 PNG dataUrl；其余保留原始字节与 MIME（与原版一致）。
        val isTiff = GelTiff.isTiff(bytes)
        val mimeOut = if (isTiff) "image/tiff" else (mime ?: "image/png")
        val dataUrl = if (isTiff) {
            val png = ByteArrayOutputStream().use { o ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
                o.toByteArray()
            }
            "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        } else {
            "data:$mimeOut;base64," + originalBase64
        }
        return GelExposure(
            id = id,
            name = name,
            dataUrl = dataUrl,
            width = bmp.width,
            height = bmp.height,
            mime = mimeOut,
            originalBase64 = originalBase64,
            sha256 = sha256,
        )
    }

    /** dataUrl → Bitmap（原版 loadImageElement 的缓存交给调用方）。 */
    fun loadBitmapFromDataUrl(dataUrl: String): Bitmap? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        val bytes = try {
            Base64.decode(dataUrl.substring(comma + 1), Base64.NO_WRAP or Base64.DEFAULT)
        } catch (e: Exception) {
            null
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Quality proxy for picking the best exposure: signal spread minus clipping. */
    fun exposureScore(processed: ProcessedImage): Double {
        val gray = processed.rawGray
        var mean = 0.0
        for (v in gray) mean += v.toDouble()
        mean /= maxOf(1, gray.size)
        var variance = 0.0
        var clipped = 0
        for (v in gray) {
            variance += (v - mean) * (v - mean)
            if (v <= 0.004f || v >= 0.996f) clipped++
        }
        return Math.sqrt(variance / maxOf(1, gray.size)) - (clipped.toDouble() / maxOf(1, gray.size)) * 2
    }

    fun renderProcessed(exposure: GelExposure, project: GelProject, img: Bitmap): ProcessedImage? {
        val w = img.width
        val h = img.height
        val orient = ((project.orient % 4) + 4) % 4
        val swap = orient % 2 == 1
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        val rad = Math.toRadians(project.rotation)
        val cos = Math.abs(Math.cos(rad))
        val sin = Math.abs(Math.sin(rad))
        val rw = maxOf(1, Math.round(ow * cos + oh * sin).toInt())
        val rh = maxOf(1, Math.round(ow * sin + oh * cos).toInt())
        val canvasBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val matrix = Matrix()
        matrix.postTranslate(rw / 2f, rh / 2f)
        matrix.postRotate((rad + orient * Math.PI / 2).toFloat())
        matrix.postScale(if (project.mirrorH) -1f else 1f, if (project.mirrorV) -1f else 1f)
        matrix.postTranslate(-w / 2f, -h / 2f)
        canvas.drawBitmap(img, matrix, null)

        val argb = IntArray(rw * rh)
        canvasBmp.getPixels(argb, 0, rw, 0, 0, rw, rh)
        val rawGray = GelMath.toGrayPlane(argb, rw, rh)
        // Explicit colour channels come from the camera pixels, before display
        // adjustments: a gamma curve would distort the channel ratio the preset
        // relies on. Luma keeps the adjusted plane the editor has always used.
        val channel = project.detect.channel
        val channelGray =
            if (channel == "auto" || channel == "luma") null
            else GelPresets.channelPlane(argb, rw, rh, channel)
        if (project.display == "color") {
            return ProcessedImage(canvasBmp, rawGray, rawGray, channelGray ?: rawGray, rw, rh)
        }
        applyAdjustments(argb, project)
        canvasBmp.setPixels(argb, 0, rw, 0, 0, rw, rh)
        val gray = GelMath.toGrayPlane(argb, rw, rh)
        return ProcessedImage(canvasBmp, rawGray, gray, channelGray ?: gray, rw, rh)
    }

    fun applyAdjustments(px: IntArray, project: GelProject) {
        val grayscale = project.adjust.grayscale
        val invert = project.adjust.invert
        val contrast = project.adjust.contrast
        val brightness = project.adjust.brightness
        val g = maxOf(0.2, project.adjust.gamma)
        for (i in px.indices) {
            var r = (px[i] shr 16) and 0xFF
            var gg = (px[i] shr 8) and 0xFF
            var b = px[i] and 0xFF
            if (grayscale) {
                val v = (r * 0.299 + gg * 0.587 + b * 0.114).toInt()
                r = v; gg = v; b = v
            }
            px[i] = (px[i] and 0xFF000000.toInt()) or
                (shift(r.toDouble(), contrast, brightness, g, invert) shl 16) or
                (shift(gg.toDouble(), contrast, brightness, g, invert) shl 8) or
                shift(b.toDouble(), contrast, brightness, g, invert)
        }
    }

    private fun shift(v: Double, contrast: Double, brightness: Double, gamma: Double, invert: Boolean): Int {
        var x = v / 255.0
        x = (x - 0.5) * contrast + 0.5 + brightness
        x = minOf(1.0, maxOf(0.0, x))
        x = Math.pow(x, gamma)
        if (invert) x = 1 - x
        return (x * 255).toInt()
    }

    /** Crop source rectangle in processed-image pixels. */
    fun cropRect(project: GelProject, width: Int, height: Int): IntArray {
        val c = project.crop
        return intArrayOf(
            Math.round(c.left * width).toInt(),
            Math.round(c.top * height).toInt(),
            maxOf(8, Math.round((1 - c.left - c.right) * width).toInt()),
            maxOf(8, Math.round((1 - c.top - c.bottom) * height).toInt()),
        )
    }

    /** Crop to a new bitmap for display and figure preview. */
    fun cropBitmap(processed: ProcessedImage, project: GelProject): Bitmap {
        val rect = cropRect(project, processed.width, processed.height)
        return Bitmap.createBitmap(processed.bitmap, rect[0], rect[1], rect[2], rect[3])
    }

    /** Rotate/flip every stored coordinate so annotations travel with the image. */
    fun transformForFlip(project: GelProject, axis: String) {
        val fx = fun(x: Double): Double = if (axis == "v") x else 1 - x
        val fy = fun(y: Double): Double = if (axis == "h") y else 1 - y
        for (lane in project.lanes) {
            lane.x = fx(lane.x)
            for (band in lane.bands) band.y = fy(band.y)
        }
        for (a in project.annotations) {
            when (a) {
                is GelAnnotation.Box -> {
                    if (axis != "v") a.x = 1 - a.x - a.w
                    if (axis != "h") a.y = 1 - a.y - a.h
                }
                is GelAnnotation.Bracket -> {
                    if (a.side == "left" || a.side == "right") {
                        a.start = fy(a.start); a.end = fy(a.end)
                    } else {
                        a.start = fx(a.start); a.end = fx(a.end)
                    }
                    if (axis == "h") a.side = if (a.side == "left") "right" else if (a.side == "right") "left" else a.side
                    if (axis == "v") a.side = if (a.side == "top") "bottom" else if (a.side == "bottom") "top" else a.side
                    if (axis == "180") {
                        a.side = if (a.side == "left") "right" else if (a.side == "right") "left" else if (a.side == "top") "bottom" else "top"
                    }
                }
                is GelAnnotation.SideText -> {
                    a.pos = if (axis == "v") a.pos else 1 - a.pos
                    if (axis == "h") a.side = if (a.side == "left") "right" else if (a.side == "right") "left" else a.side
                    if (axis == "180") {
                        a.side = if (a.side == "left") "right" else if (a.side == "right") "left" else if (a.side == "top") "bottom" else "top"
                    }
                }
                else -> {}
            }
        }
    }

    /** Coordinates after a clockwise quarter turn. Lanes are re-detected by the
     *  caller; boxes, brackets and captions rotate here. */
    fun transformForQuarterTurn(project: GelProject, clockwise: Boolean = true) {
        for (a in project.annotations) {
            when (a) {
                is GelAnnotation.Box -> {
                    val x = a.x
                    val y = a.y
                    val w = a.w
                    val h = a.h
                    if (clockwise) {
                        a.x = 1 - y - h
                        a.y = x
                    } else {
                        a.x = y
                        a.y = 1 - x - w
                    }
                    a.w = h
                    a.h = w
                }
                is GelAnnotation.Bracket -> {
                    if (a.side == "left" || a.side == "right") {
                        val start = if (clockwise) 1 - a.end else a.start
                        val end = if (clockwise) 1 - a.start else a.end
                        a.side = if (clockwise) "bottom" else "top"
                        a.start = start
                        a.end = end
                    } else {
                        val start = if (clockwise) a.start else 1 - a.start
                        val end = if (clockwise) a.end else 1 - a.end
                        a.side = if (clockwise) "left" else "right"
                        a.start = start
                        a.end = end
                    }
                }
                is GelAnnotation.SideText -> {
                    val sides = if (clockwise) mapOf("left" to "top", "top" to "right", "right" to "bottom", "bottom" to "left")
                    else mapOf("left" to "bottom", "bottom" to "right", "right" to "top", "top" to "left")
                    a.side = sides[a.side] ?: a.side
                    a.pos = if (clockwise) 1 - a.pos else a.pos
                }
                else -> {}
            }
        }
    }

    /** Rotate lane, band and annotation coordinates around the image centre when
     *  the fine rotation changes, so annotations stay on their bands. */
    fun rotateProjectCoordinates(
        project: GelProject,
        oldRotation: Double,
        newRotation: Double,
        naturalWidth: Int,
        naturalHeight: Int,
    ) {
        val orient = ((project.orient % 4) + 4) % 4
        val swap = orient % 2 == 1
        val ow = (if (swap) naturalHeight else naturalWidth).toDouble()
        val oh = (if (swap) naturalWidth else naturalHeight).toDouble()
        fun dims(deg: Double): Pair<Double, Double> {
            val r = Math.toRadians(deg)
            val cos = Math.abs(Math.cos(r))
            val sin = Math.abs(Math.sin(r))
            return maxOf(1.0, ow * cos + oh * sin) to maxOf(1.0, ow * sin + oh * cos)
        }
        val oldD = dims(oldRotation)
        val newD = dims(newRotation)
        val rad = Math.toRadians(newRotation - oldRotation)
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)
        fun mapX(x: Double, y: Double): Double {
            val px = x * oldD.first - oldD.first / 2
            val py = y * oldD.second - oldD.second / 2
            return (px * cos - py * sin + newD.first / 2) / newD.first
        }
        fun mapY(x: Double, y: Double): Double {
            val px = x * oldD.first - oldD.first / 2
            val py = y * oldD.second - oldD.second / 2
            return (px * sin + py * cos + newD.second / 2) / newD.second
        }
        for (lane in project.lanes) {
            val nx = mapX(lane.x, 0.5)
            for (band in lane.bands) band.y = mapY(lane.x, band.y)
            lane.x = nx
        }
        for (a in project.annotations) {
            when (a) {
                is GelAnnotation.Box -> {
                    val xs = doubleArrayOf(mapX(a.x, a.y), mapX(a.x + a.w, a.y), mapX(a.x, a.y + a.h), mapX(a.x + a.w, a.y + a.h))
                    val ys = doubleArrayOf(mapY(a.x, a.y), mapY(a.x + a.w, a.y), mapY(a.x, a.y + a.h), mapY(a.x + a.w, a.y + a.h))
                    val x0 = xs.min()
                    val y0 = ys.min()
                    a.x = x0
                    a.y = y0
                    a.w = xs.max() - x0
                    a.h = ys.max() - y0
                }
                is GelAnnotation.Bracket -> {
                    if (a.side == "left" || a.side == "right") {
                        a.start = mapY(0.5, a.start)
                        a.end = mapY(0.5, a.end)
                    } else {
                        a.start = mapX(a.start, 0.5)
                        a.end = mapX(a.end, 0.5)
                    }
                }
                is GelAnnotation.SideText -> {
                    a.pos = if (a.side == "left" || a.side == "right") mapY(0.5, a.pos) else mapX(a.pos, 0.5)
                }
                else -> {}
            }
        }
    }
}
