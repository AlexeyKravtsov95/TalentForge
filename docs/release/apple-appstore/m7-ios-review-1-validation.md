# M7.IOS.REVIEW.1 — Verification evidence

Date: 2026-11-16 (Asia/Novosibirsk). Base `6dc98e15158ae9961ea2d5abf2878673862d8d67`.
Workspace: isolated `codex/m7-ios-review-1`; original checkout's unrelated edits preserved.

## Local focused and static checks

Commands ran at this checkout, using the existing Python 3.12 venv executables from
`/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/`. The full pytest command uses
`PYTHONPATH=src` for this checkout, avoiding the original checkout's editable package.
No dependencies or production settings were changed.

| Command | Observed result |
|---|---|
| `python -m pytest tests/unit/test_verify_appstore_metadata.py -q` before edits | **35 passed**, 0.33s |
| Same focused command after final field edits | **64 passed**, 2.85s |
| `python -m ruff check .` | **PASS**, all checks passed |
| `python -m ruff format --check .` | **PASS**, 487 files already formatted |
| `python -m mypy` | **PASS**, no issues in 125 source files |
| `python3 scripts/sync_mobile_datasets.py --check` | **PASS**, 50 files / 5 builds |
| `python3 scripts/verify_appstore_metadata.py --report …/appstore-metadata-report.json` | **PASS**, 44 checks / 18 fields; [JSON](evidence/m7-ios-review-1/appstore-metadata-report.json), [text](evidence/m7-ios-review-1/appstore-metadata.txt) |
| `python3 scripts/verify_appstore_screenshots.py --require-duo --report …/appstore-screenshots-report.json` | **PASS**, 126 checks / 42 PNGs; [JSON](evidence/m7-ios-review-1/appstore-screenshots-report.json) |
| Original `main` validator against new field pack, copied to `/tmp` and run with explicit `--metadata` | Expected **FAIL**, exit 1: both names and both neutral subtitles rejected by old forced-branding rules |
| `graphify update .` | **PASS**, AST-only; 16,486 nodes / 53,093 edges / 507 communities; committed outputs refreshed |
| `git diff --check` | **PASS** |
| Diff against base for `mobile/`, `data/`, `docs/schema/`, `docs/ota/keys/` | Empty; versions, datasets, schemas and keys unchanged |
| Diff for submission screenshots and iOS AppIcon | Empty; no image retouching or replacement |

Graphify warns about partial parser extraction in three unchanged Kotlin test files
(`BuildManagementUiTest.kt`, `LicensesScreenUiTest.kt`, `OtaSessionTest.kt`) and eight files
with no extracted symbols. This is graph extraction coverage, not a compiler result;
no claim that graph parsing proves Kotlin correctness is made.

The full Python baseline (`env PYTHONPATH=src python -m pytest`) and local mobile baseline
(`env ANDROID_HOME=… ./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:assembleDebug
--console=plain`) are additionally run for this branch. Their **final** results/counts and
hosted CI/Mobile CI run URLs are maintained in this PR's description and Checks tab.
This focused record does not predeclare those runs green or substitute an older PR's results.
No runtime/iOS-native source changes require a new local framework build; hosted Mobile CI
retains its existing iOS compilation, framework/archive and simulator checks.

## Audit versus native acceptance

All 42 delivered PNGs and the current iOS AppIcon were visually inspected. Temporary
contact sheets were only audit aids outside the repository. Original delivered bytes and
manifest are preserved. iPad portrait files use the existing tablet side-inspector layout;
metadata promises adaptation to screen space rather than a universal portrait-bottom rule.

No new native gesture test, real-app capture, signed archive, App Store Connect access,
upload, Apple message, resubmission or publication was performed. PR #150's Duo native
pinch/pan and remaining functional gates remain **BLOCKED**. See the
[assessment](m7-ios-review-1-assessment.md) for legal/provenance gates and the recapture plan.

**Release gate remains NO-GO regardless of test/CI success.**
