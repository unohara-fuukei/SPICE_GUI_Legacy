package com.spice.nativeapp

import com.spice.nativeapp.core.SyntheticGeneQc
import org.junit.Assert.*
import org.junit.Test

/**
 * T7 块8 parity：对照 /tmp/qc_ref.json（main syntheticGeneQc.ts 经 npx tsx 直跑）。
 * 覆盖：隐蔽剪接检测+消除 / PolyA 检测+消除 / Kozak 分析+优化（含越界 quirk）/
 * PTC+ NMD / CPB 评分+优化 / DUST 检测+小写遮蔽。
 */
class SyntheticGeneQcParityTest {

    @Test
    fun syntheticGeneQcParity() {
        // splice1
        run {
            val res = SyntheticGeneQc.detectCrypticSpliceSites("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGCCAGGTATGAAACGCTTAAAGGGCCC")
            assertEquals(4, res.size)
            assertEquals("donor", res[0].type)
            assertEquals(3, res[0].position)
            assertEquals("CAGGTAAGT", res[0].sequence)
            assertEquals(100, res[0].score)
            assertEquals("donor", res[1].type)
            assertEquals(7, res[1].position)
            assertEquals("TAAGTAAAG", res[1].sequence)
            assertEquals(60, res[1].score)
            assertEquals("donor", res[2].type)
            assertEquals(38, res[2].position)
            assertEquals("CAGGTATGA", res[2].sequence)
            assertEquals(90, res[2].score)
            assertEquals("acceptor", res[3].type)
            assertEquals(36, res[3].position)
            assertEquals("ATTTTTTTTGGCCAGGT", res[3].sequence)
            assertEquals(88, res[3].score)
        }
        // splice2
        run {
            val res = SyntheticGeneQc.detectCrypticSpliceSites("GCCACCATGGAAAGCTTTTAA")
            assertEquals(0, res.size)
        }
        // splice3
        run {
            val res = SyntheticGeneQc.detectCrypticSpliceSites("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals(1, res.size)
            assertEquals("donor", res[0].type)
            assertEquals(22, res[0].position)
            assertEquals("AAAGTGAAT", res[0].sequence)
            assertEquals(75, res[0].score)
        }
        // spliceFix1（含失败告警日志路径）
        run {
            val res = SyntheticGeneQc.eliminateCrypticSpliceSites("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGCCAGGTATGAAACGCTTAAAGGGCCC")
            assertEquals("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGCCCGTTATGAAACGCTTAAAGGGCCC", res.modifiedSequence)
            assertEquals(1, res.mutationsCount)
            assertEquals(2, res.log.size)
            assertEquals("[剪接质控] 在第 37 bp 处引入同义突变 AGG ➔ CGT (消除了位于位点 39 处的隐蔽剪切供体位点)", res.log[0])
            assertEquals("[警告] 无法通过同义突变完全消除所有隐蔽剪接位点 (可能受限于氨基酸序列约束)", res.log[1])
        }
        // spliceFix3（含失败告警日志路径）
        run {
            val res = SyntheticGeneQc.eliminateCrypticSpliceSites("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals("AATAAAATGCATCATAATAAATCTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG", res.modifiedSequence)
            assertEquals(1, res.mutationsCount)
            assertEquals(1, res.log.size)
            assertEquals("[剪接质控] 在第 22 bp 处引入同义突变 AGT ➔ TCT (消除了位于位点 23 处的隐蔽剪切供体位点)", res.log[0])
        }
        // polya1
        run {
            val res = SyntheticGeneQc.detectCrypticPolyASignals("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals(3, res.size)
            assertEquals("AATAAA", res[0].signal)
            assertEquals(0, res[0].position)
            assertEquals("AATAAA", res[1].signal)
            assertEquals(15, res[1].position)
            assertEquals("AATAGA", res[2].signal)
            assertEquals(25, res[2].position)
        }
        // polya2
        run {
            val res = SyntheticGeneQc.detectCrypticPolyASignals("GCCACCATGGAAAGCTTTTAA")
            assertEquals(0, res.size)
        }
        // polyaFix1
        run {
            val res = SyntheticGeneQc.eliminateCrypticPolyASignals("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals("AACAAAATGCATCATAACAAAAGTGAGTAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG", res.modifiedSequence)
            assertEquals(3, res.mutationsCount)
            assertEquals(3, res.log.size)
            assertEquals("[PolyA 质控] 在第 1 bp 处引入同义突变 AAT ➔ AAC (消除了 AATAAA 提前转录终止信号)", res.log[0])
            assertEquals("[PolyA 质控] 在第 16 bp 处引入同义突变 AAT ➔ AAC (消除了 AATAAA 提前转录终止信号)", res.log[1])
            assertEquals("[PolyA 质控] 在第 25 bp 处引入同义突变 GAA ➔ GAG (消除了 AATAGA 提前转录终止信号)", res.log[2])
        }
        // polyaFix5
        run {
            val res = SyntheticGeneQc.eliminateCrypticPolyASignals("ATGCGCCGACGCCGGCGCCGGCGCCGGCTGA")
            assertEquals("ATGCGCCGACGCCGGCGCCGGCGCCGGCTGA", res.modifiedSequence)
            assertEquals(0, res.mutationsCount)
            assertEquals(0, res.log.size)
        }
        // kozak1
        run {
            val res = SyntheticGeneQc.analyzeKozak("GCCACCATGGAAAGCTTTTAA", 3)
            assertEquals("NNNGCC", res.upstreamSeq)
            assertEquals("ACC", res.startCodon)
            assertEquals("ATG", res.downstreamSeq)
            assertEquals("Weak", res.strength)
            assertEquals(0, res.score)
            assertEquals(1, res.recommendations.size)
            assertEquals("未找到 ATG 起始密码子，无法分析 Kozak 强度。", res.recommendations[0])
        }
        // kozak2
        run {
            val res = SyntheticGeneQc.analyzeKozak("GCCATCATGTAAAGCTTAAAGGGCCCTGA", 3)
            assertEquals("NNNGCC", res.upstreamSeq)
            assertEquals("ATC", res.startCodon)
            assertEquals("ATG", res.downstreamSeq)
            assertEquals("Weak", res.strength)
            assertEquals(0, res.score)
            assertEquals(1, res.recommendations.size)
            assertEquals("未找到 ATG 起始密码子，无法分析 Kozak 强度。", res.recommendations[0])
        }
        // kozak3
        run {
            val res = SyntheticGeneQc.analyzeKozak("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGCCAGGTATGAAACGCTTAAAGGGCCC", 40)
            assertEquals("CCAGGT", res.upstreamSeq)
            assertEquals("ATG", res.startCodon)
            assertEquals("AAA", res.downstreamSeq)
            assertEquals("Weak", res.strength)
            assertEquals(36, res.score)
            assertEquals(2, res.recommendations.size)
            assertEquals("建议将起始密码子后第一个碱基 (+4 位) 优化为 'G'。如果是 CDS 内部突变，可以通过选择同义密码子来实现。", res.recommendations[0])
            assertEquals("当前 Kozak 序列极弱，在哺乳动物细胞中表达可能面临极低的翻译起始效率，请进行同义突变优化。", res.recommendations[1])
        }
        // kozak4
        run {
            val res = SyntheticGeneQc.analyzeKozak("ATGGAA", 0)
            assertEquals("NNNNNN", res.upstreamSeq)
            assertEquals("ATG", res.startCodon)
            assertEquals("GAA", res.downstreamSeq)
            assertEquals("Weak", res.strength)
            assertEquals(30, res.score)
            assertEquals(2, res.recommendations.size)
            assertEquals("建议将 -3 位置的碱基突变为 'A' (首选) 或 'G'，这是决定真核翻译强度的核心元件。", res.recommendations[0])
            assertEquals("当前 Kozak 序列极弱，在哺乳动物细胞中表达可能面临极低的翻译起始效率，请进行同义突变优化。", res.recommendations[1])
        }
        // kozakOpt1（kozakOpt3 越界返回原串 quirk）
        run {
            val res = SyntheticGeneQc.optimizeKozak("GCCATCATGTAAAGCTTAAAGGGCCCTGA", 3)
            assertEquals("GCCATCATGTAAAGCTTAAAGGGCCCTGA", res.optimizedSequence)
            assertEquals(1, res.log.size)
            assertEquals("位置超出序列范围，无法进行 Kozak 优化", res.log[0])
        }
        // kozakOpt2（kozakOpt3 越界返回原串 quirk）
        run {
            val res = SyntheticGeneQc.optimizeKozak("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGCCAGGTATGAAACGCTTAAAGGGCCC", 40)
            assertEquals("CAGGTAAGTAAAGGGCCCTTTAAATTTTTTTTGGGCCACCATGAAACGCTTAAAGGGCCC", res.optimizedSequence)
            assertEquals(6, res.log.size)
            assertEquals("[Kozak 优化] 将 -6 位碱基 C 突变为 G", res.log[0])
            assertEquals("[Kozak 优化] 将 -4 位碱基 A 突变为 C", res.log[1])
            assertEquals("[Kozak 优化] 将 -3 位碱基 G 突变为 A", res.log[2])
            assertEquals("[Kozak 优化] 将 -2 位碱基 G 突变为 C", res.log[3])
            assertEquals("[Kozak 优化] 将 -1 位碱基 T 突变为 C", res.log[4])
            assertEquals("[Kozak 提示] 无法将 +4 位优化为 'G' (氨基酸 K 无以 'G' 开头的同义密码子)", res.log[5])
        }
        // kozakOpt3（kozakOpt3 越界返回原串 quirk）
        run {
            val res = SyntheticGeneQc.optimizeKozak("ATGAAA", 0)
            assertEquals("ATGAAA", res.optimizedSequence)
            assertEquals(1, res.log.size)
            assertEquals("位置超出序列范围，无法进行 Kozak 优化", res.log[0])
        }
        // ptc1
        run {
            val res = SyntheticGeneQc.detectPrematureStopCodons("GCCATCATGTAAAGCTTAAAGGGCCCTGA")
            assertEquals(true, res.hasPtc)
            assertEquals(27, res.cleanCdsLength)
            assertEquals(1, res.stops.size)
            assertEquals("TAA", res.stops[0].codon)
            assertEquals(9, res.stops[0].position)
            assertEquals(4, res.stops[0].aaPosition)
            assertEquals(false, res.stops[0].triggerNmd)
        }
        // ptc2
        run {
            val res = SyntheticGeneQc.detectPrematureStopCodons("GCCACCATGGAAAGCTTTTAA")
            assertEquals(false, res.hasPtc)
            assertEquals(21, res.cleanCdsLength)
            assertEquals(0, res.stops.size)
        }
        // ptc3
        run {
            val res = SyntheticGeneQc.detectPrematureStopCodons("ATGGCCTGA")
            assertEquals(false, res.hasPtc)
            assertEquals(9, res.cleanCdsLength)
            assertEquals(0, res.stops.size)
        }
        // ptc4
        run {
            val res = SyntheticGeneQc.detectPrematureStopCodons("ATGTAATAATAATAATAATAATAATAATAATAATAATAATAATAATAATAATAG")
            assertEquals(true, res.hasPtc)
            assertEquals(54, res.cleanCdsLength)
            assertEquals(16, res.stops.size)
            assertEquals("TAA", res.stops[0].codon)
            assertEquals(3, res.stops[0].position)
            assertEquals(2, res.stops[0].aaPosition)
            assertEquals(true, res.stops[0].triggerNmd)
            assertEquals("TAA", res.stops[1].codon)
            assertEquals(6, res.stops[1].position)
            assertEquals(3, res.stops[1].aaPosition)
            assertEquals(false, res.stops[1].triggerNmd)
            assertEquals("TAA", res.stops[2].codon)
            assertEquals(9, res.stops[2].position)
            assertEquals(4, res.stops[2].aaPosition)
            assertEquals(false, res.stops[2].triggerNmd)
            assertEquals("TAA", res.stops[3].codon)
            assertEquals(12, res.stops[3].position)
            assertEquals(5, res.stops[3].aaPosition)
            assertEquals(false, res.stops[3].triggerNmd)
            assertEquals("TAA", res.stops[4].codon)
            assertEquals(15, res.stops[4].position)
            assertEquals(6, res.stops[4].aaPosition)
            assertEquals(false, res.stops[4].triggerNmd)
            assertEquals("TAA", res.stops[5].codon)
            assertEquals(18, res.stops[5].position)
            assertEquals(7, res.stops[5].aaPosition)
            assertEquals(false, res.stops[5].triggerNmd)
            assertEquals("TAA", res.stops[6].codon)
            assertEquals(21, res.stops[6].position)
            assertEquals(8, res.stops[6].aaPosition)
            assertEquals(false, res.stops[6].triggerNmd)
            assertEquals("TAA", res.stops[7].codon)
            assertEquals(24, res.stops[7].position)
            assertEquals(9, res.stops[7].aaPosition)
            assertEquals(false, res.stops[7].triggerNmd)
            assertEquals("TAA", res.stops[8].codon)
            assertEquals(27, res.stops[8].position)
            assertEquals(10, res.stops[8].aaPosition)
            assertEquals(false, res.stops[8].triggerNmd)
            assertEquals("TAA", res.stops[9].codon)
            assertEquals(30, res.stops[9].position)
            assertEquals(11, res.stops[9].aaPosition)
            assertEquals(false, res.stops[9].triggerNmd)
            assertEquals("TAA", res.stops[10].codon)
            assertEquals(33, res.stops[10].position)
            assertEquals(12, res.stops[10].aaPosition)
            assertEquals(false, res.stops[10].triggerNmd)
            assertEquals("TAA", res.stops[11].codon)
            assertEquals(36, res.stops[11].position)
            assertEquals(13, res.stops[11].aaPosition)
            assertEquals(false, res.stops[11].triggerNmd)
            assertEquals("TAA", res.stops[12].codon)
            assertEquals(39, res.stops[12].position)
            assertEquals(14, res.stops[12].aaPosition)
            assertEquals(false, res.stops[12].triggerNmd)
            assertEquals("TAA", res.stops[13].codon)
            assertEquals(42, res.stops[13].position)
            assertEquals(15, res.stops[13].aaPosition)
            assertEquals(false, res.stops[13].triggerNmd)
            assertEquals("TAA", res.stops[14].codon)
            assertEquals(45, res.stops[14].position)
            assertEquals(16, res.stops[14].aaPosition)
            assertEquals(false, res.stops[14].triggerNmd)
            assertEquals("TAA", res.stops[15].codon)
            assertEquals(48, res.stops[15].position)
            assertEquals(17, res.stops[15].aaPosition)
            assertEquals(false, res.stops[15].triggerNmd)
        }
        // cpb1
        assertEquals(-1.0656, SyntheticGeneQc.calculateCpbScore("ATGCGCCGACGCCGGCGCCGGCGCCGGCTGA"), 1e-9)
        // cpb2
        assertEquals(0.02, SyntheticGeneQc.calculateCpbScore("GCCACCATGGAAAGCTTTTAA"), 1e-9)
        // cpb3
        assertEquals(0.0, SyntheticGeneQc.calculateCpbScore("ATG"), 1e-9)
        // cpbOpt1
        run {
            val res = SyntheticGeneQc.optimizeCodonPairs("ATGCGCCGACGCCGGCGCCGGCGCCGGCTGA")
            assertEquals("ATGAGAAGAAGAAGAAGAAGAAGAAGACTGA", res.optimizedSequence)
            assertEquals(-1.0656, res.originalCpb, 1e-9)
            assertEquals(0.02, res.optimizedCpb, 1e-9)
            assertEquals(9, res.log.size)
            assertEquals("[密码子对优化] 成功优化了 8 处密码子对，CPB 指数由 -1.0656 提升至 0.02", res.log[0])
            assertEquals("[密码子对优化] 替换不良配对 AA: M-R (ATG-CGC ➔ ATG-AGA)", res.log[1])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGA ➔ AGA-AGA)", res.log[2])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[3])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[4])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[5])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[6])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[7])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[8])
        }
        // cpbOpt2
        run {
            val res = SyntheticGeneQc.optimizeCodonPairs("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATAGATCTGGTAGAGCTTATATATATATATATAGAAGAAGAAGAAGAAGAAGA", res.optimizedSequence)
            assertEquals(-0.2619, res.originalCpb, 1e-9)
            assertEquals(0.02, res.optimizedCpb, 1e-9)
            assertEquals(12, res.log.size)
            assertEquals("[密码子对优化] 成功优化了 11 处密码子对，CPB 指数由 -0.2619 提升至 0.02", res.log[0])
            assertEquals("[密码子对优化] 替换不良配对 AA: D-R (GAT-CGA ➔ GAT-AGA)", res.log[1])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-S (AGA-TCG ➔ AGA-TCT)", res.log[2])
            assertEquals("[密码子对优化] 替换不良配对 AA: G-R (GGG-CGC ➔ GGT-AGA)", res.log[3])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-A (AGA-GCG ➔ AGA-GCT)", res.log[4])
            assertEquals("[密码子对优化] 替换不良配对 AA: Y-R (TAT-CGG ➔ TAT-AGA)", res.log[5])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[6])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[7])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[8])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[9])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGC ➔ AGA-AGA)", res.log[10])
            assertEquals("[密码子对优化] 替换不良配对 AA: R-R (AGA-CGG ➔ AGA-AGA)", res.log[11])
        }
        // dust1
        run {
            val res = SyntheticGeneQc.detectLowComplexityRegions("ATGAAAAAAAAAAAAAAAAGGGGGGGGGGGGCCCCTGA")
            assertEquals(1, res.size)
            assertEquals(0, res[0].start)
            assertEquals(32, res[0].end)
            assertEquals("ATGAAAAAAAAAAAAAAAAGGGGGGGGGGGGCC", res[0].sequence)
            assertEquals(1.42, res[0].entropy, 1e-9)
            assertEquals("low_entropy", res[0].type)
        }
        // dust2
        run {
            val res = SyntheticGeneQc.detectLowComplexityRegions("ATGATATATATATATATATATATATGGTGGTGGTTGA")
            assertEquals(1, res.size)
            assertEquals(3, res[0].start)
            assertEquals(35, res[0].end)
            assertEquals("ATATATATATATATATATATATGGTGGTGGTTG", res[0].sequence)
            assertEquals(1.52, res[0].entropy, 1e-9)
            assertEquals("low_entropy", res[0].type)
        }
        // dust3
        run {
            val res = SyntheticGeneQc.detectLowComplexityRegions("GCCACCATGGAAAGCTTTTAA")
            assertEquals(0, res.size)
        }
        // dust4
        run {
            val res = SyntheticGeneQc.detectLowComplexityRegions("AATAAAATGCATCATAATAAAAGTGAATAGAGCTAGCCTATAGCTGATCGATCGGGGCGCGCGTATATATATATATATCGGCGCCGGCGCCGGCGCCGG")
            assertEquals(1, res.size)
            assertEquals(63, res[0].start)
            assertEquals(98, res[0].end)
            assertEquals("TATATATATATATATCGGCGCCGGCGCCGGCGCCGG", res[0].sequence)
            assertEquals(1.978, res[0].entropy, 1e-9)
            assertEquals("low_entropy", res[0].type)
        }
        // mask1（小写遮蔽）
        run {
            val res = SyntheticGeneQc.maskLowComplexityRegions("ATGAAAAAAAAAAAAAAAAGGGGGGGGGGGGCCCCTGA")
            assertEquals("atgaaaaaaaaaaaaaaaaggggggggggggccCCTGA", res.maskedSequence)
            assertEquals(1, res.regions.size)
        }
        // mask2（小写遮蔽）
        run {
            val res = SyntheticGeneQc.maskLowComplexityRegions("ATGATATATATATATATATATATATGGTGGTGGTTGA")
            assertEquals("ATGatatatatatatatatatatatggtggtggttgA", res.maskedSequence)
            assertEquals(1, res.regions.size)
        }
    }
}
