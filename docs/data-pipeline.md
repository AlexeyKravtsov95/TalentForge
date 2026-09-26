# Talent data pipeline (M1: Forever / all nine classes)

This document describes how TalentForge turns World of Warcraft client data into a stable,
versioned talent-tree contract, and how it detects semantic changes between client builds.
Everything below was verified against real Forever beta builds `1.60.1.69876` … `1.60.1.70009`
on 2026-09-27. Nothing in the pipeline is hand-written talent data.

```
wago.tools /api/builds ──► BuildSource ──► latest Forever build
wago.tools /db2/<T>/csv ─► Db2Source ────► .cache/wago/forever/<build>/<T>.csv (+ .meta.json)
                                             │
                        TraitDataSet (typed, indexed DB2 rows)
                                             │
                              normalizer ────► data/normalized/forever/<build>/<class>.json
                              validators       (schema v1, contentHash) × 9 classes
                                             │        + manifest.json (dataset index, v1)
                     diff engine + renderer ─► stdout + artifacts/diff/<game>-<class>-<a>-<b>.json
                                              + artifacts/diff/<game>-all-<a>-<b>.json (aggregate)
```

## 1. Where builds come from

`GET https://wago.tools/api/builds` returns an object keyed by Blizzard product code, each value
a list of builds:

```json
{"wow_classic_beta": [{"product": "wow_classic_beta", "version": "1.60.1.70009",
  "created_at": "2026-09-24 22:02:03", "build_config": "…", "product_config": "…",
  "cdn_config": "…", "is_bgdl": false}, …], "wow": […], …}
```

Observed facts the code relies on (and tests pin with a real subset of the payload):

* **The list is not sorted by date.** `wow_classic_beta` starts with 2025 `5.5.0` builds; the
  Forever builds come later. The code never uses list order.
* `created_at` is `YYYY-MM-DD HH:MM:SS` with no timezone. It is parsed as a naive datetime and
  only compared with other values of the same field.
* Every entry repeats its `product`; a mismatch with the enclosing key is treated as a schema
  error rather than silently accepted.

The payload is parsed with Pydantic (`sources/wago/models.py`), so an upstream schema change
fails loudly with a clear message.

## 2. How a Forever build is identified

A build is Forever when **both** hold (`games.py`):

| rule | value | why |
| --- | --- | --- |
| product | `wow_classic_beta` | Forever beta ships through this product |
| version branch (`major.minor`) | `1.60` | the same product also carries `5.5.0` (MoP Classic beta) builds, and `wow_classic_era` carries `1.15.x` |

The **latest** build is the one with the greatest `created_at` (version as tie-breaker)
— `sources/base.py::latest_build`. At the time of writing:

| version | created_at |
| --- | --- |
| 1.60.1.70009 | 2026-09-24 22:02:03 ← latest |
| 1.60.1.69977 | 2026-09-23 00:35:05 |
| 1.60.1.69913 | 2026-09-18 03:02:04 |
| 1.60.1.69893 | 2026-09-16 23:14:03 |
| 1.60.1.69876 | 2026-09-16 18:23:04 |

`is_bgdl` (background-download build) is carried in `BuildInfo.extra` but not used for
selection; none of the Forever builds had it set. M1 makes the distinction explicit in
the domain model (`classify_build` / `latest_publishable_build` in `sources/base.py`)
and surfaces `[background-download]` in `builds list`: "latest detected" and "latest
publishable" are different notions, and neither is proven "live" — wago.tools exposes no
live marker, while the flag *is* actively used on the retail product (12 bgdl builds at
the time of writing). See `docs/forever-dataset.md` for the full reasoning.

## 3. DB2 tables used

Downloaded per build with `GET https://wago.tools/db2/<Table>/csv?build=<version>`
(`db2/tables.py::TRAIT_TABLES`):

| purpose | tables |
| --- | --- |
| class → tree | `ChrClasses`, `SkillRaceClassInfo`, `SkillLineXTraitTree` |
| tree structure | `TraitTree`, `TraitNode`, `TraitNodeXTraitNodeEntry`, `TraitNodeEntry`, `TraitDefinition`, `TraitEdge` |
| point budget | `TraitTreeXTraitCurrency`, `TraitCurrency` |
| tier gates | `TraitNodeGroupXTraitNode`, `TraitNodeGroupXTraitCond`, `TraitNodeXTraitCond`, `TraitCond` |
| per-rank values | `TraitDefinitionEffectPoints`, `Curve`, `CurvePoint` |
| spells | `Spell` (tooltip template), `SpellName`, `SpellEffect` |

About 7.9 MB of CSV per build (SpellEffect, Spell, SpellName and CurvePoint dominate).
Tables that **do not exist** in Forever builds (wago.tools answers 404):
`TraitTreeLoadout`, `TraitTreeLoadoutEntry`, `TraitSubTree`, `TraitNodeEntryXTraitCond`,
`TraitNodeGroupXTraitCost`.

CSV transport details handled only inside the wago adapter: reserved column names are
prefixed with `_` (`_Index` → `Index`), array columns are flattened as `Name_0`, `Name_1`, and
text cells can contain quoted newlines.

## 4. Table relationships (verified on real rows)

### Class → tree

`TraitTree` has no class or name column in Forever, and the retail link
`TraitTreeLoadout.ChrSpecializationID` does not exist. The only class link in the client is the
class skill line:

```
ChrClasses.ID = 1 (Filename WARRIOR)
  class bit = 1 << (ID - 1) = 1
SkillRaceClassInfo.SkillID = 26, ClassMask = 1        (skill line 26 = "Arms")
SkillLineXTraitTree.SkillLineID = 26 → TraitTreeID = 1117
```

All nine classes map 1:1 this way (Mage 237→1112, Druid 574→1089, Hunter 50→1091,
Paladin 184→1100, Priest 613→1114, Rogue 38→1111, Shaman 373→1082, Warlock 354→1116,
Warrior 26→1117). Skill lines with `ClassMask <= 0` (unrestricted / all classes) are ignored.

### Tree → node → entry → definition → spell

```
TraitTree.ID = 1117
  ← TraitNode.TraitTreeID                     53 nodes (PosX, PosY, Type, Flags)
  ← TraitNodeXTraitNodeEntry.TraitNodeID      exactly 1 entry per node in Forever (Index = 100)
  → TraitNodeEntry.ID                         MaxRanks, NodeEntryType (= 1 everywhere)
  → TraitDefinition.ID                        SpellID; OverrideName/OverrideDescription (empty)
  → SpellName.ID / Spell.ID                   name / tooltip template
```

Name = `TraitDefinition.OverrideName_lang` if non-empty, else `SpellName` of
`VisibleSpellID` (if set) or `SpellID`. Same precedence for the tooltip template.

Example (1.60.1.70009): node `105958` (x=1020, y=2130) → entry `130688` (MaxRanks 3) →
definition `135489` → spell `12282` "Improved Heroic Strike".

### Prerequisites (`TraitEdge`)

`LeftTraitNodeID` is the prerequisite of `RightTraitNodeID`. Verified on Warrior:
Improved Rend (2220,2130) → Deep Wounds (2220,3330), Sweeping Strikes → Mortal Strike,
Death Wish → Bloodthirst, Concussion Blow → Shield Slam. All 8 Warrior edges have
`Type = 2` (*SufficientForAvailability* per WoWDBDefs). Types observed in the whole `TraitEdge` table (96 rows):
`2` (82), `0` VisualOnly (13), `3` RequiredForAvailability (1). Any other type makes
normalization fail loudly instead of being guessed.

### Tabs and tier gates (`TraitNodeGroup*`, `TraitCond`)

Forever stores the three classic talent tabs of a class **inside one TraitTree**; there is no
tab column. Tier requirements are conditions attached to node groups:

```
TraitNodeGroupXTraitNode   group 11649 = the 3 row-1 nodes of the first tab
TraitNodeGroupXTraitCond   group 11649 → cond 46498
TraitCond 46498            CondType 0 (Available), TraitNodeGroupID 12825, SpentAmountRequired 5
TraitNodeGroupXTraitNode   group 12825 = the 3 row-0 nodes of the first tab
```

i.e. "spend 5 points in the row above to unlock row 1". Row *r* needs 5·r points counted over
the rows above it; the capstone (row 6) needs 30 points counted over group `11650` = the whole
tab (17 nodes). The normalizer turns each such condition into
`unlockRequirements: [{requiredPoints, countedNodeIds}]`.

Sections (tabs) are derived, not guessed: the maximal node groups referenced by gates
(`11650`, `11657`, `11670` for Warrior) are accepted as sections **only if they partition the
tree's nodes**; otherwise `sections` stays empty. Tab names do not exist in the client data
(`name: null`).

Conditions that are not `CondType 0` + group + spent amount with no other constraint are kept
as `unresolvedConditionIds`. M1 additionally proves a class of conditions **vacuous**: a
CondType-0 row whose every constraint field is zero (group, node, entry, currency, spent
amount, level, quest, achievement, spec set, granted ranks, flags) cannot gate anything
and is treated as a no-op — real examples are Priest cond `43478` (whole Discipline tab)
and Druid cond `50974` (next to the real gate `50973`). The investigation is in
`docs/forever-dataset.md`.

### Point budget

`TraitTreeXTraitCurrency` → `TraitCurrency 3820` (`SourcedMax = 51`), matching the 51 talent
points of Forever. Exposed as `pointBudget.maxPoints`.

### Rank values (`TraitDefinitionEffectPoints` → `CurvePoint`)

```
TraitDefinitionEffectPoints  TraitDefinitionID 135489, EffectIndex 0, OperationType 0, CurveID 83317
CurvePoint (CurveID 83317)   Pos_0 = rank, Pos_1 = value: (1, -10) (2, -20) (3, -30)
Spell 12282 tooltip          "Reduces the cost of your Heroic Strike ability by $/10;s1 Rage."
```

`-10/10 = 1` Rage per rank, i.e. 1/2/3 — the curve is the per-rank value of effect `$s1`.

**Why not `SpellEffect.EffectBasePointsF`:** across the 526 effect-points rows of all nine
trees (1.60.1.70009), for multi-rank talents the spell's base points equal the curve's
max-rank value in 319 cases, the rank-1 value in 89, neither in 105, and 8 effects have no
`SpellEffect` row at all; for the 5 single-rank cases base points differ from the curve
(e.g. Deep Wounds: base 0, curve 20/40/60; Improved Slam effect 0: base −500, curve
−250/−500). It is therefore exposed only as the
informational `basePoints` field, never as rank scaling.

All effect-points rows in Forever use `OperationType = 0`, `Curve.Type = 0`. Other operation
types are reported as `perRankStatus: "unresolved"`. Curve points beyond `MaxRanks` are not
exposed (Last Stand: 1 rank, 3-point curve); missing ranks yield `"incomplete"`
(Rogue "Cutthroat" effect #1 in 1.60.1.70009).

## 5. Raw → normalized pipeline

1. `BuildSource.list_builds(game)` → `latest_build()` (explicit `created_at` ordering).
2. `Db2Source.ensure_table()` for each table in `TRAIT_TABLES`. The HTTP layer
   (`sources/http.py`) uses connect/read timeouts, retries connection errors, timeouts,
   408/425/429/5xx and truncated bodies with exponential backoff (4 attempts), never retries
   other 4xx, writes to `<file>.part` and renames atomically, and writes a
   `<file>.meta.json` (size + sha256) **after** the rename. A cache entry is valid only if the
   sidecar matches, so an interrupted download is re-fetched, never used. Payloads that are
   HTML or have no `ID` column are rejected (real case: wago.tools served `TraitCurrency` for
   1.60.1.69876 with placeholder columns `Field_1_60_1_69876_000…` because no DB2 definition
   matched that layout — the fetch fails with a clear message instead of producing garbage).
3. `TraitDataSet.load()` parses rows into typed records (`db2/tables.py`); any non-numeric
   value or missing column fails with table + column in the message; duplicate primary keys
   fail.
4. `normalize_class()` resolves the class, builds trees, and computes `contentHash`;
   `normalize_batch()` (M1) does this for every class of the build, in class-id order,
   recording per-class failures instead of stopping at the first one.
5. `validate_document()`; the CLI writes nothing if there is any error — and a batch writes
   nothing (no class documents, no manifest) unless **every** class validated clean, so a
   partially-valid dataset is never published.

The normalizer and diff engine only see `Db2Table` / `TraitDataSet`; they do not know about
wago.tools, HTTP or CSV. `sources/local.py` (a directory of CSVs) is a second implementation of
the same protocol, used by the tests.

## 6. Normalized schema (v1)

JSON Schemas of all public contracts are committed and generated from the Pydantic models;
a test fails if any committed schema drifts from the code (developers regenerate with
`python -m talent_data schema`):

* `talent-data.v1.schema.json` — one class document (below)
* `dataset-manifest.v1.schema.json` — the per-build dataset index (§6c)
* `talent-diff.v1.schema.json` — one class's semantic diff (§7)
* `dataset-diff.v1.schema.json` — the all-class diff aggregate (§7)

```jsonc
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "class": {"id": 1, "token": "WARRIOR", "name": "Warrior"},
  "trees": [{
    "id": 1117,
    "pointBudget": {"currencyId": 3820, "maxPoints": 51},
    "layout": {"xStep": 600, "yStep": 600},
    "sections": [{"index": 0, "name": null, "nodeIds": [...]}, ...],
    "visualOnlyEdges": [],
    "nodes": [{
      "id": 105950,                       // TraitNode.ID = identity across builds
      "sectionIndex": 0,
      "position": {"x": 2220, "y": 3330, "row": 2, "column": 2},
      "type": "single", "flags": 0,
      "maxRank": 3, "spellId": 12834, "name": "Deep Wounds",
      "prerequisites": [{"nodeId": 105956, "kind": "sufficient"}],
      "unlockRequirements": [{"kind": "pointsSpent", "requiredPoints": 10,
                              "countedNodeIds": [105952, 105954, ...]}],
      "unresolvedConditionIds": [],
      "entries": [{
        "id": 130680, "definitionId": 135481, "spellId": 12834, "name": "Deep Wounds",
        "maxRank": 3, "entryType": 1,
        "descriptionTemplate": "Your critical strikes cause your opponent to Bleed, dealing $m1% …",
        "effects": [{"index": 0, "perRank": [{"rank": 1, "value": 20}, …],
                     "perRankStatus": "resolved", "basePoints": 0, "unresolvedReason": null}]
      }]
    }]
  }],
  "contentHash": "sha256:…",             // over everything except the volatile fields below
  "generatedAt": "…",                     // volatile
  "provenance": {"source": "wago.tools", "tables": [...]}  // volatile
}
```

Design choices:

* **Not a DB2 dump.** Join tables, curves and conditions are resolved into node-centric facts;
  no DB2 column names appear in the output (a test enforces this).
* **Identity** is `TraitNode.ID` — stable across all five Forever builds observed.
* **Layout.** Raw `x`/`y` are authoritative. `row`/`column` are a convenience grid fitted from
  the data (`normalization/grid.py`: dominant spacing + dominant phase, per-section column
  origin), because real layouts contain nudged nodes (Paladin x=5030 next to 5020, Warlock
  y=2120, Hunter Lightning Reflexes +120). Off-grid nodes produce validation warnings.
* **Unknowns are explicit**: `name: null` for sections, `perRankStatus`, `unresolvedReason`,
  `unresolvedConditionIds`, raw `flags` / `entryType`.
* **Determinism.** Nodes, entries, edges and lists are sorted; normalizing twice yields
  byte-identical JSON apart from `generatedAt`. `contentHash` lets clients detect semantic
  changes without diffing.

### 6b. Validation issues (M1 taxonomy)

Validation findings are typed values (`validation/issues.py`), not text:

```python
ValidationIssue(
    severity="warning",
    code="OFF_GRID_NODE",
    message="…",
    tree_id=1091,
    node_id=104982,
    entry_id=None,
    effect_index=None,
)
```

* `severity` is `error` (must not be published) or `warning` (known data feature / pipeline
  gap, surfaced, never hidden).
* `code` is stable and machine-readable; automation branches on it, never on message text
  (messages remain human-readable and may be reworded).
* Warning codes observed in real data are registered in `KNOWN_WARNING_CODES`; a test pins
  the exact per-class warning set of the fixture build, so an *unknown* code fails CI until
  investigated. Each known code has an investigation entry and a regression test —
  see `docs/forever-dataset.md`.

Current warning codes: `OFF_GRID_NODE`, `DUPLICATE_SPELL_IDENTITY`,
`SUFFICIENT_PREREQUISITE_CYCLE`, `INCOMPLETE_RANK_CURVE`, `UNRESOLVED_RANK_CURVE`,
`SHARED_GRID_CELL`, `SECTIONS_UNRESOLVED` (plus `UNRESOLVED_CONDITION`, currently produced
by no real Forever data).

### 6c. Dataset manifest (M1)

Every successful batch normalize writes `data/normalized/forever/<build>/manifest.json`
(schema v1, `models/manifest.py`): build identity, class inventory (ordered by class id),
per-class file name + tree ids + semantic `contentHash`, and validation counts with
warnings broken down by code. It is the entry point a future mobile updater reads first;
see `docs/forever-dataset.md` for an example and the write-only-if-all-classes-pass rule.

## 7. Semantic diff

`diff/engine.py` compares two documents of the same game + class; `diff/render.py` prints the
developer view; the machine view follows
[`docs/schema/talent-diff.v1.schema.json`](schema/talent-diff.v1.schema.json).

| change | rule |
| --- | --- |
| ADDED / REMOVED | node id present on one side only |
| `renamed` | same node, same spell id, different name |
| `spellReplaced` | same node, different spell id — **never** reported as a rename |
| (hint) `relatedNodeId` | a removed and an added node share a spell id 1:1; they stay removed + added |
| `maxRankChanged`, `positionChanged` (raw x/y), `sectionChanged`, `typeChanged`, `flagsChanged` | field comparison |
| `prerequisitesChanged` | set of (nodeId, kind) differs |
| `unlockRequirementsChanged` | required points / counted nodes differ, ignoring nodes that were themselves added/removed (avoids cascades) |
| `effectRankValueChanged` | per effect index, per rank: `rank 1: 5 -> 6` |
| `effectAdded` / `effectRemoved` / `effectBasePointsChanged` / `effectStatusChanged` | per effect index |
| `descriptionChanged` | raw tooltip template changed |

Effects are matched by effect index because that is what tooltip placeholders refer to
(`$s1` = index 0).

### Batch diff and diff noise (M1)

`diff --class all` diffs every class and writes (a) one unchanged talent-diff v1 document
per class and (b) a small aggregate following
[`docs/schema/dataset-diff.v1.schema.json`](schema/dataset-diff.v1.schema.json) — per-class
summaries plus a TOTAL, pointing at the per-class files. The aggregate adds no new
semantics; it exists so consumers can scan a whole build-to-build move without opening
nine documents.

Which change kinds a player would perceive, and which are DB2 engineering noise, is a
documented, machine-readable boundary (`USER_VISIBLE_CHANGE_KINDS` /
`DIAGNOSTIC_CHANGE_KINDS` in `diff/models.py`; a test guarantees the two sets exactly
partition all change kinds):

* **User-visible**: node added/removed, `renamed`, `spellReplaced`, `maxRankChanged`,
  `effectRankValueChanged`, `effectStatusChanged`, `effectAdded`/`effectRemoved`,
  `descriptionChanged`, `positionChanged`, `sectionChanged`, `prerequisitesChanged`,
  `unlockRequirementsChanged`, `typeChanged`, `treeChanged`, `entriesChanged`,
  `unresolvedConditionsChanged`.
* **Diagnostic**: `flagsChanged` (raw flag semantics unknown) and
  `effectBasePointsChanged`. The latter is noisy because `SpellEffect` indexes can be
  re-numbered between builds: when an effect is *inserted*, every following effect shifts
  by one index, cascading into false "changed"/"added" entries (real case: Warrior
  Improved Slam in 1.60.1.70009 — five false base-point changes and a phantom effect #7
  "add", while the genuine change was effect #2 gaining rank values).

The raw engineering diff is never filtered or deduplicated; the classification is
information for future consumers (e.g. a mobile patch-history view must not present DB2
noise as balance changes).

## 8. Known limitations (M1)

* All nine Forever classes normalize and validate (0 errors). The remaining 11 warnings on
  1.60.1.70009 are investigated client-data features, each with a stable code and a
  regression test — see `docs/forever-dataset.md` for the evidence and the full list of
  unresolved semantics.
* Tooltips are raw templates; `$s1`, `${$m1/-1000}.2`, `$12976d`, conditionals are not
  rendered. Per-rank numbers are available structurally for curve-backed effects only.
* Tab names (Arms / Fury / Protection) are not present in the client data and are `null`.
* `TraitEdge` semantics beyond the WoWDBDefs names are unverified: whether *sufficient* edges
  are OR-ed and *required* edges AND-ed, and whether a prerequisite must be fully ranked, is
  not encoded. Hunter 1.60.1.70009 contains a mutual pair of sufficient edges
  (Bestial Wrath ⇄ Intimidation) — preserved verbatim and reported as a warning.
* `TraitNode.Flags` (e.g. `8` on one node per Warrior tab) and `NodeEntryType` are exposed raw;
  their meaning is unknown.
* Only `TraitCond` "points spent in group" gates are interpreted; everything else is listed as
  unresolved (all-zero *vacuous* conditions are proven no-ops). Costs (`TraitCost`) are not
  modelled (1 point per rank is not asserted).
* Only `TraitDefinitionEffectPoints.OperationType = 0` is interpreted.
* Icons, spell ranks granted by talents, and localization (enUS only, as served by wago) are
  out of scope.
* Builds whose DB2 layout wago.tools cannot name (1.60.1.69876 `TraitCurrency`) cannot be
  normalized through wago; a direct client extractor would be needed.
* `created_at` timezone is undocumented; it is only used for ordering.
* The manifest is a local file; CDN/network publishing and scheduled dataset releases are
  out of scope for M1.

## 9. Reproducing locally

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"

python -m talent_data builds list --game forever
python -m talent_data builds latest --game forever        # Forever latest build: 1.60.1.70009
python -m talent_data fetch --game forever --build 1.60.1.69977
python -m talent_data fetch --game forever --build latest
python -m talent_data normalize --game forever --build 1.60.1.70009 --class warrior
python -m talent_data normalize --game forever --build latest --class all   # whole dataset + manifest
python -m talent_data diff --game forever --class warrior --from 1.60.1.69977 --to 1.60.1.70009
python -m talent_data diff --game forever --class all --from 1.60.1.69977 --to 1.60.1.70009
python -m talent_data validate data/normalized/forever/1.60.1.70009/hunter.json
python -m talent_data manifest data/normalized/forever/1.60.1.70009/manifest.json

ruff check . && ruff format --check . && mypy && pytest          # offline (CI runs the same)
pytest -m integration                                             # live wago.tools (manual workflow)
```

Raw CSVs go to `.cache/wago/forever/<build>/` (git-ignored). Normalized documents go to
`data/normalized/forever/<build>/<class>.json` with `manifest.json` next to them; diffs to
`artifacts/diff/`. Set `TALENT_DATA_ROOT` to use another root directory. Test fixtures are
regenerated with `scripts/extract_fixtures.py --build <build> --classes all`
(see `tests/fixtures/README.md`).

## 10. What M1 did not do

Mobile clients, backend/network publishing of datasets, scheduled releases, further game
flavours (Classic Era, TBC, …) and tooltip rendering are all out of scope — M1 is a
data-platform milestone only. See `docs/forever-dataset.md` for the recommended next
milestone.

## References

* WoWDBDefs (`wowdev/WoWDBDefs`) — DB2 column names and the documented enum values used here
  (TraitNode.Type, TraitEdge.Type, TraitCond.CondType). Used as documentation only; not a
  runtime dependency, no code copied.
* wago.tools — build list and DB2 CSV exports (the M0 source of truth transport).
* Third-party calculators were not used as a data source.
