package com.spice.nativeapp

import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqIO
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.core.Spiceg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class SpicegTest {

    private val rec = SeqRecord(
        name = "pDemo",
        sequence = "ACGTACGTACGT",
        circular = true,
        features = listOf(
            Feature("ori", "rep_origin", 1, 6, 1),
            Feature("ampR", "CDS", 7, 12, -1)
        ),
        description = "demo"
    )

    @Test
    fun `write produces full schema with format and version`() {
        val text = Spiceg.write(rec, "YEAST", listOf("EcoRI", "BamHI"))
        val root = JSONObject(text)
        assertEquals("SPICE_GENE", root.getString("format"))
        assertEquals("1.0", root.getString("version"))
        assertEquals("pDemo", root.getJSONObject("meta").getString("plasmidName"))
        assertEquals("YEAST", root.getJSONObject("meta").getString("hostPreference"))
        assertTrue(root.getJSONObject("meta").getString("createdAt").endsWith("Z"))
        assertEquals("ACGTACGTACGT", root.getString("sequence"))
        val feats = root.getJSONArray("features")
        assertEquals(2, feats.length())
        val f0 = feats.getJSONObject(0)
        assertEquals("ori", f0.getString("name"))
        assertEquals(1, f0.getInt("start"))
        assertEquals(6, f0.getInt("end"))
        assertTrue(f0.getBoolean("forward"))
        assertEquals("var(--pix-accent-2)", f0.getString("color"))
        assertFalse(feats.getJSONObject(1).getBoolean("forward"))
        assertEquals("EcoRI", root.getJSONArray("selectedEnzymes").getString(0))
        // schema 完备性：selectionNotes / notes / notebookPages 均在场
        assertTrue(root.has("selectionNotes"))
        assertTrue(root.has("notes"))
        assertTrue(root.has("notebookPages"))
    }

    @Test
    fun `round trip restores record features strand and enzymes`() {
        val text = Spiceg.write(rec, "HUMAN", listOf("EcoRI"))
        val p = Spiceg.parseFull(text)!!
        assertEquals("pDemo", p.record.name)
        assertEquals("ACGTACGTACGT", p.record.sequence)
        assertTrue(p.record.circular)
        assertEquals(2, p.record.features.size)
        assertEquals("ori", p.record.features[0].name)
        assertEquals(1, p.record.features[0].start)
        assertEquals(6, p.record.features[0].end)
        assertEquals(1, p.record.features[0].strand)
        assertEquals("ampR", p.record.features[1].name)
        assertEquals(-1, p.record.features[1].strand)
        assertEquals("HUMAN", p.host)
        assertEquals(listOf("EcoRI"), p.enzymes)
    }

    @Test
    fun `isSpiceg detects spaced and compact format markers`() {
        assertTrue(Spiceg.isSpiceg("""{"format": "SPICE_GENE", "sequence": "ACGT"}"""))
        assertTrue(Spiceg.isSpiceg("""{"format":"SPICE_GENE"}"""))
        assertFalse(Spiceg.isSpiceg("LOCUS pDemo"))
        assertFalse(Spiceg.isSpiceg("""{"format": "SPICEP"}"""))
    }

    @Test
    fun `parseFull rejects wrong format and bad json`() {
        assertNull(Spiceg.parseFull("""{"format":"OTHER","sequence":"ACGT"}"""))
        assertNull(Spiceg.parseFull("not json at all"))
    }

    @Test
    fun `parseFull tolerates missing optional fields`() {
        val text = """{"format":"SPICE_GENE","meta":{"plasmidName":"pX"},"sequence":"acgtacgtac"}"""
        val p = Spiceg.parseFull(text)!!
        assertEquals("pX", p.record.name)
        assertEquals("ACGTACGTAC", p.record.sequence)   // 序列统一大写（对齐 main toUpperCase）
        assertTrue(p.record.features.isEmpty())
        assertTrue(p.enzymes.isEmpty())
        assertEquals("ECOLI", p.host)                     // 缺省宿主
    }

    @Test
    fun `SeqIO parse routes spiceg through Spiceg`() {
        val text = Spiceg.write(rec, "ECOLI", listOf("EcoRI"))
        val parsed = SeqIO.parse(text)!!
        assertEquals("pDemo", parsed.name)
        assertEquals(2, parsed.features.size)
        assertEquals("ampR", parsed.features[1].name)
    }

    @Test
    fun `write output is 2-space pretty like main stringify`() {
        val text = Spiceg.write(rec, "ECOLI", emptyList())
        assertTrue(text.contains("\n  \"format\": \"SPICE_GENE\""))
    }
}
