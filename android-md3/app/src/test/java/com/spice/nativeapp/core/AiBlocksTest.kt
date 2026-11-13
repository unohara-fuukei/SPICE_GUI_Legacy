package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Co-Pilot 结构化块解析（1:1 对齐 main src/lib/ai/parse.ts 的语义） */
class AiBlocksTest {

    @Test
    fun `parses bare json mutation array`() {
        val text = """
            建议如下：
            ```json
            [{"pos": 103, "from": "Q", "to": "S"}, {"pos": 45, "from": "A", "to": "V"}]
            ```
        """.trimIndent()
        val muts = AiBlocks.parseMutationBlocks(text)
        assertEquals(2, muts.size)
        assertEquals(103, muts[0].pos); assertEquals("Q", muts[0].from); assertEquals("S", muts[0].to)
        assertEquals(45, muts[1].pos); assertEquals("V", muts[1].to)
    }

    @Test
    fun `parses wrapped mutations object`() {
        val text = "```json\n{\"mutations\": [{\"pos\": 1, \"from\": \"M\", \"to\": \"L\"}]}\n```"
        val muts = AiBlocks.parseMutationBlocks(text)
        assertEquals(1, muts.size)
        assertEquals("M", muts[0].from)
    }

    @Test
    fun `drops invalid rows instead of failing the block`() {
        val text = "```json\n[{\"pos\": 0, \"from\": \"A\", \"to\": \"V\"}, {\"pos\": 12, \"from\": \"\", \"to\": \"G\"}, {\"pos\": 33, \"from\": \"Q\", \"to\": \"S\"}]\n```"
        val muts = AiBlocks.parseMutationBlocks(text)
        assertEquals(1, muts.size)
        assertEquals(33, muts[0].pos)
    }

    @Test
    fun `uppercases from and to residues`() {
        val muts = AiBlocks.parseMutationBlocks("```json\n[{\"pos\": 5, \"from\": \"q\", \"to\": \"s\"}]\n```")
        assertEquals("Q", muts[0].from); assertEquals("S", muts[0].to)
    }

    @Test
    fun `malformed json block is skipped`() {
        val text = "```json\n{broken\n```\n```json\n[{\"pos\": 2, \"from\": \"W\", \"to\": \"Y\"}]\n```"
        assertEquals(1, AiBlocks.parseMutationBlocks(text).size)
    }

    @Test
    fun `non-json fenced blocks are ignored for mutations`() {
        assertEquals(0, AiBlocks.parseMutationBlocks("```dna\nATGC\n```").size)
    }

    @Test
    fun `parses first dna block stripped and uppercased`() {
        val text = "```dna\natg cgt\nacg t\n```\n```dna\nTTTT\n```"
        assertEquals("ATGCGTACGT", AiBlocks.parseDnaBlocks(text))
    }

    @Test
    fun `returns null when no dna block`() {
        assertNull(AiBlocks.parseDnaBlocks("no blocks here"))
    }

    @Test
    fun `validates wild type against sequence`() {
        val seq = "MQKGLD"
        val rows = listOf(
            AiBlocks.Mutation(1, "M", "L"), AiBlocks.Mutation(3, "X", "Y"),   // 非法残基
            AiBlocks.Mutation(9, "Q", "Y"),                                    // 越界
            AiBlocks.Mutation(2, "W", "Y")                                     // 野生型不匹配（2 号是 Q）
        )
        val v = AiBlocks.validateMutations(rows, seq)
        assertEquals(1, v.valid.size)
        assertEquals(1, v.valid[0].pos)
        assertEquals(3, v.errors.size)
    }

    @Test
    fun `validation without sequence accepts well-formed rows`() {
        val v = AiBlocks.validateMutations(listOf(AiBlocks.Mutation(50, "A", "G")), "")
        assertEquals(1, v.valid.size)
    }

    @Test
    fun `dna validation accepts IUPAC and rejects garbage`() {
        assertEquals("ACGTUNRY" to null, AiBlocks.validateDnaSequence("acgt un ry\n"))
        assertEquals("sequence contains non-DNA characters", AiBlocks.validateDnaSequence("ATGZ").second)
        assertEquals("empty sequence", AiBlocks.validateDnaSequence("   ").second)
    }
}
