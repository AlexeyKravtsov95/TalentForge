# The Forever dataset (M1): all nine classes

This document is the register of everything M1 knows about the complete Forever dataset:
per-class facts, every validation warning with its investigation, and the semantics we
could not (yet) prove. Data basis: real client DB2 tables of builds `1.60.1.69893` …
`1.60.1.70009` fetched from wago.tools on 2026-09-27.

`1.60.1.70009` is the **verified reference baseline**: the allocation evidence and the
conformance vectors are pinned to it, and the warning investigations below were made on it.
Since M5.DATA.4 the **product dataset** — the one the app creates new builds on — is
`1.60.1.70450` ([below](#product-dataset-160170450-m5data4)). `1.60.1.70374`, the product
dataset of M5.DATA.3 ([below](#product-dataset-160170374-m5data3)), `1.60.1.70338`, the
product dataset of M5.DATA.2 ([below](#product-dataset-160170338-m5data2)), `1.60.1.70245`,
the product dataset of M5.DATA ([below](#product-dataset-160170245-m5data)), and 70009 stay
committed and bundled, unchanged, for the builds saved on them. None of them is claimed to be the
build the live realms run (`builds latest` reports the newest detected build at the moment
it runs; wago.tools has no "live" marker). Whether the product dataset is still the newest
publishable build is answered by `builds freshness` ([below](#product-freshness-check-m5data2)).

## One-command dataset

```bash
# any build: the newest one wago.tools reports right now
python -m talent_data normalize --game forever --build latest --class all
# -> data/normalized/forever/<build>/{warrior..druid}.json + manifest.json

# reproduce the verified reference baseline
python -m talent_data normalize --game forever --build 1.60.1.70009 --class all

python -m talent_data diff --game forever --class all --from 1.60.1.69977 --to 1.60.1.70009
# -> per-class talent-diff v1 JSONs + aggregate forever-all-<a>-<b>.json + summary
```

Classes are discovered from `ChrClasses` (data-driven, no hardcoded list), processed and
published in class-id order. `--class` also accepts a comma-separated subset
(`--class warrior,druid`); single-class invocations behave exactly as in M0.

## Dataset status (reference baseline 1.60.1.70009)

| Class | ID | Tree | Nodes | Sections | Point budget | Errors | Warnings (codes) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Warrior | 1 | 1117 | 53 | 3 | 51 | 0 | — |
| Paladin | 2 | 1100 | 50 | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Hunter | 3 | 1091 | 52 | 3 | 51 | 0 | 2 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY, 1 × SUFFICIENT_PREREQUISITE_CYCLE |
| Rogue | 4 | 1111 | 53 | 3 | 51 | 0 | 1 × INCOMPLETE_RANK_CURVE, 1 × UNRESOLVED_CONDITION |
| Priest | 5 | 1114 | 54 | 3 | 51 | 0 | 1 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY |
| Shaman | 7 | 1082 | 50 | 3 | 51 | 0 | — |
| Mage | 8 | 1112 | 54 | 3 | 51 | 0 | — |
| Warlock | 9 | 1116 | 52 | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Druid | 11 | 1089 | 51 | 3 | 51 | 0 | — |

Total: 469 nodes, 0 errors, 12 warnings — every warning below is investigated, has a
stable machine-readable code and a regression test
(`tests/unit/test_known_anomalies.py`). The same 12 warnings appear on 1.60.1.69977.
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

A row with no group, node, node entry, account element, currency, spent amount, level,
quest, achievement, spec set, granted ranks or flags cannot gate anything — every
possible reading ("spend ≥ 0", "level ≥ 0", "no quest required", …) is trivially
satisfied. Conclusion: these conditions are **provably vacuous** — authoring leftovers.
M1 treats them as no-ops: they no longer appear in `unresolvedConditionIds` and no
longer warn. The real gates on the same groups are unaffected (regression-tested:
Penance still requires 20 points in 14 nodes, Druid roots 10 points in 6 nodes). If
Blizzard ever populates such a row, it stops being all-zero and is either interpreted
or reported as UNRESOLVED_CONDITION again — nothing can be silently ignored.

#### TraitCond field semantics (M1 review research)

The vacuous/pure-gate classification considers *every* `TraitCond` column. Evidence
per field (WoWDBDefs definitions + real rows of Forever 1.60.1.70009 ×172 and retail
12.1.0.69933 ×10 962):

| Field | Semantics | Evidence | Used in vacuous judgement |
| --- | --- | --- | --- |
| `TraitNodeGroupID`, `TraitCurrencyID`, `SpentAmountRequired` | the points gate itself | all real Forever gates | yes (must be 0) |
| `TraitNodeID` | constraint on a specific node | Forever: cond 50984 references node 105709 | yes (must be 0) |
| `TraitNodeEntryID` | constraint on a specific node *entry* | functional in retail: 25 conditions (13 distinct entries) reference one | yes (must be 0) |
| `TraitCondAccountElementID` | account-wide gating via `TraitCondAccountElement` | functional in retail: 413 conditions reference account elements | yes (must be 0) |
| `RequiredLevel`, `GrantedRanks`, `QuestID`, `AchievementID`, `SpecSetID`, `Flags` | level / free ranks / quest / achievement / spec restrictions; Flags documented as "&1: IsGate, &2: IsAlwaysMet, &4: IsSufficient" | WoWDBDefs | yes (must be 0) |
| `TraitTreeID` | scoping reference, not a constraint | real gates (e.g. 50973) also carry it | no (ignored) |
| `FreeSharedStringID`, `SpendMoreSharedStringID` | **presentation-only**: `SharedString::ID` localization texts rendered next to a gate — retail string 910 = "This talent is granted to your specialization for free and can't be unlearned", 911 = "Spend %d more \|4point:points; to unlock this talent", 1217 = "Reach level %d to increase rank" | FK target is a string table; real Forever gates and vacuous rows carry the same string ids (the vacuous rows carry none) | no (ignored, documented) |

The same table governs the *interpretation* path: a condition is flattened into a plain
"spend N points in group G" unlock requirement **only** when it has exactly that shape
(`CondType=0`, no flags, and every other functional field zero). Anything else surfaces
as UNRESOLVED_CONDITION instead of being silently flattened — which is what Rogue cond
50984 exposed (below).

### UNRESOLVED_CONDITION — Rogue cond 50984 (found by the M1 review fix)

Rogue cond 50984 (attached directly to node 105712 "Venom" via `TraitNodeXTraitCond`)
combines **three** constraints: `TraitNodeID=105709` (Mutilate), group 11576 (3 nodes)
and `SpentAmountRequired=1` with currency 3820. Until the review fix, the normalizer
silently dropped the node reference and flattened the row into a "spend 1 point in 3
nodes" unlock requirement on Venom. How a node reference combines with a group/spend
gate is not documented anywhere (WoWDBDefs documents only the column names), so since
the fix the condition is exposed verbatim as `unresolvedConditionIds: [50984]` on Venom
with an `UNRESOLVED_CONDITION` warning; the fabricated gate is gone from the document
(the real 30-point capstone gate remains). Identical on both captured builds.

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

## Product dataset 1.60.1.70450 (M5.DATA.4)

### Build selection

Verified on **2026-10-17 at 18:40 UTC** with the repository's own check and against the raw
index (`GET https://wago.tools/api/builds`, response `Date: Sat, 17 Oct 2026 18:40:47 GMT`;
the M5.DATA.4 subset is committed as `tests/fixtures/wago/builds-2026-10-17.json`):

```text
$ python scripts/check_product_freshness.py --output freshness.json      # exit 3
OUTDATED: 1.60.1.70450 is publishable (is_bgdl=false) and newer than the product build 1.60.1.70374
```

| Fact | Value |
| --- | --- |
| `PRODUCT_BUNDLE` before this milestone | `1.60.1.70374` |
| Latest detected Forever build | `1.60.1.70450` (`wow_classic_beta`, created 2026-10-16 22:29:05) |
| Latest publishable Forever build | `1.60.1.70450` |
| `is_bgdl` of 70450 on the wire | JSON `false` (a strict boolean) |
| Builds between 70374 and 70450 | `1.60.1.70425` (created 2026-10-16 00:50:05, `is_bgdl=false`) |
| Newer non-publishable builds | none |
| Source / checked at | wago.tools `/api/builds`, `checkedAt` 2026-10-17T18:40:34Z |

70450 is product `wow_classic_beta`, branch `1.60` (`build_config
84bea4233331e390fda8724c6f91ffa5`, `cdn_config ff7ab925e1032e8380dfdc125b3fe185`, same
`product_config` as 70374), so **70450 is selected**. 70425 (`build_config
ea9c525eef74f1d682c00a8ef39a5d2b`) was fetched and normalized too: every talent change below
arrived with it, and its talent data equals 70450's (diff 70425 → 70450: 0 added, 0 removed,
0 changed; the 23 trait tables are byte-identical, only `Spell`/`SpellEffect`/`SpellName`/
`Curve`/`CurvePoint` differ, in rows no talent reads). Only 70450 is committed. `wow_cn_beta`
still stops at 70374.

**The `wowf` product, re-checked.** Unchanged since 2026-10-14: one entry, `1.60.1.70245`
(created 2026-10-13 21:18:10, `is_bgdl=false`, `build_config
e8c4ef4e67bd1d94d820254984258fa8`), older than the beta and not one of Forever's products
(`GameConfig.products` stays `("wow_classic_beta",)`). One more fact surfaced: the beta's own
70245 has a different `build_config` (`0bf141260c698dfc9cb3b5b7947b78c3`), yet wago.tools'
table export is addressed by version string alone (`?build=1.60.1.70245`), so the pipeline's
source cannot tell the two 70245 builds apart. Nothing in the index says that the source of
the current Forever data moved off `wow_classic_beta`; the product mapping is unchanged and
the gap stays recorded ([dataset-freshness-watch.md](dataset-freshness-watch.md#known-gap-the-wowf-product)).

**Basis and its limit** (unchanged): 70450 is the newest Forever build whose `is_bgdl` is
explicitly `false`. It is not evidence that the realms run 70450, and no 70450 client was
observed.

### Generation and integrity

Every artifact is generated from 70450's own client rows with the unchanged pipeline;
nothing is copied from 70374, nothing hand-edited, no check relaxed:

```bash
python -m talent_data fetch --game forever --build 1.60.1.70450
python -m talent_data normalize --game forever --build 1.60.1.70450 --class all
python -m talent_data diff --game forever --class all --from 1.60.1.70374 --to 1.60.1.70450
python -m talent_data manifest data/normalized/forever/1.60.1.70450/manifest.json --expect-complete
python -m talent_data visuals fetch|report|normalize|check --build 1.60.1.70450
python -m talent_data tooltips fetch|report|normalize|check --build 1.60.1.70450
python -m talent_data localization fetch|report|normalize|check --build 1.60.1.70450 --locale ruRU --classes all
python scripts/extract_fixtures.py --build 1.60.1.70450 --classes all   # + --tooltips, --localization ruRU
```

* 9 classes, 469 nodes, **0 errors, 13 warnings**: the 12 investigated warnings of the
  reference baseline (same codes, nodes and counts) plus **one on Mage**,
  `SECTION_NOT_GATE_COUNTED` for Arcane Wrath — investigated
  [below](#arcane-wrath-a-node-no-gate-counts-section_not_gate_counted-160170425). The manifest re-validates.
* Real DB2, tooltip-table and enUS/ruRU fixtures of 70450 are committed
  (`tests/fixtures/{db2,tooltips,localization}/forever/1.60.1.70450/`);
  `tests/unit/test_product_build.py` regenerates the documents, icon sidecars, descriptions
  and all nine ruRU overlays from them byte for byte, and pins the Mage anomaly. The 70374
  tests moved unchanged to `tests/unit/test_build_70374.py`.

| Class | Tree | Nodes | Prereq links | Total ranks | Sections | Point budget | Errors | Warnings |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Warrior | 1117 | 52 | 8 | 150 | 3 | 51 | 0 | — |
| Paladin | 1100 | 50 | 7 | 138 | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Hunter | 1091 | 52 | 9 | 158 | 3 | 51 | 0 | 2 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY, 1 × SUFFICIENT_PREREQUISITE_CYCLE |
| Rogue | 1111 | 53 | 8 | 139 | 3 | 51 | 0 | 1 × INCOMPLETE_RANK_CURVE, 1 × UNRESOLVED_CONDITION |
| Priest | 1114 | **53** | 8 | 153 | 3 | 51 | 0 | 1 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY |
| Shaman | 1082 | 50 | 7 | 148 | 3 | 51 | 0 | — |
| Mage | 1112 | **55** | **7** | **148** | 3 | 51 | 0 | **1 × SECTION_NOT_GATE_COUNTED** |
| Warlock | 1116 | 52 | 8 | **146** | 3 | 51 | 0 | 2 × OFF_GRID_NODE |
| Druid | 1089 | 52 | 11 | 148 | 3 | 51 | 0 | — |

Tree ids, the 51-point budget, rank costs (1 point of currency 3820) and the gate rule are
unchanged.

### What changed 70374 → 70450

`artifacts/diff/forever-all-1.60.1.70374-1.60.1.70450.json` (+ one file per class):
**1 added, 1 removed, 65 changed nodes**.

* **Mage** (the Arcane layout):
  * added: **Arcane Wrath** 113627 (max 1) at row 3, column 3 of the Arcane tab —
    Missile Barrage's former cell — with a sufficient prerequisite on Arcane Blast and **no
    points gate** ([below](#arcane-wrath-a-node-no-gate-counts-section_not_gate_counted-160170425));
  * **Missile Barrage** 105802 moved row 3 → row 0, lost its 15-point gate, max rank 1 → 2
    (20/40 % now resolved per rank); the 5/10/15-point Arcane gates count it (3 → 4, 7 → 8,
    10 → 11 nodes);
  * **Fire Power** max 5 → 3 (1/2/3 %, effect #1 no longer per-rank); **Critical Mass**
    max 3 → 5; **Wake of Fire** renamed **Improved Fire Blast** (8/15 %); Winter's Chill
    1/2/3/4 → 5/5/5/5; Frost Channeling #1 sign flip; Ice Lance, Arcane Blast base points;
    descriptions of Winter's Chill, Arctic Reach, Improved Frostbolt, Fire Power, Heating Up,
    Improved Fire Blast, Missile Barrage, Arcane Blast, Improved Channeling.
* **Priest**: removed **Improved Mind Blast** 105827 (Shadow, max 5); node **105831** is now
  **Improved Mind Blast** (spell 15273, max 3 → 5, −0.5…−2.5 s Mind Blast cooldown; was
  Shadow Affinity 15318); node **105823** is now **Dark Inspiration** (spell 1326159, max 2 →
  5, party Spirit 3…15 %; was Improved Fade 15274); **Mental Strength** row 3 → row 1
  (gate 15 → 5, 3/6/9/12/15 → 2/4/6/8/10); **Improved Power Word: Shield** row 1 → row 2
  (gate 5 → 10); **Improved Mana Burn** column 3 → 1; Devouring Contagion and Divine Aegis
  gained an effect; Improved Mind Flay lost three; Shadow Weaving, Silent Resolve, Power in
  Light texts.
* **Shaman** (Elemental re-laid out): spells swapped between nodes — 104729/104735
  (Totemic Focus ⇄ Tidal Focus), 104765/104771 (Elemental Alacrity ⇄ Elemental Warding),
  104758 Lava Burst → Elemental Mastery, 104760 Earthbound → Lava Burst, 104762 Call of
  Thunder → Earthbound; **Improved Fire Nova** renamed **Trial by Fire**; positions of
  Lightning Overload, Lava Burst, Earthbound, Elemental Warding, Elemental Focus, Elemental
  Devastation, Call of Flame, Elemental Alacrity; prerequisites: Elemental Mastery requires
  Lava Burst (was Lightning Overload), Lightning Overload requires Elemental Alacrity (new),
  Earthbound none (was Elemental Alacrity), Elemental Fury requires Lightning Overload (was
  Call of Thunder); twelve description changes.
* **Warlock**: node **105923** is now **Grim Reach** (spell 18218, max 5 → 2, 10/20; was
  Malediction 1225177); **Shadow Mastery** 1…5 → 2…10 % (both effects); Demonic Embrace and
  Amplify Curse lost an effect; Destructive Reach base points and text.
* **Paladin**: Champion of the Light 20/40/60 → 10/20/30; Improved Seals base points;
  Reckoning text. **Hunter**: Improved Mend Pet #1 sign flip (10/20 → −10/−20),
  Resourcefulness #1 −20/−40 → 50/100; five texts. **Warrior**: Bloodthrill text.
  **Druid**: Moonkin Form, Leader of the Pack, Furor texts.
* **Rogue**: no change.

A 70374 build can therefore be invalid on 70450 (Priest Improved Mind Blast 5/5 names a node
that no longer exists) or validate while meaning another talent (Priest 105823 at 2/2:
Improved Fade on 70374, Dark Inspiration on 70450). A saved build always opens on its own
build and is never migrated (`test_a_previous_product_build_*`,
`DatasetVersionCompatibilityTest`).

### Arcane Wrath: a node no gate counts (SECTION_NOT_GATE_COUNTED, 1.60.1.70425+)

**Cause (client rows, `tests/fixtures/db2/forever/1.60.1.70450/mage/`).** The normalizer
derives the three classic tabs from the node groups the tier gates count (tab names do not
exist in the trait tables). Arcane Wrath 113627 has four `TraitNodeGroupXTraitNode` rows —
groups 12862, 12863, 12864, 12865 (`_Index` 2, 12, 12, 12) — and **each of these groups has
no other member and no condition**: no `TraitCond`, `TraitNodeGroupXTraitCond` or
`TraitNodeXTraitCond` row refers to them or to the node. Every other Mage node keeps its
70374 memberships (`TraitCond` and `TraitNodeGroupXTraitCond` are byte-identical to 70374's),
so the three maximal gate groups (18 Arcane, 17 Fire, 19 Frost nodes) are disjoint and cover
every node except Arcane Wrath. A row-3 peer such as Arcane Meditation 105803 is in four
groups too — its row group 11586 and the cumulative 30/25/20-point groups
11582/12730/12731, `_Index` 3/13/13/13 — the same shape as Arcane Wrath's rows, but with the
shared group ids. Whether Arcane Wrath's rows are an authoring slip or intended cannot be
decided from DB2 rows, and no 70425/70450 client was observed.

**Tab: by its column (owner decision, 2026-10-17).** Arcane Wrath stands in the Arcane
columns (x = 2820; the Arcane group spans x 1020…2820, Fire 5020…6820, Frost 9080…10880), the
panel the client draws it in, and Wowhead's Forever calculator — datamined from the same
tables (spell 1326079, definition 145958) — lists it in the Arcane tree. The normalizer
therefore resolves tabs as before and then places **a node that no gate counts** in the
**one** tab whose column range contains its raw x
(`normalization.normalizer._resolve_sections`). The rule is fail-closed: an x inside no
tab's range or inside more than one leaves the tree without sections
(`SECTIONS_UNRESOLVED`), and nothing else (prerequisites, names) is consulted. It changes no
earlier build (every node of 70009…70374 is counted by a gate; their documents regenerate
byte for byte). The validator flags every such node as **`SECTION_NOT_GATE_COUNTED`** — a
new, investigated warning code — so the placement is never mistaken for a gate fact.

**Gates stay literal.** Arcane Wrath has no unlock requirement (only its sufficient
prerequisite Arcane Blast, itself behind the 10-point Arcane gate), and its point counts
toward no Arcane gate; the engines read exactly that. Wowhead shows a 15-point requirement
for it, but Wowhead's `requiredPoints` equals row × 5 for all 466 nodes it lists — the
classic convention, not the client's condition rows (every other node agrees with the
client gates) — so it is no evidence for a gate, and none is added. One visible consequence
in the app: the Arcane tab's point counter sums the tab's nodes, so after taking Arcane Wrath
it is one point above what the Arcane gates count.

**Old classic tables.** The Forever client still ships `TalentTab` (Arcane 81 / Fire 41 /
Frost 61, with names) and `Talent` (432 rows), but `Talent` is the classic layout: it covers
351 of the 469 70450 nodes, none of the Forever-new talents, and places some kept ones
differently, so it is no evidence for Arcane Wrath's tab either.

### Allocation compatibility

`python scripts/allocation_inventory.py --build 1.60.1.70450` (committed fixtures) differs
from 70374 only in Priest (53 nodes, `Flags` 0×48/8×5, `MaxRanks` 1×12/2×12/3×14/5×15), Mage
(55 nodes, `MaxRanks` 2×15, `Edge.Type` 2×7) and Warlock (`MaxRanks` 2×12/5×13): **no new
node type, entry type, node flag, edge type, condition shape, currency or cost link**; the
number of conditions per tree is unchanged. A node without a points gate is not new either
(every row-0 node has none). The Python reference engine flags exactly one node as
unsupported (Rogue Venom, cond 50984), the random-walk invariants pass on all nine 70450
classes, the M2 vectors (pinned to 70009) replay 53/53, and `ProductDatasetContractTest`
pins the Kotlin engine to the same answer. **The allocation engine needs no change for
70450**; no confidence label changes (EDGE-ACTIVE / EDGE-SUF remain hypotheses).

### Icons, descriptions and Russian text

* Visuals: 469 / 469 entries resolved from 70450's own `TraitDefinition` → `SpellMisc` rows;
  **359** icon files fetched from 70450 (new: 136127, 236222, 463284; no longer used: 136014,
  136137). The 356 shared client files are byte-identical to 70374's (same `sourceSha256`)
  and decode to identical pixels, but **their PNG bytes differ**: Pillow 12.3.0's macOS arm64
  wheel links zlib-ng (`PIL.features.version("zlib")` = `1.3.1.zlib-ng`), and the compressed
  stream depends on that build — see [talent-visuals.md](talent-visuals.md). The 70374 PNGs
  stay as committed.
* Descriptions: 1328 ranks — **1272 resolved, 55 partial, 1 unresolved** (Druid Shifting
  Power `$c`, unchanged) — `docs/research/forever-1.60.1.70450-tooltip-coverage.md`. Status
  changes against 70374 (explicit gaps, never guessed): newly partial Paladin Reckoning,
  Priest Divine Aegis, Shaman Lava Burst, Warlock Amplify Curse and the new Mage Arcane
  Wrath; newly resolved Hunter Resourcefulness and Shaman Elemental Mastery.
* ruRU: the 70450 exports pass every source check of `talent_data.localization.source`;
  coverage **469 / 469 names** translated (70374: 459), **383 / 469 descriptions**
  (1130 / 1328 ranks; 70374: 355, 1058 / 1329) —
  `docs/research/forever-1.60.1.70450-ruRU-coverage.md`. All nine overlays are committed,
  linked to the 70450 dataset and tooltip hashes, and bundled.

### Validity boundaries

* Valid for WoW: Forever **1.60.1.70450** (and, by identical talent data, 70425) as published
  by wago.tools; nothing carries over to another build without re-running the pipeline.
* Not established: that 70450 is live on the realms, what the `wowf` product carries at
  launch, whether the Arcane Wrath memberships are intended, and every semantic still listed
  as unresolved below.

## Product dataset 1.60.1.70374 (M5.DATA.3)

### Build selection

Verified on **2026-10-14 at 16:50 UTC** with the repository's own check and against the
raw index (`GET https://wago.tools/api/builds`, response `Date: Wed, 14 Oct 2026 16:50:54
GMT`; the M5.DATA.3 subset is committed as `tests/fixtures/wago/builds-2026-10-14.json`):

```text
$ python scripts/check_product_freshness.py --output freshness.json      # exit 3
OUTDATED: 1.60.1.70374 is publishable (is_bgdl=false) and newer than the product build 1.60.1.70338
```

| Fact | Value |
| --- | --- |
| `PRODUCT_BUNDLE` before this milestone | `1.60.1.70338` |
| Latest detected Forever build | `1.60.1.70374` (`wow_classic_beta`, created 2026-10-13 23:25:05) |
| Latest publishable Forever build | `1.60.1.70374` |
| `is_bgdl` of 70374 on the wire | JSON `false` (a strict boolean, not `"false"`/`0`) |
| Newer non-publishable builds | none |
| Source / checked at | wago.tools `/api/builds`, `checkedAt` 2026-10-14T16:50:45Z; re-checked 2026-10-14T18:57:39Z (70338 `OUTDATED`) and 18:57:26Z (70374 `CURRENT`), index unchanged |

70374 is product `wow_classic_beta`, branch `1.60` (`build_config
25822068f8fce8b21bc714e7e3210481`, `cdn_config dffb7f6337496ac02a54d2d5191ccc12`, same
`product_config` as 70338); `wow_cn_beta` lists 70374 too (2026-10-13 23:59:08, `is_bgdl=false`).
No build newer than 70374 existed on either track at the check, so **70374 is selected**.

**New in the index: product `wowf`.** It appeared with exactly one entry, `1.60.1.70245`
(created 2026-10-13 21:18:10, `is_bgdl=false`), i.e. an *older* build than the beta. The
product code suggests a dedicated Forever product ahead of the announced 4 November 2026
launch, but nothing in the repository proves what it is; `GameConfig.products` stays
`("wow_classic_beta",)`, so the freshness check does not consult it
(`test_the_wowf_product_is_outside_the_game_s_products`). This is a recorded monitoring
gap, not a decision: if `wowf` becomes the live product, its build — possibly older than the
beta — is the relevant one, and the product mapping must be revisited with evidence
(see [dataset-freshness-watch.md](dataset-freshness-watch.md#known-gap-the-wowf-product)).

**Basis and its limit** (unchanged): 70374 is the newest Forever build whose `is_bgdl` is
explicitly `false` — the project's definition of *publishable*. It is not evidence that the
realms run 70374, and no 70374 client was observed.

### Generation and integrity

Every artifact is generated from 70374's own client rows with the unchanged pipeline;
nothing is copied from 70338, nothing hand-edited, no check relaxed:

```bash
python -m talent_data fetch --game forever --build 1.60.1.70374
python -m talent_data normalize --game forever --build 1.60.1.70374 --class all
python -m talent_data diff --game forever --class all --from 1.60.1.70338 --to 1.60.1.70374
python -m talent_data manifest data/normalized/forever/1.60.1.70374/manifest.json --expect-complete
python -m talent_data visuals fetch|report|normalize|check --build 1.60.1.70374
python -m talent_data tooltips fetch|normalize|check --build 1.60.1.70374
python -m talent_data localization fetch|report|normalize|check --build 1.60.1.70374 --locale ruRU
```

* 9 classes, 469 nodes, **0 errors, the same 12 warnings**; per-class shape identical to the
  70338 table below (tree ids, nodes, links, ranks, 51-point budget).
* Real DB2, tooltip-table and enUS/ruRU fixtures of 70374 are committed
  (`tests/fixtures/{db2,tooltips,localization}/forever/1.60.1.70374/`);
  `tests/unit/test_product_build.py` regenerates the documents, icon sidecars, descriptions
  and the Warrior ruRU overlay from them byte for byte. The 70338 tests moved unchanged to
  `tests/unit/test_build_70338.py`.

### What changed 70338 → 70374: nothing in the talent data

`artifacts/diff/forever-all-1.60.1.70338-1.60.1.70374.json`: **0 added, 0 removed,
0 changed** in every class. Because an empty diff can also hide a pipeline that read the
wrong build, it was investigated rather than accepted:

* **Source level.** All 388 source files the pipelines read for 70374 — the 23 trait tables
  plus `ChrClasses`/`Spell*`/`Curve*` (DB2), the tooltip tables, `SpellMisc`, the four ruRU
  and enUS localization tables, and all 358 CASC icon files — were downloaded for 70374 and
  are **byte-identical** to the 70338 downloads. Only `_table_status.json` differs, because
  its evidence names the build it was proven on.
* **The source honours the build.** The same request for 70245 (`TraitNode`, 560 lines,
  sha256 `d7fe3ae7…`) differs from 70338/70374 (559 lines, `263f9df9…`), and an unknown
  build (`1.60.1.99999`) is refused with HTTP 400 — the identical bytes are 70374's answer,
  not a fallback.
* **Document level.** Each 70374 document equals its 70338 counterpart in class identity,
  trees, nodes, entries, definitions, spells, ranks, gates, prerequisites, sections and
  layout (`test_same_talent_rows_but_a_distinct_dataset_identity`); only `clientBuild`,
  `generatedAt` and `contentHash` differ. The allocation inventory
  (`scripts/allocation_inventory.py`) is identical, no new mechanic, Rogue Venom still the
  only unsupported node, the M2 vectors (pinned to 70009) replay 53/53, and no confidence
  label changes (EDGE-ACTIVE / EDGE-SUF remain hypotheses).
* **Interpretation.** 70374 is a client build without talent-data changes. It is
  nevertheless published as its own dataset: a build saved on 70338 keeps opening on 70338,
  a new build is created on 70374, and the two datasets can never stand in for each other
  (different `clientBuild` and hashes; `ProductDatasetContractTest` proves that 70338's
  identical sidecars fail with a typed `BuildMismatch` for 70374).

### Icons, descriptions and Russian text

* Visuals: 469 / 469 entries resolved from 70374's own `TraitDefinition` → `SpellMisc` rows;
  358 icon files fetched from 70374 and converted — the PNGs are byte-identical to 70338's.
* Descriptions: 1329 ranks — **1281 resolved, 47 partial, 1 unresolved** (Druid Shifting
  Power `$c`), identical statuses to 70338 —
  `docs/research/forever-1.60.1.70374-tooltip-coverage.md`.
* ruRU: the 70374 exports pass every source check of `talent_data.localization.source`
  (ruRU applied, same columns, ids and non-text columns as enUS, Cyrillic present); coverage
  459 / 469 names, 355 / 469 descriptions (1058 / 1329 ranks), identical to 70338 —
  `docs/research/forever-1.60.1.70374-ruRU-coverage.md`. The Warrior pilot overlay of 70374
  is committed and linked to the 70374 dataset and tooltip hashes, so M5.REL.3b.1 can generate
  the product-build overlays directly (`docs/talent-localization.md` §11).

### Validity boundaries

* Valid for WoW: Forever **1.60.1.70374** as published by wago.tools; nothing carries over to
  another build without re-running the pipeline.
* Not established: that 70374 is live on the realms, what the `wowf` product carries at
  launch, and every semantic still listed as unresolved below.

## Product dataset 1.60.1.70338 (M5.DATA.2)

### Build selection

`python -m talent_data builds latest --game forever` on **2026-10-10** (17:28 UTC) printed
`Forever latest build: 1.60.1.70338 … not a background-download build ('latest' != proven
live)`, cross-checked against the raw `GET https://wago.tools/api/builds` entries:

| version | created_at (wago) | `is_bgdl` on the wire | note |
| --- | --- | --- | --- |
| **1.60.1.70338** | 2026-10-10 05:05:05 | `false` | latest detected = latest publishable → **selected** |
| 1.60.1.70334 | 2026-10-09 22:49:04 | `false` | the build external sources reported; byte-identical data |
| 1.60.1.70291 | 2026-10-08 20:25:12 | `false` | brought every talent change below |
| 1.60.1.70245 | 2026-10-06 23:02:04 | `false` | product dataset of M5.DATA, kept |

All four are product `wow_classic_beta`, branch `1.60` (70338 `build_config
f6e309c700cea095978aeb5d85210df4`, `cdn_config ad00f88bc136373cdfe57c6bb796516b`). The only
other product listing `1.60.1` builds is `wow_cn_beta` (70338 at 2026-10-10 04:54:10, 70334,
70245 — all `is_bgdl=false`); no other track of branch 1.60 exists on wago.tools.

70291, 70334 and 70338 were all fetched and normalized with the same pipeline: their talent
data is identical (`diff` 70291 → 70334 → 70338: 0 added, 0 removed, 0 changed), and 70334 and
70338 are byte-identical in every table the pipeline reads (the 23 trait tables, the tooltip
tables, `SpellMisc`) and in all 358 icon files. Only 70338 is committed.

**Basis and its limit** (unchanged from M5.DATA): 70338 is the newest Forever build whose
`is_bgdl` is *explicitly* `false` — the project's definition of *publishable*. It is **not**
evidence that 70338 is the build the live realms run, and no 70338 client was observed.

### Generation and integrity

Same unchanged pipeline and validators, nothing hand-edited, no check relaxed:

```bash
python -m talent_data fetch --game forever --build 1.60.1.70338
python -m talent_data normalize --game forever --build 1.60.1.70338 --class all
python -m talent_data diff --game forever --class all --from 1.60.1.70245 --to 1.60.1.70338
python -m talent_data manifest data/normalized/forever/1.60.1.70338/manifest.json --expect-complete
python -m talent_data visuals fetch|normalize|check --build 1.60.1.70338
python -m talent_data tooltips fetch|normalize|check --build 1.60.1.70338
```

* 9 classes, **0 errors, the same 12 warnings** (same codes, nodes and counts as 70009 and
  70245); the manifest re-validates.
* Real DB2 and tooltip-table fixtures of 70338 are committed
  (`tests/fixtures/{db2,tooltips}/forever/1.60.1.70338/`); `tests/unit/test_product_build.py`
  regenerates the committed documents, visuals resolutions, tooltips and coverage report
  from them and pins the facts below. The former 70245 product tests continue unchanged in
  `tests/unit/test_build_70245.py`.

| Class | Tree | Nodes | Prereq links | Total ranks | Point budget | Errors | Warnings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Warrior | 1117 | **52** | 8 | **150** | 51 | 0 | — |
| Paladin | 1100 | 50 | 7 | 138 | 51 | 0 | 2 × OFF_GRID_NODE |
| Hunter | 1091 | 52 | 9 | 158 | 51 | 0 | 2 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY, 1 × SUFFICIENT_PREREQUISITE_CYCLE |
| Rogue | 1111 | 53 | 8 | 139 | 51 | 0 | 1 × INCOMPLETE_RANK_CURVE, 1 × UNRESOLVED_CONDITION |
| Priest | 1114 | 54 | 8 | 153 | 51 | 0 | 1 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY |
| Shaman | 1082 | 50 | 7 | 148 | 51 | 0 | — |
| Mage | 1112 | 54 | 6 | 146 | 51 | 0 | — |
| Warlock | 1116 | 52 | 8 | 149 | 51 | 0 | 2 × OFF_GRID_NODE |
| Druid | 1089 | 52 | 11 | 148 | 51 | 0 | — |

Total 469 nodes (70245: 470). Tree ids, sections (3 per class), the 51-point budget, rank
costs (1 point of currency 3820) and the gate rule are unchanged.

### What changed 70245 → 70338

`artifacts/diff/forever-all-1.60.1.70245-1.60.1.70338.json` (+ one file per class):
**2 added, 3 removed, 36 changed nodes** — 29 of the changes are the Warrior rework.

* **Warrior** (tree 1117; sections: 0 Arms, 1 Fury, 2 Protection as laid out by the client):
  * removed: **Precision** 105929 (Fury, max 3), **Improved Cleave** 105936 (Fury, max 3),
    **Toughness** 105973 (Protection, max 5);
  * added: **Gore Drinker** 113569 (Fury, row 5, max 2, requires Enrage) and **Iron Will**
    113570 (Protection, row 0, max 5) — Iron Will's spell 12962 moved here from node 110857;
  * same node id, different talent: **105953** Boundless Rage (spell 1310236, +max Rage) →
    **Furious Precision** (spell 1323963, off-hand hit 4/7/10 %), moved row 2 column 3 →
    column 0; **110857** Iron Will → **Lingering Rage** (spell 1323964, rage decay delayed
    2…10 s). Both keep their max rank, so an old build that spent them still *validates* on
    70338 while meaning something else;
  * prerequisites: **Flurry** requires Death Wish instead of Enrage (and moved column 2 → 1);
    **Bloodthirst** no longer requires Death Wish; **Impale** now requires Deep Wounds;
    **Last Stand** no longer requires Improved Bloodrage;
  * Protection re-laid out (row/column): Improved Bloodrage 1/0 → 0/0 (no gate),
    Anticipation 0/2 → 1/0 (5-point gate), Improved Revenge 2/2 → 1/2 (gate 10 → 5),
    Improved Disarm 3/1 → 2/2 (gate 15 → 10), Vanguard 3/2 → 3/1, Improved Shield Bash
    4/2 → 3/2 (gate 20 → 15), Focused Rage 5/2 → 4/2 (gate 25 → 20), Bastion 4/3 → 5/2
    (gate 20 → 25); Improved Berserker Rage (Fury) 5/0 → 4/0 (gate 25 → 20); every other
    gate's counted node set follows the removed/added nodes;
  * values and texts: Dual Wield Specialization (off-hand damage 10…50 % and off-hand Rage),
    Bloodthirst base points 35 → 45, Raging Blows (Cleave and Whirlwind cost −3), Booming
    Voice (+ Rage cost reduction effect), Blood Craze, Unbridled Wrath, Piercing Howl,
    Spearing Strike texts; Improved Slam lost the per-rank values of effect #2.
* **Druid**: Predatory Instincts renamed **Natural Instinct** and gained an effect (spell
  healing by 12/25 % of Intellect); Furor text.
* **Paladin**: Reckoning text (once every 1.5 sec). **Hunter**: Expose Prey duration
  reference. **Shaman**: Water Shield gained an effect without per-rank values.
  **Warlock**: Destructive Reach effect #1 now resolved (10/20); Demonic Brand text.
* **Rogue, Priest, Mage**: no change.

Because a node id can be removed (Toughness 5/5 is a valid 70245 build and an invalid 70338
one) or keep validating while meaning another talent (Boundless Rage 3/3 on 105953), a saved
build is always opened on its own build and never migrated — see
`docs/mobile-architecture.md`.

### Allocation compatibility

`python scripts/allocation_inventory.py --build 1.60.1.70338` (committed fixtures) is
identical to 70245 except the Warrior row (52 nodes, `Flags` 0×47/8×5, `MaxRanks`
1×12/2×12/3×13/5×15, `Edge.Type` 2×8): **no new node type, entry type, node flag, edge type,
condition shape, currency or cost link** — flag 8 (TestGridPositioned) is already supported.
The Python reference engine flags exactly one node as unsupported (Rogue Venom, cond 50984,
as before), the random-walk invariants pass on all nine 70338 classes, and
`ProductDatasetContractTest` pins the Kotlin engine to the same answer. No rule was changed
for this build; the M2 conformance vectors stay pinned to 70009 and no confidence label
changes (EDGE-ACTIVE / EDGE-SUF remain hypotheses). An unsupported mechanic in a future
build fails these tests and blocks its publication.

### Icons and descriptions

* Visuals: 469 / 469 entries resolved from 70338's own `TraitDefinition` → `SpellMisc` rows;
  358 distinct icons fetched as 70338 client files (new compared with 70245: 132356, 135727,
  236309; no longer used: 132338, 236310; the 355 shared ones convert to identical PNGs).
* Descriptions: 1329 ranks — **1281 resolved, 47 partial, 1 unresolved** —
  `docs/research/forever-1.60.1.70338-tooltip-coverage.md`. The unresolved rank is still Druid
  Shifting Power (`$c`). New partial ranks, shown with explicit gaps, never guessed: Druid
  Thick Hide ranks 1–3 (`$s2` now scales with the caster's level) and Warrior Piercing Howl
  (`$a2`, a radius reference).

### Validity boundaries

* Valid for WoW: Forever **1.60.1.70338** as published by wago.tools (and, by the byte
  identity above, 70334); nothing carries over to another build without re-running the
  pipeline.
* Not established: that 70338 is live on the realms, that the Warrior rework is final, and
  every semantic still listed as unresolved below.

## Product freshness check (M5.DATA.2)

```bash
python scripts/check_product_freshness.py [--output freshness.json]
# = python -m talent_data builds freshness --game forever --product-build <PRODUCT_BUNDLE>
```

The script reads `PRODUCT_BUNDLE` strictly from the app's `BundledDatasets.kt` and compares
it with the latest publishable build wago.tools lists (`latest_publishable_build`, below).
It prints one `product-freshness` v1 JSON document (`status`, `productBuild`,
`latestPublishable`, `latestDetected`, `newerUnpublishable`, `reason`, `source`,
`checkedAt`) and exits with the status:

| status | exit | meaning |
| --- | --- | --- |
| `CURRENT` | 0 | the product build is the latest publishable build (newer background-download builds are listed in `newerUnpublishable`, not counted as updates) |
| `OUTDATED` | 3 | a newer build with `is_bgdl` explicitly `false` exists: refresh the product dataset |
| `UNKNOWN` | 4 | not established: network / HTTP / schema failure, no publishable build, a newer build without `is_bgdl`, or a product build newer than anything publishable |

A failed lookup is never read as "no update". The check only reads: it fetches no data
into the repository and publishes, commits or merges nothing. Since M5.DATA.WATCH it runs
daily in `.github/workflows/dataset-freshness.yml`, which reports through one tracked
GitHub Issue and is the mandatory release preflight
([`docs/dataset-freshness-watch.md`](dataset-freshness-watch.md)). Since M5.DATA.2 a non-boolean `is_bgdl` on the wire
(`"false"`, `0`) is an unexpected schema, not a publishability answer (it used to be coerced
by the lax pydantic `bool`). On 2026-10-10 it answered `OUTDATED` for 70245 and `CURRENT`
for 70338; on 2026-10-14 `OUTDATED` for 70338 (by 70374), and `CURRENT` for 70374 after
`PRODUCT_BUNDLE` moved (M5.DATA.3, checked 17:28 UTC); on 2026-10-17 `OUTDATED` for 70374
(by 70425, then 70450), and `CURRENT` for 70450 after `PRODUCT_BUNDLE` moved (M5.DATA.4).

## Product dataset 1.60.1.70245 (M5.DATA)

Since M5.DATA.2 70245 is no longer the product dataset; it stays committed and bundled,
unchanged, so that builds saved on it keep opening on it. Its facts below are as recorded
by M5.DATA.

### Build selection

`python -m talent_data builds list --game forever` / `builds latest` on **2026-10-08**
(≈17:50 UTC), cross-checked against the raw `GET https://wago.tools/api/builds` entries:

| version | created_at (wago) | `is_bgdl` on the wire | note |
| --- | --- | --- | --- |
| **1.60.1.70245** | 2026-10-06 23:02:04 | `false` | latest detected = latest publishable → **selected** |
| 1.60.1.70235 | 2026-10-05 23:37:04 | `false` | |
| 1.60.1.70205 | 2026-10-03 01:58:05 | `false` | |
| 1.60.1.70170 | 2026-10-01 22:54:04 | `false` | |
| 1.60.1.70124 | 2026-09-30 01:57:04 | `false` | |
| 1.60.1.70058 | 2026-09-29 16:41:04 | `false` | |
| 1.60.1.70009 | 2026-09-24 22:02:03 | `false` | reference baseline |
| 1.60.1.69977 … 69876 | 2026-09-23 … 09-16 | `false` | historical |

All entries are product `wow_classic_beta`, branch `1.60`. `builds latest` printed
`Forever latest build: 1.60.1.70245 … not a background-download build ('latest' != proven
live)`. 70245 (`build_config 0bf141260c698dfc9cb3b5b7947b78c3`, `cdn_config
f92adcaab18113166c331aa070d5b7e7`) had been seen before M5.DATA; its status was checked
again on 2026-10-08 and is unchanged. The same version is also listed under `wow_cn_beta`
(2026-10-06 23:15:07, `is_bgdl=false`).

**Basis and its limit.** 70245 is the newest Forever build whose `is_bgdl` is *explicitly*
`false` — the project's definition of *publishable* (necessary condition: not a
background download). It is **not** evidence that 70245 is the build the live realms run:
wago.tools has no such field, and no client of 70245 was observed for this milestone (the
live-client evidence of `talent-allocation-semantics.md` §15 is for 70009). M5.DATA also
fixed a fail-open default: an `/api/builds` entry *without* `is_bgdl` used to be read as
`false`; it is now *unknown* and therefore not publishable.

### Generation and integrity

Same unchanged pipeline and validators as 70009 — nothing hand-edited, no check relaxed:

```bash
python -m talent_data fetch --game forever --build 1.60.1.70245
python -m talent_data normalize --game forever --build 1.60.1.70245 --class all
python -m talent_data diff --game forever --class all --from 1.60.1.70009 --to 1.60.1.70245
python -m talent_data manifest data/normalized/forever/1.60.1.70245/manifest.json
python -m talent_data visuals fetch|normalize|check --build 1.60.1.70245
python -m talent_data tooltips fetch|normalize|check --build 1.60.1.70245
```

* 9 classes, **0 errors, the same 12 warnings** (same codes, nodes and counts per class as
  70009, see the investigations above); the cost/table-absence proofs hold; the manifest
  re-validates (identity, inventory, content hashes, warning counts).
* Re-normalizing 70009 from a fresh download reproduces every committed 70009 content
  hash (only `generatedAt` differs) — the reference data is untouched.
* Real DB2 and tooltip-table fixtures of 70245 are committed
  (`tests/fixtures/{db2,tooltips}/forever/1.60.1.70245/`);
  `tests/unit/test_build_70245.py` (`test_product_build.py` until M5.DATA.2) regenerates the committed documents, visuals
  resolutions, tooltips and coverage report from them and pins the facts below.

| Class | Tree | Nodes | Prereq links | Total ranks | Point budget | Errors | Warnings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Warrior | 1117 | 53 | 8 | 154 | 51 | 0 | — |
| Paladin | 1100 | 50 | 7 | 138 | 51 | 0 | 2 × OFF_GRID_NODE |
| Hunter | 1091 | 52 | 9 | 158 | 51 | 0 | 2 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY, 1 × SUFFICIENT_PREREQUISITE_CYCLE |
| Rogue | 1111 | 53 | 8 | 139 | 51 | 0 | 1 × INCOMPLETE_RANK_CURVE, 1 × UNRESOLVED_CONDITION |
| Priest | 1114 | 54 | 8 | 153 | 51 | 0 | 1 × OFF_GRID_NODE, 1 × DUPLICATE_SPELL_IDENTITY |
| Shaman | 1082 | 50 | 7 | 148 | 51 | 0 | — |
| Mage | 1112 | 54 | 6 | 146 | 51 | 0 | — |
| Warlock | 1116 | 52 | 8 | 149 | 51 | 0 | 2 × OFF_GRID_NODE |
| Druid | 1089 | **52** | **11** | 148 | 51 | 0 | — |

Total 470 nodes (70009: 469). Tree ids, sections (3 per class) and the 51-point budget are
unchanged.

### What changed 70009 → 70245

`artifacts/diff/forever-all-1.60.1.70009-1.60.1.70245.json` (+ one file per class):
**1 added, 0 removed, 36 changed nodes** (28 description changes, 21 rank-value changes).
User-visible changes:

* **Druid** (Feral tab reworked): new node **113563 Improved Shifting Power** (max rank 2,
  row 4, requires Shifting Power, 20-point gate). Node **104951** is **King of the Jungle**
  (spell 417046, max rank 3) on 70009 and **Shifting Power** (spell 1322605) on 70245:
  same node id, different talent, **max rank 3 → 1**,
  moved row 4 → row 3, gate 20 → 15 points, gained a prerequisite on Shredding Attacks.
  Shredding Attacks moved row 3 → row 2 (gate 15 → 10); Predatory Instincts moved column 0
  → 3; the gates of Blood Frenzy, Primal Bite, Predatory Instincts, Predatory Strikes and
  Leader of the Pack now count the moved nodes; Moonkin Form / Primal Bite texts reworded.
* **Paladin**: Redoubt 6/12/18/24/30 → 4/8/12/16/20; Champion of the Light 33/66/100 →
  20/40/60 (one effect removed); Holy Shield base points 20 → 30; six description changes.
* **Hunter**: Deflection 2/4/6/8/10 → 1/2/3/4/5; Sniper Shot gained an effect; five
  description changes.
* **Warrior**: Dual Wield Specialization effect #1 20…100 → 2…10 (and base points);
  Spearing Strike, Raging Blows texts.
* **Mage**: Hot Streak renamed **Heating Up**. **Warlock**: Soul Harvesting renamed **Soul
  Harvest**; Wrack text. **Priest**: four, **Rogue**: two description changes.
* **Shaman**: no change.

Rank costs (1 point of currency 3820), point budgets and the gate rule are unchanged.
Because the same node id can mean something else in the two builds (node 104951 at 3/3 —
King of the Jungle — is a valid 70009 build and an invalid 70245 one, where 104951 is
Shifting Power with max rank 1), a saved build is always opened on its own
build and never migrated — see `docs/mobile-architecture.md`.

### Allocation compatibility

`python scripts/allocation_inventory.py --build 1.60.1.70245` (committed fixtures) is
identical to 70009 except Druid (52 nodes, `Flags` 0×50/8×2, `Edge.Type` 0×1/2×10/3×1):
**no new node type, entry type, node flag, edge type, condition shape, currency or cost
link** — only mechanics the engine already supports (§3 of
`talent-allocation-semantics.md`). The Python reference engine flags exactly one node as
unsupported (Rogue Venom, cond 50984, as on 70009) and the random-walk invariants pass on
all nine 70245 classes; `ProductDatasetContractTest` pins the Kotlin engine to the same
answer. The M2 conformance vectors stay pinned to 70009 and replay unchanged
(53/53); no confidence label changes (EDGE-ACTIVE / EDGE-SUF remain hypotheses).

### Icons and descriptions

* Visuals: 470 / 470 entries resolved from 70245's own `TraitDefinition` → `SpellMisc`
  rows; 357 distinct icons fetched as 70245 client files (3 new FileDataIDs, 4 no longer
  used; the 354 shared ones convert to identical PNGs). Nothing is copied from 70009.
* Descriptions: 1333 ranks — **1289 resolved, 43 partial, 1 unresolved** —
  `docs/research/forever-1.60.1.70245-tooltip-coverage.md`. The unresolved rank is the new
  Shifting Power text: `$c` (its mana cost) is outside the renderer's grammar and stays an
  explicit gap; the app shows the neutral "unavailable" line, never a guessed number.

### Validity boundaries

* Valid for WoW: Forever **1.60.1.70245** as published by wago.tools; nothing here
  carries over to another build without re-running the pipeline.
* Not established: that 70245 is live on the realms, that a newer build will not change
  the Druid rework again, and every semantic still listed as unresolved below.
* The tooltip template inventory (`docs/research/forever-tooltip-template-inventory.md`)
  remains the 70009 research artifact; 70245's single new construct (`$c`) is recorded by
  its coverage report.

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
a failing batch writes nothing (no partial datasets). Reading one back is never a matter
of trust: `pipeline.validate_manifest_against_documents` (used by both `diff --class
all` reuse and the `manifest` command) re-checks every invariant — game and build
identity, unique class tokens and file names, the complete class inventory of the game
(from `GameConfig.dataset_class_tokens`, not JSON order), and per class: document game /
build / class token, tree ids, semantic content hash, live 0-error validation, warning
count and per-code counts. A partial, stale or corrupt manifest is rejected, triggering
re-normalization (or a clear failure when no raw data is available); the `manifest`
command exits non-zero on any mismatch. A future mobile updater can therefore trust:
build identity, class inventory, per-class file, semantic content hash, and validation
status — without loading every document.

## Build candidates: latest ≠ publishable ≠ live

wago.tools exposes `is_bgdl` per build. Observed on 2026-09-27: all five Forever 1.60.1
builds are `is_bgdl=false`; the retail `wow` product lists 12 builds with
`is_bgdl=true` (patch pre-downloads, e.g. 12.0.7.67808) — so the flag is actively used
by Battle.net and meaningful.

M1 domain model (`talent_data.sources.base`):

* `classify_build(info) -> BuildCandidate` — lifts the source-specific flag into the
  domain as a **tri-state**: `background_download` is `True`/`False` as stated by the
  source, or `None` (**unknown**) when the source does not provide `is_bgdl` at all.
  Unknown must never be read as "definitely not a background download": an unknown
  build is *not* publishable (backward-compatible for Wago, which always provides the
  flag);
* `latest_build(builds)` — **latest detected** (what `--build latest` uses, unchanged
  from M0);
* `latest_publishable_build(candidates)` — latest build **known** not to be a
  background download. A background-download build has the newest `created_at` exactly
  when it must *not* be treated as production data, so "latest" and "latest publishable"
  diverge precisely when it matters; a candidate set with only unknown/flagged builds
  raises instead of guessing.

wago.tools has no field that identifies the build the live realms run, so "publishable"
is only a necessary condition, never proof of "live". The pipeline surfaces
`[background-download]` in `builds list` output instead of guessing. No selection
behavior was changed silently in M1.

## Fixture strategy

`tests/fixtures/db2/forever/<build>/<class-token>/<Table>.csv` — for each of the nine
classes and every captured build (69977, 70009, 70245, 70338, 70374, 70450), the strict subset of rows reachable
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
python scripts/extract_fixtures.py --build 1.60.1.70338 --classes all   # + --tooltips
```

## Known unresolved semantics (unchanged or newly scoped in M1)

* Sufficient-edge combination semantics (OR vs AND) — preserved verbatim in the contract;
  **M2** established ANY (OR) for Sufficient and ALL for Required edges, and that a
  prerequisite must be *fully ranked* (High confidence, see
  [talent-allocation-semantics.md](talent-allocation-semantics.md)).
* `TraitNode.Flags` and `TraitNodeEntry.NodeEntryType` remain raw.
* How a `TraitNodeID` reference combines with a group/spend gate in one condition
  (Rogue cond 50984 on Venom) is unknown; the row is preserved verbatim as an
  unresolved condition.
* Costs: **M2** fetches `TraitCost` / `TraitTreeXTraitCost` and the normalizer *checks*
  that every rank costs exactly 1 point of the tree currency (it fails otherwise), after
  the source proves the per-group/node/entry cost tables absent (present or unknown fails
  closed); still no cost field in schema v1.
* Tab names (Arms/Fury/Protection…) do not exist in client data; `section.name` is null.
  Since 1.60.1.70425 one node, Mage Arcane Wrath, is in no gate group; it joins the tab whose
  columns contain it, flagged `SECTION_NOT_GATE_COUNTED`
  ([above](#arcane-wrath-a-node-no-gate-counts-section_not_gate_counted-160170425)).
* Tooltips stay raw templates in `talent-data.v1`; per-rank numbers exist for curve-backed
  effects only. Rendered descriptions (M5.V2) live in the `talent-tooltips.v1` sidecar.
* Whether the mis-placed duplicate nodes (Hunter/Priest) are drawn by the client is
  unknowable from DB2 alone; they are preserved in the contract and flagged.
* `effectBasePointsChanged` noise under SpellEffect index shifts (see diff noise above)
  is classified but not deduplicated in the raw diff.
