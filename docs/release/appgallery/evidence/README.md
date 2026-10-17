# M5.GALLERY.1 — validation evidence

What is here:

| File | Purpose |
|---|---|
| `run-gallery1-checks.sh` | Runnable check: drift checks, freshness (informational), forced (`--rerun --no-build-cache`) JVM/desktop tests, `assembleDebug` / `assembleRelease` / `bundleRelease` / `lintRelease`, the release dependency report, aapt2/apksigner inspection, optional bundletool size estimates |
| `gallery1_inspect.py` | Standard-library inspection of the APKs/AAB, manifests, dependency report, test and lint reports → `gallery1-inspection.json` / `.txt` |
| `test-run-gallery1-safety.sh` | Executable path/worktree safety tests for the runner (temporary fixture; no Android SDK needed) |
| `mac/` | **Historical, committed** output of the run recorded in [readiness §7](../readiness.md#7-validation-results). Never rewritten by the runner |

## Safety rules (enforced by the runner, tested by `test-run-gallery1-safety.sh`)

- **The commit is explicit.** `--commit REF` is mandatory; it is resolved to a full 40-hex SHA,
  printed, written to `00-commit.txt` and recorded in every row of `00-summary.tsv`. There is no
  implicit `origin/main`. `--expect-sha SHA` refuses a ref that resolves to anything else.
  `git fetch origin` runs only with `--fetch`.
- **Everything generated goes to scratch.** The detached worktree, the full logs and the curated
  results default to `${TMPDIR:-/tmp}/talentforge-gallery1/<sha12>/{wt,evidence,curated}`.
- **Paths are validated before anything is created.** `--work-dir`, `--out` and `--curated` must be
  absolute and unambiguous (no `.`, `..`, `//`, `#` or newline; no existing non-directory). They must
  be outside the invoking checkout, outside its **primary checkout** (when invoked from a linked
  worktree), outside every registered worktree and outside any other git repository. They must not
  contain the checkout, and must not be `/` or the home directory itself. Outputs may not sit inside
  the scratch worktree, and `--out` must differ from the curated directory.
- **Historical evidence is preserved.** Ordinary runs never write into the repository. Writing
  curated results into the repository is opt-in only, with `--publish-evidence NAME`. That writes
  into the **new** directory `docs/release/appgallery/evidence/NAME` and refuses an existing or
  tracked name, so `mac/` and any other earlier report can never be overwritten.
- **Scratch worktrees are reused only when provably safe.** Reuse requires a registered worktree of
  this repository, detached, at exactly the selected SHA and with no local changes. Anything else is
  refused with a message to remove it.
- **`--dry-run`** performs every check above, prints the plan and exits without creating or running
  anything.
- Nothing is signed for release, installed on a device, uploaded, committed or pushed, and no signing
  key is created or read. The debug APK's `apksigner` log is not copied to the curated set (its digests
  identify the local debug keystore). Home and worktree paths are replaced by `~`, `<worktree>` and
  `<logs>` in curated files.
- Step status: PASS / FAIL / FAIL-EXPECTED / INFO / BLOCKED. `lintRelease` is FAIL-EXPECTED
  (pre-existing TF-RC1-LINT); the release `apksigner` step is FAIL-EXPECTED (unsigned,
  TF-RC1-SIGNING); freshness is INFO (TF-RC1-DATA).

## Usage

```bash
cd <TalentForge checkout>
R=docs/release/appgallery/evidence/run-gallery1-checks.sh
# PR head as checked out here (pin it):
JAVA_HOME="$(/usr/libexec/java_home -v 17)" bash "$R" --commit HEAD --expect-sha "$(git rev-parse HEAD)"
# current main:
JAVA_HOME="$(/usr/libexec/java_home -v 17)" bash "$R" --fetch --commit origin/main
# validate only:
bash "$R" --commit HEAD --dry-run
# deliberately add a NEW committed evidence set (opt-in; never overwrites mac/):
JAVA_HOME="$(/usr/libexec/java_home -v 17)" bash "$R" --commit HEAD --publish-evidence run-<date>
# safety tests (bash, git, coreutils only):
bash docs/release/appgallery/evidence/test-run-gallery1-safety.sh
```

Requirements for a full run: JDK 17, the Android SDK (`ANDROID_HOME` or `mobile/local.properties`),
Python 3.12 for the drift checks, network access for Gradle dependencies and the freshness check
(and for `--fetch`). bundletool is optional.

## History of `mac/`

`mac/` was produced on 2026-10-17 by the **first version of the runner** (commit `bd1272f`). That
version selected `origin/main` implicitly and wrote the curated files directly into `mac/`. The
selected commit was `f7494e1ee7d863613fc43b28acff88e9458e5cb9` (recorded in every row of
`mac/00-summary.tsv`). The files are kept unchanged. The current runner cannot write there.

## Curated files

| File | Content |
|---|---|
| `00-summary.tsv` | step, status, exit code, seconds, full commit SHA, command |
| `00-commit.txt` | requested ref, full SHA, commit date and subject (runner versions after the PR #37 review; absent in `mac/`) |
| `01-env.txt` | OS, JDK, Python, build-tools, aapt2/apksigner versions, commit |
| `15-freshness.txt` | `check_product_freshness.py` output (online, read-only) |
| `34-deps-release.txt` | `:composeApp:dependencies --configuration releaseRuntimeClasspath` |
| `40-badging-{debug,release}.txt` | `aapt2 dump badging` |
| `41-manifest-release.txt` | `aapt2 dump xmltree --file AndroidManifest.xml` (merged release manifest) |
| `42-apksigner-release.txt` | `apksigner verify` of the unsigned release APK (expected failure) |
| `50-bundletool-manifest.txt` | `bundletool dump manifest` of the release AAB |
| `52-bundletool-size.txt` | `bundletool get-size total`. In `mac/` it is **summarised** per ABI (min/max) from the raw 2 072-row table, and `bundletool_get_size` in the JSON holds the same summary; new runs keep the raw table |
| `gradle-task-outcomes.txt` | executed / FROM-CACHE / UP-TO-DATE task counts per Gradle step |
| `33-lintRelease-excerpt.txt`, `lint-results-release.txt` | lint failure excerpt and the text report |
| `gallery1-inspection.{json,txt}` | parsed facts used by the readiness and privacy documents |
