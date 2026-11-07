"""Prove current committed release requests against exact main/push CI history."""

from __future__ import annotations

import re
from collections.abc import Mapping
from pathlib import Path

from talent_data.ota.automation.prepare import (
    CI_WORKFLOW,
    REQUEST_DIR,
    GitHub,
    PreparationBlocked,
    git,
    obj,
    positive,
    require,
    same,
    verify_ci,
)
from talent_data.ota.automation.provenance import REPOSITORY
from talent_data.ota.automation.requests import ReleaseRequest, parse_request
from talent_data.ota.release_package import APPROVALS_DIR, approval_record_at


def committed_requests(repo: Path) -> tuple[ReleaseRequest, ...]:
    """Read the entire request set from fetched main, rejecting unexpected files."""
    paths = (
        git(repo, "ls-tree", "-r", "--name-only", "origin/main", REQUEST_DIR)
        .decode("utf-8", "strict")
        .splitlines()
    )
    requests = []
    for path in paths:
        require(path.startswith(REQUEST_DIR + "/"), "REQUEST_PATH", "invalid request path")
        request = parse_request(git(repo, "show", f"origin/main:{path}"))
        require(
            path == f"{REQUEST_DIR}/{request.release_id}.json",
            "REQUEST_PATH",
            "request name does not match release",
        )
        requests.append(request)
    return tuple(requests)


def request_change_commit(repo: Path, request: ReleaseRequest) -> str:
    """Identify the latest first-parent change to this request or its approval.

    An approval-only merge must trigger the same exact-package assessment as the
    request merge. CI for an older approval, a feature branch or unrelated work
    cannot authorize the current pair of committed request/approval bytes.
    """
    path = f"{REQUEST_DIR}/{request.release_id}.json"
    approval_path = f"{APPROVALS_DIR}/{request.release_id}.md"
    current = git(repo, "show", f"origin/main:{path}")
    require(parse_request(current) == request, "REQUEST_CHANGED", "current main request differs")
    commit = (
        git(
            repo,
            "log",
            "--first-parent",
            "-1",
            "--format=%H",
            "origin/main",
            "--",
            path,
            approval_path,
        )
        .decode("ascii", "strict")
        .strip()
    )
    require(bool(commit), "REQUEST_HISTORY", "request change commit is missing")
    require(
        git(repo, "show", f"{commit}:{path}") == current,
        "REQUEST_HISTORY",
        "request bytes differ at change commit",
    )
    require(
        approval_record_at(repo, commit, request.release_id)
        == approval_record_at(repo, "origin/main", request.release_id),
        "REQUEST_HISTORY",
        "approval bytes differ at change commit",
    )
    return commit


def authorize_request(
    api: GitHub,
    repo: Path,
    request: ReleaseRequest,
    *,
    ci_run_id: int | None = None,
    ci_attempt: int | None = None,
) -> str:
    """Require successful CI for the latest change to this request or its approval.

    Event processing supplies its own pinned CI run/attempt; scheduled reconciliation
    discovers runs independently of artifacts and checks each attempt via the API.
    """
    commit = request_change_commit(repo, request)
    require((ci_run_id is None) == (ci_attempt is None), "CI_ORIGIN", "incomplete CI pin")
    if ci_run_id is None:
        candidates = api.pages(
            f"/actions/workflows/{Path(CI_WORKFLOW).name}/runs",
            "workflow_runs",
            params={"event": "push", "branch": "main", "head_sha": commit},
        )
        pins = [(positive(run.get("id")), positive(run.get("run_attempt"))) for run in candidates]
    else:
        pins = [(positive(ci_run_id), positive(ci_attempt))]
    for run_id, attempt in pins:
        run = api.run(run_id, attempt)
        if run.get("head_sha") != commit:
            continue
        jobs = api.pages(f"/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
        try:
            sha = verify_ci(run, jobs, run_id=run_id, attempt=attempt)
        except PreparationBlocked:
            continue
        if sha == commit:
            return sha
    raise PreparationBlocked("CI_MISSING", "no successful CI for exact request-change commit")


def verify_invocation(
    api: GitHub, repo: Path, environment: Mapping[str, str], payload: dict[str, object]
) -> None:
    """Bind this trusted-main workflow run, actor, ref and event to GitHub API facts."""
    same(environment.get("GITHUB_REPOSITORY"), REPOSITORY, "TRIGGER_REPOSITORY", "repository")
    same(environment.get("GITHUB_REF"), "refs/heads/main", "TRIGGER_REF", "ref")
    event = environment.get("GITHUB_EVENT_NAME", "")
    require(event in ("workflow_run", "workflow_dispatch", "schedule"), "TRIGGER_EVENT", "event")
    head = environment.get("GITHUB_SHA", "")
    same(git(repo, "rev-parse", "HEAD").decode().strip(), head, "TRIGGER_CHECKOUT", "checkout")
    same(
        git(repo, "rev-parse", "origin/main").decode().strip(), head, "TRIGGER_MAIN", "current main"
    )
    raw_id = environment.get("GITHUB_RUN_ID", "")
    raw_attempt = environment.get("GITHUB_RUN_ATTEMPT", "")
    require(
        raw_id.isascii()
        and raw_id.isdecimal()
        and raw_attempt.isascii()
        and raw_attempt.isdecimal(),
        "TRIGGER_RUN",
        "invalid run pin",
    )
    run_id, attempt = positive(int(raw_id)), positive(int(raw_attempt))
    run = api.run(run_id, attempt)
    for field, expected in {
        "id": run_id,
        "run_attempt": attempt,
        "path": ".github/workflows/ota-production-publish.yml",
        "event": event,
        "head_sha": head,
        "head_branch": "main",
    }.items():
        same(run.get(field), expected, "TRIGGER_ORIGIN", field)
    for field in ("repository", "head_repository"):
        same(obj(run.get(field)).get("full_name"), REPOSITORY, "TRIGGER_ORIGIN", field)
    actor = environment.get("GITHUB_ACTOR", "")
    require(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*(?:\[bot\])?", actor) is not None,
        "TRIGGER_ACTOR",
        "invalid actor",
    )
    same(obj(run.get("actor")).get("login"), actor, "TRIGGER_ACTOR", "actor")
    same(
        obj(payload.get("repository")).get("full_name"),
        REPOSITORY,
        "TRIGGER_REPOSITORY",
        "event repository",
    )
    if event == "workflow_dispatch":
        require("sender" in payload, "TRIGGER_ACTOR", "manual sender missing")
        same(obj(payload["sender"]).get("login"), actor, "TRIGGER_ACTOR", "event sender")
        permission = obj(api.repo("GET", f"/collaborators/{actor}/permission"))
        require(
            permission.get("permission") in ("admin", "maintain", "write"),
            "TRIGGER_PERMISSION",
            "actor cannot write repository",
        )
    if event == "workflow_run":
        same(payload.get("action"), "completed", "TRIGGER_CI", "action")
        trigger = obj(payload.get("workflow_run"))
        ci_id, ci_attempt = positive(trigger.get("id")), positive(trigger.get("run_attempt"))
        ci = api.run(ci_id, ci_attempt)
        same(ci.get("id"), ci_id, "TRIGGER_CI", "id")
        same(ci.get("run_attempt"), ci_attempt, "TRIGGER_CI", "attempt")
        same(ci.get("head_sha"), trigger.get("head_sha"), "TRIGGER_CI", "head SHA")
        verify_ci(
            ci,
            api.pages(f"/actions/runs/{ci_id}/attempts/{ci_attempt}/jobs", "jobs"),
            run_id=ci_id,
            attempt=ci_attempt,
        )
