"""Live checks of the talent-icon sources (wago.tools). Run with: pytest -m integration

Separate from the offline suite on purpose: icon generation is the only part of M5.V1 that
needs the network, and these tests pin the upstream behaviour the offline code relies on.
"""

from __future__ import annotations

import pytest

from talent_data.db2 import tables as t
from talent_data.models.visuals import sha256_of
from talent_data.sources.casc import CascFileError, CascFileErrorKind
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.casc import WagoCascFileSource
from talent_data.sources.wago.source import WagoDb2Source
from talent_data.versions import ClientVersion
from talent_data.visuals.icons import read_blp2_size
from tests.helpers import NEW_BUILD
from tests.unit.visuals.conftest import BLP_WITH_ALPHA, CANONICAL_VISUALS, blp_bytes
from tests.unit.visuals.test_spell_misc import SPELL_MISC_COLUMNS

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def http():
    with HttpClient() as client:
        yield client


def test_spell_misc_exists_for_the_baseline_build_with_the_documented_shape(
    http: HttpClient, tmp_path
) -> None:
    table = WagoDb2Source(http, tmp_path).load_table(NEW_BUILD, "SpellMisc")
    assert table.columns == SPELL_MISC_COLUMNS
    rows = t.parse_table(table, t.SpellMisc.parse)
    assert len(rows) == 31698
    assert len({r.id for r in rows}) == len(rows)


def test_icon_file_is_served_per_build_and_matches_the_recorded_source(
    http: HttpClient, tmp_path
) -> None:
    source = WagoCascFileSource(http, tmp_path)
    data = source.fetch_file(NEW_BUILD, BLP_WITH_ALPHA).read_bytes()
    assert read_blp2_size(data) == (64, 64)
    assert data == blp_bytes(BLP_WITH_ALPHA)
    registry = (CANONICAL_VISUALS / "icons.json").read_text(encoding="utf-8")
    assert sha256_of(data) in registry


def test_unknown_file_and_unknown_build_are_typed(http: HttpClient, tmp_path) -> None:
    source = WagoCascFileSource(http, tmp_path)
    with pytest.raises(CascFileError) as missing:
        source.fetch_file(NEW_BUILD, 999_999_999)
    assert missing.value.kind is CascFileErrorKind.FILE_NOT_FOUND
    with pytest.raises(CascFileError) as unknown:
        source.fetch_file(ClientVersion.parse("1.60.1.1"), BLP_WITH_ALPHA)
    assert unknown.value.kind is CascFileErrorKind.UNKNOWN_BUILD
