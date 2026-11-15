package com.spice.nativeapp.core

/**
 * 核糖体滑移（-1/+1 PRF）翻译模拟。1:1 移植 main
 * `src/lib/genome/advancedTools.ts` 的 `simulateRibosomalSlippage`。
 *
 * 流程（与 main 逐段一致）：
 *  1. 定位滑移位点（优先显式位点，否则自起点向后找，再回退从头找）；
 *  2. 同框翻译位点之前的肽段，再同框读到位点内整密码子；
 *  3. 按 shift 平移读框，翻译滑移后肽段（遇终止即停并计数）；
 *  4. 生成滑移接头的五行可视化比对。
 *
 * 算法纯 Kotlin；密码子翻译复用 [Bio.translateCodon]（标准遗传密码，终止 `*`，未知 `X`）。
 */
data class SlippageResult(
    val fullProtein: String,
    val preProtein: String,
    val postProtein: String,
    /** 滑移位点 1-based 起始 bp */
    val sitePosition: Int,
    val siteSequence: String,
    val shiftApplied: Int,
    val stopsEncountered: Int,
    val visualAlignment: String
)

object RibosomalSlippage {

    private fun aa(codon: String): Char = Bio.translateCodon(codon)

    /**
     * @param startPosition 起始密码子 1-based 索引
     * @param slipperySite 滑移位点 motif（如 TTTAAAC）
     * @param shift 移码（-1 / +1 / -2 / +2）
     * @param sitePosition 可选 1-based 位点起始位覆盖
     * @throws IllegalArgumentException 找不到位点时抛出（词条同 main）
     */
    fun simulate(
        sequence: String,
        startPosition: Int,
        slipperySite: String,
        shift: Int,
        sitePosition: Int? = null
    ): SlippageResult {
        val seq = sequence.uppercase().replace("U", "T")
        val startIdx = startPosition - 1
        val slipPattern = slipperySite.uppercase().replace("U", "T")

        var slipIdx = -1
        if (sitePosition != null) {
            slipIdx = sitePosition - 1
        } else {
            slipIdx = seq.indexOf(slipPattern, startIdx)
            if (slipIdx == -1) slipIdx = seq.indexOf(slipPattern)
        }
        if (slipIdx == -1) {
            throw IllegalArgumentException(
                I18n.tf("无法在序列中找到滑动位点: {v1}", slipPattern)
            )
        }

        // 2. 滑移前同框翻译到位点前
        var preProtein = ""
        var currIdx = startIdx
        while (currIdx + 3 <= slipIdx) {
            val residue = aa(seq.substring(currIdx, currIdx + 3))
            if (residue == '*') break
            preProtein += residue
            currIdx += 3
        }

        // 位点内同框读整密码子
        val slipCodonsCount = slipPattern.length / 3
        var preSlipAtSite = ""
        var preSlipSiteEndIdx = currIdx
        repeat(slipCodonsCount) {
            val residue = aa(seq.substring(currIdx, currIdx + 3))
            preSlipAtSite += residue
            currIdx += 3
            preSlipSiteEndIdx = currIdx
        }
        preProtein += preSlipAtSite

        // 3. 应用移码，翻译滑移后肽段
        val postStartIdx = preSlipSiteEndIdx + shift
        var postProtein = ""
        var postIdx = postStartIdx
        var stopsEncountered = 0
        while (postIdx >= 0 && postIdx + 3 <= seq.length) {
            val residue = aa(seq.substring(postIdx, postIdx + 3))
            if (residue == '*') {
                stopsEncountered++
                break
            }
            postProtein += residue
            postIdx += 3
        }

        val fullProtein = preProtein + postProtein

        // 4. 可视化比对（五行，模板同 main）
        val juncBefore = seq.substring(maxOf(0, preSlipSiteEndIdx - 15), preSlipSiteEndIdx)
        val juncAfter = seq.substring(preSlipSiteEndIdx, minOf(seq.length, preSlipSiteEndIdx + 15))
        val shiftedBefore = seq.substring(maxOf(0, postStartIdx - 15), postStartIdx)
        val shiftedAfter = seq.substring(postStartIdx, minOf(seq.length, postStartIdx + 15))

        val alignment = listOf(
            I18n.tf("原始框架 (Frame 0):  {v1} | {v2}", juncBefore, juncAfter),
            "                    " + " ".repeat(juncBefore.length) +
                I18n.tf("^ (切变前切割点 {v1} bp)", preSlipSiteEndIdx),
            I18n.tf(
                "滑移框架 (Frame {v1}):  {v2} | {v3}",
                if (shift > 0) "+$shift" else "$shift",
                shiftedBefore, shiftedAfter
            ),
            "                    " + " ".repeat(shiftedBefore.length) +
                I18n.tf("^ (滑移后新起点 {v1} bp)", postStartIdx),
            I18n.tf(
                "滑移接头氨基酸:     {v1} [Slippage] {v2}...",
                preSlipAtSite, postProtein.take(5)
            )
        ).joinToString("\n")

        return SlippageResult(
            fullProtein = fullProtein,
            preProtein = preProtein,
            postProtein = postProtein,
            sitePosition = slipIdx + 1,
            siteSequence = seq.substring(slipIdx, slipIdx + slipPattern.length),
            shiftApplied = shift,
            stopsEncountered = stopsEncountered,
            visualAlignment = alignment
        )
    }
}
