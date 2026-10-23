"""The historical patch replay 70374 -> 70450 (M5.OTA.AUTO.2), offline parts.

``talent_data.prepare.replay`` reconstructs the moment before the patch for the existing
detection; ``scripts/ota_historical_replay.py`` chains it with the existing preparation, the
staging stand and the Kotlin client. These tests prove the detection over the recorded build
indexes, the production stand-in, the report logic and - end to end without the network and
without Gradle - the detection, staging and guard phases of the entrypoint. The preparation
(wago.tools) and the client (Gradle) are exercised by the entrypoint itself and the workflow.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path
from types import ModuleType

import httpx
import pytest

from talent_data.freshness import FreshnessStatus, evaluate_freshness
from talent_data.games import get_game
from talent_data.prepare import replay
from talent_data.prepare.detect import Action, BaseKind
from talent_data.prepare.orchestrate import main_commit
from talent_data.prepare.production import ProductionStatus
from talent_data.prepare.replay import Verdict
from talent_data.versions import ClientVersion

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = (
    ROOT / "mobile/composeApp/src/desktopTest/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota"
)
WORKFLOW = ROOT / ".github/workflows/ota-historical-replay.yml"


def _script() -> ModuleType:
    name = "ota_historical_replay"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


script = _script()


# --------------------------------------------------------------------------- detection


def test_the_recorded_indexes_are_the_moments_before_and_after_the_patch() -> None:
    game = get_game("forever")
    product = ClientVersion.parse(replay.BASE_BUILD)
    before = evaluate_freshness(
        replay.RecordedBuildSource(ROOT / replay.BEFORE_INDEX), game, product, replay.BEFORE_AT
    )
    after = evaluate_freshness(
        replay.RecordedBuildSource(ROOT / replay.AFTER_INDEX), game, product, replay.AFTER_AT
    )
    assert before.status is FreshnessStatus.CURRENT
    assert before.latest_publishable is not None
    assert str(before.latest_publishable.info.version) == replay.BASE_BUILD
    assert after.status is FreshnessStatus.OUTDATED
    assert after.latest_publishable is not None
    assert str(after.latest_publishable.info.version) == replay.PATCH_BUILD
    assert after.latest_publishable.publishable  # is_bgdl explicitly false, as recorded


def test_the_existing_detection_sees_nothing_then_selects_70450() -> None:
    log = replay.HistoricalProduction()
    before = replay.detect_at(
        ROOT / replay.BEFORE_INDEX,
        repo=ROOT,
        product_build=replay.BASE_BUILD,
        now=replay.BEFORE_AT,
        production_log=log,
    )
    after = replay.detect_at(
        ROOT / replay.AFTER_INDEX,
        repo=ROOT,
        product_build=replay.BASE_BUILD,
        now=replay.AFTER_AT,
        production_log=log,
    )
    assert before.action is Action.NO_ACTION
    assert before.candidate_build == replay.BASE_BUILD
    assert "PRODUCT_BUNDLE" in before.reason
    assert after.action is Action.PREPARE
    assert (after.candidate_build, after.base_build) == (replay.PATCH_BUILD, replay.BASE_BUILD)
    assert after.base_kind is BaseKind.PRODUCT_BUNDLE and after.revision == 1
    assert after.product_build == replay.BASE_BUILD and not after.forced
    main = main_commit(ROOT)
    assert after.main_commit == main
    assert after.preparation_key == f"forever-1.60.1.70450-r1-from-1.60.1.70374-main-{main[:12]}"
    for detection in (before, after):
        assert detection.production.status is ProductionStatus.ABSENT
    catalog = f"{replay.HISTORICAL_PRODUCTION_ORIGIN}/v1/channels/production/catalog.json"
    assert log.requests == [f"GET {catalog}"] * 2


def test_the_historical_production_answers_its_catalog_only() -> None:
    log = replay.HistoricalProduction()
    with replay.historical_production_client(log) as client:
        catalog = client.get(
            f"{replay.HISTORICAL_PRODUCTION_ORIGIN}/v1/channels/production/catalog.json"
        )
        assert catalog.status_code == 404
        with pytest.raises(AssertionError, match="was asked for"):
            client.get("https://ota.mythweld.com/v1/channels/production/catalog.json")
        with pytest.raises(AssertionError):
            client.get(f"{replay.HISTORICAL_PRODUCTION_ORIGIN}/v1/blobs/sha256/{'0' * 64}")
    assert len(log.requests) == 3
    assert replay.HISTORICAL_PRODUCTION_ORIGIN.endswith(".invalid")  # RFC 2606: never resolves


# --------------------------------------------------------------------------- report logic


def test_release_files_are_compared_by_path_hash_and_length() -> None:
    manifest = (ROOT / "docs/ota/fixtures/v1/releases/1.60.1.70450-r1.release.json").read_bytes()
    files = replay.release_files(manifest)
    assert len(files) == 397
    assert replay.compare_release_files(files, dict(files)) == []
    changed = dict(files)
    path = "dataset/forever/1.60.1.70450/druid.json"
    changed[path] = ("sha256:" + "0" * 64, files[path][1])
    del changed["dataset/forever/1.60.1.70450/mage.json"]
    changed["dataset/forever/1.60.1.70450/extra.json"] = ("sha256:" + "1" * 64, 1)
    assert replay.compare_release_files(files, changed) == [
        "missing dataset/forever/1.60.1.70450/mage.json",
        "unexpected dataset/forever/1.60.1.70450/extra.json",
        f"{path}: {files[path]} != {changed[path]}",
    ]


def test_junit_results_and_verdicts(tmp_path: Path) -> None:
    (tmp_path / "TEST-a.xml").write_text(
        '<testsuite name="x" tests="4">'
        '<testcase classname="io.x.OtaHistoricalReplayTest" name="passes[desktop]"/>'
        '<testcase classname="io.x.OtaHistoricalReplayTest" name="fails"><failure/></testcase>'
        '<testcase classname="io.x.OtaStagingE2ETest" name="errs"><error/></testcase>'
        '<testcase classname="io.x.OtaStagingE2ETest" name="skips"><skipped/></testcase>'
        "</testsuite>",
        encoding="utf-8",
    )
    results = replay.junit_results([tmp_path / "TEST-a.xml"])
    assert results == {
        "OtaHistoricalReplayTest.passes": Verdict.PASS,
        "OtaHistoricalReplayTest.fails": Verdict.FAIL,
        "OtaStagingE2ETest.errs": Verdict.FAIL,
        "OtaStagingE2ETest.skips": Verdict.NOT_RUN,
    }
    assert replay.combine([]) is Verdict.NOT_RUN
    assert replay.combine([Verdict.PASS, Verdict.PASS]) is Verdict.PASS
    assert replay.combine([Verdict.PASS, Verdict.NOT_RUN]) is Verdict.NOT_RUN
    assert replay.combine([Verdict.NOT_RUN, Verdict.BLOCKED]) is Verdict.BLOCKED
    assert replay.combine([Verdict.BLOCKED, Verdict.FAIL, Verdict.PASS]) is Verdict.FAIL
    # a scenario whose test did not report is never PASS
    scenarios = (replay.Scenario("X", "x", ("OtaHistoricalReplayTest.passes", "Missing.test")),)
    assert replay.scenario_verdicts(results, scenarios) == {"X": Verdict.NOT_RUN}


def test_every_scenario_names_existing_kotlin_tests() -> None:
    """A renamed Kotlin test must not silently turn a scenario into NOT RUN."""
    methods: dict[str, set[str]] = {}
    for suite in ("OtaHistoricalReplayTest", "OtaStagingE2ETest"):
        source = (KOTLIN / f"{suite}.kt").read_text(encoding="utf-8")
        methods[suite] = set(re.findall(r"@Test\s+fun (\w+)\(", source))
    ids = [s.id for s in replay.CLIENT_SCENARIOS]
    assert ids == [f"C{i}" for i in range(1, 11)] + [f"N{i}" for i in range(1, 8)]
    named = {t for s in replay.CLIENT_SCENARIOS for t in s.tests}
    for test in named:
        suite, method = test.split(".")
        assert method in methods[suite], test
    # every historical test backs at least one scenario
    assert {f"OtaHistoricalReplayTest.{m}" for m in methods["OtaHistoricalReplayTest"]} <= named


def test_a_device_check_never_counts_as_executed() -> None:
    check = script.Check
    assert script.overall([check("a", "p", "t", Verdict.PASS, "")]) is Verdict.PASS
    manual = check("M1", "android", "device", Verdict.NOT_RUN, "", automatic=False)
    assert script.overall([check("a", "p", "t", Verdict.PASS, ""), manual]) is Verdict.PASS
    blocked = check("G2", "guard", "t", Verdict.BLOCKED, "")
    assert script.overall([blocked, manual]) is Verdict.BLOCKED


# --------------------------------------------------------------------------- the entrypoint


def test_the_entrypoint_refuses_an_output_inside_git_or_not_empty(tmp_path: Path) -> None:
    assert script.main(["run", str(ROOT / "build" / "replay-out")]) == script.EXIT_USAGE
    (tmp_path / "busy").mkdir()
    (tmp_path / "busy" / "file").write_text("x", encoding="utf-8")
    assert script.main(["run", str(tmp_path / "busy")]) == script.EXIT_USAGE
    assert not (ROOT / "build" / "replay-out").exists()


def test_the_offline_phases_end_to_end(tmp_path: Path) -> None:
    """Detection, the real staging stand and the guards; preparation and client NOT RUN."""
    out = tmp_path / "replay"
    code = script.main(
        ["run", str(out), "--skip-prepare", "--skip-client", "--no-production-guard"]
    )
    report = json.loads((out / "replay-report.json").read_text(encoding="utf-8"))
    verdicts = {c["id"]: c["verdict"] for c in report["checks"]}
    assert verdicts == {
        "D1": "PASS",
        "D2": "PASS",
        "D3": "PASS",
        "P0": "NOT RUN",
        "S1": "PASS",
        "S2": "NOT RUN",
        "S3": "PASS",
        "S4": "PASS",
        "S5": "PASS",
        "K0": "NOT RUN",
        "G1": "PASS",
        "G2": "NOT RUN",
        "M1": "NOT RUN",
    }
    assert report["verdict"] == "NOT RUN" and code == script.EXIT_INCOMPLETE
    assert report["facts"]["stand"]["keyId"].startswith("tf-staging-local-")
    assert json.loads((out / "detect/after.detection.json").read_text())["action"] == "PREPARE"
    assert "| S4 | stage | the new staging catalog activates 70450 | PASS |" in (
        out / "summary.md"
    ).read_text(encoding="utf-8")


# --------------------------------------------------------------------------- production guard

CATALOG = b'{"sequence": 7}'


def _production(monkeypatch: pytest.MonkeyPatch, *answers: bytes | int | Exception) -> list[str]:
    """The public production catalog answers ``answers`` in turn (body, status or exception);
    bodies are streams, as on the wire (``fetch`` reads them with ``iter_raw``)."""
    pending, requests = list(answers), []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(f"{request.method} {request.url}")
        answer = pending.pop(0)
        if isinstance(answer, Exception):
            raise answer
        if isinstance(answer, int):
            return httpx.Response(answer, stream=httpx.ByteStream(b"unavailable"))
        return httpx.Response(200, stream=httpx.ByteStream(answer))

    monkeypatch.setattr(
        script, "_production_client", lambda: httpx.Client(transport=httpx.MockTransport(handler))
    )
    return requests


@pytest.mark.parametrize(
    ("answer", "reason"),
    [
        (httpx.ConnectTimeout("timed out"), "HTTP ConnectTimeout: timed out"),
        (httpx.ReadTimeout("timed out"), "HTTP ReadTimeout: timed out"),
        (httpx.ConnectError("no route to host"), "HTTP ConnectError: no route to host"),
        (503, "HTTP 503"),
        (404, "HTTP 404"),
        (RuntimeError("TLS stack broken"), "RuntimeError: TLS stack broken"),
    ],
)
def test_an_unreadable_production_catalog_is_recorded_not_raised(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, answer: int | Exception, reason: str
) -> None:
    requests = _production(monkeypatch, answer)
    read = script.read_production_catalog(tmp_path, "before")
    assert requests == [f"GET {script.PRODUCTION_CATALOG}"]
    assert read.sha256 is None and read.detail.startswith(reason)
    assert not (tmp_path / "guard").exists()
    crash = tmp_path / "logs" / "guard-before-crash.txt"
    assert crash.is_file() == isinstance(answer, RuntimeError)
    if crash.is_file():
        assert "RuntimeError: TLS stack broken" in crash.read_text(encoding="utf-8")


def test_a_read_production_catalog_keeps_its_body(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _production(monkeypatch, CATALOG)
    read = script.read_production_catalog(tmp_path, "after")
    assert read.sha256 == "sha256:" + hashlib.sha256(CATALOG).hexdigest()
    assert (tmp_path / "guard/production-catalog-after.json").read_bytes() == CATALOG


def test_g2_passes_only_when_both_reads_agree(tmp_path: Path) -> None:
    def g2(before: str | None, after: str | None) -> tuple[Verdict, str]:
        r = script.Replay(tmp_path)
        script.guard_production(
            r,
            script.CatalogRead("2026-10-23T10:00:00Z", before, "before detail"),
            script.CatalogRead("2026-10-23T10:10:00Z", after, "after detail"),
        )
        (check,) = r.checks
        assert check.id == "G2" and r.facts["productionGuard"]["before"]["sha256"] == before
        # whatever else passed, G2 decides whether the replay may PASS
        passed = script.Check("D1", "detect", "t", Verdict.PASS, "")
        assert script.overall([passed, check]) is check.verdict
        return check.verdict, check.detail

    a, b = "sha256:" + "a" * 64, "sha256:" + "b" * 64
    assert g2(a, a)[0] is Verdict.PASS
    for before, after in ((None, a), (a, None), (None, None)):
        verdict, detail = g2(before, after)
        assert verdict is Verdict.BLOCKED and "not read" in detail
    verdict, detail = g2(a, b)
    # an external publication between the reads: uncertain, never blamed on the replay
    assert verdict is Verdict.BLOCKED
    assert f"{a} -> {b}" in detail and "external change" in detail
    assert "not established" in detail


@pytest.mark.parametrize(
    ("answers", "unread"),
    [
        ((httpx.ConnectTimeout("timed out"), CATALOG), "before"),
        ((CATALOG, RuntimeError("CDN gone")), "after"),
    ],
)
def test_a_cdn_failure_around_the_replay_is_blocked_with_its_report(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    answers: tuple[bytes | Exception, ...],
    unread: str,
) -> None:
    """The CDN failing before the replay starts or after it ends: BLOCKED, reports written."""
    requests = _production(monkeypatch, *answers)
    out = tmp_path / "replay"
    code = script.main(["run", str(out), "--skip-prepare", "--skip-client"])
    assert len(requests) == 2
    report = json.loads((out / "replay-report.json").read_text(encoding="utf-8"))
    checks = {c["id"]: c for c in report["checks"]}
    assert checks["G2"]["verdict"] == "BLOCKED" and f"{unread} (" in checks["G2"]["detail"]
    assert checks["S4"]["verdict"] == "PASS"  # the replay itself still ran to the end
    assert report["verdict"] == "BLOCKED" and code == script.EXIT_INCOMPLETE
    guard = report["facts"]["productionGuard"]
    read = "after" if unread == "before" else "before"
    assert guard[unread]["sha256"] is None
    assert guard[read]["sha256"] == "sha256:" + hashlib.sha256(CATALOG).hexdigest()
    assert (out / f"guard/production-catalog-{read}.json").read_bytes() == CATALOG
    assert "| G2 | guard |" in (out / "summary.md").read_text(encoding="utf-8")
    if unread == "after":
        assert "RuntimeError: CDN gone" in (out / "logs/guard-after-crash.txt").read_text()


def test_the_workflow_holds_no_secret_and_cannot_publish() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    assert "secrets." not in text
    for forbidden in ("--execute", "promote", "attach-signature", "--backend", "aws ", "AWS_"):
        assert forbidden not in text, forbidden
    steps = text.split("jobs:", 1)[1]
    for forbidden in ("git push", "gh pr", "gh api", "merge"):
        assert forbidden not in steps, forbidden
    assert "permissions:\n  contents: read\n\n" in text
    assert "persist-credentials: false" in text
    assert "workflow_dispatch:" in text and "pull_request:" in text
    assert 'python scripts/ota_historical_replay.py run "$RUNNER_TEMP/replay"' in text
    # the stand's private keys never leave the runner
    assert "!${{ runner.temp }}/replay/stand/keys/*.pem" in text
    assert "!${{ runner.temp }}/replay/stand/tls/server.key" in text
