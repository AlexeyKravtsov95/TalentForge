"""Allocation conformance vectors (schema ``allocation-conformance.v1``).

A conformance suite is a versioned, machine-readable list of scenarios that any
implementation of the Forever allocation rules (this Python reference engine, the future
Kotlin Multiplatform engine) must reproduce exactly. Each scenario names a real normalized
dataset (by class; pinned by content hash), optionally applies explicit synthetic
*mutations* to it (to exercise mechanics the real data does not contain), starts from an
initial allocation and runs steps (``spend`` / ``refund`` / ``validate``) whose expected
outcome is fully specified: ``ok``, sorted ``reasonCodes``, the structural issue list, the
points after the step and the rank of the touched node.

Only codes and structural fields are part of the contract - never issue messages.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from talent_data.allocation.engine import TalentAllocationEngine
from talent_data.allocation.models import AllocationIssue, BuildState, NodeAllocation
from talent_data.models.talents import (
    Prerequisite,
    TalentDataDocument,
    TalentNode,
    content_hash,
    semantic_payload,
)

CONFORMANCE_SCHEMA_VERSION: Literal[1] = 1


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
    )


class DatasetRef(_Model):
    class_: str = Field(alias="class", description="Class token, e.g. WARRIOR")
    file: str = Field(description="Normalized document path, relative to the repository root")
    content_hash: str = Field(description="contentHash the document must have")


class Mutation(_Model):
    """A synthetic, explicit change applied to the real dataset before the scenario runs.

    op
        ``addPrerequisite``   add edge ``prerequisiteNodeId -> nodeId`` of ``kind``
        ``setNodeType``       set TalentNode.type to ``value`` (a string)
        ``setNodeFlags``      set TalentNode.flags to ``value`` (an int)
        ``setEntryType``      set the entryType of every entry of the node to ``value``
        ``addUnresolvedCondition``  append ``value`` (a TraitCond id) to
                              unresolvedConditionIds
    """

    op: Literal[
        "addPrerequisite",
        "setNodeType",
        "setNodeFlags",
        "setEntryType",
        "addUnresolvedCondition",
    ]
    node_id: int
    prerequisite_node_id: int | None = None
    kind: Literal["sufficient", "required"] | None = None
    value: int | str | None = None


class ExpectedIssue(_Model):
    code: str
    node_id: int | None = None
    entry_id: int | None = None
    related_node_ids: list[int] = Field(default_factory=list)
    condition_id: int | None = None
    required: int | None = None
    actual: int | None = None
    cause: str | None = None


class Expectation(_Model):
    ok: bool = Field(description="spend/refund: action allowed; validate: build valid")
    reason_codes: list[str] = Field(description="Sorted, de-duplicated issue codes")
    issues: list[ExpectedIssue] = Field(description="Issues in canonical order, no messages")
    spent_points: int = Field(description="Points spent by the state after the step")
    remaining_points: int = Field(description="Points remaining after the step")
    node_rank: int | None = Field(
        default=None, description="spend/refund: rank of nodeId after the step"
    )


class Step(_Model):
    op: Literal["spend", "refund", "validate"]
    node_id: int | None = None
    entry_id: int | None = None
    expect: Expectation


class Scenario(_Model):
    id: str
    class_: str = Field(alias="class")
    description: str
    rules: list[str] = Field(description="Rule ids (docs/talent-allocation-semantics.md)")
    synthetic: bool = Field(description="True when mutations change the real dataset")
    point_limit: int | None = None
    mutations: list[Mutation] = Field(default_factory=list)
    initial: list[NodeAllocation] = Field(default_factory=list)
    steps: list[Step]


class ConformanceSuite(_Model):
    schema_version: Literal[1] = CONFORMANCE_SCHEMA_VERSION
    game: str
    client_build: str
    datasets: list[DatasetRef]
    scenarios: list[Scenario]

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2, exclude_none=True) + "\n"


# --------------------------------------------------------------------------- mutations


def apply_mutations(doc: TalentDataDocument, mutations: list[Mutation]) -> TalentDataDocument:
    """Return ``doc`` with ``mutations`` applied to its (single) tree, re-hashed."""
    if not mutations:
        return doc
    if len(doc.trees) != 1:
        raise ValueError("mutations are defined for single-tree documents")
    tree = doc.trees[0]
    nodes = {n.id: n for n in tree.nodes}
    for mutation in mutations:
        if mutation.node_id not in nodes:
            raise ValueError(f"mutation targets unknown node {mutation.node_id}")
        nodes[mutation.node_id] = _MUTATORS[mutation.op](nodes[mutation.node_id], mutation)
    new_tree = tree.model_copy(update={"nodes": [nodes[n.id] for n in tree.nodes]})
    mutated = doc.model_copy(update={"trees": [new_tree]})
    digest = content_hash(semantic_payload(mutated.model_dump(mode="json", by_alias=True)))
    return mutated.model_copy(update={"content_hash": digest})


def _add_prerequisite(node: TalentNode, m: Mutation) -> TalentNode:
    if m.prerequisite_node_id is None or m.kind is None:
        raise ValueError("addPrerequisite needs prerequisiteNodeId and kind")
    prereqs = [*node.prerequisites, Prerequisite(node_id=m.prerequisite_node_id, kind=m.kind)]
    return node.model_copy(update={"prerequisites": sorted(prereqs, key=lambda p: p.node_id)})


def _set_node_type(node: TalentNode, m: Mutation) -> TalentNode:
    return node.model_copy(update={"type": str(m.value)})


def _set_node_flags(node: TalentNode, m: Mutation) -> TalentNode:
    if not isinstance(m.value, int):
        raise TypeError("setNodeFlags needs an int value")
    return node.model_copy(update={"flags": m.value})


def _set_entry_type(node: TalentNode, m: Mutation) -> TalentNode:
    if not isinstance(m.value, int):
        raise TypeError("setEntryType needs an int value")
    entries = [e.model_copy(update={"entry_type": m.value}) for e in node.entries]
    return node.model_copy(update={"entries": entries})


def _add_unresolved_condition(node: TalentNode, m: Mutation) -> TalentNode:
    if not isinstance(m.value, int):
        raise TypeError("addUnresolvedCondition needs an int value")
    ids = sorted({*node.unresolved_condition_ids, m.value})
    return node.model_copy(update={"unresolved_condition_ids": ids})


_MUTATORS: dict[str, Callable[[TalentNode, Mutation], TalentNode]] = {
    "addPrerequisite": _add_prerequisite,
    "setNodeType": _set_node_type,
    "setNodeFlags": _set_node_flags,
    "setEntryType": _set_entry_type,
    "addUnresolvedCondition": _add_unresolved_condition,
}


# --------------------------------------------------------------------------- runner


def expected_issue(issue: AllocationIssue) -> ExpectedIssue:
    return ExpectedIssue.model_validate(issue.to_json())


@dataclass(frozen=True)
class StepOutcome:
    step_index: int
    actual: Expectation
    expected: Expectation

    @property
    def matches(self) -> bool:
        return self.actual == self.expected


def run_step(
    engine: TalentAllocationEngine,
    state: BuildState,
    op: str,
    node_id: int | None,
    entry_id: int | None,
) -> tuple[Expectation, BuildState]:
    """Execute one step with the reference engine; return its observed outcome."""
    if op == "validate":
        validation = engine.validate(state)
        return (
            Expectation(
                ok=validation.valid,
                reason_codes=list(validation.reason_codes),
                issues=[expected_issue(i) for i in validation.issues],
                spent_points=validation.spent_points,
                remaining_points=validation.remaining_points,
            ),
            state,
        )
    if node_id is None:
        raise ValueError(f"{op} step needs a nodeId")
    if op == "spend":
        result = engine.spend(state, node_id, entry_id)
    elif op == "refund":
        result = engine.refund(state, node_id)
    else:
        raise ValueError(f"unknown step op {op!r}")
    new_state = result.state
    return (
        Expectation(
            ok=result.allowed,
            reason_codes=list(result.decision.reason_codes),
            issues=[expected_issue(i) for i in result.decision.issues],
            spent_points=engine.spent_points(new_state),
            remaining_points=engine.remaining_points(new_state),
            node_rank=new_state.rank_of(node_id),
        ),
        new_state,
    )


def engine_for(
    scenario: Scenario, documents: Mapping[str, TalentDataDocument]
) -> TalentAllocationEngine:
    doc = apply_mutations(documents[scenario.class_], scenario.mutations)
    return TalentAllocationEngine(doc, point_limit=scenario.point_limit)


def run_scenario(
    scenario: Scenario, documents: Mapping[str, TalentDataDocument]
) -> list[StepOutcome]:
    """Replay a scenario against the reference engine and compare every step."""
    engine = engine_for(scenario, documents)
    state = BuildState(tree_id=engine.tree.id, allocations=tuple(scenario.initial))
    outcomes = []
    for index, step in enumerate(scenario.steps):
        actual, state = run_step(engine, state, step.op, step.node_id, step.entry_id)
        outcomes.append(StepOutcome(index, actual, step.expect))
    return outcomes


def load_suite(path: Path) -> ConformanceSuite:
    return ConformanceSuite.model_validate_json(path.read_text(encoding="utf-8"))


def load_suite_documents(suite: ConformanceSuite, root: Path) -> dict[str, TalentDataDocument]:
    """Load the datasets a suite references and verify their pinned content hashes."""
    documents: dict[str, TalentDataDocument] = {}
    for ref in suite.datasets:
        doc = TalentDataDocument.model_validate_json((root / ref.file).read_text("utf-8"))
        if doc.content_hash != ref.content_hash:
            raise ValueError(
                f"{ref.file}: contentHash {doc.content_hash} != pinned {ref.content_hash}; "
                "the conformance suite was generated for another dataset"
            )
        documents[ref.class_] = doc
    return documents


def outcome_report(scenario: Scenario, outcomes: list[StepOutcome]) -> list[dict[str, Any]]:
    """Mismatching steps as plain data (for test failure messages / CLI output)."""
    return [
        {
            "scenario": scenario.id,
            "step": o.step_index,
            "expected": o.expected.model_dump(by_alias=True),
            "actual": o.actual.model_dump(by_alias=True),
        }
        for o in outcomes
        if not o.matches
    ]
