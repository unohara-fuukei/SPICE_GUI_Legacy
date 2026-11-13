package com.spice.nativeapp

import com.spice.nativeapp.core.AutoAnnotate
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 自动注释移植验证（对齐 main advancedTools.ts:475-660 + +page.svelte:2627 runAutoAnnotate）：
 * exact 正/反向匹配、kmer 容错、runOnRecord 幂等、inferFeatureType。
 */
class AutoAnnotateTest {

    private val t7 = "TAATACGACTCACTATAGGG"

    @Test
    fun `exact forward match`() {
        val seq = "TTTT$t7" + "AAAA"
        val r = AutoAnnotate.annotateFeatures(seq, AutoAnnotate.commonFeatureLibrary(), "exact")
        val hit = r.features.first { it.name == "T7 promoter" }
        assertEquals(4, hit.start)          // 0-based：T 在第 4 位（0-idx）
        assertEquals(23, hit.end)
        assertTrue(hit.forward)
    }

    @Test
    fun `exact reverse-complement match`() {
        val rc = Bio.revComp(t7)
        val seq = "GG$rc"
        val r = AutoAnnotate.annotateFeatures(seq, AutoAnnotate.commonFeatureLibrary(), "exact")
        val hit = r.features.first { it.name == "T7 promoter" && !it.forward }
        assertEquals(2, hit.start)
        assertEquals(2 + rc.length - 1, hit.end)
    }

    @Test
    fun `kmer tolerant to single SNP in element body`() {
        // MCS（54bp，库 verbatim）中段 1 个 SNP：exact 应丢，kmer 应命中
        val mcs = "GAATTCGAGCTCCGTCGACAAGCTTCTGCAGGGTACCGGATCCTCTAGA"
        val mutated = mcs.substring(0, 28) + (if (mcs[28] == 'A') 'C' else 'A') + mcs.substring(29)
        val exact = AutoAnnotate.annotateFeatures(mutated, AutoAnnotate.commonFeatureLibrary(), "exact")
        val kmer = AutoAnnotate.annotateFeatures(mutated, AutoAnnotate.commonFeatureLibrary(), "kmer")
        assertTrue(exact.features.none { it.name == "MCS" })
        // SNP 落在种子后段之外也可命中（tol=2）；种子前 22bp 保持完整
        assertTrue(kmer.features.any { it.name == "MCS" && it.end - it.start + 1 >= mcs.length * 0.6 })
    }

    @Test
    fun `runOnRecord skips existing and is 1-based idempotent`() {
        val rec = SeqRecord("pTest", "AA$t7${"CG".repeat(40)}")
        val first = AutoAnnotate.runOnRecord(rec, "exact")
        assertTrue(first.isNotEmpty())
        // 1-based 坐标：T7 promoter 从第 3 位起
        val f = first.first { it.name == "T7 promoter" }
        assertEquals(3, f.start)
        assertEquals(22, f.end)
        val rec2 = rec.copy(features = rec.features + first)
        val second = AutoAnnotate.runOnRecord(rec2, "exact")
        assertTrue(second.isEmpty())
        // 幂等：已存在 name|start|end 不追加（rec3 与 rec2 检出数一致）
        val third = AutoAnnotate.runOnRecord(rec.copy(features = first + first), "exact")
        assertTrue(third.isEmpty())
    }

    @Test
    fun `inferFeatureType regex order`() {
        assertEquals("promoter", AutoAnnotate.inferFeatureType("T7 promoter"))
        assertEquals("terminator", AutoAnnotate.inferFeatureType("T7 terminator"))
        assertEquals("ori", AutoAnnotate.inferFeatureType("pMB1 origin"))
        assertEquals("cds", AutoAnnotate.inferFeatureType("AmpR bla resistance"))
        assertEquals("tag", AutoAnnotate.inferFeatureType("6x His-tag"))
        assertEquals("misc", AutoAnnotate.inferFeatureType("random thing"))
    }

    @Test
    fun `library is verbatim 14 entries`() {
        val lib = AutoAnnotate.commonFeatureLibrary()
        assertEquals(14, lib.size)
        assertEquals(listOf("T7 promoter", "SP6 promoter", "lac promoter", "T7 terminator", "pMB1 ori",
            "AmpR", "KanR", "CmR", "His-tag", "FLAG-tag", "HA-tag", "Myc-tag", "GST-tag", "MCS"), lib.map { it.name })
        assertEquals("#ff5d5d", lib.first { it.name == "T7 promoter" }.color)
        assertEquals("#ff5df6", lib.first { it.name == "MCS" }.color)
    }

    @Test
    fun `annotate color derivation via FEATURE_COLORS`() {
        val custom = listOf(AutoAnnotate.LibraryEntry("my random part", "ATATATGCATGCATATGCAT"))
        val r = AutoAnnotate.annotateFeatures(custom.first().seq, custom, "exact")
        // inferFeatureType("my random part") → misc → #5df6ff
        assertEquals("#5df6ff", r.features.first().colorHex)
    }

    @Test
    fun `feature carries matched color through Feature`() {
        val rec = SeqRecord("pTest", t7)
        val feats = AutoAnnotate.runOnRecord(rec, "exact")
        val f = feats.first { it.name == "T7 promoter" }
        assertEquals("#ff5d5d", f.color)
        assertEquals(Feature::class, f::class)
    }
}
