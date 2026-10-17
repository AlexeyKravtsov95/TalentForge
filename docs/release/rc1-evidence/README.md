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
| `pass1-summary.tsv` | Step id, exit code, seconds, command for pass 1 (E1), as recorded |
| `pass2-summary.tsv` | Same for pass 2 (E2), as recorded |
| `release-freshness.json` | Output of `scripts/check_product_freshness.py` (E1-30) |
| `lint-results-release.txt` | Full `lintRelease` text report (E2-13) |
| `emulator-device-results.json` | `rc1_android_device_check.py` result on the emulator (E2-23) |
| `screenshots/` | Emulator and iOS-simulator screenshots referenced by the defects and the matrix |
| **Runnable entry points** | |
| `run-rc1-checks.sh` | Pass 1, runnable from this location: Python/ruff/mypy/pytest, four drift checks, freshness, **forced** Gradle tests, Android/iOS builds, lint, artifact inspection |
| `run-rc1-rerun.sh` | Pass 2, runnable from this location: **forced** Gradle tests, isolated `.devcheck` device checks (phone/emulator), iOS simulator launch smoke |
| `rc1-lib.sh` | Shared helpers sourced by both (argument parsing, root/commit resolution, worktree safety, step recording) |
| `rc1_android_device_check.py` | Device scenarios S1–S3, cold start, screenshots (called by `run-rc1-rerun.sh`) |
| **Historical archives** | |
| `as-run/run-rc1-checks.sh`, `as-run/run-rc1-rerun.sh` | The two scripts **exactly as executed** for the recorded results, from `<primary checkout>/.tf-transfer/rc1/`. **Not runnable** (they `exit 64`): their relative paths assume that location, pass 1 used `git worktree add -B` with a named branch inside the primary checkout. Kept as evidence only. The physical-phone adb serial that was the `RC1_PHONE` default is redacted to `<redacted>`; nothing else changed apart from the archive header. |

## Pass 1 — `as-run/run-rc1-checks.sh` (2026-10-16 19:37–19:45 UTC)

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

## Pass 2 — `as-run/run-rc1-rerun.sh` (2026-10-17 03:10–03:17 UTC)

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

## Re-running the audit

### Prerequisites

- macOS with Xcode (26.x was used) for every iOS step; on other hosts the iOS steps are recorded as **BLOCKED**, never PASS.
- JDK 17 on `PATH` / `JAVA_HOME`.
- Android SDK: `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), or `sdk.dir` in `mobile/local.properties` of the checkout that contains these scripts. Without it the Gradle and device steps are **BLOCKED**.
- A Python with the repository's dev dependencies (the repo `.venv`, or `--python`). `PYTHONPATH` is set to the worktree's `src/`, and step `02-python-under-test` fails unless `talent_data` is imported from the audited commit.
- Network for `check_product_freshness.py` (wago.tools); without it the step reports `UNKNOWN`.
- Devices (pass 2 only): an adb serial passed explicitly with `--phone` / `RC1_PHONE` and/or `--emulator` / `RC1_EMULATOR`. **There is no default serial.** A missing or unattached device is recorded as **BLOCKED**. Never commit real device serials.

### What the scripts guarantee

- **Repository root** is resolved with `git rev-parse --show-toplevel` from the scripts' own directory; they refuse to run from anywhere but `<checkout>/docs/release/rc1-evidence/`.
- **Commit:** `--commit` (or `RC1_COMMIT`) is **required** and resolved to a full SHA; an unknown commit is an error (`--fetch` fetches `origin` first). The SHA is written to every summary row and HEAD is re-verified before and after the run. The M5.RC.1 audit used **`5648941`** (`5648941849a8ac7b9a5b291423e95f533e6292ca`).
- **Isolation:** checks run in a **detached** worktree (`git worktree add --detach`) under `--work-dir` (default `${TMPDIR:-/tmp}/talentforge-rc1/<sha12>`), which must be outside every checkout. No branch is created, moved, reset or force-updated; no checkout's working tree is touched. An existing worktree is reused only if it is clean and at exactly that SHA, otherwise the script stops. Remove it afterwards with `git worktree remove <work-dir>/worktree`.
- **Outputs:** logs, `00-plan.txt` and `00-summary.tsv` (`id, status, exit, seconds, commit, command`; status `PASS`/`FAIL`/`BLOCKED`, freshness `CURRENT`/`OUTDATED`/`UNKNOWN`) go to `--out` (default `<work-dir>/evidence-checks` / `evidence-rerun`).
- Test tasks always use `--rerun --no-build-cache`. A `FROM-CACHE` test task proves only that identical inputs passed at some earlier time.

### Invocation

```bash
# from any checkout that contains this directory (e.g. the PR branch)
docs/release/rc1-evidence/run-rc1-checks.sh --commit 5648941 --dry-run     # print the plan only
docs/release/rc1-evidence/run-rc1-checks.sh --commit 5648941               # pass 1
RC1_PHONE=<adb serial> RC1_EMULATOR=<adb serial> \
  docs/release/rc1-evidence/run-rc1-rerun.sh --commit 5648941              # pass 2 (same default work dir)
docs/release/rc1-evidence/run-rc1-checks.sh --help                         # all options
```

Run pass 1 before pass 2 with the same `--work-dir`: the iOS simulator smoke installs the Release simulator build that pass 1 produced.

### Validation of the runnable scripts (this revision)

The runnable scripts were rewritten after the audit runs, in response to PR #33 review. Their validation was:

- `bash -n` on all five shell files (runnable and archived);
- ShellCheck 0.11.0 (`-x -s bash`) clean on the three runnable files;
- `ruff check` and `ruff format --check` (ruff 0.16.9) clean on `rc1_android_device_check.py`;
- behavioural path and safety tests in a throwaway clone on Linux:
  - `--help`;
  - missing `--commit` and an unknown commit both refused;
  - `--dry-run` from another working directory creates nothing;
  - a work dir inside the checkout is refused;
  - a copy outside the checkout is refused;
  - `--setup-only` creates a detached worktree at `5648941…` and leaves all branch refs unchanged;
  - re-use at the same SHA is accepted;
  - an existing worktree at another commit is refused;
  - a dirty tracked file is refused;
  - from a linked worktree, the primary root is detected and protected;
  - on a host without SDK/Xcode every step is BLOCKED (none PASS);
  - phone/emulator steps are BLOCKED for a missing, unauthorised or absent serial;
- `--dry-run` and `--setup-only` on the owner's Mac (see the PR #33 update).

**The full Mac/mobile test matrix was not re-executed with the rewritten scripts.** All recorded results (E1, E2) come from the as-run scripts in `as-run/`.

**Note on `rc1_android_device_check.py`.** The committed file is a lint-clean refactor of the script that produced E2-23. It has the same scenarios, steps, timings and expectations. The as-run copy imported the helpers from the audit worktree. The committed copy imports them from `RC1_REPO`, which `run-rc1-rerun.sh` sets to the audited worktree; without `RC1_REPO` it uses the checkout that contains it.
