"""Allocation rules of WoW: Forever talent trees, as pure functions over a tree index.

Every rule here has a documented origin in docs/talent-allocation-semantics.md (the
"Rule" column of the evidence table is quoted in each docstring). Anything the evidence
does not cover is *not* approximated: it is turned into a fail-closed issue.

Terminology
    rank map   ``{node_id: purchased ranks}`` of a (partial) build.
    active     a prerequisite edge is active when its source node is fully ranked.
    available  every rule for purchasing the next rank of a node is met.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass

from talent_data.allocation.models import AllocationIssue, AllocationIssueCode
from talent_data.models.talents import TalentEntry, TalentNode, TalentTree, UnlockRequirement

Code = AllocationIssueCode

# Rule COST-1: every purchased rank costs exactly one point of the tree's point-budget
# currency. Not an assumption: normalization refuses to produce a v1 document for a tree
# whose TraitTreeXTraitCost/TraitCost rows say otherwise (normalizer.RANK_COST_POINTS).
RANK_COST_POINTS = 1

# TraitNodeType values the engine has evidence for. Forever 1.60.1 only uses Single (0).
# Tiered/Selection/SubTreeSelection exist in the client API but never occur in Forever
# data, so their rules cannot be verified here -> fail closed (UNSUPPORTED_NODE_TYPE).
SUPPORTED_NODE_TYPES = frozenset({"single"})

# TraitNodeEntryType values with evidence. Forever 1.60.1 only uses SpendSquare (1); the
# Camelot UI overrides only its spend sound. Other entry types (DragAndDrop, ProfPath,
# RedButton, ArmorSet, SpendInfinite, ...) carry their own mechanics -> fail closed.
SUPPORTED_ENTRY_TYPES = frozenset({1})

# Enum.TraitNodeFlag (Blizzard_APIDocumentationGenerated/TraitConstantsDocumentation.lua).
NODE_FLAG_NEVER_PURCHASABLE = 2
# TestGridPositioned (8): editor layout flag, never read by the client UI code, observed on
# 14 Forever nodes whose purchase rules are otherwise identical. Every other flag is
# unobserved in Forever and fails closed until investigated.
NODE_FLAG_TEST_GRID_POSITIONED = 8
ALLOCATION_NEUTRAL_NODE_FLAGS = NODE_FLAG_TEST_GRID_POSITIONED


@dataclass(frozen=True)
class TreeIndex:
    """Precomputed lookups of one :class:`TalentTree` for the rule functions."""

    tree: TalentTree
    nodes: Mapping[int, TalentNode]
    entries: Mapping[int, TalentEntry]  # entry id -> entry
    entry_node: Mapping[int, int]  # entry id -> owning node id
    required_sources: Mapping[int, tuple[int, ...]]  # node -> RequiredForAvailability sources
    sufficient_sources: Mapping[int, tuple[int, ...]]  # node -> SufficientForAvailability sources
    static_issues: Mapping[int, tuple[AllocationIssue, ...]]  # node -> fail-closed issues

    @classmethod
    def build(cls, tree: TalentTree) -> TreeIndex:
        nodes = {n.id: n for n in tree.nodes}
        entries = {e.id: e for n in tree.nodes for e in n.entries}
        entry_node = {e.id: n.id for n in tree.nodes for e in n.entries}
        required = {
            n.id: tuple(sorted(p.node_id for p in n.prerequisites if p.kind == "required"))
            for n in tree.nodes
        }
        sufficient = {
            n.id: tuple(sorted(p.node_id for p in n.prerequisites if p.kind == "sufficient"))
            for n in tree.nodes
        }
        static = {n.id: _static_issues(n, required[n.id], sufficient[n.id]) for n in tree.nodes}
        return cls(tree, nodes, entries, entry_node, required, sufficient, static)


def _static_issues(
    node: TalentNode, required: tuple[int, ...], sufficient: tuple[int, ...]
) -> tuple[AllocationIssue, ...]:
    """Fail-closed issues that make a node never purchasable by this engine, whatever the
    build. They depend only on the dataset, not on the build state."""
    issues: list[AllocationIssue] = []
    if node.type not in SUPPORTED_NODE_TYPES:
        issues.append(
            AllocationIssue(
                Code.UNSUPPORTED_NODE_TYPE,
                f"node {node.id} ({node.name}) has type {node.type!r}; only "
                f"{sorted(SUPPORTED_NODE_TYPES)} nodes have proven allocation rules",
                node_id=node.id,
            )
        )
    elif len(node.entries) != 1:
        issues.append(
            AllocationIssue(
                Code.UNSUPPORTED_NODE_TYPE,
                f"node {node.id} ({node.name}) is 'single' but has {len(node.entries)} entries",
                node_id=node.id,
                actual=len(node.entries),
            )
        )
    for entry in node.entries:
        if entry.entry_type not in SUPPORTED_ENTRY_TYPES:
            issues.append(
                AllocationIssue(
                    Code.UNSUPPORTED_ENTRY_TYPE,
                    f"entry {entry.id} of node {node.id} has NodeEntryType {entry.entry_type}; "
                    f"supported: {sorted(SUPPORTED_ENTRY_TYPES)}",
                    node_id=node.id,
                    entry_id=entry.id,
                    actual=entry.entry_type,
                )
            )
    if node.flags & NODE_FLAG_NEVER_PURCHASABLE:
        issues.append(
            AllocationIssue(
                Code.NODE_NEVER_PURCHASABLE,
                f"node {node.id} ({node.name}) is flagged NeverPurchasable",
                node_id=node.id,
                actual=node.flags,
            )
        )
    unknown_flags = node.flags & ~(ALLOCATION_NEUTRAL_NODE_FLAGS | NODE_FLAG_NEVER_PURCHASABLE)
    if unknown_flags:
        issues.append(
            AllocationIssue(
                Code.UNSUPPORTED_NODE_FLAGS,
                f"node {node.id} ({node.name}) has TraitNode.Flags bits {unknown_flags:#x} "
                "whose allocation semantics are not proven",
                node_id=node.id,
                actual=node.flags,
            )
        )
    if required and sufficient:
        issues.append(
            AllocationIssue(
                Code.UNSUPPORTED_EDGE_COMBINATION,
                f"node {node.id} ({node.name}) mixes RequiredForAvailability {list(required)} "
                f"and SufficientForAvailability {list(sufficient)} prerequisites; how they "
                "combine is not proven",
                node_id=node.id,
                related_node_ids=tuple(sorted(required + sufficient)),
            )
        )
    for cond_id in node.unresolved_condition_ids:
        issues.append(
            AllocationIssue(
                Code.UNRESOLVED_CONDITION,
                f"cannot verify availability of node {node.id} ({node.name}): TraitCond "
                f"{cond_id} is unresolved",
                node_id=node.id,
                condition_id=cond_id,
            )
        )
    return tuple(issues)


# --------------------------------------------------------------------------- rules


def points_spent(ranks: Mapping[int, int], node_ids: Iterable[int] | None = None) -> int:
    """Rule COST-1 / GATE-2: currency spent = purchased ranks x 1 point.

    Granted/free ranks do not exist in the v1 contract (GrantedRanks conditions are never
    flattened into gates); only purchased ranks cost - and count - points.
    """
    if node_ids is None:
        return sum(r for r in ranks.values() if r > 0) * RANK_COST_POINTS
    return sum(ranks.get(n, 0) for n in node_ids if ranks.get(n, 0) > 0) * RANK_COST_POINTS


def edge_active(index: TreeIndex, ranks: Mapping[int, int], source_id: int) -> bool:
    """Rule EDGE-ACTIVE: a prerequisite edge is active iff its source node is fully ranked
    (purchased ranks == max ranks of the source). Same rule for Required and Sufficient."""
    source = index.nodes[source_id]
    return ranks.get(source_id, 0) >= source.max_rank


def gate_issues(
    index: TreeIndex, ranks: Mapping[int, int], node: TalentNode
) -> list[AllocationIssue]:
    """Rule GATE-1/GATE-2: each unlock requirement needs ``requiredPoints`` points already
    spent in its counted nodes. All requirements of a node must hold (conditions without
    the IsSufficient flag are conjunctive; every Forever gate has Flags=0)."""
    issues = []
    for req in node.unlock_requirements:
        spent = points_spent(ranks, req.counted_node_ids)
        if spent < req.required_points:
            issues.append(_gate_issue(node, req, spent))
    return issues


def _gate_issue(node: TalentNode, req: UnlockRequirement, spent: int) -> AllocationIssue:
    return AllocationIssue(
        Code.TIER_GATE_NOT_MET,
        f"node {node.id} ({node.name}) needs {req.required_points} points spent in "
        f"{len(req.counted_node_ids)} counted nodes, has {spent}",
        node_id=node.id,
        required=req.required_points,
        actual=spent,
    )


def edge_issues(
    index: TreeIndex, ranks: Mapping[int, int], node: TalentNode
) -> list[AllocationIssue]:
    """Rule EDGE-REQ: every RequiredForAvailability edge must be active (ALL).
    Rule EDGE-SUF: at least one SufficientForAvailability edge must be active (ANY).
    Mixed required+sufficient nodes are fail-closed statically (UNSUPPORTED_EDGE_COMBINATION)
    and are not evaluated here."""
    required = index.required_sources[node.id]
    sufficient = index.sufficient_sources[node.id]
    if required and sufficient:
        return []  # reported by the static issue; never evaluate an unproven combination
    issues = []
    for source_id in required:
        if not edge_active(index, ranks, source_id):
            source = index.nodes[source_id]
            issues.append(
                AllocationIssue(
                    Code.REQUIRED_PREREQUISITE_NOT_MET,
                    f"node {node.id} ({node.name}) requires {source.name} ({source_id}) at "
                    f"{source.max_rank}/{source.max_rank}, has {ranks.get(source_id, 0)}",
                    node_id=node.id,
                    related_node_ids=(source_id,),
                    required=source.max_rank,
                    actual=ranks.get(source_id, 0),
                )
            )
    if sufficient and not any(edge_active(index, ranks, s) for s in sufficient):
        names = ", ".join(f"{index.nodes[s].name} ({s})" for s in sufficient)
        issues.append(
            AllocationIssue(
                Code.SUFFICIENT_PREREQUISITE_NOT_MET,
                f"node {node.id} ({node.name}) needs one of [{names}] fully ranked",
                node_id=node.id,
                related_node_ids=sufficient,
            )
        )
    return issues


def availability_issues(
    index: TreeIndex, ranks: Mapping[int, int], node: TalentNode
) -> list[AllocationIssue]:
    """State-dependent availability of the next rank: tier gates + prerequisite edges."""
    return gate_issues(index, ranks, node) + edge_issues(index, ranks, node)


def can_purchase_next_rank(
    index: TreeIndex, ranks: Mapping[int, int], node_id: int, max_rank: int
) -> bool:
    """Purchase rule without the budget (used by the reachability closure)."""
    node = index.nodes[node_id]
    return (
        not index.static_issues[node_id]
        and ranks.get(node_id, 0) < max_rank
        and not availability_issues(index, ranks, node)
    )


def reachable_ranks(
    index: TreeIndex, target: Mapping[int, int], max_ranks: Mapping[int, int]
) -> dict[int, int]:
    """Rule VALID-1: the largest part of ``target`` obtainable by purchasing one rank at a
    time from an empty tree, every purchase obeying the rules.

    Greedy closure is exact because every purchase rule is *monotone*: adding ranks never
    lowers points spent in a group and never deactivates an edge (edges only activate at
    max rank and ranks are only added), and the static fail-closed rules do not depend on
    the state. MutuallyExclusive edges - the one non-monotone rule in the client enum - do
    not exist in Forever and cannot enter a v1 document (normalization rejects them). So
    if any valid purchase order reaches ``target``, the closure reaches it too; the budget
    is checked separately (a purchase sequence never spends more than its final total).
    """
    reached: dict[int, int] = {}
    progress = True
    while progress:
        progress = False
        for node_id in sorted(target):
            limit = min(target[node_id], max_ranks[node_id])
            while reached.get(node_id, 0) < limit and can_purchase_next_rank(
                index, reached, node_id, max_ranks[node_id]
            ):
                reached[node_id] = reached.get(node_id, 0) + 1
                progress = True
    return reached
