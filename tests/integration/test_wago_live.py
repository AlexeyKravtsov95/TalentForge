"""Live checks against wago.tools. Run with: pytest -m integration"""

from __future__ import annotations

import pytest

from talent_data.games import get_game
from talent_data.pipeline import (
    fetch_tables,
    find_latest_build,
    normalize_batch,
    parse_class_keys,
)
from talent_data.sources.base import classify_build
from talent_data.sources.http import HttpClient
from talent_data.sources.wago.source import WagoBuildSource, WagoDb2Source

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def http():
    with HttpClient() as client:
        yield client


def test_latest_forever_build_is_discovered(http: HttpClient) -> None:
    game = get_game("forever")
    info = find_latest_build(WagoBuildSource(http), game)
    assert info.version.branch == "1.60"
    assert info.product == "wow_classic_beta"
    # a bgdl build would be surfaced, never silently treated as publishable
    assert not classify_build(info).is_background_download


def test_all_nine_classes_normalize_from_real_client_data(http: HttpClient, tmp_path) -> None:
    game = get_game("forever")
    version = find_latest_build(WagoBuildSource(http), game).version
    source = WagoDb2Source(http, tmp_path)
    fetch_tables(source, version)
    assert parse_class_keys("all") is None  # discovery is data-driven
    batch = normalize_batch(source, game, version, class_keys=None)
    assert batch.ok, batch.describe_failures()
    tokens = [r.chr_class.token for r in batch.results]
    assert tokens == [
        "WARRIOR",
        "PALADIN",
        "HUNTER",
        "ROGUE",
        "PRIEST",
        "SHAMAN",
        "MAGE",
        "WARLOCK",
        "DRUID",
    ]
    for result in batch.results:
        tree = result.document.trees[0]
        assert len(tree.nodes) > 40
        assert tree.point_budget is not None and tree.point_budget.max_points > 0
        assert all(n.max_rank > 0 for n in tree.nodes)
        assert any(n.prerequisites for n in tree.nodes)
