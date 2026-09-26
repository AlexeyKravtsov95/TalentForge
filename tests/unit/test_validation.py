from __future__ import annotations

from talent_data.models.talents import Prerequisite, TalentDataDocument
from talent_data.validation.validators import validate_document
from tests.helpers import node_by_name, rehash, replace_node, with_nodes


def codes(report, severity: str = "error") -> set[str]:
    return {i.code for i in report.issues if i.severity == severity}


def test_real_warrior_document_is_valid(warrior_new: TalentDataDocument) -> None:
    report = validate_document(warrior_new)
    assert report.ok, report.errors
    assert report.warnings == []


def test_duplicate_node_ids(warrior_new: TalentDataDocument) -> None:
    doc = with_nodes(warrior_new, lambda nodes: [*nodes, nodes[0]])
    report = validate_document(doc)
    assert "DUPLICATE_NODE_ID" in codes(report)
    issue = report.errors[0]
    assert issue.code == "DUPLICATE_NODE_ID"
    assert "duplicate node id" in issue.message


def test_dangling_prerequisite(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(
        warrior_new,
        "Deep Wounds",
        prerequisites=[Prerequisite(node_id=424242, kind="sufficient")],
    )
    report = validate_document(doc)
    assert "DANGLING_PREREQUISITE" in codes(report)
    [issue] = [i for i in report.errors if i.code == "DANGLING_PREREQUISITE"]
    assert issue.node_id is not None and "Deep Wounds" in issue.message


def test_rank_count_must_be_positive(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(warrior_new, "Deep Wounds", max_rank=0)
    assert "INVALID_MAX_RANK" in codes(validate_document(doc))


def test_missing_name_is_an_error(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Deep Wounds")
    entry = node.entries[0].model_copy(update={"name": None})
    doc = replace_node(warrior_new, "Deep Wounds", entries=[entry])
    assert "MISSING_NAME" in codes(validate_document(doc))


def test_duplicate_spell_identity_is_flagged(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    doc = replace_node(warrior_new, "Deep Wounds", spell_id=ms.spell_id)
    report = validate_document(doc)
    assert "DUPLICATE_SPELL_IDENTITY" in codes(report, "warning")


def test_shared_position_is_an_error(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    doc = replace_node(warrior_new, "Deep Wounds", position=ms.position)
    assert "SHARED_POSITION" in codes(validate_document(doc))


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
    assert "SUFFICIENT_PREREQUISITE_CYCLE" in codes(report, "warning")

    required = replace_node(
        warrior_new,
        "Improved Rend",
        prerequisites=[Prerequisite(node_id=wounds.id, kind="required")],
    )
    errors = validate_document(required).errors
    assert any(
        i.code == "REQUIRED_PREREQUISITE_CYCLE" and str(rend.id) in i.message for i in errors
    )


def test_sections_must_match_node_membership(warrior_new: TalentDataDocument) -> None:
    doc = replace_node(warrior_new, "Deep Wounds", section_index=2)
    assert "SECTION_INDEX_MISMATCH" in codes(validate_document(doc))


def test_tampered_payload_fails_content_hash(warrior_new: TalentDataDocument) -> None:
    tampered = with_nodes(warrior_new, lambda nodes: nodes[:-1])
    tampered = tampered.model_copy(update={"content_hash": warrior_new.content_hash})
    assert "CONTENT_HASH_MISMATCH" in codes(validate_document(tampered))
    assert "CONTENT_HASH_MISMATCH" not in codes(validate_document(rehash(tampered)))


def test_issues_are_machine_readable(warrior_new: TalentDataDocument) -> None:
    """Every issue carries a stable code and structured location, not just text."""
    doc = replace_node(warrior_new, "Deep Wounds", max_rank=0)
    [issue] = validate_document(doc).errors
    assert issue.severity == "error"
    assert issue.code == "INVALID_MAX_RANK"
    assert issue.tree_id == doc.trees[0].id
    assert issue.node_id == node_by_name(doc, "Deep Wounds").id
    assert str(issue) == f"[{issue.code}] {issue.message}"
