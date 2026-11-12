package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SPD 基因库 / 载体库 / 酶库客户端，对应原版 spd/client.ts 的基因、载体、限制酶端点。
 * 端点：GET /genes, GET /genes/{id}, POST /genes, GET /vectors, GET /vectors/{id},
 *       POST /vectors, GET /restriction-enzymes/export
 */
object SpdLibrary {

    data class Result<T>(val value: T?, val error: String?)

    private const val DEFAULT_BASE = "https://d-api.spicebio.top/api/v1"

    fun baseUrl(ctx: android.content.Context): String =
        ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
            .getString("spd_base_url", DEFAULT_BASE) ?: DEFAULT_BASE

    fun token(ctx: android.content.Context): String =
        ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE)
            .getString("spd_api_token", "") ?: ""

    private fun request(base: String, token: String, method: String, path: String, body: String? = null): Result<JSONObject> {
        if (token.isBlank()) return Result(null, "未配置 API Token（设置 → SPD 身份与凭据）")
        return runCatching {
            val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            if (code !in 200..299) return Result(null, "HTTP $code：${text.take(200)}")
            Result(JSONObject(text), null)
        }.getOrElse { Result(null, it.message ?: it.toString()) }
    }

    data class GeneRow(val id: String, val name: String, val length: Int?, val organism: String?, val updated: String?)
    data class VectorRow(val id: String, val name: String, val length: Int?, val kind: String?)

    /** 基因列表（对应 client.listGenes） */
    fun listGenes(ctx: android.content.Context, query: String = ""): Result<List<GeneRow>> {
        val q = if (query.isBlank()) "" else "?q=" + URLEncoder.encode(query, "UTF-8")
        val r = request(baseUrl(ctx), token(ctx), "GET", "/genes$q")
        val json = r.value ?: return Result(null, r.error)
        val arr = json.optJSONArray("items") ?: json.optJSONArray("genes") ?: if (json.has("id")) JSONArray().put(json) else JSONArray()
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            GeneRow(
                id = o.optString("id", o.optString("gene_id", "")),
                name = o.optString("name", o.optString("display_name", "(未命名)")),
                length = o.optInt("length", o.optInt("bp", 0)).takeIf { it > 0 },
                organism = o.optString("organism", null),
                updated = o.optString("updated_at", o.optString("updatedAt", null))
            )
        }
        return Result(rows, null)
    }

    /** 按 id 载入基因序列（GET /genes/{id}） */
    fun loadGene(ctx: android.content.Context, id: String): Result<SeqRecord> {
        val r = request(baseUrl(ctx), token(ctx), "GET", "/genes/" + URLEncoder.encode(id, "UTF-8"))
        val json = r.value ?: return Result(null, r.error)
        val gene = json.optJSONObject("gene") ?: json
        val seq = gene.optString("dna", gene.optString("sequence", "")).uppercase().filter { it.isLetter() }
        if (seq.isEmpty()) return Result(null, "该基因未包含 dna 字段")
        val name = gene.optString("name", "SPD-$id")
        return Result(
            SeqRecord(
                name = name,
                sequence = seq,
                circular = gene.optBoolean("circular", false),
                features = emptyList(),
                description = "SPD 基因 $id" + (gene.optString("organism", "").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            ), null
        )
    }

    /** 发布当前序列到 SPD（POST /genes） */
    fun publishGene(ctx: android.content.Context, record: SeqRecord): Result<String> {
        val payload = JSONObject().apply {
            put("name", record.name)
            put("dna", record.sequence)
            put("circular", record.circular)
            put("length", record.length)
            put("source", "spice-native")
        }
        val r = request(baseUrl(ctx), token(ctx), "POST", "/genes", payload.toString())
        val json = r.value ?: return Result(null, r.error)
        val id = json.optString("id", json.optString("gene_id", json.optString("geneId", "")))
        return if (id.isBlank()) Result(null, "创建返回中缺少 id") else Result(id, null)
    }

    /** 载体列表（GET /vectors） */
    fun listVectors(ctx: android.content.Context, query: String = ""): Result<List<VectorRow>> {
        val q = if (query.isBlank()) "" else "?q=" + URLEncoder.encode(query, "UTF-8")
        val r = request(baseUrl(ctx), token(ctx), "GET", "/vectors$q")
        val json = r.value ?: return Result(null, r.error)
        val arr = json.optJSONArray("items") ?: json.optJSONArray("vectors") ?: JSONArray()
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VectorRow(
                id = o.optString("id", ""),
                name = o.optString("name", o.optString("display_name", "(未命名)")),
                length = o.optInt("length", 0).takeIf { it > 0 },
                kind = o.optString("kind", o.optString("type", null))
            )
        }
        return Result(rows, null)
    }

    /** 载入载体模板（GET /vectors/{id}），取其中的序列字段 */
    fun loadVector(ctx: android.content.Context, id: String): Result<SeqRecord> {
        val r = request(baseUrl(ctx), token(ctx), "GET", "/vectors/" + URLEncoder.encode(id, "UTF-8"))
        val json = r.value ?: return Result(null, r.error)
        val vec = json.optJSONObject("vector") ?: json
        val seq = vec.optString("sequence", vec.optString("dna", "")).uppercase().filter { it.isLetter() }
        if (seq.isEmpty()) return Result(null, "该载体未返回 sequence 字段")
        return Result(
            SeqRecord(
                name = vec.optString("name", "SPD-$id"),
                sequence = seq,
                circular = vec.optBoolean("circular", true),
                features = emptyList(),
                description = "SPD 载体 $id"
            ), null
        )
    }

    /** 酶库导出（GET /restriction-enzymes/export），返回本地可比的酶数量 */
    fun exportEnzymeCount(ctx: android.content.Context): Result<Int> {
        val r = request(baseUrl(ctx), token(ctx), "GET", "/restriction-enzymes/export")
        val json = r.value ?: return Result(null, r.error)
        val arr = json.optJSONArray("items") ?: json.optJSONArray("enzymes") ?: return Result(0, null)
        return Result(arr.length(), null)
    }
}
