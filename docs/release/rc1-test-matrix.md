# M5.RC.1 — Test matrix

Audit base: `main` @ `5648941`. Evidence IDs: see [`rc1-evidence/README.md`](rc1-evidence/README.md).
Defect IDs: see [`rc1-defects.md`](rc1-defects.md).

## Execution environments (actually used)

| Code | Environment |
|---|---|
| **MAC-JVM** | MacBook (Apple M4 Pro, 24 GB), macOS 26.3.1, JDK 17.0.19 (Homebrew), Gradle 9.5.1 wrapper, Kotlin 2.4.20 / Compose MP 1.10.3. Shared JVM tests and Compose **desktop** UI tests (real composables, fake storage, no device). |
| **MAC-PY** | Same Mac, Python 3.12.13 venv, ruff 0.16.9, mypy 2.3.1, pytest. |
| **IOS-SIMTEST** | `:shared:iosSimulatorArm64Test` (Kotlin/Native tests on the iOS simulator runtime), Xcode 26.4.1 / iOS SDK 26.4. |
| **EMU** | Android emulator `sdk_gphone64_arm64`, Android 15 (API 35), 1080×2424 @ 420 dpi (≈411×923 dp), locale **en-US**. Isolated `.devcheck` debug APK installed by adb/uiautomator and uninstalled afterwards. |
| **SIM-iPhone** | iPhone 17e simulator, iOS 26.4, Release-simulator build (launch smoke only). |
| **SIM-iPad** | iPad Air 11-inch (M4) simulator, iOS 26.4, Release-simulator build (launch smoke only). |
| **STATIC** | Source, configuration or artifact inspection (no execution of the scenario). |

**Not available in this audit:**
- A physical Android phone. The TECNO KM5n (Android, ru-RU) was attached in pass 1 but disconnected in pass 2, so `android_section_check.py` and the RC1 device scenarios on it did not run (E2-21, E2-22).
- A physical iOS device. There is no signing identity, so nothing could be installed.
- GitHub Actions. It is infrastructure-blocked (TF-RC1-CI).
- TalkBack and VoiceOver were not used.

**Status legend:**
- **PASS**: executed and met the expectation in the stated environment.
- **FAIL**: executed and did not meet the expectation.
- **BLOCKED**: could not execute for an external reason.
- **UNVERIFIED**: not executed. Code reading alone never upgrades a scenario to PASS.
- **N/A**: not applicable.

A PASS in MAC-JVM proves the shared/common behaviour. It does **not** prove the same behaviour on a phone. Device rows are listed separately where they matter.

---

## 4.1 First launch and build creation

| ID | Scenario | Platform | Env | Status | Evidence |
|---|---|---|---|---|---|
| FL-01 | Fresh install, empty library, Home empty state | Android | EMU | PASS | E2-23 `rc1-evidence/screenshots/emulator-00-empty-home.png` (after `pm clear`) |
| FL-02 | Fresh install empty Home | iOS | SIM-iPhone, SIM-iPad | PASS (launch only) | E2-30/31 launch screenshots: "No builds yet", New build, Import build |
| FL-03 | New Build navigation + class selection, all nine classes | common | MAC-JVM | PASS | `NewBuildClassSelectionUiTest` (15), `MultiClassDraftLoopUiTest` (E2-11) |
| FL-04 | New Build → Warrior on a device | Android | EMU | PASS | E2-23 (S1–S3 each create a Warrior draft) |
| FL-05 | All nine classes on a device (tree, artwork, names) | Android/iOS | — | UNVERIFIED | only Warrior was driven on EMU |
| FL-06 | Product client build selected for new drafts | common | MAC-JVM | PASS | `BundledRepositoryTest`, `ProductDatasetContractTest`, `BuildOpeningTest` (E2-10/11) |
| FL-07 | Draft creation without duplicate records | Android | EMU | PASS | 3 drafts created → Home shows "All builds 3" (E2-23 `rc1-evidence/screenshots/emulator-font2.0-home.png`); `M51AppFlowUiTest` (MAC-JVM) |
| FL-08 | Return to Home and reopen the created build | Android | EMU | PASS | S1–S3: force-stop, relaunch, Continue editing reopens it with the saved counter |

## 4.2 Talent allocation

| ID | Scenario | Platform | Env | Status | Evidence |
|---|---|---|---|---|---|
| AL-01 | Spend, refund, rank limits, tier requirements, prerequisites | common | MAC-JVM, IOS-SIMTEST | PASS | `AllocationEngineTest` (44), `RealForeverAllocationTest`, `AllocationConformanceReplayTest`, `WarriorInteractiveAllocationTest`; 188 iOS-sim tests |
| AL-02 | Python reference engine and conformance vectors | data | MAC-PY | PASS | pytest 1048 passed (E1-13) |
| AL-03 | Invalid-action feedback (typed reasons, disabled +) | common | MAC-JVM | PASS | `noPointsTalentShowsAVisibleButDisabledPlus`, `spendToMaxOnAMaxedNodeIsDeniedWithTypedIssues` |
| AL-04 | Max | common | MAC-JVM | PASS | `maxSpendsAllRemainingRanksAsOneUndoableStep` |
| AL-05 | Undo / Redo | common / Android | MAC-JVM, EMU | PASS | `successfulSpendAddsExactlyOneUndoEntry`; EMU S2: 3 spends + Undo → **2 / 51** persisted |
| AL-06 | Reset; Reset → Undo | common | MAC-JVM | PASS | `resetIsOneAtomicHistoryStep`, `undoAfterResetRestoresTheExactPriorBuildThroughTheSameHistory` |
| AL-07 | Switching specializations (section tabs) | common | MAC-JVM | PASS | `TalentTreeSectionTabsUiTest` (14), `oneTapSwitchesTheSectionClosesTheInspectorAndNeverTouchesTheBuild` |
| AL-08 | Remaining-point counters and library points | Android | EMU | PASS | header `3 / 51`; Home rows `2 / 51`, `3 / 51` (E2-23) |
| AL-09 | Representative edge cases across **every** class on a device | Android/iOS | — | UNVERIFIED | automated coverage only (conformance + import of every bundled build/class: `BuildImportBundledDatasetsTest`) |
| AL-10 | Engine is the only legality authority | common | STATIC + MAC-JVM | PASS | `AllocationRoutingEnforcementTest` |

## 4.3 Existing builds

| ID | Scenario | Platform | Env | Status | Evidence |
|---|---|---|---|---|---|
| EB-01 | Open saved build, ranks restored | Android | EMU | PASS | S1–S3 relaunch |
| EB-02 | Saving after rapid mutations | Android | EMU | PASS | S1 3 rapid `input tap` + immediate Back → `3 / 51` |
| EB-03 | Duplicate, rename, delete, cancelled delete | common | MAC-JVM | PASS | `LocalBuildRepositoryManagementTest` (28), `BuildManagementUiTest` (22), `BuildActionsUiTest` (16) |
| EB-04 | Same on a device | Android/iOS | — | UNVERIFIED | not driven in RC1. Pre-RC1 TECNO screenshots exist only as untracked historical material and are **not** RC1 evidence. |
| EB-05 | Failed storage write + recovery | common | MAC-JVM | PASS | `BuildActionsUiTest` / `BuildShareUiTest` held/failing storage; `autosaveFailureAndRetryReadInBothLanguages` |
| EB-06 | Failed storage write on a device | Android/iOS | — | UNVERIFIED | cannot be induced safely; on iOS it is unreachable by design (SR-4) |
| EB-07 | List ordering (last edited first; rename keeps order) | common | MAC-JVM | PASS | `LocalBuildRepositoryTest` (35) |

## 5. Build management (three locations)

| ID | Scenario | Env | Status | Evidence |
|---|---|---|---|---|
| BM-01 | Home → Recent → ⋯: Rename / Share / Delete; row tap opens editor; ⋯ does not | MAC-JVM | PASS | `BuildActionsUiTest`, `HomeRedesignUiTest` |
| BM-02 | All Builds → ⋯: Rename / Duplicate / Share / Delete, failures, cancellations | MAC-JVM | PASS | `BuildManagementUiTest`, `BuildActionsUiTest` |
| BM-03 | Editor → ⋯: Rename / Share / Reset; no Delete; rename keeps allocation, selection, history | MAC-JVM | PASS | `BuildRenameCoordinationTest` (6), `TalentTreeEditingUiTest` (29) |
| BM-04 | Minimum touch target of ⋯ and row actions | MAC-JVM | PASS | `BuildActionsLayoutUiTest`, `AllBuildsRowLayoutUiTest` |
| BM-05 | All of the above on a phone or tablet | — | UNVERIFIED | — |

## 6. Autosave and data integrity

| ID | Scenario | Env | Status | Evidence |
|---|---|---|---|---|
| AS-01 | Rapid spend ×3 → immediate double Back → force-stop → relaunch | EMU | **PASS** | E2-23 S1 `3 / 51` |
| AS-02 | Rapid spend ×3 → Undo → immediate Back → relaunch | EMU | **PASS** | E2-23 S2 `2 / 51` |
| AS-03 | Rapid spend ×3 → immediate rotation (Activity recreation) → Back → relaunch | EMU | **PASS** | E2-23 S3 `3 / 51` |
| AS-04 | Back waits for the autosave flush; repeated Back is a no-op | MAC-JVM | PASS | `AutosaveExitGuaranteeUiTest` (3), `BackNavigationReentrancyUiTest` (4), `SystemBackOrderAppUiTest` |
| AS-05 | Failed autosave → visible retryable error; Retry | MAC-JVM | PASS | `autosaveFailureAndRetryReadInBothLanguages`; held-storage tests |
| AS-06 | Rename during pending autosave / after failed autosave | MAC-JVM | PASS | `BuildRenameCoordinationTest` (rename waits for `flush`; refused after failure) |
| AS-07 | Share during pending / after failed autosave uses the in-memory state | MAC-JVM | PASS | `BuildShareUiTest` (10), `BuildExportTest` |
| AS-08 | Reset followed by immediate navigation | — | UNVERIFIED (device) | covered in common by AS-04 + AL-06 |
| AS-09 | App backgrounded / process killed during a pending write | — | UNVERIFIED | needs a device run (`am kill` while held) |
| AS-10 | Concurrent writes (autosave vs duplicate/delete/import) | STATIC | UNVERIFIED → suspected risk SR-1 | — |
| AS-11 | iPad multi-window same build | STATIC + artifact | UNVERIFIED → SR-2 / TF-RC1-IOS-SCENES | built Info.plist |
| AS-12 | Large library behaviour | — | UNVERIFIED (SR-5) | procedure below |

## 7. Export / import

| ID | Scenario | Env | Status | Evidence |
|---|---|---|---|---|
| EX-01 | Editor exports current state; pending/failed autosave neither blocks nor changes the code | MAC-JVM | PASS | `BuildShareUiTest`, `BuildExportTest` |
| EX-02 | All Builds / Home export the persisted record; export never writes | MAC-JVM | PASS | `BuildShareUiTest` |
| EX-03 | Canonical codes, vectors, corruption, checksum, version, oversized | MAC-JVM, IOS-SIMTEST, MAC-PY | PASS | `BuildShareCodeTest` (25), `BuildImportTest` (12), Python independent reference (pytest) |
| EX-04 | Import: empty, damaged, unsupported build, invalid allocation, old bundled builds | MAC-JVM | PASS | `BuildImportFlowTest` (14), `BuildImportBundledDatasetsTest` (10) |
| EX-05 | Edit after preview; cancel; preview writes nothing; one record per confirmation; repeated taps; storage failure + retry; editor navigation | MAC-JVM | PASS | `BuildImportUiTest` (11), `BuildImportFlowTest` |
| EX-06 | Android ACTION_SEND chooser, copy, paste, cancel, rotation, background | — | **UNVERIFIED** | compile success is not proof |
| EX-07 | iOS UIActivityViewController, pasteboard, paste-permission prompt, cancel, iPad popover, background | — | **UNVERIFIED** | — |

## 8. Lifecycle and stability

| ID | Scenario | Env | Status | Evidence |
|---|---|---|---|---|
| LC-01 | Activity recreation (rotation) in the editor | EMU | PASS | AS-03; `rc1-evidence/screenshots/emulator-compact-568x320dp-editor-landscape.png` |
| LC-02 | Section navigation, rotation, Back, relaunch (`android_section_check.py`) | physical phone | **BLOCKED** | device disconnected (E2-21) |
| LC-03 | No crash during RC1 device scenarios | EMU, SIM-iPhone, SIM-iPad | PASS | EMU crash buffer empty; iOS process alive after 10 s, no `iosApp` DiagnosticReports |
| LC-04 | Background/foreground, returning from share sheet, keyboard open/close, interrupted import | — | UNVERIFIED | — |
| LC-05 | Navigation state restoration (saved back stack, fail-closed decode) | MAC-JVM | PASS | `AppNavigationStateTest` |
| LC-06 | Reopening a record on an older bundled client build | MAC-JVM | PASS | `DatasetVersionCompatibilityTest` (9), `BuildOpeningTest` |

## 9. Localization

| ID | Scenario | Env | Status | Evidence |
|---|---|---|---|---|
| LO-01 | EN/RU resources complete and consistent (208/208 entries) | MAC-JVM + STATIC | PASS | `AppLanguageResourceConsistencyTest`; strings files |
| LO-02 | Shell, editor, talent text, plurals, fallback to English for missing RU talent text | MAC-JVM | PASS | `ShellLocalizationUiTest`, `EditorLocalizationUiTest`, `TalentLocalizationUiTest`, `aCorruptOverlayShowsTheWholeClassInEnglishWithoutFailingTheEditor` |
| LO-03 | Localization overlay drift | MAC-PY | PASS | `sync_mobile_localization.py --check` (E1-23) |
| LO-04 | English UI on a non-Russian device | EMU (en-US) | PASS | E2-23 screenshots |
| LO-05 | Russian UI on a Russian device | — | UNVERIFIED | the RU phone was disconnected |
| LO-06 | No hard-coded English in new paths | STATIC | **FAIL (P2)** | duplicate names persist `(copy)` / `Draft (copy)` (TF-RC1-COPY-NAME, known) |
| LO-07 | Accessibility labels localized | MAC-JVM | PASS (common) | `UiCopy` label set mirrors the resources; RU on-device UNVERIFIED |

## 10. Responsive UI and accessibility

| ID | Configuration / check | Env | Status | Evidence |
|---|---|---|---|---|
| RS-01 | 320×568 dp phone | EMU (`wm size 640x1136`, 320 dpi) | PASS | `rc1-evidence/screenshots/emulator-compact-320x568dp-home.png`, `rc1-evidence/screenshots/emulator-compact-320x568dp-editor.png` |
| RS-02 | 393×852-class phone (≈411×923 dp) | EMU | PASS | default-size screenshots |
| RS-03 | 568×320 compact landscape (closest available to 640×360) | EMU | PASS | `rc1-evidence/screenshots/emulator-compact-568x320dp-editor-landscape.png` |
| RS-04 | 640×360, 915×412 landscape phones | MAC-JVM | PASS (desktop layout tests) | `TalentTreeLandscapeLayoutTest`, `TalentTreeLandscapeSectionsUiTest` (22); runtime UNVERIFIED |
| RS-05 | 800×1280 / 1280×800 tablets | SIM-iPad (launch only) | PASS with P2 | TF-RC1-TABLET-WIDTH; Android tablet UNVERIFIED |
| RS-06 | Font scale 100 % / 150 % | EMU | PASS | `rc1-evidence/screenshots/emulator-font1.0-editor.png`, `emulator-font1.5-editor.png` |
| RS-07 | Font scale 200 % | EMU | **FAIL (P2 cosmetic)** | rank badge clipped (TF-RC1-BADGE-200); section tabs switch to number-only labels by design (`NUMBER_ONLY_FONT_SCALE`) |
| RS-08 | Touch targets ≥ 48 dp (tree nodes) | MAC-JVM | PASS | `landscapeInitialScaleStillSelectsThroughThe48dpScreenTarget`, `hitTargetsOfOneSectionNeverOverlapAtAnyZoom` |
| RS-09 | TalkBack / VoiceOver | — | UNVERIFIED | — |
| RS-10 | Contrast measurement | — | UNVERIFIED | `DesignTokenTest` checks tokens, not measured contrast |
| RS-11 | Reduced motion | MAC-JVM | PASS (common) | `TalentTreeMotionUiTest` (22); platform setting UNVERIFIED |
| RS-12 | Safe area / edge-to-edge | EMU, SIM | PASS (visual) | screenshots: content clear of status/nav bars |

## 11–12. Platform packaging

| ID | Check | Env | Status | Evidence |
|---|---|---|---|---|
| PK-A1 | `assembleDebug`, `assembleRelease`, `bundleRelease` | MAC | PASS | E1-43/44/45 |
| PK-A2 | Release artifact signed | MAC | **FAIL (P1)** | `apksigner`: DOES NOT VERIFY (TF-RC1-SIGNING) |
| PK-A3 | Launcher icon present | MAC | **FAIL (P1)** | `icon=''` (TF-RC1-ICON) |
| PK-A4 | `lintRelease` | MAC | **FAIL (P2)** | TF-RC1-LINT |
| PK-A5 | Permissions | MAC | PASS | only `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX signature permission); no INTERNET |
| PK-A6 | Exported components | STATIC | PASS | only the launcher `MainActivity` (`exported=true`, required) |
| PK-I1 | Framework link debug (device+sim), release (device) | MAC | PASS | E1-47/48 |
| PK-I2 | `xcodebuild` sim Debug / sim Release / device Release (unsigned) | MAC | PASS | E1-51/52/53 `** BUILD SUCCEEDED **` |
| PK-I3 | Archive and signed device build | — | BLOCKED | needs an Apple Developer team and signing identity |
| PK-I4 | AppIcon | STATIC | **FAIL (P1)** | TF-RC1-ICON |
| PK-I5 | Privacy manifest | STATIC | **FAIL (P1 iOS)** | TF-RC1-IOS-PRIVACY |
| PK-I6 | Scene configuration | artifact | **FAIL (P2)** | TF-RC1-IOS-SCENES |

## 13. Performance and size (measured)

| ID | Measurement | Env | Result |
|---|---|---|---|
| PF-01 | Cold start, `am start -W` TotalTime, 5 runs (debug `.devcheck`) | EMU | 662 / 575 / 544 / 552 / 542 ms (first run straight after install) |
| PF-02 | Memory after the scenarios (`dumpsys meminfo`) | EMU | TOTAL PSS 106.6 MB; Java heap 36.5 MB; native heap 15.9 MB |
| PF-03 | Debug APK / release APK (unsigned) / release AAB | MAC | 20.1 MB / 16.9 MB / 16.8 MB |
| PF-04 | iOS `.app`: sim Debug / sim Release / device Release (unsigned, uncompressed) | MAC | 85.8 MB / 67.1 MB / 67.0 MB (binary 44.2 MB) |
| PF-05 | Physical-device cold start, tree load, spec switch, inspector, pan/zoom FPS, share-code generation, import validation, large library | — | **UNVERIFIED**. No profiler run, so no figures are stated. |

The EMU numbers are debug builds on an emulator. They indicate the absence of gross problems and are not release performance.

**Reproducible procedure for PF-05 (physical Android, release-like build):**
1. Use a release build with a debug signing key, or a profileable build.
2. Run `adb shell am start -W` ×10 after `am force-stop`. Report the median TotalTime.
3. Run `adb shell dumpsys gfxinfo <pkg> framestats` around 10 s of tree pan/zoom. Report janky frames.
4. Large library: create 300 builds (import a code repeatedly), then time Home load and one autosave with `Trace`/`System.nanoTime` logging or a Perfetto trace.

**iOS:** Xcode Instruments (App Launch, Animation Hitches) on a physical device.

## 14. Dataset freshness

| ID | Check | Env | Status | Evidence |
|---|---|---|---|---|
| DF-01 | `check_product_freshness.py` at 2026-10-16T19:37:52Z | MAC-PY (network: wago.tools) | **OUTDATED** | product `1.60.1.70374`, latest publishable and detected `1.60.1.70425` (TF-RC1-DATA) |

## 16. Automated verification summary

| Command | Env | Result | Notes |
|---|---|---|---|
| `ruff check .` | MAC-PY | PASS | ruff 0.16.9, E1-10 |
| `ruff format --check .` | MAC-PY | PASS | E1-11 |
| `mypy` | MAC-PY | PASS | 72 source files, E1-12 |
| `pytest` | MAC-PY | PASS | **1048 passed, 11 deselected** (`integration`, live network) |
| `sync_mobile_datasets.py --check` | MAC-PY | PASS | E1-20 |
| `sync_mobile_visuals.py --check` | MAC-PY | PASS | E1-21 |
| `sync_mobile_tooltips.py --check` | MAC-PY | PASS | E1-22 |
| `sync_mobile_localization.py --check` | MAC-PY | PASS | E1-23 |
| `:shared:jvmTest --rerun --no-build-cache` | MAC-JVM | PASS | **338 tests, 0 failed, 0 skipped**, 28 suites, E2-10 |
| `:composeApp:desktopTest --rerun --no-build-cache` | MAC-JVM | PASS | **663 tests, 0 failed, 36 skipped** (opt-in screenshot/recording tests, skipped by design), E2-11 |
| `:shared:iosSimulatorArm64Test --rerun --no-build-cache` | IOS-SIMTEST | PASS | **188 tests, 0 failed**, E2-12 |
| `:composeApp:assembleDebug` / `assembleRelease` / `bundleRelease` | MAC | PASS | E1-43/44/45 |
| `:composeApp:lintRelease` | MAC | **FAIL** | 1 error, 4 warnings (TF-RC1-LINT) |
| iOS compile + framework links (debug device+sim, release device) | MAC | PASS | E1-46/47/48 |
| `xcodebuild` simulator Debug, simulator Release, device Release unsigned | MAC | PASS | E1-51/52/53 |
| GitHub Actions `CI`, `mobile-ci`, `Dataset freshness` | GitHub | **BLOCKED (infrastructure)** | 0 steps executed per job (TF-RC1-CI). These are not test failures. |

Note: the first pass (E1) restored `jvmTest`, `desktopTest` and `iosSimulatorArm64Test` **from the Gradle build cache** (`FROM-CACHE`). Those results were not counted. The counts above come only from the forced second pass (E2).
