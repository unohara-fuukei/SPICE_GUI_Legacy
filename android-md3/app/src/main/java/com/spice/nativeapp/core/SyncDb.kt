package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 数据库同步（1:1 对齐 main src-tauri/src/cloning.rs sync_rebase_db / sync_card_amr_db /
 * sync_biosecurity_db）：真实拉取 + 本地缓存（拉取失败/解析为空时回退缓存，再回退内置条目）。
 * 三个函数均为阻塞调用，请在 IO 线程执行；错误向上抛给调用方按 toast*Failed 文案上屏。
 */
object SyncDb {

    data class EnzymeData(val name: String, val motif: String, val cutIndex: Int, val isBlunt: Boolean)
    data class CardAmrData(val gene: String, val resistanceTo: String, val pattern: String)
    data class BiosecurityData(val agent: String, val pattern: String, val description: String)

    private const val PREF = "spice_sync_db"

    private fun httpGet(url: String, timeoutSec: Int = 6): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutSec * 1000
            readTimeout = timeoutSec * 1000
            setRequestProperty("User-Agent", "SPICE-GUI-Native/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().decodeToString() }
        } finally { conn.disconnect() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun fetchFirst(urls: List<String>): String? {
        for (url in urls) { httpGet(url)?.let { return it } }
        return null
    }

    private fun cacheLoad(ctx: Context, key: String): JSONArray? = runCatching {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, null) ?: return null
        JSONArray(s)
    }.getOrNull()

    private fun cacheSave(ctx: Context, key: String, arr: JSONArray) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }

    // ─────────────────────────── REBASE ───────────────────────────

    private val REBASE_FALLBACK = listOf(
        EnzymeData("EcoRI", "GAATTC", 1, false), EnzymeData("BamHI", "GGATCC", 1, false),
        EnzymeData("HindIII", "AAGCTT", 1, false), EnzymeData("SalI", "GTCGAC", 1, false),
        EnzymeData("KpnI", "GGTACC", 5, false), EnzymeData("XhoI", "CTCGAG", 1, false),
        EnzymeData("NdeI", "CATATG", 2, false), EnzymeData("SacI", "GAGCTC", 5, false),
        EnzymeData("BsaI", "GGTCTC", 1, false), EnzymeData("ClaI", "ATCGAT", 2, false),
        EnzymeData("XbaI", "TCTAGA", 1, false), EnzymeData("MboI", "GATC", 0, false),
    )

    /** 对齐 Rust：双 URL 拉取 → 行正则解析（^ 优先，否则 max(cut,0)，isBlunt=cut==0）→ 缓存 → 双重回退。 */
    fun syncRebase(ctx: Context): List<EnzymeData> {
        val text = fetchFirst(listOf(
            "http://rebase.neb.com/rebase/link_bionet",
            "https://raw.githubusercontent.com/biopython/biopython/master/Tests/REBASE/bionet.txt",
        ))
        if (text != null) {
            val re = Regex("^([A-Za-z0-9\\-]+)\\s+([A-Z^]+)\\s+(-?\\d+)")
            val enzymes = mutableListOf<EnzymeData>()
            for (line in text.lineSequence()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val caps = re.find(line) ?: continue
                val name = caps.groupValues[1]
                var rawMotif = caps.groupValues[2]
                val rawCut = caps.groupValues[3].toIntOrNull() ?: 0
                val caretIdx = rawMotif.indexOf('^')
                val cutIndex = if (caretIdx >= 0) { rawMotif = rawMotif.removeRange(caretIdx, caretIdx + 1); caretIdx } else maxOf(rawCut, 0)
                enzymes.add(EnzymeData(name, rawMotif, cutIndex, rawCut == 0))
            }
            if (enzymes.isNotEmpty()) {
                val arr = JSONArray()
                enzymes.forEach { arr.put(JSONObject().put("name", it.name).put("motif", it.motif).put("cutIndex", it.cutIndex).put("isBlunt", it.isBlunt)) }
                cacheSave(ctx, "rebase_db", arr)
                return enzymes
            }
        }
        cacheLoad(ctx, "rebase_db")?.let { cached ->
            runCatching {
                val out = mutableListOf<EnzymeData>()
                for (i in 0 until cached.length()) {
                    val o = cached.optJSONObject(i) ?: continue
                    out.add(EnzymeData(o.optString("name"), o.optString("motif"), o.optInt("cutIndex"), o.optBoolean("isBlunt")))
                }
                if (out.isNotEmpty()) return out
            }
        }
        return REBASE_FALLBACK
    }

    // ─────────────────────────── CARD AMR ───────────────────────────

    private val CARD_FALLBACK = listOf(
        CardAmrData("blaTEM-1", "Beta-lactam (Penicillins)", "ATCAGTCAACC"),
        CardAmrData("kanR (aph(3')-Ia)", "Aminoglycoside (Kanamycin)", "ATGAGCCATATTCAACGGG"),
        CardAmrData("tet(A)", "Tetracycline", "GTGATCCTGGG"),
        CardAmrData("catA1 (CmR)", "Phenicol (Chloramphenicol)", "ATGGAGAAAAAAATCACT"),
        CardAmrData("aac(6')-Ib-cr", "Aminoglycoside, Fluoroquinolone", "CCGCTCGTGT"),
    )

    private fun parseCardAmr(text: String): List<CardAmrData> = runCatching {
        val arr = JSONArray(text)
        val out = mutableListOf<CardAmrData>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val gene = o.optString("gene"); val resistanceTo = o.optString("resistanceTo"); val pattern = o.optString("pattern")
            if (gene.isNotBlank() && pattern.isNotBlank()) out.add(CardAmrData(gene, resistanceTo, pattern))
        }
        out
    }.getOrDefault(emptyList())

    fun syncCardAmr(ctx: Context): List<CardAmrData> {
        val text = fetchFirst(listOf("https://raw.githubusercontent.com/card-mcmaster/card-database/master/card_amr_signatures.json"))
        if (text != null) {
            val data = parseCardAmr(text)
            if (data.isNotEmpty()) {
                val arr = JSONArray()
                data.forEach { arr.put(JSONObject().put("gene", it.gene).put("resistanceTo", it.resistanceTo).put("pattern", it.pattern)) }
                cacheSave(ctx, "card_amr_db", arr)
                return data
            }
        }
        cacheLoad(ctx, "card_amr_db")?.let { cached ->
            runCatching {
                val out = mutableListOf<CardAmrData>()
                for (i in 0 until cached.length()) {
                    val o = cached.optJSONObject(i) ?: continue
                    out.add(CardAmrData(o.optString("gene"), o.optString("resistanceTo"), o.optString("pattern")))
                }
                if (out.isNotEmpty()) return out
            }
        }
        return CARD_FALLBACK
    }

    // ─────────────────────────── IGSC 生物安全 ───────────────────────────

    private val IGSC_FALLBACK by lazy { listOf(
        BiosecurityData(I18n.t("蓖麻毒素 (Ricin A-Chain Activity Center)"), "TCTGGAGCGCATGATT",
            I18n.t("受控毒素 (Ricin A-chain rRNA N-glycosidase active site)。具有极其致命的核糖体灭活细胞毒性。")),
        BiosecurityData(I18n.t("肉毒毒素 (Botulinum Neurotoxin Core)"), "TTTGGATCCGGATAC",
            I18n.t("受控毒素 (Botulinum neurotoxin light chain zinc-metalloprotease active loop)。抑制乙酰胆碱释放，导致致死性肌肉麻痹。")),
        BiosecurityData(I18n.t("炭疽毒素致死因子 (Anthrax Lethal Factor)"), "ATGAAACACGAAAA",
            I18n.t("一类致病因 (Bacillus anthracis lethal factor zinc protease domain)。降解 MAPKK，造成急性细胞凋亡。")),
        BiosecurityData(I18n.t("天花病毒血凝素 (Variola Major Envelope)"), "TATAATGAGTCACA",
            I18n.t("国家一类管制烈性病毒 (Poxvirus Variola hemagglutinin envelope gene segment)。高致死性呼吸道及全身传染病。")),
    ) }

    private fun parseBiosecurity(text: String): List<BiosecurityData> = runCatching {
        val arr = JSONArray(text)
        val out = mutableListOf<BiosecurityData>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val agent = o.optString("agent"); val pattern = o.optString("pattern"); val description = o.optString("description")
            if (agent.isNotBlank() && pattern.isNotBlank()) out.add(BiosecurityData(agent, pattern, description))
        }
        out
    }.getOrDefault(emptyList())

    fun syncBiosecurity(ctx: Context): List<BiosecurityData> {
        val text = fetchFirst(listOf("https://raw.githubusercontent.com/igsc/biosecurity-database/master/biosecurity_signatures.json"))
        if (text != null) {
            val data = parseBiosecurity(text)
            if (data.isNotEmpty()) {
                val arr = JSONArray()
                data.forEach { arr.put(JSONObject().put("agent", it.agent).put("pattern", it.pattern).put("description", it.description)) }
                cacheSave(ctx, "biosecurity_db", arr)
                return data
            }
        }
        cacheLoad(ctx, "biosecurity_db")?.let { cached ->
            runCatching {
                val out = mutableListOf<BiosecurityData>()
                for (i in 0 until cached.length()) {
                    val o = cached.optJSONObject(i) ?: continue
                    out.add(BiosecurityData(o.optString("agent"), o.optString("pattern"), o.optString("description")))
                }
                if (out.isNotEmpty()) return out
            }
        }
        return IGSC_FALLBACK
    }
}
