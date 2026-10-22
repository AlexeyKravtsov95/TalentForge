# Test fixtures

All fixtures are **real** client data, reduced in size — nothing here is invented.

| Path | Origin |
| --- | --- |
| `db2/forever/<build>/<class-token>/*.csv` | wago.tools DB2 CSV exports of the given Forever build, filtered by `scripts/extract_fixtures.py --classes …` to the rows reachable from that class's talent tree (plus the shared class tables). One self-contained directory per class; column names are canonical (`_Index` → `Index`). Captured for builds `1.60.1.69977`, `1.60.1.70009`, `1.60.1.70245` (M5.DATA), `1.60.1.70338` (M5.DATA.2) and (M5.DATA.3, the product dataset) `1.60.1.70374` × all nine classes. |
| `db2/forever/<build>/<class-token>/SpellMisc.csv` | Same origin and filter, added in M5.V1: the `SpellMisc` presentation rows (icon FileDataIDs) of that class's talent spells. Not part of `TRAIT_TABLES`; read only by the visuals pipeline. |
| `tooltips/forever/1.60.1.70009/*.csv` | wago.tools DB2 CSV exports of 1.60.1.70009, filtered by `scripts/extract_fixtures.py --build 1.60.1.70009 --tooltips` (M5.V2) to the rows the committed dataset's description templates can reach: the talent spells and every spell they reference or nest, transitively (`Spell`, `SpellEffect`, `SpellMisc`, `SpellAuraOptions`, `SpellXDescriptionVariables`, `SpellDescriptionVariables`), the whole `SpellDuration` / `SpellRadius` tables and the four duration rows of `GlobalStrings`. Complete for the renderer: the tests regenerate the committed `data/tooltips` sidecars and coverage report from it byte for byte. |
| `tooltips/forever/1.60.1.70245/*.csv` | The same extraction for 1.60.1.70245, the product dataset of M5.DATA (`--build 1.60.1.70245 --tooltips`); `tests/unit/test_build_70245.py` regenerates the committed 70245 sidecars, coverage report and Markdown from it byte for byte. |
| `tooltips/forever/1.60.1.70338/*.csv` | The same extraction for 1.60.1.70338, the product dataset of M5.DATA.2 (`--build 1.60.1.70338 --tooltips`); `tests/unit/test_build_70338.py` regenerates the committed 70338 sidecars, coverage report and Markdown from it byte for byte. |
| `tooltips/forever/1.60.1.70374/*.csv` | The same extraction for the product dataset 1.60.1.70374 (M5.DATA.3, `--build 1.60.1.70374 --tooltips`); `tests/unit/test_product_build.py` regenerates the committed 70374 sidecars, coverage report and Markdown from it byte for byte. (Its rows equal 70338's: the source tables of both builds are byte-identical.) |
| `localization/forever/1.60.1.70374/{enUS,ruRU}/*.csv` | The M5.REL.3a localization extraction for 1.60.1.70374 (`--build 1.60.1.70374 --localization ruRU`); `tests/unit/test_product_build.py` regenerates the committed 70374 Warrior overlay, coverage report and Markdown from it byte for byte. |
| `casc/forever/1.60.1.70009/<FileDataID>.blp` | Two verbatim client icon files of that build, as served by `GET https://wago.tools/api/casc/<FileDataID>?version=1.60.1.70009` (2026-10-07): `132269` (BLP2, DXT with a real alpha channel) and `132090` (BLP2 without alpha). Blizzard artwork, used to test the BLP2 → PNG conversion; see `docs/talent-visuals.md`. |
| `casc/forever/1.60.1.70450/132089.blp` | The verbatim client icon file 132089 of 1.60.1.70450 (`GET https://wago.tools/api/casc/132089?version=1.60.1.70450`, M5.DATA.4 cache; 6 660 bytes, `sha256:7d8cc4ad…`, the `sourceSha256` of the committed registry). Converts to the committed `data/visuals/forever/1.60.1.70450/icons/132089.png`. |
| `visuals/forever/1.60.1.70450/linux-x86_64/132089.png` | The PNG the OTA preparation made of that file on GitHub's `ubuntu-24.04` x86_64 runner (Pillow 12.3.0 manylinux wheel, zlib 1.3.1) in Actions run 39776364185: other bytes (`sha256:0e7602b8…`) for the same pixels. Regression input of `tests/unit/visuals/test_reproduction.py` and the forced re-run tests of `test_ota_prepare.py`. |
| `db2/forever/<build>/<class-token>/_table_status.json` | Table-availability snapshot of the build: the tables the v1 contract requires to be absent (`TraitNodeGroupXTraitCost`, `TraitNodeXTraitCost`, `TraitNodeEntryXTraitCost`), each recorded `absent` with wago.tools' evidence (`{"errors": "Table not found."}` for that build). Copied from the cache by `extract_fixtures.py`, which refuses builds that do not prove the absence. A missing file never means "absent". |
| `wago/builds.json` | Subset of the real `GET https://wago.tools/api/builds` response (2026-09-27): 3 `5.5.0` + all five `1.60.1` builds of `wow_classic_beta` in a deliberately scrambled order, plus two `wow_classic_era` builds. |
| `wago/builds-2026-10-10.json` | Subset of the real `GET https://wago.tools/api/builds` response of 2026-10-10 (17:28 UTC, M5.DATA.2): the ten `1.60.1` builds `70009` … `70338` of `wow_classic_beta` in a scrambled order with two `5.5.0` builds, plus the two newest `1.60.1` builds of `wow_cn_beta`. Every `is_bgdl` is `false` as served; `tests/unit/test_freshness.py` derives its `true` / absent / non-boolean variants explicitly. |
| `wago/builds-2026-10-14.json` | Subset of the real `GET https://wago.tools/api/builds` response of 2026-10-14 (16:50 UTC, M5.DATA.3) in served order: two `5.5.0` and all fifteen `1.60.1` builds of `wow_classic_beta` (`69876` … `70374`), the two newest `1.60.1` builds of `wow_cn_beta`, and the whole `wowf` product (one entry, `1.60.1.70245`, first seen in this index). Every `is_bgdl` is `false` as served. |
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
python scripts/extract_fixtures.py --build 1.60.1.70338 --classes all
python scripts/extract_fixtures.py --build 1.60.1.70338 --tooltips
python scripts/extract_fixtures.py --build 1.60.1.70374 --classes all
python scripts/extract_fixtures.py --build 1.60.1.70374 --tooltips
python scripts/extract_fixtures.py --build 1.60.1.70374 --localization ruRU   # after localization fetch
# or a subset: --classes warrior,druid
```
