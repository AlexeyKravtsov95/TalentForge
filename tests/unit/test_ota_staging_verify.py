"""Staging verification of an OTA candidate (M5.OTA.AUTO.4, ``scripts/ota_staging_verify.py``).

Offline: the revision is resolved in temporary git repositories; the publish phase runs the real
staging stand and publisher on the repository's committed 1.60.1.70450 and 1.60.1.70532; the
client phase is driven with a stand-in for Gradle that writes the JUnit report and the evidence
the real ``OtaCandidateStagingTest`` writes, so every outcome of the run - PASS, FAIL, BLOCKED,
NOT RUN - and its exit code is exercised. The real Kotlin client runs in the workflow
(``.github/workflows/ota-staging-verify.yml``), not here.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from talent_data.prepare import staging_verify as sv
from talent_data.prepare.replay import Verdict

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = (
    ROOT / "mobile/composeApp/src/desktopTest/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota"
)
WORKFLOW = ROOT / ".github/workflows/ota-staging-verify.yml"
BASE, CANDIDATE = "1.60.1.70450", "1.60.1.70532"


def _script() -> ModuleType:
    name = "ota_staging_verify"
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


script = _script()


# --------------------------------------------------------------------------- the revision


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True, text=True
    ).stdout.strip()


@pytest.fixture
def repo(tmp_path: Path) -> dict[str, Any]:
    """A repository whose origin/main carries 70450 and 70532, plus a commit off main."""
    path = tmp_path / "repo"
    path.mkdir()
    _git(path, "init", "-q", "-b", "main")
    for build in (BASE, CANDIDATE):
        manifest = path / f"data/normalized/forever/{build}/manifest.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text("{}\n", encoding="utf-8")
    ident = ("-c", "user.email=t@invalid", "-c", "user.name=t")
    _git(path, "add", ".")
    _git(path, *ident, "commit", "-qm", "data")
    on_main = _git(path, "rev-parse", "HEAD")
    _git(path, "update-ref", "refs/remotes/origin/main", on_main)
    _git(path, "tag", "data-1", on_main)
    _git(path, "checkout", "-qb", "side")
    (path / "side.txt").write_text("x\n", encoding="utf-8")
    _git(path, "add", ".")
    _git(path, *ident, "commit", "-qm", "side")
    return {"path": path, "main": on_main, "side": _git(path, "rev-parse", "HEAD")}


@pytest.mark.parametrize("revision", ["", "  ", "origin/main", "data-1", "SHORT", "FULL"])
def test_the_revision_is_fixed_to_one_full_commit(repo: dict[str, Any], revision: str) -> None:
    named = {"SHORT": repo["main"][:9], "FULL": repo["main"]}.get(revision, revision)
    subject = sv.resolve_subject(repo["path"], named, BASE, CANDIDATE)
    assert subject.sha == repo["main"] and len(subject.sha) == 40
    assert (subject.base, subject.candidate) == (BASE, CANDIDATE)
    assert (subject.base_release, subject.candidate_release) == (f"{BASE}-r1", f"{CANDIDATE}-r1")
    if named.strip():
        assert subject.source == f"input {named.strip()!r}"
    else:
        assert subject.source == "HEAD of origin/main (no revision given)"


@pytest.mark.parametrize(
    ("revision", "base", "candidate", "messages"),
    [
        ("--upload-pack=touch", BASE, CANDIDATE, ["neither a commit id nor a ref name"]),
        ("a b", BASE, CANDIDATE, ["neither a commit id nor a ref name"]),
        ("no-such-ref", BASE, CANDIDATE, ["does not name a commit"]),
        ("SIDE", BASE, CANDIDATE, ["is not on origin/main"]),
        ("", "1.60.1", CANDIDATE, ["base: not a WoW client version"]),
        ("", "1.60.1.070450", CANDIDATE, ["base: '1.60.1.070450' is not in canonical form"]),
        ("", CANDIDATE, BASE, [f"the candidate {BASE} is not newer than the base {CANDIDATE}"]),
        ("", BASE, BASE, [f"the candidate {BASE} is not newer than the base {BASE}"]),
        ("", BASE, "1.60.1.70999", ["candidate 1.60.1.70999: no committed dataset"]),
        (
            "SIDE",
            "1.60.1.69000",
            "x",
            ["is not on origin/main", "base 1.60.1.69000: no committed", "candidate: not a WoW"],
        ),
    ],
)
def test_an_unusable_revision_or_build_is_named(
    repo: dict[str, Any], revision: str, base: str, candidate: str, messages: list[str]
) -> None:
    with pytest.raises(sv.InputError) as raised:
        sv.resolve_subject(repo["path"], revision.replace("SIDE", repo["side"]), base, candidate)
    for message in messages:
        assert message in str(raised.value)


# --------------------------------------------------------------------------- the pure parts


def test_file_sets_are_compared_by_path_hash_and_size() -> None:
    a = {"x": ("sha256:1", 1), "y": ("sha256:2", 2)}
    assert sv.compare_files(a, dict(a)) == []
    assert sv.compare_files(a, {"x": ("sha256:1", 1), "z": ("sha256:3", 3)}) == [
        "missing y",
        "unexpected z",
    ]
    assert sv.compare_files(a, {"x": ("sha256:1", 9), "y": ("sha256:2", 2)}) == [
        "x: ('sha256:1', 1) != ('sha256:1', 9)"
    ]


def test_a_store_must_hold_every_file_with_its_bytes(tmp_path: Path) -> None:
    good, other = b"talent data", b"other bytes"

    def ref(data: bytes) -> str:
        return "sha256:" + hashlib.sha256(data).hexdigest()

    manifest = json.dumps(
        {"files": [{"path": "dataset/a.json", "sha256": ref(good), "byteLength": len(good)}]}
    ).encode()
    blobs = tmp_path / "v1/blobs/sha256"
    blobs.mkdir(parents=True)
    assert sv.store_problems(tmp_path, manifest) == [
        f"dataset/a.json: blob {ref(good)} not staged",
        f"release.json: blob {ref(manifest)} not staged",
    ]
    (blobs / ref(manifest).removeprefix("sha256:")).write_bytes(manifest)
    (blobs / ref(good).removeprefix("sha256:")).write_bytes(other)
    assert sv.store_problems(tmp_path, manifest) == [
        f"dataset/a.json: blob {ref(good)} holds other bytes (11 bytes)"
    ]
    (blobs / ref(good).removeprefix("sha256:")).write_bytes(good)
    assert sv.store_problems(tmp_path, manifest) == []


def test_the_publisher_gates_are_read_from_its_output() -> None:
    log = (
        "gates for 1.60.1.70450-r1 (staging):\n  G1 PASS  on main\n  G2 PASS  9 classes\n"
        "stage ...\n"
        "gates for 1.60.1.70532-r1 (staging):\n  G1 STOP  not on main\n  G7 FLAG  evidence\n"
        "gates for 1.60.1.70532-r1 (staging):\n  G1 PASS  on main\n"
        "OK   v1/channels/staging/catalog.json\n  G8 PASS  not under a header\n"
    )
    assert sv.publisher_gates(log) == {
        "1.60.1.70450-r1": {"G1": "PASS", "G2": "PASS"},
        "1.60.1.70532-r1": {"G1": "PASS"},  # the last block of a release wins
    }


def test_only_every_automatic_pass_is_a_pass() -> None:
    def check(verdict: Verdict, automatic: bool = True) -> sv.Check:
        return sv.Check("X", "p", "t", verdict, "", automatic=automatic)

    device = check(Verdict.NOT_RUN, automatic=False)
    assert sv.overall([check(Verdict.PASS), device]) is Verdict.PASS
    assert sv.overall([check(Verdict.PASS), check(Verdict.NOT_RUN)]) is Verdict.NOT_RUN
    assert sv.overall([check(Verdict.NOT_RUN), check(Verdict.BLOCKED)]) is Verdict.BLOCKED
    assert sv.overall([check(Verdict.BLOCKED), check(Verdict.FAIL)]) is Verdict.FAIL
    assert sv.overall([device]) is Verdict.NOT_RUN  # no automatic evidence proves nothing
    assert [sv.exit_code(v) for v in Verdict] == [0, 1, 3, 3]


def test_a_scenario_without_its_test_result_never_passes() -> None:
    passed = {t: Verdict.PASS for s in sv.CLIENT_SCENARIOS for t in s.tests}
    assert set(sv.client_scenario_verdicts(passed, ran=True).values()) == {Verdict.PASS}
    assert set(sv.client_scenario_verdicts({}, ran=False).values()) == {Verdict.NOT_RUN}
    assert set(sv.client_scenario_verdicts({}, ran=True).values()) == {Verdict.BLOCKED}
    one_missing = {k: v for k, v in passed.items() if k != sv.BLOB}
    verdicts = sv.client_scenario_verdicts(one_missing, ran=True)
    assert verdicts["N2"] is Verdict.BLOCKED and verdicts["N1"] is Verdict.PASS


def test_every_scenario_names_an_existing_kotlin_test() -> None:
    """A renamed Kotlin test must not silently turn a scenario into BLOCKED or NOT RUN."""
    source = (KOTLIN / f"{sv.SUITE}.kt").read_text(encoding="utf-8")
    methods = {f"{sv.SUITE}.{m}" for m in re.findall(r"@Test\s+fun (\w+)\(", source)}
    named = {t for s in sv.CLIENT_SCENARIOS for t in s.tests}
    assert named == methods
    assert [s.id for s in sv.CLIENT_SCENARIOS] == [f"C{i}" for i in range(1, 7)] + [
        f"N{i}" for i in range(1, 5)
    ]


def test_the_junit_report_keeps_the_four_verdicts_apart() -> None:
    checks = [
        sv.Check("A", "publish", "a", Verdict.PASS, "ok"),
        sv.Check("B", "client", "b", Verdict.FAIL, "broke"),
        sv.Check("C", "client", "c", Verdict.BLOCKED, "no gradle"),
        sv.Check("D", "device", "d", Verdict.NOT_RUN, "manual", automatic=False),
    ]
    root = ET.fromstring(sv.junit_xml(checks))
    assert {k: root.get(k) for k in ("tests", "failures", "errors", "skipped")} == {
        "tests": "4",
        "failures": "1",
        "errors": "1",
        "skipped": "1",
    }
    cases = {c.get("name"): c for c in root.iter("testcase")}
    assert cases["A"].find("failure") is None and cases["A"].find("error") is None
    failure = cases["B"].find("failure")
    assert failure is not None and failure.text == "broke"
    assert cases["C"].find("error") is not None and cases["D"].find("skipped") is not None


def test_private_keys_are_found(tmp_path: Path) -> None:
    (tmp_path / "keys").mkdir()
    (tmp_path / "keys/k.pem").write_bytes(b"-----BEGIN PRIVATE KEY-----\nAA\n")
    (tmp_path / "keys/keyring.json").write_text('{"keys": []}', encoding="utf-8")
    assert sv.private_keys(tmp_path) == ["keys/k.pem"]


# --------------------------------------------------------------------------- the run


def _report(out: Path) -> dict[str, Any]:
    document: dict[str, Any] = json.loads((out / "staging-report.json").read_text("utf-8"))
    return document


def _verdicts(out: Path) -> dict[str, str]:
    return {c["id"]: c["verdict"] for c in _report(out)["checks"]}


@pytest.fixture(scope="module")
def staged(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """One real run without the client: the stand and publisher on 70450 -> 70532."""
    out = tmp_path_factory.mktemp("staging") / "out"
    code = script.main(["run", str(out), "--base", BASE, "--candidate", CANDIDATE, "--skip-client"])
    assert code == sv.EXIT_INCOMPLETE
    return out


def test_the_offline_phases_end_to_end(staged: Path) -> None:
    main = _git(ROOT, "rev-parse", "origin/main")
    report = _report(staged)
    client = ["K0", "K1", "C1", "C2", "C3", "C4", "C5", "C6", "N1", "N2", "N3", "N4"]
    assert _verdicts(staged) == {
        "R1": "PASS",
        **dict.fromkeys(("P1", "P2", "P3", "P4", "P5"), "PASS"),
        **dict.fromkeys(client, "NOT RUN"),
        "G1": "PASS",
        "G2": "PASS",
        "M1": "NOT RUN",
        "M2": "NOT RUN",
    }
    # the full commit and both versions, fixed once
    assert report["verdict"] == "NOT RUN" and report["productionApproval"] is False
    assert (report["revision"], report["baseBuild"], report["candidateBuild"]) == (
        main,
        BASE,
        CANDIDATE,
    )
    assert report["facts"]["stand"]["commit"] == main == report["facts"]["subjectExport"]["commit"]
    assert all(not c["automatic"] for c in report["checks"] if c["phase"] == "device")
    # the built manifests are kept, the client's bundle is the base of that commit
    for build in (BASE, CANDIDATE):
        manifest = json.loads((staged / f"releases/{build}-r1/release.json").read_bytes())
        assert manifest["clientBuild"] == build
        assert manifest["provenance"]["repositoryCommit"] == main
    assert (staged / f"subject/data/normalized/forever/{BASE}/manifest.json").is_file()
    assert not (staged / f"subject/data/normalized/forever/{CANDIDATE}").exists()
    # no private key survives the run; the public keyring and the CA stay
    assert sv.private_keys(staged) == []
    assert not list((staged / "stand/keys").glob("*.pem"))
    assert not (staged / "stand/tls/server.key").exists() and not (staged / "stand/work").exists()
    assert (staged / "stand/keys/staging-keyring.json").is_file()
    junit = ET.parse(staged / "junit/staging-checks.xml").getroot()
    assert junit.get("skipped") == "14" and junit.get("failures") == junit.get("errors") == "0"
    summary = (staged / "summary.md").read_text(encoding="utf-8")
    assert f"# OTA staging verification {BASE} → {CANDIDATE}: NOT RUN" in summary
    assert f"* Commit `{main}`" in summary
    assert "**A staging PASS is not a production approval**" in summary


def test_unusable_inputs_are_blocked_with_a_report(tmp_path: Path) -> None:
    out = tmp_path / "out"
    code = script.main(["run", str(out), "--base", CANDIDATE, "--candidate", BASE])
    assert code == sv.EXIT_INCOMPLETE
    verdicts = _verdicts(out)
    assert verdicts.pop("R1") == "BLOCKED"
    assert {verdicts.pop(g) for g in ("G1", "G2")} == {"PASS"}
    assert {verdicts.pop(m) for m in ("M1", "M2")} == {"NOT RUN"}
    assert set(verdicts.values()) == {"BLOCKED"}  # publish and every client check
    report = _report(out)
    assert report["verdict"] == "BLOCKED" and report["revision"] is None
    assert "not newer than the base" in report["checks"][0]["detail"]
    assert not (out / "stand").exists()
    assert "* Commit: not resolved" in (out / "summary.md").read_text(encoding="utf-8")


def test_the_entrypoint_refuses_an_output_inside_git_or_not_empty(tmp_path: Path) -> None:
    argv = ["--base", BASE, "--candidate", CANDIDATE, "--skip-client"]
    assert script.main(["run", str(ROOT / "build" / "staging-out"), *argv]) == sv.EXIT_USAGE
    (tmp_path / "busy").mkdir()
    (tmp_path / "busy" / "file").write_text("x", encoding="utf-8")
    assert script.main(["run", str(tmp_path / "busy"), *argv]) == sv.EXIT_USAGE
    assert not (ROOT / "build" / "staging-out").exists()


# --------------------------------------------------------------------------- the client phase

Gradle = Callable[[list[str], Path], int]


def _evidence(out: Path, pair: tuple[str, str] = (BASE, CANDIDATE)) -> dict[str, Any]:
    """What ``theCandidateReplacesTheBaseOverHttps`` writes after installing the built release."""
    main = _git(ROOT, "rev-parse", "origin/main")
    built = sv.release_files((out / f"releases/{CANDIDATE}-r1/release.json").read_bytes())
    base, candidate = pair
    return {
        "base": base,
        "candidate": candidate,
        "candidateRelease": f"{candidate}-r1",
        "commit": main,
        "installedFiles": {path: [ref, size] for path, (ref, size) in built.items()},
        "requested": {"base": base, "candidate": candidate, "revision": main},
        "downloadedFiles": 38,
        "downloadedBytes": 1000,
        "downloadedByRole": {"dataset": 10},
        "blobRequests": 39,
        "copiedFromInstalledBase": 359,
        "copiedByRole": {"visuals": 359},
        "copiedFromBundle": 0,
        "savedBuilds": [{"class": "MAGE", "clientBuild": BASE, "allocations": 9}],
    }


def _gradle(
    junit_dir: Path,
    *,
    outcomes: dict[str, str] | None = None,
    evidence: Callable[[Path], dict[str, Any]] | None = _evidence,
    exit_code: int = 0,
    seen: list[list[str]] | None = None,
) -> Gradle:
    """A stand-in for ``./gradlew``: the JUnit XML and the evidence of OtaCandidateStagingTest.
    ``outcomes``: method -> ``pass`` (default) / ``fail`` / ``skip``; ``None`` writes no XML."""

    def run(argv: list[str], log: Path) -> int:
        if seen is not None:
            seen.append(argv)
        log.write_text("stand-in gradle\n", encoding="utf-8")
        prefix = "-Dtalentforge.ota.candidate="
        out = Path(next(a for a in argv if a.startswith(prefix)).removeprefix(prefix))
        if outcomes is not None:
            classname = f"io.github.x.{sv.SUITE}"
            suite = ET.Element("testsuite", name=classname)
            for test in sorted({t for s in sv.CLIENT_SCENARIOS for t in s.tests}):
                method = test.split(".")[1]
                case = ET.SubElement(
                    suite, "testcase", name=f"{method}[desktop]", classname=classname
                )
                outcome = outcomes.get(method, "pass")
                if outcome == "fail":
                    ET.SubElement(case, "failure", message="assertion failed")
                elif outcome == "skip":
                    ET.SubElement(case, "skipped")
            junit_dir.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(junit_dir / script.JUNIT_XML)
        if evidence is not None:
            (out / "client").mkdir(exist_ok=True)
            (out / "client/theCandidateReplacesTheBaseOverHttps.json").write_text(
                json.dumps(evidence(out)), encoding="utf-8"
            )
        return exit_code

    return run


def _client(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path, gradle: Gradle
) -> dict[str, str]:
    """The client phase alone, against the module's stand (shared read-only)."""
    out = tmp_path / "out"
    out.mkdir()
    for name in ("stand", "releases"):
        (out / name).symlink_to(staged / name)
    monkeypatch.setattr(script, "JUNIT_DIR", tmp_path / "junit")
    monkeypatch.setattr(script, "run_gradle", gradle)
    r = script.Run(out)
    r.facts["stand"] = _report(staged)["facts"]["stand"]
    subject = sv.resolve_subject(ROOT, "", BASE, CANDIDATE)
    script.phase_client(r, subject)
    return {c.id: c.verdict.value for c in r.checks}


def test_a_complete_client_run_passes(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path
) -> None:
    seen: list[list[str]] = []
    gradle = _gradle(tmp_path / "junit", outcomes={}, seen=seen)
    verdicts = _client(monkeypatch, staged, tmp_path, gradle)
    assert set(verdicts.values()) == {"PASS"} and len(verdicts) == 12
    (argv,) = seen
    main = _git(ROOT, "rev-parse", "origin/main")
    assert argv[:4] == ["./gradlew", ":composeApp:desktopTest", "--tests", f"*{sv.SUITE}"]
    assert f"-Dtalentforge.ota.candidate.base={BASE}" in argv
    assert f"-Dtalentforge.ota.candidate.build={CANDIDATE}" in argv
    assert f"-Dtalentforge.ota.candidate.revision={main}" in argv
    assert (tmp_path / f"out/junit/{script.JUNIT_XML}").is_file()


def test_the_historical_scenario_is_not_a_check_of_the_candidate(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path
) -> None:
    """Evidence of another pair (the 70374 -> 70450 replay) fails, whatever the tests said."""

    def historical(out: Path) -> dict[str, Any]:
        return _evidence(out, pair=("1.60.1.70374", "1.60.1.70450"))

    gradle = _gradle(tmp_path / "junit", outcomes={}, evidence=historical)
    verdicts = _client(monkeypatch, staged, tmp_path, gradle)
    assert verdicts.pop("K1") == "FAIL"
    assert set(verdicts.values()) == {"PASS"}


def test_an_installed_copy_other_than_the_build_fails(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path
) -> None:
    def changed(out: Path) -> dict[str, Any]:
        facts = _evidence(out)
        path = sorted(facts["installedFiles"])[0]
        facts["installedFiles"][path][1] += 1
        return facts

    gradle = _gradle(tmp_path / "junit", outcomes={}, evidence=changed)
    assert _client(monkeypatch, staged, tmp_path, gradle)["K1"] == "FAIL"


@pytest.mark.parametrize(
    ("outcomes", "exit_code", "expected"),
    [
        # a failed negative check fails its scenario and the suite
        ({"aCorruptBlobChangesNothing": "fail"}, 1, {"K0": "FAIL", "N2": "FAIL"}),
        # a skipped test proves nothing
        (
            {"anInterruptedDownloadResumesAfterARestart": "skip"},
            0,
            {"K0": "NOT RUN", "N3": "NOT RUN"},
        ),
        # every test passed, but Gradle did not succeed
        ({}, 1, {"K0": "FAIL"}),
    ],
)
def test_a_failed_or_skipped_client_test_is_never_a_pass(
    monkeypatch: pytest.MonkeyPatch,
    staged: Path,
    tmp_path: Path,
    outcomes: dict[str, str],
    exit_code: int,
    expected: dict[str, str],
) -> None:
    gradle = _gradle(tmp_path / "junit", outcomes=outcomes, exit_code=exit_code)
    verdicts = _client(monkeypatch, staged, tmp_path, gradle)
    for check, verdict in expected.items():
        assert verdicts.pop(check) == verdict, check
    assert set(verdicts.values()) == {"PASS"}


def test_a_client_without_results_is_blocked(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path
) -> None:
    """Gradle ran but reported nothing (a dependency unavailable, a compile error)."""
    gradle = _gradle(tmp_path / "junit", outcomes=None, evidence=None, exit_code=1)
    verdicts = _client(monkeypatch, staged, tmp_path, gradle)
    assert set(verdicts.values()) == {"BLOCKED"} and len(verdicts) == 12


def test_a_gradle_that_cannot_start_is_blocked(
    monkeypatch: pytest.MonkeyPatch, staged: Path, tmp_path: Path
) -> None:
    def missing(argv: list[str], log: Path) -> int:
        raise FileNotFoundError("./gradlew")

    verdicts = _client(monkeypatch, staged, tmp_path, missing)
    assert set(verdicts.values()) == {"BLOCKED"} and len(verdicts) == 12


def test_a_whole_run_with_a_passing_client_exits_0(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(script, "JUNIT_DIR", tmp_path / "junit")
    monkeypatch.setattr(script, "run_gradle", _gradle(tmp_path / "junit", outcomes={}))
    summary = tmp_path / "step-summary.md"
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(summary))
    out = tmp_path / "out"
    code = script.main(["run", str(out), "--base", BASE, "--candidate", CANDIDATE])
    assert code == sv.EXIT_PASS
    report = _report(out)
    assert report["verdict"] == "PASS" and report["productionApproval"] is False
    assert {c["verdict"] for c in report["checks"] if c["automatic"]} == {"PASS"}
    assert {c["id"] for c in report["checks"] if not c["automatic"]} == {"M1", "M2"}
    assert sv.private_keys(out) == []
    assert summary.read_text(encoding="utf-8").startswith(
        f"# OTA staging verification {BASE} → {CANDIDATE}: PASS"
    )
    assert "## What the client received" in (out / "summary.md").read_text(encoding="utf-8")
    assert {p.name for p in (out / "junit").iterdir()} == {
        "staging-checks.xml",
        script.JUNIT_XML,
    }


# --------------------------------------------------------------------------- the workflow


def workflow_step_script(name: str) -> str:
    """The ``run: |`` block of one workflow step, dedented (no YAML library needed)."""
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = lines.index(f"      - name: {name}")
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    body: list[str] = []
    for line in lines[run + 1 :]:
        if line and not line.startswith(" " * 10):
            break
        body.append(line[10:])
    return "\n".join(body) + "\n"


@pytest.mark.parametrize(
    ("verify_exit", "job_exit", "message"),
    [
        ("0", 0, "PASS (not a production approval)"),
        ("1", 1, "FAIL"),
        ("3", 1, "BLOCKED or NOT RUN"),
        ("2", 1, "did not complete (exit '2')"),
        ("", 1, "did not complete (exit 'none')"),  # the step died before recording its exit
    ],
)
def test_the_job_is_green_only_for_a_pass(verify_exit: str, job_exit: int, message: str) -> None:
    # GitHub runs ``shell: bash`` as ``bash --noprofile --norc -eo pipefail {0}``
    bash = ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c"]
    completed = subprocess.run(
        [*bash, workflow_step_script("Final status")],
        env={"PATH": "/usr/bin:/bin", "VERIFY_EXIT": verify_exit},
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == job_exit
    assert message in completed.stdout


def test_the_workflow_takes_the_three_inputs_and_holds_no_secret() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    dispatch = text.split("workflow_dispatch:", 1)[1].split("pull_request:", 1)[0]
    for name, required, default in (
        ("revision", "false", '""'),
        ("candidate", "true", '"1.60.1.70532"'),
        ("base", "true", '"1.60.1.70450"'),
    ):
        block = dispatch.split(f"      {name}:\n", 1)[1]
        assert f"required: {required}\n        default: {default}" in block, name
    # inputs reach the script through the environment only, never spliced into the shell
    for step in text.split("      - name: ")[1:]:
        run = step.split("run: |", 1)[1] if "run: |" in step else ""
        assert "${{" not in run, step.splitlines()[0]
    assert '--base "$BASE" --candidate "$CANDIDATE" --revision "$REVISION"' in text
    assert "permissions:\n  contents: read\n\n" in text
    assert "persist-credentials: false" in text and "fetch-depth: 0" in text
    assert "secrets." not in text
    for forbidden in ("--execute", "promote", "attach-signature", "--backend", "aws ", "AWS_"):
        assert forbidden not in text, forbidden
    steps = text.split("jobs:", 1)[1]
    for forbidden in ("git push", "gh pr", "gh api", "merge"):
        assert forbidden not in steps, forbidden


def test_the_workflow_uploads_the_report_without_private_keys() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    upload = text.split("      - name: Upload the verification report\n", 1)[1]
    upload = upload.split("      - name:", 1)[0]
    assert "if: always()" in upload and "if-no-files-found: error" in upload
    for excluded in ("stand/keys/*.pem", "stand/tls/server.key", "stand/stores", "stand/work"):
        assert "!${{ runner.temp }}/staging/" + excluded in upload, excluded
    assert "steps.verify.outputs.exit_code" in text.split("      - name: Final status\n", 1)[1]
