"""Value types of the allocation engine: build state, typed issues and decisions.

Everything here is immutable. A :class:`BuildState` is a *plan* of purchased ranks for one
talent tree; the engine never mutates it, every transition returns a new state.

Machine-readable first: automation (UI, conformance runners, the future Kotlin engine)
branches on :class:`AllocationIssueCode` and the structured fields of
:class:`AllocationIssue`, never on ``message``. Codes are a stable public contract; the
message wording is not.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

BUILD_FILE_SCHEMA_VERSION: Literal[1] = 1


class AllocationIssueCode(StrEnum):
    """Stable reason codes. Declaration order is the canonical sort order of issues."""

    # --- identity / structure of the request or the build state
    UNKNOWN_TREE = "UNKNOWN_TREE"
    UNKNOWN_NODE = "UNKNOWN_NODE"
    INVALID_ENTRY = "INVALID_ENTRY"
    ENTRY_NOT_SELECTED = "ENTRY_NOT_SELECTED"
    DUPLICATE_ALLOCATION = "DUPLICATE_ALLOCATION"
    RANK_OUT_OF_BOUNDS = "RANK_OUT_OF_BOUNDS"
    INVALID_BUILD_STATE = "INVALID_BUILD_STATE"
    # --- fail-closed: mechanics the engine cannot prove
    UNSUPPORTED_NODE_TYPE = "UNSUPPORTED_NODE_TYPE"
    UNSUPPORTED_ENTRY_TYPE = "UNSUPPORTED_ENTRY_TYPE"
    UNSUPPORTED_NODE_FLAGS = "UNSUPPORTED_NODE_FLAGS"
    UNSUPPORTED_EDGE_COMBINATION = "UNSUPPORTED_EDGE_COMBINATION"
    UNRESOLVED_CONDITION = "UNRESOLVED_CONDITION"
    NODE_NEVER_PURCHASABLE = "NODE_NEVER_PURCHASABLE"
    # --- ranks and points
    MAX_RANK_REACHED = "MAX_RANK_REACHED"
    NOT_ALLOCATED = "NOT_ALLOCATED"
    NO_POINTS_REMAINING = "NO_POINTS_REMAINING"
    POINT_BUDGET_EXCEEDED = "POINT_BUDGET_EXCEEDED"
    # --- availability
    TIER_GATE_NOT_MET = "TIER_GATE_NOT_MET"
    REQUIRED_PREREQUISITE_NOT_MET = "REQUIRED_PREREQUISITE_NOT_MET"
    SUFFICIENT_PREREQUISITE_NOT_MET = "SUFFICIENT_PREREQUISITE_NOT_MET"
    # --- refund
    DEPENDENT_NODE_WOULD_BREAK = "DEPENDENT_NODE_WOULD_BREAK"


_CODE_ORDER = {code: index for index, code in enumerate(AllocationIssueCode)}


@dataclass(frozen=True)
class AllocationIssue:
    """One reason why an action is not allowed / a build is not valid.

    ``node_id`` is the node the issue is *about* (for DEPENDENT_NODE_WOULD_BREAK: the
    dependent that would break). ``related_node_ids`` are the other nodes involved
    (prerequisite sources, the node being refunded, ...). ``required``/``actual`` carry
    numbers (points, ranks, flags) where the code has them. ``cause`` is set on
    DEPENDENT_NODE_WOULD_BREAK: the availability rule the dependent would fail.
    """

    code: AllocationIssueCode
    message: str
    node_id: int | None = None
    entry_id: int | None = None
    related_node_ids: tuple[int, ...] = ()
    condition_id: int | None = None
    required: int | None = None
    actual: int | None = None
    cause: AllocationIssueCode | None = None

    def sort_key(self) -> tuple[Any, ...]:
        return (
            _CODE_ORDER[self.code],
            -1 if self.node_id is None else self.node_id,
            -1 if self.entry_id is None else self.entry_id,
            self.related_node_ids,
            -1 if self.condition_id is None else self.condition_id,
            -1 if self.cause is None else _CODE_ORDER[self.cause],
        )

    def to_json(self) -> dict[str, Any]:
        """Structural, message-free JSON (the part other implementations must reproduce)."""
        out: dict[str, Any] = {"code": self.code.value}
        if self.node_id is not None:
            out["nodeId"] = self.node_id
        if self.entry_id is not None:
            out["entryId"] = self.entry_id
        if self.related_node_ids:
            out["relatedNodeIds"] = list(self.related_node_ids)
        if self.condition_id is not None:
            out["conditionId"] = self.condition_id
        if self.required is not None:
            out["required"] = self.required
        if self.actual is not None:
            out["actual"] = self.actual
        if self.cause is not None:
            out["cause"] = self.cause.value
        return out

    def __str__(self) -> str:
        return f"[{self.code}] {self.message}"


def sort_issues(issues: Iterable[AllocationIssue]) -> tuple[AllocationIssue, ...]:
    """Canonical, deterministic order (and de-duplication) of issues."""
    unique = {(i.sort_key(), i.message): i for i in issues}
    return tuple(unique[k] for k in sorted(unique))


def reason_codes(issues: Iterable[AllocationIssue]) -> tuple[str, ...]:
    """Sorted, de-duplicated code values - the compact form used by conformance vectors."""
    return tuple(sorted({i.code.value for i in issues}))


@dataclass(frozen=True)
class AllocationDecision:
    """Answer of ``can_spend`` / ``can_refund``: allowed iff there is no issue."""

    issues: tuple[AllocationIssue, ...] = ()

    @property
    def allowed(self) -> bool:
        return not self.issues

    @property
    def reason_codes(self) -> tuple[str, ...]:
        return reason_codes(self.issues)

    @classmethod
    def of(cls, issues: Iterable[AllocationIssue]) -> AllocationDecision:
        return cls(sort_issues(issues))


# --------------------------------------------------------------------------- build state


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
    )


class NodeAllocation(_Model):
    """Purchased ranks of one node: which entry is selected and how many ranks it has."""

    node_id: int = Field(description="TraitNode.ID (TalentNode.id)")
    entry_id: int = Field(description="TraitNodeEntry.ID of the selected entry of the node")
    rank: int = Field(description="Purchased ranks of that entry (1..entry.maxRank when valid)")


class BuildState(_Model):
    """An immutable talent plan for one tree.

    Allocations are kept in canonical order (node id, entry id). The container itself is
    deliberately lenient (duplicates, out-of-range ranks, unknown ids are representable) so
    that an imported/corrupt build can be *reported* by ``engine.validate`` instead of being
    rejected before anyone can explain what is wrong with it.
    """

    tree_id: int = Field(description="TraitTree.ID the plan belongs to")
    allocations: tuple[NodeAllocation, ...] = ()

    @field_validator("allocations")
    @classmethod
    def _canonical_order(cls, value: tuple[NodeAllocation, ...]) -> tuple[NodeAllocation, ...]:
        return tuple(sorted(value, key=lambda a: (a.node_id, a.entry_id, a.rank)))

    @classmethod
    def empty(cls, tree_id: int) -> BuildState:
        return cls(tree_id=tree_id)

    @classmethod
    def of(cls, tree_id: int, allocations: Iterable[tuple[int, int, int]]) -> BuildState:
        """Build from ``(node_id, entry_id, rank)`` triples."""
        return cls(
            tree_id=tree_id,
            allocations=tuple(
                NodeAllocation(node_id=n, entry_id=e, rank=r) for n, e, r in allocations
            ),
        )

    def allocation_for(self, node_id: int) -> NodeAllocation | None:
        """The first allocation of ``node_id`` (a valid state has at most one)."""
        return next((a for a in self.allocations if a.node_id == node_id), None)

    def rank_of(self, node_id: int) -> int:
        allocation = self.allocation_for(node_id)
        return allocation.rank if allocation else 0

    def ranks(self) -> Mapping[int, int]:
        """node id -> purchased rank (meaningful for valid states: unique node ids)."""
        return {a.node_id: a.rank for a in self.allocations}

    def with_allocation(self, node_id: int, entry_id: int, rank: int) -> BuildState:
        """New state where ``node_id`` holds ``rank`` ranks of ``entry_id`` (0 removes it)."""
        kept = [a for a in self.allocations if a.node_id != node_id]
        if rank > 0:
            kept.append(NodeAllocation(node_id=node_id, entry_id=entry_id, rank=rank))
        return BuildState(tree_id=self.tree_id, allocations=tuple(kept))


class BuildFile(_Model):
    """Portable build file (``allocation-build.v1``): a plan bound to a dataset identity."""

    schema_version: Literal[1] = BUILD_FILE_SCHEMA_VERSION
    game: str
    client_build: str
    class_: str = Field(alias="class", description="Class token, e.g. WARRIOR")
    tree_id: int
    allocations: tuple[NodeAllocation, ...] = ()

    def to_state(self) -> BuildState:
        return BuildState(tree_id=self.tree_id, allocations=self.allocations)

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


# --------------------------------------------------------------------------- results


@dataclass(frozen=True)
class AllocationResult:
    """Outcome of ``spend`` / ``refund``: the decision and the resulting state.

    When the action is not allowed, ``state`` is the input state, unchanged.
    """

    decision: AllocationDecision
    state: BuildState

    @property
    def allowed(self) -> bool:
        return self.decision.allowed


@dataclass(frozen=True)
class BuildValidation:
    """Outcome of ``validate``: a build is valid iff there is no issue."""

    issues: tuple[AllocationIssue, ...]
    spent_points: int
    remaining_points: int
    point_budget: int
    reached_ranks: Mapping[int, int] = field(default_factory=dict)

    @property
    def valid(self) -> bool:
        return not self.issues

    @property
    def reason_codes(self) -> tuple[str, ...]:
        return reason_codes(self.issues)
