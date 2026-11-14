package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * SPD（SPICE Protein Database）客户端，对齐原版 src/lib/spd/client.ts + genes.ts + vectors.ts：
 * - 请求/响应语义 1:1：{data, error, meta} 信封（error.code/message → 错误）、
 *   创建回执为裸对象；基因列表 data.items、详情 {gene:…}、区域批 {regions:[…]}、
 *   RNA-only 记录 U→T 载入（对齐 genes.ts loadGene）。
 * - 网络环境：d-api.spicebio.top 直连通常不可达，默认走 HTTP 代理（设置页可自定义/留空直连），
 *   对应本仓库开发机的出网方式（Chrome 不吃代理白屏、shell 代理可达的同一事实）。
 */
object SpdLibrary {

    data class Result<T>(val value: T?, val error: String?)

    private const val PREF = "spice_settings"
    private const val KEY_BASE = "spd_base_url"
    private const val KEY_TOKEN = "spd_api_token"
    private const val KEY_PROXY = "spd_proxy_url"

    const val DEFAULT_BASE = "https://d-api.spicebio.top/api/v1"
    const val DEFAULT_PROXY = "http://proxy-sid-62971027@vortex-3e0s8vctxbmdc6uqpftnfzswr.vortexip.cn-beijing2.volces.com:8080"

    // ─────────────────────────── 配置 ───────────────────────────

    fun baseUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun proxySetting(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_PROXY, DEFAULT_PROXY) ?: DEFAULT_PROXY

    /** 代理 URL → java.net.Proxy；空串 = 直连。支持 http://[user[:pass]@]host:port 与 host:port。 */
    internal data class ParsedProxy(val proxy: Proxy?, val authHeader: String?, val error: String?)

    internal fun parseProxy(spec: String): ParsedProxy {
        val s = spec.trim()
        if (s.isEmpty()) return ParsedProxy(null, null, null) // 留空 = 直连
        return try {
            var rest = s
            val scheme = rest.indexOf("://")
            if (scheme >= 0) rest = rest.substring(scheme + 3)
            var userinfo: String? = null
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                userinfo = rest.substring(0, at)
                rest = rest.substring(at + 1)
            }
            val colon = rest.lastIndexOf(':')
            require(colon > 0) { com.spice.nativeapp.core.I18n.t("缺少端口") }
            val host = rest.substring(0, colon)
            val port = rest.substring(colon + 1).toIntOrNull() ?: throw IllegalArgumentException(com.spice.nativeapp.core.I18n.t("端口不是数字"))
            require(host.isNotBlank()) { com.spice.nativeapp.core.I18n.t("主机为空") }
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            val auth = userinfo?.takeIf { it.isNotBlank() }?.let {
                "Basic " + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
            }
            ParsedProxy(proxy, auth, null)
        } catch (e: Exception) {
            ParsedProxy(null, null, com.spice.nativeapp.core.I18n.tf("代理地址无法解析（{v1}），本次直连", e.message))
        }
    }

    private class Cfg(val base: String, val token: String, val proxy: Proxy?, val proxyAuth: String?) {
        companion object {
            fun of(ctx: Context): Cfg {
                val p = parseProxy(proxySetting(ctx))
                return Cfg(baseUrl(ctx), token(ctx), p.proxy, p.authHeader)
            }
        }
    }

    // ─────────────────────── HTTP 核心（对齐 client.ts http()） ───────────────────────

    private class HttpResult(val status: Int, val text: String)

    private fun rawHttp(cfg: Cfg, method: String, urlStr: String, body: String?, contentType: String?): HttpResult {
        // 显式传代理（或 Proxy.NO_PROXY 强制直连），不吃系统默认
        val conn = (URL(urlStr).openConnection(cfg.proxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (cfg.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.token}")
            cfg.proxyAuth?.let { setRequestProperty("Proxy-Authorization", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 对齐 client.ts http()：非 JSON → 报错；error 信封 → code: message；HTTP>=400 → 报错；
     *  {data, meta|error} 信封 → 解包 data；创建回执（裸对象）原样返回。纯函数，可单测。 */
    internal fun envelope(status: Int, text: String): Result<Any?> {
        val payload: Any? = try {
            if (text.isBlank()) JSONObject.NULL else JSONTokener(text).nextValue()
        } catch (e: Exception) {
            return Result(null, "SPD returned non-JSON ($status): ${text.take(160)}")
        }
        if (payload != JSONObject.NULL && payload !is JSONObject && payload !is JSONArray) {
            return Result(null, "SPD returned non-JSON ($status): ${text.take(160)}")
        }
        if (payload is JSONObject) {
            val err = payload.optJSONObject("error")
            if (err != null) {
                val code = err.optString("code", "HTTP_$status").ifEmpty { "HTTP_$status" }
                val msg = err.optString("message", "request failed").ifEmpty { "request failed" }
                return Result(null, "$code: $msg")
            }
        }
        if (status >= 400) {
            val msg = (payload as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(160)
            return Result(null, "HTTP $status: $msg")
        }
        if (payload is JSONObject && payload.has("data") && (payload.has("meta") || payload.has("error"))) {
            val d = payload.opt("data")
            return Result(if (d == JSONObject.NULL) null else d, null)
        }
        return Result(payload, null)
    }

    private fun http(cfg: Cfg, method: String, path: String, body: JSONObject? = null): Result<Any?> =
        runCatching {
            val r = rawHttp(cfg, method, cfg.base.trimEnd('/') + path, body?.toString(), null)
            envelope(r.status, r.text)
        }.getOrElse { Result(null, it.message ?: it.toString()) }

    /** 原始文本 GET（工件内容等非 JSON 端点，对齐 client.ts getArtifactContentText）。 */
    private fun httpText(cfg: Cfg, path: String): Result<String> = runCatching {
        val r = rawHttp(cfg, "GET", cfg.base.trimEnd('/') + path, null, null)
        if (r.status >= 400) return Result(null, "HTTP ${r.status}: ${r.text.take(200)}")
        Result(r.text, null)
    }.getOrElse { Result(null, it.message ?: it.toString()) }

    // ─────────────────────────── 列表工具 ───────────────────────────

    /** 对齐 client.ts listItems()：数组 / {items:[…]} / {regions:[…]} / {rows:[…]}。 */
    internal fun listItems(payload: Any?): JSONArray? {
        return when (payload) {
            is JSONArray -> payload
            is JSONObject -> {
                for (key in listOf("items", "regions", "rows")) {
                    val a = payload.optJSONArray(key)
                    if (a != null) return a
                }
                null
            }
            else -> null
        }
    }

    private fun optString(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank()) return v }
        return ""
    }

    // ─────────────────────── health / me（设置页校验） ───────────────────────

    data class HealthInfo(val service: String, val status: String, val apiVersion: String)

    fun health(ctx: Context): Result<HealthInfo> {
        val r = http(Cfg.of(ctx), "GET", "/health")
        val o = r.value as? JSONObject ?: return Result(null, r.error ?: "health 响应格式异常")
        return Result(HealthInfo(o.optString("service", "spd"), o.optString("status", ""), o.optString("apiVersion", "")), null)
    }

    data class MeInfo(val displayName: String, val orcidId: String, val id: String, val roles: String)

    fun me(ctx: Context): Result<MeInfo> {
        val r = http(Cfg.of(ctx), "GET", "/user/me")
        val o = r.value as? JSONObject ?: return Result(null, r.error ?: "user/me 响应格式异常")
        val user = o.optJSONObject("user") ?: o
        val rolesArr = o.optJSONArray("roles")
        val roles = rolesArr?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("role")?.takeIf { r2 -> r2.isNotBlank() } } } ?: emptyList()
        return Result(
            MeInfo(
                displayName = optString(user, "displayName", "orcidId", "id"),
                orcidId = user.optString("orcidId", ""),
                id = user.optString("id", ""),
                roles = (if (roles.isEmpty()) listOf("user") else roles).joinToString(", ")
            ), null
        )
    }

    // ─────────────────────────── 基因库 ───────────────────────────

    data class GeneRow(val id: String, val name: String, val length: Int?, val organism: String?, val updated: String?)

    /** 基因列表（对齐 client.listGenes + searchGenes：空查询 GET /genes，有查询 POST /search）。 */
    fun listGenes(ctx: Context, query: String = ""): Result<List<GeneRow>> {
        val cfg = Cfg.of(ctx)
        val r = if (query.isBlank()) {
            http(cfg, "GET", "/genes")
        } else {
            http(cfg, "POST", "/search", JSONObject().put("entityType", "gene").put("query", query).put("limit", 50))
        }
        if (r.error != null) return Result(null, r.error)
        val arr = listItems(r.value) ?: return Result(emptyList(), null)
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val organism = optString(o, "organismScientificName").ifEmpty {
                o.optJSONObject("organism")?.optString("scientificName", "") ?: ""
            }
            GeneRow(
                id = o.optString("id", ""),
                name = optString(o, "name", "symbol").ifEmpty { com.spice.nativeapp.core.I18n.t("(未命名)") },
                length = listOfNotNull(if (o.optInt("dnaLength", -1) > 0) o.optInt("dnaLength") else null,
                    if (o.optInt("rnaLength", -1) > 0) o.optInt("rnaLength") else null).firstOrNull(),
                organism = organism.ifEmpty { null },
                updated = o.optLong("sourceUpdatedAt", 0L).takeIf { it > 0 }?.let { secs ->
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(secs * 1000))
                }
            )
        }
        return Result(rows, null)
    }

    /** 区域类型白名单（对齐 genes.ts SPD_REGION_TYPES：服务端 GeneRegion.regionType 枚举）。 */
    internal val SPD_REGION_TYPES = setOf(
        "promoter", "operator", "enhancer", "silencer", "tata_box", "caat_signal",
        "terminator", "origin", "exon", "intron", "five_prime_utr", "three_prime_utr",
        "cds", "rnase_site", "other"
    )

    /** 常见 GenBank/GUI 拼写 → SPD 枚举（对齐 genes.ts REGION_TYPE_ALIASES）。 */
    internal val REGION_TYPE_ALIASES = mapOf(
        "5utr" to "five_prime_utr", "5_utr" to "five_prime_utr", "5_prime_utr" to "five_prime_utr",
        "3utr" to "three_prime_utr", "3_utr" to "three_prime_utr", "3_prime_utr" to "three_prime_utr",
        "misc_feature" to "other", "miscellaneous" to "other", "feature" to "other", "gene" to "other"
    )

    /** GUI 特征类型 → SPD 区域类型（对齐 genes.ts mapFeatureTypeToRegionType）。 */
    internal fun mapFeatureTypeToRegionType(type: String): String {
        val key = type.trim().lowercase()
            .replace(Regex("['′’]"), "")
            .replace(Regex("[\\s-]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return REGION_TYPE_ALIASES[key] ?: if (key in SPD_REGION_TYPES) key else "other"
    }

    /** GUI 特征 → SPD 区域行（对齐 genes.ts toGeneRegions：非法行跳过而非钳制，上限 500）。 */
    internal fun toGeneRegions(features: List<Feature>, sequenceLength: Int): Pair<JSONArray, Int> {
        val regions = JSONArray()
        var skipped = 0
        for (f in features) {
            if (regions.length() >= 500) { skipped++; continue }
            val valid = f.start >= 1 && f.end >= f.start && f.end <= sequenceLength
            if (!valid) { skipped++; continue }
            val rawType = f.type.trim()
            val description = (f.name.trim().ifEmpty { rawType }).take(400)
            if (description.isEmpty()) { skipped++; continue }
            regions.put(JSONObject().apply {
                put("molecule", "dna")
                put("regionType", mapFeatureTypeToRegionType(rawType))
                put("description", description)
                put("startPosition", f.start)
                put("endPosition", f.end)
                put("strand", if (f.strand == -1) "reverse" else "forward")
                put("featureKey", rawType.take(64))
            })
        }
        return regions to skipped
    }

    /** SPD 区域行 → GUI 特征（对齐 genes.ts mapGeneRegionsToFeatures：按 molecule 过滤、坐标校验）。 */
    internal fun mapGeneRegionsToFeatures(payload: Any?, sequenceLength: Int, molecule: String): List<Feature> {
        val arr = listItems(payload) ?: return emptyList()
        val out = mutableListOf<Feature>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (row.optString("molecule") != molecule) continue
            val start = if (row.has("startPosition")) row.optInt("startPosition", -1) else row.optInt("start", -1)
            val end = if (row.has("endPosition")) row.optInt("endPosition", -1) else row.optInt("end", -1)
            if (start < 1 || end < start || end > sequenceLength) continue
            val regionType = row.optString("regionType", "other").ifEmpty { "other" }
            val description = row.optString("description", "").trim()
            out += Feature(
                name = description.ifEmpty { regionType },
                type = regionType,
                start = start,
                end = end,
                strand = if (row.optString("strand") == "reverse") -1 else 1
            )
        }
        return out
    }

    /** 载入基因（对齐 genes.ts loadGene：dnaSequence 优先；RNA-only 记录 U→T 转为 DNA 画布序列）。 */
    fun loadGene(ctx: Context, id: String): Result<LoadedSpdGene> {
        val cfg = Cfg.of(ctx)
        val geneR = http(cfg, "GET", "/genes/" + URLEncoder.encode(id, "UTF-8"))
        val geneObj = geneR.value as? JSONObject ?: return Result(null, geneR.error ?: "基因响应格式异常")
        val gene = geneObj.optJSONObject("gene") ?: geneObj
        val dnaSequence = gene.optString("dnaSequence", "")
        val rnaSequence = gene.optString("rnaSequence", "")
        if (dnaSequence.isBlank() && rnaSequence.isBlank()) {
            return Result(null, "SPD gene has no DNA or RNA sequence")
        }
        val sourceMolecule = if (dnaSequence.isNotBlank()) "dna" else "rna"
        val raw = if (sourceMolecule == "dna") dnaSequence else rnaSequence
        val normalized = raw.replace(Regex("[ \\t\\n\\r\\f\\u000B]"), "").uppercase()
        if (normalized.isEmpty()) return Result(null, "gene sequence is empty")
        val sequence = if (sourceMolecule == "dna") normalized else normalized.replace("U", "T")

        val regionsR = http(cfg, "GET", "/genes/" + URLEncoder.encode(id, "UTF-8") + "/regions")
        val features = if (regionsR.error == null) mapGeneRegionsToFeatures(regionsR.value, sequence.length, sourceMolecule) else emptyList()

        val organism = optString(gene, "organismScientificName").ifEmpty {
            gene.optJSONObject("organism")?.optString("scientificName", "") ?: ""
        }
        return Result(
            LoadedSpdGene(
                geneId = gene.optString("id", id).ifEmpty { id },
                record = SeqRecord(
                    name = optString(gene, "name", "symbol").ifEmpty { "SPD-$id" },
                    sequence = sequence,
                    circular = gene.optString("topology") == "circular",
                    features = features,
                    description = com.spice.nativeapp.core.I18n.tf("SPD 基因 {v1}", id) + (if (organism.isNotBlank()) " · $organism" else "") +
                        (if (sourceMolecule == "rna") " · mRNA（U→T）" else "")
                ),
                sourceMolecule = sourceMolecule
            ), null
        )
    }

    data class LoadedSpdGene(val geneId: String, val record: SeqRecord, val sourceMolecule: String)

    /** 创建回执 → 基因 id（对齐 genes.ts extractGeneId：裸 {id} / {gene:{id}} / 信封 {data:{…}}）。 */
    internal fun extractGeneId(receipt: Any?): String {
        val root = receipt as? JSONObject ?: return ""
        optString(root, "geneId", "id").let { if (it.isNotBlank()) return it }
        root.optJSONObject("gene")?.let { g -> optString(g, "id", "geneId").let { if (it.isNotBlank()) return it } }
        root.optJSONObject("data")?.let { d ->
            optString(d, "geneId", "id").let { if (it.isNotBlank()) return it }
            d.optJSONObject("gene")?.let { g -> optString(g, "id", "geneId").let { if (it.isNotBlank()) return it } }
        }
        return ""
    }

    data class PublishGeneResult(
        val geneId: String,
        val regionCount: Int,
        val duplicateCount: Int,
        val skippedCount: Int,
        /** 基因已创建但区域批被拒时保留 id 供重试（对齐 genes.ts regionError 语义）。 */
        val regionError: String? = null
    )

    /** 发布基因 + 区域批（对齐 genes.ts publishGene：区域失败不丢弃已创建的基因）。 */
    fun publishGene(ctx: Context, record: SeqRecord): Result<PublishGeneResult> {
        val sequence = record.sequence.replace(Regex("[ \\t\\n\\r\\f\\u000B]"), "").uppercase()
        if (sequence.isEmpty()) return Result(null, "gene sequence is empty")
        val name = record.name.trim()
        if (name.isEmpty()) return Result(null, "gene name is required")
        val cfg = Cfg.of(ctx)
        val topology = if (record.circular) "circular" else "linear"
        val input = JSONObject().apply {
            put("name", name)
            put("geneType", "other")
            put("description", record.description.trim().take(400))
            put("dnaSequence", sequence)
            put("topology", topology)
            put("metadata", JSONObject().apply {
                put("source", "SPICE GUI")
                put("tool", "gene workbench")
                put("topology", topology)
            })
        }
        val createR = http(cfg, "POST", "/genes", input)
        if (createR.error != null) return Result(null, createR.error)
        val geneId = extractGeneId(createR.value)
        if (geneId.isBlank()) return Result(null, "SPD did not return a gene id")

        val (regions, skipped) = toGeneRegions(record.features, sequence.length)
        if (regions.length() == 0) {
            return Result(PublishGeneResult(geneId, 0, 0, skipped), null)
        }
        val regionR = http(cfg, "POST", "/genes/" + URLEncoder.encode(geneId, "UTF-8") + "/regions",
            JSONObject().put("regions", regions))
        if (regionR.error != null) {
            return Result(PublishGeneResult(geneId, 0, 0, skipped, regionR.error), null)
        }
        // 回执可能是 {regions:[…]} / {items:[…]} / 信封 {data:{…}}
        val rows = listItems(regionR.value) ?: JSONArray()
        val rootObj = regionR.value as? JSONObject
        var duplicateCount = -1
        for (src in listOfNotNull(rootObj, rootObj?.optJSONObject("data"))) {
            for (k in listOf("duplicateCount", "duplicates", "deduplicatedCount")) {
                if (src.has(k) && src.opt(k) is Int) { duplicateCount = src.optInt(k); break }
            }
            if (duplicateCount >= 0) break
        }
        if (duplicateCount < 0) {
            duplicateCount = (0 until rows.length()).count { i ->
                val row = rows.optJSONObject(i) ?: return@count false
                row.optBoolean("deduplicated", false) || !row.optBoolean("created", true)
            }
        }
        val hasCreatedFlag = (0 until rows.length()).any { i -> rows.optJSONObject(i)?.has("created") == true }
        val regionCount = if (hasCreatedFlag) (0 until rows.length()).count { i -> rows.optJSONObject(i)?.optBoolean("created") == true }
        else maxOf(0, regions.length() - duplicateCount)
        return Result(PublishGeneResult(geneId, regionCount, duplicateCount, skipped), null)
    }

    // ─────────────────────────── 载体库 ───────────────────────────

    data class VectorRow(val id: String, val name: String, val length: Int?, val kind: String?)

    /** 载体列表（对齐 client.listVectors：GET /vectors?q=…，data 为数组）。 */
    fun listVectors(ctx: Context, query: String = ""): Result<List<VectorRow>> {
        val q = if (query.isBlank()) "" else "?q=" + URLEncoder.encode(query, "UTF-8")
        val r = http(Cfg.of(ctx), "GET", "/vectors$q")
        if (r.error != null) return Result(null, r.error)
        val arr = listItems(r.value) ?: return Result(emptyList(), null)
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VectorRow(
                id = o.optString("id", ""),
                name = optString(o, "name", "displayName").ifEmpty { com.spice.nativeapp.core.I18n.t("(未命名)") },
                length = listOfNotNull(if (o.optInt("length", -1) > 0) o.optInt("length") else null,
                    if (o.optInt("lengthBp", -1) > 0) o.optInt("lengthBp") else null).firstOrNull(),
                kind = optString(o, "topology", "kind", "type").ifEmpty { null }
            )
        }
        return Result(rows, null)
    }

    /** FASTA/裸序列 → 纯序列（对齐 vectors.ts parseFastaSequence：丢 > 和 ; 头行，多行拼接）。 */
    internal fun parseFastaSequence(text: String): String {
        val lines = text.split(Regex("\\r?\\n"))
        val isFasta = lines.any { it.startsWith(">") }
        if (!isFasta) return text.replace(Regex("\\s+"), "").uppercase()
        return lines.filter { it.isNotBlank() && !it.startsWith(">") && !it.startsWith(";") }
            .joinToString("").uppercase()
    }

    /** 载入载体模板（对齐 vectors.ts loadVectorTemplate：详情 → sequenceArtifactId → 工件内容 → FASTA 解析）。 */
    fun loadVector(ctx: Context, id: String): Result<SeqRecord> {
        val cfg = Cfg.of(ctx)
        val vecR = http(cfg, "GET", "/vectors/" + URLEncoder.encode(id, "UTF-8"))
        val vecObj = vecR.value as? JSONObject ?: return Result(null, vecR.error ?: "载体响应格式异常")
        val vec = vecObj.optJSONObject("vector") ?: vecObj
        val artifactId = vec.optString("sequenceArtifactId", "")
        if (artifactId.isBlank()) return Result(null, com.spice.nativeapp.core.I18n.t("该载体未返回 sequenceArtifactId（无法取回序列）"))
        val textR = httpText(cfg, "/artifacts/" + URLEncoder.encode(artifactId, "UTF-8") + "/content")
        if (textR.error != null) return Result(null, textR.error)
        val seq = parseFastaSequence(textR.value ?: "")
        if (seq.isEmpty()) return Result(null, com.spice.nativeapp.core.I18n.t("该载体工件内容为空"))
        return Result(
            SeqRecord(
                name = optString(vec, "name", "displayName").ifEmpty { "SPD-$id" },
                sequence = seq,
                circular = vec.optString("topology") != "linear",
                features = emptyList(),
                description = com.spice.nativeapp.core.I18n.tf("SPD 载体 {v1}", id)
            ), null
        )
    }

    // ─────────────── 蛋白 fold 发布链（对齐 main protein/+page.svelte + spd/client.ts） ───────────────

    /** 协议版本号参与服务端身份去重，与 main spd/client.ts 常量一致。 */
    const val SPD_INPUT_PROTOCOL_VERSION = "spice-gui-input-v1"
    const val SPD_INFERENCE_PROTOCOL_VERSION = "spice-gui-fold-v1"

    private const val CACHE_PREF = "spice_settings"
    private const val CACHE_NS = "spice_spd"

    /** 对齐 normalize.ts normalizeSequence：去空白/数字/Fasta 头字符并大写。 */
    internal fun normalizeSequence(sequence: String): String =
        sequence.replace(Regex("[\\s\\d>]+"), "").uppercase()

    /** 对齐 normalize.ts sha256/sequenceSha256："sha256:<64 hex>"。 */
    fun sequenceSha256(sequence: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalizeSequence(sequence).toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** MD 环境（对齐 types.ts SpdEnvironmentInput：四个物理量必填）。 */
    data class EnvironmentInput(
        val ph: Double, val temperatureK: Double, val pressureBar: Double, val ionicStrengthM: Double
    ) {
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("ph", round(ph, 3))
            o.put("temperatureK", round(temperatureK, 2))
            o.put("pressureBar", round(pressureBar, 2))
            o.put("ionicStrengthM", round(ionicStrengthM, 3))
            return o
        }
        private fun round(v: Double, places: Int): Double {
            val f = Math.pow(10.0, places.toDouble())
            return Math.round(v * f) / f
        }
    }

    /** 模型身份（对齐 types.ts SpdModelInput；checkpointSha256 需为 sha256:<64 hex>）。 */
    data class ModelInput(
        val modelName: String,
        val modelVersion: String,
        val checkpointSha256: String,
        val inputProtocolVersion: String,
        val inferenceRuntime: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("modelName", modelName)
            put("modelVersion", modelVersion)
            put("checkpointSha256", checkpointSha256)
            put("inputProtocolVersion", inputProtocolVersion)
            put("inferenceRuntime", inferenceRuntime)
        }
    }

    /** 文件流式 SHA-256（对齐 spd.rs model_sha256：避免整块读入内存），返回 "sha256:<hex>"。 */
    fun fileSha256(path: String): Result<String> = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.File(path).inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        Result("sha256:" + digest.digest().joinToString("") { "%02x".format(it) }, null)
    } catch (e: Exception) {
        Result(null, e.message ?: e.toString())
    }

    /** 创建回执 → 实体 id（对齐 protein/+page.svelte spdReceiptId：receipt[entity].id 必在）。 */
    internal fun receiptEntityId(receipt: Any?, entity: String): String {
        val o = receipt as? JSONObject ?: return ""
        return o.optJSONObject(entity)?.optString("id", "") ?: ""
    }

    // ─────────────── 创建端点（幂等 upsert；回执为裸 {<entity>:{id}, created, deduplicated}） ───────────────

    fun createProtein(ctx: Context, preferredName: String, license: String = "CC-BY-4.0"): Result<JSONObject> {
        val body = JSONObject().put("preferredName", preferredName).put("license", license)
        val r = http(Cfg.of(ctx), "POST", "/proteins", body)
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    fun createSequence(ctx: Context, proteinId: String, sequence: String, sha256: String, license: String = "CC-BY-4.0"): Result<JSONObject> {
        val body = JSONObject()
            .put("proteinId", proteinId)
            .put("sequence", normalizeSequence(sequence))
            .put("sha256", sha256)
            .put("license", license)
        val r = http(Cfg.of(ctx), "POST", "/sequences", body)
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    /** fold 内嵌环境/模型身份的幂等 upsert（服务端据此解析去重行，create_fold 对陌生身份会 400）。 */
    fun createEnvironment(ctx: Context, environment: EnvironmentInput): Result<JSONObject> {
        val r = http(Cfg.of(ctx), "POST", "/environments", environment.toJson())
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    fun createModel(ctx: Context, model: ModelInput): Result<JSONObject> {
        val r = http(Cfg.of(ctx), "POST", "/models", model.toJson())
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    /**
     * 提交 fold（对齐 client.createFold / protein/+page.svelte publishToSpd）。
     * 只带轻量摘要——服务端 axum 请求体上限 2 MiB，distogram/接触矩阵与坐标数组留在本地。
     */
    fun createFold(
        ctx: Context,
        sequenceId: String,
        environment: EnvironmentInput,
        model: ModelInput,
        prediction: JSONObject
    ): Result<JSONObject> {
        val body = JSONObject().apply {
            put("sequenceId", sequenceId)
            put("environment", environment.toJson())
            put("model", model.toJson())
            put("inferenceProtocolVersion", SPD_INFERENCE_PROTOCOL_VERSION)
            put("prediction", prediction)
            put("provenance", JSONObject().put("source", "SPICE GUI").put("license", "CC-BY-4.0"))
        }
        val r = http(Cfg.of(ctx), "POST", "/folds", body)
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    /** 图查询 DSL（POST /query，对齐 client.graphQuery；返回 {tier, budgetUsed, budgetMax, results}）。 */
    fun graphQuery(ctx: Context, roots: JSONArray): Result<JSONObject> {
        val r = http(Cfg.of(ctx), "POST", "/query", JSONObject().put("roots", roots))
        return r.value?.let { Result(it as? JSONObject ?: JSONObject(), null) } ?: Result(null, r.error)
    }

    /** fold 详情（GET /folds/{id} → {fold:…}，供详情浮窗）。 */
    fun getFold(ctx: Context, id: String): Result<JSONObject> {
        val r = http(Cfg.of(ctx), "GET", "/folds/" + URLEncoder.encode(id, "UTF-8"))
        val o = r.value as? JSONObject ?: return Result(null, r.error ?: "fold 响应格式异常")
        return Result(o.optJSONObject("fold") ?: o, null)
    }

    // ─────────────── 序列↔公开 id 本地缓存（对齐 store.svelte.ts，键名一致） ───────────────
    // protein 行无服务端去重，故按归一化序列 SHA-256 前缀在本地缓存公开 id。

    private fun cachePrefs(ctx: Context) = ctx.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE)

    fun cachedProteinId(ctx: Context, seqKey: String): String =
        cachePrefs(ctx).getString("${CACHE_NS}_protein_$seqKey", "") ?: ""

    fun cachedSequenceId(ctx: Context, seqKey: String): String =
        cachePrefs(ctx).getString("${CACHE_NS}_sequence_$seqKey", "") ?: ""

    fun rememberProteinId(ctx: Context, seqKey: String, id: String) {
        cachePrefs(ctx).edit().putString("${CACHE_NS}_protein_$seqKey", id).apply()
    }

    fun rememberSequenceId(ctx: Context, seqKey: String, id: String) {
        cachePrefs(ctx).edit().putString("${CACHE_NS}_sequence_$seqKey", id).apply()
    }

    /** 全部已缓存的 sequence 条目（seqKey, id），供图查询批量根集。 */
    fun cachedSequenceEntries(ctx: Context): List<Pair<String, String>> {
        val prefs = cachePrefs(ctx)
        return prefs.all.entries
            .filter { it.key.startsWith("${CACHE_NS}_sequence_") && (it.value as? String)?.startsWith("spd:") == true }
            .map { it.key.removePrefix("${CACHE_NS}_sequence_") to it.value as String }
    }

    fun lastProteinName(ctx: Context): String = cachePrefs(ctx).getString("${CACHE_NS}_protein_name", "") ?: ""

    fun rememberProteinName(ctx: Context, name: String) {
        cachePrefs(ctx).edit().putString("${CACHE_NS}_protein_name", name).apply()
    }

    // ─────────────────────────── 酶库导出 ───────────────────────────

    /** 酶库导出（GET /restriction-enzymes/export，公开端点；data = {count, enzymes:[…]}）。 */
    fun exportEnzymeCount(ctx: Context): Result<Int> {
        val r = http(Cfg.of(ctx), "GET", "/restriction-enzymes/export")
        val o = r.value as? JSONObject ?: return Result(null, r.error ?: "酶库导出响应格式异常")
        val count = o.optInt("count", -1)
        if (count >= 0) return Result(count, null)
        val arr = o.optJSONArray("enzymes")
        return Result(arr?.length() ?: 0, null)
    }
}
