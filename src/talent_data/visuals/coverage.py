"""Icon coverage report of one build - the gate before any visual is published or bundled."""

from __future__ import annotations

from collections import Counter

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from talent_data.visuals.resolver import IconResolution


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, frozen=True, extra="forbid"
    )


class ClassCoverage(_Model):
    class_token: str
    total_entries: int
    resolved_entries: int
    unresolved_entries: int
    distinct_file_data_ids: int


class SharedIcon(_Model):
    file_data_id: int
    entry_count: int
    entry_ids: list[int]


class UnresolvedEntry(_Model):
    class_token: str
    node_id: int
    entry_id: int
    spell_id: int
    display_spell_id: int | None
    reason: str
    detail: str


class IconCoverageReport(_Model):
    game: str
    client_build: str
    total_entries: int
    resolved_entries: int
    unresolved_entries: int
    distinct_file_data_ids: int
    shared_file_data_ids: int
    classes: list[ClassCoverage]
    shared_icons: list[SharedIcon]
    unresolved: list[UnresolvedEntry]

    @property
    def complete(self) -> bool:
        return self.unresolved_entries == 0 and self.total_entries > 0

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True, indent=2) + "\n"


def build_coverage_report(
    *, game: str, client_build: str, by_class: dict[str, list[IconResolution]]
) -> IconCoverageReport:
    """Exact totals over every class given, in the given (class id) order."""
    everything = [r for resolutions in by_class.values() for r in resolutions]
    resolved = [r for r in everything if r.resolved]
    usage: Counter[int] = Counter(
        r.icon_file_data_id for r in resolved if r.icon_file_data_id is not None
    )
    shared = [
        SharedIcon(
            file_data_id=fid,
            entry_count=count,
            entry_ids=sorted(r.entry_id for r in resolved if r.icon_file_data_id == fid),
        )
        for fid, count in sorted(usage.items())
        if count > 1
    ]
    return IconCoverageReport(
        game=game,
        client_build=client_build,
        total_entries=len(everything),
        resolved_entries=len(resolved),
        unresolved_entries=len(everything) - len(resolved),
        distinct_file_data_ids=len(usage),
        shared_file_data_ids=len(shared),
        classes=[
            ClassCoverage(
                class_token=token,
                total_entries=len(resolutions),
                resolved_entries=sum(1 for r in resolutions if r.resolved),
                unresolved_entries=sum(1 for r in resolutions if not r.resolved),
                distinct_file_data_ids=len(
                    {r.icon_file_data_id for r in resolutions if r.resolved}
                ),
            )
            for token, resolutions in by_class.items()
        ],
        shared_icons=shared,
        unresolved=[
            UnresolvedEntry(
                class_token=r.class_token,
                node_id=r.node_id,
                entry_id=r.entry_id,
                spell_id=r.spell_id,
                display_spell_id=r.display_spell_id,
                reason=r.reason.value if r.reason is not None else "unknown",
                detail=r.detail or "",
            )
            for r in everything
            if not r.resolved
        ],
    )
