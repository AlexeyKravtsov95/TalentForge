"""The tooltip DB2 tables parse from the exact 1.60.1.70009 column sets."""

from __future__ import annotations

import pytest

from talent_data.db2 import tables as t
from talent_data.sources.base import SourceError
from tests.helpers import NEW_BUILD
from tests.unit.tooltips.conftest import tooltip_source

# Column sets observed in the wago.tools exports of 1.60.1.70009 (the fixtures are verbatim
# subsets). A changed export shape must be looked at, not silently absorbed.
OBSERVED_COLUMNS = {
    "SpellEffect": (
        "ID,EffectAura,DifficultyID,EffectIndex,Effect,EffectAmplitude,EffectAttributes,"
        "EffectAuraPeriod,EffectBonusCoefficient,EffectChainAmplitude,EffectChainTargets,"
        "EffectItemType,EffectMechanic,EffectPointsPerResource,EffectPos_facing,"
        "EffectRealPointsPerLevel,EffectTriggerSpell,BonusCoefficientFromAP,PvpMultiplier,"
        "Coefficient,Variance,ResourceCoefficient,GroupSizeBasePointsCoefficient,"
        "EffectBasePointsF,ScalingClass,Node__Field_12_0_0_63534_001,EffectMiscValue_0,"
        "EffectMiscValue_1,EffectRadiusIndex_0,EffectRadiusIndex_1,EffectSpellClassMask_0,"
        "EffectSpellClassMask_1,EffectSpellClassMask_2,EffectSpellClassMask_3,"
        "ImplicitTarget_0,ImplicitTarget_1,SpellID"
    ),
    "SpellMisc": (
        "ID,Attributes_0,Attributes_1,Attributes_2,Attributes_3,Attributes_4,Attributes_5,"
        "Attributes_6,Attributes_7,Attributes_8,Attributes_9,Attributes_10,Attributes_11,"
        "Attributes_12,Attributes_13,Attributes_14,Attributes_15,Attributes_16,DifficultyID,"
        "CastingTimeIndex,DurationIndex,PvPDurationIndex,RangeIndex,SchoolMask,Speed,"
        "LaunchDelay,MinDuration,SpellIconFileDataID,ActiveIconFileDataID,ContentTuningID,"
        "ShowFutureSpellPlayerConditionID,SpellVisualScript,ActiveSpellVisualScript,SpellID"
    ),
    "SpellDuration": "ID,Duration,MaxDuration,DurationPerResource",
    "SpellRadius": "ID,Radius,RadiusPerLevel,RadiusMin,RadiusMax",
    "SpellAuraOptions": (
        "ID,DifficultyID,CumulativeAura,ProcCategoryRecovery,ProcChance,ProcCharges,"
        "SpellProcsPerMinuteID,ProcTypeMask_0,ProcTypeMask_1,SpellID"
    ),
    "SpellXDescriptionVariables": "ID,SpellID,SpellDescriptionVariablesID",
    "SpellDescriptionVariables": "ID,Variables",
    "GlobalStrings": "ID,BaseTag,TagText_lang,Flags",
    "Spell": "ID,NameSubtext_lang,Description_lang,AuraDescription_lang",
}

RECORDS = {
    "SpellEffect": t.SpellEffectValues,
    "SpellMisc": t.SpellMiscDuration,
    "SpellDuration": t.SpellDuration,
    "SpellRadius": t.SpellRadius,
    "SpellAuraOptions": t.SpellAuraOptions,
    "SpellXDescriptionVariables": t.SpellXDescriptionVariables,
    "SpellDescriptionVariables": t.SpellDescriptionVariables,
    "GlobalStrings": t.GlobalString,
    "Spell": t.Spell,
}


def test_tooltip_tables_are_presentation_only() -> None:
    assert set(OBSERVED_COLUMNS) == set(t.TOOLTIP_TABLES)
    # Allocation normalization must never depend on the new presentation tables.
    for name in ("SpellDuration", "SpellRadius", "SpellAuraOptions", "GlobalStrings"):
        assert name not in t.TRAIT_TABLES


@pytest.mark.parametrize("table", sorted(OBSERVED_COLUMNS))
def test_every_tooltip_table_parses_from_its_exact_columns(table: str) -> None:
    db2 = tooltip_source().load_table(NEW_BUILD, table)
    assert ",".join(db2.columns) == OBSERVED_COLUMNS[table]
    records = t.parse_table(db2, RECORDS[table].parse)
    assert records, f"{table}: fixture has rows"


def test_typed_rows_read_the_documented_columns() -> None:
    source = tooltip_source()
    effects = t.parse_table(source.load_table(NEW_BUILD, "SpellEffect"), t.SpellEffectValues.parse)
    siphon = next(e for e in effects if e.spell_id == 18265 and e.effect_index == 0)
    assert (siphon.base_points, siphon.aura_period, siphon.effect_aura) == ("11", 3000, 53)
    assert siphon.bonus_coefficient == pytest.approx(0.05)
    durations = t.parse_table(source.load_table(NEW_BUILD, "SpellDuration"), t.SpellDuration.parse)
    assert t.SpellDuration(id=21, duration=-1, max_duration=-1, duration_per_resource=0) in (
        durations
    )
    options = t.parse_table(
        source.load_table(NEW_BUILD, "SpellAuraOptions"), t.SpellAuraOptions.parse
    )
    redoubt = next(o for o in options if o.spell_id == 20128)
    assert (redoubt.proc_charges, redoubt.proc_chance) == (5, 100)


def test_a_missing_column_fails_loudly() -> None:
    row = {"ID": "1", "Duration": "1000", "MaxDuration": "1000"}
    with pytest.raises(SourceError, match="DurationPerResource"):
        t.SpellDuration.parse(row)
