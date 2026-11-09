"""Select short metadata CI only above an independently proven full CI ancestor."""

from __future__ import annotations

import argparse
import os
import re
from pathlib import Path
from typing import Literal, Self

import httpx
from pydantic import Field, model_validator

from talent_data.ota.automation import provenance
from talent_data.ota.automation.prepare import (
    CI_WORKFLOW,
    GitHub,
    PreparationBlocked,
    git,
    positive,
    require,
    same,
    verify_ci,
)
from talent_data.ota.automation.probe import _package_metadata
from talent_data.ota.automation.provenance import REPOSITORY, TRUSTED_REPO, RequestRejected
from talent_data.ota.automation.requests import _Model, parse_request
from talent_data.ota.models import COMMIT_PATTERN, RELEASE_ID_PATTERN
from talent_data.ota.release_package import (
    APPROVALS_DIR,
    approval_record_at,
    approval_record_problems,
)
from talent_data.ota.verify import OtaError, parse_strict


class CiDecision(_Model):
    mode: Literal["full", "metadata"]
    head_sha: str = Field(pattern=COMMIT_PATTERN)
    baseline_sha: str | None = Field(pattern=COMMIT_PATTERN)
    baseline_run_id: int | None = Field(gt=0)
    baseline_attempt: int | None = Field(gt=0)
    changed_paths: tuple[str, ...]

    @model_validator(mode="after")
    def baseline_binding(self) -> Self:
        pins = (self.baseline_sha, self.baseline_run_id, self.baseline_attempt)
        if self.mode == "metadata" and (any(p is None for p in pins) or not self.changed_paths):
            raise ValueError("metadata mode requires full baseline and paths")
        if self.mode == "full" and any(p is not None for p in pins):
            raise ValueError("full mode cannot claim a metadata baseline")
        if len(set(self.changed_paths)) != len(self.changed_paths):
            raise ValueError("duplicate changed paths")
        return self


def _full(head: str) -> CiDecision:
    return CiDecision(
        mode="full",
        head_sha=head,
        baseline_sha=None,
        baseline_run_id=None,
        baseline_attempt=None,
        changed_paths=(),
    )


def _trusted_full(api: GitHub, run_id: int, attempt: int) -> str:
    run = api.run(run_id, attempt)
    jobs = api.pages(f"/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
    sha = verify_ci(run, jobs, run_id=run_id, attempt=attempt)
    for job in jobs:
        for key, expected in {"run_id": run_id, "run_attempt": attempt, "head_sha": sha}.items():
            same(job.get(key), expected, "FULL_BASELINE", key)
    full = [j for j in jobs if j.get("name") == "full-validation"]
    require(len(full) == 1, "FULL_BASELINE", "full validation is absent or duplicated")
    for key, expected in {
        "status": "completed",
        "conclusion": "success",
        "run_id": run_id,
        "run_attempt": attempt,
        "head_sha": sha,
    }.items():
        same(full[0].get(key), expected, "FULL_BASELINE", key)
    return sha


def _allowed(path: str) -> bool:
    pieces = path.split("/")
    if len(pieces) != 4 or pieces[:2] != ["docs", "ota"]:
        return False
    suffix = {"release-requests": ".json", "releases": ".md"}.get(pieces[2])
    return (
        suffix is not None
        and pieces[3].endswith(suffix)
        and re.fullmatch(RELEASE_ID_PATTERN, pieces[3][: -len(suffix)]) is not None
    )


def _metadata_diff(repo: Path, base: str, head: str) -> tuple[str, ...] | None:
    # --raw -z keeps LF/tab/non-ASCII names as bytes and exposes mode/status changes.
    raw = git(
        repo,
        "diff",
        "--raw",
        "-z",
        "--no-abbrev",
        "--find-renames",
        "--find-copies",
        "--find-copies-harder",
        base,
        head,
        "--",
    )
    parts = raw.split(b"\0")
    if not raw or parts[-1] != b"" or (len(parts) - 1) % 2:
        return None
    paths: list[str] = []
    for at in range(0, len(parts) - 1, 2):
        header = parts[at].decode("ascii", "strict").split()
        if len(header) != 5 or header[4] not in {"A", "M"}:
            return None  # Renames/copies have an extra name; never reinterpret it as a header.
        old, new = header[0].removeprefix(":"), header[1]
        if (header[4] == "A" and (old, new) != ("000000", "100644")) or (
            header[4] == "M" and (old, new) != ("100644", "100644")
        ):
            return None
        path = parts[at + 1].decode("utf-8", "strict")
        if not _allowed(path):
            return None
        paths.append(path)
    return tuple(paths) if paths else None


def classify_changes(api: GitHub, repo: Path, *, head_sha: str, event: str) -> CiDecision:
    actual = git(repo, "rev-parse", "HEAD").decode("ascii").strip()
    fallback = _full(actual)
    if event != "push":
        return fallback
    try:
        same(actual, head_sha, "CI_CHECKOUT", "checkout")
        same(git(repo, "rev-parse", "origin/main").decode().strip(), head_sha, "CI_MAIN", "main")
        candidates = api.pages(
            f"/actions/workflows/{Path(CI_WORKFLOW).name}/runs",
            "workflow_runs",
            params={"event": "push", "branch": "main", "status": "completed"},
        )
        for candidate in candidates:
            run_id, attempt = positive(candidate.get("id")), positive(candidate.get("run_attempt"))
            try:
                baseline = _trusted_full(api, run_id, attempt)
            except PreparationBlocked as exc:
                if exc.code in {"LISTING_INVALID", "LISTING_TRUNCATED", "GITHUB_HTTP"}:
                    return fallback
                continue
            # API identity alone does not prove ancestry of this exact checked-out tree.
            history = git(repo, "rev-list", head_sha).decode("ascii").splitlines()
            if baseline not in history:
                continue
            paths = _metadata_diff(repo, baseline, head_sha)
            if paths is None:
                return fallback
            return CiDecision(
                mode="metadata",
                head_sha=head_sha,
                baseline_sha=baseline,
                baseline_run_id=run_id,
                baseline_attempt=attempt,
                changed_paths=paths,
            )
    except (
        PreparationBlocked,
        RequestRejected,
        OtaError,
        ValueError,
        OSError,
        TypeError,
        httpx.HTTPError,
    ):
        return fallback
    return fallback


def validate_metadata_changes(api: GitHub, repo: Path, decision: CiDecision) -> None:
    # The downloaded decision is data. Re-check its trust chain before choosing short CI.
    require(decision.mode == "metadata", "CI_MODE", "metadata decision required")
    same(git(repo, "rev-parse", "HEAD").decode().strip(), decision.head_sha, "CI_CHECKOUT", "head")
    same(
        git(repo, "rev-parse", "origin/main").decode().strip(), decision.head_sha, "CI_MAIN", "main"
    )
    assert decision.baseline_run_id is not None and decision.baseline_attempt is not None
    baseline = _trusted_full(api, decision.baseline_run_id, decision.baseline_attempt)
    same(baseline, decision.baseline_sha, "FULL_BASELINE", "baseline SHA")
    require(
        baseline in git(repo, "rev-list", decision.head_sha).decode("ascii").splitlines(),
        "FULL_BASELINE",
        "baseline is not an ancestor",
    )
    same(
        _metadata_diff(repo, baseline, decision.head_sha),
        decision.changed_paths,
        "CI_DIFF",
        "all baseline-to-head paths",
    )
    for release in sorted({Path(p).stem for p in decision.changed_paths}):
        path = f"docs/ota/release-requests/{release}.json"
        request = parse_request(git(repo, "show", f"HEAD:{path}"))
        same(request.release_id, release, "REQUEST_IDENTITY", "filename")
        provenance._main_commit(request.source_commit)
        _package_metadata(api, request)
        approval = approval_record_at(repo, "HEAD", release)
        if approval is None:
            require(
                not any(p == f"{APPROVALS_DIR}/{release}.md" for p in decision.changed_paths),
                "APPROVAL",
                "changed approval missing",
            )
            continue  # A request can be pending approval; CI cannot grant G7.
        problems = approval_record_problems(
            approval.decode("utf-8", "strict"),
            release_id=release,
            manifest_sha256=request.manifest_sha256,
            source_commit=request.source_commit,
        )
        # Unfilled owner decisions remain pending; pins/STOP gates still must be valid.
        require(
            not [p for p in problems if not p.startswith("the record still has ")],
            "APPROVAL",
            "approval pins or fields are invalid",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("classify", "validate"))
    parser.add_argument("--decision-file", type=Path, required=True)
    args = parser.parse_args(argv)
    env = os.environ
    try:
        with httpx.Client(timeout=30, follow_redirects=False) as client:
            api = GitHub(client, env.get("GITHUB_TOKEN", ""))
            if args.command == "classify":
                decision = classify_changes(
                    api,
                    TRUSTED_REPO,
                    head_sha=env.get("GITHUB_SHA", ""),
                    event=env.get("GITHUB_EVENT_NAME", "")
                    if env.get("GITHUB_REPOSITORY") == REPOSITORY
                    and env.get("GITHUB_REF") == "refs/heads/main"
                    else "pull_request",
                )
                args.decision_file.write_text(decision.model_dump_json(by_alias=True) + "\n")
                if output := env.get("GITHUB_OUTPUT"):
                    with Path(output).open("a") as stream:
                        stream.write(f"mode={decision.mode}\n")
                print(decision.model_dump_json(by_alias=True))
            else:
                require(
                    env.get("GITHUB_EVENT_NAME") == "push"
                    and env.get("GITHUB_REPOSITORY") == REPOSITORY
                    and env.get("GITHUB_REF") == "refs/heads/main",
                    "CI_CONTEXT",
                    "main push only",
                )
                decision = parse_strict(CiDecision, args.decision_file.read_bytes())
                same(decision.head_sha, env.get("GITHUB_SHA"), "CI_CONTEXT", "head")
                validate_metadata_changes(api, TRUSTED_REPO, decision)
    except (
        PreparationBlocked,
        RequestRejected,
        OtaError,
        ValueError,
        OSError,
        TypeError,
        httpx.HTTPError,
    ):
        print("OTA metadata CI refused; no owner approval was granted")
        return 1
    return 0
