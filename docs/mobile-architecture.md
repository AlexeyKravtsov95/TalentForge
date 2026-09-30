# Mobile architecture (M3A foundation, M4.1–M4.2 production tree screen)

This document describes the Kotlin Multiplatform foundation introduced by M3A
and the M4.1/M4.2 production read-only Talent Tree screen: module boundaries,
dataset flow, strict JSON policy, conformance strategy and local build
commands. It is the orientation map for later milestones — none should require
restructuring what is described here.

## Module boundaries

```
mobile/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/libs.versions.toml        single source of dependency versions
├── gradlew / gradlew.bat            committed wrapper (Gradle 9.5.1)
├── shared/                          pure Kotlin Multiplatform library
├── composeApp/                      Compose Multiplatform application host
└── iosApp/                          thin native iOS host (Xcode project)
```

### `shared`

Pure Kotlin Multiplatform library — **no Compose, no platform types**. Contains:

- domain/dataset models for the public JSON contracts (`data.talentdata`,
  `data.manifest`, `data.allocation`, `data.conformance`);
- typed dataset identity (`GameId`, `ClassToken`, `ContentHash`,
  `DatasetBundleId`, `DatasetId`);
- `TalentDataContract` — the strict JSON reader for every contract;
- `TalentDatasetRepository` interface + `BundledTalentDatasetRepository`
  implementation with manifest/document integrity checks and typed
  `DatasetLoadError` failures;
- `DatasetAssetSource` abstraction (bytes in, nothing platform-specific out);
- the M3B allocation engine and conformance executor in the `allocation`
  package (see "Conformance strategy" below).

Targets: Android, iOS arm64, iOS simulator arm64, JVM (fast contract tests).

### `composeApp`

Compose Multiplatform application/UI host. Depends on `shared`. Contains:

- `App` — thin entry that hosts the production screen below;
- `ui/tree` — the **M4.1/M4.2 production read-only Talent Tree screen**: the bundled
  baseline Forever Warrior document is loaded through
  `BundledTalentDatasetRepository` (no JSON in composables) into explicit
  Loading/Ready/Failed states, and rendered as a pannable, pinch-zoomable
  canvas driven entirely by the real dataset:

  - `TalentTreeUiModel` — a pure, geometry-only projection of the real
    `TalentTree` (node id/name/monogram/max-rank/section, world-canvas
    positions, prerequisite + visual-only edges, computed bounds). It contains
    no allocation semantics and duplicates no contract hierarchy. One uniform
    factor maps client units to dp (one `layout.xStep` = 76 dp, the B3 portrait
    column pitch), preserving the client's relative geometry without
    distortion. Fail-closed presentation preconditions return typed build
    errors instead of crashing or emitting sentinel geometry: no tree,
    multiple trees, no nodes, duplicate node ids, invalid layout step,
    dangling edge references;
  - `TalentTreeViewport` — the single world→screen transform (`dp·scale +
    offset`) with pure helpers for world↔screen, pan, focal-point zoom
    clamped to the B3 0.8–2.2× bounds, and the deterministic initial
    transform. `TalentTreeViewportState` holds it (saveable — no reset across
    recomposition/rotation). Hit testing runs in **screen space**: the
    effective target is `max(56 dp · scale, orientation minimum)` so zooming
    out never shrinks the physically tappable area below the B3 usable
    minimums (56 dp portrait, 48 dp landscape) — node visuals are untouched
    by this;
  - `TalentTreeCanvas` — connectors drawn behind nodes under one shared
    transform, nodes as keyed composables carrying the real names in their
    accessibility semantics, and the B3 gesture arbitration: tap node =
    select only (never spends), tap empty canvas = deselect, drag (7 dp
    slop, including node-origin drags) = pan, pinch = zoom around the
    fingers, extra-finger join/leave rebases anchors so nothing jumps;
  - `TalentNodeFallbackVisual` — the **icon fallback**, isolated on purpose:
    `talent-data.v1` has no icon/texture identifier, so M4.1 renders the B3
    implementation-safe rounded square with a deterministic monogram derived
    from the real node name (leading "Improved" dropped; first letters of the
    first and last remaining word — "HS" Heroic Strike, "RE" Rend). No
    networking, no scraped art, no schema invention; a future icon renderer
    replaces this file's content without touching geometry or gestures.
    M4.2 layers the B3 node-state language over the fallback shape (see
    `TalentNodePresentation` below): solid/dashed/dimmed borders, accent
    tint/fill + double rim for invested ranks, glyph chips (padlock =
    tier-locked, ↑ = prerequisite-locked) and the 15 dp rank chip derived from
    the real BuildState, with the neutral selection ring layered **above**
    any state;
  - `TalentNodePresentation` — the **M4.2 read-only allocation-state
    projection**: a pure mapping of `TalentAllocationEngine.canSpend` over an
    immutable `BuildState` (the production build is `engine.emptyState()`;
    the mapper accepts any state so M4.3 reuses it when ranks change). It
    never calls `spend`/`refund` (a desktop test scans production sources to
    enforce this). The engine's full ordered issue set is preserved per node
    (a node can be prerequisite- *and* tier-locked at once); the single
    `state` field is documented UI-only precedence (fail-closed →
    partial/maxed → prerequisite → tier → no-points), not allocation
    semantics. `TalentInspectorContent` builds the inspector copy from
    published names/max-ranks and structured issue values only — no invented
    spell descriptions, effect prose or spec names (the dataset publishes no
    section names, so the B3 tier line reads "Spend N more points in this
    tree" without "in Arms");
  - `TalentInspector` — the **M4.2 adaptive inspector**, one content model in
    three presentations: portrait 64 dp compact bar (14 dp inset, grows at
    large text) expanding into the 448 dp-preferred sheet capped at available
    height minus 96 dp with internal scrolling; landscape 300 dp side panel
    laid out **beside** the canvas (never painted under it — the tree pans
    only if the panel would cover the node); tablet (840 dp+) persistent
    380 dp inspector with a minimal empty prompt (the spec freezes the
    behaviour, not the prompt wording — the line is documented
    implementation-safe). All read-only: `Show` is the only action, offered
    only when the engine reports exactly one unmet prerequisite source
    (multi-source cases fail safe with no action rather than inventing a
    preference);
  - prerequisite navigation (B3 07): `Show` selects the direct prerequisite
    (one hop, never spends), the viewport centres the target only when it is
    not fully visible outside the inspector-covered area (300 ms ease-out,
    scale untouched, transform always finite — `TalentTreeViewport.focusedOn`
    is pure and unit-tested), the target pulses once for 900 ms, the origin
    keeps a dashed ring, the guided edge turns bright at 3 dp while other
    connectors fade to 18%, and the target's state line appends the B3
    "needed for <origin>" suffix. The next selection, a deselect or a spec
    change clears the guide;

- `ui/theme` — design tokens frozen by `TalentForge_UX_UI_Spec_v1.pdf`
  section G: colour (OKLCH-derived sRGB constants), spacing, radius, motion
  durations, typography roles, and the `ClassAccent` type with the single
  spec-fixed Warrior accent;
- `ComposeResourceDatasetAssetSource` — the standard Compose Multiplatform
  resource mechanism (`composeResources/files/…`) as a `DatasetAssetSource`;
  it works on Android and iOS and only hands bytes to `shared`, where all
  JSON parsing happens. There are no per-platform parser implementations.

Targets: Android, iOS arm64, iOS simulator arm64, desktop JVM (production
screen + packaged-resource and UI interaction tests).

Material 3 is deliberately absent: Compose Multiplatform 1.10+ publishes its
`material3` artifacts only in pre-release form, and this project accepts no
alpha/beta/RC dependencies.

M4.1 is **read-only by scope**: the M3B `TalentAllocationEngine` exists but no
spend/refund UI, inspector, persistence, Home screen, class picker or
navigation is exposed — those belong to later milestones. The M3A aggregate
smoke host was removed with its screen; the repository/resource pipeline it
proved remains covered by the desktop tests.

M4.2 extends the same screen with the **adaptive Talent Inspector and the
read-only allocation-state presentation** described above. It remains strictly
**read-only**: the screen holds one immutable `engine.emptyState()` build, the
engine is only queried (`canSpend`/`validate`/`spentPoints`/
`remainingPoints`), `Show` is pure navigation, and no spend/refund/undo
controls exist anywhere. BuildState mutation arrives in M4.3.

### `iosApp`

Thin native iOS host. A SwiftUI `App`/`UIViewControllerRepresentable` pair that
presents `MainViewController()` from the `ComposeApp` framework — no separate
Swift business or domain layer, no CocoaPods. The framework is built by the
standard Compose Multiplatform integration: the Xcode target runs a
`Build KMP Framework` script phase invoking
`gradlew :composeApp:embedAndSignAppleFrameworkForXcode` and embeds the result.

## Version stack (all stable)

| Component | Version |
|---|---|
| Kotlin / Kotlin Multiplatform | 2.4.20 |
| Compose Multiplatform | 1.10.3 |
| kotlinx.serialization | 1.11.0 |
| Android Gradle plugin | 8.13.2 |
| Gradle (wrapper) | 9.5.1 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |

All versions are centralized in `mobile/gradle/libs.versions.toml`.
AGP stays on the 8.x line because AGP 9 dropped support for Android
application targets inside `kotlin("multiplatform")` modules, and Compose
Multiplatform 1.12+ requires AGP 9.1+ on Android — the combination used here is
the newest fully stable, mutually compatible stack.

## Data contracts

The Kotlin consumer reads the existing public JSON schemas; it does **not**
mirror Python internals:

| Contract | Schema | Kotlin entry point |
|---|---|---|
| talent-data v1 | `docs/schema/talent-data.v1.schema.json` | `TalentDataContract.decodeTalentDataDocument` |
| dataset manifest v1 | `docs/schema/dataset-manifest.v1.schema.json` | `TalentDataContract.decodeDatasetManifest` |
| allocation build v1 | `docs/schema/allocation-build.v1.schema.json` | `TalentDataContract.decodeAllocationBuildFile` |
| allocation conformance v1 | `docs/schema/allocation-conformance.v1.schema.json` | `TalentDataContract.decodeConformanceSuite` |

The schemas are closed contracts; M3A is a consumer and never changes them.

## Strict JSON policy

`TalentDataContract.json` is built with:

- `ignoreUnknownKeys = false` — an unknown key is schema drift and fails loudly;
- `coerceInputValues = false` — an unknown enum value fails, never falls back
  to a default;
- `isLenient = false` — structural type mismatches (objects where strings are
  expected, decimals where integers are expected, non-numeric strings) fail;
- Kotlin defaults exist **only** where the JSON schema itself declares one;
  required fields cannot be silently invented. Every property listed in a
  schema's `required` array is mandatory on the wire even when it is nullable
  (`spellId`, `name`, `perRank`, `pointBudget`, …) or normally empty
  (`prerequisites`, `effects`, `visualOnlyEdges`, `countedNodeIds`,
  `reasonCodes`, `issues`) — omitting any of them fails deserialization;
- an unsupported `schemaVersion` raises `UnsupportedContractVersionException`
  and surfaces as `DatasetLoadError.UnsupportedSchemaVersion`;
- quoted numeric literals (`"maxRank": "3"`) are rejected wherever the schema
  requires a JSON number. kotlinx.serialization accepts them by design, so
  every contract read is followed by a centralized wire-form check that
  compares the payload against the canonical re-encoding of the decoded
  model: at every position the model serializes as a number, the payload must
  carry an unquoted numeric primitive. Legitimate numeric *strings* (schema
  type string, e.g. `clientBuild`) are unaffected.

Open string unions the schema explicitly allows are modelled faithfully:
`TalentNode.type` keeps unmapped values verbatim as `TalentNodeType.Unknown`
(the `unknown:<n>` form is part of the contract, not drift).

## Dataset source of truth and sync flow

1. Canonical data lives in `data/normalized/forever/<build>/` (Python
   pipeline output, committed).
2. `scripts/sync_mobile_datasets.py` copies `manifest.json` + the nine class
   documents of the baseline (`1.60.1.70009`) into
   `mobile/composeApp/src/commonMain/composeResources/files/dataset/forever/1.60.1.70009/`.
3. `--check` mode fails (exit 1) when the bundled files differ byte-for-byte
   from the canonical tree.

The bundled copy is deterministically derived and never hand-edited. Drift is
caught in three places: the Python check script (CI step 1), the JVM
`DatasetDriftTest`, and the resource pipeline itself (files are packaged as-is).

## Runtime resource loading

`shared` never touches `AssetManager`/`NSBundle`/Compose resources. It reads
through `DatasetAssetSource`; `composeApp` implements it over the Compose
Multiplatform resource mechanism (packaged for Android and iOS alike). On
load, `BundledTalentDatasetRepository` verifies:

- supported `schemaVersion`;
- document game / client build / class token agree with the manifest and the
  request;
- the published document `contentHash` equals the manifest entry (the hash
  algorithm itself is not part of the consumer contract and is never
  recomputed);
- the document's tree ids match the manifest entry.

Failures are the typed sealed hierarchy `DatasetLoadError`
(`AssetMissing`, `AssetUnreadable`, `InvalidJson`, `UnsupportedSchemaVersion`,
`ManifestUnavailable`, `ClassEntryMissing`, `GameMismatch`, `BuildMismatch`,
`ClassMismatch`, `ContentHashMismatch`, `TreeIdsMismatch`) returned inside
`DatasetOutcome`; program logic never parses exception messages. A missing
resource and a resource that exists but cannot be read are distinct failures,
mirrored by `AssetNotFoundException` / `AssetReadException` at the asset-source
boundary.

## Conformance strategy (M3A parsing, M3B execution)

M3A implements the full serialization contract for
`allocation-conformance.v1` and `allocation-build.v1`, and proves it by
loading the committed baseline: 53 scenarios / 177 steps, with every
referenced dataset identity and content hash resolving to the baseline
documents.

**M3A parsed only.** The M2 rules `EDGE-ACTIVE` and `EDGE-SUF` are still
High-confidence-but-unverified against the live Forever client, so M3A did not
freeze allocation semantics into Kotlin: loading and structural validation
only, with no fake runner, no expected-result shortcut and no ignored test.
M3B then implemented the documented reference semantics provisionally and
executes the suite (below).

### How M3B plugged in

M3B added the `TalentAllocationEngine` inside `shared` under
`io.github.alexeykravtsov95.talentforge.allocation` — a domain package beside
the `data.*` contract packages, consuming the existing `data.talentdata`,
`data.allocation` and `data.conformance` models without duplicating them:
`BuildState` reuses the wire `NodeAllocation`, and the conformance executor
consumes the M3A `ConformanceSuite` model directly.

`AllocationConformance` is the executor: it applies a scenario's mutations to
an isolated copy of the referenced document (immutable value types — the
canonical parsed instance is never modified), builds the engine with the
scenario's `pointLimit`, replays `initial`/`steps` through the real engine and
compares each step against the `Expectation` (`ok`, sorted `reasonCodes`, the
ordered structural `issues`, `spentPoints`, `remainingPoints`, `nodeRank`;
never messages). The acceptance test `AllocationConformanceReplayTest` (JVM)
executes the committed baseline — 53 scenarios / 177 steps — and must pass
exactly. Focused engine tests run in `commonTest` (synthetic structures, also
executed on the iOS simulator) and `jvmTest` (real committed Forever
structures).

The engine ports the Python M2 reference semantics one-to-one, including the
greedy purchase closure behind `validate` (VALID-1), the `canSpend` issue
collection order and the canonical issue ordering (code declaration order,
then `nodeId`, `entryId`, `relatedNodeIds`, `conditionId`, `cause`). Two
deliberate consumer-side differences, documented on the code:

- the engine constructor validates the allocation-relevant structural
  invariants of a document (duplicate tree/node/entry ids, nodes without
  entries, invalid max ranks, dangling/self prerequisites, unlock
  requirements counting unknown nodes, dangling visual edges, required
  prerequisite cycles) but not the Python pipeline's layout-grid, section,
  position and spell/name data-quality checks — those stay pipeline concerns;
- the Python canonical content-hash algorithm is not part of the consumer
  contract and is never recomputed in Kotlin: published hashes are verified
  against the manifest by `BundledTalentDatasetRepository`, and the
  conformance mutations keep the unmutated document's hash on their in-memory
  copy (the Python runner re-hashes because its full-document validator
  recomputes it).

**EDGE-ACTIVE and EDGE-SUF remain High — hypothesis.** Passing the 53/177
replay proves parity with the Python reference engine, not agreement with the
live client; the edge contract is not final until E1/E2 are client-observed
(docs/talent-allocation-semantics.md §13). No level-derived point limits are
calculated — a runtime budget (e.g. the level-20 `maxQuantity = 11`) is
caller-supplied `pointLimit` territory.

## Design source of truth

`docs/design/` holds the two authoritative PDFs and the precedence rule (see
`docs/design/README.md`): UX/UI spec pages 2–3 win on conflicts, otherwise the
B3 spec governs Talent Tree behaviour. The theme tokens cite their spec
section, and `DesignTokenTest` recomputes every colour constant from the spec
OKLCH values. Per-class accent colours beyond Warrior are not invented here —
the spec fixes only the shared `accentClass` token so far.

## Local build commands

```bash
# dataset drift check / re-sync
python3 scripts/sync_mobile_datasets.py --check
python3 scripts/sync_mobile_datasets.py

# tests (JVM)
cd mobile
./gradlew :shared:jvmTest               # contract + repository + allocation + conformance replay
./gradlew :shared:iosSimulatorArm64Test # common allocation tests on the iOS simulator
./gradlew :composeApp:desktopTest       # bundled baseline, drift, design tokens, tree UI model/viewport/interaction/inspector

# opt-in M4.1 screenshot evidence from the real screen (skipped without the property)
./gradlew :composeApp:desktopTest --tests "*TalentTreeScreenshotTest*" \
    -Dtalentforge.m41.screenshots=<output-dir>

# opt-in M4.2 inspector screenshot evidence (same mechanism)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM42ScreenshotTest*" \
    -Dtalentforge.m42.screenshots=<output-dir>

# Android
./gradlew :composeApp:assembleDebug    # -> composeApp/build/outputs/apk/debug/

# iOS
./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
```

JDK 17 and an Android SDK (`ANDROID_HOME` or `mobile/local.properties`) are
required; Xcode is required for the iosApp build. The Gradle wrapper needs no
global Gradle install. CI (`.github/workflows/mobile-ci.yml`) runs the drift
check, JVM/shared contract tests, desktop tests and the Android debug build on
Linux, and iOS compilation, framework linking and the iosApp simulator build
on macOS — no release signing anywhere.
