"""Strict binding of unmerged data to trusted, preparation-only producers."""

import json
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Literal, Self

from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.prepare import (
    GitHub,
    PreparationBlocked,
    git,
    obj,
    positive,
    require,
    same,
)
from talent_data.ota.automation.provenance import extract_data_archive
from talent_data.ota.automation.requests import ArtifactPin, RunPin, _Model
from talent_data.ota.models import BUILD_PATTERN, COMMIT_PATTERN, RELEASE_ID_PATTERN, SHA256_PATTERN
from talent_data.ota.verify import OtaError, parse_strict, sha256_ref
from talent_data.prepare.propose import Git, GitError, Refusal, load_candidate


class VersionedModel(_Model):
    schema_version: Literal[1]

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value


class CandidateBinding(VersionedModel):
    kind: Literal["ota-candidate-binding"]
    policy: Literal["owner-merge-v1"]
    repository: Literal["AlexeyKravtsov95/TalentForge"]
    pull_number: int = Field(gt=0)
    game: Literal["forever"]
    build: str = Field(pattern=BUILD_PATTERN)
    revision: int = Field(ge=1)
    source_sha: str = Field(pattern=COMMIT_PATTERN)
    source_tree: str = Field(pattern=COMMIT_PATTERN)
    preparation_head_sha: str = Field(pattern=COMMIT_PATTERN)
    base_sha: str = Field(pattern=COMMIT_PATTERN)
    tooling_sha: str = Field(pattern=COMMIT_PATTERN)
    preparation_ci: RunPin
    candidate_run: RunPin
    staging_run: RunPin
    package_run: RunPin
    candidate_artifact: ArtifactPin
    base_release_id: str = Field(pattern=RELEASE_ID_PATTERN)
    base_manifest_sha256: str = Field(pattern=SHA256_PATTERN)

    @model_validator(mode="after")
    def producer_context(self) -> Self:
        if self.preparation_head_sha != self.source_sha:
            raise ValueError("preparation head must be the frozen candidate source")
        if self.preparation_ci.workflow_path != ".github/workflows/ci.yml":
            raise ValueError("candidate validation must use CI")
        for pin, workflow, sha in (
            (self.candidate_run, ".github/workflows/ota-prepare.yml", self.base_sha),
            (self.staging_run, ".github/workflows/ota-staging-verify.yml", self.tooling_sha),
            (self.package_run, ".github/workflows/ota-release-package.yml", self.tooling_sha),
        ):
            if pin.workflow_path != workflow or pin.head_sha != sha:
                raise ValueError("producer workflow or source/tooling identity differs")
        return self


def binding_digest(binding: CandidateBinding) -> str:
    raw = json.dumps(
        binding.model_dump(mode="json", by_alias=True),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode()
    return sha256_ref(raw)


class CandidateNotice(VersionedModel):
    kind: Literal["ota-candidate-notice"]
    policy: Literal["owner-merge-v1"]
    preparation_run: RunPin
    candidate_artifact: ArtifactPin


def candidate_notice(text: str) -> CandidateNotice:
    marker = "<!-- talentforge:owner-merge-v1"
    require(len(text) <= 100000 and text.count(marker) == 1, "CANDIDATE_POLICY", "ambiguous policy")
    match = re.search(r"<!-- talentforge:owner-merge-v1\n(.+?)\n-->", text, re.S)
    require(match is not None, "CANDIDATE_POLICY", "missing preparation binding")
    assert match is not None
    try:
        return parse_strict(CandidateNotice, match.group(1).encode())
    except OtaError as exc:
        raise PreparationBlocked("CANDIDATE_POLICY", "invalid preparation binding") from exc


def _repositories(record: dict[str, Any], *, code: str) -> None:
    for field in ("repository", "head_repository"):
        same(obj(record.get(field)).get("full_name"), "AlexeyKravtsov95/TalentForge", code, field)


def verify_full_pr_ci(
    api: GitHub, pull_number: int, head_sha: str, pin: RunPin | None = None
) -> RunPin:
    pull = obj(api.repo("GET", f"/pulls/{positive(pull_number)}"))
    head, base = obj(pull.get("head")), obj(pull.get("base"))
    same(head.get("sha"), head_sha, "CANDIDATE_CI", "PR head")
    for ref in (head, base):
        same(
            obj(ref.get("repo")).get("full_name"),
            "AlexeyKravtsov95/TalentForge",
            "CANDIDATE_CI",
            "repository",
        )
    if pin is None:
        listed = api.pages(
            "/actions/workflows/ci.yml/runs",
            "workflow_runs",
            params={"event": "pull_request", "branch": str(head.get("ref"))},
        )
        eligible = [
            r
            for r in listed
            if r.get("head_sha") == head_sha
            or any(
                p.get("number") == pull_number and obj(p.get("head")).get("sha") == head_sha
                for p in r.get("pull_requests", [])
            )
        ]
        require(bool(eligible), "CANDIDATE_CI_PENDING", "no exact PR validation")
        latest = max(eligible, key=lambda r: positive(r.get("id")))
        pin = RunPin(
            workflow_path=".github/workflows/ci.yml",
            run_id=positive(latest.get("id")),
            attempt=positive(latest.get("run_attempt")),
            head_sha=str(latest.get("head_sha")),
        )
    run = api.run(pin.run_id, pin.attempt)
    for field, expected in {
        "id": pin.run_id,
        "run_attempt": pin.attempt,
        "path": ".github/workflows/ci.yml",
        "event": "pull_request",
        "head_sha": pin.head_sha,
        "head_branch": head.get("ref"),
        "status": "completed",
        "conclusion": "success",
    }.items():
        same(run.get(field), expected, "CANDIDATE_CI", field)
    _repositories(run, code="CANDIDATE_CI")
    associated = run.get("pull_requests")
    require(
        isinstance(associated, list) and len(associated) == 1,
        "CANDIDATE_CI",
        "ambiguous PR association",
    )
    assert isinstance(associated, list)
    association = obj(associated[0])
    for actual, expected in (
        (association.get("number"), pull_number),
        (obj(association.get("head")).get("sha"), head_sha),
        (obj(association.get("base")).get("sha"), base.get("sha")),
    ):
        same(actual, expected, "CANDIDATE_CI", "PR association")
    if pin.head_sha != head_sha:
        merge = obj(api.repo("GET", f"/git/commits/{pin.head_sha}"))
        source = obj(api.repo("GET", f"/git/commits/{head_sha}"))
        same(
            [obj(p).get("sha") for p in merge.get("parents", [])],
            [base.get("sha"), head_sha],
            "CANDIDATE_CI",
            "synthetic parents",
        )
        same(
            obj(merge.get("tree")).get("sha"),
            obj(source.get("tree")).get("sha"),
            "CANDIDATE_CI",
            "synthetic tree",
        )
    jobs = api.pages(f"/actions/runs/{pin.run_id}/attempts/{pin.attempt}/jobs", "jobs")
    ids = [positive(j.get("id")) for j in jobs]
    require(len(set(ids)) == len(ids), "CANDIDATE_CI", "duplicate jobs")
    for job in jobs:
        for field, expected in {
            "run_id": pin.run_id,
            "run_attempt": pin.attempt,
            "head_sha": pin.head_sha,
        }.items():
            same(job.get(field), expected, "CANDIDATE_CI", field)
    for name in ("full-validation", "checks"):
        selected = [j for j in jobs if j.get("name") == name]
        require(
            len(selected) == 1
            and selected[0].get("status") == "completed"
            and selected[0].get("conclusion") == "success",
            "CANDIDATE_CI",
            "full PR validation did not pass",
        )
    return pin


@dataclass(frozen=True)
class VerifiedCandidate:
    pull_number: int
    source_sha: str
    source_tree: str
    base_sha: str
    tooling_sha: str
    game: str
    build: str
    base_build: str
    candidate_artifact: ArtifactPin
    candidate_run: RunPin
    ci_pin: RunPin
    verified_ref: str


def verify_candidate(
    api: GitHub, repo: Path, pull_number: int, *, source_sha: str, ci_pin: RunPin | None = None
) -> VerifiedCandidate:
    pull = obj(api.repo("GET", f"/pulls/{positive(pull_number)}"))
    same(pull.get("state"), "open", "CANDIDATE_ORIGIN", "PR state")
    same(pull.get("merged"), False, "CANDIDATE_ORIGIN", "merged")
    actor = obj(pull.get("user"))
    same(actor.get("id"), 351368232, "CANDIDATE_ORIGIN", "App identity")
    same(
        actor.get("login"), "talentforge-ota-alexeykravtsov95[bot]", "CANDIDATE_ORIGIN", "App login"
    )
    head, base = obj(pull.get("head")), obj(pull.get("base"))
    for ref in (head, base):
        same(
            obj(ref.get("repo")).get("full_name"),
            "AlexeyKravtsov95/TalentForge",
            "CANDIDATE_ORIGIN",
            "repository",
        )
    same(head.get("sha"), source_sha, "CANDIDATE_ORIGIN", "source head")
    same(base.get("ref"), "main", "CANDIDATE_ORIGIN", "base ref")
    tooling = git(repo, "rev-parse", "HEAD").decode().strip()
    same(
        git(repo, "rev-parse", "origin/main").decode().strip(),
        tooling,
        "CANDIDATE_ORIGIN",
        "trusted checkout",
    )
    same(base.get("sha"), tooling, "CANDIDATE_ORIGIN", "current base")
    pulls = api.pages(
        "/pulls", params={"head": "AlexeyKravtsov95:" + str(head.get("ref")), "state": "all"}
    )
    require(
        len(pulls) == 1 and pulls[0].get("number") == pull_number,
        "CANDIDATE_ORIGIN",
        "ambiguous candidate MR",
    )
    notice = candidate_notice(str(pull.get("body", "")))
    prep = notice.preparation_run
    same(
        prep.workflow_path,
        ".github/workflows/ota-prepare.yml",
        "CANDIDATE_ORIGIN",
        "preparation workflow",
    )
    same(prep.head_sha, tooling, "CANDIDATE_ORIGIN", "preparation main")
    run = api.run(prep.run_id, prep.attempt)
    for field, expected in {
        "id": prep.run_id,
        "run_attempt": prep.attempt,
        "head_sha": prep.head_sha,
        "head_branch": "main",
        "path": prep.workflow_path,
        "status": "completed",
        "conclusion": "success",
    }.items():
        same(run.get(field), expected, "CANDIDATE_ORIGIN", field)
    require(
        run.get("event") in {"workflow_run", "workflow_dispatch"},
        "CANDIDATE_ORIGIN",
        "preparation event",
    )
    _repositories(run, code="CANDIDATE_ORIGIN")
    prep_jobs = api.pages(f"/actions/runs/{prep.run_id}/attempts/{prep.attempt}/jobs", "jobs")
    for job in prep_jobs:
        for field, expected in {
            "run_id": prep.run_id,
            "run_attempt": prep.attempt,
            "head_sha": prep.head_sha,
        }.items():
            same(job.get(field), expected, "CANDIDATE_ORIGIN", field)
    require(
        len({positive(j.get("id")) for j in prep_jobs}) == len(prep_jobs),
        "CANDIDATE_ORIGIN",
        "duplicate preparation jobs",
    )
    for name in ("prepare", "propose"):
        selected = [j for j in prep_jobs if j.get("name") == name]
        require(
            len(selected) == 1
            and selected[0].get("status") == "completed"
            and selected[0].get("conclusion") == "success",
            "CANDIDATE_ORIGIN",
            "preparation did not complete",
        )
    artifact = obj(api.repo("GET", f"/actions/artifacts/{notice.candidate_artifact.artifact_id}"))
    for field, expected in {
        "id": notice.candidate_artifact.artifact_id,
        "name": notice.candidate_artifact.name,
        "digest": notice.candidate_artifact.archive_sha256,
        "expired": False,
    }.items():
        same(artifact.get(field), expected, "CANDIDATE_ARTIFACT", field)
    origin = obj(artifact.get("workflow_run"))
    rid = positive(obj(run.get("repository")).get("id"))
    for field, expected in {
        "id": prep.run_id,
        "head_sha": tooling,
        "head_branch": "main",
        "repository_id": rid,
        "head_repository_id": rid,
    }.items():
        same(origin.get(field), expected, "CANDIDATE_ARTIFACT", field)
    try:
        stamps = [
            datetime.fromisoformat(str(v))
            for v in (run.get("run_started_at"), artifact.get("created_at"), run.get("updated_at"))
        ]
        require(
            all(s.tzinfo is not None and s.utcoffset() == timedelta(0) for s in stamps)
            and stamps[0] <= stamps[1] <= stamps[2],
            "CANDIDATE_ARTIFACT",
            "artifact attempt interval",
        )
    except ValueError as exc:
        raise PreparationBlocked("CANDIDATE_ARTIFACT", "invalid artifact timestamps") from exc
    raw = api.archive(artifact)
    same(
        sha256_ref(raw),
        notice.candidate_artifact.archive_sha256,
        "CANDIDATE_ARTIFACT",
        "archive hash",
    )
    try:
        with tempfile.TemporaryDirectory(prefix="ota-candidate-") as temp:
            directory = extract_data_archive(raw, Path(temp).resolve() / "candidate")
            loaded = load_candidate(
                directory,
                Git(repo),
                base_ref="origin/main",
                expected=notice.candidate_artifact.name,
            )
    except (Refusal, GitError, OSError) as exc:
        raise PreparationBlocked("CANDIDATE_ORIGIN", "candidate artifact/source refused") from exc
    same(loaded.commit, source_sha, "CANDIDATE_ORIGIN", "artifact source")
    same(loaded.report.main_commit, tooling, "CANDIDATE_ORIGIN", "artifact base")
    same(
        head.get("ref"), f"ota-candidate/{loaded.game}-{loaded.build}", "CANDIDATE_ORIGIN", "branch"
    )
    tree = git(repo, "rev-parse", source_sha + "^{tree}").decode().strip()
    remote_source = obj(api.repo("GET", f"/git/commits/{source_sha}"))
    same(obj(remote_source.get("tree")).get("sha"), tree, "CANDIDATE_ORIGIN", "source tree")
    checked_ci = verify_full_pr_ci(api, pull_number, source_sha, ci_pin)
    latest = obj(api.repo("GET", f"/pulls/{pull_number}"))
    require(
        latest.get("state") == "open"
        and obj(latest.get("head")).get("sha") == source_sha
        and obj(latest.get("base")).get("sha") == tooling,
        "CANDIDATE_CHANGED",
        "MR changed during verification",
    )
    verified_ref = f"refs/talentforge/candidate/{pull_number}/{source_sha}"
    git(repo, "update-ref", verified_ref, source_sha)
    return VerifiedCandidate(
        pull_number,
        source_sha,
        tree,
        tooling,
        tooling,
        loaded.game,
        loaded.build,
        loaded.base,
        notice.candidate_artifact,
        prep,
        checked_ci,
        verified_ref,
    )
