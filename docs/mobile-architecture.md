# Mobile architecture (M3A foundation)

This document describes the Kotlin Multiplatform foundation introduced by M3A:
module boundaries, dataset flow, strict JSON policy, conformance strategy and
local build commands. It is the orientation map for M3B (Kotlin allocation
engine) and M4 (production UI) — neither should require restructuring what is
described here.

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

- `App` — the **M3A foundation smoke host (not production UI)**: a single screen
  that boots the repository against the packaged Forever dataset and renders
  the baseline identity (game, build, class count, node count, contract
  version) or a readable error state;
- `ui/theme` — design tokens frozen by `TalentForge_UX_UI_Spec_v1.pdf`
  section G: colour (OKLCH-derived sRGB constants), spacing, radius, motion
  durations, and the `ClassAccent` type with the single spec-fixed Warrior
  accent;
- `ComposeResourceDatasetAssetSource` — the standard Compose Multiplatform
  resource mechanism (`composeResources/files/…`) as a `DatasetAssetSource`;
  it works on Android and iOS and only hands bytes to `shared`, where all
  JSON parsing happens. There are no per-platform parser implementations.

Targets: Android, iOS arm64, iOS simulator arm64, desktop JVM (smoke host +
packaged-resource tests).

Material 3 is deliberately absent in M3A: Compose Multiplatform 1.10+ publishes
its `material3` artifacts only in pre-release form, and this milestone accepts
no alpha/beta/RC dependencies. The production UI milestone will revisit the
component library choice.

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
./gradlew :composeApp:desktopTest       # bundled baseline, drift, design tokens

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
