# SPICE GUI — Native Android Port (`native` branch)

> [!IMPORTANT]
> **This is not the original SPICE GUI.** This repository is a fork of [SPICE-Protein/SPICE_GUI](https://github.com/SPICE-Protein/SPICE_GUI). The `native` branch is an independent, unofficial, zero-WebView re-implementation of that desktop application as a native Android app (Kotlin + Jetpack Compose + Material 3). It deliberately tries to come as close as possible to the original's user experience, but it is **not maintained by, endorsed by, or affiliated with the original author**, it lags behind the original in features, stability, and polish, and it was **developed with AI assistance** — so inaccuracies relative to the original are expected. **If you have a serious, professional, or production use case, please use the original:** [SPICE-Protein/SPICE_GUI](https://github.com/SPICE-Protein/SPICE_GUI).

## Acknowledgements

This port simply would not exist without the original work of **[redelectricity](https://www.biostars.org/u/158838/)**, the author of SPICE GUI. Essentially the entire original application — the SvelteKit/Tauri desktop app on the `main` branch of this repository, the protein/gene/cloning workbenches, the pixel-art UI, and the embedded [TeselaGen Open Vector Editor](https://github.com/TeselaGen/openVectorEditor) derivatives — is his work, released under Apache-2.0. Every screen, canvas layer, algorithm, and UI string in this native port was written by reading his source code line by line, and it is only because he chose to open-source the project that any of this was possible. **Thank you.**

The original project also credits [TeselaGen Open Vector Editor](https://github.com/TeselaGen/tg-oss) (MIT) for the DNA editor; the gene/cloning workbench in this port re-implements that geometry (directed pie pieces, label relaxation, the five-layer circular map) from the same upstream sources.

## What this is

- **Target**: a 1:1 replication of the `main` (WebView) branch's behavior on Android, with **no WebView** — everything is Compose Canvas / native widgets.
- **Stack**: Kotlin, Jetpack Compose, Material 3, minSdk 24 (Android 7.0+).
- **Scope**: gene workbench (circular/linear plasmid maps, sequence view, features, ELN, cloning tools), protein workbench (fold/ONNX inference, MD, stability), bench tools (gel, qPCR, plates, trees, colony counting, figure studio), AI Co-Pilot (OpenAI-compatible), restriction-enzyme engineering (532 recognition groups / 3732 synonyms, IUPAC fuzzy matching, circular origin-crossing search).
- **Verification**: every fixed detail is cross-checked against the `main` branch source (and, for the OVE-derived map geometry, against TeselaGen's `tg-oss` upstream); 166 unit tests plus Robolectric software-rendered screenshot smoke tests.

## Honest differences from the original

Platform and architecture:

- Android only vs. the original's desktop (Windows/macOS/Linux) build; nothing is shared with the original code base — this is a clean-room port, not a wrapper.

Known gaps (each tracked in the changelog of [`android-md3/README.md`](android-md3/README.md)):

- **Protein 3D / folding**: ONNX Runtime inference is integrated (multi-head distogram, contact map) when model weights are present; without weights the 3D view falls back to a heuristic backbone — not a trained prediction.
- **MOL\* viewer**: the original embeds the Mol* WebGL library (molstar npm) in a Svelte-managed canvas; Android is WebView-free, so the MOL\* viewer panel re-implements the same feature surface (representation Auto/trace/spacefill/cartoon/ball-stick, view settings with background/camera/axes/exposure/light/size-factor/outline/shadow/bloom/AO, camera reset, paper PNG export with watermark and 1×/2×/4× supersampling, `spice_fold.png` filename) with a Compose Canvas software renderer. Cartoon coloring follows the Chou–Fasman secondary structure (helix/sheet/coil), outline/shadow/bloom/AO are simplified approximations of the Mol* post-processing stack, and molstar-specific hover picking and pocket overlays are not wired.
- **Gel photo recognition**: classical CV (grayscale → lane split → row projection → log10 calibration) instead of the original's GelGenie U-Net model.
- **AI Co-Pilot**: streaming SSE typewriter output and the agent tool-call chain are ported; MCP (stdio) transport is not feasible in the Android sandbox and is not ported. The offline computation toolbox (T8) follows `aiState.svelte.ts` 1:1 (mutational ΔΔG estimator, multi-host codon tuner, cloning SOP generator with SantaLucia Tm, SOP Markdown export); disclosed differences: the original's tool-tab toasts render as inline status rows on Android, the toolbox is the third tab of the AI sheet drawer (the original is a two-tab sidebar with a gear popover for settings — Android keeps its existing settings-tab form), `copilotSim*` / `copilotAiOffline*` (41 message keys) are dead keys in the original source (defined in messages/*.json, never referenced) and are therefore not ported, the codon tool's target-GC slider is display-only in the original and stays display-only here, SOP Markdown is copied through the system clipboard, and the multi-motif replacement quirk (whole-sequence `hasMotif` veto) is kept verbatim.
- **Project hub**: attachments, multi-document embedding, presenter mode, qPCR XLSX import, and multi-membrane blots are not implemented.
- **Bench tools**: qPCR is calculator-only (no Ct table import); Western blot is schematic; phylogenetic tree uses 4-mer Jaccard + UPGMA (no MSA input).
- **Keyboard shortcuts**: the settings keymap panel and the gene-page global shortcut bindings are ported as the 20-entry default mapping model (`spice_keymap` persisted) and a read-only settings list. Recording, conflict detection, per-key clear/reset, and the global hardware-key listener with its `shortcut*` toast chain are not wired — touch Android has no physical keyboard or global hardware-key dispatch channel; the strings are dictionary-reserved for a future external-keyboard path.
- **Codon optimizer**: the gene-page codon panel follows the original's CodonOptimizer component (per-host CAI, synonymous optimization via BEST_CODONS, trailing-stop-stripped translation); panel toasts render as inline status rows, and the settings-page salt/primer concentration inputs are persisted to the same keys but not yet wired into Android primer thermodynamics. The original's CodonFetcher header (Kazusa cloud table fetch) is a dead import in the original source and is therefore not ported.
- **Rust backend message layer (T7-16)**: the main desktop app routes commands such as `annotate_features_sw` / `run_local_alignment` / `solve_phase_bifurcation` through `src-tauri/src/cloning.rs`, with a TS fallback for browser mode. Android implements the Rust semantics 1:1 in Kotlin (`SwAlign.kt` = rust-bio 4.0.1 pairwise local aligner, `PhaseBifurcation.kt` = Landau quadratic fit) and discloses these platform differences:
  - `run_local_alignment` is a dead path on the original desktop build (`AlignmentMode::Local` panics in `cigar()`), so the original front-end silently falls back to a JS stub there; Android runs a real Smith-Waterman (17 parity cases vs the rust-bio output) and reports `alignmentStr` as "unavailable in local mode" instead of a CIGAR.
  - Auto-annotation ships with the full REBASE-style feature library, UniVec_Core sync (`https://ftp.ncbi.nlm.nih.gov/pub/UniVec/UniVec_Core`), and the k-mer / Smith-Waterman / exact algorithm switch; UniVec storage uses `SharedPreferences("spice")["spice_unevec_db"]` (same key as the original's localStorage) instead of a browser cache.
  - Phase Bifurcation Tracker card: the original's `PhaseBifurcationRequest` carries a `buildFailed` flag the Android phase scan does not record (no build step on-device), so Android always passes `buildFailed=false` (README disclosure); the boundary-curve data is computed and carried 1:1 but the card, like the original card, does not draw the curve itself.
- **NGS Genomics Panel (T7-adjacent)**: the cloning-page NGS panel (`fastq_qc` / `trimmer` / `orf_gene` / `crispr` tabs) follows `NgsGenomicsPanel.svelte` 1:1, with `analyzeFastqQualityRust` / `findOrfsRust` re-implemented from `cloning.rs` (rust-bio 4.0.1 `fastq::Reader` line-exact semantics, `orf::Finder` stack semantics with nested ORFs sharing a stop, bio's 256-entry IUPAC complement table with case preservation) and locked by 33 parity tests against a cargo-bin reference run of the real Rust functions. Disclosed quirks kept verbatim from the original: the panel's default demo FASTQ has read 1's quality string one char shorter than its sequence, so that record is skipped (totalReads=1); the ORF tab always shows `ATG`/`TAA` because the Rust result carries no start/stop-codon field; the trim tab's button label is the success message; the zh template `ngsReadsCount` has no `{v1}` placeholder so the count is not displayed; the "Rust 计算中..." loading states only exist in the original's async Tauri path (Android computes synchronously, so no loading state is rendered); on Android the guarded TS fallback is only reachable for empty inputs, where it produces an empty report — the browser-only `strand=undefined` quirk of the JS ORF fallback is unreachable.
- **i18n**: a seven-language setting exists, but only navigation/views/menus/settings core strings are translated so far; untranslated strings fall back to Chinese.
- **DNA viewer**: the original's feature/part tracks in sequence rows (`parts` rendering path) have not been verified against source and are not implemented.
- Plus the usual long tail: SPD publishing from the protein page is present but fold submissions without a local ONNX model carry a placeholder checkpoint hash; minor layout divergences on small screens; byte-level polish still in progress.

## Development history

The `native` branch began as a from-scratch Kotlin port of the `main` branch UI and evolved release by release toward parity. Milestones:

- **v1.0 – v1.2**: core engines — FASTA/GenBank parsing, REBASE-derived enzyme library, SantaLucia nearest-neighbor Tm, Needleman-Wunsch Sanger validation, 8 cloning methods, CRISPR off-target scan, FASTQ/EMBL IO.
- **v1.3 – v1.5**: protein workbench, analysis/multi-view alignment, reaction calculators, batch screening engine.
- **v1.6**: menu trees aligned 1:1 with `main` (entry by entry), `.spiceg` project file, export modal, six alignment-format import, Material 3 theme completion.
- **v1.7.0 – v1.7.2**: SPICE AI Co-Pilot port (including an endpoint-normalization bug the original still had), ONNX settings panel.
- **v1.7.3 – v1.7.9**: branding, OVE-style cut-site rendering, pj5_00001 demo sequence, five-layer circular map + zoom, six-view gene page, ELN notebook.
- **v1.7.10 – v1.7.12**: docs-driven audit fixes, map↔sequence linkage, ONNX protein inference, map toolbar, selection toolkit, sequence tool rail, full feature table / editor / auto-annotate.
- **v1.7.13 – v1.7.15**: user-feedback rounds — multilingual setting, selection scroll jump, ELN digital signatures (21 CFR Part 11 style), keyboard occlusion, tool gesture semantics, exact feature colors, OVE directed arrows, common-enzyme cut sites, pinch-zoom-only gestures.

(An earlier exploration of a Rust + egui port is preserved in [`NATIVE_PORT.md`](NATIVE_PORT.md); it was superseded by this Kotlin implementation.)

## Difficulties encountered (as an honest record)

> [!NOTE]
> **This port was developed with AI assistance.** The code in this branch was written collaboratively with a large language model (reading the original source and re-implementing it in Kotlin), not by hand-porting line by line from memory. Although every settled detail is cross-checked against the original's source code and locked with automated tests, AI-driven development can misread, misremember, or over-simplify what the original does. **Inaccuracies and deviations from the original almost certainly remain** — the difficulties below are real examples of mistakes that were caught, some of them only after users reported them. When a behavior here differs from the original, assume the original is correct; please open an issue or consult the original's source before trusting this port's behavior.

The difficulties below are recorded candidly, partly as examples of the kind of errors AI-assisted porting can produce:

- **Canvas geometry re-derivation**: the circular map's directed feature arrows (thin tail arc + full-height arrowhead wedge) had to be re-derived from TeselaGen's `drawDirectedPiePiece` / `PositionAnnotationOnCircle` and cross-validated numerically. An early misreading of a `0.7` argument (it is the tail-thickness ratio, not alpha) shipped a too-dark canvas and a wrong arrow direction — both fixed in v1.7.15 after user reports.
- **Gesture semantics**: web pointer events and Compose gesture detectors do not map 1:1. A Compose ordering bug (`detectTapGestures` suspends forever, making the following `awaitEachGesture` unreachable) silently killed the linear map's pan tool; the correct model (second finger cancels all single-finger drags; two fingers = pinch zoom only) was then ported verbatim from the original's handlers.
- **Rendering verification without an emulator**: the build host has no KVM, so visual regression relies on Robolectric software rendering, which lacks `RenderNode` — PNG export and layer recording needed explicit fallbacks — plus crop-and-zoom pixel analysis of the resulting screenshots.
- **Large reference-data ports**: the enzyme catalog (532 recognition-site groups, 3732 synonyms, 820-entry REBASE parity) and the 7-theme feature color tables were ported verbatim and locked with tests.
- **Test infrastructure quirks**: Gradle incremental runs once served stale test XML; the project standard became `./gradlew --stop` + `--rerun-tasks` for trustworthy results.
- **Material 3 discipline**: missing color roles silently fell back to baseline purple; completing every role (light/dark/AMOLED) and re-auditing contrast took a dedicated release.

## Build

```bash
export JAVA_HOME=<jdk-17>
export ANDROID_HOME=<android-sdk>
cd android-md3
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

- `applicationId com.spice.nativeapp`, `minSdk 24`, `targetSdk 29`
- ONNX Runtime native libs bundled (arm64-v8a / armeabi-v7a); model weights download on first use, not packaged in the APK

## Releases

Debug APKs are attached to every release — see [Releases](https://github.com/unohara-fuukei/SPICE_GUI_Legacy/releases). Latest: [v1.7.55](https://github.com/unohara-fuukei/SPICE_GUI_Legacy/releases/download/v1.7.55/SPICE_GUI_1.7.55_debug.apk).

## License

Apache-2.0, inherited from the original project. This port is a derivative work of [SPICE GUI](https://github.com/SPICE-Protein/SPICE_GUI) by [redelectricity](https://www.biostars.org/u/158838/); the original's copyright applies to all ported logic, and full credit for the design and implementation it replicates belongs to the original author.