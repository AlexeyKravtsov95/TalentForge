"""Rule-level tests of TalentAllocationEngine on the real Warrior dataset (1.60.1.70009).

Each test names the rule id of docs/talent-allocation-semantics.md it exercises.
"""

from __future__ import annotations

import pytest

from talent_data.allocation import (
    AllocationEngineError,
    AllocationIssueCode,
    BuildState,
    TalentAllocationEngine,
)
from talent_data.allocation.conformance import Mutation, apply_mutations
from talent_data.models.talents import TalentDataDocument
from tests.helpers import rehash
from tests.unit.allocation.conftest import node

C = AllocationIssueCode


def plan(engine: TalentAllocationEngine, ranks: dict[str, int]) -> BuildState:
    state = engine.empty_state()
    for name, rank in ranks.items():
        n = node(engine, name)
        state = state.with_allocation(n.id, n.entries[0].id, rank)
    return state


def spend_all(engine: TalentAllocationEngine, names: list[str]) -> BuildState:
    state = engine.empty_state()
    for name in names:
        result = engine.spend(state, node(engine, name).id)
        assert result.allowed, (name, result.decision.reason_codes)
        state = result.state
    return state


# --------------------------------------------------------------------------- construction


def test_engine_uses_dataset_budget_and_optional_lower_limit(
    docs: dict[str, TalentDataDocument],
) -> None:
    assert TalentAllocationEngine(docs["WARRIOR"]).point_budget == 51  # BUDGET-1
    assert TalentAllocationEngine(docs["WARRIOR"], point_limit=10).point_budget == 10
    for bad in (-1, 52):
        with pytest.raises(AllocationEngineError, match="point_limit"):
            TalentAllocationEngine(docs["WARRIOR"], point_limit=bad)


def test_engine_refuses_invalid_or_ambiguous_documents(
    docs: dict[str, TalentDataDocument],
) -> None:
    doc = docs["WARRIOR"]
    tampered = doc.model_copy(update={"content_hash": "sha256:" + "0" * 64})
    with pytest.raises(AllocationEngineError, match="validation errors"):
        TalentAllocationEngine(tampered)
    with pytest.raises(AllocationEngineError, match="no tree 1"):
        TalentAllocationEngine(doc, tree_id=1)
    assert TalentAllocationEngine(doc, tree_id=doc.trees[0].id).tree.id == doc.trees[0].id
    two_trees = rehash(doc.model_copy(update={"trees": [doc.trees[0], doc.trees[0]]}))
    with pytest.raises(AllocationEngineError):
        TalentAllocationEngine(two_trees)  # duplicate tree id is a document error
    no_budget = rehash(
        doc.model_copy(update={"trees": [doc.trees[0].model_copy(update={"point_budget": None})]})
    )
    with pytest.raises(AllocationEngineError, match="no point budget"):
        TalentAllocationEngine(no_budget)


# --------------------------------------------------------------------------- spend


def test_basic_purchase_costs_one_point(warrior: TalentAllocationEngine) -> None:
    ihs = node(warrior, "Improved Heroic Strike")
    empty = warrior.empty_state()
    result = warrior.spend(empty, ihs.id)
    assert result.allowed
    assert result.state.rank_of(ihs.id) == 1
    assert result.state.allocation_for(ihs.id).entry_id == ihs.entries[0].id  # type: ignore[union-attr]
    assert (warrior.spent_points(result.state), warrior.remaining_points(result.state)) == (1, 50)
    assert empty == warrior.empty_state()  # input not mutated


def test_max_rank_rejected_without_state_change(warrior: TalentAllocationEngine) -> None:
    state = plan(warrior, {"Improved Heroic Strike": 3})
    result = warrior.spend(state, node(warrior, "Improved Heroic Strike").id)
    assert not result.allowed
    assert result.decision.reason_codes == ("MAX_RANK_REACHED",)
    issue = result.decision.issues[0]
    assert (issue.required, issue.actual) == (3, 3)
    assert result.state is state


def test_gate_counts_points_spent_in_counted_nodes_before_purchase(
    warrior: TalentAllocationEngine,
) -> None:
    """GATE-1: Improved Charge needs 5 points in Arms row 0."""
    charge = node(warrior, "Improved Charge")
    four = plan(warrior, {"Improved Heroic Strike": 3, "Deflection": 1})
    decision = warrior.can_spend(four, charge.id)
    assert decision.reason_codes == ("TIER_GATE_NOT_MET",)
    assert (decision.issues[0].required, decision.issues[0].actual) == (5, 4)
    five = plan(warrior, {"Improved Heroic Strike": 3, "Deflection": 2})
    assert warrior.can_spend(five, charge.id).allowed
    other_tab = plan(warrior, {"Booming Voice": 5})
    assert warrior.can_spend(other_tab, charge.id).reason_codes == ("TIER_GATE_NOT_MET",)


def test_edge_needs_fully_ranked_source(warrior: TalentAllocationEngine) -> None:
    """EDGE-ACTIVE: Improved Rend (3 ranks) -> Deep Wounds."""
    deep_wounds = node(warrior, "Deep Wounds")
    base = {"Improved Heroic Strike": 3, "Improved Tactical Mastery": 3}
    for partial in (0, 1, 2):
        # always exactly 10 points in rows 0-1, so only the edge can fail
        ranks = {**base, "Deflection": 4 - partial}
        if partial:
            ranks["Improved Rend"] = partial
        state = plan(warrior, ranks)
        assert warrior.validate(state).valid
        decision = warrior.can_spend(state, deep_wounds.id)
        assert decision.reason_codes == ("SUFFICIENT_PREREQUISITE_NOT_MET",), partial
        assert decision.issues[0].related_node_ids == (node(warrior, "Improved Rend").id,)
    full = plan(warrior, {**base, "Deflection": 1, "Improved Rend": 3})
    assert warrior.can_spend(full, deep_wounds.id).allowed


def test_all_failing_rules_are_reported_together(warrior: TalentAllocationEngine) -> None:
    decision = warrior.can_spend(warrior.empty_state(), node(warrior, "Deep Wounds").id)
    assert decision.reason_codes == ("SUFFICIENT_PREREQUISITE_NOT_MET", "TIER_GATE_NOT_MET")


def test_budget_exhaustion(docs: dict[str, TalentDataDocument]) -> None:
    engine = TalentAllocationEngine(docs["WARRIOR"], point_limit=2)
    state = spend_all(engine, ["Improved Heroic Strike", "Deflection"])
    decision = engine.can_spend(state, node(engine, "Improved Rend").id)
    assert decision.reason_codes == ("NO_POINTS_REMAINING",)
    assert engine.remaining_points(state) == 0


def test_unknown_node_and_wrong_entry(warrior: TalentAllocationEngine) -> None:
    empty = warrior.empty_state()
    assert warrior.can_spend(empty, 42).reason_codes == ("UNKNOWN_NODE",)
    assert warrior.can_refund(empty, 42).reason_codes == ("UNKNOWN_NODE",)
    ihs, deflection = node(warrior, "Improved Heroic Strike"), node(warrior, "Deflection")
    decision = warrior.can_spend(empty, ihs.id, deflection.entries[0].id)
    assert decision.reason_codes == ("INVALID_ENTRY",)
    assert warrior.can_spend(empty, ihs.id, ihs.entries[0].id).allowed


def test_multi_entry_node_requires_an_entry_and_fails_closed(
    docs: dict[str, TalentDataDocument],
) -> None:
    doc = docs["WARRIOR"]
    tree = doc.trees[0]
    ihs = next(n for n in tree.nodes if n.name == "Improved Heroic Strike")
    extra = ihs.entries[0].model_copy(update={"id": 99_999_999})
    two = ihs.model_copy(update={"entries": [ihs.entries[0], extra]})
    mutated = rehash(
        doc.model_copy(
            update={
                "trees": [
                    tree.model_copy(
                        update={"nodes": [two if n.id == ihs.id else n for n in tree.nodes]}
                    )
                ]
            }
        )
    )
    engine = TalentAllocationEngine(mutated)
    empty = engine.empty_state()
    assert engine.can_spend(empty, ihs.id).reason_codes == ("ENTRY_NOT_SELECTED",)
    assert engine.can_spend(empty, ihs.id, extra.id).reason_codes == ("UNSUPPORTED_NODE_TYPE",)


def test_actions_on_an_invalid_state_are_refused(warrior: TalentAllocationEngine) -> None:
    broken = plan(warrior, {"Deep Wounds": 1})
    assert not warrior.validate(broken).valid
    for decision in (
        warrior.can_spend(broken, node(warrior, "Improved Heroic Strike").id),
        warrior.can_refund(broken, node(warrior, "Deep Wounds").id),
    ):
        assert decision.reason_codes == ("INVALID_BUILD_STATE",)


# --------------------------------------------------------------------------- refund


def test_refund_is_blocked_by_dependents_without_cascade(
    warrior: TalentAllocationEngine,
) -> None:
    """REFUND-1: a refund must keep the plan valid; nothing is removed implicitly."""
    state = plan(
        warrior,
        {
            "Improved Heroic Strike": 3,
            "Deflection": 2,
            "Improved Rend": 3,
            "Improved Tactical Mastery": 3,
            "Deep Wounds": 1,
        },
    )
    rend, wounds = node(warrior, "Improved Rend"), node(warrior, "Deep Wounds")
    result = warrior.refund(state, rend.id)
    assert not result.allowed
    assert result.state is state
    (issue,) = result.decision.issues
    assert issue.code is C.DEPENDENT_NODE_WOULD_BREAK
    assert issue.node_id == wounds.id
    assert issue.related_node_ids == (rend.id,)
    assert issue.cause is C.SUFFICIENT_PREREQUISITE_NOT_MET
    leaf = warrior.refund(state, wounds.id)
    assert leaf.allowed
    assert leaf.state.rank_of(wounds.id) == 0
    assert warrior.refund(leaf.state, rend.id).allowed


def test_refund_blocked_by_tier_gate(warrior: TalentAllocationEngine) -> None:
    state = plan(warrior, {"Improved Heroic Strike": 3, "Deflection": 2, "Improved Charge": 1})
    decision = warrior.can_refund(state, node(warrior, "Deflection").id)
    (issue,) = decision.issues
    assert (issue.code, issue.cause) == (C.DEPENDENT_NODE_WOULD_BREAK, C.TIER_GATE_NOT_MET)
    assert (issue.required, issue.actual) == (5, 4)


def test_multi_rank_refund_down_to_zero(warrior: TalentAllocationEngine) -> None:
    ihs = node(warrior, "Improved Heroic Strike")
    state = plan(warrior, {"Improved Heroic Strike": 3})
    for expected in (2, 1, 0):
        state = warrior.refund(state, ihs.id).state
        assert state.rank_of(ihs.id) == expected
    assert state == warrior.empty_state()
    assert warrior.can_refund(state, ihs.id).reason_codes == ("NOT_ALLOCATED",)


# --------------------------------------------------------------------------- validate


def test_validate_reports_structural_problems(warrior: TalentAllocationEngine) -> None:
    ihs, rend = node(warrior, "Improved Heroic Strike"), node(warrior, "Improved Rend")
    state = BuildState.of(
        warrior.tree.id,
        [
            (ihs.id, ihs.entries[0].id, 4),  # > max
            (rend.id, rend.entries[0].id, 1),
            (rend.id, rend.entries[0].id, 1),  # duplicate
            (123, 1, 1),  # unknown node
            (node(warrior, "Deflection").id, ihs.entries[0].id, 1),  # wrong entry
        ],
    )
    result = warrior.validate(state)
    assert result.reason_codes == (
        "DUPLICATE_ALLOCATION",
        "INVALID_ENTRY",
        "RANK_OUT_OF_BOUNDS",
        "UNKNOWN_NODE",
    )
    other_tree = BuildState(tree_id=1, allocations=())
    assert warrior.validate(other_tree).reason_codes == ("UNKNOWN_TREE",)


def test_validate_is_independent_of_history(warrior: TalentAllocationEngine) -> None:
    """VALID-1: the same allocations validate the same, however they were produced."""
    names = [
        "Improved Rend",
        "Improved Rend",
        "Improved Rend",
        "Improved Heroic Strike",
        "Improved Heroic Strike",
        "Improved Heroic Strike",
        "Improved Tactical Mastery",
        "Improved Tactical Mastery",
        "Improved Tactical Mastery",
        "Improved Tactical Mastery",
        "Deep Wounds",
    ]
    built = spend_all(warrior, names)
    imported = plan(
        warrior,
        {
            "Deep Wounds": 1,
            "Improved Tactical Mastery": 4,
            "Improved Heroic Strike": 3,
            "Improved Rend": 3,
        },
    )
    assert built == imported
    assert warrior.validate(imported).valid


def test_validate_counts_capstone_gate_before_its_own_point(
    warrior: TalentAllocationEngine,
) -> None:
    """GATE-1 + VALID-1: Mortal Strike's gate (30 in the whole Arms tab, itself included)
    is evaluated before the purchase, so it is the 31st Arms point."""
    arms = {
        "Improved Heroic Strike": 3,
        "Deflection": 5,
        "Improved Rend": 3,
        "Improved Charge": 2,
        "Improved Tactical Mastery": 5,
        "Anger Management": 1,
        "Deep Wounds": 3,
        "Two-Handed Weapon Specialization": 3,
        "Impale": 2,
        "Sweeping Strikes": 1,
    }
    assert sum(arms.values()) == 28
    for extra, valid in ((1, False), (2, True)):
        state = plan(warrior, {**arms, "Weaponmaster": extra, "Mortal Strike": 1})
        result = warrior.validate(state)
        assert result.valid is valid, result.reason_codes
        if not valid:
            assert result.reason_codes == ("TIER_GATE_NOT_MET",)
            assert result.issues[0].node_id == node(warrior, "Mortal Strike").id


def test_points_are_never_negative(warrior: TalentAllocationEngine) -> None:
    ihs = node(warrior, "Improved Heroic Strike")
    corrupt = BuildState.of(warrior.tree.id, [(ihs.id, ihs.entries[0].id, -7)])
    assert warrior.spent_points(corrupt) == 0
    assert warrior.remaining_points(corrupt) == 51
    engine = TalentAllocationEngine(warrior.document, point_limit=1)
    over = plan(engine, {"Improved Heroic Strike": 3})
    assert engine.remaining_points(over) == 0
    assert engine.validate(over).reason_codes == ("POINT_BUDGET_EXCEEDED",)


# --------------------------------------------------------------------------- fail closed


@pytest.mark.parametrize(
    ("op", "value", "code"),
    [
        ("setNodeType", "selection", "UNSUPPORTED_NODE_TYPE"),
        ("setNodeType", "tiered", "UNSUPPORTED_NODE_TYPE"),
        ("setNodeType", "unknown:7", "UNSUPPORTED_NODE_TYPE"),
        ("setNodeFlags", 2, "NODE_NEVER_PURCHASABLE"),
        ("setNodeFlags", 16, "UNSUPPORTED_NODE_FLAGS"),
        ("setNodeFlags", 2 | 8 | 256, "NODE_NEVER_PURCHASABLE,UNSUPPORTED_NODE_FLAGS"),
        ("setEntryType", 12, "UNSUPPORTED_ENTRY_TYPE"),
        ("addUnresolvedCondition", 50984, "UNRESOLVED_CONDITION"),
    ],
)
def test_unproven_mechanics_fail_closed(
    docs: dict[str, TalentDataDocument], op: str, value: int | str, code: str
) -> None:
    doc = docs["WARRIOR"]
    ihs = next(n for n in doc.trees[0].nodes if n.name == "Improved Heroic Strike")
    engine = TalentAllocationEngine(
        apply_mutations(doc, [Mutation(op=op, node_id=ihs.id, value=value)])  # type: ignore[arg-type]
    )
    decision = engine.can_spend(engine.empty_state(), ihs.id)
    assert decision.reason_codes == tuple(code.split(","))
    assert ihs.id in engine.unsupported_nodes()
    imported = BuildState.of(engine.tree.id, [(ihs.id, ihs.entries[0].id, 1)])
    assert set(engine.validate(imported).reason_codes) == set(code.split(","))


def test_mixed_edge_kinds_fail_closed(docs: dict[str, TalentDataDocument]) -> None:
    doc = docs["WARRIOR"]
    names = {n.name: n.id for n in doc.trees[0].nodes}
    target = names["Improved Overpower"]
    engine = TalentAllocationEngine(
        apply_mutations(
            doc,
            [
                Mutation(
                    op="addPrerequisite",
                    node_id=target,
                    prerequisite_node_id=names["Improved Heroic Strike"],
                    kind="required",
                ),
                Mutation(
                    op="addPrerequisite",
                    node_id=target,
                    prerequisite_node_id=names["Improved Rend"],
                    kind="sufficient",
                ),
            ],
        )
    )
    state = plan(engine, {"Improved Heroic Strike": 3, "Improved Rend": 3})
    assert engine.can_spend(state, target).reason_codes == ("UNSUPPORTED_EDGE_COMBINATION",)


def test_multiple_required_edges_need_all_sources(docs: dict[str, TalentDataDocument]) -> None:
    """EDGE-REQ on a synthetic node (Forever has one Required edge, never two)."""
    doc = docs["WARRIOR"]
    names = {n.name: n.id for n in doc.trees[0].nodes}
    target = names["Improved Overpower"]
    engine = TalentAllocationEngine(
        apply_mutations(
            doc,
            [
                Mutation(
                    op="addPrerequisite", node_id=target, prerequisite_node_id=names[s], kind=k
                )
                for s, k in (("Improved Heroic Strike", "required"), ("Improved Rend", "required"))
            ],
        )
    )
    partial = plan(engine, {"Improved Heroic Strike": 3, "Improved Rend": 2})
    decision = engine.can_spend(partial, target)
    assert decision.reason_codes == ("REQUIRED_PREREQUISITE_NOT_MET",)
    assert decision.issues[0].related_node_ids == (names["Improved Rend"],)
    full = plan(engine, {"Improved Heroic Strike": 3, "Improved Rend": 3})
    assert engine.can_spend(full, target).allowed
