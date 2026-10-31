"""Approved exact-package publication with durable recovery and no implicit retry.

The provenance callback fetches fresh API metadata for pinned ids; it returns evidence,
not a trust boolean. Existing provenance, gate, attach and storage verifiers decide.
"""

from __future__ import annotations

import argparse
import contextlib
import subprocess
import tempfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from talent_data.cli.ota import _enforce_gates_and_approval, verify_appended_releases
from talent_data.ota.automation.ci_signer import SecretError, Signer
from talent_data.ota.automation.prepare import PreparationBlocked, verify_staged_base_bytes
from talent_data.ota.automation.provenance import RequestRejected, verify_package_origin
from talent_data.ota.automation.requests import ReleaseRequest
from talent_data.ota.automation.state import (
    TERMINAL,
    Journal,
    JournalError,
    PreparedPublication,
    PublicationReceipt,
    RequestState,
    request_digest,
)
from talent_data.ota.catalog import catalog_entry, ensure_valid_succession, next_catalog
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import under_test_from_bytes
from talent_data.ota.models import CATALOG_PAYLOAD_TYPE, Channel, OtaCatalog, SignedEnvelope
from talent_data.ota.release_package import (
    STAGING_BASE_MANIFEST_NAME,
    STAGING_FILES,
    LoadedPackage,
    approval_record_at,
    approval_record_problems,
)
from talent_data.ota.signing import build_envelope, verify_der_signature
from talent_data.ota.store import (
    BlobStore,
    blob_key,
    catalog_key,
    ensure_catalog_references,
    upload_blobs,
    verify_live_envelope,
    write_catalog,
)
from talent_data.ota.verify import (
    OtaError,
    TrustedKey,
    pae,
    parse_strict,
    sha256_ref,
    strict_b64decode,
)


@dataclass(frozen=True)
class ProvenanceEvidence:
    run_metadata: dict[str, object]
    artifact_metadata: dict[str, object]
    archive_bytes: bytes


@dataclass(frozen=True)
class LiveSnapshot:
    envelope: bytes
    payload: bytes
    catalog: OtaCatalog


def read_live(
    store: BlobStore, *, keyring: Mapping[str, TrustedKey], game: str, now: datetime
) -> LiveSnapshot:
    envelope = store.get(catalog_key(Channel.PRODUCTION))
    catalog = verify_live_envelope(
        envelope, Channel.PRODUCTION, keyring=keyring, game=game, now=now
    )
    payload = strict_b64decode(parse_strict(SignedEnvelope, envelope).payload)
    return LiveSnapshot(envelope, payload, catalog)


def refresh_main(repo: Path) -> None:
    result = subprocess.run(
        ["git", "-C", str(repo), "fetch", "--quiet", "origin", "main"],
        capture_output=True,
        check=False,
    )
    if result.returncode:
        raise OtaPublisherError("current origin/main could not be read")


def approval_directory(request: ReleaseRequest, repo: Path, directory: Path) -> Path:
    refresh_main(repo)
    record = approval_record_at(repo, "origin/main", request.release_id)
    if record is None or approval_record_problems(
        record.decode("utf-8", "strict"),
        release_id=request.release_id,
        manifest_sha256=request.manifest_sha256,
        source_commit=request.source_commit,
    ):
        raise OtaPublisherError("current origin/main does not approve exact package")
    (directory / f"{request.release_id}.md").write_bytes(record)
    return directory


def recover_outcome(
    receipt: PublicationReceipt,
    prepared: PreparedPublication,
    *,
    store: BlobStore,
    keyring: Mapping[str, TrustedKey],
    now: datetime,
) -> PublicationReceipt:
    """Diagnose only. Exact target/base/other; this function never writes to origin."""
    try:
        live = read_live(store, keyring=keyring, game=receipt.request.game, now=now)
    except (OtaPublisherError, OtaError, OSError, ValueError):
        return receipt.evolve(state=RequestState.AMBIGUOUS, reason="OUTCOME_UNKNOWN")
    if live.payload == prepared.payload:
        try:
            ensure_catalog_references(store, live.catalog)
        except (OtaPublisherError, OSError, ValueError):
            return receipt.evolve(state=RequestState.AMBIGUOUS, reason="OUTCOME_UNKNOWN")
        return receipt.evolve(state=RequestState.PUBLISHED_NOT_VERIFIED, reason="S3_EXACT")
    if (
        receipt.state is not RequestState.AMBIGUOUS
        and sha256_ref(live.payload) == receipt.base_payload_sha256
    ):
        return receipt.evolve(state=RequestState.PREPARED, reason="NOT_WRITTEN")
    return receipt.evolve(state=RequestState.AMBIGUOUS, reason="OUTCOME_UNKNOWN")


def publish_request(
    request: ReleaseRequest,
    *,
    package: LoadedPackage,
    repo: Path,
    store: BlobStore,
    keyring: Mapping[str, TrustedKey],
    signer: Signer,
    receipt: PublicationReceipt,
    now: datetime,
    journal: Journal | None = None,
    provenance: Callable[[], ProvenanceEvidence] | None = None,
    key_id: str = "tf-prod-2026a",
) -> PublicationReceipt:
    """Publish exact approved bytes. S3 completion is PUBLISHED_NOT_VERIFIED.

    A separate public-origin verifier advances VERIFIED. Journal errors propagate and
    prohibit PUT. All external failure details are reduced to safe reason codes.
    """
    if journal is None:
        raise JournalError("durable publication journal is required")
    if receipt.request != request or receipt.request_digest != request_digest(request):
        raise JournalError("request and receipt differ")
    entries = journal.read_all()  # must verify even for terminal requests
    retained = entries.get(receipt.request_digest)
    run_id, attempt = receipt.run_id, receipt.attempt
    if retained is None and (
        receipt.state is not RequestState.PENDING or receipt.payload_sha256 is not None
    ):
        raise JournalError("new request must start with an unprepared PENDING receipt")
    prepared = None if retained is None else retained.prepared
    if retained is not None:
        receipt = retained.receipt
    if receipt.state in TERMINAL:
        return receipt
    receipt = receipt.evolve(run_id=run_id, attempt=attempt)
    if receipt.state in {RequestState.AMBIGUOUS, RequestState.WRITE_STARTED}:
        if prepared is None:
            raise JournalError("recovery has no exact signing work")
        outcome = recover_outcome(receipt, prepared, store=store, keyring=keyring, now=now)
        journal.save(outcome, prepared)
        return outcome  # diagnostic turn only; never blind retry
    if any(
        k != receipt.request_digest
        and v.receipt.state in {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}
        for k, v in entries.items()
    ):
        raise JournalError("another publication outcome is unresolved")
    if retained is None:
        journal.save(receipt)

    def save(state: RequestState, reason: str) -> PublicationReceipt:
        result = receipt.evolve(state=state, reason=reason)
        journal.save(result, prepared)
        return result

    if request.release_id == "1.60.1.70532-r1":
        return save(RequestState.STALE, "INITIAL_RELEASE")
    if provenance is None:
        return save(RequestState.BLOCKED, "CHECK_FAILED")

    def assess(directory: Path) -> tuple[LiveSnapshot, argparse.Namespace]:
        approvals = approval_directory(request, repo, directory)
        evidence = provenance()
        verify_package_origin(
            request,
            run_metadata=evidence.run_metadata,
            artifact_metadata=evidence.artifact_metadata,
            archive_bytes=evidence.archive_bytes,
            package=package,
        )
        live = read_live(store, keyring=keyring, game=request.game, now=now)
        active = next(
            r for r in live.catalog.releases if r.release_id == live.catalog.active_release_id
        )
        verify_staged_base_bytes(
            live.catalog,
            store.get(blob_key(active.manifest_sha256)),
            package.attachment(STAGING_BASE_MANIFEST_NAME),
            package.attachment(STAGING_FILES["report"]),
        )
        args = argparse.Namespace(repo=repo, releases_docs=approvals, main_ref="origin/main")
        under_test = under_test_from_bytes(package.manifest_bytes, repo)
        if (
            _enforce_gates_and_approval(
                args, under_test, store, live.catalog, now, channel=Channel.PRODUCTION
            )
            is not None
        ):
            raise OtaPublisherError("publication gates refused")
        return live, args

    def base_matches(live: LiveSnapshot) -> bool:
        active = next(
            x for x in live.catalog.releases if x.release_id == live.catalog.active_release_id
        )
        return (
            active.release_id == request.base_release_id
            and active.manifest_sha256 == request.base_manifest_sha256
            and (
                receipt.base_payload_sha256 is None
                or sha256_ref(live.payload) == receipt.base_payload_sha256
            )
        )

    with tempfile.TemporaryDirectory(prefix="ota-approval-") as temporary:
        directory = Path(temporary)
        try:
            live, args = assess(directory)
            if any(x.release_id == request.release_id for x in live.catalog.releases):
                return save(RequestState.STALE, "ALREADY_LISTED")
            if not base_matches(live):
                return save(RequestState.STALE, "LIVE_CHANGED")
            if prepared is None:
                update = next_catalog(
                    live.catalog,
                    game=request.game,
                    channel=Channel.PRODUCTION,
                    active_release_id=request.release_id,
                    append=catalog_entry(
                        package.manifest_bytes, package.manifest, published_at=now
                    ),
                    issued_at=now,
                )
                try:
                    upload_blobs(
                        store, {request.manifest_sha256: package.manifest_bytes, **package.blobs}
                    )
                    ensure_catalog_references(store, update.catalog)
                except (OtaPublisherError, OSError):
                    return save(RequestState.BLOCKED, "UPLOAD_FAILED")
                # Uploads can take minutes; revoked approval or provenance never reaches signer.
                fresh, args = assess(directory)
                if fresh.payload != live.payload:
                    return save(RequestState.STALE, "LIVE_CHANGED")
                ensure_valid_succession(fresh.catalog, update.catalog)
                verify_appended_releases(args, store, fresh.catalog, update.catalog, now)
                if key_id not in keyring:
                    return save(RequestState.BLOCKED, "SIGN_FAILED")
                try:
                    der = signer.sign(pae(CATALOG_PAYLOAD_TYPE, update.payload))
                    if not verify_der_signature(keyring[key_id].public_key, der, update.payload):
                        return save(RequestState.BLOCKED, "SIGN_FAILED")
                except (SecretError, OSError, ValueError):
                    return save(RequestState.BLOCKED, "SIGN_FAILED")
                prepared = PreparedPublication.create(
                    update.payload, base_sequence=live.catalog.sequence, der=der, key_id=key_id
                )
                receipt = receipt.evolve(
                    base_sequence=live.catalog.sequence,
                    base_payload_sha256=sha256_ref(live.payload),
                    target_sequence=update.catalog.sequence,
                    payload_sha256=sha256_ref(update.payload),
                    state=RequestState.PREPARED,
                    reason="READY",
                )
                journal.save(receipt, prepared)
            receipt = receipt.evolve(state=RequestState.WRITE_STARTED, reason="WRITE_STARTED")
            # Persist and read back before the final current authorization/live checks.
            journal.save(receipt, prepared)
            # Re-read all authorization/provenance immediately before activation.
            fresh, args = assess(directory)
            if not base_matches(fresh):
                return save(RequestState.STALE, "LIVE_CHANGED")
            catalog = parse_strict(OtaCatalog, prepared.payload)
            if prepared.key_id not in keyring or not verify_der_signature(
                keyring[prepared.key_id].public_key, prepared.der, prepared.payload
            ):
                return save(RequestState.BLOCKED, "SIGN_FAILED")
            envelope = build_envelope(prepared.payload, {prepared.key_id: prepared.der})
            verify_live_envelope(
                envelope, Channel.PRODUCTION, keyring=keyring, game=request.game, now=now
            )
            ensure_valid_succession(fresh.catalog, catalog)
            ensure_catalog_references(store, catalog)
            verify_appended_releases(args, store, fresh.catalog, catalog, now)
        except (
            PreparationBlocked,
            RequestRejected,
            OtaPublisherError,
            OtaError,
            OSError,
            ValueError,
        ):
            return save(RequestState.BLOCKED, "CHECK_FAILED")
        # Only exact signed live bytes resolve a lost response.
        with contextlib.suppress(OtaPublisherError, OSError):
            write_catalog(
                store,
                Channel.PRODUCTION,
                envelope,
                base_sequence=receipt.base_sequence,
                keyring=keyring,
                game=request.game,
                now=now,
            )
        receipt = recover_outcome(receipt, prepared, store=store, keyring=keyring, now=now)
        journal.save(receipt, prepared)
        return receipt
