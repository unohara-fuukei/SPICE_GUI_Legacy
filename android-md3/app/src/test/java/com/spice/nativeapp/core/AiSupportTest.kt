package com.spice.nativeapp.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AiMemory / AiRetrieval / AiAudit 纯逻辑核验（main memory.ts / retrieval.ts / audit.ts） */
class AiSupportTest {

    // ── AiRetrieval ──

    @Test
    fun `tokenize splits cjk bigrams and ascii words`() {
        val tokens = AiRetrieval.tokenize("Gibson 组装实验")
        assertTrue(tokens.contains("gibson"))
        assertTrue(tokens.contains("组装"))          // 2 字整词 + 也是二元组
        assertTrue(tokens.contains("装实"))          // 二元组
        assertTrue(tokens.contains("实验"))
        assertTrue(tokens.contains("组装实验"))      // ≤4 字整词
    }

    @Test
    fun `retrieve ranks by idf and returns snippets`() {
        val docs = listOf(
            AiRetrieval.SearchDocument("doc:1", "doc", "Gibson 组装笔记", "我们用 Gibson 组装构建了质粒，片段重叠 20bp"),
            AiRetrieval.SearchDocument("doc:2", "doc", "发酵条件", "37 度培养 12 小时，转速 220")
        )
        val hits = AiRetrieval.retrieve("Gibson 组装", docs, 2)
        assertTrue(hits.isNotEmpty())
        assertEquals("doc:1", hits.first().document.id)
        assertTrue(hits.first().snippet.isNotBlank())

        val none = AiRetrieval.retrieve("质谱", docs, 2)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `formatRetrievalContext labels data as untrusted`() {
        val docs = listOf(AiRetrieval.SearchDocument("doc:1", "doc", "克隆", "Gibson 组装"))
        val text = AiRetrievalContextDocs(docs)
        assertTrue(text.contains("not instructions"))
        assertTrue(text.contains("[doc] 克隆"))
    }

    private fun AiRetrievalContextDocs(docs: List<AiRetrieval.SearchDocument>): String =
        AiRetrieval.formatRetrievalContext(AiRetrieval.retrieve("克隆 Gibson", docs, 4))

    // ── AiMemory ──

    @Test
    fun `tokenize memory matches search expectations`() {
        val tokens = AiMemory.tokenize("宿主是大肠杆菌 BL21")
        assertTrue(tokens.any { it == "宿主" })
        assertTrue(tokens.contains("bl21"))
    }

    @Test
    fun `searchMemory scores and ranks entries`() {
        val entries = listOf(
            AiMemory.MemoryEntry("a", "表达宿主为 BL21（DE3）", emptyList(), "t1"),
            AiMemory.MemoryEntry("b", "引物要避开除错碱基", emptyList(), "t2")
        )
        val hits = AiMemory.searchMemory("宿主是什么", entries)
        assertEquals(1, hits.size)
        assertEquals("a", hits.first().id)
        // query 缺省空 → 无结果
        assertTrue(AiMemory.searchMemory("", entries).isEmpty())
    }

    // ── AiAudit ──

    @Test
    fun `redactForAudit masks credential keys and bounds size`() {
        val raw = mapOf<String, Any?>(
            "api_key" to "sk-secret",
            "note" to "hello",
            "nested" to mapOf("password" to "p")
        )
        val redacted = AiAudit.redactForAudit(raw) as Map<*, *>
        assertEquals("[redacted]", redacted["api_key"])
        assertEquals("hello", redacted["note"])
        val nested = redacted["nested"] as Map<*, *>
        assertEquals("[redacted]", nested["password"])

        assertEquals("[truncated]", AiAudit.redactForAudit("x", depth = 5))
        val long = AiAudit.redactForAudit("a".repeat(600)) as String
        assertEquals(501, long.length)   // 500 + 省略号
        val list = AiAudit.redactForAudit(List(50) { it }) as List<*>
        assertEquals(40, list.size)
    }

    @Test
    fun `audit entry kind roundtrip`() {
        assertEquals(AiAudit.Kind.APPLIED, AiAudit.Kind.valueOf("APPLIED"))
        assertEquals(4, AiAudit.Kind.values().size)
        // recordAudit 语义：main kind ∈ approval | applied | tool | memory
        assertTrue(AiAudit.Kind.values().map { it.name.lowercase() }.containsAll(listOf("approval", "applied", "tool", "memory")))
    }
}
