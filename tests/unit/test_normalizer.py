"""Normalization of the real Warrior tree (Forever 1.60.1.70009 fixture rows)."""

from __future__ import annotations

import dataclasses

import pytest

from talent_data.db2 import tables as t
from talent_data.db2.dataset import TraitDataSet
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.class_resolution import (
    ClassResolutionError,
    resolve_class,
    trees_for_class,
)
from talent_data.normalization.normalizer import NormalizationError
from tests.conftest import normalize_warrior
from tests.helpers import node_by_name


def test_warrior_resolves_to_trait_tree_1117(dataset_new: TraitDataSet) -> None:
    warrior = resolve_class(dataset_new, "warrior")
    assert (warrior.id, warrior.token, warrior.name) == (1, "WARRIOR", "Warrior")
    assert trees_for_class(dataset_new, warrior) == [1117]


def test_class_lookup_accepts_token_name_or_id(dataset_new: TraitDataSet) -> None:
    assert resolve_class(dataset_new, "WARRIOR").id == 1
    assert resolve_class(dataset_new, "1").id == 1
    with pytest.raises(ClassResolutionError, match="available"):
        resolve_class(dataset_new, "deathknight")


def test_class_without_tree_rows_in_dataset_is_an_error(dataset_new: TraitDataSet) -> None:
    # The fixture only contains TraitTree 1117; Mage's tree row is (deliberately) absent.
    with pytest.raises(ClassResolutionError, match="unknown TraitTree"):
        trees_for_class(dataset_new, resolve_class(dataset_new, "mage"))


def test_document_header(warrior_new: TalentDataDocument) -> None:
    assert warrior_new.schema_version == 1
    assert warrior_new.game == "forever"
    assert warrior_new.client_build == "1.60.1.70009"
    assert warrior_new.class_.token == "WARRIOR"
    assert [t.id for t in warrior_new.trees] == [1117]


def test_tree_shape(warrior_new: TalentDataDocument) -> None:
    tree = warrior_new.trees[0]
    assert len(tree.nodes) == 53
    assert [len(s.node_ids) for s in tree.sections] == [17, 18, 18]
    assert tree.point_budget is not None
    assert tree.point_budget.max_points == 51
    assert (tree.layout.x_step, tree.layout.y_step) == (600, 600)
    assert sum(n.max_rank for n in tree.nodes) == 154
    assert all(len(n.entries) == 1 and n.type == "single" for n in tree.nodes)


def test_ranks_and_per_rank_values(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Improved Heroic Strike")
    assert node.max_rank == 3
    effect = node.entries[0].effects[0]
    assert effect.per_rank_status == "resolved"
    assert [(r.rank, r.value) for r in effect.per_rank or []] == [(1, -10), (2, -20), (3, -30)]


def test_per_rank_values_come_from_curve_not_spell_effect(warrior_new: TalentDataDocument) -> None:
    # Deep Wounds: SpellEffect base points are 0, the rank curve holds 20/40/60.
    effect = node_by_name(warrior_new, "Deep Wounds").entries[0].effects[0]
    assert effect.base_points == 0
    assert [r.value for r in effect.per_rank or []] == [20, 40, 60]


def test_single_rank_talent_has_static_effects_only(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Mortal Strike")
    assert node.max_rank == 1
    effects = node.entries[0].effects
    assert [(e.index, e.per_rank_status, e.base_points) for e in effects] == [
        (0, "none", -50),
        (1, "none", 85),
    ]


def test_curve_points_beyond_max_rank_are_not_exposed(warrior_new: TalentDataDocument) -> None:
    # Last Stand is 1 rank but its curve carries 3 points (5/10/15) in the client data.
    effect = node_by_name(warrior_new, "Last Stand").entries[0].effects[0]
    assert [(r.rank, r.value) for r in effect.per_rank or []] == [(1, 5)]
    assert effect.per_rank_status == "resolved"


def test_prerequisites(warrior_new: TalentDataDocument) -> None:
    def prereq_names(name: str) -> list[str | None]:
        node = node_by_name(warrior_new, name)
        ids = [p.node_id for p in node.prerequisites]
        by_id = {n.id: n.name for n in warrior_new.trees[0].nodes}
        return [by_id[i] for i in ids]

    assert prereq_names("Deep Wounds") == ["Improved Rend"]
    assert prereq_names("Mortal Strike") == ["Sweeping Strikes"]
    assert prereq_names("Bloodthirst") == ["Death Wish"]
    assert prereq_names("Shield Slam") == ["Concussion Blow"]
    total = sum(len(n.prerequisites) for n in warrior_new.trees[0].nodes)
    assert total == 8
    kinds = {p.kind for n in warrior_new.trees[0].nodes for p in n.prerequisites}
    assert kinds == {"sufficient"}


def test_positions_and_grid(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    assert (ms.position.x, ms.position.y) == (1620, 5730)
    assert (ms.section_index, ms.position.row, ms.position.column) == (0, 6, 1)
    shield_slam = node_by_name(warrior_new, "Shield Slam")
    assert (shield_slam.section_index, shield_slam.position.row) == (2, 6)


def test_tier_gates(warrior_new: TalentDataDocument) -> None:
    tree = warrior_new.trees[0]
    first_row = [n for n in tree.nodes if n.position.row == 0]
    assert first_row and all(not n.unlock_requirements for n in first_row)

    ms = node_by_name(warrior_new, "Mortal Strike")
    [gate] = ms.unlock_requirements
    assert gate.required_points == 30
    assert set(gate.counted_node_ids) == set(tree.sections[0].node_ids)

    deep_wounds = node_by_name(warrior_new, "Deep Wounds")  # row 2 -> 10 points in rows 0-1
    [gate] = deep_wounds.unlock_requirements
    assert gate.required_points == 10
    counted_rows = {n.position.row for n in tree.nodes if n.id in gate.counted_node_ids}
    assert counted_rows == {0, 1}
    assert all(not n.unresolved_condition_ids for n in tree.nodes)


def test_names_and_tooltip_templates_are_raw(warrior_new: TalentDataDocument) -> None:
    entry = node_by_name(warrior_new, "Improved Heroic Strike").entries[0]
    assert entry.spell_id == 12282
    assert entry.description_template == (
        "Reduces the cost of your Heroic Strike ability by $/10;s1 Rage."
    )


def test_normalization_is_deterministic(dataset_new: TraitDataSet) -> None:
    first = normalize_warrior(dataset_new)
    second = normalize_warrior(dataset_new)
    assert first.to_json() == second.to_json()
    assert first.content_hash == second.content_hash


def test_generated_at_does_not_affect_semantic_payload(dataset_new: TraitDataSet) -> None:
    from datetime import UTC, datetime

    from talent_data.normalization.normalizer import normalize_class

    a = normalize_class(
        dataset_new,
        game="forever",
        class_key="warrior",
        source_name="x",
        generated_at=datetime(2020, 1, 1, tzinfo=UTC),
    )
    b = normalize_class(dataset_new, game="forever", class_key="warrior", source_name="y")
    assert a.semantic_payload() == b.semantic_payload()
    assert a.content_hash == b.content_hash
    assert a.generated_at != b.generated_at


def test_schema_is_not_a_db2_dump(warrior_new: TalentDataDocument) -> None:
    payload = warrior_new.model_dump(mode="json", by_alias=True)
    text = str(payload)
    for db2_name in ("TraitNodeEntryID", "CurveID", "PosX", "TraitNodeGroupID", "Pos_0"):
        assert db2_name not in text


def _replace(dataset: TraitDataSet, **changes: object) -> TraitDataSet:
    return dataclasses.replace(dataset, **changes)


def test_unknown_edge_type_fails_loudly(dataset_new: TraitDataSet) -> None:
    edge = dataset_new.edges[0]
    broken = _replace(
        dataset_new, edges=[dataclasses.replace(edge, type=4), *dataset_new.edges[1:]]
    )
    with pytest.raises(NormalizationError, match="Type=4"):
        normalize_warrior(broken)


def test_edge_crossing_tree_boundary_fails(dataset_new: TraitDataSet) -> None:
    edge = dataset_new.edges[0]
    outside = dataclasses.replace(edge, left_trait_node_id=999_999_999)
    broken = _replace(dataset_new, edges=[outside, *dataset_new.edges[1:]])
    with pytest.raises(NormalizationError, match="crosses TraitTree"):
        normalize_warrior(broken)


def test_unknown_operation_type_is_reported_unresolved(dataset_new: TraitDataSet) -> None:
    node = node_by_name(normalize_warrior(dataset_new), "Improved Heroic Strike")
    definition_id = node.entries[0].definition_id
    points = {
        k: [dataclasses.replace(p, operation_type=1) for p in v] if k == definition_id else v
        for k, v in dataset_new.effect_points.items()
    }
    doc = normalize_warrior(_replace(dataset_new, effect_points=points))
    effect = node_by_name(doc, "Improved Heroic Strike").entries[0].effects[0]
    assert effect.per_rank is None
    assert effect.per_rank_status == "unresolved"
    assert "OperationType=1" in (effect.unresolved_reason or "")


def test_curve_missing_ranks_is_incomplete(dataset_new: TraitDataSet) -> None:
    node = node_by_name(normalize_warrior(dataset_new), "Improved Heroic Strike")
    [ep] = dataset_new.effect_points[node.entries[0].definition_id]
    curve = dataset_new.curve_points[ep.curve_id]
    points = {**dataset_new.curve_points, ep.curve_id: curve[:2]}
    doc = normalize_warrior(_replace(dataset_new, curve_points=points))
    effect = node_by_name(doc, "Improved Heroic Strike").entries[0].effects[0]
    assert effect.per_rank_status == "incomplete"
    assert [r.rank for r in effect.per_rank or []] == [1, 2]


def test_unknown_condition_type_is_kept_as_unresolved(dataset_new: TraitDataSet) -> None:
    conds = {
        k: dataclasses.replace(c, cond_type=2) if c.spent_amount_required == 30 else c
        for k, c in dataset_new.conds.items()
    }
    doc = normalize_warrior(_replace(dataset_new, conds=conds))
    ms = node_by_name(doc, "Mortal Strike")
    assert ms.unlock_requirements == []
    assert len(ms.unresolved_condition_ids) == 1


def test_sections_stay_unresolved_when_groups_do_not_partition(
    dataset_new: TraitDataSet,
) -> None:
    # Drop every gate: no counted groups -> no evidence for sections -> none are invented.
    doc = normalize_warrior(_replace(dataset_new, group_conds={}, node_conds={}))
    tree = doc.trees[0]
    assert tree.sections == []
    assert all(n.section_index is None for n in tree.nodes)


def test_missing_table_column_is_reported_with_table_name() -> None:
    with pytest.raises(Exception, match="TraitNode: column 'PosX' missing"):
        t.TraitNode.parse({"ID": "1", "TraitTreeID": "2"})
