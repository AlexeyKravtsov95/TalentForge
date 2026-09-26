# The Forever dataset (M1): all nine classes

This document is the register of everything M1 knows about the complete Forever dataset:
per-class facts, every validation warning with its investigation, and the semantics we
could not (yet) prove. Data basis: real client DB2 tables of builds `1.60.1.69893` …
`1.60.1.70009` fetched from wago.tools on 2026-09-27.

## One-command dataset

```bash
python -m talent_data normalize --game forever --build latest --class all
# -> data/normalized/forever/<build>/{warrior..druid}.json + manifest.json

python -m talent_data diff --game forever --class all --from 1.60.1.69977 --to 1.60.1.70009
# -> per-class talent-diff v1 JSONs + aggregate forever-all-<a>-<b>.json + summary
```

Classes are discovered from `ChrClasses` (data-driven, no hardcoded list), processed and
published in class-id order. `--class` also accepts a comma-separated subset
(`--class warrior,druid`); single-class invocations behave exactly as in M0.

## Dataset status (build 1.60.1.70009)

| Class | ID | Tree | Nodes | Sections | Point budget | Errors | Warnings (codes) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Warrior | 1 | 1117 | 53 | 3 | 51 | 0 | — |
| Paladin | 2 | 1100 | 50 | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Hunter | 3 | 1091 | 52 | 3 | 51 | 0 | 2 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY, 1 × SUFFICIENT_PREREQUISITE_CYCLE |
| Rogue | 4 | 1111 | 53 | 3 | 51 | 0 | 1 × INCOMPLETE_RANK_CURVE |
| Priest | 5 | 1114 | 54 | 3 | 51 | 0 | 1 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY |
| Shaman | 7 | 1082 | 50 | 3 | 51 | 0 | — |
| Mage | 8 | 1112 | 54 | 3 | 51 | 0 | — |
| Warlock | 9 | 1116 | 52 | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Druid | 11 | 1089 | 51 | 3 | 51 | 0 | — |

Total: 469 nodes, 0 errors, 11 warnings — every warning below is investigated, has a
stable machine-readable code and a regression test
(`tests/unit/test_known_anomalies.py`). The same 11 warnings appear on 1.60.1.69977.
**Unknown warning codes are not normal**: a test pins the exact per-class warning set, so
a new code from Blizzard data fails CI until it is investigated and added to this
document (or the pipeline is fixed).

## Warning investigations

### OFF_GRID_NODE — mis-placed duplicate nodes (Hunter, Priest)

Two trees contain a *duplicate* node for an existing talent whose raw coordinate has an
extra trailing zero:

| Tree | Talent | Properly placed node | Mis-placed duplicate | Anomaly |
| --- | --- | --- | --- | --- |
| Hunter | Lightning Reflexes (spell 19168) | 110859 @ (10280, 5130) | 104982 @ (**102800**, 5740) | x ×10 |
| Priest | Holy Specialization (spell 14889) | 110855 @ (6220, 2130) | 105865 @ (9280, **21300**) | y ×10 |
| Hunter | Improved Serpent Sting | 105003 @ (6820, **39300**) | — (no duplicate partner) | y ×10 |

The mis-placed copies land ~50 grid rows away from their tab (e.g. row 62 of a 7-row
tree) and duplicate the spell id of the properly placed node. This looks like leftover
rows in the client data (the duplicates are in the older 104xxx/105xxx id range, the
placed ones in the newer 110xxx range). **Raw `x`/`y` stay authoritative** — the
pipeline never moves a node — and the anomaly is flagged twice, once as OFF_GRID_NODE
and once as DUPLICATE_SPELL_IDENTITY, which is exactly what a consumer needs to know
(spell-identity hints are ambiguous in these trees).

### OFF_GRID_NODE — deliberate nudges (Paladin, Warlock)

| Tree | Nodes | Raw positions | Neighbouring grid lines |
| --- | --- | --- | --- |
| Paladin | 110875 Improved Seal of Fury, 110878 Swift Judgement | x=5030 | x=5020 + 600·k |
| Warlock | 105921 Improved Life Tap (y=2120), 105916 Amplify Curse (y=3320) | y=2120/3320 | y=2130 + 600·k |

These are ±10-unit offsets from otherwise perfectly regular layouts — the same
deliberate "nudge" style M0 already documented for classic layouts. Not a data defect;
the grid (row/column) is derived, raw coordinates remain the truth.

### SUFFICIENT_PREREQUISITE_CYCLE — Hunter Bestial Wrath ⇄ Intimidation

`TraitEdge` rows (1.60.1.70009): `124700: 104961→104964`, `124701: 104964→104961` (both
Type 2 *SufficientForAvailability*), plus `124695: 104970→104964`.

* 104961 Bestial Wrath — capstone (row 6), gated by "spend 30 points in the tab".
* 104964 Intimidation — row 4, gated by "spend 20 points", with **two** sufficient
  prerequisites: Bestial Swiftness (row 2, the normal forward edge) and Bestial Wrath
  (row 6, the backward edge).
* 104961→104964 is the **only depth-decreasing sufficient edge in all nine trees**; the
  other same-row edges (Paladin Holy Shock→Divine Precision, Priest Mind Flay→Improved
  Mind Flay) follow the base-ability→improver pattern and are one-directional.

What the evidence supports:

* WoWDBDefs documents only the enum *names* (Sufficient/RequiredForAvailability), not how
  multiple sufficient edges combine.
* Under **OR** semantics ("any satisfied sufficient edge makes the node available") the
  graph is playable: Bestial Swiftness unlocks Intimidation, Intimidation unlocks Bestial
  Wrath; the backward edge is then redundant-but-harmless.
* Under **AND** semantics the pair {Bestial Wrath, Intimidation} would be mutually
  unreachable — an unobtainable capstone, which contradicts the tree shipping in every
  observed build.

Since OR-vs-AND cannot be proven from client data alone, the contract **keeps every edge
verbatim as `kind: "sufficient"`** (no conversion to required/AND) and the validator
flags the cycle as a warning. A future mobile client must treat multiple sufficient
prerequisites as *at least one* until proven otherwise.

### INCOMPLETE_RANK_CURVE — Rogue Cutthroat effect #1

Cutthroat (node 105750, spell 462708, `MaxRanks=5`):

* effect #0 → curve 83008 with 5 points (3/6/9/12/15) — resolved;
* effect #1 → curve 83007 with only 3 points (5/10/15), and **no `SpellEffect` row with
  index 1 exists at all** (base points are null).

Stable across all four builds we can normalize (69893, 69913, 69977, 70009): the client
data itself is incomplete, this is not a MaxRanks misread (effect #0 has all 5 ranks).
The pipeline exposes the three existing values with `perRankStatus: "incomplete"` and
warns; it never invents ranks 4–5.

### UNRESOLVED_CONDITION — resolved as vacuous in M1 (Priest 43478, Druid 50974)

M0 reported 20 of these (18 Priest + 2 Druid nodes). Investigation of the raw rows:

* Priest: group 11608 (the 18 Discipline-tab nodes) carries cond **43478** —
  `CondType=0` (Available), `TraitTreeID=1114`, and **every constraint field zero**.
* Druid: group 11329 (Improved Entangling Roots, Nature's Splendor) carries the *real*
  gate 50973 ("spend 10 points in group 12700", currency 3820) *plus* cond **50974** —
  again all-zero except `TraitTreeID=1089`.

A row with no group, node, entry, currency, spent amount, level, quest, achievement,
spec set, granted ranks or flags cannot gate anything — every possible reading
("spend ≥ 0", "level ≥ 0", "no quest required", …) is trivially satisfied. `TraitTreeID`
is ignored in this judgement because the *real* gates (e.g. 50973) also carry it: it
scopes a condition to its tree, it does not constrain availability.

Conclusion: these conditions are **provably vacuous** — authoring leftovers. M1 treats
them as no-ops: they no longer appear in `unresolvedConditionIds` and no longer warn.
The real gates on the same groups are unaffected (regression-tested: Penance still
requires 20 points in 14 nodes, Druid roots 10 points in 6 nodes). If Blizzard ever
populates such a row, it stops being all-zero and is either interpreted or reported as
UNRESOLVED_CONDITION again — nothing can be silently ignored.

### Diff noise (all-class diff evidence)

The 69977→70009 all-class diff produces 24 changed nodes; the raw change kinds:

* **User-visible** (16 of the kind-instances): `renamed` (Druid Mangle→Primal Bite,
  Primal Fury→Blood Frenzy), `effectRankValueChanged` (15 rank values, e.g. Paladin Holy
  Power crit bonus 2/4/6/8/10 → 3/6/9/12/15), `descriptionChanged` (12), Shaman
  `positionChanged`+`prerequisitesChanged` (two talents swapped places), Paladin
  `effectRemoved` + 2 REMOVED nodes (Improved Holy Strike, Crusade), Hunter
  `effectAdded` (Strider Kick movement-speed effect).
* **Diagnostic**: 9 × `effectBasePointsChanged`. Warrior Improved Slam (spell 12862)
  gained an *inserted* SpellEffect at index 2 in 70009; the diff engine matches effects
  by index, so old indexes 2–6 re-appear as 3–7, producing five false "base points
  changed" entries plus one "effect #7 added" that is really the shifted tail. The
  genuine change is effect #2 gaining rank values (−1500/−3000) and status
  none→resolved.

The machine-readable boundary lives in `talent_data.diff.models`
(`USER_VISIBLE_CHANGE_KINDS` / `DIAGNOSTIC_CHANGE_KINDS`); it is a classification only —
the raw engineering diff is never filtered.

## Dataset manifest

`data/normalized/forever/<build>/manifest.json` (schema v1, generated by every
successful batch normalize):

```jsonc
{
  "schemaVersion": 1,
  "game": "forever",
  "clientBuild": "1.60.1.70009",
  "generatedAt": "2026-09-27T12:00:00Z",
  "source": "wago.tools",
  "classes": [
    {
      "token": "WARRIOR",
      "file": "warrior.json",
      "treeIds": [1117],
      "contentHash": "sha256:…",       // semantic payload hash of the class document
      "validation": {
        "errors": 0,
        "warnings": 0,
        "warningsByCode": {}
      }
    },
    // … 8 more, in class-id order
  ]
}
```

A manifest is only written when **all** classes of the build validated with zero errors;
a failing batch writes nothing (no partial datasets). `python -m talent_data manifest
<path>` re-verifies a manifest against the class documents on disk (hash + validation).
A future mobile updater can therefore trust: build identity, class inventory, per-class
file, semantic content hash, and validation status — without loading every document.

## Build candidates: latest ≠ publishable ≠ live

wago.tools exposes `is_bgdl` per build. Observed on 2026-09-27: all five Forever 1.60.1
builds are `is_bgdl=false`; the retail `wow` product lists 12 builds with
`is_bgdl=true` (patch pre-downloads, e.g. 12.0.7.67808) — so the flag is actively used
by Battle.net and meaningful.

M1 domain model (`talent_data.sources.base`):

* `classify_build(info) -> BuildCandidate` — lifts the source-specific flag into the
  domain;
* `latest_build(builds)` — **latest detected** (what `--build latest` uses, unchanged
  from M0);
* `latest_publishable_build(candidates)` — latest **non-background-download** build. A
  background-download build has the newest `created_at` exactly when it must *not* be
  treated as production data, so "latest" and "latest publishable" diverge precisely
  when it matters.

wago.tools has no field that identifies the build the live realms run, so "publishable"
is only a necessary condition, never proof of "live". The pipeline surfaces
`[background-download]` in `builds list` output instead of guessing. No selection
behavior was changed silently in M1.

## Fixture strategy

`tests/fixtures/db2/forever/<build>/<class-token>/<Table>.csv` — for each of the nine
classes and both captured builds (69977, 70009), the strict subset of rows reachable
from that class's tree (~1000 rows per class), extracted by
`scripts/extract_fixtures.py` from the real wago.tools cache. Nothing is hand-written;
the Warrior M0 fixtures are the same rows in the new layout. Because each class
directory is self-contained, per-class tests cannot accidentally depend on another
class's rows. Tests that need a whole-build table set merge the per-class fixtures
deterministically (shared class tables are identical across classes, tree tables are
row-disjoint).

Regenerate after `python -m talent_data fetch --build <build>`:

```bash
python scripts/extract_fixtures.py --build 1.60.1.70009 --classes all
```

## Known unresolved semantics (unchanged or newly scoped in M1)

* Sufficient-edge combination semantics (OR vs AND) — see above; preserved verbatim.
* Whether a sufficient/required prerequisite must be *fully ranked* is not encoded.
* `TraitNode.Flags` and `TraitNodeEntry.NodeEntryType` remain raw.
* Costs (`TraitCost`) are not modelled; 1 point per rank is not asserted.
* Tab names (Arms/Fury/Protection…) do not exist in client data; `section.name` is null.
* Tooltips stay raw templates; per-rank numbers exist for curve-backed effects only.
* Whether the mis-placed duplicate nodes (Hunter/Priest) are drawn by the client is
  unknowable from DB2 alone; they are preserved in the contract and flagged.
* `effectBasePointsChanged` noise under SpellEffect index shifts (see diff noise above)
  is classified but not deduplicated in the raw diff.
