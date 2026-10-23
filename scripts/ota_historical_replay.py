"""Historical patch replay 1.60.1.70374 -> 1.60.1.70450, end to end (M5.OTA.AUTO.2).

Replays the real patch without waiting for a new one and without touching production:

  1. detect   the existing detection over two recorded wago.tools build indexes
              (talent_data.prepare.replay): 2026-10-14 -> NO_ACTION, 2026-10-17 -> PREPARE 70450,
              with PRODUCT_BUNDLE 70374 passed as a parameter and production ABSENT
              (an in-memory .invalid origin)
  2. prepare  the existing preparation (talent_data.prepare.orchestrate.prepare) of that
              detection in a disposable clone: wago.tools source steps (network), pipeline,
              scope guard, local candidate commit, gates G1-G8, bundle checks, pytest
  3. verify   the prepared candidate: nine classes, the dataset/visuals/tooltips/localization
              and the semantic diff equal what main carries, gates, SHA-256 reproducibility
  4. stage    the existing staging stand (scripts/ota_staging_stand.py prepare): the real
              publisher CLI with an ephemeral tf-staging-local-* key; staging first carries
              70374 only, then 70450 is staged, signed and activated; the staged release must
              hold exactly the prepared files; catalog, manifest and blobs re-verified here
  5. client   the real Kotlin client over HTTPS (OtaHistoricalReplayTest, OtaStagingE2ETest)
              as the app before the patch: 70374 bundled, staging channel
  6. report   <out>/replay-report.json and <out>/summary.md: PASS / FAIL / BLOCKED / NOT RUN

    python scripts/ota_historical_replay.py run ~/tf-replay    # [--skip-prepare] [--skip-client]

<out> must be outside any git checkout (the stand writes private staging and TLS keys there)
and empty. Exit codes: 0 every automatic check PASS, 1 a FAIL, 3 BLOCKED or NOT RUN (no FAIL),
2 usage. The checkout is never modified; production is only ever read (optional guard: two
anonymous GETs of the public production catalog, compared before and after; a catalog not read
or changed in between is BLOCKED, never PASS, and the report is written either way).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
import traceback
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from pathlib import Path
from types import ModuleType
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import httpx  # noqa: E402

from talent_data.games import get_game  # noqa: E402
from talent_data.ota.preflight import fetch  # noqa: E402
from talent_data.prepare import replay  # noqa: E402
from talent_data.prepare.detect import Action, DetectionDocument  # noqa: E402
from talent_data.prepare.orchestrate import (  # noqa: E402
    PrepareReport,
    Readiness,
    Services,
    StepOutcome,
    evaluate_release,
    main_commit,
    prepare,
    run_command,
)
from talent_data.prepare.production import read_production  # noqa: E402
from talent_data.prepare.replay import Verdict  # noqa: E402

EXIT_PASS, EXIT_FAIL, EXIT_USAGE, EXIT_INCOMPLETE = 0, 1, 2, 3
PRODUCTION_CATALOG = "https://ota.mythweld.com/v1/channels/production/catalog.json"
CLIENT_SUITES = ("OtaHistoricalReplayTest", "OtaStagingE2ETest")
JUNIT = "mobile/composeApp/build/test-results/desktopTest"
BUNDLED_DATASETS = Path(
    "mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/library/BundledDatasets.kt"
)


def _load_stand() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "ota_staging_stand", ROOT / "scripts" / "ota_staging_stand.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["ota_staging_stand"] = module
    spec.loader.exec_module(module)
    return module


stand = _load_stand()


@dataclass
class Check:
    id: str
    phase: str
    title: str
    verdict: Verdict
    detail: str
    automatic: bool = True  # False: needs a device or a person, never counted as executed here


@dataclass
class Replay:
    out: Path
    checks: list[Check] = field(default_factory=list)
    facts: dict[str, Any] = field(default_factory=dict)

    def add(
        self, id: str, phase: str, title: str, verdict: Verdict, detail: str, **kw: Any
    ) -> None:
        self.checks.append(Check(id, phase, title, verdict, detail, **kw))
        print(f"[replay] {id} {verdict.value}: {title} - {detail}", file=sys.stderr)

    def expect(self, id: str, phase: str, title: str, ok: bool, detail: str) -> bool:
        self.add(id, phase, title, Verdict.PASS if ok else Verdict.FAIL, detail)
        return ok


def _now() -> dt.datetime:
    return dt.datetime.now(dt.UTC).replace(microsecond=0)


def _stamp(instant: dt.datetime) -> str:
    return instant.strftime("%Y-%m-%dT%H:%M:%SZ")


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout.strip()


def _phase(r: Replay, name: str, body: Callable[[], None]) -> None:
    """Run a phase; a crash is that phase's FAIL with its traceback kept, never lost."""
    try:
        body()
    except Exception as exc:  # noqa: BLE001 - recorded as the phase's failure
        log = r.out / "logs" / f"{name}-crash.txt"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(traceback.format_exc(), encoding="utf-8")
        r.add(f"{name}-crash", name, f"phase {name} crashed", Verdict.FAIL, f"{exc!r}; {log}")


# --------------------------------------------------------------------------- 1. detect


def phase_detect(r: Replay) -> DetectionDocument | None:
    production = replay.HistoricalProduction()
    outs = {}
    for name, index, at in (
        ("before", replay.BEFORE_INDEX, replay.BEFORE_AT),
        ("after", replay.AFTER_INDEX, replay.AFTER_AT),
    ):
        detection = replay.detect_at(
            ROOT / index,
            repo=ROOT,
            product_build=replay.BASE_BUILD,
            now=at,
            production_log=production,
        )
        path = r.out / "detect" / f"{name}.detection.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(detection.to_json(), encoding="utf-8")
        outs[name] = detection
    before, after = outs["before"], outs["after"]
    r.expect(
        "D1",
        "detect",
        "index of 2026-10-14: no update",
        before.action is Action.NO_ACTION and before.candidate_build == replay.BASE_BUILD,
        f"{before.action.value}: {before.reason}",
    )
    prepared = r.expect(
        "D2",
        "detect",
        "index of 2026-10-17: 70450 detected and selected for preparation",
        after.action is Action.PREPARE
        and after.candidate_build == replay.PATCH_BUILD
        and after.base_build == replay.BASE_BUILD
        and after.revision == 1,
        f"{after.action.value} {after.candidate_build} from {after.base_build} "
        f"({after.base_kind}), r{after.revision}: {after.reason}",
    )
    r.expect(
        "D3",
        "detect",
        "production read only through the historical in-memory origin (ABSENT)",
        all(replay.HISTORICAL_PRODUCTION_ORIGIN in q for q in production.requests)
        and before.production.status.value == after.production.status.value == "ABSENT",
        f"{len(production.requests)} request(s), all to {replay.HISTORICAL_PRODUCTION_ORIGIN}",
    )
    r.facts["detection"] = {
        "before": json.loads(before.to_json()),
        "after": json.loads(after.to_json()),
    }
    return after if prepared else None


# --------------------------------------------------------------------------- 2-3. prepare


def phase_prepare(r: Replay, detection: DetectionDocument) -> PrepareReport:
    from talent_data.ota.preflight import production_keyring

    production = replay.HistoricalProduction()
    keyring = production_keyring(ROOT / replay.PRODUCTION_KEYRING)
    out, workspace = r.out / "prepare" / "out", r.out / "prepare" / "workspace"

    def no_mirror(*_: object) -> int:
        raise AssertionError("an ABSENT production has nothing to mirror")

    started = time.monotonic()
    with replay.historical_production_client(production) as client:
        services = Services(
            run=run_command,
            read_production=lambda: read_production(
                client,
                origin=replay.HISTORICAL_PRODUCTION_ORIGIN,
                keyring=keyring,
                game=detection.game,
                now=_now(),
            ),
            mirror=no_mirror,
            evaluate=evaluate_release,
            clock=_now,
        )
        report = prepare(detection, repo=ROOT, out=out, workspace=workspace, services=services)
    seconds = round(time.monotonic() - started)
    r.facts["prepare"] = {
        "status": report.status.value,
        "seconds": seconds,
        "artifactName": report.artifact_name,
        "candidateCommit": report.candidate_commit,
        "release": None
        if report.evaluation is None
        else {
            "releaseId": report.evaluation.release_id,
            "manifestSha256": report.evaluation.manifest_sha256,
        },
        "diffTotal": report.diff_total,
        "steps": {s.step.id: s.outcome.value for s in report.steps},
        "gates": {}
        if report.evaluation is None
        else {g.gate.value: g.status.value for g in report.evaluation.gates},
        "findings": [f"{f.kind.value} {f.source}: {f.detail}" for f in report.findings],
        "productionRequests": production.requests,
    }
    verdict = {
        Readiness.PREPARED: Verdict.PASS,
        Readiness.BLOCKED: Verdict.FAIL,
        Readiness.UNKNOWN: Verdict.BLOCKED,  # e.g. wago.tools unreachable: not established
    }[report.status]
    r.add(
        "P0",
        "prepare",
        "the existing pipeline prepared 70450 (PREPARED)",
        verdict,
        f"{report.status.value} in {seconds} s; {report.artifact_name}; prepare/out/summary.md",
    )
    return report


def _same_tree(prepared: Path, committed: Path) -> list[str]:
    a, b = stand.tree_digest(prepared), stand.tree_digest(committed)
    problems = [f"missing {p}" for p in sorted(b.keys() - a.keys())]
    problems += [f"extra {p}" for p in sorted(a.keys() - b.keys())]
    problems += [f"other bytes {p}" for p in sorted(a.keys() & b.keys()) if a[p] != b[p]]
    return problems


def phase_verify_candidate(r: Replay, report: PrepareReport, main: str) -> None:
    from talent_data.cli.ota import STANDARD_VALIDATION_NOTE
    from talent_data.ota.release import build_release

    out = r.out / "prepare" / "out"
    game, build, base = replay.GAME, replay.PATCH_BUILD, replay.BASE_BUILD
    steps = {s.step.id: s.outcome for s in report.steps}
    assert report.evaluation is not None
    gates = {g.gate.value: g for g in report.evaluation.gates}

    def passed(*ids: str) -> bool:
        return all(steps.get(i) is StepOutcome.PASSED for i in ids)

    manifest = json.loads((out / f"data/normalized/{game}/{build}/manifest.json").read_text())
    tokens = sorted(c["token"] for c in manifest["classes"])
    expected = sorted(get_game(game).dataset_class_tokens)
    r.expect(
        "P1",
        "prepare",
        "the data of all nine classes is complete",
        passed("normalize", "manifest")
        and tokens == expected
        and gates["G2"].status.value == "PASS",
        f"{len(tokens)} classes {tokens}; manifest --expect-complete; G2: {gates['G2'].detail}",
    )
    for check_id, title, root, ids in (
        ("P2", "talent normalization equals main", "data/normalized", ("fetch", "normalize")),
        (
            "P3",
            "visual resources equal main by meaning",
            "data/visuals",
            ("visuals-fetch", "visuals-normalize", "visuals-check"),
        ),
        (
            "P4a",
            "tooltips equal main",
            "data/tooltips",
            ("tooltips-fetch", "tooltips-normalize", "tooltips-check"),
        ),
        (
            "P4b",
            "ruRU localization equals main",
            "data/localization",
            ("localization-fetch-ruRU", "localization-normalize-ruRU", "localization-check-ruRU"),
        ),
    ):
        problems = _same_tree(out / root / game / build, ROOT / root / game / build)
        detail = f"steps {', '.join(ids)}: {'PASSED' if passed(*ids) else 'not all PASSED'}; " + (
            "bytes equal main" if not problems else "; ".join(problems[:5])
        )
        if root == "data/visuals":
            lines = (out / "logs/visuals-reproduction.log").read_text(encoding="utf-8").splitlines()
            detail += f"; {lines[1]}; {lines[-1]}"
        r.expect(check_id, "prepare", title, passed(*ids) and not problems, detail)

    problems = []
    diffs = sorted((ROOT / "artifacts/diff").glob(f"{game}-*-{base}-{build}.json"))
    for committed in diffs:
        regenerated = out / "artifacts/diff" / committed.name
        if not regenerated.is_file():
            problems.append(f"missing {committed.name}")
        elif json.loads(regenerated.read_bytes()) != json.loads(committed.read_bytes()):
            problems.append(f"differs {committed.name}")
    r.expect(
        "P5",
        "prepare",
        "the semantic diff 70374 -> 70450 equals the committed one",
        passed("diff") and len(diffs) == 10 and not problems,
        f"{len(diffs)} diff documents; total {report.diff_total}; "
        + ("; ".join(problems) if problems else "equal by content"),
    )

    stopped = sorted(g for g, res in gates.items() if res.status.value == "STOP")
    flagged = sorted(g for g, res in gates.items() if res.status.value == "FLAG")
    r.expect(
        "P6",
        "prepare",
        "OTA gates: no STOP in G2-G8, G1 STOP only as the required step",
        stopped == ["G1"] and not report.evaluation.on_main,
        "; ".join(f"{g} {res.status.value}" for g, res in sorted(gates.items()))
        + f"; FLAG: {flagged or 'none'}",
    )

    # the release built twice from main is byte-identical and holds exactly the prepared files
    # (what a release rebuilt from the merge commit of a refresh PR ships)
    created = report.started_at
    first = build_release(ROOT, main, game, build, 1, created, STANDARD_VALIDATION_NOTE)
    second = build_release(ROOT, main, game, build, 1, created, STANDARD_VALIDATION_NOTE)
    prepared_files = replay.release_files(report.evaluation.manifest_path.read_bytes())
    problems = replay.compare_release_files(
        prepared_files, replay.release_files(first.manifest_bytes)
    )
    identical = first.manifest_bytes == second.manifest_bytes
    r.facts["mainRelease"] = {
        "manifestSha256": "sha256:" + hashlib.sha256(first.manifest_bytes).hexdigest(),
        "files": len(prepared_files),
    }
    r.expect(
        "P7",
        "prepare",
        "SHA-256 and reproducibility",
        identical and not problems and gates["G8"].status.value == "PASS",
        f"G8: {gates['G8'].detail}; build-release from main {main[:12]} twice: bytes "
        f"{'identical' if identical else 'differ'}; the {len(prepared_files)} candidate files "
        + (
            "equal the release built from main (path, sha256, byteLength)"
            if not problems
            else "; ".join(problems[:5])
        ),
    )
    checks = (
        "sync-datasets-check",
        "sync-visuals-check",
        "sync-tooltips-check",
        "sync-localization-check",
        "pytest",
    )
    pytest_tail = (out / "logs/pytest.log").read_text(encoding="utf-8").strip().splitlines()[-1]
    r.expect(
        "P8",
        "prepare",
        "regression checks and tests pass",
        passed(*checks),
        ", ".join(f"{c} {steps.get(c, StepOutcome.SKIPPED).value}" for c in checks)
        + f"; pytest: {pytest_tail}",
    )


# --------------------------------------------------------------------------- 4. stage


def phase_stage(r: Replay, main: str, prepared_manifest: Path | None) -> None:
    from talent_data.ota.models import Channel
    from talent_data.ota.verify import (
        OtaError,
        load_keyring,
        verify_catalog,
        verify_release_files,
        verify_release_manifest,
    )

    directory = r.out / "stand"
    report = stand.prepare(directory, commit=main, now=_now())
    snapshots = {s["name"]: s for s in report["snapshots"]}
    a, b = report["releases"]["a"], report["releases"]["b"]
    r.facts["stand"] = {
        "keyId": report["keyId"],
        "commit": report["commit"],
        "publisherLog": report["publisherLog"],
        "snapshots": report["snapshots"],
        "g6": report["g6"],
    }
    first, second = snapshots["1-a-staged"], snapshots["2-b-staged"]
    r.expect(
        "S1",
        "stage",
        "staging first carries 70374 only",
        first["releases"] == [a] and first["activeReleaseId"] == a,
        f"sequence {first['sequence']}, active {first['activeReleaseId']}, "
        f"listed {first['releases']}",
    )
    staged_files = replay.release_files((directory / report["manifests"][b]["path"]).read_bytes())
    if prepared_manifest is None:
        r.add(
            "S2",
            "stage",
            "the staged 70450 holds exactly the prepared files",
            Verdict.NOT_RUN,
            "the preparation did not run",
        )
    else:
        problems = replay.compare_release_files(
            replay.release_files(prepared_manifest.read_bytes()), staged_files
        )
        r.expect(
            "S2",
            "stage",
            "the staged 70450 holds exactly the prepared files",
            not problems,
            f"{len(staged_files)} files: "
            + ("path, sha256, byteLength equal" if not problems else "; ".join(problems[:5])),
        )

    # signature, release manifest and blobs, re-verified with the reference verifier
    keyring = load_keyring(json.loads((directory / report["keyring"]).read_text())["keys"])
    store = directory / "stores" / "2-b-staged"
    catalog_bytes = (store / stand.CATALOG_KEY).read_bytes()
    published_at = stand.utc(report["now"])
    verified = verify_catalog(
        catalog_bytes,
        keyring=keyring,
        game=replay.GAME,
        channel=Channel.STAGING,
        now=published_at,
        last_seen=None,
    )
    entry = next(e for e in verified.catalog.releases if e.release_id == b)

    def blob(ref: str) -> bytes:
        return (store / "v1/blobs/sha256" / ref.removeprefix("sha256:")).read_bytes()

    manifest = verify_release_manifest(
        blob(entry.manifest_sha256), entry, game=replay.GAME, supported_data_level=1
    )
    verify_release_files(manifest, lambda file: blob(file.sha256))
    production_keys = load_keyring(
        json.loads((ROOT / replay.PRODUCTION_KEYRING).read_text())["keys"]
    )
    try:
        verify_catalog(
            catalog_bytes,
            keyring=production_keys,
            game=replay.GAME,
            channel=Channel.PRODUCTION,
            now=published_at,
            last_seen=None,
        )
        refused = None
    except OtaError as exc:
        refused = exc.code.value
    # the stand raises on any publisher exit code it did not expect (the G6 attempt expects 1)
    verify_live = [e["exit"] for e in report["publisherLog"] if e["command"] == "verify-live"]
    r.expect(
        "S3",
        "stage",
        "signature, release manifest and blobs verified",
        refused is not None and verify_live == [0] * len(snapshots),
        f"verify_catalog(staging, {report['keyId']}): sequence {verified.catalog.sequence}; "
        f"manifest {entry.manifest_sha256[:19]}... and {len(manifest.files)} files match their "
        f"pins; the production keyring refuses the catalog ({refused}); publisher verify-live "
        f"after each of the {len(snapshots)} publications: exit {verify_live}",
    )
    r.expect(
        "S4",
        "stage",
        "the new staging catalog activates 70450",
        second["activeReleaseId"] == b and second["releases"] == [a, b] and second["sequence"] == 2,
        f"sequence {second['sequence']}, active {second['activeReleaseId']}, listed "
        f"{second['releases']}, {second['blobCount']} blobs",
    )
    g6 = report["g6"]
    r.expect(
        "S5",
        "stage",
        "staging key only; the publisher refuses an incompatible release (G6)",
        report["keyId"].startswith("tf-staging-local-")
        and g6["gates"].get("G6") == "STOP"
        and g6["storeUnchanged"],
        f"key {report['keyId']} (ephemeral, outside git); G6 attempt {g6['releaseId']}: validate "
        f"exit {g6['validateExit']}, stage exit {g6['stageExit']}, store unchanged",
    )


# --------------------------------------------------------------------------- 5. client


def phase_client(r: Replay) -> None:
    results_dir = ROOT / JUNIT

    def xml(suite: str) -> Path:
        return results_dir / f"TEST-io.github.alexeykravtsov95.talentforge.ui.ota.{suite}.xml"

    for suite in CLIENT_SUITES:
        xml(suite).unlink(missing_ok=True)
    log = r.out / "logs" / "gradle-client.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    argv = [
        "./gradlew",
        ":composeApp:desktopTest",
        *[arg for suite in CLIENT_SUITES for arg in ("--tests", f"*{suite}")],
        "--console=plain",
        f"-Dtalentforge.ota.staging={r.out / 'stand'}",
        f"-Dtalentforge.ota.replay={r.out}",
    ]
    started = time.monotonic()
    with log.open("wb") as handle:
        code = subprocess.run(
            argv, cwd=ROOT / "mobile", stdout=handle, stderr=subprocess.STDOUT, check=False
        ).returncode
    seconds = round(time.monotonic() - started)
    reports = []
    for suite in CLIENT_SUITES:
        if xml(suite).is_file():
            target = r.out / "client" / "junit" / xml(suite).name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(xml(suite), target)
            reports.append(target)
    results = replay.junit_results(reports)
    r.facts["client"] = {
        "gradleExit": code,
        "seconds": seconds,
        "tests": {name: verdict.value for name, verdict in sorted(results.items())},
        "evidence": {
            p.stem: json.loads(p.read_text(encoding="utf-8"))
            for p in sorted((r.out / "client").glob("*.json"))
        },
    }
    ran = {name.split(".")[0] for name, v in results.items() if v is not Verdict.NOT_RUN}
    passed = sum(v is Verdict.PASS for v in results.values())
    r.expect(
        "K0",
        "client",
        "Kotlin client: both suites ran, none skipped",
        code == 0 and ran == set(CLIENT_SUITES) and passed == len(results),
        f"gradle exit {code} in {seconds} s; {passed}/{len(results)} tests PASS; "
        "logs/gradle-client.log",
    )
    verdicts = replay.scenario_verdicts(results)
    for scenario in replay.CLIENT_SCENARIOS:
        r.add(
            scenario.id,
            "client",
            scenario.title,
            verdicts[scenario.id],
            ", ".join(f"{t} {results.get(t, Verdict.NOT_RUN).value}" for t in scenario.tests),
        )


# --------------------------------------------------------------------------- guards


@dataclass(frozen=True)
class CatalogRead:
    at: str
    sha256: str | None  # None: not read (see detail)
    detail: str


def _production_client() -> httpx.Client:
    return httpx.Client(follow_redirects=False, timeout=30.0)


def read_production_catalog(out: Path, name: str) -> CatalogRead:
    """One anonymous GET of the public production catalog; never raises.

    A timeout, a network error, an unavailable CDN or any other exception is a catalog not read,
    with its reason (the traceback under ``logs/``); the body read is kept under ``guard/``.
    """
    at = _stamp(_now())
    try:
        with _production_client() as client:
            response = fetch(client, PRODUCTION_CATALOG)
    except Exception as exc:  # noqa: BLE001 - recorded as a catalog not read, never a crash
        log = out / "logs" / f"guard-{name}-crash.txt"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(traceback.format_exc(), encoding="utf-8")
        return CatalogRead(at, None, f"{type(exc).__name__}: {exc}; {log.relative_to(out)}")
    if response.status != 200:
        return CatalogRead(at, None, f"HTTP {response.status or response.error}")
    body = out / "guard" / f"production-catalog-{name}.json"
    body.parent.mkdir(parents=True, exist_ok=True)
    body.write_bytes(response.body)
    sha = "sha256:" + hashlib.sha256(response.body).hexdigest()
    return CatalogRead(at, sha, f"HTTP 200, {len(response.body)} bytes; {body.relative_to(out)}")


def guard_production(r: Replay, before: CatalogRead, after: CatalogRead) -> None:
    """G2 from the reads before and after the replay: PASS only when both were read and equal.

    A catalog not read is BLOCKED. A catalog that changed between the reads is BLOCKED too, not
    FAIL: the replay holds no production credential and publishes to its local staging stand
    only, so the change is an external publication the guard can report but not attribute.
    """
    r.facts["productionGuard"] = {
        "url": PRODUCTION_CATALOG,
        "before": asdict(before),
        "after": asdict(after),
    }
    title = "the production catalog did not change during the replay"
    unread = [(n, c) for n, c in (("before", before), ("after", after)) if c.sha256 is None]
    if unread:
        reasons = "; ".join(f"{n} ({c.at}): {c.detail}" for n, c in unread)
        r.add("G2", "guard", title, Verdict.BLOCKED, f"{PRODUCTION_CATALOG} not read - {reasons}")
    elif before.sha256 == after.sha256:
        r.add(
            "G2",
            "guard",
            title,
            Verdict.PASS,
            f"anonymous GET at {before.at} and {after.at}: {before.sha256}",
        )
    else:
        r.add(
            "G2",
            "guard",
            title,
            Verdict.BLOCKED,
            f"changed between {before.at} and {after.at}: {before.sha256} -> {after.sha256}; "
            "an external change (the replay holds no production credential and publishes to "
            "its local staging stand only), so whether production stayed untouched is not "
            "established by this guard; both bodies under guard/",
        )


def checkout_state() -> dict[str, str]:
    """What the replay must leave untouched: the checkout, PRODUCT_BUNDLE, production keys."""
    return {
        "head": _git("rev-parse", "HEAD"),
        "status": _git("status", "--porcelain", "--untracked-files=no"),
        "productionKeyring": hashlib.sha256(
            (ROOT / replay.PRODUCTION_KEYRING).read_bytes()
        ).hexdigest(),
        "bundledDatasets": hashlib.sha256((ROOT / BUNDLED_DATASETS).read_bytes()).hexdigest(),
    }


# --------------------------------------------------------------------------- report


def overall(checks: list[Check]) -> Verdict:
    """The replay's verdict: every automatic check (a device check never counts)."""
    return replay.combine(c.verdict for c in checks if c.automatic)


def render_summary(r: Replay, verdict: Verdict) -> str:
    f = r.facts
    lines = [
        f"# Historical replay {replay.BASE_BUILD} → {replay.PATCH_BUILD}: {verdict.value}",
        "",
        f"* Checkout `{f['checkout']['head'][:12]}`, main `{f['mainCommit'][:12]}`; "
        f"{f['startedAt']} → {f['finishedAt']}",
        "* Staging key: "
        f"`{f.get('stand', {}).get('keyId', '-')}` (ephemeral, outside git); "
        "production was only read.",
        "",
        "| id | phase | check | verdict | detail |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {c.id} | {c.phase} | {c.title} | {c.verdict.value} | {c.detail.replace('|', '/')} |"
        for c in r.checks
    ]
    evidence = f.get("client", {}).get("evidence", {}).get("theClientMovesFrom70374To70450")
    if evidence:
        lines += [
            "",
            "## What the client received",
            "",
            f"* Over HTTPS: {evidence['downloadedFiles']} files, "
            f"{evidence['downloadedBytes']} bytes "
            f"({evidence['downloadedByRole']}) and the manifest; blob requests: "
            f"{evidence['blobRequests']}",
            f"* From the installed 70374 release (the client's cache): "
            f"{evidence['copiedFromInstalled70374']} files ({evidence['copiedByRole']})",
            f"* From the bundle: {evidence['copiedFromBundle']} files of 70450",
        ]
    lines.append("")
    return "\n".join(lines)


def run(args: argparse.Namespace) -> int:
    out: Path = args.out.expanduser().resolve()
    if stand.inside_git_checkout(out):
        print(
            f"error: {out} is inside a git checkout (the stand writes private keys)",
            file=sys.stderr,
        )
        return EXIT_USAGE
    if out.exists() and any(out.iterdir()):
        print(f"error: {out} is not empty", file=sys.stderr)
        return EXIT_USAGE
    out.mkdir(parents=True, exist_ok=True)
    r = Replay(out)
    main = main_commit(ROOT)
    before = checkout_state()
    production_before = None if args.no_production_guard else read_production_catalog(out, "before")
    r.facts.update(startedAt=_stamp(_now()), mainCommit=main, checkout=before)

    detected: list[DetectionDocument | None] = [None]
    _phase(r, "detect", lambda: detected.__setitem__(0, phase_detect(r)))
    detection = detected[0]

    prepared_manifest: Path | None = None
    title = "the existing pipeline prepared 70450 (PREPARED)"
    if args.skip_prepare:
        r.add("P0", "prepare", title, Verdict.NOT_RUN, "--skip-prepare")
    elif detection is None:
        r.add("P0", "prepare", title, Verdict.BLOCKED, "the detection did not say PREPARE")
    else:
        holder: list[PrepareReport | None] = [None]
        _phase(r, "prepare", lambda: holder.__setitem__(0, phase_prepare(r, detection)))
        report = holder[0]
        if report is not None and report.status is Readiness.PREPARED:
            _phase(r, "verify", lambda: phase_verify_candidate(r, report, main))
            if report.evaluation is not None:
                prepared_manifest = report.evaluation.manifest_path
        if not args.keep_workspace:
            shutil.rmtree(out / "prepare" / "workspace", ignore_errors=True)
            shutil.rmtree(out / "prepare" / "workspace-production-mirror", ignore_errors=True)

    _phase(r, "stage", lambda: phase_stage(r, main, prepared_manifest))
    title = "Kotlin client: both suites ran, none skipped"
    if args.skip_client:
        r.add("K0", "client", title, Verdict.NOT_RUN, "--skip-client")
    elif not (out / "stand" / "report.json").is_file():
        r.add("K0", "client", title, Verdict.BLOCKED, "the staging stand was not prepared")
    else:
        _phase(r, "client", lambda: phase_client(r))

    after = checkout_state()
    r.expect(
        "G1",
        "guard",
        "checkout, PRODUCT_BUNDLE and the production keyring unchanged",
        before == after,
        f"HEAD {after['head'][:12]}, BundledDatasets.kt sha256 {after['bundledDatasets'][:12]}, "
        f"production keyring sha256 {after['productionKeyring'][:12]}",
    )
    if production_before is None:
        title = "the production catalog did not change during the replay"
        r.add("G2", "guard", title, Verdict.NOT_RUN, "--no-production-guard")
    else:
        guard_production(r, production_before, read_production_catalog(out, "after"))
    r.add(
        "M1",
        "android",
        "Android emulator and physical device",
        Verdict.NOT_RUN,
        "not automated: a historical debug APK (70374 bundled) by the procedure of "
        "docs/ota/M5.OTA.AUTO.2-replay-report.md §7",
        automatic=False,
    )

    verdict = overall(r.checks)
    r.facts["finishedAt"] = _stamp(_now())
    document = {
        "format": "talentforge-ota-historical-replay",
        "formatVersion": 1,
        "verdict": verdict.value,
        "baseBuild": replay.BASE_BUILD,
        "patchBuild": replay.PATCH_BUILD,
        "checks": [
            {
                "id": c.id,
                "phase": c.phase,
                "title": c.title,
                "verdict": c.verdict.value,
                "detail": c.detail,
                "automatic": c.automatic,
            }
            for c in r.checks
        ],
        "facts": r.facts,
    }
    (out / "replay-report.json").write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    summary = render_summary(r, verdict)
    (out / "summary.md").write_text(summary, encoding="utf-8")
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(step_summary).open("a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary)
    return {Verdict.PASS: EXIT_PASS, Verdict.FAIL: EXIT_FAIL}.get(verdict, EXIT_INCOMPLETE)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    go = sub.add_parser("run", help="the whole replay into an empty directory outside git")
    go.add_argument("out", type=Path)
    go.add_argument("--skip-prepare", action="store_true", help="no wago.tools: NOT RUN")
    go.add_argument("--skip-client", action="store_true", help="no Gradle: NOT RUN")
    go.add_argument("--keep-workspace", action="store_true", help="keep the preparation's clone")
    go.add_argument(
        "--no-production-guard",
        action="store_true",
        help="do not read the public production catalog before and after",
    )
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
