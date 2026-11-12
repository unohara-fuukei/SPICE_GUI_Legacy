package com.spice.nativeapp.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import java.nio.FloatBuffer

data class GBand(
    val x: Float, val y: Float, val width: Float, val height: Float,
    val area: Float, val intensity: Float, var lane: Int
)

data class GLane(val x: Float, val width: Float, val bands: List<Int>)

data class SegmentResult(
    val width: Int, val height: Int,
    val inputWidth: Int, val inputHeight: Int,
    val bands: List<GBand>, val lanes: List<GLane>, val runMs: Long
)

/**
 * GelGenie U-Net 条带分割。预处理/后处理与原版 Rust 后端一一对应：
 * 灰度 /255、对称零填充到 32 的倍数、NCHW float32；亮背景自动反相；
 * 输出两类概率取较大者成掩膜，8 连通域提取条带框，再按 x 重叠归入泳道。
 */
object GelGenie {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionPath: String? = null

    fun isReady(ctx: Context) = ModelStore.status(ctx).ok && ModelStore.status(ctx).shaMatch

    private fun ensureSession(ctx: Context): OrtSession {
        val f = ModelStore.modelFile(ctx)
        val cached = session
        if (cached != null && sessionPath == f.absolutePath) return cached
        synchronized(this) {
            if (session != null && sessionPath == f.absolutePath) return session!!
            val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val s = e.createSession(f.absolutePath, opts)
            session = s
            sessionPath = f.absolutePath
            return s
        }
    }

    fun segment(ctx: Context, gray: ByteArray, w: Int, h: Int, minAreaFrac: Float = 0.00002f): SegmentResult {
        if (w == 0 || h == 0 || gray.size != w * h) error("empty image")
        val start = System.currentTimeMillis()

        val inW = 32 * (w / 32 + 1)
        val inH = 32 * (h / 32 + 1)
        val left = (inW - w) / 2
        val top = (inH - h) / 2
        val invert = isLightBackground(gray)

        val input = FloatArray(inW * inH)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (gray[y * w + x].toInt() and 0xFF) / 255f
            input[(top + y) * inW + left + x] = if (invert) 1f - v else v
        }

        val sess = ensureSession(ctx)
        val inputName = sess.inputNames.first()
        val outputName = sess.outputNames.first()
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, inH.toLong(), inW.toLong()))
        val result = sess.run(mapOf(inputName to inputTensor))
        val outTensor = result.get(outputName) as OnnxTensor
        val shape = (outTensor.getInfo() as ai.onnxruntime.TensorInfo).shape
        val plane = shape[2].toInt() * shape[3].toInt()
        val outW = shape[3].toInt()
        val flat = outTensor.floatBuffer
        val mask = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val idx = (top + y) * outW + left + x
            if (flat[plane + idx] > flat[idx]) mask[y * w + x] = 1
        }
        result.close()

        val minArea = max((w * h * minAreaFrac).toFloat(), 12f)
        val components = connectedComponents(mask, gray, w, h, minArea, invert)
        val kept = components.filterNot { c -> !(c.x0 <= 1 && c.x1 >= w - 2) && !(c.y0 <= 1 && c.y1 >= h - 2) }

        val bands = kept.map { c ->
            GBand(
                x = c.x0 / w.toFloat(), y = c.y0 / h.toFloat(),
                width = (c.x1 - c.x0 + 1) / w.toFloat(), height = (c.y1 - c.y0 + 1) / h.toFloat(),
                area = c.area / (w * h).toFloat(), intensity = c.signalSum / c.area, lane = 0
            )
        }
        val (finalBands, lanes) = assignLanes(bands)
        return SegmentResult(w, h, inW, inH, finalBands, lanes, System.currentTimeMillis() - start)
    }

    private fun isLightBackground(gray: ByteArray): Boolean {
        if (gray.isEmpty()) return false
        val hist = IntArray(256)
        for (g in gray) hist[g.toInt() and 0xFF]++
        var seen = 0
        val middle = gray.size / 2
        for (v in 0 until 256) {
            seen += hist[v]
            if (seen > middle) return v >= 128
        }
        return false
    }

    private data class Component(var x0: Int, var y0: Int, var x1: Int, var y1: Int, val area: Int, val signalSum: Float)

    private fun connectedComponents(
        mask: ByteArray, gray: ByteArray, w: Int, h: Int, minArea: Float, invert: Boolean
    ): List<Component> {
        val seen = BooleanArray(mask.size)
        val out = mutableListOf<Component>()
        val stack = ArrayDeque<Int>()
        for (start0 in mask.indices) {
            if (mask[start0] == 0.toByte() || seen[start0]) continue
            seen[start0] = true
            stack.add(start0)
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            var area = 0; var signalSum = 0f
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val x = idx % w; val y = idx / w
                area++
                x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
                val value = (gray[idx].toInt() and 0xFF) / 255f
                signalSum += if (invert) 1f - value else value
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val nidx = ny * w + nx
                    if (mask[nidx] != 0.toByte() && !seen[nidx]) { seen[nidx] = true; stack.add(nidx) }
                }
            }
            if (area >= minArea) out.add(Component(x0, y0, x1, y1, area, signalSum))
        }
        return out
    }

    private fun sameLane(bands: List<GBand>, group: List<Int>, next: GBand, gap: Float): Boolean {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (i in group) {
            left = min(left, bands[i].x)
            right = max(right, bands[i].x + bands[i].width)
        }
        val overlap = max(0f, min(right, next.x + next.width) - max(left, next.x))
        val narrowest = max(min(right - left, next.width), 1e-6f)
        if (overlap / narrowest >= 0.25f) return true
        val last = bands[group.last()]
        val lastC = last.x + last.width * 0.5f
        val nextC = next.x + next.width * 0.5f
        return abs(nextC - lastC) <= gap
    }

    private fun assignLanes(inputBands: List<GBand>): Pair<List<GBand>, List<GLane>> {
        val mutBands = inputBands.toMutableList()
        if (mutBands.isEmpty()) return emptyList<GBand>() to emptyList()
        mutBands.sortBy { it.x + it.width * 0.5f }
        val gaps = mutBands.zipWithNext { a, b -> (b.x + b.width * 0.5f) - (a.x + a.width * 0.5f) }
            .filter { it > 0f }
        if (gaps.isEmpty()) mutBands[0].lane = 0
        val sortedGaps = gaps.sorted()
        val middle = sortedGaps.size / 2
        val median = if (sortedGaps.size % 2 == 0 && middle < sortedGaps.size)
            (sortedGaps[middle - 1] + sortedGaps[middle]) * 0.5f else sortedGaps[middle.coerceAtMost(sortedGaps.size - 1)]
        val gap = median.takeIf { gaps.isNotEmpty() }?.coerceIn(0.04f, 0.15f) ?: 0.08f

        val groups = mutableListOf<MutableList<Int>>()
        mutBands.forEachIndexed { index, band ->
            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && sameLane(mutBands, lastGroup, band, gap)) lastGroup.add(index)
            else groups.add(mutableListOf(index))
        }
        val lanes = groups.map { group ->
            val left = group.minOf { mutBands[it].x }
            val right = group.maxOf { mutBands[it].x + mutBands[it].width }
            GLane(left, (right - left).coerceAtLeast(0.01f), group)
        }
        lanes.forEachIndexed { li, lane -> lane.bands.forEach { mutBands[it].lane = li } }
        return mutBands.toList() to lanes
    }
}
