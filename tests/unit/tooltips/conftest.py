from __future__ import annotations

from pathlib import Path

import pytest

from talent_data.games import get_game
from talent_data.models.talents import TalentDataDocument, TalentEntry
from talent_data.models.tooltips import TooltipRank, rank_text
from talent_data.pipeline import read_document
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.tooltips.render import TalentRenderer
from talent_data.tooltips.spells import SpellLookup
from tests.conftest import ALL_CLASSES
from tests.helpers import FIXTURES, NEW_BUILD

ROOT = Path(__file__).resolve().parents[3]
GAME = get_game("forever")
CANONICAL_DATASET = ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD)
CANONICAL_TOOLTIPS = ROOT / "data" / "tooltips" / "forever" / str(NEW_BUILD)
COVERAGE_REPORT = ROOT / "artifacts" / "tooltips" / f"forever-{NEW_BUILD}-tooltip-coverage.json"
COVERAGE_MARKDOWN = ROOT / "docs" / "research" / f"forever-{NEW_BUILD}-tooltip-coverage.md"
# The closure of every tooltip-table row the committed templates reach (real rows only;
# regenerate with `python scripts/extract_fixtures.py --build 1.60.1.70009 --tooltips`).
TOOLTIP_FIXTURES = FIXTURES / "tooltips" / "forever" / str(NEW_BUILD)
SOURCE_NAME = "wago.tools"


def tooltip_source() -> LocalCsvDb2Source:
    return LocalCsvDb2Source(TOOLTIP_FIXTURES, per_version=False)


@pytest.fixture(scope="session")
def spells() -> SpellLookup:
    return SpellLookup.load(tooltip_source(), NEW_BUILD)


@pytest.fixture(scope="session")
def canonical_docs() -> dict[str, TalentDataDocument]:
    return {key: read_document(CANONICAL_DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture(scope="session")
def entries(canonical_docs: dict[str, TalentDataDocument]) -> dict[int, tuple[str, TalentEntry]]:
    """``entry id -> (CLASS, entry)`` for every talent entry of the committed dataset."""
    return {
        entry.id: (doc.class_.token, entry)
        for doc in canonical_docs.values()
        for tree in doc.trees
        for node in tree.nodes
        for entry in node.entries
    }


class Talent:
    """Renders one real talent of the committed dataset at its ranks."""

    def __init__(self, entry: TalentEntry, spells: SpellLookup) -> None:
        self.entry = entry
        self.renderer = TalentRenderer(entry, spells)

    def rank(self, rank: int) -> TooltipRank:
        return self.renderer.render(rank).rank

    def text(self, rank: int = 1) -> str:
        return rank_text(self.rank(rank))

    def texts(self) -> list[str]:
        return [self.text(r) for r in range(1, self.entry.max_rank + 1)]

    def values(self, rank: int = 1) -> list[str]:
        return [s.text for s in self.rank(rank).segments if s.kind == "value"]


@pytest.fixture(scope="session")
def talent(entries: dict[int, tuple[str, TalentEntry]], spells: SpellLookup):
    """A talent by class and name (names that occur twice in a class are refused)."""

    def get(class_token: str, name: str) -> Talent:
        matches = [e for c, e in entries.values() if c == class_token and e.name == name]
        assert len(matches) == 1, f"{class_token} {name}: {len(matches)} entries"
        return Talent(matches[0], spells)

    return get
