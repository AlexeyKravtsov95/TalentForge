"""Talent entry -> icon FileDataID resolution.

Source chain, verified against the real 1.60.1.70009 tables (docs/talent-visuals.md):

    TalentEntry.definitionId
      -> TraitDefinition.ID                (SpellID, VisibleSpellID, OverrideIcon)
      -> displaySpellId = VisibleSpellID if non-zero else SpellID
      -> SpellMisc.SpellID                 (SpellIconFileDataID)

Everything that is not a single, unambiguous, non-zero icon is *unresolved* with a stable
reason code. Nothing is guessed and there is no fallback from one spell to another.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import StrEnum

from talent_data.db2 import tables as t
from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.visuals import (
    VISUALS_SCHEMA_VERSION,
    TalentVisualEntry,
    TalentVisualsDocument,
    VisualsProvenance,
    content_hash,
    hashed_payload,
)
from talent_data.sources.base import Db2Source, SourceError
from talent_data.versions import ClientVersion

ICON_COLUMN = "SpellMisc.SpellIconFileDataID"
VISUAL_SOURCE_TABLES: tuple[str, ...] = ("TraitDefinition", "SpellMisc")


class UnresolvedReason(StrEnum):
    # The TraitDefinition the entry points at is not in the build's table.
    DEFINITION_MISSING = "definition-missing"
    # talent-data.v1 and the DB2 table disagree about the entry's spell: the two inputs are
    # not the same build/dataset, so nothing derived from them can be trusted.
    SPELL_MISMATCH = "spell-mismatch"
    # TraitDefinition.OverrideIcon is set. Whether (and how) the Forever client lets it
    # replace the spell icon is not recorded evidence, so the icon is not chosen for it.
    OVERRIDE_ICON_UNPROVEN = "override-icon-unproven"
    # No SpellMisc row for the display spell.
    SPELL_MISC_MISSING = "spell-misc-missing"
    # SpellMisc.SpellIconFileDataID is 0 (the client's "no icon").
    ICON_ZERO = "icon-zero"
    # Several SpellMisc rows (per DifficultyID) name different icons and no selection rule
    # is proven for this build.
    ICON_CONFLICT = "icon-conflict"


@dataclass(frozen=True)
class VisualSourceTables:
    """The DB2 rows icon resolution reads, indexed."""

    definitions: Mapping[int, t.TraitDefinition]
    spell_misc: Mapping[int, tuple[t.SpellMisc, ...]]  # by SpellID

    @classmethod
    def load(cls, source: Db2Source, version: ClientVersion) -> VisualSourceTables:
        definitions: dict[int, t.TraitDefinition] = {}
        for definition in t.parse_table(
            source.load_table(version, t.TraitDefinition.TABLE), t.TraitDefinition.parse
        ):
            if definition.id in definitions:
                raise SourceError(f"TraitDefinition: duplicate id={definition.id}")
            definitions[definition.id] = definition
        return cls(
            definitions=definitions,
            spell_misc=index_spell_misc(
                t.parse_table(source.load_table(version, t.SpellMisc.TABLE), t.SpellMisc.parse)
            ),
        )


def index_spell_misc(rows: Iterable[t.SpellMisc]) -> dict[int, tuple[t.SpellMisc, ...]]:
    seen: set[int] = set()
    by_spell: dict[int, list[t.SpellMisc]] = defaultdict(list)
    for row in rows:
        if row.id in seen:
            raise SourceError(f"SpellMisc: duplicate id={row.id}")
        seen.add(row.id)
        by_spell[row.spell_id].append(row)
    return {
        spell_id: tuple(sorted(group, key=lambda r: (r.difficulty_id, r.id)))
        for spell_id, group in by_spell.items()
    }


def display_spell_id(definition: t.TraitDefinition) -> int:
    """The spell the client presents for a definition - the same preference the normalizer
    uses for the displayed name and description."""
    return definition.visible_spell_id or definition.spell_id


@dataclass(frozen=True)
class IconResolution:
    """Outcome for one talent entry: ``icon_file_data_id`` xor ``reason`` is set."""

    class_token: str
    node_id: int
    entry_id: int
    definition_id: int
    spell_id: int
    display_spell_id: int | None
    icon_file_data_id: int | None
    reason: UnresolvedReason | None
    detail: str | None

    @property
    def resolved(self) -> bool:
        return self.icon_file_data_id is not None


def resolve_spell_icon(
    rows: tuple[t.SpellMisc, ...],
) -> tuple[int | None, UnresolvedReason | None, str | None]:
    """The normal (not the "active") icon of a spell from its SpellMisc rows.

    No DifficultyID is preferred: every talent display spell of 1.60.1.70009 has exactly
    one row (DifficultyID 0), so a preference would be an unproven rule. Several rows are
    accepted only when they all agree on one non-zero icon.
    """
    if not rows:
        return None, UnresolvedReason.SPELL_MISC_MISSING, "no SpellMisc row"
    icons = sorted({row.spell_icon_file_data_id for row in rows})
    if len(icons) > 1:
        described = ", ".join(
            f"DifficultyID {row.difficulty_id} -> {row.spell_icon_file_data_id}" for row in rows
        )
        return None, UnresolvedReason.ICON_CONFLICT, f"SpellMisc rows disagree: {described}"
    if icons[0] <= 0:
        return None, UnresolvedReason.ICON_ZERO, f"SpellIconFileDataID is {icons[0]}"
    return icons[0], None, None


def resolve_document(
    document: TalentDataDocument, tables: VisualSourceTables
) -> list[IconResolution]:
    """One resolution per talent entry of ``document``, ordered by entry id."""
    out: list[IconResolution] = []
    for tree in document.trees:
        for node in tree.nodes:
            for entry in node.entries:
                out.append(_resolve_entry(document.class_.token, node.id, entry, tables))
    ids = [r.entry_id for r in out]
    if len(ids) != len(set(ids)):
        raise SourceError(
            f"{document.class_.token}: talent entry ids are not unique in the talent-data "
            "document; talent-visuals.v1 is keyed by entryId"
        )
    return sorted(out, key=lambda r: r.entry_id)


def _resolve_entry(
    class_token: str, node_id: int, entry: TalentEntry, tables: VisualSourceTables
) -> IconResolution:
    entry_id, definition_id, spell_id = entry.id, entry.definition_id, entry.spell_id

    def unresolved(
        reason: UnresolvedReason, detail: str, display: int | None = None
    ) -> IconResolution:
        return IconResolution(
            class_token, node_id, entry_id, definition_id, spell_id, display, None, reason, detail
        )

    definition = tables.definitions.get(definition_id)
    if definition is None:
        return unresolved(
            UnresolvedReason.DEFINITION_MISSING, f"TraitDefinition {definition_id} not found"
        )
    if definition.spell_id != spell_id:
        return unresolved(
            UnresolvedReason.SPELL_MISMATCH,
            f"talent-data spellId {spell_id} != TraitDefinition.SpellID {definition.spell_id}",
        )
    display = display_spell_id(definition)
    if definition.override_icon != 0:
        return unresolved(
            UnresolvedReason.OVERRIDE_ICON_UNPROVEN,
            f"TraitDefinition.OverrideIcon is {definition.override_icon}",
            display,
        )
    icon, reason, detail = resolve_spell_icon(tables.spell_misc.get(display, ()))
    if icon is None or reason is not None:
        return unresolved(reason or UnresolvedReason.ICON_ZERO, detail or "", display)
    return IconResolution(
        class_token, node_id, entry_id, definition_id, spell_id, display, icon, None, None
    )


class UnresolvedIconsError(SourceError):
    """A talent-visuals.v1 document was requested for a class with unresolved icons."""

    def __init__(self, class_token: str, unresolved: list[IconResolution]) -> None:
        lines = "; ".join(
            f"entry {r.entry_id} (node {r.node_id}): {r.reason.value if r.reason else '?'}"
            for r in unresolved
        )
        super().__init__(
            f"{class_token}: {len(unresolved)} talent icon(s) unresolved, nothing published: "
            f"{lines}"
        )
        self.class_token = class_token
        self.unresolved = tuple(unresolved)


def build_visuals_document(
    document: TalentDataDocument, resolutions: list[IconResolution], *, source_name: str
) -> TalentVisualsDocument:
    """talent-visuals.v1 for ``document``. Refuses a class with any unresolved icon: the
    contract has no "missing icon" shape, so partial visuals are never published."""
    unresolved = [r for r in resolutions if not r.resolved]
    if unresolved:
        raise UnresolvedIconsError(document.class_.token, unresolved)
    entries = [
        TalentVisualEntry(
            entry_id=r.entry_id,
            definition_id=r.definition_id,
            spell_id=r.spell_id,
            display_spell_id=_required(r.display_spell_id),
            icon_file_data_id=_required(r.icon_file_data_id),
        )
        for r in sorted(resolutions, key=lambda r: r.entry_id)
    ]
    draft = TalentVisualsDocument(
        schema_version=VISUALS_SCHEMA_VERSION,
        game=document.game,
        client_build=document.client_build,
        class_token=document.class_.token,
        talent_data_content_hash=document.content_hash,
        entries=entries,
        content_hash="sha256:" + "0" * 64,
        provenance=VisualsProvenance(
            source=source_name, tables=list(VISUAL_SOURCE_TABLES), icon_column=ICON_COLUMN
        ),
    )
    digest = content_hash(hashed_payload(draft.model_dump(mode="json", by_alias=True)))
    return draft.model_copy(update={"content_hash": digest})


def _required(value: int | None) -> int:
    if value is None:
        raise SourceError("resolved icon without a value")
    return value
