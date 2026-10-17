"""Product freshness check (M5.DATA.2): CURRENT / OUTDATED / UNKNOWN, offline.

Every case runs the real ``WagoBuildSource`` + ``HttpClient`` against an
``httpx.MockTransport`` serving ``tests/fixtures/wago/builds-2026-10-10.json``,
``builds-2026-10-14.json`` or ``builds-2026-10-17.json`` (real ``/api/builds`` subsets) or a
variant of one derived
explicitly in the test. A network,
HTTP or schema failure must be UNKNOWN - never CURRENT ("no update").
"""

from __future__ import annotations

import importlib.util
import json
import sys
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType
from typing import Any

import httpx
import pytest

from talent_data.cli import main as cli
from talent_data.freshness import FreshnessReport, FreshnessStatus, evaluate_freshness
from talent_data.games import get_game
from talent_data.sources.http import HttpClient, HttpConfig
from talent_data.sources.wago.source import WagoBuildSource
from talent_data.versions import ClientVersion

ROOT = Path(__file__).resolve().parents[2]
BUILDS = ROOT / "tests" / "fixtures" / "wago" / "builds-2026-10-10.json"
# The index of the M5.DATA.3 decision (2026-10-14 16:50 UTC): 70374 is the newest Forever
# build, explicitly not a background download; a new `wowf` product appeared with 70245.
BUILDS_70374 = ROOT / "tests" / "fixtures" / "wago" / "builds-2026-10-14.json"
# The index of the M5.DATA.4 decision (2026-10-17 18:40 UTC): 70425 and then 70450 appeared,
# both explicitly not background downloads; `wowf` still lists only 70245.
BUILDS_70450 = ROOT / "tests" / "fixtures" / "wago" / "builds-2026-10-17.json"
GAME = get_game("forever")
NOW = datetime(2026, 10, 10, 17, 28, 14, tzinfo=UTC)
V70245, V70291, V70334, V70338 = "1.60.1.70245", "1.60.1.70291", "1.60.1.70334", "1.60.1.70338"
V70374, V70425, V70450 = "1.60.1.70374", "1.60.1.70425", "1.60.1.70450"

Handler = Callable[[httpx.Request], httpx.Response]


def payload() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(BUILDS.read_text(encoding="utf-8"))
    return data


def payload_70374() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(BUILDS_70374.read_text(encoding="utf-8"))
    return data


def payload_70450() -> dict[str, Any]:
    data: dict[str, Any] = json.loads(BUILDS_70450.read_text(encoding="utf-8"))
    return data


def with_bgdl(version: str, value: bool | None) -> dict[str, Any]:
    """The real payload with one Forever build's ``is_bgdl`` set (``None`` = field absent)."""
    data = payload()
    (entry,) = [e for e in data["wow_classic_beta"] if e["version"] == version]
    if value is None:
        del entry["is_bgdl"]
    else:
        entry["is_bgdl"] = value
    return data


def without(*versions: str) -> dict[str, Any]:
    data = payload()
    data["wow_classic_beta"] = [e for e in data["wow_classic_beta"] if e["version"] not in versions]
    return data


def serving(body: object) -> Handler:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/builds"
        return httpx.Response(200, json=body)

    return handler


def http(handler: Handler) -> HttpClient:
    return HttpClient(
        HttpConfig(max_attempts=2, backoff_base=0),
        transport=httpx.MockTransport(handler),
        sleep=lambda _: None,
    )


def check(handler: Handler, product: str) -> FreshnessReport:
    with http(handler) as client:
        return evaluate_freshness(
            WagoBuildSource(client), GAME, ClientVersion.parse(product), now=NOW
        )


def latest_publishable(report: FreshnessReport) -> str | None:
    candidate = report.latest_publishable
    return None if candidate is None else str(candidate.info.version)


# --------------------------------------------------------------------------- statuses


def test_product_build_equal_to_the_latest_publishable_build_is_current() -> None:
    report = check(serving(payload()), V70338)
    assert report.status is FreshnessStatus.CURRENT
    assert latest_publishable(report) == V70338
    assert report.newer_unpublishable == ()


def test_a_newer_publishable_build_makes_the_product_outdated() -> None:
    """The transition this milestone made: 70245 was the product, 70338 appeared."""
    report = check(serving(payload()), V70245)
    assert report.status is FreshnessStatus.OUTDATED
    assert latest_publishable(report) == V70338
    assert "1.60.1.70338 is publishable (is_bgdl=false)" in report.reason


def test_after_switching_the_product_the_same_source_is_current() -> None:
    assert check(serving(payload()), V70245).status is FreshnessStatus.OUTDATED
    assert check(serving(payload()), V70338).status is FreshnessStatus.CURRENT


def test_the_m5_data_3_index_makes_the_previous_product_outdated() -> None:
    """The transition M5.DATA.3 made: 70338 was the product, 70374 appeared publishable."""
    report = check(serving(payload_70374()), V70338)
    assert report.status is FreshnessStatus.OUTDATED
    assert latest_publishable(report) == V70374
    assert report.latest_publishable is not None
    assert report.latest_publishable.background_download is False
    assert "1.60.1.70374 is publishable (is_bgdl=false)" in report.reason


def test_the_m5_data_3_index_is_current_for_70374() -> None:
    report = check(serving(payload_70374()), V70374)
    assert report.status is FreshnessStatus.CURRENT
    assert latest_publishable(report) == V70374
    assert report.latest_detected is not None
    assert str(report.latest_detected.info.version) == V70374
    assert report.newer_unpublishable == ()


def test_the_wowf_product_is_outside_the_game_s_products() -> None:
    """``wowf`` (first seen in the 2026-10-14 index, carrying 1.60.1.70245) is not one of
    the products Forever is mapped to, so it is never a candidate - an explicit, documented
    scope limit (docs/dataset-freshness-watch.md), not a judgement about that product."""
    assert GAME.products == ("wow_classic_beta",)
    assert [e["version"] for e in payload_70374()["wowf"]] == [V70245]
    report = check(serving(payload_70374()), V70374)
    assert report.latest_publishable is not None
    assert report.latest_publishable.info.product == "wow_classic_beta"


def test_the_m5_data_4_index_makes_the_previous_product_outdated() -> None:
    """The transition M5.DATA.4 made: 70374 was the product, 70425 and 70450 appeared
    publishable; the newest one is the answer."""
    report = check(serving(payload_70450()), V70374)
    assert report.status is FreshnessStatus.OUTDATED
    assert latest_publishable(report) == V70450
    assert report.latest_publishable is not None
    assert report.latest_publishable.background_download is False
    assert "1.60.1.70450 is publishable (is_bgdl=false)" in report.reason
    assert check(serving(payload_70450()), V70425).status is FreshnessStatus.OUTDATED


def test_the_m5_data_4_index_is_current_for_70450() -> None:
    report = check(serving(payload_70450()), V70450)
    assert report.status is FreshnessStatus.CURRENT
    assert latest_publishable(report) == V70450
    assert report.latest_detected is not None
    assert str(report.latest_detected.info.version) == V70450
    assert report.newer_unpublishable == ()


def test_the_wowf_product_is_unchanged_in_the_m5_data_4_index() -> None:
    """Three days later ``wowf`` still carries only 1.60.1.70245 (and ``wow_cn_beta`` stops
    at 70374): nothing in the index moves the Forever product off ``wow_classic_beta``."""
    assert [e["version"] for e in payload_70450()["wowf"]] == [V70245]
    assert payload_70450()["wowf"] == payload_70374()["wowf"]
    report = check(serving(payload_70450()), V70450)
    assert report.latest_publishable is not None
    assert report.latest_publishable.info.product == "wow_classic_beta"


def test_a_newer_background_download_build_is_not_an_update() -> None:
    """is_bgdl=true: a pre-staged client, not publishable - the product stays CURRENT,
    and the pending build is reported."""
    report = check(serving(with_bgdl(V70338, True)), V70334)
    assert report.status is FreshnessStatus.CURRENT
    assert latest_publishable(report) == V70334
    assert report.latest_detected is not None
    assert str(report.latest_detected.info.version) == V70338
    assert [(str(c.info.version), c.background_download) for c in report.newer_unpublishable] == [
        (V70338, True)
    ]


def test_a_newer_build_with_unknown_bgdl_status_is_unknown() -> None:
    """is_bgdl absent (null): the newer build may or may not be publishable."""
    report = check(serving(with_bgdl(V70338, None)), V70334)
    assert report.status is FreshnessStatus.UNKNOWN
    assert "1.60.1.70338 have no is_bgdl flag" in report.reason
    assert [(str(c.info.version), c.background_download) for c in report.newer_unpublishable] == [
        (V70338, None)
    ]


def test_an_unknown_newer_build_does_not_hide_a_proven_update() -> None:
    """70334 (is_bgdl=false) is newer than 70245 whatever 70338 turns out to be."""
    report = check(serving(with_bgdl(V70338, None)), V70245)
    assert report.status is FreshnessStatus.OUTDATED
    assert latest_publishable(report) == V70334


def test_explicit_false_is_publishable_and_the_newest_one_wins() -> None:
    report = check(serving(with_bgdl(V70338, False)), V70291)
    assert report.status is FreshnessStatus.OUTDATED
    assert latest_publishable(report) == V70338


def test_no_publishable_build_is_unknown() -> None:
    data = payload()
    for entry in data["wow_classic_beta"]:
        entry.pop("is_bgdl", None)
    report = check(serving(data), V70338)
    assert report.status is FreshnessStatus.UNKNOWN
    assert "no publishable builds" in report.reason
    assert report.latest_publishable is None


def test_a_product_build_ahead_of_the_source_is_unknown() -> None:
    report = check(serving(without(V70338, V70334)), V70338)
    assert report.status is FreshnessStatus.UNKNOWN
    assert latest_publishable(report) == V70291


def test_no_forever_builds_at_all_is_unknown() -> None:
    data = payload()
    data["wow_classic_beta"] = [e for e in data["wow_classic_beta"] if e["version"][:4] != "1.60"]
    assert check(serving(data), V70338).status is FreshnessStatus.UNKNOWN


# --------------------------------------------------------------------------- failures


def test_a_network_error_is_unknown_not_current() -> None:
    calls = 0

    def offline(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ConnectError("connection refused", request=request)

    report = check(offline, V70338)
    assert report.status is FreshnessStatus.UNKNOWN
    assert calls == 2  # retried, then given up - never read as "no newer build"
    assert report.latest_publishable is None and report.latest_detected is None
    assert "could not be read" in report.reason


def test_an_http_error_is_unknown() -> None:
    report = check(lambda request: httpx.Response(503, text="maintenance"), V70338)
    assert report.status is FreshnessStatus.UNKNOWN


@pytest.mark.parametrize("wire", ["false", "yes", 0, 1])
def test_a_non_boolean_is_bgdl_is_unknown_never_coerced(wire: object) -> None:
    """Regression: the wago model used to coerce ``"false"`` / ``0`` into ``False``
    (publishable) and ``"yes"`` / ``1`` into ``True``."""
    data = with_bgdl(V70338, False)
    (entry,) = [e for e in data["wow_classic_beta"] if e["version"] == V70338]
    entry["is_bgdl"] = wire
    report = check(serving(data), V70245)
    assert report.status is FreshnessStatus.UNKNOWN
    assert "unexpected /api/builds schema" in report.reason


def test_a_product_build_of_another_game_is_rejected() -> None:
    with pytest.raises(ValueError, match="not a Forever build"):
        check(serving(payload()), "5.5.0.62071")


# --------------------------------------------------------------------------- output


def test_machine_readable_result() -> None:
    report = check(serving(with_bgdl(V70338, True)), V70334)
    assert report.to_dict() == {
        "schemaVersion": 1,
        "kind": "product-freshness",
        "game": "forever",
        "status": "CURRENT",
        "productBuild": V70334,
        "latestPublishable": {
            "version": V70334,
            "product": "wow_classic_beta",
            "createdAt": "2026-10-09 22:49:04",
            "isBgdl": False,
        },
        "latestDetected": {
            "version": V70338,
            "product": "wow_classic_beta",
            "createdAt": "2026-10-10 05:05:05",
            "isBgdl": True,
        },
        "newerUnpublishable": [
            {
                "version": V70338,
                "product": "wow_classic_beta",
                "createdAt": "2026-10-10 05:05:05",
                "isBgdl": True,
            }
        ],
        "reason": (
            "1.60.1.70334 is the latest publishable build wago.tools reports "
            "(newer background-download builds, not publishable: 1.60.1.70338)"
        ),
        "source": "wago.tools",
        "checkedAt": "2026-10-10T17:28:14Z",
    }
    assert json.loads(report.to_json()) == report.to_dict()


@pytest.mark.parametrize(
    ("handler", "product", "status", "code"),
    [
        (serving(payload()), V70338, "CURRENT", 0),
        (serving(payload()), V70245, "OUTDATED", 3),
        (lambda request: httpx.Response(502), V70338, "UNKNOWN", 4),
    ],
)
def test_cli_prints_json_and_exits_with_the_status(
    handler: Handler,
    product: str,
    status: str,
    code: int,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(cli, "_http", lambda: http(handler))
    output = tmp_path / "out" / "freshness.json"
    argv = ["builds", "freshness", "--product-build", product, "--output", str(output)]
    assert cli.main(argv) == code
    captured = capsys.readouterr()
    result = json.loads(captured.out)
    assert (result["status"], result["productBuild"]) == (status, product)
    assert json.loads(output.read_text(encoding="utf-8")) == result
    assert captured.err.startswith(f"{status}: ")


def test_cli_rejects_a_malformed_product_build(capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["builds", "freshness", "--product-build", "70338"]) == 1
    assert "not a WoW client version" in capsys.readouterr().err


# --------------------------------------------------------------------------- script


def load_script(name: str) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def kotlin(build: str, game: str = "forever") -> str:
    return (
        "val PRODUCT_BUNDLE: DatasetBundleId = DatasetBundleId(\n"
        f'    game = GameId("{game}"),\n'
        f'    clientBuild = "{build}",\n'
        ")\n"
    )


def test_product_build_is_read_strictly_from_the_app(tmp_path: Path) -> None:
    bundles = load_script("mobile_bundles")
    source = tmp_path / "BundledDatasets.kt"
    source.write_text(kotlin(V70338))
    assert bundles.product_build(source) == V70338
    for broken in ("", kotlin(V70338) + kotlin(V70245), kotlin("70338"), kotlin(V70338, "retail")):
        source.write_text(broken)
        with pytest.raises(ValueError):
            bundles.product_build(source)


def test_script_checks_the_app_s_product_bundle(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    script = load_script("check_product_freshness")
    declared = script.product_build()
    assert declared in script.BUNDLED_BUILDS
    monkeypatch.setattr(cli, "_http", lambda: http(serving(payload_70450())))
    code = script.main([])
    result = json.loads(capsys.readouterr().out)
    # the app declares the build this index names as the latest publishable one
    assert (result["productBuild"], result["status"], code) == (declared, "CURRENT", 0)
