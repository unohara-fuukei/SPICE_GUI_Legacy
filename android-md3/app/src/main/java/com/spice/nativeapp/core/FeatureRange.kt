package com.spice.nativeapp.core

/**
 * 基因编辑器特征区间（1-based, inclusive）。
 * 对照原版 `src/lib/gene/featureRange.ts`。
 *
 * 环状文档中跨原点的特征以 `end < start` 存储：特征从 `start` 走到序列末尾，
 * 再从序列首部走到 `end`。所有从区间读碱基的代码都必须跟随这个 wrap；
 * 所有向外写出区间的代码都必须把它拆段——没有外部格式接受 start 越过 end 的跨度。
 */
data class FeatureRange(val start: Int, val end: Int)

/** 反向存储的区间即跨原点。 */
fun isWrappedRange(range: FeatureRange): Boolean = range.end < range.start

/** 两端均落在 `1..sequenceLength` 内（整数坐标；Kotlin 类型系统已排除小数）。 */
fun isRangeInBounds(range: FeatureRange, sequenceLength: Int): Boolean =
    range.start >= 1 && range.end >= 1 &&
        range.start <= sequenceLength && range.end <= sequenceLength

/** 区间覆盖的碱基数。 */
fun rangeLength(range: FeatureRange, sequenceLength: Int): Int =
    if (isWrappedRange(range)) sequenceLength - range.start + range.end + 1
    else range.end - range.start + 1

/**
 * 按文档顺序取区间碱基。正向特征为普通切片；wrap 区间在序列末尾之后接续首部。
 */
fun extractRange(sequence: String, range: FeatureRange): String {
    if (!isWrappedRange(range)) {
        return sequence.slice(range.start - 1 until range.end)
    }
    return sequence.slice(range.start - 1 until sequence.length) +
        sequence.slice(0 until range.end)
}

/** 把区间内偏移（0-based）映射回 1-based 文档位置，跨原点取模回绕。 */
fun positionAt(range: FeatureRange, offset: Int, sequenceLength: Int): Int =
    ((((range.start - 1 + offset) % sequenceLength) + sequenceLength) % sequenceLength) + 1

/**
 * 把可能 wrap 的区间拆成一段或两段非 wrap 区间，按文档顺序。
 * 序列化或按段比对时使用。
 */
fun rangeSegments(range: FeatureRange, sequenceLength: Int): List<FeatureRange> =
    if (!isWrappedRange(range)) listOf(range)
    else listOf(FeatureRange(range.start, sequenceLength), FeatureRange(1, range.end))

/** 两区间首个重叠段；无重叠返回 null。 */
fun firstOverlap(a: FeatureRange, b: FeatureRange, sequenceLength: Int): FeatureRange? {
    for (left in rangeSegments(a, sequenceLength)) {
        for (right in rangeSegments(b, sequenceLength)) {
            val start = maxOf(left.start, right.start)
            val end = minOf(left.end, right.end)
            if (start <= end) return FeatureRange(start, end)
        }
    }
    return null
}
