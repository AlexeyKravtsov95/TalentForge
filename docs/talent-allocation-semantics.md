# Talent allocation semantics (M2)

This document is the executable specification behind `talent_data.allocation`: every
allocation rule the reference engine implements, the evidence it rests on, how confident
we are, and what the engine does where the evidence stops. It is also the handoff contract
for the future Kotlin Multiplatform engine.

> **Status of the contract: M2 reference hypothesis, not frozen.** The edge rules
> (EDGE-ACTIVE, EDGE-SUF) rest on the client's C++ evaluator, which is not inspectable;
> the Forever Lua UI only *consumes* its results (`edgeInfo.isActive`,
> `nodeInfo.meetsEdgeRequirements`). They require client verification before the Kotlin
> contract is frozen. **M3 MUST NOT start until at least E1 and E2 (§13) are
> client-observed**; E3 and E6 should be observed as well. `allocation.v1` stays the
> reference vector set for M2 but is not declared final for M3 until then.

**Baseline:** WoW: Forever client build **1.60.1.70009** (latest detected on 2026-09-27;
no newer Forever build existed when M2 started or finished). The previous captured build
1.60.1.69977 has an identical allocation structure (same costs, edge/condition/node
inventory; checked by the tests on both builds).

Confidence scale used below:

| Level | Meaning |
| --- | --- |
| **Proven** | Stated by client data or the client's own generated API/UI code for this exact build, no interpretation needed. |
| **High** | Several independent sources agree (client UI code of this build + client data + reverse-engineered references), and the alternative reading contradicts shipped data. Not observed live in the client. |
| **High — hypothesis** | As High, but the deciding computation happens in client C++ that no available source shows; the evidence is how the UI *presents* its results. Implemented as the M2 reference hypothesis; **requires client verification before the Kotlin contract is frozen.** |
| **Unresolved** | Not provable from the available sources → the engine fails closed. |

## 1. Sources examined

In the order of the source-of-truth hierarchy.

1. **Forever client behaviour / live `C_Traits` API — partially observed after M2.** No
   game client or account could be driven from the M2 environment, so the M2 rules were
   derived without any value read from a running client. A first, limited set of live
   observations (Paladin, level 20, build 1.60.1.70009) was recorded afterwards in §15. It
   supports COST-1, BUDGET-1, GATE-1 and the committed-tree part of REFUND-1; it does not
   touch EDGE-ACTIVE, EDGE-SUF or Rogue 50984. Every "High" rule below lists the exact
   in-game check that would upgrade it to Proven (§13).
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
| **COST-1** every purchased rank costs exactly 1 point of the tree's point currency | DB2: each of the 9 class trees has exactly one `TraitTreeXTraitCost` → `TraitCost` `Amount=1`, `TraitCurrencyID=3820`, `CurveID=0` (both builds); the group/node/entry cost link tables are *proven absent* for both builds (wago.tools' definitive `{"errors": "Table not found."}`, recorded per build); API `GetNodeCost` returns a list of `TraitCurrencyCost`; TrinityCore sums group+entry+node+tree costs × rank; **live client (§15 L2):** `GetNodeCost` on a Paladin node (tree 1100) returned exactly one cost, currency 3820, amount 1 | Proven | Normalizer **checks** the tree cost and refuses to produce a v1 document unless every per-group/node/entry cost table is proven absent by the source (`Db2Source.table_status`: present or unknown → fail closed) | Absence evidence is wago.tools' answer for the build (the best available origin; §7) |
| **BUDGET-1** usable points = `TraitCurrency.SourcedMax` (51), or a lower configured limit | DB2: currency 3820 `Type=2` (TraitSourced), `SourcedMax=51`; `TraitCurrencySource`: 51 rows "Level 10…60" (+1) plus 5 "Talented Rank N (Level 5…9)" rows gated by the Legacy tree 1188 node "Talented" → earlier points, same cap; API `GetMaxAvailableTraitCurrency(…, limitBySourcedMax)`; **live client (§15 L7):** at level 20 `GetTreeCurrencyInfo` reports `maxQuantity = 11` — a runtime level limit below the dataset maximum, which stays 51 | Proven | `point_budget` = dataset max, `point_limit` (0…max) for callers; no levelling logic | Level-based budgets are the Leveling Planner milestone |
| **RANK-1** 0 ≤ ranks ≤ entry `MaxRanks`; one entry per node | DB2 `TraitNodeEntry.MaxRanks`; every Forever node has exactly one entry | Proven | `MAX_RANK_REACHED`, `RANK_OUT_OF_BOUNDS`, `DUPLICATE_ALLOCATION` | — |
| **GATE-1** an unlock requirement is met when the points *already spent* in its counted nodes ≥ `requiredPoints`; evaluated before the purchase | DB2: 162 gate conditions `CondType=0 Flags=0 group+currency 3820+SpentAmountRequired` carrying `SpendMoreSharedStringID=911` = "Spend %d more point(s) in %s Talents"; API `GetGroupCurrencyInfo` → per-group `spent` (Camelot shows it per tab); `NodeOpFailureReason.NotEnoughCurrencySpent`; the 27 capstone gates (30 points, whole tab) include the capstone itself — the capstone is the tab's 31st point; **live client (§15 L5/L6, Paladin analogue, not E3):** a 5-point-gated row-1 node without prerequisite edges is unavailable with 4 points in its tab and available with 7 | Proven (quantity), High (evaluated before the purchase; a condition checked by `CanPurchaseRank` on the current config) | Counted on the current state before adding the rank; validation by reachability (VALID-1) | — |
| **GATE-2** gates count *currency spent* (= purchased ranks × COST-1); granted/increased ranks do not count | `TraitCond.TraitCurrencyID`/`SpentAmountRequired`, `GetGroupCurrencyInfo().spent`; free ranks cost nothing; TrinityCore spends `Amount × Rank` (purchased only). No `Granted`/`Increased` condition exists in Forever class trees | Proven for Forever (no free ranks exist) | Points spent = purchased ranks; gates in another currency are left unresolved by the normalizer | Granted/increased ranks would need contract support |
| **GATE-3** all conditions of a node must hold (AND) | `TraitCondFlag.IsSufficient` (4) documented as "any nodes using this condition are considered fulfilled if the condition is met" — the only OR mechanism; every Forever condition has `Flags=0` | Proven | All unlock requirements checked | — |
| **EDGE-ACTIVE** a prerequisite edge is active iff its source node is **fully ranked** (same for Required and Sufficient) | The decision itself is made by client C++ (`TraitOutEdgeInfo.isActive`, `TraitNodeInfo.meetsEdgeRequirements`), which is not available. Indirect evidence: UI code of this build *visualises* a satisfied source as "Maxed" — "The edge only shows in red if the start is satisfied (or "Maxed")" (`SharedTalentEdgeTemplates.lua` l. 58/123; `IsMaxed` = `activeRank >= maxRanks`); TrinityCore `isNodeFullyFilled` (`Rank + GrantedRanks == MaxRanks`) for both edge kinds; classic talent semantics (`TOOLTIP_TALENT_PREREQ` "Requires %d points in %s" still in the Forever GlobalStrings); 37 of 70 Forever prerequisite edges start at a multi-rank talent | High — hypothesis | `edge_active`: source ranks ≥ source max rank (M2 reference hypothesis) | **Requires client verification (§13 E1) before the Kotlin contract is frozen** |
| **EDGE-REQ** all `RequiredForAvailability` edges must be active | Enum name; base UI shows "Requires all preceding talents" exactly when every incoming edge is Required (`SharedTalentFrame.lua` l. 1980–2011); TrinityCore returns failure on the first inactive Required parent; Forever has exactly one Required edge (Druid Nature's Majesty → Nature's Splendor), where ANY = ALL | High (multi-edge case: synthetic only) | `REQUIRED_PREREQUISITE_NOT_MET` per inactive source | Two Required edges on one node never occur in Forever; covered by a synthetic scenario |
| **EDGE-SUF** at least one `SufficientForAvailability` edge must be active | Enum name; `isSufficient` vocabulary of the API; base UI treats any non-Required incoming edge as "not all required"; TrinityCore `hasAnyParentTrait`; Hunter: under ALL, Intimidation (needs Bestial Swiftness **and** Bestial Wrath) and Bestial Wrath (needs Intimidation) would be permanently unobtainable in a shipped tree. The evaluation itself (`meetsEdgeRequirements`) is client C++ | High — hypothesis | `SUFFICIENT_PREREQUISITE_NOT_MET` when no source is active (M2 reference hypothesis) | **Requires client verification (§13 E2) before the Kotlin contract is frozen**; see the Camelot tooltip note in §4 |
| **EDGE-MIX** a node with both Required and Sufficient incoming edges | Not present in Forever (tested); readings differ: TrinityCore = "all required AND any parent", an alternative = "all required AND any sufficient" | Unresolved | `UNSUPPORTED_EDGE_COMBINATION`, never purchasable | Fail closed until a real node exists and is verified |
| **COND-1** a condition whose semantics are not proven blocks the node | Rogue cond 50984 (§6) | — | `UNRESOLVED_CONDITION` on spend and validate, even when every proven rule is met | Rogue Venom is unplannable |
| **NODE-1** only proven node shapes are allocatable | Forever: all 469 nodes `Type=0` (Single), 1 entry, `NodeEntryType=1` (SpendSquare), `Flags` ∈ {0, 8 = TestGridPositioned (editor flag, never read by the UI code)}; `TraitNodeFlag.NeverPurchasable` = 2 exists in the API | Proven (inventory) | Supported: `single`, entry type 1, flag 8. `NeverPurchasable` → `NODE_NEVER_PURCHASABLE`; any other type/entry type/flag → `UNSUPPORTED_*` | Selection/tiered/sub-tree semantics not modelled (not in Forever) |
| **VALID-1** a build is valid iff it can be purchased rank by rank from an empty tree, every purchase allowed, within the budget | Forever class trees are `CannotRefund` (REFUND-1), so a plan can only be realised by purchasing it after a full reset; purchase rules are monotone (§9) | Proven (definition) | Greedy purchase closure; blocked nodes explained by the rules failing at the fixpoint | Possibly stricter than the client for plans only reachable through staged refunds (§8) |
| **REFUND-1** removing a planned rank is allowed iff the remaining plan is valid; no cascade | `NodeOpFailureReason.RequiredForEdge` / `RequiredForCondition` (refusal, not cascade); the only cascade API is `CascadeRepurchaseRanks` — a *re*-purchase convenience whose history is disabled (`IsCascadeRepurchaseHistoryEnabled() → false`); retail UI "refund invalid" nodes block Apply (`TALENT_FRAME_REFUND_INVALID_ERROR`) | High | `DEPENDENT_NODE_WOULD_BREAK` (one issue per broken dependent rule, with `cause`) | In-game, committed ranks cannot be refunded at all (`CannotRefund`; live-observed on Paladin tree 1100, §15 L1/L3) — the engine refund is a plan edit (§8) |
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

**Active edge = fully ranked source**, identical for both kinds (EDGE-ACTIVE) — the M2
reference hypothesis. Deep Wounds with Improved Rend 2/3 is unavailable; at 3/3 it is
available (`warrior.sufficient_prerequisite_requires_max_rank`). The client computes
`isActive` / `meetsEdgeRequirements` in C++; the Lua UI only colours edges from those
results, so this rule is not proven until E1 is client-observed.

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
* Live support (§15 L5/L6, Paladin; not the prescribed E3): with 4 Holy points the
  5-point-gated Healing Light is unavailable although `meetsEdgeRequirements = true`; with 7
  Retribution points the 5-point-gated Conviction is available. The before-purchase
  evaluation and the capstone self-count (29 vs 30) remain unobserved.

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
else stops normalization with a message asking for a schema extension.

**Live client (§15 L2):** `C_Traits.GetNodeCost` on Paladin Deflection (105707, tree 1100)
returned one `TraitCurrencyCost` `{ID = 3820, amount = 1}` — the client's own composition of
all cost links agrees with the invariant for this tree and build.

**The other cost links are proven absent in the production pipeline, not assumed.**
`TraitNodeGroupXTraitCost`, `TraitNodeXTraitCost`, `TraitNodeEntryXTraitCost`
(`db2.tables.RANK_COST_EXTENSION_TABLES`) are probed through the source-neutral
`Db2Source.table_status(version, table)`, which answers `present` / `absent` / `unknown`:

* wago.tools: `present` if the CSV is cached or downloads; `absent` only on the definitive
  HTTP 4xx JSON body `{"errors": "Table not found."}` for a known build (an unknown build
  gets an HTML error page instead) — recorded with its evidence in the build's
  `_table_status.json`; network errors, 5xx, other 4xx → `unknown`. Offline: cache and
  recorded snapshot only, otherwise `unknown`.
* local CSV directories (fixtures, future extractors): CSV present → `present`; absence only
  if recorded in the directory's `_table_status.json`; a missing file proves nothing.

`TraitDataSet.load` records the three statuses; the normalizer refuses to publish unless
all three are `absent`. `present` — even an empty table, even one without rows for this
tree — fails with "build X introduces T … extend cost modeling/schema before publishing";
`unknown` fails with "cannot prove that T is absent". `fetch` probes them and exits
non-zero if any is not proven absent; the committed fixtures carry the snapshot of both
captured builds. Tests: `tests/unit/test_cost_invariant.py`,
`tests/unit/test_table_status.py`; the live `test_latest_build_has_no_node_level_cost_tables`
is an additional check through the same production probe. Because the invariant is part of
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
  a sanity check). **Live client (§15 L1/L3, Paladin tree 1100):** `cannotRefund = true`,
  and the committed, fully purchased Deflection 5/5 reports `canRefundRank = false`.
* **Staged changes (not observable here):** before Apply, the client keeps a staged
  config (Undo = `RollbackConfig`). Whether a staged rank can be right-click unlearned in
  a `CannotRefund` tree is decided in C++ (`canRefundRank`). Still unobserved: §15 L3 is a
  committed rank, not a staged one.
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
* **Not frozen for M3.** The vectors encode the M2 reference hypothesis for EDGE-ACTIVE and
  EDGE-SUF. They become the Kotlin acceptance contract only after E1 and E2 are
  client-observed (§13); if an observation contradicts a rule, the engine, the affected
  scenarios and this document change first.

## 13. Kotlin handoff and in-game verification

### Milestone gate

**M3 MUST NOT start until at least E1 and E2 below are client-observed.** E3 and E6 should
be observed too. E1 and E2 have **not** been observed. §15 records a first set of live
observations (Paladin, level 20); none executes E1–E7 exactly as prescribed — they are
partial or Paladin analogues of E3, E5, E6 and E7 — and they leave EDGE-ACTIVE, EDGE-SUF and
Rogue 50984 at their current confidence. Record each observation (build, character/class,
exact state, API output) in this document and turn it into a conformance scenario; a
contradicting result blocks M3 until the engine and vectors are corrected.

| Check | Gate |
| --- | --- |
| E1 (EDGE-ACTIVE) | **required** before M3 |
| E2 (EDGE-SUF, Hunter cycle) | **required** before M3 |
| E3 (gate before purchase), E6 (refund) | strongly recommended before M3 |
| E4 (Rogue 50984), E5 (cost), E7 (budget) | recommended; E4 unblocks Venom |

### Contract the KMP engine must reproduce (once the gate is passed)

On `talent-data.v1` documents:

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

Client API mapping (§15 L8): `canSpend(...).allowed` corresponds to the UI-level purchase
check (eligibility **and** affordability). The client's `TraitNodeInfo.canPurchaseRank` /
`C_Traits.CanPurchaseRank` is eligibility only (observed `true` with 0 currency left); a
Kotlin/UI layer must not present it as "the user has enough points".

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

* Live client verification is partial (§15: Paladin, level 20). COST-1, BUDGET-1's runtime
  limit, GATE-1's quantity and the committed-tree part of REFUND-1 have live support;
  EDGE-ACTIVE and EDGE-SUF (High — hypothesis), Rogue 50984, the before-purchase gate
  timing, staged refunds and the level-60 budget are not observed. §13 lists the checks.
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
* Caches fetched before this check existed have no `_table_status.json`: offline
  normalization of them fails closed until `fetch` is re-run online.
* `CannotRefund` is not part of the v1 document; a mobile UI must know that in-game
  refunds need a trainer reset (candidate for a future contract revision).
* `required`/`actual` numbers in validation issues are computed at the purchase-closure
  fixpoint (the explanation), not as raw totals of the imported build.
* Absence of the per-group/node/entry cost tables is wago.tools' statement for the build
  (the best available origin); a different extractor would record its own evidence.

## 15. Live client observations (WoW: Forever 1.60.1.70009)

The first client-observed evidence for this document, recorded after M3A. It is
**partial**: none of the prescribed checks E1–E7 (§13) was executed exactly as written, and
no confidence label in §2 changes because of it.

| Field | Value |
| --- | --- |
| Game | WoW: Forever |
| Client build | 1.60.1.70009 — exactly the M2/M3A baseline (no build drift) |
| Class / level | Paladin, level 20 |
| Tree | 1100 (Paladin) |
| Config | active config ID `2589343` (written `c` below) |
| Method | `C_Traits` calls printed from the running client; where the printed line was recorded verbatim it is quoted |
| Talent state | 11 points spent: 4 Holy, 7 Retribution (incl. Deflection 5/5); Protection 0 (derived: 4 + 7 = 11 = `spentInTree`) |

Evidence classes, kept apart:

* **Client-observed** — values the live client returned for the state above (L1–L8).
* **UI source** — Blizzard Lua of this exact build (§1.2), used only to interpret L8.
* **Dataset/DB2** — node names, ranks, gates and edges quoted below were checked against
  the committed `data/normalized/forever/1.60.1.70009/paladin.json`; they are not
  client-observed.
* **Hypothesis / not observed** — listed at the end of this section.

| # | Call | Observed | Exact output | Bears on |
| --- | --- | --- | --- | --- |
| L1 | `C_Traits.GetTreeInfo(c, 1100).cannotRefund` | `true` | not recorded verbatim | REFUND-1 game state (§8) |
| L2 | `C_Traits.GetNodeCost(c, 105707)` — Deflection (Retribution row 0, max 5) | one `TraitCurrencyCost`: `ID = 3820`, `amount = 1` | `COST 1 3820 1` | COST-1 (§7) |
| L3 | `C_Traits.GetNodeInfo(c, 105707)` — Deflection, committed 5/5 | `activeRank = 5`, `canRefundRank = false`, `ranksPurchased = 5`, `ranksIncreased = 0` | `REFUND 5 False 5 0` | REFUND-1 game state (§8) |
| L4 | `GetNodeInfo(c, 105626)` Redoubt (Protection row 0, max 5); `GetNodeInfo(c, 110874)` Shield Specialization (Protection row 2; one Sufficient edge from Redoubt; 10-point Protection gate) | Redoubt `activeRank = 0`, `maxRanks = 5`; Shield Specialization `meetsEdgeRequirements = false`, `isAvailable = false`, `canPurchaseRank = false` | `RED 0 5 SHIELD false false false` | baseline only (see below) |
| L5 | `GetNodeInfo(c, 105333)` Healing Light (Holy row 1; 5-point Holy gate; no prerequisite edge); 4 Holy points spent | `activeRank = 0`, `meetsEdgeRequirements = true`, `isAvailable = false`, `canPurchaseRank = false` | not recorded verbatim | GATE-1 (§5) |
| L6 | `GetNodeInfo(c, 105703)` Conviction (Retribution row 1; 5-point Retribution gate; no prerequisite edge); 7 Retribution points spent | `activeRank = 0`, `meetsEdgeRequirements = true`, `isAvailable = true`, `canPurchaseRank = true` | not recorded verbatim | GATE-1 (§5) |
| L7 | `C_Traits.GetTreeCurrencyInfo(c, 1100, false)` | one entry: `traitCurrencyID = 3820`, `quantity = 0`, `maxQuantity = 11`, `spent = 11`, `spentInTree = 11` | `CUR 1 3820 0 11 11 11` | BUDGET-1 (§2) |
| L8 | `GetNodeInfo(c, 105703).canPurchaseRank`; `C_Traits.CanPurchaseRank(c, 105703, 130432)` (Conviction, entry 130432) — with L7 `quantity = 0` | both `true` | not recorded verbatim | eligibility vs affordability (below) |

In the verbatim lines the first number after `COST` / `CUR` is the length of the returned
list (one cost, one currency entry).

**Cost (L2).** Direct client evidence for COST-1 on tree 1100 of this build: a Deflection
rank costs exactly 1 point of currency 3820, and the client's own cost composition
(group + node + entry + tree links) adds nothing else. COST-1 was already Proven from DB2
(§7); L2 is an independent confirmation from the client. E5 names "any node", so its
expected result is observed here on a Paladin node; the Warrior node written in E5 was
not queried, and the other eight trees rest on the DB2 evidence alone.

**Committed-tree refund (L1, L3).** The client reports tree 1100 as non-refundable and
refuses a rank refund on a committed, fully purchased node (`ranksPurchased = 5`, no
increased ranks). This is direct live evidence for the game-state half of REFUND-1:
committed ranks in a Forever class tree cannot be refunded one by one. It says nothing
about the engine's REFUND-1 rule, which edits an uncommitted *plan* (§8) and is unchanged.
Staged purchases were not tested, so whether a staged rank can be unlearned before Apply —
the second half of E6 — is still unobserved. E6 names Warrior tree 1117; L1/L3 are the
Paladin analogue of its first half.

**Protection baseline (L4).** Shield Specialization is unavailable while its only
prerequisite (Redoubt) is 0/5 *and* its 10-point Protection gate is unmet (0 Protection
points). With both unsatisfied, and a source at 0 ranks that every reading treats as
inactive, the observation cannot separate "edge active at max rank" from any weaker
reading. **It is not evidence for EDGE-ACTIVE** (nor for EDGE-SUF: a single Sufficient
edge, where ANY = ALL). It is recorded only as a baseline.

**Branch gate (L5, L6).** Neither node has a prerequisite edge and the client reports
`meetsEdgeRequirements = true` for both, so the gate is isolated from edge rules. With 4
Holy points (so at most 4 in its counted row-0 nodes) the 5-point-gated Healing Light is
unavailable; with 7 Retribution points (Deflection 5/5 is one of Conviction's two counted
row-0 nodes) the 5-point-gated Conviction is available and purchasable. The 11 points spent tree-wide
did not unlock Healing Light, consistent with a per-tab count. This is live support for
GATE-1's quantity reading (points spent in the counted nodes ≥ `requiredPoints`). It does
not establish that the gate is evaluated before the purchase, the capstone self-count, or
any threshold other than 5: E3 (Warrior, 29 → 30 Arms points) was not performed and there
was no before/after transition on a single gate.

**Runtime currency (L7).** At level 20 the client sources 11 points (`maxQuantity = 11`),
all of them spent (`quantity = 0`, `spent = spentInTree = 11`). This is a runtime,
level-dependent limit and **not** the tree budget: the dataset's `pointBudget.maxPoints =
51` (`TraitCurrency.SourcedMax`, BUDGET-1) is unchanged and must not be replaced by 11. It
is live support for BUDGET-1's split between the full dataset maximum and a lower limit
supplied by the caller (`point_limit` in the engine; a level-based limit is Leveling
Planner scope). 11 is consistent with the DB2 `TraitCurrencySource` rows "Level 10…60"
(levels 10–20 = 11); whether the Legacy "Talented" sources applied to this character was not
checked. E7 (level 60, `maxQuantity = 51`) was not observed; L7 does not substitute for it.

**Purchase eligibility vs affordability (L8; client + UI source).** With `quantity = 0`
the client still reports Conviction purchasable through both `GetNodeInfo(...).canPurchaseRank`
and `C_Traits.CanPurchaseRank`. The Blizzard UI of this build (`Gethe/wow-ui-source`
`bd2470ae`, §1.2) adds affordability on its own side:
`TalentButtonSpendMixin:CanPurchaseRank()` returns
`nodeInfo.canPurchaseRank and self:CanAfford()` plus inspect/lock checks
(`Blizzard_TalentButtonSpend.lua` l. 35–37); `CanAfford()` calls the frame's
`CanAfford(GetTraitCurrenciesCost())` (`Blizzard_TalentButtonBase.lua` l. 343–350,
221–229), which fails when the tree currency's `quantity` is below a cost's `amount`
(`Blizzard_SharedTalentFrame.lua` l. 1816–1825; costs from `C_Traits.GetNodeCost`,
l. 1805–1807; currencies from `C_Traits.GetTreeCurrencyInfo`, l. 1359). Therefore:

* the API / `TraitNodeInfo` field `canPurchaseRank` expresses structural purchase
  eligibility; observed here, it does **not** include affordability;
* actual purchase availability in the UI = eligibility **and** affordability (tree
  currency `quantity` ≥ the `GetNodeCost` amount);
* the reference engine already models both separately: rule issues (gates, edges, max
  rank, fail-closed) and `NO_POINTS_REMAINING` (budget or `point_limit` exhausted) are
  independent issues of `can_spend`, whose `allowed` needs neither. `can_spend(...).allowed`
  therefore corresponds to the UI-level check, not to raw `canPurchaseRank`. No engine or
  conformance change follows;
* for future Kotlin/UI code: `canPurchaseRank = true` must not be read as "the user
  currently has enough talent currency"; eligibility and remaining points stay distinct
  states and reasons.

**Not observed — confidence unchanged**

| Item | Status after these observations |
| --- | --- |
| EDGE-ACTIVE (E1) | High — hypothesis. The 4/5 → 5/5 prerequisite experiment was not performed (it needed a talent reset). L4 does not bear on it. |
| EDGE-SUF (E2) | High — hypothesis. No Paladin node has more than one Sufficient prerequisite (§3), and no Hunter was used for the E2 check. |
| Rogue cond 50984 (E4) | Unresolved; not observed. Venom stays `UNRESOLVED_CONDITION`. |
| E3 | Not performed as prescribed; L5/L6 are a Paladin 5-point-gate analogue on two different tabs, not a 29 → 30 transition. |
| E5 | Expected result observed on a Paladin node (L2). |
| E6 | Partial Paladin analogue (L1, L3: committed ranks only); staged refund not observed. |
| E7 | Not observed; L7 is a level-20 runtime value. |

No observation contradicts a conformance scenario (L2 agrees with COST-1, L5/L6 with
GATE-1, L7 with the budget/limit split, L8 with the separate `NO_POINTS_REMAINING`), so
`allocation.v1` is unchanged. These observations are not yet encoded as client-observed
conformance scenarios.
