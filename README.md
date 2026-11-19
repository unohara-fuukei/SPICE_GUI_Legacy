# SPICE GUI

**Gene editing, protein design, and project management — all in one place, all on your machine.**

SPICE (Sequence-Protein Interaction under Conditional Environments) GUI is a local-first desktop workbench: a **protein workbench** (multi-head fold inference, all-atom MD verification, stability scans), a **gene & cloning workbench** (plasmid maps, primers, assembly simulation), and a **project hub** that keeps every document, experimental record, and data file together in one container, with the electronic lab notebook built in and optional publishing to the public [SPD](https://d.spicebio.top) database. Computation runs where it should: fold inference via ONNX Runtime, MD verification via the [SPICE Engine](https://github.com/SPICE-Protein/spice_engine) Rust crate. No account and no upload are required and nothing leaves your desktop unless you publish.

Built with SvelteKit (Svelte 5), Tauri v2, Mol\*, and a pixel-art UI theme. Fully internationalized in seven languages.

**Documentation:** [User manual](docs/README.md) — task-oriented guides for every workspace and bench tool, with screenshots. The `docs/` directory is also a docsify site: `pnpm docs:serve` previews it locally, and Cloudflare Pages can publish it with no build step (output directory `docs`).

## Screenshots

**Gene editor** — circular plasmid map, restriction enzymes, cut sites and sequence view in one screen.

![Gene editor](images/gene.png)

**Protein workstation** — sequence + environment (pH / temperature / ionic strength) in, multi-head ONNX fold out, with the SPD publish panel below.

![Protein workstation](images/protein.png)

**Project hub** — journal pages, embedded workspace documents, attached experimental files, and one-click Presenter mode.

![Project hub](images/project.png)

**Bench tools** — seven focused utilities for the figures and analyses between experiments: gel and blot figures, qPCR, plate maps, colony counts, image panels, and trees.

![Bench tools](images/tools.png)

## Workspaces

### Protein workbench — `/protein`

> **Status** — a pretrained SPICE ONNX checkpoint is bundled inside the release package (see [Bundled model](#bundled-model)). Only Head A (reference Cα trace + distogram) carries trained weights from the 45k-sequence pretraining checkpoint; Head B / B′ / C / D are the RL-stage heads and still hold random, untrained weights, and the UI marks those heads unavailable. Predictions are not publication-ready until the RL checkpoint ships.

**Fold inference (ONNX multi-head)**

- One forward pass runs the full multi-head graph: reference Cα trace (Head A), per-position mutation probabilities (Head B, `[L,20]` softmax), design conformation coordinates (Head B′), recommended environment offset [ΔpH, ΔT] (Head C), and path-A/path-B stability confidence (Head D). In the bundled pretrain checkpoint only Head A is trained; the RL-stage heads B / B′ / C / D output random weights and are flagged in the UI.
- Distogram head rendered as an expected-distance heatmap (48 bins, 3–48 Å) plus a Cα < 8 Å contact map.
- Trained-head status is surfaced honestly — the pretrain checkpoint reports which RL-stage heads are still random.
- Example library (mini helix / lysozyme / ubiquitin / GFP), free-form 1-letter sequence input, and pH / temperature / ionic strength sliders; sequence and environment persist across sessions.

**Structure import**

- Local PDB / mmCIF text files, RCSB fetch by 4-character ID, and PDB-REDO re-refined structures with R-free / Ramachandran Z-score deltas.
- Ligand-site annotation from the RCSB data API with crystallization-additive filtering, flagged against the active pocket.

**MD verification (SPICE Engine)**

- Build all-atom systems from imported structures (PDB or mmCIF) or Cα-seeded from the fold; optional NVT equilibration.
- Step batches of 1–100 steps with live potential-energy trace, simulated-ps clock, kinetic temperature vs target, ms/step, and coordinate-clamp counters.
- Bias-force injection toggle (random 16-dim action scaled by amplitude) to push the system out of local minima; ΔT hot-switch mid-run.
- Five biophysical metrics with thresholds — m1 potential fluctuation (MAD), m2 radius-of-gyration drift, m3 secondary-structure loss, m4 clash fraction, m5 surface charge mismatch — plus a pinnable biophysics-monitor HUD that floats over any tab.
- 2D conformational landscape (RMSD × Rg) with the MD trajectory drawn live as steps run.
- Solvent-reuse mutant rebuilds: localized L-BFGS relaxation on top of the parent solvation shell instead of a full rebuild.

**Stability & phase**

- Temperature × pH grid scans — one minimized template per pH, cloned across temperatures — streamed point by point to a heatmap (stable / crashed / build-failed) with progress and cancel.
- Cooperative-collapse tracker: a Landau expansion fitted over the scan points reports the metastable center, the ΔG = 0 boundary, the bifurcation limit, and a saddle-vs-well verdict.

**Mutation & screening**

- Top-8 substitutions per position from Head B with one-click apply; a combinatorial mutation cart with an epistasis compatibility heuristic for close pairs; mutant-vs-reference Cα overlay toggle; one-click application of the Head C environment offset.
- High-throughput screening: paste a CSV mutant library (`V15A`, multi-mutation tokens) and get real biophysical descriptors per variant — GRAVY, net charge at target pH, pI / ΔpI, helix propensity, disorder fraction, and a 0–100 thermostability score.

**Viewer & export**

- Mol\* headless viewer: Cα trace / cartoon / spacefill / ball-and-stick, background, camera, exposure, lighting, outline, bloom, SSAO presets; paper-ready PNG at 1/2/4× with a *Folded by SPICE* watermark.
- mmCIF export for fold and mutant structures — standard `entity` / `atom_site` blocks plus a custom `_spice` block carrying environment, method, and confidence.
- `.spicep` protein packages: sequence, environment, coordinates, simulation history, 2D landscape, and m1–m5 metrics in one file, round-trippable.
- Retro menu bar (FILE / MODELING & SIMULATION / MUTATION & DESIGN / WINDOW), six right-hand analysis tabs, three draggable columns, activity log, toasts and OS notifications.

### Gene & cloning workbench — `/gene`

**Maps & sequence editing** (derived from TeselaGen's Open Vector Editor — see Acknowledgments)

- Circular map with stacked feature arcs, primer track, cut-site ticks with leader-line labels, per-base letters at high zoom, rotate/pan/zoom, and drag-selection; linear map with strand-aware staggered arrows; click an enzyme label to linearize the plasmid at that cut site.
- Virtualized sequence viewer with minimap, Browse / Edit / Annotate modes, toggles for enzymes, features, primers and translations, modified-range diff highlighting, and an on-screen DNA keyboard for touch devices.
- Selection toolkit: GC, Tm, reverse-complement, delete, and a rich clipboard (text + JSON with overlapping features + rendered PNG); feature tooltips with CDS molecular weight; selection notes rendered as pseudo-features.
- Degenerate-IUPAC sequence search with reverse-strand hits; six color themes for the row renderer.
- Features tab with search / type filter / sorting and a full feature editor (13 built-in types plus custom types and colors); Properties tab with topology, host, accession links, and NCBI deep links; Notes tab with a Crepe WYSIWYG editor and DNA/protein block insertion.

**Enzymes**

- REBASE-synced enzyme catalog (NEB bionet format, GitHub fallback, local cache) with fuzzy search and 46-enzyme curated starter set.
- Methylation awareness: Dam / Dcm / EcoKI sites flagged as blocked on the maps.
- Nine cut-set presets (NEB Standard, Golden Gate, Rare Cutters, BioBrick, MoClo, blunt cutters…), custom named sets with JSON import/export, and per-enzyme NEB double-digest buffer activity (r1.1 / r2.1 / r3.1 / CutSmart %, preferred buffer, heat inactivation).

**Assembly & cloning**

- Cloning wizard with ten methods — restriction, Gibson, Golden Gate, In-Fusion, NEBuilder HiFi, Gateway, directional TOPO, TOPO, TA, GC — pre-flight validation, sticky-end junction schematics, and apply-to-workspace.
- Rust multi-fragment Golden Gate assembly (BsaI / BsmBI / BbsI parameterized overhang ordering) and Cre-loxP / FLP-FRT recombinase scenes (integrate / excise / invert).
- DNA end-modification panel: Klenow fill-in, T4 chew-back, phosphorylate, dephosphorylate.
- Expression cassette builder: promoter / RBS / CDS / terminator parts with orientation and topology checks.

**Primers & PCR**

- Auto primer pairs (SantaLucia nearest-neighbor Tm) plus a primer quality table: score, GC clamp, hairpin stem/loop Tm, self-dimers.
- 5′-end modification (clamp bases, 5′-phosphate), oligo annealing duplex view, and three PCR simulations — standard amplification, overlap-extension assembly, site-directed mutagenesis — each sendable to the virtual gel.
- Primer LIMS-lite: inventory with k-mer stock alignment against the open construct, order-sheet CSV export (Sangon / Tsingke / IDT formats), and 96-well plus 10×10 freezer grid maps.

**Analysis panels** (floating, draggable, from the ANALYSIS & VIEWS menu)

- Virtual gel: marker ladders (DL2000 → 1 kb Plus), agarose % / voltage / time migration physics, editable lanes, super-resolution PNG.
- Protein view cabin: properties (MW, pI, extinction, GRAVY, PTM motifs), structure (Chou-Fasman, FoldIndex, solubility, Tango hotspots, hydropathy), domain signatures, a codon-by-codon lifecycle scroller with ΔΔG readouts, and tryptic fingerprints / disulfides / epitope predictions.
- RNA folding (Nussinov with Rust window scan), GC sliding window, fusion reading-frame check, and ribosomal frameshifting (PRF) analysis with slippery-site presets.
- Advanced genomics: Salis translation-initiation RBS strength, σ70 promoter and TFBS motifs, signal peptide and TM helix detection, GC skew, CpG islands, tandem repeats.
- Evolution tools: local k-mer BLAST against the workspace and collections, native MAFFT multiple-sequence alignment (Rust) with consensus fallback, 2D dot-plots, and alignment-file import (Clustal / PHYLIP / NEXUS / STOCKHOLM…).
- NGS panel: FASTQ QC and adapter trimming (Rust), prokaryotic ORF/gene prediction with RBS scoring, CRISPR array finder.
- Synthetic-biology CAD: iGEM parts search and import (SynBioHub), SBOL2 export, BioBrick / MoClo compatibility validation, transformation-efficiency estimates, genetic-circuit gate detection, and CARD-AMR plus IGSC biosecurity screening against synced databases.
- Synthetic-gene QC: cryptic splice sites, Kozak strength, premature stops / NMD risk, codon-pair bias, DUST low-complexity masking — with one-click elimination where supported.
- Codon tools: optimizer with live CAI, Rust Codesign Pareto panel (CAI × ΔΔG multi-objective with Pareto scatter), reverse translation with host tables, and silent mutagenesis to add or remove restriction sites.
- CRISPR suite: SpCas9 / VQR / SaCas9 / Cas12a gRNA scanning with efficiency and specificity scoring, HDR donor design with PAM-disruption reports, pegRNA design for prime editing, and CBE/ABE base-editing windows.
- Reaction calculators: C1V2 dilutions, ligation mass ratios, PCR master mixes, ng/µL ↔ nM, gradient-PCR plans, gel band sizing, 4-parameter ELISA fits, Michaelis-Menten kinetics.

**Import, export & notebook**

- Drag-and-drop GenBank / EMBL / FASTA / FASTQ / SnapGene `.dna`; NCBI RefSeq (18 organisms), Ensembl, UniProt; GFF3 / GTF / BED annotation overlays; sequence collections with ZIP round-trip (Rust).
- Auto-annotation: UniVec_Core contaminant screening (k-mer) plus a Rust Smith–Waterman feature aligner, idempotent re-runs.
- Sanger traces: `.ab1` / `.scf` import with chromatogram viewer (four-dye curves, Phred bars, paging) and auto-validation against the reference construct.
- `.spiceg` documents with dirty tracking, optional autosave, crash-recovery restore, and GenBank / FASTA / EMBL export; 100-step undo/redo with a snapshot browser.
- ELN: multi-page notebook with categories and tags; pages are signed with the verified ORCID identity into a SHA-256 digest, audit trail, locked state, and a 21 CFR Part 11-style markdown report.

### Project hub — `/project`

- One `.spiceproj` file holds everything: gene, protein, and all seven bench-tool documents are **embedded in full** (not referenced), alongside a multi-page Markdown journal with categories and tags.
- On disk this is the SPJB private binary container — roughly 4× smaller than gzip-JSON, with byte-identical restore of float32 payloads; older plain-JSON generations still import.
- Attached files (CSVs, images, PDFs, anything ≤ 20 MiB) live inside the project with in-app previews and save-as.
- Tool documents carry a compact preview and one-line result summary; any preview can be inserted into the current journal page as a captioned Markdown image.
- Capture-and-carry handoff: workspaces and bench tools snapshot into the project or open embedded docs with a live write-back banner; project registry and session resume survive restarts; dirty LED and Ctrl+S throughout.
- **Presenter mode** — split any journal page into slides by headings and `---` rules; quote blocks become speaker notes. Full grammar: task lists, tables with alignment, footnotes, highlights, sub/superscripts, presenter-only comments. Audience and presenter views, next-slide preview, elapsed timer, keyboard navigation, fullscreen.

### Bench tools — `/tools`

Seven standalone utilities, each with its own editor, local autosave, re-openable JSON project, and publication-ready export. Images never leave the machine. Every tool is also a first-class SPICE_PROJECT document, can write a SHA-256-manifested reproducibility bundle (figure + data + project + parameters + methods), and shares one sample/plate vocabulary with the other tools.

#### Gel figure — `/tools/gel`

![Gel figure tool](images/tools-gel.png)

- Auto-straighten, auto-crop, exposure presets, grayscale / invert / brightness / contrast / gamma, 90° rotation, mirroring, and fine rotation.
- Lane and band detection, with a marker-ladder library: fit the marker lane once, then propagate sizes to every sample lane from the calibration curve (R² reported).
- Figure composer with lane names and angles, group headers, condition rows, arrows, region boxes, enlarged insets, brackets, side text, captions, and reusable style templates.
- Pre-flight checks for clipping, effective dpi, font size, and ladder fit; exports PNG / TIFF / SVG / PPTX, the uncropped image, the original file, and a processing log.
- Folder batch mode runs straighten, crop, gel-type inference, lane/band detection and optional ladder calibration over up to 60 images, then saves one ZIP with per-image figures, projects and summary CSV. The batch runs in a module worker, so the editor stays responsive.

#### Western blot densitometry — `/tools/blot`

![Western blot densitometry](images/tools-blot.png)

- Automatic lane and band detection with a morphological baseline, then draggable ROIs and manual band/lane correction.
- Four background models (ROI ring, lane median, global median, manual), loading-control or total-protein normalization, control groups, and fold change.
- Saturation flags, per-band net volume, group mean / SD / SEM, 95% confidence intervals, and Welch p values against the control group.
- Annotated PNG and SVG figures, results CSV, a project file that round-trips image/ROIs/assignments, and a batch mode that quantifies up to 40 images into one ZIP. Batch processing runs in a module worker.

#### qPCR analysis — `/tools/qpcr`

![qPCR analysis](images/tools-qpcr.png)

- Paste or import CSV/TSV, map columns manually, and run in ΔΔCt or standard-curve mode with optional per-target efficiency correction.
- Multiple reference targets with geometric-mean reference Ct, control group and control sample selection; QC flags for missing Ct, missing reference, high Ct, replicate SD, outliers, and duplicate wells.
- Standard-curve fit (slope, intercept, R², efficiency) with interpolated unknowns, ΔCt heatmap, group summaries, Welch p values, and per-replicate detail.
- Import a plate designer CSV/TSV to carry well, sample, group, and replicate identifiers into qPCR without retyping.
- Results CSV, chart SVG/PNG at 300 dpi, a project JSON, and a reproducibility bundle.

#### Plate layout designer — `/tools/plate`

![Plate layout designer](images/tools-plate.png)

- Six formats from 6 to 384 wells; paste or import samples with groups, colours, and replicate counts.
- Seeded layout generation with controls, blanks, reserved edge wells, contiguous row / column / block grouping, keep-group-together, and re-randomization.
- Rule checks for duplicate names, unplaced samples, and well conflicts; inspect or paint individual wells, then correct the map by hand.
- Export the sample list as a portable `SPICE_SAMPLES` JSON/CSV sheet or import one back; export a qPCR-ready plate map with the `well,sample,group,type,replicate` header.
- Plate-map CSV/TSV, SVG, 300 or 600 dpi PNG, JSON project, and a reproducible bundle; autosaves the current layout.

#### Colony counter — `/tools/colony`

![Colony counter](images/tools-colony.png)

- Import, rotate, crop, and adjust plate images, then segment with Otsu thresholding, blur, polarity selection, area / circularity filters, and touching-colony splitting.
- Manual correction by adding, dragging, including, excluding, or deleting markers, with mask and numbering overlays.
- Counts, included/excluded totals, mean/median area, mean intensity, and a colony-area histogram; CFU/mL from dilution factor and plated volume, with exact Poisson 95% intervals.
- Optional blue/white classification from mean RGB and HSV hue, with manual overrides and per-class counts.
- Batch mode segments up to 40 plate images into a ZIP; exports annotated PNG, mask PNG, marker CSV, statistics CSV, and a re-openable project. Processing runs in a module worker with a main-thread fallback for older WebViews.

#### Image annotator — `/tools/annotate`

![Image annotator](images/tools-annotate.png)

- Compose multi-panel figures from imported images, with per-panel crop, order, duplicate, and image adjustment.
- Figure layout controls: columns, row/column flow, gap, margin, physical width in mm, panel labels (A/B/C, a/b/c, 1/2/3), font, size, colour, and position.
- Vector annotations — arrows, rectangles, ellipses, text, calibrated or fixed scale bars, panel bars, and intensity line profiles — each editable by geometry, colour, fill, and stroke.
- Per-panel RGB channel visibility, gain, invert and merged composite rendering; profile plots export as CSV.
- Autosave plus PNG, SVG, project JSON, and a reproducibility bundle.

#### Phylogenetic tree — `/tools/tree`

![Phylogenetic tree](images/tools-tree.png)

- Newick import with quoted labels, comments, branch lengths, and support values; phylogram, cladogram, and circular layouts with support thresholds, node markers, scale bar, and dark/light themes.
- Beautify tools: ladderize either direction, sort leaves alphabetically or by group, midpoint rooting, and interactive collapse / expand / rotate clades.
- Leaf search and path-to-root highlighting, plus CSV/TSV metadata joins with group colours, palettes, and legend; one group can be focused.
- Bootstrap/support histogram with threshold-aware shown/filtered counts, and Robinson-Foulds comparison against a second Newick tree.
- Export SVG, 300 or 600 dpi PNG, edited or original Newick, project JSON, and a reproducibility bundle; session autosave.

### SPD integration

- **Universal API Token** — mint one at [d.spicebio.top](https://d.spicebio.top) after ORCID login, paste it into *Settings → Public Integrations*, verify. The token inherits your live roles and revokes instantly.
- **Publish chain** — `(protein | construct | variant) → sequence → fold`. A fold embeds its MD simulation environment and model identity (the checkpoint is hashed locally); every entity deduplicates server-side, so republishing is idempotent. Folding coordinates themselves stay local — only summary records go to the database.
- **Batch lookup** — the graph-DSL read endpoint (`POST /query`) walks `sequence → folds → environment` for every locally known sequence id in one metered request, matching your current simulation environment exactly.
- Publishing is desktop-only: the API's CORS policy excludes packaged apps, so requests go through the Rust bridge, and the checkpoint SHA-256 can only be computed locally.

### Global experience

- **Settings**: seven interface languages, ten color palettes with dark/light/system modes, ORCID identity with auto-revalidation, model status / path / cloud download with progress, database syncs (REBASE, CARD-AMR, IGSC biosecurity), engine defaults, primer salt parameters, and a rebindable keyboard-shortcut editor with conflict detection (~21 bindings).
- **AI Co-Pilot**: chat against any OpenAI-compatible endpoint (local Ollama by default) with workspace context injected — sequence, environment, metrics; replies carrying ` ```json ` mutation arrays or ` ```dna ` blocks import straight into the editors. The bundled calculators (mutation stabilizer, codon optimizer, SOP drafts) are clearly-labeled local heuristics, not engine calls, and offline mode says so.
- Pixel-retro chrome throughout: hover-dropdown menu bars, draggable floating panels with z-order focus, activity logs, in-app toasts plus native OS notifications. In this legacy mobile build, phones/tablets get a portrait bottom-tab bar instead of an orientation guard (see [Legacy mobile / WebView 75 build](#legacy-mobile--webview-75-build) below).

## Architecture

```
┌──────────────────────────────────────────────────────┐
│ SvelteKit (Svelte 5 runes) + svelte-multistyle-ui    │
│ routes: welcome · /protein · /gene · /project ·      │
│         /tools · /settings                           │
│ lib: backend (invoke wrappers + browser demo mode)   │
│      gene/ cloning components · project/ SPICE_      │
│      PROJECT · tools/ bench editors · spd/ client ·  │
│      viewer/ Mol* · identity/ ORCID · i18n Paraglide │
└───────────────────────┬──────────────────────────────┘
                        │ Tauri IPC (invoke)
┌───────────────────────▼──────────────────────────────┐
│ src-tauri (Rust)                                     │
│  onnx.rs     ort inference · tokenize · post-process │
│  spice.rs    SPICE Engine build/step/scan/metrics    │
│  spd.rs      HTTP bridge (no CORS) · checkpoint hash │
│  cloning.rs  Smith–Waterman annotator · UniVec sync  │
│  files.rs    native save dialogs                     │
└──────────────────────────────────────────────────────┘
```

`pnpm dev` runs the frontend in a plain browser: every Tauri command degrades to a typed local fallback so the entire UI stays navigable and testable without Rust, a model, or the engine.

## Legacy mobile / WebView 75 build

This branch is the legacy mobile/WebView adaptation layer on top of the upstream desktop workbench. Functionality and scientific logic track upstream v0.5.0; the items below are platform adaptations for older Android System WebView (Chromium 75, Android 10-era) phones and are not part of the upstream desktop build:

- **Portrait navigation**: the upstream orientation/rotation guard is removed. Narrow/touch viewports use a bottom tab bar (home, gene, protein, project, lab, tools, settings) instead of the top-bar switcher; the split gene/protein layouts stack vertically rather than sitting side-by-side.
- **WebView 75 compatibility**: esbuild targets `chrome75`. core-js polyfills and a Web-API shim (`static/polyfills-core.js`, `static/polyfills-web75.js`) load as classic scripts before any ES module. A post-build CSS pass (`scripts/webview75-css-compat.mjs`) rewrites `inset`, `clamp()/min()/max()`, flex `gap`, `:focus-visible`, `:where()` and `color-mix()` for Chromium 75 and emits the color-mix runtime shim.
- **Runtime fallbacks** missing on WebView 75: gzip uses a bundled `pako` pure-JS fallback when `CompressionStream` is unavailable; file pickers accept `*/*` because the WebView does not honour MIME/extension filters; `aspect-ratio` containers use a percentage-padding spacer.
- **3D viewer WebGL fallback**: on Android WebView the Mol* viewer uses the traditional `blended` transparency path instead of the default WBOIT (older era GPUs/drivers often lack the multi-render-target/float-buffer support WBOIT needs, which can blend transparent objects into the background until a camera redraw). It also prefers the WebGL1 context, allows a performance-caveated (software) context instead of refusing it, and disables MSAA/antialias to reduce weak-driver pressure; desktop keeps the upstream defaults. Extra redraws are requested after the structure loads and on resize, and a slow keep-alive redraw cadence keeps an idle frame from being cleared; desktop keeps the upstream default transparency. The viewer container sets `touch-action: none` so a single-finger drag stays with the camera trackball instead of being captured by the WebView's native scroll/zoom gesture, and on legacy WebGL1 the temporal multi-sample pass is turned off for a single-buffer render to cut per-frame load and weak-GPU flicker. The PNG export draws from the screenshot's own 2D canvas instead of a data-URI `<img>` round-trip, which fails to decode on old WebViews.
- **Touch targets**: 48px minimum tap targets, safe-area insets, and a 16px input font size to prevent WebView zoom-on-focus.
- **Portrait layout adaptation** (legacy-only; upstream v0.5.0 is a desktop workbench with no mobile layout to mirror): on narrow / coarse-pointer viewports the protein three-column view and the settings split view stack into a single column. Where a single-column page previously nested a second vertical scroll inside the outer one, that inner panel scroll is folded into the outer single scroll container; floating drawers/modals, short local lists and logs keep their own bounded scroll. Buttons, inputs, selects, tabs, toggles, checkboxes and radios get roomier touch heights and padding for one-hand use, and scrollbars are widened to a 10px draggable width with a Firefox `scrollbar-width` / `scrollbar-color` fallback. This is size, spacing and layout tuning only — the pixel-retro borders, shadows and palette are unchanged; components are not restyled into a flat Material look. Gene map / sequence canvas panes and the project rich-text editor keep their fixed-height internal scroll, as canvas/editor surfaces must size themselves.
- **Crash diagnostics**: a read-once native crash report (`get_last_crash` Rust command + `getLastCrashReport()` wrapper) surfaces the last uncaught exception in an in-app overlay.
- **i18n write-out**: `scripts/paraglide-compile-safe.mjs` compiles the catalogs with a serial, bounded file write-out instead of the CLI's unbounded concurrent writes, so the ~4.4k message modules are all emitted on hosts with a low open-file ulimit.
- **CI workflow**: upstream v0.5.0's macOS ad-hoc bundle signing and DMG signature-verification guard are not carried over. This fork keeps `.github/workflows/build.yml` at the legacy baseline — the push credential has no workflow write scope, so the upstream CI file could not be pushed. Application and engine code otherwise track upstream v0.5.0; only that CI job is omitted here.
- **Android package**: the arm64-v8a APK is produced by the CI `android-arm64` job on `ubuntu-latest` (`pnpm tauri android build --apk --target aarch64`); only `aarch64-linux-android` is built — no `armeabi-v7a`/x86 split. When the `ANDROID_KEYSTORE_*` secrets are present, the release variant is signed with the project keystore (APK Signature Scheme v2/v3); otherwise it falls back to the shared debug key exactly like a local build. The bundled ONNX model and the NDK `libc++_shared.so` are packaged into the same APK. minSdk 24, targetSdk 29 (Android 10 / WebView 75 era).

## Bundled model

The pretrained SPICE checkpoint is shipped inside every release package instead of being downloaded at runtime, so a packaged build can fold without an internet connection and does not fall back to the synthetic demo because the model is missing.

- **File**: `model/export/spice_infer_fixed.onnx` (~25.0 MiB, all fp32, ONNX opset 13, ~4.98 M parameters). It is committed to this repository and copied into the bundle through the `bundle.resources` map in `src-tauri/tauri.conf.json`, landing at `model/export/` under the app resource dir.
- **Origin**: converted from the SPICE-Protein 45k-sequence pretraining checkpoint (Keras `.weights.h5`) with the project's official export script. The RL-stage heads did not have matching weights in that checkpoint; the 146 pretrain variables match within `1e-5`, the six head-B/C/D kernels and biases are untrained random.
- **Runtime contract**: inputs `tokens` int32 `[B,L]`, `env` f32 `[B,3]`, `mask` f32 `[B,L]`; outputs `conf`, `coords`, `coords_mut`, `dist_logits [B,L,L,48]`, `env_offset`, `mutation [B,L,20]`. The backend resolves the bundled resource dir candidate at startup; an external checkpoint selected in *Settings → Model* overrides it.
- **Trained-head status**: only Head A (reference Cα trace + distogram) is trained. Heads B / B′ / C / D carry random RL-stage weights and are marked unavailable in the UI; refresh them with the same export script once the RL checkpoint is published — the I/O contract does not change.
- **Verification**: a Python replica of the Rust pre/post-processing was compared against the reference training pipeline on 18 sequence × environment cases (lengths 1, 2, 7, 24, 65, 195; default / acidic / hot), all passing finite-value, normalization and geometry checks. The bundled-model offline load and inference chain were re-verified on real Android WebView75 devices; the untrained RL-stage heads listed above still do not produce meaningful predictions, so Head-A-only output is not publication-ready.

## Main branch adaptation notes

This `main` branch is the legacy mobile / WebView 75 adaptation layer on top of the upstream v0.5.0 desktop workbench. The notes below record what was adapted here, how this fork differs from upstream, and the difficulties encountered — as they stand, without smoothing over the limitations.

### What this branch adapts

- **WebView 75 compatibility**: esbuild targets `chrome75`; core-js polyfills and Web-API shims (`static/polyfills-core.js`, `static/polyfills-web75.js`) load as classic scripts before any ES module; a post-build CSS pass rewrites `inset`, `clamp()/min()/max()`, flex `gap`, `:focus-visible`, `:where()` and `color-mix()` for Chromium 75 and emits the color-mix runtime shim.
- **Portrait / touch layout**: upstream's orientation/rotation guard is removed; narrow and coarse-pointer viewports use a bottom tab bar, split gene/protein layouts stack vertically, single-column pages fold inner panel scroll into the outer container, and tap targets, font sizes, safe-area insets and scrollbar widths are tuned for one-hand use.
- **Legacy-GPU 3D fallback**: the Mol* viewer uses the `blended` transparency path and a WebGL1 context, allows a software/performance-caveated context, and disables MSAA; the container keeps `touch-action: none` so trackball drags are not captured by the WebView gesture, and legacy WebGL1 disables temporal multi-sampling for a single-buffer render; extra redraws and a keep-alive cadence keep an idle frame from being cleared.
- **Model packaging**: the ONNX checkpoint is built into the package; the backend locates it through the Tauri resource dir, the token tensor was aligned to the graph's int32 input, and the SPD checkpoint SHA-256 hashes the bundled file locally.
- **Crash diagnostics, i18n write-out, CI**: a read-once native crash report surfaces the last uncaught exception in-app; Paraglide catalogs are written serially on low-file-limit hosts; the CI workflow builds four desktop targets plus the Android arm64 APK with conditional keystore signing.

### Differences from upstream v0.5.0

- Upstream is a desktop workbench with no mobile layout to mirror; the portrait stack, bottom tabs and touch sizing in this fork are legacy-only additions.
- The model is bundled in the package here; upstream assumed an external / not-yet-published checkpoint.
- Upstream's macOS ad-hoc bundle signing and DMG signature-verification guard are not carried over; Windows code signing and Apple notarization are still unconfigured.
- Only `aarch64-linux-android` is packaged; there is no `armeabi-v7a` or x86 Android split.
- The GitHub Release job runs only on a `v*` tag push. `workflow_dispatch` builds and uploads Actions artifacts but does not create a Release.

### Difficulties encountered

- **Missing Web APIs on WebView 75**: `CompressionStream` is unavailable, so gzip uses a bundled pure-JS `pako` fallback; the WebView ignores MIME/extension picker filters, so file pickers accept `*/*`; `aspect-ratio` containers use a percentage-padding spacer.
- **Weak GPU drivers**: WBOIT needs multi-render-target / float-buffer support that older-era GPUs and drivers often lack; transparent objects otherwise blend into the background until a camera redraw, which forced the blended/WebGL1/MSAA-off fallback.
- **ONNX export gaps**: tf2onnx did not support `Erfc` (×10) and `Cross` (×3) in this graph, so the export pipeline applies a post-graph repair step; Keras 3 `.weights.h5` loading matches variables with `skip_mismatch`, leaving the untrained RL heads unmatched.
- **Tensor dtype contract**: feeding an i64 token tensor into the graph's int32 `tokens` input is rejected by ONNX Runtime; the backend token vector was changed to i32 to match.
- **Android toolchain**: `ring`/cc-rs could not find the bare-prefix `aarch64-linux-android-ar` in the NDK (it ships only versioned `aarch64-linux-android24-clang`), so `cargo-ndk` injects the correct ar/linker. ORT's prebuilt links `libc++_shared` dynamically, which must be copied into `jniLibs/arm64-v8a` or ONNX loading crashes at runtime.
- **Build memory and system deps**: on a low-memory host without swap, the full frontend `vite` build can hit the Node heap limit (exit 137) and Rust is kept to `CARGO_BUILD_JOBS=2`. The local host check is blocked on missing `gdk-3.0`/webkit2gtk system libraries; the desktop target compile is covered by the CI desktop jobs rather than the local machine.
- **Repository size**: the 25 MiB model is committed as a plain git file — this repository has no LFS — so the `.git` history grows by that size.
- **CI credential scope**: the push credential does not have workflow-write scope, so the upstream CI file could not be pushed directly; this fork keeps its own `build.yml` at the legacy baseline.

## AI-assisted development

This legacy adaptation layer was drafted, debugged, cross-checked against the reference pipelines, and documented with assistance from a generative AI assistant (LLM). Final code and scientific behavior are the responsibility of the human reviewer.

- The bundled ONNX weights originate from a public pretraining checkpoint in the SPICE-Protein repository and are **not** AI-generated. Numerical parity with the reference Python pipeline was checked before packaging (the 18-case replica mentioned above).
- The AI assistance covers implementation, tracing and documentation; it does not replace experimental validation. Head availability, real-device inference quality and any on-device WebView75 behavior are settled by on-device verification, not by the assistant.

## Getting started

Prerequisites: Node 22, pnpm, a stable Rust toolchain, and [Tauri v2 system dependencies](https://v2.tauri.app/start/prerequisites/).

```bash
pnpm install

pnpm dev          # browser preview (demo mode) → http://localhost:1420
pnpm tauri dev    # desktop app — the first Rust compile is slow (engine + candle + ONNX Runtime)
pnpm tauri build  # bundles for the host platform
```

**Model**: the release package bundles the pretrained `spice_infer_fixed.onnx` checkpoint under `model/export/` inside the app resource dir, so a packaged build can fold out of the box without downloading anything. *Settings → Model* can still point at an external ONNX checkpoint to override the bundled one. If no model can be resolved, the app reports the missing backend rather than returning a fabricated prediction — the browser/demo fallback is a clearly labelled synthetic structure, not an inference result.

**Engine**: `src-tauri/Cargo.toml` depends on `spice_engine` from GitHub. Two `[patch.crates-io]` entries (the `ewald` and `bio_files` forks under SPICE-Protein) are mandatory in this root manifest — Cargo does not propagate patches declared by dependencies. The engine's default features pull in candle (GNN inference) and the heavy force-field assets; expect a sizeable binary.

## Quality gates

```bash
pnpm check                    # svelte-kit sync + svelte-check (0 errors required)
pnpm test                     # Vitest algorithm and data-interchange suite
pnpm build                    # static frontend build
pnpm i18n                     # recompile Paraglide catalogs after editing messages/*.json
cd src-tauri && cargo check   # Rust backend
```

All visible strings go through Paraglide (`project.inlang/messages/{zh,en,ja,ko,de,nl,sv}.json`, with Chinese as the source catalog); Rust emits `i18n:key::args` codes that the frontend resolves. Hardcoded UI strings fail review.

## Repository layout

```
src/routes/           seven areas (welcome, protein, gene, project, lab, tools, settings)
src/lib/backend/      typed invoke wrappers, browser fallbacks, mmCIF export, demo mode
src/lib/gene/         cloning UI components (maps, primers, gels, CRISPR, QC)
src/lib/project/      SPICE_PROJECT store, .spiceproj container, Presenter mode
src/lib/tools/        seven bench editors plus shared project/batch/sample helpers
src/lib/repro/        ZIP writer, SHA-256 manifest, reproducible bundle builder
src/lib/spd/          SPD client (transport switch, publish chain, graph DSL)
src/lib/viewer/       Mol* wrapper and view settings
src/lib/identity/     ORCID profile and ELN signing identity
src/lib/paraglide/    compiled i18n catalogs (generated — edit project.inlang instead)
project.inlang/       message source and plugin config
tests/                Vitest suites for scientific math and interop formats
scripts/              logo sync, WebView75 polyfill/CSS/gap adapters, Android landscape patch, safe Paraglide compile, UniVec fetch
src-tauri/            Rust backend (see architecture), Tauri config, capabilities
.github/workflows/    algorithm tests + multi-platform CI with optional signing
```

## Acknowledgments

Special thanks to [TeselaGen Open Vector Editor](https://github.com/TeselaGen/openVectorEditor) (MIT) for providing such an excellent DNA editor — the gene/cloning workbench in this app is a derivative work, developed on top of OVE and integrated into the GUI.

## License

Apache-2.0 — see [LICENSE](LICENSE).
