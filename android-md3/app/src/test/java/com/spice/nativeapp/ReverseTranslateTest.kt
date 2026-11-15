package com.spice.nativeapp

import com.spice.nativeapp.core.ReverseTranslate
import com.spice.nativeapp.core.ReverseTranslate.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReverseTranslateTest —— main src/lib/genome/advancedTools.ts reverseTranslate() 1:1 的手推期望。
 * main 无现成测试；期望值按 main 算法逐分支推导（首选密码子分支：DEFAULT_CODON_TABLE 优先）。
 */
class ReverseTranslateTest {

    @Test
    fun default_codon_table_preferred_choice() {
        // MKT → M=ATG K=AAG T=ACC（DEFAULT_CODON_TABLE 逐值）
        val r = ReverseTranslate.reverseTranslate("MKT")
        assertEquals("ATGAAGACC", r.dnaSequence)
        assertEquals("ATGAAGACC", r.cdnaSequence)
        assertEquals("AUGAAGACC", r.mrnaSequence)
        assertEquals("MKT", r.proteinSequence)
        assertTrue(r.validation.valid)
        assertEquals(3, r.codonTrace.size)
        assertEquals(0, r.warnings.size)
    }

    @Test
    fun host_does_not_change_preferred_codons() {
        // main：首选有效且 !optimizeGc 时直接返回首选 —— 宿主不改变标准 AA 的密码子
        val ecoli = ReverseTranslate.reverseTranslate("MKT", Options(host = "ecoli"))
        val yeast = ReverseTranslate.reverseTranslate("MKT", Options(host = "yeast"))
        val human = ReverseTranslate.reverseTranslate("MKT", Options(host = "human"))
        assertEquals(ecoli.dnaSequence, yeast.dnaSequence)
        assertEquals(ecoli.dnaSequence, human.dnaSequence)
    }

    @Test
    fun include_stop_appends_taa() {
        val r = ReverseTranslate.reverseTranslate("MKT", Options(includeStop = true))
        assertEquals("ATGAAGACCTAA", r.dnaSequence)
        assertTrue(r.validation.valid)
        // 校验翻译回 "*MKT"？main：校验只比对 requested vs translated —— translated 含尾部 *，
        // 但 mismatches 只按 requested 索引比对（requested 比 translated 短，无 mismatch）。
        assertEquals("MKT*", r.validation.translatedProtein)
    }

    @Test
    fun invalid_stop_codon_warns_and_skips() {
        val r = ReverseTranslate.reverseTranslate("MKT", Options(includeStop = true, stopCodon = "AAA"))
        assertEquals("ATGAAGACC", r.dnaSequence) // 无效终止子不追加
        assertTrue(r.warnings.any { it.contains("终止密码子无效") && it.contains("AAA") })
    }

    @Test
    fun unknown_chars_stripped_with_warning() {
        val r = ReverseTranslate.reverseTranslate("MK1T")
        assertEquals("MK1T".replace("1", "").length, 3)
        assertEquals("MKT", r.proteinSequence)
        assertTrue(r.warnings.any { it.contains("无法识别的字符") })
    }

    @Test
    fun unknown_amino_acid_yields_nnn_and_invalid_codon() {
        val r = ReverseTranslate.reverseTranslate("MXK") // X 无首选/同义密码子
        assertEquals("ATGNNNAAG", r.dnaSequence)
        assertTrue(r.warnings.any { it.contains("无法为 X 选择有效密码子") })
        assertTrue(r.validation.invalidCodons.isNotEmpty())
        assertTrue(!r.validation.valid)
        assertTrue(r.warnings.any { it.contains("反向翻译校验失败") })
    }

    @Test
    fun codon_trace_geometry_and_gc() {
        val r = ReverseTranslate.reverseTranslate("MKT")
        val t0 = r.codonTrace[0]
        assertEquals(0, t0.index)
        assertEquals("M", t0.aminoAcid)
        assertEquals("ATG", t0.codon)
        assertEquals(0, t0.dnaStart)
        assertEquals(3, t0.dnaEnd)
        assertEquals(33.3, t0.gcPercent, 0.05) // ATG: G 计 1 → 1/3*100=33.3
        val t2 = r.codonTrace[2]
        assertEquals("ACC", t2.codon)
        assertEquals(6, t2.dnaStart)
        assertEquals(9, t2.dnaEnd)
        assertEquals(66.7, t2.gcPercent, 0.05) // ACC: 2/3*100=66.66→66.7
    }

    @Test
    fun avoid_site_present_warns() {
        // CTG（L 首选）不含 GAATTC；构造含 EcoRI 位点的输入：GAA GAA TTC → GAAGAATTC 含 GAATTC
        val r = ReverseTranslate.reverseTranslate("EEF", Options(avoidRestrictionSites = listOf("GAATTC")))
        assertEquals("GAAGAATTC", r.dnaSequence)
        assertTrue(r.warnings.any { it.contains("GAATTC") && it.contains("应避免的限制酶位点") })
    }

    @Test
    fun start_codon_override_for_leading_m() {
        // main 行为：GTG 覆盖首 M 密码子后，回译为 V≠M → 校验失败（有意行为，不改）
        val r = ReverseTranslate.reverseTranslate("MK", Options(startCodon = "GTG"))
        assertEquals("GTGAAG", r.dnaSequence)
        assertTrue(!r.validation.valid)
        assertEquals(0, r.validation.mismatches.first().index)
        assertEquals("M", r.validation.mismatches.first().requested)
        assertEquals("V", r.validation.mismatches.first().translated)
    }

    @Test
    fun stop_amino_acid_maps_to_taa() {
        val r = ReverseTranslate.reverseTranslate("M*")
        assertEquals("ATGTAA", r.dnaSequence)
        assertTrue(r.validation.valid)
    }
}
