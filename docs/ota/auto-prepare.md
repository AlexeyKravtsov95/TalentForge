# Automatic OTA data preparation (M5.OTA.AUTO.1)

When wago.tools lists a new **publishable** WoW: Forever build that production does not serve
yet, `.github/workflows/ota-prepare.yml` prepares its data for a later OTA release with the
existing pipeline, checks and publisher gates, and keeps the result as a GitHub Actions
artifact for review.

It **never publishes**. It does not stage, promote, sign, upload to any bucket, push, commit to
the repository, open a PR or merge; it does not change `PRODUCT_BUNDLE`, `BUNDLED_BUILDS`,
`main` or any app build. It holds **no secret**: no production signing key and no S3
credential exist in CI (the production catalog is read anonymously over HTTPS and verified
against the committed public keyring). Publication stays the owner's manual procedure
([publisher-runbook.md](publisher-runbook.md), [production-deployment.md](production-deployment.md)).

## How it runs

```
Dataset freshness (daily, M5.DATA.WATCH)  ──completed──▶  OTA data preparation
Actions → OTA data preparation → Run workflow (build / base / force)  ──▶  (same job)

  1. checkout main (full history), Python 3.12, pip install -e ".[dev]"
  2. list kept artifacts (gh api, actions: read)      → idempotency
  3. scripts/ota_prepare.py detect                     → prep/detection.json
       wago.tools /api/builds via the freshness check (the existing detection),
       PRODUCT_BUNDLE (BundledDatasets.kt), the signed production catalog (anonymous GET)
  4. scripts/ota_prepare.py prepare  (only when detect says PREPARE)
       disposable clone of main → existing pipeline commands → local candidate commit
       → build_release + gates G1–G8 + review report → bundle checks + offline tests
  5. upload prep/ as  ota-prepare-<game>-<build>-r<n>-from-<base>-main-<sha12>-<STATUS>
  6. the job ends with the final status (below)
```

Code: `src/talent_data/prepare/` (`detect.py`, `production.py`, `orchestrate.py`, `runner.py`),
`scripts/ota_prepare.py`. Permissions: `contents: read`, `actions: read`; `concurrency:
ota-prepare` (queued, never cancelled); `timeout-minutes: 60`.

## Detection: what counts as a new build

`detect` combines three independent facts and never confuses them:

| fact | read from | meaning |
| --- | --- | --- |
| build index | wago.tools `/api/builds`, through `evaluate_freshness` (the existing rule: `is_bgdl` explicitly `false`) | which builds are publishable; never "live" |
| `PRODUCT_BUNDLE` | the app (`BundledDatasets.kt`, via `scripts/mobile_bundles.py`) | what the APK carries |
| production OTA | `https://ota.mythweld.com/v1/channels/production/catalog.json`, verified with `docs/ota/keys/production-keyring.json` (`tf-prod-*` only) on the wall clock | what OTA devices may activate |

Production is `PUBLISHED` (verified), `ABSENT` (403/404 - nothing published) or `UNKNOWN`
(network error, other HTTP status, oversized, wrongly signed, malformed or expired). **A build
counts as published only when a verified catalog lists it** - never because of a file in the
repository, an approval record or a staging catalog. An `UNKNOWN` catalog publishes nothing,
so the build is prepared, but the preparation cannot end `PREPARED`.

| result | when |
| --- | --- |
| `PREPARE` | the latest publishable build (or the requested one) is newer than the base and the verified production catalog does not list it |
| `NO_ACTION` | no newer build; the build is `PRODUCT_BUNDLE`; the verified catalog already lists it; it is not newer than the base; or the same inputs were already prepared |
| `UNKNOWN` | the build index failed or is inconclusive (freshness `UNKNOWN`), a requested build is not listed or not publishable, or the base has no committed dataset on `main` |

The **base** of the diff is what an up-to-date install shows: the newer of `PRODUCT_BUNDLE` and
the active production release (or the `base` input). Today both are `1.60.1.70450`.

## Preparation

In a disposable clone of `main` (the runner's checkout is never modified), with one log per step:

| phase | commands (existing CLI) | failure |
| --- | --- | --- |
| source (network) | `fetch`, `visuals fetch`, `tooltips fetch`, `localization fetch --locale ruRU` | exit 5 (wago.tools unreachable after the HTTP client's retries) → `UNKNOWN`; any other → `BLOCKED` |
| pipeline (offline) | `normalize --class all`, `manifest --expect-complete`, `diff --class all --from <base>`, `visuals normalize/check`, `tooltips normalize/check`, `localization normalize --classes all/check` | `BLOCKED`; later steps are `SKIPPED` |
| scope | only the candidate's own `data/*/forever/<build>/`, diff and coverage files may change; when `main` already carries the build (forced re-run), its classes must be identical (`contentHash`) and main's bytes are kept | `BLOCKED` |
| publisher | local candidate commit (never pushed) → `build_release` → `validate_release` (G1–G8, production channel) against a verified mirror of production → `render_review_report` | a STOP gate → `BLOCKED` (G1 excepted, below); a FLAG → a required human step |
| regression | `scripts/sync_mobile_{datasets,visuals,tooltips,localization}.py --check` (the app's bundles are unchanged), `pytest` (offline suite with the candidate data present) | `BLOCKED` (every check still runs) |

G1 always stops on a prepared candidate - its commit is not on `main`. That is the expected
state, reported as a required step: the data reaches production only after a reviewed refresh
PR is merged and the release is rebuilt from the merge commit.

## Statuses and the job result

| status | job exit | meaning |
| --- | --- | --- |
| no action | 0 (green) | nothing new, already published, bundled, or already prepared |
| `PREPARED` | 0 (green) | every step, check and test passed; no gate stopped except G1. **Not publishable**: `publishable` is always `false`; the report lists the remaining human steps |
| `BLOCKED` | 3 (red) | source data, the pipeline, a gate, the scope guard, a bundle check or a test failed; diagnostics are in the artifact |
| `UNKNOWN` | 4 (red) | not established: build index inconclusive, wago.tools unreachable, production catalog unverifiable or changed during the run |
| failure | 1 (red) | the detection or preparation itself could not run (`crash.txt` holds the traceback) |

## The artifact

`ota-prepare-…-<STATUS>` (30 days) contains `prep/`:

* `detection.json` (`ota-prepare-detection` v1) and `out/report.json` (`ota-prepare-report`
  v1: status, `publishable: false`, candidate/base/production facts, every step with its exit
  code and log, every gate, findings `BLOCKER` / `UNKNOWN` / `REQUIRED`, the kept files);
* `out/summary.md` (also the job summary);
* `out/data/{normalized,visuals,tooltips,localization}/forever/<build>/` - the prepared data;
* `out/artifacts/diff/forever-*-<base>-<build>.json` - the semantic diff (all classes + aggregate);
* `out/artifacts/{visuals,tooltips,localization}/` and `out/docs/research/` - coverage reports;
* `out/ota/<releaseId>/release.json`, `out/ota/review-report.txt` - the publisher's view of the
  candidate (built from the local commit: **not** the manifest to approve or publish);
* `out/logs/<step>.log` - one per command, including `pytest.log`;
* `out/candidate.bundle` - the local candidate commit (`git bundle`, prerequisite: `main`).

A run without preparation uploads `ota-prepare-detection-<run id>` (7 days).

## Idempotency and network errors

* The preparation key names the inputs: game, build, release revision, base (release id or
  build) and the `main` commit. A kept `PREPARED` or `BLOCKED` artifact with that key makes the
  next detection `NO_ACTION`; `UNKNOWN` is retried on the next run; `force` prepares again.
  A failed artifact listing is recorded (`priorListing: unavailable`) and only causes a repeat.
* Every run starts from an empty clone and writes only its own artifact: re-running is safe.
* wago.tools requests keep the existing HTTP client's retries (timeouts, 408/425/429/5xx,
  truncated bodies); what still fails is `UNKNOWN`, never "no new build". A definitive 4xx
  (e.g. a table wago.tools does not have) is source data, i.e. `BLOCKED`.

## From PREPARED to a release (manual)

1. Download the artifact (Actions run page, or `gh run download <run-id> -n <artifact name>`),
   read `summary.md`, `report.json`, the diff and `review-report.txt`.
2. Turn the candidate into a refresh branch and PR (the bundle needs `main` locally):

   ```bash
   git fetch origin main
   git fetch <dir>/prep/out/candidate.bundle ota-prepare/<build>:data/forever-<build>
   ```

   Review it like any data PR (diff, warnings, allocation compatibility); merge manually.
   Bundling the build into the app (`BUNDLED_BUILDS`, `PRODUCT_BUNDLE`) is a separate decision
   and not needed for an OTA-only release.
3. Rebuild the release from the merge commit and follow
   [publisher-runbook.md](publisher-runbook.md) §1-2: the approval record
   (`docs/ota/releases/<releaseId>.md` with live-build evidence for G7, G5 acceptance if
   flagged, the pinned manifest SHA-256 of the **rebuilt** manifest) merged by PR, the staging
   smoke, then the owner's `promote` → offline signing → `attach-signature` → `verify-live`.

## Run it manually

* GitHub: **Actions → OTA data preparation → Run workflow** (`build`, `base`, `force`). Runs
  on `main`; the workflow must exist on `main` to be listed.
* Locally (read-only for the checkout; network for detection and fetching):

  ```bash
  python scripts/ota_prepare.py detect --output prep/detection.json      # [--build B] [--base B] [--force]
  python scripts/ota_prepare.py prepare --detection prep/detection.json --out prep/out
  ```

  `prepare` clones the checkout into a temporary directory (or `--workspace DIR`, empty) and
  resolves `origin/main` from the checkout's own `origin/main`. The pipeline commands run the
  clone's code (`origin/main`), not the working tree; the orchestration itself runs the
  installed package.

## Verification (2026-10-22)

* Detection against the real sources: `NO_ACTION` - `1.60.1.70450` is the latest publishable
  build and the verified production catalog (sequence 1) lists `1.60.1.70450-r1`.
* Forced end-to-end run (`--build 1.60.1.70450 --base 1.60.1.70374 --force`, revision r2) on a
  developer machine: every pipeline step passed against wago.tools; G2-G6 and G8 passed, G4
  found the regenerated data identical to the published `1.60.1.70450-r1` and the bundled copy;
  G1 (not on main) and G7 (no evidence for r2) were reported as required steps. The first run
  ended `BLOCKED`: a forced re-run rewrote the committed build's volatile `generatedAt`, which
  failed the byte-level bundle check and 11 fixture tests. The preparation now keeps main's
  bytes when the classes are identical; the second run ended `PREPARED` in 4 min 37 s: 18/18
  steps passed (the offline suite in the clone: 1565 passed), 434 files / 6.2 MB kept, and
  `git fetch candidate.bundle ota-prepare/<build>:data/forever-<build>` restored the candidate
  commit in a fresh clone.
* Not run here: the workflow itself on GitHub - `workflow_dispatch` lists a workflow only once
  it is on `main`.

## Testing

`tests/unit/test_ota_prepare.py` (offline): every detection outcome over real freshness
evaluations, the production reader against an in-memory origin (signed with TEST keys under
`tf-prod-*` ids; absent, unreachable, wrongly signed, expired) and the verified mirror, the
step list, the scope guard, and `prepare` end to end over a temporary git repository with a
fake runner - `PREPARED` / `BLOCKED` / `UNKNOWN`, skipped steps, kept files, an untouched source
checkout, forced re-runs of a committed build, repeated runs - plus the CLI's exit code 5 and
the workflow's no-secret / no-publish properties.
