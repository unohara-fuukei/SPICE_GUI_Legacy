package com.spice.nativeapp

import androidx.compose.ui.graphics.Color
import com.spice.nativeapp.ui.featureColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 特征类型色表 —— main src/lib/genome/demo.ts:28 colorForType verbatim。
 * demo 特征 color 全部由 type 派生，图谱色带/特征表色块/浮动卡必须同色。
 */
class FeatureColorTest {

    private fun assertHex(type: String, hex: String) {
        assertEquals("type=$type", Color(0xFF000000.toInt() or hex.drop(1).toLong(16).toInt()), featureColor(type))
    }

    @Test
    fun `verbatim main colorForType table`() {
        assertHex("CDS", "#ef6500")
        assertHex("cds", "#ef6500")
        assertHex("gene", "#684e27")
        assertHex("promoter", "#31b440")
        assertHex("terminator", "#f51600")
        assertHex("rep_origin", "#878787")
        assertHex("origin", "#878787")
        assertHex("misc_marker", "#8dceb1")
        assertHex("protein_bind", "#2e2e2e")
        assertHex("misc_binding", "#006fef")
        assertHex("rbs", "#bdffcb")
        assertHex("RBS", "#bdffcb")
        assertHex("misc_feature", "#006fef")
        assertHex("primer_bind", "#53d969")
        assertHex("tag", "#e419da")
        assertHex("signal_peptide", "#2fff8d")
    }

    @Test
    fun `fallback and blank type`() {
        assertHex("unknown_type", "#4cd6ff")
        assertHex("", "#006fef")   // main: type || "misc_feature" —— JS 空串为 falsy
    }

    @Test
    fun `demo 22 features all derive their main colors`() {
        // araC(CDS) 橙、pBAD promoter 绿、dbl term(terminator) 红、pSC101**(rep_origin) 灰、
        // CmR(misc_marker) 青绿、Operator(protein_bind) 深灰、CAP site(misc_binding) 蓝、
        // RBS 淡绿、GFPuv(CDS) 橙 —— 与 main loadOveDemoPlasmid 派生结果一致
        assertHex("CDS", "#ef6500")
        assertHex("promoter", "#31b440")
        assertHex("terminator", "#f51600")
        assertHex("rep_origin", "#878787")
        assertHex("misc_marker", "#8dceb1")
        assertHex("protein_bind", "#2e2e2e")
        assertHex("misc_binding", "#006fef")
        assertHex("RBS", "#bdffcb")
        assertHex("misc_feature", "#006fef")
    }
}
