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

# Discover the newest Forever build wago.tools knows right now (online; the answer
# changes over time, so it is not recorded here)
python -m talent_data builds latest --game forever

# Is the app's product dataset still the latest publishable build? (online, read-only)
python scripts/check_product_freshness.py     # JSON on stdout; exit 0 CURRENT, 3 OUTDATED, 4 UNKNOWN

# Reproduce the product dataset (M5.DATA.3: what the app creates new builds on)
python -m talent_data fetch --game forever --build 1.60.1.70374
python -m talent_data normalize --game forever --build 1.60.1.70374 --class all
python -m talent_data diff --game forever --class all --from 1.60.1.70338 --to 1.60.1.70374
# -> 9 classes, 0 errors, the same 12 investigated warnings; 0 added / 0 removed / 0 changed
#    (70374 has 70338's talent rows byte for byte, yet is its own dataset)

# Reproduce the verified reference baseline
python -m talent_data fetch --game forever --build 1.60.1.70009
python -m talent_data normalize --game forever --build 1.60.1.70009 --class all
# -> data/normalized/forever/1.60.1.70009/{warrior..druid}.json + manifest.json
#    9 classes, 0 errors, 12 investigated warnings (see docs/forever-dataset.md)

# Diff between two historical builds (69977 -> reference baseline 70009)
python -m talent_data diff --game forever --class all \
  --from 1.60.1.69977 --to 1.60.1.70009
# -> per-class diffs + aggregate artifacts/diff/forever-all-<a>-<b>.json + summary
```

**Product dataset and reference baseline (M5.DATA, M5.DATA.2, M5.DATA.3).** Committed
builds have distinct roles. `1.60.1.70374` is the **product dataset**: the newest Forever
build wago.tools listed on 2026-10-14 whose `is_bgdl` is explicitly `false`; the mobile app
creates new builds on it. `1.60.1.70338` (M5.DATA.2) and `1.60.1.70245` (M5.DATA), earlier
product datasets, stay committed and bundled for the builds saved on them. `1.60.1.70009` stays the **verified reference
baseline**: the live allocation evidence and the M2 conformance vectors are pinned to it,
and builds saved on it keep opening on it. Each is committed with its own icons and
descriptions and bundled in the app. None is a claim about the build the live realms run
(wago.tools cannot say); `scripts/check_product_freshness.py` reports whether a newer
publishable build exists. Selection evidence, the empty 70338 → 70374 diff and how it was
verified, and the validity limits:
[docs/forever-dataset.md](docs/forever-dataset.md#product-dataset-160170374-m5data3).
Commands below that name `1.60.1.70009` reproduce or check the reference baseline; the
same commands with `1.60.1.70374` do it for the product dataset.

Single classes keep working exactly as in M0 (reference baseline):

```bash
python -m talent_data normalize --game forever --build 1.60.1.70009 --class warrior
python -m talent_data diff --game forever --class warrior \
  --from 1.60.1.69977 --to 1.60.1.70009
python -m talent_data validate data/normalized/forever/1.60.1.70009/hunter.json
python -m talent_data manifest data/normalized/forever/1.60.1.70009/manifest.json
```

`--build latest` works for `fetch` / `normalize` / `diff` (it resolves to whatever build
`builds latest` reports at that moment, not to the reference baseline); `--offline` uses
only the local cache. `python -m talent_data --help` lists all commands.

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
# conformance is pinned to the verified reference baseline
python -m talent_data allocation validate --game forever --build 1.60.1.70009 \
  --class warrior --build-file my-build.json
python -m talent_data allocation replay --all   # data/conformance/forever/1.60.1.70009/allocation.v1.json
```

## Talent visuals (M5.V1)

Real WoW talent icons, pinned to the client build and bundled for offline use. Icons are
resolved through `TraitDefinition` → display spell → `SpellMisc.SpellIconFileDataID`
(469/469 entries of `1.60.1.70009`, 358 distinct files) and published as a separate
sidecar contract — `talent-data.v1` is unchanged. Details, source evidence and the
ownership note for the artwork: [docs/talent-visuals.md](docs/talent-visuals.md).

```bash
# verified reference baseline
python -m talent_data visuals fetch --build 1.60.1.70009                 # online, on demand
python -m talent_data visuals normalize --build 1.60.1.70009 --offline   # data/visuals/…
python -m talent_data visuals check --build 1.60.1.70009                 # offline, CI
python -m talent_data visuals check --build 1.60.1.70374                 # product dataset: 469/469, 358 files
python scripts/sync_mobile_visuals.py --check                            # bundle == canonical
```

## Talent descriptions (M5.V2)

Rank-specific talent descriptions rendered automatically from the client tooltip templates
and the DB2 tables of the same pinned build - no client capture, no runtime network.
Published as the `talent-tooltips.v1` sidecar (`talent-data.v1` is unchanged); every
rank is `resolved`, `partial` (values that depend on the character or are not proven are
explicit gaps) or `unresolved`. The renderer is build-generic; its first canonical output
is for the reference baseline 1.60.1.70009: 1290 of 1333 ranks resolved, 43 partial,
0 unresolved. Rules and evidence: [docs/talent-tooltips.md](docs/talent-tooltips.md).

```bash
# verified reference baseline
python -m talent_data tooltips fetch --build 1.60.1.70009                # online, on demand
python -m talent_data tooltips normalize --build 1.60.1.70009 --offline  # data/tooltips/… + report
python -m talent_data tooltips check --build 1.60.1.70009                # offline, CI
python -m talent_data tooltips check --build 1.60.1.70374                # product: 1281 resolved, 47 partial, 1 unresolved
python scripts/sync_mobile_tooltips.py --check                           # bundle == canonical
```

## Russian talent text (M5.REL.3a)

The client's own `ruRU` names and description templates of the same pinned build, from the
same wago.tools DB2 exports (`&locale=ruRU`), verified against the enUS export and published
as a separate `talent-localization.v1` overlay linked to the exact dataset and tooltip
revisions - nothing is translated by hand or by machine, and the English dataset is
unchanged. Builds 1.60.1.70338 and 1.60.1.70374 (the product build since M5.DATA.3) alike:
459 / 469 names translated, 355 / 469 descriptions rendered; the rest falls back to English
with a reason. Only the Warrior pilots of both builds are committed and the app does not use
them yet (M5.REL.3b). Source evidence, contract, gates and plan:
[docs/talent-localization.md](docs/talent-localization.md).

```bash
python -m talent_data localization fetch --build 1.60.1.70338 --locale ruRU                 # online
python -m talent_data localization normalize --build 1.60.1.70338 --locale ruRU --classes warrior --offline
python -m talent_data localization check --build 1.60.1.70338 --locale ruRU                 # offline, CI
```

## Checks

```bash
ruff check . && ruff format --check .
mypy
pytest                  # offline unit tests (real DB2 fixture rows, all 9 classes)
pytest -m integration   # live wago.tools (also a manual GitHub Actions workflow)
```

CI (`.github/workflows/ci.yml`) runs exactly these four steps on every push/PR; a second
manual workflow runs the live integration tests. A third, daily workflow
(`dataset-freshness.yml`, M5.DATA.WATCH) checks whether a newer publishable Forever build
exists and reports it in one tracked GitHub Issue; before a release run
`python scripts/check_product_freshness.py` — see
[docs/dataset-freshness-watch.md](docs/dataset-freshness-watch.md).

## Mobile

**M3A** added the Kotlin Multiplatform foundation under `mobile/` (shared contract
models + dataset repository, Compose Multiplatform app host, thin iOS host),
**M3B** added the Kotlin allocation engine to `shared` (a one-to-one port of the
M2 reference semantics that replays the committed conformance suite — 53
scenarios / 177 steps — in Kotlin), **M4.1** replaces the M3A smoke host
with the first production screen: the read-only Warrior talent tree rendered
from the bundled dataset (real node geometry, connectors, pan/pinch/tap-select;
allocation UI deliberately deferred). **M4.2** adds the adaptive Talent
Inspector and the engine-backed read-only node states (portrait compact bar +
expanded sheet, landscape side panel, tablet persistent panel, prerequisite
`Show` navigation; still no spending — the build stays `engine.emptyState()`).
**M4.3** makes the tree an interactive local build editor: explicit `−`/`+`
rank controls in every inspector variant, real `TalentAllocationEngine`
spend/refund transitions with live tier/prerequisite/counter updates, undo /
redo, and Reset Talents with an undoable one-step clear (node tap still only
selects; every mutation routes through the engine). **M5.1** turns the app
into a local multi-build application: a versioned app-private build library
(platform storage injected per host — SharedPreferences / NSUserDefaults /
java.util.prefs; the public `allocation-build.v1` contract unchanged), Home as
the root with Continue / recents / All Builds / New Build, all nine bundled
Forever classes, drafts created and persisted the moment a class is picked,
autosave of every successful mutation through one observer path, fail-closed
build reopening (no silent migration), and the generic dataset-bound editor
with the class accent as an explicit input (Warrior keeps its frozen accent;
other classes use a documented neutral fallback). **M5.V1** replaces the
monogram tiles of the Talent Tree with real, build-pinned WoW talent icons
bundled as local resources (a strict visuals sidecar next to the unchanged
`talent-data.v1`; the monogram stays as the fallback for an icon that cannot be
loaded) — see
[docs/mobile-architecture.md](docs/mobile-architecture.md#talent-visuals-m5v1).
**M5.V2** adds real, build-pinned talent descriptions to the inspector (current
rank, CURRENT / NEXT RANK, rank 1 as the prospective effect) from a strict
`talent-tooltips.v1` sidecar - see
[docs/mobile-architecture.md](docs/mobile-architecture.md#talent-descriptions-m5v2).
**M5.DATA / M5.DATA.2 / M5.DATA.3** bundle the product dataset 1.60.1.70374 next to the
unchanged 1.60.1.70338 and 1.60.1.70245 (earlier product datasets) and 1.60.1.70009
(reference baseline): New Build creates drafts on 70374, every saved build opens on its own
recorded build (no migration, even where two builds carry identical talent rows),
an unbundled build fails with a typed error — see
[docs/mobile-architecture.md](docs/mobile-architecture.md#bundled-datasets-product-and-reference-m5data).

```bash
python3 scripts/sync_mobile_datasets.py --check   # bundled data == canonical data
python3 scripts/sync_mobile_visuals.py --check    # bundled icons == canonical icons
python3 scripts/sync_mobile_tooltips.py --check   # bundled descriptions == canonical descriptions
# (all three cover every build in BUNDLED_BUILDS of scripts/mobile_bundles.py)
cd mobile
./gradlew :shared:jvmTest :composeApp:desktopTest # contract/repository/conformance + tree UI + build library tests
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
  visuals/        M5.V1 talent icons: resolution, BLP2 -> PNG conversion, sidecar publishing
  tooltips/       M5.V2 talent descriptions: template grammar, build-pinned renderer, coverage
  watch/          M5.DATA.WATCH freshness watcher: report reader, tracked-issue decision, GitHub Issues client
data/normalized/  committed normalized documents + manifest per build (proof artifacts)
data/visuals/     committed talent-visuals.v1 sidecars, talent-icons.v1 registry and PNG icons
data/tooltips/    committed talent-tooltips.v1 sidecars (rendered descriptions per rank)
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
