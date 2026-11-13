package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容对话传输层（对齐 main 分支 src/lib/ai/client.ts）。
 *
 * 关键修复（main 侧 bug）：endpoint 归一化——main 直接 POST 用户填写的 endpoint，
 * 用户填 base URL（如 https://apihub.agnes-ai.com/v1/）时请求打到 /v1 报 404
 * "Invalid URL (POST /v1)"。本实现自动补全 /chat/completions。
 */
object AiClient {

    /** 错误分类与 main 的 AiErrorKind 一致 */
    enum class Kind { AUTH, RATE_LIMIT, SERVER, NETWORK, TIMEOUT, HTTP, PARSE }

    class AiRequestException(val kind: Kind, message: String, val status: Int? = null, val retryAfterMs: Long? = null) :
        Exception(message)

    data class Message(val role: String, val content: String)

    data class Config(
        val endpoint: String,
        val model: String,
        val apiKey: String = "",
        val temperature: Double = 0.7,
        val maxTokens: Int = 0   // ≤0 表示不传 max_tokens
    )

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * 归一化 chat completions 地址（bug 修复核心）：
     * - 已以 /chat/completions 结尾：原样保留（仅去尾斜杠）
     * - 其余（base URL、含或不含 /v1）：自动补 /chat/completions
     *   https://apihub.agnes-ai.com/v1/ → https://apihub.agnes-ai.com/v1/chat/completions
     */
    fun normalizeChatUrl(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        require(clean.isNotEmpty()) { "AI endpoint is empty" }
        return if (clean.endsWith("/chat/completions", ignoreCase = true)) clean
        else "$clean/chat/completions"
    }

    /** 由 chat 地址推导同源 models 地址（对齐 main deriveModelsUrl，基于归一化前的原值） */
    fun deriveModelsUrl(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions", ignoreCase = true))
            clean.replaceFirst(Regex("(?i)/chat/completions$"), "/models")
        else "$clean/models"
    }

    private fun headers(config: Config): Map<String, String> {
        val h = mutableMapOf("Content-Type" to "application/json", "Accept" to "application/json")
        val key = config.apiKey.trim()
        if (key.isNotEmpty()) h["Authorization"] = "Bearer $key"
        return h
    }

    private fun errorBodyText(conn: HttpURLConnection): String = runCatching {
        val stream = conn.errorStream ?: conn.inputStream
        stream.use { it.readBytes().decodeToString() }.take(240)
    }.getOrDefault("")

    private fun responseError(status: Int, body: String, retryAfterMs: Long? = null): AiRequestException = when {
        status == 401 || status == 403 -> AiRequestException(Kind.AUTH, "authentication failed ($status): $body", status)
        status == 429 -> AiRequestException(Kind.RATE_LIMIT, "rate limited (429): $body", 429, retryAfterMs)
        status >= 500 -> AiRequestException(Kind.SERVER, "server error ($status): $body", status)
        else -> AiRequestException(Kind.HTTP, "request failed ($status): $body", status)
    }

    /** Retry-After 秒数转毫秒（对齐 main retryAfterMs） */
    private fun retryAfterMs(conn: HttpURLConnection): Long? {
        val raw = conn.getHeaderField("Retry-After") ?: return null
        return raw.toLongOrNull()?.let { sec -> (sec * 1000).coerceIn(0, 30_000) }
    }

    /** 非流式 chat completion，返回 assistant 文本（对齐 main requestChat） */
    fun requestChat(config: Config, messages: List<Message>, ping: Boolean = false): String {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
            put("temperature", config.temperature)
            put("stream", false)
            if (ping) put("max_tokens", 1)
            else if (config.maxTokens > 0) put("max_tokens", config.maxTokens)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(normalizeChatUrl(config.endpoint)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                headers(config).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            if (status !in 200..299) throw responseError(status, errorBodyText(conn), retryAfterMs(conn))
            val text = conn.inputStream.use { it.readBytes().decodeToString() }
            val payload = try { JSONObject(text) } catch (_: Exception) { throw AiRequestException(Kind.PARSE, "AI endpoint returned non-JSON") }
            val content = payload.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: ""
            if (content.isBlank()) throw AiRequestException(Kind.PARSE, "AI endpoint returned an empty or malformed completion")
            content
        } catch (e: AiRequestException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw AiRequestException(Kind.TIMEOUT, "request timed out after ${READ_TIMEOUT_MS} ms")
        } catch (e: IOException) {
            throw AiRequestException(Kind.NETWORK, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    /** 列出 OpenAI 兼容端点的模型 ID（对齐 main listModels） */
    fun listModels(config: Config): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(deriveModelsUrl(config.endpoint)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 15_000
                headers(config).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val status = conn.responseCode
            if (status !in 200..299) throw responseError(status, errorBodyText(conn), retryAfterMs(conn))
            val payload = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
            val data = payload.optJSONArray("data") ?: throw AiRequestException(Kind.PARSE, "models endpoint returned an unexpected shape")
            (0 until data.length()).mapNotNull { i ->
                val e = data.optJSONObject(i)
                if (e != null) e.optString("id").ifBlank { null } else data.optString(i).ifBlank { null }
            }.distinct()
        } catch (e: AiRequestException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw AiRequestException(Kind.TIMEOUT, "request timed out")
        } catch (e: IOException) {
            throw AiRequestException(Kind.NETWORK, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    /** 连接测试（对齐 main testConnection）：/models 失败且 404/405 时回退一次 1-token chat ping */
    data class ConnectionResult(val ok: Boolean, val models: List<String>, val error: String?)

    fun testConnection(config: Config): ConnectionResult = try {
        ConnectionResult(true, listModels(config), null)
    } catch (e: AiRequestException) {
        if (e.kind == Kind.HTTP && (e.status == 404 || e.status == 405)) {
            try {
                requestChat(config.copy(temperature = 0.0), listOf(Message("user", "ping")), ping = true)
                ConnectionResult(true, emptyList(), null)
            } catch (e2: AiRequestException) {
                ConnectionResult(false, emptyList(), e2.message)
            }
        } else {
            ConnectionResult(false, emptyList(), e.message)
        }
    }
}
