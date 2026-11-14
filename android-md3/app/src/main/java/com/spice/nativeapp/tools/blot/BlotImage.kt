package com.spice.nativeapp.tools.blot

/*
 * Image import and the display pipeline. The source is decoded once, then
 * orientation, fine rotation and tonal adjustments are applied on demand. The
 * raw grayscale plane is kept free of display adjustments so densitometry is
 * not biased by contrast or gamma. 1:1 port of main's
 * src/lib/tools/blot/image.ts (Android: Bitmap replaces HTMLImageElement and
 * TIFF goes through the built-in GelTiff decoder instead of utif2 — see
 * README "Native platform differences").
 */

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Base64
import com.spice.nativeapp.tools.gel.GelTiff
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt

class DecodedImage(
    val name: String,
    val dataUrl: String,
    val width: Int,
    val height: Int,
    val mime: String,
)

class ProcessedImage(
    /** Adjusted bitmap used for display and figure embedding. */
    val canvas: Bitmap,
    /** Unadjusted oriented bitmap, used for raw grayscale extraction. */
    val base: Bitmap,
    val rawGray: FloatArray,
    val width: Int,
    val height: Int,
)

private fun isTiffName(name: String): Boolean = Regex("(?i)\\.tiff?$").containsMatchIn(name)

/** Decode PNG/JPEG/WebP/BMP/GIF directly and TIFF through the built-in TIFF
 *  decoder, always normalising the payload to a PNG data URL. */
fun decodeImageFile(name: String, bytes: ByteArray): DecodedImage {
    val isTiff = isTiffName(name)
    if (isTiff) {
        val dec = GelTiff.decode(bytes)
        val bmp = Bitmap.createBitmap(dec.argb, dec.width, dec.height, Bitmap.Config.ARGB_8888)
        return DecodedImage(
            name = name,
            dataUrl = bitmapToDataUrl(bmp),
            width = dec.width,
            height = dec.height,
            mime = "image/tiff",
        )
    }
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bmp == null && GelTiff.isTiff(bytes)) {
        val dec = try {
            GelTiff.decode(bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("TIFF contains no image")
        }
        bmp = Bitmap.createBitmap(dec.argb, dec.width, dec.height, Bitmap.Config.ARGB_8888)
    }
    if (bmp == null) throw IllegalArgumentException("Unsupported image format")
    return DecodedImage(
        name = name,
        dataUrl = bitmapToDataUrl(bmp),
        width = bmp.width,
        height = bmp.height,
        mime = "image/png",
    )
}

fun bitmapToDataUrl(bitmap: Bitmap): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

fun loadBitmapFromDataUrl(dataUrl: String): Bitmap? {
    if (!dataUrl.startsWith("data:")) return null
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    val meta = dataUrl.substring(0, comma)
    val payload = dataUrl.substring(comma + 1)
    return try {
        if (meta.contains("image/tiff") || meta.contains("image/tif")) {
            val dec = GelTiff.decode(Base64.decode(payload, Base64.DEFAULT))
            Bitmap.createBitmap(dec.argb, dec.width, dec.height, Bitmap.Config.ARGB_8888)
        } else {
            BitmapFactory.decodeByteArray(Base64.decode(payload, Base64.DEFAULT), 0, payload.length)
        }
    } catch (_: Exception) {
        try {
            val raw = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Exception) {
            null
        }
    }
}

fun toGrayPlane(pixels: IntArray, width: Int, height: Int): FloatArray {
    val out = FloatArray(width * height)
    for (i in 0 until width * height) {
        val c = pixels[i]
        val r = ((c shr 16) and 0xff).toDouble()
        val g = ((c shr 8) and 0xff).toDouble()
        val b = (c and 0xff).toDouble()
        out[i] = ((r * 0.299 + g * 0.587 + b * 0.114) / 255).toFloat()
    }
    return out
}

fun bitmapGrayPlane(bitmap: Bitmap): FloatArray {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    return toGrayPlane(px, w, h)
}

private class GrayLookup {
    val table = IntArray(256)
    private var contrast = Double.NaN
    private var brightness = Double.NaN
    private var gamma = Double.NaN
    private var invert = false

    fun ensure(project: BlotProject) {
        val a = project.adjust
        if (contrast == a.contrast && brightness == a.brightness &&
            gamma == a.gamma && invert == a.invert
        ) return
        contrast = a.contrast
        brightness = a.brightness
        gamma = a.gamma
        invert = a.invert
        val g = max(0.1, a.gamma)
        for (i in 0 until 256) {
            var x = i / 255.0
            x = (x - 0.5) * contrast + 0.5 + brightness
            x = min(1.0, max(0.0, x))
            x = x.pow(g)
            if (invert) x = 1 - x
            table[i] = (x * 255).roundToInt()
        }
    }
}

private val grayLookup = GrayLookup()

private fun applyAdjustments(px: IntArray, project: BlotProject) {
    grayLookup.ensure(project)
    val lookup = grayLookup.table
    val grayscale = project.adjust.display == "gray"
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        val alpha = c and -0x1000000
        px[i] = if (grayscale) {
            val v = (r * 0.299 + g * 0.587 + b * 0.114).roundToInt().coerceIn(0, 255)
            val out = lookup[v]
            alpha or (out shl 16) or (out shl 8) or out
        } else {
            alpha or (lookup[r] shl 16) or (lookup[g] shl 8) or lookup[b]
        }
    }
}

class OrientedSize(val width: Int, val height: Int)

private fun orientedSize(width: Int, height: Int, orient: Int, rotation: Double): OrientedSize {
    val swap = orient % 2 == 1
    val ow = (if (swap) height else width).toDouble()
    val oh = (if (swap) width else height).toDouble()
    val rad = rotation * Math.PI / 180
    val cos = abs(kotlin.math.cos(rad))
    val sin = abs(kotlin.math.sin(rad))
    return OrientedSize(
        max(1, (ow * cos + oh * sin).roundToInt()),
        max(1, (ow * sin + oh * cos).roundToInt()),
    )
}

fun renderProcessed(project: BlotProject, source: Bitmap): ProcessedImage {
    val w = source.width
    val h = source.height
    val orient = ((project.orient % 4) + 4) % 4
    val size = orientedSize(w, h, orient, project.rotation)
    val rad = project.rotation * Math.PI / 180
    val base = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val bcanvas = Canvas(base)
    // Rotation corners are filled with white so a normal membrane scan keeps a
    // light surround; the display inversion then turns them dark consistently.
    val white = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    bcanvas.drawRect(0f, 0f, base.width.toFloat(), base.height.toFloat(), white)
    val matrix = Matrix()
    matrix.setRotate(
        ((rad + orient * Math.PI / 2) * 180 / Math.PI).toFloat(),
        w / 2f, h / 2f,
    )
    matrix.postTranslate(
        (base.width / 2f) - (w / 2f),
        (base.height / 2f) - (h / 2f),
    )
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    bcanvas.drawBitmap(source, matrix, paint)

    val rawGray = bitmapGrayPlane(base)

    val display = base.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(display.width * display.height)
    display.getPixels(px, 0, display.width, 0, 0, display.width, display.height)
    applyAdjustments(px, project)
    display.setPixels(px, 0, display.width, 0, 0, display.width, display.height)

    return ProcessedImage(display, base, rawGray, display.width, display.height)
}

/** Fine-rotation coordinate transform, so lane and band ROIs follow the gel. */
fun rotateCoordinates(
    project: BlotProject,
    oldRotation: Double,
    newRotation: Double,
    sourceWidth: Int,
    sourceHeight: Int,
) {
    val orient = ((project.orient % 4) + 4) % 4
    val swap = orient % 2 == 1
    val ow = (if (swap) sourceHeight else sourceWidth).toDouble()
    val oh = (if (swap) sourceWidth else sourceHeight).toDouble()
    fun dims(deg: Double): Pair<Double, Double> {
        val rad = deg * Math.PI / 180
        val cos = abs(kotlin.math.cos(rad))
        val sin = abs(kotlin.math.sin(rad))
        return ow * cos + oh * sin to ow * sin + oh * cos
    }
    val (oldW, oldH) = dims(oldRotation)
    val (newW, newH) = dims(newRotation)
    val rad = (newRotation - oldRotation) * Math.PI / 180
    val cos = kotlin.math.cos(rad)
    val sin = kotlin.math.sin(rad)
    fun map(x: Double, y: Double): Pair<Double, Double> {
        val px = x * oldW - oldW / 2
        val py = y * oldH - oldH / 2
        return (px * cos - py * sin + newW / 2) / newW to (px * sin + py * cos + newH / 2) / newH
    }
    for (lane in project.lanes) {
        val (cx, _) = map(lane.x, 0.5)
        for (band in lane.bands) band.y = map(lane.x, band.y).second
        lane.x = cx
    }
}

class CropPixels(val sx: Int, val sy: Int, val sw: Int, val sh: Int)

fun cropPixels(project: BlotProject, width: Int, height: Int): CropPixels {
    val crop = project.crop
    return CropPixels(
        sx = (crop.left * width).roundToInt(),
        sy = (crop.top * height).roundToInt(),
        sw = max(8, ((1 - crop.left - crop.right) * width).roundToInt()),
        sh = max(8, ((1 - crop.top - crop.bottom) * height).roundToInt()),
    )
}

/** Cropped copy of the adjusted bitmap for previews and exports. */
fun cropCanvas(source: Bitmap, crop: BlotCrop): Bitmap {
    val sx = (crop.left * source.width).roundToInt()
    val sy = (crop.top * source.height).roundToInt()
    val sw = max(8, ((1 - crop.left - crop.right) * source.width).roundToInt())
    val sh = max(8, ((1 - crop.top - crop.bottom) * source.height).roundToInt())
    return Bitmap.createBitmap(source, sx.coerceIn(0, source.width - 1), sy.coerceIn(0, source.height - 1),
        min(sw, source.width - sx), min(sh, source.height - sy))
}

/** Border-ring median of the raw plane, a robust global background estimate. */
fun globalBackground(gray: FloatArray, width: Int, height: Int): Double {
    val ring = max(2, (min(width, height) * 0.03).roundToInt())
    val values = mutableListOf<Double>()
    val stepX = max(1, floor(width / 400.0).toInt())
    val stepY = max(1, floor(height / 400.0).toInt())
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (x < ring || y < ring || x >= width - ring || y >= height - ring) {
                values.add((1 - gray[y * width + x]).toDouble())
            }
            x += stepX
        }
        y += stepY
    }
    if (values.isEmpty()) return 0.0
    values.sort()
    return values[floor(values.size / 2.0).toInt()]
}
