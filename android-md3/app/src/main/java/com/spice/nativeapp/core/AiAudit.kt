package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

/**
 * Agent 工具审计日志（1:1 移植 main src/lib/ai/audit.ts）：
 * 只增不删、本地保存、上限 500 条、绝不发给模型。
 * redactForAudit：凭据类键名打码、超长字符串截断、数组≤40、嵌套深度≤4。
 */
object AiAudit {

    enum class Kind { TOOL, APPROVAL, APPLIED, MEMORY }

    data class AuditEntry(
        val id: String,
        val at: String,
        val kind: Kind,
        val tool: String,
        val permission: String,           // read | write
        val approved: Boolean?,           // null = 未走审批（read 工具）
        val ok: Boolean,
        val summary: String,
        val args: Any? = null
    )

    private const val PREFS_KEY = "spice_ai_audit_v1"
    private const val MAX_ENTRIES = 500
    private val SECRET_KEY = Regex("(?i)(api[_-]?key|token|secret|password|authorization|credential)")

    fun load(ctx: Context): List<AuditEntry> = runCatching {
        val saved = ctx.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).getString(PREFS_KEY, null)
            ?: return emptyList()
        val arr = JSONArray(saved)
        val out = mutableListOf<AuditEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id"); val tool = o.optString("tool")
            if (id.isEmpty() || tool.isEmpty()) continue
            val kind = runCatching { Kind.valueOf(o.optString("kind", "TOOL").uppercase()) }.getOrDefault(Kind.TOOL)
            val approved = if (o.has("approved") && !o.isNull("approved")) o.optBoolean("approved") else null
            out += AuditEntry(
                id, o.optString("at"), kind, tool,
                o.optString("permission", "read"), approved,
                o.optBoolean("ok", false), o.optString("summary", "")
            )
        }
        out.take(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    fun save(ctx: Context, entries: List<AuditEntry>): List<AuditEntry> {
        val clean = entries.take(MAX_ENTRIES)
        runCatching {
            val arr = JSONArray()
            clean.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id); put("at", e.at); put("kind", e.kind.name); put("tool", e.tool)
                    put("permission", e.permission); put("ok", e.ok); put("summary", e.summary)
                    e.approved?.let { put("approved", it) }
                })
            }
            ctx.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit()
                .putString(PREFS_KEY, arr.toString()).apply()
        }
        return clean
    }

    fun append(
        ctx: Context,
        entries: List<AuditEntry>,
        kind: Kind,
        tool: String,
        permission: String,
        approved: Boolean?,
        ok: Boolean,
        summary: String,
        args: Any? = null
    ): List<AuditEntry> = save(ctx, listOf(
        AuditEntry(UUID.randomUUID().toString(), Date().toString(), kind, tool, permission, approved, ok, summary, redactForAudit(args))
    ) + entries)

    /** 凭据打码 + 体量限制（main redactForAudit） */
    fun redactForAudit(value: Any?, depth: Int = 0): Any? {
        if (depth > 4) return "[truncated]"
        return when (value) {
            is String -> if (value.length > 500) value.take(500) + "…" else value
            is List<*> -> value.take(40).map { redactForAudit(it, depth + 1) }
            is Map<*, *> -> value.entries.associate { entry ->
                entry.key.toString() to (if (SECRET_KEY.containsMatchIn(entry.key.toString())) "[redacted]" else redactForAudit(entry.value, depth + 1))
            }
            else -> value
        }
    }
}
