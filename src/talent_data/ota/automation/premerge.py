"""One candidate MR: conditional metadata append, never merge or production writes."""

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx

from talent_data.ota.automation.candidate import (
    CandidateBinding,
    VerifiedCandidate,
    binding_digest,
    candidate_notice,
)
from talent_data.ota.automation.merge_approval import OwnerMergeRecord, render_merge_record
from talent_data.ota.automation.prepare import (
    APPROVAL_DIR,
    BOT,
    REQUEST_DIR,
    GitHub,
    PreparedProposal,
    obj,
    require,
    same,
)
from talent_data.ota.automation.requests import ReleaseRequest, RunPin
from talent_data.ota.verify import TrustedKey, sha256_ref

REPOSITORY = "AlexeyKravtsov95/TalentForge"


def metadata_paths(request: ReleaseRequest, binding: CandidateBinding) -> dict[str, str]:
    for actual, expected in (
        (binding.repository, request.repository),
        (binding.source_sha, request.source_commit),
        (binding.build, request.build),
        (binding.revision, request.revision),
        (binding.staging_run, request.staging_run),
        (binding.package_run, request.package_run),
        (binding.base_release_id, request.base_release_id),
        (binding.base_manifest_sha256, request.base_manifest_sha256),
    ):
        same(actual, expected, "CANDIDATE_BINDING", "request identity")
    raw = request.model_dump_json(by_alias=True, indent=2) + "\n"
    record = OwnerMergeRecord(
        schema_version=1,
        kind="ota-owner-merge-approval",
        policy="owner-merge-v1",
        repository="AlexeyKravtsov95/TalentForge",
        pull_number=binding.pull_number,
        release_id=request.release_id,
        source_sha=request.source_commit,
        binding_sha256=binding_digest(binding),
        request_digest=sha256_ref(raw.encode()),
        accepted_flags=binding.flagged_gates,
        revoked=False,
    )
    return {
        f"{REQUEST_DIR}/{request.release_id}.json": raw,
        f"{APPROVAL_DIR}/{request.release_id}.md": render_merge_record(record),
    }


def policy_pull(api: GitHub, pull_number: int) -> dict[str, Any]:
    pull = obj(api.repo("GET", f"/pulls/{pull_number}"))
    same(pull.get("state"), "open", "CANDIDATE_POLICY", "open PR")
    same(pull.get("merged"), False, "CANDIDATE_POLICY", "not merged")
    for part in ("head", "base"):
        same(
            obj(obj(pull.get(part)).get("repo")).get("full_name"),
            REPOSITORY,
            "CANDIDATE_POLICY",
            "repository",
        )
    same(obj(pull.get("base")).get("ref"), "main", "CANDIDATE_POLICY", "base")
    actor = obj(pull.get("user"))
    same(actor.get("id"), 351368232, "CANDIDATE_POLICY", "App id")
    same(
        actor.get("login"), "talentforge-ota-alexeykravtsov95[bot]", "CANDIDATE_POLICY", "App login"
    )
    candidate_notice(str(pull.get("body", "")))
    return pull


def verify_metadata_head(
    api: GitHub, head: str, request: ReleaseRequest, binding: CandidateBinding
) -> None:
    paths = metadata_paths(request, binding)
    commit = obj(api.repo("GET", f"/git/commits/{head}"))
    same(
        [obj(p).get("sha") for p in commit.get("parents", [])],
        [request.source_commit],
        "METADATA_CHANGED",
        "metadata parent",
    )
    same(
        obj(commit.get("author")).get("email"), BOT["email"], "METADATA_CHANGED", "metadata author"
    )
    comparison = obj(api.repo("GET", f"/compare/{request.source_commit}...{head}"))
    same(comparison.get("total_commits"), 1, "METADATA_CHANGED", "one metadata commit")
    files = comparison.get("files", [])
    require(
        isinstance(files, list)
        and len(files) == len(paths)
        and {obj(f).get("filename") for f in files} == set(paths)
        and all(obj(f).get("status") == "added" for f in files),
        "METADATA_CHANGED",
        "only new release metadata",
    )
    tree = obj(
        api.repo("GET", f"/git/trees/{obj(commit.get('tree')).get('sha')}", params={"recursive": 1})
    )
    same(tree.get("truncated"), False, "METADATA_CHANGED", "complete tree")
    entries = {obj(e).get("path"): obj(e) for e in tree.get("tree", [])}
    for path, raw in paths.items():
        same(entries.get(path, {}).get("mode"), "100644", "METADATA_CHANGED", "regular metadata")
        same(entries.get(path, {}).get("type"), "blob", "METADATA_CHANGED", "metadata blob")
        same(api.content(path, head), raw.encode(), "METADATA_CHANGED", "committed bytes")


def writer_scope(writer: GitHub) -> None:
    installation = obj(writer.call("GET", "/installation/repositories", params={"per_page": 100}))
    same(installation.get("total_count"), 1, "APP_SCOPE", "single repository")
    repos = installation.get("repositories")
    require(
        isinstance(repos, list)
        and len(repos) == 1
        and obj(repos[0]).get("full_name") == REPOSITORY,
        "APP_SCOPE",
        "TalentForge installation only",
    )


def bind_candidate_request(
    reader: GitHub,
    writer: GitHub,
    repo: Path,
    candidate: VerifiedCandidate,
    request: ReleaseRequest,
    binding: CandidateBinding,
) -> str:
    paths = metadata_paths(request, binding)
    for actual, expected in (
        (binding.pull_number, candidate.pull_number),
        (binding.source_sha, candidate.source_sha),
        (binding.source_tree, candidate.source_tree),
        (binding.base_sha, candidate.base_sha),
        (binding.tooling_sha, candidate.tooling_sha),
        (binding.candidate_run, candidate.candidate_run),
        (binding.candidate_artifact, candidate.candidate_artifact),
        (binding.preparation_ci, candidate.ci_pin),
    ):
        same(actual, expected, "CANDIDATE_BINDING", "verified candidate")
    pull = policy_pull(reader, candidate.pull_number)
    same(obj(pull.get("base")).get("sha"), candidate.base_sha, "BASE_CHANGED", "PR base")
    branch = str(obj(pull.get("head")).get("ref"))
    same(branch, f"ota-candidate/{candidate.game}-{candidate.build}", "BRANCH_CHANGED", "branch")
    head = str(obj(pull.get("head")).get("sha"))
    if head != candidate.source_sha:
        verify_metadata_head(reader, head, request, binding)
        return head
    for path in paths:
        require(
            reader.content(path, head) is None,
            "METADATA_CHANGED",
            "release metadata already exists",
        )
    writer_scope(writer)
    parent = obj(reader.repo("GET", f"/git/commits/{head}"))
    tree = obj(
        writer.repo(
            "POST",
            "/git/trees",
            data={
                "base_tree": obj(parent.get("tree"))["sha"],
                "tree": [
                    {"path": p, "mode": "100644", "type": "blob", "content": raw}
                    for p, raw in paths.items()
                ],
            },
        )
    )
    identity = {**BOT, "date": request.created_at}
    commit = obj(
        writer.repo(
            "POST",
            "/git/commits",
            data={
                "message": f"ota: bind frozen package {request.release_id}",
                "tree": tree["sha"],
                "parents": [head],
                "author": identity,
                "committer": identity,
            },
        )
    )
    result = str(commit["sha"])
    # The only ref update is fast-forward D->P. Any concurrent sibling/foreign commit
    # makes this update non-fast-forward and GitHub refuses it; never force or retry.
    current = policy_pull(reader, candidate.pull_number)
    same(obj(current.get("head")).get("sha"), head, "BRANCH_CHANGED", "head before update")
    writer.repo("PATCH", "/git/refs/heads/" + branch, data={"sha": result, "force": False})
    same(
        obj(policy_pull(reader, candidate.pull_number).get("head")).get("sha"),
        result,
        "BRANCH_CHANGED",
        "read-back",
    )
    verify_metadata_head(reader, result, request, binding)
    return result


@dataclass(frozen=True)
class CandidateReview:
    pull: dict[str, Any]
    request: ReleaseRequest
    binding: CandidateBinding
    candidate: VerifiedCandidate
    gates: tuple[dict[str, Any], ...]
    file_count: int
    total_bytes: int


def render_ready_body(review: CandidateReview) -> str:
    request, binding = review.request, review.binding
    marker = "<!-- talentforge:frozen-review-v1 -->"
    original = str(review.pull.get("body", "")).split(marker, 1)[0].rstrip()
    lines = [
        original,
        "",
        marker,
        "",
        "## Пакет готов к выпуску",
        "",
        "После проверки нажми обычный Merge. Это разрешает автоматическую публикацию "
        "этого пакета для live-серверов; второго MR "
        "или дополнительного подтверждения не требуется. "
        "Используй merge commit; squash/rebase в этой схеме не поддерживаются.",
        "",
        f"- Выпуск: `{request.release_id}`; {review.file_count} файлов, {review.total_bytes} байт.",
        f"- Манифест: `{request.manifest_sha256}`.",
        f"- Источник: `{request.source_commit}`; создан: `{request.created_at}`.",
        f"- База: `{request.base_release_id}` / `{request.base_manifest_sha256}`.",
        f"- Пакет: artifact `{request.artifact.artifact_id}`, `{request.artifact.archive_sha256}`; "
        f"[проверенный запуск](https://github.com/{request.repository}/actions/runs/{request.package_run.run_id}/attempts/{request.package_run.attempt}).",
        f"- [Staging](https://github.com/{request.repository}/actions/runs/"
        f"{request.staging_run.run_id}/attempts/{request.staging_run.attempt}): "
        "пройден на тех же файлах.",
        "- Полный CI финального MR пройден. Пакет после merge не пересобирается.",
    ]
    if "G5" in binding.flagged_gates:
        detail = next(
            str(g.get("detail"))
            for g in review.gates
            if g.get("gate") == "G5" and g.get("status") == "FLAG"
        )
        lines += [
            "",
            "Новые предупреждения относительно базы (merge означает их принятие):",
            "",
            detail,
        ]
    lines += [
        "",
        "Целевое время от merge до полной публичной проверки — 10 минут. "
        "Это целевой показатель; задержки GitHub и фактическое время проверки "
        "сохраняются в отчёте.",
        "",
    ]
    return "\n".join(lines)


def review_package(reader: GitHub, repo: Path, pull_number: int) -> CandidateReview:
    """Fresh API/archive/source proof for an MR with appended release metadata."""
    import tempfile

    from talent_data.ota.automation.candidate import verify_candidate
    from talent_data.ota.automation.prepare import artifact_for
    from talent_data.ota.automation.provenance import (
        extract_verified_archive,
        verify_package_origin,
    )
    from talent_data.ota.automation.requests import parse_request
    from talent_data.ota.release_package import candidate_binding, load_package

    pull = policy_pull(reader, pull_number)
    head = str(obj(pull.get("head")).get("sha"))
    commit = obj(reader.repo("GET", f"/git/commits/{head}"))
    parents = commit.get("parents", [])
    require(len(parents) == 1, "METADATA_CHANGED", "one metadata parent")
    source = str(obj(parents[0]).get("sha"))
    comparison = obj(reader.repo("GET", f"/compare/{source}...{head}"))
    requests = [
        str(obj(f).get("filename"))
        for f in comparison.get("files", [])
        if str(obj(f).get("filename")).startswith(REQUEST_DIR + "/")
    ]
    require(len(requests) == 1, "METADATA_CHANGED", "one release request")
    raw = reader.content(requests[0], head)
    require(raw is not None, "METADATA_CHANGED", "request missing")
    assert raw is not None
    request = parse_request(raw)
    run = reader.run(request.package_run.run_id, request.package_run.attempt)
    artifact = artifact_for(reader, run, request.artifact.name)
    same(artifact.get("id"), request.artifact.artifact_id, "ARTIFACT_CHANGED", "original package")
    archive = reader.archive(artifact)
    with tempfile.TemporaryDirectory(prefix="ota-review-package-") as temp:
        package = load_package(extract_verified_archive(archive, Path(temp).resolve() / "package"))
        binding = candidate_binding(package)
        require(binding is not None, "CANDIDATE_BINDING", "indexed proof required")
        assert binding is not None
        candidate = verify_candidate(
            reader,
            repo,
            pull_number,
            source_sha=request.source_commit,
            ci_pin=binding.preparation_ci,
            metadata=(request, binding),
        )
        verify_package_origin(
            request,
            run_metadata={
                "package": run,
                "staging": reader.run(request.staging_run.run_id, request.staging_run.attempt),
            },
            artifact_metadata=artifact,
            archive_bytes=archive,
            package=package,
            candidate_context=candidate,
        )
        from talent_data.ota.verify import strict_json

        rows = strict_json(package.attachment("report/gates.json"))
        assert isinstance(rows, list)
        review = CandidateReview(
            pull,
            request,
            binding,
            candidate,
            tuple(obj(r) for r in rows),
            package.index.file_count,
            package.index.total_byte_length,
        )
    return review


def mark_candidate_ready(
    reader: GitHub, writer: GitHub, repo: Path, pull_number: int, *, ci_pin: RunPin
) -> None:
    from talent_data.ota.automation.candidate import verify_full_pr_ci

    review = review_package(reader, repo, pull_number)
    pull, request, binding = review.pull, review.request, review.binding
    head = str(obj(pull.get("head")).get("sha"))
    verify_full_pr_ci(reader, pull_number, head, ci_pin)
    verify_metadata_head(reader, head, request, binding)
    current = policy_pull(reader, pull_number)
    same(obj(current.get("head")).get("sha"), head, "CANDIDATE_CHANGED", "head before ready")
    body = render_ready_body(review)
    if current.get("body") != body:
        writer_scope(writer)
        writer.repo("PATCH", f"/pulls/{pull_number}", data={"body": body})
        current = policy_pull(reader, pull_number)
        same(
            obj(current.get("head")).get("sha"), head, "CANDIDATE_CHANGED", "review body read-back"
        )
    if current.get("draft") is False:
        return
    same(current.get("draft"), True, "CANDIDATE_CHANGED", "draft state")
    writer_scope(writer)
    node = current.get("node_id")
    require(isinstance(node, str) and bool(node), "CANDIDATE_CHANGED", "PR node id")
    result = obj(
        writer.call(
            "POST",
            "/graphql",
            data={
                "query": (
                    "mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id})"
                    "{pullRequest{id isDraft}}}"
                ),
                "variables": {"id": node},
            },
        )
    )
    require(not result.get("errors"), "READY_FAILED", "GitHub refused readiness")
    same(
        obj(policy_pull(reader, pull_number).get("head")).get("sha"),
        head,
        "CANDIDATE_CHANGED",
        "ready read-back",
    )
    same(policy_pull(reader, pull_number).get("draft"), False, "READY_FAILED", "ready read-back")


def route_candidate_ci(reader: GitHub, repo: Path, pin: RunPin) -> tuple[str, int]:
    from talent_data.ota.automation.candidate import verify_full_pr_ci
    from talent_data.ota.automation.requests import parse_request

    run = reader.run(pin.run_id, pin.attempt)
    for field, expected in {
        "id": pin.run_id,
        "run_attempt": pin.attempt,
        "path": ".github/workflows/ci.yml",
        "name": "CI",
        "event": "pull_request",
        "head_sha": pin.head_sha,
        "status": "completed",
        "conclusion": "success",
    }.items():
        same(run.get(field), expected, "PREMERGE_TRIGGER", field)
    for key in ("repository", "head_repository"):
        same(obj(run.get(key)).get("full_name"), REPOSITORY, "PREMERGE_TRIGGER", "repository")
    pulls = run.get("pull_requests")
    require(
        isinstance(pulls, list) and len(pulls) == 1, "PREMERGE_TRIGGER", "single PR association"
    )
    assert isinstance(pulls, list)
    number = obj(pulls[0]).get("number")
    require(type(number) is int and number > 0, "PREMERGE_TRIGGER", "PR number")
    assert isinstance(number, int)
    pull = policy_pull(reader, number)
    head = str(obj(pull.get("head")).get("sha"))
    branch = str(obj(pull.get("head")).get("ref"))
    same(run.get("head_branch"), branch, "PREMERGE_TRIGGER", "branch")
    require(branch.startswith("ota-candidate/forever-"), "PREMERGE_TRIGGER", "candidate branch")
    verify_full_pr_ci(reader, number, head, pin)
    build = branch.removeprefix("ota-candidate/forever-")
    raw = reader.content(f"{REQUEST_DIR}/{build}-r1.json", head)
    if raw is None:
        return "prepare", number
    request = parse_request(raw)
    same(request.build, build, "PREMERGE_TRIGGER", "metadata build")
    return "ready", number


def prepare_candidate(
    client: httpx.Client,
    *,
    reader: GitHub,
    repo: Path,
    ci_pin: RunPin,
    destination: Path,
    now: datetime,
    keyring: Mapping[str, TrustedKey],
) -> PreparedProposal | None:
    from talent_data.ota.automation.candidate import verify_candidate
    from talent_data.ota.automation.prepare import prepare_request

    phase, number = route_candidate_ci(reader, repo, ci_pin)
    require(
        phase == "prepare", "PREMERGE_PHASE", "metadata already prepared; only readiness allowed"
    )
    pull = policy_pull(reader, number)
    candidate = verify_candidate(
        reader, repo, number, source_sha=str(obj(pull.get("head")).get("sha")), ci_pin=ci_pin
    )
    return prepare_request(
        client,
        token=reader.token,
        ci_run_id=ci_pin.run_id,
        ci_attempt=ci_pin.attempt,
        repo=repo,
        keyring=keyring,
        now=now,
        destination=destination,
        api=reader,
        candidate_context=candidate,
    )
