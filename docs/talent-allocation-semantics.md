# Talent allocation semantics (M2)

This document is the executable specification behind `talent_data.allocation`: every
allocation rule the reference engine implements, the evidence it rests on, how confident
we are, and what the engine does where the evidence stops. It is also the handoff contract
for the future Kotlin Multiplatform engine.

**Baseline:** WoW: Forever client build **1.60.1.70009** (latest detected on 2026-09-27;
no newer Forever build existed when M2 started or finished). The previous captured build
1.60.1.69977 has an identical allocation structure (same costs, edge/condition/node
inventory; checked by the tests on both builds).

Confidence scale used below:

| Level | Meaning |
| --- | --- |
| **Proven** | Stated by client data or the client's own generated API/UI code for this exact build, no interpretation needed. |
| **High** | Several independent sources agree (client UI code of this build + client data + reverse-engineered references), and the alternative reading contradicts shipped data. Not observed live in the client. |
| **Unresolved** | Not provable from the available sources → the engine fails closed. |

## 1. Sources examined

In the order of the source-of-truth hierarchy.

1. **Forever client behaviour / live `C_Traits` API — not available.** No game client or
   account could be driven from the M2 environment, so no value was read from a running
   client. Every "High" rule below lists the exact in-game check that would upgrade it to
   Proven (§13).
2. **Blizzard UI source of this exact build.** `Gethe/wow-ui-source`, branch `forever`,
   commit `bd2470aed543f72697a044e989285b6c83e63f73` ("1.60.1 (70009)", `version.txt` =
   `1.60.1.70009`). Forever is game type `camelot` (`Blizzard_PlayerSpells.toc` loads the
   `Camelot/` overrides with `[AllowLoadGameType camelot]`). Files used:
   * `Blizzard_APIDocumentationGenerated/SharedTraitsDocumentation.lua` — `CanPurchaseRank`,
     `CanRefundRank`, `RefundRank(configID, nodeID, clearEdges?)`, `CascadeRepurchaseRanks`,
     `GetNodeCost` → `TraitCurrencyCost[]`, `GetGroupCurrencyInfo`,
     `GetMaxAvailableTraitCurrency(currencyID, limitBySourcedMax)`, structures
     `TraitNodeInfo` (`meetsEdgeRequirements`, `canPurchaseRank`, `canRefundRank`,
     `ranksPurchased`, `ranksIncreased`, `isCascadeRepurchasable`), `TraitOutEdgeInfo`
     (`type`, `isActive`), `TraitCondInfo` (`isMet`, `isGate`, `isSufficient`,
     `spentAmountRequired`, … — **no node field**), `TraitTreeInfo.cannotRefund`.
   * `Blizzard_APIDocumentationGenerated/TraitConstantsDocumentation.lua` — enums
     `TraitEdgeType`, `TraitNodeType`, `TraitNodeEntryType`, `TraitNodeFlag`,
     `TraitCondFlag`, `TraitConditionType`, `TraitTreeFlag`, `TraitCurrencyType`,
     `NodeOpFailureReason` (server refusal reasons, e.g. `RequiredForEdge` = 2,
     `NotEnoughCurrencySpent` = 6, `RequiredForCondition` = 20, `TreeFlaggedNoRefund` = 23).
   * `Blizzard_SharedTalentUI/Blizzard_SharedTalentFrame.lua` l. 1901–2011
     (`AddConditionsToTooltip`, `AddEdgeRequirementsToTooltip`,
     `ShouldAddEdgeRequirementsToTooltip`), `Blizzard_SharedTalentEdgeTemplates.lua` l. 58,
     123 (edge colouring), `Blizzard_TalentButtonSpend.lua` l. 62–65 (`IsMaxed`),
     `Blizzard_SharedTalentUtil.lua` l. 572–596 (`IsCascadeRepurchaseHistoryEnabled`,
     `GetRefundInvalidInfo`).
   * `Blizzard_SharedTalentUI/Camelot/Blizzard_SharedTalentOverrides.lua` l. 53–55,
     `Blizzard_PlayerSpells/Camelot/ClassTalents/Blizzard_ClassTalentsFrame.lua` (tab
     headers show `C_Traits.GetGroupCurrencyInfo(...).spent` per tab, l. 88 and 318),
     `Blizzard_PlayerSpells/ClassTalents/Blizzard_ClassTalentsFrame.lua` l. 1321–1325,
     1370–1372, 1396–1398 (Reset button hidden for `cannotRefund` trees; Apply blocked by
     "refund invalid" nodes).
3. **Client DB2** (wago.tools CSV exports). Forever 1.60.1.70009 and 1.60.1.69977: all
   `Trait*` tables of the pipeline plus, new in M2, `TraitCost` and `TraitTreeXTraitCost`
   (now fetched, fixture-covered and checked by the normalizer); inspected ad hoc:
   `TraitNodeGroup`, `TraitCurrencySource`, `TraitSystem`, `SharedString`,
   `GlobalStrings`. Tables that **do not exist** in Forever (wago: "Table not found"):
   `TraitNodeXTraitCost`, `TraitNodeEntryXTraitCost`, `TraitNodeGroupXTraitCost`,
   `TraitSubTree`, `TraitNodeEntryXTraitCond`, `TraitCondAccountElement`,
   `TraitTreeLoadout`. Comparison build: retail **12.1.0.69933** (`TraitCond` 10 962 rows,
   `TraitEdge` 7 276, `TraitTree`, `TraitTreeLoadout`, `SharedString`).
4. **WoWDBDefs** — column/enum names (M0/M1); the enum values used here are taken from the
   client's own generated documentation (2.), which agrees with WoWDBDefs.
5. **External references — corroboration only, never a source of truth.**
   TrinityCore `master` @ `3395c1f8630a91020df143bb6567a6facb3229bc` (2026-09-26),
   `src/server/game/Spells/TraitMgr.cpp` — a reverse-engineered *retail* emulator
   (`ValidateConfig` l. 854–898 `isNodeFullyFilled` / `hasAnyParentTrait`,
   `AddSpentCurrenciesForEntry` l. 502–540). It is used to show that an independent
   implementation reached the same reading, and explicitly *not* where it disagrees with
   Forever data (its tier-gate check ignores the node group). Third-party Forever talent
   calculators and guides were read only as a sanity check (e.g. talent resets happen at a
   class trainer); nothing was copied and none is a runtime dependency.

## 2. Rules, evidence and decisions

| Rule | Evidence | Confidence | Implementation decision | Known limitation |
| --- | --- | --- | --- | --- |
| **COST-1** every purchased rank costs exactly 1 point of the tree's point currency | DB2: each of the 9 class trees has exactly one `TraitTreeXTraitCost` → `TraitCost` `Amount=1`, `TraitCurrencyID=3820`, `CurveID=0` (both builds); the group/node/entry cost link tables do not exist in the client; API `GetNodeCost` returns a list of `TraitCurrencyCost`; TrinityCore sums group+entry+node+tree costs × rank | Proven | Normalizer **checks** the invariant and refuses to produce a v1 document otherwise (`RANK_COST_POINTS`); live integration test fails if a node-level cost table appears | The offline pipeline cannot see a table it does not fetch; the absence of node-level cost tables is guarded by `pytest -m integration` |
| **BUDGET-1** usable points = `TraitCurrency.SourcedMax` (51), or a lower configured limit | DB2: currency 3820 `Type=2` (TraitSourced), `SourcedMax=51`; `TraitCurrencySource`: 51 rows "Level 10…60" (+1) plus 5 "Talented Rank N (Level 5…9)" rows gated by the Legacy tree 1188 node "Talented" → earlier points, same cap; API `GetMaxAvailableTraitCurrency(…, limitBySourcedMax)` | Proven | `point_budget` = dataset max, `point_limit` (0…max) for callers; no levelling logic | Level-based budgets are the Leveling Planner milestone |
| **RANK-1** 0 ≤ ranks ≤ entry `MaxRanks`; one entry per node | DB2 `TraitNodeEntry.MaxRanks`; every Forever node has exactly one entry | Proven | `MAX_RANK_REACHED`, `RANK_OUT_OF_BOUNDS`, `DUPLICATE_ALLOCATION` | — |
| **GATE-1** an unlock requirement is met when the points *already spent* in its counted nodes ≥ `requiredPoints`; evaluated before the purchase | DB2: 162 gate conditions `CondType=0 Flags=0 group+currency 3820+SpentAmountRequired` carrying `SpendMoreSharedStringID=911` = "Spend %d more point(s) in %s Talents"; API `GetGroupCurrencyInfo` → per-group `spent` (Camelot shows it per tab); `NodeOpFailureReason.NotEnoughCurrencySpent`; the 27 capstone gates (30 points, whole tab) include the capstone itself — the capstone is the tab's 31st point | Proven (quantity), High (evaluated before the purchase; a condition checked by `CanPurchaseRank` on the current config) | Counted on the current state before adding the rank; validation by reachability (VALID-1) | — |
| **GATE-2** gates count *currency spent* (= purchased ranks × COST-1); granted/increased ranks do not count | `TraitCond.TraitCurrencyID`/`SpentAmountRequired`, `GetGroupCurrencyInfo().spent`; free ranks cost nothing; TrinityCore spends `Amount × Rank` (purchased only). No `Granted`/`Increased` condition exists in Forever class trees | Proven for Forever (no free ranks exist) | Points spent = purchased ranks; gates in another currency are left unresolved by the normalizer | Granted/increased ranks would need contract support |
| **GATE-3** all conditions of a node must hold (AND) | `TraitCondFlag.IsSufficient` (4) documented as "any nodes using this condition are considered fulfilled if the condition is met" — the only OR mechanism; every Forever condition has `Flags=0` | Proven | All unlock requirements checked | — |
| **EDGE-ACTIVE** a prerequisite edge is active iff its source node is **fully ranked** (same for Required and Sufficient) | UI code of this build: "The edge only shows in red if the start is satisfied (or "Maxed")" (`SharedTalentEdgeTemplates.lua` l. 58/123; `IsMaxed` = `activeRank >= maxRanks`); TrinityCore `isNodeFullyFilled` (`Rank + GrantedRanks == MaxRanks`) for both edge kinds; classic talent semantics (`TOOLTIP_TALENT_PREREQ` "Requires %d points in %s" still in the Forever GlobalStrings); 37 of 70 Forever prerequisite edges start at a multi-rank talent | High | `edge_active`: source ranks ≥ source max rank | Not observed live (§13 E1) |
| **EDGE-REQ** all `RequiredForAvailability` edges must be active | Enum name; base UI shows "Requires all preceding talents" exactly when every incoming edge is Required (`SharedTalentFrame.lua` l. 1980–2011); TrinityCore returns failure on the first inactive Required parent; Forever has exactly one Required edge (Druid Nature's Majesty → Nature's Splendor), where ANY = ALL | High (multi-edge case: synthetic only) | `REQUIRED_PREREQUISITE_NOT_MET` per inactive source | Two Required edges on one node never occur in Forever; covered by a synthetic scenario |
| **EDGE-SUF** at least one `SufficientForAvailability` edge must be active | Enum name; `isSufficient` vocabulary of the API; base UI treats any non-Required incoming edge as "not all required"; TrinityCore `hasAnyParentTrait`; Hunter: under ALL, Intimidation (needs Bestial Swiftness **and** Bestial Wrath) and Bestial Wrath (needs Intimidation) would be permanently unobtainable in a shipped tree | High | `SUFFICIENT_PREREQUISITE_NOT_MET` when no source is active | Not observed live (§13 E2); see the Camelot tooltip note in §4 |
| **EDGE-MIX** a node with both Required and Sufficient incoming edges | Not present in Forever (tested); readings differ: TrinityCore = "all required AND any parent", an alternative = "all required AND any sufficient" | Unresolved | `UNSUPPORTED_EDGE_COMBINATION`, never purchasable | Fail closed until a real node exists and is verified |
| **COND-1** a condition whose semantics are not proven blocks the node | Rogue cond 50984 (§6) | — | `UNRESOLVED_CONDITION` on spend and validate, even when every proven rule is met | Rogue Venom is unplannable |
| **NODE-1** only proven node shapes are allocatable | Forever: all 469 nodes `Type=0` (Single), 1 entry, `NodeEntryType=1` (SpendSquare), `Flags` ∈ {0, 8 = TestGridPositioned (editor flag, never read by the UI code)}; `TraitNodeFlag.NeverPurchasable` = 2 exists in the API | Proven (inventory) | Supported: `single`, entry type 1, flag 8. `NeverPurchasable` → `NODE_NEVER_PURCHASABLE`; any other type/entry type/flag → `UNSUPPORTED_*` | Selection/tiered/sub-tree semantics not modelled (not in Forever) |
| **VALID-1** a build is valid iff it can be purchased rank by rank from an empty tree, every purchase allowed, within the budget | Forever class trees are `CannotRefund` (REFUND-1), so a plan can only be realised by purchasing it after a full reset; purchase rules are monotone (§9) | Proven (definition) | Greedy purchase closure; blocked nodes explained by the rules failing at the fixpoint | Possibly stricter than the client for plans only reachable through staged refunds (§8) |
| **REFUND-1** removing a planned rank is allowed iff the remaining plan is valid; no cascade | `NodeOpFailureReason.RequiredForEdge` / `RequiredForCondition` (refusal, not cascade); the only cascade API is `CascadeRepurchaseRanks` — a *re*-purchase convenience whose history is disabled (`IsCascadeRepurchaseHistoryEnabled() → false`); retail UI "refund invalid" nodes block Apply (`TALENT_FRAME_REFUND_INVALID_ERROR`) | High | `DEPENDENT_NODE_WOULD_BREAK` (one issue per broken dependent rule, with `cause`) | In-game, committed ranks cannot be refunded at all (`CannotRefund`) — the engine refund is a plan edit (§8) |
| **STATE-1** actions require a valid current state | Design rule (a reference engine must not answer "allowed" relative to an impossible state) | — | `INVALID_BUILD_STATE` from `can_spend`/`can_refund` | Callers `validate()` for details |
| **ENTRY-1** the entry must belong to the node; omitted entry = the node's only entry | API purchases by `(nodeID, nodeEntryID)`; `NodeOpFailureReason.EntryNotFound` | Proven | `UNKNOWN_NODE`, `INVALID_ENTRY`, `ENTRY_NOT_SELECTED` (multi-entry node without an entry) | — |

## 3. Dataset inventory (all nine classes)

Generated from the committed per-class DB2 fixtures (strict subsets of the wago exports)
with `python scripts/allocation_inventory.py --build 1.60.1.70009` (identical structure
for 1.60.1.69977):

| Class | Tree | Nodes | Node.Type | Node.Flags | Entries/node | EntryType | MaxRanks | Edge.Type | Nodes w/ >1 prereq edge | Currency | Rank cost (tree) | Conds | Granted conds | Tree.Flags |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| WARRIOR | 1117 | 53 | 0x53 | 0x50, 8x3 | 1x53 | 1x53 | 1x12, 2x11, 3x15, 5x15 | 2x8 | 0 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| PALADIN | 1100 | 50 | 0x50 | 0x50 | 1x50 | 1x50 | 1x12, 2x12, 3x14, 5x12 | 2x7 | 0 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| HUNTER | 1091 | 52 | 0x52 | 0x48, 8x4 | 1x52 | 1x52 | 1x12, 2x14, 3x6, 5x20 | 2x9 | 1 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| ROGUE | 1111 | 53 | 0x53 | 0x53 | 1x53 | 1x53 | 1x13, 2x16, 3x13, 5x11 | 2x8 | 0 | 3820 (max 51) | 1x3820 curve 0 | 19 | 0 | 1 |
| PRIEST | 1114 | 54 | 0x54 | 0x49, 8x5 | 1x54 | 1x54 | 1x12, 2x13, 3x15, 5x14 | 2x8 | 0 | 3820 (max 51) | 1x3820 curve 0 | 19 | 0 | 1 |
| SHAMAN | 1082 | 50 | 0x50 | 0x50 | 1x50 | 1x50 | 1x12, 2x8, 3x15, 5x15 | 2x7 | 0 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| MAGE | 1112 | 54 | 0x54 | 0x54 | 1x54 | 1x54 | 1x12, 2x14, 3x17, 5x11 | 2x6 | 0 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| WARLOCK | 1116 | 52 | 0x52 | 0x51, 8x1 | 1x52 | 1x52 | 1x12, 2x11, 3x15, 5x14 | 2x8 | 0 | 3820 (max 51) | 1x3820 curve 0 | 18 | 0 | 1 |
| DRUID | 1089 | 51 | 0x51 | 0x50, 8x1 | 1x51 | 1x51 | 1x12, 2x11, 3x13, 5x15 | 0x1, 2x8, 3x1 | 0 | 3820 (max 51) | 1x3820 curve 0 | 19 | 0 | 1 |

| TraitCond shape (non-zero functional fields) | Count |
|---|---|
| CondType=0 group+currency+spent | 162 |
| CondType=0 (all zero) | 2 |
| CondType=0 group+node+currency+spent | 1 |

Consequences:

* **Node costs / entry costs:** none exist (no link tables in the client); the tree cost
  is the whole price → COST-1.
* **Selection nodes: 0. Tiered nodes: 0. Sub-tree nodes/entries: 0** (normalizer now
  rejects sub-tree ids). Selection semantics can therefore not be verified on real data:
  a selection node fails closed (`synthetic.selection_node_fail_closed`), and a dataset
  test fails the day Forever ships one.
* **Mutually exclusive edges: 0** (the normalizer rejects every edge type other than
  0/2/3). **Granted/increased ranks: 0.** **Gate currencies:** all 3820.
* **Unresolved conditions:** exactly one, Rogue 50984 on Venom. The two all-zero rows
  (Priest 43478, Druid 50974) are the proven no-ops of M1.
* `Tree.Flags = 1` (`TraitTreeFlag.CannotRefund`) on all nine trees; retail class loadout
  trees are refundable (20 of 21 retail `TraitTreeLoadout` trees have `Flags=0`).
* Gates: row *r* of a tab needs 5·r points in the rows above; the 27 capstones need 30
  points counted over the whole tab — themselves included (see GATE-1).

## 4. Edge semantics

| `TraitEdge.Type` | Name | In Forever class trees (70009) | Meaning | Engine |
| --- | --- | --- | --- | --- |
| 0 | VisualOnly | 1 (Druid) | drawn, no requirement | ignored for availability (`visualOnlyEdges` in the contract) |
| 1 | DeprecatedRankConnection | 0 (0 in retail) | deprecated | normalization fails loudly → unsupported |
| 2 | SufficientForAvailability | 69 | any active one suffices (EDGE-SUF) | supported |
| 3 | RequiredForAvailability | 1 | all must be active (EDGE-REQ) | supported |
| 4 | MutuallyExclusive | 0 | purchasing one side forbids the other (`NodeOpFailureReason.HasMutuallyExclusiveEdge`; UI draws it red) — *non-monotone* | normalization fails loudly → unsupported |
| 5 | DeprecatedSelectionOption | 0 | deprecated | normalization fails loudly → unsupported |

**Incoming-edge combination.** Required: ALL. Sufficient: ANY. Required + Sufficient on one
node: not present, two plausible readings, **fail closed** (`UNSUPPORTED_EDGE_COMBINATION`).
Example: a node with Required A, Required B, Sufficient C, Sufficient D is never
purchasable by this engine; for A, B only it needs A and B active; for C, D only it needs C
or D active.

**Active edge = fully ranked source**, identical for both kinds (EDGE-ACTIVE). Deep Wounds
with Improved Rend 2/3 is unavailable; at 3/3 it is available
(`warrior.sufficient_prerequisite_requires_max_rank`).

**Camelot tooltip note (counter-evidence considered).** The Forever override
`ShouldAddEdgeRequirementsToTooltip` shows the red line "Requires all preceding talents"
whenever *any* incoming edge is inactive. For the 68 single-edge nodes this is equivalent
to ANY. For Intimidation it would even show on a *purchased* Intimidation while Bestial
Wrath is not taken — so it is a tooltip heuristic (the base client only shows it for
all-Required nodes), not the availability rule, which the client takes from
`meetsEdgeRequirements`. Reading it as ALL would make the Hunter capstone unobtainable.

**Hunter Bestial Wrath ⇄ Intimidation (why it is not a deadlock).** Edges (1.60.1.70009):
`124700: 104961 Bestial Wrath → 104964 Intimidation`, `124701: 104964 → 104961`,
`124695: 104970 Bestial Swiftness → 104964`, all Sufficient. With ANY-semantics the cycle
has a root: Bestial Swiftness (row 2) activates Intimidation (row 4, 20 points), which
activates Bestial Wrath (row 6, 30 points). The backward edge is redundant for purchasing.
What the engine must *not* do is let the two support each other once the root is gone:
refunding Bestial Swiftness (or importing Intimidation + Bestial Wrath without it) is
rejected because no purchase order reaches that plan (VALID-1). Covered by
`hunter.mutual_sufficient_cycle*` and `tests/unit/allocation/test_real_structures.py`.

## 5. Tier gates

`unlockRequirements: [{requiredPoints, countedNodeIds}]` means: **points spent** (currency
3820, = purchased ranks by COST-1) in `countedNodeIds` **before** the purchase ≥
`requiredPoints` (GATE-1/2/3).

* Ranks vs currency: the client counts currency spent (`GetGroupCurrencyInfo().spent`,
  "Spend %d more points in %s Talents"); with COST-1 this is exactly the purchased ranks.
* Rank costs > 1: impossible in the v1 contract — normalization fails first (COST-1).
* Granted ranks / increased ranks: cost nothing, therefore count nothing; none exist in
  Forever. Conditions of other types stay unresolved.
* Selection nodes: none in Forever.
* Self-counting: the capstone gate counts the whole tab including the capstone. Evaluated
  before the purchase, the capstone needs 30 *other* points in the tab
  (`warrior.capstone_gate_counts_points_before_purchase`: 29 + capstone is refused, 30 +
  capstone is valid).

## 6. Conditions

Only `CondType=0` (Available), `Flags=0` rows of the exact shape "group + currency +
spent" become gates, and only in the tree's own point currency (M2 tightening; unchanged
output). Everything else stays in `unresolvedConditionIds`.

### Rogue condition 50984 — unresolved

| Field | Value |
| --- | --- |
| Attached to | node 105712 **Venom** (Assassination capstone), via `TraitNodeXTraitCond` — the only node-direct condition in all nine trees |
| CondType / Flags | 0 (Available) / 0 |
| TraitNodeGroupID | 11576 = the row-4 nodes {Mutilate 105709, Improved Kidney Shot 105711, Vigor 105718} |
| TraitNodeID | 105709 Mutilate |
| TraitCurrencyID / SpentAmountRequired | 3820 / 1 |
| SpendMoreSharedStringID / FreeSharedStringID | 0 / 0 (every real gate uses 911) |
| Builds | identical in 1.60.1.69977 and 1.60.1.70009 |

What was checked:

* **Client API:** `TraitCondInfo` (the result of `C_Traits.GetConditionInfo`) has *no*
  node field at all — the client never exposes how the node reference is used. Not
  callable without a client.
* **UI source:** no code interprets a condition's node reference (tooltips only use
  `isMet`, `isGate`, `isSufficient`, `type`, `spentAmountRequired`, `playerLevel`).
* **DB2 relationships:** the row is not a gate (no "spend more" text, unlike all 162 gates);
  group 11576 is not a tab or row gate group used anywhere else.
* **Retail comparison (12.1.0.69933):** 0 of 10 962 conditions combine `TraitNodeGroupID`
  with `TraitNodeID`; node-referencing retail conditions are `Granted`/`DisplayError`
  types. No analogue exists.
* **Observation (not proof):** under every *monotone* reading "≥ 1 point spent in S" with
  Mutilate ∈ S (S = Mutilate, group 11576, their union or intersection), the condition is
  implied by Venom's own Sufficient edge Mutilate → Venom (Mutilate 1/1). It is therefore
  probably redundant — but non-monotone or scope readings cannot be excluded without the
  client, and the reverse-engineered TrinityCore evaluator would interpret the row
  differently again (currency spent across gates, ignoring group and node).

**Decision:** unresolved. The normalized contract keeps `unresolvedConditionIds: [50984]`;
the engine answers `UNRESOLVED_CONDITION` ("cannot verify availability of node 105712
(Venom): TraitCond 50984 is unresolved") on every purchase and on every imported build
containing Venom, even when the gate and the Mutilate edge are satisfied. §13 E4 is the
in-game check that would resolve it.

## 7. Cost semantics

`TraitTreeXTraitCost` → `TraitCost` per class tree (identical in 69977 and 70009):

| Trees | TraitCost | InternalName | Amount | Currency | Curve |
| --- | --- | --- | --- | --- | --- |
| 1089 | 4040 | Tree cost | 1 | 3820 | 0 |
| 1082 | 4041 | Tree cost | 1 | 3820 | 0 |
| 1091, 1100, 1111 | 4044 | Tree cost | 1 | 3820 | 0 |
| 1112, 1114, 1116, 1117 | 4114 | Tree cost | 1 | 3820 | 0 |

The client price of a rank is the sum of the costs linked to the node's groups, entry,
node and tree (API `GetNodeCost`; same composition in TrinityCore). Forever has only the
tree link. **Invariant (checked, not assumed):** `normalizer._check_rank_cost_invariant`
requires exactly one tree cost, `Amount=1`, in the budget currency, `CurveID=0`; anything
else stops normalization with a message asking for a schema extension. Tests:
`tests/unit/test_cost_invariant.py` (all 9 classes × 2 builds, plus mutated costs) and the
live `test_latest_build_has_no_node_level_cost_tables`. Because the invariant is part of
the v1 contract's production rule, **`talent-data.v1` is unchanged** (no field added;
content hashes of all 18 committed documents are identical — only the volatile
`provenance.tables` list names the two new tables).

## 8. Refund semantics

Game state versus UI convenience:

* **Game state (Proven):** all nine Forever class trees have `TraitTree.Flags = 1` =
  `TraitTreeFlag.CannotRefund` (`TraitTreeInfo.cannotRefund`); the server refusal
  `NodeOpFailureReason.TreeFlaggedNoRefund` exists; the Blizzard talent frame hides its
  Reset button for such trees. Committed talents cannot be unlearned one by one; the
  only way back is a full reset (class trainer dialogue — confirmed by external guides as
  a sanity check).
* **Staged changes (not observable here):** before Apply, the client keeps a staged
  config (Undo = `RollbackConfig`). Whether a staged rank can be right-click unlearned in
  a `CannotRefund` tree is decided in C++ (`canRefundRank`).
* **UI conveniences, not rules:** `CascadeRepurchaseRanks` (re-buy history; disabled in
  this client), `RefundRank(…, clearEdges)` and "refund invalid" nodes (a dependent left
  without support is drawn red and blocks Apply until fixed) — none produces a valid
  build.

**Engine rule (REFUND-1):** `refund` is an edit of the *plan*. It removes one rank iff the
remaining plan is still valid (VALID-1); dependents are never removed implicitly (no
cascade). In the example *Talent A 5/5 → Talent B 1/1*, refunding A is refused with
`DEPENDENT_NODE_WOULD_BREAK` (node B, related A, cause
`SUFFICIENT_PREREQUISITE_NOT_MET`); refunding B first is allowed. A refund that would
drop a purchased node's tier gate is refused the same way with cause `TIER_GATE_NOT_MET`.

Because in-game a Forever plan can only be realised by purchasing it after a reset, the
engine's validity is exactly "obtainable by purchases". It can be *stricter* than the
client in two corners reachable only through staged refunds (circular support without a
root; a capstone kept after its tab drops to 30 points including itself). Both are
refused by design.

## 9. Validity: reachability and why the greedy closure is exact

`validate(state)` checks, in order: tree id; per allocation unknown node, duplicate,
entry ownership, rank bounds; budget; static fail-closed issues of allocated nodes; then
the **purchase closure**: starting from an empty tree, repeatedly buy the next rank of
any target node whose rules pass, until nothing changes. Each target rank not reached is
explained by the gate/edge rules failing at that fixpoint.

The closure is exact because every purchase rule is monotone in the ranks bought:
spent-in-group only grows, an edge becomes active when its source reaches max rank and
stays active, static rules do not depend on the state. Hence if any valid order reaches
the target, the closure does too; the budget is checked on the final total (partial
sequences spend less). The only non-monotone client rule, `MutuallyExclusive`, cannot
enter a v1 document. An independent brute-force search over purchase orders agrees with
`validate` on generated imports for all nine classes
(`tests/unit/allocation/test_invariants.py`).

## 10. Unsupported / unresolved semantics (fail closed)

| Mechanic | In Forever 1.60.1 | Engine answer |
| --- | --- | --- |
| Rogue cond 50984 (and any condition the normalizer cannot prove) | 1 | `UNRESOLVED_CONDITION` |
| Node type Tiered / Selection / SubTreeSelection / unknown | 0 | `UNSUPPORTED_NODE_TYPE` |
| Single node with several entries | 0 | `UNSUPPORTED_NODE_TYPE` (`ENTRY_NOT_SELECTED` if no entry given) |
| Entry type ≠ SpendSquare (1) | 0 | `UNSUPPORTED_ENTRY_TYPE` |
| `TraitNode.Flags` bits other than 8 (TestGridPositioned) | 0 | `UNSUPPORTED_NODE_FLAGS`; bit 2 → `NODE_NEVER_PURCHASABLE` |
| Required + Sufficient on one node | 0 | `UNSUPPORTED_EDGE_COMBINATION` |
| Edge types 1, 4, 5 / unknown | 0 | normalization fails (no v1 document) |
| Rank costs other than 1 point, node/group/entry costs | 0 | normalization fails |
| Gates in another currency, Granted/Increased/Visible/DisplayError/RanksAllowed conditions | 0 | stay unresolved → `UNRESOLVED_CONDITION` |
| Sub-trees (hero talents) | 0 | normalization fails |

`engine.unsupported_nodes()` lists the affected nodes of a dataset; a test pins the
current inventory (only Venom) for both builds.

## 11. Reason codes

Stable, machine-readable (`AllocationIssueCode`), in canonical order:

| Code | When |
| --- | --- |
| `UNKNOWN_TREE` | build is for another tree |
| `UNKNOWN_NODE` | node id not in the tree |
| `INVALID_ENTRY` | entry does not belong to the node |
| `ENTRY_NOT_SELECTED` | multi-entry node purchased without an entry |
| `DUPLICATE_ALLOCATION` | node listed twice in a build |
| `RANK_OUT_OF_BOUNDS` | imported rank < 1 or > max |
| `INVALID_BUILD_STATE` | spend/refund on a state that does not validate |
| `UNSUPPORTED_NODE_TYPE`, `UNSUPPORTED_ENTRY_TYPE`, `UNSUPPORTED_NODE_FLAGS`, `UNSUPPORTED_EDGE_COMBINATION` | fail-closed mechanics (§10) |
| `UNRESOLVED_CONDITION` | node carries an unresolved condition (`conditionId`) |
| `NODE_NEVER_PURCHASABLE` | `TraitNodeFlag.NeverPurchasable` |
| `MAX_RANK_REACHED` | node already at max rank |
| `NOT_ALLOCATED` | refund of a node with 0 ranks |
| `NO_POINTS_REMAINING` | no point left for one more rank |
| `POINT_BUDGET_EXCEEDED` | imported build spends more than the budget |
| `TIER_GATE_NOT_MET` | gate not met (`required`, `actual` points) |
| `REQUIRED_PREREQUISITE_NOT_MET` | a Required source not fully ranked (`relatedNodeIds`) |
| `SUFFICIENT_PREREQUISITE_NOT_MET` | no Sufficient source fully ranked (`relatedNodeIds` = all sources) |
| `DEPENDENT_NODE_WOULD_BREAK` | refund would invalidate `nodeId` (`relatedNodeIds` = refunded node, `cause` = the failing rule) |

Mapping of the names in the M2 brief: `INSUFFICIENT_CURRENCY` → `NO_POINTS_REMAINING`
(one currency, COST-1); `NODE_UNAVAILABLE` → always reported as the specific rule
(`TIER_GATE_NOT_MET` / `*_PREREQUISITE_NOT_MET` / fail-closed codes);
`UNSUPPORTED_EDGE_TYPE` → rejected at normalization (no v1 document can carry one), plus
`UNSUPPORTED_EDGE_COMBINATION` in the engine. Logic never parses `message`.

## 12. Engine API and conformance vectors

```python
from talent_data.allocation import TalentAllocationEngine

# document: a normalized TalentDataDocument; point_limit: optional lower budget
engine = TalentAllocationEngine(document, point_limit=None)
state = engine.empty_state()  # immutable BuildState
result = engine.spend(state, node_id)  # AllocationResult(decision, state)
print(result.decision.allowed, result.decision.reason_codes, result.decision.issues)
decision = engine.can_spend(state, node_id, entry_id=None)  # AllocationDecision
decision = engine.can_refund(state, node_id)
result = engine.refund(state, node_id)
validation = engine.validate(state)  # BuildValidation(valid, issues, ...)
points = (engine.spent_points(state), engine.remaining_points(state))
blocked = engine.unsupported_nodes()  # fail-closed nodes of the dataset
```

Build files (`docs/schema/allocation-build.v1.schema.json`):
`python -m talent_data allocation validate --game forever --build 1.60.1.70009 --class warrior --build-file my-build.json`.

**Conformance vectors:** `data/conformance/forever/1.60.1.70009/allocation.v1.json`
(schema `docs/schema/allocation-conformance.v1.schema.json`), **53 scenarios / 177
steps**: 35 rule scenarios (8 of them synthetic mutations for mechanics absent from
Forever) + a smoke and a golden 51-point scenario for each of the nine classes.

* A suite pins `game`, `clientBuild` and each dataset file + `contentHash`.
* A scenario: `class`, `rules` (rule ids of §2), `synthetic`, optional `pointLimit`,
  `mutations`, `initial` allocations, and `steps`. Each step (`spend` / `refund` /
  `validate`) expects `ok`, sorted `reasonCodes`, the ordered structural `issues`
  (`code`, `nodeId`, `entryId`, `relatedNodeIds`, `conditionId`, `required`, `actual`,
  `cause` — never messages), `spentPoints`, `remainingPoints` and (spend/refund) the
  `nodeRank` after the step. A refused action leaves the state unchanged.
* Mutation ops (apply to the class document, then re-hash): `addPrerequisite`
  (`nodeId`, `prerequisiteNodeId`, `kind`), `setNodeType` (`value`), `setNodeFlags`,
  `setEntryType` (all entries of the node), `addUnresolvedCondition`.
* Authored expectations: every step's `ok` + codes are written by hand from the rules in
  `tests/conformance/scenarios.py`; the generator refuses to write the suite when the
  engine disagrees, and only then records the engine's full structural output. Drift is a
  test failure. Regenerate after a reviewed rule/data change:
  `python -m tests.conformance.generate`; replay: `python -m talent_data allocation replay --all`.
* Golden builds are **engine-constructed** (greedy purchase in section/row/column order,
  skipping M1 `OFF_GRID_NODE` anomalies), not client-observed; each validates at 51/51.

## 13. Kotlin handoff and in-game verification

The KMP engine must reproduce, on `talent-data.v1` documents:

1. constants: rank cost 1 point of `pointBudget.currencyId`; budget = `maxPoints` or a
   configured lower limit; supported node type `single`, entry type 1, neutral node flag 8;
2. per-node static (fail-closed) issues exactly as `rules._static_issues` (§10);
3. GATE-1/2/3, EDGE-ACTIVE, EDGE-REQ (ALL), EDGE-SUF (ANY), EDGE-MIX fail-closed;
4. `validate` = structural checks, budget, static issues, purchase closure with fixpoint
   explanations (§9);
5. `canSpend` order: state valid → node exists → entry resolution → (static issues, max
   rank, points, gates, edges) all collected; `canRefund`: state valid → node exists →
   rank > 0 → validate(plan − 1 rank) → one `DEPENDENT_NODE_WOULD_BREAK` per issue;
6. issue ordering: code declaration order, then `nodeId`, `entryId`, `relatedNodeIds`,
   `conditionId`, `cause`; `reasonCodes` = sorted distinct codes; points never negative;
7. **pass every step of the conformance suite exactly** (messages excluded).

In-game checks that would turn the High rules into Proven (run on a Forever character,
`/run` or a scratch addon; `c = C_ClassTalents.GetActiveConfigID()`):

| # | Check | Expected |
| --- | --- | --- |
| E1 | Warrior, 10 points in Arms rows 0–1 with Improved Rend 2/3: `C_Traits.GetNodeInfo(c, 105950).meetsEdgeRequirements` (Deep Wounds), then at 3/3 | false, then true |
| E2 | Hunter, 20 Beast Mastery points incl. Bestial Swiftness 1/1, no Bestial Wrath: `GetNodeInfo(c, 104964).meetsEdgeRequirements` (Intimidation) | true |
| E3 | Warrior, 29 other Arms points: `GetNodeInfo(c, 105941).isAvailable` (Mortal Strike); with 30 | false, then true |
| E4 | Rogue: `C_Traits.GetConditionInfo(c, 50984).isMet` with no points, with Mutilate 1/1, with only Vigor/Improved Kidney Shot in row 4 | resolves 50984 |
| E5 | `C_Traits.GetNodeCost(c, 105958)` (any node) | one `TraitCurrencyCost`, amount 1, currency 3820 |
| E6 | `C_Traits.GetTreeInfo(c, 1117).cannotRefund`; stage one purchase, then `GetNodeInfo(c, id).canRefundRank` | true; staged refund behaviour |
| E7 | level 60: `C_Traits.GetTreeCurrencyInfo(c, 1117, false)[1].maxQuantity` | 51 |

## 14. Known limitations

* No live client verification (§1.1); High-confidence rules are corroborated, not
  observed. §13 lists the checks.
* Rogue Venom (cond 50984) is unplannable until E4 resolves it.
* Mechanics absent from Forever (selection/tiered/sub-tree nodes, mutually exclusive
  edges, mixed edge kinds, granted/increased ranks, other node flags, node-level costs)
  fail closed; they are covered only by synthetic scenarios.
* The mis-placed duplicate nodes (Hunter 104982 Lightning Reflexes, Priest 105865 Holy
  Specialization; M1 `OFF_GRID_NODE`) have ordinary rules in client data and are
  purchasable by the engine; whether the client draws them is unknown — a UI should hide
  OFF_GRID anomalies. Golden builds avoid them.
* Validity is reachability; it may be stricter than the client for plans reachable only
  through staged refunds (§8).
* `CannotRefund` is not part of the v1 document; a mobile UI must know that in-game
  refunds need a trainer reset (candidate for a future contract revision).
* `required`/`actual` numbers in validation issues are computed at the purchase-closure
  fixpoint (the explanation), not as raw totals of the imported build.
* The absence of node-level cost tables is verified by the live integration test only.
