# M6.UX.3 - Talent requirements clarity

Audit date: 2026-11-10. Base: `main` (`9e5884d`). UI-only changes; allocation rules,
saved-build/share formats, OTA contracts, delivery workflows and app versions are unchanged.

## Evidence and findings

The audit compares canonical normalized data, the reference semantics, engine queries and
actual canvas/inspector rendering. A reported arrow direction alone is not evidence of a defect.
All 54 committed DB2 class/build fixture edge tables were compared with normalized output:
`LeftTraitNodeID` is the source, `RightTraitNodeID` the dependent; types 0/2/3 preserve
visual-only/sufficient/required distinctions. Canvas geometry keeps that same direction.

| Finding | Real data and reproduction | Expected | Result |
|---|---|---|---|
| Compact inspector dropped the point requirement when an edge was also unmet | Warrior `105950` Deep Wounds, prerequisite `105956` Improved Rend 3/3, 10-point gate; empty build | Show both independent lock reasons | Both lines wrap without ellipsis; Show still navigates exactly one hop |
| Completed requirements disappeared from full detail | With Improved Rend 3/3 and only 3 counted points, Deep Wounds remains tier-locked; with 10 points elsewhere the opposite combination exists | Explain each requirement independently, including progress already achieved | Detail retains every published gate and gameplay prerequisite with current/required numbers, `✓ Met` / `○ Not met` words and glyphs |
| Multiple sufficient edges sounded like ALL and had no navigation | Hunter `104964` Intimidation has SUFFICIENT sources `104961` Bestial Wrath and `104970` Bestial Swiftness; 20-point gate | ANY one source, not both; let the reader choose a direct prerequisite | EN/RU summary says one of two; Requirements opens named choices, each unmet source has its own Show action; no automatic target preference |
| Compact requirement width could overflow at normal size and combined locks were truncated at large text | 360 dp, English, 100%; 320 dp, EN/RU, 200% | Full essential lock text and usable controls | Requirement text occupies its available column width and grows vertically; actions stack when necessary |
| Apparent backward Hunter edge is real data, not a rendering defect | `104961` Bestial Wrath -> `104964` Intimidation and `104964` -> `104961`; Bestial Swiftness is the external root | Preserve data direction and sufficient-edge semantics | No connector reversal or decorative arrow added; the canvas already draws source-to-dependent geometry |
| Druid decoration hid the inactive gameplay connector | Raw type 0 edge `124666`, `104928` -> `104927`, overlaps raw Required type 3 edge `124675`, `104927` -> `104928`; the solid decoration filled every dash gap | Unmet gameplay connector remains dashed; completed remains solid | Renderer omits only redundant visual-only connectors on the same node pair; both raw edges stay in the model. Real-geometry pixel regression failed with zero gaps before the fix, then passed for both unmet and completed states |
| Druid decoration must not become a requirement | VISUAL_ONLY `104928` Nature's Splendor -> `104927` Nature's Majesty | Ignore for allocation and requirement text | Only `TalentNode.prerequisites` supplies requirement rows; redundant decoration is omitted, and non-overlapping visual-only connectors stay neutral |

`docs/talent-allocation-semantics.md` remains authoritative. In particular, EDGE-ACTIVE
(fully ranked source) and EDGE-SUF (ANY sufficient source) retain their documented provisional
confidence. This change presents existing dataset/engine facts; it adds no client evidence and
changes no gameplay rule. Required groups are labelled ALL; sufficient groups are labelled ANY.
Unsupported/unresolved nodes retain their existing unavailable state and typed action errors.

Point progress uses the gate's exact `countedNodeIds`, not total class points or a fabricated
`row * 5` rule. Published thresholds 5/10/15/20/25/30 are checked across all classes. This also
preserves capstone before-purchase semantics and excludes nodes that the dataset does not count
(e.g. Mage Arcane Wrath in 70450). The compact deficit comes from the engine's typed gate issue;
full detail additionally shows current/required counted points and its non-interactive meter.

No persistent labels or arrows were added to the canvas. Existing locked glyphs and neutral selection rings remain unchanged. Gameplay connectors
retain their solid/dashed state language; redundant decoration can no longer cover it. Portrait uses the B3
bottom inspector; landscape uses the side panel. The single-source Show remains pinned outside
scrolling detail; multiple-source choices live in the scrollable detail and do not spend points.

## Data coverage

`TalentRequirementsAuditTest` loads all **108 real documents**: nine classes on all 12 committed
versions (69977, 70009, 70245, 70338, 70374, 70450, 70532, 70707, 70753, 70755, 70790, 70862).
It fills each specialization through the existing engine, checks every gate's exact counted set,
prerequisite identity/kind, connector direction, legal refunds and immutable inspection.

| Class | 70450 nodes / gameplay edges / visual edges | 70862 nodes / gameplay edges / visual edges |
|---|---|---|
| Warrior | 52 / 8 / 0 | 53 / 8 / 0 |
| Paladin | 50 / 7 / 0 | 51 / 8 / 0 |
| Hunter | 52 / 9 / 0 | 52 / 9 / 0 |
| Rogue | 53 / 8 / 0 | 54 / 8 / 0 |
| Priest | 53 / 8 / 0 | 53 / 8 / 0 |
| Shaman | 50 / 7 / 0 | 50 / 7 / 0 |
| Mage | 55 / 7 / 0 | 53 / 7 / 0 |
| Warlock | 52 / 8 / 0 | 52 / 8 / 0 |
| Druid | 52 / 11 / 1 | 52 / 10 / 1 |

The dedicated Warrior test covers all four combinations of tier/prerequisite completion and
refund from 3/3 to 2/3. Hunter UI coverage checks named alternatives and one-hop navigation to
Bestial Swiftness. `TalentRequirementsPlatformTest` executes the same requirement projection on
JVM and iOS using all **45 packaged real datasets**, including completed prerequisite with a
closed tier, refund refresh and the Hunter alternatives. Existing saved ranks are never modified
by inspection; UI tests additionally assert no build-change/autosave callback from viewing/Show.

## Validation

| Command (repository root unless noted) | Result |
|---|---|
| `python3 scripts/sync_mobile_datasets.py --check` | PASS: 50 files, five bundled builds |
| `.venv/bin/mypy` | PASS: 122 source files |
| `.venv/bin/ruff format --check .` | PASS: 639 files |
| `.venv/bin/ruff check .` | Two pre-existing errors in untracked `.tf-transfer/rc1/rc1_android_device_check.py`: E501 line 144, E741 line 224. All tracked Python files pass Ruff |
| `.venv/bin/pytest` | PASS: 2,868 passed, 11 deselected |
| `cd mobile && ./gradlew :shared:jvmTest` | PASS: 373 tests |
| `cd mobile && ./gradlew :shared:iosSimulatorArm64Test` | PASS: 190 tests |
| `cd mobile && ./gradlew :composeApp:iosSimulatorArm64Test` | PASS: 8 tests, including all 45 packaged datasets in the new requirements test |
| `cd mobile && ./gradlew :composeApp:desktopTest` | PASS: full suite 894 tests, 0 failures, 64 skipped (830 executed). After the final one-line Show identity correction, focused renderer/Show/UI/resource rerun: 60 tests, 0 failures, 0 skipped |
| `cd mobile && ./gradlew :composeApp:assembleDebug` | PASS |
| `cd mobile && ./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64` | PASS |
| `cd mobile/iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build` | PASS |
| `graphify update .` | PASS: AST-only update; existing extractor warnings on three unrelated test files |
| Independent code review | No substantive findings |


The desktop Compose matrix covers EN/RU with verified localized talent names, widths
320/360/393/412 dp, font scales 100/130/200%, and a zoomed tree. It checks full text layout and
screen bounds of both compact reasons, zoom preservation and absence of saves. Full-detail
checks cover 320x568 portrait, 760x360 landscape and 1024x768 tablet at 200%, scrollable
requirements and pinned one-hop Show.

Native Android checks use the isolated `.devcheck` app; the production installation is not
used. The existing section-navigation script recorded 19/20 passes on Android 8: its landscape
Home assertion expected the off-screen Continue editing button without scrolling. The captured
Home screenshot shows the correct saved Warrior card; portrait relaunch restores the same 1/51.
Home/navigation code is outside this change. Focused native requirements checks pass for all
nine classes (70450): Anger Management, Illumination, Intimidation, Lethality, Soul Warding,
Lightning Overload, Arcane Meditation, Curse of Exhaustion and Nature's Splendor. Both lock
reasons and one-hop Show preserve the same 1/51 total; the 320 dp / 200% system-font check
also passes. Per-class results are in [the native Android report](m6-ux3-android-classes.json). The original saved Warrior build payload is compared directly in the isolated
SharedPreferences library and remains unchanged. Test display/font settings were restored afterwards.

The iOS simulator executes real packaged-data tests and the built app launches. Native iOS
interactive visual acceptance is not claimed: this environment provides no accessible Simulator
window through computer-use tools. The EN/RU size/font layout matrix is desktop-rendered shared
Compose UI, not device screenshot evidence.

## Screenshot evidence

These are deliberately labelled by renderer/platform; desktop screenshots do not claim
native iOS visual acceptance.

- [Desktop EN, 320 dp / 200%](../screenshots/m6-ux3/desktop-en-320-font200.png)
- [Desktop RU, 320 dp / 200%](../screenshots/m6-ux3/desktop-ru-320-font200.png)
- [Hunter named ANY choices](../screenshots/m6-ux3/desktop-hunter-any-360.png)
- [Small portrait RU detail at 200%, scrolled to point progress](../screenshots/m6-ux3/desktop-ru-detail-320x568-font200.png)
- [Landscape RU at 200%, scrolled to point progress](../screenshots/m6-ux3/desktop-ru-landscape-font200.png)
- [Native Android EN, 320 dp / 200%](../screenshots/m6-ux3/android-en-320-font200.png)

The connector screenshots below isolate two **real** Druid nodes/edges in the production canvas
at 1x; no synthetic talents or direction assumptions are introduced. Pixel assertions check
that the unmet line has visible gaps and the completed line has none. A further pixel regression
replaces the model with equal copied edges before Show: it failed before resolving the guide
from the same cached connector list, then passed with the expected bright one-hop path.

- [Druid before: decoration fills the gaps](../screenshots/m6-ux3/druid-before.png)
- [Druid after: unmet required connector](../screenshots/m6-ux3/druid-unmet.png)
- [Druid after: completed required connector](../screenshots/m6-ux3/druid-completed.png)
