# M5.RC.1 — Evidence index

All checks ran on the owner's Mac against an isolated git worktree of `origin/main` @ `5648941`, on branch `chore/m5-rc1-release-readiness-audit`. The owner's own checkout was not touched.

**Machine.** MacBook (Apple M4 Pro, 24 GB), macOS 26.3.1 (25D771280a). Toolchain:
- JDK 17.0.19 (Homebrew)
- Gradle 9.5.1 (wrapper)
- Xcode 26.4.1 (17E202), iOS SDK 26.4
- Python 3.12.13 (repo `.venv`)
- Android build-tools 37.0.0
- `gh` 2.102.0

Raw logs stay on the audit machine under `.tf-transfer/rc1/evidence*/` (untracked local scratch, not committed). The summaries, the machine-readable results and the screenshots needed for review are committed here.

## Files in this directory

| File | Content |
|---|---|
| `pass1-summary.tsv` | Step id, exit code, seconds, command for pass 1 (E1) |
| `pass2-summary.tsv` | Same for pass 2 (E2) |
| `release-freshness.json` | Output of `scripts/check_product_freshness.py` (E1-30) |
| `lint-results-release.txt` | Full `lintRelease` text report (E2-13) |
| `emulator-device-results.json` | `rc1_android_device_check.py` result on the emulator (E2-23) |
| `rc1_android_device_check.py` | The device scenarios S1–S3, cold start and screenshot procedure (see the note below) |
| `run-rc1-checks.sh`, `run-rc1-rerun.sh` | The two collector scripts exactly as run (paths refer to `.tf-transfer/rc1/`) |
| `screenshots/` | Emulator and iOS-simulator screenshots referenced by the defects and the matrix |

## Pass 1 — `run-rc1-checks.sh` (2026-10-16 19:37–19:45 UTC)

| ID | Step | Exit | Result |
|---|---|---|---|
| E1-01 | environment | 0 | versions above |
| E1-02 | `git fetch` + worktree at `5648941` | 0 | — |
| E1-03 | `gh pr list` / `gh run list` / jobs | 0 | 40 latest runs: all `failure` with **0 steps** per job → CI infrastructure-blocked |
| E1-10 | `ruff check .` | 0 | All checks passed |
| E1-11 | `ruff format --check .` | 0 | — |
| E1-12 | `mypy` | 0 | no issues in 72 source files |
| E1-13 | `pytest -q` | 0 | 1048 passed, 11 deselected |
| E1-20…23 | four `sync_mobile_*.py --check` | 0 | — |
| E1-30 | `check_product_freshness.py` | **3** | **OUTDATED** |
| E1-41/42/46 | `jvmTest` / `desktopTest` / iOS-sim test | 0 | **restored FROM-CACHE; not counted**, replaced by E2-10/11/12 |
| E1-43/44/45 | `assembleDebug` / `assembleRelease` / `bundleRelease` | 0 | APK 20.1 MB / 16.9 MB unsigned / AAB 16.8 MB |
| E1-47/48 | iOS framework links (debug device+sim, release device) | 0 | — |
| E1-49 | `lintRelease` | **1** | 1 error (`RememberReturnType`), 4 warnings |
| E1-50 | Xcode Release build settings | 0 | bundle id, versions, iOS 17.2, `AppIcon` |
| E1-51/52/53 | `xcodebuild` sim Debug / sim Release / device Release, unsigned | 0 | `** BUILD SUCCEEDED **` ×3 |
| E1-60 | artifact inspection | 1 (from the unsigned `apksigner` verify) | `icon=''`; release APK `DOES NOT VERIFY`; `.app` sizes; built Info.plist (`UIApplicationSupportsMultipleScenes => true`) |
| E1-70 | devices | 0 | TECNO KM5n (USB) + emulator-5554; iOS 26.x simulators |
| E1-71 | Android smoke | 0 (but no-op) | `adb: more than one device` → nothing ran; superseded by pass 2 |

## Pass 2 — `run-rc1-rerun.sh` (2026-10-17 03:10–03:17 UTC)

| ID | Step | Exit | Result |
|---|---|---|---|
| E2-10 | `:shared:jvmTest --rerun --no-build-cache` | 0 | 338 tests, 0 failed |
| E2-11 | `:composeApp:desktopTest --rerun --no-build-cache` | 0 | 663 tests, 0 failed, 36 skipped (opt-in screenshot tests) |
| E2-12 | `:shared:iosSimulatorArm64Test --rerun --no-build-cache` | 0 | 188 tests, 0 failed |
| E2-13 | lint report | 0 | `lint-results-release.txt` |
| E2-20 | isolated `.devcheck` APK build | 0 | — |
| E2-21 | `android_section_check.py --serial <TECNO>` | 1 | **BLOCKED**: `device … not connected (['emulator-5554'])` |
| E2-22 | RC1 device scenarios on the TECNO | 1 | **BLOCKED**: same cause |
| E2-23 | RC1 device scenarios on the emulator (`--screens`) | 0 | S1 `3 / 51`, S2 `2 / 51`, S3 `3 / 51`: **3/3 PASS**; cold start 662/575/544/552/542 ms; crash buffer empty |
| E2-24 | rebuild plain debug APK | 0 | — |
| E2-30 | iOS launch smoke, iPhone 17e (iOS 26.4) | 0 | launched, alive after 10 s, screenshot |
| E2-31 | iOS launch smoke, iPad Air 11-inch (M4) (iOS 26.4) | 0 | launched, alive after 10 s, screenshot |

## Reproduction

```bash
# from the repository root, on macOS with Xcode, JDK 17, an Android SDK and the repo .venv
.venv/bin/python -m ruff check . && .venv/bin/python -m ruff format --check . && .venv/bin/python -m mypy && .venv/bin/python -m pytest -q
for s in datasets visuals tooltips localization; do .venv/bin/python scripts/sync_mobile_$s.py --check; done
.venv/bin/python scripts/check_product_freshness.py --output release-freshness.json
cd mobile
G='./gradlew --console=plain --no-configuration-cache --no-build-cache'
$G :shared:jvmTest --rerun
$G :composeApp:desktopTest --rerun
$G :shared:iosSimulatorArm64Test --rerun
$G :composeApp:assembleRelease :composeApp:bundleRelease :composeApp:lintRelease
$G :composeApp:linkDebugFrameworkIosArm64 :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:linkReleaseFrameworkIosArm64
(cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release \
   -destination 'generic/platform=iOS Simulator' -derivedDataPath build-release CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build)
# device scenarios (isolated package; never touches the real app)
$G :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.devcheck
cd .. && python3 docs/release/rc1-evidence/rc1_android_device_check.py --serial <serial> --out .cache/rc1-device
```

Always use `--rerun --no-build-cache` for test tasks in an audit. A `FROM-CACHE` test task proves only that identical inputs passed at some earlier time.

**Note on `rc1_android_device_check.py`.** The committed file is a lint-clean refactor of the script that produced E2-23. It has the same scenarios, the same steps, the same timings and the same expectations. The as-run copy (in `.tf-transfer/rc1/`) imported the helpers from the audit worktree; the committed copy imports them from this repository's `scripts/`.
