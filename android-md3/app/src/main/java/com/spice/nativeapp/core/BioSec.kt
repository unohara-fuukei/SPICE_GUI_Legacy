package com.spice.nativeapp.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Biosecurity screening core, ported 1:1 from main `src/lib/biosec` modules.
 *
 * AMR is an exact DNA catalogue (NCBI AMRFinderPlus); the 12-mer hash index,
 * both strands, circular wrap and per-record hit cap live here. Commec
 * profile-HMM matching runs on the Rust/desktop side and is out of Android
 * scope; `bioriskHitsToFindings` keeps only the result-labeling shape so a
 * future installed-model path can reuse it.
 */

// ---- main: biosec/types.ts + backend/types.ts ----

enum class RiskSource(val code: String) {
    AMRFINDER("amrfinder"),
    COMMEC("commec");

    companion object {
        fun fromCode(code: String): RiskSource = entries.firstOrNull { it.code == code } ?: AMRFINDER
    }
}

enum class RiskCategory(val code: String) {
    AMR("amr"),
    BIORISK("biorisk"),
    VIRULENCE("virulence"),
    STRESS("stress"),
    UNKNOWN("unknown");

    companion object {
        fun fromCode(code: String): RiskCategory = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class RiskCatalogOrigin(val code: String) {
    NETWORK("network"),
    CACHE("cache"),
    BUILTIN("builtin");

    companion object {
        fun fromCode(code: String): RiskCatalogOrigin = entries.firstOrNull { it.code == code } ?: NETWORK
    }
}

/** One raw AMRFinderPlus row from the transport envelope. */
data class AmrFinderRow(
    val gene: String = "",
    /** The catalogue's own class: AMR, VIRULENCE or STRESS. */
    val kind: String = "",
    val allele: String = "",
    val description: String = "",
    val klass: String = "",
    val subclass: String = "",
    val accession: String = "",
    val pattern: String = ""
)

data class RiskCatalogEnvelope<T>(
    val source: String,
    val origin: RiskCatalogOrigin,
    val version: String,
    val fetchedAt: Long,
    val licence: String,
    val endpoint: String?,
    val error: String?,
    val records: List<T>
)

data class RiskRecord(
    val source: RiskSource,
    /** Stable across syncs: `amrfinder:<accession>`. */
    val id: String,
    val name: String,
    val description: String,
    val category: RiskCategory,
    /** Normalised DNA signatures, uppercase A/C/G/T only. */
    val patterns: List<String>,
    /** AMRFinderPlus class / subclass, when the catalogue names them. */
    val klass: String? = null,
    val subclass: String? = null
)

data class RiskCatalog(
    val source: RiskSource,
    val origin: RiskCatalogOrigin,
    val version: String,
    /** Hash of the normalised records, so a verdict can name its catalog exactly. */
    val contentHash: String,
    val fetchedAt: Long,
    val licence: String,
    val endpoint: String?,
    val error: String?,
    val records: List<RiskRecord>,
    val patternCount: Int,
    /** Signatures rejected because they were empty, non-ACGT or too short. */
    val droppedPatterns: Int
)

/** Commec installation status (Rust/desktop side; Android has no Commec install). */
data class CommecStatus(
    val installed: Boolean,
    val version: String,
    val path: String?,
    val models: Int,
    val mustFlagModels: Int,
    val fetchedAt: Long,
    val licence: String,
    val sourceUrl: String,
    val sha256: String,
    val error: String?
)

/** One profile-HMM match from the Commec biorisk database. */
data class BioriskHit(
    val model: String,
    val description: String,
    val mustFlag: Boolean,
    val category: String,
    val evalue: Double,
    val score: Double,
    val coverage: Double,
    val frame: Int,
    val strand: String,
    val start: Int,
    val end: Int
)

// ---- main: biosec/catalog.ts ----

const val MIN_PATTERN_LENGTH = 10
/** Below this a signature is a marker, not a gene; findings say so. */
const val SHORT_SIGNATURE_LENGTH = 20

private data class NormalisedPatterns(val values: List<String>, val dropped: Int)

fun amrCatalogFromEnvelope(envelope: RiskCatalogEnvelope<AmrFinderRow>): RiskCatalog {
    val records = mutableListOf<RiskRecord>()
    val seen = mutableSetOf<String>()
    var patternCount = 0
    var droppedPatterns = 0

    for (row in envelope.records) {
        val name = listOf(row.gene, row.allele, row.accession).firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (name.isEmpty()) {
            droppedPatterns += 1
            continue
        }
        val patterns = normalisePatterns(row.pattern)
        droppedPatterns += patterns.dropped
        if (patterns.values.isEmpty()) continue

        var id = "amrfinder:${row.accession.ifBlank { slug(name) }}"
        var suffix = 2
        while (seen.contains(id)) id = "amrfinder:${slug(name)}-${suffix++}"
        seen.add(id)

        patternCount += patterns.values.size
        records.add(
            RiskRecord(
                source = RiskSource.AMRFINDER,
                id = id,
                name = name,
                description = row.description.ifBlank { row.allele },
                category = catalogCategory(row.kind),
                patterns = patterns.values,
                klass = row.klass,
                subclass = row.subclass
            )
        )
    }

    return RiskCatalog(
        source = RiskSource.AMRFINDER,
        origin = envelope.origin,
        version = envelope.version.ifBlank { "unknown" },
        contentHash = hashRecords(records),
        fetchedAt = envelope.fetchedAt,
        licence = envelope.licence.ifBlank { "unknown" },
        endpoint = envelope.endpoint,
        error = envelope.error,
        records = records,
        patternCount = patternCount,
        droppedPatterns = droppedPatterns
    )
}

/**
 * Aggregate freshness. `sample` means the bundled sample is in use; `stale`
 * means the last refresh failed and the cached copy is being screened.
 */
fun catalogCoverage(catalogs: List<RiskCatalog>): String {
    if (catalogs.isEmpty()) return "none"
    if (catalogs.any { it.origin == RiskCatalogOrigin.BUILTIN }) return "sample"
    if (catalogs.any { it.origin == RiskCatalogOrigin.CACHE || it.error != null }) return "cache"
    return "network"
}

/** The catalogue sorts its entries into resistance, virulence and stress genes. */
private fun catalogCategory(kind: String?): RiskCategory =
    when (kind?.trim()?.uppercase()) {
        "VIRULENCE" -> RiskCategory.VIRULENCE
        "STRESS" -> RiskCategory.STRESS
        else -> RiskCategory.AMR
    }

fun hashRecords(records: List<RiskRecord>): String {
    val canonical = records
        .map { record ->
            listOf(record.id, record.category.code) + record.patterns
        }
        .map { it.joinToString("\u001f") }
        .sorted()
        .joinToString("\u001e")
    return fnv1a(canonical)
}

private fun normalisePatterns(raw: String): NormalisedPatterns {
    val values = mutableListOf<String>()
    var dropped = 0
    val pattern = raw.replace(Regex("\\s+"), "").uppercase()
    if (pattern.length < MIN_PATTERN_LENGTH || !pattern.matches(Regex("^[ACGT]+$"))) {
        dropped += 1
    } else if (!values.contains(pattern)) {
        values.add(pattern)
    }
    return NormalisedPatterns(values, dropped)
}

private fun slug(text: String): String {
    val cleaned = text.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("^-+|-+$"), "")
        .take(80)
    return cleaned.ifEmpty { "record" }
}

private fun fnv1a(text: String): String {
    var hash = 0x811c9dc5.toInt()
    for (ch in text) {
        hash = hash xor ch.code
        hash = ((hash.toLong() * 0x01000193L) and 0xFFFFFFFFL).toInt()
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}

// ---- main: biosec/screen.ts ----

private const val K = 12

private data class PatternEntry(
    val record: RiskRecord,
    val pattern: String,
    val needle: String,
    val strand: String,
    /** Offset of the indexed k-mer inside `needle`. */
    val offset: Int
)

private data class CatalogIndex(
    val kmers: MutableMap<String, MutableList<PatternEntry>> = mutableMapOf(),
    val short: MutableList<PatternEntry> = mutableListOf(),
    var maxLength: Int = 0
)

private val indexCache = mutableMapOf<RiskCatalog, CatalogIndex>()

private fun indexFor(catalog: RiskCatalog): CatalogIndex {
    indexCache[catalog]?.let { return it }

    val index = CatalogIndex()
    for (record in catalog.records) {
        for (pattern in record.patterns) {
            index.maxLength = max(index.maxLength, pattern.length)
            val reverse = Bio.revComp(pattern)
            if (pattern.length < K) {
                index.short.add(PatternEntry(record, pattern, pattern, "+", 0))
                index.short.add(PatternEntry(record, pattern, reverse, "-", 0))
                continue
            }
            val offset = ((pattern.length - K) / 2)
            val entries = listOf(
                PatternEntry(record, pattern, pattern, "+", offset),
                PatternEntry(record, pattern, reverse, "-", offset)
            )
            for (entry in entries) {
                val key = entry.needle.substring(offset, offset + K)
                val bucket = index.kmers[key]
                if (bucket != null) bucket.add(entry) else index.kmers[key] = mutableListOf(entry)
            }
        }
    }

    indexCache[catalog] = index
    return index
}

data class ScreenOptions(
    val circular: Boolean = false,
    /** Raise to ignore very short signatures; defaults to the catalog floor. */
    val minPatternLength: Int? = null,
    /** Per record, so one repetitive element cannot flood the panel. */
    val maxHitsPerRecord: Int = 25
)

fun screenSequence(
    sequence: String,
    catalogs: List<RiskCatalog>,
    options: ScreenOptions = ScreenOptions()
): List<DiagnosticFinding> {
    val subject = sequence.uppercase()
    if (subject.isEmpty()) return emptyList()
    val length = subject.length
    val circular = options.circular
    val minPatternLength = options.minPatternLength ?: 1
    val maxHitsPerRecord = options.maxHitsPerRecord

    val findings = mutableListOf<DiagnosticFinding>()
    val hitCounts = mutableMapOf<String, Int>()

    for (catalog in catalogs) {
        val index = indexFor(catalog)
        val extension = if (circular) max(0, index.maxLength - 1) else 0
        val hay = if (extension > 0) subject + subject.slice(0 until min(extension, length)) else subject
        val seen = mutableSetOf<String>()

        fun add(entry: PatternEntry, start0: Int) {
            if (entry.pattern.length < minPatternLength) return
            if (start0 < 0 || start0 >= length) return
            if (!circular && start0 + entry.pattern.length > length) return
            val count = hitCounts[entry.record.id] ?: 0
            if (count >= maxHitsPerRecord) return

            val mapped = mapHit(entry.strand, start0, entry.pattern.length, length, circular) ?: return
            val key = "${entry.record.id}:${entry.strand}:${mapped.start}:${entry.pattern.length}"
            if (seen.contains(key)) return
            seen.add(key)
            hitCounts[entry.record.id] = count + 1
            findings.add(buildFinding(catalog, entry, mapped))
        }

        var position = 0
        while (position + K <= hay.length) {
            val bucket = index.kmers[hay.substring(position, position + K)]
            if (bucket != null) {
                for (entry in bucket) {
                    val start0 = position - entry.offset
                    if (start0 < 0) continue
                    if (!hay.startsWith(entry.needle, start0)) continue
                    add(entry, start0)
                }
            }
            position += 1
        }

        for (entry in index.short) {
            var p = hay.indexOf(entry.needle)
            while (p != -1) {
                add(entry, p)
                p = hay.indexOf(entry.needle, p + 1)
            }
        }
    }

    findings.sortWith(compareBy({ it.scope.start ?: 0 }, { it.id }))
    return findings
}

private data class MappedHit(val start: Int, val end: Int, val wrapped: Boolean)

private fun mapHit(
    strand: String,
    start0: Int,
    patternLength: Int,
    length: Int,
    circular: Boolean
): MappedHit? {
    if (strand == "+") {
        if (!circular && start0 + patternLength > length) return null
        val end0 = start0 + patternLength
        return MappedHit(start0 + 1, end0, end0 > length)
    }
    var originalStart = length - (start0 + patternLength)
    if (originalStart < 0) {
        if (!circular) return null
        originalStart += length
    }
    val end0 = originalStart + patternLength
    return MappedHit(originalStart + 1, end0, end0 > length)
}

/** The catalogue names its own class; the panel words each one differently. */
private fun catalogVariant(category: RiskCategory): Pair<String, String> = when (category) {
    RiskCategory.VIRULENCE -> "risk_virulence_match" to "virulence factor"
    RiskCategory.STRESS -> "risk_stress_match" to "stress response"
    else -> "risk_amr_match" to "AMR"
}

private fun buildFinding(
    catalog: RiskCatalog,
    entry: PatternEntry,
    mapped: MappedHit
): DiagnosticFinding {
    val (record, pattern, _, strand) = entry
    val short = pattern.length < SHORT_SIGNATURE_LENGTH
    val variant = catalogVariant(record.category)

    return DiagnosticFinding(
        id = "risk:${catalog.source.code}:${record.id}:${strand}:${mapped.start}-${mapped.end}:$pattern",
        provider = FindingProvider.BIOSEC,
        severity = FindingSeverity.WARNING,
        code = variant.first,
        title = "${record.name} (${variant.second})",
        detail = "Matched ${pattern.length} nt on the $strand strand at ${mapped.start}..${mapped.end}" +
            "${if (mapped.wrapped) " across the origin" else ""}; catalogue ${catalog.source.code} ${catalog.version} (${catalog.origin.code}).",
        scope = FindingScope(kind = "sequence", start = mapped.start, end = mapped.end, strand = strand),
        params = mapOf(
            "name" to record.name,
            "description" to record.description,
            "category" to record.category.code,
            "class" to (record.klass ?: ""),
            "subclass" to (record.subclass ?: ""),
            "source" to catalog.source.code,
            "strand" to strand,
            "start" to mapped.start,
            "end" to mapped.end,
            "length" to pattern.length,
            "pattern" to pattern
        ),
        evidence = mapOf(
            "source" to catalog.source.code,
            "recordId" to record.id,
            "category" to record.category.code,
            "licence" to catalog.licence,
            "catalogVersion" to catalog.version,
            "catalogHash" to catalog.contentHash,
            "origin" to catalog.origin.code,
            "pattern" to pattern,
            "quality" to if (short) "short-signature" else "signature",
            "wrapped" to mapped.wrapped
        ),
        requiresReview = false
    )
}

/** Identity used to compare two screens: a catalog record, not a span. */
fun riskKey(finding: DiagnosticFinding): String {
    val source = finding.evidence["source"] ?: ""
    val recordId = finding.evidence["recordId"] ?: ""
    return "$source:$recordId"
}

/**
 * Findings introduced by a plan. A record that already matched before the plan
 * is not new: otherwise every later edit of a document that legitimately holds
 * an AMR marker would be blocked.
 */
fun newRiskFindings(before: List<DiagnosticFinding>, after: List<DiagnosticFinding>): List<DiagnosticFinding> {
    val known = before.map { riskKey(it) }.toSet()
    return after.filter { riskKey(it) !in known }
}

fun hasBlockingRisk(findings: List<DiagnosticFinding>): Boolean =
    findings.any { it.provider == FindingProvider.BIOSEC && it.severity == FindingSeverity.BLOCKING }

// ---- main: biosec/biorisk.ts ----

private fun asCategory(value: String): RiskCategory = when (value) {
    "biorisk", "virulence", "stress", "amr" -> RiskCategory.fromCode(value)
    else -> RiskCategory.UNKNOWN
}

/**
 * Turns Commec profile-HMM hits into the same finding shape as structural
 * diagnostics and exact-catalogue matches. The matching itself runs on the
 * desktop/Rust side; this module only labels and scopes the result.
 */
fun bioriskHitsToFindings(hits: List<BioriskHit>, status: CommecStatus?): List<DiagnosticFinding> {
    if (status == null) return emptyList()
    return hits.map { hit ->
        val blocking = hit.mustFlag
        val category = asCategory(hit.category)
        val start = hit.start
        val end = hit.end
        val frame = "frame ${hit.frame}"
        val eValue = if (hit.evalue.isFinite()) String.format(java.util.Locale.ROOT, "%.2e", hit.evalue) else "n/a"
        DiagnosticFinding(
            id = "risk:commec:${hit.model}:${hit.frame}:${hit.strand}:$start-$end",
            provider = FindingProvider.BIOSEC,
            severity = if (blocking) FindingSeverity.BLOCKING else FindingSeverity.WARNING,
            code = if (blocking) "risk_match" else "risk_virulence_match",
            title = "${hit.description.ifBlank { hit.model }} (${category.code})",
            detail = "Profile match at $start..$end on the ${hit.strand} strand ($frame, E=$eValue, " +
                "coverage ${(hit.coverage * 100).roundToInt()}%); Commec ${status.version.ifBlank { "unknown" }}.",
            scope = FindingScope(
                kind = "sequence",
                start = start,
                end = end,
                strand = if (hit.strand == "-") "-" else "+"
            ),
            params = mapOf(
                "name" to (hit.description.ifBlank { hit.model }),
                "model" to hit.model,
                "description" to hit.description,
                "category" to category.code,
                "source" to "commec",
                "strand" to hit.strand,
                "start" to start,
                "end" to end,
                "length" to end - start + 1,
                "coverage" to hit.coverage,
                "evalue" to hit.evalue
            ),
            evidence = mapOf(
                "source" to "commec",
                "recordId" to hit.model,
                "category" to category.code,
                "licence" to status.licence,
                "catalogVersion" to status.version,
                "catalogHash" to status.sha256,
                "origin" to "installed",
                "quality" to "profile-hmm",
                "wrapped" to false
            ),
            requiresReview = blocking
        )
    }
}
