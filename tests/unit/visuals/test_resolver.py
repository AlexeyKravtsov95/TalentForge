"""Icon resolution rules: real rows where the build has them, explicit row mutations for
the shapes the two captured builds do not happen to contain."""

from __future__ import annotations

import dataclasses

import pytest

from talent_data.db2 import tables as t
from talent_data.models.talents import TalentDataDocument
from talent_data.sources.base import SourceError
from talent_data.visuals.resolver import (
    IconResolution,
    UnresolvedIconsError,
    UnresolvedReason,
    VisualSourceTables,
    build_visuals_document,
    display_spell_id,
    index_spell_misc,
    resolve_document,
    resolve_spell_icon,
)

IMPROVED_REND_ENTRY = 130686  # Warrior, spell 12286, definition 135487
IMPROVED_REND_ICON = 132155


def misc(spell_id: int, icon: int, *, row_id: int = 1, difficulty: int = 0, active: int = 0):
    return t.SpellMisc(
        id=row_id,
        spell_id=spell_id,
        difficulty_id=difficulty,
        spell_icon_file_data_id=icon,
        active_icon_file_data_id=active,
    )


def with_definition(tables: VisualSourceTables, definition_id: int, **update: int):
    definitions = dict(tables.definitions)
    definitions[definition_id] = dataclasses.replace(definitions[definition_id], **update)
    return VisualSourceTables(definitions=definitions, spell_misc=tables.spell_misc)


def with_spell_misc(tables: VisualSourceTables, spell_id: int, rows: tuple[t.SpellMisc, ...]):
    spell_misc = dict(tables.spell_misc)
    if rows:
        spell_misc[spell_id] = rows
    else:
        spell_misc.pop(spell_id, None)
    return VisualSourceTables(definitions=tables.definitions, spell_misc=spell_misc)


def resolution_of(
    doc: TalentDataDocument, tables: VisualSourceTables, entry_id: int
) -> IconResolution:
    (found,) = [r for r in resolve_document(doc, tables) if r.entry_id == entry_id]
    return found


# --------------------------------------------------------------------------- display spell


def test_display_spell_is_the_definition_spell_when_no_visible_spell(visual_tables) -> None:
    definition = visual_tables.definitions[135487]
    assert definition.visible_spell_id == 0
    assert display_spell_id(definition) == 12286


def test_display_spell_prefers_visible_spell_id(visual_tables) -> None:
    definition = dataclasses.replace(visual_tables.definitions[135487], visible_spell_id=772)
    assert display_spell_id(definition) == 772


def test_icon_comes_from_the_visible_spell_not_the_underlying_one(
    canonical_docs, visual_tables
) -> None:
    """A VisibleSpellID redirects the icon lookup; the underlying spell's icon is unused."""
    tables = with_definition(visual_tables, 135487, visible_spell_id=999_001)
    tables = with_spell_misc(tables, 999_001, (misc(999_001, 4242),))
    found = resolution_of(canonical_docs["warrior"], tables, IMPROVED_REND_ENTRY)
    assert found.display_spell_id == 999_001
    assert found.icon_file_data_id == 4242
    assert found.spell_id == 12286


def test_no_fallback_to_the_underlying_spell_when_the_visible_spell_has_no_icon(
    canonical_docs, visual_tables
) -> None:
    tables = with_definition(visual_tables, 135487, visible_spell_id=999_001)
    found = resolution_of(canonical_docs["warrior"], tables, IMPROVED_REND_ENTRY)
    assert not found.resolved
    assert found.reason is UnresolvedReason.SPELL_MISC_MISSING
    assert found.display_spell_id == 999_001


# --------------------------------------------------------------------------- icon column


def test_real_entry_resolves_to_its_spell_icon(canonical_docs, visual_tables) -> None:
    found = resolution_of(canonical_docs["warrior"], visual_tables, IMPROVED_REND_ENTRY)
    assert found == IconResolution(
        class_token="WARRIOR",
        node_id=105956,
        entry_id=IMPROVED_REND_ENTRY,
        definition_id=135487,
        spell_id=12286,
        display_spell_id=12286,
        icon_file_data_id=IMPROVED_REND_ICON,
        reason=None,
        detail=None,
    )


@pytest.mark.parametrize(
    ("class_key", "spell_id", "icon", "active_icon"),
    [
        ("druid", 24858, 136036, 136116),
        ("mage", 12042, 136048, 136116),
        ("mage", 11958, 135841, 136116),
        ("priest", 15473, 136200, 136130),
    ],
)
def test_normal_icon_is_used_even_when_an_active_icon_exists(
    canonical_docs, visual_tables, class_key, spell_id, icon, active_icon
) -> None:
    """The four real talents of the build whose spell also has an ActiveIconFileDataID."""
    (row,) = visual_tables.spell_misc[spell_id]
    assert (row.spell_icon_file_data_id, row.active_icon_file_data_id) == (icon, active_icon)
    (found,) = [
        r
        for r in resolve_document(canonical_docs[class_key], visual_tables)
        if r.spell_id == spell_id
    ]
    assert found.icon_file_data_id == icon


# --------------------------------------------------------------------------- fail closed


def test_missing_spell_misc_row_is_unresolved() -> None:
    assert resolve_spell_icon(()) == (
        None,
        UnresolvedReason.SPELL_MISC_MISSING,
        "no SpellMisc row",
    )


@pytest.mark.parametrize("icon", [0, -1])
def test_zero_icon_is_unresolved_not_an_icon(icon: int) -> None:
    resolved, reason, _ = resolve_spell_icon((misc(5, icon),))
    assert resolved is None
    assert reason is UnresolvedReason.ICON_ZERO


def test_conflicting_rows_are_unresolved() -> None:
    rows = (misc(5, 100, row_id=1, difficulty=0), misc(5, 200, row_id=2, difficulty=186))
    resolved, reason, detail = resolve_spell_icon(rows)
    assert resolved is None
    assert reason is UnresolvedReason.ICON_CONFLICT
    assert detail == "SpellMisc rows disagree: DifficultyID 0 -> 100, DifficultyID 186 -> 200"


def test_a_zero_row_next_to_a_real_icon_is_a_conflict_not_a_preference() -> None:
    rows = (misc(5, 100, row_id=1, difficulty=0), misc(5, 0, row_id=2, difficulty=186))
    assert resolve_spell_icon(rows)[1] is UnresolvedReason.ICON_CONFLICT


def test_identical_multi_row_icons_resolve_regardless_of_row_order() -> None:
    a = misc(5, 100, row_id=1, difficulty=0)
    b = misc(5, 100, row_id=2, difficulty=186)
    c = misc(5, 100, row_id=3, difficulty=9)
    assert resolve_spell_icon((a, b, c)) == (100, None, None)
    assert index_spell_misc([c, a, b]) == index_spell_misc([b, c, a]) == {5: (a, c, b)}


def test_duplicate_spell_misc_ids_are_rejected() -> None:
    with pytest.raises(SourceError, match="duplicate id=1"):
        index_spell_misc([misc(5, 100), misc(6, 100)])


def test_override_icon_is_unresolved_until_its_precedence_is_proven(
    canonical_docs, visual_tables
) -> None:
    tables = with_definition(visual_tables, 135487, override_icon=132155)
    found = resolution_of(canonical_docs["warrior"], tables, IMPROVED_REND_ENTRY)
    assert not found.resolved
    assert found.reason is UnresolvedReason.OVERRIDE_ICON_UNPROVEN


def test_unknown_definition_is_unresolved(canonical_docs, visual_tables) -> None:
    definitions = {k: v for k, v in visual_tables.definitions.items() if k != 135487}
    tables = VisualSourceTables(definitions=definitions, spell_misc=visual_tables.spell_misc)
    found = resolution_of(canonical_docs["warrior"], tables, IMPROVED_REND_ENTRY)
    assert found.reason is UnresolvedReason.DEFINITION_MISSING
    assert found.display_spell_id is None


def test_dataset_and_db2_disagreeing_on_the_spell_is_unresolved(
    canonical_docs, visual_tables
) -> None:
    tables = with_definition(visual_tables, 135487, spell_id=772)
    found = resolution_of(canonical_docs["warrior"], tables, IMPROVED_REND_ENTRY)
    assert found.reason is UnresolvedReason.SPELL_MISMATCH


def test_a_class_with_an_unresolved_icon_publishes_no_document(
    canonical_docs, visual_tables
) -> None:
    tables = with_spell_misc(visual_tables, 12286, ())
    doc = canonical_docs["warrior"]
    with pytest.raises(UnresolvedIconsError) as caught:
        build_visuals_document(doc, resolve_document(doc, tables), source_name="fixture")
    assert caught.value.class_token == "WARRIOR"
    assert [r.entry_id for r in caught.value.unresolved] == [IMPROVED_REND_ENTRY]
    assert caught.value.unresolved[0].reason is UnresolvedReason.SPELL_MISC_MISSING
