"""Golden tests: semantic assertions for every Forever class on real fixture data
(build 1.60.1.70009). The numbers below are facts of that build's client data, not
snapshots of our JSON: they pin the contract (identity, structure, budgets, determinism)
so a refactor cannot silently change any class's normalized shape.
"""

from __future__ import annotations

import pytest

from talent_data.models.talents import TalentDataDocument, content_hash, semantic_payload
from talent_data.validation.validators import validate_document
from tests.conftest import ALL_CLASSES, normalize_fixture_class
from tests.helpers import NEW_BUILD

# class: (class_id, token, name, tree_id, nodes, sections, max_points, prereq_links,
#         total_ranks, entries)
CLASS_STATS = {
    "warrior": (1, "WARRIOR", "Warrior", 1117, 53, 3, 51, 8, 154, 53),
    "paladin": (2, "PALADIN", "Paladin", 1100, 50, 3, 51, 7, 138, 50),
    "hunter": (3, "HUNTER", "Hunter", 1091, 52, 3, 51, 9, 158, 52),
    "rogue": (4, "ROGUE", "Rogue", 1111, 53, 3, 51, 8, 139, 53),
    "priest": (5, "PRIEST", "Priest", 1114, 54, 3, 51, 8, 153, 54),
    "shaman": (7, "SHAMAN", "Shaman", 1082, 50, 3, 51, 7, 148, 50),
    "mage": (8, "MAGE", "Mage", 1112, 54, 3, 51, 6, 146, 54),
    "warlock": (9, "WARLOCK", "Warlock", 1116, 52, 3, 51, 8, 149, 52),
    "druid": (11, "DRUID", "Druid", 1089, 51, 3, 51, 9, 148, 51),
}


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_class_contract(class_key: str, class_docs: dict[str, TalentDataDocument]) -> None:
    doc = class_docs[class_key]
    class_id, token, name, tree_id, nodes, sections, points, links, ranks, entries = CLASS_STATS[
        class_key
    ]
    assert (doc.class_.id, doc.class_.token, doc.class_.name) == (class_id, token, name)
    assert doc.game == "forever"
    assert doc.client_build == str(NEW_BUILD)
    assert doc.schema_version == 1

    assert len(doc.trees) == 1
    tree = doc.trees[0]
    assert tree.id == tree_id
    assert len(tree.nodes) == nodes
    assert len(tree.sections) == sections
    assert tree.point_budget is not None
    assert tree.point_budget.max_points == points  # 51 talent points in Forever
    assert sum(len(n.prerequisites) for n in tree.nodes) == links
    assert sum(n.max_rank for n in tree.nodes) == ranks
    assert sum(len(n.entries) for n in tree.nodes) == entries

    # every node carries talent data resolved through the whole join chain
    for node in tree.nodes:
        assert node.max_rank >= 1
        assert node.entries, f"node {node.id} without entries"
        assert node.name, f"node {node.id} without a name"
        assert node.entries[0].spell_id > 0
        assert node.entries[0].description_template, f"node {node.id} without tooltip"
        for prereq in node.prerequisites:
            assert prereq.node_id in {n.id for n in tree.nodes}
        for req in node.unlock_requirements:
            assert req.required_points > 0
            assert set(req.counted_node_ids) <= {n.id for n in tree.nodes}
    # sections partition the nodes (classic talent tabs)
    assert sorted(nid for s in tree.sections for nid in s.node_ids) == sorted(
        n.id for n in tree.nodes
    )


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_content_hash_is_deterministic(class_key: str) -> None:
    """Normalizing the same fixture twice yields identical semantic payloads and hashes."""
    first = normalize_fixture_class(class_key, NEW_BUILD)
    second = normalize_fixture_class(class_key, NEW_BUILD)
    assert first.to_json() == second.to_json()
    assert first.content_hash == second.content_hash
    payload = semantic_payload(first.model_dump(mode="json", by_alias=True))
    assert first.content_hash == content_hash(payload)


@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_class_document_validates(class_key: str, class_docs: dict[str, TalentDataDocument]):
    report = validate_document(class_docs[class_key])
    assert report.ok, report.errors


def test_known_nodes_resolve(class_docs: dict[str, TalentDataDocument]) -> None:
    """One hand-verified talent per class proves the whole join chain per class."""
    by_id = {cls: {n.id: n for n in doc.trees[0].nodes} for cls, doc in class_docs.items()}
    deep = by_id["warrior"][105950]
    assert (deep.name, deep.spell_id, deep.max_rank) == ("Deep Wounds", 12834, 3)

    shock = by_id["paladin"][105323]
    assert (shock.name, shock.max_rank) == ("Holy Shock", 1)

    wrath = by_id["hunter"][104961]
    assert (wrath.name, wrath.spell_id, wrath.max_rank) == ("Bestial Wrath", 19574, 1)

    cutthroat = by_id["rogue"][105750]
    assert (cutthroat.name, cutthroat.spell_id, cutthroat.max_rank) == (
        "Cutthroat",
        462708,
        5,
    )

    flay = by_id["priest"][105826]
    assert (flay.name, flay.spell_id) == ("Mind Flay", 15407)

    names = {n.name for n in class_docs["shaman"].trees[0].nodes}
    assert "Rage of the Farseer" in names

    mage_names = {n.name: n for n in class_docs["mage"].trees[0].nodes}
    assert "Improved Frostbolt" in mage_names

    curse = [n for n in class_docs["warlock"].trees[0].nodes if n.name == "Amplify Curse"]
    assert len(curse) == 1 and curse[0].max_rank == 1

    druid_names = {n.name for n in class_docs["druid"].trees[0].nodes}
    assert "Primal Bite" in druid_names  # renamed from Mangle in 1.60.1.70009
