"""Tooltip coverage report: every rank of every talent entry, every token occurrence.

The report is the gate of M5.V2. It counts rank descriptions by status, token occurrences
(per rank) by category and outcome, and lists every gap with the talent, rank, template and
reason. Deterministic: no timestamp, fixed ordering.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal, get_args

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.tooltips import (
    GapClassification,
    RankStatus,
    TalentTooltipsDocument,
    TokenCategory,
)
from talent_data.tooltips.render import TokenOutcome

CategoryStatus = Literal[
    "RESOLVED", "PARTIALLY_RESOLVED", "UNSUPPORTED", "CONTEXT_DEPENDENT", "NOT_PRESENT"
]


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid", strict=True
    )


class RankTotals(_Model):
    entries: int
    ranks: int
    resolved: int
    partial: int
    unresolved: int
    resolved_percent: float = Field(description="resolved / ranks x 100, two decimals")


class ClassCoverage(RankTotals):
    class_token: str


class CategoryCoverage(_Model):
    category: TokenCategory
    status: CategoryStatus
    occurrences: int = Field(description="Token occurrences over all ranks")
    resolved: int
    context_dependent: int
    unsupported: int
    missing_data: int


class GapRecord(_Model):
    class_token: str
    talent_name: str | None
    entry_id: int
    rank: int
    rank_status: RankStatus
    category: TokenCategory
    classification: GapClassification
    token: str
    fragment: str
    reason: str
    template: str | None


class TooltipCoverageReport(_Model):
    game: str
    client_build: str
    totals: RankTotals
    classes: list[ClassCoverage]
    categories: list[CategoryCoverage]
    gaps: list[GapRecord] = Field(description="Every gap of every rank, in class/entry/rank order")

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def _percent(part: int, whole: int) -> float:
    return round(part * 100 / whole, 2) if whole else 0.0


def _totals(statuses: Sequence[RankStatus], entries: int) -> dict[str, int | float]:
    counts = Counter(statuses)
    return {
        "entries": entries,
        "ranks": len(statuses),
        "resolved": counts["resolved"],
        "partial": counts["partial"],
        "unresolved": counts["unresolved"],
        "resolved_percent": _percent(counts["resolved"], len(statuses)),
    }


def _category_status(row: Counter[str]) -> CategoryStatus:
    occurrences = sum(row.values())
    if not occurrences:
        return "NOT_PRESENT"
    if row["resolved"] == occurrences:
        return "RESOLVED"
    if row["resolved"]:
        return "PARTIALLY_RESOLVED"
    if row["contextDependent"] == occurrences:
        return "CONTEXT_DEPENDENT"
    return "UNSUPPORTED"


@dataclass(frozen=True)
class ClassInput:
    talents: TalentDataDocument
    tooltips: TalentTooltipsDocument


def build_report(
    *,
    game: str,
    client_build: str,
    classes: Sequence[ClassInput],
    outcomes: Sequence[TokenOutcome],
) -> TooltipCoverageReport:
    all_statuses: list[RankStatus] = []
    class_rows: list[ClassCoverage] = []
    gaps: list[GapRecord] = []
    entry_count = 0
    for item in classes:
        talents: dict[int, TalentEntry] = {
            e.id: e for tree in item.talents.trees for node in tree.nodes for e in node.entries
        }
        statuses: list[RankStatus] = []
        for entry in item.tooltips.entries:
            talent = talents[entry.entry_id]
            for rank in entry.ranks:
                statuses.append(rank.status)
                gaps.extend(
                    GapRecord(
                        class_token=item.tooltips.class_token,
                        talent_name=talent.name,
                        entry_id=entry.entry_id,
                        rank=rank.rank,
                        rank_status=rank.status,
                        category=gap.category,
                        classification=gap.classification,
                        token=gap.token,
                        fragment=gap.fragment,
                        reason=gap.reason,
                        template=talent.description_template,
                    )
                    for gap in rank.gaps
                )
        entry_count += len(item.tooltips.entries)
        all_statuses += statuses
        class_rows.append(
            ClassCoverage(
                class_token=item.tooltips.class_token,
                **_totals(statuses, len(item.tooltips.entries)),  # type: ignore[arg-type]
            )
        )

    per_category: dict[str, Counter[str]] = {name: Counter() for name in get_args(TokenCategory)}
    for outcome in outcomes:
        per_category[outcome.category][outcome.outcome] += 1
    categories = [
        CategoryCoverage(
            category=name,  # type: ignore[arg-type]
            status=_category_status(row),
            occurrences=sum(row.values()),
            resolved=row["resolved"],
            context_dependent=row["contextDependent"],
            unsupported=row["unsupported"],
            missing_data=row["missingData"],
        )
        for name, row in per_category.items()
    ]
    return TooltipCoverageReport(
        game=game,
        client_build=client_build,
        totals=RankTotals(**_totals(all_statuses, entry_count)),  # type: ignore[arg-type]
        classes=class_rows,
        categories=categories,
        gaps=gaps,
    )


# --------------------------------------------------------------------------- markdown


def _cell(text: str | None) -> str:
    if text is None:
        return "—"
    flat = text.replace("\r\n", "⏎").replace("\n", "⏎").replace("|", "¦")
    return f"`{flat}`" if flat else "—"


def render_markdown(report: TooltipCoverageReport) -> str:
    totals = report.totals
    lines = [
        f"# Forever {report.client_build} talent tooltip coverage (M5.V2)",
        "",
        f"> Generated by `python -m talent_data tooltips normalize --build {report.client_build}`",
        "> from the committed dataset and the build's DB2 tables. Do not edit by hand;",
        "> `python -m talent_data tooltips check` and the test suite fail when it is out of",
        "> date. Rules and evidence: `docs/talent-tooltips.md`.",
        "",
        "## Rank descriptions",
        "",
        "| | entries | ranks | resolved | partial | unresolved | resolved % |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in [*report.classes]:
        lines.append(
            f"| {row.class_token} | {row.entries} | {row.ranks} | {row.resolved} | "
            f"{row.partial} | {row.unresolved} | {row.resolved_percent:.2f} |"
        )
    lines.append(
        f"| **all** | **{totals.entries}** | **{totals.ranks}** | **{totals.resolved}** | "
        f"**{totals.partial}** | **{totals.unresolved}** | **{totals.resolved_percent:.2f}** |"
    )
    lines += [
        "",
        "## Token categories",
        "",
        "Occurrences are counted per rank (a 5-rank talent with `$s1` counts five), for the",
        "talent's own template including tokens inside expressions and conditional branches.",
        "",
        "| category | status | occurrences | resolved | context-dependent | unsupported "
        "| missing data |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for c in report.categories:
        lines.append(
            f"| `{c.category}` | {c.status} | {c.occurrences} | {c.resolved} | "
            f"{c.context_dependent} | {c.unsupported} | {c.missing_data} |"
        )
    lines += [
        "",
        "## Every gap",
        "",
        "One row per gap and rank. A gap is a construct shown as unavailable in the app; the",
        "rest of the description is still shown (`partial`). `⏎` marks a line break, `¦` a `|`.",
        "",
        "| class | talent | entry | rank | category | classification | token | reason |",
        "| --- | --- | ---: | ---: | --- | --- | --- | --- |",
    ]
    for gap in report.gaps:
        lines.append(
            f"| {gap.class_token} | {gap.talent_name or '—'} | {gap.entry_id} | {gap.rank} | "
            f"`{gap.category}` | {gap.classification} | {_cell(gap.token)} | "
            f"{gap.reason.replace('|', '¦')} |"
        )
    templates: dict[tuple[str, int], tuple[str | None, str | None]] = {}
    for gap in report.gaps:
        templates.setdefault((gap.class_token, gap.entry_id), (gap.talent_name, gap.template))
    lines += [
        "",
        "## Templates of the talents with gaps",
        "",
        "| class | talent | entry | template |",
        "| --- | --- | ---: | --- |",
    ]
    for (class_token, entry_id), (name, template) in templates.items():
        lines.append(f"| {class_token} | {name or '—'} | {entry_id} | {_cell(template)} |")
    lines.append("")
    return "\n".join(lines)
