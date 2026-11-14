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
- **Gel photo recognition**: classical CV (grayscale → lane split → row projection → log10 calibration) instead of the original's GelGenie U-Net model.
- **AI Co-Pilot**: non-streaming chat (no typewriter effect), no agent tool-calls / MCP support.
- **Project hub**: attachments, multi-document embedding, presenter mode, qPCR XLSX import, and multi-membrane blots are not implemented.
- **Bench tools**: qPCR is calculator-only (no Ct table import); Western blot is schematic; phylogenetic tree uses 4-mer Jaccard + UPGMA (no MSA input).
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

Debug APKs are attached to every release — see [Releases](https://github.com/unohara-fuukei/SPICE_GUI_Legacy/releases). Latest: [v1.7.15](https://github.com/unohara-fuukei/SPICE_GUI_Legacy/releases/download/v1.7.15/SPICE_GUI_1.7.15_debug.apk).

## License

Apache-2.0, inherited from the original project. This port is a derivative work of [SPICE GUI](https://github.com/SPICE-Protein/SPICE_GUI) by [redelectricity](https://www.biostars.org/u/158838/); the original's copyright applies to all ported logic, and full credit for the design and implementation it replicates belongs to the original author.
