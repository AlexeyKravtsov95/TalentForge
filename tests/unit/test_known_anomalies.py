"""Regression tests for the known anomalies of real Forever client data.

Each test pins one investigated warning so it cannot silently disappear (masked) or
change shape after a refactor. The evidence behind every case is in
docs/forever-dataset.md; the expected warning set as a whole is pinned in
test_known_warning_codes_exactly_match_documented_set below - an UNKNOWN code appearing
in real data fails that test and must be investigated before being accepted.
"""

from __future__ import annotations

from talent_data.diff.engine import diff_documents
from talent_data.models.talents import TalentDataDocument
from talent_data.validation.issues import KNOWN_WARNING_CODES
from talent_data.validation.validators import validate_document
from tests.conftest import ALL_CLASSES, normalize_fixture_class
from tests.helpers import NEW_BUILD, OLD_BUILD

# The complete warning inventory of build 1.60.1.70009 (class -> {code: count}),
# every line of which is investigated in docs/forever-dataset.md.
EXPECTED_WARNINGS = {
    "warrior": {},
    "paladin": {"OFF_GRID_NODE": 2},
    "hunter": {
        "OFF_GRID_NODE": 2,
        "DUPLICATE_SPELL_IDENTITY": 1,
        "SUFFICIENT_PREREQUISITE_CYCLE": 1,
    },
    "rogue": {"INCOMPLETE_RANK_CURVE": 1},
    "priest": {"OFF_GRID_NODE": 1, "DUPLICATE_SPELL_IDENTITY": 1},
    "shaman": {},
    "mage": {},
    "warlock": {"OFF_GRID_NODE": 2},
    "druid": {},
}


def warning_codes(doc: TalentDataDocument) -> dict[str, int]:
    return validate_document(doc).count_by_code()


def test_known_warning_codes_exactly_match_documented_set(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """No more, no fewer warnings than investigated. A new code from real client data is
    NOT automatically acceptable - this test fails until it is investigated and either
    fixed or documented here."""
    for class_key in ALL_CLASSES:
        assert warning_codes(class_docs[class_key]) == EXPECTED_WARNINGS[class_key], class_key
    # every emitted code is documented in the registry
    emitted = {code for counts in EXPECTED_WARNINGS.values() for code in counts}
    assert emitted <= set(KNOWN_WARNING_CODES)


# --------------------------------------------------------------------------- Hunter


def test_hunter_mutual_sufficient_edge_is_preserved(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Bestial Wrath (104961) and Intimidation (104964) are linked by mutual
    SufficientForAvailability edges in the client data. WoWDBDefs documents only the enum
    name, not how multiple sufficient edges combine; under OR semantics the pair is
    playable, under AND it would be an unobtainable deadlock. The contract therefore
    keeps both edges verbatim (no AND/required conversion) and the validator flags the
    cycle as SUFFICIENT_PREREQUISITE_CYCLE."""
    nodes = {n.id: n for n in class_docs["hunter"].trees[0].nodes}
    wrath, intimidation = nodes[104961], nodes[104964]
    assert wrath.name == "Bestial Wrath"
    assert intimidation.name == "Intimidation"
    assert [(p.node_id, p.kind) for p in wrath.prerequisites] == [(104964, "sufficient")]
    assert sorted((p.node_id, p.kind) for p in intimidation.prerequisites) == [
        (104961, "sufficient"),
        (104970, "sufficient"),  # Bestial Swiftness, the normal "forward" prerequisite
    ]
    codes = warning_codes(class_docs["hunter"])
    assert codes.get("SUFFICIENT_PREREQUISITE_CYCLE") == 1


def test_hunter_duplicate_lightning_reflexes_is_a_misplaced_duplicate(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Two nodes share spell 19168 (Lightning Reflexes): the properly placed 110859
    (10280, 5130) and 104982 at x=102800 - the real column coordinate with an extra
    trailing zero. Raw x/y stay authoritative; the anomaly is flagged, not fixed."""
    tree = class_docs["hunter"].trees[0]
    lr = sorted((n for n in tree.nodes if n.spell_id == 19168), key=lambda n: n.id)
    assert [n.id for n in lr] == [104982, 110859]
    assert (lr[0].position.x, lr[0].position.y) == (102800, 5740)
    assert (lr[1].position.x, lr[1].position.y) == (10280, 5130)
    codes = warning_codes(class_docs["hunter"])
    assert codes.get("DUPLICATE_SPELL_IDENTITY") == 1


def test_hunter_off_grid_nodes(class_docs: dict[str, TalentDataDocument]) -> None:
    """Improved Serpent Sting sits at y=39300 (the row-1 y 3930 with an extra zero);
    the duplicate Lightning Reflexes at x=102800 (see above)."""
    tree = class_docs["hunter"].trees[0]
    serpent = next(n for n in tree.nodes if n.name == "Improved Serpent Sting")
    assert (serpent.position.x, serpent.position.y) == (6820, 39300)
    assert serpent.position.row == 62  # far below the real 7-row tree
    issues = [
        i for i in validate_document(class_docs["hunter"]).warnings if i.code == "OFF_GRID_NODE"
    ]
    assert {i.node_id for i in issues} == {104982, 105003}


# --------------------------------------------------------------------------- Rogue


def test_rogue_cutthroat_rank_curve_is_incomplete_in_client_data(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Cutthroat (5 ranks): effect #0 has a full 5-point curve, effect #1 has only 3
    points and no SpellEffect row at all - stable across all four 1.60.1 builds we can
    fetch (69893...70009). The client data itself is incomplete; we expose what exists
    and warn INCOMPLETE_RANK_CURVE instead of inventing ranks 4-5."""
    cutthroat = next(n for n in class_docs["rogue"].trees[0].nodes if n.name == "Cutthroat")
    assert cutthroat.id == 105750 and cutthroat.max_rank == 5
    effects = {e.index: e for e in cutthroat.entries[0].effects}
    assert effects[0].per_rank_status == "resolved"
    assert [r.value for r in effects[0].per_rank or []] == [3, 6, 9, 12, 15]
    assert effects[1].per_rank_status == "incomplete"
    assert [r.value for r in effects[1].per_rank or []] == [5, 10, 15]
    issues = [
        i
        for i in validate_document(class_docs["rogue"]).warnings
        if i.code == "INCOMPLETE_RANK_CURVE"
    ]
    assert [(i.node_id, i.entry_id, i.effect_index) for i in issues] == [(105750, 130479, 1)]


# --------------------------------------------------------------------------- Priest


def test_priest_duplicate_holy_specialization_is_a_misplaced_duplicate(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Spell 14889 (Holy Specialization) is used by the properly placed 110855 (6220,2130)
    and by 105865 at y=21300 - the row-0 y with an extra zero."""
    tree = class_docs["priest"].trees[0]
    pair = sorted((n for n in tree.nodes if n.spell_id == 14889), key=lambda n: n.id)
    assert [n.id for n in pair] == [105865, 110855]
    assert (pair[0].position.x, pair[0].position.y) == (9280, 21300)
    assert (pair[1].position.x, pair[1].position.y) == (6220, 2130)
    codes = warning_codes(class_docs["priest"])
    assert codes.get("DUPLICATE_SPELL_IDENTITY") == 1


def test_priest_vacuous_trait_conditions_are_noops(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """TraitCond 43478 (group 11608 = whole Discipline tab) is CondType Available with
    every constraint field zero: it cannot gate anything, so it is a no-op, not an
    unresolved condition. The tab's real tier gates come from other groups and must
    survive."""
    tree = class_docs["priest"].trees[0]
    assert all(n.unresolved_condition_ids == [] for n in tree.nodes)
    assert "UNRESOLVED_CONDITION" not in warning_codes(class_docs["priest"])
    penance = next(n for n in tree.nodes if n.name == "Penance")
    assert penance.unlock_requirements  # the real gates are still there
    assert [(r.required_points, len(r.counted_node_ids)) for r in penance.unlock_requirements] == [
        (20, 14)
    ]


# --------------------------------------------------------------------------- Druid


def test_druid_vacuous_trait_conditions_are_noops(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Group 11329 (Improved Entangling Roots, Nature's Splendor) carries the real gate
    50973 (10 pts in group 12700) plus the all-zero cond 50974, which is a no-op."""
    tree = class_docs["druid"].trees[0]
    assert all(n.unresolved_condition_ids == [] for n in tree.nodes)
    roots = next(n for n in tree.nodes if n.name == "Improved Entangling Roots")
    assert [(r.required_points, len(r.counted_node_ids)) for r in roots.unlock_requirements] == [
        (10, 6)
    ]


def test_druid_real_renames_between_builds() -> None:
    """1.60.1.70009 renamed 'Mangle' -> 'Primal Bite' (node 104949, same spell id) and
    'Primal Fury' -> 'Blood Frenzy' (node 104947). Rename detection must report both -
    and must never report a spell-id change as a rename."""
    old = normalize_fixture_class("druid", OLD_BUILD)
    new = normalize_fixture_class("druid", NEW_BUILD)
    report = diff_documents(old, new)
    renames = {
        node.node_id: (c.old, c.new)
        for node in report.changed
        for c in node.changes
        if c.kind == "renamed"
    }
    assert renames == {
        104947: ("Primal Fury", "Blood Frenzy"),
        104949: ("Mangle", "Primal Bite"),
    }
    assert not any(c.kind == "spellReplaced" for node in report.changed for c in node.changes)


# --------------------------------------------------------------------------- Paladin / Warlock


def test_paladin_nudged_nodes(class_docs: dict[str, TalentDataDocument]) -> None:
    """Improved Seal of Fury (110875) and Swift Judgement (110878) sit at x=5030, i.e.
    +10 off their tab's x=5020 column origin - a deliberate nudge, unlike Hunter/Priest's
    extra-zero typos."""
    tree = class_docs["paladin"].trees[0]
    nodes = {n.name: n for n in tree.nodes}
    assert (nodes["Improved Seal of Fury"].position.x, nodes["Improved Seal of Fury"].id) == (
        5030,
        110875,
    )
    assert nodes["Swift Judgement"].position.x == 5030
    issues = [
        i for i in validate_document(class_docs["paladin"]).warnings if i.code == "OFF_GRID_NODE"
    ]
    assert {i.node_id for i in issues} == {110875, 110878}


def test_warlock_nudged_nodes(class_docs: dict[str, TalentDataDocument]) -> None:
    """Improved Life Tap (105921, y=2120) and Amplify Curse (105916, y=3320) are nudged
    -10 off their tab's y=2130/3330 rows."""
    tree = class_docs["warlock"].trees[0]
    nodes = {n.name: n for n in tree.nodes}
    assert (nodes["Improved Life Tap"].position.y, nodes["Improved Life Tap"].id) == (2120, 105921)
    assert (nodes["Amplify Curse"].position.y, nodes["Amplify Curse"].id) == (3320, 105916)
    issues = [
        i for i in validate_document(class_docs["warlock"]).warnings if i.code == "OFF_GRID_NODE"
    ]
    assert {i.node_id for i in issues} == {105916, 105921}
