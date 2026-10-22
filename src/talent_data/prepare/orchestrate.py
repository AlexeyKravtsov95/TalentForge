"""Prepare one detected build for a later OTA release with the existing tools (M5.OTA.AUTO.1).

The preparation never publishes, signs or pushes anything, and never touches the checkout
it runs from. Everything happens in a **disposable clone** of the repository at the
``origin/main`` commit the detection recorded:

1. the existing pipeline commands, as subprocesses with one log each - ``fetch``,
   ``visuals fetch``, ``tooltips fetch``, ``localization fetch`` (the only network steps),
   then offline ``normalize --class all``, ``manifest --expect-complete``,
   ``diff --class all`` against the base, ``visuals``/``tooltips``/``localization``
   ``normalize`` and ``check``;
2. a guard that the pipeline wrote nothing outside the candidate build's own data, diff and
   coverage files (``PRODUCT_BUNDLE``, the app and every other build stay untouched), and -
   when ``main`` already carries the build (a forced re-run) - that the regenerated dataset
   is identical, after which main's committed bytes are kept;
3. a **local** candidate commit in the clone (never pushed: the job has no write
   permission) so the existing publisher can read the data the way it always does:
   ``build_release`` from a commit, then ``validate_release`` (gates G1-G8, production
   channel) and ``render_review_report`` against a mirror of the *verified* production
   catalog. The candidate commit is not on ``main``, so G1's STOP is expected here: the data
   reaches production only after a reviewed refresh PR, an approval record and the owner's
   offline signing (``docs/ota/publisher-runbook.md``);
4. regression: the mobile bundle ``--check`` scripts and the offline test suite, run in the
   clone with the candidate data present.

The outcome is one of three statuses, decided only from typed results (exit codes, gate
statuses, production status), never from message text:

* ``PREPARED`` - every step and check passed and no gate stopped except the expected G1;
  the report lists what a human must still do. It is **not** "ready to publish":
  ``publishable`` is always ``false`` here;
* ``BLOCKED``  - a pipeline step, a check, a test or a gate failed: diagnostics are kept;
* ``UNKNOWN``  - the answer could not be established: a source step could not reach
  wago.tools (exit 5), or the production catalog could not be verified (or changed since
  the detection).
"""

from __future__ import annotations

import io
import json
import os
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import Any

from talent_data.cli.main import EXIT_OK, EXIT_SOURCE_UNAVAILABLE
from talent_data.models.localization import SUPPORTED_LOCALES
from talent_data.models.manifest import DatasetManifest
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import Gate, GateResult, GateStatus
from talent_data.ota.models import OtaCatalog
from talent_data.ota.store import BlobStore, LocalDirectoryStore
from talent_data.prepare.detect import (
    TIMESTAMP_FORMAT,
    Action,
    DetectionDocument,
    artifact_name,
)
from talent_data.prepare.production import ProductionState, ProductionStatus

REPORT_SCHEMA_VERSION = 1
CANDIDATE_BRANCH = "ota-prepare/{build}"
CANDIDATE_AUTHOR = ("TalentForge OTA prepare", "ota-prepare@users.noreply.github.com")
DATA_ROOTS = ("data/normalized", "data/visuals", "data/tooltips", "data/localization")
COVERAGE_DIRS = ("artifacts/visuals", "artifacts/tooltips", "artifacts/localization")


class Readiness(StrEnum):
    PREPARED = "PREPARED"
    BLOCKED = "BLOCKED"
    UNKNOWN = "UNKNOWN"


EXIT_CODES = {Readiness.PREPARED: 0, Readiness.BLOCKED: 3, Readiness.UNKNOWN: 4}


class StepKind(StrEnum):
    SOURCE = "source"  # network: wago.tools
    PIPELINE = "pipeline"  # offline, from the fetched cache
    CHECK = "check"  # regression and consistency checks over the candidate


class StepOutcome(StrEnum):
    PASSED = "PASSED"
    FAILED = "FAILED"
    SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    SKIPPED = "SKIPPED"


class FindingKind(StrEnum):
    BLOCKER = "BLOCKER"  # a failed step, check, test or gate: not publishable as is
    UNKNOWN = "UNKNOWN"  # could not be established; re-run later
    REQUIRED = "REQUIRED"  # a human step before any publication


@dataclass(frozen=True)
class Step:
    id: str
    kind: StepKind
    argv: tuple[str, ...]


@dataclass(frozen=True)
class StepResult:
    step: Step
    outcome: StepOutcome
    exit_code: int | None = None
    seconds: float = 0.0
    log: str | None = None


@dataclass(frozen=True)
class Finding:
    kind: FindingKind
    source: str
    detail: str


@dataclass(frozen=True)
class ReleaseEvaluation:
    """What the existing publisher says about the candidate (``build_release`` + gates)."""

    release_id: str
    manifest_sha256: str
    manifest_path: Path
    gates: list[GateResult]
    review_report: str
    # whether the candidate commit is on origin/main (it never is: G1 STOP is then expected)
    on_main: bool


class PrepareError(RuntimeError):
    """The preparation could not run (workspace, git): diagnostics, never a verdict."""


#: ``argv, cwd, log file -> exit code``; tests replace it
Runner = Callable[[Sequence[str], Path, Path], int]


@dataclass
class Services:
    """The I/O of a preparation; ``talent_data.prepare.runner`` wires the real ones."""

    run: Runner
    read_production: Callable[[], ProductionState]
    mirror: Callable[[ProductionState, BlobStore], int]
    evaluate: Callable[..., ReleaseEvaluation]
    clock: Callable[[], datetime]


# --------------------------------------------------------------------------- steps


def _talent_data(*args: str) -> tuple[str, ...]:
    return (sys.executable, "-m", "talent_data", *args)


def pipeline_steps(
    game: str, build: str, base: str, locales: Sequence[str] = SUPPORTED_LOCALES
) -> list[Step]:
    """The existing pipeline commands for ``build`` (docs/data-pipeline.md §9), in order."""
    g, b = ("--game", game), ("--build", build)
    steps = [
        Step("fetch", StepKind.SOURCE, _talent_data("fetch", *g, *b)),
        Step("visuals-fetch", StepKind.SOURCE, _talent_data("visuals", "fetch", *g, *b)),
        Step("tooltips-fetch", StepKind.SOURCE, _talent_data("tooltips", "fetch", *g, *b)),
    ]
    steps += [
        Step(
            f"localization-fetch-{locale}",
            StepKind.SOURCE,
            _talent_data("localization", "fetch", *g, *b, "--locale", locale),
        )
        for locale in locales
    ]
    steps += [
        Step(
            "normalize",
            StepKind.PIPELINE,
            _talent_data("normalize", *g, *b, "--class", "all", "--offline"),
        ),
        Step(
            "manifest",
            StepKind.PIPELINE,
            _talent_data(
                "manifest",
                f"data/normalized/{game}/{build}/manifest.json",
                *g,
                *b,
                "--expect-complete",
            ),
        ),
        Step(
            "diff",
            StepKind.PIPELINE,
            _talent_data("diff", *g, "--class", "all", "--from", base, "--to", build, "--offline"),
        ),
        Step(
            "visuals-normalize",
            StepKind.PIPELINE,
            _talent_data("visuals", "normalize", *g, *b, "--offline"),
        ),
        Step("visuals-check", StepKind.PIPELINE, _talent_data("visuals", "check", *g, *b)),
        Step(
            "tooltips-normalize",
            StepKind.PIPELINE,
            _talent_data("tooltips", "normalize", *g, *b, "--offline"),
        ),
        Step("tooltips-check", StepKind.PIPELINE, _talent_data("tooltips", "check", *g, *b)),
    ]
    for locale in locales:
        loc = ("--locale", locale)
        steps += [
            Step(
                f"localization-normalize-{locale}",
                StepKind.PIPELINE,
                _talent_data(
                    "localization", "normalize", *g, *b, *loc, "--classes", "all", "--offline"
                ),
            ),
            Step(
                f"localization-check-{locale}",
                StepKind.PIPELINE,
                _talent_data("localization", "check", *g, *b, *loc),
            ),
        ]
    return steps


def check_steps() -> list[Step]:
    """Regression over the candidate: the app's bundles are unchanged and consistent, and
    the offline test suite passes with the candidate data present."""
    py = sys.executable
    syncs = [
        Step(
            f"sync-{kind}-check", StepKind.CHECK, (py, f"scripts/sync_mobile_{kind}.py", "--check")
        )
        for kind in ("datasets", "visuals", "tooltips", "localization")
    ]
    return [
        *syncs,
        Step("pytest", StepKind.CHECK, (py, "-m", "pytest", "-q", "-p", "no:cacheprovider")),
    ]


def run_command(argv: Sequence[str], cwd: Path, log: Path) -> int:
    """Run one command in the clone with the clone's own code (``src`` first on the path)
    and the clone as the data root; its combined output goes to ``log``."""
    env = dict(os.environ)
    env.pop("TALENT_DATA_ROOT", None)
    env["PYTHONPATH"] = os.pathsep.join(
        [str(cwd / "src"), *([env["PYTHONPATH"]] if env.get("PYTHONPATH") else [])]
    )
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as out:
        out.write(f"$ {shlex.join(argv)}\n".encode())
        out.flush()
        completed = subprocess.run(
            list(argv), cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT, check=False
        )
    return completed.returncode


def run_steps(
    steps: Sequence[Step], run: Runner, *, cwd: Path, logs: Path, stop_on_failure: bool
) -> list[StepResult]:
    results: list[StepResult] = []
    stopped = False
    for step in steps:
        if stopped:
            results.append(StepResult(step, StepOutcome.SKIPPED))
            continue
        log = logs / f"{step.id}.log"
        print(f"[ota-prepare] {step.id}: {shlex.join(step.argv[1:])}", file=sys.stderr)
        started = time.monotonic()
        code = run(step.argv, cwd, log)
        seconds = round(time.monotonic() - started, 1)
        if code == EXIT_OK:
            outcome = StepOutcome.PASSED
        elif code == EXIT_SOURCE_UNAVAILABLE and step.kind is StepKind.SOURCE:
            outcome = StepOutcome.SOURCE_UNAVAILABLE
        else:
            outcome = StepOutcome.FAILED
        print(
            f"[ota-prepare] {step.id}: {outcome.value} (exit {code}, {seconds}s)", file=sys.stderr
        )
        results.append(StepResult(step, outcome, code, seconds, f"logs/{log.name}"))
        stopped = stop_on_failure and outcome is not StepOutcome.PASSED
    return results


def step_findings(results: Sequence[StepResult]) -> list[Finding]:
    findings = []
    for r in results:
        if r.outcome is StepOutcome.FAILED:
            findings.append(
                Finding(FindingKind.BLOCKER, r.step.id, f"exit {r.exit_code}; see {r.log}")
            )
        elif r.outcome is StepOutcome.SOURCE_UNAVAILABLE:
            findings.append(
                Finding(
                    FindingKind.UNKNOWN,
                    r.step.id,
                    f"wago.tools could not be reached (exit {r.exit_code}, after the HTTP "
                    f"client's retries); see {r.log}",
                )
            )
    return findings


# --------------------------------------------------------------------------- git workspace


def _git(cwd: Path, *args: str, env: dict[str, str] | None = None) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True, env=env
        )
    except subprocess.CalledProcessError as exc:
        raise PrepareError(f"git {' '.join(args)} failed: {exc.stderr.strip()}") from exc
    return completed.stdout


def main_commit(repo: Path) -> str:
    """The commit ``origin/main`` names in ``repo`` (what the detection prepares from)."""
    return _git(repo, "rev-parse", "--verify", "origin/main^{commit}").strip()


def committed_builds(repo: Path, commit: str, game: str) -> set[str]:
    """Builds with a committed dataset manifest at ``commit``."""
    listed = _git(repo, "ls-tree", "-r", "--name-only", commit, "--", f"data/normalized/{game}/")
    return {
        PurePosixPath(path).parent.name
        for path in listed.splitlines()
        if PurePosixPath(path).name == "manifest.json"
        and PurePosixPath(path).parent.parent == PurePosixPath(f"data/normalized/{game}")
    }


def make_workspace(repo: Path, commit: str, dest: Path) -> None:
    """A disposable clone of ``repo`` at ``commit`` whose ``origin/main`` is ``repo``'s."""
    if dest.exists() and any(dest.iterdir()):
        raise PrepareError(f"the workspace {dest} is not empty")
    dest.parent.mkdir(parents=True, exist_ok=True)
    _git(dest.parent, "clone", "--quiet", "--no-checkout", "--", str(repo), str(dest))
    _git(
        dest,
        "fetch",
        "--quiet",
        "--no-tags",
        "--",
        str(repo),
        "+refs/remotes/origin/main:refs/remotes/origin/main",
    )
    _git(dest, "checkout", "--quiet", "--detach", commit)


def candidate_path_allowed(path: str, *, game: str, build: str, base: str) -> bool:
    """Whether the pipeline may have written ``path`` while preparing ``build``."""
    if any(path.startswith(f"{root}/{game}/{build}/") for root in DATA_ROOTS):
        return True
    posix = PurePosixPath(path)
    parent, name = str(posix.parent), posix.name
    if parent == "artifacts/diff":
        return name.startswith(f"{game}-") and name.endswith(f"-{base}-{build}.json")
    if parent in (*COVERAGE_DIRS, "docs/research"):
        return name.startswith(f"{game}-{build}-")
    return False


@dataclass(frozen=True)
class Candidate:
    commit: str | None  # None when stray paths stopped the commit
    changed: list[str]
    stray: list[str]


def commit_candidate(
    workspace: Path, *, game: str, build: str, base: str, when: datetime
) -> Candidate:
    """Stage what the pipeline wrote and commit it locally (``--allow-empty``: a new commit
    even when nothing changed), unless anything lies outside the candidate's own paths."""
    _git(workspace, "add", "--all")
    changed = [p for p in _git(workspace, "diff", "--cached", "--name-only", "-z").split("\0") if p]
    stray = [p for p in changed if not candidate_path_allowed(p, game=game, build=build, base=base)]
    if stray:
        return Candidate(None, changed, stray)
    stamp = when.strftime(TIMESTAMP_FORMAT)
    env = {
        **os.environ,
        "GIT_AUTHOR_DATE": stamp,
        "GIT_COMMITTER_DATE": stamp,
        "GIT_AUTHOR_NAME": CANDIDATE_AUTHOR[0],
        "GIT_AUTHOR_EMAIL": CANDIDATE_AUTHOR[1],
        "GIT_COMMITTER_NAME": CANDIDATE_AUTHOR[0],
        "GIT_COMMITTER_EMAIL": CANDIDATE_AUTHOR[1],
    }
    message = (
        f"data: prepare the {game} {build} dataset for OTA review (diff from {base})\n\n"
        "Local candidate commit of an M5.OTA.AUTO.1 preparation - never pushed; the data "
        "reaches main only through a reviewed refresh PR."
    )
    _git(
        workspace,
        "-c",
        "commit.gpgsign=false",
        "commit",
        "--quiet",
        "--allow-empty",
        "--no-verify",
        "-m",
        message,
        env=env,
    )
    commit = _git(workspace, "rev-parse", "HEAD").strip()
    _git(workspace, "branch", "--force", CANDIDATE_BRANCH.format(build=build), commit)
    return Candidate(commit, changed, [])


def keep_committed_dataset(workspace: Path, main: str, game: str, build: str) -> Finding | None:
    """When ``main`` already carries ``build``, the regenerated classes must be identical
    (talent data of one client build never changes, OD-8): then main's committed bytes are
    kept - ``generatedAt``/``provenance`` are volatile - so the candidate differs from main
    only where the data really does. A difference is a blocker (the regenerated files stay
    for inspection)."""
    from talent_data.ota.verify import parse_strict

    path = f"data/normalized/{game}/{build}/manifest.json"
    try:
        committed = _git(workspace, "show", f"{main}:{path}")
    except PrepareError:
        return None  # a new build: nothing to reproduce
    try:
        theirs = parse_strict(DatasetManifest, committed.encode("utf-8"))
        mine = parse_strict(DatasetManifest, (workspace / path).read_bytes())
    except (OSError, ValueError) as exc:
        return Finding(FindingKind.BLOCKER, "reproducibility", f"{path}: {exc}")

    def fingerprint(m: DatasetManifest) -> list[tuple[str, str, tuple[int, ...], str]]:
        return [(e.token, e.file, tuple(sorted(e.tree_ids)), e.content_hash) for e in m.classes]

    if fingerprint(mine) != fingerprint(theirs):
        return Finding(
            FindingKind.BLOCKER,
            "reproducibility",
            f"main already carries {build}, and the regenerated dataset differs from it "
            "(class, file, tree ids or contentHash): a corrected normalization of a "
            "committed build needs a human decision",
        )
    _git(workspace, "checkout", main, "--", f"data/normalized/{game}/{build}")
    return None


def _committed_tree(workspace: Path, commit: str, directory: str) -> dict[str, bytes]:
    """``{path relative to directory: bytes}`` of every file under ``directory`` at ``commit``."""
    listed = _git(workspace, "ls-tree", "-r", "--name-only", commit, "--", f"{directory}/")
    if not listed.strip():
        return {}  # not in the commit
    try:
        archive = subprocess.run(
            ["git", "-C", str(workspace), "archive", "--format=tar", commit, "--", directory],
            capture_output=True,
            check=True,
        ).stdout
    except subprocess.CalledProcessError as exc:
        raise PrepareError(f"git archive {commit} {directory} failed: {exc.stderr!r}") from exc
    files: dict[str, bytes] = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            extracted = tar.extractfile(member) if member.isfile() else None
            if extracted is not None:
                name = PurePosixPath(member.name).relative_to(directory).as_posix()
                files[name] = extracted.read()
    return files


def keep_committed_visuals(
    workspace: Path, main: str, game: str, build: str, log: Path
) -> Finding | None:
    """When ``main`` already carries the visuals of ``build``, the regenerated ones must hold
    the same visuals: the same files, sidecars, icon FileDataIDs, client files (sourceSha256)
    and pixels (``talent_data.visuals.reproduction``). The PNG bytes may differ - they depend
    on the zlib build of the converting machine - and then main's committed bytes, the
    canonical ones, are kept. Any other difference is a blocker (the regenerated files stay
    for inspection). ``log`` records what was compared."""
    from talent_data.visuals.reproduction import compare_visuals

    directory = f"data/visuals/{game}/{build}"
    committed = _committed_tree(workspace, main, directory)
    if not committed:
        return None  # visuals new to main: nothing to reproduce
    root = workspace / directory
    regenerated = {
        p.relative_to(root).as_posix(): p.read_bytes()
        for p in sorted(root.rglob("*"))
        if p.is_file()
    }
    comparison = compare_visuals(committed, regenerated)
    lines = [
        f"main {main} already carries {directory}: {len(committed)} committed, "
        f"{len(regenerated)} regenerated file(s), {comparison.identical_files} byte-identical",
        f"re-encoded (other PNG bytes, the same image): {len(comparison.reencoded)} icon(s)",
    ]
    if comparison.converters is not None:
        committed_by, regenerated_by = comparison.converters
        lines.append(f"converter: committed {committed_by!r}, regenerated {regenerated_by!r}")
    lines += [f"DIFFERS: {problem}" for problem in comparison.problems]
    lines.append(
        "result: the same visuals; main's committed bytes are kept"
        if comparison.equivalent
        else "result: the visuals differ; the regenerated files are kept for inspection"
    )
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("\n".join(lines) + "\n", encoding="utf-8")
    if not comparison.equivalent:
        more = len(comparison.problems) - 3
        return Finding(
            FindingKind.BLOCKER,
            "reproducibility",
            f"main already carries the visuals of {build}, and the regenerated ones differ: "
            + "; ".join(comparison.problems[:3])
            + (f" (+{more} more in logs/visuals-reproduction.log)" if more > 0 else "")
            + ". A changed visual of a committed build needs a human decision",
        )
    _git(workspace, "checkout", main, "--", directory)
    return None


# --------------------------------------------------------------------------- the publisher


def evaluate_release(
    workspace: Path,
    *,
    commit: str,
    game: str,
    build: str,
    revision: int,
    created_at: datetime,
    store: BlobStore,
    live: OtaCatalog | None,
    now: datetime,
    out: Path,
) -> ReleaseEvaluation:
    """``build-release`` + ``validate`` (production channel) + ``report``, as library calls."""
    from talent_data.cli.ota import DEFAULT_RELEASES_DOCS, STANDARD_VALIDATION_NOTE
    from talent_data.ota.gates import (
        load_approval_record,
        under_test_from_bytes,
        validate_release,
    )
    from talent_data.ota.models import Channel
    from talent_data.ota.release import build_release
    from talent_data.ota.report import render_review_report
    from talent_data.ota.verify import sha256_ref

    artifact = build_release(
        workspace, commit, game, build, revision, created_at, STANDARD_VALIDATION_NOTE
    )
    manifest_path = out / "ota" / artifact.release_id / "release.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_bytes(artifact.manifest_bytes)
    under_test = under_test_from_bytes(artifact.manifest_bytes, workspace, origin=manifest_path)
    approval = load_approval_record(artifact.release_id, workspace / DEFAULT_RELEASES_DOCS)
    gates = validate_release(
        under_test,
        repo_root=workspace,
        store=store,
        live=live,
        channel=Channel.PRODUCTION,
        now=now,
        approval=approval,
    )
    on_main = (
        subprocess.run(
            ["git", "-C", str(workspace), "merge-base", "--is-ancestor", commit, "origin/main"],
            capture_output=True,
            check=False,
        ).returncode
        == 0
    )
    return ReleaseEvaluation(
        release_id=artifact.release_id,
        manifest_sha256=sha256_ref(artifact.manifest_bytes),
        manifest_path=manifest_path,
        gates=gates,
        review_report=render_review_report(under_test, live, store),
        on_main=on_main,
    )


def gate_findings(evaluation: ReleaseEvaluation) -> list[Finding]:
    """STOP blocks - except G1 for a candidate that is not on main yet, which is the
    expected state of every prepared candidate. FLAG needs an approval record."""
    record = f"docs/ota/releases/{evaluation.release_id}.md"
    findings = []
    for result in evaluation.gates:
        if result.status is GateStatus.STOP:
            if result.gate is Gate.G1 and not evaluation.on_main:
                findings.append(
                    Finding(
                        FindingKind.REQUIRED,
                        "G1",
                        "the candidate data is not on main: merge it through a reviewed "
                        "refresh PR, then rebuild the release from the merge commit",
                    )
                )
            else:
                findings.append(Finding(FindingKind.BLOCKER, result.gate.value, result.detail))
        elif result.status is GateStatus.FLAG:
            findings.append(
                Finding(
                    FindingKind.REQUIRED,
                    result.gate.value,
                    f"an approval record ({record}, merged by PR) must accept it: {result.detail}",
                )
            )
    return findings


#: the publication steps no automation performs (docs/ota/publisher-runbook.md §1-2)
PUBLICATION_STEPS = (
    Finding(
        FindingKind.REQUIRED,
        "staging",
        "staging smoke of the release rebuilt from main on staging-channel app builds "
        "(test plan §4)",
    ),
    Finding(
        FindingKind.REQUIRED,
        "production",
        "the owner's production promotion: validate, report, promote, offline signing, "
        "attach-signature, verify-live (never from CI)",
    ),
)


def assess(findings: Sequence[Finding]) -> Readiness:
    kinds = {f.kind for f in findings}
    if FindingKind.BLOCKER in kinds:
        return Readiness.BLOCKED
    if FindingKind.UNKNOWN in kinds:
        return Readiness.UNKNOWN
    return Readiness.PREPARED


# --------------------------------------------------------------------------- the run


@dataclass
class PrepareReport:
    detection: DetectionDocument
    started_at: datetime
    finished_at: datetime | None = None
    status: Readiness = Readiness.UNKNOWN
    production: ProductionState | None = None
    candidate_commit: str | None = None
    changed_paths: list[str] = field(default_factory=list)
    steps: list[StepResult] = field(default_factory=list)
    evaluation: ReleaseEvaluation | None = None
    findings: list[Finding] = field(default_factory=list)
    diff_total: dict[str, Any] | None = None
    files: list[str] = field(default_factory=list)

    @property
    def artifact_name(self) -> str:
        key = self.detection.preparation_key or "unknown"
        return artifact_name(key, self.status.value)

    def to_dict(self) -> dict[str, Any]:
        d = self.detection
        ev = self.evaluation
        return {
            "schemaVersion": REPORT_SCHEMA_VERSION,
            "kind": "ota-prepare-report",
            "game": d.game,
            "status": self.status.value,
            # a preparation never makes anything publishable (see "findings")
            "publishable": False,
            "artifactName": self.artifact_name,
            "candidateBuild": d.candidate_build,
            "baseBuild": d.base_build,
            "baseKind": None if d.base_kind is None else d.base_kind.value,
            "baseReleaseId": d.base_release_id,
            "productBuild": d.product_build,
            "mainCommit": d.main_commit,
            "candidateCommit": self.candidate_commit,
            "startedAt": self.started_at.strftime(TIMESTAMP_FORMAT),
            "finishedAt": None
            if self.finished_at is None
            else self.finished_at.strftime(TIMESTAMP_FORMAT),
            "production": None if self.production is None else self.production.to_dict(),
            "release": None
            if ev is None
            else {
                "releaseId": ev.release_id,
                "manifestSha256": ev.manifest_sha256,
                "candidateOnMain": ev.on_main,
            },
            "gates": []
            if ev is None
            else [
                {"gate": g.gate.value, "status": g.status.value, "detail": g.detail}
                for g in ev.gates
            ],
            "diffTotal": self.diff_total,
            "steps": [
                {
                    "id": r.step.id,
                    "kind": r.step.kind.value,
                    "outcome": r.outcome.value,
                    "exitCode": r.exit_code,
                    "seconds": r.seconds,
                    "log": r.log,
                    "command": shlex.join(r.step.argv[1:]),
                }
                for r in self.steps
            ],
            "changedPaths": len(self.changed_paths),
            "findings": [
                {"kind": f.kind.value, "source": f.source, "detail": f.detail}
                for f in self.findings
            ],
            "files": self.files,
        }


def prepare(
    detection: DetectionDocument,
    *,
    repo: Path,
    out: Path,
    workspace: Path,
    services: Services,
    locales: Sequence[str] = SUPPORTED_LOCALES,
) -> PrepareReport:
    """Run the whole preparation; always returns a report (diagnostics are never lost)."""
    if detection.action is not Action.PREPARE:
        raise ValueError(f"the detection says {detection.action.value}, not PREPARE")
    build, base, revision = detection.candidate_build, detection.base_build, detection.revision
    if build is None or base is None or revision is None:
        raise ValueError("a PREPARE detection names its candidate, base and revision")
    game = detection.game
    out.mkdir(parents=True, exist_ok=True)
    (out / "detection.json").write_text(detection.to_json(), encoding="utf-8")
    report = PrepareReport(detection=detection, started_at=services.clock())
    findings = report.findings

    # production, re-read: the detection may be hours old
    production = services.read_production()
    report.production = production
    mirror = LocalDirectoryStore(workspace.parent / f"{workspace.name}-production-mirror")
    if production.status is ProductionStatus.UNKNOWN:
        findings.append(Finding(FindingKind.UNKNOWN, "production", production.reason))
    elif (
        production.status != detection.production.status
        or production.payload_sha256 != detection.production.payload_sha256
    ):
        findings.append(
            Finding(
                FindingKind.UNKNOWN,
                "production",
                "the production catalog changed since the detection "
                f"({detection.production.status.value} -> {production.status.value}); re-run",
            )
        )
    elif production.status is ProductionStatus.PUBLISHED:
        try:
            services.mirror(production, mirror)
        except OtaPublisherError as exc:
            findings.append(Finding(FindingKind.UNKNOWN, "production", str(exc)))

    try:
        make_workspace(repo, detection.main_commit, workspace)
    except PrepareError as exc:
        findings.append(Finding(FindingKind.UNKNOWN, "workspace", str(exc)))
        return _finish(report, out, workspace, services)
    if not (workspace / f"data/normalized/{game}/{base}/manifest.json").is_file():
        findings.append(
            Finding(FindingKind.BLOCKER, "base", f"the base build {base} has no committed dataset")
        )
        return _finish(report, out, workspace, services)

    report.steps += run_steps(
        pipeline_steps(game, build, base, locales),
        services.run,
        cwd=workspace,
        logs=out / "logs",
        stop_on_failure=True,
    )
    findings += step_findings(report.steps)
    if any(r.outcome is not StepOutcome.PASSED for r in report.steps):
        return _finish(report, out, workspace, services)

    try:
        reproduced = keep_committed_dataset(workspace, detection.main_commit, game, build)
        if reproduced is None:
            reproduced = keep_committed_visuals(
                workspace,
                detection.main_commit,
                game,
                build,
                out / "logs" / "visuals-reproduction.log",
            )
        candidate = commit_candidate(
            workspace, game=game, build=build, base=base, when=report.started_at
        )
    except PrepareError as exc:
        findings.append(Finding(FindingKind.UNKNOWN, "candidate", str(exc)))
        return _finish(report, out, workspace, services)
    if reproduced is not None:
        findings.append(reproduced)
    report.changed_paths = candidate.changed
    if candidate.commit is None:
        findings.append(
            Finding(
                FindingKind.BLOCKER,
                "scope",
                f"the pipeline wrote outside the candidate's own paths: {candidate.stray[:10]}",
            )
        )
        return _finish(report, out, workspace, services)
    report.candidate_commit = candidate.commit

    try:
        report.evaluation = services.evaluate(
            workspace,
            commit=candidate.commit,
            game=game,
            build=build,
            revision=revision,
            created_at=report.started_at,
            store=mirror,
            live=production.catalog,
            now=services.clock(),
            out=out,
        )
    except OtaPublisherError as exc:
        findings.append(Finding(FindingKind.BLOCKER, "release", str(exc)))
    else:
        findings += gate_findings(report.evaluation)

    checks = run_steps(
        check_steps(), services.run, cwd=workspace, logs=out / "logs", stop_on_failure=False
    )
    report.steps += checks
    findings += step_findings(checks)
    return _finish(report, out, workspace, services)


def _finish(report: PrepareReport, out: Path, workspace: Path, services: Services) -> PrepareReport:
    report.status = assess(report.findings)
    report.finished_at = services.clock()
    _collect(report, out, workspace)
    report.status = assess(report.findings)  # collecting may add an UNKNOWN (bundle)
    if report.status is Readiness.PREPARED:
        report.findings += PUBLICATION_STEPS
    (out / "report.json").write_text(
        json.dumps(report.to_dict(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (out / "summary.md").write_text(render_summary(report), encoding="utf-8")
    return report


def _collect(report: PrepareReport, out: Path, workspace: Path) -> None:
    """Copy the candidate's outputs next to the report; record every kept file."""
    d = report.detection
    game, build, base = d.game, d.candidate_build, d.base_build
    if workspace.is_dir() and build and base:
        for root in DATA_ROOTS:
            source = workspace / root / game / build
            if source.is_dir():
                shutil.copytree(source, out / root / game / build, dirs_exist_ok=True)
        for rel in ("artifacts/diff", *COVERAGE_DIRS, "docs/research"):
            directory = workspace / rel
            if not directory.is_dir():
                continue
            for path in sorted(directory.iterdir()):
                relative = f"{rel}/{path.name}"
                if path.is_file() and candidate_path_allowed(
                    relative, game=game, build=build, base=base
                ):
                    target = out / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, target)
        aggregate = out / "artifacts" / "diff" / f"{game}-all-{base}-{build}.json"
        if aggregate.is_file():
            from talent_data.diff.models import DatasetDiffReport

            total = DatasetDiffReport.model_validate_json(aggregate.read_bytes()).total
            report.diff_total = total.model_dump(mode="json", by_alias=True)
        if report.candidate_commit is not None:
            branch = CANDIDATE_BRANCH.format(build=build)
            bundle = out / "candidate.bundle"
            try:
                _git(workspace, "bundle", "create", str(bundle), branch, f"^{d.main_commit}")
            except PrepareError as exc:
                report.findings.append(Finding(FindingKind.UNKNOWN, "bundle", str(exc)))
    if report.evaluation is not None:
        ota = out / "ota"
        ota.mkdir(parents=True, exist_ok=True)
        (ota / "review-report.txt").write_text(report.evaluation.review_report, encoding="utf-8")
    report.files = sorted(
        p.relative_to(out).as_posix()
        for p in out.rglob("*")
        if p.is_file() and p.name not in ("report.json", "summary.md")
    )


def render_summary(report: PrepareReport) -> str:
    """The human view (job summary, ``summary.md``); the JSON report is the machine view."""
    d = report.detection
    base_kind = d.base_kind.value if d.base_kind else "-"
    base_release = f", {d.base_release_id}" if d.base_release_id else ""
    lines = [
        f"## OTA preparation: {d.candidate_build} - {report.status.value}",
        "",
        f"* Candidate: `{d.candidate_build}` (release revision r{d.revision}); "
        f"base `{d.base_build}` ({base_kind}{base_release})",
        f"* App `PRODUCT_BUNDLE`: `{d.product_build}`; main `{d.main_commit[:12]}`",
    ]
    if report.production is not None:
        lines.append(
            f"* Production catalog: {report.production.status.value} - {report.production.reason}"
        )
    if report.candidate_commit:
        lines.append(f"* Local candidate commit (not pushed): `{report.candidate_commit[:12]}`")
    if report.evaluation is not None:
        lines.append(
            f"* Release `{report.evaluation.release_id}`: manifest "
            f"`{report.evaluation.manifest_sha256}` (built from the local candidate commit; "
            "a published release is rebuilt from main)"
        )
    if report.diff_total is not None:
        total = report.diff_total
        lines.append(
            f"* Diff {d.base_build} -> {d.candidate_build}: {total.get('added')} added, "
            f"{total.get('removed')} removed, {total.get('changed')} changed nodes "
            f"(`artifacts/diff/{d.game}-all-{d.base_build}-{d.candidate_build}.json`)"
        )
    lines += [
        "* **Not publishable from this run**: nothing was published, signed or pushed.",
        "",
        "| step | outcome | exit | seconds |",
        "| --- | --- | --- | --- |",
    ]
    lines += [
        f"| {r.step.id} | {r.outcome.value} | {'' if r.exit_code is None else r.exit_code} "
        f"| {r.seconds} |"
        for r in report.steps
    ]
    if report.evaluation is not None:
        lines += ["", "| gate | status | detail |", "| --- | --- | --- |"]
        lines += [
            f"| {g.gate.value} | {g.status.value} | {g.detail.replace('|', '/')} |"
            for g in report.evaluation.gates
        ]
    if report.findings:
        lines += ["", "### Findings", ""]
        lines += [f"* **{f.kind.value}** ({f.source}): {f.detail}" for f in report.findings]
    lines += ["", f"Artifact: `{report.artifact_name}` ({len(report.files)} files)", ""]
    return "\n".join(lines)
