"""Structural validation of a normalized :class:`TalentDataDocument`.

Errors mean the document must not be published; warnings are known client-data features or
pipeline gaps that are surfaced (never hidden) so a human and automation can judge them.
Every finding is a typed :class:`ValidationIssue` with a stable machine-readable ``code``
(see :mod:`talent_data.validation.issues`).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from itertools import pairwise

from talent_data.models.talents import TalentDataDocument, TalentTree, content_hash
from talent_data.normalization.grid import fit_grid
from talent_data.validation.issues import Severity, ValidationIssue


@dataclass
class ValidationReport:
    issues: list[ValidationIssue] = field(default_factory=list)

    def error(
        self,
        code: str,
        message: str,
        *,
        tree: int | None = None,
        node: int | None = None,
        entry: int | None = None,
        effect: int | None = None,
    ) -> None:
        self._add("error", code, message, tree, node, entry, effect)

    def warning(
        self,
        code: str,
        message: str,
        *,
        tree: int | None = None,
        node: int | None = None,
        entry: int | None = None,
        effect: int | None = None,
    ) -> None:
        self._add("warning", code, message, tree, node, entry, effect)

    def _add(
        self,
        severity: Severity,
        code: str,
        message: str,
        tree: int | None,
        node: int | None,
        entry: int | None,
        effect: int | None,
    ) -> None:
        self.issues.append(
            ValidationIssue(
                severity=severity,
                code=code,
                message=message,
                tree_id=tree,
                node_id=node,
                entry_id=entry,
                effect_index=effect,
            )
        )

    @property
    def errors(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == "error"]

    @property
    def warnings(self) -> list[ValidationIssue]:
        return [i for i in self.issues if i.severity == "warning"]

    @property
    def ok(self) -> bool:
        return not self.errors

    def count_by_code(self) -> dict[str, int]:
        return dict(sorted(Counter(i.code for i in self.issues).items()))


def validate_document(doc: TalentDataDocument) -> ValidationReport:
    report = ValidationReport()
    if not doc.trees:
        report.error("NO_TREES", "document has no trees")

    tree_ids = Counter(tree.id for tree in doc.trees)
    for tree_id, count in tree_ids.items():
        if count > 1:
            report.error("DUPLICATE_TREE_ID", f"duplicate tree id {tree_id}")

    all_nodes = Counter(node.id for tree in doc.trees for node in tree.nodes)
    for node_id, count in all_nodes.items():
        if count > 1:
            report.error("DUPLICATE_NODE_ID", f"duplicate node id {node_id}")
    all_entries = Counter(e.id for tree in doc.trees for n in tree.nodes for e in n.entries)
    for entry_id, count in all_entries.items():
        if count > 1:
            report.error("DUPLICATE_ENTRY_ID", f"duplicate entry id {entry_id}")

    for tree in doc.trees:
        _validate_tree(tree, report)

    expected = content_hash(doc.semantic_payload())
    if doc.content_hash != expected:
        report.error(
            "CONTENT_HASH_MISMATCH", f"contentHash {doc.content_hash} != recomputed {expected}"
        )
    return report


def _validate_tree(tree: TalentTree, report: ValidationReport) -> None:
    if not tree.nodes:
        report.error("TREE_HAS_NO_NODES", f"tree {tree.id}: no nodes", tree=tree.id)
        return
    node_ids = {n.id for n in tree.nodes}

    positions = Counter((n.position.x, n.position.y) for n in tree.nodes)
    for pos, count in positions.items():
        if count > 1:
            report.error(
                "SHARED_POSITION",
                f"tree {tree.id}: {count} nodes share position {pos}",
                tree=tree.id,
            )

    _validate_grid(tree, report)

    spells = Counter(n.spell_id for n in tree.nodes if n.spell_id is not None)
    for spell_id, count in spells.items():
        if count > 1:
            report.warning(
                "DUPLICATE_SPELL_IDENTITY",
                f"tree {tree.id}: spell {spell_id} used by {count} nodes (ambiguous identity hint)",
                tree=tree.id,
            )

    _validate_sections(tree, node_ids, report)

    graph: dict[int, list[int]] = {}
    required_edges: set[tuple[int, int]] = set()
    for node in tree.nodes:
        if not node.entries:
            report.error(
                "NODE_WITHOUT_ENTRIES",
                f"node {node.id} ({node.name}): no entries",
                tree=tree.id,
                node=node.id,
            )
        if node.max_rank <= 0:
            report.error(
                "INVALID_MAX_RANK",
                f"node {node.id} ({node.name}): maxRank must be > 0, got {node.max_rank}",
                tree=tree.id,
                node=node.id,
            )
        for prereq in node.prerequisites:
            if prereq.node_id not in node_ids:
                report.error(
                    "DANGLING_PREREQUISITE",
                    f"node {node.id} ({node.name}): dangling prerequisite {prereq.node_id}",
                    tree=tree.id,
                    node=node.id,
                )
            if prereq.node_id == node.id:
                report.error(
                    "SELF_PREREQUISITE",
                    f"node {node.id} ({node.name}): node is its own prerequisite",
                    tree=tree.id,
                    node=node.id,
                )
        graph[node.id] = [p.node_id for p in node.prerequisites]
        required_edges |= {(node.id, p.node_id) for p in node.prerequisites if p.kind == "required"}
        if node.unresolved_condition_ids:
            report.warning(
                "UNRESOLVED_CONDITION",
                f"node {node.id} ({node.name}): unresolved TraitCond ids "
                f"{node.unresolved_condition_ids}",
                tree=tree.id,
                node=node.id,
            )
        for req in node.unlock_requirements:
            missing = sorted(set(req.counted_node_ids) - node_ids)
            if missing:
                report.error(
                    "UNLOCK_COUNTS_UNKNOWN_NODES",
                    f"node {node.id} ({node.name}): unlock requirement counts unknown "
                    f"nodes {missing}",
                    tree=tree.id,
                    node=node.id,
                )
            if req.required_points <= 0:
                report.error(
                    "INVALID_UNLOCK_POINTS",
                    f"node {node.id} ({node.name}): unlock requirement needs > 0 points",
                    tree=tree.id,
                    node=node.id,
                )
        for entry in node.entries:
            if entry.spell_id <= 0:
                report.error(
                    "MISSING_SPELL_REFERENCE",
                    f"entry {entry.id}: missing spell reference",
                    tree=tree.id,
                    node=node.id,
                    entry=entry.id,
                )
            if not entry.name:
                report.error(
                    "MISSING_NAME",
                    f"entry {entry.id}: missing name (spell {entry.spell_id})",
                    tree=tree.id,
                    node=node.id,
                    entry=entry.id,
                )
            if entry.max_rank <= 0:
                report.error(
                    "INVALID_ENTRY_MAX_RANK",
                    f"entry {entry.id}: maxRank must be > 0, got {entry.max_rank}",
                    tree=tree.id,
                    node=node.id,
                    entry=entry.id,
                )
            for effect in entry.effects:
                if effect.per_rank_status == "incomplete":
                    report.warning(
                        "INCOMPLETE_RANK_CURVE",
                        f"entry {entry.id}: effect #{effect.index} per-rank values incomplete",
                        tree=tree.id,
                        node=node.id,
                        entry=entry.id,
                        effect=effect.index,
                    )
                elif effect.per_rank_status == "unresolved":
                    detail = f" ({effect.unresolved_reason})" if effect.unresolved_reason else ""
                    report.warning(
                        "UNRESOLVED_RANK_CURVE",
                        f"entry {entry.id}: effect #{effect.index} per-rank values "
                        f"unresolved{detail}",
                        tree=tree.id,
                        node=node.id,
                        entry=entry.id,
                        effect=effect.index,
                    )

    for edge in tree.visual_only_edges:
        if edge.from_node_id not in node_ids or edge.to_node_id not in node_ids:
            report.error(
                "DANGLING_VISUAL_EDGE",
                f"tree {tree.id}: dangling visual edge {edge}",
                tree=tree.id,
            )

    cycle = _find_cycle(graph)
    if cycle:
        text = " -> ".join(map(str, cycle))
        if any(pair in required_edges for pair in pairwise(cycle)):
            report.error(
                "REQUIRED_PREREQUISITE_CYCLE",
                f"tree {tree.id}: 'required' prerequisite cycle (deadlock) {text}",
                tree=tree.id,
            )
        else:
            # Observed in real Forever data (Hunter 1.60.1.70009: two opposite
            # SufficientForAvailability edges). Not a deadlock by itself when the node has
            # other sufficient prerequisites, so it is surfaced, not rejected.
            report.warning(
                "SUFFICIENT_PREREQUISITE_CYCLE",
                f"tree {tree.id}: 'sufficient' prerequisite cycle {text}",
                tree=tree.id,
            )


def _validate_grid(tree: TalentTree, report: ValidationReport) -> None:
    cells = Counter((n.section_index, n.position.row, n.position.column) for n in tree.nodes)
    for cell, count in cells.items():
        if count > 1:
            report.warning(
                "SHARED_GRID_CELL",
                f"tree {tree.id}: {count} nodes share grid cell {cell}",
                tree=tree.id,
            )
    grid = fit_grid((n.section_index, n.position.x, n.position.y) for n in tree.nodes)
    for node in tree.nodes:
        if (grid.row(node.position.y), grid.column(node.section_index, node.position.x)) != (
            node.position.row,
            node.position.column,
        ):
            report.error(
                "GRID_RC_INCONSISTENT",
                f"tree {tree.id} node {node.id}: row/column inconsistent with x/y",
                tree=tree.id,
                node=node.id,
            )
        dx, dy = grid.offset(node.section_index, node.position.x, node.position.y)
        if dx or dy:
            report.warning(
                "OFF_GRID_NODE",
                f"tree {tree.id} node {node.id} ({node.name}): off-grid by ({dx}, {dy}) "
                "client units",
                tree=tree.id,
                node=node.id,
            )


def _validate_sections(tree: TalentTree, node_ids: set[int], report: ValidationReport) -> None:
    if not tree.sections:
        report.warning(
            "SECTIONS_UNRESOLVED",
            f"tree {tree.id}: sections (talent tabs) unresolved",
            tree=tree.id,
        )
        if any(n.section_index is not None for n in tree.nodes):
            report.error(
                "NODE_SECTION_INCONSISTENT",
                f"tree {tree.id}: nodes reference sections but none are defined",
                tree=tree.id,
            )
        return
    seen: Counter[int] = Counter(nid for s in tree.sections for nid in s.node_ids)
    for nid, count in seen.items():
        if count > 1:
            report.error(
                "SECTIONS_NOT_PARTITION",
                f"tree {tree.id}: node {nid} is in {count} sections",
                tree=tree.id,
            )
    if set(seen) != node_ids:
        report.error(
            "SECTIONS_NOT_PARTITION",
            f"tree {tree.id}: sections do not partition nodes "
            f"(missing {sorted(node_ids - set(seen))}, unknown {sorted(set(seen) - node_ids)})",
            tree=tree.id,
        )
    membership = {nid: s.index for s in tree.sections for nid in s.node_ids}
    for node in tree.nodes:
        if membership.get(node.id) != node.section_index:
            report.error(
                "SECTION_INDEX_MISMATCH",
                f"tree {tree.id}: node {node.id} sectionIndex {node.section_index} "
                f"!= membership {membership.get(node.id)}",
                tree=tree.id,
                node=node.id,
            )
    # A tab member that no gate of the tree counts was placed by layout, not by a gate group
    # (normalizer._resolve_sections): its points count toward no tab gate. Observed on Mage
    # since 1.60.1.70425 (Arcane Wrath); surfaced so that it is never mistaken for normal.
    counted = {i for n in tree.nodes for req in n.unlock_requirements for i in req.counted_node_ids}
    if counted:
        for node in tree.nodes:
            if node.section_index is not None and node.id not in counted:
                report.warning(
                    "SECTION_NOT_GATE_COUNTED",
                    f"tree {tree.id} node {node.id} ({node.name}): in section "
                    f"{node.section_index} by layout, counted by no gate",
                    tree=tree.id,
                    node=node.id,
                )


def _find_cycle(graph: dict[int, list[int]]) -> list[int] | None:
    white, grey, black = 0, 1, 2
    color = dict.fromkeys(graph, white)
    stack: list[int] = []

    def visit(node: int) -> list[int] | None:
        color[node] = grey
        stack.append(node)
        for nxt in graph.get(node, []):
            if color.get(nxt, black) == grey:
                return [*stack[stack.index(nxt) :], nxt]
            if color.get(nxt) == white:
                found = visit(nxt)
                if found:
                    return found
        stack.pop()
        color[node] = black
        return None

    for node in sorted(graph):
        if color[node] == white:
            found = visit(node)
            if found:
                return found
    return None
