"""Semantic diff between two normalized documents of the same game + class.

Identity rules (conservative on purpose):

* a talent node is identified by ``TraitNode.ID`` (stable across all five Forever 1.60.1
  builds observed so far). Nodes present on one side only are ADDED / REMOVED;
* a node whose entry keeps its spell id but changes its name is RENAMED;
* a node whose entry changes spell id is reported as ``spellReplaced`` - never a rename;
* a removed node and an added node with the same spell id are NOT merged; each only gets a
  ``relatedNodeId`` hint, and only when the pairing is 1:1.
"""

from __future__ import annotations

from collections import Counter
from typing import Any

from talent_data.diff.models import (
    Change,
    DiffReport,
    DiffSummary,
    NodeChange,
    NodeRef,
    TreeChange,
)
from talent_data.models.talents import (
    Effect,
    TalentDataDocument,
    TalentEntry,
    TalentNode,
    TalentTree,
)


class DiffError(ValueError):
    pass


def diff_documents(old: TalentDataDocument, new: TalentDataDocument) -> DiffReport:
    if old.game != new.game:
        raise DiffError(f"cannot diff different games: {old.game} vs {new.game}")
    if old.class_.id != new.class_.id:
        raise DiffError(f"cannot diff different classes: {old.class_.token} vs {new.class_.token}")
    if old.schema_version != new.schema_version:
        raise DiffError(
            f"schema versions differ ({old.schema_version} vs {new.schema_version}); "
            "re-normalize both builds with the same pipeline version"
        )

    old_nodes = _index_nodes(old)
    new_nodes = _index_nodes(new)

    removed_ids = sorted(old_nodes.keys() - new_nodes.keys())
    added_ids = sorted(new_nodes.keys() - old_nodes.keys())
    hints = _related_hints(
        {i: old_nodes[i][1] for i in removed_ids}, {i: new_nodes[i][1] for i in added_ids}
    )

    changed: list[NodeChange] = []
    for node_id in sorted(old_nodes.keys() & new_nodes.keys()):
        (old_tree, a), (new_tree, b) = old_nodes[node_id], new_nodes[node_id]
        changes = _node_changes(old_tree, a, new_tree, b, old_nodes, new_nodes)
        if changes:
            changed.append(
                NodeChange(node_id=node_id, tree_id=new_tree.id, name=b.name, changes=changes)
            )

    kinds = Counter(c.kind for nc in changed for c in nc.changes)
    return DiffReport(
        game=new.game,
        class_=new.class_,
        from_build=old.client_build,
        to_build=new.client_build,
        from_content_hash=old.content_hash,
        to_content_hash=new.content_hash,
        summary=DiffSummary(
            added=len(added_ids),
            removed=len(removed_ids),
            changed=len(changed),
            changes_by_kind=dict(sorted(kinds.items())),
        ),
        tree_changes=_tree_changes(old, new),
        added=[_ref(*new_nodes[i], hints.get(i)) for i in added_ids],
        removed=[_ref(*old_nodes[i], hints.get(i)) for i in removed_ids],
        changed=changed,
    )


# --------------------------------------------------------------------------- helpers

_NodeIndex = dict[int, tuple[TalentTree, TalentNode]]


def _index_nodes(doc: TalentDataDocument) -> _NodeIndex:
    index: _NodeIndex = {}
    for tree in doc.trees:
        for node in tree.nodes:
            if node.id in index:
                raise DiffError(f"{doc.client_build}: duplicate node id {node.id}")
            index[node.id] = (tree, node)
    return index


def _related_hints(removed: dict[int, TalentNode], added: dict[int, TalentNode]) -> dict[int, int]:
    by_spell_removed = _group_by_spell(removed)
    by_spell_added = _group_by_spell(added)
    hints: dict[int, int] = {}
    for spell_id, olds in by_spell_removed.items():
        news = by_spell_added.get(spell_id, [])
        if len(olds) == 1 and len(news) == 1:
            hints[olds[0]] = news[0]
            hints[news[0]] = olds[0]
    return hints


def _group_by_spell(nodes: dict[int, TalentNode]) -> dict[int, list[int]]:
    out: dict[int, list[int]] = {}
    for node_id, node in nodes.items():
        if node.spell_id is not None:
            out.setdefault(node.spell_id, []).append(node_id)
    return out


def _ref(tree: TalentTree, node: TalentNode, related: int | None) -> NodeRef:
    return NodeRef(
        node_id=node.id,
        tree_id=tree.id,
        name=node.name,
        spell_id=node.spell_id,
        section_index=node.section_index,
        position=node.position,
        max_rank=node.max_rank,
        related_node_id=related,
    )


def _tree_changes(old: TalentDataDocument, new: TalentDataDocument) -> list[TreeChange]:
    olds = {t.id: t for t in old.trees}
    news = {t.id: t for t in new.trees}
    out = [TreeChange(tree_id=i, kind="treeRemoved") for i in sorted(olds.keys() - news.keys())]
    out += [TreeChange(tree_id=i, kind="treeAdded") for i in sorted(news.keys() - olds.keys())]
    for tree_id in sorted(olds.keys() & news.keys()):
        a, b = olds[tree_id], news[tree_id]
        if a.point_budget != b.point_budget:
            out.append(
                TreeChange(
                    tree_id=tree_id,
                    kind="pointBudgetChanged",
                    old=_dump(a.point_budget),
                    new=_dump(b.point_budget),
                )
            )
        if len(a.sections) != len(b.sections):
            out.append(
                TreeChange(
                    tree_id=tree_id,
                    kind="sectionsChanged",
                    old=len(a.sections),
                    new=len(b.sections),
                )
            )
    return out


def _dump(model: Any) -> Any:
    return model.model_dump(mode="json", by_alias=True) if model is not None else None


def _node_changes(
    old_tree: TalentTree,
    a: TalentNode,
    new_tree: TalentTree,
    b: TalentNode,
    old_nodes: _NodeIndex,
    new_nodes: _NodeIndex,
) -> list[Change]:
    changes: list[Change] = []
    if old_tree.id != new_tree.id:
        changes.append(Change(kind="treeChanged", old=old_tree.id, new=new_tree.id))
    if a.section_index != b.section_index:
        changes.append(Change(kind="sectionChanged", old=a.section_index, new=b.section_index))
    if (a.position.x, a.position.y) != (b.position.x, b.position.y):
        changes.append(Change(kind="positionChanged", old=_dump(a.position), new=_dump(b.position)))
    if a.type != b.type:
        changes.append(Change(kind="typeChanged", old=a.type, new=b.type))
    if a.flags != b.flags:
        changes.append(Change(kind="flagsChanged", old=a.flags, new=b.flags))
    if a.max_rank != b.max_rank:
        changes.append(Change(kind="maxRankChanged", old=a.max_rank, new=b.max_rank))

    # Identity of a prerequisite is (node id, kind); names are carried for readability only.
    if _prereq_keys(a) != _prereq_keys(b):
        changes.append(
            Change(
                kind="prerequisitesChanged",
                old=_prereqs(a, old_nodes),
                new=_prereqs(b, new_nodes),
            )
        )

    # Counted-node lists naturally shrink/grow when nodes are removed/added elsewhere; that is
    # already reported as REMOVED/ADDED, so only nodes present in both builds are compared.
    common = old_nodes.keys() & new_nodes.keys()
    if _unlock_key(a, common) != _unlock_key(b, common):
        changes.append(
            Change(
                kind="unlockRequirementsChanged",
                old=[_dump(r) for r in a.unlock_requirements],
                new=[_dump(r) for r in b.unlock_requirements],
            )
        )
    if a.unresolved_condition_ids != b.unresolved_condition_ids:
        changes.append(
            Change(
                kind="unresolvedConditionsChanged",
                old=a.unresolved_condition_ids,
                new=b.unresolved_condition_ids,
            )
        )

    changes += _entries_changes(a.entries, b.entries)
    return changes


def _unlock_key(node: TalentNode, common: set[int]) -> list[tuple[int, list[int]]]:
    return sorted(
        (r.required_points, sorted(set(r.counted_node_ids) & common))
        for r in node.unlock_requirements
    )


def _prereq_keys(node: TalentNode) -> list[tuple[int, str]]:
    return sorted((p.node_id, p.kind) for p in node.prerequisites)


def _prereqs(node: TalentNode, index: _NodeIndex) -> list[dict[str, Any]]:
    return [
        {
            "nodeId": p.node_id,
            "kind": p.kind,
            "name": index[p.node_id][1].name if p.node_id in index else None,
        }
        for p in sorted(node.prerequisites, key=lambda p: (p.node_id, p.kind))
    ]


def _entries_changes(olds: list[TalentEntry], news: list[TalentEntry]) -> list[Change]:
    pairs: list[tuple[TalentEntry, TalentEntry]]
    if len(olds) == 1 and len(news) == 1:
        # Single-entry node: the entry *is* the talent, even if its row id was re-issued.
        pairs = [(olds[0], news[0])]
        unmatched_old: list[int] = []
        unmatched_new: list[int] = []
    else:
        old_by_id = {e.id: e for e in olds}
        new_by_id = {e.id: e for e in news}
        pairs = [(old_by_id[i], new_by_id[i]) for i in sorted(old_by_id.keys() & new_by_id.keys())]
        unmatched_old = sorted(old_by_id.keys() - new_by_id.keys())
        unmatched_new = sorted(new_by_id.keys() - old_by_id.keys())

    changes: list[Change] = []
    if unmatched_old or unmatched_new:
        changes.append(
            Change(
                kind="entriesChanged",
                old=[e.id for e in olds],
                new=[e.id for e in news],
            )
        )
    for a, b in pairs:
        changes += _entry_changes(a, b)
    return changes


def _entry_changes(a: TalentEntry, b: TalentEntry) -> list[Change]:
    changes: list[Change] = []
    eid = b.id
    if a.spell_id != b.spell_id:
        changes.append(
            Change(
                kind="spellReplaced",
                entry_id=eid,
                old={"spellId": a.spell_id, "name": a.name},
                new={"spellId": b.spell_id, "name": b.name},
            )
        )
    elif a.name != b.name:
        changes.append(Change(kind="renamed", entry_id=eid, old=a.name, new=b.name))

    if a.description_template != b.description_template:
        changes.append(
            Change(
                kind="descriptionChanged",
                entry_id=eid,
                old=a.description_template,
                new=b.description_template,
            )
        )

    old_effects = {e.index: e for e in a.effects}
    new_effects = {e.index: e for e in b.effects}
    for index in sorted(old_effects.keys() | new_effects.keys()):
        ea, eb = old_effects.get(index), new_effects.get(index)
        if ea is None and eb is not None:
            changes.append(
                Change(kind="effectAdded", entry_id=eid, effect_index=index, new=_dump(eb))
            )
        elif eb is None and ea is not None:
            changes.append(
                Change(kind="effectRemoved", entry_id=eid, effect_index=index, old=_dump(ea))
            )
        elif ea is not None and eb is not None:
            changes += _effect_changes(eid, ea, eb)
    return changes


def _effect_changes(entry_id: int, a: Effect, b: Effect) -> list[Change]:
    changes: list[Change] = []
    if a.per_rank_status != b.per_rank_status:
        changes.append(
            Change(
                kind="effectStatusChanged",
                entry_id=entry_id,
                effect_index=a.index,
                old=a.per_rank_status,
                new=b.per_rank_status,
            )
        )
    old_ranks = {r.rank: r.value for r in a.per_rank or []}
    new_ranks = {r.rank: r.value for r in b.per_rank or []}
    for rank in sorted(old_ranks.keys() | new_ranks.keys()):
        if old_ranks.get(rank) != new_ranks.get(rank):
            changes.append(
                Change(
                    kind="effectRankValueChanged",
                    entry_id=entry_id,
                    effect_index=a.index,
                    rank=rank,
                    old=old_ranks.get(rank),
                    new=new_ranks.get(rank),
                )
            )
    if a.base_points != b.base_points:
        changes.append(
            Change(
                kind="effectBasePointsChanged",
                entry_id=entry_id,
                effect_index=a.index,
                old=a.base_points,
                new=b.base_points,
            )
        )
    return changes
