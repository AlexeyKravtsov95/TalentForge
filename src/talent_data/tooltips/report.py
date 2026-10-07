"""Structural analysis of an imported client tooltip capture (the input to M5.V2B).

Purely observational: it counts and quotes what the client returned. It does not decide
which line is "the description" - that is M5.V2B's decision, to be made from this report.
Patterns marked *heuristic* are text searches, not client facts.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from talent_data.tooltips.capture import CapturedTooltip, ClientTooltipCapture
from talent_data.tooltips.manifest import CaptureManifest, ManifestEntry

# Markup of the raw DB2 template language that a fully rendered tooltip should not contain.
_TEMPLATE_TOKEN = re.compile(r"\$(?:\{|@|\?|<|[/*]\d|\d|[A-Za-z])")
_COLOR_MARKUP = re.compile(r"\|c[0-9a-fA-F]{8}|\|r", re.IGNORECASE)
_OTHER_PIPE = re.compile(r"\|(?![cCrR])\S")
_RANK_TEXT = re.compile(r"\bRank\b")
_REQUIRES_TEXT = re.compile(r"\bRequires\b")

# (class token, talent name) the M5.V2A brief asks to see in full.
_NAMED_EXAMPLES = (
    ("WARRIOR", "Improved Rend"),
    ("WARRIOR", "Improved Slam"),
    ("WARRIOR", "Mortal Strike"),
    ("WARRIOR", "Weaponmaster"),
)
_FIRST_OF_CLASS = ("MAGE", "ROGUE")
_CONDITIONAL_CLASS = "DRUID"
_CONDITIONAL_SYNTAX = ("$?", "$@spelltooltip")


def _lines(record: CapturedTooltip) -> list[dict[str, Any]]:
    return list(record.tooltip["lines"])


def _texts(record: CapturedTooltip) -> Iterable[str]:
    for line in _lines(record):
        for field in ("leftText", "rightText"):
            value = line.get(field)
            if isinstance(value, str):
                yield value


def _shape(record: CapturedTooltip) -> list[tuple[Any, Any, Any]]:
    return [
        (line.get("type"), line.get("leftText"), line.get("rightText")) for line in _lines(record)
    ]


def _table(header: Sequence[str], rows: Iterable[Sequence[object]]) -> list[str]:
    out = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
    # A literal | inside a cell would end the cell; Markdown tables escape it as \|.
    out.extend(
        "| " + " | ".join(str(cell).replace("|", "\\|") for cell in row) + " |" for row in rows
    )
    return out


def _counter_rows(counter: Counter[Any]) -> list[tuple[str, int]]:
    return [(str(key), count) for key, count in sorted(counter.items(), key=lambda kv: str(kv[0]))]


def _quote(text: object) -> str:
    """A client string exactly as captured, JSON-escaped so nothing is lost in Markdown."""
    return "`" + json.dumps(text, ensure_ascii=False).replace("`", "\\u0060") + "`"


def _example(entry: ManifestEntry, records: Sequence[CapturedTooltip]) -> list[str]:
    out = [
        f"#### {entry.class_token} · {entry.name} "
        f"(entry {entry.entry_id}, max rank {entry.max_rank})",
        "",
    ]
    for record in records:
        tooltip = record.tooltip
        out.append(
            f"Rank {record.rank}: type {tooltip.get('type')}, id {tooltip.get('id')}, "
            f"hasDynamicData {tooltip.get('hasDynamicData')}, reads {record.reads}"
        )
        out.append("")
        for index, line in enumerate(_lines(record), start=1):
            extra = sorted(
                k for k in line if k not in ("type", "leftText", "rightText", "wrapText")
            )
            out.append(
                f"{index}. type {line.get('type')}, wrap {line.get('wrapText')}: "
                f"{_quote(line.get('leftText'))}"
                + (f" · right {_quote(line['rightText'])}" if "rightText" in line else "")
                + (f" · other fields: {', '.join(extra)}" if extra else "")
            )
        out.append("")
    return out


def render_report(
    capture: ClientTooltipCapture,
    manifest: CaptureManifest,
    templates: Mapping[int, str | None],
) -> str:
    """Markdown report. ``templates`` maps entry id -> raw ``descriptionTemplate``."""
    by_entry: dict[int, list[CapturedTooltip]] = {}
    for record in capture.records:
        by_entry.setdefault(record.entry_id, []).append(record)
    names = {e.entry_id: e.name for e in manifest.entries}

    line_types: Counter[Any] = Counter()
    line_counts: Counter[int] = Counter()
    dynamic: Counter[Any] = Counter()
    reads: Counter[int] = Counter()
    sequences: Counter[tuple[Any, ...]] = Counter()
    line_fields: Counter[str] = Counter()
    tooltip_fields: Counter[str] = Counter()
    first_is_name = any_is_name = 0
    with_template = with_color = with_other_pipe = with_rank = with_requires = 0
    template_examples: list[str] = []
    rank_examples: list[str] = []
    requires_examples: list[str] = []

    def remember(bucket: list[str], text: str) -> None:
        if text not in bucket and len(bucket) < 5:
            bucket.append(text)

    for record in capture.records:
        lines = _lines(record)
        line_counts[len(lines)] += 1
        dynamic[record.tooltip.get("hasDynamicData", "absent")] += 1
        reads[record.reads] += 1
        sequences[tuple(line.get("type") for line in lines)] += 1
        tooltip_fields.update(record.tooltip.keys())
        for line in lines:
            line_types[line.get("type", "absent")] += 1
            line_fields.update(line.keys())
        name = names[record.entry_id]
        first_is_name += lines[0].get("leftText") == name
        any_is_name += any(line.get("leftText") == name for line in lines)
        texts = list(_texts(record))
        flags = {"template": False, "color": False, "pipe": False, "rank": False, "requires": False}
        for text in texts:
            if _TEMPLATE_TOKEN.search(text):
                flags["template"] = True
                remember(template_examples, text)
            flags["color"] = flags["color"] or bool(_COLOR_MARKUP.search(text))
            flags["pipe"] = flags["pipe"] or bool(_OTHER_PIPE.search(text))
            # Word searches look at the text without colour codes ("|CFFFF2020Requires").
            plain = _COLOR_MARKUP.sub("", text)
            if _RANK_TEXT.search(plain):
                flags["rank"] = True
                remember(rank_examples, text)
            if _REQUIRES_TEXT.search(plain):
                flags["requires"] = True
                remember(requires_examples, text)
        with_template += flags["template"]
        with_color += flags["color"]
        with_other_pipe += flags["pipe"]
        with_rank += flags["rank"]
        with_requires += flags["requires"]

    multi_rank = [e for e in manifest.entries if e.max_rank > 1]
    rank_varies = sum(
        1 for e in multi_rank if len({json.dumps(_shape(r)) for r in by_entry[e.entry_id]}) > 1
    )
    line_count_varies = sum(
        1 for e in multi_rank if len({len(_lines(r)) for r in by_entry[e.entry_id]}) > 1
    )
    total = capture.record_count
    diagnostics = capture.diagnostics

    out: list[str] = [
        f"# Client tooltip capture report: {capture.game} {capture.client_build} {capture.locale}",
        "",
        "> Generated by `python -m talent_data tooltips report` from the imported client",
        "> capture. Observational only; do not edit by hand.",
        "",
        "## Identity",
        "",
        *_table(
            ("fact", "value"),
            [
                ("client build", capture.client_build),
                ("locale", capture.locale),
                ("manifest hash", f"`{capture.manifest_hash}`"),
                ("semantic hash", f"`{capture.semantic_hash}`"),
                ("captured at (diagnostic)", capture.captured_at),
                (
                    "captured on (diagnostic)",
                    f"{diagnostics.player_class_token} level {diagnostics.player_level}",
                ),
                ("interface version (diagnostic)", diagnostics.interface_version),
                ("capture passes (diagnostic)", diagnostics.passes),
                (
                    "TOOLTIP_DATA_UPDATE (diagnostic)",
                    f"registered {diagnostics.update_event_registered}, "
                    f"events {diagnostics.update_events}, "
                    f"without id {diagnostics.update_events_without_id}",
                ),
            ],
        ),
        "",
        "## Totals",
        "",
        *_table(
            ("question", "answer"),
            [
                ("talent entries", capture.entry_count),
                ("entry/rank requests", total),
                ("nil responses in the final capture", "0 (an import requires every tooltip)"),
                ("tooltips with `hasDynamicData` true", dynamic.get(True, 0)),
                ("tooltips with `hasDynamicData` false", dynamic.get(False, 0)),
                ("tooltips without a `hasDynamicData` field", dynamic.get("absent", 0)),
                (
                    "tooltips whose first line is exactly the talent name",
                    f"{first_is_name} / {total}",
                ),
                ("tooltips with any line exactly the talent name", f"{any_is_name} / {total}"),
                ("multi-rank entries", len(multi_rank)),
                ("multi-rank entries whose lines differ between ranks", rank_varies),
                ("multi-rank entries whose line count differs between ranks", line_count_varies),
                ("tooltips with template-like `$` tokens (heuristic)", with_template),
                ("tooltips with colour markup `|c…` / `|r`", with_color),
                ("tooltips with other `|` escapes", with_other_pipe),
                ("tooltips with the word `Rank` (heuristic)", with_rank),
                ("tooltips with the word `Requires` (heuristic)", with_requires),
            ],
        ),
        "",
        "## TooltipDataLine types",
        "",
        *_table(("line type", "lines"), _counter_rows(line_types)),
        "",
        "## Lines per tooltip",
        "",
        *_table(("lines", "tooltips"), sorted(line_counts.items())),
        "",
        "## Line-type sequences (most common first)",
        "",
        *_table(
            ("sequence of line types", "tooltips"),
            [(" ".join(str(t) for t in seq), n) for seq, n in sequences.most_common(15)],
        ),
        "",
        "## Fields present",
        "",
        *_table(("TooltipData field", "tooltips"), _counter_rows(tooltip_fields)),
        "",
        *_table(("TooltipDataLine field", "lines"), _counter_rows(line_fields)),
        "",
        "## Reads needed per tooltip (2 = stable at once)",
        "",
        *_table(("reads", "tooltips"), sorted(reads.items())),
        "",
        "## Text found by the heuristics",
        "",
    ]
    for title, bucket in (
        ("Template-like `$` tokens", template_examples),
        ("`Rank`", rank_examples),
        ("`Requires`", requires_examples),
    ):
        out.append(f"{title}: " + (", ".join(_quote(t) for t in bucket) if bucket else "none"))
        out.append("")

    out.extend(["## Examples", ""])
    chosen: list[ManifestEntry] = []
    for token, name in _NAMED_EXAMPLES:
        chosen.extend(e for e in manifest.entries if (e.class_token, e.name) == (token, name))
    for token in _FIRST_OF_CLASS:
        chosen.extend([e for e in manifest.entries if e.class_token == token][:1])
    chosen.extend(
        [
            e
            for e in manifest.entries
            if e.class_token == _CONDITIONAL_CLASS
            and any(s in (templates.get(e.entry_id) or "") for s in _CONDITIONAL_SYNTAX)
        ][:1]
    )
    for entry in chosen:
        out.extend(_example(entry, by_entry[entry.entry_id]))
    return "\n".join(out).rstrip("\n") + "\n"
