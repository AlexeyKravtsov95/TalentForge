# M5.GALLERY.1 — Huawei AppGallery release readiness

| | |
|---|---|
| Milestone | M5.GALLERY.1 — Huawei AppGallery release preparation (GitHub issue #36) |
| Base | `main` @ `f7494e1ee7d863613fc43b28acff88e9458e5cb9` (merge of PR #34, M5.RC.2a) |
| Channels | RuStore and Huawei AppGallery, one Android product. **Google Play is deferred** (backlog issue #35). |
| Date | 2026-10-17 |
| Environment | owner's Mac (Apple silicon, macOS 26.3.1), JDK 17.0.19, Python 3.12.13, Android build-tools 37.0.0 ([evidence](evidence/README.md)) |
| Documents | [Signing](multi-store-signing.md) · [Testing and publication](testing-and-publication.md) · [Privacy and compliance](privacy-and-compliance.md) · [Assets](assets-checklist.md) · [Listing EN](store-listing-en.md) · [Listing RU](store-listing-ru.md) · [Release checklist §9](../rc1-release-checklist.md#9-huawei-appgallery-publication-m5gallery1) |

## 1. Executive summary

**Decision: NO-GO for publication — unchanged from M5.RC.1.** This milestone prepares AppGallery
next to RuStore; it does not close any existing gate, and it uploads or publishes nothing.

What the evidence says:

- **The package is compatible with Android devices without Google services, on static evidence.**
  It has no Google Mobile Services, Firebase, Play Billing, Play Core or HMS dependency or code
  reference, no `INTERNET` permission, a 64-bit ABI (`arm64-v8a`, which AppGallery requires for
  phones) and `minSdk 26` (Android 8.0). Runtime behaviour on a Huawei device is **UNVERIFIED**:
  no Huawei device was available.
- **The one-key, one-APK strategy satisfies both stores' current rules.** Owner-held key, signed
  universal APK uploaded byte-identical to RuStore and AppGallery, same `applicationId` and
  `versionCode`, no flavors, no store-managed signing. Store-managed signing is mandatory **only for
  AAB** in both stores; it would hand the private key to each store, so the APK is recommended.
- **New requirements found in the stores' rules,** none of which can be closed by engineering
  alone:
  - RuStore forbids third-party trademarks and names in the app name and descriptions (rule 6.4).
    The listing drafts are therefore trademark-free by default.
  - Both stores require rights to third-party content: AppGallery Review Guidelines §9, RuStore
    rule 4.1. LR-1 to LR-3 are now explicit publication gates in both stores.
  - Both stores expect an **in-app** privacy-policy link for apps that process personal data
    (AppGallery 7.1, RuStore 5.1). TalentForge's developer processes none, but whether this applies
    is an owner/legal decision.
  - RuStore recommends a Russian app name unless the name is a registered trademark (168-FZ). This
    is an owner decision.
  - The dataset gap grew: the freshness check now reports `1.60.1.70450` as the latest publishable
    build. M5.RC.1 found `70425` (TF-RC1-DATA).
- **Validation on `f7494e1`:** all four drift checks, 338 shared JVM tests and 663 desktop tests
  (36 opt-in skipped) pass. Debug, release APK and release AAB build. `lintRelease` fails only on the
  pre-existing TF-RC1-LINT error. The release APK is unsigned, so it is **not publishable** (§7).

## 2. Scope

| In scope (done) | Out of scope (not done) |
|---|---|
| Inspection of current Android config, manifest, dependencies, icons and release docs | Any product, schema, share-code or talent-data change |
| Huawei compatibility audit (static) and device test plan | Running on a Huawei device (none available) |
| Official AppGallery and RuStore requirements, with sources | Uploading, submitting or publishing anything |
| Multi-store signing strategy, APK vs AAB, Google Play compatibility notes | Generating keys, adding signing config, enabling managed signing |
| RU/EN store listing drafts, asset checklist | Producing store screenshots (they need the signed release build) |
| Privacy facts and a RU/EN privacy-policy draft, IP review status | Legal clearance (LR-1…LR-5 stay open) |
| Runnable validation script and curated evidence | HMS SDKs, HarmonyOS NEXT, ArkTS, DevEco, HAP; Google Play (#35) |

## 3. Decisions recorded (owner approval pending)

| Topic | Recommendation | Where |
|---|---|---|
| Signing | One owner-held RSA 4096 key; signed universal APK, the same file in both stores; no AGC App Signing, no RuStore key upload | [signing §1](multi-store-signing.md#1-recommendation) |
| Format | APK for the first public release | [signing §3](multi-store-signing.md#3-apk-vs-aab-for-the-first-public-release) |
| Google Play later | Provide the same key to Play before the first open/production release | [signing §4](multi-store-signing.md#4-preserving-compatibility-with-google-play-app-signing-deferred-issue-35) |
| AGC app record | Android APK, Mobile phone, category **App**, no project | [testing §2.2](testing-and-publication.md#22-creating-the-app-in-agc-) |
| Countries | Explicit list; exclude the Chinese mainland | [testing §2.9](testing-and-publication.md#29-release-countriesregions) |
| First AppGallery release | Open Testing first; then the formal version as an AGC **update** with a new APK and a higher `versionCode`, the same APK released in RuStore in the same window | [testing §6.1](testing-and-publication.md#61-open-testing--formal-production-release) |
| Listing copy | Variant A (trademark-free) by default; Variant B only after LR-2 review | [listing EN](store-listing-en.md), [RU](store-listing-ru.md) |
| Privacy | "Collect personal data = No"; publish the RU/EN policy at a public URL | [privacy §5](privacy-and-compliance.md#5-store-privacy-declarations) |

## 4. Huawei compatibility audit

Static evidence comes from the build of `f7494e1` (§7). "UNVERIFIED" means it was not executed on a
Huawei device.

| Check | Result | Evidence | Runtime on Huawei |
|---|---|---|---|
| No mandatory Google Mobile Services | ✅ none among the release runtime modules; no `com/google/android/gms` reference in dex; no `google-services.json` | `34-deps-release.txt`, `gallery1-inspection.json` | UNVERIFIED |
| No Firebase / Google Play Billing | ✅ none (dependencies and dex) | idem | — |
| No unavailable Google API in core features | ✅ core features use framework APIs only: `SharedPreferences`, `ClipboardManager`, `Intent.ACTION_SEND`, bundled resources. Start-up initializers (emoji2, lifecycle, profileinstaller) need no Google service. | source + merged manifest | UNVERIFIED (emoji2 font lookup without Google services) |
| Offline talent-tree loading | ✅ datasets, icons and texts are bundled in `assets/composeResources`; no `INTERNET` permission | APK contents, badging | UNVERIFIED (H-1, H-2) |
| Local build storage | ✅ app-private `SharedPreferences`; persistence covered by the desktop/shared tests; emulator process-death checks passed in M5.RC.1 | `AndroidBuildLibraryStorage.kt`, tests | UNVERIFIED (H-4) |
| Clipboard | ✅ `ClipboardManager`, plain text, write on Copy and read on Paste only | `BuildCodeSharing.android.kt` | UNVERIFIED (H-7) |
| `ACTION_SEND` sharing | ✅ `text/plain` chooser from the Activity; typed failure if no target | idem | UNVERIFIED (H-6) |
| Import/export | ✅ `TF1` codes, logic in common Kotlin, covered by desktop tests | `BuildImportFlowTest`, `BuildShareUiTest` | UNVERIFIED (H-8) |
| Russian and English | ✅ `values` + `values-ru` UI resources; ruRU talent text bundles; sync check PASS | `1l-sync-localization` | UNVERIFIED (H-9) |
| Adaptive icon | ✅ `android:icon` = adaptive icon (background, foreground, monochrome) from M5.RC.2a; release badging `icon='res/BW.xml'` | `40-badging-release.txt` | UNVERIFIED (H-11; TF-RC1-ICON open) |
| CPU architectures | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (the only native library is `libandroidx.graphics.path.so`); 64-bit present, as AppGallery requires | badging `native-code` | — |
| Android versions | `minSdk 26` (Android 8.0) to `targetSdk 36` | badging | UNVERIFIED on EMUI/HarmonyOS versions |
| Permissions | only the app's own signature permission `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `41-manifest-release.txt` | — |
| HMS Core needed? | **No.** No feature needs an HMS kit; none is added | — | — |
| HarmonyOS NEXT | Not targeted; no ArkTS, DevEco or HAP introduced | — | — |

## 5. Store requirements — where they are documented

- AppGallery (Android APK): account and identity verification, app creation, APK/AAB, listing
  fields, icon, screenshots, privacy URL and tags, content rating, AI declaration, review, countries,
  open testing, phased release — [testing-and-publication §2](testing-and-publication.md#2-appgallery-publication-requirements-official-sources),
  with the Android vs HarmonyOS distinction in §1 there.
- RuStore: moderation rules read for the first time (M5.RC.1 could not fetch them) —
  [testing-and-publication §3](testing-and-publication.md#3-rustore-requirements-re-checked-in-this-milestone).

## 6. Owner actions

🔑 = needs the owner's Huawei Developer account (or RuStore Console account).

| # | Action | Detail |
|---|---|---|
| O-1 | Approve the signing strategy and the APK format | [signing §11](multi-store-signing.md#11-owner-actions) S-1 |
| O-2 | Create, back up and drill-restore the release key; record the fingerprints | S-2, S-3 |
| O-3 | Decide the first public `versionCode` / `versionName` | S-5 |
| O-4 | Huawei developer account identity verification: **owner reports COMPLETE** (2026-10-17; not independently verified). No new registration is needed | [testing §2.1](testing-and-publication.md#21-developer-account-and-identity-verification-) |
| O-5 | 🔑 **VERIFY IN CONSOLE** that the account can create the Android app in AppGallery Connect and submit it for release (not independently verified); then create the app record (category **App** is irreversible) and choose the release countries | testing §2.2, §2.9, §6 step 1 |
| O-6 | Legal review LR-1…LR-3, including store screenshots | [privacy §9](privacy-and-compliance.md#9-owner-and-legal-actions) P-1, P-3 |
| O-7 | Choose the trademark/disclaimer approach (LR-2) and approve the listing copy | P-2 |
| O-8 | Decide the OSS-notice mechanism (LR-4) | P-4 |
| O-9 | Decide `allowBackup`; finalise and host the privacy policy (RU + EN); decide on an in-app privacy link | P-5, P-6, P-7 |
| O-10 | Decide on a Russian app name for RuStore (168-FZ recommendation) | [listing RU §2](store-listing-ru.md#2-название) |
| O-11 | Provide a developer contact for the store cards | listing §9 |
| O-12 | 🔑 Answer the age-rating questionnaires and the AppGallery AI declaration | P-8 |
| O-13 | Provide a Huawei phone (no Google services) for H-1…H-14, or approve Cloud Testing as a supplement | [testing §5](testing-and-publication.md#5-device-test-plan) |
| O-14 | Capture and approve the EN/RU screenshot sets from the signed release build | [assets §3](assets-checklist.md#3-screenshots) |
| O-15 | 🔑 Open Testing round, then the formal release as an AGC update with a new, higher-`versionCode` APK that is also the RuStore release | [testing §6.1](testing-and-publication.md#61-open-testing--formal-production-release) |

## 7. Validation results

### 7.1 How it was run

[`evidence/run-gallery1-checks.sh`](evidence/run-gallery1-checks.sh) ran twice on the owner's Mac at
`f7494e1`, in a detached worktree outside the checkout:

- **Run 1** (2026-10-17, about 15:14 UTC) used a fresh worktree.
- **Run 2** (about 15:30 UTC) reused that worktree. The two test tasks were forced again
  (`--rerun --no-build-cache`). The build and lint tasks were **UP-TO-DATE** from run 1, with 0 tasks
  restored from the build cache ([`gradle-task-outcomes.txt`](evidence/mac/gradle-task-outcomes.txt)).
  The task-outcome report did not exist yet in run 1, so whether run 1's build tasks executed or were
  restored from the local Gradle build cache is **not recorded**. Either way, the artifacts are the
  outputs of this commit's inputs.

The curated files in [`evidence/mac/`](evidence/mac/) come from run 2. The bundletool size table
is summarised there (the raw table has 2 072 rows).

Both runs used the **first version of the runner** (as committed in `bd1272f`). That version selected
`origin/main` implicitly (after `git fetch`) and wrote the curated files straight into
`evidence/mac/`. `origin/main` was `f7494e1` both times; `00-summary.tsv` records the full SHA in
every row. The PR #37 review replaced that behaviour: `--commit` is now mandatory, every output goes
to a scratch directory outside all checkouts by default, and repository evidence is written only
with `--publish-evidence NEW_NAME` ([evidence README](evidence/README.md)). The committed `mac/`
results above are kept unchanged as the historical record; they were not regenerated.

### 7.2 Results

| Check | Result |
|---|---|
| `sync_mobile_{datasets,visuals,tooltips,localization}.py --check` | **PASS** ×4 |
| `check_product_freshness.py` (online, informational) | exit 3 **OUTDATED**: latest publishable `1.60.1.70450`, product `1.60.1.70374` (checked 2026-10-17T15:30:54Z) — TF-RC1-DATA |
| `:shared:jvmTest --rerun --no-build-cache` | **PASS**: 338 tests, 0 failures, 0 errors (28 suites) |
| `:composeApp:desktopTest --rerun --no-build-cache` | **PASS**: 663 tests, 0 failures, 36 skipped (opt-in screenshot tests; 86 suites) |
| `:composeApp:assembleDebug` | **PASS**: `composeApp-debug.apk` 20 346 191 bytes |
| `:composeApp:assembleRelease` | **PASS**: `composeApp-release-unsigned.apk` 17 148 789 bytes, **unsigned, not publishable** |
| `:composeApp:bundleRelease` | **PASS**: `composeApp-release.aab` 17 047 508 bytes |
| `:composeApp:lintRelease` | **FAIL (expected, pre-existing)**: 1 error, 9 warnings — see §7.3 |
| `:composeApp:dependencies --configuration releaseRuntimeClasspath` | **PASS**: no GMS, Firebase, Billing, Play Core, Huawei, OkHttp, Ktor or Retrofit coordinates |
| `aapt2 dump badging` (release) | `io.github.alexeykravtsov95.talentforge`, `versionCode 1`, `versionName 0.1.0`, `minSdk 26`, `targetSdk 36`, label `TalentForge`, `icon='res/BW.xml'`, `native-code` arm64-v8a / armeabi-v7a / x86 / x86_64, not debuggable, 85 `application-label-*` locales from dependencies |
| `aapt2 dump xmltree` (release manifest) | the only permission is the app's own signature permission; `allowBackup` unset; no `queries`; exported: `MainActivity`, `ProfileInstallReceiver` (`android.permission.DUMP`) |
| `apksigner verify` (debug APK) | verifies, signer `C=US, O=Android, CN=Android Debug`, v2 scheme. This is what a debug-signed package looks like; it must never be uploaded |
| `apksigner verify` (release APK) | **DOES NOT VERIFY** (`Missing META-INF/MANIFEST.MF`): unsigned, TF-RC1-SIGNING |
| dex / package scan (`gallery1_inspect.py`) | no class names from `com/google/android/gms`, `com/google/firebase`, `com/android/billingclient`, `com/google/android/play/core`, `com/huawei/hms`, `com/huawei/agconnect`, OkHttp, Ktor or `HttpURLConnection`. `java.net.Socket` and `android.webkit.WebView` are referenced by library code (inert without `INTERNET`). No `google-services.json` / `agconnect-services.json` |
| `bundletool get-size total` (release AAB) | estimated per-device download **16 283 743 – 16 326 720 bytes** for every ABI, density and language, versus 17 148 789 bytes for the universal APK file: AAB splits save about 0.8 MB (≈ 5 %). The largest compressed parts are `assets/composeResources` (9.26 MB) and dex (6.82 MB); native libraries are 37 KB. This supports the APK recommendation in [signing §3](multi-store-signing.md#3-apk-vs-aab-for-the-first-public-release) |
| `bundletool build-apks` (splits, universal) and `dump manifest` | **PASS** (local debug-keystore signing, size estimates only, never distribution artifacts) |

### 7.3 Lint: pre-existing problems (none introduced by this milestone)

This milestone changes no product file, so no lint result can come from it.

| Issue | Severity | Count | Origin |
|---|---|---|---|
| `RememberReturnType` (`AppLanguage.android.kt:15`) | Error | 1 | pre-existing TF-RC1-LINT (M5.RC.1); likely a false positive |
| `ApplySharedPref` (`AndroidBuildLibraryStorage.kt`) | Warning | 1 | pre-existing; `commit()` is the deliberate fail-closed choice |
| `UseKtx` (`AndroidBuildLibraryStorage.kt`) | Warning | 1 | pre-existing |
| `OldTargetApi` (`build.gradle.kts`) | Warning | 1 | pre-existing |
| `ObsoleteSdkInt` (`mipmap-anydpi-v26`) | Warning | 1 | introduced by M5.RC.2a icon integration (recorded there) |
| `IconLauncherShape` (`mipmap-*/ic_launcher.png`) | Warning | 5 | introduced by M5.RC.2a icon integration (recorded there) |

This matches M5.RC.2a's report exactly (1 error, 9 warnings).

### 7.4 Not run

- Python baseline (`ruff`, `mypy`, `pytest`): no Python file changed. The new `gallery1_inspect.py`
  is a standalone evidence script and ran successfully as step `60-inspect`.
- iOS framework links and `xcodebuild`: no iOS-facing change.
- Any device or emulator run, any Huawei device, any store console: not available or out of scope (§9).

## 8. Release blockers

None of the existing gates is closed by this milestone.

| Gate | Status after M5.GALLERY.1 | AppGallery-specific note |
|---|---|---|
| TF-RC1-ICON | **OPEN.** Signed-release and physical-device verification outstanding | The store icon file meets AppGallery's 512×512 PNG ≤ 2 MB; the Huawei launcher check is H-11 |
| TF-RC1-SIGNING | **OPEN.** No production signing; the release APK does not verify | The strategy is documented; implementation is M5.RC.2b |
| TF-RC1-DATA | **OPEN, and the gap grew:** latest publishable `1.60.1.70450` vs product `1.60.1.70374` (M5.RC.1 found `70425`) | Refresh, or record the owner's decision, before RC.3 |
| TF-RC1-LINT | **OPEN.** Unchanged: 1 error (`RememberReturnType`), 9 warnings | — |
| LR-1…LR-5 | **OPEN.** Now explicit in both stores' rules | [privacy §7](privacy-and-compliance.md#7-intellectual-property-and-trademarks) |
| M5.RC.3 final device testing | **PENDING.** Adds H-1…H-14 on a Huawei device | [testing §5](testing-and-publication.md#5-device-test-plan) |
| TF-RC1-ANDROID-CONFIG (P2) | **OPEN.** `allowBackup`, R8, ABIs, `localeConfig`, versions | `allowBackup` now also determines the privacy text |

## 9. Limitations

- No Huawei device, no Huawei Developer account and no RuStore Console access were used. Every
  runtime and console statement is UNVERIFIED or taken from official documentation.
- Huawei help pages render with JavaScript; they were read in a browser on 2026-10-17. Each page's
  "Last updated" date is recorded. The rules can change before submission.
- RuStore's category list, the AppGallery category list as shown in the console, and both age-rating
  questionnaires were not inspected.
- The evidence is static: package contents, manifest, dependency coordinates and dex class-name
  strings. A class-name reference proves neither use nor absence of behaviour; the absence of the
  `INTERNET` permission is the decisive network fact.

## 10. Next milestone (recommendation only)

**M5.RC.2b** as already planned, extended with the decisions above: the signing configuration
(§6 of the signing document), `allowBackup` and the other TF-RC1-ANDROID-CONFIG decisions, the lint
baseline or fix, and, if the owner decides so, an in-app About/legal screen (disclaimer, OSS
notices, privacy link). In parallel: **M5.DATA.4** (dataset refresh to the latest publishable build,
or a recorded decision). Then **M5.RC.3** on physical devices, including a Huawei phone.
