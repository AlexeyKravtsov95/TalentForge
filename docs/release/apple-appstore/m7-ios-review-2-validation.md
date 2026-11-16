# M7.IOS.REVIEW.2 — validation record

Date: 2026-11-17, Asia/Novosibirsk. Base `502facedf853ec48f79bd763a04c5e03f76d1e24`
(main after PR #151). Only metadata, unsent responses and release evidence change.
No validator/test logic, runtime, Android, game content, image or version changes.

## Local checks

Commands run from the isolated worktree, using the existing workspace `.venv/bin`
executables. The pytest command reads this worktree's scripts/fixtures; no application
package behavior is changed by this patch.

| Command | Result |
|---|---|
| `python scripts/verify_appstore_metadata.py --report docs/release/apple-appstore/evidence/m7-ios-review-2/appstore-metadata-report.json` | PASS — 18 fields, 44 checks; [report](evidence/m7-ios-review-2/appstore-metadata-report.json) |
| `python scripts/verify_appstore_screenshots.py --require-duo` | PASS — all 42 unchanged PNGs, 126 checks |
| `python -m pytest tests/unit/test_verify_appstore_metadata.py tests/unit/test_verify_appstore_screenshots.py -q` | **120 passed**, 104.77s |
| `ruff check .` | PASS |
| `ruff format --check .` | PASS — 491 files already formatted |
| `mypy` | PASS — 125 source files |
| `python3 scripts/sync_mobile_datasets.py --check` | PASS — 50 files / 5 builds |
| `graphify update .` | Exit 0; no topology changes, outputs untouched; AST-only, no API cost |
| `git diff --check` | PASS |
| Temporary document consistency check | PASS — local Markdown links exist; 42 individual audit rows, icon SHA-256, report check count and single identifying title in each description |

Graphify reports existing extractor limitations: three Kotlin test files partially parsed
(`BuildManagementUiTest.kt`, `LicensesScreenUiTest.kt`, `OtaSessionTest.kt`), eight files
with no modeled symbols and 24 unclassified files. These are parser coverage warnings,
not compiler/test results. No source file in that list was changed. Private `ceremony/`
remains excluded by `.graphifyignore`; no private material was read or indexed.

Full Python/mobile validation is supplied by the exact-head hosted checks recorded below.
No local Gradle/Xcode/archive/IPA build is created for this metadata-only patch. Existing
CI jobs may compile/test as configured; they do not deliver or submit a new store build.
No new tests mirror prose edits: the existing tests cover the changed field requirements.

## Visual and store evidence

- All 42 screenshots and the iOS App Icon opened visually; every screenshot hash verified
  against the unchanged manifest. [Individual results](m7-ios-review-2-screenshot-audit.md).
- Read-only native Chrome ASC inspection, [selective observation record](evidence/m7-ios-review-2/asc-read-only-observation.json).
  Full original rejection and follow-up read; all requested EN/RU public text fields
  inspected. No Save, Update Review Information, reply, upload or resubmit action.
- No remote-original image hash comparison, uploaded-binary install/parity test or native
  Duo gesture acceptance is claimed. A build-number match cannot establish source parity.

## Hosted checks

Exact PR head/results are recorded in the PR description and its Checks tab after the
workflows finish. A green result is technical validation only; release stays **NO-GO**
for the rights, content declaration, candidate/screenshots and native acceptance gates
in the [assessment](m7-ios-review-2-assessment.md).
