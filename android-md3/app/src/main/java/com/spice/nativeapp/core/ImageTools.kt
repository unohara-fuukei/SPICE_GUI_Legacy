package com.spice.nativeapp.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.abs
import kotlin.math.log10

/**
 * 轻量图像分析：灰度化、泳道切分、条带峰检测、连通域菌落计数。
 * 不使用 OpenCV / ONNX，全部为纯 Kotlin 实现，便于离线运行。
 */
object ImageTools {

    data class Band(val lane: Int, val yFraction: Float, val strength: Float, val estBp: Int)
    data class Colony(val x: Int, val y: Int, val area: Int)
    data class ColonyResult(val colonies: List<Colony>, val threshold: Float)

    /** 读取并按最长边缩放，避免 OOM */
    fun loadScaled(ctx: Context, uri: Uri, maxDim: Int = 1400): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim) sample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
    }.getOrNull()

    fun toGray(bmp: Bitmap): Array<FloatArray> {
        val w = bmp.width
        val h = bmp.height
        val out = Array(h) { FloatArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16 and 0xFF)
            val g = (c shr 8 and 0xFF)
            val b = (c and 0xFF)
            out[y][x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return out
    }

    /** 列投影找泳道中心（亮带为条带时，泳道处列均值偏低） */
    private fun laneCenters(gray: Array<FloatArray>): List<Int> {
        val h = gray.size
        val w = gray[0].size
        val col = FloatArray(w)
        for (x in 0 until w) {
            var s = 0f
            for (y in 0 until h) s += gray[y][x]
            col[x] = s / h
        }
        val mean = col.average().toFloat()
        val std = kotlin.math.sqrt(col.map { (it - mean) * (it - mean) }.average()).toFloat()
        // 泳道信号：与背景差异明显的连续区段
        val flags = col.map { abs(it - mean) > std * 0.6f }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i] && start < 0) start = i
            if ((!flags[i] || i == flags.size - 1) && start >= 0) {
                val end = if (flags[i]) i else i - 1
                if (end - start >= w / 25) runs.add(start..end)
                start = -1
            }
        }
        return runs.map { (it.first + it.last) / 2 }.take(16)
    }

    /** 泳道内按行投影找条带峰 */
    private fun bandsInLane(gray: Array<FloatArray>, centerX: Int, laneW: Int): List<Pair<Int, Float>> {
        val h = gray.size
        val half = (laneW / 2).coerceAtLeast(3)
        val from = (centerX - half).coerceAtLeast(0)
        val to = (centerX + half).coerceAtMost(gray[0].size - 1)
        val prof = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            for (x in from..to) s += gray[y][x]
            prof[y] = s / (to - from + 1)
        }
        val mean = prof.average().toFloat()
        val std = kotlin.math.sqrt(prof.map { (it - mean) * (it - mean) }.average()).toFloat()
        if (std < 0.01f) return emptyList()
        val peaks = mutableListOf<Pair<Int, Float>>()
        val minGap = (h / 60).coerceAtLeast(3)
        var last = -minGap
        for (y in 1 until h - 1) {
            if (prof[y] > prof[y - 1] && prof[y] >= prof[y + 1] && prof[y] > mean + std * 1.2f && y - last >= minGap) {
                peaks.add(y to prof[y])
                last = y
            }
        }
        return peaks
    }

    /**
     * 凝胶照片条带识别。
     * 迁移换算：以 100 bp 位于图像底部 0.92、10 kb 位于顶部 0.08 为默认标定，
     * 返回的 estBp 为粗略估计，精确值需用 ladder 泳道回归。
     */
    fun analyzeGel(gray: Array<FloatArray>): List<Band> {
        val h = gray.size
        val w = gray[0].size
        val centers = laneCenters(gray)
        if (centers.isEmpty()) return emptyList()
        val laneW = (w / centers.size)
        val out = mutableListOf<Band>()
        centers.forEachIndexed { idx, cx ->
            bandsInLane(gray, cx, laneW).forEach { (y, strength) ->
                val frac = y.toFloat() / h
                val t = ((0.92f - frac) / (0.92f - 0.08f)).coerceIn(0f, 1f)
                val bp = Math.pow(10.0, log10(100.0) + t * (log10(10000.0) - log10(100.0))).toInt()
                out.add(Band(idx, frac, strength, bp))
            }
        }
        return out
    }

    /** 亮背景 → 深色菌落；暗背景 → 亮菌落（自动判断） */
    fun countColonies(gray: Array<FloatArray>, minArea: Int = 30, maxArea: Int = 4000): ColonyResult {
        val h = gray.size
        val w = gray[0].size
        val mean = gray.flatMap { it.asIterable() }.average().toFloat()
        val darkBg = mean < 0.45f
        val threshold = if (darkBg) mean * 0.75f else mean * 1.25f
        val mask = Array(h) { y -> BooleanArray(w) { x -> if (darkBg) gray[y][x] > threshold else gray[y][x] < threshold } }
        val visited = Array(h) { BooleanArray(w) }
        val colonies = mutableListOf<Colony>()
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[y][x] || visited[y][x]) continue
            // BFS 连通域
            var area = 0
            var sx = 0L
            var sy = 0L
            val queue = ArrayDeque<IntArray>()
            queue.add(intArrayOf(x, y))
            visited[y][x] = true
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                area++
                sx += p[0]
                sy += p[1]
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p[0] + dx
                    val ny = p[1] + dy
                    if (nx in 0 until w && ny in 0 until h && !visited[ny][nx] && mask[ny][nx]) {
                        visited[ny][nx] = true
                        queue.add(intArrayOf(nx, ny))
                    }
                }
            }
            if (area in minArea..maxArea) colonies.add(Colony((sx / area).toInt(), (sy / area).toInt(), area))
        }
        return ColonyResult(colonies, threshold)
    }

    /** 把识别结果画回位图（预览用） */
    fun overlay(bmp: Bitmap, bands: List<Band>, colonies: List<Colony>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(255, 196, 0) }
        bands.forEach { b ->
            val y = (b.yFraction * out.height)
            canvas.drawLine(0f, y, out.width.toFloat(), y, paint.apply { strokeWidth = 2f })
        }
        val cp = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(127, 231, 214)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        colonies.forEach { c -> canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), 8f, cp) }
        return out
    }
}
