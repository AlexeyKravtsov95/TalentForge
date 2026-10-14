"""Shared inputs for the localization tests: the real enUS/ruRU rows of the M5.REL.3a pilot
build 1.60.1.70338 (the product build of M5.REL.3a, retained since M5.DATA.3)."""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import pytest

from talent_data.games import get_game
from talent_data.localization.generate import LocalizationInputs, load_inputs
from talent_data.localization.pipeline import BuildLocalization, localize_build
from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.tooltips import TalentTooltipsDocument
from talent_data.pipeline import read_document
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.tooltips.pipeline import read_tooltips_document
from talent_data.tooltips.spells import SpellLookup
from tests.helpers import FIXTURES, PILOT_BUILD

ROOT = Path(__file__).resolve().parents[3]
GAME = get_game("forever")
LOCALE = "ruRU"
SOURCE_NAME = "wago.tools"
DATASET = ROOT / "data" / "normalized" / "forever" / str(PILOT_BUILD)
TOOLTIPS = ROOT / "data" / "tooltips" / "forever" / str(PILOT_BUILD)
OVERLAYS = ROOT / "data" / "localization" / "forever" / str(PILOT_BUILD) / LOCALE
REPORT = ROOT / "artifacts" / "localization" / f"forever-{PILOT_BUILD}-{LOCALE}-coverage.json"
MARKDOWN = ROOT / "docs" / "research" / f"forever-{PILOT_BUILD}-{LOCALE}-coverage.md"
# Real rows of both exports (identical ids); regenerate with
# `python scripts/extract_fixtures.py --build 1.60.1.70338 --localization ruRU`.
LOCALIZATION_FIXTURES = FIXTURES / "localization" / "forever" / str(PILOT_BUILD)
TOOLTIP_FIXTURES = FIXTURES / "tooltips" / "forever" / str(PILOT_BUILD)


def english_source() -> LocalCsvDb2Source:
    return LocalCsvDb2Source(LOCALIZATION_FIXTURES / "enUS", per_version=False)


def russian_source() -> LocalCsvDb2Source:
    return LocalCsvDb2Source(LOCALIZATION_FIXTURES / LOCALE, per_version=False)


def tooltip_source() -> LocalCsvDb2Source:
    return LocalCsvDb2Source(TOOLTIP_FIXTURES, per_version=False)


@pytest.fixture(scope="session")
def inputs() -> LocalizationInputs:
    return load_inputs(
        english_source(),
        russian_source(),
        SpellLookup.load(tooltip_source(), PILOT_BUILD),
        PILOT_BUILD,
        LOCALE,
    )


@pytest.fixture(scope="session")
def build() -> BuildLocalization:
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("TALENT_DATA_ROOT", str(ROOT))
        return localize_build(
            english_source(),
            russian_source(),
            tooltip_source(),
            GAME,
            PILOT_BUILD,
            LOCALE,
            source_name=SOURCE_NAME,
        )


@pytest.fixture(scope="session")
def documents() -> dict[str, TalentDataDocument]:
    return {
        path.stem.upper(): read_document(path)
        for path in sorted(DATASET.glob("*.json"))
        if path.name != "manifest.json"
    }


@pytest.fixture(scope="session")
def tooltips() -> dict[str, TalentTooltipsDocument]:
    return {path.stem.upper(): read_tooltips_document(path) for path in TOOLTIPS.glob("*.json")}


@pytest.fixture
def at_repo_root(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TALENT_DATA_ROOT", str(ROOT))


def entry_named(document: TalentDataDocument, name: str) -> list[TalentEntry]:
    return [e for t in document.trees for n in t.nodes for e in n.entries if e.name == name]


def with_russian(
    inputs: LocalizationInputs,
    *,
    names: dict[int, str | None] | None = None,
    descriptions: dict[int, str | None] | None = None,
) -> LocalizationInputs:
    """``inputs`` with some ruRU strings replaced (``None`` removes the row)."""

    def patched(base: dict[int, str], changes: dict[int, str | None]) -> dict[int, str]:
        result = dict(base)
        for key, value in changes.items():
            if value is None:
                result.pop(key, None)
            else:
                result[key] = value
        return result

    localized = replace(
        inputs.localized,
        names=patched(dict(inputs.localized.names), names or {}),
        descriptions=patched(dict(inputs.localized.descriptions), descriptions or {}),
    )
    return replace(inputs, localized=localized)
