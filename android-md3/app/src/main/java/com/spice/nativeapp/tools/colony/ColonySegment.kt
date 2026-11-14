package com.spice.nativeapp.tools.colony

/*
 * 菌落平板二值化分割，1:1 移植 main 的 src/lib/tools/colony/segment.ts。
 * 流水线：高斯模糊 → Otsu（或手动）阈值 → 连通域 → 可选距离变换种子 +
 * 标记控制的分水岭式分离 → 面积/圆度过滤。
 * 全部为纯类型数组代码，可在降采样检测平面上运行，不触碰 UI。
 * Float32Array → FloatArray，Int32Array → IntArray，Uint8Array → ByteArray。
 */
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val RESOLVED_DARK = "dark"
const val RESOLVED_BRIGHT = "bright"

data class SegmentRequest(
    /** 固定阈值（0..255 灰度级），null 表示用 Otsu。 */
    val threshold: Int?,
    val polarity: ColonyPolarity,
    /** 高斯半径（检测像素）。 */
    val blurRadius: Double,
    /** 面积以检测像素平方计。 */
    val minArea: Double,
    val maxArea: Double,
    val minCircularity: Double,
    val splitTouching: Boolean,
)

data class RawColony(
    val x: Double,
    val y: Double,
    val area: Double,
    val radius: Double,
    val meanIntensity: Double,
    val circularity: Double,
)

data class SegmentResult(
    val colonies: List<RawColony>,
    val mask: ByteArray,
    val width: Int,
    val height: Int,
    val otsu: Int,
    val threshold: Int,
    val background: Int,
    val polarity: String,   // ResolvedPolarity
)

// ------------------------------------------------------------------- blur

private fun gaussianKernel(radius: Double): DoubleArray {
    val sigma = max(0.5, radius / 2)
    val half = max(1, ceil(sigma * 2.5).toInt())
    val kernel = DoubleArray(half * 2 + 1)
    var sum = 0.0
    for (i in -half..half) {
        val v = exp(-(i * i) / (2 * sigma * sigma))
        kernel[i + half] = v
        sum += v
    }
    for (i in kernel.indices) kernel[i] /= sum
    return kernel
}

/** 可分离高斯模糊，边缘钳位。半径 <= 0.05 时原样返回。 */
fun gaussianBlur(gray: DoubleArray, width: Int, height: Int, radius: Double): DoubleArray {
    if (!(radius > 0.05)) return gray
    val kernel = gaussianKernel(radius)
    val half = (kernel.size - 1) / 2
    val temp = DoubleArray(gray.size)
    val out = DoubleArray(gray.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            var acc = 0.0
            for (k in -half..half) {
                val sx = if (x + k < 0) 0 else if (x + k >= width) width - 1 else x + k
                acc += gray[row + sx] * kernel[k + half]
            }
            temp[row + x] = acc
        }
    }
    for (y in 0 until height) {
        for (x in 0 until width) {
            var acc = 0.0
            for (k in -half..half) {
                val sy = if (y + k < 0) 0 else if (y + k >= height) height - 1 else y + k
                acc += temp[sy * width + x] * kernel[k + half]
            }
            out[y * width + x] = acc
        }
    }
    return out
}

// ------------------------------------------------------------------- otsu

fun otsuThreshold(gray: DoubleArray): Int {
    val hist = DoubleArray(256)
    for (v0 in gray) {
        var v = v0.roundToInt()
        if (v < 0) v = 0 else if (v > 255) v = 255
        hist[v]++
    }
    val total = gray.size
    if (total == 0) return 128
    var sum = 0.0
    for (i in 0 until 256) sum += i * hist[i]
    var sumB = 0.0
    var weightB = 0.0
    var best = -1.0
    var threshold = 128
    for (t in 0 until 256) {
        weightB += hist[t]
        if (weightB == 0.0) continue
        val weightF = total - weightB
        if (weightF == 0.0) break
        sumB += t * hist[t]
        val meanB = sumB / weightB
        val meanF = (sum - sumB) / weightF
        val between = weightB * weightF * (meanB - meanF) * (meanB - meanF)
        if (between > best) {
            best = between
            threshold = t
        }
    }
    return threshold
}

/** 边缘灰度带的中位灰度，用于推断哪一侧是背景。 */
fun estimateBackground(gray: DoubleArray, width: Int, height: Int): Int {
    val band = max(2, (min(width, height) * 0.03).roundToInt())
    val hist = DoubleArray(256)
    fun sample(x: Int, y: Int) {
        var v = gray[y * width + x].roundToInt()
        if (v < 0) v = 0 else if (v > 255) v = 255
        hist[v]++
    }
    for (y in 0 until height) {
        val edgeY = y < band || y >= height - band
        for (x in 0 until width) {
            if (edgeY || x < band || x >= width - band) sample(x, y)
        }
    }
    var total = 0.0
    for (h in hist) total += h
    if (total == 0.0) return 128
    var acc = 0.0
    for (i in 0 until 256) {
        acc += hist[i]
        if (acc >= total / 2) return i
    }
    return 128
}

fun resolvePolarity(polarity: ColonyPolarity, background: Int, threshold: Int): String {
    if (polarity == POLARITY_DARK) return RESOLVED_DARK
    if (polarity == POLARITY_BRIGHT) return RESOLVED_BRIGHT
    // 菌落通常是少数类；平板背景接触图像边缘。若边缘亮于阈值，则菌落在暗侧。
    return if (background >= threshold) RESOLVED_DARK else RESOLVED_BRIGHT
}

fun buildMask(gray: DoubleArray, threshold: Int, polarity: String): ByteArray {
    val mask = ByteArray(gray.size)
    if (polarity == RESOLVED_DARK) {
        for (i in gray.indices) mask[i] = if (gray[i] <= threshold) 1 else 0
    } else {
        for (i in gray.indices) mask[i] = if (gray[i] >= threshold) 1 else 0
    }
    return mask
}

// ------------------------------------------------------- connected components

private class Component(
    val area: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val sumX: Int,
    val sumY: Int,
    val sumIntensity: Double,
    val circularity: Double,
    val pixels: IntArray,
)

private fun componentCircularity(area: Double, perimeter: Double): Double {
    if (area <= 0 || perimeter <= 0) return 0.0
    val value = (4 * PI * area) / (perimeter * perimeter)
    return min(1.0, max(0.0, value))
}

private fun collectComponents(
    mask: ByteArray,
    gray: DoubleArray,
    width: Int,
    height: Int,
): List<Component> {
    val labels = IntArray(width * height)
    val stack = IntArray(width * height)
    val order = IntArray(width * height)
    val components = mutableListOf<Component>()
    var nextId = 0
    for (seed in mask.indices) {
        if (mask[seed] == 0.toByte() || labels[seed] != 0) continue
        nextId++
        var sp = 0
        var count = 0
        stack[sp++] = seed
        labels[seed] = nextId
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var sumX = 0
        var sumY = 0
        var sumIntensity = 0.0
        while (sp > 0) {
            val p = stack[--sp]
            order[count++] = p
            val x = p % width
            val y = (p - x) / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sumX += x
            sumY += y
            sumIntensity += gray[p]
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= width) continue
                    val ni = ny * width + nx
                    if (mask[ni] != 0.toByte() && labels[ni] == 0) {
                        labels[ni] = nextId
                        stack[sp++] = ni
                    }
                }
            }
        }
        val pixels = order.copyOf(count)
        var perimeter = 0.0
        for (p in pixels) {
            val x = p % width
            val y = (p - x) / width
            if (x == 0 || mask[p - 1] == 0.toByte()) perimeter++
            if (x == width - 1 || mask[p + 1] == 0.toByte()) perimeter++
            if (y == 0 || mask[p - width] == 0.toByte()) perimeter++
            if (y == height - 1 || mask[p + width] == 0.toByte()) perimeter++
        }
        components.add(
            Component(
                area = count, minX = minX, minY = minY, maxX = maxX, maxY = maxY,
                sumX = sumX, sumY = sumY, sumIntensity = sumIntensity,
                circularity = componentCircularity(count.toDouble(), perimeter),
                pixels = pixels,
            )
        )
    }
    return components
}

// ----------------------------------------------------- distance transform

/** 1-D 平方欧氏距离变换（Felzenszwalb & Huttenlocher）。 */
private fun edt1d(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
    var k = 0
    v[0] = 0
    z[0] = Double.NEGATIVE_INFINITY
    z[1] = Double.POSITIVE_INFINITY
    for (q in 1 until n) {
        var s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k])
        while (s <= z[k]) {
            k--
            s = (f[q] + q * q - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k])
        }
        k++
        v[k] = q
        z[k] = s
        z[k + 1] = Double.POSITIVE_INFINITY
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dq = (q - v[k]).toDouble()
        d[q] = dq * dq + f[v[k]]
    }
}

/** 二值平面（0 = 背景）的精确欧氏距离变换。 */
fun distanceTransform(bin: ByteArray, width: Int, height: Int): DoubleArray {
    val grid = DoubleArray(width * height)
    val INF = 1e12
    for (i in bin.indices) grid[i] = if (bin[i] != 0.toByte()) INF else 0.0
    val maxN = max(width, height)
    val f = DoubleArray(maxN)
    val d = DoubleArray(maxN)
    val z = DoubleArray(maxN + 1)
    val v = IntArray(maxN)
    for (x in 0 until width) {
        for (y in 0 until height) f[y] = grid[y * width + x]
        edt1d(f, height, d, v, z)
        for (y in 0 until height) grid[y * width + x] = d[y]
    }
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) f[x] = grid[row + x]
        edt1d(f, width, d, v, z)
        for (x in 0 until width) grid[row + x] = d[x]
    }
    val out = DoubleArray(width * height)
    for (i in out.indices) out[i] = sqrt(grid[i])
    return out
}

private class Seed(val x: Int, val y: Int, val distance: Double)

/** 距离变换的局部极大值，非极大值抑制细化。 */
private fun findSeeds(dt: DoubleArray, bin: ByteArray, width: Int, height: Int): List<Seed> {
    var maxDistance = 0.0
    for (i in dt.indices) {
        if (bin[i] != 0.toByte() && dt[i] > maxDistance) maxDistance = dt[i]
    }
    if (maxDistance <= 0) return emptyList()
    val minSeed = max(1.5, maxDistance * 0.35)
    val candidates = mutableListOf<Seed>()
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val i = y * width + x
            if (bin[i] == 0.toByte()) continue
            val dd = dt[i]
            if (dd < minSeed) continue
            var localMax = true
            var dy = -1
            while (dy <= 1 && localMax) {
                var dx = -1
                while (dx <= 1) {
                    if (dx == 0 && dy == 0) { dx++; continue }
                    if (dt[i + dy * width + dx] > dd) {
                        localMax = false
                        break
                    }
                    dx++
                }
                dy++
            }
            if (localMax) candidates.add(Seed(x, y, dd))
        }
    }
    candidates.sortWith(compareByDescending<Seed> { it.distance }.thenBy { it.y }.thenBy { it.x })
    // 两个相贴菌瓣的中心大约相距两个半径，因此一个半径的间距能同时保留两者，
    // 并坍缩单个斑块的脊台。
    val suppress = max(2.5, maxDistance)
    val kept = mutableListOf<Seed>()
    for (candidate in candidates) {
        var tooClose = false
        for (seed in kept) {
            val dx = (seed.x - candidate.x).toDouble()
            val dy = (seed.y - candidate.y).toDouble()
            if (dx * dx + dy * dy < suppress * suppress) {
                tooClose = true
                break
            }
        }
        if (!tooClose) kept.add(candidate)
    }
    if (kept.size < 2) return kept
    // 拒绝单个细长目标的平坦距离脊：两个真正的瓣之间必有明显低于两峰的鞍部。
    val separated = mutableSetOf<Int>()
    for (i in kept.indices) {
        for (j in i + 1 until kept.size) {
            val a = kept[i]
            val b = kept[j]
            val distance = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            val steps = max(2, ceil(distance).toInt())
            var saddle = Double.POSITIVE_INFINITY
            var insideObject = true
            for (step in 0..steps) {
                val t = step.toDouble() / steps
                val sx = (a.x + (b.x - a.x) * t).roundToInt()
                val sy = (a.y + (b.y - a.y) * t).roundToInt()
                val index = sy * width + sx
                if (bin[index] == 0.toByte()) {
                    insideObject = false
                    break
                }
                val value = dt[index]
                if (value < saddle) saddle = value
            }
            if (insideObject && saddle < 0.85 * min(a.distance, b.distance)) {
                separated.add(i)
                separated.add(j)
            }
        }
    }
    return kept.filterIndexed { index, _ -> separated.contains(index) }
}

/** 把一个连通域分成分水岭式子区域；不值得分离时返回 null。 */
private fun splitComponent(
    component: Component,
    gray: DoubleArray,
    width: Int,
    height: Int,
): List<RawColony>? {
    val bw = component.maxX - component.minX + 1
    val bh = component.maxY - component.minY + 1
    if (bw < 5 || bh < 5) return null
    // 一像素宽的零边框：接触包围盒的目标在距离变换中仍能看到外部边界。
    val pw = bw + 2
    val ph = bh + 2
    val bin = ByteArray(pw * ph)
    for (p in component.pixels) {
        val x = p % width
        val y = (p - x) / width
        bin[(y - component.minY + 1) * pw + (x - component.minX + 1)] = 1
    }
    // 环形目标（或包裹另一个菌落的菌落）没有有意义的颈部，保持整体。
    val visited = ByteArray(pw * ph)
    val flood = IntArray(pw * ph)
    var floodHead = 0
    var floodTail = 0
    for (x in 0 until pw) {
        flood[floodTail++] = x
        flood[floodTail++] = (ph - 1) * pw + x
        visited[x] = 1
        visited[(ph - 1) * pw + x] = 1
    }
    for (y in 1 until ph - 1) {
        flood[floodTail++] = y * pw
        flood[floodTail++] = y * pw + pw - 1
        visited[y * pw] = 1
        visited[y * pw + pw - 1] = 1
    }
    while (floodHead < floodTail) {
        val p = flood[floodHead++]
        val x = p % pw
        val y = (p - x) / pw
        for (k in 0 until 4) {
            val nx = x + (if (k == 0) -1 else if (k == 1) 1 else 0)
            val ny = y + (if (k == 2) -1 else if (k == 3) 1 else 0)
            if (nx < 0 || ny < 0 || nx >= pw || ny >= ph) continue
            val ni = ny * pw + nx
            if (bin[ni] == 0.toByte() && visited[ni] == 0.toByte()) {
                visited[ni] = 1
                flood[floodTail++] = ni
            }
        }
    }
    for (y in 1 until ph - 1) {
        for (x in 1 until pw - 1) {
            val i = y * pw + x
            if (bin[i] == 0.toByte() && visited[i] == 0.toByte()) return null // 封闭孔洞
        }
    }

    val dt = distanceTransform(bin, pw, ph)
    // 轻度 3x3 平滑，减少锯齿菌落边缘的伪极大值。
    val smooth = DoubleArray(pw * ph)
    for (y in 1 until ph - 1) {
        for (x in 1 until pw - 1) {
            val i = y * pw + x
            if (bin[i] == 0.toByte()) continue
            var sum = 0.0
            for (dy in -1..1) {
                for (dx in -1..1) sum += dt[i + dy * pw + dx]
            }
            smooth[i] = sum / 9
        }
    }
    val seeds = findSeeds(smooth, bin, pw, ph)
    if (seeds.size < 2) return null

    // 标记控制分水岭：每个前景像素归入经目标内部（4 邻域 BFS）最近的种子，
    // 使分割沿相贴菌落之间的窄颈部。
    val assign = IntArray(pw * ph) { -1 }
    val queue = IntArray(pw * ph)
    var head = 0
    var tail = 0
    for (s in seeds.indices) {
        val idx = seeds[s].y * pw + seeds[s].x
        if (assign[idx] >= 0) continue
        assign[idx] = s
        queue[tail++] = idx
    }
    while (head < tail) {
        val p = queue[head++]
        val label = assign[p]
        val x = p % pw
        val y = (p - x) / pw
        if (x > 1 && bin[p - 1] != 0.toByte() && assign[p - 1] < 0) {
            assign[p - 1] = label; queue[tail++] = p - 1
        }
        if (x < pw - 2 && bin[p + 1] != 0.toByte() && assign[p + 1] < 0) {
            assign[p + 1] = label; queue[tail++] = p + 1
        }
        if (y > 1 && bin[p - pw] != 0.toByte() && assign[p - pw] < 0) {
            assign[p - pw] = label; queue[tail++] = p - pw
        }
        if (y < ph - 2 && bin[p + pw] != 0.toByte() && assign[p + pw] < 0) {
            assign[p + pw] = label; queue[tail++] = p + pw
        }
    }

    val regions = mutableListOf<RawColony>()
    val area = DoubleArray(seeds.size)
    val sx = DoubleArray(seeds.size)
    val sy = DoubleArray(seeds.size)
    val si = DoubleArray(seeds.size)
    val peri = DoubleArray(seeds.size)
    for (p in component.pixels) {
        val x = p % width
        val y = (p - x) / width
        val localX = x - component.minX + 1
        val localY = y - component.minY + 1
        val local = localY * pw + localX
        val r = assign[local]
        if (r < 0) {
            // 未分配像素只可能出现在种子被覆盖时；按直接距离归入最近种子，
            // 面积不丢失。
            var best = 0
            var bestD = Double.POSITIVE_INFINITY
            for (s in seeds.indices) {
                val dx = (seeds[s].x - localX).toDouble()
                val dy = (seeds[s].y - localY).toDouble()
                val dd = dx * dx + dy * dy
                if (dd < bestD) {
                    bestD = dd
                    best = s
                }
            }
            area[best]++
            sx[best] += x
            sy[best] += y
            si[best] += gray[p]
            continue
        }
        area[r]++
        sx[r] += x
        sy[r] += y
        si[r] += gray[p]
        if (bin[local - 1] == 0.toByte() || assign[local - 1] != r) peri[r]++
        if (bin[local + 1] == 0.toByte() || assign[local + 1] != r) peri[r]++
        if (bin[local - pw] == 0.toByte() || assign[local - pw] != r) peri[r]++
        if (bin[local + pw] == 0.toByte() || assign[local + pw] != r) peri[r]++
    }
    for (r in seeds.indices) {
        if (area[r] <= 0.0) continue
        regions.add(
            RawColony(
                x = sx[r] / area[r] + 0.5,
                y = sy[r] / area[r] + 0.5,
                area = area[r],
                radius = sqrt(area[r] / PI),
                meanIntensity = si[r] / area[r],
                circularity = componentCircularity(area[r], peri[r]),
            )
        )
    }
    return if (regions.size >= 2) regions else null
}

/** 检测分辨率灰度平面上的完整分割一遍。 */
fun segmentGray(gray: DoubleArray, width: Int, height: Int, request: SegmentRequest): SegmentResult {
    val blurred = gaussianBlur(gray, width, height, request.blurRadius)
    val otsu = otsuThreshold(blurred)
    val threshold = request.threshold?.let { min(255, max(0, it)) } ?: otsu
    val background = estimateBackground(blurred, width, height)
    val polarity = resolvePolarity(request.polarity, background, threshold)
    val mask = buildMask(blurred, threshold, polarity)
    val components = collectComponents(mask, blurred, width, height)
    val colonies = mutableListOf<RawColony>()
    val minArea = max(0.0, request.minArea)
    val maxArea = max(0.0, request.maxArea)

    fun accept(colony: RawColony): Boolean {
        if (colony.area < max(1.0, minArea)) return false
        if (maxArea > 0 && colony.area > maxArea) return false
        if (request.minCircularity > 0 && colony.circularity < request.minCircularity) return false
        return true
    }

    for (component in components) {
        if (component.area < max(1.0, minArea)) continue
        if (request.splitTouching && component.area >= max(12.0, minArea * 1.5)) {
            val split = splitComponent(component, blurred, width, height)
            if (split != null) {
                for (colony in split) if (accept(colony)) colonies.add(colony)
                continue
            }
        }
        val colony = RawColony(
            x = component.sumX.toDouble() / component.area + 0.5,
            y = component.sumY.toDouble() / component.area + 0.5,
            area = component.area.toDouble(),
            radius = sqrt(component.area / PI),
            meanIntensity = component.sumIntensity / component.area,
            circularity = component.circularity,
        )
        if (accept(colony)) colonies.add(colony)
    }
    return SegmentResult(
        colonies = colonies, mask = mask, width = width, height = height,
        otsu = otsu, threshold = threshold, background = background, polarity = polarity,
    )
}
