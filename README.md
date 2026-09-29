# TalentForge

Talent / build planner for World of Warcraft (future Kotlin Multiplatform mobile app).

This repository contains the **data platform** — M0 proved the pipeline on Warrior, M1
makes it production-ready for *all nine* Forever classes: discover the latest
*WoW: Forever* beta build, download its client DB2 tables, normalize every class talent
tree into our own versioned JSON contract (plus a dataset manifest), and produce semantic
diffs between builds — with no hand-maintained talent data. **M2** adds the reference
talent allocation engine on top of that dataset, and **M3A** starts the Kotlin
Multiplatform mobile client (see [Mobile](#mobile) below).

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

## Allocation engine (M2)

A headless reference engine (`talent_data.allocation`) answers whether a rank can be
purchased or refunded, why not (stable reason codes), and whether any build is valid —
on the normalized dataset, with every rule's evidence in
[docs/talent-allocation-semantics.md](docs/talent-allocation-semantics.md).

```python
from pathlib import Path

from talent_data.allocation import TalentAllocationEngine
from talent_data.pipeline import read_document

doc = read_document(Path("data/normalized/forever/1.60.1.70009/warrior.json"))
engine = TalentAllocationEngine(doc)
state = engine.empty_state()
decision = engine.can_spend(state, 105950)  # Deep Wounds
print(decision.allowed, decision.reason_codes)
# False ('SUFFICIENT_PREREQUISITE_NOT_MET', 'TIER_GATE_NOT_MET')
state = engine.spend(state, 105958).state  # Improved Heroic Strike 1/3
print(engine.validate(state).valid, engine.remaining_points(state))  # True 50
```

```bash
python -m talent_data allocation validate --game forever --build 1.60.1.70009 \
  --class warrior --build-file my-build.json
python -m talent_data allocation replay --all   # data/conformance/forever/1.60.1.70009/allocation.v1.json
```

## Checks

```bash
ruff check . && ruff format --check .
mypy
pytest                  # offline unit tests (real DB2 fixture rows, all 9 classes)
pytest -m integration   # live wago.tools (also a manual GitHub Actions workflow)
```

CI (`.github/workflows/ci.yml`) runs exactly these four steps on every push/PR; a second
manual workflow runs the live integration tests.

## Mobile

**M3A** adds the Kotlin Multiplatform foundation under `mobile/` (shared contract
models + dataset repository, Compose Multiplatform smoke host, thin iOS host), and
**M3B** adds the Kotlin allocation engine to `shared`: a one-to-one port of the M2
reference semantics that replays the committed conformance suite (53 scenarios /
177 steps) in Kotlin.

```bash
python3 scripts/sync_mobile_datasets.py --check   # bundled data == canonical data
cd mobile
./gradlew :shared:jvmTest :composeApp:desktopTest # contract/repository/conformance tests
./gradlew :composeApp:assembleDebug               # Android debug APK
```

iOS: `./gradlew :composeApp:linkDebugFrameworkIosArm64` and the `iosApp/` Xcode project.
Details, version stack and design-source precedence: [docs/mobile-architecture.md](docs/mobile-architecture.md).
Mobile CI lives in `.github/workflows/mobile-ci.yml` (Linux + macOS jobs).

## Layout

```
src/talent_data/
  sources/        BuildSource / Db2Source protocols, build candidates, HTTP, wago.tools + local CSV adapters
  db2/            typed DB2 records + indexed per-build dataset (the only place with DB2 column names)
  normalization/  class -> tree resolution, normalizer, layout grid
  models/         normalized schema v1 + dataset manifest v1 (public contracts)
  validation/     structural validators + typed ValidationIssue model (severity, stable codes)
  diff/           semantic diff engine, diff schema v1 + dataset aggregate, noise classification, renderer
  allocation/     M2 allocation engine (models, rules, engine) + conformance vector runner
  cli/            argparse CLI (python -m talent_data)
data/normalized/  committed normalized documents + manifest per build (proof artifacts)
data/conformance/ allocation conformance vectors (for the future Kotlin engine)
artifacts/diff/   committed diff output (per class + aggregate)
docs/             data-pipeline.md (how it works), forever-dataset.md (the dataset itself),
                  talent-allocation-semantics.md (allocation rules + evidence)
tests/            unit (offline) + integration (network), real per-class DB2 fixtures,
                  conformance/ (authored scenarios + generator)
```

See [docs/data-pipeline.md](docs/data-pipeline.md) for data sources, DB2 relationships,
the schemas, validation codes, diff rules and known limitations, and
[docs/forever-dataset.md](docs/forever-dataset.md) for the per-class dataset status and
every investigated warning.
