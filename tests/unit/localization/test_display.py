"""English fallback: the reference consumer rule of talent-localization.v1, on the committed
Warrior pilot and on overlays that must not be applied."""

from __future__ import annotations

import json
from typing import Any

import pytest

from talent_data.localization.display import resolve_class_text
from talent_data.localization.pipeline import BuildLocalization
from talent_data.models.localization import (
    TalentLocalizationDocument,
    expected_content_hash,
    rank_text,
)
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.pipeline import PipelineError
from tests.unit.localization.conftest import OVERLAYS, entry_named

IMPROVED_HEROIC_STRIKE = 130688  # translated name, rendered description
GORE_DRINKER = 141191  # Forever-only: the ruRU export serves the English name
BLOODTHIRST = 130660  # English sidecar partial -> sourceNotResolved
FLURRY = 130658  # three-form Russian plural -> pluralRuleUnverified


def committed() -> TalentLocalizationDocument:
    return TalentLocalizationDocument.model_validate_json(
        (OVERLAYS / "warrior.json").read_text(encoding="utf-8")
    )


def rehashed(raw: dict[str, Any]) -> TalentLocalizationDocument:
    document = TalentLocalizationDocument.model_validate(raw)
    return document.model_copy(update={"content_hash": expected_content_hash(document)})


@pytest.fixture
def warrior(
    documents: dict[str, TalentDataDocument], tooltips: dict[str, TalentTooltipsDocument]
) -> tuple[TalentDataDocument, TalentTooltipsDocument]:
    return documents["WARRIOR"], tooltips["WARRIOR"]


def english_names(talents: TalentDataDocument) -> dict[int, str | None]:
    return {e.id: e.name for t in talents.trees for n in t.nodes for e in n.entries}


def test_without_an_overlay_everything_is_the_english_content(warrior) -> None:
    talents, tooltips = warrior
    text = resolve_class_text(talents, tooltips, None)
    assert text.overlay_problems == ()
    assert {k: v.name for k, v in text.entries.items()} == english_names(talents)
    english = {e.entry_id: tuple(e.ranks) for e in tooltips.entries}
    for entry in text.entries.values():
        assert (entry.name_locale, entry.description_locale) == ("enUS", "enUS")
        assert entry.english_ranks == english[entry.entry_id]
        assert entry.localized_ranks is None


def test_the_pilot_shows_russian_where_proven_and_english_elsewhere(warrior) -> None:
    talents, tooltips = warrior
    text = resolve_class_text(talents, tooltips, committed())
    assert text.overlay_problems == ()
    assert set(text.entries) == set(english_names(talents))  # every entry, keyed by entry id

    heroic = text.entries[IMPROVED_HEROIC_STRIKE]
    assert (heroic.name, heroic.name_locale) == ("Улучшенный удар героя", "ruRU")
    assert heroic.description_locale == "ruRU" and heroic.english_ranks is None
    assert heroic.localized_ranks is not None
    assert [r.rank for r in heroic.localized_ranks] == [1, 2, 3]

    gore = text.entries[GORE_DRINKER]
    assert (gore.name, gore.name_locale) == ("Gore Drinker", "enUS")


@pytest.mark.parametrize("entry_id", [BLOODTHIRST, FLURRY])
def test_an_unrendered_description_is_the_english_sidecar_unchanged(warrior, entry_id) -> None:
    """Partial status, gaps and missing-value markers come from English, never mixed."""
    talents, tooltips = warrior
    entry = resolve_class_text(talents, tooltips, committed()).entries[entry_id]
    english = next(e for e in tooltips.entries if e.entry_id == entry_id)
    assert entry.name_locale == "ruRU"  # the name is still translated
    assert (entry.description_locale, entry.localized_ranks) == ("enUS", None)
    assert entry.english_ranks == tuple(english.ranks)


def test_bloodthirst_keeps_its_english_missing_value_markers(warrior) -> None:
    talents, tooltips = warrior
    entry = resolve_class_text(talents, tooltips, committed()).entries[BLOODTHIRST]
    assert entry.english_ranks is not None
    assert {rank.status for rank in entry.english_ranks} != {"resolved"}
    assert any(rank.gaps for rank in entry.english_ranks)


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda d: d.update(talentDataContentHash="sha256:" + "0" * 64), "belongs to talent-data"),
        (lambda d: d.update(tooltipsContentHash="sha256:" + "1" * 64), "checked against tooltips"),
        (lambda d: d.update(clientBuild="1.60.1.70245"), "clientBuild"),
        (lambda d: d.update(classToken="MAGE"), "MAGE"),
        (lambda d: d["entries"].append(d["entries"][0]), "duplicate entryId"),
        (lambda d: d["entries"].pop(0), "without an overlay entry"),
        (lambda d: d["entries"][0].update(spellId=1), "node/definition"),
    ],
)
def test_a_mismatched_overlay_is_not_applied_at_all(warrior, mutate, reason: str) -> None:
    talents, tooltips = warrior
    raw = json.loads(committed().to_json())
    mutate(raw)
    text = resolve_class_text(talents, tooltips, rehashed(raw))
    assert any(reason in p for p in text.overlay_problems), text.overlay_problems
    assert {k: v.name for k, v in text.entries.items()} == english_names(talents)
    assert all(e.localized_ranks is None for e in text.entries.values())


def test_a_tampered_overlay_without_a_new_hash_is_not_applied(warrior) -> None:
    talents, tooltips = warrior
    raw = json.loads(committed().to_json())
    raw["entries"][0]["name"]["text"] = "Подделка"
    tampered = TalentLocalizationDocument.model_validate(raw)
    text = resolve_class_text(talents, tooltips, tampered)
    assert any("contentHash" in p for p in text.overlay_problems)
    assert "Подделка" not in {e.name for e in text.entries.values()}


def test_inconsistent_english_tooltips_are_an_error_not_a_fallback(
    documents: dict[str, TalentDataDocument], tooltips: dict[str, TalentTooltipsDocument]
) -> None:
    with pytest.raises(PipelineError, match="English tooltips"):
        resolve_class_text(documents["WARRIOR"], tooltips["MAGE"], None)


def test_entries_sharing_a_spell_resolve_by_their_own_identity(
    build: BuildLocalization,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    """DUPLICATE_SPELL_IDENTITY (Hunter Lightning Reflexes): two entries, two display texts."""
    talents = documents["HUNTER"]
    shared = entry_named(talents, "Lightning Reflexes")
    text = resolve_class_text(talents, tooltips["HUNTER"], build.overlays["HUNTER"])
    assert text.overlay_problems == ()
    resolved = [text.entries[t.id] for t in shared]
    assert [r.entry_id for r in resolved] == [t.id for t in shared]
    assert {r.name_locale for r in resolved} == {"ruRU"}


def test_localized_ranks_carry_the_english_numbers(warrior) -> None:
    talents, tooltips = warrior
    text = resolve_class_text(talents, tooltips, committed())
    english = {e.entry_id: e for e in tooltips.entries}
    heroic = text.entries[IMPROVED_HEROIC_STRIKE]
    assert heroic.localized_ranks is not None
    for mine, theirs in zip(heroic.localized_ranks, english[heroic.entry_id].ranks, strict=True):
        ours = [s.text for s in mine.segments if s.kind == "value"]
        reference = [s.text for s in theirs.segments if s.kind == "value"]
        assert ours == reference
        assert rank_text(mine) != "".join(s.text for s in theirs.segments)
