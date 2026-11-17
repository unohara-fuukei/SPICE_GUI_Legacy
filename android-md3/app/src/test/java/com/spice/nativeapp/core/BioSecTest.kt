package com.spice.nativeapp.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from main `tests/biosec/screen.test.ts`. The `assessPlanRisk`
 * (gate.ts) cases are deferred with the workspace-intents wiring.
 */
class BioSecTest {

    private fun envelope(rows: List<AmrFinderRow>): RiskCatalogEnvelope<AmrFinderRow> =
        RiskCatalogEnvelope(
            source = "amrfinder",
            origin = RiskCatalogOrigin.NETWORK,
            version = "2026-10-29.1",
            fetchedAt = 1_700_000_000,
            licence = "Public domain (US Government Work)",
            endpoint = "https://example.test/amrfinder/",
            error = null,
            records = rows
        )

    private fun row(
        gene: String,
        pattern: String,
        accession: String? = null,
        kind: String = "AMR",
        allele: String? = null,
        description: String? = null
    ) = AmrFinderRow(
        gene = gene,
        kind = kind,
        allele = allele ?: gene,
        description = description ?: "Resistance gene",
        klass = "BETA-LACTAM",
        subclass = "PENICILLIN",
        accession = accession ?: gene,
        pattern = pattern
    )

    private val commec = CommecStatus(
        installed = true,
        version = "1.1.0",
        path = "/tmp/commec",
        models = 3091,
        mustFlagModels = 807,
        fetchedAt = 1_700_000_000,
        licence = "MIT (IBBIS)",
        sourceUrl = "https://example.test/commec",
        sha256 = "abc123",
        error = null
    )

    private fun hit(
        mustFlag: Boolean = true,
        category: String = "biorisk",
        model: String = "P02879"
    ) = BioriskHit(
        model = model,
        description = "Ricin",
        mustFlag = mustFlag,
        category = category,
        evalue = 1e-40,
        score = 120.0,
        coverage = 0.9,
        frame = 1,
        strand = "+",
        start = 5,
        end = 200
    )

    // ---- risk catalog normalisation ----

    @Test
    fun `drops signatures that are too short or not DNA and reports the count`() {
        val parsed = amrCatalogFromEnvelope(
            envelope(
                listOf(
                    row("short", "ACGT"),
                    row("ambiguous", "ACGTZACGTACGT"),
                    row("blaTEM-1", "ATCAGTCAACC")
                )
            )
        )
        assertEquals(1, parsed.records.size)
        assertEquals(listOf("ATCAGTCAACC"), parsed.records[0].patterns)
        assertEquals(RiskCategory.AMR, parsed.records[0].category)
        assertEquals(2, parsed.droppedPatterns)
        assertTrue(parsed.contentHash.matches(Regex("^[0-9a-f]{8}$")))
    }

    @Test
    fun `keeps the catalogue accession as the stable record identity`() {
        val parsed = amrCatalogFromEnvelope(
            envelope(listOf(row("blaTEM-1", "ATCAGTCAACC", accession = "WP_000000001.1")))
        )
        assertEquals("amrfinder:WP_000000001.1", parsed.records[0].id)
        assertEquals("BETA-LACTAM", parsed.records[0].klass)
    }

    // ---- screenSequence ----

    @Test
    fun `reports a forward match with a 1-based span`() {
        val amr = amrCatalogFromEnvelope(envelope(listOf(row("blaTEM-1", "ATCAGTCAACC"))))
        val hit = screenSequence("AAAAATCAGTCAACCCCCC", listOf(amr)).first()
        assertEquals(5, hit.scope.start)
        assertEquals(15, hit.scope.end)
        assertEquals("+", hit.scope.strand)
        assertEquals(FindingSeverity.WARNING, hit.severity)
        assertFalse(hit.requiresReview)
        assertEquals("blaTEM-1", hit.params["name"])
    }

    @Test
    fun `finds the reverse complement without moving the span`() {
        // rc(AACCGGTTAACC) = GGTAACCGGTT
        val patternCatalog = amrCatalogFromEnvelope(envelope(listOf(row("marker", "AACCGGTTAACC"))))
        val hit = screenSequence("TTTGGTTAACCGGTTAAA", listOf(patternCatalog)).first()
        assertEquals(4, hit.scope.start)
        assertEquals(15, hit.scope.end)
        assertEquals("-", hit.scope.strand)
    }

    @Test
    fun `wraps a circular match across the origin`() {
        val patternCatalog = amrCatalogFromEnvelope(envelope(listOf(row("marker", "AAAACCCCGG"))))
        assertTrue(screenSequence("CCCCGGGGAAAA", listOf(patternCatalog), ScreenOptions(circular = false)).isEmpty())
        val hits = screenSequence("CCCCGGGGAAAA", listOf(patternCatalog), ScreenOptions(circular = true))
        assertEquals(1, hits.size)
        assertEquals(9, hits[0].scope.start)
        assertEquals(18, hits[0].scope.end)
        assertEquals(true, hits[0].evidence["wrapped"])
    }

    @Test
    fun `keeps document coordinates when the sequence contains ambiguity codes`() {
        val marker = amrCatalogFromEnvelope(envelope(listOf(row("marker", "TCTGGAGCGCATGATT"))))
        val hits = screenSequence("NNNNNTCTGGAGCGCATGATTCCC", listOf(marker))
        assertEquals(6, hits[0].scope.start)
        assertEquals(21, hits[0].scope.end)
    }

    @Test
    fun `reports a single reverse-strand occurrence once`() {
        val reverse = amrCatalogFromEnvelope(envelope(listOf(row("marker", "AACCGGTTAACC"))))
        val hits = screenSequence("GGTTAACCGGTT", listOf(reverse))
        assertEquals(1, hits.size)
        assertEquals("-", hits[0].scope.strand)
    }

    @Test
    fun `scans a large catalogue without one substring search per record`() {
        val encode = { value: Int ->
            value.toString(4).padStart(6, '0').map { "ACGT"[it.digitToInt()] }.joinToString("")
        }
        val records = (0 until 2000).map { index ->
            row(
                gene = "gene-$index",
                pattern = "AAAACCCCGGGGTTTT${encode(index)}",
                accession = "TEST_$index"
            )
        }
        val big = amrCatalogFromEnvelope(envelope(records))
        val hits = screenSequence("GGGGAAAACCCCGGGGTTTT${encode(42)}CCCC", listOf(big))
        assertEquals(1, hits.size)
        assertEquals("gene-42", hits[0].params["name"])
    }

    // ---- catalogue classes ----

    @Test
    fun `labels virulence and stress entries apart from resistance genes`() {
        val parsed = amrCatalogFromEnvelope(
            envelope(
                listOf(
                    row("stxA2b", "ATCAGTCAACC", kind = "VIRULENCE"),
                    row("merA", "GTGATCCTGGG", kind = "STRESS")
                )
            )
        )
        val hits = screenSequence("ATCAGTCAACCGGGGGGGGGGGGGTGATCCTGGG", listOf(parsed))
        val byName = hits.associateBy { it.params["name"] as String }
        assertEquals(listOf(RiskCategory.VIRULENCE, RiskCategory.STRESS), parsed.records.map { it.category })
        assertEquals("risk_virulence_match", byName["stxA2b"]!!.code)
        assertEquals("risk_stress_match", byName["merA"]!!.code)
    }

    // ---- biorisk findings ----

    @Test
    fun `marks a must-flag model as blocking and keeps document coordinates`() {
        val finding = bioriskHitsToFindings(listOf(hit()), commec).first()
        assertEquals(FindingSeverity.BLOCKING, finding.severity)
        assertEquals("risk_match", finding.code)
        assertTrue(finding.requiresReview)
        assertEquals(5, finding.scope.start)
        assertEquals(200, finding.scope.end)
        assertEquals("+", finding.scope.strand)
        assertEquals("commec", finding.evidence["source"])
        assertEquals("P02879", finding.evidence["recordId"])
    }

    @Test
    fun `marks a virulence-factor model as a warning without review`() {
        val finding = bioriskHitsToFindings(
            listOf(hit(mustFlag = false, category = "virulence", model = "VF0001")),
            commec
        ).first()
        assertEquals(FindingSeverity.WARNING, finding.severity)
        assertEquals("risk_virulence_match", finding.code)
        assertFalse(finding.requiresReview)
    }

    @Test
    fun `biorisk returns nothing when commec status is absent`() {
        assertTrue(bioriskHitsToFindings(listOf(hit()), null).isEmpty())
    }

    // ---- newRiskFindings ----

    @Test
    fun `reports only catalog records that did not already match`() {
        val first = amrCatalogFromEnvelope(envelope(listOf(row("first", "TCTGGAGCGCATGATT"))))
        val second = amrCatalogFromEnvelope(envelope(listOf(row("second", "TATAATGAGTCACA"))))
        val before = screenSequence("AAATCTGGAGCGCATGATT", listOf(first, second))
        val after = screenSequence("AAATCTGGAGCGCATGATTTATAATGAGTCACA", listOf(first, second))
        val introduced = newRiskFindings(before, after)
        assertEquals(1, introduced.size)
        assertEquals("second", introduced[0].params["name"])
    }

    @Test
    fun `reports nothing when a plan keeps the same match`() {
        val first = amrCatalogFromEnvelope(envelope(listOf(row("first", "TCTGGAGCGCATGATT"))))
        val before = screenSequence("AAATCTGGAGCGCATGATT", listOf(first))
        val after = screenSequence("TTTAAATCTGGAGCGCATGATTGGG", listOf(first))
        assertTrue(newRiskFindings(before, after).isEmpty())
    }
}
