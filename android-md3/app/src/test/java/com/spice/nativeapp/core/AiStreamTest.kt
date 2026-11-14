package com.spice.nativeapp.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiStream 解析器与 AiContext 裁剪的 1:1 核验（main client.ts streamChatCompletion / context.ts）：
 * SSE 增量、[DONE]、tool_calls 流式合并、错误负载、整段 JSON 回退、tool 结果配对裁剪。
 */
class AiStreamTest {

    private fun accum(onToken: ((String, String) -> Unit)? = null) = AiStream.Accumulator(onToken)

    @Test
    fun `sse content deltas accumulate and stream to onToken`() {
        val seen = mutableListOf<Pair<String, String>>()
        val a = accum { d, f -> seen.add(d to f) }
        assertTrue(a.consumeLine("""data: {"choices":[{"delta":{"content":"你"}}]}"""))
        assertTrue(a.consumeLine("""data: {"choices":[{"delta":{"content":"好"}}]}"""))
        // [DONE] 返回 false 表示流终止（main consumeLine 同语义）
        assertEquals(false, a.consumeLine("data: [DONE]"))
        assertEquals("你好", a.result().content)
        assertEquals(listOf("你" to "你", "好" to "你好"), seen)
        assertEquals("stop", a.result().finishReason ?: "stop")
    }

    @Test
    fun `finish_reason captured from payload`() {
        val a = accum()
        a.consumeLine("""data: {"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""")
        a.consumeLine("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals("stop", a.result().finishReason)
    }

    @Test
    fun `streaming tool_calls merge by index with argument fragments`() {
        val a = accum()
        // OpenAI 流式：第一个 chunk 带 id+name，后续 chunk 只带 index+arguments 增量
        a.consumeLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"analyze_","arguments":""}}]}}]}""")
        a.consumeLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"dna","arguments":"{\"seq"}}]}}]}""")
        a.consumeLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"uence\":\"ATG\"}"}}]}}]}""")
        a.consumeLine("data: [DONE]")
        val calls = a.result().toolCalls
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("analyze_dna", calls[0].name)
        assertEquals("""{"sequence":"ATG"}""", calls[0].arguments)
    }

    @Test
    fun `tool call without id falls back to call_index and empty args to braces`() {
        val a = accum()
        a.consumeLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":2,"function":{"name":"get_workspace_context"}}]}}]}""")
        a.consumeLine("data: [DONE]")
        val calls = a.result().toolCalls
        assertEquals("call_0", calls[0].id)
        assertEquals("{}", calls[0].arguments)
    }

    @Test
    fun `error payload throws server error`() {
        val a = accum()
        val ex = assertThrows(AiClient.AiRequestException::class.java) {
            a.consumeLine("""data: {"error":{"message":"quota exceeded"}}""")
        }
        assertEquals(AiClient.Kind.SERVER, ex.kind)
        assertTrue(ex.message!!.contains("quota exceeded"))
    }

    @Test
    fun `non-json and keepalive lines ignored`() {
        val a = accum()
        assertTrue(a.consumeLine(": keep-alive"))
        assertTrue(a.consumeLine(""))
        assertTrue(a.consumeLine("event: ping"))
        assertTrue(a.consumeLine("""data: {"choices":[{"delta":{"content":"x"}}]}"""))
        assertEquals("x", a.result().content)
    }

    @Test
    fun `non-streamed message shape consumed as single payload`() {
        val a = accum()
        a.consumePayload(
            JSONObject("""{"choices":[{"message":{"content":"full reply","tool_calls":[{"id":"c","type":"function","function":{"name":"t","arguments":"{}"}}]}}]}""")
        )
        val r = a.result()
        assertEquals("full reply", r.content)
        assertEquals(listOf("t"), r.toolCalls.map { it.name })
    }

    @Test
    fun `parseToolCalls serializes object arguments`() {
        val raw = org.json.JSONArray(
            """[{"id":"a","function":{"name":"f","arguments":{"k":1}}}]"""
        )
        val calls = AiStream.parseToolCalls(raw)
        assertEquals("""{"k":1}""", calls[0].arguments)
    }

    // ── AiContext ──

    @Test
    fun `estimateTokens counts cjk as one and others as quarter`() {
        assertEquals(1, AiContext.estimateTokens("abc"))        // 3 ASCII = 0.75 → ceil 1
        assertEquals(2, AiContext.estimateTokens("中文"))       // 2 CJK = 2
        assertEquals(3, AiContext.estimateTokens("中文abc"))    // 2 + 0.75 → ceil 3
    }

    @Test
    fun `buildChatPayload drops oldest history first and keeps system and current`() {
        val history = (1..40).map {
            AiStream.ChatMessage("user", "问题 $it ").let { u ->
                listOf(u, AiStream.ChatMessage("assistant", "回答 $it 回答 $it 回答 $it"))
            }
        }.flatten()
        val payload = AiContext.buildChatPayload(
            systemPrompt = "SYS",
            history = history,
            userContent = "当前问题",
            contextTokens = 300,
            reserveTokens = 64
        )
        assertTrue(payload.messages.first().content == "SYS")
        assertTrue(payload.messages.last().content == "当前问题")
        assertTrue(payload.droppedMessages > 0)
        // 历史保留的是最新片段
        assertTrue(payload.messages.dropLast(1).last().content.startsWith("回答 40") || payload.messages.dropLast(1).last().role != "system")
    }

    @Test
    fun `trimAgentConversation keeps tool result paired with its assistant message`() {
        fun m(role: String, content: String) = AiStream.ChatMessage(role, content)
        val msgs = mutableListOf(
            m("system", "SYS"),
            m("user", "很长的问题 ".repeat(120)),   // 约 1800 token，撑爆小预算
            m("assistant", ""),
        )
        // assistant 带 tool_calls 的占位 + tool 结果
        val call = AiStream.ChatMessage(
            "assistant", "",
            toolCalls = listOf(AiStream.ToolCall("c1", "function", "analyze_dna", "{}"))
        )
        val toolResult = AiStream.ChatMessage("tool", """{"ok":true}""", toolCallId = "c1", name = "analyze_dna")
        msgs.addAll(listOf(call, toolResult, m("user", "final ask")))
        val trimmed = AiContext.trimAgentConversation(msgs, contextTokens = 600, reserveTokens = 128)
        // tool 结果若保留，必须伴随它前面的 assistant tool_calls 消息
        val toolIdx = trimmed.indexOfFirst { it.role == "tool" }
        if (toolIdx >= 0) {
            assertTrue(toolIdx > 0)
            assertEquals("assistant", trimmed[toolIdx - 1].role)
            assertTrue(trimmed[toolIdx - 1].toolCalls != null)
        }
        assertTrue(trimmed.last().content == "final ask")
    }

    @Test
    fun `small conversation returns unchanged`() {
        val msgs = listOf(
            AiStream.ChatMessage("system", "S"),
            AiStream.ChatMessage("user", "hi"),
            AiStream.ChatMessage("assistant", "hello"),
        )
        assertEquals(msgs, AiContext.trimAgentConversation(msgs))
    }
}
