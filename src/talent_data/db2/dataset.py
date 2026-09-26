"""Indexed, typed snapshot of all trait-related DB2 tables of one client build."""

from __future__ import annotations

import logging
from collections import defaultdict
from dataclasses import dataclass

from talent_data.db2 import tables as t
from talent_data.sources.base import Db2Source, SourceError
from talent_data.versions import ClientVersion

log = logging.getLogger(__name__)

# SpellEffect rows exist per difficulty; DifficultyID 0 is the difficulty-independent default
# row, which is what player talents use.
DEFAULT_DIFFICULTY_ID = 0


@dataclass(frozen=True)
class TraitDataSet:
    version: ClientVersion
    classes: dict[int, t.ChrClass]
    skill_class_masks: dict[int, list[int]]  # SkillID -> ClassMask values
    skill_line_trees: list[t.SkillLineXTraitTree]
    trees: dict[int, t.TraitTree]
    tree_currencies: dict[int, list[t.TraitTreeXTraitCurrency]]
    currencies: dict[int, t.TraitCurrency]
    tree_costs: dict[int, list[t.TraitTreeXTraitCost]]  # by TraitTreeID
    costs: dict[int, t.TraitCost]
    nodes_by_tree: dict[int, list[t.TraitNode]]
    node_entry_links: dict[int, list[t.TraitNodeXTraitNodeEntry]]  # by TraitNodeID
    entries: dict[int, t.TraitNodeEntry]
    definitions: dict[int, t.TraitDefinition]
    edges: list[t.TraitEdge]
    group_members: dict[int, set[int]]  # TraitNodeGroupID -> TraitNodeIDs
    node_groups: dict[int, list[int]]  # TraitNodeID -> TraitNodeGroupIDs
    group_conds: dict[int, list[int]]  # TraitNodeGroupID -> TraitCondIDs
    node_conds: dict[int, list[int]]  # TraitNodeID -> TraitCondIDs
    conds: dict[int, t.TraitCond]
    effect_points: dict[int, list[t.TraitDefinitionEffectPoints]]  # by TraitDefinitionID
    curves: dict[int, t.Curve]
    curve_points: dict[int, list[t.CurvePoint]]  # by CurveID, sorted by OrderIndex
    spells: dict[int, t.Spell]
    spell_names: dict[int, str]
    spell_effects: dict[int, list[t.SpellEffect]]  # by SpellID, default difficulty only

    @classmethod
    def load(cls, source: Db2Source, version: ClientVersion) -> TraitDataSet:
        def rows[R: t.Db2Record](record: type[R]) -> list[R]:
            return t.parse_table(source.load_table(version, record.TABLE), record.parse)

        def unique[R](items: list[R], key: str, table: str) -> dict[int, R]:
            out: dict[int, R] = {}
            for item in items:
                k = getattr(item, key)
                if k in out:
                    raise SourceError(f"{table}: duplicate {key}={k}")
                out[k] = item
            return out

        skill_masks: dict[int, list[int]] = defaultdict(list)
        for info in rows(t.SkillRaceClassInfo):
            skill_masks[info.skill_id].append(info.class_mask)

        tree_currencies: dict[int, list[t.TraitTreeXTraitCurrency]] = defaultdict(list)
        for currency_link in rows(t.TraitTreeXTraitCurrency):
            tree_currencies[currency_link.trait_tree_id].append(currency_link)

        tree_costs: dict[int, list[t.TraitTreeXTraitCost]] = defaultdict(list)
        for cost_link in rows(t.TraitTreeXTraitCost):
            tree_costs[cost_link.trait_tree_id].append(cost_link)

        nodes_by_tree: dict[int, list[t.TraitNode]] = defaultdict(list)
        all_nodes = rows(t.TraitNode)
        unique(all_nodes, "id", "TraitNode")
        for node in all_nodes:
            nodes_by_tree[node.trait_tree_id].append(node)

        node_entry_links: dict[int, list[t.TraitNodeXTraitNodeEntry]] = defaultdict(list)
        for link in rows(t.TraitNodeXTraitNodeEntry):
            node_entry_links[link.trait_node_id].append(link)

        group_members: dict[int, set[int]] = defaultdict(set)
        node_groups: dict[int, list[int]] = defaultdict(list)
        for member in rows(t.TraitNodeGroupXTraitNode):
            group_members[member.trait_node_group_id].add(member.trait_node_id)
            node_groups[member.trait_node_id].append(member.trait_node_group_id)

        group_conds: dict[int, list[int]] = defaultdict(list)
        for gc in rows(t.TraitNodeGroupXTraitCond):
            group_conds[gc.trait_node_group_id].append(gc.trait_cond_id)
        node_conds: dict[int, list[int]] = defaultdict(list)
        for nc in rows(t.TraitNodeXTraitCond):
            node_conds[nc.trait_node_id].append(nc.trait_cond_id)

        effect_points: dict[int, list[t.TraitDefinitionEffectPoints]] = defaultdict(list)
        for ep in rows(t.TraitDefinitionEffectPoints):
            effect_points[ep.trait_definition_id].append(ep)

        curve_points: dict[int, list[t.CurvePoint]] = defaultdict(list)
        for cp in rows(t.CurvePoint):
            curve_points[cp.curve_id].append(cp)
        for points in curve_points.values():
            points.sort(key=lambda p: (p.order_index, p.id))

        spell_effects: dict[int, list[t.SpellEffect]] = defaultdict(list)
        for effect in rows(t.SpellEffect):
            if effect.difficulty_id == DEFAULT_DIFFICULTY_ID:
                spell_effects[effect.spell_id].append(effect)
        for effects in spell_effects.values():
            effects.sort(key=lambda e: (e.effect_index, e.id))

        spell_names = {row.id: row.name for row in rows(t.SpellName)}

        dataset = cls(
            version=version,
            classes=unique(rows(t.ChrClass), "id", "ChrClasses"),
            skill_class_masks=dict(skill_masks),
            skill_line_trees=rows(t.SkillLineXTraitTree),
            trees=unique(rows(t.TraitTree), "id", "TraitTree"),
            tree_currencies=dict(tree_currencies),
            currencies=unique(rows(t.TraitCurrency), "id", "TraitCurrency"),
            tree_costs=dict(tree_costs),
            costs=unique(rows(t.TraitCost), "id", "TraitCost"),
            nodes_by_tree=dict(nodes_by_tree),
            node_entry_links=dict(node_entry_links),
            entries=unique(rows(t.TraitNodeEntry), "id", "TraitNodeEntry"),
            definitions=unique(rows(t.TraitDefinition), "id", "TraitDefinition"),
            edges=rows(t.TraitEdge),
            group_members=dict(group_members),
            node_groups=dict(node_groups),
            group_conds=dict(group_conds),
            node_conds=dict(node_conds),
            conds=unique(rows(t.TraitCond), "id", "TraitCond"),
            effect_points=dict(effect_points),
            curves=unique(rows(t.Curve), "id", "Curve"),
            curve_points=dict(curve_points),
            spells=unique(rows(t.Spell), "id", "Spell"),
            spell_names=spell_names,
            spell_effects=dict(spell_effects),
        )
        log.debug("loaded trait dataset for %s", version)
        return dataset
