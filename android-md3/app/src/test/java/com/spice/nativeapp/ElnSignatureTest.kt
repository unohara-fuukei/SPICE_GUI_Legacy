package com.spice.nativeapp

import com.spice.nativeapp.ui.canonicalizeElnSignature
import com.spice.nativeapp.ui.digestElnSignature
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ELN 数字签署（对齐 main limsCollaboration.ts canonicalizeElnSignatureSnapshot / digestElnSignatureSnapshot）：
 * 键字母序 JSON + undefined 省略 + SHA-256 lowercase hex。
 */
class ElnSignatureTest {

    @Test
    fun `canonical json keys sorted`() {
        // reason/signer/signedAt 按字母序；无 witness
        val c = canonicalizeElnSignature("ORCID", "2026-11-14T10:00:00", "reason text", null, null)
        assertEquals("""{"reason":"reason text","signedAt":"2026-11-14T10:00:00","signer":"ORCID"}""", c)
    }

    @Test
    fun `witness included when present`() {
        val c = canonicalizeElnSignature("S", "T", "R", "W", "T2")
        assertEquals("""{"reason":"R","signedAt":"T","signer":"S","witness":"W","witnessSignedAt":"T2"}""", c)
    }

    @Test
    fun `blank reason and witness omitted`() {
        val c = canonicalizeElnSignature("S", "T", "  ", "  ", null)
        assertEquals("""{"signedAt":"T","signer":"S"}""", c)
    }

    @Test
    fun `digest is sha256 lowercase hex 64 chars`() {
        val d = digestElnSignature("""{"signedAt":"T","signer":"S"}""")
        assertEquals(64, d.length)
        assertEquals(true, d.all { it in "0123456789abcdef" })
        // 与 WebCrypto SHA-256 一致性基准（预先计算）
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("""{"signedAt":"T","signer":"S"}""".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, d)
    }

    @Test
    fun `signer trimmed as main snapshot trim`() {
        val c = canonicalizeElnSignature("  SPICE Author  ", "T", null, null, null)
        assertEquals("""{"signedAt":"T","signer":"SPICE Author"}""", c)
    }
}
