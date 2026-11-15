package com.spice.nativeapp

/**
 * CodonOptParityTest —— T7-15 CodonOptimizer 算法层 1:1 校验。
 * 期望值 ref.json 由 tsx 直跑 main src/lib/genome/codon.ts 生成：
 * 7 DNA 序列 × 3 宿主 calculateCai/optimizeCodons(isDna)、3 蛋白串 × 3 宿主 optimizeCodons、
 * BEST_CODONS 三宿主全表逐值。
 */
class CodonOptParityTest {

    @org.junit.Test
    fun cai_1_mini_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCCTTTAAATAA", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.43173598837665544, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 26.666666666666668, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 2, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 5, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_2_mini_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCCTTTAAATAA", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCGTTCAAGTAA", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_3_mini_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCCTTTAAATAA", "yeast")
        org.junit.Assert.assertEquals("cai", 0.8325532074018731, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 26.666666666666668, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 4, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 5, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_4_mini_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCCTTTAAATAA", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCTTTTAAATAA", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_5_mini_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCCTTTAAATAA", "human")
        org.junit.Assert.assertEquals("cai", 0.5448139854853322, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 26.666666666666668, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 2, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 5, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_6_mini_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCCTTTAAATAA", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCCTTCAAGTGA", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_7_lysozyme_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.4818239255491166, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.5, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 9, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 16, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_8_lysozyme_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGAAGTTCGAAAAGATGGCGAGCGGCTGGCTGTTCACCAAGTTCCTG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_9_lysozyme_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "yeast")
        org.junit.Assert.assertEquals("cai", 0.5202499300430251, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.5, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 9, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 16, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_10_lysozyme_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGAAATTTGAAAAAATGGCTTCTGGTTGGTTATTTACTAAATTTTTA", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_11_lysozyme_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "human")
        org.junit.Assert.assertEquals("cai", 0.48806867715921887, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.5, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 7, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 16, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_12_lysozyme_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGAAATTTGAAAAAATGGCGTCTGGCTGGCTATTCACAAAGTTTCTG", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGAAGTTCGAGAAGATGGCCAGCGGCTGGCTGTTCACCAAGTTCCTG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_13_all_one_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGTGGTTCTTGATTATG", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.6069622310029172, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 33.33333333333333, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 4, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 6, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_14_all_one_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGTGGTTCTTGATTATG", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGTGGTTCCTGATCATG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_15_all_one_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGTGGTTCTTGATTATG", "yeast")
        org.junit.Assert.assertEquals("cai", 0.8848579141499344, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 33.33333333333333, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 4, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 6, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_16_all_one_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGTGGTTCTTGATTATG", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGTGGTTTTTAATTATG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_17_all_one_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGTGGTTCTTGATTATG", "human")
        org.junit.Assert.assertEquals("cai", 0.6947983105209848, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 33.33333333333333, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 4, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 6, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_18_all_one_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGTGGTTCTTGATTATG", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGTGGTTCCTGATCATG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_19_dirty_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("atg-nnGCGXYZtaaCCG", "ecoli")
        org.junit.Assert.assertEquals("cai", 1.0, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 58.333333333333336, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 4, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 4, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_20_dirty_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("atg-nnGCGXYZtaaCCG", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCGTAACCG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_21_dirty_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("atg-nnGCGXYZtaaCCG", "yeast")
        org.junit.Assert.assertEquals("cai", 0.26591479484724945, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 58.333333333333336, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 2, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 4, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_22_dirty_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("atg-nnGCGXYZtaaCCG", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCTTAACCA", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_23_dirty_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("atg-nnGCGXYZtaaCCG", "human")
        org.junit.Assert.assertEquals("cai", 0.4605779351596907, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 58.333333333333336, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 2, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 4, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_24_dirty_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("atg-nnGCGXYZtaaCCG", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCCTGACCG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_25_partial_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCC", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.447213595499958, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 66.66666666666666, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 1, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 2, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_26_partial_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCC", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCG", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_27_partial_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCC", "yeast")
        org.junit.Assert.assertEquals("cai", 0.6324555320336759, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 66.66666666666666, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 1, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 2, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_28_partial_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCC", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCT", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_29_partial_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCC", "human")
        org.junit.Assert.assertEquals("cai", 1.0, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 66.66666666666666, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 2, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 2, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_30_partial_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCC", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_31_empty_ish_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("NNNNNN", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.0, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 0.0, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 0, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 0, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_32_empty_ish_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("NNNNNN", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_33_empty_ish_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("NNNNNN", "yeast")
        org.junit.Assert.assertEquals("cai", 0.0, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 0.0, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 0, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 0, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_34_empty_ish_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("NNNNNN", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_35_empty_ish_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("NNNNNN", "human")
        org.junit.Assert.assertEquals("cai", 0.0, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 0.0, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 0, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 0, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_36_empty_ish_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("NNNNNN", "human", true)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_37_longer_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "ecoli")
        org.junit.Assert.assertEquals("cai", 0.29484701224626486, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.9746835443038, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 9, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 26, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_38_longer_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "ecoli", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCGCCGAAGGTGGACTACTAACGTAACGGCCTGTAAACCATGGTGAGCATCTAAAACCGTCGTAGCGTGTAAATC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_39_longer_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "yeast")
        org.junit.Assert.assertEquals("cai", 0.3780304039241722, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.9746835443038, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 9, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 26, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_40_longer_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "yeast", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCTCCAAAAGTTGATTATTAAAGAAATGGTTTATAAACTATGGTTTCTATTTAAAATAGAAGATCTGTTTAAATT", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun cai_41_longer_human() {
        val r = com.spice.nativeapp.core.CodonOpt.calculateCai("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "human")
        org.junit.Assert.assertEquals("cai", 0.42373269098399136, r.cai, 1e-12)
        org.junit.Assert.assertEquals("gc", 37.9746835443038, r.gc, 1e-12)
        org.junit.Assert.assertEquals("optimizedCount", 9, r.optimizedCount)
        org.junit.Assert.assertEquals("totalCodons", 26, r.totalCodons)
    }

    @org.junit.Test
    fun optDna_42_longer_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ATGGCACCCAAAGTTGACTATTGACGTAATGGACTTTAAACCATGGTATCAATCTGAAACAGGCGAAGCGTTTAAATAA", "human", true)
        org.junit.Assert.assertEquals("optimized", "ATGGCCCCGAAGGTGGACTACTGAAGAAACGGCCTGTGAACCATGGTGAGCATCTGAAACAGAAGAAGCGTGTGAATC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_43_MKWVTFLL_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("MKWVTFLL", "ecoli", false)
        org.junit.Assert.assertEquals("optimized", "ACC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_44_MKWVTFLL_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("MKWVTFLL", "yeast", false)
        org.junit.Assert.assertEquals("optimized", "ACT", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_45_MKWVTFLL_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("MKWVTFLL", "human", false)
        org.junit.Assert.assertEquals("optimized", "ACC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_46_ACDEFGHIKLMNPQRSTVWY__ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ACDEFGHIKLMNPQRSTVWY*", "ecoli", false)
        org.junit.Assert.assertEquals("optimized", "GCGTGCGGCACC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_47_ACDEFGHIKLMNPQRSTVWY__yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ACDEFGHIKLMNPQRSTVWY*", "yeast", false)
        org.junit.Assert.assertEquals("optimized", "GCTTGTGGTACT", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_48_ACDEFGHIKLMNPQRSTVWY__human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("ACDEFGHIKLMNPQRSTVWY*", "human", false)
        org.junit.Assert.assertEquals("optimized", "GCCTGCGGCACC", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_49_XZB_ecoli() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("XZB", "ecoli", false)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_50_XZB_yeast() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("XZB", "yeast", false)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun optProt_51_XZB_human() {
        val r = com.spice.nativeapp.core.CodonOpt.optimizeCodons("XZB", "human", false)
        org.junit.Assert.assertEquals("optimized", "", r.optimized)
        org.junit.Assert.assertEquals("changed", 0, r.changed)
    }

    @org.junit.Test
    fun best_table_52() {
        val b1 = com.spice.nativeapp.core.CodonOpt.BEST_CODONS.getValue("ecoli")
        org.junit.Assert.assertEquals("ecoli:*", "TAA", b1.getValue("*"))
        org.junit.Assert.assertEquals("ecoli:A", "GCG", b1.getValue("A"))
        org.junit.Assert.assertEquals("ecoli:C", "TGC", b1.getValue("C"))
        org.junit.Assert.assertEquals("ecoli:D", "GAC", b1.getValue("D"))
        org.junit.Assert.assertEquals("ecoli:E", "GAA", b1.getValue("E"))
        org.junit.Assert.assertEquals("ecoli:F", "TTC", b1.getValue("F"))
        org.junit.Assert.assertEquals("ecoli:G", "GGC", b1.getValue("G"))
        org.junit.Assert.assertEquals("ecoli:H", "CAC", b1.getValue("H"))
        org.junit.Assert.assertEquals("ecoli:I", "ATC", b1.getValue("I"))
        org.junit.Assert.assertEquals("ecoli:K", "AAG", b1.getValue("K"))
        org.junit.Assert.assertEquals("ecoli:L", "CTG", b1.getValue("L"))
        org.junit.Assert.assertEquals("ecoli:M", "ATG", b1.getValue("M"))
        org.junit.Assert.assertEquals("ecoli:N", "AAC", b1.getValue("N"))
        org.junit.Assert.assertEquals("ecoli:P", "CCG", b1.getValue("P"))
        org.junit.Assert.assertEquals("ecoli:Q", "CAG", b1.getValue("Q"))
        org.junit.Assert.assertEquals("ecoli:R", "CGT", b1.getValue("R"))
        org.junit.Assert.assertEquals("ecoli:S", "AGC", b1.getValue("S"))
        org.junit.Assert.assertEquals("ecoli:T", "ACC", b1.getValue("T"))
        org.junit.Assert.assertEquals("ecoli:V", "GTG", b1.getValue("V"))
        org.junit.Assert.assertEquals("ecoli:W", "TGG", b1.getValue("W"))
        org.junit.Assert.assertEquals("ecoli:Y", "TAC", b1.getValue("Y"))
        val b2 = com.spice.nativeapp.core.CodonOpt.BEST_CODONS.getValue("yeast")
        org.junit.Assert.assertEquals("yeast:*", "TAA", b2.getValue("*"))
        org.junit.Assert.assertEquals("yeast:A", "GCT", b2.getValue("A"))
        org.junit.Assert.assertEquals("yeast:C", "TGT", b2.getValue("C"))
        org.junit.Assert.assertEquals("yeast:D", "GAT", b2.getValue("D"))
        org.junit.Assert.assertEquals("yeast:E", "GAA", b2.getValue("E"))
        org.junit.Assert.assertEquals("yeast:F", "TTT", b2.getValue("F"))
        org.junit.Assert.assertEquals("yeast:G", "GGT", b2.getValue("G"))
        org.junit.Assert.assertEquals("yeast:H", "CAT", b2.getValue("H"))
        org.junit.Assert.assertEquals("yeast:I", "ATT", b2.getValue("I"))
        org.junit.Assert.assertEquals("yeast:K", "AAA", b2.getValue("K"))
        org.junit.Assert.assertEquals("yeast:L", "TTA", b2.getValue("L"))
        org.junit.Assert.assertEquals("yeast:M", "ATG", b2.getValue("M"))
        org.junit.Assert.assertEquals("yeast:N", "AAT", b2.getValue("N"))
        org.junit.Assert.assertEquals("yeast:P", "CCA", b2.getValue("P"))
        org.junit.Assert.assertEquals("yeast:Q", "CAA", b2.getValue("Q"))
        org.junit.Assert.assertEquals("yeast:R", "AGA", b2.getValue("R"))
        org.junit.Assert.assertEquals("yeast:S", "TCT", b2.getValue("S"))
        org.junit.Assert.assertEquals("yeast:T", "ACT", b2.getValue("T"))
        org.junit.Assert.assertEquals("yeast:V", "GTT", b2.getValue("V"))
        org.junit.Assert.assertEquals("yeast:W", "TGG", b2.getValue("W"))
        org.junit.Assert.assertEquals("yeast:Y", "TAT", b2.getValue("Y"))
        val b3 = com.spice.nativeapp.core.CodonOpt.BEST_CODONS.getValue("human")
        org.junit.Assert.assertEquals("human:*", "TGA", b3.getValue("*"))
        org.junit.Assert.assertEquals("human:A", "GCC", b3.getValue("A"))
        org.junit.Assert.assertEquals("human:C", "TGC", b3.getValue("C"))
        org.junit.Assert.assertEquals("human:D", "GAC", b3.getValue("D"))
        org.junit.Assert.assertEquals("human:E", "GAG", b3.getValue("E"))
        org.junit.Assert.assertEquals("human:F", "TTC", b3.getValue("F"))
        org.junit.Assert.assertEquals("human:G", "GGC", b3.getValue("G"))
        org.junit.Assert.assertEquals("human:H", "CAC", b3.getValue("H"))
        org.junit.Assert.assertEquals("human:I", "ATC", b3.getValue("I"))
        org.junit.Assert.assertEquals("human:K", "AAG", b3.getValue("K"))
        org.junit.Assert.assertEquals("human:L", "CTG", b3.getValue("L"))
        org.junit.Assert.assertEquals("human:M", "ATG", b3.getValue("M"))
        org.junit.Assert.assertEquals("human:N", "AAC", b3.getValue("N"))
        org.junit.Assert.assertEquals("human:P", "CCG", b3.getValue("P"))
        org.junit.Assert.assertEquals("human:Q", "CAG", b3.getValue("Q"))
        org.junit.Assert.assertEquals("human:R", "AGA", b3.getValue("R"))
        org.junit.Assert.assertEquals("human:S", "AGC", b3.getValue("S"))
        org.junit.Assert.assertEquals("human:T", "ACC", b3.getValue("T"))
        org.junit.Assert.assertEquals("human:V", "GTG", b3.getValue("V"))
        org.junit.Assert.assertEquals("human:W", "TGG", b3.getValue("W"))
        org.junit.Assert.assertEquals("human:Y", "TAC", b3.getValue("Y"))
    }

}
