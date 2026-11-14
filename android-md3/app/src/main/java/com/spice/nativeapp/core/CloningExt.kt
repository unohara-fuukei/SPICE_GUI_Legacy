package com.spice.nativeapp.core

/**
 * SPICE Cloning & Molecular Engineering Extension Module (Features 15-19) — 1:1
 * main src/lib/genome/biophysics/cloningEngineering.ts + backend/api.ts GG fallback
 *
 * 15. Recombinase Cloning simulation (Cre-loxP, FLP-FRT)
 * 16. Simple Sequence Repeat (SSR) marker flanking primer design
 * 17. Allele-specific SNP Genotyping primer design (KASP/TaqMan)
 * 18. Restriction Fragment Length Polymorphism (RFLP) fragment simulation
 * 19. Golden Gate Assembly —— Android 对应 main 非 Tauri 运行形态，走 backend/api.ts
 *     simulateGoldenGateAssembly 的浏览器 fallback 实现（仅正向方向解析 + 标准顺序拼装 +
 *     环化校验警告）；Rust simulate_goldengate_assembly 的哈密顿环匹配/反向方向解析仅为
 *     Tauri 桌面路径，平台差异已在 README 披露。
 *
 * 注：main Svelte 面板读取 snpRes.compatible/asp1Primer 与 rflp.wildTypeFragments 等字段，
 * 而引擎实际返回 kasp/taqman、fragmentsWt/fragmentsMut —— 字段失配为主版既有状态：
 * SNP tab 恒走 ⚠️ 分支（message 为 undefined 渲染为空）、RFLP tab 首渲染即 TypeError。
 * 本移植按"1:1 复刻主版运行行为"处理（不修正、不补主版不存在的输出），UI 侧如实复刻。
 */

object CloningExt {

    private const val SITE_LOXP = "ATAACTTCGTATAATGTATGCTATACGAAGTTAT" // 34bp
    private const val SITE_FRT = "GAAGTTCCTATTCTCTAGAAAGTATAGGAACTTC"  // 34bp

    // ───────────────────── JS 语义工具 ─────────────────────

    /** JS String.prototype.slice 语义（负索引自尾部起算，越界钳制） */
    private fun jsSlice(s: String, a: Int, b: Int): String {
        val from = if (a < 0) maxOf(s.length + a, 0) else minOf(a, s.length)
        val to = if (b < 0) maxOf(s.length + b, 0) else minOf(b, s.length)
        return if (from >= to) "" else s.substring(from, to)
    }

    /** JS String.prototype.substring 语义（负值钳 0，越界钳 len，start>end 时交换两参数） */
    private fun jsSubstring(s: String, a: Int, b: Int): String {
        var x = minOf(maxOf(a, 0), s.length)
        var y = minOf(maxOf(b, 0), s.length)
        if (x > y) { val t = x; x = y; y = t }
        return s.substring(x, y)
    }

    /** JS Math.round 语义（floor(x+0.5)） */
    private fun jsRound(x: Double): Int = kotlin.math.floor(x + 0.5).toInt()

    private fun cleanDna(seq: String): String = seq.uppercase().filter { it in "ATCG" }

    /** main primersLims.ts calculatePrimerTm 1:1（<14nt Wallace，否则 GC 经验式） */
    fun calculatePrimerTm(seq: String): Double {
        val clean = cleanDna(seq)
        val len = clean.length
        if (len == 0) return 0.0
        var g = 0; var c = 0; var a = 0; var t = 0
        for (b in clean) {
            when (b) {
                'G' -> g++
                'C' -> c++
                'A' -> a++
                'T' -> t++
            }
        }
        return if (len < 14) 4.0 * (g + c) + 2.0 * (a + t)
        else 64.9 + 41.0 * (g + c - 16.4) / len
    }

    // ───────────────────── 15: Recombinase Cloning ─────────────────────

    data class RecombinaseResult(
        val productSequence: String,
        val reactionType: String, // "excision" | "integration" | "inversion" | "no_reaction"
        val log: List<String>
    )

    /** simulateRecombinaseCloning 1:1（Cre-loxP / FLP-FRT 位点特异重组：环出切除/整合/倒位） */
    fun simulateRecombinaseCloning(vectorSeq: String, insertSeq: String, system: String): RecombinaseResult {
        val vSeq = cleanDna(vectorSeq)
        val insSeq = cleanDna(insertSeq)
        val sitePattern = if (system == "Cre-lox") SITE_LOXP else SITE_FRT
        val siteLen = sitePattern.length
        val log = mutableListOf<String>()

        val vSite1 = vSeq.indexOf(sitePattern)
        val vSite2 = vSeq.indexOf(sitePattern, vSite1 + 1)

        if (vSite1 != -1 && vSite2 != -1) {
            val siteRc = Bio.revComp(sitePattern)
            val site2IsRc = vSeq.substring(vSite2, vSite2 + siteLen) == siteRc || vSeq.indexOf(siteRc) == vSite2
            if (site2IsRc) {
                // 倒位：两位点之间的区段反向互补
                val prefix = vSeq.substring(0, vSite1 + siteLen)
                val middle = vSeq.substring(vSite1 + siteLen, vSite2)
                val suffix = vSeq.substring(vSite2)
                val invertedMiddle = Bio.revComp(middle)
                log += I18n.tf("[{v1}] 检测到两个处于反向对置方向的重组位点。启动 DNA 片段倒位反应 (Inversion)。", system)
                return RecombinaseResult(prefix + invertedMiddle + suffix, "inversion", log)
            } else {
                // 环出切除：两位点之间的区段被切出
                val prefix = vSeq.substring(0, vSite1 + siteLen)
                val suffix = vSeq.substring(vSite2 + siteLen)
                log += I18n.tf("[{v1}] 检测到两个处于同向平行排列的重组位点。启动 DNA 片段环出切除反应 (Excision)，释放出中间 {v2} bp 的环状分子。", system, vSite2 - vSite1)
                return RecombinaseResult(prefix + suffix, "excision", log)
            }
        }

        // 整合：载体与插入片段各含一个位点
        val insSite = insSeq.indexOf(sitePattern)
        if (vSite1 != -1 && insSite != -1) {
            val prefix = vSeq.substring(0, vSite1 + siteLen)
            val suffix = vSeq.substring(vSite1 + siteLen)
            val integrated = prefix + insSeq.substring(insSite + siteLen) + insSeq.substring(0, insSite + siteLen) + suffix
            log += I18n.tf("[{v1}] 载体与插入片段各含有一个重组位点。启动重组酶介导的供体片段整合反应 (Integration)。", system)
            return RecombinaseResult(integrated, "integration", log)
        }

        log += I18n.tf("[{v1}] 未找到匹配的重组位点对，无法触发位点特异性基因重组。", system)
        return RecombinaseResult(vectorSeq, "no_reaction", log)
    }

    // ───────────────────── 16: SSR Marker Primer Design ─────────────────────

    data class SsrMarker(
        val repeatPattern: String, val repeatCount: Int,
        val start: Int, val end: Int, // 1-based
        val forwardPrimer: String, val reversePrimer: String,
        val productSize: Int, val tm: Int
    )

    /** designSsrMarkers 1:1（微卫星串联重复侧翼引物设计，18-22bp / Tm 54-62） */
    fun designSsrMarkers(dnaSeq: String): List<SsrMarker> {
        val seq = cleanDna(dnaSeq)
        val repeats = GenomicsTools.detectTandemRepeats(seq)
        val markers = mutableListOf<SsrMarker>()

        for (rep in repeats) {
            val leftRegion = jsSlice(seq, maxOf(0, rep.start - 100), rep.start)
            val rightRegion = jsSlice(seq, rep.end + 1, minOf(seq.length, rep.end + 101))
            if (leftRegion.length < 25 || rightRegion.length < 25) continue

            var forwardPrimer = ""
            for (len in 18..22) {
                val candidate = leftRegion.takeLast(len)
                val tm = calculatePrimerTm(candidate)
                if (tm >= 54 && tm <= 62) { forwardPrimer = candidate; break }
            }
            if (forwardPrimer.isEmpty()) forwardPrimer = leftRegion.takeLast(20)

            var reversePrimer = ""
            val rightRc = Bio.revComp(rightRegion)
            for (len in 18..22) {
                val candidate = rightRc.takeLast(len)
                val tm = calculatePrimerTm(candidate)
                if (tm >= 54 && tm <= 62) { reversePrimer = candidate; break }
            }
            if (reversePrimer.isEmpty()) reversePrimer = rightRc.takeLast(20)

            val fIdx = seq.indexOf(forwardPrimer)
            val rIdx = seq.indexOf(Bio.revComp(reversePrimer))
            val productSize = if (rIdx != -1 && fIdx != -1) rIdx + reversePrimer.length - fIdx else 0

            markers += SsrMarker(
                rep.pattern, rep.repeatCount, rep.start + 1, rep.end + 1,
                forwardPrimer, reversePrimer, productSize, jsRound(calculatePrimerTm(forwardPrimer))
            )
        }
        return markers
    }

    // ───────────────────── 17: SNP Genotyping (KASP/TaqMan) ─────────────────────

    data class KaspPrimerSet(
        val allele1Forward: String, // FAM tail + allele 1 base
        val allele2Forward: String, // HEX tail + allele 2 base
        val commonReverse: String,
        val tm: Int
    )

    data class TaqManPrimerProbeSet(
        val forwardPrimer: String, val reversePrimer: String,
        val probeAllele1: String, // FAM reporter + quencher
        val probeAllele2: String  // VIC reporter + quencher
    )

    data class SnpGenotypingResult(
        val kasp: KaspPrimerSet,
        val taqman: TaqManPrimerProbeSet,
        val log: List<String>
    )

    /** designSnpGenotypingPrimers 1:1（等位特异性前向引物 3' 端止于 SNP 碱基 + 共通反向引物 + 探针） */
    fun designSnpGenotypingPrimers(dnaSeq: String, snpIndex: Int, allele1: String, allele2: String): SnpGenotypingResult {
        val seq = cleanDna(dnaSeq)
        val log = mutableListOf<String>()

        val famTail = "GAAGGTGACCAAGTTCATGCT" // 标准 KASP FAM tail
        val hexTail = "GAAGGTCGGTCAAGCGACTGC" // 标准 KASP HEX tail

        // 1. KASP：前向引物 3' 端恰好止于 SNP 碱基
        val upstreamFlank = jsSlice(seq, maxOf(0, snpIndex - 25), snpIndex)
        val f1 = upstreamFlank.takeLast(20) + allele1
        val kaspF1 = famTail + f1
        val f2 = upstreamFlank.takeLast(20) + allele2
        val kaspF2 = hexTail + f2

        val downstreamFlank = jsSlice(seq, snpIndex + 1, minOf(seq.length, snpIndex + 100))
        val rcDownstream = Bio.revComp(downstreamFlank)
        val commonRev = rcDownstream.takeLast(20)

        log += I18n.tf("[KASP 设计] 生成了特异性等位基因前向引物。A1 末端终止于 '{v1}' (FAM)；A2 末端终止于 '{v2}' (HEX)。", allele1, allele2)

        // 2. TaqMan：引物侧翼 SNP，探针覆盖 SNP 位点
        val fPrimer = jsSlice(seq, maxOf(0, snpIndex - 60), snpIndex - 40)
        val rPrimer = Bio.revComp(jsSlice(seq, snpIndex + 40, minOf(seq.length, snpIndex + 60)))
        val probeRegionLeft = jsSlice(seq, snpIndex - 10, snpIndex)
        val probeRegionRight = jsSlice(seq, snpIndex + 1, snpIndex + 10)
        val probe1 = "5'-[FAM]-" + probeRegionLeft + allele1 + probeRegionRight + "-[BHQ1]-3'"
        val probe2 = "5'-[VIC]-" + probeRegionLeft + allele2 + probeRegionRight + "-[BHQ1]-3'"

        log += I18n.tf("[TaqMan 设计] 设计了特异性探针对。探针1包裹 '{v1}' (FAM荧光)；探针2包裹 '{v2}' (VIC荧光)。", allele1, allele2)

        return SnpGenotypingResult(
            kasp = KaspPrimerSet(kaspF1, kaspF2, commonRev, jsRound(calculatePrimerTm(f1))),
            taqman = TaqManPrimerProbeSet(fPrimer, rPrimer, probe1, probe2),
            log = log
        )
    }

    // ───────────────────── 18: RFLP Analysis ─────────────────────

    data class RflpResult(
        val enzymeUsed: String,
        val cleavageInWt: List<Int>, val cleavageInMut: List<Int>,
        val fragmentsWt: List<Int>, val fragmentsMut: List<Int>, val fragmentsHet: List<Int>,
        val isDiscriminative: Boolean
    )

    /** analyzeRflp 1:1（野生型 vs 突变型酶切片段谱预测，大片段在前模拟凝胶视角） */
    fun analyzeRflp(wildTypeSeq: String, mutantSeq: String, enzymeName: String, enzymeSite: String): RflpResult {
        val wt = cleanDna(wildTypeSeq)
        val mut = cleanDna(mutantSeq)
        val site = enzymeSite.uppercase()

        fun getCuts(sequence: String): List<Int> {
            val cuts = mutableListOf<Int>()
            var pos = sequence.indexOf(site)
            while (pos != -1) {
                cuts += pos + site.length / 2 // 粗略切点位置
                pos = sequence.indexOf(site, pos + 1)
            }
            return cuts
        }

        val cutsWt = getCuts(wt)
        val cutsMut = getCuts(mut)

        fun getFragments(sequence: String, cuts: List<Int>): List<Int> {
            val sortedCuts = (listOf(0) + cuts + listOf(sequence.length)).sorted()
            val frags = mutableListOf<Int>()
            for (i in 0 until sortedCuts.size - 1) {
                val len = sortedCuts[i + 1] - sortedCuts[i]
                if (len > 0) frags += len
            }
            return frags.sortedDescending()
        }

        val fragsWt = getFragments(wt, cutsWt)
        val fragsMut = getFragments(mut, cutsMut)
        val fragsHet = (fragsWt + fragsMut).distinct().sortedDescending()

        return RflpResult(enzymeName, cutsWt, cutsMut, fragsWt, fragsMut, fragsHet, cutsWt.size != cutsMut.size)
    }

    // ───────────────────── 19: Golden Gate Assembly（非 Tauri fallback 1:1） ─────────────────────

    data class GgFragment(
        val index: Int, val originalLen: Int,
        val leftOverhang: String, val rightOverhang: String,
        val insertSeq: String, val orientationForward: Boolean
    )

    data class GgResult(
        val success: Boolean, val productSeq: String,
        val fragments: List<GgFragment>, val sortedIndices: List<Int>, val overhangs: List<String>,
        val errors: List<String>, val warnings: List<String>
    )

    /** main backend/api.ts simulateGoldenGateAssembly 浏览器 fallback 1:1 */
    fun simulateGoldenGateAssembly(fragments: List<String>, enzyme: String): GgResult {
        val parsedFragments = mutableListOf<GgFragment>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val siteFwd = when (enzyme) { "BsaI" -> "GGTCTC"; "BsmBI" -> "CGTCTC"; else -> "GAAGAC" }
        val siteRev = when (enzyme) { "BsaI" -> "GAGACC"; "BsmBI" -> "GAGACG"; else -> "GTCTTC" }
        val offset = if (enzyme == "BbsI") 8 else 7

        for (i in fragments.indices) {
            val frag = fragments[i].trim().uppercase()
            if (frag.length < 15) {
                errors += I18n.tf("第 {v1} 个片段长度过短，无法包含 Type IIS 识别位点", i + 1)
                continue
            }
            val fwdPos = frag.indexOf(siteFwd)
            val revPos = frag.indexOf(siteRev)
            if (fwdPos >= 0 && revPos >= 0 && fwdPos < revPos) {
                val leftOverhang = jsSubstring(frag, fwdPos + offset, fwdPos + offset + 4)
                val rightOverhang = jsSubstring(frag, revPos - 4, revPos)
                val insertSeq = jsSubstring(frag, fwdPos + offset + 4, revPos - 4)
                parsedFragments += GgFragment(i, frag.length, leftOverhang, rightOverhang, insertSeq, true)
            } else {
                errors += I18n.tf("第 {v1} 个片段未包含成对的 {v2}/{v3} 限制性识别位点", i + 1, siteFwd, siteRev)
            }
        }

        if (errors.isNotEmpty()) {
            return GgResult(false, "", parsedFragments, emptyList(), emptyList(), errors, warnings)
        }

        // 标准顺序拼装 [0 -> 1 -> 2...]（fallback 行为；不做 Tauri 路径的环匹配拓扑求解）
        var productSeq = ""
        val sortedIndices = mutableListOf<Int>()
        val overhangs = mutableListOf<String>()
        val currentOverhang = parsedFragments[0].leftOverhang

        for (frag in parsedFragments) {
            productSeq += frag.insertSeq
            sortedIndices += frag.index
            overhangs += frag.leftOverhang
        }

        // 环化校验
        if (parsedFragments.last().rightOverhang != currentOverhang) {
            warnings += I18n.t("警告: 最后一个片段的右端悬垂与第一个片段的左端悬垂不匹配，无法自动环化质粒")
        }

        return GgResult(true, productSeq, parsedFragments, sortedIndices, overhangs, errors, warnings)
    }
}
