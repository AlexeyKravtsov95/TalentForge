"""OTA preparation orchestration (M5.OTA.AUTO.1), fully offline.

* ``decide``: every action over real freshness evaluations (an in-memory build index) and
  production states, including idempotency through kept artifact names;
* the production reader: a signed publication served by an in-memory origin (TEST keys
  under ``tf-prod-*`` ids, as the preflight tests do), absent, unreachable, wrongly signed
  and expired catalogs, and the verified mirror;
* ``prepare``: the whole sequence over a temporary git repository with a fake command
  runner, fake gates and fake production - outcomes PREPARED / BLOCKED / UNKNOWN, skipped
  steps, the scope guard, the kept files and that the source checkout is never touched;
* the CLI's typed "source unavailable" exit code and the workflow's safety properties.
"""

from __future__ import annotations

import io
import json
import subprocess
import sys
from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from PIL import Image

from talent_data.cli import main as cli_main
from talent_data.freshness import evaluate_freshness
from talent_data.games import get_game
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import Gate, GateResult, GateStatus
from talent_data.ota.models import CatalogRelease, Channel, OtaCatalog
from talent_data.ota.store import BlobStore, LocalDirectoryStore
from talent_data.pipeline import PipelineError
from talent_data.prepare import orchestrate
from talent_data.prepare.detect import (
    Action,
    BaseKind,
    DetectionDocument,
    PriorListing,
    artifact_name,
    decide,
    read_detection,
)
from talent_data.prepare.orchestrate import (
    Readiness,
    ReleaseEvaluation,
    Services,
    StepKind,
    StepOutcome,
    candidate_path_allowed,
    check_steps,
    pipeline_steps,
    prepare,
    run_command,
    run_steps,
    step_findings,
)
from talent_data.prepare.production import (
    CATALOG_KEY,
    ProductionState,
    ProductionStatus,
    mirror_production,
    read_production,
)
from talent_data.sources.base import BuildInfo, SourceError
from talent_data.sources.http import DownloadError
from talent_data.versions import ClientVersion
from tests.unit.ota.test_ota_preflight import KEYRING, ORIGIN, FakeOrigin, publication
from tests.unit.visuals.test_reproduction import (
    ICON,
    LINUX_CONVERTER,
    LINUX_PNG,
    with_icon,
)
from tests.unit.visuals.test_reproduction import committed_files as committed_visuals

ROOT = Path(__file__).resolve().parents[2]
GAME = get_game("forever")
NOW = datetime(2026, 10, 22, 6, 30, tzinfo=UTC)
MAIN = "a" * 40
PRODUCT = ClientVersion.parse("1.60.1.70450")
NEW = ClientVersion.parse("1.60.1.70500")
COMMITTED = {"1.60.1.70009", "1.60.1.70374", "1.60.1.70450"}


# --------------------------------------------------------------------------- facts


def info(version: str, created: str, bgdl: str | None = "false") -> BuildInfo:
    extra = {} if bgdl is None else {"is_bgdl": bgdl}
    return BuildInfo(
        version=ClientVersion.parse(version),
        product="wow_classic_beta",
        created_at=datetime.fromisoformat(created),
        extra=extra,
    )


class Index:
    """The wago.tools build index, in memory (``None``: unreachable)."""

    name = "wago.tools"

    def __init__(self, builds: list[BuildInfo] | None) -> None:
        self.builds = builds

    def list_builds(self, game: Any) -> list[BuildInfo]:
        if self.builds is None:
            raise SourceError("GET https://wago.tools/api/builds failed after 4 attempts")
        return self.builds


INDEX = [
    info("1.60.1.70374", "2026-10-14 01:00:00"),
    info("1.60.1.70450", "2026-10-16 22:29:05"),
    info("1.60.1.70500", "2026-10-21 03:00:00"),
]


def release(release_id: str) -> CatalogRelease:
    return CatalogRelease(
        release_id=release_id,
        client_build=release_id.rsplit("-r", 1)[0],
        min_data_level=1,
        manifest_sha256="sha256:" + "1" * 64,
        manifest_byte_length=100,
        total_byte_length=1000,
        published_at=NOW,
    )


def published(*release_ids: str, active: str | None = None) -> ProductionState:
    catalog = OtaCatalog(
        schema_version=1,
        game="forever",
        channel=Channel.PRODUCTION,
        sequence=len(release_ids),
        issued_at=NOW,
        expires_at=NOW + timedelta(days=45),
        active_release_id=active or release_ids[-1],
        releases=[release(r) for r in release_ids],
    )
    return ProductionState(
        ProductionStatus.PUBLISHED,
        ORIGIN,
        "verified",
        catalog=catalog,
        envelope=b"{}",
        payload_sha256="sha256:" + "2" * 64,
    )


ABSENT = ProductionState(ProductionStatus.ABSENT, ORIGIN, "no production catalog (HTTP 403)")
UNREACHABLE = ProductionState(ProductionStatus.UNKNOWN, ORIGIN, "ConnectError: refused")


def detect(
    builds: list[BuildInfo] | None = INDEX,
    production: ProductionState | None = None,
    **options: Any,
) -> DetectionDocument:
    freshness = evaluate_freshness(Index(builds), GAME, PRODUCT, NOW)
    return decide(
        game=GAME,
        freshness=freshness,
        builds=builds,
        production=published("1.60.1.70450-r1") if production is None else production,
        product_build=PRODUCT,
        committed_builds=options.pop("committed", COMMITTED),
        main_commit=options.pop("main", MAIN),
        now=NOW,
        **options,
    )


# --------------------------------------------------------------------------- detection


def test_a_new_publishable_build_is_prepared_against_the_active_production_release() -> None:
    d = detect()
    assert d.action is Action.PREPARE, d.reason
    assert d.candidate_build == "1.60.1.70500"
    assert (d.base_build, d.base_kind, d.base_release_id) == (
        "1.60.1.70450",
        BaseKind.PRODUCTION_RELEASE,
        "1.60.1.70450-r1",
    )
    assert d.revision == 1
    assert d.preparation_key == f"forever-1.60.1.70500-r1-from-1.60.1.70450-r1-main-{MAIN[:12]}"
    assert d.production.status is ProductionStatus.PUBLISHED
    assert d.production.active_release_id == "1.60.1.70450-r1"


def test_the_bundled_product_is_the_base_when_nothing_is_published() -> None:
    d = detect(production=ABSENT)
    assert d.action is Action.PREPARE
    assert (d.base_build, d.base_kind, d.base_release_id) == (
        str(PRODUCT),
        BaseKind.PRODUCT_BUNDLE,
        None,
    )


def test_an_older_active_release_does_not_hide_the_newer_bundle() -> None:
    d = detect(production=published("1.60.1.70374-r1"))
    assert d.action is Action.PREPARE
    assert (d.base_build, d.base_kind) == (str(PRODUCT), BaseKind.PRODUCT_BUNDLE)


def test_a_build_the_verified_production_catalog_lists_is_not_prepared_again() -> None:
    d = detect(production=published("1.60.1.70450-r1", "1.60.1.70500-r1"))
    assert d.action is Action.NO_ACTION
    assert "already published" in d.reason
    assert d.preparation_key is None


def test_an_unverifiable_catalog_never_makes_a_build_published() -> None:
    d = detect(production=UNREACHABLE)
    assert d.action is Action.PREPARE  # preparing publishes nothing
    assert d.production.status is ProductionStatus.UNKNOWN
    assert d.base_kind is BaseKind.PRODUCT_BUNDLE
    assert "not treated as published" in d.reason


def test_the_bundled_product_build_needs_no_ota_release() -> None:
    builds = INDEX[:2]  # 70450 is the newest publishable build
    for production in (ABSENT, UNREACHABLE):
        d = detect(builds, production)
        assert d.action is Action.NO_ACTION, d.reason
        assert "PRODUCT_BUNDLE" in d.reason


def test_an_inconclusive_build_index_is_unknown_never_no_action() -> None:
    assert detect(None).action is Action.UNKNOWN  # unreachable
    # no newer publishable build, but a newer one whose is_bgdl is missing
    unknown_flag = [*INDEX[:2], info("1.60.1.70600", "2026-10-22 01:00:00", bgdl=None)]
    d = detect(unknown_flag)
    assert d.action is Action.UNKNOWN
    assert d.source_status.value == "UNKNOWN"


def test_a_newer_background_download_build_is_not_a_candidate() -> None:
    d = detect([*INDEX[:2], info("1.60.1.70500", "2026-10-21 03:00:00", bgdl="true")], ABSENT)
    assert d.action is Action.NO_ACTION
    assert d.candidate_build == str(PRODUCT)


def test_a_requested_build_must_be_listed_and_publishable() -> None:
    missing = detect(requested_build=ClientVersion.parse("1.60.1.70999"))
    assert missing.action is Action.UNKNOWN
    assert "not a Forever build the index lists" in missing.reason
    bgdl = [*INDEX, info("1.60.1.70600", "2026-10-22 01:00:00", bgdl="true")]
    refused = detect(bgdl, requested_build=ClientVersion.parse("1.60.1.70600"))
    assert refused.action is Action.UNKNOWN and "not publishable" in refused.reason
    unreachable = detect(None, requested_build=NEW)
    assert unreachable.action is Action.UNKNOWN
    chosen = detect(requested_build=NEW, requested_base=ClientVersion.parse("1.60.1.70374"))
    assert chosen.action is Action.PREPARE
    assert (chosen.base_build, chosen.base_kind) == ("1.60.1.70374", BaseKind.REQUESTED)


def test_force_prepares_a_published_build_as_the_next_revision() -> None:
    production = published(
        "1.60.1.70450-r1", "1.60.1.70500-r1", "1.60.1.70500-r2", active="1.60.1.70450-r1"
    )
    d = detect(production=production, requested_build=NEW, force=True)
    assert d.action is Action.PREPARE
    assert d.revision == 3
    assert d.forced and "forced" in d.reason


def test_a_base_without_a_committed_dataset_is_unknown() -> None:
    d = detect(committed={"1.60.1.70009"})
    assert d.action is Action.UNKNOWN
    assert "no committed dataset" in d.reason


def test_a_prepared_preparation_of_the_same_inputs_is_not_repeated() -> None:
    key = detect().preparation_key
    assert key is not None
    kept = [artifact_name(key, "BLOCKED"), artifact_name(key, "PREPARED"), "freshness-report"]
    d = detect(prior_artifacts=kept)
    assert d.action is Action.NO_ACTION
    assert d.prior_artifact == artifact_name(key, "PREPARED")
    assert d.prior_listing is PriorListing.LISTED
    assert detect(prior_artifacts=kept, force=True).action is Action.PREPARE


@pytest.mark.parametrize("status", ["BLOCKED", "UNKNOWN", "FAILED"])
def test_a_kept_failure_is_prepared_again_on_the_next_run(status: str) -> None:
    """Right after a patch the source can be incomplete; a kept BLOCKED (or UNKNOWN, or a
    crashed run) must not hide the build for the artifact's whole retention."""
    key = detect().preparation_key
    assert key is not None
    d = detect(prior_artifacts=[artifact_name(key, status)])
    assert d.action is Action.PREPARE
    assert d.prior_artifact == artifact_name(key, status)
    assert f"preparing again after {artifact_name(key, status)}" in d.reason


def test_unknown_or_other_inputs_are_prepared_again() -> None:
    key = detect().preparation_key
    assert key is not None
    other_main = key.replace(MAIN[:12], "b" * 12)
    kept = [artifact_name(other_main, "PREPARED")]  # main moved on: other inputs
    assert detect(prior_artifacts=kept).action is Action.PREPARE
    failed = detect(prior_artifacts=None, unlisted=PriorListing.UNAVAILABLE)
    assert failed.action is Action.PREPARE
    assert failed.prior_listing is PriorListing.UNAVAILABLE


def test_the_detection_document_round_trips_strictly(tmp_path: Path) -> None:
    path = tmp_path / "detection.json"
    path.write_text(detect().to_json(), encoding="utf-8")
    assert read_detection(path) == detect()
    doc = json.loads(path.read_text())
    for broken in (
        {**doc, "surprise": 1},
        {**doc, "revision": "1"},
        {**doc, "action": "MAYBE"},
        {k: v for k, v in doc.items() if k != "candidateBuild"},
    ):
        path.write_text(json.dumps(broken), encoding="utf-8")
        with pytest.raises(ValueError):
            read_detection(path)


# --------------------------------------------------------------------------- production


def read(origin: FakeOrigin, now: datetime = NOW + timedelta(hours=1)) -> ProductionState:
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        return read_production(client, origin=ORIGIN, keyring=KEYRING, game="forever", now=now)


# the publication of the preflight tests is issued at its own NOW (2026-10-18 12:00 UTC)
SERVED_AT = datetime(2026, 10, 18, 13, 0, tzinfo=UTC)


def test_a_signed_production_catalog_is_published() -> None:
    state = read(FakeOrigin(publication()), SERVED_AT)
    assert state.status is ProductionStatus.PUBLISHED, state.reason
    assert state.active is not None and state.active.client_build == "1.60.1.70374"
    assert state.payload_sha256 and state.payload_sha256.startswith("sha256:")
    assert state.releases_of("1.60.1.70374") and not state.releases_of("1.60.1.70450")


def test_absent_unreachable_unsigned_and_expired_catalogs() -> None:
    assert read(FakeOrigin(), SERVED_AT).status is ProductionStatus.ABSENT  # anonymous S3: 403

    down = FakeOrigin(publication())
    down.failures[CATALOG_KEY] = httpx.ConnectError("connection refused")
    assert read(down, SERVED_AT).status is ProductionStatus.UNKNOWN

    error = FakeOrigin(publication())
    error.edge_overrides[CATALOG_KEY] = (503, {}, b"")
    assert read(error, SERVED_AT).status is ProductionStatus.UNKNOWN

    foreign = read(FakeOrigin(publication(signer="tf-prod-2026x")), SERVED_AT)
    assert foreign.status is ProductionStatus.UNKNOWN and foreign.catalog is None
    assert "not treated as published" in foreign.reason

    expired = read(FakeOrigin(publication()), SERVED_AT + timedelta(days=46))
    assert expired.status is ProductionStatus.UNKNOWN


def test_the_mirror_holds_exactly_what_production_serves(tmp_path: Path) -> None:
    objects = publication()
    origin = FakeOrigin(objects)
    state = read(origin, SERVED_AT)
    store = LocalDirectoryStore(tmp_path / "mirror")
    with httpx.Client(transport=httpx.MockTransport(origin.handler)) as client:
        written = mirror_production(client, state, store, game="forever")
    assert written == len(objects) - 1
    for key, data in objects.items():
        assert store.get(key) == data
    assert set(origin.methods) == {"GET"}

    tampered = dict(objects)
    victim = next(k for k, v in objects.items() if k != CATALOG_KEY and v[:1] == b"{")
    tampered[victim] = objects[victim] + b" "
    bad = FakeOrigin(tampered)
    with (
        httpx.Client(transport=httpx.MockTransport(bad.handler)) as client,
        pytest.raises(OtaPublisherError, match="did not verify"),
    ):
        mirror_production(
            client, read(bad, SERVED_AT), LocalDirectoryStore(tmp_path / "bad"), game="forever"
        )
    with httpx.Client() as client, pytest.raises(OtaPublisherError, match="nothing to mirror"):
        mirror_production(client, ABSENT, store, game="forever")


# --------------------------------------------------------------------------- steps


def test_the_steps_are_the_existing_pipeline_commands() -> None:
    steps = pipeline_steps("forever", "1.60.1.70500", "1.60.1.70450", ["ruRU"])
    assert [s.id for s in steps] == [
        "fetch",
        "tooltips-fetch",
        "localization-fetch-ruRU",
        "normalize",
        "manifest",
        "visuals-fetch",  # after normalize: it resolves icons from the dataset (issue #78)
        "diff",
        "visuals-normalize",
        "visuals-check",
        "tooltips-normalize",
        "tooltips-check",
        "localization-normalize-ruRU",
        "localization-check-ruRU",
    ]
    for step in steps:
        assert step.argv[:3] == (sys.executable, "-m", "talent_data")
        # only the fetch steps reach the network; every producing step reads the cache only
        assert (step.kind is StepKind.SOURCE) == ("fetch" in step.argv[3:5])
        if step.kind is StepKind.PIPELINE and not {"check", "manifest"} & set(step.argv):
            assert "--offline" in step.argv, step.id  # check/manifest never touch the network
    by_id = {s.id: s.argv for s in steps}
    assert by_id["normalize"][-3:] == ("--class", "all", "--offline")  # all nine classes
    assert "--expect-complete" in by_id["manifest"]
    assert by_id["diff"][-5:] == ("--from", "1.60.1.70450", "--to", "1.60.1.70500", "--offline")
    checks = [s.argv[1:] for s in check_steps()]
    assert ("scripts/sync_mobile_datasets.py", "--check") in checks
    assert ("-m", "pytest", "-q", "-p", "no:cacheprovider") in checks


#: what each pipeline step reads, by the step that produces it (from the commands' code):
#: ``visuals fetch``/``normalize``, ``tooltips normalize`` and ``localization normalize``
#: load the normalized dataset (``visuals.pipeline.load_canonical_dataset``),
#: ``localization normalize`` also the published tooltips; the fetches of tooltips and
#: localization only download tables
STEP_NEEDS: dict[str, set[str]] = {
    "fetch": set(),
    "tooltips-fetch": set(),
    "localization-fetch-ruRU": set(),
    "normalize": {"fetch"},
    "manifest": {"normalize"},
    "visuals-fetch": {"normalize", "manifest"},
    "diff": {"normalize"},
    "visuals-normalize": {"normalize", "visuals-fetch"},
    "visuals-check": {"visuals-normalize"},
    "tooltips-normalize": {"normalize", "tooltips-fetch"},
    "tooltips-check": {"tooltips-normalize"},
    "localization-normalize-ruRU": {
        "normalize",
        "tooltips-normalize",
        "localization-fetch-ruRU",
    },
    "localization-check-ruRU": {"localization-normalize-ruRU", "tooltips-normalize"},
}


def test_every_step_runs_after_the_steps_it_reads() -> None:
    order = [s.id for s in pipeline_steps("forever", "1.60.1.70500", "1.60.1.70450", ["ruRU"])]
    assert set(order) == set(STEP_NEEDS)
    for position, step in enumerate(order):
        missing = STEP_NEEDS[step] - set(order[:position])
        assert not missing, f"{step} runs before {sorted(missing)}"


def test_visuals_fetch_reads_the_normalized_dataset_and_the_other_fetches_do_not(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The dependency behind issue #78, on the real code: without the candidate's
    normalized dataset, icon resolution stops before reading a single table, while the
    tooltip and localization fetches only ask the source for tables."""
    from talent_data.tooltips.pipeline import fetch_tooltip_tables
    from talent_data.visuals.pipeline import resolve_build

    class Tables:
        def __init__(self) -> None:
            self.asked: list[str] = []

        def ensure_table(self, version: ClientVersion, table: str) -> None:
            self.asked.append(table)

        def load_table(self, version: ClientVersion, table: str) -> Any:
            self.asked.append(table)
            raise AssertionError("no table is loaded without the dataset")

    monkeypatch.setenv("TALENT_DATA_ROOT", str(tmp_path))  # no data/normalized at all
    version = ClientVersion.parse("1.60.1.70532")
    source = Tables()
    with pytest.raises(PipelineError, match=r"no normalized dataset for 1\.60\.1\.70532"):
        resolve_build(source, GAME, version)  # type: ignore[arg-type]
    assert source.asked == []
    assert fetch_tooltip_tables(source, version)  # type: ignore[arg-type]
    assert source.asked  # tables only: no dataset needed


ISSUE_78_ORDER = (  # the order of issue #78's run: visuals-fetch second
    "fetch",
    "visuals-fetch",
    "tooltips-fetch",
    "localization-fetch-ruRU",
    "normalize",
    "manifest",
    "diff",
)


def test_the_issue_78_order_fails_on_a_new_build_and_the_fixed_order_does_not(
    tmp_path: Path,
) -> None:
    """Issue #78: on a build main does not carry yet, ``visuals fetch`` before ``normalize``
    found no dataset and blocked the run; everything after it was skipped."""
    by_id = {s.id: s for s in pipeline_steps("forever", BUILD, BASE)}
    old = run_steps(
        [by_id[i] for i in ISSUE_78_ORDER],
        FakeRunner(),
        cwd=tmp_path / "old",
        logs=tmp_path / "old-logs",
        stop_on_failure=True,
    )
    outcomes = {r.step.id: r.outcome for r in old}
    assert outcomes["fetch"] is StepOutcome.PASSED
    assert outcomes["visuals-fetch"] is StepOutcome.FAILED
    assert {outcomes[i] for i in ISSUE_78_ORDER[2:]} == {StepOutcome.SKIPPED}
    assert "no normalized dataset" in (tmp_path / "old-logs/visuals-fetch.log").read_text()
    assert [f.source for f in step_findings(old)] == ["visuals-fetch"]

    fixed = run_steps(
        pipeline_steps("forever", BUILD, BASE),
        FakeRunner(),
        cwd=tmp_path / "fixed",
        logs=tmp_path / "fixed-logs",
        stop_on_failure=True,
    )
    assert {r.outcome for r in fixed} == {StepOutcome.PASSED}


@pytest.mark.parametrize(
    ("path", "allowed"),
    [
        ("data/normalized/forever/1.60.1.70500/mage.json", True),
        ("data/visuals/forever/1.60.1.70500/icons/123.png", True),
        ("data/localization/forever/1.60.1.70500/ruRU/mage.json", True),
        ("artifacts/diff/forever-all-1.60.1.70450-1.60.1.70500.json", True),
        ("artifacts/tooltips/forever-1.60.1.70500-tooltip-coverage.json", True),
        ("docs/research/forever-1.60.1.70500-ruRU-coverage.md", True),
        ("data/normalized/forever/1.60.1.70450/mage.json", False),  # the base / the bundle
        ("artifacts/diff/forever-all-1.60.1.70374-1.60.1.70500.json", False),
        ("mobile/composeApp/src/commonMain/composeResources/files/x.json", False),
        ("scripts/mobile_bundles.py", False),
        ("docs/research/forever-1.60.1.70450-ruRU-coverage.md", False),
    ],
)
def test_the_scope_guard(path: str, allowed: bool) -> None:
    assert (
        candidate_path_allowed(path, game="forever", build="1.60.1.70500", base="1.60.1.70450")
        is allowed
    )


def test_run_command_runs_in_the_clone_with_its_own_code(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("TALENT_DATA_ROOT", "/elsewhere")
    script = (
        "import os, sys; print(os.environ.get('TALENT_DATA_ROOT')); "
        "print(os.environ['PYTHONPATH'].split(os.pathsep)[0]); sys.exit(7)"
    )
    log = tmp_path / "logs" / "x.log"
    assert run_command((sys.executable, "-c", script), tmp_path, log) == 7
    lines = log.read_text().splitlines()
    assert lines[0].startswith("$ ")
    assert lines[1:] == ["None", str(tmp_path / "src")]


# --------------------------------------------------------------------------- prepare

BUILD, BASE = "1.60.1.70500", "1.60.1.70450"


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True
    ).stdout.strip()


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """A checkout whose origin/main carries the base build's dataset."""
    root = tmp_path / "repo"
    (root / f"data/normalized/forever/{BASE}").mkdir(parents=True)
    (root / f"data/normalized/forever/{BASE}/manifest.json").write_text("{}\n")
    (root / "README.md").write_text("checkout\n")
    git(tmp_path, "init", "--quiet", "-b", "main", str(root))
    git(root, "add", "--all")
    git(
        root,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        "-m",
        "main",
    )
    git(root, "update-ref", "refs/remotes/origin/main", "HEAD")
    return root


STEP_IDS = {s.argv: s.id for s in [*pipeline_steps("forever", BUILD, BASE), *check_steps()]}


class FakeRunner:
    """Plays the pipeline: each step "writes" its files and exits with a chosen code."""

    def __init__(
        self,
        codes: dict[str, int] | None = None,
        stray: bool = False,
        manifest: bytes = b"{}\n",
        visuals: dict[str, bytes] | None = None,
    ) -> None:
        self.codes = codes or {}
        self.stray = stray
        self.manifest = manifest  # what ``normalize`` writes as the dataset manifest
        self.visuals = visuals or {}  # what ``visuals normalize`` writes, by relative path
        self.calls: list[str] = []

    def __call__(self, argv: Sequence[str], cwd: Path, log: Path) -> int:
        step = STEP_IDS[tuple(argv)]
        self.calls.append(step)
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(f"$ {' '.join(argv)}\n")
        dataset = cwd / f"data/normalized/forever/{BUILD}/manifest.json"
        if "normalize" in STEP_NEEDS.get(step, set()) and not dataset.is_file():
            # what the real commands do: they read the normalized dataset first
            with log.open("a") as out:
                out.write(f"error: no normalized dataset for {BUILD} ({dataset})\n")
            return cli_main.EXIT_FAILURE
        if step == "normalize":
            target = cwd / f"data/normalized/forever/{BUILD}"
            target.mkdir(parents=True, exist_ok=True)
            (target / "manifest.json").write_bytes(self.manifest)
            if self.stray:
                (cwd / "mobile.txt").write_text("not the candidate's\n")
        if step == "visuals-normalize":
            for name, data in self.visuals.items():
                target = cwd / f"data/visuals/forever/{BUILD}/{name}"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        if step == "tooltips-normalize":
            (cwd / "docs/research").mkdir(parents=True, exist_ok=True)
            (cwd / f"docs/research/forever-{BUILD}-tooltip-coverage.md").write_text("ok\n")
        return self.codes.get(step, 0)


def gates(**statuses: GateStatus) -> list[GateResult]:
    defaults = {"G1": GateStatus.STOP, "G7": GateStatus.FLAG, **statuses}
    return [
        GateResult(Gate(g), defaults.get(g, GateStatus.PASS), f"{g} detail")
        for g in ("G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8")
    ]


def services(
    runner: FakeRunner,
    *,
    production: ProductionState | None = None,
    results: list[GateResult] | None = None,
    evaluate_error: Exception | None = None,
) -> tuple[Services, list[dict[str, Any]]]:
    evaluated: list[dict[str, Any]] = []
    state = published("1.60.1.70450-r1") if production is None else production

    def evaluate(workspace: Path, **kwargs: Any) -> ReleaseEvaluation:
        evaluated.append({"workspace": workspace, **kwargs})
        if evaluate_error is not None:
            raise evaluate_error
        manifest = kwargs["out"] / f"ota/{BUILD}-r1/release.json"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text("{}\n")
        return ReleaseEvaluation(
            release_id=f"{BUILD}-r1",
            manifest_sha256="sha256:" + "3" * 64,
            manifest_path=manifest,
            gates=gates() if results is None else results,
            review_report="review\n",
            on_main=False,
        )

    def mirror(state: ProductionState, store: BlobStore) -> int:
        store.put(CATALOG_KEY, b"{}", cache_control="public, max-age=300")
        return 0

    ticks = iter(NOW + timedelta(seconds=i) for i in range(1000))
    return (
        Services(
            run=runner,
            read_production=lambda: state,
            mirror=mirror,
            evaluate=evaluate,
            clock=lambda: next(ticks),
        ),
        evaluated,
    )


def run_prepare(
    repo: Path, tmp_path: Path, svc: Services, detection: DetectionDocument | None = None
) -> tuple[orchestrate.PrepareReport, Path]:
    d = detection or detect(main=git(repo, "rev-parse", "HEAD"))
    out = tmp_path / "out"
    report = prepare(d, repo=repo, out=out, workspace=tmp_path / "ws" / "workspace", services=svc)
    return report, out


def kinds(report: orchestrate.PrepareReport) -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for f in report.findings:
        found.setdefault(f.kind.value, set()).add(f.source)
    return found


def test_a_clean_preparation_is_prepared_but_never_publishable(repo: Path, tmp_path: Path) -> None:
    head, status = git(repo, "rev-parse", "HEAD"), git(repo, "status", "--porcelain")
    runner = FakeRunner()
    svc, evaluated = services(runner)
    report, out = run_prepare(repo, tmp_path, svc)

    assert report.status is Readiness.PREPARED
    assert orchestrate.EXIT_CODES[report.status] == 0
    assert runner.calls == list(STEP_IDS.values())
    assert kinds(report) == {"REQUIRED": {"G1", "G7", "staging", "production"}}
    # the publisher ran on the local candidate commit with the verified production state
    (call,) = evaluated
    assert call["commit"] == report.candidate_commit and call["revision"] == 1
    assert call["live"] is not None and call["live"].active_release_id == "1.60.1.70450-r1"
    ws = call["workspace"]
    git(ws, "merge-base", "--is-ancestor", head, report.candidate_commit or "")
    assert git(ws, "branch", "--list", f"ota-prepare/{BUILD}")

    doc = json.loads((out / "report.json").read_text())
    assert doc["kind"] == "ota-prepare-report" and doc["status"] == "PREPARED"
    assert doc["publishable"] is False
    key = detect(main=head).preparation_key
    assert doc["artifactName"] == artifact_name(key or "", "PREPARED")
    assert {g["gate"]: g["status"] for g in doc["gates"]}["G1"] == "STOP"
    for kept in (
        "detection.json",
        "candidate.bundle",
        f"data/normalized/forever/{BUILD}/manifest.json",
        f"docs/research/forever-{BUILD}-tooltip-coverage.md",
        "logs/fetch.log",
        "logs/pytest.log",
        f"ota/{BUILD}-r1/release.json",
        "ota/review-report.txt",
    ):
        assert kept in doc["files"], kept
        assert (out / kept).is_file()
    assert "Not publishable from this run" in (out / "summary.md").read_text()
    # the source checkout is untouched: same HEAD, same tree, no candidate branch
    assert git(repo, "rev-parse", "HEAD") == head
    assert git(repo, "status", "--porcelain") == status
    assert git(repo, "branch", "--list", "ota-prepare/*") == ""


def test_an_unreachable_source_is_unknown_and_stops_the_pipeline(
    repo: Path, tmp_path: Path
) -> None:
    runner = FakeRunner({"visuals-fetch": cli_main.EXIT_SOURCE_UNAVAILABLE})
    svc, evaluated = services(runner)
    report, out = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.UNKNOWN
    assert orchestrate.EXIT_CODES[report.status] == 4
    order = list(STEP_IDS.values())
    assert runner.calls == order[: order.index("visuals-fetch") + 1]
    outcomes = {r.step.id: r.outcome for r in report.steps}
    assert outcomes["normalize"] is StepOutcome.PASSED
    assert outcomes["visuals-fetch"] is StepOutcome.SOURCE_UNAVAILABLE
    assert outcomes["diff"] is StepOutcome.SKIPPED
    assert not evaluated and report.candidate_commit is None
    assert kinds(report) == {"UNKNOWN": {"visuals-fetch"}}
    assert (out / "logs/visuals-fetch.log").is_file() and (out / "report.json").is_file()


@pytest.mark.parametrize("step", ["fetch", "normalize", "manifest", "tooltips-check"])
def test_a_failed_pipeline_step_blocks(repo: Path, tmp_path: Path, step: str) -> None:
    runner = FakeRunner({step: 1})
    svc, evaluated = services(runner)
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.BLOCKED
    assert orchestrate.EXIT_CODES[report.status] == 3
    assert runner.calls[-1] == step and not evaluated
    assert kinds(report) == {"BLOCKER": {step}}


def test_exit_5_of_an_offline_step_is_a_failure_not_unknown(repo: Path, tmp_path: Path) -> None:
    report, _ = run_prepare(repo, tmp_path, services(FakeRunner({"normalize": 5}))[0])
    assert report.status is Readiness.BLOCKED


def test_failed_regression_checks_block_and_every_check_still_runs(
    repo: Path, tmp_path: Path
) -> None:
    runner = FakeRunner({"pytest": 1, "sync-datasets-check": 1})
    report, _ = run_prepare(repo, tmp_path, services(runner)[0])
    assert report.status is Readiness.BLOCKED
    assert runner.calls[-5:] == [s.id for s in check_steps()]
    assert kinds(report)["BLOCKER"] == {"pytest", "sync-datasets-check"}


@pytest.mark.parametrize("gate", ["G2", "G3", "G4", "G6", "G8"])
def test_a_stopping_gate_blocks(repo: Path, tmp_path: Path, gate: str) -> None:
    svc, _ = services(FakeRunner(), results=gates(**{gate: GateStatus.STOP}))
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.BLOCKED
    assert gate in kinds(report)["BLOCKER"]


def test_new_warnings_need_an_approval_record_but_do_not_block(repo: Path, tmp_path: Path) -> None:
    svc, _ = services(FakeRunner(), results=gates(G5=GateStatus.FLAG))
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.PREPARED
    assert {"G1", "G5", "G7"} <= kinds(report)["REQUIRED"]


def test_a_publisher_error_blocks(repo: Path, tmp_path: Path) -> None:
    svc, _ = services(FakeRunner(), evaluate_error=OtaPublisherError("G3 cannot read"))
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.BLOCKED
    assert kinds(report)["BLOCKER"] == {"release"}


REAL_MANIFEST = (ROOT / "data/normalized/forever/1.60.1.70450/manifest.json").read_bytes()


def commit_on_main(repo: Path, files: dict[str, bytes]) -> str:
    for path, data in files.items():
        (repo / path).parent.mkdir(parents=True, exist_ok=True)
        (repo / path).write_bytes(data)
    git(repo, "add", "--all")
    git(
        repo,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        "-m",
        "main carries the build",
    )
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    return git(repo, "rev-parse", "HEAD")


def test_a_rerun_of_a_committed_build_keeps_main_bytes_when_identical(
    repo: Path, tmp_path: Path
) -> None:
    path = f"data/normalized/forever/{BUILD}/manifest.json"
    head = commit_on_main(repo, {path: REAL_MANIFEST})
    regenerated = json.loads(REAL_MANIFEST)
    regenerated["generatedAt"] = "2026-10-22T06:30:00Z"  # volatile: same classes and hashes
    runner = FakeRunner(manifest=json.dumps(regenerated, indent=2).encode())
    svc, evaluated = services(runner)
    report, out = run_prepare(repo, tmp_path, svc, detect(main=head))
    assert report.status is Readiness.PREPARED, report.findings
    ws = evaluated[0]["workspace"]
    assert (ws / path).read_bytes() == REAL_MANIFEST
    assert git(ws, "diff", "--name-only", head, report.candidate_commit or "", "--", path) == ""
    assert (out / path).read_bytes() == REAL_MANIFEST


def test_a_rerun_that_changes_a_committed_build_blocks(repo: Path, tmp_path: Path) -> None:
    path = f"data/normalized/forever/{BUILD}/manifest.json"
    head = commit_on_main(repo, {path: REAL_MANIFEST})
    changed = json.loads(REAL_MANIFEST)
    changed["classes"][0]["contentHash"] = "sha256:" + "0" * 64
    svc, _ = services(FakeRunner(manifest=json.dumps(changed).encode()))
    report, out = run_prepare(repo, tmp_path, svc, detect(main=head))
    assert report.status is Readiness.BLOCKED
    assert "reproducibility" in kinds(report)["BLOCKER"]
    assert json.loads((out / path).read_bytes()) == changed  # kept for inspection


VISUALS = f"data/visuals/forever/{BUILD}"


def main_with_visuals(repo: Path) -> tuple[str, dict[str, bytes]]:
    """main carries the build: its manifest and the real committed 1.60.1.70450 visuals."""
    visuals = committed_visuals()
    files = {f"data/normalized/forever/{BUILD}/manifest.json": REAL_MANIFEST}
    files.update({f"{VISUALS}/{name}": data for name, data in visuals.items()})
    return commit_on_main(repo, files), visuals


def test_a_rerun_on_another_platform_keeps_main_visuals_when_only_png_bytes_differ(
    repo: Path, tmp_path: Path
) -> None:
    """Regression (live run 39776364185): regenerated on ubuntu-24.04 x86_64, an icon of the
    committed build has other PNG bytes but the same client file and pixels. The run is
    PREPARED and the candidate carries main's bytes - the bundle checks and the OTA
    fixtures see the committed visuals unchanged."""
    head, committed = main_with_visuals(repo)
    linux = with_icon(committed, ICON, LINUX_PNG.read_bytes(), converter=LINUX_CONVERTER)
    assert linux[f"icons/{ICON}.png"] != committed[f"icons/{ICON}.png"]
    svc, evaluated = services(FakeRunner(manifest=REAL_MANIFEST, visuals=linux))
    report, out = run_prepare(repo, tmp_path, svc, detect(main=head))
    assert report.status is Readiness.PREPARED, report.findings
    ws = evaluated[0]["workspace"]
    for name, data in committed.items():
        assert (ws / VISUALS / name).read_bytes() == data, name
        assert (out / VISUALS / name).read_bytes() == data, name
    assert git(ws, "diff", "--name-only", head, report.candidate_commit or "", "--", VISUALS) == ""
    assert not any(p.startswith(VISUALS) for p in report.changed_paths)
    log = (out / "logs/visuals-reproduction.log").read_text()
    assert "re-encoded (other PNG bytes, the same image): 1 icon(s)" in log
    assert LINUX_CONVERTER in log
    assert "result: the same visuals; main's committed bytes are kept" in log


@pytest.mark.parametrize(
    ("change", "expected"),
    [
        ({"source_sha256": "sha256:" + "0" * 64}, "source_sha256 differ"),
        ({}, "the image differs"),
    ],
    ids=["client-file", "pixels"],
)
def test_a_rerun_that_changes_committed_visuals_blocks(
    repo: Path, tmp_path: Path, change: dict[str, object], expected: str
) -> None:
    """Real changes of a committed build's visuals are never replaced by main's files."""
    head, committed = main_with_visuals(repo)
    if change:
        png = LINUX_PNG.read_bytes()
    else:
        image = Image.open(io.BytesIO(committed[f"icons/{ICON}.png"]))
        image.load()
        image.putpixel((0, 0), tuple(255 - v for v in image.getpixel((0, 0))))
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        png = buffer.getvalue()
    regenerated = with_icon(committed, ICON, png, **change)
    svc, _ = services(FakeRunner(manifest=REAL_MANIFEST, visuals=regenerated))
    report, out = run_prepare(repo, tmp_path, svc, detect(main=head))
    assert report.status is Readiness.BLOCKED
    assert "reproducibility" in kinds(report)["BLOCKER"]
    finding = next(f for f in report.findings if f.source == "reproducibility")
    assert expected in finding.detail and f"icon {ICON}" in finding.detail
    # the regenerated files stay for inspection, main's are not slipped in
    assert (out / VISUALS / f"icons/{ICON}.png").read_bytes() == png
    assert (out / VISUALS / "icons.json").read_bytes() == regenerated["icons.json"]
    assert "DIFFERS: icon" in (out / "logs/visuals-reproduction.log").read_text()


def test_a_blocked_run_is_prepared_once_the_source_recovers(repo: Path, tmp_path: Path) -> None:
    """Regression (PR #71 review): run 1 is BLOCKED because a DB2 table is not exported
    yet (a definitive answer of the source, exit 1); the next scheduled run, with run 1's
    artifact kept, prepares again and - the source has caught up - ends PREPARED; the run
    after that is skipped."""
    head = git(repo, "rev-parse", "HEAD")
    kept: list[str] = []

    first = detect(main=head, prior_artifacts=kept)
    assert first.action is Action.PREPARE
    blocked, _ = run_prepare(repo, tmp_path / "1", services(FakeRunner({"fetch": 1}))[0], first)
    assert blocked.status is Readiness.BLOCKED
    kept.append(blocked.artifact_name)

    second = detect(main=head, prior_artifacts=kept)
    assert second.action is Action.PREPARE, second.reason
    assert second.prior_artifact == blocked.artifact_name
    prepared, out = run_prepare(repo, tmp_path / "2", services(FakeRunner())[0], second)
    assert prepared.status is Readiness.PREPARED
    assert prepared.artifact_name == blocked.artifact_name.replace("-BLOCKED", "-PREPARED")
    assert json.loads((out / "detection.json").read_text())["priorArtifact"] == (
        blocked.artifact_name
    )
    kept.append(prepared.artifact_name)

    third = detect(main=head, prior_artifacts=kept)
    assert third.action is Action.NO_ACTION
    assert third.prior_artifact == prepared.artifact_name


def test_writes_outside_the_candidate_block_before_any_commit(repo: Path, tmp_path: Path) -> None:
    svc, evaluated = services(FakeRunner(stray=True))
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.BLOCKED
    assert kinds(report) == {"BLOCKER": {"scope"}}
    assert report.candidate_commit is None and not evaluated
    assert any("mobile.txt" in f.detail for f in report.findings)


def test_an_unverifiable_production_catalog_keeps_a_clean_run_unknown(
    repo: Path, tmp_path: Path
) -> None:
    d = detect(production=UNREACHABLE, main=git(repo, "rev-parse", "HEAD"))
    svc, evaluated = services(FakeRunner(), production=UNREACHABLE)
    report, _ = run_prepare(repo, tmp_path, svc, d)
    assert report.status is Readiness.UNKNOWN
    assert kinds(report)["UNKNOWN"] == {"production"}
    assert evaluated and evaluated[0]["live"] is None  # still diagnosed in full


def test_a_production_catalog_changed_since_the_detection_is_unknown(
    repo: Path, tmp_path: Path
) -> None:
    changed = published("1.60.1.70450-r1")
    moved = ProductionState(
        ProductionStatus.PUBLISHED,
        ORIGIN,
        "verified",
        catalog=changed.catalog,
        envelope=b"{}",
        payload_sha256="sha256:" + "9" * 64,
    )
    svc, _ = services(FakeRunner(), production=moved)
    report, _ = run_prepare(repo, tmp_path, svc)
    assert report.status is Readiness.UNKNOWN
    assert kinds(report)["UNKNOWN"] == {"production"}


def test_a_repeated_preparation_reaches_the_same_result(repo: Path, tmp_path: Path) -> None:
    first, _ = run_prepare(repo, tmp_path / "1", services(FakeRunner())[0])
    second, _ = run_prepare(repo, tmp_path / "2", services(FakeRunner())[0])
    assert first.status is second.status is Readiness.PREPARED
    assert first.artifact_name == second.artifact_name
    # a used workspace is refused, never reused
    again, _ = run_prepare(repo, tmp_path / "1", services(FakeRunner())[0])
    assert again.status is Readiness.UNKNOWN
    assert kinds(again)["UNKNOWN"] == {"workspace"}


def test_only_a_prepare_detection_is_prepared(repo: Path, tmp_path: Path) -> None:
    d = detect(production=published("1.60.1.70450-r1", "1.60.1.70500-r1"))
    with pytest.raises(ValueError, match="NO_ACTION"):
        run_prepare(repo, tmp_path, services(FakeRunner())[0], d)


# --------------------------------------------------------------------------- CLI and workflow


def test_the_cli_tells_an_unreachable_source_from_bad_data(monkeypatch) -> None:
    def wrapped(cause: DownloadError) -> SourceError:
        # what the wago source does: ``raise SourceError(...) from DownloadError(...)``
        error = SourceError("could not fetch DB2 table Spell")
        error.__cause__ = cause
        return error

    def transport(_: object) -> int:
        # the HTTP client's "failed after N attempts" error, raised from the last failure
        exhausted = DownloadError("GET https://wago.tools/db2/Spell/csv failed after 4 attempts")
        exhausted.__cause__ = httpx.ConnectTimeout("timed out")
        raise wrapped(exhausted)

    def html(_: object) -> int:
        raise wrapped(DownloadError("Spell.csv: expected CSV, got HTML/XML"))

    def not_found(_: object) -> int:
        raise wrapped(DownloadError("HTTP 404 Table not found", status_code=404))

    def busy(_: object) -> int:
        raise DownloadError("HTTP 503", status_code=503)

    def broken(_: object) -> int:
        raise PipelineError("bad rows")

    argv = ["fetch", "--build", BUILD]
    cases = ((transport, 5), (busy, 5), (not_found, 1), (html, 1), (broken, 1))
    for handler, code in cases:
        monkeypatch.setattr(cli_main, "_cmd_fetch", handler)
        assert cli_main.main(argv) == code, handler.__name__


def test_the_script_adds_the_product_bundle(monkeypatch) -> None:
    monkeypatch.syspath_prepend(str(ROOT / "scripts"))
    import ota_prepare
    from mobile_bundles import product_build

    seen: list[list[str]] = []

    def runner(args: list[str]) -> int:
        seen.append(args)
        return 0

    monkeypatch.setattr(ota_prepare, "runner_main", runner)
    assert ota_prepare.main(["detect", "--force"]) == 0
    assert ota_prepare.main(["prepare", "--detection", "d.json", "--out", "o"]) == 0
    assert seen == [
        ["detect", "--force", "--product-build", product_build()],
        ["prepare", "--detection", "d.json", "--out", "o"],
    ]


def test_the_prepare_command_refuses_invalid_detections(tmp_path: Path, capsys) -> None:
    from talent_data.prepare.runner import main

    bad = tmp_path / "detection.json"
    bad.write_text('{"schemaVersion": 1}')
    out = str(tmp_path / "o")
    assert main(["prepare", "--detection", str(bad), "--out", out]) == 1
    assert "not a valid detection document" in capsys.readouterr().err
    no_action = tmp_path / "no-action.json"
    no_action.write_text(detect(INDEX[:2]).to_json())
    assert main(["prepare", "--detection", str(no_action), "--out", out]) == 1
    assert "nothing to prepare" in capsys.readouterr().err


def test_the_workflow_holds_no_publishing_secret_and_cannot_publish() -> None:
    text = (ROOT / ".github/workflows/ota-prepare.yml").read_text(encoding="utf-8")
    # The only secrets are the owner's personal Telegram pair of the `notify` job
    # (M5.NOTIFY), gated behind the `notifications` environment; no signing key and no
    # cloud credential exists anywhere in this workflow.
    assert sorted(line.strip() for line in text.splitlines() if "secrets." in line) == [
        "TELEGRAM_BOT_TOKEN: ${{ secrets.TELEGRAM_BOT_TOKEN }}",
        "TELEGRAM_CHAT_ID: ${{ secrets.TELEGRAM_CHAT_ID }}",
    ]
    notify_job = text.split("  notify:", 1)[1]
    assert "environment: notifications" in notify_job
    assert "continue-on-error: true" in notify_job
    for forbidden in ("--execute", "promote", "attach-signature", "aws ", "AWS_", "git push"):
        assert forbidden not in text, forbidden
    assert "permissions:\n  contents: read\n  actions: read" in text
    assert "persist-credentials: false" in text
    assert 'workflows: ["Dataset freshness"]' in text and "workflow_dispatch:" in text
    assert "actions/upload-artifact@v4" in text


def test_the_workflow_pins_the_icon_generation_environment() -> None:
    """New icons get their PNG bytes from the runner's Pillow wheel: a pinned image, and the
    environment in the job log (docs/talent-visuals.md §3)."""
    text = (ROOT / ".github/workflows/ota-prepare.yml").read_text(encoding="utf-8")
    assert [line.strip() for line in text.splitlines() if "runs-on:" in line] == [
        "runs-on: ubuntu-24.04",  # prepare
        "runs-on: ubuntu-24.04",  # propose (M5.OTA.AUTO.3)
        "runs-on: ubuntu-latest",  # notify (M5.NOTIFY; no icon generation)
    ]
    step = text.split("      - name: Icon generation environment\n", 1)[1].splitlines()[0]
    assert "print(i.converter_description())" in step


def workflow_step_script(name: str) -> str:
    """The ``run: |`` block of one workflow step, dedented (no YAML library needed)."""
    lines = (ROOT / ".github/workflows/ota-prepare.yml").read_text(encoding="utf-8").splitlines()
    start = lines.index(f"      - name: {name}")
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    body: list[str] = []
    for line in lines[run + 1 :]:
        if line and not line.startswith(" " * 10):
            break
        body.append(line[10:])
    return "\n".join(body) + "\n"


@pytest.mark.parametrize(
    ("detect_exit", "action", "prepare_exit", "status", "job_exit"),
    [
        ("0", "NO_ACTION", "", "", 0),
        ("0", "PREPARE", "0", "PREPARED", 0),
        ("0", "PREPARE", "3", "BLOCKED", 3),
        ("0", "PREPARE", "4", "UNKNOWN", 4),
        ("0", "PREPARE", "1", "", 1),  # crashed
        ("0", "PREPARE", "", "", 1),  # the prepare step died before recording its result
        ("0", "PREPARE", "0", "BLOCKED", 1),  # contradictory result
        ("0", "PREPARE", "0", "", 1),
        ("0", "", "", "", 1),  # exit 0 without a decision
        ("4", "UNKNOWN", "", "", 4),
        ("1", "", "", "", 1),
        ("", "", "", "", 1),  # the detection never ran (install failed)
    ],
)
def test_the_workflow_never_turns_a_failed_preparation_green(
    detect_exit: str, action: str, prepare_exit: str, status: str, job_exit: int
) -> None:
    env = {
        "PATH": "/usr/bin:/bin",
        "DETECT_EXIT": detect_exit,
        "DETECT_ACTION": action,
        "PREPARE_EXIT": prepare_exit,
        "PREPARE_STATUS": status,
    }
    # GitHub runs ``shell: bash`` as ``bash --noprofile --norc -eo pipefail {0}``
    completed = subprocess.run(
        [
            "bash",
            "--noprofile",
            "--norc",
            "-eo",
            "pipefail",
            "-c",
            workflow_step_script("Final status"),
        ],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == job_exit, completed.stdout + completed.stderr


def test_the_workflow_uploads_every_outcome_and_prepares_only_on_prepare() -> None:
    text = (ROOT / ".github/workflows/ota-prepare.yml").read_text(encoding="utf-8")
    assert "if: steps.detect.outputs.action == 'PREPARE'" in text
    assert "if: always() && steps.prepare.outcome != 'skipped'" in text  # the preparation
    assert "if: always() && steps.prepare.outcome == 'skipped'" in text  # the detection only
    assert "steps.prepare.outputs.artifact_name || format('ota-prepare-{0}-FAILED'" in text
    assert "cancel-in-progress: false" in text and "group: ota-prepare" in text
