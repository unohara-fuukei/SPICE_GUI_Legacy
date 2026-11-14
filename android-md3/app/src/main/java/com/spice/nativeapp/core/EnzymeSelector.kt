package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONObject

/**
 * 限制酶选择器核心 — 1:1 复刻 main `src/lib/gene/EnzymeSelector.svelte` 与
 * `src/routes/gene/+page.svelte` 的算法层：
 *
 * - [EnzymeMethylation.findMethylatedRegions]：main genome/enzymes.ts findMethylatedRegions
 *   （Dam GATC / Dcm CC[AT]GG / EcoKI AAC(N6)GTGC + GCAC(N6)GTT 扫描，环状双倍序列，%len 坐标归一）
 * - [EnzymeMethylation.checkCutsiteMethylation]：main checkCutsiteMethylation
 *   （ENZYME_METHYLATION_SENSITIVITY 敏感表 + 环状区间重叠检查 + ClaI/XbaI/TaqI 上下文特判 +
 *   MboI/BclI 全阻断 + DpnI 依赖 Dam 的 requiredButUnmethylated 分支）
 * - [EnzymeSelector.colorOf]：main +page.svelte getEnzymeColor（名称哈希 → 10 色 pix 色板）
 * - [EnzymeSelector.database]：main enzymeDatabase（动态 REBASE 同步库）→ 安卓内置 EnzymeDb
 *   全库（532 组 / 3732 同义名，别名键与标准名同条目；seq/cut/isBlunt/color 逐字段等价）
 * - [EnzymeSelector.restrictionSites]：main +page.svelte:2001 restrictionSites 推导
 *   （COMMON_ENZYMES ∪ selectedEnzymes → resolveEnzymeFor → OVE 切点引擎(circular=true 硬编码) →
 *   Dam/Dcm/EcoKI 位点索引集重叠 → methylated/methylationType → pos 排序）
 * - [EnzymeSelector.PRESETS]：9 组系统预设 verbatim
 * - [EnzymeSelector.bufferCompatibility]：main genome/enzymes.ts ENZYME_BUFFERS 36 行表 +
 *   getEnzymeBufferCompatibility（SharedPreferences 'spice_neb_buffers' 可覆盖，未命中回默认表，
 *   未知酶回 {100,75,100,75,CutSmart}）
 *
 * 平台差异（披露）：main 的 enzymeDatabase 由 REBASE/SPD 网络同步写 localStorage（getInitialEnzymeDb /
 * applyEnzymeRows），安卓无同步链路，直接以内置 EnzymeDb 全库作为等价持久库（既有披露惯例）。
 */
object EnzymeMethylation {

    /** main MethylatedRegion */
    data class Region(val start: Int, val end: Int, val type: String, val motif: String)

    /** main MethylationStatus */
    data class Status(val isBlocked: Boolean, val requiredButUnmethylated: Boolean, val type: String? = null, val motif: String? = null) {
        companion object { val NONE = Status(false, false) }
    }

    /** main ENZYME_METHYLATION_SENSITIVITY（键为酶名小写；值 dam/dcm/ecoki: blocked|required） */
    private val SENSITIVITY: Map<String, Map<String, String>> = mapOf(
        "clai" to mapOf("dam" to "blocked"),    // ClaI: GATCGAT / ATCGATC 上下文阻断
        "mboi" to mapOf("dam" to "blocked"),    // MboI: 任一 Dam 重叠即全阻断
        "dpni" to mapOf("dam" to "required"),   // DpnI: 需 Dam 甲基化才切割
        "xbai" to mapOf("dam" to "blocked"),    // XbaI: GATCTAGA / TCTAGATC 上下文阻断
        "bcli" to mapOf("dam" to "blocked"),    // BclI: TGATCA 含 GATC 全阻断
        "taqi" to mapOf("dam" to "blocked"),    // TaqI: GATCGA / TCGATC 上下文阻断
        "hpa2" to mapOf("dcm" to "blocked"),    // HpaII/MspI: Dcm/CpG 上下文阻断
        "ecor2" to mapOf("dcm" to "blocked"),   // EcoRII: CCWGG 全阻断
        "stui" to mapOf("dcm" to "blocked"),    // StuI: CCAGGCCTGG 侧翼阻断
        "sexai" to mapOf("dcm" to "blocked"),   // SexAI: ACCWGGT 全阻断
        "bsu36i" to mapOf("dcm" to "blocked"),  // Bsu36I: CCWGG 重叠阻断
        "ecoki" to mapOf("ecoki" to "blocked")  // EcoKI: 自身系统未甲基化即阻断
    )

    /** main findMethylatedRegions：扫描 Dam/Dcm/EcoKI 甲基化目标区（环状双倍序列 + %len 归一） */
    fun findMethylatedRegions(sequence: String, circular: Boolean): List<Region> {
        val regions = mutableListOf<Region>()
        val seqUpper = sequence.uppercase()
        val len = sequence.length
        if (len == 0) return regions
        val searchSeq = if (circular) seqUpper + seqUpper else seqUpper

        // 1. Dam: GATC（4 bp，回文，腺嘌呤甲基化）
        val damRegex = Regex("GATC")
        for (m in damRegex.findAll(searchSeq)) {
            val start = m.range.first
            val end = start + 3
            if (start < len) regions.add(Region(start % len, end % len, "Dam", "GATC"))
        }
        // 2. Dcm: CCWGG（5 bp，第二胞嘧啶甲基化）
        val dcmRegex = Regex("CC[AT]GG")
        for (m in dcmRegex.findAll(searchSeq)) {
            val start = m.range.first
            val end = start + 4
            if (start < len) regions.add(Region(start % len, end % len, "Dcm", searchSeq.substring(start, start + 5)))
        }
        // 3. EcoKI: AAC(N6)GTGC / GCAC(N6)GTT（13 bp）
        val ecoKiFwd = Regex("AAC[ATGCN].{5}GTGC")
        for (m in ecoKiFwd.findAll(searchSeq)) {
            val start = m.range.first
            val end = start + 12
            if (start < len) regions.add(Region(start % len, end % len, "EcoKI", searchSeq.substring(start, start + 13)))
        }
        val ecoKiRev = Regex("GCAC[ATGCN].{5}GTT")
        for (m in ecoKiRev.findAll(searchSeq)) {
            val start = m.range.first
            val end = start + 12
            if (start < len) regions.add(Region(start % len, end % len, "EcoKI", searchSeq.substring(start, start + 13)))
        }
        return regions
    }

    /** 环状/线性序列上两区间是否重叠（main isOverlapping：逐碱基集合求交） */
    private fun isOverlapping(s1: Int, e1: Int, s2: Int, e2: Int, seqLen: Int): Boolean {
        fun bases(s: Int, e: Int): Set<Int> {
            val set = mutableSetOf<Int>()
            var curr = s
            while (curr != e) {
                set.add(curr)
                curr = (curr + 1) % seqLen
            }
            set.add(e)
            return set
        }
        val set1 = bases(s1, e1)
        val set2 = bases(s2, e2)
        return set1.any { it in set2 }
    }

    /**
     * main checkCutsiteMethylation：重叠感知的甲基化敏感检查。
     * @param name 切点酶名（main 取 cutsite.name，小写查敏感表）
     * @param siteStart/siteEnd 识别位点区间（main cutsite.recognitionSiteRange.start/end）
     */
    fun checkCutsiteMethylation(name: String, siteStart: Int, siteEnd: Int, sequence: String, circular: Boolean, regions: List<Region>): Status {
        val enzymeName = name.lowercase()
        val sensitivity = SENSITIVITY[enzymeName] ?: return Status.NONE
        val seqLen = sequence.length
        if (seqLen == 0) return Status.NONE

        // 找出与识别位点重叠的甲基化区
        for (region in regions) {
            if (isOverlapping(siteStart, siteEnd, region.start, region.end, seqLen)) {
                if (region.type == "Dam") {
                    if (sensitivity["dam"] == "blocked") {
                        // MboI / BclI：任一重叠即阻断
                        if (enzymeName == "mboi" || enzymeName == "bcli") {
                            return Status(true, false, "Dam", "GATC")
                        }
                        // ClaI：ATCGAT 前接 G 或后接 TC（GATCGAT / ATCGATC）才阻断
                        if (enzymeName == "clai") {
                            val prevChar = sequence[(siteStart - 1 + seqLen) % seqLen].uppercaseChar()
                            val nextChars = (sequence[(siteEnd + 1) % seqLen].toString() + sequence[(siteEnd + 2) % seqLen].toString()).uppercase()
                            if (prevChar == 'G' || nextChars == "TC") {
                                return Status(true, false, "Dam", "GATC")
                            }
                        }
                        // XbaI：TCTAGA 前接 GA（GATCTAGA）或后接 TC（TCTAGATC）才阻断
                        if (enzymeName == "xbai") {
                            val prevChar2 = (sequence[(siteStart - 2 + seqLen) % seqLen].toString() + sequence[(siteStart - 1 + seqLen) % seqLen].toString()).uppercase()
                            val nextChars = (sequence[(siteEnd + 1) % seqLen].toString() + sequence[(siteEnd + 2) % seqLen].toString()).uppercase()
                            if (prevChar2 == "GA" || nextChars == "TC") {
                                return Status(true, false, "Dam", "GATC")
                            }
                        }
                        // TaqI：TCGA 前接 GA（GATCGA）或后接 TC（TCGATC）才阻断
                        if (enzymeName == "taqi") {
                            val prevChar2 = (sequence[(siteStart - 2 + seqLen) % seqLen].toString() + sequence[(siteStart - 1 + seqLen) % seqLen].toString()).uppercase()
                            val nextChars = (sequence[(siteEnd + 1) % seqLen].toString() + sequence[(siteEnd + 2) % seqLen].toString()).uppercase()
                            if (prevChar2 == "GA" || nextChars == "TC") {
                                return Status(true, false, "Dam", "GATC")
                            }
                        }
                        // 其余 Dam 敏感酶：默认阻断
                        return Status(true, false, "Dam", "GATC")
                    }
                } else if (region.type == "Dcm") {
                    if (sensitivity["dcm"] == "blocked") {
                        return Status(true, false, "Dcm", region.motif)
                    }
                } else if (region.type == "EcoKI") {
                    if (sensitivity["ecoki"] == "blocked") {
                        return Status(true, false, "EcoKI", region.motif)
                    }
                }
            }
        }

        // DpnI 需要 Dam 甲基化：无 Dam 重叠时标记 requiredButUnmethylated
        if (enzymeName == "dpni" && sensitivity["dam"] == "required") {
            var hasDamOverlap = false
            for (region in regions) {
                if (region.type == "Dam" && isOverlapping(siteStart, siteEnd, region.start, region.end, seqLen)) {
                    hasDamOverlap = true
                    break
                }
            }
            if (!hasDamOverlap) {
                return Status(false, true, "Dam", "GATC")
            }
        }
        return Status.NONE
    }
}

object EnzymeSelector {

    /** main enzymeDatabase 条目 { seq, cut, color, isBlunt } */
    data class DbEntry(val seq: String, val cut: Int, val color: String, val isBlunt: Boolean)

    /** 面板展示用切点行（main restrictionSites 元素的展示子集） */
    data class Site(
        val name: String,
        val pos: Int,               // 1-based 上链切点（topSnipPosition + 1）
        val seq: String,            // 识别序列（大写）
        val color: String,          // hex 或 'var(--pix-red)' / 'var(--pix-accent)'
        val methylated: Boolean,
        val methylationType: String? // 'Dam' / 'Dcm' / 'EcoKI' / 'Dam/Dcm'…
    )

    /** main NEB BufferCompatibility */
    data class BufferCompatibility(
        val cutSmart: Int, val r1_1: Int, val r2_1: Int, val r3_1: Int, val preferredBuffer: String
    )

    /** main +page.svelte getEnzymeColor：名称哈希 → pix 10 色板。
     *  JS 语义精确复刻：`hash = code + ((hash << 5) - hash)` 中仅 `<<` 做 ToInt32 截断，
     *  加减以 float64 累加不回绕 —— 故用 Long 累加、每步对旧值 toInt() 截断再 shl。 */
    fun colorOf(name: String): String {
        var hash = 0L
        for (ch in name) {
            val h32 = hash.toInt()                    // ToInt32(hash)
            hash = ch.code.toLong() + ((h32 shl 5).toLong() - hash)
        }
        val colors = listOf(
            "#ff5d5d", "#ff9f1c", "#ffd23f", "#4cd6ff", "#b48cff",
            "#53d769", "#ff5df6", "#53d7a9", "#ff9e9e", "#5df6ff"
        )
        val idx = kotlin.math.abs((hash % 10).toInt())
        return colors[idx]
    }

    /** 酶全库（main REBASE 同步库口径）：标准名 + 全部同义名 → 条目（颜色按各键名哈希，等价 main 逐行着色） */
    val database: Map<String, DbEntry> by lazy {
        val db = mutableMapOf<String, DbEntry>()
        for (e in Enzymes.catalog) {
            val entry = DbEntry(e.site.uppercase(), e.cutOffset, colorOf(e.name), e.cutOffset == e.bottomCut)
            db[e.name] = entry
            for (a in e.aliases) db[a] = DbEntry(e.site.uppercase(), e.cutOffset, colorOf(a), e.cutOffset == e.bottomCut)
        }
        db
    }

    /** main EnzymeSelector PRESETS verbatim（9 组公司/流程预设） */
    val PRESETS: List<Pair<String, List<String>>> = listOf(
        "NEB Standard" to listOf("EcoRI", "BamHI", "HindIII", "SacI", "SalI", "KpnI", "PstI", "NotI", "XbaI"),
        "Thermo Fisher" to listOf("EcoRI", "BamHI", "HindIII", "XhoI", "XmaI", "PstI", "NotI", "SacI", "SalI"),
        "Golden Gate (Type IIS)" to listOf("BsaI", "BbsI", "BsmBI", "SapI", "BspQI", "AarI"),
        "Rare Cutters (8bp+)" to listOf("NotI", "AscI", "FseI", "PacI", "PmeI", "SbfI", "SfiI", "SgrAI"),
        "BioBrick Standard" to listOf("EcoRI", "XbaI", "SpeI", "PstI"),
        "MoClo Assembly" to listOf("BsaI", "BbsI", "BsmBI", "SapI", "PaqCI"),
        "Blunt End Cutters" to listOf("EcoRV", "SmaI", "HpaI", "DraI", "PvuII", "ScaI", "StuI", "NaeI"),
        "Methylation-Insensitive" to listOf("EcoRI", "BamHI", "HindIII", "KpnI", "SacI", "NcoI", "PstI"),
        "Standard Lab Cloning" to listOf("EcoRI", "BamHI", "HindIII", "SalI", "XhoI", "KpnI", "SacI", "XbaI", "NcoI", "NdeI", "PstI")
    )

    /** main resolveEnzymeFor：tg-oss 默认库优先，REBASE 行（EnzymeDb）兜底 enzymeFromSite */
    fun resolveEnzymeFor(name: String): EnzymesVe.VeEnzyme? {
        EnzymesVe.getEnzymeByName(name)?.let { return it }
        val e = Enzymes.byName(name) ?: return null
        return EnzymesVe.enzymeFromSite(name, e.site.uppercase(), e.cutOffset, e.cutOffset == e.bottomCut)
    }

    /**
     * main +page.svelte:2001 restrictionSites 推导 1:1：
     * COMMON_ENZYMES ∪ selectedEnzymes → OVE 引擎（circular=true 硬编码，main 同此）→
     * Dam/Dcm/EcoKI 位点索引集与识别序列逐位重叠 → 甲基化标注 → pos 升序。
     */
    fun restrictionSites(dnaSeq: String, selectedEnzymes: Set<String>): List<Site> {
        val sites = mutableListOf<Site>()
        val upperDna = dnaSeq.uppercase()

        val allNames = (Enzymes.mapCommon + selectedEnzymes).distinct()
        val enzymes = allNames.mapNotNull { resolveEnzymeFor(it) }

        // Dam 甲基化目标 GATC（indexOf 链 = 允许重叠，main 同）
        val damPositions = mutableSetOf<Int>()
        var damPos = upperDna.indexOf("GATC")
        while (damPos != -1) {
            for (i in 0 until 4) damPositions.add(damPos + i)
            damPos = upperDna.indexOf("GATC", damPos + 1)
        }
        // Dcm 甲基化目标 CCWGG（正则 /g = 不重叠，main 同）
        val dcmPositions = mutableSetOf<Int>()
        for (m in Regex("CC[AT]GG").findAll(upperDna)) {
            val start = m.range.first
            for (i in 0 until 5) dcmPositions.add(start + i)
        }
        // EcoKI 甲基化目标 AAC(N6)GTGC / GCAC(N6)GTT（正则 /g = 不重叠，main 同）
        val ecokiPositions = mutableSetOf<Int>()
        for (rx in listOf(Regex("AAC[ATGCN].{5}GTGC"), Regex("GCAC[ATGCN].{5}GTT"))) {
            for (m in rx.findAll(upperDna)) {
                val start = m.range.first
                for (i in 0 until 13) ecokiPositions.add(start + i)
            }
        }

        val cutsites = EnzymesVe.getCutsitesFromSequenceFlat(dnaSeq, true, enzymes)

        for (cs in cutsites) {
            val db = database[cs.name]
            val siteSeq = cs.enzyme.site.uppercase().ifEmpty { db?.seq ?: "" }
            var isDam = false
            var isDcm = false
            var isEcoKI = false
            for (i in 0 until siteSeq.length) {
                val idx = cs.recognitionSiteRange.start + i
                if (idx in damPositions) isDam = true
                if (idx in dcmPositions) isDcm = true
                if (idx in ecokiPositions) isEcoKI = true
            }
            val methylated = isDam || isDcm || isEcoKI
            val mTypes = mutableListOf<String>()
            if (isDam) mTypes.add("Dam")
            if (isDcm) mTypes.add("Dcm")
            if (isEcoKI) mTypes.add("EcoKI")

            sites.add(
                Site(
                    name = cs.name,
                    pos = cs.topSnipPosition + 1,   // 1-based 切点显示（main topSnipPosition + 1）
                    seq = siteSeq,
                    color = if (methylated) "var(--pix-red)" else (db?.color ?: "var(--pix-accent)"),
                    methylated = methylated,
                    methylationType = if (mTypes.isNotEmpty()) mTypes.joinToString("/") else null
                )
            )
        }
        sites.sortBy { it.pos }
        return sites
    }

    // ───────────────── NEB 缓冲液活性表（main genome/enzymes.ts ENZYME_BUFFERS 36 行 verbatim） ─────────────────

    private val DEFAULT_BUFFERS: Map<String, BufferCompatibility> = mapOf(
        "EcoRI" to BufferCompatibility(100, 25, 100, 100, "CutSmart"),
        "BamHI" to BufferCompatibility(100, 75, 100, 100, "CutSmart"),
        "HindIII" to BufferCompatibility(100, 50, 100, 50, "CutSmart"),
        "SalI" to BufferCompatibility(100, 10, 100, 100, "Buffer 3.1"),
        "XhoI" to BufferCompatibility(100, 75, 100, 100, "CutSmart"),
        "NdeI" to BufferCompatibility(100, 75, 100, 100, "CutSmart"),
        "SacI" to BufferCompatibility(100, 10, 100, 100, "CutSmart"),
        "KpnI" to BufferCompatibility(100, 25, 100, 100, "CutSmart"),
        "BsaI" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "BbsI" to BufferCompatibility(100, 10, 100, 100, "CutSmart"),
        "BsmBI" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "NotI" to BufferCompatibility(100, 10, 100, 100, "CutSmart"),
        "XbaI" to BufferCompatibility(100, 25, 100, 100, "CutSmart"),
        "PstI" to BufferCompatibility(100, 75, 100, 100, "CutSmart"),
        "SmaI" to BufferCompatibility(50, 50, 25, 10, "Buffer 1.1"),
        "SpeI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "ClaI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "BglII" to BufferCompatibility(10, 10, 100, 100, "Buffer 3.1"),
        "EcoRV" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "NcoI" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "MluI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "ScaI" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "AflII" to BufferCompatibility(100, 75, 100, 100, "CutSmart"),
        "AvrII" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "ApaI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "SapI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "AarI" to BufferCompatibility(100, 10, 50, 100, "Buffer 3.1"),
        "XmaI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "PvuI" to BufferCompatibility(100, 10, 100, 100, "CutSmart"),
        "PvuII" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "DpnI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "AluI" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "HaeIII" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "SphI" to BufferCompatibility(100, 50, 100, 100, "CutSmart"),
        "HincII" to BufferCompatibility(100, 100, 100, 100, "CutSmart"),
        "FokI" to BufferCompatibility(100, 100, 100, 100, "CutSmart")
    )

    private const val PREFS_KEY = "spice_neb_buffers"

    /**
     * main getEnzymeBufferCompatibility：SharedPreferences 'spice_neb_buffers' 可覆盖表
     * （缺省时以默认表初始化持久层），大小写不敏感查名，未知酶回
     * { cutSmart 100, r1_1 75, r2_1 100, r3_1 75, preferredBuffer 'CutSmart' }。
     */
    fun bufferCompatibility(name: String, ctx: Context? = null): BufferCompatibility {
        val clean = name.trim()
        var buffers: Map<String, BufferCompatibility> = DEFAULT_BUFFERS
        if (ctx != null) {
            val prefs = ctx.getSharedPreferences("spice_settings", Context.MODE_PRIVATE)
            val saved = prefs.getString(PREFS_KEY, null)
            if (saved != null) {
                try {
                    val obj = JSONObject(saved)
                    val parsed = mutableMapOf<String, BufferCompatibility>()
                    for (k in obj.keys()) {
                        val v = obj.getJSONObject(k)
                        parsed[k] = BufferCompatibility(
                            v.getInt("cutSmart"), v.getInt("r1_1"), v.getInt("r2_1"),
                            v.getInt("r3_1"), v.getString("preferredBuffer")
                        )
                    }
                    if (parsed.isNotEmpty()) buffers = parsed
                } catch (_: Exception) { /* main 同：解析失败回默认表 */ }
            } else {
                // 以全表初始化持久层（main localStorage.setItem 同语义）
                val obj = JSONObject()
                for ((k, v) in DEFAULT_BUFFERS) {
                    val e = JSONObject()
                    e.put("cutSmart", v.cutSmart); e.put("r1_1", v.r1_1); e.put("r2_1", v.r2_1)
                    e.put("r3_1", v.r3_1); e.put("preferredBuffer", v.preferredBuffer)
                    obj.put(k, e)
                }
                prefs.edit().putString(PREFS_KEY, obj.toString()).apply()
            }
        }
        val foundKey = buffers.keys.firstOrNull { it.equals(clean, ignoreCase = true) }
        return foundKey?.let { buffers[it] } ?: BufferCompatibility(100, 75, 100, 75, "CutSmart")
    }
}
