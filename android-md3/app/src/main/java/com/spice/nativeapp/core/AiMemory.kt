package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * Co-Pilot 长期记忆（1:1 移植 main src/lib/ai/memory.ts）。
 *
 * - SharedPreferences 键 spice_ai_memory_v1（与 main localStorage 同名）；
 * - 上限 200 条；去重（大小写不敏感）；标签归一（小写、去重、≤12）；
 * - searchMemory：词元重叠打分，CJK 按二元组（bigram）切分，≥3 字符词元权重 ×2。
 * 记忆仅在词法相关时注入，绝不作为隐式指令（main 同约定）。
 */
object AiMemory {

    data class MemoryEntry(val id: String, val text: String, val tags: List<String>, val createdAt: String)

    private const val PREFS_KEY = "spice_ai_memory_v1"
    private const val MAX_ENTRIES = 200
    private val CJK = Regex("[\\u2E80-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]")

    private fun normalizeTags(tags: Any?): List<String> {
        val raw = when (tags) {
            is List<*> -> tags.map { it.toString() }
            is String -> tags.split(',', ';')
            else -> emptyList()
        }
        return raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(12)
    }

    fun load(ctx: Context): List<MemoryEntry> = runCatching {
        val saved = ctx.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).getString(PREFS_KEY, null)
            ?: return emptyList()
        val arr = JSONArray(saved)
        val out = mutableListOf<MemoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val text = o.optString("text").trim()
            if (id.isEmpty() || text.isEmpty()) continue
            out += MemoryEntry(
                id = id,
                text = text,
                tags = normalizeTags(o.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) } }),
                createdAt = o.optString("createdAt").ifEmpty { Date().toString() }
            )
        }
        out.take(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    fun save(ctx: Context, entries: List<MemoryEntry>): List<MemoryEntry> {
        val clean = entries.take(MAX_ENTRIES)
        runCatching {
            val arr = JSONArray()
            clean.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("text", e.text)
                    put("tags", JSONArray(e.tags))
                    put("createdAt", e.createdAt)
                })
            }
            ctx.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit()
                .putString(PREFS_KEY, arr.toString()).apply()
        }
        return clean
    }

    fun add(ctx: Context, entries: List<MemoryEntry>, text: String, tags: Any? = emptyList<String>()): List<MemoryEntry> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return entries
        if (entries.any { it.text.lowercase() == cleanText.lowercase() }) return entries
        return save(ctx, listOf(
            MemoryEntry(UUID.randomUUID().toString(), cleanText.take(2000), normalizeTags(tags), Date().toString())
        ) + entries)
    }

    fun remove(ctx: Context, entries: List<MemoryEntry>, id: String): List<MemoryEntry> =
        save(ctx, entries.filter { it.id != id })

    /** 词元化（main tokens()：CJK 短串整词 + 二元组；其他 ≥2 字符整词） */
    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val out = LinkedHashSet<String>()
        Regex("[a-z0-9]+|[\\u2E80-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF]+").findAll(lower).forEach { m ->
            val token = m.value
            if (CJK.containsMatchIn(token)) {
                if (token.length <= 4) out.add(token)
                for (i in 0 until token.length - 1) out.add(token.substring(i, i + 2))
            } else if (token.length >= 2) out.add(token)
        }
        return out.toList()
    }

    /** 按词元重叠排序记忆（main searchMemory：命中计分，≥3 字符词元 ×2） */
    fun searchMemory(query: String, entries: List<MemoryEntry>, limit: Int = 5): List<MemoryEntry> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()
        return entries.map { entry ->
            val haystack = "${entry.text} ${entry.tags.joinToString(" ")}".lowercase()
            var score = 0
            for (token in queryTokens) if (haystack.contains(token)) score += if (token.length >= 3) 2 else 1
            entry to score
        }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(1))
            .map { it.first }
    }
}
