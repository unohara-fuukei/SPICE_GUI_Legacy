package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.util.TreeMap

/**
 * SSE 流式 chat completion（1:1 移植 main src/lib/ai/client.ts streamChatCompletion）。
 *
 * - SSE（text/event-stream）与 NDJSON 逐行解析；`data:` 前缀、`[DONE]`、`:keep-alive` 行、
 *   非增量的整段 JSON 响应（服务端忽略 stream:true）均按 main 语义处理。
 * - 流式 tool_calls 按 index 合并（id/名称/参数增量拼接），非流式 message.tool_calls 整组替换。
 * - onToken(delta, full) 与 main 一致：每个文本增量回调一次，携带累计全文。
 * - 读取超时按"无活动"计（HttpURLConnection readTimeout 即逐次 read 的不活跃超时，
 *   与 main 每次 chunk 后 re-arm 的不活跃计时语义一致）。
 */
object AiStream {

    /** OpenAI function 调用（main AiToolCall） */
    data class ToolCall(val id: String, val type: String, val name: String, val arguments: String)

    /** OpenAI function 定义（main AiToolDefinition） */
    data class ToolDefinition(val name: String, val description: String, val parameters: JSONObject)

    /** 对话消息：比 AiClient.Message 多 tool_calls / tool_call_id（main AiChatMessage） */
    data class ChatMessage(
        val role: String,                       // system | user | assistant | tool
        val content: String,
        val toolCalls: List<ToolCall>? = null,  // assistant 消息携带的调用请求
        val toolCallId: String? = null,         // tool 结果消息对应的调用 id
        val name: String? = null               // tool 结果消息的工具名
    )

    data class StreamResult(val content: String, val toolCalls: List<ToolCall>, val finishReason: String?)

    class CancelledException : Exception("request cancelled")

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    // ───────────────────────── 可测试的行/负载解析器（main consumeLine/consumePayload） ─────────────────────────

    /**
     * 增量累积器：喂入每一行，产出文本 delta 与合并后的 tool_calls。
     * 纯解析逻辑，无 IO，供单测与流式读取循环共用。
     */
    internal class Accumulator(private val onToken: ((delta: String, full: String) -> Unit)?) {
        private val full = StringBuilder()
        private val toolCalls = TreeMap<Int, Pair<ToolCall, StringBuilder>>() // index → (call, argsBuf)
        var finishReason: String? = null
            private set

        /** 处理一行；返回 false 表示终止（[DONE]） */
        fun consumeLine(lineRaw: String): Boolean {
            val clean = lineRaw.trimEnd('\r')
            val trimmed = clean.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(":")) return true
            val data = if (clean.startsWith("data:")) clean.removePrefix("data:").trim() else trimmed
            if (data.isEmpty()) return true
            if (data == "[DONE]") return false
            return try {
                consumePayload(JSONObject(data))
            } catch (e: AiClient.AiRequestException) {
                throw e
            } catch (_: Exception) {
                // 供应商可能夹带 keep-alive 或非 JSON 行；忽略（main 同语义）
                true
            }
        }

        /** 处理一个完整 JSON 负载；错误对象抛 AiRequestException(SERVER) */
        fun consumePayload(payload: JSONObject): Boolean {
            if (payload.has("error")) {
                val err = payload.opt("error")
                val detail = when (err) {
                    is String -> err
                    is JSONObject -> err.optString("message", err.toString())
                    else -> err.toString()
                }
                throw AiClient.AiRequestException(AiClient.Kind.SERVER, "AI stream error: $detail")
            }
            val choice = payload.optJSONArray("choices")?.optJSONObject(0)
            if (choice != null) {
                choice.opt("finish_reason")?.let { if (it != JSONObject.NULL && it.toString() != "null") finishReason = it.toString() }
                val delta = choice.optJSONObject("delta")
                val message = choice.optJSONObject("message")
                val content = (delta?.opt("content") ?: message?.opt("content") ?: choice.opt("text")) as? String
                if (content != null && content.isNotEmpty()) {
                    full.append(content)
                    onToken?.invoke(content, full.toString())
                }
                (delta?.optJSONArray("tool_calls") ?: message?.optJSONArray("tool_calls"))?.let { raw ->
                    if (raw !== message?.optJSONArray("tool_calls") || delta == null) mergeToolCalls(raw)
                }
                // 非流式 message.tool_calls 整组替换（main mergeMessageToolCalls）
                message?.optJSONArray("tool_calls")?.let { raw ->
                    val parsed = parseToolCalls(raw)
                    if (parsed.isNotEmpty()) {
                        toolCalls.clear()
                        parsed.forEachIndexed { i, c -> toolCalls[i] = c to StringBuilder() }
                    }
                }
            }
            return true
        }

        /** 流式 tool_calls 增量合并（main mergeStreamingToolCalls：名称/参数按 index 累加） */
        private fun mergeToolCalls(raw: JSONArray) {
            for (i in 0 until raw.length()) {
                val entry = raw.optJSONObject(i) ?: continue
                val index = when (val v = entry.opt("index")) {
                    is Int -> v
                    is Long -> v.toInt()
                    is Number -> v.toInt()
                    else -> i
                }
                var slot = toolCalls[index]
                if (slot == null) {
                    slot = ToolCall("", "function", "", "") to StringBuilder()
                    toolCalls[index] = slot
                }
                entry.optString("id").takeIf { it.isNotEmpty() }?.let { id ->
                    slot = slot!!.first.copy(id = id) to slot!!.second
                    toolCalls[index] = slot
                }
                entry.optJSONObject("function")?.let { fn ->
                    val name = fn.optString("name")
                    if (name.isNotEmpty() && !slot!!.first.name.endsWith(name)) {
                        val merged = slot!!.first.copy(name = slot!!.first.name + name)
                        slot = merged to slot!!.second
                        toolCalls[index] = slot
                    }
                    val args = fn.opt("arguments")
                    if (args is String) slot!!.second.append(args)
                }
            }
        }

        /** 汇总输出（main result()：index 排序、空 id 补 call_N、空参数补 {}、无名项剔除） */
        fun result(): StreamResult {
            val calls = toolCalls.entries.sortedBy { it.key }.mapIndexed { i, (_, pair) ->
                val (call, argsBuf) = pair
                ToolCall(
                    id = call.id.ifEmpty { "call_$i" },
                    type = "function",
                    name = call.name,
                    arguments = argsBuf.toString().ifEmpty { "{}" }
                )
            }.filter { it.name.isNotEmpty() }
            return StreamResult(full.toString(), calls, finishReason)
        }
    }

    /** 非流式 tool_calls 数组解析（main parseToolCalls） */
    fun parseToolCalls(raw: JSONArray): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        for (i in 0 until raw.length()) {
            val record = raw.optJSONObject(i) ?: continue
            val fn = record.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            if (name.isEmpty()) continue
            val args = fn.opt("arguments")
            val serialized = when (args) {
                is String -> args
                null -> "{}"
                else -> args.toString()
            }
            out += ToolCall(
                id = record.optString("id").ifEmpty { "call_$i" },
                type = "function",
                name = name,
                arguments = serialized
            )
        }
        return out
    }

    // ───────────────────────── 请求体（main requestBody + toolExtras） ─────────────────────────

    private fun messageJson(m: ChatMessage): JSONObject = JSONObject().apply {
        put("role", m.role)
        put("content", m.content)
        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            put("tool_calls", JSONArray().apply {
                calls.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("type", "function")
                        put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
                    })
                }
            })
        }
        m.toolCallId?.let { put("tool_call_id", it) }
        m.name?.let { put("name", it) }
    }

    private fun requestBody(
        config: AiClient.Config,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>?,
        toolChoice: String?
    ): JSONObject = JSONObject().apply {
        put("model", config.model)
        put("messages", JSONArray().apply { messages.forEach { put(messageJson(it)) } })
        put("temperature", config.temperature)
        put("stream", stream)
        if (config.maxTokens > 0) put("max_tokens", config.maxTokens)
        if (!tools.isNullOrEmpty()) {
            put("tools", JSONArray().apply {
                tools.forEach { t ->
                    put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.parameters))
                    })
                }
            })
            put("tool_choice", toolChoice ?: "auto")
        }
    }

    // ───────────────────────── 流式请求主流程 ─────────────────────────

    /**
     * 流式 chat completion。取消：[isCancelled] 回调变 true 时尽快断流并抛 [CancelledException]
     * （对齐 main AbortSignal：取消已收到的部分内容仍可通过 onToken 拿到，但本调用以异常收尾）。
     */
    fun streamChatCompletion(
        config: AiClient.Config,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        toolChoice: String = "auto",
        onToken: ((delta: String, full: String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): StreamResult {
        val url = AiClient.normalizeChatUrl(config.endpoint)
        val acc = Accumulator(onToken)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                val key = config.apiKey.trim()
                if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(requestBody(config, messages, true, tools, toolChoice).toString().toByteArray()) }
            if (isCancelled()) throw CancelledException()
            val status = conn.responseCode
            if (status !in 200..299) {
                val body = runCatching {
                    (conn.errorStream ?: conn.inputStream).use { s -> s.readBytes().decodeToString() }.take(240)
                }.getOrDefault("")
                throw when {
                    status == 401 || status == 403 -> AiClient.AiRequestException(AiClient.Kind.AUTH, "authentication failed ($status): $body", status)
                    status == 429 -> AiClient.AiRequestException(AiClient.Kind.RATE_LIMIT, "rate limited (429): $body", 429)
                    status >= 500 -> AiClient.AiRequestException(AiClient.Kind.SERVER, "server error ($status): $body", status)
                    else -> AiClient.AiRequestException(AiClient.Kind.HTTP, "request failed ($status): $body", status)
                }
            }

            val contentType = conn.getHeaderField("Content-Type") ?: ""
            val streamBody = contentType.contains("text/event-stream") || contentType.contains("ndjson")
            val input = conn.inputStream
            if (!streamBody) {
                // 服务端忽略 stream:true，返回整段 JSON：按单个 delta 消费（main 同语义）
                val text = input.use { s -> s.readBytes().decodeToString() }
                try {
                    acc.consumePayload(JSONObject(text))
                } catch (e: AiClient.AiRequestException) {
                    throw e
                } catch (_: Exception) {
                    text.split('\n').forEach { line -> if (!acc.consumeLine(line)) return@forEach }
                }
            } else {
                // 增量读字节 + UTF-8 增量解码（跨 chunk 断行/断字符安全），再按行喂解析器
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val buffer = ByteArray(8192)
                var lineBuf = StringBuilder()
                input.use { s ->
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = s.read(buffer)
                        if (n < 0) break
                        val decoded = decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, n)).toString()
                        for (ch in decoded) {
                            if (ch == '\n') {
                                if (!acc.consumeLine(lineBuf.toString())) return finish(acc, conn)
                                lineBuf = StringBuilder()
                            } else lineBuf.append(ch)
                        }
                    }
                }
                if (lineBuf.isNotBlank() && !acc.consumeLine(lineBuf.toString())) return finish(acc, conn)
            }
            return finish(acc, conn)
        } catch (e: CancelledException) {
            throw e
        } catch (e: AiClient.AiRequestException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw AiClient.AiRequestException(AiClient.Kind.TIMEOUT, "request timed out after ${READ_TIMEOUT_MS} ms")
        } catch (e: IOException) {
            throw AiClient.AiRequestException(AiClient.Kind.NETWORK, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun finish(acc: Accumulator, conn: HttpURLConnection?): StreamResult {
        conn?.disconnect()
        val out = acc.result()
        if (out.content.isBlank() && out.toolCalls.isEmpty()) {
            throw AiClient.AiRequestException(AiClient.Kind.PARSE, "AI endpoint returned an empty or malformed completion")
        }
        return out
    }
}
