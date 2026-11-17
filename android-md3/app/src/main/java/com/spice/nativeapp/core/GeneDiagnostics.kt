package com.spice.nativeapp.core

/**
 * Document diagnostics for the Gene Editor.
 *
 * Pure and deterministic: the same snapshot produces the same findings, in the
 * same order. Structural problems block; scientific observations warn or
 * inform, because the editor cannot decide biology for the user. Fixes are
 * expressed as the same typed intents the agent uses, so applying one still
 * passes the version and post-verify gates.
 *
 * Ported 1:1 from main `src/lib/gene/diagnostics.ts`. The full plan-application
 * engine (`workspaceIntents.ts`) is deferred; here we own only the snapshot
 * shape, the version hash and the read-only diagnostic rules. `intents` stay an
 * open list (`List<Any>`) until the typed workspace intents land.
 */

/** One restriction-enzyme recognition site reported inside a CDS. */
data class EnzymeSite(val name: String, val motif: String)

/** Diagnostics tuning options (defaults mirror main). */
data class DiagnoseOptions(
    val enzymeSites: List<EnzymeSite> = emptyList(),
    val maxFeaturesForPairChecks: Int? = null
)

/** Mirrors main `GeneWorkspaceFeature` (1-based inclusive coordinates). */
data class WorkspaceFeature(
    val id: Int,
    val name: String,
    /** 1-based inclusive. */
    val start: Int,
    /** 1-based inclusive. */
    val end: Int,
    val type: String,
    val color: String? = null,
    val forward: Boolean = true
)

/** Mirrors main `GeneWorkspaceSnapshot`. */
data class WorkspaceSnapshot(
    val name: String,
    val sequence: String,
    /** `"circular"` | `"linear"`; anything else is a blocking topology finding. */
    val topology: String,
    val features: List<WorkspaceFeature>
)

private val DNA_ALPHABET = Regex("^[ACGTNRYWSKMBDHV]*$")
private val START_CODONS = setOf("ATG", "GTG", "TTG")
private const val HOMOPOLYMER_MIN = 8
private val STRUCTURAL_CODES = setOf(
    "seq_empty",
    "seq_non_iupac",
    "topology_invalid",
    "feature_duplicate_id",
    "feature_empty_name",
    "feature_range"
)

/**
 * Stable FNV-1a hash of everything that changes what the workspace means.
 * Mirrors main `workspaceVersion` (`workspaceIntents.ts`).
 */
fun workspaceVersion(snapshot: WorkspaceSnapshot): String {
    val parts = mutableListOf(
        snapshot.name,
        snapshot.sequence.uppercase(),
        snapshot.topology
    )
    for (f in snapshot.features) {
        val featureType = f.type.ifEmpty { "misc_feature" }.lowercase()
        parts.add(
            listOf(
                f.id, f.name, f.start, f.end, featureType, if (f.forward) "1" else "0"
            ).joinToString("\u001f")
        )
    }
    return fnv1a(parts.joinToString("\u001e"))
}

private fun fnv1a(text: String): String {
    var hash = 0x811c9dc5.toInt()
    for (ch in text) {
        hash = hash xor ch.code
        hash = hash * 0x01000193  // Int overflow wraps to the low 32 bits, like Math.imul
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}

fun diagnoseGeneWorkspace(
    snapshot: WorkspaceSnapshot,
    options: DiagnoseOptions = DiagnoseOptions()
): List<DiagnosticFinding> {
    val version = workspaceVersion(snapshot)
    val findings = mutableListOf<DiagnosticFinding>()
    val sequence = snapshot.sequence.uppercase()
    val length = sequence.length

    fun add(
        code: String,
        severity: FindingSeverity,
        title: String,
        detail: String,
        scope: FindingScope,
        params: Map<String, Any?> = emptyMap(),
        fixes: List<FindingFix> = emptyList()
    ) {
        findings.add(
            DiagnosticFinding(
                id = "diag:$code:${scope.kind}:${scope.id ?: ""}:${scope.start ?: ""}-${scope.end ?: ""}:$version",
                provider = if (STRUCTURAL_CODES.contains(code)) FindingProvider.STRUCTURAL else FindingProvider.SCIENCE,
                severity = severity,
                code = code,
                title = title,
                detail = detail,
                scope = scope,
                params = params,
                fixes = fixes
            )
        )
    }

    // ---------------- structural ----------------
    if (length == 0) {
        add(
            "seq_empty", FindingSeverity.BLOCKING, "The sequence is empty",
            "No bases are present in the document.",
            FindingScope(kind = "sequence", start = 1, end = 1)
        )
    } else if (!DNA_ALPHABET.matches(sequence)) {
        val invalid = sequence.firstOrNull { it !in "ACGTNRYWSKMBDHV" }
        val position = invalid?.let { sequence.indexOf(it) + 1 } ?: 1
        add(
            "seq_non_iupac", FindingSeverity.BLOCKING, "Non-IUPAC base in the sequence",
            "\"$invalid\" at position $position is not an IUPAC DNA code.",
            FindingScope(kind = "sequence", start = position, end = position),
            params = mapOf("base" to (invalid ?: '?'), "position" to position)
        )
    }

    if (snapshot.topology != "circular" && snapshot.topology != "linear") {
        add(
            "topology_invalid", FindingSeverity.BLOCKING, "Unknown topology",
            "Topology \"${snapshot.topology}\" is neither circular nor linear.",
            FindingScope(kind = "sequence", start = 1, end = maxOf(1, length)),
            params = mapOf("topology" to snapshot.topology)
        )
    }

    val seenIds = mutableSetOf<Int>()

    for (feature in snapshot.features) {
        val featureScope = FindingScope(
            kind = "feature", id = feature.id, start = feature.start, end = feature.end
        )
        if (seenIds.contains(feature.id)) {
            add(
                "feature_duplicate_id", FindingSeverity.BLOCKING, "Duplicate feature id",
                "Feature id ${feature.id} is used more than once.",
                featureScope,
                params = mapOf("id" to feature.id)
            )
        }
        seenIds.add(feature.id)

        if (feature.name.trim().isEmpty()) {
            add(
                "feature_empty_name", FindingSeverity.BLOCKING, "Feature without a name",
                "Feature ${feature.id} has an empty name.",
                featureScope,
                params = mapOf("id" to feature.id)
            )
        }

        val range = FeatureRange(feature.start, feature.end)
        if (!isRangeInBounds(range, length) || (isWrappedRange(range) && snapshot.topology != "circular")) {
            val detail = if (isRangeInBounds(range, length)) {
                "Feature ${feature.id} runs from ${feature.start} back to ${feature.end}, which a linear document cannot hold."
            } else {
                "Feature ${feature.id} spans ${feature.start}..${feature.end}, which is not a valid range for $length bp."
            }
            add(
                "feature_range", FindingSeverity.BLOCKING, "Feature outside the sequence",
                detail, featureScope,
                params = mapOf("id" to feature.id, "start" to feature.start, "end" to feature.end, "length" to length)
            )
            continue
        }
    }

    // Duplicate names are ambiguous for both the user and the agent.
    val duplicateNames = mutableListOf<Pair<Int, String>>()
    val seenNames = mutableMapOf<String, Int>()
    for (feature in snapshot.features) {
        val key = feature.name.trim().lowercase()
        if (key.isEmpty()) continue
        val count = seenNames[key] ?: 0
        seenNames[key] = count + 1
        if (count > 0) duplicateNames.add(feature.id to feature.name.trim())
    }
    if (duplicateNames.isNotEmpty()) {
        val firstId = duplicateNames.first().first
        val firstFeature = snapshot.features.firstOrNull { it.id == firstId }
        val fixes = listOf(
            FindingFix(label = "rename-duplicates", intents = duplicateNameFixes(snapshot))
        )
        add(
            "feature_duplicate_name", FindingSeverity.WARNING, "Duplicate feature names",
            "${duplicateNames.size} feature(s) repeat a name already used in the document.",
            FindingScope(
                kind = "feature", id = firstId,
                start = firstFeature?.start, end = firstFeature?.end
            ),
            params = mapOf(
                "count" to duplicateNames.size, "name" to duplicateNames.first().second
            ),
            fixes = fixes
        )
    }

    // ---------------- CDS checks ----------------
    for (feature in snapshot.features) {
        if (feature.type.lowercase() != "cds") continue
        val range = FeatureRange(feature.start, feature.end)
        if (!isRangeInBounds(range, length)) continue
        if (isWrappedRange(range) && snapshot.topology != "circular") continue

        val oriented = orientedFeatureSequence(sequence, feature)
        val scope = FindingScope(kind = "feature", id = feature.id, start = feature.start, end = feature.end)

        if (oriented.length % 3 != 0) {
            add(
                "cds_frame", FindingSeverity.WARNING, "CDS length is not a multiple of three",
                "${feature.name} is ${oriented.length} nt; the last ${oriented.length % 3} base(s) fall outside a codon.",
                scope,
                params = mapOf(
                    "name" to feature.name, "length" to oriented.length,
                    "remainder" to oriented.length % 3
                )
            )
        }

        val codons = oriented.chunked(3)
        if (codons.isNotEmpty()) {
            if (!START_CODONS.contains(codons[0])) {
                add(
                    "cds_start", FindingSeverity.INFO,
                    "CDS does not begin with a common start codon",
                    "${feature.name} starts with ${codons[0]} rather than ATG, GTG or TTG.",
                    scope,
                    params = mapOf("name" to feature.name, "codon" to codons[0])
                )
            }
            val stopIndex = codons.indices.firstOrNull { idx ->
                idx < codons.size - 1 && Bio.translateCodon(codons[idx]) == '*'
            } ?: -1
            if (stopIndex != -1) {
                val stopOffset = orientedOffset(feature, oriented.length, stopIndex * 3, 3)
                val position = positionAt(range, stopOffset, length)
                add(
                    "cds_internal_stop", FindingSeverity.WARNING, "Internal stop codon in a CDS",
                    "${feature.name} has ${codons[stopIndex]} at base $position, before the annotated end.",
                    scope.copy(
                        start = position,
                        end = positionAt(range, stopOffset + 2, length)
                    ),
                    params = mapOf(
                        "name" to feature.name, "codon" to codons[stopIndex], "position" to position
                    )
                )
            }
        }

        val enzymeFindings = enzymeSitesInFeature(feature, oriented, options.enzymeSites)
        for (hit in enzymeFindings) {
            val start = positionAt(range, hit.offset, length)
            add(
                "cds_enzyme_site", FindingSeverity.WARNING, "Restriction site inside a CDS",
                "${hit.name} (${hit.motif}) occurs inside ${feature.name} at base $start.",
                scope.copy(
                    start = start,
                    end = positionAt(range, hit.offset + hit.motif.length - 1, length)
                ),
                params = mapOf(
                    "name" to feature.name, "enzyme" to hit.name,
                    "motif" to hit.motif, "position" to start
                )
            )
        }
    }

    // ---------------- sequence-wide checks ----------------
    if (length >= 100) {
        val gc = sequence.count { it == 'G' || it == 'C' }
        val percent = Math.round(gc * 100.0 / length)
        if (percent < 30 || percent > 70) {
            add(
                "sequence_gc_extreme", FindingSeverity.INFO, "Extreme GC content",
                "GC is $percent%; synthesis and amplification may need different conditions.",
                FindingScope(kind = "sequence", start = 1, end = length),
                params = mapOf("percent" to percent)
            )
        }
    }

    val run = longestHomopolymer(sequence)
    if (run.length >= HOMOPOLYMER_MIN) {
        add(
            "sequence_homopolymer", FindingSeverity.INFO, "Long homopolymer run",
            "${run.base} repeated ${run.length} times at ${run.start}..${run.end}.",
            FindingScope(kind = "sequence", start = run.start, end = run.end),
            params = mapOf("base" to run.base, "length" to run.length)
        )
    }

    // ---------------- overlaps ----------------
    val maxPairs = options.maxFeaturesForPairChecks ?: 400
    if (snapshot.features.size <= maxPairs) {
        outer@ for (i in snapshot.features.indices) {
            for (j in i + 1 until snapshot.features.size) {
                val a = snapshot.features[i]
                val b = snapshot.features[j]
                if (!isRangeInBounds(FeatureRange(a.start, a.end), length) ||
                    !isRangeInBounds(FeatureRange(b.start, b.end), length)
                ) continue
                val opposite = (a.forward == false) != (b.forward == false)
                if (!opposite) continue
                val overlap = firstOverlap(FeatureRange(a.start, a.end), FeatureRange(b.start, b.end), length)
                if (overlap == null) continue
                add(
                    "feature_overlap_opposite", FindingSeverity.INFO,
                    "Opposite-strand features overlap",
                    "\"${a.name}\" and \"${b.name}\" overlap at ${overlap.start}..${overlap.end} on opposite strands.",
                    FindingScope(kind = "feature", id = a.id, start = overlap.start, end = overlap.end),
                    params = mapOf(
                        "first" to a.name, "second" to b.name,
                        "start" to overlap.start, "end" to overlap.end
                    )
                )
                continue@outer
            }
        }
    }

    return findings.sortedWith(bySeverityThenPosition)
}

private fun orientedFeatureSequence(sequence: String, feature: WorkspaceFeature): String {
    val raw = extractRange(sequence, FeatureRange(feature.start, feature.end)).uppercase()
    return if (feature.forward == false) Bio.revComp(raw) else raw
}

/**
 * Offset in the CDS as written, from an offset in its oriented (5'→3') read.
 * A reverse feature reads back to front, so its last base comes first.
 */
private fun orientedOffset(
    feature: WorkspaceFeature,
    orientedLength: Int,
    offset: Int,
    span: Int
): Int = if (feature.forward == false) orientedLength - offset - span else offset

private data class EnzymeHit(val name: String, val motif: String, val offset: Int)

private fun enzymeSitesInFeature(
    feature: WorkspaceFeature,
    oriented: String,
    sites: List<EnzymeSite>
): List<EnzymeHit> {
    val hits = mutableListOf<EnzymeHit>()
    for (site in sites) {
        val motif = site.motif.uppercase()
        if (motif.isEmpty()) continue
        val regex = try {
            Regex(iupacToRegex(motif))
        } catch (_: Throwable) {
            continue
        }
        for (match in regex.findAll(oriented)) {
            // Offsets stay inside the feature; the caller maps them to the document.
            hits.add(
                EnzymeHit(
                    name = site.name, motif = motif,
                    offset = orientedOffset(feature, oriented.length, match.range.first, motif.length)
                )
            )
            if (hits.size >= 3) return hits
        }
    }
    return hits
}

private fun iupacToRegex(motif: String): String {
    val map = mapOf(
        'A' to "A", 'C' to "C", 'G' to "G", 'T' to "T",
        'R' to "[AG]", 'Y' to "[CT]", 'S' to "[GC]", 'W' to "[AT]",
        'K' to "[GT]", 'M' to "[AC]", 'B' to "[CGT]", 'D' to "[AGT]",
        'H' to "[ACT]", 'V' to "[ACG]", 'N' to "[ACGT]"
    )
    return motif.map { map[it] ?: it.toString() }.joinToString("")
}

private data class HomopolymerRun(val base: String, val length: Int, val start: Int, val end: Int)

private fun longestHomopolymer(sequence: String): HomopolymerRun {
    var best = HomopolymerRun("", 0, 0, 0)
    var currentBase = ""
    var currentLength = 0
    for (index in sequence.indices) {
        val base = sequence[index].toString()
        if (base == currentBase) {
            currentLength += 1
        } else {
            currentBase = base
            currentLength = 1
        }
        if (currentLength > best.length && base in "ACGT") {
            best = HomopolymerRun(
                base = base,
                length = currentLength,
                start = index - currentLength + 2,
                end = index + 1
            )
        }
    }
    return best
}

private fun duplicateNameFixes(snapshot: WorkspaceSnapshot): List<Map<String, Any>> {
    val used = snapshot.features.map { it.name.trim().lowercase() }.toMutableSet()
    val seen = mutableSetOf<String>()
    val intents = mutableListOf<Map<String, Any>>()
    for (feature in snapshot.features) {
        val key = feature.name.trim().lowercase()
        if (key.isEmpty()) continue
        if (!seen.contains(key)) {
            seen.add(key)
            continue
        }
        var suffix = 2
        var candidate = "${feature.name.trim()} ($suffix)"
        while (used.contains(candidate.lowercase())) {
            suffix += 1
            candidate = "${feature.name.trim()} ($suffix)"
        }
        used.add(candidate.lowercase())
        intents.add(
            mapOf("type" to "rename_feature", "featureId" to feature.id, "name" to candidate)
        )
    }
    return intents
}

private val bySeverityThenPosition: Comparator<DiagnosticFinding> = Comparator { a, b ->
    val order = mapOf(
        FindingSeverity.BLOCKING to 0, FindingSeverity.WARNING to 1, FindingSeverity.INFO to 2
    )
    val severityDiff = (order[a.severity] ?: 2) - (order[b.severity] ?: 2)
    if (severityDiff != 0) return@Comparator severityDiff
    (a.scope.start ?: 0) - (b.scope.start ?: 0)
}
