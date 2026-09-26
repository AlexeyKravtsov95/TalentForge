# TalentForge

Talent / build planner for World of Warcraft (future Kotlin Multiplatform mobile app).

This repository contains the **data platform** — M0 proved the pipeline on Warrior, **M1
(current)** makes it production-ready for *all nine* Forever classes: discover the latest
*WoW: Forever* beta build, download its client DB2 tables, normalize every class talent
tree into our own versioned JSON contract (plus a dataset manifest), and produce semantic
diffs between builds — with no hand-maintained talent data.

## Quick start

Requires Python 3.12+.

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"

python -m talent_data builds latest --game forever
# Forever latest build: 1.60.1.70009

python -m talent_data fetch --game forever --build 1.60.1.70009
python -m talent_data normalize --game forever --build latest --class all
# -> data/normalized/forever/1.60.1.70009/{warrior..druid}.json + manifest.json
#    9 classes, 0 errors, 12 investigated warnings (see docs/forever-dataset.md)

python -m talent_data diff --game forever --class all \
  --from 1.60.1.69977 --to 1.60.1.70009
# -> per-class diffs + aggregate artifacts/diff/forever-all-<a>-<b>.json + summary
```

Single classes keep working exactly as in M0:

```bash
python -m talent_data normalize --game forever --build 1.60.1.70009 --class warrior
python -m talent_data diff --game forever --class warrior \
  --from 1.60.1.69977 --to 1.60.1.70009
python -m talent_data validate data/normalized/forever/1.60.1.70009/hunter.json
python -m talent_data manifest data/normalized/forever/1.60.1.70009/manifest.json
```

`--build latest` works for `fetch` / `normalize` / `diff`; `--offline` uses only the local
cache. `python -m talent_data --help` lists all commands.

## Checks

```bash
ruff check . && ruff format --check .
mypy
pytest                  # offline unit tests (real DB2 fixture rows, all 9 classes)
pytest -m integration   # live wago.tools (also a manual GitHub Actions workflow)
```

CI (`.github/workflows/ci.yml`) runs exactly these four steps on every push/PR; a second
manual workflow runs the live integration tests.

## Layout

```
src/talent_data/
  sources/        BuildSource / Db2Source protocols, build candidates, HTTP, wago.tools + local CSV adapters
  db2/            typed DB2 records + indexed per-build dataset (the only place with DB2 column names)
  normalization/  class -> tree resolution, normalizer, layout grid
  models/         normalized schema v1 + dataset manifest v1 (public contracts)
  validation/     structural validators + typed ValidationIssue model (severity, stable codes)
  diff/           semantic diff engine, diff schema v1 + dataset aggregate, noise classification, renderer
  cli/            argparse CLI (python -m talent_data)
data/normalized/  committed normalized documents + manifest per build (proof artifacts)
artifacts/diff/   committed diff output (per class + aggregate)
docs/             data-pipeline.md (how it works), forever-dataset.md (the dataset itself)
tests/            unit (offline) + integration (network), real per-class DB2 fixtures
```

See [docs/data-pipeline.md](docs/data-pipeline.md) for data sources, DB2 relationships,
the schemas, validation codes, diff rules and known limitations, and
[docs/forever-dataset.md](docs/forever-dataset.md) for the per-class dataset status and
every investigated warning.
