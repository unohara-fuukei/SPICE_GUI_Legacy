package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI endpoint 归一化与模型地址推导（main 侧 404 "Invalid URL (POST /v1)" bug 的回归测试） */
class AiClientTest {

    @Test
    fun `base URL with v1 and trailing slash gets chat completions appended`() {
        // 用户 bug 现场：main 直接 POST 该值 → 404 Invalid URL (POST /v1)
        assertEquals(
            "https://apihub.agnes-ai.com/v1/chat/completions",
            AiClient.normalizeChatUrl("https://apihub.agnes-ai.com/v1/")
        )
    }

    @Test
    fun `bare base URL gets chat completions appended`() {
        assertEquals(
            "https://apihub.agnes-ai.com/chat/completions",
            AiClient.normalizeChatUrl("https://apihub.agnes-ai.com")
        )
    }

    @Test
    fun `full chat completions URL is kept as-is`() {
        val url = "https://api.openai.com/v1/chat/completions"
        assertEquals(url, AiClient.normalizeChatUrl(url))
    }

    @Test
    fun `trailing slash on full URL is trimmed`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiClient.normalizeChatUrl("https://api.openai.com/v1/chat/completions/")
        )
    }

    @Test
    fun `local ollama default endpoint is kept`() {
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            AiClient.normalizeChatUrl("http://localhost:11434/v1/chat/completions")
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            AiClient.normalizeChatUrl("http://localhost:11434/v1/")
        )
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals(
            "https://apihub.agnes-ai.com/v1/chat/completions",
            AiClient.normalizeChatUrl("  https://apihub.agnes-ai.com/v1/  ")
        )
    }

    @Test
    fun `empty endpoint is rejected`() {
        var threw = false
        try { AiClient.normalizeChatUrl("   ") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `models URL derives from base URL`() {
        assertEquals(
            "https://apihub.agnes-ai.com/v1/models",
            AiClient.deriveModelsUrl("https://apihub.agnes-ai.com/v1/")
        )
    }

    @Test
    fun `models URL replaces chat completions path`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            AiClient.deriveModelsUrl("https://api.openai.com/v1/chat/completions")
        )
    }

    @Test
    fun `case insensitive chat completions suffix is kept as-is`() {
        // 已显式给出完整路径时保留用户原样（大小写不敏感识别）
        assertEquals(
            "https://api.example.com/v1/Chat/Completions",
            AiClient.normalizeChatUrl("https://api.example.com/v1/Chat/Completions")
        )
    }
}
