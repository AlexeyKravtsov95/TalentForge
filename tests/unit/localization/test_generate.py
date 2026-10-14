"""Overlay generation: identity mapping, EN/RU separation, every description gate, numeric
preservation and fail-closed integrity - on the real enUS/ruRU rows of 1.60.1.70338."""

# Russian client text is the subject of these tests, not a confusable-character accident.
# ruff: noqa: RUF001, RUF003

from __future__ import annotations

import re
from collections import Counter
from dataclasses import replace

import pytest

from talent_data.localization.generate import (
    LocalizationInputs,
    localize_document,
    value_signature,
)
from talent_data.localization.pipeline import BuildLocalization
from talent_data.models.localization import TalentLocalizationDocument, rank_text
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.models.tooltips import rank_text as english_text
from talent_data.pipeline import PipelineError
from talent_data.tooltips.grammar import parse
from talent_data.versions import ClientVersion
from tests.helpers import PRODUCT_BUILD
from tests.unit.localization.conftest import SOURCE_NAME, entry_named, with_russian

WARRIOR = "WARRIOR"
CYRILLIC = re.compile("[А-Яа-яЁё]")
NUMBER = re.compile(r"\d+(?:\.\d+)?")


def overlay_entry(overlay: TalentLocalizationDocument, entry_id: int):
    (entry,) = [e for e in overlay.entries if e.entry_id == entry_id]
    return entry


def regenerate(
    token: str,
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> TalentLocalizationDocument:
    return localize_document(documents[token], tooltips[token], inputs, source_name=SOURCE_NAME)


# --------------------------------------------------------------------------- names


def test_russian_names_are_mapped_to_the_real_talent_identities(
    build: BuildLocalization, documents: dict[str, TalentDataDocument]
) -> None:
    overlay = build.overlays[WARRIOR]
    talents = documents[WARRIOR]
    by_id = {e.id: (n.id, e) for t in talents.trees for n in t.nodes for e in n.entries}
    assert [e.entry_id for e in overlay.entries] == sorted(by_id)
    for entry in overlay.entries:
        node_id, talent = by_id[entry.entry_id]
        assert (entry.node_id, entry.definition_id, entry.spell_id, entry.max_rank) == (
            node_id,
            talent.definition_id,
            talent.spell_id,
            talent.max_rank,
        )
    heroic = overlay_entry(overlay, 130688)
    assert (heroic.node_id, heroic.text_spell_id) == (105958, 12282)
    assert (heroic.name.status, heroic.name.text) == ("translated", "Улучшенный удар героя")
    assert overlay_entry(overlay, 141192).name.text == "Железная воля"  # Iron Will, moved node


def test_english_content_stays_english_and_separate(
    build: BuildLocalization, documents: dict[str, TalentDataDocument]
) -> None:
    """The dataset keeps its English names; the overlay never carries English text."""
    assert entry_named(documents[WARRIOR], "Improved Heroic Strike")[0].id == 130688
    for overlay in build.overlays.values():
        for entry in overlay.entries:
            if entry.name.text is not None:
                assert CYRILLIC.search(entry.name.text)
            for rank in entry.description.ranks:
                assert CYRILLIC.search(rank_text(rank))


def test_forever_only_talents_have_no_russian_name_and_fall_back(build: BuildLocalization) -> None:
    untranslated = {
        (o.class_token, e.entry_id)
        for o in build.overlays.values()
        for e in o.entries
        if e.name.status != "translated"
    }
    # The new/renamed Forever talents: the ruRU export serves their English strings.
    assert untranslated == {
        ("WARRIOR", 130683),  # Furious Precision
        ("WARRIOR", 137849),  # Lingering Rage
        ("WARRIOR", 141191),  # Gore Drinker
        ("MAGE", 130515),  # Heating Up
        ("WARLOCK", 130652),  # Soul Harvest
        ("DRUID", 129607),  # Blood Frenzy
        ("DRUID", 129609),  # Primal Bite
        ("DRUID", 129610),  # Natural Instinct
        ("DRUID", 129611),  # Shifting Power
        ("DRUID", 141188),  # Improved Shifting Power
    }
    gore = overlay_entry(build.overlays[WARRIOR], 141191)
    assert (gore.name.status, gore.name.text) == ("untranslated", None)


@pytest.mark.parametrize(
    ("token", "name"), [("HUNTER", "Lightning Reflexes"), ("PRIEST", "Holy Specialization")]
)
def test_entries_sharing_a_spell_keep_separate_identities(
    build: BuildLocalization, documents: dict[str, TalentDataDocument], token: str, name: str
) -> None:
    """DUPLICATE_SPELL_IDENTITY: two nodes, one spell - two overlay entries keyed by entry id,
    never one entry chosen by spell id."""
    talents = entry_named(documents[token], name)
    assert len(talents) == 2
    assert len({t.spell_id for t in talents}) == 1
    entries = [overlay_entry(build.overlays[token], t.id) for t in talents]
    assert len({e.node_id for e in entries}) == 2
    assert entries[0].name == entries[1].name
    assert entries[0].name.status == "translated"


def test_a_missing_russian_row_or_empty_string_is_missing(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    removed = regenerate(WARRIOR, with_russian(inputs, names={12282: None}), documents, tooltips)
    assert overlay_entry(removed, 130688).name.status == "missing"
    empty = regenerate(WARRIOR, with_russian(inputs, names={12282: ""}), documents, tooltips)
    name = overlay_entry(empty, 130688).name
    assert (name.status, name.text) == ("missing", None)


def test_text_that_is_neither_english_nor_russian_stops_generation(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    patched = with_russian(inputs, names={12282: "Verbesserter Heldenstoß"})
    with pytest.raises(PipelineError, match="contains no Cyrillic"):
        regenerate(WARRIOR, patched, documents, tooltips)


# --------------------------------------------------------------------------- descriptions


def test_rendered_descriptions_are_the_client_text_with_the_build_numbers(
    build: BuildLocalization,
) -> None:
    heroic = overlay_entry(build.overlays[WARRIOR], 130688)
    assert heroic.description.status == "rendered"
    assert [rank_text(r) for r in heroic.description.ranks] == [
        'Снижает затраты ярости на "Удар героя" на 1 ед.',
        'Снижает затраты ярости на "Удар героя" на 2 ед.',
        'Снижает затраты ярости на "Удар героя" на 3 ед.',
    ]
    # Russian word order is the template's own: the value moves, nothing is spliced in
    mortal = overlay_entry(build.overlays[WARRIOR], 130671)
    assert rank_text(mortal.description.ranks[0]).startswith("Жестокий удар, наносящий урон")
    # duration units come from the ruRU GlobalStrings of the same build
    deep = overlay_entry(build.overlays[WARRIOR], 130680)
    assert "за 12 сек." in rank_text(deep.description.ranks[-1])


def test_every_rendered_rank_shows_exactly_the_english_numbers(
    build: BuildLocalization, tooltips: dict[str, TalentTooltipsDocument]
) -> None:
    checked = 0
    for token, overlay in build.overlays.items():
        english = {e.entry_id: e for e in tooltips[token].entries}
        for entry in overlay.entries:
            for mine in entry.description.ranks:
                theirs = english[entry.entry_id].ranks[mine.rank - 1]
                ours = Counter(
                    n for s in mine.segments if s.kind == "value" for n in NUMBER.findall(s.text)
                )
                reference = Counter(
                    n for s in theirs.segments if s.kind == "value" for n in NUMBER.findall(s.text)
                )
                assert ours == reference, (token, entry.entry_id, mine.rank)
                checked += 1
    assert checked == 1058


@pytest.mark.parametrize(
    ("entry_id", "status"),
    [
        (130663, "untranslated"),  # Dual Wield Specialization: ruRU serves the new English text
        (130660, "sourceNotResolved"),  # Bloodthirst: English is partial (attack power)
        (130658, "pluralRuleUnverified"),  # Flurry: "$lудар:удара:ударов;"
        (137850, "decimalFormatUnverified"),  # Improved Slam: "0.5"
        (130694, "decimalFormatUnverified"),  # Improved Shield Wall
    ],
)
def test_real_descriptions_that_fall_back_to_english(
    build: BuildLocalization, entry_id: int, status: str
) -> None:
    entry = overlay_entry(build.overlays[WARRIOR], entry_id)
    assert entry.description.status == status
    assert entry.description.ranks == []


def test_a_missing_template_is_missing(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    patched = with_russian(inputs, descriptions={12282: None})
    overlay = regenerate(WARRIOR, patched, documents, tooltips)
    assert overlay_entry(overlay, 130688).description.status == "missing"


@pytest.mark.parametrize(
    "template",
    [
        'Снижает затраты ярости на "Удар героя" на $/10;s2 ед.',  # another effect
        'Снижает затраты ярости на "Удар героя".',  # the value dropped
        "Снижает затраты ярости на $/10;s1 и еще $/10;s1 ед.",  # the value doubled
        "Снижает затраты ярости $unknown",  # syntax outside the grammar
    ],
)
def test_a_template_whose_value_constructs_differ_is_a_structure_mismatch(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
    template: str,
) -> None:
    patched = with_russian(inputs, descriptions={12282: template})
    overlay = regenerate(WARRIOR, patched, documents, tooltips)
    assert overlay_entry(overlay, 130688).description.status == "structureMismatch"


def test_numbers_that_change_in_rendering_are_a_structure_mismatch(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    """Same constructs, different numbers shown (a duration unit containing a digit)."""
    formats = inputs.localized.duration_formats
    broken = replace(formats, seconds=replace(formats.seconds, unit="0 сек"))
    patched = replace(inputs, localized=replace(inputs.localized, duration_formats=broken))
    overlay = regenerate(WARRIOR, patched, documents, tooltips)
    assert overlay_entry(overlay, 130680).description.status == "structureMismatch"  # Deep Wounds
    assert overlay_entry(overlay, 130688).description.status == "rendered"  # no duration


def test_a_client_condition_whose_russian_branches_differ_is_a_render_gap(
    build: BuildLocalization,
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    """Feral Charge: identical English branches (colour only) resolve; Russian branches that
    differ would need the character's form - never chosen."""
    (feral,) = entry_named(documents["DRUID"], "Feral Charge")
    assert overlay_entry(build.overlays["DRUID"], feral.id).description.status == "rendered"
    template = inputs.localized.descriptions[1238122].replace(
        "[|CFFFF2020Требуется облик медведя", "[|CFFFF2020Требуется облик кошки", 1
    )
    patched = with_russian(inputs, descriptions={1238122: template})
    overlay = regenerate("DRUID", patched, documents, tooltips)
    assert overlay_entry(overlay, feral.id).description.status == "renderGap"


def test_an_untranslated_nested_tooltip_is_never_mixed_into_russian_text(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    (feral,) = entry_named(documents["DRUID"], "Feral Charge")
    english_nested = inputs.english.descriptions[414924]
    mixed = regenerate(
        "DRUID", with_russian(inputs, descriptions={414924: english_nested}), documents, tooltips
    )
    assert overlay_entry(mixed, feral.id).description.status == "untranslated"
    gone = regenerate(
        "DRUID", with_russian(inputs, descriptions={414924: None}), documents, tooltips
    )
    assert overlay_entry(gone, feral.id).description.status == "missing"


def test_value_signature_ignores_language_but_not_values() -> None:
    english = parse("Increases your melee attack speed by $m1% for your next $12966n swings.")
    russian = parse(
        "Скорость атаки повышается на $m1%. Действует, пока вы не нанесете $12966n удара."
    )
    assert value_signature(english) == value_signature(russian)
    assert value_signature(parse("на $m2%")) != value_signature(parse("на $m1%"))


# --------------------------------------------------------------------------- integrity


def test_tables_of_another_build_are_refused(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    english = replace(inputs.english, names={**inputs.english.names, 12282: "Heroic Strike II"})
    with pytest.raises(PipelineError, match="not the same build"):
        regenerate(WARRIOR, replace(inputs, english=english), documents, tooltips)
    other = replace(inputs, version=ClientVersion.parse("1.60.1.70334"))
    with pytest.raises(PipelineError, match="with tables of"):
        regenerate(WARRIOR, other, documents, tooltips)


def test_english_tooltips_of_another_class_are_refused(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    with pytest.raises(PipelineError, match="English tooltips do not match"):
        localize_document(documents[WARRIOR], tooltips["MAGE"], inputs, source_name=SOURCE_NAME)


def test_generation_is_deterministic(
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    first = regenerate(WARRIOR, inputs, documents, tooltips)
    second = regenerate(WARRIOR, inputs, documents, tooltips)
    assert first.to_json() == second.to_json()


def test_overlays_are_linked_to_the_exact_dataset_and_tooltip_revisions(
    build: BuildLocalization,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    for token, overlay in build.overlays.items():
        assert (overlay.game, overlay.client_build, overlay.locale) == (
            "forever",
            str(PRODUCT_BUILD),
            "ruRU",
        )
        assert overlay.talent_data_content_hash == documents[token].content_hash
        assert overlay.tooltips_content_hash == tooltips[token].content_hash


def test_english_tooltip_text_is_not_changed_by_localization(
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    (heroic,) = [e for e in tooltips[WARRIOR].entries if e.entry_id == 130688]
    assert english_text(heroic.ranks[0]) == (
        "Reduces the cost of your Heroic Strike ability by 1 Rage."
    )


def _changed_values(a: list[tuple[str, str]], b: list[tuple[str, str]]) -> list[str] | None:
    """The inspector's rank comparison (TalentInspectorDescription.changedValueIndices): the
    values of ``a`` that differ in ``b``, or ``None`` when the two ranks are not comparable."""
    if len(a) != len(b) or any(x[0] != y[0] for x, y in zip(a, b, strict=True)):
        return None
    if any(x[0] == "text" and x[1] != y[1] for x, y in zip(a, b, strict=True)):
        return None
    return [x[1] for x, y in zip(a, b, strict=True) if x[0] == "value" and x[1] != y[1]]


def test_rank_comparison_behaves_as_in_english(
    build: BuildLocalization, tooltips: dict[str, TalentTooltipsDocument]
) -> None:
    """Wherever English ranks compare, the Russian ranks compare too, and the changed values
    carry the same numbers - only their order follows Russian word order (e.g. Priest Early
    Demise names the 15 % before the 20 % health threshold)."""
    pairs = 0
    for token, overlay in build.overlays.items():
        english = {e.entry_id: e for e in tooltips[token].entries}
        for entry in overlay.entries:
            if entry.description.status != "rendered":
                continue
            ours = [[(s.kind, s.text) for s in r.segments] for r in entry.description.ranks]
            theirs = [[(s.kind, s.text) for s in r.segments] for r in english[entry.entry_id].ranks]
            for rank in range(len(ours) - 1):
                pairs += 1
                mine = _changed_values(ours[rank], ours[rank + 1])
                reference = _changed_values(theirs[rank], theirs[rank + 1])
                assert (mine is None) == (reference is None), (token, entry.entry_id, rank + 1)
                if mine is not None and reference is not None:
                    numbers = Counter(n for v in mine for n in NUMBER.findall(v))
                    assert numbers == Counter(n for v in reference for n in NUMBER.findall(v))
    assert pairs > 600  # every rendered multi-rank entry of the build was compared
