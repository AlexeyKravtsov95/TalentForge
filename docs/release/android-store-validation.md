# Android public store delivery validation, 2026-11-03

## Scope and real evidence

Implementation branch: `codex/rustore-appgallery-delivery`, based on main
`b114dd97c8437960af7927772c5dd6a33fb2ce8d`. Separate version PR:
[#111](https://github.com/AlexeyKravtsov95/TalentForge/pull/111), Android code 5.
The live-console audit and limits are recorded in [the runbook](android-public-stores.md).

Owner created both store credential secrets and RUSTORE_KEY_ID directly in GitHub.
Only their names/presence were checked. APPGALLERY_APP_ID=119465493 is configured.
The two new environments are main-only; the public approval requires the sole
owner User and disables admin bypass. Existing signing and notification values
were not read or changed.

Authorized real store API calls, uploads, moderation and publication have **not**
been exercised: those must run from owner-merged main with the protected settings.
No new production APK, production APK hash or successful publication is claimed.

## Initial implementation checks

- `ruff check .`: passed.
- `ruff format --check .`: 414 files already formatted.
- `mypy`: no issues in 116 source files (repository's configured src scope).
- `pytest tests/unit/test_android_store_delivery.py tests/unit/test_store_delivery.py -q --tb=short`:
  **143 passed** after the final URL-error regression.
- Full Python baseline: **2597 passed, 11 deselected**, 11m54s. This run was
  collected before the last malformed-URL regression was added; all **143**
  delivery tests were then rerun against the final URL handling. GitHub CI for
  `b0fc924` subsequently passed **2598 tests, 11 deselected**, Ruff (415 files)
  and mypy: [run 41569861672](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41569861672).
- Earlier full baseline also passed: **2572 passed, 11 deselected**.
- `actionlint -ignore 'label "xcode-27" is unknown' .github/workflows/store-delivery.yml .github/workflows/android-store-reconcile.yml`:
  exit 0. Without the filter the local actionlint reports only the pre-existing
  `xcode-27` runner label in the unchanged iOS jobs. No iOS runner was substituted.
- `git diff --check`: passed.

Mobile checks for version PR #111, run in its isolated worktree:

- `python3 scripts/sync_mobile_datasets.py --check`: exit 0, bundle in sync.
- `./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:assembleDebug --console=plain`:
  BUILD SUCCESSFUL, 6m44s; shared 373 tests, 0 failures/errors; desktop 879 tests,
  0 failures/errors, 64 opt-in skips; debug APK assembled. No production signature
  was created locally.

Read-only production OTA preflight:

`PYTHONPATH=src python scripts/ota_production_preflight.py --expect published --keyring docs/ota/keys/production-keyring.json --min-validity-days 30`

Result **OK**, 2026-11-03T02:33:38Z. All 3 listed releases rehashed; current catalog
sequence 3, active 1.60.1.70707-r1, 44 days until expiry (>30 required). TLS and
catalog signature passed. Existing plain-HTTP warning remains: clients themselves
reject HTTP. Anonymous writes were not probed. OTA settings and infrastructure
were not modified.

## Review and regression evidence

An independent whole-branch review identified an approval restriction issue and
six recovery/security issues. One fix pass addressed all of them. The regression
run first failed 11 cases and then passed with the legacy delivery tests:

- only the owner User may approve, not an arbitrary additional reviewer;
- one damaged journal cannot discard another release's result;
- each store retains its own publication proof and observation time;
- unchanged polls do not consume release assets (1100 polls tested);
- GitHub asset read failures are bounded, typed and redacted;
- Telegram deduplicates a transition, while a return to an earlier error sends
  a new notification;
- Huawei upload Host must match its approved authority, with no duplicate casing.

The review's upload-time note was treated as operationally important: two minutes
are measured from successful upload completion, so a slow upload cannot exhaust
its own parsing interval. A separate malformed-URL regression confirms that
provider URL parse errors are typed/redacted as well. Both were observed failing
before their fixes and passed afterward.

All store tests use `httpx.MockTransport`; they are not evidence of real API access
or successful publication. The later owner review found the two P2 paths below.

## Owner P2 follow-up on b0fc924

Eight new regression cases were first observed failing on the old behavior:

- Explicit Huawei attach rejection reproduced `STORE → PROCESSING → PROCESSING`;
  lost and malformed responses had the same false recovery. All three now retain
  the original typed error across three polls and JSON journal reloads, with one
  upload/attach attempt. A matching package readback resolves the failure.
- An attach intent with no response/error receipt stays UNKNOWN on later polls.
- The real `notify` CLI previously sent no Telegram message for missing
  `approved.apk` or `manifest.json`. Both cases now send a durable operational
  alert, retain the original report error and return nonzero. Three repeated
  polls, lost Telegram responses, journal repair and recurrence are exercised
  through HTTP fixtures and the actual report/notifier path.

The final targeted delivery run passed **151 tests**. Ruff, format, mypy and
`git diff --check` are rerun for this follow-up. A new full Python baseline and
GitHub CI validate the updated snapshot; results are recorded in PR #112.
The PR remains Draft for review of these corrections; it has not been merged.

`graphify update .` ran without LLM/API work. It retains the existing three Kotlin
parser warnings (BuildManagementUiTest, LicensesScreenUiTest, OtaSessionTest);
these are graph extraction warnings, not failed Kotlin compilations. The generated
graph/report/aggregated HTML are committed with the implementation.
