package com.spice.nativeapp.tools.gel

/**
 * Export helpers: PNG with physical resolution and a processing summary, an
 * uncompressed RGB TIFF, editable SVG, an editable PowerPoint slide, the
 * uncropped annotated image, the original file and a processing log.
 * 1:1 移植自原版 src/lib/tools/gel/export.ts。
 */
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object GelExport {

    // ---------------------------------------------------------------- utilities

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format("%02x", it) }
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------- PNG

    private val CRC_TABLE = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) c = if (c and 1 != 0) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1
            table[n] = c
        }
    }

    private fun crc32(bytes: ByteArray): Int {
        var c = -1
        for (b in bytes) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return c xor (-1)
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(12 + data.size)
        val view = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        view.putInt(0, data.size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        System.arraycopy(data, 0, out, 8, data.size)
        val crc = crc32(out.copyOfRange(4, 8 + data.size))
        view.putInt(8 + data.size, crc)
        return out
    }

    /** Add pHYs, sRGB and an iTXt description to a canvas PNG. */
    fun exportPngWithMetadata(
        bitmap: Bitmap,
        dpi: Double,
        summary: String,
    ): ByteArray {
        val png = bitmapToPngBytes(bitmap)
        val chunks = ArrayList<ByteArray>()
        val phys = ByteArray(9)
        val pv = ByteBuffer.wrap(phys).order(ByteOrder.BIG_ENDIAN)
        val ppm = Math.round(dpi / 0.0254).toInt()
        pv.putInt(0, ppm)
        pv.putInt(4, ppm)
        phys[8] = 1
        chunks.add(pngChunk("pHYs", phys))
        chunks.add(pngChunk("sRGB", byteArrayOf(0)))
        val keyword = "Description".toByteArray(Charsets.UTF_8)
        val text = summary.toByteArray(Charsets.UTF_8)
        val itxt = ByteArray(keyword.size + 5 + text.size)
        System.arraycopy(keyword, 0, itxt, 0, keyword.size)
        // compression flag 0, method 0, empty language tag, empty translated keyword
        var p = keyword.size
        itxt[p++] = 0
        itxt[p++] = 0
        itxt[p++] = 0
        itxt[p++] = 0
        itxt[p++] = 0
        System.arraycopy(text, 0, itxt, p, text.size)
        chunks.add(pngChunk("iTXt", itxt))

        // Insert after IHDR (signature 8 + length/type/data/crc 25).
        val ihdrEnd = 8 + 25
        val extra = chunks.sumOf { it.size }
        val out = ByteArray(png.size + extra)
        System.arraycopy(png, 0, out, 0, ihdrEnd)
        var off = ihdrEnd
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        System.arraycopy(png, ihdrEnd, out, off, png.size - ihdrEnd)
        return out
    }

    // ------------------------------------------------------------------- TIFF

    private fun asciiBytes(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return if (b.size % 2 == 1) b + byteArrayOf(0) else b
    }

    /** Minimal baseline TIFF: 8-bit RGB (or grayscale), one uncompressed strip. */
    fun encodeTiff(
        rgba: IntArray,
        width: Int,
        height: Int,
        dpi: Int,
        description: String,
        grayscale: Boolean,
    ): ByteArray {
        val samples = if (grayscale) 1 else 3
        val pixels = ByteArray(width * height * samples)
        if (grayscale) {
            var p = 0
            for (i in rgba.indices) {
                val c = rgba[i]
                pixels[p++] = Math.round((c shr 16 and 0xFF) * 0.299 + (c shr 8 and 0xFF) * 0.587 + (c and 0xFF) * 0.114).toByte()
            }
        } else {
            var p = 0
            for (c in rgba) {
                pixels[p++] = (c shr 16 and 0xFF).toByte()
                pixels[p++] = (c shr 8 and 0xFF).toByte()
                pixels[p++] = (c and 0xFF).toByte()
            }
        }
        val bits = if (grayscale) intArrayOf(8) else intArrayOf(8, 8, 8)
        val desc = asciiBytes(description)
        val software = asciiBytes("SPICE gel figure tool")
        val date = asciiBytes(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now())
        )

        class Entry(val tag: Int, val type: Int, val count: Int, val value: Any)

        val entries = listOf(
            Entry(256, 4, 1, width),
            Entry(257, 4, 1, height),
            Entry(258, 3, bits.size, bits),
            Entry(259, 3, 1, 1),
            Entry(262, 3, 1, if (grayscale) 1 else 2),
            Entry(270, 2, desc.size, desc),
            Entry(273, 4, 1, 0),
            Entry(277, 3, 1, samples),
            Entry(278, 4, 1, height),
            Entry(279, 4, 1, pixels.size),
            Entry(282, 5, 1, Pair(dpi, 1)),
            Entry(283, 5, 1, Pair(dpi, 1)),
            Entry(296, 3, 1, 2),
            Entry(305, 2, software.size, software),
            Entry(306, 2, date.size, date),
            Entry(339, 3, bits.size, IntArray(bits.size) { 1 }),
        ).sortedBy { it.tag }

        fun encodeValue(e: Entry): ByteArray {
            if (e.type == 2) return e.value as ByteArray
            if (e.type == 1) return e.value as ByteArray
            if (e.type == 5) {
                val rat = e.value as Pair<Int, Int>
                val bytes = ByteArray(8)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(rat.first)
                    putInt(4, rat.second)
                }
                return bytes
            }
            val arr: IntArray = when (val v = e.value) {
                is Int -> intArrayOf(v)
                is IntArray -> v
                else -> throw IllegalArgumentException()
            }
            val bytes = ByteArray(arr.size * (if (e.type == 4) 4 else 2))
            val dv = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            arr.forEachIndexed { i, v ->
                if (e.type == 4) dv.putInt(i * 4, v) else dv.putShort(i * 2, v.toShort())
            }
            return bytes
        }

        val ifdOffset = 8
        val ifdSize = 2 + entries.size * 12 + 4
        var extraOffset = ifdOffset + ifdSize
        val extras = ArrayList<Pair<Int, ByteArray>>() // offset to bytes
        fun alloc(bytes: ByteArray): Int {
            val offset = extraOffset
            extras.add(offset to bytes)
            extraOffset += bytes.size + (bytes.size % 2)
            return offset
        }
        data class Resolved(val e: Entry, val bytes: ByteArray, val inline: Boolean, val offset: Int)
        val resolved = entries.map { e ->
            val bytes = encodeValue(e)
            val inline = bytes.size <= 4
            Resolved(e, bytes, inline, if (inline) 0 else alloc(bytes))
        }
        val stripOffset = extraOffset
        val total = stripOffset + pixels.size
        val out = ByteArray(total)
        val dv = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 0x49.toByte()
        out[1] = 0x49.toByte()
        dv.putShort(2, 42.toShort())
        dv.putInt(4, ifdOffset)
        dv.putShort(ifdOffset, entries.size.toShort())
        var ip = ifdOffset + 2
        for (r in resolved) {
            dv.putShort(ip, r.e.tag.toShort())
            dv.putShort(ip + 2, r.e.type.toShort())
            dv.putInt(ip + 4, r.e.count)
            if (r.e.tag == 273) {
                dv.putInt(ip + 8, stripOffset)
            } else if (r.inline) {
                System.arraycopy(r.bytes, 0, out, ip + 8, r.bytes.size)
            } else {
                dv.putInt(ip + 8, r.offset)
            }
            ip += 12
        }
        dv.putInt(ip, 0)
        for ((offset, bytes) in extras) System.arraycopy(bytes, 0, out, offset, bytes.size)
        System.arraycopy(pixels, 0, out, stripOffset, pixels.size)
        return out
    }

    // -------------------------------------------------------------------- SVG

    fun makeSvg(layout: FigureLayout, processed: ProcessedImage): String {
        val png = bitmapToPngBytes(processed.bitmap)
        val href = "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        return GelRender.figureToSvg(layout, href, processed.width, processed.height)
    }

    // ------------------------------------------------------------------- PPTX

    /** Editable slide: the gel is a cropped picture and every label is real text.
     *  pptxgenjs → 自写 GelPptxWriter（自定义 GEL_FIGURE 版面 + 图片裁剪）。 */
    fun makePptx(layout: FigureLayout, processed: ProcessedImage): ByteArray {
        val png = bitmapToPngBytes(processed.bitmap)
        return GelPptxWriter.write(layout, png, processed.width, processed.height)
    }

    // --------------------------------------------------------------- pre-flight

    class PreflightItem(val id: String, val severity: String, val value: Double)

    fun preflight(
        project: GelProject,
        layout: FigureLayout,
        processed: ProcessedImage,
        rawGray: FloatArray,
        adjustedGray: FloatArray,
    ): List<PreflightItem> {
        val out = ArrayList<PreflightItem>()
        val rawClip = clippedFraction(rawGray)
        if (rawClip.first > 0.003 || rawClip.second > 0.003) {
            out.add(PreflightItem("saturation", "warn", Math.round((rawClip.first + rawClip.second) * 1000) / 10.0))
        }
        val adjClip = clippedFraction(adjustedGray)
        if (adjClip.first > 0.02 || adjClip.second > 0.02) {
            out.add(PreflightItem("clip", "warn", Math.round((adjClip.first + adjClip.second) * 1000) / 10.0))
        }
        val visW = 1 - project.crop.left - project.crop.right
        val effectiveDpi = (processed.width * visW) / (layout.gel.w / 96)
        if (effectiveDpi < 300) out.add(PreflightItem("resolution", "warn", Math.round(effectiveDpi).toDouble()))
        val minFont = Math.min(project.layout.nameFontSize, project.layout.labelFontSize) * 0.75
        if (minFont < 6) out.add(PreflightItem("tinyFont", "warn", Math.round(minFont * 10) / 10.0))
        val approx = project.lanes.sumOf { l -> l.bands.count { it.approx == true } }
        if (approx > 0) out.add(PreflightItem("approx", "info", approx.toDouble()))
        if (project.gelType == "protein") out.add(PreflightItem("protein", "info", 0.0))
        if (project.fit == null) out.add(PreflightItem("ladder", "warn", 0.0))
        return out
    }

    /** (dark, bright) */
    private fun clippedFraction(gray: FloatArray): Pair<Double, Double> {
        var dark = 0
        var bright = 0
        for (v in gray) {
            if (v <= 0.004f) dark++
            else if (v >= 0.996f) bright++
        }
        val n = maxOf(1, gray.size)
        return (dark.toDouble() / n) to (bright.toDouble() / n)
    }

    // --------------------------------------------------------- processing log

    fun makeLog(
        project: GelProject,
        processed: ProcessedImage,
        layout: FigureLayout,
        ladders: List<LadderDef>,
        sourceSha: String? = null,
    ): org.json.JSONObject {
        val exposure = project.exposures.find { it.id == project.activeExposureId }
        val ladder = GelLadders.ladderById(project.fit?.ladderId ?: project.ladderId, ladders)
        val visW = 1 - project.crop.left - project.crop.right
        val o = org.json.JSONObject()
        o.put("format", "SPICE_GEL_LOG")
        o.put("version", 1)
        o.put("exportedAt", Instant.now().toString())
        o.put("source", org.json.JSONObject().apply {
            put("name", exposure?.name ?: project.imageName)
            put("mime", exposure?.mime ?: "")
            put("width", exposure?.width ?: processed.width)
            put("height", exposure?.height ?: processed.height)
            put("sha256", sourceSha ?: exposure?.sha256 ?: org.json.JSONObject.NULL)
        })
        o.put("image", org.json.JSONObject().apply {
            put("rotation", project.rotation)
            put("orient", project.orient)
            put("mirrorH", project.mirrorH)
            put("mirrorV", project.mirrorV)
            put("display", project.display)
            put("crop", org.json.JSONObject().apply {
                put("left", project.crop.left)
                put("top", project.crop.top)
                put("right", project.crop.right)
                put("bottom", project.crop.bottom)
            })
            put("levels", org.json.JSONObject().apply {
                put("grayscale", project.adjust.grayscale)
                put("invert", project.adjust.invert)
                put("contrast", project.adjust.contrast)
                put("brightness", project.adjust.brightness)
                put("gamma", project.adjust.gamma)
                put("gammaLocked", project.adjust.gammaLocked)
            })
        })
        o.put("gel", org.json.JSONObject().apply {
            put("type", project.gelType)
            put("unit", project.unit)
            put("effectiveDpi", (processed.width * visW) / (layout.gel.w / 96))
        })
        o.put("ladder", if (ladder != null) org.json.JSONObject().apply {
            put("id", ladder.id)
            put("name", ladder.name)
            put("degree", project.fit?.degree ?: 1)
            put("r2", project.fit?.r2 ?: 0.0)
            put("n", project.fit?.n ?: 0)
        } else org.json.JSONObject.NULL)
        o.put("lanes", org.json.JSONArray().apply {
            project.lanes.forEachIndexed { i, l ->
                put(org.json.JSONObject().apply {
                    put("index", i + 1)
                    put("name", l.name)
                    put("marker", l.isMarker)
                    put("removed", l.removed == true)
                    put("bands", l.bands.size)
                })
            }
        })
        o.put("arrows", org.json.JSONArray().apply {
            for (a in project.annotations.filterIsInstance<GelAnnotation.Arrow>()) {
                val lane = project.lanes.find { it.id == a.laneId }
                val band = lane?.bands?.getOrNull(a.bandIndex)
                put(org.json.JSONObject().apply {
                    put("lane", lane?.name ?: "")
                    put("band", a.bandIndex)
                    put("size", band?.size ?: org.json.JSONObject.NULL)
                    put("approx", band?.approx == true)
                })
            }
        })
        o.put("legendNote",
            if (project.gelType == "protein")
                "Prestained marker sizes are apparent molecular weights (Tris-glycine)."
            else "")
        return o
    }

    /** Processing summary embedded in PNG/TIFF metadata. */
    fun summaryText(project: GelProject, processed: ProcessedImage, ladder: LadderDef? = null): String {
        val parts = mutableListOf(
            "SPICE gel figure; ${project.gelType} gel; unit ${project.unit}",
            String.format(Locale.US, "rotation %.1f deg", project.rotation),
            String.format(
                Locale.US, "crop L%.3f T%.3f R%.3f B%.3f",
                project.crop.left, project.crop.top, project.crop.right, project.crop.bottom,
            ),
            "display ${project.display}",
            "levels c=${project.adjust.contrast} b=${project.adjust.brightness} g=${project.adjust.gamma} invert=${project.adjust.invert}",
            "source ${processed.width}x${processed.height}px",
        )
        if (ladder != null) parts.add("ladder ${ladder.name} (${ladder.sizes.size} bands)")
        project.fit?.let { parts.add(String.format(Locale.US, "fit R2=%.3f n=%d", it.r2, it.n)) }
        return parts.joinToString("; ")
    }

    fun uncroppedBitmap(project: GelProject, processed: ProcessedImage): Bitmap =
        GelRender.renderUncropped(project, processed, 1.0)

    fun fitSizeAt(project: GelProject, y: Double): Double? {
        val fit = project.fit ?: return null
        return Math.pow(10.0, GelMath.polyAt(fit, y))
    }
}
