"""SpellMisc typed layer: the exact 1.60.1.70009 column shape, nothing assumed."""

from __future__ import annotations

import pytest

from talent_data.db2 import tables as t
from talent_data.sources.base import SourceError
from talent_data.sources.local import LocalCsvDb2Source
from tests.conftest import ALL_CLASSES, fixture_source_for
from tests.helpers import NEW_BUILD, OLD_BUILD

# The header of the real wago.tools export of SpellMisc for build 1.60.1.70009.
SPELL_MISC_COLUMNS = (
    "ID",
    *(f"Attributes_{i}" for i in range(17)),
    "DifficultyID",
    "CastingTimeIndex",
    "DurationIndex",
    "PvPDurationIndex",
    "RangeIndex",
    "SchoolMask",
    "Speed",
    "LaunchDelay",
    "MinDuration",
    "SpellIconFileDataID",
    "ActiveIconFileDataID",
    "ContentTuningID",
    "ShowFutureSpellPlayerConditionID",
    "SpellVisualScript",
    "ActiveSpellVisualScript",
    "SpellID",
)


@pytest.mark.parametrize("build", [OLD_BUILD, NEW_BUILD], ids=str)
@pytest.mark.parametrize("class_key", ALL_CLASSES)
def test_real_export_has_the_documented_columns(class_key: str, build) -> None:
    table = fixture_source_for(class_key, build).load_table(build, "SpellMisc")
    assert table.columns == SPELL_MISC_COLUMNS
    assert len(table) > 0


def test_parser_reads_exactly_the_icon_columns() -> None:
    table = fixture_source_for("warrior").load_table(NEW_BUILD, "SpellMisc")
    rows = {r.spell_id: r for r in t.parse_table(table, t.SpellMisc.parse)}
    # Improved Rend (spell 12286): real row of the build.
    rend = rows[12286]
    assert rend == t.SpellMisc(
        id=rend.id,
        spell_id=12286,
        difficulty_id=0,
        spell_icon_file_data_id=132155,
        active_icon_file_data_id=0,
    )


@pytest.mark.parametrize(
    "column", ["ID", "SpellID", "DifficultyID", "SpellIconFileDataID", "ActiveIconFileDataID"]
)
def test_parser_fails_loudly_without_a_required_column(column: str) -> None:
    table = fixture_source_for("warrior").load_table(NEW_BUILD, "SpellMisc")
    row = {k: v for k, v in table.rows[0].items() if k != column}
    with pytest.raises(SourceError, match=column):
        t.SpellMisc.parse(row)


def test_parser_rejects_a_non_integer_icon() -> None:
    table = fixture_source_for("warrior").load_table(NEW_BUILD, "SpellMisc")
    row = {**table.rows[0], "SpellIconFileDataID": "interface/icons/x.blp"}
    with pytest.raises(SourceError, match="not an integer"):
        t.SpellMisc.parse(row)


def test_spell_misc_is_not_a_core_trait_table() -> None:
    """Allocation normalization must not depend on visual metadata being available."""
    assert "SpellMisc" not in t.TRAIT_TABLES
    assert t.VISUAL_TABLES == ("SpellMisc",)


def test_trait_definition_exposes_the_icon_override(merged_source: LocalCsvDb2Source) -> None:
    table = merged_source.load_table(NEW_BUILD, "TraitDefinition")
    definitions = t.parse_table(table, t.TraitDefinition.parse)
    # Real fact of the nine bundled trees in this build: no definition overrides its icon
    # and none redirects presentation to another spell.
    assert {d.override_icon for d in definitions} == {0}
    assert {d.visible_spell_id for d in definitions} == {0}
