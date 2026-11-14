package com.spice.nativeapp.core

/**
 * main src/lib/genome/primerAnalysis.ts 294–489 行 1:1 移植：
 * simulatePCR / simulateOverlapExtensionPCR / simulateSiteDirectedMutagenesis + emptyProduct。
 * 依赖 CollectionIO.calculatePercentGC / calculateNebTm（简并碱基 → null → 0，
 * 对应 main `typeof tm === "number" ? tm : 0`）/ revComp
 * （=getReverseComplementSequenceString）。
 * 警告文案走 I18n（zh 原文键 = main paraglide 词条）。
 */
object PcrSimulation {

    data class PcrProduct(
        val product: String,
        val productLength: Int,
        val forwardPrimer: String,
        val reversePrimer: String,
        val templateStart: Int,
        val templateEnd: Int,
        val meltingTemp: Double,
        val gcContent: Double,
        val warnings: List<String>
    )

    data class MutagenesisResult(
        val mutatedSequence: String,
        val mutationStart: Int,
        val mutationEnd: Int,
        val fwdPrimer: String,
        val revPrimer: String,
        val warnings: List<String>
    )

    private fun cleanAtcg(s: String): String = s.uppercase().replace(Regex("[^ATCG]"), "")

    /** JS String.prototype.slice（DNA 字符串用：负值自尾部，越界钳制，from>=to 为空） */
    private fun jsSlice(s: String, a: Int, b: Int): String {
        val from = if (a < 0) maxOf(s.length + a, 0) else minOf(a, s.length)
        val to = if (b < 0) maxOf(s.length + b, 0) else minOf(b, s.length)
        return if (from >= to) "" else s.substring(from, to)
    }

    /** 模拟标准 PCR 扩增（main simulatePCR 1:1）。 */
    fun simulatePCR(
        template: String,
        fwdPrimer: String,
        revPrimer: String,
        circular: Boolean = false
    ): PcrProduct {
        val warnings = mutableListOf<String>()
        val tmpl = template.uppercase()
        val fwd = cleanAtcg(fwdPrimer)
        val revCompInput = cleanAtcg(revPrimer)
        val rev = CollectionIO.revComp(revCompInput)

        // 正向引物定位：15bp 种子，失败回退 10bp 种子
        var fwdPos = tmpl.indexOf(jsSlice(fwd, 0, minOf(15, fwd.length)))
        if (fwdPos == -1) {
            fwdPos = tmpl.indexOf(jsSlice(fwd, 0, 10))
        }
        if (fwdPos == -1) {
            warnings += I18n.t("正向引物未在模板中找到结合位点")
            return emptyProduct(fwdPrimer, revPrimer)
        }

        // 反向引物（反向互补）定位：自 fwdPos 起
        var revPos = tmpl.indexOf(jsSlice(rev, 0, minOf(15, rev.length)), fwdPos)
        if (revPos == -1) {
            revPos = tmpl.indexOf(jsSlice(rev, 0, 10), fwdPos)
        }
        if (revPos == -1) {
            warnings += I18n.t("反向引物未在模板中找到结合位点")
            return emptyProduct(fwdPrimer, revPrimer)
        }

        // 产物：正向起点 → 反向引物末端
        val productEnd = revPos + rev.length
        var productSeq = jsSlice(tmpl, fwdPos, productEnd)

        // 环状模板：跨起点处理
        if (circular && productEnd > tmpl.length) {
            val wrap = productEnd - tmpl.length
            productSeq = jsSlice(tmpl, fwdPos, tmpl.length) + jsSlice(tmpl, 0, wrap)
        }

        val gc = CollectionIO.calculatePercentGC(productSeq)
        val tm = CollectionIO.calculateNebTm(
            jsSlice(productSeq, 0, 20),
            monovalentCationConc = 0.05,
            primerConc = 0.0000005
        )

        if (productSeq.length < 50) {
            warnings += I18n.t("PCR 产物过短 (<50bp)")
        }
        if (productSeq.length > 5000) {
            warnings += I18n.t("PCR 产物较长，可能需要优化延伸时间")
        }

        return PcrProduct(
            product = productSeq,
            productLength = productSeq.length,
            forwardPrimer = fwd,
            reversePrimer = revCompInput,
            templateStart = fwdPos,
            templateEnd = productEnd,
            meltingTemp = tm ?: 0.0,
            gcContent = gc,
            warnings = warnings
        )
    }

    /** 模拟重叠延伸 PCR 多片段组装（main simulateOverlapExtensionPCR 1:1）。 */
    fun simulateOverlapExtensionPCR(
        fragments: List<Pair<String, String>>, // (seq, name)
        overlapLength: Int = 20
    ): PcrProduct {
        val warnings = mutableListOf<String>()
        if (fragments.size < 2) {
            warnings += I18n.t("至少需要 2 个片段进行重叠延伸 PCR")
            return emptyProduct("", "")
        }

        var product = fragments[0].first.uppercase()
        for (i in 1 until fragments.size) {
            val frag = fragments[i].first.uppercase()
            // 上一片段产物末端 与 下一片段开头 的重叠比对
            val endOfProductRaw = jsSlice(product, -overlapLength, product.length) // JS product.slice(-overlapLength)：-0 视为 0 → 整串
            val startOfFrag = jsSlice(frag, 0, overlapLength)
            if (endOfProductRaw == startOfFrag) {
                // 完美重叠：跳过重复区
                product += jsSlice(frag, overlapLength, frag.length)
            } else {
                // 反向互补重叠检查
                val rcEnd = CollectionIO.revComp(endOfProductRaw)
                if (rcEnd == startOfFrag) {
                    product += jsSlice(frag, overlapLength, frag.length)
                } else {
                    warnings += I18n.tf("片段 {v1} 与前一片段重叠区域不匹配", i)
                    product += frag // 直接拼接
                }
            }
        }

        val gc = CollectionIO.calculatePercentGC(product)
        val tm = CollectionIO.calculateNebTm(
            jsSlice(product, 0, 20),
            monovalentCationConc = 0.05,
            primerConc = 0.0000005
        )

        return PcrProduct(
            product = product,
            productLength = product.length,
            forwardPrimer = jsSlice(fragments[0].first, 0, 20),
            reversePrimer = CollectionIO.revComp(
                jsSlice(fragments[fragments.size - 1].first, -20, fragments[fragments.size - 1].first.length)
            ),
            templateStart = 0,
            templateEnd = product.length,
            meltingTemp = tm ?: 0.0,
            gcContent = gc,
            warnings = warnings
        )
    }

    /** 点突变（main simulateSiteDirectedMutagenesis { position, newBase } 分支 1:1）。 */
    fun simulateSiteDirectedMutagenesis(
        template: String,
        position: Int,
        newBase: String,
        primerLength: Int = 25
    ): MutagenesisResult = simulateMutagenesis(template, position, position, newBase, primerLength)

    /** 区间替换/插入/缺失（main { start, end, newSeq } 分支 1:1）。 */
    fun simulateSiteDirectedMutagenesis(
        template: String,
        start: Int,
        end: Int,
        newSeq: String,
        primerLength: Int = 25
    ): MutagenesisResult = simulateMutagenesis(template, start, end, newSeq, primerLength)

    private fun simulateMutagenesis(
        template: String,
        mutStart: Int,
        mutEnd: Int,
        newSeq: String,
        primerLength: Int
    ): MutagenesisResult {
        val warnings = mutableListOf<String>()
        val tmpl = template.uppercase()
        val mutated = jsSlice(tmpl, 0, mutStart) + newSeq + jsSlice(tmpl, mutEnd + 1, tmpl.length)

        // 以突变位点为中心设计引物对
        val center = Math.floor((mutStart + mutEnd) / 2.0).toInt()
        val half = primerLength / 2
        val primerStart = maxOf(0, center - half)
        val primerEnd = minOf(mutated.length, center + half + (primerLength % 2))
        val fwdPrimer = jsSlice(mutated, primerStart, primerEnd)
        val revPrimer = CollectionIO.revComp(fwdPrimer)

        if (mutStart < 0 || mutEnd >= tmpl.length) {
            warnings += I18n.t("突变位置超出模板范围")
        }
        if (newSeq.length > (mutEnd - mutStart + 1) * 2) {
            warnings += I18n.t("插入序列较大，可能影响引物退火")
        }

        return MutagenesisResult(
            mutatedSequence = mutated,
            mutationStart = mutStart,
            mutationEnd = mutEnd,
            fwdPrimer = fwdPrimer,
            revPrimer = revPrimer,
            warnings = warnings
        )
    }

    private fun emptyProduct(fwd: String, rev: String) = PcrProduct(
        product = "",
        productLength = 0,
        forwardPrimer = fwd,
        reversePrimer = rev,
        templateStart = 0,
        templateEnd = 0,
        meltingTemp = 0.0,
        gcContent = 0.0,
        warnings = listOf(I18n.t("PCR 模拟失败"))
    )
}
