from __future__ import annotations

import json
from datetime import datetime

import pytest

from talent_data.games import GameConfig, get_game
from talent_data.sources.base import BuildInfo, SourceError, latest_build
from talent_data.sources.csvio import read_csv_table
from talent_data.sources.wago.models import WagoBuildIndex
from talent_data.sources.wago.source import WagoDb2Source, filter_game_builds
from talent_data.versions import ClientVersion
from tests.helpers import FIXTURES

WAGO = FIXTURES / "wago"


def load_index() -> WagoBuildIndex:
    return WagoBuildIndex.model_validate(json.loads((WAGO / "builds.json").read_text()))


def test_forever_builds_are_filtered_by_product_and_branch() -> None:
    builds = filter_game_builds(load_index(), get_game("forever"))
    versions = sorted(str(b.version) for b in builds)
    assert versions == [
        "1.60.1.69876",
        "1.60.1.69893",
        "1.60.1.69913",
        "1.60.1.69977",
        "1.60.1.70009",
    ]
    assert {b.product for b in builds} == {"wow_classic_beta"}


def test_classic_era_builds_with_1x_versions_are_not_forever() -> None:
    # wow_classic_era has 1.15.x builds: same major version, different branch and product.
    era_only = GameConfig("x", "X", products=("wow_classic_era",), version_branch="1.60")
    assert filter_game_builds(load_index(), era_only) == []


def test_latest_is_chosen_by_created_at_not_list_order() -> None:
    builds = filter_game_builds(load_index(), get_game("forever"))
    # Fixture order is scrambled on purpose; the first 1.60 entry is 69893.
    assert str(builds[0].version) == "1.60.1.69893"
    latest = latest_build(builds)
    assert str(latest.version) == "1.60.1.70009"
    assert latest.created_at == datetime(2026, 9, 24, 22, 2, 3)


def test_latest_prefers_created_at_over_version_number() -> None:
    older_but_higher = BuildInfo(
        ClientVersion.parse("1.60.2.1"), "p", created_at=datetime(2026, 1, 1)
    )
    newer = BuildInfo(ClientVersion.parse("1.60.1.9"), "p", created_at=datetime(2026, 2, 1))
    assert latest_build([newer, older_but_higher]) is newer


def test_latest_of_nothing_is_an_error() -> None:
    with pytest.raises(SourceError):
        latest_build([])


def test_product_field_mismatch_is_rejected() -> None:
    raw = json.loads((WAGO / "builds.json").read_text())
    raw["wow_classic_beta"][1]["product"] = "wow"
    with pytest.raises(SourceError, match="product field"):
        filter_game_builds(WagoBuildIndex.model_validate(raw), get_game("forever"))


def test_wago_csv_reserved_column_prefix_is_stripped(tmp_path) -> None:
    version = ClientVersion.parse("1.60.1.70009")
    cache = tmp_path / str(version)
    cache.mkdir()
    src = WAGO / "TraitNodeXTraitNodeEntry.csv"
    (cache / src.name).write_bytes(src.read_bytes())
    _write_meta(cache / src.name)
    table = WagoDb2Source(None, tmp_path, offline=True).load_table(
        version, "TraitNodeXTraitNodeEntry"
    )
    assert table.columns == ("ID", "TraitNodeID", "TraitNodeEntryID", "Index")
    assert table.rows[0]["Index"] == "0"


def test_offline_source_refuses_missing_or_unverified_cache(tmp_path) -> None:
    version = ClientVersion.parse("1.60.1.70009")
    (tmp_path / str(version)).mkdir()
    # A CSV without its .meta.json sidecar is an interrupted/unknown download.
    (tmp_path / str(version) / "TraitNode.csv").write_text("ID\n1\n")
    source = WagoDb2Source(None, tmp_path, offline=True)
    with pytest.raises(SourceError, match="not in the local cache"):
        source.load_table(version, "TraitNode")


def test_csv_quoted_multiline_text_is_one_record() -> None:
    table = read_csv_table(WAGO / "Spell.csv", "Spell")
    assert [r["ID"] for r in table.rows] == ["12282", "1290261"]
    assert "Axe/Polearm" in table.rows[1]["Description_lang"]
    assert "\n" in table.rows[1]["Description_lang"]


def test_csv_ragged_row_is_an_error(tmp_path) -> None:
    path = tmp_path / "T.csv"
    path.write_text("ID,A\n1,2\n3\n")
    with pytest.raises(SourceError, match="record 3 has 1 fields"):
        read_csv_table(path, "T")


def _write_meta(path) -> None:
    import hashlib

    data = path.read_bytes()
    meta = {"url": "fixture", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    path.with_name(path.name + ".meta.json").write_text(json.dumps(meta))
