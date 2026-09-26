"""Structural validation of a normalized :class:`TalentDataDocument`.

Errors mean the document must not be published; warnings are known M0 limitations that are
surfaced (never hidden) so a human can judge them.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from itertools import pairwise

from talent_data.models.talents import TalentDataDocument, TalentTree, content_hash
from talent_data.normalization.grid import fit_grid


@dataclass
class ValidationReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def validate_document(doc: TalentDataDocument) -> ValidationReport:
    report = ValidationReport()
    if not doc.trees:
        report.errors.append("document has no trees")

    tree_ids = Counter(tree.id for tree in doc.trees)
    for tree_id, count in tree_ids.items():
        if count > 1:
            report.errors.append(f"duplicate tree id {tree_id}")

    all_nodes = Counter(node.id for tree in doc.trees for node in tree.nodes)
    for node_id, count in all_nodes.items():
        if count > 1:
            report.errors.append(f"duplicate node id {node_id}")
    all_entries = Counter(e.id for tree in doc.trees for n in tree.nodes for e in n.entries)
    for entry_id, count in all_entries.items():
        if count > 1:
            report.errors.append(f"duplicate entry id {entry_id}")

    for tree in doc.trees:
        _validate_tree(tree, report)

    expected = content_hash(doc.semantic_payload())
    if doc.content_hash != expected:
        report.errors.append(f"contentHash {doc.content_hash} != recomputed {expected}")
    return report


def _validate_tree(tree: TalentTree, report: ValidationReport) -> None:
    where = f"tree {tree.id}"
    if not tree.nodes:
        report.errors.append(f"{where}: no nodes")
        return
    node_ids = {n.id for n in tree.nodes}

    positions = Counter((n.position.x, n.position.y) for n in tree.nodes)
    for pos, count in positions.items():
        if count > 1:
            report.errors.append(f"{where}: {count} nodes share position {pos}")

    _validate_grid(tree, report)

    spells = Counter(n.spell_id for n in tree.nodes if n.spell_id is not None)
    for spell_id, count in spells.items():
        if count > 1:
            report.warnings.append(
                f"{where}: spell {spell_id} used by {count} nodes (ambiguous identity hint)"
            )

    _validate_sections(tree, node_ids, report)

    graph: dict[int, list[int]] = {}
    required_edges: set[tuple[int, int]] = set()
    for node in tree.nodes:
        at = f"{where} node {node.id} ({node.name})"
        if not node.entries:
            report.errors.append(f"{at}: no entries")
        if node.max_rank <= 0:
            report.errors.append(f"{at}: maxRank must be > 0, got {node.max_rank}")
        for prereq in node.prerequisites:
            if prereq.node_id not in node_ids:
                report.errors.append(f"{at}: dangling prerequisite {prereq.node_id}")
            if prereq.node_id == node.id:
                report.errors.append(f"{at}: node is its own prerequisite")
        graph[node.id] = [p.node_id for p in node.prerequisites]
        required_edges |= {(node.id, p.node_id) for p in node.prerequisites if p.kind == "required"}
        for req in node.unlock_requirements:
            missing = sorted(set(req.counted_node_ids) - node_ids)
            if missing:
                report.errors.append(f"{at}: unlock requirement counts unknown nodes {missing}")
            if req.required_points <= 0:
                report.errors.append(f"{at}: unlock requirement needs > 0 points")
        if node.unresolved_condition_ids:
            report.warnings.append(
                f"{at}: unresolved TraitCond ids {node.unresolved_condition_ids}"
            )
        for entry in node.entries:
            eat = f"{at} entry {entry.id}"
            if entry.spell_id <= 0:
                report.errors.append(f"{eat}: missing spell reference")
            if not entry.name:
                report.errors.append(f"{eat}: missing name (spell {entry.spell_id})")
            if entry.max_rank <= 0:
                report.errors.append(f"{eat}: maxRank must be > 0, got {entry.max_rank}")
            for effect in entry.effects:
                if effect.per_rank_status in ("incomplete", "unresolved"):
                    report.warnings.append(
                        f"{eat}: effect #{effect.index} per-rank values {effect.per_rank_status}"
                        + (f" ({effect.unresolved_reason})" if effect.unresolved_reason else "")
                    )

    for edge in tree.visual_only_edges:
        if edge.from_node_id not in node_ids or edge.to_node_id not in node_ids:
            report.errors.append(f"{where}: dangling visual edge {edge}")

    cycle = _find_cycle(graph)
    if cycle:
        text = " -> ".join(map(str, cycle))
        if any(pair in required_edges for pair in pairwise(cycle)):
            report.errors.append(f"{where}: 'required' prerequisite cycle (deadlock) {text}")
        else:
            # Observed in real Forever data (Hunter 1.60.1.70009: two opposite
            # SufficientForAvailability edges). Not a deadlock by itself when the node has
            # other sufficient prerequisites, so it is surfaced, not rejected.
            report.warnings.append(f"{where}: 'sufficient' prerequisite cycle {text}")


def _validate_grid(tree: TalentTree, report: ValidationReport) -> None:
    where = f"tree {tree.id}"
    cells = Counter((n.section_index, n.position.row, n.position.column) for n in tree.nodes)
    for cell, count in cells.items():
        if count > 1:
            report.warnings.append(f"{where}: {count} nodes share grid cell {cell}")
    grid = fit_grid((n.section_index, n.position.x, n.position.y) for n in tree.nodes)
    for node in tree.nodes:
        if (grid.row(node.position.y), grid.column(node.section_index, node.position.x)) != (
            node.position.row,
            node.position.column,
        ):
            report.errors.append(f"{where} node {node.id}: row/column inconsistent with x/y")
        dx, dy = grid.offset(node.section_index, node.position.x, node.position.y)
        if dx or dy:
            report.warnings.append(
                f"{where} node {node.id} ({node.name}): off-grid by ({dx}, {dy}) client units"
            )


def _validate_sections(tree: TalentTree, node_ids: set[int], report: ValidationReport) -> None:
    where = f"tree {tree.id}"
    if not tree.sections:
        report.warnings.append(f"{where}: sections (talent tabs) unresolved")
        if any(n.section_index is not None for n in tree.nodes):
            report.errors.append(f"{where}: nodes reference sections but none are defined")
        return
    seen: Counter[int] = Counter(nid for s in tree.sections for nid in s.node_ids)
    for nid, count in seen.items():
        if count > 1:
            report.errors.append(f"{where}: node {nid} is in {count} sections")
    if set(seen) != node_ids:
        report.errors.append(
            f"{where}: sections do not partition nodes "
            f"(missing {sorted(node_ids - set(seen))}, unknown {sorted(set(seen) - node_ids)})"
        )
    membership = {nid: s.index for s in tree.sections for nid in s.node_ids}
    for node in tree.nodes:
        if membership.get(node.id) != node.section_index:
            report.errors.append(
                f"{where}: node {node.id} sectionIndex {node.section_index} "
                f"!= membership {membership.get(node.id)}"
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
