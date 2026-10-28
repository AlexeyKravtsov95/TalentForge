"""Staging verification of an OTA candidate before production (M5.OTA.AUTO.4), end to end.

One run verifies one candidate against one base, from one commit fixed at the start:

  1. resolve  the revision (empty: the HEAD of origin/main) to a full commit id on origin/main;
              both builds must be committed there and the candidate newer than the base
  2. publish  the existing staging stand (scripts/ota_staging_stand.py prepare --base
              --candidate): the real publisher CLI builds both releases from that commit and
              stages them with an ephemeral tf-staging-local-* key into a local store, no cloud
              credential; staging first carries the base only, then the candidate is staged,
              signed and activated. Every built file is compared with the commit's data and
              with the staged store (path, SHA-256, size); the catalog is re-verified here
  3. client   the real Kotlin client over HTTPS (OtaCandidateStagingTest) as the app before the
              update: the base bundled - read from that commit's data, exported to <out>/subject
              - the candidate neither bundled nor installed; its installed copy is compared with
              the built release again from its evidence
  4. report   <out>/staging-report.json, <out>/summary.md (also the Actions job summary),
              <out>/junit/*.xml and <out>/logs/: PASS / FAIL / BLOCKED / NOT RUN

    python scripts/ota_staging_verify.py run ~/tf-staging \\
        --base 1.60.1.70450 --candidate 1.60.1.70532 [--revision <sha|ref>] [--skip-client]

<out> must be outside any git checkout (the stand writes private staging and TLS keys there; they
are deleted at the end of the run) and empty. Exit codes: 0 every automatic check PASS, 1 a FAIL,
3 BLOCKED or NOT RUN (no FAIL), 2 usage. The checkout is never modified, nothing is published
outside <out>, production is not contacted, and a PASS approves nothing for production: the
approval record, the owner's promotion and signing are unchanged (docs/ota/publisher-runbook.md).
Android, iOS and physical devices are not part of this run (NOT RUN, never counted).
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import time
import traceback
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from talent_data.prepare import replay  # noqa: E402
from talent_data.prepare import staging_verify as sv  # noqa: E402
from talent_data.prepare.replay import Verdict  # noqa: E402

JUNIT_DIR = ROOT / "mobile/composeApp/build/test-results/desktopTest"
JUNIT_XML = f"TEST-io.github.alexeykravtsov95.talentforge.ui.ota.{sv.SUITE}.xml"
BUNDLED_DATASETS = Path(
    "mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/library/BundledDatasets.kt"
)
MOBILE_BUNDLES = Path("scripts/mobile_bundles.py")
#: what the stand writes that must not leave the runner (private keys) or is only a scratch clone
PRIVATE = ("keys/*.pem", "tls/server.key")
SCRATCH = ("work",)


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


class Run:
    def __init__(self, out: Path) -> None:
        self.out = out
        self.checks: list[sv.Check] = []
        self.facts: dict[str, Any] = {}

    def add(
        self, id: str, phase: str, title: str, verdict: Verdict, detail: str, **kw: Any
    ) -> None:
        self.checks.append(sv.Check(id, phase, title, verdict, detail, **kw))
        print(f"[staging] {id} {verdict.value}: {title} - {detail}", file=sys.stderr)

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


def _phase(r: Run, name: str, body: Callable[[], None]) -> bool:
    """Run a phase; a crash is that phase's FAIL with its traceback kept, never lost."""
    try:
        body()
    except Exception as exc:  # noqa: BLE001 - recorded as the phase's failure
        log = r.out / "logs" / f"{name}-crash.txt"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(traceback.format_exc(), encoding="utf-8")
        r.add(f"{name}-crash", name, f"phase {name} crashed", Verdict.FAIL, f"{exc!r}; {log}")
        return False
    return True


# --------------------------------------------------------------------------- titles

PUBLISH = {
    "P1": "both releases built from the commit by the publisher, gates G1-G8 PASS on staging",
    "P2": "the built releases hold exactly the commit's data (path, SHA-256, size)",
    "P3": "the staged store holds every built file; the catalog verifies with the staging key only",
    "P4": "staging carries the base first, then lists both and activates the candidate",
    "P5": "isolated stand: ephemeral staging key, loopback HTTPS, the publisher refuses G6",
}
CLIENT = {
    "K0": "Kotlin client: the candidate suite ran, none skipped",
    "K1": "the client verified the requested pair and commit; its installed copy is the build",
}


def _scenario_title(scenario: str) -> str:
    return next(s.title for s in sv.CLIENT_SCENARIOS if s.id == scenario)


def publish_not_established(r: Run, why: str) -> None:
    for id, title in PUBLISH.items():
        r.add(id, "publish", title, Verdict.BLOCKED, why)


def client_not_established(r: Run, verdict: Verdict, why: str) -> None:
    """Every client check ``verdict`` (``NOT RUN``: not started on purpose; ``BLOCKED``: could
    not run or report), so a missing client run can never look like a passed one."""
    for id, title in CLIENT.items():
        r.add(id, "client", title, verdict, why)
    for scenario in sv.client_scenario_verdicts({}, ran=verdict is not Verdict.NOT_RUN):
        r.add(scenario, "client", _scenario_title(scenario), verdict, why)


# --------------------------------------------------------------------------- 1. resolve


def phase_resolve(r: Run, revision: str, base: str, candidate: str) -> sv.Subject | None:
    title = "the revision is fixed to one commit on origin/main; both builds are committed there"
    try:
        subject = sv.resolve_subject(ROOT, revision, base, candidate)
    except sv.InputError as exc:
        r.add("R1", "resolve", title, Verdict.BLOCKED, str(exc))
        return None
    r.facts["subject"] = {
        "revisionInput": subject.revision_input,
        "sha": subject.sha,
        "source": subject.source,
        "base": subject.base,
        "candidate": subject.candidate,
        "baseRelease": subject.base_release,
        "candidateRelease": subject.candidate_release,
    }
    r.add(
        "R1",
        "resolve",
        title,
        Verdict.PASS,
        f"{subject.sha} ({subject.source}); {subject.base} -> {subject.candidate}",
    )
    return subject


# --------------------------------------------------------------------------- 2. publish


def phase_publish(r: Run, subject: sv.Subject) -> None:
    from talent_data.ota.models import Channel
    from talent_data.ota.verify import OtaError, load_keyring, verify_catalog

    directory = r.out / "stand"
    log = r.out / "logs" / "publisher.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    captured = io.StringIO()
    started = time.monotonic()
    try:
        with contextlib.redirect_stdout(captured):
            report = stand.prepare(
                directory,
                commit=subject.sha,
                now=_now(),
                base=subject.base,
                candidate=subject.candidate,
            )
    finally:
        log.write_text(captured.getvalue(), encoding="utf-8")
    seconds = round(time.monotonic() - started)
    a, b = subject.base_release, subject.candidate_release
    gates = sv.publisher_gates(captured.getvalue())
    snapshots = {s["name"]: s for s in report["snapshots"]}
    r.facts["stand"] = {
        "keyId": report["keyId"],
        "commit": report["commit"],
        "seconds": seconds,
        "gates": gates,
        "manifests": report["manifests"],
        "publisherLog": report["publisherLog"],
        "snapshots": report["snapshots"],
        "g6": report["g6"],
    }

    # P1: built from the fixed commit, every gate PASS on the staging channel
    wanted = {a: gates.get(a, {}), b: gates.get(b, {})}
    passed = all(len(g) == 8 and set(g.values()) == {"PASS"} for g in wanted.values())
    r.expect(
        "P1",
        "publish",
        PUBLISH["P1"],
        report["commit"] == subject.sha
        and report["builds"] == {"a": subject.base, "b": subject.candidate}
        and passed,
        f"commit {report['commit']}; "
        + "; ".join(
            f"{rid}: " + (", ".join(f"{k} {v}" for k, v in sorted(g.items())) or "no gates")
            for rid, g in wanted.items()
        )
        + f"; logs/publisher.log ({seconds} s)",
    )

    # P2: the release files are the commit's files, nothing more, nothing less
    built = {
        rid: sv.release_files((directory / report["manifests"][rid]["path"]).read_bytes())
        for rid in (a, b)
    }
    problems: list[str] = []
    for rid, build in ((a, subject.base), (b, subject.candidate)):
        committed = sv.committed_files(ROOT, subject.sha, build)
        problems += [f"{rid}: {p}" for p in sv.compare_files(committed, built[rid])]
    r.facts["releases"] = {
        rid: {
            "manifestSha256": report["manifests"][rid]["sha256"],
            "files": len(files),
            "bytes": sum(size for _, size in files.values()),
        }
        for rid, files in built.items()
    }
    r.expect(
        "P2",
        "publish",
        PUBLISH["P2"],
        not problems,
        f"{a}: {len(built[a])} files, {b}: {len(built[b])} files vs. the committed data at "
        f"{subject.sha[:12]}: " + ("equal" if not problems else "; ".join(problems[:5])),
    )

    # P3: the staged store and the catalog's signature
    store = directory / "stores" / "2-b-staged"
    problems = []
    for rid in (a, b):
        manifest = (directory / report["manifests"][rid]["path"]).read_bytes()
        if snapshots["2-b-staged"]["manifests"].get(rid) != report["manifests"][rid]["sha256"]:
            problems.append(f"{rid}: the catalog pins another manifest")
        problems += [f"{rid}: {p}" for p in sv.store_problems(store, manifest)]
    catalog = (store / stand.CATALOG_KEY).read_bytes()
    published_at = stand.utc(report["now"])
    staging_keys = load_keyring(json.loads((directory / report["keyring"]).read_text())["keys"])
    verified = verify_catalog(
        catalog,
        keyring=staging_keys,
        game=sv.GAME,
        channel=Channel.STAGING,
        now=published_at,
        last_seen=None,
    )
    production_keys = load_keyring(
        json.loads((ROOT / replay.PRODUCTION_KEYRING).read_text())["keys"]
    )
    try:
        verify_catalog(
            catalog,
            keyring=production_keys,
            game=sv.GAME,
            channel=Channel.PRODUCTION,
            now=published_at,
            last_seen=None,
        )
        refused = None
    except OtaError as exc:
        refused = exc.code.value
    r.expect(
        "P3",
        "publish",
        PUBLISH["P3"],
        not problems and refused is not None,
        f"stores/2-b-staged: the manifests and {len(built[b])} files of {b}, {len(built[a])} of "
        f"{a} "
        + ("staged with their bytes" if not problems else "; ".join(problems[:5]))
        + f"; verify_catalog(staging, {report['keyId']}): sequence {verified.catalog.sequence}; "
        f"the production keyring refuses it ({refused})",
    )

    # P4: what a device sees, in order
    first, second = snapshots["1-a-staged"], snapshots["2-b-staged"]
    r.expect(
        "P4",
        "publish",
        PUBLISH["P4"],
        first["releases"] == [a]
        and first["activeReleaseId"] == a
        and second["releases"] == [a, b]
        and second["activeReleaseId"] == b
        and second["sequence"] == first["sequence"] + 1,
        f"1-a-staged: sequence {first['sequence']}, active {first['activeReleaseId']}, listed "
        f"{first['releases']}; 2-b-staged: sequence {second['sequence']}, active "
        f"{second['activeReleaseId']}, listed {second['releases']}",
    )

    # P5: isolation and the publisher's own refusals
    g6 = report["g6"]
    verify_live = [e["exit"] for e in report["publisherLog"] if e["command"] == "verify-live"]
    r.expect(
        "P5",
        "publish",
        PUBLISH["P5"],
        report["keyId"].startswith("tf-staging-local-")
        and g6["gates"].get("G6") == "STOP"
        and g6["storeUnchanged"]
        and verify_live == [0] * len(snapshots),
        f"key {report['keyId']} generated for this run, outside git, deleted at its end; the "
        f"client's origin is 127.0.0.1 over the stand's TLS; G6 attempt {g6['releaseId']}: "
        f"validate exit {g6['validateExit']}, stage exit {g6['stageExit']}, store unchanged; "
        f"verify-live after each of the {len(snapshots)} publications: {verify_live}",
    )

    # the built manifests stay with the report (the stand's work directory is scratch)
    for rid in (a, b):
        kept = r.out / "releases" / rid / "release.json"
        kept.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(directory / report["manifests"][rid]["path"], kept)

    # the client's bundle: the base's data of the same commit
    count = sv.export_build(ROOT, subject.sha, subject.base, r.out / "subject")
    r.facts["subjectExport"] = {"build": subject.base, "files": count, "commit": subject.sha}


# --------------------------------------------------------------------------- 3. client


def run_gradle(argv: list[str], log: Path) -> int:
    """Runs Gradle in ``mobile/`` with its output in ``log``; ``OSError`` when it cannot start."""
    with log.open("wb") as handle:
        return subprocess.run(
            argv, cwd=ROOT / "mobile", stdout=handle, stderr=subprocess.STDOUT, check=False
        ).returncode


def phase_client(r: Run, subject: sv.Subject) -> None:
    (JUNIT_DIR / JUNIT_XML).unlink(missing_ok=True)
    log = r.out / "logs" / "gradle-client.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    argv = [
        "./gradlew",
        ":composeApp:desktopTest",
        "--tests",
        f"*{sv.SUITE}",
        "--console=plain",
        f"-Dtalentforge.ota.candidate={r.out}",
        f"-Dtalentforge.ota.candidate.base={subject.base}",
        f"-Dtalentforge.ota.candidate.build={subject.candidate}",
        f"-Dtalentforge.ota.candidate.revision={subject.sha}",
    ]
    started = time.monotonic()
    try:
        code = run_gradle(argv, log)
    except OSError as exc:
        client_not_established(r, Verdict.BLOCKED, f"Gradle could not be started: {exc}")
        return
    seconds = round(time.monotonic() - started)
    xml = JUNIT_DIR / JUNIT_XML
    reports = []
    if xml.is_file():
        target = r.out / "junit" / xml.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(xml, target)
        reports.append(target)
    results = {
        name: verdict
        for name, verdict in replay.junit_results(reports).items()
        if name.startswith(f"{sv.SUITE}.")
    }
    expected = {t for s in sv.CLIENT_SCENARIOS for t in s.tests}
    evidence = {
        p.stem: json.loads(p.read_text(encoding="utf-8"))
        for p in sorted((r.out / "client").glob("*.json"))
    }
    r.facts["client"] = {
        "gradleExit": code,
        "seconds": seconds,
        "tests": {name: verdict.value for name, verdict in sorted(results.items())},
        "evidence": {
            name: {k: v for k, v in facts.items() if k != "installedFiles"}
            for name, facts in evidence.items()
        },
    }
    detail = (
        f"gradle exit {code} in {seconds} s; "
        f"{sum(v is Verdict.PASS for v in results.values())}/{len(expected)} tests PASS; "
        "logs/gradle-client.log"
    )
    if not results:
        r.add("K0", "client", CLIENT["K0"], Verdict.BLOCKED, f"no test results; {detail}")
    elif missing := sorted(expected - results.keys()):
        r.add("K0", "client", CLIENT["K0"], Verdict.BLOCKED, f"no result for {missing}; {detail}")
    else:
        verdict = replay.combine(results.values())
        if verdict is Verdict.PASS and code != 0:
            verdict = Verdict.FAIL
        r.add("K0", "client", CLIENT["K0"], verdict, detail)

    # K1: the evidence names the requested pair and commit; the installed copy is the build
    move = sv.MOVE.split(".")[1]
    built = r.out / "releases" / subject.candidate_release / "release.json"
    published = sv.release_files(built.read_bytes())
    if move not in evidence:
        result = results.get(sv.MOVE, Verdict.BLOCKED).value
        r.add("K1", "client", CLIENT["K1"], Verdict.BLOCKED, f"no client/{move}.json ({result})")
    else:
        problems = sv.evidence_problems(evidence[move], subject, published)
        requested = {"base": subject.base, "candidate": subject.candidate, "revision": subject.sha}
        problems += [
            f"{name}: requested {facts.get('requested')}"
            for name, facts in evidence.items()
            if facts.get("requested") != requested
        ]
        r.expect(
            "K1",
            "client",
            CLIENT["K1"],
            not problems,
            f"{len(evidence)} evidence file(s) name {subject.base} -> {subject.candidate} at "
            f"{subject.sha[:12]}; installed {len(evidence[move].get('installedFiles') or {})} "
            f"files vs. the {len(published)} built: "
            + ("path, SHA-256, size equal" if not problems else "; ".join(problems[:5])),
        )
    for scenario, verdict in sv.client_scenario_verdicts(results, ran=True).items():
        tests = next(s.tests for s in sv.CLIENT_SCENARIOS if s.id == scenario)
        r.add(
            scenario,
            "client",
            _scenario_title(scenario),
            verdict,
            ", ".join(f"{t} {results.get(t, Verdict.BLOCKED).value}" for t in tests),
        )


# --------------------------------------------------------------------------- guards


def checkout_state() -> dict[str, str]:
    """What the run must leave untouched: the checkout, PRODUCT_BUNDLE, BUNDLED_BUILDS, the
    production keyring (the tracked files' status covers app versions and every other file)."""

    def sha(path: Path) -> str:
        return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()

    return {
        "head": _git("rev-parse", "HEAD"),
        "status": _git("status", "--porcelain", "--untracked-files=no"),
        "productionKeyring": sha(replay.PRODUCTION_KEYRING),
        "bundledDatasets": sha(BUNDLED_DATASETS),
        "mobileBundles": sha(MOBILE_BUNDLES),
    }


def scrub(out: Path) -> list[str]:
    """Deletes the stand's private keys and scratch clone; returns what was removed."""
    removed = []
    directory = out / "stand"
    for pattern in PRIVATE:
        for path in sorted(directory.glob(pattern)):
            path.unlink()
            removed.append(path.relative_to(out).as_posix())
    for name in SCRATCH:
        if (directory / name).exists():
            shutil.rmtree(directory / name)
            removed.append(f"stand/{name}/")
    return removed


# --------------------------------------------------------------------------- report


def render_summary(r: Run, verdict: Verdict, requested: dict[str, str]) -> str:
    f = r.facts
    subject = f.get("subject")
    pair = f"{requested['base']} → {requested['candidate']}"
    lines = [f"# OTA staging verification {pair}: {verdict.value}", ""]
    if subject:
        lines.append(f"* Commit `{subject['sha']}` ({subject['source']})")
    else:
        lines.append(f"* Commit: not resolved (revision {requested['revision']!r})")
    lines += [
        f"* Base `{requested['base']}`, candidate `{requested['candidate']}`; tooling checkout "
        f"`{f['tooling']['head']}`; {f['startedAt']} → {f['finishedAt']}",
        "* Isolated stand on this machine: ephemeral `tf-staging-local-*` key "
        f"(`{f.get('stand', {}).get('keyId', '-')}`), loopback HTTPS, no cloud credential; "
        "production was neither read nor written.",
        "* **A staging PASS is not a production approval**: the approval record, the owner's "
        "promotion and offline signing are unchanged (publisher-runbook.md).",
        "* Automatic run: the JVM client (`desktopTest`). Android, iOS and physical devices are "
        "NOT RUN here and never count.",
        "",
        "| id | phase | check | verdict | detail |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {c.id} | {c.phase} | {c.title} | {c.verdict.value} | {c.detail.replace('|', '/')} |"
        for c in r.checks
    ]
    move = f.get("client", {}).get("evidence", {}).get(sv.MOVE.split(".")[1])
    if move:
        lines += [
            "",
            "## What the client received",
            "",
            f"* Over HTTPS: {move['downloadedFiles']} files, {move['downloadedBytes']} bytes "
            f"({move['downloadedByRole']}) and the manifest; blob requests: {move['blobRequests']}",
            "* From the installed base release (the client's cache): "
            f"{move['copiedFromInstalledBase']} files ({move['copiedByRole']})",
            f"* From the bundle: {move['copiedFromBundle']} files of the candidate",
            f"* Saved builds checked: {move['savedBuilds']}",
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
        return sv.EXIT_USAGE
    if out.exists() and any(out.iterdir()):
        print(f"error: {out} is not empty", file=sys.stderr)
        return sv.EXIT_USAGE
    out.mkdir(parents=True, exist_ok=True)
    r = Run(out)
    requested = {"revision": args.revision, "base": args.base, "candidate": args.candidate}
    before = checkout_state()
    r.facts.update(startedAt=_stamp(_now()), requested=requested, tooling={"head": before["head"]})

    subject = phase_resolve(r, args.revision, args.base, args.candidate)
    published = False
    if subject is None:
        publish_not_established(r, "not established: the revision or the builds are unusable (R1)")
    else:
        published = _phase(r, "publish", lambda: phase_publish(r, subject))

    if args.skip_client:
        client_not_established(r, Verdict.NOT_RUN, "--skip-client")
    elif subject is None or not published:
        client_not_established(r, Verdict.BLOCKED, "not established: nothing was staged")
    else:
        _phase(r, "client", lambda: phase_client(r, subject))

    after = checkout_state()
    r.expect(
        "G1",
        "guard",
        "checkout, PRODUCT_BUNDLE, BUNDLED_BUILDS and the production keyring unchanged",
        before == after,
        f"HEAD {after['head'][:12]}, tracked files {'clean' if not after['status'] else 'dirty'} "
        f"as before: {before['status'] == after['status']}, BundledDatasets.kt sha256 "
        f"{after['bundledDatasets'][:12]}, production keyring sha256 "
        f"{after['productionKeyring'][:12]}",
    )
    removed = scrub(out)
    left = sv.private_keys(out)
    r.expect(
        "G2",
        "guard",
        "no private key is left in the report directory",
        not left,
        f"deleted {removed or 'nothing'}; " + (f"still found: {left}" if left else "none found"),
    )
    r.add(
        "M1",
        "device",
        "Android: emulator and physical device",
        Verdict.NOT_RUN,
        "not automated: a debug APK whose product is the base and that does not bundle the "
        "candidate, by the procedure of docs/ota/staging-verification.md §6",
        automatic=False,
    )
    r.add(
        "M2",
        "device",
        "iOS: simulator and physical device",
        Verdict.NOT_RUN,
        "not automated, no procedure against the local stand yet "
        "(docs/ota/staging-verification.md §6)",
        automatic=False,
    )

    verdict = sv.overall(r.checks)
    r.facts["finishedAt"] = _stamp(_now())
    document = {
        "format": "talentforge-ota-staging-verification",
        "formatVersion": 1,
        "verdict": verdict.value,
        "productionApproval": False,
        "revision": r.facts.get("subject", {}).get("sha"),
        "baseBuild": args.base,
        "candidateBuild": args.candidate,
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
    (out / "staging-report.json").write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (out / "junit").mkdir(exist_ok=True)
    (out / "junit" / "staging-checks.xml").write_text(sv.junit_xml(r.checks), encoding="utf-8")
    summary = render_summary(r, verdict, requested)
    (out / "summary.md").write_text(summary, encoding="utf-8")
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(step_summary).open("a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary)
    return sv.exit_code(verdict)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    go = sub.add_parser("run", help="the whole verification into an empty directory outside git")
    go.add_argument("out", type=Path)
    go.add_argument("--base", required=True, help="the build the app runs before the update")
    go.add_argument("--candidate", required=True, help="the build whose data is verified")
    go.add_argument(
        "--revision", default="", help="commit or ref on origin/main (default: its HEAD)"
    )
    go.add_argument("--skip-client", action="store_true", help="no Gradle: NOT RUN")
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
