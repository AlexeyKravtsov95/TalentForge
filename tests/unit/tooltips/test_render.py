"""Rendering real Forever 1.60.1.70009 talents from the real DB2 rows (fixture closure)."""

from __future__ import annotations

from dataclasses import replace

import pytest

from talent_data.models.talents import TalentEntry
from talent_data.models.tooltips import MARKUP_CHARACTERS, rank_text
from talent_data.tooltips.render import TalentRenderer
from talent_data.tooltips.spells import SpellLookup

W, P, H, R, PR, S, M, WL, D = (
    "WARRIOR",
    "PALADIN",
    "HUNTER",
    "ROGUE",
    "PRIEST",
    "SHAMAN",
    "MAGE",
    "WARLOCK",
    "DRUID",
)


def with_template(entry: TalentEntry, template: str) -> TalentEntry:
    return entry.model_copy(update={"description_template": template})


# --------------------------------------------------------------------------- effect values


def test_local_values_follow_the_rank_curve(talent) -> None:
    rend = talent(W, "Improved Rend")
    assert rend.texts() == [
        f"Increases the Bleed damage done by your Rend ability by {v}%." for v in (12, 23, 35)
    ]
    # the per-rank value comes from talent-data.v1, not from SpellEffect base points (15)
    assert rend.entry.effects[0].base_points == 15
    assert talent(W, "Deflection").values(4) == ["4%"]


def test_effect_n_is_effect_index_n_minus_one(talent) -> None:
    # Weaponmaster: $s1/$s2/$s3 are EffectIndex 0/1/2 (1/3/1 per rank).
    assert talent(W, "Weaponmaster").values(5) == ["5%", "15%", "5%"]
    # Improved Slam: $m3 is EffectIndex 2 (-1500/-3000 ms); EffectIndex 3 holds a spell id
    # (1310196), so a one-based reading would print nonsense.
    slam = talent(W, "Improved Slam")
    assert slam.values(1) == ["0.25", "1.5"]
    assert slam.values(2) == ["0.50", "3.0"]
    # Anger Management: $m2/$m3/$s4 -> EffectIndex 1/2/3; EffectIndex 4 does not exist.
    assert talent(W, "Anger Management").text() == (
        "Generates 1 Rage every 3 sec while in combat, and reduces Rage loss while out of "
        "combat by 30%."
    )


def test_static_effects_use_the_spell_base_points(talent) -> None:
    assert talent(W, "Mortal Strike").text() == (
        "A vicious strike that deals weapon damage plus 85 and wounds the target, reducing "
        "the effectiveness of any healing by 50% for 10 sec."
    )


def test_stand_alone_values_are_written_without_sign(talent) -> None:
    # EffectBasePoints -3/-6/... ("Reduces ... by $s1%")
    assert talent(W, "Iron Will").values(2) == ["6%"]
    assert talent(H, "Survivalist's Discipline").values(2) == ["40%"]  # bare $m


def test_cross_spell_values_read_the_referenced_spell(talent) -> None:
    assert talent(W, "Last Stand").text() == (
        "When activated, this ability temporarily grants you 30% of your maximum health for "
        "20 sec. After the effect expires, the health is lost."
    )
    assert talent(W, "Unbridled Wrath").values(1) == ["12%", "1", "2"]


def test_a_missing_referenced_spell_is_missing_data(talent, spells: SpellLookup) -> None:
    entry = talent(W, "Last Stand").entry
    renderer = TalentRenderer(with_template(entry, "Grants $999999999s1%."), spells)
    rank = renderer.render(1).rank
    assert rank.status == "partial"
    assert rank.gaps[0].classification == "missingData"
    assert "does not exist in this build" in rank.gaps[0].reason


def test_an_effect_without_a_spell_effect_row_is_a_gap(talent) -> None:
    # Resourcefulness has a rank curve for effect 2 but the spell has no EffectIndex 2 row.
    rank = talent(H, "Resourcefulness").rank(2)
    assert rank.status == "partial"
    assert [(g.token, g.classification) for g in rank.gaps] == [("$m3", "missingData")]


# --------------------------------------------------------------------------- durations


def test_local_and_cross_spell_durations(talent) -> None:
    assert talent(W, "Mortal Strike").values()[-1] == "10 sec"
    assert talent(W, "Improved Hamstring").values(3) == ["15%", "5 sec"]
    assert talent(W, "Deep Wounds").values(1) == ["20%", "12 sec"]
    assert talent(W, "Bloodthrill").values(5) == ["20%", "6 sec"]
    assert talent(M, "Improved Blizzard").values(1) == ["15%", "1.5 sec"]
    assert talent(H, "Trueshot Aura").values() == ["45", "30", "30 min"]
    assert talent(WL, "Demonic Sacrifice").values()[0] == "2 hrs"


def test_a_spell_without_spell_misc_has_no_duration(talent) -> None:
    rank = talent(R, "Riposte").rank(1)
    assert rank.status == "partial"
    gap = rank.gaps[0]
    assert (gap.category, gap.classification, gap.token) == (
        "cross-spell-duration",
        "missingData",
        "$19718d",
    )


# --------------------------------------------------------------------------- radius, period, procs


def test_radius(talent) -> None:
    assert talent(H, "Trueshot Aura").values()[0] == "45"  # SpellRadius 11
    assert talent(D, "Leader of the Pack").values()[0] == "45"  # cross-spell $24932a1
    holy_nova = talent(PR, "Holy Nova").rank(1)
    radius_gaps = [g for g in holy_nova.gaps if g.category == "local-radius"]
    # EffectRadiusIndex [0, 13]: which index $a1 reads is not proven -> a gap, not 10 yards
    assert [g.classification for g in radius_gaps] == ["unsupported"]
    assert "10 yards" in rank_text(holy_nova)  # $23455a1 reads EffectRadiusIndex_0 = 13


def test_period(talent) -> None:
    assert "every 3 sec" in talent(WL, "Siphon Life").text()  # $t1 = 3000 ms
    assert "every 1 sec for 2 sec" in talent(PR, "Penance").text()  # $402261t2
    assert "every 1 sec" in talent(WL, "Wrack").text()  # bare $t, one periodic effect


def test_proc_chance_charges_and_stacks(talent) -> None:
    assert talent(W, "Sweeping Strikes").values() == ["5"]  # ProcCharges
    assert talent(W, "Enrage").values(1) == ["30%", "2%", "12 sec"]  # ProcChance
    assert talent(P, "Redoubt").values(1) == ["10%", "6%", "10 sec", "5"]
    assert talent(P, "Vengeance").values(1)[-1] == "3"  # CumulativeAura of 20050
    assert talent(PR, "Shadow Weaving").values(1)[-1] == "5"


# --------------------------------------------------------------------------- scaled / arithmetic


def test_scaled_values(talent) -> None:
    assert talent(W, "Improved Heroic Strike").values(3) == ["3"]  # $/10;s1 of -30
    assert [talent(M, "Improved Fireball").values(r)[0] for r in range(1, 6)] == [
        "0.1",
        "0.2",
        "0.3",
        "0.4",
        "0.5",
    ]  # $/1000;S1 (upper case S)
    assert talent(WL, "Fel Domination").values()[0] == "5.5"


def test_arithmetic(talent) -> None:
    assert talent(W, "Spearing Strike").values() == ["40%", "80%"]  # ${$s2*$s3}
    assert talent(W, "Unbridled Wrath").values(5)[1:] == ["1", "2"]  # ${($12964m1/10)*2}
    assert talent(PR, "Improved Mind Flay").values(1)[-1] == "35%"  # ${$s4-($m3)}
    assert talent(PR, "Improved Mind Flay").values(2)[-1] == "20%"


def test_precision_suffix_prints_exactly_n_decimals(talent) -> None:
    assert [talent(D, "Thick Hide").values(r)[1] for r in (1, 2, 3)] == ["0.67", "1.33", "2.00"]
    assert talent(W, "Improved Shield Wall").values(2) == ["11.0"]


def test_non_integral_result_without_precision_is_a_gap(talent) -> None:
    rank = talent(R, "Mutilate").rank(1)
    assert [(g.category, g.classification) for g in rank.gaps] == [("arithmetic", "unsupported")]
    assert "17.25" in rank.gaps[0].reason


# --------------------------------------------------------------------------- plural / layout


def test_plural_binds_to_the_preceding_number(talent) -> None:
    expose = talent(R, "Improved Expose Armor")
    assert "refunds 1 Combo Point when" in expose.text(1)
    assert "refunds 2 Combo Points when" in expose.text(2)
    assert "as if you were 1 level higher" in talent(R, "Master of Deception").text(1)
    assert "as if you were 3 levels higher" in talent(R, "Master of Deception").text(3)
    assert "next 1 spell cast" in talent(M, "Fingers of Frost").text(1)
    assert "Awards 1 combo point." in talent(R, "Ghostly Strike").text()


def test_line_breaks_are_kept_as_newlines(talent) -> None:
    text = talent(W, "Weaponmaster").text(1)
    assert "\r" not in text
    assert text.startswith("Gives your melee weapon attacks a benefit depending on the weapon.\n\n")
    assert text.count("\n\n") == 3
    assert not text.endswith("\n")


def test_colour_markup_is_dropped(talent) -> None:
    text = talent(W, "Weaponmaster").text(1)
    assert "|" not in text
    assert "\nAxe/Polearm: Increases your\n" in text
    assert "\nMace/Staff: Your attacks ignore\n" in text


# --------------------------------------------------------------------------- nested / conditional


def test_nested_tooltip_is_inlined_from_the_same_build(talent) -> None:
    text = talent(D, "Feral Charge").text()
    assert text.endswith("for 4 sec.\n\nLeap behind an enemy.")


def test_nested_tooltip_cycle_fails_closed(talent, spells: SpellLookup) -> None:
    entry = talent(D, "Feral Charge").entry
    looped = replace(
        spells,
        descriptions={
            **spells.descriptions,
            414924: "Leap. $@spelltooltip1238122",  # 1238122 is Feral Charge itself
        },
    )
    rank = TalentRenderer(entry, looped).render(1).rank
    assert rank.status == "partial"
    assert [(g.category, g.classification) for g in rank.gaps] == [
        ("nested-tooltip", "unsupported")
    ]
    assert "cycle 1238122 -> 414924 -> 1238122" in rank.gaps[0].reason


def test_nested_tooltip_depth_is_bounded(talent, spells: SpellLookup) -> None:
    entry = talent(D, "Feral Charge").entry
    chain = {414924: "$@spelltooltip900001"} | {
        900000 + i: f"$@spelltooltip{900001 + i}" for i in range(1, 10)
    }
    deep = replace(spells, descriptions={**spells.descriptions, **chain})
    rank = TalentRenderer(entry, deep).render(1).rank
    assert rank.gaps[0].category == "nested-tooltip"
    assert "nesting deeper" in rank.gaps[0].reason


def test_conditional_with_identical_branches_needs_no_choice(talent) -> None:
    # Feral Charge: both branches read "Requires Bear Form, Dire Bear Form" and differ only
    # in colour (white / red), so the text is the same whatever the character's form.
    rank = talent(D, "Feral Charge").rank(1)
    assert rank.status == "resolved"
    assert rank_text(rank).startswith("Requires Bear Form, Dire Bear Form\nCharge an enemy")


def test_conditional_with_different_branches_never_picks_one(talent) -> None:
    rank = talent(D, "Berserk").rank(1)
    assert rank.status == "partial"
    gap = rank.gaps[0]
    assert (gap.category, gap.classification) == ("conditional", "contextDependent")
    text = rank_text(rank)
    assert "Reuires" not in text and "Requires" not in text
    assert text.lstrip().startswith("Causes your Primal Bite ability to strike up to 3 targets")


# --------------------------------------------------------------------------- variables / failures


def test_named_variables_are_explicit_gaps(talent) -> None:
    hawk = talent(H, "Summon Hawk").rank(1)
    assert [(g.category, g.token, g.classification) for g in hawk.gaps] == [
        ("named-variable", "$rap", "unsupported")
    ]
    shield = talent(P, "Shield Specialization").rank(3)
    gap = shield.gaps[0]
    assert (gap.category, gap.token) == ("named-variable", "$proccooldown")
    assert "ProcCategoryRecovery" in gap.reason and "not proven" in gap.reason


def test_description_variables_built_from_character_state(talent) -> None:
    rank = talent(WL, "Demonic Brand").rank(1)
    assert [(g.token, g.classification) for g in rank.gaps] == [
        ("$<minDam>", "contextDependent"),
        ("$<maxDam>", "contextDependent"),
    ]
    assert "SpellDescriptionVariables 1016" in rank.gaps[0].reason


def test_caster_scaled_amounts_are_context_dependent(talent) -> None:
    pyroblast = talent(M, "Pyroblast").rank(1)
    assert {g.classification for g in pyroblast.gaps} == {"contextDependent"}
    assert "EffectRealPointsPerLevel" in pyroblast.gaps[0].reason  # $s1
    assert "spell power" in pyroblast.gaps[1].reason  # $o2


def test_unknown_syntax_makes_the_rank_unresolved(talent, spells: SpellLookup) -> None:
    entry = with_template(talent(W, "Deflection").entry, "Increases parry by $s1% $!x.")
    rank = TalentRenderer(entry, spells).render(1).rank
    assert rank.status == "unresolved"
    assert rank.segments == []
    assert (rank.gaps[0].category, rank.gaps[0].classification) == ("unclassified", "unsupported")


def test_unknown_reference_letter_is_unknown_syntax(talent, spells: SpellLookup) -> None:
    entry = with_template(talent(W, "Deflection").entry, "Value $q1.")
    assert TalentRenderer(entry, spells).render(1).rank.status == "unresolved"


def test_rank_outside_the_entry_is_refused(talent) -> None:
    with pytest.raises(ValueError, match=r"outside 1\.\.5"):
        talent(W, "Deflection").renderer.render(6)


# --------------------------------------------------------------------------- invariants


def test_no_rendered_segment_contains_template_markup(entries, spells: SpellLookup) -> None:
    ranks = 0
    for _, entry in entries.values():
        renderer = TalentRenderer(entry, spells)
        for rank_number in range(1, entry.max_rank + 1):
            rank = renderer.render(rank_number).rank
            ranks += 1
            for segment in rank.segments:
                assert not any(c in segment.text for c in MARKUP_CHARACTERS), (entry.id, segment)
            if rank.status == "resolved":
                assert rank.gaps == []
                assert rank_text(rank).strip() == rank_text(rank)
    assert ranks == 1333
