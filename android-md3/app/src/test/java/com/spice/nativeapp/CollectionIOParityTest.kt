package com.spice.nativeapp

import com.spice.nativeapp.core.BatchEngine
import com.spice.nativeapp.core.CollectionIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CollectionsBatchPanel（项目集合工作空间）算法层 parity 测试 —
 * 参考值由原版 SPICE_main（tsx 逐字 inline 原版 formats.ts / sequence.ts / model.ts /
 * tm.ts / primerAnalysis.ts / batch.ts + enzymes.ts 实模块 + 最小二进制 .dna 构造）
 * 生成：/tmp/cio_gen.ts → /tmp/cio_ref.json。
 * 覆盖：detectFormat / parseFasta / parseSequenceFile / parseSnapGeneDna（含过短与
 * 错 magic 报错文案）/ calculateNebTm（含简并碱基 null、verbatim 9.4^-6 盐修正）/
 * designPrimersForCloning / BatchEngine.batchDigest(DpnI 别名经 gatc/2/2) /
 * batchDesignPrimers；ZIP 导入导出为 Rust 语义（java.util.zip）自洽回环断言。
 */
class CollectionIOParityTest {

    // ── 夹具（与 cio_gen.ts 完全一致） ──
    private val fasta = ">seq one desc\nACGTNRY\nKMBSWV\n;comment\n>second\nacgt\n"
    private val gbText = ("LOCUS       pTest      47 bp    DNA     circular  01-JAN-2025\n" +
        "ORIGIN\n        1 acgatcgatc gatcgatcga tcgatcgatc gatcgatcgat cgtcg\n//\n")
    private val dnaSeq = "ACGATCGATCGATTCGATCGATCGATCGATCG"
    private val t1 = "ACGTTGCACTGCAGTTACGCATGCATGCTAACGTACGTCGATCGATCGTAGCTAGCTAGCATCGATCGTACGATCACGCGCGCGTATATATATGCGCGCGCGATAT"
    private val t2 = "T".repeat(101)
    private val p1 = "GATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATCGATC"
    private val p2 = "T".repeat(28)

    private val de = 1e-9

    // ── detectFormat ──

    @Test
    fun `detectFormat 10 例`() {
        val cases = listOf(
            "LOCUS       pUC19" to "x.gb",
            "ID   X; svm;" to "x.embl",
            "@read1" to "x.fq",
            ">seq1\nATGC" to "x.fa",
            "{\"format\": \"SPICE_GENE\"}" to "x.spiceg",
            "ATGCATGC" to "x.txt",
            "ATGCATGC" to "pET.plasmid",
            ">abc" to null,
            "plain" to "noext",
            "acgt" to "x.fasta"
        )
        val expect = listOf(
            "genbank", "embl", "fastq", "fasta", "spiceg",
            "plain", "plain", "fasta", "plain", "fasta"
        )
        cases.zip(expect).forEach { (c, e) ->
            assertEquals(e, CollectionIO.detectFormat(c.first, c.second))
        }
    }

    // ── parseFasta / parseSequenceFile ──

    @Test
    fun `parseFasta 多记录与注释行`() {
        val recs = CollectionIO.parseFasta(fasta)
        assertEquals(2, recs.size)
        assertEquals("seq", recs[0].id)
        assertEquals("one desc", recs[0].description)
        assertEquals("ACGTNRYKMBSWV", recs[0].sequence)
        assertEquals("second", recs[1].id)
        assertEquals("", recs[1].description)
        assertEquals("acgt", recs[1].sequence)
    }

    @Test
    fun `parseSequenceFile fasta 首记录`() {
        val r = CollectionIO.parseSequenceFile(fasta, "multi.fasta")
        assertEquals("seq", r.name)
        assertEquals("ACGTNRYKMBSWV", r.sequence)
        assertTrue(!r.circular)
    }

    @Test
    fun `parseSequenceFile plain 剔非法字符且文件名去扩展`() {
        val r = CollectionIO.parseSequenceFile("acgtXYZ12acgt", "My Vector.dna.txt")
        assertEquals("My Vector.dna", r.name)
        assertEquals("acgtYacgt", r.sequence)
        assertTrue(!r.circular)
    }

    @Test
    fun `parseSequenceFile fastq 与 genbank`() {
        val fq = CollectionIO.parseSequenceFile("@r1\nACGTACGTAC\n+\n!!!!!!!!!!", "s.fastq")
        assertEquals("r1", fq.name)
        assertEquals("ACGTACGTAC", fq.sequence)
        val gb = CollectionIO.parseSequenceFile(gbText, "p.gbk")
        assertEquals("pTest", gb.name)
        assertEquals("acgatcgatcgatcgatcgatcgatcgatcgatcgatcgatcgtcg", gb.sequence)
        assertTrue(gb.circular)
    }

    // ── SnapGene .dna ──

    private fun buildDna(circular: Boolean): ByteArray {
        val header = byteArrayOf(0x09) + "SnapGene".toByteArray() +
            byteArrayOf(if (circular) 1 else 0, 0)
        val payload = dnaSeq.toByteArray()
        val block0 = ByteArray(5).also { it[0] = 0; writeUInt32BE(it, 1, payload.size) }
        val xml6 = "<?xml version=\"1.0\"?><DNA circular=\"$circular\" />".toByteArray()
        val block6 = ByteArray(5).also { it[0] = 6; writeUInt32BE(it, 1, xml6.size) }
        val xml1 = ("<Features><Feature name=\" promoter \" type=\"regulatory\" color=\"#00ff00\" " +
            "direction=\"2\"><Segment range=\"2-24\"/></Feature></Features>").toByteArray()
        val block1 = ByteArray(5).also { it[0] = 1; writeUInt32BE(it, 1, xml1.size) }
        return header + block0 + payload + block6 + xml6 + block1 + xml1
    }

    private fun writeUInt32BE(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    @Test
    fun `SnapGene 环状解析含特征 XML`() {
        val r = CollectionIO.parseSnapGeneDna(buildDna(true))
        assertEquals("Imported SnapGene", r.name)
        assertEquals(dnaSeq, r.sequence)
        assertTrue(r.circular)
        assertEquals(1, r.features.size) // Android 有 DOM → 1；主版浏览器同
        val f = r.features[0]
        assertEquals(" promoter ", f.name)
        assertEquals("regulatory", f.type)
        assertEquals("#00ff00", f.color)
        assertEquals(2, f.start)
        assertEquals(24, f.end)
        assertTrue(!f.forward) // direction="2" → rev
    }

    @Test
    fun `SnapGene 线状拓扑覆盖 flags`() {
        val r = CollectionIO.parseSnapGeneDna(buildDna(false))
        assertEquals(dnaSeq, r.sequence)
        assertTrue(!r.circular)
    }

    @Test
    fun `SnapGene 过短与错 magic 报错文案`() {
        try {
            CollectionIO.parseSnapGeneDna(byteArrayOf(1, 2, 3))
            throw AssertionError("no-throw")
        } catch (e: com.spice.nativeapp.core.CollectionIO.SnapGeneFormatException) {
            assertEquals("Invalid .dna file: file is too short", e.message)
        }
        try {
            CollectionIO.parseSnapGeneDna(ByteArray(11) { 0x09 })
            throw AssertionError("no-throw")
        } catch (e: com.spice.nativeapp.core.CollectionIO.SnapGeneFormatException) {
            assertEquals("Invalid .dna file: incorrect magic string: \t\t\t\t\t\t\t\t", e.message)
        }
    }

    // ── calculateNebTm ──

    @Test
    fun `NEB Tm 六例含简并 null 与 verbatim 盐修正`() {
        val seqs = listOf(
            "ATGCATGCATGCATGCAT", "GCGCGCATATATGCGC", "AAAATTTTGGGGCCCCAA",
            "ATGCRYKM", "acgtacgtacgt", "AT"
        )
        val expect = doubleArrayOf(
            63.16322138553869, 65.89791388988266, 62.393389113381375,
            Double.NaN, 49.20433020740802, -210.3028110358373
        )
        seqs.forEachIndexed { i, s ->
            val v = CollectionIO.calculateNebTm(s)
            if (expect[i].isNaN()) assertNull(v) else assertEquals(expect[i], v!!, de)
        }
        assertEquals(63.16322138553869, CollectionIO.calculateNebTm("ATGCATGCATGCATGCAT")!!, de)
    }

    // ── designPrimersForCloning ──

    @Test
    fun `克隆引物设计常规模板`() {
        val r = CollectionIO.designPrimersForCloning(t1, 0, 100, targetTm = 60.0)
        assertEquals("ACGTTGCACTGCAGTTAC", r.forward.sequence)
        assertEquals("ACGTTGCACTGCAGTTAC", r.forward.annealingSeq)
        assertEquals(18, r.forward.length)
        assertEquals(63.031151560363526, r.forward.tm, de)
        assertEquals(50.0, r.forward.gcContent, de)
        assertEquals("GCGCGCGCATATATATAC", r.reverse.sequence)
        assertEquals("GCGCGCGCATATATATAC", r.reverse.annealingSeq)
        assertEquals(18, r.reverse.length)
        assertEquals(60.66142812442433, r.reverse.tm, de)
        assertEquals(50.0, r.reverse.gcContent, de)
    }

    @Test
    fun `克隆引物设计低 Tm 模板退火区延长至 35`() {
        val r = CollectionIO.designPrimersForCloning(t2, 0, 100, targetTm = 60.0)
        assertEquals("T".repeat(35), r.forward.sequence)
        assertEquals(35, r.forward.length)
        assertEquals(52.734673260286115, r.forward.tm, de)
        assertEquals(0.0, r.forward.gcContent, de)
        assertEquals("A".repeat(35), r.reverse.sequence)
        assertEquals(52.734673260286115, r.reverse.tm, de)
    }

    // ── batchDigest / batchDesignPrimers ──

    @Test
    fun `批量 DpnI 酶切（别名 gatc 2_2 切点）`() {
        val res = CollectionIO.batchDigest(
            listOf(
                BatchEngine.BatchSeq(sequence = p1, name = "pDam.plasmid", circular = true),
                BatchEngine.BatchSeq(sequence = p2, name = "pEmpty", circular = true)
            ),
            listOf("DpnI")
        )
        assertEquals(2, res.size)
        assertEquals("pDam.plasmid", res[0].sequenceName)
        assertEquals(18, res[0].cutsitesCount)
        assertArrayEquals(
            (3..71 step 4).toList().toIntArray(),
            res[0].cutsites.map { it.topSnipPosition + 1 }.toIntArray()
        )
        assertEquals("pEmpty", res[1].sequenceName)
        assertEquals(0, res[1].cutsitesCount)
    }

    @Test
    fun `批量引物设计含空名 Untitled 回退`() {
        val res = CollectionIO.batchDesignPrimers(
            listOf(
                BatchEngine.BatchSeq(sequence = t1, name = "T1.gbk"),
                BatchEngine.BatchSeq(sequence = t2, name = "")
            ),
            relativeStart = 0, relativeEnd = 100, targetTm = 60.0
        )
        assertEquals(2, res.size)
        assertEquals("T1.gbk", res[0].first)
        assertEquals("ACGTTGCACTGCAGTTAC", res[0].second.forward.sequence)
        assertEquals(63.031151560363526, res[0].second.forward.tm, de)
        assertEquals("GCGCGCGCATATATATAC", res[0].second.reverse.sequence)
        assertEquals(60.66142812442433, res[0].second.reverse.tm, de)
        assertEquals("Untitled", res[1].first)
        assertEquals("T".repeat(35), res[1].second.forward.sequence)
        assertEquals("A".repeat(35), res[1].second.reverse.sequence)
    }

    // ── ZIP（Rust 语义：无 '.' 名补 .gb；导入跳目录） ──

    @Test
    fun `ZIP 导出导入回环含名称补 gb 后缀`() {
        val entries = listOf(
            CollectionIO.ZipEntryData("pUC19.gb", "ATGCATGC"),
            CollectionIO.ZipEntryData("pBR322", "TTTTGGGG"), // 无 '.' → pBR322.gb
            CollectionIO.ZipEntryData("笔记.fa.txt", "ACGT")
        )
        val zip = CollectionIO.exportCollectionZip(entries)
        val back = CollectionIO.importCollectionZip(zip)
        assertEquals(3, back.size)
        assertEquals(listOf("pUC19.gb", "pBR322.gb", "笔记.fa.txt"), back.map { it.name })
        assertEquals(listOf("ATGCATGC", "TTTTGGGG", "ACGT"), back.map { it.seq })
        // 产物是合法 zip（魔数 PK\x03\x04）
        assertEquals("PK", String(zip.copyOfRange(0, 2), Charsets.US_ASCII))
    }

    @Test
    fun `简并碱基 revComp 全字母表`() {
        assertEquals("NNHDVBMKWSRYACGT", CollectionIO.revComp("ACGTRYSWMKVBHDNN"))
    }
    // ── EMBL / GenBank 特征 qualifier 解析（参考值见 /tmp/cio_ref.json emblCase/gbQual） ──

    @Test
    fun `parseEmbl ID SQ 序列与 FT 特征`() {
        val embl = ("ID   X53942; SV 1; circular; DNA; PRO; 47 BP.\n" +
            "FH   Key             Location/Qualifiers\n" +
            "FT   promoter        12..34\n" +
            "FT                   /gene=\"pA\"\n" +
            "FT   CDS             complement(40..46)\n" +
            "SQ   Sequence 47 BP;  \n" +
            "     acgatcgatcgatcgatcgatcgatcgatcgatcgatcgatcgatcgtc g 47\n" +
            "//\n")
        val r = CollectionIO.parseSequenceFile(embl, "x.embl")
        assertEquals("X53942;", r.name)
        assertTrue(r.circular)
        assertEquals("acgatcgatcgatcgatcgatcgatcgatcgatcgatcgatcgatcgtcg", r.sequence)
        assertEquals(2, r.features.size)
        assertEquals("promoter", r.features[0].type)
        assertEquals("pA", r.features[0].name)
        assertEquals(11, r.features[0].start)
        assertEquals(33, r.features[0].end)
        assertTrue(r.features[0].forward)
        assertEquals("cds", r.features[1].type)
        assertEquals("cds", r.features[1].name)
        assertEquals(39, r.features[1].start)
        assertEquals(45, r.features[1].end)
        assertTrue(!r.features[1].forward)
    }
}
