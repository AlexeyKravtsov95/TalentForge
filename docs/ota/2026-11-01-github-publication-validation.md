# GitHub production publication: validation, 2026-11-01

Implementation evidence for PR C (Tasks 4–6), based on offline TEST keys, local stores and
mock GitHub/public transports. No production read/write, real key/App/environment/IAM setup,
flag enabling, remote journal initialization or real Telegram delivery was performed.
Current `1.60.1.70532-r1` was not reissued and no new G7 requested. Deployment remains disabled
until the owner's post-review setup in [publisher-runbook](publisher-runbook.md#github-production-automation-owner-setup).

## Behavioral RED → GREEN

These are actual recorded runs; collection errors are distinguished from behavior failures.
Commands below use `PYTHONPATH=src` and the project's Python 3.12 venv pytest.

| Phase / regression | Actual RED | Actual GREEN / scope |
| --- | --- | --- |
| Task 4: fresh approval and durable PREPARED barrier | temporary guard removal: 2 failed in 24.84s; original source restored | publisher/state/signer/prepare regression set: 63 passed in 93.20s |
| Task 4: changed staged same-build base | 1 failed in 22.38s (incorrectly reached PUBLISHED_NOT_VERIFIED) | included in the final 63-test set |
| Task 5: failed directory fsync and removed request | 1 failed in 31.79s; 1 failed in 27.60s | reconciliation/publisher/state/public-verifier set: 56 passed in 98.03s |
| Task 5: signed wrong expected public payload and immediate resign repeat | temporary guard removal: 2 failed in 21.14s; source restored | included in the final 56-test set |
| Task 6 CLI: source CI path/repository/event/checks/attempt/action, automatic sender | 7 failed, 7 passed in 1.76s | 14 passed in 1.58s, then expanded final CLI set below |
| Task 6 CLI: stale request/API evidence at upload and WRITE_STARTED barriers | temporary evidence cache: 2 failed in 17.49s; source restored byte-for-byte | final seven-file CLI/publisher/reconcile/state/signer/Telegram set: 166 passed in 247.45s |
| Task 6 workflow: renewal must still run after candidate failure | temporary dependency on writer success: 1 failed in 0.12s; workflow restored byte-for-byte | included in focused workflow suite below |
| Task 6 interrupted status file | real truncated status: 1 failed in 1.78s | protected finalizer validates strict existing status; missing/partial status becomes AMBIGUOUS, valid status is preserved |
| Task 6 cleanup after catalog PUT | real profile/writer context exit failure: 2 failed in 17.52s (BLOCKED despite durable PUBLISHED_NOT_VERIFIED) | outcome retained as PUBLISHED_NOT_VERIFIED, safe CLEANUP/CLEANUP_FAILED; verification-only retry without PUT |
| Task 6 notification: healthy resign must preserve candidate refusal dedup | real behavior failed; initial combined run: 2 failed in 22.24s (other failure was absent workflow file) | fixed operation-scoped recovery; healthy hourly observation and resign stay quiet without clearing candidate failure |

Initial Task 4/5/6 missing-module collection RED runs were scaffolding evidence only. During
workflow implementation, one assertion was aligned with the actual fixed `OPERATION: resign`
environment rather than literal shell text; one subprocess test was corrected to use the venv
Python installed by setup-python on CI. These test-harness failures are not safety regressions.

The final CLI offline rehearsal exercises exact approved package/rerun/lost webhook,
revoked approval and changed API/request evidence, foreign artifact/ref/attempt, partial blob
upload, lost PUT answer, corrupt journal, retained removed AMBIGUOUS work, changed base,
legitimate higher-sequence rollback with terminal receipt retention, CDN lag with verification-only
retry, removed request/expired artifact, independent same-active renewal, and notification
outage/dedup/public corruption. Real encrypted TEST PEM signing and temporary AWS profile are
used; TEST PEM/password/access/secret values are absent from stdout/stderr, summary/status,
journal/export artifacts and recorded subprocess arguments, including failure cleanup.

## Workflow and Python checks

Actual focused command:

```bash
PYTHONPATH=src /Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/pytest \
  tests/unit/ota/test_automation_e2e.py \
  tests/unit/ota/test_automation_workflows.py tests/unit/test_telegram_notify.py -q
```

Result: **126 passed in 147.23s**. This covers real extracted workflow mode selection and
interrupted-writer shell behavior, topology/permissions/secrets/pinning/artifact boundaries,
existing authorization tests and notification tests. The full YAML/expression grammar is
checked separately with the actual actionlint binary, rather than a test-only YAML parser.
An additional bootstrap assertion checks that enabled/manual/execute init preflight returns
READY with a missing journal without initializing it or reading production secrets:
`pytest tests/unit/ota/test_automation_e2e.py::test_explicit_one_time_journal_setup_is_manual_and_never_reads_production_secrets tests/unit/ota/test_automation_workflows.py -q`
passed **25 tests in 7.95s**. Final post-integration focused command adds `tests/unit/ota/test_automation_signer.py` to
those three files: **134 passed in 184.01s**. This includes both profile/writer late-cleanup
regressions and the bootstrap assertion. After adding interrupted partial-status recovery,
the final workflow-only run `pytest tests/unit/ota/test_automation_workflows.py -q` passed
**24 tests in 4.73s**.

```bash
/opt/homebrew/bin/actionlint .github/workflows/ota-production-publish.yml .github/workflows/ota-production-watch.yml
/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/ruff check .
/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/ruff format --check .
/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/mypy
git diff --check
```

Results: actionlint **1.7.12**, exit 0; Ruff **All checks passed**; **404 files already
formatted** in the earlier run; final format check: **405 files already formatted**; mypy **no issues in 116 source files**; diff check exit 0. Graphify AST update succeeds on the final implementation and is included in the commit.
The final workflow-only partial-status check passed as recorded above.

The four journal/writer jobs share `ota-production-writer`, cancel false, with explicit
sequential dependencies; daily renewal uses `always()` even after blocked candidates.
Protected jobs also refuse cancellation, so cancelling a workflow cannot start a later writer.
No nested same-group workflow lock, unsupported queue syntax, secret cache, arbitrary ref,
artifact code execution or mutable Action tag exists in the changed publication/watch paths.
Only status files cross jobs in four distinct immutable V4 artifacts. Missing or truncated writer status
after possible PUT is AMBIGUOUS. Notifications share `ota-production-watch` and
`telegram-notify-state/production.json`; Telegram failure never changes a publication receipt.

## Task 6 integration review fixes

The first whole-branch review of `2b7bd01` found four real integration defects. The
following regressions reproduced them before correction using TEST keys, local Git pack
transport and local storage only:

| Regression | Genuine RED | Corrected behavior |
| --- | --- | --- |
| Clean checkout with an authentication-required journal remote | one of 7 failures in 79.57s | `git_auth` supplies URL-scoped GitHub authorization only in the guarded command environment; no persisted checkout credentials, token argument/config/file, and inherited variables restored on success/refusal |
| Approval revoked between extraction and provenance refresh; inverse request race | four of the same 7 failures, covering upload and WRITE_STARTED barriers | approval, request/provenance and G1 bind one fresh main SHA; a refetch or final check observing a different SHA refuses the assessment |
| Blocked candidate followed by actual due renewal | one of the same 7 failures | successful fully verified resign reports VERIFIED independently, retains the blocked candidate and its notification failure marker, and repeats quietly |
| Lost PUT with public files unavailable | one of the same 7 failures | retained unknown work is privately diagnosed before CDN convergence; exact landed bytes become PUBLISHED_NOT_VERIFIED, with no signing/re-PUT; public proof alone advances VERIFIED |
| Other PUBLISHED_NOT_VERIFIED work while an operation remains AMBIGUOUS | 1 failed in 30.30s after the initial recovery-order correction | private diagnosis remains first, then all retained public-verification retries run; global ambiguity still forbids writes |
| Fake staging ZIP changes at a two-second clock boundary | 2 failed in 78.56s, deterministic mocked clock, no sleep | fixed ZipInfo metadata for normal staging artifacts and the wrong-base variant; production archive digest enforcement unchanged |

The first four fixes passed their exact seven regressions: **7 passed in 58.69s**.
The entire preparation test file plus the two recovery follow-ups then passed **42 tests in
48.72s**. These checks use real signature/package/gate/reference verification; only GitHub,
public HTTP and the local authentication-required SSH boundary are synthetic. The Git test
runs real fetch/push/pack operations and records a durable BLOCKED receipt through the CLI.
It checks that neither the TEST token nor its Basic encoding appears in stdout/stderr,
command arguments, status/summary/output, journal or repository configuration.

The controller's prior whole-suite run at `2b7bd01` was **1 failed, 2506 passed,
11 deselected in 505.24s**. Its failure was
`test_automation_prepare.py::test_retry_before_pr_creation_discovers_existing_exact_package`:
the fake staging artifact was regenerated with wall-clock ZIP headers between metadata and
download. The deterministic clock regression above reproduces and fixes that fixture defect;
it does not weaken the production verifier. The controller's rerun at `e91e710` passed
**2517 tests, 11 deselected in 561.29s**. This is **PRE-final-fix** whole-suite evidence;
it predates the two final-review corrections below and does not validate their final state.

The review-round-1 focused command was:

```bash
PYTHONPATH=src /Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/pytest \
  tests/unit/ota/test_automation_e2e.py tests/unit/ota/test_automation_workflows.py \
  tests/unit/ota/test_automation_publisher.py tests/unit/ota/test_automation_reconcile.py \
  tests/unit/ota/test_automation_state.py tests/unit/ota/test_automation_signer.py \
  tests/unit/ota/test_automation_public_verify.py tests/unit/ota/test_automation_prepare.py \
  tests/unit/test_telegram_notify.py -q
```

Result at `e91e710`: **239 passed in 416.07s (0:06:56)**. This includes the authenticated Git journal,
both snapshot race directions, real renewal/status/notification, unavailable-public-content
recovery, remaining PNV retries, fixed ZIP clock boundary and prior cleanup/serialization/
notification regressions. An intermediate broad run before the final recovery/fixture
follow-ups passed 236 tests in 370.02s and is not substituted for this final result.

Final Ruff check: All checks passed; format check: **405 files already formatted**; mypy:
**116 source files, no issues**; working/staged diff checks: exit 0. Actual actionlint on both
production workflows was repeated, exit 0. No workflow source changed in this correction.
Graphify AST update: **14,590 nodes / 46,555 edges / 442 communities**, with the same unchanged
three Kotlin partial-extraction and seven no-symbol warnings. No full suite or Gradle was
run by the fix phase; controller validation and independent re-review were completed later below.

## Consolidated final-review correction

The final review identified two enabled-path defects at `e91e710`. Both were reproduced
before changing implementation code:

| Regression | Genuine RED | Corrected boundary |
| --- | --- | --- |
| Approval or request removed during the last reference/appended-release object reads | four failures within **6 failed in 89.75s**; each incorrectly reached VERIFIED | after all expensive checks, fetch main again and require the exact full-assessment SHA before entering the short live-sequence-read/PUT path; observed revocation is BLOCKED with zero catalog PUT |
| Valid signed older public catalog versus newer authoritative S3 base, through preflight and publish | two failures in the same six-test RED; both recorded irreversible STALE and reported NO_ACTION | public-only mismatch becomes retryable BLOCKED; no PEM/profile/writer readers are loaded for that failed preflight |
| Public LIVE_CHANGED and ALREADY_LISTED with later convergence | **2 failed in 72.88s** | both remain retryable; confirmed terminal receipts remain unchanged |

The exact eight regression cases then passed **8 tests in 137.72s**. The CDN regression
constructs a signed metadata-only `70374-r2` base and a newer signed rollback to `70374-r1`,
retaining both entries. CDN initially serves the older active `r2`; the untouched committed
request correctly targets authoritative `r1` for the genuinely unpublished `70450-r1`
candidate. After public convergence, daily reconciliation publishes that candidate once,
verifies it and deduplicates notification/retry. No manual journal alteration, request change,
repack or createdAt change is used. Real package/provenance/gate/signature/reference checks
execute; only transports and TEST keys are synthetic.

No core store API, staging/mobile runtime, contracts, keyring, product/data or G1–G8 change
was needed. Durable WRITE_STARTED/readback, private base/succession/signature checks and
lost-PUT recovery are retained. Public-only refusals do not reset STALE/VERIFIED/PNV receipts;
current 70532 and trusted-main request removal still provide independent terminal proof.

GitHub fetch → the short private live-sequence read → S3 PUT cannot be an atomic cross-service
transaction. Revocation after the final fetch can still race that minimal window. The
runbook/threat model now say this explicitly and require stop/drain plus credential revocation
when immediate cessation is necessary. No production setup or delivery is inferred from TEST.

The expanded focused run added `tests/unit/ota/test_automation_provenance.py` to the nine-file
review-round-1 command above: **1 failed, 316 passed in 431.97s**. The sole failure was the
new 70532 exclusion test fixture: it changed releaseId without changing build, and strict
ReleaseRequest validation correctly refused the inconsistent identity before reconciliation.
The fixture now supplies both fields. This is a fixture error, separate from the genuine
behavior REDs above; production source was unchanged after the expanded run.

Corrected current-tree module command:

```bash
PYTHONPATH=src /Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/pytest tests/unit/ota/test_automation_reconcile.py -q
```

Result: **24 passed in 33.99s**. This rechecks both retryable mismatch reasons, preserved
terminal/removed/70532 guards, private renewal/recovery, full public proof and verification-only
CDN retry. The other focused files and both actual defect regression families passed in the
expanded run; the definitive controller whole-suite result is recorded below.

Final checks: Ruff **All checks passed**; format **405 files already formatted**; mypy
**116 source files, no issues**; actual actionlint **1.7.12**, exit 0 on both production
workflows (unchanged); diff checks exit 0. Final Graphify AST update: **14,603 nodes /
46,604 edges / 451 communities**, with the same three Kotlin partial-extraction and seven
no-symbol warnings. Its required full graph rewrite is committed. Logs are retained in the
local final-fix report bundle; no full-suite or Gradle run is claimed by this correction.

## Real JVM staging rehearsal (controller)

Both runs used the isolated local HTTPS TEST stand for `1.60.1.70450 → 1.60.1.70532`, without
cloud credentials. Actual client tests ran (no cached/skipped JUnit substituted for evidence).

| Run | Data SHA | Tooling SHA | Result |
| --- | --- | --- | --- |
| Baseline | `e4e83fdaea523d732d0894c9762cacd300c7bcb5` | same `e4e83fd` | 6/6 JVM tests PASS, actual 238s; installed 397 files match path/SHA-256/size |
| PR core tooling | `e4e83fdaea523d732d0894c9762cacd300c7bcb5` | `f86227d78f4214d3b4e3382ac40559e163790a8f` | 6/6 JVM tests PASS, actual 204s (Gradle 3m23s); installed 397 files match path/SHA-256/size |

PR-tooling run: 2026-11-01T00:31:54Z → 00:35:23Z; guard G1 (unchanged checkout/product/keyring)
and G2 (temporary keys scrubbed) PASS. Production was neither read nor written. Workflow/docs
integration changes after `f86227d` were compared by the controller: staging/mobile and core
runtime paths are unchanged through `c1f573f`. The later changes affect automation, tests,
workflow, documentation and the required generated graph, establishing the rehearsal's scope.

## Final review boundary

Controller whole-Python suite at `e91e710`: **2517 passed, 11 deselected in 561.29s**, explicitly
**PRE-final-fix** evidence. Final whole-branch review identified two Important findings; both
were fixed in `c1f573f` and independently scoped re-reviewed as ADDRESSED, with no new
Critical/Important breakage. All code-review findings are closed. The definitive final checks
on `c1f573f723e13157eefc54dd65e9106ec29bbaf4` are:

| Command | Actual result |
| --- | --- |
| `ruff check .` | All checks passed, exit 0 |
| `ruff format --check .` | 405 files already formatted, exit 0 |
| `mypy` | No issues in 116 source files, exit 0 |
| `pytest` | **2525 passed, 11 deselected in 641.05s (0:10:41)**, exit 0 |
| `actionlint .github/workflows/ota-production-publish.yml .github/workflows/ota-production-watch.yml` | Actual actionlint 1.7.12, exit 0 |
| `git diff e4e83fd..HEAD --check` | exit 0 |

The pytest process used the existing project virtualenv and `PYTHONPATH=src`; production,
AWS/S3, GitHub and Telegram credential environment variables were removed. The repository's
pytest configuration excludes integration tests. A cold, sanitized real CLI invocation of
`preflight --status-file <temporary path>` also returned explicit DISABLED, exit 0,
`writerNeeded=false`, with no operations. No production dependency was initialized.

Controller diff assertions confirm no changes to mobile, data, public schemas, production
keyring or PRODUCT_BUNDLE, and no staging/core runtime change after the PR-tooling JVM run.
The following documentation/graph-only commit records these results; Python/workflow logic
has not changed since the tested code commit. The worktree is retained for owner review.

The final full pytest log, scoped re-review and sanitized rehearsal reports were retained in
the local evidence bundle. The JVM runs above are completed evidence with the runtime scope
explicitly checked, not a claim of production deployment.
Android emulator/device, iOS simulator/device and physical devices: **NOT RUN**.
No actual production delivery, key import, owner environment restriction, IAM scope, credential
revocation or Telegram send is proven by local code/tests. Those remain owner setup/first-next-release
verification, not placeholders for unfinished workflow implementation.

## PR #105 follow-up: journal loss after PUT and request-independent renewal

Two owner-reproduced failures were corrected relative to `144594ce9642db411a9ca2c0ecb481c6d8cc6308`.
All new scenarios use the existing offline rehearsal: real CLI, exact package/provenance,
approval gates, TEST signatures, local Git/journal/store and public reference verification.
Telegram delivery is an in-process TEST sender only.

### Post-PUT journal outage

The fixture makes `journal._read` unavailable only after a successful real
`store.put(CATALOG, ...)`. Each publish/resign case checks that the catalog actually changed
and exactly one catalog PUT occurred **before** asserting the CLI result. A typed
`OutcomeJournalError` now carries the diagnosed receipt out of the publisher; the CLI retains
known outcomes independently of another journal read. This is reporting evidence only and
cannot authorize a write or replace the durable WRITE_STARTED barrier.

- Exact private S3 read-back plus all referenced objects: `PUBLISHED_NOT_VERIFIED`, exit 3.
- Unavailable private catalog read-back: `AMBIGUOUS`, exit 3.
- Both include the pinned operation and `JOURNAL / JOURNAL_FAILED` in status/summary. The
  notification path reports the failure once and does not advance the activation marker.
- Once the journal returns, the retained WRITE_STARTED work is diagnosed and publicly
  verified without another catalog PUT, signing-key read, package download or sequence bump.
  Further runs are NO_ACTION; publish notifies the verified activation once, resign is quiet.
- A journal failure at the WRITE_STARTED save **before** PUT still refuses the write.

### Renewal isolation

Resign planning now returns after strict journal/unresolved-outcome checks and full signed
public catalog/reference verification, before reading or reconciling release requests.
The rehearsal commits the malformed bytes `{"schemaVersion"}` to main, refreshes its checkout
and metadata SHA, and advances its clock 30 days while the catalog remains valid. Both
`preflight --operation resign` and the writer succeed; renewal keeps active/releases,
increments sequence once and extends expiry. Publication still rejects that malformed
request without another writer/secret read. A valid-request renewal is the positive control.
No provenance, exact-package, G1–G8 or reference-verification requirement was relaxed.

### Behavioral RED → GREEN

Regression selection:

```bash
PYTHONPATH=src /Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin/pytest \
  -q tests/unit/ota/test_automation_e2e.py \
  -k 'journal_outage or due_resign_preflight'
```

The final baseline replay used the **same final tests** and original `cli.py`/`publisher.py`
source from `144594c`, loaded with their original module filenames to preserve the rehearsal's
repository/fixture paths. It did not edit HEAD, the index or working source:
**5 failed, 3 passed, 43 deselected in 72.42s**. Failures were exactly the four post-PUT
publish/resign × exact/unknown cases and malformed-request resign preflight. The two
pre-PUT refusal controls and the valid-request renewal passed. Each post-PUT RED had already
asserted a changed catalog and one PUT, then observed the erroneous BLOCKED result.

Initial fixed-source GREEN: **8 passed, 43 deselected in 69.39s**. Definitive current-source
whole-suite results, including these eight scenarios, are recorded below. An intermediate
broader OTA subset was interrupted to run the mandatory whole suite instead; no complete
subset result is claimed. An attempted isolated source-directory replay failed fixture path
setup and is not counted as behavioral RED evidence.

Independent read-only review found no Critical/Important/Minor issue in the correction.
It covered the three edited Python files, not the full original PR or live infrastructure.

### Definitive follow-up checks

Tested source commit: `60cbfeec6647fd7efda485554b847f887587026e`. The following documentation-only commit records these
results; no Python/workflow behavior changed after the successful whole-suite run.

| Command | Actual result |
| --- | --- |
| `ruff check .` | All checks passed, exit 0 |
| `ruff format --check .` | 405 files already formatted, exit 0 |
| `mypy` | No issues in 116 source files, exit 0 |
| `pytest` (offline) | **2533 passed, 11 deselected in 692.55s (0:11:32)**, exit 0 |
| `actionlint .github/workflows/ota-production-publish.yml .github/workflows/ota-production-watch.yml` | Actual actionlint 1.7.12, exit 0 |
| `git diff --check`, staged check and `git diff origin/main --check` | exit 0 |

Commands used the existing project virtualenv at
`/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin`; mypy/pytest used
`PYTHONPATH=src`. The whole pytest process removed `AWS_`, `OTA_`, `S3_`, `TELEGRAM_`,
`GITHUB_` and `GH_` environment variables. Its 11 integration tests were excluded by the
repository configuration. The whole run passed all 51 automation rehearsal E2E cases,
including the eight new cases, and the existing provenance/publisher/reconciliation tests.
The clean behavioral RED log and successful full log are retained locally as
`/tmp/talentforge-pr105-followup-red-final.txt` and
`/tmp/talentforge-pr105-followup-pytest.txt`.

Required AST-only Graphify refresh: **14,625 nodes / 46,661 edges / 465 communities**.
It retained the same three existing Kotlin partial-extraction and seven no-symbol warnings;
the full generated graph rewrite is included. Diff inspection confirms the correction
changes only automation CLI/publisher, their regressions, this record and Graphify output.
Mobile, data, schemas, keyring, PRODUCT_BUNDLE, G1–G8 and workflows are unchanged.

No new JVM/staging-device run was performed for this Python-only correction. The prior JVM
rehearsal above remains historical evidence for its stated tooling/data SHAs; it is not a
new production-delivery claim. Production was neither read nor published, the repository
enable flag was not changed, real keys were neither requested nor created, and no real
Telegram message was sent. RuStore/AppGallery work is absent. Owner review/merge is required;
no merge was performed.
