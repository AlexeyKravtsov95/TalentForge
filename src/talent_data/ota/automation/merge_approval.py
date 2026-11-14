"""A conditional approval becomes effective only after independently verified owner merge."""

import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, model_validator

from talent_data.ota.automation.candidate import (
    CandidateBinding,
    VersionedModel,
    candidate_notice,
    verify_full_pr_ci,
    verify_original_candidate,
)
from talent_data.ota.automation.prepare import GitHub, git, obj, require, same
from talent_data.ota.automation.requests import ReleaseRequest, RunPin
from talent_data.ota.gates import ApprovalRecord, Gate
from talent_data.ota.models import COMMIT_PATTERN, RELEASE_ID_PATTERN, SHA256_PATTERN
from talent_data.ota.verify import OtaError, parse_strict

MODE_LINE = "- Authorization mode: owner-merge-v1"
FENCE = "```owner-merge-v1"


class OwnerMergeRecord(VersionedModel):
    kind: Literal["ota-owner-merge-approval"]
    policy: Literal["owner-merge-v1"]
    repository: Literal["AlexeyKravtsov95/TalentForge"]
    pull_number: int = Field(gt=0)
    release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    source_sha: str = Field(pattern=COMMIT_PATTERN)
    binding_sha256: str = Field(pattern=SHA256_PATTERN)
    request_digest: str = Field(pattern=SHA256_PATTERN)
    accepted_flags: tuple[Literal["G5", "G7"], ...]
    revoked: bool

    @model_validator(mode="after")
    def unique_flags(self) -> Self:
        if len(set(self.accepted_flags)) != len(self.accepted_flags):
            raise ValueError("duplicate accepted flags")
        return self


def parse_merge_record(text: str) -> OwnerMergeRecord | None:
    if "- Authorization mode:" not in text and FENCE not in text:
        return None
    if text.splitlines().count(MODE_LINE) != 1 or text.count(FENCE) != 1:
        raise ValueError("ambiguous or unsupported merge authorization")
    match = re.search(r"^```owner-merge-v1\n(.+?)\n```$", text, re.M | re.S)
    if match is None:
        raise ValueError("missing conditional authorization payload")
    try:
        return parse_strict(OwnerMergeRecord, match.group(1).encode())
    except OtaError as exc:
        raise ValueError("invalid conditional authorization payload") from exc


def render_merge_record(record: OwnerMergeRecord) -> str:
    raw = json.dumps(record.model_dump(mode="json", by_alias=True), indent=2, ensure_ascii=False)
    return (
        f"# OTA release authorization — {record.release_id}\n\n{MODE_LINE}\n\n"
        "Слияние этого MR владельцем разрешает публикацию указанного пакета для live-серверов.\n"
        "До проверенного слияния разрешение не действует.\n\n"
        f"{FENCE}\n{raw}\n```\n"
    )


@dataclass(frozen=True)
class VerifiedOwnerMerge:
    record: OwnerMergeRecord
    request: ReleaseRequest
    binding: CandidateBinding
    final_head_sha: str
    final_tree: str
    merge_sha: str
    base_sha: str
    main_sha: str
    owner_login: str
    owner_id: int
    merged_at: datetime
    pr_ci: RunPin


def verify_owner_merge(
    api: GitHub,
    repo: Path,
    request: ReleaseRequest,
    record: OwnerMergeRecord,
    *,
    binding: CandidateBinding,
) -> VerifiedOwnerMerge:
    from talent_data.ota.automation.premerge import metadata_paths, verify_metadata_head

    require(record.revoked is False, "OWNER_MERGE_REVOKED", "merge authorization withdrawn")
    paths = metadata_paths(request, binding)
    expected = parse_merge_record(paths[f"docs/ota/releases/{request.release_id}.md"])
    same(record, expected, "OWNER_MERGE_RECORD", "conditional record")
    pull = obj(api.repo("GET", f"/pulls/{record.pull_number}"))
    for field, value in {
        "number": record.pull_number,
        "state": "closed",
        "merged": True,
        "draft": False,
    }.items():
        same(pull.get(field), value, "OWNER_MERGE_ORIGIN", field)
    for ref in ("head", "base"):
        same(
            obj(obj(pull.get(ref)).get("repo")).get("full_name"),
            request.repository,
            "OWNER_MERGE_ORIGIN",
            "repository",
        )
    same(obj(pull.get("base")).get("ref"), "main", "OWNER_MERGE_ORIGIN", "base ref")
    same(obj(pull.get("base")).get("sha"), binding.base_sha, "OWNER_MERGE_ORIGIN", "frozen base")
    actor = obj(pull.get("user"))
    same(
        actor.get("login"),
        "talentforge-ota-alexeykravtsov95[bot]",
        "OWNER_MERGE_ORIGIN",
        "App login",
    )
    same(actor.get("id"), 351368232, "OWNER_MERGE_ORIGIN", "App id")
    owner = obj(pull.get("merged_by"))
    same(owner.get("login"), "AlexeyKravtsov95", "OWNER_MERGE_ORIGIN", "owner login")
    same(owner.get("id"), 5190861, "OWNER_MERGE_ORIGIN", "owner id")
    notice = candidate_notice(str(pull.get("body", "")))
    same(notice.preparation_run, binding.candidate_run, "OWNER_MERGE_ORIGIN", "original producer")
    same(
        notice.candidate_artifact,
        binding.candidate_artifact,
        "OWNER_MERGE_ORIGIN",
        "original data artifact",
    )
    head = str(obj(pull.get("head")).get("sha"))
    merge = str(pull.get("merge_commit_sha"))
    main = git(repo, "rev-parse", "origin/main").decode().strip()
    git(repo, "merge-base", "--is-ancestor", merge, main)
    git(repo, "merge-base", "--is-ancestor", request.source_commit, main)
    merged = obj(api.repo("GET", f"/git/commits/{merge}"))
    same(
        [obj(p).get("sha") for p in merged.get("parents", [])],
        [binding.base_sha, head],
        "OWNER_MERGE_TREE",
        "ordinary merge parents",
    )
    local_parents = git(repo, "show", "-s", "--format=%P", merge).decode().strip().split()
    same(local_parents, [binding.base_sha, head], "OWNER_MERGE_TREE", "local merge parents")
    tree = git(repo, "rev-parse", head + "^{tree}").decode().strip()
    same(
        git(repo, "rev-parse", merge + "^{tree}").decode().strip(),
        tree,
        "OWNER_MERGE_TREE",
        "exact merge tree",
    )
    same(obj(merged.get("tree")).get("sha"), tree, "OWNER_MERGE_TREE", "API merge tree")
    verify_metadata_head(api, head, request, binding)
    for ref in (head, merge, "origin/main"):
        for path, text in paths.items():
            same(
                api.content(path, main if ref == "origin/main" else ref),
                text.encode(),
                "OWNER_MERGE_RECORD",
                "current committed bytes",
            )
            same(
                git(repo, "show", f"{ref}:{path}"),
                text.encode(),
                "OWNER_MERGE_RECORD",
                "local committed bytes",
            )
    source = verify_original_candidate(api, repo, notice, binding.base_sha)
    for actual, wanted in (
        (source.commit, binding.source_sha),
        (source.report.main_commit, binding.base_sha),
        (source.game, binding.game),
        (source.build, binding.build),
        (git(repo, "rev-parse", source.commit + "^{tree}").decode().strip(), binding.source_tree),
    ):
        same(actual, wanted, "OWNER_MERGE_SOURCE", "frozen data identity")
    same(binding.tooling_sha, binding.base_sha, "OWNER_MERGE_SOURCE", "frozen main tooling")
    verify_full_pr_ci(
        api, record.pull_number, source.commit, binding.preparation_ci, review_head_sha=head
    )
    pin = verify_full_pr_ci(api, record.pull_number, head)
    instant = datetime.fromisoformat(str(pull.get("merged_at")).replace("Z", "+00:00"))
    require(
        instant.tzinfo is not None and instant.utcoffset() == timedelta(0),
        "OWNER_MERGE_TIME",
        "UTC merge timestamp",
    )
    return VerifiedOwnerMerge(
        record,
        request,
        binding,
        head,
        tree,
        merge,
        binding.base_sha,
        main,
        "AlexeyKravtsov95",
        5190861,
        instant,
        pin,
    )


def as_gate_approval(verified: VerifiedOwnerMerge) -> ApprovalRecord:
    audit = (
        f"owner merge #{verified.record.pull_number} by {verified.owner_login} "
        f"({verified.owner_id}), merge {verified.merge_sha}, at {verified.merged_at.isoformat()}"
    )
    return ApprovalRecord(
        release_id=verified.request.release_id,
        accepted_gates=frozenset(Gate(g) for g in verified.record.accepted_flags),
        live_build_evidence=None,
        manifest_sha256=verified.request.manifest_sha256,
        merge_authorization_evidence=audit,
    )
