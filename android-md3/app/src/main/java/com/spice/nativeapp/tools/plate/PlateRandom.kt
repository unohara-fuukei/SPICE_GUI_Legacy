package com.spice.nativeapp.tools.plate

// Deterministic pseudo-random helpers for reproducible plate layouts.
// 1:1 port of src/lib/tools/plate/random.ts. mulberry32 is a tiny 32-bit
// generator with a full period and stable output across platforms, which
// matters when a layout must be recreated from a seed. All arithmetic uses
// UInt to mirror JS's `>>> 0` normalization and Math.imul wrap semantics.

fun mulberry32(seed: Int): () -> Double {
    var state = seed.toUInt()
    return {
        state += 0x6d2b79f5u
        var value = state
        value = (value xor (value shr 15)) * (value or 1u)
        value = value xor (value + ((value xor (value shr 7)) * (value or 61u)))
        (value xor (value shr 14)).toDouble() / 4294967296.0
    }
}

/** In-place Fisher-Yates shuffle driven by the supplied generator. */
fun <T> shuffleInPlace(items: MutableList<T>, random: () -> Double): MutableList<T> {
    for (i in items.size - 1 downTo 1) {
        val j = Math.floor(random() * (i + 1)).toInt().coerceAtMost(i)
        val tmp = items[i]
        items[i] = items[j]
        items[j] = tmp
    }
    return items
}

fun <T> shuffled(items: List<T>, random: () -> Double): List<T> =
    shuffleInPlace(items.toMutableList(), random)

private val secureRandom = java.security.SecureRandom()

fun randomSeedValue(): Int =
    // JS: crypto.getRandomValues(Uint32Array(1)) % 2147483647 (always >= 0).
    ((secureRandom.nextInt().toLong() and 0xffffffffL) % 2147483647L).toInt()
