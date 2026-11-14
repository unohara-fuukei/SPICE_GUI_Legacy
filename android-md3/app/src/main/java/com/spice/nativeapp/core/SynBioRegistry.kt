package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * T7 块7：main src/lib/backend/externalDbs.ts 的 SynBioHub iGEM 注册库两函数 1:1 移植。
 * searchBioParts（原生搜索 API + 本地回退库）/ fetchPartSequence（SBOL XML <sbol:elements> 解析 + 默认库回退）。
 * 走 SPD 同源代理设置（SpdLibrary.proxySetting）；网络失败 → 本地 fallback registry（与 main catch 分支一致）。
 */
object SynBioRegistry {

    data class StandardBioPart(
        val partId: String,
        val partType: String,   // promoter / rbs / cds / terminator / ori
        val name: String,
        val shortDesc: String,
        val sequence: String,   // 懒加载（搜索时为空串）
        val creator: String,
        val registryUrl: String
    )

    data class SearchOutcome(val data: List<StandardBioPart>, val error: String? = null)

    // ───────────────────────── 本地回退库（main externalDbs.ts fallbackRegistry 原文）─────────────────────────

    private val FALLBACK_REGISTRY = listOf(
        StandardBioPart(
            "BBa_J23100", "promoter", "BBa_J23100",
            "Most robust constitutive E. coli promoter (high transcription)",
            "TTGACGGCTAGCTCAGTCCTAGGTACAGTGCTAGC",
            "Berkeley iGEM 2006", "http://parts.igem.org/Part:BBa_J23100"
        ),
        StandardBioPart(
            "BBa_B0034", "rbs", "BBa_B0034",
            "High efficiency ribosome binding site (Weiss design)",
            "AAAGAGGAGAA",
            "iGEM Registry", "http://parts.igem.org/Part:BBa_B0034"
        ),
        StandardBioPart(
            "BBa_E0040", "cds", "BBa_E0040 (GFP)",
            "Green Fluorescent Protein (codon-optimized for bacteria)",
            "ATGCGTAAAGGAGAAGAACTTTTCACTGGAGTTGTCCCAATTCTTGTTGAATTAGATGGTGATGTTAATGGGCACAAATTTTCTGTCAGTGGAGAGGGTGAAGGTGATGCAACATACGGAAAACTTACCCTTAAATTTATTTGCACTACTGGAAAACTACCTGTTCCGTGGCCAACACTTGTCACTACTTTCGGTTATGGTGTTCAATGCTTTGCGAGATACCCAGATCATATGAAACAGCATGACTTTTTCAAGAGTGCCATGCCCGAAGGTTATGTACAGGAAAGAACTATATTTTTCAAAGATGACGGGAACTACAAGACACGTGCTGAAGTCAAGTTTGAAGGTGATACCCTTGTTAATAGAATCGAGTTAAAAGGTATTGATTTTAAAGAAGATGGAAACATTCTTGGACACAAATTGGAATACAACTATAACTCACACAATGTATACATCATGGCAGACAAACAAAAGAATGGAATCAAAGTTAACTTCAAAATTAGACACAACATTGAAGATGGAAGCGTTCAACTAGCAGACCATTATCAACAAAATACTCCAATTGGCGATGGCCCTGTCCTTTTACCAGACAACCATTACCTGTCCACACAATCTGCCCTTTCGAAAGATCCCAACGAAAAGAGAGACCACATGGTCCTTCTTGAGTTTGTAACAGCTGCTGGATTACACATGGCATGGATGAACTATACAAATAA",
            "iGEM Registry", "http://parts.igem.org/Part:BBa_E0040"
        ),
        StandardBioPart(
            "BBa_B0015", "terminator", "BBa_B0015",
            "Double transcriptional terminator: B0010 + B0012",
            "CCAGGCATCAAATAAAACGAAAGGCTCAGTCGAAAGACTGGGCCTTTCGTTTTATCTGTTGTTTGTCGGTGAACGCTCTCCTGAGTAGGACAAATCCGCCGGGAGCGGATTTGAACGTTGCGAAGCAACGGCCCGGAGGGTGGCGGGCAGGACGCCCGCCATAAACTGCCAGGCATCAAATTAAGCAGAAGGCCATCCTGACGGATGGCCTTTTTGCGTTTCTACAAACTCTTTTG",
            "iGEM Registry", "http://parts.igem.org/Part:BBa_B0015"
        )
    )

    /** main fetchPartSequence 默认库（fetch 兜底；键集合与 fallbackRegistry 一致 + 'atgc' 默认） */
    private val FETCH_DEFAULTS = mapOf(
        "BBa_J23100" to "ttgacggctagctcagtcctaggtacagtgctagc",
        "BBa_B0034" to "aaagaggagaa",
        "BBa_E0040" to "atgcgtaaaggagaagaacttttcactggagttgtcccaattcttgttgaattagatggtgatgttaatgggcacaaattttctgtcagtggagagggtgaaggtgatgcaacatacggaaaacttacccttaaatttatttgcactactggaaaactacctgttccgtggccaacacttgtcactactttcggttatggtgttcaatgctttgcgagatacccagatcatatgaaacagcatgactttttcaagagtgccatgcccgaaggttatgtacaggaaagaactatatttttcaaagatgacgggaactacaagacacgtgctgaagtcaagtttgaaggtgatacccttgttaatagaatcgagttaaaaggtattgattttaaagaagatggaaacattcttggacacaaattggaatacaactataactcacacaatgtatacatcatggcagacaaacaaaagaatggaatcaaagttaacttcaaaattagacacaacattgaagatggaagcgttcaactagcagaccattatcaacaaaatactccaattggcgatggccctgtccttttaccagacaaccattacctgtccacacaatctgccctttcgaaagatcccaacgaaaagagagaccacatggtccttcttgagtttgtaacagctgctggattacacatggcatggatgaactatacaaataa",
        "BBa_B0015" to "ccaggcatcaaataaaacgaaaggctcagtcgaaagactgggcctttcgttttatctgttgtttgtcggtgaacgctctcctgagtaggacaaatccgccgggagcggatttgaacgttgcgaagcaacggcccggagggtggcgggcaggacgcccgccataaactgccaggcatcaaattaagcagaaggccatcctgacggatggcclttttgcgtttctacaaactcttttg"
    )

    // ───────────────────────── searchBioParts（externalDbs.ts:268-358）─────────────────────────

    /** main searchBioParts：SynBioHub 公共 iGEM 集合原生搜索；失败 → 本地回退库过滤（含 error 提示）。 */
    fun searchBioParts(query: String, filterType: String? = null, proxy: Proxy? = null): SearchOutcome {
        val cleanQuery = query.trim()
        val searchUrl = "https://synbiohub.org/public/igem/igem_collection/search/" +
            java.net.URLEncoder.encode(cleanQuery, "UTF-8") + "/?offset=0&limit=30"
        return try {
            val conn = (URL(searchUrl).openConnection(proxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw Exception("SynBioHub returned status $code")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseSearchResults(text, filterType)
                SearchOutcome(parsed)
            } finally {
                conn.disconnect()
            }
        } catch (err: Exception) {
            // Robust local fallback（离线可用性，与 main catch 分支一致）
            var filtered = FALLBACK_REGISTRY
            if (cleanQuery.isNotEmpty()) {
                filtered = filtered.filter { it.partId.lowercase().contains(cleanQuery.lowercase()) }
            }
            if (filterType != null) {
                filtered = filtered.filter { it.partType == filterType }
            }
            SearchOutcome(filtered, "Local registry fallback (SynBioHub offline: ${err.message})")
        }
    }

    private fun parseSearchResults(text: String, filterType: String?): List<StandardBioPart> {
        val arr = JSONArray(text)
        val out = mutableListOf<StandardBioPart>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val displayId = item.optString("displayId", "").ifEmpty { item.optString("name", "") }
            val desc = item.optString("description", "")
            val lowerDesc = desc.lowercase()
            var type = "cds"
            if (lowerDesc.contains("promoter")) type = "promoter"
            else if (lowerDesc.contains("rbs") || lowerDesc.contains("ribosome")) type = "rbs"
            else if (lowerDesc.contains("terminator")) type = "terminator"
            else if (lowerDesc.contains("origin") || lowerDesc.contains("replic")) type = "ori"
            if (filterType != null && type != filterType) continue
            out += StandardBioPart(
                partId = displayId,
                partType = type,
                name = displayId,
                shortDesc = desc.ifEmpty { "iGEM Standard Part" },
                sequence = "",
                creator = item.optString("creator", "iGEM Community"),
                registryUrl = item.optString("uri", "http://parts.igem.org/Part:$displayId")
            )
        }
        return out
    }

    // ───────────────────────── fetchPartSequence（externalDbs.ts:368-403）─────────────────────────

    /**
     * main fetchPartSequence：拉取 SBOL XML 并解析 <sbol:elements>（次选 <elements>）；
     * 失败 → 默认库（键不命中 → 'atgc'）。
     */
    fun fetchPartSequence(partId: String, proxy: Proxy? = null): String {
        val cleanId = partId.trim()
        val sbolUrl = "https://synbiohub.org/public/igem/$cleanId/1/sbol"
        return try {
            val conn = (URL(sbolUrl).openConnection(proxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw Exception("Failed to fetch SBOL for $partId")
                val xmlText = conn.inputStream.bufferedReader().use { it.readText() }
                // 标准标签 <sbol:elements>
                Regex("<sbol:elements>([atcgnATCGN\\s\\r\\n]+)</sbol:elements>").find(xmlText)?.let { m ->
                    return m.groupValues[1].replace(Regex("[\\s\\r\\n]+"), "").lowercase()
                }
                // 次选通用标签 <elements>
                Regex("<elements>([atcgnATCGN\\s\\r\\n]+)</elements>").find(xmlText)?.let { m ->
                    return m.groupValues[1].replace(Regex("[\\s\\r\\n]+"), "").lowercase()
                }
                throw Exception("Could not find sequence elements in SBOL payload.")
            } finally {
                conn.disconnect()
            }
        } catch (err: Exception) {
            // Standard offline library fallback（main console.warn + defaults）
            return FETCH_DEFAULTS[cleanId] ?: "atgc"
        }
    }
}
