# M5.GALLERY.1 — validation evidence

What is here:

| File | Purpose |
|---|---|
| `run-gallery1-checks.sh` | Runnable check: drift checks, freshness (informational), forced (`--rerun --no-build-cache`) JVM/desktop tests, `assembleDebug` / `assembleRelease` / `bundleRelease` / `lintRelease`, the release dependency report, aapt2/apksigner inspection, optional bundletool size estimates |
| `gallery1_inspect.py` | Standard-library inspection of the APKs/AAB, manifests, dependency report, test and lint reports → `gallery1-inspection.json` / `.txt` |
| `mac/` | The curated, committed output of the run recorded in [readiness §7](../readiness.md#7-validation-results) |

## Safety rules (enforced by the script)

- The commit under test (default `origin/main` after `git fetch origin`) is checked out in a
  **detached worktree outside the checkout** (`$TMPDIR/talentforge-gallery1…`). No branch is created
  or moved; the checkout's working tree and its local changes are never touched.
- Nothing is signed for release, installed on a device, uploaded, committed or pushed. No signing key
  is created or read.
- The debug-signed APK's `apksigner` log is **not** copied to `mac/` (its digests identify the local
  debug keystore); only the subject DN is recorded in the inspection. Home and worktree paths are
  replaced by `~`, `<worktree>` and `<logs>` in the curated files.
- Every step is recorded as PASS / FAIL / FAIL-EXPECTED / INFO / BLOCKED with the commit SHA in
  `mac/00-summary.tsv`. `lintRelease` is FAIL-EXPECTED (pre-existing TF-RC1-LINT); the release
  `apksigner` step is FAIL-EXPECTED (unsigned, TF-RC1-SIGNING); freshness is INFO (TF-RC1-DATA).

## Reproduce

```bash
cd <TalentForge checkout>
JAVA_HOME="$(/usr/libexec/java_home -v 17)" bash docs/release/appgallery/evidence/run-gallery1-checks.sh \
  --work-dir "$TMPDIR/talentforge-gallery1-<run>"        # optional: --bundletool <jar or executable>
```

Requirements: JDK 17, the Android SDK (`ANDROID_HOME` or `mobile/local.properties`), Python 3.12 for
the drift checks, and network access for `git fetch`, Gradle dependencies and the freshness check.

## Curated files in `mac/`

| File | Content |
|---|---|
| `00-summary.tsv` | step, status, exit code, seconds, commit, command |
| `01-env.txt` | OS, JDK, Python, build-tools, aapt2/apksigner versions, commit |
| `15-freshness.txt` | `check_product_freshness.py` output (online, read-only) |
| `34-deps-release.txt` | `:composeApp:dependencies --configuration releaseRuntimeClasspath` |
| `40-badging-{debug,release}.txt` | `aapt2 dump badging` |
| `41-manifest-release.txt` | `aapt2 dump xmltree --file AndroidManifest.xml` (merged release manifest) |
| `42-apksigner-release.txt` | `apksigner verify` of the unsigned release APK (expected failure) |
| `50-bundletool-manifest.txt` | `bundletool dump manifest` of the release AAB |
| `52-bundletool-size.txt` | `bundletool get-size total` per ABI (min/max), **summarised** from the raw 2 072-row table; `bundletool_get_size` in the JSON holds the same summary |
| `gradle-task-outcomes.txt` | executed / FROM-CACHE / UP-TO-DATE task counts per Gradle step |
| `33-lintRelease-excerpt.txt`, `lint-results-release.txt` | lint failure excerpt and the text report |
| `gallery1-inspection.{json,txt}` | parsed facts used by the readiness and privacy documents |
