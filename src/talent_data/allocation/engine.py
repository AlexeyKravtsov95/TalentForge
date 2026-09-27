"""TalentAllocationEngine - the headless reference implementation of Forever allocation.

The engine answers, for one talent tree of a normalized :class:`TalentDataDocument`:

* can a rank be purchased / refunded, and exactly why not (typed issues);
* is an arbitrary build valid, independently of how it was created;
* how many points are spent / remaining.

It is pure and deterministic: no hidden mutation, no clock, no randomness, no I/O. The
document is the only source of talent data; nothing in here knows a class, a node id or a
build number. See docs/talent-allocation-semantics.md for the evidence behind each rule.

Validity is *reachability*: a plan is valid iff it can be obtained from an empty tree by
purchasing one rank at a time, each purchase allowed by the rules, within the point
budget. Forever class trees are flagged CannotRefund (TraitTree.Flags & 1), so in the game
a plan can only be realised by purchasing it (after a full reset at a trainer); the
reachability definition is therefore exactly "the game lets you build this".
"""

from __future__ import annotations

from talent_data.allocation.models import (
    AllocationDecision,
    AllocationIssue,
    AllocationIssueCode,
    AllocationResult,
    BuildState,
    BuildValidation,
    sort_issues,
)
from talent_data.allocation.rules import (
    RANK_COST_POINTS,
    TreeIndex,
    availability_issues,
    points_spent,
    reachable_ranks,
)
from talent_data.models.talents import TalentDataDocument, TalentEntry, TalentNode, TalentTree
from talent_data.validation.validators import validate_document

Code = AllocationIssueCode


class AllocationEngineError(ValueError):
    """The engine cannot be constructed for this document/configuration."""


class TalentAllocationEngine:
    """Allocation rules for one tree of a normalized talent document."""

    def __init__(
        self,
        document: TalentDataDocument,
        *,
        tree_id: int | None = None,
        point_limit: int | None = None,
    ) -> None:
        report = validate_document(document)
        if not report.ok:
            codes = sorted({i.code for i in report.errors})
            raise AllocationEngineError(
                f"document {document.class_.token} {document.client_build} has validation "
                f"errors {codes}; refusing to allocate on an invalid dataset"
            )
        self._document = document
        self._tree = _select_tree(document, tree_id)
        if self._tree.point_budget is None:
            raise AllocationEngineError(
                f"tree {self._tree.id} has no point budget (TraitTreeXTraitCurrency); "
                "points cannot be counted - fail closed"
            )
        max_points = self._tree.point_budget.max_points
        if point_limit is not None and not 0 <= point_limit <= max_points:
            raise AllocationEngineError(
                f"point_limit must be within 0..{max_points} (tree {self._tree.id} budget), "
                f"got {point_limit}"
            )
        self._budget = max_points if point_limit is None else point_limit
        self._index = TreeIndex.build(self._tree)

    # ------------------------------------------------------------------ properties

    @property
    def document(self) -> TalentDataDocument:
        return self._document

    @property
    def tree(self) -> TalentTree:
        return self._tree

    @property
    def point_budget(self) -> int:
        """Usable points: the dataset budget, or the configured lower ``point_limit``."""
        return self._budget

    def empty_state(self) -> BuildState:
        return BuildState.empty(self._tree.id)

    def unsupported_nodes(self) -> dict[int, tuple[AllocationIssue, ...]]:
        """Nodes this engine refuses to allocate, whatever the build (fail-closed report)."""
        return {n: issues for n, issues in self._index.static_issues.items() if issues}

    # ------------------------------------------------------------------ points

    def spent_points(self, state: BuildState) -> int:
        """Points spent by the state's allocations (never negative)."""
        return max(0, sum(a.rank for a in state.allocations if a.rank > 0) * RANK_COST_POINTS)

    def remaining_points(self, state: BuildState) -> int:
        """Points still available (never negative, 0 for an over-budget state)."""
        return max(0, self._budget - self.spent_points(state))

    # ------------------------------------------------------------------ validation

    def validate(self, state: BuildState) -> BuildValidation:
        """Check an arbitrary build (imported, saved, migrated or corrupt), independently of
        how it was created."""
        issues: list[AllocationIssue] = []
        target: dict[int, int] = {}
        if state.tree_id != self._tree.id:
            issues.append(
                AllocationIssue(
                    Code.UNKNOWN_TREE,
                    f"build is for tree {state.tree_id}, engine allocates tree {self._tree.id}",
                    actual=state.tree_id,
                    required=self._tree.id,
                )
            )
            return self._validation(state, issues, {})

        seen: set[int] = set()
        for allocation in state.allocations:
            node = self._index.nodes.get(allocation.node_id)
            if node is None:
                issues.append(
                    AllocationIssue(
                        Code.UNKNOWN_NODE,
                        f"node {allocation.node_id} does not exist in tree {self._tree.id}",
                        node_id=allocation.node_id,
                    )
                )
                continue
            if allocation.node_id in seen:
                issues.append(
                    AllocationIssue(
                        Code.DUPLICATE_ALLOCATION,
                        f"node {node.id} ({node.name}) is allocated more than once",
                        node_id=node.id,
                    )
                )
                continue
            seen.add(allocation.node_id)
            entry = _entry_of(node, allocation.entry_id)
            if entry is None:
                issues.append(_invalid_entry(node, allocation.entry_id))
                continue
            if not 1 <= allocation.rank <= entry.max_rank:
                issues.append(
                    AllocationIssue(
                        Code.RANK_OUT_OF_BOUNDS,
                        f"node {node.id} ({node.name}) has rank {allocation.rank}, allowed "
                        f"1..{entry.max_rank}",
                        node_id=node.id,
                        entry_id=entry.id,
                        required=entry.max_rank,
                        actual=allocation.rank,
                    )
                )
                continue
            target[node.id] = allocation.rank

        spent = points_spent(target)
        if spent > self._budget:
            issues.append(
                AllocationIssue(
                    Code.POINT_BUDGET_EXCEEDED,
                    f"build spends {spent} points, budget is {self._budget}",
                    required=self._budget,
                    actual=spent,
                )
            )
        for node_id in sorted(target):
            issues.extend(self._index.static_issues[node_id])

        max_ranks = {n: self._index.nodes[n].max_rank for n in target}
        reached = reachable_ranks(self._index, target, max_ranks)
        for node_id in sorted(target):
            if reached.get(node_id, 0) >= target[node_id] or self._index.static_issues[node_id]:
                continue
            # Blocked at the fixpoint of the purchase closure: explain with the rules that
            # fail there (this is what exposes self-supporting cycles and ordering gaps).
            issues.extend(availability_issues(self._index, reached, self._index.nodes[node_id]))
        return self._validation(state, issues, reached)

    def _validation(
        self, state: BuildState, issues: list[AllocationIssue], reached: dict[int, int]
    ) -> BuildValidation:
        return BuildValidation(
            issues=sort_issues(issues),
            spent_points=self.spent_points(state),
            remaining_points=self.remaining_points(state),
            point_budget=self._budget,
            reached_ranks=dict(sorted(reached.items())),
        )

    # ------------------------------------------------------------------ spend

    def can_spend(
        self, state: BuildState, node_id: int, entry_id: int | None = None
    ) -> AllocationDecision:
        """May one more rank of ``node_id`` (entry ``entry_id``) be purchased?"""
        return AllocationDecision.of(self._spend_issues(state, node_id, entry_id)[0])

    def spend(
        self, state: BuildState, node_id: int, entry_id: int | None = None
    ) -> AllocationResult:
        """Purchase one rank. Not allowed -> the input state is returned unchanged."""
        issues, entry = self._spend_issues(state, node_id, entry_id)
        decision = AllocationDecision.of(issues)
        if not decision.allowed or entry is None:
            return AllocationResult(decision, state)
        new_state = state.with_allocation(node_id, entry.id, state.rank_of(node_id) + 1)
        return AllocationResult(decision, new_state)

    def _spend_issues(
        self, state: BuildState, node_id: int, entry_id: int | None
    ) -> tuple[list[AllocationIssue], TalentEntry | None]:
        precondition = self._require_valid(state)
        if precondition:
            return precondition, None
        node = self._index.nodes.get(node_id)
        if node is None:
            return [_unknown_node(node_id, self._tree.id)], None

        current = state.allocation_for(node_id)
        if entry_id is None:
            if current is not None:
                entry_id = current.entry_id
            elif len(node.entries) == 1:
                entry_id = node.entries[0].id
            else:
                return [
                    AllocationIssue(
                        Code.ENTRY_NOT_SELECTED,
                        f"node {node.id} ({node.name}) has {len(node.entries)} entries; "
                        "an entry must be chosen",
                        node_id=node.id,
                    )
                ], None
        entry = _entry_of(node, entry_id)
        if entry is None:
            return [_invalid_entry(node, entry_id)], None
        # A valid state only holds single-entry nodes (multi-entry/selection nodes fail
        # closed), so a purchased node's entry is the only one: switching entries (selection
        # swaps) is not reachable and deliberately not modelled.

        issues = list(self._index.static_issues[node.id])
        rank = current.rank if current else 0
        if rank >= entry.max_rank:
            issues.append(
                AllocationIssue(
                    Code.MAX_RANK_REACHED,
                    f"node {node.id} ({node.name}) is already {rank}/{entry.max_rank}",
                    node_id=node.id,
                    entry_id=entry.id,
                    required=entry.max_rank,
                    actual=rank,
                )
            )
        spent = self.spent_points(state)
        if spent + RANK_COST_POINTS > self._budget:
            issues.append(
                AllocationIssue(
                    Code.NO_POINTS_REMAINING,
                    f"{spent}/{self._budget} points spent, a rank costs {RANK_COST_POINTS}",
                    node_id=node.id,
                    required=RANK_COST_POINTS,
                    actual=self._budget - spent,
                )
            )
        issues.extend(availability_issues(self._index, state.ranks(), node))
        return issues, entry

    # ------------------------------------------------------------------ refund

    def can_refund(self, state: BuildState, node_id: int) -> AllocationDecision:
        """May one rank of ``node_id`` be removed from the plan?"""
        return AllocationDecision.of(self._refund_issues(state, node_id)[0])

    def refund(self, state: BuildState, node_id: int) -> AllocationResult:
        """Remove one rank. Not allowed -> the input state is returned unchanged."""
        issues, candidate = self._refund_issues(state, node_id)
        decision = AllocationDecision.of(issues)
        if not decision.allowed or candidate is None:
            return AllocationResult(decision, state)
        return AllocationResult(decision, candidate)

    def _refund_issues(
        self, state: BuildState, node_id: int
    ) -> tuple[list[AllocationIssue], BuildState | None]:
        """Rule REFUND-1: removing a rank is allowed iff the resulting plan is still valid.
        No cascade: dependents are never removed implicitly (the client's cascade *re*-purchase
        history is a UI convenience and is disabled in this client)."""
        precondition = self._require_valid(state)
        if precondition:
            return precondition, None
        node = self._index.nodes.get(node_id)
        if node is None:
            return [_unknown_node(node_id, self._tree.id)], None
        current = state.allocation_for(node_id)
        if current is None:
            return [
                AllocationIssue(
                    Code.NOT_ALLOCATED,
                    f"node {node.id} ({node.name}) has no purchased rank to refund",
                    node_id=node.id,
                    actual=0,
                )
            ], None
        candidate = state.with_allocation(node_id, current.entry_id, current.rank - 1)
        after = self.validate(candidate)
        issues = [
            AllocationIssue(
                Code.DEPENDENT_NODE_WOULD_BREAK,
                f"refunding {node.name} ({node.id}) would invalidate node {broken.node_id}: "
                f"{broken.message}",
                node_id=broken.node_id,
                related_node_ids=(node.id,),
                required=broken.required,
                actual=broken.actual,
                cause=broken.code,
            )
            for broken in after.issues
        ]
        return issues, candidate

    # ------------------------------------------------------------------ helpers

    def _require_valid(self, state: BuildState) -> list[AllocationIssue]:
        validation = self.validate(state)
        if validation.valid:
            return []
        return [
            AllocationIssue(
                Code.INVALID_BUILD_STATE,
                "the current build is not valid "
                f"({', '.join(validation.reason_codes)}); validate() explains why",
            )
        ]


def _select_tree(document: TalentDataDocument, tree_id: int | None) -> TalentTree:
    if tree_id is None:
        if len(document.trees) != 1:
            raise AllocationEngineError(
                f"document has {len(document.trees)} trees; pass tree_id explicitly"
            )
        return document.trees[0]
    for tree in document.trees:
        if tree.id == tree_id:
            return tree
    raise AllocationEngineError(f"document has no tree {tree_id}")


def _entry_of(node: TalentNode, entry_id: int) -> TalentEntry | None:
    return next((e for e in node.entries if e.id == entry_id), None)


def _unknown_node(node_id: int, tree_id: int) -> AllocationIssue:
    return AllocationIssue(
        Code.UNKNOWN_NODE,
        f"node {node_id} does not exist in tree {tree_id}",
        node_id=node_id,
    )


def _invalid_entry(node: TalentNode, entry_id: int) -> AllocationIssue:
    return AllocationIssue(
        Code.INVALID_ENTRY,
        f"entry {entry_id} does not belong to node {node.id} ({node.name}); entries: "
        f"{[e.id for e in node.entries]}",
        node_id=node.id,
        entry_id=entry_id,
    )
