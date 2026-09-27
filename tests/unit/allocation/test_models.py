"""Value types: immutability, canonical order, stable codes, JSON round trips."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from talent_data.allocation.models import (
    AllocationDecision,
    AllocationIssue,
    AllocationIssueCode,
    BuildFile,
    BuildState,
    NodeAllocation,
    reason_codes,
)

# The public reason-code contract. Adding a code is a reviewed contract change (update this
# list, the docs and the Kotlin handoff); renaming or removing one is a breaking change.
STABLE_CODES = [
    "UNKNOWN_TREE",
    "UNKNOWN_NODE",
    "INVALID_ENTRY",
    "ENTRY_NOT_SELECTED",
    "DUPLICATE_ALLOCATION",
    "RANK_OUT_OF_BOUNDS",
    "INVALID_BUILD_STATE",
    "UNSUPPORTED_NODE_TYPE",
    "UNSUPPORTED_ENTRY_TYPE",
    "UNSUPPORTED_NODE_FLAGS",
    "UNSUPPORTED_EDGE_COMBINATION",
    "UNRESOLVED_CONDITION",
    "NODE_NEVER_PURCHASABLE",
    "MAX_RANK_REACHED",
    "NOT_ALLOCATED",
    "NO_POINTS_REMAINING",
    "POINT_BUDGET_EXCEEDED",
    "TIER_GATE_NOT_MET",
    "REQUIRED_PREREQUISITE_NOT_MET",
    "SUFFICIENT_PREREQUISITE_NOT_MET",
    "DEPENDENT_NODE_WOULD_BREAK",
]


def test_reason_codes_are_a_stable_contract() -> None:
    assert [c.value for c in AllocationIssueCode] == STABLE_CODES
    assert all(c.value == c.name for c in AllocationIssueCode)


def test_build_state_is_canonical_and_immutable() -> None:
    a = BuildState.of(1, [(3, 30, 1), (1, 10, 2)])
    b = BuildState.of(1, [(1, 10, 2), (3, 30, 1)])
    assert a == b
    assert [x.node_id for x in a.allocations] == [1, 3]
    with pytest.raises(ValidationError):
        a.tree_id = 2  # type: ignore[misc]
    c = a.with_allocation(1, 10, 3)
    assert a.rank_of(1) == 2  # the original is untouched
    assert c.rank_of(1) == 3
    assert c.with_allocation(1, 10, 0).allocation_for(1) is None
    assert c.rank_of(99) == 0


def test_build_state_json_round_trip_uses_camel_case() -> None:
    state = BuildState.of(1117, [(105958, 130688, 3)])
    data = state.model_dump(mode="json", by_alias=True)
    assert data == {
        "treeId": 1117,
        "allocations": [{"nodeId": 105958, "entryId": 130688, "rank": 3}],
    }
    assert BuildState.model_validate(data) == state


def test_build_state_rejects_unknown_fields_but_keeps_corrupt_values() -> None:
    with pytest.raises(ValidationError):
        BuildState.model_validate({"treeId": 1, "allocations": [], "extra": 1})
    # A negative rank is representable: validate() must be able to report it.
    corrupt = BuildState.model_validate(
        {"treeId": 1, "allocations": [{"nodeId": 1, "entryId": 1, "rank": -4}]}
    )
    assert corrupt.allocations[0].rank == -4


def test_build_file_round_trip() -> None:
    build = BuildFile.model_validate(
        {
            "game": "forever",
            "clientBuild": "1.60.1.70009",
            "class": "WARRIOR",
            "treeId": 1117,
            "allocations": [{"nodeId": 105958, "entryId": 130688, "rank": 1}],
        }
    )
    assert build.schema_version == 1
    assert BuildFile.model_validate_json(build.to_json()) == build
    assert build.to_state() == BuildState(
        tree_id=1117, allocations=(NodeAllocation(node_id=105958, entry_id=130688, rank=1),)
    )


def test_decision_orders_and_deduplicates_issues() -> None:
    gate = AllocationIssue(AllocationIssueCode.TIER_GATE_NOT_MET, "g", node_id=5)
    rank = AllocationIssue(AllocationIssueCode.MAX_RANK_REACHED, "r", node_id=5)
    decision = AllocationDecision.of([gate, rank, gate])
    assert decision.issues == (rank, gate)  # declaration order of the codes, no duplicate
    assert not decision.allowed
    assert decision.reason_codes == ("MAX_RANK_REACHED", "TIER_GATE_NOT_MET")
    assert AllocationDecision.of([]).allowed
    assert reason_codes([]) == ()


def test_issue_json_is_structural_and_message_free() -> None:
    issue = AllocationIssue(
        AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK,
        "human text",
        node_id=2,
        related_node_ids=(1,),
        required=10,
        actual=9,
        cause=AllocationIssueCode.TIER_GATE_NOT_MET,
    )
    assert issue.to_json() == {
        "code": "DEPENDENT_NODE_WOULD_BREAK",
        "nodeId": 2,
        "relatedNodeIds": [1],
        "required": 10,
        "actual": 9,
        "cause": "TIER_GATE_NOT_MET",
    }
    assert str(issue) == "[DEPENDENT_NODE_WOULD_BREAK] human text"
