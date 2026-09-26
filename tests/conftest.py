from __future__ import annotations

import pytest

from talent_data.db2.dataset import TraitDataSet
from talent_data.models.talents import TalentDataDocument
from talent_data.normalization.normalizer import normalize_class
from talent_data.sources.local import LocalCsvDb2Source
from tests.helpers import DB2_FIXTURES, FIXED_TIME, NEW_BUILD, OLD_BUILD


@pytest.fixture(scope="session")
def fixture_source() -> LocalCsvDb2Source:
    return LocalCsvDb2Source(DB2_FIXTURES)


@pytest.fixture(scope="session")
def dataset_new(fixture_source: LocalCsvDb2Source) -> TraitDataSet:
    return TraitDataSet.load(fixture_source, NEW_BUILD)


@pytest.fixture(scope="session")
def dataset_old(fixture_source: LocalCsvDb2Source) -> TraitDataSet:
    return TraitDataSet.load(fixture_source, OLD_BUILD)


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
