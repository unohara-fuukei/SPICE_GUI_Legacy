package com.spice.nativeapp

/**
 * TranslatedProteinParityTest —— main getAminoAcidStringFromSequenceString(forward) 1:1。
 * 覆盖：标准密码子、IUPAC 简并（N/X/Y/R）、RNA-U、脏字符过滤、尾部不足3bp、
 * 尾 3bp 终止子剔除（ATGTAATAGTGA → M** 内部 * 保留）、多例尾部终止子。
 * 期望值 tsx 直跑 main src/lib/genome/sequence.ts 生成（ref2.json）。
 */
class TranslatedProteinParityTest {

    @org.junit.Test
    fun prot_01() {
        org.junit.Assert.assertEquals(
            "ATGGCCTTTAAATAA",
            "MAFK",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCCTTTAAATAA")
        )
    }

    @org.junit.Test
    fun prot_02() {
        org.junit.Assert.assertEquals(
            "ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG",
            "MKFEKMASGWLFTKFL",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG")
        )
    }

    @org.junit.Test
    fun prot_03() {
        org.junit.Assert.assertEquals(
            "ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA",
            "MAPKVDY*RNGL*TMVSI*NRRSV*I",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA")
        )
    }

    @org.junit.Test
    fun prot_04() {
        org.junit.Assert.assertEquals(
            "atg-nnGCGXYZtaaCCG",
            "MXAX*P",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("atg-nnGCGXYZtaaCCG")
        )
    }

    @org.junit.Test
    fun prot_05() {
        org.junit.Assert.assertEquals(
            "ATGGCC",
            "MA",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCC")
        )
    }

    @org.junit.Test
    fun prot_06() {
        org.junit.Assert.assertEquals(
            "ATGTGGTTCTTGATTATG",
            "MWFLIM",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGTGGTTCTTGATTATG")
        )
    }

    @org.junit.Test
    fun prot_07() {
        org.junit.Assert.assertEquals(
            "ATGNNAAYTAA",
            "MXX",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGNNAAYTAA")
        )
    }

    @org.junit.Test
    fun prot_08() {
        org.junit.Assert.assertEquals(
            "ATGGCNTAA",
            "MA",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCNTAA")
        )
    }

    @org.junit.Test
    fun prot_09() {
        org.junit.Assert.assertEquals(
            "ATRTAA",
            "X",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATRTAA")
        )
    }

    @org.junit.Test
    fun prot_10() {
        org.junit.Assert.assertEquals(
            "ATGGCT",
            "MA",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCT")
        )
    }

    @org.junit.Test
    fun prot_11() {
        org.junit.Assert.assertEquals(
            "ATGTAATAGTGA",
            "M**",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGTAATAGTGA")
        )
    }

    @org.junit.Test
    fun prot_12() {
        org.junit.Assert.assertEquals(
            "ATGGCCTAA",
            "MA",
            com.spice.nativeapp.core.CodonOpt.translatedProtein("ATGGCCTAA")
        )
    }

}
