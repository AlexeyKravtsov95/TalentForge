"""Localization coverage: how much of a build's talent text a locale really provides.

The report is computed from the overlays of **every** class of the build, whether or not the
overlay of a class is published (``published``): coverage statistics are evidence about the
source, publication is a product decision. Every name that is not translated and every
description that is not rendered is listed with its reason, so a reviewer sees each fallback
to English. Deterministic: no timestamp.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from talent_data.models.localization import (
    DESCRIPTION_STATUSES,
    DescriptionStatus,
    NameStatus,
    TalentLocalizationDocument,
)
from talent_data.models.talents import TalentDataDocument


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid", strict=True
    )


class NameCounts(_Model):
    translated: int
    untranslated: int
    missing: int


class DescriptionCounts(_Model):
    missing: int
    untranslated: int
    source_not_resolved: int
    plural_rule_unverified: int
    structure_mismatch: int
    render_gap: int
    decimal_format_unverified: int
    rendered: int


class CoverageCounts(_Model):
    entries: int
    names: NameCounts
    descriptions: DescriptionCounts
    ranks: int
    rendered_ranks: int


class ClassCoverage(CoverageCounts):
    class_token: str
    published: bool


class NameException(_Model):
    class_token: str
    entry_id: int
    node_id: int
    text_spell_id: int
    english_name: str | None
    status: NameStatus


class DescriptionException(_Model):
    class_token: str
    entry_id: int
    text_spell_id: int
    english_name: str | None
    status: DescriptionStatus


class LocalizationCoverageReport(_Model):
    game: str
    client_build: str
    locale: str
    classes: list[ClassCoverage]
    totals: CoverageCounts
    name_exceptions: list[NameException]
    description_exceptions: list[DescriptionException]

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


@dataclass(frozen=True)
class ClassInput:
    talents: TalentDataDocument
    overlay: TalentLocalizationDocument
    published: bool


def _counts(overlays: Sequence[TalentLocalizationDocument]) -> CoverageCounts:
    entries = [e for o in overlays for e in o.entries]
    names: Counter[str] = Counter(e.name.status for e in entries)
    descriptions: Counter[str] = Counter(e.description.status for e in entries)
    return CoverageCounts(
        entries=len(entries),
        names=NameCounts(
            translated=names["translated"],
            untranslated=names["untranslated"],
            missing=names["missing"],
        ),
        descriptions=DescriptionCounts(
            missing=descriptions["missing"],
            untranslated=descriptions["untranslated"],
            source_not_resolved=descriptions["sourceNotResolved"],
            plural_rule_unverified=descriptions["pluralRuleUnverified"],
            structure_mismatch=descriptions["structureMismatch"],
            render_gap=descriptions["renderGap"],
            decimal_format_unverified=descriptions["decimalFormatUnverified"],
            rendered=descriptions["rendered"],
        ),
        ranks=sum(e.max_rank for e in entries),
        rendered_ranks=sum(len(e.description.ranks) for e in entries),
    )


def build_report(
    *, game: str, client_build: str, locale: str, classes: Sequence[ClassInput]
) -> LocalizationCoverageReport:
    rows: list[ClassCoverage] = []
    name_exceptions: list[NameException] = []
    description_exceptions: list[DescriptionException] = []
    for item in classes:
        token = item.overlay.class_token
        counts = _counts([item.overlay])
        rows.append(
            ClassCoverage(
                class_token=token,
                published=item.published,
                entries=counts.entries,
                names=counts.names,
                descriptions=counts.descriptions,
                ranks=counts.ranks,
                rendered_ranks=counts.rendered_ranks,
            )
        )
        english = {
            e.id: e.name for tree in item.talents.trees for n in tree.nodes for e in n.entries
        }
        for entry in item.overlay.entries:
            if entry.name.status != "translated":
                name_exceptions.append(
                    NameException(
                        class_token=token,
                        entry_id=entry.entry_id,
                        node_id=entry.node_id,
                        text_spell_id=entry.text_spell_id,
                        english_name=english.get(entry.entry_id),
                        status=entry.name.status,
                    )
                )
            if entry.description.status != "rendered":
                description_exceptions.append(
                    DescriptionException(
                        class_token=token,
                        entry_id=entry.entry_id,
                        text_spell_id=entry.text_spell_id,
                        english_name=english.get(entry.entry_id),
                        status=entry.description.status,
                    )
                )
    return LocalizationCoverageReport(
        game=game,
        client_build=client_build,
        locale=locale,
        classes=rows,
        totals=_counts([c.overlay for c in classes]),
        name_exceptions=name_exceptions,
        description_exceptions=description_exceptions,
    )


def _percent(part: int, whole: int) -> str:
    return f"{100 * part / whole:.1f} %" if whole else "-"


def _description_cell(counts: DescriptionCounts, status: DescriptionStatus) -> int:
    value = counts.model_dump(by_alias=True)[status]
    assert isinstance(value, int)
    return value


def render_markdown(report: LocalizationCoverageReport) -> str:
    t = report.totals
    lines = [
        f"# {report.locale} talent text coverage: {report.game} {report.client_build}",
        "",
        "Generated by `python -m talent_data localization normalize` from the client's own "
        f"{report.locale} strings of exactly this build - do not edit by hand. Rules, gates and "
        "evidence: [`docs/talent-localization.md`](../talent-localization.md).",
        "",
        f"**Names:** {t.names.translated} / {t.entries} translated "
        f"({_percent(t.names.translated, t.entries)}), {t.names.untranslated} untranslated "
        f"(English served for {report.locale}), {t.names.missing} missing.",
        "",
        f"**Descriptions:** {t.descriptions.rendered} / {t.entries} entries rendered "
        f"({t.rendered_ranks} / {t.ranks} ranks); every other entry falls back to the "
        "English description.",
        "",
        "## Per class",
        "",
        "| Class | Published | Entries | Names translated | Names untranslated | Names missing "
        "| Descriptions rendered | Ranks rendered |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for row in report.classes:
        lines.append(
            f"| {row.class_token} | {'yes' if row.published else 'no'} | {row.entries} "
            f"| {row.names.translated} | {row.names.untranslated} | {row.names.missing} "
            f"| {row.descriptions.rendered} | {row.rendered_ranks} / {row.ranks} |"
        )
    lines += [
        "",
        "## Description status",
        "",
        "| Class | " + " | ".join(f"`{s}`" for s in DESCRIPTION_STATUSES) + " |",
        "| --- | " + " | ".join("---" for _ in DESCRIPTION_STATUSES) + " |",
    ]
    for row in report.classes:
        cells = " | ".join(
            str(_description_cell(row.descriptions, s)) for s in DESCRIPTION_STATUSES
        )
        lines.append(f"| {row.class_token} | {cells} |")
    totals = " | ".join(str(_description_cell(t.descriptions, s)) for s in DESCRIPTION_STATUSES)
    lines.append(f"| **total** | {totals} |")
    lines += [
        "",
        "## Names shown in English",
        "",
        "| Class | Entry | Node | Text spell | English name | Status |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {e.class_token} | {e.entry_id} | {e.node_id} | {e.text_spell_id} "
        f"| {e.english_name or '-'} | `{e.status}` |"
        for e in report.name_exceptions
    ]
    lines += [
        "",
        "## Descriptions shown in English",
        "",
        "| Class | Entry | Text spell | English name | Status |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {e.class_token} | {e.entry_id} | {e.text_spell_id} | {e.english_name or '-'} "
        f"| `{e.status}` |"
        for e in report.description_exceptions
    ]
    return "\n".join(lines) + "\n"
