# M7.IOS.REVIEW.2 — validation record

Date: 2026-11-17, Asia/Novosibirsk. Base `502facedf853ec48f79bd763a04c5e03f76d1e24`
(main after PR #151). Only metadata, unsent responses and release evidence change.
The metadata validator/tests also change to reject claims for library features introduced
after the selected build’s review. No runtime, Android, game content, image or version changes.

## Local checks

Commands run from the isolated worktree, using the existing workspace `.venv/bin`
executables. The pytest command reads this worktree's scripts/fixtures; no application
package behavior is changed by this patch.

| Command | Result |
|---|---|
| `python scripts/verify_appstore_metadata.py --report docs/release/apple-appstore/evidence/m7-ios-review-2/appstore-metadata-report.json` | PASS — 18 fields, 45 checks; [report](evidence/m7-ios-review-2/appstore-metadata-report.json) |
| `python scripts/verify_appstore_screenshots.py --require-duo` | PASS — all 42 unchanged PNGs, 126 checks |
| `python -m pytest tests/unit/test_verify_appstore_metadata.py tests/unit/test_verify_appstore_screenshots.py -q` | Final **156 passed**, 61.05s (100 metadata + 56 screenshot tests) |
| `ruff check .` | PASS |
| `ruff format --check .` | PASS — 491 files already formatted |
| `mypy` | PASS — 125 source files |
| `python3 scripts/sync_mobile_datasets.py --check` | PASS — 50 files / 5 builds |
| `graphify update .` | Exit 0; final graph rebuilt to 16,488 nodes / 53,101 edges / 514 communities; all three tracked graph outputs included; AST-only, no API cost |
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
35 new negative cases prevent newer-library claims across EN/RU public fields and Review
Notes. The existing requirement test now protects rename/duplicate/delete instead of
requiring search/sort. These verify candidate accuracy, not the wording of a prose edit.

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

## Candidate-accuracy regression

The November 9 review predates repository search/filter UI (November 10), favorites
(November 11) and sorting/grouping UI (November 14). Immutable commits are linked in the
assessment. This exposed an error in #151’s proposed copy and recent Duo capture labeling.

- RED: all **35 new negative cases failed** against the previous validator because the
  candidate-accuracy check was absent (64 other metadata cases deselected, 0.33s).
- GREEN: **100 metadata tests passed**, 0.64s, including the new cases and revised
  positive requirements. The revised field pack passes **45 checks / 18 fields**.
- The initial PR CI/Mobile CI runs were canceled before updating the patch so their
  results are not final validation. Exact final-head runs are recorded in the PR.
