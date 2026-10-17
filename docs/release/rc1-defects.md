# M5.RC.1 — Defects, suspected risks and review items

Audit base: `main` @ `5648941` (merge of PR #32). Audit date: 2026-10-17 (UTC+7).
Evidence references (`E1-…`, `E2-…`) point to [`rc1-evidence/README.md`](rc1-evidence/README.md).

This file keeps three kinds of finding apart:

1. **Confirmed defects**: reproduced, or read directly from a build artifact or source. These are counted.
2. **Suspected risks**: plausible from code reading but not reproduced. These are **not** counted as defects.
3. **Owner / legal review items**: decisions or rights questions an audit cannot settle.

The [test matrix](rc1-test-matrix.md) lists the scenarios that could not be run (UNVERIFIED).

## Counts (confirmed only)

| Priority | Count | IDs |
|---|---|---|
| P0 | 0 | — |
| P1 | 4 | TF-RC1-ICON, TF-RC1-SIGNING, TF-RC1-DATA, TF-RC1-IOS-PRIVACY (iOS only) |
| P2 | 8 | TF-RC1-LINT, TF-RC1-IOS-SCENES, TF-RC1-COPY-NAME, TF-RC1-DRAFT-TITLE, TF-RC1-BADGE-200, TF-RC1-TABLET-WIDTH, TF-RC1-ANDROID-CONFIG, TF-RC1-CI |

**M5.RC.2b status (2026-10-18).** TF-RC1-LINT is **RESOLVED**. TF-RC1-SIGNING is **implemented** and
stays OPEN until the owner's key signs a verified APK. TF-RC1-ANDROID-CONFIG is partly resolved
(backup off; the other items stay open). M5.RC.2b adds **TF-RC2B-IOS-NOTICES** (iOS only, below),
now **RESOLVED**: the iOS About screen lists the framework's notices.
Evidence: [rc2b-evidence](rc2b-evidence/README.md), [ios-oss-notices](rc2b-evidence/ios-oss-notices.md).

No P0 was found. The audit found no evidence of saved-build corruption or silent loss.
The device-level checks behind that statement ran on an Android emulator only (see the matrix); the physical-device run is UNVERIFIED.

---

## P1 — Release blockers

### TF-RC1-ICON — Application icon missing

| Field | Value |
|---|---|
| Severity | **P1** |
| Platforms | Android + iOS |
| Status | **OPEN**. M5.RC.1: known, pre-existing. M5.RC.2a: approved artwork integrated; stays OPEN until platform rendering is verified (see below) |
| Owner | Product/Design (artwork), Mobile engineering (integration) |
| Planned resolution | **M5.RC.2a** — TalentForge branding and app icon |

**Summary.** TalentForge ships no application icon on either platform.

**Evidence.**
- Android source: `mobile/composeApp/src/androidMain/AndroidManifest.xml` has no `android:icon` or `android:roundIcon`. `mobile/composeApp/src/androidMain/res/` contains only `values/themes.xml`: no `mipmap-*`, no `mipmap-anydpi-v26/ic_launcher.xml`.
- Android artifacts (E1-60): `aapt2 dump badging` of both the debug APK and the release APK reports `application: label='TalentForge' icon=''` and `launchable-activity … icon=''`.
- Android lint (E2-13): `AndroidManifest.xml:4: Warning: Should explicitly set android:icon, there is no default [MissingApplicationIcon]`.
- iOS source: `mobile/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` contains only `Contents.json`, with one `universal / ios / 1024x1024` slot and no `filename`, so there is no image. `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon` (E1-50).
- iOS artifact (E1-60): the Release simulator `.app` has an 18 KB `Assets.car` and no icon files.

**Reproduction.** Build `:composeApp:assembleRelease`, then run `aapt2 dump badging composeApp-release-unsigned.apk | grep icon`. On iOS, open `AppIcon.appiconset` in Xcode and see the empty 1024 slot.

**Expected.** The launcher and Home Screen show the approved TalentForge icon. The RuStore listing has a 512×512 store icon.
**Actual.** Android launchers fall back to the system default icon. iOS shows a blank placeholder icon.

**Required assets.** These are listed in full in the [release checklist §4](rc1-release-checklist.md#4-app-icon-readiness-tf-rc1-icon).

**Suggested resolution (M5.RC.2a).** Integrate the *approved* design only:
- Android: an adaptive icon in `mipmap-anydpi-v26/ic_launcher.xml` (foreground, background and monochrome layers), legacy densities, and `android:icon` in the manifest.
- iOS: the 1024×1024 master in `AppIcon.appiconset`, or an Icon Composer `.icon`, with dark/tinted variants as the design provides.

**Icon policy (applies to M5.RC.1 → M5.RC.2a).**
1. **M5.RC.1 generates and integrates no icon.** This milestone only records the gap; `TF-RC1-ICON` stays **OPEN / P1**.
2. **M5.RC.2a requires original TalentForge artwork that the owner has approved** before it is integrated.
3. **AI-assisted concept creation is allowed**, provided the result is approved by the owner and passes a rights review (originality, the tool's terms and output rights, no resemblance to third-party marks) before integration.
4. **Not allowed:** Blizzard or World of Warcraft logos or artwork (class/talent icons included), any other borrowed or unauthorized artwork, downloaded stock icons without a cleared license, and placeholder icons shipped in a release build.

**M5.RC.2a progress.** The owner-approved concept #3 is integrated byte-for-byte from the approved asset package: the Android adaptive icon (background, foreground and monochrome layers, raster fallbacks, `android:icon`) and the iOS 1024×1024 `AppIcon`. Source, checksums, asset QA and the build/rendering checks are in [rc2a-evidence/](rc2a-evidence/README.md). Verified with unsigned builds:
- `aapt2` shows the packaged adaptive icon in the debug and release APKs.
- `MissingApplicationIcon` is gone from lint.
- The iOS simulator builds have no icon warnings, and `AppIcon` is in `Assets.car`.
- The icon renders on the API 35 emulator under the circle mask (drawer, Home, themed on) and on the iOS 26.4 simulator Home screen (light and dark).

**Status stays OPEN.** Still outstanding: the signed-release check, the physical devices (TECNO HiOS, iPhone, iPad), API 26 and 36, iOS tinted/clear appearances, and Archive → Validate App. The integration adds 6 lint warnings (`ObsoleteSdkInt`, `IconLauncherShape` ×5), recorded under TF-RC1-LINT.

**Verification.**
1. `aapt2 dump badging` on the signed release APK shows a non-empty `icon=` value.
2. Lint shows no `MissingApplicationIcon`.
3. Visual check of the launcher on Android 8, 13+ (themed icons) and 16, and on a round-mask launcher.
4. The Xcode archive shows no AppIcon warnings, and the icon renders on the simulator and on a device.
5. On iOS, App Store Connect validation passes for the icon.

---

### TF-RC1-SIGNING — Android release artifact is unsigned; no release signing configuration

| Field | Value |
|---|---|
| Severity | **P1** (packaging prerequisite that blocks publication) |
| Platforms | Android (RuStore) |
| Status | **OPEN — engineering implemented in M5.RC.2b; closes when the owner's key signs an APK that passes `assemblePublishableRelease`** |
| Owner | Owner / Release engineering (key custody is an owner decision) |
| Planned resolution | M5.RC.2b (implementation, done) → owner key and first verified signed APK before M5.RC.3 |

**Evidence.**
- E1-60: `apksigner verify composeApp-release-unsigned.apk` reports `DOES NOT VERIFY` and `ERROR: Missing META-INF/MANIFEST.MF`.
- `mobile/composeApp/build.gradle.kts` defines no `signingConfigs` and no release signing.

**Expected.** A release APK, or an AAB plus a RuStore-uploaded signing key, signed with a long-lived key held by the owner.
**Actual.** Unsigned output.

**Notes.**
- RuStore requires a signed APK. For AAB, the signing key is uploaded separately during upload.
- Changing the key later requires RuStore support, and all existing versions are archived.
- Pick the key once and keep it.
- No keys were created during this audit.

**Resolution.**
- Generate and store a release keystore outside the repository.
- Wire it through `signingConfigs` read from local or environment properties, never committed.
- Decide APK vs AAB for RuStore.

**Verification.** `apksigner verify --print-certs` on the release APK succeeds. The certificate fingerprint is recorded in the release notes (not in the repo).

**M5.RC.2b implementation.** See the [owner guide](android-release-signing.md).
- `build.gradle.kts` reads the four signing settings from the environment or `~/.gradle/gradle.properties`.
- No settings: the unsigned technical build, as before. All four: signed.
- Partial or invalid settings, a keystore inside the checkout, the debug keystore, or a committed `talentforge.release.*` key: every release build fails, naming the settings without values.
- The release build type never uses the debug key.
- `:composeApp:assemblePublishableRelease` is the only upload path. It requires the complete settings plus the owner's certificate SHA-256, and stages `TalentForge-<versionName>-<versionCode>.apk` only after `scripts/verify_release_apk.py` passes. That covers: apksigner verify, one signer, not the debug certificate, fingerprint match, RSA ≥ 4096, frozen `applicationId`, versions, not debuggable, universal APK with `arm64-v8a`, and the APK SHA-256 in a secret-free report.
- Verified with a **temporary test key** only: 14/14 self-test scenarios pass, including the PR #38 review case (a failed publishable attempt leaves no earlier APK staged) ([evidence](rc2b-evidence/README.md#signing-self-test)).

**Still open (owner):** create and back up the key, configure the settings, and build and verify the first signed APK.

---

### TF-RC1-DATA — Product dataset is OUTDATED against the latest publishable Forever build

| Field | Value |
|---|---|
| Severity | **P1 release gate** (closed by a refresh *or* a documented owner decision) |
| Platforms | Android + iOS (shared dataset) |
| Status | OPEN |
| Owner | Data pipeline / Product |
| Planned resolution | Dedicated dataset-refresh milestone (proposed **M5.DATA.4**) before M5.RC.3 |

**Evidence (E1-30, `release-freshness.json`).**
- Check timestamp: **2026-10-16T19:37:52Z**.
- Source: wago.tools `/api/builds`.
- Command: `python scripts/check_product_freshness.py --output release-freshness.json`, exit code 3.

| Field | Value |
|---|---|
| status | **OUTDATED** |
| PRODUCT_BUNDLE | `1.60.1.70374` |
| latestPublishable | `1.60.1.70425` (`wow_classic_beta`, created 2026-10-16 00:50:05, `isBgdl=false`) |
| latestDetected | `1.60.1.70425` |
| newerUnpublishable | none |

**Limitations.**
- "Publishable" means `is_bgdl=false` on wago.tools. It is **not** proof that `70425` is the live client.
- The product is listed as `wow_classic_beta`.
- Whether `70425` changes any talent data is unknown until it is normalized and diffed.
- The scheduled GitHub freshness watcher is infrastructure-blocked (TF-RC1-CI), so it gives no corroboration.

**Resolution.**
- Run the established refresh pipeline (M5.DATA.3 precedent): normalize `70425`, diff it against `70374`, add it to `BUNDLED_BUILDS`, and decide on `PRODUCT_BUNDLE`.
- Never remove `70009/70245/338/374`, which saved builds may reference.
- Alternatively, the owner records why `70374` stays the product build.
- Do **not** update the bundle silently, and do not update it in this PR.

**Verification.** Re-run the freshness check immediately before M5.RC.3. It must report `CURRENT`, or a signed-off decision must be recorded in `rc3` notes.

---

### TF-RC1-IOS-PRIVACY — No privacy manifest (`PrivacyInfo.xcprivacy`) in the iOS app

| Field | Value |
|---|---|
| Severity | **P1 for iOS App Store submission**; not applicable to the Android/RuStore release |
| Platforms | iOS |
| Status | OPEN (absence confirmed; App Store rejection not observed — no upload was possible) |
| Owner | Mobile engineering |
| Planned resolution | M5.RC.2b (before the iOS release, not needed for RuStore) |

**Evidence.**
- `find mobile/iosApp -name '*.xcprivacy'` returns nothing (static review), and the Release `.app` bundle has no `PrivacyInfo.xcprivacy` (E1-60).
- The app stores the build library in `NSUserDefaults` (`IosBuildLibraryStorage.kt`), a required-reason API category.
- JetBrains documents that Compose Multiplatform binaries may contain `stat`/`fstat` (reason `0A2A.1`) and `mach_absolute_time` (`35F9.1`) usages that Apple can flag.
- Apple states: "Starting May 1, 2024, apps that don't describe their use of required reason API in their privacy manifest file aren't accepted by App Store Connect."

**Resolution.**
- Add an app-level `PrivacyInfo.xcprivacy` declaring the User Defaults category and the categories the Compose and Kotlin binaries use.
- Confirm the exact reason codes against Apple's current documentation at implementation time. This audit could not render Apple's reason table, so the codes for User Defaults are **unverified here**.
- Declare `NSPrivacyTracking = false` and no collected data types.

**Verification.** Upload a signed archive to App Store Connect (TestFlight). There must be no ITMS-91053 email or validation error.

---

## P2 — Non-blocking

### TF-RC1-LINT — `:composeApp:lintRelease` fails on `RememberReturnType`

- **Platform:** Android build tooling.
- **Evidence (E1-49, E2-13):**
  - `AppLanguage.android.kt:15: Error: remember calls must not return Unit [RememberReturnType]` on `remember { primaryLanguageTagOf(…) }`. The expression is typed `String`, so this looks like a lint false positive (UAST/K2).
  - 4 warnings: `ApplySharedPref`, `UseKtx`, `OldTargetApi` (targetSdk 36), `MissingApplicationIcon`.
  - `lintVitalRelease` inside `assembleRelease` passes, so packaging is not blocked.
- **Expected:** a full lint run is clean or has a baseline. **Actual:** BUILD FAILED.
- **Resolution:** confirm the false positive and suppress it locally with a justification, or restructure the expression. Add a lint baseline for intentional warnings. `commit()` is a deliberate fail-closed choice: keep it and document it.
- **Verification:** `./gradlew :composeApp:lintRelease` exits 0.
- **M5.RC.2b: RESOLVED at the root cause, with no suppression, no baseline and no disabled check.**
  - Lint's release model contains only the `androidMain` sources of this KMP module.
  - Its bundled Kotlin analysis also rejects the module's Kotlin 2.4 metadata: the lint log reports "expected version is 2.2.0".
  - So the `commonMain` call `primaryLanguageTagOf(…)` that ended the `remember` lambda had no type for lint.
  - The compose-runtime `RememberDetector` then read the `remember` call as `Unit`.
  - Fix: the lambda now calls an `androidMain` helper with a declared `String` type. Same value, same remember-once behaviour.
  - `lintRelease`: **0 errors, 9 warnings**, all pre-existing (risk assessment in the [evidence](rc2b-evidence/README.md#lint)). `DataExtractionRules`, raised by the new backup rules, was fixed by adding the API 26–30 rules, not suppressed.
- **M5.RC.2a update:** `MissingApplicationIcon` is resolved. The icon integration adds 6 warnings (`ObsoleteSdkInt` on `res/mipmap-anydpi-v26`, `IconLauncherShape` on the 5 full-bleed raster fallbacks). These are icon regressions, not part of the pre-existing set. The pre-existing `RememberReturnType` error still fails the task. Current report: 1 error, 9 warnings ([rc2a-evidence/mac/lint](rc2a-evidence/mac/lint/lint-results-release.txt)).

### TF-RC1-IOS-SCENES — Built Info.plist enables multiple scenes despite the source plist disabling them

- **Platform:** iOS (iPad).
- **Evidence (E1-60):**
  - Source `iosApp/Info.plist` declares `UIApplicationSupportsMultipleScenes = false`.
  - The built Release `.app` Info.plist contains `"UIApplicationSupportsMultipleScenes" => true` (from `INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES`).
- **Risk (suspected, not reproduced):**
  - On iPad, two windows each compose their own `App`, each with its own `BuildAutosaveCoordinator` and write lock, over the same `NSUserDefaults` key.
  - The repository does a whole-library read–modify–write. Editing the same build in two windows ends as last-writer-wins.
  - Overlapping writes could drop an unrelated record's update.
- **Classification:** the *configuration mismatch* is the confirmed P2 defect. The data-loss scenario stays a *suspected* risk (SR-2) until it is reproduced; it is not counted as a defect.
- **Release gate (iPad, M5.RC.3).** The persistence model is a whole-library read–modify–write with one write coordinator per `App` composition. M5.RC.3 therefore **must not approve iPad multi-window support without verification**. One of the following must be true before an iOS RC is accepted:
  - **(a)** multiple scenes are **disabled** for the first release, and the **built** `Info.plist` of the release archive shows `UIApplicationSupportsMultipleScenes = false` (checking the source plist is not enough, see the evidence above); **or**
  - **(b)** cross-scene-safe persistence is implemented and tested (for example one process-wide serialized writer, or compare-and-swap on the stored payload) **in a separately reviewed change**, with a two-window iPad test showing that neither concurrent edits nor edits to different builds lose data.
- **Resolution:** option (a) is the smaller change for v1 (`INFOPLIST_KEY_UIApplicationSupportsMultipleScenes = NO`, or drop the scene-manifest generation key).
- **Verification:** `plutil -p <archive>.app/Info.plist | grep UIApplicationSupportsMultipleScenes`, and on an iPad simulator check that a second window cannot be opened; for option (b), the two-window scenario.

### TF-RC1-COPY-NAME — Duplicate names are persisted in English (" (copy)", "Draft (copy)") in the Russian UI

- **Platform:** both.
- **Evidence:** `LocalBuildRepository.copyNameOf` uses the constants `" (copy)"` and `"Draft"`. This is documented as a known limitation in `docs/mobile-architecture.md` (M5.REL.2a and M5.REL.2b "Known limitations").
- **Repro:** set the device to Russian, then All Builds → ⋯ → Duplicate. The new row is named `… (copy)`.
- **Resolution:** a localized copy-name policy. This is a persistence change and needs its own decision.
- **Verification:** a unit test plus a RU device check.

### TF-RC1-DRAFT-TITLE — The same unnamed build is titled differently on Home/All Builds and in the editor

- **Platform:** both.
- **Evidence (E2-23 screenshots `rc1-evidence/screenshots/emulator-font2.0-home.png`, `rc1-evidence/screenshots/emulator-compact-568x320dp-editor-landscape.png`):**
  - Home shows the unnamed record as **"Warrior build"**: the display-only `<Class> build` fallback (M5.UI.1).
  - The editor header shows **"Draft"** (`editor_draft_title`).
  - Duplication persists **"Draft (copy)"**.
- **Expected:** one consistent naming rule. **Actual:** three names for one record.
- **Resolution:** a product/design decision. Then align `TalentTreeBuildScreen` with `BuildCardModel.title`, or document the rule.
- **Verification:** UI tests for an unnamed build's title on Home, All Builds and the editor.

### TF-RC1-BADGE-200 — Rank badge text clipped at 200 % font scale

- **Platform:** Android (observed on the emulator). iOS is not checked.
- **Evidence:** E2-23 `rc1-evidence/screenshots/emulator-font2.0-editor.png`: node badges `0/3` and `0/5` are vertically cut. At 150 % they are intact (`rc1-evidence/screenshots/emulator-font1.5-editor.png`).
- **Cosmetic:** the counter, inspector and actions stay usable.
- **Resolution:** a minimum badge height that scales with font, or a cap on the badge font scale.
- **Verification:** compare screenshots at 1.0, 1.5 and 2.0 scale.

### TF-RC1-TABLET-WIDTH — Tablet Home stretches content and primary actions to full width

- **Platform:** iOS iPad (simulator, E2-31). Android tablets are not checked.
- **Evidence:** `rc1-evidence/screenshots/ios-ipad-air-11-launch.png`: "New build" spans the full 1640 px width and the body text runs edge to edge.
- **Cosmetic:** no functional loss.
- **Resolution:** a max content width for shell screens on wide windows, following the B+ design system.

### TF-RC1-ANDROID-CONFIG — Android release configuration not hardened

These are owner decisions. None of them blocks RuStore by itself.

**Evidence (E1-60, `build.gradle.kts`, manifest):**
- No R8/minify or resource shrinking. The release APK is 16.9 MB.
- 4 ABIs are bundled (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- 85 `application-label-*` locales come from dependencies, while the app supports en/ru only.
- `android:allowBackup` is unset (it defaults to `true`), so the SharedPreferences library is included in cloud backup and device transfer. Disclose this in the privacy text or set it explicitly.
- No `localeConfig`, so there is no Android 13+ per-app language entry.
- `targetSdk = 36` triggers lint `OldTargetApi`.
- `versionCode = 1` / `versionName = "0.1.0"`, pending a release decision.

**Resolution:** decide each item in M5.RC.2b. Any R8 enablement needs a full device regression.

**M5.RC.2b:**
- `allowBackup` is **decided and implemented**:
  - `android:allowBackup="false"`;
  - `dataExtractionRules`, Android 12+: every domain excluded from `cloud-backup` and `device-transfer`;
  - `fullBackupContent`, API 26–30: every domain excluded.
- Verified in the merged release manifest; the privacy text is updated.
- Vendor migration tools that bypass the Android backup framework are outside the app's control (UNVERIFIED on Huawei, H-12).
- **Still open (owner decisions, not changed here):** R8/minify, ABI filtering (all four ABIs ship), `localeFilters`/`localeConfig`, `targetSdk` (`OldTargetApi`), first public `versionCode`/`versionName`.

### TF-RC2B-IOS-NOTICES — iOS open-source notices not compiled (added in M5.RC.2b) — **RESOLVED**

- **Severity:** **P1 for an iOS App Store release**; not applicable to the Android (RuStore/AppGallery) release.
- **Evidence (original):**
  - The iOS framework links `org.jetbrains.skiko` (`iosArm64CompileKlibraries`). Skiko statically links the Skia graphics library and Skia's third-party code.
  - Their licences (BSD-3-Clause for Skia, plus others) require their notices in the distribution. No notice text ships in the skiko klibs.
  - The Android notices generated in M5.RC.2b do not cover them.
- **Resolution:**
  - `scripts/generate_oss_notices.py` now also generates `files/legal/oss-notices-ios.json` (schema `talentforge.oss-notices.v2`, shared with Android). It contains:
    - the 80 external modules of `:composeApp:iosArm64CompileKlibraries` (POM licences verified);
    - 12 *linked* components (third-party code that is not a Gradle module):
      - Skia, HarfBuzz, ICU, libjpeg-turbo, libpng, libwebp, Wuffs, zlib (Chromium), Expat, Adobe DNG SDK and Piex, inside `skiko-iosarm64:0.9.37.4`;
      - the Kotlin standard library's third-party code (ThreeTen, GWT, Guava).
  - 16 licence and notice texts are bundled verbatim from pinned upstream revisions and SHA-256-pinned. The IJG and Adobe DNG attribution statements appear verbatim.
  - The iosMain `platformOpenSourceNoticesPath` points at the file.
  - Full evidence (archive inventory, symbol and version strings, Skia DEPS pins, licence provenance): [rc2b-evidence/ios-oss-notices.md](rc2b-evidence/ios-oss-notices.md).
- **Drift check (fail-closed):** `generate_oss_notices.py --check --platform ios` runs in `mobile-ci` (macOS job). It fails when any of these changes:
  - the klib module set;
  - any static archive or object-file family inside a klib that the curated inventory of that exact module version does not claim (a new skiko version therefore fails until it is established);
  - any licence text's hash.
- **Verification:**
  - Desktop UI test renders the iOS notices: linked components, attributions, 16 licence toggles.
  - Strict-parser tests, offline generator tests, `linkReleaseFrameworkIosArm64`.
  - The iOS simulator build shows the list and licence texts on the About screen ([evidence §4](rc2b-evidence/ios-oss-notices.md#4-on-the-ios-simulator)).
  - A physical-device look remains part of the M5.RC.3 iOS runs.
- **Open items for legal (LR-4), not defects of the notice list:**
  - the OFL reference in Skia's embedded SpiderSymbol probe font;
  - Adobe DNG SDK §5 indemnification;
  - whether the K/N runtime's `mpmc_queue` code ships;
  - the Android notices lack the ThreeTen text for kotlin-stdlib's `kotlin.time`.

  See [§5 of the evidence](rc2b-evidence/ios-oss-notices.md#5-open-items-not-resolved-here-ownerlegal-review).

### TF-RC1-CI — GitHub Actions is infrastructure-blocked

- **Platform:** process.
- **Evidence (E1-03):** the last 40 runs since at least 2026-10-13 (`CI`, `mobile-ci`, `Dataset freshness`, on main, PR and schedule) all have `conclusion=failure` with **0 steps executed** per job.
- These are not test failures. CI simply provides **no** verification at present, and every result in this audit comes from local Mac runs.
- **Resolution:** the owner checks the Actions billing, spending limit and runner availability, then re-runs `mobile-ci` on `main`.
- **Verification:** a green `mobile-ci` and `CI` on the RC.3 commit.

---

## Suspected risks (not counted; need confirmation)

| ID | Risk | Basis | How to confirm |
|---|---|---|---|
| SR-1 | Unlocked library writes. `deleteBuild`, `duplicateBuild`, `open` (touch) and `createDraft` run on `Dispatchers.Default` **outside** `BuildAutosaveCoordinator.writeLock`. Safety relies on the invariant that leaving the editor first `flush()`es. A future entry point that writes while an autosave is pending would lose an update. | Code: `AllBuildsScreen.kt:194`, `BuildActionsPresenter.kt:214`, `BuildImportController.kt:177`, `BuildLoaders.kt:171,321` vs `BuildAutosaveCoordinator.kt` | Stress test with a held storage plus concurrent duplicate/delete; or route all writes through one serialized writer |
| SR-2 | iPad multi-window last-writer-wins / lost update (see TF-RC1-IOS-SCENES) | Built plist + per-window coordinator | iPad two-window manual test |
| SR-3 | Import confirm during Activity recreation: phase/preview is `remember`, not saveable. A rotation while `createDraft` is in flight could return to Editing after the record was written, and a second Import would create a second record. The window is milliseconds. | `ImportBuildScreen.kt` / `BuildImportController.kt` | Held-storage UI test with state restoration |
| SR-4 | iOS storage write failures are undetectable. `NSUserDefaults.setObject` neither throws nor reports, so the typed `StorageWrite` / Retry path is unreachable on iOS and persistence relies on `cfprefsd`. | `IosBuildLibraryStorage.kt` | Device test (low disk) plus `synchronize()` evaluation |
| SR-5 | Large libraries: every operation re-reads and re-encodes the whole library (O(n) JSON per tap-save) | `LocalBuildRepository` | Seed 500+ builds and time autosave/open (procedure in the matrix) |

## Owner / legal review items (not defects; must be decided before publication)

| ID | Item | Category |
|---|---|---|
| LR-1 | Talent icons are extracted from the Blizzard game client (BLP, `docs/talent-visuals.md`), and talent names and descriptions are Blizzard game text. Their redistribution in a store app is **not** established as permitted. | Potential legal risk — owner/legal review |
| LR-2 | "World of Warcraft", "Forever" and class names are Blizzard trademarks. The app and listing have no non-affiliation disclaimer, and no about/legal surface exists in the app. **M5.RC.2b:** the About screen shows the trademark-free draft statement (RU/EN); still not legally reviewed. | Potential legal risk — needs disclaimer decision (OPEN) |
| LR-3 | The provenance and license of the 9 class artwork masters (`composeResources/drawable/class_*.png`) are not recorded in the repo. | Owner review |
| LR-4 | No open-source notices or attribution surface (Kotlin, Compose Multiplatform, AndroidX, kotlinx are Apache-2.0). No `LICENSE` in the repo. **M5.RC.2b:** Android in-app notices (generated, 122 modules, Apache-2.0 text). iOS in-app notices (generated: 80 modules plus Skia and 11 other linked components, 16 verbatim licence texts; TF-RC2B-IOS-NOTICES resolved). Open legal points: [ios-oss-notices §5](rc2b-evidence/ios-oss-notices.md#5-open-items-not-resolved-here-ownerlegal-review). | Potential compliance gap — owner/legal sign-off (OPEN) |
| LR-5 | Privacy: no network, no account, data stays on the device (plus Android auto-backup, TF-RC1-ANDROID-CONFIG). Store forms still need answers: RuStore "Data Safety", Apple privacy nutrition label, and a privacy-policy URL. Apple's requirement for a URL is an assumption not re-verified in this audit. **M5.RC.2b:** Android backup off; in-app privacy summary; policy link slot empty until a URL is approved. | Owner action (OPEN) |
