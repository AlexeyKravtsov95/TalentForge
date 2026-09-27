"""Live checks against wago.tools. Run with: pytest -m integration"""

from __future__ import annotations

import pytest

from talent_data.db2.tables import RANK_COST_EXTENSION_TABLES
from talent_data.games import get_game
from talent_data.pipeline import (
    fetch_tables,
    find_latest_build,
    normalize_batch,
    parse_class_keys,
)
from talent_data.sources.base import TableStatus, classify_build
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
    # tri-state: the source must *state* that the build is not a background download -
    # an unknown flag (None) is not evidence of a publishable build
    candidate = classify_build(info)
    assert candidate.background_download is False
    assert candidate.publishable is True


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


# Rank prices can also be attached per node group, node or entry. The production pipeline
# proves their absence itself (Db2Source.table_status + the normalizer gate); this live test
# is an additional check that the latest build still answers "absent" through that same
# production probe, not the only safety barrier.


@pytest.mark.parametrize("table", RANK_COST_EXTENSION_TABLES)
def test_latest_build_has_no_node_level_cost_tables(http: HttpClient, tmp_path, table: str) -> None:
    game = get_game("forever")
    version = find_latest_build(WagoBuildSource(http), game).version
    availability = WagoDb2Source(http, tmp_path).table_status(version, table)
    assert availability.status is TableStatus.ABSENT, availability.evidence
