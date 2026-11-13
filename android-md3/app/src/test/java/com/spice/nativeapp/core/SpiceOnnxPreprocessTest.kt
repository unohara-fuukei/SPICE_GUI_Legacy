package com.spice.nativeapp.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SpiceOnnx 预处理契约对齐 main src-tauri/src/onnx.rs：
 * seq_to_tokens（1..20=AA，21=unknown）与 normalize_env 边界一致。
 */
class SpiceOnnxPreprocessTest {

    @Test
    fun `tokens map A C W Y to 1 2 20`() {
        // main AA = "ACDEFGHIKLMNPQRSTVWY"：A=1, C=2, W=19, Y=20
        val t = SpiceOnnx.seqToTokens("ACWY")
        assertArrayEquals(longArrayOf(1, 2, 19, 20), t)
    }

    @Test
    fun `unknown and lowercase map to 21`() {
        // main: 非 AA 一律 21；安卓复刻时先按 main 语义（原版对输入执行 clean 后全大写，
        // 但 tokenizer 本身直接 find，未命中即 21）
        val t = SpiceOnnx.seqToTokens("AGXZ")
        assertEquals(21L, t[2])
        assertEquals(21L, t[3])
    }

    @Test
    fun `env normalization clamps and matches main`() {
        // main normalize_env: ph/14, (T-150)/250, log10(ionic)/log10(1e-3)
        val e = SpiceOnnx.normalizeEnv(7.0, 310.0, 0.15)
        assertEquals(0.5f, e[0], 1e-6f)
        assertEquals(((310.0 - 150.0) / 250.0).toFloat(), e[1], 1e-6f)
        val expectedI = (Math.log10(0.15) / Math.log10(1e-3)).toFloat()
        assertEquals(expectedI, e[2], 1e-6f)
    }

    @Test
    fun `env normalization clamps out of range`() {
        val e = SpiceOnnx.normalizeEnv(-5.0, 100.0, 5.0)
        assertEquals(0f, e[0], 1e-6f)   // ph<0 → clamp 0
        assertEquals(0f, e[1], 1e-6f)   // tempK=100 → (100-150)/250<0 → clamp 0
        assertEquals(0f, e[2], 1e-6f)   // main onnx.rs:29 ionic clamp(1e-3,1.0)→1.0，log10(1)/log10(1e-3)=0 → clamp 0
    }
}
