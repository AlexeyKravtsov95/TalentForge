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

## Real JVM staging rehearsal (controller)

Both runs used the isolated local HTTPS TEST stand for `1.60.1.70450 → 1.60.1.70532`, without
cloud credentials. Actual client tests ran (no cached/skipped JUnit substituted for evidence).

| Run | Data SHA | Tooling SHA | Result |
| --- | --- | --- | --- |
| Baseline | `e4e83fdaea523d732d0894c9762cacd300c7bcb5` | same `e4e83fd` | 6/6 JVM tests PASS, actual 238s; installed 397 files match path/SHA-256/size |
| PR core tooling | `e4e83fdaea523d732d0894c9762cacd300c7bcb5` | `f86227d78f4214d3b4e3382ac40559e163790a8f` | 6/6 JVM tests PASS, actual 204s (Gradle 3m23s); installed 397 files match path/SHA-256/size |

PR-tooling run: 2026-11-01T00:31:54Z → 00:35:23Z; guard G1 (unchanged checkout/product/keyring)
and G2 (temporary keys scrubbed) PASS. Production was neither read nor written. Workflow/docs
integration changes after `f86227d` require the controller's final assertion that staging/mobile
runtime is unchanged, to establish the scope of that tooling rehearsal.

## Final review boundary

Controller final whole-Python suite and final whole-branch review: **PENDING** at this record.
The JVM runs above are completed evidence, not a claim about a later unexamined runtime diff.
Android emulator/device, iOS simulator/device and physical devices: **NOT RUN**.
No actual production delivery, key import, owner environment restriction, IAM scope, credential
revocation or Telegram send is proven by local code/tests. Those remain owner setup/first-next-release
verification, not placeholders for unfinished workflow implementation.
