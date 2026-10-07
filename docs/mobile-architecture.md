# Mobile architecture (M3A foundation, M4.1–M4.3 tree screen, M5.1 local build library)

This document describes the Kotlin Multiplatform foundation introduced by M3A,
the M4.1–M4.3 production Talent Tree screen (read-only rendering since M4.1,
adaptive inspector since M4.2, interactive allocation since M4.3) and the M5.1
local build library with the app root (Home, New Build, All Builds, autosave):
module boundaries, dataset flow, strict JSON policy, conformance strategy,
persistence architecture and local build commands. It is the orientation map
for later milestones — none should require restructuring what is described
here.

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
  package (see "Conformance strategy" below), plus the pure
  `BuildState ↔ BuildFile` conversions (`BuildFileMapping.kt`): only
  allocation triples cross between the editing state and the portable
  `allocation-build.v1` payload — never selection, viewport, undo history or
  any other editing-session UI state;
- the M5.1 **local build library** (`data.builds`): the app-private,
  versioned persistence envelope and `LocalBuildRepository` (see
  "Local build library" below). Pure Kotlin, no platform types — the platform
  storage cell is injected at the app edge.

Targets: Android, iOS arm64, iOS simulator arm64, JVM (fast contract tests).

### `composeApp`

Compose Multiplatform application/UI host. Depends on `shared`. Contains:

- `App` — since M5.1 the **app root**: it takes the host-injected platform
  `BuildLibraryStorage` (plus injectable clock/id generator for tests), builds
  the repository + dataset collaborators once, and drives a small explicit
  destination back stack — `Home → NewBuild → pick class: the tree REPLACES
  New build (back → Home)`, `Home → Continue/Recent → tree (push, back →
  Home)`, `Home → AllBuilds → tree (push, back → AllBuilds)`. The stack is
  `rememberSaveable` with an explicit plain-value saver (navigation facts
  only — Home/NewBuild/AllBuilds/Editor(localId, origin); a corrupt entry
  fails closed to Home), so a configuration change restores the exact
  destination while the editing session restores through its own M4.3
  saveable form. No navigation framework for four local screens, no bottom
  navigation; the editor opens by opaque local build id, never by passing
  mutable UI state; navigation state is not the persistence model. Android's
  system back and the visible chevrons (`PlatformBackHandler` expect/actual)
  share one pop, and popping an editor flushes the autosave coordinator
  first;
- `ui/tree` — the **M4.1–M4.3 production Talent Tree screen** (read-only since
  M4.1, interactive since M4.3): the bundled
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
  - `TalentNodePresentation` — the **M4.2 allocation-state
    projection**: a pure mapping of `TalentAllocationEngine.canSpend` over an
    immutable `BuildState`. It
    never calls `spend`/`refund` (the M4.3 routing guard below scans
    production sources to enforce this). The engine's full ordered issue set is preserved per node
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
    large text) expanding into the B3 448 dp sheet — it *occupies*
    min(448 dp, available height − 96 dp), scrolls internally at large text
    sizes and pins its actions to the bottom; because that is the sheet's
    actual geometry, the expand focus covers exactly this height (never a
    mismatched estimate) and pans the minimum amount needed (B3 08);
    landscape 300 dp side panel
    laid out **beside** the canvas (never painted under it — the tree pans
    only if the panel would cover the node); tablet (840 dp+) persistent
    380 dp inspector with a minimal empty prompt (the spec freezes the
    behaviour, not the prompt wording — the line is documented
    implementation-safe). Since M4.3 the inspector also carries the explicit
    rank controls (see `TalentInspectorActions` below); `Show` remains the
    only action for a *locked* node, offered
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

  - `TalentBuildEditingState` — the **M4.3 build editing session**: a small,
    pure, fully unit-tested layer in `composeApp` (not `shared` — it is UI
    editing concern, not domain semantics) holding the current immutable
    `BuildState` plus undo/redo histories of exact immutable states. Every
    mutation goes through the engine and nowhere else: `spend`/`refund`
    delegate to `TalentAllocationEngine.spend`/`refund`, `reset` delegates to
    `engine.emptyState()`, `spendToMax` loops engine spends but commits as one
    atomic history entry. A successful mutation pushes the before-state onto
    the undo history and clears redo; a denied mutation (typed
    `AllocationIssue`s returned) changes nothing — no state, no history, no
    counter. Undo/redo move whole immutable states between the histories.
    Reset is one atomic history step (one undo restores the exact cleared
    build) and a documented no-op on an already-empty build. A desktop test
    (`AllocationRoutingEnforcementTest`) scans production sources: no
    `BuildState.withAllocation` calls from production code, and
    `engine.spend(`/`engine.refund(` appear only in this file — rank
    arithmetic and refund dependency rules are never reimplemented in the UI.
    The `rememberSaveable` form is a flat list of `IntArray`s parsed by a
    **total fail-closed deserializer**: any malformed payload (truncation,
    bad counts, trailing data, overflow-sized counts) returns `null` and the
    session restarts empty — a corrupt save can never throw, hang or restore
    a wrong build;
  - `TalentNodeActions` — the **action legality projection**: derives
    `canSpend`/`canRefund` (plus the engine's denial reasons) for the
    *selected* node from `canSpend`/`canRefund` queries — deliberately
    independent from the visual `TalentNodeState`, because presentation state
    and action legality answer different questions (a MAXED node still offers
    refund; an AVAILABLE node at 0 remaining points shows a disabled +).
    `disabledActionExplanation` turns the same typed issues into the
    steady-state state-line reason a disabled control needs (B3 04 state 07
    freezes "No talent points left · x/y spent"; a dependent-blocked refund
    reads "Needed by <real name>") — a disabled button can never be clicked
    into a denial, so the reason must not depend on one, and enabled controls
    never show a false reason. `blockedActionReason` keeps mapping issue
    codes to copy for the transient post-denial path — never guessed game
    semantics;
  - `TalentInspectorActions` + `TalentTreeHeaderBar` + `TalentBuildOverlays`
    — the M4.3 controls per B3: explicit `−`/`+` (compact inspector), the
    expanded `− + Rank Max` row (expanded sheet, landscape side panel, tablet
    persistent panel), the header bar with undo/redo and the overflow
    (`⋯`) opening the "Reset talents" sheet in the warning colour, and the
    reset notice ("Talents reset · N point(s) cleared" + focus-coloured
    *Undo*, auto-dismiss 4 s). The notice floats at the **top of the canvas
    region** — the spec's notice model (UX/UI 07 places snackbars "at the
    top"; 01 ADAPT 3 floats notices "over the top of the canvas"), inset
    from any side inspector by its real width — so it can never cover the
    compact bar, the expanded sheet or any action control in any layout, and
    it is valid only while Reset is still the latest successful history
    operation: any later successful mutation dismisses it (undo/redo already
    did), because its Undo promise is otherwise false. A successful spend
    plays the B3 purchase feedback (260 ms node glow + 180 ms rank-chip pop)
    driven by a monotonic token so recomposition can never replay it;

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
read-only allocation-state presentation** described above. System UI follows
one cross-platform rule: the hosts draw edge to edge (Android
`enableEdgeToEdge` with transparent light-icon bars; SwiftUI
`ignoresSafeArea`), the TalentForge background paints to the physical window
edges, and the screen insets its *content* exactly once via
`WindowInsets.safeDrawing` — no hardcoded bar heights, no double padding on
iOS. The canvas is a real viewport (`clipToBounds`): nothing it draws can
escape into the header, the inspector or a neighbouring panel. It remains strictly
**read-only**: the screen holds one immutable `engine.emptyState()` build, the
engine is only queried (`canSpend`/`validate`/`spentPoints`/
`remainingPoints`), `Show` is pure navigation, and no spend/refund/undo
controls exist anywhere. BuildState mutation arrives in M4.3.

M4.3 turns the same screen into the **interactive local build editor**. The
screen owns a `TalentBuildEditingState` (see `ui/tree` above) —
`rememberSaveable` with a plain-value `Saver` (allocation entries and history
as `IntArray`s; the engine is never serialized) — so editing survives
recomposition and portrait↔landscape with the exact same build *and*
histories. Node tap still **selects only** (never spends — a dedicated
regression test taps repeatedly and asserts an unchanged counter), `Show`
still navigates one prerequisite hop and never spends, and every mutation is
an explicit visible control. The presentation, tier meter, prerequisite
arrows and header counter all re-derive from the real engine state on every
change, so locked→available transitions are live.

M5.1 **genericizes the editor and persists it**. `TalentTreeUiState.Ready`
now carries the build's title line (name, or "Draft" while unnamed — ADAPT 1)
and its **class accent as an explicit input**; the header renders the
two-line title plus the back control; every mutation lands in one
`commitEditing` path that reports actual `BuildState` changes through a
single `onBuildStateChanged` observer (the autosave contract — selection,
pan, zoom, `Show` and denied actions never report). The class accent policy
(`classAccentFor`) gives Warrior its spec-frozen accent and every other class
the documented neutral fallback (`UnfrozenClassAccent`, the hueless
`textSecondary` grey — replaceable when the spec freezes real per-class
accents; no class colour is invented and the dataset stays colour-free). A
source-scan test proves nothing outside the policy reads the Warrior-only
token. `TalentTreeBuildScreen` is the generic editor host: it opens a locally
stored build by id, validates it fail-closed (`loadEditorSession`: record →
exact dataset identity → treeId → engine validation of the saved allocations;
typed `EditorFailure`s, **no silent migration, clamping or baseline
fallback**) and owns autosave (below). The self-loading Warrior production
entry is gone; the baseline Warrior loader remains as the tests' fixture.

- `ui/library` — the M5.1 loader/controller layer: Home and All-Builds card
  models (each record resolved against its real class document and the real
  engine — class display name, spent/budget; unresolved fields are simply
  absent, never faked), the New Build class list (tokens from the baseline
  manifest, display names from the nine real documents; a failed class
  document disables that class conservatively — no invented name, no
  substitution), draft creation (document → engine → `emptyState()` →
  `BuildFile` bound to the exact dataset identity → persisted, and only a
  persisted draft is "created"), the editor session loader, the relative-time
  wording, shared shell controls and the platform clock expect/actual;
- `ui/home`, `ui/newbuild`, `ui/builds` — the M5.1 shell screens
  (Home / New Build / the basic All Builds list; see the design sections
  above for their frozen behaviour).

### `iosApp`

Thin native iOS host. A SwiftUI `App`/`UIViewControllerRepresentable` pair that
presents `MainViewController()` from the `ComposeApp` framework — no separate
Swift business or domain layer, no CocoaPods. The framework is built by the
standard Compose Multiplatform integration: the Xcode target runs a
`Build KMP Framework` script phase invoking
`gradlew :composeApp:embedAndSignAppleFrameworkForXcode` and embeds the result.

## Local build library (M5.1)

The public portable build contract is **unchanged**: `allocation-build.v1`
(`BuildFile`) stays a pure allocation plan bound to a dataset identity
(game, clientBuild, classToken, treeId, allocations). Names, ids and
timestamps are **local application metadata** and live in a separate,
app-private, versioned envelope that is never presented as a TalentForge
interchange schema:

```json
{ "storageVersion": 1,
  "builds": [ { "localId": "…opaque…", "name": null,          // null = Draft
                "createdAt": 0, "updatedAt": 0, "lastOpenedAt": null,
                "build": { …the unchanged BuildFile payload… } } ] }
```

`LocalBuildLibraryCodec` (in `shared`) is deterministic (records serialized in
canonical `localId` order) and keeps the same fail-closed reading standard as
the public contracts: unknown fields within a storage version are rejected
(documented policy — schema evolution requires a `storageVersion` bump;
migration is deliberately absent in M5.1), quoted numeric literals are
rejected by the same canonical wire-form check, an unsupported
`storageVersion`, a wrong embedded build `schemaVersion`, duplicate/blank
local ids and malformed JSON are typed failures, and persisted blank names
normalize to `null` (Draft). `LocalBuildRepository` owns create-draft /
autosave / open-touch / list with deterministic ordering (last edited first:
`updatedAt` desc, then `createdAt` desc, then `localId`); every operation is
a full read–modify–write over the storage, so a failed write never replaces
the last persisted payload and a fresh instance (a relaunched app) observes
prior writes. Time and id generation are injected (`BuildClock`,
`LocalBuildIdGenerator`; default: platform clock + a random time-ordered
prefix — no UUID dependency).

**Platform storage stays at the edge.** `BuildLibraryStorage` is a one-string
cell (`read(): String?` / `write(payload)`); the hosts inject their
implementation into `App(...)`: Android `SharedPreferences` (synchronous
`commit()`), iOS `NSUserDefaults`, desktop `java.util.prefs` — no database, no
ORM, no DI framework, no file/storage library. A read/write failure is a
typed `BuildLibraryError`, surfaced as a recoverable error state with Retry —
never as "zero builds", which would invite an overwrite that destroys the
stored library.

**Autosave flow.** The editing layer's single `onBuildStateChanged` observer
hands the current `BuildState` to the editor host, which converts it into the
record's `BuildFile` and submits it to the app-level
`BuildAutosaveCoordinator` — a save pipeline owned ABOVE any single
destination, so leaving the editor can never cancel a pending or in-flight
save. Submissions are FIFO with monotonic generations and may coalesce to the
newest queued state; `repository.saveBuild` replaces the build, advances
`updatedAt`, and preserves `localId`/`createdAt`/`name`/`lastOpenedAt`.
Popping the editor first FLUSHES the coordinator: navigation completes only
after the newest submitted state is confirmed persisted; if the newest save
failed, navigation visibly stays put with the retryable error notice (the
in-memory build never rolls back, no duplicate record is created to recover,
and a later successful save clears the error). One pop is ever in flight —
repeated Back during a flush is a no-op, and the pop re-verifies the stack
after the suspended flush so Home can never be removed (the decoded saved
stack is likewise always non-empty and rooted at Home). A configuration change that
rebuilds the pipeline cannot lose a newer restored session either: the
editor re-submits a restored session that differs from the persisted build
through the same single change path. Opening a build touches `lastOpenedAt`
(a real write, also fail-closed).

**Build opening — no silent migration.** A saved build is never trusted
blindly: the record's exact dataset identity resolves through
`BundledTalentDatasetRepository` (an unavailable dataset never falls back to
the current baseline), the saved `treeId` must exist in the loaded document,
and the saved allocations must pass the real engine validation — no rank is
clamped, dropped or "repaired". Any mismatch is a typed unavailable/
incompatible editor state. A reopened build starts with empty undo/redo
histories by design; only the build is persisted, never the M4 UI session.

**Class-accent fallback policy.** Only Warrior has a spec-frozen accent; all
eight other bundled classes render with the documented neutral fallback
(`UnfrozenClassAccent`) until a committed source freezes real per-class
accents. The policy is one function; a regression test scans production
sources so nothing can read the Warrior-only token directly again.

**Deferred to M5.2** (deliberately absent here): Name/Rename, the Name build
flow, Duplicate, Delete + irreversible confirmation, and the Saved Builds
management polish. There is no dead overflow control anywhere in M5.1.

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
| talent visuals v1 (M5.V1) | `docs/schema/talent-visuals.v1.schema.json` | `TalentDataContract.decodeTalentVisualsDocument` |
| talent icons v1 (M5.V1) | `docs/schema/talent-icons.v1.schema.json` | `TalentDataContract.decodeTalentIconRegistry` |

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

## Talent visuals (M5.V1)

> **Verification status.** This section's Kotlin was written in an environment
> without access to the Gradle/Maven repositories or a macOS toolchain: it has
> **not been compiled, and none of its tests have been run**. Treat everything
> below as the intended design until `:shared:jvmTest`, `:composeApp:desktopTest`
> and the platform builds have been run on a developer machine or CI.

Real WoW talent icons replace the monogram as the normal node content. They are
build-pinned, bundled, and read only from local resources — there is no network
client anywhere in the app. Where the icons come from, the two sidecar contracts
and the ownership note for the artwork: [talent-visuals.md](talent-visuals.md).

**Bundle.** `scripts/sync_mobile_visuals.py` mirrors
`data/visuals/forever/1.60.1.70009/` into
`composeResources/files/visuals/forever/1.60.1.70009/`: nine
`talent-visuals.v1` sidecars, the `talent-icons.v1` registry `icons.json` and
`icons/<FileDataID>.png` (358 files, deduplicated by FileDataID across
classes). Drift is caught by the script's `--check` (CI) and the JVM
`VisualsDriftTest`, which also fail on a missing or extra file.

**`shared` — `data/visuals/`.** `TalentVisualsDocument` / `TalentIconRegistry`
are strict models with no Kotlin defaults (every property, `schemaVersion`
included, is required on the wire; the closed `imageFormat` / `sourceFormat`
values are enums). `BundledTalentVisualsRepository.visuals(document)` loads the
visuals **of an already loaded class document** and returns
`VisualsOutcome<TalentVisuals>`; it refuses, with a typed `VisualsLoadError`:

- a sidecar whose `game`, `clientBuild` or `classToken` differs from the
  document (`GameMismatch`, `BuildMismatch`, `ClassMismatch`);
- a sidecar resolved for another dataset revision — `talentDataContentHash`
  must equal the document's published `contentHash`
  (`DatasetRevisionMismatch`; the hashes are compared as published strings,
  never recomputed);
- duplicate `entryId`s, entries the document does not contain, document entries
  without a visual, and entries whose definition/spell contradict the document
  (`DuplicateEntry`, `UnknownEntry`, `MissingEntry`, `EntryIdentityMismatch`);
- non-positive ids/sizes (`NonPositiveValue`);
- a registry of another game/build, duplicate registry keys, a `file` that is
  not exactly `icons/<fileDataId>.png` (no other directory, URL or traversal is
  ever resolved), and an icon used by an entry but absent from the registry
  (`DuplicateIcon`, `InvalidIconFile`, `UnregisteredIcon`);
- and the asset/parsing failures (`AssetMissing`, `AssetUnreadable`,
  `InvalidJson`, `UnsupportedSchemaVersion`).

There is no cross-version substitution: a build without bundled visuals simply
has none.

**`composeApp` — `ui/assets/TalentIconAssets.kt`.** `BundledTalentIconSource`
reads an icon through the same `DatasetAssetSource` as the datasets and decodes
it with the Compose resources decoder. Each image is read and decoded at most
once per app run: outcomes — failures included — are cached by asset path,
which carries game and build. `TalentIconsLoader.load(document, tree)` combines
the repository and the source into a `TalentNodeIcons` (node id → decoded
`ImageBitmap`) plus typed diagnostics (`visualsError`, `failedNodes`). It runs
when an editor session opens (`loadEditorSession`, after every allocation gate
passed, off the UI thread), so composition and drawing only look up an already
decoded bitmap — no file I/O or decoding in a draw pass. A node gets an icon
only when it has exactly one entry; the dataset publishes no rule for which
entry represents a multi-entry node.

**Failure is visual, never functional.** Visuals that fail to load, a missing
image, an unreadable image and undecodable bytes (`TalentIconFailure`) each
leave the affected node(s) on the M4 monogram tile. They never fail the
session, the tree, or allocation. The monogram code is kept as that exceptional
fallback and as the default for callers without visuals
(`TalentNodeIcons.None`).

**Node rendering** (`TalentNodeVisual`, formerly `TalentNodeFallbackVisual`).
The icon fills the existing 48 dp rounded square; node centres, hit targets,
pan/zoom, connectors, the rank chip, the selection ring, the Show pulse and
the purchase feedback are untouched, and nothing depends on whether a node
shows an icon. The B3 state language is layered over/around the artwork at
render time — nothing is baked into an image file:

| state | icon | around it (unchanged unless noted) |
|---|---|---|
| available | full colour | solid outline, outline chip |
| partial | full colour | + class-accent inner frame; filled accent chip |
| maxed | full colour | + accent frame and light inner rim (double rim); light chip |
| tier / prerequisite locked, unavailable | desaturated, dimmed | dashed border; padlock / ↑ chip |
| no points | dimmed, colour kept | dimmed solid border and chip |
| selected | — | neutral white ring + 6 % scale on top of any state |

Two adaptations are implementation decisions, not spec text, and need a visual
review against B3: B3 01 "Fill = what I spent" cannot fill a body that shows
artwork, so the accent moves from the body fill to a frame on the same body;
and outline chips get an opaque plate over an icon so rank text and lock glyphs
never read against art. The dimming/frame constants are marked
implementation-safe in the source. State is still never carried by colour
alone: border style and chip treatment differ exactly as in M4.2.

**Inspector.** Every inspector variant shows the selected talent's icon in
front of its name (`[icon] Talent Name`, 36 dp in the compact bar, 40 dp in
the full-detail headers); with no icon the header is the M4 one. Home / New
Build / Saved Builds are unchanged — class marks stay monograms (they identify
classes, not talents).

**Descriptions are not rendered.** `descriptionTemplate` is raw client tooltip
markup (`$s1`, `${…}`, cross-spell references, conditionals, …) for 461 of the
469 bundled entries; an unevaluated template is never shown. The syntax
inventory for M5.V2 is `docs/research/forever-tooltip-template-inventory.md`.

**Tests added** (unrun, see the status note): `TalentVisualsContractTest`,
`TalentVisualsRepositoryTest` (`shared/jvmTest`); `TalentIconAssetsTest`,
`TalentTreeIconUiTest`, `VisualsDriftTest` and the opt-in, desktop-rendered
`TalentTreeM5V1ScreenshotTest` (`composeApp/desktopTest`).

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

# talent visuals (icons) drift check / re-sync — offline, repository files only
python3 scripts/sync_mobile_visuals.py --check
python3 scripts/sync_mobile_visuals.py

# tests (JVM)
cd mobile
./gradlew :shared:jvmTest               # contract + repository + allocation + conformance replay + local build library
./gradlew :shared:iosSimulatorArm64Test # common allocation + build-library tests on the iOS simulator
./gradlew :composeApp:desktopTest       # bundled baseline, drift, design tokens, tree UI, M5.1 build opening/restart/app flow/accent policy

# opt-in M4.1 screenshot evidence from the real screen (skipped without the property)
./gradlew :composeApp:desktopTest --tests "*TalentTreeScreenshotTest*" \
    -Dtalentforge.m41.screenshots=<output-dir>

# opt-in M4.2 inspector screenshot evidence (same mechanism)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM42ScreenshotTest*" \
    -Dtalentforge.m42.screenshots=<output-dir>

# opt-in M4.3 interactive-allocation screenshot evidence (same mechanism)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM43ScreenshotTest*" \
    -Dtalentforge.m43.screenshots=<output-dir>

# opt-in M5.1 app-flow screenshot evidence (same mechanism)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM51ScreenshotTest*" \
    -Dtalentforge.m51.screenshots=<output-dir>

# opt-in M5.V1 icon screenshot evidence (same mechanism; desktop-rendered, not device screenshots)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM5V1ScreenshotTest*" \
    -Dtalentforge.m5v1.screenshots=<output-dir>

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
