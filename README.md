# TalentForge

Talent / build planner for World of Warcraft (future Kotlin Multiplatform mobile app).

This repository currently contains **M0 — the talent data pipeline proof of concept**:
discover the latest *WoW: Forever* beta build, download its client DB2 tables, normalize a
class talent tree into our own versioned JSON contract, and produce a semantic diff between
two builds — with no hand-maintained talent data.

## Quick start

Requires Python 3.12+.

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"

python -m talent_data builds latest --game forever
# Forever latest build: 1.60.1.70009

python -m talent_data fetch --game forever --build 1.60.1.70009
python -m talent_data normalize --game forever --build 1.60.1.70009 --class warrior
# -> data/normalized/forever/1.60.1.70009/warrior.json

python -m talent_data diff --game forever --class warrior \
  --from 1.60.1.69977 --to 1.60.1.70009
# -> human-readable report + artifacts/diff/forever-warrior-1.60.1.69977-1.60.1.70009.json
```

`--build latest` works for `fetch` / `normalize` / `diff`; `--offline` uses only the local
cache. `python -m talent_data --help` lists all commands.

## Checks

```bash
ruff check . && ruff format --check .
mypy
pytest                  # offline unit tests (real DB2 fixture rows)
pytest -m integration   # live wago.tools
```

## Layout

```
src/talent_data/
  sources/        BuildSource / Db2Source protocols, HTTP downloader, wago.tools + local CSV adapters
  db2/            typed DB2 records + indexed per-build dataset (the only place with DB2 column names)
  normalization/  class -> tree resolution, normalizer, layout grid
  models/         normalized schema v1 (public contract)
  validation/     structural validators
  diff/           semantic diff engine, diff schema v1, text renderer
  cli/            argparse CLI (python -m talent_data)
data/normalized/  committed normalized documents (proof artifacts)
artifacts/diff/   committed diff output (proof artifact)
docs/             data-pipeline.md, JSON Schemas of the contracts
tests/            unit (offline) + integration (network), fixtures of real DB2 rows
```

See [docs/data-pipeline.md](docs/data-pipeline.md) for data sources, DB2 relationships, the
schema, diff rules and known limitations.
