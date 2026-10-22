# Dataset freshness watcher (M5.DATA.WATCH)

The watcher answers one question every day: is the app's product dataset
(`PRODUCT_BUNDLE`, currently `1.60.1.70450`) still the latest **publishable** Forever
build? It reports through **one** tracked GitHub Issue and changes nothing else.

It never switches `PRODUCT_BUNDLE`, never edits `BUNDLED_BUILDS`, normalized data,
icons, descriptions or saved builds, and never commits, opens a pull request or merges.
A new build is a signal for a developer to run an M5.DATA refresh, not an automatic
update.

## How it works

```
.github/workflows/dataset-freshness.yml   (daily ~06:00 UTC + manual "Run workflow")
  1. checkout main, Python 3.12, pip install -e .
  2. scripts/check_product_freshness.py --output freshness/freshness.json
       = python -m talent_data builds freshness (M5.DATA.2), wago.tools /api/builds only
       exit 0 CURRENT / 3 OUTDATED / 4 UNKNOWN (anything else: the check itself failed);
       the code is recorded, the step does not fail
  3. upload freshness/ as the `freshness-report` artifact (always)
  4. scripts/dataset_freshness_watch.py --report ... --check-exit-code <code>
       talent_data/watch: report.py (strict reader) -> tracking.py (pure decision)
       -> github.py (Issues REST, GITHUB_TOKEN); its exit code is the job's final status
```

* Only the build index (`GET https://wago.tools/api/builds`) is read; no DB2 table is
  downloaded.
* Permissions: `contents: read`, `issues: write`; the built-in `GITHUB_TOKEN`, no PAT.
* `concurrency: dataset-freshness` (queued, not cancelled): two runs never race to open
  an issue.
* The check's non-zero codes do not stop the job early: the report is uploaded and the
  issue updated first, then the last step exits with the final status.
* **Schedules only run while GitHub Actions is available for the repository** (enabled,
  and the account's Actions billing in good standing). A blocked account runs nothing:
  no issue is opened, closed or updated, and no scheduled check happens.

## Statuses and the job result

| check | job exit | tracking issue | meaning |
| --- | --- | --- | --- |
| `CURRENT` | 0 (green) | closed with a comment, if one was open | the product build is the latest publishable build; publishable is not "live" |
| `OUTDATED` | 3 (red) | opened, or updated when the tracked facts change | a newer build with `is_bgdl=false` exists: refresh the product dataset |
| `UNKNOWN` | 4 (red) | opened, or the failure recorded on the open one; **never closed** | freshness not established (network/HTTP/schema failure, newer build without `is_bgdl`, no publishable build, product ahead of the source) |
| other exit / bad JSON | 1 (red) | untouched | the watcher failed: diagnostics in the log and the artifact |
| GitHub API error | 5 (red) | **not delivered** | Issues disabled, missing permission/token, API outage |

`OUTDATED` and `UNKNOWN` are red on purpose: neither is a passed check. The red run says
"look"; the issue says what and why.

`is_bgdl=false` only means "not a background download". It is the project's
publishability condition, never proof that the realms run the build - `CURRENT` does not
mean "live", and the watcher never claims it does.

## The tracked issue

* Found by the hidden marker `<!-- talentforge:forever-dataset-freshness -->` in its
  description, never by its title (renaming it is safe). At most one is open; if several
  open issues carry the marker, the oldest is used and the run warns.
* The description carries the current `PRODUCT_BUNDLE`, the latest confirmed publishable
  build, the latest detected build, newer non-publishable builds, the reason, the check
  time (UTC), the data source, the run link, the refresh procedure and a history table
  (the last 50 significant changes; comments keep the rest).
  Its machine state is a hidden `<!-- talentforge:freshness-state {...} -->` comment;
  do not edit it (an unreadable state is rebuilt from the next check, with a comment).
* Title: `Forever dataset outdated: <product build> → <new build>` (numbers from the
  report), or `Forever dataset freshness unknown: <product build> could not be verified`.

Deduplication - a run writes to GitHub only when something significant changed:

| open issue | result | action |
| --- | --- | --- |
| none | `CURRENT` | nothing |
| none | `OUTDATED` / `UNKNOWN` | open one |
| any | `CURRENT` | comment, close (`completed`) |
| `OUTDATED` | `OUTDATED`, same product + same latest publishable | nothing |
| `OUTDATED` | `OUTDATED`, other product or latest publishable (e.g. an even newer build) | edit title/description, append history, one comment |
| `OUTDATED` | `UNKNOWN`, reason not yet recorded | add an UNKNOWN section, keep the OUTDATED title and findings, one comment |
| `OUTDATED` + recorded `UNKNOWN` | `OUTDATED`, same facts | drop the UNKNOWN section quietly (no comment) |
| `UNKNOWN` | `UNKNOWN`, same reason and product | nothing |
| `UNKNOWN` | `UNKNOWN`, new reason | edit, append history, one comment |
| `UNKNOWN` | `OUTDATED` | turn it into the OUTDATED issue, one comment |

A closed issue is history: the next `OUTDATED` / `UNKNOWN` opens a new one.

## Run it manually

* GitHub: **Actions → Dataset freshness → Run workflow** (runs on `main`).
* Locally, the check only (read-only, needs the network):

  ```bash
  python scripts/check_product_freshness.py --output freshness.json; echo "exit $?"
  ```

* Locally, the full watcher against a repository (writes to its Issues):

  ```bash
  GITHUB_TOKEN=<token with issues:write> python scripts/dataset_freshness_watch.py \
      --report freshness.json --check-exit-code <exit of the check> \
      --repository AlexeyKravtsov95/TalentForge --run-url <link to cite in the issue>
  ```

## After a new build is detected

Every completed watcher run triggers **OTA data preparation**
(`.github/workflows/ota-prepare.yml`, M5.OTA.AUTO.1, [ota/auto-prepare.md](ota/auto-prepare.md)):
when the newest publishable build is not in the signed production catalog, it runs the steps
below up to the review diff and the publisher gates in a disposable clone and keeps everything
as an artifact - it never commits, publishes or changes `PRODUCT_BUNDLE`. Start from that
artifact when it is `PREPARED`; the manual steps are the same.

Follow the steps in the issue (they name the real builds), in short: re-check freshness;
`fetch` / `normalize --class all` / `diff --from <product> --to <new>`; review the diff and
allocation compatibility; publish icons and descriptions (`visuals`, `tooltips`); add the
build to `BUNDLED_BUILDS` (keeping every build saved builds may reference), switch
`PRODUCT_BUNDLE`, run the sync scripts and their `--check`, run the Python and mobile
baseline, open the refresh PR, merge it manually. The next watcher run is then `CURRENT`
and closes the issue. Reference: `docs/forever-dataset.md` (product dataset sections)
and the commands block of `docs/data-pipeline.md`.

## Troubleshooting GitHub Issues access (exit 5)

The run log has an `::error::` line naming the HTTP status and GitHub's message:

* **403 "Resource not accessible by integration"** - the job lacks `issues: write`
  (check the workflow's `permissions:`), or the repository/organization limits
  `GITHUB_TOKEN` (Settings → Actions → General → Workflow permissions).
* **410 / 404** - Issues are disabled (Settings → General → Features → Issues) or the
  repository is not reachable with this token.
* **401** - the token was not passed to the step.
* **5xx / network** - GitHub outage; the next run retries (there is no in-run retry).

The notification is never reported as delivered when a call failed: the job ends red with
exit 5 and the job summary says "notification NOT delivered".

## Release preflight (mandatory)

Before releasing a TalentForge version, run a **fresh** check from the release commit:

```bash
python scripts/check_product_freshness.py --output release-freshness.json; echo "exit $?"
```

* Fresh means: run at release time, by the person releasing, against the `PRODUCT_BUNDLE`
  of the commit being released. A green watcher run, an old artifact, the tracking issue
  being closed, or a check from the refresh PR is **not** a substitute - the watcher may not
  have run at all (see [When GitHub Actions cannot run](#when-github-actions-cannot-run)).
* Record the JSON (`status`, `productBuild`, `latestPublishable`, `checkedAt`) in the
  release notes or the release PR.
* `CURRENT` (exit 0) - the freshness check passed. Only this status may be described as
  "current", and only as "the latest publishable build wago.tools listed at `checkedAt`",
  never as "live".
* `OUTDATED` (exit 3) - the release needs a dataset refresh first, or an explicit,
  documented decision to ship the older dataset, worded as outdated.
* `UNKNOWN` (exit 4) - freshness is **not** confirmed; this is not a passed check and must
  never be reported as current. Resolve the cause or document the decision, worded as
  unknown.
* any other exit - the check failed; fix it before relying on it.

### Newer builds that are only background downloads

A build with `is_bgdl: true` is a pre-staged client, not a publishable one. The check stays
`CURRENT` and lists it under `newerUnpublishable`; the tracking issue is not opened for it.

* Do **not** refresh to it, and do not describe the product as outdated because of it.
* Mention it in the release notes ("newer background-download build X exists, not
  publishable yet") and re-run the check right before the release: when the same build is
  published its flag becomes `is_bgdl: false`, and the check then answers `OUTDATED`.
* A newer build **without** an `is_bgdl` flag is `UNKNOWN`, not `CURRENT` - its status
  cannot be told apart from a published build.
* A non-boolean `is_bgdl` (`"false"`, `0`) is a schema failure (`UNKNOWN`), never coerced.

The network check is deliberately **not** part of the per-PR CI (`ci.yml`): a new Forever
build appearing upstream must not block unrelated UI work. A dedicated release gate may
be added later.

## When GitHub Actions cannot run

The workflow is only as reliable as the account's Actions availability. A schedule that
never starts produces **no** issue update and **no** result of the check itself - silence
is not `CURRENT`.

### Classifying a failed run

Separate the three causes before touching code; only the first is fixed in the repository.

| Cause | Run evidence | Repository action |
| --- | --- | --- |
| **Repository code** (workflow, scripts, Python) | the job started: steps exist in the run and the log shows checkout / install / check output; or the run is a `startup_failure` that names a workflow-file error | fix in a PR; reproduce locally with the commands under [Run it manually](#run-it-manually) |
| **GitHub Actions availability** (billing, spending limit, account lock, Actions disabled, no hosted runner) | the job has **no executed steps**; its annotation names billing, a spending limit, a locked account, or that no runner picked it up; other workflows (`ci.yml`) fail the same way | none in code - restore billing / enable Actions in the account settings, then **Run workflow** once by hand |
| **Source API** (wago.tools) | the check step ran and exited 4 (`UNKNOWN`); `freshness.json` names a network / HTTP / schema failure | none in code unless the schema changed; re-run later, or check by hand (below) |

Evidence for a run id (read-only, with an authenticated `gh`):

```bash
gh run view <run-id> --repo AlexeyKravtsov95/TalentForge --json status,conclusion,event,createdAt,updatedAt,headSha,jobs
gh api repos/AlexeyKravtsov95/TalentForge/actions/runs/<run-id>/jobs --jq '.jobs[] | {name, conclusion, runner_name, steps: (.steps | length)}'
gh api repos/AlexeyKravtsov95/TalentForge/check-runs/<job-id>/annotations --jq '.[].message'
gh run list --repo AlexeyKravtsov95/TalentForge --limit 10   # do other workflows fail the same way?
```

### Scheduled run 38554070273

Reported failing before executing any step (scheduled run, 2026-10-14). What M5.DATA.3
could and could not establish:

| Question | Answer | Basis |
| --- | --- | --- |
| Repository code (workflow file) | **no defect found** | `actionlint` 1.7.12 reports nothing for all four workflows; the job uses only `checkout@v4`, `setup-python@v5`, `upload-artifact@v4` and repository scripts that pass locally |
| Repository code (scripts) | **no defect found** | the exact check step (`scripts/check_product_freshness.py`) ran locally on 2026-10-14 at 16:50 and 18:57 UTC: exit 3 for `70338` (main), exit 0 for `70374` (this change) |
| Source API (wago.tools) | **reachable, valid** | both runs above read `/api/builds` and validated its schema; a source failure would have needed the job to start and exit 4 |
| Did the job execute any step? GitHub's annotation? | **not established in the change's environment** | the repository is private and no GitHub credential was available there (run page and `git ls-remote` refused); the read-only `gh` evidence is collected by the PR's handoff run and recorded in the M5.DATA.3 PR description |

If that evidence shows a job without executed steps and an annotation about billing, a
spending limit or a locked account, the cause is **GitHub Actions account/billing**, not
repository code and not wago.tools; nothing in the repository fixes it. Until a run of
this workflow completes, the watcher has produced **no** freshness result: the absence of
an issue update is not `CURRENT`, and the watcher is not described as working.

### Manual check (no GitHub needed)

From any checkout with the package installed - the same code path as the workflow:

```bash
python scripts/check_product_freshness.py --output freshness.json; echo "exit $?"
```

Without a checkout, read the index directly and compare with `PRODUCT_BUNDLE`
(`mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/library/BundledDatasets.kt`):

```bash
curl -s https://wago.tools/api/builds | python3 -c '
import json, sys
for product, builds in json.load(sys.stdin).items():
    for b in builds:
        if b["version"].startswith("1.60."):
            print(product, b["version"], b["created_at"], "is_bgdl=%r" % b.get("is_bgdl"))' | sort -k2 -V
```

Only `wow_classic_beta` rows count for the product today (see the `wowf` gap below); an
`is_bgdl` that is not a JSON `false` does not make a build publishable.

### Backup monitoring that does not depend on GitHub Actions

Run the same read-only check on a machine you control, and make every non-`CURRENT` exit
visible. It changes nothing in the repository and needs no GitHub token. It compares
`origin/main`'s `PRODUCT_BUNDLE`, not the local branch:

```bash
#!/usr/bin/env bash
# talentforge-freshness.sh - run from a TalentForge clone with .venv installed (pip install -e .)
KT=mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/library/BundledDatasets.kt
OUT="${TMPDIR:-/tmp}/tf-freshness.json"
notify() { # every non-CURRENT outcome, including a failure of this script, is visible
  if command -v osascript >/dev/null; then
    osascript -e "display notification \"$1\" with title \"TalentForge dataset freshness\""
  elif command -v notify-send >/dev/null; then notify-send "TalentForge dataset freshness" "$1"
  else echo "TalentForge dataset freshness: $1" >&2; fi
}
cd "$(dirname "$0")" || { notify "cannot enter the clone"; exit 1; }
git fetch -q origin || { notify "git fetch failed - freshness NOT checked"; exit 1; }
# only the PRODUCT_BUNDLE block: the file also declares the 70009 reference bundle
product="$(git show "origin/main:$KT" |
  sed -n '/^val PRODUCT_BUNDLE/,/^)/s/^ *clientBuild = "\(1\.60\.[0-9.]*\)",$/\1/p')"
case "$product" in 1.60.*.*) ;; *) notify "PRODUCT_BUNDLE not readable - freshness NOT checked"; exit 1 ;; esac
.venv/bin/python -m talent_data builds freshness --game forever --product-build "$product" \
  --output "$OUT" >/dev/null
code=$?
[ "$code" = 0 ] || notify "$product: exit $code (3 OUTDATED, 4 UNKNOWN, other = check failed) - $OUT"
exit "$code"
```

Schedule it daily with `launchd` (a `LaunchAgent` with `StartCalendarInterval`) or `cron`
(`0 9 * * * /path/to/TalentForge/talentforge-freshness.sh`); `notify` falls back to
`notify-send` and stderr off macOS. It is silent only on exit 0 (`CURRENT`). A missed notification
is still not `CURRENT`: the release preflight above is the gate, the backup only shortens the
time to notice. It never fetches datasets, edits files, commits, pushes or opens PRs.

### Known gap: the `wowf` product

On 2026-10-14 wago.tools listed a new product, `wowf`, with one build: `1.60.1.70245`
(created 2026-10-13, `is_bgdl=false`) - older than the beta's 70374. Forever is mapped to
`wow_classic_beta` only (`talent_data/games.py`), so neither the check nor the watcher looks
at `wowf`. What `wowf` is (for example, the product of the launched game) is not established
in the repository. Until it is, list it by hand (the `curl` above shows it) whenever a release
is prepared, and treat a `wowf` build that differs from `PRODUCT_BUNDLE` as a question for a
product decision - never as an automatic switch.

Re-checked by M5.DATA.4 on 2026-10-17 (18:40 UTC): the index still listed only
`1.60.1.70245` under `wowf` (`build_config e8c4ef4e67bd1d94d820254984258fa8`), now older
than the beta's product build 70450. The beta's own 70245 has a different `build_config`
(`0bf141260c698dfc9cb3b5b7947b78c3`), yet wago.tools' table export is addressed by version
string alone (`?build=1.60.1.70245`), so the pipeline's source cannot tell the two 70245
builds apart. The product mapping is unchanged
([forever-dataset.md](forever-dataset.md#product-dataset-160170450-m5data4)).

## Testing

`tests/unit/test_freshness_watch.py` (offline): the strict report reader (wrong schema
version, unknown fields, coerced wire types, status/exit-code mismatches, unexpected exit
codes), every decision above on `tracking.decide` alone, and delivery through the real
client against an in-memory GitHub Issues API (`httpx.MockTransport`) - including repeated
runs, an even newer build, `UNKNOWN` over an open `OUTDATED` issue, renamed issues,
several marker issues, 401/403/404/410/5xx and network errors, and a missing token.
