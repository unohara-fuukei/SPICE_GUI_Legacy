package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GeneDiagnostics.kt 诊断引擎回归测试，对齐原版 `tests/gene/diagnostics.test.ts`。
 * 同一快照产出相同 findings、相同顺序；结构问题 blocking，科学观察 warning/info。
 */
class GeneDiagnosticsTest {

    private fun snap(
        sequence: String = "ATGAAACCCGGGTTTAAATAG",
        topology: String = "circular",
        features: List<WorkspaceFeature> = listOf(
            WorkspaceFeature(1, "orfA", 1, 21, "cds", forward = true)
        )
    ): WorkspaceSnapshot = WorkspaceSnapshot("pTEST", sequence, topology, features)

    private fun finding(findings: List<DiagnosticFinding>, code: String): DiagnosticFinding? =
        findings.firstOrNull { it.code == code }

    @Test
    fun `clean document reports no findings`() {
        assertTrue(diagnoseGeneWorkspace(snap()).isEmpty())
    }

    @Test
    fun `non-IUPAC base blocks at its position`() {
        val findings = diagnoseGeneWorkspace(snap(sequence = "ATGZ"))
        val hit = finding(findings, "seq_non_iupac")
        assertNotNull(hit)
        assertEquals(FindingSeverity.BLOCKING, hit!!.severity)
        assertEquals(4, hit.scope.start)
        assertEquals(4, hit.scope.end)
    }

    @Test
    fun `out-of-range feature and duplicate ids are blocked`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ATGAAA",
                features = listOf(
                    WorkspaceFeature(1, "a", 1, 9, "misc_feature"),
                    WorkspaceFeature(1, "b", 1, 3, "misc_feature")
                )
            )
        )
        val codes = findings.map { it.code }.toSet()
        assertTrue(codes.contains("feature_range"))
        assertTrue(codes.contains("feature_duplicate_id"))
    }

    @Test
    fun `several enzyme sites in one feature keep unique finding ids`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ATGGAATTCAAAGAATTCTAAGGG",
                features = listOf(WorkspaceFeature(1, "cds", 1, 24, "cds", forward = true))
            ),
            DiagnoseOptions(enzymeSites = listOf(EnzymeSite("EcoRI", "GAATTC")))
        )
        val sites = findings.filter { it.code == "cds_enzyme_site" }
        assertEquals(2, sites.size)
        assertEquals(findings.map { it.id }.toSet().size, findings.size)
    }

    @Test
    fun `CDS length not a multiple of three warns with remainder`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ATGAAACCCT",
                features = listOf(WorkspaceFeature(1, "cds", 1, 10, "cds"))
            )
        )
        val hit = finding(findings, "cds_frame")
        assertNotNull(hit)
        assertEquals(FindingSeverity.WARNING, hit!!.severity)
        assertEquals(1, hit.params["remainder"])
    }

    @Test
    fun `internal stop codon reports its base position`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ATGTAATTTTAA",
                features = listOf(WorkspaceFeature(1, "cds", 1, 12, "cds"))
            )
        )
        val hit = finding(findings, "cds_internal_stop")
        assertNotNull(hit)
        assertEquals(FindingSeverity.WARNING, hit!!.severity)
        assertEquals(4, hit.scope.start)
        assertEquals(6, hit.scope.end)
    }

    @Test
    fun `reverse-strand CDS is translated before checking`() {
        // rc(ATGAAACCCTAA) = TTAGGGTTTCAT, starts with TTG (allowed) and stops once.
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "TTAGGGTTTCAT",
                features = listOf(WorkspaceFeature(1, "rev", 1, 12, "cds", forward = false))
            )
        )
        assertFalse(findings.any { it.code == "cds_start" })
        assertFalse(findings.any { it.code == "cds_internal_stop" })
    }

    @Test
    fun `restriction site inside a CDS reports enzyme and position`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ATGGAATTCTAA",
                features = listOf(WorkspaceFeature(1, "cds", 1, 12, "cds"))
            ),
            DiagnoseOptions(enzymeSites = listOf(EnzymeSite("EcoRI", "GAATTC")))
        )
        val hit = finding(findings, "cds_enzyme_site")
        assertNotNull(hit)
        assertEquals(FindingSeverity.WARNING, hit!!.severity)
        assertEquals("EcoRI", hit.params["enzyme"])
        assertEquals(4, hit.params["position"])
    }

    @Test
    fun `cross-origin feature accepted on circular but blocked on linear`() {
        val features = listOf(WorkspaceFeature(1, "backbone", 15, 5, "misc_feature"))
        assertTrue(diagnoseGeneWorkspace(snap(features = features)).isEmpty())
        val linear = diagnoseGeneWorkspace(snap(topology = "linear", features = features))
        val hit = finding(linear, "feature_range")
        assertNotNull(hit)
        assertEquals(FindingSeverity.BLOCKING, hit!!.severity)
        assertTrue(hit.detail.contains("linear"))
    }

    @Test
    fun `wrapped CDS maps its internal stop back across the origin`() {
        // 19..20 then 1..10 reads ATG AAA TAA CCC: the stop is at 5..7.
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "GAAATAACCCACGTACGTAT",
                features = listOf(WorkspaceFeature(1, "cds", 19, 10, "cds", forward = true))
            )
        )
        val hit = finding(findings, "cds_internal_stop")
        assertNotNull(hit)
        assertEquals(5, hit!!.scope.start)
        assertEquals(7, hit.scope.end)
        assertEquals("TAA", hit.params["codon"])
        assertEquals(5, hit.params["position"])
    }

    @Test
    fun `opposite-strand overlap across the origin is detected`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "ACGTACGTACGTACGTACGT",
                features = listOf(
                    WorkspaceFeature(1, "backbone", 15, 5, "misc_feature", forward = true),
                    WorkspaceFeature(2, "rep", 18, 2, "misc_feature", forward = false)
                )
            )
        )
        val hit = finding(findings, "feature_overlap_opposite")
        assertNotNull(hit)
        assertEquals(18, hit!!.scope.start)
        assertEquals(20, hit.scope.end)
    }

    @Test
    fun `duplicate names offer rename intents`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                features = listOf(
                    WorkspaceFeature(1, "ampR", 1, 6, "misc_feature"),
                    WorkspaceFeature(2, "ampR", 7, 12, "misc_feature")
                )
            )
        )
        val hit = finding(findings, "feature_duplicate_name")
        assertNotNull(hit)
        val intent = hit!!.fixes.first().intents.first() as Map<*, *>
        assertEquals("rename_feature", intent["type"])
        assertEquals(2, intent["featureId"])
        assertEquals("ampR (2)", intent["name"])
    }

    @Test
    fun `opposite-strand overlap and long homopolymer carry positions`() {
        val findings = diagnoseGeneWorkspace(
            snap(
                sequence = "AAAAAAAAAACCCCCCCCCCTTTTTTTTTT",
                features = listOf(
                    WorkspaceFeature(1, "fwd", 1, 20, "misc_feature", forward = true),
                    WorkspaceFeature(2, "rev", 10, 30, "misc_feature", forward = false)
                )
            )
        )
        val overlap = finding(findings, "feature_overlap_opposite")
        assertNotNull(overlap)
        assertEquals(10, overlap!!.scope.start)
        assertEquals(20, overlap.scope.end)

        val homo = finding(findings, "sequence_homopolymer")
        assertNotNull(homo)
        assertEquals("A", homo!!.params["base"])
        assertEquals(10, homo.params["length"])
        assertEquals(1, homo.scope.start)
        assertEquals(10, homo.scope.end)
    }

    @Test
    fun `workspace version hash is stable and content-sensitive`() {
        val a = snap()
        val b = snap()
        assertEquals(workspaceVersion(a), workspaceVersion(b))
        val c = snap(sequence = "ATGAAACCCGGGTTTAAATAC")
        assertTrue(workspaceVersion(a) != workspaceVersion(c))
        assertEquals(8, workspaceVersion(a).length)
    }

    @Test
    fun `demo plasmid carries no blocking finding`() {
        val demo = Bio.demoPlasmid()
        val snapshot = WorkspaceSnapshot(
            name = demo.name,
            sequence = demo.sequence,
            topology = if (demo.circular) "circular" else "linear",
            features = demo.features.map { f ->
                WorkspaceFeature(
                    id = demo.features.indexOf(f) + 1,
                    name = f.name,
                    start = f.start,
                    end = f.end,
                    type = f.type,
                    forward = f.strand != -1
                )
            }
        )
        val findings = diagnoseGeneWorkspace(snapshot)
        assertTrue(findings.none { it.severity == FindingSeverity.BLOCKING })
    }
}
