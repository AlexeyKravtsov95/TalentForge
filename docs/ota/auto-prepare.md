# Automatic OTA data preparation (M5.OTA.AUTO.1) and review PR (M5.OTA.AUTO.3)

When wago.tools lists a new **publishable** WoW: Forever build that production does not serve
yet, `.github/workflows/ota-prepare.yml` prepares its data for a later OTA release with the
existing pipeline, checks and publisher gates, and keeps the result as a GitHub Actions
artifact. Since M5.OTA.AUTO.3 a `PREPARED` candidate is also proposed for review
automatically: its candidate commit goes to the branch `ota-candidate/<game>-<build>` and a
pull request is opened ([below](#the-review-pull-request-m5otaauto3)), so nobody carries the
artifact over by hand.

It **never publishes**. It does not stage, promote, sign, upload to any bucket or merge; it
never writes to `main` and does not change `PRODUCT_BUNDLE`, `BUNDLED_BUILDS` or any app
build. The only write is the review branch and its PR, made by a separate job (`propose`).
It holds **no production signing or S3 secret** (the
production catalog is read anonymously over HTTPS and verified against the committed public
keyring); the isolated `propose` job uses a repository-scoped GitHub App installation token. Publication stays the
owner's manual procedure ([publisher-runbook.md](publisher-runbook.md),
[production-deployment.md](production-deployment.md)); the publication rules and the
approval gates are unchanged.

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
  6. select the candidate to propose: this run's PREPARED artifact, or the kept PREPARED
     artifact that made the detection NO_ACTION (so a PR that failed before is retried)
  7. the job ends with the final status (below)

job propose  (only when step 6 selected a candidate; M5.OTA.AUTO.3)
  1. checkout main (full history, no persisted credentials), pip install -e .
  2. download that artifact (this run's or the earlier run's, actions: read)
  3. scripts/ota_prepare.py propose  → check → branch ota-candidate/<game>-<build> → PR
  4. upload propose/ as  ota-propose-<run id>-<attempt>;  the final proposal status
```

Code: `src/talent_data/prepare/` (`detect.py`, `production.py`, `orchestrate.py`,
`propose.py`, `runner.py`), `scripts/ota_prepare.py`. Permissions: the workflow default and the
`prepare` job `contents: read`, `actions: read`; only the `propose` job has `contents: write`,
`pull-requests: write`, `actions: read`. `concurrency: ota-prepare` (one run at a time, queued,
never cancelled) covers both jobs; `timeout-minutes` 60 and 15.

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
| `NO_ACTION` | no newer build; the build is `PRODUCT_BUNDLE`; the verified catalog already lists it; it is not newer than the base; or the same inputs were already `PREPARED` |
| `UNKNOWN` | the build index failed or is inconclusive (freshness `UNKNOWN`), a requested build is not listed or not publishable, or the base has no committed dataset on `main` |

The **base** of the diff is what an up-to-date install shows: the newer of `PRODUCT_BUNDLE` and
the active production release (or the `base` input). Today both are `1.60.1.70450`.

## Preparation

In a disposable clone of `main` (the runner's checkout is never modified), with one log per step.
The pipeline steps run in this order, which follows what each command reads
(`pipeline_steps` in `orchestrate.py`; the first failure stops the pipeline and the later
steps are `SKIPPED`):

| # | step | kind | reads |
| --- | --- | --- | --- |
| 1 | `fetch` | source | wago.tools: the talent DB2 tables |
| 2 | `tooltips fetch` | source | wago.tools: the tooltip tables |
| 3 | `localization fetch --locale ruRU` | source | wago.tools: the enUS and ruRU text tables |
| 4 | `normalize --class all --offline` | pipeline | the tables of 1 → writes the dataset |
| 5 | `manifest --expect-complete` | pipeline | the dataset (all nine classes) |
| 6 | `visuals fetch` | source | **the normalized dataset** (icon of every talent entry), then wago.tools: icon tables and client files |
| 7 | `diff --class all --from <base> --offline` | pipeline | the base and the candidate datasets |
| 8–9 | `visuals normalize --offline` / `check` | pipeline | the dataset and the icons of 6 |
| 10–11 | `tooltips normalize --offline` / `check` | pipeline | the dataset and the tables of 2 |
| 12–13 | `localization normalize --classes all --offline` / `check` | pipeline | the dataset, the tooltips of 10 and the tables of 3 |

`visuals fetch` is a network step that needs the normalized dataset: it resolves the icons
from it before downloading them. Until issue #78 it ran second, before `normalize`, and so
failed on every build that `main` does not carry yet (`no normalized dataset for <build>`);
the forced re-runs of `1.60.1.70450` did not show it because `main` already held that dataset.
`tests/unit/test_ota_prepare.py` holds the dependencies of every step and checks the order.

| phase | commands (existing CLI) | failure |
| --- | --- | --- |
| source (network) | steps 1, 2, 3 and 6 | exit 5 (wago.tools unreachable after the HTTP client's retries) → `UNKNOWN`; any other → `BLOCKED` |
| pipeline (offline) | steps 4, 5 and 7–13 | `BLOCKED` |
| scope | only the candidate's own `data/*/forever/<build>/`, diff and coverage files may change; when `main` already carries the build (forced re-run), its classes must be identical (`contentHash`) and its visuals the same by meaning - files, sidecars, icon FileDataIDs, client files (`sourceSha256`) and pixels, PNG bytes excepted ([talent-visuals.md](../talent-visuals.md) §3) - and then main's bytes are kept (`logs/visuals-reproduction.log`) | `BLOCKED` |
| publisher | local candidate commit (never pushed) → `build_release` → `validate_release` (G1–G8, production channel) against a verified mirror of production → `render_review_report` | a STOP gate → `BLOCKED` (G1 excepted, below); a FLAG → a required human step |
| regression | `scripts/sync_mobile_{datasets,visuals,tooltips,localization}.py --check` (the app's bundles are unchanged), `pytest` (offline suite with the candidate data present) | `BLOCKED` (every check still runs) |

G1 always stops on a prepared candidate - its commit is not on `main`. That is the expected
state, reported as a required step: the data reaches production only after a reviewed refresh
PR is merged and the release is rebuilt from the merge commit.

## Statuses and the job result

| status | job exit | meaning |
| --- | --- | --- |
| no action | 0 (green) | nothing new, already published, bundled, or already `PREPARED` |
| `PREPARED` | 0 (green) | the preparation exited 0 **and** reported `PREPARED`: every step, check and test passed; no gate stopped except G1. **Not publishable**: `publishable` is always `false`; the report lists the remaining human steps |
| `BLOCKED` | 3 (red) | source data, the pipeline, a gate, the scope guard, a bundle check or a test failed; diagnostics are in the artifact |
| `UNKNOWN` | 4 (red) | not established: build index inconclusive, wago.tools unreachable, production catalog unverifiable or changed during the run |
| failure | 1 (red) | the detection or preparation itself could not run (`crash.txt` holds the traceback), a step died before recording its result, the detection gave no decision, or exit code and reported status disagree |

The last step (`Final status`) restores these codes from the recorded step outputs; it is green
only for `NO_ACTION` or for exit 0 together with `PREPARED` - any missing or contradictory
output is red. A failed artifact upload fails the job as well.

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
  build) and the `main` commit. Only a kept `PREPARED` artifact with that key makes the next
  detection `NO_ACTION`. `BLOCKED`, `UNKNOWN` and `FAILED` are prepared again on the next
  scheduled run (the detection records the earlier artifact in `priorArtifact` and its
  reason): right after a patch wago.tools may still lack a DB2 table, and a kept failure
  must not hide the build for the artifact's 30-day retention. `force` prepares again in
  every case. A failed artifact listing is recorded (`priorListing: unavailable`) and only
  causes a repeat.
* One run at a time: `concurrency: ota-prepare` queues runs (never cancels one), so a retry
  never overlaps a running preparation.
* Every run starts from an empty clone and writes only its own artifact: re-running is safe.
  The `propose` job is repeatable as well: no second PR, no commit when the branch already
  holds the same files, and a kept `PREPARED` artifact is proposed again until its PR exists
  ([below](#the-review-pull-request-m5otaauto3)).
* wago.tools requests keep the existing HTTP client's retries (timeouts, 408/425/429/5xx,
  truncated bodies); what still fails is `UNKNOWN`, never "no new build". A definitive 4xx
  (e.g. a table wago.tools does not have) is source data, i.e. `BLOCKED`.

## The review pull request (M5.OTA.AUTO.3)

The `propose` job runs `scripts/ota_prepare.py propose` (`talent_data.prepare.propose`) on the
selected artifact. It moves **only the artifact's candidate commit** (`out/candidate.bundle`),
never a file from a workspace, and only after these checks; any failed check is `REFUSED` and
nothing is pushed:

* `out/report.json` reads back strictly (`ota-prepare-report` v1: no unknown field, no coerced
  type, `publishable: false`), says `PREPARED`, has no `BLOCKER` or `UNKNOWN` finding and every
  step `PASSED`; `out/detection.json` says `PREPARE` for the same game, build, base, `main`
  commit and preparation key; the artifact name is the one the workflow selected;
* the bundle verifies and holds exactly the report's candidate commit, made by the
  preparation's author, with one parent: the preparation's `main` commit, an ancestor of the
  current `main`;
* every changed file passes the preparation's own scope guard (`candidate_path_allowed`:
  `data/{normalized,visuals,tooltips,localization}/<game>/<build>/`, its diff and coverage
  files), is added or modified (never deleted), a regular file (mode `100644`: no symlink, no
  executable, no submodule), and their number equals the report's `changedPaths`.

Then:

| case | outcome | job |
| --- | --- | --- |
| `main` already carries every file of the candidate (merged by another PR, as `1.60.1.70532` through #80), or the candidate changes nothing | `ALREADY_ON_MAIN`: no branch, no PR, no API call | green |
| no branch yet | the candidate commit is pushed to `ota-candidate/<game>-<build>`; a PR to `main` is opened | `OPENED`, green |
| the branch already holds this commit, or an earlier automation commit with byte-identical files (a later preparation from a newer `main`) | nothing pushed; the open PR is reused | `EXISTS`, green |
| the branch is an automation commit with other files (the data changed) | moved to the new candidate with `--force-with-lease`; the open PR is reused and its description refreshed | `EXISTS`, green |
| the branch holds any commit the automation did not make (e.g. a reviewer's fix) | never overwritten | `CONFLICT` (exit 3), red |
| no open PR, but a closed (or merged) PR had exactly the branch's commit | not reopened, no new PR; to propose again delete the branch and run with `force` | `DECLINED`, green |
| the report is not `PREPARED`, or any check above fails | nothing pushed | `REFUSED` (exit 3), red |
| git or the GitHub API fails (network, 403, 5xx) | the branch may already be pushed; no PR yet | `FAILED` (exit 1), red; retried |

**Retry after a failure.** The artifact deduplication stays as it was: a kept `PREPARED`
artifact makes the next detection `NO_ACTION`. The detection then names that artifact
(`prepared_artifact`), the `prepare` job looks up its run in the artifact listing and the
`propose` job runs again on it: the branch is found up to date (no new commit) and only the
PR is opened. **Re-run failed jobs** on the run page retries the same way. At most one PR is
open per branch: an HTTP 422 on creation (another run was first) is resolved by listing the
PRs again. Runs are queued by the concurrency group; pushes use `--force-with-lease`.

**The PR description** shows the candidate build and release revision, the base (kind and
release id), `PRODUCT_BUNDLE`, the source `main` SHA, the candidate commit, a link to the
preparation run and the artifact name, the production catalog state, the release id and
manifest SHA-256 built from the candidate commit (not the one to approve), the talent changes
(aggregate diff totals and every changed class), translation coverage per locale (names,
descriptions, ranks) and tooltip coverage, every step and gate of the preparation, and the
remaining conditions before any publication (every `REQUIRED` finding: G1 merge, G7 / G5
approval record, staging smoke, the owner's production promotion) as unchecked boxes. Its
title: `data: OTA candidate <game> <build> (diff from <base>)`.

**Diagnostics.** Every proposal uploads `ota-propose-<run id>-<attempt>` (30 days):
`proposal.json` (`ota-prepare-proposal` v1: outcome, reason, branch, commits, pushed, PR number
and URL, the error and its HTTP status), `pr-body.md`, `summary.md`, `git.log` (every git
command with its exit code and stderr; the token never appears) and `crash.txt` after a crash.
The job summary shows the outcome, the PR link, the branch and any error; a 403 adds the
setting to check. A failed download of the artifact or a step that died is red with its own
summary line.

### Token, CI and the owner's settings

The `propose` job runs in the main-only `ota-preparation` environment. Its GITHUB_TOKEN
has contents/actions read only; a repository-scoped GitHub App installation token supplies
contents and pull requests write for the candidate branch/PR. No production signing/S3
credentials are present. App-created PR events start CI normally, without close/reopen.

One-time owner setup, Client ID/private key environment entries, minimum permissions and
known verification limits are in [release-preparation.md](release-preparation.md#разовая-настройка-github-app-владельцем).
Without `OTA_APP_CLIENT_ID` and `OTA_APP_PRIVATE_KEY`, the proposal explicitly reports
`BLOCKED: APP_CONFIG_MISSING`; the autonomous chain is not ready. The private key is never
requested in chat or created during development. No App token reaches read-only producer
jobs or artifacts. Publication remains manual and Tasks 4–6 are not part of this change.

The scope guard still permits only `ota-candidate/<game>-<build>` and candidate data paths;
it never authorizes a write to main or workflow files. Real App installation/dispatch/PR CI
smoke is a post-merge owner setup check; development tests use fakes and TEST keys.

## From PREPARED to a release (manual)

1. Review the candidate PR (Actions → the run → `propose`, or the `ota-candidate/*` PR):
   `summary.md`, `report.json`, the diff and `review-report.txt` are in the preparation
   artifact (`gh run download <run-id> -n <artifact name>`).
2. Review it like any data PR (diff, warnings, allocation compatibility); start CI (above);
   merge manually. Without the PR (e.g. a `FAILED` proposal), the candidate can still be
   turned into a branch by hand (the bundle needs `main` locally):

   ```bash
   git fetch origin main
   git fetch <dir>/prep/out/candidate.bundle ota-prepare/<build>:data/forever-<build>
   ```

   Bundling the build into the app (`BUNDLED_BUILDS`, `PRODUCT_BUNDLE`) is a separate decision
   and not needed for an OTA-only release.
3. Rebuild the release from the merge commit and follow
   [publisher-runbook.md](publisher-runbook.md) §1-2: the approval record
   (`docs/ota/releases/<releaseId>.md` with live-build evidence for G7, G5 acceptance if
   flagged, the pinned manifest SHA-256 of the **rebuilt** manifest) merged by PR, the staging
   smoke, then the owner's `promote` → offline signing → `attach-signature` → `verify-live`.
   Before that, **Actions → OTA staging verification** (`candidate` = the build, `base` = the
   active production build) builds the candidate from `main`, stages it on an isolated local
   stand and runs the real client as the app before the update
   ([staging-verification.md](staging-verification.md)). Its PASS approves nothing for production.

## Run it manually

* GitHub: **Actions → OTA data preparation → Run workflow** (`build`, `base`, `force`). Runs
  on `main`; the workflow must exist on `main` to be listed.
* Locally (read-only for the checkout; network for detection and fetching):

  ```bash
  python scripts/ota_prepare.py detect --output prep/detection.json      # [--build B] [--base B] [--force]
  python scripts/ota_prepare.py prepare --detection prep/detection.json --out prep/out
  GH_TOKEN=… python scripts/ota_prepare.py propose --artifact prep --out propose \
      --repository AlexeyKravtsov95/TalentForge                         # pushes and opens a PR
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
* `actionlint` 1.7.12 with `shellcheck` 0.11.0: no finding for `ota-prepare.yml`; the
  `Final status` script is also executed by the tests for every combination of outcomes.
* First run of the workflow on GitHub (run 39776364185, `build=1.60.1.70450`,
  `base=1.60.1.70374`, `force=true`, `main` `8f18066`): `BLOCKED`. Every pipeline step and
  G2-G6, G8 passed, but `sync-visuals-check` failed and `pytest` had 10 failures: the runner
  re-encoded 353 of the 359 committed 70450 icons into other PNG bytes (the manylinux Pillow
  wheel bundles zlib 1.3.1, the macOS wheel that made the committed icons zlib-ng) - the same
  `sourceSha256`, the same pixels. The preparation now compares a committed build's visuals by
  meaning and keeps main's bytes ([talent-visuals.md](../talent-visuals.md) §3); the runner
  image is pinned to `ubuntu-24.04` and its icon environment is printed. The run reaches
  `PREPARED` only from a `main` that carries this change (the workflow checks out `main`).
* First automatic run for a build `main` does not carry (run 39942655153, `1.60.1.70532`,
  issue #78): `BLOCKED` at step 2 - `visuals fetch` ran before `normalize` and found no
  dataset; every later step was skipped. Fixed by the step order above.
* Local preparation of `1.60.1.70532` against production `1.60.1.70450-r1` (2026-10-24,
  developer machine, real wago.tools and the verified production catalog). With the fixed
  order every pipeline step passed, but the clone's offline suite failed once:
  `test_content_hash_vectors` listed every overlay under `data/localization`, the
  candidate's included, while the committed vectors - outside the preparation's scope - know
  only the bundled builds. The vectors now follow `BUNDLED_BUILDS` (the fixture is
  byte-identical). Re-run with that code as `origin/main`: `PREPARED` - 18/18 steps (the
  suite in the clone: 1757 passed), G2-G6 and G8 `PASS`, G1 and G7 the expected required
  steps, release `1.60.1.70532-r1` (397 files, 5,077,082 bytes). The diff to `1.60.1.70450`
  is empty, and so is every other comparison: the 30 enUS and 4 ruRU DB2 tables of both
  builds are byte-identical, and the prepared data equals 70450's apart from the build
  identity fields (`clientBuild`, `generatedAt`, `provenance`, the content hashes that
  cover them); the 359 icons are byte-identical. Two runs gave the same data apart from
  `generatedAt`.

## Live-build evidence: 1.60.1.70532

* **Source:** the owner's confirmation (2026-10-28) that client build `1.60.1.70532` ran on
  the game servers. **Period:** the beta. No screenshot or patch note is recorded for it.
* The data of `1.60.1.70532` is already on `main` (PR #80); it is not created again.
* This confirmation does **not** establish which build the servers run after the official
  launch. It is not an approval record: a release of `1.60.1.70532` still needs
  `docs/ota/releases/<releaseId>.md` (G7, publisher-runbook), whose acceptance - and whether
  beta-period evidence is enough for it - stays the owner's decision.

## Testing

`tests/unit/test_ota_prepare.py` (offline): every detection outcome over real freshness
evaluations, the production reader against an in-memory origin (signed with TEST keys under
`tf-prod-*` ids; absent, unreachable, wrongly signed, expired) and the verified mirror, the
step list, the scope guard, and `prepare` end to end over a temporary git repository with a
fake runner - `PREPARED` / `BLOCKED` / `UNKNOWN`, skipped steps, kept files, an untouched source
checkout, forced re-runs of a committed build (main's dataset and visuals kept; the real
70450 visuals with the live run's Linux-encoded icon kept as main's bytes; a changed client
file or pixel blocked), repeated runs, a `BLOCKED` run prepared again
once the source recovers (then `PREPARED`, then skipped) - plus the CLI's exit code 5, the
workflow's `Final status` script run in `bash` for every outcome (a step that died, a missing
decision, a contradictory status are red), and the workflow's no-secret / no-publish
properties.

`tests/unit/test_ota_propose.py` (offline, M5.OTA.AUTO.3): a real preparation in a temporary
checkout whose `origin` is a local bare repository, proposed with real git and an in-memory
GitHub API - the PR opened with the expected description; repeated runs (no second PR, no new
commit, also from a later preparation with identical files); an automation branch moved to
changed data; a failed creation (403, 500, unreachable) and a failed listing retried by the
next run without a second push; a creation race (422); data already on `main` and an empty
candidate (`ALREADY_ON_MAIN`, no API call); a closed PR not reopened; `BLOCKED`, `UNKNOWN` and
crashed preparations, an inconsistent report and tampered bundles (a stray file, an
executable, a symlink, a deletion, a foreign author) refused; a reviewer's commit never
overwritten (`CONFLICT`); the CLI's outputs and diagnostics without the token; the workflow's
candidate selection and final proposal status for every outcome, and the job permissions.
