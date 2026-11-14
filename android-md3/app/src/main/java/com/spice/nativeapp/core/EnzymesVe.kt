package com.spice.nativeapp.core

/**
 * 限制酶切点引擎 — 1:1 复刻 main `genome/enzymes.ts`（TeselaGen tg-oss MIT 移植版）中
 * CloningSimulator/CloningValidator/EnzymeSelector 共用的切点计算部分：
 *
 * - `cutSequenceByRestrictionEnzyme`：正则引擎切点扫描（前向链 + 反向链 revComp 扫描 + 环状双倍序列）
 * - `cutSequence` 内层：识别位点范围、top/bottom snip 偏移、cutType 1 双切上游 snip、
 *   normalizePositionByRangeLength 环状坐标归一（range.ts 语义）
 * - `reverseAllPositionsOfCutsite`：反向链命中坐标换算（按 main 实现复刻——最终生效值为原始坐标交换；
 *   对象字面量中的 reversePositionInRange 初值在 main 中随后被裸交换覆盖，此处直接写最终值，行为一致）
 * - `enzymeFromSite` / `getEnzymeByName` / `getCutsitesFromSequenceFlat`
 *
 * 平台差异（披露）：JS RegExp(i) → Kotlin Regex(IGNORE_CASE)；酶库复用 core/EnzymeDb.kt
 * （与 main data/aliasedEnzymesByName.ts 同源：cutOffset=topSnipOffset、bottomCut=bottomSnipOffset、
 * 别名解析等价；main 按"查询名"作为返回酶的 name，此处同样用查询名）。
 * `getCutsitesFromSequenceFlat` 中的 methylationStatus 标注（findMethylatedRegions/checkCutsiteMethylation）
 * 暂缓移植：CloningSimulator 仅使用返回数组长度，甲基化不影响本块行为；将在 EnzymeSelector 块补齐。
 */
object EnzymesVe {

    /** main RestrictionEnzyme（VE 字段语义：topSnipOffset 为识别位点起点到上链切口的 0-based 偏移） */
    data class VeEnzyme(
        val name: String,
        val site: String,
        val forwardRegex: String,
        val reverseRegex: String,
        val topSnipOffset: Int,
        val bottomSnipOffset: Int,
        val cutType: Int = 0,
        val usForward: Int? = null,
        val usReverse: Int? = null
    )

    /** main Cutsite */
    data class VeCutsite(
        val id: String,
        val start: Int,
        val end: Int,
        val topSnipPosition: Int,
        val bottomSnipPosition: Int,
        val topSnipBeforeBottom: Boolean,
        val overhangBps: String,
        val overhangSize: Int,
        val upstreamTopBeforeBottom: Boolean,
        val upstreamTopSnip: Int?,
        val upstreamBottomSnip: Int?,
        val recognitionSiteRange: SimpleRange,
        val forward: Boolean,
        val name: String,
        val enzyme: VeEnzyme
    )

    data class SimpleRange(val start: Int, val end: Int)

    private var shortidCounter = 0L
    private fun shortid(): String = "cs_${System.currentTimeMillis().toString(36)}_${(shortidCounter++).toString(36)}"

    // ───────────────── range.ts 助手（1:1） ─────────────────

    /**
     * main range.ts normalizePositionByRangeLength：
     * isInBetweenPositions=false → 0..len-1；true → 0..len（数的是碱基之间的空隙）。
     */
    fun normalizePositionByRangeLength(pPosition: Int, sequenceLength: Int, isInBetweenPositions: Boolean = false): Int {
        var position = pPosition
        if (position < 0) {
            position += sequenceLength
        } else if (position + (if (isInBetweenPositions) 0 else 1) > sequenceLength) {
            position -= sequenceLength
        }
        return when {
            position < 0 -> 0
            position > sequenceLength - (if (isInBetweenPositions) 0 else 1) ->
                sequenceLength - (if (isInBetweenPositions) 0 else 1)
            else -> position
        }
    }

    /** main range.ts reversePositionInRange */
    fun reversePositionInRange(position: Int, rangeLength: Int, isInBetweenPositions: Boolean = false): Int =
        rangeLength - position - (if (isInBetweenPositions) 0 else 1)

    /** main range.ts getSequenceWithinRange（start>end 视为环状范围） */
    fun getSequenceWithinRange(range: SimpleRange, sequence: String): String {
        if (range.start < 0 || range.end < 0) return ""
        return if (range.start > range.end) {
            sequence.substring(range.start) + sequence.substring(0, range.end + 1)
        } else {
            sequence.substring(range.start, range.end + 1)
        }
    }

    // ───────────────── 酶定义解析（1:1） ─────────────────

    private val IUPAC_TO_REGEX = mapOf(
        'A' to "a", 'C' to "c", 'G' to "g", 'T' to "t", 'U' to "u",
        'R' to "[ag]", 'Y' to "[ct]", 'S' to "[cg]", 'W' to "[at]",
        'K' to "[gt]", 'M' to "[ac]", 'B' to "[cgt]", 'D' to "[agt]",
        'H' to "[act]", 'V' to "[acg]", 'N' to "[abcdghkmnrstvwy]"
    )

    /** main motifToRegex：IUPAC → 正则 */
    fun motifToRegex(site: String): String = site.uppercase().map { IUPAC_TO_REGEX[it] ?: "." }.joinToString("")

    /**
     * main getEnzymeByName：按名字（不区分大小写）从默认酶库解析。
     * main 侧键 = 查询别名本身，返回酶的 name 即查询名（如 'BsaI' 而非规范名 'EcoA4I'）。
     */
    /**
     * main getEnzymeByName：按名字（不区分大小写）从默认酶库（defaultDb）解析。
     * main 侧键 = 查询别名本身，返回酶的 name 即查询名（如 'BsaI' 而非规范名 'EcoA4I'）。
     * 注意：defaultDb 仅是 aliasedEnzymesByName 的 267 键子集（如 'btgzi' 不在其中 → 解析失败，
     * 面板经 enzymeFromSite 空位点回退得到 0 切点 → "未知 Type IIS 酶" warning，与 main 行为一致）。
     */
private val DEFAULT_DB_KEYS = setOf(
    "aari", "aatii", "aaui", "accebi", "acci", "accii", "acciii", "acsi",
    "afai", "aflii", "alui", "aor13hi", "aor51hi", "apai", "apali", "asci",
    "asisi", "asp35hi", "asp713i", "aspmi", "atsi", "atu1i", "atu1ii", "avrbi",
    "avrii", "bali", "bamhi", "banii", "bari", "baui", "bbsi", "bbvbi",
    "bcni", "bcoki", "bdii", "bgli", "bglii", "blni", "bme216i", "bmet110i",
    "bmgt120i", "bmui", "bnai", "bpu1102i", "bpuai", "bpub5i", "bsai", "bsavi",
    "bseri", "bsgi", "bshhi", "bsiwi", "bsmbi", "bsmfi", "bsofi", "bsp105i",
    "bsp1286i", "bsp139i", "bsp1407i", "bsp4009i", "bsp70i", "bsp71i", "bspa2i", "bspci",
    "bspfni", "bspt104i", "bspt107i", "bsrfi", "bsshii", "bst1107i", "bstb7si", "bsthi",
    "bstiz316i", "bstli", "bstpi", "bstri", "bstxi", "bsu1532i", "bsu8565i", "bsu90i",
    "btgai", "btsci", "btsi", "bvei", "ccini", "ccop95i", "ceqi", "cfr10i",
    "cfr45i", "cfr47i", "cfr6i", "cjuii", "clai", "cpaai", "cpoi", "cspai",
    "cspci", "ddei", "dpni", "drai", "dsai", "dsavi", "eaei", "eagi",
    "eam1105i", "ecidi", "ecl37i", "eco211i", "eco240i", "eco241i", "eco246i", "eco247i",
    "eco52i", "eco68i", "eco81i", "eco93i", "ecoicri", "ecoo109i", "ecoo65i", "ecopi",
    "ecoprri", "ecori", "ecorv", "ecot14i", "ecot22i", "ecoviii", "ehei", "esphk22i",
    "fbai", "fdiii", "foki", "fsei", "haeii", "haeiii", "hapii", "hgiai",
    "hgibi", "hhai", "hin1i", "hincii", "hindiii", "hinfi", "hpai", "hpyf13iii",
    "hpyf19ii", "hpyf21i", "hpyf28i", "hpyf29i", "hpyf36i", "hpyf62i", "hpyf65i", "hpyii",
    "koyi", "kpni", "m.bbu297i", "m.pmudam", "maek81i", "mboi", "mboii", "mfli",
    "mizi", "mlui", "mph1103ii", "msei", "msii", "mspi", "mthai", "muni",
    "naei", "ncoi", "ndai", "ndei", "nflaii", "ngbi", "ngobviii", "ngodviii",
    "ngoeii", "ngotii", "nhei", "nlaiv", "noci", "noti", "novii", "nrui",
    "nsbi", "nsici", "nsp29132i", "paci", "pae17ki", "pca17ai", "pfaaiii", "pfl16i",
    "ple214i", "pmaci", "pmei", "pru2i", "psb9879i", "pshai", "pshbi", "psp1406i",
    "psp31i", "psp33i", "psp5i", "psti", "psuni", "pvui", "pvuii", "ral8i",
    "rsplki", "saci", "sacii", "sali", "sapi", "sau3ai", "sauni", "sbai",
    "sbfi", "scai", "sfii", "sflhk115731i", "slu1777i", "smai", "smii", "snabi",
    "spei", "sphi", "srfi", "srl61di", "sse8387i", "ssp2i", "sspi", "stui",
    "suii", "swai", "taqi", "tauii", "tsci", "tth111i", "tthhb8i", "uba1093i",
    "uba1095i", "uba1122i", "uba1160i", "uba1229i", "uba1371i", "uba1380i", "uba1403i", "uba1415i",
    "uba1437i", "uba1439i", "uba1440i", "uba36i", "uba41i", "uba42i", "uba89i", "uban13i",
    "van91i", "vcho66i", "vpak11bi", "vpakutji", "xbai", "xcyi", "xhoi", "xmai",
    "xmli", "xphi", "xspi",
)

    fun getEnzymeByName(name: String): VeEnzyme? {
        if (name.isEmpty() || name.lowercase() !in DEFAULT_DB_KEYS) return null
        val e = Enzymes.byName(name) ?: return null
        return VeEnzyme(
            name = name,
            site = e.site,
            forwardRegex = motifToRegex(e.site),
            reverseRegex = motifToRegex(Bio.revComp(e.site)),
            topSnipOffset = e.cutOffset,
            bottomSnipOffset = e.bottomCut
        )
    }

    /** main enzymeFromSite：REBASE 风格 {seq, cut} → VE 定义（cut 为识别位点内 0-based 上链切点） */
    fun enzymeFromSite(name: String, site: String, cut: Int, isBlunt: Boolean = false): VeEnzyme {
        val forwardRegex = motifToRegex(site)
        val reverseRegex = motifToRegex(Bio.revComp(site))
        val topSnipOffset = cut + 1
        val bottomSnipOffset = if (isBlunt) topSnipOffset else topSnipOffset - 1
        return VeEnzyme(name, site, forwardRegex, reverseRegex, topSnipOffset, bottomSnipOffset, 0)
    }

    /** main resolveEnzyme（CloningSimulator/CloningMethods 用）：库名优先，回退 site 构造 */
    fun resolveEnzyme(name: String, fallbackSite: String = "", cut: Int = 0): VeEnzyme? {
        getEnzymeByName(name)?.let { return it }
        if (fallbackSite.isNotEmpty()) return enzymeFromSite(name, fallbackSite, cut)
        return null
    }

    // ───────────────── 切点引擎（1:1） ─────────────────

    /** main cutSequenceByRestrictionEnzyme：单酶全部切点（前向链 + 可选反向链） */
    fun cutSequenceByRestrictionEnzyme(
        pSequence: String,
        circular: Boolean,
        restrictionEnzyme: VeEnzyme
    ): List<VeCutsite> {
        if (restrictionEnzyme.forwardRegex.isEmpty() || restrictionEnzyme.reverseRegex.isEmpty()) {
            return emptyList() // main：附加 error 字段 "Enzyme restriction site must be at least 1 bp long."
        }
        val forwardRegExpPattern = Regex(restrictionEnzyme.forwardRegex, RegexOption.IGNORE_CASE)
        val sequence = pSequence
        val cutsitesForward = cutSequence(forwardRegExpPattern, restrictionEnzyme, sequence, circular)
        var cutsitesReverse: List<VeCutsite> = emptyList()
        if (restrictionEnzyme.forwardRegex != restrictionEnzyme.reverseRegex) {
            val revSequence = Bio.revComp(sequence)
            cutsitesReverse = cutSequence(forwardRegExpPattern, restrictionEnzyme, revSequence, circular)
            cutsitesReverse = cutsitesReverse.map { reverseAllPositionsOfCutsite(it, sequence.length) }
        }
        return cutsitesForward + cutsitesReverse
    }

    /**
     * main reverseAllPositionsOfCutsite。
     * main 实现先在对象字面量里做 reversePositionInRange 换算，随后又用裸交换逐字段覆盖——
     * 最终生效值即裸交换（start↔end、topSnip↔bottomSnip、upstream 交换、识别位点范围交换、
     * overhangBps 取反向互补、forward=false）。此处直接写最终值，行为一致。
     */
    private fun reverseAllPositionsOfCutsite(cutsite: VeCutsite, rangeLength: Int): VeCutsite {
        return cutsite.copy(
            start = cutsite.end,
            end = cutsite.start,
            topSnipPosition = cutsite.bottomSnipPosition,
            bottomSnipPosition = cutsite.topSnipPosition,
            overhangBps = Bio.revComp(cutsite.overhangBps),
            upstreamTopSnip = cutsite.upstreamBottomSnip,
            upstreamBottomSnip = cutsite.upstreamTopSnip,
            upstreamTopBeforeBottom = cutsite.upstreamTopBeforeBottom,
            topSnipBeforeBottom = cutsite.topSnipBeforeBottom,
            recognitionSiteRange = SimpleRange(cutsite.recognitionSiteRange.end, cutsite.recognitionSiteRange.start),
            forward = false
        )
    }

    /** main cutSequence 内层：环状双倍序列 + 逐命中滚动搜索（重叠命中不漏） */
    private fun cutSequence(
        forwardRegExpPattern: Regex,
        restrictionEnzyme: VeEnzyme,
        sequenceIn: String,
        circular: Boolean
    ): List<VeCutsite> {
        val restrictionCutSites = mutableListOf<VeCutsite>()
        val recognitionSiteLength = restrictionEnzyme.site.length
        val originalSequence = sequenceIn
        val originalSequenceLength = sequenceIn.length
        var sequence = if (circular) sequenceIn + sequenceIn else sequenceIn
        val currentSequenceLength = sequence.length

        var startIndex = 0
        var match = forwardRegExpPattern.find(sequence, startIndex)
        while (match != null) {
            var recognitionSiteRange = SimpleRange(0, 0)
            var upstreamTopSnip: Int? = null
            var upstreamBottomSnip: Int? = null
            var upstreamTopBeforeBottom = false
            var topSnipPosition: Int? = null
            var bottomSnipPosition: Int? = null
            var topSnipBeforeBottom = false
            var fitsWithinSequence = false

            val start0 = match.range.first // recognitionSiteRange.start（matchIndex + startIndex）
            recognitionSiteRange = SimpleRange(start0, match.range.first + recognitionSiteLength - 1)
            var start = recognitionSiteRange.start
            var end = recognitionSiteRange.end

            // cutType 1（双切酶）：上游也有切口
            if (restrictionEnzyme.cutType == 1) {
                upstreamTopSnip = recognitionSiteRange.end - (restrictionEnzyme.usForward ?: 0)
                upstreamBottomSnip = recognitionSiteRange.end - (restrictionEnzyme.usReverse ?: 0)
                if (upstreamTopSnip >= 0 && upstreamBottomSnip >= 0) {
                    fitsWithinSequence = true
                    if (upstreamTopSnip < upstreamBottomSnip) {
                        if (start > upstreamTopSnip) start = upstreamTopSnip + 1
                        upstreamTopBeforeBottom = true
                    } else {
                        if (start > upstreamBottomSnip) start = upstreamBottomSnip + 1
                    }
                    upstreamTopSnip = normalizePositionByRangeLength(upstreamTopSnip, originalSequenceLength, true)
                    upstreamBottomSnip = normalizePositionByRangeLength(upstreamBottomSnip, originalSequenceLength, true)
                } else {
                    upstreamTopSnip = null
                    upstreamBottomSnip = null
                }
            }

            var topSnip = recognitionSiteRange.start + restrictionEnzyme.topSnipOffset
            var bottomSnip = recognitionSiteRange.start + restrictionEnzyme.bottomSnipOffset
            if (bottomSnip <= currentSequenceLength && topSnip <= currentSequenceLength) {
                fitsWithinSequence = true
                if (topSnip > bottomSnip) {
                    if (topSnip > recognitionSiteRange.end) end = topSnip - 1
                } else {
                    if (bottomSnip > recognitionSiteRange.end) end = bottomSnip - 1
                    topSnipBeforeBottom = true
                }
                topSnipPosition = normalizePositionByRangeLength(topSnip, originalSequenceLength, true)
                bottomSnipPosition = normalizePositionByRangeLength(bottomSnip, originalSequenceLength, true)
            } else {
                topSnipPosition = null
                bottomSnipPosition = null
            }

            if (fitsWithinSequence && start >= 0 && end >= 0 &&
                start < originalSequenceLength && end < currentSequenceLength
            ) {
                start = normalizePositionByRangeLength(start, originalSequenceLength, false)
                end = normalizePositionByRangeLength(end, originalSequenceLength, false)
                recognitionSiteRange = SimpleRange(
                    normalizePositionByRangeLength(recognitionSiteRange.start, originalSequenceLength, false),
                    normalizePositionByRangeLength(recognitionSiteRange.end, originalSequenceLength, false)
                )
                var cutRange = SimpleRange(-1, -1)
                if (topSnipPosition != bottomSnipPosition) {
                    // main 此处为 TS 非空断言（topSnipPosition!）；能进此分支则两值已归一化非空
                    val t = topSnipPosition!!
                    val b = bottomSnipPosition!!
                    cutRange = if (topSnipBeforeBottom) {
                        SimpleRange(t, normalizePositionByRangeLength(b - 1, originalSequenceLength))
                    } else {
                        SimpleRange(b, normalizePositionByRangeLength(t - 1, originalSequenceLength))
                    }
                }
                val overhangBps = getSequenceWithinRange(cutRange, originalSequence)
                restrictionCutSites += VeCutsite(
                    id = shortid(),
                    start = start,
                    end = end,
                    topSnipPosition = topSnipPosition!!,
                    bottomSnipPosition = bottomSnipPosition!!,
                    topSnipBeforeBottom = topSnipBeforeBottom,
                    overhangBps = overhangBps,
                    overhangSize = overhangBps.length,
                    upstreamTopBeforeBottom = upstreamTopBeforeBottom,
                    upstreamTopSnip = upstreamTopSnip,
                    upstreamBottomSnip = upstreamBottomSnip,
                    recognitionSiteRange = recognitionSiteRange,
                    forward = true,
                    name = restrictionEnzyme.name,
                    enzyme = restrictionEnzyme
                )
            }

            startIndex = match.range.first + 1
            match = forwardRegExpPattern.find(sequence, startIndex)
        }
        return restrictionCutSites
    }

    /** main getCutsitesFromSequenceFlat：多酶切点平铺（甲基化标注暂缓，见文件头披露） */
    fun getCutsitesFromSequenceFlat(
        sequence: String,
        circular: Boolean,
        restrictionEnzymes: List<VeEnzyme>
    ): List<VeCutsite> {
        val all = mutableListOf<VeCutsite>()
        for (re in restrictionEnzymes) {
            all += cutSequenceByRestrictionEnzyme(sequence, circular, re)
        }
        return all
    }
}
