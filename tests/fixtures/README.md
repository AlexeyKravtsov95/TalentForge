# Test fixtures

All fixtures are **real** client data, reduced in size — nothing here is invented.

| Path | Origin |
| --- | --- |
| `db2/forever/<build>/<class-token>/*.csv` | wago.tools DB2 CSV exports of the given Forever build, filtered by `scripts/extract_fixtures.py --classes …` to the rows reachable from that class's talent tree (plus the shared class tables). One self-contained directory per class; column names are canonical (`_Index` → `Index`). Captured for builds `1.60.1.69977`, `1.60.1.70009` and (M5.DATA, the product dataset) `1.60.1.70245` × all nine classes. |
| `db2/forever/<build>/<class-token>/SpellMisc.csv` | Same origin and filter, added in M5.V1: the `SpellMisc` presentation rows (icon FileDataIDs) of that class's talent spells. Not part of `TRAIT_TABLES`; read only by the visuals pipeline. |
| `tooltips/forever/1.60.1.70009/*.csv` | wago.tools DB2 CSV exports of 1.60.1.70009, filtered by `scripts/extract_fixtures.py --build 1.60.1.70009 --tooltips` (M5.V2) to the rows the committed dataset's description templates can reach: the talent spells and every spell they reference or nest, transitively (`Spell`, `SpellEffect`, `SpellMisc`, `SpellAuraOptions`, `SpellXDescriptionVariables`, `SpellDescriptionVariables`), the whole `SpellDuration` / `SpellRadius` tables and the four duration rows of `GlobalStrings`. Complete for the renderer: the tests regenerate the committed `data/tooltips` sidecars and coverage report from it byte for byte. |
| `tooltips/forever/1.60.1.70245/*.csv` | The same extraction for the product dataset 1.60.1.70245 (M5.DATA, `--build 1.60.1.70245 --tooltips`); `tests/unit/test_product_build.py` regenerates the committed 70245 sidecars, coverage report and Markdown from it byte for byte. |
| `casc/forever/1.60.1.70009/<FileDataID>.blp` | Two verbatim client icon files of that build, as served by `GET https://wago.tools/api/casc/<FileDataID>?version=1.60.1.70009` (2026-10-07): `132269` (BLP2, DXT with a real alpha channel) and `132090` (BLP2 without alpha). Blizzard artwork, used to test the BLP2 → PNG conversion; see `docs/talent-visuals.md`. |
| `db2/forever/<build>/<class-token>/_table_status.json` | Table-availability snapshot of the build: the tables the v1 contract requires to be absent (`TraitNodeGroupXTraitCost`, `TraitNodeXTraitCost`, `TraitNodeEntryXTraitCost`), each recorded `absent` with wago.tools' evidence (`{"errors": "Table not found."}` for that build). Copied from the cache by `extract_fixtures.py`, which refuses builds that do not prove the absence. A missing file never means "absent". |
| `wago/builds.json` | Subset of the real `GET https://wago.tools/api/builds` response (2026-09-27): 3 `5.5.0` + all five `1.60.1` builds of `wow_classic_beta` in a deliberately scrambled order, plus two `wow_classic_era` builds. |
| `wago/*.csv` | Verbatim head rows of wago.tools CSV exports (wago column naming, quoted multi-line text). |

Each class fixture deliberately contains the rows of that class's known anomalies
(Hunter mutual edge + mis-placed duplicates, Priest vacuous conds + duplicate, Rogue
incomplete curve, Paladin/Warlock nudges, Druid pre-rename names in the 69977 build) —
see `docs/forever-dataset.md` and `tests/unit/test_known_anomalies.py`.

Regenerate the DB2 subsets after `python -m talent_data fetch --build <build>`:

```bash
python scripts/extract_fixtures.py --build 1.60.1.69977 --classes all
python scripts/extract_fixtures.py --build 1.60.1.70009 --classes all
python scripts/extract_fixtures.py --build 1.60.1.70245 --classes all
python scripts/extract_fixtures.py --build 1.60.1.70245 --tooltips
# or a subset: --classes warrior,druid
```
