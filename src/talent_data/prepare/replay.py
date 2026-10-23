"""Historical patch replay: the 1.60.1.70374 -> 1.60.1.70450 update, end to end (M5.OTA.AUTO.2).

The real patch already happened: ``main`` carries 70450, the app bundles it as
``PRODUCT_BUNDLE`` and production serves it. This module reconstructs the moment *before*
the patch without touching any of that, by injecting the facts the existing detection reads:

* the build index: two **recorded** ``GET https://wago.tools/api/builds`` responses
  (``tests/fixtures/wago/``) read by :class:`RecordedBuildSource` through the same parser and
  game filter as :class:`~talent_data.sources.wago.source.WagoBuildSource`: on 2026-10-14 the
  latest publishable build is 70374, on 2026-10-17 70450 is listed with ``is_bgdl`` false;
* the app's ``PRODUCT_BUNDLE``: 70374, passed as the parameter the detection already takes
  (``BundledDatasets.kt`` is not read and not changed);
* production: ``ABSENT`` (production OTA did not exist before 70450), answered by
  :func:`historical_production_client` - an in-memory transport for a reserved ``.invalid``
  origin, so the real reader runs and the real production origin is never contacted.

:func:`detect_at` then runs the unchanged ``evaluate_freshness`` and ``decide``. The rest of
the replay (the preparation, the staging publication, the client) reuses the existing tools
as they are; this module also holds the pure parts of its report: release-file comparison,
JUnit results and the scenario verdicts (``PASS`` / ``FAIL`` / ``BLOCKED`` / ``NOT RUN``).
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path

import httpx

from talent_data.freshness import evaluate_freshness
from talent_data.games import GameConfig, get_game
from talent_data.prepare.detect import DetectionDocument, decide
from talent_data.prepare.orchestrate import committed_builds, main_commit
from talent_data.prepare.production import CATALOG_KEY, read_production
from talent_data.sources.base import BuildInfo
from talent_data.sources.wago.models import WagoBuildIndex
from talent_data.sources.wago.source import filter_game_builds
from talent_data.versions import ClientVersion

GAME = "forever"
BASE_BUILD = "1.60.1.70374"
PATCH_BUILD = "1.60.1.70450"
BEFORE_INDEX = Path("tests/fixtures/wago/builds-2026-10-14.json")
AFTER_INDEX = Path("tests/fixtures/wago/builds-2026-10-17.json")
#: when each index was recorded (tests/fixtures/README.md, docs/forever-dataset.md M5.DATA.4)
BEFORE_AT = datetime(2026, 10, 14, 16, 50, tzinfo=UTC)
AFTER_AT = datetime(2026, 10, 17, 18, 40, 47, tzinfo=UTC)
#: a reserved name (RFC 2606): nothing can resolve it, and only the in-memory transport answers
HISTORICAL_PRODUCTION_ORIGIN = "https://production.replay.invalid"
PRODUCTION_KEYRING = Path("docs/ota/keys/production-keyring.json")


class Verdict(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    BLOCKED = "BLOCKED"  # could not be established here (network, a missing prerequisite)
    NOT_RUN = "NOT RUN"  # deliberately not executed (an option, or needs a device)


# --------------------------------------------------------------------------- detection


class RecordedBuildSource:
    """A recorded ``/api/builds`` response as the build source of the freshness check."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.name = f"wago.tools /api/builds recorded in {path.name}"

    def list_builds(self, game: GameConfig) -> list[BuildInfo]:
        return filter_game_builds(WagoBuildIndex.model_validate_json(self.path.read_bytes()), game)


@dataclass
class HistoricalProduction:
    """The transport of :func:`historical_production_client` and what it was asked."""

    requests: list[str] = field(default_factory=list)

    def handle(self, request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        self.requests.append(f"{request.method} {url}")
        if request.method == "GET" and url == f"{HISTORICAL_PRODUCTION_ORIGIN}/{CATALOG_KEY}":
            return httpx.Response(404, stream=httpx.ByteStream(b""))
        raise AssertionError(f"the historical production origin was asked for {url}")


def historical_production_client(log: HistoricalProduction) -> httpx.Client:
    """An HTTP client on which production publishes nothing yet: the catalog answers 404
    (``ABSENT``, like the real origin before its first publication) and any other request
    fails the replay. It cannot reach a network."""
    return httpx.Client(transport=httpx.MockTransport(log.handle), follow_redirects=False)


def detect_at(
    index: Path,
    *,
    repo: Path,
    product_build: str,
    now: datetime,
    production_log: HistoricalProduction,
) -> DetectionDocument:
    """The existing detection, unchanged, over a recorded build index and historical facts."""
    from talent_data.ota.preflight import production_keyring

    game = get_game(GAME)
    product = ClientVersion.parse(product_build)
    source = RecordedBuildSource(index)
    builds = source.list_builds(game)
    freshness = evaluate_freshness(source, game, product, now)
    main = main_commit(repo)
    keyring = production_keyring(repo / PRODUCTION_KEYRING)
    with historical_production_client(production_log) as client:
        production = read_production(
            client, origin=HISTORICAL_PRODUCTION_ORIGIN, keyring=keyring, game=game.key, now=now
        )
    return decide(
        game=game,
        freshness=freshness,
        builds=builds,
        production=production,
        product_build=product,
        committed_builds=committed_builds(repo, main, game.key),
        main_commit=main,
        now=now,
    )


# --------------------------------------------------------------------------- releases


def release_files(manifest: bytes) -> dict[str, tuple[str, int]]:
    """``path -> (sha256 ref, byteLength)`` of a release manifest."""
    document = json.loads(manifest)
    return {f["path"]: (f["sha256"], f["byteLength"]) for f in document["files"]}


def compare_release_files(
    expected: Mapping[str, tuple[str, int]], actual: Mapping[str, tuple[str, int]]
) -> list[str]:
    """Every difference between two releases' file sets (empty: the same files and bytes)."""
    problems = [f"missing {path}" for path in sorted(expected.keys() - actual.keys())]
    problems += [f"unexpected {path}" for path in sorted(actual.keys() - expected.keys())]
    problems += [
        f"{path}: {expected[path]} != {actual[path]}"
        for path in sorted(expected.keys() & actual.keys())
        if expected[path] != actual[path]
    ]
    return problems


# --------------------------------------------------------------------------- the client's results


def junit_results(reports: Iterable[Path]) -> dict[str, Verdict]:
    """``Class.method -> verdict`` of JUnit XML reports (skipped tests are ``NOT RUN``)."""
    results: dict[str, Verdict] = {}
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            # Gradle's KMP test task appends the target: "method[desktop]"
            method = re.sub(r"\[[^\]]*\]$", "", case.get("name", ""))
            name = f"{case.get('classname', '').rsplit('.', 1)[-1]}.{method}"
            if case.find("failure") is not None or case.find("error") is not None:
                results[name] = Verdict.FAIL
            elif case.find("skipped") is not None:
                results[name] = Verdict.NOT_RUN
            else:
                results[name] = Verdict.PASS
    return results


def combine(verdicts: Iterable[Verdict]) -> Verdict:
    """One verdict for several pieces of evidence: any FAIL fails; then BLOCKED; then NOT RUN
    (nothing proved it); PASS only when every piece passed. No evidence is ``NOT RUN``."""
    seen = set(verdicts)
    for verdict in (Verdict.FAIL, Verdict.BLOCKED, Verdict.NOT_RUN):
        if verdict in seen:
            return verdict
    return Verdict.PASS if seen else Verdict.NOT_RUN


@dataclass(frozen=True)
class Scenario:
    id: str
    title: str
    tests: tuple[str, ...]  # ``Class.method`` of the Kotlin client tests that prove it


H = "OtaHistoricalReplayTest"
S = "OtaStagingE2ETest"
MOVE = f"{H}.theClientMovesFrom70374To70450"
INTERRUPTED = f"{H}.anInterruptedPatchDownloadResumesAfterARestart"
OFFLINE = f"{H}.noNetworkWhenThePatchAppearsKeeps70374"
CORRUPT = f"{S}.aCorruptBlobManifestOrSignatureChangesNothing"
EDITOR = f"{S}.anEditorOpenDuringActivationKeepsReadingItsOwnRelease"
LOST = f"{S}.aConnectionLostMidDownloadResumesWithTheVerifiedFiles"
DEATH = f"{S}.processDeathDuringTheInstallRecoversAndResumes"
STAGED = f"{S}.aToBRollbackAndAnOfflineColdStartOnThePublishersBytes"  # level 2, offline
EXPIRED = f"{S}.anExpiredCatalogIsRefused"

#: the client and negative scenarios of the task, each with the tests that prove it
CLIENT_SCENARIOS: tuple[Scenario, ...] = (
    Scenario("C1", "before the new catalog the client runs on 70374", (MOVE,)),
    Scenario("C2", "the updated staging catalog makes the client find 70450", (MOVE,)),
    Scenario("C3", "the files 70374 does not have are downloaded over HTTPS", (MOVE,)),
    Scenario("C4", "ECDSA signature of the catalog and SHA-256 of every file", (MOVE, CORRUPT)),
    Scenario("C5", "70450 is activated", (MOVE,)),
    Scenario("C6", "a new editor uses the new data", (MOVE,)),
    Scenario("C7", "an editor opened before keeps its 70374 snapshot", (MOVE, EDITOR)),
    Scenario("C8", "saved 70374 builds stay available", (MOVE,)),
    Scenario("C9", "the update survives an app restart", (MOVE,)),
    Scenario("C10", "an interrupted download recovers safely", (INTERRUPTED, LOST, DEATH)),
    Scenario("N1", "invalid catalog signature", (CORRUPT,)),
    Scenario("N2", "SHA-256 mismatch (manifest and file)", (CORRUPT,)),
    Scenario("N3", "no network", (OFFLINE, STAGED)),
    Scenario("N4", "incomplete download", (INTERRUPTED, LOST)),
    Scenario("N5", "a restart does not download verified files again", (INTERRUPTED, MOVE)),
    Scenario("N6", "an invalid or incompatible release", (CORRUPT, STAGED, EXPIRED)),
    Scenario("N7", "a check without a new update changes nothing", (MOVE,)),
)


def scenario_verdicts(
    results: Mapping[str, Verdict], scenarios: Sequence[Scenario] = CLIENT_SCENARIOS
) -> dict[str, Verdict]:
    """Each scenario's verdict from its tests; a test without a result is ``NOT RUN``."""
    return {
        s.id: combine(results.get(test, Verdict.NOT_RUN) for test in s.tests) for s in scenarios
    }
