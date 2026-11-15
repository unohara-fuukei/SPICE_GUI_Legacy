package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExpressionCassetteAssembler（main genome/expressionCassette.ts 1:1）语义测试。
 * 参考值由 Node 复刻 main 原函数跑出（/tmp/cass_ref.mjs）。
 */
class ExpressionCassetteTest {

    @Test
    fun forwardPartsConcatenateWithRanges() {
        val cassette = ExpressionCassette(
            id = "c",
            topology = "linear",
            parts = listOf(
                CassettePart(id = "p1", role = "promoter", sequence = "ATG"),
                CassettePart(id = "p2", role = "cds", sequence = "GGC")
            )
        )
        val r = ExpressionCassetteAssembler.assembleExpressionCassette(cassette)
        assertEquals("ATGGGC", r.sequence)
        assertEquals("linear", r.topology)
        assertEquals(PartRange("p1", "promoter", 0, 2), r.partRanges[0])
        assertEquals(PartRange("p2", "cds", 3, 5), r.partRanges[1])
    }

    @Test
    fun reversePartIsReverseComplemented() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(
                CassettePart(id = "p1", role = "cds", sequence = "ATGC", orientation = "reverse")
            )
        )
        val r = ExpressionCassetteAssembler.assembleExpressionCassette(cassette)
        assertEquals("GCAT", r.sequence)
        assertEquals(PartRange("p1", "cds", 0, 3), r.partRanges[0])
    }

    @Test
    fun featureProjectsForward() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(
                CassettePart(
                    id = "p1", role = "promoter", sequence = "ATGGGC",
                    features = listOf(PartFeature(start = 0, end = 2, strand = 1))
                )
            )
        )
        val r = ExpressionCassetteAssembler.assembleExpressionCassette(cassette)
        assertEquals("ATGGGC", r.sequence)
        assertEquals(1, r.features.size)
        assertEquals(0, r.features[0].start)
        assertEquals(2, r.features[0].end)
        assertEquals(1, r.features[0].strand)
    }

    @Test
    fun featureProjectsReverseWithStrandFlip() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(
                CassettePart(
                    id = "p1", role = "cds", sequence = "ATGGGC", orientation = "reverse",
                    features = listOf(PartFeature(start = 0, end = 2, strand = 1))
                )
            )
        )
        val r = ExpressionCassetteAssembler.assembleExpressionCassette(cassette)
        assertEquals("GCCCAT", r.sequence)
        assertEquals(1, r.features.size)
        assertEquals(3, r.features[0].start)
        assertEquals(5, r.features[0].end)
        assertEquals(-1, r.features[0].strand)
    }

    @Test
    fun cdsOutOfFrameFailsValidation() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(CassettePart(id = "cds1", role = "cds", sequence = "ATGG"))
        )
        val r = ExpressionCassetteAssembler.assembleExpressionCassette(cassette)
        val v = ExpressionCassetteAssembler.validateVectorAssembly(
            r, ValidationRules(checkReadingFrame = true)
        )
        assertFalse(v.valid)
        assertTrue(v.issues.any { it.code == "cds_frame" && it.message.contains("cds1") })
    }

    @Test
    fun diffLinearMismatchInsertionDeletion() {
        assertEquals(
            listOf(SequenceDiff("mismatch", 3, reference = "C", actual = "X", length = 1)),
            ExpressionCassetteAssembler.diffSequences("ATGC", "ATGX")
        )
        assertEquals(
            listOf(SequenceDiff("insertion", 4, actual = "TA", length = 2)),
            ExpressionCassetteAssembler.diffSequences("ATGC", "ATGCTA")
        )
        assertEquals(
            listOf(SequenceDiff("deletion", 3, reference = "C", length = 1)),
            ExpressionCassetteAssembler.diffSequences("ATGC", "ATG")
        )
    }

    @Test
    fun diffCircularRotatesToAlign() {
        val diffs = ExpressionCassetteAssembler.diffSequences("ATGC", "GCAT", "circular")
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun vectorWithoutSiteReturnsCassetteAsIs() {
        val cassette = ExpressionCassette(
            id = "c", topology = "linear",
            parts = listOf(
                CassettePart(id = "p1", role = "promoter", sequence = "ATG"),
                CassettePart(id = "p2", role = "cds", sequence = "GGC")
            )
        )
        val template = VectorTemplate(id = "v1", sequence = "TTTT", topology = "circular")
        val r = ExpressionCassetteAssembler.assembleVector(template, cassette)
        assertEquals("ATGGGC", r.sequence)
        assertEquals("linear", r.topology)
        assertEquals(template, r.vector)
    }

    @Test
    fun vectorInsertsCassetteAtEndWithShiftedRanges() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(
                CassettePart(id = "p1", role = "promoter", sequence = "ATG"),
                CassettePart(id = "p2", role = "cds", sequence = "GGC")
            )
        )
        val site = InsertionSite(id = "site1")
        val template = VectorTemplate(
            id = "v1", sequence = "TTTT", topology = "circular",
            insertionSites = listOf(site)
        )
        val r = ExpressionCassetteAssembler.assembleVector(template, cassette)
        assertEquals("TTTTATGGGC", r.sequence)
        assertEquals("circular", r.topology)
        assertEquals(PartRange("p1", "promoter", 4, 6), r.partRanges[0])
        assertEquals(PartRange("p2", "cds", 7, 9), r.partRanges[1])
    }

    @Test
    fun vectorSiteReverseOrientsCassette() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(CassettePart(id = "p1", role = "cds", sequence = "ATGC"))
        )
        val site = InsertionSite(id = "site1", orientation = "reverse")
        val template = VectorTemplate(
            id = "v1", sequence = "TT", topology = "linear",
            insertionSites = listOf(site)
        )
        val r = ExpressionCassetteAssembler.assembleVector(template, cassette)
        // ATGC 反向互补 = GCAT，插到末端：TT + GCAT
        assertEquals("TTGCAT", r.sequence)
    }

    @Test
    fun vectorFeaturesShiftOnlyWhenPastInsertionPoint() {
        val cassette = ExpressionCassette(
            id = "c",
            parts = listOf(CassettePart(id = "p1", role = "cds", sequence = "AAA"))
        )
        val f1 = VectorFeature(id = "f1", start = 0, end = 1)
        val f2 = VectorFeature(id = "f2", start = 3, end = 3)
        val site = InsertionSite(id = "site1", afterFeatureId = "f1")
        val template = VectorTemplate(
            id = "v1", sequence = "TTCG", topology = "linear",
            features = listOf(f1, f2), insertionSites = listOf(site)
        )
        val r = ExpressionCassetteAssembler.assembleVector(template, cassette)
        // at = f1.end+1 = 2；shift=3；TT + AAA + CG = TTAAACG
        assertEquals("TTAAACG", r.sequence)
        val kept = r.features.first { it.id == "f1" }
        assertEquals(0, kept.start); assertEquals(1, kept.end)
        val shifted = r.features.first { it.id == "f2" }
        assertEquals(6, shifted.start); assertEquals(6, shifted.end)
    }
}
