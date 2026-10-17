from __future__ import annotations

import pytest

from talent_data.db2.dataset import TraitDataSet
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.normalizer import normalize_class
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.versions import ClientVersion
from tests.helpers import (
    BUILD_70245,
    BUILD_70338,
    DB2_FIXTURES,
    FIXED_TIME,
    NEW_BUILD,
    OLD_BUILD,
    PREVIOUS_PRODUCT_BUILD,
    PRODUCT_BUILD,
)

# The nine Forever classes, in ChrClasses.ID order (the canonical processing order).
ALL_CLASSES = [
    "warrior",
    "paladin",
    "hunter",
    "rogue",
    "priest",
    "shaman",
    "mage",
    "warlock",
    "druid",
]


def fixture_source_for(class_key: str, build: ClientVersion = NEW_BUILD) -> LocalCsvDb2Source:
    """Each class fixture dir is a self-contained table set for that class's tree in one
    build (tests/fixtures/db2/forever/<build>/<class>/<Table>.csv)."""
    return LocalCsvDb2Source(DB2_FIXTURES / str(build) / class_key, per_version=False)


def load_dataset(class_key: str, build: ClientVersion = NEW_BUILD) -> TraitDataSet:
    return TraitDataSet.load(fixture_source_for(class_key, build), build)


def normalize_fixture_class(class_key: str, build: ClientVersion = NEW_BUILD) -> TalentDataDocument:
    return normalize_class(
        load_dataset(class_key, build),
        game="forever",
        class_key=class_key,
        source_name="fixture",
        generated_at=FIXED_TIME,
    )


@pytest.fixture(scope="session")
def fixture_source() -> LocalCsvDb2Source:
    """Warrior NEW_BUILD fixture source (kept for the M0-era tests)."""
    return fixture_source_for("warrior", NEW_BUILD)


@pytest.fixture(scope="session")
def dataset_new(fixture_source: LocalCsvDb2Source) -> TraitDataSet:
    return TraitDataSet.load(fixture_source, NEW_BUILD)


@pytest.fixture(scope="session")
def dataset_old() -> TraitDataSet:
    return load_dataset("warrior", OLD_BUILD)


def normalize_warrior(dataset: TraitDataSet) -> TalentDataDocument:
    return normalize_class(
        dataset,
        game="forever",
        class_key="warrior",
        source_name="fixture",
        generated_at=FIXED_TIME,
    )


@pytest.fixture(scope="session")
def warrior_new(dataset_new: TraitDataSet) -> TalentDataDocument:
    return normalize_warrior(dataset_new)


@pytest.fixture(scope="session")
def warrior_old(dataset_old: TraitDataSet) -> TalentDataDocument:
    return normalize_warrior(dataset_old)


@pytest.fixture(scope="session")
def class_docs() -> dict[str, TalentDataDocument]:
    """All nine classes normalized from the NEW_BUILD fixtures (deterministic output)."""
    return {key: normalize_fixture_class(key, NEW_BUILD) for key in ALL_CLASSES}


@pytest.fixture(scope="session")
def merged_source(tmp_path_factory: pytest.TempPathFactory) -> LocalCsvDb2Source:
    """A flat per-build table set merged from the per-class fixtures of all classes.

    Shared class tables are identical across class fixtures and tree tables are
    row-disjoint, so the union is a valid whole-build table set for offline tests.
    """
    from tests.unit.test_cli import _merge_class_fixtures

    root = tmp_path_factory.mktemp("merged")
    for build in (
        OLD_BUILD,
        NEW_BUILD,
        BUILD_70245,
        BUILD_70338,
        PREVIOUS_PRODUCT_BUILD,
        PRODUCT_BUILD,
    ):
        _merge_class_fixtures(DB2_FIXTURES / str(build), root / str(build))
    return LocalCsvDb2Source(root)
