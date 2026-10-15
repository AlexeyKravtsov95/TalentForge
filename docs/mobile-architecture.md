# Mobile architecture (M3A foundation, M4.1–M4.3 tree screen, M5.1 local build library, M5.2a build management, M5.UI.1 B+ shell, M5.UI.2 B+ class selection)

This document describes the Kotlin Multiplatform foundation introduced by M3A,
the M4.1–M4.3 production Talent Tree screen (read-only rendering since M4.1,
adaptive inspector since M4.2, interactive allocation since M4.3), the M5.1
local build library with the app root (Home, New Build, All Builds, autosave),
the M5.UI.1 B+ redesign of Home and All Builds with the shared class
artwork, and the M5.UI.2 B+ redesign of the New Build class selection:
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
  M4.1, interactive since M4.3): a bundled Forever class document (the
  reference-baseline Warrior in the M4-era test fixture) is loaded through
  `BundledTalentDatasetRepository` (no JSON in composables) into explicit
  Loading/Ready/Failed states, and rendered as a pannable, pinch-zoomable
  canvas driven entirely by the real dataset:

  - `TalentTreeUiModel` — a pure, geometry-only projection of the real
    `TalentTree` (node id/name/monogram/max-rank/section, world-canvas
    positions, prerequisite + visual-only edges, computed bounds). It contains
    no allocation semantics and duplicates no contract hierarchy. One uniform
    factor maps client units to dp (one `layout.xStep` = 80 dp — the 1A v2
    S1 pitch since M5.UX.1, 76 dp before), preserving the client's relative
    geometry without distortion. Fail-closed presentation preconditions return typed build
    errors instead of crashing or emitting sentinel geometry: no tree,
    multiple trees, no nodes, duplicate node ids, invalid layout step,
    dangling edge references;
  - `TalentTreeViewport` — the single world→screen transform (`dp·scale +
    offset`) with pure helpers for world↔screen, pan, focal-point zoom
    clamped to the 0.62–1.6× bounds (1A v2 S3, M5.UX.1; B3's 0.8–2.2×
    before), and the deterministic initial transform.
    `TalentTreeViewportState` holds it (saveable — no reset across
    recomposition/rotation for the whole-tree canvas). Hit testing runs in
    **screen space**: since M5.UX.1 the effective target is the 1A v2 S1
    `min(pitch − 2, max(48, size + 8))` (52 dp node, 80 dp pitch, both × the
    scale; 68 dp tablet floor kept) — at least 48 dp while zoomed out, yet
    always 2 dp short of the next grid position, so neighbouring targets
    never overlap at any zoom; node visuals are untouched by this;
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
    three presentations: portrait compact strip expanding into a sheet
    (since M5.UX.2a the 1A v2 docked strip and the content-sized sheet — see
    "M5.UX.2a adaptive inspector and header" below; M4.2 had the B3 64 dp
    card and a sheet that occupied min(448 dp, available − 96 dp));
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
  absent, never faked), the New Build class list (tokens from the product
  dataset's manifest, display names from the nine real documents; a failed class
  document disables that class conservatively — no invented name, no
  substitution; the header context names the data version the drafts are
  created on, "talent data 1.60.1", derived from the product manifest's exact
  client build 1.60.1.70374 — no "up to date" / "latest" claim, since the app
  has no runtime freshness mechanism; the repository-side check is
  `scripts/check_product_freshness.py`), draft creation on the product
  dataset (document → engine → `emptyState()` →
  `BuildFile` bound to the exact dataset identity → persisted, and only a
  persisted draft is "created"), the editor session loader, the relative-time
  wording, shared shell controls and the platform clock expect/actual;
- `ui/home`, `ui/newbuild`, `ui/builds` — the M5.1 shell screens
  (Home / New Build / All Builds; see the design sections above for their
  frozen behaviour). Since M5.2a, All Builds is also the ONE build-management
  surface: each row ends in a `⋯` button (its own 48 dp target — pressing it
  never opens the build) that opens a Rename / Duplicate / Delete sheet
  (`ui/builds/BuildManagementOverlays.kt`), with rename and delete each
  confirming in a dialog (delete states irreversibility and uses the danger
  colour). Operations run off the main thread, one at a time; every success
  reloads the list from the repository (count, order and empty state included),
  and failures keep their dialog — or show a retryable notice for duplicate —
  open, never half-completing. The modal surface and its scrim consume stray
  touches (nothing reaches the interface underneath, and touching the surface
  itself never dismisses — only the explicit actions do), and the system Back
  button closes the open modal first (as a cancel: no write) — a Back with no
  modal open navigates as usual, and a Back while an operation is in flight
  is consumed but does nothing.

- **M5.UX.1 section tabs and per-section cameras** (approved direction
  "1A v2 · Specialization Tabs"; portrait phones — landscape phones since
  M5.UX.2b.1, below; tablet keeps the whole-tree canvas):

  - `TalentTreeSections` — a pure projection of the real tree's
    `sections` onto the UI model, never a second game model: each
    `TalentTreeSectionUiModel` holds the section's own node values (same ids,
    names, ranks, world positions), only the connectors with both ends in the
    section, and bounds from its actual nodes; connectors between sections
    are reported (`crossSectionEdges`, none in the published data) and never
    drawn, while the engine keeps enforcing them on the one `BuildState`.
    Labels are the dataset's section name, else the approved specialization
    name resolved at presentation time from (class token, section index)
    — M5.REL.1, `TalentSpecializationNames`; only a pair outside that table
    reads the localized generic "Tree N" / "Ветка N". No spec name is ever
    guessed from order or node names at runtime. A missing/unknown `sectionIndex` or a
    `nodeIds` list that disagrees with the nodes is a typed
    `TalentTreeSectionsResult.Unsectioned` reason: the screen then shows the
    whole tree on one canvas without tabs — nothing hidden or re-assigned.
    `sectionSpentPoints` counts each tab's points with the allocation rules'
    own `AllocationRules.pointsSpent` (the tier-gate count); for any valid
    build the tabs add up to the header counter;
  - `TalentTreeSectionTabs` — one tab per section (first-talent icon, label,
    points; icons hidden under 360 dp or above 115 % font scale), equal
    widths while each keeps 72 dp, otherwise a scrolling row; one tap
    switches the section (closing the inspector and clearing the selection),
    a tap on the active tab is FIT, an undo/redo that changes another tab
    flashes it for 600 ms without switching. There is no swipe between tabs
    and no pan through empty space between trees;
  - `TalentTreeSectionCameras` — `sectionIndex → TalentTreeViewportState`
    (policy `SECTION`) plus the open tab, for ONE build: keyed and saved with
    the build's local id and tree id (`rememberSaveable`, plain values,
    fail-closed decoding), so another build never inherits its cameras. A
    section's first camera is `s = clamp(Wvis / 354, 0.62, 1.0)`, centred
    horizontally, top-aligned (tall sections scroll to their lower tiers);
    returning to a tab restores its exact pan/zoom; a canvas of another size
    (rotation) re-fits. Gestures go through `applyUserTransform`: clamped so
    the content never leaves the visible area by more than the 12 dp field
    (the inspector-covered bottom excluded), marked `userMoved`, and they
    interrupt any running camera animation. FIT appears only while the
    camera was moved away from its fit and animates back in 280 ms;
  - cross-section `Show` (1A v2 04): the target's tab opens with the origin
    tab's camera untouched, the target is selected and centred within its
    section, and a "Back to <origin> · Tree N" chip returns to the origin
    talent. Covered by a synthetic, test-only cross-section edge — the
    PDF's Omen of Clarity → Feral Charge link is illustrative and exists in
    no dataset.

  Tab switches, pan, zoom, FIT, selection and Show are view state only: the
  autosave observer (`onBuildStateChanged`) never sees them; undo/redo,
  reset and the point budget stay global to the one build.

- **M5.UX.2a adaptive inspector and header** (approved direction 1A v2,
  pages 02–03 and S1/S2/S3/S8; portrait phones — landscape phones get the
  B3 05 side panel in M5.UX.2b.1, below; tablet keeps its M4.2 panel):

  - compact strip (`TalentInspectorCompactBar`): docked to the bottom, a
    12 dp handle over one 64 dp row `[icon 44] name … rank / state
    [actions]` (76 dp; the safe area below is the screen root's). The handle
    or the name expands — the B3 chevron button is gone, which is what keeps
    a readable name column next to `− 48×48 + 56×48` at 320 dp. Actions by
    state come from the engine-backed `TalentNodeActions`: Available `+`,
    Partial `− +`, Maxed `− ✓`, no points `+` disabled (`−` if ranked),
    refund blocked `−` disabled, prerequisite lock an amber `↑ Show`, tier
    lock the real gate counter chip `13/15`. The state line is the existing
    `TalentInspectorContent` wording (lock line, else the state line with a
    disabled control's `disabledActionExplanation`), toned by
    `talentInspectorStateTone` (green Available, class accent for invested
    ranks, amber for every lock/denial — never colour alone).
    `talentInspectorCompactLayout` keeps the single row while the text
    column keeps ≥ 88 dp per font scale (320 dp at 130 % stays one row,
    1A v2 03) and switches to the two-line form at ≥ 175 % text (1A v2 S8):
    the name takes the full width on up to two lines with the rank under it,
    so a long word is never split, and the state line sits next to the
    actions; at that size the section tabs show only their number and points
    (`NUMBER_ONLY_FONT_SCALE`, 1A v2 S8 «N» и очки; the label stays the tab's
    accessibility description);
  - expanded sheet (`TalentInspectorExpandedSheet`): sized by its content
    between `min = 260 dp` and `max = min(66 % H, H − top − 104)`
    (`expandedSheetLimits`; H = editor height inside the safe area, H − top
    = the canvas region below header and tabs). Top bar 48 dp (`⌄ · handle
    · ×`), 18 dp padding, identity row, then the detail, which scrolls
    inside the sheet once taller than `max`; the actions (`− 56×52`,
    `+ Rank` flex × 52, `Max 68×52`, or a full-width Show) are pinned below
    the scroll region. Slack of the 260 dp floor sits above the actions.
    At ≥ 175 % text the rank moves under the name, `Max` grows past 68 dp
    instead of clipping and `+ Rank` shows only `+` (its accessibility label
    is unchanged);
  - camera integration: the strip and the sheet report their **measured**
    height from the layout pass (a layout modifier capturing the talent it
    shows, so a measurement is never attributed to another talent); that
    height is the camera's `coveredBottomDp` (clamp, FIT position, Show
    centring). `TalentInspectorRevealTracker` (pure) decides when a new
    measurement moves the camera: a new presentation reveals once (opening,
    expanding — B3 08 / 1A v2 S3 "при выделении и смене высоты листа"),
    collapsing never does, Show and the Back chip place their own target,
    growth within a presentation reveals again only while the user has not
    moved the camera. The move is `panToReveal` over 260 ms: scale
    untouched, no-op while the talent is visible. A reveal never changes the
    inspector's height, so measure → reveal cannot loop;
  - header (`TalentTreeHeaderBar`, 1A v2 S1): ≥ 52 dp (grows with the
    font), back/undo/redo/⋯ 48 × 48, name 15 sp / meta 12 sp / counter mono
    16 sp, the title column takes all free width (the M5.1 second weighted
    spacer that halved it is gone); the counter leads the meta line
    (`21 / 51 · Druid · Forever`) when `W < 360`, or the font is above
    115 % and `W < 400` (plus an implementation-safe `W ÷ fontScale < 300`
    guard) — `talentTreeHeaderLayout`.

  None of this touches the engine, `BuildState`, `allocation-build.v1`,
  storage or autosave: expand, collapse, reveal and the header layout are
  view state only.

- **PR #19 gesture arbitration** (found on a real Android device; rules in
  `TalentTreeGestureRules.kt`, all thresholds implementation-safe):

  - section swipe — inside the canvas's ONE gesture handler
    (`talentTreeGestures`), not a second detector. The model is chosen when
    the gesture passes the 7 dp tap slop (`TalentSectionSwipeModel`):
    - **FIT** — the camera is at its fit zoom and the section fits the width
      (every bundled class at 360 dp): a gesture more horizontal than
      vertical is locked as a section swipe. The camera does not move; the
      canvas follows the finger as a bounded rubber band (0.35×, ≤ 32 dp,
      drawn after the pointer-input node). The release switches
      (`talentSectionFitSwipeDirection`) after ≥ 56 dp, or a ≥ 32 dp flick
      still moving ≥ 600 dp/s the same way; the path must stay within
      |dy| ≤ 0.5·|dx|. A more vertical gesture scrolls a tall section with the
      sideways movement locked, so scrolling keeps the FIT model. A second
      finger ends the lock and the gesture pinches as usual;
    - **edge** — zoomed in or out, or a section wider than the canvas: one
      finger pans as always. The movement the content clamp refuses
      accumulates as overscroll (`TalentSectionOverscroll`; moving back
      unwinds it). `talentSectionSwipeDirection` switches when it is still
      ≥ 40 dp at the release (64 dp before the device check), judging the
      slope only on the movement since the edge was reached. FIT (or a
      re-tap of the tab) returns the FIT model;
    - never for a tap, a pinch, a diagonal/short/pulled-back gesture, or one
      that started in `WindowInsets.systemGestures`: Android's edge Back
      stays the OS's, and nothing calls `systemGestureExclusion`;
    - direction (`sectionSwipeDirectionOf`): the content follows the finger,
      like a platform pager. Finger left → the tree whose tab is on the right
      (next); finger right → previous. A right-to-left layout is mirrored, and
      there is no wrap-around (`neighbourSection`). "Swipe right to the next
      tree" is read as moving right along the tabs, matching the 1A v2 S4 entry
      shift (the new tree arrives from the chosen tab's side);
    - the switch is the tab tap path (`onSectionSelected`): selection cleared,
      every section keeps its own camera, no `BuildState` change, no autosave;
  - bottom inspector — `inspectorVerticalSwipe` on the whole 76 dp compact
    strip (swipe up expands) and on the sheet's header (top bar + identity
    row; swipe down collapses). It observes after the children, takes the
    pointer only after 8 dp of mostly vertical travel (consuming it cancels
    any button press under the finger, so a drag never spends, refunds,
    maxes or shows) and needs 32 dp at the release
    (`talentInspectorSwipe`). The description's scroll region is outside the
    header, so scrolling never collapses the sheet. Taps on the handle (now
    the strip's full width) and the name still expand;
  - system Back (1A v2 S6, `editorBackStep`) — the editor registers its own
    `PlatformBackHandler` after the app's: ⋯ sheet open → close it; sheet
    expanded → collapse; a selection → clear it; otherwise it is disabled and
    the app's navigation Back runs as before (autosave flush, re-entrancy
    guard). On Android this is activity-compose `BackHandler`, so the
    predictive Back gesture follows the same order. The desktop actual is now
    a real dispatcher with the same last-enabled-wins rule (`DesktopBackDispatcher`,
    Esc in the desktop window), which makes the order testable; iOS has no
    system Back.

- **PR #19 motion** (`TalentSectionPaging.kt`, all values implementation-safe).
  It replaces the 1A v2 S4 tab-change entry (180 ms fade + 24 dp shift of the
  new section only) at the brief's request:
  - one pager position (round 4, HorizontalPager-like). A single continuous
    `TalentSectionPager.position` in tab ordinals drives everything: section
    *i* is drawn at `(i − position) · width` (RTL mirrored), and the tab
    indicator sits at `position`. Both are read at draw/layout time.
    - drag: the finger moves it 1:1 from wherever it was, counted from where
      the canvas locked the swipe (`talentSectionHeldPosition`, with the
      rubber band past the first/last tab);
    - release: the canvas reports `(direction, releaseDragDp,
      releaseVelocity)`. The motion continues from the drawn position with the
      finger's velocity on HorizontalPager's snap spring (`StiffnessMediumLow`,
      no bounce: 95 % of a page by ≈ 240 ms, 99 % by ≈ 330 ms), one frame ahead
      so the speed has no gap, and clamped so it never passes its rest
      (`talentSectionReleaseTarget`: the next boundary in the swipe direction,
      within ¼ page of the motion's tab it counts as arrived, an unconfirmed
      touch rests on the active tab);
    - release velocity: Compose's `VelocityTracker` fed through
      `addPointerInputChange` with the down, every move (including the touch
      samples Android batches into one event between two frames) and the lift,
      so the estimate does not depend on the display rate and a finger that
      rests before the lift releases without speed. The "one frame ahead" lead
      is `FRAME_LEAD_NANOS`, one 60 Hz frame: at 90/120 Hz the first frame after
      a release advances by 1.5/2 frames' worth;
    - touch: a touch stops the motion exactly where it is drawn
      (`grab`/`heldFrom`), and a tap that only caught moving pages never selects;
    - section changes: tab, Show and Back chip call `pager.settle` from the
      drawn position, carrying the running velocity (passing the tab between, as
      `animateScrollToPage` does), in the same event that changes
      `cameras.selectedSection`;
    - one coroutine job, cancelled synchronously by the next motion, so two
      animations never fight. The system animator scale (`MotionDurationScale`)
      is honoured;
  - one interactive canvas. Pages are keyed by section. Round 6 (measured on
    the TECNO Spark 40, debug build): composing a page on the frame a swipe
    starts cost 240–360 ms of main-thread work (composition ≈ 140, first
    measure ≈ 105, first draw ≈ 45), and the end of every motion disposed the
    old page and re-diffed every node's modifiers (50–90 ms). So the other
    pages are composed ahead, at rest, nearest first
    (`talentSectionNextWarmPage`), and kept (`talentSectionComposedPages(…,
    warm)`): a motion only moves pages that already exist. Round 7: a page
    composed ahead grows by a few talent nodes per frame
    (`TalentTreeCanvas(composedNodeCount = …)`; `talentSectionWarmBatch`
    halves the batch after a frame over two 60 Hz vsyncs, doubles it after one
    under 20 ms, 1–8 nodes), never a whole page in one frame, so the editor
    answers a touch right after it opens; a touch or a motion pauses it for the
    pages off screen, and a page that comes on screen before it is complete
    keeps growing a few nodes per frame, also during the motion (completing it
    in one frame cost 224–240 ms in the middle of the first swipe). The node
    count is read only inside the page's node layer (`TalentTreeNodeLayer`), so a
    step recomposes that layer alone, not the screen or the other pages (the
    editor-opening traces measured ≈ 53 ms per step when the screen read it);
    each talent is its own skippable composable (`TalentTreeNode`), so a step
    does not rebuild the other talents' modifiers and semantics; and
    the page the editor opened on counts as composed, so the first switch never
    disposes it and composes it again (≈ 300 ms). Only the active
    page has pointer input and exposes talents to accessibility; the others
    are drawn only, with ONE `clearAndSetSemantics` on their node layer (the
    per-node modifiers are the same on every page, so switching the active
    page does not touch the nodes). The page offset is applied after the
    pointer input, so it never shifts gesture coordinates. The pages' touch
    observer only watches (Initial/Final passes) and never consumes. The
    neighbours' cameras are fitted ahead of time with `prepare` (an existing
    camera is never reset), and no camera, `BuildState` or autosave is touched
    by the motion;
  - tab indicator. `TalentTreeSectionTabs(indicatorPosition = …)` reads
    the pager position at layout time, so it travels with the visible share of
    the pages. The label emphasis follows the mostly shown
    tab, while the `selected` semantics stay the logical section;
  - inspector sheet. It slides up from the strip's top edge and back down
    (240 ms, Material `FastOutSlowInEasing`: it starts from rest, without the
    first-frame jump the earlier `cubic(.2, .8, .2, 1)` had) by `graphicsLayer`
    translation only. Its layout,
    and therefore the measured covered height and `TalentInspectorRevealTracker`,
    is the final geometry from the first measured frame, so the camera
    reveals the talent once and never per animation frame. A closing sheet
    is a picture: no semantics and no input, with the strip already back
    underneath;
  - reduced motion. `prefersReducedMotion()` (expect/actual: Android
    `ANIMATOR_DURATION_SCALE` = 0, iOS Reduce Motion, desktop none;
    `LocalReducedMotion` overrides it in tests) makes pages, the sheet and the
    compact strip's entry instant. Finger-follow stays (direct
    manipulation), and every action works the same.

- **M5.UX.2b.1 landscape phones** (B3 05 / 08 "Landscape behaviour", UX/UI
  spec 03 §5 "Landscape: 300 dp side panel"; tablet unchanged — its own
  milestone):

  - classification. `talentTreeWorkspaceLayoutClass` classifies the editor
    ONCE from its whole workspace (the canvas region = workspace − the
    nominal 52 dp header), so the header and the body always agree.
    `talentTreeLayoutClass` now requires a tablet to be tall as well as
    wide (`TABLET_MIN_HEIGHT_DP` = 480, implementation-safe: Material's
    compact-height bound; the sources freeze no value): most modern phones
    are 840+ dp wide in landscape (915 × 412, 932 × 430) and used to fall
    into the tablet layout. 840 × 600 and larger keep the tablet layout;
  - one paged layout for phones. Portrait and landscape share the section
    pages, the ONE pager position, the per-section cameras, the Back chip,
    FIT and the warm-up of off-screen pages unchanged (PR #19); only the
    chrome differs. The section navigation (active section, pager position,
    tab change, indicator) is computed once above the header, because the
    landscape header hosts the tabs; the canvas region below uses it;
  - tabs. `talentTreeLandscapeTabs` (pure) puts them in the header — B3's
    one 48 dp bar `back · title · tabs · undo · redo · x / 51 · ⋯`
    (`TalentTreeHeaderBar(tabs = …)`) — when every tab keeps its 72 dp
    minimum (scaled with the font until the number-only tabs) next to the
    controls, the counter and a 120 dp title; otherwise (568 × 320, a
    cutout-narrowed 640 dp phone, 130 % text on 640 dp) the portrait tab row
    stays below the header. Both use the same `TalentTreeSectionTabs`;
  - side panel. Selecting opens `TalentInspectorSidePanel` (300 dp, full
    detail, pinned actions) OVER the canvas at the layout end (left in RTL),
    below the header, so it never covers the tabs, undo / redo or the
    counter. The canvas therefore keeps its size — no section camera re-fits
    when the panel opens or closes — and "moves only if the panel would
    cover the selected node" (`sidePanelWouldCover`, then the B3 08 minimal
    `panToReveal` within the uncovered region). The camera excludes the
    covered side from its clamp (`coveredLeftDp` / `coveredRightDp`, like
    `coveredBottomDp` for the portrait strip), and a focus request carries
    the visible region's origin (`visibleOrigin`) for a left panel. The
    panel consumes its touches, so nothing reaches the canvas below. Its
    actions are now the expanded sheet's 1A v2 row (`+ Rank` flexes, `Max`
    grows with large text; the M5.UX.2a note deferred this to 2b) and at
    ≥ 175 % text the decorative icon gives its width to the name, so a word
    is never split. The Back chip and FIT sit over the visible canvas, left
    of the panel; the reset notice keeps its existing inset;
  - Show / Back. Cross-section Show switches the tab exactly as in portrait
    and keeps the panel open on the prerequisite; the Back chip returns to
    the origin and its untouched camera. System Back clears the landscape
    selection in one step (only the portrait sheet expands, so an expansion
    carried over from portrait is ignored there, not a hidden extra step);
  - rotation. Section, selection, Show guide, Back chip, cameras and the
    editing session are the same saved state in both orientations: the open
    section and the selection survive, the open section's camera re-fits to
    the new canvas (1A v2 S5, `SECTION` policy) and the neighbours are
    prepared for it. Nothing about an orientation change reaches
    `onBuildStateChanged`; on Android (activity recreation) the restored
    session equals the persisted build, so the M5.1 restore safety net stays
    silent;
  - talent layer extent (found on a TECNO KM5n, Android 15). Android
    accessibility skips every child of a node whose own transformed bounds
    are off screen. The node layer used to be canvas-sized at world 0,0, so
    for a section far from the origin (Tree 2 in portrait, Tree 3 in
    landscape) the camera moved the layer itself off screen and no talent
    of the open section reached accessibility, although all were drawn and
    tappable. The layer now spans its talents' world extent from world 0,0
    (`talentNodeLayerExtent`: the furthest node centre plus the container
    half side), top-start, so the viewport mapping, the per-node modifiers
    and the inactive pages' single `clearAndSetSemantics` are unchanged.
    Desktop semantics never showed this; `scripts/android_section_check.py`
    checks it on a device.

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

**Build management (M5.2a).** The repository also owns
`renameBuild` / `duplicateBuild` / `deleteBuild`, each the same full
read–modify–write with the same fail-closed guarantees:

- `renameBuild` trims the requested name; blank or longer than
  `MAX_BUILD_NAME_LENGTH` (60, the one constant the rename dialog enforces)
  is a typed `BuildLibraryError.InvalidBuildName` that never touches storage.
  The rename preserves `localId`, the `BuildFile`, `createdAt`,
  `lastOpenedAt` **and `updatedAt`** — renaming is metadata, not a talent
  change, so it must not reorder the "last edited first" list — and renaming
  to the current name writes nothing.
- `duplicateBuild` creates an independent record: a fresh id under the same
  bounded collision policy as create-draft, the source's exact `BuildFile`
  payload, fresh `createdAt`/`updatedAt`, `lastOpenedAt = null`, and the
  initial name derived from the source's CURRENT name with ` (copy)`
  appended (`Draft (copy)` for a Draft). A duplicate of a duplicate
  therefore appends once per copy (`Build (copy)` → `Build (copy) (copy)`) —
  the documented policy — always shortened to the 60-character limit while
  keeping the suffix. Duplicate names are allowed — identity is the
  `localId`, never the name — and the source record is untouched.
- `deleteBuild` removes exactly one record; a missing id is
  `UnknownRecord`, and a failed read or write leaves the stored payload
  byte-for-byte intact (a failed delete never looks successful).

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
`BundledTalentDatasetRepository` (a build opens on its own bundled build, and an
unavailable dataset never falls back to a bundled one — see
[Bundled datasets](#bundled-datasets-product-and-reference-m5data)), the saved
`treeId` must exist in the loaded document,
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

## B+ shell redesign (M5.UI.1)

> **Verification.** Run locally on macOS 26.3.1 / Xcode 26.4.1 on this branch:
> `:shared:jvmTest` (244 tests), `:composeApp:desktopTest` (493, of which the
> opt-in screenshot tests are skipped), `:composeApp:assembleDebug`, both iOS
> debug framework links and the `iosApp` simulator `xcodebuild` — all green.
> Desktop-rendered screenshots of every state named below are in
> `docs/screenshots/m5ui1/`.

Home and All Builds were restyled to the approved B+ "Modern Fantasy Utility"
design. This is a visual milestone: navigation, loaders, the repository and
the editor are untouched, and the M5.2a build-management behaviour (rename /
duplicate / delete, operation-in-flight guards, modal Back handling) is
preserved as delivered.

**Class artwork (shared component).** The nine approved TalentForge class
paintings are bundled as Compose Multiplatform drawables
(`composeResources/drawable/class_*.png`) at their native 118×118 px,
imported byte-exact from the supplied masters — no upscaling, no re-encode,
single density bucket (512 px re-renders will drop in under the same names
later; no code change will be needed). `ui/library/ClassArtwork.kt` owns the
one shared `ClassToken → drawable` lookup (token string = filename suffix)
and the `ClassArtworkMark` tile, used by Home and All Builds and reserved for
the next milestone's Class Selection screen. The tile renders the art
unmodified — no tint, no colour filter, the painting supplies its own colour —
cropped with high-quality filtering into the slot's rounded rect over the
spec's ground, with the spec's 1 dp inner hairline drawn on top. The image is
decorative (no content description); the accessible class name comes from the
surrounding text. An unknown class token falls back to the existing
`ClassMonogramMark` at the same slot size (its new `size` parameter); since
M5.UI.2 the monogram is only the picker's unknown-token fallback (below).
There is no network fetch and no Blizzard-hosted URL
anywhere: the artwork is a bundled resource, full stop. Slots frozen by the
supplied asset spec: Continue card 44 dp / 9 dp radius, Recent and All-Builds
rows 36 dp / 8 dp radius; the class-picker slots (56/46 dp, 10 dp) landed
with M5.UI.2 (`ClassArtworkSlot.Picker` / `PickerCompact`).

**Intentional token additions.** Three constants were added to
`TalentForgeColors`, documented in source as NOT UX/UI-spec section G tokens:
`primaryAction` (oklch(0.83 0.12 68) — the B+ primary-action amber, same value
the asset spec uses for its accent spinner; hue 68 stays distinct from
`warning`'s 75 so warning semantics remain unambiguous), `classArtworkHairline`
(`#3E424A`) and `classArtworkGround` (`#16181C`). All three are pinned by
`DesignTokenTest`. `PrimaryActionButton` now uses the amber (its M5.1
"neutral light" reading is retired with the design it belonged to); the
editor's token set is deliberately untouched.

**Home (B+).** The empty state is typography only: the brand header, the
`No builds yet` heading (aligned in the upper-middle of the usable content,
≈16 % of the screen height below the header — PR #22 review), one workflow
sentence ("Pick a class and spend points on its talent tree…" — the class is
the actual New Build choice; no misleading version wording), the real
bundled data version line — and a single PRIMARY pinned `New build` action;
no illustration, no decorative tree fragment, no fake builds. The populated
state keeps the M5.1 hierarchy (Continue → Recent 3/4 → All builds · N) in
B+ clothing: the Continue card carries the 44 dp artwork tile, title,
`class · version · recency` metadata, the engine-computed `spent / budget`
beside a simple 4 dp progress bar (amber fill on the interactive track), and
the amber `Continue editing` action; RECENT rows carry the 36 dp tile. All
Builds is reachable at EVERY library size — with exactly one build (no
RECENT section) the `All builds 1` link renders as its own row after the
Continue card, a fix for the M5.1 behaviour where the link existed only
inside the RECENT header. The pinned `New build` becomes a neutral
secondary once builds exist. A failed library load stays the recoverable
Retry state — never a faked empty Home.

**Display-only build names.** `BuildCardModel.title` now shows the descriptive
`<Class> build` fallback (`Mage build`) for a persisted `name == null`, using
the real class display name (or the capitalized token when the class document
failed to load). The rule is display-only and consistent across Home, All
Builds and the management overlays: nothing is written back, the rename
dialog still opens empty for an unnamed build, and a user-provided name — an
explicitly entered `Draft` included — always wins. The M5.2a copy naming
(`Draft (copy)`, persisted by the repository when duplicating an unnamed
build) is unchanged repository semantics.

**All Builds (B+).** Same list, count, order and management as M5.2a; the row
mark is the 36 dp artwork tile and the empty-state action label matches
Home's `New build`. The row is RESPONSIVE (PR #22 review): its fixed-width
elements (artwork tile, mono points column, 48 dp `⋯` button) would crowd a
long build name into unreadability at large font scales, so the row measures
the widest points label at the current font scale and, when the title column
would fall below ~112 dp, switches to the compact arrangement with the
review's exact priority — build name, then the points as their OWN
single-line element on the metadata row (natural width, never ellipsized,
never wrapped), then `class · version · recency` taking the remainder and
ellipsizing where space does not permit. The artwork, the 48 dp actions
target and the click-to-open row are identical in both arrangements.
`AllBuildsRowLayoutUiTest` pins VISIBILITY, not just semantics: the points
element's laid-out width must cover the label's full intrinsic width
(measured with the production style at the exact font scale), it must have
nonzero bounds and stay inside the screen — an ellipsized/truncated element
would report a narrower box.

**Tests added/updated:** `HomeRedesignUiTest`, `ClassArtworkTest`,
`AllBuildsRowLayoutUiTest` (the responsive row of the PR #22 review)
(`composeApp/desktopTest`), the display-name cases in `BuildCardMetadataTest`,
and the M5.1/M5.2a suites' expectations (button label, empty-state heading,
fallback titles). Opt-in screenshot evidence:
`M5Ui1ScreenshotTest` (`-Dtalentforge.m5ui1.screenshots=<dir>`).

> **PR #22 review verification.** `:shared:jvmTest` (244 tests),
> `:composeApp:desktopTest` (497, of which the opt-in screenshot tests are
> skipped), `:composeApp:assembleDebug`, both iOS debug framework links and
> the `iosApp` simulator `xcodebuild` — all green. Review evidence in
> `docs/screenshots/m5ui1/`: the All Builds row at 200 % font scale on
> 360×800 and 320×568 (long name, two-digit points as a separate, fully
> visible element), and the realigned empty state at 320×568 / 130 % / 200 %.

## B+ class selection (M5.UI.2)

> **Verification.** Run locally on macOS 26.3.1 / Xcode 26.4.1 on this branch:
> `:shared:jvmTest` (244 tests), `:composeApp:desktopTest` (514, of which the
> 28 opt-in screenshot/recording tests are skipped), `:composeApp:assembleDebug`,
> both iOS debug framework links and the `iosApp` simulator `xcodebuild` — all
> green. Desktop-rendered screenshots of every state named below are in
> `docs/screenshots/m5ui2/`. An additional Android smoke ran the whole flow on
> an emulator through the isolated `.devcheck` application (installed next to,
> never over, the real app, and uninstalled afterwards; evidence in
> `.cache/android-m5ui2-check/`, not committed).

The M5.1 New Build picker was restyled into the same B+ system. This is again
a visual milestone: the loader, the draft-creation pipeline and the
`NewBuild → Editor` navigation replacement are untouched — ONE tap on a class
still creates and persists the draft and opens the editor only after
persistence succeeded, with no confirmation step anywhere.

**Header.** A 44 dp back row (`BackChevronButton` — "go back"), the
`Choose a class` component title and the real data context
`Forever · talent data 1.60.1` (`NewBuildChoices.dataVersion`, derived from
the product manifest's exact client build; the build itself stays pinned by
the read-path tests). The interactive version selector is gone: the app has
no second game to switch to, so a control implying one would be a lie.
Loading shows `Loading classes…`; a manifest failure swaps the grid for the
recoverable `Couldn't load classes` state (danger title, the local-data
explanation, Retry — Back keeps working). A failed single class document
never reaches that state; it disables that one class (below).

**Grid and artwork.** The nine classes render in the manifest's own order
(the M5.1 monogram grid is gone): one `ClassCard` per class on the restrained
elevated surface (1 dp default border, no gradient, no glow, no ornament)
with the `ClassArtworkMark` tile — `ClassArtworkSlot.Picker` 56 dp / 10 dp
radius, or `PickerCompact` 46 dp / 10 dp when the picker is narrow (the same
two slots the asset spec froze; `ClassArtworkTest` pins them) — the art
unmodified over the spec's ground, and the class name (bodySmall, up to two
lines) under it. The column policy is measured, never hardcoded:
`classPickerLayout(gridWidth, widestLabel)` computes the card content width
per column count and picks 3 columns only if the widest label (measured with
the production style at the CURRENT font scale via `rememberTextMeasurer`)
fits one line, else 2; the standard tile keeps `Picker` only with ≥ 12 dp
clearance each side, else `PickerCompact`. Result: 393/360 dp at 100 % and
130 %, and 320 dp at 100 %, are 3 columns (320 compact); 200 % font scale is
2 columns; the grid scrolls vertically whenever it must, so all nine classes
are always reachable.

**Creating state.** The tapped card is marked creating IMMEDIATELY (the
artwork is replaced in place by the compact 24 dp amber spinner — the
`primaryAction` accent; a static, frame-zero variant composes under reduced
motion so no infinite animation runs), the other eight cards dim and lose
their click actions, and both Back paths (header chevron and the nested
`PlatformBackHandler(enabled = creating)`) are held until the repository call
settles — a draft can never be orphaned mid-creation. A second tap during
creation is impossible by construction (the creating guard). On failure the
screen stays, the cards restore, and the typed error becomes a plain-language
notice (dataset / engine / library each have their own sentence); retry is a
normal tap. There is no artificial delay anywhere.

**Unavailable class.** Availability is exactly `NewBuildClassChoice.available`
(never a hardcoded list): the card stays visible but disabled — the artwork
falls back to the `ClassMonogramMark` (dimmed, still recognizable as the
class's own tile), the label is the honest token (`SHAMAN` when the display
name failed to load — no invented name), `Role.Button` with the Disabled
semantics property, and a tap does nothing. One failed class document never
affects the other eight.

**Accessibility.** Each card is ONE merged semantics node: the class name
text, `Role.Button`, the click label `create a <class> build`, the Disabled
property when unavailable, and — on the creating card — a `Creating build`
content description on the spinner overlay. The artwork itself stays
decorative (no duplicated announcements). Focus order follows the grid rows.

**Tests added/updated:** `NewBuildClassSelectionUiTest` (desktop; 15 tests —
manifest order by real laid-out positions, the responsive matrix at
360/320 dp and 130 %/200 % incl. scroll-to-reach, no horizontal overflow,
the pure `classPickerLayout` policy, one-tap persist → editor, Back → Home,
repeated taps = exactly one draft, the creating card's progress + dimmed
others + held Back, the unavailable class (disabled semantics, no click,
others unaffected), manifest failure + Retry recovery, persistence failure +
retry success), the picker-slot pins in `ClassArtworkTest`, the
`dataVersion` rename in `DatasetVersionCompatibilityTest`, and the M5.1 /
M5.UI.1 suites' navigation expectations (they now await `Choose a class`).
Opt-in screenshot evidence: `M5Ui2ScreenshotTest`
(`-Dtalentforge.m5ui2.screenshots=<dir>`) — the responsive matrix
(393×852, 360×800, 320×568, 130 %, 200 %), the unavailable class, the
loading failure, creation in flight and the creation failure.

## Localized specialization names (M5.REL.1)

Presentation-only milestone: the section tabs (portrait row, landscape
header row, the Back chip and the accessibility descriptions) show real
specialization names instead of the generic "Tree N".

- `ui/lang/AppLanguage` — the display-language choice: Russian only when the
  device's primary language is Russian (`ru`, `ru-RU`, `ru-KZ`, …; read once
  from `LocaleList` on Android, `NSLocale.preferredLanguages` on iOS, the JVM
  default locale on desktop), English for every other primary language. No
  in-app selector; `LocalAppLanguage` overrides it in tests and previews.
  M5.REL.2a widened the same rule to the shell screens' copy (see
  "Localized shell screens (M5.REL.2a)");
- `TalentSpecializationNames` — the ONE approved mapping
  (class token × `TalentSection.index` → English/Russian name), verified in
  this repository against every section's node membership on every bundled
  client build (each section holds exactly its spec's talents — Warrior 0 =
  Mortal Strike/Impale/… = Arms, Paladin 0 = Holy Shock/… = Holy, Druid 1 =
  Feral Charge/… = Feral Combat, …). `talentSectionDisplayLabel` is the
  single resolution chain every editor surface reads: the dataset's own
  published `TalentSection.name` still wins, the approved name follows, and
  a pair the table does not name falls back to the localized generic
  "Tree N" / "Ветка N" — never a guessed specialization;
- wiring: `TalentTreeUiState.Ready` carries the build's `classToken`
  (presentation input only), the screen reads `appLanguage()` once, and
  `talentSectionTabs` resolves the labels. Identity is untouched — section
  indices, node ids, the pager, cameras, the one `BuildState` and saved
  builds never see a localized label; at 200 % font scale a tab keeps its
  position number while its full resolved name stays the accessibility
  description.

**Tests added:** `TalentSpecializationNamesTest` (all 27 mappings in both
languages, unknown class/section → the localized generic fallback, the
dataset-name-wins chain, the `ru`/`ru-RU`/`ru-KZ` vs `en`/`de`/… language
rule) and `TalentSpecializationNamesUiTest` (the real screen: EN default
and RU tabs, the unresolvable-class fallback on screen, a language switch
changing ONLY labels — selection, camera, build, autosave observer and
section ids untouched, 200 % font scale numbers + full a11y names in both
languages, 320 dp long labels, rotation keeping the resolved label and the
open section). The existing tree suites keep their behaviour assertions
with the resolved labels (Warrior: Arms/Fury/Protection). The physical
device check (`scripts/android_section_check.py`) reads the device's
primary locale and drives the same scenarios with the matching labels
(`Arms`/`Fury`/`Protection` or `Оружие`/`Неистовство`/`Защита`), still
asserting each tab against its dataset section index. Opt-in screenshot
evidence: `TalentSpecializationNamesScreenshotTest`
(`-Dtalentforge.m5rel1.screenshots=<dir>`) — Paladin and Mage in EN and RU,
landscape navigation, 320 dp, 200 % font scale.

## Localized shell screens (M5.REL.2a)

Home, All Builds (with its row sheet and the rename/delete dialogs) and
Class Selection — and the components they share — present every user-facing
string in English or Russian. The talent editor's own copy is M5.REL.2b;
game content (talent names and descriptions) is M5.REL.3.

- **Resources.** `composeResources/values/strings.xml` is the English
  default and fallback, `values-ru/strings.xml` the Russian set with the
  same keys; screens read them through the generated typed accessors
  (`Res.string.*`, `Res.plurals.*`) with `stringResource` /
  `pluralStringResource`. Dynamic text uses positional arguments
  (`%1$s`, `%1$d`); the relative build time's day and week counts are
  plurals (`3 дня назад`, `5 дней назад`, `21 неделю назад`). In Russian a
  count stays on the line of its noun (`Все билды\u00A04`). Brand names
  (TalentForge, Forever) are not resources and are never translated.
- **One rule, one input.** Compose resources select `values-ru` exactly when
  `Locale.current.language` is `ru`, and Compose's `Locale.current` reads the
  same platform value `platformPrimaryLanguageTag()` does — Android
  `LocaleList.getDefault()[0]`, iOS `NSLocale.preferredLanguages[0]`, desktop
  `Locale.getDefault()`. The resource choice therefore equals
  `appLanguageOf(...)` on every platform: Russian only for a Russian PRIMARY
  language (`ru`, `ru-RU`, `ru-KZ`, …), English for every other primary
  language — `[en-US, ru-RU]` is English — and for an undeterminable one
  (`primaryLanguageTagOf` takes the first preferred entry). There is still no
  in-app selector. iOS declares `CFBundleLocalizations` `en` + `ru`, so the
  app's system-provided UI (e.g. the text-field edit menu) can also be
  Russian on a Russian device (not yet verified on a device).
- **Overrides.** Compose Resources 1.10.3 has no public API to override the
  resource locale (`LocalComposeEnvironment` and the `ResourceEnvironment`
  constructor are internal), so `LocalAppLanguage` cannot switch resource
  copy on its own — and plural rules follow the resource locale too. A test
  switches the platform locale (desktop: `AppLanguageRule`, which pins and
  restores `Locale.getDefault()`) and the override together; an override the
  resource locale contradicts fails in `appLanguage()` instead of rendering a
  mixed-language UI. Every desktop UI test that renders localized copy (or
  uses `LocalAppLanguage`) pins its language explicitly, so the suite does
  not depend on the machine's locale.
- **Class names and titles.** The nine class names are localized at the UI
  level by the stable `ClassToken` (`localizedClassName`); the English
  resources equal the datasets' own names. A class whose document did not
  load keeps its honest token, an unknown token keeps the dataset name. An
  unnamed build's display title is the localized fallback
  (`Mage build` / `Билд мага`, `buildCardTitle`) — derived, never persisted;
  a user-given name is always shown verbatim. Datasets, saved builds and
  their identity are untouched by the language.
- **Responsive guarantees.** No text breaks mid-word: the dialogs' action row
  wraps whole buttons onto the next line (end-aligned) when a translation at
  a large font scale cannot hold them on one line, and the class grid drops
  to one column when the widest single word of a class name no longer fits a
  two-column card (Russian `Чернокнижник` at 200 %); at the reference sizes
  both are unchanged. Russian copy is kept short where the approved layout is
  narrow (`Продолжить`, `Новое название`).

**Tests added:** `AppLanguageResourceConsistencyTest` (the `ru`/`ru-RU`/
`ru-KZ` vs `en`/`de`/`uk`/`kk`/… rule, English-first-Russian-second →
English, the resource locale and `appLanguage()` agreeing for 15 platform
locales, the contradicting-override failure) and `ShellLocalizationUiTest`
(the real app in EN and RU: Home empty / populated / error + Retry, All
Builds list, sheet, rename and delete dialogs, duplicate and failure
notices, empty state, Class Selection grid, load and creation errors and the
creating mark; every accessibility label in both languages; no English shell
copy on a Russian screen; the nine class names and unnamed titles; Russian
plural forms for 1, 2, 5, 11, 21; the relative-time buckets; user names
verbatim; a language switch leaving the stored library byte-for-byte
unchanged with no writes; and the 320 / 393 dp × 100 / 200 % matrix where no
text breaks mid-word, overflows the space its parent gives it or leaves
the window — measured against the parent's constraints, not the text's own
box, because Skia's intrinsic width of a single word on macOS fonts can be a
few px under its laid-out glyphs). Opt-in
screenshot evidence: `M5Rel2aScreenshotTest`
(`-Dtalentforge.m5rel2a.screenshots=<dir>`), committed under
`docs/screenshots/m5-rel-2a/`.

**Home loading state is read in Home's own scope.** `HomeScreen` reads its
`LibraryData` once, outside `BoxWithConstraints`, and passes that value into
the subcomposition. In the desktop UI-test harness the effect dispatcher is
unconfined, so the load result is written from a `Dispatchers.Default`
worker; a change observed only inside the subcomposition was never applied
and Home stayed on "Loading your builds…" until the `waitUntil` timeout
(`M51AppFlowUiTest.onePersistedDraftAppearsOnHomeAsContinue` failed on every
Mac run, also on main). On devices the effect resumes on the UI thread, so
behaviour there is unchanged.

**Known limitations.** A duplicate's initial name is generated and
persisted by the shared repository in English (`Draft (copy)`,
`<name> (copy)`); it is stored data shown verbatim, so it stays English in a
Russian UI until the copy-name policy itself is localized (a persistence
change outside this milestone). At 200 % font scale All Builds still
ellipsizes long titles by design; unnamed Russian titles share the `Билд`
prefix, so the class there is carried by the artwork and the meta line.

## Localized talent editor (M5.REL.2b)

The talent editor — header, section tabs and the Back chip, canvas
accessibility, the compact / expanded / side / tablet inspector, the
description labels, the build-actions sheet and reset notice, the loading,
failure and autosave panes — presents every application-owned string in
English or Russian under the M5.REL.2a rule (resources in
`values{,-ru}/strings.xml`, resource locale = platform primary language, no
in-app selector). Game content stays as the dataset publishes it: talent
names and tooltip text are English in both languages until M5.REL.3 (since
M5.REL.3b.2 the client's own Russian names and verified descriptions in Russian —
see "Russian talent text in the editor").

- **Descriptors, not pre-formatted English.** The pure projections no longer
  produce English sentences. `buildTalentInspectorContent`,
  `blockedActionReason` and `disabledActionExplanation` return
  `InspectorText` descriptors (`TalentEditorText.kt`): the message identity
  (`Available`, `RequiresPrerequisite`, `SpendMorePoints`, …), its numbers
  (`NoPointsLeft(51, 51)`, `SpendMorePoints(deficit = 5)`), the node ids it
  names and — for every blocked-action reason — the engine's typed
  `AllocationIssueCode` (`NeededBy(DEPENDENT_NODE_WOULD_BREAK, …)`). Names are
  `TalentName(nodeId, published)`: the dataset's non-blank name verbatim, or
  `null` → the localized `Talent <id>` / `Талант <id>` fallback.
  `MissingValuesNote` replaces the description's English note strings. The
  same allocation state yields the same descriptors in every language; text
  is resolved only at the composable boundary (`InspectorText.text()`,
  `TalentName.text()`), with counts through plural resources and names
  through positional arguments, so Russian word order and plural forms
  (`Вложите ещё 5 очков в эту ветку`, `Таланты сброшены · снято 21 очко`,
  `потрачено 1 очко`) belong to the resource. Nothing localized reaches the
  engine, `BuildState`, the history or the build library.
- **Canvas.** The nodes' spoken state (`2/5, частично изучен`) and the
  select / clear-selection actions are resolved once per node layer
  (`TalentNodeAccessibilityCopy`) and handed to every node, so a node stays a
  skippable scope (PR #19) and no node resolves resources itself.
- **Header.** An unnamed build shows the localized `Draft` / `Черновик`,
  resolved by `TalentTreeBuildScreen` at display time — `record.name` stays
  `null` in storage; a user-given name is shown verbatim in both languages.
  The meta line uses `localizedClassName` (`Воин · Forever`).
- **Failures.** `EditorFailure.describe()`, `BuildLibraryError.describeLibraryError()`
  and `TalentTreeUiFailure.describe()` are composable and localized. Embedded
  technical details stay as reported — the shared dataset diagnostic
  (`DatasetLoadError.describe()`), the allocation engine's reason and the
  validation reason codes are diagnostics, not product copy.
- **Layout.** One minimal rule, inert where the text fits
  (`TextBesideActions`): the compact strip's two-line reason row and the
  reset notice keep their text beside their actions as before, and the text
  moves onto its own line above the end-aligned actions only when its
  longest word cannot fit beside them (`Требуется` next to `↑ Показать`,
  `Таланты сброшены` next to `Отменить`, on a 320 dp phone at 200 %), so no
  word is split and `Undo` is never pushed off its row. The Russian rank
  comparison label is the short `СЛЕД. РАНГ` (a half-width column at 200 %).
  `Max` grows with its translation (`Макс.`) as it already did with a
  larger font.

**Tests added:** `TalentEditorTextTest` (every `AllocationIssueCode` → its
typed reason keeping the code; reasons without a named node; the nameless
fallback; identical inspector content under every platform locale; every
descriptor rendered against the literal `EditorCopy` oracle in both
languages; Russian plurals for 0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101,
111 and English plurals; EN/RU resource parity — same keys, same
placeholders, complete plural forms; a source scan that fails on any
user-facing literal in the editor), `EditorLocalizationUiTest` (the real
editor in EN and RU: loading / failure / opening panes, every compact
inspector state with its spoken node state and controls, the expanded
sheet, spend and refund, blocked reasons, prerequisite and tier locks,
cross-section Show with the Back chip and Fit, Reset + Undo with 1 and 21
points, the header's labels, autosave failure + Retry, the description
labels, a language switch that changes words only — build, selection,
expansion, camera, reports and stored bytes unchanged, no autosave — the
nameless / unavailable fallbacks and user titles verbatim; no English editor
copy on a Russian screen, dataset tooltip text excepted) and
`EditorLocalizationLayoutUiTest` (393 × 852, 320 × 568, 915 × 412 × 100 / 200 %
× EN/RU: no mid-word break, no overflow, nothing past the window, rank
actions and Show ≥ 44 dp). Every desktop UI test that renders editor copy now
pins its language with `AppLanguageRule`. Opt-in screenshot evidence:
`M5Rel2bScreenshotTest` (`-Dtalentforge.m5rel2b.screenshots=<dir>`),
committed under `docs/screenshots/m5-rel-2b/`.

The physical device check follows the same copy: every selector
`scripts/android_section_check.py` taps or reads lives in one `UiCopy` set
per language (`UI_COPY_BY_LANGUAGE`, chosen by the existing `language_of`
locale rule) — Home's create button and continue card, Class Selection's
Warrior tile, the editor header's undo control, the + control's
accessibility label (its `%1$s` talent name stays the untranslated
`Improved Rend`) and the `not_talents` accessibility set that must never
read as an on-screen talent. Dataset names, the numeric `1 / 51` counter and
the `x` close glyph are language-neutral constants. The same scenario —
tabs, rotation, swipes, spend, the side panel, Back and the relaunch — runs
unchanged on an English or a Russian phone;
`tests/unit/test_android_section_check.py` pins both selector sets and
cross-checks them against `values{,-ru}/strings.xml`, so renamed copy fails
in the unit suite instead of on a device.

**Known limitations.** Talent names and tooltip text are dataset content and
stay English (M5.REL.3). The section tabs' fallback labels keep their
M5.REL.1 table (`Tree N` / `Ветка N`). Technical details inside the failure
panes (dataset diagnostic, engine reason, reason codes) are not translated.
`Draft (copy)` names produced by duplication are persisted by the shared
repository in English (unchanged, see M5.REL.2a).

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
2. `scripts/sync_mobile_datasets.py` mirrors `manifest.json` + the nine class
   documents of **every bundled build** (`BUNDLED_BUILDS` in
   `scripts/mobile_bundles.py`: `1.60.1.70009`, `1.60.1.70245`, `1.60.1.70338` and `1.60.1.70374`) into
   `mobile/composeApp/src/commonMain/composeResources/files/dataset/forever/<build>/`;
   `sync_mobile_visuals.py`, `sync_mobile_tooltips.py` and `sync_mobile_localization.py` do
   the same for `files/visuals/…`, `files/tooltips/…` and `files/localization/…` through
   the same module.
3. `--check` mode (offline, CI) fails (exit 1) on a missing, changed or extra
   file of a bundled build, on a missing build and on a bundled directory of a
   build that is not listed. A sync repairs files inside the listed builds but
   **never removes another build's bundle** — saved builds open on their own
   build, so retiring a bundled build is a deliberate change (it also fails the
   Kotlin drift tests below), never a side effect of syncing a new one. A sync is
   all or nothing: its preflight (an unlisted bundled build, a missing required
   canonical file) runs before the first file is written or removed, and a failed
   preflight leaves the bundle untouched.

The bundled copy is deterministically derived and never hand-edited. Drift is
caught in three places: the Python check scripts (CI), the JVM
`DatasetDriftTest` / `VisualsDriftTest` / `TooltipsDriftTest` /
`LocalizationDriftTest` (every bundled build, and exactly the four bundled
builds), and the resource pipeline itself (files are packaged as-is); the
localization bundle is also guarded by the Python
`sync_mobile_localization.py --check` (CI and `test_mobile_bundles.py`).
`test_mobile_bundles.py` also pins `PRODUCT_BUNDLE`
(read from `BundledDatasets.kt`) to the newest bundled build.

## Bundled datasets: product and reference (M5.DATA)

The app bundles four complete Forever datasets side by side — documents, icons and
descriptions of each, every one from its own client build:

| Constant (`ui/library/BundledDatasets.kt`) | Client build | Role |
| --- | --- | --- |
| `PRODUCT_BUNDLE` | `1.60.1.70374` | The **product dataset** (M5.DATA.3): New Build lists its classes from this manifest and creates every new draft on it. Selected as the newest Forever build wago.tools listed on 2026-10-14 with `is_bgdl` explicitly `false` ([forever-dataset.md](forever-dataset.md#product-dataset-160170374-m5data3)) — not a claim that it is the build the live realms run. |
| — | `1.60.1.70338` | The product dataset of M5.DATA.2 — the same talent rows, icons and descriptions as 70374, but its own dataset identity. Kept bundled, byte-for-byte unchanged, so that builds saved on it open on it; no app code names it (desktop tests do, as `PREVIOUS_PRODUCT_BUNDLE`) — a saved record carries its own client build. |
| — | `1.60.1.70245` | The product dataset of M5.DATA. Kept bundled, byte-for-byte unchanged, for the builds saved on it (desktop tests: `BUNDLE_70245`). |
| `REFERENCE_BUNDLE` | `1.60.1.70009` | The **verified reference baseline**: the live-client evidence and the M2 conformance vectors are pinned to it, and every build saved before M5.DATA was created on it. Kept bundled, byte-for-byte unchanged. The M4/M5 tree UI fixture (`loadBaselineWarriorTreeState`) and the existing screenshot tests use it. |

**Saved builds.** `loadEditorSession` and the build cards resolve each record's **own**
`game` / `clientBuild` / `classToken` / `treeId` (unchanged since M5.1): a build saved on
1.60.1.70009 (or 1.60.1.70245, or 1.60.1.70338) opens on that build's document, engine, icons and
descriptions, with its exact ranks and points. Nothing is migrated: no node-id remapping,
no rank or point recount against the product dataset, no rewrite of the stored record, and
`allocation-build.v1` is unchanged. The data shows why this matters — the same node id can
mean something else in two builds (Druid node 104951 is *King of the Jungle*, max rank 3,
on 70009 and *Shifting Power*, max rank 1, since 70245, so a valid 70009 build is invalid on
the later ones), a node can disappear (Warrior *Toughness* 105973 exists on 70245, not on
70338, so a 70245 Toughness build is invalid on 70338), and a build can keep *validating*
while its talents change meaning (Warrior node 105953 is *Boundless Rage* on 70245 and
*Furious Precision* on 70338, both max rank 3) — validity is no license to migrate. The
same holds when nothing changed at all: 70338 and 70374 carry identical talent rows, and a
70338 build still opens on 70338 (its own document hash, icons and descriptions). A record
of a client build that is not bundled (e.g. 1.60.1.70170, or 1.60.1.70334 although its talent
data equals 70338's and 70374's) keeps failing with the typed
`DatasetLoadError.ManifestUnavailable(AssetMissing("dataset/forever/<build>/manifest.json"))`
and reads nothing else — never a fallback to a bundled dataset.

**What the UI states.** The New Build header shows "Forever · talent data
1.60.1" (the data version of the exact client build 1.60.1.70374 the drafts
are created on; the exact build is still pinned by the read-path tests below);
Home's first-run line keeps the spec form "Forever talent data 1.60.1". Neither
claims "latest" or "up to date". Build cards do not show a build yet: the
spec's "Built on older talent data" notice (UX/UI spec F.1) and any
review/migration flow are out of scope.

**Package size.** Each bundled build adds ≈ 5 MB of raw resources (dataset 0.98 MB,
descriptions 0.89 MB, 357–358 PNG icons 3.1 MB); icons are not shared across builds, by
design (each build ships the icons of its own client files).

**Tests.** `DatasetVersionCompatibilityTest` (desktop, real bundled resources, every
asset read recorded to prove which build answered): New Build lists 70374; new Druid and
Warrior drafts are created and open on 70374 (the reworked Warrior tree: Gore Drinker, Iron
Will, Furious Precision with their own icons and descriptions); a 70009 Druid build with
node 104951 (King of the Jungle) at 3/3 opens on 70009 unchanged and is invalid on 70338; a
70245 Warrior build with Toughness 5/5 opens on 70245 unchanged and is invalid on 70338; a
70245 Boundless Rage 3/3 build validates on 70338 but still opens on 70245 as Boundless
Rage; a 70338 Warrior build with Gore Drinker maxed validates on 70374 (identical tree) and
still opens on 70338 with 70338's document hash, icons and descriptions; cards of 70009,
70245, 70338 and 70374 builds resolve their own datasets; unbundled builds (70170, 70334)
fail typed and read nothing else. `ProductDatasetContractTest` (shared JVM): the committed
70374 documents, manifest, visuals and tooltips pass the strict readers and repository
identity checks, the Kotlin engine supports them exactly like 70009 (only Rogue Venom
unresolved), and 70338's content-identical sidecars placed under 70374 fail with a typed
`BuildMismatch`.
`BundledBaselineTest` loads every bundle through the real Compose resource pipeline.

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

> **Verification.** Run locally on macOS 26.3.1 / Xcode 26.4.1 at `add3738`:
> `:shared:jvmTest` (185 tests), `:composeApp:desktopTest` (243, of which the 9 opt-in
> screenshot tests are skipped), `:composeApp:assembleDebug`,
> `:shared:iosSimulatorArm64Test` (123), both iOS framework links and the `iosApp`
> simulator `xcodebuild` — all green. The rendering was reviewed on a real Android
> emulator (360 × 640 dp) and an iOS simulator; evidence is in
> `docs/screenshots/m5-v1/`.

Real WoW talent icons replace the monogram as the normal node content. They are
build-pinned, bundled, and read only from local resources — there is no network
client anywhere in the app. Where the icons come from, the two sidecar contracts
and the ownership note for the artwork: [talent-visuals.md](talent-visuals.md).

**Bundle.** `scripts/sync_mobile_visuals.py` mirrors
`data/visuals/forever/<build>/` into
`composeResources/files/visuals/forever/<build>/` for every bundled build: nine
`talent-visuals.v1` sidecars, the `talent-icons.v1` registry `icons.json` and
`icons/<FileDataID>.png` (deduplicated by FileDataID across classes: 358 files
for 1.60.1.70009, 357 for 1.60.1.70245, 358 for 1.60.1.70338 and 1.60.1.70374). Drift is caught by the script's `--check` (CI) and the JVM
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

Two adaptations are implementation decisions, not spec text (shown in
`docs/screenshots/m5-v1/m5v1-android-close-crop-states.png`): B3 01 "Fill = what I spent" cannot fill a body that shows
artwork, so the accent moves from the body fill to a frame on the same body;
and outline chips get an opaque plate over an icon so rank text and lock glyphs
never read against art. The dimming/frame constants are marked
implementation-safe in the source. State is still never carried by colour
alone: border style and chip treatment differ exactly as in M4.2.

**Inspector.** Every inspector variant shows the selected talent's icon in
front of its name (`[icon] Talent Name`, 36 dp in the compact bar, 40 dp in
the full-detail headers); with no icon the header is the M4 one. In the compact
bar the rank sits on the second line, in front of the state line
(`1/3  In progress`): next to the icon and the − / + controls, name and rank no
longer fit on one line of a 360 dp screen — on a real device the rank wrapped
one character per line — so the name keeps the first line and the rank is
single-line. Home / New
Build / Saved Builds are unchanged — class marks stay monograms (they identify
classes, not talents). *(Superseded for Home and All Builds by M5.UI.1, which
replaces those class monograms with the bundled class artwork — see
[B+ shell redesign (M5.UI.1)](#b-shell-redesign-m5ui1); New Build's monogram
picker was superseded by M5.UI.2 — see
[B+ class selection (M5.UI.2)](#b-class-selection-m5ui2), where the artwork
tile became the picker's mark and the monogram survives only as the
unknown-token fallback.)*

**Descriptions** were not rendered in M5.V1; M5.V2 adds them from a separate
sidecar - see [Talent descriptions (M5.V2)](#talent-descriptions-m5v2). The raw
`descriptionTemplate` is still never shown.

**Package size.** Debug APK 9 469 072 bytes on `main` (`202467f`) →
11 950 284 bytes at `add3738`: +2 481 212 bytes (≈ 2.37 MiB) for 358 PNG icons
(1 996 859 bytes), the registry and nine sidecars (216 640 bytes) and the code.
PNGs are stored uncompressed in the APK.

**Tests added:** `TalentVisualsContractTest`,
`TalentVisualsRepositoryTest` (`shared/jvmTest`); `TalentIconAssetsTest`,
`TalentTreeIconUiTest`, `VisualsDriftTest` and the opt-in, desktop-rendered
`TalentTreeM5V1ScreenshotTest` (`composeApp/desktopTest`).

## Talent descriptions (M5.V2)

Real rank-specific talent descriptions, rendered at build time from the DB2 tables of the
pinned client build and bundled offline. Sources, rules, evidence and the gap policy:
[talent-tooltips.md](talent-tooltips.md); coverage for the reference baseline 1.60.1.70009 (1290 of 1333 ranks
resolved, 43 partial, 0 unresolved, every gap listed):
[research/forever-1.60.1.70009-tooltip-coverage.md](research/forever-1.60.1.70009-tooltip-coverage.md);
for 1.60.1.70245 (1289 resolved, 43 partial, 1 unresolved):
[research/forever-1.60.1.70245-tooltip-coverage.md](research/forever-1.60.1.70245-tooltip-coverage.md);
for the product dataset 1.60.1.70374 (1281 resolved, 47 partial, 1 unresolved, identical to 70338):
[research/forever-1.60.1.70374-tooltip-coverage.md](research/forever-1.60.1.70374-tooltip-coverage.md).

**Contract and loading.** `talent-tooltips.v1` sidecars
(`composeResources/files/tooltips/forever/<build>/<class>.json` of every bundled build, mirrored
byte-for-byte by `scripts/sync_mobile_tooltips.py`, CI `--check`) are decoded by
`TalentDataContract.decodeTalentTooltipsDocument` (strict, closed enums, numeric
wire-form check) and verified by `BundledTalentTooltipsRepository` (`shared`,
`data.tooltips`) against the loaded class document: game, client build, class token,
`talentDataContentHash`, every entry exactly once with the dataset's definition / spell /
maxRank, ranks exactly `1..maxRank`, status consistent with segments and gaps, no `$`,
`|` or `\r` in any text. Failures are typed `TooltipsLoadError`s; there is no
cross-build substitution and no runtime network. `TalentDescriptionsLoader`
(`composeApp`) loads them together with the icons when an editor session opens and maps
entries to nodes (single-entry nodes only, like icons). A load failure never fails the
session: `TalentNodeDescriptions.Unavailable` makes every inspector show "Talent
description unavailable for this client data."

**Inspector.** The full-detail variants (portrait expanded sheet, landscape side panel,
tablet persistent panel) show, below the rank, state and lock lines:

* the description of the **current** rank; for an unspent talent the rank-1 description
  as the prospective effect;
* the B3 04 / UX/UI spec H **CURRENT / NEXT RANK** comparison when exactly one value
  changes between the ranks (rank 0: "—" / the rank-1 value, as in spec H's
  "Deflection 0/5 … — · 1%"); when several values change (Weaponmaster, plurals), the
  next rank's full text with the changed values emphasised - an unlabelled column of
  numbers would not be readable; at max rank no next-rank section;
* values semibold; a value that is not shown is a muted "—" with one note line
  ("Values marked — depend on your character and are not shown." or "… are not
  available in this client data.").

The presentation (`TalentInspectorDescription`) is a pure function of the verified
segments and the node's rank - it is recomputed from the engine-backed presentation, so
`+`, `−`, Max, undo, redo and Reset update it with the build. The compact portrait bar is
unchanged (B3 03: name, rank, state, actions). The detail column scrolls; the `− + Rank
Max` row **and Show** are pinned to the bottom of the side and tablet panels (as in the
expanded sheet), so a long description can never push a control, the blocked-action
reason in the state line, or a lock reason out of reach. Deviation from the B3 04 order
("description, current / next, requirements"): the lock lines stay above the description
for that reason.

**Tests added:** `TalentTooltipsContractTest`, `TalentTooltipsRepositoryTest`
(`shared/jvmTest`); `TalentInspectorDescriptionTest`, `TalentTreeDescriptionUiTest`,
`TooltipsDriftTest` and the opt-in, desktop-rendered `TalentTreeM5V2ScreenshotTest`
(`composeApp/desktopTest`).

**Device evidence** (`docs/screenshots/m5-v2/`, reference baseline 1.60.1.70009, real
`adb screencap` / `xcrun simctl io screenshot` captures of the debug app): Android
emulator (1080 × 2424, API 35) portrait Improved Rend rank 0 / 1 of 3 / max, Improved
Slam, Weaponmaster (multi-line), Anger Management (prerequisite-locked, Show pinned),
Mage Arcane Focus, Rogue Relentless Strikes (partial description), Druid Improved Wrath
(several values change: next-rank text), landscape side inspector; iOS simulator
(iPhone 17 Pro) New Build version line and the Improved Rend inspector.

## Localized talent text bundle (M5.REL.3b.1)

The client's own ruRU talent names and descriptions, as offline presentation overlays
next to the English data. Source rules, the eight description gates, coverage per build
and the English-fallback contract: [talent-localization.md](talent-localization.md).
M5.REL.3b.1 published and mirrored the data; M5.REL.3b.2 (next section) reads it in the
editor.

**Bundle.** `talent-localization.v1` overlays
(`composeResources/files/localization/forever/<build>/ruRU/<class>.json` of every bundled
build — 36 files, nine classes × four builds, mirrored byte-for-byte by
`scripts/sync_mobile_localization.py` through `scripts/mobile_bundles.py`, CI `--check` in
`mobile-ci`). Presentation-only: never an input to allocation, validation, saved builds or
any English contract; each overlay pins its build's dataset and English-tooltip revisions
(`talentDataContentHash`, `tooltipsContentHash`) and its own `contentHash`.

**Coverage (committed 2026-10-15).** Names translated / entries: 70009 — 467/469,
70245 — 464/470, 70338 — 459/469, 70374 — 459/469 (0 missing everywhere). Descriptions
rendered / entries (ranks): 70009 — 386/469 (1 143/1 333), 70245 — 365/470 (1 097/1 333),
70338 — 355/469 (1 058/1 329), 70374 — identical to 70338. Everything else falls back to
the English text with a recorded reason (plural selection, decimal separator, unresolved
English source).

## Russian talent text in the editor (M5.REL.3b.2)

The editor shows the client's own Russian talent names and the descriptions it can show
honestly when the device's primary language is Russian (the existing `AppLanguage` rule, no
in-app selector), and exactly the previous English presentation otherwise. Localization is a
separate, validated presentation projection: the allocation engine, `TalentDataDocument`
(its English names stay), saved builds, history and navigation are untouched.

```
EditorSession load (after every allocation gate)
  TalentIconsLoader ──── visuals (verified) ─────┐
  TalentDescriptionsLoader ── tooltips (verified) ┤  each asset read once
  TalentLocalizationLoader ──────────────────────┘
    BundledTalentLocalizationRepository  localization/forever/<build>/ruRU/<class>.json
      → LocalizationOutcome.Loaded | Failed(LocalizationLoadError)   (whole or nothing)
    resolveClassTalentText (port of display.resolve_class_text, by entryId)
    → TalentNodeLocalization  Loaded(names, ranks by node) | Rejected(error) | None
TalentTreeScreen: talentNodeDisplayText(appLanguage(), descriptions, localization)
  → names  → talentNodeFacts (inspector heading, Requires/Show/needed-for/blocked copy)
           → TalentTreeCanvas (node accessibility labels)
  → descriptions → the existing inspector description, comparison and highlighting
```

**`mobile/shared` (`data/localization/`).**

* `TalentLocalizationDocument` — the strict `talent-localization.v1` model, decoded by
  `TalentDataContract.decodeTalentLocalizationDocument` (unknown fields/enums, missing fields
  — nullable ones included — and quoted or fractional numbers rejected; unsupported
  `schemaVersion` typed).
* `localizationContentHash` — the Python `content_hash` procedure in pure Kotlin: canonical
  JSON (`sort_keys`, `(",", ":")`, `ensure_ascii=False` escaping, code-point key order) of
  every field except `contentHash`/`provenance`, SHA-256. The published hash is recomputed,
  never trusted; cross-language vectors (`tests/fixtures/localization/content-hash-vectors.json`)
  are generated and checked by Python and recomputed by `LocalizationContentHashTest`.
* `BundledTalentLocalizationRepository.localization(document, tooltips, visuals, localeCode)`
  — the runtime `check_localization_document`, in this order: schema patterns/ranges;
  locale (case-exact `ruRU`, anything else `UnsupportedLocale` without a read); game, build,
  class; the English tooltips/visuals belong to the document; `talentDataContentHash`;
  `tooltipsContentHash`; recomputed `contentHash`; then the entry set (no duplicate, unknown,
  missing or unordered entry), node/definition/spell/maxRank identity and `textSpellId` =
  the visuals' `displaySpellId` (VisibleSpellID, else SpellID — the same rule; talent-data.v1
  has no VisibleSpellID); text ⇔ `translated` with Cyrillic; ranks exactly `1..maxRank` ⇔
  `rendered`; no empty segment or markup (`$ | \r`); every rendered rank over a `resolved`
  English rank with exactly its numbers and no decimal value. Typed `LocalizationLoadError`s
  (missing, unreadable, malformed, unsupported version, invalid value, foreign identity,
  stale dataset hash, stale tooltips hash, tampered content, entry set/identity, name, ranks,
  segment, unresolved English, number, decimal); cancellation propagates. `describe()` is
  for diagnostics only — no error ever becomes user-facing copy.
* `resolveClassTalentText` — `TalentClassText` by entry id: translated name or English;
  rendered ranks or the English ranks unchanged; a rejected overlay → English for every
  entry with the typed reason. English tooltips of another document are an error.
* `TalentTooltips` / `TalentVisuals` additionally expose the hashes and display spell ids they
  were verified with.

**`mobile/composeApp`.**

* `TalentLocalizationLoader` (ui/assets) runs only with verified English tooltips and
  visuals (otherwise `None`: nothing to check against), maps the resolved text onto
  single-entry nodes (the icons/descriptions rule — no multi-entry node is localized), and
  turns a rendered rank into the inspector's rank shape (`resolved`, the same text/value
  segments), so CURRENT / NEXT RANK and the changed-value highlight work unchanged.
* `EditorSession.localization` / `TalentTreeUiState.Ready.localization` carry the projection;
  `loadEditorSession(…, localization)` and `TalentTreeBuildScreen(…, localization)` take the
  loader (`App` passes it). Gameplay dataset failure → the existing typed `EditorFailure`;
  tooltip failure → the existing unavailable description (and English names); overlay
  failure → the editor opens in English.
* `TalentTreeScreen` reads `appLanguage()` once and derives `talentNodeDisplayText`; a
  language change only re-projects — no reload, no report, no autosave; selection, sheet,
  section cameras, the build and undo/redo are untouched. The overlay is loaded at session
  open whatever the language, so a switch needs no I/O.
* Product decision (talent-localization.md §9 step 5): a Russian name over an English
  description is shown as is; nothing labels the editor or a talent "localized".
* Layout for longer Russian text: the expanded sheet and the side panels show the whole name
  (no two-line cap — only the compact strip ellipsizes, by design); at ≥ 175 % text the
  sheet's icon gives its width to the name and the landscape panel puts × above it.

**Tests.** shared: `TalentLocalizationRepositoryTest` (36 overlays, every rejection),
`TalentClassTextTest` (the `test_display.py` port), `LocalizationContentHashTest`; desktop:
`TalentLocalizationUiTest` (EN/RU inspector, Show/prerequisite/a11y, fallback, all four
client builds, language change), `EditorLocalizationLayoutUiTest` (longest Russian names and
descriptions × 393 × 852 / 320 × 568 / 915 × 412 × 100 %/200 %), `LocalizationDriftTest`
(bundle == `data/localization`, 4 builds × 9 classes). Screenshots:
`M5Rel3b2ScreenshotTest` (`-Dtalentforge.m5rel3b2.screenshots=<dir>`), committed under
`docs/screenshots/m5-rel-3b2/`.

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

# talent descriptions drift check / re-sync — offline
python3 scripts/sync_mobile_tooltips.py --check
python3 scripts/sync_mobile_tooltips.py

# ruRU talent text overlays drift check / re-sync — offline
python3 scripts/sync_mobile_localization.py --check
python3 scripts/sync_mobile_localization.py
# (all four mirror every build in BUNDLED_BUILDS of scripts/mobile_bundles.py)

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

# opt-in M5.UX.2b.1 landscape screenshot evidence (desktop-rendered; cutouts emulated)
./gradlew :composeApp:desktopTest --tests "*TalentTreeM5Ux2b1ScreenshotTest*" \
    -Dtalentforge.m5ux2b1.screenshots=<output-dir>

# opt-in M5.UI.1 B+ Home / All Builds screenshot evidence (desktop-rendered)
./gradlew :composeApp:desktopTest --tests "*M5Ui1ScreenshotTest*" \
    -Dtalentforge.m5ui1.screenshots=<output-dir>

# opt-in M5.UI.2 B+ class selection screenshot evidence (desktop-rendered)
./gradlew :composeApp:desktopTest --tests "*M5Ui2ScreenshotTest*" \
    -Dtalentforge.m5ui2.screenshots=<output-dir>

# Android
./gradlew :composeApp:assembleDebug    # -> composeApp/build/outputs/apk/debug/

# M5.UX.2b.1 section navigation on a USB device (from the repository root; adb on PATH).
# Builds and installs the isolated `.devcheck` application, runs the scenarios
# (tabs, rotation, swipe, side panel, Back, relaunch) with real `adb input` taps,
# then restores rotation and uninstalls it. Report: .cache/android-section-check/
python3 scripts/android_section_check.py --runs 2

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
