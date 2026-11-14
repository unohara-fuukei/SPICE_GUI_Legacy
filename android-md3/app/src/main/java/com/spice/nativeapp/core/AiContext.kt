package com.spice.nativeapp.core

/**
 * Co-Pilot 上下文预算（1:1 移植 main src/lib/ai/context.ts）。
 *
 * estimateTokens：CJK 字符 1 token、其余 1/4 token 的保守估计（main 同公式）。
 * buildChatPayload：system + 最近可用历史 + 当前用户轮，历史从旧到新丢弃。
 * trimAgentConversation：agent 工具调用循环内的裁剪——最新轮优先，
 * 但 system 与 tool 结果所属的 assistant 消息永不孤立。
 */
object AiContext {

    const val DEFAULT_CONTEXT_TOKENS = 8192

    private val CJK = Regex("[\\u2E80-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]")

    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) if (CJK.matches(ch.toString())) cjk++ else other++
        return Math.ceil(cjk + other / 4.0).toInt()
    }

    data class BuiltPayload(
        val messages: List<AiStream.ChatMessage>,
        val estimatedTokens: Int,
        val droppedMessages: Int
    )

    /**
     * 构造最终消息列表（main buildChatPayload）：
     * system 提示词与当前用户轮永不丢弃；历史从最旧开始丢弃以装进预算。
     */
    fun buildChatPayload(
        systemPrompt: String,
        history: List<AiStream.ChatMessage>,
        userContent: String,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        reserveTokens: Int = 1024
    ): BuiltPayload {
        val total = maxOf(512, contextTokens)
        val reserve = reserveTokens.coerceIn(0, total - 256)
        val budget = maxOf(256, total - reserve)

        val system = AiStream.ChatMessage("system", systemPrompt)
        val current = AiStream.ChatMessage("user", userContent)
        val cleanHistory = history.filter { it.content.isNotBlank() }

        var used = estimateTokens(system.content) + estimateTokens(current.content)
        val kept = mutableListOf<AiStream.ChatMessage>()
        for (i in cleanHistory.indices.reversed()) {
            val m = cleanHistory[i]
            val cost = estimateTokens(m.content) + 4
            if (used + cost > budget) break
            kept.add(0, m)
            used += cost
        }
        return BuiltPayload(listOf(system) + kept + current, used, cleanHistory.size - kept.size)
    }

    /**
     * agent 循环内的会话裁剪（main trimAgentConversation）：
     * 超预算时保留最新消息，tool 结果消息必须与其 assistant 调用消息成对保留。
     */
    fun trimAgentConversation(
        messages: List<AiStream.ChatMessage>,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        reserveTokens: Int = 1024
    ): List<AiStream.ChatMessage> {
        val total = maxOf(512, contextTokens)
        val reserve = reserveTokens.coerceIn(0, total - 256)
        val budget = total - reserve
        if (messages.sumOf { estimateTokens(it.content) + 6 } <= budget) return messages

        var used = 0
        var start = messages.size
        for (i in messages.indices.reversed()) {
            val cost = estimateTokens(messages[i].content) + 6
            if (used + cost > budget && start < messages.size) break
            used += cost
            start = i
        }

        // tool 结果必须与拥有它的 assistant tool_calls 消息同时保留
        while (start > 1 && messages.getOrNull(start)?.role == "tool") start--

        val system = if (messages.firstOrNull()?.role == "system") listOf(messages.first()) else emptyList()
        val tailStart = maxOf(start, system.size)
        return system + messages.subList(tailStart.coerceAtMost(messages.size), messages.size)
    }
}
