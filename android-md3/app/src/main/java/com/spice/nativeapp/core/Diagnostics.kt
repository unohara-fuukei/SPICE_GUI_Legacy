package com.spice.nativeapp.core

/**
 * Shared shape for every finding the app can show against a document.
 * Ported 1:1 from main `src/lib/diagnostics/types.ts`.
 *
 * Two providers feed it: the gene diagnostics engine (structure and scientific
 * consistency) and the biosecurity screen (matches against locally synced risk
 * catalogs). One shape means one panel, one severity vocabulary and one place
 * where an agent or a human is told what to do.
 */

/** `structural` / `science` come from the diagnostics engine; `biosec` from the risk catalog screen. */
enum class FindingProvider(val code: String) {
    STRUCTURAL("structural"),
    SCIENCE("science"),
    BIOSEC("biosec");

    companion object {
        fun fromCode(code: String): FindingProvider =
            entries.firstOrNull { it.code == code } ?: STRUCTURAL
    }
}

/** `blocking` means the document or plan is unsafe to accept as-is. */
enum class FindingSeverity(val code: String) {
    BLOCKING("blocking"),
    WARNING("warning"),
    INFO("info");

    companion object {
        fun fromCode(code: String): FindingSeverity =
            entries.firstOrNull { it.code == code } ?: INFO
    }
}

/** A 1-based inclusive sequence span, or a feature reference. */
data class FindingScope(
    val kind: String,               // "sequence" | "feature"
    val id: Int? = null,            // feature id for `kind: "feature"`
    val start: Int? = null,         // 1-based inclusive
    val end: Int? = null,
    val strand: String? = null      // "+" | "-"
)

/**
 * Stable action name; the panel supplies the localised label.
 * `intents` mirrors main `GeneIntent[]` — left as an open list until
 * `workspaceIntents` is ported (#3 follow-up).
 */
data class FindingFix(
    val label: String,
    val intents: List<Any> = emptyList()
)

data class DiagnosticFinding(
    /** Stable for one snapshot and one catalog version. */
    val id: String,
    val provider: FindingProvider,
    val severity: FindingSeverity,
    /** Machine-readable code; the panel maps it to a localised sentence. */
    val code: String,
    /** English fallback used by exports and logs. */
    val title: String,
    val detail: String,
    val scope: FindingScope,
    /** Values interpolated into the localised sentence. */
    val params: Map<String, Any?> = emptyMap(),
    val evidence: Map<String, Any?> = emptyMap(),
    val fixes: List<FindingFix> = emptyList(),
    /** A human must clear this before the finding stops blocking. */
    val requiresReview: Boolean = false
)
