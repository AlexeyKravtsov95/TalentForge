from __future__ import annotations

from talent_data.models.talents import Prerequisite, TalentDataDocument
from talent_data.validation.validators import validate_document
from tests.helpers import node_by_name, rehash, replace_node, with_nodes


def test_real_warrior_document_is_valid(warrior_new: TalentDataDocument) -> None:
    report = validate_document(warrior_new)
    assert report.ok, report.errors
    assert report.warnings == []


def test_duplicate_node_ids(warrior_new: TalentDataDocument) -> None:
    doc = with_nodes(warrior_new, lambda nodes: [*nodes, nodes[0]])
    assert any("duplicate node id" in e for e in validate_document(doc).errors)


def test_dangling_prerequisite(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(
        warrior_new,
        "Deep Wounds",
        prerequisites=[Prerequisite(node_id=424242, kind="sufficient")],
    )
    assert any("dangling prerequisite 424242" in e for e in validate_document(doc).errors)


def test_rank_count_must_be_positive(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(warrior_new, "Deep Wounds", max_rank=0)
    assert any("maxRank must be > 0" in e for e in validate_document(doc).errors)


def test_missing_name_is_an_error(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Deep Wounds")
    entry = node.entries[0].model_copy(update={"name": None})
    doc = replace_node(warrior_new, "Deep Wounds", entries=[entry])
    assert any("missing name" in e for e in validate_document(doc).errors)


def test_duplicate_spell_identity_is_flagged(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    doc = replace_node(warrior_new, "Deep Wounds", spell_id=ms.spell_id)
    assert any("used by 2 nodes" in w for w in validate_document(doc).warnings)


def test_shared_position_is_an_error(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    doc = replace_node(warrior_new, "Deep Wounds", position=ms.position)
    assert any("share position" in e for e in validate_document(doc).errors)


def test_required_cycle_is_error_sufficient_cycle_is_warning(
    warrior_new: TalentDataDocument,
) -> None:
    rend = node_by_name(warrior_new, "Improved Rend")
    wounds = node_by_name(warrior_new, "Deep Wounds")
    sufficient = replace_node(
        warrior_new,
        "Improved Rend",
        prerequisites=[Prerequisite(node_id=wounds.id, kind="sufficient")],
    )
    report = validate_document(sufficient)
    assert report.ok
    assert any("'sufficient' prerequisite cycle" in w for w in report.warnings)

    required = replace_node(
        warrior_new,
        "Improved Rend",
        prerequisites=[Prerequisite(node_id=wounds.id, kind="required")],
    )
    errors = validate_document(required).errors
    assert any("deadlock" in e and str(rend.id) in e for e in errors)


def test_sections_must_match_node_membership(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(warrior_new, "Deep Wounds", section_index=2)
    assert any("!= membership" in e for e in validate_document(doc).errors)


def test_tampered_payload_fails_content_hash(warrior_new: TalentDataDocument) -> None:
    tampered = with_nodes(warrior_new, lambda nodes: nodes[:-1])
    tampered = tampered.model_copy(update={"content_hash": warrior_new.content_hash})
    assert any("contentHash" in e for e in validate_document(tampered).errors)
    assert all("contentHash" not in e for e in validate_document(rehash(tampered)).errors)
