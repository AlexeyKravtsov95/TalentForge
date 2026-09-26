"""Human-readable rendering of a :class:`DiffReport` for developers."""

from __future__ import annotations

from typing import Any

from talent_data.diff.models import Change, DatasetDiffReport, DiffReport, NodeRef

_TEXT_LIMIT = 160


def render_dataset_text(aggregate: DatasetDiffReport, game_display: str) -> str:
    """Compact all-class summary: one block per class plus a TOTAL block."""
    lines = [
        f"{game_display} (all classes)",
        f"{aggregate.from_build} -> {aggregate.to_build}",
        "",
    ]
    for entry in aggregate.classes:
        s = entry.summary
        lines.append(entry.class_.name)
        lines.append(f"  added: {s.added}, removed: {s.removed}, changed: {s.changed}")
        if s.changes_by_kind:
            kinds = ", ".join(f"{k}={v}" for k, v in s.changes_by_kind.items())
            lines.append(f"  ({kinds})")
    total = aggregate.total
    lines += [
        "TOTAL",
        f"  added: {total.added}, removed: {total.removed}, changed: {total.changed}",
    ]
    if total.changes_by_kind:
        kinds = ", ".join(f"{k}={v}" for k, v in total.changes_by_kind.items())
        lines.append(f"  ({kinds})")
    return "\n".join(lines) + "\n"


def render_text(report: DiffReport, game_display: str) -> str:
    lines = [
        f"{game_display} {report.class_.name}",
        f"{report.from_build} -> {report.to_build}",
        "",
        f"ADDED: {report.summary.added}",
        f"REMOVED: {report.summary.removed}",
        f"CHANGED: {report.summary.changed}",
    ]
    if report.summary.changes_by_kind:
        kinds = ", ".join(f"{k}={v}" for k, v in report.summary.changes_by_kind.items())
        lines.append(f"  ({kinds})")
    if report.is_empty:
        lines += ["", "No semantic changes."]
        return "\n".join(lines) + "\n"

    for tc in report.tree_changes:
        lines += ["", f"[TREE] {tc.tree_id}: {tc.kind}"]
        if tc.old is not None or tc.new is not None:
            lines.append(f"  {tc.old} -> {tc.new}")
    for ref in report.added:
        lines += ["", "[ADDED]", *_ref_lines(ref)]
    for ref in report.removed:
        lines += ["", "[REMOVED]", *_ref_lines(ref)]
    for node in report.changed:
        lines += ["", "[CHANGED]", f"{node.name or '<unnamed>'} (node {node.node_id})"]
        lines += _change_lines(node.changes)
    return "\n".join(lines) + "\n"


def _ref_lines(ref: NodeRef) -> list[str]:
    p = ref.position
    out = [
        f"{ref.name or '<unnamed>'} (node {ref.node_id}, spell {ref.spell_id})",
        f"  section {ref.section_index}, row {p.row}, column {p.column} (x={p.x}, y={p.y}), "
        f"maxRank {ref.max_rank}",
    ]
    if ref.related_node_id is not None:
        out.append(
            f"  note: node {ref.related_node_id} on the other side uses the same spell "
            "(not merged: different node identity)"
        )
    return out


def _change_lines(changes: list[Change]) -> list[str]:
    out: list[str] = []
    effect_header: tuple[int | None, int | None] | None = None
    for c in changes:
        if c.kind == "effectRankValueChanged":
            header = (c.entry_id, c.effect_index)
            if header != effect_header:
                out.append(f"  effect #{c.effect_index} (${_placeholder(c.effect_index)}):")
                effect_header = header
            out.append(f"    rank {c.rank}: {_fmt(c.old)} -> {_fmt(c.new)}")
            continue
        effect_header = None
        match c.kind:
            case "renamed":
                out.append(f"  renamed: {c.old!r} -> {c.new!r}")
            case "spellReplaced":
                out.append(
                    f"  spell replaced: {c.old['spellId']} {c.old['name']!r} -> "
                    f"{c.new['spellId']} {c.new['name']!r}"
                )
            case "maxRankChanged":
                out.append(f"  maxRank: {c.old} -> {c.new}")
            case "positionChanged":
                out.append(f"  position: {_pos(c.old)} -> {_pos(c.new)}")
            case "prerequisitesChanged":
                out += [
                    "  prerequisites:",
                    f"    old: {_prereqs(c.old)}",
                    f"    new: {_prereqs(c.new)}",
                ]
            case "unlockRequirementsChanged":
                out += ["  unlock requirements:", *_unlock(c.old, c.new)]
            case "effectAdded":
                out.append(f"  effect #{c.effect_index} added: {_effect(c.new)}")
            case "effectRemoved":
                out.append(f"  effect #{c.effect_index} removed: {_effect(c.old)}")
            case "effectStatusChanged":
                out.append(f"  effect #{c.effect_index} per-rank status: {c.old} -> {c.new}")
            case "effectBasePointsChanged":
                out.append(
                    f"  effect #{c.effect_index} base points: {_fmt(c.old)} -> {_fmt(c.new)}"
                )
            case "descriptionChanged":
                out += [
                    "  tooltip template:",
                    f"    old: {_clip(c.old)}",
                    f"    new: {_clip(c.new)}",
                ]
            case _:
                out.append(f"  {c.kind}: {c.old} -> {c.new}")
    return out


def _placeholder(effect_index: int | None) -> str:
    return f"s{effect_index + 1}" if effect_index is not None else "?"


def _fmt(value: Any) -> str:
    return "none" if value is None else str(value)


def _pos(p: dict[str, Any]) -> str:
    return f"(row {p['row']}, col {p['column']}; x={p['x']}, y={p['y']})"


def _prereqs(items: list[dict[str, Any]]) -> str:
    return "[" + ", ".join(f"{i['nodeId']} {i['name'] or ''}".strip() for i in items) + "]"


def _unlock(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> list[str]:
    before = {i["requiredPoints"]: set(i["countedNodeIds"]) for i in old}
    after = {i["requiredPoints"]: set(i["countedNodeIds"]) for i in new}
    lines = []
    for points in sorted(before.keys() | after.keys()):
        if points not in after:
            lines.append(f"    removed: {points} pts in {len(before[points])} nodes")
        elif points not in before:
            lines.append(f"    added: {points} pts in {len(after[points])} nodes")
        elif before[points] != after[points]:
            gained = sorted(after[points] - before[points])
            lost = sorted(before[points] - after[points])
            lines.append(f"    {points} pts: counted nodes +{gained} -{lost}")
    return lines


def _effect(e: dict[str, Any]) -> str:
    ranks = e.get("perRank") or []
    values = ", ".join(f"r{r['rank']}={r['value']}" for r in ranks) or "no rank curve"
    return f"{values}; base points {e.get('basePoints')}"


def _clip(text: str | None) -> str:
    if text is None:
        return "none"
    flat = " ".join(text.split())
    return flat if len(flat) <= _TEXT_LIMIT else flat[: _TEXT_LIMIT - 1] + "…"
