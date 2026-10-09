from __future__ import annotations

from pathlib import Path

import pytest

from talent_data.games import get_game
from talent_data.models.talents import TalentDataDocument
from talent_data.pipeline import read_document
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.visuals.resolver import IconResolution, VisualSourceTables, resolve_document
from tests.conftest import ALL_CLASSES
from tests.helpers import FIXTURES, NEW_BUILD

ROOT = Path(__file__).resolve().parents[3]
GAME = get_game("forever")
CANONICAL_DATASET = ROOT / "data" / "normalized" / "forever" / str(NEW_BUILD)
CANONICAL_VISUALS = ROOT / "data" / "visuals" / "forever" / str(NEW_BUILD)
COVERAGE_REPORT = ROOT / "artifacts" / "visuals" / f"forever-{NEW_BUILD}-icon-coverage.json"
CASC_FIXTURES = FIXTURES / "casc" / "forever" / str(NEW_BUILD)
# Two real client icon files of 1.60.1.70009: one with a real alpha channel (rounded
# corners, DXT with 8-bit alpha) and one without alpha.
BLP_WITH_ALPHA = 132269
BLP_WITHOUT_ALPHA = 132090


def blp_bytes(file_data_id: int) -> bytes:
    return (CASC_FIXTURES / f"{file_data_id}.blp").read_bytes()


@pytest.fixture(scope="session")
def canonical_docs() -> dict[str, TalentDataDocument]:
    """The committed talent-data.v1 documents of the baseline build, by class key."""
    return {key: read_document(CANONICAL_DATASET / f"{key}.json") for key in ALL_CLASSES}


@pytest.fixture(scope="session")
def visual_tables(merged_source: LocalCsvDb2Source) -> VisualSourceTables:
    """Real TraitDefinition + SpellMisc rows of all nine classes (fixture subset)."""
    return VisualSourceTables.load(merged_source, NEW_BUILD)


@pytest.fixture(scope="session")
def resolutions(
    canonical_docs: dict[str, TalentDataDocument], visual_tables: VisualSourceTables
) -> dict[str, list[IconResolution]]:
    return {
        doc.class_.token: resolve_document(doc, visual_tables) for doc in canonical_docs.values()
    }
