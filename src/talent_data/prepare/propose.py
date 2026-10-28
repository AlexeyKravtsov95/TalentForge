"""Propose a PREPARED OTA candidate for review as a pull request (M5.OTA.AUTO.3).

The preparation (``orchestrate.prepare``) ends with a local candidate commit, kept in the
artifact as ``out/candidate.bundle``. This module turns exactly that commit into a review
branch and a pull request, so nobody carries the artifact over by hand. It never merges,
publishes, signs or approves anything: merging the PR only puts the data on ``main``; the
release is still rebuilt from the merge commit and published by the owner
(``docs/ota/publisher-runbook.md``).

What is proposed, and what is refused:

* only an artifact whose ``report.json`` says ``PREPARED`` with no ``BLOCKER`` or ``UNKNOWN``
  finding and every step ``PASSED``, and whose detection said ``PREPARE`` for the same
  inputs. Anything else is ``REFUSED`` and nothing is pushed;
* only the candidate commit of the bundle - never files from a workspace. It must be one
  commit by the preparation's author directly on top of the preparation's ``main`` commit
  (an ancestor of the current ``main``), and every file it changes must be one the
  preparation may write (``candidate_path_allowed``): added or modified, a regular file
  (mode 100644), as many as the report recorded;
* nothing when ``main`` already carries every file of the candidate (``ALREADY_ON_MAIN``).

Repeatable and safe to run concurrently (the workflow also queues runs):

* one branch per candidate build, ``ota-candidate/<game>-<build>``. A branch that already
  holds the same files (the same commit, or an earlier automation commit with identical
  blobs) is left as it is: no new commit. An automation branch with other files is moved
  to the new candidate with ``--force-with-lease``; a branch with any commit the automation
  did not make is a ``CONFLICT`` and is never overwritten;
* at most one open PR per branch: an open PR is reused (its description refreshed only when
  the branch holds this preparation's commit), a race on creation (HTTP 422) is resolved by
  listing again, and a PR closed with exactly this commit is not reopened (``DECLINED``);
* a failure after the push (GitHub API, permissions) is ``FAILED``: the next run finds the
  branch already up to date and only opens the PR.

Outcomes and exit codes: ``OPENED`` / ``EXISTS`` / ``ALREADY_ON_MAIN`` / ``DECLINED`` 0,
``REFUSED`` / ``CONFLICT`` 3, ``FAILED`` 1. Decided from typed results only (git exit codes,
HTTP status codes, parsed documents), never from message text.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any, Literal

import httpx
from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from talent_data.diff.models import DatasetDiffReport
from talent_data.localization.coverage import LocalizationCoverageReport
from talent_data.models.localization import SUPPORTED_LOCALES
from talent_data.ota.gates import Gate, GateStatus
from talent_data.prepare.detect import (
    Action,
    DetectionDocument,
    ProductionFact,
    artifact_name,
    read_detection,
)
from talent_data.prepare.orchestrate import (
    CANDIDATE_AUTHOR,
    CANDIDATE_BRANCH,
    FindingKind,
    Readiness,
    StepKind,
    StepOutcome,
    candidate_path_allowed,
)
from talent_data.tooltips.coverage import TooltipCoverageReport

PROPOSAL_SCHEMA_VERSION = 1
PROPOSAL_BRANCH = "ota-candidate/{game}-{build}"
REGULAR_FILE = "100644"
GITHUB_API_VERSION = "2022-11-28"


class Outcome(StrEnum):
    OPENED = "OPENED"  # this run opened the pull request
    EXISTS = "EXISTS"  # an open pull request already proposes this candidate
    ALREADY_ON_MAIN = "ALREADY_ON_MAIN"  # main carries every file: nothing to propose
    DECLINED = "DECLINED"  # a closed pull request proposed exactly this commit: not reopened
    REFUSED = "REFUSED"  # not a proposable PREPARED candidate: nothing pushed
    CONFLICT = "CONFLICT"  # the review branch holds commits the automation did not make
    FAILED = "FAILED"  # git or the GitHub API failed: the next run tries again


EXIT_CODES = {
    Outcome.OPENED: 0,
    Outcome.EXISTS: 0,
    Outcome.ALREADY_ON_MAIN: 0,
    Outcome.DECLINED: 0,
    Outcome.REFUSED: 3,
    Outcome.CONFLICT: 3,
    Outcome.FAILED: 1,
}


class Refusal(Exception):
    """The artifact is not a candidate this module may propose."""


class GitError(Exception):
    """A git command failed (``returncode`` is git's exit code)."""

    def __init__(self, args: Sequence[str], returncode: int, stderr: str) -> None:
        super().__init__(f"git {' '.join(args)} exited {returncode}: {stderr.strip()}")
        self.returncode = returncode


class GitHubError(Exception):
    """A GitHub API call failed; ``status`` is the HTTP status (``None``: no response)."""

    def __init__(self, operation: str, status: int | None, message: str) -> None:
        shown = "-" if status is None else str(status)
        super().__init__(f"{operation}: HTTP {shown}: {message}")
        self.operation = operation
        self.status = status


# --------------------------------------------------------------------------- the report


class _Strict(BaseModel):
    # The preparation's own report, read back as a closed contract.
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, strict=True, extra="forbid", frozen=True
    )


class ReportRelease(_Strict):
    release_id: str
    manifest_sha256: str
    candidate_on_main: bool


class ReportGate(_Strict):
    gate: Gate
    status: GateStatus
    detail: str


class ReportStep(_Strict):
    id: str
    kind: StepKind
    outcome: StepOutcome
    exit_code: int | None
    seconds: float
    log: str | None
    command: str


class ReportFinding(_Strict):
    kind: FindingKind
    source: str
    detail: str


class ReportDiffTotal(_Strict):
    added: int
    removed: int
    changed: int
    changes_by_kind: dict[str, int]


class PreparationReport(_Strict):
    """``out/report.json`` (``ota-prepare-report`` v1, ``PrepareReport.to_dict``)."""

    schema_version: Literal[1]
    kind: Literal["ota-prepare-report"]
    game: str
    status: Readiness
    publishable: Literal[False]
    artifact_name: str
    candidate_build: str | None
    base_build: str | None
    base_kind: str | None
    base_release_id: str | None
    product_build: str
    main_commit: str
    candidate_commit: str | None
    started_at: str
    finished_at: str | None
    production: ProductionFact | None
    release: ReportRelease | None
    gates: list[ReportGate]
    diff_total: ReportDiffTotal | None
    steps: list[ReportStep]
    changed_paths: int
    findings: list[ReportFinding]
    files: list[str]


# --------------------------------------------------------------------------- git


@dataclass
class Git:
    """git in ``repo``; ``auth`` (environment) is added only to the commands that reach the
    remote. Every command and its exit code is recorded in ``log`` - never the environment."""

    repo: Path
    auth: Mapping[str, str] = field(default_factory=dict)
    log: list[str] = field(default_factory=list)

    def __call__(self, *args: str, remote: bool = False) -> str:
        env = {**os.environ, **self.auth} if remote and self.auth else None
        completed = subprocess.run(
            ["git", "-C", str(self.repo), *args],
            capture_output=True,
            text=True,
            check=False,
            env=env,
        )
        self.log.append(f"$ git {' '.join(args)}  -> exit {completed.returncode}")
        self.log.extend(f"  {line}" for line in completed.stderr.strip().splitlines())
        if completed.returncode != 0:
            raise GitError(args, completed.returncode, completed.stderr)
        return completed.stdout

    def succeeds(self, *args: str) -> bool:
        try:
            self(*args)
        except GitError:
            return False
        return True


def git_auth(token: str | None, server_url: str) -> dict[str, str]:
    """The environment that authenticates git over HTTPS with ``token``, the way
    ``actions/checkout`` does, for the commands that reach the remote only."""
    if not token:
        return {}
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return {
        "GIT_CONFIG_COUNT": "1",
        "GIT_CONFIG_KEY_0": f"http.{server_url.rstrip('/')}/.extraheader",
        "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}",
        "GIT_TERMINAL_PROMPT": "0",
    }


@dataclass(frozen=True, order=True)
class FileChange:
    path: str
    status: str  # git's raw status letter: A, M, D, T, ...
    mode: str
    blob: str


def file_changes(git: Git, parent: str, commit: str) -> list[FileChange]:
    """Every file ``commit`` changes relative to ``parent`` (no rename detection)."""
    fields = git("diff-tree", "-r", "-z", "--no-renames", "--raw", parent, commit).split("\0")
    changes = []
    for meta, path in zip(fields[0::2], fields[1::2], strict=False):
        if not meta:
            break
        _old_mode, new_mode, _old_blob, new_blob, status = meta.lstrip(":").split(" ")
        changes.append(FileChange(path, status, new_mode, new_blob))
    return sorted(changes)


def tree_entries(git: Git, commit: str, paths: Sequence[str]) -> dict[str, tuple[str, str]]:
    """``{path: (mode, blob)}`` of ``paths`` at ``commit`` (absent paths are left out)."""
    entries: dict[str, tuple[str, str]] = {}
    for start in range(0, len(paths), 200):
        chunk = paths[start : start + 200]
        out = git("--literal-pathspecs", "ls-tree", "-r", "-z", "--full-tree", commit, "--", *chunk)
        for record in out.split("\0"):
            if record:
                meta, path = record.split("\t", 1)
                mode, _kind, blob = meta.split(" ")
                entries[path] = (mode, blob)
    return entries


def _parents(git: Git, commit: str) -> list[str]:
    return git("rev-list", "--parents", "-n", "1", commit).split()[1:]


def _author_email(git: Git, commit: str) -> str:
    return git("log", "-1", "--format=%ae", commit).strip()


# --------------------------------------------------------------------------- GitHub


class _Lenient(BaseModel):
    # GitHub's responses carry many more fields; only the ones read here are typed.
    model_config = ConfigDict(extra="ignore", frozen=True)


class PullHead(_Lenient):
    ref: str
    sha: str


class PullRequest(_Lenient):
    number: int
    html_url: str
    state: Literal["open", "closed"]
    merged_at: str | None = None
    body: str | None = None
    head: PullHead


class GitHub:
    """The three pull request calls the proposal makes: list, create, update."""

    def __init__(self, client: httpx.Client, repository: str) -> None:
        self._client = client
        self.repository = repository
        self.owner = repository.split("/", 1)[0]

    def _call(self, operation: str, method: str, url: str, **kwargs: Any) -> Any:
        try:
            response = self._client.request(method, url, **kwargs)
        except httpx.HTTPError as exc:
            raise GitHubError(operation, None, f"{type(exc).__name__}: {exc}") from exc
        if response.is_success:
            return response.json()
        message = response.text[:500]
        try:
            payload = response.json()
        except ValueError:
            payload = None
        if isinstance(payload, dict) and isinstance(payload.get("message"), str):
            message = payload["message"]
            if payload.get("errors"):
                message += f" {json.dumps(payload['errors'], ensure_ascii=False)[:500]}"
        raise GitHubError(operation, response.status_code, message)

    def pulls(self, branch: str) -> list[PullRequest]:
        data = self._call(
            "list pull requests",
            "GET",
            f"/repos/{self.repository}/pulls",
            params={"head": f"{self.owner}:{branch}", "state": "all", "per_page": 100},
        )
        try:
            pulls = [PullRequest.model_validate(item) for item in data]
        except (TypeError, ValidationError) as exc:
            raise GitHubError("list pull requests", None, f"unexpected response: {exc}") from exc
        return [p for p in pulls if p.head.ref == branch]

    def create(self, *, title: str, branch: str, base: str, body: str) -> PullRequest:
        data = self._call(
            "create pull request",
            "POST",
            f"/repos/{self.repository}/pulls",
            json={"title": title, "head": branch, "base": base, "body": body, "draft": False},
        )
        try:
            return PullRequest.model_validate(data)
        except ValidationError as exc:
            raise GitHubError("create pull request", None, f"unexpected response: {exc}") from exc

    def update_body(self, number: int, body: str) -> None:
        self._call(
            "update pull request",
            "PATCH",
            f"/repos/{self.repository}/pulls/{number}",
            json={"body": body},
        )


def github_client(token: str, api_url: str) -> httpx.Client:
    return httpx.Client(
        base_url=api_url.rstrip("/"),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": GITHUB_API_VERSION,
            "User-Agent": "TalentForge-ota-prepare",
        },
        timeout=30.0,
        follow_redirects=False,
    )


# --------------------------------------------------------------------------- the proposal


@dataclass
class Proposal:
    outcome: Outcome
    reason: str
    branch: str | None = None
    candidate_build: str | None = None
    base_build: str | None = None
    main_commit: str | None = None
    candidate_commit: str | None = None
    branch_commit: str | None = None
    pushed: bool = False
    pull_number: int | None = None
    pull_url: str | None = None
    body_updated: bool = False
    files: int = 0
    error: str | None = None
    http_status: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": PROPOSAL_SCHEMA_VERSION,
            "kind": "ota-prepare-proposal",
            "outcome": self.outcome.value,
            "reason": self.reason,
            "branch": self.branch,
            "candidateBuild": self.candidate_build,
            "baseBuild": self.base_build,
            "mainCommit": self.main_commit,
            "candidateCommit": self.candidate_commit,
            "branchCommit": self.branch_commit,
            "pushed": self.pushed,
            "pullNumber": self.pull_number,
            "pullUrl": self.pull_url,
            "bodyUpdated": self.body_updated,
            "files": self.files,
            "error": self.error,
            "httpStatus": self.http_status,
        }


@dataclass(frozen=True)
class Candidate:
    report: PreparationReport
    detection: DetectionDocument
    game: str
    build: str
    base: str
    commit: str
    changes: list[FileChange]


def load_candidate(artifact: Path, git: Git, *, base_ref: str, expected: str | None) -> Candidate:
    """Read and check the artifact (the preparation's ``prep/`` directory) and fetch its
    candidate commit into ``git.repo``. Raises ``Refusal`` for anything that is not a
    proposable PREPARED candidate."""
    out = artifact / "out"
    try:
        report = PreparationReport.model_validate_json((out / "report.json").read_bytes())
        detection = read_detection(out / "detection.json")
    except (OSError, ValueError) as exc:
        raise Refusal(f"the artifact holds no valid preparation report: {exc}") from exc
    if report.status is not Readiness.PREPARED:
        raise Refusal(f"the preparation ended {report.status.value}; only PREPARED is proposed")
    if expected is not None and report.artifact_name != expected:
        raise Refusal(f"the report names artifact {report.artifact_name}, not {expected}")
    stops = [f for f in report.findings if f.kind is not FindingKind.REQUIRED]
    if stops:
        raise Refusal(f"the report is PREPARED but lists {stops[0].kind.value} ({stops[0].source})")
    if not report.steps or any(s.outcome is not StepOutcome.PASSED for s in report.steps):
        raise Refusal("the report is PREPARED but not every step passed")
    build, base, commit = report.candidate_build, report.base_build, report.candidate_commit
    if build is None or base is None or commit is None:
        raise Refusal("the report names no candidate build, base or candidate commit")
    if (
        detection.action is not Action.PREPARE
        or detection.game != report.game
        or detection.candidate_build != build
        or detection.base_build != base
        or detection.main_commit != report.main_commit
        or detection.preparation_key is None
        or report.artifact_name != artifact_name(detection.preparation_key, "PREPARED")
    ):
        raise Refusal("the detection and the report describe different preparations")

    bundle = out / "candidate.bundle"
    ref = f"refs/heads/{CANDIDATE_BRANCH.format(build=build)}"
    try:
        git("bundle", "verify", "--quiet", str(bundle))
        git("fetch", "--quiet", "--no-tags", str(bundle), ref)
        fetched = git("rev-parse", "--verify", "FETCH_HEAD^{commit}").strip()
    except GitError as exc:
        raise Refusal(f"the candidate bundle cannot be read: {exc}") from exc
    if fetched != commit:
        raise Refusal(f"the bundle holds {fetched[:12]}, the report names {commit[:12]}")
    if _parents(git, commit) != [report.main_commit]:
        raise Refusal("the candidate is not one commit on top of the preparation's main commit")
    if _author_email(git, commit) != CANDIDATE_AUTHOR[1]:
        raise Refusal("the candidate commit was not made by the preparation")
    if not git.succeeds("merge-base", "--is-ancestor", report.main_commit, base_ref):
        raise Refusal(f"the preparation's main commit {report.main_commit[:12]} is not on main")

    changes = file_changes(git, report.main_commit, commit)
    for change in changes:
        if not candidate_path_allowed(change.path, game=report.game, build=build, base=base):
            raise Refusal(f"the candidate changes {change.path}, outside its own paths")
        if change.status not in ("A", "M") or change.mode != REGULAR_FILE:
            raise Refusal(
                f"the candidate changes {change.path} with status {change.status} and mode "
                f"{change.mode}; only added or modified regular files are proposed"
            )
    if len(changes) != report.changed_paths:
        raise Refusal(
            f"the candidate commit changes {len(changes)} file(s), the report recorded "
            f"{report.changed_paths}"
        )
    return Candidate(report, detection, report.game, build, base, commit, changes)


def propose(
    artifact: Path,
    *,
    git: Git,
    github: GitHub,
    remote: str = "origin",
    base_branch: str = "main",
    expected_artifact: str | None = None,
    preparation_url: str | None = None,
) -> tuple[Proposal, str | None]:
    """Propose the artifact's candidate; returns the proposal and the PR description, when
    one was rendered. Expected failures never raise: the outcome says what happened."""
    base_ref = f"refs/remotes/{remote}/{base_branch}"
    try:
        git(
            "fetch",
            "--quiet",
            "--no-tags",
            remote,
            f"+refs/heads/{base_branch}:{base_ref}",
            remote=True,
        )
    except GitError as exc:
        return Proposal(Outcome.FAILED, "main could not be fetched", error=str(exc)), None
    try:
        candidate = load_candidate(artifact, git, base_ref=base_ref, expected=expected_artifact)
    except Refusal as exc:
        return Proposal(Outcome.REFUSED, str(exc)), None

    report = candidate.report
    branch = PROPOSAL_BRANCH.format(game=candidate.game, build=candidate.build)
    proposal = Proposal(
        Outcome.FAILED,
        "",
        branch=branch,
        candidate_build=candidate.build,
        base_build=candidate.base,
        main_commit=report.main_commit,
        candidate_commit=candidate.commit,
        files=len(candidate.changes),
    )
    on_main = tree_entries(git, base_ref, [c.path for c in candidate.changes])
    if all(on_main.get(c.path) == (c.mode, c.blob) for c in candidate.changes):
        proposal.outcome = Outcome.ALREADY_ON_MAIN
        proposal.reason = (
            "main already carries every file of the candidate"
            if candidate.changes
            else "the candidate changes nothing on main"
        )
        return proposal, None

    body = render_body(candidate, artifact, preparation_url, on_main)
    try:
        _sync_branch(git, candidate, proposal, remote=remote, base_ref=base_ref)
    except GitError as exc:
        proposal.reason, proposal.error = f"the branch {branch} could not be updated", str(exc)
        return proposal, body
    if proposal.outcome is Outcome.CONFLICT:
        return proposal, body
    try:
        _sync_pull(github, candidate, proposal, base_branch=base_branch, body=body)
    except GitHubError as exc:
        proposal.outcome, proposal.error = Outcome.FAILED, str(exc)
        proposal.http_status = exc.status
        proposal.reason = (
            f"the branch {branch} holds the candidate, but the pull request step failed "
            f"({exc.operation}); the next run retries it"
        )
    return proposal, body


def _sync_branch(
    git: Git, candidate: Candidate, proposal: Proposal, *, remote: str, base_ref: str
) -> None:
    """Make the review branch hold the candidate's files, with as few writes as possible."""
    branch = proposal.branch or ""
    listed = git("ls-remote", "--heads", remote, f"refs/heads/{branch}", remote=True).split()
    tip = listed[0] if listed else None
    if tip is None:
        git("push", "--quiet", remote, f"{candidate.commit}:refs/heads/{branch}", remote=True)
        proposal.pushed, proposal.branch_commit = True, candidate.commit
        return
    if tip == candidate.commit:
        proposal.branch_commit = tip
        return
    git("fetch", "--quiet", "--no-tags", remote, f"refs/heads/{branch}", remote=True)
    parents = _parents(git, tip)
    owned = (
        len(parents) == 1
        and _author_email(git, tip) == CANDIDATE_AUTHOR[1]
        and git.succeeds("merge-base", "--is-ancestor", parents[0], base_ref)
    )
    if not owned:
        proposal.outcome = Outcome.CONFLICT
        proposal.branch_commit = tip
        proposal.reason = (
            f"the branch {branch} holds commits the automation did not make ({tip[:12]}); it "
            "is never overwritten: review, merge or delete it, then run the workflow again"
        )
        return
    if file_changes(git, parents[0], tip) == candidate.changes:
        proposal.branch_commit = tip  # an earlier preparation with identical files
        return
    git(
        "push",
        "--quiet",
        f"--force-with-lease=refs/heads/{branch}:{tip}",
        remote,
        f"{candidate.commit}:refs/heads/{branch}",
        remote=True,
    )
    proposal.pushed, proposal.branch_commit = True, candidate.commit


def _sync_pull(
    github: GitHub, candidate: Candidate, proposal: Proposal, *, base_branch: str, body: str
) -> None:
    branch = proposal.branch or ""
    pulls = github.pulls(branch)
    open_pull = next((p for p in pulls if p.state == "open"), None)
    if open_pull is None:
        closed = next((p for p in pulls if p.head.sha == proposal.branch_commit), None)
        if closed is not None:
            proposal.outcome = Outcome.DECLINED
            proposal.pull_number, proposal.pull_url = closed.number, closed.html_url
            proposal.reason = (
                f"pull request #{closed.number} proposed exactly this commit and was "
                f"{'merged' if closed.merged_at else 'closed'}; it is not reopened (delete "
                f"the branch {branch} and run the workflow with force to propose again)"
            )
            return
        title = (
            f"data: OTA candidate {candidate.game} {candidate.build} (diff from {candidate.base})"
        )
        try:
            created = github.create(title=title, branch=branch, base=base_branch, body=body)
        except GitHubError as exc:
            if exc.status != 422:
                raise
            # another run opened it first: use that one
            open_pull = next((p for p in github.pulls(branch) if p.state == "open"), None)
            if open_pull is None:
                raise
        else:
            proposal.outcome = Outcome.OPENED
            proposal.pull_number, proposal.pull_url = created.number, created.html_url
            proposal.reason = f"opened pull request #{created.number}"
            return
    proposal.outcome = Outcome.EXISTS
    proposal.pull_number, proposal.pull_url = open_pull.number, open_pull.html_url
    proposal.reason = f"pull request #{open_pull.number} already proposes the candidate"
    # the description follows the preparation whose commit the branch holds
    if open_pull.head.sha == candidate.commit and (open_pull.body or "") != body:
        github.update_body(open_pull.number, body)
        proposal.body_updated = True
        proposal.reason += "; its description was refreshed"


# --------------------------------------------------------------------------- rendering


def _load(model: type[BaseModel], path: Path) -> Any:
    """A diff or coverage document of the artifact (``None`` when it is not there)."""
    if not path.is_file():
        return None
    return model.model_validate_json(path.read_bytes())


def render_body(
    candidate: Candidate,
    artifact: Path,
    preparation_url: str | None,
    on_main: Mapping[str, tuple[str, str]],
) -> str:
    """The pull request description: what was prepared, from what, how it was checked and
    what is still missing before anything may be published."""
    r, d = candidate.report, candidate.detection
    game, build, base = candidate.game, candidate.build, candidate.base
    out = artifact / "out"
    run = f"[preparation run]({preparation_url})" if preparation_url else "the preparation run"
    base_release = f", release `{r.base_release_id}`" if r.base_release_id else ""
    lines = [
        f"Automatic OTA candidate for **{game} `{build}`**, prepared by {run} "
        f"(artifact `{r.artifact_name}`).",
        "",
        "> Merging this PR only puts the data on `main`. It publishes, signs and approves "
        "nothing: the release is rebuilt from the merge commit and published by the owner "
        "(`docs/ota/publisher-runbook.md`).",
        "",
        "| | |",
        "| --- | --- |",
        f"| Candidate build | `{build}` (release revision r{d.revision}) |",
        f"| Base of the diff | `{base}` ({r.base_kind or '-'}{base_release}) |",
        f"| App `PRODUCT_BUNDLE` | `{r.product_build}` |",
        f"| Source `main` commit | `{r.main_commit}` |",
        f"| Candidate commit | `{candidate.commit}` |",
    ]
    if r.production is not None:
        p = r.production
        sequence = "no catalog" if p.sequence is None else f"sequence {p.sequence}"
        active = f", active `{p.active_release_id}`" if p.active_release_id else ""
        lines.append(f"| Production catalog | {p.status.value} ({sequence}{active}) |")
    if r.release is not None:
        lines.append(
            f"| Release from the candidate commit | `{r.release.release_id}`, manifest "
            f"`{r.release.manifest_sha256}` (not the manifest to approve: the release is "
            "rebuilt from the merge commit) |"
        )
    lines.append(f"| Prepared | {r.started_at} - {r.finished_at or '-'} |")

    lines += ["", "### Talent changes", ""]
    diff = _load(DatasetDiffReport, out / "artifacts" / "diff" / f"{game}-all-{base}-{build}.json")
    if diff is None:
        lines.append("The aggregate diff is not in the artifact.")
    else:
        total = diff.total
        lines.append(
            f"`{base}` → `{build}`: **{total.added} added, {total.removed} removed, "
            f"{total.changed} changed** talent nodes."
        )
        changed = [
            c for c in diff.classes if c.summary.added or c.summary.removed or c.summary.changed
        ]
        if changed:
            lines += ["", "| class | added | removed | changed | changes by kind |"]
            lines.append("| --- | --- | --- | --- | --- |")
            for c in changed:
                kinds = ", ".join(f"{k} {n}" for k, n in sorted(c.summary.changes_by_kind.items()))
                lines.append(
                    f"| {c.class_.token} | {c.summary.added} | {c.summary.removed} | "
                    f"{c.summary.changed} | {kinds or '-'} |"
                )
        else:
            lines.append("No class has changes: the talent trees equal the base's.")

    lines += ["", "### Translation and tooltip coverage", ""]
    for locale in SUPPORTED_LOCALES:
        name = f"{game}-{build}-{locale}-coverage.json"
        cov = _load(LocalizationCoverageReport, out / "artifacts" / "localization" / name)
        if cov is None:
            lines.append(f"* {locale}: the coverage report is not in the artifact")
            continue
        t = cov.totals
        lines.append(
            f"* {locale}: names {t.names.translated} / {t.entries} translated "
            f"({t.names.untranslated} untranslated, {t.names.missing} missing); descriptions "
            f"{t.descriptions.rendered} / {t.entries} entries rendered "
            f"({t.rendered_ranks} / {t.ranks} ranks), the rest fall back to English"
        )
    name = f"{game}-{build}-tooltip-coverage.json"
    tooltips = _load(TooltipCoverageReport, out / "artifacts" / "tooltips" / name)
    if tooltips is None:
        lines.append("* tooltips: the coverage report is not in the artifact")
    else:
        t = tooltips.totals
        lines.append(
            f"* tooltips: {t.resolved} / {t.ranks} ranks resolved ({t.resolved_percent} %), "
            f"{t.partial} partial, {t.unresolved} unresolved"
        )

    passed = sum(s.outcome is StepOutcome.PASSED for s in r.steps)
    lines += [
        "",
        "### Checks of the preparation",
        "",
        f"{passed} / {len(r.steps)} steps passed (the pipeline, the mobile bundle `--check` "
        "scripts and the offline `pytest` with the candidate present).",
        "",
        "<details><summary>Steps</summary>",
        "",
        "| step | kind | outcome | exit | seconds |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines += [
        f"| {s.id} | {s.kind.value} | {s.outcome.value} | "
        f"{'' if s.exit_code is None else s.exit_code} | {s.seconds} |"
        for s in r.steps
    ]
    lines += ["", "</details>", ""]
    if r.gates:
        lines += ["| gate | status | detail |", "| --- | --- | --- |"]
        lines += [
            f"| {g.gate.value} | {g.status.value} | {g.detail.replace('|', '/')} |" for g in r.gates
        ]
        lines.append("")

    lines += ["### Still required before any publication (not done by this PR)", ""]
    lines += [
        f"- [ ] **{f.source}**: {f.detail}" for f in r.findings if f.kind is FindingKind.REQUIRED
    ]

    roots = Counter(
        "/".join(c.path.split("/")[:2]) if c.path.startswith("data/") else c.path.rsplit("/", 1)[0]
        for c in candidate.changes
    )
    already = sum(on_main.get(c.path) == (c.mode, c.blob) for c in candidate.changes)
    lines += [
        "",
        "### Files",
        "",
        f"{len(candidate.changes)} file(s), all inside the candidate's own paths (checked "
        "before the push); only the candidate commit of the artifact's bundle is pushed."
        + (f" {already} of them already equal `main`'s." if already else ""),
        "",
    ]
    lines += [f"* `{root}`: {count}" for root, count in sorted(roots.items())]
    lines.append("")
    return "\n".join(lines)


def render_summary(proposal: Proposal) -> str:
    """The Actions job summary of the proposal."""
    lines = [f"## OTA candidate pull request: {proposal.outcome.value}", "", proposal.reason, ""]
    if proposal.pull_url:
        lines.append(f"* Pull request: {proposal.pull_url}")
    if proposal.branch:
        pushed = "pushed by this run" if proposal.pushed else "not pushed by this run"
        commit = f" at `{proposal.branch_commit[:12]}`" if proposal.branch_commit else ""
        lines.append(f"* Branch: `{proposal.branch}`{commit} ({pushed})")
    if proposal.candidate_build:
        lines.append(
            f"* Candidate `{proposal.candidate_build}` from `{proposal.base_build}`, main "
            f"`{(proposal.main_commit or '')[:12]}`, {proposal.files} file(s)"
        )
    if proposal.error:
        status = "" if proposal.http_status is None else f" (HTTP {proposal.http_status})"
        lines.append(f"* **Error**{status}: {proposal.error}")
        if proposal.http_status == 403:
            lines.append(
                "* HTTP 403: the token may not create pull requests - check Settings → Actions "
                '→ General → Workflow permissions → "Allow GitHub Actions to create and '
                'approve pull requests" (docs/ota/auto-prepare.md)'
            )
    lines.append("")
    return "\n".join(lines)


def write_outputs(out: Path, proposal: Proposal, body: str | None, log: Sequence[str]) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "proposal.json").write_text(
        json.dumps(proposal.to_dict(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (out / "summary.md").write_text(render_summary(proposal), encoding="utf-8")
    (out / "git.log").write_text("\n".join(log) + "\n", encoding="utf-8")
    if body is not None:
        (out / "pr-body.md").write_text(body, encoding="utf-8")
