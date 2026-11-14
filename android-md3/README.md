# SPICE Native Android App (Kotlin + Jetpack Compose + Material 3)

Fully native rendering, zero WebView. This is the Kotlin implementation on the `native` branch; an earlier Rust + egui exploration is preserved in [`../NATIVE_PORT.md`](../NATIVE_PORT.md).

> **Not the original.** This app is an unofficial port of the original SPICE GUI desktop application ([SPICE-Protein/SPICE_GUI](https://github.com/SPICE-Protein/SPICE_GUI), by [redelectricity](https://www.biostars.org/u/158838/)). For real work, use the original. See the repository root [README](../README.md) for the full disclaimer, credits, and an honest list of current differences.

## Build

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>
./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

- `applicationId com.spice.nativeapp`, `minSdk 24`, `targetSdk 29`
- Release signing injected via `SPICE_KS / SPICE_KSPASS / SPICE_ALIAS`
- Since v0.5.0 the ONNX Runtime for Android native libs are bundled (arm64-v8a / armeabi-v7a); model weights are downloaded from HuggingFace on first use, not packaged in the APK
- Under a TLS-intercepting proxy, the Gradle JVM needs a custom truststore
- AGP 9 requires `android.builtInKotlin=false`

## Feature parity matrix (vs. the WebView original)

| Original (WebView) | Native implementation | Status |
|---|---|---|
| Bottom nav / top bar | MD3 Scaffold + NavigationBar, 6 tabs | Matched |
| Circular plasmid map | Canvas backbone + feature arcs + adaptive ticks + cut marks + drag rotate + tap select | Near 1:1 (no wheel zoom) |
| Linear map | Stacked feature lanes + ruler + cut-site ticks | Matched |
| Sequence view | 60 bp/row, base coloring, cut-site marks | Matched |
| Restriction digest | 532 groups / 3732 synonyms + IUPAC + circular origin crossing | Matched |
| Double-digest recommendations | Buffer-compatible pairs | Matched |
| PCR primers | Nearest-neighbor Tm + GC clamp + dimer screen | Matched |
| Gibson / Golden Gate | Homology arms + BsaI site screening | Matched |
| Protein properties | MW / pI / charge@pH7 / GRAVY / A280 | Matched |
| Protein 3D | Heuristic helix+β backbone, rotatable | **Not ONNX prediction** (weights-gated inference added in 1.7.10) |
| Gel simulation | log10 migration + 3 ladders + lanes/fragments | Matched |
| Gel photo recognition | Grayscale → lane split → row projection peaks → log10 bp calibration | Implemented (pure Kotlin, classical CV) |
| qPCR | ΔΔCt + efficiency self-check | Calculator only (no Ct import) |
| Western blot | Lane intensity + reference normalization | Schematic |
| 96-well plate | 8×12 layout + 4 count classes | Matched |
| Phylogenetic tree | FASTA + 4-mer Jaccard + UPGMA | Simplified (no MSA) |
| Colony counting | Manual tap + photo auto-count (connected components) | Implemented |
| Figure studio | Box/label/arrow/bitmap + captions + 1600×900 PNG + native OOXML PPTX (editable shapes, embedded media parts) | Implemented |
| Image annotation | 1/2/4/6 panels + scale bar + captions | Layout version |
| File IO | SAF open FASTA/GenBank + clipboard + share | Matched |
| PDF report | PdfDocument single-page A4 + MediaStore share | Implemented |
| AI assistant | SPICE AI Co-Pilot (OpenAI-compatible endpoint + workspace context + mutation/DNA block import) | Implemented (bring your own key; non-streaming) |
| Project hub | JSON persistence + notes + action history | Implemented (no attachments / multi-doc / presenter / qPCR XLSX / multi-membrane blots) |

## Known gaps

1. **ONNX weight inference**: the original downloads GelGenie U-Net weights at runtime; the native app substitutes classical CV for gel photo segmentation. Pixel-identical results would require onnxruntime-android + weights (APK +~100 MB). Protein fold inference (added 1.7.10) is real ONNX when a model file is present, heuristic otherwise.
2. **DNA viewer part tracks**: the original's sequence-row feature/part tracks were never verified against source; not implemented (recorded honestly in 1.7.9).
3. **AI**: non-streaming; no agent tool-calls / MCP (`tools.ts`, 800+ lines, unported); chat history now persists globally (fixed in 1.7.11) but has no cross-session storage.
4. **i18n**: seven-language switch exists; core strings translated, remainder falls back to Chinese.
5. **SPD publishing**: implemented (v1.7.16) — settings-page proxy/token, gene & vector library sheets, protein-page fold publish chain (protein→sequence→environment→model→fold) with SHA-256 dedup caching, and exact-match fold query via the `/query` graph DSL. Divergence from the original: the original's desktop-only gate (`isTauri`) is intentionally lifted on Android; when no ONNX model file is present the fold's `checkpointSha256` is submitted as `sha256:` + 64 zeros (placeholder, disclosed here) instead of a real file digest.

## Changelog

### 1.7.16 — SPD integration (versionCode 50)

1. **SPD settings** (1.7.16, first commit): base URL / API token / HTTP proxy fields in Settings → Public Integrations; every SpdLibrary request goes through the configured proxy explicitly (`URL.openConnection(proxy)`, anonymous direct connection when cleared); proxy userinfo becomes a Proxy-Authorization Basic header; `health()` / `me()` service checks reuse SpdLibrary.
2. **Protein-page SPD publish panel** (1.7.16): name field (accepts a pasted `spd:protein:` id, verbatim `spdProteinNameLabel`/`spdProteinNameHint`), exact-match fold query over local sequence-id cache roots via the `/query` graph DSL (5 roots with token / 4 anonymous, folds limit 3, environment expand only with token, environment exact-match picks the matched fold else the first), and the full publish chain protein → sequence (SHA-256 server dedup) → environment upsert → model upsert (streaming file SHA-256, 1 MiB buffer, `sha256:` + 64 zeros placeholder when no model file — the original's desktop gate is lifted on Android) → fold (lightweight prediction summary only; the 2 MiB request cap keeps coordinates/distogram/contact map local). Receipt caching keys match the original's localStorage (`spice_spd_protein_<seqKey>` / `spice_spd_sequence_<seqKey>` / `spice_spd_protein_name`, seqKey = first 20 hex of the sequence SHA-256); `deduplicated` receipts report `spdPublishedDedup`. All strings verbatim from the original's zh.json. Fold detail opens the record inspector dialog (`spdInspectorTitle`).
3. **Vector arcs on the circular map** (1.7.16): feature bands' tail arcs were originally ported as fixed 24-segment polylines, so zooming in revealed chords ("circles look like polygons"); the original uses SVG `A` (true vector arcs) — `ovePiePiecePath` now emits `Path.arcTo` true arcs for both inner and outer tail arcs, matching the original's `largeArc` semantics for sweeps > 180°.
4. Tests 196/196 (6 new SpdLibrary cases + 4 new OveArcTest cases: tail-arc radius deviation < 0.5 px sampled via `android.graphics.PathMeasure` over forward/reverse/small/359° sweeps — the old 24-segment polyline fails at ~1.2 px on a 300° arc).

### 1.7.16b — generalization: any sequence behaves like the demo (not just pj5_00001)

1. **Feature lane stacking algorithms re-ported verbatim**: the circular map now uses the original's `calculateYOffsets` (longest-arc-first, cross-origin features split into `[start,seqLen-1]+[0,end]` segments with closed-interval overlap detection, lowest free lane) instead of a start-sorted greedy — cross-origin features previously landed on wrong lanes or merged visually; the linear map now uses `calculateRowYOffsets` (longest-first, closed-interval overlap) and label boxes use `calculateLabelBoxes` (x0-sorted, closed-interval, lowest lane) exactly as in `LinearMapCanvas.svelte`.
2. **Cut/linearize bubble**: now matches the original's `linearizePlasmid` exactly — only rotates the sequence (`rotateBpsToPosition(pos-1)`), resets selection to 1..1, and shows the success toast; the port previously also flipped `circular=false` and switched views, which the original does not do. Dialog strings now verbatim from zh.json (`restrictionSite` / `cutPlasmidHere` / `pmLinearizeSuccess`).
3. Verified: RevComp/Delete on selections mutate only the sequence string (annotations untouched), same as the original's DnaViewer handlers; linear map zoom bounds (maxZoom = seqLen/5, pan clamp) and circular zoom (max(5, seqLen/100)) already matched the original.
4. Tests 202/202 (6 new `StackLanesTest` cases: longest-first ordering, closed-interval touching, cross-origin wrap blocking both segments, cross-origin b>1 rendering fraction, linear half-open interval equivalence).

### 1.7.15 — five user-reported fixes (versionCode 49)

1. **Tool gestures** (verbatim semantics from the original's `handlePointerDown/Move/Up`): circular select fixed by removing the 0.55r–1.15r distance clamp (the original's `getMouseBpOnCircle` has no distance limit); linear pan fixed by restructuring an unreachable `awaitEachGesture` that followed a suspending `detectTapGestures`; both maps: select sets selection on press, pan drags (`panBp = startPan + (−dx/(linW·zoom))·seqLen`), rotate at the original's 1.125°/px; **two fingers = pinch zoom only** (a second finger cancels all single-finger drags).
2. **Exact feature colors**: the original's `drawDirectedPiePiece` 9th argument `0.7` is the tail-thickness ratio, not alpha — bands are now fully opaque; the floating "Plasmid Features" card rows carry the feature color (colored bold name + colored type badge, per the original's tooltip).
3. **Directed arrows on the feature tail** (user's self-correction): geometry rewritten verbatim from TeselaGen OVE — thin tail arc (0.7) + full-height arrowhead wedge; fwd apex at the start angle pointing counterclockwise, rev apex at the end angle pointing clockwise, both at the trailing edge of the band; arrowhead length fixed at 80/(2π); active white stroke 0.5px; primer bands same geometry (height 9 / 0.6 / cyan α0.45 fill + #06b6d4 stroke).
4. **Cut-site annotations**: maps now digest `COMMON_ENZYMES` (47, verbatim) ∪ selected enzymes (AatII included); ticks + enzyme labels render in the original's fallback red `#ef4444`; default selection is the original's 8 initial enzymes.
5. Linear arrows at globalAlpha 0.85. Tests 166/166 (new `EnzymeSetsTest`).

### 1.7.14 — feature color table + directed arrows

`featureColor` switched to the original's `colorForType` verbatim (15 types); circular map gained directed arrows (first pass, direction later found wrong); `parseHexColor` in pure Kotlin; `FeatureColorTest` (163/163).

### 1.7.13 — six user-reported fixes

Seven-language setting; selection scroll-jump into sequence view (map→sequence linkage); settings entry relocated to the only original entry point; AI dialog keyboard occlusion (`imePadding`); ELN: experiment types, literature tags, DNA/protein block insertion, and 21 CFR Part 11-style digital signing (ORCID + SHA-256 canonicalized snapshot, lock on sign); 160/160 tests.

### 1.7.12 — five components ported 1:1

Map toolbar (§3.1), selection toolkit + selection property card + MW tooltip (§3.4), sequence tool rail + minimap (§4.1), feature table + editor modal + auto-annotate (§5/§5.1/§5.2, `COMMON_FEATURES_DB` 14 entries verbatim, exact/kmer matchers); 155/155 tests.

### 1.7.11 — AI session persistence + portrait split + 10-base blocks

AI chat state hoisted to a global store; portrait phones stack map/sequence vertically; 10-base block spacing; floating cards z-order fix; gesture/scroll conflict fix (single finger passes through to page scroll outside tool modes); 147/147 tests.

### 1.7.10 — docs-driven audit

Tab labels from the original's zh.json; sequence-view crash fix (TextMeasurer per-frame overloading → row-level cache); AI system prompt setting; entry-point dedup; linear map cut-site bubble; map↔sequence split layout; ONNX protein inference integrated (`SpiceOnnx.kt`, full-head distogram + contact map, heuristic fallback); 145/145 tests.

### 1.7.9 — gene page six-view layout + five-layer map

Six left-pane tabs (features/properties/notes added); GenBank full-field properties page; ELN notebook pages + selection notes; sequence view 7 color themes + translation row; circular map 9 layers (base blocks, backbone, selection glow, ticks, stacked feature arcs + along-arc labels, primer lanes, cut marks, guide rings, center text); linear map 7 layers; zoom geometry from the original's OVE formulas; view config persistence.

### 1.7.8 — pj5_00001 demo + top bar + cut-site label relaxation

Default record switched to pj5_00001 (5299 bp, 22 features); top bar aligned (AI + settings only); circular cut-site enzyme labels ported 1:1 (relaxed outer-orbit labels with 3-segment leader lines, quadrant slotting, "+N" merging); linear cut-site labels with collision lanes.

### 1.7.7 — contrast audit

WCAG pass over all hardcoded colors + Canvas text; scientific canvases pinned to a fixed dark background (palettes are designed for dark); MD energy chart made theme-aware.

### 1.7.6 — theme unification + AMOLED black + welcome branding

All pages paint `colorScheme.background`; AMOLED pure-black scheme toggle; welcome page rebranded.

### 1.7.5 — global back navigation + AI drawer + floating windows

Back gesture hierarchy (close drawer/panel → exit demo → gene tab → exit); AI Co-Pilot moved to a full-height right drawer; `FloatingPanel` draggable window system replacing bottom sheets (26+ panels aligned).

### 1.7.4 — OVE-style cut-site rendering + Sanger colors

Sequence-view cut sites: hash-colored enzyme labels + sticky-end tick pairs + connector lines (byte-aligned with the original's `getEnzymeColor`); Sanger channel G fixed to #F39C12.

### 1.7.3 — app branding ("生信工具箱") + user-supplied icon.

### 1.7.1/1.7.2 — built-in AI defaults + ONNX settings panel (model status, local path, cloud download).

### 1.7.0 — SPICE AI Co-Pilot

OpenAI-compatible transport with error classification and 429 Retry-After; **endpoint-normalization fix** (the original POSTs the raw user endpoint, so base URLs 404 — the native `normalizeChatUrl` appends `/chat/completions`); ```json mutation and ```dna block parsing verbatim; mutation import into the protein cart; 138/138 tests.

### 1.6.6 — Material 3 theme completion (all color roles, light/dark/AMOLED, dynamicColor, safe window handling).

### 1.6.5 — minSdk kept at 24 (user requirement), targetSdk 29.

### 1.6.3 — protein menu 1:1 cleanup (self-added entries removed, functions kept in-page).

### 1.6.2 — export modal + `.spiceg` project file (schema 1:1, round-trip tests).

### 1.6.1 — alignment file import (Clustal/FASTA/NEXUS/PHYLIP/MSF/Stockholm parsers, 1:1).

### 1.6.0 — menu logic alignment (gene/protein menu trees, order, separators, conditions, dynamic labels; codon co-design module ported from the Rust `pareto_codesign_evaluate`).

### 1.5.0 — reaction calculators + batch screening engine.

### 1.4.0 — gene page analysis & multi-view alignment.

### 1.3.0 — protein workstation alignment.

### 1.2.0 — algorithm-level completion against `lib/genome` (23k lines): SantaLucia 1998 NN Tm, Sanger validation via k-mer anchor + semi-global NW, 8 cloning methods, CRISPR off-target scan, FASTQ/EMBL IO; 16 core algorithm tests; two real bugs fixed (dimer predicate inverted — always false; hairpin double-complement loop never ran).

### 1.0 – 1.1 — baseline: six-tab shell, sequence engine, digest engine, circular/linear maps, sequence view, gel simulation, protein properties.
