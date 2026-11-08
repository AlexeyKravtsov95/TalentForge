"""Read-only proof of one stable public catalog and every referenced release/file."""

from __future__ import annotations

from collections import OrderedDict, deque
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from contextlib import closing
from contextvars import copy_context
from dataclasses import dataclass
from datetime import datetime, timedelta
from time import monotonic
from urllib.parse import urlsplit

import httpx

from talent_data.models.manifest import DatasetManifest
from talent_data.ota import io_metrics
from talent_data.ota.models import (
    MAX_CATALOG_BYTES,
    MAX_RELEASE_BYTES,
    Channel,
    FileRole,
    ReleaseFile,
)
from talent_data.ota.store import blob_key, catalog_key
from talent_data.ota.verify import (
    SUPPORTED_DATA_LEVEL,
    OtaError,
    OtaErrorCode,
    TrustedKey,
    parse_strict,
    sha256_ref,
    verify_catalog,
    verify_release_files,
    verify_release_manifest,
)


@dataclass(frozen=True)
class PublicVerification:
    ok: bool
    catalog_payload_sha256: str | None
    active_release_id: str | None
    sequence: int | None
    problem: str | None
    retryable: bool = False


class _PublicFailure(Exception):
    def __init__(self, problem: str, *, retryable: bool = False) -> None:
        super().__init__(problem)
        self.retryable = retryable


def _require(condition: bool, problem: str, *, retryable: bool = False) -> None:
    if not condition:
        raise _PublicFailure(problem, retryable=retryable)


def _fetch_once_unobserved(
    client: httpx.Client,
    url: str,
    limit: int,
    *,
    remaining: Callable[[], float] = lambda: 30.0,
) -> bytes:
    # Override defaults on even a credentialed/follow-redirects client supplied by a caller.
    timeout = min(15.0, remaining())
    _require(timeout > 0, "VERIFY_BUDGET_EXHAUSTED")
    request = httpx.Request(
        "GET",
        url,
        headers={"Accept-Encoding": "identity"},
        extensions={
            "timeout": {
                "connect": min(5.0, timeout),
                "read": timeout,
                "write": timeout,
                "pool": timeout,
            }
        },
    )
    with closing(client.send(request, auth=None, follow_redirects=False, stream=True)) as response:
        if response.status_code != 200:
            raise _PublicFailure(
                f"HTTP_STATUS: {response.status_code}",
                retryable=response.status_code in {404, 408, 429}
                or 500 <= response.status_code < 600,
            )
        if response.headers.get("content-encoding"):
            raise _PublicFailure("CONTENT_ENCODING: expected identity")
        declared = response.headers.get("content-length")
        if declared is not None:
            if not declared.isascii() or not declared.isdecimal():
                raise _PublicFailure("CONTENT_LENGTH: invalid length")
            if int(declared) > limit:
                raise _PublicFailure("SIZE_LIMIT: declared body exceeds pin/limit")
        body = bytearray()
        for chunk in response.iter_raw():
            _require(remaining() > 0, "VERIFY_BUDGET_EXHAUSTED")
            if len(body) + len(chunk) > limit:
                raise _PublicFailure("SIZE_LIMIT: received body exceeds pin/limit")
            body.extend(chunk)
        if declared is not None and int(declared) != len(body):
            raise _PublicFailure("CONTENT_LENGTH: truncated body")
        return bytes(body)


def _fetch_once(
    client: httpx.Client,
    url: str,
    limit: int,
    *,
    remaining: Callable[[], float],
    retry: bool = False,
) -> bytes:
    body = None
    try:
        body = _fetch_once_unobserved(client, url, limit, remaining=remaining)
        return body
    finally:
        io_metrics.count(
            "GET",
            bytes=len(body) if body is not None else 0,
            retry=retry,
            sha_ref=io_metrics.object_hash(urlsplit(url).path),
        )
        if body is None:
            io_metrics.incomplete()


def _fetch(
    client: httpx.Client, url: str, limit: int, *, remaining: Callable[[], float] = lambda: 30.0
) -> bytes:
    started = monotonic()

    def budget() -> float:
        return min(remaining(), 30.0 - (monotonic() - started))

    for attempt in range(3):
        try:
            return _fetch_once(client, url, limit, remaining=budget, retry=attempt > 0)
        except (httpx.TransportError, _PublicFailure) as exc:
            transient = isinstance(exc, httpx.TransportError) or exc.retryable
            if not transient or attempt == 2 or budget() <= 0:
                raise
    raise AssertionError("read attempts exhausted")  # pragma: no cover


class _BlobCache:
    """Coordinator-owned cache with pinned current-release bytes and bounded futures.

    The reference verifier retains one release's bodies while checking relations.
    Pin those bodies until the next manifest; only older-release entries may be
    evicted. Reserved response lengths count against the same wire-byte budget.
    """

    def __init__(
        self,
        fetch: Callable[[ReleaseFile], bytes],
        *,
        read_workers: int = 8,
        max_bytes: int = MAX_RELEASE_BYTES,
    ) -> None:
        if type(read_workers) is not int or not 1 <= read_workers <= 8:
            raise ValueError("read_workers must be between 1 and 8")
        self._fetch = fetch
        self._workers = read_workers
        self._max_bytes = max_bytes
        self._pool = ThreadPoolExecutor(max_workers=read_workers, thread_name_prefix="ota-public")
        self._bodies: OrderedDict[str, bytes] = OrderedDict()
        self._pending: dict[str, tuple[Future[bytes], int]] = {}
        self._lengths: dict[str, int] = {}
        self._queue: deque[ReleaseFile] = deque()
        self._pinned: set[str] = set()
        self._cached_bytes = self._reserved_bytes = 0

    def prepare(self, files: Sequence[ReleaseFile]) -> None:
        unique = {}
        for file in files:
            previous = self._lengths.setdefault(file.sha256, file.byte_length)
            _require(previous == file.byte_length, "LENGTH_CONFLICT: inconsistent reference pins")
            unique[file.sha256] = file
        _require(not self._pending, "CACHE_STATE: previous release was not consumed")
        _require(
            sum(f.byte_length for f in unique.values()) <= self._max_bytes,
            "SIZE_LIMIT: release cache pins exceed budget",
        )
        self._pinned = set(unique)
        self._queue = deque(unique.values())
        self._fill()

    def _fill(self) -> None:
        while self._queue and len(self._pending) < self._workers:
            if any(
                future.done() and future.exception() is not None
                for future, _ in self._pending.values()
            ):
                return
            file = self._queue[0]
            if file.sha256 in self._bodies or file.sha256 in self._pending:
                self._queue.popleft()
                continue
            while self._cached_bytes + self._reserved_bytes + file.byte_length > self._max_bytes:
                victim = next((sha for sha in self._bodies if sha not in self._pinned), None)
                if victim is None:
                    return
                self._cached_bytes -= len(self._bodies.pop(victim))
            self._queue.popleft()
            self._reserved_bytes += file.byte_length
            self._pending[file.sha256] = (
                self._pool.submit(copy_context().run, self._fetch, file),
                file.byte_length,
            )

    def read(self, file: ReleaseFile) -> bytes:
        if file.sha256 not in self._bodies:
            self._fill()
            _require(file.sha256 in self._pending, "CACHE_STATE: missing pinned response")
            future, reserved = self._pending.pop(file.sha256)
            # Keep the reservation until the complete response is available.
            body = future.result()
            _require(len(body) <= reserved, "SIZE_LIMIT: response exceeds reservation")
            self._reserved_bytes -= reserved
            self._bodies[file.sha256] = body
            self._cached_bytes += len(body)
        self._bodies.move_to_end(file.sha256)
        body = self._bodies[file.sha256]
        self._fill()
        return body

    def close(self) -> None:
        for future, _ in self._pending.values():
            future.cancel()
        self._pool.shutdown(wait=True, cancel_futures=True)
        self._pending.clear()
        self._bodies.clear()
        self._reserved_bytes = self._cached_bytes = 0


@io_metrics.instrument("PUBLIC_VERIFY")
def verify_publication(
    client: httpx.Client,
    *,
    origin: str,
    keyring: Mapping[str, TrustedKey],
    game: str,
    now: datetime,
    expected_payload_sha256: str | None = None,
    deadline: float | None = None,
    clock: Callable[[], float] = monotonic,
    read_workers: int = 8,
) -> PublicVerification:
    """Verify all catalog entries, then re-read the signed payload to detect a race.

    Downloads are bounded by catalog/manifest pins. A blob cache is capped at one
    maximum release size. Nothing is persisted on failure. Same-build identity is checked
    across retained revisions too.
    """
    started = clock()

    def remaining() -> float:
        return 30.0 if deadline is None else deadline - clock()

    def fetch(url: str, limit: int) -> bytes:
        return _fetch(client, url, limit, remaining=remaining)

    def current_time() -> datetime:
        return now + timedelta(seconds=max(0.0, clock() - started))

    digest = active = None
    sequence = None

    def fetch_file(file: ReleaseFile) -> bytes:
        body = fetch(f"{origin}/{blob_key(file.sha256)}", file.byte_length)
        if len(body) != file.byte_length:
            raise OtaError(OtaErrorCode.BLOB_SIZE_MISMATCH, f"{file.path}: pinned length mismatch")
        if sha256_ref(body) != file.sha256:
            raise OtaError(OtaErrorCode.BLOB_HASH_MISMATCH, f"{file.path}: pinned hash mismatch")
        return body

    cache = _BlobCache(fetch_file, read_workers=read_workers)
    try:
        parsed = urlsplit(origin)
        _require(
            not (
                parsed.scheme != "https"
                or not parsed.hostname
                or parsed.username is not None
                or parsed.password is not None
                or parsed.query
                or parsed.fragment
            ),
            "ORIGIN_INVALID: expected anonymous HTTPS origin",
        )
        origin = origin.rstrip("/")
        catalog_url = f"{origin}/{catalog_key(Channel.PRODUCTION)}"
        verified = verify_catalog(
            fetch(catalog_url, MAX_CATALOG_BYTES),
            keyring=keyring,
            game=game,
            channel=Channel.PRODUCTION,
            now=current_time(),
            last_seen=None,
        )
        digest = verified.payload_sha256
        _require(
            expected_payload_sha256 is None or digest == expected_payload_sha256,
            "EXPECTED_PAYLOAD_MISMATCH: public catalog has not converged",
            retryable=True,
        )
        catalog = verified.catalog
        active, sequence = catalog.active_release_id, catalog.sequence
        known: DatasetManifest | None = None
        for entry in catalog.releases:
            raw = fetch(f"{origin}/{blob_key(entry.manifest_sha256)}", entry.manifest_byte_length)
            manifest = verify_release_manifest(
                raw, entry, game=game, supported_data_level=SUPPORTED_DATA_LEVEL
            )
            dataset_raw: bytes | None = None
            cache.prepare(manifest.files)

            def read(file: ReleaseFile) -> bytes:
                nonlocal dataset_raw
                body = cache.read(file)
                if file.role is FileRole.DATASET_MANIFEST:
                    dataset_raw = body
                return body

            previous = (
                known if known is not None and known.client_build == entry.client_build else None
            )
            verify_release_files(manifest, read, known_same_build_manifest=previous)
            assert dataset_raw is not None  # required and verified by reference verifier
            known = parse_strict(DatasetManifest, dataset_raw)
        after = verify_catalog(
            fetch(catalog_url, MAX_CATALOG_BYTES),
            keyring=keyring,
            game=game,
            channel=Channel.PRODUCTION,
            now=current_time(),
            last_seen=None,
        )
        _require(
            after.payload_sha256 == digest,
            "SNAPSHOT_CHANGED: catalog changed during verification",
            retryable=True,
        )
        return PublicVerification(True, digest, active, sequence, None)
    except (OtaError, _PublicFailure, httpx.HTTPError, ValueError) as exc:
        # Transport messages can include supplied client details; emit only the type.
        problem = type(exc).__name__ if isinstance(exc, httpx.HTTPError) else str(exc)
        transient = isinstance(exc, httpx.TransportError) or (
            isinstance(exc, _PublicFailure) and exc.retryable
        )
        return PublicVerification(False, digest, active, sequence, problem, transient)
    finally:
        cache.close()
