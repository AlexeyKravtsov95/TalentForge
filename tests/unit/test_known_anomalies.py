"""Regression tests for the known anomalies of real Forever client data.

Each test pins one investigated warning so it cannot silently disappear (masked) or
change shape after a refactor. The evidence behind every case is in
docs/forever-dataset.md; the expected warning set as a whole is pinned in
test_known_warning_codes_exactly_match_documented_set below - an UNKNOWN code appearing
in real data fails that test and must be investigated before being accepted.
"""

from __future__ import annotations

import dataclasses

import pytest

from talent_data.db2 import tables as t
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
    "rogue": {"INCOMPLETE_RANK_CURVE": 1, "UNRESOLVED_CONDITION": 1},
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


# ------------------------------------------------------------------ condition classification
#
# The vacuous/pure-gate classification must consider EVERY functional TraitCond field.
# Field semantics researched for the M1 review (WoWDBDefs + real rows):
#   * TraitNodeEntryID, TraitCondAccountElementID: functional constraints - retail
#     12.1.0.69933 uses them on 25 / 413 conditions respectively;
#   * FreeSharedStringID / SpendMoreSharedStringID: presentation-only - SharedString
#     localization texts (911 = "Spend %d more |4point:points; to unlock this talent"),
#     carried by real gates and vacuous rows alike;
#   * TraitTreeID: scoping reference (real gates carry it too).


@pytest.fixture(scope="module")
def real_conds() -> dict[int, t.TraitCond]:
    """Real typed rows: 43478 (vacuous), 50973 (pure points gate), 50984 (node+group)."""
    from tests.conftest import load_dataset

    priest = load_dataset("priest", NEW_BUILD).conds
    druid = load_dataset("druid", NEW_BUILD).conds
    rogue = load_dataset("rogue", NEW_BUILD).conds
    return {"vacuous": priest[43478], "gate": druid[50973], "node_gate": rogue[50984]}


@pytest.mark.parametrize(
    "field",
    [
        "trait_node_group_id",
        "trait_node_id",
        "trait_node_entry_id",
        "trait_cond_account_element_id",
        "trait_currency_id",
        "spent_amount_required",
        "required_level",
        "granted_ranks",
        "quest_id",
        "achievement_id",
        "spec_set_id",
        "flags",
    ],
)
def test_any_functional_field_blocks_vacuous_classification(field: str) -> None:
    """A condition is only provably vacuous when EVERY functional field is zero - any
    non-zero value means the row could constrain something and must not be a no-op."""
    from talent_data.normalization.normalizer import _is_vacuous_cond

    vacuous = t.TraitCond(
        id=99999,
        cond_type=0,
        trait_tree_id=0,
        trait_node_group_id=0,
        trait_node_id=0,
        trait_node_entry_id=0,
        trait_cond_account_element_id=0,
        trait_currency_id=0,
        spent_amount_required=0,
        required_level=0,
        granted_ranks=0,
        quest_id=0,
        achievement_id=0,
        spec_set_id=0,
        flags=0,
        free_shared_string_id=0,
        spend_more_shared_string_id=0,
    )
    assert _is_vacuous_cond(vacuous)
    value = 3 if field != "cond_type" else 1
    assert not _is_vacuous_cond(dataclasses.replace(vacuous, **{field: value}))


def test_non_available_cond_type_is_never_vacuous() -> None:
    """Visible/Granted/Increased/DisplayError conditions are not availability no-ops;
    they must surface as unresolved when attached, not be dropped."""
    from talent_data.normalization.normalizer import _is_vacuous_cond

    vacuous = t.TraitCond(
        id=99999,
        cond_type=1,
        trait_tree_id=0,
        trait_node_group_id=0,
        trait_node_id=0,
        trait_node_entry_id=0,
        trait_cond_account_element_id=0,
        trait_currency_id=0,
        spent_amount_required=0,
        required_level=0,
        granted_ranks=0,
        quest_id=0,
        achievement_id=0,
        spec_set_id=0,
        flags=0,
        free_shared_string_id=0,
        spend_more_shared_string_id=0,
    )
    assert not _is_vacuous_cond(vacuous)


@pytest.mark.parametrize(
    ("field", "value"),
    [("free_shared_string_id", 910), ("spend_more_shared_string_id", 911)],
)
def test_presentation_string_fields_do_not_block_vacuous_classification(
    field: str, value: int
) -> None:
    """SharedString references are proven presentation-only (localization texts rendered
    next to a gate, present on real gates and vacuous rows alike), so they are not
    constraints. If this ever changes, the vacuous classification must be tightened."""
    from talent_data.normalization.normalizer import _is_vacuous_cond

    vacuous = t.TraitCond(
        id=99999,
        cond_type=0,
        trait_tree_id=0,
        trait_node_group_id=0,
        trait_node_id=0,
        trait_node_entry_id=0,
        trait_cond_account_element_id=0,
        trait_currency_id=0,
        spent_amount_required=0,
        required_level=0,
        granted_ranks=0,
        quest_id=0,
        achievement_id=0,
        spec_set_id=0,
        flags=0,
        free_shared_string_id=0,
        spend_more_shared_string_id=0,
    )
    assert _is_vacuous_cond(dataclasses.replace(vacuous, **{field: value}))


def test_real_vacuous_and_gate_rows_classify_as_researched(
    real_conds: dict[str, t.TraitCond],
) -> None:
    """The three real row shapes land in exactly the researched buckets."""
    from talent_data.normalization.normalizer import _is_pure_points_gate, _is_vacuous_cond

    vacuous, gate, node_gate = real_conds["vacuous"], real_conds["gate"], real_conds["node_gate"]
    # 43478: all-zero row (incl. the string fields and tree-scoping id)
    assert (vacuous.trait_node_entry_id, vacuous.trait_cond_account_element_id) == (0, 0)
    assert _is_vacuous_cond(vacuous)
    # 50973 (Druid): exactly 'spend 10 of currency 3820 in group 12700' -> a pure gate
    assert _is_pure_points_gate(gate) and not _is_vacuous_cond(gate)
    assert (gate.trait_node_group_id, gate.spent_amount_required, gate.trait_currency_id) == (
        12700,
        10,
        3820,
    )
    # 50984 (Rogue): group+spend AND a TraitNodeID reference -> NOT a pure gate
    assert not _is_pure_points_gate(node_gate) and not _is_vacuous_cond(node_gate)
    assert (node_gate.trait_node_id, node_gate.trait_node_group_id) == (105709, 11576)


def test_cond_with_entry_or_account_reference_surfaces_as_unresolved() -> None:
    """End-to-end: if a real vacuous row ever gained a node-entry or account-element
    reference (both functional in retail), it must stop being a no-op and surface as an
    unresolved condition - never be silently ignored or flattened into a gate."""
    from talent_data.normalization.normalizer import normalize_class
    from tests.conftest import load_dataset

    for field in ("trait_node_entry_id", "trait_cond_account_element_id"):
        dataset = load_dataset("priest", NEW_BUILD)
        cond = dataset.conds[43478]
        dataset.conds[43478] = dataclasses.replace(cond, **{field: 123})
        doc = normalize_class(dataset, game="forever", class_key="priest", source_name="fixture")
        affected = [n for n in doc.trees[0].nodes if n.unresolved_condition_ids]
        assert len(affected) == 18, field  # the whole Discipline-tab group carries the cond
        assert all(43478 in n.unresolved_condition_ids for n in affected), field


def test_rogue_venom_cond_50984_is_unresolved_not_flattened(
    class_docs: dict[str, TalentDataDocument],
) -> None:
    """Real regression from the stricter shape check: Rogue cond 50984 attaches to node
    105712 (Venom) and combines a TraitNodeID reference (105709 Mutilate) with a
    group/1-point spend. How these combine is not documented anywhere, so since the M1
    review fix the condition is UNRESOLVED_CONDITION - previously the node reference was
    silently dropped and the row flattened into a plain '1 pt in 3 nodes' gate."""
    tree = class_docs["rogue"].trees[0]
    venom = next(n for n in tree.nodes if n.id == 105712)
    assert venom.name == "Venom"
    assert venom.unresolved_condition_ids == [50984]
    # the fabricated 'spend 1 in 3 nodes' gate is gone; the real capstone gate remains
    assert [(r.required_points, len(r.counted_node_ids)) for r in venom.unlock_requirements] == [
        (30, 17)
    ]
    issues = [
        i
        for i in validate_document(class_docs["rogue"]).warnings
        if i.code == "UNRESOLVED_CONDITION"
    ]
    assert len(issues) == 1 and issues[0].node_id == 105712


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
