"""Live checks against wago.tools. Run with: pytest -m integration"""

from __future__ import annotations

import pytest

from talent_data.games import get_game
from talent_data.pipeline import fetch_tables, find_latest_build, normalize
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


def test_latest_warrior_normalizes_from_real_client_data(http: HttpClient, tmp_path) -> None:
    game = get_game("forever")
    version = find_latest_build(WagoBuildSource(http), game).version
    source = WagoDb2Source(http, tmp_path)
    fetch_tables(source, version)
    result = normalize(source, game, version, "warrior")
    assert result.report.ok, result.report.errors
    tree = result.document.trees[0]
    assert len(tree.nodes) > 40
    assert any(n.prerequisites for n in tree.nodes)
    assert all(n.max_rank > 0 for n in tree.nodes)
