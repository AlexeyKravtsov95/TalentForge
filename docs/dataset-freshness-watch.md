# Dataset freshness watcher (M5.DATA.WATCH)

The watcher answers one question every day: is the app's product dataset
(`PRODUCT_BUNDLE`, currently `1.60.1.70338`) still the latest **publishable** Forever
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

Before releasing a TalentForge version, run:

```bash
python scripts/check_product_freshness.py
```

* `CURRENT` (exit 0) - the freshness check passed.
* `OUTDATED` (exit 3) - the release needs a dataset refresh first, or an explicit,
  documented decision to ship the older dataset.
* `UNKNOWN` (exit 4) - freshness is **not** confirmed; this is not a passed check. Resolve
  the cause or document the decision.
* any other exit - the check failed; fix it before relying on it.

The network check is deliberately **not** part of the per-PR CI (`ci.yml`): a new Forever
build appearing upstream must not block unrelated UI work. A dedicated release gate may
be added later.

## Testing

`tests/unit/test_freshness_watch.py` (offline): the strict report reader (wrong schema
version, unknown fields, coerced wire types, status/exit-code mismatches, unexpected exit
codes), every decision above on `tracking.decide` alone, and delivery through the real
client against an in-memory GitHub Issues API (`httpx.MockTransport`) - including repeated
runs, an even newer build, `UNKNOWN` over an open `OUTDATED` issue, renamed issues,
several marker issues, 401/403/404/410/5xx and network errors, and a missing token.
