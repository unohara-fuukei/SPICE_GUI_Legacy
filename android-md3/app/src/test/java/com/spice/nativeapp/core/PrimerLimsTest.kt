package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrimerLims（main primersLims.ts + limsCollaboration.ts Feature 24/26 1:1）语义测试。
 * 参考值对照 main 原函数行为；CSV 表头在未装载词典时直返 zh 原文。
 */
class PrimerLimsTest {

    private fun primer(id: String, seq: String, loc: String = "Box A - A3") =
        StockPrimer(id = id, name = id, sequence = seq, tm = 55.0, concentration = 100, volume = 50, location = loc)

    @Test
    fun matchReturnsEmptyWhenQueryShorterThanOverlap() {
        val inv = listOf(primer("p1", "ATCGATCGATCGATC"))
        assertTrue(PrimerLims.matchQueryToStockPrimers("ATCGATCG", inv, 15).isEmpty())
    }

    @Test
    fun matchFindsSubstringContainment() {
        val stock = "ATCGATCGATCGATCGAT"
        val inv = listOf(primer("p1", stock))
        val hits = PrimerLims.matchQueryToStockPrimers(stock, inv, 15)
        assertEquals(1, hits.size)
        assertEquals("p1", hits[0].id)
    }

    @Test
    fun matchExcludesUnrelatedPrimer() {
        val inv = listOf(primer("p1", "GGGGCCCCAAAATTTTGG"))
        val hits = PrimerLims.matchQueryToStockPrimers("ATCGATCGATCGATCGAT", inv, 15)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun idtOrderFormatUsesNmScaleAndModifiers() {
        val out = PrimerLims.exportPrimerOrderFormat(
            listOf(OrderPrimer("p1", "ATCGN", "50 nmol", "DSL", mod5 = "Phos", mod3 = "Biotin")),
            "IDT"
        )
        assertEquals("Name,Sequence,Scale,Purification", out.lineSequence().first())
        assertEquals("\"p1\",\"/Phos/ATCG/Biotin/\",\"50nm\",\"DSL\"", out.lineSequence().last())
    }

    @Test
    fun sangonOrderFormatDefaultsModNone() {
        val out = PrimerLims.exportPrimerOrderFormat(
            listOf(OrderPrimer("p1", "ATCG", "50 nmol", "DSL")),
            "Sangon"
        )
        assertEquals("\"p1\",\"ATCG\",\"50 nmol\",\"DSL\",\"无\",\"无\"", out.lineSequence().last())
    }

    @Test
    fun tsingkeOrderFormatLeavesModBlank() {
        val out = PrimerLims.exportPrimerOrderFormat(
            listOf(OrderPrimer("p1", "ATCG", "50 nmol", "DSL")),
            "Tsingke"
        )
        assertEquals("Name,Sequence(5'-3'),Scale,Purification,5'-Mod,3'-Mod", out.lineSequence().first())
        assertEquals("\"p1\",\"ATCG\",\"50 nmol\",\"DSL\",\"\",\"\"", out.lineSequence().last())
    }

    @Test
    fun grid96WellMapsCoordinatesAndFillsCell() {
        val samples = listOf(GridSample("s1", "P1", "primer", "A1"))
        val grid = PrimerLims.generate2DGrid(samples, "96-well")
        assertEquals(8, grid.rows.size)
        assertEquals(12, grid.columns.size)
        assertEquals("A1", grid.grid[0][0].coordinate)
        assertNotNull(grid.grid[0][0].sample)
        assertNull(grid.grid[0][1].sample)
    }

    @Test
    fun gridDetectsPositionConflict() {
        val samples = listOf(
            GridSample("s1", "P1", "primer", "A1"),
            GridSample("s2", "P2", "primer", "a1")
        )
        val grid = PrimerLims.generate2DGrid(samples, "96-well")
        assertEquals(1, grid.overlapClashes.size)
        assertTrue(grid.overlapClashes[0].contains("a1"))
        assertTrue(grid.overlapClashes[0].contains("P1"))
        assertTrue(grid.overlapClashes[0].contains("P2"))
    }

    @Test
    fun gridFreezerUsesRRowCColCoordinates() {
        val samples = listOf(GridSample("s1", "P1", "primer", "R1-C1"))
        val grid = PrimerLims.generate2DGrid(samples, "10x10-freezer")
        assertEquals(10, grid.rows.size)
        assertEquals(10, grid.columns.size)
        assertEquals("R1-C1", grid.grid[0][0].coordinate)
        assertNotNull(grid.grid[0][0].sample)
    }
}
