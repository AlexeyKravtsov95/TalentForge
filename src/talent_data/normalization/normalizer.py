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

# TraitNode.Type values, as documented in WoWDBDefs (TraitNode.dbd).
NODE_TYPES = {0: "single", 1: "tiered", 2: "selection"}

# TraitEdge.Type values, as documented in WoWDBDefs (TraitEdge.dbd):
#   0 VisualOnly, 1 DeprecatedRankConnection, 2 SufficientForAvailability,
#   3 RequiredForAvailability, 4 MutuallyExclusive, 5 DeprecatedSelectionOption
EDGE_VISUAL_ONLY = 0
EDGE_PREREQUISITE_KINDS: dict[int, PrerequisiteKind] = {2: "sufficient", 3: "required"}

# TraitCond.CondType 0 = Available (WoWDBDefs). Only this type, with no flags set, is
# interpreted by M0 (as a "spend N points in node group G" gate).
COND_TYPE_AVAILABLE = 0

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

    gates = {n.id: _node_gates(ds, n.id, node_ids) for n in raw_nodes}
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
        point_budget=_point_budget(ds, tree_id),
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


# --------------------------------------------------------------------------- gates / sections

_Gates = tuple[list[UnlockRequirement], list[int]]


def _node_gates(ds: TraitDataSet, node_id: int, tree_nodes: set[int]) -> _Gates:
    cond_ids: set[int] = set(ds.node_conds.get(node_id, []))
    for group_id in ds.node_groups.get(node_id, []):
        cond_ids.update(ds.group_conds.get(group_id, []))

    requirements: list[UnlockRequirement] = []
    unresolved: list[int] = []
    for cond_id in sorted(cond_ids):
        cond = ds.conds.get(cond_id)
        if cond is None:
            raise NormalizationError(f"TraitNode {node_id} references unknown TraitCond {cond_id}")
        counted = ds.group_members.get(cond.trait_node_group_id, set())
        if (
            cond.cond_type == COND_TYPE_AVAILABLE
            and cond.flags == 0
            and cond.spent_amount_required > 0
            and counted
            and cond.required_level == 0
            and cond.quest_id == 0
            and cond.achievement_id == 0
            and cond.spec_set_id == 0
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
    if they partition the tree's nodes. Otherwise sections stay empty (unresolved).
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
    if covered != all_ids:
        return []
    by_x = {n.id: n.pos_x for n in nodes}
    ordered = sorted(maximal, key=lambda g: (min(by_x[i] for i in g), min(g)))
    return [Section(index=i, name=None, node_ids=sorted(group)) for i, group in enumerate(ordered)]


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
