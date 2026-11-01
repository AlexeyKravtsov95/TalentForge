"""Guarded production commands. Production locations and keys have no CLI overrides.

Test dependencies are injected in-process only. The external entry point constructs
its own trusted repository/API/public client, journal and fixed S3 adapter. Preflight
never calls a production secret reader, even when recording a durable refusal.
"""

from __future__ import annotations

import argparse
import contextlib
import os
import re
import sys
import tempfile
from collections.abc import Callable, Iterator, Mapping
from contextlib import AbstractContextManager
from dataclasses import dataclass
from datetime import UTC, datetime
from functools import partial
from pathlib import Path
from typing import Literal, Self

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from pydantic import Field, field_validator, model_validator

from talent_data.ota.automation.authorization import (
    authorize_request,
    committed_requests,
    request_change_commit,
    verify_invocation,
)
from talent_data.ota.automation.ci_signer import (
    CiPemSigner,
    SecretError,
    SecretReader,
    temporary_aws_profile,
)
from talent_data.ota.automation.prepare import GitHub, PreparationBlocked, git, obj, require, same
from talent_data.ota.automation.provenance import (
    TRUSTED_REPO,
    RequestRejected,
    extract_verified_archive,
)
from talent_data.ota.automation.public_verify import _fetch, _PublicFailure, verify_publication
from talent_data.ota.automation.publisher import (
    LiveSnapshot,
    ProvenanceEvidence,
    assess_request,
    publish_request,
    read_live,
    recover_outcome,
    refresh_main,
    renew_catalog,
)
from talent_data.ota.automation.reconcile import (
    confirm_publication,
    reconcile_requests,
    resign_due,
    select_requests,
    verify_after_write,
)
from talent_data.ota.automation.requests import ReleaseRequest, _Model
from talent_data.ota.automation.state import (
    FileJournal,
    GitJournal,
    Journal,
    JournalError,
    PublicationReceipt,
    Receipt,
    RenewalReceipt,
    RequestState,
    request_digest,
)
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.models import (
    MAX_CATALOG_BYTES,
    MAX_RELEASE_BYTES,
    RELEASE_ID_PATTERN,
    SHA256_PATTERN,
    Channel,
)
from talent_data.ota.preflight import production_keyring
from talent_data.ota.release_package import LoadedPackage, load_package
from talent_data.ota.s3_channels import PRODUCTION_PROFILE
from talent_data.ota.s3_store import ProductionCatalogGuard, S3Location, connect
from talent_data.ota.store import BLOB_PREFIX, BlobStore, catalog_key
from talent_data.ota.verify import OtaError, TrustedKey, pae, parse_strict, sha256_ref, strict_json
from talent_data.prepare.propose import git_auth

ENABLE_FLAG = "OTA_PRODUCTION_AUTOMATION_ENABLED"
PRIMARY_KEY = "tf-prod-2026a"
GAME = "forever"
Operation = Literal["publish", "reconcile", "resign", "verify", "signer-smoke", "init-journal"]
Outcome = Literal[
    "DISABLED",
    "BLOCKED",
    "DRY_RUN",
    "READY",
    "VERIFIED",
    "NO_ACTION",
    "AMBIGUOUS",
    "PUBLISHED_NOT_VERIFIED",
]
Stage = Literal[
    "CONFIG",
    "INVOCATION",
    "JOURNAL",
    "PUBLIC_CATALOG",
    "RECOVERY",
    "PUBLIC_VERIFY",
    "PLANNING",
    "AUTHORIZATION",
    "PROVENANCE",
    "APPROVAL_GATES",
    "PUBLISH",
    "RENEWAL",
    "SIGNER",
    "CLEANUP",
]
Reason = Literal[
    "AUTOMATION_DISABLED",
    "CONFIG_MISSING",
    "INVOCATION_REFUSED",
    "JOURNAL_FAILED",
    "PUBLIC_CATALOG_UNVERIFIED",
    "RECOVERY_REQUIRED",
    "OUTCOME_UNKNOWN",
    "PUBLIC_NOT_VERIFIED",
    "NO_WORK",
    "READY",
    "EXECUTE_REQUIRED",
    "REQUEST_AUTHORIZATION_FAILED",
    "PACKAGE_PROVENANCE_FAILED",
    "ASSESSMENT_FAILED",
    "PUBLIC_VERIFIED",
    "PUBLICATION_BLOCKED",
    "RENEWAL_BLOCKED",
    "SIGNER_VERIFIED",
    "SIGNER_FAILED",
    "JOURNAL_INITIALIZED",
    "CLEANUP_FAILED",
]


class OperationStatus(_Model):
    digest: str = Field(pattern=SHA256_PATTERN)
    kind: Literal["release", "renewal"]
    state: RequestState
    reason: Literal[
        "NEW",
        "READY",
        "WRITE_STARTED",
        "S3_EXACT",
        "PUBLIC_VERIFIED",
        "LIVE_CHANGED",
        "ALREADY_LISTED",
        "INITIAL_RELEASE",
        "CHECK_FAILED",
        "REQUEST_REMOVED",
        "UPLOAD_FAILED",
        "SIGN_FAILED",
        "NOT_WRITTEN",
        "OUTCOME_UNKNOWN",
    ]
    release_id: str | None = Field(pattern=RELEASE_ID_PATTERN)
    payload_sha256: str | None = Field(pattern=SHA256_PATTERN)


class StatusDocument(_Model):
    schema_version: Literal[1]
    kind: Literal["ota-production-status"]
    command: Literal[
        "preflight", "publish", "reconcile", "resign", "verify", "signer-smoke", "init-journal"
    ]
    operation: Operation
    outcome: Outcome
    stage: Stage
    reason: Reason
    writer_needed: bool
    operations: tuple[OperationStatus, ...]

    @field_validator("schema_version", mode="before")
    @classmethod
    def integer_version(cls, value: object) -> object:
        if type(value) is not int:
            raise ValueError("schemaVersion must be an integer")
        return value

    @model_validator(mode="after")
    def writer_ready(self) -> Self:
        if self.writer_needed and self.outcome != "READY":
            raise ValueError("only READY can request a writer")
        return self

    @property
    def exit_code(self) -> int:
        if self.outcome in {"AMBIGUOUS", "PUBLISHED_NOT_VERIFIED"}:
            return 3
        return 1 if self.outcome == "BLOCKED" else 0


def read_status(raw: bytes) -> StatusDocument:
    return parse_strict(StatusDocument, raw)


@dataclass
class Dependencies:
    repo: Path
    api: GitHub
    client: httpx.Client
    journal: Journal
    keyring: Mapping[str, TrustedKey]
    writer: Callable[[], AbstractContextManager[BlobStore]]
    read_secret: SecretReader = os.environ.get
    temporary_root: Path | None = None
    now: Callable[[], datetime] = lambda: datetime.now(UTC).replace(microsecond=0)
    origin: str = PRODUCTION_PROFILE.public_origin
    verification_budget: float = 600


class PublicStore:
    """Anonymous read-only mirror for the existing publisher assessment/gates."""

    def __init__(self, client: httpx.Client, origin: str):
        self.client, self.origin = client, origin.rstrip("/")

    def get(self, key: str) -> bytes:
        if key == catalog_key(Channel.PRODUCTION):
            limit = MAX_CATALOG_BYTES
        elif re.fullmatch(BLOB_PREFIX + r"[a-f0-9]{64}", key):
            limit = MAX_RELEASE_BYTES
        else:
            raise OtaPublisherError("out of scope public object")
        try:
            return _fetch(self.client, self.origin + "/" + key, limit)
        except (httpx.HTTPError, ValueError, _PublicFailure) as exc:
            raise OtaPublisherError("public object unavailable") from exc

    def exists(self, key: str) -> bool:
        self.get(key)
        return True

    def put(self, key: str, data: bytes, *, cache_control: str) -> None:
        raise OtaPublisherError("public mirror is read-only")

    def list(self, prefix: str) -> list[str]:
        raise OtaPublisherError("public mirror cannot list")

    def delete(self, key: str) -> None:
        raise OtaPublisherError("public mirror is read-only")


_FAILURES = (
    PreparationBlocked,
    RequestRejected,
    JournalError,
    SecretError,
    OtaPublisherError,
    OtaError,
    OSError,
    ValueError,
    httpx.HTTPError,
)
_UNRESOLVED = {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}


class _Run:
    def __init__(self, args: argparse.Namespace, env: Mapping[str, str], deps: Dependencies):
        self.args, self.env, self.d = args, env, deps
        self.operation: Operation = args.operation if args.command == "preflight" else args.command
        self.stage: Stage = "INVOCATION"
        self.reason: Reason = "INVOCATION_REFUSED"
        self.packages: dict[str, LoadedPackage] = {}
        self.archives: dict[str, bytes] = {}
        self.current_bytes: dict[str, bytes] = {}
        self.recovered: set[str] = set()
        self.failure: tuple[Stage, Reason] | None = None
        self.ci_pin: tuple[int, int] | None = None
        self.ci_sha: str | None = None
        self.run_id = int(env.get("GITHUB_RUN_ID", "0"))
        self.attempt = int(env.get("GITHUB_RUN_ATTEMPT", "0"))

    def status(
        self, outcome: Outcome, stage: Stage, reason: Reason, *, writer: bool = False
    ) -> StatusDocument:
        operations = []
        if self.operation not in {"init-journal", "signer-smoke"}:
            for entry in self.d.journal.read_all().values():
                receipt = entry.receipt
                operations.append(
                    OperationStatus(
                        digest=receipt.request_digest,
                        kind="release" if isinstance(receipt, PublicationReceipt) else "renewal",
                        state=receipt.state,
                        reason=receipt.reason,
                        release_id=receipt.request.release_id
                        if isinstance(receipt, PublicationReceipt)
                        else None,
                        payload_sha256=receipt.payload_sha256,
                    )
                )
        return StatusDocument(
            schema_version=1,
            kind="ota-production-status",
            command=self.args.command,
            operation=self.operation,
            outcome=outcome,
            stage=stage,
            reason=reason,
            writer_needed=writer,
            operations=tuple(operations),
        )

    def invocation(self) -> None:
        self.stage, self.reason = "INVOCATION", "INVOCATION_REFUSED"
        refresh_main(self.d.repo)
        payload = obj(strict_json(Path(self.env["GITHUB_EVENT_PATH"]).read_bytes()))
        verify_invocation(self.d.api, self.d.repo, self.env, payload)
        event = self.env["GITHUB_EVENT_NAME"]
        if self.operation in {"signer-smoke", "init-journal"}:
            require(event == "workflow_dispatch", "MANUAL_REQUIRED", "manual setup only")
        raw_id, raw_attempt = self.args.ci_run_id, self.args.ci_attempt
        require((raw_id is None) == (raw_attempt is None), "CI_PIN", "incomplete CI pin")
        if event == "workflow_run":
            trigger = obj(payload["workflow_run"])
            event_pin = (int(trigger["id"]), int(trigger["run_attempt"]))
            if raw_id is not None:
                same((raw_id, raw_attempt), event_pin, "CI_PIN", "event pin")
            self.ci_pin = event_pin
            self.ci_sha = str(trigger["head_sha"])
        elif raw_id is not None:
            require(event == "workflow_dispatch", "CI_PIN", "CI pins require manual event")
            inputs = obj(payload.get("inputs"))
            same(inputs.get("ci_run_id"), str(raw_id), "CI_PIN", "manual CI run")
            same(inputs.get("ci_attempt"), str(raw_attempt), "CI_PIN", "manual CI attempt")
            self.ci_pin = (raw_id, raw_attempt)

    def public_live(self) -> LiveSnapshot:
        self.stage, self.reason = "PUBLIC_CATALOG", "PUBLIC_CATALOG_UNVERIFIED"
        result = verify_publication(
            self.d.client, origin=self.d.origin, keyring=self.d.keyring, game=GAME, now=self.d.now()
        )
        require(result.ok, "PUBLIC_UNVERIFIED", "full public verification failed")
        live = read_live(
            PublicStore(self.d.client, self.d.origin),
            keyring=self.d.keyring,
            game=GAME,
            now=self.d.now(),
        )
        same(sha256_ref(live.payload), result.catalog_payload_sha256, "PUBLIC_CHANGED", "catalog")
        return live

    def evidence(self, request: ReleaseRequest) -> ProvenanceEvidence:
        # Re-fetch current main + exact committed bytes + authorization and API metadata
        # on EVERY assessment, including after upload and the WRITE_STARTED barrier.
        self.stage, self.reason = "AUTHORIZATION", "REQUEST_AUTHORIZATION_FAILED"
        refresh_main(self.d.repo)
        path = f"docs/ota/release-requests/{request.release_id}.json"
        raw = git(self.d.repo, "show", f"origin/main:{path}")
        same(raw, self.current_bytes[request_digest(request)], "REQUEST_CHANGED", "request bytes")
        authorize_request(
            self.d.api,
            self.d.repo,
            request,
            ci_run_id=self.ci_pin[0] if self.ci_pin else None,
            ci_attempt=self.ci_pin[1] if self.ci_pin else None,
        )
        self.stage, self.reason = "PROVENANCE", "PACKAGE_PROVENANCE_FAILED"
        runs: dict[str, object] = {
            name: self.d.api.run(pin.run_id, pin.attempt)
            for name, pin in (("package", request.package_run), ("staging", request.staging_run))
        }
        artifact = obj(self.d.api.repo("GET", f"/actions/artifacts/{request.artifact.artifact_id}"))
        # Reject expired/moved metadata before downloading; archive bytes alone may cache.
        for field, expected in {
            "id": request.artifact.artifact_id,
            "name": request.artifact.name,
            "digest": request.artifact.archive_sha256,
            "expired": False,
        }.items():
            same(artifact.get(field), expected, "ARTIFACT_ORIGIN", field)
        digest = request_digest(request)
        if digest not in self.archives:
            self.archives[digest] = self.d.api.archive(artifact)
        return ProvenanceEvidence(runs, artifact, self.archives[digest])

    def assess(self, request: ReleaseRequest, root: Path) -> None:
        digest = request_digest(request)
        evidence = self.evidence(request)
        if digest not in self.packages:
            self.packages[digest] = load_package(
                extract_verified_archive(
                    evidence.archive_bytes, root / digest.removeprefix("sha256:")
                )
            )
        approvals = root / "approvals"
        approvals.mkdir(exist_ok=True)
        self.stage, self.reason = "APPROVAL_GATES", "ASSESSMENT_FAILED"
        assess_request(
            request,
            package=self.packages[digest],
            repo=self.d.repo,
            store=PublicStore(self.d.client, self.d.origin),
            keyring=self.d.keyring,
            now=self.d.now(),
            provenance=lambda: self.evidence(request),
            directory=approvals,
        )

    def pending_verification(self) -> None:
        self.stage, self.reason = "PUBLIC_VERIFY", "PUBLIC_NOT_VERIFIED"
        for digest, entry in self.d.journal.read_all().items():
            receipt = entry.receipt
            if receipt.state is not RequestState.PUBLISHED_NOT_VERIFIED:
                continue
            assert receipt.payload_sha256 is not None
            result = verify_after_write(
                self.d.client,
                origin=self.d.origin,
                keyring=self.d.keyring,
                game=receipt.game,
                now=self.d.now(),
                expected_payload_sha256=receipt.payload_sha256,
                budget_seconds=self.d.verification_budget,
            )
            confirm_publication(self.d.journal, digest, result)

    def plan(self, root: Path, *, mutate: bool) -> tuple[tuple[ReleaseRequest, ...], bool]:
        # An unknown private PUT must be diagnosed before CDN convergence is required.
        # This branch authorizes a diagnostic turn only, never signing or a catalog PUT.
        self.stage, self.reason = "JOURNAL", "JOURNAL_FAILED"
        entries = self.d.journal.read_all()
        if any(x.receipt.state in _UNRESOLVED for x in entries.values()):
            return (), True
        live = self.public_live()
        self.stage, self.reason = "PLANNING", "NO_WORK"
        requests = committed_requests(self.d.repo)
        receipts = {
            k: v.receipt for k, v in entries.items() if isinstance(v.receipt, PublicationReceipt)
        }
        if mutate:
            selected = reconcile_requests(
                requests,
                journal=self.d.journal,
                live=live.catalog,
                run_id=self.run_id,
                attempt=self.attempt,
            )
        else:
            selected = select_requests(requests, receipts, live=live.catalog)
        eligible = []
        if self.operation == "resign":
            selected = ()
        for request in selected:
            digest = request_digest(request)
            if digest in self.recovered:
                continue  # recovery only diagnoses; exact PREPARED resumes on next run
            if (
                self.ci_sha is not None
                and request_change_commit(self.d.repo, request) != self.ci_sha
            ):
                continue  # event writes only its exact release-change commit
            if self.args.release_id is not None and request.release_id != self.args.release_id:
                continue
            path = f"docs/ota/release-requests/{request.release_id}.json"
            raw = git(self.d.repo, "show", f"origin/main:{path}")
            if digest in self.current_bytes:
                same(raw, self.current_bytes[digest], "REQUEST_CHANGED", "request bytes")
            self.current_bytes[digest] = raw
            try:
                self.assess(request, root)
            except _FAILURES:
                if mutate:
                    retained = self.d.journal.load(digest)
                    receipt = (
                        retained.receipt
                        if retained
                        else PublicationReceipt.pending(
                            request, run_id=self.run_id, attempt=self.attempt
                        )
                    )
                    self.d.journal.save(
                        receipt.evolve(state=RequestState.BLOCKED, reason="CHECK_FAILED"),
                        retained.prepared if retained else None,
                    )
                raise
            eligible.append(request)
        due = self.operation in {"resign", "reconcile"} and resign_due(
            live.catalog, now=self.d.now()
        )
        if self.operation == "resign":
            eligible = []
        return tuple(eligible), due

    def final(self, *, changed: bool) -> StatusDocument:
        entries = self.d.journal.read_all().values()
        states = {x.receipt.state for x in entries}
        if states & _UNRESOLVED:
            return self.status("AMBIGUOUS", "RECOVERY", "OUTCOME_UNKNOWN")
        if RequestState.PUBLISHED_NOT_VERIFIED in states:
            return self.status("PUBLISHED_NOT_VERIFIED", "PUBLIC_VERIFY", "PUBLIC_NOT_VERIFIED")
        blocked = any(
            entry.receipt.state is RequestState.BLOCKED
            and (
                self.operation == "reconcile"
                or (self.operation == "publish" and isinstance(entry.receipt, PublicationReceipt))
                or (self.operation == "resign" and isinstance(entry.receipt, RenewalReceipt))
            )
            for entry in entries
        )
        if blocked:
            stage, reason = self.failure or (
                ("RENEWAL", "RENEWAL_BLOCKED")
                if self.operation == "resign"
                else ("PUBLISH", "PUBLICATION_BLOCKED")
            )
            return self.status("BLOCKED", stage, reason)
        return self.status(
            "VERIFIED" if changed else "NO_ACTION",
            "PUBLIC_VERIFY",
            "PUBLIC_VERIFIED" if changed else "NO_WORK",
        )

    def run(self) -> StatusDocument:
        self.invocation()
        self.stage, self.reason = "CONFIG", "CONFIG_MISSING"
        same(self.env.get("OTA_PRODUCTION_KEY_ID"), PRIMARY_KEY, "KEY_ID", "primary key id")
        require(PRIMARY_KEY in self.d.keyring, "KEY_ID", "primary key not pinned")
        execute = self.args.execute
        if self.operation == "init-journal":
            self.invocation()
            if self.args.command == "preflight" or not execute:
                return self.status(
                    "READY" if self.args.command == "preflight" else "DRY_RUN",
                    "JOURNAL",
                    "EXECUTE_REQUIRED",
                    writer=self.args.command == "preflight",
                )
            self.stage, self.reason = "JOURNAL", "JOURNAL_FAILED"
            if not isinstance(self.d.journal, (GitJournal, FileJournal)):
                raise JournalError("journal does not support initialization")
            self.d.journal.initialize()
            return self.status("NO_ACTION", "JOURNAL", "JOURNAL_INITIALIZED")
        if self.operation == "signer-smoke":
            self.invocation()
            if self.args.command == "preflight" or not execute:
                return self.status(
                    "READY" if self.args.command == "preflight" else "DRY_RUN",
                    "SIGNER",
                    "EXECUTE_REQUIRED",
                    writer=self.args.command == "preflight",
                )
            self.stage, self.reason = "SIGNER", "SIGNER_FAILED"
            signer = CiPemSigner(
                self.d.keyring, read_secret=self.d.read_secret, temporary_root=self.d.temporary_root
            )
            challenge = pae(
                "application/vnd.talentforge.ci-signer-smoke.v1",
                b"TalentForge synthetic signer verification; never a catalog",
            )
            signature = signer.sign(challenge)
            self.d.keyring[PRIMARY_KEY].public_key.verify(
                signature, challenge, ec.ECDSA(hashes.SHA256())
            )
            return self.status("VERIFIED", "SIGNER", "SIGNER_VERIFIED")
        if self.operation == "verify":
            if execute:
                self.pending_verification()
            self.public_live()
            return self.final(changed=True)
        with tempfile.TemporaryDirectory(prefix="ota-publication-") as temporary:
            root = Path(temporary).resolve()
            if execute and not any(
                entry.receipt.state in _UNRESOLVED for entry in self.d.journal.read_all().values()
            ):
                self.pending_verification()
            # Pure preflight is also used inside the protected writer before credentials.
            selected, due_or_recovery = self.plan(root, mutate=execute)
            if self.args.command == "preflight":
                if not selected and not due_or_recovery:
                    return self.final(changed=False)
                return self.status(
                    "READY" if selected or due_or_recovery else "NO_ACTION",
                    "PLANNING",
                    "READY" if selected or due_or_recovery else "NO_WORK",
                    writer=bool(selected or due_or_recovery),
                )
            if not execute:
                return self.status("DRY_RUN", "PLANNING", "EXECUTE_REQUIRED")
            # PNV verification above is independent of request/package selection.
            unresolved = any(
                x.receipt.state in _UNRESOLVED for x in self.d.journal.read_all().values()
            )
            if not selected and not due_or_recovery and not unresolved:
                return self.final(changed=False)
            self.invocation()
            # Repeat the complete secret-free assessment immediately before profile loading.
            selected, due_or_recovery = self.plan(root, mutate=True)
            if not selected and not due_or_recovery:
                return self.final(changed=False)
            self.stage, self.reason = "CONFIG", "CONFIG_MISSING"
            with (
                temporary_aws_profile(
                    read_secret=self.d.read_secret, temporary_root=self.d.temporary_root
                ),
                self.d.writer() as store,
            ):
                for digest, entry in self.d.journal.read_all().items():
                    if entry.receipt.state in _UNRESOLVED:
                        if entry.prepared is None:
                            raise JournalError("exact recovery work missing")
                        retained_receipt = entry.receipt
                        if isinstance(retained_receipt, PublicationReceipt):
                            result: Receipt = recover_outcome(
                                retained_receipt,
                                entry.prepared,
                                store=store,
                                keyring=self.d.keyring,
                                now=self.d.now(),
                            )
                        else:
                            result = recover_outcome(
                                retained_receipt,
                                entry.prepared,
                                store=store,
                                keyring=self.d.keyring,
                                now=self.d.now(),
                            )
                        self.d.journal.save(result, entry.prepared)
                        self.recovered.add(digest)
                if self.recovered:
                    # A recovery turn only diagnoses retained exact work. It never
                    # selects a fresh write, even if public convergence is unavailable.
                    selected = ()
                signer = CiPemSigner(
                    self.d.keyring,
                    read_secret=self.d.read_secret,
                    temporary_root=self.d.temporary_root,
                )
                changed = any(
                    entry.receipt.state is RequestState.PUBLISHED_NOT_VERIFIED
                    for digest, entry in self.d.journal.read_all().items()
                    if digest in self.recovered
                )
                for request in selected:
                    retained = self.d.journal.load(request_digest(request))
                    assert retained is not None and isinstance(retained.receipt, PublicationReceipt)
                    self.stage, self.reason = "PUBLISH", "PUBLICATION_BLOCKED"

                    result = publish_request(
                        request,
                        package=self.packages[request_digest(request)],
                        repo=self.d.repo,
                        store=store,
                        keyring=self.d.keyring,
                        signer=signer,
                        receipt=retained.receipt,
                        now=self.d.now(),
                        journal=self.d.journal,
                        provenance=partial(self.evidence, request),
                    )
                    if result.state is RequestState.BLOCKED:
                        self.failure = (
                            ("SIGNER", "SIGNER_FAILED")
                            if result.reason == "SIGN_FAILED"
                            else (self.stage, self.reason)
                        )
                    changed |= result.state is RequestState.PUBLISHED_NOT_VERIFIED
                if self.operation in {"reconcile", "resign"} and not self.recovered:
                    self.stage, self.reason = "RENEWAL", "RENEWAL_BLOCKED"
                    renewal = renew_catalog(
                        store=store,
                        keyring=self.d.keyring,
                        game=GAME,
                        now=self.d.now(),
                        journal=self.d.journal,
                        signer=signer,
                        run_id=self.run_id,
                        attempt=self.attempt,
                    )
                    if renewal is not None and renewal.state is RequestState.BLOCKED:
                        self.failure = (
                            ("SIGNER", "SIGNER_FAILED")
                            if renewal.reason == "SIGN_FAILED"
                            else ("RENEWAL", "RENEWAL_BLOCKED")
                        )
                    changed |= (
                        renewal is not None and renewal.state is RequestState.PUBLISHED_NOT_VERIFIED
                    )
                # A resource cleanup error after PUT must not erase durable evidence
                # that the target was written but has not yet had its public proof.
                self.stage, self.reason = "CLEANUP", "CLEANUP_FAILED"
            self.pending_verification()
            return self.final(changed=changed)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ota_release_automation.py")
    commands = parser.add_subparsers(dest="command", required=True)
    for command in (
        "preflight",
        "publish",
        "reconcile",
        "resign",
        "verify",
        "signer-smoke",
        "init-journal",
    ):
        sub = commands.add_parser(command)
        sub.add_argument("--execute", action="store_true")
        sub.add_argument("--status-file", type=Path, required=True)
        sub.add_argument("--ci-run-id", type=int)
        sub.add_argument("--ci-attempt", type=int)
        sub.add_argument("--release-id")
        if command == "preflight":
            sub.add_argument(
                "--operation",
                choices=(
                    "publish",
                    "reconcile",
                    "resign",
                    "verify",
                    "signer-smoke",
                    "init-journal",
                ),
                default="reconcile",
            )
    return parser


def _emit(document: StatusDocument, path: Path, env: Mapping[str, str]) -> int:
    raw = document.model_dump_json(by_alias=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(raw + "\n", encoding="utf-8")
    print(raw)
    if summary := env.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as out:
            out.write(
                f"OTA production: {document.outcome}\n"
                f"Stage: {document.stage}\nReason: {document.reason}\n"
            )
    if output := env.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as out:
            out.write(
                f"outcome={document.outcome}\nwriter_needed={str(document.writer_needed).lower()}\nexit_code={document.exit_code}\n"
            )
    return document.exit_code


def _empty(
    args: argparse.Namespace, outcome: Outcome, stage: Stage, reason: Reason
) -> StatusDocument:
    return StatusDocument(
        schema_version=1,
        kind="ota-production-status",
        command=args.command,
        operation=args.operation if args.command == "preflight" else args.command,
        outcome=outcome,
        stage=stage,
        reason=reason,
        writer_needed=False,
        operations=(),
    )


@contextlib.contextmanager
def _git_authentication(token: str | None) -> Iterator[None]:
    """URL-scoped GitHub auth in subprocess environment, never config/argv/files.

    Checkout deliberately persists no credentials. Reuse the existing preparation
    adapter and restore every inherited variable after success, refusal or cleanup.
    """
    authentication = git_auth(token, "https://github.com")
    previous = {name: os.environ.get(name) for name in authentication}
    try:
        os.environ.update(authentication)
        yield
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def main(
    argv: list[str] | None = None,
    *,
    environment: Mapping[str, str] | None = None,
    dependencies: Dependencies | None = None,
) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] in {"check-app", "prepare-request", "propose-request", "fetch-staging"}:
        from talent_data.ota.automation.prepare import main as prepare_main

        return prepare_main(argv)
    args = _parser().parse_args(argv)
    env = os.environ if environment is None else environment
    if env.get(ENABLE_FLAG) != "true":
        return _emit(
            _empty(args, "DISABLED", "CONFIG", "AUTOMATION_DISABLED"), args.status_file, env
        )
    with _git_authentication(env.get("GITHUB_TOKEN")):
        run = None
        try:
            with contextlib.ExitStack() as stack:
                if dependencies is None:
                    client = stack.enter_context(httpx.Client(follow_redirects=False, timeout=30))
                    keys = production_keyring(
                        TRUSTED_REPO / "docs/ota/keys/production-keyring.json"
                    )
                    api = GitHub(client, env.get("GITHUB_TOKEN", ""))

                    @contextlib.contextmanager
                    def writer() -> Iterator[BlobStore]:
                        profile = PRODUCTION_PROFILE
                        yield connect(
                            S3Location(
                                bucket=profile.bucket,
                                endpoint_url=profile.endpoint_url,
                                region=profile.region,
                                profile=profile.aws_profile,
                                channel=Channel.PRODUCTION,
                            ),
                            writable=True,
                            allow_delete=False,
                            catalog_guard=ProductionCatalogGuard(keys, game=GAME),
                        )

                    dependencies = Dependencies(
                        TRUSTED_REPO, api, client, GitJournal(TRUSTED_REPO), keys, writer
                    )
                run = _Run(args, env, dependencies)
                document = run.run()
        except (*_FAILURES, InvalidSignature, KeyError, TypeError):
            document = _empty(
                args,
                "BLOCKED",
                run.stage if run else "CONFIG",
                run.reason if run else "CONFIG_MISSING",
            )
            if run is not None:
                with contextlib.suppress(*_FAILURES):
                    document = run.status("BLOCKED", run.stage, run.reason)
                    if any(x.state in _UNRESOLVED for x in document.operations):
                        document = run.status("AMBIGUOUS", "RECOVERY", "OUTCOME_UNKNOWN")
                    elif any(
                        x.state is RequestState.PUBLISHED_NOT_VERIFIED for x in document.operations
                    ):
                        document = run.status("PUBLISHED_NOT_VERIFIED", run.stage, run.reason)
    return _emit(document, args.status_file, env)


if __name__ == "__main__":
    raise SystemExit(main())
