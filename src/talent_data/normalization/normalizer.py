"""Raw DB2 rows -> normalized :class:`TalentDataDocument`.

Join chain (see docs/data-pipeline.md for the evidence):

    TraitTree.ID
      <- TraitNode.TraitTreeID                          (nodes, PosX/PosY, Type, Flags)
      <- TraitNodeXTraitNodeEntry.TraitNodeID           (-> TraitNodeEntryID, ordered by Index)
      -> TraitNodeEntry.ID                              (MaxRanks, TraitDefinitionID)
      -> TraitDefinition.ID                             (SpellID, override name/description)
      -> SpellName.ID / Spell.ID                        (name, tooltip template)
    TraitDefinitionEffectPoints.TraitDefinitionID       (EffectIndex, CurveID)
      -> CurvePoint.CurveID                             (Pos_0 = rank, Pos_1 = value)
    TraitEdge.LeftTraitNodeID -> RightTraitNodeID       (left is the prerequisite of right)
    TraitNodeGroupXTraitNode / TraitNodeGroupXTraitCond / TraitNodeXTraitCond -> TraitCond
                                                        (tier gates: "spend N points in group")
"""

from __future__ import annotations

import math
from datetime import UTC, datetime

from talent_data.db2 import tables as t
from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import TRAIT_TABLES
from talent_data.models.talents import (
    ClassRef,
    Effect,
    Layout,
    Number,
    PerRankStatus,
    PointBudget,
    Position,
    Prerequisite,
    PrerequisiteKind,
    Provenance,
    RankValue,
    Section,
    TalentDataDocument,
    TalentEntry,
    TalentNode,
    TalentTree,
    UnlockRequirement,
    VisualEdge,
    content_hash,
    semantic_payload,
)
from talent_data.normalization.class_resolution import resolve_class, trees_for_class
from talent_data.normalization.grid import fit_grid
from talent_data.sources.base import TableStatus

# TraitNode.Type values, as documented in WoWDBDefs (TraitNode.dbd).
NODE_TYPES = {0: "single", 1: "tiered", 2: "selection"}

# TraitEdge.Type values, as documented in WoWDBDefs (TraitEdge.dbd):
#   0 VisualOnly, 1 DeprecatedRankConnection, 2 SufficientForAvailability,
#   3 RequiredForAvailability, 4 MutuallyExclusive, 5 DeprecatedSelectionOption
EDGE_VISUAL_ONLY = 0
EDGE_PREREQUISITE_KINDS: dict[int, PrerequisiteKind] = {2: "sufficient", 3: "required"}

# TraitCond.CondType 0 = Available (WoWDBDefs: "0: Available, 1: Visible, 2: Granted,
# 3: Increased, 4: DisplayError"). Only this type, with no flags set, is interpreted
# (as a "spend N points in node group G" gate).
COND_TYPE_AVAILABLE = 0


def _is_pure_points_gate(cond: t.TraitCond) -> bool:
    """True when the row expresses *exactly* "spend SpentAmountRequired of TraitCurrencyID
    points in TraitNodeGroupID" and nothing else.

    Any other functional field set (node, node entry, account element, level, granted
    ranks, quest, achievement, spec set, flags) makes the condition shape something we
    have not proven, so it must surface as unresolved rather than be silently flattened
    into a plain points gate. Evidence that the extra fields are functional, not dead
    columns (researched for M1 review, retail build 12.1.0.69933): ``TraitNodeEntryID``
    is non-zero on 25 conditions (gating on one specific node entry) and
    ``TraitCondAccountElementID`` on 413 (account-wide gating via the
    TraitCondAccountElement table). ``TraitTreeID`` is excluded because real Forever
    gates (e.g. Druid 50973) also carry it: it scopes a condition to its tree.
    """
    return (
        cond.cond_type == COND_TYPE_AVAILABLE
        and cond.flags == 0
        and cond.trait_node_id == 0
        and cond.trait_node_entry_id == 0
        and cond.trait_cond_account_element_id == 0
        and cond.required_level == 0
        and cond.granted_ranks == 0
        and cond.quest_id == 0
        and cond.achievement_id == 0
        and cond.spec_set_id == 0
    )


def _is_vacuous_cond(cond: t.TraitCond) -> bool:
    """A *proven empty* condition: a pure-points-gate shape (see _is_pure_points_gate)
    with every constraint field - including the group, currency and spent amount - zero.

    Such a row cannot gate anything: every possible reading ("spend >= 0", "level >= 0",
    no quest, ...) is trivially satisfied. ``FreeSharedStringID`` /
    ``SpendMoreSharedStringID`` are deliberately NOT part of the judgement: they are
    ``SharedString::ID`` localization texts rendered by the client next to a gate
    (real 12.1.0 strings: 910 "This talent is granted to your specialization for free
    and can't be unlearned", 911 "Spend %d more |4point:points; to unlock this talent"),
    i.e. presentation, not condition semantics - proven by the FK target being a string
    table and by real Forever gates carrying the same string ids as their vacuous
    neighbours. Real Forever vacuous rows: Priest cond 43478 (group 11608, whole
    Discipline tab) and Druid cond 50974 (next to the real gate 50973 on group 11329);
    all their fields are zero. See docs/forever-dataset.md for the full evidence.
    """
    return (
        _is_pure_points_gate(cond)
        and cond.trait_node_group_id == 0
        and cond.trait_currency_id == 0
        and cond.spent_amount_required == 0
    )


# TraitDefinitionEffectPoints.OperationType whose curve we verified to hold the effect value
# per rank (see docs: curve points match SpellEffect base points and tooltips). Every
# effect-points row in Forever 1.60.1 builds uses this value; others are reported unresolved.
VERIFIED_OPERATION_TYPE = 0


class NormalizationError(ValueError):
    """Client data contradicts the assumptions the normalizer relies on."""


def normalize_class(
    dataset: TraitDataSet,
    *,
    game: str,
    class_key: str,
    source_name: str,
    generated_at: datetime | None = None,
) -> TalentDataDocument:
    chr_class = resolve_class(dataset, class_key)
    trees = [_build_tree(dataset, tree_id) for tree_id in trees_for_class(dataset, chr_class)]
    draft = {
        "schemaVersion": 1,
        "game": game,
        "clientBuild": str(dataset.version),
        "class": ClassRef(id=chr_class.id, token=chr_class.token, name=chr_class.name),
        "trees": trees,
    }
    # Hash is computed over the JSON form of the semantic payload only.
    probe = TalentDataDocument.model_validate(
        {
            **draft,
            "contentHash": "",
            "generatedAt": datetime(1970, 1, 1, tzinfo=UTC),
            "provenance": Provenance(source=source_name, tables=list(TRAIT_TABLES)),
        }
    )
    digest = content_hash(semantic_payload(probe.model_dump(mode="json", by_alias=True)))
    return probe.model_copy(
        update={
            "content_hash": digest,
            "generated_at": generated_at or datetime.now(UTC).replace(microsecond=0),
        }
    )


# --------------------------------------------------------------------------- tree


def _build_tree(ds: TraitDataSet, tree_id: int) -> TalentTree:
    raw_nodes = sorted(ds.nodes_by_tree.get(tree_id, []), key=lambda n: n.id)
    if not raw_nodes:
        raise NormalizationError(f"TraitTree {tree_id} has no TraitNode rows")
    node_ids = {n.id for n in raw_nodes}
    _check_no_sub_trees(ds, raw_nodes)

    point_budget = _point_budget(ds, tree_id)
    _check_rank_cost_invariant(ds, tree_id, point_budget)
    budget_currency = point_budget.currency_id if point_budget else None
    gates = {n.id: _node_gates(ds, n.id, node_ids, budget_currency) for n in raw_nodes}
    sections = _resolve_sections(ds, raw_nodes, gates)
    section_of = {nid: s.index for s in sections for nid in s.node_ids}

    grid = fit_grid((section_of.get(n.id), n.pos_x, n.pos_y) for n in raw_nodes)

    prerequisites, visual_edges = _edges(ds, tree_id, node_ids)

    nodes = []
    for raw in raw_nodes:
        section_index = section_of.get(raw.id)
        entries = _entries(ds, raw)
        requirements, unresolved = gates[raw.id]
        single = entries[0] if len(entries) == 1 else None
        nodes.append(
            TalentNode(
                id=raw.id,
                section_index=section_index,
                position=Position(
                    x=raw.pos_x,
                    y=raw.pos_y,
                    row=grid.row(raw.pos_y),
                    column=grid.column(section_index, raw.pos_x),
                ),
                type=NODE_TYPES.get(raw.type, f"unknown:{raw.type}"),
                flags=raw.flags,
                max_rank=max(e.max_rank for e in entries),
                spell_id=single.spell_id if single else None,
                name=single.name if single else None,
                prerequisites=sorted(prerequisites.get(raw.id, []), key=lambda p: p.node_id),
                unlock_requirements=requirements,
                unresolved_condition_ids=unresolved,
                entries=entries,
            )
        )
    nodes.sort(
        key=lambda n: (
            n.section_index if n.section_index is not None else math.inf,
            n.position.row,
            n.position.column,
            n.id,
        )
    )
    return TalentTree(
        id=tree_id,
        point_budget=point_budget,
        layout=Layout(x_step=next(iter(grid.columns.values())).step, y_step=grid.rows.step),
        sections=sections,
        nodes=nodes,
        visual_only_edges=visual_edges,
    )


def _point_budget(ds: TraitDataSet, tree_id: int) -> PointBudget | None:
    links = sorted(ds.tree_currencies.get(tree_id, []), key=lambda link: link.index)
    if not links:
        return None
    if len(links) > 1:
        raise NormalizationError(
            f"TraitTree {tree_id} has {len(links)} currencies; M0 supports exactly one"
        )
    currency = ds.currencies.get(links[0].trait_currency_id)
    if currency is None:
        raise NormalizationError(
            f"TraitTree {tree_id} references unknown TraitCurrency {links[0].trait_currency_id}"
        )
    return PointBudget(currency_id=currency.id, max_points=currency.sourced_max)


# --------------------------------------------------------------------------- costs

# The only rank price the v1 contract can express: every purchased rank of every node costs
# exactly this many units of the tree's point-budget currency (see _check_rank_cost_invariant
# and docs/talent-allocation-semantics.md, "Cost semantics").
RANK_COST_POINTS = 1


def _check_rank_cost_invariant(
    ds: TraitDataSet, tree_id: int, point_budget: PointBudget | None
) -> None:
    """Prove - do not assume - that one purchased rank costs exactly one talent point.

    The client prices a rank as the sum of the TraitCost rows linked to the node's tree
    (TraitTreeXTraitCost), node groups, node and entry (C_Traits.GetNodeCost returns that
    list). The tree link is the whole price only if the group/node/entry link tables are
    *proven absent* for this build by the source (_check_no_rank_cost_extensions).
    Schema v1 has no cost field, so anything but "exactly one TraitCost, Amount 1, in the
    budget currency, no cost curve" is a contract violation and stops normalization.
    """
    _check_no_rank_cost_extensions(ds)
    links = sorted(ds.tree_costs.get(tree_id, []), key=lambda link: link.id)
    if point_budget is None:
        if links:
            raise NormalizationError(
                f"TraitTree {tree_id} has rank costs but no point budget currency"
            )
        return
    if len(links) != 1:
        raise NormalizationError(
            f"TraitTree {tree_id} has {len(links)} TraitTreeXTraitCost rows; the v1 contract "
            f"requires exactly one (every rank costs {RANK_COST_POINTS} budget point)"
        )
    cost = ds.costs.get(links[0].trait_cost_id)
    if cost is None:
        raise NormalizationError(
            f"TraitTree {tree_id} references unknown TraitCost {links[0].trait_cost_id}"
        )
    if (cost.amount, cost.trait_currency_id, cost.curve_id) != (
        RANK_COST_POINTS,
        point_budget.currency_id,
        0,
    ):
        raise NormalizationError(
            f"TraitTree {tree_id}: TraitCost {cost.id} is Amount={cost.amount} "
            f"TraitCurrencyID={cost.trait_currency_id} CurveID={cost.curve_id}; the v1 contract "
            f"only supports Amount={RANK_COST_POINTS} of the budget currency "
            f"{point_budget.currency_id} without a cost curve - extend the schema first"
        )


def _check_no_rank_cost_extensions(ds: TraitDataSet) -> None:
    """Fail closed unless every per-group/node/entry cost table is proven absent.

    PRESENT (even if empty, even if this class tree has no rows in it): a cost mechanic
    talent-data.v1 cannot express - its schema/semantics must be reviewed first.
    UNKNOWN: the source could not prove absence (offline without a recorded snapshot,
    network failure, ...) - absence is never assumed.
    """
    for table in t.RANK_COST_EXTENSION_TABLES:
        availability = ds.table_status.get(table)
        status = availability.status if availability else TableStatus.UNKNOWN
        evidence = availability.evidence if availability else "not probed"
        if status is TableStatus.PRESENT:
            raise NormalizationError(
                f"build {ds.version} introduces {table} ({evidence}). talent-data.v1 cannot "
                "prove uniform one-point rank costs; extend cost modeling/schema before "
                "publishing this dataset"
            )
        if status is not TableStatus.ABSENT:
            raise NormalizationError(
                f"build {ds.version}: cannot prove that {table} is absent ({evidence}). "
                "talent-data.v1 requires proven absence of per-group/node/entry rank costs; "
                "refusing to assume one-point rank costs"
            )


def _check_no_sub_trees(ds: TraitDataSet, raw_nodes: list[t.TraitNode]) -> None:
    """Sub-trees (hero talents: TraitSubTree, SubTreeSelection nodes) do not exist in
    Forever (the TraitSubTree table is absent). A node or entry pointing at one would carry
    activation semantics the v1 contract cannot express."""
    for node in raw_nodes:
        if node.trait_sub_tree_id:
            raise NormalizationError(
                f"TraitNode {node.id} belongs to TraitSubTree {node.trait_sub_tree_id}; "
                "sub-trees are not supported by the v1 contract"
            )
        for link in ds.node_entry_links.get(node.id, []):
            entry = ds.entries.get(link.trait_node_entry_id)
            if entry is not None and entry.trait_sub_tree_id:
                raise NormalizationError(
                    f"TraitNodeEntry {entry.id} activates TraitSubTree "
                    f"{entry.trait_sub_tree_id}; sub-trees are not supported by the v1 contract"
                )


# --------------------------------------------------------------------------- gates / sections

_Gates = tuple[list[UnlockRequirement], list[int]]


def _node_gates(
    ds: TraitDataSet, node_id: int, tree_nodes: set[int], budget_currency: int | None
) -> _Gates:
    cond_ids: set[int] = set(ds.node_conds.get(node_id, []))
    for group_id in ds.node_groups.get(node_id, []):
        cond_ids.update(ds.group_conds.get(group_id, []))

    requirements: list[UnlockRequirement] = []
    unresolved: list[int] = []
    for cond_id in sorted(cond_ids):
        cond = ds.conds.get(cond_id)
        if cond is None:
            raise NormalizationError(f"TraitNode {node_id} references unknown TraitCond {cond_id}")
        if _is_vacuous_cond(cond):
            continue
        counted = ds.group_members.get(cond.trait_node_group_id, set())
        # The gate compares *currency spent* in the group (GetGroupCurrencyInfo "spent",
        # SharedString 911 "Spend %d more points in %s Talents"). v1 has no currency field on
        # UnlockRequirement, so only gates in the tree's own point currency are expressible.
        if (
            _is_pure_points_gate(cond)
            and cond.spent_amount_required > 0
            and counted
            and budget_currency is not None
            and cond.trait_currency_id == budget_currency
        ):
            outside = counted - tree_nodes
            if outside:
                raise NormalizationError(
                    f"TraitCond {cond_id} counts nodes outside the tree: {sorted(outside)}"
                )
            requirements.append(
                UnlockRequirement(
                    required_points=cond.spent_amount_required,
                    counted_node_ids=sorted(counted),
                )
            )
        else:
            # Anything else (other CondType, flags, or a functional field we have not
            # proven - node/entry/account-element/level/quest/achievement/spec-set
            # references) is surfaced as an unresolved condition, never silently ignored.
            unresolved.append(cond_id)
    requirements.sort(key=lambda r: (r.required_points, r.counted_node_ids))
    return requirements, unresolved


def _resolve_sections(
    ds: TraitDataSet, nodes: list[t.TraitNode], gates: dict[int, _Gates]
) -> list[Section]:
    """Classic talent tabs.

    Forever stores the three classic tabs of a class inside ONE TraitTree. The tab itself is
    not a column anywhere; it is the node group that the deepest tier gate counts ("spend 30
    points in <group>"). We take every node group referenced by a gate, keep the maximal
    ones (not a strict subset of another referenced group), and accept them as sections only
    if they are disjoint and cover the tree's nodes. Otherwise sections stay empty (unresolved).

    A node that no gate counts at all (since 1.60.1.70425: Mage Arcane Wrath 113627, whose
    four group rows point at single-node groups without a condition) joins the one tab whose
    columns contain its raw x - the panel the client draws it in. This is layout only: its
    gates and prerequisites stay exactly as the rows say, and the validator flags it
    (SECTION_NOT_GATE_COUNTED). If its x lies in no tab's column range or in more than one,
    sections stay unresolved; nothing is guessed.
    """
    all_ids = frozenset(n.id for n in nodes)
    referenced = {
        frozenset(req.counted_node_ids)
        for requirements, _ in gates.values()
        for req in requirements
    }
    maximal = [g for g in referenced if not any(g < other for other in referenced)]
    covered: set[int] = set()
    for group in maximal:
        if covered & group:
            return []
        covered |= group
    by_x = {n.id: n.pos_x for n in nodes}
    members = {group: set(group) for group in maximal}
    for node_id in sorted(all_ids - covered):
        containing = [
            group
            for group in maximal
            if min(by_x[i] for i in group) <= by_x[node_id] <= max(by_x[i] for i in group)
        ]
        if len(containing) != 1:
            return []
        members[containing[0]].add(node_id)
    if set().union(*members.values()) != all_ids:
        return []
    ordered = sorted(maximal, key=lambda g: (min(by_x[i] for i in g), min(g)))
    return [
        Section(index=i, name=None, node_ids=sorted(members[group]))
        for i, group in enumerate(ordered)
    ]


# --------------------------------------------------------------------------- edges


def _edges(
    ds: TraitDataSet, tree_id: int, node_ids: set[int]
) -> tuple[dict[int, list[Prerequisite]], list[VisualEdge]]:
    prerequisites: dict[int, list[Prerequisite]] = {}
    visual: list[VisualEdge] = []
    for edge in sorted(ds.edges, key=lambda e: e.id):
        left_in, right_in = (
            edge.left_trait_node_id in node_ids,
            edge.right_trait_node_id in node_ids,
        )
        if not (left_in or right_in):
            continue
        if left_in != right_in:
            raise NormalizationError(
                f"TraitEdge {edge.id} crosses TraitTree {tree_id} boundary "
                f"({edge.left_trait_node_id} -> {edge.right_trait_node_id})"
            )
        if edge.type == EDGE_VISUAL_ONLY:
            visual.append(
                VisualEdge(
                    from_node_id=edge.left_trait_node_id, to_node_id=edge.right_trait_node_id
                )
            )
            continue
        kind = EDGE_PREREQUISITE_KINDS.get(edge.type)
        if kind is None:
            raise NormalizationError(
                f"TraitEdge {edge.id} has Type={edge.type}, which M0 does not interpret "
                f"(supported: 0, {sorted(EDGE_PREREQUISITE_KINDS)})"
            )
        # Left node is the prerequisite of the right node (verified: Improved Rend ->
        # Deep Wounds, Sweeping Strikes -> Mortal Strike on Warrior 1.60.1.70009).
        prerequisites.setdefault(edge.right_trait_node_id, []).append(
            Prerequisite(node_id=edge.left_trait_node_id, kind=kind)
        )
    return prerequisites, visual


# --------------------------------------------------------------------------- entries


def _entries(ds: TraitDataSet, node: t.TraitNode) -> list[TalentEntry]:
    links = sorted(ds.node_entry_links.get(node.id, []), key=lambda x: (x.index, x.id))
    if not links:
        raise NormalizationError(f"TraitNode {node.id} has no TraitNodeXTraitNodeEntry rows")
    entries = []
    for link in links:
        entry = ds.entries.get(link.trait_node_entry_id)
        if entry is None:
            raise NormalizationError(
                f"TraitNode {node.id} links unknown TraitNodeEntry {link.trait_node_entry_id}"
            )
        definition = ds.definitions.get(entry.trait_definition_id)
        if definition is None:
            raise NormalizationError(
                f"TraitNodeEntry {entry.id} links unknown TraitDefinition "
                f"{entry.trait_definition_id}"
            )
        # VisibleSpellID, when set, is what the client shows in tooltips (WoWDBDefs).
        display_spell = definition.visible_spell_id or definition.spell_id
        name = definition.override_name or ds.spell_names.get(display_spell)
        spell = ds.spells.get(display_spell)
        description = definition.override_description or (spell.description if spell else None)
        entries.append(
            TalentEntry(
                id=entry.id,
                definition_id=definition.id,
                spell_id=definition.spell_id,
                name=name or None,
                max_rank=entry.max_ranks,
                entry_type=entry.node_entry_type,
                description_template=description or None,
                effects=_effects(ds, definition, entry.max_ranks),
            )
        )
    return entries


def _num(value: float) -> Number:
    return int(value) if float(value).is_integer() else value


def _effects(ds: TraitDataSet, definition: t.TraitDefinition, max_rank: int) -> list[Effect]:
    base_points = {
        e.effect_index: _num(e.base_points) for e in ds.spell_effects.get(definition.spell_id, [])
    }
    curves: dict[int, t.TraitDefinitionEffectPoints] = {}
    for row in ds.effect_points.get(definition.id, []):
        if row.effect_index in curves:
            raise NormalizationError(
                f"TraitDefinition {definition.id} has two effect-points rows for effect "
                f"{row.effect_index}"
            )
        curves[row.effect_index] = row

    effects = []
    for index in sorted(set(base_points) | set(curves)):
        ep = curves.get(index)
        per_rank: list[RankValue] | None = None
        status: PerRankStatus = "none"
        reason = None
        if ep is not None:
            if ep.operation_type != VERIFIED_OPERATION_TYPE:
                status = "unresolved"
                reason = (
                    f"TraitDefinitionEffectPoints.OperationType={ep.operation_type} "
                    "is not interpreted in M0"
                )
            else:
                per_rank, status = _curve_ranks(ds, ep.curve_id, max_rank)
        effects.append(
            Effect(
                index=index,
                per_rank=per_rank,
                per_rank_status=status,
                base_points=base_points.get(index),
                unresolved_reason=reason,
            )
        )
    return effects


def _curve_ranks(
    ds: TraitDataSet, curve_id: int, max_rank: int
) -> tuple[list[RankValue] | None, PerRankStatus]:
    if curve_id not in ds.curves:
        return None, "unresolved"
    points = ds.curve_points.get(curve_id, [])
    if not points:
        return None, "incomplete"
    values: dict[int, Number] = {}
    for p in points:
        if not p.x.is_integer():
            # A non-integer X means this is not a rank curve; do not invent a mapping.
            return None, "unresolved"
        rank = int(p.x)
        if rank in values and values[rank] != _num(p.y):
            return None, "unresolved"
        values[rank] = _num(p.y)
    # Only ranks the talent can actually reach are part of the contract. Some curves carry
    # extra points beyond MaxRanks (e.g. single-rank talents reusing a 3-point curve).
    per_rank = [RankValue(rank=r, value=values[r]) for r in sorted(values) if 1 <= r <= max_rank]
    complete = all(r in values for r in range(1, max_rank + 1))
    return per_rank, ("resolved" if complete else "incomplete")
