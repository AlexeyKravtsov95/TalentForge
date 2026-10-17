# M5.RC.2b — Android release hardening: evidence

| | |
|---|---|
| Branch | `feature/m5-rc2b-release-hardening`, from `main` @ `26db603` (merge of PR #37) |
| Validated commit | `a020e1b` (product code); the PR #38 review fixes were validated on top of it (§ Review fixes) |
| Environment | owner's Mac (Apple silicon, macOS 26.3.1), JDK 17.0.19, Gradle 9.5.1, AGP 8.13.2, Kotlin 2.4.20, Android build-tools 35.0.0 (AGP default) / 37.0.0, Python 3.12 |
| Date | 2026-10-18 |

Nothing in this folder involves a production key. Every signed artifact below was signed with a
**temporary self-test key** that was deleted at the end of the run. Its certificate digest is
recorded only to show that the checks compare real values.

## Regression

[`mac/regression-run1-627658f.txt`](mac/regression-run1-627658f.txt) and
[`mac/regression-run2-a020e1b.txt`](mac/regression-run2-a020e1b.txt) hold the exit codes;
[`mac/regression-run3-review-fixes.txt`](mac/regression-run3-review-fixes.txt) is the run after the review fixes.

Run 1 found two real regressions from the first About entry, a text link in the Home header:
- `ShellLocalizationUiTest`: "About" broke mid-word at 320 dp / 200 %;
- `AllBuildsRowLayoutUiTest`: the wrapped header pushed "All builds" off screen.

Commit `a020e1b` fixed both. Run 2 re-ran the affected tasks on the fixed code.

| Check | Result |
|---|---|
| `sync_mobile_{datasets,visuals,tooltips,localization}.py --check` | PASS ×4 (run 1; no dataset or resource input changed after it) |
| `generate_oss_notices.py --check` (new; full content since the review) | PASS: 122 modules, every POM licence re-read |
| `ruff check .` / `ruff format --check .` / `mypy` / `pytest` | PASS / PASS / PASS (72 files) / **1102 passed** after the review fixes (1088 before; +14 generator tests), 11 deselected (network-marked) |
| `:shared:jvmTest --rerun --no-build-cache` | PASS: 338 tests, 0 failures (28 suites) |
| `:composeApp:desktopTest --rerun --no-build-cache` (run 2) | PASS: **681 tests, 0 failures, 37 skipped** (opt-in screenshot tests; 89 suites). Before this milestone: 663 / 36 |
| `:composeApp:assembleDebug` / `assembleRelease` / `bundleRelease` | PASS (debug 20.8 MB; unsigned release 17.2 MB; AAB 17.1 MB) |
| `:composeApp:lintRelease` (run 2) | **PASS: 0 errors, 9 warnings** ([report](mac/lint-results-release.txt)) |
| `:composeApp:compileKotlinIosArm64`, `linkDebugFrameworkIosSimulatorArm64` | PASS |
| `scripts/release_signing_selftest.sh` (after the review fixes) | **PASS 14/14** (below) |

Not run: `xcodebuild` (the iOS change is two `iosMain` declarations, compiled and linked above),
`:shared:iosSimulatorArm64Test` (`shared` is unchanged), and any physical device.

## Signing self-test

[`signing-selftest/`](signing-selftest/): the logs of `scripts/release_signing_selftest.sh` after
the review fixes (home directory shown as `~`; logs are written as `.txt` because `*.log` is
git-ignored repository-wide).

| # | Scenario | Result |
|---|---|---|
| 1 | No signing settings → `assembleRelease` | unsigned `composeApp-release-unsigned.apk`; the validator rejects it (`signature-verifies` FAIL) |
| 2 | No settings → `assemblePublishableRelease` | fails before building; nothing staged |
| 3 | Two of four settings | `checkReleaseSigning` fails, naming the missing settings; no value or path printed ([log](signing-selftest/03-partial.txt)) |
| 4 | The SDK debug keystore as release key | refused |
| 5 | A keystore inside the checkout | refused |
| 6 | All four settings | signed APK; `apksigner` shows the test certificate (RSA 4096, v2); the validator passes ([apksigner](signing-selftest/06-signed-apksigner.txt), [validator](signing-selftest/06-signed-validator.txt)) |
| 7 | Publishable, no expected fingerprint | fails before building |
| 8 | Publishable, wrong fingerprint | `certificate-sha256-matches` FAIL; nothing staged |
| 9 | Publishable, correct fingerprint | `TalentForge-0.1.0-1.apk` + PASS [report](signing-selftest/09-publish-ok.report.json) (APK SHA-256 recorded) |
| 10 | A good APK staged, then a wrong fingerprint | the attempt fails; the earlier APK is **gone** from `outputs/publishable` |
| 11 | A good APK staged, then no signing settings | fails before building; the earlier APK is **gone** |
| 12 | A good APK staged, then partial settings (fails in `checkReleaseSigning`) | the earlier APK is **gone** |
| 13 | The debug APK | the validator rejects it (`not-debug-certificate-dn`, `not-debuggable`) |
| 14 | Secret scan | neither the password nor the keystore path appears in any log, report or `build/` file |

After the run, the temporary directory, `apk/release` and `outputs/publishable` were gone.
Gradle ran with `--no-build-cache`.

## Review fixes (PR #38)

**P1: stale publishable APK.**
- *The defect.* The publishable directory was cleared only inside the `assemblePublishableRelease` action. A later attempt that failed earlier left the previous attempt's APK in `build/outputs/publishable/`, where it could be uploaded by mistake. Earlier failure points: `checkPublishableSigning`, `checkReleaseSigning` (which has no ordering against it), compilation, or packaging.
- *The fix* (`composeApp/build.gradle.kts`):
  - a `gradle.taskGraph.whenReady` hook deletes the directory as soon as a task graph containing `assemblePublishableRelease` is ready, before any task runs;
  - the task action also deletes it on any exception, not only on a validation failure.
- *Proof that the regression test catches the defect.* The extended self-test was run against the **unfixed** `build.gradle.kts`: `stale-none` and `stale-partial` **FAIL**, with the earlier `TalentForge-0.1.0-1.apk` still staged ([summary](review-fixes/selftest-on-unfixed-gradle-summary.txt)). `stale-wrong` passed there, because the validator path already deleted the directory. On the fixed code all 14 scenarios pass.

**P2: `generate_oss_notices.py --check` compared only coordinates.**
- *The fix.* `--check` now rebuilds the complete expected notices: Gradle resolution, plus every POM licence re-verified against its curated component. It compares them with the committed file after JSON normalization (schema, platform, source, licence metadata, every component's name, URL, licence and modules) and lists each differing path.
- *New tests:* `tests/unit/test_generate_oss_notices.py` (14, offline; Gradle and POM lookups stubbed). They cover:
  - an **upstream licence change with unchanged coordinates**;
  - an edited component licence with unchanged coordinates;
  - edits to the licence name, licence file, URL, schema, platform and source;
  - a module moved to another component;
  - coordinate drift;
  - a missing or malformed file;
  - an unclaimed module;
  - the committed file equalling the generator's rendering.
- *Proof.* Against the **unfixed** generator, 11 of the 14 fail, including both licence-change tests. Against the fix, all 14 pass. The real `--check` (Gradle + POMs) passes on the committed file.

## Backup policy

[`mac/manifest-release-application.txt`](mac/manifest-release-application.txt): the merged
release manifest has `allowBackup=false`, `fullBackupContent=@xml/backup_rules` and
`dataExtractionRules=@xml/data_extraction_rules`. Both XML files are packaged with all nine data
domains excluded (in `cloud-backup` and `device-transfer` for the Android 12+ file).

Emulator check (API 35, `sdk_gphone64_arm64`, isolated `.devcheck` debug install, removed
afterwards). `dumpsys package` flags:
- the new build: `[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]`, with **no `ALLOW_BACKUP`**;
- the pre-existing install of the previous code on the same emulator: `[ … ALLOW_BACKUP ]`.

The emulator's Backup Manager was disabled and was not changed. An actual backup or transfer
run, and vendor tools (Huawei Phone Clone, TECNO), are **UNVERIFIED**.

## Lint

0 errors, 9 warnings, all pre-existing. None was suppressed or baselined.

| Warning | Count | Risk |
|---|---|---|
| `ApplySharedPref` (`AndroidBuildLibraryStorage.kt`) | 1 | Low. `commit()` is the deliberate fail-closed write: a save is reported only once it is on disk |
| `UseKtx` (same file) | 1 | None (style) |
| `OldTargetApi` (`targetSdk 36`) | 1 | Low-medium. Both stores accept 36 today. Raising it is a TF-RC1-ANDROID-CONFIG owner decision that needs a device regression |
| `ObsoleteSdkInt` (`mipmap-anydpi-v26`) | 1 | None (folder qualifier; `minSdk 26`) |
| `IconLauncherShape` (`mipmap-*/ic_launcher.png`) | 5 | Low. Full-bleed raster fallbacks; every API 26+ launcher uses the adaptive icon. Vendor launcher check in M5.RC.3 |

`DataExtractionRules`, raised by the first backup change, was fixed by adding the API 26–30
rules. The lint log also prints `e: … incompatible version of Kotlin … expected version is 2.2.0`
for the module's Kotlin 2.4 binaries. That is lint's bundled Kotlin analysis. It does not fail
the task, and it is part of the `RememberReturnType` root cause (see rc1-defects TF-RC1-LINT).

## About / legal screen

- [`screenshots/`](screenshots/): desktop-rendered, from `M5Rc2bScreenshotTest`. EN and RU at
  393×852: the Home entry, the top, privacy, licences, expanded modules and licence text. Also the
  320×568 phone at 200 % font.
- [`emulator/`](emulator/): the real Android runtime on the API 35 emulator (English device). It
  shows the Home ⓘ entry (48 dp), and About with `Version 0.1.0 (1)` read from PackageManager and
  the notices loaded from the APK. System Back returned to Home. Russian on a device was not run.

## Open-source notices

`scripts/generate_oss_notices.py` resolved `:composeApp:releaseRuntimeClasspath`: 124 modules,
minus 2 BOMs (no code), leaving 122 listed modules. Each module's POM `<licenses>` was read,
following `<parent>`; Google Maven / Maven Central were used where Gradle had cached only
`.module` metadata. All 122 declare Apache-2.0.

| Component | Modules |
|---|---|
| AndroidX (Android Jetpack) | 88 |
| Compose Multiplatform | 23 |
| kotlinx.serialization | 4 |
| kotlinx.coroutines | 3 |
| Kotlin Standard Library, JetBrains Java Annotations, JSpecify, Guava ListenableFuture | 1 each |

- **NOTICE files:** none of the shipped jars/aars contains a `NOTICE` file, so Apache-2.0 §4(d)
  requires no NOTICE text. The APK already carried four AndroidX copies of the Apache licence. The
  official `LICENSE-2.0.txt` (SHA-256 `cfc7749b…3d30`) is now bundled and shown in-app.
- **iOS:** the framework also links `org.jetbrains.skiko` (Skia and third-party code). Those
  notices are **not** compiled (TF-RC2B-IOS-NOTICES).
