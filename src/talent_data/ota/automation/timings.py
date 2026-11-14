"""Strict nonsecret delivery evidence, with approval time taken only from GitHub."""

from __future__ import annotations

import json
import re
from collections.abc import Callable, Iterator, Mapping, Sequence
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from threading import Lock
from typing import TYPE_CHECKING, Annotated, Literal, Self

from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.prepare import (
    CI_WORKFLOW,
    GitHub,
    PreparationBlocked,
    git,
    positive,
    verify_ci,
)
from talent_data.ota.automation.public_verify import PublicVerification
from talent_data.ota.automation.requests import ReleaseRequest, RunPin, _Model, parse_request
from talent_data.ota.io_metrics import Operation, Phase
from talent_data.ota.models import COMMIT_PATTERN, RELEASE_ID_PATTERN, SHA256_PATTERN
from talent_data.ota.release_package import (
    APPROVALS_DIR,
    approval_record_at,
    approval_record_problems,
)
from talent_data.ota.s3_store import S3Failure
from talent_data.ota.verify import parse_strict, sha256_ref

if TYPE_CHECKING:
    from talent_data.ota.automation.merge_approval import VerifiedOwnerMerge

Digest = Annotated[str, Field(pattern=SHA256_PATTERN)]
Commit = Annotated[str, Field(pattern=COMMIT_PATTERN)]
Seconds = Annotated[float, Field(ge=0, allow_inf_nan=False)]
Count = Annotated[int, Field(ge=0)]
Slo = Literal["PASS", "MISS", "UNKNOWN", "PENDING"]


class _TimedModel(_Model):
    @field_validator("*")
    @classmethod
    def utc_timestamps(cls, value: object) -> object:
        if isinstance(value, datetime) and value.utcoffset() != timedelta(0):
            raise ValueError("timing timestamps must be UTC")
        return value


class ApprovalEpoch(_TimedModel):
    epoch_id: Digest
    request_digest: Digest
    approval_digest: Digest | None
    approval_change_sha: Commit | None
    epoch_start_sha: Commit | None
    approved_at: datetime | None
    time_source: Literal["merged_at", "ci_created_at"] | None

    @field_validator("approved_at")
    @classmethod
    def utc(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.utcoffset() != timedelta(0):
            raise ValueError("approval time must be UTC")
        return value

    @model_validator(mode="after")
    def source_pair(self) -> Self:
        if (self.approved_at is None) != (self.time_source is None):
            raise ValueError("partial approval time evidence")
        if self.approved_at is not None and self.epoch_start_sha is None:
            raise ValueError("approval time requires an epoch commit")
        return self


class DeliveryIdentity(_TimedModel):
    request: ReleaseRequest
    request_digest: Digest
    approval_epoch: ApprovalEpoch
    main_sha: Commit
    trigger_ci: RunPin | None
    request_ci: RunPin | None

    @model_validator(mode="after")
    def request_binding(self) -> Self:
        digest = sha256_ref(self.request.model_dump_json(by_alias=True).encode())
        if digest != self.request_digest or self.approval_epoch.request_digest != digest:
            raise ValueError("timing request identity mismatch")
        return self


class PhaseTiming(_TimedModel):
    name: Phase
    source: Literal["local", "trigger-ci", "request-ci"]
    started_at: datetime
    ended_at: datetime
    elapsed_seconds: Seconds
    ok: bool


class IoCounters(_TimedModel):
    scope: Literal["ota-objects"]
    get_requests: Count
    head_requests: Count
    put_requests: Count
    transferred_bytes: Count
    retries: Count
    unique_hashes: Count
    complete: bool


class ProofSummary(_TimedModel):
    verified: bool
    payload_sha256: Digest | None
    active_release_id: str | None = Field(pattern=RELEASE_ID_PATTERN)
    sequence: int | None = Field(gt=0)


class DeliveryFailure(_TimedModel):
    stage: Phase
    reason: Literal[
        "UPLOAD_FAILED",
        "SIGN_FAILED",
        "SIGNER_FAILED",
        "CHECK_FAILED",
        "OUTCOME_UNKNOWN",
        "PUBLIC_NOT_VERIFIED",
        "REQUEST_AUTHORIZATION_FAILED",
        "PACKAGE_PROVENANCE_FAILED",
        "ASSESSMENT_FAILED",
        "CONFIG_MISSING",
        "INVOCATION_REFUSED",
        "JOURNAL_FAILED",
        "PUBLIC_CATALOG_UNVERIFIED",
        "PUBLICATION_BLOCKED",
        "RENEWAL_BLOCKED",
        "CLEANUP_FAILED",
    ]
    kind: S3Failure | None
    operation: Literal["get_object", "head_object", "put_object", "delete_object"] | None
    object_hash: Digest | None
    http_status: int | None = Field(ge=100, le=599)
    attempt: int | None = Field(gt=0)
    elapsed_seconds: Seconds | None


class DeliveryTimings(_TimedModel):
    schema_version: Literal[1]
    kind: Literal["delivery-timings.v1"]
    identity: DeliveryIdentity
    input_fingerprint: Digest
    phases: tuple[PhaseTiming, ...]
    io: IoCounters
    ci_metadata_complete: bool
    expected_payload_sha256: Digest | None
    proof: ProofSummary | None
    verified_at: datetime | None
    elapsed_seconds: Seconds | None
    package_age_seconds: Seconds | None
    slo: Slo
    failure: DeliveryFailure | None

    @field_validator("schema_version", mode="before")
    @classmethod
    def version_type(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @model_validator(mode="after")
    def honest_outcome(self) -> Self:
        fingerprint = delivery_input_fingerprint(
            self.identity.request_digest,
            self.identity.approval_epoch.approval_digest or sha256_ref(b""),
            self.identity.main_sha,
            (self.identity.request.artifact.artifact_id,),
        )
        if self.input_fingerprint != fingerprint:
            raise ValueError("timing fingerprint does not match its identity")
        proven = (
            self.proof is not None
            and self.proof.verified
            and self.expected_payload_sha256 is not None
            and self.expected_payload_sha256 == self.proof.payload_sha256
            and self.proof.active_release_id == self.identity.request.release_id
            and self.verified_at is not None
        )
        if not proven:
            if (
                self.slo != "PENDING"
                or self.verified_at is not None
                or self.elapsed_seconds is not None
            ):
                raise ValueError("latency result without complete expected public proof")
            return self
        approved = self.identity.approval_epoch.approved_at
        assert self.verified_at is not None  # proven above
        elapsed = (self.verified_at - approved).total_seconds() if approved is not None else None
        if elapsed is None or elapsed < 0:
            if self.slo != "UNKNOWN" or self.elapsed_seconds is not None:
                raise ValueError("unknown approval clock cannot prove latency")
        elif self.elapsed_seconds != elapsed or self.slo != ("PASS" if elapsed <= 600 else "MISS"):
            raise ValueError("incorrect approval-to-proof latency")
        return self


def read_timings(raw: bytes) -> DeliveryTimings:
    return parse_strict(DeliveryTimings, raw)


def delivery_input_fingerprint(
    request_digest: str, approval_digest: str, code_sha: str, artifact_ids: tuple[int, ...]
) -> str:
    if not all(re.fullmatch(SHA256_PATTERN, value) for value in (request_digest, approval_digest)):
        raise ValueError("invalid fingerprint digests")
    if re.fullmatch(COMMIT_PATTERN, code_sha) is None or any(
        type(n) is not int or n <= 0 for n in artifact_ids
    ):
        raise ValueError("invalid fingerprint identity")
    return sha256_ref(
        json.dumps(
            {
                "request": request_digest,
                "approval": approval_digest,
                "code": code_sha,
                "artifacts": sorted(set(artifact_ids)),
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
    )


def _utc(raw: object) -> datetime:
    if not isinstance(raw, str):
        raise TypeError("server timestamp missing")
    value = datetime.fromisoformat(raw)
    if value.utcoffset() != timedelta(0):
        raise ValueError("server timestamp must be UTC")
    return value


def resolve_approval_epoch(
    api: GitHub, repo: Path, request: ReleaseRequest, *, allow_direct_push: bool = False
) -> ApprovalEpoch:
    digest = sha256_ref(request.model_dump_json(by_alias=True).encode())
    path = f"docs/ota/release-requests/{request.release_id}.json"
    approval_path = f"{APPROVALS_DIR}/{request.release_id}.md"
    current_approval = approval_record_at(repo, "origin/main", request.release_id)
    from talent_data.ota.automation.merge_approval import parse_merge_record, verify_owner_merge

    conditional = (
        parse_merge_record(current_approval.decode("utf-8", "strict")) if current_approval else None
    )
    if conditional is not None:
        start = None
        approved_at = None
        try:
            from talent_data.ota.automation.ci_validation import load_merge_binding

            binding = load_merge_binding(api, request)
            verified = verify_owner_merge(api, repo, request, conditional, binding=binding)
            start, approved_at = verified.merge_sha, verified.merged_at
        except (PreparationBlocked, ValueError, OSError, KeyError, TypeError):
            pass
        return ApprovalEpoch(
            epoch_id=sha256_ref(json.dumps([digest, start], separators=(",", ":")).encode()),
            request_digest=digest,
            approval_digest=sha256_ref(current_approval) if current_approval else None,
            approval_change_sha=start,
            epoch_start_sha=start,
            approved_at=approved_at,
            time_source="merged_at" if approved_at is not None else None,
        )
    changes = (
        git(
            repo,
            "log",
            "--first-parent",
            "--full-history",
            "--reverse",
            "--format=%H",
            "origin/main",
            "--",
            path,
            approval_path,
        )
        .decode("ascii")
        .splitlines()
    )
    start = latest = None
    approval: bytes | None = None
    for commit in changes:
        latest = commit
        paths = git(repo, "ls-tree", "-r", "--name-only", commit, "--", path).decode().splitlines()
        approval = approval_record_at(repo, commit, request.release_id)
        valid = False
        if path in paths and approval is not None:
            try:
                valid = parse_request(
                    git(repo, "show", f"{commit}:{path}")
                ) == request and not approval_record_problems(
                    approval.decode("utf-8", "strict"),
                    release_id=request.release_id,
                    manifest_sha256=request.manifest_sha256,
                    source_commit=request.source_commit,
                )
            except (ValueError, UnicodeError):
                valid = False
        start = (start or commit) if valid else None
    approved_at = None
    source: Literal["merged_at", "ci_created_at"] | None = None
    if start is not None:
        try:
            pulls = api.pages(f"/commits/{start}/pulls")
            matched: dict[int, datetime] = {}
            for summary in pulls:
                number = positive(summary.get("number"))
                pr = api.repo("GET", f"/pulls/{number}")
                base = pr.get("base", {})
                if (
                    pr.get("merged") is True
                    and pr.get("merge_commit_sha") == start
                    and base.get("ref") == "main"
                    and base.get("repo", {}).get("full_name") == request.repository
                ):
                    matched[number] = _utc(pr.get("merged_at"))
            if len(matched) == 1:
                approved_at = next(iter(matched.values()))
                source = "merged_at"
            elif not matched and allow_direct_push:
                runs = api.pages(
                    f"/actions/workflows/{Path(CI_WORKFLOW).name}/runs",
                    "workflow_runs",
                    params={"event": "push", "branch": "main", "head_sha": start},
                )
                candidates = []
                for record in runs:
                    run_id, attempt = positive(record["id"]), positive(record["run_attempt"])
                    run = api.run(run_id, attempt)
                    jobs = api.pages(f"/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
                    if verify_ci(run, jobs, run_id=run_id, attempt=attempt) == start:
                        candidates.append(_utc(run.get("created_at")))
                if candidates:
                    approved_at, source = min(candidates), "ci_created_at"
        except (PreparationBlocked, ValueError, KeyError, TypeError, AttributeError):
            approved_at = source = None
    return ApprovalEpoch(
        epoch_id=sha256_ref(json.dumps([digest, start], separators=(",", ":")).encode()),
        request_digest=digest,
        approval_digest=sha256_ref(approval) if approval is not None else None,
        approval_change_sha=latest,
        epoch_start_sha=start,
        approved_at=approved_at,
        time_source=source,
    )


class DeliveryRecorder:
    def __init__(
        self, identity: DeliveryIdentity, *, clock: Callable[[], float], now: Callable[[], datetime]
    ) -> None:
        self.identity, self._clock, self._now = identity, clock, now
        self._lock = Lock()
        self._counts = {"GET": 0, "HEAD": 0, "PUT": 0}
        self._bytes = self._retries = 0
        self._hashes: set[str] = set()
        self._complete = True
        self._phases: list[PhaseTiming] = []
        self.failure: DeliveryFailure | None = None
        self._ci_sources: set[str] = set()

    def record_ci(
        self,
        pin: RunPin,
        run: Mapping[str, object],
        jobs: Sequence[Mapping[str, object]],
        *,
        source: Literal["trigger-ci", "request-ci"],
    ) -> None:
        self.identity = self.identity.model_copy(
            update={"trigger_ci" if source == "trigger-ci" else "request_ci": pin}
        )
        with self._lock:
            self._ci_sources.discard(source)
            self._phases = [item for item in self._phases if item.source != source]
        try:
            created, started = _utc(run.get("created_at")), _utc(run.get("run_started_at"))
            ended = max(
                _utc(job.get("completed_at")) for job in jobs if job.get("conclusion") != "skipped"
            )
            if ended < started or started < created:
                return
            phases = (
                PhaseTiming(
                    name="QUEUE",
                    source=source,
                    started_at=created,
                    ended_at=started,
                    elapsed_seconds=(started - created).total_seconds(),
                    ok=True,
                ),
                PhaseTiming(
                    name="CI",
                    source=source,
                    started_at=started,
                    ended_at=ended,
                    elapsed_seconds=(ended - started).total_seconds(),
                    ok=True,
                ),
            )
        except (ValueError, TypeError):
            return  # Missing server timestamps are unavailable, never fabricated as zero.
        with self._lock:
            self._ci_sources.add(source)
            self._phases.extend(phases)

    def record_failure(
        self, diagnostic: Mapping[str, str | int | float | None], stage: Phase
    ) -> None:
        raw = {
            **diagnostic,
            "stage": stage,
            "reason": "UPLOAD_FAILED" if stage == "UPLOAD" else "CHECK_FAILED",
        }
        self.failure = parse_strict(DeliveryFailure, json.dumps(raw).encode())

    @contextmanager
    def phase(self, name: Phase) -> Iterator[None]:
        start, stamp, ok = self._clock(), self._now(), False
        try:
            yield
            ok = True
        finally:
            item = PhaseTiming(
                name=name,
                source="local",
                started_at=stamp,
                ended_at=self._now(),
                elapsed_seconds=max(0.0, self._clock() - start),
                ok=ok,
            )
            self.append_phase(item)

    def append_phase(self, timing: PhaseTiming) -> None:
        with self._lock:
            self._phases.append(timing)

    def count(
        self, operation: Operation, *, bytes: int, retry: bool = False, sha_ref: str | None = None
    ) -> None:
        if (
            operation not in self._counts
            or type(bytes) is not int
            or bytes < 0
            or type(retry) is not bool
        ):
            raise ValueError("invalid I/O counter")
        if sha_ref is not None and re.fullmatch(SHA256_PATTERN, sha_ref) is None:
            raise ValueError("invalid object hash")
        with self._lock:
            self._counts[operation] += 1
            self._bytes += bytes
            self._retries += int(retry)
            if sha_ref is not None:
                self._hashes.add(sha_ref)

    def add_bytes(self, amount: int) -> None:
        if type(amount) is not int or amount < 0:
            raise ValueError("invalid byte count")
        with self._lock:
            self._bytes += amount

    def incomplete(self) -> None:
        with self._lock:
            self._complete = False

    def finish(
        self,
        *,
        verified_at: datetime | None,
        expected_payload_sha256: str | None,
        proof: PublicVerification | None,
    ) -> DeliveryTimings:
        good = (
            proof is not None
            and proof.ok
            and expected_payload_sha256 is not None
            and proof.catalog_payload_sha256 == expected_payload_sha256
            and proof.active_release_id == self.identity.request.release_id
            and verified_at is not None
        )
        if not good:
            verified_at = None
        approved = self.identity.approval_epoch.approved_at
        elapsed = (
            (verified_at - approved).total_seconds()
            if verified_at is not None and approved is not None
            else None
        )
        if elapsed is not None and elapsed < 0:
            elapsed = None
        slo: Slo = (
            "PENDING"
            if verified_at is None
            else "UNKNOWN"
            if elapsed is None
            else "PASS"
            if elapsed <= 600
            else "MISS"
        )
        age = (
            self._now() - datetime.fromisoformat(self.identity.request.created_at)
        ).total_seconds()
        with self._lock:
            counters = IoCounters(
                scope="ota-objects",
                get_requests=self._counts["GET"],
                head_requests=self._counts["HEAD"],
                put_requests=self._counts["PUT"],
                transferred_bytes=self._bytes,
                retries=self._retries,
                unique_hashes=len(self._hashes),
                complete=self._complete,
            )
            phases = tuple(self._phases)
            ci_complete = {"trigger-ci", "request-ci"} <= self._ci_sources
        return DeliveryTimings(
            schema_version=1,
            kind="delivery-timings.v1",
            identity=self.identity,
            input_fingerprint=delivery_input_fingerprint(
                self.identity.request_digest,
                self.identity.approval_epoch.approval_digest or sha256_ref(b""),
                self.identity.main_sha,
                (self.identity.request.artifact.artifact_id,),
            ),
            phases=phases,
            io=counters,
            ci_metadata_complete=ci_complete,
            expected_payload_sha256=expected_payload_sha256,
            proof=ProofSummary(
                verified=bool(good),
                payload_sha256=proof.catalog_payload_sha256,
                active_release_id=proof.active_release_id,
                sequence=proof.sequence,
            )
            if proof is not None
            else None,
            verified_at=verified_at,
            elapsed_seconds=elapsed,
            package_age_seconds=age if age >= 0 else None,
            slo=slo,
            failure=self.failure,
        )


class OwnerMergeTimeline(_TimedModel):
    """Separate internal evidence; existing delivery-timings.v1/journal stay unchanged."""

    schema_version: Literal[1]
    kind: Literal["owner-merge-timeline.v1"]
    release_id: str
    request_digest: Digest
    pull_number: int = Field(gt=0)
    source_sha: Commit
    tooling_sha: Commit
    final_head_sha: Commit
    merge_sha: Commit
    candidate_at: datetime | None
    staging_at: datetime | None
    package_at: datetime | None
    final_ci_at: datetime | None
    ready_at: datetime | None
    merged_at: datetime
    verified_at: datetime | None
    candidate_to_ready_seconds: Seconds | None
    ready_to_merge_seconds: Seconds | None
    merge_to_verified_seconds: Seconds | None
    slo: Slo

    @field_validator("schema_version", mode="before")
    @classmethod
    def version_type(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value


def owner_merge_timeline(
    api: GitHub, verified: VerifiedOwnerMerge, *, verified_at: datetime | None = None
) -> OwnerMergeTimeline:
    """Read actual server events. Missing readiness is unknown, never a CI timestamp."""
    pull = api.repo("GET", f"/pulls/{verified.record.pull_number}")

    def stamp(value: object) -> datetime | None:
        try:
            return _utc(value)
        except (TypeError, ValueError):
            return None

    candidate_at = stamp(pull.get("created_at"))
    times = []
    for pin in (verified.binding.staging_run, verified.binding.package_run, verified.pr_ci):
        times.append(stamp(api.run(pin.run_id, pin.attempt).get("updated_at")))
    events = api.pages(f"/issues/{verified.record.pull_number}/timeline")
    # GitHub issue-event-types: ready_for_review.created_at is the server event time.
    ready = [
        stamp(e.get("created_at"))
        for e in events
        if e.get("event") == "ready_for_review" and e.get("actor", {}).get("id") == 351368232
    ]
    eligible = [
        t
        for t in ready
        if t is not None and t <= verified.merged_at and (times[2] is None or t >= times[2])
    ]
    ready_at = max(eligible) if eligible else None

    def elapsed(start: datetime | None, end: datetime | None) -> float | None:
        if start is None or end is None:
            return None
        value = (end - start).total_seconds()
        if value < 0:
            raise ValueError("server timeline is out of order")
        return value

    seconds = elapsed(verified.merged_at, verified_at)
    return OwnerMergeTimeline(
        schema_version=1,
        kind="owner-merge-timeline.v1",
        release_id=verified.request.release_id,
        request_digest=sha256_ref(verified.request.model_dump_json(by_alias=True).encode()),
        pull_number=verified.record.pull_number,
        source_sha=verified.request.source_commit,
        tooling_sha=verified.binding.tooling_sha,
        final_head_sha=verified.final_head_sha,
        merge_sha=verified.merge_sha,
        candidate_at=candidate_at,
        staging_at=times[0],
        package_at=times[1],
        final_ci_at=times[2],
        ready_at=ready_at,
        merged_at=verified.merged_at,
        verified_at=verified_at,
        candidate_to_ready_seconds=elapsed(candidate_at, ready_at),
        ready_to_merge_seconds=elapsed(ready_at, verified.merged_at),
        merge_to_verified_seconds=seconds,
        slo="PENDING" if seconds is None else ("PASS" if seconds <= 600 else "MISS"),
    )
