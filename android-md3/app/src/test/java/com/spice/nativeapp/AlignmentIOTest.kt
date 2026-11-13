package com.spice.nativeapp

import com.spice.nativeapp.core.AlignmentIO
import com.spice.nativeapp.core.MultipleAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentIOTest {

    @Test
    fun `detects all six formats`() {
        assertEquals("clustal", AlignmentIO.detectFormat("CLUSTAL W (1.82) multiple sequence alignment\n\nseq1 ACGT"))
        assertEquals("nexus", AlignmentIO.detectFormat("#NEXUS\nbegin data;"))
        assertEquals("stockholm", AlignmentIO.detectFormat("#STOCKHOLM 1.0\n\nseq1 ACGT"))
        assertEquals("msf", AlignmentIO.detectFormat("!!AA_MULTIPLE_ALIGNMENT 1.0\n\n Name/len.. MSF: 20\n\n//"))
        assertEquals("fasta", AlignmentIO.detectFormat(">seq1\nACGTACGT"))
        assertEquals("phylip", AlignmentIO.detectFormat("  3  8"))
        // main 检测正则锚定整段前 20 字符：真实 PHYLIP 文件（计数行后接序列行）实际回退 fasta —— 1:1 保留
        assertEquals("fasta", AlignmentIO.detectFormat("  3  8\nseq01 ACGTACGTACGTACGTACGTACGTACGTAC"))
        assertEquals("fasta", AlignmentIO.detectFormat("任意未知内容"))
    }

    @Test
    fun `parses fasta alignment`() {
        val text = """
            >seqA desc here
            ACGTACGTAC
            GTAC
            >seqB
            ACGT--GTAC
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals("fasta", r.format)
        assertEquals(2, r.rows.size)
        // 头部描述只取第一个空白分隔词
        assertEquals("seqA", r.rows[0].name)
        assertEquals("ACGTACGTACGTAC", r.rows[0].seq)
        assertEquals("seqB", r.rows[1].name)
        assertEquals("ACGT--GTAC", r.rows[1].seq)
    }

    @Test
    fun `parses clustal blocks accumulating sequences`() {
        val text = """
            CLUSTAL W alignment

            seq1 ACGTACGT
            seq2 ACGTACCA
            seq1 GTTAC
            seq2 GTTGC
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals("clustal", r.format)
        assertEquals(2, r.rows.size)
        assertEquals("ACGTACGTGTTAC", r.rows[0].seq)
        assertEquals("ACGTACCAGTTGC", r.rows[1].seq)
        // 全空格行（标尺行）被跳过
        assertEquals("seq1", r.rows[0].name)
    }

    @Test
    fun `clustal ruler lines starting with space are skipped`() {
        val text = """
            CLUSTAL

            seq1 ACGTAC
            seq2 ACGTAC
                 ******
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals(2, r.rows.size)
        assertEquals("ACGTAC", r.rows[0].seq)
    }

    @Test
    fun `parses nexus matrix block`() {
        val text = """
            #NEXUS
            begin data;
              matrix
                'seq1' ACGTAC
                seq2    ACGTAC
              ;
            end;
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals("nexus", r.format)
        assertEquals(2, r.rows.size)
        // 引号去除（1:1 保留 main 行为：名称按空白切分，引号内不得含空格）
        assertEquals("seq1", r.rows[0].name)
        assertEquals("ACGTAC", r.rows[1].seq)
    }

    @Test
    fun `parses phylip with ten char names and interleaved seq`() {
        val text = """
             2  6
            sequence01 ACGTAC
            sequence02 ACGTAC
        """.trimIndent()
        // 显式格式参数（main 的 parseAlignmentFile 同样支持 format 覆盖自动检测）
        val r = AlignmentIO.parse(text, "phylip")
        assertEquals("phylip", r.format)
        assertEquals(2, r.rows.size)
        assertEquals("sequence01".take(10), r.rows[0].name)
        assertEquals("ACGTAC", r.rows[0].seq)
    }

    @Test
    fun `parses msf after separator`() {
        val text = """
            !!AA_MULTIPLE_ALIGNMENT 1.0

             Name/len  MSF: 12

            //

            seq1  ACGTAC GTAC
            seq2  ACGTAC GTAC
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals("msf", r.format)
        assertEquals(2, r.rows.size)
        // MSF 行内多段空白拼接
        assertEquals("ACGTACGTAC", r.rows[0].seq)
    }

    @Test
    fun `parses stockholm skipping annotations`() {
        val text = """
            #STOCKHOLM 1.0

            #=GF ID test
            seq1 ACGTAC
            #=GC RF xxxxxx
            seq2 ACGTAC
            //
        """.trimIndent()
        val r = AlignmentIO.parse(text)
        assertEquals("stockholm", r.format)
        assertEquals(2, r.rows.size)
        assertEquals("ACGTAC", r.rows[0].seq)
        assertEquals("ACGTAC", r.rows[1].seq)
    }

    @Test
    fun `nexus without matrix returns empty rows`() {
        val r = AlignmentIO.parse("#NEXUS\nbegin data;\nend;")
        assertEquals("nexus", r.format)
        assertTrue(r.rows.isEmpty())
    }

    @Test
    fun `imported alignment feeds multipleAlign`() {
        val text = ">a\nACGTACGTAC\n>b\nACGTTCGTAC\n>c\nACGTACGAAC"
        val imported = AlignmentIO.parse(text)
        val msa = MultipleAlign.multipleAlign(imported.rows.map { it.name to it.seq })
        assertEquals(3, msa.rows.size)
        assertEquals(10, msa.width)
        assertTrue(msa.identity > 0.6)
    }
}
