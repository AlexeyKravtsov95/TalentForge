"""Committed-request reconciliation and read-only public convergence retries."""

from __future__ import annotations

import math
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from datetime import datetime, timedelta
from time import monotonic, sleep

import httpx

from talent_data.ota.automation.public_verify import PublicVerification, verify_publication
from talent_data.ota.automation.requests import ReleaseRequest
from talent_data.ota.automation.state import (
    TERMINAL,
    Journal,
    JournalError,
    PublicationReceipt,
    Receipt,
    RequestState,
    request_digest,
)
from talent_data.ota.models import OtaCatalog
from talent_data.ota.verify import TrustedKey


def _stale_reason(request: ReleaseRequest, live: OtaCatalog) -> str | None:
    if request.release_id == "1.60.1.70532-r1":
        return "INITIAL_RELEASE"
    if any(x.release_id == request.release_id for x in live.releases):
        return "ALREADY_LISTED"
    active = next((x for x in live.releases if x.release_id == live.active_release_id), None)
    if (
        live.game != request.game
        or active is None
        or active.release_id != request.base_release_id
        or active.manifest_sha256 != request.base_manifest_sha256
    ):
        return "LIVE_CHANGED"
    return None


def _validated(
    requests: Sequence[ReleaseRequest],
    receipts: Mapping[str, PublicationReceipt],
) -> tuple[ReleaseRequest, ...]:
    for key, value in receipts.items():
        try:
            checked = PublicationReceipt.model_validate(value.model_dump())
        except ValueError:
            raise JournalError("trusted receipt is invalid") from None
        if key != checked.request_digest:
            raise JournalError("trusted receipt index mismatch")
    unique: dict[str, ReleaseRequest] = {}
    for request in requests:
        checked_request = ReleaseRequest.model_validate(request.model_dump())
        digest = request_digest(checked_request)
        retained = receipts.get(digest)
        if retained is not None and retained.request != checked_request:
            raise JournalError("trusted request identity mismatch")
        unique[digest] = checked_request
    return tuple(unique.values())


def select_requests(
    requests: Sequence[ReleaseRequest],
    receipts: Mapping[str, PublicationReceipt],
    *,
    live: OtaCatalog,
) -> tuple[ReleaseRequest, ...]:
    """Pure selection; reconcile_requests durably records all excluded pending work."""
    unique = _validated(requests, receipts)
    counts = Counter(x.release_id for x in unique)
    selected = []
    for request in unique:
        receipt = receipts.get(request_digest(request))
        if receipt is not None and receipt.state in TERMINAL:
            continue
        # Unresolved writes must be diagnosed even if live moved to another release.
        if (
            receipt is not None
            and receipt.state in {RequestState.WRITE_STARTED, RequestState.AMBIGUOUS}
        ) or (counts[request.release_id] == 1 and _stale_reason(request, live) is None):
            selected.append(request)
    return tuple(selected)


def reconcile_requests(
    requests: Sequence[ReleaseRequest],
    *,
    journal: Journal,
    live: OtaCatalog,
    run_id: int,
    attempt: int,
) -> tuple[ReleaseRequest, ...]:
    """Record discovery/STALE/BLOCKED independently of webhooks or package availability.

    Input is the complete set of committed requests from trusted main (Task6 discovers
    git paths and proves CI authorization); no artifact lookup is needed here.
    """
    receipts = {
        k: v.receipt
        for k, v in journal.read_all().items()
        if isinstance(v.receipt, PublicationReceipt)
    }
    unique = _validated(requests, receipts)
    selected = select_requests(unique, receipts, live=live)
    counts = Counter(x.release_id for x in unique)
    discovered = {request_digest(x) for x in unique}
    for digest, retained_old in receipts.items():
        if (
            digest not in discovered
            and retained_old.state not in TERMINAL
            and retained_old.state
            not in {
                RequestState.WRITE_STARTED,
                RequestState.AMBIGUOUS,
            }
        ):
            journal.save(retained_old.evolve(state=RequestState.STALE, reason="REQUEST_REMOVED"))
    for request in unique:
        old = receipts.get(request_digest(request))
        if old is not None and (
            old.state in TERMINAL
            or old.state
            in {
                RequestState.WRITE_STARTED,
                RequestState.AMBIGUOUS,
            }
        ):
            continue
        receipt = old or PublicationReceipt.pending(request, run_id=run_id, attempt=attempt)
        receipt = receipt.evolve(run_id=run_id, attempt=attempt)
        reason = _stale_reason(request, live)
        if counts[request.release_id] > 1:
            receipt = receipt.evolve(state=RequestState.BLOCKED, reason="CHECK_FAILED")
        elif reason is not None:
            receipt = receipt.evolve(state=RequestState.STALE, reason=reason)
        journal.save(receipt)
    return selected


def resign_due(catalog: OtaCatalog, *, now: datetime) -> bool:
    """Scheduling predicate only; callers must verify signature/references first."""
    return catalog.issued_at <= now < catalog.expires_at <= now + timedelta(days=15)


def verify_after_write(
    client: httpx.Client,
    *,
    origin: str,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
    expected_payload_sha256: str,
    budget_seconds: float = 600,
    sleeper: Callable[[float], None] = sleep,
    clock: Callable[[], float] = monotonic,
) -> PublicVerification:
    """Only retry verification. Each fetch checks a shared monotonic deadline.

    A zero budget permits no network work. Per-fetch timeout is capped by remaining
    time; an in-flight transport phase may overrun by its last timeout. No further
    files/attempts start after expiry. Actual elapsed time advances validity checks.
    """
    if not math.isfinite(budget_seconds) or budget_seconds < 0:
        raise ValueError("verification budget must be finite and nonnegative")
    started = clock()
    deadline = started + budget_seconds
    result = PublicVerification(False, None, None, None, "VERIFY_BUDGET_EXHAUSTED")
    while clock() < deadline:
        result = verify_publication(
            client,
            origin=origin,
            keyring=keyring,
            game=game,
            now=now + timedelta(seconds=max(0.0, clock() - started)),
            expected_payload_sha256=expected_payload_sha256,
            deadline=deadline,
            clock=clock,
        )
        if result.ok:
            return result
        remaining = deadline - clock()
        if remaining <= 0:
            break
        sleeper(min(30.0, remaining))
    return result


def confirm_publication(journal: Journal, digest: str, result: PublicVerification) -> Receipt:
    """Only full public proof of the exact retained payload advances terminal state."""
    entry = journal.load(digest)
    if entry is None:
        raise JournalError("public confirmation has no retained operation")
    receipt = entry.receipt
    if (
        receipt.state is RequestState.PUBLISHED_NOT_VERIFIED
        and result.ok
        and result.catalog_payload_sha256 == receipt.payload_sha256
    ):
        receipt = receipt.evolve(state=RequestState.VERIFIED, reason="PUBLIC_VERIFIED")
        journal.save(receipt, entry.prepared)
    return receipt
