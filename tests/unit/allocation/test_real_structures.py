"""Regression tests for the real structures of the Forever dataset that shaped the rules:
the Hunter mutual sufficient cycle, the Rogue unresolved condition 50984, the only
RequiredForAvailability edge (Druid), and the mis-placed duplicate nodes (Hunter/Priest).

Node ids/names of real classes are allowed here (tests only).
"""

from __future__ import annotations

from talent_data.allocation import AllocationIssueCode, BuildState, TalentAllocationEngine
from talent_data.models.talents import TalentDataDocument
from tests.unit.allocation.conftest import node

C = AllocationIssueCode

BESTIAL_WRATH, INTIMIDATION, BESTIAL_SWIFTNESS = 104961, 104964, 104970
VENOM, MUTILATE, ROGUE_COND = 105712, 105709, 50984


def fill_section(
    engine: TalentAllocationEngine,
    state: BuildState,
    section: int,
    points: int,
    exclude: set[int],
    prefer: tuple[int, ...] = (),
) -> BuildState:
    nodes = sorted(
        (n for n in engine.tree.nodes if n.section_index == section and n.id not in exclude),
        key=lambda n: (n.id not in prefer, n.position.row, n.position.column, n.id),
    )
    ids = {n.id for n in engine.tree.nodes if n.section_index == section}
    while sum(a.rank for a in state.allocations if a.node_id in ids) < points:
        state = next(r.state for n in nodes if (r := engine.spend(state, n.id)).allowed)
    return state


# --------------------------------------------------------------------------- Hunter


def test_hunter_cycle_is_the_real_data(docs: dict[str, TalentDataDocument]) -> None:
    engine = TalentAllocationEngine(docs["HUNTER"])
    by_id = {n.id: n for n in engine.tree.nodes}
    assert by_id[BESTIAL_WRATH].name == "Bestial Wrath"
    assert by_id[INTIMIDATION].name == "Intimidation"
    assert by_id[BESTIAL_SWIFTNESS].name == "Bestial Swiftness"
    assert {(p.node_id, p.kind) for p in by_id[INTIMIDATION].prerequisites} == {
        (BESTIAL_WRATH, "sufficient"),
        (BESTIAL_SWIFTNESS, "sufficient"),
    }
    assert [(p.node_id, p.kind) for p in by_id[BESTIAL_WRATH].prerequisites] == [
        (INTIMIDATION, "sufficient")
    ]


def test_hunter_cycle_is_not_a_deadlock(docs: dict[str, TalentDataDocument]) -> None:
    """ANY-semantics of sufficient edges: Bestial Swiftness roots the cycle."""
    engine = TalentAllocationEngine(docs["HUNTER"])
    cycle = {BESTIAL_WRATH, INTIMIDATION, BESTIAL_SWIFTNESS}
    state = fill_section(engine, engine.empty_state(), 0, 30, exclude=cycle)
    for blocked in (BESTIAL_WRATH, INTIMIDATION):
        decision = engine.can_spend(state, blocked)
        assert decision.reason_codes == ("SUFFICIENT_PREREQUISITE_NOT_MET",)
    for step in (BESTIAL_SWIFTNESS, INTIMIDATION, BESTIAL_WRATH):
        result = engine.spend(state, step)
        assert result.allowed, (step, result.decision.reason_codes)
        state = result.state
    assert engine.validate(state).valid


def test_hunter_cycle_cannot_support_itself(docs: dict[str, TalentDataDocument]) -> None:
    """Refunding the root would leave Intimidation and Bestial Wrath supporting each other
    only statically; no purchase order reaches that plan, so the refund is refused and
    such an import is invalid."""
    engine = TalentAllocationEngine(docs["HUNTER"])
    cycle = {BESTIAL_WRATH, INTIMIDATION, BESTIAL_SWIFTNESS}
    state = fill_section(engine, engine.empty_state(), 0, 30, exclude=cycle)
    for step in (BESTIAL_SWIFTNESS, INTIMIDATION, BESTIAL_WRATH):
        state = engine.spend(state, step).state
    decision = engine.can_refund(state, BESTIAL_SWIFTNESS)
    assert decision.reason_codes == ("DEPENDENT_NODE_WOULD_BREAK",)
    broken = {i.node_id for i in decision.issues}
    assert broken == {INTIMIDATION, BESTIAL_WRATH}
    assert all(i.related_node_ids == (BESTIAL_SWIFTNESS,) for i in decision.issues)

    rootless = state.with_allocation(BESTIAL_SWIFTNESS, 0, 0)
    validation = engine.validate(rootless)
    assert not validation.valid
    assert {(i.code, i.node_id) for i in validation.issues} >= {
        (C.SUFFICIENT_PREREQUISITE_NOT_MET, INTIMIDATION),
        (C.SUFFICIENT_PREREQUISITE_NOT_MET, BESTIAL_WRATH),
    }
    # the leaf of the cycle is freely refundable
    assert engine.can_refund(state, BESTIAL_WRATH).allowed


# --------------------------------------------------------------------------- Rogue


def test_rogue_venom_fails_closed_on_unresolved_condition(
    docs: dict[str, TalentDataDocument],
) -> None:
    engine = TalentAllocationEngine(docs["ROGUE"])
    venom = next(n for n in engine.tree.nodes if n.id == VENOM)
    assert venom.name == "Venom"
    assert venom.unresolved_condition_ids == [ROGUE_COND]
    assert engine.unsupported_nodes().keys() == {VENOM}

    state = fill_section(engine, engine.empty_state(), 0, 30, exclude={VENOM}, prefer=(MUTILATE,))
    assert state.rank_of(MUTILATE) == 1
    decision = engine.can_spend(state, VENOM)
    # every proven rule (gate 30/30, Mutilate edge) is met - the unknown still blocks
    assert decision.reason_codes == ("UNRESOLVED_CONDITION",)
    assert decision.issues[0].condition_id == ROGUE_COND
    assert "50984 is unresolved" in decision.issues[0].message

    imported = state.with_allocation(VENOM, venom.entries[0].id, 1)
    validation = engine.validate(imported)
    assert validation.reason_codes == ("UNRESOLVED_CONDITION",)


# --------------------------------------------------------------------------- Druid


def test_druid_required_edge(docs: dict[str, TalentDataDocument]) -> None:
    engine = TalentAllocationEngine(docs["DRUID"])
    splendor, majesty = node(engine, "Nature's Splendor"), node(engine, "Nature's Majesty")
    assert [(p.node_id, p.kind) for p in splendor.prerequisites] == [(majesty.id, "required")]
    state = engine.empty_state()
    for name, rank in (
        ("Improved Wrath", 5),
        ("Genesis", 3),
        ("Nature's Majesty", 1),
        ("Moonglow", 1),
    ):
        n = node(engine, name)
        state = state.with_allocation(n.id, n.entries[0].id, rank)
    assert engine.validate(state).valid
    decision = engine.can_spend(state, splendor.id)
    assert decision.reason_codes == ("REQUIRED_PREREQUISITE_NOT_MET",)
    assert (decision.issues[0].required, decision.issues[0].actual) == (2, 1)
    state = engine.spend(state, majesty.id).state
    assert engine.can_spend(state, splendor.id).allowed


# --------------------------------------------------------------------------- duplicates


def test_misplaced_duplicate_nodes_follow_the_same_rules(
    docs: dict[str, TalentDataDocument],
) -> None:
    """Hunter 104982 / Priest 105865 are mis-placed duplicates of real talents (M1
    OFF_GRID_NODE + DUPLICATE_SPELL_IDENTITY). Client data gives them ordinary rules, so the
    engine applies them uniformly - no id-based special case. Hiding them is a UI decision
    (docs: Known limitations)."""
    hunter = TalentAllocationEngine(docs["HUNTER"])
    dup = next(n for n in hunter.tree.nodes if n.id == 104982)
    assert dup.name == "Lightning Reflexes"
    assert hunter.can_spend(hunter.empty_state(), dup.id).reason_codes == ("TIER_GATE_NOT_MET",)
    priest = TalentAllocationEngine(docs["PRIEST"])
    pdup = next(n for n in priest.tree.nodes if n.id == 105865)
    assert pdup.name == "Holy Specialization"
    assert pdup.unlock_requirements == []
    assert priest.can_spend(priest.empty_state(), pdup.id).allowed
