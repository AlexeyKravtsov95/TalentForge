"""Staging verification of an OTA candidate before production (M5.OTA.AUTO.4): the pure parts.

``scripts/ota_staging_verify.py`` builds the releases of a base and a candidate build from one
commit with the existing publisher, stages them on the isolated local stand
(``scripts/ota_staging_stand.py``) and runs the real Kotlin client as the app before the update
(``OtaCandidateStagingTest``). This module holds what that run decides and can be tested
offline: which commit is verified (:func:`resolve_subject`), whether two file sets are the same
(path, SHA-256, size), what a staged store holds, the client scenarios with the tests that prove
them, the overall verdict and its JUnit form.

The verdicts are the replay's (:class:`~talent_data.prepare.replay.Verdict`): ``PASS``, ``FAIL``,
``BLOCKED`` (not established here: an input that cannot be verified, a dependency that did not
run) and ``NOT RUN`` (deliberately not executed, or a device check). Only ``PASS`` of every
automatic check is a green run; a staging ``PASS`` approves nothing for production.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from talent_data.ota.release import ROOT_DIRS, commit_snapshot
from talent_data.prepare.replay import Scenario, Verdict, combine
from talent_data.versions import ClientVersion

GAME = "forever"
MAIN_REF = "origin/main"
EXIT_PASS, EXIT_FAIL, EXIT_USAGE, EXIT_INCOMPLETE = 0, 1, 2, 3
#: a revision the user may name: a commit id or a ref name, never an option
REVISION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._/-]{0,199}$")

#: ``path -> (sha256 ref, byteLength)``, the identity of a release's files
Files = Mapping[str, tuple[str, int]]


class InputError(Exception):
    """The requested revision or builds cannot be verified (the run is ``BLOCKED``)."""


@dataclass(frozen=True)
class Subject:
    """What one run verifies, fixed once: the commit and the two builds."""

    revision_input: str  # as requested; empty: the HEAD of ``origin/main``
    sha: str  # the full commit id every phase uses
    source: str  # how ``sha`` was chosen
    base: str
    candidate: str

    @property
    def base_release(self) -> str:
        return f"{self.base}-r1"

    @property
    def candidate_release(self) -> str:
        return f"{self.candidate}-r1"


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)


def resolve_subject(repo: Path, revision: str, base: str, candidate: str) -> Subject:
    """Fix the commit and validate the builds; :class:`InputError` names every problem.

    An empty ``revision`` is the HEAD of ``origin/main``. Any revision must be a commit on
    ``origin/main``: the publisher's gate G1 refuses any other source, and production releases
    are built from ``main`` only. Both builds must be valid client versions, the candidate newer
    than the base, and both must carry a committed dataset at that commit.
    """
    revision = revision.strip()
    if revision and not REVISION_RE.fullmatch(revision):
        raise InputError(f"revision {revision!r} is neither a commit id nor a ref name")
    named = revision or MAIN_REF
    resolved = _git(repo, "rev-parse", "--verify", "--quiet", f"{named}^{{commit}}")
    if resolved.returncode != 0:
        raise InputError(f"revision {named!r} does not name a commit in {repo}")
    sha = resolved.stdout.strip()
    problems: list[str] = []
    if _git(repo, "merge-base", "--is-ancestor", sha, MAIN_REF).returncode != 0:
        problems.append(f"commit {sha} is not on {MAIN_REF}")

    versions: dict[str, ClientVersion] = {}
    for name, build in (("base", base), ("candidate", candidate)):
        try:
            versions[name] = ClientVersion.parse(build)
        except ValueError as exc:
            problems.append(f"{name}: {exc}")
            continue
        if build != str(versions[name]):
            problems.append(f"{name}: {build!r} is not in canonical form")
            continue
        manifest = f"data/normalized/{GAME}/{build}/manifest.json"
        if _git(repo, "cat-file", "-e", f"{sha}:{manifest}").returncode != 0:
            problems.append(f"{name} {build}: no committed dataset at {sha[:12]} ({manifest})")
    if len(versions) == 2 and not versions["candidate"] > versions["base"]:
        problems.append(f"the candidate {candidate} is not newer than the base {base}")
    if problems:
        raise InputError("; ".join(problems))
    source = f"input {revision!r}" if revision else f"HEAD of {MAIN_REF} (no revision given)"
    return Subject(revision, sha, source, base, candidate)


# --------------------------------------------------------------------------- files


def release_files(manifest: bytes) -> dict[str, tuple[str, int]]:
    """``path -> (sha256 ref, byteLength)`` of a release manifest."""
    document = json.loads(manifest)
    return {f["path"]: (f["sha256"], f["byteLength"]) for f in document["files"]}


def bytes_files(files: Mapping[str, bytes]) -> dict[str, tuple[str, int]]:
    """``path -> (sha256 ref, byteLength)`` of files in memory."""
    return {
        path: ("sha256:" + hashlib.sha256(data).hexdigest(), len(data))
        for path, data in files.items()
    }


def compare_files(expected: Files, actual: Files) -> list[str]:
    """Every difference between two file sets (empty: the same paths, hashes and sizes)."""
    problems = [f"missing {path}" for path in sorted(expected.keys() - actual.keys())]
    problems += [f"unexpected {path}" for path in sorted(actual.keys() - expected.keys())]
    problems += [
        f"{path}: {expected[path]} != {actual[path]}"
        for path in sorted(expected.keys() & actual.keys())
        if tuple(expected[path]) != tuple(actual[path])
    ]
    return problems


def committed_files(repo: Path, sha: str, build: str) -> dict[str, tuple[str, int]]:
    """The committed files of ``build`` at ``sha``, in release layout (the publisher's reader)."""
    return bytes_files(commit_snapshot(repo, sha, GAME, build))


def export_build(repo: Path, sha: str, build: str, dest: Path) -> int:
    """Writes the committed data of ``build`` at ``sha`` under ``dest`` in repository layout
    (``data/normalized/<game>/<build>/...``): what the client test reads as the app bundle.
    Returns the number of files."""
    files = commit_snapshot(repo, sha, GAME, build)
    for path, data in files.items():
        head, rest = path.split("/", 1)
        target = dest / ROOT_DIRS[head] / PurePosixPath(rest)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return len(files)


def store_problems(store: Path, manifest: bytes) -> list[str]:
    """What a staged store lacks for a release: the manifest and every file as a
    content-addressed blob whose bytes hash to its name and have the pinned size."""
    problems: list[str] = []
    blobs = store / "v1" / "blobs" / "sha256"
    wanted = {"release.json": ("sha256:" + hashlib.sha256(manifest).hexdigest(), len(manifest))}
    wanted.update(release_files(manifest))
    for path, (ref, size) in sorted(wanted.items()):
        blob = blobs / ref.removeprefix("sha256:")
        if not blob.is_file():
            problems.append(f"{path}: blob {ref} not staged")
            continue
        data = blob.read_bytes()
        if "sha256:" + hashlib.sha256(data).hexdigest() != ref or len(data) != size:
            problems.append(f"{path}: blob {ref} holds other bytes ({len(data)} bytes)")
    return problems


def publisher_gates(log: str) -> dict[str, dict[str, str]]:
    """``releaseId -> {gate: status}`` from the publisher's ``gates for <id> (<channel>):`` blocks.

    A release validated more than once (``validate``, then ``stage``) keeps its last block."""
    gates: dict[str, dict[str, str]] = {}
    current: dict[str, str] | None = None
    for line in log.splitlines():
        header = re.match(r"^gates for (\S+) \((\w+)\):$", line)
        if header:
            current = gates[header.group(1)] = {}
            continue
        row = re.match(r"^  (G[1-8]) (PASS|FLAG|STOP)\b", line)
        if row and current is not None:
            current[row.group(1)] = row.group(2)
        elif not line.startswith("  "):
            current = None
    return gates


# --------------------------------------------------------------------------- the client

SUITE = "OtaCandidateStagingTest"
MOVE = f"{SUITE}.theCandidateReplacesTheBaseOverHttps"
SIGNATURE = f"{SUITE}.aCorruptCatalogSignatureChangesNothing"
MANIFEST = f"{SUITE}.aCorruptReleaseManifestChangesNothing"
BLOB = f"{SUITE}.aCorruptBlobChangesNothing"
INTERRUPTED = f"{SUITE}.anInterruptedDownloadResumesAfterARestart"
OFFLINE = f"{SUITE}.noNetworkWhenTheCandidateAppearsKeepsTheBase"

#: the client scenarios of a candidate, each with the ``OtaCandidateStagingTest`` tests proving it
CLIENT_SCENARIOS: tuple[Scenario, ...] = (
    Scenario("C1", "the app starts on the base; the candidate is not local", (MOVE,)),
    Scenario(
        "C2",
        "the candidate is found and downloaded over HTTPS, signature and SHA-256 verified",
        (MOVE,),
    ),
    Scenario("C3", "the candidate is activated; a new editor works on its data", (MOVE,)),
    Scenario("C4", "an editor opened before the activation keeps its base snapshot", (MOVE,)),
    Scenario("C5", "saved non-empty builds open on the base, unchanged", (MOVE,)),
    Scenario("C6", "a cold start keeps the candidate; the next check downloads nothing", (MOVE,)),
    Scenario("N1", "a corrupt catalog signature changes nothing", (SIGNATURE,)),
    Scenario("N2", "a corrupt release manifest or blob changes nothing", (MANIFEST, BLOB)),
    Scenario(
        "N3",
        "an interrupted download keeps the base and resumes after a restart",
        (INTERRUPTED,),
    ),
    Scenario("N4", "no network when the candidate appears keeps the base", (OFFLINE,)),
)


def client_scenario_verdicts(results: Mapping[str, Verdict], *, ran: bool) -> dict[str, Verdict]:
    """Each scenario's verdict from its tests. A test without a result is ``NOT RUN`` when the
    client was not started, and ``BLOCKED`` when it was started but did not report it."""
    missing = Verdict.BLOCKED if ran else Verdict.NOT_RUN
    return {s.id: combine(results.get(test, missing) for test in s.tests) for s in CLIENT_SCENARIOS}


def evidence_problems(
    evidence: Mapping[str, object], subject: Subject, published: Files
) -> list[str]:
    """What the client's evidence of the update says against the requested run: the requested
    pair and commit, and its installed copy against the published candidate."""
    problems: list[str] = []
    expected = {"base": subject.base, "candidate": subject.candidate, "revision": subject.sha}
    if evidence.get("requested") != expected:
        problems.append(f"requested {evidence.get('requested')} != {expected}")
    for key, value in (
        ("base", subject.base),
        ("candidate", subject.candidate),
        ("commit", subject.sha),
        ("candidateRelease", subject.candidate_release),
    ):
        if evidence.get(key) != value:
            problems.append(f"{key} {evidence.get(key)!r} != {value!r}")
    raw = evidence.get("installedFiles")
    if not isinstance(raw, dict) or not raw:
        return [*problems, "the evidence lists no installed files"]
    installed = {path: (entry[0], entry[1]) for path, entry in raw.items()}
    problems += [f"installed copy: {p}" for p in compare_files(published, installed)]
    return problems


# --------------------------------------------------------------------------- report


@dataclass
class Check:
    id: str
    phase: str
    title: str
    verdict: Verdict
    detail: str
    automatic: bool = True  # False: needs a device or a person, never counted as executed here


def overall(checks: Iterable[Check]) -> Verdict:
    """The run's verdict: every automatic check (a device check never counts)."""
    return combine(c.verdict for c in checks if c.automatic)


def exit_code(verdict: Verdict) -> int:
    """0 PASS, 1 a FAIL, 3 BLOCKED or NOT RUN (2 is the usage error of the entrypoint)."""
    return {Verdict.PASS: EXIT_PASS, Verdict.FAIL: EXIT_FAIL}.get(verdict, EXIT_INCOMPLETE)


def junit_xml(checks: Sequence[Check], *, suite: str = "ota-staging-verification") -> str:
    """The checks as one JUnit suite: FAIL a failure, BLOCKED an error, NOT RUN skipped."""
    tag = {Verdict.FAIL: "failure", Verdict.BLOCKED: "error", Verdict.NOT_RUN: "skipped"}
    root = ET.Element(
        "testsuite",
        name=suite,
        tests=str(len(checks)),
        failures=str(sum(c.verdict is Verdict.FAIL for c in checks)),
        errors=str(sum(c.verdict is Verdict.BLOCKED for c in checks)),
        skipped=str(sum(c.verdict is Verdict.NOT_RUN for c in checks)),
    )
    for c in checks:
        case = ET.SubElement(root, "testcase", classname=f"{suite}.{c.phase}", name=c.id)
        if c.verdict in tag:
            element = ET.SubElement(case, tag[c.verdict], message=f"{c.verdict.value}: {c.title}")
            element.text = c.detail
        ET.SubElement(case, "system-out").text = f"{c.title}: {c.detail}"
    ET.indent(root)
    return ET.tostring(root, encoding="unicode", xml_declaration=True) + "\n"


def private_keys(root: Path) -> list[str]:
    """Files under ``root`` that hold a PEM private key, as relative paths."""
    return [
        path.relative_to(root).as_posix()
        for path in sorted(root.rglob("*"))
        if path.is_file() and not path.is_symlink() and b"PRIVATE KEY" in path.read_bytes()
    ]
