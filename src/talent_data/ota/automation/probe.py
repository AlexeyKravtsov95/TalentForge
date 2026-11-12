"""Read-only delivery selection. A suggestion never authorizes a catalog write."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import stat
import zipfile
import zlib
from collections.abc import Callable, Mapping
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import TYPE_CHECKING, Any, Literal, Self, cast

import httpx
from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.authorization import (
    authorize_request_ci,
    committed_requests,
    current_main_ci,
    request_ci_pin,
    verify_invocation,
)
from talent_data.ota.automation.prepare import (
    GitHub,
    PreparationBlocked,
    git,
    obj,
    positive,
    require,
    same,
)
from talent_data.ota.automation.provenance import (
    PACKAGE_WORKFLOW,
    REPOSITORY,
    TRUSTED_REPO,
    RequestRejected,
    _check_zip_directory,
    _run,
)
from talent_data.ota.automation.reconcile import _stale_reason
from talent_data.ota.automation.requests import ReleaseRequest
from talent_data.ota.automation.state import (
    TERMINAL,
    GitJournal,
    Journal,
    JournalEntry,
    JournalError,
    PublicationReceipt,
    RequestState,
    request_digest,
)
from talent_data.ota.automation.timings import (
    Commit,
    Digest,
    _TimedModel,
    _utc,
    delivery_input_fingerprint,
    read_timings,
)
from talent_data.ota.models import RELEASE_ID_PATTERN, OtaCatalog
from talent_data.ota.preflight import production_keyring
from talent_data.ota.release_package import (
    STAGING_WORKFLOW,
    approval_record_at,
    approval_record_problems,
)
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.ota.verify import OtaError, parse_strict, sha256_ref, strict_json
from talent_data.prepare.production import read_production

if TYPE_CHECKING:
    from talent_data.ota.automation.cli import Dependencies

WORKFLOW = ".github/workflows/ota-production-publish.yml"
MAX_HISTORY_ZIP = 2 * 1024 * 1024
MAX_HISTORY_JSON = 1024 * 1024
Action = Literal["NO_WORK", "PUBLISH", "VERIFY", "RECOVER", "WAIT", "BLOCKED"]
Reason = Literal[
    "IDLE",
    "READY",
    "PUBLIC_PROOF_PENDING",
    "OUTCOME_UNKNOWN",
    "CI_PENDING",
    "APPROVAL_PENDING",
    "INPUT_INVALID",
    "JOURNAL_UNAVAILABLE",
    "CURRENT_MAIN_UNPROVEN",
    "HISTORY_UNAVAILABLE",
    "COOLDOWN",
    "RETRY_LIMIT",
    "PERMANENT_FAILURE",
    "RUN_PENDING",
    "DISABLED",
    "INVOCATION_REFUSED",
    "BASE_NOT_CURRENT",
]


class ProbeCandidate(_TimedModel):
    action: Literal["PUBLISH", "VERIFY", "RECOVER"]
    release_id: str | None = Field(pattern=RELEASE_ID_PATTERN)
    request_digest: Digest
    input_fingerprint: Digest


class ExecutionProgress(_TimedModel):
    schema_version: Literal[1]
    kind: Literal["ota-batch-execution"]
    main_sha: Commit
    input_sha256: Digest
    started: tuple[Digest, ...]

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value


def execution_progress(plan_raw: bytes, progress_raw: bytes) -> ExecutionProgress:
    plan = read_probe(plan_raw)
    progress = parse_strict(ExecutionProgress, progress_raw)
    same(progress.main_sha, plan.main_sha, "HISTORY_UNAVAILABLE", "progress tooling")
    same(progress.input_sha256, sha256_ref(plan_raw), "HISTORY_UNAVAILABLE", "progress input")
    same(
        progress.started,
        plan.request_digests[: len(progress.started)],
        "HISTORY_UNAVAILABLE",
        "started target order",
    )
    return progress


class _HistoryReads(GitHub):
    """One probe's shared, worst-case 48-request history budget. Never a writer cache."""

    def __init__(self, api: GitHub):
        self.api, self.remaining = api, 48
        self.cache: dict[tuple[object, ...], Any] = {}

    def _read[T](self, key: tuple[object, ...], cost: int, read: Callable[[], T]) -> T:
        if key not in self.cache:
            require(self.remaining >= cost, "HISTORY_BUDGET", "complete history exceeds budget")
            self.remaining -= cost
            self.cache[key] = read()
        return cast(T, self.cache[key])

    def pages(
        self,
        path: str,
        member: str | None = None,
        *,
        params: Mapping[str, str | int] | None = None,
        max_pages: int = 200,
    ) -> list[dict[str, Any]]:
        bound = min(3, max_pages)
        return self._read(
            ("pages", path, member, json.dumps(params, sort_keys=True)),
            bound,
            lambda: self.api.pages(path, member, params=params, max_pages=bound),
        )

    def run(self, run_id: int, attempt: int) -> dict[str, Any]:
        return self._read(("run", run_id, attempt), 1, lambda: self.api.run(run_id, attempt))

    def repo(self, method: str, path: str, **kwargs: Any) -> Any:
        require(method == "GET", "HISTORY_METHOD", "history is read-only")
        return self._read(
            (method, path, json.dumps(kwargs, sort_keys=True)),
            1,
            lambda: self.api.repo(method, path, **kwargs),
        )

    def archive(self, artifact: dict[str, Any], *, max_bytes: int | None = None) -> bytes:
        bound = MAX_HISTORY_ZIP if max_bytes is None else min(max_bytes, MAX_HISTORY_ZIP)
        return self._read(
            ("archive", positive(artifact.get("id")), artifact.get("digest")),
            2,
            lambda: self.api.archive(artifact, max_bytes=bound),
        )


class ProbeResult(_TimedModel):
    schema_version: Literal[1]
    kind: Literal["ota-delivery-probe"]
    action: Action
    release_ids: tuple[str, ...]
    request_digests: tuple[Digest, ...]
    candidates: tuple[ProbeCandidate, ...]
    reason: Reason
    next_eligible_at: datetime | None
    input_fingerprint: Digest
    main_sha: Commit | None

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @model_validator(mode="after")
    def bindings(self) -> Self:
        if len(set(self.request_digests)) != len(self.request_digests):
            raise ValueError("duplicate probe target")
        if self.request_digests != tuple(c.request_digest for c in self.candidates):
            raise ValueError("probe digest order differs")
        if self.release_ids != tuple(c.release_id for c in self.candidates if c.release_id):
            raise ValueError("probe release order differs")
        actions = {c.action for c in self.candidates}
        if self.action in {"PUBLISH", "VERIFY", "RECOVER"}:
            if self.action not in actions or ("RECOVER" in actions and actions != {"RECOVER"}):
                raise ValueError("invalid probe actions")
            if self.action == "VERIFY" and "PUBLISH" in actions:
                raise ValueError("ready delivery must precede verification retries")
        elif self.candidates:
            raise ValueError("non-work result has candidates")
        if self.action != "WAIT" and self.next_eligible_at is not None:
            raise ValueError("unexpected eligibility timestamp")
        return self


def read_probe(raw: bytes) -> ProbeResult:
    return parse_strict(ProbeResult, raw)


def _result(
    action: Action,
    reason: Reason,
    *,
    head: str | None = None,
    candidates: tuple[ProbeCandidate, ...] = (),
    next_at: datetime | None = None,
) -> ProbeResult:
    return ProbeResult(
        schema_version=1,
        kind="ota-delivery-probe",
        action=action,
        release_ids=tuple(c.release_id for c in candidates if c.release_id),
        request_digests=tuple(c.request_digest for c in candidates),
        candidates=candidates,
        reason=reason,
        next_eligible_at=next_at,
        main_sha=head,
        input_fingerprint=sha256_ref(
            json.dumps(
                [head, [c.model_dump(mode="json") for c in candidates]],
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
        ),
    )


def _retained(
    entry: JournalEntry, action: Literal["VERIFY", "RECOVER"], head: str
) -> ProbeCandidate:
    receipt = entry.receipt
    return ProbeCandidate(
        action=action,
        release_id=receipt.request.release_id if isinstance(receipt, PublicationReceipt) else None,
        request_digest=receipt.request_digest,
        input_fingerprint=sha256_ref(
            json.dumps([head, receipt.model_dump(mode="json")], sort_keys=True).encode()
        ),
    )


def _package_metadata(api: GitHub, request: ReleaseRequest) -> None:
    same(request.repository, REPOSITORY, "REQUEST_REPOSITORY", "repository")
    same(request.game, "forever", "REQUEST_GAME", "game")
    package = _run(
        api.run(request.package_run.run_id, request.package_run.attempt),
        request.package_run,
        PACKAGE_WORKFLOW,
    )
    _run(
        api.run(request.staging_run.run_id, request.staging_run.attempt),
        request.staging_run,
        STAGING_WORKFLOW,
    )
    artifact = obj(api.repo("GET", f"/actions/artifacts/{request.artifact.artifact_id}"))
    for key, expected in {
        "id": request.artifact.artifact_id,
        "name": request.artifact.name,
        "digest": request.artifact.archive_sha256,
        "expired": False,
    }.items():
        same(artifact.get(key), expected, "ARTIFACT_ORIGIN", key)
    repo_id = positive(obj(package.get("repository")).get("id"))
    attached = obj(artifact.get("workflow_run"))
    for key, expected in {
        "id": request.package_run.run_id,
        "repository_id": repo_id,
        "head_repository_id": repo_id,
        "head_branch": "main",
        "head_sha": request.package_run.head_sha,
    }.items():
        same(attached.get(key), expected, "ARTIFACT_ORIGIN", key)
    require(
        _utc(package.get("run_started_at"))
        <= _utc(artifact.get("created_at"))
        <= _utc(package.get("updated_at")),
        "ARTIFACT_ATTEMPT",
        "artifact outside attempt",
    )


def _public_catalog(api: GitHub, repo: Path, now: datetime) -> OtaCatalog | None:
    """A catalog-only hint can suppress heavy work, never prove publication or STALE."""
    state = read_production(
        api.client,
        origin=PRODUCTION_PROFILE.public_origin,
        keyring=production_keyring(repo / "docs/ota/keys/production-keyring.json"),
        game="forever",
        now=now,
    )
    return state.catalog


def _history_document(
    api: GitHub,
    listed: list[dict[str, object]],
    *,
    name: str,
    filename: str,
    run: dict[str, object],
    digest: str,
) -> bytes | None:
    selected = [a for a in listed if a.get("name") == name]
    require(len(selected) == 1, "HISTORY_UNAVAILABLE", "missing or duplicate history artifact")
    artifact = obj(api.repo("GET", f"/actions/artifacts/{positive(selected[0].get('id'))}"))
    for key in ("id", "name", "digest", "size_in_bytes", "created_at", "workflow_run"):
        same(artifact.get(key), selected[0].get(key), "HISTORY_UNAVAILABLE", key)
    same(artifact.get("name"), name, "HISTORY_UNAVAILABLE", "artifact name")
    same(artifact.get("expired"), False, "HISTORY_UNAVAILABLE", "artifact expired")
    require(
        positive(artifact.get("size_in_bytes")) <= MAX_HISTORY_ZIP,
        "HISTORY_UNAVAILABLE",
        "history archive too large",
    )
    attached = obj(artifact.get("workflow_run"))
    for key, expected in {
        "id": run["id"],
        "head_sha": run["head_sha"],
        "head_branch": "main",
        "repository_id": positive(obj(run.get("repository")).get("id")),
        "head_repository_id": positive(obj(run.get("head_repository")).get("id")),
    }.items():
        same(attached.get(key), expected, "HISTORY_UNAVAILABLE", key)
    require(
        _utc(run.get("run_started_at"))
        <= _utc(artifact.get("created_at"))
        <= _utc(run.get("updated_at")),
        "HISTORY_UNAVAILABLE",
        "history outside attempt",
    )
    raw = api.archive(artifact, max_bytes=MAX_HISTORY_ZIP)
    same(len(raw), artifact.get("size_in_bytes"), "HISTORY_UNAVAILABLE", "archive length")
    same(sha256_ref(raw), artifact.get("digest"), "HISTORY_UNAVAILABLE", "archive digest")
    _check_zip_directory(raw)
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        require(
            len(infos) <= 128
            and len(set(names)) == len(names)
            and sum(i.file_size for i in infos) <= MAX_HISTORY_ZIP,
            "HISTORY_UNAVAILABLE",
            "history members exceed bounds",
        )
        for info in infos:
            require(
                info.filename == info.orig_filename
                and not info.is_dir()
                and not info.flag_bits & 1
                and stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG)
                and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                and info.file_size <= MAX_HISTORY_JSON
                and (
                    info.filename
                    in {
                        "input-plan.json",
                        "execution.json",
                        "plan.json",
                        "preflight.json",
                        "candidate-status.json",
                    }
                    or re.fullmatch(
                        r"targets/[a-f0-9]{64}/(?:preflight|candidate-status|preflight-timings|delivery-timings)\.json",
                        info.filename,
                    )
                    is not None
                    or info.filename == filename
                ),
                "HISTORY_UNAVAILABLE",
                "invalid history member",
            )
        selected_path = filename
        if "input-plan.json" in names:
            plan_raw = archive.read("input-plan.json")
            plan = read_probe(plan_raw)
            same(plan.main_sha, run["head_sha"], "HISTORY_UNAVAILABLE", "batch tooling")
            if digest not in plan.request_digests:
                return None  # Another target's failure is not this input's retry.
            require("execution.json" in names, "HISTORY_UNAVAILABLE", "execution evidence missing")
            progress = execution_progress(plan_raw, archive.read("execution.json"))
            if digest not in progress.started:
                return None
            selected_path = f"targets/{digest.removeprefix('sha256:')}/{filename}"
        require(selected_path in names, "HISTORY_UNAVAILABLE", "target history is missing")
        info = archive.getinfo(selected_path)
        with archive.open(info) as stream:
            document = stream.read(MAX_HISTORY_JSON + 1)
        require(len(document) == info.file_size, "HISTORY_UNAVAILABLE", "history file length")
        return document


def _run_identity(run: dict[str, object], head: str, run_id: int, attempt: int) -> None:
    for key, expected in {
        "id": run_id,
        "run_attempt": attempt,
        "path": WORKFLOW,
        "head_sha": head,
        "head_branch": "main",
    }.items():
        same(run.get(key), expected, "HISTORY_UNAVAILABLE", key)
    require(
        run.get("event") in {"workflow_run", "schedule"},
        "HISTORY_UNAVAILABLE",
        "unexpected automatic event",
    )
    for key in ("repository", "head_repository"):
        same(obj(run.get(key)).get("full_name"), REPOSITORY, "HISTORY_UNAVAILABLE", key)


def _backoff(
    api: GitHub, head: str, candidate: ProbeCandidate, now: datetime
) -> tuple[Reason | None, datetime | None]:
    # Imports stay here to avoid cli -> probe -> cli initialization cycles.
    from talent_data.ota.automation.cli import read_status

    since = now - timedelta(hours=24)
    listing = api.pages(
        "/actions/workflows/ota-production-publish.yml/runs",
        "workflow_runs",
        params={"branch": "main", "head_sha": head, "created": ">=" + since.isoformat()},
    )
    failures: list[datetime] = []
    seen: set[tuple[int, int]] = set()
    for item in listing:
        # API filter is not proof. Older code fingerprints have their own limit.
        if item.get("head_sha") != head:
            continue
        if item.get("event") == "workflow_dispatch":
            continue  # Explicit owner-controlled runs are a separate authorization path.
        run_id, last = positive(item.get("id")), positive(item.get("run_attempt"))
        require(last <= 100, "HISTORY_UNAVAILABLE", "attempt history exceeds bound")
        if last == 1 and item.get("status") == "completed" and item.get("conclusion") == "success":
            # This code SHA audits partial batch failures into the overall run result.
            _run_identity(item, head, run_id, 1)
            continue
        for attempt in range(1, last + 1):
            require((run_id, attempt) not in seen, "HISTORY_UNAVAILABLE", "duplicate attempt")
            seen.add((run_id, attempt))
            run = api.run(run_id, attempt)
            _run_identity(run, head, run_id, attempt)
            if run.get("status") == "completed" and run.get("conclusion") == "success":
                continue  # Earlier failed attempts remain separately accounted.
            if run.get("status") != "completed":
                require(
                    run.get("status")
                    in {"queued", "in_progress", "waiting", "pending", "requested"}
                    and run.get("conclusion") is None,
                    "HISTORY_UNAVAILABLE",
                    "unknown run status",
                )
            require(
                run.get("conclusion") is None
                or run.get("conclusion")
                in {
                    "success",
                    "failure",
                    "cancelled",
                    "timed_out",
                    "action_required",
                    "startup_failure",
                    "stale",
                    "neutral",
                    "skipped",
                },
                "HISTORY_UNAVAILABLE",
                "unknown run conclusion",
            )
            jobs = api.pages(f"/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
            for job in jobs:
                for field, expected in {
                    "run_id": run_id,
                    "run_attempt": attempt,
                    "head_sha": head,
                }.items():
                    same(job.get(field), expected, "HISTORY_UNAVAILABLE", field)
            # Renewal/global health and probe-only failures do not spend candidate budget.
            heavy = [
                j
                for j in jobs
                if j.get("name") in {"preflight", "writer"}
                and j.get("conclusion") != "skipped"
                and j.get("started_at") is not None
            ]
            if not heavy:
                continue
            require(
                len({j.get("name") for j in heavy}) == len(heavy),
                "HISTORY_UNAVAILABLE",
                "duplicate heavy jobs",
            )
            if run.get("status") != "completed":
                return "RUN_PENDING", None
            artifacts = api.pages(f"/actions/runs/{run_id}/artifacts", "artifacts")
            for failed in sorted(heavy, key=lambda job: job.get("name") != "writer"):
                stamp = _utc(failed.get("completed_at"))
                require(stamp <= now, "HISTORY_UNAVAILABLE", "future failure")
                if stamp < since:
                    continue
                writer = failed.get("name") == "writer"
                status_raw = _history_document(
                    api,
                    artifacts,
                    name=f"ota-production-{'writer' if writer else 'preflight'}-{run_id}-{attempt}",
                    filename="candidate-status.json" if writer else "preflight.json",
                    run=run,
                    digest=candidate.request_digest,
                )
                if status_raw is None:
                    continue
                status = read_status(status_raw)
                if status.outcome in {"READY", "VERIFIED", "NO_ACTION", "DISABLED", "DRY_RUN"}:
                    continue
                timing_raw = _history_document(
                    api,
                    artifacts,
                    name="ota-delivery-"
                    + ("timings" if writer else "preflight-timings")
                    + f"-{run_id}-{attempt}",
                    filename="delivery-timings.json" if writer else "preflight-timings.json",
                    run=run,
                    digest=candidate.request_digest,
                )
                require(timing_raw is not None, "HISTORY_UNAVAILABLE", "target timing is missing")
                assert timing_raw is not None
                timing = read_timings(timing_raw)
                same(timing.identity.main_sha, head, "HISTORY_UNAVAILABLE", "timing code")
                require(
                    status.command in ({"publish", "reconcile"} if writer else {"preflight"})
                    and status.operation in {"publish", "reconcile"}
                    and status.outcome in {"BLOCKED", "AMBIGUOUS", "PUBLISHED_NOT_VERIFIED"},
                    "HISTORY_UNAVAILABLE",
                    "failed heavy job status missing",
                )
                if timing.input_fingerprint != candidate.input_fingerprint:
                    continue
                same(
                    timing.identity.request_digest,
                    candidate.request_digest,
                    "HISTORY_UNAVAILABLE",
                    "timing request",
                )
                if timing.failure is not None and (
                    timing.failure.reason in {"SIGN_FAILED", "SIGNER_FAILED"}
                    or (
                        timing.failure.kind is not None
                        and timing.failure.kind.value in {"ACCESS_DENIED", "CREDENTIALS"}
                    )
                ):
                    return "PERMANENT_FAILURE", None
                failures.append(stamp)
                if len(failures) >= 3:
                    return "RETRY_LIMIT", None
                break  # One failed heavy attempt, even if both jobs report it.

    if len(failures) >= 3:
        return "RETRY_LIMIT", None
    if failures and (eligible := max(failures) + timedelta(seconds=300)) > now:
        return "COOLDOWN", eligible
    return None, None


def probe_delivery(
    api: GitHub,
    repo: Path,
    journal: Journal,
    *,
    now: datetime,
    trigger_sha: str | None,
    trigger_pin: tuple[int, int] | None,
) -> ProbeResult:
    head: str | None = None
    try:
        require(now.utcoffset() == timedelta(0), "CLOCK", "UTC required")
        head = git(repo, "rev-parse", "origin/main").decode().strip()
        same(git(repo, "rev-parse", "HEAD").decode().strip(), head, "CHECKOUT", "current main")
        same(trigger_sha is None, trigger_pin is None, "CI_PIN", "complete trigger pin")
        if trigger_sha is not None:
            same(trigger_sha, head, "CI_PIN", "current trigger SHA")
        try:
            entries = journal.read_all()
            for digest, entry in entries.items():
                checked = parse_strict(JournalEntry, entry.model_dump_json(by_alias=True).encode())
                same(digest, checked.receipt.request_digest, "JOURNAL", "receipt index")
        except (OSError, ValueError, JournalError, OtaError):
            return _result("BLOCKED", "JOURNAL_UNAVAILABLE", head=head)
        unresolved = tuple(
            _retained(e, "RECOVER", head)
            for _, e in sorted(entries.items())
            if e.receipt.state in {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}
        )
        if unresolved:
            return _result("RECOVER", "OUTCOME_UNKNOWN", head=head, candidates=unresolved)
        verify = tuple(
            _retained(e, "VERIFY", head)
            for _, e in sorted(entries.items())
            if e.receipt.state is RequestState.PUBLISHED_NOT_VERIFIED
        )
        requests = committed_requests(repo)
        pending = [
            r
            for r in requests
            if request_digest(r) not in entries
            or entries[request_digest(r)].receipt.state
            not in TERMINAL | {RequestState.PUBLISHED_NOT_VERIFIED}
        ]
        if not pending:
            return (
                _result("VERIFY", "PUBLIC_PROOF_PENDING", head=head, candidates=verify)
                if verify
                else _result("NO_WORK", "IDLE", head=head)
            )
        if current_main_ci(api, repo, head, pin=trigger_pin) is None:
            if verify:
                return _result("VERIFY", "PUBLIC_PROOF_PENDING", head=head, candidates=verify)
            return _result("WAIT", "CI_PENDING", head=head)
        publish: list[ProbeCandidate] = []
        waits: list[tuple[Reason, datetime | None]] = []
        blocks: list[Reason] = []
        catalog: OtaCatalog | None = None
        catalog_checked = False
        history = _HistoryReads(api)
        for request in sorted(pending, key=lambda r: r.release_id, reverse=True):
            approval = approval_record_at(repo, "origin/main", request.release_id)
            from talent_data.ota.automation.merge_approval import parse_merge_record

            conditional = (
                parse_merge_record(approval.decode("utf-8", "strict")) if approval else None
            )
            if conditional is not None:
                if (
                    conditional.revoked
                    or conditional.release_id != request.release_id
                    or conditional.source_sha != request.source_commit
                    or conditional.request_digest
                    != sha256_ref(
                        (request.model_dump_json(by_alias=True, indent=2) + "\n").encode()
                    )
                ):
                    waits.append(("APPROVAL_PENDING", None))
                    continue
            elif approval is None or approval_record_problems(
                approval.decode("utf-8", "strict"),
                release_id=request.release_id,
                manifest_sha256=request.manifest_sha256,
                source_commit=request.source_commit,
            ):
                waits.append(("APPROVAL_PENDING", None))
                continue
            retained = entries.get(request_digest(request))
            if retained and retained.receipt.reason in {"LIVE_CHANGED", "ALREADY_LISTED"}:
                if not catalog_checked:
                    try:
                        catalog = _public_catalog(api, repo, now)
                    except (OSError, OtaError, ValueError, httpx.HTTPError):
                        catalog = None
                    catalog_checked = True
                if catalog is None or _stale_reason(request, catalog) is not None:
                    waits.append(("BASE_NOT_CURRENT", None))
                    continue
            assert approval is not None
            candidate = ProbeCandidate(
                action="PUBLISH",
                release_id=request.release_id,
                request_digest=request_digest(request),
                input_fingerprint=delivery_input_fingerprint(
                    request_digest(request),
                    sha256_ref(approval),
                    head,
                    (request.artifact.artifact_id,),
                ),
            )
            try:
                pin = request_ci_pin(
                    repo, request, trigger_sha=trigger_sha, trigger_pin=trigger_pin
                )
                authorize_request_ci(
                    api,
                    repo,
                    request,
                    ci_run_id=pin[0] if pin else None,
                    ci_attempt=pin[1] if pin else None,
                )
                _package_metadata(api, request)
            except (PreparationBlocked, RequestRejected):
                blocks.append("INPUT_INVALID")
                continue
            try:
                reason, next_at = _backoff(history, head, candidate, now)
            except (
                PreparationBlocked,
                RequestRejected,
                ValueError,
                TypeError,
                KeyError,
                OSError,
                OtaError,
                zlib.error,
                zipfile.BadZipFile,
                NotImplementedError,
                httpx.HTTPError,
            ):
                blocks.append("HISTORY_UNAVAILABLE")
                continue
            if reason in {"COOLDOWN", "RUN_PENDING"}:
                waits.append((reason, next_at))
            elif reason is not None:
                blocks.append(reason)
            else:
                publish.append(candidate)
        if publish:
            return _result("PUBLISH", "READY", head=head, candidates=(*publish, *verify))
        if verify:
            return _result("VERIFY", "PUBLIC_PROOF_PENDING", head=head, candidates=verify)
        if blocks:
            return _result("BLOCKED", blocks[0], head=head)
        if waits:
            reason, next_at = waits[0]
            return _result("WAIT", reason, head=head, next_at=next_at)
        return _result("NO_WORK", "IDLE", head=head)
    except (
        PreparationBlocked,
        RequestRejected,
        JournalError,
        OtaError,
        ValueError,
        TypeError,
        KeyError,
        OSError,
        httpx.HTTPError,
    ):
        return _result("BLOCKED", "CURRENT_MAIN_UNPROVEN", head=head)


def main(
    argv: list[str],
    *,
    environment: Mapping[str, str] | None = None,
    dependencies: Dependencies | None = None,
) -> int:
    from talent_data.ota.automation.cli import ENABLE_FLAG, _git_authentication
    from talent_data.ota.automation.publisher import refresh_main

    parser = argparse.ArgumentParser()
    parser.add_argument("--status-file", type=Path, required=True)
    args = parser.parse_args(argv)
    env = os.environ if environment is None else environment
    result = _result("NO_WORK", "DISABLED")
    if env.get(ENABLE_FLAG) == "true":
        try:
            with (
                _git_authentication(env.get("GITHUB_TOKEN")),
                httpx.Client(timeout=30, follow_redirects=False) as client,
            ):
                api = (
                    dependencies.api
                    if dependencies
                    else GitHub(client, env.get("GITHUB_TOKEN", ""))
                )
                repo = dependencies.repo if dependencies else TRUSTED_REPO
                journal = dependencies.journal if dependencies else GitJournal(repo)
                refresh_main(repo)
                payload = obj(strict_json(Path(env["GITHUB_EVENT_PATH"]).read_bytes()))
                ci = verify_invocation(api, repo, env, payload)
                result = probe_delivery(
                    api,
                    repo,
                    journal,
                    now=dependencies.now() if dependencies else datetime.now(UTC),
                    trigger_sha=ci.pin.head_sha if ci else None,
                    trigger_pin=(ci.pin.run_id, ci.pin.attempt) if ci else None,
                )
        except (
            PreparationBlocked,
            RequestRejected,
            JournalError,
            OtaError,
            ValueError,
            TypeError,
            KeyError,
            OSError,
            httpx.HTTPError,
        ):
            result = _result("BLOCKED", "INVOCATION_REFUSED")
    args.status_file.parent.mkdir(parents=True, exist_ok=True)
    raw = result.model_dump_json(by_alias=True)
    args.status_file.write_text(raw + "\n")
    print(raw)
    if output := env.get("GITHUB_OUTPUT"):
        with Path(output).open("a") as out:
            out.write(f"action={result.action}\n")
    return 0  # Typed refusal/wait is routing data; it never implies an interrupted writer.
