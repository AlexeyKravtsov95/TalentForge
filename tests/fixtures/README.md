# Test fixtures

All fixtures are **real** client data, reduced in size — nothing here is invented.

| Path | Origin |
| --- | --- |
| `db2/forever/<build>/*.csv` | wago.tools DB2 CSV exports of the given Forever build, filtered by `scripts/extract_fixtures.py --tree 1117` to the rows reachable from the Warrior talent tree (plus the 9-row class tables). Column names are canonical (`_Index` → `Index`). |
| `wago/builds.json` | Subset of the real `GET https://wago.tools/api/builds` response (2026-09-27): 3 `5.5.0` + all five `1.60.1` builds of `wow_classic_beta` in a deliberately scrambled order, plus two `wow_classic_era` builds. |
| `wago/*.csv` | Verbatim head rows of wago.tools CSV exports (wago column naming, quoted multi-line text). |

Regenerate the DB2 subset after `python -m talent_data fetch --build <build>`:

```bash
python scripts/extract_fixtures.py --build 1.60.1.69977 --tree 1117
python scripts/extract_fixtures.py --build 1.60.1.70009 --tree 1117
```
