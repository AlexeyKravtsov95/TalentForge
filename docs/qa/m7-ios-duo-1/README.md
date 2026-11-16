# M7.IOS.DUO.1 — iPhone Duo validation

**Overall release gate: BLOCKED.** The official simulator works and the 24 screenshot
files pass validation. This is not a declaration of complete Duo compatibility: native
two-finger pinch and the full functional cross-product still need owner/device verification.
No App Store Connect upload, App Review submission, merge or publication was performed.

## Environment and source

Validation date: 2026-11-16 (Asia/Novosibirsk). Base: `origin/main`,
`4797bcd3` (see full immutable source SHA in the screenshot manifest).
The unchanged iOS identity is `io.github.alexeykravtsov95.talentforge`, **1.0.0 (4)**.
This milestone prepares 1.1.0; it does not authorize a version/build change.

- Xcode **27.1 (27A9275)**; SDK `iphonesimulator27.1`.
- Runtime **iOS 27.1 (24A94425)**, arm64 simulator, Debug, OTA OFF.
- Official `com.apple.CoreSimulator.SimDeviceType.iPhone-Duo`.
- Simulator UDID `DF33DB4D-7AD6-460C-8154-C0FA21FA06FE`.
- Native DeviceHub **Closed / Open / Rotate Right** controls were used; no other
  iPhone was resized to impersonate Duo. Device transitions ran in the same app process.
- `simctl io … enumerate` exposed real framebuffer ports for 1398×2034 and
  2007×2853. Captures selected the active display UUID explicitly.
- EN submission screenshots use source `4797bcd3` (full SHA per entry);
  RU submission screenshots and additional landscape evidence use
  `7eab87d57cb35dbb4a52245e8dc84af006c2840e` (the dark system UI fix).
  Subsequent commits change only validation, tests, graph and release evidence.

[Apple's Duo preparation guidance](https://developer.apple.com/iphone-duo/prepare/)
requires testing actual resizing and asymmetric safe areas.
[Apple screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)
list the two Duo sizes and their landscape transposes (checked 2026-11-16).

## Layout result matrix

**PASS below means the rendered editor/inspector layout was observed at that viewport,
not that every feature and setting permutation received native end-to-end coverage.**
The six submission screens are captured in portrait in each display/locale combination.

| Display | Locale | Orientation | Native pixels | Layout | Evidence |
|---|---|---|---|---|---|
| Outer | EN | Portrait | 1398×2034 | PASS | [Editor](../../release/apple-appstore/submission/screenshots/iphone-duo/outer/en-US/01-editor.png) |
| Outer | RU | Portrait | 1398×2034 | PASS | [Editor](../../release/apple-appstore/submission/screenshots/iphone-duo/outer/ru/01-editor.png) |
| Inner | EN | Portrait | 2007×2853 | PASS | [Editor](../../release/apple-appstore/submission/screenshots/iphone-duo/inner/en-US/01-editor.png) |
| Inner | RU | Portrait | 2007×2853 | PASS | [Editor](../../release/apple-appstore/submission/screenshots/iphone-duo/inner/ru/01-editor.png) |
| Outer | EN | Landscape | 2034×1398 | PASS | [Inspector](outer-en-US-landscape.png) |
| Outer | RU | Landscape | 2034×1398 | PASS | [Requirements](outer-ru-landscape.png) |
| Inner | EN | Landscape | 2853×2007 | PASS | [Inspector](inner-en-US-landscape.png) |
| Inner | RU | Landscape | 2853×2007 | PASS | [Requirements](inner-ru-landscape.png) |

Observed: portrait bottom inspector; landscape side inspector; circular/square icon
proportions preserved; reachable rank controls; no content under the camera/system
indicator region; no crash while folding or rotating. Canvas content can extend beyond
the viewport by design and is not treated as a layout overflow.

## Functional evidence and remaining checks

| Area | Native observation | Automated coverage / remaining work |
|---|---|---|
| Home / onboarding | Empty EN Home, How to use; populated RU/EN Home | Existing shell/localization tests; full native onboarding matrix remains unexecuted |
| Class / specialization | RU/EN nine-class chooser, Paladin/Holy editor | Real Warrior/Fury resize regression checks specialization and selection |
| Inspector / requirements | Selected rank 5/5; RU prerequisite and tier reasons; Show selects exactly one prerequisite without changing 5/51 | Existing inspector, allocation, gesture and localization suites |
| Outer → Inner → Outer editor | Same build, Holy, selected Divine Strength and 5/51 retained across native fold/rotation | New test also verifies unsaved callback state and Undo history, in-place |
| Library / scroll | Four locally created/imported/duplicated builds; scrolled list returns at its prior position after native Outer → Inner → Outer | New RU/EN 30-build test preserves query, row position and storage bytes across all four sizes |
| Search / filters / sort / grouping | Favorites, Paladin filter and class grouping exercised | Query and all sort choices covered by Compose tests; native text entry/search and all sort permutations still need verification |
| Favorites | Add and filter observed, persisted across app relaunch | Existing removal/storage tests; native removal still needs verification |
| Save / open / import / export | Spent 5 points, exited/reopened; copied real TF1 code, pasted, previewed and imported as separate build; native share popover opened and dismissed without sending | Existing autosave, sharing and import suites; native delete confirmation still needs verification |
| About / Help / OTA | EN How to use; RU About shows app 1.0.0 and bundled data | OTA is compiled OFF; there is no enabled OTA status screen to claim tested |
| Dynamic Type | Native `large` and `accessibility-extra-extra-extra-large` tested; RU editor/inspector and EN Home checked at the large category | Supplemental 100%/200% RU/EN editor/share tests at all four sizes; exhaustive native settings cross-product remains unexecuted |
| System appearance | Light and dark exercised; product palette remains fixed dark | Fixed native system UI contrast; native light theme for app content is not part of the existing design system |
| Pinch / pan | Pointer drag attempted; two-finger pinch not delivered by this control interface | Existing Compose gesture/viewport tests pass; **native pinch/pan acceptance BLOCKED** |

Apple documents DeviceHub's two-finger input limitation in
[Xcode 27 release notes](https://developer.apple.com/documentation/Xcode-Release-Notes/xcode-27-release-notes)
(issue 169537162). Its [interaction documentation](https://developer.apple.com/documentation/xcode/interacting-with-your-app-in-device-hub)
describes the official fold controls. Desktop pinch tests do not establish native
Compose-on-Duo gesture compatibility. Complete that check with an input path that
supports native multi-touch before approving the optimization claim.

## Defects and changes

1. **Fixed: system indicator contrast with light system appearance.** The Compose palette
   is fixed dark, and SwiftUI already requested dark appearance, but a cold launch in
   light system appearance showed black indicators on the dark background. Declaring
   `UIUserInterfaceStyle=Dark` in the iOS host plist restored white indicators on the
   official simulator. The project verifier and three negative cases guard this setting.
   No business logic moved to Swift and no light palette was invented.
2. Extended the existing screenshot validator with optional complete Duo packs,
   `--require-duo`, display-specific portrait/landscape sizes, the six required screens,
   locale parity, required capture provenance, and duplicate-manifest rejection.
   Existing iPhone/iPad packs remain valid without Duo files.
3. Added in-place editor and library resize regressions. Extended existing editor
   overflow and import/share action-reachability matrices with the two Duo point-size
   equivalents and their transposes. No new test framework or runtime dependency.

## Screenshot pack and upload decision

[Submission manifest](../../release/apple-appstore/submission/screenshots/manifest.json)
contains all 24 Duo images, exact dimensions/bytes, SHA-256 of each delivered PNG,
SHA-256 of the raw capture, app version/build, source commit, Xcode, SDK, runtime,
UDID, locale and capture method. Existing 18 iPhone/iPad files and their provenance
are preserved. [Landscape manifest](landscape-manifest.json) records the four extra QA images.

| Order | File suffix | Content |
|---|---|---|
| 01 | editor | Talent Editor |
| 02 | talent-details | Expanded Talent Details |
| 03 | class-selection | Class Selection |
| 04 | build-library | Build Library |
| 05 | search-favorites | Favorites filter in Search / Library |
| 06 | build-sharing | Real exported build code |

Browse [Outer EN](../../release/apple-appstore/submission/screenshots/iphone-duo/outer/en-US/),
[Outer RU](../../release/apple-appstore/submission/screenshots/iphone-duo/outer/ru/),
[Inner EN](../../release/apple-appstore/submission/screenshots/iphone-duo/inner/en-US/),
[Inner RU](../../release/apple-appstore/submission/screenshots/iphone-duo/inner/ru/).

Capture command: `xcrun simctl io <UDID> screenshot --display <framebuffer-UUID> <raw.png>`.
Outer port: `F27A8E74-7ACE-443E-AAC2-5D85814C005F`; Inner port:
`42788329-0287-4155-AC6E-B3EB52304C46`.
Raw captures were fully opaque RGBA (alpha extrema 255/255). Only that redundant alpha
channel was removed; decoded RGB bytes were checked unchanged. No generation,
retouching, compositing, cropping, resolution substitution or stretching was used.

**Files are technically suitable PNG assets for the documented App Store Connect slots.**
They show the current 1.0.0 (4) app, not a nonexistent 1.1.0 build. **Hold upload/optimized-Duo
claims until native gesture and remaining acceptance checks are closed and the owner
reviews the pack.** No claim of App Store Connect server acceptance is made.

## Validation

- `ruff check .`: PASS.
- `ruff format --check .`: PASS, 485 files.
- `mypy`: PASS, 125 source files.
- `python3 scripts/sync_mobile_datasets.py --check`: PASS, 50 files / 5 builds.
- `python3 scripts/verify_appstore_screenshots.py --require-duo`: PASS, all 42 images;
  [machine report](screenshot-validation.json).
- `pytest tests/unit/test_verify_appstore_screenshots.py tests/unit/test_verify_ios_project.py -q`:
  PASS, 84 tests.
- `./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:assembleDebug`:
  PASS (7m29s); 383 shared tests, 950 desktop tests, 64 opt-in desktop tests skipped.
- Added library resize test after that full run:
  `./gradlew :composeApp:desktopTest --tests '*BuildLibrarySortingUiTest.duo*'`:
  PASS, 1 test (internally RU and EN).
- `./gradlew :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64`:
  PASS (1m10s).
- Simulator `xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build`: PASS, also rebuilt after plist fix.
- Full Python suite and GitHub Mobile CI: final results recorded below once complete.

Execution notes: an initial validation run exhausted disk space and was stopped. Only
this task's generated intermediates and completed pytest scratch directory were removed.
A subsequent run used the shared venv's editable package from the original checkout and
reported 3230 passed / 59 setup errors (`preflight.time` mismatch); it is invalid evidence
for this worktree. The final full run explicitly sets `PYTHONPATH` to this checkout's `src`.

Android 1.1.0 (6), OTA production catalog/keys, WoW Forever datasets, bundle identifier,
iOS version/build and Guideline 4.1(a) assessment metadata are unchanged.
