"""Live checks of the localized wago.tools exports. Run with: pytest -m integration"""

from __future__ import annotations

from pathlib import Path

import pytest

from talent_data.localization.source import (
    LocalizationSourceError,
    LocalizationSourceErrorKind,
    WagoLocalizedDb2Source,
    verify_localized_table,
)
from talent_data.sources.csvio import read_csv_table
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion

pytestmark = pytest.mark.integration

PRODUCT = ClientVersion.parse("1.60.1.70338")
REFERENCE = ClientVersion.parse("1.60.1.70009")
FURIOUS_PRECISION = "1323963"  # a Forever-only spell first shipped after 70009


@pytest.fixture(scope="module")
def http():
    with HttpClient() as client:
        yield client


def test_rurU_is_served_for_the_exact_build_and_verifies(http: HttpClient, tmp_path: Path) -> None:
    english = WagoDb2Source(http, tmp_path).load_table(PRODUCT, "SpellName")
    russian = WagoLocalizedDb2Source(http, tmp_path, "ruRU").load_table(PRODUCT, "SpellName")
    check = verify_localized_table(english, russian, "ruRU")
    assert check.translated_rows > 20_000
    rows = {r["ID"]: r["Name_lang"] for r in russian.rows}
    assert rows["12282"] == "Улучшенный удар героя"
    assert rows[FURIOUS_PRECISION] == "Furious Precision"  # untranslated: English served


def test_the_locale_is_build_pinned(http: HttpClient, tmp_path: Path) -> None:
    source = WagoLocalizedDb2Source(http, tmp_path, "ruRU")
    old = {r["ID"] for r in source.load_table(REFERENCE, "SpellName").rows}
    new = {r["ID"] for r in source.load_table(PRODUCT, "SpellName").rows}
    assert FURIOUS_PRECISION in new
    assert FURIOUS_PRECISION not in old


def test_an_unsupported_locale_value_silently_returns_english(
    http: HttpClient, tmp_path: Path
) -> None:
    """The reason every localized table is verified: HTTP 200 with the enUS body."""
    english = WagoDb2Source(http, tmp_path).load_table(PRODUCT, "SpellName")
    path = tmp_path / "ruru.csv"
    http.download_file(
        f"https://wago.tools/db2/SpellName/csv?build={PRODUCT}&locale=ruru", path, force=True
    )
    served = read_csv_table(path, "SpellName")
    with pytest.raises(LocalizationSourceError) as caught:
        verify_localized_table(english, served, "ruRU")
    assert caught.value.kind is LocalizationSourceErrorKind.LOCALE_NOT_APPLIED
