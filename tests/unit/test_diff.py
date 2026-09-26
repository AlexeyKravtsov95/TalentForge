from __future__ import annotations

from talent_data.diff.engine import diff_documents
from talent_data.diff.render import render_text
from talent_data.models.talents import Position, Prerequisite, TalentDataDocument
from tests.helpers import node_by_name, replace_node, with_nodes


def kinds(report, node_name: str) -> list[str]:
    [node] = [c for c in report.changed if c.name == node_name]
    return [c.kind for c in node.changes]


# --------------------------------------------------------------------------- real builds


def test_real_diff_69977_to_70009(
    warrior_old: TalentDataDocument, warrior_new: TalentDataDocument
) -> None:
    report = diff_documents(warrior_old, warrior_new)
    assert (report.from_build, report.to_build) == ("1.60.1.69977", "1.60.1.70009")
    assert (report.summary.added, report.summary.removed, report.summary.changed) == (0, 0, 2)
    assert {c.name for c in report.changed} == {"Bloodthrill", "Improved Slam"}

    [bloodthrill] = [c for c in report.changed if c.name == "Bloodthrill"]
    ranks = [
        (c.rank, c.old, c.new) for c in bloodthrill.changes if c.kind == "effectRankValueChanged"
    ]
    assert ranks == [(1, 2, 4), (2, 4, 8), (3, 6, 12), (4, 8, 16), (5, 10, 20)]
    assert "descriptionChanged" in kinds(report, "Bloodthrill")

    [slam] = [c for c in report.changed if c.name == "Improved Slam"]
    new_effect = [
        (c.rank, c.old, c.new)
        for c in slam.changes
        if c.kind == "effectRankValueChanged" and c.effect_index == 2
    ]
    assert new_effect == [(1, None, -1500), (2, None, -3000)]


def test_identical_documents_have_empty_diff(warrior_new: TalentDataDocument) -> None:
    report = diff_documents(warrior_new, warrior_new)
    assert report.is_empty
    assert "No semantic changes." in render_text(report, "Forever")


# --------------------------------------------------------------------------- rule coverage
# The two real builds contain only value/tooltip changes for Warrior, so the remaining rules
# are exercised by explicit, local mutations of the real document.


def test_added_and_removed(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    without = with_nodes(warrior_new, lambda nodes: [n for n in nodes if n.id != ms.id])
    report = diff_documents(warrior_new, without)
    assert [r.name for r in report.removed] == ["Mortal Strike"]
    back = diff_documents(without, warrior_new)
    assert [r.node_id for r in back.added] == [ms.id]


def test_removed_node_does_not_spam_unlock_changes(warrior_new: TalentDataDocument) -> None:
    rend = node_by_name(warrior_new, "Improved Rend")
    without = with_nodes(warrior_new, lambda nodes: [n for n in nodes if n.id != rend.id])
    report = diff_documents(warrior_new, without)
    assert report.summary.removed == 1
    # Deep Wounds loses its prerequisite (real consequence) but gate lists shrinking because
    # a counted node disappeared is not reported as a separate change.
    assert all(
        c.kind != "unlockRequirementsChanged" for node in report.changed for c in node.changes
    )


def test_rename_requires_same_spell(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Deep Wounds")
    entry = node.entries[0].model_copy(update={"name": "Deeper Wounds"})
    renamed = replace_node(warrior_new, "Deep Wounds", name="Deeper Wounds", entries=[entry])
    report = diff_documents(warrior_new, renamed)
    [change] = report.changed[0].changes
    assert (change.kind, change.old, change.new) == ("renamed", "Deep Wounds", "Deeper Wounds")


def test_different_spell_is_never_a_rename(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Deep Wounds")
    entry = node.entries[0].model_copy(update={"spell_id": 1, "name": "Something Else"})
    replaced = replace_node(warrior_new, "Deep Wounds", spell_id=1, entries=[entry])
    report = diff_documents(warrior_new, replaced)
    assert kinds(report, "Deep Wounds") == ["spellReplaced"]


def test_same_spell_on_new_node_id_is_removed_plus_added_with_hint(
    warrior_new: TalentDataDocument,
) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    moved = with_nodes(
        warrior_new,
        lambda nodes: [n.model_copy(update={"id": 1}) if n.id == ms.id else n for n in nodes],
    )
    report = diff_documents(warrior_new, moved)
    assert (report.summary.added, report.summary.removed) == (1, 1)
    assert report.removed[0].related_node_id == 1
    assert report.added[0].related_node_id == ms.id
    assert "not merged" in render_text(report, "Forever")


def test_max_rank_change(warrior_new: TalentDataDocument) -> None:
    report = diff_documents(warrior_new, replace_node(warrior_new, "Deep Wounds", max_rank=2))
    [change] = [c for c in report.changed[0].changes if c.kind == "maxRankChanged"]
    assert (change.old, change.new) == (3, 2)
    assert "maxRank: 3 -> 2" in render_text(report, "Forever")


def test_position_change(warrior_new: TalentDataDocument) -> None:
    node = node_by_name(warrior_new, "Improved Overpower")
    moved = replace_node(
        warrior_new,
        "Improved Overpower",
        position=Position(x=node.position.x - 600, y=node.position.y, row=1, column=2),
    )
    report = diff_documents(warrior_new, moved)
    assert kinds(report, "Improved Overpower") == ["positionChanged"]
    assert "position: (row 1, col 3; x=2820" in render_text(report, "Forever")


def test_prerequisite_change(warrior_new: TalentDataDocument) -> None:
    ms = node_by_name(warrior_new, "Mortal Strike")
    extra = Prerequisite(node_id=node_by_name(warrior_new, "Deep Wounds").id, kind="sufficient")
    changed = replace_node(warrior_new, "Mortal Strike", prerequisites=[*ms.prerequisites, extra])
    report = diff_documents(warrior_new, changed)
    text = render_text(report, "Forever")
    assert kinds(report, "Mortal Strike") == ["prerequisitesChanged"]
    assert "old: [105945 Sweeping Strikes]" in text
    assert "new: [105945 Sweeping Strikes, 105950 Deep Wounds]" in text


def test_render_header(warrior_old: TalentDataDocument, warrior_new: TalentDataDocument) -> None:
    text = render_text(diff_documents(warrior_old, warrior_new), "Forever")
    assert text.startswith("Forever Warrior\n1.60.1.69977 -> 1.60.1.70009\n\nADDED: 0\n")
    assert "[CHANGED]\nBloodthrill (node 110524)" in text
    assert "    rank 1: 2 -> 4" in text


def test_diff_json_round_trips(
    warrior_old: TalentDataDocument, warrior_new: TalentDataDocument
) -> None:
    from talent_data.diff.models import DiffReport

    report = diff_documents(warrior_old, warrior_new)
    again = DiffReport.model_validate_json(report.to_json())
    assert again == report
    assert '"fromBuild": "1.60.1.69977"' in report.to_json()
